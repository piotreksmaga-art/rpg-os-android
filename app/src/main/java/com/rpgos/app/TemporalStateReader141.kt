package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class TemporalStateValue141(
    val entityType: String,
    val entityUid: EntityUid,
    val field: String,
    val value: String?,
    val atTurn: Long,
    val resolvedFromTurn: Long?,
    val existed: Boolean
)

/**
 * Read-only reconstruction of mutable state at an arbitrary accepted turn.
 *
 * Current state remains optimized in gm_entity_state. Historical values are
 * reconstructed from the immutable mutation ledger instead of rolling the DB
 * backwards or trusting narrative summaries.
 */
class TemporalStateReader141(
    private val db: SQLiteDatabase,
    private val campaignUid: EntityUid
) {
    fun valueAt(
        entityType: String,
        entityUid: EntityUid,
        field: String,
        atTurn: Long
    ): TemporalStateValue141 {
        require(atTurn >= 0L) { "atTurn nie może być ujemny." }

        data class MutationRow(
            val turn: Long,
            val oldValue: String?,
            val newValue: String?,
            val operation: MutationOperation
        )

        val rows = mutableListOf<MutationRow>()
        db.rawQuery(
            """
            SELECT turn_number,old_value_json,new_value_json,operation
            FROM gm_state_mutations
            WHERE campaign_id=? AND entity_type=? AND entity_id=? AND field_key=?
            ORDER BY turn_number ASC, created_at ASC
            """.trimIndent(),
            arrayOf(campaignUid.value, entityType, entityUid.value, field)
        ).use { c ->
            while (c.moveToNext()) {
                rows += MutationRow(
                    turn = c.getLong(0),
                    oldValue = if (c.isNull(1)) null else c.getString(1),
                    newValue = if (c.isNull(2)) null else c.getString(2),
                    operation = MutationOperation.valueOf(c.getString(3))
                )
            }
        }

        val latest = rows.lastOrNull { it.turn <= atTurn }
        if (latest != null) {
            val value = if (latest.operation == MutationOperation.REMOVE) null else latest.newValue
            return TemporalStateValue141(
                entityType = entityType,
                entityUid = entityUid,
                field = field,
                value = value,
                atTurn = atTurn,
                resolvedFromTurn = latest.turn,
                existed = value != null
            )
        }

        // If the requested turn predates the first GM141 mutation, its oldValue
        // is the best durable representation of the state immediately before
        // that mutation. A null oldValue explicitly means the field did not yet
        // exist.
        rows.firstOrNull()?.let { first ->
            return TemporalStateValue141(
                entityType = entityType,
                entityUid = entityUid,
                field = field,
                value = first.oldValue,
                atTurn = atTurn,
                resolvedFromTurn = null,
                existed = first.oldValue != null
            )
        }

        // Fields that have never mutated can safely be read from current state
        // only when their valid_from_turn is not later than the requested turn.
        db.rawQuery(
            """
            SELECT value_json,valid_from_turn
            FROM gm_entity_state
            WHERE campaign_id=? AND entity_type=? AND entity_id=? AND field_key=?
            LIMIT 1
            """.trimIndent(),
            arrayOf(campaignUid.value, entityType, entityUid.value, field)
        ).use { c ->
            if (c.moveToFirst()) {
                val validFrom = c.getLong(1)
                if (validFrom <= atTurn) {
                    return TemporalStateValue141(
                        entityType = entityType,
                        entityUid = entityUid,
                        field = field,
                        value = c.getString(0),
                        atTurn = atTurn,
                        resolvedFromTurn = validFrom,
                        existed = true
                    )
                }
            }
        }

        return TemporalStateValue141(
            entityType = entityType,
            entityUid = entityUid,
            field = field,
            value = null,
            atTurn = atTurn,
            resolvedFromTurn = null,
            existed = false
        )
    }

    fun entityAt(
        entityType: String,
        entityUid: EntityUid,
        atTurn: Long,
        limit: Int = 512
    ): List<TemporalStateValue141> {
        require(limit in 1..5000) { "Niepoprawny limit pól temporalnych: $limit" }
        val fields = linkedSetOf<String>()
        db.rawQuery(
            """
            SELECT field_key FROM gm_entity_state
            WHERE campaign_id=? AND entity_type=? AND entity_id=?
            UNION
            SELECT field_key FROM gm_state_mutations
            WHERE campaign_id=? AND entity_type=? AND entity_id=?
            LIMIT ?
            """.trimIndent(),
            arrayOf(
                campaignUid.value, entityType, entityUid.value,
                campaignUid.value, entityType, entityUid.value,
                limit.toString()
            )
        ).use { c -> while (c.moveToNext()) fields += c.getString(0) }

        return fields.map { field -> valueAt(entityType, entityUid, field, atTurn) }
    }
}
