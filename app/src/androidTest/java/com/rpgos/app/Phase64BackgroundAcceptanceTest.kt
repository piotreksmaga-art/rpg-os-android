package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Small real-SQLite acceptance for the canonical boundary. No model, host or replacement
 * inventory is involved. Closing a DB tests reopen, not Android OS process-death recovery. */
@RunWith(AndroidJUnit4::class)
class Phase64BackgroundAcceptanceTest {
    @get:Rule val folder = TemporaryFolder()

    private val campaign = "P64:DEVICE:CAMPAIGN"
    private val player = DomainRef("PLAYER", "P64:DEVICE:PLAYER")
    private val actor = CommandActorRef(player.kindUid, player.uid)
    private val binding = WorldPackRuleBinding("P64:DEVICE:PACK", "1")
    private val itemUid = "P64:DEVICE:MATERIAL"
    private val actionUid = "P64:DEVICE:CONSUME_ACTION"
    private val rule = BackgroundProcessDefinition("P64:DEVICE:CONSUME", 1, "ECONOMY",
        Phase64EconomyOperations.CONSUME, 1000, parameters = mapOf(
            "inputItemUids" to itemUid,
            "sourceUid" to "P64:DEVICE:CONSUMPTION_POLICY",
            Phase64ProcessActivation.ACTION_KEY to actionUid,
            Phase64ProcessActivation.PUBLIC_KEY to "true"
        ))

    private fun calendar(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
    }

    private fun setup(db: SQLiteDatabase, enabled: Boolean = true,
                      definitions: List<BackgroundProcessDefinition> = listOf(rule),
                      resources: List<MechanicalResource> = emptyList()) {
        calendar(db)
        // Legacy extension tables must exist before bootstrap installs their write guards.
        db.execSQL("CREATE TABLE entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        GameplayRuntimeBootstrap.initialize(db, campaign)
        withAdministrativeMutationAuthority(db, campaign) {
            MechanicalActorStateStore(db, campaign).materializeIfMissing(MechanicalActorSeed(
                player, MechanicalActorKind.ACTIVE_PLAYER, "DEVICE", "DEVICE-SEED", "DEVICE-BOOTSTRAP",
                mapOf("DEFENCE" to 10), listOf(MechanicalResource("HEALTH", 10, 10)) + resources, setOf("INTERACTION")))
            val inventory = InventoryStore(db, campaign)
            inventory.registerDefinitions(binding.worldPackUid, listOf(ItemDefinition(
                "P64:DEVICE:MATERIAL_DEF", binding.worldPackUid, "device-material", "Material",
                storagePolicy = ItemStoragePolicy.UNIQUE_INSTANCE, provenance = "DEVICE-BOOTSTRAP")))
            inventory.createInstance(ItemInstance(campaign, itemUid, "P64:DEVICE:MATERIAL_DEF", provenance = "DEVICE-BOOTSTRAP"))
            inventory.addUnique(player.uid, itemUid, "DEVICE-BOOTSTRAP")
            AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), "DEVICE:GRANT",
                AccessAuthorityChange(AccessOperation.GRANT, "DEVICE:GRANT", player.kindUid, player.uid,
                    AccessGrantKind.WORLD_RULE.name, Phase64EconomyOperations.CONSUME,
                    "ITEM_INSTANCE", itemUid, validFromOrder = 0), 0)
            if (enabled) Phase64BackgroundStore(db, campaign).initializeNew(binding, definitions)
        }
    }

    private fun identity(uid: String) = TurnTransactionIdentity(campaign, "TURN:$uid", "CMD:$uid", "TX:$uid")

    private fun temporalScope(db: SQLiteDatabase) = TemporalScope(campaign,
        HistoryGenerationStore(db, campaign).current().value,
        TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0,
        AuthoritativeStateDigest.compute(db))

    private fun admit(uid: String, order: Long, clock: TemporalStateChange,
                      payloads: List<PlayerDomainChangePayload> = emptyList(),
                      effects: List<VerifiedMechanicsCommandEffect> = emptyList()): CanonicalCampaignMutationProposal {
        val command = PlayerCommand(commandUid = identity(uid).commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("PLAN:$uid", effects, temporalState = clock, backgroundChanges = payloads),
            provenance = CommandProvenance("P64:DEVICE:ACCEPTANCE"), requestedEffectiveOrder = order)
        val evidenceRefs = payloads.filterIsInstance<KnowledgeAcquisitionChange>().flatMap { change ->
            change.evidence.mapNotNull { it.sourceRef }.filter { it.scope == KnowledgeReferenceScope.CAMPAIGN }
                .map { DomainRef(it.kindUid, it.entityUid) }
        }
        val refs = (listOf(player, DomainRef("CAMPAIGN", campaign)) + effects.map { it.target } +
            payloads.flatMap(::phase64References) + evidenceRefs).map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val engine = productionMechanicsPlayerDomainEngine(
            WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(binding))),
            WorldPackAuthoritySnapshot.single(campaign, binding))
        var resolutionRejection: PlayerResolutionRejection? = null
        val result = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding))) { resolutionRejection = it }
        assertTrue("$uid: $result; resolution=${resolutionRejection?.reason?.reasonUid}; " +
            "detail=${resolutionRejection?.detailUid}; refs=${resolutionRejection?.relatedRefs}", result is CampaignMutationAdmission.Accepted)
        return (result as CampaignMutationAdmission.Accepted).proposal
    }

    private fun commit(db: SQLiteDatabase, uid: String, proposal: CanonicalCampaignMutationProposal,
                       failure: TurnFailureInjector = TurnFailureInjector.NONE) =
        TurnTransactionBoundary.create(db, identity(uid), proposal, failure).commit()

    /** Trusted verified-effect fixture follows the actual initiation helper and exact source
     * proof, action track, command binding, rule fingerprint and future deadline contract. */
    private fun start(db: SQLiteDatabase, uid: String, definition: BackgroundProcessDefinition = rule):
        Pair<BackgroundProcessChange, CanonicalCampaignMutationProposal> {
        val before = Phase60TemporalStateStore(db, campaign).read()
        val at = WorldTimeTick(before.time.milliseconds + 1000)
        val processUid = "P64:DEVICE:PROCESS:$uid"
        val hash = phase63Hash(Phase64BackgroundCodec.definition(definition).toString())
        val proof = "${Phase64ProcessActivation.START_PROOF}$hash:DEVICE:$uid"
        val effect = VerifiedMechanicsCommandEffect("E:$uid", "NODE:$uid", "UNIVERSAL_ACTION", "INTERACTION", player, 1,
            mapOf("track_uid" to "ACTION:P64_START:$processUid", "magnitude" to "1",
                "p64_start_rule" to definition.uid, "p64_start_version" to definition.version.toString(), "p64_start_fingerprint" to hash,
                "p64_start_process" to processUid, "p64_start_target_kind" to "ITEM_INSTANCE", "p64_start_target_uid" to itemUid),
            proof, phase63Hash("DEVICE:INPUT:$uid"), phase63Hash("DEVICE:OUTPUT:$uid"))
        val change = Phase64ProcessActivation.start(temporalScope(db), identity(uid).commandUid, definition, effect, at)
        val deadline = WorldProcessDeadline(Phase64BackgroundProcessOwner.deadline(change.process), Phase64BackgroundProcessOwner.OWNER, change.process.due)
        val clock = TemporalStateChange(campaign, before.version, before.time, at,
            Phase60ProcessStateCodec.encode(before.processStates), Phase60DeadlineCodec.encode(before.deadlines + deadline))
        val order = (TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0) + 1
        return change to admit(uid, order, clock, listOf(change), listOf(effect))
    }

    private fun settle(db: SQLiteDatabase, file: File, processUid: String, uid: String): CanonicalCampaignMutationProposal {
        val before = Phase60TemporalStateStore(db, campaign).read()
        val store = Phase64BackgroundStore(db, campaign)
        val process = requireNotNull(store.process(processUid))
        val scope = BackgroundProcessEvaluationScope(temporalScope(db), "DEVICE-WORLD-SEED", requireNotNull(store.policy()))
        val reads = Phase64ProductionReads(scope,
            { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) },
            { temporalScope(db) }, process.due, { _, _, _ -> null })
        val owner = Phase64BackgroundProcessOwner(scope, listOf(process), mapOf((rule.uid to rule.version) to rule),
            { store.process(it) }, reads, listOf(Phase64EconomyProjectsAdapter()))
        val outcome = owner.evaluate(TemporalOwnerInput(scope.temporal, before.time, process.due, emptyList(), before.deadlines,
            before.processStates.singleOrNull { it.ownerUid == owner.ownerUid }))
        assertTrue(outcome.toString(), outcome is TemporalOwnerResult.Evaluated)
        val evaluated = outcome as TemporalOwnerResult.Evaluated
        assertEquals(BackgroundProcessStatus.COMPLETED, evaluated.changes.filterIsInstance<BackgroundProcessChange>().single().process.status)
        assertEquals(-1L, evaluated.changes.filterIsInstance<InventoryChange>().single().quantityDelta.units)
        val clock = TemporalStateChange(campaign, before.version, before.time, process.due,
            Phase60ProcessStateCodec.encode(before.processStates.filterNot { it.ownerUid == owner.ownerUid } + evaluated.state),
            Phase60DeadlineCodec.encode(before.deadlines.filterNot { it.uid == Phase64BackgroundProcessOwner.deadline(process) } + evaluated.nextDeadlines))
        return admit(uid, 2, clock, evaluated.changes, evaluated.mechanicalEffects)
    }

    @Test fun additiveMigrationKeepsLegacyDigestAndReadsNeverActivateProcesses() = SQLiteDatabase.create(null).use { db ->
        calendar(db)
        val before = AuthoritativeStateDigest.compute(db)
        assertNull(Phase64BackgroundStore(db, campaign).policy())
        Phase64BackgroundSchema.ensureReady(db)
        assertEquals(before, AuthoritativeStateDigest.compute(db))
        val store = Phase64BackgroundStore(db, campaign)
        assertNull(store.policy())
        assertTrue(store.due(WorldTimeTick(Long.MAX_VALUE)).isEmpty())
        assertNull(store.activation(actionUid))
        assertTrue(store.publicActivations().isEmpty())
        assertTrue(store.playerNotices(player,1).isEmpty())
        assertEquals(before, AuthoritativeStateDigest.compute(db))
        Phase64BackgroundSchema.tables.forEach { table ->
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { assertTrue(it.moveToFirst()); assertEquals(table, 0L, it.getLong(0)) }
        }
        GameplayRuntimeBootstrap.initialize(db, campaign)
        val migrated = AuthoritativeStateDigest.compute(db)
        GameplayRuntimeBootstrap.initialize(db, campaign)
        GameplayRuntimeBootstrap.requireReady(db, campaign)
        assertNull(store.policy())
        assertEquals(migrated, AuthoritativeStateDigest.compute(db))
    }

    @Test fun explicitPackAndNativeBootstrapSharePolicyButDoNotImportNarutoOrStartAnything() {
        val fingerprints = mutableSetOf<String>()
        for (source in listOf(CampaignRuleSourceKind.WORLD_PACK, CampaignRuleSourceKind.CAMPAIGN_NATIVE)) {
            SQLiteDatabase.create(null).use { db ->
                calendar(db)
                GameplayRuntimeBootstrap.initialize(db, campaign)
                SQLiteDatabase.create(null).use { world ->
                    world.execSQL("CREATE TABLE background_process_definitions(definition_uid TEXT,definition_version INTEGER,canonical TEXT)")
                    world.execSQL("INSERT INTO background_process_definitions VALUES(?,?,?)",
                        arrayOf(rule.uid, rule.version, Phase64BackgroundCodec.definition(rule).toString()))
                    val selected = WorldPackRuleBinding("P64:DEVICE:SOURCE", "1", source)
                    withAdministrativeMutationAuthority(db, campaign) {
                        Phase64NewCampaignBootstrap.initialize(db, campaign, selected,
                            world.takeIf { source == CampaignRuleSourceKind.WORLD_PACK })
                    }
                    val store = Phase64BackgroundStore(db, campaign)
                    fingerprints += requireNotNull(store.policy())
                    assertTrue(store.due(WorldTimeTick(Long.MAX_VALUE)).isEmpty())
                    assertEquals(Phase64NewCampaignBootstrap.coreDefinitions().map { it.domain }.toSet(), BackgroundProcessDefinition.DOMAINS)
                    assertTrue(Phase64NewCampaignBootstrap.coreDefinitions().none {
                        Phase64BackgroundCodec.definition(it).toString().contains("NARUTO", ignoreCase = true) ||
                            Phase64BackgroundCodec.definition(it).toString().contains("CHAKRA", ignoreCase = true)
                    })
                    if (source == CampaignRuleSourceKind.WORLD_PACK) {
                        assertEquals(rule, store.definition(rule.uid, rule.version))
                        assertEquals(rule, store.activation(actionUid))
                    } else {
                        assertNull(store.definition(rule.uid, rule.version))
                        assertNull(store.activation(actionUid))
                    }
                    val digest = AuthoritativeStateDigest.compute(db)
                    assertTrue(runCatching { withAdministrativeMutationAuthority(db, campaign) {
                        Phase64NewCampaignBootstrap.initialize(db, campaign, selected)
                    } }.isFailure)
                    assertEquals(digest, AuthoritativeStateDigest.compute(db))
                }
            }
        }
        assertEquals(2, fingerprints.size) // the authoritative policy binds the selected source
    }

    @Test fun missingPolicyAndMissingInitiationEvidenceFailClosedWithNoWrites() = SQLiteDatabase.create(null).use { db ->
        setup(db, enabled = false)
        val (_, proposal) = start(db, "DISABLED")
        val before = AuthoritativeStateDigest.compute(db)
        assertTrue(runCatching { commit(db, "DISABLED", proposal) }.isFailure)
        assertEquals(before, AuthoritativeStateDigest.compute(db))
        assertNull(TurnTransactionReceiptStore(db).committedCommand(campaign, "CMD:DISABLED"))
        withAdministrativeMutationAuthority(db, campaign) { Phase64BackgroundStore(db, campaign).initializeNew(binding, listOf(rule)) }
        val (change, legalProposal) = start(db, "MISSING_PROOF")
        val clock = legalProposal.playerChangeSet.changes.mapNotNull { it.payload as? TemporalStateChange }.single()
        val withoutTrack = admit("MISSING_PROOF", 1, clock, listOf(change))
        val enabled = AuthoritativeStateDigest.compute(db)
        assertTrue(runCatching { commit(db, "MISSING_PROOF", withoutTrack) }.isFailure)
        assertEquals(enabled, AuthoritativeStateDigest.compute(db))
        assertNull(Phase64BackgroundStore(db, campaign).process(change.process.uid))
        val registry = TypedPlayerChangeRegistry.core()
        val wire = registry.encodeWorkerPayload(change)
        assertEquals(change, registry.decodeWorkerPayload(wire))
        val extended = JsonObject(wire.getValue("payload").jsonObject + ("unapproved_field" to JsonPrimitive(true)))
        assertTrue(runCatching { registry.decodeWorkerPayload(JsonObject(wire + ("payload" to extended))) }.isFailure)
    }

    @Test fun productionAuthorizationHonorsStagedValidityAndDoesNotRemoveCurrentGrantEarly() = SQLiteDatabase.create(null).use { db ->
        setup(db)
        val target=DomainRef("ITEM_INSTANCE",itemUid)
        val before=AuthoritativeStateDigest.compute(db)
        val future=AccessAuthorityChange(AccessOperation.REVOKE_GRANT,"FUTURE_REVOKE",player.kindUid,player.uid,
            AccessGrantKind.WORLD_RULE.name,Phase64EconomyOperations.CONSUME,target.kindUid,target.uid,validFromOrder=2)
        assertTrue(phase64Authorize(db,campaign,player,Phase64EconomyOperations.CONSUME,listOf(target),0,listOf(future)))
        assertFalse(phase64Authorize(db,campaign,player,Phase64EconomyOperations.CONSUME,listOf(target),0,
            listOf(future.copy(validFromOrder=1))))
        val expired=future.copy(operation=AccessOperation.GRANT,recordUid="EXPIRED_GRANT",validFromOrder=0,validUntilOrder=0)
        assertTrue(phase64Authorize(db,campaign,player,Phase64EconomyOperations.CONSUME,listOf(target),0,listOf(expired)))
        assertEquals(before,AuthoritativeStateDigest.compute(db))
    }

    @Test fun realOwnerPortsCommitProjectMessageAndAgeWhileMissingDeliveryRouteStaysBlockedThenUndoReplaysStarts() {
        val file = File(folder.root, "phase64-owners.db")
        val snapshots = File(folder.root, "owner-snapshots")
        val recipient = DomainRef("NPC", "P64:DEVICE:RECIPIENT")
        val destination = DomainRef("PLACE", "P64:DEVICE:DESTINATION")
        val projectUid = "P64:DEVICE:PROJECT"
        val labourUid = phase64MechanicalResource(player, "WORK").uid
        fun definition(uid: String, domain: String, operation: String, parameters: Map<String, String>) =
            BackgroundProcessDefinition(uid, 1, domain, operation, 10_000, parameters = parameters + mapOf(
                Phase64ProcessActivation.ACTION_KEY to "ACTION:$uid", Phase64ProcessActivation.PUBLIC_KEY to "true"))
        val project = definition("P64:DEVICE:RESEARCH", "PROJECT", Phase64EconomyOperations.RESEARCH,
            mapOf("projectUid" to projectUid, "labourResourceUid" to labourUid, "labourUnits" to "1", "progressUnits" to "1"))
        val message = definition("P64:DEVICE:MESSAGE", "INFORMATION", "MESSAGE", mapOf(
            "recipient_kind" to recipient.kindUid, "recipient_uid" to recipient.uid,
            "message_uid" to "P64:DEVICE:MESSAGE:1", "message_text" to "The bridge is open.",
            "channel_uid" to "P64:DEVICE:CHANNEL", "disclosure_policy_uid" to "P64:DEVICE:DISCLOSURE", "delay_ms" to "1000"))
        val age = definition("P64:DEVICE:AGE", "POPULATION", "AGE", emptyMap())
        val delivery = definition("P64:DEVICE:DELIVERY", "ECONOMY", Phase64EconomyOperations.DELIVER, mapOf(
            "inputItemUids" to itemUid, "recipientKind" to recipient.kindUid, "recipientUid" to recipient.uid,
            "destinationKind" to destination.kindUid, "destinationUid" to destination.uid, "routeUid" to "P64:DEVICE:ABSENT_ROUTE"))
        val definitions = listOf(project, message, age, delivery)
        var db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            setup(db, definitions = definitions, resources = listOf(MechanicalResource("WORK", 2, 2)))
            withAdministrativeMutationAuthority(db, campaign) {
                // PLAYER capacity is owned by Phase4, not the creation-time Phase50 mirror.
                StatResourceStore(db, campaign).apply {
                    registerResourceDefinitions(binding.worldPackUid,
                        listOf(ResourceDefinition("WORK", "work", "CAPACITY", minValue = 0.0,
                            maxValue = 2.0, worldPackUid = binding.worldPackUid)))
                    savePlayerResource(PlayerResource(campaign, player.uid, "WORK", 2.0))
                }
                MechanicalActorStateStore(db, campaign).materializeIfMissing(MechanicalActorSeed(
                    recipient, MechanicalActorKind.NPC, "DEVICE", "DEVICE-RECIPIENT", "DEVICE-BOOTSTRAP",
                    mapOf("DEFENCE" to 10), listOf(MechanicalResource("HEALTH", 10, 10)), setOf("INTERACTION")))
                CampaignTruthStore(db, campaign).record(TruthKind.FACT, "KIND",
                    Provenance(ProvenanceSourceType.WORLD_CANON, sourceId = "DEVICE-BOOTSTRAP", verified = true),
                    subjectUid = destination.uid, objectValue = destination.kindUid,
                    truthUid = "P64:DEVICE:DESTINATION:FACT", createdAt = 0)
                val projects = DevelopmentProjectStore(db, campaign)
                projects.registerProjectType(ProjectTypeDefinition(PROJECT_TYPE_RESEARCH, "RESEARCH", provenance = "RPGOS-15 core generic project type"))
                OwnershipReferenceRegistry(db, campaign).apply {
                    registerOwnerKind(player.kindUid, "DEVICE-BOOTSTRAP")
                    registerOwner(OwnershipOwnerRef(player.kindUid, player.uid), "DEVICE-BOOTSTRAP")
                }
                projects.createProject(DevelopmentProject(campaign, projectUid, PROJECT_TYPE_RESEARCH,
                    OwnershipOwnerRef(player.kindUid, player.uid), title = "Bridge research", objectiveSummary = "Inspect the bridge",
                    targetDomainUid = "WORLD", progressCapUnits = 2, createdOrder = 0, provenance = "DEVICE-BOOTSTRAP"), "P64:DEVICE:PROJECT:IDEA")
                // Seed a legal lifecycle; production guards deliberately reject IDEA -> ACTIVE_WORK.
                projects.changeStatus(ProjectStatusEvent(campaign, "P64:DEVICE:PROJECT:REQUIREMENTS", projectUid,
                    ProjectStatus.REQUIREMENTS, 1, provenance = "DEVICE-BOOTSTRAP"))
                projects.changeStatus(ProjectStatusEvent(campaign, "P64:DEVICE:PROJECT:PROTOTYPE", projectUid,
                    ProjectStatus.PROTOTYPE, 2, provenance = "DEVICE-BOOTSTRAP"))
                projects.changeStatus(ProjectStatusEvent(campaign, "P64:DEVICE:PROJECT:ACTIVE", projectUid,
                    ProjectStatus.ACTIVE_WORK, 3, provenance = "DEVICE-BOOTSTRAP"))
                listOf(player to Phase64EconomyOperations.RESEARCH, player to "P64:INFO_SEND:MESSAGE",
                    player to Phase64PopulationConflictsAdapter.POPULATION_AGE, recipient to "P64:INFO_RECEIVE").forEachIndexed { index, (principal, purpose) ->
                    val grant = "P64:DEVICE:OWNER_GRANT:$index"
                    AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), grant,
                        AccessAuthorityChange(AccessOperation.GRANT, grant, principal.kindUid, principal.uid,
                            AccessGrantKind.WORLD_RULE.name, purpose, validFromOrder = 0), 0)
                }
                val messageAnchor=DomainRef("LOCATION","P64:DEVICE:MESSAGE_ANCHOR")
                val channel=DomainRef("INFORMATION_CHANNEL","P64:DEVICE:CHANNEL")
                val disclosure=DomainRef("DISCLOSURE_POLICY","P64:DEVICE:DISCLOSURE")
                listOf(messageAnchor,channel,disclosure).forEach { ref->
                    CampaignTruthStore(db,campaign).record(TruthKind.FACT,CampaignWorldFacts.KIND,
                        Provenance(ProvenanceSourceType.WORLD_CANON,sourceId="DEVICE-BOOTSTRAP",verified=true),
                        subjectUid=ref.uid,objectValue=ref.kindUid,truthUid="KIND:${ref.uid}",createdAt=0)
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS entity_positions(entity_uid TEXT PRIMARY KEY,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
                listOf(player,recipient).forEach { ref->db.execSQL("INSERT INTO entity_positions VALUES(?,?,0,0,0,0)",arrayOf(ref.uid,messageAnchor.uid)) }
                listOf(player to "P64:INFO_SEND:MESSAGE",recipient to "P64:INFO_RECEIVE").forEach { (principal,purpose)->
                    listOf(channel,disclosure).forEach { ref->
                        val uid="P64:DEVICE:EXACT:${principal.uid}:${ref.uid}"
                        AccessAuthorityStore(db,campaign).apply(identity("BOOTSTRAP"),uid,
                            AccessAuthorityChange(AccessOperation.GRANT,uid,principal.kindUid,principal.uid,AccessGrantKind.EXPLICIT.name,
                                purpose,ref.kindUid,ref.uid,validFromOrder=0),0)
                    }
                    CarrierAccessStage.entries.forEach { stage->
                        val uid="P64:DEVICE:STAGE:${principal.uid}:${stage.name}"
                        AccessAuthorityStore(db,campaign).apply(identity("BOOTSTRAP"),uid,
                            AccessAuthorityChange(AccessOperation.SET_CARRIER_ACCESS,uid,principal.kindUid,principal.uid,AccessGrantKind.EXPLICIT.name,
                                stage.name,channel.kindUid,channel.uid,validFromOrder=0),0)
                    }
                }
            }
            CampaignSnapshotManager(db, campaign, snapshots).create(SnapshotKind.UNDO_BASELINE, true)
            val starts = definitions.map { rule ->
                val uid = "START:${rule.domain}:${rule.operation}"
                val (change, proposal) = start(db, uid, rule)
                assertTrue(commit(db, uid, proposal) is TurnExecutionResult.Committed)
                change.process
            }
            val startedDigest = AuthoritativeStateDigest.compute(db)
            val store = Phase64BackgroundStore(db, campaign)
            val before = Phase60TemporalStateStore(db, campaign).read()
            val through = starts.maxOf { it.due }
            val scope = BackgroundProcessEvaluationScope(temporalScope(db), "DEVICE-WORLD-SEED", requireNotNull(store.policy()))
            val reads = Phase64ProductionReads(scope,
                { SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY) },
                { temporalScope(db) }, through, { _, _, _ -> null })
            val owner = Phase64BackgroundProcessOwner(scope, store.due(through), definitions.associateBy { it.uid to it.version },
                { store.process(it) }, reads, listOf(Phase64EconomyProjectsAdapter(),
                    Phase64OrganizationsInformationAdapter(), Phase64PopulationConflictsAdapter()))
            val result = owner.evaluate(TemporalOwnerInput(scope.temporal, before.time, through, emptyList(), before.deadlines,
                before.processStates.singleOrNull { it.ownerUid == owner.ownerUid }))
            assertTrue(result.toString(), result is TemporalOwnerResult.Evaluated)
            val evaluated = result as TemporalOwnerResult.Evaluated
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db)) // evaluation is read only
            val outcomes = evaluated.changes.filterIsInstance<BackgroundProcessChange>().associateBy { it.process.definitionUid }
            assertEquals(outcomes.getValue(project.uid).process.reasonUid,
                BackgroundProcessStatus.COMPLETED, outcomes.getValue(project.uid).process.status)
            assertEquals(1L, evaluated.changes.filterIsInstance<BackgroundProjectWorkChange>().single().progressUnits)
            assertEquals(ResourceChange(player,"WORK",ExactLongDelta.of(-1)),evaluated.changes.filterIsInstance<ResourceChange>().single())
            assertEquals(KnowledgeEpistemicState.BELIEVED, evaluated.changes.filterIsInstance<KnowledgeAcquisitionChange>().single().acquisition.epistemicState)
            assertEquals(10_000L, evaluated.changes.filterIsInstance<MechanicalTrackChange>().single().delta.units)
            assertTrue(evaluated.changes.none { it is InventoryChange || it is SpatialChange || it is DevelopmentProjectCompletionChange })
            listOf(project, message, age).forEach { assertEquals(BackgroundProcessStatus.COMPLETED, outcomes.getValue(it.uid).process.status) }
            assertEquals(BackgroundProcessStatus.BLOCKED, outcomes.getValue(delivery.uid).process.status)
            assertEquals("P64:DELIVERY_ROUTE_UNAVAILABLE", outcomes.getValue(delivery.uid).process.reasonUid)
            assertEquals(listOf(outcomes.getValue(delivery.uid).process.due), evaluated.nextDeadlines.map { it.due })
            val removed = starts.map(Phase64BackgroundProcessOwner::deadline).toSet()
            val clock = TemporalStateChange(campaign, before.version, before.time, through,
                Phase60ProcessStateCodec.encode(before.processStates.filterNot { it.ownerUid == owner.ownerUid } + evaluated.state),
                Phase60DeadlineCodec.encode(before.deadlines.filterNot { it.uid in removed } + evaluated.nextDeadlines))
            val proposal = admit("OWNERS_SETTLE", 5, clock, evaluated.changes, evaluated.mechanicalEffects)
            assertTrue(commit(db, "OWNERS_SETTLE", proposal) is TurnExecutionResult.Committed)
            val committedDigest = AuthoritativeStateDigest.compute(db)
            assertTrue(commit(db, "OWNERS_SETTLE", proposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(committedDigest, AuthoritativeStateDigest.compute(db))
            val holder = KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER, recipient.uid, campaign)
            assertEquals(1, KnowledgeStore(db, campaign).acquisitions(holder).size)
            assertEquals(KnowledgeEpistemicState.BELIEVED, KnowledgeStore(db, campaign).states(holder).single().epistemicState)
            assertEquals(1L, DevelopmentProjectStore(db, campaign).progress(projectUid).progressUnits)
            assertEquals(1L, DevelopmentProjectStore(db, campaign).historyCount(projectUid))
            assertEquals(1.0,StatResourceStore(db,campaign).playerResources(player.uid).single { it.resourceUid=="WORK" }.currentValue,0.0)
            assertEquals(ProjectStatus.ACTIVE_WORK, DevelopmentProjectStore(db, campaign).currentStatus(projectUid))
            assertEquals(1, InventoryStore(db, campaign).typedUnique(player.uid).size)
            assertEquals(listOf(1L, 2L, 3L, 4L, 5L), CommittedReplayPayloadStore(db).after(campaign, 0).map { it.commitOrder })

            db.close()
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db, campaign)
            assertEquals(committedDigest, AuthoritativeStateDigest.compute(db)) // reopen, not OS process death
            val undo = DestructiveTurnUndoCoordinator(db, campaign, snapshots, file)
            val preview = undo.previewLastTurn()
            assertTrue(preview.toString(), preview.canConfirm)
            assertTrue(undo.confirm(preview) is DestructiveUndoResult.Completed)
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))
            assertTrue(KnowledgeStore(db, campaign).acquisitions(holder).isEmpty())
            assertEquals(0L, DevelopmentProjectStore(db, campaign).progress(projectUid).progressUnits)
            assertEquals(2.0,StatResourceStore(db,campaign).playerResources(player.uid).single { it.resourceUid=="WORK" }.currentValue,0.0)
            starts.forEach { assertEquals(BackgroundProcessStatus.ACTIVE, Phase64BackgroundStore(db, campaign).process(it.uid)!!.status) }
            assertNull(TurnTransactionReceiptStore(db).committedCommand(campaign, "CMD:OWNERS_SETTLE"))
            assertTrue(runCatching { reads.available(phase64InventoryResource(player, itemUid), emptyList()) }.isFailure)
            assertTrue(runCatching { commit(db, "OWNERS_SETTLE", proposal) }.isFailure)
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))
        } finally {
            if (db.isOpen) db.close()
        }
    }

    @Test fun canonicalProcessRollbackRetryCacheAnnihilationReopenPrefixReplayUndoAndAlternativeWait() {
        val file = File(folder.root, "phase64.db")
        val snapshots = File(folder.root, "snapshots")
        var db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            setup(db)
            CampaignSnapshotManager(db, campaign, snapshots).create(SnapshotKind.UNDO_BASELINE, true)
            val (startChange, startProposal) = start(db, "START")
            val baseline = AuthoritativeStateDigest.compute(db)
            assertTrue(runCatching { commit(db, "START", startProposal, TurnFailureInjector {
                if (it == TurnFailurePoint.AFTER_SECOND_DOMAIN_WRITE) error("DEVICE:INJECTED_START")
            }) }.isFailure)
            assertEquals(baseline, AuthoritativeStateDigest.compute(db))
            assertNull(Phase64BackgroundStore(db, campaign).process(startChange.process.uid))
            assertTrue(commit(db, "START", startProposal) is TurnExecutionResult.Committed)
            val startedDigest = AuthoritativeStateDigest.compute(db)
            val projection=Phase64BackgroundStore(db,campaign).playerProcessProjection(temporalScope(db),player)
            assertEquals(listOf(startChange.process.uid),projection.rows.map { it.processRef.uid })
            assertTrue(Phase64BackgroundStore(db,campaign).playerProcessProjection(temporalScope(db),DomainRef("PLAYER","OTHER")).rows.isEmpty())
            assertEquals(startedDigest,AuthoritativeStateDigest.compute(db))
            assertTrue(commit(db, "START", startProposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))

            val event = CampaignEventStore(db, campaign).eventsForTransaction("TX:START").first()
            val leaves = listOf(MemorySourceLeafRef("EVENT", event.eventUid, event.schemaVersion.toLong(),
                requireNotNull(event.committedOrder), event.semanticFingerprint))
            MemoryArtifactStore(db).upsert(MemoryArtifactIdentity(campaign, HistoryGenerationStore(db, campaign).current(),
                "DEVICE:DERIVED", "DEVICE:DERIVED:1", MemoryArtifactKind.EPISODE_INTERPRETATION,
                leaves, memoryLeafFingerprint(leaves), "DEVICE:DERIVATION", 1, 1, 1, 1), MemoryArtifactStatus.CLEAN, "{\"title\":\"derived only\"}")
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))
            listOf(Phase55To58MemorySchema.LEAVES, Phase55To58MemorySchema.DEPENDENCIES,
                Phase55To58MemorySchema.ARTIFACTS, Phase55To58MemorySchema.EPISODE_MEMBERSHIP,
                Phase55To58MemorySchema.RECEIPTS).forEach { db.delete(it, "campaign_uid=?", arrayOf(campaign)) }
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))

            val settlement = settle(db, file, startChange.process.uid, "SETTLE")
            listOf(TurnFailurePoint.BEFORE_EVENT_APPEND, TurnFailurePoint.AFTER_EVENT_APPEND,
                TurnFailurePoint.AFTER_CAUSAL_APPEND, TurnFailurePoint.BEFORE_COMMIT,
                TurnFailurePoint.AFTER_RECEIPT_BEFORE_COMMIT).forEach { point ->
                var injected = false
                assertTrue(point.name, runCatching { commit(db, "SETTLE", settlement, TurnFailureInjector {
                    if (it == point) { injected = true; error("DEVICE:INJECTED_SETTLEMENT:$point") }
                }) }.isFailure)
                assertTrue("Failure point was not reached: $point", injected)
                assertEquals(point.name, startedDigest, AuthoritativeStateDigest.compute(db))
                assertEquals(1, InventoryStore(db, campaign).typedUnique(player.uid).size)
                assertEquals(BackgroundProcessStatus.ACTIVE, Phase64BackgroundStore(db, campaign).process(startChange.process.uid)!!.status)
                assertNull(TurnTransactionReceiptStore(db).committedCommand(campaign, "CMD:SETTLE"))
                assertTrue(CampaignEventStore(db, campaign).eventsForTransaction("TX:SETTLE").isEmpty())
                assertTrue(CampaignCausalGraph(db, campaign).relationsForTransaction("TX:SETTLE").isEmpty())
                assertEquals(listOf(1L), CommittedReplayPayloadStore(db).after(campaign, 0).map { it.commitOrder })
            }
            assertTrue(commit(db, "SETTLE", settlement) is TurnExecutionResult.Committed)
            val ownNotices=Phase64BackgroundStore(db,campaign).playerNotices(player,2)
            assertEquals(1,ownNotices.size)
            assertTrue(ownNotices.single().contains("rozliczono zakończenie"))
            assertTrue(Phase64BackgroundStore(db,campaign).playerNotices(DomainRef("PLAYER","ANOTHER_PLAYER"),2).isEmpty())
            val settledDigest = AuthoritativeStateDigest.compute(db)
            assertTrue(commit(db, "SETTLE", settlement) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(settledDigest, AuthoritativeStateDigest.compute(db))
            assertTrue(InventoryStore(db, campaign).typedUnique(player.uid).isEmpty())
            assertEquals(BackgroundProcessStatus.COMPLETED, Phase64BackgroundStore(db, campaign).process(startChange.process.uid)!!.status)
            val replay = CommittedReplayPayloadStore(db).after(campaign, 0)
            assertEquals(listOf(1L, 2L), replay.map { it.commitOrder })
            assertEquals(1, replay.last().changeSet.changes.count { it.payload is InventoryChange })

            db.close()
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            GameplayRuntimeBootstrap.requireReady(db, campaign)
            assertEquals(settledDigest, AuthoritativeStateDigest.compute(db))
            val undo = DestructiveTurnUndoCoordinator(db, campaign, snapshots, file)
            val preview = undo.previewLastTurn()
            assertTrue(preview.toString(), preview.canConfirm)
            val result = undo.confirm(preview)
            assertTrue(result.toString(), result is DestructiveUndoResult.Completed)
            db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db)) // replayed prefix includes initiation track + process
            assertEquals(1, InventoryStore(db, campaign).typedUnique(player.uid).size)
            assertEquals(BackgroundProcessStatus.ACTIVE, Phase64BackgroundStore(db, campaign).process(startChange.process.uid)!!.status)
            assertTrue(Phase64BackgroundStore(db,campaign).playerNotices(player,2).isEmpty())
            assertNull(TurnTransactionReceiptStore(db).committedCommand(campaign, "CMD:SETTLE"))
            assertTrue(runCatching { commit(db, "SETTLE", settlement) }.isFailure) // old generation cannot resurrect a removed future
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))

            val before = Phase60TemporalStateStore(db, campaign).read()
            val shortWait = VerifiedMechanicsCommandEffect("E:SHORT_WAIT", "NODE:SHORT_WAIT", "UNIVERSAL_ACTION", "INTERACTION", player, 1,
                mapOf("track_uid" to "ACTION:WAIT", "magnitude" to "1"), "DEVICE:VERIFIED:SHORT_WAIT", "DEVICE:WAIT:INPUT", "DEVICE:WAIT:OUTPUT")
            val alternative = admit("SHORT_WAIT", 2, TemporalStateChange(campaign, before.version, before.time,
                WorldTimeTick(before.time.milliseconds + 500), Phase60ProcessStateCodec.encode(before.processStates),
                Phase60DeadlineCodec.encode(before.deadlines)), effects = listOf(shortWait))
            assertTrue(commit(db, "SHORT_WAIT", alternative) is TurnExecutionResult.Committed)
            assertEquals(1500L, Phase60TemporalStateStore(db, campaign).read().time.milliseconds)
            assertEquals(1, InventoryStore(db, campaign).typedUnique(player.uid).size)
            assertEquals(BackgroundProcessStatus.ACTIVE, Phase64BackgroundStore(db, campaign).process(startChange.process.uid)!!.status)
        } finally {
            if (db.isOpen) db.close()
        }
    }
}
