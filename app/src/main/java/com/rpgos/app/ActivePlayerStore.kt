package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Persists the single authoritative player identity for a campaign. */
class ActivePlayerStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    fun active(): ActivePlayerRef? {
        db.rawQuery(
            "SELECT player_uid FROM active_player_ref WHERE campaign_id=? LIMIT 1",
            arrayOf(campaignId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val uid = c.getString(0)?.trim().orEmpty()
            return if (uid.isBlank()) null else ActivePlayerRef(campaignId, uid)
        }
    }

    fun requireActive(): ActivePlayerRef = active() ?: error("No active player configured for campaign $campaignId")

    /** Ordinary supported identity mutation is ADMIN-only and never gains permission from absent guards. */
    fun set(playerUid: String): ActivePlayerRef {
        val ref = ActivePlayerRef(campaignId, playerUid.trim())
        PlayerStatePolicy.validate(ref)
        require(playerIdentityExists(ref.playerUid)) { "Player UID does not exist in campaign data: ${ref.playerUid}" }
        require(GameplayMutationDatabaseGuards.isInstalled(db)) { "RPGOS-G32:ADMIN_REQUIRES_PRODUCTION_READINESS" }
        return withAdministrativeMutationAuthority(db, campaignId) {
            persist(ref)
            ref
        }
    }

    /**
     * One-time migration helper for legacy saves. This is intentionally not an ordinary application
     * writer: CurrentSchema owns its invocation before G32 guards are installed during explicit bootstrap.
     */
    fun seedFromLegacyIfMissing(): ActivePlayerRef? {
        active()?.let { return it }
        val candidate = legacyCandidate() ?: return null
        val ref = ActivePlayerRef(campaignId, candidate)
        PlayerStatePolicy.validate(ref)
        if (GameplayMutationDatabaseGuards.isInstalled(db)) {
            return withAdministrativeMutationAuthority(db, campaignId) { persist(ref); ref }
        }
        persist(ref)
        return ref
    }

    private fun persist(ref: ActivePlayerRef) {
        db.execSQL(
            """
            INSERT INTO active_player_ref(campaign_id,player_uid,updated_at)
            VALUES(?,?,strftime('%s','now'))
            ON CONFLICT(campaign_id) DO UPDATE SET
                player_uid=excluded.player_uid,
                updated_at=excluded.updated_at
            """.trimIndent(),
            arrayOf(ref.campaignId, ref.playerUid)
        )
    }

    private fun playerIdentityExists(playerUid: String): Boolean {
        val sources = listOf(
            EntitySource("character_status_snapshot", "entity_uid"),
            EntitySource("character_stats", "entity_uid"),
            EntitySource("character_skills", "entity_uid"),
            EntitySource("character_techniques", "entity_uid"),
            EntitySource("character_finances", "entity_uid"),
            EntitySource("character_goals", "entity_uid"),
            EntitySource("entity_positions", "entity_uid"),
            EntitySource("organization_memberships_v3", "character_uid")
        )
        return sources.any { source -> containsUid(source, playerUid) }
    }

    private fun legacyCandidate(): String? {
        uniqueUidFrom("character_status_snapshot", "entity_uid")?.let { return it }
        val playerCentricSources = listOf(
            EntitySource("character_stats", "entity_uid"), EntitySource("character_skills", "entity_uid"),
            EntitySource("character_techniques", "entity_uid"), EntitySource("character_finances", "entity_uid"),
            EntitySource("character_goals", "entity_uid")
        )
        val sourceCandidates = playerCentricSources.mapNotNull(::uidsFrom)
        if (sourceCandidates.isEmpty()) return null
        val scores = linkedMapOf<String, Int>()
        sourceCandidates.forEach { uids -> uids.distinct().forEach { uid -> scores[uid] = (scores[uid] ?: 0) + 1 } }
        return PlayerIdentityPolicy.resolveUnambiguous(scores)
    }

    private fun uniqueUidFrom(table: String, column: String): String? {
        val uids = uidsFrom(EntitySource(table, column)) ?: return null
        return uids.distinct().singleOrNull()
    }

    private fun containsUid(source: EntitySource, uid: String): Boolean {
        if (!tableExists(source.table) || !hasColumn(source.table, source.column)) return false
        db.rawQuery("SELECT 1 FROM ${source.table} WHERE ${source.column}=? LIMIT 1", arrayOf(uid)).use { return it.moveToFirst() }
    }

    private fun uidsFrom(source: EntitySource): List<String>? {
        if (!tableExists(source.table) || !hasColumn(source.table, source.column)) return null
        val out = mutableListOf<String>()
        db.rawQuery("SELECT DISTINCT ${source.column} FROM ${source.table} WHERE ${source.column} IS NOT NULL ORDER BY ${source.column}", null).use { cursor ->
            while (cursor.moveToNext()) {
                val uid = cursor.getString(0)?.trim().orEmpty()
                if (uid.isNotBlank()) out += uid
            }
        }
        return out
    }

    private fun tableExists(table: String): Boolean = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)
    ).use { it.moveToFirst() }

    private fun hasColumn(table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        while (cursor.moveToNext()) if (nameIndex >= 0 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) return@use true
        false
    }

    private data class EntitySource(val table: String, val column: String)
}

object PlayerIdentityPolicy {
    fun resolveUnambiguous(scores: Map<String, Int>): String? {
        val normalized = scores.mapKeys { it.key.trim() }.filterKeys { it.isNotBlank() }.filterValues { it > 0 }
        if (normalized.isEmpty()) return null
        if (normalized.size == 1) return normalized.keys.single()
        val bestScore = normalized.values.maxOrNull() ?: return null
        val best = normalized.filterValues { it == bestScore }.keys
        return if (bestScore >= 2 && best.size == 1) best.single() else null
    }
}