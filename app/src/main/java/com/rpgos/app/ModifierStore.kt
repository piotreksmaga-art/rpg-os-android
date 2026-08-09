package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** Phase 5 persistence for authoritative modifier inputs only. Resolved values are never stored here. */
internal class ModifierStore(
    private val db: SQLiteDatabase,
    private val campaignId: String
) {
    init { require(campaignId.isNotBlank()) { "campaignId must not be blank" } }

    fun modifiers(characterUid: String): List<Modifier> {
        require(characterUid.isNotBlank()) { "characterUid must not be blank" }
        val out = mutableListOf<Modifier>()
        db.rawQuery(
            """
            SELECT modifier_uid,campaign_id,character_uid,target_definition_uid,target_kind,lifecycle,operation,
                   modifier_value,priority,source_type,source_uid,source_active,valid_from,valid_until,active,provenance,version
            FROM modifiers
            WHERE campaign_id=? AND character_uid=?
            ORDER BY lifecycle,operation,priority,modifier_uid
            """.trimIndent(),
            arrayOf(campaignId, characterUid)
        ).use { c ->
            while (c.moveToNext()) {
                out += Modifier(
                    modifierUid = c.getString(0), campaignId = c.getString(1), characterUid = c.getString(2),
                    targetDefinitionUid = c.getString(3), targetKind = ModifierTargetKind.valueOf(c.getString(4)),
                    lifecycle = ModifierLifecycle.valueOf(c.getString(5)), operation = ModifierOperation.valueOf(c.getString(6)),
                    value = c.getDouble(7), priority = c.getInt(8), sourceType = c.getString(9), sourceUid = c.getString(10),
                    sourceActive = c.getInt(11) != 0,
                    validFrom = if (c.isNull(12)) null else c.getLong(12),
                    validUntil = if (c.isNull(13)) null else c.getLong(13),
                    active = c.getInt(14) != 0, provenance = c.getString(15), version = c.getLong(16)
                )
            }
        }
        return out
    }

    internal fun save(modifier: Modifier) {
        ModifierPolicy.validate(modifier)
        require(modifier.campaignId == campaignId) { "Modifier belongs to another campaign" }
        requireTargetExists(modifier)
        require(!exists(modifier.modifierUid)) { "Duplicate modifier UID: ${modifier.modifierUid}" }
        db.execSQL(
            """
            INSERT INTO modifiers(
                modifier_uid,campaign_id,character_uid,target_definition_uid,target_kind,lifecycle,operation,
                modifier_value,priority,source_type,source_uid,source_active,valid_from,valid_until,active,provenance,version
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """.trimIndent(),
            arrayOf<Any?>(
                modifier.modifierUid, modifier.campaignId, modifier.characterUid, modifier.targetDefinitionUid,
                modifier.targetKind.name, modifier.lifecycle.name, modifier.operation.name, modifier.value,
                modifier.priority, modifier.sourceType, modifier.sourceUid, if (modifier.sourceActive) 1 else 0,
                modifier.validFrom, modifier.validUntil, if (modifier.active) 1 else 0, modifier.provenance, modifier.version
            )
        )
    }

    internal fun setActive(characterUid: String, modifierUid: String, active: Boolean) {
        val changed = db.update(
            "modifiers",
            android.content.ContentValues().apply { put("active", if (active) 1 else 0) },
            "campaign_id=? AND character_uid=? AND modifier_uid=?",
            arrayOf(campaignId, characterUid, modifierUid)
        )
        require(changed == 1) { "Modifier not found: $modifierUid" }
    }

    internal fun setSourceActive(characterUid: String, sourceType: String, sourceUid: String, active: Boolean): Int {
        require(characterUid.isNotBlank() && sourceType.isNotBlank() && sourceUid.isNotBlank()) { "source identity must not be blank" }
        return db.update(
            "modifiers",
            android.content.ContentValues().apply { put("source_active", if (active) 1 else 0) },
            "campaign_id=? AND character_uid=? AND source_type=? AND source_uid=?",
            arrayOf(campaignId, characterUid, sourceType, sourceUid)
        )
    }

    internal fun remove(characterUid: String, modifierUid: String): Boolean =
        db.delete("modifiers", "campaign_id=? AND character_uid=? AND modifier_uid=?", arrayOf(campaignId, characterUid, modifierUid)) == 1

    private fun exists(modifierUid: String): Boolean = db.rawQuery(
        "SELECT 1 FROM modifiers WHERE campaign_id=? AND modifier_uid=? LIMIT 1", arrayOf(campaignId, modifierUid)
    ).use { it.moveToFirst() }

    private fun requireTargetExists(modifier: Modifier) {
        val (table, column) = when (modifier.targetKind) {
            ModifierTargetKind.STAT_EFFECTIVE -> "stat_definitions" to "stat_uid"
            ModifierTargetKind.RESOURCE_MAXIMUM, ModifierTargetKind.RESOURCE_REGENERATION -> "resource_definitions" to "resource_uid"
        }
        val exists = db.rawQuery("SELECT 1 FROM $table WHERE $column=? LIMIT 1", arrayOf(modifier.targetDefinitionUid)).use { it.moveToFirst() }
        require(exists) { "Modifier ${modifier.modifierUid} targets missing ${modifier.targetKind} definition ${modifier.targetDefinitionUid}" }
        require(!LegacyCompatibilityIdentity.isReservedDefinitionUid(modifier.targetDefinitionUid)) {
            "Modifiers must target canonical typed identities after Phase 4 reconciliation"
        }
    }
}
