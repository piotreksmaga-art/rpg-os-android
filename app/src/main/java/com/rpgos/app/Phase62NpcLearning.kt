package com.rpgos.app

/** An explicit progression rule, never a model-supplied reward or technique acquisition. */
data class NpcLearningRule(val targetKindUid:String,val targetUid:String,val progressSemanticsUid:String,
    val effortUnits:Long,val minimumMastery:Double=0.0) {
    init {
        require(targetKindUid in setOf(ProgressionTargetKinds.SKILL,ProgressionTargetKinds.TECHNIQUE))
        npcUid(targetUid);npcUid(progressSemanticsUid)
        require(effortUnits>0 && minimumMastery.isFinite() && minimumMastery>=0)
    }
    internal val fingerprint get()=phase60Hash("P62:LEARNING:1|$targetKindUid|$targetUid|$progressSemanticsUid|$effortUnits|$minimumMastery")
}

/** The progression owner reads an existing entry. Absence is not permission to learn it. */
data class NpcLearningState(val mastery:Double,val progress:Double,val progressSemanticsUid:String,
    val entryVersion:Long,val definitionVersion:Long) {
    init { require(mastery.isFinite() && mastery>=0 && progress.isFinite() && progress>=0)
        npcUid(progressSemanticsUid);require(entryVersion>0 && definitionVersion>0) }
    internal val fingerprint get()=phase60Hash("$mastery|$progress|$progressSemanticsUid|$entryVersion|$definitionVersion")
}
fun interface NpcLearningStatePort {
    fun state(campaignUid:String,actor:DomainRef,rule:NpcLearningRule):NpcLearningState?
    companion object { val NONE=NpcLearningStatePort{_,_,_->null} }
}

internal object NpcLearningApplication {
    private const val PREFIX="npc_learning_"
    /** Re-evaluate the SAME Phase21 owner for interval settlement. In particular, completion of
     * an elapsed NPC lesson may interrupt the foreground without discarding its lawful grant. */
    fun intervalPayloads(campaign:String,command:String,effects:List<VerifiedMechanicsCommandEffect>):List<PlayerDomainChangePayload> {
        val engine=ProgressionEngine()
        return stimuli(campaign,effects).flatMap { s ->
            val input=ProgressionEvaluationInput.create(campaignUid=campaign,characterUid=s.subject.uid,
                sourceTypeUid=s.sourceTypeUid,sourceChannelUid=s.sourceChannelUid,stimulusUid=s.stimulusUid,
                sourceCommandUid=command,commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,commandFingerprint=phase60Hash(command),
                targetKindUid=s.targetKindUid,targetUid=s.targetUid,progressionDomainUid=null,targetValueEvidence=s.targetValueEvidence,
                progressSemanticsUid=s.progressSemanticsUid,progressSemanticsVersion=s.progressSemanticsVersion,
                effortUnits=s.effortUnits,durationUnits=s.durationUnits,methodUid=s.methodUid,
                worldPackBindingIdentity="P62:INTERVAL_CHECK",progressionPolicyUid=s.progressionPolicyUid,progressionPolicyVersion=s.progressionPolicyVersion,
                progressionEngineUid=engine.engineUid,progressionEngineVersion=engine.engineVersion,dependencyVersions=s.dependencyVersions)
            engine.evaluate(input).grants.map { g->when(g.targetKindUid) {
                ProgressionTargetKinds.SKILL->SkillChange(s.subject,g.targetUid,ExactLongDelta.of(g.grantUnits))
                ProgressionTargetKinds.TECHNIQUE->TechniqueChange(s.subject,g.targetUid,ExactLongDelta.of(g.grantUnits))
                else->error("P62:INTERVAL_LEARNING_TARGET")
            }}
        }
    }
    fun admitted(rule:NpcLearningRule,state:NpcLearningState?)=state!=null &&
        state.progressSemanticsUid==rule.progressSemanticsUid && state.mastery>=rule.minimumMastery

    fun fields(campaign:String,actor:DomainRef,contract:NpcActivityContract,state:NpcLearningState):Map<String,String> {
        val rule=requireNotNull(contract.learning)
        require(admitted(rule,state))
        return mapOf("${PREFIX}campaign" to campaign,"${PREFIX}actor_kind" to actor.kindUid,"${PREFIX}actor" to actor.uid,
            "${PREFIX}target_kind" to rule.targetKindUid,"${PREFIX}target" to rule.targetUid,
            "${PREFIX}semantics" to rule.progressSemanticsUid,"${PREFIX}effort" to rule.effortUnits.toString(),
            "${PREFIX}progress" to state.progress.toString(),"${PREFIX}state" to state.fingerprint,
            "${PREFIX}contract" to contract.fingerprint,"${PREFIX}rule" to contract.ruleUid,
            "${PREFIX}version" to contract.version.toString())
    }

    /** The existing PlayerDomainEngine evaluates these stimuli through Phase21 and writes its
     * progression ledger in the same transaction. No direct skill/technique award is added here. */
    fun stimuli(campaign:String,effects:List<VerifiedMechanicsCommandEffect>):List<ProgressionStimulus> = effects.mapNotNull { effect->
        val p=effect.canonicalPayload
        if(p["${PREFIX}target"]==null || effect.effectKindUid!="INTERACTION")return@mapNotNull null
        require(effect.mechanicsOwnerUid==NpcActivityMechanics.OWNER && effect.effectKindUid=="INTERACTION" &&
            p["${PREFIX}campaign"]==campaign && p["${PREFIX}actor_kind"]==effect.target.kindUid &&
            p["${PREFIX}actor"]==effect.target.uid && p["${PREFIX}contract"]==p["npc_activity_contract"])
        val kind=requireNotNull(p["${PREFIX}target_kind"])
        require(kind in setOf(ProgressionTargetKinds.SKILL,ProgressionTargetKinds.TECHNIQUE))
        val effort=requireNotNull(p["${PREFIX}effort"]?.toLongOrNull()).also{require(it>0)}
        val version=requireNotNull(p["${PREFIX}version"])
        val rule=requireNotNull(p["${PREFIX}rule"])
        val state=requireNotNull(p["${PREFIX}state"])
        ProgressionStimulus.create("P62:LEARNING:${effect.effectUid}","NPC_REGISTERED_LEARNING",ProgressionSourceChannels.TRAINING,
            effect.target,kind,requireNotNull(p["${PREFIX}target"]),
            targetValueEvidence=ProgressionTargetValueEvidence("P62:LEARNING-STATE:$state",requireNotNull(p["${PREFIX}progress"]),
                requireNotNull(p["${PREFIX}semantics"]),"1"),
            progressSemanticsUid=requireNotNull(p["${PREFIX}semantics"]),progressSemanticsVersion="1",
            effortUnits=effort,durationUnits=requireNotNull(p["p60_core_duration_ms"]?.toLongOrNull()),
            methodUid=rule,progressionPolicyUid=rule,progressionPolicyVersion=version,
            dependencyVersions=mapOf("P62:ACTIVITY:$rule" to requireNotNull(p["${PREFIX}contract"])))
    }
}
