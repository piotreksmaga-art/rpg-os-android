package com.rpgos.app

enum class ContextEpistemicState { PROJECTED_FACT, PLAYER_ASSERTION, SYSTEM_CONSTRAINT, UNKNOWN }
enum class ContextOmissionCause { MODEL_BUDGET, PROVIDER_NO_DATA, PROVIDER_DENIED, NOT_DISCLOSED, UNKNOWN, UNSUPPORTED, CORRUPTION, INCOMPLETE_PAGE }
enum class ContextCompletionState { COMPLETE, NEEDS_INFORMATION, UNSAFE_FOR_AI, EXHAUSTED }

data class SemanticCoreCapsule(
    val campaignUid:String,
    val planUid:String,
    val intentHash:String,
    val intentCanonicalPayload:String,
    val planSemanticPayload:String,
    val activeNodeUids:List<String>,
    val capabilityUids:List<String>,
    val dependencyEdges:List<Pair<String,String>>,
    val hardDirectiveUids:List<String>
){init{
    require(campaignUid.isNotBlank()&&planUid.isNotBlank()&&intentHash.isNotBlank()&&intentCanonicalPayload.isNotBlank()&&planSemanticPayload.isNotBlank())
    require(activeNodeUids.distinct()==activeNodeUids&&capabilityUids.none{it.isBlank()})
}}

data class CanonicalContextRecord(
    val record:RetrievalRecord,
    val epistemicState:ContextEpistemicState,
    val projectionBoundaryUid:String,
    val sourceRequirementUid:String
){init{require(projectionBoundaryUid.isNotBlank()&&sourceRequirementUid.isNotBlank())}}

data class CanonicalContextSegment(
    val segmentUid:String,
    val requirement:PlannedRequirement,
    val state:RetrievalState,
    val records:List<CanonicalContextRecord>,
    val complete:Boolean,
    val continuation:RetrievalContinuation,
    val nextCursor:String?=null,
    val reasonUid:String?=null
){init{
    require(segmentUid.isNotBlank()&&records.map{it.record.recordUid}.distinct().size==records.size)
    require((state==RetrievalState.VALUE)==records.isNotEmpty())
    require((continuation==RetrievalContinuation.CURSOR)==(nextCursor!=null))
}}

data class CanonicalContextCandidate(
    val plan:CanonicalTurnPlan,
    val core:SemanticCoreCapsule,
    val segments:List<CanonicalContextSegment>
){init{
    require(core.campaignUid==plan.campaignUid&&core.planUid==plan.planUid)
    require(segments.map{it.requirement.requirementUid}.distinct().size==segments.size)
    val planned=plan.steps.flatMap{it.requirements}.map{it.requirementUid}.toSet()
    require(segments.all{it.requirement.requirementUid in planned}){"RPGOS-P45:UNPLANNED_CONTEXT"}
}}

/** The only Phase45 entry point; every read originates in a validated Phase44 envelope. */
class ContextIntegrityBuilder(private val retriever:StructuredSqlRetriever){
    fun build(plan:CanonicalTurnPlan):CanonicalContextCandidate{
        val requirements=plan.steps.flatMap{it.requirements}.sortedBy{it.requirementUid}
        val segments=requirements.map{requirement->read(requirement,requirement.request)}
        val core=SemanticCoreCapsule(
            campaignUid=plan.campaignUid,planUid=plan.planUid,intentHash=plan.intent.canonicalFingerprint(),
            intentCanonicalPayload=plan.intent.canonicalPayload(),planSemanticPayload=plan.steps.joinToString("|") { it.toString() },
            activeNodeUids=plan.steps.map{it.nodeUid},capabilityUids=plan.steps.mapNotNull{it.capabilityUid},
            dependencyEdges=plan.steps.flatMap{step->step.dependencyNodeUids.map{it to step.nodeUid}},
            hardDirectiveUids=(plan.intent.globalConstraints+plan.intent.nodes.flatMap{it.constraints}).filter{it.strength==DirectiveStrength.HARD}.map{it.directiveUid}.sorted()
        )
        return CanonicalContextCandidate(plan,core,segments)
    }

    internal fun read(requirement:PlannedRequirement,request:StructuredRetrievalRequest):CanonicalContextSegment{
        val validation=requirement.envelope.validate(request)
        require(validation==EnvelopeValidationResult.Allowed){"RPGOS-P45:ENVELOPE_REJECTED:$validation"}
        return when(val result=retriever.retrieve(request)){
            is StructuredRetrievalResult.Value->{
                val unsafe=result.records.firstOrNull{record->record.provenanceUid.isNullOrBlank()||record.values.values.any{!isSafeContextValue(it)}}
                if(unsafe!=null)emptySegment(requirement,RetrievalState.CORRUPTION,true,if(unsafe.provenanceUid.isNullOrBlank())"MISSING_PROJECTION_PROVENANCE" else "UNSAFE_CONTEXT_VALUE")
                else CanonicalContextSegment(
                    segmentUid="CTX:${requirement.requirementUid}",requirement=requirement,state=result.state,
                    records=result.records.map{CanonicalContextRecord(it,ContextEpistemicState.PROJECTED_FACT,it.provenanceUid!!,requirement.requirementUid)},
                    complete=result.complete,continuation=result.continuation,nextCursor=result.nextCursor,
                    reasonUid=if(result.complete)null else "INCOMPLETE_PAGE"
                )
            }
            StructuredRetrievalResult.NoData->emptySegment(requirement,result.state,true,"NO_DATA")
            is StructuredRetrievalResult.Denied->emptySegment(requirement,result.state,true,result.reasonUid)
            is StructuredRetrievalResult.NotDisclosed->emptySegment(requirement,result.state,true,result.reasonUid)
            is StructuredRetrievalResult.Unknown->emptySegment(requirement,result.state,true,result.reasonUid)
            is StructuredRetrievalResult.Unsupported->emptySegment(requirement,result.state,true,result.reasonUid)
            is StructuredRetrievalResult.Corruption->emptySegment(requirement,result.state,true,result.reasonUid)
        }
    }

    private fun emptySegment(requirement:PlannedRequirement,state:RetrievalState,complete:Boolean,reason:String)=CanonicalContextSegment(
        "CTX:${requirement.requirementUid}",requirement,state,emptyList(),complete,RetrievalContinuation.COMPLETE,reasonUid=reason
    )
    private fun isSafeContextValue(value:Any?):Boolean=when(value){
        null,is String,is Number,is Boolean->true
        is List<*>->value.all(::isSafeContextValue)
        is Map<*,*>->value.all{(key,item)->key is String&&isSafeContextValue(item)}
        else->false
    }
}

data class ContextRuntimeProfile(
    val profileUid:String,
    val effectiveContextUnits:Int,
    val protocolReserveUnits:Int,
    val systemReserveUnits:Int,
    val outputReserveUnits:Int,
    val safetyMarginUnits:Int=0
){init{
    require(profileUid.isNotBlank()&&effectiveContextUnits>0)
    require(listOf(protocolReserveUnits,systemReserveUnits,outputReserveUnits,safetyMarginUnits).all{it>=0})
    require(protocolReserveUnits+systemReserveUnits+outputReserveUnits+safetyMarginUnits<effectiveContextUnits)
}
    val payloadUnits:Int get()=effectiveContextUnits-protocolReserveUnits-systemReserveUnits-outputReserveUnits-safetyMarginUnits
}

fun interface CanonicalContextUnitEstimator{fun units(value:String):Int}

data class ContextOmission(
    val requirementUid:String,
    val importance:RequirementImportance,
    val cause:ContextOmissionCause,
    val reasonUid:String
)

data class BudgetedCanonicalContext(
    val candidate:CanonicalContextCandidate,
    val includedSegments:List<CanonicalContextSegment>,
    val omissions:List<ContextOmission>,
    val coreUnits:Int,
    val segmentUnits:Int,
    val payloadCapacityUnits:Int,
    val finalSerializedUnits:Int,
    val safeForAi:Boolean,
    val reasonUids:List<String>
){
    val usedPayloadUnits:Int get()=coreUnits+segmentUnits
    fun canonicalPayload():String=buildString{
        append("core=").append(candidate.core)
        includedSegments.sortedBy{it.requirement.requirementUid}.forEach{append("|segment=").append(it)}
        omissions.sortedBy{it.requirementUid}.forEach{append("|omission=").append(it)}
    }
}

/** Semantic budgeting retains the core and every REQUIRED/SAFETY segment or refuses AI consumption. */
class SemanticContextBudgetManager(
    private val estimator:CanonicalContextUnitEstimator=CanonicalContextUnitEstimator{(it.length+3)/4}
){
    fun apply(candidate:CanonicalContextCandidate,profile:ContextRuntimeProfile):BudgetedCanonicalContext{
        val coreUnits=estimator.units(candidate.core.toString()).coerceAtLeast(1)
        val mandatory=candidate.segments.filter{it.requirement.importance in setOf(RequirementImportance.REQUIRED,RequirementImportance.SAFETY)}
        val elective=candidate.segments.filterNot{it in mandatory}.sortedWith(compareBy<CanonicalContextSegment>{it.requirement.importance.ordinal}.thenBy{it.requirement.requirementUid})
        val included=mandatory.toMutableList()
        var segmentUnits=mandatory.sumOf(::segmentUnits)
        val omissions=candidate.segments.mapNotNull(::sourceOmission).toMutableList()
        elective.forEach{segment->
            val cost=segmentUnits(segment)
            if(coreUnits+segmentUnits+cost<=profile.payloadUnits){included+=segment;segmentUnits+=cost}
            else omissions+=ContextOmission(segment.requirement.requirementUid,segment.requirement.importance,ContextOmissionCause.MODEL_BUDGET,"SEGMENT_EXCEEDS_PAYLOAD_BUDGET")
        }
        val hardMissing=mandatory.filter{it.state!=RetrievalState.VALUE||!it.complete}
        val provisionalPayload=serialize(candidate.core,included,omissions)
        val finalUnits=estimator.units(provisionalPayload).coerceAtLeast(1)
        val reasons=linkedSetOf<String>()
        if(coreUnits>profile.payloadUnits)reasons+="SEMANTIC_CORE_EXCEEDS_BUDGET"
        if(coreUnits+segmentUnits>profile.payloadUnits)reasons+="MANDATORY_CONTEXT_EXCEEDS_BUDGET"
        if(finalUnits>profile.payloadUnits)reasons+="FINAL_SERIALIZED_PAYLOAD_EXCEEDS_BUDGET"
        if(hardMissing.isNotEmpty())reasons+="MANDATORY_CONTEXT_INCOMPLETE"
        val safe=reasons.isEmpty()
        return BudgetedCanonicalContext(candidate,included.sortedBy{it.requirement.requirementUid},omissions.distinctBy{it.requirementUid to it.cause},coreUnits,segmentUnits,profile.payloadUnits,finalUnits,safe,reasons.toList())
    }

    private fun segmentUnits(segment:CanonicalContextSegment)=estimator.units(segment.toString()).coerceAtLeast(1)
    private fun serialize(core:SemanticCoreCapsule,segments:List<CanonicalContextSegment>,omissions:List<ContextOmission>)=buildString{
        append("core=").append(core);segments.sortedBy{it.requirement.requirementUid}.forEach{append("|segment=").append(it)}
        omissions.sortedBy{it.requirementUid}.forEach{append("|omission=").append(it)}
    }
    private fun sourceOmission(segment:CanonicalContextSegment):ContextOmission?{
        val cause=when{
            segment.state==RetrievalState.VALUE&&!segment.complete->ContextOmissionCause.INCOMPLETE_PAGE
            segment.state==RetrievalState.NO_DATA->ContextOmissionCause.PROVIDER_NO_DATA
            segment.state==RetrievalState.DENIED->ContextOmissionCause.PROVIDER_DENIED
            segment.state==RetrievalState.NOT_DISCLOSED->ContextOmissionCause.NOT_DISCLOSED
            segment.state==RetrievalState.UNKNOWN->ContextOmissionCause.UNKNOWN
            segment.state==RetrievalState.UNSUPPORTED->ContextOmissionCause.UNSUPPORTED
            segment.state==RetrievalState.CORRUPTION->ContextOmissionCause.CORRUPTION
            else->null
        }?:return null
        return ContextOmission(segment.requirement.requirementUid,segment.requirement.importance,cause,segment.reasonUid?:cause.name)
    }
}

data class TypedContextGap(
    val requirementUid:String,
    val ownerNodeUid:String,
    val importance:RequirementImportance,
    val cause:ContextOmissionCause,
    val retryable:Boolean,
    val continuationCursor:String?=null
)

data class CanonicalRetrievalPolicy(
    val maxIterations:Int=3,
    val maxFollowUpsPerIteration:Int=8,
    val maxTotalFollowUps:Int=16,
    val maxTotalRecords:Int=400,
    val maxTotalPayloadUnits:Int=64_000
){init{
    require(maxIterations in 0..8&&maxFollowUpsPerIteration in 0..32&&maxTotalFollowUps in 0..128&&maxTotalRecords in 1..2_000&&maxTotalPayloadUnits in 1..1_000_000)
}}

fun interface TypedContextCompletionStrategy{
    fun followUps(plan:CanonicalTurnPlan,current:CanonicalContextCandidate,gaps:List<TypedContextGap>):List<StructuredRetrievalRequest>
}

data class ContextRetrievalAttempt(
    val iteration:Int,
    val requestUid:String,
    val requestFingerprint:String,
    val accepted:Boolean,
    val reasonUid:String
)

data class CanonicalIterativeContextResult(
    val state:ContextCompletionState,
    val budgeted:BudgetedCanonicalContext,
    val iterations:Int,
    val gaps:List<TypedContextGap>,
    val attempts:List<ContextRetrievalAttempt>,
    val terminationUid:String
)

/** Bounded Phase47 completion. Follow-ups may narrow or page an envelope; they cannot widen it. */
class CanonicalIterativeRetrievalPipeline(
    private val retriever:StructuredSqlRetriever,
    private val budgetManager:SemanticContextBudgetManager,
    private val strategy:TypedContextCompletionStrategy,
    private val policy:CanonicalRetrievalPolicy=CanonicalRetrievalPolicy()
){
    fun execute(plan:CanonicalTurnPlan,profile:ContextRuntimeProfile):CanonicalIterativeContextResult{
        val builder=ContextIntegrityBuilder(retriever)
        var candidate=builder.build(plan)
        var budgeted=enforceAggregate(budgetManager.apply(candidate,profile),candidate)
        val attempts=mutableListOf<ContextRetrievalAttempt>()
        val fingerprints=plan.steps.flatMap{it.requirements}.map{it.request.fingerprint()}.toMutableSet()
        var iteration=0;var totalFollowUps=0
        var gaps=gaps(candidate)
        while(gaps.any{it.retryable}&&!budgeted.safeForAi&&iteration<policy.maxIterations&&totalFollowUps<policy.maxTotalFollowUps){
            val proposed=strategy.followUps(plan,candidate,gaps).take(minOf(policy.maxFollowUpsPerIteration,policy.maxTotalFollowUps-totalFollowUps))
            val accepted=mutableListOf<Pair<PlannedRequirement,StructuredRetrievalRequest>>()
            proposed.forEach{request->
                val requirement=plan.steps.flatMap{it.requirements}.singleOrNull{it.requirementUid==request.requestUid||it.request.requestUid==request.requestUid}
                val validation=requirement?.envelope?.validate(request)
                val fresh=fingerprints.add(request.fingerprint())
                val reason=when{requirement==null->"REQUEST_OUTSIDE_TURN_PLAN";validation !is EnvelopeValidationResult.Allowed->"ENVELOPE_REJECTED";!fresh->"NO_PROGRESS_DUPLICATE";else->"ACCEPTED"}
                attempts+=ContextRetrievalAttempt(iteration+1,request.requestUid,request.fingerprint(),reason=="ACCEPTED",reason)
                if(reason=="ACCEPTED")accepted+=requirement!! to request
            }
            if(accepted.isEmpty())break
            accepted.take(policy.maxTotalFollowUps-totalFollowUps).forEach{(requirement,request)->
                val replacement=builder.read(requirement,request)
                val prior=candidate.segments.single{it.requirement.requirementUid==requirement.requirementUid}
                val combined=if(request.cursor!=null&&replacement.state==RetrievalState.VALUE&&prior.state==RetrievalState.VALUE)replacement.copy(
                    records=(prior.records+replacement.records).distinctBy{it.record.recordUid}
                ) else replacement
                candidate=candidate.copy(segments=candidate.segments.filterNot{it.requirement.requirementUid==requirement.requirementUid}+combined)
                totalFollowUps++
            }
            iteration++
            if(candidate.segments.sumOf{it.records.size}>policy.maxTotalRecords)break
            budgeted=enforceAggregate(budgetManager.apply(candidate,profile),candidate)
            if(budgeted.finalSerializedUnits>policy.maxTotalPayloadUnits)break
            gaps=gaps(candidate)
        }
        budgeted=enforceAggregate(budgetManager.apply(candidate,profile),candidate);gaps=gaps(candidate)
        val state=when{budgeted.safeForAi->ContextCompletionState.COMPLETE;gaps.any{it.retryable}->ContextCompletionState.EXHAUSTED;else->ContextCompletionState.UNSAFE_FOR_AI}
        val termination=when{
            budgeted.safeForAi->"CONTEXT_SAFE_FOR_AI"
            candidate.segments.sumOf{it.records.size}>policy.maxTotalRecords->"TOTAL_RECORD_LIMIT"
            budgeted.finalSerializedUnits>policy.maxTotalPayloadUnits->"TOTAL_PAYLOAD_LIMIT"
            iteration>=policy.maxIterations->"ITERATION_LIMIT"
            totalFollowUps>=policy.maxTotalFollowUps->"FOLLOW_UP_LIMIT"
            attempts.any{!it.accepted}->"NO_LEGAL_PROGRESS"
            else->"NON_RETRYABLE_CONTEXT_GAP"
        }
        return CanonicalIterativeContextResult(state,budgeted,iteration,gaps,attempts,termination)
    }

    private fun enforceAggregate(budgeted:BudgetedCanonicalContext,candidate:CanonicalContextCandidate):BudgetedCanonicalContext{
        val reasons=budgeted.reasonUids.toMutableList()
        if(candidate.segments.sumOf{it.records.size}>policy.maxTotalRecords)reasons+="TOTAL_RECORD_LIMIT"
        if(budgeted.finalSerializedUnits>policy.maxTotalPayloadUnits)reasons+="TOTAL_PAYLOAD_LIMIT"
        return if(reasons==budgeted.reasonUids)budgeted else budgeted.copy(safeForAi=false,reasonUids=reasons.distinct())
    }

    private fun gaps(candidate:CanonicalContextCandidate)=candidate.segments.mapNotNull{segment->
        val cause=when{
            segment.state==RetrievalState.VALUE&&!segment.complete->ContextOmissionCause.INCOMPLETE_PAGE
            segment.state==RetrievalState.NO_DATA->ContextOmissionCause.PROVIDER_NO_DATA
            segment.state==RetrievalState.DENIED->ContextOmissionCause.PROVIDER_DENIED
            segment.state==RetrievalState.NOT_DISCLOSED->ContextOmissionCause.NOT_DISCLOSED
            segment.state==RetrievalState.UNKNOWN->ContextOmissionCause.UNKNOWN
            segment.state==RetrievalState.UNSUPPORTED->ContextOmissionCause.UNSUPPORTED
            segment.state==RetrievalState.CORRUPTION->ContextOmissionCause.CORRUPTION
            else->null
        }?:return@mapNotNull null
        val retryable=cause in setOf(ContextOmissionCause.INCOMPLETE_PAGE,ContextOmissionCause.UNKNOWN)&&segment.requirement.importance in setOf(RequirementImportance.REQUIRED,RequirementImportance.SAFETY)
        TypedContextGap(segment.requirement.requirementUid,segment.requirement.ownerNodeUid,segment.requirement.importance,cause,retryable,segment.nextCursor)
    }.sortedBy{it.requirementUid}
}
