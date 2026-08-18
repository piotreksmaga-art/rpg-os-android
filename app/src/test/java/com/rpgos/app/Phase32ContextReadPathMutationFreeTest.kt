package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32ContextReadPathMutationFreeTest {
    @Test
    fun contextReaderSourcesCannotHideMigrationOrRepairBehindGameplayReads() {
        val sourceDir = productionSourceDir()
        val localStore = File(sourceDir, "LocalGameStore.kt").readText()
        val buildContext = functionSource(localStore, "buildContext")
        assertTrue(buildContext.contains("openGameplaySaveDb()"))
        assertFalse(buildContext.contains("ensureCurrentSchema"))
        assertFalse(buildContext.contains("AutoRepairEngine"))

        val financeReader = File(sourceDir, "FinancialContextReader.kt").readText()
        assertFalse("financial context reader must not migrate", financeReader.contains("MigrationManager"))
        assertFalse("financial context reader must not write SQL", financeReader.contains("execSQL("))

        val playerState = File(sourceDir, "PlayerStateStore.kt").readText()
        assertFalse("player state read must not invoke migration", playerState.contains("MigrationManager"))
        assertFalse("player state read must not construct mutating Phase9Store", playerState.contains("Phase9Store("))
        assertFalse("player state read must not write SQL", playerState.contains("execSQL("))

        val inventory = File(sourceDir, "InventoryStore.kt").readText()
        assertFalse("inventory construction and reads must not invoke compatibility migration", inventory.contains("MigrationManager"))
    }

    @Test
    fun readyDatabaseContextReadersDoNotChangeMigrationInventory() {
        SQLiteDatabase.create(null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, "C1")
            withAdministrativeMutationAuthority(db, "C1") {
                db.execSQL(
                    "INSERT OR REPLACE INTO active_player_ref(campaign_id,player_uid,updated_at) VALUES('C1','P1',1)"
                )
            }
            val beforeMigrations = migrationInventory(db)
            val beforeTables = sqliteObjects(db)

            FinancialContextReader(db, "C1").forPlayerUid("P1")
            InventoryStore(db, "C1").reconciled("P1")
            PlayerStateStore(db, "C1").load()

            assertEquals(beforeMigrations, migrationInventory(db))
            assertEquals(beforeTables, sqliteObjects(db))
        }
    }

    private fun migrationInventory(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT migration_id || ':' || COALESCE(notes,'') FROM rpgos_schema_migrations ORDER BY migration_id",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun sqliteObjects(db: SQLiteDatabase): List<String> =
        db.rawQuery(
            "SELECT type || ':' || name || ':' || COALESCE(sql,'') FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    private fun functionSource(source: String, method: String): String {
        val start = source.indexOf("fun $method")
        require(start >= 0) { "RPGOS-G32:CONTEXT_READ_METHOD_NOT_FOUND:$method" }
        val nextPublic = source.indexOf("\n    fun ", start + 1).takeIf { it >= 0 } ?: source.length
        val nextPrivate = source.indexOf("\n    private fun ", start + 1).takeIf { it >= 0 } ?: source.length
        return source.substring(start, minOf(nextPublic, nextPrivate))
    }

    private fun productionSourceDir(): File {
        val start = File(System.getProperty("user.dir")).absoluteFile
        return generateSequence(start) { it.parentFile }
            .flatMap { base ->
                sequenceOf(
                    File(base, "app/src/main/java/com/rpgos/app"),
                    File(base, "src/main/java/com/rpgos/app")
                )
            }
            .firstOrNull { it.isDirectory }
            ?: error("RPGOS-G32:PRODUCTION_SOURCE_DIRECTORY_NOT_FOUND from ${start.absolutePath}")
    }
}
