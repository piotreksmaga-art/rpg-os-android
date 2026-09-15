package com.rpgos.app

/** The NPC may remember its own accepted attempt, not infer hidden damage, success, recipients'
 * beliefs or goal achievement. Phase37 remains the only writer/owner of this acquisition. */
internal object NpcActionMemory {
    data class Draft(val changes:List<PlayerDomainChange>,val events:List<PlayerEventIntent>)
    fun materialize(campaign:String,commandUid:String,order:Long?,effects:List<VerifiedMechanicsCommandEffect>,brains:List<NpcBrainChange>):Draft {
        val actors=effects.flatMap(::mechanicSourceActors).filterNotNull().toSet()
        val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>()
        brains.groupBy{it.actor}.forEach { (actor,revisions)->
            if(actor !in actors)return@forEach
            val after=NpcBrainCodec.decode(revisions.last().stateCanonical)
            require(after.campaignUid==campaign)
            after.plans.filter{it.lifecycle==NpcPlanLifecycle.COMPLETED && it.cause==NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION,commandUid)}.forEach { plan->
                val goal=after.goals.single{it.uid==plan.goalUid}
                val uid=phase60Hash("P62:OWN_ATTEMPT:1|$campaign|$commandUid|$actor|${plan.uid}")
                val holder=after.knowledgeHolder
                val acquisition=KnowledgeAcquisitionChange(
                    KnowledgeClaim("P62:CLAIM:$uid",actor.kindUid,actor.uid,"P62:ATTEMPTED_GOAL_ACTION",goal.objective.take(450-actor.uid.length),domainUid=KnowledgeDomains.WORLD_SPECIFIC),
                    KnowledgeAcquisitionSpec("P62:ACQ:$uid",holder,KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,KnowledgeScope.PERSONAL,
                        KnowledgeEpistemicState.KNOWN,KnowledgeQuality(1.0,1.0,1.0,1.0,1,order)),
                    listOf(KnowledgeEvidenceSpec("P62:EVIDENCE:$uid","P62:OWN_ACCEPTED_ATTEMPT",KnowledgeEvidencePolarity.SUPPORTS,
                        sourceRef=KnowledgeSourceRef.campaign(campaign,actor.kindUid,actor.uid))))
                val changeUid="P62:OWN-MEMORY:$uid"
                changes+=PlayerDomainChange.create(changeUid,PHASE37_KNOWLEDGE_CHANGE_KIND,acquisition,sourceRuleUid="P62:OWN_ATTEMPT:1")
                val holderRef=DomainRef(holder.holderKindUid,holder.holderUid)
                events+=PlayerEventIntent.create("P62:OWN-MEMORY-EVENT:$uid",PlayerEventIntentKinds.DOMAIN_EFFECT,actor,
                    listOf(holderRef),listOf(changeUid),DomainEffectEventIntentPayload(holderRef,"RPGOS-EFFECT:KNOWLEDGE_ACQUISITION"))
            }
        }
        return Draft(changes,events)
    }
}
