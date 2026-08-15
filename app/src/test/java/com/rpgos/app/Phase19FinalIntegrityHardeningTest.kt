package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19FinalIntegrityHardeningTest {
    @Test fun P19_BOOTSTRAP_01_stableBootstrapPathWorks() {
        val app = cleanApp()
        LocalGameStore(app).bootstrap()
        assertTrue(File(app.filesDir, "rpgos/saves/Naruto_Default.campaign/campaign.db").isFile)
        assertTrue(File(app.filesDir, "rpgos/worldpacks/Naruto.worldpack/world.db").isFile)
    }

    @Test fun P19_BOOTSTRAP_02_authorityReadBlocksConcurrentBootstrapMutation() {
        val fixture = blockingBootstrapFixture()
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<CurrentWorldPackAuthority> { fixture.source.currentAuthority() }
            assertTrue(fixture.captured.await(5, TimeUnit.SECONDS))
            val bootstrap = pool.submit { LocalGameStore(fixture.app).bootstrap() }
            Thread.sleep(200)
            assertFalse("bootstrap must wait for authority read", bootstrap.isDone)
            assertFalse(File(fixture.campaign, "campaign.db").exists())
            fixture.resume.countDown()
            read.get(5, TimeUnit.SECONDS)
            bootstrap.get(10, TimeUnit.SECONDS)
            assertTrue(File(fixture.campaign, "campaign.db").isFile)
        } finally {
            fixture.resume.countDown(); pool.shutdownNow()
        }
    }

    @Test fun P19_BOOTSTRAP_03_nextAuthorityReadAfterBootstrapIsCoherent() {
        val app = cleanApp()
        LocalGameStore(app).bootstrap()
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        val root = File(app.filesDir, "rpgos")
        val authority = CanonicalSelectionWorldPackAuthoritySource(prefs, File(root, "saves"), File(root, "worldpacks")).currentAuthority()
        assertTrue(authority.campaignUid.isNotBlank())
        assertTrue(authority.binding.worldPackUid.isNotBlank())
        assertTrue(authority.binding.worldPackVersion.isNotBlank())
    }

    @Test fun P19_BOOTSTRAP_04_sharedActivationFailureRestoresOldCanonicalPackage() {
        val root = tempDir("bootstrap-rollback")
        val target = File(root, "Naruto_Default.campaign").apply { mkdirs(); File(this, "old.txt").writeText("OLD") }
        val prepared = File(root, ".prepared").apply { mkdirs(); File(this, "new.txt").writeText("NEW") }
        val before = directoryFingerprint(target)
        val ops = object : CanonicalPackageFileOps {
            var renameCount = 0
            override fun rename(source: File, target: File): Boolean {
                renameCount++
                return when (renameCount) {
                    1, 3 -> source.renameTo(target)
                    else -> false
                }
            }
            override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
        }
        try {
            CanonicalPackageAuthorityGate.mutate { CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops) }
            fail("activation must fail")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, directoryFingerprint(target))
    }

    @Test fun P19_BOOTSTRAP_05_campaignBootstrapUsesGate() = assertBootstrapTargetWaits("campaign.db")
    @Test fun P19_BOOTSTRAP_06_worldpackBootstrapUsesGate() = assertBootstrapTargetWaits("world.db")

    @Test fun P19_IMPORT_ATOMIC_01_validWorldPackReplacementSucceeds() {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val target = File(root, "worldpacks/A.worldpack"); createWorldPack(target, "A", "1")
        val zip = zipPackage(File(app.cacheDir, "valid-A2.zip")) { createWorldPack(it, "A", "2") }
        val result = RpgPackageManager(app).validatedImportWorldPack(zip, "A.worldpack")
        assertTrue(result.ok); assertEquals("2", PackageValidator().validateWorldPack(target).version)
    }

    @Test fun P19_IMPORT_ATOMIC_02_failedWorldPackReplacementPreservesOldPackage() {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val target = File(root, "worldpacks/A.worldpack"); createWorldPack(target, "A", "1")
        File(target, "sentinel.bin").writeBytes(byteArrayOf(1,2,3,4)); val before = directoryFingerprint(target)
        val zip = zipPackage(File(app.cacheDir, "invalid-A2.zip")) { createWorldPack(it, "A", "2", engineApi = "2") }
        val result = RpgPackageManager(app).validatedImportWorldPack(zip, "A.worldpack")
        assertFalse(result.ok); assertEquals(before, directoryFingerprint(target))
    }

    @Test fun P19_IMPORT_ATOMIC_03_extractionFailurePreservesOldTarget() {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val target = File(root, "worldpacks/A.worldpack"); createWorldPack(target, "A", "1"); val before = directoryFingerprint(target)
        val zip = File(app.cacheDir, "zip-slip.zip")
        ZipOutputStream(zip.outputStream()).use { z -> z.putNextEntry(ZipEntry("../escape")); z.write(byteArrayOf(7)); z.closeEntry() }
        runCatching { RpgPackageManager(app).validatedImportWorldPack(zip, "A.worldpack") }.onSuccess { fail("zip-slip extraction must fail") }
        assertEquals(before, directoryFingerprint(target))
    }

    @Test fun P19_IMPORT_ATOMIC_04_validationFailurePreservesOldTarget() = P19_IMPORT_ATOMIC_02_failedWorldPackReplacementPreservesOldPackage()

    @Test fun P19_IMPORT_ATOMIC_05_validCampaignReplacementSucceeds() {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val target = File(root, "saves/C1.campaign"); createCampaign(target, "C1", "1")
        val zip = zipPackage(File(app.cacheDir, "valid-C2.zip")) { createCampaign(it, "C1", "2") }
        val result = RpgPackageManager(app).validatedImportCampaign(zip, "C1.campaign")
        assertTrue(result.ok); assertEquals("2", PackageValidator().validateCampaign(target).version)
    }

    @Test fun P19_IMPORT_ATOMIC_06_failedCampaignReplacementPreservesOldCampaign() {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val target = File(root, "saves/C1.campaign"); createCampaign(target, "C1", "1")
        File(target, "sentinel.bin").writeBytes(byteArrayOf(9,8,7)); val before = directoryFingerprint(target)
        val zip = zipPackage(File(app.cacheDir, "invalid-C2.zip")) { createCampaign(it, "C1", "2", coreApi = "2") }
        val result = RpgPackageManager(app).validatedImportCampaign(zip, "C1.campaign")
        assertFalse(result.ok); assertEquals(before, directoryFingerprint(target))
    }

    @Test fun P19_IMPORT_ATOMIC_07_authorityReaderNeverSeesPartialLiveTarget() {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val saves = File(root, "saves"); val worldpacks = File(root, "worldpacks")
        createCampaign(File(saves, "C1.campaign"), "C1", "1"); createWorldPack(File(worldpacks, "A.worldpack"), "A", "1")
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("active_campaign", "C1.campaign").putString("active_worldpack", "A.worldpack").commit()
        val captured = CountDownLatch(1); val resume = CountDownLatch(1)
        val source = CanonicalSelectionWorldPackAuthoritySource(blockingPrefs(prefs, captured, resume), saves, worldpacks)
        val zip = zipPackage(File(app.cacheDir, "A2-concurrent.zip")) { createWorldPack(it, "A", "2") }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<CurrentWorldPackAuthority> { source.currentAuthority() }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val replace = pool.submit { RpgPackageManager(app).validatedImportWorldPack(zip, "A.worldpack") }
            Thread.sleep(200); assertFalse(replace.isDone)
            assertEquals("1", PackageValidator().validateWorldPack(File(worldpacks, "A.worldpack")).version)
            resume.countDown(); assertEquals("1", read.get(5, TimeUnit.SECONDS).binding.worldPackVersion)
            assertTrue(replace.get(10, TimeUnit.SECONDS).ok)
            assertEquals("2", source.currentAuthority().binding.worldPackVersion)
        } finally { resume.countDown(); pool.shutdownNow() }
    }

    @Test fun P19_IMPORT_ATOMIC_08_rollbackFailureFailsClosedWithExplicitError() {
        val root = tempDir("rollback-failure")
        val target = File(root, "A.worldpack").apply { mkdirs(); File(this, "old").writeText("old") }
        val prepared = File(root, ".prepared").apply { mkdirs(); File(this, "new").writeText("new") }
        val ops = object : CanonicalPackageFileOps {
            var renameCount = 0
            override fun rename(source: File, target: File): Boolean {
                renameCount++
                return if (renameCount == 1) source.renameTo(target) else false
            }
            override fun deleteRecursively(target: File): Boolean = target.deleteRecursively()
        }
        try {
            CanonicalPackageAuthorityGate.mutate { CanonicalPackageReplacement.activatePreparedUnderGate(prepared, target, ops) }
            fail("rollback failure must surface")
        } catch (e: IllegalStateException) {
            assertEquals("PACKAGE_REPLACEMENT_ROLLBACK_FAILED", e.message)
        }
    }

    @Test fun P19_PROVIDER_STATE_01_innerProviderWithMutableOuterHostRejected() = assertProviderRejected(MutableHost().Provider())
    @Test fun P19_PROVIDER_STATE_02_outerFinalReferenceToNestedMutableObjectRejected() = assertProviderRejected(NestedHost().Provider())
    @Test fun P19_PROVIDER_STATE_03_capturedMutableCollectionRejected() = assertProviderRejected(CapturedListHost().Provider())
    @Test fun P19_PROVIDER_STATE_04_capturedStringBuilderRejected() = assertProviderRejected(BuilderHost().Provider())
    @Test fun P19_PROVIDER_STATE_05_safeStatelessProviderAccepted() { assertNotNull(WorldRuleProviderRegistry.of(listOf(StatelessProvider()))) }
    @Test fun P19_PROVIDER_STATE_06_safeStringScalarConfigurationAccepted() { assertNotNull(WorldRuleProviderRegistry.of(listOf(ScalarProvider("safe", 7, true)))) }
    @Test fun P19_PROVIDER_STATE_07_constantSpecificEnumMutableStateRejected() = assertProviderRejected(EnumProvider(MutableMode.EVIL))
    @Test fun P19_PROVIDER_STATE_08_inheritedUnsafeStateRejected() = assertProviderRejected(InheritedUnsafeProvider())

    private fun assertBootstrapTargetWaits(required: String) {
        val fixture = blockingBootstrapFixture(); val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<CurrentWorldPackAuthority> { fixture.source.currentAuthority() }
            assertTrue(fixture.captured.await(5, TimeUnit.SECONDS))
            val bootstrap = pool.submit { LocalGameStore(fixture.app).bootstrap() }
            Thread.sleep(200); assertFalse(bootstrap.isDone)
            val target = if (required == "campaign.db") File(fixture.campaign, required) else File(File(fixture.app.filesDir, "rpgos/worldpacks/Naruto.worldpack"), required)
            if (required == "campaign.db") assertFalse(target.exists())
            fixture.resume.countDown(); runCatching { read.get(5, TimeUnit.SECONDS) }; bootstrap.get(10, TimeUnit.SECONDS)
            assertTrue(target.isFile)
        } finally { fixture.resume.countDown(); pool.shutdownNow() }
    }

    private data class BootstrapFixture(val app: Context, val campaign: File, val source: CanonicalSelectionWorldPackAuthoritySource, val captured: CountDownLatch, val resume: CountDownLatch)
    private fun blockingBootstrapFixture(): BootstrapFixture {
        val app = cleanApp(); val root = File(app.filesDir, "rpgos")
        val saves = File(root, "saves"); val worldpacks = File(root, "worldpacks")
        val campaign = File(saves, "Naruto_Default.campaign").apply { mkdirs(); File(this, "campaign.json").writeText("""{"id":"naruto-default","core_api":"1"}""") }
        createWorldPack(File(worldpacks, "Naruto.worldpack"), "NARUTO", "1")
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("active_campaign", "Naruto_Default.campaign").putString("active_worldpack", "Naruto.worldpack").commit()
        val captured = CountDownLatch(1); val resume = CountDownLatch(1)
        val source = CanonicalSelectionWorldPackAuthoritySource(blockingPrefs(prefs, captured, resume), saves, worldpacks)
        return BootstrapFixture(app, campaign, source, captured, resume)
    }

    private fun assertProviderRejected(provider: WorldRuleProvider) {
        try { WorldRuleProviderRegistry.of(listOf(provider)); fail("unsafe retained state must be rejected") }
        catch (e: PlayerDomainEngineStructuralException) { assertTrue(e.message.orEmpty().contains("WORLD_RULE_PROVIDER_STATE")) }
    }

    private class MutableHost { var counter = 0; inner class Provider : TestProvider("INNER") { override fun evaluate(request: WorldRuleRequest) = if (counter == 0) WorldRuleDecision.Allowed.create("R") else WorldRuleDecision.Rejected.create("R","MUT") } }
    private class Box(var value: Int)
    private class NestedHost { val box = Box(0); inner class Provider : TestProvider("NESTED") }
    private class CapturedListHost { val values = mutableListOf("x"); inner class Provider : TestProvider("LIST") }
    private class BuilderHost { val builder = StringBuilder("x"); inner class Provider : TestProvider("BUILDER") }
    private open class TestProvider(uid: String) : WorldRuleProvider("P19-$uid", "1", "A-$uid", "1") { override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("ALLOW") }
    private class StatelessProvider : TestProvider("STATELESS")
    private class ScalarProvider(val label: String, val count: Int, val enabled: Boolean) : TestProvider("SCALAR")
    private enum class MutableMode { SAFE, EVIL { var counter: Int = 0 } }
    private class EnumProvider(val mode: MutableMode) : TestProvider("ENUM")
    private open class UnsafeParent(val retained: StringBuilder = StringBuilder("unsafe")) : TestProvider("INHERITED")
    private class InheritedUnsafeProvider : UnsafeParent()

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        File(app.cacheDir, "p19-integrity").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }

    private fun tempDir(name: String): File = File(RuntimeEnvironment.getApplication().cacheDir, "p19-integrity/$name-${System.nanoTime()}").apply { deleteRecursively(); mkdirs() }

    private fun blockingPrefs(delegate: SharedPreferences, captured: CountDownLatch, resume: CountDownLatch): SharedPreferences = object : SharedPreferences by delegate {
        override fun getAll(): MutableMap<String, *> { val snapshot = LinkedHashMap(delegate.all); captured.countDown(); check(resume.await(10, TimeUnit.SECONDS)); return snapshot }
    }

    private fun createWorldPack(dir: File, id: String, version: String, engineApi: String = "1") { dir.mkdirs(); SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close(); File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"$engineApi"}""") }
    private fun createCampaign(dir: File, id: String, version: String, coreApi: String = "1") { dir.mkdirs(); SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close(); File(dir, "campaign.json").writeText("""{"id":"$id","version":"$version","core_api":"$coreApi"}""") }

    private fun zipPackage(file: File, create: (File) -> Unit): File {
        val staging = File(file.parentFile, file.nameWithoutExtension + "-${System.nanoTime()}").apply { deleteRecursively(); mkdirs() }; create(staging)
        ZipOutputStream(file.outputStream()).use { zip -> staging.walkTopDown().filter { it.isFile }.forEach { input -> zip.putNextEntry(ZipEntry(input.relativeTo(staging).invariantSeparatorsPath)); input.inputStream().use { it.copyTo(zip) }; zip.closeEntry() } }
        staging.deleteRecursively(); return file
    }

    private fun directoryFingerprint(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.forEach { file ->
            digest.update(file.relativeTo(dir).invariantSeparatorsPath.toByteArray()); digest.update(0); digest.update(file.readBytes()); digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
