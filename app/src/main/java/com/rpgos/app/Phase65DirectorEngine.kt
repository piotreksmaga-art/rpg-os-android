package com.rpgos.app

import java.security.MessageDigest

const val DIRECTOR_BUNDLE_SCHEMA_VERSION=1
enum class DirectorTriggerKind { CADENCE, SEMANTIC_EVENT, PACING_PRESSURE, ANTI_REPETITION }
enum class DirectorCandidateKind { STORY_ARC, QUEST_SEED, WORLD_EVENT, NPC_AGENDA, FACTION_DEVELOPMENT, COMPLICATION, FORESHADOWING, PACING_HINT, ANTI_REPETITION }
enum class DirectorJobState { RESERVED, RUNNING, ACCEPTED, REJECTED, CANCELLED, FAILED }

data class DirectorTrigger(
    val triggerUid:String,val campaignUid:String,val kind:DirectorTriggerKind,val atCommittedOrder:Long,val semanticEvidenceUids:List<String> = emptyList()
){init{require(triggerUid.isNotBlank()&&campaignUid.isNotBlank()&&atCommittedOrder>=0&&semanticEvidenceUids.none{it.isBlank()})}}

data class DirectorContextEnvelope(
    val campaignUid:String,val contextVersion:String,val asOfCommittedOrder:Long,val projectedRecordUids:Set<String>,
    val strategicSummarySegments:List<String>,val hiddenDisclosureTokens:Set<String>,val phase38ProjectionUid:String
){init{
    require(campaignUid.isNotBlank()&&contextVersion.isNotBlank()&&asOfCommittedOrder>=0&&phase38ProjectionUid.isNotBlank())
    require(projectedRecordUids.none{it.isBlank()}&&strategicSummarySegments.none{it.isBlank()}&&hiddenDisclosureTokens.none{it.isBlank()})
}}

data class DirectorCandidate(
    val candidateUid:String,val kind:DirectorCandidateKind,val title:String,val summary:String,
    val supportingProjectedRecordUids:List<String>,val horizonUid:String,val pacingTags:Set<String>,
    val proposedOwnerPhaseUid:String,val directMutationPayload:String?=null
){init{
    require(candidateUid.isNotBlank()&&title.isNotBlank()&&summary.isNotBlank()&&horizonUid.isNotBlank()&&proposedOwnerPhaseUid.isNotBlank())
    require(supportingProjectedRecordUids.none{it.isBlank()}&&pacingTags.none{it.isBlank()}&&directMutationPayload?.isBlank()!=true)
}}

data class DirectorBundle(
    val schemaVersion:Int=DIRECTOR_BUNDLE_SCHEMA_VERSION,val bundleUid:String,val jobUid:String,val campaignUid:String,
    val triggerUid:String,val contextVersion:String,val asOfCommittedOrder:Long,val providerUid:String,val modelUid:String,
    val candidates:List<DirectorCandidate>,val createdAgainstFingerprint:String
){init{
    require(schemaVersion==DIRECTOR_BUNDLE_SCHEMA_VERSION&&listOf(bundleUid,jobUid,campaignUid,triggerUid,contextVersion,providerUid,modelUid,createdAgainstFingerprint).none{it.isBlank()})
    require(asOfCommittedOrder>=0&&candidates.map{it.candidateUid}.distinct().size==candidates.size&&candidates.size<=64)
}}

data class AiDirectorRequest(
    val requestUid:String,val jobUid:String,val trigger:DirectorTrigger,val context:DirectorContextEnvelope
){init{require(requestUid.isNotBlank()&&jobUid.isNotBlank()&&trigger.campaignUid==context.campaignUid&&trigger.atCommittedOrder==context.asOfCommittedOrder)}}

data class DirectorJobRecord(
    val jobUid:String,val campaignUid:String,val triggerUid:String,val contextVersion:String,val atCommittedOrder:Long,
    val state:DirectorJobState,val providerUid:String?=null,val modelUid:String?=null,val terminalReasonUid:String?=null
)

interface DirectorJobStore{
    fun reserve(record:DirectorJobRecord):Boolean
    fun transition(record:DirectorJobRecord)
    fun find(jobUid:String):DirectorJobRecord?
    fun lastAcceptedOrder(campaignUid:String):Long?
}

class InMemoryDirectorJobStore:DirectorJobStore{
    private val jobs=linkedMapOf<String,DirectorJobRecord>()
    @Synchronized override fun reserve(record:DirectorJobRecord):Boolean{if(record.jobUid in jobs)return false;jobs[record.jobUid]=record;return true}
    @Synchronized override fun transition(record:DirectorJobRecord){require(jobs[record.jobUid]?.campaignUid==record.campaignUid);jobs[record.jobUid]=record}
    @Synchronized override fun find(jobUid:String)=jobs[jobUid]
    @Synchronized override fun lastAcceptedOrder(campaignUid:String)=jobs.values.filter{it.campaignUid==campaignUid&&it.state==DirectorJobState.ACCEPTED}.maxOfOrNull{it.atCommittedOrder}
}

interface DirectorCandidateStore{
    fun put(bundle:DirectorBundle)
    fun latest(campaignUid:String):DirectorBundle?
}
class InMemoryDirectorCandidateStore:DirectorCandidateStore{
    private val values=linkedMapOf<String,DirectorBundle>()
    @Synchronized override fun put(bundle:DirectorBundle){
        val existing=values[bundle.bundleUid];require(existing==null||existing==bundle){"RPGOS-P65:DIRECTOR_BUNDLE_IDENTITY_CONFLICT"};values[bundle.bundleUid]=bundle
    }
    @Synchronized override fun latest(campaignUid:String)=values.values.filter{it.campaignUid==campaignUid}.maxByOrNull{it.asOfCommittedOrder}
}

data class DirectorBundleValidation(val accepted:Boolean,val reasonUids:List<String>){init{require(accepted==reasonUids.isEmpty())}}
class DirectorBundleValidator{
    fun validate(bundle:DirectorBundle,request:AiDirectorRequest,currentContextVersion:String):DirectorBundleValidation{
        val reasons=linkedSetOf<String>()
        if(bundle.campaignUid!=request.context.campaignUid)reasons+="DIRECTOR_CROSS_CAMPAIGN"
        if(bundle.jobUid!=request.jobUid||bundle.triggerUid!=request.trigger.triggerUid)reasons+="DIRECTOR_JOB_IDENTITY_MISMATCH"
        if(bundle.contextVersion!=request.context.contextVersion||bundle.contextVersion!=currentContextVersion)reasons+="DIRECTOR_STALE_CONTEXT"
        if(bundle.asOfCommittedOrder!=request.context.asOfCommittedOrder)reasons+="DIRECTOR_AS_OF_ORDER_MISMATCH"
        if(bundle.candidates.any{it.directMutationPayload!=null})reasons+="DIRECTOR_DIRECT_MUTATION_ATTEMPT"
        if(bundle.candidates.flatMap{it.supportingProjectedRecordUids}.any{it !in request.context.projectedRecordUids})reasons+="DIRECTOR_SUPPORT_OUTSIDE_PROJECTED_CONTEXT"
        val joined=bundle.candidates.joinToString("|"){"${it.title}|${it.summary}"}.lowercase()
        if(request.context.hiddenDisclosureTokens.any{it.lowercase() in joined})reasons+="DIRECTOR_HIDDEN_LEAKAGE"
        val legalOwners=setOf("PHASE55_MEMORY","PHASE61_NPC","PHASE63_WORLD","PHASE64_WORLD_PROCESS","PHASE65_DIRECTOR","PHASE66_PROMISE","PHASE67_PACING")
        if(bundle.candidates.any{it.proposedOwnerPhaseUid !in legalOwners})reasons+="DIRECTOR_UNKNOWN_MATERIALIZATION_OWNER"
        return DirectorBundleValidation(reasons.isEmpty(),reasons.sorted())
    }
}

data class DirectorCadencePolicy(val minimumCommittedTurns:Int=5,val preferredCommittedTurns:Int=10,val maximumCommittedTurns:Int=16){
    init{require(minimumCommittedTurns in 1..100&&preferredCommittedTurns>=minimumCommittedTurns&&maximumCommittedTurns>=preferredCommittedTurns)}
}
class DirectorTriggerPolicy(private val cadence:DirectorCadencePolicy=DirectorCadencePolicy()){
    fun shouldRun(trigger:DirectorTrigger,lastAcceptedOrder:Long?):Boolean{
        if(trigger.kind!=DirectorTriggerKind.CADENCE)return true
        val distance=trigger.atCommittedOrder-(lastAcceptedOrder?:0L)
        return distance>=cadence.preferredCommittedTurns||distance>=cadence.maximumCommittedTurns
    }
}

fun interface DirectorJobDispatcher{fun dispatch(jobUid:String,work:()->Unit)}
fun interface DirectorContextVersionPort{fun current(campaignUid:String):String}

sealed interface DirectorDispatchResult{
    data class Scheduled(val jobUid:String):DirectorDispatchResult
    data class Skipped(val reasonUid:String):DirectorDispatchResult
}

/** Phase65 owner: asynchronous candidate generation only; no current-turn or mutation dependency. */
class DirectorEngine(
    private val modelRoute:AiModelRoutePort,
    private val jobs:DirectorJobStore,
    private val candidates:DirectorCandidateStore,
    private val dispatcher:DirectorJobDispatcher,
    private val contextVersions:DirectorContextVersionPort,
    private val triggerPolicy:DirectorTriggerPolicy=DirectorTriggerPolicy(),
    private val validator:DirectorBundleValidator=DirectorBundleValidator()
){
    fun schedule(trigger:DirectorTrigger,context:DirectorContextEnvelope):DirectorDispatchResult{
        if(trigger.campaignUid!=context.campaignUid||trigger.atCommittedOrder!=context.asOfCommittedOrder)return DirectorDispatchResult.Skipped("DIRECTOR_TRIGGER_CONTEXT_MISMATCH")
        if(!triggerPolicy.shouldRun(trigger,jobs.lastAcceptedOrder(trigger.campaignUid)))return DirectorDispatchResult.Skipped("DIRECTOR_CADENCE_NOT_DUE")
        val jobUid="DIRECTOR-JOB:${fingerprint("${trigger.campaignUid}|${trigger.triggerUid}|${context.contextVersion}|${context.asOfCommittedOrder}")}"
        val initial=DirectorJobRecord(jobUid,trigger.campaignUid,trigger.triggerUid,context.contextVersion,context.asOfCommittedOrder,DirectorJobState.RESERVED)
        if(!jobs.reserve(initial))return DirectorDispatchResult.Skipped("DIRECTOR_JOB_ALREADY_RESERVED")
        dispatcher.dispatch(jobUid){run(initial,trigger,context)}
        return DirectorDispatchResult.Scheduled(jobUid)
    }
    private fun run(initial:DirectorJobRecord,trigger:DirectorTrigger,context:DirectorContextEnvelope){
        val route=modelRoute.route(AiRole.DIRECTOR_SCENARIST,AiWorkload.DIRECTOR_STRATEGY,context.strategicSummarySegments.sumOf{it.length/4+1})
        val provider=(route as? AiRouteResult.Selected)?.provider
        if(provider==null){jobs.transition(initial.copy(state=DirectorJobState.FAILED,terminalReasonUid="DIRECTOR_PROVIDER_UNAVAILABLE"));return}
        val running=initial.copy(state=DirectorJobState.RUNNING,providerUid=provider.capabilities.providerUid,modelUid=provider.capabilities.modelUid);jobs.transition(running)
        val request=AiDirectorRequest("${initial.jobUid}:REQUEST",initial.jobUid,trigger,context)
        when(val generated=provider.generateDirector(request)){
            is AiProviderResult.Failure->jobs.transition(running.copy(state=if(generated.kind==AiProviderFailureKind.CANCELLED)DirectorJobState.CANCELLED else DirectorJobState.FAILED,terminalReasonUid=generated.reasonUid))
            is AiProviderResult.Success->{
                val bundle=generated.value
                if(bundle.providerUid!=provider.capabilities.providerUid||bundle.modelUid!=provider.capabilities.modelUid){jobs.transition(running.copy(state=DirectorJobState.REJECTED,terminalReasonUid="DIRECTOR_PROVENANCE_MISMATCH"));return}
                val checked=validator.validate(bundle,request,contextVersions.current(context.campaignUid))
                if(!checked.accepted)jobs.transition(running.copy(state=DirectorJobState.REJECTED,terminalReasonUid=checked.reasonUids.joinToString("|")))
                else{candidates.put(bundle);jobs.transition(running.copy(state=DirectorJobState.ACCEPTED,terminalReasonUid="DIRECTOR_BUNDLE_ACCEPTED"))}
            }
        }
    }
    private fun fingerprint(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

fun interface DirectorCandidateMaterializationPort{
    /** Implemented only by the real Phase55/61/63/64/etc owner; Director itself never calls a DB. */
    fun materialize(candidate:DirectorCandidate,bundle:DirectorBundle):String
}
