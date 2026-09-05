package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class Phase55To57MemoryOwnersTest {
    @Test
    fun requiredAndSafetyRecordsArePinnedInWorkingMemory() {
        val scope = workingScope("C1")
        val context = budgetedContext("C1", listOf(
            contextSegment("REQ-REQUIRED", RequirementImportance.REQUIRED, semanticScore = 0.31),
            contextSegment("REQ-SAFETY", RequirementImportance.SAFETY, semanticScore = 0.82)
        ))
        val snapshot = WorkingMemoryOwner.materialize(scope, context)

        val byUid = snapshot.records.associateBy { it.canonicalRecordUid }
        assertEquals(2, snapshot.records.count { it.pinned })
        assertTrue(byUid.getValue("R-REQ-REQUIRED").pinned)
        assertTrue(byUid.getValue("R-REQ-SAFETY").pinned)
    }

    @Test
    fun nonRequiredRecordsAreNotPinnedInWorkingMemory() {
        val scope = workingScope("C1")
        val context = budgetedContext("C1", listOf(
            contextSegment("REQ-QUALITY", RequirementImportance.QUALITY),
            contextSegment("REQ-OPTIONAL", RequirementImportance.OPTIONAL)
        ))
        val snapshot = WorkingMemoryOwner.materialize(scope, context)
        snapshot.records.forEach { assertFalse(it.pinned) }
    }

    @Test
    fun workingMemoryOwnerRejectsCrossCampaignScopes() {
        val context = budgetedContext("C1", listOf(contextSegment("REQ", RequirementImportance.REQUIRED)))
        val failure = runCatching {
            WorkingMemoryOwner.materialize(context.candidate.plan.campaignUid.let { workingScope("C2") }, context)
        }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(failure?.message.orEmpty().contains("RPGOS-MEMORY:WORKING_MEMORY_CROSS_CAMPAIGN"))
    }

    @Test
    fun phase37ProjectorRequiresRecordedAcquisitionAndEvidence() = withDb { db ->
        init(db)
        commit(db, "ACQ-RECORDED", change("ACQ-RECORDED", holder("A"), claim("CLAIM-MEM", "MEM-VALUE")))
        val eventUid = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().createdEventUid!!
        val endOrder = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().createdOrder

        val generation = historyGeneration(db, "C1")
        val manifest = episodeManifest("C1", generation, listOf(eventUid), endOrder)
        val projector = Phase37EpisodeMemoryProjector(db, "C1")
        val derivation = projector.derive(manifest)

        assertEquals(1, derivation.holderMemories.size)
        assertEquals(1, derivation.semanticAssertions.size)
        assertEquals(1, derivation.holderMemories.single().acquisitionUids.size)
        assertEquals(1, derivation.semanticAssertions.single().supportingLeafRefs.size)
    }

    @Test
    fun phase37ProjectorIgnoresAcquisitionWithoutEvidence() = withDb { db ->
        init(db)
        commit(db, "ACQ-NOEVID", change("ACQ-NOEVID", holder("A"), claim("CLAIM-NOEVID", "MEM-VAL-NOEVID")))
        val eventUid = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().createdEventUid!!
        val acquisitionUid = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().acquisitionUid

        withAdministrativeMutationAuthority(db, "C1") {
            db.execSQL("DROP TRIGGER IF EXISTS rpgos_p37_evidence_no_delete")
            db.execSQL("DELETE FROM ${Phase37KnowledgeSchema.EVIDENCE} WHERE campaign_uid=? AND acquisition_uid=?",
                arrayOf("C1", acquisitionUid))
        }

        val generation = historyGeneration(db, "C1")
        val endOrder = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().createdOrder
        val manifest = episodeManifest("C1", generation, listOf(eventUid), endOrder)
        val projector = Phase37EpisodeMemoryProjector(db, "C1")
        val derivation = projector.derive(manifest)

        assertTrue(derivation.holderMemories.isEmpty())
        assertTrue(derivation.semanticAssertions.isEmpty())
    }

    @Test
    fun phase37ProjectorFiltersNonRecordedProvenanceAndRejectsCrossCampaignInput() = withDb { db ->
        init(db)
        commit(db, "ACQ-UNREC", change("ACQ-UNREC", holder("A"), claim("CLAIM-UNREC", "MEM-VAL-UNREC")))
        val acquisitionUid = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().acquisitionUid
        val eventUid = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().createdEventUid!!

        withAdministrativeMutationAuthority(db, "C1") {
            db.execSQL("DROP TRIGGER IF EXISTS rpgos_p37_acquisition_no_update")
            db.execSQL(
                "UPDATE ${Phase37KnowledgeSchema.ACQUISITIONS} SET provenance_status=? WHERE campaign_uid=? AND acquisition_uid=?",
                arrayOf(KnowledgeProvenanceStatus.VERIFIED_IMPORT.name, "C1", acquisitionUid)
            )
        }

        val generation = historyGeneration(db, "C1")
        val endOrder = KnowledgeStore(db, "C1").acquisitions(holder("A")).single().createdOrder
        val manifest = episodeManifest("C1", generation, listOf(eventUid), endOrder)
        val projector = Phase37EpisodeMemoryProjector(db, "C1")
        val filtered = projector.derive(manifest)
        assertTrue(filtered.holderMemories.isEmpty())
        assertTrue(filtered.semanticAssertions.isEmpty())

        val foreignManifest = episodeManifest("C2", generation, listOf("EVENT-OTHER"), 101L)
        val crossFailure = runCatching { Phase37EpisodeMemoryProjector(db, "C1").derive(foreignManifest) }.exceptionOrNull()
        assertNotNull(crossFailure)
        assertTrue(crossFailure?.message.orEmpty().contains("RPGOS-MEMORY:EPISODE_CROSS_CAMPAIGN"))
    }

    @Test
    fun laterPhase37StateCannotLeakIntoAnEarlierEpisode() = withDb { db ->
        init(db)
        val sharedClaim = claim("CLAIM-TEMPORAL", "TEMPORAL-VALUE")
        commit(db, "TEMPORAL-PAST", change("TEMPORAL-PAST", holder("A"), sharedClaim, state = KnowledgeEpistemicState.KNOWN))
        val pastAcquisition = KnowledgeStore(db, "C1").acquisitions(holder("A")).single()
        val pastEventUid = requireNotNull(pastAcquisition.createdEventUid)
        val pastOrder = pastAcquisition.createdOrder

        commit(db, "TEMPORAL-LATER", change("TEMPORAL-LATER", holder("A"), sharedClaim, state = KnowledgeEpistemicState.DISBELIEVED))

        val manifest = episodeManifest("C1", historyGeneration(db, "C1"), listOf(pastEventUid), pastOrder)
        val derivation = Phase37EpisodeMemoryProjector(db, "C1").derive(manifest)

        assertEquals(listOf(pastEventUid), derivation.holderMemories.single().rememberedEventUids)
        assertTrue("A later mutable state must not be projected into an earlier as-of episode", derivation.semanticAssertions.isEmpty())
    }

    private fun withDb(block: (SQLiteDatabase) -> Unit) {
        val dbFile = createTempFile("phase55-memory-owners", ".db")
        try {
            SQLiteDatabase.openOrCreateDatabase(dbFile, null).use(block)
        } finally {
            dbFile.delete()
        }
    }

    private fun init(db: SQLiteDatabase, vararg campaigns: String) {
        val resolved = campaigns.ifEmpty { arrayOf("C1") }
        resolved.forEach { GameplayRuntimeBootstrap.initialize(db, it) }
    }

    private fun historyGeneration(db: SQLiteDatabase, campaignUid: String): HistoryGenerationUid {
        Phase55To58MemorySchema.ensureReady(db, campaignUid)
        return HistoryGenerationStore(db, campaignUid).current()
    }

    private fun workingScope(campaign: String, audience: String = AudienceKinds.PLAYER): WorkingMemoryScope = WorkingMemoryScope(
        campaignUid = campaign,
        historyGenerationUid = HistoryGenerationUid("HGEN-$campaign-0"),
        audienceKindUid = audience,
        principalUid = "P1",
        purposeUid = VisibilityPurposeKinds.GAMEPLAY_NARRATION,
        sceneUid = null,
        asOfCommittedOrder = 1L,
        accessPolicyVersion = 1L
    )

    private fun budgetedContext(
        campaign: String,
        segments: List<CanonicalContextSegment>
    ): BudgetedCanonicalContext {
        val requirements = segments.map { it.requirement }
        val plan = CanonicalTurnPlan(
            planUid = "PLAN-$campaign",
            campaignUid = campaign,
            intent = IntentDocument(
                campaignUid = campaign,
                actor = CommandActorRef("PLAYER", "P1"),
                rawInput = "memory test",
                meaningState = MeaningState.UNDERSTOOD,
                nodes = emptyList(),
                provenance = IntentInterpretationProvenance(
                    IntentInterpretationSource.LEGACY_RULE,
                    "LEGACY",
                    "1",
                    "legacy:memory-test"
                )
            ),
            audience = AudienceContext(campaign, AudienceKinds.PLAYER),
            purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION),
            steps = listOf(
                CanonicalTurnPlanStep(
                    stepUid = "STEP-1",
                    nodeUid = "NODE-1",
                    capabilityUid = "CAP-1",
                    matchState = CapabilityMatchState.EXACT,
                    dependencyNodeUids = emptyList(),
                    requirements = requirements,
                    executionKind = CapabilityExecutionKind.READ_CONTEXT,
                    sideEffectClass = CapabilitySideEffectClass.NONE,
                    mechanicsOwnerUid = null
                )
            )
        )

        val core = SemanticCoreCapsule(
            campaignUid = campaign,
            planUid = plan.planUid,
            intentHash = plan.intent.canonicalFingerprint(),
            intentCanonicalPayload = plan.intent.canonicalPayload(),
            planSemanticPayload = CanonicalContextPayloadCodec.plan(plan),
            activeNodeUids = listOf("NODE-1"),
            capabilityUids = listOf("CAP-1"),
            dependencyEdges = emptyList(),
            hardDirectiveUids = emptyList()
        )
        val candidate = CanonicalContextCandidate(plan, core, segments)
        return BudgetedCanonicalContext(candidate, segments, emptyList(), 1, 1, 100_000, 1, true, emptyList())
    }

    private fun contextSegment(
        requirementUid: String,
        importance: RequirementImportance,
        semanticScore: Double = 0.5
    ) = CanonicalContextSegment(
        segmentUid = "SEGMENT-$requirementUid",
        requirement = plannedRequirement(requirementUid, importance),
        state = RetrievalState.VALUE,
        records = listOf(
            CanonicalContextRecord(
                record = RetrievalRecord("R-$requirementUid", mapOf("semantic_score" to semanticScore)),
                epistemicState = ContextEpistemicState.PROJECTED_FACT,
                projectionBoundaryUid = "BOUNDARY-$requirementUid",
                sourceRequirementUid = "REQ-$requirementUid"
            )
        ),
        complete = true,
        continuation = RetrievalContinuation.COMPLETE
    )

    private fun plannedRequirement(requirementUid: String, importance: RequirementImportance): PlannedRequirement {
        val audience = AudienceContext("C1", AudienceKinds.PLAYER)
        val purpose = PurposeContext("C1", VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val request = StructuredRetrievalRequest("REQ-$requirementUid", "C1", "WORLD", "READ", emptyMap(), 100, audience, purpose)
        val envelope = CapabilityEnvelope(
            "ENV-$requirementUid",
            "C1",
            "WORLD",
            "READ",
            emptySet(),
            maximumLimit = 100,
            audience = audience,
            purpose = purpose
        )
        return PlannedRequirement("REQ-$requirementUid", "NODE-1", importance, request, envelope)
    }

    private fun episodeManifest(campaign: String, generation: HistoryGenerationUid, eventUids: List<String>, endOrder: Long): EpisodeManifest {
        val leaves = eventUids.map { MemorySourceLeafRef("EVENT", it, 1L, 1L, "FP:$it") }
        val fingerprint = memoryLeafFingerprint(leaves)
        return EpisodeManifest(
            identity = MemoryArtifactIdentity(
                campaign,
                generation,
                "EPISODE:$campaign:${eventUids.joinToString(",")}",
                "REV-${fingerprint.take(16)}",
                MemoryArtifactKind.EPISODE_MANIFEST,
                leaves,
                fingerprint,
                "RPGOS-P56-PRIMARY-EPISODE",
                1,
                endOrder,
                1L,
                endOrder
            ),
            eventUids = eventUids,
            startOrder = 1L,
            endOrder = endOrder,
            participantRefs = emptyList(),
            locationRefs = emptyList(),
            segmentationRuleUid = "RPGOS-P56-PRIMARY-EPISODE",
            segmentationVersion = 1
        )
    }

    private fun holder(uid: String, campaign: String = "C1") = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, uid, campaign)

    private fun claim(uid: String, value: String, domain: String = KnowledgeDomains.INVESTIGATION) =
        KnowledgeClaim(uid, "TARGET", "X", "about", value, domainUid = domain)

    private fun quality(
        confidence: Double = .9,
        precision: Double = .8,
        completeness: Double = .8,
        reliability: Double = .8,
        corroboration: Int = 1,
        observed: Long? = 1L
    ) = KnowledgeQuality(confidence, precision, completeness, reliability, corroboration, observed)

    private fun change(
        suffix: String,
        holder: KnowledgeHolderRef,
        claim: KnowledgeClaim,
        method: String = KnowledgeAcquisitionMethods.DIRECT_OBSERVATION,
        state: KnowledgeEpistemicState = KnowledgeEpistemicState.KNOWN,
        quality: KnowledgeQuality = quality(),
        evidence: List<KnowledgeEvidenceSpec> = emptyList()
    ) = KnowledgeAcquisitionChange(
        claim,
        KnowledgeAcquisitionSpec(
            acquisitionUid = "ACQ-$suffix",
            holder = holder,
            methodUid = method,
            scope = KnowledgeScope.PERSONAL,
            epistemicState = state,
            quality = quality
        ),
        evidence
    )

    private fun commit(
        db: SQLiteDatabase,
        command: String,
        change: KnowledgeAcquisitionChange,
        campaign: String = "C1"
    ) =
        TurnTransactionBoundary.create(
            db,
            TurnTransactionIdentity(campaign, "TURN-$command", command, "TX-$command"),
            proposal(command, change, campaign)
        ).commit()

    private fun proposal(
        command: String,
        change: KnowledgeAcquisitionChange,
        campaign: String
    ): CanonicalCampaignMutationProposal {
        knowledgeByCommand[command] = change
        val actor = CommandActorRef("PLAYER", "P1")
        val cmd = PlayerCommand(
            commandUid = command,
            campaignUid = campaign,
            actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS,
            payload = TransferFundsCommandPayload("A", "B", 1, "CUR"),
            provenance = CommandProvenance("P37-TEST"),
            requestedEffectiveOrder = command.hashCode().toLong().let { if (it == Long.MIN_VALUE) 1L else kotlin.math.abs(it) + 1L }
        )
        val refs = LinkedHashSet<CampaignScopedDomainRef>()
        refs += CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        refs += CampaignScopedDomainRef(campaign, DomainRef(change.acquisition.holder.holderKindUid, change.acquisition.holder.holderUid))
        val context = PlayerResolutionContext.createUnboundGeneric(campaign, actor, refs)
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(KnowledgeComponent())))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class KnowledgeComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,
        TransferFundsCommandPayload::class,
        "P37-KNOWLEDGE-COMPONENT",
        "1"
    ) {
        override fun resolve(
            command: PlayerCommand<TransferFundsCommandPayload>,
            context: PlayerResolutionContext
        ): PlayerResolutionComponentOutcome {
            val knowledge = requireNotNull(knowledgeByCommand[command.commandUid])
            val changeUid = "CHANGE-${command.commandUid}"
            val holderRef = DomainRef(knowledge.acquisition.holder.holderKindUid, knowledge.acquisition.holder.holderUid)
            val actorRef = DomainRef(command.actor.actorKindUid, command.actor.actorUid)
            return PlayerResolutionComponentOutcome.Resolved(
                PlayerResolutionDraft.create(
                    changes = listOf(PlayerDomainChange.create(changeUid, PHASE37_KNOWLEDGE_CHANGE_KIND, knowledge)),
                    eventIntents = listOf(
                        PlayerEventIntent.create(
                            eventIntentUid = "EVENT-${command.commandUid}",
                            eventKindUid = PlayerEventIntentKinds.DOMAIN_EFFECT,
                            actorRef = actorRef,
                            targetRefs = listOf(holderRef),
                            causalChangeUids = listOf(changeUid),
                            payload = DomainEffectEventIntentPayload(holderRef, "RPGOS-EFFECT:KNOWLEDGE_ACQUISITION")
                        )
                    )
                )
            )
        }
    }

    companion object {
        private val knowledgeByCommand = mutableMapOf<String, KnowledgeAcquisitionChange>()
    }
}
