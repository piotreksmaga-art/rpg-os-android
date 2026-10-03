package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/** A campaign-native world need not install Naruto-era presentation storage. Only these
 * explicitly optional projections may be absent. A present but corrupt schema still throws. */
internal fun optionalWorldPresentationPresent(db:SQLiteDatabase,table:String):Boolean {
    require(table in setOf("relationships_v2","organization_definitions_v3","political_entities",
        "npc_memories_v2","npc_beliefs","npc_schedules","npc_decisions","country_economies",
        "active_world_events","timeline_events"))
    return db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use { it.moveToFirst() }
}
