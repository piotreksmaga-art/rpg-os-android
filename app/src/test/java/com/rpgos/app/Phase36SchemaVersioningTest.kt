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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase36SchemaVersioningTest {
    private lateinit var root: File
    private lateinit var file: File
    private lateinit var snapshots: File

    @Before fun setUp() {
        root = kotlin.io.path.createTempDirectory("p36-repair-").toFile()
        file = File(root, "campaign.db")
        snapshots = File(root, "snapshots")
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun oldCampaignRegistersCurrentFamiliesWithoutInventingDivergenceAndRerunIsIdempotent() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            MigrationManager().ensureV1(db)
            assertFalse(table(db, Phase35CanonDivergenceSchema.TABLE))
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertTrue(table(db, Phase35CanonDivergenceSchema.TABLE))
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
            assertEquals(Phase36SchemaVersioning.contracts.size.toLong(), count(db, Phase36SchemaVersioning.VERSIONS))
            val attempts = count(db, Phase36SchemaVersioning.ATTEMPTS)
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertEquals(attempts, count(db, Phase36SchemaVersioning.ATTEMPTS))
            GameplayRuntimeBootstrap.requireReady(db, "C1")
        }
    }

    @Test fun everyUnsupportedFutureFamilyFailsBeforeMigrationMutation() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val attempts = count(db, Phase36SchemaVersioning.ATTEMPTS)
            Phase36SchemaVersioning.contracts.forEach { contract ->
                db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=? WHERE schema_family_uid=?",
                    arrayOf(contract.currentVersion + 1, contract.family.name))
                val failure = runCatching { GameplayRuntimeBootstrap.initialize(db, "C1") }.exceptionOrNull()
                assertTrue("future ${contract.family} must fail closed", failure is UnsupportedFutureSchemaException)
                failure as UnsupportedFutureSchemaException
                assertEquals(contract.family, failure.family)
                assertEquals(attempts, count(db, Phase36SchemaVersioning.ATTEMPTS))
                db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=? WHERE schema_family_uid=?",
                    arrayOf(contract.currentVersion, contract.family.name))
            }
        }
    }

    @Test fun versionGraphCoversSingleMultiMissingAmbiguousCycleWrongFamilyAndSourceFingerprint() {
        val noop: (SQLiteDatabase, String) -> Unit = { _, _ -> }
        fun edge(from:Int,to:Int,id:String,family:SchemaFamilyUid=SchemaFamilyUid.EVENT) = VersionMigrationEdge(
            family, from, to, id, MigrationMateriality.STRUCTURAL_ADDITIVE, noop
        )

        val single = VersionMigrationGraph(listOf(edge(1,2,"E12")))
        assertEquals(listOf(1 to 2), single.route(SchemaFamilyUid.EVENT,1,2).map { it.fromVersion to it.toVersion })

        val multi = VersionMigrationGraph(listOf(edge(1,2,"E12"),edge(2,3,"E23")))
        assertEquals(listOf(1 to 2,2 to 3), multi.route(SchemaFamilyUid.EVENT,1,3).map { it.fromVersion to it.toVersion })
        assertTrue(runCatching { single.route(SchemaFamilyUid.EVENT,1,3) }.exceptionOrNull()!!.message!!.contains("MISSING_MIGRATION_EDGE"))

        val ambiguous = VersionMigrationGraph(listOf(edge(1,3,"E13"),edge(1,2,"E12"),edge(2,3,"E23")))
        assertTrue(runCatching { ambiguous.route(SchemaFamilyUid.EVENT,1,3) }.exceptionOrNull()!!.message!!.contains("AMBIGUOUS_MIGRATION_PATH"))

        val cyclic = VersionMigrationGraph(listOf(edge(1,2,"E12"),edge(2,1,"E21")))
        assertTrue(runCatching { cyclic.route(SchemaFamilyUid.EVENT,1,3) }.exceptionOrNull()!!.message!!.contains("MIGRATION_VERSION_CYCLE"))

        val wrongFamily = VersionMigrationGraph(listOf(edge(1,2,"C12",SchemaFamilyUid.CAMPAIGN)))
        assertTrue(runCatching { wrongFamily.route(SchemaFamilyUid.EVENT,1,2) }.exceptionOrNull()!!.message!!.contains("MISSING_MIGRATION_EDGE"))

        val contract = SchemaFamilyContract(SchemaFamilyUid.EVENT,3,1,setOf(SchemaFamilyUid.RECEIPT))
        val from1 = PlannedMigration(contract,1,multi.route(SchemaFamilyUid.EVENT,1,3))
        val from2 = PlannedMigration(contract,2,multi.route(SchemaFamilyUid.EVENT,2,3))
        assertNotEquals(Phase36SchemaVersioning.fingerprint(listOf(from1)), Phase36SchemaVersioning.fingerprint(listOf(from2)))

        val reversed = VersionMigrationGraph(listOf(edge(2,3,"E23"),edge(1,2,"E12")))
        assertEquals(
            multi.manifestFingerprint(listOf(contract), Phase36SchemaVersioning.PLAN_VERSION),
            reversed.manifestFingerprint(listOf(contract), Phase36SchemaVersioning.PLAN_VERSION)
        )
    }

    @Test fun productionMigrationManifestHasStableImplementationIdentity() {
        assertEquals(
            "3bdde55f529cd8cbe4f5d8c7dab5973e625e216d48ad9762f512bd3bbf38f24f",
            Phase36SchemaVersioning.migrationManifestFingerprint()
        )
    }

    @Test fun realPhysicalEventV1ToV2SurvivesEveryFaultBoundaryAndRestartWithoutDataLoss() {
        val boundaries = EventV1ToV2FaultPoint.values().map { it.name } + "BEFORE_APPLIED"
        boundaries.forEachIndexed { index, boundary ->
            val dbFile = File(root, "event-fault-$index.db")
            val snapDir = File(root, "event-fault-$index-snaps")
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                val safety = preparePhysicalEventV1WithSafetySnapshot(db, snapDir)
                val before = legacyEventSemanticRows(db)
                assertEquals(1, Phase36EventSchemaScaffold.detectPhysicalVersion(db))
                val failure = if (boundary == "BEFORE_APPLIED") {
                    runCatching {
                        Phase36SchemaVersioning.ensureReady(
                            db,"C1",safety.snapshotUid,beforeApplied={ throw SimulatedMigrationProcessDeath(boundary) }
                        )
                    }.exceptionOrNull()
                } else {
                    runCatching {
                        Phase36SchemaVersioning.ensureReady(
                            db,"C1",safety.snapshotUid,
                            eventFaultInjector=EventV1ToV2FaultInjector { point ->
                                if (point.name == boundary) throw SimulatedMigrationProcessDeath(boundary)
                            }
                        )
                    }.exceptionOrNull()
                }
                assertTrue("$boundary must simulate process death", failure is SimulatedMigrationProcessDeath)
                assertEquals("rollback at $boundary must preserve physical v1", 1, Phase36EventSchemaScaffold.detectPhysicalVersion(db))
                assertEquals(before, legacyEventSemanticRows(db))
                assertFalse(table(db,"canonical_gameplay_events_v2_new"))
                assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='PREPARED'"))

                Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)
                assertEquals(PHASE30_EVENT_SCHEMA_VERSION, Phase36EventSchemaScaffold.detectPhysicalVersion(db))
                assertEquals(before, legacyEventSemanticRows(db))
                assertFalse(table(db,"canonical_gameplay_events_v2_new"))
                assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='FAILED' AND failure_code='INTERRUPTED_RESTART_SAFE'"))
                assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='APPLIED'"))
            }
        }
    }

    @Test fun materialEventVersionAdvanceRequiresRecoverableProtectedSnapshot() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=1 WHERE schema_family_uid='EVENT'")
            assertTrue(runCatching { Phase36SchemaVersioning.ensureReady(db,"C1") }.isFailure)
            val safety = CampaignSnapshotManager(db,"C1",snapshots).create(SnapshotKind.PRE_RESTORE)
            Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)
            assertEquals(PHASE30_EVENT_SCHEMA_VERSION,version(db,SchemaFamilyUid.EVENT))
        }
    }

    @Test fun snapshotKindMatrixUsesOneRecoverabilityDefinitionAndAcceptedSafetyAlwaysReconstructs() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val manager = CampaignSnapshotManager(db,"C1",snapshots)
            val materialPlan = materialPlan()
            SnapshotKind.values().forEach { kind ->
                val descriptor = manager.create(kind, pinned = kind == SnapshotKind.USER_PINNED)
                val recoverable = runCatching { RecoverableSnapshotPolicy.requireRecoverable(db,"C1",descriptor.snapshotUid) }.isSuccess
                val expectedRecoverable = kind !in setOf(SnapshotKind.MANUAL_EXPORT,SnapshotKind.LEGACY_BACKUP)
                assertEquals("recoverability for $kind",expectedRecoverable,recoverable)
                val safetyAccepted = runCatching {
                    MigrationSafetyPolicy.requireProtectedSnapshot(db,"C1",materialPlan,descriptor.snapshotUid)
                }.isSuccess
                val expectedSafety = kind in setOf(SnapshotKind.MANUAL_BACKUP,SnapshotKind.PRE_RESTORE,SnapshotKind.USER_PINNED)
                assertEquals("safety acceptance for $kind",expectedSafety,safetyAccepted)
                if (safetyAccepted) {
                    val staging = manager.reconstructToVerifiedStaging(descriptor.snapshotUid)
                    assertTrue(staging.isFile)
                    staging.delete()
                }
            }
        }
    }

    @Test fun corruptedCrossCampaignAndMissingReplaySafetySnapshotsFailClosedWhileReplayableStaleSnapshotWorks() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db)
            val manager = CampaignSnapshotManager(db,"C1",snapshots)
            commitFinancial(db,"A")
            val stale = manager.create(SnapshotKind.PRE_RESTORE)
            commitFinancial(db,"B")
            assertNotNull(RecoverableSnapshotPolicy.requireRecoverable(db,"C1",stale.snapshotUid))

            db.execSQL("DROP TRIGGER IF EXISTS rpgos_replay_no_delete")
            db.execSQL("DELETE FROM ${CampaignSnapshotSchema.REPLAY} WHERE campaign_uid='C1' AND commit_order=2")
            assertTrue(runCatching { RecoverableSnapshotPolicy.requireRecoverable(db,"C1",stale.snapshotUid) }.isFailure)

            GameplayRuntimeBootstrap.initialize(db,"C2")
            val c2 = CampaignSnapshotManager(db,"C2",File(root,"c2-snaps")).create(SnapshotKind.PRE_RESTORE)
            assertTrue(runCatching { RecoverableSnapshotPolicy.requireRecoverable(db,"C1",c2.snapshotUid) }.isFailure)

            val corrupt = manager.create(SnapshotKind.PRE_RESTORE)
            File(corrupt.payloadPath).writeText("corrupt")
            assertTrue(runCatching { RecoverableSnapshotPolicy.requireRecoverable(db,"C1",corrupt.snapshotUid) }.isFailure)
        }
    }

    @Test fun safetySnapshotDeleteCannotCrossMigrationLifecycleWriteLock() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val safety = preparePhysicalEventV1WithSafetySnapshot(db,snapshots)
            val manager = CampaignSnapshotManager(db,"C1",snapshots)
            val migrationAtBoundary = CountDownLatch(1)
            val releaseMigration = CountDownLatch(1)
            val deleteDone = CountDownLatch(1)
            val migrationFailure = AtomicReference<Throwable?>()
            val deleteResult = AtomicReference<Boolean?>()

            val migration = thread(start=true) {
                try {
                    Phase36SchemaVersioning.ensureReady(
                        db,"C1",safety.snapshotUid,
                        eventFaultInjector=EventV1ToV2FaultInjector { point ->
                            if (point == EventV1ToV2FaultPoint.BEFORE_STAGING_CREATE) {
                                migrationAtBoundary.countDown()
                                releaseMigration.await(5,TimeUnit.SECONDS)
                            }
                        }
                    )
                } catch (t:Throwable) { migrationFailure.set(t) }
            }
            assertTrue(migrationAtBoundary.await(2,TimeUnit.SECONDS))
            val deletion = thread(start=true) {
                deleteResult.set(manager.delete(safety.snapshotUid))
                deleteDone.countDown()
            }
            assertFalse("delete must block behind migration WRITE lock",deleteDone.await(200,TimeUnit.MILLISECONDS))
            assertTrue(File(safety.payloadPath).isFile)
            releaseMigration.countDown()
            migration.join(5000); deletion.join(5000)
            assertNull(migrationFailure.get())
            assertEquals(PHASE30_EVENT_SCHEMA_VERSION,Phase36EventSchemaScaffold.detectPhysicalVersion(db))
            assertTrue(deleteDone.count==0L)
            assertEquals(true,deleteResult.get())
        }
    }

    @Test fun currentSchemaWithForeignActiveAttemptFailsClosed() {
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            db.execSQL("""INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(
                migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,
                plan_fingerprint,plan_version,state,started_at_epoch_ms)
                VALUES('FOREIGN','C1','x','y','z',${Phase36SchemaVersioning.PLAN_VERSION},'PREPARED',1)""")
            val failure = runCatching { Phase36SchemaVersioning.ensureReady(db,"C1") }.exceptionOrNull()
            assertTrue(failure is CorruptMigrationAttemptException)
            assertTrue(failure!!.message!!.contains("ACTIVE_ATTEMPT_WITH_CURRENT_SCHEMA"))
        }
    }

    @Test fun tamperedInterruptedAttemptFieldsFailClosedAndLegalInterruptedAttemptRecovers() {
        listOf("plan_version","source_vector_fingerprint","target_vector_fingerprint","plan_fingerprint").forEachIndexed { i,column ->
            val dbFile=File(root,"tamper-$i.db");val snapDir=File(root,"tamper-$i-snaps")
            SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
                val safety=preparePhysicalEventV1WithSafetySnapshot(db,snapDir)
                createInterruptedPreparedAttempt(db,safety.snapshotUid)
                when(column){
                    "plan_version"->db.execSQL("UPDATE ${Phase36SchemaVersioning.ATTEMPTS} SET plan_version=999 WHERE state='PREPARED'")
                    "source_vector_fingerprint"->db.execSQL("UPDATE ${Phase36SchemaVersioning.ATTEMPTS} SET source_vector_fingerprint='BAD-SOURCE' WHERE state='PREPARED'")
                    "target_vector_fingerprint"->db.execSQL("UPDATE ${Phase36SchemaVersioning.ATTEMPTS} SET target_vector_fingerprint='BAD-TARGET' WHERE state='PREPARED'")
                    else->db.execSQL("UPDATE ${Phase36SchemaVersioning.ATTEMPTS} SET plan_fingerprint='BAD-PLAN' WHERE state='PREPARED'")
                }
                val failure=runCatching{Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)}.exceptionOrNull()
                assertNotNull("tamper $column must fail",failure)
                if(column=="plan_fingerprint") assertTrue(failure is MigrationPlanMismatchException)
                else assertTrue(failure is CorruptMigrationAttemptException)
                assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='PREPARED'"))
            }
        }

        val legalFile=File(root,"legal-restart.db");val legalSnaps=File(root,"legal-restart-snaps")
        SQLiteDatabase.openOrCreateDatabase(legalFile,null).use { db ->
            val safety=preparePhysicalEventV1WithSafetySnapshot(db,legalSnaps)
            createInterruptedPreparedAttempt(db,safety.snapshotUid)
            Phase36SchemaVersioning.ensureReady(db,"C1",safety.snapshotUid)
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='FAILED' AND failure_code='INTERRUPTED_RESTART_SAFE'"))
            assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='APPLIED'"))
        }
    }

    @Test fun malformedAttemptStateFailsAtDatabaseBoundaryAndPreexistingCorruptionIsDetected() {
        SQLiteDatabase.openOrCreateDatabase(file,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            assertTrue(runCatching {
                db.execSQL("""INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(
                    migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,
                    plan_fingerprint,plan_version,state,started_at_epoch_ms)
                    VALUES('BADSTATE','C1','s','t','p',${Phase36SchemaVersioning.PLAN_VERSION},'BROKEN',1)""")
            }.isFailure)
        }

        val legacy=File(root,"legacy-bad-state.db")
        SQLiteDatabase.openOrCreateDatabase(legacy,null).use { db ->
            db.execSQL("""CREATE TABLE ${Phase36SchemaVersioning.ATTEMPTS}(
                migration_attempt_uid TEXT PRIMARY KEY,campaign_uid TEXT NOT NULL,
                source_vector_fingerprint TEXT NOT NULL,target_vector_fingerprint TEXT NOT NULL,
                plan_fingerprint TEXT NOT NULL,plan_version INTEGER NOT NULL,safety_snapshot_uid TEXT,
                state TEXT NOT NULL,started_at_epoch_ms INTEGER NOT NULL,completed_at_epoch_ms INTEGER,failure_code TEXT)""")
            db.execSQL("INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,plan_fingerprint,plan_version,state,started_at_epoch_ms) VALUES('OLD-BAD','C1','s','t','p',2,'BROKEN',1)")
            val failure=runCatching{Phase36SchemaVersioning.ensureReady(db,"C1")}.exceptionOrNull()
            assertTrue(failure is CorruptMigrationAttemptException)
        }
    }

    @Test fun constructorsAndReadsDoNotInstallSchema() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            CanonDivergenceStore(db, "C1")
            assertFalse(table(db, Phase35CanonDivergenceSchema.TABLE))
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
            assertFalse(table(db, Phase36SchemaVersioning.VERSIONS))
        }
    }

    private fun preparePhysicalEventV1WithSafetySnapshot(db:SQLiteDatabase,snapshotDir:File):CampaignSnapshotDescriptor {
        MigrationManager().ensureV1(db)
        TurnTransactionReceiptSchema.ensureReady(db)
        CampaignCausalGraphSchema.ensureReady(db)
        CampaignSnapshotSchema.ensureReady(db)
        db.execSQL("DROP TABLE IF EXISTS ${CampaignIntelligencePhase30Schema.EVENT_TABLE}")
        createPhysicalEventV1(db)
        insertLegacyEvent(db)
        return CampaignSnapshotManager(db,"C1",snapshotDir).create(SnapshotKind.PRE_RESTORE)
    }

    private fun createPhysicalEventV1(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE ${CampaignIntelligencePhase30Schema.EVENT_TABLE}(
            campaign_uid TEXT NOT NULL,event_uid TEXT NOT NULL,transaction_uid TEXT NOT NULL,turn_uid TEXT NOT NULL,
            command_uid TEXT NOT NULL,event_intent_uid TEXT NOT NULL,event_kind_uid TEXT NOT NULL,committed_order INTEGER,
            source_actor_kind_uid TEXT NOT NULL,source_actor_uid TEXT NOT NULL,actor_ref_kind_uid TEXT,actor_ref_uid TEXT,
            subject_ref_kind_uid TEXT NOT NULL,subject_ref_uid TEXT NOT NULL,target_refs_canonical TEXT NOT NULL,
            causal_change_uids_canonical TEXT NOT NULL,effect_kind_uid TEXT NOT NULL,source_event_uid TEXT,
            resolver_kind_uid TEXT NOT NULL,resolver_version TEXT NOT NULL,semantic_fingerprint TEXT NOT NULL,
            schema_version INTEGER NOT NULL,PRIMARY KEY(campaign_uid,event_uid),
            UNIQUE(campaign_uid,transaction_uid,event_intent_uid),UNIQUE(campaign_uid,committed_order))""")
    }

    private fun insertLegacyEvent(db:SQLiteDatabase) {
        db.execSQL("""INSERT INTO ${CampaignIntelligencePhase30Schema.EVENT_TABLE}(
            campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,committed_order,
            source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid,subject_ref_kind_uid,subject_ref_uid,
            target_refs_canonical,causal_change_uids_canonical,effect_kind_uid,source_event_uid,resolver_kind_uid,resolver_version,
            semantic_fingerprint,schema_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf("C1","EV-LEGACY","TX-LEGACY","TURN-LEGACY","CMD-LEGACY","INTENT-LEGACY","DOMAIN_EFFECT",null,
                "PLAYER","P1",null,null,"PLAYER","P1","[]","[]","LEGACY_EFFECT",null,"LEGACY","1","SEM-LEGACY",1))
    }

    private fun legacyEventSemanticRows(db:SQLiteDatabase):List<List<String?>> = db.rawQuery(
        """SELECT campaign_uid,event_uid,transaction_uid,turn_uid,command_uid,event_intent_uid,event_kind_uid,
            source_actor_kind_uid,source_actor_uid,subject_ref_kind_uid,subject_ref_uid,target_refs_canonical,
            causal_change_uids_canonical,effect_kind_uid,resolver_kind_uid,resolver_version,semantic_fingerprint,schema_version
            FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} ORDER BY event_uid""",null
    ).use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { i -> if(c.isNull(i))null else c.getString(i) }) } }

    private fun createInterruptedPreparedAttempt(db:SQLiteDatabase,safetyUid:String) {
        val failure=runCatching {
            Phase36SchemaVersioning.ensureReady(
                db,"C1",safetyUid,
                eventFaultInjector=EventV1ToV2FaultInjector { point ->
                    if(point==EventV1ToV2FaultPoint.BEFORE_STAGING_CREATE) throw SimulatedMigrationProcessDeath(point.name)
                }
            )
        }.exceptionOrNull()
        assertTrue(failure is SimulatedMigrationProcessDeath)
        assertEquals(1L,countWhere(db,Phase36SchemaVersioning.ATTEMPTS,"state='PREPARED'"))
    }

    private fun materialPlan():List<PlannedMigration> {
        val contract=Phase36SchemaVersioning.contracts.single{it.family==SchemaFamilyUid.EVENT}
        val edge=VersionMigrationEdge(SchemaFamilyUid.EVENT,1,2,"TEST-MATERIAL",MigrationMateriality.MATERIAL_DATA_MUTATION){_,_->}
        return listOf(PlannedMigration(contract,1,listOf(edge)))
    }

    private fun commitFinancial(db:SQLiteDatabase,suffix:String) {
        val command="CMD-$suffix"
        val result=TurnTransactionBoundary.create(
            db,TurnTransactionIdentity("C1","TURN-$suffix",command,"TX-$suffix"),
            GroupATransactionTestFixtures.admittedFinancialProposal(commandUid=command)
        ).commit()
        assertTrue(result is TurnExecutionResult.Committed)
    }

    private fun table(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}
    private fun countWhere(db:SQLiteDatabase,table:String,where:String)=db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where",null).use{it.moveToFirst();it.getLong(0)}
    private fun version(db:SQLiteDatabase,family:SchemaFamilyUid)=db.rawQuery("SELECT schema_version FROM ${Phase36SchemaVersioning.VERSIONS} WHERE schema_family_uid=?",arrayOf(family.name)).use{it.moveToFirst();it.getInt(0)}
}
