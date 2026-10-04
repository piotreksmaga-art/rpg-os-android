package com.rpgos.app

internal data class RegisteredTemporalOwner(val versionUid:String,val owner:WorldProcessOwnerPort) {
    init { require(versionUid.isNotBlank() && owner.ownerUid != PHASE60_FOREGROUND_OWNER) }
}

internal sealed interface ProductionTemporalExecutionResult {
    data class Completed(val work:TemporalExecutionResult,val change:TemporalStateChange,
                         val effects:List<VerifiedMechanicsCommandEffect>,val npcBrains:List<NpcBrainChange> = emptyList(),
                         val worldChanges:List<WorldSimulationChange> = emptyList(),
                         val backgroundChanges:List<PlayerDomainChangePayload> = emptyList()):ProductionTemporalExecutionResult
    data class Rejected(val reasonUid:String):ProductionTemporalExecutionResult
}

/** The same bounded processor is used by ordinary actions and long actions. No slice commits. */
internal class Phase60ProductionExecution(
    private val checkpoints:TemporalCheckpointPort,
    private val currentScope:()->TemporalScope,
    private val owners:List<RegisteredTemporalOwner> = emptyList(),
    private val effectPolicy:TemporalEffectPolicyPort = TemporalEffectPolicyPort.COMPLETION_ONLY,
    private val externalEvaluation:TemporalExternalEvaluationPort? = null
) {
    fun execute(request:ChatTurnRequest, timing:ProductionTimeResult.Ready, snapshot:TemporalReadSnapshot,
                effects:List<VerifiedMechanicsCommandEffect>, cancelled:()->Boolean):ProductionTemporalExecutionResult {
        fun reject(reason:String):ProductionTemporalExecutionResult.Rejected {
            checkpoints.remove(request.campaignUid,request.commandUid)
            return ProductionTemporalExecutionResult.Rejected(reason)
        }
        require(timing.schedule.isNotEmpty())
        if(snapshot.state.processStates.any{it.ownerUid==PHASE60_FOREGROUND_OWNER}) return reject("P60:RESERVED_PROCESS_OWNER")
        val policies=effects.associate{it.effectUid to effectPolicy.policy(it)}
        val payload=ApplyVerifiedMechanicsCommandPayload("P60:PREPARED",effects,if(effects.isEmpty())timing.change else null)
        val specification=coreCommandCodecs().getValue(PlayerCommandKinds.APPLY_VERIFIED_MECHANICS).encodeUntyped(payload).toString()
        val fingerprint=phase60Hash(specification+"|"+policies.toSortedMap()+"|"+owners.sortedBy{it.owner.ownerUid}.joinToString{"${it.owner.ownerUid}:${it.versionUid}"})
        val foreground=object:WorldProcessOwnerPort {
            override val ownerUid=PHASE60_FOREGROUND_OWNER
            override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult =
                if(input.deadlines.isNotEmpty()) TemporalOwnerResult.Unsupported("P60:FOREGROUND_DEADLINE_NOT_SUPPORTED")
                else TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid,1,fingerprint))
        }
        val processor=Phase60TimeProcessor(listOf(foreground)+owners.map{it.owner})
        val initial=processor.begin(snapshot.scope,request.commandUid,snapshot.state.time,timing.schedule.map{it.action},
            snapshot.state.deadlines,snapshot.state.processStates).copy(executionFingerprint=fingerprint)
        val saved=checkpoints.load(request.campaignUid,request.commandUid)
        if(saved!=null && (saved.scope!=initial.scope || saved.schedule!=initial.schedule || saved.executionFingerprint!=fingerprint ||
                saved.initialDeadlines!=initial.initialDeadlines || saved.initialOwnerStates!=initial.initialOwnerStates))
            return reject("P60:CHECKPOINT_SPECIFICATION_CHANGED")
        // CACHE is not authority. On restart deterministically replay the speculative prefix
        // from canonical input/rules; never turn cached deltas or cached owner text into truth.
        val answers=linkedMapOf<String,TemporalOwnerResult.Evaluated>()
        var result=processor.advance(initial,currentScope(),cancelled,maxBoundaries=32,maxWallMillis=50)
        while(result.reason in setOf(TemporalStopReason.YIELDED,TemporalStopReason.OWNER_EVALUATION_REQUIRED)) {
            if(result.reason==TemporalStopReason.OWNER_EVALUATION_REQUIRED) {
                if(answers.size>=32)return reject("P62:EVALUATION_BUDGET")
                val pending=result.pendingEvaluation ?: return reject("P62:EVALUATION_REQUEST_MISSING")
                val evaluator=externalEvaluation ?: return reject("P62:EVALUATION_OWNER_MISSING")
                if(cancelled())return reject("P60:CANCELLED")
                val answer=evaluator.evaluate(pending,cancelled)
                if(cancelled())return reject("P60:CANCELLED")
                if(currentScope()!=initial.scope)return reject("P60:STALE_HISTORY")
                when(answer) {
                    is TemporalEvaluationResponse.Unavailable -> return reject(answer.reasonUid)
                    is TemporalEvaluationResponse.Yielded -> {
                        if(answer.requestFingerprint!=pending.fingerprint)return reject("P64:EVALUATION_CORRELATION")
                        checkpoints.save(result.checkpoint)
                        Thread.yield()
                        continue
                    }
                    is TemporalEvaluationResponse.Accepted -> {
                        if(answer.requestFingerprint!=pending.fingerprint)return reject("P62:EVALUATION_CORRELATION")
                        answers[pending.fingerprint]=answer.result
                    }
                }
            }
            checkpoints.save(result.checkpoint)
            Thread.yield()
            result=processor.advance(result.checkpoint,currentScope(),cancelled,maxBoundaries=32,maxWallMillis=50,evaluations=answers)
        }
        if(!result.readyForAdmission) return reject("P60:${result.reason.name}")
        if(cancelled()) return reject("P60:CANCELLED")
        checkpoints.save(result.checkpoint)
        val work=result.checkpoint
        val processStates=work.ownerStates.values.filter{it.ownerUid!=PHASE60_FOREGROUND_OWNER}
        val finalDeadlines=work.deadlines.associateBy { it.uid }.toMutableMap()
        work.candidateChanges.filterIsInstance<BackgroundProcessChange>().forEach { receipt->
            receipt.deadlineRemovals.forEach { uid->
                val old=finalDeadlines[uid]
                if(old!=null && old.ownerUid!=NpcDutyDeadlineProcess.OWNER)return reject("P64:DEADLINE_OWNER_MISMATCH")
                finalDeadlines.remove(uid)
            }
            receipt.deadlineAdds.forEach { deadline->
                if(deadline.ownerUid!=NpcDutyDeadlineProcess.OWNER || deadline.due<=work.reached)return reject("P64:DEADLINE_NOT_FUTURE")
                val old=finalDeadlines[deadline.uid]
                if(old!=null && old!=deadline)return reject("P64:DEADLINE_IDENTITY_CONFLICT")
                finalDeadlines[deadline.uid]=deadline
            }
        }
        val change=TemporalStateChange(request.campaignUid,snapshot.state.version,snapshot.state.time,work.reached,
            Phase60ProcessStateCodec.encode(processStates),Phase60DeadlineCodec.encode(finalDeadlines.values.toList()),result.reason.name,
            Phase60ExecutionReport.encode(Phase60ExecutionReport.from(work)))
        val selected=try { Phase60SegmentEffects.select(effects,work,policies) }
            catch(_:IllegalArgumentException){return reject("P60:EFFECT_TIMING_RULE_REJECTED")}
        val npcBrains=work.candidateChanges.filterIsInstance<NpcBrainChange>()
        val worldChanges=work.candidateChanges.filterIsInstance<WorldSimulationChange>()
        // Preserve only payloads actually sealed by a Phase64 receipt. Other temporal
        // owners still use their existing adapters, even in a mixed-domain boundary.
        val receipts=work.candidateChanges.filterIsInstance<BackgroundProcessChange>()
        val remaining=receipts.flatMap { it.consequenceFingerprints }.groupingBy { it }.eachCount().toMutableMap()
        val backgroundChanges=mutableListOf<PlayerDomainChangePayload>()
        val scalarChanges=mutableListOf<PlayerDomainChangePayload>()
        work.candidateChanges.forEach { payload->
            if(payload is BackgroundProcessChange)backgroundChanges+=payload
            else if(payload !is NpcBrainChange && payload !is WorldSimulationChange) {
                val fingerprint=Phase64BackgroundCodec.fingerprint(payload)
                val count=remaining[fingerprint]?:0
                if(count>0) { backgroundChanges+=payload;remaining[fingerprint]=count-1 }
                else scalarChanges+=payload
            }
        }
        val background=try { Phase60SegmentEffects.background(phase60CoalesceChanges(scalarChanges),work) }
            catch(_:IllegalStateException){return reject("P60:PROCESS_EFFECT_ADAPTER_REQUIRED")}
        return ProductionTemporalExecutionResult.Completed(result,change,selected+background+work.candidateEffects,npcBrains,worldChanges,backgroundChanges)
    }
}

/** Canonical scalar rows hold the final delta, even if a process was evaluated in many slices. */
internal fun phase60CoalesceChanges(changes:List<PlayerDomainChangePayload>):List<PlayerDomainChangePayload> {
    val groups=linkedMapOf<Any,MutableList<PlayerDomainChangePayload>>()
    changes.forEachIndexed { index,payload ->
        val key=when(payload) {
            is ResourceChange->listOf("RESOURCE",payload.subject.kindUid,payload.subject.uid,payload.resourceUid)
            is MechanicalTrackChange->listOf("TRACK",payload.subject.kindUid,payload.subject.uid,payload.trackUid)
            else->index
        }
        groups.getOrPut(key){mutableListOf()}.add(payload)
    }
    return groups.values.mapNotNull { group ->
        when(val first=group.first()) {
            is ResourceChange->group.fold(0L){sum,p->Math.addExact(sum,(p as ResourceChange).delta.units)}.takeIf{it!=0L}?.let{first.copy(delta=ExactLongDelta.of(it))}
            is MechanicalTrackChange->group.fold(0L){sum,p->Math.addExact(sum,(p as MechanicalTrackChange).delta.units)}.takeIf{it!=0L}?.let{first.copy(delta=ExactLongDelta.of(it))}
            else->first
        }
    }
}
