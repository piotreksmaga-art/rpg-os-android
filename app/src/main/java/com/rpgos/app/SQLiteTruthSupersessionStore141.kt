package com.rpgos.app

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.util.UUID

/**
 * SQLite implementation backed by the same campaign.db and repository used by the GM runtime.
 * The replacement insert, predecessor closure and lineage ledger are one transaction when the
 * caller has not already opened a repository transaction.
 */
class SQLiteTruthSupersessionStore141(
    private val db: SQLiteDatabase,
    private val repository: UnifiedCampaignRepository,
    private val campaignUid: EntityUid
) : TruthSupersession141 {
    init {
        TruthSupersessionSchema141.ensure(db)
    }

    override suspend fun supersedeFact(
        previousTruthUid: EntityUid,
        replacement: CampaignTruth,
        effectiveTurn: Long
    ): CampaignTruth {
        require(effectiveTurn > 0L) {
            "TRUTH_SUPERSESSION_INVALID_TURN: effectiveTurn musi być > 0."
        }
        require(previousTruthUid != replacement.uid) {
            "TRUTH_SUPERSESSION_SAME_UID: replacement musi mieć nowy truth UID."
        }
        require(replacement.kind == TruthKind.FACT && replacement.holderUid == null) {
            "TRUTH_SUPERSESSION_REPLACEMENT_NOT_FACT: replacement musi być obiektywnym FACT."
        }
        require(replacement.validFromTurn == null || replacement.validFromTurn == effectiveTurn) {
            "TRUTH_SUPERSESSION_START_MISMATCH: replacement.validFromTurn musi być null albo równe effectiveTurn=$effectiveTurn."
        }
        require(replacement.validUntilTurn == null || replacement.validUntilTurn >= effectiveTurn) {
            "TRUTH_SUPERSESSION_INVALID_WINDOW: replacement wygasa przed effectiveTurn=$effectiveTurn."
        }

        val resolvedReplacement = replacement.copy(validFromTurn = effectiveTurn)
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        return try {
            val result = supersedeInsideTransaction(previousTruthUid, resolvedReplacement, effectiveTurn)
            if (ownsTransaction) db.setTransactionSuccessful()
            result
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private suspend fun supersedeInsideTransaction(
        previousTruthUid: EntityUid,
        replacement: CampaignTruth,
        effectiveTurn: Long
    ): CampaignTruth {
        val existingLineage = supersessionForPrevious(previousTruthUid)
        if (existingLineage != null) {
            require(
                existingLineage.replacementTruthUid == replacement.uid &&
                    existingLineage.effectiveTurn == effectiveTurn
            ) {
                "TRUTH_SUPERSESSION_CONFLICT: ${previousTruthUid.value} ma już inne następstwo."
            }
            val closedPrevious = requireNotNull(truthByUid(previousTruthUid)) {
                "TRUTH_SUPERSESSION_BROKEN_LINEAGE: brak poprzedniego FACT ${previousTruthUid.value}."
            }
            val storedReplacement = requireNotNull(truthByUid(replacement.uid)) {
                "TRUTH_SUPERSESSION_BROKEN_LINEAGE: brak replacement FACT ${replacement.uid.value}."
            }
            require(closedPrevious.validUntilTurn == effectiveTurn - 1L) {
                "TRUTH_SUPERSESSION_BROKEN_WINDOW: poprzedni FACT ma niepoprawny koniec ważności."
            }
            require(storedReplacement == replacement) {
                "TRUTH_SUPERSESSION_CONFLICT: zapisany replacement różni się od retry."
            }
            return storedReplacement
        }

        val previous = requireNotNull(truthByUid(previousTruthUid)) {
            "TRUTH_SUPERSESSION_UNKNOWN_PREVIOUS: brak FACT ${previousTruthUid.value}."
        }
        require(previous.kind == TruthKind.FACT && previous.holderUid == null) {
            "TRUTH_SUPERSESSION_PREVIOUS_NOT_FACT: ${previousTruthUid.value} nie jest obiektywnym FACT."
        }
        require(previous.subjectUid == replacement.subjectUid && previous.predicate == replacement.predicate) {
            "TRUTH_SUPERSESSION_SEMANTIC_MISMATCH: replacement musi zachować subject/predicate poprzedniego FACT."
        }
        val previousFrom = requireNotNull(previous.validFromTurn) {
            "TRUTH_SUPERSESSION_BROKEN_PREVIOUS: brak validFromTurn."
        }
        require(effectiveTurn > previousFrom) {
            "TRUTH_SUPERSESSION_INVALID_TURN: effectiveTurn=$effectiveTurn musi być późniejsze niż validFromTurn=$previousFrom."
        }
        val requiredPreviousEnd = effectiveTurn - 1L
        require(previous.validUntilTurn == null || previous.validUntilTurn >= requiredPreviousEnd) {
            "TRUTH_SUPERSESSION_PREVIOUS_EXPIRED: poprzedni FACT wygasł przed turą $requiredPreviousEnd."
        }

        supersessionForReplacement(replacement.uid)?.let { lineage ->
            error(
                "TRUTH_SUPERSESSION_REPLACEMENT_REUSED: ${replacement.uid.value} jest już replacement dla ${lineage.previousTruthUid.value}."
            )
        }

        repository.writeTruth(replacement)

        if (previous.validUntilTurn != requiredPreviousEnd) {
            val updated = db.update(
                "gm_facts",
                ContentValues().apply { put("valid_until_turn", requiredPreviousEnd) },
                "campaign_id=? AND fact_id=? AND (valid_until_turn IS NULL OR valid_until_turn>=?)",
                arrayOf(campaignUid.value, previousTruthUid.value, effectiveTurn.toString())
            )
            require(updated == 1) {
                "TRUTH_SUPERSESSION_CLOSE_FAILED: nie można atomowo zamknąć ${previousTruthUid.value}."
            }
        }

        db.insertOrThrow(
            "gm_truth_supersessions",
            null,
            ContentValues().apply {
                put("supersession_id", "SUPERSEDE-${UUID.randomUUID()}")
                put("campaign_id", campaignUid.value)
                put("previous_truth_id", previousTruthUid.value)
                put("replacement_truth_id", replacement.uid.value)
                put("effective_turn", effectiveTurn)
                put("created_at", System.currentTimeMillis())
            }
        )
        return replacement
    }

    private fun supersessionForPrevious(previousTruthUid: EntityUid): TruthSupersessionRecord141? =
        supersession("previous_truth_id", previousTruthUid)

    private fun supersessionForReplacement(replacementTruthUid: EntityUid): TruthSupersessionRecord141? =
        supersession("replacement_truth_id", replacementTruthUid)

    private fun supersession(column: String, uid: EntityUid): TruthSupersessionRecord141? {
        db.rawQuery(
            """
            SELECT previous_truth_id,replacement_truth_id,effective_turn
            FROM gm_truth_supersessions
            WHERE campaign_id=? AND $column=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, uid.value)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return TruthSupersessionRecord141(
                previousTruthUid = EntityUid(c.getString(0)),
                replacementTruthUid = EntityUid(c.getString(1)),
                effectiveTurn = c.getLong(2)
            )
        }
    }

    private fun truthByUid(uid: EntityUid): CampaignTruth? {
        db.rawQuery(
            """
            SELECT fact_id,truth_kind,subject_id,predicate,object_json,holder_id,
                   valid_from_turn,valid_until_turn,source_type,source_id,source_turn,
                   confidence,canon_status,verified
            FROM gm_facts
            WHERE campaign_id=? AND fact_id=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, uid.value)
        ).use { c -> return if (c.moveToFirst()) readTruth(c) else null }
    }

    private fun readTruth(c: Cursor): CampaignTruth = CampaignTruth(
        uid = EntityUid(c.getString(0)),
        kind = TruthKind.valueOf(c.getString(1)),
        subjectUid = if (c.isNull(2)) null else EntityUid(c.getString(2)),
        predicate = c.getString(3),
        value = c.getString(4),
        holderUid = if (c.isNull(5)) null else EntityUid(c.getString(5)),
        validFromTurn = c.getLong(6),
        validUntilTurn = if (c.isNull(7)) null else c.getLong(7),
        provenance = ProvenanceRecord(
            type = ProvenanceType.valueOf(c.getString(8)),
            sourceUid = if (c.isNull(9)) null else EntityUid(c.getString(9)),
            turnId = if (c.isNull(10)) null else c.getLong(10),
            confidence = c.getDouble(11),
            canonStatus = if (c.isNull(12)) null else c.getString(12),
            verified = c.getInt(13) != 0
        )
    )
}
