package com.rpgos.app

/** Shared with production and regressions: a 2k model has only 1152 payload units after reserves. */
internal object NpcContextProfiles {
    val MOBILE=ContextRuntimeProfile("ANDROID-NPC",2048,128,512,128,128)
}

/** Reads keep Phase37/38 ownership. Neither the decision model nor semantic ranker gets SQL. */
internal interface NpcProjectionReadPort {
    fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef):ProtectedReadResult<NpcBrainState>
    fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int):ProtectedReadResult<List<NpcKnownRecord>>
    fun currentRoles(audience:AudienceContext,purpose:PurposeContext):Set<String> = emptySet()
    fun historical(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,generation:HistoryGenerationUid,order:Long):ProtectedReadResult<List<NpcKnownRecord>> = ProtectedReadResult.NoData
}
internal class RepositoryNpcProjection(private val reads:ProtectedCampaignReadRepository):NpcProjectionReadPort {
    override fun brain(audience:AudienceContext,purpose:PurposeContext,actor:DomainRef,holder:KnowledgeHolderRef)=reads.npcBrain(audience,purpose,actor,holder)
    override fun knowledge(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,order:Long,limit:Int)=reads.npcKnowledge(audience,purpose,holder,order,limit)
    override fun currentRoles(audience:AudienceContext,purpose:PurposeContext)=reads.trustedPrincipal(audience)?.roleUids.orEmpty()
    override fun historical(audience:AudienceContext,purpose:PurposeContext,holder:KnowledgeHolderRef,generation:HistoryGenerationUid,order:Long)=
        reads.npcHistoricalMemory(audience,purpose,holder,generation,order)
}
internal sealed interface NpcContextResult {
    data class Ready(val context:NpcDecisionContextEnvelope,val budget:BudgetedCanonicalContext):NpcContextResult {
        /** Rebuildable Phase55 materialization: no extra canonical table and no knowledge copy. */
        val workingMemory:WorkingMemorySnapshot=WorkingMemoryOwner.materialize(WorkingMemoryScope(
            context.scope.temporal.campaignUid,HistoryGenerationUid(context.scope.temporal.historyGenerationUid),
            AudienceKinds.WORLD_ACTOR,context.scope.actor.uid,VisibilityPurposeKinds.WORLD_ACTOR_REASONING,null,
            context.scope.temporal.baseCommitOrder,1),budget)
    }
    data class Unavailable(val reasonUid:String):NpcContextResult
}

/** A real Phase41 -> Phase44 envelope -> Phase45 budget path, scoped to one canonical holder.
 * The affordance factory sees only this actor's projection, never a hidden-world snapshot. */
internal class NpcDecisionContextProjector(private val reads:NpcProjectionReadPort,private val recall:NpcRecallPort=NpcRecallPort.NONE) {
    fun project(scope:NpcDecisionScope,trigger:NpcTrigger,holder:KnowledgeHolderRef,profile:ContextRuntimeProfile,
                affordances:(NpcBrainState,List<NpcKnownRecord>)->List<NpcActionOption>):NpcContextResult {
        fun unavailable(reason:String)=NpcContextResult.Unavailable("P62:$reason")
        if(holder.campaignUid!=scope.temporal.campaignUid || holder.holderUid!=scope.actor.uid)return unavailable("HOLDER_MISMATCH")
        val audience=AudienceContext(scope.temporal.campaignUid,AudienceKinds.WORLD_ACTOR,
            VisibilityPrincipalRef(scope.actor.kindUid,scope.actor.uid))
        val purpose=PurposeContext(scope.temporal.campaignUid,VisibilityPurposeKinds.WORLD_ACTOR_REASONING)
        val brainRead=reads.brain(audience,purpose,scope.actor,holder)
        val brain=(brainRead as? ProtectedReadResult.Allow)?.value ?: return unavailable("BRAIN_${brainRead.stateUid}")
        if(brain.revision!=scope.brainRevision || brain.knowledgeHolder!=holder)return unavailable("STALE_BRAIN")
        val currentRoles=reads.currentRoles(audience,purpose)
        val knownRead=reads.knowledge(audience,purpose,holder,scope.temporal.baseCommitOrder,64)
        val current=when(knownRead) {
            is ProtectedReadResult.Allow -> knownRead.value
            ProtectedReadResult.NoData -> emptyList()
            else -> return unavailable("KNOWLEDGE_${knownRead.stateUid}")
        }
        if(current.size>64 || current.map{it.uid}.distinct().size!=current.size)return unavailable("INVALID_PROJECTION")
        val historical=runCatching{reads.historical(audience,purpose,holder,HistoryGenerationUid(scope.temporal.historyGenerationUid),scope.temporal.baseCommitOrder)}.getOrNull()
        val recalled=(historical as? ProtectedReadResult.Allow)?.value.orEmpty().take(16)
        // Exact current trigger/goal sources remain first. Cache failure never removes these.
        val exactCauses=brain.goals.filter{it.lifecycle==NpcGoalLifecycle.ACTIVE}.map{it.cause.uid}.toSet()+trigger.cause.uid
        val records=npcContextMemoryRecords(current,recalled,exactCauses)
        // A committed event UID is not perception. The acquisition must belong to this holder.
        val causeVisible=when(trigger.cause.kind) {
            NpcCauseKind.KNOWLEDGE_ACQUISITION -> records.any{it.acquisitionUid==trigger.cause.uid}
            NpcCauseKind.ACCEPTED_ACTION -> brain.plans.any{it.cause==trigger.cause && it.nextEvaluationAt==trigger.atTime}
            NpcCauseKind.INTRINSIC_MOTIVATION -> trigger.kind==NpcTriggerKind.SELF_REFLECTION && brain.motivations.any{it.uid==trigger.cause.uid}
            else -> false
        }
        if(!causeVisible)return unavailable("TRIGGER_NOT_PERCEIVED")
        // Candidate enumeration is not REQUIRED world context. Bound the choice set BEFORE
        // building envelopes instead of letting eight large options disable a 2k provider.
        // A pending exact action is first; otherwise round-robin across capabilities.
        val offered=affordances(brain,records)
        val pending=brain.plans.filter{it.lifecycle in setOf(NpcPlanLifecycle.RUNNING,NpcPlanLifecycle.WAITING)}.map{it.actionUid}.toSet()
        val alternatives=brain.plans.filter{it.lifecycle in setOf(NpcPlanLifecycle.RUNNING,NpcPlanLifecycle.WAITING,NpcPlanLifecycle.INTERRUPTED)}.mapNotNull{it.onUnavailableOptionUid}.toSet()
        val continuations=brain.plans.filter{it.lifecycle==NpcPlanLifecycle.COMPLETED && it.nextActionUids.isNotEmpty()}
                .maxWithOrNull(compareBy<NpcPlan>{it.startedAt}.thenBy{it.uid})?.nextActionUids.orEmpty()
        val groups=offered.groupBy{it.capabilityUid}.toSortedMap()
        val diverse=(0 until (groups.values.maxOfOrNull{it.size}?:0)).flatMap{index->groups.values.mapNotNull{it.getOrNull(index)}}
        var options=diverse.sortedBy{when{it.uid in pending->0;it.uid in alternatives->1;it.uid in continuations->2;else->3}}
        while(options.size>1) {
            val needed=options.flatMap{it.supportingRecordUids}.toSet()+records.filter{it.acquisitionUid==trigger.cause.uid}.map{it.uid}
            val probe=NpcDecisionContextEnvelope(scope,trigger,brain,records.filter{it.uid in needed},options,profile.payloadUnits,"0".repeat(64),currentRoles)
            if((NpcDecisionCodec.wireRequest("R".repeat(160),probe).length.toLong()+3)/4<=profile.payloadUnits)break
            options=options.dropLast(1)
        }
        val mandatory=options.flatMap{it.supportingRecordUids}.toSet()+records.filter{it.acquisitionUid==trigger.cause.uid}.map{it.uid}
        if(!records.map{it.uid}.containsAll(mandatory))return unavailable("UNGROUNDED_OPTION")
        val query=(records.filter{it.acquisitionUid==trigger.cause.uid}.joinToString("\n"){it.projectedText}+
            "\n"+brain.goals.filter{it.lifecycle==NpcGoalLifecycle.ACTIVE}.joinToString("\n"){it.objective})
            .trim().take(1024).ifBlank{"${trigger.kind}:${trigger.cause.uid}"}
        val recallRequest=NpcRecallRequest(scope,holder,query,records)
        val ranking=try{recall.rank(recallRequest)}catch(_:Exception){NpcRecallResult.Fallback("P62:RECALL_FAILED")}
        val ranked=if(ranking is NpcRecallResult.Ranked && ranking.requestFingerprint==recallRequest.fingerprint)
            ranking.hits.filter{hit->records.any{it.uid==hit.uid && npcRecallFingerprint(it)==hit.sourceFingerprint}}
                .sortedWith(compareByDescending<NpcRecallHit>{it.score.value}.thenBy{it.uid}).map{it.uid}.distinct()
        else emptyList()
        // A score affects only elective ordering. REQUIRED/SAFETY still go first in Phase45.
        val ordered=ranked+records.map{it.uid}.filterNot{it in ranked}
        val rank=ordered.withIndex().associate{it.value to it.index}
        val providerUid="P62:HOLDER_CONTEXT"
        val brainUid="P62:BRAIN"
        val projected=mapOf(brainUid to RetrievalRecord(brainUid,mapOf("epistemic_state_uid" to "SYSTEM_CONSTRAINT",
            "text" to NpcDecisionCodec.presentationBrain(brain,scope.atTime,options.mapNotNull{it.goalUid}.toSet(),currentRoles,
                if(trigger.cause.kind==NpcCauseKind.INTRINSIC_MOTIVATION)setOf(trigger.cause.uid) else emptySet(),options.mapNotNull{it.target}.toSet()).toString()),"P38:SELF_BRAIN"))+
            records.associate{r->r.uid to RetrievalRecord(r.uid,mapOf("epistemic_state_uid" to
                if(r.memoryKind==NpcMemoryRecordKind.HISTORICAL_ACQUISITION)"MEMORY" else "HOLDER_BELIEF",
                "knowledge_state" to r.epistemicState.name,"text" to r.projectedText,"version" to r.sourceVersion,
                "acquisition" to r.acquisitionUid),"P37:${r.acquisitionUid}")}
        val bindings=listOf(StructuredProviderBinding(providerUid,setOf("EXACT"),StructuredQueryProvider { req ->
            val uid=req.filters["uid"]
            if(req.campaignUid!=scope.temporal.campaignUid || req.audience!=audience || req.purpose!=purpose ||
                req.atOrder!=scope.temporal.baseCommitOrder || req.filters.keys!=setOf("uid","holder") ||
                req.filters["holder"]!=holder.holderUid) StructuredRetrievalResult.Denied("P62:PROJECTION_SCOPE")
            else projected[uid]?.let{StructuredRetrievalResult.Value(listOf(it),true)} ?: StructuredRetrievalResult.NoData
        }))
        val requirements=projected.keys.sorted().map { uid ->
            val filters=mapOf("uid" to uid,"holder" to holder.holderUid)
            val request=StructuredRetrievalRequest("P62:READ:$uid",scope.temporal.campaignUid,providerUid,"EXACT",filters,1,
                audience,purpose,scope.temporal.baseCommitOrder)
            PlannedRequirement("P62:REQ:${(rank[uid]?:0).toString().padStart(3,'0')}:$uid","P62:DECIDE",if(uid==brainUid || uid in mandatory)RequirementImportance.REQUIRED else RequirementImportance.QUALITY,
                request,CapabilityEnvelope("P62:ENV:$uid",scope.temporal.campaignUid,providerUid,"EXACT",filters.keys,filters,1,
                    audience,purpose,scope.temporal.baseCommitOrder))
        }
        val intent=IntentDocument(campaignUid=scope.temporal.campaignUid,actor=CommandActorRef(scope.actor.kindUid,scope.actor.uid),
            rawInput="NPC_DECISION",meaningState=MeaningState.UNDERSTOOD,
            nodes=listOf(IntentNode("P62:DECIDE",IntentForm.QUERY,SemanticAction("P62:DECIDE",rawPhrase="NPC_DECISION"))),
            provenance=IntentInterpretationProvenance(IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,"P62:CORE","1",phase60Hash(trigger.uid)))
        val plan=CanonicalTurnPlan(planUid="P62:PLAN:${trigger.uid}",campaignUid=scope.temporal.campaignUid,intent=intent,
            audience=audience,purpose=purpose,atOrder=scope.temporal.baseCommitOrder,
            steps=listOf(CanonicalTurnPlanStep("P62:STEP","P62:DECIDE","P62:DECIDE",CapabilityMatchState.EXACT,emptyList(),requirements,
                CapabilityExecutionKind.READ_CONTEXT,CapabilitySideEffectClass.NONE)))
        val candidate=ContextIntegrityBuilder(StructuredSqlRetriever(TrustedStructuredProviderRegistry.fromCore(bindings))).build(plan)
        val budget=SemanticContextBudgetManager().apply(candidate,profile)
        if(!budget.safeForAi)return unavailable("CONTEXT_BUDGET_OR_INTEGRITY")
        val included=budget.includedSegments.flatMap{it.records}.map{it.record.recordUid}.toSet()
        var selected=records.filter{it.uid in included}.sortedBy{rank[it.uid]}
        while(true) {
            val context=NpcDecisionContextEnvelope(scope,trigger,brain,selected,options,profile.payloadUnits,phase60Hash(budget.canonicalPayload()),currentRoles)
            // Reserve the maximum request identifier as well, rather than testing a shorter dummy.
            if((NpcDecisionCodec.wireRequest("R".repeat(160),context).length.toLong()+3)/4<=profile.payloadUnits)
                return NpcContextResult.Ready(context,budget)
            val drop=selected.lastOrNull{it.uid !in mandatory} ?: return unavailable("MANDATORY_INPUT_EXCEEDS_BUDGET")
            selected=selected.filterNot{it.uid==drop.uid}
        }
    }
}

/** No DB handle survives into a model invocation. Scope is revalidated after it returns. */
internal class NpcDecisionApplication(private val route:AiModelRoutePort,private val scope:()->NpcDecisionScope,
                                      private val engine:NpcDecisionEngine=NpcDecisionEngine(),private val progress:NpcWorkProgressPort=NpcWorkProgressPort.NONE) {
    private fun currentScopeOrNull():NpcDecisionScope?=try{scope()}catch(_:Exception){null}
    fun decide(request:NpcDecisionRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):NpcDecisionResult {
        if(cancellation.isCancelled())return NpcDecisionResult.Unavailable("P62:CANCELLED")
        if(currentScopeOrNull()!=request.context.scope)return NpcDecisionResult.Unavailable("P62:STALE_SCOPE")
        val payload=try{NpcDecisionCodec.encodeRequest(request.requestUid,request.context)}
            catch(_:IllegalArgumentException){return NpcDecisionResult.Unavailable("P62:INPUT_REJECTED")}
        val routing=route.route(AiRole.GAME_MASTER,AiWorkload.NPC_DECISION,(payload.length+3)/4+512)
        if(routing is AiRouteResult.Unavailable)return NpcDecisionResult.Unavailable("P62:PROVIDER_UNAVAILABLE:${routing.reasonUids.joinToString(",")}")
        val provider=(routing as AiRouteResult.Selected).provider
        val response=try{progress.observe(request.context.scope.temporal.campaignUid,AiWorkload.NPC_DECISION){provider.decideNpc(request,cancellation)}}
            catch(_:java.util.concurrent.CancellationException){return NpcDecisionResult.Unavailable("P62:CANCELLED")}
            catch(_:Exception){return NpcDecisionResult.Unavailable("P62:PROVIDER_FAILED")}
        if(cancellation.isCancelled())return NpcDecisionResult.Unavailable("P62:CANCELLED")
        return when(response) {
            is AiProviderResult.Success -> {
                val current=currentScopeOrNull() ?: return NpcDecisionResult.Unavailable("P62:STALE_SCOPE")
                engine.select(request,response.value,current,cancellation)
            }
            is AiProviderResult.Failure -> NpcDecisionResult.Unavailable("P62:AI:${response.reasonUid}")
        }
    }
}
