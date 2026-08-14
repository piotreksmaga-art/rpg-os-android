from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"missing patch marker in {path}: {old[:120]!r}")
    if text.count(old) != 1:
        raise SystemExit(f"non-unique patch marker in {path}: {old[:120]!r}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


wr = ROOT / "app/src/main/java/com/rpgos/app/WorldRuleProvider.kt"
replace_once(
    wr,
    "import java.util.Collections\n",
    "import java.util.Collections\nimport java.util.IdentityHashMap\n",
)
replace_once(
    wr,
    "internal data object UnboundGenericWorldRuleMode : WorldRuleMode\n\nenum class WorldRuleEvaluationStage",
    '''internal data object UnboundGenericWorldRuleMode : WorldRuleMode

/**
 * Immutable read-only snapshot of the canonical campaign -> active World Pack selection.
 * It is derived from CampaignSelectionManager and is not a second persisted authority.
 */
internal class WorldPackAuthoritySnapshot private constructor(
    bindings: Map<String, WorldPackRuleBinding>
) {
    private val byCampaignUid: Map<String, WorldPackRuleBinding> =
        Collections.unmodifiableMap(LinkedHashMap(bindings))

    fun bindingForCampaign(campaignUid: String): WorldPackRuleBinding? = byCampaignUid[campaignUid]

    companion object {
        fun empty(): WorldPackAuthoritySnapshot = WorldPackAuthoritySnapshot(emptyMap())

        fun single(campaignUid: String, binding: WorldPackRuleBinding): WorldPackAuthoritySnapshot {
            require(campaignUid.isNotBlank()) { "campaignUid must not be blank" }
            return WorldPackAuthoritySnapshot(mapOf(campaignUid to binding))
        }
    }
}

enum class WorldRuleEvaluationStage''',
)
old_validator = '''private fun validateProviderState(provider: WorldRuleProvider) {
    val safe = scalarSafeTypes()
    var type: Class<*>? = provider.javaClass
    while (type != null && type != WorldRuleProvider::class.java) {
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
            if (!Modifier.isFinal(field.modifiers)) failRule("MUTABLE_WORLD_RULE_PROVIDER_STATE")
            when {
                field.type.isPrimitive || field.type in safe -> Unit
                field.type.isEnum -> validateEnumRetainedState(field.type, safe)
                else -> failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
            }
        }
        type = type.superclass
    }
}

private fun scalarSafeTypes(): Set<Class<*>> = setOf(
    String::class.java, java.lang.Long::class.java, java.lang.Integer::class.java,
    java.lang.Boolean::class.java, java.lang.Short::class.java, java.lang.Byte::class.java,
    java.lang.Character::class.java, java.lang.Double::class.java, java.lang.Float::class.java
)

private fun validateEnumRetainedState(enumType: Class<*>, safe: Set<Class<*>>) {
    enumType.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach { field ->
        if (!Modifier.isFinal(field.modifiers)) failRule("MUTABLE_WORLD_RULE_PROVIDER_STATE")
        when {
            field.type.isPrimitive || field.type in safe -> Unit
            field.type.isEnum && field.type != enumType -> validateEnumRetainedState(field.type, safe)
            else -> failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
        }
    }
}
'''
new_validator = '''private fun validateProviderState(provider: WorldRuleProvider) {
    val safe = scalarSafeTypes()
    var type: Class<*>? = provider.javaClass
    while (type != null && type != WorldRuleProvider::class.java) {
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach { field ->
            if (!Modifier.isFinal(field.modifiers)) failRule("MUTABLE_WORLD_RULE_PROVIDER_STATE")
            when {
                field.type.isPrimitive || field.type in safe -> Unit
                field.type.isEnum -> {
                    val value = readRetainedField(field, provider) as? Enum<*>
                        ?: failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
                    val visited = Collections.newSetFromMap(IdentityHashMap<Enum<*>, Boolean>())
                    validateEnumRetainedState(value, safe, visited)
                }
                else -> failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
            }
        }
        type = type.superclass
    }
}

private fun scalarSafeTypes(): Set<Class<*>> = setOf(
    String::class.java, java.lang.Long::class.java, java.lang.Integer::class.java,
    java.lang.Boolean::class.java, java.lang.Short::class.java, java.lang.Byte::class.java,
    java.lang.Character::class.java, java.lang.Double::class.java, java.lang.Float::class.java
)

private fun validateEnumRetainedState(
    enumValue: Enum<*>,
    safe: Set<Class<*>>,
    visited: MutableSet<Enum<*>>
) {
    if (!visited.add(enumValue)) return
    var type: Class<*>? = enumValue.javaClass
    while (type != null && type != Enum::class.java) {
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }.forEach { field ->
            if (!Modifier.isFinal(field.modifiers)) failRule("MUTABLE_WORLD_RULE_PROVIDER_STATE")
            when {
                field.type.isPrimitive || field.type in safe -> Unit
                field.type.isEnum -> {
                    val nested = readRetainedField(field, enumValue) as? Enum<*>
                        ?: failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
                    validateEnumRetainedState(nested, safe, visited)
                }
                else -> failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
            }
        }
        type = type.superclass
    }
}

private fun readRetainedField(field: java.lang.reflect.Field, target: Any): Any? = try {
    field.isAccessible = true
    field.get(target)
} catch (e: ReflectiveOperationException) {
    throw PlayerDomainEngineStructuralException("UNSAFE_WORLD_RULE_PROVIDER_STATE", e)
} catch (e: SecurityException) {
    throw PlayerDomainEngineStructuralException("UNSAFE_WORLD_RULE_PROVIDER_STATE", e)
}
'''
replace_once(wr, old_validator, new_validator)

engine = ROOT / "app/src/main/java/com/rpgos/app/PlayerDomainEngine.kt"
replace_once(
    engine,
    "    private val changeRegistry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core(),\n    private val worldRuleRegistry: WorldRuleProviderRegistry = WorldRuleProviderRegistry.empty()\n) {",
    "    private val changeRegistry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core(),\n    private val worldRuleRegistry: WorldRuleProviderRegistry = WorldRuleProviderRegistry.empty(),\n    private val worldPackAuthority: WorldPackAuthoritySnapshot = WorldPackAuthoritySnapshot.empty()\n) {",
)
replace_once(
    engine,
    '''        validateReferences(context, commandReferences(canonicalCommand))?.let {
            return rejected(it, contextFingerprint, context, null, ruleTrace)
        }

        evaluateWorldRules(''',
    '''        validateReferences(context, commandReferences(canonicalCommand))?.let {
            return rejected(it, contextFingerprint, context, null, ruleTrace)
        }

        validateWorldRuleAuthority(context)

        evaluateWorldRules(''',
)
replace_once(
    engine,
    '''    private fun evaluateWorldRules(
        stage: WorldRuleEvaluationStage,''',
    '''    private fun validateWorldRuleAuthority(context: PlayerResolutionContext) {
        val authoritative = worldPackAuthority.bindingForCampaign(context.campaignUid)
        when (val mode = context.worldRuleMode) {
            is WorldRuleMode.Bound -> {
                if (authoritative == null) fail("WORLD_RULE_AUTHORITY_MISSING")
                if (authoritative != mode.binding) fail("WORLD_RULE_BINDING_AUTHORITY_MISMATCH")
            }
            UnboundGenericWorldRuleMode -> {
                if (authoritative != null) fail("WORLD_RULE_GENERIC_MODE_AUTHORITY_MISMATCH")
            }
        }
    }

    private fun evaluateWorldRules(
        stage: WorldRuleEvaluationStage,''',
)

selection = ROOT / "app/src/main/java/com/rpgos/app/CampaignSelectionManager.kt"
replace_once(
    selection,
    '''    fun activeWorldRuleMode(): WorldRuleMode.Bound {
        val dir = File(worldpacks, activeWorldPackDirName())
        val validation = PackageValidator().validateWorldPack(dir)
        require(validation.ok) { "Active World Pack is invalid: ${validation.message}" }
        val uid = validation.packageId?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no id")
        val version = validation.version?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no version")
        return WorldRuleMode.Bound(WorldPackRuleBinding(uid, version))
    }
''',
    '''    fun activeWorldRuleMode(): WorldRuleMode.Bound {
        val dir = File(worldpacks, activeWorldPackDirName())
        val validation = PackageValidator().validateWorldPack(dir)
        require(validation.ok) { "Active World Pack is invalid: ${validation.message}" }
        val uid = validation.packageId?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no id")
        val version = validation.version?.takeIf { it.isNotBlank() }
            ?: error("Active World Pack manifest has no version")
        return WorldRuleMode.Bound(WorldPackRuleBinding(uid, version))
    }

    /** Read-only Phase-19 authority snapshot derived from canonical app selection. */
    internal fun activeWorldPackAuthoritySnapshot(): WorldPackAuthoritySnapshot =
        WorldPackAuthoritySnapshot.single(activeCampaignId(), activeWorldRuleMode().binding)
''',
)

p19 = ROOT / "app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19Test.kt"
replace_once(
    p19,
    '''            PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TrainComponent())))
                .resolve(train(), context())''',
    '''            PlayerDomainEngine(
                PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
                worldPackAuthority = authority()
            ).resolve(train(), context())''',
)
replace_once(
    p19,
    '''            PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TrainComponent())), worldRuleRegistry = registry)
                .resolve(train(), context())''',
    '''            PlayerDomainEngine(
                PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
                worldRuleRegistry = registry,
                worldPackAuthority = authority()
            ).resolve(train(), context())''',
)
replace_once(
    p19,
    '''    private fun engine(provider: WorldRuleProvider): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider))
    )

    private fun financeEngine(): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(FinanceComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(GenericProvider(Mode.ALLOW)))
    )
''',
    '''    private fun engine(provider: WorldRuleProvider): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = authority()
    )

    private fun financeEngine(): PlayerDomainEngine = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(FinanceComponent())),
        worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(GenericProvider(Mode.ALLOW))),
        worldPackAuthority = authority()
    )

    private fun authority(b: WorldPackRuleBinding = binding): WorldPackAuthoritySnapshot =
        WorldPackAuthoritySnapshot.single("C1", b)
''',
)

hard = ROOT / "app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19HardeningTest.kt"
replace_once(
    hard,
    '''        assertTrue(engine(null).resolve(train(), unboundContext()) is PlayerResolutionOutcome.Resolved)''',
    '''        assertTrue(engine(null, WorldPackAuthoritySnapshot.empty()).resolve(train(), unboundContext()) is PlayerResolutionOutcome.Resolved)''',
)
replace_once(
    hard,
    '''        engine(AllowAllProvider()).resolve(train(), boundContext(WorldPackRuleBinding("TEST-WORLD", "2")))''',
    '''        val v2 = WorldPackRuleBinding("TEST-WORLD", "2")
        engine(AllowAllProvider(), WorldPackAuthoritySnapshot.single("C1", v2)).resolve(train(), boundContext(v2))''',
)
replace_once(
    hard,
    '''    private fun engine(provider:WorldRuleProvider?)=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),worldRuleRegistry=if(provider==null)WorldRuleProviderRegistry.empty() else WorldRuleProviderRegistry.of(listOf(provider)))''',
    '''    private fun engine(
        provider: WorldRuleProvider?,
        authority: WorldPackAuthoritySnapshot = WorldPackAuthoritySnapshot.single("C1", binding)
    ) = PlayerDomainEngine(
        PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
        worldRuleRegistry = if (provider == null) WorldRuleProviderRegistry.empty() else WorldRuleProviderRegistry.of(listOf(provider)),
        worldPackAuthority = authority
    )''',
)

repro = ROOT / "app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19BlockerReproductionTest.kt"
replace_once(
    repro,
    '''        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider))
        )''',
    '''        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TrainComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(provider)),
            worldPackAuthority = WorldPackAuthoritySnapshot.single("C1", binding)
        )''',
)

test = ROOT / "app/src/test/java/com/rpgos/app/WorldRuleProviderPhase19FinalHotfixTest.kt"
test.write_text(r'''package com.rpgos.app

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
''', encoding="utf-8")

print("Phase 19 final authority/enum hotfix staged")
