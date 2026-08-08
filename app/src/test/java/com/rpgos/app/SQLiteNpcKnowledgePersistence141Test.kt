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
@Config(sdk = [36])
class SQLiteNpcKnowledgePersistence141Test {
    private lateinit var dbFile: File

    private val campaign = EntityUid("CAMPAIGN-roundtrip")
    private val holder = EntityUid("NPC-roundtrip")
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
    fun retractionsAndInferencePremisesSurviveDatabaseReopen() = runBlocking {
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

            reopened.rawQuery(
                "SELECT COUNT(*) FROM rpgos_schema_migrations WHERE migration_id=?",
                arrayOf(NpcKnowledgePersistenceSchema141.MIGRATION_ID)
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    private fun openDb(): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(dbFile, null)
}
