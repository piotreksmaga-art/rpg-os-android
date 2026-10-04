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

/** Short real-SQLite owner integration. Only initial bodies, roles and registered rules use
 * bootstrap authority. Assignment, knowledge, population extraction and travel use the normal
 * mutation boundary. Trusted initiation effects are fixture inputs, not a claim to test NPC
 * selection or models. Database reopen is persistence acceptance, not Android process death. */
@RunWith(AndroidJUnit4::class)
class Phase64InstitutionPopulationAcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val campaign = "P64:DOMAIN:C"
    private val player = DomainRef("PLAYER", "P64:DOMAIN:P")
    private val issuer = DomainRef("NPC", "P64:DOMAIN:ISSUER")
    private val assignee = DomainRef("NPC", "P64:DOMAIN:ASSIGNEE")
    private val organization = DomainRef("ORGANIZATION", "P64:DOMAIN:ORG")
    private val origin = DomainRef("LOCATION", "P64:DOMAIN:ORIGIN")
    private val destination = DomainRef("LOCATION", "P64:DOMAIN:DESTINATION")
    private val channel = DomainRef("INFORMATION_CHANNEL", "P64:DOMAIN:CHANNEL")
    private val disclosure = DomainRef("DISCLOSURE_POLICY", "P64:DOMAIN:DISCLOSURE")
    private val binding = WorldPackRuleBinding("P64:DOMAIN:PACK", "1")

    private fun identity(uid: String) = TurnTransactionIdentity(campaign, "TURN:$uid", "CMD:$uid", "TX:$uid")
    private fun scope(db: SQLiteDatabase) = TemporalScope(campaign, HistoryGenerationStore(db, campaign).current().value,
        TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0, AuthoritativeStateDigest.compute(db))
    private fun processUid(rule: BackgroundProcessDefinition) = "PROCESS:${rule.uid}"
    private fun holder(ref: DomainRef) = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, ref.uid, campaign)

    private fun setup(db: SQLiteDatabase, prepare: (SQLiteDatabase) -> List<BackgroundProcessDefinition>): List<BackgroundProcessDefinition> {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        GameplayRuntimeBootstrap.initialize(db, campaign)
        var definitions = emptyList<BackgroundProcessDefinition>()
        withAdministrativeMutationAuthority(db, campaign) {
            listOf(player, issuer, assignee).forEach { ref ->
                MechanicalActorStateStore(db, campaign).materializeIfMissing(MechanicalActorSeed(ref,
                    if (ref == player) MechanicalActorKind.ACTIVE_PLAYER else MechanicalActorKind.NPC,
                    "DEVICE", "DEVICE-SEED", "DEVICE-BOOTSTRAP", mapOf("DEFENCE" to 10),
                    listOf(MechanicalResource("HEALTH", 10, 10), MechanicalResource("STAMINA", 10, 10)), setOf("INTERACTION", "WALK")))
                db.execSQL("INSERT INTO entity_positions VALUES(?,?,0,0,0,0)", arrayOf(ref.uid, origin.uid))
            }
            listOf(organization, origin, destination).forEach { ref ->
                CampaignTruthStore(db, campaign).record(TruthKind.FACT, CampaignWorldFacts.KIND,
                    Provenance(ProvenanceSourceType.WORLD_CANON, sourceId = "DEVICE-BOOTSTRAP", verified = true),
                    subjectUid = ref.uid, objectValue = ref.kindUid, truthUid = "KIND:${ref.uid}", createdAt = 0)
            }
            definitions = prepare(db)
            Phase64BackgroundStore(db, campaign).initializeNew(binding, definitions)
        }
        return definitions
    }

    private fun grant(db: SQLiteDatabase, principal: DomainRef, uid: String, purpose: String, subject: DomainRef? = null) {
        AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), uid,
            AccessAuthorityChange(AccessOperation.GRANT, uid, principal.kindUid, principal.uid,
                AccessGrantKind.WORLD_RULE.name, purpose, subject?.kindUid, subject?.uid, validFromOrder = 0), 0)
    }

    private fun bind(db: SQLiteDatabase, principal: DomainRef, kind: AccessBindingKind, value: String) {
        val uid = "BIND:${principal.uid}:${kind.name}:$value"
        AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), uid,
            AccessAuthorityChange(AccessOperation.UPSERT_BINDING, uid, principal.kindUid, principal.uid,
                kind.name, value, validFromOrder = 0), 0)
    }

    private fun channelAccess(db: SQLiteDatabase, principal: DomainRef, purpose: String, references: List<DomainRef>) {
        references.forEach { ref -> grant(db, principal, "CHANNEL:${principal.uid}:$purpose:${ref.kindUid}:${ref.uid}", purpose, ref) }
        CarrierAccessStage.entries.forEach { stage ->
            val uid = "STAGE:${principal.uid}:$purpose:${stage.name}"
            AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), uid,
                AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS, uid, principal.kindUid, principal.uid,
                    AccessGrantKind.EXPLICIT.name, stage.name, channel.kindUid, channel.uid, validFromOrder = 0), 0)
        }
    }

    private fun admit(db: SQLiteDatabase, uid: String, clock: TemporalStateChange?, changes: List<PlayerDomainChangePayload>,
        effects: List<VerifiedMechanicsCommandEffect> = emptyList(), world: List<WorldSimulationChange> = emptyList(),
        extraRefs: List<DomainRef> = emptyList()): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef(player.kindUid, player.uid)
        val order = (TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0) + 1
        val command = PlayerCommand(commandUid = identity(uid).commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("PLAN:$uid", effects, clock, worldChanges = world, backgroundChanges = changes),
            provenance = CommandProvenance("P64:DEVICE:DOMAIN_INTEGRATION"), requestedEffectiveOrder = order)
        val evidence = changes.filterIsInstance<KnowledgeAcquisitionChange>().flatMap { it.evidence }.mapNotNull { it.sourceRef }
            .filter { it.scope == KnowledgeReferenceScope.CAMPAIGN }.map { DomainRef(it.kindUid, it.entityUid) }
        val access = changes.filterIsInstance<AccessAuthorityChange>().flatMap { change ->
            listOf(DomainRef(change.principalKindUid, change.principalUid)) +
                listOfNotNull(change.subjectKindUid?.let { kind -> change.subjectUid?.let { DomainRef(kind, it) } })
        }
        val refs = (listOf(player, issuer, assignee, organization, origin, destination, DomainRef("CAMPAIGN", campaign),
            DomainRef("RESOURCE", "STAMINA")) + extraRefs + evidence + access + changes.flatMap(::phase64References) + effects.map { it.target })
            .map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val engine = productionMechanicsPlayerDomainEngine(WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(binding))),
            WorldPackAuthoritySnapshot.single(campaign, binding))
        val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding)))
        assertTrue(admission.toString(), admission is CampaignMutationAdmission.Accepted)
        return (admission as CampaignMutationAdmission.Accepted).proposal
    }

    private fun commit(db: SQLiteDatabase, uid: String, proposal: CanonicalCampaignMutationProposal) =
        TurnTransactionBoundary.create(db, identity(uid), proposal).commit()

    /** The same proof/action-track/command/rule/deadline binding as the production initiation
     * helper. These inputs assert an admitted NPC action; they do not replace completion reads. */
    private fun start(db: SQLiteDatabase, requests: List<Triple<BackgroundProcessDefinition, DomainRef, DomainRef>>) {
        val before = Phase60TemporalStateStore(db, campaign).read()
        val at = WorldTimeTick(before.time.milliseconds + 1000)
        val effects = requests.map { (rule, actor, target) ->
            val hash = phase63Hash(Phase64BackgroundCodec.definition(rule).toString())
            VerifiedMechanicsCommandEffect("START:${rule.uid}", "NODE:${rule.uid}", "UNIVERSAL_ACTION", "INTERACTION", actor, 1,
                mapOf("track_uid" to "ACTION:P64_START:${processUid(rule)}", "magnitude" to "1",
                    "p64_start_rule" to rule.uid, "p64_start_version" to rule.version.toString(), "p64_start_fingerprint" to hash,
                    "p64_start_process" to processUid(rule), "p64_start_target_kind" to target.kindUid, "p64_start_target_uid" to target.uid,
                    "p64_start_decision_uid" to "P62:DECISION:DEVICE:${rule.uid}",
                    "source_actor_kind_uid" to actor.kindUid, "source_actor_uid" to actor.uid),
                "${Phase64ProcessActivation.START_PROOF}$hash:DEVICE:${rule.uid}", "DEVICE:INPUT", "DEVICE:OUTPUT")
        }
        val changes = requests.zip(effects).map { (request, effect) ->
            Phase64ProcessActivation.start(scope(db), identity("START").commandUid, request.first, effect, at)
        }
        val deadlines = before.deadlines + changes.map { WorldProcessDeadline(Phase64BackgroundProcessOwner.deadline(it.process),
            Phase64BackgroundProcessOwner.OWNER, it.process.due) }
        val clock = TemporalStateChange(campaign, before.version, before.time, at,
            Phase60ProcessStateCodec.encode(before.processStates), Phase60DeadlineCodec.encode(deadlines))
        assertTrue(commit(db, "START", admit(db, "START", clock, changes, effects)) is TurnExecutionResult.Committed)
    }

    private fun waitUntil(db: SQLiteDatabase, file: File, uid: String, through: WorldTimeTick,
        definitions: List<BackgroundProcessDefinition>, routeRead: ((SQLiteDatabase, BackgroundProcessEvaluationScope, DomainRef, DomainRef) -> WorldTravelPlan?)? = null
    ): List<PlayerDomainChangePayload> {
        val before = Phase60TemporalStateStore(db, campaign).read()
        val store = Phase64BackgroundStore(db, campaign)
        val evaluation = BackgroundProcessEvaluationScope(scope(db), "DEVICE-WORLD-SEED", requireNotNull(store.policy()))
        val open = { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) }
        val reads = Phase64ProductionReads(scope = evaluation, open = open, current = { scope(db) }, through = through,
            principalRouteRead = { principal, _, target, _ -> open().use { routeRead?.invoke(it, evaluation, principal, target) } },
            routeRead = { actor, target, _ -> open().use { routeRead?.invoke(it, evaluation, actor, target) } })
        val owner = Phase64BackgroundProcessOwner(evaluation, store.due(through), definitions.associateBy { it.uid to it.version },
            { store.process(it) }, reads, listOf(Phase64OrganizationsInformationAdapter(), Phase64PopulationConflictsAdapter()))
        val foreground = object : WorldProcessOwnerPort {
            override val ownerUid = PHASE60_FOREGROUND_OWNER
            override fun evaluate(input: TemporalOwnerInput) = TemporalOwnerResult.Evaluated(TemporalOwnerState(ownerUid, 1, "WAIT"))
        }
        val processor = Phase60TimeProcessor(listOf(foreground, owner) + NpcDutyDeadlineProcess.extension().owners.map { it.owner }, nanoTime = { 0 })
        val initial = processor.begin(evaluation.temporal, identity(uid).commandUid, before.time,
            listOf(TimedActionNode("WAIT:$uid", foreground.ownerUid,
                AcceptedActionTiming(ActionDuration(through.milliseconds - before.time.milliseconds), "DEVICE_WAIT", 1))), before.deadlines, before.processStates)
        val result = processor.advance(initial, scope(db))
        assertEquals(result.toString(), TemporalStopReason.COMPLETED, result.reason)
        assertTrue(result.readyForAdmission)
        assertEquals(evaluation.temporal.authoritativeFingerprint, AuthoritativeStateDigest.compute(db))
        val work = result.checkpoint
        // The production Phase60 boundary admits the duty owner's future timer together with
        // its access grant. This existing helper applies the same receipt deadline overlay.
        val deadlines = phase64StagedDeadlines(work.deadlines, work.candidateChanges)
        val clock = TemporalStateChange(campaign, before.version, before.time, work.reached,
            Phase60ProcessStateCodec.encode(work.ownerStates.values.filterNot { it.ownerUid == foreground.ownerUid }),
            Phase60DeadlineCodec.encode(deadlines), result.reason.name, Phase60ExecutionReport.encode(Phase60ExecutionReport.from(work)))
        val proposal = admit(db, uid, clock, work.candidateChanges, work.candidateEffects)
        assertTrue(commit(db, uid, proposal) is TurnExecutionResult.Committed)
        val digest = AuthoritativeStateDigest.compute(db)
        assertTrue(commit(db, uid, proposal) is TurnExecutionResult.AlreadyCommitted)
        assertEquals(digest, AuthoritativeStateDigest.compute(db))
        return work.candidateChanges
    }

    @Test fun possessedCarrierCannotBypassMissingOrRevokedComprehensionCapture() {
        SQLiteDatabase.create(null).use { db ->
            val carrier = DomainRef("REPORT", "P64:PRIVATE_REPORT")
            val claim = KnowledgeClaim("P64:PRIVATE_CLAIM", "LOCATION", origin.uid, "OPEN", "false",
                domainUid = KnowledgeDomains.MILITARY_INTELLIGENCE)
            val reading = NpcActivityContract("READ", "P64:READ_REPORT", 1, ActionDuration(1000), "READ_EFFORT",
                reading = NpcReadingRule(carrier, "P64:COVERT", claim))
            val fields = mapOf("carrier_kind" to carrier.kindUid, "carrier_uid" to carrier.uid,
                "carrier_rule_uid" to reading.ruleUid, "carrier_rule_version" to "1",
                "espionage_policy_uid" to "P64:COVERT", "recipient_kind" to assignee.kindUid,
                "recipient_uid" to assignee.uid)
            val definition = BackgroundProcessDefinition("P64:READ_PRIVATE", 1, "INFORMATION", "ESPIONAGE", 1000,
                parameters = fields)
            setup(db) { initial ->
                grant(initial, issuer, "COVERT_GRANT", "P64:COVERT", carrier)
                SQLiteDatabase.create(null).use { world ->
                    world.execSQL("CREATE TABLE npc_activity_definitions(rule_uid TEXT,rule_version INTEGER,contract_json TEXT)")
                    world.execSQL("INSERT INTO npc_activity_definitions VALUES(?,?,?)",
                        arrayOf<Any?>(reading.ruleUid, reading.version, NpcActivityContractCodec.encode(reading)))
                    NpcActivityDefinitionImport.importPack(initial, world, campaign, binding)
                }
                // Real inventory custody deliberately proves possession, but no carrier stages.
                val inventory = InventoryStore(initial, campaign)
                inventory.registerDefinitions(binding.worldPackUid, listOf(ItemDefinition("P64:REPORT_DEF",
                    binding.worldPackUid, "private_report", "Private report", "DOCUMENT",
                    ItemStoragePolicy.UNIQUE_INSTANCE, provenance = "DEVICE-BOOTSTRAP")))
                inventory.createInstance(ItemInstance(campaign, carrier.uid, "P64:REPORT_DEF", provenance = "DEVICE-BOOTSTRAP"))
                inventory.addUnique(issuer.uid, carrier.uid, "DEVICE-BOOTSTRAP")
                listOf(definition)
            }
            val evaluation = BackgroundProcessEvaluationScope(scope(db), "DEVICE", requireNotNull(Phase64BackgroundStore(db, campaign).policy()))
            val process = BackgroundProcessInstance(processUid(definition), definition.uid, 1, issuer, 1,
                WorldTimeTick(0), WorldTimeTick(1000))
            val staged = listOf(BackgroundProcessChange(campaign, evaluation.temporal.historyGenerationUid,
                0, process, WorldProcessEvidence("PRIVATE:START", process.uid, definition.uid, definition.version,
                    listOf(carrier.uid), WorldTimeTick(0))))
            val parameters = fields + mapOf("_p64_definition_uid" to definition.uid, "_p64_rule_version" to "1",
                "_p64_process_uid" to process.uid, "_p64_rule_fingerprint" to evaluation.ruleFingerprint,
                "_p64_logical_event_uid" to Phase64OrganizationsInformationAdapter.logicalEventUid(definition, process, evaluation))
            fun capture(overlay: List<PlayerDomainChangePayload>): Phase64InstitutionEspionageCapture? {
                val snapshot = Phase64InstitutionCarrierSnapshot(evaluation.temporal, evaluation.temporal.baseCommitOrder,
                    setOf(issuer, assignee, carrier), reading,
                    AccessAuthorityStore(db, campaign).effective(VisibilityPrincipalRef(issuer.kindUid, issuer.uid), 0))
                return (Phase64InstitutionCarrierOwner.capture(issuer, parameters, evaluation, snapshot, overlay)
                    as? Phase64InstitutionCarrierProjection.Ready)?.capture
            }
            fun prepare(overlay: List<PlayerDomainChangePayload>, withCapture: Boolean) = Phase64InstitutionProductionReads.prepare(
                db, campaign, Phase64OrganizationsInformationAdapter.OWNER_ESPIONAGE, issuer, parameters, evaluation,
                overlay, captureEspionage = if (withCapture) { _, _, _, changes -> capture(changes) } else null)
            val before = AuthoritativeStateDigest.compute(db)
            for (withCapture in listOf(false, true)) {
                val blocked = prepare(staged, withCapture)
                assertEquals("P64:ESPIONAGE_CARRIER_ACCESS_UNAVAILABLE", blocked.reasonUid)
                assertTrue(blocked.changes.isEmpty())
            }
            val stages = CarrierAccessStage.entries.map { stage ->
                AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS, "PRIVATE:${stage.name}", issuer.kindUid,
                    issuer.uid, AccessGrantKind.EXPLICIT.name, stage.name, carrier.kindUid, carrier.uid, validFromOrder = 0)
            }
            val ready = prepare(staged + stages, true)
            assertEquals(BackgroundProcessStatus.COMPLETED, ready.status)
            assertEquals(KnowledgeEpistemicState.BELIEVED, (ready.changes.single() as KnowledgeAcquisitionChange).acquisition.epistemicState)
            val revoke = stages.single { it.valueUid == CarrierAccessStage.COMPREHENDED.name }
                .copy(operation = AccessOperation.REVOKE_GRANT, recordUid = "PRIVATE:REVOKE")
            val revoked = prepare(staged + stages + revoke, true)
            assertEquals("P64:ESPIONAGE_CARRIER_ACCESS_UNAVAILABLE", revoked.reasonUid)
            assertTrue(revoked.changes.isEmpty())
            assertEquals("Evaluation cannot mutate canonical state", before, AuthoritativeStateDigest.compute(db))
        }
    }

    @Test fun realInstitutionAssignmentAdmitsDutyTimerAndDelayedReportCitesCommittedKnowledge() {
        val file = File(folder.root, "institution.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val duty = NpcDutyRule("P64:DOMAIN:WATCH", 1, organization.uid, "GUARD", "P64:DOMAIN:WATCH_DUE", WorldTimeTick(6000), "ASSIGN_WATCH")
            val activity = NpcActivityContract("GUARD", "P64:DOMAIN:WATCH_ACTIVITY", 1, ActionDuration(1000), "WATCH_EFFORT", duty = duty)
            val assignment = (Phase64InstitutionRuleCatalog.assign(Phase64InstitutionRuleRegistration(campaign, "P64:DOMAIN:ASSIGN", 1,
                "ASSIGN_WATCH", 1000, npcAction = true, npcActivationPolicyUid = "P64:DOMAIN:ASSIGN_POLICY"),
                Phase64InstitutionDutyRecipeSnapshot(organization, activity)) as Phase64InstitutionRulePreparation.Ready).definition
            fun information(uid: String, operation: String, duration: Long, recipient: DomainRef, fields: Map<String, String> = emptyMap()) =
                BackgroundProcessDefinition(uid, 1, "INFORMATION", operation, duration, parameters = mapOf(
                    "recipient_kind" to recipient.kindUid, "recipient_uid" to recipient.uid, "message_uid" to "MESSAGE:$uid",
                    "message_text" to "The watch saw the bridge open.", "channel_uid" to channel.uid,
                    "disclosure_policy_uid" to disclosure.uid, "delay_ms" to duration.toString(),
                    "activation_npc" to "true", Phase64ProcessActivation.ACTION_KEY to "ACTION:$uid") + fields)
            val source = information("P64:DOMAIN:SOURCE", "MESSAGE", 1000, issuer)
            val sourceProcess = BackgroundProcessInstance(processUid(source), source.uid, source.version, assignee, 1, WorldTimeTick(1000), WorldTimeTick(2000))
            val citationScope = BackgroundProcessEvaluationScope(TemporalScope(campaign, "INPUT", 0, "INPUT"), "INPUT", "INPUT")
            val sourceAcquisition = "P64:MESSAGE_ACQUISITION:${Phase64OrganizationsInformationAdapter.logicalEventUid(source, sourceProcess, citationScope)}"
            val report = information("P64:DOMAIN:REPORT", "REPORT", 3000, organization, mapOf("source_acquisition_uid" to sourceAcquisition))
            val definitions = setup(db) { initial ->
                bind(initial, issuer, AccessBindingKind.ORGANIZATION, organization.uid)
                bind(initial, assignee, AccessBindingKind.ORGANIZATION, organization.uid)
                bind(initial, assignee, AccessBindingKind.ROLE, duty.roleUid)
                grant(initial, issuer, "GRANT:ASSIGN", Phase64OrganizationsInformationAdapter.OWNER_ASSIGN)
                grant(initial, issuer, "GRANT:ASSIGN_POLICY", duty.assignmentPolicyUid, organization)
                listOf(channel, disclosure).forEach { ref ->
                    CampaignTruthStore(initial, campaign).record(TruthKind.FACT, CampaignWorldFacts.KIND,
                        Provenance(ProvenanceSourceType.WORLD_CANON, sourceId = "DEVICE-BOOTSTRAP", verified = true),
                        subjectUid = ref.uid, objectValue = ref.kindUid, truthUid = "KIND:${ref.uid}", createdAt = 0)
                }
                CampaignTruthStore(initial, campaign).record(TruthKind.FACT, "P64:MAILBOX_ANCHOR",
                    Provenance(ProvenanceSourceType.WORLD_CANON, sourceId = "DEVICE-BOOTSTRAP", verified = true),
                    subjectUid = organization.uid, objectValue = origin.uid, truthUid = "MAILBOX:${organization.uid}", createdAt = 0)
                channelAccess(initial, assignee, "P64:INFO_SEND:MESSAGE", listOf(issuer, channel, disclosure))
                channelAccess(initial, issuer, "P64:INFO_RECEIVE", listOf(assignee, channel, disclosure))
                channelAccess(initial, issuer, "P64:INFO_SEND:REPORT", listOf(organization, channel, disclosure, DomainRef("KNOWLEDGE_ACQUISITION", sourceAcquisition)))
                channelAccess(initial, organization, "P64:INFO_RECEIVE", listOf(issuer, channel, disclosure))
                SQLiteDatabase.create(null).use { world ->
                    world.execSQL("CREATE TABLE npc_activity_definitions(rule_uid TEXT,rule_version INTEGER,contract_json TEXT)")
                    world.execSQL("INSERT INTO npc_activity_definitions VALUES(?,?,?)", arrayOf<Any?>(activity.ruleUid, activity.version, NpcActivityContractCodec.encode(activity)))
                    NpcActivityDefinitionImport.importPack(initial, world, campaign, binding)
                }
                listOf(assignment, source, report)
            }
            start(db, listOf(Triple(assignment, issuer, assignee), Triple(source, assignee, issuer), Triple(report, issuer, organization)))
            val institutionHolder = KnowledgeHolderRef(KnowledgeHolderKinds.ORGANIZATION, organization.uid, campaign)
            assertTrue(waitUntil(db, file, "BEFORE_DUE", WorldTimeTick(1999), definitions).filterIsInstance<BackgroundProcessChange>().isEmpty())
            assertFalse(SqliteNpcDutyAssignmentPort(db, campaign).admitted(campaign, assignee, duty, WorldTimeTick(1999)))
            assertTrue(KnowledgeStore(db, campaign).acquisitions(institutionHolder).isEmpty())
            val assigned = waitUntil(db, file, "ASSIGN_AND_SOURCE", WorldTimeTick(2000), definitions)
            assertEquals(1, assigned.filterIsInstance<AccessAuthorityChange>().size)
            assertEquals(WorldProcessDeadline(duty.deadlineUid, NpcDutyDeadlineProcess.OWNER, duty.due),
                Phase60TemporalStateStore(db, campaign).read().deadlines.single { it.uid == duty.deadlineUid })
            assertTrue(SqliteNpcDutyAssignmentPort(db, campaign).admitted(campaign, assignee, duty, WorldTimeTick(2000)))
            assertEquals(sourceAcquisition, KnowledgeStore(db, campaign).acquisitions(holder(issuer)).single().acquisitionUid)
            assertTrue(KnowledgeStore(db, campaign).acquisitions(institutionHolder).isEmpty())
            val delivered = waitUntil(db, file, "REPORT_DUE", WorldTimeTick(4000), definitions).filterIsInstance<KnowledgeAcquisitionChange>().single()
            assertEquals(KnowledgeAcquisitionMethods.REPORT, delivered.acquisition.methodUid)
            assertEquals(KnowledgeEpistemicState.BELIEVED, delivered.acquisition.epistemicState)
            assertEquals(KnowledgeScope.INSTITUTIONAL, delivered.acquisition.scope)
            assertTrue(delivered.evidence.any { it.evidenceKindUid == "P64:REPORT_SOURCE_CITATION" && it.sourceRef?.entityUid == sourceAcquisition })
            assertNull(delivered.acquisition.parentAcquisitionUid)
            assertEquals(1, KnowledgeStore(db, campaign).acquisitions(institutionHolder).size)
            assertEquals(KnowledgeEpistemicState.BELIEVED, KnowledgeStore(db, campaign).states(institutionHolder).single().epistemicState)
            assertEquals(BackgroundProcessStatus.COMPLETED, Phase64BackgroundStore(db, campaign).process(processUid(report))!!.status)
        }
    }

    /** Scalar-only event-linked input fixture, using the actual route-memory materializer. */
    private class RouteKnowledgeInput(private val campaign: String, private val generation: String,
        private val edgeWire: String, private val principalKind: String, private val principalUid: String, private val order: Long) :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "P64:DOMAIN:ROUTE_INPUT", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val input = WorldSimulationChange(campaign, HistoryGenerationUid(generation), 0, null,
                listOf(Phase63WorldCodec.readEdge(Json.parseToJsonElement(edgeWire).jsonObject)))
            val memory = WorldRouteKnowledge.materialize(campaign, command.commandUid, order, CommandActorRef(principalKind, principalUid), listOf(input))
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes = memory.changes, eventIntents = memory.events))
        }
    }

    @Test fun realWholeCohortMigrationMovesNamedMemberPreservesCasualtiesAndBlocksCompetingDeparture() {
        val file = File(folder.root, "population.db")
        val group = DomainRef("GROUP", "P64:DOMAIN:COHORT")
        val skeleton = CampaignWorldSkeleton.legacy(campaign, binding.ruleSource, "Device era", origin).copy(latentRules = CoreLatentWorldRules.initial())
        val manifest = WorldPopulationManifest(group, 6, skeleton.domainSeed("POPULATION", group.toString()))
        val edge = WorldTopologyEdge("P64:DOMAIN:ROAD", 1, origin, destination, ActionDuration(1000),
            mapOf("STAMINA" to 2), setOf("WALK"), WorldTimeTick(0), null, "P63:LOCAL-CONNECTION:P64:DOMAIN:BOOTSTRAP")
        val route = WorldTravelPlan(origin, destination, listOf(edge))
        var migration: BackgroundProcessDefinition? = null
        var competitor: BackgroundProcessDefinition? = null
        var digest: String? = null
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val definitions = setup(db) { initial ->
                MechanicalActorStateStore(initial, campaign).materializeIfMissing(MechanicalActorSeed(group, MechanicalActorKind.GROUP,
                    "DEVICE-COHORT", "DEVICE-SEED", "DEVICE-BOOTSTRAP", mapOf("DEFENCE" to 10),
                    listOf(MechanicalResource("HEALTH", 60, 60), MechanicalResource("STAMINA", 12, 12)),
                    setOf("WALK"), aggregateName = "Device cohort", aggregateCount = 6))
                initial.execSQL("INSERT INTO entity_positions VALUES(?,?,0,0,0,0)", arrayOf(group.uid, origin.uid))
                initial.execSQL("UPDATE aggregate_combat_populations SET active_count=4,wounded_count=1,eliminated_count=1 WHERE campaign_id=? AND entity_uid=?", arrayOf(campaign, group.uid))
                initial.execSQL("INSERT INTO ${Phase63WorldSchema.EDGES} VALUES(?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>(campaign, edge.uid, edge.version,
                    origin.kindUid, origin.uid, destination.kindUid, destination.uid, Phase63WorldCodec.edge(edge).toString(), edge.fingerprint, 1))
                val body = requireNotNull(MechanicalActorStateStore(initial, campaign).actor(group))
                val first = Phase64PopulationRuleCatalog.cohortMovement(Phase64PopulationActivitySnapshot(body, manifest), route).copy(priority = 10)
                val second = first.copy(uid = first.uid + ":SECOND", priority = 0,
                    parameters = first.parameters + (Phase64ProcessActivation.ACTION_KEY to "SECOND_COHORT_DEPARTURE"))
                migration = first; competitor = second
                grant(initial, issuer, "GRANT:MIGRATE", Phase64PopulationConflictsAdapter.POPULATION_MIGRATE)
                listOf(first, second)
            }
            // Extract an existing living slot through the real Phase63/50 owner chain.
            val draft = WorldElementDraft(campaign, manifest.member(5), "Named cohort member", WorldElementBaseKind.ACTOR,
                "LOCAL_PERSON", null, setOf("TALK"), "LOCAL_SITE", WorldEvidenceClassification.GENERATED_PLAUSIBLE,
                listOf(manifest.uid), null, null, null, slotOrdinal = 5)
            val proof = "RPGOS-CORE:WORLD-MATERIALIZATION:${draft.fingerprint()}"
            val body = requireNotNull(MechanicalActorStateStore(db, campaign).actor(group))
            val world = WorldSimulationChange(campaign, HistoryGenerationStore(db, campaign).current(), 0, skeleton,
                populationManifests = listOf(manifest), populationExtractions = listOf(WorldPopulationExtraction(manifest.uid, 5, draft.element, draft.displayName, body.stateVersion, proof)))
            val effect = VerifiedMechanicsCommandEffect("EXTRACT", "EXTRACT", "RPGOS-CORE:WORLD-MATERIALIZER", "WORLD_ELEMENT_MATERIALIZE", draft.element, 1,
                draft.materializationPayload() + ("p63_population_manifest" to manifest.uid), proof, phase63Hash("INPUT"), phase63Hash("OUTPUT"))
            assertTrue(commit(db, "EXTRACT", admit(db, "EXTRACT", null, emptyList(), listOf(effect), listOf(world), listOf(group))) is TurnExecutionResult.Committed)
            assertEquals(listOf(draft.element), WorldPopulationStore(db, campaign).namedMembers(group))
            val counts = MechanicalActorStateStore(db, campaign).actor(group)!!.aggregatePopulation!!
            assertEquals(AggregateMechanicalPopulation(5, 3, 1, 1), counts)
            val routeOrder = scope(db).baseCommitOrder + 1
            val input = RouteKnowledgeInput(campaign, scope(db).historyGenerationUid, Phase63WorldCodec.edge(edge).toString(), issuer.kindUid, issuer.uid, routeOrder)
            val actor = CommandActorRef(player.kindUid, player.uid)
            val command = PlayerCommand(commandUid = identity("ROUTE_INPUT").commandUid, campaignUid = campaign, actor = actor,
                commandKindUid = PlayerCommandKinds.TRANSFER_FUNDS, payload = TransferFundsCommandPayload("A", "B", 1, "CUR"),
                provenance = CommandProvenance("P64:DEVICE:INPUT_FIXTURE"), requestedEffectiveOrder = routeOrder)
            val refs = listOf(player, issuer, DomainRef(KnowledgeHolderKinds.CHARACTER, issuer.uid), DomainRef("WORLD_ROUTE", edge.uid),
                DomainRef("FINANCIAL_ACCOUNT", "A"), DomainRef("FINANCIAL_ACCOUNT", "B"), DomainRef("CURRENCY", "CUR"))
                .map { CampaignScopedDomainRef(campaign, it) }.toSet()
            val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(input))), command,
                PlayerResolutionContext.createUnboundGeneric(campaign, actor, refs))
            assertTrue(admission.toString(), admission is CampaignMutationAdmission.Accepted)
            assertTrue(commit(db, "ROUTE_INPUT", (admission as CampaignMutationAdmission.Accepted).proposal) is TurnExecutionResult.Committed)
            assertEquals(WorldRouteKnowledge.claimUid(edge), KnowledgeStore(db, campaign).states(holder(issuer)).single().claimUid)
            start(db, definitions.map { Triple(it, issuer, destination) })
            val beforeCapacity = MechanicalActorStateStore(db, campaign).actor(group)!!.resources.single { it.resourceUid == "STAMINA" }.current
            val results = waitUntil(db, file, "MIGRATE", WorldTimeTick(2000), definitions) { captured, evaluation, principal, target ->
                if (principal != issuer || target != destination) null else phase64CapturedDeliveryRoute(captured, campaign, issuer,
                    mapOf("destinationKind" to destination.kindUid, "destinationUid" to destination.uid, "routeEdgeUids" to edge.uid), evaluation, emptyList())
            }
            val outcomes = results.filterIsInstance<BackgroundProcessChange>().associateBy { it.process.definitionUid }
            assertEquals(BackgroundProcessStatus.COMPLETED, outcomes.getValue(requireNotNull(migration).uid).process.status)
            assertEquals(BackgroundProcessStatus.BLOCKED, outcomes.getValue(requireNotNull(competitor).uid).process.status)
            assertEquals("P64:NAMED_MEMBER_CAPTURE_REQUIRED", outcomes.getValue(requireNotNull(competitor).uid).process.reasonUid)
            assertEquals(setOf(group, draft.element), results.filterIsInstance<SpatialChange>().map { it.subject }.toSet())
            assertEquals(listOf(ResourceChange(group, "STAMINA", ExactLongDelta.of(-2))), results.filterIsInstance<ResourceChange>())
            assertEquals(counts, MechanicalActorStateStore(db, campaign).actor(group)!!.aggregatePopulation)
            assertEquals(beforeCapacity - 2, MechanicalActorStateStore(db, campaign).actor(group)!!.resources.single { it.resourceUid == "STAMINA" }.current)
            assertEquals(destination, MechanicalActorStateStore(db, campaign).actor(group)!!.locationRef)
            assertEquals(destination, MechanicalActorStateStore(db, campaign).actor(draft.element)!!.locationRef)
            assertEquals(group, WorldPopulationStore(db, campaign).aggregateForMember(draft.element))
            assertEquals(manifest, WorldPopulationStore(db, campaign).forAggregate(group))
            digest = AuthoritativeStateDigest.compute(db)
        }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { reopened ->
            GameplayRuntimeBootstrap.requireReady(reopened, campaign)
            assertEquals(digest, AuthoritativeStateDigest.compute(reopened))
            assertEquals(destination, MechanicalActorStateStore(reopened, campaign).actor(manifest.member(5))!!.locationRef)
            assertEquals(BackgroundProcessStatus.COMPLETED, Phase64BackgroundStore(reopened, campaign).process(processUid(requireNotNull(migration)))!!.status)
            assertEquals(BackgroundProcessStatus.BLOCKED, Phase64BackgroundStore(reopened, campaign).process(processUid(requireNotNull(competitor)))!!.status)
        }
    }
}
