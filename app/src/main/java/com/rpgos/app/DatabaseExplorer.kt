package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

data class DbTableInfo(val name:String,val rows:Int,val writable:Boolean)

class DatabaseExplorer(
    private val coreDb:SQLiteDatabase,
    private val saveDb:SQLiteDatabase
){
    fun tables():List<DbTableInfo>{
        val writable=mutableSetOf<String>()
        try{
            coreDb.rawQuery("SELECT active_table FROM source_of_truth_registry",null).use{c->
                while(c.moveToNext())c.getString(0).split(";").map{it.trim()}.filter{it.isNotBlank()}.forEach{writable+=it}
            }
        }catch(_:Exception){}
        val out=mutableListOf<DbTableInfo>()
        saveDb.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",null).use{c->
            while(c.moveToNext()){
                val name=c.getString(0)
                val rows=runCatching{saveDb.rawQuery("SELECT COUNT(*) FROM \"$name\"",null).use{q->if(q.moveToFirst())q.getInt(0) else 0}}.getOrDefault(0)
                out+=DbTableInfo(name,rows,name in writable)
            }
        }
        return out
    }
}
