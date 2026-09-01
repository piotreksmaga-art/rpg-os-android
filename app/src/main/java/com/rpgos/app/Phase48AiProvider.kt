package com.rpgos.app

enum class AiWorkload {
    INTENT_INTERPRETATION,
    GM_PROPOSAL,
    PROPOSAL_REPAIR,
    NARRATIVE_RENDER,
    NARRATIVE_REPAIR,
    CHARACTER_CREATION,
    DIRECTOR_STRATEGY
}
enum class AiProviderKind { LOCAL, CLOUD, CONTROLLED_TEST }
enum class AiProviderFailureKind { CANCELLED, UNAVAILABLE, TIMEOUT, INVALID_STRUCTURED_OUTPUT, CAPABILITY_MISMATCH, INTERNAL_FAILURE }

data class AiCapabilityContract(
    val contractUid:String,
    val providerUid:String,
    val modelUid:String,
    val supportedWorkloads:Set<AiWorkload>,
    val intentSchemaVersions:Set<Int> = setOf(PHASE43_INTENT_SCHEMA_VERSION),
    val gmProposalSchemaVersions:Set<Int> = setOf(1),
    val supportsStreaming:Boolean=false,
    val maximumContextUnits:Int,
    val providerKind:AiProviderKind=AiProviderKind.CONTROLLED_TEST,
    val supportsJsonSchema:Boolean=true
){init{
    require(contractUid.isNotBlank()&&providerUid.isNotBlank()&&modelUid.isNotBlank())
    require(supportedWorkloads.isNotEmpty()&&maximumContextUnits>0)
    require(intentSchemaVersions.all{it>0}&&gmProposalSchemaVersions.all{it>0})
}}

fun interface AiCancellationSignal{
    fun isCancelled():Boolean
    companion object{val NONE=AiCancellationSignal{false}}
}

class MutableAiCancellationSignal:AiCancellationSignal{
    private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    override fun isCancelled()=cancelled.get()
    fun cancel(){cancelled.set(true)}
}

sealed interface AiProviderResult<out T>{
    data class Success<T>(val value:T,val providerUid:String,val modelUid:String,val traceUid:String):AiProviderResult<T>{init{require(providerUid.isNotBlank()&&modelUid.isNotBlank()&&traceUid.isNotBlank())}}
    data class Failure(val kind:AiProviderFailureKind,val reasonUid:String,val retryable:Boolean=false):AiProviderResult<Nothing>{init{require(reasonUid.isNotBlank())}}
}

data class AiIntentRequest(
    val requestUid:String,
    val campaignUid:String,
    val actor:CommandActorRef,
    val rawInput:String,
    val localeUid:String,
    val schemaVersion:Int=PHASE43_INTENT_SCHEMA_VERSION
){init{require(requestUid.isNotBlank()&&campaignUid.isNotBlank()&&rawInput.isNotBlank()&&localeUid.isNotBlank())}}

data class AiGmProposalRequest(
    val requestUid:String,
    val plan:CanonicalTurnPlan,
    val context:BudgetedCanonicalContext,
    val strategicGuidance:DirectorGuidanceEnvelope?=null,
    val proposalSchemaVersion:Int=1
){init{
    require(requestUid.isNotBlank()&&proposalSchemaVersion>0)
    require(context.candidate.plan.planUid==plan.planUid&&context.safeForAi){"RPGOS-P48:UNSAFE_CONTEXT"}
    strategicGuidance?.let{require(it.campaignUid==plan.campaignUid){"RPGOS-P48:DIRECTOR_GUIDANCE_CROSS_CAMPAIGN"}}
}}

data class AiRepairRequest(
    val requestUid:String,
    val original:AiGmProposalRequest,
    val rejectedCandidate:GmProposalCandidate,
    val rejectionReasonUids:List<String>,
    val attempt:Int
){init{require(requestUid.isNotBlank()&&rejectionReasonUids.isNotEmpty()&&attempt>0)}}

data class AiNarrativeRequest(
    val requestUid:String,
    val context:CommittedNarrationContext,
    val localeUid:String,
    /** The player's own committed turn input. It is presentation evidence, never new volition. */
    val playerInput:String?=null,
    /** Player-visible, Phase44/45-authorized context that was actually admitted for this turn. */
    val authorizedContext:List<NarrativeAuthorizedContext> = emptyList()
){init{
    require(requestUid.isNotBlank()&&context.campaignUid.isNotBlank()&&localeUid.isNotBlank()&&context.committedOrder>0)
    require(playerInput?.isBlank()!=true)
    require(authorizedContext.map{it.recordUid}.distinct().size==authorizedContext.size)
}
    val campaignUid:String get()=context.campaignUid
}

data class NarrativeAuthorizedContext(
    val recordUid:String,
    val recordKindUid:String,
    val epistemicStateUid:String,
    val projectedText:String
){init{
    require(listOf(recordUid,recordKindUid,epistemicStateUid,projectedText).none{it.isBlank()})
    require(projectedText.length<=2_048)
}}

data class RenderedNarrative(
    val text:String,
    val stopReasonUid:String,
    val committedOrder:Long,
    val claims:List<NarrativeSemanticClaim> = emptyList(),
    val assertsPlayerVolition:Boolean=false
){init{
    require(text.isNotBlank()&&stopReasonUid.isNotBlank()&&committedOrder>0)
    require(claims.map{it.claimUid}.distinct().size==claims.size)
}}

interface AiProvider{
    val capabilities:AiCapabilityContract
    fun interpret(request:AiIntentRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<IntentDocument>
    fun propose(request:AiGmProposalRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<GmProposalCandidate>
    fun repair(request:AiRepairRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<GmProposalCandidate>
    fun renderNarrative(request:AiNarrativeRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<RenderedNarrative>
    fun repairNarrative(request:AiNarrativeRepairRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<RenderedNarrative> =
        AiProviderResult.Failure(AiProviderFailureKind.CAPABILITY_MISMATCH,"NARRATIVE_REPAIR_UNSUPPORTED")
    fun guideCharacterCreation(request:AiCharacterCreationRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<CharacterCreationGmCandidate> =
        AiProviderResult.Failure(AiProviderFailureKind.CAPABILITY_MISMATCH,"CHARACTER_CREATION_UNSUPPORTED")
    fun generateDirector(request:AiDirectorRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):AiProviderResult<DirectorBundle> =
        AiProviderResult.Failure(AiProviderFailureKind.CAPABILITY_MISMATCH,"DIRECTOR_STRATEGY_UNSUPPORTED")
    fun cancel(requestUid:String)
}

/** Trusted registration is composition-root configuration, never model output or chat content. */
class AiProviderRegistry private constructor(registered:List<AiProvider>){
    private val providers=registered.associateBy{it.capabilities.providerUid to it.capabilities.modelUid}
    private val byProviderUid=providers.values.groupBy{it.capabilities.providerUid}
    init{require(this.providers.size==registered.size){"RPGOS-P48:DUPLICATE_PROVIDER_MODEL"}}
    fun require(providerUid:String,workload:AiWorkload):AiProvider{
        val matching=byProviderUid[providerUid].orEmpty()
        val provider=matching.singleOrNull()?:throw IllegalArgumentException(if(matching.isEmpty())"RPGOS-P48:PROVIDER_NOT_REGISTERED" else "RPGOS-P48:PROVIDER_MODEL_AMBIGUOUS")
        require(workload in provider.capabilities.supportedWorkloads){"RPGOS-P48:WORKLOAD_UNSUPPORTED"}
        return provider
    }
    fun all():List<AiProvider> = providers.values.sortedWith(
        compareBy<AiProvider>{it.capabilities.providerKind.ordinal}
            .thenBy{it.capabilities.providerUid}
            .thenBy{it.capabilities.modelUid}
    )
    fun find(providerUid:String,modelUid:String):AiProvider? = providers[providerUid to modelUid]
    companion object{fun fromCompositionRoot(providers:List<AiProvider>)=AiProviderRegistry(providers.toList())}
}

data class AiTransportRequest(
    val requestUid:String,
    val workload:AiWorkload,
    val schemaVersion:Int,
    val payload:String,
    val maximumOutputUnits:Int
)
data class AiTransportResponse(val requestUid:String,val structuredPayload:String,val traceUid:String)

fun interface AiStructuredTransport{
    /** May call a configured local or cloud runtime; it receives serialized projected data, never repository access. */
    fun execute(request:AiTransportRequest,cancellation:AiCancellationSignal):AiProviderResult<AiTransportResponse>
}

class AiTransportException(val reasonUid:String,val retryable:Boolean=false,cause:Throwable?=null):RuntimeException(reasonUid,cause){init{require(reasonUid.isNotBlank())}}

interface AiStructuredCodec{
    fun encodeIntent(request:AiIntentRequest):String
    fun decodeIntent(payload:String):IntentDocument
    fun decodeIntent(payload:String,request:AiIntentRequest):IntentDocument=decodeIntent(payload)
    fun encodeProposal(request:AiGmProposalRequest):String
    fun decodeProposal(payload:String):GmProposalCandidate
    fun decodeProposal(payload:String,request:AiGmProposalRequest):GmProposalCandidate=decodeProposal(payload)
    fun encodeRepair(request:AiRepairRequest):String
    fun encodeNarrative(request:AiNarrativeRequest):String
    fun encodeNarrativeRepair(request:AiNarrativeRepairRequest):String
    fun decodeNarrative(payload:String):RenderedNarrative
    fun decodeNarrative(payload:String,request:AiNarrativeRequest):RenderedNarrative=decodeNarrative(payload)
    fun encodeCharacterCreation(request:AiCharacterCreationRequest):String = throw IllegalArgumentException("CHARACTER_CREATION_CODEC_UNSUPPORTED")
    fun decodeCharacterCreation(payload:String):CharacterCreationGmCandidate = throw IllegalArgumentException("CHARACTER_CREATION_CODEC_UNSUPPORTED")
    fun decodeCharacterCreation(payload:String,request:AiCharacterCreationRequest):CharacterCreationGmCandidate=
        decodeCharacterCreation(payload)
    fun encodeDirector(request:AiDirectorRequest):String
    fun decodeDirector(payload:String):DirectorBundle
}

/** Production-ready adapter seam: adding a model requires transport + codec + registration, not Core changes. */
class TransportAiProviderAdapter(
    override val capabilities:AiCapabilityContract,
    private val transport:AiStructuredTransport,
    private val codec:AiStructuredCodec,
    private val maximumOutputUnits:Int=4_096,
    private val cancellationHook:(String)->Unit={}
):AiProvider{
    init{require(maximumOutputUnits>0)}

    override fun interpret(request:AiIntentRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.INTENT_INTERPRETATION,request.schemaVersion,codec.encodeIntent(request),cancellation,
        {payload->codec.decodeIntent(payload,request)}
    )
    override fun propose(request:AiGmProposalRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.GM_PROPOSAL,request.proposalSchemaVersion,codec.encodeProposal(request),cancellation,
        {payload->codec.decodeProposal(payload,request).copy(providerUid=capabilities.providerUid,modelUid=capabilities.modelUid)}
    )
    override fun repair(request:AiRepairRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.PROPOSAL_REPAIR,request.original.proposalSchemaVersion,codec.encodeRepair(request),cancellation,
        {payload->codec.decodeProposal(payload,request.original).copy(providerUid=capabilities.providerUid,modelUid=capabilities.modelUid)}
    )
    override fun renderNarrative(request:AiNarrativeRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.NARRATIVE_RENDER,1,codec.encodeNarrative(request),cancellation,
        {payload->codec.decodeNarrative(payload,request)}
    )
    override fun repairNarrative(request:AiNarrativeRepairRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.NARRATIVE_REPAIR,1,codec.encodeNarrativeRepair(request),cancellation,
        {payload->codec.decodeNarrative(payload,request.original)}
    )
    override fun guideCharacterCreation(request:AiCharacterCreationRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.CHARACTER_CREATION,1,codec.encodeCharacterCreation(request),cancellation,
        {payload->codec.decodeCharacterCreation(payload,request)}
    )
    override fun generateDirector(request:AiDirectorRequest,cancellation:AiCancellationSignal)=call(
        request.requestUid,AiWorkload.DIRECTOR_STRATEGY,DIRECTOR_BUNDLE_SCHEMA_VERSION,codec.encodeDirector(request),cancellation,
        {payload->codec.decodeDirector(payload).copy(providerUid=capabilities.providerUid,modelUid=capabilities.modelUid)}
    )
    override fun cancel(requestUid:String){require(requestUid.isNotBlank());cancellationHook(requestUid)}

    private fun <T> call(requestUid:String,workload:AiWorkload,schema:Int,payload:String,cancellation:AiCancellationSignal,decode:(String)->T):AiProviderResult<T>{
        if(workload !in capabilities.supportedWorkloads)return AiProviderResult.Failure(AiProviderFailureKind.CAPABILITY_MISMATCH,"WORKLOAD_UNSUPPORTED")
        if(cancellation.isCancelled())return AiProviderResult.Failure(AiProviderFailureKind.CANCELLED,"CANCELLED_BEFORE_TRANSPORT")
        val response=try{transport.execute(AiTransportRequest(requestUid,workload,schema,payload,maximumOutputUnits),cancellation)}
        catch(failure:AiTransportException){return AiProviderResult.Failure(AiProviderFailureKind.UNAVAILABLE,failure.reasonUid,failure.retryable)}
        if(response is AiProviderResult.Failure)return response
        response as AiProviderResult.Success
        if(cancellation.isCancelled())return AiProviderResult.Failure(AiProviderFailureKind.CANCELLED,"CANCELLED_AFTER_TRANSPORT")
        return try{
            val decoded=decode(response.value.structuredPayload)
            AiProviderResult.Success(decoded,capabilities.providerUid,capabilities.modelUid,response.value.traceUid)
        }catch(_:RuntimeException){
            AiProviderResult.Failure(AiProviderFailureKind.INVALID_STRUCTURED_OUTPUT,"STRUCTURED_OUTPUT_DECODE_REJECTED")
        }
    }
}

/** Deterministic conformance provider. Behavior is injected; Core contains no language-specific cases. */
class DeterministicAiProvider(
    override val capabilities:AiCapabilityContract,
    private val intentFunction:(AiIntentRequest)->IntentDocument,
    private val proposalFunction:(AiGmProposalRequest)->GmProposalCandidate,
    private val repairFunction:(AiRepairRequest)->GmProposalCandidate={it.rejectedCandidate},
    private val narrativeFunction:(AiNarrativeRequest)->RenderedNarrative,
    private val narrativeRepairFunction:(AiNarrativeRepairRequest)->RenderedNarrative={narrativeFunction(it.original)},
    private val directorFunction:(AiDirectorRequest)->DirectorBundle={throw IllegalArgumentException("DIRECTOR_NOT_CONFIGURED")},
    private val characterCreationFunction:(AiCharacterCreationRequest)->CharacterCreationGmCandidate={throw IllegalArgumentException("CHARACTER_CREATION_NOT_CONFIGURED")}
):AiProvider{
    override fun interpret(request:AiIntentRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){intentFunction(request)}
    override fun propose(request:AiGmProposalRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){proposalFunction(request)}
    override fun repair(request:AiRepairRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){repairFunction(request)}
    override fun renderNarrative(request:AiNarrativeRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){narrativeFunction(request)}
    override fun repairNarrative(request:AiNarrativeRepairRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){narrativeRepairFunction(request)}
    override fun guideCharacterCreation(request:AiCharacterCreationRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){characterCreationFunction(request)}
    override fun generateDirector(request:AiDirectorRequest,cancellation:AiCancellationSignal)=invoke(request.requestUid,cancellation){directorFunction(request)}
    override fun cancel(requestUid:String)=Unit
    private fun <T> invoke(requestUid:String,cancellation:AiCancellationSignal,block:()->T):AiProviderResult<T>{
        if(cancellation.isCancelled())return AiProviderResult.Failure(AiProviderFailureKind.CANCELLED,"CANCELLED")
        return try{AiProviderResult.Success(block(),capabilities.providerUid,capabilities.modelUid,"DETERMINISTIC:$requestUid")}
        catch(_:IllegalArgumentException){AiProviderResult.Failure(AiProviderFailureKind.INVALID_STRUCTURED_OUTPUT,"DETERMINISTIC_OUTPUT_REJECTED")}
    }
}
