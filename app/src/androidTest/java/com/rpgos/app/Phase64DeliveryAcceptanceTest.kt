package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Short SQLite integration, not UI/generative acceptance. Fixture authority prepares only
 * existing bodies, cargo and a registered edge. Actual protected reads, Phase60 processing
 * and the ordinary TurnTransaction produce every delivery consequence. Reopen is not OS death. */
@RunWith(AndroidJUnit4::class)
class Phase64DeliveryAcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val campaign = "P64:DELIVERY:C"
    private val player = DomainRef("PLAYER", "P64:DELIVERY:P")
    private val carrier = DomainRef("NPC", "P64:DELIVERY:CARRIER")
    private val recipient = DomainRef("NPC", "P64:DELIVERY:RECIPIENT")
    private val origin = DomainRef("LOCATION", "P64:DELIVERY:WAREHOUSE")
    private val destination = DomainRef("LOCATION", "P64:DELIVERY:TOWN")
    private val cargo = "P64:DELIVERY:CARGO"
    private val binding = WorldPackRuleBinding("P64:DELIVERY:PACK", "1")
    private val edge = WorldTopologyEdge("P64:DELIVERY:ROAD", 1, origin, destination,
        ActionDuration(1000), mapOf("STAMINA" to 2), setOf("WALK"), WorldTimeTick(0), null,
        "P63:LOCAL-CONNECTION:P64:REGISTERED-INPUT")
    private val route = WorldTravelPlan(origin, destination, listOf(edge))
    private val delivery = BackgroundProcessDefinition("P64:DELIVERY:RULE", 1, "ECONOMY",
        Phase64EconomyOperations.DELIVER, 1000, priority = 10, parameters = mapOf(
            "inputItemUids" to cargo, "recipientKind" to recipient.kindUid, "recipientUid" to recipient.uid,
            "destinationKind" to destination.kindUid, "destinationUid" to destination.uid,
            "routeUid" to route.fingerprint, "routeEdgeUids" to edge.uid, "activation_npc" to "true",
            Phase64ProcessActivation.ACTION_KEY to "DELIVER_CARGO"))
    private val consumption = BackgroundProcessDefinition("P64:DELIVERY:CONSUME", 1, "ECONOMY",
        Phase64EconomyOperations.CONSUME, 1000, parameters = mapOf("inputItemUids" to cargo,
            "activation_npc" to "true", Phase64ProcessActivation.ACTION_KEY to "CONSUME_CARGO"))

    private fun identity(uid: String) = TurnTransactionIdentity(campaign, "TURN:$uid", "CMD:$uid", "TX:$uid")

    private fun scope(db: SQLiteDatabase) = TemporalScope(campaign, HistoryGenerationStore(db, campaign).current().value,
        TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0, AuthoritativeStateDigest.compute(db))

    private fun commit(db: SQLiteDatabase, uid: String, proposal: CanonicalCampaignMutationProposal) =
        TurnTransactionBoundary.create(db, identity(uid), proposal).commit()

    private fun setup(db: SQLiteDatabase, definitions: List<BackgroundProcessDefinition>) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        GameplayRuntimeBootstrap.initialize(db, campaign)
        withAdministrativeMutationAuthority(db, campaign) {
            val bodies = MechanicalActorStateStore(db, campaign)
            listOf(player, carrier, recipient).forEach { ref ->
                bodies.materializeIfMissing(MechanicalActorSeed(ref,
                    if (ref == player) MechanicalActorKind.ACTIVE_PLAYER else MechanicalActorKind.NPC,
                    "DEVICE", "DEVICE-SEED", "DEVICE-BOOTSTRAP", mapOf("DEFENCE" to 10),
                    listOf(MechanicalResource("HEALTH", 10, 10), MechanicalResource("STAMINA", 5, 5)),
                    setOf("INTERACTION", "WALK")))
            }
            db.execSQL("INSERT INTO entity_positions VALUES(?,?,0,0,0,0)", arrayOf(carrier.uid, origin.uid))
            listOf(origin, destination).forEach { place ->
                CampaignTruthStore(db, campaign).record(TruthKind.FACT, CampaignWorldFacts.KIND,
                    Provenance(ProvenanceSourceType.WORLD_CANON, sourceId = "DEVICE-BOOTSTRAP", verified = true),
                    subjectUid = place.uid, objectValue = place.kindUid, truthUid = "KIND:${place.uid}", createdAt = 0)
            }
            db.execSQL("INSERT INTO ${Phase63WorldSchema.EDGES} VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(
                campaign, edge.uid, edge.version, edge.origin.kindUid, edge.origin.uid, edge.destination.kindUid,
                edge.destination.uid, Phase63WorldCodec.edge(edge).toString(), edge.fingerprint, 1))
            val inventory = InventoryStore(db, campaign)
            inventory.registerDefinitions(binding.worldPackUid, listOf(ItemDefinition("P64:DELIVERY:CARGO_DEF",
                binding.worldPackUid, "cargo", "Cargo", storagePolicy = ItemStoragePolicy.UNIQUE_INSTANCE,
                provenance = "DEVICE-BOOTSTRAP")))
            inventory.createInstance(ItemInstance(campaign, cargo, "P64:DELIVERY:CARGO_DEF", provenance = "DEVICE-BOOTSTRAP"))
            inventory.addUnique(carrier.uid, cargo, "DEVICE-BOOTSTRAP")
            definitions.forEach { rule ->
                val grant = "GRANT:${rule.uid}"
                AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), grant,
                    AccessAuthorityChange(AccessOperation.GRANT, grant, carrier.kindUid, carrier.uid,
                        AccessGrantKind.WORLD_RULE.name, rule.operation, validFromOrder = 0), 0)
            }
            Phase64BackgroundStore(db, campaign).initializeNew(binding, definitions)
        }
    }

    /** Knowledge is an input, but it still needs a real event-linked acquisition. This small
     * fixture component follows Phase37's test boundary and uses the actual route-memory owner;
     * it neither replaces the delivery adapter nor writes a fabricated completed process. */
    private fun recordRouteKnowledge(db: SQLiteDatabase) {
        val uid = "ROUTE_KNOWLEDGE"
        val fixture = RouteKnowledgeInput(campaign, HistoryGenerationStore(db, campaign).current().value,
            Phase63WorldCodec.edge(edge).toString(), carrier.kindUid, carrier.uid)
        val actor = CommandActorRef(player.kindUid, player.uid)
        val command = PlayerCommand(commandUid = identity(uid).commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS, payload = TransferFundsCommandPayload("A", "B", 1, "CUR"),
            provenance = CommandProvenance("P64:DEVICE:INPUT_FIXTURE"), requestedEffectiveOrder = 1)
        val refs = listOf(player, carrier, DomainRef(KnowledgeHolderKinds.CHARACTER, carrier.uid), DomainRef("WORLD_ROUTE", edge.uid),
            DomainRef("FINANCIAL_ACCOUNT", "A"), DomainRef("FINANCIAL_ACCOUNT", "B"), DomainRef("CURRENCY", "CUR"))
            .map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val admitted = CampaignMutationBoundary.resolveAndAdmit(campaign,
            PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(fixture))), command,
            PlayerResolutionContext.createUnboundGeneric(campaign, actor, refs))
        assertTrue(admitted.toString(), admitted is CampaignMutationAdmission.Accepted)
        assertTrue(commit(db, uid, (admitted as CampaignMutationAdmission.Accepted).proposal) is TurnExecutionResult.Committed)
        val holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, carrier.uid, campaign)
        assertEquals(WorldRouteKnowledge.claimUid(edge), KnowledgeStore(db, campaign).states(holder).single().claimUid)
        assertEquals(KnowledgeEpistemicState.KNOWN, KnowledgeStore(db, campaign).states(holder).single().epistemicState)
    }

    // Resolution components accept immutable scalar captures only. No test instance, live
    // SQLite handle or mutable edge can cross the mutation boundary as captured state.
    private class RouteKnowledgeInput(private val campaign: String, private val generation: String,
        private val edgeWire: String, private val carrierKind: String, private val carrierUid: String) :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,
            TransferFundsCommandPayload::class, "P64:ROUTE-INPUT", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val input = WorldSimulationChange(campaign, HistoryGenerationUid(generation), 0, null,
                listOf(Phase63WorldCodec.readEdge(Json.parseToJsonElement(edgeWire).jsonObject)))
            val memory = WorldRouteKnowledge.materialize(campaign, command.commandUid, 1,
                CommandActorRef(carrierKind, carrierUid), listOf(input))
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes = memory.changes, eventIntents = memory.events))
        }
    }

    private fun admit(uid: String, order: Long, clock: TemporalStateChange,
        changes: List<PlayerDomainChangePayload>, effects: List<VerifiedMechanicsCommandEffect> = emptyList()): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef(player.kindUid, player.uid)
        val command = PlayerCommand(commandUid = identity(uid).commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("PLAN:$uid", effects, temporalState = clock, backgroundChanges = changes),
            provenance = CommandProvenance("P64:DEVICE:DELIVERY_INTEGRATION"), requestedEffectiveOrder = order)
        val refs = (listOf(player, DomainRef("CAMPAIGN", campaign), DomainRef("RESOURCE", "STAMINA")) +
            changes.flatMap(::phase64References) + effects.map { it.target }).map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val engine = productionMechanicsPlayerDomainEngine(WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(binding))),
            WorldPackAuthoritySnapshot.single(campaign, binding))
        val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding)))
        assertTrue(admission.toString(), admission is CampaignMutationAdmission.Accepted)
        return (admission as CampaignMutationAdmission.Accepted).proposal
    }

    /** Trusted initiation-effect fixture matches the existing acceptance boundary. It proves
     * a completed NPC initiation, not selection quality; the tested delivery remains future. */
    private fun start(db: SQLiteDatabase, definitions: List<BackgroundProcessDefinition>): List<BackgroundProcessInstance> {
        val before = Phase60TemporalStateStore(db, campaign).read()
        val at = WorldTimeTick(1000)
        val effects = definitions.map { rule ->
            val process = "PROCESS:${rule.uid}"
            val hash = phase63Hash(Phase64BackgroundCodec.definition(rule).toString())
            VerifiedMechanicsCommandEffect("START:${rule.uid}", "NODE:${rule.uid}", "UNIVERSAL_ACTION", "INTERACTION", carrier, 1,
                mapOf("track_uid" to "ACTION:P64_START:$process", "magnitude" to "1", "p64_start_rule" to rule.uid,
                    "p64_start_version" to rule.version.toString(), "p64_start_fingerprint" to hash, "p64_start_process" to process,
                    "p64_start_target_kind" to "ITEM_INSTANCE", "p64_start_target_uid" to cargo,
                    "p64_start_decision_uid" to "P62:DECISION:DEVICE:${rule.uid}",
                    "source_actor_kind_uid" to carrier.kindUid, "source_actor_uid" to carrier.uid),
                "${Phase64ProcessActivation.START_PROOF}$hash:DEVICE:${rule.uid}", "DEVICE:INPUT", "DEVICE:OUTPUT")
        }
        val changes = definitions.zip(effects).map { (rule, effect) ->
            Phase64ProcessActivation.start(scope(db), identity("START").commandUid, rule, effect, at)
        }
        val deadlines = changes.map { WorldProcessDeadline(Phase64BackgroundProcessOwner.deadline(it.process),
            Phase64BackgroundProcessOwner.OWNER, it.process.due) }
        val clock = TemporalStateChange(campaign, before.version, before.time, at,
            Phase60ProcessStateCodec.encode(before.processStates), Phase60DeadlineCodec.encode(deadlines))
        assertTrue(commit(db, "START", admit("START", 2, clock, changes, effects)) is TurnExecutionResult.Committed)
        return changes.map { it.process }
    }

    private fun runDelivery(competingConsumption: Boolean) {
        val file = File(folder.root, if (competingConsumption) "delivery-conflict.db" else "delivery.db")
        val definitions = listOfNotNull(delivery, consumption.takeIf { competingConsumption })
        var db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            setup(db, definitions)
            recordRouteKnowledge(db)
            val started = start(db, definitions)
            val before = Phase60TemporalStateStore(db, campaign).read()
            val digest = AuthoritativeStateDigest.compute(db)
            val through = WorldTimeTick(2000)
            val store = Phase64BackgroundStore(db, campaign)
            val evaluationScope = BackgroundProcessEvaluationScope(scope(db), "DEVICE-SEED", requireNotNull(store.policy()))
            val open = { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
            val reads = Phase64ProductionReads(evaluationScope, open, { scope(db) }, through, { who, where, _ ->
                if (who != carrier || where != destination) null else open().use { captured ->
                    phase64CapturedDeliveryRoute(captured, campaign, carrier, started.first().parameters, evaluationScope, emptyList())
                }
            })
            val owner = Phase64BackgroundProcessOwner(evaluationScope, store.due(through), definitions.associateBy { it.uid to it.version },
                { store.process(it) }, reads, listOf(Phase64EconomyProjectsAdapter()))
            val foreground = object : WorldProcessOwnerPort {
                override val ownerUid = PHASE60_FOREGROUND_OWNER
                override fun evaluate(input: TemporalOwnerInput) = TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid, 1, "WAIT"))
            }
            val processor = Phase60TimeProcessor(listOf(foreground, owner), nanoTime = { 0 })
            val checkpoint = processor.begin(evaluationScope.temporal, identity("DELIVER").commandUid, before.time,
                listOf(TimedActionNode("WAIT", foreground.ownerUid, AcceptedActionTiming(ActionDuration(1000), "DEVICE_WAIT", 1))),
                before.deadlines, before.processStates)
            val execution = processor.advance(checkpoint, scope(db))
            assertEquals(execution.toString(), TemporalStopReason.COMPLETED, execution.reason)
            assertTrue(execution.readyForAdmission)
            assertEquals(digest, AuthoritativeStateDigest.compute(db)) // preparation is read-only
            val work = execution.checkpoint
            val receipts = work.candidateChanges.filterIsInstance<BackgroundProcessChange>().associateBy { it.process.definitionUid }
            assertEquals(receipts.getValue(delivery.uid).process.reasonUid,
                BackgroundProcessStatus.COMPLETED, receipts.getValue(delivery.uid).process.status)
            assertEquals(listOf(ResourceChange(carrier, "STAMINA", ExactLongDelta.of(-2))), work.candidateChanges.filterIsInstance<ResourceChange>())
            assertEquals(listOf(SpatialChange(carrier, 0, destinationLocation = destination)), work.candidateChanges.filterIsInstance<SpatialChange>())
            assertEquals(listOf(InventoryChange(carrier, cargo, ExactLongDelta.of(-1)), InventoryChange(recipient, cargo, ExactLongDelta.of(1))),
                work.candidateChanges.filterIsInstance<InventoryChange>())
            if (competingConsumption) {
                assertEquals(BackgroundProcessStatus.BLOCKED, receipts.getValue(consumption.uid).process.status)
                assertEquals("P64:RESOURCE_REQUIREMENT_UNSATISFIED", receipts.getValue(consumption.uid).process.reasonUid)
            }
            val clock = TemporalStateChange(campaign, before.version, before.time, work.reached,
                Phase60ProcessStateCodec.encode(work.ownerStates.values.filterNot { it.ownerUid == foreground.ownerUid }),
                Phase60DeadlineCodec.encode(work.deadlines), execution.reason.name,
                Phase60ExecutionReport.encode(Phase60ExecutionReport.from(work)))
            val proposal = admit("DELIVER", 3, clock, work.candidateChanges, work.candidateEffects)
            assertTrue(commit(db, "DELIVER", proposal) is TurnExecutionResult.Committed)
            val delivered = AuthoritativeStateDigest.compute(db)
            assertTrue(commit(db, "DELIVER", proposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(delivered, AuthoritativeStateDigest.compute(db))
            assertTrue(InventoryStore(db, campaign).typedUnique(carrier.uid).isEmpty())
            assertEquals(cargo, InventoryStore(db, campaign).typedUnique(recipient.uid).single().first.itemInstanceUid)
            val body = requireNotNull(MechanicalActorStateStore(db, campaign).actor(carrier))
            assertEquals(destination, body.locationRef)
            assertEquals(3L, body.resources.single { it.resourceUid == "STAMINA" }.current)
            assertEquals(BackgroundProcessStatus.COMPLETED, store.process(started.first().uid)!!.status)
            assertEquals(1, CommittedReplayPayloadStore(db).after(campaign, 2).single().changeSet.changes.count { it.payload is ResourceChange })
            db.close()
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db, campaign)
            assertEquals(delivered, AuthoritativeStateDigest.compute(db))
            assertEquals(BackgroundProcessStatus.COMPLETED, Phase64BackgroundStore(db, campaign).process(started.first().uid)!!.status)
            assertTrue(commit(db, "DELIVER", proposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(delivered, AuthoritativeStateDigest.compute(db))
            // Use the reopened store, not a stale SQLite handle captured by the earlier fixture.
            assertEquals(cargo, InventoryStore(db, campaign).typedUnique(recipient.uid).single().first.itemInstanceUid)
            assertEquals(3L, MechanicalActorStateStore(db, campaign).actor(carrier)!!.resources.single { it.resourceUid == "STAMINA" }.current)
        } finally {
            if (db.isOpen) db.close()
        }
    }

    @Test fun realRouteDeliveryCommitsOnceAndSurvivesDatabaseReopen() = runDelivery(false)

    @Test fun deliveredCargoCannotAlsoBeConsumedAtTheSameDeadline() = runDelivery(true)
}
