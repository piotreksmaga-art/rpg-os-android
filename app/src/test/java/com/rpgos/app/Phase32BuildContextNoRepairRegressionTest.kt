package com.rpgos.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32BuildContextNoRepairRegressionTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var campaignDbFile: File
    private val campaignUid = ActiveCampaignRef.DEFAULT_CAMPAIGN_ID

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root = File(context.filesDir, "rpgos").also { it.deleteRecursively() }

        val campaignDir = File(root, "saves/${ActiveCampaignRef.DEFAULT_DIRECTORY}").apply { mkdirs() }
        File(campaignDir, "campaign.json").writeText("{\"id\":\"$campaignUid\"}")
        campaignDbFile = File(campaignDir, "campaign.db")
        SQLiteDatabase.openOrCreateDatabase(campaignDbFile, null).use { db ->
            Phase32ProductionReadyTestFixture.setup(db, campaignUid)
            assertFalse(tableExists(db, "rpgos_repair_log"))
        }

        val worldDir = File(root, "worldpacks/Naruto.worldpack").apply { mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(File(worldDir, "world.db"), null).close()
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("rpgos_selection", Context.MODE_PRIVATE).edit().clear().commit()
        root.deleteRecursively()
    }

    @Test
    fun productionBuildContextUsesReadyDatabaseWithoutInvokingMigrationRepairWriter() {
        val before = SQLiteDatabase.openDatabase(
            campaignDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            GameplayRuntimeBootstrap.requireReady(db, campaignUid)
            CanonicalCounts(
                receipts = count(db, "turn_transaction_receipts"),
                events = count(db, "canonical_gameplay_events"),
                causal = count(db, "canonical_causal_relations"),
                ledger = count(db, "financial_ledger_transactions")
            )
        }

        val audience = VisibilityAudienceFactory.player(campaignUid)
        val purpose = PurposeContext(campaignUid, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val bundle = LocalGameStore(context).buildContext("inspect", 1, audience, purpose)
        assertEquals(campaignUid, bundle.contextMeta["campaign_id"])

        SQLiteDatabase.openDatabase(
            campaignDbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            GameplayRuntimeBootstrap.requireReady(db, campaignUid)
            assertFalse("ordinary gameplay context read invoked AutoRepairEngine", tableExists(db, "rpgos_repair_log"))
            assertEquals(before.receipts, count(db, "turn_transaction_receipts"))
            assertEquals(before.events, count(db, "canonical_gameplay_events"))
            assertEquals(before.causal, count(db, "canonical_causal_relations"))
            assertEquals(before.ledger, count(db, "financial_ledger_transactions"))
            assertTrue(triggerExists(db, "rpgos_event_store_no_update"))
            assertTrue(triggerExists(db, "rpgos_causal_graph_no_update"))
            assertTrue(triggerExists(db, "rpgos_turn_receipts_no_update"))
        }
    }

    private data class CanonicalCounts(
        val receipts: Long,
        val events: Long,
        val causal: Long,
        val ledger: Long
    )

    private fun tableExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }

    private fun triggerExists(db: SQLiteDatabase, name: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name=? LIMIT 1", arrayOf(name)).use { it.moveToFirst() }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
