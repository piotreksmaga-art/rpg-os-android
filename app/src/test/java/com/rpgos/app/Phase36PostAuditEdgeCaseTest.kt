package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase36PostAuditEdgeCaseTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = kotlin.io.path.createTempDirectory("p36-edge-").toFile()
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun currentSchemaWithForeignPreparedOrRunningAttemptFailsClosed() {
        listOf(MigrationAttemptState.PREPARED, MigrationAttemptState.RUNNING).forEachIndexed { index, state ->
            val dbFile = File(root, "foreign-$index.db")
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
                GameplayRuntimeBootstrap.initialize(db, "C1")
                db.execSQL(
                    """INSERT INTO ${Phase36SchemaVersioning.ATTEMPTS}(
                        migration_attempt_uid,campaign_uid,source_vector_fingerprint,target_vector_fingerprint,
                        plan_fingerprint,plan_version,state,started_at_epoch_ms)
                        VALUES(?,?,?,?,?,?,?,?)""".trimIndent(),
                    arrayOf("FOREIGN-${state.name}", "C1", "foreign-source", "foreign-target", "foreign-plan",
                        Phase36SchemaVersioning.PLAN_VERSION, state.name, 1L)
                )
                val failure = runCatching { Phase36SchemaVersioning.ensureReady(db, "C1") }.exceptionOrNull()
                assertTrue("current schema + foreign ${state.name} must fail closed", failure is CorruptMigrationAttemptException)
                assertTrue(failure!!.message.orEmpty().contains("ACTIVE_ATTEMPT_WITH_CURRENT_SCHEMA"))
            }
        }
    }

    @Test fun unsupportedOldVersionFailsBeforeMigrationAttemptMutation() {
        val dbFile = File(root, "unsupported-old.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val attemptsBefore = count(db, Phase36SchemaVersioning.ATTEMPTS)
            db.execSQL(
                "UPDATE ${Phase36SchemaVersioning.VERSIONS} SET schema_version=0 WHERE schema_family_uid=?",
                arrayOf(SchemaFamilyUid.EVENT.name)
            )
            val failure = runCatching { Phase36SchemaVersioning.ensureReady(db, "C1") }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure!!.message.orEmpty().contains("UNSUPPORTED_OLD"))
            assertTrue(count(db, Phase36SchemaVersioning.ATTEMPTS) == attemptsBefore)
        }
    }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
