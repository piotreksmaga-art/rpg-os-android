package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real SQLite owner acceptance, not UI or model-quality acceptance. The fixture supplies an
 * acquired, visible opponent and chooses one Core-authorized option. Phase62 owns the durable
 * plan and completion; UniversalCombatEngine calculates the actual wound/proof. Only the
 * initial bodies, positions, active-player identity, genesis and rule registration use ADMIN.
 * The small snapshot adapter avoids bootstrapping an unrelated selected-save/world repository.
 */
@RunWith(AndroidJUnit4::class)
class Phase64CombatDeviceAcceptanceTest {
    private val campaign = "P64:COMBAT:DEVICE"
    private val player = DomainRef("PLAYER", "P64:COMBAT:PLAYER")
    private val npc = DomainRef("NPC", "P64:COMBAT:NPC")
    private val opponent = DomainRef("NPC", "P64:COMBAT:OPPONENT")
    private val binding = WorldPackRuleBinding("P64:COMBAT:PACK", "1")
    private val ability = CombatAbilityContract("ATTACK", resourceUid = "STAMINA", resourceCost = 2,
        targetKindUids = setOf("NPC"), effectKinds = listOf(UniversalMechanicalEffectKind.WOUND))
    private val contracts = CombatAbilityContractPort.registered(listOf(ability))
    private val rule = BackgroundProcessDefinition(Phase64CombatReceiptFactory.RULE_UID, Phase64CombatReceiptFactory.RULE_VERSION,
        "CONFLICT", "COMBAT", 60_000, parameters = mapOf(Phase64CombatReceiptFactory.OWNER_PARAMETER to NpcActionProcess.OWNER))

    private fun identity(uid: String) = TurnTransactionIdentity(campaign, "TURN:$uid", "CMD:$uid", "TX:$uid")
    private fun scope(db: SQLiteDatabase) = TemporalScope(campaign, HistoryGenerationStore(db, campaign).current().value,
        TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0, AuthoritativeStateDigest.compute(db))
    private fun body(db: SQLiteDatabase, ref: DomainRef) = requireNotNull(MechanicalActorStateStore(db, campaign).actor(ref))
    private fun brain(db: SQLiteDatabase) = requireNotNull(NpcBrainStore(db, campaign).read(npc))

    private fun setup(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        GameplayRuntimeBootstrap.initialize(db, campaign)
        withAdministrativeMutationAuthority(db, campaign) {
            listOf(player, npc, opponent).forEach { ref ->
                // A bounded advantage above the contest's random draw spread guarantees a
                // real positive wound without choosing a seed or replacing damage resolution.
                val attributes = if (ref == npc) mapOf("POWER" to 120L, "SKILL" to 100L, "DEFENCE" to 20L, "AGILITY" to 10L)
                    else mapOf("POWER" to 10L, "SKILL" to 5L, "DEFENCE" to 10L, "AGILITY" to 5L)
                MechanicalActorStateStore(db, campaign).materializeIfMissing(MechanicalActorSeed(ref,
                    if (ref == player) MechanicalActorKind.ACTIVE_PLAYER else MechanicalActorKind.NPC,
                    "DEVICE", "DEVICE-SEED", "DEVICE-BOOTSTRAP", attributes,
                    listOf(MechanicalResource("HEALTH", 1000, 1000), MechanicalResource("STAMINA", 20, 20)), setOf("ATTACK")))
                db.execSQL("INSERT INTO entity_positions VALUES(?,?,?,0,0,0)", arrayOf<Any>(ref.uid, "LOCATION:DEVICE:ARENA", if (ref == opponent) 1 else 0))
            }
            ActivePlayerStore(db, campaign).set(player.uid)
            val initial = NpcBrainOwner.initialize(campaign, npc, "DEVICE-SEED")
            val genesis = NpcBrainChange(campaign, npc, HistoryGenerationStore(db, campaign).current().value, 0, null,
                NpcBrainCodec.encode(initial), NpcBrainRules.GENESIS.uid, 1,
                listOf(NpcCauseRef(NpcCauseKind.GENESIS, "P61:GENESIS:${initial.seedFingerprint}")))
            NpcBrainStore(db, campaign).apply(identity("BOOTSTRAP"), "GENESIS", genesis)
            Phase64BackgroundStore(db, campaign).initializeNew(binding, listOf(rule))
        }
    }

    /** Controlled recognition is an input committed by the real Phase37 event/knowledge owner,
     * not a fake protected read, a running plan or an assertion that combat already occurred. */
    private class RecognitionInput(private val campaign: String, private val observerUid: String, private val opponentUid: String) :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,
            TransferFundsCommandPayload::class, "P64:COMBAT:RECOGNITION_INPUT", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val observer = DomainRef("NPC", observerUid)
            val holder = DomainRef(KnowledgeHolderKinds.CHARACTER, observerUid)
            val acquired = KnowledgeAcquisitionChange(
                KnowledgeClaim("DEVICE:OPPONENT", "NPC", opponentUid, "DEVICE:VISIBLE_OPPONENT", "Rozpoznany partner kontrolowanego starcia.",
                    domainUid = KnowledgeDomains.TACTICS),
                KnowledgeAcquisitionSpec("DEVICE:RECOGNITION", KnowledgeHolderRef(holder.kindUid, holder.uid, campaign),
                    KnowledgeAcquisitionMethods.DIRECT_OBSERVATION, KnowledgeScope.PERSONAL, KnowledgeEpistemicState.KNOWN,
                    KnowledgeQuality(1.0, 1.0, 1.0, 1.0, 1, command.requestedEffectiveOrder)))
            val change = PlayerDomainChange.create("RECOGNITION", PHASE37_KNOWLEDGE_CHANGE_KIND, acquired, "DEVICE:VISIBLE_RECOGNITION")
            val event = PlayerEventIntent.create("EVENT:RECOGNITION", PlayerEventIntentKinds.DOMAIN_EFFECT, observer,
                listOf(holder), listOf(change.changeUid), DomainEffectEventIntentPayload(holder, "RPGOS-EFFECT:KNOWLEDGE_ACQUISITION"))
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = listOf(change), eventIntents = listOf(event)))
        }
    }

    private fun recognize(db: SQLiteDatabase) {
        val actor = CommandActorRef(player.kindUid, player.uid)
        val command = PlayerCommand(commandUid = identity("RECOGNIZE").commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS, payload = TransferFundsCommandPayload("A", "B", 1, "CUR"),
            provenance = CommandProvenance("DEVICE:CONTROLLED_PERCEPTION_INPUT"), requestedEffectiveOrder = 1)
        val refs = listOf(player, npc, opponent, DomainRef(KnowledgeHolderKinds.CHARACTER, npc.uid),
            DomainRef("FINANCIAL_ACCOUNT", "A"), DomainRef("FINANCIAL_ACCOUNT", "B"), DomainRef("CURRENCY", "CUR"))
            .map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(RecognitionInput(campaign, npc.uid, opponent.uid))))
        val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.createUnboundGeneric(campaign, actor, refs))
        assertTrue(admission.toString(), admission is CampaignMutationAdmission.Accepted)
        assertTrue(TurnTransactionBoundary.create(db, identity("RECOGNIZE"), (admission as CampaignMutationAdmission.Accepted).proposal).commit()
            is TurnExecutionResult.Committed)
    }

    private fun project(db: SQLiteDatabase, input: TemporalOwnerInput, pending: NpcPendingAction?): NpcContextResult {
        val state = brain(db)
        val holder = state.knowledgeHolder
        val trusted = TrustedPrincipalContext(campaign, VisibilityPrincipalRef(npc.kindUid, npc.uid), AudienceKinds.WORLD_ACTOR,
            roleUids = emptySet(), organizationUids = emptySet(), clearanceUids = emptySet(), cognitionHolders = setOf(holder))
        val reads = ProtectedCampaignReadRepository.borrowedTrusted(db, campaign, { ActivePlayerStore(db, campaign).active() }, trusted)
        val cause = pending?.let { state.plans.single { plan -> plan.uid == it.planUid }.cause }
            ?: NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION, "DEVICE:RECOGNITION")
        return NpcDecisionContextProjector(RepositoryNpcProjection(reads)).project(
            NpcDecisionScope(input.scope, npc, state.revision, input.through, 0, player.uid),
            NpcTrigger("DEVICE:TRIGGER:${input.through.milliseconds}", if (pending == null) NpcTriggerKind.KNOWLEDGE_CHANGED else NpcTriggerKind.PLAN_BOUNDARY,
                input.through, cause), holder, ContextRuntimeProfile("DEVICE", 8192, 64, 64, 512)
        ) { current, records -> NpcMechanicalAffordances(contracts, NpcActivityContractPort.NONE).options(current, records, body(db, npc)) }
    }

    /** Thin snapshot seam only: exact canonical bodies/positions and a sealed Phase62 choice
     * enter the shared Phase50 engine. No magnitude, effect, proof or completed brain is seeded. */
    private fun resolver(db: SQLiteDatabase) = MechanicsRuleResolver { request, context ->
        val node = context.plan.intent.nodes.single()
        val auth = requireNotNull(context.npcAuthorization)
        check(auth.scope.actor == npc && auth.scope.activePlayerUid == ActivePlayerStore(db, campaign).requireActive().playerUid)
        check(auth.authorizesMechanics(scope(db), context.plan, node, request))
        check(request.mechanicsOwnerUid == "UNIVERSAL_COMBAT" && request.parameters["npc_ability_contract"] == npcCombatContractFingerprint(ability))
        val target = requireNotNull(request.targetProjectedRef)
        val participants = listOf(body(db, npc), body(db, target))
        val positions = participants.associate { actor -> actor.actor to db.rawQuery(
            "SELECT x_coord,y_coord FROM entity_positions WHERE entity_uid=?", arrayOf(actor.actor.uid)).use { cursor ->
            check(cursor.moveToFirst())
            CombatPosition.Exact((cursor.getDouble(0) * 1000).toLong(), (cursor.getDouble(1) * 1000).toLong())
        } }
        check(participants.map { it.locationRef }.distinct().size == 1 && participants.first().locationRef != null)
        val fingerprint = phase60Hash("${context.context.canonicalPayload()}|$participants|$positions")
        val intent = CombatIntent("P50:${request.effectUid}", campaign, npc, target, ability.abilityUid,
            VolitionalActionSource.NPC_DECISION_ENGINE, "DISABLE", context.plan.atOrder!!, auth)
        val snapshot = ImmutableCombatSnapshot("SNAPSHOT:$fingerprint", campaign, context.plan.atOrder!!,
            participants, emptyList(), emptyMap(), emptyMap(), fingerprint)
        val result = UniversalCombatEngine().resolve(UniversalCombatRequest(intent, snapshot, ability, CombatSpatialState(positions)))
        if (result is CombatResolution.Rejected) MechanicsEffectResolution.Rejected(result.reasonUid)
        else {
            result as CombatResolution.Resolved
            val effect = result.effects.single()
            val payload = effect.payload + Phase60CombatTime.metadata(DeterministicCombatScheduler().schedule(intent, snapshot)) + mapOf(
                "magnitude" to effect.magnitude.toString(), "target_kind_uid" to effect.target.kindUid, "target_uid" to effect.target.uid,
                "combat_proof_uid" to result.evidence.proofUid, "canonical_effect_kind_uid" to effect.kind.name)
            val input = result.evidence.inputFingerprint
            val output = result.evidence.outputFingerprint
            MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(request.effectUid, request.nodeUid, "UNIVERSAL_COMBAT", effect.kind.name,
                payload, "RPGOS-P50-PROOF:${phase60Hash("$input|$output")}", input, output))
        }
    }

    private fun evaluate(db: SQLiteDatabase, uid: String, input: TemporalOwnerInput, starting: Boolean): TemporalOwnerResult.Evaluated {
        val contexts = NpcPhysicalContextPort { actor, current, pending -> check(actor == npc); project(db, current, pending) }
        val app = NpcTimedActionApplication(identity(uid).commandUid, contexts,
            AiModelRoutePort { _, _, _ -> error("No model invocation is permitted in this acceptance test") }, { scope(db) },
            NpcMechanicalActionApplication(resolver(db), { scope(db) }, NpcTravelRoutePort.NONE, NpcTravelActorReadPort.NONE,
                settleCombatResourceCosts = true), interruptsForeground = { false })
        val port = object : NpcTimedActionPort {
            override fun prepare(actor: DomainRef, current: TemporalOwnerInput, cancelled: () -> Boolean): NpcActionPreparation {
                val ready = contexts.project(actor, current, null)
                assertTrue(ready.toString(), ready is NpcContextResult.Ready)
                ready as NpcContextResult.Ready
                val choice = NpcDecisionEngine().select(ready.context, NpcDecisionProposal("DEVICE:CHOICE", ready.context.contextFingerprint,
                    listOf(NpcDecisionCandidate(ready.context.options.single().uid))), ready.context.scope)
                assertTrue(choice.toString(), choice is NpcDecisionResult.Selected)
                return app.prepareAuthorized(ready, choice as NpcDecisionResult.Selected, current, cancelled)
            }
            override fun complete(action: NpcPendingAction, current: TemporalOwnerInput, cancelled: () -> Boolean) = app.complete(action, current, cancelled)
        }
        val extension = NpcActionProcess(input.scope, WorldTimeTick(0), player.uid, if (starting) listOf(npc) else emptyList(), port).extension()
        val required = extension.owners.single().owner.evaluate(input)
        assertTrue(required.toString(), required is TemporalOwnerResult.EvaluationRequired)
        val request = TemporalEvaluationRequest(NpcActionProcess.OWNER, input, (required as TemporalOwnerResult.EvaluationRequired).reasonUid)
        val response = requireNotNull(extension.evaluation).evaluate(request) { false }
        assertTrue(response.toString(), response is TemporalEvaluationResponse.Accepted)
        response as TemporalEvaluationResponse.Accepted
        assertEquals(request.fingerprint, response.requestFingerprint)
        return response.result
    }

    private fun admit(db: SQLiteDatabase, uid: String, brains: List<NpcBrainChange>, effects: List<VerifiedMechanicsCommandEffect> = emptyList(),
        clock: TemporalStateChange? = null, receipt: BackgroundProcessChange? = null): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef(player.kindUid, player.uid)
        val command = PlayerCommand(commandUid = identity(uid).commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("PLAN:$uid", effects, clock, brains, backgroundChanges = listOfNotNull(receipt)),
            provenance = CommandProvenance("P64:DEVICE:COMBAT_OWNERS"), requestedEffectiveOrder = scope(db).baseCommitOrder + 1)
        // START has a canonical clock replacement even before a Phase64 receipt exists.
        // Its campaign reference must be in the exact command and draft admission context.
        val refs = (listOf(player, npc, opponent, DomainRef("CAMPAIGN", campaign), DomainRef(KnowledgeHolderKinds.CHARACTER, npc.uid),
            DomainRef(KnowledgeHolderKinds.CHARACTER, opponent.uid), DomainRef("RESOURCE", "STAMINA")) +
            listOfNotNull(receipt).flatMap(::phase64References)).map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val engine = productionMechanicsPlayerDomainEngine(WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(binding))),
            WorldPackAuthoritySnapshot.single(campaign, binding))
        var resolutionRejection: PlayerResolutionRejection? = null
        val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding))) { resolutionRejection = it }
        assertTrue("$uid: $admission; resolution=${resolutionRejection?.reason?.reasonUid}; " +
            "detail=${resolutionRejection?.detailUid}; refs=${resolutionRejection?.relatedRefs}", admission is CampaignMutationAdmission.Accepted)
        return (admission as CampaignMutationAdmission.Accepted).proposal
    }

    /** Read-only contact-to-exposure eligibility regression. The exposure definition is a
     * catalog candidate made after real contact, not an imported disease/settlement fixture. */
    private fun assertCommittedNpcContactExposureCapture(db: SQLiteDatabase, proposal: CanonicalCampaignMutationProposal) {
        val wound = proposal.playerChangeSet.changes.single { (it.payload as? WoundChange)?.subject == opponent }
        val intent = proposal.playerChangeSet.eventIntents.single { it.actorRef == npc && it.targetRefs == listOf(opponent) &&
            it.causalChangeUids == listOf(wound.changeUid) }
        val event = CampaignEventStore(db, campaign).eventsForTransaction(identity("COMPLETE").transactionUid)
            .single { it.eventIntentUid == intent.eventIntentUid }
        db.rawQuery("SELECT source_actor_kind_uid,source_actor_uid,actor_ref_kind_uid,actor_ref_uid FROM ${CampaignIntelligencePhase30Schema.EVENT_TABLE} WHERE campaign_uid=? AND event_uid=?",
            arrayOf(campaign, event.eventUid)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(player.kindUid, cursor.getString(0)); assertEquals(player.uid, cursor.getString(1))
            assertEquals(npc.kindUid, cursor.getString(2)); assertEquals(npc.uid, cursor.getString(3))
        }
        val definition = Phase64PopulationRuleCatalog.exposure("DEVICE:PATHOGEN_POLICY", "EXPOSED:DEVICE", 2, 2, event.eventUid)
        val parameters = Phase64ProcessActivation.bind(definition, npc, opponent)
        val evaluation = BackgroundProcessEvaluationScope(scope(db), "DEVICE-SEED", requireNotNull(Phase64BackgroundStore(db, campaign).policy()))
        fun capture(source: DomainRef = npc, target: DomainRef = opponent, eventUid: String = event.eventUid,
            at: BackgroundProcessEvaluationScope = evaluation) = requireNotNull(Phase64PopulationProductionReads.activitySnapshot(db, campaign,
            source, definition, Phase64ProcessActivation.bind(definition, source, target) + ("contact_event_uid" to eventUid), at, emptyList()))
        val digest = AuthoritativeStateDigest.compute(db)
        val captured = capture()
        assertTrue(captured.contactEvidencePresent)
        assertNull(Phase64PopulationRuleCatalog.unavailable(definition, parameters, npc, captured))
        val exposure = Phase64PopulationOwnerPreparation.exposure(captured.body, definition, "EXPOSED:DEVICE", 2, 2,
            event.eventUid, "DEVICE:EXPOSURE_CANDIDATE")
        assertEquals(listOf(ConditionChange(opponent, "EXPOSED:DEVICE", ConditionOperation.ADD)), exposure.changes)
        assertTrue(event.eventUid in exposure.sourceUids)
        // All participants share the arena; neither co-location nor the outer command's
        // PLAYER source may replace the actual NPC actor and exact event target.
        assertEquals(body(db, npc).locationRef, body(db, player).locationRef)
        assertEquals(body(db, npc).locationRef, body(db, opponent).locationRef)
        assertFalse(capture(source = player).contactEvidencePresent)
        assertFalse(capture(source = opponent).contactEvidencePresent)
        assertFalse(capture(target = npc).contactEvidencePresent)
        assertFalse(capture(eventUid = "DEVICE:MISSING_CONTACT").contactEvidencePresent)
        val beforeContact = evaluation.copy(temporal = evaluation.temporal.copy(baseCommitOrder = requireNotNull(event.committedOrder) - 1))
        assertFalse(capture(at = beforeContact).contactEvidencePresent)
        assertEquals(digest, AuthoritativeStateDigest.compute(db))
        assertTrue(body(db, opponent).conditions.none { it.conditionUid == "EXPOSED:DEVICE" })
    }

    @Test fun controlledCombatCommitsRealWoundBrainAndReceiptAtomicallyWithoutDuplicateRetry() = SQLiteDatabase.create(null).use { db ->
        setup(db)
        recognize(db)
        val initialInput = TemporalOwnerInput(scope(db), WorldTimeTick(0), WorldTimeTick(0), emptyList(), emptyList(), null)
        val projected = project(db, initialInput, null)
        assertTrue(projected.toString(), projected is NpcContextResult.Ready)
        val context = (projected as NpcContextResult.Ready).context
        assertTrue(context.records.any { opponent in it.subjectRefs && it.acquisitionUid == "DEVICE:RECOGNITION" })
        val goal = requireNotNull(NpcBrainDynamics.considerGoals(context, listOf(NpcGoalCandidate("DEVICE:COMBAT_GOAL",
            context.brain.motivations.first().uid, "Wykonać kontrolowaną próbę starcia.", context.records.map { it.uid }.toSet()))))
        assertTrue(TurnTransactionBoundary.create(db, identity("GOAL"), admit(db, "GOAL", listOf(goal))).commit() is TurnExecutionResult.Committed)

        val beforeStart = AuthoritativeStateDigest.compute(db)
        val started = evaluate(db, "START", initialInput.copy(scope = scope(db)), true)
        assertEquals(beforeStart, AuthoritativeStateDigest.compute(db))
        assertTrue(started.mechanicalEffects.isEmpty()) // preflight never becomes an admitted impact
        val pending = NpcActionProcess.decode(started.state).single()
        val startClock = Phase60TemporalStateStore(db, campaign).read().let { before -> TemporalStateChange(campaign, before.version,
            before.time, before.time, Phase60ProcessStateCodec.encode(listOf(started.state)), Phase60DeadlineCodec.encode(started.nextDeadlines)) }
        assertTrue(TurnTransactionBoundary.create(db, identity("START"), admit(db, "START", started.changes.filterIsInstance<NpcBrainChange>(),
            clock = startClock)).commit() is TurnExecutionResult.Committed)
        assertEquals(NpcPlanLifecycle.RUNNING, brain(db).plans.single().lifecycle)
        assertTrue(body(db, opponent).conditions.none { it.conditionUid == "WOUND" })
        assertEquals(20L, body(db, npc).resources.single { it.resourceUid == "STAMINA" }.current)

        val before = Phase60TemporalStateStore(db, campaign).read()
        val running = brain(db)
        val baseline = AuthoritativeStateDigest.compute(db)
        val input = TemporalOwnerInput(scope(db), before.time, pending.due, emptyList(), before.deadlines,
            before.processStates.single(), peerStates = before.processStates.associateBy { it.ownerUid }, deadlineView = before.deadlines)
        val done = evaluate(db, "COMPLETE", input, false)
        assertFalse(done.playerDecisionRequired)
        assertTrue(NpcActionProcess.decode(done.state).isEmpty())
        val finished = done.changes.filterIsInstance<NpcBrainChange>().single()
        assertEquals(NpcPlanLifecycle.COMPLETED, NpcBrainCodec.decode(finished.stateCanonical).plans.single().lifecycle)
        val impact = done.mechanicalEffects.single { it.target == opponent }
        assertEquals("WOUND", impact.effectKindUid)
        assertTrue(impact.magnitude > 0)
        assertTrue(impact.canonicalPayload.getValue("combat_proof_uid").matches(Regex("PROOF:[0-9a-f]{64}")))
        val cost = done.mechanicalEffects.single { it.target == npc }
        assertEquals(-2L, cost.magnitude)
        val mechanical = done.mechanicalEffects.flatMap { effect ->
            (MechanicalEffectMaterializer.materialize(effect) as MechanicalEffectMaterializationResult.Materialized).changes.map { it.payload }
        }
        val staged = input.copy(stagedChanges = done.changes + mechanical, stagedEffects = done.mechanicalEffects,
            peerStates = mapOf(NpcActionProcess.OWNER to done.state))
        val store = Phase64BackgroundStore(db, campaign)
        val prepared = Phase64CombatReceiptFactory.prepare(BackgroundProcessEvaluationScope(input.scope, "DEVICE-SEED", requireNotNull(store.policy())),
            identity("COMPLETE").commandUid, ActivePlayerStore(db, campaign).requireActive().playerUid,
            requireNotNull(store.definition(rule.uid, rule.version)), running, staged, done.mechanicalEffects)
        assertTrue(prepared.toString(), prepared is Phase64CombatReceiptPreparation.Ready)
        val receipt = (prepared as Phase64CombatReceiptPreparation.Ready).change
        assertEquals(pending.planUid, receipt.process.parameters["decision_uid"])
        assertEquals(pending.due, receipt.process.due) // acknowledgement adds no artificial 60-second wait
        assertTrue(receipt.evidence.sourceUids.containsAll(done.mechanicalEffects.map { it.proofUid }))
        assertTrue(receipt.evidence.sourceUids.contains(impact.canonicalPayload.getValue("combat_proof_uid")))
        assertEquals((listOf(finished) + mechanical).map(Phase64BackgroundCodec::fingerprint).sorted(), receipt.consequenceFingerprints.sorted())
        assertEquals(baseline, AuthoritativeStateDigest.compute(db))
        val clock = TemporalStateChange(campaign, before.version, before.time, pending.due, Phase60ProcessStateCodec.encode(listOf(done.state)),
            Phase60DeadlineCodec.encode(done.nextDeadlines))
        val proposal = admit(db, "COMPLETE", listOf(finished), done.mechanicalEffects, clock, receipt)
        assertTrue(runCatching { TurnTransactionBoundary.create(db, identity("COMPLETE"), proposal, TurnFailureInjector {
            if (it == TurnFailurePoint.AFTER_FIRST_WRITE) error("injected")
        }).commit() }.isFailure)
        assertEquals(baseline, AuthoritativeStateDigest.compute(db))
        assertEquals(NpcPlanLifecycle.RUNNING, brain(db).plans.single().lifecycle)
        assertNull(store.process(receipt.process.uid))
        assertTrue(TurnTransactionBoundary.create(db, identity("COMPLETE"), proposal).commit() is TurnExecutionResult.Committed)
        assertEquals(impact.magnitude, body(db, opponent).conditions.single { it.conditionUid == "WOUND" }.intensity)
        assertEquals(18L, body(db, npc).resources.single { it.resourceUid == "STAMINA" }.current)
        assertEquals(NpcPlanLifecycle.COMPLETED, brain(db).plans.single().lifecycle)
        assertEquals(BackgroundProcessStatus.COMPLETED, store.process(receipt.process.uid)!!.status)
        assertTrue(Phase60TemporalStateStore(db, campaign).read().deadlines.isEmpty())
        val committed = AuthoritativeStateDigest.compute(db)
        assertTrue(TurnTransactionBoundary.create(db, identity("COMPLETE"), proposal).commit() is TurnExecutionResult.AlreadyCommitted)
        assertEquals(committed, AuthoritativeStateDigest.compute(db))
        assertEquals(impact.magnitude, body(db, opponent).conditions.single { it.conditionUid == "WOUND" }.intensity)
        assertEquals(18L, body(db, npc).resources.single { it.resourceUid == "STAMINA" }.current)
        assertCommittedNpcContactExposureCapture(db, proposal)
        val evidenceCount = db.rawQuery("SELECT COUNT(*) FROM ${Phase64BackgroundSchema.EVIDENCE} WHERE campaign_uid=? AND process_uid=?",
            arrayOf(campaign, receipt.process.uid)).use { cursor -> cursor.moveToFirst(); cursor.getLong(0) }
        assertEquals(1L, evidenceCount)
    }
}
