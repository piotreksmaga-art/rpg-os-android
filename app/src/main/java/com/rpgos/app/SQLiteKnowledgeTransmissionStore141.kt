package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** Uses the same campaign.db connection as the active GM141 repository. */
class SQLiteKnowledgeTransmissionStore141(
    private val db: SQLiteDatabase,
    private val activeCampaignUid: EntityUid
) : KnowledgeTransmissionStore141 {
    override suspend fun appendKnowledgeTransmission(record: KnowledgeTransmission141) {
        require(record.campaignUid == activeCampaignUid) {
            "Knowledge transmission należy do innej kampanii."
        }
        require(db.inTransaction()) {
            "Knowledge transmission musi być zapisany wewnątrz transakcji tury."
        }
        db.insertOrThrow(
            "gm_knowledge_transmissions",
            null,
            ContentValues().apply {
                put("transmission_id", record.transmissionUid.value)
                put("campaign_id", record.campaignUid.value)
                put("source_truth_id", record.sourceTruthUid.value)
                record.sourceNpcUid?.let { put("source_npc_id", it.value) }
                put("receiver_id", record.receiverUid.value)
                put("resulting_belief_id", record.resultingBeliefUid.value)
                put("channel", record.channel.name)
                put("turn_number", record.turnId)
                put("confidence", record.confidence)
                put("created_at", System.currentTimeMillis())
            }
        )
    }

    override suspend fun knowledgeTransmissionsForReceiver(
        campaignUid: EntityUid,
        receiverUid: EntityUid,
        beforeOrAtTurn: Long?,
        limit: Int
    ): List<KnowledgeTransmission141> {
        require(campaignUid == activeCampaignUid) { "Odczyt dotyczy innej kampanii." }
        require(limit in 1..1000) { "Niepoprawny limit knowledge transmissions: $limit" }
        val turnClause = if (beforeOrAtTurn == null) "" else " AND turn_number<=?"
        val args = mutableListOf(campaignUid.value, receiverUid.value)
        beforeOrAtTurn?.let { args += it.toString() }
        args += limit.toString()

        val out = mutableListOf<KnowledgeTransmission141>()
        db.rawQuery(
            """
            SELECT transmission_id,source_truth_id,source_npc_id,receiver_id,
                   resulting_belief_id,channel,turn_number,confidence
            FROM gm_knowledge_transmissions
            WHERE campaign_id=? AND receiver_id=?$turnClause
            ORDER BY turn_number DESC, created_at DESC
            LIMIT ?
            """.trimIndent(),
            args.toTypedArray()
        ).use { c ->
            while (c.moveToNext()) {
                out += KnowledgeTransmission141(
                    transmissionUid = EntityUid(c.getString(0)),
                    campaignUid = campaignUid,
                    sourceTruthUid = EntityUid(c.getString(1)),
                    sourceNpcUid = if (c.isNull(2)) null else EntityUid(c.getString(2)),
                    receiverUid = EntityUid(c.getString(3)),
                    resultingBeliefUid = EntityUid(c.getString(4)),
                    channel = KnowledgeChannel141.valueOf(c.getString(5)),
                    turnId = c.getLong(6),
                    confidence = c.getDouble(7)
                )
            }
        }
        return out
    }
}
