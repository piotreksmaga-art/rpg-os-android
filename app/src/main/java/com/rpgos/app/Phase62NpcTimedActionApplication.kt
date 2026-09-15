package com.rpgos.app

internal fun interface NpcPhysicalContextPort {
    fun project(actor:DomainRef,input:TemporalOwnerInput,pending:NpcPendingAction?):NpcContextResult
}

/** Application orchestration uses no persistent future effects and holds no SQL transaction. */
internal class NpcTimedActionApplication(private val commandUid:String,private val contexts:NpcPhysicalContextPort,
    private val route:AiModelRoutePort,private val currentScope:()->TemporalScope,
    private val mechanics:NpcMechanicalActionApplication,
    private val foregroundAt:(TemporalOwnerInput)->List<VerifiedMechanicsCommandEffect> = {emptyList()},
    private val interruptsForeground:(List<VerifiedMechanicsCommandEffect>)->Boolean = {true},
    private val progress:NpcWorkProgressPort=NpcWorkProgressPort.NONE,
    private val initiatedSpeech:NpcInitiatedSpeechPort=NpcInitiatedSpeechPort.NONE):NpcTimedActionPort {
    override fun prepare(actor:DomainRef,input:TemporalOwnerInput,cancelled:()->Boolean):NpcActionPreparation {
        fun skip(reason:String)=NpcActionPreparation.Skipped(reason)
        if(cancelled())return skip("P62:CANCELLED")
        if(currentScope()!=input.scope)return skip("P62:STALE_SCOPE")
        val projected=when(val read=contexts.project(actor,input,null)) {
            is NpcContextResult.Unavailable->return skip(read.reasonUid)
            is NpcContextResult.Ready->read
        }
        val context=projected.context
        if(context.brain.plans.any{it.lifecycle in setOf(NpcPlanLifecycle.RUNNING,NpcPlanLifecycle.WAITING)})return skip("P62:ALREADY_BUSY")
        if(context.options.isEmpty())return skip("P62:NO_LEGAL_ACTION")
        val decision=NpcDecisionApplication(route,{
            check(currentScope()==input.scope){"P62:STALE_SCOPE"};context.scope
        },progress=progress).decide(NpcDecisionRequest("P62:ACT:${context.contextFingerprint}",context),AiCancellationSignal(cancelled))
        if(decision is NpcDecisionResult.Reflected)return NpcActionPreparation.Reflected(decision.brainChanges)
        if(decision !is NpcDecisionResult.Selected)return skip((decision as? NpcDecisionResult.Unavailable)?.reasonUid?:"P62:NO_ACTION_SELECTED")
        // A preflight proves executable timing/cost rules. Its effects are discarded, not saved.
        val preview=mechanics.resolve(projected,decision,(foregroundAt(input)+input.stagedEffects).map{it.asStagedMechanics()},AiCancellationSignal(cancelled))
        if(preview !is NpcMechanicalResult.Resolved) {
            val reason=(preview as NpcMechanicalResult.Unavailable).reasonUid
            return if(decision.brainChanges.isNotEmpty() && reason !in setOf("P62:STALE_SCOPE","P62:CANCELLED"))
                NpcActionPreparation.Reflected(decision.brainChanges) else skip(reason)
        }
        if(preview.timing.instantaneous)return skip("P62:TIMED_ACTION_REQUIRED")
        val started=NpcBrainDynamics.beginPlan(context,decision,commandUid,preview.timing)
        val plan=NpcBrainCodec.decode(started.stateCanonical).plans.single{it.uid==decision.authorization.decisionUid}
        return NpcActionPreparation.Started(NpcPendingAction(actor,plan.uid,decision.option.uid,requireNotNull(plan.startedAt),
            requireNotNull(plan.nextEvaluationAt),preview.timing.ruleUid,preview.timing.ruleVersion),decision.brainChanges+started)
    }
    override fun complete(action:NpcPendingAction,input:TemporalOwnerInput,cancelled:()->Boolean):NpcActionCompletion {
        fun fail(reason:String)=NpcActionCompletion.Unavailable(reason)
        if(cancelled())return fail("P62:CANCELLED")
        if(currentScope()!=input.scope)return fail("P62:STALE_SCOPE")
        if(input.through!=action.due)return fail("P62:ACTION_NOT_DUE")
        val projected=when(val read=contexts.project(action.actor,input,action)) {
            is NpcContextResult.Unavailable->return fail(read.reasonUid)
            is NpcContextResult.Ready->read
        }
        val context=projected.context
        val plan=context.brain.plans.singleOrNull{it.uid==action.planUid}?:return fail("P62:PLAN_NOT_FOUND")
        if(plan.actionUid!=action.optionUid || plan.startedAt!=action.startedAt || plan.nextEvaluationAt!=action.due || plan.lifecycle!=NpcPlanLifecycle.RUNNING)
            return fail("P62:PLAN_EXECUTION_MISMATCH")
        fun interrupted():NpcActionCompletion {
            val stopped=NpcBrainDynamics.finishPlan(context,plan.uid,commandUid,false)
            val nextUid=plan.onUnavailableOptionUid?:return NpcActionCompletion.Finished(stopped,emptyList(),interrupted=true)
            val next=followup(action.actor,input.copy(stagedChanges=input.stagedChanges+stopped),plan.uid,nextUid,emptyList(),cancelled)
            if(cancelled())return fail("P62:CANCELLED")
            if(currentScope()!=input.scope)return fail("P62:STALE_SCOPE")
            return NpcActionCompletion.Finished(stopped,emptyList(),interrupted=true,continuation=next)
        }
        val option=context.options.singleOrNull{it.uid==action.optionUid}?:return interrupted()
        // No fresh decision at completion: execute exactly the saved intention only if current
        // mechanics still admit it. Speech content is generated separately after delivery checks.
        val selected=NpcDecisionEngine().select(context,NpcDecisionProposal("P62:RESUME",context.contextFingerprint,
            listOf(NpcDecisionCandidate(option.uid))),context.scope,AiCancellationSignal(cancelled))
        if(selected !is NpcDecisionResult.Selected)return if(cancelled())fail("P62:CANCELLED") else interrupted()
        val resolved=mechanics.resolve(projected,selected,(foregroundAt(input)+input.stagedEffects).map{it.asStagedMechanics()},AiCancellationSignal(cancelled))
        if(resolved !is NpcMechanicalResult.Resolved)return if(cancelled())fail("P62:CANCELLED") else if(currentScope()!=input.scope)fail("P62:STALE_SCOPE") else interrupted()
        if(resolved.timing.ruleUid!=action.timingRuleUid || resolved.timing.ruleVersion!=action.timingRuleVersion ||
            resolved.timing.duration.milliseconds!=Math.subtractExact(action.due.milliseconds,action.startedAt.milliseconds))return interrupted()
        val delivered=if(option.mechanicsOwnerUid==NpcSpeechMechanics.OWNER) {
            val base=resolved.effects.singleOrNull()?:return fail("P62:SPEECH_EFFECT_CONTRACT")
            when(val speech=initiatedSpeech.deliver(context,selected,base,cancelled)) {
                is NpcInitiatedSpeechResult.Unavailable->return fail(speech.reasonUid)
                is NpcInitiatedSpeechResult.Delivered->resolved.effects+speech.effect
            }
        } else resolved.effects
        val effects=delivered.map { effect->effect.copy(proofUid="P60:PROCESS:${phase60Hash(effect.proofUid+"|"+context.contextFingerprint)}",
            canonicalPayload=effect.canonicalPayload+mapOf(
            "source_actor_kind_uid" to action.actor.kindUid,"source_actor_uid" to action.actor.uid)) }
        val fulfillment=NpcExecutionGoals.fulfilled(context,plan,selected,resolved)
        val finished=NpcBrainDynamics.finishPlan(context,plan.uid,commandUid,true,fulfillment)
        val interrupt=option.mechanicsOwnerUid==NpcSpeechMechanics.OWNER || interruptsForeground(effects)
        if(interrupt || fulfillment!=null || plan.nextActionUids.isEmpty())return NpcActionCompletion.Finished(finished,effects,playerDecisionRequired=interrupt)
        // Each continuation gets a new projection, fresh preflight and its own future boundary.
        // No effect computed here is committed early. Losing an option stops the chain, not the
        // already completed preceding action. Never ask AI again to silently replace that step.
        if(cancelled())return fail("P62:CANCELLED")
        val nextInput=input.copy(stagedChanges=input.stagedChanges+finished,stagedEffects=input.stagedEffects+effects)
        val next=followup(action.actor,nextInput,plan.uid,plan.nextActionUids.first(),plan.nextActionUids.drop(1),cancelled)
        if(cancelled())return fail("P62:CANCELLED")
        if(currentScope()!=input.scope)return fail("P62:STALE_SCOPE")
        return NpcActionCompletion.Finished(finished,effects,continuation=next)
    }
    /** Both branches reuse an already chosen intention, never another AI decision. A failed
     * branch has no successor, so it cannot spin or silently replace the original objective. */
    private fun followup(actor:DomainRef,input:TemporalOwnerInput,previousPlanUid:String,nextUid:String,continuation:List<String>,cancelled:()->Boolean):NpcActionPreparation.Started? {
        if(cancelled() || currentScope()!=input.scope)return null
        val nextProjected=contexts.project(actor,input,null) as? NpcContextResult.Ready?:return null
        if(cancelled() || currentScope()!=input.scope)return null
        val nextContext=nextProjected.context
        val nextDecision=NpcDecisionEngine().select(nextContext,NpcDecisionProposal("P62:CONTINUE",nextContext.contextFingerprint,
            listOf(NpcDecisionCandidate(nextUid,continuation))),nextContext.scope,AiCancellationSignal(cancelled)) as? NpcDecisionResult.Selected?:return null
        val preflight=mechanics.resolve(nextProjected,nextDecision,(foregroundAt(input)+input.stagedEffects).map{it.asStagedMechanics()},AiCancellationSignal(cancelled))
        if(cancelled() || currentScope()!=input.scope || preflight !is NpcMechanicalResult.Resolved || preflight.timing.instantaneous)return null
        val started=NpcBrainDynamics.beginPlan(nextContext,nextDecision,commandUid,preflight.timing,previousPlanUid)
        val nextPlan=NpcBrainCodec.decode(started.stateCanonical).plans.single{it.uid==nextDecision.authorization.decisionUid}
        val next=NpcPendingAction(actor,nextPlan.uid,nextUid,requireNotNull(nextPlan.startedAt),requireNotNull(nextPlan.nextEvaluationAt),
            preflight.timing.ruleUid,preflight.timing.ruleVersion)
        return NpcActionPreparation.Started(next,listOf(started))
    }
}
