package com.rpgos.app

import kotlinx.serialization.json.*

/** Correlated to the complete speculative owner input, never just a deadline UID. */
@ConsistentCopyVisibility
data class TemporalEvaluationRequest internal constructor(val ownerUid:String,val input:TemporalOwnerInput,val reasonUid:String) {
    val fingerprint:String=phase60Hash(buildJsonObject {
        put("owner",ownerUid);put("reason",reasonUid);put("campaign",input.scope.campaignUid)
        put("generation",input.scope.historyGenerationUid);put("order",input.scope.baseCommitOrder)
        put("canonical",input.scope.authoritativeFingerprint);put("from",input.from.milliseconds);put("through",input.through.milliseconds)
        put("staged_changes",JsonArray(input.stagedChanges.map{TypedPlayerChangeRegistry.core().encodeWorkerPayload(it)}))
        put("staged_effects",TemporalMechanicsCodec.encode(input.stagedEffects))
        put("previous",input.previous?.let { state -> buildJsonObject {
            put("owner",state.ownerUid);put("version",state.version);put("state",state.canonicalValue)
        } }?:JsonNull)
        put("deadlines",JsonArray(input.deadlines.sortedBy{it.uid}.map{buildJsonObject {
            put("uid",it.uid);put("owner",it.ownerUid);put("due",it.due.milliseconds)
        }}))
        put("actions",JsonArray(input.actions.sortedBy{it.action.uid}.map { interval -> buildJsonObject {
            val action=interval.action
            put("uid",action.uid);put("owner",action.ownerUid);put("start",interval.start.milliseconds);put("end",interval.end.milliseconds)
            put("duration",action.timing.duration.milliseconds);put("rule",action.timing.ruleUid);put("version",action.timing.ruleVersion)
            put("instantaneous",action.timing.instantaneous);put("during",action.during?.let(::JsonPrimitive)?:JsonNull)
            put("after",JsonArray(action.after.sorted().map(::JsonPrimitive)));put("slots",JsonArray(action.exclusiveSlots.sorted().map(::JsonPrimitive)))
        } }))
    }.toString())
}
internal sealed interface TemporalEvaluationResponse {
    data class Accepted(val requestFingerprint:String,val result:TemporalOwnerResult.Evaluated):TemporalEvaluationResponse
    data class Unavailable(val reasonUid:String):TemporalEvaluationResponse
}
/** Application orchestration only. Implementations may call AI without a canonical DB transaction. */
internal fun interface TemporalExternalEvaluationPort {
    fun evaluate(request:TemporalEvaluationRequest,cancelled:()->Boolean):TemporalEvaluationResponse
}
