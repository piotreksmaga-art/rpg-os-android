package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRuleProviderPhase19PackageContentToctouReproTest {
    private val c1 = "C1"
    private val c2 = "C2"
    private val a1 = WorldPackRuleBinding("A", "1")
    private val a2 = WorldPackRuleBinding("A", "2")
    private val b1 = WorldPackRuleBinding("B", "1")

    @Test
    fun CASE_A_supportedContentUpdateCanProduceImpossibleC1A2() {
        val fixture = fixture()
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("active_campaign", "C1.campaign")
            .putString("active_campaign_id", c1)
            .putString("active_worldpack", "A.worldpack")
            .commit()

        val a2Zip = worldPackZip(File(app.cacheDir, "A2.zip"), a2)
        val bytes = a2Zip.readBytes()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/A2.zip") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/A2.zip"
            var interleaved = false
            val hookedPrefs = object : SharedPreferences by prefs {
                override fun getAll(): MutableMap<String, *> {
                    val captured = LinkedHashMap(prefs.all)
                    if (!interleaved) {
                        interleaved = true
                        prefs.edit()
                            .putString("active_campaign", "C2.campaign")
                            .putString("active_campaign_id", c2)
                            .putString("active_worldpack", "A.worldpack")
                            .commit()
                        runBlocking {
                            ContentUpdateManager(app).install(
                                ContentPackageManifest(
                                    id = "A",
                                    type = ContentPackageType.WORLD,
                                    version = 2,
                                    minEngineVersionCode = 1,
                                    downloadUrl = url,
                                    sha256 = sha256(a2Zip)
                                )
                            )
                        }
                    }
                    return captured
                }
            }
            val source = CanonicalSelectionWorldPackAuthoritySource(
                hookedPrefs,
                File(root, "saves"),
                File(root, "worldpacks")
            )

            val observed = source.currentAuthority()
            assertEquals("Pre-fix must not synthesize C1/A2", CurrentWorldPackAuthority(c1, a1), observed)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun CASE_B_supportedValidatedImportCanProduceImpossibleC1B() {
        fixture()
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("active_campaign", "C1.campaign")
            .putString("active_campaign_id", c1)
            .putString("active_worldpack", "A.worldpack")
            .commit()
        val bZip = worldPackZip(File(app.cacheDir, "B.zip"), b1)

        var interleaved = false
        val hookedPrefs = object : SharedPreferences by prefs {
            override fun getAll(): MutableMap<String, *> {
                val captured = LinkedHashMap(prefs.all)
                if (!interleaved) {
                    interleaved = true
                    prefs.edit()
                        .putString("active_campaign", "C2.campaign")
                        .putString("active_campaign_id", c2)
                        .putString("active_worldpack", "B.worldpack")
                        .commit()
                    val result = RpgPackageManager(app).validatedImportWorldPack(bZip, "A.worldpack")
                    assertTrue("Pre-fix supported import should accept B payload into stale A directory", result.ok)
                }
                return captured
            }
        }
        val source = CanonicalSelectionWorldPackAuthoritySource(
            hookedPrefs,
            File(root, "saves"),
            File(root, "worldpacks")
        )

        val observed = source.currentAuthority()
        assertEquals("Pre-fix must not synthesize C1/B", CurrentWorldPackAuthority(c1, a1), observed)
    }

    private fun fixture() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        root.deleteRecursively()
        createCampaign(File(root, "saves/C1.campaign"), c1)
        createCampaign(File(root, "saves/C2.campaign"), c2)
        createWorldPack(File(root, "worldpacks/A.worldpack"), a1)
        createWorldPack(File(root, "worldpacks/B.worldpack"), b1)
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun createCampaign(dir: File, uid: String) {
        dir.mkdirs()
        File(dir, "campaign.json").writeText("""{"id":"$uid"}""")
    }

    private fun createWorldPack(dir: File, binding: WorldPackRuleBinding) {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText(
            """{"id":"${binding.worldPackUid}","version":"${binding.worldPackVersion}","engine_api":"1"}"""
        )
    }

    private fun worldPackZip(file: File, binding: WorldPackRuleBinding): File {
        val staging = File(file.parentFile, "${file.nameWithoutExtension}-staging")
        staging.deleteRecursively()
        createWorldPack(staging, binding)
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

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                md.update(buffer, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
