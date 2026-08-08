package com.rpgos.app

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

/**
 * Compatibility imports from the existing RPG OS campaign schema into GM141
 * working state. Each logical import has its own durable migration marker so a
 * later build can add baseline domains without silently skipping them on saves
 * that already ran an older bootstrap.
 *
 * Imports never delete or update legacy tables and never overwrite a field that
 * already exists in gm_entity_state.
 */
object GameMasterLegacyBootstrap141 {
    const val MIGRATION_ID = "GM-141-LEGACY-STATE-BOOTSTRAP-V1"
    const val CALENDAR_MIGRATION_ID = "GM-141-CALENDAR-BOOTSTRAP-V1"

    fun ensure(db: SQLiteDatabase, campaignUid: EntityUid) {
        CampaignSourceOfTruthSchema.ensure(db)

        val baselineApplied = wasApplied(db, MIGRATION_ID)
        val calendarApplied = wasApplied(db, CALENDAR_MIGRATION_ID)
        if (baselineApplied && calendarApplied) return

        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            if (!calendarApplied) {
                importCalendar(db, campaignUid)
                markApplied(
                    db,
                    CALENDAR_MIGRATION_ID,
                    "GM141 imported campaign calendar baseline"
                )
            }

            if (!baselineApplied) {
                val playerUid = resolvePlayerUid(db)
                if (playerUid != null) {
                    importCharacterStats(db, campaignUid, playerUid)
                    importStatusSnapshot(db, campaignUid, playerUid)
                    importPosition(db, campaignUid, playerUid)
                    importFinances(db, campaignUid, playerUid)
                    importSkills(db, campaignUid, playerUid)
                    importTechniques(db, campaignUid, playerUid)
                }

                markApplied(
                    db,
                    MIGRATION_ID,
                    if (playerUid == null)
                        "GM141 legacy baseline completed; player UID was not resolvable"
                    else "GM141 imported baseline state for player $playerUid"
                )
            }

            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    private fun wasApplied(db: SQLiteDatabase, migrationId: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM rpgos_schema_migrations WHERE migration_id=? LIMIT 1",
            arrayOf(migrationId)
        ).use { it.moveToFirst() }

    private fun markApplied(db: SQLiteDatabase, migrationId: String, notes: String) {
        db.execSQL(
            """
            INSERT OR IGNORE INTO rpgos_schema_migrations(migration_id,applied_at,notes)
            VALUES(?,?,?)
            """.trimIndent(),
            arrayOf(migrationId, System.currentTimeMillis(), notes)
        )
    }

    private fun resolvePlayerUid(db: SQLiteDatabase): String? {
        val candidates = listOf(
            "SELECT entity_uid FROM character_skills GROUP BY entity_uid ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_techniques GROUP BY entity_uid ORDER BY COUNT(*) DESC LIMIT 1",
            "SELECT entity_uid FROM character_finances LIMIT 1",
            "SELECT entity_uid FROM entity_positions ORDER BY updated_chapter DESC LIMIT 1"
        )
        for (sql in candidates) {
            val value = runCatching {
                db.rawQuery(sql, null).use { c ->
                    if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
                }
            }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun importCalendar(db: SQLiteDatabase, campaignUid: EntityUid) {
        runCatching {
            db.rawQuery("SELECT * FROM campaign_calendar WHERE id=1 LIMIT 1", null).use { c ->
                if (!c.moveToFirst()) return@use
                val aliases = mapOf(
                    "year_label" to "time.year_label",
                    "era_name" to "time.era",
                    "season" to "time.season",
                    "hour" to "time.hour",
                    "minute" to "time.minute",
                    "day" to "time.day",
                    "day_of_year" to "time.day_of_year",
                    "year" to "time.year"
                )
                c.columnNames.forEachIndexed { index, column ->
                    val field = aliases[column] ?: return@forEachIndexed
                    if (!c.isNull(index)) {
                        putState(
                            db = db,
                            campaignUid = campaignUid,
                            entityType = "CAMPAIGN",
                            entityUid = campaignUid.value,
                            field = field,
                            value = cursorValue(c, index)
                        )
                    }
                }
            }
        }
    }

    private fun importCharacterStats(db: SQLiteDatabase, campaignUid: EntityUid, playerUid: String) {
        safelyQuery(db, "SELECT stat_key,current_value FROM character_stats") { c ->
            val key = c.getString(0)?.trim().orEmpty()
            if (key.isNotEmpty() && !c.isNull(1)) {
                putState(db, campaignUid, "CHARACTER", playerUid, "stat.$key", cursorValue(c, 1))
            }
        }
    }

    private fun importStatusSnapshot(db: SQLiteDatabase, campaignUid: EntityUid, playerUid: String) {
        runCatching {
            db.rawQuery("SELECT * FROM character_status_snapshot LIMIT 1", null).use { c ->
                if (!c.moveToFirst()) return@use
                c.columnNames.forEachIndexed { index, name ->
                    if (!c.isNull(index) && name.isNotBlank()) {
                        putState(db, campaignUid, "CHARACTER", playerUid, "status.$name", cursorValue(c, index))
                    }
                }
            }
        }
    }

    private fun importPosition(db: SQLiteDatabase, campaignUid: EntityUid, playerUid: String) {
        safelyQuery(
            db,
            "SELECT location_uid,x_coord,y_coord,last_updated_day,updated_chapter FROM entity_positions WHERE entity_uid=? LIMIT 1",
            arrayOf(playerUid)
        ) { c ->
            putIfPresent(db, campaignUid, playerUid, "position.location_uid", c, 0)
            putIfPresent(db, campaignUid, playerUid, "position.x", c, 1)
            putIfPresent(db, campaignUid, playerUid, "position.y", c, 2)
            putIfPresent(db, campaignUid, playerUid, "position.last_updated_day", c, 3)
            putIfPresent(db, campaignUid, playerUid, "position.updated_chapter", c, 4)
        }
    }

    private fun importFinances(db: SQLiteDatabase, campaignUid: EntityUid, playerUid: String) {
        safelyQuery(
            db,
            """
            SELECT ryo,monthly_income,monthly_expenses,debt,property_value,investment_value,updated_chapter
            FROM character_finances WHERE entity_uid=? LIMIT 1
            """.trimIndent(),
            arrayOf(playerUid)
        ) { c ->
            val names = listOf("ryo", "monthly_income", "monthly_expenses", "debt", "property_value", "investment_value", "updated_chapter")
            names.forEachIndexed { index, name ->
                putIfPresent(db, campaignUid, playerUid, "finance.$name", c, index)
            }
        }
    }

    private fun importSkills(db: SQLiteDatabase, campaignUid: EntityUid, playerUid: String) {
        safelyQuery(
            db,
            "SELECT skill_uid,mastery,xp,updated_chapter FROM character_skills WHERE entity_uid=?",
            arrayOf(playerUid)
        ) { c ->
            val skillUid = c.getString(0)?.trim().orEmpty()
            if (skillUid.isNotEmpty()) {
                putIfPresent(db, campaignUid, playerUid, "skill.$skillUid.mastery", c, 1)
                putIfPresent(db, campaignUid, playerUid, "skill.$skillUid.xp", c, 2)
                putIfPresent(db, campaignUid, playerUid, "skill.$skillUid.updated_chapter", c, 3)
            }
        }
    }

    private fun importTechniques(db: SQLiteDatabase, campaignUid: EntityUid, playerUid: String) {
        safelyQuery(
            db,
            """
            SELECT technique_uid,mastery,xp,learned_chapter,last_used_chapter,usage_count,
                   success_count,failure_count,is_equipped
            FROM character_techniques WHERE entity_uid=?
            """.trimIndent(),
            arrayOf(playerUid)
        ) { c ->
            val techniqueUid = c.getString(0)?.trim().orEmpty()
            if (techniqueUid.isNotEmpty()) {
                val names = listOf(
                    "mastery", "xp", "learned_chapter", "last_used_chapter",
                    "usage_count", "success_count", "failure_count", "is_equipped"
                )
                names.forEachIndexed { offset, name ->
                    putIfPresent(db, campaignUid, playerUid, "technique.$techniqueUid.$name", c, offset + 1)
                }
            }
        }
    }

    private fun putIfPresent(
        db: SQLiteDatabase,
        campaignUid: EntityUid,
        playerUid: String,
        field: String,
        cursor: Cursor,
        index: Int
    ) {
        if (!cursor.isNull(index)) {
            putState(db, campaignUid, "CHARACTER", playerUid, field, cursorValue(cursor, index))
        }
    }

    private fun putState(
        db: SQLiteDatabase,
        campaignUid: EntityUid,
        entityType: String,
        entityUid: String,
        field: String,
        value: String
    ) {
        val now = System.currentTimeMillis()
        db.insertWithOnConflict(
            "gm_entity_state",
            null,
            ContentValues().apply {
                put("campaign_id", campaignUid.value)
                put("entity_type", entityType)
                put("entity_id", entityUid)
                put("field_key", field)
                put("value_json", value)
                put("valid_from_turn", 0L)
                put("updated_at", now)
                put("provenance_type", ProvenanceType.IMPORTED_CONTENT.name)
            },
            SQLiteDatabase.CONFLICT_IGNORE
        )
    }

    private fun cursorValue(c: Cursor, index: Int): String = when (c.getType(index)) {
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(index).toString()
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(index).toString()
        Cursor.FIELD_TYPE_BLOB -> "[BLOB ${c.getBlob(index).size} bytes]"
        Cursor.FIELD_TYPE_NULL -> "null"
        else -> c.getString(index)
    }

    private inline fun safelyQuery(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>? = null,
        row: (Cursor) -> Unit
    ) {
        runCatching {
            db.rawQuery(sql, args).use { c -> while (c.moveToNext()) row(c) }
        }
    }
}
