package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class WorldRuleProviderPhase19BlockerReproductionTest {
    private val actor = CommandActorRef("PLAYER", "P1")
    private val binding = WorldPackRuleBinding("TEST-WORLD", "1")

    @Test fun reproduce_nullBindingBypass() {
        val provider = RejectingProvider()
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
            worldPackAuthority = WorldPackAuthoritySnapshot.single("C1", binding)
        )
        val result = engine.resolve(train(), context(WorldRuleMode.Bound(binding)))
        assertTrue("bound-world omission must not bypass world rules", result is PlayerResolutionOutcome.Rejected)
    }

    @Test fun reproduce_mutableEnumProviderStateBypass() {
        try {
            WorldRuleProviderRegistry.of(listOf(MutableEnumProvider()))
            fail("mutable enum retained state must be rejected")
        } catch (e: PlayerDomainEngineStructuralException) {
            assertTrue(e.code == "MUTABLE_WORLD_RULE_PROVIDER_STATE" || e.code == "UNSAFE_WORLD_RULE_PROVIDER_STATE")
        }
    }

    @Test fun reproduce_nullableSentinelCollision() {
        val a = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(
            warnings = listOf(ChangeSetWarning("WARN", null, null))
        ))
        val b = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(
            warnings = listOf(ChangeSetWarning("WARN", "RPGOS-NULL", null))
        ))
        assertNotEquals(a.deterministicFingerprint(), b.deterministicFingerprint())
    }

    @Test fun reproduce_allowRejectDecisionCollision() {
        val provider = AllowingProvider()
        val command = train()
        val registry = PlayerCommandKindRegistry.core()
        val fp = registry.fingerprint(command)
        val ctx = context(WorldRuleMode.Bound(binding))
        val request = WorldRuleRequest.commandPrecheck(binding, "C1", actor, command, fp, ctx.deterministicFingerprint())
        val allowed = WorldRuleDecisionRecord.create(provider, request, WorldRuleDecision.Allowed.create("RULE"))
        val rejected = WorldRuleDecisionRecord.create(
            provider,
            request,
            WorldRuleDecision.Rejected.create("RULE", "RPGOS-WORLD-RULE:ALLOW")
        )
        assertNotEquals(allowed.decisionFingerprint, rejected.decisionFingerprint)
    }

    @Test fun reproduce_unframedEffectSnapshotCollision() {
        val equipment = PlayerDomainChange.create(
            "E-CHANGE", PlayerChangeKinds.EQUIPMENT,
            EquipmentChange(DomainRef("PLAYER", "P1"), "SLOT", EquipmentOperation.UNEQUIP, null)
        )
        val evidence = listOf(
            DomainRef("E-CHANGE", PlayerChangeKinds.EQUIPMENT),
            DomainRef("RPGOS-NULL", "PLAYER"),
            DomainRef("P1", "SLOT"),
            DomainRef(EquipmentOperation.UNEQUIP.name, "RPGOS-NULL")
        )
        fun project(refs: List<DomainRef>) = PlayerDomainChange.create(
            "P-CHANGE", PlayerChangeKinds.DEVELOPMENT_PROJECT,
            DevelopmentProjectChange.create("PROJECT", "WORK", ProjectProgressDelta.of(1L), refs)
        )
        val a = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes = listOf(project(evidence))))
        val b = WorldRuleEffectSnapshot.create(PlayerResolutionDraft.create(changes = listOf(project(emptyList()), equipment)))
        assertNotEquals(a.deterministicFingerprint(), b.deterministicFingerprint())
    }

    @Test fun reproduce_unframedContextFingerprintCollision() {
        val a = PlayerResolutionContext.create(
            "C1", actor,
            setOf(
                CampaignScopedDomainRef("A", DomainRef("B", "C")),
                CampaignScopedDomainRef("D", DomainRef("E", "F"))
            ),
            emptyMap(), ResolutionEntropyEvidence.none(), WorldRuleMode.Bound(binding)
        )
        val b = PlayerResolutionContext.create(
            "C1", actor, emptySet(),
            linkedMapOf("A" to "B", "C" to "D", "E" to "F"),
            ResolutionEntropyEvidence.none(), WorldRuleMode.Bound(binding)
        )
        assertNotEquals(a.deterministicFingerprint(), b.deterministicFingerprint())
    }

    private fun train() = PlayerCommand(
        commandUid = "CMD-P19-REPRO", campaignUid = "C1", actor = actor,
        commandKindUid = PlayerCommandKinds.TRAIN,
        payload = TrainCommandPayload(DomainRef("STAT", "STR"), 10L, "METHOD"),
        provenance = CommandProvenance("TEST")
    )

    private fun context(worldRuleMode: WorldRuleMode) = PlayerResolutionContext.create(
        "C1", actor,
        setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef("C1", DomainRef("STAT", "STR"))
        ),
        worldRuleMode = worldRuleMode
    )

    private class TrainComponent : PlayerResolutionComponent<TrainCommandPayload>(
        PlayerCommandKinds.TRAIN, TrainCommandPayload::class, "REPRO-COMPONENT", "1"
    ) {
        override fun resolve(command: PlayerCommand<TrainCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome =
            PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(changes = listOf(
                    PlayerDomainChange.create(
                        "CH-REPRO", PlayerChangeKinds.STAT,
                        StatChange(DomainRef("PLAYER", "P1"), "STR", ExactLongDelta.of(1L))
                    )
                ))
            )
    }

    private class RejectingProvider : WorldRuleProvider("REPRO-PROVIDER", "1", "TEST-WORLD", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision =
            WorldRuleDecision.Rejected.create("RULE", "REJECT")
    }

    private class AllowingProvider : WorldRuleProvider("REPRO-PROVIDER", "1", "TEST-WORLD", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision = WorldRuleDecision.Allowed.create("RULE")
    }

    private enum class MutableMode { INSTANCE; var counter: Int = 0 }

    private class MutableEnumProvider(
        private val mode: MutableMode = MutableMode.INSTANCE
    ) : WorldRuleProvider("REPRO-MUTABLE-ENUM", "1", "TEST-WORLD", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision =
            if (mode.counter++ == 0) WorldRuleDecision.Allowed.create("RULE")
            else WorldRuleDecision.Rejected.create("RULE", "CHANGED")
    }
}
