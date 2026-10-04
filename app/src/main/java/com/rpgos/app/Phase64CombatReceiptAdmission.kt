package com.rpgos.app

internal enum class Phase64CombatReceiptAdmissionCode {
    IDENTITY, RULE, INITIAL_TERMINAL, PARAMETERS, EVIDENCE, CLOCK, BRAIN_CHAIN,
    COMPLETION, OPTION, PENDING, PROOF_BINDING, MECHANICS_BINDING, CONSEQUENCES, MALFORMED;

    val uid get() = "P64:COMBAT_RECEIPT_ADMISSION_$name"
}

internal class Phase64CombatReceiptAdmissionException(val code: Phase64CombatReceiptAdmissionCode) :
    IllegalArgumentException(code.uid)

/** Pure preflight, before any turn writes. This acknowledges existing owner results, never
 * authorizes combat. The caller supplies the canonical pre-turn brain/rule/clock baseline
 * and separately excludes an already persisted receipt UID in the same transaction. */
internal object Phase64CombatReceiptAdmission {
    fun validate(identity: TurnTransactionIdentity, change: BackgroundProcessChange,
        rule: BackgroundProcessDefinition, ruleFingerprint: String, canonicalBrain: NpcBrainState,
        activePlayerUid: String, clock: TemporalStateChange, set: PlayerChangeSet) {
        try {
            validateCaptured(identity, change, rule, ruleFingerprint, canonicalBrain, activePlayerUid, clock, set)
        } catch (failure: Phase64CombatReceiptAdmissionException) {
            throw failure
        } catch (_: IllegalArgumentException) {
            reject(Phase64CombatReceiptAdmissionCode.MALFORMED)
        } catch (_: IllegalStateException) {
            reject(Phase64CombatReceiptAdmissionCode.MALFORMED)
        } catch (_: ArithmeticException) {
            reject(Phase64CombatReceiptAdmissionCode.MALFORMED)
        }
    }

    private fun validateCaptured(identity: TurnTransactionIdentity, change: BackgroundProcessChange,
        rule: BackgroundProcessDefinition, ruleFingerprint: String, canonicalBrain: NpcBrainState,
        activePlayerUid: String, clock: TemporalStateChange, set: PlayerChangeSet) {
        val process = change.process
        val evidence = change.evidence
        val actor = process.actor
        check(identity.campaignUid == change.campaignUid && set.campaignUid == change.campaignUid &&
            canonicalBrain.campaignUid == change.campaignUid && clock.campaignUid == change.campaignUid &&
            set.sourceCommandUid == identity.commandUid && set.provenance.sourceCommandUid == identity.commandUid &&
            canonicalBrain.actor == actor && activePlayerUid.isNotBlank() && actor.kindUid != "PLAYER" &&
            actor.uid != activePlayerUid, Phase64CombatReceiptAdmissionCode.IDENTITY)
        check(rule.uid == Phase64CombatReceiptFactory.RULE_UID && rule.version == Phase64CombatReceiptFactory.RULE_VERSION &&
            rule.domain == "CONFLICT" && rule.operation == "COMBAT" &&
            rule.parameters == mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER) &&
            ruleFingerprint.isNotBlank() && process.definitionUid == rule.uid && process.definitionVersion == rule.version,
            Phase64CombatReceiptAdmissionCode.RULE)
        check(change.expectedVersion == 0L && process.version == 1L && process.status == BackgroundProcessStatus.COMPLETED &&
            process.progressUnits == 1L && process.reasonUid == null && evidence.reasonUid == null &&
            process.dependencyUids.isEmpty() && change.deadlineAdds.isEmpty() && change.deadlineRemovals.isEmpty() &&
            change.ownerDelegations.isEmpty(), Phase64CombatReceiptAdmissionCode.INITIAL_TERMINAL)

        val parameters = process.parameters
        check(parameters.keys == PARAMETER_KEYS && parameters.values.all(String::isNotBlank), Phase64CombatReceiptAdmissionCode.PARAMETERS)
        val planUid = parameters.getValue("decision_uid")
        val abilityUid = parameters.getValue("ability_uid")
        val contract = parameters.getValue("p64_ability_contract")
        val optionUid = parameters.getValue("p64_completion_option_uid")
        listOf(planUid, abilityUid, optionUid).forEach(::npcUid)
        val target = DomainRef(parameters.getValue("target_kind_uid"), parameters.getValue("target_uid"))
        val processUid = Phase64CombatReceiptFactory.receiptUid(change.campaignUid, planUid, rule.uid, rule.version)
        val sourceUid = Phase64CombatReceiptFactory.completionSourceUid(change.campaignUid, planUid, rule.uid, rule.version)
        val eventUid = Phase64CombatReceiptFactory.logicalEventUid(change.campaignUid, planUid, rule.uid, rule.version)
        check(target != actor && contract.matches(Regex("[0-9a-f]{64}")) &&
            process.uid == processUid && parameters.getValue("p64_process_uid") == processUid &&
            parameters.getValue("p64_completion_proof_uid") == sourceUid && parameters.getValue("p64_logical_event_uid") == eventUid &&
            parameters.getValue("p64_completion_owner") == NpcActionProcess.OWNER &&
            parameters.getValue("p64_completion_command_uid") == identity.commandUid &&
            parameters.getValue("p64_rule_uid") == rule.uid && parameters.getValue("p64_rule_version") == rule.version.toString() &&
            parameters.getValue("p64_rule_fingerprint") == ruleFingerprint &&
            parameters.getValue("p64_started_at_ms") == process.startedAt.milliseconds.toString() &&
            parameters.getValue("p64_event_at_ms") == process.due.milliseconds.toString(), Phase64CombatReceiptAdmissionCode.PARAMETERS)
        check(evidence.uid == Phase64CombatReceiptFactory.evidenceUid(change.campaignUid, planUid, rule.uid, rule.version) &&
            evidence.processUid == processUid && evidence.ruleUid == rule.uid && evidence.ruleVersion == rule.version &&
            evidence.at == process.due && evidence.sourceUids.size in 1..256 &&
            evidence.sourceUids.distinct().size == evidence.sourceUids.size, Phase64CombatReceiptAdmissionCode.EVIDENCE)

        check(set.changes.mapNotNull { it.payload as? TemporalStateChange } == listOf(clock) &&
            set.changes.count { it.payload == change } == 1 &&
            process.startedAt.milliseconds >= 0 && process.due > process.startedAt &&
            clock.expectedTime.milliseconds >= 0 && process.due > clock.expectedTime && process.due <= clock.proposedTime,
            Phase64CombatReceiptAdmissionCode.CLOCK)
        val state = Phase60ProcessStateCodec.decode(clock.processStatesCanonical).singleOrNull { it.ownerUid == NpcActionProcess.OWNER }
        check(state != null, Phase64CombatReceiptAdmissionCode.PENDING)
        check(NpcActionProcess.decode(state).none { it.planUid == planUid } &&
            Phase60DeadlineCodec.decode(clock.deadlinesCanonical).none {
                it.uid == "P62:ACTION:${phase60Hash("${actor.kindUid}|${actor.uid}|$planUid")}"
            }, Phase64CombatReceiptAdmissionCode.PENDING)

        val allBrains = set.changes.mapNotNull { it.payload as? NpcBrainChange }
        check(validNpcBrainChains(allBrains) && allBrains.all { it.campaignUid == change.campaignUid &&
            it.historyGenerationUid == change.historyGenerationUid }, Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN)
        val actorBrains = allBrains.filter { it.actor == actor }
        // An actor born during this turn has no persisted brain. Its caller supplies the
        // decoded first genesis, not a invented revision-zero state; do not apply it twice.
        val genesis = actorBrains.firstOrNull()?.takeIf { it.expectedVersion == 0L }
        val overlay = if (genesis == null) actorBrains else {
            check(genesis.ruleUid == NpcBrainRules.GENESIS.uid && genesis.ruleVersion == NpcBrainRules.GENESIS.version &&
                NpcBrainCodec.decode(genesis.stateCanonical) == canonicalBrain, Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN)
            NpcBrainOwner.validateTransition(null, canonicalBrain, NpcBrainRules.GENESIS, genesis.causes)
            actorBrains.drop(1)
        }
        var brain = canonicalBrain
        var completedPrior: NpcPlan? = null
        var completion: NpcBrainChange? = null
        val scope = TemporalScope(change.campaignUid, change.historyGenerationUid, 0, ruleFingerprint)
        for (entry in overlay) {
            val prior = brain.plans.singleOrNull { it.uid == planUid }
            brain = guarded(Phase64CombatReceiptAdmissionCode.BRAIN_CHAIN) { applyNpcBrainOverlay(brain, scope, listOf(entry)) }
            val after = brain.plans.singleOrNull { it.uid == planUid }
            if (prior?.lifecycle == NpcPlanLifecycle.RUNNING && after?.lifecycle == NpcPlanLifecycle.COMPLETED) {
                val cause = NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, identity.commandUid)
                check(completion == null && entry.ruleUid in setOf(NpcBrainRules.PLANNING.uid, NpcBrainRules.EXECUTION_COMPLETION.uid) &&
                    entry.ruleVersion == 1 && entry.causes == listOf(cause) &&
                    after == prior.copy(lifecycle = NpcPlanLifecycle.COMPLETED, nextEvaluationAt = null, cause = cause),
                    Phase64CombatReceiptAdmissionCode.COMPLETION)
                completedPrior = prior
                completion = entry
            }
        }
        val prior = completedPrior ?: reject(Phase64CombatReceiptAdmissionCode.COMPLETION)
        val finished = completion ?: reject(Phase64CombatReceiptAdmissionCode.COMPLETION)
        check(brain.plans.singleOrNull { it.uid == planUid }?.lifecycle == NpcPlanLifecycle.COMPLETED &&
            prior.startedAt == process.startedAt && prior.nextEvaluationAt == process.due, Phase64CombatReceiptAdmissionCode.COMPLETION)
        val canonicalOption = "P62:OPTION:${phase60Hash("$actor|${prior.goalUid}|$abilityUid|$target|$contract").take(32)}"
        check(prior.actionUid == canonicalOption && optionUid == canonicalOption, Phase64CombatReceiptAdmissionCode.OPTION)
        check(set.changes.singleOrNull { it.payload == finished }?.sourceRuleUid == finished.ruleUid,
            Phase64CombatReceiptAdmissionCode.PROOF_BINDING)

        val sourceProofs = evidence.sourceUids.filter { it.startsWith("P60:PROCESS:") }.toSet()
        check(sourceProofs.isNotEmpty() && sourceProofs.all { it.matches(Regex("P60:PROCESS:[0-9a-f]{64}:.+")) },
            Phase64CombatReceiptAdmissionCode.PROOF_BINDING)
        val mechanics = set.changes.filter { it.sourceRuleUid in sourceProofs }
        check(sourceProofs.all { proof -> mechanics.any { it.sourceRuleUid == proof } } && mechanics.isNotEmpty() &&
            mechanics.all { subject(it.payload) != null }, Phase64CombatReceiptAdmissionCode.PROOF_BINDING)
        check(mechanics.any { subject(it.payload) == target } && mechanics.all { wrapper ->
            val subject = subject(wrapper.payload)!!
            (subject != actor || wrapper.payload is ResourceChange && wrapper.payload.delta.units < 0) &&
                set.eventIntents.any { intent ->
                    val payload = intent.payload as? DomainEffectEventIntentPayload
                    intent.eventKindUid == PlayerEventIntentKinds.DOMAIN_EFFECT && intent.actorRef == actor &&
                        intent.causalChangeUids == listOf(wrapper.changeUid) && intent.targetRefs == listOf(subject) && payload?.subject == subject
                }
        }, Phase64CombatReceiptAdmissionCode.MECHANICS_BINDING)
        val mandatorySources = setOf(sourceUid, identity.commandUid, rule.uid, eventUid, planUid, optionUid, finished.ruleUid)
        val combatProofs = evidence.sourceUids.filter { it !in mandatorySources && it !in sourceProofs }
        check(combatProofs.isNotEmpty() && combatProofs.all { it.matches(Regex("PROOF:[0-9a-f]{64}")) } &&
            evidence.sourceUids.toSet() == mandatorySources + sourceProofs + combatProofs,
            Phase64CombatReceiptAdmissionCode.EVIDENCE)
        val actual = listOf(Phase64BackgroundCodec.fingerprint(finished)) + mechanics.map { Phase64BackgroundCodec.fingerprint(it.payload) }
        check(change.consequenceFingerprints.groupingBy { it }.eachCount() == actual.groupingBy { it }.eachCount(),
            Phase64CombatReceiptAdmissionCode.CONSEQUENCES)
    }

    private fun subject(payload: PlayerDomainChangePayload): DomainRef? = when (payload) {
        is ResourceChange -> payload.subject
        is WoundChange -> payload.subject
        is ConditionChange -> payload.subject
        is SpatialChange -> payload.subject
        is EquipmentIntegrityChange -> payload.subject
        is StructureIntegrityChange -> payload.subject
        is MechanicalTrackChange -> payload.subject
        is AggregatePopulationChange -> payload.subject
        else -> null
    }

    private val PARAMETER_KEYS = setOf("decision_uid", "ability_uid", "target_kind_uid", "target_uid", "p64_ability_contract",
        "p64_completion_option_uid", "p64_completion_owner", "p64_completion_command_uid", "p64_completion_proof_uid",
        "p64_logical_event_uid", "p64_started_at_ms", "p64_event_at_ms", "p64_process_uid", "p64_rule_uid", "p64_rule_version",
        "p64_rule_fingerprint")

    private fun check(condition: Boolean, code: Phase64CombatReceiptAdmissionCode) { if (!condition) reject(code) }
    private fun reject(code: Phase64CombatReceiptAdmissionCode): Nothing = throw Phase64CombatReceiptAdmissionException(code)
    private inline fun <T> guarded(code: Phase64CombatReceiptAdmissionCode, block: () -> T): T = try { block() }
        catch (_: IllegalArgumentException) { reject(code) }
        catch (_: IllegalStateException) { reject(code) }
}
