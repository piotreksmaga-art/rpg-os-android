package com.rpgos.app

import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections

data class WorldPackRuleBinding(val worldPackUid: String, val worldPackVersion: String) {
    init {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(worldPackVersion.isNotBlank()) { "worldPackVersion must not be blank" }
    }
}

enum class WorldRuleEvaluationStage { COMMAND_PRECHECK, DRAFT_EFFECT_CHECK }

internal class WorldRuleEffectSnapshot private constructor(
    changes: List<PlayerDomainChange>,
    eventIntents: List<PlayerEventIntent>,
    ledgerIntents: List<PlayerLedgerIntent>,
    warnings: List<ChangeSetWarning>
) {
    val changes: List<PlayerDomainChange> = frozen(changes)
    val eventIntents: List<PlayerEventIntent> = frozen(eventIntents)
    val ledgerIntents: List<PlayerLedgerIntent> = frozen(ledgerIntents)
    val warnings: List<ChangeSetWarning> = frozen(warnings)

    internal fun deterministicFingerprint(): String = ruleSha256(buildString {
        changes.forEach {
            token(it.changeUid); token(it.changeKindUid); token(it.payload.javaClass.name); token(it.payload.toString())
        }
        eventIntents.forEach {
            token(it.eventIntentUid); token(it.eventKindUid); token(it.payload.javaClass.name); token(it.payload.toString())
        }
        ledgerIntents.forEach {
            token(it.ledgerIntentUid); token(it.ledgerKindUid); token(it.payload.javaClass.name); token(it.payload.toString())
        }
        warnings.forEach { token(it.warningKindUid); token(it.detail ?: ""); token(it.relatedChangeUid ?: "") }
    })

    companion object {
        fun create(draft: PlayerResolutionDraft): WorldRuleEffectSnapshot = WorldRuleEffectSnapshot(
            draft.changes, draft.eventIntents, draft.ledgerIntents, draft.warnings
        )
    }
}

/** Read-only transient legality input. No writer/database/transaction capability is supported. */
internal class WorldRuleRequest private constructor(
    val stage: WorldRuleEvaluationStage,
    val worldPack: WorldPackRuleBinding,
    val campaignUid: String,
    val actor: CommandActorRef,
    val command: PlayerCommand<out PlayerCommandPayload>,
    val commandFingerprint: String,
    val contextFingerprint: String,
    val effects: WorldRuleEffectSnapshot?
) {
    val requestFingerprint: String = ruleSha256(buildString {
        token(stage.name); token(worldPack.worldPackUid); token(worldPack.worldPackVersion)
        token(campaignUid); token(actor.actorKindUid); token(actor.actorUid)
        token(command.commandUid); token(command.commandKindUid)
        token(commandFingerprint); token(contextFingerprint)
        token(effects?.deterministicFingerprint() ?: "RPGOS-WORLD-RULE:NO-DRAFT")
    })

    init {
        require(campaignUid.isNotBlank() && commandFingerprint.isNotBlank() && contextFingerprint.isNotBlank())
        require(command.campaignUid == campaignUid && command.actor == actor)
        if (stage == WorldRuleEvaluationStage.COMMAND_PRECHECK) require(effects == null)
        else require(effects != null)
    }

    companion object {
        fun commandPrecheck(
            worldPack: WorldPackRuleBinding,
            campaignUid: String,
            actor: CommandActorRef,
            command: PlayerCommand<out PlayerCommandPayload>,
            commandFingerprint: String,
            contextFingerprint: String
        ) = WorldRuleRequest(
            WorldRuleEvaluationStage.COMMAND_PRECHECK, worldPack, campaignUid, actor,
            command, commandFingerprint, contextFingerprint, null
        )

        fun draftEffectCheck(
            worldPack: WorldPackRuleBinding,
            campaignUid: String,
            actor: CommandActorRef,
            command: PlayerCommand<out PlayerCommandPayload>,
            commandFingerprint: String,
            contextFingerprint: String,
            effects: WorldRuleEffectSnapshot
        ) = WorldRuleRequest(
            WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK, worldPack, campaignUid, actor,
            command, commandFingerprint, contextFingerprint, effects
        )
    }
}

internal sealed interface WorldRuleDecision {
    val ruleUid: String
    val evidenceUids: List<String>

    class Allowed private constructor(
        override val ruleUid: String,
        evidenceUids: List<String>
    ) : WorldRuleDecision {
        override val evidenceUids: List<String> = frozen(evidenceUids)
        override fun equals(other: Any?) = other is Allowed && ruleUid == other.ruleUid && evidenceUids == other.evidenceUids
        override fun hashCode() = arrayOf(ruleUid, evidenceUids).contentHashCode()
        companion object {
            fun create(ruleUid: String, evidenceUids: List<String> = emptyList()): Allowed {
                validateDecision(ruleUid, null, evidenceUids)
                return Allowed(ruleUid, evidenceUids)
            }
        }
    }

    class Rejected private constructor(
        override val ruleUid: String,
        val reasonUid: String,
        evidenceUids: List<String>
    ) : WorldRuleDecision {
        override val evidenceUids: List<String> = frozen(evidenceUids)
        override fun equals(other: Any?) = other is Rejected &&
            ruleUid == other.ruleUid && reasonUid == other.reasonUid && evidenceUids == other.evidenceUids
        override fun hashCode() = arrayOf(ruleUid, reasonUid, evidenceUids).contentHashCode()
        companion object {
            fun create(ruleUid: String, reasonUid: String, evidenceUids: List<String> = emptyList()): Rejected {
                validateDecision(ruleUid, reasonUid, evidenceUids)
                return Rejected(ruleUid, reasonUid, evidenceUids)
            }
        }
    }
}

class WorldRuleDecisionRecord private constructor(
    val providerUid: String,
    val providerVersion: String,
    val worldPackUid: String,
    val worldPackVersion: String,
    val stage: WorldRuleEvaluationStage,
    val ruleUid: String,
    val reasonUid: String?,
    evidenceUids: List<String>,
    val requestFingerprint: String,
    val decisionFingerprint: String
) {
    val evidenceUids: List<String> = frozen(evidenceUids)
    val allowed: Boolean get() = reasonUid == null

    override fun equals(other: Any?) = other is WorldRuleDecisionRecord &&
        providerUid == other.providerUid && providerVersion == other.providerVersion &&
        worldPackUid == other.worldPackUid && worldPackVersion == other.worldPackVersion &&
        stage == other.stage && ruleUid == other.ruleUid && reasonUid == other.reasonUid &&
        evidenceUids == other.evidenceUids && requestFingerprint == other.requestFingerprint &&
        decisionFingerprint == other.decisionFingerprint

    override fun hashCode() = arrayOf(
        providerUid, providerVersion, worldPackUid, worldPackVersion, stage, ruleUid,
        reasonUid, evidenceUids, requestFingerprint, decisionFingerprint
    ).contentHashCode()

    companion object {
        internal fun create(provider: WorldRuleProvider, request: WorldRuleRequest, decision: WorldRuleDecision): WorldRuleDecisionRecord {
            val reason = (decision as? WorldRuleDecision.Rejected)?.reasonUid
            validateDecision(decision.ruleUid, reason, decision.evidenceUids)
            val sortedEvidence = decision.evidenceUids.sorted()
            val fingerprint = ruleSha256(buildString {
                token(provider.providerUid); token(provider.providerVersion)
                token(provider.worldPackUid); token(provider.worldPackVersion)
                token(request.stage.name); token(request.requestFingerprint); token(decision.ruleUid)
                token(reason ?: "RPGOS-WORLD-RULE:ALLOW")
                sortedEvidence.forEach { token(it) }
            })
            return WorldRuleDecisionRecord(
                provider.providerUid, provider.providerVersion, provider.worldPackUid, provider.worldPackVersion,
                request.stage, decision.ruleUid, reason, sortedEvidence, request.requestFingerprint, fingerprint
            )
        }
    }
}

/** Trusted internal legality extension point; it cannot return a proposal or commit state. */
internal abstract class WorldRuleProvider(
    val providerUid: String,
    val providerVersion: String,
    val worldPackUid: String,
    val worldPackVersion: String
) {
    init {
        require(providerUid.isNotBlank() && providerVersion.isNotBlank())
        require(worldPackUid.isNotBlank() && worldPackVersion.isNotBlank())
    }
    internal abstract fun evaluate(request: WorldRuleRequest): WorldRuleDecision
}

internal class WorldRuleProviderRegistry private constructor(providers: List<WorldRuleProvider>) {
    private val byWorldPackUid: Map<String, WorldRuleProvider>
    val worldPackUids: Set<String>

    init {
        val collected = LinkedHashMap<String, WorldRuleProvider>()
        providers.forEach { provider ->
            validateProviderState(provider)
            if (collected.put(provider.worldPackUid, provider) != null) failRule("DUPLICATE_WORLD_RULE_PROVIDER")
        }
        byWorldPackUid = Collections.unmodifiableMap(LinkedHashMap(collected))
        worldPackUids = Collections.unmodifiableSet(LinkedHashSet(collected.keys))
    }

    fun providerFor(binding: WorldPackRuleBinding): WorldRuleProvider? {
        val provider = byWorldPackUid[binding.worldPackUid] ?: return null
        if (provider.worldPackUid != binding.worldPackUid) failRule("WORLD_RULE_PROVIDER_WORLDPACK_MISMATCH")
        if (provider.worldPackVersion != binding.worldPackVersion) failRule("WORLD_RULE_PROVIDER_VERSION_MISMATCH")
        return provider
    }

    companion object {
        fun of(providers: List<WorldRuleProvider>) = WorldRuleProviderRegistry(ArrayList(providers))
        fun empty() = WorldRuleProviderRegistry(emptyList())
    }
}

private fun validateProviderState(provider: WorldRuleProvider) {
    val safe = setOf<Class<*>>(
        String::class.java, java.lang.Long::class.java, java.lang.Integer::class.java,
        java.lang.Boolean::class.java, java.lang.Short::class.java, java.lang.Byte::class.java,
        java.lang.Character::class.java
    )
    var type: Class<*>? = provider.javaClass
    while (type != null && type != WorldRuleProvider::class.java) {
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
            if (!Modifier.isFinal(field.modifiers)) failRule("MUTABLE_WORLD_RULE_PROVIDER_STATE")
            if (!(field.type.isPrimitive || field.type.isEnum || field.type in safe)) {
                failRule("UNSAFE_WORLD_RULE_PROVIDER_STATE")
            }
        }
        type = type.superclass
    }
}

private fun validateDecision(ruleUid: String, reasonUid: String?, evidenceUids: List<String>) {
    require(ruleUid.isNotBlank())
    require(reasonUid?.isBlank() != true)
    require(evidenceUids.none { it.isBlank() })
    require(evidenceUids.size == evidenceUids.distinct().size)
}

private fun failRule(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun StringBuilder.token(value: String) { append(value.length).append(':').append(value).append('|') }
private fun ruleSha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
