package com.rpgos.app

import java.lang.reflect.Modifier
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

internal object PlayerResolutionReferenceKinds {
    const val FINANCIAL_ACCOUNT = "FINANCIAL_ACCOUNT"
    const val CURRENCY = "CURRENCY"
    const val OBLIGATION = "OBLIGATION"
    const val PROJECT = "PROJECT"
    const val PROJECT_REQUIREMENT = "PROJECT_REQUIREMENT"
    const val PROJECT_MILESTONE = "PROJECT_MILESTONE"
    const val SKILL = "SKILL"
    const val TECHNIQUE = "TECHNIQUE"
    const val PROGRESSION_DOMAIN = "PROGRESSION_DOMAIN"
}

data class ResolutionEntropyEvidence(
    val evidenceUid: String,
    val exactValue: Long
) {
    init { require(evidenceUid.isNotBlank()) }
    companion object {
        fun none(): ResolutionEntropyEvidence = ResolutionEntropyEvidence("RPGOS-ENTROPY:NONE", 0L)
    }
}

class PlayerResolutionContext private constructor(
    val campaignUid: String,
    val actor: CommandActorRef,
    knownReferences: Set<CampaignScopedDomainRef>,
    dependencyVersions: Map<String, String>,
    val entropy: ResolutionEntropyEvidence,
    val worldRuleMode: WorldRuleMode
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

    internal fun deterministicFingerprint(): String = WorldRuleCanonicalWriter.fingerprint("PLAYER_RESOLUTION_CONTEXT") {
        field("CONTEXT_VERSION", "1")
        field("CAMPAIGN_UID", campaignUid)
        section("ACTOR") {
            field("KIND_UID", actor.actorKindUid)
            field("UID", actor.actorUid)
        }
        val refs = knownReferences.sortedWith(compareBy({ it.campaignUid }, { it.ref.kindUid }, { it.ref.uid }))
        list("KNOWN_REFERENCES", refs) { scoped ->
            record("CAMPAIGN_SCOPED_DOMAIN_REF") {
                field("CAMPAIGN_UID", scoped.campaignUid)
                field("KIND_UID", scoped.ref.kindUid)
                field("UID", scoped.ref.uid)
            }
        }
        val dependencies = dependencyVersions.entries.toList()
        list("DEPENDENCY_VERSIONS", dependencies) { entry ->
            record("DEPENDENCY_VERSION") {
                field("KEY", entry.key)
                field("VALUE", entry.value)
            }
        }
        section("ENTROPY") {
            field("EVIDENCE_UID", entropy.evidenceUid)
            longField("EXACT_VALUE", entropy.exactValue)
        }
        section("WORLD_RULE_MODE") {
            when (val mode = worldRuleMode) {
                is WorldRuleMode.Bound -> {
                    field("MODE", "BOUND")
                    field("WORLD_PACK_UID", mode.binding.worldPackUid)
                    field("WORLD_PACK_VERSION", mode.binding.worldPackVersion)
                }
                UnboundGenericWorldRuleMode -> field("MODE", "UNBOUND_GENERIC")
            }
        }
    }

    companion object {
        fun create(
            campaignUid: String,
            actor: CommandActorRef,
            knownReferences: Set<CampaignScopedDomainRef>,
            dependencyVersions: Map<String, String> = emptyMap(),
            entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none(),
            worldRuleMode: WorldRuleMode
        ): PlayerResolutionContext = PlayerResolutionContext(
            campaignUid,
            actor,
            LinkedHashSet(knownReferences),
            TreeMap(dependencyVersions),
            entropy,
            worldRuleMode
        )

        internal fun createUnboundGeneric(
            campaignUid: String,
            actor: CommandActorRef,
            knownReferences: Set<CampaignScopedDomainRef>,
            dependencyVersions: Map<String, String> = emptyMap(),
            entropy: ResolutionEntropyEvidence = ResolutionEntropyEvidence.none()
        ): PlayerResolutionContext = create(
            campaignUid,
            actor,
            knownReferences,
            dependencyVersions,
            entropy,
            UnboundGenericWorldRuleMode
        )
    }
}

enum class PlayerResolutionRejectionReason(val reasonUid: String) {
    DOMAIN_REJECTED("RPGOS-RESOLUTION-REJECTION:DOMAIN_REJECTED"),
    CONTEXT_CAMPAIGN_MISMATCH("RPGOS-RESOLUTION-REJECTION:CONTEXT_CAMPAIGN_MISMATCH"),
    CONTEXT_ACTOR_MISMATCH("RPGOS-RESOLUTION-REJECTION:CONTEXT_ACTOR_MISMATCH"),
    UNKNOWN_REFERENCE("RPGOS-RESOLUTION-REJECTION:UNKNOWN_REFERENCE"),
    WRONG_CAMPAIGN_REFERENCE("RPGOS-RESOLUTION-REJECTION:WRONG_CAMPAIGN_REFERENCE"),
    WORLD_RULE_REJECTED("RPGOS-RESOLUTION-REJECTION:WORLD_RULE_REJECTED")
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

class PlayerResolutionEvidence(
    val contextFingerprint: String,
    val entropy: ResolutionEntropyEvidence,
    val componentKindUid: String?,
    val componentVersion: String?,
    worldRuleDecisions: List<WorldRuleDecisionRecord> = emptyList()
) {
    val worldRuleDecisions: List<WorldRuleDecisionRecord> = immutableList(worldRuleDecisions)

    override fun equals(other: Any?): Boolean = other is PlayerResolutionEvidence &&
        contextFingerprint == other.contextFingerprint && entropy == other.entropy &&
        componentKindUid == other.componentKindUid && componentVersion == other.componentVersion &&
        worldRuleDecisions == other.worldRuleDecisions
    override fun hashCode(): Int = arrayOf(
        contextFingerprint, entropy, componentKindUid, componentVersion, worldRuleDecisions
    ).contentHashCode()
}

sealed interface PlayerResolutionOutcome {
    data class Resolved(val proposal: PlayerChangeSet, val evidence: PlayerResolutionEvidence) : PlayerResolutionOutcome
    data class Rejected(val rejection: PlayerResolutionRejection, val evidence: PlayerResolutionEvidence) : PlayerResolutionOutcome
}

internal class PlayerResolutionDraft private constructor(
    changes: List<PlayerDomainChange>,
    eventIntents: List<PlayerEventIntent>,
    ledgerIntents: List<PlayerLedgerIntent>,
    warnings: List<ChangeSetWarning>,
    progressionStimuli: List<ProgressionStimulus>
) {
    val changes: List<PlayerDomainChange> = immutableList(changes)
    val eventIntents: List<PlayerEventIntent> = immutableList(eventIntents)
    val ledgerIntents: List<PlayerLedgerIntent> = immutableList(ledgerIntents)
    val warnings: List<ChangeSetWarning> = immutableList(warnings)
    val progressionStimuli: List<ProgressionStimulus> = immutableList(progressionStimuli)

    companion object {
        fun create(
            changes: List<PlayerDomainChange> = emptyList(),
            eventIntents: List<PlayerEventIntent> = emptyList(),
            ledgerIntents: List<PlayerLedgerIntent> = emptyList(),
            warnings: List<ChangeSetWarning> = emptyList(),
            progressionStimuli: List<ProgressionStimulus> = emptyList()
        ): PlayerResolutionDraft = PlayerResolutionDraft(changes, eventIntents, ledgerIntents, warnings, progressionStimuli)
    }
}

internal sealed interface PlayerResolutionComponentOutcome {
    data class Resolved(val draft: PlayerResolutionDraft) : PlayerResolutionComponentOutcome
    data class Rejected(val rejection: PlayerResolutionRejection) : PlayerResolutionComponentOutcome
}

/** Trusted internal Core extension point with read-only deterministic context only. */
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
            PlayerResolutionComponentStateValidator.validate(component)
            if (collected.put(component.commandKindUid, component) != null) {
                fail("DUPLICATE_COMMAND_RESOLUTION_COMPONENT")
            }
        }
        byKind = Collections.unmodifiableMap(LinkedHashMap(collected))
        commandKindUids = Collections.unmodifiableSet(LinkedHashSet(collected.keys))
    }

    fun componentFor(commandKindUid: String): PlayerResolutionComponent<out PlayerCommandPayload>? = byKind[commandKindUid]

    companion object {
        fun of(components: List<PlayerResolutionComponent<out PlayerCommandPayload>>): PlayerResolutionComponentRegistry =
            PlayerResolutionComponentRegistry(ArrayList(components))
        fun empty(): PlayerResolutionComponentRegistry = PlayerResolutionComponentRegistry(emptyList())
    }

    private fun fail(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
}

class PlayerDomainEngine internal constructor(
    private val componentRegistry: PlayerResolutionComponentRegistry,
    private val commandRegistry: PlayerCommandKindRegistry = PlayerCommandKindRegistry.core(),
    private val changeRegistry: TypedPlayerChangeRegistry = TypedPlayerChangeRegistry.core(),
    private val worldRuleRegistry: WorldRuleProviderRegistry = WorldRuleProviderRegistry.empty(),
    private val worldPackAuthority: WorldPackAuthorityResolver = WorldPackAuthoritySnapshot.empty(),
    private val progressionEngine: ProgressionEngine = ProgressionEngine(),
    private val invariantSnapshotResolver: PlayerInvariantSnapshotResolver = PlayerInvariantSnapshotResolver.empty()
) {
    fun resolve(
        command: PlayerCommand<out PlayerCommandPayload>,
        context: PlayerResolutionContext
    ): PlayerResolutionOutcome {
        commandRegistry.validate(command)
        val canonicalCommand = commandRegistry.decode(commandRegistry.encode(command))
        val commandFingerprint = commandRegistry.fingerprint(canonicalCommand)
        val contextFingerprint = context.deterministicFingerprint()
        val ruleTrace = ArrayList<WorldRuleDecisionRecord>()

        if (context.campaignUid != canonicalCommand.campaignUid) {
            return rejected(
                PlayerResolutionRejection.create(PlayerResolutionRejectionReason.CONTEXT_CAMPAIGN_MISMATCH),
                contextFingerprint, context, null, ruleTrace
            )
        }
        if (context.actor != canonicalCommand.actor) {
            return rejected(
                PlayerResolutionRejection.create(PlayerResolutionRejectionReason.CONTEXT_ACTOR_MISMATCH),
                contextFingerprint, context, null, ruleTrace
            )
        }

        validateReferences(context, commandReferences(canonicalCommand))?.let {
            return rejected(it, contextFingerprint, context, null, ruleTrace)
        }

        validateWorldRuleAuthority(context)

        evaluateWorldRules(
            stage = WorldRuleEvaluationStage.COMMAND_PRECHECK,
            canonicalCommand = canonicalCommand,
            commandFingerprint = commandFingerprint,
            context = context,
            contextFingerprint = contextFingerprint,
            effects = null
        )?.let { evaluation ->
            ruleTrace += evaluation.record
            if (!evaluation.record.allowed) {
                return rejected(
                    PlayerResolutionRejection.create(
                        PlayerResolutionRejectionReason.WORLD_RULE_REJECTED,
                        detailUid = evaluation.record.reasonUid
                    ),
                    contextFingerprint, context, null, ruleTrace
                )
            }
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

        return when (outcome) {
            is PlayerResolutionComponentOutcome.Rejected ->
                PlayerResolutionOutcome.Rejected(
                    outcome.rejection,
                    evidence(contextFingerprint, context, component, ruleTrace)
                )

            is PlayerResolutionComponentOutcome.Resolved -> {
                validateReferences(context, draftReferences(outcome.draft))?.let {
                    return PlayerResolutionOutcome.Rejected(
                        it,
                        evidence(contextFingerprint, context, component, ruleTrace)
                    )
                }

                val augmentedDraft = try {
                    augmentWithProgression(canonicalCommand, commandFingerprint, context, outcome.draft)
                } catch (e: PlayerDomainEngineStructuralException) {
                    throw e
                } catch (e: ProgressionStructuralException) {
                    throw PlayerDomainEngineStructuralException("PROGRESSION_ENGINE_FAILURE:${e.code}", e)
                } catch (e: IllegalArgumentException) {
                    throw PlayerDomainEngineStructuralException("PROGRESSION_ENGINE_FAILURE", e)
                } catch (e: Throwable) {
                    throw PlayerDomainEngineStructuralException("PROGRESSION_ENGINE_FAILURE", e)
                }

                validateReferences(context, draftReferences(augmentedDraft))?.let {
                    return PlayerResolutionOutcome.Rejected(
                        it,
                        evidence(contextFingerprint, context, component, ruleTrace)
                    )
                }

                validateCanonDivergenceAuthority(context, augmentedDraft)
                val effectSnapshot = WorldRuleEffectSnapshot.create(augmentedDraft)
                evaluateWorldRules(
                    stage = WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK,
                    canonicalCommand = canonicalCommand,
                    commandFingerprint = commandFingerprint,
                    context = context,
                    contextFingerprint = contextFingerprint,
                    effects = effectSnapshot
                )?.let { evaluation ->
                    ruleTrace += evaluation.record
                    if (!evaluation.record.allowed) {
                        return PlayerResolutionOutcome.Rejected(
                            PlayerResolutionRejection.create(
                                PlayerResolutionRejectionReason.WORLD_RULE_REJECTED,
                                detailUid = evaluation.record.reasonUid
                            ),
                            evidence(contextFingerprint, context, component, ruleTrace)
                        )
                    }
                }

                val proposal = assembleProposal(
                    canonicalCommand,
                    contextFingerprint,
                    component,
                    augmentedDraft,
                    ruleTrace
                )
                PlayerChangeSetValidator.validate(proposal, changeRegistry)
                val resolutionEvidence = evidence(contextFingerprint, context, component, ruleTrace)
                validatePlayerInvariants(proposal, resolutionEvidence)
            }
        }
    }

    private fun validateCanonDivergenceAuthority(context:PlayerResolutionContext,draft:PlayerResolutionDraft){
        val divergent=draft.changes.mapNotNull{change->
            val truth=change.payload as? CampaignTruthChange ?: return@mapNotNull null
            truth.canonDivergence?.let{truth to it}
        }
        if(divergent.isEmpty())return
        val binding=(context.worldRuleMode as? WorldRuleMode.Bound)?.binding?:fail("CANON_DIVERGENCE_REQUIRES_BOUND_WORLD_PACK")
        val authoritative=try{worldPackAuthority.bindingForCampaign(context.campaignUid)}catch(e:Throwable){throw PlayerDomainEngineStructuralException("WORLD_RULE_AUTHORITY_READ_FAILED",e)}
            ?:fail("CANON_DIVERGENCE_WORLD_PACK_AUTHORITY_MISSING")
        if(authoritative!=binding)fail("CANON_DIVERGENCE_WORLD_PACK_AUTHORITY_MISMATCH")
        val provider=worldRuleRegistry.providerFor(binding)?:fail("CANON_DIVERGENCE_WORLD_RULE_PROVIDER_MISSING")
        divergent.forEach{(truth,spec)->
            if(spec.provenanceStatus!=HistoricalProvenanceStatus.RECORDED)fail("CANON_DIVERGENCE_GAMEPLAY_PROVENANCE_NOT_RECORDED")
            if(spec.worldPackUid!=binding.worldPackUid)fail("CANON_DIVERGENCE_WORLD_PACK_UID_MISMATCH")
            if(spec.worldPackVersion!=binding.worldPackVersion)fail("CANON_DIVERGENCE_WORLD_PACK_VERSION_MISMATCH")
            val expectation=try{provider.canonicalExpectation(spec.canonicalReference)}catch(e:Throwable){throw PlayerDomainEngineStructuralException("CANON_DIVERGENCE_EXPECTATION_LOOKUP_FAILED",e)}
                ?:fail("CANON_DIVERGENCE_EXPECTATION_NOT_FOUND")
            if(expectation.canonicalReference!=spec.canonicalReference)fail("CANON_DIVERGENCE_EXPECTATION_REFERENCE_MISMATCH")
            if(expectation.kind!=spec.kind)fail("CANON_DIVERGENCE_EXPECTATION_KIND_MISMATCH")
            if(expectation.expectedCanonicalValue!=spec.expectedCanonicalValue)fail("CANON_DIVERGENCE_EXPECTED_VALUE_MISMATCH")
            val actual=truth.objectValue?:fail("CANON_DIVERGENCE_ACTUAL_VALUE_NOT_BINDABLE")
            if(actual!=spec.actualCampaignValue)fail("CANON_DIVERGENCE_ACTUAL_VALUE_MISMATCH")
        }
    }

    private fun validatePlayerInvariants(
        proposal: PlayerChangeSet,
        resolutionEvidence: PlayerResolutionEvidence
    ): PlayerResolutionOutcome {
        val snapshot = try {
            invariantSnapshotResolver.snapshotFor(proposal.campaignUid, proposal.actor.actorUid)
        } catch (e: PlayerDomainEngineStructuralException) {
            throw e
        } catch (e: Throwable) {
            throw PlayerDomainEngineStructuralException("PLAYER_INVARIANT_SNAPSHOT_READ_FAILED", e)
        }
        return when (val validation = PlayerInvariantValidator.validate(proposal, snapshot)) {
            PlayerInvariantValidationResult.Valid -> PlayerResolutionOutcome.Resolved(proposal, resolutionEvidence)
            is PlayerInvariantValidationResult.Invalid -> PlayerResolutionOutcome.Rejected(
                PlayerResolutionRejection.create(
                    PlayerResolutionRejectionReason.DOMAIN_REJECTED,
                    detailUid = validation.violations.first().detailUid
                ),
                resolutionEvidence
            )
        }
    }

    private fun augmentWithProgression(
        command: PlayerCommand<out PlayerCommandPayload>,
        commandFingerprint: String,
        context: PlayerResolutionContext,
        draft: PlayerResolutionDraft
    ): PlayerResolutionDraft {
        if (draft.progressionStimuli.isEmpty()) return draft
        val seenStimulusUids = HashSet<String>()
        val generatedChanges = ArrayList<PlayerDomainChange>()
        val generatedLedgerIntents = ArrayList<PlayerLedgerIntent>()
        draft.progressionStimuli.forEach { stimulus ->
            if (!seenStimulusUids.add(stimulus.stimulusUid)) fail("DUPLICATE_PROGRESSION_STIMULUS_UID")
            val input = progressionInput(command, commandFingerprint, context, stimulus)
            val result = progressionEngine.evaluate(input)
            result.grants.forEach { grant ->
                generatedChanges += progressionGrantChange(stimulus.subject, grant)
            }
            generatedLedgerIntents += result.ledgerIntents
        }
        return PlayerResolutionDraft.create(
            changes = draft.changes + generatedChanges,
            eventIntents = draft.eventIntents,
            ledgerIntents = draft.ledgerIntents + generatedLedgerIntents,
            warnings = draft.warnings,
            progressionStimuli = draft.progressionStimuli
        )
    }

    private fun progressionInput(
        command: PlayerCommand<out PlayerCommandPayload>,
        commandFingerprint: String,
        context: PlayerResolutionContext,
        stimulus: ProgressionStimulus
    ): ProgressionEvaluationInput {
        val binding = when (val mode = context.worldRuleMode) {
            is WorldRuleMode.Bound -> mode.binding
            UnboundGenericWorldRuleMode -> null
        }
        if (stimulus.expectedWorldPackUid != null) {
            if (binding == null || binding.worldPackUid != stimulus.expectedWorldPackUid ||
                binding.worldPackVersion != stimulus.expectedWorldPackVersion) fail("PROGRESSION_WORLD_PACK_MISMATCH")
        }
        if (stimulus.progressionDomainUid != null) {
            if (binding == null || stimulus.progressionDomainWorldPackUid == null ||
                stimulus.progressionDomainWorldPackUid != binding.worldPackUid) fail("PROGRESSION_DOMAIN_WORLD_PACK_MISMATCH")
        } else if (stimulus.progressionDomainWorldPackUid != null) {
            fail("PROGRESSION_DOMAIN_WORLD_PACK_WITHOUT_DOMAIN")
        }
        val dependencies = TreeMap(context.dependencyVersions)
        stimulus.dependencyVersions.forEach { (key, value) ->
            val existing = dependencies[key]
            if (existing != null && existing != value) fail("PROGRESSION_DEPENDENCY_VERSION_MISMATCH")
            dependencies[key] = value
        }
        return ProgressionEvaluationInput.create(
            campaignUid = context.campaignUid,
            characterUid = stimulus.subject.uid,
            sourceTypeUid = stimulus.sourceTypeUid,
            sourceChannelUid = stimulus.sourceChannelUid,
            stimulusUid = stimulus.stimulusUid,
            sourceCommandUid = command.commandUid,
            commandKindUid = command.commandKindUid,
            commandFingerprint = commandFingerprint,
            targetKindUid = stimulus.targetKindUid,
            targetUid = stimulus.targetUid,
            progressionDomainUid = stimulus.progressionDomainUid,
            targetValueEvidence = stimulus.targetValueEvidence,
            progressSemanticsUid = stimulus.progressSemanticsUid,
            progressSemanticsVersion = stimulus.progressSemanticsVersion,
            effortUnits = stimulus.effortUnits,
            durationUnits = stimulus.durationUnits,
            intensity = stimulus.intensity,
            methodUid = stimulus.methodUid,
            calculationFactors = stimulus.calculationFactors,
            talentEvidence = stimulus.talentEvidence,
            potentialEvidence = stimulus.potentialEvidence,
            worldPackUid = binding?.worldPackUid,
            worldPackVersion = binding?.worldPackVersion,
            worldPackBindingIdentity = progressionWorldPackBindingIdentity(binding),
            progressionPolicyUid = stimulus.progressionPolicyUid,
            progressionPolicyVersion = stimulus.progressionPolicyVersion,
            progressionEngineUid = progressionEngine.engineUid,
            progressionEngineVersion = progressionEngine.engineVersion,
            dependencyVersions = dependencies
        )
    }

    private fun progressionGrantChange(subject: DomainRef, grant: ProgressionGrant): PlayerDomainChange {
        if (subject.uid != grant.characterUid) fail("PROGRESSION_GRANT_SUBJECT_MISMATCH")
        val payload: PlayerDomainChangePayload
        val kindUid: String
        when (grant.targetKindUid) {
            ProgressionTargetKinds.STAT -> {
                kindUid = PlayerChangeKinds.STAT
                payload = StatChange(subject, grant.targetUid, ExactLongDelta.of(grant.grantUnits))
            }
            ProgressionTargetKinds.SKILL -> {
                kindUid = PlayerChangeKinds.SKILL
                payload = SkillChange(subject, grant.targetUid, ExactLongDelta.of(grant.grantUnits))
            }
            ProgressionTargetKinds.TECHNIQUE -> {
                kindUid = PlayerChangeKinds.TECHNIQUE
                payload = TechniqueChange(subject, grant.targetUid, ExactLongDelta.of(grant.grantUnits))
            }
            else -> fail("UNSUPPORTED_PROGRESSION_TARGET")
        }
        return PlayerDomainChange.create(
            changeUid = grant.causalChangeUid,
            changeKindUid = kindUid,
            payload = payload,
            sourceRuleUid = grant.policyUid,
            registry = changeRegistry
        )
    }

    private fun validateWorldRuleAuthority(context: PlayerResolutionContext) {
        val authoritative = try {
            worldPackAuthority.bindingForCampaign(context.campaignUid)
        } catch (e: PlayerDomainEngineStructuralException) {
            throw e
        } catch (e: Throwable) {
            throw PlayerDomainEngineStructuralException("WORLD_RULE_AUTHORITY_READ_FAILED", e)
        }
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
        stage: WorldRuleEvaluationStage,
        canonicalCommand: PlayerCommand<out PlayerCommandPayload>,
        commandFingerprint: String,
        context: PlayerResolutionContext,
        contextFingerprint: String,
        effects: WorldRuleEffectSnapshot?
    ): WorldRuleEvaluation? {
        val binding = when (val mode = context.worldRuleMode) {
            is WorldRuleMode.Bound -> mode.binding
            UnboundGenericWorldRuleMode -> return null
        }
        val provider = worldRuleRegistry.providerFor(binding)
            ?: fail("WORLD_RULE_PROVIDER_MISSING")
        val providerCommand = commandRegistry.decode(commandRegistry.encode(canonicalCommand))
        val request = when (stage) {
            WorldRuleEvaluationStage.COMMAND_PRECHECK -> WorldRuleRequest.commandPrecheck(
                binding, context.campaignUid, context.actor, providerCommand,
                commandFingerprint, contextFingerprint
            )
            WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK -> WorldRuleRequest.draftEffectCheck(
                binding, context.campaignUid, context.actor, providerCommand,
                commandFingerprint, contextFingerprint,
                effects ?: fail("WORLD_RULE_DRAFT_EFFECTS_MISSING")
            )
        }
        val effectsFingerprint = effects?.deterministicFingerprint()
        val decision = try {
            provider.evaluate(request)
        } catch (e: PlayerDomainEngineStructuralException) {
            throw e
        } catch (e: Throwable) {
            throw PlayerDomainEngineStructuralException("WORLD_RULE_PROVIDER_FAILURE", e)
        }
        if (commandRegistry.fingerprint(providerCommand) != commandFingerprint) {
            fail("WORLD_RULE_PROVIDER_INPUT_MUTATED")
        }
        if (effects != null && effects.deterministicFingerprint() != effectsFingerprint) {
            fail("WORLD_RULE_PROVIDER_INPUT_MUTATED")
        }
        val record = try {
            WorldRuleDecisionRecord.create(provider, request, decision)
        } catch (e: IllegalArgumentException) {
            throw PlayerDomainEngineStructuralException("WORLD_RULE_PROVIDER_MALFORMED_DECISION", e)
        }
        return WorldRuleEvaluation(record)
    }

    private fun assembleProposal(
        command: PlayerCommand<out PlayerCommandPayload>,
        contextFingerprint: String,
        component: PlayerResolutionComponent<out PlayerCommandPayload>,
        draft: PlayerResolutionDraft,
        ruleDecisions: List<WorldRuleDecisionRecord>
    ): PlayerChangeSet {
        val changeSetUid = "RPGOS-CS18:" + WorldRuleCanonicalWriter.fingerprint("PLAYER_DOMAIN_PROPOSAL") {
            field("COMMAND_ENCODING", commandRegistry.encode(command))
            field("CONTEXT_FINGERPRINT", contextFingerprint)
            section("COMPONENT") {
                field("KIND_UID", component.componentKindUid)
                field("VERSION", component.componentVersion)
            }
            list("WORLD_RULE_DECISIONS", ruleDecisions) { decision ->
                record("WORLD_RULE_DECISION_FINGERPRINT") {
                    field("FINGERPRINT", decision.decisionFingerprint)
                }
            }
        }
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
                resolverVersion = component.componentVersion,
                worldRuleProviderUid = ruleDecisions.lastOrNull()?.providerUid
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
        component: PlayerResolutionComponent<out PlayerCommandPayload>,
        ruleDecisions: List<WorldRuleDecisionRecord>
    ): PlayerResolutionEvidence = PlayerResolutionEvidence(
        contextFingerprint,
        context.entropy,
        component.componentKindUid,
        component.componentVersion,
        ruleDecisions
    )

    private fun rejected(
        rejection: PlayerResolutionRejection,
        contextFingerprint: String,
        context: PlayerResolutionContext,
        component: PlayerResolutionComponent<out PlayerCommandPayload>?,
        ruleDecisions: List<WorldRuleDecisionRecord>
    ): PlayerResolutionOutcome.Rejected = PlayerResolutionOutcome.Rejected(
        rejection,
        PlayerResolutionEvidence(
            contextFingerprint,
            context.entropy,
            component?.componentKindUid,
            component?.componentVersion,
            ruleDecisions
        )
    )

    private fun fail(code: String): Nothing = throw PlayerDomainEngineStructuralException(code)
}

private data class WorldRuleEvaluation(val record: WorldRuleDecisionRecord)

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

internal fun commandReferences(command: PlayerCommand<out PlayerCommandPayload>): List<DomainRef> = buildList {
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
        is PracticeSkillCommandPayload -> add(DomainRef(PlayerResolutionReferenceKinds.SKILL, payload.skillUid))
        is LearnTechniqueCommandPayload -> Unit
        is UseTechniqueCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.TECHNIQUE, payload.techniqueUid))
            payload.target?.let(::add)
        }
        is AcquireItemCommandPayload -> payload.sourceRef?.let(::add)
        is TransferItemCommandPayload -> { add(payload.item); add(payload.toParty) }
        is ConsumeItemCommandPayload -> add(payload.item)
        is EquipItemCommandPayload -> add(payload.item)
        is UnequipSlotCommandPayload -> Unit
        is TransferOwnershipCommandPayload -> { add(payload.subject); add(payload.toParty) }
        is TransferFundsCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.fromAccountUid))
            add(DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.toAccountUid))
            add(DomainRef(PlayerResolutionReferenceKinds.CURRENCY, payload.currencyUid))
        }
        is AcquireAssetCommandPayload -> payload.requestedTermsRef?.let(::add)
        is EnterObligationCommandPayload -> {
            add(payload.counterparty)
            payload.currencyUid?.let { add(DomainRef(PlayerResolutionReferenceKinds.CURRENCY, it)) }
        }
        is SettleObligationCommandPayload -> add(DomainRef(PlayerResolutionReferenceKinds.OBLIGATION, payload.obligationUid))
        is StartProjectCommandPayload -> {
            payload.beneficiaryRef?.let(::add)
            payload.targetRef?.let(::add)
        }
        is RecordProjectWorkCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
            addAll(payload.evidenceRefs)
            addAll(payload.requestedResourceUse)
        }
        is SatisfyProjectRequirementCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT_REQUIREMENT, payload.requirementUid))
            addAll(payload.evidenceRefs)
        }
        is AchieveProjectMilestoneCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT_MILESTONE, payload.milestoneUid))
            addAll(payload.evidenceRefs)
            payload.sourceWorkRef?.let(::add)
        }
        is ChangeProjectLifecycleCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
            payload.successorProjectUid?.let { add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, it)) }
        }
        is CompleteProjectCommandPayload -> {
            add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
            addAll(payload.completionEvidenceRefs)
        }
        is CancelProjectCommandPayload -> add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
        else -> Unit
    }
}

internal fun draftReferences(draft: PlayerResolutionDraft): List<DomainRef> = buildList {
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
                payload.itemInstanceUid?.let { add(DomainRef("ITEM_INSTANCE", it)) }
            }
            is FinancialChange -> {
                add(DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.fromAccountUid))
                add(DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.toAccountUid))
                add(DomainRef(PlayerResolutionReferenceKinds.CURRENCY, payload.currencyUid))
            }
            is AssetChange -> Unit
            is OwnershipChange -> {
                add(DomainRef(payload.asset.assetKindUid, payload.asset.assetUid))
                add(DomainRef(payload.fromOwner.ownerKindUid, payload.fromOwner.ownerUid))
                add(DomainRef(payload.toOwner.ownerKindUid, payload.toOwner.ownerUid))
            }
            is CampaignTruthChange -> Unit
            is ConditionChange -> { add(payload.subject); add(DomainRef("CONDITION", payload.conditionUid)) }
            is RuntimeChange -> { add(payload.subject); add(DomainRef("RUNTIME_COUNTER", payload.runtimeCounterUid)) }
            is DevelopmentProjectChange -> {
                add(DomainRef(PlayerResolutionReferenceKinds.PROJECT, payload.projectUid))
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
    draft.ledgerIntents.forEach { intent ->
        when (val payload = intent.payload) {
            is FinancialTransferLedgerIntentPayload -> {
                add(DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.fromAccountUid))
                add(DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, payload.toAccountUid))
                add(DomainRef(PlayerResolutionReferenceKinds.CURRENCY, payload.currencyUid))
            }
            is ProgressionLedgerIntentPayload -> {
                add(DomainRef("PLAYER", payload.characterUid))
                add(DomainRef(payload.targetKindUid, payload.targetUid))
                payload.progressionDomainUid?.let { add(DomainRef(PlayerResolutionReferenceKinds.PROGRESSION_DOMAIN, it)) }
            }
        }
    }
    draft.progressionStimuli.forEach { stimulus ->
        add(stimulus.subject)
        add(DomainRef(stimulus.targetKindUid, stimulus.targetUid))
        stimulus.progressionDomainUid?.let { add(DomainRef(PlayerResolutionReferenceKinds.PROGRESSION_DOMAIN, it)) }
        addAll(stimulus.evidenceRefs)
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
