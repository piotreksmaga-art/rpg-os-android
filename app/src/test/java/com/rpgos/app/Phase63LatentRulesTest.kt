package com.rpgos.app

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Phase63LatentRulesTest {
    private val source=CampaignRuleSource(CampaignRuleSourceKind.CAMPAIGN_NATIVE,"C","CORE-NATIVE-1")
    private val skeleton=CampaignWorldSkeleton.legacy("C",source,"Era",DomainRef("PLACE","START")).copy(latentRules=CoreLatentWorldRules.initial())
    @Test fun openCategoryAndTenThousandSlotsHaveStableIdentities() {
        val slots=(0L until 10_000).map { LatentWorldSlot("REGION:${it/1000}","NEW-CATEGORY",WorldElementBaseKind.OBJECT,it%1000) }
        val first=slots.associateWith { it.ref(skeleton) }
        assertEquals(10_000,first.values.distinct().size)
        val decoded=Phase63WorldCodec.readSkeleton(Phase63WorldCodec.skeleton(skeleton))
        assertEquals(first,slots.reversed().associateWith { it.ref(decoded) })
        assertNotEquals(first.values.first(),slots.first().ref(skeleton.copy(campaignUid="OTHER")))
        assertNotEquals(skeleton.domainSeed("MECHANICS","SLOT"),skeleton.domainSeed("APPEARANCE","SLOT"))
    }
    @Test fun localRuleCreatesExplicitEdgesButContainmentAndGeographyDoNot() {
        val slot=LatentWorldSlot("START","TRAINING_SITE",WorldElementBaseKind.PLACE)
        val draft=WorldElementDraft("C",slot.ref(skeleton),"Miejsce treningu",WorldElementBaseKind.PLACE,"TRAINING_SITE","START",
            setOf("TRAIN"),"LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
        val edges=CoreLatentWorldRules.localEdges(skeleton,draft,WorldTimeTick(0))
        assertEquals(2,edges.size);assertEquals(ActionDuration(300_000),edges.first().duration)
        assertTrue(CoreLatentWorldRules.localEdges(skeleton.copy(latentRules=emptyList()),draft,WorldTimeTick(0)).isEmpty())
        for(category in listOf("SEA","DESERT")) {
            assertNull(CoreLatentWorldRules.select(skeleton,slot.copy(categoryUid=category),category))
        }
        assertTrue(CoreLatentWorldRules.localEdges(skeleton,draft.copy(element=DomainRef("PLACE","AI-UID")),WorldTimeTick(0)).isEmpty())
    }
    @Test fun ruleVersionsCountsAndConditionsAreCheckedWithoutRandomizingEstablishedComponents() {
        val rule=skeleton.latentRules.first { it.baseKind==WorldElementBaseKind.OBJECT }.copy(maximumInstances=3,
            requiredAnchorTags=setOf("KNOWN"),excludedAnchorTags=setOf("FORBIDDEN"))
        val slot=LatentWorldSlot("START","OPEN-CATEGORY",WorldElementBaseKind.OBJECT,2)
        assertTrue(rule.admits(slot,"LOCAL_SITE",setOf("KNOWN")))
        assertFalse(rule.admits(slot.copy(ordinal=3),"LOCAL_SITE",setOf("KNOWN")))
        assertFalse(rule.admits(slot,"LOCAL_SITE",emptySet()))
        assertFalse(rule.admits(slot,"LOCAL_SITE",setOf("KNOWN","FORBIDDEN")))
        assertNotEquals(rule.fingerprint,rule.copy(version=2).fingerprint)
        assertEquals(rule,Phase63LatentRuleCodec.decode(Phase63LatentRuleCodec.encode(rule)))
    }
    @Test fun typedBatchesRequireAnUnbrokenVersionChainAndOneHistory() {
        val generation=HistoryGenerationUid("G")
        val root=WorldSimulationChange("C",generation,0,skeleton)
        val edge=WorldTopologyEdge("AB",1,DomainRef("PLACE","A"),DomainRef("PLACE","B"),ActionDuration(1),emptyMap(),emptySet(),WorldTimeTick(0),null,"RULE")
        val next=WorldSimulationChange("C",generation,1,null,listOf(edge))
        requireWorldSimulationChain(listOf(root,next))
        assertThrows(IllegalArgumentException::class.java) { requireWorldSimulationChain(listOf(next,root)) }
        assertThrows(IllegalArgumentException::class.java) { requireWorldSimulationChain(listOf(root,next.copy(historyGenerationUid=HistoryGenerationUid("OLD")))) }
        assertThrows(IllegalArgumentException::class.java) { requireWorldSimulationChain(listOf(root,next.copy(expectedVersion=3))) }
    }
    @Test fun namedSlotLineageSurvivesTransportAndActorGenesis() {
        val category="LOCAL_PERSON"
        val slotCategory="$category@SOURCE:PERSON:17"
        val slot=LatentWorldSlot("START",slotCategory,WorldElementBaseKind.ACTOR)
        val draft=WorldElementDraft("C",slot.ref(skeleton),"Osoba o znanej tożsamości",WorldElementBaseKind.ACTOR,category,"START",
            setOf("TALK"),"LOCAL_SITE",WorldEvidenceClassification.SOURCE_CANON,listOf("SOURCE:PERSON:17"),"https://example.org/source","17","a".repeat(64),slotCategoryUid=slotCategory)
        val reference=IntentReference("R1",IntentReferenceKind.DESCRIPTIVE,draft.displayName,"TARGET")
        val transported=LatentWorldReferenceCodec.attach(reference,draft,WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_NEARBY,"SOURCE","START",draft.sourceEvidenceUids))
        assertEquals(draft,LatentWorldReferenceCodec.decode("C",transported))
        assertNotNull(Phase63ActorGeneration.forDraft(skeleton,draft,MechanicalStateMaterialization.FULL))
        assertNull(Phase63ActorGeneration.forDraft(skeleton,draft.copy(slotCategoryUid=null),MechanicalStateMaterialization.FULL))
    }
    @Test fun aNewRequestedAffordanceRehydratesTheEstablishedSlotRatherThanRerollingIt() {
        val slot=LatentWorldSlot("START","TRAINING_SITE",WorldElementBaseKind.PLACE)
        val established=CampaignWorldElement(slot.ref(skeleton),"Poligon",slot.categoryUid,"START",setOf("LOOK"),
            "LOCAL_SITE",WorldEvidenceClassification.GENERATED_PLAUSIBLE,sourceVersion=3)
        val reference=IntentReference("R",IntentReferenceKind.DESCRIPTIVE,"miejsce treningu","TARGET",setOf("PLACE"),
            mapOf("category" to "TRAINING_SITE","affordances" to "TRAIN","topology" to "LOCAL_SITE"))
        val result=UniversalWorldMaterializationResolver().resolve("C",reference,emptyList(),"START",emptyList(),null,
            skeleton=skeleton,establishedSlot={ref->established.takeIf { it.element==ref }}) as UniversalWorldReferenceResolution.Existing
        assertEquals(established,result.element)
        assertEquals(setOf("LOOK"),result.element.affordanceUids)
    }
    @Test fun registeredConnectionConstraintsRemainVersionedDataNotModelCosts() {
        val before=skeleton.latentRules.single { it.baseKind==WorldElementBaseKind.PLACE }
        val rule=before.copy(version=2,localResourceCosts=mapOf("STAMINA" to 3),localRequiredCapabilities=setOf("CLIMB"),
            localValidityDuration=ActionDuration(600_000))
        val configured=skeleton.copy(latentRules=listOf(rule))
        val slot=LatentWorldSlot("START","OPEN_FACILITY",WorldElementBaseKind.PLACE)
        val draft=WorldElementDraft("C",slot.ref(configured),"Miejsce",slot.baseKind,slot.categoryUid,"START",setOf("LOOK"),"LOCAL_SITE",
            WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null)
        val edges=CoreLatentWorldRules.localEdges(configured,draft,WorldTimeTick(1000))
        assertEquals(2,edges.size)
        assertTrue(edges.all { it.resourceCosts==mapOf("STAMINA" to 3L) && it.requiredCapabilities==setOf("CLIMB") && it.validThrough==WorldTimeTick(601_000) })
        assertEquals(rule,Phase63LatentRuleCodec.decode(Phase63LatentRuleCodec.encode(rule)))
        assertNotEquals(before.fingerprint,rule.fingerprint)
        assertFalse("local_costs" in Phase63LatentRuleCodec.encode(before))
    }
}
