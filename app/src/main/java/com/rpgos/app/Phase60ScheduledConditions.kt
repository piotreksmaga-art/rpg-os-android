package com.rpgos.app

import kotlinx.serialization.json.*

/** An evaluation request owned by Core; it is not a promise about an NPC/world outcome. */
internal data class ScheduledConditionExpiry(val deadlineUid:String,val subject:DomainRef,val conditionUid:String,val applicationUids:Set<String>) {
    init { require(deadlineUid.isNotBlank() && conditionUid.isNotBlank() && applicationUids.isNotEmpty() && applicationUids.size<=128 && applicationUids.none{it.isBlank()}) }
}

internal object Phase60ScheduledConditions {
    const val OWNER="P60:CONDITION_EXPIRY"
    fun encode(rows:List<ScheduledConditionExpiry>):String {
        require(rows.size<=4096 && rows.map{it.deadlineUid}.distinct().size==rows.size)
        return JsonArray(rows.sortedBy{it.deadlineUid}.map { row->buildJsonObject {
            put("deadline",row.deadlineUid);put("kind",row.subject.kindUid);put("subject",row.subject.uid);put("condition",row.conditionUid)
            put("applications",JsonArray(row.applicationUids.sorted().map(::JsonPrimitive)))
        } }).toString()
    }
    fun decode(value:String):List<ScheduledConditionExpiry> {
        require(value.length<=1_048_576)
        val rows=Json.parseToJsonElement(value).jsonArray;require(rows.size<=4096)
        return rows.map { row->
            val obj=row.jsonObject
            require(obj.keys==setOf("deadline","kind","subject","condition","applications"))
            fun field(key:String)=obj.getValue(key).jsonPrimitive.let{require(it.isString);it.content}
            val applications=obj.getValue("applications").jsonArray.map{require(it.jsonPrimitive.isString);it.jsonPrimitive.content}
            require(applications.distinct().size==applications.size)
            ScheduledConditionExpiry(field("deadline"),DomainRef(field("kind"),field("subject")),field("condition"),applications.toSet())
        }.also { require(encode(it)==value) }
    }
    /** Called by a domain resolver while preparing a sealed temporal change, never by an AI codec. */
    fun schedule(state:CanonicalTemporalState,entry:ScheduledConditionExpiry,due:WorldTimeTick):CanonicalTemporalState {
        require(due>state.time)
        val existing=state.processStates.singleOrNull{it.ownerUid==OWNER}
        require(existing==null || existing.version==1)
        val rows=existing?.let{decode(it.canonicalValue)}.orEmpty()
        val deadline=WorldProcessDeadline(entry.deadlineUid,OWNER,due)
        if(state.deadlines.any{it.uid==entry.deadlineUid}) {
            require(state.deadlines.single{it.uid==entry.deadlineUid}==deadline && rows.singleOrNull{it.deadlineUid==entry.deadlineUid}==entry)
            return state
        }
        require(rows.none{it.deadlineUid==entry.deadlineUid})
        return state.copy(processStates=(state.processStates.filterNot{it.ownerUid==OWNER}+
            TemporalOwnerState(OWNER,1,encode(rows+entry))).sortedBy{it.ownerUid},
            deadlines=(state.deadlines+deadline).sortedWith(compareBy<WorldProcessDeadline>{it.due}.thenBy{it.uid}))
    }
    fun registered(campaignUid:String,currentApplications:Map<String,Set<String>>,
                   decisionSubjects:Set<DomainRef> = emptySet())=RegisteredTemporalOwner("1",object:WorldProcessOwnerPort {
        override val ownerUid=OWNER
        override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
            if(input.scope.campaignUid!=campaignUid || input.previous?.version!=1 || input.actions.isNotEmpty())
                return TemporalOwnerResult.Unsupported("P60:CONDITION_EXPIRY_SCOPE")
            val rows=decode(input.previous.canonicalValue)
            val due=input.deadlines.map{it.uid}.toSet()
            if(!rows.map{it.deadlineUid}.toSet().containsAll(due)) return TemporalOwnerResult.Unsupported("P60:CONDITION_EXPIRY_RECORD_MISSING")
            if(rows.filter{it.deadlineUid in due}.any{currentApplications[it.deadlineUid]!=it.applicationUids})
                return TemporalOwnerResult.Unsupported("P60:CONDITION_APPLICATION_CHANGED")
            return TemporalOwnerResult.Evaluated(TemporalOwnerState(OWNER,1,encode(rows.filterNot{it.deadlineUid in due})),
                rows.filter{it.deadlineUid in due}.map{ConditionChange(it.subject,it.conditionUid,ConditionOperation.REMOVE)},
                playerDecisionRequired=rows.any{it.deadlineUid in due && it.subject in decisionSubjects})
        }
    })
}
