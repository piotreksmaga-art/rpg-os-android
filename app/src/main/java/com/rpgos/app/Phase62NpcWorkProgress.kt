package com.rpgos.app

/** Presentation-only progress, with no NPC identity, thought, knowledge, prompt or canonical data. */
fun interface NpcWorkProgressPort {
    fun begin(campaignUid:String,workload:AiWorkload):AutoCloseable
    companion object { val NONE=NpcWorkProgressPort{_,_->AutoCloseable{}} }
}

internal inline fun <T> NpcWorkProgressPort.observe(campaignUid:String,workload:AiWorkload,work:()->T):T {
    val observation=try{begin(campaignUid,workload)}catch(_:Exception){null}
    try{return work()}finally{try{observation?.close()}catch(_:Exception){/* UI failure never changes gameplay. */}}
}
