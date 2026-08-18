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
        if (tableExists("origin_definitions_v2")) {
            persistent["phase9"] = phase9Context(ref.playerUid)
        }

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
        val row = legacyStatusRow(playerUid)
        row.forEach { (key, value) ->
            when (PlayerStatePolicy.classifyLegacyField(key)) {
                PlayerStateClass.PERSISTENT -> persistent["legacy_status.$key"] = value
                PlayerStateClass.DERIVED -> derived["legacy_status.$key"] = value
                PlayerStateClass.RUNTIME -> runtime["legacy_status.$key"] = value
            }
        }
    }

    private fun phase9Context(characterUid: String): Map<String, Any?> = linkedMapOf(
        "origins" to phase9Origins(characterUid),
        "innate_features" to phase9InnateFeatures(characterUid),
        "evolution_states" to phase9EvolutionStates(characterUid),
        "attained_stages" to phase9AttainedStages(characterUid),
        "form_unlocks" to phase9FormUnlocks(characterUid),
        "active_forms" to phase9ActiveForms(characterUid),
        "unresolved_legacy" to phase9LegacyEvidence(characterUid)
    )

    private fun phase9Origins(characterUid: String): List<PlayerOrigin> {
        val out = mutableListOf<PlayerOrigin>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,origin_uid,relationship_kind,entry_version,provenance FROM player_origins_v2 WHERE campaign_id=? AND character_uid=? ORDER BY origin_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) out += PlayerOrigin(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5))
        }
        return out
    }

    private fun phase9InnateFeatures(characterUid: String): List<PlayerInnateFeature> {
        val out = mutableListOf<PlayerInnateFeature>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,feature_uid,acquired_chapter,entry_version,provenance FROM player_innate_features WHERE campaign_id=? AND character_uid=? ORDER BY feature_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) out += PlayerInnateFeature(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3), c.getLong(4), c.getString(5))
        }
        return out
    }

    private fun phase9EvolutionStates(characterUid: String): List<PlayerEvolutionState> {
        val out = mutableListOf<PlayerEvolutionState>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,path_uid,current_stage_uid,state_version,provenance FROM player_evolution_states WHERE campaign_id=? AND character_uid=? ORDER BY path_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) out += PlayerEvolutionState(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getLong(4), c.getString(5))
        }
        return out
    }

    private fun phase9AttainedStages(characterUid: String): List<PlayerEvolutionStage> {
        val out = mutableListOf<PlayerEvolutionStage>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,stage_uid,attained_via_transition_uid,attained_chapter,entry_version,provenance FROM player_evolution_stages WHERE campaign_id=? AND character_uid=? ORDER BY stage_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) out += PlayerEvolutionStage(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), if (c.isNull(4)) null else c.getLong(4), c.getLong(5), c.getString(6))
        }
        return out
    }

    private fun phase9FormUnlocks(characterUid: String): List<PlayerFormUnlock> {
        val out = mutableListOf<PlayerFormUnlock>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,form_uid,entry_version,provenance FROM player_form_unlocks WHERE campaign_id=? AND character_uid=? ORDER BY form_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) out += PlayerFormUnlock(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getString(4))
        }
        return out
    }

    private fun phase9ActiveForms(characterUid: String): List<PlayerActiveForm> {
        val out = mutableListOf<PlayerActiveForm>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,form_uid,activated_at,state_version,provenance FROM player_active_forms WHERE campaign_id=? AND character_uid=? ORDER BY form_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) out += PlayerActiveForm(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getLong(3), c.getLong(4), c.getString(5))
        }
        return out
    }

    private fun phase9LegacyEvidence(characterUid: String): List<LegacyPhase9Evidence> {
        val candidates = setOf(
            "race", "species", "clan", "clan_uid", "bloodline", "lineage", "heritage", "innate",
            "innate_trait", "trait", "mutation", "evolution", "evolution_stage", "stage", "form",
            "transformation", "kekkei_genkai"
        )
        return legacyStatusRow(characterUid).entries
            .filter { (key, value) ->
                value != null && candidates.any { token -> key.lowercase() == token || key.lowercase().contains(token) }
            }
            .map { LegacyPhase9Evidence(it.key, it.value.toString()) }
            .sortedWith(compareBy({ it.field }, { it.value }))
    }

    private fun legacyStatusRow(playerUid: String): Map<String, Any?> {
        if (!tableExists("character_status_snapshot")) return emptyMap()
        return if (hasColumn("character_status_snapshot", "entity_uid")) {
            queryOne(
                "SELECT * FROM character_status_snapshot WHERE entity_uid=? LIMIT 1",
                arrayOf(playerUid)
            )
        } else {
            val count = scalarLong("SELECT COUNT(*) FROM character_status_snapshot")
            when (count) {
                0L -> emptyMap()
                1L -> queryOne("SELECT * FROM character_status_snapshot LIMIT 1", null)
                else -> error("Ambiguous legacy character_status_snapshot: $count rows without entity_uid")
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
        require(hasColumn(table, entityColumn)) { "Existing table $table is missing required player identity column $entityColumn" }
        val where = buildString {
            append("$entityColumn=?")
            if (!whereSuffix.isNullOrBlank()) append(" AND ($whereSuffix)")
        }
        val order = if (orderBy.isNullOrBlank()) "" else " ORDER BY $orderBy"
        return queryMany("SELECT * FROM $table WHERE $where$order", arrayOf(entityUid))
    }

    private fun firstForEntity(table: String, entityColumn: String, entityUid: String): Map<String, Any?> {
        if (!tableExists(table)) return emptyMap()
        require(hasColumn(table, entityColumn)) { "Existing table $table is missing required player identity column $entityColumn" }
        return queryOne("SELECT * FROM $table WHERE $entityColumn=? LIMIT 1", arrayOf(entityUid))
    }

    private fun tableExists(table: String): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }
    private fun hasColumn(table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) if (nameIndex >= 0 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) return@use true
        false
    }
    private fun scalarLong(sql: String): Long = db.rawQuery(sql, null).use { cursor -> if (!cursor.moveToFirst()) 0L else cursor.getLong(0) }
    private fun queryOne(sql: String, args: Array<String>?): Map<String, Any?> = queryMany(sql, args).firstOrNull() ?: emptyMap()
    private fun queryMany(sql: String, args: Array<String>?): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        db.rawQuery(sql, args).use { cursor -> while (cursor.moveToNext()) out += cursor.toRow() }
        return out
    }
    private fun Cursor.toRow(): Map<String, Any?> {
        val row = linkedMapOf<String, Any?>()
        for (index in columnNames.indices) row[columnNames[index]] = when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_BLOB -> "[BLOB ${getBlob(index).size} bytes]"
            else -> getString(index)
        }
        return row
    }
}
