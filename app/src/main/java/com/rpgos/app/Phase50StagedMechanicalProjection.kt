package com.rpgos.app

/** A read-only projection of effects already verified in this turn. Use the SAME expansion as
 * commit: an area effect's primary fields summarize one area member, not an extra hit. */
internal object StagedMechanicalProjection {
    private val movementKinds=setOf("MOVEMENT","DISPLACEMENT")
    private val sceneKinds=setOf("LOCATION_TRANSITION","TELEPORT","SPATIAL")
    private fun kind(effect:VerifiedMechanicsCommandEffect)=effect.effectKindUid.substringAfterLast(':').uppercase()
    private fun expand(effects:List<VerifiedMechanicsEffect>)=effects.flatMap { effect ->
        val p=effect.canonicalPayload
        val target=DomainRef(requireNotNull(p["target_kind_uid"]){"P50:STAGED_TARGET_REQUIRED"},
            requireNotNull(p["target_uid"]){"P50:STAGED_TARGET_REQUIRED"})
        requireNotNull(canonicalMechanicsCommandEffects(effect,target)){"P50:STAGED_EFFECT_INVALID"}
    }

    fun hasSpatialChange(subjects:Set<DomainRef>,effects:List<VerifiedMechanicsEffect>)=
        expand(effects).any{it.target in subjects && kind(it) in movementKinds+sceneKinds}

    /** Old scene paths cannot prove reachability after a transition in the current transaction. */
    fun hasSceneTransition(subjects:Set<DomainRef>,effects:List<VerifiedMechanicsEffect>)=
        expand(effects).any{it.target in subjects && kind(it) in sceneKinds}

    fun position(ref:DomainRef,base:CombatPosition?,effects:List<VerifiedMechanicsEffect>):CombatPosition? {
        var position=base
        expand(effects).filter{it.target==ref}.forEach { impact ->
            when(kind(impact)) {
                in sceneKinds -> position=null
                in movementKinds -> position=when(val current=position) {
                    is CombatPosition.Exact -> current.copy(xMillimetres=Math.addExact(current.xMillimetres,impact.magnitude))
                    // A displacement does not tell us where an unknown/zone/grid origin was.
                    else -> null
                }
            }
        }
        return position
    }

    fun actor(base:MechanicalActorView,effects:List<VerifiedMechanicsEffect>):MechanicalActorView {
        val attributes=base.attributes.toMutableMap();var resources=base.resources
        var conditions=base.conditions;var population=base.aggregatePopulation;var version=base.stateVersion
        expand(effects).filter{it.target==base.actor}.forEach { effect ->
            val kind=kind(effect);val magnitude=effect.magnitude;val payload=effect.canonicalPayload
            version=Math.addExact(version,1)
            when(kind) {
                "WOUND"->{
                    require(magnitude>0){"P50:INVALID_STAGED_WOUND"}
                    attributes["DEFENCE"]=(attributes["DEFENCE"]?:0L).minus(magnitude).coerceAtLeast(0)
                    val wound=Math.addExact(conditions.singleOrNull{it.conditionUid=="WOUND"}?.intensity?:0,magnitude)
                    conditions=conditions.filterNot{it.conditionUid=="WOUND"}+MechanicalCondition("WOUND",wound)
                }
                "RESOURCE_DELTA","RESOURCE","HEALTH_DELTA","DAMAGE_HP","HEALING","RESTORATION"->{
                    val uid=payload["resource_uid"]?:"HEALTH"
                    resources=resources.map{if(it.resourceUid==uid)it.copy(current=Math.addExact(it.current,magnitude).also{next->
                        require(next in 0..it.maximum){"P50:STAGED_RESOURCE_OUT_OF_RANGE"}
                    })else it}
                }
                "CONDITION","BUFF","DEBUFF","CONTROL","RESTRICTION"->{
                    val uid=payload["condition_uid"]
                    if(uid!=null) {
                        val operation=payload["operation"]?.uppercase()?:if(magnitude<0)"REMOVE" else "ADD"
                        conditions=if(operation in setOf("REMOVE","CLEAR"))conditions.filterNot{it.conditionUid==uid}
                        else if(conditions.none{it.conditionUid==uid})conditions+MechanicalCondition(uid,1) else conditions
                    }
                }
                "EQUIPMENT","EQUIPMENT_DAMAGE"->attributes["ARMOR"]=(attributes["ARMOR"]?:0L).minus(magnitude.coerceAtLeast(0)).coerceAtLeast(0)
                "MORALE","COHESION","FORMATION"->attributes[kind]=Math.addExact(attributes[kind]?:10_000L,magnitude).coerceAtLeast(0)
                "AGGREGATE_ELIMINATION"->population=population?.let{p->val amount=magnitude.coerceIn(0,p.activeCount);p.copy(activeCount=p.activeCount-amount,eliminatedCount=p.eliminatedCount+amount)}
                "AGGREGATE_INJURY"->population=population?.let{p->val amount=magnitude.coerceIn(0,p.activeCount);p.copy(activeCount=p.activeCount-amount,woundedCount=p.woundedCount+amount)}
                "AGGREGATE_CONDITION"->{val uid=payload["condition_uid"];if(uid!=null)population=population?.let{p->p.copy(conditionCounts=p.conditionCounts+(uid to Math.addExact(p.conditionCounts[uid]?:0L,magnitude).coerceAtMost(p.totalCount)))} }
            }
        }
        return base.copy(stateVersion=version,attributes=attributes,resources=resources,conditions=conditions,aggregatePopulation=population)
    }
}
