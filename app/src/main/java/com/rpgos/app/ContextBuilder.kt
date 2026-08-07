package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class ContextBuilder(
    private val saveDb: SQLiteDatabase,
    private val worldDb: SQLiteDatabase
) {
    fun build(playerInput: String, chapter: Int): ContextBundle {
        val time = queryOne(
            saveDb,
            "SELECT year_label,era_name,season,hour,minute,absolute_day FROM campaign_calendar WHERE id=1"
        )

        val status = linkedMapOf<String, Any?>(
            "chapter" to chapter,
            "player_input" to playerInput
        ).apply {
            try {
                queryOne(saveDb, "SELECT * FROM character_status_snapshot LIMIT 1").forEach { (k, v) -> put(k, v) }
            } catch (_: Exception) {}
        }

        val threads = queryMany(
            saveDb,
            """SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description
               FROM story_threads WHERE status='active'
               ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20"""
        )

        val missions = queryMany(
            saveDb,
            """SELECT mission_uid,title,mission_rank,status,objective_summary,reward_ryo,deadline_day,location_uid
               FROM missions_v3
               WHERE status IN ('available','active','assigned')
               ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END, reward_ryo DESC
               LIMIT 20"""
        )

        val pressures = queryMany(
            saveDb,
            """SELECT pressure_uid,target_type,target_uid,starts_day,peaks_day,pressure_type,magnitude,hidden,summary
               FROM future_world_pressure
               ORDER BY magnitude DESC LIMIT 20"""
        )

        val chronicle = queryMany(
            saveDb,
            """SELECT chapter,title,active_threads_json,decisions_json,consequences_json,quests_json,continuity_warnings_json
               FROM chapter_manifests_v2
               ORDER BY chapter DESC LIMIT 8"""
        )

        val memories = queryMany(
            saveDb,
            """SELECT memory_uid,entity_uid,memory_type,source_chapter,importance,keywords,summary
               FROM narrative_memory_index
               WHERE active=1
               ORDER BY importance DESC,source_chapter DESC LIMIT 25"""
        )

        val scene = linkedMapOf<String, Any?>(
            "query" to playerInput
        ).apply {
            try {
                queryOne(saveDb, "SELECT location_uid,last_updated_day,updated_chapter FROM entity_positions LIMIT 1")
                    .forEach { (k, v) -> put(k, v) }
            } catch (_: Exception) {}
        }

        val relevantNpcIds = LinkedHashSet<String>()
        try {
            queryMany(
                saveDb,
                """SELECT entity_uid FROM npc_schedules
                   WHERE visibility='gm' ORDER BY priority DESC LIMIT 12"""
            ).forEach { row ->
                (row["entity_uid"] as? String)?.let { relevantNpcIds += it }
            }
        } catch (_: Exception) {}

        val npcRows = mutableListOf<Map<String, Any?>>()
        val knowledgeRows = mutableListOf<Map<String, Any?>>()
        for (id in relevantNpcIds.take(12)) {
            try {
                queryOne(worldDb, "SELECT * FROM canon_characters_v2 WHERE character_uid=?", arrayOf(id))
                    .takeIf { it.isNotEmpty() }?.let { npcRows += it }
            } catch (_: Exception) {}
            try {
                knowledgeRows += queryMany(
                    saveDb,
                    """SELECT k.holder_uid,k.info_uid,k.confidence,k.accuracy,k.acquisition_method,k.learned_chapter,
                              f.title,f.content_summary,f.secrecy_level
                       FROM information_knowledge k
                       JOIN information_facts f ON f.info_uid=k.info_uid
                       WHERE k.holder_uid=?
                       ORDER BY k.confidence DESC,k.learned_chapter DESC LIMIT 20""",
                    arrayOf(id)
                )
            } catch (_: Exception) {}
        }

        val constraints = try {
            queryMany(
                worldDb,
                """SELECT constraint_uid,subject_type,subject_uid,constraint_key,constraint_value,canon_scope,notes
                   FROM canon_constraints_v2
                   WHERE status='active' OR status IS NULL
                   LIMIT 30"""
            )
        } catch (_: Exception) {
            emptyList()
        }

        return ContextBundle(
            playerStatus = status,
            scene = scene,
            time = time,
            activeThreads = threads,
            relevantNpcs = npcRows,
            npcKnowledge = knowledgeRows,
            missions = missions,
            worldPressures = pressures,
            canonConstraints = constraints,
            recentChronicle = chronicle,
            retrievedLongTermMemory = memories
        )
    }

    private fun queryOne(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Map<String, Any?> =
        queryMany(db, sql, args).firstOrNull() ?: emptyMap()

    private fun queryMany(db: SQLiteDatabase, sql: String, args: Array<String>? = null): List<Map<String, Any?>> {
        val out = mutableListOf<Map<String, Any?>>()
        db.rawQuery(sql, args).use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val row = LinkedHashMap<String, Any?>()
                for (i in names.indices) {
                    row[names[i]] = when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_NULL -> null
                        android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        android.database.Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
                out += row
            }
        }
        return out
    }
}
