package com.rpgos.app

/** Interpretations may be wrong beliefs; none of these labels is a world FACT or a relation. */
enum class NpcAppraisalMeaning { THREAT, SAFETY, GOODWILL, HOSTILITY, LOSS, ACHIEVEMENT, UNCERTAINTY }
data class NpcAppraisalCandidate(val meaning:NpcAppraisalMeaning,val supportingRecordUid:String,val subject:DomainRef?=null) {
    init { npcUid(supportingRecordUid) }
}
enum class NpcGoalOperation { CREATE, SUSPEND, RESUME, ABANDON }
data class NpcGoalCandidate(val uid:String,val motivationUid:String,val objective:String,val supportingRecordUids:Set<String>,
                            val operation:NpcGoalOperation=NpcGoalOperation.CREATE,val executionOptionUid:String?=null) {
    init { npcUid(uid);npcUid(motivationUid);npcText(objective);require(supportingRecordUids.size<=8);supportingRecordUids.forEach(::npcUid)
        executionOptionUid?.let{npcUid(it);require(operation==NpcGoalOperation.CREATE)} }
}

/** Core's bounded update rules. This owner can change individuality, never the acquired records,
 * authoritative relations, statistics, resources, time or another actor. No inference from absence. */
internal object NpcBrainDynamics {
    fun proposedChanges(context:NpcDecisionContextEnvelope,appraisals:List<NpcAppraisalCandidate>,goals:List<NpcGoalCandidate>):List<NpcBrainChange> {
        val first=appraise(context,appraisals)
        val staged=first?.let { change ->
            val brain=NpcBrainCodec.decode(change.stateCanonical)
            NpcDecisionContextEnvelope(context.scope.copy(brainRevision=brain.revision),context.trigger,brain,context.records,context.options,
                context.maximumInputUnits,context.projectionFingerprint,context.currentRoleUids)
        } ?: context
        val planning=considerGoals(staged,goals,if(first==null)emptyList() else appraisals,context.brain.lastAppraisedAcquisitionOrder)
        val afterPlanning=planning?.let{NpcBrainCodec.decode(it.stateCanonical)}?:staged.brain
        val adaptation=if(first==null)null else adaptAfterAppraisal(context.brain,
            NpcDecisionContextEnvelope(context.scope.copy(brainRevision=afterPlanning.revision),context.trigger,afterPlanning,context.records,
                context.options.filter{option->option.goalUid==null || afterPlanning.goals.any{it.uid==option.goalUid && it.lifecycle==NpcGoalLifecycle.ACTIVE}},
                context.maximumInputUnits,context.projectionFingerprint,context.currentRoleUids))
        return listOfNotNull(first,planning,adaptation)
    }
    private fun intact(context:NpcDecisionContextEnvelope) {
        require(context.contextFingerprint==context.computeFingerprint()) { "P61:CONTEXT_MUTATED" }
    }
    private fun change(context:NpcDecisionContextEnvelope,after:NpcBrainState,rule:NpcBrainRule,causes:List<NpcCauseRef>):NpcBrainChange {
        val before=context.brain
        NpcBrainOwner.validateTransition(before,after,rule,causes)
        return NpcBrainChange(before.campaignUid,before.actor,context.scope.temporal.historyGenerationUid,before.revision,
            NpcBrainCodec.fingerprint(before),NpcBrainCodec.encode(after),rule.uid,rule.version,causes)
    }
    fun appraise(context:NpcDecisionContextEnvelope,candidates:List<NpcAppraisalCandidate>):NpcBrainChange? {
        intact(context);require(candidates.size<=8 && candidates.distinct().size==candidates.size)
        if(candidates.isEmpty())return null
        val before=context.brain
        val emotions=before.emotions.associateByTo(linkedMapOf()){it.uid}
        val dispositions=before.dispositions.associateByTo(linkedMapOf()){it.subject}
        val causes=linkedSetOf<NpcCauseRef>()
        var watermark=before.lastAppraisedAcquisitionOrder
        for(candidate in candidates.sortedWith(compareBy<NpcAppraisalCandidate>{it.supportingRecordUid}.thenBy{it.meaning.name}.thenBy{it.subject.toString()})) {
            val record=context.records.singleOrNull{it.uid==candidate.supportingRecordUid}?:error("P61:APPRAISAL_EVIDENCE_NOT_PERCEIVED")
            require(candidate.subject==null || candidate.subject in record.subjectRefs) { "P61:APPRAISAL_SUBJECT_NOT_PERCEIVED" }
            if(record.sourceCommittedOrder<=before.lastAppraisedAcquisitionOrder)continue
            val cause=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,record.acquisitionUid)
            // An old repeated interpretation must not pump an emotion indefinitely after reopen.
            if(emotions.values.any{it.cause==cause && it.uid==candidate.meaning.name})continue
            causes+=cause
            watermark=maxOf(watermark,record.sourceCommittedOrder)
            val certainty=when(record.epistemicState) {
                KnowledgeEpistemicState.KNOWN->1000
                KnowledgeEpistemicState.BELIEVED,KnowledgeEpistemicState.PARTIALLY_KNOWN->750
                KnowledgeEpistemicState.SUSPECTED->500
                else->250
            }
            val sensitivity=when(candidate.meaning) {
                NpcAppraisalMeaning.THREAT,NpcAppraisalMeaning.SAFETY,NpcAppraisalMeaning.LOSS->before.personality["CAUTION"]
                NpcAppraisalMeaning.GOODWILL,NpcAppraisalMeaning.HOSTILITY->before.personality["SOCIABILITY"]
                NpcAppraisalMeaning.ACHIEVEMENT->before.personality["PERSISTENCE"]
                NpcAppraisalMeaning.UNCERTAINTY->before.personality["CURIOSITY"]
            }?.basisPoints?:5000
            val delta=(certainty*(5000L+sensitivity)/15000).toInt().coerceIn(0,1000)
            val prior=emotions[candidate.meaning.name]
            require(prior==null || prior.updatedAt<=context.scope.atTime) { "P61:APPRAISAL_TIME_REGRESSION" }
            val retained=prior?.intensityAt(context.scope.atTime)?.basisPoints?.toLong()?:0L
            emotions[candidate.meaning.name]=NpcEmotion(candidate.meaning.name,NpcAffect((retained+delta).coerceIn(-10_000,10_000).toInt()),context.scope.atTime,cause)
            val subject=candidate.subject
            if(subject!=null && candidate.meaning in setOf(NpcAppraisalMeaning.GOODWILL,NpcAppraisalMeaning.HOSTILITY)) {
                val previous=dispositions[subject]?:NpcDisposition(subject,NpcAffect(0),NpcAffect(0),NpcWeight(0),cause)
                if(candidate.meaning==NpcAppraisalMeaning.GOODWILL)dispositions[subject]=previous.copy(
                    trust=NpcAffect((previous.trust.basisPoints+delta).coerceAtMost(10_000)),cause=cause)
                else dispositions[subject]=previous.copy(trust=NpcAffect((previous.trust.basisPoints-delta).coerceAtLeast(-10_000)),
                    grievance=NpcWeight((previous.grievance.basisPoints+delta).coerceAtMost(10_000)),cause=cause)
            }
        }
        if(causes.isEmpty())return null
        require(emotions.size<=32 && dispositions.size<=128) { "P61:APPRAISAL_CAPACITY" }
        return change(context,before.copy(revision=before.revision+1,lastAppraisedAcquisitionOrder=watermark,emotions=emotions.values.sortedBy{it.uid},
            dispositions=dispositions.values.sortedWith(compareBy<NpcDisposition>{it.subject.kindUid}.thenBy{it.subject.uid})),NpcBrainRules.APPRAISAL,causes.toList())
    }

    /** AI supplies possible objectives, but cannot assign priorities, motivations or success. */
    fun considerGoals(context:NpcDecisionContextEnvelope,candidates:List<NpcGoalCandidate>,
                     appraisals:List<NpcAppraisalCandidate> = emptyList(),previousWatermark:Long=context.brain.lastAppraisedAcquisitionOrder):NpcBrainChange? {
        intact(context);require(candidates.size<=8 && candidates.map{it.uid}.distinct().size==candidates.size)
        val before=context.brain;val goals=before.goals.associateByTo(linkedMapOf()){it.uid}
        val affect=NpcAffectiveMotivations.derive(context,appraisals,previousWatermark)
        val causes=affect.causes.toCollection(linkedSetOf())
        for(candidate in candidates.sortedBy{it.uid}) {
            val motivation=before.motivations.singleOrNull{it.uid==candidate.motivationUid}?:error("P61:GOAL_MOTIVATION_UNKNOWN")
            val execution=candidate.executionOptionUid?.let{NpcExecutionGoals.fromOption(context,it)}
            val objective=execution?.let(NpcExecutionGoals::description)?:candidate.objective
            if(candidate.supportingRecordUids.isEmpty()) {
                require(context.trigger.cause.kind==NpcCauseKind.INTRINSIC_MOTIVATION && candidate.operation==NpcGoalOperation.CREATE) { "P61:GOAL_SUPPORT_REQUIRED" }
                val cause=NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION,motivation.uid)
                val existing=goals[candidate.uid]
                if(existing!=null) {
                    require(existing.motivationUid==candidate.motivationUid && existing.objective==objective && existing.executionObjective==execution) { "P61:GOAL_IDENTITY_CHANGED" }
                    continue
                }
                // An intention about one's own needs is not a belief that an external object
                // exists. Affordances still require separately acquired evidence for any target.
                goals[candidate.uid]=NpcGoal(candidate.uid,motivation.uid,objective,motivation.strength,NpcGoalLifecycle.ACTIVE,cause,executionObjective=execution)
                causes+=cause
                continue
            }
            val support=candidate.supportingRecordUids.sorted().map{uid->context.records.singleOrNull{it.uid==uid}?:error("P61:GOAL_SUPPORT_NOT_PERCEIVED")}
            val newest=support.maxWith(compareBy<NpcKnownRecord>{it.sourceCommittedOrder}.thenBy{it.uid})
            val cause=NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,newest.acquisitionUid)
            val existing=goals[candidate.uid]
            // A subjective change of intention does not prove world success. Terminal goals
            // cannot be reopened, and the same acquisition cannot toggle a goal repeatedly.
            if(existing!=null) {
                require(existing.motivationUid==candidate.motivationUid && existing.objective==objective &&
                    (candidate.operation!=NpcGoalOperation.CREATE || existing.executionObjective==execution)) { "P61:GOAL_IDENTITY_CHANGED" }
                if(candidate.operation==NpcGoalOperation.CREATE)continue
                val previousOrder=context.records.singleOrNull{it.acquisitionUid==existing.cause.uid}?.sourceCommittedOrder
                    ?:before.lastAppraisedAcquisitionOrder
                require(cause!=existing.cause && newest.sourceCommittedOrder>previousOrder) { "P61:NEW_GOAL_EVIDENCE_REQUIRED" }
                val lifecycle=when(candidate.operation) {
                    NpcGoalOperation.SUSPEND->{require(existing.lifecycle==NpcGoalLifecycle.ACTIVE);NpcGoalLifecycle.SUSPENDED}
                    NpcGoalOperation.RESUME->{require(existing.lifecycle==NpcGoalLifecycle.SUSPENDED);NpcGoalLifecycle.ACTIVE}
                    NpcGoalOperation.ABANDON->{require(existing.lifecycle in setOf(NpcGoalLifecycle.ACTIVE,NpcGoalLifecycle.SUSPENDED));NpcGoalLifecycle.ABANDONED}
                    NpcGoalOperation.CREATE->error("P61:UNREACHABLE_GOAL_OPERATION")
                }
                // In-flight actions settle/interrupt at their existing Phase60 boundary first.
                require(before.plans.none{it.goalUid==existing.uid && it.lifecycle in setOf(NpcPlanLifecycle.RUNNING,NpcPlanLifecycle.WAITING)}) { "P61:RUNNING_PLAN_REQUIRES_BOUNDARY" }
                goals[existing.uid]=existing.copy(lifecycle=lifecycle,cause=cause)
                causes+=cause
                continue
            }
            require(candidate.operation==NpcGoalOperation.CREATE) { "P61:GOAL_NOT_FOUND" }
            causes+=support.map{NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,it.acquisitionUid)}
            goals[candidate.uid]=NpcGoal(candidate.uid,motivation.uid,objective,motivation.strength,NpcGoalLifecycle.ACTIVE,cause,executionObjective=execution)
        }
        if(causes.isEmpty())return null
        require(goals.size<=64) { "P61:GOAL_CAPACITY" }
        return change(context,before.copy(revision=before.revision+1,goals=goals.values.sortedBy{it.uid},motivations=affect.motivations),NpcBrainRules.PLANNING,causes.toList())
    }

    /** Slow, deterministic adaptation only when NEW own evidence crosses an accumulated affect
     * threshold. AI never chooses a trait delta. One ordinary interpretation cannot reroll identity. */
    private fun adaptAfterAppraisal(previous:NpcBrainState,context:NpcDecisionContextEnvelope):NpcBrainChange? {
        val changes=linkedMapOf<String,Int>();val causes=linkedSetOf<NpcCauseRef>()
        val mapping=mapOf("THREAT" to ("CAUTION" to 25),"SAFETY" to ("CAUTION" to -25),
            "GOODWILL" to ("SOCIABILITY" to 25),"HOSTILITY" to ("CAUTION" to 25),
            "ACHIEVEMENT" to ("PERSISTENCE" to 25),"UNCERTAINTY" to ("CURIOSITY" to 25))
        context.brain.emotions.sortedBy{it.uid}.forEach { emotion ->
            val old=previous.emotions.singleOrNull{it.uid==emotion.uid}
            if(emotion.intensity.basisPoints<7500 || (old?.intensity?.basisPoints?:0)>=7500 || emotion.cause==old?.cause)return@forEach
            val (trait,delta)=mapping[emotion.uid]?:return@forEach
            if(trait !in context.brain.personality)return@forEach
            require(context.records.any{it.acquisitionUid==emotion.cause.uid && it.sourceCommittedOrder>previous.lastAppraisedAcquisitionOrder})
            changes[trait]=(changes[trait]?:0)+delta;causes+=emotion.cause
        }
        if(changes.isEmpty())return null
        val personality=context.brain.personality.mapValues{(uid,value)->NpcWeight((value.basisPoints+(changes[uid]?:0)).coerceIn(0,10000))}
        if(personality==context.brain.personality)return null
        return change(context,context.brain.copy(revision=context.brain.revision+1,personality=personality),NpcBrainRules.ADAPTATION,causes.toList())
    }

    fun beginPlan(context:NpcDecisionContextEnvelope,selected:NpcDecisionResult.Selected,commandUid:String,
                  acceptedTiming:AcceptedActionTiming=selected.option.timing,previousPlanUid:String?=null):NpcBrainChange {
        intact(context);npcUid(commandUid)
        require(selected.authorization.matches(context.scope,context.contextFingerprint,selected.option)) { "P61:PLAN_AUTHORIZATION_MISMATCH" }
        val goal=context.brain.goals.singleOrNull{it.uid==selected.option.goalUid && it.lifecycle==NpcGoalLifecycle.ACTIVE}
            ?:error("P61:ACTIVE_GOAL_REQUIRED")
        val base=applyNpcBrainOverlay(context.brain,context.scope.temporal,selected.brainChanges)
        val staged=NpcDecisionContextEnvelope(context.scope.copy(brainRevision=base.revision),context.trigger,base,context.records,
            context.options.filter{it.goalUid==null || base.goals.any{g->g.uid==it.goalUid && g.lifecycle==NpcGoalLifecycle.ACTIVE}},
            context.maximumInputUnits,context.projectionFingerprint,context.currentRoleUids)
        val end=WorldTimeTick(Math.addExact(context.scope.atTime.milliseconds,acceptedTiming.duration.milliseconds))
        val cause=NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,commandUid)
        val continuation=selected.authorization.continuationOptionUids
        require(continuation.all{uid->context.options.any{it.uid==uid && it.goalUid==goal.uid}}) { "P61:PLAN_CONTINUATION_NOT_AUTHORIZED" }
        val predecessor=previousPlanUid?.let{uid->base.plans.single{it.uid==uid}.also{
            val sequence=it.lifecycle==NpcPlanLifecycle.COMPLETED && it.nextActionUids.firstOrNull()==selected.option.uid && it.nextActionUids.drop(1)==continuation
            val alternative=it.lifecycle==NpcPlanLifecycle.INTERRUPTED && it.onUnavailableOptionUid==selected.option.uid &&
                continuation.isEmpty() && selected.authorization.onUnavailableOptionUid==null
            require((sequence || alternative) && it.goalUid==goal.uid && it.cause==cause) { "P61:PLAN_PREDECESSOR_MISMATCH" }
        }}
        val plan=NpcPlan(selected.authorization.decisionUid,goal.uid,selected.option.uid,NpcPlanLifecycle.RUNNING,context.scope.atTime,end,cause,
            continuation,predecessor?.uid,selected.authorization.onUnavailableOptionUid)
        require(base.plans.none{it.uid==plan.uid} && base.plans.none{it.lifecycle in setOf(NpcPlanLifecycle.RUNNING,NpcPlanLifecycle.WAITING)}) { "P61:PLAN_ALREADY_STARTED" }
        // Terminal plan detail remains in canonical history. Keep the current brain bounded.
        val retained=base.plans.sortedWith(compareByDescending<NpcPlan>{it.startedAt}.thenBy{it.uid}).take(31)
        return change(staged,base.copy(revision=base.revision+1,plans=(retained+plan).sortedBy{it.uid}),NpcBrainRules.PLANNING,listOf(cause))
    }

    /** Finishing an action is not automatically achieving a goal. That needs its objective owner. */
    fun finishPlan(context:NpcDecisionContextEnvelope,planUid:String,commandUid:String,completed:Boolean,fulfillment:NpcExecutionFulfillment?=null):NpcBrainChange {
        intact(context);npcUid(commandUid)
        val plan=context.brain.plans.singleOrNull{it.uid==planUid}?:error("P61:PLAN_NOT_FOUND")
        require(plan.lifecycle in setOf(NpcPlanLifecycle.RUNNING,NpcPlanLifecycle.WAITING)) { "P61:PLAN_NOT_RUNNING" }
        if(completed)require(plan.nextEvaluationAt!=null && context.scope.atTime>=plan.nextEvaluationAt) { "P61:PLAN_NOT_ELAPSED" }
        val cause=NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,commandUid)
        val updated=plan.copy(lifecycle=if(completed)NpcPlanLifecycle.COMPLETED else NpcPlanLifecycle.INTERRUPTED,nextEvaluationAt=null,cause=cause)
        require(fulfillment==null || completed && fulfillment.matches(context,plan)) { "P61:GOAL_COMPLETION_PROOF_MISMATCH" }
        val goals=context.brain.goals.map{if(it.uid==fulfillment?.goalUid)it.copy(lifecycle=NpcGoalLifecycle.ACHIEVED,cause=cause) else it}
        return change(context,context.brain.copy(revision=context.brain.revision+1,goals=goals,plans=context.brain.plans.map{if(it.uid==planUid)updated else it}),
            if(fulfillment==null)NpcBrainRules.PLANNING else NpcBrainRules.EXECUTION_COMPLETION,listOf(cause))
    }
}

/** Core speculative overlay, checked against the exact canonical revision before re-projection. */
internal fun applyNpcBrainOverlay(base:NpcBrainState,scope:TemporalScope,changes:List<NpcBrainChange>):NpcBrainState {
    require(validNpcBrainChains(changes))
    return changes.filter{it.actor==base.actor}.fold(base){before,change->
        require(change.campaignUid==base.campaignUid && change.historyGenerationUid==scope.historyGenerationUid &&
            change.expectedVersion==before.revision && change.beforeFingerprint==NpcBrainCodec.fingerprint(before)) { "P61:STALE_BRAIN_OVERLAY" }
        NpcBrainCodec.decode(change.stateCanonical).also{after->
            NpcBrainOwner.validateTransition(before,after,NpcBrainRules.resolve(change.ruleUid,change.ruleVersion),change.causes)
        }
    }
}
