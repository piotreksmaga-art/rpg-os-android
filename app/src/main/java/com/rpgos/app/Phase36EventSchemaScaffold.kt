package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Pre-Phase36 Event setup may create an absent current schema, but it must never rewrite an
 * existing legacy Event table. Physical v1->v2 migration is owned by the Phase36 EVENT edge.
 */
internal object Phase36EventSchemaScaffold {
    fun ensureWithoutMaterialMigration(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank())
        val version = detectPhysicalVersion(db)
        if (version == null || version == PHASE30_EVENT_SCHEMA_VERSION) {
            CampaignIntelligencePhase30Schema.ensureActivated(db, campaignUid)
        }
    }

    /** null means the Event table does not exist yet. */
    fun detectPhysicalVersion(db: SQLiteDatabase): Int? {
        if (!tableExists(db, CampaignIntelligencePhase30Schema.EVENT_TABLE)) return null
        val current = hasColumn(db, CampaignIntelligencePhase30Schema.EVENT_TABLE, "event_ordinal") &&
            !hasLegacyUniqueCommittedOrder(db)
        return if (current) PHASE30_EVENT_SCHEMA_VERSION else 1
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info(`$table`)", null).use { c ->
            val name = c.getColumnIndex("name")
            while (c.moveToNext()) if (name >= 0 && c.getString(name) == column) return@use true
            false
        }

    private fun hasLegacyUniqueCommittedOrder(db: SQLiteDatabase): Boolean = db.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",
        arrayOf(CampaignIntelligencePhase30Schema.EVENT_TABLE)
    ).use { c ->
        if (!c.moveToFirst() || c.isNull(0)) false
        else c.getString(0).replace(" ", "").lowercase().contains("unique(campaign_uid,committed_order)")
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)
    ).use { it.moveToFirst() }
}
