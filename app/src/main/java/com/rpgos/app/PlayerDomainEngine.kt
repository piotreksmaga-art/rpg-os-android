package com.rpgos.app

import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.TreeMap
import kotlin.reflect.KClass

class PlayerDomainEngineStructuralException(
    val code: String,
    cause: Throwable? = null
) : IllegalStateException(code, cause)

data class CampaignScopedDomainRef(
    val campaignUid: String,
    val ref: DomainRef
)

data class ResolutionEntropyEvidence(
    val evidenceUid: String,
    val exactValue: Long
) {
    init {
        require(evidenceUid.isNotBlank())
    }

    companion object {
        fun none(): ResolutionEntropyEvidence = ResolutionEntropyEvidence("RPGOS-ENTROPY:NONE", 0L)
    }
}

class PlayerResolutionContext private constructor(
    val campaignUid: String,
    val actor: CommandActorRef,
    knownReferences: Set<CampaignScopedDomainRef>,
    dependencyVersions: Map<String, String>,
    val entropy: ResolutionEntropyEvidence
) {
    val knownReferences: Set<CampaignScopedDomainRef> =
        Collections.unmodifiableSet(LinkedHashSet(knownReferences))
    val dependencyVersions: Map<String, String> =
        Collections.unmodifiableMap(TreeMap(dependencyVersions))

    init {
        require(campaignUid.isNotBlank())
        require(actor.actorKindUid.isNotBlank() && actor.actorUid.isNotBlank())
        require(this.knownReferences.all {
            it.campaignUid.isNotBlank() && it.ref.kindUid.isNotBlank() && it.ref.uid.isNotBlank()
        })
        require(this.dependencyVersions.all { it.key.isNotBlank() && it.value.isNotBlank() })
    }

    internal fun referenceStatus(ref: DomainRef): ResolutionReferenceStatus {
        val inCampaign = CampaignScopedDomainRef(campaignUid, ref) in knownReferences
        if (inCampaign) return ResolutionReferenceStatus.RESOLVED
        val elsewhere = knownReferences.any { it.ref == ref && it.campaignUid != campaignUid }
        return if (elsewhere) ResolutionReferenceStatus.WRONG_CAMPAIGN else ResolutionReferenceStatus.UNKNOWN
    }

    internal fun deterministicFingerprint(): String = sha256(
        buildString {
            appendToken(campaignUid)
            appendToken(actor.actorKindUid)
            appendToken(actor.actorUid)
            knownReferences
                .sortedWith(compareBy({ it.campaignUid }, { it.ref.kindUid }, { it.ref.uid }))
                .forEach {
                    appendToken(it.campaignUid)
                    appendToken(it.ref.kindUid)
                    appendToken(it.ref.uid)
                }
            dependencyVersions.forEach { (key, value) ->
                appendToken(key)
                appendToken(value)
            }
            appendToken(entropy.evidenceUid)
            appendToken(entropy.exactValue.toString())
        }
    )

    companion object {
        fun create(
            campaignUid: String,
            actor: CommandActorRef,
            knownReferences: Set<CampaignScopedDomainRef>,
            dependencyVersions: Map<String, String> = emptyMap(),
            entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none()
        ): PlayerResolutionContext = PlayerResolutionContext(
            campaignUid,
            actor,
            LinkedHashSet(knownReferences),
            TreeMap(dependencyVersions),
            entropy
        )
    }
}

enum class PlayerResolutionRejectionReason(val reasonUid: String) {
    DOMAIN_REJECTED("RPGOS-RESOLUTION-REJECTION:DOMAIN_REJECTED"),
    CONTEXT_CAMPAIGN_MISMATCH("RPGOS-RESOLUTION-REJECTION:CONTEXT_CAMPAIGN_MISMATCH"),
    CONTEXT_ACTOR_MISMATCH("RPGOS-RESOLUTION-REJECTION:CONTEXT_ACTOR_MISMATCH"),
    UNKNOWN_REFERENCE("RPGOS-RESOLUTION-REJECTION:UNKNOWN_REFERENCE"),
    WRONG_CAMPAIGN_REFERENCE("RPGOS-RESOLUTION-REJECTION:WRONG_CAMPAIGN_REFERENCE")
}

class PlayerResolutionRejection private constructor(
    val reason: PlayerResolutionRejectionReason,
    relatedRefs: List<DomainRef>,
    val detailUid: String?
) {
    val relatedRefs: List<DomainRef> = immutableList(relatedRefs)

    override fun equals(other: Any?): Boolean = other is PlayerResolutionRejection &&
        reason == other.reason && relatedRefs == other.relatedRefs && detailUid == other.detailUid

    override fun hashCode(): Int = arrayOf(reason, relatedRefs, detailUid).contentHashCode()

    companion object {
        fun create(
            reason: PlayerResolutionRejectionReason,
            relatedRefs: List<DomainRef> = emptyList(),
            detailUid: String? = null
        ): PlayerResolutionRejection {
            require(detailUid?.isBlank() != true)
            return PlayerResolutionRejection(reason, relatedRefs, detailUid)
        }
    }
}

data class PlayerResolutionEvidence(
    val contextFingerprint: String,
    val entropy: ResolutionEntropyEvidence,
    val componentKindUid: String?,
    val componentVersion: String?
)

sealed interface PlayerResolutionOutcome {
    data class Resolved(
        val proposal: PlayerChangeSet,
        val evidence: PlayerResolutionEvidence
    ) : PlayerResolutionOutcome

    data class Rejected(
        val rejection: PlayerResolutionRejection,
        val evidence: PlayerResolutionEvidence
    ) : PlayerResolutionOutcome
}

internal class PlayerResolutionDraft private constructor(
    changes: List<PlayerDomainChange>,
    eventIntents: List<PlayerEventIntent>,
    ledgerIntents: List<PlayerLedgerIntent>,
    warnings: List<ChangeSetWarning>
) {
    val changes: List<PlayerDomainChange> = immutableList(changes)
    val eventIntents: List<PlayerEventIntent> = immutableList(eventIntents)
    val ledgerIntents: List<PlayerLedgerIntent> = immutableList(ledgerIntents)
    val warnings: List<ChangeSetWarning> = immutableList(warnings)

    companion object {
        fun create(
            changes: List<PlayerDomainChange> = emptyList(),
            eventIntents: List<PlayerEventIntent> = emptyList(),
            ledgerIntents: List<PlayerLedgerIntent> = emptyList(),
            warnings: List<ChangeSetWarning> = emptyList()
        ): PlayerResolutionDraft = PlayerResolutionDraft(
            changes,
            eventIntents,
            ledgerIntents,
            warnings
        )
    }
}

internal sealed interface PlayerResolutionComponentOutcome {
    data class Resolved(val draft: PlayerResolutionDraft) : PlayerResolutionComponentOutcome
    data class Rejected(val rejection: PlayerResolutionRejection) : PlayerResolutionComponentOutcome
}

internal abstract class PlayerResolutionComponent<P : PlayerCommandPayload>(
    val commandKindUid: String,
    val payloadType: KClass<P>,
    val componentKindUid: String,
    val componentVersion: String
) {
    internal abstract fun resolve(
        command: PlayerCommand<P>,
        context: PlayerResolutionContext
    ): PlayerResolutionComponentOutcome
}

internal class PlayerResolutionComponentRegistry private constructor(
    components: List<PlayerResolutionComponent<out PlayerCommandPayload>>
) {
    private val byKind: Map<String, PlayerResolutionComponent<out PlayerCommandPayload>>
    val commandKindUids: Set<String>

    init {
        val collected = LinkedHashMap<String, PlayerResolutionComponent<out PlayerCommandPayload>>()
        components.forEach { component ->
            if (component.commandKindUid.isBlank()) fail("EMPTY_RESOLUTION_COMPONENT_KIND")
            if (component.componentKindUid.isBlank() || component.componentVersion.isBlank()) {
                fail("INVALID_RESOLUTION_COMPONENT_IDENTITY")
            }
            validateComponentState(component)
            if (collected.put(component.commandKindUid, component) != null) {
                fail("DUPLICATE_COMMAND_RESOLUTION_COMPONENT")
            }
        }
        byKind = Collections.unmodifiableMap(LinkedHashMap(collected))
        commandKindUids = Collections.unmodifiableSet(LinkedHashSet(collected.keys))
    }

    fun componentFor(commandKindUid: String): PlayerResolutionComponent<out PlayerCommandPayload>? =
        byKind[commandKindUid]

    companion object {
        fun of(components: List<PlayerResolutionComponent<out PlayerCommandPayload>>): PlayerResolutionComponentRegistry =
            PlayerResolutionComponentRegistry(ArrayList(components))

        fun empty(): PlayerResolutionComponentRegistry = PlayerResolutionComponentRegistry(emptyList())
    }

    private fun validateComponentState(component: PlayerResolutionComponent<out PlayerCommandPayload>) {
        component.javaClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                if (!Modifier.isFinal(field.modifiers)) fail("MUTABLE_RESOLUTION_COMPONENT_STATE")
                if (!safeComponentFieldType(field.type)) fail("UNSAFE_RESOLUTION_COMPONENT_STATE")
            }
    }

    private fun safeComponentFieldType(type: Class<*>): Boolean =
        type.isPrimitive ||
            type == java.lang.Long::class.java ||
            type == java.lang.Integer::class.java ||
            type == java.lang.Boolean::class.java ||
            type == java.lang.Short::class.java ||
            type == java.lang.Byte::class.java ||
            type == java.lang.Character::class.java ||
            type == String::class.java ||
            type.isEnum

    private fun fail(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
}

class PlayerDomainEngine internal constructor(
    private val componentRegistry: PlayerResolutionComponentRegistry,
    private val commandRegistry: PlayerCommandKindRegistry = PlayerCommandKindRegistry.core(),
    private val changeRegistry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core()
) {
    fun resolve(
        command: PlayerCommand<out PlayerCommandPayload>,
        context: PlayerResolutionContext
    ): PlayerResolutionOutcome {
        commandRegistry.validate(command)
        val canonicalCommand = commandRegistry.decode(commandRegistry.encode(command))
        val commandFingerprint = commandRegistry.fingerprint(canonicalCommand)
        val contextFingerprint = context.deterministicFingerprint()

        if (context.campaignUid != canonicalCommand.campaignUid) {
            return rejected(
                PlayerResolutionRejection.create(PlayerResolutionRejectionReason.CONTEXT_CAMPAIGN_MISMATCH),
                contextFingerprint,
                context,
                null
            )
        }
        if (context.actor != canonicalCommand.actor) {
            return rejected(
                PlayerResolutionRejection.create(PlayerResolutionRejectionReason.CONTEXT_ACTOR_MISMATCH),
                contextFingerprint,
                context,
                null
            )
        }

        validateReferences(context, commandReferences(canonicalCommand))?.let {
            return rejected(it, contextFingerprint, context, null)
        }

        val component = componentRegistry.componentFor(canonicalCommand.commandKindUid)
            ?: fail("UNKNOWN_COMMAND_RESOLUTION_COMPONENT")

        val outcome = try {
            resolveTyped(component, canonicalCommand, context)
        } catch (e: PlayerDomainEngineStructuralException) {
            throw e
        } catch (e: Throwable) {
            throw PlayerDomainEngineStructuralException("RESOLUTION_COMPONENT_FAILURE", e)
        }

        if (commandRegistry.fingerprint(canonicalCommand) != commandFingerprint) {
            fail("COMMAND_MUTATED_DURING_RESOLUTION")
        }

        val evidence = evidence(contextFingerprint, context, component)
        return when (outcome) {
            is PlayerResolutionComponentOutcome.Rejected ->
                PlayerResolutionOutcome.Rejected(outcome.rejection, evidence)

            is PlayerResolutionComponentOutcome.Resolved -> {
                validateReferences(context, draftReferences(outcome.draft))?.let {
                    return PlayerResolutionOutcome.Rejected(it, evidence)
                }
                val proposal = assembleProposal(
                    canonicalCommand,
                    contextFingerprint,
                    component,
                    outcome.draft
                )
                PlayerChangeSetValidator.validate(proposal, changeRegistry)
                PlayerResolutionOutcome.Resolved(proposal, evidence)
            }
        }
    }

    private fun assembleProposal(
        command: PlayerCommand<out PlayerCommandPayload>,
        contextFingerprint: String,
        component: PlayerResolutionComponent<out PlayerCommandPayload>,
        draft: PlayerResolutionDraft
    ): PlayerChangeSet {
        val changeSetUid = "RPGOS-CS18:" + sha256(
            buildString {
                appendToken(commandRegistry.encode(command))
                appendToken(contextFingerprint)
                appendToken(component.componentKindUid)
                appendToken(component.componentVersion)
            }
        )
        return PlayerChangeSet.create(
            changeSetUid = changeSetUid,
            campaignUid = command.campaignUid,
            sourceCommandUid = command.commandUid,
            actor = command.actor,
            changes = draft.changes,
            eventIntents = draft.eventIntents,
            ledgerIntents = draft.ledgerIntents,
            preconditions = command.preconditions.map(::toChangeSetPrecondition),
            provenance = ChangeSetProvenance(
                sourceCommandUid = command.commandUid,
                resolverKindUid = component.componentKindUid,
                resolverVersion = component.componentVersion
            ),
            causationUid = command.causationUid,
            correlationUid = command.correlationUid,
            requestedEffectiveOrder = command.requestedEffectiveOrder,
            warnings = draft.warnings,
            registry = changeRegistry
        )
    }

    private fun evidence(
        contextFingerprint: String,
        context: PlayerResolutionContext,
        component: PlayerResolutionComponent<out PlayerCommandPayload>
    ): PlayerResolutionEvidence = PlayerResolutionEvidence(
        contextFingerprint = contextFingerprint,
        entropy = context.entropy,
        componentKindUid = component.componentKindUid,
        componentVersion = component.componentVersion
    )

    private fun rejected(
        rejection: PlayerResolutionRejection,
        contextFingerprint: String,
        context: PlayerResolutionContext,
        component: PlayerResolutionComponent<out PlayerCommandPayload>?
    ): PlayerResolutionOutcome.Rejected = PlayerResolutionOutcome.Rejected(
        rejection,
        PlayerResolutionEvidence(
            contextFingerprint = contextFingerprint,
            entropy = context.entropy,
            componentKindUid = component?.componentKindUid,
            componentVersion = component?.componentVersion
        )
    )

    private fun fail(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
}

internal enum class ResolutionReferenceStatus { RESOLVED, UNKNOWN, WRONG_CAMPAIGN }

private fun validateReferences(
    context: PlayerResolutionContext,
    refs: List<DomainRef>
): PlayerResolutionRejection? {
    refs.forEach { ref ->
        when (context.referenceStatus(ref)) {
            ResolutionReferenceStatus.RESOLVED -> Unit
            ResolutionReferenceStatus.UNKNOWN -> return PlayerResolutionRejection.create(
                PlayerResolutionRejectionReason.UNKNOWN_REFERENCE,
                listOf(ref)
            )
            ResolutionReferenceStatus.WRONG_CAMPAIGN -> return PlayerResolutionRejection.create(
                PlayerResolutionRejectionReason.WRONG_CAMPAIGN_REFERENCE,
                listOf(ref)
            )
        }
    }
    return null
}

private fun commandReferences(command: PlayerCommand<out PlayerCommandPayload>): List<DomainRef> = buildList {
    command.preconditions.forEach {
        when (it) {
            is ExpectedRecordVersion -> add(it.target)
            is ExpectedLifecycleState -> add(it.target)
        }
    }
    when (val payload = command.payload) {
        is TrainCommandPayload -> add(payload.focus)
        is UseResourceActionCommandPayload -> add(payload.resource)
        is RecoverCommandPayload -> payload.resource?.let(::add)
        is LearnSkillCommandPayload -> Unit
        is PracticeSkillCommandPayload -> Unit
        is LearnTechniqueCommandPayload -> Unit
        is UseTechniqueCommandPayload -> payload.target?.let(::add)
        is AcquireItemCommandPayload -> payload.sourceRef?.let(::add)
        is TransferItemCommandPayload -> { add(payload.item); add(payload.toParty) }
        is ConsumeItemCommandPayload -> add(payload.item)
        is EquipItemCommandPayload -> add(payload.item)
        is UnequipSlotCommandPayload -> Unit
        is TransferOwnershipCommandPayload -> { add(payload.subject); add(payload.toParty) }
        is TransferFundsCommandPayload -> Unit
        is AcquireAssetCommandPayload -> payload.requestedTermsRef?.let(::add)
        is EnterObligationCommandPayload -> add(payload.counterparty)
        is SettleObligationCommandPayload -> Unit
        is StartProjectCommandPayload -> {
            payload.beneficiaryRef?.let(::add)
            payload.targetRef?.let(::add)
        }
        is RecordProjectWorkCommandPayload -> {
            addAll(payload.evidenceRefs)
            addAll(payload.requestedResourceUse)
        }
        is SatisfyProjectRequirementCommandPayload -> addAll(payload.evidenceRefs)
        is AchieveProjectMilestoneCommandPayload -> {
            addAll(payload.evidenceRefs)
            payload.sourceWorkRef?.let(::add)
        }
        is ChangeProjectLifecycleCommandPayload -> Unit
        is CompleteProjectCommandPayload -> addAll(payload.completionEvidenceRefs)
        is CancelProjectCommandPayload -> Unit
        else -> Unit
    }
}

private fun draftReferences(draft: PlayerResolutionDraft): List<DomainRef> = buildList {
    draft.changes.forEach { change ->
        when (val payload = change.payload) {
            is StatChange -> { add(payload.subject); add(DomainRef("STAT", payload.statUid)) }
            is ResourceChange -> { add(payload.subject); add(DomainRef("RESOURCE", payload.resourceUid)) }
            is SkillChange -> { add(payload.subject); add(DomainRef("SKILL", payload.skillUid)) }
            is TechniqueChange -> { add(payload.subject); add(DomainRef("TECHNIQUE", payload.techniqueUid)) }
            is InnateChange -> { add(payload.subject); add(DomainRef("INNATE", payload.innateUid)) }
            is InventoryChange -> { add(payload.subject); add(DomainRef("ITEM_INSTANCE", payload.itemInstanceUid)) }
            is EquipmentChange -> {
                add(payload.subject)
                add(DomainRef("EQUIPMENT_SLOT", payload.slotUid))
                payload.itemInstanceUid?.let { add(DomainRef("ITEM_INSTANCE", it)) }
            }
            is FinancialChange -> Unit
            is AssetChange -> Unit
            is OwnershipChange -> Unit
            is ConditionChange -> { add(payload.subject); add(DomainRef("CONDITION", payload.conditionUid)) }
            is RuntimeChange -> { add(payload.subject); add(DomainRef("RUNTIME_COUNTER", payload.runtimeCounterUid)) }
            is DevelopmentProjectChange -> {
                add(DomainRef("PROJECT", payload.projectUid))
                addAll(payload.evidenceRefs)
            }
        }
    }
    draft.eventIntents.forEach { intent ->
        intent.actorRef?.let(::add)
        addAll(intent.targetRefs)
        val payload = intent.payload
        if (payload is DomainEffectEventIntentPayload) add(payload.subject)
    }
}

private fun toChangeSetPrecondition(precondition: CommandPrecondition): ChangeSetPrecondition = when (precondition) {
    is ExpectedRecordVersion -> ChangeSetExpectedRecordVersion(precondition.target, precondition.expectedVersion)
    is ExpectedLifecycleState -> ChangeSetExpectedLifecycleState(precondition.target, precondition.expectedStateUid)
}

private fun resolveTyped(
    component: PlayerResolutionComponent<out PlayerCommandPayload>,
    command: PlayerCommand<out PlayerCommandPayload>,
    context: PlayerResolutionContext
): PlayerResolutionComponentOutcome {
    if (!component.payloadType.isInstance(command.payload)) {
        throw PlayerDomainEngineStructuralException("COMMAND_RESOLUTION_COMPONENT_PAYLOAD_TYPE_MISMATCH")
    }
    @Suppress("UNCHECKED_CAST")
    val typedComponent = component as PlayerResolutionComponent<PlayerCommandPayload>
    @Suppress("UNCHECKED_CAST")
    val typedCommand = command as PlayerCommand<PlayerCommandPayload>
    return typedComponent.resolve(typedCommand, context)
}

private fun StringBuilder.appendToken(value: String) {
    append(value.length).append(':').append(value).append('|')
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
