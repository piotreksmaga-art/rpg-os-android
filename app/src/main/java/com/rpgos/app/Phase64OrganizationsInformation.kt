package com.rpgos.app

/**
 * Version-one institutional and communication rules. Parameters are explicit inputs accepted by
 * Core, never a way to read an institution's members or to choose an NPC's action.
 *
 * ORGANIZATION operations:
 * - AGENDA: organization_uid, agenda_uid, agenda_version, objective.
 * - ASSIGN: organization_uid, duty_uid, duty_version, assignee_kind, assignee_uid, role_uid,
 *   assignment_policy_uid, deadline_uid, deadline_ms. The owner must resolve the registered
 *   Phase62 duty contract and prepare its Phase38 grant and ordinary Phase60 deadline.
 * - REVOKE: the same duty/assignee/policy identity and reason_uid. The owner prepares revocation,
 *   retaining the historical assignment and never reinstating it on a read.
 * - DECISION: organization_uid, agenda_uid, agenda_version, option_uid, decision_policy_uid.
 *   The owner checks a legal Phase62 option; an option name alone is no action authorization.
 * - ALLOCATE: organization_uid, recipient_kind, recipient_uid, resource_kind, resource_uid,
 *   quantity, allocation_policy_uid. The existing resource owner prepares the actual transfer.
 * ASSIGN/DECISION may also reserve one explicit resource_kind/resource_uid/quantity tuple.
 *
 * INFORMATION operations:
 * - MESSAGE/REPORT/DIPLOMACY: recipient_kind, recipient_uid, message_uid, message_text,
 *   channel_uid, disclosure_policy_uid, delay_ms. REPORT also cites source_acquisition_uid;
 *   DIPLOMACY requires organization_uid. The delivered assertion is a Phase37 belief about
 *   what the sender asserted, not a new fact about its subject or an automatic agreement.
 * - ESPIONAGE: recipient_kind, recipient_uid, carrier_kind, carrier_uid, channel_uid,
 *   disclosure_policy_uid, espionage_policy_uid, delay_ms. The owner must use Phase38 carrier
 *   access and Phase37 acquisition, and may not substitute global or private member memories.
 *
 * Loss/distortion are optional definition-only rule parameters: transmission_policy_uid and
 * transmission_policy_version plus loss_basis_points and/or distortion_basis_points (0..10000).
 * A distortion also requires distorted_text. Process parameters cannot replace this policy.
 * Optional confidence_basis_points/source_reliability_basis_points are definition-only too.
 * Registered owner operations are enumerated below. Every owner receives the original explicit
 * parameters plus derived _p64_* evidence and the complete speculative change overlay.
 */
class Phase64OrganizationsInformationAdapter : BackgroundDomainAdapter {
    override val domains = setOf("ORGANIZATION", "INFORMATION")

    override fun evaluate(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        at: WorldTimeTick,
        reads: BackgroundWorldReadPort,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan {
        if (definition.domain !in domains || definition.uid != process.definitionUid ||
            definition.version != process.definitionVersion) return backgroundBlocked("P64:ORG_INFO_RULE_SCOPE")
        if (process.status in terminalStatuses) return WorldConsequencePlan(
            status = process.status, reasonUid = process.reasonUid, progressUnits = process.progressUnits)
        if (at < process.due) return WorldConsequencePlan(status = process.status, progressUnits = process.progressUnits)
        if (!reads.exists(process.actor)) return backgroundBlocked("P64:ORG_INFO_ACTOR_MISSING")
        return try {
            val parameters = backgroundParameters(definition, process).filterKeys { it !in definitionOnlyParameters } +
                definition.parameters.filterKeys { it in definitionOnlyParameters }
            require(parameters.keys.none { it.startsWith("_p64_") }) { "P64:RESERVED_PARAMETER" }
            when (definition.domain) {
                "ORGANIZATION" -> organization(definition, process, scope, at, parameters, reads, staged)
                else -> information(definition, process, scope, at, parameters, reads, staged)
            }
        } catch (failure: IllegalArgumentException) {
            backgroundBlocked(failure.message?.takeIf { it.startsWith("P64:") } ?: "P64:ORG_INFO_INVALID_PARAMETERS")
        } catch (_: ArithmeticException) {
            backgroundBlocked("P64:ORG_INFO_PARAMETER_OVERFLOW")
        }
    }

    private fun organization(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        settledAt: WorldTimeTick,
        parameters: Map<String, String>,
        reads: BackgroundWorldReadPort,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan {
        val ownerOperation = organizationOperations[definition.operation]
            ?: return backgroundBlocked("P64:ORGANIZATION_OPERATION_UNAVAILABLE")
        val organization = DomainRef("ORGANIZATION", required(parameters, "organization_uid"))
        if (!reads.exists(organization)) return backgroundBlocked("P64:ORGANIZATION_MISSING")
        val references = mutableListOf(organization)
        when (definition.operation) {
            "AGENDA" -> {
                required(parameters, "agenda_uid"); positive(parameters, "agenda_version")
                text(parameters, "objective", 1024)
            }
            "ASSIGN", "REVOKE" -> {
                required(parameters, "duty_uid"); positive(parameters, "duty_version")
                val assignee = ref(parameters, "assignee")
                if (!reads.exists(assignee)) return backgroundBlocked("P64:ASSIGNEE_MISSING")
                references += assignee
                references += DomainRef("NPC_DUTY", required(parameters, "duty_uid"))
                required(parameters, "assignment_policy_uid")
                if (definition.operation == "ASSIGN") {
                    required(parameters, "role_uid"); required(parameters, "deadline_uid")
                    if (positive(parameters, "deadline_ms") <= process.due.milliseconds)
                        return backgroundBlocked("P64:DUTY_DEADLINE_EXPIRED")
                } else required(parameters, "reason_uid")
            }
            "DECISION" -> {
                if (parameters.containsKey("decision_actor_kind") || parameters.containsKey("decision_actor_uid")) {
                    if (DomainRef(required(parameters, "decision_actor_kind"), required(parameters, "decision_actor_uid")) != process.actor)
                        return backgroundBlocked("P64:INSTITUTIONAL_DECISION_ACTOR_SCOPE")
                }
                required(parameters, "agenda_uid"); positive(parameters, "agenda_version")
                references += DomainRef("NPC_ACTION_OPTION", required(parameters, "option_uid"))
                required(parameters, "decision_policy_uid")
            }
            "ALLOCATE" -> {
                val recipient = ref(parameters, "recipient")
                if (!reads.exists(recipient)) return backgroundBlocked("P64:ALLOCATION_RECIPIENT_MISSING")
                references += recipient
                required(parameters, "allocation_policy_uid")
            }
        }
        val claim = resourceClaim(parameters, required = definition.operation == "ALLOCATE")
        if (claim != null) {
            references += claim.resource
            val available = reads.available(claim.resource, staged)
                ?: return backgroundBlocked("P64:ORGANIZATION_RESOURCE_UNAVAILABLE")
            if (available < claim.quantity) return backgroundBlocked("P64:ORGANIZATION_RESOURCE_INSUFFICIENT")
        }
        if (!reads.authorize(process.actor, ownerOperation, references.distinct(), staged))
            return backgroundBlocked("P64:ORGANIZATION_AUTHORITY_DENIED")
        val result = reads.prepareOwnedEffect(ownerOperation, process.actor,
            ownerParameters(definition, process, scope, settledAt, parameters), scope, staged)
        if (definition.operation != "AGENDA" && result.status == BackgroundProcessStatus.COMPLETED &&
            result.changes.isEmpty() && result.effects.isEmpty() && result.reasonUid !in setOf(
                "P64:DUTY_ALREADY_ASSIGNED", "P64:DUTY_ALREADY_REVOKED"))
            return backgroundBlocked("P64:ORGANIZATION_OWNER_RESULT_REQUIRED")
        return result.copy(
            claims = if (result.status == BackgroundProcessStatus.BLOCKED) result.claims else
                (result.claims + listOfNotNull(claim)).distinct(),
            sourceUids = (result.sourceUids + sourceUids(definition, process, parameters)).distinct())
    }

    private fun information(
        definition: BackgroundProcessDefinition,
        process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope,
        settledAt: WorldTimeTick,
        parameters: Map<String, String>,
        reads: BackgroundWorldReadPort,
        staged: List<PlayerDomainChangePayload>
    ): WorldConsequencePlan {
        if (definition.operation !in informationOperations)
            return backgroundBlocked("P64:INFORMATION_OPERATION_UNAVAILABLE")
        if (parameters.containsKey("sender_kind") || parameters.containsKey("sender_uid")) {
            if (ref(parameters, "sender") != process.actor) return backgroundBlocked("P64:INFORMATION_SENDER_SCOPE")
        }
        val recipient = ref(parameters, "recipient")
        if (!reads.exists(recipient)) return backgroundBlocked("P64:INFORMATION_RECIPIENT_MISSING")
        val recipientHolder = holder(recipient, scope.temporal.campaignUid)
        val channel = DomainRef("INFORMATION_CHANNEL", required(parameters, "channel_uid"))
        val disclosure = DomainRef("DISCLOSURE_POLICY", required(parameters, "disclosure_policy_uid"))
        val delay = nonNegative(parameters, "delay_ms")
        // The event's due time is immutable. Host time, retries and history generation cannot
        // change when this accepted message was actually deliverable.
        val deliverable = WorldTimeTick(Math.addExact(process.startedAt.milliseconds, delay))
        val logicalDue = WorldTimeTick(Math.addExact(process.startedAt.milliseconds, definition.durationMillis))
        if (logicalDue < deliverable) return backgroundBlocked("P64:INFORMATION_DELAY_NOT_SCHEDULED")
        val references = mutableListOf(recipient, channel, disclosure)
        val sourceAcquisition = parameters["source_acquisition_uid"]?.also { require(it.isNotBlank()) }
        sourceAcquisition?.let {
            val source = DomainRef("KNOWLEDGE_ACQUISITION", it)
            if (!reads.exists(source, staged)) return backgroundBlocked("P64:REPORT_SOURCE_ACQUISITION_MISSING")
            references += source
        }
        when (definition.operation) {
            "REPORT" -> required(parameters, "source_acquisition_uid")
            "DIPLOMACY" -> {
                val organization = DomainRef("ORGANIZATION", required(parameters, "organization_uid"))
                if (!reads.exists(organization)) return backgroundBlocked("P64:DIPLOMACY_ORGANIZATION_MISSING")
                references += organization
            }
            "ESPIONAGE" -> {
                val carrier = ref(parameters, "carrier")
                if (!reads.exists(carrier)) return backgroundBlocked("P64:ESPIONAGE_CARRIER_MISSING")
                references += carrier
                required(parameters, "espionage_policy_uid")
            }
        }
        val senderPurpose = "P64:INFO_SEND:${definition.operation}"
        val senderReferences = references.distinct()
        val senderAccess = reads.communicationAccess(process.actor, senderPurpose, senderReferences,
            definition, process, settledAt, staged)
        if (senderAccess != null && !communicationAccessBound(senderAccess, process.actor, channel, scope))
            return backgroundBlocked("P64:INFORMATION_CHANNEL_ACCESS_SCOPE")
        if (senderAccess?.accessible != true)
            return backgroundBlocked("P64:INFORMATION_SENDER_ACCESS_DENIED")
        val recipientReferences = listOf(process.actor, channel, disclosure)
        val recipientAccess = reads.communicationAccess(recipient, "P64:INFO_RECEIVE", recipientReferences,
            definition, process, settledAt, staged)
        if (recipientAccess != null && !communicationAccessBound(recipientAccess, recipient, channel, scope))
            return backgroundBlocked("P64:INFORMATION_CHANNEL_ACCESS_SCOPE")
        if (recipientAccess?.accessible != true)
            return backgroundBlocked("P64:INFORMATION_DISCLOSURE_DENIED")
        if (definition.operation == "ESPIONAGE") {
            val result = reads.prepareOwnedEffect(OWNER_ESPIONAGE, process.actor,
                ownerParameters(definition, process, scope, settledAt, parameters), scope, staged)
            // The owner may report an unsuccessful legal attempt, but a completed acquisition
            // can never consist solely of a scalar or an assertion of world truth.
            if (result.status == BackgroundProcessStatus.COMPLETED &&
                (result.changes.isEmpty() || result.changes.any { it !is KnowledgeAcquisitionChange } ||
                    result.changes.filterIsInstance<KnowledgeAcquisitionChange>().any {
                        it.acquisition.holder != recipientHolder ||
                            it.acquisition.methodUid != KnowledgeAcquisitionMethods.ESPIONAGE ||
                            it.acquisition.epistemicState == KnowledgeEpistemicState.KNOWN
                    } || result.effects.isNotEmpty())) return backgroundBlocked("P64:ESPIONAGE_ACQUISITION_REQUIRED")
            return result.copy(sourceUids = (result.sourceUids + sourceUids(definition, process, parameters)).distinct())
        }
        val messageUid = required(parameters, "message_uid")
        require(messageUid.length <= 160 && process.actor.uid.length <= 160) { "P64:MESSAGE_IDENTITY_TOO_LONG" }
        val literal = text(parameters, "message_text", 2048)
        val outcome = transmission(definition, process, scope)
        if (outcome == TransmissionOutcome.LOST) return WorldConsequencePlan(
            sourceUids = sourceUids(definition, process, parameters),
            reasonUid = "P64:INFORMATION_LOST_BY_RULE", progressUnits = 1)
        val delivered = if (outcome == TransmissionOutcome.DISTORTED)
            text(definition.parameters, "distorted_text", 2048) else literal
        val id = logicalEventUid(definition, process, scope)
        val predicate = "P64:ASSERTED_MESSAGE:$messageUid"
        val acquisition = KnowledgeAcquisitionSpec("P64:MESSAGE_ACQUISITION:$id",
            recipientHolder,
            when (definition.operation) {
                "REPORT" -> KnowledgeAcquisitionMethods.REPORT
                "DIPLOMACY" -> KnowledgeAcquisitionMethods.INSTITUTIONAL_SHARING
                else -> KnowledgeAcquisitionMethods.DIRECT_COMMUNICATION
            }, if (isInstitution(recipient)) KnowledgeScope.INSTITUTIONAL else KnowledgeScope.PERSONAL,
            if (outcome == TransmissionOutcome.DISTORTED) KnowledgeEpistemicState.SUSPECTED else KnowledgeEpistemicState.BELIEVED,
            KnowledgeQuality(basisPoints(definition.parameters, "confidence_basis_points", 5000) / 10000.0,
                if (outcome == TransmissionOutcome.DISTORTED) 0.5 else 1.0, 1.0,
                basisPoints(definition.parameters, "source_reliability_basis_points", 5000) / 10000.0, 0),
            carrier = KnowledgeCarrierRef(channel.kindUid, channel.uid, scope.temporal.campaignUid))
        val evidence = mutableListOf(
            evidence(id, "SENDER", process.actor, scope),
            evidence(id, "CHANNEL", channel, scope),
            evidence(id, "DISCLOSURE", disclosure, scope),
            evidence(id, "PROCESS", DomainRef("BACKGROUND_PROCESS", process.uid), scope))
        listOf("SENDER_ACCESS" to senderAccess?.path, "RECIPIENT_ACCESS" to recipientAccess?.path).forEach { (kind, path) ->
            if (path != null) evidence += KnowledgeEvidenceSpec("P64:MESSAGE_EVIDENCE:$id:$kind",
                "P64:DELIVERY_$kind", KnowledgeEvidencePolarity.NEUTRAL,
                sourceCarrier = KnowledgeCarrierRef(channel.kindUid, channel.uid, scope.temporal.campaignUid),
                sourceRef = KnowledgeSourceRef.campaign(scope.temporal.campaignUid, "ACCESS_PATH_EVIDENCE", path.evidenceUid))
        }
        if (sourceAcquisition != null) evidence += KnowledgeEvidenceSpec("P64:MESSAGE_EVIDENCE:$id:SOURCE",
            // A report asserts what its sender said, a different claim from the cited source.
            // Phase37 acquisition links are reserved for evidence of that exact same claim.
            "P64:REPORT_SOURCE_CITATION", KnowledgeEvidencePolarity.NEUTRAL,
            sourceRef = KnowledgeSourceRef.campaign(scope.temporal.campaignUid, "KNOWLEDGE_ACQUISITION", sourceAcquisition))
        if (outcome == TransmissionOutcome.DISTORTED) evidence += evidence(id, "DISTORTION",
            DomainRef("TRANSMISSION_POLICY", required(definition.parameters, "transmission_policy_uid")), scope)
        // Phase37's mobile projection excludes oversized claims. Chunk the exact delivered
        // assertion at a bounded size, preserving order and all evidence instead of silently
        // making an otherwise delivered long report unavailable to its recipient's context.
        val parts = chunks(delivered, (500 - process.actor.uid.length - predicate.length - 8).coerceAtMost(240))
        val changes = parts.mapIndexed { ordinal, part ->
            val suffix = if (parts.size == 1) "" else ":$ordinal"
            val claim = KnowledgeClaim("P64:MESSAGE_CLAIM:$id$suffix", process.actor.kindUid, process.actor.uid,
                predicate + suffix, part, domainUid = if (definition.operation == "DIPLOMACY")
                    KnowledgeDomains.POLITICS else KnowledgeDomains.WORLD_SPECIFIC)
            KnowledgeAcquisitionChange(claim, acquisition.copy(acquisitionUid = acquisition.acquisitionUid + suffix),
                evidence.map { it.copy(evidenceUid = it.evidenceUid + suffix) })
        }
        return WorldConsequencePlan(changes = changes,
            sourceUids = sourceUids(definition, process, parameters), progressUnits = 1,
            reasonUid = if (outcome == TransmissionOutcome.DISTORTED) "P64:INFORMATION_DISTORTED_BY_RULE" else null)
    }

    private fun evidence(id: String, kind: String, ref: DomainRef, scope: BackgroundProcessEvaluationScope) =
        KnowledgeEvidenceSpec("P64:MESSAGE_EVIDENCE:$id:$kind", "P64:DELIVERY_$kind",
            KnowledgeEvidencePolarity.NEUTRAL,
            sourceRef = KnowledgeSourceRef.campaign(scope.temporal.campaignUid, ref.kindUid, ref.uid))

    private fun communicationAccessBound(access: EffectiveAccessDecision, principal: DomainRef, channel: DomainRef,
        scope: BackgroundProcessEvaluationScope): Boolean {
        val path = access.path ?: return false
        return access.accessible && path.campaignUid == scope.temporal.campaignUid &&
            path.principal == VisibilityPrincipalRef(principal.kindUid, principal.uid) &&
            path.carrier == InformationCarrierRef(scope.temporal.campaignUid, channel.kindUid, channel.uid) &&
            CarrierAccessStage.COMPREHENDED in access.resolvedStages && CarrierAccessStage.COMPREHENDED in path.resolvedStages
    }

    private fun transmission(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
                             scope: BackgroundProcessEvaluationScope): TransmissionOutcome {
        val parameters = definition.parameters
        val loss = basisPoints(parameters, "loss_basis_points", 0)
        val distortion = basisPoints(parameters, "distortion_basis_points", 0)
        if (loss == 0 && distortion == 0) return TransmissionOutcome.DELIVERED
        val policy = required(parameters, "transmission_policy_uid")
        val version = positive(parameters, "transmission_policy_version")
        if (distortion > 0) text(parameters, "distorted_text", 2048)
        fun draw(label: String) = phase60Hash("P64:TRANSMISSION:1|${scope.worldSeed}|${scope.ruleFingerprint}|" +
            "$policy|$version|${logicalEventUid(definition, process, scope)}|$label").take(8).toLong(16) % 10000
        if (draw("LOSS") < loss) return TransmissionOutcome.LOST
        return if (draw("DISTORTION") < distortion) TransmissionOutcome.DISTORTED else TransmissionOutcome.DELIVERED
    }

    private fun ownerParameters(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
                                scope: BackgroundProcessEvaluationScope, settledAt: WorldTimeTick,
                                parameters: Map<String, String>) = parameters + mapOf(
        "_p64_process_uid" to process.uid,
        "_p64_definition_uid" to definition.uid,
        "_p64_rule_version" to definition.version.toString(),
        "_p64_rule_fingerprint" to scope.ruleFingerprint,
        "_p64_logical_event_uid" to logicalEventUid(definition, process, scope),
        "_p64_effective_ms" to settledAt.milliseconds.toString())

    private fun sourceUids(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
                           parameters: Map<String, String>) = (listOf(definition.uid, process.uid) +
        listOf("organization_uid", "agenda_uid", "duty_uid", "assignment_policy_uid", "decision_policy_uid",
            "allocation_policy_uid", "message_uid", "channel_uid", "disclosure_policy_uid", "source_acquisition_uid",
            "espionage_policy_uid", "carrier_uid", "transmission_policy_uid").mapNotNull(parameters::get)).distinct()

    private fun resourceClaim(parameters: Map<String, String>, required: Boolean): WorldResourceClaim? {
        val keys = listOf("resource_kind", "resource_uid", "quantity")
        if (!required && keys.none(parameters::containsKey)) return null
        return WorldResourceClaim(ref(parameters, "resource"), positive(parameters, "quantity"))
    }

    private fun holder(recipient: DomainRef, campaign: String): KnowledgeHolderRef {
        val kind = when (recipient.kindUid) {
            "NPC", "ACTOR", "CHARACTER", "PLAYER" -> KnowledgeHolderKinds.CHARACTER
            else -> {
                require(isInstitution(recipient)) { "P64:INFORMATION_HOLDER_KIND_UNAVAILABLE" }
                recipient.kindUid
            }
        }
        return KnowledgeHolderRef(kind, recipient.uid, campaign)
    }

    private fun isInstitution(ref: DomainRef) = ref.kindUid in institutionKinds
    private fun ref(parameters: Map<String, String>, prefix: String) =
        DomainRef(required(parameters, "${prefix}_kind"), required(parameters, "${prefix}_uid"))
    private fun required(parameters: Map<String, String>, key: String) = backgroundRequired(parameters, key)
    private fun positive(parameters: Map<String, String>, key: String) = backgroundPositive(parameters, key)
    private fun nonNegative(parameters: Map<String, String>, key: String) = required(parameters, key).toLong().also {
        require(it >= 0) { "P64:NEGATIVE_PARAMETER:$key" }
    }
    private fun text(parameters: Map<String, String>, key: String, maximum: Int) = required(parameters, key).also {
        require(it.length <= maximum) { "P64:PARAMETER_TOO_LONG:$key" }
    }
    private fun basisPoints(parameters: Map<String, String>, key: String, default: Int) =
        (parameters[key]?.toInt() ?: default).also { require(it in 0..10000) { "P64:INVALID_BASIS_POINTS:$key" } }

    private fun chunks(value: String, maximum: Int): List<String> {
        require(maximum >= 2) { "P64:MESSAGE_IDENTITY_TOO_LONG" }
        return buildList {
            var start = 0
            while (start < value.length) {
                var end = (start + maximum).coerceAtMost(value.length)
                if (end < value.length && value[end - 1].isHighSurrogate()) end--
                add(value.substring(start, end)); start = end
            }
        }
    }

    private enum class TransmissionOutcome { DELIVERED, LOST, DISTORTED }

    companion object {
        const val OWNER_AGENDA = "P64:ORG_AGENDA"
        const val OWNER_ASSIGN = "P64:ORG_ASSIGN"
        const val OWNER_REVOKE = "P64:ORG_REVOKE"
        const val OWNER_DECISION = "P64:ORG_DECIDE"
        const val OWNER_ALLOCATION = "P64:ORG_ALLOCATE"
        const val OWNER_ESPIONAGE = "P64:INFO_ESPIONAGE"
        val registeredOwnerOperations = setOf(OWNER_AGENDA, OWNER_ASSIGN, OWNER_REVOKE, OWNER_DECISION,
            OWNER_ALLOCATION, OWNER_ESPIONAGE)
        private val organizationOperations = mapOf("AGENDA" to OWNER_AGENDA, "ASSIGN" to OWNER_ASSIGN,
            "REVOKE" to OWNER_REVOKE, "DECISION" to OWNER_DECISION, "ALLOCATE" to OWNER_ALLOCATION)
        private val informationOperations = setOf("MESSAGE", "REPORT", "DIPLOMACY", "ESPIONAGE")
        private val terminalStatuses = setOf(BackgroundProcessStatus.COMPLETED, BackgroundProcessStatus.INTERRUPTED,
            BackgroundProcessStatus.FAILED)
        private val definitionOnlyParameters = setOf("transmission_policy_uid", "transmission_policy_version", "loss_basis_points",
            "distortion_basis_points", "distorted_text", "confidence_basis_points", "source_reliability_basis_points")
        private val institutionKinds = setOf(KnowledgeHolderKinds.ORGANIZATION, KnowledgeHolderKinds.MILITARY_COMMAND,
            KnowledgeHolderKinds.CITY_ADMINISTRATION, KnowledgeHolderKinds.STATE, KnowledgeHolderKinds.INTELLIGENCE_SERVICE,
            KnowledgeHolderKinds.RESEARCH_TEAM, KnowledgeHolderKinds.LABORATORY, KnowledgeHolderKinds.GUILD,
            KnowledgeHolderKinds.COMPANY, KnowledgeHolderKinds.WORLD_SPECIFIC)

        /** Durable event identity excludes host time, evaluation ordinal and history generation. */
        internal fun logicalEventUid(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
                                     scope: BackgroundProcessEvaluationScope) = phase60Hash("P64:ORG_INFO:1" +
            listOf(scope.temporal.campaignUid, definition.uid, definition.version.toString(), process.uid,
                Math.addExact(process.startedAt.milliseconds, definition.durationMillis).toString())
                .joinToString("") { "${it.length}:$it" })
    }
}

/** Captured, campaign-scoped values. The repository supplies these after its scope and principal
 * checks. Effective access records come from Phase38; the history set includes revoked records,
 * so a missing live grant can never be mistaken for an assignment that needs to be re-created. */
internal data class Phase64DutyOwnerSnapshot(
    val currentScope: TemporalScope,
    val currentOrder: Long,
    val issuingAuthorityAuthorized: Boolean,
    val assigneeEligible: Boolean,
    val registeredDuty: NpcDutyRule?,
    val assigneeAccess: List<AccessAuthorityRecord>,
    val recordedAssignmentUids: Set<String>,
    val deadlines: List<WorldProcessDeadline>,
    /** Keep a shared Phase62 deadline unless the repository proves this is its last assignment. */
    val otherAssigneesWithActiveDuty: Boolean = true
)

/** Account/instance custody is resolved by its existing owner, not declared by a process. */
internal data class Phase64AllocationOwnerSnapshot(
    val currentScope: TemporalScope,
    val authorized: Boolean,
    val sourceOwner: DomainRef,
    val recipientOwner: DomainRef,
    val availableUnits: Long?,
    val sourceAccountUid: String? = null,
    val recipientAccountUid: String? = null,
    val currencyUid: String? = null,
    val itemInstanceUid: String? = null
)

/** Executable owner helpers. All functions are pure: they prepare existing typed owner payloads
 * and ordinary future Phase60 deadlines. They never perform a database write or add a clock. */
internal object Phase64OrganizationsInformationOwners {
    /** Allocate money or one existing unique item. FinancialStore and InventoryStore remain
     * owners. Other registered allocation semantics must supply their own typed owner result. */
    fun prepareAllocation(parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
                          snapshot: Phase64AllocationOwnerSnapshot): WorldConsequencePlan {
        if (snapshot.currentScope != scope.temporal) return backgroundBlocked("P64:STALE_HISTORY_GENERATION")
        if (!snapshot.authorized) return backgroundBlocked("P64:ALLOCATION_OWNER_AUTHORITY_DENIED")
        val organization = DomainRef("ORGANIZATION", backgroundRequired(parameters, "organization_uid"))
        val recipient = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        if (snapshot.sourceOwner != organization || snapshot.recipientOwner != recipient || organization == recipient)
            return backgroundBlocked("P64:ALLOCATION_OWNER_SCOPE")
        val resource = DomainRef(backgroundRequired(parameters, "resource_kind"), backgroundRequired(parameters, "resource_uid"))
        val units = backgroundPositive(parameters, "quantity")
        if (snapshot.availableUnits == null || snapshot.availableUnits < units)
            return backgroundBlocked("P64:ALLOCATION_RESOURCE_INSUFFICIENT")
        val changes: List<PlayerDomainChangePayload> = when (resource.kindUid) {
            "FINANCIAL_ACCOUNT" -> {
                if (snapshot.sourceAccountUid != resource.uid || snapshot.recipientAccountUid.isNullOrBlank() ||
                    snapshot.currencyUid.isNullOrBlank() || snapshot.sourceAccountUid == snapshot.recipientAccountUid)
                    return backgroundBlocked("P64:ALLOCATION_ACCOUNT_SCOPE")
                listOf(FinancialChange(resource.uid, snapshot.recipientAccountUid, units, snapshot.currencyUid,
                    parameters["transaction_type_uid"] ?: "RPGOS-FIN-TYPE:TRANSFER"))
            }
            "ITEM_INSTANCE" -> {
                if (units != 1L || snapshot.itemInstanceUid != resource.uid)
                    return backgroundBlocked("P64:ALLOCATION_UNIQUE_ITEM_SCOPE")
                listOf(InventoryChange(organization, resource.uid, ExactLongDelta.of(-1)),
                    InventoryChange(recipient, resource.uid, ExactLongDelta.of(1)))
            }
            else -> return backgroundBlocked("P64:ALLOCATION_RESOURCE_OWNER_UNAVAILABLE")
        }
        return WorldConsequencePlan(changes = changes, sourceUids = listOf(resource.uid, organization.uid,
            recipient.uid, backgroundRequired(parameters, "allocation_policy_uid")), progressUnits = 1)
    }

    fun prepareAgenda(parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope): WorldConsequencePlan {
        val agenda = backgroundRequired(parameters, "agenda_uid")
        val version = backgroundPositive(parameters, "agenda_version")
        backgroundRequired(parameters, "objective")
        // The versioned BackgroundProcessInstance (its explicit objective and completion) is
        // the agenda record. It does not need a made-up NPC resource track or a second balance.
        return WorldConsequencePlan(progressUnits = 1,
            sourceUids = listOf(agenda, version.toString(), scope.ruleFingerprint), reasonUid = "P64:AGENDA_REGISTERED")
    }

    fun prepareDuty(operation: String, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        snapshot: Phase64DutyOwnerSnapshot, staged: List<PlayerDomainChangePayload> = emptyList()): WorldConsequencePlan {
        if (scope.temporal != snapshot.currentScope || snapshot.currentOrder != scope.temporal.baseCommitOrder)
            return backgroundBlocked("P64:STALE_HISTORY_GENERATION")
        if (!snapshot.issuingAuthorityAuthorized) return backgroundBlocked("P64:DUTY_ISSUING_AUTHORITY_DENIED")
        if (!snapshot.assigneeEligible) return backgroundBlocked("P64:DUTY_ASSIGNEE_INELIGIBLE")
        val duty = snapshot.registeredDuty ?: return backgroundBlocked("P64:DUTY_RULE_UNAVAILABLE")
        if (parameters["organization_uid"] != duty.organizationUid || parameters["duty_uid"] != duty.dutyUid ||
            parameters["duty_version"]?.toIntOrNull() != duty.version ||
            parameters["assignment_policy_uid"] != duty.assignmentPolicyUid)
            return backgroundBlocked("P64:DUTY_CONTRACT_MISMATCH")
        val assignee = DomainRef(backgroundRequired(parameters, "assignee_kind"), backgroundRequired(parameters, "assignee_uid"))
        val recordUid = assignmentUid(scope.temporal.campaignUid, assignee, duty)
        val access = effectiveOverlay(snapshot.assigneeAccess, assignee, snapshot.currentOrder, staged)
        val hasRole = access.any { it.operation == AccessOperation.UPSERT_BINDING &&
            it.kindUid == AccessBindingKind.ROLE.name && it.valueUid == duty.roleUid }
        val hasOrganization = access.any { it.operation == AccessOperation.UPSERT_BINDING &&
            it.kindUid == AccessBindingKind.ORGANIZATION.name && it.valueUid == duty.organizationUid }
        val activeGrant = access.singleOrNull { it.operation in setOf(AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS) &&
            it.kindUid == AccessGrantKind.WORLD_RULE.name && it.valueUid == duty.assignmentPolicyUid &&
            it.subjectKindUid == "NPC_DUTY" && it.subjectUid == duty.dutyUid }
        val sourceUids = listOf(duty.dutyUid, duty.fingerprint, duty.assignmentPolicyUid, recordUid)
        val deadline = WorldProcessDeadline(duty.deadlineUid, NpcDutyDeadlineProcess.OWNER, duty.due)
        when (operation) {
            Phase64OrganizationsInformationAdapter.OWNER_ASSIGN -> {
                if (parameters["role_uid"] != duty.roleUid || parameters["deadline_uid"] != duty.deadlineUid ||
                    parameters["deadline_ms"]?.toLongOrNull() != duty.due.milliseconds)
                    return backgroundBlocked("P64:DUTY_CONTRACT_MISMATCH")
                val effective = parameters["_p64_effective_ms"]?.toLongOrNull()
                    ?: return backgroundBlocked("P64:DUTY_EFFECTIVE_TIME_REQUIRED")
                if (duty.due.milliseconds <= effective) return backgroundBlocked("P64:DUTY_DEADLINE_EXPIRED")
                if (!hasRole || !hasOrganization) return backgroundBlocked("P64:DUTY_ROLE_OR_ORGANIZATION_MISSING")
                val existing = snapshot.deadlines.singleOrNull { it.uid == duty.deadlineUid }
                if (existing != null && existing != deadline) return backgroundBlocked("P64:DUTY_DEADLINE_IDENTITY_REUSED")
                if (activeGrant != null) {
                    if (existing == null) return backgroundBlocked("P64:DUTY_ASSIGNED_WITHOUT_DEADLINE")
                    return WorldConsequencePlan(sourceUids = sourceUids, progressUnits = 1, reasonUid = "P64:DUTY_ALREADY_ASSIGNED")
                }
                // A changed obligation needs a new registered version. Revoked history is not
                // erased by reopen, retry, re-import, or a same-version second process.
                if (recordUid in snapshot.recordedAssignmentUids || staged.filterIsInstance<AccessAuthorityChange>().any {
                        it.recordUid == recordUid && it.principalKindUid == assignee.kindUid && it.principalUid == assignee.uid })
                    return backgroundBlocked("P64:DUTY_VERSION_ALREADY_REVOKED")
                val grant = AccessAuthorityChange(AccessOperation.GRANT, recordUid, assignee.kindUid, assignee.uid,
                    AccessGrantKind.WORLD_RULE.name, duty.assignmentPolicyUid, "NPC_DUTY", duty.dutyUid, snapshot.currentOrder)
                return WorldConsequencePlan(changes = listOf(grant), sourceUids = sourceUids, progressUnits = 1,
                    deadlineAdds = if (existing == null) listOf(deadline) else emptyList())
            }
            Phase64OrganizationsInformationAdapter.OWNER_REVOKE -> {
                backgroundRequired(parameters, "reason_uid")
                if (activeGrant == null) return WorldConsequencePlan(sourceUids = sourceUids,
                    progressUnits = 1, reasonUid = "P64:DUTY_ALREADY_REVOKED")
                val revoke = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, recordUid,
                    assignee.kindUid, assignee.uid, activeGrant.kindUid, activeGrant.valueUid,
                    activeGrant.subjectKindUid, activeGrant.subjectUid, snapshot.currentOrder)
                return WorldConsequencePlan(changes = listOf(revoke), sourceUids = sourceUids, progressUnits = 1,
                    deadlineRemovals = if (!snapshot.otherAssigneesWithActiveDuty && snapshot.deadlines.contains(deadline))
                        listOf(deadline.uid) else emptyList())
            }
            else -> return backgroundBlocked("P64:DUTY_OPERATION_UNAVAILABLE")
        }
    }

    /** Route the legal, budgeted context to the existing engine. The repository then starts the
     * selected action through its existing Phase62 action owner using this sealed authorization;
     * the callback cannot be called with an unselected option or for the active player. */
    fun prepareDecision(parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        actor: DomainRef, context: NpcDecisionContextEnvelope?, currentScope: NpcDecisionScope?,
        proposal: NpcDecisionProposal? = null, cancellation: AiCancellationSignal = AiCancellationSignal.NONE,
        prepareSelectedAction: (NpcDecisionContextEnvelope, NpcDecisionResult.Selected) -> WorldConsequencePlan
    ): WorldConsequencePlan {
        if (context == null || currentScope == null) return backgroundBlocked("P64:INSTITUTIONAL_DECISION_CONTEXT_UNAVAILABLE")
        if (context.scope.temporal != scope.temporal || context.scope.actor != actor || currentScope.actor != actor)
            return backgroundBlocked("P64:INSTITUTIONAL_DECISION_SCOPE")
        val optionUid = backgroundRequired(parameters, "option_uid")
        if (context.options.none { it.uid == optionUid }) return backgroundBlocked("P64:INSTITUTIONAL_OPTION_UNAVAILABLE")
        return when (val decision = NpcDecisionEngine().select(context, proposal, currentScope, cancellation)) {
            is NpcDecisionResult.Unavailable -> backgroundBlocked(decision.reasonUid)
            is NpcDecisionResult.Reflected -> backgroundBlocked("P64:INSTITUTIONAL_ACTION_NOT_SELECTED")
            is NpcDecisionResult.Selected -> {
                if (decision.option.uid != optionUid) backgroundBlocked("P64:INSTITUTIONAL_OPTION_NOT_SELECTED")
                else prepareSelectedAction(context, decision).let { result ->
                    // A refused owner admission cannot leak selected cognitive changes into the
                    // transaction, or begin a plan without its Phase62 pending state and deadline.
                    if (result.status != BackgroundProcessStatus.COMPLETED)
                        backgroundBlocked(result.reasonUid ?: "P64:INSTITUTIONAL_ACTION_NOT_ADMITTED")
                    else result.copy(changes = decision.brainChanges + result.changes,
                        sourceUids = (result.sourceUids + listOf(decision.authorization.decisionUid, optionUid)).distinct())
                }
            }
        }
    }

    /** Carrier content is captured from the authorized existing owner, never message_text or
     * member cognition. Phase38's sealed access path supplies the exact actor/carrier/evidence. */
    fun prepareEspionage(parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        actor: DomainRef, access: EffectiveAccessDecision?, carrierClaim: KnowledgeClaim?,
        recipientHolder: KnowledgeHolderRef?, sourceAcquisition: KnowledgeAcquisition? = null
    ): WorldConsequencePlan {
        if (access?.accessible != true || carrierClaim == null || recipientHolder == null)
            return backgroundBlocked("P64:ESPIONAGE_CARRIER_ACCESS_UNAVAILABLE")
        val path = access.path ?: return backgroundBlocked("P64:ESPIONAGE_ACCESS_PATH_REQUIRED")
        val carrier = DomainRef(backgroundRequired(parameters, "carrier_kind"), backgroundRequired(parameters, "carrier_uid"))
        val recipient = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        val expectedHolderKind = when (recipient.kindUid) { "NPC", "ACTOR", "CHARACTER", "PLAYER" -> KnowledgeHolderKinds.CHARACTER
            else -> recipient.kindUid }
        if (path.campaignUid != scope.temporal.campaignUid || path.principal != VisibilityPrincipalRef(actor.kindUid, actor.uid) ||
            path.carrier != InformationCarrierRef(scope.temporal.campaignUid, carrier.kindUid, carrier.uid) ||
            CarrierAccessStage.COMPREHENDED !in access.resolvedStages ||
            CarrierAccessStage.COMPREHENDED !in path.resolvedStages ||
            recipientHolder != KnowledgeHolderRef(expectedHolderKind, recipient.uid, scope.temporal.campaignUid))
            return backgroundBlocked("P64:ESPIONAGE_EXACT_ACCESS_SCOPE_REQUIRED")
        if (sourceAcquisition != null && (sourceAcquisition.campaignUid != scope.temporal.campaignUid ||
                sourceAcquisition.claimUid != carrierClaim.claimUid ||
                sourceAcquisition.holder != KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, actor.uid, scope.temporal.campaignUid)))
            return backgroundBlocked("P64:ESPIONAGE_SOURCE_ACQUISITION_MISMATCH")
        val event = backgroundRequired(parameters, "_p64_logical_event_uid")
        val acquisition = KnowledgeAcquisitionSpec("P64:ESPIONAGE_ACQUISITION:$event", recipientHolder,
            KnowledgeAcquisitionMethods.ESPIONAGE,
            if (expectedHolderKind == KnowledgeHolderKinds.CHARACTER) KnowledgeScope.PERSONAL else KnowledgeScope.INSTITUTIONAL,
            KnowledgeEpistemicState.BELIEVED, KnowledgeQuality(0.5, 1.0, 1.0, 0.5, 0),
            parentAcquisitionUid = sourceAcquisition?.acquisitionUid, sourceHolder = sourceAcquisition?.holder,
            carrier = KnowledgeCarrierRef(carrier.kindUid, carrier.uid, scope.temporal.campaignUid))
        val evidence = listOf(KnowledgeEvidenceSpec("P64:ESPIONAGE_EVIDENCE:$event", "P64:AUTHORIZED_CARRIER_ACCESS",
            KnowledgeEvidencePolarity.NEUTRAL, sourceRef = KnowledgeSourceRef.campaign(scope.temporal.campaignUid,
                "ACCESS_PATH_EVIDENCE", path.evidenceUid)))
        return WorldConsequencePlan(changes = listOf(KnowledgeAcquisitionChange(carrierClaim, acquisition, evidence)),
            sourceUids = listOf(carrier.uid, path.evidenceUid, path.mechanismUid,
                backgroundRequired(parameters, "espionage_policy_uid")), progressUnits = 1)
    }

    internal fun assignmentUid(campaign: String, assignee: DomainRef, duty: NpcDutyRule) =
        "P64:DUTY:${phase60Hash(listOf(campaign, assignee.kindUid, assignee.uid, duty.fingerprint)
            .joinToString("") { "${it.length}:$it" })}"

    internal fun effectiveOverlay(records: List<AccessAuthorityRecord>, assignee: DomainRef, order: Long,
                                 staged: List<PlayerDomainChangePayload>): List<AccessAuthorityRecord> {
        fun key(record: AccessAuthorityRecord) = listOf(if (record.operation in setOf(AccessOperation.UPSERT_BINDING,
            AccessOperation.REVOKE_BINDING, AccessOperation.BIND_COGNITION)) "BINDING" else "GRANT", record.kindUid, record.valueUid,
            record.subjectKindUid.orEmpty(), record.subjectUid.orEmpty())
        val principal = VisibilityPrincipalRef(assignee.kindUid, assignee.uid)
        val active = linkedMapOf<List<String>, AccessAuthorityRecord>()
        records.filter { it.principal == principal && it.validFromOrder <= order &&
            (it.validUntilOrder == null || it.validUntilOrder >= order) }.forEach { active[key(it)] = it }
        // Canonical records belong to the captured base. Supplied changes become visible in
        // the proposed commit, matching the outer Phase64 authorization read.
        val stagedOrder = Math.addExact(order, 1)
        staged.filterIsInstance<AccessAuthorityChange>().filter {
            it.principalKindUid == assignee.kindUid && it.principalUid == assignee.uid &&
                it.validFromOrder <= stagedOrder }.forEach { change ->
            val record = AccessAuthorityRecord(change.recordUid, change.operation, principal, change.bindingOrGrantKindUid,
                change.valueUid, change.subjectKindUid, change.subjectUid, change.validFromOrder, change.validUntilOrder,
                stagedOrder, change.delegatedByPrincipalUid)
            when (change.operation) {
                AccessOperation.REVOKE_BINDING, AccessOperation.REVOKE_GRANT -> active.remove(key(record))
                else -> if (change.validUntilOrder == null || change.validUntilOrder >= stagedOrder)
                    active[key(record)] = record
            }
        }
        return active.values.toList()
    }
}
