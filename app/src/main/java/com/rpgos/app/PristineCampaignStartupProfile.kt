package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Opt-in package data, applied only by the new-campaign administrative owner, never on reopen. */
internal data class PristineCampaignStartupProfile(val worldPackUid:String,val eraKey:String,
    val eraName:String,val yearLabel:String) {
    init { require(listOf(worldPackUid,eraKey,eraName,yearLabel).none{it.isBlank()}) }

    fun apply(db:SQLiteDatabase,campaignUid:String):Boolean {
        fun table(name:String)=db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(name)).use{it.moveToFirst()}
        if(table("active_player_ref") && db.rawQuery("SELECT 1 FROM active_player_ref LIMIT 1",null).use{it.moveToFirst()})return false
        if(table("turn_transaction_receipts") && db.rawQuery(
            "SELECT 1 FROM turn_transaction_receipts WHERE commit_order IS NOT NULL LIMIT 1",
            null).use{it.moveToFirst()})return false
        if(!table("campaign_calendar"))return false
        db.beginTransaction()
        try {
            val changed=db.rawQuery("SELECT era_key FROM campaign_calendar WHERE id=1",null).use{it.moveToFirst() && it.getString(0)!=eraKey}
            db.execSQL("UPDATE campaign_calendar SET absolute_day=0,year_number=0,year_label=?,era_key=?,era_name=?,canon_anchor_event_uid=NULL,updated_chapter=0 WHERE id=1",
                arrayOf<Any?>(yearLabel,eraKey,eraName))
            if(table("world_clock"))db.execSQL("UPDATE world_clock SET campaign_day=0,campaign_year=0,era=?,updated_chapter=0 WHERE id=1",arrayOf<Any?>(eraName))
            if(changed)listOf("active_world_events","timeline_events").filter(::table).forEach { name ->
                db.execSQL("UPDATE $name SET status='cancelled' WHERE status IN ('active','planned')")
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    companion object {
        fun fromJson(payload:String,worldPackUid:String):PristineCampaignStartupProfile? {
            val root=JSONObject(payload)
            require(root.getInt("schema_version")==1)
            val profiles=root.getJSONArray("profiles")
            return (0 until profiles.length()).map{profiles.getJSONObject(it)}
                .singleOrNull{it.getString("worldpack_uid")==worldPackUid}?.let {
                    PristineCampaignStartupProfile(worldPackUid,it.getString("era_key"),it.getString("era_name"),it.getString("year_label"))
                }
        }
    }
}
