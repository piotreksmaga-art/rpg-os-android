package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class Phase19CanonicalAuthorityTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val canonical = WorldPackRuleBinding("WORLD-A", "1")

    @Test fun P19_AUTH_01_validCanonicalAuthorityAllowsProvider() {
        val calls = AtomicInteger()
        val result = engine(authority = { canonical }, provider = CountingProvider(canonical, calls))
            .resolve(command("AUTH01"), context("C1", canonical))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(2, calls.get())
    }

    @Test fun P19_AUTH_02_missingAuthorityFailsClosedBeforeProvider() {
        val calls = AtomicInteger()
        structural("WORLD_RULE_AUTHORITY_MISSING") {
            engine(authority = { null }, provider = CountingProvider(canonical, calls))
                .resolve(command("AUTH02"), context("C1", canonical))
        }
        assertEquals(0, calls.get())
    }

    @Test fun P19_AUTH_03_wrongCampaignFailsClosed() {
        val calls = AtomicInteger()
        val resolver = WorldPackAuthorityResolver { campaignUid ->
            canonical.takeIf { campaignUid == "C1" }
        }
        structural("WORLD_RULE_AUTHORITY_MISSING") {
            engine(resolver, CountingProvider(canonical, calls))
                .resolve(command("AUTH03", campaign = "C2"), context("C2", canonical))
        }
        assertEquals(0, calls.get())
    }

    @Test fun P19_AUTH_04_wrongWorldPackUidFailsClosed() {
        val calls = AtomicInteger()
        val wrong = WorldPackRuleBinding("WORLD-B", "1")
        structural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(authority = { canonical }, provider = CountingProvider(canonical, calls))
                .resolve(command("AUTH04"), context("C1", wrong))
        }
        assertEquals(0, calls.get())
    }

    @Test fun P19_AUTH_05_wrongWorldPackVersionFailsClosed() {
        val calls = AtomicInteger()
        val wrong = WorldPackRuleBinding("WORLD-A", "2")
        structural("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(authority = { canonical }, provider = CountingProvider(canonical, calls))
                .resolve(command("AUTH05"), context("C1", wrong))
        }
        assertEquals(0, calls.get())
    }

    @Test fun P19_AUTH_06_authorityReadFailureFailsClosed() {
        val calls = AtomicInteger()
        structural("WORLD_RULE_AUTHORITY_READ_FAILED") {
            engine(
                authority = { throw IllegalStateException("authority unavailable") },
                provider = CountingProvider(canonical, calls)
            ).resolve(command("AUTH06"), context("C1", canonical))
        }
        assertEquals(0, calls.get())
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

    private class CountingProvider(
        binding: WorldPackRuleBinding,
        private val calls: AtomicInteger
    ) : WorldRuleProvider("P19-CANONICAL-PROVIDER", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            calls.incrementAndGet()
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
