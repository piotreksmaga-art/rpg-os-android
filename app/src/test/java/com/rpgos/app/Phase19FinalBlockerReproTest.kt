package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19FinalBlockerReproTest {

    @Test fun REPRO_P19_RECOVERY_CLEANUP_VALID_LIVE_01_validAuthorityCanBeBlockedByStaleCleanupFailure() {
        val root = tempDir("cleanup-repro")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val failed = File(root, ".A.worldpack.failed-stale").apply { mkdirs(); File(this, "junk").writeText("x") }
        val ops = object : CanonicalPackageFileOps {
            override fun rename(source: File, target: File): Boolean = source.renameTo(target)
            override fun deleteRecursively(target: File): Boolean = if (target == failed) false else target.deleteRecursively()
        }
        val error = runCatching {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.reconcileUnderGate(target, ::validWorldPack, ops)
            }
        }.exceptionOrNull()
        assertNotNull("old behavior must surface cleanup failure even though live is valid", error)
        assertEquals("PACKAGE_REPLACEMENT_RECOVERY_CLEANUP_FAILED", error!!.message)
        assertTrue("canonical live package itself remains valid", validWorldPack(target))
    }

    @Test fun REPRO_P19_CREATE_CAMPAIGN_LIVE_DB_01_packageGateDoesNotFreezeOrdinarySQLiteWriter() {
        val app = cleanApp()
        val source = campaign(File(saves(app), ActiveCampaignRef.DEFAULT_DIRECTORY))
        CampaignSelectionManager(app).setActiveCampaign(source.name)

        val writerEntered = CountDownLatch(1)
        val writerDone = CountDownLatch(1)
        val observedUnblockedWriter = booleanArrayOf(false)

        val manager = CampaignSelectionManager(app) { src, staging ->
            staging.mkdirs()
            val dbFile = File(src, "campaign.db")
            val initial = dbFile.readBytes()
            val firstOffset = initial.indexOfAscii(FIRST_A)
            val secondOffset = initial.indexOfAscii(SECOND_A)
            assertTrue("first marker must exist in production-format SQLite file", firstOffset >= 0)
            assertTrue("second marker must exist in production-format SQLite file", secondOffset >= 0)
            val low = minOf(firstOffset, secondOffset)
            val high = maxOf(firstOffset, secondOffset)
            assertTrue("markers must be separated so raw file copy straddles one commit", high - low > 4096)
            val split = (low + high) / 2

            RandomAccessFile(File(staging, "campaign.db"), "rw").use { out ->
                out.write(initial, 0, split)
                Thread {
                    LocalGameStore(app).openSaveDb().use { db ->
                        writerEntered.countDown()
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
                assertTrue(writerEntered.await(2, TimeUnit.SECONDS))
                observedUnblockedWriter[0] = writerDone.await(2, TimeUnit.SECONDS)
                val after = dbFile.readBytes()
                out.write(after, split, after.size - split)
            }
            File(src, "campaign.json").copyTo(File(staging, "campaign.json"))
            true
        }

        val clone = manager.createCampaign("LiveDbRepro", source.name)
        assertTrue("ordinary SQLite writer is not covered by CanonicalPackageAuthorityGate", observedUnblockedWriter[0])
        val values = readMarkers(File(clone, "campaign.db"))
        assertTrue(
            "pre-fix file snapshot must demonstrate a mixed committed SQLite generation: $values",
            values.contains(FIRST_A) && values.contains(SECOND_B) || values.contains(FIRST_B) && values.contains(SECOND_A)
        )
        assertTrue("generic package validation does not prove logical generation coherence", PackageValidator().validateCampaign(clone).ok)
    }

    private fun campaign(dir: File): File {
        dir.mkdirs()
        val dbFile = File(dir, "campaign.db")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val mode = db.rawQuery("PRAGMA journal_mode", null).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
            assertTrue("production-compatible default journal must be a concrete SQLite mode", mode.isNotBlank())
            db.execSQL("CREATE TABLE marker_first(value TEXT NOT NULL)")
            db.execSQL("INSERT INTO marker_first(value) VALUES(?)", arrayOf(FIRST_A))
            db.execSQL("CREATE TABLE filler(payload BLOB NOT NULL)")
            repeat(96) { db.execSQL("INSERT INTO filler(payload) VALUES(zeroblob(4096))") }
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

    private fun ByteArray.indexOfAscii(value: String): Int {
        val needle = value.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun validWorldPack(file: File): Boolean = runCatching { PackageValidator().validateWorldPack(file).ok }.getOrDefault(false)
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
    private fun tempDir(name: String): File = File(System.getProperty("java.io.tmpdir"), "rpgos-$name-${System.nanoTime()}").apply { deleteRecursively(); mkdirs() }

    companion object {
        private const val FIRST_A = "AAAAAAAA_FIRST_GENERATION_0000000000000000"
        private const val SECOND_A = "AAAAAAAA_SECOND_GENERATION_000000000000000"
        private const val FIRST_B = "BBBBBBBB_FIRST_GENERATION_0000000000000000"
        private const val SECOND_B = "BBBBBBBB_SECOND_GENERATION_000000000000000"
    }
}
