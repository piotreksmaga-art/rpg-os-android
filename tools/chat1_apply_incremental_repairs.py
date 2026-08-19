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
