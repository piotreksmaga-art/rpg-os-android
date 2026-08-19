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
