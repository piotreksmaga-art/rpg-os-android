package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
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
class Phase19AuthorityFreshnessReproductionTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val campaignUid = ActiveCampaignRef.DEFAULT_CAMPAIGN_ID
    private val bindingA = WorldPackRuleBinding("WORLD-A", "1")
    private val bindingB = WorldPackRuleBinding("WORLD-B", "1")

    @Before
    fun resetCounters() {
        ProviderA.invocations = 0
    }

    @Test
    fun P19_C2_AUTH_STALE_01_staleAAfterCanonicalAtoBMustFailClosedBeforeProviderInvocation() {
        val app = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "rpgos")
        File(root, "saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").mkdirs()
        createWorldPack(File(root, "worldpacks/A.worldpack"), bindingA)
        createWorldPack(File(root, "worldpacks/B.worldpack"), bindingB)

        val selection = CampaignSelectionManager(app)
        selection.setActiveWorldPack("A.worldpack")
        val capturedAuthority = selection.activeWorldPackAuthoritySnapshot()

        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(ProviderA(), ProviderB())),
            worldPackAuthority = capturedAuthority
        )

        selection.setActiveWorldPack("B.worldpack")
        assertEquals(bindingB, selection.activeWorldRuleMode().binding)

        try {
            engine.resolve(train(), context(WorldRuleMode.Bound(bindingA)))
            fail("stale A must be rejected after canonical selection changes A -> B")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals("WORLD_RULE_BINDING_AUTHORITY_MISMATCH", e.code)
        }

        assertEquals("provider A must not run after A -> B", 0, ProviderA.invocations)
    }

    private fun createWorldPack(dir: File, binding: WorldPackRuleBinding) {
        dir.mkdirs()
        val db = File(dir, "world.db")
        SQLiteDatabase.openOrCreateDatabase(db, null).close()
        File(dir, "worldpack.json").writeText(
            """{"id":"${binding.worldPackUid}","version":"${binding.worldPackVersion}","engine_api":"1"}"""
        )
    }

    private fun train() = PlayerCommand(
        commandUid = "CMD-P19-AUTH-STALE-REPRO",
        campaignUid = campaignUid,
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-AUTH-STALE-REPRO")
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

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-AUTH-STALE-REPRO-COMPONENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-AUTH-STALE-CHANGE",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }

    private class ProviderA : WorldRuleProvider("P19-PROVIDER-A", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            invocations++
            return WorldRuleDecision.Allowed.create("P19-AUTH-A")
        }

        companion object {
            var invocations: Int = 0
        }
    }

    private class ProviderB : WorldRuleProvider("P19-PROVIDER-B", "1", "WORLD-B", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision =
            WorldRuleDecision.Allowed.create("P19-AUTH-B")
    }
}
