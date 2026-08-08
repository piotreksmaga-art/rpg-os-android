package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NpcKnowledgeIntegrity141Test {
    private lateinit var db: SQLiteDatabase

    @Before
    fun setUp() {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            """
            CREATE TABLE gm_campaign_meta(
                campaign_id TEXT PRIMARY KEY,
                current_turn INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE gm_facts(
                fact_id TEXT PRIMARY KEY,
                campaign_id TEXT NOT NULL,
                truth_kind TEXT NOT NULL,
                holder_id TEXT
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO gm_campaign_meta(campaign_id,current_turn) VALUES('CAMPAIGN-test',3)")
        NpcKnowledgePersistenceSchema141.ensure(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun acceptsConsistentDurableInference() {
        fact("FACT-source", "FACT", null)
        fact("BELIEF-result", "BELIEF", "NPC-a")
        db.execSQL(
            """
            INSERT INTO gm_npc_inferences(
                inference_id,campaign_id,holder_id,resulting_belief_id,premise_truth_ids_json,
                turn_number,confidence,created_at
            ) VALUES('INF-1','CAMPAIGN-test','NPC-a','BELIEF-result','["FACT-source"]',2,0.8,1)
            """.trimIndent()
        )

        val report = NpcKnowledgeIntegrity141(db).check()
        assertTrue(report.issues.joinToString { it.code }, report.ok)
    }

    @Test
    fun detectsInferenceHolderMismatchAndMissingPremise() {
        fact("BELIEF-result", "BELIEF", "NPC-other")
        db.execSQL(
            """
            INSERT INTO gm_npc_inferences(
                inference_id,campaign_id,holder_id,resulting_belief_id,premise_truth_ids_json,
                turn_number,confidence,created_at
            ) VALUES('INF-1','CAMPAIGN-test','NPC-a','BELIEF-result','["FACT-missing"]',2,0.8,1)
            """.trimIndent()
        )

        val report = NpcKnowledgeIntegrity141(db).check()
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "NPC_INFERENCE_HOLDER_MISMATCH" })
        assertTrue(report.issues.any { it.code == "NPC_INFERENCE_UNKNOWN_PREMISE" })
    }

    @Test
    fun detectsFactUsedAsResolutionBelief() {
        fact("BELIEF-a", "BELIEF", "NPC-a")
        fact("FACT-b", "FACT", null)
        db.execSQL(
            """
            INSERT INTO gm_npc_knowledge_resolutions(
                resolution_id,campaign_id,holder_id,subject_id,predicate,
                competing_belief_ids_json,winner_belief_id,superseded_belief_ids_json,
                reason,turn_number,created_at
            ) VALUES(
                'RES-1','CAMPAIGN-test','NPC-a','TARGET','location',
                '["BELIEF-a","FACT-b"]','BELIEF-a','["FACT-b"]',
                'STRONGER_PROVENANCE',3,1
            )
            """.trimIndent()
        )

        val report = NpcKnowledgeIntegrity141(db).check()
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "NPC_RESOLUTION_REF_NOT_BELIEF" })
    }

    private fun fact(uid: String, kind: String, holder: String?) {
        db.execSQL(
            "INSERT INTO gm_facts(fact_id,campaign_id,truth_kind,holder_id) VALUES(?,?,?,?)",
            arrayOf(uid, "CAMPAIGN-test", kind, holder)
        )
    }
}
