package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase63MacroRegionsTest {
    private val base=CampaignWorldSkeleton.legacy("C",CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","1"),"Era",DomainRef("PLACE","START"))
    private val skeleton=base.copy(latentRules=CoreLatentWorldRules.initial(),macroRegionRules=listOf(
        WorldMacroRegionRule("SEA-RULE",1,1,mapOf("SEA" to 1),ActionDuration(28_800_000)),
        WorldMacroRegionRule("DESERT-RULE",1,2,mapOf("DESERT" to 1),ActionDuration(57_600_000))))
    private fun candidate(category:String)=requireNotNull(LatentWorldGeography.candidate(skeleton,
        IntentReference("R",IntentReferenceKind.DESCRIPTIVE,category,"TARGET"),
        WorldReferenceShape(WorldReferenceShapeKind.CATEGORY,WorldElementBaseKind.PLACE,category,setOf("TRAVEL"),if(category=="SEA")"SEA" else "NATURAL_FEATURE")))
    @Test fun queryOrderCannotRearrangeRemoteTerrainAndRemoteEdgesDoNotGrantKnowledge() {
        val forward=listOf("SEA","DESERT").associateWith { candidate(it).draft.element }
        assertEquals(forward,listOf("DESERT","SEA").associateWith { candidate(it).draft.element })
        assertEquals(skeleton,Phase63WorldCodec.readSkeleton(Phase63WorldCodec.skeleton(skeleton)))
        assertNotEquals(skeleton.initialAnchor.uid,candidate("SEA").draft.parentAnchorUid)
        assertEquals(WorldFeasibilityState.FEASIBLE_AS_JOURNEY,candidate("SEA").feasibility.state)
        val edges=CoreLatentWorldRules.localEdges(skeleton,candidate("SEA").draft,WorldTimeTick(0))
        assertEquals(2,edges.size);assertEquals(ActionDuration(28_800_000),edges.first().duration)
        assertEquals(setOf("SAILING"),edges.first().requiredCapabilities)
        val change=WorldSimulationChange("C",HistoryGenerationUid("G"),0,skeleton,edges)
        assertTrue(WorldRouteKnowledge.materialize("C","CMD",1,CommandActorRef("PLAYER","P"),listOf(change)).changes.isEmpty())
    }
    @Test fun unknownPresetExclusionsAndChangedSlotsNeverManufactureAConvenientSea() {
        assertTrue(LatentWorldGeography.nativeRules(null).isEmpty())
        assertTrue(LatentWorldGeography.nativeRules("SPACE_STATION").isEmpty())
        val region=LatentWorldGeography.regions(skeleton).first { it.categoryUid=="SEA" }
        assertNull(LatentWorldGeography.rule(skeleton,LatentWorldSlot(region.ref.uid,"DESERT",WorldElementBaseKind.PLACE),"NATURAL_FEATURE"))
        assertNull(LatentWorldGeography.rule(skeleton,LatentWorldSlot(region.ref.uid,"SEA",WorldElementBaseKind.PLACE,1),"SEA"))
        assertNull(LatentWorldGeography.select(skeleton.copy(macroRegionRules=skeleton.macroRegionRules.map { it.copy(excludedFeatures=setOf("SEA")) }),"SEA"))
        assertTrue(LatentWorldGeography.regions(base).isEmpty())
    }
    @Test fun weightedDistributionIsIntegerVersionedAndIndependentOfClockAndOtherDomains() {
        val configured=base.copy(macroRegionRules=LatentWorldGeography.nativeRules("MOUNTAIN_SETTLEMENT"))
        val regions=LatentWorldGeography.regions(configured)
        assertEquals(8,regions.size)
        assertEquals(regions,LatentWorldGeography.regions(configured.copy(macroRegionRules=configured.macroRegionRules.asReversed())))
        assertEquals(regions.map { it.ref },LatentWorldGeography.regions(configured.copy(macroRegionRules=configured.macroRegionRules.map { it.copy(version=2) })).map { it.ref })
        assertNotEquals(configured.fingerprint,configured.copy(macroRegionRules=configured.macroRegionRules.map { it.copy(version=2) }).fingerprint)
    }
}
