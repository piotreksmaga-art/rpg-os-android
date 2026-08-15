package com.rpgos.app

import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorldRuleProviderPhase19AtomicAuthorityTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val c1 = "C1"
    private val c2 = "C2"
    private val c1Dir = "C1.campaign"
    private val c2Dir = "C2.campaign"
    private val a1 = WorldPackRuleBinding("WORLD-A", "1")
    private val a2 = WorldPackRuleBinding("WORLD-A", "2")
    private val b1 = WorldPackRuleBinding("WORLD-B", "1")

    @Before
    fun reset() {
        Probe.a = 0
        Probe.b = 0
    }

    @Test
    fun P19_AUTH_ATOMIC_01_C1AUnchangedIsValid() {
        val selection = canonicalSelection()
        select(selection, c1Dir, "A1.worldpack")
        assertEquals(CurrentWorldPackAuthority(c1, a1), selection.currentWorldPackAuthority())
        assertTrue(engine(selection).resolve(train("01", c1), context(c1, a1)) is PlayerResolutionOutcome.Resolved)
    }

    @Test
    fun P19_AUTH_ATOMIC_02_controlledInterleavingCannotProduceC1PlusBHybrid() {
        val fixture = canonicalSelection()
        select(fixture, c1Dir, "A1.worldpack")
        val app = RuntimeEnvironment.getApplication()
        val realPrefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        val root = File(app.filesDir, "rpgos")
        var switched = false
        val snapshotPrefs = object : SharedPreferences by realPrefs {
            override fun getAll(): MutableMap<String, *> {
                val captured = LinkedHashMap(realPrefs.all)
                if (!switched) {
                    switched = true
                    realPrefs.edit()
                        .putString("active_campaign", c2Dir)
                        .putString("active_campaign_id", c2)
                        .putString("active_worldpack", "B.worldpack")
                        .commit()
                }
                return captured
            }
        }
        val source = CanonicalSelectionWorldPackAuthoritySource(
            snapshotPrefs,
            File(root, "saves"),
            File(root, "worldpacks")
        )

        val duringSwitch = source.currentAuthority()
        assertEquals(CurrentWorldPackAuthority(c1, a1), duringSwitch)
        assertEquals(c2Dir, realPrefs.getString("active_campaign", null))
        assertEquals("B.worldpack", realPrefs.getString("active_worldpack", null))
        assertFalse(duringSwitch.campaignUid == c1 && duringSwitch.binding == b1)
    }

    @Test
    fun P19_AUTH_ATOMIC_03_afterCompletedSwitchNewResolutionSeesC2B() {
        val selection = canonicalSelection()
        select(selection, c1Dir, "A1.worldpack")
        val e = engine(selection)
        select(selection, c2Dir, "B.worldpack")
        assertEquals(CurrentWorldPackAuthority(c2, b1), selection.currentWorldPackAuthority())
        assertTrue(e.resolve(train("03", c2), context(c2, b1)) is PlayerResolutionOutcome.Resolved)
    }

    @Test
    fun P19_AUTH_ATOMIC_04_sameLongLivedEngineAcrossC1AToC2B() {
        val selection = canonicalSelection()
        select(selection, c1Dir, "A1.worldpack")
        val e = engine(selection)
        assertTrue(e.resolve(train("04A", c1), context(c1, a1)) is PlayerResolutionOutcome.Resolved)
        select(selection, c2Dir, "B.worldpack")
        assertTrue(e.resolve(train("04B", c2), context(c2, b1)) is PlayerResolutionOutcome.Resolved)
    }

    @Test
    fun P19_AUTH_ATOMIC_05_Av1ToAv2UsesOneVersionSnapshot() {
        val selection = canonicalSelection()
        select(selection, c1Dir, "A1.worldpack")
        assertEquals(CurrentWorldPackAuthority(c1, a1), selection.currentWorldPackAuthority())
        selection.setActiveWorldPack("A2.worldpack")
        assertEquals(CurrentWorldPackAuthority(c1, a2), selection.currentWorldPackAuthority())
    }

    @Test
    fun P19_AUTH_ATOMIC_06_C1AToC2BToC1AHasNoHybrid() {
        val selection = canonicalSelection()
        select(selection, c1Dir, "A1.worldpack")
        assertEquals(CurrentWorldPackAuthority(c1, a1), selection.currentWorldPackAuthority())
        select(selection, c2Dir, "B.worldpack")
        assertEquals(CurrentWorldPackAuthority(c2, b1), selection.currentWorldPackAuthority())
        select(selection, c1Dir, "A1.worldpack")
        assertEquals(CurrentWorldPackAuthority(c1, a1), selection.currentWorldPackAuthority())
    }

    @Test
    fun P19_AUTH_ATOMIC_07_authorityReadFailureFailsClosed() {
        val e = engineWithResolver(WorldPackAuthorityResolver { throw IllegalStateException("read failure") })
        assertStructural("WORLD_RULE_AUTHORITY_READ_FAILED") {
            e.resolve(train("07", c1), context(c1, a1))
        }
    }

    @Test
    fun P19_AUTH_ATOMIC_08_invalidCrossCampaignAuthorityInvokesNoProvider() {
        val selection = canonicalSelection()
        select(selection, c2Dir, "B.worldpack")
        val e = engine(selection)
        assertStructural("WORLD_RULE_AUTHORITY_MISSING") {
            e.resolve(train("08", c1), context(c1, b1))
        }
        assertEquals(0, Probe.a)
        assertEquals(0, Probe.b)
    }

    @Test
    fun P19_AUTH_ATOMIC_09_singleResolutionReadsAuthorityOnceAndUsesOneBinding() {
        var authorityReads = 0
        val resolver = WorldPackAuthorityResolver {
            authorityReads++
            if (authorityReads == 1) a1 else b1
        }
        val e = engineWithResolver(resolver)
        val outcome = e.resolve(train("09", c1), context(c1, a1))
        assertTrue(outcome is PlayerResolutionOutcome.Resolved)
        assertEquals(1, authorityReads)
        val resolved = outcome as PlayerResolutionOutcome.Resolved
        assertEquals(2, resolved.evidence.worldRuleDecisions.size)
        assertTrue(resolved.evidence.worldRuleDecisions.all {
            it.worldPackUid == a1.worldPackUid && it.worldPackVersion == a1.worldPackVersion
        })
    }

    @Test
    fun P19_AUTH_ATOMIC_10_resolverExposesNoCampaignMutationCapability() {
        val selection = canonicalSelection()
        val resolver = selection.activeWorldPackAuthorityResolver()
        assertTrue(resolver is CurrentSelectionWorldPackAuthorityResolver)
        assertFalse(resolver.javaClass.declaredFields.any {
            CampaignSelectionManager::class.java.isAssignableFrom(it.type)
        })
        val methods = WorldPackAuthoritySource::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("currentAuthority"), methods)
        assertFalse(methods.any { it.startsWith("setActive") })
    }

    private fun canonicalSelection(): CampaignSelectionManager {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        root.deleteRecursively()
        createCampaign(File(root, "saves/$c1Dir"), c1)
        createCampaign(File(root, "saves/$c2Dir"), c2)
        createWorldPack(File(root, "worldpacks/A1.worldpack"), a1)
        createWorldPack(File(root, "worldpacks/A2.worldpack"), a2)
        createWorldPack(File(root, "worldpacks/B.worldpack"), b1)
        val prefs = app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return CampaignSelectionManager(app)
    }

    private fun select(selection: CampaignSelectionManager, campaignDir: String, worldPackDir: String) {
        selection.setActiveCampaign(campaignDir)
        selection.setActiveWorldPack(worldPackDir)
        val app = RuntimeEnvironment.getApplication()
        // apply() is synchronous in memory, but commit a no-op barrier for deterministic Robolectric tests.
        app.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().commit()
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

    private fun engine(selection: CampaignSelectionManager): PlayerDomainEngine =
        engineWithResolver(selection.activeWorldPackAuthorityResolver())

    private fun engineWithResolver(resolver: WorldPackAuthorityResolver): PlayerDomainEngine =
        PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(ProviderA(), ProviderB())),
            worldPackAuthority = resolver
        )

    private fun train(suffix: String, campaignUid: String) = PlayerCommand(
        commandUid = "CMD-P19-AUTH-ATOMIC-$suffix",
        campaignUid = campaignUid,
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-AUTH-ATOMIC")
    )

    private fun context(campaignUid: String, binding: WorldPackRuleBinding) = PlayerResolutionContext.create(
        campaignUid,
        actor,
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
        "P19-AUTH-ATOMIC-COMPONENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-AUTH-ATOMIC-CHANGE-${command.commandUid}",
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

    private class ProviderA : WorldRuleProvider("P19-AUTH-ATOMIC-A", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.a++
            return WorldRuleDecision.Allowed.create("P19-AUTH-ATOMIC-A")
        }
    }

    private class ProviderB : WorldRuleProvider("P19-AUTH-ATOMIC-B", "1", "WORLD-B", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.b++
            return WorldRuleDecision.Allowed.create("P19-AUTH-ATOMIC-B")
        }
    }
}
