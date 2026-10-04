package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64NpcCombatCostsTest {
    private val actor=DomainRef("NPC","N")
    private val target=DomainRef("NPC","T")
    private val option=NpcActionOption("OPTION","ATTACK",target,AcceptedActionTiming(ActionDuration(1000),Phase60CombatTime.RULE,1),
        null,emptyList(),emptySet(),resourceCosts=mapOf("STAMINA" to 2L),mechanicsOwnerUid="UNIVERSAL_COMBAT",mechanicalEffectKindUid="DAMAGE",parameters=mapOf("npc_ability_contract" to "a".repeat(64)))
    private val impact=VerifiedMechanicsCommandEffect("E","NODE","UNIVERSAL_COMBAT","DAMAGE",target,2,
        mapOf("resource_uid" to "HEALTH","p60_core_duration_ms" to "1000"),"COMBAT_PROOF","INPUT","OUTPUT")
    @Test fun registeredCostIsOneActorScopedResourceDebitAndNeverCopiesImpactPayload() {
        val cost=npcRegisteredCombatCosts(actor,option,listOf(impact)).single()
        assertEquals(actor,cost.target);assertEquals(-2L,cost.magnitude);assertEquals("STAMINA",cost.canonicalPayload["resource_uid"])
        assertEquals("1000",cost.canonicalPayload["p60_core_duration_ms"])
        assertEquals(npcRegisteredCombatCosts(actor,option,listOf(impact)).single(),cost)
        val material=MechanicalEffectMaterializer.materialize(cost) as MechanicalEffectMaterializationResult.Materialized
        assertEquals(listOf(ResourceChange(actor,"STAMINA",ExactLongDelta.of(-2))),material.changes.map { it.payload })
    }
    @Test fun zeroCostIsNotAnInventedPoolAndDuplicateDebitIsRejected() {
        assertTrue(npcRegisteredCombatCosts(actor,option.copy(resourceCosts=emptyMap()),listOf(impact)).isEmpty())
        val debit=npcRegisteredCombatCosts(actor,option,listOf(impact)).single()
        assertThrows(IllegalArgumentException::class.java) { npcRegisteredCombatCosts(actor,option,listOf(impact,debit)) }
    }
}
