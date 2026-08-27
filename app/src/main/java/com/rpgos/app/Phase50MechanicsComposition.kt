package com.rpgos.app

import java.security.MessageDigest

sealed interface MechanicalEffectMaterializationResult{
    data class Materialized(val changes:List<PlayerDomainChange>,val eventIntents:List<PlayerEventIntent>):MechanicalEffectMaterializationResult
    data class Rejected(val reasonUid:String):MechanicalEffectMaterializationResult
}

/**
 * Pure routing layer. It maps mechanics effects to existing domain-owned PlayerChange payloads;
 * it never writes and never drops an unknown effect.
 */
object MechanicalEffectMaterializer{
    fun materialize(effect:VerifiedMechanicsCommandEffect):MechanicalEffectMaterializationResult{
        val kind=effect.effectKindUid.substringAfterLast(':').uppercase()
        val change=when(kind){
            "RESOURCE_DELTA","RESOURCE","HEALTH_DELTA","DAMAGE_HP","HEALING","RESTORATION"->{
                if(effect.magnitude==0L)return rejected(effect,"ZERO_RESOURCE_EFFECT")
                val resource=effect.canonicalPayload["resource_uid"]
                    ?:if(kind in setOf("HEALTH_DELTA","DAMAGE_HP","HEALING","RESTORATION"))"HEALTH" else null
                    ?:return rejected(effect,"RESOURCE_UID_REQUIRED")
                change(effect,PlayerChangeKinds.RESOURCE,ResourceChange(effect.target,resource,ExactLongDelta.of(effect.magnitude)))
            }
            "CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->{
                val condition=effect.canonicalPayload["condition_uid"]?:return rejected(effect,"CONDITION_UID_REQUIRED")
                val operation=when(effect.canonicalPayload["operation"]?.uppercase()?:if(effect.magnitude<0)"REMOVE" else "ADD"){
                    "ADD","APPLY"->ConditionOperation.ADD;"REMOVE","CLEAR"->ConditionOperation.REMOVE
                    else->return rejected(effect,"INVALID_CONDITION_OPERATION")
                }
                change(effect,PlayerChangeKinds.CONDITION,ConditionChange(effect.target,condition,operation))
            }
            "OWNERSHIP_DESTRUCTION","ASSET_DESTRUCTION","DESTROY"->{
                val assetKind=effect.canonicalPayload["asset_kind_uid"]?:return rejected(effect,"ASSET_KIND_UID_REQUIRED")
                change(effect,PlayerChangeKinds.ASSET,AssetChange(OwnedAssetRef(assetKind,effect.target.uid),"DESTROYED"))
            }
            "WOUND"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_WOUND_REQUIRED")
                change(effect,PlayerChangeKinds.WOUND,WoundChange(effect.target,ExactLongDelta.of(effect.magnitude),effect.canonicalPayload["severity_uid"]))
            }
            "MOVEMENT","DISPLACEMENT"->{
                if(effect.magnitude==0L)return rejected(effect,"ZERO_SPATIAL_EFFECT")
                change(effect,PlayerChangeKinds.SPATIAL,SpatialChange(effect.target,effect.magnitude))
            }
            "EQUIPMENT","EQUIPMENT_DAMAGE"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_EQUIPMENT_DAMAGE_REQUIRED")
                val component=effect.canonicalPayload["component_uid"]?:"EQUIPPED_ARMOR"
                change(effect,PlayerChangeKinds.EQUIPMENT_INTEGRITY,EquipmentIntegrityChange(effect.target,component,ExactLongDelta.of(effect.magnitude)))
            }
            "STRUCTURE","STRUCTURE_DAMAGE"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_STRUCTURE_DAMAGE_REQUIRED")
                change(effect,PlayerChangeKinds.STRUCTURE_INTEGRITY,StructureIntegrityChange(effect.target,effect.canonicalPayload["component_uid"],ExactLongDelta.of(effect.magnitude)))
            }
            "MORALE","COHESION","FORMATION","ENVIRONMENT","PERSISTENT_EFFECT"->{
                if(effect.magnitude==0L)return rejected(effect,"ZERO_TRACK_EFFECT")
                val track=when(kind){
                    "PERSISTENT_EFFECT"->effect.canonicalPayload["effect_uid"]?:return rejected(effect,"EFFECT_UID_REQUIRED")
                    "ENVIRONMENT"->effect.canonicalPayload["environment_track_uid"]?:"ENVIRONMENT"
                    else->kind
                }
                change(effect,PlayerChangeKinds.MECHANICAL_TRACK,MechanicalTrackChange(effect.target,track,ExactLongDelta.of(effect.magnitude)))
            }
            "AGGREGATE_ELIMINATION"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_AGGREGATE_COUNT_REQUIRED")
                change(effect,PlayerChangeKinds.AGGREGATE_POPULATION,AggregatePopulationChange(effect.target,eliminatedDelta=effect.magnitude))
            }
            "AGGREGATE_INJURY"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_AGGREGATE_COUNT_REQUIRED")
                change(effect,PlayerChangeKinds.AGGREGATE_POPULATION,AggregatePopulationChange(effect.target,woundedDelta=effect.magnitude))
            }
            "AGGREGATE_CONDITION"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_AGGREGATE_COUNT_REQUIRED")
                val condition=effect.canonicalPayload["condition_uid"]?:return rejected(effect,"CONDITION_UID_REQUIRED")
                change(effect,PlayerChangeKinds.AGGREGATE_POPULATION,AggregatePopulationChange(effect.target,conditionUid=condition,conditionAffectedDelta=effect.magnitude))
            }
            else->return rejected(effect,"UNSUPPORTED_EFFECT_KIND:$kind")
        }
        val event=PlayerEventIntent.create(
            eventIntentUid="RPGOS-MECHANICS-EVENT:${safeUid(effect.effectUid)}",
            eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,
            actorRef=null,
            targetRefs=listOf(effect.target),
            causalChangeUids=listOf(change.changeUid),
            payload=DomainEffectEventIntentPayload(effect.target,effect.effectKindUid)
        )
        return MechanicalEffectMaterializationResult.Materialized(listOf(change),listOf(event))
    }

    private fun change(effect:VerifiedMechanicsCommandEffect,kind:String,payload:PlayerDomainChangePayload)=PlayerDomainChange.create(
        changeUid="RPGOS-MECHANICS-CHANGE:${safeUid(effect.effectUid)}",changeKindUid=kind,payload=payload,sourceRuleUid=effect.proofUid
    )
    private fun rejected(effect:VerifiedMechanicsCommandEffect,reason:String)=MechanicalEffectMaterializationResult.Rejected("${effect.effectUid}:$reason")
    private fun safeUid(value:String)=sha256(value).take(32)
}

/** Stateless trusted extension registered in the existing PlayerDomainEngine. */
internal class ProductionVerifiedMechanicsComponent:PlayerResolutionComponent<ApplyVerifiedMechanicsCommandPayload>(
    PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,ApplyVerifiedMechanicsCommandPayload::class,
    "RPGOS-COMPONENT:VERIFIED-UNIVERSAL-MECHANICS","2"
){
    override fun resolve(command:PlayerCommand<ApplyVerifiedMechanicsCommandPayload>,context:PlayerResolutionContext):PlayerResolutionComponentOutcome{
        val changes=mutableListOf<PlayerDomainChange>();val events=mutableListOf<PlayerEventIntent>()
        command.payload.effects.forEach{effect->
            when(val result=MechanicalEffectMaterializer.materialize(effect)){
                is MechanicalEffectMaterializationResult.Rejected->return PlayerResolutionComponentOutcome.Rejected(
                    PlayerResolutionRejection.create(PlayerResolutionRejectionReason.DOMAIN_REJECTED,listOf(effect.target),result.reasonUid)
                )
                is MechanicalEffectMaterializationResult.Materialized->{changes+=result.changes;events+=result.eventIntents}
            }
        }
        if(changes.isEmpty())return PlayerResolutionComponentOutcome.Rejected(
            PlayerResolutionRejection.create(PlayerResolutionRejectionReason.DOMAIN_REJECTED,detailUid="EMPTY_MECHANICS_MATERIALIZATION")
        )
        return PlayerResolutionComponentOutcome.Resolved(PlayerResolutionDraft.create(changes=changes,eventIntents=events))
    }
}

fun interface PlayerResolutionContextFactory{
    fun create(command:PlayerCommand<ApplyVerifiedMechanicsCommandPayload>):PlayerResolutionContext
}

/**
 * Production implementation of the Phase54 seam. All nodes are staged in plan order and admitted
 * together as one command, so TurnTransaction provides full all-or-nothing semantics.
 */
class ProductionCanonicalMutationAssembler(
    private val engine:PlayerDomainEngine,
    private val contexts:PlayerResolutionContextFactory
):CanonicalMutationAssembler{
    override fun assemble(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal):CanonicalCampaignMutationProposal?{
        if(request.campaignUid!=plan.campaignUid||request.campaignUid!=proposal.campaignUid)return null
        val nodeOrder=plan.steps.mapIndexed{index,step->step.nodeUid to index}.toMap()
        if(proposal.verifiedEffects.any{it.nodeUid !in nodeOrder})return null
        val requested=proposal.candidate.mechanicsEffects.associateBy{it.effectUid}
        val effects=proposal.verifiedEffects.sortedWith(compareBy<VerifiedMechanicsEffect>{nodeOrder[it.nodeUid]?:Int.MAX_VALUE}.thenBy{it.effectUid}).flatMap{verified->
            val source=requested[verified.effectUid]?:return null
            val proposedTarget=source.targetProjectedRef?:return null
            val targetKind=verified.canonicalPayload["target_kind_uid"]
            val targetUid=verified.canonicalPayload["target_uid"]
            if((targetKind==null)!=(targetUid==null))return null
            val target=if(targetKind!=null)DomainRef(targetKind,targetUid!!) else proposedTarget
            val magnitude=verified.canonicalPayload["magnitude"]?.toLongOrNull()?:return null
            val areaCount=verified.canonicalPayload["area_target_count"]?.toIntOrNull()?:0
            if(areaCount !in 0..256)return null
            val specifications=if(areaCount==0)listOf(Triple(target,magnitude,verified.effectKindUid)) else (0 until areaCount).map{index->
                val kind=verified.canonicalPayload["area_target_${index}_kind_uid"]?:return null
                val uid=verified.canonicalPayload["area_target_${index}_uid"]?:return null
                val areaMagnitude=verified.canonicalPayload["area_target_${index}_magnitude"]?.toLongOrNull()?:return null
                val effectKind=verified.canonicalPayload["area_target_${index}_effect_kind_uid"]?:return null
                Triple(DomainRef(kind,uid),areaMagnitude,effectKind)
            }
            val commonPayload=verified.canonicalPayload.filterKeys{!it.startsWith("area_target_")}
            specifications.mapIndexed{index,(canonicalTarget,canonicalMagnitude,canonicalKind)->
                val suffix=if(areaCount==0)"" else ":AREA:$index"
                VerifiedMechanicsCommandEffect(
                    verified.effectUid+suffix,verified.nodeUid,verified.mechanicsOwnerUid,canonicalKind,canonicalTarget,canonicalMagnitude,
                    commonPayload+mapOf("target_kind_uid" to canonicalTarget.kindUid,"target_uid" to canonicalTarget.uid,"magnitude" to canonicalMagnitude.toString()),
                    verified.proofUid+suffix,mechanicsFingerprint(verified.deterministicInputFingerprint+suffix),mechanicsFingerprint(verified.deterministicOutputFingerprint+"|$canonicalTarget|$canonicalMagnitude")
                )
            }
        }
        if(effects.isEmpty())return null
        if(!validateDependencies(plan,proposal))return null
        val command=PlayerCommand(
            commandUid=request.commandUid,campaignUid=request.campaignUid,actor=request.actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload(plan.planUid,effects),
            provenance=CommandProvenance("RPGOS-PHASE54-CANONICAL-COMPOSER",proposal.candidate.proposalUid),
            causationUid=request.turnUid,correlationUid=request.requestUid,requestedEffectiveOrder=request.atOrder?:1L
        )
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit(request.campaignUid,engine,command,contexts.create(command))){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->null
        }
    }

    private fun validateDependencies(plan:CanonicalTurnPlan,proposal:ResolvedGmProposal):Boolean{
        val outcomes=proposal.candidate.nodeProposals.associateBy{it.nodeUid}
        return plan.steps.all{step->
            step.dependencyNodeUids.all{dependency->outcomes[dependency]?.outcomeState==GmNodeOutcomeState.PROPOSED_SUCCESS}&&
                (outcomes[step.nodeUid]?.outcomeState!=GmNodeOutcomeState.PROPOSED_SUCCESS||step.matchState in setOf(CapabilityMatchState.EXACT,CapabilityMatchState.COMPOSED,CapabilityMatchState.GENERIC))
        }
    }
}

internal fun productionMechanicsPlayerDomainEngine(
    worldRules:WorldRuleProviderRegistry=WorldRuleProviderRegistry.empty(),
    worldPackAuthority:WorldPackAuthorityResolver=WorldPackAuthoritySnapshot.empty()
)=PlayerDomainEngine(
    PlayerResolutionComponentRegistry.of(listOf(ProductionVerifiedMechanicsComponent())),
    worldRuleRegistry=worldRules,worldPackAuthority=worldPackAuthority
)

private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
private fun mechanicsFingerprint(value:String)=sha256(value)
