package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class GameMasterIntegrityIssue141(
    val code: String,
    val severity: ValidationSeverity,
    val message: String,
    val count: Int = 1
)

data class GameMasterIntegrityReport141(
    val ok: Boolean,
    val issues: List<GameMasterIntegrityIssue141>
)

/**
 * Offline integrity audit for migration, restore and release diagnostics.
 * It never repairs data automatically; repair must be explicit and auditable.
 */
class GameMasterIntegrity141(private val db: SQLiteDatabase) {
    fun check(): GameMasterIntegrityReport141 {
        if (!tableExists("gm_campaign_meta")) {
            return GameMasterIntegrityReport141(
                ok = true,
                issues = listOf(
                    GameMasterIntegrityIssue141(
                        "GM141_NOT_INITIALIZED",
                        ValidationSeverity.WARNING,
                        "Schemat GM141 nie został jeszcze zainicjalizowany."
                    )
                )
            )
        }

        val issues = mutableListOf<GameMasterIntegrityIssue141>()
        val campaignUid = scalarString("SELECT campaign_id FROM gm_campaign_meta LIMIT 1")
        if (campaignUid.isNullOrBlank()) {
            issues += error("MISSING_CAMPAIGN_UID", "gm_campaign_meta nie zawiera campaign_id.")
            return report(issues)
        }

        val metaTurn = scalarLong(
            "SELECT current_turn FROM gm_campaign_meta WHERE campaign_id=? LIMIT 1",
            arrayOf(campaignUid)
        ) ?: 0L
        val maxTurn = scalarLong(
            "SELECT COALESCE(MAX(turn_number),0) FROM gm_turns WHERE campaign_id=?",
            arrayOf(campaignUid)
        ) ?: 0L
        if (metaTurn != maxTurn) {
            issues += error(
                "CURRENT_TURN_MISMATCH",
                "gm_campaign_meta.current_turn=$metaTurn, ale najwyższa trwała tura=$maxTurn."
            )
        }

        addCountIssue(
            issues,
            "DUPLICATE_TURN_NUMBER",
            "Występują zduplikowane numery tur w jednej kampanii.",
            """
            SELECT COUNT(*) FROM (
              SELECT turn_number FROM gm_turns WHERE campaign_id=?
              GROUP BY turn_number HAVING COUNT(*)>1
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "EVENT_WITHOUT_TURN",
            "Eventy wskazują nieistniejącą turę.",
            """
            SELECT COUNT(*) FROM gm_events e
            LEFT JOIN gm_turns t ON t.turn_id=e.turn_id
            WHERE e.campaign_id=? AND t.turn_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "MUTATION_WITH_UNKNOWN_EVENT",
            "Mutacje wskazują nieistniejący event przyczynowy.",
            """
            SELECT COUNT(*) FROM gm_state_mutations m
            LEFT JOIN gm_events e ON e.event_id=m.caused_by_event_id
            WHERE m.campaign_id=? AND m.caused_by_event_id IS NOT NULL AND e.event_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "BELIEF_WITHOUT_HOLDER",
            "BELIEF bez holder_id narusza model wiedzy NPC.",
            """
            SELECT COUNT(*) FROM gm_facts
            WHERE campaign_id=? AND truth_kind='BELIEF' AND (holder_id IS NULL OR trim(holder_id)='')
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "INVALID_TRUTH_INTERVAL",
            "Fakty mają valid_until_turn wcześniejsze niż valid_from_turn.",
            """
            SELECT COUNT(*) FROM gm_facts
            WHERE campaign_id=? AND valid_until_turn IS NOT NULL AND valid_until_turn < valid_from_turn
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "MEMORY_WITH_UNKNOWN_EVENT",
            "Pamięć wskazuje nieistniejący event.",
            """
            SELECT COUNT(*) FROM gm_memory_event_links l
            LEFT JOIN gm_memories m ON m.memory_id=l.memory_id
            LEFT JOIN gm_events e ON e.event_id=l.event_id
            WHERE m.campaign_id=? AND e.event_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "CHRONICLE_WITH_UNKNOWN_EVENT",
            "Kronika wskazuje nieistniejący event.",
            """
            SELECT COUNT(*) FROM gm_chronicle_event_links l
            LEFT JOIN gm_chronicle_entries c ON c.chronicle_id=l.chronicle_id
            LEFT JOIN gm_events e ON e.event_id=l.event_id
            WHERE c.campaign_id=? AND e.event_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "DIVERGENCE_WITH_UNKNOWN_EVENT",
            "Divergence wskazuje nieistniejący event kampanii.",
            """
            SELECT COUNT(*) FROM gm_divergences d
            LEFT JOIN gm_events e ON e.event_id=d.caused_by_event_id
            WHERE d.campaign_id=? AND d.caused_by_event_id IS NOT NULL AND e.event_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "STATE_FROM_FUTURE",
            "Bieżący stan zawiera valid_from_turn późniejszy niż current_turn.",
            """
            SELECT COUNT(*) FROM gm_entity_state
            WHERE campaign_id=? AND valid_from_turn > ?
            """.trimIndent(),
            arrayOf(campaignUid, metaTurn.toString())
        )

        auditKnowledgeTransmissions(issues, campaignUid, metaTurn)
        return report(issues)
    }

    private fun auditKnowledgeTransmissions(
        issues: MutableList<GameMasterIntegrityIssue141>,
        campaignUid: String,
        metaTurn: Long
    ) {
        if (!tableExists("gm_knowledge_transmissions")) {
            issues += GameMasterIntegrityIssue141(
                "KNOWLEDGE_LEDGER_NOT_INITIALIZED",
                ValidationSeverity.WARNING,
                "Ledger transmisji wiedzy NPC nie został jeszcze zainicjalizowany."
            )
            return
        }

        addCountIssue(
            issues,
            "NPC_BELIEF_WITHOUT_TRANSMISSION",
            "BELIEF utworzone przez jawny kanał wiedzy nie ma rekordu transmisji.",
            """
            SELECT COUNT(*)
            FROM gm_facts f
            LEFT JOIN gm_knowledge_transmissions k
              ON k.campaign_id=f.campaign_id AND k.resulting_belief_id=f.fact_id
            WHERE f.campaign_id=?
              AND f.truth_kind='BELIEF'
              AND f.source_type IN ('NPC_OBSERVATION','NPC_REPORT','NPC_RESEARCH','NPC_INFERENCE','ORGANIZATION_REPORT')
              AND k.transmission_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_WITH_UNKNOWN_SOURCE",
            "Transmisja wiedzy wskazuje nieistniejące źródło.",
            """
            SELECT COUNT(*)
            FROM gm_knowledge_transmissions k
            LEFT JOIN gm_facts source ON source.fact_id=k.source_truth_id
            WHERE k.campaign_id=? AND source.fact_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_WITH_UNKNOWN_RESULT",
            "Transmisja wiedzy wskazuje nieistniejący wynikowy BELIEF.",
            """
            SELECT COUNT(*)
            FROM gm_knowledge_transmissions k
            LEFT JOIN gm_facts result ON result.fact_id=k.resulting_belief_id
            WHERE k.campaign_id=? AND result.fact_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_RESULT_NOT_BELIEF",
            "Wynik transmisji wiedzy nie jest BELIEF.",
            """
            SELECT COUNT(*)
            FROM gm_knowledge_transmissions k
            JOIN gm_facts result ON result.fact_id=k.resulting_belief_id
            WHERE k.campaign_id=? AND result.truth_kind!='BELIEF'
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_RECEIVER_MISMATCH",
            "Odbiorca transmisji nie jest holderem wynikowego BELIEF.",
            """
            SELECT COUNT(*)
            FROM gm_knowledge_transmissions k
            JOIN gm_facts result ON result.fact_id=k.resulting_belief_id
            WHERE k.campaign_id=? AND (result.holder_id IS NULL OR result.holder_id!=k.receiver_id)
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "REPORT_WITHOUT_SENDER",
            "Transmisja REPORT nie wskazuje nadawcy.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND channel='REPORT' AND (source_npc_id IS NULL OR trim(source_npc_id)='')
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "ORGANIZATION_WITH_NPC_SENDER",
            "Transmisja ORGANIZATION nie może wskazywać source_npc_id.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND channel='ORGANIZATION' AND source_npc_id IS NOT NULL AND trim(source_npc_id)!=''
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_FROM_FUTURE",
            "Ledger zawiera transmisję z przyszłej tury.",
            """
            SELECT COUNT(*) FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND turn_number>?
            """.trimIndent(),
            arrayOf(campaignUid, metaTurn.toString())
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_SOURCE_NOT_VALID_AT_TRANSFER",
            "Źródło transmisji nie było temporalnie ważne w chwili przekazania wiedzy.",
            """
            SELECT COUNT(*)
            FROM gm_knowledge_transmissions k
            JOIN gm_facts source ON source.fact_id=k.source_truth_id
            WHERE k.campaign_id=?
              AND (source.valid_from_turn>k.turn_number OR
                   (source.valid_until_turn IS NOT NULL AND source.valid_until_turn<k.turn_number))
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        addCountIssue(
            issues,
            "KNOWLEDGE_CHANNEL_PROVENANCE_MISMATCH",
            "Kanał transmisji nie zgadza się z provenance wynikowego BELIEF.",
            """
            SELECT COUNT(*)
            FROM gm_knowledge_transmissions k
            JOIN gm_facts result ON result.fact_id=k.resulting_belief_id
            WHERE k.campaign_id=? AND (
                (k.channel='OBSERVATION' AND result.source_type!='NPC_OBSERVATION') OR
                (k.channel='REPORT' AND result.source_type!='NPC_REPORT') OR
                (k.channel='RESEARCH' AND result.source_type!='NPC_RESEARCH') OR
                (k.channel='INFERENCE' AND result.source_type!='NPC_INFERENCE') OR
                (k.channel='ORGANIZATION' AND result.source_type!='ORGANIZATION_REPORT')
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )
    }

    private fun addCountIssue(
        issues: MutableList<GameMasterIntegrityIssue141>,
        code: String,
        message: String,
        sql: String,
        args: Array<String>
    ) {
        val count = scalarLong(sql, args)?.toInt() ?: 0
        if (count > 0) issues += GameMasterIntegrityIssue141(code, ValidationSeverity.ERROR, message, count)
    }

    private fun report(issues: List<GameMasterIntegrityIssue141>) =
        GameMasterIntegrityReport141(
            ok = issues.none { it.severity == ValidationSeverity.ERROR },
            issues = issues
        )

    private fun error(code: String, message: String) =
        GameMasterIntegrityIssue141(code, ValidationSeverity.ERROR, message)

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
