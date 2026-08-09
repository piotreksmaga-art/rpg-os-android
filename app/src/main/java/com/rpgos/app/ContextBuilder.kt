package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class ContextBuilder(
    private val saveDb: SQLiteDatabase,
    private val worldDb: SQLiteDatabase
) {
    fun build(playerInput: String, chapter: Int): ContextBundle {
        val campaignRef = ActiveCampaignRef.fromDatabasePath(saveDb.path)

        val campaign = safeQueryOne(
            saveDb,
            "SELECT campaign_name,schema_version,current_chapter,current_tome FROM campaign_meta WHERE id=1"
        )

        val time = safeQueryOne(
            saveDb,
            "SELECT year_label,era_name,season,hour,minute,absolute_day FROM campaign_calendar WHERE id=1"
        )

        val playerUid = ActivePlayerStore(saveDb, campaignRef.campaignId).active()?.playerUid

        val position = if (playerUid != null) safeQueryOne(
            saveDb,
            """SELECT entity_uid,location_uid,x_coord,y_coord,last_updated_day,updated_chapter
               FROM entity_positions WHERE entity_uid=? LIMIT 1""",
            arrayOf(playerUid)
        ) else emptyMap()

        val status = linkedMapOf<String, Any?>(
            "chapter" to chapter,
            "player_input" to playerInput,
            "player_uid" to playerUid,
            "campaign" to campaign
        ).apply {
            position.forEach { (k, v) -> put(k, v) }

            if (playerUid != null) {
                safeQueryOne(
                    saveDb,
                    """SELECT entity_uid,ryo,monthly_income,monthly_expenses,debt,property_value,
                              investment_value,updated_chapter
                       FROM character_finances WHERE entity_uid=? LIMIT 1""",
                    arrayOf(playerUid)
                ).forEach { (k, v) -> put("finance_$k", v) }

                put(
                    "injuries",
                    safeQueryMany(
                        saveDb,
                        """SELECT injury_uid,body_part_uid,severity,pain,bleeding,status,created_chapter
                           FROM injuries_v2 WHERE entity_uid=? AND status!='healed'
                           ORDER BY severity DESC LIMIT 12""",
                        arrayOf(playerUid)
                    )
                )
            }
        }

        val scene = linkedMapOf<String, Any?>(
            "query" to playerInput,
            "player_uid" to playerUid
        ).apply {
            position.forEach { (k, v) -> put(k, v) }

            val locationUid = position["location_uid"] as? String
            if (!locationUid.isNullOrBlank()) {
                safeQueryOne(
                    worldDb,
                    """SELECT location_uid,name,location_type,region_uid,description
                       FROM map_locations_v2 WHERE location_uid=? LIMIT 1""",
                    arrayOf(locationUid)
                ).forEach { (k, v) -> put("location_$k", v) }
            }
        }

        val threads = safeQueryMany(
            saveDb,
            """SELECT thread_uid,title,thread_type,status,priority,last_advanced_chapter,description
               FROM story_threads WHERE status='active'
               ORDER BY priority DESC,last_advanced_chapter DESC LIMIT 20"""
        )

        val missions = safeQueryMany(
            saveDb,
            """SELECT mission_uid,title,mission_rank,status,objective_summary,reward_ryo,
                      deadline_day,location_uid,consequence_on_failure
               FROM missions_v3
               WHERE status IN ('available','active','assigned')
               ORDER BY CASE status WHEN 'active' THEN 0 WHEN 'assigned' THEN 1 ELSE 2 END,
                        reward_ryo DESC
               LIMIT 20"""
        )

        val pressures = safeQueryMany(
            saveDb,
            """SELECT pressure_uid,target_type,target_uid,starts_day,peaks_day,pressure_type,
                      magnitude,hidden,summary
               FROM future_world_pressure
               ORDER BY magnitude DESC LIMIT 20"""
        )

        val activeWorldEvents = safeQueryMany(
            saveDb,
            """SELECT active_event_uid,event_type,subject_uid,location_uid,started_day,
                      expected_end_day,status,visibility,public_summary,gm_summary
               FROM active_world_events
               WHERE status='active'
               ORDER BY started_day DESC LIMIT 20"""
        )

        val chronicle = safeQueryMany(
            saveDb,
            """SELECT chapter,title,active_threads_json,decisions_json,consequences_json,
                      quests_json,continuity_warnings_json
               FROM chapter_manifests_v2
               ORDER BY chapter DESC LIMIT 10"""
        )

        val longTermMemory = safeQueryMany(
            saveDb,
            """SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,
                      emotional_valence,accuracy,summary
               FROM npc_memories_v2
               WHERE active=1
               ORDER BY importance DESC,chapter DESC LIMIT 30"""
        )

        val relevantNpcIds = LinkedHashSet<String>()

        safeQueryMany(
            saveDb,
            """SELECT entity_uid,location_uid,priority,summary
               FROM npc_schedules
               WHERE visibility IN ('gm','public')
               ORDER BY priority DESC LIMIT 20"""
        ).forEach { row ->
            (row["entity_uid"] as? String)?.let { relevantNpcIds += it }
        }

        safeQueryMany(
            saveDb,
            """SELECT holder_uid FROM information_knowledge
               ORDER BY confidence DESC,learned_chapter DESC LIMIT 20"""
        ).forEach { row ->
            (row["holder_uid"] as? String)?.let { relevantNpcIds += it }
        }

        val npcRows = mutableListOf<Map<String, Any?>>()
        val knowledgeRows = mutableListOf<Map<String, Any?>>()
        val npcMemoryRows = mutableListOf<Map<String, Any?>>()

        for (id in relevantNpcIds.take(16)) {
            val npc = safeQueryOne(
                worldDb,
                """SELECT character_uid,name,sex,birth_era,clan_uid,village_uid,rank_title,
                          affiliation_summary,personality_summary,combat_summary
                   FROM canon_characters_v2 WHERE character_uid=? LIMIT 1""",
                arrayOf(id)
            )
            if (npc.isNotEmpty()) npcRows += npc

            knowledgeRows += safeQueryMany(
                saveDb,
                """SELECT k.holder_uid,k.info_uid,k.confidence,k.accuracy,k.acquisition_method,
                          k.learned_chapter,f.title,f.content_summary,f.secrecy_level
                   FROM information_knowledge k
                   LEFT JOIN information_facts f ON f.info_uid=k.info_uid
                   WHERE k.holder_uid=?
                   ORDER BY k.confidence DESC,k.learned_chapter DESC LIMIT 16""",
                arrayOf(id)
            )

            npcMemoryRows += safeQueryMany(
                saveDb,
                """SELECT memory_uid,entity_uid,memory_type,subject_uid,chapter,day,importance,
                          emotional_valence,accuracy,summary
                   FROM npc_memories_v2
                   WHERE entity_uid=? AND active=1
                   ORDER BY importance DESC,chapter DESC LIMIT 12""",
                arrayOf(id)
            )
        }

        val constraints = safeQueryMany(
            worldDb,
            """SELECT constraint_uid,subject_type,subject_uid,constraint_key,constraint_value,
                      canon_scope,notes
               FROM canon_constraints_v2
               WHERE status='active' OR status IS NULL
               LIMIT 40"""
        )

        val skills = if (playerUid != null) safeQueryMany(
            saveDb,
            """SELECT entity_uid,skill_uid,mastery,xp,updated_chapter
               FROM character_skills WHERE entity_uid=?
               ORDER BY mastery DESC,xp DESC LIMIT 50""",
            arrayOf(playerUid)
        ) else emptyList()

        val techniques = if (playerUid != null) safeQueryMany(
            saveDb,
            """SELECT entity_uid,technique_uid,mastery,xp,learned_chapter,last_used_chapter,
                      usage_count,success_count,failure_count,is_equipped,notes
               FROM character_techniques WHERE entity_uid=?
               ORDER BY is_equipped DESC,mastery DESC,xp DESC LIMIT 60""",
            arrayOf(playerUid)
        ) else emptyList()

        val organizations = if (playerUid != null) safeQueryMany(
            saveDb,
            """SELECT organization_uid,character_uid,unit_uid,position_uid,role_title,loyalty,
                      secrecy_clearance,joined_era,status
               FROM organization_memberships_v3
               WHERE character_uid=? AND status='active'
               ORDER BY loyalty DESC""",
            arrayOf(playerUid)
        ) else emptyList()

        val meta = linkedMapOf<String, Any?>(
            "engine" to "ContextBundle Engine v1",
            "schema" to 1,
            "campaign_id" to campaignRef.campaignId,
            "campaign_directory" to campaignRef.directoryName,
            "chapter" to chapter,
            "player_uid" to playerUid,
            "player_uid_source" to "active_player_ref",
            "player_uid_resolved" to (playerUid != null),
            "threads" to threads.size,
            "missions" to missions.size,
            "pressures" to pressures.size,
            "world_events" to activeWorldEvents.size,
            "npcs" to npcRows.size,
            "npc_knowledge" to knowledgeRows.size,
            "npc_memories" to npcMemoryRows.size,
            "long_term_memory" to longTermMemory.size,
            "skills" to skills.size,
            "techniques" to techniques.size,
            "organizations" to organizations.size
        )

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
            retrievedLongTermMemory = longTermMemory,
            playerSkills = skills,
            playerTechniques = techniques,
            playerOrganizations = organizations,
            activeWorldEvents = activeWorldEvents,
            npcMemories = npcMemoryRows,
            contextMeta = meta
        )
    }

    private fun safeQueryOne(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>? = null
    ): Map<String, Any?> =
        safeQueryMany(db, sql, args).firstOrNull() ?: emptyMap()

    private fun safeQueryMany(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>? = null
    ): List<Map<String, Any?>> =
        try {
            queryMany(db, sql, args)
        } catch (_: Throwable) {
            emptyList()
        }

    private fun queryMany(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>? = null
    ): List<Map<String, Any?>> {
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
                        android.database.Cursor.FIELD_TYPE_BLOB ->
                            "[BLOB ${c.getBlob(i).size} bytes]"
                        else -> c.getString(i)
                    }
                }
                out += row
            }
        }
        return out
    }
}
