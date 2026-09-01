package com.rpgos.app

import java.math.BigInteger
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/** World-agnostic spatial primitives owned by Phase50. Phase63 remains the LOD/scale owner. */
sealed interface CombatPosition {
    data class Exact(val xMillimetres:Long,val yMillimetres:Long,val zMillimetres:Long=0):CombatPosition
    data class Grid(val gridUid:String,val column:Long,val row:Long,val layer:Long=0):CombatPosition{init{require(gridUid.isNotBlank())}}
    data class Zone(val zoneUid:String):CombatPosition{init{require(zoneUid.isNotBlank())}}
    data class RangeBand(val anchor:DomainRef,val bandUid:String,val ordinal:Int):CombatPosition{init{require(bandUid.isNotBlank()&&ordinal>=0)}}
    data class Formation(val formationUid:String,val slotUid:String):CombatPosition{init{require(formationUid.isNotBlank()&&slotUid.isNotBlank())}}
}

data class CombatVector(val x:Long,val y:Long,val z:Long=0)
data class CombatArea(val areaUid:String,val centre:CombatPosition,val radiusMillimetres:Long){init{require(areaUid.isNotBlank()&&radiusMillimetres>=0)}}
data class CombatSpatialState(
    val positions:Map<DomainRef,CombatPosition>,
    val orientationsMilliDegrees:Map<DomainRef,Long> = emptyMap(),
    val velocitiesMillimetresPerTick:Map<DomainRef,CombatVector> = emptyMap()
)
data class CombatSpatialQuery(val actor:DomainRef,val target:DomainRef,val maximumRangeMillimetres:Long,val trajectoryRequired:Boolean=false){init{require(maximumRangeMillimetres>=0)}}
sealed interface CombatSpatialResult{
    data class Feasible(val distanceMillimetres:Long,val evidenceUid:String):CombatSpatialResult
    data class Rejected(val reasonUid:String):CombatSpatialResult
}

fun interface CombatSpatialResolver{
    fun evaluate(query:CombatSpatialQuery,state:CombatSpatialState,snapshot:ImmutableCombatSnapshot):CombatSpatialResult
}

/** Safe generic resolver. World packs may replace this contract, never the Combat Engine. */
class UniversalCombatSpatialResolver:CombatSpatialResolver{
    override fun evaluate(query:CombatSpatialQuery,state:CombatSpatialState,snapshot:ImmutableCombatSnapshot):CombatSpatialResult{
        val left=state.positions[query.actor]?:snapshot.positionOf(query.actor)
            ?:return CombatSpatialResult.Rejected("ACTOR_POSITION_UNKNOWN")
        val right=state.positions[query.target]?:snapshot.positionOf(query.target)
            ?:return CombatSpatialResult.Rejected("TARGET_POSITION_UNKNOWN")
        val distance=distance(left,right)?:return CombatSpatialResult.Rejected("SPATIAL_REPRESENTATION_REQUIRES_WORLD_RESOLVER")
        if(distance>query.maximumRangeMillimetres)return CombatSpatialResult.Rejected("TARGET_OUT_OF_RANGE")
        if(query.trajectoryRequired&&snapshot.spatialFacts["trajectory:${query.actor.uid}:${query.target.uid}"]=="BLOCKED"){
            return CombatSpatialResult.Rejected("TRAJECTORY_BLOCKED")
        }
        return CombatSpatialResult.Feasible(distance,"SPACE:${hash("$left|$right|$distance")}")
    }
    private fun distance(a:CombatPosition,b:CombatPosition):Long?=when{
        a is CombatPosition.Exact&&b is CombatPosition.Exact->
            (abs(a.xMillimetres-b.xMillimetres)+abs(a.yMillimetres-b.yMillimetres)+abs(a.zMillimetres-b.zMillimetres))
        a is CombatPosition.Grid&&b is CombatPosition.Grid&&a.gridUid==b.gridUid->
            (abs(a.column-b.column)+abs(a.row-b.row)+abs(a.layer-b.layer))*1_000L
        a is CombatPosition.Zone&&b is CombatPosition.Zone->if(a.zoneUid==b.zoneUid)0L else 100_000L
        a is CombatPosition.RangeBand&&b is CombatPosition.RangeBand&&a.anchor==b.anchor->abs(a.ordinal-b.ordinal)*10_000L
        a is CombatPosition.Formation&&b is CombatPosition.Formation&&a.formationUid==b.formationUid->if(a.slotUid==b.slotUid)0L else 1_000L
        else->null
    }
}

internal fun ImmutableCombatSnapshot.positionOf(ref:DomainRef):CombatPosition?{
    val key="${ref.kindUid}:${ref.uid}"
    spatialFacts["$key:zone"]?.let{return CombatPosition.Zone(it)}
    val x=spatialFacts["$key:x_mm"]?.toLongOrNull();val y=spatialFacts["$key:y_mm"]?.toLongOrNull()
    return if(x!=null&&y!=null)CombatPosition.Exact(x,y,spatialFacts["$key:z_mm"]?.toLongOrNull()?:0L) else null
}

enum class CombatActionPhase { DECLARE, PREPARE, ACTION_COMMIT, EXECUTE, IMPACT, RECOVERY }
data class CombatPhaseWindow(val phase:CombatActionPhase,val startsAtTick:Long,val endsAtTick:Long){init{require(startsAtTick>=0&&endsAtTick>=startsAtTick)}}
data class CombatActionSchedule(val actionUid:String,val windows:List<CombatPhaseWindow>){
    init{require(actionUid.isNotBlank()&&windows.map{it.phase}.distinct().size==windows.size&&windows.zipWithNext().all{it.first.endsAtTick<=it.second.startsAtTick})}
    fun window(phase:CombatActionPhase)=windows.single{it.phase==phase}
}

class DeterministicCombatScheduler{
    fun schedule(intent:CombatIntent,snapshot:ImmutableCombatSnapshot):CombatActionSchedule{
        val prepare=snapshot.timingFacts["ability:${intent.abilityUid}:prepare_ticks"]?.coerceAtLeast(0)?:0
        val execute=snapshot.timingFacts["ability:${intent.abilityUid}:execute_ticks"]?.coerceAtLeast(1)?:1
        val recovery=snapshot.timingFacts["ability:${intent.abilityUid}:recovery_ticks"]?.coerceAtLeast(0)?:0
        var tick=intent.declaredAtOrder
        fun phase(kind:CombatActionPhase,duration:Long):CombatPhaseWindow{
            val result=CombatPhaseWindow(kind,tick,tick+duration);tick=result.endsAtTick;return result
        }
        return CombatActionSchedule(intent.intentUid,listOf(
            phase(CombatActionPhase.DECLARE,0),phase(CombatActionPhase.PREPARE,prepare),phase(CombatActionPhase.ACTION_COMMIT,0),
            phase(CombatActionPhase.EXECUTE,execute),phase(CombatActionPhase.IMPACT,0),phase(CombatActionPhase.RECOVERY,recovery)
        ))
    }
}

data class CombatAbilityContract(
    val abilityUid:String,
    val contestAttributeUid:String="POWER",
    val contestSkillUid:String="SKILL",
    val defenceAttributeUid:String="DEFENCE",
    val defenceSkillUid:String="AGILITY",
    val maximumRangeMillimetres:Long=2_000,
    val resourceUid:String?=null,
    val resourceCost:Long=0,
    val requiredEquipmentKinds:Set<String> = emptySet(),
    val targetKindUids:Set<String> = emptySet(),
    val trajectoryRequired:Boolean=false,
    val areaRadiusMillimetres:Long?=null,
    val maximumTargets:Int=1,
    val aggregateAreaProfile:AggregateAreaImpactProfile?=null,
    val aggregateDirectProfile:AggregateDirectImpactProfile?=null,
    val aggregateGroupProfile:AggregateGroupEngagementProfile?=null,
    val statusApplications:List<AbilityStatusApplication> = emptyList(),
    val effectKinds:List<UniversalMechanicalEffectKind> = listOf(UniversalMechanicalEffectKind.WOUND),
    val damageTypeUid:String="PHYSICAL"
){init{
    require(abilityUid.isNotBlank()&&listOf(contestAttributeUid,contestSkillUid,defenceAttributeUid,defenceSkillUid,damageTypeUid).none{it.isBlank()})
    require(maximumRangeMillimetres>=0&&resourceCost>=0&&resourceUid?.isBlank()!=true&&requiredEquipmentKinds.none{it.isBlank()}&&targetKindUids.none{it.isBlank()}&&effectKinds.isNotEmpty())
    require(areaRadiusMillimetres?.let{it>0}!=false&&maximumTargets in 1..256)
    require(areaRadiusMillimetres!=null||maximumTargets==1)
    require(aggregateAreaProfile==null||areaRadiusMillimetres!=null)
    require(aggregateDirectProfile==null||areaRadiusMillimetres==null)
    require(aggregateGroupProfile==null||areaRadiusMillimetres==null)
    require(statusApplications.map{it.statusEffectUid}.distinct().size==statusApplications.size)
}}

enum class UniversalStatusStackingPolicy { REFRESH, STACK_INTENSITY, KEEP_STRONGER, NON_STACKING }
data class UniversalStatusEffectDefinition(
    val statusEffectUid:String,
    val stackingPolicy:UniversalStatusStackingPolicy
){init{require(statusEffectUid.isNotBlank())}}

/** Core taxonomy. A World Pack binds abilities to these effects; it does not redefine their identity. */
object UniversalStatusEffectRegistry{
    val definitions=listOf(
        UniversalStatusEffectDefinition("BURNING",UniversalStatusStackingPolicy.STACK_INTENSITY),
        UniversalStatusEffectDefinition("POISONED",UniversalStatusStackingPolicy.STACK_INTENSITY),
        UniversalStatusEffectDefinition("PARALYZED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("FROZEN",UniversalStatusStackingPolicy.KEEP_STRONGER),
        UniversalStatusEffectDefinition("BLEEDING",UniversalStatusStackingPolicy.STACK_INTENSITY),
        UniversalStatusEffectDefinition("STUNNED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("BLINDED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("SILENCED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("SLOWED",UniversalStatusStackingPolicy.KEEP_STRONGER),
        UniversalStatusEffectDefinition("ROOTED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("CONFUSED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("FEARED",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("SLEEPING",UniversalStatusStackingPolicy.NON_STACKING),
        UniversalStatusEffectDefinition("EXHAUSTED",UniversalStatusStackingPolicy.STACK_INTENSITY),
        UniversalStatusEffectDefinition("CORRODED",UniversalStatusStackingPolicy.STACK_INTENSITY),
        UniversalStatusEffectDefinition("WET",UniversalStatusStackingPolicy.REFRESH),
        UniversalStatusEffectDefinition("ELECTRIFIED",UniversalStatusStackingPolicy.STACK_INTENSITY)
    ).associateBy{it.statusEffectUid}
    fun requireDefinition(uid:String)=requireNotNull(definitions[uid]){"RPGOS-P50:UNKNOWN_UNIVERSAL_STATUS_EFFECT:$uid"}
}

/** World-Pack-owned binding on an ability. 2_000 basis points means an exact 20% application chance. */
data class AbilityStatusApplication(val statusEffectUid:String,val applicationChanceBasisPoints:Long){init{
    require(applicationChanceBasisPoints in 1..10_000)
    UniversalStatusEffectRegistry.requireDefinition(statusEffectUid)
}}

/** Minimal Phase63-facing LOD contract pulled forward for O(1) mass-combat impact resolution. */
data class AggregateAreaImpactProfile(
    val centreEliminationBasisPoints:Long,
    val outerInjuryBasisPoints:Long
){init{
    require(centreEliminationBasisPoints in 0..10_000&&outerInjuryBasisPoints in 0..10_000)
    require(centreEliminationBasisPoints+outerInjuryBasisPoints<=10_000)
}}
data class AggregateAreaEffectDistribution(val eliminated:Long,val wounded:Long,val secondaryAffected:Long,val unaffected:Long){init{require(minOf(eliminated,wounded,secondaryAffected,unaffected)>=0)}}
class AggregateAreaEffectResolver{
    fun resolve(population:AggregateMechanicalPopulation,profile:AggregateAreaImpactProfile,impactBasisPoints:Long):AggregateAreaEffectDistribution{
        val impact=impactBasisPoints.coerceIn(0,10_000)
        fun affected(basisPoints:Long)=scaledByTwoBasisPoints(population.activeCount,basisPoints,impact)
        val eliminated=affected(profile.centreEliminationBasisPoints).coerceAtMost(population.activeCount)
        val wounded=affected(profile.outerInjuryBasisPoints).coerceAtMost(population.activeCount-eliminated)
        return AggregateAreaEffectDistribution(eliminated,wounded,0,(population.activeCount-eliminated-wounded).coerceAtLeast(0))
    }
}

/**
 * A bounded Phase63-facing profile for one overwhelmingly powerful actor engaging a GROUP/UNIT.
 * Attributes on the aggregate actor describe a representative active member, never the sum of
 * every member. Capacity and exposure prevent a sword or a fist from becoming unlimited AoE.
 */
data class AggregateDirectImpactProfile(
    val minimumPowerRatioBasisPoints:Long,
    val maximumEliminationsPerAction:Long,
    val maximumInjuriesPerAction:Long,
    val engagementExposureBasisPoints:Long
){init{
    require(minimumPowerRatioBasisPoints>=10_000)
    require(maximumEliminationsPerAction>=0&&maximumInjuriesPerAction>=0)
    require(maximumEliminationsPerAction+maximumInjuriesPerAction>0)
    require(engagementExposureBasisPoints in 1..10_000)
}}

sealed interface AggregateDirectImpactResolution{
    data class Resolved(val distribution:AggregateAreaEffectDistribution,val powerRatioBasisPoints:Long):AggregateDirectImpactResolution
    data class Rejected(val reasonUid:String):AggregateDirectImpactResolution
}

class AggregateDirectImpactResolver{
    fun resolve(
        population:AggregateMechanicalPopulation,
        profile:AggregateDirectImpactProfile,
        attackerPower:Long,
        representativeDefenderPower:Long,
        impactBasisPoints:Long
    ):AggregateDirectImpactResolution{
        val ratio=safeProductDivision(attackerPower.coerceAtLeast(0),10_000,representativeDefenderPower.coerceAtLeast(1))
        if(ratio<profile.minimumPowerRatioBasisPoints)return AggregateDirectImpactResolution.Rejected("AGGREGATE_POWER_ADVANTAGE_INSUFFICIENT")
        val exposed=scaledByBasisPoints(population.activeCount,profile.engagementExposureBasisPoints).coerceIn(1,population.activeCount)
        val dominance=safeProductDivision(ratio-profile.minimumPowerRatioBasisPoints,10_000,profile.minimumPowerRatioBasisPoints).coerceIn(1,10_000)
        val quality=impactBasisPoints.coerceIn(0,10_000)
        val eliminated=scaledByTwoBasisPoints(exposed,dominance,quality)
            .coerceAtMost(profile.maximumEliminationsPerAction).coerceAtMost(population.activeCount)
        val remaining=(population.activeCount-eliminated).coerceAtLeast(0)
        val exposedRemaining=(exposed-eliminated).coerceAtLeast(0)
        val wounded=scaledByTwoBasisPoints(exposedRemaining,dominance,quality)
            .coerceAtMost(profile.maximumInjuriesPerAction).coerceAtMost(remaining)
        return AggregateDirectImpactResolution.Resolved(
            AggregateAreaEffectDistribution(eliminated,wounded,0,(population.activeCount-eliminated-wounded).coerceAtLeast(0)),ratio
        )
    }
}

/** O(1) force-on-force resolution. Both populations stay aggregated; no member expansion occurs. */
data class AggregateGroupEngagementProfile(
    val defenderExposureBasisPoints:Long=2_500,
    val eliminationBasisPoints:Long=1_500,
    val injuryBasisPoints:Long=3_500,
    val minimumImpactQualityBasisPoints:Long=1_000
){init{
    require(defenderExposureBasisPoints in 1..10_000)
    require(eliminationBasisPoints in 0..10_000&&injuryBasisPoints in 0..10_000)
    require(eliminationBasisPoints+injuryBasisPoints<=10_000)
    require(minimumImpactQualityBasisPoints in 1..10_000)
}}

data class AggregateGroupEngagementResolution(
    val distribution:AggregateAreaEffectDistribution,
    val forceRatioBasisPoints:Long
)

class AggregateGroupEngagementResolver{
    fun resolve(
        attackers:AggregateMechanicalPopulation,
        defenders:AggregateMechanicalPopulation,
        profile:AggregateGroupEngagementProfile,
        representativeAttackPower:Long,
        representativeDefencePower:Long,
        contestMagnitude:Long
    ):AggregateGroupEngagementResolution{
        val attackForce=BigInteger.valueOf(attackers.activeCount).multiply(BigInteger.valueOf(representativeAttackPower.coerceAtLeast(1)))
        val defenceForce=BigInteger.valueOf(defenders.activeCount).multiply(BigInteger.valueOf(representativeDefencePower.coerceAtLeast(1)))
        val ratio=attackForce.multiply(BigInteger.valueOf(10_000)).divide(defenceForce.max(BigInteger.ONE)).coerceToLong()
        val exposed=scaledByBasisPoints(defenders.activeCount,profile.defenderExposureBasisPoints).coerceIn(1,defenders.activeCount)
        val quality=(profile.minimumImpactQualityBasisPoints+contestMagnitude.coerceAtLeast(0)*100).coerceAtMost(10_000)
        val forceMultiplier=ratio.coerceIn(2_500,40_000)
        val effectiveQuality=safeProductDivision(quality,forceMultiplier,10_000).coerceIn(1,10_000)
        val eliminated=scaledByTwoBasisPoints(exposed,profile.eliminationBasisPoints,effectiveQuality).coerceAtMost(defenders.activeCount)
        val remaining=(defenders.activeCount-eliminated).coerceAtLeast(0)
        val wounded=scaledByTwoBasisPoints((exposed-eliminated).coerceAtLeast(0),profile.injuryBasisPoints,effectiveQuality).coerceAtMost(remaining)
        return AggregateGroupEngagementResolution(
            AggregateAreaEffectDistribution(eliminated,wounded,0,(defenders.activeCount-eliminated-wounded).coerceAtLeast(0)),ratio
        )
    }
}

private fun scaledByBasisPoints(value:Long,basisPoints:Long):Long=
    safeProductDivision(value,basisPoints,10_000)

private fun scaledByTwoBasisPoints(value:Long,firstBasisPoints:Long,secondBasisPoints:Long):Long=
    BigInteger.valueOf(value).multiply(BigInteger.valueOf(firstBasisPoints)).multiply(BigInteger.valueOf(secondBasisPoints))
        .divide(BigInteger.valueOf(100_000_000L)).min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()

private fun safeProductDivision(value:Long,multiplier:Long,divisor:Long):Long=
    BigInteger.valueOf(value).multiply(BigInteger.valueOf(multiplier)).divide(BigInteger.valueOf(divisor))
        .min(BigInteger.valueOf(Long.MAX_VALUE)).toLong()

sealed interface CombatAreaSelection{
    data class Selected(val targets:List<DomainRef>,val evidenceUid:String):CombatAreaSelection
    data class Rejected(val reasonUid:String):CombatAreaSelection
}

/** Core selects every materialized actor in the area; AI cannot quietly remove collateral targets. */
class CombatAreaTargetSelector(private val spatial:CombatSpatialResolver=UniversalCombatSpatialResolver()){
    fun select(actor:DomainRef,centre:DomainRef,ability:CombatAbilityContract,state:CombatSpatialState,snapshot:ImmutableCombatSnapshot):CombatAreaSelection{
        val radius=ability.areaRadiusMillimetres?:return CombatAreaSelection.Selected(listOf(centre),"AREA:SINGLE")
        val candidates=snapshot.actors.asSequence().map{it.actor}.filter{it!=actor}
            .filter{ability.targetKindUids.isEmpty()||it.kindUid in ability.targetKindUids}.sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid}).toList()
        val selected=mutableListOf<DomainRef>()
        candidates.forEach{candidate->when(val result=spatial.evaluate(CombatSpatialQuery(centre,candidate,radius),state,snapshot)){
            is CombatSpatialResult.Feasible->selected+=candidate
            is CombatSpatialResult.Rejected->if(result.reasonUid !in setOf("TARGET_OUT_OF_RANGE"))return CombatAreaSelection.Rejected("AREA_${result.reasonUid}")
        }}
        if(centre !in selected)return CombatAreaSelection.Rejected("AREA_PRIMARY_TARGET_NOT_SELECTED")
        if(selected.size>ability.maximumTargets)return CombatAreaSelection.Rejected("AREA_TARGET_LIMIT_EXCEEDED")
        return CombatAreaSelection.Selected(selected,"AREA:${hash("$centre|$radius|$selected")}")
    }
}

sealed interface CombatEligibilityResult{
    data class Eligible(val evidenceUids:List<String>):CombatEligibilityResult
    data class Rejected(val reasonUid:String):CombatEligibilityResult
}

class CombatEligibilityGate{
    fun evaluate(intent:CombatIntent,ability:CombatAbilityContract,snapshot:ImmutableCombatSnapshot):CombatEligibilityResult{
        val actor=snapshot.actors.singleOrNull{it.actor==intent.actor}?:return CombatEligibilityResult.Rejected("ACTOR_NOT_MATERIALIZED")
        if(actor.materialization==MechanicalStateMaterialization.SEED_ONLY)return CombatEligibilityResult.Rejected("ACTOR_MECHANICAL_STATE_INCOMPLETE")
        if(intent.abilityUid!=ability.abilityUid||intent.abilityUid !in actor.executableAbilityUids)return CombatEligibilityResult.Rejected("ABILITY_UNAVAILABLE")
        if(ability.targetKindUids.isNotEmpty()&&intent.target.kindUid !in ability.targetKindUids)return CombatEligibilityResult.Rejected("TARGET_INELIGIBLE")
        if(ability.resourceUid!=null){
            val resource=actor.resources.singleOrNull{it.resourceUid==ability.resourceUid}?:return CombatEligibilityResult.Rejected("ACTION_RESOURCE_MISSING")
            if(resource.current<ability.resourceCost)return CombatEligibilityResult.Rejected("ACTION_RESOURCE_INSUFFICIENT")
        }
        if(ability.requiredEquipmentKinds.isNotEmpty()&&actor.equipmentRefs.none{it.kindUid in ability.requiredEquipmentKinds})return CombatEligibilityResult.Rejected("REQUIRED_EQUIPMENT_MISSING")
        if(actor.conditions.any{it.conditionUid=="INCAPACITATED"&&it.intensity>0})return CombatEligibilityResult.Rejected("ACTOR_INCAPACITATED")
        val cooldown=snapshot.timingFacts["${intent.actor.kindUid}:${intent.actor.uid}:cooldown:${intent.abilityUid}"]?:0
        if(cooldown>snapshot.atOrder)return CombatEligibilityResult.Rejected("ABILITY_IN_RECOVERY")
        if(snapshot.timingFacts["world_rule:${intent.intentUid}:allowed"]==0L)return CombatEligibilityResult.Rejected("WORLD_RULE_REJECTED")
        return CombatEligibilityResult.Eligible(listOf("ABILITY:${ability.abilityUid}","STATE:${actor.stateVersion}"))
    }
}

data class CombatDetectionRequest(val observer:DomainRef,val subject:DomainRef,val requiredQualityBasisPoints:Long=1,val beforeOrder:Long){init{require(requiredQualityBasisPoints in 0..10_000&&beforeOrder>=0)}}
sealed interface CombatDetectionResult{
    data class Detected(val evidenceUid:String,val qualityBasisPoints:Long):CombatDetectionResult
    data class Hidden(val reasonUid:String):CombatDetectionResult
}
class CombatDetectionEngine{
    fun detect(request:CombatDetectionRequest,snapshot:ImmutableCombatSnapshot):CombatDetectionResult{
        val evidence=snapshot.perception.filter{it.observer==request.observer&&it.perceivedSubject==request.subject&&it.availableAtOrder<=request.beforeOrder}
            .maxWithOrNull(compareBy<CombatPerceptionEvidence>{it.qualityBasisPoints}.thenBy{it.evidenceUid})
            ?:return CombatDetectionResult.Hidden("THREAT_NOT_PERCEIVED")
        return if(evidence.qualityBasisPoints>=request.requiredQualityBasisPoints)CombatDetectionResult.Detected(evidence.evidenceUid,evidence.qualityBasisPoints)
        else CombatDetectionResult.Hidden("PERCEPTION_QUALITY_INSUFFICIENT")
    }
}

enum class CombatInteractionKind { BLOCK, PARRY, INTERCEPTION, SIMULTANEOUS_CONTEST, COLLISION, COUNTER, INTERRUPT }
data class CombatActionInteraction(val interactionUid:String,val kind:CombatInteractionKind,val primaryIntentUid:String,val opposingIntentUid:String,val priority:Long=0){init{require(interactionUid.isNotBlank()&&primaryIntentUid.isNotBlank()&&opposingIntentUid.isNotBlank())}}
sealed interface CombatInteractionResult{
    data class Applied(val attackModifier:Long,val defenceModifier:Long,val evidenceUid:String):CombatInteractionResult
    data class Rejected(val reasonUid:String):CombatInteractionResult
}
class CombatInteractionResolver{
    fun resolve(interaction:CombatActionInteraction?,primary:CombatActionSchedule,opposing:CombatActionSchedule?):CombatInteractionResult{
        if(interaction==null)return CombatInteractionResult.Applied(0,0,"INTERACTION:NONE")
        if(opposing==null)return CombatInteractionResult.Rejected("OPPOSING_ACTION_MISSING")
        if(interaction.primaryIntentUid!=primary.actionUid||interaction.opposingIntentUid!=opposing.actionUid)return CombatInteractionResult.Rejected("INTERACTION_ACTION_IDENTITY_MISMATCH")
        val overlap=primary.window(CombatActionPhase.EXECUTE).startsAtTick<=opposing.window(CombatActionPhase.IMPACT).endsAtTick&&
            opposing.window(CombatActionPhase.EXECUTE).startsAtTick<=primary.window(CombatActionPhase.IMPACT).endsAtTick
        if(!overlap&&interaction.kind in setOf(CombatInteractionKind.PARRY,CombatInteractionKind.COLLISION,CombatInteractionKind.SIMULTANEOUS_CONTEST))return CombatInteractionResult.Rejected("INTERACTION_WINDOW_MISSED")
        val modifiers=when(interaction.kind){
            CombatInteractionKind.BLOCK->0L to 20L;CombatInteractionKind.PARRY->-5L to 25L;CombatInteractionKind.INTERCEPTION->-15L to 10L
            CombatInteractionKind.SIMULTANEOUS_CONTEST->0L to 0L;CombatInteractionKind.COLLISION->10L to 10L
            CombatInteractionKind.COUNTER->-10L to 15L;CombatInteractionKind.INTERRUPT->-25L to 5L
        }
        return CombatInteractionResult.Applied(modifiers.first,modifiers.second,"INTERACTION:${interaction.interactionUid}")
    }
}

data class TypedContest(
    val contestUid:String,val attacker:DomainRef,val defender:DomainRef,val attackBase:Long,val defenceBase:Long,
    val lowerBound:Long=-10_000,val upperBound:Long=10_000,val rulesetUid:String
){init{require(contestUid.isNotBlank()&&lowerBound<upperBound&&rulesetUid.isNotBlank())}}
data class TypedContestOutcome(val margin:Long,val degreeBasisPoints:Long,val attackTotal:Long,val defenceTotal:Long,val drawUids:List<String>)
class DeterministicContestResolver{
    fun resolve(contest:TypedContest,seed:String):Pair<TypedContestOutcome,List<Long>>{
        val mismatch=contest.attackBase-contest.defenceBase
        if(mismatch>=contest.upperBound)return TypedContestOutcome(contest.upperBound,10_000,contest.attackBase,contest.defenceBase,emptyList()) to emptyList()
        if(mismatch<=contest.lowerBound)return TypedContestOutcome(contest.lowerBound,-10_000,contest.attackBase,contest.defenceBase,emptyList()) to emptyList()
        val random=Random(seed.take(16).toULong(16).toLong())
        val attackDraw=random.nextInt(10_001).toLong();val defenceDraw=random.nextInt(10_001).toLong()
        val attack=Math.addExact(contest.attackBase,attackDraw/100);val defence=Math.addExact(contest.defenceBase,defenceDraw/100)
        val margin=(attack-defence).coerceIn(contest.lowerBound,contest.upperBound)
        val degree=(margin*10_000/max(1L,max(abs(attack),abs(defence)))).coerceIn(-10_000,10_000)
        return TypedContestOutcome(margin,degree,attack,defence,listOf("ATTACK_DRAW","DEFENCE_DRAW")) to listOf(attackDraw,defenceDraw)
    }
}

enum class TargetComponentKind { ANATOMY, LIMB, WING, PROTECTION_LAYER, VEHICLE_MODULE, SHIP_SYSTEM, STRUCTURE, GENERIC }
data class TargetComponent(val componentUid:String,val owner:DomainRef,val kind:TargetComponentKind,val protection:Long=0,val resistanceBasisPoints:Map<String,Long> = emptyMap(),val enabled:Boolean=true){init{require(componentUid.isNotBlank()&&protection>=0&&resistanceBasisPoints.values.all{it in -100_000..100_000})}}
data class TargetComponentSelection(val component:TargetComponent?,val evidenceUid:String)
class TargetComponentResolver{
    fun select(target:MechanicalActorView,intent:CombatIntent,snapshot:ImmutableCombatSnapshot):TargetComponentSelection{
        val requested=snapshot.spatialFacts["intent:${intent.intentUid}:target_component"]
        val componentUid=requested?:target.traitUids.firstOrNull{it.startsWith("COMPONENT:")}?.substringAfter("COMPONENT:")
        return TargetComponentSelection(componentUid?.let{TargetComponent(it,target.actor,TargetComponentKind.GENERIC)},"COMPONENT:${componentUid?:"WHOLE"}")
    }
}

data class CombatProtectionResult(val rawMagnitude:Long,val protectionAbsorbed:Long,val resistanceBasisPoints:Long,val finalMagnitude:Long,val evidenceUid:String)
class CombatProtectionResolver{
    fun apply(raw:Long,damageTypeUid:String,target:MechanicalActorView,component:TargetComponent?):CombatProtectionResult{
        val protection=component?.protection?:target.attributes["ARMOR"]?:0
        val afterProtection=(raw-protection).coerceAtLeast(0)
        val resistance=(component?.resistanceBasisPoints?.get(damageTypeUid)?:target.resistanceBasisPoints[damageTypeUid]?:0).coerceIn(-100_000,100_000)
        val final=Math.multiplyExact(afterProtection,10_000-resistance)/10_000
        return CombatProtectionResult(raw,raw-afterProtection,resistance,final.coerceAtLeast(0),"PROTECTION:$protection:$resistance")
    }
}

enum class CombatObjectiveKind { KILL, CAPTURE, DELAY, ESCAPE, PROTECT, HOLD, SURVIVE, DISABLE, REACH, DESTROY, BREAK_FORMATION, FORCE_SURRENDER, EXTENSION }
data class CombatObjective(val objectiveUid:String,val kind:CombatObjectiveKind,val threshold:Long=1,val extensionUid:String?=null){init{require(objectiveUid.isNotBlank()&&threshold>=0&&extensionUid?.isBlank()!=true&&((kind==CombatObjectiveKind.EXTENSION)==(extensionUid!=null)))}}
data class CombatObjectiveOutcome(val objectiveUid:String,val satisfied:Boolean,val progress:Long,val evidenceUid:String)
class CombatObjectiveEvaluator{
    fun evaluate(objective:CombatObjective,finalMagnitude:Long,effects:List<UniversalMechanicalEffect>):CombatObjectiveOutcome{
        val progress=when(objective.kind){
            CombatObjectiveKind.ESCAPE,CombatObjectiveKind.REACH->effects.filter{it.kind==UniversalMechanicalEffectKind.MOVEMENT}.sumOf{abs(it.magnitude)}
            CombatObjectiveKind.BREAK_FORMATION->effects.filter{it.kind==UniversalMechanicalEffectKind.FORMATION}.sumOf{abs(it.magnitude)}
            CombatObjectiveKind.FORCE_SURRENDER->effects.filter{it.kind==UniversalMechanicalEffectKind.MORALE}.sumOf{abs(it.magnitude)}
            else->finalMagnitude
        }
        return CombatObjectiveOutcome(objective.objectiveUid,progress>=objective.threshold,progress,"OBJECTIVE:${objective.objectiveUid}:$progress")
    }
}

data class UniversalCombatRequest(
    val intent:CombatIntent,
    val snapshot:ImmutableCombatSnapshot,
    val ability:CombatAbilityContract,
    val spatialState:CombatSpatialState=CombatSpatialState(emptyMap()),
    val reaction:CombatReactionRequest?=null,
    val opposingIntent:CombatIntent?=null,
    val interaction:CombatActionInteraction?=null,
    val objective:CombatObjective=CombatObjective(intent.objectiveUid,CombatObjectiveKind.DISABLE)
)

/** The single canonical, deterministic Phase50 pipeline. It is pure and has no DB authority. */
class UniversalCombatEngine(
    private val rulesetUid:String="RPGOS-UNIVERSAL-COMBAT-V2",
    private val spatialResolver:CombatSpatialResolver=UniversalCombatSpatialResolver()
){
    fun resolve(request:UniversalCombatRequest):CombatResolution{
        val intent=request.intent;val snapshot=request.snapshot
        if(intent.campaignUid!=snapshot.campaignUid)return CombatResolution.Rejected("CROSS_CAMPAIGN_COMBAT")
        val attacker=snapshot.actors.singleOrNull{it.actor==intent.actor}?:return CombatResolution.Rejected("ACTOR_NOT_MATERIALIZED")
        snapshot.actors.singleOrNull{it.actor==intent.target}?:return CombatResolution.Rejected("TARGET_NOT_MATERIALIZED")
        when(val eligibility=CombatEligibilityGate().evaluate(intent,request.ability,snapshot)){
            is CombatEligibilityResult.Rejected->return CombatResolution.Rejected(eligibility.reasonUid)
            is CombatEligibilityResult.Eligible->Unit
        }
        when(val spatial=spatialResolver.evaluate(CombatSpatialQuery(intent.actor,intent.target,request.ability.maximumRangeMillimetres,request.ability.trajectoryRequired),request.spatialState,snapshot)){
            is CombatSpatialResult.Rejected->return CombatResolution.Rejected(spatial.reasonUid)
            is CombatSpatialResult.Feasible->Unit
        }
        val targets=when(val area=CombatAreaTargetSelector(spatialResolver).select(intent.actor,intent.target,request.ability,request.spatialState,snapshot)){
            is CombatAreaSelection.Rejected->return CombatResolution.Rejected(area.reasonUid)
            is CombatAreaSelection.Selected->area.targets
        }
        val scheduler=DeterministicCombatScheduler();val schedule=scheduler.schedule(intent,snapshot)
        val reactionEligibility=request.reaction?.let{reaction->
            val impact=schedule.window(CombatActionPhase.IMPACT).startsAtTick
            when(val detection=CombatDetectionEngine().detect(CombatDetectionRequest(reaction.reactor,intent.actor,1,impact),snapshot)){
                is CombatDetectionResult.Hidden->return CombatResolution.Rejected("ATTACK_NOT_PERCEIVED")
                is CombatDetectionResult.Detected->CombatReactionGate().evaluate(reaction,intent,snapshot,impact)
            }
        }
        if(reactionEligibility is CombatReactionEligibility.Rejected)return CombatResolution.Rejected(reactionEligibility.reasonUid)
        val opposingSchedule=request.opposingIntent?.let{scheduler.schedule(it,snapshot)}
        val interaction=when(val result=CombatInteractionResolver().resolve(request.interaction,schedule,opposingSchedule)){
            is CombatInteractionResult.Rejected->return CombatResolution.Rejected(result.reasonUid)
            is CombatInteractionResult.Applied->result
        }
        val input=canonicalInput(request,schedule)
        val seed=hash("$rulesetUid|$input")
        val attackBase=attacker.attributes[request.ability.contestAttributeUid].orZero()+attacker.attributes[request.ability.contestSkillUid].orZero()+interaction.attackModifier
        val draws=mutableListOf<Long>();val effects=mutableListOf<UniversalMechanicalEffect>();var totalMagnitude=0L
        targets.forEach{targetRef->
            val defender=snapshot.actors.single{it.actor==targetRef}
            val defenceBase=defender.attributes[request.ability.defenceAttributeUid].orZero()+defender.attributes[request.ability.defenceSkillUid].orZero()+interaction.defenceModifier+
                if(reactionEligibility is CombatReactionEligibility.Eligible&&request.reaction?.reactor==targetRef)20 else 0
            val targetIntent=intent.copy(target=targetRef)
            val (contest,targetDraws)=DeterministicContestResolver().resolve(TypedContest("${intent.intentUid}:${targetRef.kindUid}:${targetRef.uid}",intent.actor,targetRef,attackBase,defenceBase,rulesetUid=rulesetUid),hash("$seed|$targetRef"))
            draws+=targetDraws
            val component=TargetComponentResolver().select(defender,targetIntent,snapshot)
            val protection=CombatProtectionResolver().apply(contest.margin.coerceAtLeast(0),request.ability.damageTypeUid,defender,component.component)
            val interactionOnly=request.ability.effectKinds.distinct()==listOf(UniversalMechanicalEffectKind.INTERACTION)
            val mechanicalMagnitude=if(interactionOnly)abs(contest.margin).coerceAtLeast(1) else protection.finalMagnitude
            val objectiveMagnitude=if(interactionOnly&&contest.margin<=0)0 else protection.finalMagnitude
            val groupAggregate=attacker.aggregatePopulation?.let{attackers->defender.aggregatePopulation?.takeIf{request.ability.areaRadiusMillimetres==null}?.let{defenders->
                val profile=request.ability.aggregateGroupProfile?:return CombatResolution.Rejected("AGGREGATE_GROUP_PROFILE_REQUIRED")
                AggregateGroupEngagementResolver().resolve(attackers,defenders,profile,attackBase,defenceBase,protection.finalMagnitude)
            }}
            val directAggregate=defender.aggregatePopulation?.takeIf{request.ability.areaRadiusMillimetres==null&&groupAggregate==null}?.let{population->
                val profile=request.ability.aggregateDirectProfile?:return CombatResolution.Rejected("AGGREGATE_DIRECT_PROFILE_REQUIRED")
                when(val resolution=AggregateDirectImpactResolver().resolve(population,profile,attackBase,defenceBase,protection.finalMagnitude.coerceIn(0,10_000))){
                    is AggregateDirectImpactResolution.Rejected->return CombatResolution.Rejected(resolution.reasonUid)
                    is AggregateDirectImpactResolution.Resolved->resolution
                }
            }
            if(defender.aggregatePopulation!=null&&request.ability.areaRadiusMillimetres!=null&&request.ability.aggregateAreaProfile==null){
                return CombatResolution.Rejected("AGGREGATE_AREA_PROFILE_REQUIRED")
            }
            totalMagnitude=Math.addExact(totalMagnitude,objectiveMagnitude)
            effects+=materializeEffects(request,targetIntent,mechanicalMagnitude,component.component,directAggregate,groupAggregate,contest.margin>0)
            effects+=materializeStatusEffects(request,targetIntent,defender,protection.finalMagnitude,seed,draws)
        }
        val objective=CombatObjectiveEvaluator().evaluate(request.objective,totalMagnitude,effects)
        val outcome=when{objective.satisfied->"OBJECTIVE_SATISFIED";effects.isEmpty()->"NO_EFFECT";else->"EFFECT_APPLIED"}
        val output=listOf(outcome,effects,objective).joinToString("|")
        return CombatResolution.Resolved(outcome,effects,DeterministicMechanicsEvidence(
            "PROOF:${hash("$input|$output")}",rulesetUid,snapshot.fingerprint,seed,draws,hash(input),hash(output)
        ))
    }

    private fun materializeEffects(
        request:UniversalCombatRequest,
        intent:CombatIntent,
        magnitude:Long,
        component:TargetComponent?,
        directAggregate:AggregateDirectImpactResolution.Resolved?,
        groupAggregate:AggregateGroupEngagementResolution?,
        contestSucceeded:Boolean
    ):List<UniversalMechanicalEffect>{
        if(magnitude<=0)return emptyList()
        val population=request.snapshot.actors.single{it.actor==intent.target}.aggregatePopulation
        val aggregateProfile=request.ability.aggregateAreaProfile
        if(population!=null&&aggregateProfile!=null){
            val distribution=AggregateAreaEffectResolver().resolve(population,aggregateProfile,magnitude.coerceIn(0,10_000))
            val common=mapOf("aggregation_level" to "PHASE63_LOD1","population_total" to population.totalCount.toString(),"impact_mode" to "AREA")
            return buildList{
                if(distribution.eliminated>0)add(UniversalMechanicalEffect("COMBAT:${request.intent.intentUid}:${intent.target.uid}:ELIMINATED",UniversalMechanicalEffectKind.AGGREGATE_ELIMINATION,intent.target,distribution.eliminated,common,"${request.intent.intentUid}"))
                if(distribution.wounded>0)add(UniversalMechanicalEffect("COMBAT:${request.intent.intentUid}:${intent.target.uid}:WOUNDED",UniversalMechanicalEffectKind.AGGREGATE_INJURY,intent.target,distribution.wounded,common,"${request.intent.intentUid}"))
            }
        }
        if(population!=null&&directAggregate!=null){
            val distribution=directAggregate.distribution
            val common=mapOf(
                "aggregation_level" to "PHASE63_LOD1","population_total" to population.totalCount.toString(),
                "impact_mode" to "DIRECT_DOMINANCE","power_ratio_basis_points" to directAggregate.powerRatioBasisPoints.toString()
            )
            return buildList{
                if(distribution.eliminated>0)add(UniversalMechanicalEffect("COMBAT:${request.intent.intentUid}:${intent.target.uid}:ELIMINATED",UniversalMechanicalEffectKind.AGGREGATE_ELIMINATION,intent.target,distribution.eliminated,common,request.intent.intentUid))
                if(distribution.wounded>0)add(UniversalMechanicalEffect("COMBAT:${request.intent.intentUid}:${intent.target.uid}:WOUNDED",UniversalMechanicalEffectKind.AGGREGATE_INJURY,intent.target,distribution.wounded,common,request.intent.intentUid))
            }
        }
        if(population!=null&&groupAggregate!=null){
            val distribution=groupAggregate.distribution
            val common=mapOf(
                "aggregation_level" to "PHASE63_LOD1","population_total" to population.totalCount.toString(),
                "impact_mode" to "GROUP_ENGAGEMENT","force_ratio_basis_points" to groupAggregate.forceRatioBasisPoints.toString()
            )
            return buildList{
                if(distribution.eliminated>0)add(UniversalMechanicalEffect("COMBAT:${request.intent.intentUid}:${intent.target.uid}:ELIMINATED",UniversalMechanicalEffectKind.AGGREGATE_ELIMINATION,intent.target,distribution.eliminated,common,request.intent.intentUid))
                if(distribution.wounded>0)add(UniversalMechanicalEffect("COMBAT:${request.intent.intentUid}:${intent.target.uid}:WOUNDED",UniversalMechanicalEffectKind.AGGREGATE_INJURY,intent.target,distribution.wounded,common,request.intent.intentUid))
            }
        }
        return buildList{
            request.ability.effectKinds.distinct().forEachIndexed{index,kind->
                val signed=when(kind){
                    UniversalMechanicalEffectKind.RESOURCE_DELTA,UniversalMechanicalEffectKind.MORALE,UniversalMechanicalEffectKind.COHESION->-magnitude
                    else->magnitude
                }
                add(UniversalMechanicalEffect(
                    "COMBAT:${request.intent.intentUid}:${intent.target.kindUid}:${intent.target.uid}:E$index",kind,intent.target,signed,
                    buildMap{
                        put("damage_type_uid",request.ability.damageTypeUid)
                        component?.let{put("component_uid",it.componentUid)}
                        if(kind==UniversalMechanicalEffectKind.WOUND)put("severity_uid",when{magnitude>100->"SEVERE";magnitude>30->"MODERATE";else->"LIGHT"})
                        if(kind==UniversalMechanicalEffectKind.RESOURCE_DELTA)request.ability.resourceUid?.let{put("resource_uid",it)}
                        if(kind==UniversalMechanicalEffectKind.INTERACTION){
                            val outcome=if(contestSucceeded)"CONTACT_SUCCESS" else "CONTACT_EVADED"
                            put("track_uid","CONTEST:$outcome");put("interaction_outcome_uid",outcome)
                        }
                    },request.intent.intentUid
                ))
            }
        }
    }

    private fun materializeStatusEffects(
        request:UniversalCombatRequest,
        intent:CombatIntent,
        defender:MechanicalActorView,
        impactMagnitude:Long,
        seed:String,
        draws:MutableList<Long>
    ):List<UniversalMechanicalEffect>{
        if(impactMagnitude<=0||request.ability.statusApplications.isEmpty())return emptyList()
        return request.ability.statusApplications.sortedBy{it.statusEffectUid}.mapNotNull{application->
            val population=defender.aggregatePopulation
            if(population!=null){
                val affected=scaledByTwoBasisPoints(population.activeCount,application.applicationChanceBasisPoints,impactMagnitude.coerceIn(0,10_000))
                if(affected<=0)return@mapNotNull null
                UniversalMechanicalEffect(
                    "COMBAT:${request.intent.intentUid}:${intent.target.uid}:STATUS:${application.statusEffectUid}",
                    UniversalMechanicalEffectKind.AGGREGATE_CONDITION,intent.target,affected,
                    mapOf(
                        "aggregation_level" to "PHASE63_LOD1","population_total" to population.totalCount.toString(),
                        "condition_uid" to application.statusEffectUid,"application_chance_basis_points" to application.applicationChanceBasisPoints.toString()
                    ),request.intent.intentUid
                )
            }else{
                val roll=(hash("$seed|STATUS|${intent.target.kindUid}|${intent.target.uid}|${application.statusEffectUid}").take(16).toULong(16)%10_000u).toLong()
                draws+=roll
                if(roll>=application.applicationChanceBasisPoints)return@mapNotNull null
                UniversalMechanicalEffect(
                    "COMBAT:${request.intent.intentUid}:${intent.target.uid}:STATUS:${application.statusEffectUid}",
                    UniversalMechanicalEffectKind.CONDITION,intent.target,1,
                    mapOf("condition_uid" to application.statusEffectUid,"operation" to "ADD","application_chance_basis_points" to application.applicationChanceBasisPoints.toString(),"deterministic_roll" to roll.toString()),
                    request.intent.intentUid
                )
            }
        }
    }
    private fun canonicalInput(request:UniversalCombatRequest,schedule:CombatActionSchedule)=listOf(
        request.intent,request.snapshot.fingerprint,request.ability,request.reaction,request.opposingIntent,request.interaction,schedule,request.objective
    ).joinToString("|")
    private fun Long?.orZero()=this?:0L
}

private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}

private fun BigInteger.coerceToLong():Long=when{
    this>BigInteger.valueOf(Long.MAX_VALUE)->Long.MAX_VALUE
    this<BigInteger.valueOf(Long.MIN_VALUE)->Long.MIN_VALUE
    else->toLong()
}
