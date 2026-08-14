package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRuleProviderPhase19AuthorityFreshnessTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val campaignUid = ActiveCampaignRef.DEFAULT_CAMPAIGN_ID
    private val a1 = WorldPackRuleBinding("WORLD-A", "1")
    private val b1 = WorldPackRuleBinding("WORLD-B", "1")

    @Before
    fun reset() {
        Probe.a = 0
        Probe.b = 0
    }

    @Test
    fun P19_AUTH_FRESH_01_staleAAfterAtoBIsRejected() {
        val selection = canonicalSelection()
        selection.setActiveWorldPack("A.worldpack")
        val engine = liveEngine(selection)
        selection.setActiveWorldPack("B.worldpack")

        assertStructural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine.resolve(train("01"), context(WorldRuleMode.Bound(a1)))
        }
    }

    @Test
    fun P19_AUTH_FRESH_02_staleAProviderInvocationAfterAtoBIsZero() {
        val selection = canonicalSelection()
        selection.setActiveWorldPack("A.worldpack")
        val engine = liveEngine(selection)
        selection.setActiveWorldPack("B.worldpack")

        runCatching { engine.resolve(train("02"), context(WorldRuleMode.Bound(a1))) }
        assertEquals(0, Probe.a)
    }

    @Test
    fun P19_AUTH_FRESH_03_currentBoundBAfterAtoBIsAccepted() {
        val selection = canonicalSelection()
        selection.setActiveWorldPack("A.worldpack")
        val engine = liveEngine(selection)
        selection.setActiveWorldPack("B.worldpack")

        val outcome = engine.resolve(train("03"), context(WorldRuleMode.Bound(b1)))
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
        assertTrue(Probe.b > 0)
        assertEquals(0, Probe.a)
    }

    @Test
    fun P19_AUTH_FRESH_04_staleVersionIsRejectedBeforeProvider() {
        val selection = canonicalSelection(aVersion = "2")
        selection.setActiveWorldPack("A.worldpack")
        val engine = liveEngine(selection)

        assertStructural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine.resolve(train("04"), context(WorldRuleMode.Bound(a1)))
        }
        assertEquals(0, Probe.a)
    }

    @Test
    fun P19_AUTH_FRESH_05_missingAuthorityFailsClosed() {
        val engine = engineWithResolver(WorldPackAuthorityResolver { null })

        assertStructural("WORLD_RULE_AUTHORITY_MISSING") {
            engine.resolve(train("05"), context(WorldRuleMode.Bound(a1)))
        }
        assertEquals(0, Probe.a)
    }

    @Test
    fun P19_AUTH_FRESH_06_AtoBtoAUsesCurrentCanonicalStateOnSameEngine() {
        val selection = canonicalSelection()
        selection.setActiveWorldPack("A.worldpack")
        val engine = liveEngine(selection)

        assertTrue(engine.resolve(train("06A"), context(WorldRuleMode.Bound(a1))) is PlayerResolutionOutcome.Resolved)
        selection.setActiveWorldPack("B.worldpack")
        assertTrue(engine.resolve(train("06B"), context(WorldRuleMode.Bound(b1))) is PlayerResolutionOutcome.Resolved)
        selection.setActiveWorldPack("A.worldpack")
        assertTrue(engine.resolve(train("06C"), context(WorldRuleMode.Bound(a1))) is PlayerResolutionOutcome.Resolved)
        assertTrue(Probe.a > 0)
        assertTrue(Probe.b > 0)
    }

    @Test
    fun P19_AUTH_FRESH_07_authorityReadFailureFailsClosedBeforeProvider() {
        val engine = engineWithResolver(WorldPackAuthorityResolver {
            throw IllegalStateException("simulated canonical authority read failure")
        })

        assertStructural("WORLD_RULE_AUTHORITY_READ_FAILED") {
            engine.resolve(train("07"), context(WorldRuleMode.Bound(a1)))
        }
        assertEquals(0, Probe.a)
        assertEquals(0, Probe.b)
    }

    @Test
    fun P19_AUTH_FRESH_08_resolutionDoesNotMutateCanonicalAuthority() {
        val selection = canonicalSelection()
        selection.setActiveWorldPack("B.worldpack")
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("rpgos_selection", android.content.Context.MODE_PRIVATE)
        val beforePrefs = LinkedHashMap(prefs.all)
        val root = File(app.filesDir, "rpgos/worldpacks/B.worldpack")
        val manifest = File(root, "worldpack.json")
        val db = File(root, "world.db")
        val beforeManifest = manifest.readBytes()
        val beforeDb = db.readBytes()
        val engine = liveEngine(selection)

        assertTrue(engine.resolve(train("08"), context(WorldRuleMode.Bound(b1))) is PlayerResolutionOutcome.Resolved)

        assertEquals(beforePrefs, LinkedHashMap(prefs.all))
        assertTrue(beforeManifest.contentEquals(manifest.readBytes()))
        assertTrue(beforeDb.contentEquals(db.readBytes()))
        assertEquals("B.worldpack", selection.activeWorldPackDirName())
        assertEquals(b1, selection.activeWorldRuleMode().binding)
    }

    private fun canonicalSelection(aVersion: String = "1"): CampaignSelectionManager {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        root.deleteRecursively()
        File(root, "saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").mkdirs()
        createWorldPack(File(root, "worldpacks/A.worldpack"), WorldPackRuleBinding("WORLD-A", aVersion))
        createWorldPack(File(root, "worldpacks/B.worldpack"), b1)
        return CampaignSelectionManager(app)
    }

    private fun createWorldPack(dir: File, binding: WorldPackRuleBinding) {
        dir.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(File(dir, "world.db"), null).close()
        File(dir, "worldpack.json").writeText(
            """{"id":"${binding.worldPackUid}","version":"${binding.worldPackVersion}","engine_api":"1"}"""
        )
    }

    private fun liveEngine(selection: CampaignSelectionManager): PlayerDomainEngine =
        engineWithResolver(selection.activeWorldPackAuthoritySnapshot())

    private fun engineWithResolver(resolver: WorldPackAuthorityResolver): PlayerDomainEngine =
        PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(
                listOf(ProviderA1(), ProviderB1())
            ),
            worldPackAuthority = resolver
        )

    private fun train(suffix: String) = PlayerCommand(
        commandUid = "CMD-P19-AUTH-FRESH-$suffix",
        campaignUid = campaignUid,
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-AUTH-FRESH")
    )

    private fun context(mode: WorldRuleMode) = PlayerResolutionContext.create(
        campaignUid,
        actor,
        setOf(
            CampaignScopedDomainRef(campaignUid, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaignUid, DomainRef("STAT", "STR"))
        ),
        worldRuleMode = mode
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
        "P19-AUTH-FRESH-COMPONENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-AUTH-FRESH-CHANGE-${command.commandUid}",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }

    private object Probe {
        var a = 0
        var b = 0
    }

    private class ProviderA1 : WorldRuleProvider("P19-AUTH-FRESH-A1", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.a++
            return WorldRuleDecision.Allowed.create("P19-AUTH-FRESH-A1")
        }
    }

    private class ProviderB1 : WorldRuleProvider("P19-AUTH-FRESH-B1", "1", "WORLD-B", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.b++
            return WorldRuleDecision.Allowed.create("P19-AUTH-FRESH-B1")
        }
    }
}
