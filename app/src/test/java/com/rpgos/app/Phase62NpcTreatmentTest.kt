package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase62NpcTreatmentTest {
    private val n=DomainRef("NPC","N")
    private val view=MechanicalActorView("C",n,MechanicalActorKind.NPC,1,MechanicalStateMaterialization.FULL,
        mapOf("DEFENCE" to 0L,"MEDICINE" to 10L),listOf(MechanicalResource("HEALTH",50,100),MechanicalResource("STAMINA",90,100)),
        setOf("MEDICAL_CARE"),conditions=listOf(MechanicalCondition("WOUND",30),MechanicalCondition("BLEEDING",1)),
        generationProvenanceUid="TEST",unwoundedDefence=20)
    private val rule=NpcTreatmentRule(mapOf("HEALTH" to 10),5,stabilizationConditionUid="BLEEDING",successAttributeUid="MEDICINE",successThreshold=5)

    @Test fun treatmentSeparatesResourcesWoundsAndStabilizationAndReportsPartialResult() {
        val result=NpcTreatmentApplication.settle(view,view,rule)
        assertEquals(NpcActivityResolutionKind.PARTIAL,result.outcome)
        assertEquals(listOf("RESOURCE_DELTA","WOUND_HEALING","CONDITION"),result.impacts.map{it.kind})
        assertEquals("REMOVE",result.impacts.last().fields["operation"])
        assertEquals(5L,result.impacts.single{it.kind=="WOUND_HEALING"}.units)
        assertEquals(50L,view.resources.first().current)
    }
    @Test fun failedAttemptAndNoFurtherNeedPayNoFutureReward() {
        val failed=NpcTreatmentApplication.settle(view.copy(attributes=mapOf("MEDICINE" to 0)),view,rule)
        assertEquals(NpcActivityResolutionKind.FAILED,failed.outcome);assertTrue(failed.impacts.isEmpty())
        val healthy=view.copy(conditions=emptyList(),resources=view.resources.map{it.copy(current=it.maximum)})
        assertFalse(NpcTreatmentApplication.needed(healthy,rule))
        assertTrue(NpcTreatmentApplication.settle(view,healthy,rule).impacts.isEmpty())
        assertThrows(IllegalArgumentException::class.java){NpcTreatmentRule(removedConditionUids=setOf("WOUND"))}
        assertThrows(IllegalArgumentException::class.java){NpcTreatmentRule(removedConditionUids=setOf("DEAD"))}
    }
    private fun effect(kind:String,units:Long,extra:Map<String,String> = emptyMap())=VerifiedMechanicsEffect("E","NODE",NpcActivityMechanics.OWNER,kind,
        mapOf("target_kind_uid" to "NPC","target_uid" to "N","magnitude" to units.toString(),"npc_treatment_contract" to rule.fingerprint,
            "expected_wound_units" to "30")+extra,"PROOF")
    @Test fun woundHealingHasExplicitOwnerAndMatchesStagedDefenceEvenWhenDefenceWasClipped() {
        val e=effect("WOUND_HEALING",15)
        val commands=canonicalMechanicsCommandEffects(e,n)!!
        val material=MechanicalEffectMaterializer.materialize(commands.single()) as MechanicalEffectMaterializationResult.Materialized
        assertEquals(-15L,(material.changes.single().payload as WoundChange).severityDelta.units)
        val staged=StagedMechanicalProjection.actor(view,listOf(e))
        assertEquals(15L,staged.conditions.single{it.conditionUid=="WOUND"}.intensity)
        assertEquals(5L,staged.attributes["DEFENCE"])
        assertTrue(MechanicalEffectMaterializer.materialize(commands.single().copy(mechanicsOwnerUid="AI")) is MechanicalEffectMaterializationResult.Rejected)
        assertThrows(IllegalArgumentException::class.java){StagedMechanicalProjection.actor(view,listOf(effect("WOUND_HEALING",31)))}
        assertThrows(IllegalArgumentException::class.java){StagedMechanicalProjection.actor(view,listOf(effect("WOUND",-1)))}
    }
    @Test fun preciseInteractionRangeCannotBeReplacedBySharedLocation() {
        val a=CombatPosition.Exact(0,0)
        assertTrue(npcWithinInteractionRange(a,CombatPosition.Exact(1200,1600),2000))
        assertFalse(npcWithinInteractionRange(a,CombatPosition.Exact(2001,0),2000))
        assertFalse(npcWithinInteractionRange(CombatPosition.Exact(Long.MAX_VALUE,0),CombatPosition.Exact(Long.MIN_VALUE,0),2000))
    }
}
