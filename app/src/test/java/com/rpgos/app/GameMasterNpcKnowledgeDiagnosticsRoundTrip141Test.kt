package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GameMasterNpcKnowledgeDiagnosticsRoundTrip141Test {
    private lateinit var context: Context
    private lateinit var campaignDir: File
    private lateinit var store: LocalGameStore

    private val holder = EntityUid("NPC-diagnostics-roundtrip")
    private val subject = EntityUid("SUBJECT-diagnostics-roundtrip")
    private val sourceFactUid = EntityUid("FACT-source")
    private val replacementFactUid = EntityUid("FACT-replacement")
    private val oldBeliefUid = EntityUid("BELIEF-old")
    private val inferredBeliefUid = EntityUid("BELIEF-inferred")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()

        campaignDir = File(context.filesDir, "rpgos/saves/GM141_Diagnostics_RoundTrip.campaign")
        campaignDir.deleteRecursively()
        campaignDir.mkdirs()

        store = LocalGameStore(context)
        store.setActiveCampaign(campaignDir.name)
        // Exercise GameMasterRepositoryFactory against a real campaign schema, not an empty SQLite file.
        store.bootstrap()
        require(File(campaignDir, "campaign.db").isFile) { "Test bootstrap did not create campaign.db" }
    }

    @After
    fun tearDown() {
        runCatching { campaignDir.deleteRecursively() }
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun offlineNpcKnowledgeReportSurvivesRepositoryRestart() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        factory.openActiveSession().use { active ->
            val stores = requireNotNull(active.npcKnowledgeStores)
            val campaign = active.campaignUid

            active.repository.inTransaction {
                writeTruth(
                    CampaignTruth(
                        uid = sourceFactUid,
                        kind = TruthKind.FACT,
                        subjectUid = subject,
                        predicate = "suspicion",
                        value = "enemy",
                        validFromTurn = 10,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.CAMPAIGN_EVENT,
                            sourceUid = null,
                            turnId = 10,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
                writeTruth(
                    CampaignTruth(
                        uid = replacementFactUid,
                        kind = TruthKind.FACT,
                        subjectUid = subject,
                        predicate = "location",
                        value = "KUMO",
                        validFromTurn = 20,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.NPC_OBSERVATION,
                            sourceUid = null,
                            turnId = 20,
                            confidence = 1.0,
                            verified = true
                        )
                    )
                )
                writeTruth(
                    CampaignTruth(
                        uid = oldBeliefUid,
                        kind = TruthKind.BELIEF,
                        subjectUid = subject,
                        predicate = "location",
                        value = "KONOHA",
                        holderUid = holder,
                        validFromTurn = 12,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.NPC_INFERENCE,
                            sourceUid = sourceFactUid,
                            turnId = 12,
                            confidence = 0.55
                        )
                    )
                )
                writeTruth(
                    CampaignTruth(
                        uid = inferredBeliefUid,
                        kind = TruthKind.BELIEF,
                        subjectUid = subject,
                        predicate = "suspicion",
                        value = "enemy",
                        holderUid = holder,
                        validFromTurn = 15,
                        provenance = ProvenanceRecord(
                            type = ProvenanceType.NPC_INFERENCE,
                            sourceUid = sourceFactUid,
                            turnId = 15,
                            confidence = 0.80
                        )
                    )
                )
            }

            stores.inferences.appendInference(
                NpcInferenceLedgerEntry141(
                    inferenceUid = EntityUid("INFERENCE-diagnostics-roundtrip"),
                    campaignUid = campaign,
                    holderUid = holder,
                    resultingBeliefUid = inferredBeliefUid,
                    premiseTruthUids = listOf(sourceFactUid),
                    turnId = 15,
                    confidence = 0.80
                )
            )
            stores.retractions.appendRetraction(
                NpcBeliefRetraction141(
                    retractionUid = EntityUid("RETRACTION-diagnostics-roundtrip"),
                    campaignUid = campaign,
                    holderUid = holder,
                    retractedBeliefUid = oldBeliefUid,
                    replacementTruthUid = replacementFactUid,
                    turnId = 20,
                    reason = "direct observation"
                )
            )
        }

        val report = GameMasterDiagnosticsService141(context, LocalGameStore(context))
            .npcKnowledgeReport(holderUid = holder, atTurnId = 100)

        assertTrue(report.contains("GM141 NPC KNOWLEDGE DIAGNOSTICS"))
        assertTrue(report.contains("holder=${holder.value}"))
        assertTrue(report.contains("activeBeliefs=1"))
        assertTrue(report.contains("${inferredBeliefUid.value} suspicion=enemy"))
        assertTrue(report.contains("${oldBeliefUid.value} status=RETRACTED"))
        assertTrue(report.contains("replacement=${replacementFactUid.value}"))
        assertTrue(report.contains("status=OK"))
    }

    @Test
    fun failedNpcKnowledgeTransactionRollsBackAllLedgerWrites() = runBlocking {
        val factory = GameMasterRepositoryFactory(context, store)
        val campaignUid = factory.openActiveSession().use { active ->
            val stores = requireNotNull(active.npcKnowledgeStores)
            val tx = NpcKnowledgeTurnTransaction141(active.repository, stores)

            runCatching {
                tx.commit {
                    inferences.appendInference(
                        NpcInferenceLedgerEntry141(
                            inferenceUid = EntityUid("INFERENCE-rollback"),
                            campaignUid = active.campaignUid,
                            holderUid = holder,
                            resultingBeliefUid = EntityUid("BELIEF-rollback"),
                            premiseTruthUids = listOf(EntityUid("FACT-rollback")),
                            turnId = 30,
                            confidence = 0.5
                        )
                    )
                    retractions.appendRetraction(
                        NpcBeliefRetraction141(
                            retractionUid = EntityUid("RETRACTION-rollback"),
                            campaignUid = active.campaignUid,
                            holderUid = holder,
                            retractedBeliefUid = EntityUid("BELIEF-old-rollback"),
                            replacementTruthUid = EntityUid("FACT-new-rollback"),
                            turnId = 30,
                            reason = "forced rollback"
                        )
                    )
                    error("simulate failed turn")
                }
            }
            active.campaignUid
        }

        factory.openActiveSession().use { reopened ->
            val stores = requireNotNull(reopened.npcKnowledgeStores)
            assertEquals(campaignUid, reopened.campaignUid)
            assertEquals(
                null,
                stores.inferences.inferenceForBelief(
                    campaignUid = campaignUid,
                    holderUid = holder,
                    resultingBeliefUid = EntityUid("BELIEF-rollback")
                )
            )
            assertTrue(
                stores.retractions.retractionsForHolder(
                    campaignUid = campaignUid,
                    holderUid = holder,
                    beforeOrAtTurn = 100
                ).none { it.retractionUid == EntityUid("RETRACTION-rollback") }
            )
        }
    }
}
