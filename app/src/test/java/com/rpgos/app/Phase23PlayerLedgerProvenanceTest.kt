package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase23PlayerLedgerProvenanceTest {
    @Test fun P23_01_progressionEvidenceRefsAreForwardedIntoDeterministicProvenance() {
        val left = progressionEnvelope(listOf(DomainRef("EVENT", "E1"), DomainRef("ENVIRONMENT", "ROOM")))
        val right = progressionEnvelope(listOf(DomainRef("ENVIRONMENT", "ROOM"), DomainRef("EVENT", "E1")))
        assertEquals(left, right)
        assertEquals(left.fingerprint, right.fingerprint)
        assertEquals(listOf("ENVIRONMENT:ROOM", "EVENT:E1"), left.evidenceRefs.map { "${it.kindUid}:${it.uid}" })
        assertEquals(PlayerLedgerProvenanceStatus.PROPOSAL_EVIDENCE, left.status)
        assertEquals(PlayerLedgerEnvelopeClassification.PROPOSAL_PROVENANCE, left.classification)
    }

    @Test fun P23_02_provenanceReferenceChangeChangesIdentityWithoutChangingProgressionAuthority() {
        val a = progressionEnvelope(listOf(DomainRef("EVENT", "E1")))
        val b = progressionEnvelope(listOf(DomainRef("EVENT", "E2")))
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.envelopeUid, b.envelopeUid)
        assertEquals(PlayerLedgerFamilies.PROGRESSION, a.familyUid)
        assertEquals(PlayerLedgerProvenanceEnvelope.PROGRESSION_PROPOSAL_AUTHORITY, a.sourceAuthorityUid)
    }

    @Test fun P23_03_crossCampaignAndUnknownEvidenceRefsFailClosed() {
        val stimulus = stimulus(listOf(DomainRef("EVENT", "E1")))
        val ledger = progressionLedger(stimulus)
        try {
            PlayerLedgerProvenanceEnvelope.progressionProposal(
                "C1", "P1", "CMD", stimulus, ledger,
                setOf(CampaignScopedDomainRef("OTHER", DomainRef("EVENT", "E1")))
            )
            throw AssertionError("cross-campaign provenance must reject")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVENANCE_CROSS_CAMPAIGN_REFERENCE", e.message)
        }
        try {
            PlayerLedgerProvenanceEnvelope.progressionProposal("C1", "P1", "CMD", stimulus, ledger, emptySet())
            throw AssertionError("unknown provenance must reject")
        } catch (e: IllegalArgumentException) {
            assertEquals("PROVENANCE_UNKNOWN_REFERENCE", e.message)
        }
    }

    @Test fun P23_04_legacyUnknownRemainsUnknownWithoutFabricatedHistory() {
        val unknown = PlayerLedgerProvenanceEnvelope.legacyUnknown(
            "C1", "P1", PlayerLedgerFamilies.PROGRESSION, "LEGACY-REC", "RPGOS-LEGACY:EVIDENCE"
        )
        assertEquals(PlayerLedgerProvenanceStatus.UNKNOWN_NOT_RECORDED, unknown.status)
        assertTrue(unknown.evidenceRefs.isEmpty())
        assertEquals(PlayerLedgerEnvelopeClassification.DERIVED_PROVENANCE_REFERENCE, unknown.classification)
    }

    @Test fun P23_05_financeAndOwnershipRemainFamilyAuthoritiesNotUnifiedTruthCopies() {
        val finance = PlayerLedgerProvenanceEnvelope.committedFamilyReference(
            "C1", "P1", PlayerLedgerFamilies.FINANCE, "FIN-TX-1",
            PlayerLedgerProvenanceEnvelope.FINANCE_AUTHORITY
        )
        val ownership = PlayerLedgerProvenanceEnvelope.committedFamilyReference(
            "C1", "P1", PlayerLedgerFamilies.OWNERSHIP, "OWN-1",
            PlayerLedgerProvenanceEnvelope.OWNERSHIP_AUTHORITY
        )
        assertEquals(PlayerLedgerProvenanceStatus.COMMITTED_FAMILY_REFERENCE, finance.status)
        assertEquals(PlayerLedgerEnvelopeClassification.DERIVED_PROVENANCE_REFERENCE, finance.classification)
        assertEquals(PlayerLedgerProvenanceEnvelope.FINANCE_AUTHORITY, finance.sourceAuthorityUid)
        assertEquals(PlayerLedgerProvenanceEnvelope.OWNERSHIP_AUTHORITY, ownership.sourceAuthorityUid)
        assertFalse(finance.sourceAuthorityUid == ownership.sourceAuthorityUid)
        val view = PlayerLedgerProvenanceView.rebuild(listOf(ownership, finance))
        assertEquals(2, view.envelopes.size)
    }

    @Test fun P23_06_envelopeAndViewHaveNoDatabaseWriterCapability() {
        val forbidden = listOf("SQLite", "Database", "Store", "Repository", "Dao", "Transaction", "Writer")
        listOf(PlayerLedgerProvenanceEnvelope::class.java, PlayerLedgerProvenanceView::class.java).forEach { type ->
            type.declaredFields.forEach { field ->
                forbidden.forEach { token -> assertFalse(field.type.name.contains(token, ignoreCase = true)) }
            }
        }
    }

    private fun progressionEnvelope(refs: List<DomainRef>): PlayerLedgerProvenanceEnvelope {
        val stimulus = stimulus(refs)
        val ledger = progressionLedger(stimulus)
        val known = refs.map { CampaignScopedDomainRef("C1", it) }.toSet()
        return PlayerLedgerProvenanceEnvelope.progressionProposal("C1", "P1", "CMD", stimulus, ledger, known)
    }

    private fun stimulus(refs: List<DomainRef>) = ProgressionStimulus.create(
        stimulusUid = "STIM", sourceTypeUid = "SRC", sourceChannelUid = ProgressionSourceChannels.PRACTICE,
        subject = DomainRef("PLAYER", "P1"), targetKindUid = ProgressionTargetKinds.STAT, targetUid = "STR",
        targetValueEvidence = ProgressionTargetValueEvidence("CURRENT", "10", "EXACT", "1"),
        progressSemanticsUid = "EXACT_UNITS", progressSemanticsVersion = "1", effortUnits = 2L,
        evidenceRefs = refs, progressionPolicyUid = "POLICY", progressionPolicyVersion = "1"
    )

    private fun progressionLedger(stimulus: ProgressionStimulus): PlayerLedgerIntent {
        val input = ProgressionEvaluationInput.create(
            campaignUid = "C1", characterUid = "P1", sourceTypeUid = stimulus.sourceTypeUid,
            sourceChannelUid = stimulus.sourceChannelUid, stimulusUid = stimulus.stimulusUid,
            sourceCommandUid = "CMD", commandKindUid = PlayerCommandKinds.TRAIN, commandFingerprint = "COMMAND-FP",
            targetKindUid = stimulus.targetKindUid, targetUid = stimulus.targetUid,
            targetValueEvidence = stimulus.targetValueEvidence,
            progressSemanticsUid = stimulus.progressSemanticsUid,
            progressSemanticsVersion = stimulus.progressSemanticsVersion,
            effortUnits = stimulus.effortUnits, calculationFactors = stimulus.calculationFactors,
            worldPackBindingIdentity = progressionWorldPackBindingIdentity(null),
            progressionPolicyUid = stimulus.progressionPolicyUid,
            progressionPolicyVersion = stimulus.progressionPolicyVersion,
            progressionEngineUid = ProgressionEngine.ENGINE_UID,
            progressionEngineVersion = ProgressionEngine.ENGINE_VERSION
        )
        return ProgressionEngine().evaluate(input).ledgerIntents.single()
    }
}
