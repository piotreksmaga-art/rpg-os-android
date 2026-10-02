package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Complements the host process-death checkpoint test with actual timed completion and commit.
 * The model choice and acquired destination are controlled fixture inputs, not an AI-quality test.
 * Neither destination nor resource deltas are fabricated by the test: Core resolves the route.
 */
@RunWith(AndroidJUnit4::class)
class Phase62NpcTravelCompletionDeviceTest {
    @Test fun timedTravelCommitsArrivalAndCostsAtomicallyAndRetryCannotChargeTwice() {
        SQLiteDatabase.create(null).use { db ->
            val npc = DomainRef("NPC", "N")
            val origin = DomainRef("LOCATION", "A")
            val destination = DomainRef("LOCATION", "B")
            db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
            db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
            db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
            GameplayRuntimeBootstrap.initialize(db, "C1")
            withAdministrativeMutationAuthority(db, "C1") {
                MechanicalActorStateStore(db, "C1").materializeIfMissing(MechanicalActorSeed(
                    npc, MechanicalActorKind.NPC, "T", "S", "TEST",
                    mapOf("POWER" to 10), listOf(MechanicalResource("STAMINA", 20, 20),
                        MechanicalResource("SUPPLIES", 10, 10), MechanicalResource("HEALTH", 100, 100)),
                    setOf("WORLD:WALK")))
                db.execSQL("INSERT INTO entity_positions VALUES('N','A',12,34,0,0)")
            }
            val route = NpcTravelRouteContract("C1", "ROAD", 1, origin, destination,
                ActionDuration(120000), "WORLD:ROAD", capabilityUid = "WORLD:WALK",
                resourceCosts = mapOf("STAMINA" to 3L, "SUPPLIES" to 2L))
            val routes = NpcTravelRoutePort { campaign, actor, at ->
                if (campaign == "C1" && actor == npc && at == origin) listOf(route) else emptyList()
            }
            val scope = TemporalScope("C1", "H", 0, AuthoritativeStateDigest.compute(db))
            val brain = NpcBrainOwner.initialize("C1", npc, "SEED").let { state ->
                state.copy(goals = listOf(NpcGoal("GOAL:TRAVEL", state.motivations.first().uid,
                    "Dotrzeć do znanego celu", NpcWeight(5000), NpcGoalLifecycle.ACTIVE,
                    NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION, "ACQUISITION"))))
            }
            val known = NpcKnownRecord("DESTINATION", KnowledgeEpistemicState.KNOWN,
                "Znam drogę do celu.", "ACQUISITION", 0, setOf(destination))
            fun body() = MechanicalActorStateStore(db, "C1").actor(npc)!!
            fun input(at: Long, staged: List<PlayerDomainChangePayload> = emptyList()) =
                TemporalOwnerInput(scope, WorldTimeTick(0), WorldTimeTick(at), emptyList(),
                    emptyList(), null, stagedChanges = staged)
            val contexts = NpcPhysicalContextPort { _, current, pending ->
                val state = applyNpcBrainOverlay(brain, scope, current.stagedChanges.filterIsInstance<NpcBrainChange>())
                val reads = object : NpcProjectionReadPort {
                    override fun brain(a: AudienceContext, p: PurposeContext, actor: DomainRef, holder: KnowledgeHolderRef) =
                        ProtectedReadResult.Allow(state, DisclosureLevel.DISCLOSE_FULL, "SELF")
                    override fun knowledge(a: AudienceContext, p: PurposeContext, holder: KnowledgeHolderRef, order: Long, limit: Int) =
                        ProtectedReadResult.Allow(listOf(known), DisclosureLevel.DISCLOSE_FULL, "ACQUIRED")
                }
                val cause = pending?.let { row -> state.plans.single { it.uid == row.planUid }.cause }
                    ?: NpcCauseRef(NpcCauseKind.KNOWLEDGE_ACQUISITION, "ACQUISITION")
                NpcDecisionContextProjector(reads).project(
                    NpcDecisionScope(scope, npc, state.revision, current.through, 0, "P1"),
                    NpcTrigger("TRIGGER", if (pending == null) NpcTriggerKind.KNOWLEDGE_CHANGED else NpcTriggerKind.PLAN_BOUNDARY,
                        current.through, cause), state.knowledgeHolder, ContextRuntimeProfile("TEST", 8192, 64, 64, 512)
                ) { stateNow, records -> NpcTravelAffordances.options(stateNow, records, body(), routes) }
            }
            var modelCalls = 0
            val provider = DeterministicAiProvider(
                AiCapabilityContract("DEVICE-CONTROLLED", "MODEL", "TEST", setOf(AiWorkload.NPC_DECISION), maximumContextUnits = 8192),
                intentFunction = { error("unused") }, proposalFunction = { error("unused") }, narrativeFunction = { error("unused") },
                npcDecisionFunction = { request ->
                    modelCalls++
                    NpcDecisionProposal(request.requestUid, request.context.contextFingerprint,
                        listOf(NpcDecisionCandidate(request.context.options.single().uid)))
                })
            val app = NpcTimedActionApplication("CMD:TRAVEL", contexts,
                AiModelRoutePort { _, _, _ -> AiRouteResult.Selected(provider, true, "DEVICE-CONTROLLED") }, { scope },
                NpcMechanicalActionApplication(MechanicsRuleResolver { _, _ -> error("No generic travel fallback") },
                    { scope }, routes, NpcTravelActorReadPort { expected, actor -> body().takeIf { expected == scope && actor == npc } }))
            val before = AuthoritativeStateDigest.compute(db)
            val started = app.prepare(npc, input(0)) { false }
            assertTrue(started.toString(), started is NpcActionPreparation.Started)
            started as NpcActionPreparation.Started
            val pending = NpcActionProcess.decode(TemporalOwnerState(NpcActionProcess.OWNER, 1,
                NpcActionProcess.encode(listOf(started.pending)))).single()
            assertTrue(app.complete(pending, input(119999, started.changes)) { false } is NpcActionCompletion.Unavailable)
            val done = app.complete(pending, input(120000, started.changes)) { false }
            assertTrue(done.toString(), done is NpcActionCompletion.Finished)
            done as NpcActionCompletion.Finished
            assertFalse(done.interrupted)
            assertEquals(1, modelCalls)
            assertEquals(before, AuthoritativeStateDigest.compute(db))
            assertEquals(origin, body().locationRef)
            val actor = CommandActorRef("PLAYER", "P1")
            val command = PlayerCommand(commandUid = "CMD:TRAVEL", campaignUid = "C1", actor = actor,
                commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
                payload = ApplyVerifiedMechanicsCommandPayload("PLAN:TRAVEL", done.effects), provenance = CommandProvenance("DEVICE-CONTROLLED"),
                requestedEffectiveOrder = 1)
            val refs = setOf(DomainRef("PLAYER", "P1"), npc, origin, destination,
                DomainRef("RESOURCE", "STAMINA"), DomainRef("RESOURCE", "SUPPLIES"))
                .map { CampaignScopedDomainRef("C1", it) }.toSet()
            val admission = CampaignMutationBoundary.resolveAndAdmit("C1", productionMechanicsPlayerDomainEngine(), command,
                PlayerResolutionContext.createUnboundGeneric("C1", actor, refs))
            assertTrue(admission.toString(), admission is CampaignMutationAdmission.Accepted)
            val proposal = (admission as CampaignMutationAdmission.Accepted).proposal
            val identity = TurnTransactionIdentity("C1", "TURN:TRAVEL", "CMD:TRAVEL", "TX:TRAVEL")
            assertTrue(runCatching {
                TurnTransactionBoundary.create(db, identity, proposal, TurnFailureInjector {
                    if (it == TurnFailurePoint.AFTER_FIRST_WRITE) error("injected")
                }).commit()
            }.isFailure)
            assertEquals(before, AuthoritativeStateDigest.compute(db))
            assertTrue(TurnTransactionBoundary.create(db, identity, proposal).commit() is TurnExecutionResult.Committed)
            assertEquals(destination, body().locationRef)
            assertEquals(17L, body().resources.single { it.resourceUid == "STAMINA" }.current)
            assertEquals(8L, body().resources.single { it.resourceUid == "SUPPLIES" }.current)
            val committed = AuthoritativeStateDigest.compute(db)
            assertTrue(TurnTransactionBoundary.create(db, identity, proposal).commit() is TurnExecutionResult.AlreadyCommitted)
            assertEquals(committed, AuthoritativeStateDigest.compute(db))
        }
    }
}
