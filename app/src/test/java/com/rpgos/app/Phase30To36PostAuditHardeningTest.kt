package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase30To36PostAuditHardeningTest {
    private lateinit var root: File
    private lateinit var dbFile: File
    private lateinit var snapshots: File

    @Before fun setUp() {
        divergenceSpecs.clear()
        root = kotlin.io.path.createTempDirectory("p30-36-hardening-").toFile()
        dbFile = File(root, "campaign.db")
        snapshots = File(root, "snapshots")
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun sameCommandSameSemanticsNewTransactionWithCausalPlanReturnsOriginalReceipt() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db)
            val proposal = twoEventProposal("CMD-RETRY")
            val firstIdentity = TurnTransactionIdentity("C1", "TURN-A", "CMD-RETRY", "TX-A")
            val firstPlan = temporalPlan(firstIdentity)
            val first = TurnTransactionBoundary.create(db, firstIdentity, proposal, causalRelationIntents = firstPlan).commit()
            assertTrue(first is TurnExecutionResult.Committed)

            val retryIdentity = TurnTransactionIdentity("C1", "TURN-B", "CMD-RETRY", "TX-B")
            val retry = TurnTransactionBoundary.create(db, retryIdentity, proposal, causalRelationIntents = temporalPlan(retryIdentity)).commit()
            assertTrue(retry is TurnExecutionResult.AlreadyCommitted)
            retry as TurnExecutionResult.AlreadyCommitted
            assertEquals("TX-A", retry.receipt.transactionUid)
            assertEquals((first as TurnExecutionResult.Committed).receipt, retry.receipt)
            assertEquals(95L, FinancialStore(db, "C1").balance("A"))
            assertEquals(2L, countWhere(db, "canonical_gameplay_events", "transaction_uid='TX-A'"))
            assertEquals(0L, countWhere(db, "canonical_gameplay_events", "transaction_uid='TX-B'"))
            assertEquals(1L, countWhere(db, "canonical_causal_relations", "transaction_uid='TX-A'"))
            assertEquals(0L, countWhere(db, "canonical_causal_relations", "transaction_uid='TX-B'"))
            assertEquals(1L, countWhere(db, "canonical_turn_replay_payloads", "transaction_uid='TX-A'"))
            assertEquals(0L, countWhere(db, "canonical_turn_replay_payloads", "transaction_uid='TX-B'"))
            assertEquals(1L, countWhere(db, "turn_transaction_receipts", "transaction_uid='TX-A'"))
            assertEquals(0L, countWhere(db, "turn_transaction_receipts", "transaction_uid='TX-B'"))
        }
    }

    @Test fun receiptAndReplayEvidenceCannotBeForgedByRawOrAdministrativeSqlAndReadinessChecksInsertGuards() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db)
            assertTrue(runCatching { forgedReceipt(db, "FORGED-RAW") }.isFailure)
            assertTrue(runCatching { withAdministrativeMutationAuthority(db, "C1") { forgedReceipt(db, "FORGED-ADMIN") } }.isFailure)
            assertTrue(runCatching { forgedReplay(db, "FORGED-REPLAY") }.isFailure)

            val proposal = GroupATransactionTestFixtures.admittedFinancialProposal(commandUid = "REAL")
            assertTrue(TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1", "TURN-REAL", "REAL", "TX-REAL"), proposal).commit() is TurnExecutionResult.Committed)
            assertEquals(1L, countWhere(db, "turn_transaction_receipts", "transaction_uid='TX-REAL'"))
            assertEquals(1L, countWhere(db, "canonical_turn_replay_payloads", "transaction_uid='TX-REAL'"))

            db.execSQL("DROP TRIGGER rpgos_replay_commit_insert")
            val failure = runCatching { GameplayRuntimeBootstrap.requireReady(db, "C1") }.exceptionOrNull()
            assertNotNull(failure)
            assertTrue(failure!!.message.orEmpty().contains("MISSING_EVIDENCE_GUARD:rpgos_replay_commit_insert"))
        }
    }

    @Test fun commitTurnWriterContractDeclaresEveryCurrentCanonicalSinkFamily() {
        val contract = RuntimePersistentWriterRegistry.requireContract("commitTurn")
        assertEquals(PersistentWriterCapability.CANONICAL_TURN, contract.capability)
        assertEquals(RuntimePersistentWriterRegistry.canonicalTurnTargetFamilies, contract.targetFamilyUids)
        assertTrue(contract.targetFamilyUids.containsAll(setOf(
            "BASE_STATS_RESOURCES","SKILLS_TECHNIQUES","INVENTORY","EQUIPMENT_LOADOUT","OWNERSHIP_HISTORY",
            "FINANCE_AUTHORITY","CAMPAIGN_TRUTH","CANON_DIVERGENCE","DEVELOPMENT_PROJECTS",
            "EVENT_STORE","CAUSAL_GRAPH","TURN_RECEIPTS","COMMITTED_REPLAY_MATERIAL"
        )))
    }

    @Test fun lifecycleLockSerializesTurnAgainstRecoveryInBothDirections() {
        val turnEntered = CountDownLatch(1)
        val releaseTurn = CountDownLatch(1)
        val recoveryEntered = CountDownLatch(1)
        val t1 = thread(start = true) {
            CampaignRuntimeLifecycleLock.withTurn("C1") {
                turnEntered.countDown()
                releaseTurn.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(turnEntered.await(2, TimeUnit.SECONDS))
        val t2 = thread(start = true) {
            CampaignRuntimeLifecycleLock.withRecovery("C1") { recoveryEntered.countDown() }
        }
        assertFalse(recoveryEntered.await(150, TimeUnit.MILLISECONDS))
        releaseTurn.countDown()
        assertTrue(recoveryEntered.await(2, TimeUnit.SECONDS))
        t1.join(2000); t2.join(2000)

        val recoveryHeld = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val turnAfterRecovery = CountDownLatch(1)
        val r = thread(start = true) {
            CampaignRuntimeLifecycleLock.withRecovery("C1") {
                recoveryHeld.countDown()
                releaseRecovery.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue(recoveryHeld.await(2, TimeUnit.SECONDS))
        val t = thread(start = true) {
            CampaignRuntimeLifecycleLock.withTurn("C1") { turnAfterRecovery.countDown() }
        }
        assertFalse(turnAfterRecovery.await(150, TimeUnit.MILLISECONDS))
        releaseRecovery.countDown()
        assertTrue(turnAfterRecovery.await(2, TimeUnit.SECONDS))
        r.join(2000); t.join(2000)
    }

    @Test fun phase34RetentionPreservesNonEmptyCommitEvidence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GroupATransactionTestFixtures.setupFinance(db)
            val identity = TurnTransactionIdentity("C1", "TURN-HISTORY", "CMD-HISTORY", "TX-HISTORY")
            TurnTransactionBoundary.create(db, identity, twoEventProposal("CMD-HISTORY"), causalRelationIntents = temporalPlan(identity)).commit()
            val before = evidenceDigests(db)
            val manager = CampaignSnapshotManager(db, "C1", snapshots)
            repeat(8) { manager.create(SnapshotKind.AUTOMATIC) }
            assertEquals(CampaignSnapshotManager.AUTOMATIC_RETENTION,
                manager.list().count { it.kind == SnapshotKind.AUTOMATIC && !it.pinned && it.state == SnapshotPublicationState.VALID })
            assertEquals(before, evidenceDigests(db))
            assertEquals(2L, count(db, "canonical_gameplay_events"))
            assertEquals(1L, count(db, "canonical_causal_relations"))
            assertEquals(1L, count(db, "turn_transaction_receipts"))
            assertEquals(1L, count(db, "canonical_turn_replay_payloads"))
        }
    }

    @Test fun deleteFailureAndOrphanReconciliationRemainCampaignScoped() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C2")
            val c1 = CampaignSnapshotManager(db, "C1", snapshots)
            val c2 = CampaignSnapshotManager(db, "C2", snapshots)
            val s1 = c1.create(SnapshotKind.AUTOMATIC)
            val s2 = c2.create(SnapshotKind.AUTOMATIC)
            val blocked = File(s1.payloadPath)
            assertTrue(blocked.delete())
            assertTrue(blocked.mkdirs())
            File(blocked, "prevents-delete").writeText("x")
            assertFalse(c1.delete(s1.snapshotUid))
            assertNotNull(c1.list().firstOrNull { it.snapshotUid == s1.snapshotUid })
            c1.reconcileOrphans()
            assertEquals(SnapshotPublicationState.INVALID, c1.list().single { it.snapshotUid == s1.snapshotUid }.state)
            val c2row = c2.list().single { it.snapshotUid == s2.snapshotUid }
            assertEquals(SnapshotPublicationState.VALID, c2row.state)
            assertTrue(File(c2row.payloadPath).isFile)
        }
    }

    @Test fun worldPackReplacementAndRollbackCannotRewriteCommittedDivergenceTruthOrEvents() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val spec = CanonDivergenceSpec(
                "DIV-WP", CanonReference("CHARACTER", "P1", "CANON-WP-A"), "WORLD-A", "1",
                CanonDivergenceKind.OUTCOME, "CANON", "CAMPAIGN"
            )
            val proposal = divergenceProposal("CMD-WP", spec)
            TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1", "TURN-WP", "CMD-WP", "TX-WP"), proposal).commit()
            val before = listOf(
                TableDigest.compute(db, Phase35CanonDivergenceSchema.TABLE),
                TableDigest.compute(db, "campaign_truth_records"),
                TableDigest.compute(db, "canonical_gameplay_events")
            )

            val packageRoot = File(root, "worldpacks").apply { mkdirs() }
            val target = File(packageRoot, "active").apply { mkdirs(); File(this, "version").writeText("A"); File(this, "valid").writeText("yes") }
            val sourceB = File(root, "sourceB").apply { mkdirs(); File(this, "version").writeText("B"); File(this, "valid").writeText("yes") }
            val valid: (File) -> Boolean = { File(it, "valid").takeIf(File::isFile)?.readText() == "yes" }
            val preparedB = CanonicalPackageReplacement.prepareCopy(sourceB, target)
            CanonicalPackageReplacement.activatePrepared(preparedB, target, valid)
            assertEquals("B", File(target, "version").readText())
            assertEquals(before, listOf(
                TableDigest.compute(db, Phase35CanonDivergenceSchema.TABLE),
                TableDigest.compute(db, "campaign_truth_records"),
                TableDigest.compute(db, "canonical_gameplay_events")
            ))
            val row = CanonDivergenceStore(db, "C1").list().single()
            assertEquals("WORLD-A", row.spec.worldPackUid)
            assertEquals("1", row.spec.worldPackVersion)
            assertEquals("CANON", row.spec.expectedCanonicalValue)
            assertEquals("CAMPAIGN", row.spec.actualCampaignValue)

            val sourceC = File(root, "sourceC").apply { mkdirs(); File(this, "version").writeText("C"); File(this, "valid").writeText("yes") }
            val preparedC = CanonicalPackageReplacement.prepareCopy(sourceC, target)
            assertTrue(runCatching {
                CanonicalPackageReplacement.activatePrepared(preparedC, target, valid) { error("post-activation compatibility failure") }
            }.isFailure)
            assertEquals("B", File(target, "version").readText())
            assertEquals(before, listOf(
                TableDigest.compute(db, Phase35CanonDivergenceSchema.TABLE),
                TableDigest.compute(db, "campaign_truth_records"),
                TableDigest.compute(db, "canonical_gameplay_events")
            ))
        }
    }

    private fun evidenceDigests(db: SQLiteDatabase) = listOf(
        "turn_transaction_receipts","canonical_gameplay_events","canonical_causal_relations","canonical_turn_replay_payloads"
    ).associateWith { TableDigest.compute(db, it) }

    private fun forgedReceipt(db: SQLiteDatabase, tx: String) {
        db.execSQL("""INSERT INTO turn_transaction_receipts(
            transaction_uid,campaign_uid,turn_uid,command_uid,semantic_fingerprint,result_fingerprint,commit_order,
            required_event_count,required_event_manifest_fingerprint,receipt_version,commit_state)
            VALUES(?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(tx,"C1","T-$tx","C-$tx","semantic","result",99L,1,"manifest",TURN_TRANSACTION_RECEIPT_VERSION,"COMMITTED"))
    }

    private fun forgedReplay(db: SQLiteDatabase, tx: String) {
        db.execSQL("""INSERT INTO canonical_turn_replay_payloads(
            transaction_uid,campaign_uid,turn_uid,command_uid,commit_order,semantic_fingerprint,required_event_count,
            required_event_manifest_fingerprint,event_boundary_uid,replay_schema_version,player_change_set_json,causal_plan_json,payload_sha256)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            arrayOf(tx,"C1","T-$tx","C-$tx",99L,"semantic",1,"manifest",null,1,"{}","[]","digest"))
    }

    private fun temporalPlan(identity: TurnTransactionIdentity): List<CanonicalCausalRelationIntent> {
        val e = endpoints(identity)
        return listOf(CanonicalCausalRelationIntent(
            "REL-${identity.commandUid}", CausalRelationClass.TEMPORAL, CausalRelationKinds.BEFORE,
            e.first, e.second, emptyList(), emptyList(), null
        ))
    }

    private fun endpoints(identity: TurnTransactionIdentity) =
        eventUid("C1", identity.transactionUid, identity.commandUid, "EVENT-INTENT-${identity.commandUid}-A") to
            eventUid("C1", identity.transactionUid, identity.commandUid, "EVENT-INTENT-${identity.commandUid}-B")

    private fun eventUid(campaign: String, tx: String, command: String, intent: String) =
        "RPGOS-EVENT:" + sha256("$campaign|$tx|$command|$intent")

    private fun twoEventProposal(commandUid: String): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val command = PlayerCommand(
            commandUid=commandUid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload("A","B",5L,"CUR"),provenance=CommandProvenance("POST-AUDIT"),requestedEffectiveOrder=10L
        )
        val refs = setOf(
            CampaignScopedDomainRef("C1", DomainRef("PLAYER","P1")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),
            CampaignScopedDomainRef("C1", DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR"))
        )
        val context = PlayerResolutionContext.createUnboundGeneric("C1", actor, refs)
        val engine = PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TwoEventFinancialComponent())))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit("C1",engine,command,context)){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class TwoEventFinancialComponent : PlayerResolutionComponent<TransferFundsCommandPayload>(
        PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"POST-AUDIT-TWO-EVENT","1"
    ) {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val changeUid="CHANGE-${command.commandUid}-1"
            val change=PlayerDomainChange.create(changeUid,PlayerChangeKinds.FINANCIAL,
                FinancialChange(command.payload.fromAccountUid,command.payload.toAccountUid,command.payload.amountMinor,command.payload.currencyUid,"RPGOS-FIN-TYPE:TRANSFER"))
            val subject=DomainRef("PLAYER","P1")
            fun event(suffix:String,effect:String)=PlayerEventIntent.create(
                "EVENT-INTENT-${command.commandUid}-$suffix",PlayerEventIntentKinds.DOMAIN_EFFECT,subject,listOf(subject),listOf(changeUid),
                DomainEffectEventIntentPayload(subject,effect)
            )
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes=listOf(change),eventIntents=listOf(event("A","RPGOS-EFFECT:A"),event("B","RPGOS-EFFECT:B"))
            ))
        }
    }

    private fun divergenceProposal(commandUid: String, spec: CanonDivergenceSpec): CanonicalCampaignMutationProposal {
        divergenceSpecs[commandUid] = spec
        val actor=CommandActorRef("PLAYER","P1")
        val command=PlayerCommand(commandUid=commandUid,campaignUid="C1",actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("WORLD-PACK-TEST"),requestedEffectiveOrder=20)
        val refs=setOf(
            CampaignScopedDomainRef("C1",DomainRef("PLAYER","P1")),
            CampaignScopedDomainRef("C1",DomainRef("CHARACTER","P1")),
            CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),
            CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),
            CampaignScopedDomainRef("C1",DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR"))
        )
        val binding=WorldPackRuleBinding("WORLD-A","1")
        val engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(DivergenceTruthComponent())),
            worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(PostAuditCanonProvider())),
            worldPackAuthority=WorldPackAuthoritySnapshot.single("C1",binding))
        val context=PlayerResolutionContext.create("C1",actor,refs,worldRuleMode=WorldRuleMode.Bound(binding))
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit("C1",engine,command,context)){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class PostAuditCanonProvider : WorldRuleProvider("POST-AUDIT-CANON-PROVIDER","1","WORLD-A","1") {
        override fun canonicalExpectation(reference:CanonReference):CanonicalWorldExpectation? =
            if(reference.expectationUid=="CANON-WP-A") CanonicalWorldExpectation(reference,CanonDivergenceKind.OUTCOME,"CANON") else null
        override fun evaluate(request:WorldRuleRequest):WorldRuleDecision=WorldRuleDecision.Allowed.create("POST-AUDIT-CANON-RULE")
    }

    private class DivergenceTruthComponent :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS,TransferFundsCommandPayload::class,"POST-AUDIT-DIVERGENCE","1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val spec = requireNotNull(divergenceSpecs[command.commandUid]) { "missing divergence spec for ${command.commandUid}" }
            val changeUid="CHANGE-${command.commandUid}"
            val truth=CampaignTruthChange("TRUTH-${command.commandUid}",TruthKind.FACT,"P1","canon.outcome",spec.actualCampaignValue,null,null,null,spec)
            val subject=DomainRef("PLAYER","P1")
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes=listOf(PlayerDomainChange.create(changeUid,PlayerChangeKinds.CAMPAIGN_TRUTH,truth)),
                eventIntents=listOf(PlayerEventIntent.create(
                    "EVENT-INTENT-${command.commandUid}",PlayerEventIntentKinds.DOMAIN_EFFECT,subject,listOf(subject),listOf(changeUid),
                    DomainEffectEventIntentPayload(subject,"RPGOS-EFFECT:CANON-DIVERGENCE")
                ))
            ))
        }
    }

    private fun count(db:SQLiteDatabase,table:String)=db.rawQuery("SELECT COUNT(*) FROM $table",null).use{it.moveToFirst();it.getLong(0)}
    private fun countWhere(db:SQLiteDatabase,table:String,where:String)=db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where",null).use{it.moveToFirst();it.getLong(0)}
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}

    companion object {
        private val divergenceSpecs = mutableMapOf<String, CanonDivergenceSpec>()
    }
}
