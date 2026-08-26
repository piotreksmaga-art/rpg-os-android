package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

/**
 * Durable Phase50 state adapter over the accepted campaign owners. Callers may use it only from
 * TurnTransaction; it deliberately exposes no transaction or authority bypass.
 */
internal class Phase50MechanicalStateStore(private val db:SQLiteDatabase,private val campaignUid:String){
    fun applyCondition(identity:TurnTransactionIdentity,changeUid:String,change:ConditionChange,effectiveOrder:Long){
        require(identity.campaignUid==campaignUid&&db.inTransaction()){"RPGOS-P50:CONDITION_OUTSIDE_TURN"}
        when(change.operation){
            ConditionOperation.ADD->db.execSQL(
                "INSERT INTO active_combat_effects(active_effect_uid,entity_uid,effect_key,magnitude,started_chapter,status) VALUES(?,?,?,?,?,'active')",
                arrayOf<Any?>("${identity.transactionUid}:$changeUid",change.subject.uid,"CONDITION:${change.conditionUid}",1.0,effectiveOrder)
            )
            ConditionOperation.REMOVE->{
                val statement=db.compileStatement("UPDATE active_combat_effects SET status='removed',remaining_duration_sec=0 WHERE entity_uid=? AND effect_key=? AND status='active'")
                statement.use{it.bindString(1,change.subject.uid);it.bindString(2,"CONDITION:${change.conditionUid}");require(it.executeUpdateDelete()>0){"RPGOS-P50:CONDITION_NOT_ACTIVE"}}
            }
        }
    }

    fun applyRuntime(identity:TurnTransactionIdentity,changeUid:String,change:RuntimeChange,effectiveOrder:Long){
        require(identity.campaignUid==campaignUid&&db.inTransaction()){"RPGOS-P50:RUNTIME_OUTSIDE_TURN"}
        val prefix="RPGOS-MECHANICS:";require(change.runtimeCounterUid.startsWith(prefix)){"RPGOS-P50:UNOWNED_RUNTIME_COUNTER"}
        val encoded=change.runtimeCounterUid.removePrefix(prefix)
        val kind=encoded.substringBefore(':')
        val effectKey=encoded.substringBeforeLast(':')
        when(kind){
            "MOVEMENT"->applyMovement(change,effectiveOrder)
            "WOUND","PERSISTENT_EFFECT","EQUIPMENT_DAMAGE","STRUCTURE_DAMAGE","MORALE","COHESION","FORMATION","ENVIRONMENT",
            "AGGREGATE_ELIMINATION","AGGREGATE_INJURY","AGGREGATE_CONDITION"->
                appendEffect(identity,changeUid,change,effectiveOrder,effectKey)
            else->error("RPGOS-P50:UNSUPPORTED_RUNTIME_COUNTER:$kind")
        }
    }

    private fun applyMovement(change:RuntimeChange,effectiveOrder:Long){
        val update=db.compileStatement("UPDATE entity_positions SET x_coord=COALESCE(x_coord,0)+?,y_coord=COALESCE(y_coord,0),last_updated_day=?,updated_chapter=? WHERE entity_uid=?")
        val changed=update.use{statement->
            statement.bindDouble(1,change.delta.units.toDouble());statement.bindLong(2,effectiveOrder);statement.bindLong(3,effectiveOrder);statement.bindString(4,change.subject.uid)
            statement.executeUpdateDelete()
        }
        if(changed==0)db.execSQL(
            "INSERT INTO entity_positions(entity_uid,x_coord,y_coord,last_updated_day,updated_chapter) VALUES(?,?,0,?,?)",
            arrayOf<Any?>(change.subject.uid,change.delta.units.toDouble(),effectiveOrder,effectiveOrder)
        )
    }

    private fun appendEffect(identity:TurnTransactionIdentity,changeUid:String,change:RuntimeChange,effectiveOrder:Long,kind:String){
        db.execSQL(
            "INSERT INTO active_combat_effects(active_effect_uid,entity_uid,source_entity_uid,effect_key,magnitude,started_chapter,status) VALUES(?,?,?,?,?,?,'active')",
            arrayOf<Any?>("${identity.transactionUid}:$changeUid",change.subject.uid,identity.commandUid,kind,change.delta.units.toDouble(),effectiveOrder)
        )
    }
}
