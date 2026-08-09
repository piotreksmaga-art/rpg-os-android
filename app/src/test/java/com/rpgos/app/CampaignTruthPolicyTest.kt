package com.rpgos.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignTruthPolicyTest {
    @Test(expected = IllegalArgumentException::class)
    fun beliefRequiresPerspective() {
        CampaignTruthRecord(
            truthUid = "TRUTH-1",
            campaignId = "campaign-1",
            kind = TruthKind.BELIEF,
            predicate = "location",
            objectValue = "Konoha",
            provenance = Provenance(ProvenanceSourceType.NPC_INFERENCE)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun narrativeRequiresText() {
        CampaignTruthRecord(
            truthUid = "TRUTH-2",
            campaignId = "campaign-1",
            kind = TruthKind.NARRATIVE,
            predicate = "scene.description",
            provenance = Provenance(ProvenanceSourceType.CAMPAIGN_EVENT)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun factCannotCarryNarrativeText() {
        CampaignTruthRecord(
            truthUid = "TRUTH-3",
            campaignId = "campaign-1",
            kind = TruthKind.FACT,
            predicate = "location",
            objectValue = "Iron Country",
            narrativeText = "Aiko vanished into the snow.",
            provenance = Provenance(ProvenanceSourceType.CAMPAIGN_EVENT)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun provenanceRejectsConfidenceAboveOne() {
        Provenance(
            sourceType = ProvenanceSourceType.NPC_INFERENCE,
            confidence = 1.01
        )
    }

    @Test
    fun narrativeNeverAutoPromotesToFact() {
        assertFalse(CampaignTruthPolicy.narrativeCanBecomeFactAutomatically())
    }

    @Test
    fun validFactCarriesExplicitProvenance() {
        val record = CampaignTruthRecord(
            truthUid = "TRUTH-4",
            campaignId = "campaign-1",
            kind = TruthKind.FACT,
            subjectUid = "NPC-AIKO",
            predicate = "current_location",
            objectValue = "LOC-IRON-COUNTRY",
            provenance = Provenance(
                sourceType = ProvenanceSourceType.CAMPAIGN_EVENT,
                sourceId = "EVENT-42",
                createdTurn = 42,
                verified = true
            )
        )

        assertTrue(CampaignTruthPolicy.validate(record).isEmpty())
        assertTrue(record.provenance.verified)
    }
}
