package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

data class SyncCheckResult(
    val ok:Boolean,
    val issues:List<String>
)

class SyncManager {
    fun check(core:SQLiteDatabase, world:SQLiteDatabase, save:SQLiteDatabase):SyncCheckResult{
        val issues=mutableListOf<String>()
        fun integrity(db:SQLiteDatabase,label:String){
            db.rawQuery("PRAGMA integrity_check",null).use{c->
                if(!c.moveToFirst() || c.getString(0)!="ok")issues+="$label integrity failed"
            }
        }
        integrity(core,"core");integrity(world,"world");integrity(save,"save")
        try{
            core.rawQuery("SELECT COUNT(*) FROM source_of_truth_registry",null).use{if(!it.moveToFirst())issues+="Source of Truth missing"}
        }catch(_:Exception){issues+="Source of Truth missing"}
        try{
            save.rawQuery("SELECT COUNT(*) FROM chapter_manifests_v2",null).use{}
        }catch(_:Exception){issues+="chapter_manifests_v2 missing"}
        return SyncCheckResult(issues.isEmpty(),issues)
    }
}
