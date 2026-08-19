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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase35CanonDivergenceTest {
    private lateinit var root: File
    private lateinit var dbFile: File

    @Before fun setUp() { root = kotlin.io.path.createTempDirectory("p35-").toFile(); dbFile = File(root, "campaign.db") }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun canonConsistentCommitCreatesNoDivergenceAndDifferingCommitCreatesExactlyOne() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            commit(db, "SAME", null)
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())
            val result = commit(db, "DIFF", spec("DIV-1", "CANON", "CAMPAIGN"))
            assertTrue(result is TurnExecutionResult.Committed)
            val row = CanonDivergenceStore(db, "C1").list().single()
            assertEquals("DIV-1", row.spec.divergenceUid)
            assertEquals("TX-DIFF", row.createdTransactionUid)
            assertEquals("TURN-DIFF", row.createdTurnUid)
            assertTrue(row.createdEventUid!!.startsWith("RPGOS-EVENT:"))
            assertEquals("CANON", row.spec.expectedCanonicalValue)
            assertEquals("CAMPAIGN", row.spec.actualCampaignValue)
        }
    }

    @Test fun rollbackAndAlreadyCommittedNeverDuplicateDivergence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val proposal = proposal("ROLL", spec("DIV-ROLL", "A", "B"))
            assertTrue(runCatching {
                TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1","TURN-ROLL","ROLL","TX-ROLL"), proposal,
                    TurnFailureInjector { if (it == TurnFailurePoint.AFTER_EVENT_APPEND) error("crash") }).commit()
            }.isFailure)
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())

            val first = commit(db, "IDEM", spec("DIV-IDEM", "A", "B"))
            val replay = TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1","TURN-IDEM","IDEM","TX-IDEM"),
                proposal("IDEM", spec("DIV-IDEM", "A", "B"))).commit()
            assertTrue(first is TurnExecutionResult.Committed)
            assertTrue(replay is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1, CanonDivergenceStore(db, "C1").list().size)
        }
    }

    @Test fun divergenceIsCampaignScopedAndContextKeepsItSeparateFromTruth() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C2")
            commit(db, "C1-X", spec("DIV-C1", "A", "B"), "C1")
            commit(db, "C2-X", spec("DIV-C2", "A", "C"), "C2")
            assertEquals(listOf("DIV-C1"), CanonDivergenceStore(db, "C1").list().map { it.spec.divergenceUid })
            assertEquals(listOf("DIV-C2"), CanonDivergenceStore(db, "C2").list().map { it.spec.divergenceUid })
            assertEquals("HISTORICAL_WORLD_EVIDENCE", RuntimeTruthLayerRegistry.requireClassifiedTable("timeline_divergences").uid)
            assertEquals("DERIVED_SIMULATION_STATE", RuntimeTruthLayerRegistry.requireClassifiedTable("canon_divergence_metrics").uid)
        }
    }

    @Test fun divergenceSurvivesSnapshotReplayWithOriginalIdentity() {
        val snapshots = File(root, "snapshots")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            CampaignSnapshotManager(db, "C1", snapshots).create()
            commit(db, "AFTER", spec("DIV-AFTER", "A", "B"))
            val staged = CampaignSnapshotManager(db, "C1", snapshots).reconstructToVerifiedStaging()
            SQLiteDatabase.openDatabase(staged.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { restored ->
                val row = CanonDivergenceStore(restored, "C1").list().single()
                assertEquals("DIV-AFTER", row.spec.divergenceUid)
                assertEquals("TX-AFTER", row.createdTransactionUid)
                assertEquals(CanonDivergenceStore(db, "C1").list(), CanonDivergenceStore(restored, "C1").list())
            }
        }
    }

    @Test fun verifiedAdminImportKeepsUnknownHistoryUnknownAndGameplayCannotEscalate() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            val legacy = spec("DIV-LEGACY", "A", "B").copy(provenanceStatus = HistoricalProvenanceStatus.UNKNOWN_NOT_RECORDED)
            assertTrue(runCatching { CanonDivergenceStore(db, "C1").importVerified(legacy) }.isFailure)
            val imported = withAdministrativeMutationAuthority(db, "C1") { CanonDivergenceStore(db, "C1").importVerified(legacy) }
            assertNull(imported.createdTransactionUid); assertNull(imported.createdTurnUid); assertNull(imported.createdEventUid)
            assertEquals(HistoricalProvenanceStatus.UNKNOWN_NOT_RECORDED, imported.spec.provenanceStatus)
        }
    }

    @Test fun forgedTurnAndAdminSqlContextsCannotCreateRecordedWithoutCanonicalEvidence() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            listOf("TURN","ADMIN").forEach { kind ->
                db.execSQL("DELETE FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE}")
                db.execSQL("INSERT INTO ${GameplayMutationDatabaseGuards.CONTEXT_TABLE}(campaign_uid,capability_kind,depth) VALUES('C1',?,1)",arrayOf(kind))
                assertTrue(runCatching { rawRecordedInsert(db,"RAW-$kind") }.isFailure)
            }
            db.execSQL("DELETE FROM ${GameplayMutationDatabaseGuards.CONTEXT_TABLE}")
            assertTrue(CanonDivergenceStore(db,"C1").list().isEmpty())
        }
    }

    @Test fun administrativeAuthorityForeignCampaignAndMissingProvenanceCannotCallRecordCommitted() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            val s=spec("DIV-AUTH","CANON","CAMPAIGN")
            db.beginTransaction(); try {
                assertTrue(runCatching { withAdministrativeMutationAuthority(db,"C1") { CanonDivergenceStore(db,"C1").recordCommitted(s,TurnTransactionIdentity("C1","T","C","TX"),"E") } }.isFailure)
                assertTrue(runCatching { CanonDivergenceStore(db,"C1").recordCommitted(s,TurnTransactionIdentity("C2","T","C","TX"),"E") }.isFailure)
                assertTrue(runCatching { CanonDivergenceStore(db,"C1").recordCommitted(s,TurnTransactionIdentity("C1","T","C","TX"),"E") }.isFailure)
            } finally { db.endTransaction() }
        }
    }

    @Test fun unboundWorldPackAndCallerSuppliedAuthenticityMismatchesFailClosed() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1")
            assertTrue(runCatching { proposalBound("UNBOUND",spec("DIV-U","CANON","CAMPAIGN"),false) }.isFailure)
            assertTrue(runCatching { proposalBound("UID",spec("DIV-UID","CANON","CAMPAIGN").copy(worldPackUid="WORLD-X"),true) }.isFailure)
            assertTrue(runCatching { proposalBound("VER",spec("DIV-VER","CANON","CAMPAIGN").copy(worldPackVersion="2"),true) }.isFailure)
            assertTrue(runCatching { proposalBound("EXPECT",spec("DIV-EXPECT","WRONG","CAMPAIGN"),true) }.isFailure)
            divergenceActualOverrides["ACTUAL"]="REAL"
            assertTrue(runCatching { proposalBound("ACTUAL",spec("DIV-ACTUAL","CANON","CLAIMED"),true) }.isFailure)
        }
    }

    @Test fun lifecycleLinksRequireExistingSameCampaignNonSelfLegalStatus() {
        SQLiteDatabase.openOrCreateDatabase(dbFile,null).use { db ->
            GameplayRuntimeBootstrap.initialize(db,"C1"); GameplayRuntimeBootstrap.initialize(db,"C2")
            commitBound(db,"BASE",spec("DIV-BASE","CANON","CAMPAIGN"),"C1")
            commitBound(db,"C2BASE",spec("DIV-C2","CANON","CAMPAIGN"),"C2")
            assertTrue(runCatching{commitBound(db,"SELF",spec("DIV-SELF","CANON","CAMPAIGN").copy(supersedesDivergenceUid="DIV-SELF"),"C1")}.isFailure)
            assertTrue(runCatching{commitBound(db,"MISS",spec("DIV-MISS","CANON","CAMPAIGN").copy(supersedesDivergenceUid="NONE"),"C1")}.isFailure)
            assertTrue(runCatching{commitBound(db,"CROSS",spec("DIV-CROSS","CANON","CAMPAIGN").copy(supersedesDivergenceUid="DIV-C2"),"C1")}.isFailure)
            assertTrue(runCatching{commitBound(db,"BADSTATUS",spec("DIV-BAD","CANON","CAMPAIGN").copy(status=CanonDivergenceStatus.RESOLVED),"C1")}.isFailure)
            commitBound(db,"SUPER",spec("DIV-SUPER","CANON","CAMPAIGN").copy(supersedesDivergenceUid="DIV-BASE"),"C1")
            commitBound(db,"RESOLVE",spec("DIV-RESOLVE","CANON","CAMPAIGN").copy(status=CanonDivergenceStatus.RESOLVED,resolvesDivergenceUid="DIV-SUPER"),"C1")
            assertEquals(setOf("DIV-BASE","DIV-SUPER","DIV-RESOLVE"),CanonDivergenceStore(db,"C1").list().map{it.spec.divergenceUid}.toSet())
        }
    }

    private fun rawRecordedInsert(db:SQLiteDatabase,uid:String){
        db.execSQL("""INSERT INTO ${Phase35CanonDivergenceSchema.TABLE}(divergence_uid,campaign_uid,canonical_subject_kind_uid,canonical_subject_uid,canonical_expectation_uid,world_pack_uid,world_pack_version,divergence_kind,expected_canonical_value,actual_campaign_value,lifecycle_status,created_transaction_uid,created_turn_uid,created_event_uid,provenance_status,divergence_schema_version,created_at_epoch_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",arrayOf(uid,"C1","CHARACTER","P1","CANON-EXPECTATION-1","WORLD-A","1","OUTCOME","CANON","CAMPAIGN","ACTIVE","TX-FAKE","TURN-FAKE","EVENT-FAKE","RECORDED",1,1L))
    }

    private fun commitBound(db:SQLiteDatabase,command:String,s:CanonDivergenceSpec,campaign:String)=
        TurnTransactionBoundary.create(db,TurnTransactionIdentity(campaign,"TURN-$command",command,"TX-$command"),proposalBound(command,s,true,campaign)).commit()

    private fun proposalBound(command:String,s:CanonDivergenceSpec,bound:Boolean,campaign:String="C1"):CanonicalCampaignMutationProposal {
        divergenceByCommand[command]=s
        val actor=CommandActorRef("PLAYER","P1")
        val cmd=PlayerCommand(commandUid=command,campaignUid=campaign,actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("P35-BOUND"),requestedEffectiveOrder=1)
        val refs=setOf(CampaignScopedDomainRef(campaign,DomainRef("PLAYER","P1")),CampaignScopedDomainRef(campaign,DomainRef("CHARACTER","P1")),CampaignScopedDomainRef(campaign,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"A")),CampaignScopedDomainRef(campaign,DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT,"B")),CampaignScopedDomainRef(campaign,DomainRef(PlayerResolutionReferenceKinds.CURRENCY,"CUR")))
        val registry=PlayerResolutionComponentRegistry.of(listOf(TruthComponent()))
        val engine:PlayerDomainEngine; val context:PlayerResolutionContext
        if(bound){val binding=WorldPackRuleBinding("WORLD-A","1");engine=PlayerDomainEngine(registry,worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(TestCanonProvider())),worldPackAuthority=WorldPackAuthoritySnapshot.single(campaign,binding));context=PlayerResolutionContext.create(campaign,actor,refs,worldRuleMode=WorldRuleMode.Bound(binding))}
        else{engine=PlayerDomainEngine(registry);context=PlayerResolutionContext.createUnboundGeneric(campaign,actor,refs)}
        return when(val a=CampaignMutationBoundary.resolveAndAdmit(campaign,engine,cmd,context)){is CampaignMutationAdmission.Accepted->a.proposal;is CampaignMutationAdmission.Rejected->error(a.reasonUid)}
    }

    private class TestCanonProvider:WorldRuleProvider("P35-TEST-PROVIDER","1","WORLD-A","1"){
        override fun canonicalExpectation(reference:CanonReference)=if(reference.expectationUid=="CANON-EXPECTATION-1")CanonicalWorldExpectation(reference,CanonDivergenceKind.OUTCOME,"CANON")else null
        override fun evaluate(request:WorldRuleRequest)=WorldRuleDecision.Allowed.create("P35-TEST-RULE")
    }

    private fun spec(uid: String, expected: String, actual: String) = CanonDivergenceSpec(
        uid, CanonReference("CHARACTER", "P1", "CANON-EXPECTATION-1"), "WORLD-A", "1",
        CanonDivergenceKind.OUTCOME, expected, actual
    )

    private fun commit(db: SQLiteDatabase, command: String, divergence: CanonDivergenceSpec?, campaign: String = "C1") =
        TurnTransactionBoundary.create(db, TurnTransactionIdentity(campaign,"TURN-$command",command,"TX-$command"), proposal(command, divergence, campaign)).commit()

    private fun proposal(command: String, divergence: CanonDivergenceSpec?, campaign: String = "C1"): CanonicalCampaignMutationProposal {
        val actor = CommandActorRef("PLAYER", "P1")
        val cmd = PlayerCommand(commandUid=command,campaignUid=campaign,actor=actor,commandKindUid=PlayerCommandKinds.TRANSFER_FUNDS,
            payload=TransferFundsCommandPayload("A","B",1,"CUR"),provenance=CommandProvenance("P35"),requestedEffectiveOrder=1)
        divergenceByCommand[command] = divergence
        val refs = setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("CHARACTER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
         )
        val engine: PlayerDomainEngine
        val context: PlayerResolutionContext
        if(divergence!=null){
            val binding=WorldPackRuleBinding("WORLD-A","1")
            engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TruthComponent())),worldRuleRegistry=WorldRuleProviderRegistry.of(listOf(TestCanonProvider())),worldPackAuthority=WorldPackAuthoritySnapshot.single(campaign,binding))
            context=PlayerResolutionContext.create(campaign,actor,refs,worldRuleMode=WorldRuleMode.Bound(binding))
        } else {
            engine=PlayerDomainEngine(PlayerResolutionComponentRegistry.of(listOf(TruthComponent())))
            context=PlayerResolutionContext.createUnboundGeneric(campaign,actor,refs)
        }
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class TruthComponent :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "P35-TRUTH", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val divergence = divergenceByCommand[command.commandUid]
            val payload = CampaignTruthChange("TRUTH-${command.commandUid}", TruthKind.FACT, "P1", "canon.outcome",
                divergenceActualOverrides[command.commandUid] ?: divergence?.actualCampaignValue ?: "CANON", null, null, null, divergence)
            val changeUid = "CHANGE-${command.commandUid}"
            val subject = DomainRef("PLAYER", "P1")
            return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(
                changes=listOf(PlayerDomainChange.create(changeUid, PlayerChangeKinds.CAMPAIGN_TRUTH, payload)),
                eventIntents=listOf(PlayerEventIntent.create(
                    "EVENT-INTENT-${command.commandUid}", PlayerEventIntentKinds.DOMAIN_EFFECT, subject,
                    listOf(subject), listOf(changeUid), DomainEffectEventIntentPayload(subject, "RPGOS-EFFECT:CANON-DIVERGENCE")
                ))
            ))
        }
    }

    companion object { private val divergenceByCommand = mutableMapOf<String, CanonDivergenceSpec?>(); private val divergenceActualOverrides=mutableMapOf<String,String>() }
}
