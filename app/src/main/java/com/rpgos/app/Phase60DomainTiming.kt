package com.rpgos.app

/** A versioned domain conversion, not a conversion of commit orders to world time. */
internal object Phase60CombatTime {
    const val RULE="P60:COMBAT_TICK_MS_V1"
    const val MILLIS_PER_TICK=1_000L
    fun metadata(schedule:CombatActionSchedule):Map<String,String> {
        val start=schedule.windows.first().startsAtTick
        val duration=Math.multiplyExact(Math.subtractExact(schedule.windows.last().endsAtTick,start),MILLIS_PER_TICK)
        val impact=Math.multiplyExact(Math.subtractExact(schedule.window(CombatActionPhase.IMPACT).startsAtTick,start),MILLIS_PER_TICK)
        require(duration>0 && impact in 0..duration)
        return mapOf("p60_core_duration_ms" to duration.toString(),"p60_core_timing_rule" to RULE,
            "p60_core_effect_at_ms" to impact.toString())
    }
}

/** Only resolved mechanics payloads enter this adapter, never model request parameters. */
internal object Phase60DomainTiming {
    private val rules=setOf(Phase60CombatTime.RULE,NpcActivityMechanics.TIMING_RULE,NpcSpeechMechanics.TIMING_RULE)
    fun accepted(effects:List<VerifiedMechanicsCommandEffect>):Map<String,AcceptedActionTiming> = effects
        .filter{it.canonicalPayload["p60_core_timing_rule"] in rules}
        .groupBy{it.nodeUid}.mapValues { (_,rows)->
            val durations=rows.map{it.canonicalPayload.getValue("p60_core_duration_ms").toLong()}.distinct()
            val identities=rows.map{it.canonicalPayload.getValue("p60_core_timing_rule") to (it.canonicalPayload["p60_core_timing_version"]?.toInt()?:1)}.distinct()
            require(durations.size==1 && durations.single()>0 && identities.size==1){"P60:DOMAIN_TIMING_CONFLICT"}
            AcceptedActionTiming(ActionDuration(durations.single()),identities.single().first,identities.single().second)
        }
    fun effectOffset(effect:VerifiedMechanicsCommandEffect, duration:ActionDuration):Long {
        if(effect.canonicalPayload["p60_core_timing_rule"] !in rules)return duration.milliseconds
        val offset=effect.canonicalPayload.getValue("p60_core_effect_at_ms").toLong()
        require(effect.canonicalPayload.getValue("p60_core_duration_ms").toLong()==duration.milliseconds && offset in 0..duration.milliseconds)
        return offset
    }
}

internal fun phase60ElapsedLabel(milliseconds:Long):String {
    require(milliseconds>=0)
    val days=milliseconds/86_400_000;val hours=milliseconds/3_600_000%24
    val minutes=milliseconds/60_000%60;val seconds=milliseconds/1_000%60;val fraction=milliseconds%1_000
    return buildList {
        if(days>0)add("$days d")
        if(hours>0)add("$hours godz.")
        if(minutes>0)add("$minutes min")
        if(seconds>0 || fraction>0 || isEmpty())add(if(fraction==0L)"$seconds s" else "$seconds,${fraction.toString().padStart(3,'0')} s")
    }.joinToString(" ")
}
