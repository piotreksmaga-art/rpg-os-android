package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignTruthChangeIntegrationTest {

    @Test
    fun campaignTruthChange_has_no_invented_domain_references() {
        val draft = PlayerResolutionDraft.create(changes = listOf(change(baseTruth())))

        assertTrue(draftReferences(draft).isEmpty())
    }

    @Test
    fun campaignTruthChange_effect_fingerprint_is_deterministic_and_binds_complete_semantics() {
        val base = baseTruth()
        val baseFingerprint = fingerprint(base)

        assertEquals(baseFingerprint, fingerprint(base.copy()))

        val semanticVariants = listOf(
            base.copy(truthUid = "TRUTH-2"),
            base.copy(kind = TruthKind.BELIEF, narrativeText = null, perspectiveUid = "PERSPECTIVE-1"),
            base.copy(subjectUid = "SUBJECT-2"),
            base.copy(predicate = "predicate.changed"),
            base.copy(objectValue = "OBJECT-2"),
            base.copy(perspectiveUid = "PERSPECTIVE-2"),
            base.copy(narrativeText = "different narrative"),
            base.copy(supersedesTruthUid = "TRUTH-OLD-2")
        )

        semanticVariants.forEach { variant ->
            assertNotEquals(baseFingerprint, fingerprint(variant))
        }
    }

    private fun fingerprint(payload: CampaignTruthChange): String =
        WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes = listOf(change(payload))))
            .deterministicFingerprint()

    private fun change(payload: CampaignTruthChange): PlayerDomainChange = PlayerDomainChange.create(
        changeUid = "CH-TRUTH",
        changeKindUid = PlayerChangeKinds.CAMPAIGN_TRUTH,
        payload = payload
    )

    private fun baseTruth() = CampaignTruthChange(
        truthUid = "TRUTH-1",
        kind = TruthKind.NARRATIVE,
        subjectUid = "SUBJECT-1",
        predicate = "narrative.about",
        objectValue = "OBJECT-1",
        perspectiveUid = "PERSPECTIVE-1",
        narrativeText = "canonical narrative",
        supersedesTruthUid = "TRUTH-OLD-1"
    )
}
