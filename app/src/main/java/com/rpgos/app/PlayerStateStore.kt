package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Canonical read-only Player State adapter.
 *
 * Unlike bounded GM-context retrieval, this repository read must never silently
 * truncate authoritative player collections or mask schema/query failures as an
 * empty legal state. Missing optional legacy tables are tolerated explicitly;
 * failures in tables that do exist are surfaced to the caller.
 */
class PlayerStateStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    fun load(): PlayerStateSnapshot? {
        val ref = ActivePlayerStore(db, campaignId).active() ?: return null
        PlayerStatePolicy.validate(ref)

        val persistent = linkedMapOf<String, Any?>()
        val derived = linkedMapOf<String, Any?>()
        val runtime = linkedMapOf<String, Any?>()

        splitLegacyStatus(ref.playerUid, persistent, derived, runtime)

        persistent["stats"] = rowsForEntity(
            table = "character_stats",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid,
            orderBy = "stat_key ASC"
        )
        persistent["skills"] = rowsForEntity(
            table = "character_skills",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid,
            orderBy = "mastery DESC, xp DESC"
        )
        persistent["techniques"] = rowsForEntity(
            table = "character_techniques",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid,
            orderBy = "is_equipped DESC, mastery DESC, xp DESC"
        )
        persistent["finances"] = firstForEntity(
            table = "character_finances",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid
        )
        persistent["organizations"] = rowsForEntity(
            table = "organization_memberships_v3",
            entityColumn = "character_uid",
            entityUid = ref.playerUid,
            whereSuffix = "status='active'",
            orderBy = "loyalty DESC"
        )
        persistent["goals"] = rowsForEntity(
            table = "character_goals",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid,
            whereSuffix = "status='active'",
            orderBy = "priority DESC"
        )

        runtime["position"] = firstForEntity(
            table = "entity_positions",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid
        )
        runtime["injuries"] = rowsForEntity(
            table = "injuries_v2",
            entityColumn = "entity_uid",
            entityUid = ref.playerUid,
            whereSuffix = "status!='healed'",
            orderBy = "severity DESC"
        )

        return PlayerStateSnapshot(
            activePlayer = ref,
            persistent = persistent,
            derived = derived,
            runtime = runtime
        )
    }

    private fun splitLegacyStatus(
        playerUid: String,
        persistent: MutableMap<String, Any?>,
        derived: MutableMap<String, Any?>,
        runtime: MutableMap<String, Any?>
    ) {
        if (!tableExists("character_status_snapshot")) return

        val row = if (hasColumn("character_status_snapshot", "entity_uid")) {
            queryOne(
                "SELECT * FROM character_status_snapshot WHERE entity_uid=? LIMIT 1",
                arrayOf(playerUid)
            )
        } else {
            // A no-UID legacy status table is accepted only when it is provably
            // a single-row player snapshot. Multi-row ambiguity is an integrity
            // error, never "first row wins".
            val count = scalarLong("SELECT COUNT(*) FROM character_status_snapshot")
            when (count) {
                0L -> emptyMap()
                1L -> queryOne("SELECT * FROM character_status_snapshot LIMIT 1", null)
                else -> error("Ambiguous legacy character_status_snapshot: $count rows without entity_uid")
            }
        }

        row.forEach { (key, value) ->
            when (PlayerStatePolicy.classifyLegacyField(key)) {
                PlayerStateClass.PERSISTENT -> persistent["legacy_status.$key"] = value
                PlayerStateClass.DERIVED -> derived["legacy_status.$key"] = value
                PlayerStateClass.RUNTIME -> runtime["legacy_status.$key"] = value
            }
        }
    }

    private fun rowsForEntity(
        table: String,
        entityColumn: String,
        entityUid: String,
        whereSuffix: String? = null,
        orderBy: String? = null
    ): List<Map<String, Any?>> {
        if (!tableExists(table)) return emptyList()
        require(hasColumn(table, entityColumn)) {
            "Existing table $table is missing required player identity column $entityColumn"
        }
        val where = buildString {
            append("$entityColumn=?")
            if (!whereSuffix.isNullOrBlank()) append(" AND ($whereSuffix)")
        }
        val order = if (orderBy.isNullOrBlank()) "" else " ORDER BY $orderBy"
        return queryMany(
            "SELECT * FROM $table WHERE $where$order",
            arrayOf(entityUid)
        )
    }

    private fun firstForEntity(
        table: String,
        entityColumn: String,
        entityUid: String
    ): Map<String, Any?> {
        if (!tableExists(table)) return emptyMap()
        require(hasColumn(table, entityColumn)) {
            "Existing table $table is missing required player identity column $entityColumn"
        }
        return queryOne(
            "SELECT * FROM $table WHERE $entityColumn=? LIMIT 1",
            arrayOf(entityUid)
        )
    }

    private fun tableExists(table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun hasColumn(table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) {
                    return@use true
                }
            }
            false
        }

    private fun scalarLong(sql: String): Long =
        db.rawQuery(sql, null).use { cursor ->
            if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
        }

    private fun queryOne(sql: String, args: Array<String>?): Map<String, Any?> =
        queryMany(sql, args).firstOrNull() ?: emptyMap()

    private fun queryMany(sql: String, args: Array<String>?): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) out += cursor.toRow()
        }
        return out
    }

    private fun Cursor.toRow(): Map<String, Any?> {
        val row = linkedMapOf<String, Any?>()
        for (index in columnNames.indices) {
            row[columnNames[index]] = when (getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> getLong(index)
                Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
                Cursor.FIELD_TYPE_BLOB -> "[BLOB ${getBlob(index).size} bytes]"
                else -> getString(index)
            }
        }
        return row
    }
}
