package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcConsequenceObservationTest {
    private val scope=TemporalScope("C1","G1",0,"HASH")
    private val npc=DomainRef("NPC","N1")
    private val actor=MechanicalActorView("C1",npc,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
        emptyMap(),emptyList(),emptySet(),generationProvenanceUid="GEN")
    private val wound=VerifiedMechanicsCommandEffect("E","N","UNIVERSAL_COMBAT","WOUND",npc,2,
        mapOf("source_actor_kind_uid" to "NPC","source_actor_uid" to "HIDDEN_ASSASSIN","secret_poison" to "UNKNOWN"),"PROOF","INPUT","OUTPUT")
    @Test fun directSensationDoesNotDiscloseAttackerCauseExactDamageOrBystanders() {
        val effects=NpcConsequenceObservation.annotate(scope,listOf(wound)){actor}
        val draft=NpcConsequenceObservation.materialize("C1","CMD",1,effects)
        val memory=draft.changes.single().payload as KnowledgeAcquisitionChange
        assertEquals("N1",memory.acquisition.holder.holderUid)
        assertEquals("N1",memory.claim.subjectUid)
        assertEquals(KnowledgeEpistemicState.KNOWN,memory.acquisition.epistemicState)
        assertEquals("P62:OWN_WOUND_SENSATION",memory.claim.predicateUid)
        assertFalse(draft.toString().contains("HIDDEN_ASSASSIN"));assertFalse(draft.toString().contains("secret_poison"))
        assertEquals(draft,NpcConsequenceObservation.materialize("C1","CMD",1,effects))
        assertTrue(NpcConsequenceObservation.materialize("C1","CMD",1,emptyList()).changes.isEmpty())
    }
    @Test fun missingPartialUnconsciousForeignOrPlayerActorHasNoAutomaticKnowledge() {
        listOf(null,actor.copy(materialization=MechanicalStateMaterialization.PARTIAL),
            actor.copy(conditions=listOf(MechanicalCondition("UNCONSCIOUS",1))),actor.copy(campaignUid="OTHER"),
            actor.copy(actor=DomainRef("NPC","N2")),actor.copy(kind=MechanicalActorKind.ACTIVE_PLAYER)).forEach { state ->
            val e=NpcConsequenceObservation.annotate(scope,listOf(wound)){state}.single()
            assertNull(NpcConsequenceObservation.recipient("C1",e))
        }
        val faint=wound.copy(effectUid="F",effectKindUid="CONDITION",canonicalPayload=mapOf("condition_uid" to "UNCONSCIOUS"))
        assertTrue(NpcConsequenceObservation.annotate(scope,listOf(wound,faint)){actor}.all{NpcConsequenceObservation.recipient("C1",it)==null})
        // A generic resource decrease can be hidden, so it is deliberately NOT a sensory rule.
        assertNull(NpcConsequenceObservation.recipient("C1",NpcConsequenceObservation.annotate(scope,listOf(wound.copy(effectKindUid="RESOURCE_DELTA"))){actor}.single()))
    }
    @Test fun stampCannotBeMovedToAnotherCampaignRecipientEffectOrOrder() {
        val e=NpcConsequenceObservation.annotate(scope,listOf(wound)){actor}.single()
        listOf(e.copy(target=DomainRef("NPC","N2")),e.copy(magnitude=4),e.copy(effectUid="OTHER"),
            e.copy(canonicalPayload=e.canonicalPayload+("secret_poison" to "changed"))).forEach{
            assertTrue(runCatching{NpcConsequenceObservation.recipient("C1",it)}.isFailure)
        }
        assertTrue(runCatching{NpcConsequenceObservation.materialize("OTHER","CMD",1,listOf(e))}.isFailure)
        assertTrue(runCatching{NpcConsequenceObservation.materialize("C1","CMD",2,listOf(e))}.isFailure)
        // Incoming metadata alone cannot manufacture permission when the current actor is missing.
        val stripped=NpcConsequenceObservation.annotate(scope,listOf(e)){null}.single()
        assertNull(NpcConsequenceObservation.recipient("C1",stripped))
    }
}
