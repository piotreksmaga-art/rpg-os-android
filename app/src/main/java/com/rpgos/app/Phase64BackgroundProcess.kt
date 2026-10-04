package com.rpgos.app

/** One owner serializes competing domain decisions at the same deadline. All outputs remain
 * speculative until the ordinary TurnTransaction; no independently committed micro-ticks. */
internal class Phase64BackgroundProcessOwner(
    private val scope:BackgroundProcessEvaluationScope,
    private val processes:List<BackgroundProcessInstance>,
    private val definitions:Map<Pair<String,Int>,BackgroundProcessDefinition>,
    private val dependency:(String)->BackgroundProcessInstance?,
    private val reads:BackgroundWorldReadPort,
    adapters:List<BackgroundDomainAdapter>,
    private val currentScope:()->TemporalScope={scope.temporal},
    private val frontier:BackgroundProcessFrontierPort? = null
):WorldProcessOwnerPort {
    override val ownerUid=OWNER
    private val adaptersByDomain=buildMap { adapters.forEach { adapter->adapter.domains.forEach { domain->require(put(domain,adapter)==null) } } }
    init { require(processes.size<=4096 && processes.map { it.uid }.distinct().size==processes.size) }
    /** CACHE/REBUILDABLE only. Restart discards this speculative cursor and replays canonical input. */
    private data class EvaluationCursor(
        val current:List<BackgroundProcessInstance>,val candidates:List<BackgroundProcessInstance>,
        val scheduled:MutableSet<String>,val changes:MutableList<PlayerDomainChangePayload>,
        val effects:MutableList<VerifiedMechanicsCommandEffect>,val effectOverlay:MutableList<PlayerDomainChangePayload>,
        val next:MutableList<WorldProcessDeadline>,val claimed:MutableMap<DomainRef,Long>,
        val delegations:MutableList<TemporalOwnerDelegation>,val peerStates:MutableMap<String,TemporalOwnerState>,var offset:Int=0
    )
    private fun evaluateBatch(input:TemporalOwnerInput,cancelled:()->Boolean,resume:EvaluationCursor?=null,
        checkpoint:((EvaluationCursor)->Unit)?=null):TemporalOwnerResult {
        if(input.scope!=scope.temporal || currentScope()!=scope.temporal)return TemporalOwnerResult.Unsupported("P64:STALE_HISTORY")
        if(input.actions.isNotEmpty())return TemporalOwnerResult.Unsupported("P64:FOREGROUND_OWNER_REQUIRED")
        val overlay=input.stagedChanges.filterIsInstance<BackgroundProcessChange>().associate { it.process.uid to it.process }
        val current=resume?.current?:(processes.associateBy { it.uid }+overlay).values.toList()
        val scheduled=resume?.scheduled?:input.previous?.canonicalValue?.let { kotlinx.serialization.json.Json.parseToJsonElement(it).let { value->
            (value as kotlinx.serialization.json.JsonObject)["scheduled"]?.let { rows->(rows as kotlinx.serialization.json.JsonArray).map { (it as kotlinx.serialization.json.JsonPrimitive).content }.toMutableSet() }
        } }?:mutableSetOf()
        // Initiation already committed these deadlines. Never schedule a second copy on
        // the first ordinary turn after opening or restarting the campaign.
        scheduled+=input.deadlineView.filter { it.ownerUid==OWNER }.map { it.uid }
        val pending=current.filter { it.status in setOf(BackgroundProcessStatus.ACTIVE,BackgroundProcessStatus.BLOCKED) && it.due<=input.through }
            .sortedWith(compareBy<BackgroundProcessInstance>{it.due}.thenByDescending { definitions[it.definitionUid to it.definitionVersion]?.priority?:0 }.thenBy { it.uid })
            .toMutableList()
        val candidates=resume?.candidates?:buildList {
            while(pending.isNotEmpty()) {
                val uids=pending.map { it.uid }.toSet()
                // Same-boundary dependencies settle before their consumers. A cycle remains
                // explicitly blocked; it cannot produce an arbitrary partial consequence.
                val ready=pending.firstOrNull { p->p.dependencyUids.none { it in uids } }?:pending.first()
                add(ready);pending.remove(ready)
            }
        }
        val changes=resume?.changes?:mutableListOf<PlayerDomainChangePayload>();val effects=resume?.effects?:mutableListOf<VerifiedMechanicsCommandEffect>()
        val effectOverlay=resume?.effectOverlay?:input.stagedEffects.flatMap { effect->
            (MechanicalEffectMaterializer.materialize(effect) as? MechanicalEffectMaterializationResult.Materialized)?.changes?.map { it.payload }
                ?:return TemporalOwnerResult.Unsupported("P64:STAGED_EFFECT_NOT_MATERIALIZED")
        }.toMutableList()
        val next=resume?.next?:mutableListOf<WorldProcessDeadline>();val claimed=resume?.claimed?:linkedMapOf<DomainRef,Long>()
        val delegations=resume?.delegations?:mutableListOf<TemporalOwnerDelegation>()
        val peerStates=resume?.peerStates?:input.peerStates.toMutableMap()
        val cursor=resume?:EvaluationCursor(current,candidates,scheduled,changes,effects,effectOverlay,next,claimed,delegations,peerStates)
        var sliceStart=System.nanoTime();var sliceUnits=0
        while(cursor.offset<candidates.size) {
            if(sliceUnits>=32 || System.nanoTime()-sliceStart>=50_000_000L) {
                if(checkpoint!=null) {
                    checkpoint(cursor)
                    return TemporalOwnerResult.EvaluationRequired("P64:PROCESS_SLICE_YIELDED")
                }
                Thread.yield();sliceStart=System.nanoTime();sliceUnits=0
                if(currentScope()!=scope.temporal)return TemporalOwnerResult.Unsupported("P64:STALE_HISTORY")
            }
            sliceUnits++
            val process=candidates[cursor.offset]
            if(cancelled())return TemporalOwnerResult.Unsupported("P60:CANCELLED")
            val definition=definitions[process.definitionUid to process.definitionVersion]?:return TemporalOwnerResult.Unsupported("P64:RULE_REQUIRED")
            val staged=input.stagedChanges+changes+effectOverlay
            val dependencyReady=process.dependencyUids.all { uid->
                val latest=changes.filterIsInstance<BackgroundProcessChange>().lastOrNull { it.process.uid==uid }?.process?:overlay[uid]?:dependency(uid)
                latest?.status==BackgroundProcessStatus.COMPLETED
            }
            val passive=definition.domain=="POPULATION" && definition.operation in setOf("AGE","DEATH") || definition.domain=="EPIDEMIC" && definition.operation=="EXPOSURE"
            val playerInitiated=process.parameters["p64_start_command_uid"]?.isNotBlank()==true &&
                process.parameters["p64_initiator_kind_uid"]=="PLAYER" && process.parameters["p64_initiator_uid"]==process.actor.uid &&
                process.parameters["p64_start_proof_uid"]?.startsWith(Phase64ProcessActivation.START_PROOF)==true
            val evaluationReads=reads.forEvaluation(input.copy(stagedChanges=staged,peerStates=peerStates.toMap()),cancelled)
            val outcome=if(!dependencyReady)backgroundBlocked("P64:DEPENDENCY_NOT_COMPLETED") else if(process.actor.kindUid=="PLAYER" && !passive && !playerInitiated)
                backgroundBlocked("P64:ACTIVE_PLAYER_AGENCY") else adaptersByDomain[definition.domain]?.evaluate(definition,process,scope,input.through,evaluationReads,staged)
                    ?:return TemporalOwnerResult.Unsupported("P64:DOMAIN_OWNER_REQUIRED")
            if(cancelled())return TemporalOwnerResult.Unsupported("P60:CANCELLED")
            if(currentScope()!=scope.temporal)return TemporalOwnerResult.Unsupported("P64:STALE_HISTORY")
            if(outcome.changes.any { it is TemporalStateChange || it is BackgroundProcessChange })return TemporalOwnerResult.Unsupported("P64:FORBIDDEN_DOMAIN_CHANGE")
            val amounts=outcome.claims.groupBy { it.resource }.mapValues { (_,v)->v.fold(0L) { sum,c->Math.addExact(sum,c.quantity) } }
            val conflict=amounts.any { (ref,quantity)-> val capacity=reads.available(ref,staged)
                capacity==null || quantity>Math.subtractExact(capacity,claimed[ref]?:0L) }
            val resolved=if(conflict)backgroundBlocked("P64:RESOURCE_CONFLICT") else outcome
            val existing=(input.stagedChanges+changes+effectOverlay).map(Phase64BackgroundCodec::fingerprint).groupingBy { it }.eachCount()
            if(resolved.existingConsequenceFingerprints.groupingBy { it }.eachCount().any { (hash,count)->count>(existing[hash]?:0) })
                return TemporalOwnerResult.Unsupported("P64:EXISTING_CONSEQUENCE_NOT_BOUND")
            if(resolved.status!=BackgroundProcessStatus.COMPLETED && (resolved.changes.isNotEmpty() || resolved.effects.isNotEmpty()))
                return TemporalOwnerResult.Unsupported("P64:NONCOMPLETED_EFFECT_REQUIRES_PARTIAL_RULE")
            val due=if(resolved.status==BackgroundProcessStatus.BLOCKED)WorldTimeTick(Math.addExact(input.through.milliseconds,definition.durationMillis)) else process.due
            val after=process.copy(version=Math.addExact(process.version,1),status=resolved.status,due=due,
                progressUnits=Math.addExact(process.progressUnits,resolved.progressUnits),reasonUid=resolved.reasonUid)
            val event="P64-EVIDENCE:${phase63Hash("${process.uid}|${process.version}|${definition.version}|${input.through.milliseconds}")}"
            val evidence=WorldProcessEvidence(event,process.uid,definition.uid,definition.version,resolved.sourceUids,input.through,resolved.reasonUid)
            changes+=resolved.changes
            effects+=resolved.effects
            val materialized=resolved.effects.flatMap { effect->
                val result=MechanicalEffectMaterializer.materialize(effect) as? MechanicalEffectMaterializationResult.Materialized
                    ?:return TemporalOwnerResult.Unsupported("P64:OWNER_EFFECT_NOT_MATERIALIZED")
                result.changes.map { it.payload }
            }
            if(resolved.status==BackgroundProcessStatus.COMPLETED)amounts.forEach { (ref,quantity)->
                // A transferred/consumed resource is already absent from the next staged
                // read. Reserve only the unconsumed part (e.g. labour), not a second debit.
                val before=reads.available(ref,staged)?:0L
                val afterCapacity=reads.available(ref,staged+resolved.changes+materialized)?:before
                val custodyTransferred=ref.kindUid=="ITEM_INSTANCE" && (resolved.changes+materialized).filterIsInstance<InventoryChange>()
                    .any { it.itemInstanceUid==ref.uid && it.quantityDelta.units<0 }
                val consumed=if(custodyTransferred)quantity else (before-afterCapacity).coerceAtLeast(0L).coerceAtMost(quantity)
                claimed[ref]=Math.addExact(claimed[ref]?:0L,quantity-consumed)
            }
            effectOverlay+=materialized
            changes+=BackgroundProcessChange(input.scope.campaignUid,input.scope.historyGenerationUid,process.version,after,evidence,
                (resolved.changes+materialized).map(Phase64BackgroundCodec::fingerprint)+resolved.existingConsequenceFingerprints,
                resolved.deadlineAdds,resolved.deadlineRemovals,resolved.ownerDelegations)
            resolved.ownerDelegations.forEach { delegation->
                if(delegation.expectedStateFingerprint!=TemporalOwnerDelegation.fingerprint(peerStates[delegation.proposed.ownerUid]))
                    return TemporalOwnerResult.Unsupported("P64:DELEGATION_STATE_CONFLICT")
                peerStates[delegation.proposed.ownerUid]=delegation.proposed
                delegations+=delegation
            }
            if(after.status==BackgroundProcessStatus.BLOCKED)next+=WorldProcessDeadline(deadline(after),OWNER,due)
            cursor.offset++
        }
        current.filter { it.due>input.through && it.status in setOf(BackgroundProcessStatus.ACTIVE,BackgroundProcessStatus.BLOCKED) }.forEach { process->
            if(scheduled.add(deadline(process)))next+=WorldProcessDeadline(deadline(process),OWNER,process.due)
        }
        val active=current.filter { it.status in setOf(BackgroundProcessStatus.ACTIVE,BackgroundProcessStatus.BLOCKED) && it.due>input.through }.map(::deadline).toSet()+next.map { it.uid }+
            input.deadlineView.filter { it.ownerUid==OWNER && it.due>input.through }.map { it.uid }
        scheduled.retainAll(active)
        scheduled+=next.map { it.uid }
        val state=kotlinx.serialization.json.buildJsonObject { put("scheduled",kotlinx.serialization.json.JsonArray(scheduled.sorted().map { kotlinx.serialization.json.JsonPrimitive(it) })) }.toString()
        return TemporalOwnerResult.Evaluated(TemporalOwnerState(OWNER,1,state),changes,next,mechanicalEffects=effects,ownerDelegations=delegations)
    }
    override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
        if(frontier!=null)return TemporalOwnerResult.EvaluationRequired("P64:INDEXED_FRONTIER_REQUIRED")
        val count=processes.count { it.due<=input.through && it.status in setOf(BackgroundProcessStatus.ACTIVE,BackgroundProcessStatus.BLOCKED) }
        if(count>32 && input.stagedChanges.none { it is BackgroundProcessChange })return TemporalOwnerResult.EvaluationRequired("P64:PROCESS_BATCH_REQUIRED")
        return evaluateBatch(input,cancelled={false})
    }
    private data class CaptureCursor(var after:BackgroundDueCursor?=null,
        val rows:MutableList<BackgroundProcessInstance> = mutableListOf(),val rules:MutableMap<Pair<String,Int>,BackgroundProcessDefinition> = mutableMapOf())
    fun extension():TemporalProcessExtension {
        val captures=mutableMapOf<String,CaptureCursor>()
        val workers=mutableMapOf<String,Phase64BackgroundProcessOwner>()
        val evaluations=mutableMapOf<String,EvaluationCursor>()
        return TemporalProcessExtension(listOf(RegisteredTemporalOwner("PHASE64_V1:${scope.ruleFingerprint}",this)),TemporalExternalEvaluationPort { request,cancelled->
        if(request.ownerUid!=OWNER || request.input.scope!=scope.temporal)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P64:EVALUATION_CORRELATION")
        fun clear() { captures.remove(request.fingerprint);workers.remove(request.fingerprint);evaluations.remove(request.fingerprint) }
        if(cancelled() || currentScope()!=scope.temporal) {
            clear();return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable(if(cancelled())"P60:CANCELLED" else "P64:STALE_HISTORY")
        }
        if(workers[request.fingerprint]==null && frontier!=null) {
            val capture=captures.getOrPut(request.fingerprint) { CaptureCursor() }
            val page=frontier.page(request.input.through,capture.after)
            if(capture.rows.size+page.processes.size>4096) { clear();return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P64:TURN_FRONTIER_BUDGET") }
            capture.rows+=page.processes
            page.processes.forEach { process->
                val key=process.definitionUid to process.definitionVersion
                if(key !in capture.rules)capture.rules[key]=frontier.definition(key.first,key.second)
                    ?:return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P64:PROCESS_RULE_MISSING").also { clear() }
            }
            capture.after=page.next
            if(page.next!=null)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Yielded(request.fingerprint)
            workers[request.fingerprint]=Phase64BackgroundProcessOwner(scope,capture.rows.toList(),capture.rules.toMap(),dependency,reads,
                adaptersByDomain.values.distinct(),currentScope)
            captures.remove(request.fingerprint)
        }
        val worker=workers[request.fingerprint]?:this
        val answer=worker.evaluateBatch(request.input,cancelled,evaluations[request.fingerprint]) { evaluations[request.fingerprint]=it }
        if(answer is TemporalOwnerResult.EvaluationRequired)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Yielded(request.fingerprint)
        clear()
        if(answer is TemporalOwnerResult.Evaluated)TemporalEvaluationResponse.Accepted(request.fingerprint,answer)
        else TemporalEvaluationResponse.Unavailable((answer as? TemporalOwnerResult.Unsupported)?.reasonUid?:"P64:EVALUATION_REQUIRED")
    })
    }
    companion object {
        const val OWNER="RPGOS-P64:BACKGROUND_WORLD"
        fun deadline(p:BackgroundProcessInstance)="P64:DUE:${p.uid}:${p.version}:${p.due.milliseconds}"
    }
}
