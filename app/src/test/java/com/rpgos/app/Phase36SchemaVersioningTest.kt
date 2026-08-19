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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase36SchemaVersioningTest {
    private lateinit var root: File
    private lateinit var file: File
    private lateinit var snapshots: File

    @Before fun setUp() {
        root = kotlin.io.path.createTempDirectory("p36-").toFile()
        file = File(root, "campaign.db")
        snapshots = File(root, "snapshots")
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun oldCampaignMigratesAdditivelyWithoutInventingDivergenceAndRerunIsIdempotent() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            MigrationManager().ensureV1(db)
            assertFalse(table(db, Phase35CanonDivergenceSchema.TABLE))
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertTrue(table(db, Phase35CanonDivergenceSchema.TABLE))
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
            val versions = count(db, Phase36SchemaVersioning.VERSIONS)
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertEquals(versions, count(db, Phase36SchemaVersioning.VERSIONS))
            assertEquals(1L, countWhere(db, Phase36SchemaVersioning.ATTEMPTS, "state='APPLIED'"))
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
                assertEquals(contract.currentVersion + 1, failure.found)
                assertEquals(contract.currentVersion, failure.maximum)
                assertEquals(attempts, count(db, Phase36SchemaVersioning.ATTEMPTS))
                db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=? WHERE schema_family_uid=?",
                    arrayOf(contract.currentVersion, contract.family.name))
            }
        }
    }

    @Test fun interruptedAttemptIsMarkedFailedBeforeReadyStateIsRestored() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            db.execSQL("INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,plan_fingerprint,plan_version,state,started_at_epoch_ms) VALUES('INTERRUPTED','C1','a','b','c',1,'RUNNING',1)")
            GameplayRuntimeBootstrap.initialize(db, "C1")
            assertEquals(1L, countWhere(db, Phase36SchemaVersioning.ATTEMPTS, "migration_attempt_uid='INTERRUPTED' AND state='FAILED' AND failure_code='INTERRUPTED_RESTART_SAFE'"))
            GameplayRuntimeBootstrap.requireReady(db, "C1")
        }
    }

    @Test fun interruptedAttemptWithDifferentCurrentPlanFailsClosedWithoutMutation() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            db.execSQL("DELETE FROM ${Phase36SchemaVersioning.VERSIONS} WHERE schema_family_uid=?", arrayOf(SchemaFamilyUid.CANON_DIVERGENCE.name))
            db.execSQL("INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,plan_fingerprint,plan_version,state,started_at_epoch_ms) VALUES('PLAN-MISMATCH','C1','a','b','OLD-PLAN',1,'PREPARED',1)")
            val failure = runCatching { Phase36SchemaVersioning.ensureReady(db, "C1") }.exceptionOrNull()
            assertTrue(failure is MigrationPlanMismatchException)
            assertEquals(1L, countWhere(db, Phase36SchemaVersioning.ATTEMPTS, "migration_attempt_uid='PLAN-MISMATCH' AND state='PREPARED'"))
            assertEquals(0L, countWhere(db, Phase36SchemaVersioning.VERSIONS, "schema_family_uid='CANON_DIVERGENCE'"))
            assertTrue(runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.isFailure)
        }
    }

    @Test fun migrationPlanIsDeterministicAmbiguityAndCyclesFailClosed() {
        val input = listOf(
            SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN,1,1,setOf(SchemaFamilyUid.ENGINE)),
            SchemaFamilyContract(SchemaFamilyUid.ENGINE,1,1)
        )
        assertEquals(listOf(SchemaFamilyUid.ENGINE,SchemaFamilyUid.CAMPAIGN), MigrationPlanRegistry.order(input).map { it.family })
        assertEquals(MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.order(input)), MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.order(input.reversed())))

        val ambiguous = listOf(
            SchemaFamilyContract(SchemaFamilyUid.ENGINE,1,1),
            SchemaFamilyContract(SchemaFamilyUid.ENGINE,2,1)
        )
        assertTrue(runCatching { MigrationPlanRegistry.order(ambiguous) }.exceptionOrNull()!!.message!!.contains("AMBIGUOUS_MIGRATION_PATH"))

        val cycle = listOf(
            SchemaFamilyContract(SchemaFamilyUid.ENGINE,1,1,setOf(SchemaFamilyUid.CAMPAIGN)),
            SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN,1,1,setOf(SchemaFamilyUid.ENGINE))
        )
        assertTrue(runCatching { MigrationPlanRegistry.order(cycle) }.exceptionOrNull()!!.message!!.contains("DEPENDENCY_CYCLE"))
    }

    @Test fun materialMigrationRequiresVerifiedProtectedPhase33Snapshot() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val material = listOf(SchemaFamilyContract(
                SchemaFamilyUid.CAMPAIGN, 2, 1, emptySet(), MigrationMateriality.MATERIAL_DATA_MUTATION
            ))
            assertTrue(runCatching { MigrationSafetyPolicy.requireProtectedSnapshot(db, "C1", material, null) }.isFailure)

            val automatic = CampaignSnapshotManager(db, "C1", snapshots).create(SnapshotKind.AUTOMATIC, pinned = false)
            assertTrue(runCatching { MigrationSafetyPolicy.requireProtectedSnapshot(db, "C1", material, automatic.snapshotUid) }.isFailure)

            val safety = CampaignSnapshotManager(db, "C1", snapshots).create(SnapshotKind.PRE_RESTORE, pinned = true)
            MigrationSafetyPolicy.requireProtectedSnapshot(db, "C1", material, safety.snapshotUid)
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

    private fun table(db:SQLiteDatabase,name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}
    private fun countWhere(db:SQLiteDatabase,table:String,where:String)=db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where",null).use{it.moveToFirst();it.getLong(0)}
}
