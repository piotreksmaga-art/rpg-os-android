package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Exact campaign captures supplied by the existing body, catalog and topology readers. */
internal data class Phase64NeutralCommunicationSnapshot(
    val currentScope: TemporalScope,
    val definition: BackgroundProcessDefinition,
    val process: BackgroundProcessInstance,
    val sender: MechanicalActorView?,
    val recipient: MechanicalActorView?,
    val route: WorldTravelPlan? = null
)

/** A neutral public channel admits one addressed assertion. It grants no institution access,
 * private channel permission, membership knowledge, or authority over the assertion's truth. */
internal object Phase64NeutralCommunicationOwner {
    const val RULE = "P64:CORE:INFO:MESSAGE"
    const val ACTION = "SEND_DELAYED_MESSAGE"
    const val CHANNEL = "P64:CORE:COURIER"
    const val DISCLOSURE = "P64:CORE:NAMED_RECIPIENT"
    private val personalKinds = setOf("NPC", "ACTOR", "CHARACTER", "PLAYER")
    private val bodyKinds = setOf(MechanicalActorKind.NPC, MechanicalActorKind.FORMER_PLAYER, MechanicalActorKind.ACTIVE_PLAYER)

    fun matchesDefinition(definition: BackgroundProcessDefinition): Boolean = definition.uid == RULE && definition.version == 1 &&
        definition.domain == "INFORMATION" && definition.operation == "MESSAGE" &&
        definition.parameters[Phase64ProcessActivation.ACTION_KEY] == ACTION &&
        definition.parameters[Phase64ProcessActivation.PUBLIC_KEY] == "true" &&
        definition.parameters["channel_uid"] == CHANNEL && definition.parameters["disclosure_policy_uid"] == DISCLOSURE &&
        definition.parameters["recipient_kind"] == "@TARGET_KIND" && definition.parameters["recipient_uid"] == "@TARGET_UID" &&
        definition.parameters["message_uid"] == "@PROCESS_UID" && definition.parameters["message_text"] == "@MESSAGE_LITERAL" &&
        definition.parameters["delay_ms"]?.toLongOrNull() == definition.durationMillis

    fun authorize(principal: DomainRef, purpose: String, references: List<DomainRef>,
        scope: BackgroundProcessEvaluationScope, at: WorldTimeTick,
        snapshot: Phase64NeutralCommunicationSnapshot): EffectiveAccessDecision? = try {
        authorizeCaptured(principal, purpose, references, scope, at, snapshot)
    } catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }

    private fun authorizeCaptured(principal: DomainRef, purpose: String, references: List<DomainRef>,
        scope: BackgroundProcessEvaluationScope, at: WorldTimeTick,
        snapshot: Phase64NeutralCommunicationSnapshot): EffectiveAccessDecision? {
        val definition = snapshot.definition
        val process = snapshot.process
        if (snapshot.currentScope != scope.temporal || !matchesDefinition(definition) ||
            process.definitionUid != definition.uid || process.definitionVersion != definition.version ||
            process.status !in setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.BLOCKED) || at < process.due)
            return null
        val parameters = backgroundParameters(definition, process)
        val sender = snapshot.sender ?: return null
        val recipient = snapshot.recipient ?: return null
        val recipientRef = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        val senderRef = process.actor
        if (sender.campaignUid != scope.temporal.campaignUid || recipient.campaignUid != scope.temporal.campaignUid ||
            sender.actor != senderRef || recipient.actor != recipientRef || senderRef.kindUid !in personalKinds ||
            recipientRef.kindUid !in personalKinds || sender.kind !in bodyKinds || recipient.kind !in bodyKinds ||
            senderRef.kindUid != "PLAYER" || sender.kind != MechanicalActorKind.ACTIVE_PLAYER ||
            parameters["channel_uid"] != CHANNEL || parameters["disclosure_policy_uid"] != DISCLOSURE ||
            parameters["delay_ms"]?.toLongOrNull() != definition.durationMillis ||
            parameters["p64_initiator_kind_uid"] != senderRef.kindUid || parameters["p64_initiator_uid"] != senderRef.uid ||
            parameters["p64_start_target_kind"] != recipientRef.kindUid || parameters["p64_start_target_uid"] != recipientRef.uid ||
            parameters["p64_start_command_uid"].isNullOrBlank() || parameters["message_uid"].isNullOrBlank() ||
            parameters["message_text"].isNullOrBlank() || parameters.getValue("message_text").length > 2048 ||
            parameters["p64_start_proof_uid"]?.startsWith(Phase64ProcessActivation.START_PROOF +
                phase63Hash(Phase64BackgroundCodec.definition(definition).toString()) + ":") != true)
            return null
        val bound = Phase64ProcessActivation.bind(definition, senderRef, recipientRef, parameters.getValue("message_text"), process.uid)
        if (process.parameters.filterKeys { !it.startsWith("p64_") } != bound ||
            !parameters.getValue("p64_start_proof_uid").endsWith(":" + Phase64ProcessActivation.parameterFingerprint(bound)))
            return null
        val channel = DomainRef("INFORMATION_CHANNEL", CHANNEL)
        val disclosure = DomainRef("DISCLOSURE_POLICY", DISCLOSURE)
        val expected = when {
            principal == senderRef && purpose == "P64:INFO_SEND:MESSAGE" -> setOf(recipientRef, channel, disclosure)
            principal == recipientRef && purpose == "P64:INFO_RECEIVE" -> setOf(senderRef, channel, disclosure)
            else -> return null
        }
        if (references.size != expected.size || references.toSet() != expected) return null
        val origin = sender.locationRef ?: return null
        val destination = recipient.locationRef ?: return null
        val routeEvidence = if (WorldTopologyAnchor.same(origin, destination)) {
            "LOCAL:${origin.kindUid}:${origin.uid}"
        } else {
            val route = snapshot.route ?: return null
            if (!WorldTopologyAnchor.same(route.origin, origin) || !WorldTopologyAnchor.same(route.destination, destination) ||
                route.duration.milliseconds > definition.durationMillis || route.resourceCosts.isNotEmpty() ||
                route.edges.any { at < it.validFrom || it.validThrough?.let { through -> at > through } == true ||
                    !sender.executableAbilityUids.containsAll(it.requiredCapabilities) }) return null
            route.fingerprint
        }
        val evidenceUid = "P64:CHANNEL_ACCESS:${phase60Hash(listOf(scope.temporal.campaignUid, process.uid,
            principal.kindUid, principal.uid, sender.stateVersion.toString(), recipient.stateVersion.toString(),
            origin.kindUid, origin.uid, destination.kindUid, destination.uid, routeEvidence)
            .joinToString("") { "${it.length}:$it" })}"
        val trusted = TrustedPrincipalContext(scope.temporal.campaignUid, VisibilityPrincipalRef(principal.kindUid, principal.uid),
            AudienceKinds.WORLD_ACTOR, cognitionHolders = setOf(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,
                principal.uid, scope.temporal.campaignUid)))
        val path = Phase38AccessRuntimeAuthority.issuePath(trusted, InformationCarrierRef(scope.temporal.campaignUid,
            channel.kindUid, channel.uid), "P64:CORE:NAMED_COURIER_DELIVERY", evidenceUid, false, CarrierAccessStage.entries.toSet())
        return EffectiveAccessDecision.granted("P64:CORE_NAMED_MESSAGE_AUTHORIZED", path, path.resolvedStages)
    }
}

/** Captured by the canonical holder/body owner, never inferred from organization membership.
 * An anchor without a body location needs its actual position/mailbox owner's evidence. */
internal data class Phase64CommunicationPrincipalCapture(
    val principal: DomainRef,
    val holder: KnowledgeHolderRef,
    val body: MechanicalActorView? = null,
    val anchor: DomainRef? = body?.locationRef,
    val anchorEvidenceUid: String? = null
)

/** The existing authorized topology reader supplies this capture, including its principal. */
internal data class Phase64CommunicationRouteCapture(
    val scope: TemporalScope,
    val principal: DomainRef,
    val plan: WorldTravelPlan,
    val authorizationEvidenceUid: String
)

/** All references and effective records are exact, bounded, campaign-scoped owner reads at
 * currentOrder. The caller must recheck currentScope after capture and before returning a path. */
internal data class Phase64ScopedCommunicationSnapshot(
    val currentScope: TemporalScope,
    val currentOrder: Long,
    val canonicalReferences: Set<DomainRef>,
    val sender: Phase64CommunicationPrincipalCapture?,
    val recipient: Phase64CommunicationPrincipalCapture?,
    val senderAuthority: List<AccessAuthorityRecord>,
    val recipientAuthority: List<AccessAuthorityRecord>,
    val route: Phase64CommunicationRouteCapture? = null
)

internal sealed interface Phase64ScopedCommunicationPreparation {
    data class Ready(val senderAccess: EffectiveAccessDecision, val recipientAccess: EffectiveAccessDecision,
        val senderPurpose: String) : Phase64ScopedCommunicationPreparation {
        fun accessFor(principal: DomainRef, purpose: String): EffectiveAccessDecision? = when {
            purpose == senderPurpose && senderAccess.path?.principal == VisibilityPrincipalRef(principal.kindUid, principal.uid) -> senderAccess
            purpose == "P64:INFO_RECEIVE" && recipientAccess.path?.principal == VisibilityPrincipalRef(principal.kindUid, principal.uid) -> recipientAccess
            else -> null
        }
    }
    data class Unavailable(val reasonUid: String) : Phase64ScopedCommunicationPreparation
}

/** A versioned Phase38 carrier owner: SET_CARRIER_ACCESS records name the exact channel and
 * each CarrierAccessStage.name. Purpose grants are separate from these stage records. Neither
 * an explicit grant nor a recipe's registration alone proves comprehension or delivery. */
internal object Phase64ScopedCommunicationOwner {
    private const val MECHANISM = "P38:SCOPED_CARRIER_CHANNEL_DELIVERY_V1"
    private val personalKinds = setOf("NPC", "ACTOR", "CHARACTER", "PLAYER")
    private val institutionalKinds = setOf(KnowledgeHolderKinds.ORGANIZATION, KnowledgeHolderKinds.MILITARY_COMMAND,
        KnowledgeHolderKinds.CITY_ADMINISTRATION, KnowledgeHolderKinds.STATE, KnowledgeHolderKinds.INTELLIGENCE_SERVICE,
        KnowledgeHolderKinds.RESEARCH_TEAM, KnowledgeHolderKinds.LABORATORY, KnowledgeHolderKinds.GUILD,
        KnowledgeHolderKinds.COMPANY, KnowledgeHolderKinds.WORLD_SPECIFIC)
    private val grantKinds = AccessGrantKind.entries.map { it.name }.toSet()

    fun prepare(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope, at: WorldTimeTick, snapshot: Phase64ScopedCommunicationSnapshot,
        staged: List<PlayerDomainChangePayload> = emptyList()): Phase64ScopedCommunicationPreparation = try {
        prepareCaptured(definition, process, scope, at, snapshot, staged)
    } catch (_: IllegalArgumentException) {
        unavailable("INVALID_CAPTURE")
    } catch (_: ArithmeticException) {
        unavailable("CAPTURE_OVERFLOW")
    }

    private fun unavailable(reason: String) = Phase64ScopedCommunicationPreparation.Unavailable("P64:COMMUNICATION_$reason")

    private fun prepareCaptured(definition: BackgroundProcessDefinition, process: BackgroundProcessInstance,
        scope: BackgroundProcessEvaluationScope, at: WorldTimeTick, snapshot: Phase64ScopedCommunicationSnapshot,
        staged: List<PlayerDomainChangePayload>): Phase64ScopedCommunicationPreparation {
        if (snapshot.currentScope != scope.temporal || snapshot.currentOrder != scope.temporal.baseCommitOrder)
            return unavailable("STALE_CAPTURE")
        if (definition.domain != "INFORMATION" || definition.operation !in setOf("MESSAGE", "REPORT", "DIPLOMACY", "ESPIONAGE") ||
            process.definitionUid != definition.uid || process.definitionVersion != definition.version ||
            process.status !in setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.BLOCKED) || at < process.due)
            return unavailable("PROCESS_SCOPE")
        if (snapshot.canonicalReferences.size > 128 || snapshot.senderAuthority.size > 128 || snapshot.recipientAuthority.size > 128)
            return unavailable("CAPTURE_BUDGET")
        val parameters = backgroundParameters(definition, process)
        val sender = snapshot.sender ?: return unavailable("SENDER_OWNER_UNAVAILABLE")
        val recipient = snapshot.recipient ?: return unavailable("RECIPIENT_OWNER_UNAVAILABLE")
        val recipientRef = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        val channel = DomainRef("INFORMATION_CHANNEL", backgroundRequired(parameters, "channel_uid"))
        val disclosure = DomainRef("DISCLOSURE_POLICY", backgroundRequired(parameters, "disclosure_policy_uid"))
        if (sender.principal != process.actor || recipient.principal != recipientRef ||
            !validPrincipal(sender, scope.temporal.campaignUid, snapshot.canonicalReferences) ||
            !validPrincipal(recipient, scope.temporal.campaignUid, snapshot.canonicalReferences))
            return unavailable("PRINCIPAL_SCOPE")
        if (channel !in snapshot.canonicalReferences || disclosure !in snapshot.canonicalReferences)
            return unavailable("CHANNEL_OR_DISCLOSURE_UNAVAILABLE")
        if ((parameters["sender_kind"] != null || parameters["sender_uid"] != null) &&
            DomainRef(backgroundRequired(parameters, "sender_kind"), backgroundRequired(parameters, "sender_uid")) != sender.principal)
            return unavailable("PRINCIPAL_SCOPE")
        val delay = backgroundRequired(parameters, "delay_ms").toLong()
        if (delay < 0 || delay > definition.durationMillis ||
            at.milliseconds < Math.addExact(process.startedAt.milliseconds, delay))
            return unavailable("DELAY_NOT_SCHEDULED")
        val senderRecords = effective(snapshot.senderAuthority, sender.principal, snapshot.currentOrder, staged)
            ?: return unavailable("AUTHORITY_PRINCIPAL_MISMATCH")
        val recipientRecords = effective(snapshot.recipientAuthority, recipient.principal, snapshot.currentOrder, staged)
            ?: return unavailable("AUTHORITY_PRINCIPAL_MISMATCH")
        val senderPurpose = "P64:INFO_SEND:${definition.operation}"
        fun grants(records: List<AccessAuthorityRecord>, purpose: String) = listOf(channel, disclosure).all { ref ->
            records.any { it.operation in setOf(AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS) &&
                it.kindUid in grantKinds && it.valueUid == purpose && it.subjectKindUid == ref.kindUid && it.subjectUid == ref.uid }
        }
        if (!grants(senderRecords, senderPurpose) || !grants(recipientRecords, "P64:INFO_RECEIVE"))
            return unavailable("PURPOSE_GRANT_REQUIRED")
        fun stages(records: List<AccessAuthorityRecord>) = records.filter {
            it.operation == AccessOperation.SET_CARRIER_ACCESS && it.kindUid in grantKinds &&
                it.subjectKindUid == channel.kindUid && it.subjectUid == channel.uid &&
                it.valueUid in CarrierAccessStage.entries.map { stage -> stage.name }
        }
        val senderStages = stages(senderRecords)
        val recipientStages = stages(recipientRecords)
        val requiredStages = CarrierAccessStage.entries.map { it.name }.toSet()
        if (!senderStages.map { it.valueUid }.toSet().containsAll(requiredStages) ||
            !recipientStages.map { it.valueUid }.toSet().containsAll(requiredStages))
            return unavailable("CARRIER_OWNER_UNAVAILABLE")
        val origin = sender.anchor ?: return unavailable("SENDER_ANCHOR_UNAVAILABLE")
        val destination = recipient.anchor ?: return unavailable("RECIPIENT_ANCHOR_UNAVAILABLE")
        val routeEvidence = if (WorldTopologyAnchor.same(origin, destination)) {
            "LOCAL:${origin.kindUid}:${origin.uid}"
        } else {
            val route = snapshot.route ?: return unavailable("ROUTE_OWNER_UNAVAILABLE")
            if (route.scope != scope.temporal || route.principal != sender.principal || route.authorizationEvidenceUid.isBlank() ||
                !WorldTopologyAnchor.same(route.plan.origin, origin) || !WorldTopologyAnchor.same(route.plan.destination, destination) ||
                route.plan.duration.milliseconds > delay || route.plan.resourceCosts.isNotEmpty() ||
                route.plan.edges.any { at < it.validFrom || it.validThrough?.let { end -> at > end } == true ||
                    !sender.body?.executableAbilityUids.orEmpty().containsAll(it.requiredCapabilities) })
                return unavailable("ROUTE_SCOPE")
            route.authorizationEvidenceUid + ":" + route.plan.fingerprint
        }
        fun access(principal: Phase64CommunicationPrincipalCapture, purpose: String, records: List<AccessAuthorityRecord>): EffectiveAccessDecision {
            val relevant = records.filter { it.valueUid == purpose || it in stages(records) }
                .sortedWith(compareBy<AccessAuthorityRecord> { it.recordUid }.thenBy { it.createdOrder })
            val evidence = "P64:CHANNEL_ACCESS:${phase60Hash((listOf(scope.temporal.campaignUid, scope.temporal.historyGenerationUid,
                snapshot.currentOrder.toString(), process.uid, definition.uid, definition.version.toString(), principal.principal.kindUid,
                principal.principal.uid, purpose, channel.uid, disclosure.uid, origin.kindUid, origin.uid, destination.kindUid,
                destination.uid, sender.body?.stateVersion?.toString().orEmpty(), recipient.body?.stateVersion?.toString().orEmpty(),
                sender.anchorEvidenceUid.orEmpty(), recipient.anchorEvidenceUid.orEmpty(), routeEvidence) + relevant.flatMap {
                    listOf(it.recordUid, it.operation.name, it.kindUid, it.valueUid, it.subjectKindUid.orEmpty(), it.subjectUid.orEmpty(),
                        it.validFromOrder.toString(), it.validUntilOrder?.toString().orEmpty(), it.createdOrder.toString())
                }).joinToString("") { "${it.length}:$it" })}"
            // No roles, membership, control or another holder's cognition are transferred.
            val trusted = TrustedPrincipalContext(scope.temporal.campaignUid,
                VisibilityPrincipalRef(principal.principal.kindUid, principal.principal.uid), AudienceKinds.WORLD_ACTOR)
            val path = Phase38AccessRuntimeAuthority.issuePath(trusted,
                InformationCarrierRef(scope.temporal.campaignUid, channel.kindUid, channel.uid), MECHANISM, evidence, false,
                CarrierAccessStage.entries.toSet())
            return EffectiveAccessDecision.granted("P64:SCOPED_CHANNEL_DELIVERY_AUTHORIZED", path, path.resolvedStages)
        }
        return Phase64ScopedCommunicationPreparation.Ready(access(sender, senderPurpose, senderRecords),
            access(recipient, "P64:INFO_RECEIVE", recipientRecords), senderPurpose)
    }

    private fun validPrincipal(capture: Phase64CommunicationPrincipalCapture, campaign: String, canonical: Set<DomainRef>): Boolean {
        val principal = capture.principal
        if (principal !in canonical || principal.kindUid !in personalKinds && principal.kindUid !in institutionalKinds) return false
        val holderKind = if (principal.kindUid in personalKinds) KnowledgeHolderKinds.CHARACTER else principal.kindUid
        if (capture.holder != KnowledgeHolderRef(holderKind, principal.uid, campaign)) return false
        val body = capture.body
        if (principal.kindUid in personalKinds && body == null) return false
        if (body != null && (body.campaignUid != campaign || body.actor != principal ||
                principal.kindUid in personalKinds && body.kind !in setOf(MechanicalActorKind.NPC, MechanicalActorKind.FORMER_PLAYER,
                    MechanicalActorKind.ACTIVE_PLAYER))) return false
        val anchor = capture.anchor ?: return true // Produces the specific missing-anchor diagnostic later.
        if (anchor.kindUid !in setOf("PLACE", "LOCATION") || anchor !in canonical) return false
        if (body?.locationRef != null) return WorldTopologyAnchor.same(body.locationRef, anchor)
        return !capture.anchorEvidenceUid.isNullOrBlank()
    }

    internal fun effective(records: List<AccessAuthorityRecord>, principal: DomainRef, order: Long,
        staged: List<PlayerDomainChangePayload>): List<AccessAuthorityRecord>? {
        val exact = VisibilityPrincipalRef(principal.kindUid, principal.uid)
        if (records.any { it.principal != exact }) return null
        records.forEach { record ->
            require(record.createdOrder >= 0)
            AccessAuthorityChangeValidator.requireValid(AccessAuthorityChange(record.operation, record.recordUid,
                record.principal.kindUid, record.principal.uid, record.kindUid, record.valueUid,
                record.subjectKindUid, record.subjectUid, record.validFromOrder, record.validUntilOrder, record.delegatedByPrincipalUid))
        }
        // Reject future-created captures as authority; canonical reads remain at the base order.
        val canonical = records.filter { it.createdOrder <= order && it.validFromOrder <= order &&
            (it.validUntilOrder == null || it.validUntilOrder >= order) }
        val proposedOrder = Math.addExact(order, 1)
        return Phase64OrganizationsInformationOwners.effectiveOverlay(canonical, principal, order, staged)
            .filter { it.kindUid in grantKinds && (it.validUntilOrder == null || it.validUntilOrder >= proposedOrder) }
    }
}

internal data class Phase64InstitutionDecisionCapture(
    val context: NpcDecisionContextEnvelope,
    val currentScope: NpcDecisionScope,
    val proposal: NpcDecisionProposal? = null,
    val selected: NpcDecisionResult.Selected? = null
)

internal sealed interface Phase64InstitutionDecisionProjection {
    data class Ready(val capture: Phase64InstitutionDecisionCapture) : Phase64InstitutionDecisionProjection
    data class Unavailable(val reasonUid: String) : Phase64InstitutionDecisionProjection
}

/** A production capture may already contain Phase62's protected model selection. Consume that
 * exact receipt, rather than selecting the definition's fixed option as an implicit order.
 * The proposal/routine path remains available for existing pure owner callers and fixtures. */
internal object Phase64InstitutionDecisionOwner {
    fun prepare(parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        actor: DomainRef, capture: Phase64InstitutionDecisionCapture,
        prepareSelectedAction: (NpcDecisionContextEnvelope, NpcDecisionResult.Selected) -> WorldConsequencePlan
    ): WorldConsequencePlan {
        val selected = capture.selected ?: return Phase64OrganizationsInformationOwners.prepareDecision(
            parameters, scope, actor, capture.context, capture.currentScope, capture.proposal,
            prepareSelectedAction = prepareSelectedAction)
        val context = capture.context
        if (context.scope.temporal != scope.temporal || context.scope.actor != actor || capture.currentScope.actor != actor)
            return backgroundBlocked("P64:INSTITUTIONAL_DECISION_SCOPE")
        if (capture.currentScope != context.scope) return backgroundBlocked("P62:STALE_SCOPE")
        if (context.contextFingerprint != context.computeFingerprint() ||
            !selected.authorization.matches(context.scope, context.contextFingerprint, selected.option) ||
            selected.option !in context.options)
            return backgroundBlocked("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING")
        val optionUid = backgroundRequired(parameters, "option_uid")
        if (context.options.none { it.uid == optionUid }) return backgroundBlocked("P64:INSTITUTIONAL_OPTION_UNAVAILABLE")
        if (selected.option.uid != optionUid) return backgroundBlocked("P64:INSTITUTIONAL_OPTION_NOT_SELECTED")
        val result = prepareSelectedAction(context, selected)
        // A reflected/denied action admission cannot commit the model's cognitive prefix.
        if (result.status != BackgroundProcessStatus.COMPLETED)
            return backgroundBlocked(result.reasonUid ?: "P64:INSTITUTIONAL_ACTION_NOT_ADMITTED")
        return result.copy(changes = selected.brainChanges + result.changes,
            sourceUids = (result.sourceUids + listOf(selected.authorization.decisionUid, optionUid)).distinct())
    }
}

/** Reuses the existing protected Phase62 projection, including its mobile budget and legal
 * affordances. Instantiate for one immutable evaluation input, then pass ::captureDecision and
 * ::prepareSelectedAction to preparePhase64InstitutionOwnedEffect. The input's speculative
 * prefix must not be discarded; the context port receives the complete supplied staged prefix.
 *
 * projectNpcDecision overlays brains only. The scoped guard therefore permits unrelated
 * foreground movement/costs and the evaluated time, but refuses changed actor/target/access/
 * knowledge prerequisites rather than replacing them with an older canonical read.
 *
 * A selected reaction is admitted through Phase62's ordinary timed action preflight and its
 * pending-state delegation. Phase60 commits the brain, pending state and deadline together.
 * Missing admission remains BLOCKED; this helper cannot manufacture a running plan. */
internal class Phase64InstitutionDecisionCallbacks(
    input: TemporalOwnerInput,
    private val contexts: NpcPhysicalContextPort,
    private val currentScope: () -> TemporalScope,
    /** Called by external evaluation, before the canonical database transaction is opened. */
    private val selectDecision: ((NpcContextResult.Ready) -> NpcDecisionResult)? = null,
    private val prepareAction: ((NpcContextResult.Ready, NpcDecisionResult.Selected,
        TemporalOwnerInput) -> NpcActionPreparation)? = null
) {
    private val capturedInput = input.copy(actions = input.actions.toList(), deadlines = input.deadlines.toList(),
        stagedChanges = input.stagedChanges.toList(), stagedEffects = input.stagedEffects.toList(), peerStates = input.peerStates.toMap())
    private data class CapturedDecision(val input: TemporalOwnerInput, val processUid: String?)
    private val decisions = linkedMapOf<NpcDecisionContextEnvelope, CapturedDecision>()

    fun captureDecision(actor: DomainRef, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        staged: List<PlayerDomainChangePayload>): Phase64InstitutionDecisionCapture? =
        (capture(actor, parameters, scope, staged) as? Phase64InstitutionDecisionProjection.Ready)?.capture

    /** Typed diagnostic form; the nullable callback above preserves the current read-port API. */
    fun capture(actor: DomainRef, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        staged: List<PlayerDomainChangePayload>): Phase64InstitutionDecisionProjection {
        fun unavailable(reason: String) = Phase64InstitutionDecisionProjection.Unavailable(reason)
        if (scope.temporal != capturedInput.scope || currentScope() != scope.temporal)
            return unavailable("P64:INSTITUTIONAL_DECISION_SCOPE")
        if (parameters["_p64_effective_ms"]?.toLongOrNull() != capturedInput.through.milliseconds ||
            capturedInput.through < capturedInput.from)
            return unavailable("P64:INSTITUTIONAL_DECISION_TIME")
        if (staged.take(capturedInput.stagedChanges.size) != capturedInput.stagedChanges)
            return unavailable("P64:INSTITUTIONAL_STAGED_PREFIX_CHANGED")
        if (staged.filterIsInstance<NpcBrainChange>().any {
                it.campaignUid != scope.temporal.campaignUid || it.historyGenerationUid != scope.temporal.historyGenerationUid })
            return unavailable("P64:INSTITUTIONAL_STAGED_SCOPE")
        val decisionInput = capturedInput.copy(stagedChanges = staged.toList())
        val projected = when (val result = contexts.project(actor, decisionInput, null)) {
            is NpcContextResult.Unavailable -> return unavailable(result.reasonUid)
            is NpcContextResult.Ready -> result
        }
        val context = projected.context
        if (currentScope() != scope.temporal || context.scope.temporal != scope.temporal ||
            context.scope.actor != actor || context.scope.atTime != capturedInput.through)
            return unavailable("P64:INSTITUTIONAL_DECISION_SCOPE")
        if (actor.uid == context.scope.activePlayerUid)
            return unavailable("P62:ACTIVE_PLAYER_CONTROL_FORBIDDEN")
        val stagedBrains = staged.filterIsInstance<NpcBrainChange>()
        if (!validNpcBrainChains(stagedBrains) || stagedBrains.lastOrNull { it.actor == actor }?.let {
                it.stateCanonical != NpcBrainCodec.encode(context.brain)
            } == true)
            return unavailable("P64:INSTITUTIONAL_STAGED_BRAIN_BINDING")
        if (!projected.budget.safeForAi || context.contextFingerprint != context.computeFingerprint() ||
            context.projectionFingerprint != phase60Hash(projected.budget.canonicalPayload()))
            return unavailable("P64:INSTITUTIONAL_DECISION_PROJECTION_BINDING")
        if (!Phase64InstitutionStagedProjection.supports(context, decisionInput))
            return unavailable("P64:INSTITUTIONAL_STAGED_PROJECTION_UNAVAILABLE")
        if (decisions.size >= 128) return unavailable("P64:INSTITUTIONAL_DECISION_CAPTURE_LIMIT")
        val selected = selectDecision?.let { select ->
            val decision = select(projected)
            if (currentScope() != scope.temporal) return unavailable("P64:INSTITUTIONAL_DECISION_SCOPE")
            if (staged != decisionInput.stagedChanges ||
                staged.take(capturedInput.stagedChanges.size) != capturedInput.stagedChanges)
                return unavailable("P64:INSTITUTIONAL_STAGED_PREFIX_CHANGED")
            if (context.contextFingerprint != context.computeFingerprint())
                return unavailable("P64:INSTITUTIONAL_DECISION_PROJECTION_CHANGED")
            val recaptured = when (val result = contexts.project(actor, decisionInput, null)) {
                is NpcContextResult.Unavailable -> return unavailable(result.reasonUid)
                is NpcContextResult.Ready -> result
            }
            if (currentScope() != scope.temporal || recaptured.context.scope != context.scope ||
                recaptured.context.contextFingerprint != context.contextFingerprint ||
                recaptured.context.contextFingerprint != recaptured.context.computeFingerprint() ||
                !recaptured.budget.safeForAi ||
                recaptured.context.projectionFingerprint != phase60Hash(recaptured.budget.canonicalPayload()))
                return unavailable("P64:INSTITUTIONAL_DECISION_PROJECTION_CHANGED")
            if (!Phase64InstitutionStagedProjection.supports(recaptured.context, decisionInput))
                return unavailable("P64:INSTITUTIONAL_STAGED_PROJECTION_UNAVAILABLE")
            when (decision) {
                is NpcDecisionResult.Unavailable -> return unavailable(decision.reasonUid)
                is NpcDecisionResult.Reflected -> return unavailable("P64:INSTITUTIONAL_ACTION_NOT_SELECTED")
                is NpcDecisionResult.Selected -> {
                    if (!decision.authorization.matches(context.scope, context.contextFingerprint, decision.option) ||
                        decision.option !in context.options)
                        return unavailable("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING")
                    decision
                }
            }
        }
        decisions[context] = CapturedDecision(decisionInput, parameters["_p64_process_uid"])
        return Phase64InstitutionDecisionProjection.Ready(Phase64InstitutionDecisionCapture(context, context.scope,
            selected = selected))
    }

    fun prepareSelectedAction(context: NpcDecisionContextEnvelope, selected: NpcDecisionResult.Selected): WorldConsequencePlan {
        if (currentScope() != capturedInput.scope || context.scope.temporal != capturedInput.scope ||
            context.scope.atTime != capturedInput.through)
            return backgroundBlocked("P64:INSTITUTIONAL_DECISION_SCOPE")
        if (context.scope.actor.uid == context.scope.activePlayerUid)
            return backgroundBlocked("P62:ACTIVE_PLAYER_CONTROL_FORBIDDEN")
        if (context.contextFingerprint != context.computeFingerprint() ||
            !selected.authorization.matches(context.scope, context.contextFingerprint, selected.option) ||
            selected.option !in context.options)
            return backgroundBlocked("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING")
        val prepare = prepareAction ?: return backgroundBlocked("P64:INSTITUTIONAL_ACTION_OWNER_STATE_REQUIRED")
        val captured = decisions[context] ?: return backgroundBlocked("P64:INSTITUTIONAL_DECISION_NOT_CAPTURED")
        val processUid = captured.processUid?.takeIf { it.isNotBlank() }
            ?: return backgroundBlocked("P64:INSTITUTIONAL_PROCESS_IDENTITY_REQUIRED")
        val projected = when (val result = contexts.project(context.scope.actor, captured.input, null)) {
            is NpcContextResult.Unavailable -> return backgroundBlocked(result.reasonUid)
            is NpcContextResult.Ready -> result
        }
        if (currentScope() != capturedInput.scope || projected.context.contextFingerprint != context.contextFingerprint ||
            projected.context.contextFingerprint != projected.context.computeFingerprint() || !projected.budget.safeForAi ||
            projected.context.projectionFingerprint != phase60Hash(projected.budget.canonicalPayload()))
            return backgroundBlocked("P64:INSTITUTIONAL_DECISION_PROJECTION_CHANGED")
        if (!Phase64InstitutionStagedProjection.supports(projected.context, captured.input))
            return backgroundBlocked("P64:INSTITUTIONAL_STAGED_PROJECTION_UNAVAILABLE")
        return when (val prepared = prepare(projected, selected, captured.input)) {
            is NpcActionPreparation.Skipped -> backgroundBlocked(prepared.reasonUid)
            is NpcActionPreparation.Reflected -> backgroundBlocked("P64:INSTITUTIONAL_ACTION_NOT_ADMITTED")
            is NpcActionPreparation.Started -> {
                // prepareDecision appends the selected cognitive prefix once. Phase62's
                // preflight returns that same prefix followed by its single lifecycle change.
                if (prepared.changes.take(selected.brainChanges.size) != selected.brainChanges ||
                    prepared.changes.size != selected.brainChanges.size + 1 || !validNpcBrainChains(prepared.changes))
                    return backgroundBlocked("P64:INSTITUTIONAL_ACTION_CHANGE_BINDING")
                val delegation = try {
                    NpcActionProcess.prepareDelegation(captured.input, prepared, processUid)
                } catch (_: IllegalArgumentException) {
                    return backgroundBlocked("P64:INSTITUTIONAL_ACTION_OWNER_STATE_BINDING")
                }
                WorldConsequencePlan(changes = prepared.changes.drop(selected.brainChanges.size),
                    sourceUids = listOf(processUid, prepared.pending.planUid, prepared.pending.optionUid),
                    progressUnits = 1, ownerDelegations = listOf(delegation))
            }
        }
    }
}

/** Proves only that a staged owner change cannot alter this protected choice set. Relevant
 * changes still need their owner's actual projection; this is not a mechanical/access overlay
 * and never grants a new role, belief, capability or resource. Keep the full prefix for preflight. */
internal object Phase64InstitutionStagedProjection {
    fun supports(context: NpcDecisionContextEnvelope, input: TemporalOwnerInput): Boolean {
        if (context.scope.temporal != input.scope || context.scope.atTime != input.through) return false
        val dependencies = buildSet {
            add(context.scope.actor.uid)
            add(context.brain.knowledgeHolder.holderUid)
            context.options.forEach { option ->
                option.target?.let { add(it.uid) }
                addAll(option.parameters.values)
                context.records.filter { it.uid in option.supportingRecordUids }.forEach { record ->
                    add(record.acquisitionUid)
                    addAll(record.subjectRefs.map { it.uid })
                }
            }
        }
        val routeSensitive = context.options.any {
            it.mechanicalEffectKindUid?.substringAfterLast(':')?.uppercase() in setOf("LOCATION_TRANSITION", "MOVEMENT", "DISPLACEMENT")
        }
        val evaluationOrder = Math.addExact(input.scope.baseCommitOrder, 1)
        fun unrelated(subject: DomainRef) = subject.uid !in dependencies
        fun supported(change: PlayerDomainChangePayload): Boolean = when (change) {
            is NpcBrainChange -> change.campaignUid == input.scope.campaignUid &&
                change.historyGenerationUid == input.scope.historyGenerationUid
            is BackgroundProcessChange -> change.campaignUid == input.scope.campaignUid &&
                change.historyGenerationUid == input.scope.historyGenerationUid
            is TemporalStateChange -> change.campaignUid == input.scope.campaignUid &&
                change.proposedTime <= input.through && change.expectedTime <= input.from &&
                Phase60ProcessStateCodec.decode(change.processStatesCanonical).associateBy { it.ownerUid } == input.peerStates
            is AccessAuthorityChange -> change.validFromOrder > evaluationOrder || change.principalUid !in dependencies
            is KnowledgeAcquisitionChange -> change.acquisition.holder.campaignUid == input.scope.campaignUid &&
                change.acquisition.holder.holderUid != context.brain.knowledgeHolder.holderUid
            is StatChange -> unrelated(change.subject)
            is ResourceChange -> unrelated(change.subject)
            is SkillChange -> unrelated(change.subject)
            is TechniqueChange -> unrelated(change.subject)
            is InnateChange -> unrelated(change.subject)
            is InventoryChange -> unrelated(change.subject) && change.itemInstanceUid !in dependencies
            is EquipmentChange -> unrelated(change.subject) && change.itemInstanceUid !in dependencies
            is ConditionChange -> unrelated(change.subject)
            is RuntimeChange -> unrelated(change.subject)
            is WoundChange -> unrelated(change.subject)
            is SpatialChange -> unrelated(change.subject)
            is EquipmentIntegrityChange -> unrelated(change.subject)
            is StructureIntegrityChange -> unrelated(change.subject)
            is MechanicalTrackChange -> unrelated(change.subject)
            is AggregatePopulationChange -> unrelated(change.subject)
            is FinancialChange -> change.fromAccountUid !in dependencies && change.toAccountUid !in dependencies
            is DevelopmentProjectChange -> change.projectUid !in dependencies && change.evidenceRefs.all(::unrelated)
            is MechanicalActorGenesisChange -> unrelated(change.actor)
            is WorldSimulationChange -> change.campaignUid == input.scope.campaignUid &&
                change.historyGenerationUid.value == input.scope.historyGenerationUid &&
                (!routeSensitive || change.edges.isEmpty() && change.skeleton == null) &&
                change.actorExpansions.all { unrelated(it.actor) } &&
                change.populationManifests.all { unrelated(it.aggregate) } &&
                change.populationExtractions.all { unrelated(it.member) }
            else -> false
        }
        if (!input.stagedChanges.all(::supported)) return false
        // Materialize with the ordinary Phase50 owner, including verified effect targets.
        // Unknown/invalid effects cannot be declared unrelated merely from their summary.
        return try {
            input.stagedEffects.all { effect ->
                val impacts = canonicalMechanicsCommandEffects(effect.asStagedMechanics(), effect.target)
                impacts != null && impacts.all { impact ->
                    val material = MechanicalEffectMaterializer.materialize(impact)
                    material is MechanicalEffectMaterializationResult.Materialized && material.changes.all { supported(it.payload) }
                }
            }
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        }
    }
}

internal data class Phase64InstitutionEspionageCapture(
    val access: EffectiveAccessDecision,
    val carrierClaim: KnowledgeClaim,
    val recipientHolder: KnowledgeHolderRef,
    val sourceAcquisition: KnowledgeAcquisition? = null
)

/** Exact existing Phase38 stage records and one registered carrier-content contract. No
 * inventory possession, other holder's acquisitions or raw recipe text supply this claim. */
internal data class Phase64InstitutionCarrierSnapshot(
    val currentScope: TemporalScope,
    val currentOrder: Long,
    val canonicalReferences: Set<DomainRef>,
    val activity: NpcActivityContract?,
    val authority: List<AccessAuthorityRecord>
)

internal sealed interface Phase64InstitutionCarrierProjection {
    data class Ready(val capture: Phase64InstitutionEspionageCapture) : Phase64InstitutionCarrierProjection
    data class Unavailable(val reasonUid: String) : Phase64InstitutionCarrierProjection
}

/** Existing SET_CARRIER_ACCESS is the carrier owner: a separate exact policy grant plus
 * every actual prerequisite stage proves reading. A recipe, grant, co-location or bare
 * COMPREHENDED flag alone is not a path and never creates campaign truth or private cognition. */
internal object Phase64InstitutionCarrierOwner {
    fun capture(actor: DomainRef, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        snapshot: Phase64InstitutionCarrierSnapshot, staged: List<PlayerDomainChangePayload> = emptyList()
    ): Phase64InstitutionCarrierProjection = try {
        captureExact(actor, parameters, scope, snapshot, staged)
    } catch (_: IllegalArgumentException) {
        unavailable("INVALID_CAPTURE")
    } catch (_: ArithmeticException) {
        unavailable("CAPTURE_OVERFLOW")
    }

    private fun unavailable(reason: String) = Phase64InstitutionCarrierProjection.Unavailable("P64:ESPIONAGE_$reason")

    private fun captureExact(actor: DomainRef, parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        snapshot: Phase64InstitutionCarrierSnapshot, staged: List<PlayerDomainChangePayload>): Phase64InstitutionCarrierProjection {
        if (snapshot.currentScope != scope.temporal || snapshot.currentOrder != scope.temporal.baseCommitOrder)
            return unavailable("STALE_CARRIER_CAPTURE")
        if (snapshot.canonicalReferences.size > 128 || snapshot.authority.size > 128)
            return unavailable("CARRIER_CAPTURE_BUDGET")
        val activity = snapshot.activity ?: return unavailable("REGISTERED_CARRIER_UNAVAILABLE")
        val reading = activity.reading ?: return unavailable("REGISTERED_CARRIER_UNAVAILABLE")
        val carrier = DomainRef(backgroundRequired(parameters, "carrier_kind"), backgroundRequired(parameters, "carrier_uid"))
        val recipient = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        if (activity.ruleUid != parameters["carrier_rule_uid"] ||
            activity.version != parameters["carrier_rule_version"]?.toIntOrNull() || reading.carrier != carrier ||
            reading.accessPolicyUid != parameters["espionage_policy_uid"])
            return unavailable("REGISTERED_CARRIER_BINDING")
        if (!snapshot.canonicalReferences.containsAll(setOf(actor, carrier, recipient)))
            return unavailable("CARRIER_REFERENCE_UNAVAILABLE")
        // Until the source-content owner projects truth mutations, do not read its old value.
        if (staged.filterIsInstance<CampaignTruthChange>().any { it.subjectUid == carrier.uid })
            return unavailable("STAGED_CARRIER_CONTENT_UNAVAILABLE")
        val records = Phase64ScopedCommunicationOwner.effective(snapshot.authority, actor, snapshot.currentOrder, staged)
            ?: return unavailable("CARRIER_PRINCIPAL_MISMATCH")
        val grantKinds = AccessGrantKind.entries.map { it.name }.toSet()
        val exactRecords = records.filter { it.kindUid in grantKinds &&
            it.subjectKindUid == carrier.kindUid && it.subjectUid == carrier.uid }
        if (exactRecords.none { it.operation in setOf(AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS) &&
                it.valueUid == reading.accessPolicyUid }) return unavailable("CARRIER_POLICY_GRANT_REQUIRED")
        val stageRecords = exactRecords.filter { it.operation == AccessOperation.SET_CARRIER_ACCESS &&
            it.valueUid in CarrierAccessStage.entries.map { stage -> stage.name } }
        if (!stageRecords.map { it.valueUid }.toSet().containsAll(CarrierAccessStage.entries.map { it.name }))
            return unavailable("CARRIER_STAGE_REQUIRED")
        val evidence = "P64:CARRIER_ACCESS:${phase60Hash((listOf(scope.temporal.campaignUid,
            scope.temporal.historyGenerationUid, snapshot.currentOrder.toString(), scope.temporal.authoritativeFingerprint,
            actor.kindUid, actor.uid, recipient.kindUid, recipient.uid, activity.ruleUid, activity.version.toString(),
            activity.fingerprint, carrier.kindUid, carrier.uid, reading.accessPolicyUid) +
            exactRecords.filter { it.valueUid == reading.accessPolicyUid || it in stageRecords }.sortedBy { it.recordUid }.flatMap {
                listOf(it.recordUid, it.operation.name, it.kindUid, it.valueUid, it.subjectKindUid.orEmpty(), it.subjectUid.orEmpty(),
                    it.validFromOrder.toString(), it.validUntilOrder?.toString().orEmpty(), it.createdOrder.toString())
            }).joinToString("") { "${it.length}:$it" })}"
        val trusted = TrustedPrincipalContext(scope.temporal.campaignUid,
            VisibilityPrincipalRef(actor.kindUid, actor.uid), AudienceKinds.WORLD_ACTOR)
        val path = Phase38AccessRuntimeAuthority.issuePath(trusted,
            InformationCarrierRef(scope.temporal.campaignUid, carrier.kindUid, carrier.uid),
            "P38:REGISTERED_CARRIER_STAGES_V1", evidence, false, CarrierAccessStage.entries.toSet())
        val holderKind = if (recipient.kindUid in setOf("NPC", "ACTOR", "PLAYER", "CHARACTER"))
            KnowledgeHolderKinds.CHARACTER else recipient.kindUid
        return Phase64InstitutionCarrierProjection.Ready(Phase64InstitutionEspionageCapture(
            EffectiveAccessDecision.granted("P64:AUTHORIZED_REGISTERED_CARRIER_STAGES", path, path.resolvedStages),
            reading.claim, KnowledgeHolderRef(holderKind, recipient.uid, scope.temporal.campaignUid)))
    }
}

/** The integrator enables deadline admission only after wiring these requests into the one
 * ordinary Phase60 temporal commit. Until then an assignment cannot silently lose its timer. */
internal fun preparePhase64InstitutionOwnedEffect(
    db: SQLiteDatabase, campaign: String, operation: String, actor: DomainRef,
    parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
    staged: List<PlayerDomainChangePayload>,
    deadlineAdmission: ((WorldConsequencePlan) -> Boolean)? = null,
    deadlineView: List<WorldProcessDeadline>? = null,
    captureDecision: ((DomainRef, Map<String, String>, BackgroundProcessEvaluationScope,
        List<PlayerDomainChangePayload>) -> Phase64InstitutionDecisionCapture?)? = null,
    prepareSelectedAction: ((NpcDecisionContextEnvelope, NpcDecisionResult.Selected) -> WorldConsequencePlan)? = null,
    captureEspionage: ((DomainRef, Map<String, String>, BackgroundProcessEvaluationScope,
        List<PlayerDomainChangePayload>) -> Phase64InstitutionEspionageCapture?)? = null
): WorldConsequencePlan {
    val result = Phase64InstitutionProductionReads.prepare(db, campaign, operation, actor, parameters, scope,
        staged, deadlineView, captureDecision, prepareSelectedAction, captureEspionage)
    if ((result.deadlineAdds.isNotEmpty() || result.deadlineRemovals.isNotEmpty()) && deadlineAdmission?.invoke(result) != true)
        return backgroundBlocked("P64:DUTY_TEMPORAL_ADMISSION_UNAVAILABLE")
    return result
}

/** Production read-only dispatch. Exact catalog lookups never enumerate the world's NPCs.
 * ASSIGN/REVOKE use activity_rule_uid/activity_rule_version (default duty_uid/duty_version).
 * ESPIONAGE uses carrier_rule_uid/carrier_rule_version for a registered Phase62 readable carrier;
 * a custom registered covert access owner may instead supply the sealed capture callback.
 * Financial allocation names recipient_account_uid; no default/new account is manufactured.
 * Optional callbacks are the existing Phase62 projection/action and Phase38 access owners,
 * not AI-supplied authorization. Missing execution context is typed BLOCKED. */
internal object Phase64InstitutionProductionReads {
    /** Thin capture over existing Phase62/38 owners. The production caller validates these
     * named canonical references and currentScope in its bounded read, then wires Ready.capture
     * into captureEspionage. Missing/invalid stage authority is a typed unavailable, not possession. */
    fun captureRegisteredEspionage(db: SQLiteDatabase, campaign: String, actor: DomainRef,
        parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope, currentScope: TemporalScope,
        canonicalReferences: Set<DomainRef>, staged: List<PlayerDomainChangePayload>): Phase64InstitutionCarrierProjection {
        if (campaign != scope.temporal.campaignUid || currentScope != scope.temporal)
            return Phase64InstitutionCarrierProjection.Unavailable("P64:ESPIONAGE_STALE_CARRIER_CAPTURE")
        if (!Phase38AccessAuthoritySchema.isReady(db))
            return Phase64InstitutionCarrierProjection.Unavailable("P64:ESPIONAGE_CARRIER_AUTHORITY_UNAVAILABLE")
        return try {
            val uid = backgroundRequired(parameters, "carrier_rule_uid")
            val version = Math.toIntExact(backgroundPositive(parameters, "carrier_rule_version"))
            val activity = contract(db, campaign, uid, version)
            val records = AccessAuthorityStore(db, campaign).effective(VisibilityPrincipalRef(actor.kindUid, actor.uid),
                scope.temporal.baseCommitOrder)
            Phase64InstitutionCarrierOwner.capture(actor, parameters, scope,
                Phase64InstitutionCarrierSnapshot(currentScope, currentScope.baseCommitOrder, canonicalReferences, activity, records), staged)
        } catch (_: IllegalArgumentException) {
            Phase64InstitutionCarrierProjection.Unavailable("P64:ESPIONAGE_INVALID_CAPTURE")
        } catch (_: ArithmeticException) {
            Phase64InstitutionCarrierProjection.Unavailable("P64:ESPIONAGE_CAPTURE_OVERFLOW")
        }
    }

    fun prepare(
        db: SQLiteDatabase,
        campaign: String,
        operation: String,
        actor: DomainRef,
        parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope,
        staged: List<PlayerDomainChangePayload>,
        deadlineView: List<WorldProcessDeadline>? = null,
        captureDecision: ((DomainRef, Map<String, String>, BackgroundProcessEvaluationScope,
            List<PlayerDomainChangePayload>) -> Phase64InstitutionDecisionCapture?)? = null,
        prepareSelectedAction: ((NpcDecisionContextEnvelope, NpcDecisionResult.Selected) -> WorldConsequencePlan)? = null,
        captureEspionage: ((DomainRef, Map<String, String>, BackgroundProcessEvaluationScope,
            List<PlayerDomainChangePayload>) -> Phase64InstitutionEspionageCapture?)? = null
    ): WorldConsequencePlan = try {
        prepareCaptured(db, campaign, operation, actor, parameters, scope, staged, deadlineView,
            captureDecision, prepareSelectedAction, captureEspionage)
    } catch (_: IllegalArgumentException) {
        backgroundBlocked("P64:INSTITUTION_OWNER_INVALID_PARAMETERS")
    } catch (_: ArithmeticException) {
        backgroundBlocked("P64:INSTITUTION_OWNER_OVERFLOW")
    }

    private fun prepareCaptured(
        db: SQLiteDatabase, campaign: String, operation: String, actor: DomainRef,
        parameters: Map<String, String>, scope: BackgroundProcessEvaluationScope,
        staged: List<PlayerDomainChangePayload>, deadlineView: List<WorldProcessDeadline>?,
        captureDecision: ((DomainRef, Map<String, String>, BackgroundProcessEvaluationScope,
            List<PlayerDomainChangePayload>) -> Phase64InstitutionDecisionCapture?)?,
        prepareSelectedAction: ((NpcDecisionContextEnvelope, NpcDecisionResult.Selected) -> WorldConsequencePlan)?,
        captureEspionage: ((DomainRef, Map<String, String>, BackgroundProcessEvaluationScope,
            List<PlayerDomainChangePayload>) -> Phase64InstitutionEspionageCapture?)?
    ): WorldConsequencePlan {
        if (operation !in Phase64OrganizationsInformationAdapter.registeredOwnerOperations)
            return backgroundBlocked("P64:INSTITUTION_OWNER_OPERATION_UNAVAILABLE")
        if (campaign != scope.temporal.campaignUid ||
            HistoryGenerationStore(db, campaign).current().value != scope.temporal.historyGenerationUid)
            return backgroundBlocked("P64:STALE_HISTORY_GENERATION")
        if (!Phase64BackgroundSchema.isReady(db) || !Phase38AccessAuthoritySchema.isReady(db))
            return backgroundBlocked("P64:INSTITUTION_OWNER_SCHEMA_UNAVAILABLE")
        val background = Phase64BackgroundStore(db, campaign)
        if (background.policy() != scope.ruleFingerprint) return backgroundBlocked("P64:INSTITUTION_RULE_BINDING")
        val definitionUid = backgroundRequired(parameters, "_p64_definition_uid")
        val definitionVersion = Math.toIntExact(backgroundPositive(parameters, "_p64_rule_version"))
        val definition = background.definition(definitionUid, definitionVersion)
            ?: return backgroundBlocked("P64:INSTITUTION_RULE_UNAVAILABLE")
        val processUid = backgroundRequired(parameters, "_p64_process_uid")
        val process = staged.filterIsInstance<BackgroundProcessChange>().lastOrNull { it.process.uid == processUid }?.process
            ?: background.process(processUid) ?: return backgroundBlocked("P64:INSTITUTION_PROCESS_UNAVAILABLE")
        val locked = setOf("transmission_policy_uid", "transmission_policy_version", "loss_basis_points", "distortion_basis_points",
            "distorted_text", "confidence_basis_points", "source_reliability_basis_points")
        val acceptedParameters = backgroundParameters(definition, process).filterKeys { it !in locked } +
            definition.parameters.filterKeys { it in locked }
        if (parameters.filterKeys { !it.startsWith("_p64_") } != acceptedParameters ||
            process.status !in setOf(BackgroundProcessStatus.ACTIVE, BackgroundProcessStatus.BLOCKED) ||
            definition.domain != (if (operation == Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE) "INFORMATION" else "ORGANIZATION") ||
            process.actor != actor || process.definitionUid != definition.uid || process.definitionVersion != definition.version ||
            expectedOwnerOperation(definition.operation) != operation ||
            parameters["_p64_rule_fingerprint"] != scope.ruleFingerprint ||
            parameters["_p64_logical_event_uid"] != Phase64OrganizationsInformationAdapter.logicalEventUid(definition, process, scope))
            return backgroundBlocked("P64:INSTITUTION_PROCESS_BINDING")
        val order = AccessAuthorityStore(db, campaign).currentCanonicalOrder()
        if (order != scope.temporal.baseCommitOrder) return backgroundBlocked("P64:STALE_HISTORY_GENERATION")
        val authority = AccessAuthorityStore(db, campaign)
        val actorAccess = Phase64OrganizationsInformationOwners.effectiveOverlay(
            authority.effective(VisibilityPrincipalRef(actor.kindUid, actor.uid), order), actor, order, staged)
        val policy = when (operation) {
            Phase64OrganizationsInformationAdapter.OWNER_ASSIGN, Phase64OrganizationsInformationAdapter.OWNER_REVOKE ->
                backgroundRequired(parameters, "assignment_policy_uid")
            Phase64OrganizationsInformationAdapter.OWNER_DECISION -> backgroundRequired(parameters, "decision_policy_uid")
            Phase64OrganizationsInformationAdapter.OWNER_ALLOCATION -> backgroundRequired(parameters, "allocation_policy_uid")
            Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE -> backgroundRequired(parameters, "espionage_policy_uid")
            else -> parameters["agenda_policy_uid"] ?: definition.uid
        }
        val organizationUid = parameters["organization_uid"]
        val carrier = if (operation == Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE)
            DomainRef(backgroundRequired(parameters, "carrier_kind"), backgroundRequired(parameters, "carrier_uid"))
            else organizationUid?.let { DomainRef("ORGANIZATION", it) }
        val issuingAuthority = ownsInstitutionalAuthority(actor, actorAccess, policy, carrier,
            organizationUid, definition.parameters["issuer_role_uid"])
        if (!issuingAuthority) return backgroundBlocked("P64:INSTITUTION_ISSUING_AUTHORITY_DENIED")
        return when (operation) {
            Phase64OrganizationsInformationAdapter.OWNER_AGENDA ->
                Phase64OrganizationsInformationOwners.prepareAgenda(parameters, scope)
            Phase64OrganizationsInformationAdapter.OWNER_ASSIGN, Phase64OrganizationsInformationAdapter.OWNER_REVOKE -> {
                val assignee = DomainRef(backgroundRequired(parameters, "assignee_kind"), backgroundRequired(parameters, "assignee_uid"))
                val ruleUid = parameters["activity_rule_uid"] ?: backgroundRequired(parameters, "duty_uid")
                val ruleVersion = (parameters["activity_rule_version"] ?: backgroundRequired(parameters, "duty_version")).toInt()
                val contract = contract(db, campaign, ruleUid, ruleVersion)
                val rule = contract?.duty ?: return backgroundBlocked("P64:DUTY_RULE_UNAVAILABLE")
                val body = MechanicalActorStateStore(db, campaign).actor(assignee)
                val marker = Phase64OrganizationsInformationOwners.assignmentUid(campaign, assignee, rule)
                val recorded = db.rawQuery("SELECT record_uid FROM ${Phase38AccessAuthoritySchema.RECORDS} " +
                    "WHERE campaign_uid=? AND principal_kind_uid=? AND principal_uid=? AND record_uid=? LIMIT 1",
                    arrayOf(campaign, assignee.kindUid, assignee.uid, marker)).use { c ->
                    if (c.moveToFirst()) setOf(c.getString(0)) else emptySet() }
                val deadlines = deadlineView ?: Phase60TemporalStateStore(db, campaign).read().deadlines
                val snapshot = Phase64DutyOwnerSnapshot(scope.temporal, order, issuingAuthority,
                    body?.kind in setOf(MechanicalActorKind.NPC, MechanicalActorKind.FORMER_PLAYER), rule,
                    authority.effective(VisibilityPrincipalRef(assignee.kindUid, assignee.uid), order), recorded, deadlines,
                    otherDutyAssignees(db, campaign, assignee, rule, order, staged))
                Phase64OrganizationsInformationOwners.prepareDuty(operation, parameters, scope, snapshot, staged)
            }
            Phase64OrganizationsInformationAdapter.OWNER_ALLOCATION -> allocation(db, campaign, parameters, scope, staged, issuingAuthority)
            Phase64OrganizationsInformationAdapter.OWNER_DECISION -> {
                val captured = captureDecision?.invoke(actor, parameters, scope, staged)
                    ?: return backgroundBlocked("P64:INSTITUTIONAL_DECISION_CONTEXT_UNAVAILABLE")
                val action = prepareSelectedAction ?: return backgroundBlocked("P64:INSTITUTIONAL_ACTION_OWNER_UNAVAILABLE")
                Phase64InstitutionDecisionOwner.prepare(parameters, scope, actor, captured,
                    prepareSelectedAction = action)
            }
            else -> {
                val captured = captureEspionage?.invoke(actor, parameters, scope, staged)
                    ?: possessedCarrier(db, campaign, actor, parameters, scope, actorAccess, staged)
                    ?: return backgroundBlocked("P64:ESPIONAGE_CARRIER_ACCESS_UNAVAILABLE")
                Phase64OrganizationsInformationOwners.prepareEspionage(parameters, scope, actor, captured.access,
                    captured.carrierClaim, captured.recipientHolder, captured.sourceAcquisition)
            }
        }
    }

    private fun ownsInstitutionalAuthority(actor: DomainRef, records: List<AccessAuthorityRecord>, policy: String,
        carrier: DomainRef?, organizationUid: String?, requiredRole: String?): Boolean {
        if (organizationUid != null && actor != DomainRef("ORGANIZATION", organizationUid) && records.none {
                it.operation == AccessOperation.UPSERT_BINDING && it.kindUid == AccessBindingKind.ORGANIZATION.name && it.valueUid == organizationUid }) return false
        if (requiredRole != null && records.none { it.operation == AccessOperation.UPSERT_BINDING &&
                it.kindUid == AccessBindingKind.ROLE.name && it.valueUid == requiredRole }) return false
        // These are the exact same admitted grant families/carrier semantics used by Phase38.
        // The overlay consists solely of existing typed Phase38 changes in this speculative turn.
        return records.any { it.operation in setOf(AccessOperation.GRANT, AccessOperation.SET_CARRIER_ACCESS) &&
            it.kindUid in AccessGrantKind.entries.map { kind -> kind.name } && it.valueUid == policy &&
            (carrier == null || it.subjectKindUid == carrier.kindUid && it.subjectUid == carrier.uid) }
    }

    private fun contract(db: SQLiteDatabase, campaign: String, uid: String, version: Int): NpcActivityContract? {
        if (!Phase62ActivitySchema.isReady(db)) return null
        return db.rawQuery("SELECT contract_json,contract_fingerprint FROM ${Phase62ActivitySchema.TABLE} " +
            "WHERE campaign_uid=? AND rule_uid=? AND rule_version=? AND active=1 LIMIT 1",
            arrayOf(campaign, uid, version.toString())).use { c ->
            if (!c.moveToFirst()) null else NpcActivityContractCodec.decode(c.getString(0)).also {
                require(it.ruleUid == uid && it.version == version && it.fingerprint == c.getString(1)) { "P64:NPC_CONTRACT_BINDING" }
            }
        }
    }

    private fun otherDutyAssignees(db: SQLiteDatabase, campaign: String, assignee: DomainRef, duty: NpcDutyRule,
        order: Long, staged: List<PlayerDomainChangePayload>): Boolean {
        val other = db.rawQuery("SELECT DISTINCT principal_kind_uid,principal_uid FROM ${Phase38AccessAuthoritySchema.RECORDS} " +
            "WHERE campaign_uid=? AND subject_kind_uid='NPC_DUTY' AND subject_uid=? LIMIT 33",
            arrayOf(campaign, duty.dutyUid)).use { c -> buildList { while (c.moveToNext()) add(DomainRef(c.getString(0), c.getString(1))) } }
        if (other.size > 32) return true // No proof that deleting a shared deadline is legal.
        val stagedAssignees = staged.filterIsInstance<AccessAuthorityChange>().filter {
            it.subjectKindUid == "NPC_DUTY" && it.subjectUid == duty.dutyUid }.map { DomainRef(it.principalKindUid, it.principalUid) }
        val store = AccessAuthorityStore(db, campaign)
        return (other + stagedAssignees).distinct().filter { it != assignee }.any { principal ->
            Phase64OrganizationsInformationOwners.effectiveOverlay(store.effective(VisibilityPrincipalRef(principal.kindUid, principal.uid), order),
                principal, order, staged).any { it.operation == AccessOperation.GRANT && it.kindUid == AccessGrantKind.WORLD_RULE.name &&
                it.valueUid == duty.assignmentPolicyUid && it.subjectKindUid == "NPC_DUTY" && it.subjectUid == duty.dutyUid }
        }
    }

    private fun allocation(db: SQLiteDatabase, campaign: String, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>, authorized: Boolean): WorldConsequencePlan {
        val organization = DomainRef("ORGANIZATION", backgroundRequired(parameters, "organization_uid"))
        val recipient = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        val resourceUid = backgroundRequired(parameters, "resource_uid")
        val snapshot = when (backgroundRequired(parameters, "resource_kind")) {
            "FINANCIAL_ACCOUNT" -> {
                val destinationUid = backgroundRequired(parameters, "recipient_account_uid")
                val source = account(db, campaign, resourceUid) ?: return backgroundBlocked("P64:ALLOCATION_ACCOUNT_UNAVAILABLE")
                val destination = account(db, campaign, destinationUid) ?: return backgroundBlocked("P64:ALLOCATION_ACCOUNT_UNAVAILABLE")
                if (source.first != organization || !sameOwner(destination.first, recipient) || source.second != destination.second)
                    return backgroundBlocked("P64:ALLOCATION_ACCOUNT_SCOPE")
                val transactionType = parameters["transaction_type_uid"] ?: "RPGOS-FIN-TYPE:TRANSFER"
                val typeRegistered = db.rawQuery("SELECT 1 FROM financial_transaction_type_definitions " +
                    "WHERE transaction_type_uid=? AND flow_kind='INTERNAL' AND type_status='ACTIVE' LIMIT 1", arrayOf(transactionType)).use { it.moveToFirst() }
                if (!typeRegistered) return backgroundBlocked("P64:ALLOCATION_TRANSACTION_RULE_UNAVAILABLE")
                var available = FinancialStore(db, campaign).balance(resourceUid)
                staged.filterIsInstance<FinancialChange>().forEach {
                    if (it.fromAccountUid == resourceUid) available = Math.subtractExact(available, it.amountMinor)
                    if (it.toAccountUid == resourceUid) available = Math.addExact(available, it.amountMinor)
                }
                Phase64AllocationOwnerSnapshot(scope.temporal, authorized, organization, recipient, available,
                    sourceAccountUid = resourceUid, recipientAccountUid = destinationUid, currencyUid = source.second)
            }
            "ITEM_INSTANCE" -> {
                val held = holds(db, campaign, organization, resourceUid, staged)
                Phase64AllocationOwnerSnapshot(scope.temporal, authorized, organization, recipient, if (held) 1 else 0,
                    itemInstanceUid = if (held) resourceUid else null)
            }
            else -> return backgroundBlocked("P64:ALLOCATION_RESOURCE_OWNER_UNAVAILABLE")
        }
        return Phase64OrganizationsInformationOwners.prepareAllocation(parameters, scope, snapshot)
    }

    private fun account(db: SQLiteDatabase, campaign: String, uid: String): Pair<DomainRef, String>? =
        db.rawQuery("SELECT holder_kind_uid,holder_uid,currency_uid FROM financial_accounts " +
            "WHERE campaign_id=? AND account_uid=? AND closed_order IS NULL LIMIT 1", arrayOf(campaign, uid)).use { c ->
            if (c.moveToFirst()) DomainRef(c.getString(0), c.getString(1)) to c.getString(2) else null }

    private fun sameOwner(stored: DomainRef, actor: DomainRef) = stored == actor || stored.uid == actor.uid &&
        stored.kindUid == "CHARACTER" && actor.kindUid in setOf("NPC", "ACTOR", "PLAYER")

    private fun holds(db: SQLiteDatabase, campaign: String, owner: DomainRef, itemUid: String,
        staged: List<PlayerDomainChangePayload>): Boolean {
        var held = db.rawQuery("SELECT 1 FROM player_inventory_unique u JOIN item_instances i " +
            "ON i.campaign_id=u.campaign_id AND i.item_instance_uid=u.item_instance_uid " +
            "WHERE u.campaign_id=? AND u.character_uid=? AND u.item_instance_uid=? LIMIT 1",
            arrayOf(campaign, owner.uid, itemUid)).use { it.moveToFirst() }
        staged.filterIsInstance<InventoryChange>().filter { it.subject == owner && it.itemInstanceUid == itemUid }.forEach {
            require(it.quantityDelta.units in setOf(-1L, 1L)); held = it.quantityDelta.units == 1L
        }
        return held
    }

    private fun possessedCarrier(db: SQLiteDatabase, campaign: String, actor: DomainRef, parameters: Map<String, String>,
        scope: BackgroundProcessEvaluationScope, accessRecords: List<AccessAuthorityRecord>, staged: List<PlayerDomainChangePayload>
    ): Phase64InstitutionEspionageCapture? {
        val uid = parameters["carrier_rule_uid"] ?: return null
        val version = parameters["carrier_rule_version"]?.toIntOrNull() ?: return null
        val reading = contract(db, campaign, uid, version)?.reading ?: return null
        val carrier = DomainRef(backgroundRequired(parameters, "carrier_kind"), backgroundRequired(parameters, "carrier_uid"))
        if (reading.carrier != carrier || reading.accessPolicyUid != parameters["espionage_policy_uid"] ||
            !holds(db, campaign, actor, carrier.uid, staged)) return null
        val authorized = ownsInstitutionalAuthority(actor, accessRecords, reading.accessPolicyUid, carrier, null, null)
        if (!authorized) return null
        val principal = VisibilityPrincipalRef(actor.kindUid, actor.uid)
        val trusted = UniversalAccessAuthority(AccessAuthorityStore(db, campaign)).trustedContext(
            AudienceContext(campaign, AudienceKinds.WORLD_ACTOR, principal), scope.temporal.baseCommitOrder) ?: return null
        val exactCarrier = InformationCarrierRef(campaign, carrier.kindUid, carrier.uid)
        val path = Phase38AccessRuntimeAuthority.issuePath(trusted, exactCarrier, "P64:POSSESSED_REGISTERED_CARRIER",
            reading.fingerprint, false, CarrierAccessStage.entries.toSet())
        val access = EffectiveAccessDecision.granted("P64:AUTHORIZED_REGISTERED_CARRIER", path, CarrierAccessStage.entries.toSet())
        val recipient = DomainRef(backgroundRequired(parameters, "recipient_kind"), backgroundRequired(parameters, "recipient_uid"))
        val holderKind = if (recipient.kindUid in setOf("NPC", "ACTOR", "PLAYER", "CHARACTER")) KnowledgeHolderKinds.CHARACTER else recipient.kindUid
        return Phase64InstitutionEspionageCapture(access, reading.claim, KnowledgeHolderRef(holderKind, recipient.uid, campaign))
    }

    private fun expectedOwnerOperation(operation: String) = when (operation) {
        "AGENDA" -> Phase64OrganizationsInformationAdapter.OWNER_AGENDA
        "ASSIGN" -> Phase64OrganizationsInformationAdapter.OWNER_ASSIGN
        "REVOKE" -> Phase64OrganizationsInformationAdapter.OWNER_REVOKE
        "DECISION" -> Phase64OrganizationsInformationAdapter.OWNER_DECISION
        "ALLOCATE" -> Phase64OrganizationsInformationAdapter.OWNER_ALLOCATION
        "ESPIONAGE" -> Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE
        else -> null
    }
}
