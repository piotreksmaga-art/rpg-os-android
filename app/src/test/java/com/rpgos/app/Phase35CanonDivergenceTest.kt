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

    @Before fun setUp() {
        divergenceByCommand.clear()
        root = kotlin.io.path.createTempDirectory("p35-").toFile()
        dbFile = File(root, "campaign.db")
    }
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
            val proposal = proposal("ROLL", spec("DIV-ROLL", "CANON", "B"))
            assertTrue(runCatching {
                TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1","TURN-ROLL","ROLL","TX-ROLL"), proposal,
                    TurnFailureInjector { if (it == TurnFailurePoint.AFTER_EVENT_APPEND) error("crash") }).commit()
            }.isFailure)
            assertTrue(CanonDivergenceStore(db, "C1").list().isEmpty())

            val first = commit(db, "IDEM", spec("DIV-IDEM", "CANON", "B"))
            val replay = TurnTransactionBoundary.create(db, TurnTransactionIdentity("C1","TURN-IDEM","IDEM","TX-IDEM"),
                proposal("IDEM", spec("DIV-IDEM", "CANON", "B"))).commit()
            assertTrue(first is TurnExecutionResult.Committed)
            assertTrue(replay is TurnExecutionResult.AlreadyCommitted)
            assertEquals(1, CanonDivergenceStore(db, "C1").list().size)
        }
    }

    @Test fun divergenceIsCampaignScopedAndContextKeepsItSeparateFromTruth() {
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.initialize(db, "C2")
            commit(db, "C1-X", spec("DIV-C1", "CANON", "B"), "C1")
            commit(db, "C2-X", spec("DIV-C2", "CANON", "C"), "C2")
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
            commit(db, "AFTER", spec("DIV-AFTER", "CANON", "B"))
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
            val legacy = spec("DIV-LEGACY", "CANON", "B").copy(provenanceStatus = HistoricalProvenanceStatus.UNKNOWN_NOT_RECORDED)
            assertTrue(runCatching { CanonDivergenceStore(db, "C1").importVerified(legacy) }.isFailure)
            val imported = withAdministrativeMutationAuthority(db, "C1") { CanonDivergenceStore(db, "C1").importVerified(legacy) }
            assertNull(imported.createdTransactionUid); assertNull(imported.createdTurnUid); assertNull(imported.createdEventUid)
            assertEquals(HistoricalProvenanceStatus.UNKNOWN_NOT_RECORDED, imported.spec.provenanceStatus)
        }
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
        val binding = WorldPackRuleBinding("WORLD-A", "1")
        val engine = PlayerDomainEngine(
            PlayerResolutionComponentRegistry.of(listOf(TruthComponent())),
            worldRuleRegistry = WorldRuleProviderRegistry.of(listOf(CanonProvider())),
            worldPackAuthority = WorldPackAuthoritySnapshot.single(campaign, binding)
        )
        val context = PlayerResolutionContext.create(campaign, actor, setOf(
            CampaignScopedDomainRef(campaign, DomainRef("PLAYER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef("CHARACTER", "P1")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "A")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.FINANCIAL_ACCOUNT, "B")),
            CampaignScopedDomainRef(campaign, DomainRef(PlayerResolutionReferenceKinds.CURRENCY, "CUR"))
        ), worldRuleMode = WorldRuleMode.Bound(binding))
        return when (val admission = CampaignMutationBoundary.resolveAndAdmit(campaign, engine, cmd, context)) {
            is CampaignMutationAdmission.Accepted -> admission.proposal
            is CampaignMutationAdmission.Rejected -> error("admission rejected: ${admission.reasonUid}")
        }
    }

    private class CanonProvider : WorldRuleProvider("P35-CANON-PROVIDER", "1", "WORLD-A", "1") {
        override fun evaluate(request: WorldRuleRequest): WorldRuleDecision {
            val evidence = if (request.stage == WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK) {
                listOf(CanonExpectationEvidence.uid(CanonReference("CHARACTER", "P1", "CANON-EXPECTATION-1"), "CANON"))
            } else emptyList()
            return WorldRuleDecision.Allowed.create("RPGOS-RULE:P35-CANON", evidence)
        }
    }

    private class TruthComponent :
        PlayerResolutionComponent<TransferFundsCommandPayload>(PlayerCommandKinds.TRANSFER_FUNDS, TransferFundsCommandPayload::class, "P35-TRUTH", "1") {
        override fun resolve(command: PlayerCommand<TransferFundsCommandPayload>, context: PlayerResolutionContext): PlayerResolutionComponentOutcome {
            val divergence = divergenceByCommand[command.commandUid]
            val payload = CampaignTruthChange("TRUTH-${command.commandUid}", TruthKind.FACT, "P1", "canon.outcome",
                divergence?.actualCampaignValue ?: "CANON", null, null, null, divergence)
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

    companion object { private val divergenceByCommand = mutableMapOf<String, CanonDivergenceSpec?>() }
}
