from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    p = Path(path)
    text = p.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one old fragment, got {count}")
    p.write_text(text.replace(old, new), encoding="utf-8")

# P35: activate connection-local SQL capability only inside the sealed canonical authority scope.
replace_once(
    "app/src/main/java/com/rpgos/app/Phase35CanonDivergence.kt",
    "return try{block()}finally{activeCanonDivergenceAuthority.remove()}",
    "return try{CanonDivergenceSqlAuthority.withAuthority(db,identity,frozen,block)}finally{activeCanonDivergenceAuthority.remove()}"
)

# Phase36 owns schema creation; after the durable schema is ready, replace the evidence-only trigger
# with the connection-local authority + evidence trigger.
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''        if(plan.orderedEdges.isEmpty()){
            administrativeWrite(db,campaignUid){Phase35CanonDivergenceSchema.ensureReady(db)}
            return
        }''',
    '''        if(plan.orderedEdges.isEmpty()){
            administrativeWrite(db,campaignUid){Phase35CanonDivergenceSchema.ensureReady(db)}
            CanonDivergenceSqlAuthority.install(db)
            return
        }'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=? WHERE migration_attempt_uid=?",arrayOf(MigrationAttemptState.APPLIED.name,System.currentTimeMillis(),attempt))
            }
        }catch(t:Throwable){''',
    '''                db.execSQL("UPDATE $ATTEMPTS SET state=?,completed_at_epoch_ms=? WHERE migration_attempt_uid=?",arrayOf(MigrationAttemptState.APPLIED.name,System.currentTimeMillis(),attempt))
            }
            CanonDivergenceSqlAuthority.install(db)
        }catch(t:Throwable){'''
)

# P36-AUD-001/002/005: receipt V1/V2 rebuild is also a material physical migration. It must not
# execute in bootstrap before PREPARED/RUNNING and must have explicit per-source implementation IDs.
replace_once(
    "app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt",
    '''    fun isReady(db: SQLiteDatabase): Boolean = tableExists(db, "turn_transaction_receipts") &&
        hasColumn(db, "turn_transaction_receipts", "commit_order") &&
        hasColumn(db, "turn_transaction_receipts", "required_event_count") &&
        hasColumn(db, "turn_transaction_receipts", "required_event_manifest_fingerprint")

    fun ensureReady(db: SQLiteDatabase) {''',
    '''    fun isReady(db: SQLiteDatabase): Boolean = tableExists(db, "turn_transaction_receipts") &&
        hasColumn(db, "turn_transaction_receipts", "commit_order") &&
        hasColumn(db, "turn_transaction_receipts", "required_event_count") &&
        hasColumn(db, "turn_transaction_receipts", "required_event_manifest_fingerprint") &&
        !hasLegacyReceiptVersionCheck(db)

    /** Creates only fresh prerequisites; never rebuilds an existing legacy receipt table. */
    fun ensurePhase36Prerequisites(db:SQLiteDatabase) {
        val ownsTx=!db.inTransaction();if(ownsTx)db.beginTransaction()
        try {
            db.execSQL("""CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                migration_id TEXT PRIMARY KEY, applied_at INTEGER NOT NULL, notes TEXT)""")
            if(!tableExists(db,"turn_transaction_receipts")){
                createCurrentTable(db,"turn_transaction_receipts")
                createIndexes(db)
            }
            if(ownsTx)db.setTransactionSuccessful()
        } finally { if(ownsTx)db.endTransaction() }
    }

    fun physicalSchemaVersion(db:SQLiteDatabase):Int? {
        if(!tableExists(db,"turn_transaction_receipts"))return null
        val hasOrder=hasColumn(db,"turn_transaction_receipts","commit_order")
        val hasManifest=hasColumn(db,"turn_transaction_receipts","required_event_count")&&
            hasColumn(db,"turn_transaction_receipts","required_event_manifest_fingerprint")
        return when {
            hasManifest&&!hasLegacyReceiptVersionCheck(db) -> TURN_TRANSACTION_RECEIPT_VERSION
            hasOrder -> 2
            else -> 1
        }
    }

    fun ensureReady(db: SQLiteDatabase) {'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt",
    '''                !tableExists(db, "turn_transaction_receipts") -> createCurrentTable(db, "turn_transaction_receipts")
                !isReady(db) || hasLegacyReceiptVersionCheck(db) -> migrateReceiptTable(db)
            }
            createIndexes(db)''',
    '''                !tableExists(db, "turn_transaction_receipts") -> createCurrentTable(db, "turn_transaction_receipts")
                !isReady(db) || hasLegacyReceiptVersionCheck(db) -> migrateToCurrent(db)
            }
            createIndexes(db)'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/TurnTransactionReceiptStore.kt",
    '''    private fun migrateReceiptTable(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS turn_transaction_receipts_v32_new")''',
    '''    internal fun migrateToCurrent(db:SQLiteDatabase) {
        require(tableExists(db,"turn_transaction_receipts")){"RPGOS-SCHEMA:RECEIPT_TABLE_MISSING"}
        if(isReady(db))return
        migrateReceiptTable(db)
        createIndexes(db)
    }

    private fun migrateReceiptTable(db: SQLiteDatabase) {
        db.execSQL("DROP TABLE IF EXISTS turn_transaction_receipts_v32_new")'''
)

replace_once(
    "app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt",
    '''                CurrentSchema.ensure(db, campaignUid)
                TurnTransactionReceiptSchema.ensureReady(db)
                CampaignSnapshotSchema.ensureReady(db)''',
    '''                CurrentSchema.ensure(db, campaignUid)
                TurnTransactionReceiptSchema.ensurePhase36Prerequisites(db)
                CampaignSnapshotSchema.ensureReady(db)'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt",
    '''            val ensurePostPhase36Schemas = {
                CampaignIntelligencePhase30Schema.ensureActivated(db,campaignUid)
                CampaignCausalGraphSchema.ensureReady(db)
            }''',
    '''            val ensurePostPhase36Schemas = {
                TurnTransactionReceiptSchema.ensureReady(db)
                CampaignIntelligencePhase30Schema.ensureActivated(db,campaignUid)
                CampaignCausalGraphSchema.ensureReady(db)
            }'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/GameplayRuntimeBootstrap.kt",
    '''        Phase36SchemaVersioning.requireReady(db)''',
    '''        Phase36SchemaVersioning.requireReady(db,campaignUid)'''
)

replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''    val migrationManifest:List<MigrationEdge> = listOf(
        MigrationEdge(SchemaFamilyUid.EVENT,1,PHASE30_EVENT_SCHEMA_VERSION,"RPGOS-P36-EVENT-V1-V2-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,injector->
            CampaignIntelligencePhase30Schema.migrateEventTableIfNeeded(db,injector)
        }
    )''',
    '''    val migrationManifest:List<MigrationEdge> = listOf(
        MigrationEdge(SchemaFamilyUid.RECEIPT,1,TURN_TRANSACTION_RECEIPT_VERSION,"RPGOS-P36-RECEIPT-V1-V3-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,_->
            TurnTransactionReceiptSchema.migrateToCurrent(db)
        },
        MigrationEdge(SchemaFamilyUid.RECEIPT,2,TURN_TRANSACTION_RECEIPT_VERSION,"RPGOS-P36-RECEIPT-V2-V3-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,_->
            TurnTransactionReceiptSchema.migrateToCurrent(db)
        },
        MigrationEdge(SchemaFamilyUid.EVENT,1,PHASE30_EVENT_SCHEMA_VERSION,"RPGOS-P36-EVENT-V1-V2-R1",MigrationMateriality.MATERIAL_DATA_MUTATION){db,injector->
            CampaignIntelligencePhase30Schema.migrateEventTableIfNeeded(db,injector)
        }
    )'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''                val adopted=if(c.family==SchemaFamilyUid.EVENT) CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)?:c.currentVersion else c.currentVersion''',
    '''                val adopted=when(c.family){
                    SchemaFamilyUid.EVENT -> CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)?:c.currentVersion
                    SchemaFamilyUid.RECEIPT -> TurnTransactionReceiptSchema.physicalSchemaVersion(db)?:c.currentVersion
                    else -> c.currentVersion
                }'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''    private fun validatePhysicalMetadataConsistency(db:SQLiteDatabase){
        val physical=CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)
        val metadata=currentMetadata(db,SchemaFamilyUid.EVENT)
        if(physical!=null&&metadata!=null)require(physical==metadata){"RPGOS-SCHEMA:EVENT_PHYSICAL_METADATA_MISMATCH:$physical:$metadata"}
    }''',
    '''    private fun validatePhysicalMetadataConsistency(db:SQLiteDatabase){
        val eventPhysical=CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db)
        val eventMetadata=currentMetadata(db,SchemaFamilyUid.EVENT)
        if(eventPhysical!=null&&eventMetadata!=null)require(eventPhysical==eventMetadata){"RPGOS-SCHEMA:EVENT_PHYSICAL_METADATA_MISMATCH:$eventPhysical:$eventMetadata"}
        val receiptPhysical=TurnTransactionReceiptSchema.physicalSchemaVersion(db)
        val receiptMetadata=currentMetadata(db,SchemaFamilyUid.RECEIPT)
        if(receiptPhysical!=null&&receiptMetadata!=null)require(receiptPhysical==receiptMetadata){"RPGOS-SCHEMA:RECEIPT_PHYSICAL_METADATA_MISMATCH:$receiptPhysical:$receiptMetadata"}
    }'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''    fun requireReady(db:SQLiteDatabase){''',
    '''    fun requireReady(db:SQLiteDatabase,campaignUid:String){
        require(campaignUid.isNotBlank())'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''        check(db.rawQuery("SELECT 1 FROM $ATTEMPTS WHERE state IN (?,?) LIMIT 1",arrayOf(MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name)).use{!it.moveToFirst()}){"RPGOS-SCHEMA:INCOMPLETE_MIGRATION"}''',
    '''        check(db.rawQuery("SELECT 1 FROM $ATTEMPTS WHERE campaign_uid=? AND state IN (?,?) LIMIT 1",arrayOf(campaignUid,MigrationAttemptState.PREPARED.name,MigrationAttemptState.RUNNING.name)).use{!it.moveToFirst()}){"RPGOS-SCHEMA:INCOMPLETE_MIGRATION"}'''
)

# Upgrade old Phase36 attempt tables to a real DB-level state CHECK after validating existing rows.
replace_once(
    "app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt",
    '''        ensureColumn(db,ATTEMPTS,"source_vector_canonical","TEXT")
        ensureColumn(db,ATTEMPTS,"target_vector_canonical","TEXT")
    }''',
    '''        ensureColumn(db,ATTEMPTS,"source_vector_canonical","TEXT")
        ensureColumn(db,ATTEMPTS,"target_vector_canonical","TEXT")
        if(!attemptTableHasStateCheck(db)){
            validateAttemptStateVocabulary(db)
            db.beginTransaction();try{
                db.execSQL("DROP TABLE IF EXISTS ${ATTEMPTS}_p36_checked")
                db.execSQL("""CREATE TABLE ${ATTEMPTS}_p36_checked(
                    migration_attempt_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,source_vector_fingerprint TEXT NOT NULL,target_vector_fingerprint TEXT NOT NULL,
                    source_vector_canonical TEXT,target_vector_canonical TEXT,plan_fingerprint TEXT NOT NULL,plan_version INTEGER NOT NULL,safety_snapshot_uid TEXT,
                    state TEXT NOT NULL CHECK(state IN ('PREPARED','RUNNING','APPLIED','FAILED')),started_at_epoch_ms INTEGER NOT NULL,completed_at_epoch_ms INTEGER,failure_code TEXT)""")
                db.execSQL("""INSERT INTO ${ATTEMPTS}_p36_checked(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,source_vector_canonical,target_vector_canonical,plan_fingerprint,plan_version,safety_snapshot_uid,state,started_at_epoch_ms,completed_at_epoch_ms,failure_code)
                    SELECT migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,source_vector_canonical,target_vector_canonical,plan_fingerprint,plan_version,safety_snapshot_uid,state,started_at_epoch_ms,completed_at_epoch_ms,failure_code FROM $ATTEMPTS""")
                db.execSQL("DROP TABLE $ATTEMPTS")
                db.execSQL("ALTER TABLE ${ATTEMPTS}_p36_checked RENAME TO $ATTEMPTS")
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
        }
    }'''
)
phase36 = Path("app/src/main/java/com/rpgos/app/Phase36SchemaVersioning.kt")
p36core = phase36.read_text(encoding="utf-8")
state_marker='''    private fun adoptMissingFamilyVersions(db:SQLiteDatabase,campaignUid:String){'''
state_method='''    private fun attemptTableHasStateCheck(db:SQLiteDatabase):Boolean = db.rawQuery(
        "SELECT sql FROM sqlite_master WHERE type='table' AND name=?",arrayOf(ATTEMPTS)
    ).use{c->c.moveToFirst()&&!c.isNull(0)&&c.getString(0).replace(" ","").contains("CHECK(stateIN('PREPARED','RUNNING','APPLIED','FAILED'))",ignoreCase=true)}

'''
if state_method not in p36core:
    if state_marker not in p36core: raise SystemExit("attempt check marker missing")
    p36core=p36core.replace(state_marker,state_method+state_marker,1)
phase36.write_text(p36core,encoding="utf-8")

# P36-AUD-003: one shared anchor definition must also work on legacy receipt V2 snapshots, before
# the receipt migration has installed V3 manifest columns.
replace_once(
    "app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt",
    '''            val anchor=TurnTransactionReceiptStore(captured).lastValidCommit(campaignUid)
            val order=anchor?.commitOrder?:0L
            require(order==snapshot.anchorCommitOrder){"RPGOS-SNAPSHOT:ANCHOR_ORDER_MISMATCH"}
            require(anchor?.transactionUid==snapshot.anchorTransactionUid&&anchor?.turnUid==snapshot.anchorTurnUid){"RPGOS-SNAPSHOT:ANCHOR_IDENTITY_MISMATCH"}
            val eventUid=anchor?.transactionUid?.let{tx->anchorEventUid(captured,campaignUid,tx)}
            require(eventUid==snapshot.anchorEventUid){"RPGOS-SNAPSHOT:ANCHOR_EVENT_MISMATCH"}''',
    '''            val anchor=lastCommittedAnchor(captured,campaignUid)
            require(anchor.commitOrder==snapshot.anchorCommitOrder){"RPGOS-SNAPSHOT:ANCHOR_ORDER_MISMATCH"}
            require(anchor.transactionUid==snapshot.anchorTransactionUid&&anchor.turnUid==snapshot.anchorTurnUid){"RPGOS-SNAPSHOT:ANCHOR_IDENTITY_MISMATCH"}
            require(anchor.eventUid==snapshot.anchorEventUid){"RPGOS-SNAPSHOT:ANCHOR_EVENT_MISMATCH"}'''
)
replace_once(
    "app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt",
    '''        val last=TurnTransactionReceiptStore(db).lastValidCommit(campaignUid)?.commitOrder?:0L
        require(snapshot.anchorCommitOrder<=last){"RPGOS-SNAPSHOT:STALE_SNAPSHOT_ANCHOR"}''',
    '''        val last=lastCommittedAnchor(db,campaignUid).commitOrder
        require(snapshot.anchorCommitOrder<=last){"RPGOS-SNAPSHOT:STALE_SNAPSHOT_ANCHOR"}'''
)
anchor_marker = '''    private fun find(db:SQLiteDatabase,campaignUid:String,uid:String):CampaignSnapshotDescriptor? {'''
anchor_method = '''    internal fun lastCommittedAnchor(db:SQLiteDatabase,campaignUid:String):CapturedSnapshotAnchor {
        val receiptTable="turn_transaction_receipts"
        val exists=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(receiptTable)).use{it.moveToFirst()}
        if(!exists)return CapturedSnapshotAnchor(0L,null,null,null)
        val hasOrder=db.rawQuery("PRAGMA table_info($receiptTable)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)=="commit_order")found=true;found}
        if(!hasOrder)return CapturedSnapshotAnchor(0L,null,null,null)
        return db.rawQuery("""SELECT transaction_uid,turn_uid,commit_order FROM $receiptTable
            WHERE campaign_uid=? AND commit_state='COMMITTED' AND commit_order IS NOT NULL ORDER BY commit_order DESC LIMIT 1""",arrayOf(campaignUid)).use{c->
            if(!c.moveToFirst())CapturedSnapshotAnchor(0L,null,null,null)
            else { val tx=c.getString(0);CapturedSnapshotAnchor(c.getLong(2),tx,c.getString(1),anchorEventUid(db,campaignUid,tx)) }
        }
    }

'''
snap = Path("app/src/main/java/com/rpgos/app/CampaignSnapshotSystem.kt")
snap_text = snap.read_text(encoding="utf-8")
if anchor_method not in snap_text:
    if anchor_marker not in snap_text: raise SystemExit("snapshot anchor insertion marker missing")
    snap_text=snap_text.replace(anchor_marker,anchor_method+anchor_marker,1)
old_capture='''                val receipt=TurnTransactionReceiptStore(capturedDb).lastValidCommit(campaignUid)
                CapturedSnapshotAnchor(
                    commitOrder=receipt?.commitOrder?:0L,
                    transactionUid=receipt?.transactionUid,
                    turnUid=receipt?.turnUid,
                    eventUid=receipt?.transactionUid?.let{tx->CampaignSnapshotRecoveryPolicy.anchorEventUid(capturedDb,campaignUid,tx)}
                )'''
new_capture='''                CampaignSnapshotRecoveryPolicy.lastCommittedAnchor(capturedDb,campaignUid)'''
if new_capture not in snap_text:
    if old_capture not in snap_text: raise SystemExit("snapshot capture anchor fragment missing")
    snap_text=snap_text.replace(old_capture,new_capture,1)
old_reconstruct='''    fun reconstructToVerifiedStaging():File {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { reconstructLocked() }
    }

    private fun reconstructLocked():File {
        reconcileOrphansLocked()
        val snapshot=list().firstOrNull{CampaignSnapshotRecoveryPolicy.isRecoverable(db,campaignUid,it)}
            ?:error("RPGOS-SNAPSHOT:NO_VALID_COMPATIBLE_SNAPSHOT")'''
new_reconstruct='''    fun reconstructToVerifiedStaging():File {
        requireAdministrativeRecoveryEntryPoint()
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { reconstructLocked(null) }
    }

    internal fun reconstructSnapshotToVerifiedStaging(snapshotUid:String):File {
        requireAdministrativeRecoveryEntryPoint()
        require(snapshotUid.isNotBlank())
        return CampaignRuntimeLifecycleLock.withRecovery(campaignUid) { reconstructLocked(snapshotUid) }
    }

    private fun reconstructLocked(requiredSnapshotUid:String?):File {
        reconcileOrphansLocked()
        val snapshot=(if(requiredSnapshotUid==null) list().firstOrNull{CampaignSnapshotRecoveryPolicy.isRecoverable(db,campaignUid,it)}
            else find(requiredSnapshotUid)?.takeIf{CampaignSnapshotRecoveryPolicy.isRecoverable(db,campaignUid,it)})
            ?:error("RPGOS-SNAPSHOT:NO_VALID_COMPATIBLE_SNAPSHOT")'''
if new_reconstruct not in snap_text:
    if old_reconstruct not in snap_text: raise SystemExit("snapshot reconstruct fragment missing")
    snap_text=snap_text.replace(old_reconstruct,new_reconstruct,1)
snap.write_text(snap_text,encoding="utf-8")

# P36 regression fixture: the non-replayable interval test performs a real financial turn, so the
# financial canonical state must exist first.
replace_once(
    "app/src/test/java/com/rpgos/app/Phase36SchemaVersioningTest.kt",
    '''        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val material=listOf(edge(SchemaFamilyUid.EVENT,1,2,"TEST-MATERIAL"))

            val corrupted=''',
    '''        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db,"C1")
            val material=listOf(edge(SchemaFamilyUid.EVENT,1,2,"TEST-MATERIAL"))

            val corrupted='''
)

# Update the manifest golden now that receipt material edges are explicit.
replace_once(
    "app/src/test/java/com/rpgos/app/Phase36SchemaVersioningTest.kt",
    '''        assertEquals("EVENT:1->2:RPGOS-P36-EVENT-V1-V2-R1:MATERIAL_DATA_MUTATION",Phase36SchemaVersioning.migrationManifestCanonical)''',
    '''        assertEquals(listOf(
            "EVENT:1->2:RPGOS-P36-EVENT-V1-V2-R1:MATERIAL_DATA_MUTATION",
            "RECEIPT:1->3:RPGOS-P36-RECEIPT-V1-V3-R1:MATERIAL_DATA_MUTATION",
            "RECEIPT:2->3:RPGOS-P36-RECEIPT-V2-V3-R1:MATERIAL_DATA_MUTATION"
        ).joinToString("\\n"),Phase36SchemaVersioning.migrationManifestCanonical)'''
)

# Exact same-snapshot implication test: acceptance is followed by the actual Phase33 reconstruction
# engine for that UID, not merely a second predicate call.
p36 = Path("app/src/test/java/com/rpgos/app/Phase36SchemaVersioningTest.kt")
p36_text = p36.read_text(encoding="utf-8")
old_matrix='''                if(accepted){
                    assertTrue("Phase36 accepted $kind but recovery rejects it",CampaignSnapshotRecoveryPolicy.isRecoverable(db,"C1",snapshot))
                    CampaignSnapshotRecoveryPolicy.requireRecoverable(db,"C1",snapshot)
                }'''
new_matrix='''                if(accepted){
                    assertTrue("Phase36 accepted $kind but recovery rejects it",CampaignSnapshotRecoveryPolicy.isRecoverable(db,"C1",snapshot))
                    CampaignSnapshotRecoveryPolicy.requireRecoverable(db,"C1",snapshot)
                    val staging=CampaignSnapshotManager(db,"C1",dir).reconstructSnapshotToVerifiedStaging(snapshot.snapshotUid)
                    assertTrue(staging.isFile);staging.delete()
                }'''
if new_matrix not in p36_text:
    if old_matrix not in p36_text: raise SystemExit("snapshot kind matrix fragment missing")
    p36_text=p36_text.replace(old_matrix,new_matrix,1)

receipt_test_marker='''    @Test fun versionEdgeGraphFailsClosedAndFingerprintUsesActualSourceRoute() {'''
receipt_test='''    @Test fun realReceiptV2RebuildRunsInsidePhase36MaterialLifecycle() {
        val file=File(root,"receipt-v2.db");val dir=File(root,"receipt-v2-snaps")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db,"C1")
            commitTurn(db,"C1","RECEIPT-V2")
            rebuildReceiptAsV2(db)
            db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=2 WHERE schema_family_uid=?",arrayOf(SchemaFamilyUid.RECEIPT.name))
            assertEquals(2,TurnTransactionReceiptSchema.physicalSchemaVersion(db))
            val before=receiptHistory(db,"C1")
            val safety=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)
            GameplayRuntimeBootstrap.initialize(db,"C1")
            assertEquals(TURN_TRANSACTION_RECEIPT_VERSION,TurnTransactionReceiptSchema.physicalSchemaVersion(db))
            assertEquals(before,receiptHistory(db,"C1"))
            assertTrue(countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='APPLIED'")>=1)
        }
    }

'''
if receipt_test not in p36_text:
    if receipt_test_marker not in p36_text: raise SystemExit("receipt test insertion marker missing")
    p36_text=p36_text.replace(receipt_test_marker,receipt_test+receipt_test_marker,1)

campaign_scope_marker='''    @Test fun malformedMigrationStateIsRejectedByDatabaseCheck() {'''
campaign_scope_test='''    @Test fun activeAttemptForAnotherCampaignDoesNotPoisonReadyCampaign() {
        val file=File(root,"attempt-campaign-scope.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val current=targetVector();val plan=MigrationPlanRegistry.fingerprint(MigrationPlan(current,emptyList()))
            insertAttempt(db,"FOREIGN-C2","C2",MigrationAttemptState.PREPARED,current,current,plan,Phase36SchemaVersioning.PLAN_VERSION,null)
            GameplayRuntimeBootstrap.requireReady(db,"C1")
            Phase36SchemaVersioning.ensureReady(db,"C1")
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"migration_attempt_uid='FOREIGN-C2' AND state='PREPARED'"))
        }
    }

'''
if campaign_scope_test not in p36_text:
    if campaign_scope_marker not in p36_text: raise SystemExit("campaign scope insertion marker missing")
    p36_text=p36_text.replace(campaign_scope_marker,campaign_scope_test+campaign_scope_marker,1)

helpers_marker='''    private fun rebuildEventAsV1(db:SQLiteDatabase){'''
receipt_helpers='''    private fun rebuildReceiptAsV2(db:SQLiteDatabase){
        listOf("rpgos_turn_receipts_commit_insert","rpgos_turn_receipts_no_update","rpgos_turn_receipts_no_delete",
            "rpgos_guard_turn_transaction_receipts_insert","rpgos_guard_turn_transaction_receipts_update","rpgos_guard_turn_transaction_receipts_delete").forEach{db.execSQL("DROP TRIGGER IF EXISTS $it")}
        db.execSQL("DROP TABLE IF EXISTS turn_transaction_receipts_v2_test")
        db.execSQL("""CREATE TABLE turn_transaction_receipts_v2_test(
            transaction_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,command_uid TEXT NOT NULL,
            semantic_fingerprint TEXT NOT NULL,result_fingerprint TEXT NOT NULL,commit_order INTEGER NULL CHECK(commit_order IS NULL OR commit_order>0),
            receipt_version INTEGER NOT NULL CHECK(receipt_version IN (1,2)),commit_state TEXT NOT NULL CHECK(commit_state='COMMITTED'),
            UNIQUE(campaign_uid,command_uid))""")
        db.execSQL("""INSERT INTO turn_transaction_receipts_v2_test(transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,commit_order,receipt_version,commit_state)
            SELECT transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,commit_order,2,commit_state FROM turn_transaction_receipts""")
        db.execSQL("DROP TABLE turn_transaction_receipts")
        db.execSQL("ALTER TABLE turn_transaction_receipts_v2_test RENAME TO turn_transaction_receipts")
    }

    private fun receiptHistory(db:SQLiteDatabase,campaign:String)=db.rawQuery("""SELECT transaction_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,COALESCE(CAST(commit_order AS TEXT),'NULL')
        FROM turn_transaction_receipts WHERE campaign_uid=? ORDER BY transaction_uid""",arrayOf(campaign)).use{c->buildList{while(c.moveToNext())add((0 until c.columnCount).joinToString("|"){i->c.getString(i)})}}}

'''
if receipt_helpers not in p36_text:
    if helpers_marker not in p36_text: raise SystemExit("receipt helper insertion marker missing")
    p36_text=p36_text.replace(helpers_marker,receipt_helpers+helpers_marker,1)
p36.write_text(p36_text,encoding="utf-8")

# P35 regression: forged writable SQL context must still fail even if the attacker reuses genuine
# committed receipt/event/replay identities from a legal turn.
p35 = Path("app/src/test/java/com/rpgos/app/Phase35CanonDivergenceTest.kt")
text = p35.read_text(encoding="utf-8")
marker = '''    @Test fun administrativeAuthorityForeignCampaignAndMissingProvenanceCannotCallRecordCommitted() {'''
new_test = '''    @Test fun rawSqlCannotReuseRealCanonicalEvidenceToForgeRecordedDivergence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            commit(db,"REAL-EVIDENCE",spec("DIV-REAL","CANON","CAMPAIGN"))
            val legal=CanonDivergenceStore(db,"C1").list().single()
            db.execSQL("DELETE FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}")
            db.execSQL("INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}(campaign_uid,capability_kind) VALUES('C1','TURN')")
            val failure=runCatching {
                rawRecordedInsert(db,"DIV-FORGED-REAL",legal.createdTransactionUid!!,legal.createdTurnUid!!,legal.createdEventUid!!,"FORGED-EXPECTATION")
            }.exceptionOrNull()
            assertNotNull(failure)
            db.execSQL("DELETE FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE_NAME}")
            assertEquals(listOf("DIV-REAL"),CanonDivergenceStore(db,"C1").list().map{it.spec.divergenceUid})
        }
    }

'''
if new_test not in text:
    if marker not in text:
        raise SystemExit("Phase35 test insertion marker missing")
    text = text.replace(marker, new_test + marker, 1)

old_helper = '''    private fun rawRecordedInsert(db:SQLiteDatabase,uid:String){
        db.execSQL("""INSERT INTO ${Phase35CanonDivergenceSchema.TABLE}(divergence_uid,campaign_uid,canonical_subject_kind_uid,canonical_subject_uid,canonical_expectation_uid,world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,actual_campaign_value,lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,divergence_schema_version,created_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(uid,"C1","CHARACTER","P1","CANON-EXPECTATION-1","WORLD-A","1","OUTCOME","CANON","CAMPAIGN","ACTIVE","TX-FAKE","TURN-FAKE","EVENT-FAKE","RECORDED",1,1L))
    }'''
new_helper = '''    private fun rawRecordedInsert(db:SQLiteDatabase,uid:String,tx:String="TX-FAKE",turn:String="TURN-FAKE",event:String="EVENT-FAKE",expectation:String="CANON-EXPECTATION-1"){
        db.execSQL("""INSERT INTO ${Phase35CanonDivergenceSchema.TABLE}(divergence_uid,campaign_uid,canonical_subject_kind_uid,canonical_subject_uid,canonical_expectation_uid,world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,actual_campaign_value,lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,divergence_schema_version,created_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(uid,"C1","CHARACTER","P1",expectation,"WORLD-A","1","OUTCOME","CANON","CAMPAIGN","ACTIVE",tx,turn,event,"RECORDED",1,1L))
    }'''
if new_helper not in text:
    if old_helper not in text:
        raise SystemExit("Phase35 raw helper fragment missing")
    text = text.replace(old_helper, new_helper, 1)
p35.write_text(text, encoding="utf-8")

print("incremental repairs applied")
