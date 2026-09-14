package com.rpgos.app

import kotlinx.serialization.json.*

internal enum class TemporalActionCompletion { COMPLETED, INTERRUPTED, NOT_STARTED }
internal data class TemporalActionExecution(val nodeUid:String,val startedAt:WorldTimeTick,val plannedEnd:WorldTimeTick,
    val elapsed:ActionDuration,val completion:TemporalActionCompletion)

/** Factual execution coverage, not an award of skill/XP and not an instruction to auto-continue. */
internal object Phase60ExecutionReport {
    fun from(work:TemporalExecutionCheckpoint)=work.schedule.map { interval->
        val elapsed=when {
            work.reached<=interval.start->0L
            work.reached>=interval.end->interval.action.timing.duration.milliseconds
            else->Math.subtractExact(work.reached.milliseconds,interval.start.milliseconds)
        }
        TemporalActionExecution(interval.action.uid,interval.start,interval.end,ActionDuration(elapsed),when {
            work.reached>=interval.end->TemporalActionCompletion.COMPLETED
            work.reached<=interval.start->TemporalActionCompletion.NOT_STARTED
            else->TemporalActionCompletion.INTERRUPTED
        })
    }
    fun encode(rows:List<TemporalActionExecution>):String {
        require(rows.size<=1024 && rows.map{it.nodeUid}.distinct().size==rows.size)
        return JsonArray(rows.sortedBy{it.nodeUid}.map { row->buildJsonObject {
            put("node",row.nodeUid);put("start",row.startedAt.milliseconds);put("end",row.plannedEnd.milliseconds)
            put("elapsed",row.elapsed.milliseconds);put("completion",row.completion.name)
        } }).toString()
    }
    fun decode(value:String,from:WorldTimeTick,through:WorldTimeTick):List<TemporalActionExecution> {
        require(value.length<=262_144)
        val input=Json.parseToJsonElement(value).jsonArray;require(input.size<=1024)
        val rows=input.map { element ->
            val row=element.jsonObject;require(row.keys==setOf("node","start","end","elapsed","completion"))
            fun text(key:String)=row.getValue(key).jsonPrimitive.let{require(it.isString);it.content}
            fun number(key:String)=row.getValue(key).jsonPrimitive.let{require(!it.isString);it.long}
            val start=WorldTimeTick(number("start"));val end=WorldTimeTick(number("end"))
            require(start>=from && end>=start)
            val elapsed=when {through<=start->0L;through>=end->Math.subtractExact(end.milliseconds,start.milliseconds)
                else->Math.subtractExact(through.milliseconds,start.milliseconds)}
            val completion=when {through>=end->TemporalActionCompletion.COMPLETED;through<=start->TemporalActionCompletion.NOT_STARTED
                else->TemporalActionCompletion.INTERRUPTED}
            require(number("elapsed")==elapsed && text("completion")==completion.name && text("node").isNotBlank())
            TemporalActionExecution(text("node"),start,end,ActionDuration(elapsed),completion)
        }
        require(encode(rows)==value)
        return rows
    }
}

internal fun phase60PlayerExecutionSummary(change:TemporalStateChange):String {
    val rows=Phase60ExecutionReport.decode(change.actionExecutionsCanonical,change.expectedTime,change.proposedTime)
    val elapsed=phase60ElapsedLabel(Math.subtractExact(change.proposedTime.milliseconds,change.expectedTime.milliseconds))
    return buildString {
        append("Upłynęło $elapsed czasu świata.")
        rows.forEachIndexed { index,row ->
            append(" Czynność ${index+1}: ")
            when(row.completion) {
                TemporalActionCompletion.COMPLETED->append("zakończona.")
                TemporalActionCompletion.NOT_STARTED->append("nierozpoczęta.")
                TemporalActionCompletion.INTERRUPTED->append("wykonano ${phase60ElapsedLabel(row.elapsed.milliseconds)} z planowanych ${phase60ElapsedLabel(Math.subtractExact(row.plannedEnd.milliseconds,row.startedAt.milliseconds))}; przerwana.")
            }
        }
        if(change.stopReason=="PLAYER_DECISION")append(" Dalsza decyzja należy do gracza. Niewykonana część nie została rozstrzygnięta.")
    }
}
