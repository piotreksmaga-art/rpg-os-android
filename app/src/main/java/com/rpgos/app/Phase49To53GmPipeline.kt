package com.rpgos.app

enum class ProposedClaimKind { PROJECTED_FACT_CONCLUSION, PLAYER_ASSERTION, UNCERTAIN_INFERENCE, NARRATIVE_COLOR }
enum class MechanicsResolutionState { VERIFIED, REJECTED, NOT_REQUIRED }
enum class GmNodeOutcomeState { PROPOSED_SUCCESS, PROPOSED_FAILURE, BLOCKED_BY_PREREQUISITE, NEEDS_CLARIFICATION, REQUIRES_ADJUDICATION }
enum class ClaimEpistemicBasis { PROJECTED_FACT, PLAYER_ASSERTION, HOLDER_BELIEF, AI_INFERENCE, NARRATIVE_ONLY }
enum class ClaimTemporalPosition { CURRENT, HISTORICAL_AS_OF, FUTURE_PLAN, PREDICTED_OUTCOME, COUNTERFACTUAL }

data class GmNodeProposal(
    val nodeUid:String,
    val outcomeUid:String,
    val playerFacingSummary:String,
    val actor:CommandActorRef,
    val actionSemanticUid:String,
    val targetProjectedRefs:List<DomainRef>,
    val modality:IntentModality,
    val outcomeState:GmNodeOutcomeState,
    val uncertaintyUids:List<String> = emptyList(),
    val materializedResultUids:List<String> = emptyList()
){init{
    require(nodeUid.isNotBlank()&&outcomeUid.isNotBlank()&&playerFacingSummary.isNotBlank()&&actionSemanticUid.isNotBlank())
    require(uncertaintyUids.none{it.isBlank()}&&targetProjectedRefs.distinct()==targetProjectedRefs&&materializedResultUids.none{it.isBlank()})
}}

data class ProposedWorldClaim(
    val claimUid:String,
    val nodeUid:String,
    val claimKind:ProposedClaimKind,
    val subjectProjectedUid:String?,
    val predicateUid:String,
    val valueCanonical:String,
    val supportingRecordUids:List<String> = emptyList(),
    val supportingPlayerClaimUids:List<String> = emptyList(),
    val epistemicBasis:ClaimEpistemicBasis=when(claimKind){
        ProposedClaimKind.PROJECTED_FACT_CONCLUSION->ClaimEpistemicBasis.PROJECTED_FACT
        ProposedClaimKind.PLAYER_ASSERTION->ClaimEpistemicBasis.PLAYER_ASSERTION
        ProposedClaimKind.UNCERTAIN_INFERENCE->ClaimEpistemicBasis.AI_INFERENCE
        ProposedClaimKind.NARRATIVE_COLOR->ClaimEpistemicBasis.NARRATIVE_ONLY
    },
    val temporalPosition:ClaimTemporalPosition=ClaimTemporalPosition.CURRENT,
    val campaignDivergenceEvidenceUids:List<String> = emptyList()
){init{
    require(claimUid.isNotBlank()&&nodeUid.isNotBlank()&&predicateUid.isNotBlank()&&valueCanonical.isNotBlank())
    require(subjectProjectedUid?.isBlank()!=true&&supportingRecordUids.none{it.isBlank()}&&supportingRecordUids.distinct()==supportingRecordUids)
    require(supportingPlayerClaimUids.none{it.isBlank()}&&supportingPlayerClaimUids.distinct()==supportingPlayerClaimUids)
    require(campaignDivergenceEvidenceUids.none{it.isBlank()}&&campaignDivergenceEvidenceUids.distinct()==campaignDivergenceEvidenceUids)
}}

data class MechanicsEffectRequest(
    val effectUid:String,
    val nodeUid:String,
    val mechanicsOwnerUid:String,
    val effectKindUid:String,
    val targetProjectedRef:DomainRef?=null,
    val parameters:Map<String,String> = emptyMap()
){init{
    require(effectUid.isNotBlank()&&nodeUid.isNotBlank()&&mechanicsOwnerUid.isNotBlank()&&effectKindUid.isNotBlank())
    require(parameters.keys.none{it.isBlank()}&&parameters.values.none{it.length>512})
}}

data class NarrativeBlueprint(
    val beatUids:List<String>,
    val toneHintUids:List<String> = emptyList(),
    val stopPointUid:String,
    val forbiddenDisclosureUids:List<String> = emptyList()
){init{
    require(beatUids.none{it.isBlank()}&&toneHintUids.none{it.isBlank()}&&stopPointUid.isNotBlank()&&forbiddenDisclosureUids.none{it.isBlank()})
}}

data class GmProposalCandidate(
    val schemaVersion:Int=1,
    val proposalUid:String,
    val campaignUid:String,
    val planUid:String,
    val nodeProposals:List<GmNodeProposal>,
    val proposedClaims:List<ProposedWorldClaim> = emptyList(),
    val mechanicsEffects:List<MechanicsEffectRequest> = emptyList(),
    val narrativeBlueprint:NarrativeBlueprint,
    val providerUid:String,
    val modelUid:String,
    val intentFingerprint:String,
    val requestedPlayerVolitionalActionUids:List<String> = emptyList(),
    val playerDecisionPointUid:String?=null
){init{
    require(schemaVersion==1&&proposalUid.isNotBlank()&&campaignUid.isNotBlank()&&planUid.isNotBlank()&&providerUid.isNotBlank()&&modelUid.isNotBlank()&&intentFingerprint.isNotBlank())
    require(nodeProposals.map{it.nodeUid}.distinct().size==nodeProposals.size)
    require(proposedClaims.map{it.claimUid}.distinct().size==proposedClaims.size&&mechanicsEffects.map{it.effectUid}.distinct().size==mechanicsEffects.size)
    require(requestedPlayerVolitionalActionUids.none{it.isBlank()}&&playerDecisionPointUid?.isBlank()!=true)
}}

sealed interface GmProposalValidationResult{
    data class Accepted(val candidate:GmProposalCandidate):GmProposalValidationResult
    data class Rejected(val reasonUids:List<String>):GmProposalValidationResult{init{require(reasonUids.isNotEmpty())}}
}

class StructuredGmProposalValidator{
    fun validate(candidate:GmProposalCandidate,plan:CanonicalTurnPlan):GmProposalValidationResult{
        val reasons=linkedSetOf<String>()
        if(candidate.campaignUid!=plan.campaignUid)reasons+="CROSS_CAMPAIGN_PROPOSAL"
        if(candidate.planUid!=plan.planUid)reasons+="PLAN_IDENTITY_MISMATCH"
        if(candidate.intentFingerprint!=plan.intent.canonicalFingerprint())reasons+="INTENT_FINGERPRINT_MISMATCH"
        val executable=plan.steps.filter{it.matchState in setOf(CapabilityMatchState.EXACT,CapabilityMatchState.COMPOSED,CapabilityMatchState.GENERIC)}
        val plannedNodes=plan.steps.map{it.nodeUid}.toSet()
        if(candidate.nodeProposals.any{it.nodeUid !in plannedNodes})reasons+="UNPLANNED_NODE_OUTCOME"
        if(executable.any{step->candidate.nodeProposals.none{it.nodeUid==step.nodeUid}})reasons+="MISSING_NODE_OUTCOME"
        candidate.nodeProposals.forEach{nodeProposal->
            val intentNode=plan.intent.nodes.singleOrNull{it.nodeUid==nodeProposal.nodeUid}
            if(intentNode!=null){
                if(nodeProposal.actor!=plan.intent.actor)reasons+="ACTOR_PRESERVATION_VIOLATION:${nodeProposal.nodeUid}"
                val semantic=intentNode.semanticAction.canonicalActionUid?:intentNode.semanticAction.semanticFamilyUid
                if(nodeProposal.actionSemanticUid!=semantic)reasons+="ACTION_PRESERVATION_VIOLATION:${nodeProposal.nodeUid}"
                if(nodeProposal.modality!=intentNode.modality)reasons+="MODALITY_PRESERVATION_VIOLATION:${nodeProposal.nodeUid}"
                val intendedTargets=intentNode.participants.mapNotNull{participant->participant.referenceUid?.let{uid->plan.intent.references.singleOrNull{it.referenceUid==uid}?.resolvedProjectedRef}}
                if(nodeProposal.targetProjectedRefs!=intendedTargets)reasons+="TARGET_PRESERVATION_VIOLATION:${nodeProposal.nodeUid}"
                val failedDependencies=intentNode.dependencies.mapNotNull{dependency->candidate.nodeProposals.singleOrNull{it.nodeUid==dependency.predecessorNodeUid}}
                    .filter{it.outcomeState !in setOf(GmNodeOutcomeState.PROPOSED_SUCCESS)}
                if(failedDependencies.isNotEmpty()&&nodeProposal.outcomeState==GmNodeOutcomeState.PROPOSED_SUCCESS)reasons+="FAILED_PREREQUISITE_MATERIALIZED:${nodeProposal.nodeUid}"
            }
        }
        if(candidate.requestedPlayerVolitionalActionUids.isNotEmpty())reasons+="AI_REQUESTED_PLAYER_VOLITION"
        if(candidate.proposedClaims.any{it.nodeUid !in plannedNodes})reasons+="UNPLANNED_CLAIM_NODE"
        candidate.mechanicsEffects.forEach{effect->
            val step=plan.steps.singleOrNull{it.nodeUid==effect.nodeUid}
            if(step==null)reasons+="UNPLANNED_EFFECT_NODE"
            else if(step.sideEffectClass!=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT)reasons+="EFFECT_FOR_READ_ONLY_CAPABILITY"
            else if(step.mechanicsOwnerUid==null||step.mechanicsOwnerUid!=effect.mechanicsOwnerUid)reasons+="MECHANICS_OWNER_MISMATCH"
        }
        if(candidate.nodeProposals.any{it.playerFacingSummary.length>4_096})reasons+="SUMMARY_LIMIT"
        if(candidate.narrativeBlueprint.beatUids.size>64)reasons+="NARRATIVE_BEAT_LIMIT"
        return if(reasons.isEmpty())GmProposalValidationResult.Accepted(candidate) else GmProposalValidationResult.Rejected(reasons.sorted())
    }
}

data class MechanicsResolutionContext(
    val campaignUid:String,
    val plan:CanonicalTurnPlan,
    val context:BudgetedCanonicalContext,
    val stagedEffects:List<VerifiedMechanicsEffect> = emptyList()
){init{require(campaignUid==plan.campaignUid&&context.candidate.plan.planUid==plan.planUid&&context.safeForAi)}}

data class VerifiedMechanicsEffect(
    val effectUid:String,
    val nodeUid:String,
    val mechanicsOwnerUid:String,
    val effectKindUid:String,
    val canonicalPayload:Map<String,String>,
    val proofUid:String,
    val deterministicInputFingerprint:String=proofUid,
    val deterministicOutputFingerprint:String=proofUid,
    val candidateChangeSetFingerprint:String?=null
){init{
    require(effectUid.isNotBlank()&&nodeUid.isNotBlank()&&mechanicsOwnerUid.isNotBlank()&&effectKindUid.isNotBlank()&&proofUid.isNotBlank())
    require(deterministicInputFingerprint.isNotBlank()&&deterministicOutputFingerprint.isNotBlank()&&candidateChangeSetFingerprint?.isBlank()!=true)
}}

sealed interface MechanicsEffectResolution{
    data class Verified(val effect:VerifiedMechanicsEffect):MechanicsEffectResolution
    data class Rejected(val reasonUid:String):MechanicsEffectResolution{init{require(reasonUid.isNotBlank())}}
}

fun interface MechanicsRuleResolver{
    /** Pure legality/mechanics resolution. It returns proposed canonical material and has no commit authority. */
    fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext):MechanicsEffectResolution
}

class MechanicsResolverRegistry private constructor(resolvers:Map<String,MechanicsRuleResolver>){
    private val resolvers=resolvers.toMap()
    init{require(this.resolvers.keys.none{it.isBlank()})}
    fun resolver(ownerUid:String)=resolvers[ownerUid]
    companion object{fun fromCompositionRoot(resolvers:Map<String,MechanicsRuleResolver>)=MechanicsResolverRegistry(resolvers)}
}

data class ResolvedGmProposal(
    val candidate:GmProposalCandidate,
    val verifiedEffects:List<VerifiedMechanicsEffect>
){
    val campaignUid:String get()=candidate.campaignUid
    init{
        require(verifiedEffects.map{it.effectUid}.distinct().size==verifiedEffects.size)
        require(candidate.mechanicsEffects.map{it.effectUid}.toSet()==verifiedEffects.map{it.effectUid}.toSet())
    }
}

sealed interface MechanicsPipelineResult{
    data class Resolved(val proposal:ResolvedGmProposal):MechanicsPipelineResult
    data class Rejected(val reasonUids:List<String>):MechanicsPipelineResult{init{require(reasonUids.isNotEmpty())}}
}

class MechanicsResolutionEngine(private val registry:MechanicsResolverRegistry){
    fun resolve(candidate:GmProposalCandidate,context:MechanicsResolutionContext):MechanicsPipelineResult{
        val verified=mutableListOf<VerifiedMechanicsEffect>();val reasons=linkedSetOf<String>()
        val nodeOrder=context.plan.steps.mapIndexed{index,step->step.nodeUid to index}.toMap()
        candidate.mechanicsEffects.sortedWith(compareBy<MechanicsEffectRequest>{nodeOrder[it.nodeUid]?:Int.MAX_VALUE}.thenBy{it.effectUid}).forEach{effect->
            val resolver=registry.resolver(effect.mechanicsOwnerUid)
            if(resolver==null)reasons+="MECHANICS_OWNER_NOT_REGISTERED:${effect.mechanicsOwnerUid}"
            else when(val result=resolver.resolve(effect,context.copy(stagedEffects=verified.toList()))){
                is MechanicsEffectResolution.Verified->{
                    if(result.effect.effectUid!=effect.effectUid||result.effect.nodeUid!=effect.nodeUid||result.effect.mechanicsOwnerUid!=effect.mechanicsOwnerUid)reasons+="MECHANICS_PROOF_IDENTITY_MISMATCH:${effect.effectUid}"
                    else verified+=result.effect
                }
                is MechanicsEffectResolution.Rejected->reasons+="${effect.effectUid}:${result.reasonUid}"
            }
        }
        return if(reasons.isEmpty())MechanicsPipelineResult.Resolved(ResolvedGmProposal(candidate,verified)) else MechanicsPipelineResult.Rejected(reasons.sorted())
    }
}

class GmConsistencyValidator{
    fun rejectionReasons(proposal:ResolvedGmProposal,plan:CanonicalTurnPlan):List<String>{
        val reasons=linkedSetOf<String>()
        val byNode=plan.steps.associateBy{it.nodeUid}
        proposal.verifiedEffects.forEach{effect->
            val step=byNode[effect.nodeUid]
            if(step?.mechanicsOwnerUid!=effect.mechanicsOwnerUid)reasons+="VERIFIED_EFFECT_OWNER_DIVERGENCE:${effect.effectUid}"
            val nodeOutcome=proposal.candidate.nodeProposals.singleOrNull{it.nodeUid==effect.nodeUid}
            if(nodeOutcome?.outcomeState!=GmNodeOutcomeState.PROPOSED_SUCCESS)reasons+="EFFECT_WITHOUT_SUCCESSFUL_NODE:${effect.effectUid}"
            val canonicalTarget=effect.canonicalPayload["target_uid"]
            val actorUid=plan.intent.actor.actorUid
            if(canonicalTarget!=null&&canonicalTarget!=actorUid&&nodeOutcome?.targetProjectedRefs?.none{it.uid==canonicalTarget}==true)reasons+="MECHANICS_TARGET_DIVERGENCE:${effect.effectUid}"
        }
        proposal.candidate.proposedClaims.groupBy{Triple(it.nodeUid,it.subjectProjectedUid,it.predicateUid)}.values
            .filter{claims->claims.map{it.valueCanonical}.distinct().size>1}.forEach{reasons+="CONTRADICTORY_PROPOSED_CLAIMS:${it.first().nodeUid}"}
        if(proposal.candidate.narrativeBlueprint.beatUids.any{it.startsWith("FACT:")})reasons+="NARRATIVE_BLUEPRINT_ASSERTS_FACT"
        return reasons.sorted()
    }
}

class CounterfactualGuard{
    fun rejectionReasons(proposal:ResolvedGmProposal,context:BudgetedCanonicalContext):List<String>{
        val visibleRecords=context.includedSegments.flatMap{it.records}.map{it.record.recordUid}.toSet()
        val playerClaims=context.candidate.plan.intent.playerContextClaims.map{it.claimUid}.toSet()
        val projectedSubjects=buildSet{
            context.candidate.plan.intent.references.mapNotNullTo(this){it.resolvedProjectedRef?.uid}
            context.includedSegments.flatMap{it.records}.flatMap{record->record.record.values.values}.forEach{value->if(value is String)add(value)}
        }
        val reasons=linkedSetOf<String>()
        proposal.candidate.proposedClaims.forEach{claim->
            if(claim.supportingRecordUids.any{it !in visibleRecords})reasons+="CLAIM_SUPPORT_OUTSIDE_CONTEXT:${claim.claimUid}"
            if(claim.claimKind==ProposedClaimKind.PROJECTED_FACT_CONCLUSION&&claim.supportingRecordUids.isEmpty())reasons+="UNSUPPORTED_FACT_CLAIM:${claim.claimUid}"
            if(claim.claimKind==ProposedClaimKind.PROJECTED_FACT_CONCLUSION&&claim.supportingPlayerClaimUids.isNotEmpty())reasons+="PLAYER_ASSERTION_PROMOTED:${claim.claimUid}"
            if(claim.claimKind==ProposedClaimKind.PLAYER_ASSERTION&&(claim.supportingPlayerClaimUids.isEmpty()||claim.supportingPlayerClaimUids.any{it !in playerClaims}))reasons+="PLAYER_ASSERTION_UNBOUND:${claim.claimUid}"
            if(claim.epistemicBasis==ClaimEpistemicBasis.HOLDER_BELIEF&&claim.claimKind==ProposedClaimKind.PROJECTED_FACT_CONCLUSION)reasons+="BELIEF_PROMOTED_TO_FACT:${claim.claimUid}"
            if(claim.epistemicBasis==ClaimEpistemicBasis.NARRATIVE_ONLY&&claim.claimKind!=ProposedClaimKind.NARRATIVE_COLOR)reasons+="NARRATIVE_PROMOTED_TO_FACT:${claim.claimUid}"
            if(claim.temporalPosition in setOf(ClaimTemporalPosition.FUTURE_PLAN,ClaimTemporalPosition.PREDICTED_OUTCOME,ClaimTemporalPosition.COUNTERFACTUAL)&&claim.claimKind==ProposedClaimKind.PROJECTED_FACT_CONCLUSION)reasons+="FUTURE_OR_COUNTERFACTUAL_PROMOTED:${claim.claimUid}"
            if(claim.campaignDivergenceEvidenceUids.isNotEmpty()&&claim.supportingRecordUids.isEmpty())reasons+="DIVERGENCE_CLAIM_WITHOUT_PROJECTED_SUPPORT:${claim.claimUid}"
            if(claim.subjectProjectedUid!=null&&claim.subjectProjectedUid !in projectedSubjects)reasons+="CLAIM_SUBJECT_OUTSIDE_CONTEXT:${claim.claimUid}"
        }
        proposal.verifiedEffects.forEach{effect->
            if(effect.canonicalPayload.keys.any{it.contains("narrative",ignoreCase=true)})reasons+="NARRATIVE_AS_MECHANICS:${effect.effectUid}"
        }
        proposal.candidate.mechanicsEffects.filter{it.targetProjectedRef!=null&&it.targetProjectedRef.uid !in projectedSubjects}.forEach{reasons+="EFFECT_TARGET_OUTSIDE_CONTEXT:${it.effectUid}"}
        return reasons.sorted()
    }
}

sealed interface GmProposalEvaluation{
    data class Accepted(val proposal:ResolvedGmProposal):GmProposalEvaluation
    data class Rejected(val reasonUids:List<String>,val mechanicsAnchor:List<VerifiedMechanicsEffect> = emptyList()):GmProposalEvaluation{init{require(reasonUids.isNotEmpty())}}
}

class GmProposalEvaluator(
    private val structuredValidator:StructuredGmProposalValidator,
    private val mechanicsEngine:MechanicsResolutionEngine,
    private val consistencyValidator:GmConsistencyValidator=GmConsistencyValidator(),
    private val counterfactualGuard:CounterfactualGuard=CounterfactualGuard(),
    private val candidateStateConsistency:CandidateStateConsistencyPort=CandidateStateConsistencyPort.NONE
){
    fun evaluate(candidate:GmProposalCandidate,request:AiGmProposalRequest,mechanicsAnchor:List<VerifiedMechanicsEffect> = emptyList()):GmProposalEvaluation{
        when(val structural=structuredValidator.validate(candidate,request.plan)){
            is GmProposalValidationResult.Rejected->return GmProposalEvaluation.Rejected(structural.reasonUids)
            is GmProposalValidationResult.Accepted->Unit
        }
        val resolved=if(mechanicsAnchor.isEmpty())when(val mechanics=mechanicsEngine.resolve(candidate,MechanicsResolutionContext(candidate.campaignUid,request.plan,request.context))){
            is MechanicsPipelineResult.Rejected->return GmProposalEvaluation.Rejected(mechanics.reasonUids)
            is MechanicsPipelineResult.Resolved->mechanics.proposal
        }else{
            val requested=candidate.mechanicsEffects.map{it.effectUid}.toSet()
            if(requested!=mechanicsAnchor.map{it.effectUid}.toSet())return GmProposalEvaluation.Rejected(listOf("REPAIR_MECHANICS_ENTITLEMENT_CHANGED"),mechanicsAnchor)
            ResolvedGmProposal(candidate,mechanicsAnchor)
        }
        val reasons=(consistencyValidator.rejectionReasons(resolved,request.plan)+candidateStateConsistency.rejectionReasons(resolved,request)+counterfactualGuard.rejectionReasons(resolved,request.context)).distinct().sorted()
        return if(reasons.isEmpty())GmProposalEvaluation.Accepted(resolved) else GmProposalEvaluation.Rejected(reasons,resolved.verifiedEffects)
    }
}

data class ProposalRepairPolicy(val maxAttempts:Int=2){init{require(maxAttempts in 0..3)}}
data class ProposalRepairResult(val evaluation:GmProposalEvaluation,val attempts:Int,val terminalReasonUid:String)

class BoundedProposalRepair(private val evaluator:GmProposalEvaluator,private val policy:ProposalRepairPolicy=ProposalRepairPolicy()){
    fun evaluateAndRepair(provider:AiProvider,request:AiGmProposalRequest,candidate:GmProposalCandidate,cancellation:AiCancellationSignal):ProposalRepairResult{
        var current=candidate;var evaluation=evaluator.evaluate(current,request);var attempts=0
        val originalMechanicsRequests=candidate.mechanicsEffects.associateBy{it.effectUid}
        var mechanicsAnchor=(evaluation as? GmProposalEvaluation.Rejected)?.mechanicsAnchor.orEmpty()
        while(evaluation is GmProposalEvaluation.Rejected&&attempts<policy.maxAttempts&&!cancellation.isCancelled()){
            val repair=provider.repair(AiRepairRequest("${request.requestUid}:REPAIR:${attempts+1}",request,current,evaluation.reasonUids,attempts+1),cancellation)
            if(repair is AiProviderResult.Failure)return ProposalRepairResult(evaluation,attempts,"REPAIR_PROVIDER_FAILURE:${repair.reasonUid}")
            current=(repair as AiProviderResult.Success).value;attempts++
            val changedMechanics=current.mechanicsEffects.associateBy{it.effectUid}!=originalMechanicsRequests
            evaluation=when{
                current.providerUid!=provider.capabilities.providerUid||current.modelUid!=provider.capabilities.modelUid->
                    GmProposalEvaluation.Rejected(listOf("PROVIDER_PROVENANCE_MISMATCH"),mechanicsAnchor)
                changedMechanics&&mechanicsAnchor.isNotEmpty()->
                    GmProposalEvaluation.Rejected(listOf("REPAIR_REROLL_OR_ENTITLEMENT_CHANGE"),mechanicsAnchor)
                else->evaluator.evaluate(current,request,mechanicsAnchor)
            }
            if(mechanicsAnchor.isEmpty())mechanicsAnchor=(evaluation as? GmProposalEvaluation.Rejected)?.mechanicsAnchor.orEmpty()
        }
        val terminal=when{evaluation is GmProposalEvaluation.Accepted->"PROPOSAL_ACCEPTED";cancellation.isCancelled()->"CANCELLED";else->"REPAIR_LIMIT"}
        return ProposalRepairResult(evaluation,attempts,terminal)
    }
}
