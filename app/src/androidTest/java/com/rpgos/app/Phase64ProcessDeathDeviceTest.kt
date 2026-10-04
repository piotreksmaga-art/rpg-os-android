package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Host-driven gate, not an ordinary run of this whole class. Invoke #seedPendingProcess,
 * force-stop the isolated target package, then invoke #resumePendingProcessAfterProcessDeath
 * in a second instrumentation process. No model, host connection or UI is involved.
 * The host must independently record the force-stop; a changed PID alone does not prove it. */
@RunWith(AndroidJUnit4::class)
class Phase64ProcessDeathDeviceTest {
    private val appContext: Context = ApplicationProvider.getApplicationContext()
    private val fixtureRunUid get() = requireNotNull(InstrumentationRegistry.getArguments().getString("phase64RunUid", "v1")).also {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "P64:FIXTURE_RUN_UID_INVALID" }
    }
    private val fixtureDirectory get() = File(appContext.filesDir, "phase64-process-death-device-$fixtureRunUid")
    private val dbFile get() = File(fixtureDirectory, "campaign.db")
    private val markerFile get() = File(fixtureDirectory, "pending.properties")
    private val snapshots get() = File(fixtureDirectory, "snapshots")
    private val campaign = "P64:PROCESS_DEATH:CAMPAIGN"
    private val player = DomainRef("PLAYER", "P64:PROCESS_DEATH:PLAYER")
    private val actor = CommandActorRef(player.kindUid, player.uid)
    private val binding = WorldPackRuleBinding("P64:PROCESS_DEATH:PACK", "1")
    private val itemUid = "P64:PROCESS_DEATH:MATERIAL"
    private val processUid = "P64:PROCESS_DEATH:PROCESS:START"
    private val rule = BackgroundProcessDefinition("P64:PROCESS_DEATH:CONSUME", 1, "ECONOMY",
        Phase64EconomyOperations.CONSUME, 1000, parameters = mapOf("inputItemUids" to itemUid,
            "sourceUid" to "P64:PROCESS_DEATH:CONSUMPTION_POLICY",
            Phase64ProcessActivation.ACTION_KEY to "P64:PROCESS_DEATH:CONSUME_ACTION",
            Phase64ProcessActivation.PUBLIC_KEY to "true"))

    private fun isolatedFixture() {
        check(appContext.packageName.startsWith("com.rpgos.app.")) {
            "P64:PROCESS_DEATH_ISOLATED_TARGET_REQUIRED: use the acceptance applicationIdSuffix, never the user's app"
        }
        check(fixtureDirectory.canonicalFile.parentFile == appContext.filesDir.canonicalFile)
        check(dbFile.canonicalFile.parentFile == fixtureDirectory.canonicalFile)
        check(markerFile.canonicalFile.parentFile == fixtureDirectory.canonicalFile)
    }

    private fun identity(uid: String) = TurnTransactionIdentity(campaign, "TURN:$uid", "CMD:$uid", "TX:$uid")
    private fun temporalScope(db: SQLiteDatabase) = TemporalScope(campaign, HistoryGenerationStore(db, campaign).current().value,
        TurnTransactionReceiptStore(db).lastValidCommit(campaign)?.commitOrder ?: 0, AuthoritativeStateDigest.compute(db))
    private fun commit(db: SQLiteDatabase, uid: String, proposal: CanonicalCampaignMutationProposal) =
        TurnTransactionBoundary.create(db, identity(uid), proposal).commit()

    /** Administrative fixture input only. Outcomes below always go through admission/turn commit. */
    private fun setup(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE campaign_calendar(id INTEGER PRIMARY KEY,absolute_day INTEGER,hour INTEGER,minute INTEGER)")
        db.execSQL("INSERT INTO campaign_calendar VALUES(1,0,0,0)")
        GameplayRuntimeBootstrap.initialize(db, campaign)
        withAdministrativeMutationAuthority(db, campaign) {
            MechanicalActorStateStore(db, campaign).materializeIfMissing(MechanicalActorSeed(player,
                MechanicalActorKind.ACTIVE_PLAYER, "PROCESS-DEATH", "PROCESS-DEATH-SEED", "PROCESS-DEATH-BOOTSTRAP",
                mapOf("DEFENCE" to 10), listOf(MechanicalResource("HEALTH", 10, 10)), setOf("INTERACTION")))
            val inventory = InventoryStore(db, campaign)
            inventory.registerDefinitions(binding.worldPackUid, listOf(ItemDefinition("P64:PROCESS_DEATH:MATERIAL_DEF",
                binding.worldPackUid, "process-death-material", "Material", storagePolicy = ItemStoragePolicy.UNIQUE_INSTANCE,
                provenance = "PROCESS-DEATH-BOOTSTRAP")))
            inventory.createInstance(ItemInstance(campaign, itemUid, "P64:PROCESS_DEATH:MATERIAL_DEF", provenance = "PROCESS-DEATH-BOOTSTRAP"))
            inventory.addUnique(player.uid, itemUid, "PROCESS-DEATH-BOOTSTRAP")
            AccessAuthorityStore(db, campaign).apply(identity("BOOTSTRAP"), "PROCESS-DEATH:GRANT",
                AccessAuthorityChange(AccessOperation.GRANT, "PROCESS-DEATH:GRANT", player.kindUid, player.uid,
                    AccessGrantKind.WORLD_RULE.name, Phase64EconomyOperations.CONSUME, "ITEM_INSTANCE", itemUid, validFromOrder = 0), 0)
            Phase64BackgroundStore(db, campaign).initializeNew(binding, listOf(rule))
        }
    }

    private fun admit(uid: String, order: Long, clock: TemporalStateChange, payloads: List<PlayerDomainChangePayload>,
        effects: List<VerifiedMechanicsCommandEffect>): CanonicalCampaignMutationProposal {
        val command = PlayerCommand(commandUid = identity(uid).commandUid, campaignUid = campaign, actor = actor,
            commandKindUid = PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload = ApplyVerifiedMechanicsCommandPayload("PLAN:$uid", effects, temporalState = clock, backgroundChanges = payloads),
            provenance = CommandProvenance("P64:PROCESS_DEATH:ACCEPTANCE"), requestedEffectiveOrder = order)
        val refs = (listOf(player, DomainRef("CAMPAIGN", campaign)) + effects.map { it.target } + payloads.flatMap(::phase64References))
            .map { CampaignScopedDomainRef(campaign, it) }.toSet()
        val engine = productionMechanicsPlayerDomainEngine(WorldRuleProviderRegistry.of(listOf(UniversalMechanicsWorldRuleProvider(binding))),
            WorldPackAuthoritySnapshot.single(campaign, binding))
        val result = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, command,
            PlayerResolutionContext.create(campaign, actor, refs, worldRuleMode = WorldRuleMode.Bound(binding)))
        assertTrue(result.toString(), result is CampaignMutationAdmission.Accepted)
        return (result as CampaignMutationAdmission.Accepted).proposal
    }

    private fun start(db: SQLiteDatabase): CanonicalCampaignMutationProposal {
        val before = Phase60TemporalStateStore(db, campaign).read()
        val at = WorldTimeTick(Math.addExact(before.time.milliseconds, 1000))
        val hash = phase63Hash(Phase64BackgroundCodec.definition(rule).toString())
        val effect = VerifiedMechanicsCommandEffect("E:START", "NODE:START", "UNIVERSAL_ACTION", "INTERACTION", player, 1,
            mapOf("track_uid" to "ACTION:P64_START:$processUid", "magnitude" to "1", "p64_start_rule" to rule.uid,
                "p64_start_version" to rule.version.toString(), "p64_start_fingerprint" to hash, "p64_start_process" to processUid,
                "p64_start_target_kind" to "ITEM_INSTANCE", "p64_start_target_uid" to itemUid),
            "${Phase64ProcessActivation.START_PROOF}$hash:PROCESS-DEATH:START", phase63Hash("INPUT:START"), phase63Hash("OUTPUT:START"))
        val change = Phase64ProcessActivation.start(temporalScope(db), identity("START").commandUid, rule, effect, at)
        val deadline = WorldProcessDeadline(Phase64BackgroundProcessOwner.deadline(change.process), Phase64BackgroundProcessOwner.OWNER, change.process.due)
        val clock = TemporalStateChange(campaign, before.version, before.time, at, Phase60ProcessStateCodec.encode(before.processStates),
            Phase60DeadlineCodec.encode(before.deadlines + deadline))
        return admit("START", 1, clock, listOf(change), listOf(effect))
    }

    private fun owner(db: SQLiteDatabase, through: WorldTimeTick): Phase64BackgroundProcessOwner {
        val store = Phase64BackgroundStore(db, campaign)
        val scope = BackgroundProcessEvaluationScope(temporalScope(db), "PROCESS-DEATH-WORLD-SEED", requireNotNull(store.policy()))
        val reads = Phase64ProductionReads(scope, { SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY) },
            { temporalScope(db) }, through, { _, _, _ -> null })
        return Phase64BackgroundProcessOwner(scope, store.due(through), mapOf((rule.uid to rule.version) to rule),
            { store.process(it) }, reads, listOf(Phase64EconomyProjectsAdapter()))
    }

    private fun settle(db: SQLiteDatabase): CanonicalCampaignMutationProposal {
        val process = requireNotNull(Phase64BackgroundStore(db, campaign).process(processUid))
        val before = Phase60TemporalStateStore(db, campaign).read()
        val owner = owner(db, process.due)
        val pendingDigest = AuthoritativeStateDigest.compute(db)
        val outcome = owner.evaluate(TemporalOwnerInput(temporalScope(db), before.time, process.due, emptyList(), before.deadlines,
            before.processStates.singleOrNull { it.ownerUid == owner.ownerUid }))
        assertTrue(outcome.toString(), outcome is TemporalOwnerResult.Evaluated)
        assertEquals(pendingDigest, AuthoritativeStateDigest.compute(db))
        val evaluated = outcome as TemporalOwnerResult.Evaluated
        assertEquals(BackgroundProcessStatus.COMPLETED, evaluated.changes.filterIsInstance<BackgroundProcessChange>().single().process.status)
        assertEquals(-1L, evaluated.changes.filterIsInstance<InventoryChange>().single().quantityDelta.units)
        val clock = TemporalStateChange(campaign, before.version, before.time, process.due,
            Phase60ProcessStateCodec.encode(before.processStates.filterNot { it.ownerUid == owner.ownerUid } + evaluated.state),
            Phase60DeadlineCodec.encode(before.deadlines.filterNot { it.uid == Phase64BackgroundProcessOwner.deadline(process) } + evaluated.nextDeadlines))
        return admit("SETTLE", 2, clock, evaluated.changes, evaluated.mechanicalEffects)
    }

    @Test fun seedPendingProcess() {
        isolatedFixture()
        // Do not silently delete an earlier fixture, and never enumerate or modify user saves.
        check(!dbFile.exists() && !markerFile.exists()) { "P64:PROCESS_DEATH_FIXTURE_ALREADY_EXISTS: reset only the dedicated test directory" }
        check(fixtureDirectory.isDirectory || fixtureDirectory.mkdirs())
        val marker = SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            setup(db)
            val baselineDigest = AuthoritativeStateDigest.compute(db)
            val baseline = CampaignSnapshotManager(db, campaign, snapshots).create(SnapshotKind.UNDO_BASELINE, true)
            assertEquals(baselineDigest, baseline.anchorAuthoritativeDigest)
            val proposal = start(db)
            assertTrue(commit(db, "START", proposal) is TurnExecutionResult.Committed)
            val startedDigest = AuthoritativeStateDigest.compute(db)
            assertTrue(commit(db, "START", proposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(startedDigest, AuthoritativeStateDigest.compute(db))
            val pending = requireNotNull(Phase64BackgroundStore(db, campaign).process(processUid))
            val clock = Phase60TemporalStateStore(db, campaign).read()
            assertEquals(BackgroundProcessStatus.ACTIVE, pending.status)
            assertEquals(1L, pending.version)
            assertEquals(WorldTimeTick(1000), clock.time)
            assertEquals(WorldTimeTick(2000), pending.due)
            assertEquals(1, InventoryStore(db, campaign).typedUnique(player.uid).size)
            assertEquals(listOf(1L), CommittedReplayPayloadStore(db).after(campaign, 0).map { it.commitOrder })
            assertEquals(1, clock.deadlines.count { it.uid == Phase64BackgroundProcessOwner.deadline(pending) })
            assertNull(TurnTransactionReceiptStore(db).committedCommand(campaign, identity("SETTLE").commandUid))
            Properties().apply {
                setProperty("schema", "1"); setProperty("package", appContext.packageName); setProperty("seed_pid", Process.myPid().toString())
                setProperty("campaign", campaign); setProperty("process_uid", processUid); setProperty("pending_digest", startedDigest)
                setProperty("baseline_uid", baseline.snapshotUid); setProperty("baseline_digest", baselineDigest)
                setProperty("history", HistoryGenerationStore(db, campaign).current().value)
            }
        }
        FileOutputStream(markerFile).use { stream -> marker.store(stream, "Phase64 isolated process-death fixture"); stream.fd.sync() }
    }

    @Test fun resumePendingProcessAfterProcessDeath() {
        isolatedFixture()
        check(dbFile.isFile && markerFile.isFile) { "P64:PROCESS_DEATH_SEED_REQUIRED" }
        val marker = Properties().apply { markerFile.inputStream().use(::load) }
        assertEquals("1", marker.getProperty("schema"))
        assertEquals(appContext.packageName, marker.getProperty("package"))
        assertEquals(campaign, marker.getProperty("campaign"))
        assertEquals(processUid, marker.getProperty("process_uid"))
        assertNotEquals("Invoke seed and resume in separate instrumentation processes, with host force-stop between", marker.getProperty("seed_pid"), Process.myPid().toString())
        val (settlement, committedDigest) = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            GameplayRuntimeBootstrap.requireReady(db, campaign)
            assertTrue(db.isDatabaseIntegrityOk)
            assertEquals(marker.getProperty("history"), HistoryGenerationStore(db, campaign).current().value)
            assertEquals(marker.getProperty("pending_digest"), AuthoritativeStateDigest.compute(db))
            val baseline = CampaignSnapshotManager(db, campaign, snapshots).list().single { it.snapshotUid == marker.getProperty("baseline_uid") }
            assertEquals(SnapshotPublicationState.VALID, baseline.state)
            assertEquals(marker.getProperty("baseline_digest"), baseline.anchorAuthoritativeDigest)
            val process = requireNotNull(Phase64BackgroundStore(db, campaign).process(processUid))
            assertEquals(BackgroundProcessStatus.ACTIVE, process.status)
            assertEquals(1L, process.version)
            assertEquals(WorldTimeTick(1000), Phase60TemporalStateStore(db, campaign).read().time)
            assertEquals(1, InventoryStore(db, campaign).typedUnique(player.uid).size)
            assertNull(TurnTransactionReceiptStore(db).committedCommand(campaign, identity("SETTLE").commandUid))
            val proposal = settle(db)
            assertEquals(marker.getProperty("pending_digest"), AuthoritativeStateDigest.compute(db))
            assertTrue(commit(db, "SETTLE", proposal) is TurnExecutionResult.Committed)
            val digest = AuthoritativeStateDigest.compute(db)
            assertTrue(commit(db, "SETTLE", proposal) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(digest, AuthoritativeStateDigest.compute(db))
            assertCompletedOnce(db)
            proposal to digest
        }
        SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            GameplayRuntimeBootstrap.requireReady(db, campaign)
            assertEquals(committedDigest, AuthoritativeStateDigest.compute(db))
            assertTrue(commit(db, "SETTLE", settlement) is TurnExecutionResult.AlreadyCommitted)
            assertEquals(committedDigest, AuthoritativeStateDigest.compute(db))
            assertCompletedOnce(db)
            val clock = Phase60TemporalStateStore(db, campaign).read()
            val worker = owner(db, clock.time)
            val again = worker.evaluate(TemporalOwnerInput(temporalScope(db), clock.time, clock.time, emptyList(), clock.deadlines,
                clock.processStates.singleOrNull { it.ownerUid == worker.ownerUid })) as TemporalOwnerResult.Evaluated
            assertTrue(again.changes.isEmpty())
            assertTrue(again.mechanicalEffects.isEmpty())
            assertTrue(again.nextDeadlines.isEmpty())
            assertEquals(committedDigest, AuthoritativeStateDigest.compute(db))
        }
        // Retain this isolated fixture and marker for host audit; no campaign cleanup is implied.
    }

    private fun assertCompletedOnce(db: SQLiteDatabase) {
        val store = Phase64BackgroundStore(db, campaign)
        val process = requireNotNull(store.process(processUid))
        assertEquals(BackgroundProcessStatus.COMPLETED, process.status)
        assertEquals(2L, process.version)
        assertTrue(store.due(WorldTimeTick(Long.MAX_VALUE)).isEmpty())
        assertTrue(InventoryStore(db, campaign).typedUnique(player.uid).isEmpty())
        val clock = Phase60TemporalStateStore(db, campaign).read()
        assertEquals(WorldTimeTick(2000), clock.time)
        assertTrue(clock.deadlines.none { it.uid == Phase64BackgroundProcessOwner.deadline(process) })
        val replay = CommittedReplayPayloadStore(db).after(campaign, 0)
        assertEquals(listOf(1L, 2L), replay.map { it.commitOrder })
        val consumption = replay.last().changeSet.changes.mapNotNull { it.payload as? InventoryChange }.single()
        assertEquals(player, consumption.subject)
        assertEquals(itemUid, consumption.itemInstanceUid)
        assertEquals(-1L, consumption.quantityDelta.units)
        assertEquals(1, replay.last().changeSet.changes.count { it.payload is BackgroundProcessChange })
        assertNotNull(TurnTransactionReceiptStore(db).committedCommand(campaign, identity("SETTLE").commandUid))
        db.rawQuery("SELECT COUNT(*) FROM ${Phase64BackgroundSchema.EVIDENCE} WHERE campaign_uid=? AND process_uid=?", arrayOf(campaign, processUid)).use {
            assertTrue(it.moveToFirst()); assertEquals(2L, it.getLong(0))
        }
    }
}
