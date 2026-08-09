package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class TruthSupersessionIntegrityReport141(
    val ok: Boolean,
    val issues: List<GameMasterIntegrityIssue141>
)

/**
 * Read-only offline audit of the durable FACT supersession graph.
 *
 * This checker deliberately never repairs data. A damaged timeline must be surfaced first so any
 * repair can be explicit, reviewed and auditable instead of silently rewriting campaign history.
 */
class TruthSupersessionIntegrity141(private val db: SQLiteDatabase) {
    fun check(): TruthSupersessionIntegrityReport141 {
        if (!tableExists("gm_truth_supersessions")) {
            return TruthSupersessionIntegrityReport141(ok = true, issues = emptyList())
        }

        val issues = mutableListOf<GameMasterIntegrityIssue141>()
        val campaignUid = scalarString("SELECT campaign_id FROM gm_campaign_meta LIMIT 1")
            ?: return TruthSupersessionIntegrityReport141(
                ok = false,
                issues = listOf(
                    issue(
                        "SUPERSESSION_MISSING_CAMPAIGN",
                        "Ledger supersession istnieje bez trwałego campaign_id."
                    )
                )
            )

        countIssue(
            issues,
            "SUPERSESSION_MISSING_PREVIOUS",
            "Supersession wskazuje nieistniejący predecessor FACT.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            LEFT JOIN gm_facts p
              ON p.campaign_id=s.campaign_id AND p.fact_id=s.previous_truth_id
            WHERE s.campaign_id=? AND p.fact_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_MISSING_REPLACEMENT",
            "Supersession wskazuje nieistniejący replacement FACT.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            LEFT JOIN gm_facts r
              ON r.campaign_id=s.campaign_id AND r.fact_id=s.replacement_truth_id
            WHERE s.campaign_id=? AND r.fact_id IS NULL
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_SELF_REFERENCE",
            "Supersession wskazuje ten sam truth jako predecessor i replacement.",
            """
            SELECT COUNT(*) FROM gm_truth_supersessions
            WHERE campaign_id=? AND previous_truth_id=replacement_truth_id
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_BRANCHED_PREVIOUS",
            "Jeden predecessor FACT ma więcej niż jednego bezpośredniego następcę.",
            """
            SELECT COUNT(*) FROM (
                SELECT previous_truth_id
                FROM gm_truth_supersessions
                WHERE campaign_id=?
                GROUP BY previous_truth_id
                HAVING COUNT(*)>1
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_BRANCHED_REPLACEMENT",
            "Jeden replacement FACT ma więcej niż jednego bezpośredniego poprzednika.",
            """
            SELECT COUNT(*) FROM (
                SELECT replacement_truth_id
                FROM gm_truth_supersessions
                WHERE campaign_id=?
                GROUP BY replacement_truth_id
                HAVING COUNT(*)>1
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_PREVIOUS_NOT_FACT",
            "Predecessor supersession nie jest obiektywnym FACT.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            JOIN gm_facts p
              ON p.campaign_id=s.campaign_id AND p.fact_id=s.previous_truth_id
            WHERE s.campaign_id=?
              AND (p.truth_kind!='FACT' OR p.holder_id IS NOT NULL)
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_REPLACEMENT_NOT_FACT",
            "Replacement supersession nie jest obiektywnym FACT.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            JOIN gm_facts r
              ON r.campaign_id=s.campaign_id AND r.fact_id=s.replacement_truth_id
            WHERE s.campaign_id=?
              AND (r.truth_kind!='FACT' OR r.holder_id IS NOT NULL)
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_SEMANTIC_MISMATCH",
            "Predecessor i replacement nie opisują tego samego subject/predicate.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            JOIN gm_facts p
              ON p.campaign_id=s.campaign_id AND p.fact_id=s.previous_truth_id
            JOIN gm_facts r
              ON r.campaign_id=s.campaign_id AND r.fact_id=s.replacement_truth_id
            WHERE s.campaign_id=?
              AND (NOT (p.subject_id IS r.subject_id) OR p.predicate!=r.predicate)
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_PREVIOUS_WINDOW_MISMATCH",
            "Predecessor nie kończy się dokładnie w turze poprzedzającej effective_turn.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            JOIN gm_facts p
              ON p.campaign_id=s.campaign_id AND p.fact_id=s.previous_truth_id
            WHERE s.campaign_id=? AND (
                s.effective_turn<=p.valid_from_turn OR
                p.valid_until_turn IS NULL OR
                p.valid_until_turn!=s.effective_turn-1
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )
        countIssue(
            issues,
            "SUPERSESSION_REPLACEMENT_WINDOW_MISMATCH",
            "Replacement nie zaczyna się dokładnie w effective_turn albo ma niepoprawne okno ważności.",
            """
            SELECT COUNT(*)
            FROM gm_truth_supersessions s
            JOIN gm_facts r
              ON r.campaign_id=s.campaign_id AND r.fact_id=s.replacement_truth_id
            WHERE s.campaign_id=? AND (
                r.valid_from_turn!=s.effective_turn OR
                (r.valid_until_turn IS NOT NULL AND r.valid_until_turn<r.valid_from_turn)
            )
            """.trimIndent(),
            arrayOf(campaignUid)
        )

        val cycleCount = countCycles(campaignUid)
        if (cycleCount > 0) {
            issues += issue(
                "SUPERSESSION_CYCLE",
                "Łańcuch supersession zawiera cykl i nie ma jednoznacznego kierunku historii.",
                cycleCount
            )
        }

        return TruthSupersessionIntegrityReport141(
            ok = issues.none { it.severity == ValidationSeverity.ERROR },
            issues = issues
        )
    }

    private fun countCycles(campaignUid: String): Int {
        val edges = linkedMapOf<String, MutableList<String>>()
        db.rawQuery(
            """
            SELECT previous_truth_id,replacement_truth_id
            FROM gm_truth_supersessions
            WHERE campaign_id=?
            ORDER BY previous_truth_id,replacement_truth_id
            """.trimIndent(),
            arrayOf(campaignUid)
        ).use { c ->
            while (c.moveToNext()) {
                edges.getOrPut(c.getString(0)) { mutableListOf() } += c.getString(1)
            }
        }

        val state = mutableMapOf<String, Int>() // 0=unseen, 1=visiting, 2=done
        var cycles = 0

        fun visit(node: String) {
            when (state[node] ?: 0) {
                1 -> {
                    cycles++
                    return
                }
                2 -> return
            }
            state[node] = 1
            edges[node].orEmpty().forEach(::visit)
            state[node] = 2
        }

        (edges.keys + edges.values.flatten()).distinct().forEach { node ->
            if ((state[node] ?: 0) == 0) visit(node)
        }
        return cycles
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
