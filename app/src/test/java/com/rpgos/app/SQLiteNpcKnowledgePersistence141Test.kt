package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
class SQLiteNpcKnowledgePersistence141Test {
    private lateinit var dbFile: File

    private val campaign = EntityUid("CAMPAIGN-roundtrip")
    private val holder = EntityUid("NPC-roundtrip")
    private val subject = EntityUid("SUBJECT-roundtrip")
    private val oldBelief = EntityUid("BELIEF-old")
    private val replacementTruth = EntityUid("FACT-replacement")
    private val inferredBelief = EntityUid("BELIEF-inferred")

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication() as Context
        dbFile = context.getDatabasePath("gm141-npc-knowledge-roundtrip.db")
        dbFile.parentFile?.mkdirs()
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(dbFile)
    }

    @Test
    fun allNpcKnowledgeLedgersSurviveDatabaseReopen() = runBlocking {
        openDb().use { db ->
            val stores = SQLiteNpcKnowledgeStores141(db, campaign)

            stores.retractions.appendRetraction(
                NpcBeliefRetraction141(
                    retractionUid = EntityUid("RETRACTION-1"),
                    campaignUid = campaign,
                    holderUid = holder,
                    retractedBeliefUid = oldBelief,
                    replacementTruthUid = replacementTruth,
                    turnId = 41,
                    reason = "direct observation"
                )
            )

            stores.inferences.appendInference(
                NpcInferenceLedgerEntry141(
                    inferenceUid = EntityUid("INFERENCE-1"),
                    campaignUid = campaign,
                    holderUid = holder,
                    resultingBeliefUid = inferredBelief,
                    premiseTruthUids = listOf(EntityUid("FACT-A"), EntityUid("FACT-B")),
                    turnId = 42,
                    confidence = 0.81
                )
            )

            stores.organizations.appendOrganizationKnowledge(
                OrganizationKnowledgeTransmission141(
                    transmissionUid = EntityUid("ORGKNOW-1"),
                    campaignUid = campaign,
                    organizationUid = EntityUid("ORG-1"),
                    membershipUid = EntityUid("MEMBERSHIP-1"),
                    publicationUid = EntityUid("PUBLICATION-1"),
                    sourceTruthUid = EntityUid("FACT-ORG-1"),
                    receiverUid = holder,
                    resultingBeliefUid = EntityUid("BELIEF-ORG-1"),
                    turnId = 43,
                    confidence = 0.88
                )
            )

            val winner = belief(
                uid = EntityUid("BELIEF-resolution-winner"),
                value = "north",
                provenance = ProvenanceType.NPC_OBSERVATION,
                confidence = 0.95,
                turnId = 44
            )
            val loser = belief(
                uid = EntityUid("BELIEF-resolution-loser"),
                value = "south",
                provenance = ProvenanceType.NPC_INFERENCE,
                confidence = 0.70,
                turnId = 40
            )
            stores.resolutions.appendResolution(
                NpcKnowledgeLifecycle141.Resolution(
                    resolutionUid = EntityUid("RESOLUTION-1"),
                    conflict = NpcKnowledgeLifecycle141.Conflict(
                        holderUid = holder,
                        subjectUid = subject,
                        predicate = "location",
                        competingBeliefs = listOf(winner, loser)
                    ),
                    winner = winner,
                    supersededBeliefUids = listOf(loser.uid),
                    reason = NpcKnowledgeLifecycle141.ResolutionReason.STRONGER_PROVENANCE,
                    turnId = 44
                )
            )
        }

        openDb().use { reopened ->
            val stores = SQLiteNpcKnowledgeStores141(reopened, campaign)

            val retractions = stores.retractions.retractionsForHolder(
                campaignUid = campaign,
                holderUid = holder,
                beforeOrAtTurn = 100
            )
            assertEquals(1, retractions.size)
            assertEquals(oldBelief, retractions.single().retractedBeliefUid)
            assertEquals(replacementTruth, retractions.single().replacementTruthUid)
            assertEquals(41L, retractions.single().turnId)

            val inference = stores.inferences.inferenceForBelief(
                campaignUid = campaign,
                holderUid = holder,
                resultingBeliefUid = inferredBelief
            )
            assertNotNull(inference)
            assertEquals(listOf(EntityUid("FACT-A"), EntityUid("FACT-B")), inference!!.premiseTruthUids)
            assertEquals(42L, inference.turnId)
            assertEquals(0.81, inference.confidence, 0.0001)

            assertRowCount(
                reopened,
                "gm_organization_knowledge_transmissions",
                "campaign_id=? AND receiver_id=? AND resulting_belief_id=?",
                arrayOf(campaign.value, holder.value, "BELIEF-ORG-1"),
                1
            )
            assertRowCount(
                reopened,
                "gm_npc_knowledge_resolutions",
                "campaign_id=? AND holder_id=? AND resolution_id=?",
                arrayOf(campaign.value, holder.value, "RESOLUTION-1"),
                1
            )
            assertRowCount(
                reopened,
                "rpgos_schema_migrations",
                "migration_id=?",
                arrayOf(NpcKnowledgePersistenceSchema141.MIGRATION_ID),
                1
            )
        }
    }

    private fun belief(
        uid: EntityUid,
        value: String,
        provenance: ProvenanceType,
        confidence: Double,
        turnId: Long
    ) = CampaignTruth(
        uid = uid,
        kind = TruthKind.BELIEF,
        subjectUid = subject,
        predicate = "location",
        value = value,
        holderUid = holder,
        validFromTurn = turnId,
        provenance = ProvenanceRecord(
            type = provenance,
            sourceUid = EntityUid("SOURCE-${uid.value}"),
            turnId = turnId,
            confidence = confidence
        )
    )

    private fun assertRowCount(
        db: SQLiteDatabase,
        table: String,
        where: String,
        args: Array<String>,
        expected: Int
    ) {
        db.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", args).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(expected, cursor.getInt(0))
        }
    }

    private fun openDb(): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(dbFile, null)
}
