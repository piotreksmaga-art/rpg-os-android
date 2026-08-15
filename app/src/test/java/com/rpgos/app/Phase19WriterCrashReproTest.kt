package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase19WriterCrashReproTest {
    @Test fun P19_REPRO_CONTENT_UPDATE_ROLLBACK_FAILURE_leavesInvalidCanonicalTarget() = runBlocking {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val target = File(root, "worldpacks/A.worldpack")
        createWorldPack(target, "A", "1")
        File(target, "old-marker.txt").writeText("OLD")

        val zip = zipPackage(File(app.cacheDir, "A2.zip")) { dir ->
            createWorldPack(dir, "A", "2")
            repeat(2500) { index -> File(dir, "padding/$index.bin").apply { parentFile.mkdirs(); writeText("x") } }
        }
        val bytes = zip.readBytes()
        val server = ServerSocket(0)
        val served = CountDownLatch(1)
        val serverThread = Thread {
            server.use { socket ->
                socket.accept().use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = client.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                    served.countDown()
                }
            }
        }.apply { start() }

        val registry = File(root, "content/installed-content.json").apply {
            mkdirs()
            File(this, "keep").writeText("force registry rename failure")
        }
        assertTrue(registry.isDirectory)

        val backups = File(root, "content/backups")
        val sabotaged = CountDownLatch(1)
        val watcher = Thread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline) {
                val backup = backups.listFiles()?.firstOrNull { it.name.startsWith("A-") && File(it, "old-marker.txt").isFile }
                if (backup != null) {
                    backup.deleteRecursively()
                    backup.writeText("BROKEN_ROLLBACK_SOURCE")
                    sabotaged.countDown()
                    return@Thread
                }
                Thread.sleep(2)
            }
        }.apply { start() }

        val pkg = ContentPackageManifest(
            id = "A",
            type = ContentPackageType.WORLD,
            version = 2,
            minEngineVersionCode = 1,
            downloadUrl = "http://127.0.0.1:${server.localPort}/A2.zip",
            sha256 = sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            description = "repro"
        )

        val failure = runCatching { ContentUpdateManager(app, "unused").install(pkg) }.exceptionOrNull()
        assertTrue(served.await(5, TimeUnit.SECONDS))
        assertTrue("backup sabotage must hit rollback source", sabotaged.await(5, TimeUnit.SECONDS))
        assertTrue("install must fail", failure != null)
        assertTrue("pre-fix rollback can leave malformed live target", !target.isDirectory || !File(target, "world.db").isFile)
    }

    @Test fun P19_REPRO_BOOTSTRAP_STALE_INTENT_overwritesFreshCanonicalPackage() {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val campaign = File(root, "saves/Naruto_Default.campaign")
        campaign.deleteRecursively()
        createWorldPack(File(root, "worldpacks/Naruto.worldpack"), "NARUTO", "1")

        val gateHeld = CountDownLatch(1)
        val installFresh = CountDownLatch(1)
        val releaseGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val canonicalWriter = pool.submit {
                CanonicalPackageAuthorityGate.mutate {
                    gateHeld.countDown()
                    check(installFresh.await(10, TimeUnit.SECONDS))
                    createCampaign(campaign, "FRESH", "99")
                    File(campaign, "fresh-marker.txt").writeText("FRESH_CANONICAL")
                    releaseGate.countDown()
                }
            }
            assertTrue(gateHeld.await(5, TimeUnit.SECONDS))
            val bootstrap = pool.submit { LocalGameStore(app).bootstrap() }

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var preparedSeen = false
            while (System.nanoTime() < deadline) {
                preparedSeen = campaign.parentFile?.listFiles()?.any {
                    it.name.startsWith(".${campaign.name}.prepared-")
                } == true
                if (preparedSeen) break
                Thread.sleep(5)
            }
            assertTrue("bootstrap must prepare stale intent before entering gate", preparedSeen)
            installFresh.countDown()
            canonicalWriter.get(10, TimeUnit.SECONDS)
            bootstrap.get(10, TimeUnit.SECONDS)

            assertFalse("pre-fix stale bootstrap overwrites fresh canonical marker", File(campaign, "fresh-marker.txt").exists())
        } finally {
            installFresh.countDown()
            releaseGate.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun P19_REPRO_INTERRUPTED_REPLACEMENT_hasNoDeterministicRecovery() {
        val app = cleanApp()
        val root = File(app.filesDir, "rpgos")
        val parent = File(root, "saves").apply { mkdirs() }
        val target = File(parent, "Naruto_Default.campaign")
        target.deleteRecursively()

        val rollback = File(parent, ".${target.name}.rollback-crash").apply {
            createCampaign(this, "RECOVER_ME", "7")
            File(this, "rollback-marker.txt").writeText("ROLLBACK_VALID")
        }
        val prepared = File(parent, ".${target.name}.prepared-crash").apply {
            createCampaign(this, "PREPARED", "8")
            File(this, "prepared-marker.txt").writeText("PREPARED_VALID")
        }
        createWorldPack(File(root, "worldpacks/Naruto.worldpack"), "NARUTO", "1")

        LocalGameStore(app).bootstrap()

        assertTrue("bootstrap creates some live target", File(target, "campaign.db").isFile)
        assertFalse("pre-fix startup does not restore valid rollback", File(target, "rollback-marker.txt").exists())
        assertTrue("stale rollback remains unreconciled", rollback.exists())
        assertTrue("stale prepared remains unreconciled", prepared.exists())
    }

    private fun cleanApp(): Context {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "rpgos").deleteRecursively()
        File(app.cacheDir, "content-updates").deleteRecursively()
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        return app
    }

    private fun createWorldPack(dir: File, id: String, version: String) {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText("""{"id":"$id","version":"$version","engine_api":"1"}""")
    }

    private fun createCampaign(dir: File, id: String, version: String) {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
        File(dir, "campaign.json").writeText("""{"id":"$id","version":"$version","core_api":"1"}""")
    }

    private fun zipPackage(file: File, create: (File) -> Unit): File {
        val staging = File(file.parentFile, "repro-${System.nanoTime()}").apply { mkdirs() }
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
