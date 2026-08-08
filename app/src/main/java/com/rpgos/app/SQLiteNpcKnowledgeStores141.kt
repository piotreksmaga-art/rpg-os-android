package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Additive migration for durable NPC-knowledge ledgers. It deliberately lives in the existing
 * campaign.db and uses RPG OS' migration registry instead of PRAGMA user_version.
 */
object NpcKnowledgePersistenceSchema141 {
    const val MIGRATION_ID = "GM-141-NPC-KNOWLEDGE-PERSISTENCE-V1"

    fun ensure(db: SQLiteDatabase) {
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS rpgos_schema_migrations(
                    migration_id TEXT PRIMARY KEY,
                    applied_at INTEGER NOT NULL,
                    notes TEXT
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_npc_belief_retractions(
                    retraction_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    holder_id TEXT NOT NULL,
                    retracted_belief_id TEXT NOT NULL,
                    replacement_truth_id TEXT NOT NULL,
                    turn_number INTEGER NOT NULL,
                    reason TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, holder_id, retracted_belief_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_npc_retractions_holder_turn
                ON gm_npc_belief_retractions(campaign_id, holder_id, turn_number)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_npc_inferences(
                    inference_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    holder_id TEXT NOT NULL,
                    resulting_belief_id TEXT NOT NULL,
                    premise_truth_ids_json TEXT NOT NULL,
                    turn_number INTEGER NOT NULL,
                    confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0),
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, resulting_belief_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_npc_inferences_holder_turn
                ON gm_npc_inferences(campaign_id, holder_id, turn_number)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_organization_knowledge_transmissions(
                    transmission_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    organization_id TEXT NOT NULL,
                    membership_id TEXT NOT NULL,
                    publication_id TEXT NOT NULL,
                    source_truth_id TEXT NOT NULL,
                    receiver_id TEXT NOT NULL,
                    resulting_belief_id TEXT NOT NULL,
                    turn_number INTEGER NOT NULL,
                    confidence REAL NOT NULL CHECK(confidence >= 0.0 AND confidence <= 1.0),
                    created_at INTEGER NOT NULL,
                    UNIQUE(campaign_id, resulting_belief_id)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_org_knowledge_receiver_turn
                ON gm_organization_knowledge_transmissions(campaign_id, receiver_id, turn_number)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS gm_npc_knowledge_resolutions(
                    resolution_id TEXT PRIMARY KEY,
                    campaign_id TEXT NOT NULL,
                    holder_id TEXT NOT NULL,
                    subject_id TEXT,
                    predicate TEXT NOT NULL,
                    competing_belief_ids_json TEXT NOT NULL,
                    winner_belief_id TEXT,
                    superseded_belief_ids_json TEXT NOT NULL,
                    reason TEXT NOT NULL,
                    turn_number INTEGER NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_gm_npc_resolutions_holder_turn
                ON gm_npc_knowledge_resolutions(campaign_id, holder_id, turn_number)
                """.trimIndent()
            )

            db.execSQL(
                """
                INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
                VALUES(?,?,?)
                """.trimIndent(),
                arrayOf(
                    MIGRATION_ID,
                    System.currentTimeMillis(),
                    "GM141 durable NPC retractions, inference premises, organization knowledge and belief resolutions"
                )
            )
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }
}

class SQLiteNpcBeliefRetractionStore141(
    private val db: SQLiteDatabase
) : NpcBeliefRetractionStore141 {
    init { NpcKnowledgePersistenceSchema141.ensure(db) }

    override suspend fun appendRetraction(record: NpcBeliefRetraction141) {
        val values = ContentValues().apply {
            put("retraction_id", record.retractionUid.value)
            put("campaign_id", record.campaignUid.value)
            put("holder_id", record.holderUid.value)
            put("retracted_belief_id", record.retractedBeliefUid.value)
            put("replacement_truth_id", record.replacementTruthUid.value)
            put("turn_number", record.turnId)
            put("reason", record.reason)
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("gm_npc_belief_retractions", null, values)
    }

    override suspend fun retractionsForHolder(
        campaignUid: EntityUid,
        holderUid: EntityUid,
        beforeOrAtTurn: Long
    ): List<NpcBeliefRetraction141> {
        val out = mutableListOf<NpcBeliefRetraction141>()
        db.rawQuery(
            """
            SELECT retraction_id,retracted_belief_id,replacement_truth_id,turn_number,reason
            FROM gm_npc_belief_retractions
            WHERE campaign_id=? AND holder_id=? AND turn_number<=?
            ORDER BY turn_number ASC, retraction_id ASC
            """.trimIndent(),
            arrayOf(campaignUid.value, holderUid.value, beforeOrAtTurn.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += NpcBeliefRetraction141(
                    retractionUid = EntityUid(c.getString(0)),
                    campaignUid = campaignUid,
                    holderUid = holderUid,
                    retractedBeliefUid = EntityUid(c.getString(1)),
                    replacementTruthUid = EntityUid(c.getString(2)),
                    turnId = c.getLong(3),
                    reason = c.getString(4)
                )
            }
        }
        return out
    }
}

class SQLiteNpcInferenceStore141(
    private val db: SQLiteDatabase
) : NpcInferenceQueryStore141 {
    init { NpcKnowledgePersistenceSchema141.ensure(db) }

    override suspend fun appendInference(record: NpcInferenceLedgerEntry141) {
        val values = ContentValues().apply {
            put("inference_id", record.inferenceUid.value)
            put("campaign_id", record.campaignUid.value)
            put("holder_id", record.holderUid.value)
            put("resulting_belief_id", record.resultingBeliefUid.value)
            put("premise_truth_ids_json", JSONArray(record.premiseTruthUids.map { it.value }).toString())
            put("turn_number", record.turnId)
            put("confidence", record.confidence)
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("gm_npc_inferences", null, values)
    }

    override suspend fun inferenceForBelief(
        campaignUid: EntityUid,
        holderUid: EntityUid,
        resultingBeliefUid: EntityUid
    ): NpcInferenceLedgerEntry141? {
        db.rawQuery(
            """
            SELECT inference_id,premise_truth_ids_json,turn_number,confidence
            FROM gm_npc_inferences
            WHERE campaign_id=? AND holder_id=? AND resulting_belief_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, holderUid.value, resultingBeliefUid.value)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return NpcInferenceLedgerEntry141(
                inferenceUid = EntityUid(c.getString(0)),
                campaignUid = campaignUid,
                holderUid = holderUid,
                resultingBeliefUid = resultingBeliefUid,
                premiseTruthUids = jsonUidList(c.getString(1)),
                turnId = c.getLong(2),
                confidence = c.getDouble(3)
            )
        }
    }
}

class SQLiteOrganizationKnowledgeStore141(
    private val db: SQLiteDatabase
) : OrganizationKnowledgeStore141 {
    init { NpcKnowledgePersistenceSchema141.ensure(db) }

    override suspend fun appendOrganizationKnowledge(record: OrganizationKnowledgeTransmission141) {
        val values = ContentValues().apply {
            put("transmission_id", record.transmissionUid.value)
            put("campaign_id", record.campaignUid.value)
            put("organization_id", record.organizationUid.value)
            put("membership_id", record.membershipUid.value)
            put("publication_id", record.publicationUid.value)
            put("source_truth_id", record.sourceTruthUid.value)
            put("receiver_id", record.receiverUid.value)
            put("resulting_belief_id", record.resultingBeliefUid.value)
            put("turn_number", record.turnId)
            put("confidence", record.confidence)
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("gm_organization_knowledge_transmissions", null, values)
    }
}

class SQLiteNpcKnowledgeResolutionStore141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) : NpcKnowledgeResolutionStore141 {
    init { NpcKnowledgePersistenceSchema141.ensure(db) }

    override suspend fun appendResolution(record: NpcKnowledgeLifecycle141.Resolution) {
        val conflict = record.conflict
        val values = ContentValues().apply {
            put("resolution_id", record.resolutionUid.value)
            put("campaign_id", campaignUid.value)
            put("holder_id", conflict.holderUid.value)
            conflict.subjectUid?.let { put("subject_id", it.value) }
            put("predicate", conflict.predicate)
            put("competing_belief_ids_json", JSONArray(conflict.competingBeliefs.map { it.uid.value }).toString())
            record.winner?.let { put("winner_belief_id", it.uid.value) }
            put("superseded_belief_ids_json", JSONArray(record.supersededBeliefUids.map { it.value }).toString())
            put("reason", record.reason.name)
            put("turn_number", record.turnId)
            put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("gm_npc_knowledge_resolutions", null, values)
    }
}

/** Convenience holder so runtime/diagnostics can share one set of stores over one campaign.db handle. */
class SQLiteNpcKnowledgeStores141(
    db: SQLiteDatabase,
    campaignUid: EntityUid
) {
    val retractions: NpcBeliefRetractionStore141 = SQLiteNpcBeliefRetractionStore141(db)
    val inferences: NpcInferenceQueryStore141 = SQLiteNpcInferenceStore141(db)
    val organizations: OrganizationKnowledgeStore141 = SQLiteOrganizationKnowledgeStore141(db)
    val resolutions: NpcKnowledgeResolutionStore141 = SQLiteNpcKnowledgeResolutionStore141(db, campaignUid)
}

private fun jsonUidList(raw: String): List<EntityUid> {
    val array = JSONArray(raw)
    return buildList(array.length()) {
        for (index in 0 until array.length()) add(EntityUid(array.getString(index)))
    }
}
