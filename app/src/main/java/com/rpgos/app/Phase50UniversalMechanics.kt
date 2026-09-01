package com.rpgos.app

import java.security.MessageDigest
import java.util.Random

enum class MechanicalActorKind { ACTIVE_PLAYER, FORMER_PLAYER, NPC, MONSTER, SUMMON, VEHICLE, UNIT, GROUP, WORLD_ACTOR }
enum class MechanicalStateMaterialization { SEED_ONLY, PARTIAL, FULL }

data class MechanicalResource(val resourceUid:String,val current:Long,val maximum:Long){init{require(resourceUid.isNotBlank()&&maximum>=0&&current in 0..maximum)}}
data class MechanicalCondition(val conditionUid:String,val intensity:Long,val expiresAtOrder:Long?=null){init{require(conditionUid.isNotBlank()&&intensity>=0&&expiresAtOrder?.let{it>=0}!=false)}}
data class AggregateMechanicalPopulation(
    val totalCount:Long,val activeCount:Long,val woundedCount:Long=0,val eliminatedCount:Long=0,
    val conditionCounts:Map<String,Long> = emptyMap()
){init{
    require(totalCount>0&&activeCount>=0&&woundedCount>=0&&eliminatedCount>=0&&activeCount+woundedCount+eliminatedCount<=totalCount)
    require(conditionCounts.keys.none{it.isBlank()}&&conditionCounts.values.all{it in 0..totalCount})
}}

data class MechanicalActorView(
    val campaignUid:String,
    val actor:DomainRef,
    val kind:MechanicalActorKind,
    val stateVersion:Long,
    val materialization:MechanicalStateMaterialization,
    val attributes:Map<String,Long>,
    val resources:List<MechanicalResource>,
    val executableAbilityUids:Set<String>,
    val traitUids:Set<String> = emptySet(),
    val resistanceBasisPoints:Map<String,Long> = emptyMap(),
    val equipmentRefs:List<DomainRef> = emptyList(),
    val conditions:List<MechanicalCondition> = emptyList(),
    val locationRef:DomainRef?=null,
    val generationProvenanceUid:String,
    val aggregatePopulation:AggregateMechanicalPopulation?=null
){init{
    require(campaignUid.isNotBlank()&&actor.kindUid.isNotBlank()&&actor.uid.isNotBlank()&&stateVersion>=0&&generationProvenanceUid.isNotBlank())
    require(attributes.keys.none{it.isBlank()}&&resources.map{it.resourceUid}.distinct().size==resources.size)
    require(executableAbilityUids.none{it.isBlank()}&&resistanceBasisPoints.values.all{it in -100_000..100_000})
    require(aggregatePopulation==null||kind in setOf(MechanicalActorKind.UNIT,MechanicalActorKind.GROUP))
}}

enum class GenerationRuleKind { REQUIRED, CONDITIONAL, WEIGHTED, FORBIDDEN }
data class MechanicalGenerationRule(
    val ruleUid:String,val kind:GenerationRuleKind,val componentUid:String,val weight:Long=0,val conditionTagUid:String?=null
){init{require(ruleUid.isNotBlank()&&componentUid.isNotBlank()&&weight>=0&&conditionTagUid?.isBlank()!=true);require(kind!=GenerationRuleKind.WEIGHTED||weight>0)}}

data class WorldActorMechanicalTemplate(
    val templateUid:String,val actorKind:MechanicalActorKind,val baseAttributes:Map<String,Long>,
    val baseAbilities:Set<String>,val rules:List<MechanicalGenerationRule>,val minimumPowerEnvelope:Long,val maximumPowerEnvelope:Long
){init{
    require(templateUid.isNotBlank()&&baseAttributes.keys.none{it.isBlank()}&&baseAbilities.none{it.isBlank()})
    require(rules.map{it.ruleUid}.distinct().size==rules.size&&minimumPowerEnvelope>=0&&maximumPowerEnvelope>=minimumPowerEnvelope)
}}

/** Deliberately contains no player power input: encounter difficulty emerges from world state. */
data class WorldActorGenerationRequest(
    val campaignUid:String,val actor:DomainRef,val templateUid:String,val hierarchicalSeed:String,
    val worldStateTags:Set<String>,val causalKnowledgeEvidenceUids:Set<String>,val targetMaterialization:MechanicalStateMaterialization
){init{
    require(campaignUid.isNotBlank()&&actor.kindUid.isNotBlank()&&actor.uid.isNotBlank()&&templateUid.isNotBlank()&&hierarchicalSeed.isNotBlank())
    require(worldStateTags.none{it.isBlank()}&&causalKnowledgeEvidenceUids.none{it.isBlank()})
}}

class WorldActorMechanicalGenerator{
    fun generate(request:WorldActorGenerationRequest,template:WorldActorMechanicalTemplate):MechanicalActorView{
        require(request.templateUid==template.templateUid)
        val seed=sha256("${request.campaignUid}|${request.actor}|${request.hierarchicalSeed}|${template.templateUid}")
        val random=Random(seed.take(16).toULong(16).toLong())
        val forbidden=template.rules.filter{it.kind==GenerationRuleKind.FORBIDDEN}.map{it.componentUid}.toSet()
        val components=linkedSetOf<String>().apply{
            addAll(template.baseAbilities.filterNot{it in forbidden})
            addAll(template.rules.filter{it.kind==GenerationRuleKind.REQUIRED}.map{it.componentUid}.filterNot{it in forbidden})
            addAll(template.rules.filter{it.kind==GenerationRuleKind.CONDITIONAL&&it.conditionTagUid in request.worldStateTags}.map{it.componentUid}.filterNot{it in forbidden})
            template.rules.filter{it.kind==GenerationRuleKind.WEIGHTED&&it.componentUid !in forbidden}.sortedBy{it.ruleUid}.forEach{
                if(random.nextLong().ushr(1)%10_000<it.weight.coerceAtMost(10_000))add(it.componentUid)
            }
        }
        val attributes=template.baseAttributes.toSortedMap().mapValues{(_,base)->
            val variance=if(request.targetMaterialization==MechanicalStateMaterialization.SEED_ONLY)0 else random.nextInt(11)-5
            (base+variance).coerceAtLeast(0)
        }
        val power=attributes.values.sum()
        require(power in template.minimumPowerEnvelope..template.maximumPowerEnvelope){"RPGOS-P50:GENERATION_POWER_ENVELOPE"}
        return MechanicalActorView(
            request.campaignUid,request.actor,template.actorKind,0,request.targetMaterialization,attributes,emptyList(),components,
            generationProvenanceUid="GEN:$seed"
        )
    }
    private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
}

enum class VolitionalActionSource { VALIDATED_PLAYER_COMMAND, NPC_DECISION_ENGINE, WORLD_PROCESS, MECHANICAL_CONSEQUENCE }
data class CombatIntent(
    val intentUid:String,val campaignUid:String,val actor:DomainRef,val target:DomainRef,val abilityUid:String,
    val source:VolitionalActionSource,val objectiveUid:String,val declaredAtOrder:Long
){init{
    require(intentUid.isNotBlank()&&campaignUid.isNotBlank()&&abilityUid.isNotBlank()&&objectiveUid.isNotBlank()&&declaredAtOrder>=0)
    if(actor.kindUid=="PLAYER")require(source==VolitionalActionSource.VALIDATED_PLAYER_COMMAND){"RPGOS-P50:ACTIVE_PLAYER_VOLITION_REQUIRES_USER_COMMAND"}
}}

data class CombatPerceptionEvidence(
    val observer:DomainRef,val perceivedSubject:DomainRef,val evidenceUid:String,val qualityBasisPoints:Long,val availableAtOrder:Long
){init{require(evidenceUid.isNotBlank()&&qualityBasisPoints in 0..10_000&&availableAtOrder>=0)}}

data class ImmutableCombatSnapshot(
    val snapshotUid:String,val campaignUid:String,val atOrder:Long,val actors:List<MechanicalActorView>,
    val perception:List<CombatPerceptionEvidence>,val spatialFacts:Map<String,String>,val timingFacts:Map<String,Long>,val fingerprint:String
){init{
    require(snapshotUid.isNotBlank()&&campaignUid.isNotBlank()&&atOrder>=0&&fingerprint.isNotBlank())
    require(actors.all{it.campaignUid==campaignUid}&&actors.map{it.actor}.distinct().size==actors.size)
}}

data class CombatReactionRequest(val reactor:DomainRef,val reactionAbilityUid:String,val resourceUid:String?,val resourceCost:Long=0){
    init{require(reactionAbilityUid.isNotBlank()&&resourceCost>=0&&resourceUid?.isBlank()!=true)}
}
sealed interface CombatReactionEligibility{
    data class Eligible(val evidenceUid:String):CombatReactionEligibility
    data class Rejected(val reasonUid:String):CombatReactionEligibility
}

class CombatReactionGate{
    fun evaluate(request:CombatReactionRequest,intent:CombatIntent,snapshot:ImmutableCombatSnapshot,reactionAtOrder:Long=snapshot.atOrder):CombatReactionEligibility{
        val actor=snapshot.actors.singleOrNull{it.actor==request.reactor}?:return CombatReactionEligibility.Rejected("REACTOR_NOT_MATERIALIZED")
        if(request.reactionAbilityUid !in actor.executableAbilityUids)return CombatReactionEligibility.Rejected("REACTION_CAPABILITY_UNAVAILABLE")
        val availableAt=snapshot.timingFacts["${request.reactor.kindUid}:${request.reactor.uid}:reaction_available_at:${request.reactionAbilityUid}"]?:snapshot.atOrder
        if(availableAt>reactionAtOrder)return CombatReactionEligibility.Rejected("REACTION_WINDOW_NOT_OPEN")
        val cooldownUntil=snapshot.timingFacts["${request.reactor.kindUid}:${request.reactor.uid}:cooldown:${request.reactionAbilityUid}"]?:0L
        if(cooldownUntil>reactionAtOrder)return CombatReactionEligibility.Rejected("REACTION_IN_RECOVERY")
        val perceived=snapshot.perception.filter{it.observer==request.reactor&&it.perceivedSubject==intent.actor&&it.availableAtOrder<=reactionAtOrder}
            .maxByOrNull{it.qualityBasisPoints}?:return CombatReactionEligibility.Rejected("ATTACK_NOT_PERCEIVED")
        if(request.resourceUid!=null){
            val resource=actor.resources.singleOrNull{it.resourceUid==request.resourceUid}?:return CombatReactionEligibility.Rejected("REACTION_RESOURCE_MISSING")
            if(resource.current<request.resourceCost)return CombatReactionEligibility.Rejected("REACTION_RESOURCE_INSUFFICIENT")
        }
        return CombatReactionEligibility.Eligible(perceived.evidenceUid)
    }
}

enum class UniversalMechanicalEffectKind {
    RESOURCE_DELTA, WOUND, CONDITION, MOVEMENT, EQUIPMENT, STRUCTURE, MORALE, COHESION, FORMATION, ENVIRONMENT, INTERACTION,
    AGGREGATE_ELIMINATION, AGGREGATE_INJURY, AGGREGATE_CONDITION
}
data class UniversalMechanicalEffect(
    val effectUid:String,val kind:UniversalMechanicalEffectKind,val target:DomainRef,val magnitude:Long,
    val payload:Map<String,String>,val causeIntentUid:String
){init{require(effectUid.isNotBlank()&&causeIntentUid.isNotBlank()&&payload.keys.none{it.isBlank()})}}

data class DeterministicMechanicsEvidence(
    val proofUid:String,val rulesetUid:String,val snapshotFingerprint:String,val seedFingerprint:String,
    val randomDraws:List<Long>,val inputFingerprint:String,val outputFingerprint:String
){init{require(listOf(proofUid,rulesetUid,snapshotFingerprint,seedFingerprint,inputFingerprint,outputFingerprint).none{it.isBlank()})}}

sealed interface CombatResolution{
    data class Resolved(val outcomeUid:String,val effects:List<UniversalMechanicalEffect>,val evidence:DeterministicMechanicsEvidence):CombatResolution
    data class Rejected(val reasonUid:String):CombatResolution
}

class UniversalCombatResolver(private val rulesetUid:String="RPGOS-UNIVERSAL-COMBAT-V1"){
    fun resolve(intent:CombatIntent,snapshot:ImmutableCombatSnapshot,reaction:CombatReactionRequest?=null):CombatResolution{
        val positioned=if(snapshot.positionOf(intent.actor)==null&&snapshot.positionOf(intent.target)==null){
            val positions=mapOf(intent.actor to CombatPosition.Exact(0,0),intent.target to CombatPosition.Exact(0,0))
            CombatSpatialState(positions)
        }else CombatSpatialState(emptyMap())
        return UniversalCombatEngine(rulesetUid).resolve(UniversalCombatRequest(
            intent=intent,snapshot=snapshot,ability=CombatAbilityContract(intent.abilityUid),spatialState=positioned,reaction=reaction,
            objective=CombatObjective(intent.objectiveUid,CombatObjectiveKind.DISABLE)
        ))
    }
}
