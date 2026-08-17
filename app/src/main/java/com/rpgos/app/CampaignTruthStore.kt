package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

class CampaignTruthStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    fun record(
        kind: TruthKind,
        predicate: String,
        provenance: Provenance,
        subjectUid: String? = null,
        objectValue: String? = null,
        perspectiveUid: String? = null,
        narrativeText: String? = null,
        truthUid: String = "TRUTH-${UUID.randomUUID()}",
        supersedesTruthUid: String? = null
    ): CampaignTruthRecord {
        val record = CampaignTruthRecord(
            truthUid = truthUid,
            campaignId = campaignId,
            kind = kind,
            subjectUid = subjectUid,
            predicate = predicate,
            objectValue = objectValue,
            perspectiveUid = perspectiveUid,
            narrativeText = narrativeText,
            provenance = provenance,
            supersedesTruthUid = supersedesTruthUid
        )
        require(CampaignTruthPolicy.validate(record).isEmpty()) {
            "Nieprawidłowy CampaignTruthRecord: ${CampaignTruthPolicy.validate(record).joinToString()}"
        }

        tx {
            if (!supersedesTruthUid.isNullOrBlank()) {
                val oldCampaign = campaignForTruth(supersedesTruthUid)
                require(oldCampaign == null || oldCampaign == campaignId) {
                    "Nie można supersede truth record z innej kampanii."
                }
                db.execSQL(
                    "UPDATE campaign_truth_records SET active=0 WHERE truth_uid=? AND campaign_id=?",
                    arrayOf(supersedesTruthUid, campaignId)
                )
            }

            val values = ContentValues().apply {
                put("truth_uid", record.truthUid)
                put("campaign_id", record.campaignId)
                put("truth_kind", record.kind.name)
                put("subject_uid", record.subjectUid)
                put("predicate", record.predicate)
                put("object_value", record.objectValue)
                put("perspective_uid", record.perspectiveUid)
                put("narrative_text", record.narrativeText)
                put("source_type", record.provenance.sourceType.name)
                put("source_id", record.provenance.sourceId)
                record.provenance.createdTurn?.let { put("created_turn", it) }
                put("created_event", record.provenance.createdEvent)
                put("confidence", record.provenance.confidence)
                put("canon_status", record.provenance.canonStatus)
                put("verified", if (record.provenance.verified) 1 else 0)
                put("actor_uid", record.provenance.actorUid)
                put("method", record.provenance.method)
                put("engine_version", record.provenance.engineVersion)
                put("created_at", record.createdAt)
                put("supersedes_truth_uid", record.supersedesTruthUid)
                put("active", if (record.active) 1 else 0)
            }
            db.insertOrThrow("campaign_truth_records", null, values)
        }
        return record
    }

    fun active(
        kind: TruthKind? = null,
        subjectUid: String? = null,
        perspectiveUid: String? = null,
        limit: Int = 100
    ): List<CampaignTruthRecord> {
        val boundedLimit = limit.coerceIn(1, 500)
        val clauses = mutableListOf("campaign_id=?", "active=1")
        val args = mutableListOf(campaignId)
        if (kind != null) {
            clauses += "truth_kind=?"
            args += kind.name
        }
        if (!subjectUid.isNullOrBlank()) {
            clauses += "subject_uid=?"
            args += subjectUid
        }
        if (!perspectiveUid.isNullOrBlank()) {
            clauses += "perspective_uid=?"
            args += perspectiveUid
        }

        return db.rawQuery(
            "SELECT truth_uid,campaign_id,truth_kind,subject_uid,predicate,object_value," +
                "perspective_uid,narrative_text,source_type,source_id,created_turn,created_event," +
                "confidence,canon_status,verified,actor_uid,method,engine_version,created_at," +
                "supersedes_truth_uid,active FROM campaign_truth_records WHERE " +
                clauses.joinToString(" AND ") + " ORDER BY created_at DESC LIMIT $boundedLimit",
            args.toTypedArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CampaignTruthRecord(
                            truthUid = cursor.getString(0),
                            campaignId = cursor.getString(1),
                            kind = TruthKind.valueOf(cursor.getString(2)),
                            subjectUid = cursor.getStringOrNull(3),
                            predicate = cursor.getString(4),
                            objectValue = cursor.getStringOrNull(5),
                            perspectiveUid = cursor.getStringOrNull(6),
                            narrativeText = cursor.getStringOrNull(7),
                            provenance = Provenance(
                                sourceType = ProvenanceSourceType.valueOf(cursor.getString(8)),
                                sourceId = cursor.getStringOrNull(9),
                                createdTurn = if (cursor.isNull(10)) null else cursor.getLong(10),
                                createdEvent = cursor.getStringOrNull(11),
                                confidence = cursor.getDouble(12),
                                canonStatus = cursor.getStringOrNull(13),
                                verified = cursor.getInt(14) != 0,
                                actorUid = cursor.getStringOrNull(15),
                                method = cursor.getStringOrNull(16),
                                engineVersion = cursor.getStringOrNull(17)
                            ),
                            createdAt = cursor.getLong(18),
                            supersedesTruthUid = cursor.getStringOrNull(19),
                            active = cursor.getInt(20) != 0
                        )
                    )
                }
            }
        }
    }

    fun activeForContext(limit: Int = 80): List<Map<String, Any?>> =
        active(limit = limit).map { record ->
            linkedMapOf(
                "truth_uid" to record.truthUid,
                "truth_kind" to record.kind.name,
                "subject_uid" to record.subjectUid,
                "predicate" to record.predicate,
                "object_value" to record.objectValue,
                "perspective_uid" to record.perspectiveUid,
                "narrative_text" to record.narrativeText,
                "source_type" to record.provenance.sourceType.name,
                "source_id" to record.provenance.sourceId,
                "created_turn" to record.provenance.createdTurn,
                "created_event" to record.provenance.createdEvent,
                "confidence" to record.provenance.confidence,
                "canon_status" to record.provenance.canonStatus,
                "verified" to record.provenance.verified,
                "actor_uid" to record.provenance.actorUid,
                "method" to record.provenance.method
            )
        }

    private fun campaignForTruth(truthUid: String): String? =
        db.rawQuery(
            "SELECT campaign_id FROM campaign_truth_records WHERE truth_uid=? LIMIT 1",
            arrayOf(truthUid)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun <T> tx(block: () -> T): T {
        if (db.inTransaction()) return block()
        db.beginTransaction()
        return try {
            val result = block()
            db.setTransactionSuccessful()
            result
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)
}
