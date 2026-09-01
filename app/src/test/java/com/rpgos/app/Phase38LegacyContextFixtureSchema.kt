package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

internal object Phase38LegacyContextFixtureSchema {
    fun ensure(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS entity_positions(entity_uid TEXT,location_uid TEXT,x_coord REAL,y_coord REAL,last_updated_day INTEGER,updated_chapter INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS injuries_v2(injury_uid TEXT,entity_uid TEXT,body_part_uid TEXT,severity INTEGER,pain_level INTEGER,bleeding_rate INTEGER,status TEXT,chapter_received INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS story_threads(thread_uid TEXT,title TEXT,thread_type TEXT,status TEXT,priority INTEGER,last_advanced_chapter INTEGER,description TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS missions_v3(mission_uid TEXT,title TEXT,mission_rank TEXT,status TEXT,objective_summary TEXT,reward_ryo INTEGER,deadline_day INTEGER,location_uid TEXT,consequence_on_failure TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS future_world_pressure(pressure_uid TEXT,target_type TEXT,target_uid TEXT,starts_day INTEGER,peaks_day INTEGER,pressure_type TEXT,magnitude REAL,summary TEXT,hidden INTEGER DEFAULT 0)")
        db.execSQL("CREATE TABLE IF NOT EXISTS chapter_manifests_v2(chapter INTEGER,title TEXT,active_threads_json TEXT,decisions_json TEXT,consequences_json TEXT,quests_json TEXT,continuity_warnings_json TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS npc_memories_v2(memory_uid TEXT,entity_uid TEXT,memory_type TEXT,subject_uid TEXT,chapter INTEGER,day INTEGER,importance REAL,emotional_valence REAL,accuracy REAL,summary TEXT,active INTEGER DEFAULT 1)")
        db.execSQL("CREATE TABLE IF NOT EXISTS organization_memberships_v3(organization_uid TEXT,character_uid TEXT,unit_uid TEXT,position_uid TEXT,role_title TEXT,status TEXT,loyalty REAL)")
        if (!hasColumn(db, "organization_memberships_v3", "loyalty")) {
            db.execSQL("ALTER TABLE organization_memberships_v3 ADD COLUMN loyalty REAL")
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS timeline_events(timeline_uid TEXT,name TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS active_world_events(timeline_uid TEXT,event_type TEXT,status TEXT,public_summary TEXT,gm_summary TEXT,started_day INTEGER)")
    }

    fun ensure(saveDb: SQLiteDatabase, worldDb: SQLiteDatabase) {
        ensure(saveDb)
        ensureWorld(worldDb)
    }

    fun ensureWorld(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS canon_constraints_v2(constraint_uid TEXT,subject_type TEXT,subject_uid TEXT,constraint_key TEXT,constraint_value TEXT,canon_scope TEXT,notes TEXT,status TEXT)")
    }

    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex).equals(column, ignoreCase = true)) return@use true
            }
            false
        }
}
