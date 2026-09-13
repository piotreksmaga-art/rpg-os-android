package com.rpgos.app

/** One canonical read, performed by the repository under its campaign lifecycle lock. */
internal data class TemporalReadSnapshot(val scope: TemporalScope, val state: CanonicalTemporalState)

internal sealed interface ProductionTimeResult {
    data class Ready(val change: TemporalStateChange?, val schedule:List<ScheduledActionInterval> = emptyList()) : ProductionTimeResult
    data class Rejected(val reasonUid: String) : ProductionTimeResult
}

/**
 * Interpreted durations are candidates, not writes. Core chooses a bounded estimate only when
 * no process deadline can be crossed. A registered domain timing rule can override the candidate.
 * Missing duration is explicit; neither message length nor a verb allowlist defines elapsed time.
 */
internal object Phase60ProductionTime {
    fun prepare(request: ChatTurnRequest, plan: CanonicalTurnPlan, read: TemporalReadSnapshot,
                authoritative: Map<String, AcceptedActionTiming> = emptyMap(),
                processExecutionAvailable:Boolean=false,
                outcomes:Map<String,GmNodeOutcomeState> = emptyMap()): ProductionTimeResult {
        if (read.scope.campaignUid != request.campaignUid || plan.campaignUid != request.campaignUid)
            return ProductionTimeResult.Rejected("P60:CROSS_CAMPAIGN_TIME")
        val bindings = linkedMapOf<String, TemporalNodeBinding>()
        for (node in plan.intent.activeNodes()) {
            if (node.modality != IntentModality.ATTEMPT_NOW || node.form in setOf(IntentForm.CORRECTION, IntentForm.CANCELLATION)) continue
            val attributes = node.semanticAction.attributes
            val minimum = attributes["time_min_ms"]?.toLongOrNull()?.takeIf { it > 0 }
            val maximum = attributes["time_max_ms"]?.toLongOrNull()?.takeIf { it > 0 }
            // Pure UI/meta questions do not describe an attempted world action. A model's scope
            // label alone cannot turn an admitted world-effect capability into a free action.
            val step = plan.steps.singleOrNull { it.nodeUid == node.nodeUid }
            val meta = attributes["time_scope"] == "META" && node.form == IntentForm.QUERY &&
                step?.sideEffectClass != CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT
            val evidence = ActionTimingEvidence(
                if (meta) ActionTimeMeaning.OUT_OF_WORLD else ActionTimeMeaning.IN_WORLD,
                authoritative = authoritative[node.nodeUid],
                estimatedMinimum = minimum?.let(::ActionDuration), estimatedMaximum = maximum?.let(::ActionDuration),
                uncertaintyHasMaterialConsequences = minimum == null || maximum == null || maximum < minimum ||
                    maximum - minimum > minimum / 4 || (minimum != maximum && (read.state.deadlines.isNotEmpty() || read.state.processStates.isNotEmpty()))
            )
            val outcome=outcomes[node.nodeUid]
            if(outcome in setOf(GmNodeOutcomeState.NEEDS_CLARIFICATION,GmNodeOutcomeState.REQUIRES_ADJUDICATION))
                return ProductionTimeResult.Rejected("P60:ACTION_REQUIRES_CLARIFICATION")
            val conditional=node.dependencies.filter{it.kind in setOf(IntentDependencyKind.AFTER_SUCCESS,IntentDependencyKind.AFTER_EVENT,IntentDependencyKind.REQUIRES_RESULT)}
            val satisfied=if(node.conditions.isEmpty() && conditional.isNotEmpty() && conditional.all{it.kind==IntentDependencyKind.AFTER_SUCCESS} &&
                conditional.all{it.predecessorNodeUid in outcomes}) conditional.all{outcomes[it.predecessorNodeUid]==GmNodeOutcomeState.PROPOSED_SUCCESS} else null
            bindings[node.nodeUid] = TemporalNodeBinding(evidence, PHASE60_FOREGROUND_OWNER,conditionSatisfied=satisfied,
                skippedByCore=outcome==GmNodeOutcomeState.BLOCKED_BY_PREREQUISITE)
        }
        return when (val bound = Phase60PlanBinding.bind(plan, bindings)) {
            TemporalPlanBindingResult.OutOfWorld -> ProductionTimeResult.Ready(null)
            is TemporalPlanBindingResult.NeedsDecision -> ProductionTimeResult.Rejected(bound.reasonUid)
            is TemporalPlanBindingResult.Bound -> {
                val schedule = try { Phase60ActionPlanner.schedule(read.state.time, bound.nodes) }
                    catch (_: ArithmeticException) { return ProductionTimeResult.Rejected("P60:TIME_OVERFLOW") }
                val end=schedule.maxOf { it.end }
                // Until a domain owner is registered, never skip its due evaluation or silently
                // claim that a partial action produced its full verified mechanical effects.
                if (read.state.deadlines.any { it.due <= end } && !processExecutionAvailable) return ProductionTimeResult.Rejected("P60:PROCESS_OWNER_REQUIRED")
                ProductionTimeResult.Ready(TemporalStateChange(request.campaignUid, read.state.version, read.state.time, end,
                    Phase60ProcessStateCodec.encode(read.state.processStates),
                    Phase60DeadlineCodec.encode(if(processExecutionAvailable)read.state.deadlines.filter { it.due > end } else read.state.deadlines)),schedule)
            }
        }
    }
}

/** Uses the same commit and narration-recovery path as untimed turns; never commits twice. */
internal class ProductionTemporalMutationAssembler(
    private val delegate: ProductionCanonicalMutationAssembler,
    private val read: () -> TemporalReadSnapshot,
    private val checkpoints:TemporalCheckpointPort,
    private val processOwners:(TemporalReadSnapshot,List<VerifiedMechanicsCommandEffect>)->List<RegisteredTemporalOwner> = {_,_->emptyList()},
    private val effectPolicy:TemporalEffectPolicyPort = TemporalEffectPolicyPort.COMPLETION_ONLY
) : CancellableCanonicalMutationAssembler, CanonicalMutationAssemblyDiagnostics {
    @Volatile private var reasons: List<String> = emptyList()
    private val scopes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<CanonicalCampaignMutationProposal, TemporalScope>())
    fun scopeFor(proposal: CanonicalCampaignMutationProposal): TemporalScope? = scopes[proposal]
    fun committed(campaignUid:String,commandUid:String) = checkpoints.remove(campaignUid,commandUid)
    override fun lastAssemblyReasonUids() = reasons
    override fun assemble(request: ChatTurnRequest, plan: CanonicalTurnPlan, proposal: ResolvedGmProposal): CanonicalCampaignMutationProposal? {
        return assemble(request,plan,proposal){false}
    }
    override fun assemble(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal,cancelled:()->Boolean):CanonicalCampaignMutationProposal? {
        reasons = emptyList()
        return try { assembleChecked(request,plan,proposal,cancelled) }
        catch(failure:java.util.concurrent.CancellationException){reasons=listOf("P60:CANCELLED");null}
        catch(failure:Exception){
            // Never expose SQL, cached owner text or arbitrary exception messages to the player.
            reasons=listOf(if(failure is ArithmeticException)"P60:TIME_OR_EFFECT_OVERFLOW" else "P60:EXECUTION_REJECTED")
            null
        }
    }
    private fun assembleChecked(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal,cancelled:()->Boolean):CanonicalCampaignMutationProposal? {
        val snapshot = read()
        if(cancelled()){reasons=listOf("P60:CANCELLED");return null}
        if(request.atOrder!=null && request.atOrder!=Math.addExact(snapshot.scope.baseCommitOrder,1L)){
            reasons=listOf("P60:STALE_TURN_CONTEXT");return null
        }
        val prepared=delegate.prepareEffects(request,plan,proposal)?:run {reasons=delegate.lastAssemblyReasonUids();return null}
        return when (val time = Phase60ProductionTime.prepare(request, plan, snapshot,authoritative=Phase60DomainTiming.accepted(prepared),processExecutionAvailable=true,
            outcomes=proposal.candidate.nodeProposals.associate{it.nodeUid to it.outcomeState})) {
            is ProductionTimeResult.Rejected -> { reasons = listOf(time.reasonUid); null }
            is ProductionTimeResult.Ready -> {
                if(time.schedule.isEmpty()) return delegate.admitEffects(request,plan.planUid,proposal.candidate.proposalUid,prepared,null).also {
                    if(it!=null)scopes[it]=snapshot.scope else reasons=delegate.lastAssemblyReasonUids()
                }
                val execution=Phase60ProductionExecution(checkpoints,{read().scope},processOwners(snapshot,prepared),effectPolicy)
                    .execute(request,time,snapshot,prepared,cancelled)
                when(execution) {
                    is ProductionTemporalExecutionResult.Rejected->{reasons=listOf(execution.reasonUid);null}
                    is ProductionTemporalExecutionResult.Completed-> {
                        val canonical=delegate.admitEffects(request,plan.planUid,proposal.candidate.proposalUid,execution.effects,execution.change)
                        if(canonical==null){reasons=delegate.lastAssemblyReasonUids();return null}
                        val foregroundPayloads=execution.effects.flatMap { (MechanicalEffectMaterializer.materialize(it) as MechanicalEffectMaterializationResult.Materialized).changes.map{change->change.payload} }
                        // Includes only elapsed foreground effects plus evaluated process deltas.
                        val settled=execution.work.copy(checkpoint=execution.work.checkpoint.copy(candidateChanges=phase60CoalesceChanges(foregroundPayloads)))
                        val failure=Phase60EffectSettlement.validate(settled,phase60CoalesceChanges(canonical.playerChangeSet.changes.map{it.payload}))
                        if(failure!=null){reasons=listOf(failure);return null}
                        scopes[canonical]=snapshot.scope
                        canonical
                    }
                }
            }
        }
    }
}
