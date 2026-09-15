package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62ControlAndAttributionTest {
    @Test fun formerPlayerNeedsNpcAuthorityAndCurrentPlayerCannotBeTakenOver() {
        val actor=DomainRef("PLAYER","OLD_PC");val target=DomainRef("NPC","N2")
        val brain=NpcBrainOwner.initialize("C1",actor,"SEED")
        val scope=NpcDecisionScope(TemporalScope("C1","G1",1,"HASH"),actor,1,WorldTimeTick(0),0,"CURRENT_PC")
        val option=NpcActionOption("O","ATTACK",null,AcceptedActionTiming(ActionDuration(1000),"R",1),null,emptyList(),emptySet())
        val context=NpcDecisionContextEnvelope(scope,NpcTrigger("T",NpcTriggerKind.KNOWLEDGE_CHANGED,WorldTimeTick(0),
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A")),brain,emptyList(),listOf(option),8192)
        val authorization=NpcActionAuthorization.issue(context,option)
        assertTrue(runCatching{CombatIntent("I","C1",actor,target,"ATTACK",VolitionalActionSource.NPC_DECISION_ENGINE,"ACT",1)}.isFailure)
        val intent=CombatIntent("I","C1",actor,target,"ATTACK",VolitionalActionSource.NPC_DECISION_ENGINE,"ACT",1,authorization)
        val mechanical=MechanicalActorView("C1",actor,MechanicalActorKind.FORMER_PLAYER,1,MechanicalStateMaterialization.FULL,
            mapOf("POWER" to 5),emptyList(),setOf("ATTACK"),generationProvenanceUid="GEN")
        val snapshot=ImmutableCombatSnapshot("S","C1",1,listOf(mechanical),emptyList(),emptyMap(),emptyMap(),"HASH")
        assertTrue(CombatEligibilityGate().evaluate(intent,CombatAbilityContract("ATTACK"),snapshot) is CombatEligibilityResult.Eligible)
        assertEquals(CombatEligibilityResult.Rejected("ACTIVE_PLAYER_VOLITION_REQUIRES_USER_COMMAND"),
            CombatEligibilityGate().evaluate(intent,CombatAbilityContract("ATTACK"),snapshot.copy(actors=listOf(mechanical.copy(kind=MechanicalActorKind.ACTIVE_PLAYER)))))
    }
    @Test fun coalescedResourceRowRetainsBothNpcEventActors() {
        fun effect(uid:String,actor:String)=VerifiedMechanicsCommandEffect(uid,"NODE","CORE","RESOURCE_DELTA",DomainRef("PLAYER","P1"),-1,
            mapOf("resource_uid" to "HEALTH","magnitude" to "-1","source_actor_kind_uid" to "NPC","source_actor_uid" to actor),"PROOF:$uid","INPUT","OUTPUT")
        val merged=phase60CoalesceEffects(listOf(effect("E1","N1"),effect("E2","N2"))).single()
        val material=MechanicalEffectMaterializer.materialize(merged) as MechanicalEffectMaterializationResult.Materialized
        assertEquals(1,material.changes.size)
        assertEquals(2,material.eventIntents.size)
        assertEquals(setOf(DomainRef("NPC","N1"),DomainRef("NPC","N2")),material.eventIntents.map{it.actorRef}.toSet())
        assertEquals(2,material.eventIntents.map{it.eventIntentUid}.distinct().size)
        assertEquals(merged,TemporalMechanicsCodec.decode(TemporalMechanicsCodec.encode(listOf(merged))).single())
    }
    @Test fun nonCombatAbilitiesAreNeverTurnedIntoDefaultDamage() {
        val actor=DomainRef("NPC","N1");val target=DomainRef("NPC","N2")
        val base=NpcBrainOwner.initialize("C1",actor,"SEED")
        val brain=base.copy(goals=listOf(NpcGoal("G",base.motivations.first().uid,"Zdobyć informacje",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A"))))
        val records=listOf(NpcKnownRecord("R",KnowledgeEpistemicState.BELIEVED,"Rozmówca zna drogę","A",1,setOf(target)))
        val state=MechanicalActorView("C1",actor,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,mapOf("POWER" to 5),emptyList(),
            setOf("READ","HEAL","TALK","CUSTOM_UNKNOWN"),generationProvenanceUid="GEN")
        assertTrue(NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK,NpcActivityContractPort.NONE).options(brain,records,state).isEmpty())
        val options=NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK,NpcActivityContractPort.NONE).options(brain,records,state.copy(executableAbilityUids=setOf("ATTACK")))
        assertEquals(setOf(target),options.map{it.target}.toSet())
    }
    @Test fun explicitCustomAbilityKeepsItsExactIdentityAndBindsTheWholeContract() {
        val actor=DomainRef("NPC","N1");val target=DomainRef("NPC","N2")
        val base=NpcBrainOwner.initialize("C1",actor,"SEED")
        val brain=base.copy(goals=listOf(NpcGoal("G",base.motivations.first().uid,"Obronić się",NpcWeight(5000),NpcGoalLifecycle.ACTIVE,
            NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION,"A"))))
        val records=listOf(NpcKnownRecord("R",KnowledgeEpistemicState.KNOWN,"Dostrzegam cel","A",1,setOf(target)))
        val ability="Technique:WaterPalm.v2"
        val state=MechanicalActorView("C1",actor,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,mapOf("POWER" to 5),
            listOf(MechanicalResource("ENERGY",10,10)),setOf(ability,"READ"),generationProvenanceUid="GEN")
        val contract=CombatAbilityContract(ability,resourceUid="ENERGY",resourceCost=2,targetKindUids=setOf("NPC"))
        fun options(c:CombatAbilityContract)=NpcMechanicalAffordances(CombatAbilityContractPort.registered(listOf(c)),NpcActivityContractPort.NONE).options(brain,records,state)
        val option=options(contract).single()
        assertEquals(ability,option.capabilityUid)
        assertEquals(npcCombatContractFingerprint(contract),option.parameters["npc_ability_contract"])
        assertNotEquals(option.uid,options(contract.copy(resourceCost=3)).single().uid)
        assertNotEquals(option.uid,options(contract.copy(maximumRangeMillimetres=9000)).single().uid)
        assertTrue(options(contract.copy(resourceCost=11)).isEmpty())
        assertTrue(NpcMechanicalAffordances(CombatAbilityContractPort.UNIVERSAL_FALLBACK,NpcActivityContractPort.NONE).options(brain,records,state).isEmpty())
        val node=IntentNode("N",IntentForm.DIRECT_ACTION,SemanticAction(ability,rawPhrase=ability))
        assertEquals(ability,combatAbilityIdentity(node,true))
        assertEquals("ATTACK",combatAbilityIdentity(node.copy(semanticAction=SemanticAction("attack",rawPhrase="atak")),false))
    }
}
