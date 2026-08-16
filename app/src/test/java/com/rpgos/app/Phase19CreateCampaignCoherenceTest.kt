package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19CreateCampaignCoherenceTest {

    @Test fun P19_CREATE_CAMPAIGN_TORN_CLONE_01_legacyUngatedCopyCanTear() {
        val app = cleanApp()
        val saves = saves(app)
        val source = campaign(File(saves, "Template.campaign"), "template", "A1")
        val target = File(saves, "Legacy.campaign")

        target.mkdirs()
        File(source, "campaign.json").copyTo(File(target, "campaign.json"))

        // Deterministic adversarial source replacement between two file copies: this models the
        // old createCampaign source.copyRecursively(target) running without the package gate.
        replaceGeneration(source, "A2")
        File(source, "campaign.db").copyTo(File(target, "campaign.db"))

        assertEquals("A1", manifestGeneration(target))
        assertEquals("A2", dbGeneration(target))
        assertNotEquals(manifestGeneration(target), dbGeneration(target))
    }

    @Test fun P19_CREATE_CAMPAIGN_01_stableSourceCompleteValidClone() {
        val app = cleanApp()
        val source = campaign(File(saves(app), "Template.campaign"), "template", "A1")
        val manager = CampaignSelectionManager(app)

        val clone = manager.createCampaign("Clone", source.name)

        assertTrue(clone.isDirectory)
        assertTrue(PackageValidator().validateCampaign(clone).ok)
        assertEquals("A1", manifestGeneration(clone))
        assertEquals("A1", dbGeneration(clone))
        assertEquals(clone.name, manager.activeCampaignDirName())
    }

    @Test fun P19_CREATE_CAMPAIGN_02_copyRecursivelyFailureNoActivation() {
        val app = cleanApp()
        val saves = saves(app)
        val source = campaign(File(saves, "Template.campaign"), "template", "A1")
        val existing = campaign(File(saves, "Existing.campaign"), "existing", "E1")
        val baseline = CampaignSelectionManager(app)
        baseline.setActiveCampaign(existing.name)
        val manager = CampaignSelectionManager(app) { _, staging ->
            staging.mkdirs()
            File(staging, "campaign.json").writeText("partial")
            false
        }

        val failure = runCatching { manager.createCampaign("Failed", source.name) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(existing.name, baseline.activeCampaignDirName())
        assertFalse(File(saves, "Failed.campaign").exists())
    }

    @Test fun P19_CREATE_CAMPAIGN_03_sourceReplacementDuringCopyNoTornClone() {
        val app = cleanApp()
        val saves = saves(app)
        val source = campaign(File(saves, "Template.campaign"), "template", "A1")
        val writerStarted = CountDownLatch(1)
        val writerCompleted = CountDownLatch(1)
        val writerWasBlockedDuringCopy = AtomicBoolean(false)

        val manager = CampaignSelectionManager(app) { src, staging ->
            staging.mkdirs()
            File(src, "campaign.json").copyTo(File(staging, "campaign.json"))

            val writer = Thread {
                writerStarted.countDown()
                CanonicalPackageAuthorityGate.mutate {
                    replaceGeneration(source, "A2")
                }
                writerCompleted.countDown()
            }
            writer.start()
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS))
            writerWasBlockedDuringCopy.set(!writerCompleted.await(100, TimeUnit.MILLISECONDS))

            File(src, "campaign.db").copyTo(File(staging, "campaign.db"))
            true
        }

        val clone = manager.createCampaign("Coherent", source.name)
        assertTrue(writerCompleted.await(2, TimeUnit.SECONDS))

        assertTrue("writer must be excluded while source snapshot is copied", writerWasBlockedDuringCopy.get())
        assertEquals("A1", manifestGeneration(clone))
        assertEquals("A1", dbGeneration(clone))
        assertEquals("A2", manifestGeneration(source))
        assertEquals("A2", dbGeneration(source))
    }

    @Test fun P19_CREATE_CAMPAIGN_04_targetValidatedBeforeActivation() {
        val app = cleanApp()
        val saves = saves(app)
        val source = campaign(File(saves, "Template.campaign"), "template", "A1")
        val existing = campaign(File(saves, "Existing.campaign"), "existing", "E1")
        val baseline = CampaignSelectionManager(app)
        baseline.setActiveCampaign(existing.name)
        val manager = CampaignSelectionManager(app) { src, staging ->
            src.copyRecursively(staging, overwrite = false).also {
                File(staging, "campaign.json").writeText("{malformed")
            }
        }

        val failure = runCatching { manager.createCampaign("Invalid", source.name) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(existing.name, baseline.activeCampaignDirName())
        assertFalse(File(saves, "Invalid.campaign").exists())
    }

    @Test fun P19_CREATE_CAMPAIGN_05_failedCloneCleanup() {
        val app = cleanApp()
        val saves = saves(app)
        val source = campaign(File(saves, "Template.campaign"), "template", "A1")
        val manager = CampaignSelectionManager(app) { _, staging ->
            staging.mkdirs()
            File(staging, "partial.bin").writeText("partial")
            false
        }

        runCatching { manager.createCampaign("Cleanup", source.name) }

        assertFalse(File(saves, "Cleanup.campaign").exists())
        assertFalse(saves.listFiles().orEmpty().any { it.name.startsWith(".clone-Cleanup-") })
    }

    @Test fun P19_CREATE_CAMPAIGN_06_sourceGenerationCoherencePreserved() {
        val app = cleanApp()
        val saves = saves(app)
        val source = campaign(File(saves, "Template.campaign"), "template", "A1")
        val writerStarted = CountDownLatch(1)
        val writerDone = CountDownLatch(1)

        val manager = CampaignSelectionManager(app) { src, staging ->
            staging.mkdirs()
            File(src, "campaign.db").copyTo(File(staging, "campaign.db"))
            Thread {
                writerStarted.countDown()
                CanonicalPackageAuthorityGate.mutate { replaceGeneration(source, "A2") }
                writerDone.countDown()
            }.start()
            assertTrue(writerStarted.await(2, TimeUnit.SECONDS))
            File(src, "campaign.json").copyTo(File(staging, "campaign.json"))
            true
        }

        val clone = manager.createCampaign("Generation", source.name)
        assertTrue(writerDone.await(2, TimeUnit.SECONDS))

        assertEquals(dbGeneration(clone), manifestGeneration(clone))
        assertEquals("A1", dbGeneration(clone))
        assertTrue(PackageValidator().validateCampaign(clone).ok)
    }

    private fun campaign(dir: File, id: String, generation: String): File {
        dir.mkdirs()
        writeDb(File(dir, "campaign.db"), generation)
        File(dir, "campaign.json").writeText(
            """{\"id\":\"$id\",\"version\":\"1\",\"core_api\":\"1\",\"generation\":\"$generation\"}"""
        )
        return dir
    }

    private fun replaceGeneration(dir: File, generation: String) {
        val db = File(dir, "campaign.db")
        if (db.exists()) db.delete()
        writeDb(db, generation)
        File(dir, "campaign.json").writeText(
            """{\"id\":\"template\",\"version\":\"1\",\"core_api\":\"1\",\"generation\":\"$generation\"}"""
        )
    }

    private fun writeDb(file: File, generation: String) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE marker(generation TEXT NOT NULL)")
            db.execSQL("INSERT INTO marker(generation) VALUES(?)", arrayOf(generation))
        }
    }

    private fun dbGeneration(dir: File): String =
        SQLiteDatabase.openDatabase(
            File(dir, "campaign.db").absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { db ->
            db.rawQuery("SELECT generation FROM marker", null).use { c ->
                assertTrue(c.moveToFirst())
                c.getString(0)
            }
        }

    private fun manifestGeneration(dir: File): String {
        val text = File(dir, "campaign.json").readText()
        return Regex("\\\"generation\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(text)!!.groupValues[1]
    }

    private fun saves(app: Context): File =
        File(app.filesDir, "rpgos/saves").apply { mkdirs() }

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }
}
