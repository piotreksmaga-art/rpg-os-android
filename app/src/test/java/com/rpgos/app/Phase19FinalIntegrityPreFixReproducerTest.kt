package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19FinalIntegrityPreFixReproducerTest {
    @Test
    fun PRE_FIX_BOOTSTRAP_writerMutatesLiveCampaignWhileAuthorityReadIsHeld() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos").apply { deleteRecursively() }
        val saves = File(root, "saves")
        val worldpacks = File(root, "worldpacks")
        val campaign = File(saves, "C1.campaign").apply { mkdirs() }
        File(campaign, "campaign.json").writeText("""{"id":"C1","core_api":"1"}""")
        val world = File(worldpacks, "A.worldpack")
        createWorldPack(world, "A", "1")
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("active_campaign", "C1.campaign").putString("active_worldpack", "A.worldpack").commit()

        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val source = CanonicalSelectionWorldPackAuthoritySource(blockingPrefs(prefs, captured, resume), saves, worldpacks)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<CurrentWorldPackAuthority> { source.currentAuthority() }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val bootstrap = pool.submit { LocalGameStore(app).bootstrap() }
            bootstrap.get(10, TimeUnit.SECONDS)
            assertTrue("pre-fix bootstrap must have mutated the live campaign while read lock is held", File(campaign, "campaign.db").exists())
            resume.countDown()
            runCatching { read.get(5, TimeUnit.SECONDS) }
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun PRE_FIX_IMPORT_failedValidatedReplacementDestroysPreviousValidTargets() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos").apply { deleteRecursively() }
        val oldWorld = File(root, "worldpacks/A.worldpack")
        createWorldPack(oldWorld, "A", "1")
        val oldCampaign = File(root, "saves/C1.campaign")
        createCampaign(oldCampaign, "C1", "1")
        val worldZip = zipPackage(File(app.cacheDir, "p19-invalid-world.zip")) { staging ->
            createWorldPack(staging, "A", "2", engineApi = "2")
        }
        val campaignZip = zipPackage(File(app.cacheDir, "p19-invalid-campaign.zip")) { staging ->
            createCampaign(staging, "C1", "2", coreApi = "2")
        }
        val manager = RpgPackageManager(app)
        assertFalse(manager.validatedImportWorldPack(worldZip, "A.worldpack").ok)
        assertFalse("pre-fix old valid worldpack is lost", oldWorld.exists())
        assertFalse(manager.validatedImportCampaign(campaignZip, "C1.campaign").ok)
        assertFalse("pre-fix old valid campaign is lost", oldCampaign.exists())
    }

    @Test
    fun PRE_FIX_PROVIDER_syntheticOuterReferenceBypassesRetainedStateValidation() {
        val host = MutableHost()
        val provider = host.Provider()
        WorldRuleProviderRegistry.of(listOf(provider))
        val request = request()
        host.counter = 0
        val first = provider.evaluate(request)
        host.counter = 1
        val second = provider.evaluate(request)
        assertTrue(first is WorldRuleDecision.Allowed)
        assertTrue(second is WorldRuleDecision.Rejected)
    }

    private class MutableHost {
        var counter: Int = 0
        inner class Provider : WorldRuleProvider("P19-SYNTHETIC", "1", "A", "1") {
            override fun evaluate(request: WorldRuleRequest): WorldRuleDecision =
                if (counter == 0) WorldRuleDecision.Allowed.create("HOST-STATE")
                else WorldRuleDecision.Rejected.create("HOST-STATE", "COUNTER_CHANGED")
        }
    }

    private fun request(): WorldRuleRequest {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid = "CMD-P19-SYNTHETIC",
            campaignUid = "C1",
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRAIN,
            payload = TrainCommandPayload(DomainRef("STAT", "STR"), 1L, "METHOD"),
            provenance = CommandProvenance("P19-SYNTHETIC")
        )
        return WorldRuleRequest.commandPrecheck(WorldPackRuleBinding("A", "1"), "C1", actor, command, "CMD-FP", "CTX-FP")
    }

    private fun blockingPrefs(delegate: SharedPreferences, captured: CountDownLatch, resume: CountDownLatch): SharedPreferences =
        object : SharedPreferences by delegate {
            override fun getAll(): MutableMap<String, *> {
                val snapshot = LinkedHashMap(delegate.all)
                captured.countDown()
                check(resume.await(10, TimeUnit.SECONDS))
                return snapshot
            }
        }

    private fun createWorldPack(dir: File, id: String, version: String, engineApi: String = "1") {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"$engineApi"}""")
    }

    private fun createCampaign(dir: File, id: String, version: String, coreApi: String = "1") {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
        File(dir, "campaign.json").writeText("""{"id":"$id","version":"$version","core_api":"$coreApi"}""")
    }

    private fun zipPackage(file: File, create: (File) -> Unit): File {
        val staging = File(file.parentFile, file.nameWithoutExtension + "-staging").apply { deleteRecursively(); mkdirs() }
        create(staging)
        ZipOutputStream(file.outputStream()).use { zip ->
            staging.walkTopDown().filter { it.isFile }.forEach { input ->
                zip.putNextEntry(ZipEntry(input.relativeTo(staging).invariantSeparatorsPath))
                input.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        staging.deleteRecursively()
        return file
    }
}
