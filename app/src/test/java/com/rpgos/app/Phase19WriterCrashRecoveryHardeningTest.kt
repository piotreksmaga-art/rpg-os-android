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
class Phase19WriterCrashRecoveryHardeningTest {
    @Test fun P19_CONTENT_UPDATE_01_normalSharedReplacementSucceeds() {
        val root = tempDir("content-normal")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-current"), "A", "2")
        CanonicalPackageReplacement.activatePrepared(prepared, target, ::validWorldPack)
        assertEquals("2", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_CONTENT_UPDATE_02_activationFailureRestoresOldPackage() {
        val root = tempDir("content-activation-fail")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        File(target, "old-marker").writeText("OLD")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-current"), "A", "2")
        val ops = object : CanonicalPackageFileOps {
            var renames = 0
            override fun rename(source: File, target: File): Boolean {
                renames++
                return when (renames) {
                    1, 3 -> source.renameTo(target)
                    else -> false
                }
            }
            override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
        }
        runCatching {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops)
            }
        }.onSuccess { fail("activation must fail") }
        assertTrue(File(target, "old-marker").isFile)
        assertEquals("1", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_CONTENT_UPDATE_03_rollbackFailureIsExplicitFailClosed() {
        val root = tempDir("content-rollback-fail")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-current"), "A", "2")
        val ops = object : CanonicalPackageFileOps {
            var renames = 0
            override fun rename(source: File, target: File): Boolean {
                renames++
                return if (renames == 1) source.renameTo(target) else false
            }
            override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
        }
        try {
            CanonicalPackageAuthorityGate.mutate {
                CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops)
            }
            fail("rollback failure must surface")
        } catch (error: IllegalStateException) {
            assertEquals("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", error.message)
        }
    }

    @Test fun P19_CONTENT_UPDATE_04_callbackFailureLeavesOldCompletePackage() {
        val root = tempDir("content-callback-fail")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        File(target, "old-marker").writeText("OLD")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-current"), "A", "2")
        runCatching {
            CanonicalPackageReplacement.activatePrepared(prepared, target, ::validWorldPack) {
                error("registry failure")
            }
        }.onSuccess { fail("callback failure must roll back") }
        assertTrue(File(target, "old-marker").isFile)
        assertEquals("1", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_CONTENT_UPDATE_05_authorityReaderNeverObservesPartialContent() {
        val root = tempDir("content-reader")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val prepared = worldPack(File(root, ".A.worldpack.prepared-current"), "A", "2")
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<String> {
                CanonicalPackageAuthorityGate.observe {
                    captured.countDown()
                    check(resume.await(10, TimeUnit.SECONDS))
                    PackageValidator().validateWorldPack(target).version
                }
            }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val write = pool.submit { CanonicalPackageReplacement.activatePrepared(prepared, target, ::validWorldPack) }
            Thread.sleep(150)
            assertFalse(write.isDone)
            resume.countDown()
            assertEquals("1", read.get(5, TimeUnit.SECONDS))
            write.get(5, TimeUnit.SECONDS)
            assertEquals("2", PackageValidator().validateWorldPack(target).version)
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun P19_BOOTSTRAP_DECISION_01_missingTargetAllowsBootstrap() {
        val app = cleanApp()
        LocalGameStore(app).bootstrap()
        assertTrue(File(app.filesDir, "rpgos/saves/Naruto_Default.campaign/campaign.db").isFile)
        assertTrue(File(app.filesDir, "rpgos/worldpacks/Naruto.worldpack/world.db").isFile)
    }

    @Test fun P19_BOOTSTRAP_DECISION_02_targetAppearsBeforeActivationIsPreserved() =
        bootstrapRace("saves/Naruto_Default.campaign", "campaign.db", isCampaign = true)

    @Test fun P19_BOOTSTRAP_DECISION_03_concurrentCanonicalReplacementWinsStaleBootstrapIntent() =
        bootstrapRace("worldpacks/Naruto.worldpack", "world.db", isCampaign = false)

    @Test fun P19_BOOTSTRAP_DECISION_04_campaignWriterVsWriterRace() =
        bootstrapRace("saves/Naruto_Default.campaign", "campaign.db", isCampaign = true)

    @Test fun P19_BOOTSTRAP_DECISION_05_worldPackWriterVsWriterRace() =
        bootstrapRace("worldpacks/Naruto.worldpack", "world.db", isCampaign = false)

    @Test fun P19_REPLACEMENT_RECOVERY_01_crashAfterLiveMovedLeavesRecoverableArtifacts() {
        val root = tempDir("recovery-crash")
        val target = worldPack(File(root, "A.worldpack"), "A", "1")
        val rollback = File(root, ".A.worldpack.rollback-crash")
        assertTrue(target.renameTo(rollback))
        worldPack(File(root, ".A.worldpack.prepared-crash"), "A", "2")
        assertFalse(target.exists())
        assertTrue(rollback.exists())
    }

    @Test fun P19_REPLACEMENT_RECOVERY_02_startupRestoresValidRollback() {
        val root = tempDir("recovery-rollback")
        val target = File(root, "A.worldpack")
        val rollback = worldPack(File(root, ".A.worldpack.rollback-crash"), "A", "1")
        File(rollback, "old-marker").writeText("OLD")
        worldPack(File(root, ".A.worldpack.prepared-crash"), "A", "2")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertTrue(File(target, "old-marker").isFile)
        assertEquals("1", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_REPLACEMENT_RECOVERY_03_stalePreparedCleanup() {
        val root = tempDir("recovery-stale-prepared")
        val target = worldPack(File(root, "A.worldpack"), "A", "3")
        val stale = worldPack(File(root, ".A.worldpack.prepared-stale"), "A", "2")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertFalse(stale.exists())
        assertEquals("3", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_REPLACEMENT_RECOVERY_04_liveValidAndStaleRollbackPreservesLive() {
        val root = tempDir("recovery-live-wins")
        val target = worldPack(File(root, "A.worldpack"), "A", "3")
        val stale = worldPack(File(root, ".A.worldpack.rollback-stale"), "A", "1")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertEquals("3", PackageValidator().validateWorldPack(target).version)
        assertFalse(stale.exists())
    }

    @Test fun P19_REPLACEMENT_RECOVERY_05_invalidLiveAndValidRollbackRestoresRollback() {
        val root = tempDir("recovery-invalid-live")
        val target = File(root, "A.worldpack").apply { mkdirs(); File(this, "partial").writeText("BAD") }
        val rollback = worldPack(File(root, ".A.worldpack.rollback-crash"), "A", "1")
        File(rollback, "old-marker").writeText("OLD")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertTrue(File(target, "old-marker").isFile)
        assertEquals("1", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_REPLACEMENT_RECOVERY_06_ambiguousInvalidStateFailsClosed() {
        val root = tempDir("recovery-ambiguous")
        val target = File(root, "A.worldpack")
        worldPack(File(root, ".A.worldpack.rollback-one"), "A", "1")
        worldPack(File(root, ".A.worldpack.rollback-two"), "A", "1")
        try {
            CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
            fail("ambiguous recovery must fail closed")
        } catch (error: IllegalStateException) {
            assertEquals("PACKAGE_REPLACEMENT_RECOVERY_AMBIGUOUS", error.message)
        }
        assertFalse(target.exists())
    }

    @Test fun P19_REPLACEMENT_RECOVERY_07_recoveryIsIdempotent() {
        val root = tempDir("recovery-idempotent")
        val target = File(root, "A.worldpack")
        val rollback = worldPack(File(root, ".A.worldpack.rollback-crash"), "A", "1")
        File(rollback, "old-marker").writeText("OLD")
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        CanonicalPackageReplacement.reconcile(target, ::validWorldPack)
        assertTrue(File(target, "old-marker").isFile)
    }

    @Test fun P19_REPLACEMENT_RECOVERY_08_secondStartupDoesNotChangeRecoveredResult() {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val target = File(root, "saves/Naruto_Default.campaign")
        val rollback = campaign(File(target.parentFile, ".${target.name}.rollback-crash"), "RECOVER", "7")
        File(rollback, "rollback-marker").writeText("OLD")
        worldPack(File(root, "worldpacks/Naruto.worldpack"), "NARUTO", "1")
        LocalGameStore(app).bootstrap()
        assertTrue(File(target, "rollback-marker").isFile)
        LocalGameStore(app).bootstrap()
        assertTrue(File(target, "rollback-marker").isFile)
    }

    private fun bootstrapRace(relative: String, required: String, isCampaign: Boolean) {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val target = File(root, relative)
        target.deleteRecursively()
        if (isCampaign) worldPack(File(root, "worldpacks/Naruto.worldpack"), "NARUTO", "1")
        else campaign(File(root, "saves/Naruto_Default.campaign"), "NARUTO_DEFAULT", "1")

        val gateHeld = CountDownLatch(1)
        val writeFresh = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit {
                CanonicalPackageAuthorityGate.mutate {
                    gateHeld.countDown()
                    check(writeFresh.await(10, TimeUnit.SECONDS))
                    if (isCampaign) campaign(target, "FRESH", "99") else worldPack(target, "FRESH", "99")
                    File(target, "fresh-marker").writeText("FRESH")
                }
            }
            assertTrue(gateHeld.await(5, TimeUnit.SECONDS))
            val bootstrap = pool.submit { LocalGameStore(app).bootstrap() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var preparedSeen = false
            while (System.nanoTime() < deadline) {
                preparedSeen = target.parentFile?.listFiles()?.any {
                    it.name.startsWith(".${target.name}.prepared-")
                } == true
                if (preparedSeen) break
                Thread.sleep(5)
            }
            assertTrue(preparedSeen)
            writeFresh.countDown()
            writer.get(10, TimeUnit.SECONDS)
            bootstrap.get(10, TimeUnit.SECONDS)
            assertTrue("fresh canonical writer must win stale bootstrap intent", File(target, "fresh-marker").isFile)
            assertTrue(File(target, required).isFile)
        } finally {
            writeFresh.countDown()
            pool.shutdownNow()
        }
    }

    private fun validWorldPack(file: File): Boolean =
        runCatching { PackageValidator().validateWorldPack(file).ok }.getOrDefault(false)

    private fun worldPack(dir: File, id: String, version: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""")
        return dir
    }

    private fun campaign(dir: File, id: String, version: String): File {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
        File(dir, "campaign.json").writeText("""{"id":"$id","version":"$version","core_api":"1"}""")
        return dir
    }

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }

    private fun tempDir(name: String): File = File(
        System.getProperty("java.io.tmpdir"),
        "rpgos-p19-writer-$name-${System.nanoTime()}"
    ).apply { deleteRecursively(); mkdirs() }
}
