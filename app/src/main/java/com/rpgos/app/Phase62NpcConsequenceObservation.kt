package com.rpgos.app

/** Phase37 acquisition from a narrow, explicit sensory rule, never from a global event scan.
 * Being wounded while conscious permits knowledge of one's own pain, NOT knowledge of who
 * attacked, exact damage, hidden poison, the attacker's intent or anyone else's condition. */
internal object NpcConsequenceObservation {
    private const val PREFIX="npc_sensation_"
    private const val RULE="P62:CONSCIOUS_WOUND:1"
    private val unconscious=setOf("DEAD","UNCONSCIOUS","INCAPACITATED")
    fun annotate(scope:TemporalScope,effects:List<VerifiedMechanicsCommandEffect>,
                 actorAt:(DomainRef)->MechanicalActorView?):List<VerifiedMechanicsCommandEffect> {
        // Caller-provided fields cannot establish a recipient. Recompute from Core's snapshot.
        val clean=effects.map{it.copy(canonicalPayload=it.canonicalPayload.filterKeys{key->!key.startsWith(PREFIX)})}
        val views=linkedMapOf<DomainRef,MechanicalActorView?>()
        return clean.map { effect ->
            if(effect.effectKindUid!="WOUND" || effect.mechanicsOwnerUid!="UNIVERSAL_COMBAT" || effect.magnitude<=0)return@map effect
            val view=views.getOrPut(effect.target){actorAt(effect.target)} ?: return@map effect
            if(view.campaignUid!=scope.campaignUid || view.actor!=effect.target ||
                view.kind !in setOf(MechanicalActorKind.NPC,MechanicalActorKind.MONSTER,MechanicalActorKind.SUMMON,MechanicalActorKind.FORMER_PLAYER) ||
                view.materialization!=MechanicalStateMaterialization.FULL ||
                view.conditions.any{it.intensity>0 && it.conditionUid.uppercase() in unconscious} ||
                view.resources.any{it.resourceUid=="HEALTH" && it.current<=0})return@map effect
            // Conservative for unordered/multiple simultaneous effects: do not assert retained
            // awareness when this admitted set also contains loss of consciousness.
            if(clean.any{it.target==effect.target && it.effectKindUid in setOf("CONDITION","CONTROL","RESTRICTION") &&
                    it.canonicalPayload["condition_uid"]?.uppercase() in unconscious &&
                    (it.canonicalPayload["operation"]?.uppercase()?:"ADD") in setOf("ADD","APPLY")})return@map effect
            val fields=mapOf("${PREFIX}rule" to RULE,"${PREFIX}campaign" to scope.campaignUid,
                "${PREFIX}generation" to scope.historyGenerationUid,"${PREFIX}order" to Math.addExact(scope.baseCommitOrder,1L).toString(),
                "${PREFIX}actor_version" to view.stateVersion.toString())
            effect.copy(canonicalPayload=effect.canonicalPayload+fields+("${PREFIX}fingerprint" to fingerprint(effect,fields)))
        }
    }
    private fun fingerprint(effect:VerifiedMechanicsCommandEffect,fields:Map<String,String>):String=phase60Hash(
        listOf(RULE,effect.effectUid,effect.nodeUid,effect.mechanicsOwnerUid,effect.effectKindUid,effect.target,effect.magnitude,
            effect.proofUid,effect.deterministicInputFingerprint,effect.deterministicOutputFingerprint,
            effect.canonicalPayload.filterKeys{!it.startsWith(PREFIX)}.toSortedMap(),fields.toSortedMap()).joinToString("|"))
    fun recipient(campaign:String,effect:VerifiedMechanicsCommandEffect):DomainRef? {
        if(effect.canonicalPayload["${PREFIX}rule"]==null)return null
        val fields=effect.canonicalPayload.filterKeys{it.startsWith(PREFIX) && it!="${PREFIX}fingerprint"}
        require(fields.keys==setOf("${PREFIX}rule","${PREFIX}campaign","${PREFIX}generation","${PREFIX}order","${PREFIX}actor_version"))
        require(fields["${PREFIX}rule"]==RULE && fields["${PREFIX}campaign"]==campaign &&
            effect.effectKindUid=="WOUND" && effect.mechanicsOwnerUid=="UNIVERSAL_COMBAT" && effect.magnitude>0 &&
            effect.canonicalPayload["${PREFIX}fingerprint"]==fingerprint(effect,fields)) { "P62:SENSATION_BINDING_MISMATCH" }
        return effect.target
    }
    fun materialize(campaign:String,commandUid:String,order:Long?,effects:List<VerifiedMechanicsCommandEffect>):NpcActionMemory.Draft {
        val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>()
        effects.forEach { effect ->
            val recipient=recipient(campaign,effect)?:return@forEach
            require(order==effect.canonicalPayload["${PREFIX}order"]?.toLongOrNull()) { "P62:SENSATION_ORDER_MISMATCH" }
            val id=phase60Hash("$RULE|$campaign|$commandUid|${effect.effectUid}|$recipient")
            val holder=KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,recipient.uid,campaign)
            val acquisition=KnowledgeAcquisitionChange(
                KnowledgeClaim("P62:SENSE-CLAIM:$id",recipient.kindUid,recipient.uid,"P62:OWN_WOUND_SENSATION",
                    "Odczułem ból związany z własnym urazem.",domainUid=KnowledgeDomains.WORLD_SPECIFIC),
                KnowledgeAcquisitionSpec("P62:SENSE-ACQ:$id",holder,KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,
                    KnowledgeScope.PERSONAL,KnowledgeEpistemicState.KNOWN,KnowledgeQuality(1.0,1.0,1.0,1.0,1,order)),
                listOf(KnowledgeEvidenceSpec("P62:SENSE-EVIDENCE:$id",RULE,KnowledgeEvidencePolarity.SUPPORTS,
                    sourceRef=KnowledgeSourceRef.campaign(campaign,recipient.kindUid,recipient.uid))))
            val uid="P62:SENSE-CHANGE:$id"
            changes+=PlayerDomainChange.create(uid,PHASE37_KNOWLEDGE_CHANGE_KIND,acquisition,sourceRuleUid=RULE)
            val ref=DomainRef(holder.holderKindUid,holder.holderUid)
            events+=PlayerEventIntent.create("P62:SENSE-EVENT:$id",PlayerEventIntentKinds.DOMAIN_EFFECT,recipient,listOf(ref),listOf(uid),
                DomainEffectEventIntentPayload(ref,"RPGOS-EFFECT:KNOWLEDGE_ACQUISITION"))
        }
        return NpcActionMemory.Draft(changes,events)
    }
}
