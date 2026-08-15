package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRuleProviderPhase19PackageContentAuthorityTest {
    private val c1 = "C1"
    private val c2 = "C2"
    private val a1 = WorldPackRuleBinding("A", "1")
    private val a2 = WorldPackRuleBinding("A", "2")
    private val b1 = WorldPackRuleBinding("B", "1")

    @Before
    fun resetProbe() {
        Probe.a1 = 0
        Probe.a2 = 0
        Probe.b1 = 0
        Probe.seen.clear()
    }

    @Test
    fun P19_AUTH_CONTENT_01_stableC1A1ProducesCorrectAuthority() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        assertEquals(CurrentWorldPackAuthority(c1, a1), f.source.currentAuthority())
    }

    @Test
    fun P19_AUTH_CONTENT_02_selectionSnapshotPlusA1ToA2CannotProduceC1A2() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val source = CanonicalSelectionWorldPackAuthoritySource(
            blockingSnapshotPrefs(f.prefs, captured, resume), f.saves, f.worldpacks
        )
        val zip = worldPackZip(File(f.app.cacheDir, "p19-a2-race.zip"), a2)
        val server = zipServer(zip)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<CurrentWorldPackAuthority> { source.currentAuthority() }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val writerStarted = CountDownLatch(1)
            val write = pool.submit {
                writerStarted.countDown()
                f.selection.setActiveCampaign("C2.campaign")
                installWorld(f.app, zip, a2, server)
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse("supported package mutation must wait for coherent read", write.isDone)
            resume.countDown()
            assertEquals(CurrentWorldPackAuthority(c1, a1), read.get(5, TimeUnit.SECONDS))
            write.get(10, TimeUnit.SECONDS)
            assertEquals(CurrentWorldPackAuthority(c2, a2), f.source.currentAuthority())
        } finally {
            resume.countDown()
            server.stop(0)
            pool.shutdownNow()
        }
    }

    @Test
    fun P19_AUTH_CONTENT_03_oldADirectoryReplacementCannotProduceC1PlusB() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        val captured = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val source = CanonicalSelectionWorldPackAuthoritySource(
            blockingSnapshotPrefs(f.prefs, captured, resume), f.saves, f.worldpacks
        )
        val zip = worldPackZip(File(f.app.cacheDir, "p19-b-race.zip"), b1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val read = pool.submit<CurrentWorldPackAuthority> { source.currentAuthority() }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            val writerStarted = CountDownLatch(1)
            val write = pool.submit {
                writerStarted.countDown()
                f.selection.setActiveCampaign("C2.campaign")
                f.selection.setActiveWorldPack("B.worldpack")
                check(RpgPackageManager(f.app).validatedImportWorldPack(zip, "A.worldpack").ok)
            }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse("supported import/replace must wait for coherent read", write.isDone)
            resume.countDown()
            assertEquals(CurrentWorldPackAuthority(c1, a1), read.get(5, TimeUnit.SECONDS))
            write.get(10, TimeUnit.SECONDS)
            assertEquals(CurrentWorldPackAuthority(c2, b1), f.source.currentAuthority())
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun P19_AUTH_CONTENT_04_completedLegitimateA1ToA2IsVisibleNextResolution() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        val zip = worldPackZip(File(f.app.cacheDir, "p19-a2-complete.zip"), a2)
        val server = zipServer(zip)
        try {
            installWorld(f.app, zip, a2, server)
        } finally {
            server.stop(0)
        }
        assertEquals(CurrentWorldPackAuthority(c1, a2), f.source.currentAuthority())
    }

    @Test
    fun P19_AUTH_CONTENT_05_completedC1AToC2BIsVisibleNextResolution() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        f.selection.setActiveCampaign("C2.campaign")
        f.selection.setActiveWorldPack("B.worldpack")
        assertEquals(CurrentWorldPackAuthority(c2, b1), f.source.currentAuthority())
    }

    @Test
    fun P19_AUTH_CONTENT_06_sameLongLivedEngineAcrossPackageUpdate() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        val engine = engine(f.selection.activeWorldPackAuthoritySnapshot(), listOf(a2))

        // Before update the live authority is still A@1. The same engine therefore reaches the
        // provider-version guard rather than pretending the canonical authority is already A@2.
        assertStructural("WORLD_RULE_PROVIDER_VERSION_MISMATCH") {
            engine.resolve(train(c1, "06-BEFORE"), context(c1, a1))
        }
        assertEquals(0, Probe.a2)

        val zip = worldPackZip(File(f.app.cacheDir, "p19-a2-engine.zip"), a2)
        val server = zipServer(zip)
        try {
            installWorld(f.app, zip, a2, server)
        } finally {
            server.stop(0)
        }

        assertTrue(engine.resolve(train(c1, "06-AFTER"), context(c1, a2)) is PlayerResolutionOutcome.Resolved)
        assertTrue(Probe.a2 > 0)
    }

    @Test
    fun P19_AUTH_CONTENT_07_sameLongLivedEngineAcrossCampaignAndPackageSwitch() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        val engine = engine(f.selection.activeWorldPackAuthoritySnapshot(), listOf(a1, b1))
        assertTrue(engine.resolve(train(c1, "07-A"), context(c1, a1)) is PlayerResolutionOutcome.Resolved)

        f.selection.setActiveCampaign("C2.campaign")
        f.selection.setActiveWorldPack("B.worldpack")

        assertTrue(engine.resolve(train(c2, "07-B"), context(c2, b1)) is PlayerResolutionOutcome.Resolved)
        assertTrue(Probe.a1 > 0)
        assertTrue(Probe.b1 > 0)
    }

    @Test
    fun P19_AUTH_CONTENT_08_packageReadFailureFailsClosed() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        CanonicalPackageAuthorityGate.mutate {
            File(f.worldpacks, "A.worldpack/world.db").delete()
        }
        val engine = engine(f.selection.activeWorldPackAuthoritySnapshot(), listOf(a1))
        assertStructural("WORLD_RULE_AUTHORITY_READ_FAILED") {
            engine.resolve(train(c1, "08"), context(c1, a1))
        }
        assertEquals(0, Probe.a1)
    }

    @Test
    fun P19_AUTH_CONTENT_09_inconsistentAuthorityInvokesNoProvider() {
        val f = fixture()
        select(f, c1, "A.worldpack")
        val engine = engine(f.selection.activeWorldPackAuthoritySnapshot(), listOf(a1, b1))
        assertStructural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine.resolve(train(c1, "09"), context(c1, b1))
        }
        assertEquals(0, Probe.a1)
        assertEquals(0, Probe.b1)
    }

    @Test
    fun P19_AUTH_CONTENT_10_oneCoherentAuthorityObservationPerResolution() {
        val reads = AtomicInteger(0)
        val resolver = WorldPackAuthorityResolver {
            reads.incrementAndGet()
            a1
        }
        val engine = engine(resolver, listOf(a1))
        assertTrue(engine.resolve(train(c1, "10"), context(c1, a1)) is PlayerResolutionOutcome.Resolved)
        assertEquals(1, reads.get())
    }

    @Test
    fun P19_AUTH_CONTENT_11_bothPhase19StagesUseSamePinnedBinding() {
        val reads = AtomicInteger(0)
        val resolver = WorldPackAuthorityResolver {
            if (reads.getAndIncrement() == 0) a1 else b1
        }
        val engine = engine(resolver, listOf(a1))
        assertTrue(engine.resolve(train(c1, "11"), context(c1, a1)) is PlayerResolutionOutcome.Resolved)
        assertEquals(1, reads.get())
        assertEquals(listOf(a1, a1), Probe.seen)
    }

    @Test
    fun P19_AUTH_CONTENT_12_directoryAliasDoesNotOverrideManifestLogicalIdentity() {
        val f = fixture(aDirName = "friendly-name.worldpack")
        select(f, c1, "friendly-name.worldpack")
        val authority = f.source.currentAuthority()
        assertEquals(a1, authority.binding)
        assertEquals("A", authority.binding.worldPackUid)
    }

    private data class Fixture(
        val app: Context,
        val prefs: SharedPreferences,
        val saves: File,
        val worldpacks: File,
        val selection: CampaignSelectionManager,
        val source: CanonicalSelectionWorldPackAuthoritySource
    )

    private fun fixture(aDirName: String = "A.worldpack"): Fixture {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        root.deleteRecursively()
        val saves = File(root, "saves")
        val worldpacks = File(root, "worldpacks")
        createCampaign(File(saves, "C1.campaign"), c1)
        createCampaign(File(saves, "C2.campaign"), c2)
        createWorldPack(File(worldpacks, aDirName), a1)
        createWorldPack(File(worldpacks, "B.worldpack"), b1)
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return Fixture(
            app = app,
            prefs = prefs,
            saves = saves,
            worldpacks = worldpacks,
            selection = CampaignSelectionManager(app),
            source = CanonicalSelectionWorldPackAuthoritySource(prefs, saves, worldpacks)
        )
    }

    private fun select(f: Fixture, campaignUid: String, worldPackDirName: String) {
        f.selection.setActiveCampaign("$campaignUid.campaign")
        f.selection.setActiveWorldPack(worldPackDirName)
    }

    private fun blockingSnapshotPrefs(
        delegate: SharedPreferences,
        captured: CountDownLatch,
        resume: CountDownLatch
    ): SharedPreferences = object : SharedPreferences by delegate {
        override fun getAll(): MutableMap<String, *> {
            val snapshot = LinkedHashMap(delegate.all)
            captured.countDown()
            check(resume.await(5, TimeUnit.SECONDS)) { "Timed out waiting to resume authority read" }
            return snapshot
        }
    }

    private fun createCampaign(dir: File, uid: String) {
        dir.mkdirs()
        File(dir, "campaign.json").writeText("""{"id":"$uid"}""")
        SQLiteDatabase.openOrCreateDatabase(File(dir, "campaign.db"), null).close()
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

    private fun zipServer(file: File): HttpServer {
        val bytes = file.readBytes()
        return HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/world.zip") { exchange ->
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }

    private fun installWorld(
        app: Context,
        zip: File,
        binding: WorldPackRuleBinding,
        server: HttpServer
    ) {
        val manifest = ContentPackageManifest(
            id = binding.worldPackUid,
            type = ContentPackageType.WORLD,
            version = binding.worldPackVersion.toInt(),
            minEngineVersionCode = 1,
            downloadUrl = "http://127.0.0.1:${server.address.port}/world.zip",
            sha256 = sha256(zip)
        )
        runBlocking { ContentUpdateManager(app).install(manifest) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun engine(
        resolver: WorldPackAuthorityResolver,
        bindings: List<WorldPackRuleBinding>
    ): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(bindings.map(::Provider)),
        worldPackAuthority = resolver
    )

    private fun train(campaignUid: String, suffix: String) = PlayerCommand(
        commandUid = "CMD-P19-AUTH-CONTENT-$suffix",
        campaignUid = campaignUid,
        actor = CommandActorRef("PLAYER", "P1"),
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-AUTH-CONTENT")
    )

    private fun context(campaignUid: String, binding: WorldPackRuleBinding) =
        PlayerResolutionContext.create(
            campaignUid,
            CommandActorRef("PLAYER", "P1"),
            setOf(
                CampaignScopedDomainRef(campaignUid, DomainRef("PLAYER", "P1")),
                CampaignScopedDomainRef(campaignUid, DomainRef("STAT", "STR"))
            ),
            worldRuleMode = WorldRuleMode.Bound(binding)
        )

    private fun assertStructural(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected structural failure $code")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-AUTH-CONTENT-COMPONENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "CHANGE-${command.commandUid}",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }

    private object Probe {
        var a1 = 0
        var a2 = 0
        var b1 = 0
        val seen = mutableListOf<WorldPackRuleBinding>()
    }

    private class Provider(binding: WorldPackRuleBinding) : WorldRuleProvider(
        "P19-CONTENT-${binding.worldPackUid}-${binding.worldPackVersion}",
        "1",
        binding.worldPackUid,
        binding.worldPackVersion
    ) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            when (worldPackUid to worldPackVersion) {
                "A" to "1" -> Probe.a1++
                "A" to "2" -> Probe.a2++
                "B" to "1" -> Probe.b1++
            }
            Probe.seen += request.worldPack
            return WorldRuleDecision.Allowed.create("P19-AUTH-CONTENT")
        }
    }
}
