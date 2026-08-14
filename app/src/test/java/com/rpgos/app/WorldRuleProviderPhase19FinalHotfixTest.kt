package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class WorldRuleProviderPhase19FinalHotfixTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val a = WorldPackRuleBinding("WORLD-A", "1")
    private val b = WorldPackRuleBinding("WORLD-B", "1")

    @Test fun p19Auth01_activeAWithCanonicalBoundAExecutesRules() {
        val result = engine(listOf(ProbeProvider(a, allow = true)), authority("C1", a))
            .resolve(train("C1"), context("C1", a))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        assertEquals(2, (result as PlayerResolutionOutcome.Resolved).evidence.worldRuleDecisions.size)
    }

    @Test fun p19Auth02_activeAWithSuppliedBoundBRejectedBeforeProviderB() {
        InvocationProbe.reset()
        fails("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(listOf(ProbeProvider(a, true), ProbeProvider(b, true)), authority("C1", a))
                .resolve(train("C1"), context("C1", b))
        }
        assertEquals(0, InvocationProbe.worldBCalls)
    }

    @Test fun p19Auth03_activeASameUidWrongVersionRejected() {
        val wrong = WorldPackRuleBinding("WORLD-A", "2")
        fails("WORLD_RULE_BINDING_AUTHORITY_MISMATCH") {
            engine(listOf(ProbeProvider(a, true)), authority("C1", a)).resolve(train("C1"), context("C1", wrong))
        }
    }

    @Test fun p19Auth04_activeAMissingProviderFailsClosed() {
        fails("WORLD_RULE_PROVIDER_MISSING") {
            engine(emptyList(), authority("C1", a)).resolve(train("C1"), context("C1", a))
        }
    }

    @Test fun p19Auth05_permissiveProviderBCannotBypassA() = p19Auth02_activeAWithSuppliedBoundBRejectedBeforeProviderB()

    @Test fun p19Auth06_providerBInvocationCountZeroDuringSubstitution() = p19Auth02_activeAWithSuppliedBoundBRejectedBeforeProviderB()

    @Test fun p19Auth07_campaignC1AuthorityCannotBeReusedForC2() {
        fails("WORLD_RULE_AUTHORITY_MISSING") {
            engine(listOf(ProbeProvider(a, true)), authority("C1", a)).resolve(train("C2"), context("C2", a))
        }
    }

    @Test fun p19Auth08_genericCoreModeRequiresExplicitInternalPathAndNoBoundAuthority() {
        val result = engine(emptyList(), WorldPackAuthoritySnapshot.empty())
            .resolve(train("C1"), unboundContext("C1"))
        assertTrue(result is PlayerResolutionOutcome.Resolved)
        fails("WORLD_RULE_GENERIC_MODE_AUTHORITY_MISMATCH") {
            engine(emptyList(), authority("C1", a)).resolve(train("C1"), unboundContext("C1"))
        }
    }

    @Test fun p19Auth09_unknownReferenceStillRejectsBeforeProvider() {
        InvocationProbe.reset()
        val cmd = train("C1").copy(payload = TrainCommandPayload(DomainRef("STAT", "GHOST"), 10L, "METHOD"))
        val result = engine(listOf(ProbeProvider(a, true)), authority("C1", a)).resolve(cmd, context("C1", a))
            as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.UNKNOWN_REFERENCE, result.rejection.reason)
        assertEquals(0, InvocationProbe.worldACalls)
    }

    @Test fun p19Auth10_wrongCampaignReferenceStillRejectsBeforeProvider() {
        InvocationProbe.reset()
        val cmd = train("C1").copy(payload = TrainCommandPayload(DomainRef("STAT", "GHOST"), 10L, "METHOD"))
        val ctx = context("C1", a, setOf(CampaignScopedDomainRef("C2", DomainRef("STAT", "GHOST"))))
        val result = engine(listOf(ProbeProvider(a, true)), authority("C1", a)).resolve(cmd, ctx)
            as PlayerResolutionOutcome.Rejected
        assertEquals(PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE, result.rejection.reason)
        assertEquals(0, InvocationProbe.worldACalls)
    }

    @Test fun p19Enum01_ordinaryStatelessEnumAccepted() {
        WorldRuleProviderRegistry.of(listOf(EnumProvider(PlainMode.SAFE)))
    }

    @Test fun p19Enum02_baseEnumMutableFieldRejected() = fails("MUTABLE_WORLD_RULE_PROVIDER_STATE") {
        WorldRuleProviderRegistry.of(listOf(BaseMutableEnumProvider(BaseMutableMode.INSTANCE)))
    }

    @Test fun p19Enum03_constantSpecificEnumMutableFieldRejected() = fails("MUTABLE_WORLD_RULE_PROVIDER_STATE") {
        WorldRuleProviderRegistry.of(listOf(ConstantEnumProvider(ConstantMode.EVIL)))
    }

    @Test fun p19Enum04_constantSpecificNestedMutableObjectRejected() = fails("UNSAFE_WORLD_RULE_PROVIDER_STATE") {
        WorldRuleProviderRegistry.of(listOf(NestedEnumProvider(NestedMode.EVIL)))
    }

    @Test fun p19Enum05_constantSpecificWriterLikeCapabilityRejected() = fails("UNSAFE_WORLD_RULE_PROVIDER_STATE") {
        WorldRuleProviderRegistry.of(listOf(WriterEnumProvider(WriterMode.EVIL)))
    }

    @Test fun p19Enum06_identicalRequestsCannotVaryThroughConstantSpecificStateBecauseRegistrationFails() =
        p19Enum03_constantSpecificEnumMutableFieldRejected()

    @Test fun p19Enum07_existingProviderStateAttacksRemainRejected() {
        WorldRuleProviderPhase19HardeningTest().p19H2_04_mutableCollectionAndInheritedUnsafeStateRemainRejected()
    }

    @Test fun p19Enum08_safeScalarStringAndEnumConfigurationAccepted() {
        WorldRuleProviderRegistry.of(listOf(SafeConfigProvider("CONFIG", 7L, PlainMode.SAFE)))
    }

    private fun engine(providers: List<WorldRuleProvider>, authority: WorldPackAuthoritySnapshot) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(providers),
        worldPackAuthority = authority
    )

    private fun authority(campaign: String, binding: WorldPackRuleBinding) =
        WorldPackAuthoritySnapshot.single(campaign, binding)

    private fun train(campaign: String) = PlayerCommand(
        commandUid = "CMD-AUTH-$campaign", campaignUid = campaign, actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("TEST")
    )

    private fun context(
        campaign: String,
        binding: WorldPackRuleBinding,
        extra: Set<CampaignScopedDomainRef> = emptySet()
    ) = PlayerResolutionContext.create(
        campaign, actor,
        setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("STAT", "STR"))
        ) + extra,
        worldRuleMode = WorldRuleMode.Bound(binding)
    )

    private fun unboundContext(campaign: String) = PlayerResolutionContext.createUnboundGeneric(
        campaign, actor,
        setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("STAT", "STR"))
        )
    )

    private fun fails(code: String, block: () -> Unit) {
        try {
            block()
            fail("expected $code")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertEquals(code, e.code)
        }
    }

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN, TrainCommandPayload::class, "AUTH-COMPONENT", "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext) =
            PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(
                    PlayerDomainChange.create(
                        "CH-AUTH", PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                ))
            )
    }

    private object InvocationProbe {
        var worldACalls = 0
        var worldBCalls = 0
        fun reset() { worldACalls = 0; worldBCalls = 0 }
    }

    private class ProbeProvider(
        private val binding: WorldPackRuleBinding,
        private val allow: Boolean
    ) : WorldRuleProvider("PROBE-${binding.worldPackUid}", "1", binding.worldPackUid, binding.worldPackVersion) {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            if (binding.worldPackUid == "WORLD-A") InvocationProbe.worldACalls++ else InvocationProbe.worldBCalls++
            return if (allow) WorldRuleDecision.Allowed.create("RULE")
            else WorldRuleDecision.Rejected.create("RULE", "DENY")
        }
    }

    private enum class PlainMode { SAFE }
    private class EnumProvider(private val mode: PlainMode) : WorldRuleProvider("ENUM-SAFE", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("RULE-${mode.name}")
    }

    private enum class BaseMutableMode { INSTANCE; var counter = 0 }
    private class BaseMutableEnumProvider(private val mode: BaseMutableMode) : WorldRuleProvider("ENUM-BASE-MUT", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("RULE-${mode.counter}")
    }

    private enum class ConstantMode {
        SAFE,
        EVIL { var counter = 0 }
    }
    private class ConstantEnumProvider(private val mode: ConstantMode) : WorldRuleProvider("ENUM-CONSTANT-MUT", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("RULE-${mode.name}")
    }

    private enum class NestedMode {
        SAFE,
        EVIL { val buffer = StringBuilder() }
    }
    private class NestedEnumProvider(private val mode: NestedMode) : WorldRuleProvider("ENUM-NESTED", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("RULE-${mode.name}")
    }

    private class TestWriter
    private enum class WriterMode {
        SAFE,
        EVIL { val writer = TestWriter() }
    }
    private class WriterEnumProvider(private val mode: WriterMode) : WorldRuleProvider("ENUM-WRITER", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("RULE-${mode.name}")
    }

    private class SafeConfigProvider(
        private val config: String,
        private val number: Long,
        private val mode: PlainMode
    ) : WorldRuleProvider("ENUM-CONFIG", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest) = WorldRuleDecision.Allowed.create("RULE-$config-$number-${mode.name}")
    }
}
