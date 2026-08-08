package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class KnowledgeTransmissionIntegrityReport141(
    val ok: Boolean,
    val issues: List<GameMasterIntegrityIssue141>
)

/** Read-only audit of auditable NPC information paths. Never repairs data. */
class KnowledgeTransmissionIntegrity141(private val db: SQLiteDatabase) {
    fun check(): KnowledgeTransmissionIntegrityReport141 {
        if (!tableExists("gm_knowledge_transmissions")) {
            return KnowledgeTransmissionIntegrityReport141(ok = true, issues = emptyList())
        }
        val issues = mutableListOf<GameMasterIntegrityIssue141>()
        val campaignUid = scalarString("SELECT campaign_id FROM gm_campaign_meta LIMIT 1")
            ?: return KnowledgeTransmissionIntegrityReport141(
                ok = false,
                issues = listOf(issue("KNOWLEDGE_MISSING_CAMPAIGN", "Ledger wiedzy istnieje bez gm_campaign_meta."))
            )
        val currentTurn = scalarLong(
            "SELECT current_turn FROM gm_campaign_meta WHERE campaign_id=? LIMIT 1",
            arrayOf(campaignUid)
        ) ?: 0L

        countIssue(
            issues,
            "KNOWLEDGE_UNKNOWN_SOURCE_TRUTH",
            "Transmisja wiedzy wskazuje nieistniejący source truth.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            LEFT JOIN gm_facts s ON s.fact_id=k.source_truth_id AND s.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND s.fact_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_UNKNOWN_RESULT_BELIEF",
            "Transmisja wiedzy wskazuje nieistniejący resulting BELIEF.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            LEFT JOIN gm_facts r ON r.fact_id=k.resulting_belief_id AND r.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND r.fact_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_RESULT_NOT_BELIEF",
            "Wynik transmisji wiedzy nie jest BELIEF.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            JOIN gm_facts r ON r.fact_id=k.resulting_belief_id AND r.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND r.truth_kind!='BELIEF'
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_RECEIVER_HOLDER_MISMATCH",
            "Receiver transmisji nie jest holderem wynikowego BELIEF.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            JOIN gm_facts r ON r.fact_id=k.resulting_belief_id AND r.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND (r.holder_id IS NULL OR r.holder_id!=k.receiver_id)
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_REPORT_WITHOUT_SENDER",
            "Transmisja REPORT nie ma source_npc_id.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND channel='REPORT' AND (source_npc_id IS NULL OR trim(source_npc_id)='')
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_REPORT_SENDER_NOT_HOLDER",
            "REPORT przekazuje BELIEF, którego source NPC nie jest holderem.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            JOIN gm_facts s ON s.fact_id=k.source_truth_id AND s.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND k.channel='REPORT' AND s.truth_kind='BELIEF'
              AND (k.source_npc_id IS NULL OR s.holder_id IS NULL OR s.holder_id!=k.source_npc_id)
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_FROM_FUTURE",
            "Ledger zawiera transmisję z tury późniejszej niż current_turn.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND turn_number>?
            """.trimIndent(),
            arrayOf(campaignUid, currentTurn.toString())
        )
        countIssue(
            issues,
            "KNOWLEDGE_SOURCE_NOT_VALID_AT_TRANSFER",
            "Transmisja używa źródła przed jego powstaniem albo po jego wygaśnięciu.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            JOIN gm_facts s ON s.fact_id=k.source_truth_id AND s.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND (
                k.turn_number < s.valid_from_turn OR
                (s.valid_until_turn IS NOT NULL AND k.turn_number > s.valid_until_turn)
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_CONFIDENCE_INCREASE",
            "Confidence wynikowego BELIEF jest wyższe niż confidence źródła.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            JOIN gm_facts s ON s.fact_id=k.source_truth_id AND s.campaign_id=k.campaign_id
            JOIN gm_facts r ON r.fact_id=k.resulting_belief_id AND r.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND r.confidence > s.confidence + 0.000001
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_SELF_REFERENCE",
            "Transmisja wskazuje ten sam truth jako źródło i wynik.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND source_truth_id=resulting_belief_id
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "KNOWLEDGE_PROVENANCE_SOURCE_MISMATCH",
            "Resulting BELIEF ma provenance source_id inne niż source_truth_id ledgeru.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions k
            JOIN gm_facts r ON r.fact_id=k.resulting_belief_id AND r.campaign_id=k.campaign_id
            WHERE k.campaign_id=? AND (r.source_id IS NULL OR r.source_id!=k.source_truth_id)
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        return KnowledgeTransmissionIntegrityReport141(
            ok = issues.none { it.severity == ValidationSeverity.ERROR },
            issues = issues
        )
    }

    private fun countIssue(
        issues: MutableList<GameMasterIntegrityIssue141>,
        code: String,
        message: String,
        sql: String,
        args: Array<String>
    ) {
        val count = scalarLong(sql, args)?.toInt() ?: 0
        if (count > 0) issues += issue(code, message, count)
    }

    private fun issue(code: String, message: String, count: Int = 1) =
        GameMasterIntegrityIssue141(code, ValidationSeverity.ERROR, message, count)

    private fun scalarLong(sql: String, args: Array<String>? = null): Long? = runCatching {
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    }.getOrNull()

    private fun scalarString(sql: String, args: Array<String>? = null): String? = runCatching {
        db.rawQuery(sql, args).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull()

    private fun tableExists(name: String): Boolean = runCatching {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(name)
        ).use { it.moveToFirst() }
    }.getOrDefault(false)
}
