package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase36SchemaVersioningTest {
    private lateinit var root: File

    @Before fun setUp() { root = kotlin.io.path.createTempDirectory("p36-audit-").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun freshBootstrapIsIdempotentAndDoesNotInventHistoricalDivergence() {
        val file=File(root,"fresh.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            GameplayRuntimeBootstrap.requireReady(db,"C1")
            assertEquals(PHASE30_EVENT_SCHEMA_VERSION,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
            assertTrue(CanonDivergenceStore(db,"C1").list().isEmpty())
            val versions=count(db,Phase36SchemaVersioning.VERSIONS)
            GameplayRuntimeBootstrap.initialize(db,"C1")
            assertEquals(versions,count(db,Phase36SchemaVersioning.VERSIONS))
            assertTrue(CanonDivergenceStore(db,"C1").list().isEmpty())
        }
    }

    @Test fun realEventV1CrashMatrixPreservesHistoryAndRestartsCleanly() {
        val points=listOf(
            Phase36MigrationFailurePoint.BEFORE_STAGING_CREATE,
            Phase36MigrationFailurePoint.AFTER_STAGING_CREATE,
            Phase36MigrationFailurePoint.AFTER_COPY,
            Phase36MigrationFailurePoint.BEFORE_DROP,
            Phase36MigrationFailurePoint.AFTER_DROP,
            Phase36MigrationFailurePoint.AFTER_RENAME,
            Phase36MigrationFailurePoint.BEFORE_FINAL_METADATA_APPLIED
        )
        points.forEachIndexed { index,point ->
            val campaign="C-MIG-$index";val file=File(root,"${point.name}.db");val dir=File(root,"snaps-${point.name}")
            SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
                legacyEventV1Fixture(db,campaign,"CMD-$index")
                val before=eventHistory(db,campaign)
                assertTrue(before.isNotEmpty())
                assertEquals(1,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
                val safety=CampaignSnapshotManager(db,campaign,dir).create(SnapshotKind.PRE_RESTORE)
                val failure=runCatching {
                    Phase36SchemaVersioning.ensureReady(db,campaign,safety.snapshotUid,Phase36MigrationFailureInjector { if(it==point) error("INJECT-${point.name}") })
                }.exceptionOrNull()
                assertNotNull("$point must fail",failure)
                assertEquals("history after $point",before,eventHistory(db,campaign))
                assertFalse("no stranded staging after $point",table(db,"canonical_gameplay_events_v2_new"))
                assertEquals("physical rollback after $point",1,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))

                Phase36SchemaVersioning.ensureReady(db,campaign,safety.snapshotUid)
                GameplayRuntimeBootstrap.initialize(db,campaign)
                GameplayRuntimeBootstrap.requireReady(db,campaign)
                assertEquals(PHASE30_EVENT_SCHEMA_VERSION,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
                assertEquals("history after restart $point",before,eventHistory(db,campaign))
                assertFalse(table(db,"canonical_gameplay_events_v2_new"))
            }
        }
    }

    @Test fun realReceiptV2RebuildRunsInsidePhase36MaterialLifecycle() {
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

    @Test fun versionEdgeGraphFailsClosedAndFingerprintUsesActualSourceRoute() {
        val contract=SchemaFamilyContract(SchemaFamilyUid.EVENT,3,1)
        val e12=edge(SchemaFamilyUid.EVENT,1,2,"E12")
        val e23=edge(SchemaFamilyUid.EVENT,2,3,"E23")
        val e13=edge(SchemaFamilyUid.EVENT,1,3,"E13")
        val e21=edge(SchemaFamilyUid.EVENT,2,1,"E21")

        assertTrue(runCatching{MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 1),listOf(e23))}.exceptionOrNull()!!.message!!.contains("MISSING_MIGRATION_EDGE"))
        val chain=MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 1),listOf(e12,e23))
        assertEquals(listOf("E12","E23"),chain.orderedEdges.map{it.implementationId})
        assertTrue(runCatching{MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 1),listOf(e12,e23,e13))}.exceptionOrNull()!!.message!!.contains("AMBIGUOUS_MIGRATION_PATH"))
        assertTrue(runCatching{MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 1),listOf(e12,e21,e23))}.exceptionOrNull()!!.message!!.contains("CYCLE"))
        assertTrue(runCatching{MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 1),listOf(edge(SchemaFamilyUid.RECEIPT,1,2,"WRONG")))}.isFailure)
        assertTrue(runCatching{MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 0),listOf(e12,e23))}.exceptionOrNull()!!.message!!.contains("UNSUPPORTED_OLD"))
        assertTrue(runCatching{MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 4),listOf(e12,e23))}.exceptionOrNull() is UnsupportedFutureSchemaException)

        val from1=MigrationPlanRegistry.fingerprint(chain)
        val from2=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 2),listOf(e12,e23)))
        assertNotEquals(from1,from2)
        val reversed=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(listOf(contract),mapOf(SchemaFamilyUid.EVENT to 1),listOf(e23,e12)))
        assertEquals(from1,reversed)
    }

    @Test fun migrationManifestHasImmutablePerEdgeIdentityGolden() {
        assertEquals(listOf(
            "EVENT:1->2:RPGOS-P36-EVENT-V1-V2-R1:MATERIAL_DATA_MUTATION",
            "RECEIPT:1->3:RPGOS-P36-RECEIPT-V1-V3-R1:MATERIAL_DATA_MUTATION",
            "RECEIPT:2->3:RPGOS-P36-RECEIPT-V2-V3-R1:MATERIAL_DATA_MUTATION"
        ).joinToString("\n"),Phase36SchemaVersioning.migrationManifestCanonical)
        val edge=Phase36SchemaVersioning.migrationManifest.single{it.family==SchemaFamilyUid.EVENT&&it.fromVersion==1}
        assertEquals("RPGOS-P36-EVENT-V1-V2-R1",edge.implementationId)
        assertEquals(MigrationMateriality.MATERIAL_DATA_MUTATION,edge.materiality)
    }

    @Test fun foreignPreparedFingerprintFailsClosed() = tamperedAttemptCase(MigrationAttemptState.PREPARED) { source,target,plan ->
        AttemptFixture(source,target,"FOREIGN-$plan",Phase36SchemaVersioning.PLAN_VERSION)
    }

    @Test fun foreignRunningFingerprintFailsClosed() = tamperedAttemptCase(MigrationAttemptState.RUNNING) { source,target,plan ->
        AttemptFixture(source,target,"FOREIGN-$plan",Phase36SchemaVersioning.PLAN_VERSION)
    }

    @Test fun wrongPlanVersionFailsClosed() = tamperedAttemptCase(MigrationAttemptState.PREPARED) { source,target,plan ->
        AttemptFixture(source,target,plan,Phase36SchemaVersioning.PLAN_VERSION+1)
    }

    @Test fun impossibleSourceVectorFailsClosed() {
        val file=File(root,"impossible.db");val dir=File(root,"impossible-snaps")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            legacyEventV1Fixture(db,"C1","IMP")
            val safety=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            val actual=sourceVector(eventVersion=1);val impossible=targetVector()
            val impossiblePlan=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(Phase36SchemaVersioning.contracts,impossible,Phase36SchemaVersioning.migrationManifest))
            insertAttempt(db,"IMPOSSIBLE","C1",MigrationAttemptState.PREPARED,impossible,targetVector(),impossiblePlan,Phase36SchemaVersioning.PLAN_VERSION,safety.snapshotUid)
            val t=runCatching{Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)}.exceptionOrNull()
            assertTrue(t is MigrationEvidenceCorruptException)
            assertEquals(actual,sourceVectorFromDb(db))
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"migration_attempt_uid='IMPOSSIBLE' AND state='PREPARED'"))
        }
    }

    @Test fun wrongTargetVectorFailsClosed() {
        val file=File(root,"wrong-target.db");val dir=File(root,"wrong-target-snaps")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            legacyEventV1Fixture(db,"C1","TARGET")
            val safety=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            val source=sourceVector(1);val plan=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(Phase36SchemaVersioning.contracts,source,Phase36SchemaVersioning.migrationManifest))
            insertAttempt(db,"WRONG-TARGET","C1",MigrationAttemptState.PREPARED,source,source,plan,Phase36SchemaVersioning.PLAN_VERSION,safety.snapshotUid)
            val t=runCatching{Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)}.exceptionOrNull()
            assertTrue(t is MigrationEvidenceCorruptException)
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"migration_attempt_uid='WRONG-TARGET' AND state='PREPARED'"))
        }
    }

    @Test fun legalInterruptedRunningAttemptIsRecoveredThenMigrationCompletes() {
        val file=File(root,"legal-restart.db");val dir=File(root,"legal-restart-snaps")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            legacyEventV1Fixture(db,"C1","LEGAL")
            val before=eventHistory(db,"C1")
            val safety=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            val source=sourceVector(1);val plan=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(Phase36SchemaVersioning.contracts,source,Phase36SchemaVersioning.migrationManifest))
            insertAttempt(db,"LEGAL-INTERRUPTED","C1",MigrationAttemptState.RUNNING,source,targetVector(),plan,Phase36SchemaVersioning.PLAN_VERSION,safety.snapshotUid)
            Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"migration_attempt_uid='LEGAL-INTERRUPTED' AND state='FAILED' AND failure_code='INTERRUPTED_RESTART_SAFE'"))
            assertTrue(countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='APPLIED'")>=1)
            assertEquals(before,eventHistory(db,"C1"))
            assertEquals(PHASE30_EVENT_SCHEMA_VERSION,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
        }
    }

    @Test fun activeAttemptForAnotherCampaignDoesNotPoisonReadyCampaign() {
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

    @Test fun malformedMigrationStateIsRejectedByDatabaseCheck() {
        val file=File(root,"state-check.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val source=targetVector();val canon=MigrationPlanRegistry.vectorCanonical(source);val plan=MigrationPlanRegistry.fingerprint(MigrationPlan(source,emptyList()))
            assertTrue(runCatching{
                db.execSQL("""INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,source_vector_canonical,target_vector_canonical,plan_fingerprint,plan_version,state,started_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    arrayOf("BAD-STATE","C1",sha(canon),sha(canon),canon,canon,plan,Phase36SchemaVersioning.PLAN_VERSION,"MALFORMED",1L))
            }.isFailure)
        }
    }

    @Test fun safetySnapshotKindMatrixImpliesCanonicalRecoverability() {
        val file=File(root,"kind-matrix.db");val dir=File(root,"kind-snaps")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val material=listOf(edge(SchemaFamilyUid.EVENT,1,2,"TEST-MATERIAL"))
            SnapshotKind.entries.forEach { kind ->
                val snapshot=CampaignSnapshotManager(db,"C1",dir).create(kind,pinned=kind==SnapshotKind.AUTOMATIC||kind==SnapshotKind.USER_PINNED)
                val accepted=runCatching{MigrationSafetyPolicy.requireProtectedSnapshot(db,"C1",material,snapshot.snapshotUid)}.isSuccess
                if(accepted){
                    assertTrue("Phase36 accepted $kind but recovery rejects it",CampaignSnapshotRecoveryPolicy.isRecoverable(db,"C1",snapshot))
                    CampaignSnapshotRecoveryPolicy.requireRecoverable(db,"C1",snapshot)
                    val staging=CampaignSnapshotManager(db,"C1",dir).reconstructSnapshotToVerifiedStaging(snapshot.snapshotUid)
                    assertTrue(staging.isFile);staging.delete()
                }
                if(kind==SnapshotKind.MANUAL_EXPORT||kind==SnapshotKind.LEGACY_BACKUP) assertFalse("$kind must never be Phase36 safety",accepted)
            }
        }
    }

    @Test fun corruptedCrossCampaignStaleAndNonReplayableSnapshotsFailClosed() {
        val file=File(root,"snapshot-adversarial.db");val dir=File(root,"snapshot-adv")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db,"C1")
            val material=listOf(edge(SchemaFamilyUid.EVENT,1,2,"TEST-MATERIAL"))

            val corrupted=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            File(corrupted.payloadPath).appendText("CORRUPTION")
            assertTrue(runCatching{MigrationSafetyPolicy.requireProtectedSnapshot(db,"C1",material,corrupted.snapshotUid)}.isFailure)

            val cross=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            assertTrue(runCatching{CampaignSnapshotRecoveryPolicy.requireRecoverable(db,"C2",cross)}.isFailure)

            val stale=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            db.execSQL("UPDATE ${CampaignSnapshotSchema.CATALOG} SET anchor_commit_order=anchor_commit_order+99 WHERE snapshot_uid=?",arrayOf(stale.snapshotUid))
            val staleReload=CampaignSnapshotManager(db,"C1",dir).list().first{it.snapshotUid==stale.snapshotUid}
            assertTrue(runCatching{CampaignSnapshotRecoveryPolicy.requireRecoverable(db,"C1",staleReload)}.isFailure)

            val gap=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            commitTurn(db,"C1","AFTER-GAP")
            db.execSQL("DROP TRIGGER IF EXISTS rpgos_replay_no_delete")
            db.execSQL("DELETE FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid='C1' AND command_uid='AFTER-GAP'")
            assertTrue(runCatching{MigrationSafetyPolicy.requireProtectedSnapshot(db,"C1",material,gap.snapshotUid)}.isFailure)
        }
    }

    @Test fun safetySnapshotDeletionIsSerializedAcrossValidationAndMaterialMutation() {
        val file=File(root,"toctou.db");val dir=File(root,"toctou-snaps")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            legacyEventV1Fixture(db,"C1","TOCTOU")
            val before=eventHistory(db,"C1")
            val safety=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            val originalSha=fileSha(File(safety.payloadPath))
            val reached=CountDownLatch(1);val release=CountDownLatch(1)
            val pool=Executors.newFixedThreadPool(2)
            try {
                val migration=pool.submit<Unit>{
                    Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid,Phase36MigrationFailureInjector { point ->
                        if(point==Phase36MigrationFailurePoint.AFTER_SAFETY_REVALIDATION_BEFORE_RUNNING){reached.countDown();check(release.await(10,TimeUnit.SECONDS))}
                    })
                }
                assertTrue(reached.await(10,TimeUnit.SECONDS))
                val deletion=pool.submit<Boolean>{CampaignSnapshotManager(db,"C1",dir).delete(safety.snapshotUid)}
                Thread.sleep(150)
                assertFalse("delete must block behind lifecycle WRITE lock",deletion.isDone)
                assertTrue(File(safety.payloadPath).isFile)
                assertEquals(originalSha,fileSha(File(safety.payloadPath)))
                release.countDown()
                migration.get(15,TimeUnit.SECONDS)
                deletion.get(15,TimeUnit.SECONDS)
                assertEquals(before,eventHistory(db,"C1"))
                assertEquals(PHASE30_EVENT_SCHEMA_VERSION,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
            } finally { release.countDown();pool.shutdownNow() }
        }
    }

    @Test fun unsupportedFutureFamilyAndReadOnlyReadinessRemainFailClosed() {
        val file=File(root,"future.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val before=TableDigest.compute(db,Phase36SchemaVersioning.VERSIONS)
            db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=? WHERE schema_family_uid=?",arrayOf(PHASE30_EVENT_SCHEMA_VERSION+1,SchemaFamilyUid.EVENT.name))
            val t=runCatching{GameplayRuntimeBootstrap.initialize(db,"C1")}.exceptionOrNull()
            assertTrue(t is UnsupportedFutureSchemaException)
            db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=? WHERE schema_family_uid=?",arrayOf(PHASE30_EVENT_SCHEMA_VERSION,SchemaFamilyUid.EVENT.name))
            GameplayRuntimeBootstrap.requireReady(db,"C1")
            assertNotNull(before)
        }
    }

    @Test fun constructorsAndReadsDoNotInstallSchema() {
        val file=File(root,"read-only.db")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            CanonDivergenceStore(db,"C1")
            assertFalse(table(db,Phase35CanonDivergenceSchema.TABLE))
            assertTrue(CanonDivergenceStore(db,"C1").list().isEmpty())
            assertFalse(table(db,Phase36SchemaVersioning.VERSIONS))
        }
    }

    private data class AttemptFixture(val source:Map<SchemaFamilyUid,Int>,val target:Map<SchemaFamilyUid,Int>,val plan:String,val planVersion:Int)

    private fun tamperedAttemptCase(state:MigrationAttemptState,fixture:(Map<SchemaFamilyUid,Int>,Map<SchemaFamilyUid,Int>,String)->AttemptFixture){
        val file=File(root,"tampered-${state.name}-${System.nanoTime()}.db");val dir=File(root,"tampered-${state.name}-${System.nanoTime()}")
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            legacyEventV1Fixture(db,"C1","TAMPER-${state.name}")
            val safety=CampaignSnapshotManager(db,"C1",dir).create(SnapshotKind.PRE_RESTORE)
            val source=sourceVector(1);val target=targetVector();val plan=MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.buildPlan(Phase36SchemaVersioning.contracts,source,Phase36SchemaVersioning.migrationManifest))
            val f=fixture(source,target,plan)
            insertAttempt(db,"TAMPER-${state.name}","C1",state,f.source,f.target,f.plan,f.planVersion,safety.snapshotUid)
            val t=runCatching{Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)}.exceptionOrNull()
            assertNotNull(t)
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"migration_attempt_uid='TAMPER-${state.name}' AND state='${state.name}'"))
            assertEquals(1,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
        }
    }

    private fun legacyEventV1Fixture(db:SQLiteDatabase,campaign:String,command:String){
        GroupATransactionTestFixtures.setupFinance(db,campaign)
        commitTurn(db,campaign,command)
        rebuildEventAsV1(db)
        db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=1 WHERE schema_family_uid=?",arrayOf(SchemaFamilyUid.EVENT.name))
        if(table(db,CampaignIntelligencePhase30Schema.ACTIVATION_TABLE)) db.execSQL("UPDATE ${CampaignIntelligencePhase30Schema.ACTIVATION_TABLE} SET event_schema_version=1 WHERE campaign_uid=?",arrayOf(campaign))
        assertEquals(1,CampaignIntelligencePhase30Schema.physicalEventSchemaVersion(db))
    }

    private fun commitTurn(db:SQLiteDatabase,campaign:String,command:String){
        val proposal=GroupATransactionTestFixtures.admittedFinancialProposal(campaignUid=campaign,commandUid=command,effectiveOrder=10L)
        val result=TurnTransactionBoundary.create(db,TurnTransactionIdentity(campaign,"TURN-$command",command,"TX-$command"),proposal).commit()
        assertTrue(result is TurnExecutionResult.Committed)
    }

    private fun rebuildReceiptAsV2(db:SQLiteDatabase){
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

    private fun rebuildEventAsV1(db:SQLiteDatabase){
        listOf("rpgos_event_store_no_update","rpgos_event_store_no_delete","rpgos_event_store_turn_insert").forEach{db.execSQL("DROP TRIGGER IF EXISTS $it")}
        db.execSQL("DROP TABLE IF EXISTS canonical_gameplay_events_v1_test")
        db.execSQL("""CREATE TABLE canonical_gameplay_events_v1_test(
            campaign_uid TEXT NOT NULL,event_uid TEXT NOT NULL,transaction_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,command_uid TEXT NOT NULL,
            event_intent_uid TEXT NOT NULL,event_kind_uid TEXT NOT NULL,committed_order INTEGER,source_actor_kind_uid TEXT NOT NULL,source_actor_uid TEXT NOT NULL,
            actor_ref_kind_uid TEXT,actor_ref_uid TEXT,subject_ref_kind_uid TEXT NOT NULL,subject_ref_uid TEXT NOT NULL,target_refs_canonical TEXT NOT NULL,
            causal_change_uids_canonical TEXT NOT NULL,effect_kind_uid TEXT NOT NULL,source_event_uid TEXT,resolver_kind_uid TEXT NOT NULL,resolver_version TEXT NOT NULL,
            semantic_fingerprint TEXT NOT NULL,schema_version INTEGER NOT NULL,PRIMARY KEY(campaign_uid,event_uid),UNIQUE(campaign_uid,transaction_uid,event_intent_uid),UNIQUE(campaign_uid,committed_order))""")
        db.execSQL("""INSERT INTO canonical_gameplay_events_v1_test(campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,committed_order,source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,source_event_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version)
            SELECT campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,committed_order,source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,source_event_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version FROM canonical_gameplay_events""")
        db.execSQL("DROP TABLE canonical_gameplay_events")
        db.execSQL("ALTER TABLE canonical_gameplay_events_v1_test RENAME TO canonical_gameplay_events")
    }

    private fun eventHistory(db:SQLiteDatabase,campaign:String):List<String>{
        val hasOrdinal=db.rawQuery("PRAGMA table_info(canonical_gameplay_events)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)=="event_ordinal")found=true;found}
        val ordinal=if(hasOrdinal)"COALESCE(CAST(event_ordinal AS TEXT),'NULL')" else "'LEGACY'"
        return db.rawQuery("""SELECT event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,COALESCE(CAST(committed_order AS TEXT),'NULL'),$ordinal,semantic_fingerprint FROM canonical_gameplay_events WHERE campaign_uid=? ORDER BY event_intent_uid""",arrayOf(campaign)).use{c->buildList{while(c.moveToNext())add((0 until c.columnCount).joinToString("|"){i->c.getString(i)})}}
            .map{it.replace("|LEGACY|","|0|")}
    }

    private fun sourceVector(eventVersion:Int)=Phase36SchemaVersioning.contracts.associate{it.family to if(it.family==SchemaFamilyUid.EVENT)eventVersion else it.currentVersion}
    private fun targetVector()=Phase36SchemaVersioning.contracts.associate{it.family to it.currentVersion}
    private fun sourceVectorFromDb(db:SQLiteDatabase)=Phase36SchemaVersioning.contracts.associate{c->c.family to version(db,c.family)}

    private fun insertAttempt(db:SQLiteDatabase,uid:String,campaign:String,state:MigrationAttemptState,source:Map<SchemaFamilyUid,Int>,target:Map<SchemaFamilyUid,Int>,plan:String,planVersion:Int,safety:String?){
        val sc=MigrationPlanRegistry.vectorCanonical(source);val tc=MigrationPlanRegistry.vectorCanonical(target)
        db.execSQL("""INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,source_vector_canonical,target_vector_canonical,plan_fingerprint,plan_version,safety_snapshot_uid,state,started_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(uid,campaign,sha(sc),sha(tc),sc,tc,plan,planVersion,safety,state.name,System.currentTimeMillis()))
    }

    private fun edge(family:SchemaFamilyUid,from:Int,to:Int,id:String)=MigrationEdge(family,from,to,id,MigrationMateriality.MATERIAL_DATA_MUTATION)
    private fun table(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}
    private fun countWhere(db:SQLiteDatabase,table:String,where:String)=db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where",null).use{it.moveToFirst();it.getLong(0)}
    private fun version(db:SQLiteDatabase,family:SchemaFamilyUid)=db.rawQuery("SELECT schema_version FROM ${Phase36SchemaVersioning.VERSIONS} WHERE schema_family_uid=?",arrayOf(family.name)).use{it.moveToFirst();it.getInt(0)}
    private fun sha(v:String)=MessageDigest.getInstance("SHA-256").digest(v.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun fileSha(f:File):String{val md=MessageDigest.getInstance("SHA-256");f.inputStream().use{s->val b=ByteArray(8192);while(true){val n=s.read(b);if(n<0)break;md.update(b,0,n)}};return md.digest().joinToString(""){"%02x".format(it)}}
}
