package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * Stable campaign identity stored inside campaign.db.
 *
 * Import/export keeps the UID because the database travels with the campaign.
 * Creating a new campaign from a template calls [forkIdentity] so the copy gets
 * a new identity even if the template already contains GM Engine metadata.
 */
object CampaignIdentityResolver {
    fun ensure(db: SQLiteDatabase): EntityUid {
        CampaignSourceOfTruthSchema.ensure(db)
        db.rawQuery("SELECT campaign_id FROM gm_campaign_meta LIMIT 1", null).use { c ->
            if (c.moveToFirst()) return EntityUid(c.getString(0))
        }
        return EntityUid(newUid())
    }

    fun forkIdentity(db: SQLiteDatabase): EntityUid {
        CampaignSourceOfTruthSchema.ensure(db)
        val oldUid = db.rawQuery("SELECT campaign_id FROM gm_campaign_meta LIMIT 1", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val newUid = newUid()
        if (oldUid == null) return EntityUid(newUid)

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            // Snapshot files contain absolute storage paths belonging to the
            // source campaign. A fork keeps history/state but must create its
            // own future checkpoints.
            if (tableExists(db, "gm_snapshots")) {
                db.delete("gm_snapshots", "campaign_id=?", arrayOf(oldUid))
            }
            if (tableExists(db, "gm_campaign_meta")) {
                db.execSQL(
                    "UPDATE gm_campaign_meta SET current_snapshot_id=NULL WHERE campaign_id=?",
                    arrayOf(oldUid)
                )
            }

            CAMPAIGN_SCOPED_TABLES.forEach { table ->
                if (tableExists(db, table)) {
                    db.execSQL(
                        "UPDATE $table SET campaign_id=? WHERE campaign_id=?",
                        arrayOf(newUid, oldUid)
                    )
                }
            }
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
        return EntityUid(newUid)
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean {
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { return it.moveToFirst() }
    }

    private fun newUid(): String = "CAMPAIGN-${UUID.randomUUID()}"

    private val CAMPAIGN_SCOPED_TABLES = listOf(
        "gm_campaign_meta",
        "gm_turns",
        "gm_entity_state",
        "gm_state_mutations",
        "gm_facts",
        "gm_events",
        "gm_memories",
        "gm_chronicle_entries",
        "gm_divergences"
    )
}
