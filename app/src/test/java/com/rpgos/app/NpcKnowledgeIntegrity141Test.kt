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
                holder_id TEXT,
                subject_id TEXT,
                predicate TEXT NOT NULL,
                valid_from_turn INTEGER NOT NULL,
                valid_until_turn INTEGER
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO gm_campaign_meta(campaign_id,current_turn) VALUES('CAMPAIGN-test',3)")
        NpcKnowledgePersistenceSchema141.ensure(db)
        OrganizationKnowledgeAuthorizationSchema141.ensure(db)
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

    @Test
    fun detectsTamperedOrganizationAuthorization() {
        fact("FACT-secret", "FACT", null, "TARGET", "classified.location")
        fact("BELIEF-secret", "BELIEF", "NPC-a", "TARGET", "classified.location")
        db.execSQL(
            """
            INSERT INTO gm_organization_memberships(
                membership_id,campaign_id,npc_id,organization_id,clearance,
                valid_from_turn,valid_until_turn,created_at
            ) VALUES('MEM-1','CAMPAIGN-test','NPC-a','ORG-a',1,1,NULL,1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO gm_organization_fact_publications(
                publication_id,campaign_id,organization_id,truth_id,subject_id,predicate,
                minimum_clearance,valid_from_turn,valid_until_turn,created_at
            ) VALUES('PUB-1','CAMPAIGN-test','ORG-a','FACT-secret','TARGET','classified.location',5,1,NULL,1)
            """.trimIndent()
        )
        db.execSQL(
            """
            INSERT INTO gm_organization_knowledge_transmissions(
                transmission_id,campaign_id,organization_id,membership_id,publication_id,
                source_truth_id,receiver_id,resulting_belief_id,turn_number,confidence,created_at
            ) VALUES(
                'ORGKNOW-1','CAMPAIGN-test','ORG-wrong','MEM-1','PUB-1',
                'FACT-secret','NPC-a','BELIEF-secret',2,0.9,1
            )
            """.trimIndent()
        )

        val report = NpcKnowledgeIntegrity141(db).check()
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "ORG_KNOWLEDGE_MEMBERSHIP_ORG_MISMATCH" })
        assertTrue(report.issues.any { it.code == "ORG_KNOWLEDGE_PUBLICATION_ORG_MISMATCH" })
        assertTrue(report.issues.any { it.code == "ORG_KNOWLEDGE_INSUFFICIENT_CLEARANCE" })
    }

    @Test
    fun detectsUnusedTamperedOrganizationPublication() {
        fact(
            uid = "FACT-windowed",
            kind = "FACT",
            holder = null,
            subject = "TARGET-real",
            predicate = "classified.real",
            validFrom = 2L,
            validUntil = 5L
        )
        db.execSQL(
            """
            INSERT INTO gm_organization_fact_publications(
                publication_id,campaign_id,organization_id,truth_id,subject_id,predicate,
                minimum_clearance,valid_from_turn,valid_until_turn,created_at
            ) VALUES(
                'PUB-unused','CAMPAIGN-test','ORG-a','FACT-windowed','TARGET-wrong','classified.wrong',
                1,1,9,1
            )
            """.trimIndent()
        )

        val report = NpcKnowledgeIntegrity141(db).check()
        assertFalse(report.ok)
        assertTrue(report.issues.any { it.code == "ORG_PUBLICATION_FACT_MISMATCH" })
        assertTrue(report.issues.any { it.code == "ORG_PUBLICATION_BEFORE_FACT" })
        assertTrue(report.issues.any { it.code == "ORG_PUBLICATION_OUTLIVES_FACT" })
    }

    private fun fact(
        uid: String,
        kind: String,
        holder: String?,
        subject: String = "SUBJECT-test",
        predicate: String = "test.predicate",
        validFrom: Long = 0L,
        validUntil: Long? = null
    ) {
        db.execSQL(
            """
            INSERT INTO gm_facts(
                fact_id,campaign_id,truth_kind,holder_id,subject_id,predicate,valid_from_turn,valid_until_turn
            ) VALUES(?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf(uid, "CAMPAIGN-test", kind, holder, subject, predicate, validFrom, validUntil)
        )
    }
}
