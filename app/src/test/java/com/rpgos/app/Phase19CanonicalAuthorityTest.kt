package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class Phase19CanonicalAuthorityTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val canonical = WorldPackRuleBinding("WORLD-A", "1")

    @Before fun resetProbe() {
        Probe.calls = 0
    }

    @Test fun P19_AUTH_01_validCanonicalAuthorityAllowsProvider() {
        val result = engine(authority = { canonical }, provider = CountingProvider(canonical))
            .resolve(command("AUTH01"), context("C1", canonical))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(2, Probe.calls)
    }

    @Test fun P19_AUTH_02_missingAuthorityFailsClosedBeforeProvider() {
        structural("WORLD_RULE_AUTHORITY_MISSING") {
            engine(authority = { null }, provider = CountingProvider(canonical))
                .resolve(command("AUTH02"), context("C1", canonical))
        }
        assertEquals(0, Probe.calls)
    }

    @Test fun P19_AUTH_03_wrongCampaignFailsClosed() {
        val resolver = WorldPackAuthorityResolver { campaignUid ->
            canonical.takeIf { campaignUid == "C1" }
        }
        structural("WORLD_RULE_AUTHORITY_MISSING") {
            engine(resolver, CountingProvider(canonical))
                .resolve(command("AUTH03", campaign = "C2"), context("C2", canonical))
        }
        assertEquals(0, Probe.calls)
    }

    @Test fun P19_AUTH_04_wrongWorldPackUidFailsClosed() {
        val wrong = WorldPackRuleBinding("WORLD-B", "1")
        structural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(authority = { canonical }, provider = CountingProvider(canonical))
                .resolve(command("AUTH04"), context("C1", wrong))
        }
        assertEquals(0, Probe.calls)
    }

    @Test fun P19_AUTH_05_wrongWorldPackVersionFailsClosed() {
        val wrong = WorldPackRuleBinding("WORLD-A", "2")
        structural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(authority = { canonical }, provider = CountingProvider(canonical))
                .resolve(command("AUTH05"), context("C1", wrong))
        }
        assertEquals(0, Probe.calls)
    }

    @Test fun P19_AUTH_06_authorityReadFailureFailsClosed() {
        structural("WORLD_RULE_AUTHORITY_READ_FAILED") {
            engine(
                authority = { throw IllegalStateException("authority unavailable") },
                provider = CountingProvider(canonical)
            ).resolve(command("AUTH06"), context("C1", canonical))
        }
        assertEquals(0, Probe.calls)
    }

    private fun engine(
        authority: () -> WorldPackRuleBinding?,
        provider: WorldRuleProvider
    ): PlayerDomainEngine = engine(WorldPackAuthorityResolver { authority() }, provider)

    private fun engine(
        authority: WorldPackAuthorityResolver,
        provider: WorldRuleProvider
    ): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = authority
    )

    private fun command(suffix: String, campaign: String = "C1") = PlayerCommand(
        commandUid = "CMD-P19-$suffix",
        campaignUid = campaign,
        actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("P19-CANONICAL")
    )

    private fun context(campaign: String, binding: WorldPackRuleBinding) = PlayerResolutionContext.create(
        campaign,
        actor,
        setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("STAT", "STR"))
        ),
        worldRuleMode = WorldRuleMode.Bound(binding)
    )

    private fun structural(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected $code")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private object Probe {
        var calls: Int = 0
    }

    private class CountingProvider(
        binding: WorldPackRuleBinding
    ) : WorldRuleProvider("P19-CANONICAL-PROVIDER", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            Probe.calls++
            return WorldRuleDecision.Allowed.create("P19-CANONICAL-RULE")
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN,
        TrainCommandPayload::class,
        "P19-CANONICAL-TRAIN",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TrainCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome = PlayerResolutionComponentOutcome.Resolved(
            PlayerResolutionDraft.create(
                changes = listOf(
                    PlayerDomainChange.create(
                        "P19-CANONICAL-CHANGE",
                        PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                )
            )
        )
    }
}
