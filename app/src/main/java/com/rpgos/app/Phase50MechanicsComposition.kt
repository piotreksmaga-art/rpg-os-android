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
        val sources=mechanicSourceActors(effect)
        val events=sources.map{source->PlayerEventIntent.create(
            eventIntentUid="RPGOS-MECHANICS-EVENT:${safeUid(if(sources.size==1)effect.effectUid else "${effect.effectUid}|$source")}",
            eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,
            actorRef=source,
            targetRefs=listOf(effect.target),
            causalChangeUids=listOf(change.changeUid),
            payload=DomainEffectEventIntentPayload(effect.target,effect.effectKindUid)
        )}
        return MechanicalEffectMaterializationResult.Materialized(listOf(change),events)
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
        val genesis=MechanicalActorGenesis.from(effect)?.let { body->PlayerDomainChange.create(
            changeUid="RPGOS-WORLD-BODY:${safeUid(effect.effectUid)}",changeKindUid=MECHANICAL_ACTOR_GENESIS_KIND,
            payload=body,sourceRuleUid=effect.proofUid) }
        val allChanges=changes+listOfNotNull(genesis)
        val event=PlayerEventIntent.create(
            eventIntentUid="RPGOS-WORLD-EVENT:${safeUid(effect.effectUid)}",eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,
            actorRef=null,targetRefs=listOf(effect.target),causalChangeUids=allChanges.map{it.changeUid},payload=DomainEffectEventIntentPayload(effect.target,effect.effectKindUid)
        )
        return MechanicalEffectMaterializationResult.Materialized(allChanges,listOf(event))
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
        command.payload.temporalState?.let { time ->
            if(time.campaignUid!=command.campaignUid)return PlayerResolutionComponentOutcome.Rejected(
                PlayerResolutionRejection.create(PlayerResolutionRejectionReason.DOMAIN_REJECTED,detailUid="P60:CROSS_CAMPAIGN_TIME"))
            changes+=PlayerDomainChange.create("RPGOS-TIME-CHANGE:${command.commandUid}",PHASE60_TIME_CHANGE_KIND,time,sourceRuleUid="RPGOS-P60:ACCEPTED_ACTION_TIME")
        }
        command.payload.npcBrains.forEach { brain ->
            if(brain.campaignUid!=command.campaignUid || brain.actor.uid==command.actor.actorUid)
                return PlayerResolutionComponentOutcome.Rejected(PlayerResolutionRejection.create(
                    PlayerResolutionRejectionReason.DOMAIN_REJECTED,detailUid="P61:INVALID_BRAIN_SUBJECT"))
            val uid="RPGOS-NPC-BRAIN:${command.commandUid}:${sha256(brain.actor.toString()).take(24)}:${brain.expectedVersion}"
            changes+=PlayerDomainChange.create(uid,NPC_BRAIN_CHANGE_KIND,brain,sourceRuleUid=brain.ruleUid)
            events+=PlayerEventIntent.create(eventIntentUid="EVENT:$uid",eventKindUid=PlayerEventIntentKinds.DOMAIN_EFFECT,
                actorRef=brain.actor,targetRefs=listOf(brain.actor),causalChangeUids=listOf(uid),
                payload=DomainEffectEventIntentPayload(brain.actor,NPC_BRAIN_CHANGE_KIND))
        }
        val memory=NpcActionMemory.materialize(command.campaignUid,command.commandUid,command.requestedEffectiveOrder,
            command.payload.effects,command.payload.npcBrains)
        changes+=memory.changes;events+=memory.events
        val communication=NpcCommunicationMemory.materialize(command.campaignUid,command.commandUid,command.requestedEffectiveOrder,command.payload.effects)
        changes+=communication.changes;events+=communication.events
        val sensations=NpcConsequenceObservation.materialize(command.campaignUid,command.commandUid,command.requestedEffectiveOrder,command.payload.effects)
        changes+=sensations.changes;events+=sensations.events
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
        return assembleTimed(request,plan,proposal,null)
    }

    /** Time is resolved before admission, never appended to an already sealed proposal. */
    fun assembleTimed(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal,time:TemporalStateChange?):CanonicalCampaignMutationProposal?{
        val effects=prepareEffects(request,plan,proposal)?:return null
        return admitEffects(request,plan.planUid,proposal.candidate.proposalUid,effects,time)
    }

    /** Keep node identities until temporal execution decides which effects actually happened. */
    internal fun prepareEffects(request:ChatTurnRequest,plan:CanonicalTurnPlan,proposal:ResolvedGmProposal):List<VerifiedMechanicsCommandEffect>?{
        lastReasons=emptyList()
        if(request.campaignUid!=plan.campaignUid||request.campaignUid!=proposal.campaignUid)return null
        val nodeOrder=plan.steps.mapIndexed{index,step->step.nodeUid to index}.toMap()
        if(proposal.verifiedEffects.any{it.nodeUid !in nodeOrder})return null
        val requested=proposal.candidate.mechanicsEffects.associateBy{it.effectUid}
        val providerEffects=proposal.verifiedEffects.sortedWith(compareBy<VerifiedMechanicsEffect>{nodeOrder[it.nodeUid]?:Int.MAX_VALUE}.thenBy{it.effectUid}).flatMap{verified->
            val source=requested[verified.effectUid]?:return null
            val proposedTarget=source.targetProjectedRef?:return null
            canonicalMechanicsCommandEffects(verified,proposedTarget)?:return null
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
                canonicalPayload=draft.materializationPayload(),proofUid="RPGOS-CORE:WORLD-MATERIALIZATION:$fingerprint",
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
            val effect=VerifiedMechanicsCommandEffect(
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
            val node=plan.intent.nodes.single{it.nodeUid==claim.nodeUid}
            if(claim.predicateUid==GmNarrativePredicates.NPC_UTTERANCE && isConversationNode(node) &&
                claim.nodeUid in successfulNodes)NpcCommunicationMemory.annotate(effect,plan,node,plan.intent.nodes.filter{
                    it.nodeUid in successfulNodes && isConversationNode(it) && target in projectedTargetRefs(plan.intent,it)
                }) else effect
        }
        // Multiple natural-language questions to the same actor may be represented as separate
        // intent nodes, but the canonical mechanical track has one key per actor/action.  Fold
        // those verified deltas before PlayerChangeSet validation; otherwise two legal QUERY
        // nodes become duplicate mutations of ACTION:QUERY in a single atomic change set.
        if(!validateDependencies(plan,proposal))return null
        return materializationEffects+narrativeEffects+providerEffects
    }

    internal fun admitEffects(request:ChatTurnRequest,planUid:String,proposalUid:String,
                             effects:List<VerifiedMechanicsCommandEffect>,time:TemporalStateChange?,
                             npcBrains:List<NpcBrainChange> = emptyList()):CanonicalCampaignMutationProposal?{
        if(time!=null&&time.campaignUid!=request.campaignUid){lastReasons=listOf("P60:CROSS_CAMPAIGN_TIME");return null}
        if(effects.isEmpty()&&time==null&&npcBrains.isEmpty())return null
        val command=PlayerCommand(
            commandUid=request.commandUid,campaignUid=request.campaignUid,actor=request.actor,
            commandKindUid=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS,
            payload=ApplyVerifiedMechanicsCommandPayload(planUid,
                if(time==null)coalesceInteractionEffects(effects) else phase60CoalesceEffects(effects),time,npcBrains),
            provenance=CommandProvenance("RPGOS-PHASE54-CANONICAL-COMPOSER",proposalUid),
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
            val outcome=outcomes[step.nodeUid]?.outcomeState
            if(outcome==GmNodeOutcomeState.BLOCKED_BY_PREREQUISITE) return@all proposal.verifiedEffects.none{it.nodeUid==step.nodeUid} &&
                proposal.candidate.proposedClaims.none{it.nodeUid==step.nodeUid && it.claimKind==ProposedClaimKind.NARRATIVE_COLOR}
            val node=plan.intent.nodes.singleOrNull{it.nodeUid==step.nodeUid}?:return@all false
            step.dependencyNodeUids.all{dependency->
                val kind=node.dependencies.firstOrNull{it.predecessorNodeUid==dependency}?.kind
                val prior=outcomes[dependency]?.outcomeState
                if(kind in setOf(IntentDependencyKind.AFTER_ATTEMPT,IntentDependencyKind.AFTER_COMPLETION,IntentDependencyKind.DURING))
                    prior in setOf(GmNodeOutcomeState.PROPOSED_SUCCESS,GmNodeOutcomeState.PROPOSED_FAILURE)
                else prior==GmNodeOutcomeState.PROPOSED_SUCCESS
            }&&
                (outcomes[step.nodeUid]?.outcomeState!=GmNodeOutcomeState.PROPOSED_SUCCESS||step.matchState in setOf(CapabilityMatchState.EXACT,CapabilityMatchState.COMPOSED,CapabilityMatchState.GENERIC))
        }
    }
}

/** One conversion for player and NPC owners, including aggregate/area impacts. No second physics. */
internal fun canonicalMechanicsCommandEffects(verified:VerifiedMechanicsEffect,proposedTarget:DomainRef):List<VerifiedMechanicsCommandEffect>? {
    val targetKind=verified.canonicalPayload["target_kind_uid"]
    val targetUid=verified.canonicalPayload["target_uid"]
    if((targetKind==null)!=(targetUid==null))return null
    val target=if(targetKind!=null)DomainRef(targetKind,targetUid!!) else proposedTarget
    val magnitude=verified.canonicalPayload["magnitude"]?.toLongOrNull()?:return null
    val areaCount=if("area_target_count" in verified.canonicalPayload)
        verified.canonicalPayload.getValue("area_target_count").toIntOrNull()?:return null else 0
    if(areaCount !in 0..256)return null
    val specifications=if(areaCount==0)listOf(Triple(target,magnitude,verified.effectKindUid)) else (0 until areaCount).map{index->
        val kind=verified.canonicalPayload["area_target_${index}_kind_uid"]?:return null
        val uid=verified.canonicalPayload["area_target_${index}_uid"]?:return null
        val areaMagnitude=verified.canonicalPayload["area_target_${index}_magnitude"]?.toLongOrNull()?:return null
        val effectKind=verified.canonicalPayload["area_target_${index}_effect_kind_uid"]?:return null
        Triple(DomainRef(kind,uid),areaMagnitude,effectKind)
    }
    val commonPayload=verified.canonicalPayload.filterKeys{!it.startsWith("area_target_")}
    return specifications.mapIndexed{index,(canonicalTarget,canonicalMagnitude,canonicalKind)->
        val suffix=if(areaCount==0)"" else ":AREA:$index"
        VerifiedMechanicsCommandEffect(verified.effectUid+suffix,verified.nodeUid,verified.mechanicsOwnerUid,canonicalKind,canonicalTarget,canonicalMagnitude,
            commonPayload+mapOf("target_kind_uid" to canonicalTarget.kindUid,"target_uid" to canonicalTarget.uid,"magnitude" to canonicalMagnitude.toString()),
            verified.proofUid+suffix,phase60Hash(verified.deterministicInputFingerprint+suffix),phase60Hash(verified.deterministicOutputFingerprint+"|$canonicalTarget|$canonicalMagnitude"))
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
