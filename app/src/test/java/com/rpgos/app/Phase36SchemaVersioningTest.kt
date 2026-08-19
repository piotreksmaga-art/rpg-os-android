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
    private lateinit var file: File
    @Before fun setUp() { file = File.createTempFile("p36-", ".db").also { it.delete() } }
    @After fun tearDown() { file.delete() }

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

    @Test fun unsupportedFutureFamilyFailsBeforeMigrationMutation() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            db.execSQL("UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=99 WHERE schema_family_uid='EVENT'")
            val attempts = count(db, Phase36SchemaVersioning.ATTEMPTS)
            val failure = runCatching { GameplayRuntimeBootstrap.initialize(db, "C1") }.exceptionOrNull()
            assertTrue(failure is UnsupportedFutureSchemaException)
            assertEquals(SchemaFamilyUid.EVENT, (failure as UnsupportedFutureSchemaException).family)
            assertEquals(attempts, count(db, Phase36SchemaVersioning.ATTEMPTS))
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

    @Test fun migrationPlanIsDeterministicAndCyclesFailClosed() {
        val input = listOf(
            SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN,1,1,setOf(SchemaFamilyUid.ENGINE)),
            SchemaFamilyContract(SchemaFamilyUid.ENGINE,1,1)
        )
        assertEquals(listOf(SchemaFamilyUid.ENGINE,SchemaFamilyUid.CAMPAIGN), MigrationPlanRegistry.order(input).map { it.family })
        assertEquals(MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.order(input)), MigrationPlanRegistry.fingerprint(MigrationPlanRegistry.order(input.reversed())))
        val cycle = listOf(
            SchemaFamilyContract(SchemaFamilyUid.ENGINE,1,1,setOf(SchemaFamilyUid.CAMPAIGN)),
            SchemaFamilyContract(SchemaFamilyUid.CAMPAIGN,1,1,setOf(SchemaFamilyUid.ENGINE))
        )
        assertTrue(runCatching { MigrationPlanRegistry.order(cycle) }.exceptionOrNull()!!.message!!.contains("DEPENDENCY_CYCLE"))
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
