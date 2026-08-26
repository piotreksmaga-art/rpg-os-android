package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement

/** SQLite UPSERT appeared after the oldest engine supported by the application. */
internal fun SQLiteDatabase.updateOrInsertCompat(
    updateSql:String,
    updateArgs:Array<out Any?>,
    insertSql:String,
    insertArgs:Array<out Any?>
){
    val statement=compileStatement(updateSql)
    val updated=try{
        updateArgs.forEachIndexed{index,value->statement.bindCompat(index+1,value)}
        statement.executeUpdateDelete()
    }finally{statement.close()}
    if(updated==0)execSQL(insertSql,insertArgs)
}

private fun SQLiteStatement.bindCompat(index:Int,value:Any?){
    when(value){
        null->bindNull(index)
        is ByteArray->bindBlob(index,value)
        is Float->bindDouble(index,value.toDouble())
        is Double->bindDouble(index,value)
        is Boolean->bindLong(index,if(value)1 else 0)
        is Number->bindLong(index,value.toLong())
        else->bindString(index,value.toString())
    }
}
