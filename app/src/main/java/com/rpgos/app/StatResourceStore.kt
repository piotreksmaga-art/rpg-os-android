package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Phase 4 persistence adapter. Kept internal so player-value mutation does not
 * become a public bypass around the future PlayerDomainEngine/transaction path.
 *
 * Pre-Phase-4 campaign values are exposed through a lossless read-through
 * projection. They remain authoritative in legacy storage until a later,
 * separately audited migration can retire that compatibility source.
 */
internal class StatResourceStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun statDefinitions(worldPackUid: String? = null): List<StatDefinition> {
        val args: Array<String>? = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val persisted = mutableListOf<StatDefinition>()
        db.rawQuery(
            "SELECT stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid " +
                "FROM stat_definitions$where ORDER BY world_pack_uid,category,stat_key",
            args
        ).use { c ->
            while (c.moveToNext()) {
                persisted += StatDefinition(
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
        rejectPersistedReservedDefinitions(persisted.map { it.statUid to it.worldPackUid })

        val legacy = if (
            worldPackUid == null || worldPackUid == LegacyStatResourceCompatibility.WORLD_PACK_UID
        ) {
            LegacyStatResourceCompatibility.statDefinitions(db)
        } else {
            emptyList()
        }
        return mergeDefinitionsByUid(persisted, legacy) { it.statUid }
            .sortedWith(compareBy(StatDefinition::worldPackUid, StatDefinition::category, StatDefinition::key))
    }

    fun resourceDefinitions(worldPackUid: String? = null): List<ResourceDefinition> {
        val args: Array<String>? = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val persisted = mutableListOf<ResourceDefinition>()
        db.rawQuery(
            "SELECT resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid " +
                "FROM resource_definitions$where ORDER BY world_pack_uid,category,resource_key",
            args
        ).use { c ->
            while (c.moveToNext()) {
                persisted += ResourceDefinition(
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
        rejectPersistedReservedDefinitions(persisted.map { it.resourceUid to it.worldPackUid })

        val legacy = if (
            worldPackUid == null || worldPackUid == LegacyStatResourceCompatibility.WORLD_PACK_UID
        ) {
            LegacyStatResourceCompatibility.resourceDefinitions(db)
        } else {
            emptyList()
        }
        return mergeDefinitionsByUid(persisted, legacy) { it.resourceUid }
            .sortedWith(compareBy(ResourceDefinition::worldPackUid, ResourceDefinition::category, ResourceDefinition::key))
    }

    fun registerStatDefinitions(worldPackUid: String, definitions: List<StatDefinition>) {
        requireWorldPackNamespaceAvailable(worldPackUid)
        definitions.forEach {
            StatResourcePolicy.validate(it)
            require(it.worldPackUid == worldPackUid) { "StatDefinition belongs to another World Pack: ${it.statUid}" }
            requireDefinitionUidAvailable(it.statUid)
        }
        inTransaction {
            definitions.forEach { definition ->
                val existing = existingStatDefinition(definition.statUid)
                require(existing == null || existing == definition) {
                    "Definition UID ${definition.statUid} already exists with incompatible metadata"
                }
                rejectKeyCollision(
                    table = "stat_definitions",
                    keyColumn = "stat_key",
                    uidColumn = "stat_uid",
                    worldPackUid = worldPackUid,
                    key = definition.key,
                    uid = definition.statUid
                )
                if (existing == null) {
                    db.execSQL(
                        """
                        INSERT INTO stat_definitions(
                            stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid
                        ) VALUES(?,?,?,?,?,?,?,?,?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            definition.statUid, definition.key, definition.category, definition.unit,
                            definition.minValue, definition.maxValue, definition.growthRuleUid,
                            definition.derivationRuleUid, definition.worldPackUid
                        )
                    )
                }
            }
        }
    }

    fun registerResourceDefinitions(worldPackUid: String, definitions: List<ResourceDefinition>) {
        requireWorldPackNamespaceAvailable(worldPackUid)
        definitions.forEach {
            StatResourcePolicy.validate(it)
            require(it.worldPackUid == worldPackUid) { "ResourceDefinition belongs to another World Pack: ${it.resourceUid}" }
            requireDefinitionUidAvailable(it.resourceUid)
        }
        inTransaction {
            definitions.forEach { definition ->
                val existing = existingResourceDefinition(definition.resourceUid)
                require(existing == null || existing == definition) {
                    "Definition UID ${definition.resourceUid} already exists with incompatible metadata"
                }
                rejectKeyCollision(
                    table = "resource_definitions",
                    keyColumn = "resource_key",
                    uidColumn = "resource_uid",
                    worldPackUid = worldPackUid,
                    key = definition.key,
                    uid = definition.resourceUid
                )
                if (existing == null) {
                    db.execSQL(
                        """
                        INSERT INTO resource_definitions(
                            resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid
                        ) VALUES(?,?,?,?,?,?,?,?,?)
                        """.trimIndent(),
                        arrayOf<Any?>(
                            definition.resourceUid, definition.key, definition.category, definition.unit,
                            definition.minValue, definition.maxValue, definition.maxRuleUid,
                            definition.regenerationRuleUid, definition.worldPackUid
                        )
                    )
                }
            }
        }
    }

    fun playerStats(characterUid: String): List<PlayerStat> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val persisted = mutableListOf<PlayerStat>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,stat_uid,base_value,version FROM player_stats " +
                "WHERE campaign_id=? AND character_uid=? ORDER BY stat_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                persisted += PlayerStat(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4))
            }
        }
        rejectPersistedReservedValueUids(persisted.map { it.statUid })
        val legacy = LegacyStatResourceCompatibility.playerStats(db, campaignId, characterUid)
        return mergeValuesByUid(persisted, legacy) { it.statUid }.sortedBy { it.statUid }
    }

    fun playerResources(characterUid: String): List<PlayerResource> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val persisted = mutableListOf<PlayerResource>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,resource_uid,current_value,version FROM player_resources " +
                "WHERE campaign_id=? AND character_uid=? ORDER BY resource_uid",
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                persisted += PlayerResource(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4))
            }
        }
        rejectPersistedReservedValueUids(persisted.map { it.resourceUid })
        val legacy = LegacyStatResourceCompatibility.playerResources(db, campaignId, characterUid)
        return mergeValuesByUid(persisted, legacy) { it.resourceUid }.sortedBy { it.resourceUid }
    }

    internal fun savePlayerStat(stat: PlayerStat) {
        StatResourcePolicy.validate(stat)
        require(stat.campaignId == campaignId) { "PlayerStat belongs to another campaign" }
        requireDefinitionUidAvailable(stat.statUid)
        requireValueWithinDefinition("stat_definitions", "stat_uid", stat.statUid, stat.baseValue)
        db.execSQL(
            """
            INSERT INTO player_stats(campaign_id,character_uid,stat_uid,base_value,version)
            VALUES(?,?,?,?,?)
            ON CONFLICT(campaign_id,character_uid,stat_uid) DO UPDATE SET
                base_value=excluded.base_value,
                version=excluded.version
            """.trimIndent(),
            arrayOf<Any?>(stat.campaignId, stat.characterUid, stat.statUid, stat.baseValue, stat.version)
        )
    }

    internal fun savePlayerResource(resource: PlayerResource) {
        StatResourcePolicy.validate(resource)
        require(resource.campaignId == campaignId) { "PlayerResource belongs to another campaign" }
        requireDefinitionUidAvailable(resource.resourceUid)
        requireValueWithinDefinition("resource_definitions", "resource_uid", resource.resourceUid, resource.currentValue)
        db.execSQL(
            """
            INSERT INTO player_resources(campaign_id,character_uid,resource_uid,current_value,version)
            VALUES(?,?,?,?,?)
            ON CONFLICT(campaign_id,character_uid,resource_uid) DO UPDATE SET
                current_value=excluded.current_value,
                version=excluded.version
            """.trimIndent(),
            arrayOf<Any?>(
                resource.campaignId, resource.characterUid, resource.resourceUid,
                resource.currentValue, resource.version
            )
        )
    }

    private fun existingStatDefinition(uid: String): StatDefinition? = db.rawQuery(
        "SELECT stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid " +
            "FROM stat_definitions WHERE stat_uid=?",
        arrayOf(uid)
    ).use { c ->
        if (!c.moveToFirst()) null else StatDefinition(
            statUid = c.getString(0), key = c.getString(1), category = c.getString(2), unit = c.stringOrNull(3),
            minValue = c.doubleOrNull(4), maxValue = c.doubleOrNull(5), growthRuleUid = c.stringOrNull(6),
            derivationRuleUid = c.stringOrNull(7), worldPackUid = c.getString(8)
        )
    }

    private fun existingResourceDefinition(uid: String): ResourceDefinition? = db.rawQuery(
        "SELECT resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid " +
            "FROM resource_definitions WHERE resource_uid=?",
        arrayOf(uid)
    ).use { c ->
        if (!c.moveToFirst()) null else ResourceDefinition(
            resourceUid = c.getString(0), key = c.getString(1), category = c.getString(2), unit = c.stringOrNull(3),
            minValue = c.doubleOrNull(4), maxValue = c.doubleOrNull(5), maxRuleUid = c.stringOrNull(6),
            regenerationRuleUid = c.stringOrNull(7), worldPackUid = c.getString(8)
        )
    }

    private fun rejectKeyCollision(
        table: String,
        keyColumn: String,
        uidColumn: String,
        worldPackUid: String,
        key: String,
        uid: String
    ) {
        val existingUid = db.rawQuery(
            "SELECT $uidColumn FROM $table WHERE world_pack_uid=? AND $keyColumn=? LIMIT 1",
            arrayOf(worldPackUid, key)
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(existingUid == null || existingUid == uid) {
            "Definition key $key in World Pack $worldPackUid is already owned by UID $existingUid"
        }
    }

    private fun requireWorldPackNamespaceAvailable(worldPackUid: String) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(!LegacyStatResourceCompatibility.isReservedWorldPack(worldPackUid)) {
            "World Pack UID $worldPackUid is reserved for legacy compatibility"
        }
    }

    private fun requireDefinitionUidAvailable(uid: String) {
        require(!LegacyStatResourceCompatibility.isReservedDefinitionUid(uid)) {
            "Definition UID $uid is reserved for legacy compatibility"
        }
    }

    private fun rejectPersistedReservedDefinitions(definitions: List<Pair<String, String>>) {
        definitions.forEach { (uid, worldPackUid) ->
            require(!LegacyStatResourceCompatibility.isReservedDefinitionUid(uid) &&
                !LegacyStatResourceCompatibility.isReservedWorldPack(worldPackUid)
            ) {
                "Persisted definition uses the reserved legacy compatibility namespace: $uid / $worldPackUid"
            }
        }
    }

    private fun rejectPersistedReservedValueUids(uids: List<String>) {
        uids.forEach { uid ->
            require(!LegacyStatResourceCompatibility.isReservedDefinitionUid(uid)) {
                "Persisted player value uses the reserved legacy compatibility UID $uid"
            }
        }
    }

    private fun requireValueWithinDefinition(table: String, uidColumn: String, uid: String, value: Double) {
        val bounds = db.rawQuery(
            "SELECT min_value,max_value FROM $table WHERE $uidColumn=? LIMIT 1",
            arrayOf(uid)
        ).use { c ->
            require(c.moveToFirst()) { "Unknown definition UID: $uid" }
            c.doubleOrNull(0) to c.doubleOrNull(1)
        }
        bounds.first?.let { require(value >= it) { "Value $value is below definition minimum $it for $uid" } }
        bounds.second?.let { require(value <= it) { "Value $value exceeds definition maximum $it for $uid" } }
    }

    private inline fun <T> mergeDefinitionsByUid(
        persisted: List<T>,
        compatibility: List<T>,
        uid: (T) -> String
    ): List<T> {
        val out = linkedMapOf<String, T>()
        persisted.forEach { value ->
            require(out.put(uid(value), value) == null) { "Duplicate persisted definition UID ${uid(value)}" }
        }
        compatibility.forEach { value ->
            require(out.putIfAbsent(uid(value), value) == null) {
                "Legacy compatibility definition UID collides with persisted definition ${uid(value)}"
            }
        }
        return out.values.toList()
    }

    private inline fun <T> mergeValuesByUid(
        persisted: List<T>,
        compatibility: List<T>,
        uid: (T) -> String
    ): List<T> {
        val out = linkedMapOf<String, T>()
        persisted.forEach { value ->
            require(out.put(uid(value), value) == null) { "Duplicate persisted player value UID ${uid(value)}" }
        }
        compatibility.forEach { value ->
            require(out.putIfAbsent(uid(value), value) == null) {
                "Legacy compatibility value UID collides with persisted player value ${uid(value)}"
            }
        }
        return out.values.toList()
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
