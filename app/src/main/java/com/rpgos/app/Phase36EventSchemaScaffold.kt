package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class EventV1ToV2FaultPoint {
    BEFORE_STAGING_CREATE,
    AFTER_STAGING_CREATE,
    AFTER_COPY,
    BEFORE_DROP,
    AFTER_DROP,
    AFTER_RENAME
}

fun interface EventV1ToV2FaultInjector {
    fun failIfRequested(point: EventV1ToV2FaultPoint)

    companion object {
        val NONE = EventV1ToV2FaultInjector { }
    }
}

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

/**
 * The real physical Event v1->v2 rewrite. This code is invoked only as a Phase36 version edge,
 * inside the RUNNING transaction after PREPARED has committed. Every DDL/DML boundary therefore
 * rolls back atomically on process death before APPLIED.
 */
internal object Phase36EventV1ToV2Migration {
    private const val STAGING = "canonical_gameplay_events_v2_new"

    fun migrate(db: SQLiteDatabase, faultInjector: EventV1ToV2FaultInjector = EventV1ToV2FaultInjector.NONE) {
        check(db.inTransaction()) { "RPGOS-SCHEMA:EVENT_MIGRATION_REQUIRES_TRANSACTION" }
        val physical = Phase36EventSchemaScaffold.detectPhysicalVersion(db)
        if (physical == PHASE30_EVENT_SCHEMA_VERSION) return
        require(physical == 1) { "RPGOS-SCHEMA:EVENT_V1_SOURCE_REQUIRED:$physical" }

        listOf("rpgos_event_store_no_update", "rpgos_event_store_no_delete", "rpgos_event_store_turn_insert").forEach {
            db.execSQL("DROP TRIGGER IF EXISTS $it")
        }
        db.execSQL("DROP TABLE IF EXISTS $STAGING")
        faultInjector.failIfRequested(EventV1ToV2FaultPoint.BEFORE_STAGING_CREATE)
        createCurrentEventTable(db, STAGING)
        faultInjector.failIfRequested(EventV1ToV2FaultPoint.AFTER_STAGING_CREATE)

        db.execSQL(
            """INSERT INTO $STAGING(
                campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
                committed_order,event_ordinal,source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,
                subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,
                source_event_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version)
            SELECT e.campaign_uid,e.event_uid,e.transaction_uid,e.turn_uid,e.command_uid,e.event_intent_uid,e.event_kind_uid,
                CASE WHEN r.commit_order IS NOT NULL THEN r.commit_order ELSE NULL END,
                CASE WHEN r.commit_order IS NOT NULL THEN (
                    SELECT COUNT(*) FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} e2
                    WHERE e2.campaign_uid=e.campaign_uid AND e2.transaction_uid=e.transaction_uid
                      AND e2.event_intent_uid < e.event_intent_uid
                ) ELSE NULL END,
                e.source_actor_kind_uid,e.source_actor_uid,e.actor_ref_kind_uid,e.actor_ref_uid,
                e.subject_ref_kind_uid,e.subject_ref_uid,e.target_refs_canonical,e.causal_change_uids_canonical,e.effect_kind_uid,
                e.source_event_uid,e.resolver_kind_uid,e.resolver_version,e.semantic_fingerprint,e.schema_version
            FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} e
            LEFT JOIN turn_transaction_receipts r
              ON r.campaign_uid=e.campaign_uid AND r.transaction_uid=e.transaction_uid AND r.commit_state='COMMITTED'""".trimIndent()
        )
        faultInjector.failIfRequested(EventV1ToV2FaultPoint.AFTER_COPY)
        faultInjector.failIfRequested(EventV1ToV2FaultPoint.BEFORE_DROP)
        db.execSQL("DROP TABLE ${CampaignIntelligencePhase30Schema.EVENT_TABLE}")
        faultInjector.failIfRequested(EventV1ToV2FaultPoint.AFTER_DROP)
        db.execSQL("ALTER TABLE $STAGING RENAME TO ${CampaignIntelligencePhase30Schema.EVENT_TABLE}")
        faultInjector.failIfRequested(EventV1ToV2FaultPoint.AFTER_RENAME)
        createEventIndexes(db)
        check(Phase36EventSchemaScaffold.detectPhysicalVersion(db) == PHASE30_EVENT_SCHEMA_VERSION) {
            "RPGOS-SCHEMA:EVENT_MIGRATION_DID_NOT_REACH_V2"
        }
    }

    private fun createCurrentEventTable(db: SQLiteDatabase, table: String) {
        db.execSQL(
            """CREATE TABLE $table(
                campaign_uid TEXT NOT NULL,
                event_uid TEXT NOT NULL,
                transaction_uid TEXT NOT NULL,
                turn_uid TEXT NOT NULL,
                command_uid TEXT NOT NULL,
                event_intent_uid TEXT NOT NULL,
                event_kind_uid TEXT NOT NULL,
                committed_order INTEGER NULL CHECK(committed_order IS NULL OR committed_order > 0),
                event_ordinal INTEGER NULL CHECK(event_ordinal IS NULL OR event_ordinal >= 0),
                source_actor_kind_uid TEXT NOT NULL,
                source_actor_uid TEXT NOT NULL,
                actor_ref_kind_uid TEXT,
                actor_ref_uid TEXT,
                subject_ref_kind_uid TEXT NOT NULL,
                subject_ref_uid TEXT NOT NULL,
                target_refs_canonical TEXT NOT NULL,
                causal_change_uids_canonical TEXT NOT NULL,
                effect_kind_uid TEXT NOT NULL,
                source_event_uid TEXT,
                resolver_kind_uid TEXT NOT NULL,
                resolver_version TEXT NOT NULL,
                semantic_fingerprint TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                PRIMARY KEY(campaign_uid,event_uid),
                UNIQUE(campaign_uid,transaction_uid,event_intent_uid),
                UNIQUE(campaign_uid,transaction_uid,event_ordinal),
                CHECK((committed_order IS NULL AND event_ordinal IS NULL) OR (committed_order IS NOT NULL AND event_ordinal IS NOT NULL))
            )""".trimIndent()
        )
    }

    private fun createEventIndexes(db: SQLiteDatabase) {
        val table = CampaignIntelligencePhase30Schema.EVENT_TABLE
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canonical_gameplay_events_tx ON $table(campaign_uid,transaction_uid,event_ordinal)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_canonical_gameplay_events_order ON $table(campaign_uid,committed_order,event_ordinal) WHERE committed_order IS NOT NULL")
    }
}
