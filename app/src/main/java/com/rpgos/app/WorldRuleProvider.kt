package com.rpgos.app

import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap

data class WorldPackRuleBinding(val worldPackUid: String, val worldPackVersion: String) {
    init {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(worldPackVersion.isNotBlank()) { "worldPackVersion must not be blank" }
    }
}

sealed interface WorldRuleMode {
    data class Bound(val binding: WorldPackRuleBinding) : WorldRuleMode
}

internal data object UnboundGenericWorldRuleMode : WorldRuleMode

/** Read-only World Pack authority lookup. Implementations must not mutate canonical selection. */
internal fun interface WorldPackAuthorityResolver {
    fun bindingForCampaign(campaignUid: String): WorldPackRuleBinding?
}

/**
 * Immutable read-only authority fixture/snapshot. It is not a persisted source of truth.
 * Production canonical authority should be resolved live from CampaignSelectionManager.
 */
internal class WorldPackAuthoritySnapshot private constructor(
    bindings: Map<String, WorldPackRuleBinding>
) : WorldPackAuthorityResolver {
    private val byCampaignUid: Map<String, WorldPackRuleBinding> =
        Collections.unmodifiableMap(LinkedHashMap(bindings))

    override fun bindingForCampaign(campaignUid: String): WorldPackRuleBinding? = byCampaignUid[campaignUid]

    companion object {
        fun empty(): WorldPackAuthoritySnapshot = WorldPackAuthoritySnapshot(emptyMap())

        fun single(campaignUid: String, binding: WorldPackRuleBinding): WorldPackAuthoritySnapshot {
            require(campaignUid.isNotBlank()) { "campaignUid must not be blank" }
            return WorldPackAuthoritySnapshot(mapOf(campaignUid to binding))
        }
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

    internal fun deterministicFingerprint(): String = WorldRuleCanonicalWriter.fingerprint("WORLD_RULE_EFFECT_SNAPSHOT") {
        list("CHANGES", changes) { appendCanonicalChange(it) }
        list("EVENT_INTENTS", eventIntents) { appendCanonicalEventIntent(it) }
        list("LEDGER_INTENTS", ledgerIntents) { appendCanonicalLedgerIntent(it) }
        list("WARNINGS", warnings) { warning ->
            record("CHANGE_SET_WARNING") {
                field("WARNING_KIND_UID", warning.warningKindUid)
                nullableField("DETAIL", warning.detail)
                nullableField("RELATED_CHANGE_UID", warning.relatedChangeUid)
            }
        }
    }

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
    val requestFingerprint: String = WorldRuleCanonicalWriter.fingerprint("WORLD_RULE_REQUEST") {
        field("STAGE", stage.name)
        section("WORLD_PACK") {
            field("UID", worldPack.worldPackUid)
            field("VERSION", worldPack.worldPackVersion)
        }
        field("CAMPAIGN_UID", campaignUid)
        section("ACTOR") {
            field("KIND_UID", actor.actorKindUid)
            field("UID", actor.actorUid)
        }
        section("COMMAND") {
            field("UID", command.commandUid)
            field("KIND_UID", command.commandKindUid)
            field("FINGERPRINT", commandFingerprint)
        }
        field("CONTEXT_FINGERPRINT", contextFingerprint)
        section("EFFECTS") {
            if (effects == null) {
                field("PRESENCE", "NULL")
            } else {
                field("PRESENCE", "VALUE")
                field("FINGERPRINT", effects.deterministicFingerprint())
            }
        }
    }

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
            require(provider.worldPackUid == request.worldPack.worldPackUid)
            require(provider.worldPackVersion == request.worldPack.worldPackVersion)
            val reason = (decision as? WorldRuleDecision.Rejected)?.reasonUid
            validateDecision(decision.ruleUid, reason, decision.evidenceUids)
            val sortedEvidence = decision.evidenceUids.sorted()
            val variant = when (decision) {
                is WorldRuleDecision.Allowed -> "ALLOWED"
                is WorldRuleDecision.Rejected -> "REJECTED"
            }
            val fingerprint = WorldRuleCanonicalWriter.fingerprint("WORLD_RULE_DECISION") {
                section("PROVIDER") {
                    field("UID", provider.providerUid)
                    field("VERSION", provider.providerVersion)
                }
                section("WORLD_PACK") {
                    field("UID", provider.worldPackUid)
                    field("VERSION", provider.worldPackVersion)
                }
                field("STAGE", request.stage.name)
                field("REQUEST_FINGERPRINT", request.requestFingerprint)
                field("DECISION_VARIANT", variant)
                field("RULE_UID", decision.ruleUid)
                nullableField("REASON_UID", reason)
                list("EVIDENCE_UIDS", sortedEvidence) { evidenceUid ->
                    record("EVIDENCE_UID") { field("UID", evidenceUid) }
                }
            }
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
    val safe = scalarSafeTypes()
    var type: Class<*>? = provider.javaClass
    while (type != null && type != WorldRuleProvider::class.java) {
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
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
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
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

private fun WorldRuleCanonicalWriter.appendCanonicalChange(change: PlayerDomainChange) {
    val payloadType = when (change.payload) {
        is StatChange -> "STAT_CHANGE"
        is ResourceChange -> "RESOURCE_CHANGE"
        is SkillChange -> "SKILL_CHANGE"
        is TechniqueChange -> "TECHNIQUE_CHANGE"
        is InnateChange -> "INNATE_CHANGE"
        is InventoryChange -> "INVENTORY_CHANGE"
        is EquipmentChange -> "EQUIPMENT_CHANGE"
        is FinancialChange -> "FINANCIAL_CHANGE"
        is AssetChange -> "ASSET_CHANGE"
        is OwnershipChange -> "OWNERSHIP_CHANGE"
        is CampaignTruthChange -> "CAMPAIGN_TRUTH_CHANGE"
        is ConditionChange -> "CONDITION_CHANGE"
        is RuntimeChange -> "RUNTIME_CHANGE"
        is WoundChange -> "WOUND_CHANGE"
        is SpatialChange -> "SPATIAL_CHANGE"
        is EquipmentIntegrityChange -> "EQUIPMENT_INTEGRITY_CHANGE"
        is StructureIntegrityChange -> "STRUCTURE_INTEGRITY_CHANGE"
        is MechanicalTrackChange -> "MECHANICAL_TRACK_CHANGE"
        is AggregatePopulationChange -> "AGGREGATE_POPULATION_CHANGE"
        is DevelopmentProjectChange -> "DEVELOPMENT_PROJECT_CHANGE"
        is KnowledgeAcquisitionChange -> "KNOWLEDGE_ACQUISITION_CHANGE"
        is AccessAuthorityChange -> "ACCESS_AUTHORITY_CHANGE"
    }
    record(payloadType) {
        field("CHANGE_UID", change.changeUid)
        field("CHANGE_KIND_UID", change.changeKindUid)
        nullableField("SOURCE_RULE_UID", change.sourceRuleUid)
        when (val payload = change.payload) {
            is StatChange -> {
                domainRef("SUBJECT", payload.subject); field("STAT_UID", payload.statUid); longField("DELTA", payload.delta.units)
            }
            is ResourceChange -> {
                domainRef("SUBJECT", payload.subject); field("RESOURCE_UID", payload.resourceUid); longField("DELTA", payload.delta.units)
            }
            is SkillChange -> {
                domainRef("SUBJECT", payload.subject); field("SKILL_UID", payload.skillUid); longField("PROGRESS_DELTA", payload.progressDelta.units)
            }
            is TechniqueChange -> {
                domainRef("SUBJECT", payload.subject); field("TECHNIQUE_UID", payload.techniqueUid); longField("PROGRESS_DELTA", payload.progressDelta.units)
            }
            is InnateChange -> {
                domainRef("SUBJECT", payload.subject); field("INNATE_UID", payload.innateUid); field("PROPOSED_STATE_UID", payload.proposedStateUid)
            }
            is InventoryChange -> {
                domainRef("SUBJECT", payload.subject); field("ITEM_INSTANCE_UID", payload.itemInstanceUid); longField("QUANTITY_DELTA", payload.quantityDelta.units)
                payload.itemMaterialization?.let{materialization->section("ITEM_MATERIALIZATION"){
                    field("ITEM_DEFINITION_UID",materialization.itemDefinitionUid)
                    field("WORLD_PACK_UID",materialization.worldPackUid)
                    field("ITEM_KEY",materialization.itemKey)
                    field("DISPLAY_NAME",materialization.displayName)
                    nullableField("CATEGORY_UID",materialization.categoryUid)
                }}
            }
            is EquipmentChange -> {
                domainRef("SUBJECT", payload.subject); field("SLOT_UID", payload.slotUid); field("OPERATION", payload.operation.name)
                nullableField("ITEM_INSTANCE_UID", payload.itemInstanceUid)
            }
            is FinancialChange -> {
                field("FROM_ACCOUNT_UID", payload.fromAccountUid); field("TO_ACCOUNT_UID", payload.toAccountUid)
                longField("AMOUNT_MINOR", payload.amountMinor); field("CURRENCY_UID", payload.currencyUid)
                field("TRANSACTION_TYPE_UID", payload.transactionTypeUid)
            }
            is AssetChange -> {
                field("ASSET_KIND_UID", payload.asset.assetKindUid); field("ASSET_UID", payload.asset.assetUid)
                field("PROPOSED_LIFECYCLE_STATE_UID", payload.proposedLifecycleStateUid)
            }
            is OwnershipChange -> {
                field("OWNERSHIP_RECORD_UID", payload.ownershipRecordUid)
                field("ASSET_KIND_UID", payload.asset.assetKindUid); field("ASSET_UID", payload.asset.assetUid)
                field("FROM_OWNER_KIND_UID", payload.fromOwner.ownerKindUid); field("FROM_OWNER_UID", payload.fromOwner.ownerUid)
                field("TO_OWNER_KIND_UID", payload.toOwner.ownerKindUid); field("TO_OWNER_UID", payload.toOwner.ownerUid)
                longField("SHARE_UNITS", payload.share.units)
            }
            is CampaignTruthChange -> {
                field("TRUTH_UID", payload.truthUid)
                field("TRUTH_KIND", payload.kind.name)
                nullableField("SUBJECT_UID", payload.subjectUid)
                field("PREDICATE", payload.predicate)
                nullableField("OBJECT_VALUE", payload.objectValue)
                nullableField("PERSPECTIVE_UID", payload.perspectiveUid)
                nullableField("NARRATIVE_TEXT", payload.narrativeText)
                nullableField("SUPERSEDES_TRUTH_UID", payload.supersedesTruthUid)
                section("CANON_DIVERGENCE") {
                    val d = payload.canonDivergence
                    field("PRESENCE", if (d == null) "NULL" else "VALUE")
                    if (d != null) {
                        field("UID", d.divergenceUid); field("SUBJECT_KIND", d.canonicalReference.subjectKindUid)
                        field("SUBJECT_UID", d.canonicalReference.subjectUid); field("EXPECTATION_UID", d.canonicalReference.expectationUid)
                        field("WORLD_PACK_UID", d.worldPackUid); field("WORLD_PACK_VERSION", d.worldPackVersion)
                        field("KIND", d.kind.name); field("EXPECTED", d.expectedCanonicalValue); field("ACTUAL", d.actualCampaignValue)
                        field("STATUS", d.status.name); nullableLongField("EFFECTIVE_FROM", d.effectiveFrom)
                        nullableLongField("EFFECTIVE_UNTIL", d.effectiveUntil); nullableField("SUPERSEDES", d.supersedesDivergenceUid)
                        nullableField("RESOLVES", d.resolvesDivergenceUid); field("PROVENANCE", d.provenanceStatus.name)
                        longField("SCHEMA_VERSION", d.schemaVersion.toLong())
                    }
                }
            }
            is ConditionChange -> {
                domainRef("SUBJECT", payload.subject); field("CONDITION_UID", payload.conditionUid); field("OPERATION", payload.operation.name)
            }
            is RuntimeChange -> {
                domainRef("SUBJECT", payload.subject); field("RUNTIME_COUNTER_UID", payload.runtimeCounterUid); longField("DELTA", payload.delta.units)
            }
            is WoundChange -> {
                domainRef("SUBJECT", payload.subject); longField("SEVERITY_DELTA", payload.severityDelta.units); nullableField("SEVERITY_UID", payload.severityUid)
            }
            is SpatialChange -> {
                domainRef("SUBJECT", payload.subject); longField("DELTA_X_MILLIMETRES", payload.deltaXMillimetres); longField("DELTA_Y_MILLIMETRES", payload.deltaYMillimetres);payload.destinationLocation?.let{domainRef("DESTINATION",it)}
            }
            is EquipmentIntegrityChange -> {
                domainRef("SUBJECT", payload.subject); field("COMPONENT_UID", payload.componentUid); longField("DAMAGE_DELTA", payload.damageDelta.units)
            }
            is StructureIntegrityChange -> {
                domainRef("SUBJECT", payload.subject); nullableField("COMPONENT_UID", payload.componentUid); longField("DAMAGE_DELTA", payload.damageDelta.units)
            }
            is MechanicalTrackChange -> {
                domainRef("SUBJECT", payload.subject); field("TRACK_UID", payload.trackUid); longField("DELTA", payload.delta.units)
            }
            is AggregatePopulationChange -> {
                domainRef("SUBJECT", payload.subject); longField("ELIMINATED_DELTA", payload.eliminatedDelta); longField("WOUNDED_DELTA", payload.woundedDelta)
                nullableField("CONDITION_UID", payload.conditionUid); longField("CONDITION_AFFECTED_DELTA", payload.conditionAffectedDelta)
            }
            is DevelopmentProjectChange -> {
                field("PROJECT_UID", payload.projectUid); field("WORK_RESULT_KIND_UID", payload.workResultKindUid)
                longField("PROGRESS_DELTA", payload.progressDelta.units)
                list("EVIDENCE_REFS", payload.evidenceRefs) { ref -> domainRef("EVIDENCE_REF", ref) }
            }
            is AccessAuthorityChange -> {
                field("OPERATION", payload.operation.name)
                field("RECORD_UID", payload.recordUid)
                field("PRINCIPAL_KIND_UID", payload.principalKindUid)
                field("PRINCIPAL_UID", payload.principalUid)
                field("BINDING_OR_GRANT_KIND_UID", payload.bindingOrGrantKindUid)
                field("VALUE_UID", payload.valueUid)
                nullableField("SUBJECT_KIND_UID", payload.subjectKindUid)
                nullableField("SUBJECT_UID", payload.subjectUid)
                longField("VALID_FROM_ORDER", payload.validFromOrder)
                nullableLongField("VALID_UNTIL_ORDER", payload.validUntilOrder)
                nullableField("DELEGATED_BY_PRINCIPAL_UID", payload.delegatedByPrincipalUid)
            }
            is KnowledgeAcquisitionChange -> {
                section("CLAIM") {
                    field("UID", payload.claim.claimUid); field("SUBJECT_KIND", payload.claim.subjectKindUid)
                    field("SUBJECT_UID", payload.claim.subjectUid); field("PREDICATE", payload.claim.predicateUid)
                    field("VALUE", payload.claim.valueCanonical); nullableField("OBJECT_KIND", payload.claim.objectKindUid)
                    nullableField("OBJECT_UID", payload.claim.objectUid); field("DOMAIN", payload.claim.domainUid)
                }
                section("ACQUISITION") {
                    field("UID", payload.acquisition.acquisitionUid)
                    field("HOLDER_KIND", payload.acquisition.holder.holderKindUid); field("HOLDER_UID", payload.acquisition.holder.holderUid)
                    nullableField("HOLDER_CAMPAIGN_UID", payload.acquisition.holder.campaignUid)
                    field("METHOD", payload.acquisition.methodUid); field("SCOPE", payload.acquisition.scope.name)
                    field("EPISTEMIC_STATE", payload.acquisition.epistemicState.name)
                    field("CONFIDENCE", payload.acquisition.quality.confidence.toString())
                    field("PRECISION", payload.acquisition.quality.precision.toString())
                    field("COMPLETENESS", payload.acquisition.quality.completeness.toString())
                    field("SOURCE_RELIABILITY", payload.acquisition.quality.sourceReliability.toString())
                    longField("CORROBORATION_COUNT", payload.acquisition.quality.corroborationCount.toLong())
                    nullableLongField("SOURCE_OBSERVED_ORDER", payload.acquisition.quality.sourceObservedOrder)
                    nullableField("PARENT_ACQUISITION", payload.acquisition.parentAcquisitionUid)
                    nullableField("SOURCE_HOLDER_KIND", payload.acquisition.sourceHolder?.holderKindUid)
                    nullableField("SOURCE_HOLDER_UID", payload.acquisition.sourceHolder?.holderUid)
                    nullableField("SOURCE_HOLDER_CAMPAIGN_UID", payload.acquisition.sourceHolder?.campaignUid)
                    nullableField("ROLE_UID", payload.acquisition.roleUid)
                    nullableField("CARRIER_KIND", payload.acquisition.carrier?.carrierKindUid)
                    nullableField("CARRIER_UID", payload.acquisition.carrier?.carrierUid)
                    nullableField("CARRIER_CAMPAIGN_UID", payload.acquisition.carrier?.campaignUid)
                    field("PROVENANCE", payload.acquisition.provenanceStatus.name)
                }
                list("KNOWLEDGE_EVIDENCE", payload.evidence) { e ->
                    record("KNOWLEDGE_EVIDENCE") {
                        field("UID", e.evidenceUid); field("KIND", e.evidenceKindUid); field("POLARITY", e.polarity.name)
                        nullableField("SOURCE_ACQUISITION", e.sourceAcquisitionUid)
                        nullableField("SOURCE_CARRIER_KIND", e.sourceCarrier?.carrierKindUid)
                        nullableField("SOURCE_CARRIER_UID", e.sourceCarrier?.carrierUid)
                        nullableField("SOURCE_CARRIER_CAMPAIGN_UID", e.sourceCarrier?.campaignUid)
                        nullableField("SOURCE_REF_SCOPE", e.sourceRef?.scope?.name)
                        nullableField("SOURCE_REF_CAMPAIGN_UID", e.sourceRef?.campaignUid)
                        nullableField("SOURCE_REF_KIND", e.sourceRef?.kindUid); nullableField("SOURCE_REF_UID", e.sourceRef?.entityUid)
                    }
                }
            }
        }
    }
}

private fun WorldRuleCanonicalWriter.appendCanonicalEventIntent(intent: PlayerEventIntent) {
    record("PLAYER_EVENT_INTENT") {
        field("EVENT_INTENT_UID", intent.eventIntentUid)
        field("EVENT_KIND_UID", intent.eventKindUid)
        nullableDomainRef("ACTOR_REF", intent.actorRef)
        list("TARGET_REFS", intent.targetRefs) { ref -> domainRef("TARGET_REF", ref) }
        list("CAUSAL_CHANGE_UIDS", intent.causalChangeUids) { uid -> record("CAUSAL_CHANGE_UID") { field("UID", uid) } }
        nullableLongField("PROPOSED_EFFECTIVE_ORDER", intent.proposedEffectiveOrder)
        when (val payload = intent.payload) {
            is DomainEffectEventIntentPayload -> record("DOMAIN_EFFECT_EVENT_INTENT_PAYLOAD") {
                domainRef("SUBJECT", payload.subject)
                field("EFFECT_KIND_UID", payload.effectKindUid)
            }
        }
    }
}

private fun WorldRuleCanonicalWriter.appendCanonicalLedgerIntent(intent: PlayerLedgerIntent) {
    record("PLAYER_LEDGER_INTENT") {
        field("LEDGER_INTENT_UID", intent.ledgerIntentUid)
        field("LEDGER_KIND_UID", intent.ledgerKindUid)
        list("CAUSAL_CHANGE_UIDS", intent.causalChangeUids) { uid -> record("CAUSAL_CHANGE_UID") { field("UID", uid) } }
        when (val payload = intent.payload) {
            is FinancialTransferLedgerIntentPayload -> record("FINANCIAL_TRANSFER_LEDGER_INTENT_PAYLOAD") {
                field("FROM_ACCOUNT_UID", payload.fromAccountUid); field("TO_ACCOUNT_UID", payload.toAccountUid)
                longField("AMOUNT_MINOR", payload.amountMinor); field("CURRENCY_UID", payload.currencyUid)
                field("TRANSACTION_TYPE_UID", payload.transactionTypeUid)
            }
            is ProgressionLedgerIntentPayload -> record("PROGRESSION_LEDGER_INTENT_PAYLOAD") {
                field("PROGRESSION_UID", payload.progressionUid)
                field("CAMPAIGN_UID", payload.campaignUid)
                field("CHARACTER_UID", payload.characterUid)
                field("TARGET_KIND_UID", payload.targetKindUid)
                field("TARGET_UID", payload.targetUid)
                field("SOURCE_TYPE_UID", payload.sourceTypeUid)
                field("SOURCE_CHANNEL_UID", payload.sourceChannelUid)
                field("SOURCE_COMMAND_UID", payload.sourceCommandUid)
                field("STIMULUS_UID", payload.stimulusUid)
                nullableField("PROGRESSION_DOMAIN_UID", payload.progressionDomainUid)
                nullableField("METHOD_UID", payload.methodUid)
                field("CURRENT_VALUE_EVIDENCE_UID", payload.currentValueEvidenceUid)
                field("CURRENT_VALUE_CANONICAL", payload.currentValueCanonical)
                field("CURRENT_VALUE_SEMANTICS_UID", payload.currentValueSemanticsUid)
                field("CURRENT_VALUE_SEMANTICS_VERSION", payload.currentValueSemanticsVersion)
                list("CALCULATION_FACTORS", payload.calculationFactors) { factor ->
                    record("PROGRESSION_LEDGER_FACTOR") {
                        field("FACTOR_KIND_UID", factor.factorKindUid)
                        field("EVIDENCE_UID", factor.evidenceUid)
                        longField("SOURCE_VALUE_SCALED", factor.sourceValueScaled)
                        longField("APPLIED_FACTOR_SCALED", factor.appliedFactorScaled)
                        longField("SCALE", factor.scale)
                    }
                }
                nullableField("TALENT_EVIDENCE_UID", payload.talentEvidenceUid)
                nullableLongField("TALENT_FACTOR_SCALED", payload.talentFactorScaled)
                nullableField("POTENTIAL_EVIDENCE_UID", payload.potentialEvidenceUid)
                nullableLongField("POTENTIAL_FACTOR_SCALED", payload.potentialFactorScaled)
                longField("BASE_GRANT_UNITS", payload.baseGrantUnits)
                longField("FINAL_GRANT_UNITS", payload.finalGrantUnits)
                field("PROGRESS_SEMANTICS_UID", payload.progressSemanticsUid)
                field("PROGRESS_SEMANTICS_VERSION", payload.progressSemanticsVersion)
                field("ENGINE_UID", payload.engineUid)
                field("ENGINE_VERSION", payload.engineVersion)
                field("NUMERIC_POLICY_UID", payload.numericPolicyUid)
                field("NUMERIC_POLICY_VERSION", payload.numericPolicyVersion)
                field("PROGRESSION_POLICY_UID", payload.progressionPolicyUid)
                field("PROGRESSION_POLICY_VERSION", payload.progressionPolicyVersion)
                nullableField("WORLD_PACK_UID", payload.worldPackUid)
                nullableField("WORLD_PACK_VERSION", payload.worldPackVersion)
                field("WORLD_PACK_BINDING_IDENTITY", payload.worldPackBindingIdentity)
                field("INPUT_FINGERPRINT", payload.inputFingerprint)
                field("COMPUTATION_FINGERPRINT", payload.computationFingerprint)
                field("GRANT_UID", payload.grantUid)
            }
        }
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
