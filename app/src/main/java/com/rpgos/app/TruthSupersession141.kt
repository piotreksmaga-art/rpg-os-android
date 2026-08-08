package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Explicit lifecycle boundary for replacing one objective FACT with a newer FACT.
 *
 * Supersession never rewrites the identity/content/provenance of the predecessor. The only
 * permitted lifecycle mutation is closing its validity window immediately before the replacement
 * becomes active. Both UIDs remain durable and a ledger records why the timeline changed.
 */
data class TruthSupersessionRecord141(
    val previousTruthUid: EntityUid,
    val replacementTruthUid: EntityUid,
    val effectiveTurn: Long
) {
    init {
        require(effectiveTurn >= 0L) { "effectiveTurn nie może być ujemny." }
        require(previousTruthUid != replacementTruthUid) {
            "Supersession wymaga nowego truth UID."
        }
    }
}

interface TruthSupersession141 {
    suspend fun supersedeFact(
        previousTruthUid: EntityUid,
        replacement: CampaignTruth,
        effectiveTurn: Long
    ): CampaignTruth
}

object TruthSupersessionSchema141 {
    const val MIGRATION_ID = "GM-141-TRUTH-SUPERSESSION-V1"

    fun ensure(db: SQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                    migration_id TEXT PRIMARY KEY,
                    applied_at INTEGER NOT NULL,
                    notes TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_truth_supersessions(
                    supersession_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    previous_truth_id TEXT NOT NULL,
                    replacement_truth_id TEXT NOT NULL,
                    effective_turn INTEGER NOT NULL CHECK(effective_turn >= 0),
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, previous_truth_id),
                    UNIQUE(campaign_id, replacement_truth_id),
                    CHECK(previous_truth_id <> replacement_truth_id),
                    FOREIGN KEY(previous_truth_id) REFERENCES gm_facts(fact_id),
                    FOREIGN KEY(replacement_truth_id) REFERENCES gm_facts(fact_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_truth_supersessions_campaign_turn
                ON gm_truth_supersessions(campaign_id, effective_turn)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "GM141 explicit non-overlapping FACT supersession ledger"
                )
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}
