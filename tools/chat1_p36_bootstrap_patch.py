from pathlib import Path

def rep(path,old,new):
 p=Path(path);s=p.read_text();n=s.count(old)
 if n!=1: raise SystemExit(f'{path}: expected 1 match got {n}')
 p.write_text(s.replace(old,new))

p='app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt'
rep(p,'''        "rpgos_replay_commit_insert", "rpgos_replay_no_update", "rpgos_replay_no_delete"
    )''','''        "rpgos_replay_commit_insert", "rpgos_replay_no_update", "rpgos_replay_no_delete",
        "rpgos_canon_divergence_recorded_provenance_insert", "rpgos_canon_divergence_lifecycle_insert",
        "rpgos_canon_divergence_no_update", "rpgos_canon_divergence_no_delete"
    )''')
rep(p,'''    fun initialize(db: SQLiteDatabase, campaignUid: String) {
        require(campaignUid.isNotBlank()) { "RPGOS-G32:BLANK_CAMPAIGN_UID" }
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { initializeLocked(db, campaignUid) }
    }

    private fun initializeLocked(db: SQLiteDatabase, campaignUid: String) {''','''    fun initialize(db: SQLiteDatabase, campaignUid: String, safetySnapshotUid:String?=null) {
        require(campaignUid.isNotBlank()) { "RPGOS-G32:BLANK_CAMPAIGN_UID" }
        CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { initializeLocked(db, campaignUid, safetySnapshotUid) }
    }

    private fun initializeLocked(db: SQLiteDatabase, campaignUid: String, safetySnapshotUid:String?) {''')
rep(p,'''            // Upgrade the previously accepted Phase1-34 schemas first. Existing guarded databases
            // receive the ordinary ADMIN capability only for these legacy/current-schema writes.
            val ensureAcceptedSchemas = {
                CurrentSchema.ensure(db, campaignUid)
                TurnTransactionReceiptSchema.ensureReady(db)
                CampaignIntelligencePhase30Schema.ensureActivated(db, campaignUid)
                CampaignCausalGraphSchema.ensureReady(db)
                CampaignSnapshotSchema.ensureReady(db)
            }
            if (GameplayMutationDatabaseGuards.isInstalled(db)) {
                withAdministrativeMutationAuthority(db, campaignUid) { ensureAcceptedSchemas() }
            } else {
                ensureAcceptedSchemas()
            }

            // Phase36 owns its own durable PREPARED/RUNNING/APPLIED transaction. Do not nest it
            // inside the broad ADMIN transaction above or an interrupted attempt would be erased.
            check(!db.inTransaction()) { "RPGOS-SCHEMA:PHASE36_REQUIRES_TOP_LEVEL_MIGRATION_BOUNDARY" }
            Phase36SchemaVersioning.ensureReady(db, campaignUid)

            // Close/reinstall guards only after every table exists; lifecycle WRITE lock prevents
            // gameplay from observing the short administrative installation window.
            GameplayMutationDatabaseGuards.ensureInstalled(db)
''','''            // Install prerequisites that are non-Event material migration surfaces. Event Store v1->v2
            // is deliberately NOT activated here; Phase36 owns that physical migration lifecycle.
            val ensurePrePhase36Schemas = {
                CurrentSchema.ensure(db, campaignUid)
                TurnTransactionReceiptSchema.ensureReady(db)
                CampaignSnapshotSchema.ensureReady(db)
            }
            if (GameplayMutationDatabaseGuards.isInstalled(db)) withAdministrativeMutationAuthority(db,campaignUid){ensurePrePhase36Schemas()} else ensurePrePhase36Schemas()

            check(!db.inTransaction()) { "RPGOS-SCHEMA:PHASE36_REQUIRES_TOP_LEVEL_MIGRATION_BOUNDARY" }
            Phase36SchemaVersioning.ensureReady(db, campaignUid, safetySnapshotUid)

            // Only after Phase36 has established current Event physical schema may activation install
            // Event/causal triggers and writer-contract evidence.
            val ensurePostPhase36Schemas = {
                CampaignIntelligencePhase30Schema.ensureActivated(db,campaignUid)
                CampaignCausalGraphSchema.ensureReady(db)
            }
            if (GameplayMutationDatabaseGuards.isInstalled(db)) withAdministrativeMutationAuthority(db,campaignUid){ensurePostPhase36Schemas()} else ensurePostPhase36Schemas()

            GameplayMutationDatabaseGuards.ensureInstalled(db)
''')
rep(p,'''        check(tableExists(db, CampaignIntelligencePhase30Schema.EVENT_TABLE)) { "RPGOS-G32:EVENT_STORE_NOT_READY" }
''','''        check(tableExists(db, CampaignIntelligencePhase30Schema.EVENT_TABLE)) { "RPGOS-G32:EVENT_STORE_NOT_READY" }
        check(CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)==PHASE30_EVENT_SCHEMA_VERSION){"RPGOS-G32:EVENT_STORE_PHYSICAL_VERSION_NOT_READY"}
''')
print('Phase36 bootstrap patched')
