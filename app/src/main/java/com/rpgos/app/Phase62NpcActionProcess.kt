package com.rpgos.app

import kotlinx.serialization.json.*

/** A commitment to attempt an action, not a serialized future effect or model authorization. */
internal data class NpcPendingAction(val actor:DomainRef,val planUid:String,val optionUid:String,
    val startedAt:WorldTimeTick,val due:WorldTimeTick,val timingRuleUid:String,val timingRuleVersion:Int) {
    init { npcUid(planUid);npcUid(optionUid);npcUid(timingRuleUid);require(due>startedAt && timingRuleVersion>0) }
    val deadlineUid get()="P62:ACTION:${phase60Hash("${actor.kindUid}|${actor.uid}|$planUid") }"
}
internal sealed interface NpcActionPreparation {
    data class Started(val pending:NpcPendingAction,val changes:List<NpcBrainChange>):NpcActionPreparation
    data class Reflected(val changes:List<NpcBrainChange>):NpcActionPreparation
    data class Skipped(val reasonUid:String):NpcActionPreparation
}
internal sealed interface NpcActionCompletion {
    data class Finished(val brainChange:NpcBrainChange,val effects:List<VerifiedMechanicsCommandEffect>,
        val interrupted:Boolean=false,val playerDecisionRequired:Boolean=false,
        val continuation:NpcActionPreparation.Started?=null):NpcActionCompletion
    data class Unavailable(val reasonUid:String):NpcActionCompletion
}
internal interface NpcTimedActionPort {
    fun prepare(actor:DomainRef,input:TemporalOwnerInput,cancelled:()->Boolean):NpcActionPreparation
    fun complete(action:NpcPendingAction,input:TemporalOwnerInput,cancelled:()->Boolean):NpcActionCompletion
}

/** Phase60 owns the clock. Phase62 owns decisions and plan lifecycle. Existing mechanics owns
 * the outcome. Pending plans survive reopen, but their future effects are NEVER precomputed. */
internal class NpcActionProcess(private val expected:TemporalScope,private val start:WorldTimeTick,
    private val activePlayerUid:String,private val participants:List<DomainRef>,private val port:NpcTimedActionPort) {
    companion object {
        const val OWNER="P62:NPC_EXECUTION"
        fun encode(pending:List<NpcPendingAction>):String {
            require(pending.size<=128 && pending.map{it.actor}.distinct().size==pending.size)
            return JsonArray(pending.sortedWith(compareBy<NpcPendingAction>{it.actor.kindUid}.thenBy{it.actor.uid}).map{p->buildJsonObject {
                put("actor",NpcBrainCodec.ref(p.actor));put("plan",p.planUid);put("option",p.optionUid)
                put("started",p.startedAt.milliseconds);put("due",p.due.milliseconds);put("rule",p.timingRuleUid);put("rule_version",p.timingRuleVersion)
            }}).toString()
        }
        fun decode(state:TemporalOwnerState?):List<NpcPendingAction> {
            if(state==null)return emptyList()
            require(state.ownerUid==OWNER && state.version==1 && state.canonicalValue.length<=131072)
            val rows=Json.parseToJsonElement(state.canonicalValue).jsonArray.also{require(it.size<=128)}.map{element->
                val o=element.jsonObject;NpcBrainCodec.keys(o,"actor","plan","option","started","due","rule","rule_version")
                NpcPendingAction(NpcBrainCodec.readRef(o.getValue("actor")),NpcBrainCodec.text(o,"plan"),NpcBrainCodec.text(o,"option"),
                    WorldTimeTick(NpcBrainCodec.number(o,"started")),WorldTimeTick(NpcBrainCodec.number(o,"due")),
                    NpcBrainCodec.text(o,"rule"),NpcBrainCodec.integer(o,"rule_version"))
            }
            require(encode(rows)==state.canonicalValue)
            return rows
        }
    }
    init { require(participants.size<=32 && participants.distinct().size==participants.size && participants.none{it.uid==activePlayerUid}) }
    private fun due(input:TemporalOwnerInput,pending:List<NpcPendingAction>):List<NpcPendingAction> {
        val deadlineUids=input.deadlines.map{it.uid}.toSet()
        val due=pending.filter{it.deadlineUid in deadlineUids}
        require(due.size==deadlineUids.size && due.all{it.due==input.through}) { "P62:ACTION_DEADLINE_MISMATCH" }
        return due.sortedBy{it.deadlineUid}
    }
    fun extension():TemporalProcessExtension=TemporalProcessExtension(listOf(RegisteredTemporalOwner("1",object:WorldProcessOwnerPort {
        override val ownerUid=OWNER
        override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
            if(input.scope!=expected || input.actions.isNotEmpty())return TemporalOwnerResult.Unsupported("P62:ACTION_SCOPE")
            val pending=decode(input.previous)
            if(pending.any{it.actor.uid==activePlayerUid})return TemporalOwnerResult.Unsupported("P62:ACTIVE_PLAYER_CONTROL_FORBIDDEN")
            if(due(input,pending).isNotEmpty() || (input.from==start && input.through==start && participants.any{actor->pending.none{it.actor==actor}}))
                return TemporalOwnerResult.EvaluationRequired("P62:ACTION_EVALUATION")
            return TemporalOwnerResult.Evaluated(input.previous?:TemporalOwnerState(OWNER,1,encode(emptyList())))
        }
    })),TemporalExternalEvaluationPort { request,cancelled ->
        if(request.ownerUid!=OWNER || request.input.scope!=expected)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P62:ACTION_SCOPE")
        val input=request.input
        val pending=decode(input.previous).toMutableList()
        val brainChanges=mutableListOf<NpcBrainChange>();val effects=mutableListOf<VerifiedMechanicsCommandEffect>();val deadlines=mutableListOf<WorldProcessDeadline>()
        val completedActors=hashSetOf<DomainRef>()
        var decision=false
        fun overlay()=input.copy(stagedChanges=input.stagedChanges+brainChanges,stagedEffects=input.stagedEffects+effects)
        for(action in due(input,pending)) {
            if(cancelled())return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P60:CANCELLED")
            if(action.actor.uid==activePlayerUid)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P62:ACTIVE_PLAYER_CONTROL_FORBIDDEN")
            when(val finished=port.complete(action,overlay(),cancelled)) {
                is NpcActionCompletion.Unavailable->return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable(finished.reasonUid)
                is NpcActionCompletion.Finished->{
                    require(finished.brainChange.actor==action.actor && finished.brainChange.campaignUid==expected.campaignUid &&
                        finished.brainChange.historyGenerationUid==expected.historyGenerationUid)
                    require(!finished.interrupted || finished.effects.isEmpty()) { "P62:INTERRUPTED_ACTION_HAS_EFFECTS" }
                    brainChanges+=finished.brainChange;effects+=finished.effects;decision=decision||finished.playerDecisionRequired
                    pending.remove(action)
                    completedActors+=action.actor
                    finished.continuation?.let { continuation ->
                        require(!finished.playerDecisionRequired && continuation.changes.size==1)
                        val next=continuation.pending
                        require(next.actor==action.actor && next.planUid!=action.planUid && next.startedAt==input.through && next.due>input.through)
                        val old=NpcBrainCodec.decode(finished.brainChange.stateCanonical).plans.single{it.uid==action.planUid}
                        val update=continuation.changes.single()
                        require(update.actor==action.actor && update.campaignUid==expected.campaignUid && update.historyGenerationUid==expected.historyGenerationUid)
                        require(validNpcBrainChains(listOf(finished.brainChange,update)))
                        val new=NpcBrainCodec.decode(update.stateCanonical).plans.single{it.uid==next.planUid}
                        val sequence=!finished.interrupted && old.lifecycle==NpcPlanLifecycle.COMPLETED &&
                            old.nextActionUids.firstOrNull()==new.actionUid && old.nextActionUids.drop(1)==new.nextActionUids
                        val alternative=finished.interrupted && old.lifecycle==NpcPlanLifecycle.INTERRUPTED &&
                            old.onUnavailableOptionUid==new.actionUid && new.nextActionUids.isEmpty() && new.onUnavailableOptionUid==null
                        require((sequence || alternative) && new.goalUid==old.goalUid && new.previousPlanUid==old.uid)
                        brainChanges+=update;pending+=next
                        deadlines+=WorldProcessDeadline(next.deadlineUid,OWNER,next.due)
                    }
                }
            }
        }
        // One decision per participating actor per player turn, not an unbounded population loop.
        if(input.from==start && input.through==start)for(actor in participants.filter{actor->actor !in completedActors && pending.none{it.actor==actor}}.take(minOf(4,128-pending.size))) {
            if(cancelled())return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P60:CANCELLED")
            when(val prepared=port.prepare(actor,overlay(),cancelled)) {
                is NpcActionPreparation.Skipped->{
                    if(prepared.reasonUid in setOf("P62:STALE_SCOPE","P62:CANCELLED"))
                        return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable(prepared.reasonUid)
                }
                is NpcActionPreparation.Reflected->{
                    require(prepared.changes.size<=3 && prepared.changes.all{
                        it.actor==actor && it.campaignUid==expected.campaignUid && it.historyGenerationUid==expected.historyGenerationUid})
                    require(validNpcBrainChains(prepared.changes))
                    brainChanges+=prepared.changes
                }
                is NpcActionPreparation.Started->{
                    val action=prepared.pending
                    require(action.actor==actor && action.startedAt==input.through && action.due>input.through)
                    require(prepared.changes.isNotEmpty() && prepared.changes.size<=4 && prepared.changes.all{
                        it.actor==actor && it.campaignUid==expected.campaignUid && it.historyGenerationUid==expected.historyGenerationUid})
                    brainChanges+=prepared.changes;pending+=action
                    deadlines+=WorldProcessDeadline(action.deadlineUid,OWNER,action.due)
                }
            }
        }
        TemporalEvaluationResponse.Accepted(request.fingerprint,TemporalOwnerResult.Evaluated(TemporalOwnerState(OWNER,1,encode(pending)),
            brainChanges,deadlines,decision,effects))
    })
}
