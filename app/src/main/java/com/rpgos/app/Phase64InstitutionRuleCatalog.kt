package com.rpgos.app

/** Registration is not execution or a grant. The production importer supplies versioned
 * identities and captures from the existing Phase37/38/50/62 owners, never model parameters. */
internal data class Phase64InstitutionRuleRegistration(
    val campaignUid: String,
    val definitionUid: String,
    val version: Int,
    val actionUid: String,
    val durationMillis: Long,
    val publicAction: Boolean = false,
    val npcAction: Boolean = false,
    val npcActivationPolicyUid: String? = null
)

internal sealed interface Phase64InstitutionRulePreparation {
    data class Ready(val definition: BackgroundProcessDefinition) : Phase64InstitutionRulePreparation
    data class Unavailable(val reasonUid: String) : Phase64InstitutionRulePreparation
}

internal data class Phase64InstitutionAgendaRecipeSnapshot(
    val organization: DomainRef?, val agendaUid: String?, val agendaVersion: Int?,
    val objective: String?, val policyUid: String?
)

internal data class Phase64InstitutionDutyRecipeSnapshot(
    val organization: DomainRef?, val activity: NpcActivityContract?,
    val issuerRoleUid: String? = null, val revokeReasonUid: String? = null
)

internal data class Phase64InstitutionDecisionRecipeSnapshot(
    val agenda: Phase64InstitutionAgendaRecipeSnapshot?, val context: NpcDecisionContextEnvelope?,
    val optionUid: String?, val policyUid: String?
)

internal data class Phase64InstitutionAllocationRecipeSnapshot(
    val owner: Phase64AllocationOwnerSnapshot?, val resource: DomainRef?,
    val quantity: Long?, val policyUid: String?
)

/** Both paths are exact Phase38 captures for this registered channel and these named holders.
 * They establish that the imported recipe has a real delivery mechanism, not lasting authority:
 * normal Phase64 evaluation still checks the current sender/recipient grants at execution. */
internal data class Phase64InstitutionDeliveryRecipeSnapshot(
    val currentScope: TemporalScope,
    val sender: DomainRef?, val recipient: DomainRef?, val channel: DomainRef?, val disclosure: DomainRef?,
    val senderAccess: EffectiveAccessDecision?, val recipientAccess: EffectiveAccessDecision?,
    val delayMillis: Long?, val messageUid: String? = null, val messageText: String? = null
)

internal data class Phase64InstitutionReportRecipeSnapshot(
    val delivery: Phase64InstitutionDeliveryRecipeSnapshot?,
    val sourceAcquisition: KnowledgeAcquisition? = null,
    val ownContext: NpcDecisionContextEnvelope? = null,
    val ownRecordUid: String? = null
)

internal data class Phase64InstitutionDiplomacyRecipeSnapshot(
    val delivery: Phase64InstitutionDeliveryRecipeSnapshot?, val organization: DomainRef?
)

internal data class Phase64InstitutionEspionageRecipeSnapshot(
    val delivery: Phase64InstitutionDeliveryRecipeSnapshot?, val activity: NpcActivityContract?,
    val carrier: Phase64InstitutionEspionageCapture?
)

/** Complete immutable recipes for the ordinary production registration/activation path.
 * There are no default organizations, balances, sources, duties or private carriers. Missing
 * captures are typed unavailability, not a bare definition presented as an executable feature.
 * No factory emits a process instance, deadline, acquisition, assignment or resource change. */
internal object Phase64InstitutionRuleCatalog {
    fun agenda(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionAgendaRecipeSnapshot?) =
        prepare(registration, "ORGANIZATION", "AGENDA") {
            val agenda = required(snapshot, "AGENDA")
            agendaParameters(agenda) + ("agenda_policy_uid" to uid(agenda.policyUid, "AGENDA_POLICY"))
        }

    fun assign(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionDutyRecipeSnapshot?) =
        duty(registration, snapshot, false)

    fun revoke(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionDutyRecipeSnapshot?) =
        duty(registration, snapshot, true)

    private fun duty(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionDutyRecipeSnapshot?, revoke: Boolean) =
        prepare(registration, "ORGANIZATION", if (revoke) "REVOKE" else "ASSIGN") {
            val captured = required(snapshot, "DUTY")
            val organization = organization(captured.organization)
            val activity = required(captured.activity, "DUTY_ACTIVITY")
            val duty = required(activity.duty, "REGISTERED_DUTY")
            check(duty.organizationUid == organization.uid, "DUTY_ORGANIZATION_SCOPE")
            check(duty.due.milliseconds > 0, "DUTY_DEADLINE_EXPIRED")
            mapOf("organization_uid" to organization.uid, "duty_uid" to duty.dutyUid, "duty_version" to duty.version.toString(),
                "activity_rule_uid" to activity.ruleUid, "activity_rule_version" to activity.version.toString(),
                "assignee_kind" to "@TARGET_KIND", "assignee_uid" to "@TARGET_UID", "role_uid" to duty.roleUid,
                "assignment_policy_uid" to duty.assignmentPolicyUid, "deadline_uid" to duty.deadlineUid,
                "deadline_ms" to duty.due.milliseconds.toString()) +
                (captured.issuerRoleUid?.let { mapOf("issuer_role_uid" to uid(it, "ISSUER_ROLE")) } ?: emptyMap()) +
                (if (revoke) mapOf("reason_uid" to uid(captured.revokeReasonUid, "REVOKE_REASON")) else emptyMap())
        }

    fun decision(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionDecisionRecipeSnapshot?) =
        prepare(registration, "ORGANIZATION", "DECISION") {
            val captured = required(snapshot, "DECISION")
            check(registration.npcAction && !registration.publicAction, "NPC_DECISION_ONLY")
            val context = required(captured.context, "PROTECTED_DECISION_CONTEXT")
            check(context.scope.temporal.campaignUid == registration.campaignUid &&
                context.contextFingerprint == context.computeFingerprint(), "DECISION_CONTEXT_SCOPE")
            val optionUid = uid(captured.optionUid, "LEGAL_OPTION")
            val option = required(context.options.singleOrNull { it.uid == optionUid }, "LEGAL_OPTION")
            check(option.goalUid != null && context.brain.goals.any {
                it.uid == option.goalUid && it.lifecycle == NpcGoalLifecycle.ACTIVE
            }, "DECISION_ACTIVE_GOAL")
            val agenda = required(captured.agenda, "AGENDA")
            mapOf("organization_uid" to organization(agenda.organization).uid, "agenda_uid" to uid(agenda.agendaUid, "AGENDA"),
                "agenda_version" to positive(agenda.agendaVersion?.toLong(), "AGENDA_VERSION").toString(),
                "option_uid" to option.uid, "decision_policy_uid" to uid(captured.policyUid, "DECISION_POLICY"),
                "decision_actor_kind" to context.scope.actor.kindUid, "decision_actor_uid" to context.scope.actor.uid)
        }

    fun allocate(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionAllocationRecipeSnapshot?) =
        prepare(registration, "ORGANIZATION", "ALLOCATE") {
            val captured = required(snapshot, "ALLOCATION")
            val owner = required(captured.owner, "ALLOCATION_OWNER")
            check(owner.currentScope.campaignUid == registration.campaignUid, "ALLOCATION_SCOPE")
            check(owner.authorized, "ALLOCATION_AUTHORITY_DENIED")
            val resource = required(captured.resource, "ALLOCATION_RESOURCE")
            val units = positive(captured.quantity, "ALLOCATION_QUANTITY")
            check(owner.availableUnits != null && owner.availableUnits >= units, "ALLOCATION_RESOURCE_INSUFFICIENT")
            check(owner.sourceOwner != owner.recipientOwner, "ALLOCATION_OWNER_SCOPE")
            when (resource.kindUid) {
                "ITEM_INSTANCE" -> check(units == 1L && owner.itemInstanceUid == resource.uid, "ALLOCATION_ITEM_SCOPE")
                "FINANCIAL_ACCOUNT" -> {
                    check(owner.sourceAccountUid == resource.uid && owner.recipientAccountUid != null &&
                        owner.recipientAccountUid != resource.uid && owner.currencyUid != null, "ALLOCATION_ACCOUNT_SCOPE")
                    uid(owner.recipientAccountUid, "RECIPIENT_ACCOUNT"); uid(owner.currencyUid, "CURRENCY")
                }
                else -> check(false, "ALLOCATION_RESOURCE_OWNER_UNAVAILABLE")
            }
            mapOf("organization_uid" to organization(owner.sourceOwner).uid, "recipient_kind" to owner.recipientOwner.kindUid,
                "recipient_uid" to owner.recipientOwner.uid, "resource_kind" to resource.kindUid, "resource_uid" to resource.uid,
                "quantity" to units.toString(), "allocation_policy_uid" to uid(captured.policyUid, "ALLOCATION_POLICY")) +
                if (resource.kindUid == "FINANCIAL_ACCOUNT") mapOf("recipient_account_uid" to uid(owner.recipientAccountUid, "RECIPIENT_ACCOUNT"))
                else emptyMap()
        }

    fun report(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionReportRecipeSnapshot?) =
        prepare(registration, "INFORMATION", "REPORT") {
            val captured = required(snapshot, "REPORT")
            val delivery = required(captured.delivery, "DELIVERY")
            val parameters = deliveryParameters(registration, delivery, true)
            val context = captured.ownContext
            val source = if (context != null) {
                check(registration.npcAction && !registration.publicAction && context.scope.actor.kindUid != "PLAYER" &&
                    context.scope.actor == delivery.sender && context.scope.temporal == delivery.currentScope &&
                    context.contextFingerprint == context.computeFingerprint(), "REPORT_OWN_CONTEXT_SCOPE")
                check(captured.sourceAcquisition == null, "REPORT_SOURCE_AMBIGUOUS")
                val recordUid = required(captured.ownRecordUid, "REPORT_OWN_RECORD")
                required(context.records.singleOrNull { it.uid == recordUid }, "REPORT_OWN_RECORD")
                Phase64ProcessActivation.OWN_ACQUISITION
            } else {
                check(captured.ownRecordUid == null, "REPORT_OWN_CONTEXT_MISSING")
                val acquisition = required(captured.sourceAcquisition, "REPORT_SOURCE_ACQUISITION")
                check(acquisition.campaignUid == registration.campaignUid &&
                    acquisition.createdOrder <= delivery.currentScope.baseCommitOrder, "REPORT_SOURCE_SCOPE")
                uid(acquisition.acquisitionUid, "REPORT_SOURCE_ACQUISITION")
            }
            parameters + ("source_acquisition_uid" to source)
        }

    fun diplomacy(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionDiplomacyRecipeSnapshot?) =
        prepare(registration, "INFORMATION", "DIPLOMACY") {
            val captured = required(snapshot, "DIPLOMACY")
            deliveryParameters(registration, required(captured.delivery, "DELIVERY"), true) +
                ("organization_uid" to organization(captured.organization).uid)
        }

    fun espionage(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionEspionageRecipeSnapshot?) =
        prepare(registration, "INFORMATION", "ESPIONAGE") {
            val captured = required(snapshot, "ESPIONAGE")
            val delivery = required(captured.delivery, "DELIVERY")
            val parameters = deliveryParameters(registration, delivery, false)
            val activity = required(captured.activity, "CARRIER_ACTIVITY")
            val reading = required(activity.reading, "REGISTERED_CARRIER")
            val carrier = required(captured.carrier, "CARRIER_CAPTURE")
            val access = carrier.access
            val path = required(access.path, "CARRIER_ACCESS_PATH")
            val sender = required(delivery.sender, "SENDER")
            val recipient = required(delivery.recipient, "RECIPIENT")
            check(access.accessible && path.campaignUid == registration.campaignUid &&
                path.principal == VisibilityPrincipalRef(sender.kindUid, sender.uid) &&
                path.carrier == InformationCarrierRef(registration.campaignUid, reading.carrier.kindUid, reading.carrier.uid) &&
                CarrierAccessStage.COMPREHENDED in access.resolvedStages && CarrierAccessStage.COMPREHENDED in path.resolvedStages &&
                carrier.carrierClaim == reading.claim && carrier.recipientHolder == holder(recipient, registration.campaignUid),
                "CARRIER_EXACT_ACCESS_SCOPE")
            parameters + mapOf("carrier_kind" to reading.carrier.kindUid, "carrier_uid" to reading.carrier.uid,
                "carrier_rule_uid" to activity.ruleUid, "carrier_rule_version" to activity.version.toString(),
                "espionage_policy_uid" to reading.accessPolicyUid)
        }

    private fun deliveryParameters(registration: Phase64InstitutionRuleRegistration, snapshot: Phase64InstitutionDeliveryRecipeSnapshot,
        message: Boolean): Map<String, String> {
        check(snapshot.currentScope.campaignUid == registration.campaignUid, "DELIVERY_SCOPE")
        val sender = required(snapshot.sender, "SENDER")
        val recipient = required(snapshot.recipient, "RECIPIENT")
        check(if (sender.kindUid == "PLAYER") registration.publicAction else registration.npcAction, "DELIVERY_ACTIVATION_PRINCIPAL")
        holder(recipient, registration.campaignUid)
        val channel = required(snapshot.channel, "CHANNEL")
        val disclosure = required(snapshot.disclosure, "DISCLOSURE_POLICY")
        check(channel.kindUid == "INFORMATION_CHANNEL" && disclosure.kindUid == "DISCLOSURE_POLICY", "DELIVERY_REFERENCE_KIND")
        check(channelAccess(snapshot.senderAccess, sender, channel, registration.campaignUid) &&
            channelAccess(snapshot.recipientAccess, recipient, channel, registration.campaignUid), "DELIVERY_OWNER_ACCESS_DENIED")
        val delay = required(snapshot.delayMillis, "DELIVERY_DELAY")
        check(delay >= 0 && delay <= registration.durationMillis, "DELIVERY_DELAY_NOT_SCHEDULED")
        return mapOf("sender_kind" to sender.kindUid, "sender_uid" to sender.uid,
            "recipient_kind" to recipient.kindUid, "recipient_uid" to recipient.uid, "channel_uid" to channel.uid,
            "disclosure_policy_uid" to disclosure.uid, "delay_ms" to delay.toString()) +
            if (message) mapOf("message_uid" to uid(snapshot.messageUid, "MESSAGE"),
                "message_text" to text(snapshot.messageText, "MESSAGE_TEXT", 2048)) else emptyMap()
    }

    private fun agendaParameters(snapshot: Phase64InstitutionAgendaRecipeSnapshot) = mapOf(
        "organization_uid" to organization(snapshot.organization).uid, "agenda_uid" to uid(snapshot.agendaUid, "AGENDA"),
        "agenda_version" to positive(snapshot.agendaVersion?.toLong(), "AGENDA_VERSION").toString(),
        "objective" to text(snapshot.objective, "AGENDA_OBJECTIVE", 1024))

    private fun channelAccess(access: EffectiveAccessDecision?, principal: DomainRef, channel: DomainRef, campaign: String): Boolean {
        val path = access?.path ?: return false
        return access.accessible && path.campaignUid == campaign && path.principal == VisibilityPrincipalRef(principal.kindUid, principal.uid) &&
            path.carrier == InformationCarrierRef(campaign, channel.kindUid, channel.uid) &&
            CarrierAccessStage.COMPREHENDED in access.resolvedStages && CarrierAccessStage.COMPREHENDED in path.resolvedStages
    }

    private fun organization(value: DomainRef?): DomainRef = required(value, "ORGANIZATION").also {
        check(it.kindUid == "ORGANIZATION", "ORGANIZATION_SCOPE")
    }

    private fun holder(ref: DomainRef, campaign: String): KnowledgeHolderRef {
        val kind = if (ref.kindUid in setOf("NPC", "ACTOR", "CHARACTER", "PLAYER")) KnowledgeHolderKinds.CHARACTER else ref.kindUid
        check(kind in setOf(KnowledgeHolderKinds.CHARACTER, KnowledgeHolderKinds.ORGANIZATION, KnowledgeHolderKinds.MILITARY_COMMAND,
            KnowledgeHolderKinds.CITY_ADMINISTRATION, KnowledgeHolderKinds.STATE, KnowledgeHolderKinds.INTELLIGENCE_SERVICE,
            KnowledgeHolderKinds.RESEARCH_TEAM, KnowledgeHolderKinds.LABORATORY, KnowledgeHolderKinds.GUILD,
            KnowledgeHolderKinds.COMPANY, KnowledgeHolderKinds.WORLD_SPECIFIC), "RECIPIENT_HOLDER_KIND")
        return KnowledgeHolderRef(kind, ref.uid, campaign)
    }

    private fun prepare(registration: Phase64InstitutionRuleRegistration, domain: String, operation: String,
        recipe: () -> Map<String, String>): Phase64InstitutionRulePreparation = try {
        uid(registration.campaignUid, "CAMPAIGN"); uid(registration.definitionUid, "RULE"); uid(registration.actionUid, "ACTION")
        check(registration.version > 0 && registration.durationMillis > 0, "RULE_VERSION_OR_DURATION")
        check(registration.publicAction || registration.npcAction, "ACTIVATION_REQUIRED")
        val metadata = mapOf(Phase64ProcessActivation.ACTION_KEY to registration.actionUid,
            Phase64ProcessActivation.PUBLIC_KEY to registration.publicAction.toString(), "activation_npc" to registration.npcAction.toString()) +
            if (registration.npcAction) mapOf("activation_policy_uid" to uid(registration.npcActivationPolicyUid, "NPC_ACTIVATION_POLICY")) else emptyMap()
        val parameters = recipe()
        check(parameters.values.all { it.isNotBlank() }, "PARAMETER_MISSING")
        check(parameters.values.none { it.startsWith("@") && it !in setOf("@TARGET_KIND", "@TARGET_UID", Phase64ProcessActivation.OWN_ACQUISITION) },
            "UNSUPPORTED_BINDING")
        Phase64InstitutionRulePreparation.Ready(BackgroundProcessDefinition(registration.definitionUid, registration.version,
            domain, operation, registration.durationMillis, parameters = metadata + parameters))
    } catch (failure: IllegalArgumentException) {
        Phase64InstitutionRulePreparation.Unavailable(failure.message?.takeIf { it.startsWith("P64:CATALOG_") } ?: "P64:CATALOG_INVALID_RECIPE")
    }

    private fun <T> required(value: T?, field: String): T = value ?: throw IllegalArgumentException("P64:CATALOG_${field}_MISSING")
    private fun uid(value: String?, field: String) = text(value, field, 160)
    private fun text(value: String?, field: String, maximum: Int): String = required(value, field).also {
        check(it.isNotBlank() && it.length <= maximum && !it.startsWith("@"), "${field}_INVALID")
    }
    private fun positive(value: Long?, field: String): Long = required(value, field).also { check(it > 0, "${field}_INVALID") }
    private fun check(condition: Boolean, reason: String) { require(condition) { "P64:CATALOG_$reason" } }
}
