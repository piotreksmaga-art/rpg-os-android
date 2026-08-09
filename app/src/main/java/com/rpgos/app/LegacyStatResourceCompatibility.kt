package com.rpgos.app

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/**
 * Lossless Phase 4 compatibility projection for pre-dynamic-stat campaigns.
 *
 * Legacy rows remain authoritative in their original tables. This adapter exposes
 * them through the typed Phase 4 read model without copying or rewriting campaign
 * truth. The reserved namespace cannot be registered by a World Pack.
 */
internal object LegacyStatResourceCompatibility {
    const val WORLD_PACK_UID = "RPGOS-LEGACY-COMPAT"
    private const val STAT_UID_PREFIX = "RPGOS-LEGACY-STAT-"
    private const val RESOURCE_UID_PREFIX = "RPGOS-LEGACY-RESOURCE-"
    private const val STATUS_TABLE = "character_status_snapshot"
    private const val STATS_TABLE = "character_stats"

    fun isReservedWorldPack(uid: String): Boolean = uid == WORLD_PACK_UID

    fun isReservedDefinitionUid(uid: String): Boolean =
        uid.startsWith(STAT_UID_PREFIX) || uid.startsWith(RESOURCE_UID_PREFIX)

    fun statDefinitions(db: SQLiteDatabase): List<StatDefinition> {
        if (!tableExists(db, STATS_TABLE)) return emptyList()
        requireStatShape(db)
        val byUid = linkedMapOf<String, StatDefinition>()
        db.rawQuery("SELECT DISTINCT stat_key FROM character_stats ORDER BY stat_key", null).use { c ->
            while (c.moveToNext()) {
                require(!c.isNull(0)) { "Legacy character_stats contains null stat_key" }
                val key = c.getString(0)
                require(key.isNotBlank()) { "Legacy character_stats contains blank stat_key" }
                val definition = legacyStatDefinition(key)
                val previous = byUid.putIfAbsent(definition.statUid, definition)
                require(previous == null || previous == definition) {
                    "Deterministic legacy stat UID collision for key $key"
                }
            }
        }
        return byUid.values.toList()
    }

    fun playerStats(db: SQLiteDatabase, campaignId: String, characterUid: String): List<PlayerStat> {
        if (!tableExists(db, STATS_TABLE)) return emptyList()
        requireStatShape(db)
        val byUid = linkedMapOf<String, PlayerStat>()
        db.rawQuery(
            "SELECT stat_key,current_value FROM character_stats WHERE entity_uid=? ORDER BY stat_key",
            arrayOf(characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                require(!c.isNull(0)) { "Legacy character_stats contains null stat_key for $characterUid" }
                val key = c.getString(0)
                require(key.isNotBlank()) { "Legacy character_stats contains blank stat_key for $characterUid" }
                val value = c.finiteDoubleOrFail(1, "character_stats[$characterUid,$key]")
                val stat = PlayerStat(
                    campaignId = campaignId,
                    characterUid = characterUid,
                    statUid = legacyStatUid(key),
                    baseValue = value,
                    version = 1L
                )
                val previous = byUid.putIfAbsent(stat.statUid, stat)
                require(previous == null || previous == stat) {
                    "Conflicting duplicate legacy stat $key for character $characterUid"
                }
            }
        }
        return byUid.values.sortedBy { it.statUid }
    }

    fun resourceDefinitions(db: SQLiteDatabase): List<ResourceDefinition> {
        val columns = resourceColumns(db)
        val byUid = linkedMapOf<String, ResourceDefinition>()
        columns.forEach { column ->
            val definition = legacyResourceDefinition(column.resourceKey)
            val previous = byUid.putIfAbsent(definition.resourceUid, definition)
            require(previous == null || previous == definition) {
                "Deterministic legacy resource UID collision for key ${column.resourceKey}"
            }
        }
        return byUid.values.toList()
    }

    fun playerResources(db: SQLiteDatabase, campaignId: String, characterUid: String): List<PlayerResource> {
        if (!tableExists(db, STATUS_TABLE)) return emptyList()
        val columns = resourceColumns(db)
        if (columns.isEmpty()) return emptyList()

        val hasEntityUid = hasColumn(db, STATUS_TABLE, "entity_uid")
        val sql: String
        val args: Array<String>?
        if (hasEntityUid) {
            sql = "SELECT * FROM character_status_snapshot WHERE entity_uid=?"
            args = arrayOf(characterUid)
        } else {
            val count = scalarLong(db, "SELECT COUNT(*) FROM character_status_snapshot")
            if (count == 0L) return emptyList()
            require(count == 1L) {
                "Ambiguous legacy character_status_snapshot: $count rows without entity_uid"
            }
            val activeUid = ActivePlayerStore(db, campaignId).active()?.playerUid
            if (activeUid != characterUid) return emptyList()
            sql = "SELECT * FROM character_status_snapshot"
            args = null
        }

        val byUid = linkedMapOf<String, PlayerResource>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                columns.forEach { column ->
                    val index = c.getColumnIndex(column.columnName)
                    require(index >= 0) { "Legacy resource column disappeared: ${column.columnName}" }
                    if (c.isNull(index)) return@forEach
                    val value = c.finiteDoubleOrFail(
                        index,
                        "character_status_snapshot[$characterUid,${column.columnName}]"
                    )
                    val resource = PlayerResource(
                        campaignId = campaignId,
                        characterUid = characterUid,
                        resourceUid = legacyResourceUid(column.resourceKey),
                        currentValue = value,
                        version = 1L
                    )
                    val previous = byUid.putIfAbsent(resource.resourceUid, resource)
                    require(previous == null || previous == resource) {
                        "Conflicting duplicate legacy resource ${column.resourceKey} for character $characterUid"
                    }
                }
            }
        }
        return byUid.values.sortedBy { it.resourceUid }
    }

    private data class ResourceColumn(val columnName: String, val resourceKey: String)

    private fun resourceColumns(db: SQLiteDatabase): List<ResourceColumn> {
        if (!tableExists(db, STATUS_TABLE)) return emptyList()
        val names = mutableListOf<String>()
        db.rawQuery("PRAGMA table_info(character_status_snapshot)", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIndex >= 0) names += c.getString(nameIndex)
            }
        }

        return names.mapNotNull { column ->
            if (column.equals("entity_uid", ignoreCase = true)) return@mapNotNull null
            val key = safeCurrentResourceKey(column, names) ?: return@mapNotNull null
            require(key.isNotBlank()) { "Legacy resource column $column maps to a blank key" }
            ResourceColumn(columnName = column, resourceKey = key)
        }
    }

    /**
     * A legacy status column is promoted into PlayerResource only when its shape
     * says "current resource" without requiring a universe-specific name:
     * - current_resource_<key> / resource_<key>_current are explicit;
     * - current_<key> / <key>_current require a matching max column, unless
     *   Phase 3 already classifies the current_* field as RUNTIME;
     * - a bare <key> is accepted only when it has a max sibling and Phase 3
     *   already classifies that bare field as RUNTIME.
     *
     * max/effective/regeneration columns themselves are never promoted. Their
     * semantics remain DERIVED/legacy until a later rules layer can rebuild them.
     */
    private fun safeCurrentResourceKey(column: String, allColumns: List<String>): String? {
        if (PlayerStatePolicy.classifyLegacyField(column) == PlayerStateClass.DERIVED) return null
        val lower = column.lowercase()

        if (lower.startsWith("current_resource_")) {
            return column.substring("current_resource_".length)
        }
        if (lower.startsWith("resource_") && lower.endsWith("_current")) {
            return column.substring("resource_".length, column.length - "_current".length)
        }
        if (lower.startsWith("current_")) {
            val key = column.substring("current_".length)
            if (hasMaxSibling(key, allColumns) ||
                PlayerStatePolicy.classifyLegacyField(column) == PlayerStateClass.RUNTIME
            ) return key
            return null
        }
        if (lower.endsWith("_current")) {
            val key = column.dropLast("_current".length)
            return key.takeIf { hasMaxSibling(it, allColumns) }
        }
        return column.takeIf {
            hasMaxSibling(it, allColumns) &&
                PlayerStatePolicy.classifyLegacyField(column) == PlayerStateClass.RUNTIME
        }
    }

    private fun hasMaxSibling(key: String, allColumns: List<String>): Boolean =
        allColumns.any {
            it.equals("max_$key", ignoreCase = true) || it.equals("${key}_max", ignoreCase = true)
        }

    private fun legacyStatDefinition(key: String): StatDefinition = StatDefinition(
        statUid = legacyStatUid(key),
        key = key,
        category = "legacy_compat",
        worldPackUid = WORLD_PACK_UID
    )

    private fun legacyResourceDefinition(key: String): ResourceDefinition = ResourceDefinition(
        resourceUid = legacyResourceUid(key),
        key = key,
        category = "legacy_compat",
        worldPackUid = WORLD_PACK_UID
    )

    private fun legacyStatUid(key: String): String = stableUid(STAT_UID_PREFIX, key)
    private fun legacyResourceUid(key: String): String = stableUid(RESOURCE_UID_PREFIX, key)

    private fun stableUid(prefix: String, identity: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return prefix + hex
    }

    private fun requireStatShape(db: SQLiteDatabase) {
        listOf("entity_uid", "stat_key", "current_value").forEach { column ->
            require(hasColumn(db, STATS_TABLE, column)) {
                "Existing character_stats is missing required compatibility column $column"
            }
        }
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(table)
        ).use { it.moveToFirst() }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) {
                if (nameIndex >= 0 && c.getString(nameIndex).equals(column, ignoreCase = true)) return@use true
            }
            false
        }

    private fun scalarLong(db: SQLiteDatabase, sql: String): Long =
        db.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    private fun Cursor.finiteDoubleOrFail(index: Int, source: String): Double {
        require(index >= 0 && !isNull(index)) { "Legacy numeric value is null: $source" }
        val value = when (getType(index)) {
            Cursor.FIELD_TYPE_INTEGER -> getLong(index).toDouble()
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_STRING -> getString(index)?.toDoubleOrNull()
            else -> null
        } ?: error("Legacy numeric value is not representable as Double: $source")
        require(value.isFinite()) { "Legacy numeric value must be finite: $source" }
        return value
    }
}
