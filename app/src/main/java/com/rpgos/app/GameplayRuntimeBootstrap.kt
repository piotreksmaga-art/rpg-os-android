package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Single production readiness owner for a writable campaign DB.
 * INITIALIZE is administrative and may migrate; REQUIRE READY is strictly read-only verification.
 */
internal object GameplayRuntimeBootstrap {
    private val requiredEvidenceTriggers = setOf(
        "rpgos_turn_receipts_no_update", "rpgos_turn_receipts_no_delete",
        "rpgos_event_store_no_update", "rpgos_event_store_no_delete",
        "rpgos_causal_graph_no_update", "rpgos_causal_graph_no_delete"
    )

    /** Explicit bootstrap/migration/restore boundary. Never call from an ordinary read path. */
    fun initialize(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank()) { "RPGOS-G32:BLANK_CAMPAIGN_UID" }
        val install = {
            CurrentSchema.ensure(db, campaignUid)
            TurnTransactionReceiptSchema.ensureReady(db)
            CampaignIntelligencePhase30Schema.ensureActivated(db, campaignUid)
            CampaignCausalGraphSchema.ensureReady(db)
            GameplayMutationDatabaseGuards.ensureInstalled(db)
        }
        if (GameplayMutationDatabaseGuards.isInstalled(db)) {
            withAdministrativeMutationAuthority(db, campaignUid) { install() }
        } else {
            install()
        }
        requireReady(db, campaignUid)
    }

    /** Compatibility name retained for explicit setup/tests; production reads use requireReady(). */
    @Deprecated("Use initialize() only at explicit administrative setup boundaries; reads must use requireReady()")
    fun ensureReady(db: SQLiteDatabase, campaignUid: String) = initialize(db, campaignUid)

    /** Pure verification: no DDL, migration, repair or durable write. */
    fun requireReady(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank()) { "RPGOS-G32:BLANK_CAMPAIGN_UID" }
        check(TurnTransactionReceiptSchema.isReady(db)) { "RPGOS-G32:RECEIPT_SCHEMA_NOT_READY" }
        check(CampaignIntelligencePhase30Schema.isActivated(db, campaignUid)) { "RPGOS-G32:PHASE30_NOT_ACTIVATED" }
        check(tableExists(db, CampaignIntelligencePhase30Schema.EVENT_TABLE)) { "RPGOS-G32:EVENT_STORE_NOT_READY" }
        check(CampaignCausalGraphSchema.isReady(db)) { "RPGOS-G32:CAUSAL_GRAPH_NOT_READY" }
        check(GameplayMutationDatabaseGuards.isInstalled(db)) { "RPGOS-G32:GAMEPLAY_GUARDS_NOT_READY" }
        requiredEvidenceTriggers.forEach { trigger -> check(triggerExists(db, trigger)) { "RPGOS-G32:MISSING_EVIDENCE_GUARD:$trigger" } }
        GameplayMutationDatabaseGuards.authoritativeTablesForCompatibility().filter { tableExists(db, it) }.forEach { table ->
            listOf("insert", "update", "delete").forEach { operation ->
                val trigger = "rpgos_guard_${table}_$operation"
                check(triggerExists(db, trigger)) { "RPGOS-G32:MISSING_AUTHORITY_GUARD:$trigger" }
            }
        }
        GameplayMutationDatabaseGuards.administrativeOnlyTablesForCompatibility().filter { tableExists(db, it) }.forEach { table ->
            listOf("insert", "update", "delete").forEach { operation ->
                val trigger = "rpgos_admin_guard_${table}_$operation"
                check(triggerExists(db, trigger)) { "RPGOS-G32:MISSING_ADMIN_GUARD:$trigger" }
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)
    ).use { it.moveToFirst() }

    private fun triggerExists(db: SQLiteDatabase, name: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1", arrayOf(name)
    ).use { it.moveToFirst() }
}