package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

class SourceOfTruthRegistry(private val coreDb: SQLiteDatabase) {
    private val activeTables: Set<String> by lazy {
        val tables = linkedSetOf<String>()
        try {
            coreDb.rawQuery("SELECT active_table FROM source_of_truth_registry", null).use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(0) ?: continue
                    raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { tables += it }
                }
            }
        } catch (_: Exception) {}
        tables
    }

    private val readOnlyTables: Set<String> by lazy {
        val tables = linkedSetOf<String>()
        try {
            coreDb.rawQuery(
                "SELECT table_name FROM table_registry WHERE writable=0 OR lifecycle_status IN ('legacy','reference')",
                null
            ).use { c ->
                while (c.moveToNext()) tables += c.getString(0)
            }
        } catch (_: Exception) {}
        tables
    }

    fun canWrite(table: String): Boolean {
        // Campaign truth has a dedicated typed/provenance-aware repository path.
        // Generic StatePatch operations must never bypass that contract.
        if (table == "campaign_truth_records") return false
        if (table in readOnlyTables) return false
        return table in activeTables || isExplicitRuntimeTable(table)
    }

    private fun isExplicitRuntimeTable(table: String): Boolean {
        return table in setOf(
            "chapter_manifests_v2",
            "story_threads",
            "story_beats",
            "decision_points",
            "consequence_links",
            "narrative_memory_index",
            "information_knowledge",
            "information_facts",
            "entity_positions",
            "npc_memories_v2",
            "npc_decisions",
            "npc_action_candidates",
            "timeline_divergences",
            "gm_timeline_alerts",
            "financial_transactions",
            "mission_outcomes",
            "mission_participants"
        )
    }
}
