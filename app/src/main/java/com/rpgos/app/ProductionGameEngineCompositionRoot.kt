package com.rpgos.app

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.roundToLong

private const val CORE_PLAYER_CONTEXT_PROVIDER="RPGOS-CORE:PLAYER-CONTEXT"
private const val CORE_PLAYER_CONTEXT_OPERATION="PLAYER_STATE"

/** Phase38-protected application context provider; it never exposes a writable repository to AI. */
private class ProductionPlayerContextProvider(
    private val repository:UnifiedGameRepository,
    private val campaignUid:String
):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        if(request.campaignUid!=campaignUid)return StructuredRetrievalResult.Denied("CROSS_CAMPAIGN_CONTEXT")
        if(request.operationUid!=CORE_PLAYER_CONTEXT_OPERATION)return StructuredRetrievalResult.Unsupported("OPERATION_UNSUPPORTED")
        val player=repository.activePlayerRef()?:return StructuredRetrievalResult.NoData
        return when(val read=repository.protectedReads().playerState(request.audience,request.purpose,player.playerUid)){
            is ProtectedReadResult.Allow->StructuredRetrievalResult.Value(
                listOf(RetrievalRecord("PLAYER-STATE:${player.playerUid}",read.value.toContextMap(),"PHASE38:${read.reasonCode}")),true
            )
            is ProtectedReadResult.NoData->StructuredRetrievalResult.NoData
            is ProtectedReadResult.Deny->StructuredRetrievalResult.Denied(read.reasonCode)
            is ProtectedReadResult.NotDisclosed->StructuredRetrievalResult.NotDisclosed(read.reasonCode)
            is ProtectedReadResult.Unknown->StructuredRetrievalResult.Unknown(read.reasonCode)
            is ProtectedReadResult.Corruption->StructuredRetrievalResult.Corruption(read.reasonCode)
        }
    }
}

/** Generic rule floor for Phase50 effects. A World Pack may add constraints, never bypass Core. */
internal class UniversalMechanicsWorldRuleProvider(binding:WorldPackRuleBinding):WorldRuleProvider(
    "RPGOS-WORLD-RULE:UNIVERSAL-MECHANICS-FLOOR","1",binding.worldPackUid,binding.worldPackVersion
){
    override fun evaluate(request:WorldRuleRequest):WorldRuleDecision{
        if(request.command.commandKindUid!=PlayerCommandKinds.APPLY_VERIFIED_MECHANICS){
            return WorldRuleDecision.Rejected.create("RPGOS-RULE:MECHANICS-COMMAND-ONLY","COMMAND_KIND_OUTSIDE_UNIVERSAL_MECHANICS")
        }
        if(request.stage==WorldRuleEvaluationStage.DRAFT_EFFECT_CHECK&&request.effects?.changes?.any{change->
                change.payload !is ResourceChange&&change.payload !is ConditionChange&&change.payload !is RuntimeChange&&change.payload !is AssetChange&&
                    change.payload !is WoundChange&&change.payload !is SpatialChange&&change.payload !is EquipmentIntegrityChange&&
                    change.payload !is StructureIntegrityChange&&change.payload !is MechanicalTrackChange&&change.payload !is AggregatePopulationChange
            }==true){
            return WorldRuleDecision.Rejected.create("RPGOS-RULE:MECHANICS-OWNER-ALLOWLIST","UNOWNED_MECHANICS_CHANGE")
        }
        return WorldRuleDecision.Allowed.create("RPGOS-RULE:UNIVERSAL-MECHANICS-FLOOR",listOf("RPGOS-EVIDENCE:TYPED-MECHANICS"))
    }
}

/**
 * Core-owned mechanics resolver. Model parameters are treated as requests: magnitude, target and
 * proof are recomputed here. Combat requests traverse the full UniversalCombatEngine.
 */
/** Phase63 can supply LOD group counts through this seam without changing Phase50 combat rules. */
fun interface AggregateCombatStatePort{
    fun populationFor(campaignUid:String,target:DomainRef,atOrder:Long):AggregateMechanicalPopulation?

    companion object{val NONE=AggregateCombatStatePort{_,_,_->null}}
}

data class CombatAbilityContractQuery(
    val campaignUid:String,
    val abilityUid:String,
    val semanticFamilyUid:String,
    val targetCount:Int,
    val containsAggregateTarget:Boolean
)

/** World Packs can provide exact shapes, ranges, costs and secondary effects for any ability. */
fun interface CombatAbilityContractPort{
    fun contractFor(query:CombatAbilityContractQuery):CombatAbilityContract?

    companion object{
        val UNIVERSAL_FALLBACK=CombatAbilityContractPort{query->
            val areaFamilies=setOf("AREA_ATTACK","AOE","BLAST","EXPLOSION","CONE_ATTACK","LINE_ATTACK","ZONE_ATTACK","SWEEP_ATTACK")
            val isArea=query.semanticFamilyUid in areaFamilies||query.abilityUid in areaFamilies
            CombatAbilityContract(
                query.abilityUid,
                areaRadiusMillimetres=if(isArea)3_000 else null,
                maximumTargets=if(isArea)query.targetCount.coerceIn(1,256) else 1,
                aggregateAreaProfile=if(isArea&&query.containsAggregateTarget)AggregateAreaImpactProfile(2_000,2_000) else null,
                aggregateDirectProfile=if(!isArea&&query.containsAggregateTarget)AggregateDirectImpactProfile(50_000,25,50,1_000) else null,
                aggregateGroupProfile=if(!isArea&&query.containsAggregateTarget)AggregateGroupEngagementProfile() else null,
                effectKinds=listOf(UniversalMechanicalEffectKind.WOUND)
            )
        }
    }
}

internal class ProductionCombatSnapshotAuthority(
    private val repository:UnifiedGameRepository,
    private val aggregateCombatState:AggregateCombatStatePort=AggregateCombatStatePort.NONE,
    private val abilityContracts:CombatAbilityContractPort=CombatAbilityContractPort.UNIVERSAL_FALLBACK
){
    fun build(request:MechanicsEffectRequest,context:MechanicsResolutionContext,node:IntentNode,target:DomainRef):UniversalCombatRequest?{
        val active=repository.activePlayerRef()?:return null
        val actor=DomainRef(context.plan.intent.actor.actorKindUid,context.plan.intent.actor.actorUid)
        if(active.campaignId!=context.campaignUid||active.playerUid!=actor.uid)return null
        val ability=(node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid?:return null).uppercase()
        val playerValues=linkedMapOf<String,Long>()
        val statKeys=repository.statDefinitions().associate{it.statUid to it.key.uppercase()}
        repository.infrastructurePlayerStats().filter{it.characterUid==active.playerUid}.forEach{
            playerValues[statKeys[it.statUid]?:it.statUid.uppercase()]=it.baseValue.roundToLong()
        }
        val state=repository.infrastructurePlayerState()
        listOf(state?.persistent,state?.derived,state?.runtime).filterNotNull().forEach{values->values.forEach{(key,value)->
            val numeric=when(value){is Number->value.toDouble().roundToLong();is String->value.toDoubleOrNull()?.roundToLong();else->null}
            if(numeric!=null)playerValues.putIfAbsent(key.uppercase(),numeric)
        }}
        if(playerValues.isEmpty())return null
        val fallback=playerValues.values.sorted().let{it[it.size/2]}
        fun canonical(vararg hints:String)=playerValues.entries.firstOrNull{entry->hints.any{it in entry.key}}?.value?:fallback
        val attackerPersistence=repository.infrastructureMechanicalPersistence(actor.uid)
        val committedMechanical=repository.infrastructureMechanicalActor(actor)
        val committedWound=committedMechanical?.conditions?.filter{it.conditionUid=="WOUND"}?.sumOf{it.intensity}?:0L
        val playerAttributes=playerValues+mapOf("POWER" to canonical("POWER","STRENGTH","ATTACK"),"SKILL" to canonical("SKILL","DEXTERITY","ACCURACY"),
            "DEFENCE" to (canonical("DEFENCE","DEFENSE","ARMOR")-committedWound).coerceAtLeast(0),"AGILITY" to canonical("AGILITY","SPEED","REFLEX"),
            "ARMOR" to (committedMechanical?.attributes?.get("ARMOR")?:canonical("ARMOR","DEFENCE","DEFENSE")))
        val attacker=applyStaged(MechanicalActorView(
            context.campaignUid,actor,MechanicalActorKind.ACTIVE_PLAYER,maxOf(context.plan.atOrder?:0,attackerPersistence.stateVersion,committedMechanical?.stateVersion?:0),MechanicalStateMaterialization.FULL,
            playerAttributes,
            repository.infrastructurePlayerResources().filter{it.characterUid==active.playerUid}.map{MechanicalResource(it.resourceUid,it.currentValue.roundToLong().coerceAtLeast(0),it.currentValue.roundToLong().coerceAtLeast(0))},
            setOf(ability),conditions=(conditions(attackerPersistence)+committedMechanical?.conditions.orEmpty()).distinctBy{it.conditionUid},generationProvenanceUid="PLAYER-DOMAIN:${active.playerUid}"
        ),context.stagedEffects)
        val targetRefs=(node.participants.mapNotNull{participant->participant.referenceUid?.let{uid->context.plan.intent.references.singleOrNull{it.referenceUid==uid}?.resolvedProjectedRef}}+target).distinct()
        val targetPersistence=targetRefs.associateWith{repository.infrastructureMechanicalPersistence(it.uid)}
        val defenders=targetRefs.map{ref->
            val canonical=repository.infrastructureMechanicalActor(ref)?:return null
            val aggregateKind=canonical.kind in setOf(MechanicalActorKind.GROUP,MechanicalActorKind.UNIT)
            val aggregatePopulation=if(aggregateKind)
                aggregateCombatState.populationFor(context.campaignUid,ref,context.plan.atOrder?:0)?:canonical.aggregatePopulation
            else null
            if(aggregateKind&&aggregatePopulation==null)return null
            applyStaged(canonical.copy(aggregatePopulation=aggregatePopulation),context.stagedEffects)
        }
        val positions=buildMap{
            applyStagedPosition(actor,attackerPersistence.position,context.stagedEffects)?.let{put(actor,it)}
            targetPersistence.forEach{(ref,persistence)->applyStagedPosition(ref,persistence.position,context.stagedEffects)?.let{put(ref,it)}}
        }
        val fingerprint=mechanicsHash(listOf(context.context.canonicalPayload(),attacker,defenders,positions,context.plan.atOrder,context.stagedEffects).joinToString("|"))
        val intent=CombatIntent("P50:${request.effectUid}",context.campaignUid,actor,target,ability,VolitionalActionSource.VALIDATED_PLAYER_COMMAND,
            node.intendedResult?.semanticTypeUid?:"DISABLE",context.plan.atOrder?:0)
        val hasAggregate=defenders.any{it.aggregatePopulation!=null}
        val semanticFamily=(node.semanticAction.semanticFamilyUid?:ability).uppercase()
        val abilityContract=abilityContracts.contractFor(CombatAbilityContractQuery(
            context.campaignUid,ability,semanticFamily,targetRefs.size,hasAggregate
        ))?:return null
        if(abilityContract.abilityUid!=ability)return null
        return UniversalCombatRequest(intent,ImmutableCombatSnapshot(
            "SNAPSHOT:$fingerprint",context.campaignUid,context.plan.atOrder?:0,listOf(attacker)+defenders,emptyList(),emptyMap(),emptyMap(),fingerprint
        ),abilityContract,CombatSpatialState(positions))
    }

    private fun conditions(state:InfrastructureMechanicalPersistence)=state.activeEffects.filter{it.first.startsWith("CONDITION:")}
        .map{MechanicalCondition(it.first.substringAfter("CONDITION:"),it.second.coerceAtLeast(1))}

    /** Projects already verified earlier nodes into later-node snapshots without committing them. */
    private fun applyStaged(base:MechanicalActorView,effects:List<VerifiedMechanicsEffect>):MechanicalActorView{
        var attributes=base.attributes.toMutableMap();var resources=base.resources
        var conditions=base.conditions;var population=base.aggregatePopulation;var version=base.stateVersion
        effects.flatMap(::stagedImpacts).filter{it.first==base.actor}.forEach{(_,kind,magnitude,payload)->
            version++
            when(kind){
                "WOUND"->attributes["DEFENCE"]=(attributes["DEFENCE"]?:0L).minus(magnitude.coerceAtLeast(0)).coerceAtLeast(0)
                "RESOURCE_DELTA","RESOURCE","HEALTH_DELTA","DAMAGE_HP","HEALING","RESTORATION"->{
                    val uid=payload["resource_uid"]?:"HEALTH"
                    resources=resources.map{if(it.resourceUid==uid)it.copy(current=(it.current+magnitude).coerceIn(0,it.maximum))else it}
                }
                "CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->{
                    val uid=payload["condition_uid"]
                    if(uid!=null){
                        conditions=if(payload["operation"]?.uppercase() in setOf("REMOVE","CLEAR"))conditions.filterNot{it.conditionUid==uid}
                        else if(conditions.none{it.conditionUid==uid})conditions+MechanicalCondition(uid,1) else conditions
                    }
                }
                "EQUIPMENT","EQUIPMENT_DAMAGE"->attributes["ARMOR"]=(attributes["ARMOR"]?:0L).minus(magnitude.coerceAtLeast(0)).coerceAtLeast(0)
                "MORALE","COHESION","FORMATION"->attributes[kind]=Math.addExact(attributes[kind]?:10_000L,magnitude).coerceAtLeast(0)
                "AGGREGATE_ELIMINATION"->population=population?.let{p->val amount=magnitude.coerceIn(0,p.activeCount);p.copy(activeCount=p.activeCount-amount,eliminatedCount=p.eliminatedCount+amount)}
                "AGGREGATE_INJURY"->population=population?.let{p->val amount=magnitude.coerceIn(0,p.activeCount);p.copy(activeCount=p.activeCount-amount,woundedCount=p.woundedCount+amount)}
                "AGGREGATE_CONDITION"->{val uid=payload["condition_uid"];if(uid!=null)population=population?.let{p->p.copy(conditionCounts=p.conditionCounts+(uid to ((p.conditionCounts[uid]?:0L)+magnitude).coerceAtMost(p.totalCount)))} }
            }
        }
        return base.copy(stateVersion=version,attributes=attributes,resources=resources,conditions=conditions,aggregatePopulation=population)
    }

    private fun applyStagedPosition(ref:DomainRef,base:CombatPosition?,effects:List<VerifiedMechanicsEffect>):CombatPosition?{
        var position=base
        effects.flatMap(::stagedImpacts).filter{it.first==ref&&it.kind in setOf("MOVEMENT","DISPLACEMENT")}.forEach{impact->
            position=when(val current=position){
                is CombatPosition.Exact->current.copy(xMillimetres=Math.addExact(current.xMillimetres,impact.magnitude))
                else->CombatPosition.Exact(impact.magnitude,0)
            }
        }
        return position
    }

    private fun stagedImpacts(effect:VerifiedMechanicsEffect):List<StagedImpact>{
        val out=mutableListOf<StagedImpact>()
        fun add(kind:String,targetKind:String?,targetUid:String?,magnitude:String?,payload:Map<String,String>){
            val amount=magnitude?.toLongOrNull()?:return;if(targetKind!=null&&targetUid!=null)out+=StagedImpact(DomainRef(targetKind,targetUid),kind.substringAfterLast(':').uppercase(),amount,payload)
        }
        add(effect.effectKindUid,effect.canonicalPayload["target_kind_uid"],effect.canonicalPayload["target_uid"],effect.canonicalPayload["magnitude"],effect.canonicalPayload)
        val count=effect.canonicalPayload["area_target_count"]?.toIntOrNull()?:0
        repeat(count){index->add(effect.canonicalPayload["area_target_${index}_effect_kind_uid"]?:return@repeat,effect.canonicalPayload["area_target_${index}_kind_uid"],effect.canonicalPayload["area_target_${index}_uid"],effect.canonicalPayload["area_target_${index}_magnitude"],effect.canonicalPayload)}
        return out
    }
    private data class StagedImpact(val first:DomainRef,val kind:String,val magnitude:Long,val payload:Map<String,String>)
}

internal class ProductionUniversalMechanicsRuleResolver(private val combatSnapshots:ProductionCombatSnapshotAuthority):MechanicsRuleResolver{
    override fun resolve(request:MechanicsEffectRequest,context:MechanicsResolutionContext):MechanicsEffectResolution{
        val node=context.plan.intent.nodes.singleOrNull{it.nodeUid==request.nodeUid}
            ?:return MechanicsEffectResolution.Rejected("INTENT_NODE_NOT_FOUND")
        val target=request.targetProjectedRef?:return MechanicsEffectResolution.Rejected("TARGET_REQUIRED")
        val owner=context.plan.steps.singleOrNull{it.nodeUid==request.nodeUid}?.mechanicsOwnerUid
            ?:return MechanicsEffectResolution.Rejected("MECHANICS_OWNER_MISSING")
        if(owner!=request.mechanicsOwnerUid)return MechanicsEffectResolution.Rejected("MECHANICS_OWNER_MISMATCH")
        val canonical=(if(owner=="UNIVERSAL_COMBAT")resolveCombat(request,context,node,target) else resolveUniversal(request,context,node,target))
            ?:return MechanicsEffectResolution.Rejected("UNSUPPORTED_OR_UNVERIFIABLE_EFFECT")
        val input=mechanicsHash(listOf(context.plan.intent.canonicalFingerprint(),context.context.canonicalPayload(),context.stagedEffects,request.effectUid,request.effectKindUid,owner).joinToString("|"))
        val output=mechanicsHash(canonical.toSortedMap().toString())
        return MechanicsEffectResolution.Verified(VerifiedMechanicsEffect(
            request.effectUid,request.nodeUid,owner,canonical["canonical_effect_kind_uid"]?:request.effectKindUid,canonical,
            "RPGOS-P50-PROOF:${mechanicsHash("$input|$output")}",input,output
        ))
    }

    private fun resolveCombat(request:MechanicsEffectRequest,context:MechanicsResolutionContext,node:IntentNode,target:DomainRef):Map<String,String>?{
        val coreRequest=combatSnapshots.build(request,context,node,target)?:return null
        val result=UniversalCombatEngine().resolve(coreRequest) as? CombatResolution.Resolved?:return null
        if(result.effects.isEmpty())return null
        val resolved=result.effects.singleOrNull{it.target==target}?:result.effects.first()
        return resolved.payload+buildMap{
            put("magnitude",resolved.magnitude.toString());put("target_kind_uid",resolved.target.kindUid);put("target_uid",resolved.target.uid)
            put("combat_proof_uid",result.evidence.proofUid);put("canonical_effect_kind_uid",resolved.kind.name)
            if(result.effects.size>1){
                put("area_target_count",result.effects.size.toString())
                result.effects.sortedWith(compareBy<UniversalMechanicalEffect>{it.target.kindUid}.thenBy{it.target.uid}).forEachIndexed{index,effect->
                    put("area_target_${index}_kind_uid",effect.target.kindUid);put("area_target_${index}_uid",effect.target.uid)
                    put("area_target_${index}_magnitude",effect.magnitude.toString());put("area_target_${index}_effect_kind_uid",effect.kind.name)
                }
            }
        }
    }

    private fun resolveUniversal(request:MechanicsEffectRequest,context:MechanicsResolutionContext,node:IntentNode,target:DomainRef):Map<String,String>?{
        val kind=request.effectKindUid.substringAfterLast(':').uppercase()
        val actor=DomainRef(context.plan.intent.actor.actorKindUid,context.plan.intent.actor.actorUid)
        val deterministic=(mechanicsHash("${context.plan.intent.canonicalFingerprint()}|${request.effectUid}|$kind").take(8).toLong(16)%10)+1
        val canonicalTarget=when(kind){"MOVEMENT","DISPLACEMENT","RESOURCE_DELTA","CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->actor;else->target}
        val magnitude=when(kind){
            "MOVEMENT","DISPLACEMENT"->1_000L
            "RESOURCE_DELTA"->-deterministic
            "CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->1L
            "WOUND","EQUIPMENT_DAMAGE","STRUCTURE_DAMAGE","MORALE","COHESION","FORMATION","ENVIRONMENT","PERSISTENT_EFFECT"->deterministic
            else->return null
        }
        val safePayload=mutableMapOf(
            "magnitude" to magnitude.toString(),"target_kind_uid" to canonicalTarget.kindUid,"target_uid" to canonicalTarget.uid,
            "semantic_action_uid" to (node.semanticAction.canonicalActionUid?:node.semanticAction.semanticFamilyUid?:"OPEN_ACTION")
        )
        when(kind){
            "RESOURCE_DELTA"->{
                val resource=request.parameters["resource_uid"]?:return null
                if(!context.context.canonicalPayload().contains(resource))return null
                safePayload["resource_uid"]=resource
            }
            "CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->{
                val condition=request.parameters["condition_uid"]?.takeIf{it.matches(Regex("[A-Za-z0-9:_-]{1,128}"))}?:return null
                safePayload["condition_uid"]=condition;safePayload["operation"]="ADD"
            }
        }
        return safePayload
    }
}

private class ProductionIntentResolver(
    private val repository:UnifiedGameRepository,
    private val audience:()->AudienceContext,
    private val purpose:()->PurposeContext
):TrustedIntentResolutionPort{
    override fun resolve(candidate:IntentDocument):IntentDocument{
        val player=repository.activePlayerRef()
        val resolved=candidate.references.map{reference->
            if(reference.state==IntentReferenceState.RESOLVED_PROJECTED||reference.kind in setOf(IntentReferenceKind.FUTURE_RESULT,IntentReferenceKind.RESOURCE_FROM_RESULT))return@map reference
            val phrase=(reference.rawPhrase?:reference.descriptorHints["surface"]).orEmpty().trim()
            val direct=when{
                phrase.lowercase() in setOf("ja","mnie","mi","sobie","self","me")&&player!=null->DomainRef("PLAYER",player.playerUid)
                else->null
            }
            val candidates=if(direct!=null)listOf(direct) else buildList{
                runCatching{repository.npcsProjection(phrase,audience(),purpose()).value.orEmpty()}.getOrDefault(emptyList()).filter{nameMatch(it.name,phrase)}.forEach{add(DomainRef("NPC",it.uid))}
                repository.infrastructureAggregateTargets(phrase).forEach{add(it.second)}
                repository.worldLocations(phrase).filter{nameMatch(it.name,phrase)}.forEach{add(DomainRef("LOCATION",it.uid))}
            }.distinct()
            when(candidates.size){
                1->reference.copy(state=IntentReferenceState.RESOLVED_PROJECTED,resolvedProjectedRef=candidates.single(),candidateProjectedRefs=emptyList(),resolutionEvidenceUid="PHASE38:EXACT-DESCRIPTOR")
                in 2..Int.MAX_VALUE->reference.copy(state=IntentReferenceState.AMBIGUOUS,candidateProjectedRefs=candidates,resolutionEvidenceUid="PHASE38:AMBIGUOUS-DESCRIPTOR")
                else->reference
            }
        }
        return candidate.copy(references=resolved,provenance=candidate.provenance.copy(source=IntentInterpretationSource.TRUSTED_REFERENCE_RESOLUTION,sourceUid="RPGOS-CORE-REFERENCE-RESOLVER"))
    }
    private fun nameMatch(name:String,phrase:String)=name.trim().equals(phrase,true)||name.lowercase().contains(phrase.lowercase()).takeIf{phrase.length>=3}==true
}

/** Exact as-of readback: only material bound to this committed order may enter narration. */
private class ProductionCommittedNarrationReadPort(private val repository:UnifiedGameRepository):CommittedNarrationReadPort{
    override fun read(identity:TurnTransactionIdentity,receipt:TurnCommitReceipt,audience:AudienceContext,purpose:PurposeContext):PostCommitPlayerVisibleReadback{
        val order=requireNotNull(receipt.commitOrder)
        val replay=requireNotNull(repository.infrastructureReplayPayload(identity.transactionUid,order)){"RPGOS-P54:COMMITTED_REPLAY_MISSING"}
        val snapshot=mapOf("committed_order" to order.toString(),"committed_change_count" to replay.changeSet.changes.size.toString())
        val facts=replay.changeSet.changes.map{change->CommittedNarrativeFact(
            "FACT:${mechanicsHash(change.changeUid).take(24)}",CommittedNarrativeFactKind.MECHANICAL_RESULT,
            subjectOf(change)?.uid,change.changeKindUid,valueOf(change),order
        )}
        val consequences=replay.changeSet.changes.map{change->when(val payload=change.payload){
            is ResourceChange->"Zasób ${payload.resourceUid} zmienił się o ${payload.delta.units}."
            is ConditionChange->if(payload.operation==ConditionOperation.ADD)"Pojawił się stan ${payload.conditionUid}." else "Stan ${payload.conditionUid} ustąpił."
            is RuntimeChange->"Skutek działania został zastosowany."
            is WoundChange->"Cel otrzymał ranę o nasileniu ${payload.severityDelta.units}."
            is SpatialChange->"Pozycja celu zmieniła się."
            is EquipmentIntegrityChange->"Wyposażenie celu zostało uszkodzone."
            is StructureIntegrityChange->"Struktura została uszkodzona."
            is MechanicalTrackChange->"Stan ${payload.trackUid.lowercase()} zmienił się."
            is AggregatePopulationChange->"Zmienił się stan walczącej grupy."
            is AssetChange->"Stan obiektu zmienił się na ${payload.proposedLifecycleStateUid.lowercase()}."
            else->"Świat przyjął skutek tej decyzji."
        }}
        val forbidden=replay.changeSet.changes.flatMap{listOf(it.changeUid,it.sourceRuleUid)}.filterNotNull().toSet()
        return PostCommitPlayerVisibleReadback(
            identity.campaignUid,identity.turnUid,identity.commandUid,identity.transactionUid,order,
            "PHASE38:${mechanicsHash("${identity.transactionUid}|$order|$snapshot")}",snapshot,facts,consequences,forbidden,emptySet(),"PLAYER_DECISION_POINT"
        )
    }
    private fun subjectOf(change:PlayerDomainChange):DomainRef?=when(val payload=change.payload){
        is StatChange->payload.subject;is ResourceChange->payload.subject;is SkillChange->payload.subject;is TechniqueChange->payload.subject
        is InventoryChange->payload.subject;is EquipmentChange->payload.subject;is ConditionChange->payload.subject;is RuntimeChange->payload.subject
        is WoundChange->payload.subject;is SpatialChange->payload.subject;is EquipmentIntegrityChange->payload.subject
        is StructureIntegrityChange->payload.subject;is MechanicalTrackChange->payload.subject;is AggregatePopulationChange->payload.subject
        else->null
    }
    private fun valueOf(change:PlayerDomainChange)=when(val payload=change.payload){
        is ResourceChange->payload.delta.units.toString();is ConditionChange->payload.operation.name;is RuntimeChange->payload.delta.units.toString()
        is WoundChange->payload.severityDelta.units.toString();is SpatialChange->"${payload.deltaXMillimetres},${payload.deltaYMillimetres}"
        is EquipmentIntegrityChange->payload.damageDelta.units.toString();is StructureIntegrityChange->payload.damageDelta.units.toString()
        is MechanicalTrackChange->payload.delta.units.toString();is AggregatePopulationChange->"${payload.eliminatedDelta}/${payload.woundedDelta}/${payload.conditionAffectedDelta}"
        is AssetChange->payload.proposedLifecycleStateUid;else->"APPLIED"
    }
}

private class DynamicProductionModelRoute(
    private val providers:AndroidAiProviderCenterApplication,
    private val configuration:()->AiSystemConfiguration,
    private val additionalProviders:()->List<AiProvider>
):AiModelRoutePort{
    override fun route(role:AiRole,workload:AiWorkload,requiredContextUnits:Int):AiRouteResult{
        val config=configuration();val all=(providers.availableProviders(config)+additionalProviders()).distinctBy{it.capabilities.providerUid to it.capabilities.modelUid}
        val registry=AiProviderRegistry.fromCompositionRoot(all)
        val ui=providers.initialState(config)
        val availability=AiAvailabilityPort{provider->
            val state=when(provider.capabilities.providerKind){
                AiProviderKind.LOCAL->if(ui.localArtifactInstalled&&ui.localAdmission is LocalAdmissionResult.Admitted)AiAvailabilityState.READY else AiAvailabilityState.NOT_CONFIGURED
                AiProviderKind.CLOUD->if(ui.openRouterStatus.state==CloudAuthState.CONNECTED)AiAvailabilityState.READY else AiAvailabilityState.NOT_CONFIGURED
                AiProviderKind.CONTROLLED_TEST->AiAvailabilityState.READY
            }
            AiProviderAvailability(AiModelSelection(provider.capabilities.providerUid,provider.capabilities.modelUid),state,if(state==AiAvailabilityState.READY)"READY" else "PROVIDER_NOT_CONFIGURED")
        }
        return RoleAwareModelRouter(registry,listOf(config.gameMaster,config.director),config.privacy,availability).route(role,workload,requiredContextUnits)
    }
}

/** One production composition root for Chat/UI, providers, mechanics, PlayerDomain and commit. */
class ProductionGameEngineCompositionRoot(
    context:Context,
    private val repository:UnifiedGameRepository,
    private val providerCenter:AndroidAiProviderCenterApplication,
    private val configuration:() -> AiSystemConfiguration,
    private val additionalProviders:() -> List<AiProvider> = { emptyList() },
    private val aggregateCombatState:AggregateCombatStatePort=AggregateCombatStatePort.NONE,
    private val combatAbilityContracts:CombatAbilityContractPort=CombatAbilityContractPort.UNIVERSAL_FALLBACK
){
    private val app=context.applicationContext
    fun characterCreationApplication():AiCharacterCreationApplication=AiCharacterCreationApplication(
        DynamicProductionModelRoute(providerCenter,configuration,additionalProviders),repository
    )
    fun chatApplication():CanonicalChatApplication{
        val authority=repository.infrastructureWorldPackAuthority()
        val worldProvider=UniversalMechanicsWorldRuleProvider(authority.binding)
        val worldRules=WorldRuleProviderRegistry.of(listOf(worldProvider))
        val worldAuthority=WorldPackAuthoritySnapshot.single(authority.campaignUid,authority.binding)
        val playerEngine=productionMechanicsPlayerDomainEngine(worldRules,worldAuthority)
        val mechanics=ProductionUniversalMechanicsRuleResolver(ProductionCombatSnapshotAuthority(repository,aggregateCombatState,combatAbilityContracts))
        val mechanicsRegistry=MechanicsResolverRegistry.fromCompositionRoot(mapOf(
            "UNIVERSAL_COMBAT" to mechanics,"UNIVERSAL_ACTION" to mechanics,"UNIVERSAL_MOVEMENT" to mechanics
        ))
        val requirements=listOf(CapabilityRequirementTemplate(
            "PLAYER_STATE",CORE_PLAYER_CONTEXT_PROVIDER,CORE_PLAYER_CONTEXT_OPERATION,RequirementImportance.SAFETY,maximumLimit=1
        ))
        val capabilities=listOf(
            CapabilityDescriptor("RPGOS-CAPABILITY:UNIVERSAL-COMBAT",2,semanticFamilyUids=setOf(
                "ATTACK","COMBAT","STRIKE","FIGHT","DEFEND","AREA_ATTACK","BLAST","EXPLOSION","AOE","CONE_ATTACK","LINE_ATTACK","ZONE_ATTACK","SWEEP_ATTACK"
            ),
                executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_COMBAT",composable=true,requirements=requirements),
            CapabilityDescriptor("RPGOS-CAPABILITY:UNIVERSAL-MOVEMENT",2,semanticFamilyUids=setOf("MOVE","TRAVEL","ESCAPE","REACH","PUSH"),
                executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_MOVEMENT",composable=true,requirements=requirements),
            CapabilityDescriptor("RPGOS-CAPABILITY:UNIVERSAL-ACTION",2,semanticFamilyUids=setOf("USE","CRAFT","FISH","BUY","SELL","HEAL","REST","CAPTURE","PROTECT","HOLD","DISABLE","DESTROY"),
                executionKind=CapabilityExecutionKind.MECHANICS_PROPOSAL,sideEffectClass=CapabilitySideEffectClass.PROPOSED_WORLD_EFFECT,mechanicsOwnerUid="UNIVERSAL_ACTION",composable=true,requirements=requirements)
        )
        val binding=StructuredProviderBinding(CORE_PLAYER_CONTEXT_PROVIDER,setOf(CORE_PLAYER_CONTEXT_OPERATION),ProductionPlayerContextProvider(repository,authority.campaignUid))
        val contextPipeline=CanonicalIterativeRetrievalPipeline(StructuredSqlRetriever(listOf(binding)),SemanticContextBudgetManager(),TypedContextCompletionStrategy{_,_,_->emptyList()})
        val evaluator=GmProposalEvaluator(StructuredGmProposalValidator(),MechanicsResolutionEngine(mechanicsRegistry))
        val assembler=ProductionCanonicalMutationAssembler(playerEngine,PlayerResolutionContextFactory{command->
            val refs=linkedSetOf<CampaignScopedDomainRef>()
            fun add(ref:DomainRef){refs+=CampaignScopedDomainRef(command.campaignUid,ref)}
            add(DomainRef(command.actor.actorKindUid,command.actor.actorUid))
            command.payload.effects.forEach{effect->
                add(effect.target)
                when(effect.effectKindUid.substringAfterLast(':').uppercase()){
                    "RESOURCE_DELTA","RESOURCE","HEALTH_DELTA","DAMAGE_HP","HEALING","RESTORATION"->add(DomainRef("RESOURCE",effect.canonicalPayload["resource_uid"]?:"HEALTH"))
                    "CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->effect.canonicalPayload["condition_uid"]?.let{add(DomainRef("CONDITION",it))}
                    "EQUIPMENT","EQUIPMENT_DAMAGE","STRUCTURE","STRUCTURE_DAMAGE"->
                        effect.canonicalPayload["component_uid"]?.let{add(DomainRef("MECHANICAL_COMPONENT",it))}
                    else->Unit
                }
            }
            PlayerResolutionContext.create(command.campaignUid,command.actor,refs,dependencyVersions=mapOf("PHASE50" to "2"),worldRuleMode=WorldRuleMode.Bound(authority.binding))
        })
        val route=DynamicProductionModelRoute(providerCenter,configuration,additionalProviders)
        val facade=AiChatEngineFacade(
            route,Phase43IntentValidator(),ProductionIntentResolver(repository,
                {VisibilityAudienceFactory.player(repository.activeCampaignRef().campaignId)},
                {PurposeContext(repository.activeCampaignRef().campaignId,VisibilityPurposeKinds.GAMEPLAY_NARRATION)}),
            LegacyRuleIntentFallback(),GraphTurnPlanner(capabilities),contextPipeline,
            ContextRuntimeProfile("ANDROID-PRODUCTION",8_192,256,768,1_024,256),BoundedProposalRepair(evaluator),assembler,
            AuthoritativeTurnCommitPort{identity,proposal->try{repository.commitTurn(identity,proposal)}catch(t:Throwable){throw CanonicalCommitException(t.message?:"TURN_COMMIT_FAILED",t)}},
            PersistedCommitReceiptAuthority(CommittedReceiptLookup(repository::infrastructureReceipt)),
            CommittedNarrationContextBuilder(ProductionCommittedNarrationReadPort(repository)),
            deliveryStore=FileNarrativeDeliveryStore(File(app.filesDir,"narrative-delivery")),
            recoveryStore=FileNarrationRecoveryStore(File(app.filesDir,"narrative-recovery")),
            recoveryDiscovery={repository.infrastructureLastReceipt()?.let{receipt->
                if(!receipt.transactionUid.startsWith("TRANSACTION:")||!receipt.commandUid.startsWith("COMMAND:")||!receipt.turnUid.startsWith("TURN:"))return@let null
                val order=receipt.commitOrder?:return@let null
                val actor=repository.infrastructureReplayPayload(receipt.transactionUid,order)?.changeSet?.actor?:return@let null
                ChatTurnRequest("RECOVERY:${receipt.transactionUid}",receipt.campaignUid,receipt.turnUid,receipt.commandUid,receipt.transactionUid,
                    actor,"RECOVERY_FROM_COMMITTED_RECEIPT","pl-PL",VisibilityAudienceFactory.player(receipt.campaignUid),
                    PurposeContext(receipt.campaignUid,VisibilityPurposeKinds.GAMEPLAY_NARRATION),order)
            }}
        )
        return CanonicalChatApplication(facade,ChatTurnRequestFactory{input->
            val campaign=repository.activeCampaignRef().campaignId
            val player=requireNotNull(repository.activePlayerRef()){ "RPGOS-CHAT:ACTIVE_PLAYER_REQUIRED" }
            val uid=UUID.randomUUID().toString()
            ChatTurnRequest("REQUEST:$uid",campaign,"TURN:$uid","COMMAND:$uid","TRANSACTION:$uid",CommandActorRef("PLAYER",player.playerUid),input,"pl-PL",
                VisibilityAudienceFactory.player(campaign),PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),repository.infrastructureLastCommitOrder()+1)
        })
    }
}

/** Rebuilds the root at each turn/recovery so campaign and provider selection never go stale. */
class DynamicCanonicalChatApplication(private val factory:()->CanonicalChatApplication):ChatApplicationPort{
    override suspend fun play(input:String,cancellation:AiCancellationSignal)=factory().play(input,cancellation)
    override suspend fun recover(token:ChatNarrationRecoveryToken,cancellation:AiCancellationSignal)=factory().recover(token,cancellation)
    override fun pendingRecovery():ChatNarrationRecoveryToken?=factory().pendingRecovery()
}

private fun mechanicsHash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
