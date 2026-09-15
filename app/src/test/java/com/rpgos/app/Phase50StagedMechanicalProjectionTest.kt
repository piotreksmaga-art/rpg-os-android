package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase50StagedMechanicalProjectionTest {
    private val npc=DomainRef("NPC","N")
    private val player=DomainRef("PLAYER","P")
    private fun actor(ref:DomainRef=npc)=MechanicalActorView("C",ref,MechanicalActorKind.NPC,1,
        MechanicalStateMaterialization.FULL,mapOf("DEFENCE" to 50L),listOf(MechanicalResource("HEALTH",100,100)),
        emptySet(),generationProvenanceUid="TEST")
    private fun effect(kind:String,target:DomainRef=npc,magnitude:Long=1,extra:Map<String,String> = emptyMap())=
        VerifiedMechanicsEffect("E","NODE","OWNER",kind,mapOf("target_kind_uid" to target.kindUid,
            "target_uid" to target.uid,"magnitude" to magnitude.toString())+extra,"PROOF","INPUT","OUTPUT")
    private fun area(kind:String="WOUND",a:Long=10,b:Long=20)=effect(kind,npc,a,mapOf(
        "area_target_count" to "2","area_target_0_kind_uid" to npc.kindUid,"area_target_0_uid" to npc.uid,
        "area_target_0_effect_kind_uid" to kind,"area_target_0_magnitude" to a.toString(),
        "area_target_1_kind_uid" to player.kindUid,"area_target_1_uid" to player.uid,
        "area_target_1_effect_kind_uid" to kind,"area_target_1_magnitude" to b.toString()))

    @Test fun areaPrimaryIsSummaryNotAnExtraHitAndMatchesCommitExpansion() {
        val effect=area();val material=canonicalMechanicsCommandEffects(effect,npc)!!
        assertEquals(2,material.size)
        for(ref in listOf(npc,player)) {
            val projected=StagedMechanicalProjection.actor(actor(ref),listOf(effect))
            val committedWounds=material.filter{it.target==ref}.flatMap{e->
                (MechanicalEffectMaterializer.materialize(e) as MechanicalEffectMaterializationResult.Materialized).changes
            }.map{it.payload as WoundChange}.sumOf{it.severityDelta.units}
            assertEquals(50-committedWounds,projected.attributes["DEFENCE"])
            assertEquals(committedWounds,projected.conditions.single{it.conditionUid=="WOUND"}.intensity)
            assertEquals(2L,projected.stateVersion)
        }
        assertEquals(40L,StagedMechanicalProjection.actor(actor(),listOf(effect)).attributes["DEFENCE"])
        assertEquals(50L,actor().attributes["DEFENCE"]) // no mutation of the input snapshot
    }

    @Test fun areaResourceChangesAreAppliedOnceAndLaterActionsSeeExhaustion() {
        val effect=area("RESOURCE_DELTA",-40,-100)
        assertEquals(60L,StagedMechanicalProjection.actor(actor(),listOf(effect)).resources.single().current)
        val exhausted=StagedMechanicalProjection.actor(actor(player),listOf(effect))
        assertFalse(NpcSpeechMechanics.canSpeak(exhausted))
        assertFalse(NpcActivityMechanics.available(exhausted,NpcActivityContractPort.STANDARD.contract("C","REST")!!))
    }

    @Test fun changingLocationInvalidatesSpeechAndOldCombatSceneBeforeCommit() {
        val transition=effect("LOCATION_TRANSITION",player,0,mapOf("destination_kind_uid" to "PLACE","destination_uid" to "OTHER"))
        assertTrue(StagedMechanicalProjection.hasSpatialChange(setOf(npc,player),listOf(transition)))
        assertTrue(StagedMechanicalProjection.hasSceneTransition(setOf(npc,player),listOf(transition)))
        assertFalse(StagedMechanicalProjection.hasSpatialChange(setOf(npc),listOf(transition)))
        assertNull(StagedMechanicalProjection.position(player,CombatPosition.Exact(0,0),listOf(transition)))
        assertNull(StagedMechanicalProjection.position(player,CombatPosition.Exact(0,0),listOf(transition,effect("MOVEMENT",player,1))))
    }

    @Test fun areaMovementUsesEachActualMemberAndCannotInventAnExactOrigin() {
        val move=area("MOVEMENT",1000,2000)
        assertEquals(CombatPosition.Exact(1100,200,300),StagedMechanicalProjection.position(npc,CombatPosition.Exact(100,200,300),listOf(move)))
        assertEquals(CombatPosition.Exact(2100,200),StagedMechanicalProjection.position(player,CombatPosition.Exact(100,200),listOf(move)))
        assertNull(StagedMechanicalProjection.position(npc,null,listOf(move)))
        assertNull(StagedMechanicalProjection.position(npc,CombatPosition.Zone("ROOM"),listOf(move)))
        assertTrue(StagedMechanicalProjection.hasSpatialChange(setOf(player),listOf(move)))
        assertFalse(StagedMechanicalProjection.hasSceneTransition(setOf(player),listOf(move)))
        val untouched=CombatPosition.Grid("G",2,3)
        assertEquals(untouched,StagedMechanicalProjection.position(npc,untouched,listOf(effect("MOVEMENT",player,3))))
    }

    @Test fun malformedAreaMetadataIsRejectedRatherThanPartiallyApplied() {
        for(bad in listOf(area().copy(canonicalPayload=area().canonicalPayload-"area_target_1_magnitude"),
            area().copy(canonicalPayload=area().canonicalPayload+("area_target_count" to "two")),
            area().copy(canonicalPayload=area().canonicalPayload+("area_target_count" to "257")))) {
            assertNull(canonicalMechanicsCommandEffects(bad,npc))
            assertThrows(IllegalArgumentException::class.java){StagedMechanicalProjection.actor(actor(),listOf(bad))}
        }
    }

    @Test fun conditionRemovalDefaultsAgreeWithMaterializer() {
        val remove=effect("CONDITION",npc,-1,mapOf("condition_uid" to "UNCONSCIOUS"))
        val base=actor().copy(conditions=listOf(MechanicalCondition("UNCONSCIOUS",1)))
        assertFalse(NpcSpeechMechanics.canSpeak(base))
        val current=StagedMechanicalProjection.actor(base,listOf(remove))
        assertTrue(NpcSpeechMechanics.canSpeak(current))
        val change=(MechanicalEffectMaterializer.materialize(canonicalMechanicsCommandEffects(remove,npc)!!.single())
            as MechanicalEffectMaterializationResult.Materialized).changes.single().payload as ConditionChange
        assertEquals(ConditionOperation.REMOVE,change.operation)
    }

    @Test fun impossibleResourceAndCoordinateArithmeticCannotYieldAUsableSnapshot() {
        assertThrows(IllegalArgumentException::class.java){StagedMechanicalProjection.actor(actor(),listOf(effect("RESOURCE_DELTA",npc,-101)))}
        val enormous=actor().copy(resources=listOf(MechanicalResource("HEALTH",Long.MAX_VALUE,Long.MAX_VALUE)))
        assertThrows(ArithmeticException::class.java){StagedMechanicalProjection.actor(enormous,listOf(effect("HEALING",npc,1)))}
        assertThrows(ArithmeticException::class.java){StagedMechanicalProjection.position(npc,CombatPosition.Exact(Long.MAX_VALUE,0),listOf(effect("MOVEMENT",npc,1)))}
    }
}
