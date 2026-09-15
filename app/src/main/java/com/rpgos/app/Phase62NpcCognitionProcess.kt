package com.rpgos.app

import kotlinx.serialization.json.*

internal data class NpcCognitionStimulus(val actor:DomainRef,val brainRevision:Long,val acquisitionUid:String,val order:Long,
    val causeKind:NpcCauseKind=NpcCauseKind.KNOWLEDGE_ACQUISITION) {
    init { require(brainRevision>0 && order>=0);npcUid(acquisitionUid)
        require(causeKind in setOf(NpcCauseKind.KNOWLEDGE_ACQUISITION,NpcCauseKind.INTRINSIC_MOTIVATION)) }
    val cause get()=NpcCauseRef(causeKind,acquisitionUid)
    val triggerKind get()=if(causeKind==NpcCauseKind.INTRINSIC_MOTIVATION)NpcTriggerKind.SELF_REFLECTION else NpcTriggerKind.KNOWLEDGE_CHANGED
}
internal data class TemporalProcessExtension(val owners:List<RegisteredTemporalOwner>,val evaluation:TemporalExternalEvaluationPort?) {
    companion object { val NONE=TemporalProcessExtension(emptyList(),null) }
    fun plus(other:TemporalProcessExtension):TemporalProcessExtension {
        require(owners.none{a->other.owners.any{it.owner.ownerUid==a.owner.ownerUid}})
        return TemporalProcessExtension(owners+other.owners,TemporalExternalEvaluationPort{request,cancelled->
            val port=if(owners.any{it.owner.ownerUid==request.ownerUid})evaluation else other.evaluation
            port?.evaluate(request,cancelled)?:TemporalEvaluationResponse.Unavailable("P62:EVALUATION_OWNER_MISSING")
        })
    }
}
internal data class NpcCognitionCursor(val actor:DomainRef,val throughOrder:Long) {
    init { require(throughOrder>=0) }
}

/** NPC cognition runs once at the initial time boundary. It deliberately cannot claim movement,
 * speech or combat happened: those need the separately timed mechanics/communication owners. */
internal class NpcCognitionProcess(private val expected:TemporalScope,private val at:WorldTimeTick,
    private val stimuli:List<NpcCognitionStimulus>,
    private val decide:(NpcCognitionStimulus,()->Boolean)->NpcDecisionResult) {
    companion object {
        const val OWNER="P62:NPC_COGNITION"
        fun encode(cursors:List<NpcCognitionCursor>,diagnostics:Map<String,String> = emptyMap()):String {
            require(cursors.size<=128 && cursors.map{it.actor}.distinct().size==cursors.size && diagnostics.size<=4)
            return buildJsonObject {
                put("cursors",JsonArray(cursors.sortedWith(compareBy<NpcCognitionCursor>{it.actor.kindUid}.thenBy{it.actor.uid}).map { row ->
                    buildJsonObject{put("actor",NpcBrainCodec.ref(row.actor));put("order",row.throughOrder)}
                }))
                put("diagnostics",JsonObject(diagnostics.toSortedMap().mapValues{JsonPrimitive(it.value.take(160))}))
            }.toString()
        }
        fun decode(state:TemporalOwnerState?):List<NpcCognitionCursor> {
            if(state==null)return emptyList()
            require(state.ownerUid==OWNER && state.version==1 && state.canonicalValue.length<=65536)
            val value=Json.parseToJsonElement(state.canonicalValue).jsonObject
            NpcBrainCodec.keys(value,"cursors","diagnostics")
            val cursors=value.getValue("cursors").jsonArray.also{require(it.size<=128)}.map{row->
                val o=row.jsonObject;NpcBrainCodec.keys(o,"actor","order")
                NpcCognitionCursor(NpcBrainCodec.readRef(o.getValue("actor")),NpcBrainCodec.number(o,"order"))
            }
            val diagnostics=value.getValue("diagnostics").jsonObject.mapValues{NpcBrainCodec.text(value.getValue("diagnostics").jsonObject,it.key)}
            require(encode(cursors,diagnostics)==state.canonicalValue)
            return cursors
        }
    }
    init { require(stimuli.size<=32 && stimuli.map{it.actor}.distinct().size==stimuli.size && stimuli.all{it.order<=expected.baseCommitOrder}) }
    private fun pending(input:TemporalOwnerInput):List<NpcCognitionStimulus> {
        val cursor=decode(input.previous).associate{it.actor to it.throughOrder}
        return stimuli.filter{it.order>(cursor[it.actor]?:-1)}
            .sortedWith(compareBy<NpcCognitionStimulus>{it.order}.thenBy{it.actor.kindUid}.thenBy{it.actor.uid})
            .take(4)
    }
    fun extension():TemporalProcessExtension {
        val registered=RegisteredTemporalOwner("1",object:WorldProcessOwnerPort {
            override val ownerUid=OWNER
            override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
                if(input.scope!=expected || input.actions.isNotEmpty() || input.deadlines.isNotEmpty())return TemporalOwnerResult.Unsupported("P62:COGNITION_SCOPE")
                if(input.from==at && input.through==at && pending(input).isNotEmpty())return TemporalOwnerResult.EvaluationRequired("P62:COGNITION_REQUIRED")
                decode(input.previous)
                return TemporalOwnerResult.Evaluated(input.previous?:TemporalOwnerState(OWNER,1,encode(emptyList())))
            }
        })
        return TemporalProcessExtension(listOf(registered),TemporalExternalEvaluationPort { request,cancelled ->
            if(request.ownerUid!=OWNER || request.input.scope!=expected || request.input.from!=at || request.input.through!=at)
                return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P62:COGNITION_CORRELATION")
            val cursors=decode(request.input.previous).associateByTo(linkedMapOf()){it.actor}
            val changes=mutableListOf<NpcBrainChange>();val diagnostics=linkedMapOf<String,String>()
            val evaluating=pending(request.input)
            for(stimulus in evaluating) {
                if(cancelled())return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P60:CANCELLED")
                when(val result=decide(stimulus,cancelled)) {
                    is NpcDecisionResult.Unavailable -> {
                        if(result.reasonUid in setOf("P62:STALE_SCOPE","P62:CANCELLED"))return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable(result.reasonUid)
                        diagnostics[stimulus.actor.uid]=result.reasonUid
                    }
                    is NpcDecisionResult.Selected -> return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P62:PHYSICAL_ACTION_OWNER_REQUIRED")
                    is NpcDecisionResult.Reflected -> {
                        if(result.brainChanges.size>3 || result.brainChanges.withIndex().any{(i,change)->
                            change.actor!=stimulus.actor || change.campaignUid!=expected.campaignUid || change.historyGenerationUid!=expected.historyGenerationUid ||
                                change.expectedVersion!=stimulus.brainRevision+i})
                            return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P62:COGNITION_CHANGE_SCOPE")
                        changes+=result.brainChanges
                        cursors[stimulus.actor]=NpcCognitionCursor(stimulus.actor,stimulus.order)
                        // This is a bounded scheduling cursor, not the NPC's durable knowledge
                        // or appraisal watermark. Do not permanently disable the 129th actor.
                        while(cursors.size>128) {
                            val evicted=cursors.values.filter{row->evaluating.none{it.actor==row.actor}}
                                .minWith(compareBy<NpcCognitionCursor>{it.throughOrder}.thenBy{it.actor.kindUid}.thenBy{it.actor.uid})
                            cursors.remove(evicted.actor)
                        }
                        diagnostics[stimulus.actor.uid]="P62:COGNITION_EVALUATED"
                    }
                }
            }
            TemporalEvaluationResponse.Accepted(request.fingerprint,TemporalOwnerResult.Evaluated(TemporalOwnerState(OWNER,1,encode(cursors.values.toList(),diagnostics)),changes))
        })
    }
}
