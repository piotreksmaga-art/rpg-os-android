package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63WorldLodTest {
    private val scope=TemporalScope("C","HGEN-C",3,"HASH")
    private val group=DomainRef("GROUP","G")
    private val person=DomainRef("ACTOR","NAMED")
    @Test fun anonymousPopulationIsOneUnitAndNamedIdentitySurvivesCoarsening() {
        val coarse=WorldLodWorkPlan(scope,listOf(WorldLodSubject(group,anonymousCount=4999),WorldLodSubject(person,group)))
        assertEquals(1,coarse.items.size)
        assertEquals(4999L,coarse.items.single().anonymousCount)
        assertEquals(setOf(person),coarse.items.single().namedActors)
        assertEquals(WorldSimulationLod.LOD0_AGGREGATE,coarse.items.single().level)
        val interaction=WorldLodWorkPlan(scope,listOf(WorldLodSubject(group,anonymousCount=4999),WorldLodSubject(person,group,directInteraction=true)))
        assertEquals(coarse.items.single().namedActors,interaction.items.single().namedActors)
        assertEquals(WorldSimulationLod.LOD3_INDIVIDUAL,interaction.items.single().level)
        assertEquals(listOf(person),interaction.individualDecisionActors)
        val pending=WorldLodWorkPlan(scope,listOf(WorldLodSubject(person,group,hasPendingProcess=true)))
        assertEquals(WorldSimulationLod.LOD2_FEATURED,pending.items.single().level)
        assertTrue(pending.individualDecisionActors.isEmpty()) // existing Phase62 deadline, not another AI decision
    }
    @Test fun formationAndActivePlayerRetainTheirAuthorityWithoutPopulationExpansion() {
        val unit=DomainRef("UNIT","U")
        val player=DomainRef("PLAYER","P")
        val plan=WorldLodWorkPlan(scope,listOf(WorldLodSubject(unit,anonymousCount=1_000_000,formation=true),
            WorldLodSubject(player,activePlayer=true,directInteraction=true)))
        assertEquals(WorldSimulationLod.LOD1_UNIT,plan.items.single { it.owner==unit }.level)
        assertEquals(WorldSimulationLod.LOD3_INDIVIDUAL,plan.items.single { it.owner==player }.level)
        assertTrue(plan.individualDecisionActors.isEmpty())
        assertEquals(0L,WorldLodWorkPlan(scope,listOf(WorldLodSubject(group))).items.single().anonymousCount)
    }
    @Test fun frontierIsStableAndBoundedWithoutDroppingWork() {
        val subjects=(0 until 80).map { WorldLodSubject(DomainRef("GROUP","G$it"),anonymousCount=5000) }
        val first=WorldLodWorkPlan(scope,subjects)
        assertEquals(first.items,WorldLodWorkPlan(scope,subjects.asReversed()).items)
        var cursor=0;val all=mutableListOf<WorldLodWorkItem>()
        do { val (batch,next)=first.batch(cursor);assertTrue(batch.size<=32);all+=batch;cursor=next?:first.items.size } while(cursor<first.items.size)
        assertEquals(first.items,all)
        assertThrows(IllegalArgumentException::class.java) { WorldLodWorkPlan(scope,listOf(subjects.first(),subjects.first())) }
    }
}
