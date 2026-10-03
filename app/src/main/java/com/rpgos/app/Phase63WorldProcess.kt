package com.rpgos.app

import kotlinx.serialization.json.*

/** A required refinement is owned by Phase50 and scheduled through the existing clock.
 * There is no population sweep, autonomous PC action or second NPC decision engine here. */
internal class Phase63WorldProcessOwner(
    private val scope:TemporalScope,
    private val rootVersion:Long,
    private val jobs:List<MechanicalActorExpansion>,
    private val currentScope:()->TemporalScope = {scope},
    private val yieldWork:()->Unit = {Thread.yield()}
):WorldProcessOwnerPort {
    override val ownerUid=OWNER
    init {
        require(rootVersion>=0 && jobs.size<=256 && jobs.map { it.actor }.distinct().size==jobs.size)
    }
    private val orderedJobs=jobs.sortedWith(compareBy<MechanicalActorExpansion>{it.actor.kindUid}.thenBy{it.actor.uid})
    private val specification=phase63Hash(buildJsonArray { orderedJobs.forEach { add(Phase50ActorExpansion.encode(it)) } }.toString())
    override fun evaluate(input:TemporalOwnerInput):TemporalOwnerResult {
        if(input.scope!=scope)return TemporalOwnerResult.Unsupported("P63:STALE_PROCESS_SCOPE")
        if(input.deadlines.isNotEmpty())return TemporalOwnerResult.Unsupported("P63:PROCESS_RULE_REQUIRED")
        if(input.actions.isNotEmpty())return TemporalOwnerResult.Unsupported("P63:PROCESS_ACTION_OWNER_REQUIRED")
        if(input.previous?.version!=null && input.previous.version!=1)return TemporalOwnerResult.Unsupported("P63:PROCESS_STATE_VERSION")
        val already=completed(input.previous)
        if(!already && jobs.size>32)return TemporalOwnerResult.EvaluationRequired("P63:REFINEMENT_WORK_REQUIRED")
        val changes=if(already || jobs.isEmpty())emptyList() else listOf(batch(orderedJobs,0))
        return result(changes)
    }
    private fun completed(state:TemporalOwnerState?):Boolean {
        val previous=state?.let {
            require(state.version==1) { "P63:PROCESS_STATE_VERSION" }
            Json.parseToJsonElement(state.canonicalValue).jsonObject.also { Phase63WorldCodec.keys(it,"specification","complete") }
        }
        // A completed previous turn can be superseded by a new authorized interaction. The
        // same speculative execution must never apply its expansion twice.
        return previous?.let { Phase63WorldCodec.text(it,"specification")==specification && it.getValue("complete").jsonPrimitive.boolean }==true
    }
    private fun batch(values:List<MechanicalActorExpansion>,index:Int)=WorldSimulationChange(scope.campaignUid,
        HistoryGenerationUid(scope.historyGenerationUid),Math.addExact(rootVersion,index.toLong()),null,actorExpansions=values)
    private fun result(changes:List<WorldSimulationChange>):TemporalOwnerResult.Evaluated {
        return TemporalOwnerResult.Evaluated(TemporalOwnerState(OWNER,1,buildJsonObject {
            put("specification",specification);put("complete",true)
        }.toString()),changes)
    }
    fun extension()=TemporalProcessExtension(listOf(RegisteredTemporalOwner("1:$specification",this)),TemporalExternalEvaluationPort { request,cancelled->
        if(request.ownerUid!=OWNER || request.input.scope!=scope || request.input.deadlines.isNotEmpty() || request.input.actions.isNotEmpty() || jobs.size<=32 || completed(request.input.previous))
            return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P63:REFINEMENT_CORRELATION")
        // The existing Phase60 suspension/checkpoint owns this work. No model or world-time
        // deadline is involved. Restart recomputes this pure prefix; only the final turn commits.
        val changes=mutableListOf<WorldSimulationChange>()
        for((index,values) in orderedJobs.chunked(32).withIndex()) {
            if(cancelled())return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P60:CANCELLED")
            if(currentScope()!=scope)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P63:STALE_PROCESS_SCOPE")
            changes+=batch(values,index)
            yieldWork()
        }
        if(cancelled())return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P60:CANCELLED")
        if(currentScope()!=scope)return@TemporalExternalEvaluationPort TemporalEvaluationResponse.Unavailable("P63:STALE_PROCESS_SCOPE")
        TemporalEvaluationResponse.Accepted(request.fingerprint,result(changes))
    })
    companion object { const val OWNER="RPGOS-P63:WORLD_REFINEMENT" }
}

/** Only pure, captured-scope refinement batches may follow a foreground materialization.
 * Preserve every typed batch/transfer identity; never turn world changes into scalar text. */
internal fun phase63PreparedWorldChain(foreground:List<WorldSimulationChange>,refinements:List<WorldSimulationChange>):List<WorldSimulationChange> {
    requireWorldSimulationChain(foreground)
    requireWorldSimulationChain(refinements)
    if(foreground.isEmpty())return refinements
    if(refinements.isEmpty())return foreground
    val first=foreground.first()
    require(refinements.all { it.campaignUid==first.campaignUid && it.historyGenerationUid==first.historyGenerationUid &&
        it.skeleton==null && it.edges.isEmpty() && it.populationManifests.isEmpty() && it.populationExtractions.isEmpty() && it.actorExpansions.isNotEmpty() }) { "P63:UNREGISTERED_REFINEMENT_BATCH" }
    require(refinements.first().expectedVersion==first.expectedVersion) { "P63:REFINEMENT_CAPTURED_VERSION_MISMATCH" }
    return foreground+refinements.mapIndexed { index,change->change.copy(expectedVersion=Math.addExact(first.expectedVersion,foreground.size.toLong()+index)) }
}
