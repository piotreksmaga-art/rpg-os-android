package com.rpgos.app

data class ContextSegment(val segmentUid:String,val sourceRequestUid:String,val priority:PlanStepPriority,val state:RetrievalState,val records:List<RetrievalRecord>,val complete:Boolean)
data class ContextCandidate(val campaignUid:String,val audience:AudienceContext,val purpose:PurposeContext,val intent:NormalizedIntent,val segments:List<ContextSegment>){init{require(campaignUid==audience.campaignUid&&campaignUid==purpose.campaignUid&&campaignUid==intent.campaignUid);require(segments.map{it.segmentUid}.distinct().size==segments.size)}}
class Phase45ContextBuilder(private val retriever:StructuredSqlRetriever){
    fun build(plan:TurnPlan):ContextCandidate{val segments=plan.steps.map{step->when(val result=retriever.retrieve(step.request)){is StructuredRetrievalResult.Value->ContextSegment(step.stepUid,step.request.requestUid,step.priority,result.state,result.records,result.complete);else->ContextSegment(step.stepUid,step.request.requestUid,step.priority,result.state,emptyList(),result is StructuredRetrievalResult.NoData)}};return ContextCandidate(plan.campaignUid,plan.audience,plan.purpose,plan.intent,segments)}
}

data class ContextModelProfile(val profileUid:String,val effectiveContextUnits:Int,val reservedOutputUnits:Int){init{require(profileUid.isNotBlank()&&effectiveContextUnits>0&&reservedOutputUnits>=0&&reservedOutputUnits<effectiveContextUnits)}}
fun interface ContextUnitEstimator{fun units(record:RetrievalRecord):Int}
data class BudgetedContext(val candidate:ContextCandidate,val includedSegments:List<ContextSegment>,val omittedSegmentUids:List<String>,val usedUnits:Int,val availableUnits:Int,val complete:Boolean)
class ContextBudgetManager(private val estimator:ContextUnitEstimator=ContextUnitEstimator{r->(r.recordUid.length+r.values.entries.sumOf{it.key.length+(it.value?.toString()?.length?:0)}+3)/4}){
    fun apply(candidate:ContextCandidate,profile:ContextModelProfile):BudgetedContext{val available=profile.effectiveContextUnits-profile.reservedOutputUnits;var used=0;val included=mutableListOf<ContextSegment>();val omitted=mutableListOf<String>();candidate.segments.sortedWith(compareBy<ContextSegment>{it.priority.ordinal}.thenBy{it.segmentUid}).forEach{segment->val cost=segment.records.sumOf{estimator.units(it).coerceAtLeast(1)};if(used+cost<=available){included+=segment;used+=cost}else omitted+=segment.segmentUid};return BudgetedContext(candidate,included,omitted,used,available,omitted.isEmpty()&&included.all{it.complete})}
}

data class MissingContext(val segmentUid:String,val requestUid:String,val reasonUid:String)
data class IterativeRetrievalPolicy(val maxIterations:Int=2,val maxFollowUpRequests:Int=8){init{require(maxIterations in 0..5&&maxFollowUpRequests in 0..32)}}
fun interface MissingContextStrategy{fun followUps(plan:TurnPlan,current:ContextCandidate,missing:List<MissingContext>):List<StructuredRetrievalRequest>}
data class IterativeContextResult(val budgeted:BudgetedContext,val iterations:Int,val missing:List<MissingContext>,val terminationUid:String)
class IterativeRetrievalPipeline(private val retriever:StructuredSqlRetriever,private val budgetManager:ContextBudgetManager,private val strategy:MissingContextStrategy,private val policy:IterativeRetrievalPolicy=IterativeRetrievalPolicy()){
    fun execute(plan:TurnPlan,profile:ContextModelProfile):IterativeContextResult{
        var candidate=Phase45ContextBuilder(retriever).build(plan);val fingerprints=plan.steps.map{it.request.fingerprint()}.toMutableSet();var iterations=0
        fun missing()=candidate.segments.filter{it.priority in setOf(PlanStepPriority.REQUIRED,PlanStepPriority.SAFETY)&&it.state!=RetrievalState.VALUE}.map{MissingContext(it.segmentUid,it.sourceRequestUid,it.state.name)}
        var absent=missing()
        while(absent.isNotEmpty()&&iterations<policy.maxIterations){val proposed=strategy.followUps(plan,candidate,absent).take(policy.maxFollowUpRequests).filter{request->require(request.campaignUid==plan.campaignUid&&request.audience==plan.audience&&request.purpose==plan.purpose){"RPGOS-P47:ENTITLEMENT_EXPANSION"};fingerprints.add(request.fingerprint())};if(proposed.isEmpty())break;proposed.forEachIndexed{i,request->val replacement=when(val result=retriever.retrieve(request)){is StructuredRetrievalResult.Value->ContextSegment("FOLLOWUP:${iterations+1}:$i",request.requestUid,PlanStepPriority.REQUIRED,result.state,result.records,result.complete);else->ContextSegment("FOLLOWUP:${iterations+1}:$i",request.requestUid,PlanStepPriority.REQUIRED,result.state,emptyList(),result is StructuredRetrievalResult.NoData)};candidate=candidate.copy(segments=candidate.segments.filterNot{it.sourceRequestUid==request.requestUid}+replacement)};iterations++;absent=missing()}
        val budgeted=budgetManager.apply(candidate,profile);absent=missing();val termination=when{absent.isEmpty()->"CONTEXT_COMPLETE";iterations>=policy.maxIterations->"ITERATION_LIMIT";else->"NO_LEGAL_FOLLOW_UP"};return IterativeContextResult(budgeted,iterations,absent,termination)
    }
}
