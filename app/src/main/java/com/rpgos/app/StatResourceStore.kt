package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Phase 4 dynamic stat/resource persistence and explicit legacy reconciliation. */
internal class StatResourceStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun statDefinitions(worldPackUid: String? = null): List<StatDefinition> {
        val persisted = loadPersistedStatDefinitions(worldPackUid)
        rejectPersistedReservedDefinitions(persisted.map { it.statUid to it.worldPackUid })
        val legacy = if (worldPackUid == null || worldPackUid == LegacyCompatibilityIdentity.WORLD_PACK_UID) {
            LegacyStatResourceCompatibility.statDefinitions(db)
        } else emptyList()
        val reconciled = reconcileStatDefinitions(persisted, legacy, worldPackUid == null)
        return reconciled.sortedWith(compareBy(StatDefinition::worldPackUid, StatDefinition::category, StatDefinition::key))
    }

    fun resourceDefinitions(worldPackUid: String? = null): List<ResourceDefinition> {
        val persisted = loadPersistedResourceDefinitions(worldPackUid)
        rejectPersistedReservedDefinitions(persisted.map { it.resourceUid to it.worldPackUid })
        val legacy = if (worldPackUid == null || worldPackUid == LegacyCompatibilityIdentity.WORLD_PACK_UID) {
            LegacyStatResourceCompatibility.resourceDefinitions(db)
        } else emptyList()
        val reconciled = reconcileResourceDefinitions(persisted, legacy, worldPackUid == null)
        return reconciled.sortedWith(compareBy(ResourceDefinition::worldPackUid, ResourceDefinition::category, ResourceDefinition::key))
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
                rejectKeyCollision("stat_definitions", "stat_key", "stat_uid", worldPackUid, definition.key, definition.statUid)
                if (existing == null) {
                    db.execSQL(
                        """INSERT INTO stat_definitions(
                            stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid
                        ) VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent(),
                        arrayOf<Any?>(definition.statUid, definition.key, definition.category, definition.unit,
                            definition.minValue, definition.maxValue, definition.growthRuleUid,
                            definition.derivationRuleUid, definition.worldPackUid)
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
                rejectKeyCollision("resource_definitions", "resource_key", "resource_uid", worldPackUid, definition.key, definition.resourceUid)
                if (existing == null) {
                    db.execSQL(
                        """INSERT INTO resource_definitions(
                            resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid
                        ) VALUES(?,?,?,?,?,?,?,?,?)""".trimIndent(),
                        arrayOf<Any?>(definition.resourceUid, definition.key, definition.category, definition.unit,
                            definition.minValue, definition.maxValue, definition.maxRuleUid,
                            definition.regenerationRuleUid, definition.worldPackUid)
                    )
                }
            }
        }
    }

    fun registerLegacyStatAlias(alias: LegacyStatAlias) {
        StatResourcePolicy.validate(alias)
        require(alias.campaignId == campaignId) { "LegacyStatAlias belongs to another campaign" }
        val canonical = existingStatDefinition(alias.canonicalStatUid)
            ?: error("Canonical stat definition does not exist: ${alias.canonicalStatUid}")
        require(canonical.worldPackUid == alias.worldPackUid) {
            "Canonical stat ${alias.canonicalStatUid} is owned by ${canonical.worldPackUid}, not ${alias.worldPackUid}"
        }
        val legacyDefinition = LegacyStatResourceCompatibility.statDefinitions(db)
            .singleOrNull { it.statUid == alias.legacyStatUid }
            ?: error("Legacy stat definition is not present: ${alias.legacyStatUid}")
        validateLegacyStatValuesAgainst(canonical, legacyDefinition)
        inTransaction {
            val existing = existingStatAlias(alias.legacyStatUid)
            require(existing == null || existing == alias) {
                "Legacy stat alias ${alias.legacyStatUid} already exists with incompatible mapping metadata"
            }
            if (existing == null) {
                db.execSQL(
                    "INSERT INTO legacy_stat_aliases(campaign_id,legacy_stat_uid,canonical_stat_uid,world_pack_uid,mapping_version,provenance) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(alias.campaignId, alias.legacyStatUid, alias.canonicalStatUid,
                        alias.worldPackUid, alias.mappingVersion, alias.provenance)
                )
            }
        }
    }

    fun registerLegacyResourceAlias(alias: LegacyResourceAlias) {
        StatResourcePolicy.validate(alias)
        require(alias.campaignId == campaignId) { "LegacyResourceAlias belongs to another campaign" }
        val canonical = existingResourceDefinition(alias.canonicalResourceUid)
            ?: error("Canonical resource definition does not exist: ${alias.canonicalResourceUid}")
        require(canonical.worldPackUid == alias.worldPackUid) {
            "Canonical resource ${alias.canonicalResourceUid} is owned by ${canonical.worldPackUid}, not ${alias.worldPackUid}"
        }
        val legacyDefinition = LegacyStatResourceCompatibility.resourceDefinitions(db)
            .singleOrNull { it.resourceUid == alias.legacyResourceUid }
            ?: error("Legacy resource definition is not present: ${alias.legacyResourceUid}")
        validateLegacyResourceValuesAgainst(canonical, legacyDefinition)
        inTransaction {
            val existing = existingResourceAlias(alias.legacyResourceUid)
            require(existing == null || existing == alias) {
                "Legacy resource alias ${alias.legacyResourceUid} already exists with incompatible mapping metadata"
            }
            if (existing == null) {
                db.execSQL(
                    "INSERT INTO legacy_resource_aliases(campaign_id,legacy_resource_uid,canonical_resource_uid,world_pack_uid,mapping_version,provenance) VALUES(?,?,?,?,?,?)",
                    arrayOf<Any?>(alias.campaignId, alias.legacyResourceUid, alias.canonicalResourceUid,
                        alias.worldPackUid, alias.mappingVersion, alias.provenance)
                )
            }
        }
    }

    fun playerStats(characterUid: String): List<PlayerStat> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val persisted = mutableListOf<PlayerStat>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,stat_uid,base_value,version FROM player_stats WHERE campaign_id=? AND character_uid=? ORDER BY stat_uid",
            arrayOf(campaignId, characterUid)
        ).use { c -> while (c.moveToNext()) persisted += PlayerStat(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4)) }
        rejectPersistedReservedValueUids(persisted.map { it.statUid })

        val persistedDefinitions = loadPersistedStatDefinitions(null)
        val legacyDefinitions = LegacyStatResourceCompatibility.statDefinitions(db)
        reconcileStatDefinitions(persistedDefinitions, legacyDefinitions, true)
        val legacy = LegacyStatResourceCompatibility.playerStats(db, campaignId, characterUid)
        val aliases = statAliases().associateBy { it.legacyStatUid }
        val out = linkedMapOf<String, PlayerStat>()
        persisted.forEach { require(out.put(it.statUid, it) == null) { "Duplicate persisted player value UID ${it.statUid}" } }
        legacy.forEach { value ->
            val alias = aliases[value.statUid]
            val projected = if (alias == null) value else value.copy(statUid = alias.canonicalStatUid)
            if (alias == null) {
                require(out.putIfAbsent(projected.statUid, projected) == null) {
                    "Legacy compatibility value UID collides with persisted player value ${projected.statUid}"
                }
            } else if (!out.containsKey(projected.statUid)) {
                out[projected.statUid] = projected
            }
        }
        return out.values.sortedBy { it.statUid }
    }

    fun playerResources(characterUid: String): List<PlayerResource> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val persisted = mutableListOf<PlayerResource>()
        db.rawQuery(
            "SELECT campaign_id,character_uid,resource_uid,current_value,version FROM player_resources WHERE campaign_id=? AND character_uid=? ORDER BY resource_uid",
            arrayOf(campaignId, characterUid)
        ).use { c -> while (c.moveToNext()) persisted += PlayerResource(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getLong(4)) }
        rejectPersistedReservedValueUids(persisted.map { it.resourceUid })

        val persistedDefinitions = loadPersistedResourceDefinitions(null)
        val legacyDefinitions = LegacyStatResourceCompatibility.resourceDefinitions(db)
        reconcileResourceDefinitions(persistedDefinitions, legacyDefinitions, true)
        val legacy = LegacyStatResourceCompatibility.playerResources(db, campaignId, characterUid)
        val aliases = resourceAliases().associateBy { it.legacyResourceUid }
        val out = linkedMapOf<String, PlayerResource>()
        persisted.forEach { require(out.put(it.resourceUid, it) == null) { "Duplicate persisted player value UID ${it.resourceUid}" } }
        legacy.forEach { value ->
            val alias = aliases[value.resourceUid]
            val projected = if (alias == null) value else value.copy(resourceUid = alias.canonicalResourceUid)
            if (alias == null) {
                require(out.putIfAbsent(projected.resourceUid, projected) == null) {
                    "Legacy compatibility value UID collides with persisted player value ${projected.resourceUid}"
                }
            } else if (!out.containsKey(projected.resourceUid)) {
                out[projected.resourceUid] = projected
            }
        }
        return out.values.sortedBy { it.resourceUid }
    }

    internal fun savePlayerStat(stat: PlayerStat) {
        StatResourcePolicy.validate(stat)
        require(stat.campaignId == campaignId) { "PlayerStat belongs to another campaign" }
        requireDefinitionUidAvailable(stat.statUid)
        requireValueWithinDefinition("stat_definitions", "stat_uid", stat.statUid, stat.baseValue)
        db.execSQL(
            """INSERT INTO player_stats(campaign_id,character_uid,stat_uid,base_value,version) VALUES(?,?,?,?,?)
               ON CONFLICT(campaign_id,character_uid,stat_uid) DO UPDATE SET base_value=excluded.base_value,version=excluded.version""".trimIndent(),
            arrayOf<Any?>(stat.campaignId, stat.characterUid, stat.statUid, stat.baseValue, stat.version)
        )
    }

    internal fun savePlayerResource(resource: PlayerResource) {
        StatResourcePolicy.validate(resource)
        require(resource.campaignId == campaignId) { "PlayerResource belongs to another campaign" }
        requireDefinitionUidAvailable(resource.resourceUid)
        requireValueWithinDefinition("resource_definitions", "resource_uid", resource.resourceUid, resource.currentValue)
        db.execSQL(
            """INSERT INTO player_resources(campaign_id,character_uid,resource_uid,current_value,version) VALUES(?,?,?,?,?)
               ON CONFLICT(campaign_id,character_uid,resource_uid) DO UPDATE SET current_value=excluded.current_value,version=excluded.version""".trimIndent(),
            arrayOf<Any?>(resource.campaignId, resource.characterUid, resource.resourceUid, resource.currentValue, resource.version)
        )
    }

    private fun reconcileStatDefinitions(
        persisted: List<StatDefinition>, legacy: List<StatDefinition>, enforceAmbiguity: Boolean
    ): List<StatDefinition> {
        val aliases = statAliases().associateBy { it.legacyStatUid }
        validateStatAliasTargets(aliases.values.toList(), loadPersistedStatDefinitions(null))
        val mappedLegacy = aliases.keys
        val unmappedLegacy = legacy.filterNot { it.statUid in mappedLegacy }
        if (enforceAmbiguity) {
            unmappedLegacy.forEach { legacyDef ->
                val candidates = persisted.filter { it.key == legacyDef.key }
                check(candidates.isEmpty()) {
                    "Unresolved semantic ambiguity for legacy stat '${legacyDef.key}' (${legacyDef.statUid}); explicit legacy alias required before canonical typed read"
                }
            }
        }
        return mergeDefinitionsByUid(persisted, unmappedLegacy) { it.statUid }
    }

    private fun reconcileResourceDefinitions(
        persisted: List<ResourceDefinition>, legacy: List<ResourceDefinition>, enforceAmbiguity: Boolean
    ): List<ResourceDefinition> {
        val aliases = resourceAliases().associateBy { it.legacyResourceUid }
        validateResourceAliasTargets(aliases.values.toList(), loadPersistedResourceDefinitions(null))
        val mappedLegacy = aliases.keys
        val unmappedLegacy = legacy.filterNot { it.resourceUid in mappedLegacy }
        if (enforceAmbiguity) {
            unmappedLegacy.forEach { legacyDef ->
                val candidates = persisted.filter { it.key == legacyDef.key }
                check(candidates.isEmpty()) {
                    "Unresolved semantic ambiguity for legacy resource '${legacyDef.key}' (${legacyDef.resourceUid}); explicit legacy alias required before canonical typed read"
                }
            }
        }
        return mergeDefinitionsByUid(persisted, unmappedLegacy) { it.resourceUid }
    }

    private fun statAliases(): List<LegacyStatAlias> {
        val out = mutableListOf<LegacyStatAlias>()
        db.rawQuery(
            "SELECT campaign_id,legacy_stat_uid,canonical_stat_uid,world_pack_uid,mapping_version,provenance FROM legacy_stat_aliases WHERE campaign_id=? ORDER BY legacy_stat_uid",
            arrayOf(campaignId)
        ).use { c -> while (c.moveToNext()) out += LegacyStatAlias(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5)) }
        return out
    }

    private fun resourceAliases(): List<LegacyResourceAlias> {
        val out = mutableListOf<LegacyResourceAlias>()
        db.rawQuery(
            "SELECT campaign_id,legacy_resource_uid,canonical_resource_uid,world_pack_uid,mapping_version,provenance FROM legacy_resource_aliases WHERE campaign_id=? ORDER BY legacy_resource_uid",
            arrayOf(campaignId)
        ).use { c -> while (c.moveToNext()) out += LegacyResourceAlias(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5)) }
        return out
    }

    private fun existingStatAlias(legacyUid: String): LegacyStatAlias? = statAliases().singleOrNull { it.legacyStatUid == legacyUid }
    private fun existingResourceAlias(legacyUid: String): LegacyResourceAlias? = resourceAliases().singleOrNull { it.legacyResourceUid == legacyUid }

    private fun validateStatAliasTargets(aliases: List<LegacyStatAlias>, definitions: List<StatDefinition>) {
        val byUid = definitions.associateBy { it.statUid }
        aliases.forEach { alias ->
            val target = byUid[alias.canonicalStatUid] ?: error("Legacy stat alias target disappeared: ${alias.canonicalStatUid}")
            check(target.worldPackUid == alias.worldPackUid) { "Legacy stat alias owner mismatch for ${alias.legacyStatUid}" }
        }
    }

    private fun validateResourceAliasTargets(aliases: List<LegacyResourceAlias>, definitions: List<ResourceDefinition>) {
        val byUid = definitions.associateBy { it.resourceUid }
        aliases.forEach { alias ->
            val target = byUid[alias.canonicalResourceUid] ?: error("Legacy resource alias target disappeared: ${alias.canonicalResourceUid}")
            check(target.worldPackUid == alias.worldPackUid) { "Legacy resource alias owner mismatch for ${alias.legacyResourceUid}" }
        }
    }

    private fun validateLegacyStatValuesAgainst(canonical: StatDefinition, legacy: StatDefinition) {
        if (!tableExists("character_stats")) return
        db.rawQuery("SELECT DISTINCT entity_uid FROM character_stats WHERE entity_uid IS NOT NULL ORDER BY entity_uid", null).use { c ->
            while (c.moveToNext()) {
                val uid = c.getString(0)
                LegacyStatResourceCompatibility.playerStats(db, campaignId, uid)
                    .filter { it.statUid == legacy.statUid }
                    .forEach { requireWithinBounds(canonical.minValue, canonical.maxValue, it.baseValue, canonical.statUid) }
            }
        }
    }

    private fun validateLegacyResourceValuesAgainst(canonical: ResourceDefinition, legacy: ResourceDefinition) {
        if (!tableExists("character_status_snapshot")) return
        if (hasColumn("character_status_snapshot", "entity_uid")) {
            db.rawQuery("SELECT DISTINCT entity_uid FROM character_status_snapshot WHERE entity_uid IS NOT NULL ORDER BY entity_uid", null).use { c ->
                while (c.moveToNext()) {
                    val uid = c.getString(0)
                    LegacyStatResourceCompatibility.playerResources(db, campaignId, uid)
                        .filter { it.resourceUid == legacy.resourceUid }
                        .forEach { requireWithinBounds(canonical.minValue, canonical.maxValue, it.currentValue, canonical.resourceUid) }
                }
            }
        } else {
            ActivePlayerStore(db, campaignId).active()?.playerUid?.let { uid ->
                LegacyStatResourceCompatibility.playerResources(db, campaignId, uid)
                    .filter { it.resourceUid == legacy.resourceUid }
                    .forEach { requireWithinBounds(canonical.minValue, canonical.maxValue, it.currentValue, canonical.resourceUid) }
            }
        }
    }

    private fun requireWithinBounds(min: Double?, max: Double?, value: Double, uid: String) {
        min?.let { require(value >= it) { "Legacy value $value is below canonical minimum $it for $uid" } }
        max?.let { require(value <= it) { "Legacy value $value exceeds canonical maximum $it for $uid" } }
    }

    private fun loadPersistedStatDefinitions(worldPackUid: String?): List<StatDefinition> {
        val args = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val out = mutableListOf<StatDefinition>()
        db.rawQuery("SELECT stat_uid,stat_key,category,unit,min_value,max_value,growth_rule_uid,derivation_rule_uid,world_pack_uid FROM stat_definitions$where ORDER BY world_pack_uid,category,stat_key", args).use { c ->
            while (c.moveToNext()) out += StatDefinition(c.getString(0), c.getString(1), c.getString(2), c.stringOrNull(3), c.doubleOrNull(4), c.doubleOrNull(5), c.stringOrNull(6), c.stringOrNull(7), c.getString(8))
        }
        return out
    }

    private fun loadPersistedResourceDefinitions(worldPackUid: String?): List<ResourceDefinition> {
        val args = worldPackUid?.let { arrayOf(it) }
        val where = if (worldPackUid == null) "" else " WHERE world_pack_uid=?"
        val out = mutableListOf<ResourceDefinition>()
        db.rawQuery("SELECT resource_uid,resource_key,category,unit,min_value,max_value,max_rule_uid,regeneration_rule_uid,world_pack_uid FROM resource_definitions$where ORDER BY world_pack_uid,category,resource_key", args).use { c ->
            while (c.moveToNext()) out += ResourceDefinition(c.getString(0), c.getString(1), c.getString(2), c.stringOrNull(3), c.doubleOrNull(4), c.doubleOrNull(5), c.stringOrNull(6), c.stringOrNull(7), c.getString(8))
        }
        return out
    }

    private fun existingStatDefinition(uid: String): StatDefinition? = loadPersistedStatDefinitions(null).singleOrNull { it.statUid == uid }
    private fun existingResourceDefinition(uid: String): ResourceDefinition? = loadPersistedResourceDefinitions(null).singleOrNull { it.resourceUid == uid }

    private fun rejectKeyCollision(table: String, keyColumn: String, uidColumn: String, worldPackUid: String, key: String, uid: String) {
        val existingUid = db.rawQuery("SELECT $uidColumn FROM $table WHERE world_pack_uid=? AND $keyColumn=? LIMIT 1", arrayOf(worldPackUid, key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        require(existingUid == null || existingUid == uid) { "Definition key $key in World Pack $worldPackUid is already owned by UID $existingUid" }
    }

    private fun requireWorldPackNamespaceAvailable(worldPackUid: String) {
        require(worldPackUid.isNotBlank()) { "worldPackUid must not be blank" }
        require(!LegacyCompatibilityIdentity.isReservedWorldPack(worldPackUid)) { "World Pack UID $worldPackUid is reserved for legacy compatibility" }
    }

    private fun requireDefinitionUidAvailable(uid: String) {
        require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(uid)) { "Definition UID $uid is reserved for legacy compatibility" }
    }

    private fun rejectPersistedReservedDefinitions(definitions: List<Pair<String, String>>) {
        definitions.forEach { (uid, worldPackUid) ->
            require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(uid) && !LegacyCompatibilityIdentity.isReservedWorldPack(worldPackUid)) {
                "Persisted definition uses the reserved legacy compatibility namespace: $uid / $worldPackUid"
            }
        }
    }

    private fun rejectPersistedReservedValueUids(uids: List<String>) {
        uids.forEach { uid -> require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(uid)) { "Persisted player value uses the reserved legacy compatibility UID $uid" } }
    }

    private fun requireValueWithinDefinition(table: String, uidColumn: String, uid: String, value: Double) {
        val bounds = db.rawQuery("SELECT min_value,max_value FROM $table WHERE $uidColumn=? LIMIT 1", arrayOf(uid)).use { c ->
            require(c.moveToFirst()) { "Unknown definition UID: $uid" }
            c.doubleOrNull(0) to c.doubleOrNull(1)
        }
        requireWithinBounds(bounds.first, bounds.second, value, uid)
    }

    private inline fun <T> mergeDefinitionsByUid(persisted: List<T>, compatibility: List<T>, uid: (T) -> String): List<T> {
        val out = linkedMapOf<String, T>()
        persisted.forEach { value -> require(out.put(uid(value), value) == null) { "Duplicate persisted definition UID ${uid(value)}" } }
        compatibility.forEach { value -> require(out.putIfAbsent(uid(value), value) == null) { "Legacy compatibility definition UID collides with persisted definition ${uid(value)}" } }
        return out.values.toList()
    }

    private fun tableExists(table: String): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", arrayOf(table)).use { it.moveToFirst() }
    private fun hasColumn(table: String, column: String): Boolean = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
        val i = c.getColumnIndex("name")
        while (c.moveToNext()) if (i >= 0 && c.getString(i).equals(column, true)) return@use true
        false
    }

    private inline fun inTransaction(block: () -> Unit) {
        db.beginTransaction()
        try { block(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }

    private fun android.database.Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun android.database.Cursor.doubleOrNull(index: Int): Double? = if (isNull(index)) null else getDouble(index)
}
