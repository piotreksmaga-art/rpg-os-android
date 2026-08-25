package com.rpgos.app

enum class TurnMutationState { NOT_STARTED, COMMITTED, UNVERIFIED }
enum class AiTurnStage { INTERPRETATION, INTENT_VALIDATION, PLANNING, CONTEXT, PROPOSAL, VALIDATION_REPAIR, ASSEMBLY, COMMIT, NARRATIVE }

data class ChatTurnRequest(
    val requestUid:String,
    val campaignUid:String,
    val turnUid:String,
    val commandUid:String,
    val transactionUid:String,
    val actor:CommandActorRef,
    val input:String,
    val localeUid:String,
    val audience:AudienceContext,
    val purpose:PurposeContext,
    val atOrder:Long?=null
){init{
    require(requestUid.isNotBlank()&&campaignUid.isNotBlank()&&turnUid.isNotBlank()&&commandUid.isNotBlank()&&transactionUid.isNotBlank())
    require(input.isNotBlank()&&localeUid.isNotBlank()&&audience.campaignUid==campaignUid&&purpose.campaignUid==campaignUid)
}}

sealed interface ChatTurnResult{
    val mutationState:TurnMutationState
    data class Narrated(
        val narrative:RenderedNarrative,
        val receipt:TurnCommitReceipt,
        val planUid:String,
        val proposalUid:String,
        val repairAttempts:Int
    ):ChatTurnResult{override val mutationState=TurnMutationState.COMMITTED}
    data class CommittedWithoutNarrative(
        val receipt:TurnCommitReceipt,
        val planUid:String,
        val proposalUid:String,
        val reasonUid:String
    ):ChatTurnResult{override val mutationState=TurnMutationState.COMMITTED}
    data class Rejected(val stage:AiTurnStage,val reasonUids:List<String>):ChatTurnResult{
        override val mutationState=TurnMutationState.NOT_STARTED
        init{require(reasonUids.isNotEmpty())}
    }
    data class Failed(val stage:AiTurnStage,val reasonUid:String,override val mutationState:TurnMutationState):ChatTurnResult{init{require(reasonUid.isNotBlank())}}
    data class Cancelled(val stage:AiTurnStage,override val mutationState:TurnMutationState):ChatTurnResult
}

fun interface TrustedIntentResolutionPort{
    /** Core-owned optional resolution of descriptors against projected context; never accepts model authority. */
    fun resolve(candidate:IntentDocument):IntentDocument
    companion object{val NONE=TrustedIntentResolutionPort{it}}
}

fun interface IntentInterpretationFallback{
    fun interpret(request:AiIntentRequest):IntentDocument?
    companion object{val NONE=IntentInterpretationFallback{null}}
}

class LegacyRuleIntentFallback(private val parser:IntentParser=IntentParser()):IntentInterpretationFallback{
    override fun interpret(request:AiIntentRequest)=when(val result=parser.parse(request.campaignUid,request.actor,request.rawInput)){
        is IntentParseResult.Parsed->LegacyIntentDocumentAdapter.toDocument(result.intent)
        else->null
    }
}

fun interface CanonicalMutationAssembler{
    /** Converts verified mechanics into material admitted by the existing PlayerDomainEngine boundary. */
    fun assemble(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal):CanonicalCampaignMutationProposal?
}

fun interface AuthoritativeTurnCommitPort{
    fun commit(identity:TurnTransactionIdentity,proposal:CanonicalCampaignMutationProposal):TurnExecutionResult<TurnCommitAppliedResult>
}

fun interface CommittedReceiptLookup{fun find(transactionUid:String):TurnCommitReceipt?}

class AuthoritativeCommitEvidence internal constructor(
    val receipt:TurnCommitReceipt,
    val requestedIdentity:TurnTransactionIdentity
)

class PersistedCommitReceiptAuthority(private val lookup:CommittedReceiptLookup){
    fun authorize(receipt:TurnCommitReceipt,identity:TurnTransactionIdentity):AuthoritativeCommitEvidence?{
        if(receipt.campaignUid!=identity.campaignUid||receipt.commandUid!=identity.commandUid)return null
        if(receipt.commitOrder?.let{it>0L}!=true||receipt.receiptVersion<TURN_TRANSACTION_RECEIPT_VERSION)return null
        if(receipt.requiredEventCount==null||receipt.requiredEventManifestFingerprint.isNullOrBlank())return null
        if(lookup.find(receipt.transactionUid)!=receipt)return null
        return AuthoritativeCommitEvidence(receipt,identity)
    }
}

class CanonicalCommitException(val reasonUid:String,cause:Throwable?=null):RuntimeException(reasonUid,cause){init{require(reasonUid.isNotBlank())}}

/**
 * Provider-independent application facade. AI may interpret and propose; only the assembler,
 * canonical transaction port and persisted-receipt authority can cross the mutation boundary.
 */
class AiChatEngineFacade(
    private val providerRegistry:AiProviderRegistry,
    private val providerUid:String,
    private val intentValidator:Phase43IntentValidator,
    private val intentResolution:TrustedIntentResolutionPort,
    private val intentFallback:IntentInterpretationFallback,
    private val planner:GraphTurnPlanner,
    private val contextPipeline:CanonicalIterativeRetrievalPipeline,
    private val contextProfile:ContextRuntimeProfile,
    private val proposalRepair:BoundedProposalRepair,
    private val assembler:CanonicalMutationAssembler,
    private val commitPort:AuthoritativeTurnCommitPort,
    private val receiptAuthority:PersistedCommitReceiptAuthority
){
    fun play(request:ChatTurnRequest,cancellation:AiCancellationSignal=AiCancellationSignal.NONE):ChatTurnResult{
        if(cancellation.isCancelled())return ChatTurnResult.Cancelled(AiTurnStage.INTERPRETATION,TurnMutationState.NOT_STARTED)
        val provider=try{providerRegistry.require(providerUid,AiWorkload.INTENT_INTERPRETATION)}catch(_:IllegalArgumentException){
            return ChatTurnResult.Failed(AiTurnStage.INTERPRETATION,"AI_PROVIDER_UNAVAILABLE",TurnMutationState.NOT_STARTED)
        }
        if(PHASE43_INTENT_SCHEMA_VERSION !in provider.capabilities.intentSchemaVersions)return ChatTurnResult.Failed(
            AiTurnStage.INTERPRETATION,"PROVIDER_INTENT_SCHEMA_UNSUPPORTED",TurnMutationState.NOT_STARTED
        )
        val intentRequest=AiIntentRequest("${request.requestUid}:INTENT",request.campaignUid,request.actor,request.input,request.localeUid)
        val interpreted=when(val result=provider.interpret(intentRequest,cancellation)){
            is AiProviderResult.Success->result.value
            is AiProviderResult.Failure->intentFallback.interpret(intentRequest)?:return if(result.kind==AiProviderFailureKind.CANCELLED)
                ChatTurnResult.Cancelled(AiTurnStage.INTERPRETATION,TurnMutationState.NOT_STARTED)
            else ChatTurnResult.Failed(AiTurnStage.INTERPRETATION,result.reasonUid,TurnMutationState.NOT_STARTED)
        }
        val resolved=try{intentResolution.resolve(interpreted)}catch(failure:IllegalArgumentException){
            return ChatTurnResult.Failed(AiTurnStage.INTENT_VALIDATION,failure.message?:"TRUSTED_RESOLUTION_FAILED",TurnMutationState.NOT_STARTED)
        }
        if(resolved.campaignUid!=request.campaignUid||resolved.actor!=request.actor)return ChatTurnResult.Rejected(AiTurnStage.INTENT_VALIDATION,listOf("INTENT_REQUEST_IDENTITY_MISMATCH"))
        val intent=when(val validation=intentValidator.validate(resolved)){
            is IntentValidationResult.Accepted->validation.document
            is IntentValidationResult.Rejected->return ChatTurnResult.Rejected(AiTurnStage.INTENT_VALIDATION,validation.reasonUids)
        }
        if(cancellation.isCancelled())return ChatTurnResult.Cancelled(AiTurnStage.PLANNING,TurnMutationState.NOT_STARTED)
        val plan=when(val planning=planner.plan(intent,request.audience,request.purpose,request.atOrder)){
            is CanonicalPlanningResult.Planned->planning.plan
            is CanonicalPlanningResult.Rejected->return ChatTurnResult.Rejected(AiTurnStage.PLANNING,planning.reasonUids)
        }
        if(plan.steps.any{it.matchState==CapabilityMatchState.REQUIRES_ADJUDICATION})return ChatTurnResult.Rejected(
            AiTurnStage.PLANNING,plan.steps.flatMap{it.reasonUids}.ifEmpty{listOf("PLAN_REQUIRES_ADJUDICATION")}.distinct().sorted()
        )
        val context=contextPipeline.execute(plan,contextProfile)
        if(context.state!=ContextCompletionState.COMPLETE||!context.budgeted.safeForAi)return ChatTurnResult.Rejected(AiTurnStage.CONTEXT,(context.budgeted.reasonUids+context.terminationUid).distinct())
        if(cancellation.isCancelled())return ChatTurnResult.Cancelled(AiTurnStage.PROPOSAL,TurnMutationState.NOT_STARTED)
        val proposalProvider=try{providerRegistry.require(providerUid,AiWorkload.GM_PROPOSAL)}catch(_:IllegalArgumentException){
            return ChatTurnResult.Failed(AiTurnStage.PROPOSAL,"AI_PROVIDER_UNAVAILABLE",TurnMutationState.NOT_STARTED)
        }
        if(context.budgeted.finalSerializedUnits>proposalProvider.capabilities.maximumContextUnits)return ChatTurnResult.Rejected(AiTurnStage.CONTEXT,listOf("PROVIDER_CONTEXT_LIMIT"))
        if(proposalRequestVersionUnsupported(proposalProvider))return ChatTurnResult.Failed(
            AiTurnStage.PROPOSAL,"PROVIDER_SCHEMA_UNSUPPORTED",TurnMutationState.NOT_STARTED
        )
        val proposalRequest=AiGmProposalRequest("${request.requestUid}:PROPOSAL",plan,context.budgeted)
        val candidate=when(val generated=proposalProvider.propose(proposalRequest,cancellation)){
            is AiProviderResult.Success->generated.value
            is AiProviderResult.Failure->return if(generated.kind==AiProviderFailureKind.CANCELLED)
                ChatTurnResult.Cancelled(AiTurnStage.PROPOSAL,TurnMutationState.NOT_STARTED)
            else ChatTurnResult.Failed(AiTurnStage.PROPOSAL,generated.reasonUid,TurnMutationState.NOT_STARTED)
        }
        if(candidate.providerUid!=proposalProvider.capabilities.providerUid||candidate.modelUid!=proposalProvider.capabilities.modelUid)return ChatTurnResult.Rejected(
            AiTurnStage.PROPOSAL,listOf("PROVIDER_PROVENANCE_MISMATCH")
        )
        val repaired=proposalRepair.evaluateAndRepair(proposalProvider,proposalRequest,candidate,cancellation)
        val verified=when(val evaluation=repaired.evaluation){
            is GmProposalEvaluation.Accepted->evaluation.proposal
            is GmProposalEvaluation.Rejected->return if(cancellation.isCancelled())ChatTurnResult.Cancelled(AiTurnStage.VALIDATION_REPAIR,TurnMutationState.NOT_STARTED)
            else ChatTurnResult.Rejected(AiTurnStage.VALIDATION_REPAIR,evaluation.reasonUids)
        }
        if(cancellation.isCancelled())return ChatTurnResult.Cancelled(AiTurnStage.ASSEMBLY,TurnMutationState.NOT_STARTED)
        val canonical=assembler.assemble(request,plan,verified)?:return ChatTurnResult.Rejected(AiTurnStage.ASSEMBLY,listOf("NO_CANONICAL_MUTATION_PROPOSAL"))
        if(canonical.campaignUid!=request.campaignUid||canonical.playerChangeSet.sourceCommandUid!=request.commandUid)return ChatTurnResult.Rejected(
            AiTurnStage.ASSEMBLY,listOf("CANONICAL_PROPOSAL_IDENTITY_MISMATCH")
        )
        if(cancellation.isCancelled())return ChatTurnResult.Cancelled(AiTurnStage.COMMIT,TurnMutationState.NOT_STARTED)
        val identity=TurnTransactionIdentity(request.campaignUid,request.turnUid,request.commandUid,request.transactionUid)
        val receipt=try{when(val committed=commitPort.commit(identity,canonical)){
            is TurnExecutionResult.Committed->committed.receipt
            is TurnExecutionResult.AlreadyCommitted->committed.receipt
        }}catch(failure:CanonicalCommitException){
            return ChatTurnResult.Failed(AiTurnStage.COMMIT,failure.reasonUid,TurnMutationState.NOT_STARTED)
        }
        val evidence=receiptAuthority.authorize(receipt,identity)?:return ChatTurnResult.Failed(AiTurnStage.COMMIT,"COMMIT_RECEIPT_NOT_AUTHORITATIVE",TurnMutationState.UNVERIFIED)
        if(cancellation.isCancelled())return ChatTurnResult.CommittedWithoutNarrative(receipt,plan.planUid,verified.candidate.proposalUid,"CANCELLED_AFTER_COMMIT")
        val narrativeProvider=try{providerRegistry.require(providerUid,AiWorkload.NARRATIVE_RENDER)}catch(_:IllegalArgumentException){
            return ChatTurnResult.CommittedWithoutNarrative(receipt,plan.planUid,verified.candidate.proposalUid,"NARRATIVE_PROVIDER_UNAVAILABLE")
        }
        val narrativeRequest=AiNarrativeRequest("${request.requestUid}:NARRATIVE",request.campaignUid,verified,evidence,request.localeUid)
        return when(val rendered=narrativeProvider.renderNarrative(narrativeRequest,cancellation)){
            is AiProviderResult.Success->{
                if(rendered.value.committedOrder!=receipt.commitOrder)ChatTurnResult.CommittedWithoutNarrative(receipt,plan.planUid,verified.candidate.proposalUid,"NARRATIVE_COMMIT_ORDER_MISMATCH")
                else ChatTurnResult.Narrated(rendered.value,receipt,plan.planUid,verified.candidate.proposalUid,repaired.attempts)
            }
            is AiProviderResult.Failure->ChatTurnResult.CommittedWithoutNarrative(receipt,plan.planUid,verified.candidate.proposalUid,rendered.reasonUid)
        }
    }

    private fun proposalRequestVersionUnsupported(provider:AiProvider)=1 !in provider.capabilities.gmProposalSchemaVersions
}
