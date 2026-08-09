package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Phase 4 persistence adapter. Kept internal so player-value mutation does not
 * become a public bypass around the future PlayerDomainEngine/transaction path.
 */
internal class StatResourceStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun statDefinitions(worldPackUid: String? = null): List<StatDefinition> {
        val args = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val out = mutableListOf<StatDefinition>()
        db.rawQuery(
            "SELECT stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid " +
                "FROM stat_definitions$where ORDER BY world_pack_uid,category,stat_key",
            args
        ).use { c ->
            while (c.moveToNext()) {
                out += StatDefinition(
                    statUid = c.getString(0),
                    key = c.getString(1),
                    category = c.getString(2),
                    unit = c.stringOrNull(3),
                    minValue = c.doubleOrNull(4),
                    maxValue = c.doubleOrNull(5),
                    growthRuleUid = c.stringOrNull(6),
                    derivationRuleUid = c.stringOrNull(7),
                    worldPackUid = c.getString(8)
                )
            }
        }
        return out
    }

    fun resourceDefinitions(worldPackUid: String? = null): List<ResourceDefinition> {
        val args = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val out = mutableListOf<ResourceDefinition>()
        db.rawQuery(
            "SELECT resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid " +
                "FROM resource_definitions$where ORDER BY world_pack_uid,category,resource_key",
            args
        ).use { c ->
            while (c.moveToNext()) {
                out += ResourceDefinition(
                    resourceUid = c.getString(0),
                    key = c.getString(1),
                    category = c.getString(2),
                    unit = c.stringOrNull(3),
                    minValue = c.doubleOrNull(4),
                    maxValue = c.doubleOrNull(5),
                    maxRuleUid = c.stringOrNull(6),
                    regenerationRuleUid = c.stringOrNull(7),
                    worldPackUid = c.getString(8)
                )
            }
        }
        return out
    }

    fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        definitions.forEach {
            StatResourcePolicy.validate(it)
            require(it.worldPackUid == worldPackUid) { "StatDefinition belongs to another World Pack: ${it.statUid}" }
        }
        inTransaction {
            definitions.forEach { definition ->
                rejectUidHijack("stat_definitions", "stat_uid", definition.statUid, worldPackUid)
                db.execSQL(
                    """
                    INSERT INTO stat_definitions(
                        stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid
                    ) VALUES(?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(stat_uid) DO UPDATE SET
                        stat_key=excluded.stat_key,
                        category=excluded.category,
                        unit=excluded.unit,
                        min_value=excluded.min_value,
                        max_value=excluded.max_value,
                        growth_rule_uid=excluded.growth_rule_uid,
                        derivation_rule_uid=excluded.derivation_rule_uid
                    """.trimIndent(),
                    arrayOf(
                        definition.statUid, definition.key, definition.category, definition.unit,
                        definition.minValue, definition.maxValue, definition.growthRuleUid,
                        definition.derivationRuleUid, definition.worldPackUid
                    )
                )
            }
        }
    }

    fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        definitions.forEach {
            StatResourcePolicy.validate(it)
            require(it.worldPackUid == worldPackUid) { "ResourceDefinition belongs to another World Pack: ${it.resourceUid}" }
        }
        inTransaction {
            definitions.forEach { definition ->
                rejectUidHijack("resource_definitions", "resource_uid", definition.resourceUid, worldPackUid)
                db.execSQL(
                    """
                    INSERT INTO resource_definitions(
                        resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid
                    ) VALUES(?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(resource_uid) DO UPDATE SET
                        resource_key=excluded.resource_key,
                        category=excluded.category,
                        unit=excluded.unit,
                        min_value=excluded.min_value,
                        max_value=excluded.max_value,
                        max_rule_uid=excluded.max_rule_uid,
                        regeneration_rule_uid=excluded.regeneration_rule_uid
                    """.trimIndent(),
                    arrayOf(
                        definition.resourceUid, definition.key, definition.category, definition.unit,
                        definition.minValue, definition.maxValue, definition.maxRuleUid,
                        definition.regenerationRuleUid, definition.worldPackUid
                    )
                )
            }
        }
    }

    fun playerStats(characterUid: String): List<PlayerStat> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val out = mutableListOf<PlayerStat>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,stat_uid,base_value,version FROM player_stats " +
                "WHERE campaign_id=? AND character_uid=? ORDER BY stat_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                out += PlayerStat(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4))
            }
        }
        return out
    }

    fun playerResources(characterUid: String): List<PlayerResource> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val out = mutableListOf<PlayerResource>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,resource_uid,current_value,version FROM player_resources " +
                "WHERE campaign_id=? AND character_uid=? ORDER BY resource_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                out += PlayerResource(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4))
            }
        }
        return out
    }

    internal fun savePlayerStat(stat: PlayerStat) {
        StatResourcePolicy.validate(stat)
        require(stat.campaignId == campaignId) { "PlayerStat belongs to another campaign" }
        requireDefinition("stat_definitions", "stat_uid", stat.statUid)
        db.execSQL(
            """
            INSERT INTO player_stats(campaign_id,character_uid,stat_uid,base_value,version)
            VALUES(?,?,?,?,?)
            ON CONFLICT(campaign_id,character_uid,stat_uid) DO UPDATE SET
                base_value=excluded.base_value,
                version=excluded.version
            """.trimIndent(),
            arrayOf(stat.campaignId, stat.characterUid, stat.statUid, stat.baseValue, stat.version)
        )
    }

    internal fun savePlayerResource(resource: PlayerResource) {
        StatResourcePolicy.validate(resource)
        require(resource.campaignId == campaignId) { "PlayerResource belongs to another campaign" }
        requireDefinition("resource_definitions", "resource_uid", resource.resourceUid)
        db.execSQL(
            """
            INSERT INTO player_resources(campaign_id,character_uid,resource_uid,current_value,version)
            VALUES(?,?,?,?,?)
            ON CONFLICT(campaign_id,character_uid,resource_uid) DO UPDATE SET
                current_value=excluded.current_value,
                version=excluded.version
            """.trimIndent(),
            arrayOf(
                resource.campaignId, resource.characterUid, resource.resourceUid,
                resource.currentValue, resource.version
            )
        )
    }

    private fun requireDefinition(table: String, uidColumn: String, uid: String) {
        val exists = db.rawQuery(
            "SELECT 1 FROM $table WHERE $uidColumn=? LIMIT 1",
            arrayOf(uid)
        ).use { it.moveToFirst() }
        require(exists) { "Unknown definition UID: $uid" }
    }

    private fun rejectUidHijack(table: String, uidColumn: String, uid: String, worldPackUid: String) {
        val existingOwner = db.rawQuery(
            "SELECT world_pack_uid FROM $table WHERE $uidColumn=? LIMIT 1",
            arrayOf(uid)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(existingOwner == null || existingOwner == worldPackUid) {
            "Definition UID $uid is already owned by World Pack $existingOwner"
        }
    }

    private inline fun inTransaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun android.database.Cursor.doubleOrNull(index: Int): Double? =
        if (isNull(index)) null else getDouble(index)
}
