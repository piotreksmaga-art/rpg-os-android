package com.rpgos.app

/** These scales are not interchangeable with knowledge confidence or semantic similarity. */
@JvmInline value class NpcWeight(val basisPoints:Int) { init { require(basisPoints in 0..10_000) } }
@JvmInline value class NpcAffect(val basisPoints:Int) { init { require(basisPoints in -10_000..10_000) } }

internal fun npcUid(value:String) { require(value.isNotBlank() && value.length<=160) { "P61:INVALID_UID" } }
internal fun npcText(value:String) { require(value.isNotBlank() && value.length<=512) { "P61:INVALID_TEXT" } }

enum class NpcMotivationKind { NEED, DESIRE, ASPIRATION, FEAR, LOYALTY, OBLIGATION }
enum class NpcGoalLifecycle { ACTIVE, SUSPENDED, ACHIEVED, ABANDONED }
enum class NpcPlanLifecycle { READY, RUNNING, WAITING, INTERRUPTED, COMPLETED, ABANDONED }
enum class NpcCauseKind { GENESIS, KNOWLEDGE_ACQUISITION, COMMITTED_EVENT, ACCEPTED_ACTION, INTRINSIC_MOTIVATION }

data class NpcCauseRef(val kind:NpcCauseKind,val uid:String) { init { npcUid(uid) } }
data class NpcMotivation(val uid:String,val kind:NpcMotivationKind,val domainUid:String,val strength:NpcWeight,
                         val subject:DomainRef?=null) { init { npcUid(uid);npcUid(domainUid) } }
data class NpcGoal(val uid:String,val motivationUid:String,val objective:String,val priority:NpcWeight,
                   val lifecycle:NpcGoalLifecycle,val cause:NpcCauseRef,val deadline:WorldTimeTick?=null,
                   val executionObjective:NpcExecutionObjective?=null) {
    init { npcUid(uid);npcUid(motivationUid);npcText(objective)
        require(executionObjective==null || objective==NpcExecutionGoals.description(executionObjective)) { "P61:OBJECTIVE_DESCRIPTION_MISMATCH" } }
}
data class NpcEmotion(val uid:String,val intensity:NpcAffect,val updatedAt:WorldTimeTick,val cause:NpcCauseRef) {
    init { npcUid(uid) }
    /** Current affect is a deterministic projection of world time, not a periodic write. The
     * canonical sample/evidence remains intact for replay and historical interpretation. */
    fun intensityAt(at:WorldTimeTick):NpcAffect {
        require(at>=updatedAt){"P61:FUTURE_EMOTION"}
        val elapsed=(at.milliseconds-updatedAt.milliseconds).coerceAtMost(3_600_000L)
        return NpcAffect((intensity.basisPoints.toLong()*(3_600_000L-elapsed)/3_600_000L).toInt())
    }
}
/** A personal attitude is not a new relationship/knowledge authority. */
data class NpcDisposition(val subject:DomainRef,val trust:NpcAffect,val attachment:NpcAffect,val grievance:NpcWeight,
                          val cause:NpcCauseRef)
data class NpcPlan(val uid:String,val goalUid:String,val actionUid:String,val lifecycle:NpcPlanLifecycle,
                   val startedAt:WorldTimeTick?,val nextEvaluationAt:WorldTimeTick?,val cause:NpcCauseRef,
                   val nextActionUids:List<String> = emptyList(),val previousPlanUid:String?=null,
                   val onUnavailableOptionUid:String?=null) {
    init { npcUid(uid);npcUid(goalUid);npcUid(actionUid)
        require(startedAt==null || nextEvaluationAt==null || nextEvaluationAt>=startedAt)
        require(nextActionUids.size<=3);nextActionUids.forEach(::npcUid);previousPlanUid?.let(::npcUid)
        onUnavailableOptionUid?.let{npcUid(it);require(it!=actionUid)}
        require(previousPlanUid!=uid) }
}

/** Authoritative individuality. Knowledge, memory, inventory and social relations stay with their owners. */
data class NpcBrainState(
    val campaignUid:String,val actor:DomainRef,val revision:Long,val seedFingerprint:String,
    val personality:Map<String,NpcWeight>,val values:Map<String,NpcWeight>,
    val motivations:List<NpcMotivation> = emptyList(),val goals:List<NpcGoal> = emptyList(),
    val emotions:List<NpcEmotion> = emptyList(),val dispositions:List<NpcDisposition> = emptyList(),
    val roleUids:Set<String> = emptySet(),val plans:List<NpcPlan> = emptyList(),
    val knowledgeHolder:KnowledgeHolderRef=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,actor.uid,campaignUid),
    val lastAppraisedAcquisitionOrder:Long = -1
) {
    init {
        npcUid(campaignUid);npcUid(actor.kindUid);npcUid(actor.uid)
        require(knowledgeHolder.campaignUid==campaignUid && knowledgeHolder.holderUid==actor.uid) { "P61:INVALID_COGNITION_BINDING" }
        require(revision in 1 until Long.MAX_VALUE && seedFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(lastAppraisedAcquisitionOrder>=-1)
        require(personality.size<=64 && values.size<=64 && motivations.size<=64 && goals.size<=64 &&
            emotions.size<=32 && dispositions.size<=128 && roleUids.size<=32 && plans.size<=32) { "P61:BRAIN_BUDGET" }
        (personality.keys+values.keys+roleUids).forEach(::npcUid)
        require(motivations.map{it.uid}.distinct().size==motivations.size && goals.map{it.uid}.distinct().size==goals.size &&
            emotions.map{it.uid}.distinct().size==emotions.size && dispositions.map{it.subject}.distinct().size==dispositions.size &&
            plans.map{it.uid}.distinct().size==plans.size) { "P61:DUPLICATE_COMPONENT" }
        require(goals.all{goal->motivations.any{it.uid==goal.motivationUid}}) { "P61:GOAL_WITHOUT_MOTIVATION" }
        require(plans.all{plan->goals.any{it.uid==plan.goalUid}}) { "P61:PLAN_WITHOUT_GOAL" }
    }
}

enum class NpcBrainTransitionKind { INITIALIZE, APPRAISAL, GOALS_AND_PLANS, PERSONALITY_ADAPTATION }

/** Rules are registered by Core, never read from a model response. */
data class NpcBrainRule(val uid:String,val version:Int,val allowed:NpcBrainTransitionKind,
                        val maximumTraitDelta:Int=0) {
    init { npcUid(uid);require(version>0 && maximumTraitDelta in 0..10_000)
        require(allowed==NpcBrainTransitionKind.PERSONALITY_ADAPTATION || maximumTraitDelta==0) }
}

internal object NpcBrainOwner {
    /** Drafts alone grant nothing. Only exact effects admitted for this turn may seed a brain
     * alongside the actor, so rollback removes both and reopening never rerolls individuality. */
    fun admittedMaterializations(campaignUid:String,effects:List<VerifiedMechanicsCommandEffect>,drafts:List<WorldElementDraft>):Set<DomainRef> =
        drafts.asSequence().filter{it.campaignUid==campaignUid && it.baseKind==WorldElementBaseKind.ACTOR}
            .filter{draft->effects.any{effect->effect.target==draft.element && effect.magnitude==1L &&
                effect.mechanicsOwnerUid=="RPGOS-CORE:WORLD-MATERIALIZER" && effect.effectKindUid=="WORLD_ELEMENT_MATERIALIZE" &&
                effect.proofUid=="RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}" &&
                effect.canonicalPayload==draft.materializationPayload()}}
            .mapTo(linkedSetOf()){it.element}

    /** Per-field hashing: materializing another NPC or adding a trait never rerolls existing traits. */
    fun initialize(campaignUid:String,actor:DomainRef,worldSeed:String,knownTraits:Map<String,NpcWeight> = emptyMap(),
                   knownValues:Map<String,NpcWeight> = emptyMap(),roles:Set<String> = emptySet()):NpcBrainState {
        require(worldSeed.isNotBlank())
        val seed=phase60Hash("P61:GENESIS:1|${campaignUid.length}:$campaignUid|${actor.kindUid.length}:${actor.kindUid}|${actor.uid.length}:${actor.uid}|$worldSeed")
        val defaults=listOf("CAUTION","SOCIABILITY","PERSISTENCE","CURIOSITY").associateWith { key ->
            NpcWeight(2_500+(phase60Hash("$seed|$key").take(8).toLong(16)%5_001).toInt())
        }
        val personality=defaults+knownTraits
        // Values are individuality, not invented biography or membership. Separate field seeds
        // preserve trait draws and explicit World Pack values always take precedence. Existing
        // canonical brains are read unchanged; replay restores their stored values, never rerolls.
        val values=listOf("HONESTY","COMPASSION","AUTONOMY","LOYALTY","DISCIPLINE").associateWith { key ->
            NpcWeight(2_500+(phase60Hash("$seed|VALUE:$key").take(8).toLong(16)%5_001).toInt())
        }+knownValues
        return NpcBrainState(campaignUid,actor,1,seed,personality,values,
            motivations=intrinsicMotivations(personality),roleUids=roles)
    }

    /** Intrinsic drives describe current individuality, not acquired facts or a fabricated past.
     * Domain-specific obligations and concrete goals require later, evidenced transitions. */
    private fun intrinsicMotivations(personality:Map<String,NpcWeight>)=listOf(
        NpcMotivation("P61:SELF_PRESERVATION",NpcMotivationKind.NEED,"EXISTENCE",personality["CAUTION"]?:NpcWeight(5000)),
        NpcMotivation("P61:EXPLORATION",NpcMotivationKind.DESIRE,"KNOWLEDGE",personality["CURIOSITY"]?:NpcWeight(5000)),
        NpcMotivation("P61:SOCIAL_CONNECTION",NpcMotivationKind.DESIRE,"RELATIONSHIPS",personality["SOCIABILITY"]?:NpcWeight(5000))
    ).sortedBy{it.uid}

    fun validateTransition(before:NpcBrainState?,after:NpcBrainState,rule:NpcBrainRule,causes:List<NpcCauseRef>) {
        require(causes.isNotEmpty() && causes.size<=64 && causes.distinct().size==causes.size) { "P61:CAUSE_REQUIRED" }
        if(before==null) {
            require(rule.allowed==NpcBrainTransitionKind.INITIALIZE && after.revision==1L &&
                causes.all{it.kind==NpcCauseKind.GENESIS}) { "P61:INVALID_GENESIS" }
            require(after.motivations==intrinsicMotivations(after.personality) && after.goals.isEmpty() && after.emotions.isEmpty() &&
                after.dispositions.isEmpty() && after.plans.isEmpty() && after.lastAppraisedAcquisitionOrder==-1L) { "P61:GENESIS_CANNOT_INVENT_HISTORY" }
            return
        }
        require(before.campaignUid==after.campaignUid && before.actor==after.actor && before.knowledgeHolder==after.knowledgeHolder && before.seedFingerprint==after.seedFingerprint &&
            after.revision==Math.addExact(before.revision,1L)) { "P61:IDENTITY_OR_REVISION_CHANGED" }
        require(rule.allowed!=NpcBrainTransitionKind.INITIALIZE && causes.none{it.kind==NpcCauseKind.GENESIS}) { "P61:REINITIALIZATION_FORBIDDEN" }
        if(rule.allowed!=NpcBrainTransitionKind.PERSONALITY_ADAPTATION) {
            require(before.personality==after.personality && before.values==after.values) { "P61:PERSONALITY_OWNER_REQUIRED" }
        } else {
            require(causes.any{it.kind==NpcCauseKind.COMMITTED_EVENT || it.kind==NpcCauseKind.KNOWLEDGE_ACQUISITION}) { "P61:ADAPTATION_EVIDENCE_REQUIRED" }
            require(before.personality.keys==after.personality.keys && before.values.keys==after.values.keys)
            fun bounded(a:Map<String,NpcWeight>,b:Map<String,NpcWeight>)=a.all{(k,v)->kotlin.math.abs(v.basisPoints-b.getValue(k).basisPoints)<=rule.maximumTraitDelta}
            require(bounded(before.personality,after.personality) && bounded(before.values,after.values)) { "P61:ADAPTATION_BOUND" }
        }
        require(before.roleUids==after.roleUids) { "P61:SOCIAL_ROLE_OWNER_REQUIRED" }
        if(rule.allowed!=NpcBrainTransitionKind.GOALS_AND_PLANS)
            require(before.motivations==after.motivations && before.goals==after.goals && before.plans==after.plans) { "P61:GOAL_OWNER_REQUIRED" }
        if(rule.allowed!=NpcBrainTransitionKind.APPRAISAL)
            require(before.emotions==after.emotions && before.dispositions==after.dispositions &&
                before.lastAppraisedAcquisitionOrder==after.lastAppraisedAcquisitionOrder) { "P61:APPRAISAL_OWNER_REQUIRED" }
        require(after.lastAppraisedAcquisitionOrder>=before.lastAppraisedAcquisitionOrder) { "P61:APPRAISAL_WATERMARK_REGRESSION" }
        fun caused(cause:NpcCauseRef)=cause in causes
        require(after.emotions.filter{it !in before.emotions}.all{caused(it.cause)} &&
            after.dispositions.filter{it !in before.dispositions}.all{caused(it.cause)} &&
            after.goals.filter{it !in before.goals}.all{caused(it.cause)} &&
            after.plans.filter{it !in before.plans}.all{caused(it.cause)}) { "P61:COMPONENT_CAUSE_MISMATCH" }
        require(after.emotions.all{emotion->before.emotions.singleOrNull{it.uid==emotion.uid}?.let{emotion.updatedAt>=it.updatedAt}!=false}) { "P61:BACKWARD_APPRAISAL" }
        after.goals.forEach{goal->before.goals.singleOrNull{it.uid==goal.uid}?.let{prior->
            require(goal.objective==prior.objective && goal.motivationUid==prior.motivationUid && goal.executionObjective==prior.executionObjective) { "P61:GOAL_IDENTITY_CHANGED" }
            require(prior.lifecycle !in setOf(NpcGoalLifecycle.ACHIEVED,NpcGoalLifecycle.ABANDONED) || goal==prior) { "P61:TERMINAL_GOAL_REWRITTEN" }
            if(goal.lifecycle==NpcGoalLifecycle.ACHIEVED && prior.lifecycle!=goal.lifecycle)require(
                rule==NpcBrainRules.EXECUTION_COMPLETION && prior.executionObjective!=null && prior.lifecycle==NpcGoalLifecycle.ACTIVE &&
                    after.plans.any{it.goalUid==goal.uid && it.lifecycle==NpcPlanLifecycle.COMPLETED && it.cause==goal.cause && it !in before.plans}) { "P61:GOAL_COMPLETION_OWNER_REQUIRED" }
        } ?: require(goal.lifecycle!=NpcGoalLifecycle.ACHIEVED) { "P61:GOAL_CANNOT_START_ACHIEVED" }}
        after.plans.forEach{plan->before.plans.singleOrNull{it.uid==plan.uid}?.let{prior->
            require(plan.goalUid==prior.goalUid && plan.actionUid==prior.actionUid && plan.startedAt==prior.startedAt &&
                plan.nextActionUids==prior.nextActionUids && plan.previousPlanUid==prior.previousPlanUid &&
                plan.onUnavailableOptionUid==prior.onUnavailableOptionUid) { "P61:PLAN_IDENTITY_CHANGED" }
            require(prior.lifecycle !in setOf(NpcPlanLifecycle.COMPLETED,NpcPlanLifecycle.INTERRUPTED,NpcPlanLifecycle.ABANDONED) || plan==prior) { "P61:TERMINAL_PLAN_REWRITTEN" }
        }}
    }
}
