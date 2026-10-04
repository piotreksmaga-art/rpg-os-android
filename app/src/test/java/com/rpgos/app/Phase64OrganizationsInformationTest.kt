package com.rpgos.app

import org.junit.Assert.*
import org.junit.Test

class Phase64OrganizationsInformationTest {
    private val adapter = Phase64OrganizationsInformationAdapter()
    private val actor = DomainRef("NPC", "N1")
    private val recipient = DomainRef("NPC", "N2")
    private val scope = BackgroundProcessEvaluationScope(TemporalScope("C1", "G1", 7, "digest"), "seed", "rules")
    private val delivery = mapOf("recipient_kind" to recipient.kindUid, "recipient_uid" to recipient.uid,
        "message_uid" to "M1", "message_text" to "Most jest bezpieczny.", "channel_uid" to "COURIER",
        "disclosure_policy_uid" to "NAMED_RECIPIENT", "delay_ms" to "1000")
    private val assignment = mapOf("organization_uid" to "O1", "duty_uid" to "D1", "duty_version" to "2",
        "assignee_kind" to "NPC", "assignee_uid" to "N2", "role_uid" to "GUARD",
        "assignment_policy_uid" to "ASSIGN_GUARD", "deadline_uid" to "DEADLINE1", "deadline_ms" to "2000")

    private data class OwnedCall(val operation: String, val actor: DomainRef, val parameters: Map<String, String>,
        val scope: BackgroundProcessEvaluationScope, val staged: List<PlayerDomainChangePayload>)
    private data class AuthorizationCall(val actor: DomainRef, val purpose: String, val references: List<DomainRef>)
    private data class CommunicationCall(val actor: DomainRef, val purpose: String, val references: List<DomainRef>,
        val definition: BackgroundProcessDefinition, val process: BackgroundProcessInstance, val at: WorldTimeTick,
        val staged: List<PlayerDomainChangePayload>)

    private inner class CapturedReads : BackgroundWorldReadPort {
        var missing = emptySet<DomainRef>()
        var deniedPurpose: String? = null
        var available: Long? = 4
        var ownedResult = WorldConsequencePlan(changes = listOf(AccessAuthorityChange(AccessOperation.GRANT,
            "ASSIGNMENT1", "NPC", "N2", AccessGrantKind.WORLD_RULE.name, "ASSIGN_GUARD",
            "NPC_DUTY", "D1", 7)))
        val owned = mutableListOf<OwnedCall>()
        val authorization = mutableListOf<AuthorizationCall>()
        val overlays = mutableListOf<List<PlayerDomainChangePayload>>()
        val authorizationOverlays = mutableListOf<List<PlayerDomainChangePayload>>()
        // Test owner only: the production capture has its separate scoped-helper tests below.
        // A null override deliberately means that no channel owner was available.
        var communicationRead: ((CommunicationCall) -> EffectiveAccessDecision?)? = { call ->
            if (!authorize(call.actor, call.purpose, call.references, call.staged)) null else {
                val channel = backgroundParameters(call.definition, call.process).getValue("channel_uid")
                val trusted = TrustedPrincipalContext(scope.temporal.campaignUid,
                    VisibilityPrincipalRef(call.actor.kindUid, call.actor.uid), AudienceKinds.WORLD_ACTOR)
                val path = Phase38AccessRuntimeAuthority.issuePath(trusted,
                    InformationCarrierRef(scope.temporal.campaignUid, "INFORMATION_CHANNEL", channel),
                    "TEST_CAPTURED_CHANNEL_OWNER", "TEST_CHANNEL_EVIDENCE:${call.actor.kindUid}:${call.actor.uid}:$channel",
                    false, CarrierAccessStage.entries.toSet())
                EffectiveAccessDecision.granted("TEST_CAPTURED_CHANNEL", path, path.resolvedStages)
            }
        }
        val communicationCalls = mutableListOf<CommunicationCall>()
        override fun exists(ref: DomainRef) = ref !in missing
        override fun available(resource: DomainRef, staged: List<PlayerDomainChangePayload>): Long? {
            overlays += staged
            return available
        }
        override fun route(actor: DomainRef, destination: DomainRef, at: WorldTimeTick): String? = error("No route read")
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>): Boolean {
            authorization += AuthorizationCall(actor, purpose, refs)
            return purpose != deniedPurpose
        }
        override fun authorize(actor: DomainRef, purpose: String, refs: List<DomainRef>,
                               staged: List<PlayerDomainChangePayload>): Boolean {
            authorizationOverlays += staged
            val base = authorize(actor, purpose, refs)
            return base && staged.filterIsInstance<AccessAuthorityChange>().none {
                it.operation == AccessOperation.REVOKE_GRANT && it.principalKindUid == actor.kindUid &&
                    it.principalUid == actor.uid && refs.any { ref -> ref.kindUid == "DISCLOSURE_POLICY" && ref.uid == it.valueUid }
            }
        }
        override fun prepareOwnedEffect(operation: String, actor: DomainRef, parameters: Map<String, String>,
            scope: BackgroundProcessEvaluationScope, staged: List<PlayerDomainChangePayload>): WorldConsequencePlan {
            owned += OwnedCall(operation, actor, parameters, scope, staged)
            return ownedResult
        }
        override fun communicationAccess(actor: DomainRef, purpose: String, refs: List<DomainRef>,
            definition: BackgroundProcessDefinition, process: BackgroundProcessInstance, at: WorldTimeTick,
            staged: List<PlayerDomainChangePayload>): EffectiveAccessDecision? {
            val call = CommunicationCall(actor, purpose, refs, definition, process, at, staged)
            communicationCalls += call
            return communicationRead?.invoke(call)
        }
    }

    private fun definition(domain: String = "INFORMATION", operation: String = "MESSAGE",
                           parameters: Map<String, String> = delivery) =
        BackgroundProcessDefinition("RULE", 1, domain, operation, 1000, parameters = parameters)
    private fun process(definition: BackgroundProcessDefinition, parameters: Map<String, String> = emptyMap()) =
        BackgroundProcessInstance("PROCESS", definition.uid, definition.version, actor, 1,
            WorldTimeTick(0), WorldTimeTick(1000), parameters = parameters)
    private fun evaluate(definition: BackgroundProcessDefinition = definition(), reads: CapturedReads = CapturedReads(),
        instance: BackgroundProcessInstance = process(definition), at: WorldTimeTick = WorldTimeTick(1000),
        currentScope: BackgroundProcessEvaluationScope = scope, staged: List<PlayerDomainChangePayload> = emptyList()) =
        adapter.evaluate(definition, instance, currentScope, at, reads, staged)

    @Test fun deliveredAssertionIsOnlyRecipientBeliefWithChannelDisclosureAndProcessEvidence() {
        val reads = CapturedReads()
        val result = evaluate(reads = reads)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertEquals(1, result.changes.size)
        assertTrue(result.effects.isEmpty())
        val acquisition = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "N2", "C1"), acquisition.acquisition.holder)
        assertEquals(KnowledgeEpistemicState.BELIEVED, acquisition.acquisition.epistemicState)
        assertEquals(KnowledgeScope.PERSONAL, acquisition.acquisition.scope)
        assertEquals("N1", acquisition.claim.subjectUid)
        assertEquals("P64:ASSERTED_MESSAGE:M1", acquisition.claim.predicateUid)
        assertEquals("Most jest bezpieczny.", acquisition.claim.valueCanonical)
        assertTrue(acquisition.evidence.all { it.polarity == KnowledgeEvidencePolarity.NEUTRAL })
        assertEquals(setOf("NPC", "INFORMATION_CHANNEL", "DISCLOSURE_POLICY", "BACKGROUND_PROCESS", "ACCESS_PATH_EVIDENCE"),
            acquisition.evidence.mapNotNull { it.sourceRef?.kindUid }.toSet())
        assertEquals(2, acquisition.evidence.count { it.sourceRef?.kindUid == "ACCESS_PATH_EVIDENCE" })
        assertEquals(listOf(actor, recipient), reads.authorization.map { it.actor })
        assertTrue(reads.owned.isEmpty())
    }

    @Test fun capturedRecipeSenderAndDecisionActorCannotBeSubstitutedByAnotherInitiator() {
        val message=definition(parameters=delivery+mapOf("sender_kind" to actor.kindUid,"sender_uid" to "OTHER_SENDER"))
        assertEquals("P64:INFORMATION_SENDER_SCOPE",evaluate(message).reasonUid)
        val decision=definition("ORGANIZATION","DECISION",mapOf("organization_uid" to "O1",
            "agenda_uid" to "EXISTING_AGENDA","agenda_version" to "1","option_uid" to "LEGAL_OPTION","decision_policy_uid" to "POLICY",
            "decision_actor_kind" to actor.kindUid,"decision_actor_uid" to "OTHER_ACTOR"))
        val reads=CapturedReads()
        assertEquals("P64:INSTITUTIONAL_DECISION_ACTOR_SCOPE",evaluate(decision,reads).reasonUid)
        assertTrue(reads.owned.isEmpty())
    }

    @Test fun institutionGetsOnlyExplicitDeliveryAndNoMemberMemoryOrSenderAcquisition() {
        val d = definition(parameters = delivery + mapOf("recipient_kind" to "ORGANIZATION", "recipient_uid" to "O2"))
        val result = evaluate(d)
        val acquisition = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeHolderRef(KnowledgeHolderKinds.ORGANIZATION, "O2", "C1"), acquisition.acquisition.holder)
        assertEquals(KnowledgeScope.INSTITUTIONAL, acquisition.acquisition.scope)
        assertNull(acquisition.acquisition.parentAcquisitionUid)
        assertNull(acquisition.acquisition.sourceHolder)
        assertEquals(1, result.changes.size)
    }

    @Test fun reportSourceIsNeutralCitationNotProofOfReportedAssertion() {
        val d = definition(operation = "REPORT", parameters = delivery + ("source_acquisition_uid" to "SOURCE_ACQ"))
        val reads = CapturedReads()
        val result = evaluate(d, reads)
        val acquisition = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeAcquisitionMethods.REPORT, acquisition.acquisition.methodUid)
        val citation = acquisition.evidence.single { it.evidenceKindUid == "P64:REPORT_SOURCE_CITATION" }
        assertEquals(KnowledgeSourceRef.campaign("C1", "KNOWLEDGE_ACQUISITION", "SOURCE_ACQ"), citation.sourceRef)
        assertNull(citation.sourceAcquisitionUid)
        assertEquals(KnowledgeEvidencePolarity.NEUTRAL, citation.polarity)
        assertNull(acquisition.acquisition.parentAcquisitionUid); assertNull(acquisition.acquisition.sourceHolder)
        KnowledgeDomainValidator.validateForCampaign(acquisition, "C1")
        assertTrue(reads.authorization.first().references.contains(DomainRef("KNOWLEDGE_ACQUISITION", "SOURCE_ACQ")))
    }

    @Test fun delayedFalseReportCreatesOnlyNamedRecipientBeliefAndMissingCitationNeverReachesDelivery() {
        val d = definition(operation = "REPORT", parameters = delivery + mapOf("source_acquisition_uid" to "SOURCE_ACQ",
            "message_text" to "Most jest zamknięty, choć nadawca twierdzi inaczej."))
        val early = CapturedReads()
        val waiting = evaluate(d, early, at = WorldTimeTick(999))
        assertEquals(BackgroundProcessStatus.ACTIVE, waiting.status)
        assertTrue(waiting.changes.isEmpty()); assertTrue(early.authorization.isEmpty())
        val delivered = evaluate(d)
        val acquisition = delivered.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, recipient.uid, "C1"), acquisition.acquisition.holder)
        assertEquals(KnowledgeEpistemicState.BELIEVED, acquisition.acquisition.epistemicState)
        assertEquals(KnowledgeAcquisitionMethods.REPORT, acquisition.acquisition.methodUid)
        assertEquals(actor.uid, acquisition.claim.subjectUid)
        assertTrue(acquisition.claim.predicateUid.startsWith("P64:ASSERTED_MESSAGE:"))
        assertEquals(d.parameters["message_text"], acquisition.claim.valueCanonical)
        assertTrue(acquisition.evidence.all { it.polarity == KnowledgeEvidencePolarity.NEUTRAL && it.sourceAcquisitionUid == null })
        assertTrue(acquisition.evidence.any { it.sourceRef == KnowledgeSourceRef.campaign("C1", "INFORMATION_CHANNEL", "COURIER") })
        assertTrue(acquisition.evidence.any { it.sourceRef == KnowledgeSourceRef.campaign("C1", "DISCLOSURE_POLICY", "NAMED_RECIPIENT") })
        assertTrue(delivered.effects.isEmpty())
        val missing = CapturedReads().also { it.missing = setOf(DomainRef("KNOWLEDGE_ACQUISITION", "SOURCE_ACQ")) }
        val blocked = evaluate(d, missing)
        assertEquals("P64:REPORT_SOURCE_ACQUISITION_MISSING", blocked.reasonUid)
        assertTrue(blocked.changes.isEmpty()); assertTrue(missing.authorization.isEmpty())
    }

    private fun scopedCommunicationSnapshot(d: BackgroundProcessDefinition = definition()): Phase64ScopedCommunicationSnapshot {
        val anchor = DomainRef("LOCATION", "CANONICAL_VILLAGE")
        val channel = DomainRef("INFORMATION_CHANNEL", d.parameters.getValue("channel_uid"))
        val disclosure = DomainRef("DISCLOSURE_POLICY", d.parameters.getValue("disclosure_policy_uid"))
        fun principal(ref: DomainRef) = Phase64CommunicationPrincipalCapture(ref,
            KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, ref.uid, "C1"),
            MechanicalActorView("C1", ref, MechanicalActorKind.NPC, 2, MechanicalStateMaterialization.FULL,
                emptyMap(), emptyList(), emptySet(), locationRef = anchor, generationProvenanceUid = "ACTUAL_BODY"))
        fun records(ref: DomainRef, purpose: String): List<AccessAuthorityRecord> {
            val exact = VisibilityPrincipalRef(ref.kindUid, ref.uid)
            fun record(uid: String, operation: AccessOperation, value: String, subject: DomainRef) =
                AccessAuthorityRecord("${ref.uid}:$uid", operation, exact, AccessGrantKind.EXPLICIT.name, value,
                    subject.kindUid, subject.uid, 0, null, 7)
            return listOf(record("CHANNEL_GRANT", AccessOperation.GRANT, purpose, channel),
                record("DISCLOSURE_GRANT", AccessOperation.GRANT, purpose, disclosure)) + CarrierAccessStage.entries.map {
                    record(it.name, AccessOperation.SET_CARRIER_ACCESS, it.name, channel)
                }
        }
        return Phase64ScopedCommunicationSnapshot(scope.temporal, 7, setOf(actor, recipient, channel, disclosure, anchor),
            principal(actor), principal(recipient), records(actor, "P64:INFO_SEND:${d.operation}"), records(recipient, "P64:INFO_RECEIVE"))
    }

    private fun scopedCommunication(d: BackgroundProcessDefinition = definition(),
        captured: Phase64ScopedCommunicationSnapshot = scopedCommunicationSnapshot(d),
        staged: List<PlayerDomainChangePayload> = emptyList()) =
        Phase64ScopedCommunicationOwner.prepare(d, process(d), scope, WorldTimeTick(1000), captured, staged)

    private fun scopedReason(result: Phase64ScopedCommunicationPreparation) =
        (result as Phase64ScopedCommunicationPreparation.Unavailable).reasonUid

    @Test fun registeredChannelOwnerRequiresActualBothPrincipalGrantsAndStagesForAllInformationOperations() {
        listOf("MESSAGE", "REPORT", "DIPLOMACY", "ESPIONAGE").forEach { operation ->
            val d = definition(operation = operation)
            val capture = scopedCommunicationSnapshot(d)
            val result = scopedCommunication(d, capture) as Phase64ScopedCommunicationPreparation.Ready
            val senderAccess = requireNotNull(result.accessFor(actor, "P64:INFO_SEND:$operation"))
            val receiverAccess = requireNotNull(result.accessFor(recipient, "P64:INFO_RECEIVE"))
            assertEquals(VisibilityPrincipalRef(actor.kindUid, actor.uid), senderAccess.path!!.principal)
            assertEquals(VisibilityPrincipalRef(recipient.kindUid, recipient.uid), receiverAccess.path!!.principal)
            assertEquals(InformationCarrierRef("C1", "INFORMATION_CHANNEL", "COURIER"), senderAccess.path!!.carrier)
            assertEquals(CarrierAccessStage.entries.toSet(), senderAccess.path!!.resolvedStages)
            assertFalse(senderAccess.path!!.worldRulePermitsAccess)
            assertNotEquals(senderAccess.path!!.evidenceUid, receiverAccess.path!!.evidenceUid)
            assertNull(result.accessFor(DomainRef("NPC", "N3"), "P64:INFO_RECEIVE"))
            assertNull(result.accessFor(actor, "P64:INFO_RECEIVE"))
            assertNull(result.accessFor(actor, "UNRELATED_PURPOSE"))
            val noStages = capture.copy(senderAuthority = capture.senderAuthority.filter { it.operation == AccessOperation.GRANT })
            assertEquals("P64:COMMUNICATION_CARRIER_OWNER_UNAVAILABLE", scopedReason(scopedCommunication(d, noStages)))
            val noDisclosure = capture.copy(recipientAuthority = capture.recipientAuthority.filterNot { it.subjectKindUid == "DISCLOSURE_POLICY" })
            assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(d, noDisclosure)))
        }
        assertEquals("P64:COMMUNICATION_PROCESS_SCOPE", scopedReason(scopedCommunication(definition(operation = "UNREGISTERED"))))
    }

    @Test fun channelCaptureRejectsStaleHistoryWrongPrincipalHolderAndUnregisteredCanonicalReferences() {
        val capture = scopedCommunicationSnapshot()
        assertEquals("P64:COMMUNICATION_STALE_CAPTURE", scopedReason(scopedCommunication(captured = capture.copy(
            currentScope = scope.temporal.copy(historyGenerationUid = "G2")))))
        assertEquals("P64:COMMUNICATION_STALE_CAPTURE", scopedReason(scopedCommunication(captured = capture.copy(currentOrder = 8))))
        assertEquals("P64:COMMUNICATION_PRINCIPAL_SCOPE", scopedReason(scopedCommunication(captured = capture.copy(
            recipient = capture.recipient!!.copy(holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "N3", "C1"))))))
        assertEquals("P64:COMMUNICATION_PRINCIPAL_SCOPE", scopedReason(scopedCommunication(captured = capture.copy(
            recipient = capture.recipient!!.copy(body = capture.recipient!!.body!!.copy(campaignUid = "C2"))))))
        assertEquals("P64:COMMUNICATION_AUTHORITY_PRINCIPAL_MISMATCH", scopedReason(scopedCommunication(captured = capture.copy(
            recipientAuthority = capture.senderAuthority))))
        assertEquals("P64:COMMUNICATION_CHANNEL_OR_DISCLOSURE_UNAVAILABLE", scopedReason(scopedCommunication(captured = capture.copy(
            canonicalReferences = capture.canonicalReferences - DomainRef("DISCLOSURE_POLICY", "NAMED_RECIPIENT")))))
        val wrongChannel = capture.copy(senderAuthority = capture.senderAuthority.map {
            if (it.operation == AccessOperation.SET_CARRIER_ACCESS) it.copy(subjectUid = "PRIVATE_CHANNEL") else it
        })
        assertEquals("P64:COMMUNICATION_CARRIER_OWNER_UNAVAILABLE", scopedReason(scopedCommunication(captured = wrongChannel)))
        val onlyMembership = capture.copy(senderAuthority = listOf(AccessAuthorityRecord("ROLE", AccessOperation.UPSERT_BINDING,
            VisibilityPrincipalRef(actor.kindUid, actor.uid), AccessBindingKind.ROLE.name, "MEMBER", null, null, 0, null, 7)))
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(captured = onlyMembership)))
    }

    @Test fun channelAuthorityUsesCanonicalBaseAndOnlyEffectiveStagedGrantOrRevocationAtNextCommit() {
        val capture = scopedCommunicationSnapshot()
        val current = scopedCommunication(captured = capture) as Phase64ScopedCommunicationPreparation.Ready
        val future = capture.copy(senderAuthority = capture.senderAuthority.map { it.copy(validFromOrder = 9) })
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(captured = future)))
        val futureCreated = capture.copy(senderAuthority = capture.senderAuthority.map { it.copy(createdOrder = 8) })
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(captured = futureCreated)))
        val expiresBeforeCommit = capture.copy(senderAuthority = capture.senderAuthority.map { it.copy(validUntilOrder = 7) })
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(captured = expiresBeforeCommit)))
        val revoke = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "REVOKE_RECEIVER_CHANNEL", recipient.kindUid,
            recipient.uid, AccessGrantKind.EXPLICIT.name, "P64:INFO_RECEIVE", "INFORMATION_CHANNEL", "COURIER", 8)
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(captured = capture, staged = listOf(revoke))))
        val later = scopedCommunication(captured = capture, staged = listOf(revoke.copy(validFromOrder = 9))) as Phase64ScopedCommunicationPreparation.Ready
        assertEquals(current.recipientAccess.path!!.evidenceUid, later.recipientAccess.path!!.evidenceUid)
        val stageRevoke = revoke.copy(recordUid = "REVOKE_COMPREHENSION", valueUid = CarrierAccessStage.COMPREHENDED.name)
        assertEquals("P64:COMMUNICATION_CARRIER_OWNER_UNAVAILABLE", scopedReason(scopedCommunication(captured = capture, staged = listOf(stageRevoke))))
        assertTrue(scopedCommunication(captured = capture, staged = listOf(revoke.copy(principalUid = "N3"))) is Phase64ScopedCommunicationPreparation.Ready)
        val grant = revoke.copy(operation = AccessOperation.GRANT, recordUid = "RESTORE_RECEIVER_CHANNEL")
        val noChannelGrant = capture.copy(recipientAuthority = capture.recipientAuthority.filterNot {
            it.operation == AccessOperation.GRANT && it.subjectKindUid == "INFORMATION_CHANNEL"
        })
        assertTrue(scopedCommunication(captured = noChannelGrant, staged = listOf(grant)) is Phase64ScopedCommunicationPreparation.Ready)
        assertEquals("P64:COMMUNICATION_PURPOSE_GRANT_REQUIRED", scopedReason(scopedCommunication(captured = noChannelGrant,
            staged = listOf(grant.copy(validFromOrder = 9)))))
    }

    @Test fun scopedChannelCannotInventAnOrganizationAddressOrUseAnUnboundRemoteRoute() {
        val capture = scopedCommunicationSnapshot()
        val origin = capture.sender!!.anchor!!
        val destination = DomainRef("LOCATION", "ACTUAL_DESTINATION")
        val remoteReceiver = capture.recipient!!.copy(body = capture.recipient!!.body!!.copy(locationRef = destination), anchor = destination)
        val edge = WorldTopologyEdge("ACTUAL_ROUTE", 1, origin, destination, ActionDuration(500), emptyMap(), emptySet(),
            WorldTimeTick(0), null, "ACTUAL_TOPOLOGY")
        val route = Phase64CommunicationRouteCapture(scope.temporal, actor, WorldTravelPlan(origin, destination, listOf(edge)), "AUTHORIZED_ROUTE_CAPTURE")
        val remote = capture.copy(recipient = remoteReceiver, canonicalReferences = capture.canonicalReferences + destination, route = route)
        assertTrue(scopedCommunication(captured = remote) is Phase64ScopedCommunicationPreparation.Ready)
        assertEquals("P64:COMMUNICATION_ROUTE_OWNER_UNAVAILABLE", scopedReason(scopedCommunication(captured = remote.copy(route = null))))
        assertEquals("P64:COMMUNICATION_ROUTE_SCOPE", scopedReason(scopedCommunication(captured = remote.copy(route = route.copy(principal = recipient)))))
        assertEquals("P64:COMMUNICATION_ROUTE_SCOPE", scopedReason(scopedCommunication(captured = remote.copy(route = route.copy(
            scope = scope.temporal.copy(historyGenerationUid = "G2"))))))
        assertEquals("P64:COMMUNICATION_ROUTE_SCOPE", scopedReason(scopedCommunication(captured = remote.copy(route = route.copy(
            plan = WorldTravelPlan(origin, destination, listOf(edge.copy(duration = ActionDuration(1001)))))))))
        val org = DomainRef("ORGANIZATION", "O2")
        val d = definition(parameters = delivery + mapOf("recipient_kind" to org.kindUid, "recipient_uid" to org.uid))
        val organizationReceiver = Phase64CommunicationPrincipalCapture(org, KnowledgeHolderRef(KnowledgeHolderKinds.ORGANIZATION, org.uid, "C1"))
        val organizationCapture = capture.copy(recipient = organizationReceiver, canonicalReferences = capture.canonicalReferences + org,
            recipientAuthority = capture.recipientAuthority.map { it.copy(principal = VisibilityPrincipalRef(org.kindUid, org.uid)) })
        assertEquals("P64:COMMUNICATION_RECIPIENT_ANCHOR_UNAVAILABLE", scopedReason(scopedCommunication(d, organizationCapture)))
        assertEquals("P64:COMMUNICATION_PRINCIPAL_SCOPE", scopedReason(scopedCommunication(d, organizationCapture.copy(
            recipient = organizationReceiver.copy(anchor = origin)))))
        assertTrue(scopedCommunication(d, organizationCapture.copy(recipient = organizationReceiver.copy(anchor = origin,
            anchorEvidenceUid = "ACTUAL_REGISTERED_ORGANIZATION_MAILBOX"))) is Phase64ScopedCommunicationPreparation.Ready)
    }

    @Test fun importedDeliveryWithoutChannelOwnerNeverFallsBackToBooleanGrant() {
        val reads = CapturedReads().also { it.communicationRead = null }
        val blocked = evaluate(reads = reads)
        assertEquals("P64:INFORMATION_SENDER_ACCESS_DENIED", blocked.reasonUid)
        assertTrue(blocked.changes.isEmpty()); assertTrue(reads.authorization.isEmpty())
    }

    private fun neutralCommunicationSnapshot(): Phase64NeutralCommunicationSnapshot {
        val sender = DomainRef("PLAYER", "P1")
        val location = DomainRef("LOCATION", "VILLAGE")
        val d = BackgroundProcessDefinition(Phase64NeutralCommunicationOwner.RULE, 1, "INFORMATION", "MESSAGE", 1000,
            parameters = mapOf(Phase64ProcessActivation.ACTION_KEY to Phase64NeutralCommunicationOwner.ACTION,
                Phase64ProcessActivation.PUBLIC_KEY to "true", "recipient_kind" to "@TARGET_KIND", "recipient_uid" to "@TARGET_UID",
                "message_uid" to "@PROCESS_UID", "message_text" to "@MESSAGE_LITERAL", "delay_ms" to "1000",
                "channel_uid" to Phase64NeutralCommunicationOwner.CHANNEL, "disclosure_policy_uid" to Phase64NeutralCommunicationOwner.DISCLOSURE))
        val bound = Phase64ProcessActivation.bind(d, sender, recipient, "Most jest bezpieczny.", "NAMED_MESSAGE")
        val p = BackgroundProcessInstance("NAMED_MESSAGE", d.uid, 1, sender, 1, WorldTimeTick(0), WorldTimeTick(1000),
            parameters = bound + mapOf(
                "p64_initiator_kind_uid" to sender.kindUid, "p64_initiator_uid" to sender.uid,
                "p64_start_target_kind" to recipient.kindUid, "p64_start_target_uid" to recipient.uid,
                "p64_start_command_uid" to "SEND1", "p64_start_proof_uid" to Phase64ProcessActivation.START_PROOF +
                    phase63Hash(Phase64BackgroundCodec.definition(d).toString()) + ":SIGNED_START:" +
                    Phase64ProcessActivation.parameterFingerprint(bound)))
        fun body(ref: DomainRef, kind: MechanicalActorKind, version: Long) = MechanicalActorView("C1", ref, kind, version,
            MechanicalStateMaterialization.FULL, emptyMap(), emptyList(), emptySet(), locationRef = location,
            generationProvenanceUid = "CANONICAL_BODY")
        return Phase64NeutralCommunicationSnapshot(scope.temporal, d, p,
            body(sender, MechanicalActorKind.ACTIVE_PLAYER, 3), body(recipient, MechanicalActorKind.NPC, 2))
    }

    private fun neutralReferences(snapshot: Phase64NeutralCommunicationSnapshot, sender: Boolean) = listOf(
        if (sender) snapshot.recipient!!.actor else snapshot.sender!!.actor,
        DomainRef("INFORMATION_CHANNEL", Phase64NeutralCommunicationOwner.CHANNEL),
        DomainRef("DISCLOSURE_POLICY", Phase64NeutralCommunicationOwner.DISCLOSURE))

    @Test fun neutralCoreMessageRetainsExactChannelPathsAndOnlyNamedRecipientAcquiresTheAssertion() {
        val snapshot = neutralCommunicationSnapshot()
        val reads = CapturedReads().also { read ->
            read.deniedPurpose = "P64:INFO_SEND:MESSAGE"
            read.communicationRead = { call -> Phase64NeutralCommunicationOwner.authorize(call.actor, call.purpose,
                call.references, scope, call.at, snapshot) }
        }
        val result = evaluate(snapshot.definition, reads, snapshot.process)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertEquals(listOf(snapshot.sender!!.actor, recipient), reads.communicationCalls.map { it.actor })
        assertTrue(reads.authorization.isEmpty())
        val acquisition = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, recipient.uid, "C1"), acquisition.acquisition.holder)
        assertEquals(KnowledgeCarrierRef("INFORMATION_CHANNEL", Phase64NeutralCommunicationOwner.CHANNEL, "C1"), acquisition.acquisition.carrier)
        assertEquals(KnowledgeEpistemicState.BELIEVED, acquisition.acquisition.epistemicState)
        val accessEvidence = acquisition.evidence.filter { it.sourceRef?.kindUid == "ACCESS_PATH_EVIDENCE" }
        assertEquals(2, accessEvidence.size)
        assertEquals(2, accessEvidence.map { it.sourceRef!!.entityUid }.distinct().size)
        assertTrue(accessEvidence.all { it.polarity == KnowledgeEvidencePolarity.NEUTRAL &&
            it.sourceCarrier == acquisition.acquisition.carrier })
        KnowledgeDomainValidator.validateForCampaign(acquisition, "C1")
        assertTrue(result.effects.isEmpty())
    }

    @Test fun neutralCoreChannelCannotAuthorizeUnrelatedPrincipalsPrivateRulesOrMissingCanonicalBodies() {
        val snapshot = neutralCommunicationSnapshot()
        val sender = snapshot.sender!!.actor
        val references = neutralReferences(snapshot, true)
        fun authorize(candidate: Phase64NeutralCommunicationSnapshot = snapshot, refs: List<DomainRef> = references,
            principal: DomainRef = sender, purpose: String = "P64:INFO_SEND:MESSAGE") =
            Phase64NeutralCommunicationOwner.authorize(principal, purpose, refs, scope, WorldTimeTick(1000), candidate)
        assertNotNull(authorize())
        assertNull(authorize(snapshot.copy(sender = null)))
        assertNull(authorize(snapshot.copy(recipient = null)))
        assertNull(authorize(snapshot.copy(recipient = snapshot.recipient!!.copy(campaignUid = "C2"))))
        assertNull(authorize(snapshot.copy(currentScope = scope.temporal.copy(historyGenerationUid = "G2"))))
        assertNull(authorize(refs = references + DomainRef("ORGANIZATION", "PRIVATE_MEMBERS")))
        assertNull(authorize(principal = DomainRef("NPC", "N3")))
        assertNull(authorize(purpose = "P64:INFO_SEND:REPORT"))
        assertNull(authorize(snapshot.copy(definition = snapshot.definition.copy(uid = "IMPORTED_MESSAGE"))))
        assertNull(authorize(snapshot.copy(process = snapshot.process.copy(parameters = snapshot.process.parameters +
            ("p64_start_proof_uid" to "UNSIGNED")))))
        assertNull(authorize(snapshot.copy(process = snapshot.process.copy(parameters = snapshot.process.parameters +
            ("message_text" to "Treść podmieniona po przyjęciu wypowiedzi.")))))
        assertNull(authorize(snapshot.copy(process = snapshot.process.copy(parameters = snapshot.process.parameters +
            ("message_uid" to "OTHER_PROCESS")))))
        val privateDefinition = snapshot.definition.copy(uid = "PRIVATE_MESSAGE")
        val privateProcess = snapshot.process.copy(definitionUid = privateDefinition.uid)
        val reads = CapturedReads().also {
            it.deniedPurpose = "P64:INFO_SEND:MESSAGE"
            it.communicationRead = { call -> Phase64NeutralCommunicationOwner.authorize(call.actor, call.purpose,
                call.references, scope, call.at, snapshot.copy(definition = privateDefinition, process = privateProcess)) }
        }
        assertEquals("P64:INFORMATION_SENDER_ACCESS_DENIED", evaluate(privateDefinition, reads, privateProcess).reasonUid)
        assertEquals(1, reads.communicationCalls.size)
    }

    @Test fun remoteNeutralCourierRequiresExactCurrentCostFreeRouteThatFitsRegisteredDelay() {
        val local = neutralCommunicationSnapshot()
        val origin = local.sender!!.locationRef!!
        val destination = DomainRef("LOCATION", "BRIDGE")
        val edge = WorldTopologyEdge("KNOWN_ROUTE", 1, origin, destination, ActionDuration(500), emptyMap(), emptySet(),
            WorldTimeTick(0), null, "REGISTERED_TOPOLOGY")
        val remote = local.copy(recipient = local.recipient!!.copy(locationRef = destination),
            route = WorldTravelPlan(origin, destination, listOf(edge)))
        fun authorize(candidate: Phase64NeutralCommunicationSnapshot) = Phase64NeutralCommunicationOwner.authorize(
            candidate.sender!!.actor, "P64:INFO_SEND:MESSAGE", neutralReferences(candidate, true), scope, WorldTimeTick(1000), candidate)
        assertNotNull(authorize(remote))
        assertNull(authorize(remote.copy(route = null)))
        assertNull(authorize(remote.copy(recipient = remote.recipient!!.copy(locationRef = DomainRef("LOCATION", "OTHER_ADDRESS")))))
        assertNull(authorize(remote.copy(route = WorldTravelPlan(origin, destination, listOf(edge.copy(duration = ActionDuration(1001)))))))
        assertNull(authorize(remote.copy(route = WorldTravelPlan(origin, destination, listOf(edge.copy(validThrough = WorldTimeTick(999)))))))
        assertNull(authorize(remote.copy(route = WorldTravelPlan(origin, destination, listOf(edge.copy(resourceCosts = mapOf("ENERGY" to 1)))))))
        assertNull(authorize(remote.copy(route = WorldTravelPlan(origin, destination, listOf(edge.copy(requiredCapabilities = setOf("FLY")))))))
    }

    @Test fun mismatchedNeutralChannelPathCannotDeliverEvenWithOrdinaryGrant() {
        val snapshot = neutralCommunicationSnapshot()
        val carrier = InformationCarrierRef("C1", "INFORMATION_CHANNEL", Phase64NeutralCommunicationOwner.CHANNEL)
        val path = AccessPath.issue("C1", VisibilityPrincipalRef("NPC", "N3"), carrier, "OTHER_ACTOR", "OTHER_EVIDENCE", false,
            CarrierAccessStage.entries.toSet())
        val reads = CapturedReads().also { it.communicationRead = {
            EffectiveAccessDecision.granted("OTHER_ACTOR", path, path.resolvedStages)
        } }
        val blocked = evaluate(snapshot.definition, reads, snapshot.process)
        assertEquals("P64:INFORMATION_CHANNEL_ACCESS_SCOPE", blocked.reasonUid)
        assertTrue(blocked.changes.isEmpty()); assertTrue(reads.authorization.isEmpty())
    }

    @Test fun diplomacyDeliversOfferWithoutCreatingAgreementOrNpcAction() {
        val d = definition(operation = "DIPLOMACY", parameters = delivery + ("organization_uid" to "O1"))
        val result = evaluate(d)
        assertEquals(1, result.changes.size)
        val acquisition = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeDomains.POLITICS, acquisition.claim.domainUid)
        assertEquals(KnowledgeAcquisitionMethods.INSTITUTIONAL_SHARING, acquisition.acquisition.methodUid)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun senderAndRecipientDisclosureAreBothCheckedAndDeniedDeliveryHasNoEffects() {
        listOf("P64:INFO_SEND:MESSAGE", "P64:INFO_RECEIVE").forEach { denied ->
            val reads = CapturedReads().also { it.deniedPurpose = denied }
            val result = evaluate(reads = reads)
            assertEquals(BackgroundProcessStatus.BLOCKED, result.status)
            assertTrue(result.changes.isEmpty()); assertTrue(result.effects.isEmpty()); assertTrue(reads.owned.isEmpty())
        }
    }

    @Test fun directDeliveryRechecksRecipientDisclosureAgainstEarlierStagedRevocation() {
        val revoke = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "DISCLOSURE_REVOKED", "NPC", "N2",
            AccessGrantKind.EXPLICIT.name, "NAMED_RECIPIENT", validFromOrder = 7)
        val staged = listOf<PlayerDomainChangePayload>(revoke)
        val reads = CapturedReads()
        val blocked = evaluate(reads = reads, staged = staged)
        assertEquals("P64:INFORMATION_DISCLOSURE_DENIED", blocked.reasonUid)
        assertTrue(blocked.changes.isEmpty()); assertTrue(blocked.effects.isEmpty())
        assertEquals(2, reads.authorizationOverlays.size)
        assertTrue(reads.authorizationOverlays.all { it === staged })
        val allowed = evaluate(reads = CapturedReads(), staged = emptyList())
        assertEquals(BackgroundProcessStatus.COMPLETED, allowed.status)
        assertEquals(1, allowed.changes.size)
    }

    @Test fun futureDelayIsNotSilentlyDeliveredAndNotDueProcessDoesNotReadAuthority() {
        val late = definition(parameters = delivery + ("delay_ms" to "1500"))
        assertEquals("P64:INFORMATION_DELAY_NOT_SCHEDULED", evaluate(late).reasonUid)
        val reads = CapturedReads()
        assertEquals(BackgroundProcessStatus.ACTIVE, evaluate(reads = reads, at = WorldTimeTick(999)).status)
        assertTrue(reads.authorization.isEmpty()); assertTrue(reads.owned.isEmpty())
    }

    @Test fun retryLateEvaluationAndHistoryGenerationDoNotRerollDeliveryIdentity() {
        val d = definition(parameters = delivery + mapOf("transmission_policy_uid" to "NOISY_POST",
            "transmission_policy_version" to "1", "loss_basis_points" to "5000",
            "distortion_basis_points" to "5000", "distorted_text" to "Most jest zamknięty."))
        val first = evaluate(d)
        val retry = evaluate(d, at = WorldTimeTick(9000))
        val afterUndo = evaluate(d, currentScope = scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "G2")))
        val afterBlockedRetry = evaluate(d, instance = process(d).copy(status = BackgroundProcessStatus.BLOCKED,
            due = WorldTimeTick(2000)), at = WorldTimeTick(2000))
        assertEquals(first, retry)
        assertEquals(first, afterUndo)
        assertEquals(first, afterBlockedRetry)
    }

    @Test fun certainRegisteredLossCreatesNoKnowledgeAndDistortionUsesOnlyRegisteredReplacement() {
        val policy = mapOf("transmission_policy_uid" to "POST", "transmission_policy_version" to "1")
        val lost = evaluate(definition(parameters = delivery + policy + ("loss_basis_points" to "10000")))
        assertEquals(BackgroundProcessStatus.COMPLETED, lost.status)
        assertEquals("P64:INFORMATION_LOST_BY_RULE", lost.reasonUid)
        assertTrue(lost.changes.isEmpty())
        val d = definition(parameters = delivery + policy + mapOf("distortion_basis_points" to "10000",
            "distorted_text" to "Most jest zamknięty."))
        val result = evaluate(d, instance = process(d, mapOf("distortion_basis_points" to "0", "distorted_text" to "Podszyta treść")))
        val acquisition = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals("Most jest zamknięty.", acquisition.claim.valueCanonical)
        assertEquals(KnowledgeEpistemicState.SUSPECTED, acquisition.acquisition.epistemicState)
        assertTrue(acquisition.evidence.any { it.evidenceKindUid == "P64:DELIVERY_DISTORTION" })
    }

    @Test fun unregisteredTransmissionOrMalformedInputIsTypedUnavailable() {
        val definitions = listOf(
            definition(parameters = delivery + ("loss_basis_points" to "1")),
            definition(parameters = delivery + ("delay_ms" to "-1")),
            definition(parameters = delivery + ("delay_ms" to "bad")),
            definition(parameters = delivery - "channel_uid"),
            definition(operation = "REPORT"),
            definition(parameters = delivery + ("recipient_kind" to "MONEY")))
        definitions.forEach { d ->
            val result = evaluate(d)
            assertEquals(BackgroundProcessStatus.BLOCKED, result.status)
            assertTrue(result.changes.isEmpty())
        }
    }

    @Test fun assignmentUsesVersionedExistingOwnerAndSameStagedOverlayWithoutWriting() {
        val d = definition("ORGANIZATION", "ASSIGN", assignment + mapOf("resource_kind" to "LABOUR",
            "resource_uid" to "GUARDS", "quantity" to "2"))
        val reads = CapturedReads()
        val overlay = listOf(reads.ownedResult.changes.single())
        val result = evaluate(d, reads, staged = overlay)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertEquals(listOf(WorldResourceClaim(DomainRef("LABOUR", "GUARDS"), 2)), result.claims)
        val call = reads.owned.single()
        assertEquals(Phase64OrganizationsInformationAdapter.OWNER_ASSIGN, call.operation)
        assertEquals("2", call.parameters["duty_version"])
        assertEquals("1000", call.parameters["_p64_effective_ms"])
        assertEquals(scope.ruleFingerprint, call.parameters["_p64_rule_fingerprint"])
        assertSame(overlay, call.staged)
        assertSame(overlay, reads.overlays.single())
        assertTrue(reads.authorization.single().references.contains(DomainRef("NPC_DUTY", "D1")))
    }

    @Test fun unavailableAuthorityResourcesAndExpiredDutyDoNotReachMutationOwner() {
        val d = definition("ORGANIZATION", "ASSIGN", assignment + mapOf("resource_kind" to "LABOUR",
            "resource_uid" to "GUARDS", "quantity" to "5"))
        val short = CapturedReads()
        assertEquals("P64:ORGANIZATION_RESOURCE_INSUFFICIENT", evaluate(d, short).reasonUid)
        assertTrue(short.owned.isEmpty())
        val absent = CapturedReads().also { it.available = null }
        assertEquals("P64:ORGANIZATION_RESOURCE_UNAVAILABLE", evaluate(d, absent).reasonUid)
        assertTrue(absent.owned.isEmpty())
        val denied = CapturedReads().also { it.deniedPurpose = Phase64OrganizationsInformationAdapter.OWNER_ASSIGN }
        assertEquals("P64:ORGANIZATION_AUTHORITY_DENIED", evaluate(definition("ORGANIZATION", "ASSIGN", assignment), denied).reasonUid)
        assertTrue(denied.owned.isEmpty())
        val expired = definition("ORGANIZATION", "ASSIGN", assignment + ("deadline_ms" to "1000"))
        assertEquals("P64:DUTY_DEADLINE_EXPIRED", evaluate(expired).reasonUid)
    }

    @Test fun agendaDecisionAndRevocationAreRegisteredOperationsAndCannotCompleteAsTextOnly() {
        val cases = listOf(
            definition("ORGANIZATION", "AGENDA", mapOf("organization_uid" to "O1", "agenda_uid" to "A1",
                "agenda_version" to "1", "objective" to "Przygotować raport.")) to Phase64OrganizationsInformationAdapter.OWNER_AGENDA,
            definition("ORGANIZATION", "DECISION", mapOf("organization_uid" to "O1", "agenda_uid" to "A1",
                "agenda_version" to "1", "option_uid" to "LEGAL_OPTION", "decision_policy_uid" to "LEGAL_DECISION")) to Phase64OrganizationsInformationAdapter.OWNER_DECISION,
            definition("ORGANIZATION", "REVOKE", assignment + ("reason_uid" to "ORDER_WITHDRAWN")) to Phase64OrganizationsInformationAdapter.OWNER_REVOKE)
        cases.forEach { (d, operation) ->
            val reads = CapturedReads()
            assertEquals(BackgroundProcessStatus.COMPLETED, evaluate(d, reads).status)
            assertEquals(operation, reads.owned.single().operation)
            val emptyOwner = CapturedReads().also { it.ownedResult = WorldConsequencePlan() }
            if (operation == Phase64OrganizationsInformationAdapter.OWNER_AGENDA)
                assertEquals(BackgroundProcessStatus.COMPLETED, evaluate(d, emptyOwner).status)
            else assertEquals("P64:ORGANIZATION_OWNER_RESULT_REQUIRED", evaluate(d, emptyOwner).reasonUid)
        }
    }

    @Test fun allocationRequiresAvailableResourceAndExistingOwnerTransfer() {
        val parameters = mapOf("organization_uid" to "O1", "recipient_kind" to "NPC", "recipient_uid" to "N2",
            "resource_kind" to "MATERIAL", "resource_uid" to "MEDICINE", "quantity" to "2",
            "allocation_policy_uid" to "MEDICAL_SUPPLY")
        val d = definition("ORGANIZATION", "ALLOCATE", parameters)
        val reads = CapturedReads()
        assertEquals(BackgroundProcessStatus.COMPLETED, evaluate(d, reads).status)
        assertEquals(Phase64OrganizationsInformationAdapter.OWNER_ALLOCATION, reads.owned.single().operation)
        assertEquals(BackgroundProcessStatus.BLOCKED, evaluate(definition("ORGANIZATION", "ALLOCATE", parameters - "quantity")).status)
    }

    @Test fun interruptedProcessesAndMismatchedRulesDoNotAcquireKnowledgeOrRepeatRevocation() {
        val d = definition()
        val reads = CapturedReads()
        val interrupted = process(d).copy(status = BackgroundProcessStatus.INTERRUPTED, reasonUid = "CHANNEL_INTERRUPTED")
        val result = evaluate(d, reads, interrupted)
        assertEquals(BackgroundProcessStatus.INTERRUPTED, result.status)
        assertTrue(result.changes.isEmpty()); assertTrue(reads.owned.isEmpty()); assertTrue(reads.authorization.isEmpty())
        assertEquals("P64:ORG_INFO_RULE_SCOPE", evaluate(d, instance = process(d).copy(definitionVersion = 2)).reasonUid)
    }

    @Test fun espionageRequiresExactCarrierAccessAndRecipientBeliefFromKnowledgeOwner() {
        val d = definition(operation = "ESPIONAGE", parameters = (delivery - "message_text" - "message_uid") + mapOf(
            "carrier_kind" to "REPORT", "carrier_uid" to "PRIVATE_REPORT", "espionage_policy_uid" to "LEGAL_COVERT_PATH"))
        val delivered = evaluate().changes.single() as KnowledgeAcquisitionChange
        val espionage = delivered.copy(acquisition = delivered.acquisition.copy(methodUid = KnowledgeAcquisitionMethods.ESPIONAGE))
        val reads = CapturedReads().also { it.ownedResult = WorldConsequencePlan(changes = listOf(espionage)) }
        assertEquals(BackgroundProcessStatus.COMPLETED, evaluate(d, reads).status)
        assertEquals(Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE, reads.owned.single().operation)
        assertTrue(reads.authorization.first().references.contains(DomainRef("REPORT", "PRIVATE_REPORT")))
        val denied = CapturedReads().also { it.deniedPurpose = "P64:INFO_SEND:ESPIONAGE" }
        assertEquals("P64:INFORMATION_SENDER_ACCESS_DENIED", evaluate(d, denied).reasonUid)
        assertTrue(denied.owned.isEmpty())
        val fact = CapturedReads().also { it.ownedResult = WorldConsequencePlan(changes = listOf(espionage.copy(
            acquisition = espionage.acquisition.copy(epistemicState = KnowledgeEpistemicState.KNOWN)))) }
        assertEquals("P64:ESPIONAGE_ACQUISITION_REQUIRED", evaluate(d, fact).reasonUid)
        assertEquals("P64:ESPIONAGE_ACQUISITION_REQUIRED", evaluate(d).reasonUid)
    }

    @Test fun staleOwnedEvaluationIsPropagatedWithoutReservingResources() {
        val d = definition("ORGANIZATION", "ASSIGN", assignment + mapOf("resource_kind" to "LABOUR",
            "resource_uid" to "GUARDS", "quantity" to "2"))
        val reads = CapturedReads().also { it.ownedResult = WorldConsequencePlan(status = BackgroundProcessStatus.BLOCKED,
            reasonUid = "P64:STALE_HISTORY_GENERATION") }
        val result = evaluate(d, reads)
        assertEquals("P64:STALE_HISTORY_GENERATION", result.reasonUid)
        assertTrue(result.claims.isEmpty()); assertTrue(result.changes.isEmpty())
    }

    private val duty = NpcDutyRule("D1", 2, "O1", "GUARD", "DEADLINE1", WorldTimeTick(2000), "ASSIGN_GUARD")
    private fun dutySnapshot(access: List<AccessAuthorityRecord> = roleRecords(),
                             deadlines: List<WorldProcessDeadline> = emptyList()) =
        Phase64DutyOwnerSnapshot(scope.temporal, 7, true, true, duty, access, emptySet(), deadlines,
            otherAssigneesWithActiveDuty = false)
    private fun roleRecords() = listOf(
        AccessAuthorityRecord("ROLE", AccessOperation.UPSERT_BINDING, VisibilityPrincipalRef("NPC", "N2"),
            AccessBindingKind.ROLE.name, "GUARD", null, null, 0, null, 1),
        AccessAuthorityRecord("ORG", AccessOperation.UPSERT_BINDING, VisibilityPrincipalRef("NPC", "N2"),
            AccessBindingKind.ORGANIZATION.name, "O1", null, null, 0, null, 1))
    private fun activeAssignmentRecord() = AccessAuthorityRecord(
        Phase64OrganizationsInformationOwners.assignmentUid("C1", recipient, duty), AccessOperation.GRANT,
        VisibilityPrincipalRef("NPC", "N2"), AccessGrantKind.WORLD_RULE.name, "ASSIGN_GUARD", "NPC_DUTY", "D1", 7, null, 7)

    @Test fun pureDutyOwnerCreatesRealAccessGrantAndExistingPhase62FutureDeadline() {
        val result = Phase64OrganizationsInformationOwners.prepareDuty(Phase64OrganizationsInformationAdapter.OWNER_ASSIGN,
            assignment + ("_p64_effective_ms" to "1000"), scope, dutySnapshot())
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        val grant = result.changes.single() as AccessAuthorityChange
        assertEquals(AccessOperation.GRANT, grant.operation)
        assertEquals(AccessGrantKind.WORLD_RULE.name, grant.bindingOrGrantKindUid)
        assertEquals("NPC_DUTY", grant.subjectKindUid)
        assertEquals("D1", grant.subjectUid)
        assertEquals(7L, grant.validFromOrder)
        assertEquals(listOf(WorldProcessDeadline("DEADLINE1", NpcDutyDeadlineProcess.OWNER, WorldTimeTick(2000))), result.deadlineAdds)
        assertTrue(result.deadlineRemovals.isEmpty())
        assertTrue(result.changes.none { it is TemporalStateChange })
    }

    @Test fun pureDutyOwnerRejectsChangedRuleAuthorityAndRoleWithoutPartialGrant() {
        val parameters = assignment + ("_p64_effective_ms" to "1000")
        val snapshots = listOf(dutySnapshot().copy(issuingAuthorityAuthorized = false),
            dutySnapshot().copy(assigneeEligible = false), dutySnapshot().copy(registeredDuty = null),
            dutySnapshot().copy(registeredDuty = duty.copy(version = 3)), dutySnapshot(access = roleRecords().take(1)),
            dutySnapshot().copy(currentScope = scope.temporal.copy(historyGenerationUid = "G2")))
        snapshots.forEach { snapshot ->
            val result = Phase64OrganizationsInformationOwners.prepareDuty(Phase64OrganizationsInformationAdapter.OWNER_ASSIGN,
                parameters, scope, snapshot)
            assertEquals(BackgroundProcessStatus.BLOCKED, result.status)
            assertTrue(result.changes.isEmpty()); assertTrue(result.deadlineAdds.isEmpty())
        }
        val revokedRole = AccessAuthorityChange(AccessOperation.REVOKE_BINDING, "ROLE_REVOKED", "NPC", "N2",
            AccessBindingKind.ROLE.name, "GUARD", validFromOrder = 7)
        assertEquals("P64:DUTY_ROLE_OR_ORGANIZATION_MISSING", Phase64OrganizationsInformationOwners.prepareDuty(
            Phase64OrganizationsInformationAdapter.OWNER_ASSIGN, parameters, scope, dutySnapshot(), listOf(revokedRole)).reasonUid)
    }

    @Test fun pureDutyRevocationKeepsHistoryAndSharedDeadlineAndSameVersionCannotRevive() {
        val deadline = WorldProcessDeadline(duty.deadlineUid, NpcDutyDeadlineProcess.OWNER, duty.due)
        val snapshot = dutySnapshot(access = roleRecords() + activeAssignmentRecord(), deadlines = listOf(deadline))
        val result = Phase64OrganizationsInformationOwners.prepareDuty(Phase64OrganizationsInformationAdapter.OWNER_REVOKE,
            assignment + ("reason_uid" to "ORDER_WITHDRAWN"), scope, snapshot)
        assertEquals(AccessOperation.REVOKE_GRANT, (result.changes.single() as AccessAuthorityChange).operation)
        assertEquals(listOf(deadline.uid), result.deadlineRemovals)
        val shared = Phase64OrganizationsInformationOwners.prepareDuty(Phase64OrganizationsInformationAdapter.OWNER_REVOKE,
            assignment + ("reason_uid" to "ORDER_WITHDRAWN"), scope, snapshot.copy(otherAssigneesWithActiveDuty = true))
        assertTrue(shared.deadlineRemovals.isEmpty())
        val history = dutySnapshot().copy(recordedAssignmentUids = setOf(activeAssignmentRecord().recordUid))
        assertEquals("P64:DUTY_VERSION_ALREADY_REVOKED", Phase64OrganizationsInformationOwners.prepareDuty(
            Phase64OrganizationsInformationAdapter.OWNER_ASSIGN, assignment + ("_p64_effective_ms" to "1000"), scope, history).reasonUid)
        val undoRetry = Phase64OrganizationsInformationOwners.prepareDuty(Phase64OrganizationsInformationAdapter.OWNER_ASSIGN,
            assignment + ("_p64_effective_ms" to "1000"), scope.copy(temporal = scope.temporal.copy(historyGenerationUid = "G2")),
            dutySnapshot().copy(currentScope = scope.temporal.copy(historyGenerationUid = "G2")))
        assertEquals(activeAssignmentRecord().recordUid, (undoRetry.changes.single() as AccessAuthorityChange).recordUid)
    }

    @Test fun repeatedExistingDutyNeedsItsDeadlineAndDoesNotGrantTwice() {
        val snapshot = dutySnapshot(access = roleRecords() + activeAssignmentRecord(),
            deadlines = listOf(WorldProcessDeadline(duty.deadlineUid, NpcDutyDeadlineProcess.OWNER, duty.due)))
        val parameters = assignment + ("_p64_effective_ms" to "1000")
        val already = Phase64OrganizationsInformationOwners.prepareDuty(Phase64OrganizationsInformationAdapter.OWNER_ASSIGN,
            parameters, scope, snapshot)
        assertEquals("P64:DUTY_ALREADY_ASSIGNED", already.reasonUid)
        assertTrue(already.changes.isEmpty()); assertTrue(already.deadlineAdds.isEmpty())
        assertEquals("P64:DUTY_ASSIGNED_WITHOUT_DEADLINE", Phase64OrganizationsInformationOwners.prepareDuty(
            Phase64OrganizationsInformationAdapter.OWNER_ASSIGN, parameters, scope, snapshot.copy(deadlines = emptyList())).reasonUid)
    }

    @Test fun stagedAccessOverlayMatchesCanonicalValidityAndKeepsGrantAndBindingFamiliesSeparate() {
        val principal = VisibilityPrincipalRef("NPC", "N2")
        val sharedValue = "GUARD"
        val grant = AccessAuthorityRecord("AUTHORITY", AccessOperation.GRANT, principal,
            AccessGrantKind.WORLD_RULE.name, sharedValue, null, null, 0, null, 1)
        val records = roleRecords() + grant
        val futureRoleRevoke = AccessAuthorityChange(AccessOperation.REVOKE_BINDING, "FUTURE_ROLE_REVOKE", "NPC", "N2",
            AccessBindingKind.ROLE.name, sharedValue, validFromOrder = 9)
        val futureGrantRevoke = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "FUTURE_GRANT_REVOKE", "NPC", "N2",
            AccessGrantKind.WORLD_RULE.name, sharedValue, validFromOrder = 9)
        assertEquals(records, Phase64OrganizationsInformationOwners.effectiveOverlay(records, recipient, 7,
            listOf(futureRoleRevoke, futureGrantRevoke)))
        val effective = Phase64OrganizationsInformationOwners.effectiveOverlay(records, recipient, 7,
            listOf(futureRoleRevoke.copy(validFromOrder = 8)))
        assertFalse(effective.any { it.operation == AccessOperation.UPSERT_BINDING && it.valueUid == sharedValue })
        assertTrue(effective.contains(grant))
        val wrongPrincipal = futureGrantRevoke.copy(principalUid = "N3", validFromOrder = 7)
        assertEquals(records, Phase64OrganizationsInformationOwners.effectiveOverlay(records, recipient, 7, listOf(wrongPrincipal)))
        val expired = AccessAuthorityChange(AccessOperation.GRANT, "EXPIRED", "NPC", "N2", AccessGrantKind.WORLD_RULE.name,
            sharedValue, validFromOrder = 1, validUntilOrder = 6)
        assertTrue(Phase64OrganizationsInformationOwners.effectiveOverlay(records, recipient, 7, listOf(expired)).contains(grant))
        val nextCommitGrant = grant.copy(recordUid = "NEXT_AUTHORITY", validFromOrder = 8, createdOrder = 8)
        val change = AccessAuthorityChange(AccessOperation.GRANT, nextCommitGrant.recordUid, "NPC", "N2",
            nextCommitGrant.kindUid, nextCommitGrant.valueUid, validFromOrder = 8)
        assertTrue(Phase64OrganizationsInformationOwners.effectiveOverlay(records, recipient, 7, listOf(change)).contains(nextCommitGrant))
        assertFalse(Phase64OrganizationsInformationOwners.effectiveOverlay(records, recipient, 7,
            listOf(futureRoleRevoke.copy(validFromOrder = 7))).any {
                it.operation == AccessOperation.UPSERT_BINDING && it.valueUid == sharedValue })
    }

    @Test fun pureDecisionUsesExistingNpcEngineAndDoesNotInvokeActionOnStaleOrUnselectedOption() {
        val brain = NpcBrainOwner.initialize("C1", actor, "seed")
        val decisionScope = NpcDecisionScope(scope.temporal, actor, brain.revision, WorldTimeTick(1000), 0, "P1")
        val option = NpcActionOption("LEGAL_OPTION", "WAIT", null, AcceptedActionTiming(ActionDuration(1000), "WAIT_RULE", 1),
            null, emptyList(), emptySet(), routine = true)
        val context = NpcDecisionContextEnvelope(decisionScope,
            NpcTrigger("T1", NpcTriggerKind.PLAN_BOUNDARY, WorldTimeTick(1000), NpcCauseRef(NpcCauseKind.ACCEPTED_ACTION, "A1")),
            brain, emptyList(), listOf(option), 8192)
        var calls = 0
        fun prepare(parameters: Map<String, String>, current: NpcDecisionScope = decisionScope) =
            Phase64OrganizationsInformationOwners.prepareDecision(parameters, scope, actor, context, current) { ctx, selected ->
                calls++
                assertEquals(option, selected.option)
                assertTrue(selected.authorization.matches(ctx.scope, ctx.contextFingerprint, selected.option))
                WorldConsequencePlan(changes = CapturedReads().ownedResult.changes)
            }
        assertEquals(BackgroundProcessStatus.COMPLETED, prepare(mapOf("option_uid" to option.uid)).status)
        assertEquals(1, calls)
        assertEquals("P64:INSTITUTIONAL_OPTION_UNAVAILABLE", prepare(mapOf("option_uid" to "FORGED_OPTION")).reasonUid)
        assertEquals("P62:STALE_SCOPE", prepare(mapOf("option_uid" to option.uid),
            decisionScope.copy(temporal = scope.temporal.copy(historyGenerationUid = "G2"))).reasonUid)
        assertEquals(1, calls)
    }

    private val institutionalDecisionParameters = mapOf("option_uid" to "LEGAL_REACTION", "_p64_effective_ms" to "1000")
    private fun institutionalDecisionInput(staged: List<PlayerDomainChangePayload> = emptyList()) =
        TemporalOwnerInput(scope.temporal, WorldTimeTick(0), WorldTimeTick(1000), emptyList(), emptyList(), null, staged)

    private fun institutionalDecisionProjection(input: TemporalOwnerInput, mechanical: Boolean = false,
        routine: Boolean = true): NpcContextResult.Ready {
        val genesis = NpcBrainOwner.initialize("C1", actor, "decision-seed")
        val canonical = if (!mechanical) genesis else genesis.copy(revision = 2, goals = listOf(
            NpcGoal("OWN_REACTION_GOAL", genesis.motivations.first().uid, "Odpocząć", NpcWeight(7000), NpcGoalLifecycle.ACTIVE,
                NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION, genesis.motivations.first().uid))))
        val brain = applyNpcBrainOverlay(canonical, input.scope, input.stagedChanges.filterIsInstance<NpcBrainChange>())
        val reads = object : NpcProjectionReadPort {
            override fun brain(audience: AudienceContext, purpose: PurposeContext, requestedActor: DomainRef,
                holder: KnowledgeHolderRef) = ProtectedReadResult.Allow(brain, DisclosureLevel.DISCLOSE_FULL, "OWN_CANONICAL_BRAIN")
            override fun knowledge(audience: AudienceContext, purpose: PurposeContext, holder: KnowledgeHolderRef,
                order: Long, limit: Int): ProtectedReadResult<List<NpcKnownRecord>> = ProtectedReadResult.NoData
        }
        val trigger = NpcTrigger("OWN_REACTION", NpcTriggerKind.SELF_REFLECTION, input.through,
            NpcCauseRef(NpcCauseKind.INTRINSIC_MOTIVATION, brain.motivations.first().uid))
        return NpcDecisionContextProjector(reads).project(NpcDecisionScope(input.scope, actor, brain.revision,
            input.through, 1, "P1"), trigger, brain.knowledgeHolder, ContextRuntimeProfile("TEST", 8192, 64, 64, 512)) { _, _ ->
            listOf(NpcActionOption("LEGAL_REACTION", "WAIT", actor,
                AcceptedActionTiming(ActionDuration(1000), "REGISTERED_WAIT", 1),
                if (mechanical) "OWN_REACTION_GOAL" else null, emptyList(), emptySet(), routine = routine,
                parameters = if (mechanical) mapOf("resource_uid" to "ENERGY") else emptyMap(),
                mechanicsOwnerUid = if (mechanical) "CORE" else null,
                mechanicalEffectKindUid = if (mechanical) "RESOURCE_DELTA" else null))
        } as NpcContextResult.Ready
    }

    private fun institutionalTimedAction(contexts: NpcPhysicalContextPort, resolver: MechanicsRuleResolver,
        cancelled: () -> Boolean = { false }):
        (NpcContextResult.Ready, NpcDecisionResult.Selected, TemporalOwnerInput) -> NpcActionPreparation {
        val application = NpcTimedActionApplication("INSTITUTIONAL_REACTION", contexts,
            AiModelRoutePort { _, _, _ -> error("A sealed institutional choice must not invoke another model") },
            { scope.temporal }, NpcMechanicalActionApplication(resolver) { scope.temporal })
        return { projected, selected, input -> application.prepareAuthorized(projected, selected, input, cancelled) }
    }

    @Test fun productionDecisionAdmitsExistingTimedActionWithOneCognitivePrefixAndDurablePendingState() {
        val unrelatedPending = NpcPendingAction(DomainRef("NPC", "N3"), "OTHER_PLAN", "OTHER_OPTION",
            WorldTimeTick(0), WorldTimeTick(6000), "OTHER_RULE", 1)
        val previous = TemporalOwnerState(NpcActionProcess.OWNER, 1, NpcActionProcess.encode(listOf(unrelatedPending)))
        val input = institutionalDecisionInput().copy(peerStates = mapOf(NpcActionProcess.OWNER to previous))
        var mechanicsCalls = 0
        val contexts = NpcPhysicalContextPort { _, captured, _ -> institutionalDecisionProjection(captured, mechanical = true) }
        val prepare = institutionalTimedAction(contexts, MechanicsRuleResolver { request, _ ->
            mechanicsCalls++
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid, request.nodeUid, "CORE", "RESOURCE_DELTA",
                mapOf("resource_uid" to "ENERGY", "magnitude" to "-2", "p60_core_duration_ms" to "1200",
                    "p60_core_timing_rule" to Phase60CombatTime.RULE, "p60_core_effect_at_ms" to "1200"),
                "PROOF", "INPUT", "OUTPUT"))
        })
        val callbacks = Phase64InstitutionDecisionCallbacks(input, contexts, { scope.temporal }, prepareAction = prepare)
        val parameters = institutionalDecisionParameters + ("_p64_process_uid" to "REACTION_PROCESS")
        val capture = callbacks.captureDecision(actor, parameters, scope, emptyList())!!
        val proposal = NpcDecisionProposal("REACTION", capture.context.contextFingerprint,
            listOf(NpcDecisionCandidate("LEGAL_REACTION")), goals = listOf(NpcGoalCandidate("SECOND_INTENTION",
                capture.context.brain.motivations.first().uid, "Przygotować się do służby", emptySet())))
        val result = Phase64OrganizationsInformationOwners.prepareDecision(parameters, scope, actor, capture.context,
            capture.currentScope, proposal, prepareSelectedAction = callbacks::prepareSelectedAction)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        val changes = result.changes.filterIsInstance<NpcBrainChange>()
        assertEquals(listOf(2L, 3L), changes.map { it.expectedVersion })
        assertTrue(validNpcBrainChains(changes))
        assertEquals(1, mechanicsCalls)
        assertTrue(result.effects.isEmpty()); assertTrue(result.deadlineAdds.isEmpty())
        val delegation = result.ownerDelegations.single()
        assertEquals("REACTION_PROCESS", delegation.sourceUid)
        assertEquals(TemporalOwnerDelegation.fingerprint(previous), delegation.expectedStateFingerprint)
        val admitted = NpcActionProcess.decode(delegation.proposed)
        assertEquals(2, admitted.size)
        assertTrue(admitted.contains(unrelatedPending))
        val pending = admitted.single { it.actor == actor }
        assertEquals(actor, pending.actor)
        assertEquals(WorldTimeTick(1000), pending.startedAt)
        assertEquals(WorldTimeTick(2200), pending.due)
        assertEquals(listOf(WorldProcessDeadline(pending.deadlineUid, NpcActionProcess.OWNER, pending.due)), delegation.deadlines)
        val plan = NpcBrainCodec.decode(changes.last().stateCanonical).plans.single()
        assertEquals(pending.planUid, plan.uid)
        assertEquals(NpcPlanLifecycle.RUNNING, plan.lifecycle)
        assertEquals(delegation.proposed, TemporalOwnerState(NpcActionProcess.OWNER, 1,
            NpcActionProcess.encode(NpcActionProcess.decode(delegation.proposed))))
    }

    @Test fun productionDecisionPreflightCancellationAndChangedProjectionHaveNoPendingAdmission() {
        val input = institutionalDecisionInput()
        val contexts = NpcPhysicalContextPort { _, captured, _ -> institutionalDecisionProjection(captured, mechanical = true) }
        var calls = 0
        val denied = institutionalTimedAction(contexts, MechanicsRuleResolver { _, _ ->
            calls++; MechanicsEffectResolution.Rejected("NO_ENERGY")
        })
        val parameters = institutionalDecisionParameters + ("_p64_process_uid" to "REACTION_PROCESS")
        fun result(prepare: (NpcContextResult.Ready, NpcDecisionResult.Selected, TemporalOwnerInput) -> NpcActionPreparation,
            project: NpcPhysicalContextPort = contexts): WorldConsequencePlan {
            val callbacks = Phase64InstitutionDecisionCallbacks(input, project, { scope.temporal }, prepareAction = prepare)
            val capture = callbacks.captureDecision(actor, parameters, scope, emptyList())!!
            return Phase64OrganizationsInformationOwners.prepareDecision(parameters, scope, actor, capture.context,
                capture.currentScope, prepareSelectedAction = callbacks::prepareSelectedAction)
        }
        val deniedResult = result(denied)
        assertEquals("P62:MECHANICS:NO_ENERGY", deniedResult.reasonUid)
        assertTrue(deniedResult.changes.isEmpty()); assertTrue(deniedResult.ownerDelegations.isEmpty())
        assertEquals(1, calls)
        val cancelled = result(institutionalTimedAction(contexts, MechanicsRuleResolver { _, _ -> error("Cancelled") }) { true })
        assertEquals("P62:CANCELLED", cancelled.reasonUid)
        assertTrue(cancelled.changes.isEmpty()); assertTrue(cancelled.ownerDelegations.isEmpty())
        var projections = 0
        val changing = NpcPhysicalContextPort { _, captured, _ ->
            projections++
            institutionalDecisionProjection(captured, mechanical = projections == 1)
        }
        val changed = result({ _, _, _ -> error("Changed projection must not reach admission") }, changing)
        assertEquals("P64:INSTITUTIONAL_DECISION_PROJECTION_CHANGED", changed.reasonUid)
        assertTrue(changed.changes.isEmpty()); assertTrue(changed.ownerDelegations.isEmpty())
    }

    @Test fun productionDecisionCallbacksReuseProtectedProjectionAndNeverCreateOrphanPlanOrDeadline() {
        val input = institutionalDecisionInput()
        var projectionCalls = 0
        val callbacks = Phase64InstitutionDecisionCallbacks(input, NpcPhysicalContextPort { requested, captured, pending ->
            projectionCalls++
            assertEquals(actor, requested); assertNull(pending)
            assertEquals(input, captured)
            institutionalDecisionProjection(captured)
        }, { scope.temporal })
        val capture = callbacks.captureDecision(actor, institutionalDecisionParameters, scope, emptyList())!!
        assertEquals(scope.temporal, capture.context.scope.temporal)
        assertEquals(capture.currentScope, capture.context.scope)
        assertNotNull(capture.context.projectionFingerprint)
        val result = Phase64OrganizationsInformationOwners.prepareDecision(institutionalDecisionParameters, scope, actor,
            capture.context, capture.currentScope, prepareSelectedAction = callbacks::prepareSelectedAction)
        assertEquals("P64:INSTITUTIONAL_ACTION_OWNER_STATE_REQUIRED", result.reasonUid)
        assertEquals(BackgroundProcessStatus.BLOCKED, result.status)
        assertTrue(result.changes.isEmpty()); assertTrue(result.effects.isEmpty())
        assertTrue(result.deadlineAdds.isEmpty()); assertTrue(result.deadlineRemovals.isEmpty())
        assertTrue(capture.context.brain.plans.isEmpty())
        assertEquals(1, projectionCalls)
    }

    @Test fun productionDecisionCaptureForwardsStagedBrainPrefixAndRejectsDroppedPrefixAndUnsupportedReads() {
        val input = institutionalDecisionInput()
        val context = institutionalDecisionProjection(input).context
        val goal = NpcGoalCandidate("OWN_LEGAL_GOAL", context.brain.motivations.first().uid, "Rozważyć odpoczynek", emptySet())
        val change = NpcBrainDynamics.considerGoals(context, listOf(goal))!!
        val stagedInput = institutionalDecisionInput(listOf(change))
        var seenPrefix: List<PlayerDomainChangePayload> = emptyList()
        val callbacks = Phase64InstitutionDecisionCallbacks(stagedInput, NpcPhysicalContextPort { _, captured, _ ->
            seenPrefix = captured.stagedChanges
            institutionalDecisionProjection(captured)
        }, { scope.temporal })
        val ready = callbacks.capture(actor, institutionalDecisionParameters, scope, listOf(change)) as Phase64InstitutionDecisionProjection.Ready
        assertEquals(listOf(change), seenPrefix)
        assertEquals(context.brain.revision + 1, ready.capture.context.brain.revision)
        assertEquals("OWN_LEGAL_GOAL", ready.capture.context.brain.goals.single().uid)
        val ignoresOverlay = Phase64InstitutionDecisionCallbacks(stagedInput,
            NpcPhysicalContextPort { _, _, _ -> institutionalDecisionProjection(input) }, { scope.temporal })
        assertEquals("P64:INSTITUTIONAL_STAGED_BRAIN_BINDING", (ignoresOverlay.capture(actor,
            institutionalDecisionParameters, scope, listOf(change)) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        assertEquals("P64:INSTITUTIONAL_STAGED_PREFIX_CHANGED", (callbacks.capture(actor,
            institutionalDecisionParameters, scope, emptyList()) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        val changedAccess = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "REVOCATION", actor.kindUid,
            actor.uid, AccessGrantKind.WORLD_RULE.name, "DECISION_POLICY", "ORGANIZATION", "O1", 7)
        assertEquals("P64:INSTITUTIONAL_STAGED_PROJECTION_UNAVAILABLE", (callbacks.capture(actor,
            institutionalDecisionParameters, scope, listOf(change, changedAccess)) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
    }

    @Test fun productionDecisionCallbacksRejectStaleGenerationTimeDeniedContextAndChangedSelection() {
        val input = institutionalDecisionInput()
        var current = scope.temporal
        val callbacks = Phase64InstitutionDecisionCallbacks(input,
            NpcPhysicalContextPort { _, captured, _ -> institutionalDecisionProjection(captured) }, { current })
        fun unavailable(parameters: Map<String, String> = institutionalDecisionParameters,
            requestedScope: BackgroundProcessEvaluationScope = scope) =
            (callbacks.capture(actor, parameters, requestedScope, emptyList()) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid
        assertEquals("P64:INSTITUTIONAL_DECISION_TIME", unavailable(institutionalDecisionParameters + ("_p64_effective_ms" to "999")))
        assertEquals("P64:INSTITUTIONAL_DECISION_SCOPE", unavailable(requestedScope = scope.copy(
            temporal = scope.temporal.copy(historyGenerationUid = "OLD"))))
        val denied = Phase64InstitutionDecisionCallbacks(input,
            NpcPhysicalContextPort { _, _, _ -> NpcContextResult.Unavailable("P62:KNOWLEDGE_DENIED") }, { current })
        assertEquals("P62:KNOWLEDGE_DENIED", (denied.capture(actor, institutionalDecisionParameters, scope,
            emptyList()) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        val context = callbacks.captureDecision(actor, institutionalDecisionParameters, scope, emptyList())!!.context
        val selected = NpcDecisionEngine().select(context, null, context.scope) as NpcDecisionResult.Selected
        val forged = selected.copy(option = selected.option.copy(parameters = mapOf("unapproved" to "true")))
        assertEquals("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING", callbacks.prepareSelectedAction(context, forged).reasonUid)
        current = current.copy(historyGenerationUid = "G2")
        assertEquals("P64:INSTITUTIONAL_DECISION_SCOPE", unavailable())
        assertEquals("P64:INSTITUTIONAL_DECISION_SCOPE", callbacks.prepareSelectedAction(context, selected).reasonUid)
    }

    @Test fun productionNonRoutineDecisionReusesOneProtectedSelectionAndExistingTimedAdmission() {
        val input = institutionalDecisionInput()
        var projections = 0
        val contexts = NpcPhysicalContextPort { _, captured, _ ->
            projections++
            institutionalDecisionProjection(captured, mechanical = true, routine = false)
        }
        var selections = 0
        var mechanicsCalls = 0
        val callbacks = Phase64InstitutionDecisionCallbacks(input, contexts, { scope.temporal }, selectDecision = { ready ->
            selections++
            assertFalse(ready.context.options.single().routine)
            assertEquals("P62:DECISION_PROVIDER_REQUIRED", (NpcDecisionEngine().select(ready.context, null,
                ready.context.scope) as NpcDecisionResult.Unavailable).reasonUid)
            val proposal = NpcDecisionProposal("PROTECTED_REACTION", ready.context.contextFingerprint,
                listOf(NpcDecisionCandidate("LEGAL_REACTION")), goals = listOf(NpcGoalCandidate("SECOND_INTENTION",
                    ready.context.brain.motivations.first().uid, "Przygotować się do służby", emptySet())))
            NpcDecisionEngine().select(ready.context, proposal, ready.context.scope)
        }, prepareAction = institutionalTimedAction(contexts, MechanicsRuleResolver { request, _ ->
            mechanicsCalls++
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid, request.nodeUid, "CORE", "RESOURCE_DELTA",
                mapOf("resource_uid" to "ENERGY", "magnitude" to "-1"), "PROOF", "INPUT", "OUTPUT"))
        }))
        val parameters = institutionalDecisionParameters + ("_p64_process_uid" to "REACTION_PROCESS")
        val capture = callbacks.captureDecision(actor, parameters, scope, emptyList())!!
        assertNotNull(capture.selected)
        assertNull(capture.proposal)
        assertEquals(2, projections)
        val result = Phase64InstitutionDecisionOwner.prepare(parameters, scope, actor, capture, callbacks::prepareSelectedAction)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertEquals(1, selections); assertEquals(1, mechanicsCalls); assertEquals(3, projections)
        val changes = result.changes.filterIsInstance<NpcBrainChange>()
        assertEquals(listOf(2L, 3L), changes.map { it.expectedVersion })
        assertTrue(validNpcBrainChains(changes))
        assertEquals(1, result.ownerDelegations.size)
        val pending = NpcActionProcess.decode(result.ownerDelegations.single().proposed).single()
        assertEquals("LEGAL_REACTION", pending.optionUid)
        assertEquals(WorldTimeTick(2000), pending.due)
        assertTrue(result.effects.isEmpty()); assertTrue(result.deadlineAdds.isEmpty())
        assertTrue(capture.selected!!.authorization.decisionUid in result.sourceUids)
    }

    @Test fun sealedInstitutionalSelectionRequiresExactCurrentScopeContextAndOptionAndNeverForcesFixedOption() {
        val context = institutionalDecisionProjection(institutionalDecisionInput(), routine = false).context
        val selected = NpcDecisionEngine().select(context, NpcDecisionProposal("MODEL", context.contextFingerprint,
            listOf(NpcDecisionCandidate("LEGAL_REACTION"))), context.scope) as NpcDecisionResult.Selected
        val capture = Phase64InstitutionDecisionCapture(context, context.scope, selected = selected)
        var admissions = 0
        fun prepare(captured: Phase64InstitutionDecisionCapture, parameters: Map<String, String> = institutionalDecisionParameters) =
            Phase64InstitutionDecisionOwner.prepare(parameters, scope, actor, captured) { _, _ ->
                admissions++
                backgroundBlocked("P64:TEST_ACTION_DENIED")
            }
        assertEquals("P62:STALE_SCOPE", prepare(capture.copy(currentScope = context.scope.copy(observationOrdinal = 2))).reasonUid)
        val other = institutionalDecisionProjection(institutionalDecisionInput(), routine = true).context
        assertEquals("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING", prepare(capture.copy(context = other)).reasonUid)
        assertEquals("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING", prepare(capture.copy(selected = selected.copy(
            option = selected.option.copy(parameters = mapOf("forged" to "true"))))).reasonUid)
        assertEquals("P64:INSTITUTIONAL_OPTION_UNAVAILABLE", prepare(capture,
            institutionalDecisionParameters + ("option_uid" to "UNSEEN_OPTION")).reasonUid)
        val options = listOf(selected.option, selected.option.copy(uid = "OTHER_LEGAL_OPTION"))
        val twoChoices = NpcDecisionContextEnvelope(context.scope, context.trigger, context.brain, context.records,
            options, context.maximumInputUnits, context.projectionFingerprint)
        val otherSelected = NpcDecisionEngine().select(twoChoices, NpcDecisionProposal("MODEL", twoChoices.contextFingerprint,
            listOf(NpcDecisionCandidate("OTHER_LEGAL_OPTION"))), twoChoices.scope) as NpcDecisionResult.Selected
        assertEquals("P64:INSTITUTIONAL_OPTION_NOT_SELECTED", prepare(Phase64InstitutionDecisionCapture(twoChoices,
            twoChoices.scope, selected = otherSelected)).reasonUid)
        assertEquals(0, admissions)
        val denied = prepare(capture)
        assertEquals("P64:TEST_ACTION_DENIED", denied.reasonUid)
        assertTrue(denied.changes.isEmpty()); assertTrue(denied.ownerDelegations.isEmpty())
        assertEquals(1, admissions)
    }

    @Test fun productionSelectionUnavailableOrReflectedRemainsTypedAndCannotAdmitAnyAction() {
        val input = institutionalDecisionInput()
        val context = institutionalDecisionProjection(input, routine = false).context
        val reflected = NpcBrainDynamics.considerGoals(context, listOf(NpcGoalCandidate("INTENTION_ONLY",
            context.brain.motivations.first().uid, "Rozważyć odpoczynek", emptySet())))!!
        listOf(NpcDecisionResult.Unavailable("P62:MODEL_UNAVAILABLE") to "P62:MODEL_UNAVAILABLE",
            NpcDecisionResult.Reflected(listOf(reflected)) to "P64:INSTITUTIONAL_ACTION_NOT_SELECTED").forEach { (decision, reason) ->
            var selections = 0
            val callbacks = Phase64InstitutionDecisionCallbacks(input,
                NpcPhysicalContextPort { _, captured, _ -> institutionalDecisionProjection(captured, routine = false) },
                { scope.temporal }, selectDecision = { selections++; decision },
                prepareAction = { _, _, _ -> error("No action selected") })
            val unavailable = callbacks.capture(actor, institutionalDecisionParameters, scope,
                emptyList()) as Phase64InstitutionDecisionProjection.Unavailable
            assertEquals(reason, unavailable.reasonUid)
            assertEquals(1, selections)
            val selected = NpcDecisionEngine().select(context, NpcDecisionProposal("LATE_FORGED_SELECTION",
                context.contextFingerprint, listOf(NpcDecisionCandidate("LEGAL_REACTION"))), context.scope) as NpcDecisionResult.Selected
            assertEquals("P64:INSTITUTIONAL_DECISION_NOT_CAPTURED", callbacks.prepareSelectedAction(context, selected).reasonUid)
        }
    }

    @Test fun productionSelectionRechecksScopeProjectionAndStagedPrefixAfterProviderReturns() {
        val input = institutionalDecisionInput()
        val contexts = NpcPhysicalContextPort { _, captured, _ -> institutionalDecisionProjection(captured, routine = false) }
        fun selected(ready: NpcContextResult.Ready) = NpcDecisionEngine().select(ready.context,
            NpcDecisionProposal("MODEL", ready.context.contextFingerprint, listOf(NpcDecisionCandidate("LEGAL_REACTION"))),
            ready.context.scope)
        var current = scope.temporal
        val stale = Phase64InstitutionDecisionCallbacks(input, contexts, { current }, selectDecision = { ready ->
            selected(ready).also { current = current.copy(historyGenerationUid = "G2") }
        })
        assertEquals("P64:INSTITUTIONAL_DECISION_SCOPE", (stale.capture(actor, institutionalDecisionParameters, scope,
            emptyList()) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        var projections = 0
        val changing = Phase64InstitutionDecisionCallbacks(input, NpcPhysicalContextPort { _, captured, _ ->
            projections++
            institutionalDecisionProjection(captured, routine = projections > 1)
        }, { scope.temporal }, selectDecision = ::selected)
        assertEquals("P64:INSTITUTIONAL_DECISION_PROJECTION_CHANGED", (changing.capture(actor, institutionalDecisionParameters,
            scope, emptyList()) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        val staged = mutableListOf<PlayerDomainChangePayload>()
        val changedPrefix = Phase64InstitutionDecisionCallbacks(input, contexts, { scope.temporal }, selectDecision = { ready ->
            selected(ready).also { staged.add(ResourceChange(actor, "ENERGY", ExactLongDelta.of(-1))) }
        })
        assertEquals("P64:INSTITUTIONAL_STAGED_PREFIX_CHANGED", (changedPrefix.capture(actor, institutionalDecisionParameters,
            scope, staged) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        val forged = Phase64InstitutionDecisionCallbacks(input, contexts, { scope.temporal }, selectDecision = { ready ->
            (selected(ready) as NpcDecisionResult.Selected).let { it.copy(option = it.option.copy(routine = true)) }
        })
        assertEquals("P64:INSTITUTIONAL_SELECTED_ACTION_BINDING", (forged.capture(actor, institutionalDecisionParameters,
            scope, emptyList()) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        var selections = 0
        val changedAccess = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "REVOCATION", actor.kindUid,
            actor.uid, AccessGrantKind.WORLD_RULE.name, "DECISION_POLICY", "ORGANIZATION", "O1", 8)
        val guarded = Phase64InstitutionDecisionCallbacks(input, contexts, { scope.temporal }, selectDecision = { ready ->
            selections++; selected(ready)
        })
        assertEquals("P64:INSTITUTIONAL_STAGED_PROJECTION_UNAVAILABLE", (guarded.capture(actor, institutionalDecisionParameters,
            scope, listOf(changedAccess)) as Phase64InstitutionDecisionProjection.Unavailable).reasonUid)
        assertEquals(0, selections)
    }

    @Test fun productionDecisionAllowsUnrelatedForegroundMovementCostsAndTimeWithoutDroppingThePrefix() {
        val player = DomainRef("PLAYER", "P1")
        val cost = ResourceChange(player, "STAMINA", ExactLongDelta.of(-1))
        val move = SpatialChange(player, 0, 0, DomainRef("PLACE", "OTHER_PLACE"))
        val clock = TemporalStateChange("C1", 1, WorldTimeTick(0), WorldTimeTick(1000), "[]")
        val foreignGrant = AccessAuthorityChange(AccessOperation.GRANT, "OTHER_GRANT", "NPC", "N3",
            AccessGrantKind.WORLD_RULE.name, "OTHER_POLICY", "ORGANIZATION", "O3", 8)
        val foreignKnowledge = evaluate().changes.single() as KnowledgeAcquisitionChange
        val effects = listOf(
            VerifiedMechanicsCommandEffect("PLAYER_COST", "NODE", "CORE", "RESOURCE_DELTA", player, -1,
                mapOf("resource_uid" to "STAMINA"), "PROOF", "INPUT", "OUTPUT"),
            VerifiedMechanicsCommandEffect("PLAYER_MOVE", "NODE", "CORE", "LOCATION_TRANSITION", player, 1,
                mapOf("destination_kind_uid" to "PLACE", "destination_uid" to "OTHER_PLACE"), "PROOF", "INPUT", "OUTPUT"))
        val input = institutionalDecisionInput(listOf(cost, move, clock, foreignGrant, foreignKnowledge)).copy(stagedEffects = effects)
        val contexts = NpcPhysicalContextPort { _, captured, _ ->
            assertEquals(input.stagedChanges, captured.stagedChanges)
            assertEquals(effects, captured.stagedEffects)
            institutionalDecisionProjection(captured, mechanical = true)
        }
        val prepare = institutionalTimedAction(contexts, MechanicsRuleResolver { request, resolution ->
            assertEquals(effects.map { it.effectUid }, resolution.stagedEffects.map { it.effectUid })
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid, request.nodeUid, "CORE", "RESOURCE_DELTA",
                mapOf("resource_uid" to "ENERGY", "magnitude" to "-1"), "PROOF", "INPUT", "OUTPUT"))
        })
        val callbacks = Phase64InstitutionDecisionCallbacks(input, contexts, { scope.temporal }, prepareAction = prepare)
        val parameters = institutionalDecisionParameters + ("_p64_process_uid" to "REACTION_PROCESS")
        val capture = callbacks.captureDecision(actor, parameters, scope, input.stagedChanges)!!
        val result = Phase64OrganizationsInformationOwners.prepareDecision(parameters, scope, actor, capture.context,
            capture.currentScope, prepareSelectedAction = callbacks::prepareSelectedAction)
        assertEquals(BackgroundProcessStatus.COMPLETED, result.status)
        assertEquals(1, result.ownerDelegations.size)
        assertTrue(result.effects.isEmpty())
        assertEquals(WorldTimeTick(2000), NpcActionProcess.decode(result.ownerDelegations.single().proposed).single().due)
    }

    @Test fun productionDecisionStillRefusesChangedOwnMechanicsKnowledgeAndTargetPrerequisites() {
        val input = institutionalDecisionInput()
        val context = institutionalDecisionProjection(input, mechanical = true).context
        val ownCost = ResourceChange(actor, "ENERGY", ExactLongDelta.of(-1))
        val ownMove = SpatialChange(actor, 1)
        val message = evaluate().changes.single() as KnowledgeAcquisitionChange
        val ownKnowledge = message.copy(acquisition = message.acquisition.copy(holder = context.brain.knowledgeHolder))
        listOf(ownCost, ownMove, ownKnowledge).forEach { change ->
            assertFalse(Phase64InstitutionStagedProjection.supports(context, input.copy(stagedChanges = listOf(change))))
        }
        val record = NpcKnownRecord("TARGET_RECORD", KnowledgeEpistemicState.BELIEVED, "A known recipient.",
            "TARGET_ACQUISITION", 1, setOf(recipient), scope.temporal.baseCommitOrder)
        val targeted = NpcDecisionContextEnvelope(context.scope, context.trigger, context.brain, listOf(record),
            listOf(context.options.single().copy(target = recipient, supportingRecordUids = setOf(record.uid))), 8192)
        assertFalse(Phase64InstitutionStagedProjection.supports(targeted,
            input.copy(stagedChanges = listOf(SpatialChange(recipient, 1)))))
        val lateGrant = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "LATE", actor.kindUid, actor.uid,
            AccessGrantKind.WORLD_RULE.name, "DECISION_POLICY", "ORGANIZATION", "O1", 9)
        assertTrue(Phase64InstitutionStagedProjection.supports(context, input.copy(stagedChanges = listOf(lateGrant))))
        assertFalse(Phase64InstitutionStagedProjection.supports(context, input.copy(stagedChanges = listOf(
            lateGrant.copy(validFromOrder = 8)))))
    }

    @Test fun scopedStagedGuardExpandsAreaMembersAndRejectsUnknownEffectsInsteadOfIgnoringThem() {
        val input = institutionalDecisionInput()
        val context = institutionalDecisionProjection(input).context
        val player = DomainRef("PLAYER", "P1")
        val mixed = VerifiedMechanicsCommandEffect("AREA", "NODE", "CORE", "RESOURCE_DELTA", player, -1, mapOf(
            "resource_uid" to "STAMINA", "area_target_count" to "2",
            "area_target_0_kind_uid" to player.kindUid, "area_target_0_uid" to player.uid,
            "area_target_0_magnitude" to "-1", "area_target_0_effect_kind_uid" to "RESOURCE_DELTA",
            "area_target_1_kind_uid" to actor.kindUid, "area_target_1_uid" to actor.uid,
            "area_target_1_magnitude" to "-1", "area_target_1_effect_kind_uid" to "RESOURCE_DELTA"), "PROOF", "INPUT", "OUTPUT")
        assertFalse(Phase64InstitutionStagedProjection.supports(context, input.copy(stagedEffects = listOf(mixed))))
        val unknown = mixed.copy(effectKindUid = "UNKNOWN_OWNER_EFFECT", canonicalPayload = emptyMap())
        assertFalse(Phase64InstitutionStagedProjection.supports(context, input.copy(stagedEffects = listOf(unknown))))
    }

    @Test fun refusedInstitutionalActionAdmissionCannotCommitSelectedCognitiveChanges() {
        val context = institutionalDecisionProjection(institutionalDecisionInput()).context
        val goal = NpcGoalCandidate("INTENTION_ONLY", context.brain.motivations.first().uid, "Rozważyć odpoczynek", emptySet())
        val proposal = NpcDecisionProposal("REACTION", context.contextFingerprint,
            listOf(NpcDecisionCandidate("LEGAL_REACTION")), goals = listOf(goal))
        val selected = NpcDecisionEngine().select(context, proposal, context.scope) as NpcDecisionResult.Selected
        assertTrue(selected.brainChanges.isNotEmpty())
        val result = Phase64OrganizationsInformationOwners.prepareDecision(institutionalDecisionParameters, scope, actor,
            context, context.scope, proposal, prepareSelectedAction = { _, _ ->
                backgroundBlocked("P64:INSTITUTIONAL_ACTION_OWNER_STATE_REQUIRED")
            })
        assertEquals("P64:INSTITUTIONAL_ACTION_OWNER_STATE_REQUIRED", result.reasonUid)
        assertTrue(result.changes.isEmpty()); assertTrue(result.effects.isEmpty()); assertTrue(result.deadlineAdds.isEmpty())
    }

    private val registeredEspionageParameters = mapOf("carrier_kind" to "REPORT", "carrier_uid" to "PRIVATE_REPORT",
        "carrier_rule_uid" to "EXISTING_CARRIER_READING", "carrier_rule_version" to "3",
        "espionage_policy_uid" to "COVERT_RULE", "recipient_kind" to "NPC", "recipient_uid" to "N2",
        "_p64_logical_event_uid" to "ESPIONAGE_EVENT", "message_text" to "To nie jest treść nośnika.")

    private fun registeredEspionageSnapshot(): Phase64InstitutionCarrierSnapshot {
        val carrier = DomainRef("REPORT", "PRIVATE_REPORT")
        val claim = KnowledgeClaim("CARRIER_CLAIM", "LOCATION", "BRIDGE", "OPEN", "false",
            domainUid = KnowledgeDomains.MILITARY_INTELLIGENCE)
        val activity = NpcActivityContract("READ", "EXISTING_CARRIER_READING", 3, ActionDuration(1000), "READ_EFFORT",
            reading = NpcReadingRule(carrier, "COVERT_RULE", claim))
        val principal = VisibilityPrincipalRef(actor.kindUid, actor.uid)
        val policy = AccessAuthorityRecord("CARRIER_POLICY", AccessOperation.GRANT, principal, AccessGrantKind.EXPLICIT.name,
            "COVERT_RULE", carrier.kindUid, carrier.uid, 0, null, 1)
        val stages = CarrierAccessStage.entries.map { stage -> AccessAuthorityRecord("CARRIER_STAGE_${stage.name}",
            AccessOperation.SET_CARRIER_ACCESS, principal, AccessGrantKind.EXPLICIT.name, stage.name,
            carrier.kindUid, carrier.uid, 0, null, 1) }
        return Phase64InstitutionCarrierSnapshot(scope.temporal, scope.temporal.baseCommitOrder,
            setOf(actor, recipient, carrier), activity, listOf(policy) + stages)
    }

    @Test fun nonPossessedEspionageUsesOnlyRegisteredContentAndExactExistingPhase38CarrierStages() {
        val snapshot = registeredEspionageSnapshot()
        val ready = Phase64InstitutionCarrierOwner.capture(actor, registeredEspionageParameters, scope,
            snapshot) as Phase64InstitutionCarrierProjection.Ready
        val captured = ready.capture
        assertTrue(captured.access.accessible)
        assertEquals(CarrierAccessStage.entries.toSet(), captured.access.resolvedStages)
        val path = captured.access.path!!
        assertEquals("P38:REGISTERED_CARRIER_STAGES_V1", path.mechanismUid)
        assertFalse(path.worldRulePermitsAccess)
        assertEquals(VisibilityPrincipalRef(actor.kindUid, actor.uid), path.principal)
        assertEquals(InformationCarrierRef("C1", "REPORT", "PRIVATE_REPORT"), path.carrier)
        assertEquals(snapshot.activity!!.reading!!.claim, captured.carrierClaim)
        assertNull(captured.sourceAcquisition)
        val result = Phase64OrganizationsInformationOwners.prepareEspionage(registeredEspionageParameters,
            scope, actor, captured.access, captured.carrierClaim, captured.recipientHolder)
        val acquired = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(KnowledgeEpistemicState.BELIEVED, acquired.acquisition.epistemicState)
        assertEquals("false", acquired.claim.valueCanonical)
        assertEquals(KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "N2", "C1"), acquired.acquisition.holder)
        assertEquals(path.evidenceUid, acquired.evidence.single().sourceRef!!.entityUid)
        assertTrue(result.effects.isEmpty())
    }

    @Test fun nonPossessedCarrierCaptureRejectsMissingOwnersWrongPrincipalAndFutureOrExpiredStageAuthority() {
        val snapshot = registeredEspionageSnapshot()
        fun reason(captured: Phase64InstitutionCarrierSnapshot = snapshot,
            parameters: Map<String, String> = registeredEspionageParameters,
            staged: List<PlayerDomainChangePayload> = emptyList()) =
            (Phase64InstitutionCarrierOwner.capture(actor, parameters, scope, captured,
                staged) as Phase64InstitutionCarrierProjection.Unavailable).reasonUid
        assertEquals("P64:ESPIONAGE_STALE_CARRIER_CAPTURE", reason(snapshot.copy(currentScope =
            scope.temporal.copy(historyGenerationUid = "OLD"))))
        assertEquals("P64:ESPIONAGE_REGISTERED_CARRIER_UNAVAILABLE", reason(snapshot.copy(activity = null)))
        assertEquals("P64:ESPIONAGE_REGISTERED_CARRIER_BINDING", reason(parameters =
            registeredEspionageParameters + ("carrier_uid" to "OTHER_REPORT")))
        assertEquals("P64:ESPIONAGE_REGISTERED_CARRIER_BINDING", reason(parameters =
            registeredEspionageParameters + ("carrier_rule_version" to "4")))
        assertEquals("P64:ESPIONAGE_CARRIER_REFERENCE_UNAVAILABLE", reason(snapshot.copy(canonicalReferences = setOf(actor, recipient))))
        assertEquals("P64:ESPIONAGE_CARRIER_PRINCIPAL_MISMATCH", reason(snapshot.copy(authority = snapshot.authority.map {
            it.copy(principal = VisibilityPrincipalRef("NPC", "OTHER_READER")) })))
        assertEquals("P64:ESPIONAGE_CARRIER_POLICY_GRANT_REQUIRED", reason(snapshot.copy(authority = snapshot.authority.drop(1))))
        assertEquals("P64:ESPIONAGE_CARRIER_STAGE_REQUIRED", reason(snapshot.copy(authority = snapshot.authority.filter {
            it.operation == AccessOperation.GRANT || it.valueUid == CarrierAccessStage.COMPREHENDED.name })))
        listOf(snapshot.authority.map { if (it.valueUid == CarrierAccessStage.COMPREHENDED.name) it.copy(createdOrder = 8) else it },
            snapshot.authority.map { if (it.valueUid == CarrierAccessStage.COMPREHENDED.name) it.copy(validFromOrder = 8) else it },
            snapshot.authority.map { if (it.valueUid == CarrierAccessStage.COMPREHENDED.name) it.copy(validUntilOrder = 7) else it }
        ).forEach { records -> assertEquals("P64:ESPIONAGE_CARRIER_STAGE_REQUIRED", reason(snapshot.copy(authority = records))) }
        val revoke = AccessAuthorityChange(AccessOperation.REVOKE_GRANT, "CARRIER_STAGE_REVOKE", actor.kindUid, actor.uid,
            AccessGrantKind.EXPLICIT.name, CarrierAccessStage.COMPREHENDED.name, "REPORT", "PRIVATE_REPORT", 8)
        assertEquals("P64:ESPIONAGE_CARRIER_STAGE_REQUIRED", reason(staged = listOf(revoke)))
        assertTrue(Phase64InstitutionCarrierOwner.capture(actor, registeredEspionageParameters, scope, snapshot,
            listOf(revoke.copy(validFromOrder = 9))) is Phase64InstitutionCarrierProjection.Ready)
        assertEquals("P64:ESPIONAGE_CARRIER_POLICY_GRANT_REQUIRED", reason(staged = listOf(revoke.copy(valueUid = "COVERT_RULE"))))
    }

    @Test fun pureEspionageUsesExactSealedCarrierPathAndNeverRawMessageText() {
        val carrier = InformationCarrierRef("C1", "REPORT", "PRIVATE_REPORT")
        val path = AccessPath.issue("C1", VisibilityPrincipalRef("NPC", "N1"), carrier, "COVERT_RULE", "E1", true,
            setOf(CarrierAccessStage.COMPREHENDED))
        val access = EffectiveAccessDecision.granted("AUTHORIZED", path, setOf(CarrierAccessStage.COMPREHENDED))
        val parameters = mapOf("carrier_kind" to "REPORT", "carrier_uid" to "PRIVATE_REPORT", "recipient_kind" to "NPC",
            "recipient_uid" to "N2", "_p64_logical_event_uid" to "EVENT1", "espionage_policy_uid" to "COVERT_RULE",
            "message_text" to "Ten tekst nie pochodzi z nośnika.")
        val claim = KnowledgeClaim("CLAIM1", "LOCATION", "BRIDGE", "OPEN", "false", domainUid = KnowledgeDomains.MILITARY_INTELLIGENCE)
        val holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, "N2", "C1")
        val result = Phase64OrganizationsInformationOwners.prepareEspionage(parameters, scope, actor, access, claim, holder)
        val acquired = result.changes.single() as KnowledgeAcquisitionChange
        assertEquals(claim, acquired.claim)
        assertEquals(KnowledgeEpistemicState.BELIEVED, acquired.acquisition.epistemicState)
        assertEquals(KnowledgeCarrierRef("REPORT", "PRIVATE_REPORT", "C1"), acquired.acquisition.carrier)
        assertEquals("E1", acquired.evidence.single().sourceRef?.entityUid)
        assertEquals("P64:ESPIONAGE_EXACT_ACCESS_SCOPE_REQUIRED", Phase64OrganizationsInformationOwners.prepareEspionage(
            parameters + ("carrier_uid" to "OTHER_REPORT"), scope, actor, access, claim, holder).reasonUid)
        assertEquals("P64:ESPIONAGE_EXACT_ACCESS_SCOPE_REQUIRED", Phase64OrganizationsInformationOwners.prepareEspionage(
            parameters, scope, actor, access, claim, holder.copy(holderUid = "OTHER_RECIPIENT")).reasonUid)
        val unreadPath = AccessPath.issue("C1", VisibilityPrincipalRef("NPC", "N1"), carrier, "COVERT_RULE", "UNREAD", true,
            setOf(CarrierAccessStage.REACHABLE, CarrierAccessStage.DECODED))
        val unsupportedComprehension = EffectiveAccessDecision.granted("AUTHORIZED", unreadPath,
            setOf(CarrierAccessStage.COMPREHENDED))
        val unread = Phase64OrganizationsInformationOwners.prepareEspionage(parameters, scope, actor,
            unsupportedComprehension, claim, holder)
        assertEquals("P64:ESPIONAGE_EXACT_ACCESS_SCOPE_REQUIRED", unread.reasonUid)
        assertTrue(unread.changes.isEmpty())
        val otherActorPath = AccessPath.issue("C1", VisibilityPrincipalRef("NPC", "N3"), carrier, "COVERT_RULE", "OTHER", true,
            setOf(CarrierAccessStage.COMPREHENDED))
        assertEquals("P64:ESPIONAGE_EXACT_ACCESS_SCOPE_REQUIRED", Phase64OrganizationsInformationOwners.prepareEspionage(
            parameters, scope, actor, EffectiveAccessDecision.granted("AUTHORIZED", otherActorPath,
                setOf(CarrierAccessStage.COMPREHENDED)), claim, holder).reasonUid)
    }

    @Test fun longDeliveredMessageIsExactAndAllChunksFitExistingMobileKnowledgeProjection() {
        val literal = "x".repeat(239) + "\uD83D\uDE00" + " dalsza wiadomość".repeat(100)
        val text = literal.take(2048)
        val result = evaluate(definition(parameters = delivery + ("message_text" to text)))
        val acquisitions = result.changes.filterIsInstance<KnowledgeAcquisitionChange>()
        assertTrue(acquisitions.size > 1)
        assertEquals(text, acquisitions.joinToString("") { it.claim.valueCanonical })
        assertTrue(acquisitions.all { it.claim.subjectUid.length + it.claim.predicateUid.length + it.claim.valueCanonical.length <= 500 })
        assertEquals(acquisitions.size, acquisitions.map { it.acquisition.acquisitionUid }.distinct().size)
        assertEquals(acquisitions.indices.toList(), acquisitions.map { it.claim.predicateUid.substringAfterLast(':').toInt() })
        assertTrue(acquisitions.none { it.claim.valueCanonical.last().isHighSurrogate() })
    }

    @Test fun allocationOwnerConservesMoneyAndExistingUniqueItemCustody() {
        val organization = DomainRef("ORGANIZATION", "O1")
        val parameters = mapOf("organization_uid" to "O1", "recipient_kind" to "NPC", "recipient_uid" to "N2",
            "resource_kind" to "FINANCIAL_ACCOUNT", "resource_uid" to "ORG_ACCOUNT", "quantity" to "100",
            "allocation_policy_uid" to "LEGAL_SUPPLY")
        val accountSnapshot = Phase64AllocationOwnerSnapshot(scope.temporal, true, organization, recipient, 100,
            sourceAccountUid = "ORG_ACCOUNT", recipientAccountUid = "N2_ACCOUNT", currencyUid = "RYO")
        val money = Phase64OrganizationsInformationOwners.prepareAllocation(parameters, scope, accountSnapshot)
        assertEquals(listOf(FinancialChange("ORG_ACCOUNT", "N2_ACCOUNT", 100, "RYO", "RPGOS-FIN-TYPE:TRANSFER")), money.changes)
        val itemParameters = parameters + mapOf("resource_kind" to "ITEM_INSTANCE", "resource_uid" to "MEDICINE1", "quantity" to "1")
        val itemSnapshot = Phase64AllocationOwnerSnapshot(scope.temporal, true, organization, recipient, 1, itemInstanceUid = "MEDICINE1")
        val item = Phase64OrganizationsInformationOwners.prepareAllocation(itemParameters, scope, itemSnapshot)
        assertEquals(listOf(InventoryChange(organization, "MEDICINE1", ExactLongDelta.of(-1)),
            InventoryChange(recipient, "MEDICINE1", ExactLongDelta.of(1))), item.changes)
        assertEquals(0L, item.changes.filterIsInstance<InventoryChange>().sumOf { it.quantityDelta.units })
        assertEquals("P64:ALLOCATION_RESOURCE_INSUFFICIENT", Phase64OrganizationsInformationOwners.prepareAllocation(
            parameters, scope, accountSnapshot.copy(availableUnits = 99)).reasonUid)
        assertEquals("P64:ALLOCATION_ACCOUNT_SCOPE", Phase64OrganizationsInformationOwners.prepareAllocation(
            parameters, scope, accountSnapshot.copy(sourceAccountUid = "OTHER_ACCOUNT")).reasonUid)
        assertEquals("P64:ALLOCATION_OWNER_SCOPE", Phase64OrganizationsInformationOwners.prepareAllocation(
            parameters, scope, accountSnapshot.copy(sourceOwner = DomainRef("ORGANIZATION", "OTHER_ORG"))).reasonUid)
    }
}
