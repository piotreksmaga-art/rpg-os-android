package com.rpgos.app

import java.util.Collections

object PlayerLedgerFamilies {
    const val FINANCE = "RPGOS-LEDGER-FAMILY:FINANCE"
    const val OWNERSHIP = "RPGOS-LEDGER-FAMILY:OWNERSHIP"
    const val PROGRESSION = "RPGOS-LEDGER-FAMILY:PROGRESSION"
    const val DEVELOPMENT_PROJECT = "RPGOS-LEDGER-FAMILY:DEVELOPMENT_PROJECT"
}

enum class PlayerLedgerProvenanceStatus {
    PROPOSAL_EVIDENCE,
    COMMITTED_FAMILY_REFERENCE,
    UNKNOWN_NOT_RECORDED
}

enum class PlayerLedgerEnvelopeClassification {
    DERIVED_PROVENANCE_REFERENCE,
    PROPOSAL_PROVENANCE
}

data class PlayerProvenanceRef(
    val campaignUid: String,
    val kindUid: String,
    val uid: String
) {
    init {
        require(campaignUid.isNotBlank() && kindUid.isNotBlank() && uid.isNotBlank())
    }
}

/**
 * Phase-23 semantic envelope only. It never owns balances/current state and has no writer capability.
 * COMMITTED_FAMILY_REFERENCE points at an existing family authority; it is not a copied committed ledger.
 */
class PlayerLedgerProvenanceEnvelope private constructor(
    val envelopeUid: String,
    val campaignUid: String,
    val characterUid: String?,
    val familyUid: String,
    val sourceRecordUid: String,
    val sourceAuthorityUid: String,
    val status: PlayerLedgerProvenanceStatus,
    val classification: PlayerLedgerEnvelopeClassification,
    evidenceRefs: List<PlayerProvenanceRef>,
    val sourceCommandUid: String?,
    val stimulusUid: String?,
    val fingerprint: String
) {
    val evidenceRefs: List<PlayerProvenanceRef> = Collections.unmodifiableList(ArrayList(evidenceRefs))

    init {
        require(envelopeUid.isNotBlank() && campaignUid.isNotBlank())
        require(characterUid?.isBlank() != true)
        require(familyUid.isNotBlank() && sourceRecordUid.isNotBlank() && sourceAuthorityUid.isNotBlank())
        require(sourceCommandUid?.isBlank() != true && stimulusUid?.isBlank() != true)
        require(this.evidenceRefs.all { it.campaignUid == campaignUid })
        require(fingerprint.isNotBlank())
        if (status == PlayerLedgerProvenanceStatus.PROPOSAL_EVIDENCE) {
            require(classification == PlayerLedgerEnvelopeClassification.PROPOSAL_PROVENANCE)
            require(sourceCommandUid != null)
        }
        if (status == PlayerLedgerProvenanceStatus.UNKNOWN_NOT_RECORDED) {
            require(this.evidenceRefs.isEmpty())
        }
    }

    override fun equals(other: Any?): Boolean = other is PlayerLedgerProvenanceEnvelope &&
        envelopeUid == other.envelopeUid && campaignUid == other.campaignUid && characterUid == other.characterUid &&
        familyUid == other.familyUid && sourceRecordUid == other.sourceRecordUid && sourceAuthorityUid == other.sourceAuthorityUid &&
        status == other.status && classification == other.classification && evidenceRefs == other.evidenceRefs &&
        sourceCommandUid == other.sourceCommandUid && stimulusUid == other.stimulusUid && fingerprint == other.fingerprint

    override fun hashCode(): Int = arrayOf(
        envelopeUid, campaignUid, characterUid, familyUid, sourceRecordUid, sourceAuthorityUid,
        status, classification, evidenceRefs, sourceCommandUid, stimulusUid, fingerprint
    ).contentHashCode()

    companion object {
        const val ENVELOPE_VERSION = "1"
        const val FINANCE_AUTHORITY = "RPGOS-AUTHORITY:FINANCIAL_LEDGER"
        const val OWNERSHIP_AUTHORITY = "RPGOS-AUTHORITY:OWNERSHIP_HISTORY"
        const val PROGRESSION_PROPOSAL_AUTHORITY = "RPGOS-PROPOSAL:PROGRESSION_LEDGER_INTENT"

        internal fun progressionProposal(
            campaignUid: String,
            characterUid: String,
            sourceCommandUid: String,
            stimulus: ProgressionStimulus,
            ledgerIntent: PlayerLedgerIntent,
            knownReferences: Set<CampaignScopedDomainRef>
        ): PlayerLedgerProvenanceEnvelope {
            require(campaignUid.isNotBlank() && characterUid.isNotBlank() && sourceCommandUid.isNotBlank())
            require(stimulus.subject.kindUid == "PLAYER" && stimulus.subject.uid == characterUid)
            require(ledgerIntent.ledgerKindUid == PlayerLedgerIntentKinds.PROGRESSION)
            val payload = ledgerIntent.payload as? ProgressionLedgerIntentPayload
                ?: throw IllegalArgumentException("PROGRESSION_PROVENANCE_PAYLOAD_REQUIRED")
            require(payload.campaignUid == campaignUid && payload.characterUid == characterUid)
            require(payload.sourceCommandUid == sourceCommandUid && payload.stimulusUid == stimulus.stimulusUid)

            val refs = canonicalEvidenceRefs(campaignUid, stimulus.evidenceRefs, knownReferences)
            return create(
                campaignUid = campaignUid,
                characterUid = characterUid,
                familyUid = PlayerLedgerFamilies.PROGRESSION,
                sourceRecordUid = ledgerIntent.ledgerIntentUid,
                sourceAuthorityUid = PROGRESSION_PROPOSAL_AUTHORITY,
                status = PlayerLedgerProvenanceStatus.PROPOSAL_EVIDENCE,
                classification = PlayerLedgerEnvelopeClassification.PROPOSAL_PROVENANCE,
                evidenceRefs = refs,
                sourceCommandUid = sourceCommandUid,
                stimulusUid = stimulus.stimulusUid
            )
        }

        fun committedFamilyReference(
            campaignUid: String,
            characterUid: String?,
            familyUid: String,
            sourceRecordUid: String,
            sourceAuthorityUid: String,
            evidenceRefs: List<PlayerProvenanceRef> = emptyList()
        ): PlayerLedgerProvenanceEnvelope = create(
            campaignUid, characterUid, familyUid, sourceRecordUid, sourceAuthorityUid,
            PlayerLedgerProvenanceStatus.COMMITTED_FAMILY_REFERENCE,
            PlayerLedgerEnvelopeClassification.DERIVED_PROVENANCE_REFERENCE,
            canonicalProvidedRefs(campaignUid, evidenceRefs), null, null
        )

        fun legacyUnknown(
            campaignUid: String,
            characterUid: String?,
            familyUid: String,
            sourceRecordUid: String,
            sourceAuthorityUid: String
        ): PlayerLedgerProvenanceEnvelope = create(
            campaignUid, characterUid, familyUid, sourceRecordUid, sourceAuthorityUid,
            PlayerLedgerProvenanceStatus.UNKNOWN_NOT_RECORDED,
            PlayerLedgerEnvelopeClassification.DERIVED_PROVENANCE_REFERENCE,
            emptyList(), null, null
        )

        private fun create(
            campaignUid: String,
            characterUid: String?,
            familyUid: String,
            sourceRecordUid: String,
            sourceAuthorityUid: String,
            status: PlayerLedgerProvenanceStatus,
            classification: PlayerLedgerEnvelopeClassification,
            evidenceRefs: List<PlayerProvenanceRef>,
            sourceCommandUid: String?,
            stimulusUid: String?
        ): PlayerLedgerProvenanceEnvelope {
            val canonicalRefs = canonicalProvidedRefs(campaignUid, evidenceRefs)
            val fingerprint = progressionFingerprint(
                "PLAYER_LEDGER_PROVENANCE_ENVELOPE", ENVELOPE_VERSION, campaignUid,
                characterUid ?: "<NULL>", familyUid, sourceRecordUid, sourceAuthorityUid,
                status.name, classification.name,
                canonicalRefs.joinToString(",") { "${it.campaignUid}:${it.kindUid}:${it.uid}" },
                sourceCommandUid ?: "<NULL>", stimulusUid ?: "<NULL>"
            )
            val uid = "RPGOS-PLAYER-PROVENANCE:$fingerprint"
            return PlayerLedgerProvenanceEnvelope(
                uid, campaignUid, characterUid, familyUid, sourceRecordUid, sourceAuthorityUid,
                status, classification, canonicalRefs, sourceCommandUid, stimulusUid, fingerprint
            )
        }

        private fun canonicalEvidenceRefs(
            campaignUid: String,
            refs: List<DomainRef>,
            knownReferences: Set<CampaignScopedDomainRef>
        ): List<PlayerProvenanceRef> {
            val canonical = refs.distinct().sortedWith(compareBy({ it.kindUid }, { it.uid }))
            canonical.forEach { ref ->
                val local = CampaignScopedDomainRef(campaignUid, ref) in knownReferences
                if (!local) {
                    val elsewhere = knownReferences.any { it.ref == ref && it.campaignUid != campaignUid }
                    throw IllegalArgumentException(if (elsewhere) "PROVENANCE_CROSS_CAMPAIGN_REFERENCE" else "PROVENANCE_UNKNOWN_REFERENCE")
                }
            }
            return canonical.map { PlayerProvenanceRef(campaignUid, it.kindUid, it.uid) }
        }

        private fun canonicalProvidedRefs(
            campaignUid: String,
            refs: List<PlayerProvenanceRef>
        ): List<PlayerProvenanceRef> {
            require(refs.all { it.campaignUid == campaignUid })
            return refs.distinct().sortedWith(compareBy({ it.campaignUid }, { it.kindUid }, { it.uid }))
        }
    }
}

/** Rebuildable semantic view. It is deliberately not persisted and cannot commit family records. */
class PlayerLedgerProvenanceView private constructor(envelopes: List<PlayerLedgerProvenanceEnvelope>) {
    val envelopes: List<PlayerLedgerProvenanceEnvelope> = Collections.unmodifiableList(
        ArrayList(envelopes.sortedWith(compareBy(
            { it.campaignUid }, { it.familyUid }, { it.sourceRecordUid }, { it.envelopeUid }
        )))
    )

    companion object {
        fun rebuild(envelopes: List<PlayerLedgerProvenanceEnvelope>) = PlayerLedgerProvenanceView(envelopes)
    }
}
