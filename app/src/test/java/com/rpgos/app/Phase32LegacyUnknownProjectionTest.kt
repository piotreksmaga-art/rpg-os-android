package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Phase32LegacyUnknownProjectionTest {
    private lateinit var root: File
    private lateinit var saveFile: File
    private lateinit var worldFile: File

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "rpgos-g32-legacy-unknown-${System.nanoTime()}")
        val campaignDir = File(root, "saves/C1.campaign").apply { mkdirs() }
        File(campaignDir, "campaign.json").writeText("{\"id\":\"C1\"}")
        saveFile = File(campaignDir, "campaign.db")
        worldFile = File(root, "world.db")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun absentLegacyHistoricalProvenanceRemainsUnknownThroughFinalContextProjection() {
        SQLiteDatabase.openOrCreateDatabase(saveFile, null).use { db ->
            // Representative pre-Phase30 state exists before campaign-intelligence activation.
            CurrentSchema.ensure(db, "C1")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS chapter_events(id INTEGER PRIMARY KEY,campaign_id TEXT,event_type TEXT,description TEXT)"
            )
            db.execSQL(
                "INSERT INTO chapter_events(campaign_id,event_type,description) VALUES('C1','LEGACY','old event with no canonical provenance')"
            )
            CampaignTruthStore(db, "C1").record(
                kind = TruthKind.FACT,
                predicate = "legacy.unknown.provenance",
                objectValue = "known content, unknown historical provenance",
                subjectUid = "P1",
                provenance = Provenance(
                    sourceType = ProvenanceSourceType.LEGACY,
                    sourceId = null,
                    createdTurn = null,
                    createdEvent = null,
                    confidence = 1.0,
                    verified = false,
                    actorUid = null,
                    method = null
                ),
                truthUid = "TRUTH-G32-LEGACY-UNKNOWN"
            )

            GameplayRuntimeBootstrap.initialize(db, "C1")
            GameplayRuntimeBootstrap.requireReady(db, "C1")

            assertEquals("UNKNOWN_NOT_RECORDED", legacyHistoryStatus(db))
            assertEquals(0L, count(db, "canonical_gameplay_events"))
            assertEquals(0L, count(db, "canonical_causal_relations"))
            assertEquals(0L, count(db, "turn_transaction_receipts"))

            SQLiteDatabase.openOrCreateDatabase(worldFile, null).use { world ->
                val projected = ContextBuilder(db, world)
                    .let { builder -> Phase38LegacyContextFixtureSchema.ensure(db, world); builder.build("inspect legacy history",1,VisibilityAudienceFactory.diagnostic("C1"),PurposeContext("C1",VisibilityPurposeKinds.DIAGNOSTIC_INSPECTION)) }
                    .campaignTruth
                    .single { it["truth_uid"] == "TRUTH-G32-LEGACY-UNKNOWN" }

                assertEquals("FACT", projected["truth_kind"])
                assertEquals("LEGACY", projected["source_type"])
                assertNull(projected["source_id"])
                assertNull(projected["created_turn"])
                assertNull(projected["created_event"])
                assertNull(projected["actor_uid"])
                assertNull(projected["method"])
            }

            // A final read/projection is not a provenance reconstruction pass.
            assertEquals("UNKNOWN_NOT_RECORDED", legacyHistoryStatus(db))
            assertEquals(0L, count(db, "canonical_gameplay_events"))
            assertEquals(0L, count(db, "canonical_causal_relations"))
            assertEquals(0L, count(db, "turn_transaction_receipts"))
            assertEquals(
                null,
                CampaignTruthStore(db, "C1").active().single { it.truthUid == "TRUTH-G32-LEGACY-UNKNOWN" }.provenance.createdEvent
            )
        }
    }

    private fun legacyHistoryStatus(db: SQLiteDatabase): String =
        db.rawQuery(
            "SELECT legacy_event_history_status FROM campaign_intelligence_activation WHERE campaign_uid='C1'",
            null
        ).use { c -> c.moveToFirst(); c.getString(0) }

    private fun count(db: SQLiteDatabase, table: String): Long =
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> c.moveToFirst(); c.getLong(0) }
}
