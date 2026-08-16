package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19FinalBlockerReproTest {

    @Test fun P19_RECOVERY_CLEANUP_VALID_LIVE_01_validLiveSurvivesUndeletableFailedSibling() =
        assertValidLiveSurvivesCleanupFailure("failed")

    @Test fun P19_RECOVERY_CLEANUP_VALID_LIVE_02_validLiveSurvivesUndeletablePreparedSibling() =
        assertValidLiveSurvivesCleanupFailure("prepared")

    @Test fun P19_RECOVERY_CLEANUP_VALID_LIVE_03_validLiveSurvivesUndeletableRollbackSibling() =
        assertValidLiveSurvivesCleanupFailure("rollback")

    @Test fun P19_RECOVERY_CLEANUP_INVALID_LIVE_01_unresolvedAuthorityStillFailsClosedOnCleanupFailure() {
        val root = tempDir("cleanup-invalid")
        val target = File(root, "A.worldpack")
        val failed = File(root, ".A.worldpack.failed-stale").apply {
            mkdirs()
            File(this, "junk").writeText("x")
        }
        val ops = failingDeleteOps(failed)
        try {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.reconcileUnderGate(target, ::validWorldPack, ops)
            }
            fail("unresolved authority must remain fail-closed")
        } catch (error: IllegalStateException) {
            assertEquals("PACKAGE_REPLACEMENT_RECOVERY_CLEANUP_FAILED", error.message)
        }
        assertFalse(target.exists())
        assertTrue(failed.exists())
    }

    @Test fun P19_CREATE_CAMPAIGN_LIVE_DB_01_liveProductionWriterCannotTearCloneSnapshot() {
        val app = cleanApp()
        val source = campaign(File(saves(app), ActiveCampaignRef.DEFAULT_DIRECTORY))
        CampaignSelectionManager(app).setActiveCampaign(source.name)

        val copierEntered = CountDownLatch(1)
        val writerStarted = CountDownLatch(1)
        val allowCopierToFinish = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val manager = CampaignSelectionManager(app) { src, staging ->
                copierEntered.countDown()
                Thread {
                    writerStarted.countDown()
                    LocalGameStore(app).openSaveDb().use { db ->
                        db.beginTransaction()
                        try {
                            db.execSQL("UPDATE marker_first SET value=?", arrayOf(FIRST_B))
                            db.execSQL("UPDATE marker_second SET value=?", arrayOf(SECOND_B))
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                    writerDone.countDown()
                }.start()
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
                assertFalse("writer must not commit while createCampaign holds source SQLite snapshot", writerDone.await(250, TimeUnit.MILLISECONDS))
                assertTrue(src.copyRecursively(staging, overwrite = false))
                allowCopierToFinish.await(5, TimeUnit.SECONDS)
                true
            }

            val cloneFuture = pool.submit<File> { manager.createCampaign("LiveDbClone", source.name) }
            assertTrue(copierEntered.await(5, TimeUnit.SECONDS))
            allowCopierToFinish.countDown()
            val clone = cloneFuture.get(10, TimeUnit.SECONDS)
            assertTrue(writerDone.await(5, TimeUnit.SECONDS))

            assertEquals(listOf(FIRST_A, SECOND_A), readMarkers(File(clone, "campaign.db")))
            assertEquals(listOf(FIRST_B, SECOND_B), readMarkers(File(source, "campaign.db")))
            assertTrue(PackageValidator().validateCampaign(clone).ok)
        } finally {
            allowCopierToFinish.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun P19_CREATE_CAMPAIGN_LIVE_DB_02_productionOpenPathUsesNonWalJournalAndCloneIsSelfContained() {
        val app = cleanApp()
        val source = campaign(File(saves(app), ActiveCampaignRef.DEFAULT_DIRECTORY))
        CampaignSelectionManager(app).setActiveCampaign(source.name)

        val journalMode = LocalGameStore(app).openSaveDb().use { db ->
            db.rawQuery("PRAGMA journal_mode", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(0).lowercase()
            }
        }
        assertFalse("production open path does not opt into WAL", journalMode == "wal")

        val clone = CampaignSelectionManager(app).createCampaign("JournalClone", source.name)
        assertTrue(PackageValidator().validateCampaign(clone).ok)
        assertEquals(listOf(FIRST_A, SECOND_A), readMarkers(File(clone, "campaign.db")))
        assertFalse(File(clone, "campaign.db-wal").exists())
        assertFalse(File(clone, "campaign.db-shm").exists())
    }

    private fun assertValidLiveSurvivesCleanupFailure(kind: String) {
        val root = tempDir("cleanup-$kind")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        File(target, "live-marker").writeText("LIVE")
        val stale = File(root, ".A.worldpack.$kind-stale").apply {
            mkdirs()
            File(this, "junk").writeText("x")
        }
        CanonicalPackageAuthorityGate.mutate {
            CanonicalPackageReplacement.reconcileUnderGate(target, ::validWorldPack, failingDeleteOps(stale))
        }
        assertTrue(validWorldPack(target))
        assertTrue(File(target, "live-marker").isFile)
        assertTrue("failed cleanup stays non-authoritative and may be retried later", stale.exists())
    }

    private fun failingDeleteOps(undeletable: File) = object : CanonicalPackageFileOps {
        override fun rename(source: File, target: File): Boolean = source.renameTo(target)
        override fun deleteRecursively(target: File): Boolean =
            if (target == undeletable) false else target.deleteRecursively()
    }

    private fun campaign(dir: File): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).use { db ->
            db.execSQL("CREATE TABLE marker_first(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO marker_first(value) VALUES(?)", arrayOf(FIRST_A))
            db.execSQL("CREATE TABLE filler(payload BLOB NOT NULL)")
            repeat(32) { db.execSQL("INSERT INTO filler(payload) VALUES(zeroblob(4096))") }
            db.execSQL("CREATE TABLE marker_second(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO marker_second(value) VALUES(?)", arrayOf(SECOND_A))
        }
        File(dir, "campaign.json").writeText("""{"id":"template","version":"1","core_api":"1"}""")
        return dir
    }

    private fun readMarkers(file: File): List<String> =
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            listOf(
                db.rawQuery("SELECT value FROM marker_first", null).use { c -> assertTrue(c.moveToFirst()); c.getString(0) },
                db.rawQuery("SELECT value FROM marker_second", null).use { c -> assertTrue(c.moveToFirst()); c.getString(0) }
            )
        }

    private fun validWorldPack(file: File): Boolean =
        runCatching { PackageValidator().validateWorldPack(file).ok }.getOrDefault(false)

    private fun worldPack(dir: File, id: String, version: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""")
        return dir
    }

    private fun saves(app: Context): File = File(app.filesDir, "rpgos/saves").apply { mkdirs() }

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }

    private fun tempDir(name: String): File = File(
        System.getProperty("java.io.tmpdir"),
        "rpgos-p19-final-$name-${System.nanoTime()}"
    ).apply { deleteRecursively(); mkdirs() }

    companion object {
        private const val FIRST_A = "AAAAAAAA_FIRST_GENERATION_0000000000000000"
        private const val SECOND_A = "AAAAAAAA_SECOND_GENERATION_000000000000000"
        private const val FIRST_B = "BBBBBBBB_FIRST_GENERATION_0000000000000000"
        private const val SECOND_B = "BBBBBBBB_SECOND_GENERATION_000000000000000"
    }
}
