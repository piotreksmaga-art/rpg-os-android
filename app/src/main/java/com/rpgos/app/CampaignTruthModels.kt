package com.rpgos.app

enum class TruthKind {
    FACT,
    BELIEF,
    NARRATIVE
}

enum class ProvenanceSourceType {
    WORLD_CANON,
    CAMPAIGN_EVENT,
    PLAYER_ACTION,
    NPC_OBSERVATION,
    NPC_COMMUNICATION,
    NPC_INFERENCE,
    RESEARCH,
    SIMULATION,
    RULE_ENGINE,
    MANUAL_IMPORT,
    SYSTEM_MIGRATION,
    LEGACY
}

data class Provenance(
    val sourceType: ProvenanceSourceType,
    val sourceId: String? = null,
    val createdTurn: Long? = null,
    val createdEvent: String? = null,
    val confidence: Double = 1.0,
    val canonStatus: String? = null,
    val verified: Boolean = false,
    val actorUid: String? = null,
    val method: String? = null,
    val engineVersion: String? = null
) {
    init {
        require(confidence in 0.0..1.0) { "confidence musi być w zakresie 0..1" }
    }
}

data class CampaignTruthRecord(
    val truthUid: String,
    val campaignId: String,
    val kind: TruthKind,
    val subjectUid: String? = null,
    val predicate: String,
    val objectValue: String? = null,
    val perspectiveUid: String? = null,
    val narrativeText: String? = null,
    val provenance: Provenance,
    val createdAt: Long = System.currentTimeMillis(),
    val supersedesTruthUid: String? = null,
    val active: Boolean = true
) {
    init {
        require(truthUid.isNotBlank()) { "truthUid nie może być pusty" }
        require(campaignId.isNotBlank()) { "campaignId nie może być pusty" }
        require(predicate.isNotBlank()) { "predicate nie może być pusty" }
        if (kind == TruthKind.BELIEF) {
            require(!perspectiveUid.isNullOrBlank()) { "BELIEF wymaga perspectiveUid" }
        }
        if (kind == TruthKind.NARRATIVE) {
            require(!narrativeText.isNullOrBlank()) { "NARRATIVE wymaga narrativeText" }
        }
        if (kind != TruthKind.NARRATIVE) {
            require(narrativeText.isNullOrBlank()) { "Narrative text należy wyłącznie do NARRATIVE" }
        }
    }
}

object CampaignTruthPolicy {
    fun validate(record: CampaignTruthRecord): List<String> {
        val errors = mutableListOf<String>()
        if (record.truthUid.isBlank()) errors += "EMPTY_TRUTH_UID"
        if (record.campaignId.isBlank()) errors += "EMPTY_CAMPAIGN_ID"
        if (record.predicate.isBlank()) errors += "EMPTY_PREDICATE"
        if (record.provenance.confidence !in 0.0..1.0) errors += "INVALID_CONFIDENCE"
        if (record.kind == TruthKind.BELIEF && record.perspectiveUid.isNullOrBlank()) {
            errors += "BELIEF_REQUIRES_PERSPECTIVE"
        }
        if (record.kind == TruthKind.NARRATIVE && record.narrativeText.isNullOrBlank()) {
            errors += "NARRATIVE_REQUIRES_TEXT"
        }
        if (record.kind != TruthKind.NARRATIVE && !record.narrativeText.isNullOrBlank()) {
            errors += "NARRATIVE_TEXT_ON_NON_NARRATIVE"
        }
        return errors
    }

    fun narrativeCanBecomeFactAutomatically(): Boolean = false
}
