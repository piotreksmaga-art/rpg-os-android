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
        if(kind=="WORLD_ELEMENT_MATERIALIZE")return materializeWorldElement(effect)
        if(kind=="NARRATIVE_EVENT")return materializeNarrativeEvent(effect)
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
            "INVENTORY_ADD","INVENTORY_REMOVE"->{
                val itemUid=effect.canonicalPayload["item_instance_uid"]?:return rejected(effect,"ITEM_INSTANCE_UID_REQUIRED")
                val delta=if(kind=="INVENTORY_ADD")1L else -1L
                val materialization=if(kind=="INVENTORY_ADD"){
                    val definitionUid=effect.canonicalPayload["item_definition_uid"]?:return rejected(effect,"ITEM_DEFINITION_UID_REQUIRED")
                    val worldPackUid=effect.canonicalPayload["item_world_pack_uid"]?:return rejected(effect,"ITEM_WORLD_PACK_UID_REQUIRED")
                    val itemKey=effect.canonicalPayload["item_key"]?:return rejected(effect,"ITEM_KEY_REQUIRED")
                    val displayName=effect.canonicalPayload["item_display_name"]?:return rejected(effect,"ITEM_DISPLAY_NAME_REQUIRED")
                    InventoryItemMaterialization(definitionUid,worldPackUid,itemKey,displayName,effect.canonicalPayload["item_category_uid"])
                }else null
                change(effect,PlayerChangeKinds.INVENTORY,InventoryChange(effect.target,itemUid,ExactLongDelta.of(delta),materialization))
            }
            "WOUND"->{
                if(effect.magnitude<=0L)return rejected(effect,"POSITIVE_WOUND_REQUIRED")
                change(effect,PlayerChangeKinds.WOUND,WoundChange(effect.target,ExactLongDelta.of(effect.magnitude),effect.canonicalPayload["severity_uid"]))
            }
            "MOVEMENT","DISPLACEMENT"->{
                if(effect.magnitude==0L)return rejected(effect,"ZERO_SPATIAL_EFFECT")
                change(effect,PlayerChangeKinds.SPATIAL,SpatialChange(effect.target,effect.magnitude))
            }
            "LOCATION_TRANSITION"->{
                val destinationKind=effect.canonicalPayload["destination_kind_uid"]?:return rejected(effect,"DESTINATION_KIND_REQUIRED")
                val destinationUid=effect.canonicalPayload["destination_uid"]?:return rejected(effect,"DESTINATION_UID_REQUIRED")
                change(effect,PlayerChangeKinds.SPATIAL,SpatialChange(effect.target,0,0,DomainRef(destinationKind,destinationUid)))
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
            "MORALE","COHESION","FORMATION","ENVIRONMENT","PERSISTENT_EFFECT","TRAINING","INTERACTION"->{
                if(effect.magnitude==0L)return rejected(effect,"ZERO_TRACK_EFFECT")
                val track=when(kind){
                    "PERSISTENT_EFFECT"->effect.canonicalPayload["effect_uid"]?:return rejected(effect,"EFFECT_UID_REQUIRED")
                    "ENVIRONMENT"->effect.canonicalPayload["environment_track_uid"]?:"ENVIRONMENT"
                    "TRAINING"->effect.canonicalPayload["track_uid"]?:"TRAINING:GENERAL"
                    "INTERACTION"->effect.canonicalPayload["track_uid"]?:"ACTION:GENERAL"
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

    private fun materializeWorldElement(effect:VerifiedMechanicsCommandEffect):MechanicalEffectMaterializationResult{
        fun field(key:String)=effect.canonicalPayload[key]?.takeIf(String::isNotBlank)
        val kind=field("world_base_kind")?:return rejected(effect,"WORLD_BASE_KIND_REQUIRED")
        if(kind!=effect.target.kindUid||runCatching{WorldElementBaseKind.valueOf(kind)}.isFailure)return rejected(effect,"WORLD_BASE_KIND_MISMATCH")
        val sourceClassification=field("source_classification")?:return rejected(effect,"WORLD_SOURCE_CLASSIFICATION_REQUIRED")
        if(runCatching{WorldEvidenceClassification.valueOf(sourceClassification)}.isFailure)return rejected(effect,"WORLD_SOURCE_CLASSIFICATION_INVALID")
        val ordered=buildList{
            add(CampaignWorldFacts.KIND to kind)
            add(CampaignWorldFacts.NAME to (field("display_name")?:return rejected(effect,"WORLD_DISPLAY_NAME_REQUIRED")))
            add(CampaignWorldFacts.CATEGORY to (field("category_uid")?:return rejected(effect,"WORLD_CATEGORY_REQUIRED")))
            field("parent_anchor_uid")?.let{add(CampaignWorldFacts.PARENT to it)}
            field("affordance_uids").orEmpty().split(',').filter(String::isNotBlank).distinct().sorted().forEach{add(CampaignWorldFacts.AFFORDANCE to it)}
            add(CampaignWorldFacts.TOPOLOGY to (field("topology_class_uid")?:return rejected(effect,"WORLD_TOPOLOGY_REQUIRED")))
            add(CampaignWorldFacts.SOURCE_CLASSIFICATION to sourceClassification)
            field("source_uri")?.let{add(CampaignWorldFacts.SOURCE_URI to it)}
            field("source_revision")?.let{add(CampaignWorldFacts.SOURCE_REVISION to it)}
            field("source_hash")?.let{add(CampaignWorldFacts.SOURCE_HASH to it)}
            add(CampaignWorldFacts.MATERIALIZATION_LEVEL to (field("materialization_level_uid")?:"PARTIAL"))
            add(CampaignWorldFacts.AUDIENCE_SCOPE to CampaignWorldAudience.PLAYER_VISIBLE)
        }
        val changes=ordered.mapIndexed{index,(predicate,value)->
            val suffix="$index|$predicate|$value"
            PlayerDomainChange.create(
                changeUid="RPGOS-WORLD-CHANGE:${safeUid(effect.effectUid+suffix)}",changeKindUid=PlayerChangeKinds.CAMPAIGN_TRUTH,
                payload=CampaignTruthChange("RPGOS-WORLD-TRUTH:${safeUid(effect.target.uid+suffix)}",TruthKind.FACT,effect.target.uid,predicate,value,null,null,null),
                sourceRuleUid=effect.proofUid
            )
        }
        val event=PlayerEventIntent.create(
            eventIntentUid="RPGOS-WORLD-EVENT:${safeUid(effect.effectUid)}",eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,
            actorRef=null,targetRefs=listOf(effect.target),causalChangeUids=changes.map{it.changeUid},payload=DomainEffectEventIntentPayload(effect.target,effect.effectKindUid)
        )
        return MechanicalEffectMaterializationResult.Materialized(changes,listOf(event))
    }

    private fun materializeNarrativeEvent(effect:VerifiedMechanicsCommandEffect):MechanicalEffectMaterializationResult{
        val predicate=effect.canonicalPayload["predicate_uid"]?:return rejected(effect,"NARRATIVE_PREDICATE_REQUIRED")
        if(predicate !in GmNarrativePredicates.ALLOWED)return rejected(effect,"NARRATIVE_PREDICATE_NOT_ALLOWED")
        if(predicate==GmNarrativePredicates.NPC_UTTERANCE&&effect.target.kindUid !in setOf("ACTOR","NPC"))
            return rejected(effect,"NPC_UTTERANCE_TARGET_NOT_ACTOR")
        val text=effect.canonicalPayload["narrative_text"]?.trim()?.trim('„','”','“','"')?.trim()?.takeIf{it.isNotBlank()&&it.length<=512}
            ?:return rejected(effect,"NARRATIVE_TEXT_INVALID")
        if(Regex("RPGOS-[A-Z0-9:_-]+|(?:EVENT|PROOF|TX|RECEIPT):[A-Za-z0-9:_-]+",RegexOption.IGNORE_CASE).containsMatchIn(text))
            return rejected(effect,"NARRATIVE_INTERNAL_TOKEN")
        val change=change(effect,PlayerChangeKinds.CAMPAIGN_TRUTH,CampaignTruthChange(
            truthUid="RPGOS-NARRATIVE-TRUTH:${safeUid(effect.effectUid+text)}",kind=TruthKind.NARRATIVE,
            subjectUid=effect.target.uid,predicate=predicate,objectValue=null,perspectiveUid=null,
            narrativeText=text,supersedesTruthUid=null
        ))
        val event=PlayerEventIntent.create(
            eventIntentUid="RPGOS-NARRATIVE-EVENT:${safeUid(effect.effectUid)}",eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,
            actorRef=effect.target,targetRefs=listOf(effect.target),causalChangeUids=listOf(change.changeUid),
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
):CanonicalMutationAssembler,CanonicalMutationAssemblyDiagnostics{
    @Volatile private var lastReasons:List<String> = emptyList()
    override fun lastAssemblyReasonUids()=lastReasons
    override fun assemble(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal):CanonicalCampaignMutationProposal?{
        lastReasons=emptyList()
        if(request.campaignUid!=plan.campaignUid||request.campaignUid!=proposal.campaignUid)return null
        val nodeOrder=plan.steps.mapIndexed{index,step->step.nodeUid to index}.toMap()
        if(proposal.verifiedEffects.any{it.nodeUid !in nodeOrder})return null
        val requested=proposal.candidate.mechanicsEffects.associateBy{it.effectUid}
        val providerEffects=proposal.verifiedEffects.sortedWith(compareBy<VerifiedMechanicsEffect>{nodeOrder[it.nodeUid]?:Int.MAX_VALUE}.thenBy{it.effectUid}).flatMap{verified->
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
        val successfulNodes=proposal.candidate.nodeProposals.filter{it.outcomeState==GmNodeOutcomeState.PROPOSED_SUCCESS}.map{it.nodeUid}.toSet()
        val narrativeSubjectUids=proposal.candidate.proposedClaims.asSequence()
            .filter{it.claimKind==ProposedClaimKind.NARRATIVE_COLOR}
            .mapNotNull{it.subjectProjectedUid}.toSet()
        val materializationEffects=plan.intent.references.mapNotNull{reference->
            val consumer=plan.intent.nodes.firstOrNull{node->node.nodeUid in successfulNodes&&node.participants.any{it.referenceUid==reference.referenceUid}}?:return@mapNotNull null
            val draft=LatentWorldReferenceCodec.decode(plan.campaignUid,reference)?:return@mapNotNull null
            val step=plan.steps.singleOrNull{it.nodeUid==consumer.nodeUid}?:return@mapNotNull null
            // Mentioning a latent concept in a read-only question must not create it in the
            // canonical world.  Materialise only for an admitted world effect, or when this
            // exact latent actor is the subject of accepted narrative evidence (for example a
            // newly introduced NPC actually replying).
            if(step.sideEffectClass!=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT&&draft.element.uid !in narrativeSubjectUids)
                return@mapNotNull null
            val fingerprint=draft.fingerprint()
            VerifiedMechanicsCommandEffect(
                effectUid="RPGOS-WORLD-MATERIALIZE:${draft.element.uid}",nodeUid=consumer.nodeUid,mechanicsOwnerUid="RPGOS-CORE:WORLD-MATERIALIZER",
                effectKindUid="WORLD_ELEMENT_MATERIALIZE",target=draft.element,magnitude=1,
                canonicalPayload=buildMap{
                    put("world_base_kind",draft.baseKind.name);put("display_name",draft.displayName);put("category_uid",draft.categoryUid)
                    draft.parentAnchorUid?.let{put("parent_anchor_uid",it)};put("affordance_uids",draft.affordanceUids.sorted().joinToString(","))
                    put("topology_class_uid",draft.topologyClassUid);put("source_classification",draft.sourceClassification.name)
                    draft.sourceUri?.let{put("source_uri",it)};draft.sourceRevision?.let{put("source_revision",it)};draft.sourceHash?.let{put("source_hash",it)}
                    put("materialization_level_uid",draft.materializationLevelUid);put("draft_fingerprint",fingerprint)
                    put("target_kind_uid",draft.element.kindUid);put("target_uid",draft.element.uid);put("magnitude","1")
                },proofUid="RPGOS-CORE:WORLD-MATERIALIZATION:$fingerprint",
                deterministicInputFingerprint=mechanicsFingerprint("${plan.intent.canonicalFingerprint()}|${reference.referenceUid}|$fingerprint"),
                deterministicOutputFingerprint=mechanicsFingerprint("${draft.element}|$fingerprint")
            )
        }.distinctBy{it.target}
        val projectedByUid=buildMap{
            put(plan.intent.actor.actorUid,DomainRef(plan.intent.actor.actorKindUid,plan.intent.actor.actorUid))
            plan.intent.references.mapNotNull{it.resolvedProjectedRef}.forEach{put(it.uid,it)}
        }
        val narrativeEffects=proposal.candidate.proposedClaims.filter{it.claimKind==ProposedClaimKind.NARRATIVE_COLOR}.map{claim->
            val target=claim.subjectProjectedUid?.let(projectedByUid::get)?:return null
            val fingerprint=mechanicsFingerprint("${plan.intent.canonicalFingerprint()}|${claim.claimUid}|${claim.predicateUid}|${claim.valueCanonical}")
            VerifiedMechanicsCommandEffect(
                // Claim UIDs such as CLAIM:N1:NPC_UTTERANCE are commonly reused by providers in
                // later turns.  Bind the canonical narrative event to this intent as well, so an
                // NPC repeating the same words is still a distinct historical utterance.
                effectUid="RPGOS-NARRATIVE-MATERIALIZE:${mechanicsFingerprint("${plan.intent.canonicalFingerprint()}|${claim.claimUid}").take(24)}",nodeUid=claim.nodeUid,
                mechanicsOwnerUid="RPGOS-CORE:NARRATIVE-MATERIALIZER",effectKindUid="NARRATIVE_EVENT",target=target,magnitude=1,
                canonicalPayload=mapOf("predicate_uid" to claim.predicateUid,"narrative_text" to claim.valueCanonical,
                    "target_kind_uid" to target.kindUid,"target_uid" to target.uid,"magnitude" to "1"),
                proofUid="RPGOS-CORE:NARRATIVE-MATERIALIZATION:$fingerprint",
                deterministicInputFingerprint=fingerprint,deterministicOutputFingerprint=mechanicsFingerprint("$target|${claim.valueCanonical}")
            )
        }
        // Multiple natural-language questions to the same actor may be represented as separate
        // intent nodes, but the canonical mechanical track has one key per actor/action.  Fold
        // those verified deltas before PlayerChangeSet validation; otherwise two legal QUERY
        // nodes become duplicate mutations of ACTION:QUERY in a single atomic change set.
        val effects=materializationEffects+narrativeEffects+coalesceInteractionEffects(providerEffects)
        if(effects.isEmpty())return null
        if(!validateDependencies(plan,proposal))return null
        val command=PlayerCommand(
            commandUid=request.commandUid,campaignUid=request.campaignUid,actor=request.actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload(plan.planUid,effects),
            provenance=CommandProvenance("RPGOS-PHASE54-CANONICAL-COMPOSER",proposal.candidate.proposalUid),
            causationUid=request.turnUid,correlationUid=request.requestUid,requestedEffectiveOrder=request.atOrder?:1L
        )
        var domainRejection:PlayerResolutionRejection?=null
        return when(val admission=CampaignMutationBoundary.resolveAndAdmit(request.campaignUid,engine,command,contexts.create(command)){domainRejection=it}){
            is CampaignMutationAdmission.Accepted->admission.proposal
            is CampaignMutationAdmission.Rejected->{
                lastReasons=buildList{
                    add(admission.reasonUid)
                    domainRejection?.let{rejection->
                        add(rejection.reason.reasonUid)
                        rejection.detailUid?.let(::add)
                        rejection.relatedRefs.sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid}).forEach{add("RELATED_REF:${it.kindUid}:${it.uid}")}
                    }
                }.distinct().sorted()
                null
            }
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

internal fun coalesceInteractionEffects(effects:List<VerifiedMechanicsCommandEffect>):List<VerifiedMechanicsCommandEffect>{
    val grouped=effects.groupBy{effect->
        if(effect.effectKindUid.substringAfterLast(':').uppercase()!="INTERACTION")"UNIQUE:${effect.effectUid}"
        else listOf(effect.effectKindUid,effect.target.kindUid,effect.target.uid,effect.canonicalPayload["track_uid"].orEmpty()).joinToString("|")
    }
    return grouped.values.map{group->
        if(group.size==1)group.single() else{
            val first=group.first();val last=group.last();val magnitude=group.fold(0L){sum,effect->Math.addExact(sum,effect.magnitude)}
            val identity=group.joinToString("|"){"${it.effectUid}:${it.proofUid}:${it.magnitude}"}
            first.copy(
                effectUid="RPGOS-CORE:COALESCED-INTERACTION:${mechanicsFingerprint(identity).take(24)}",
                nodeUid=last.nodeUid,magnitude=magnitude,
                canonicalPayload=first.canonicalPayload+("magnitude" to magnitude.toString())+("coalesced_node_uids" to group.joinToString(","){it.nodeUid}),
                proofUid="RPGOS-CORE:COALESCED-INTERACTION:${mechanicsFingerprint(identity)}",
                deterministicInputFingerprint=mechanicsFingerprint(group.joinToString("|"){it.deterministicInputFingerprint}),
                deterministicOutputFingerprint=mechanicsFingerprint(group.joinToString("|"){it.deterministicOutputFingerprint}+"|$magnitude")
            )
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
