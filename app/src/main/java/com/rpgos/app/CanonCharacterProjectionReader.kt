package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException

/**
 * Compatibility boundary for canonical character profiles supplied by Engine API 1 World Packs.
 * Identity is required. Profile attributes are optional presentation data and remain explicit
 * empty values when an older, otherwise valid pack does not define their columns.
 */
internal class CanonCharacterProjectionReader(private val worldDb: SQLiteDatabase) {
    private val columns: Set<String> by lazy {
        worldDb.rawQuery("PRAGMA table_info(`canon_characters_v2`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(nameIndex).lowercase())
            }
        }.also { available ->
            val missing = REQUIRED_COLUMNS - available
            if (missing.isNotEmpty()) {
                throw SQLiteException("canon_characters_v2 missing required columns: ${missing.sorted().joinToString()}")
            }
        }
    }

    fun list(search: String): List<NpcListItem> {
        val projection = listOf(
            requiredText("character_uid"),
            requiredText("name"),
            optionalText("clan_uid"),
            optionalText("village_uid"),
            optionalText("status")
        ).joinToString(",")
        val filter = if (search.isBlank()) "" else " WHERE lower(`name`) LIKE lower(?)"
        val args = if (search.isBlank()) null else arrayOf("%$search%")
        val out = mutableListOf<NpcListItem>()
        worldDb.rawQuery(
            "SELECT $projection FROM `canon_characters_v2`$filter ORDER BY `name` LIMIT 1000",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += NpcListItem(
                    cursor.requiredString(0, "character_uid"),
                    cursor.requiredString(1, "name"),
                    cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(),
                    cursor.getString(4).orEmpty()
                )
            }
        }
        return out
    }

    fun profileFields(characterUid: String): List<StatLine> =
        profileRow(characterUid).map { (key, value) -> StatLine(key, value?.toString() ?: "—") }

    fun profileRow(characterUid: String): Map<String, Any?> {
        val projection = PROFILE_COLUMNS.joinToString(",") { column ->
            if (column in REQUIRED_COLUMNS) requiredText(column) else optionalText(column)
        }
        return worldDb.rawQuery(
            "SELECT $projection FROM `canon_characters_v2` WHERE `character_uid`=? LIMIT 1",
            arrayOf(characterUid)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use emptyMap()
            LinkedHashMap<String, Any?>().apply {
                cursor.columnNames.forEachIndexed { index, name ->
                    put(name, if (cursor.isNull(index)) null else cursor.getString(index))
                }
            }
        }
    }

    private fun requiredText(column: String): String {
        if (column.lowercase() !in columns) {
            throw SQLiteException("canon_characters_v2 missing required column: $column")
        }
        return "`$column` AS `$column`"
    }

    private fun optionalText(column: String): String =
        if (column.lowercase() in columns) "COALESCE(`$column`,'') AS `$column`" else "'' AS `$column`"

    private fun Cursor.requiredString(index: Int, column: String): String {
        if (isNull(index)) throw SQLiteException("canon_characters_v2 contains null required value: $column")
        return getString(index)
    }

    private companion object {
        val REQUIRED_COLUMNS = setOf("character_uid", "name")
        val PROFILE_COLUMNS = listOf(
            "character_uid", "name", "sex", "clan_uid", "village_uid", "rank_title",
            "affiliation_summary", "status"
        )
    }
}
