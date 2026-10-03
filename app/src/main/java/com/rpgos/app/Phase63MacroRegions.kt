package com.rpgos.app

import kotlinx.serialization.json.*

/** Immutable generation data, not discovered knowledge. A weighted region is drawn from its
 * own stable slot before any reference is resolved, so queries cannot rearrange geography. */
data class WorldMacroRegionRule(val uid:String,val version:Int,val ordinal:Long,
    val terrainWeights:Map<String,Int>,val journeyDuration:ActionDuration,val excludedFeatures:Set<String> = emptySet()) {
    init {
        require(uid.isNotBlank() && version>0 && ordinal in 0..127 && terrainWeights.isNotEmpty() && terrainWeights.size<=32)
        require(terrainWeights.keys.all { it.matches(Regex("[A-Z0-9:_-]{1,96}")) } && terrainWeights.values.all { it in 1..10000 })
        require(terrainWeights.values.sum()<=100_000 && journeyDuration.milliseconds>0 && excludedFeatures.none(String::isBlank))
    }
    val fingerprint:String get()=phase63Hash(Phase63MacroRegionCodec.encode(this).toString())
}

internal data class LatentMacroRegion(val ref:DomainRef,val categoryUid:String,val rule:WorldMacroRegionRule) {
    val tags:Set<String> get()=setOf("MACRO_REGION",categoryUid)
}

internal object Phase63MacroRegionCodec {
    fun encode(value:WorldMacroRegionRule)=buildJsonObject {
        put("uid",value.uid);put("version",value.version);put("ordinal",value.ordinal)
        put("terrain",JsonObject(value.terrainWeights.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("journey_ms",value.journeyDuration.milliseconds);put("excluded",JsonArray(value.excludedFeatures.sorted().map(::JsonPrimitive)))
    }
    fun decode(value:JsonObject):WorldMacroRegionRule {
        Phase63WorldCodec.keys(value,"uid","version","ordinal","terrain","journey_ms","excluded")
        val version=Phase63WorldCodec.number(value,"version");require(version in 1..Int.MAX_VALUE)
        val weights=value.getValue("terrain").jsonObject.mapValues { Phase63WorldCodec.long(it.value).also { n->require(n in 1..10000) }.toInt() }
        val exclusions=value.getValue("excluded").jsonArray.map(Phase63WorldCodec::string).also { require(it.distinct().size==it.size) }.toSet()
        return WorldMacroRegionRule(Phase63WorldCodec.text(value,"uid"),version.toInt(),Phase63WorldCodec.number(value,"ordinal"),weights,
            ActionDuration(Phase63WorldCodec.number(value,"journey_ms")),exclusions)
    }
}

internal object LatentWorldGeography {
    /** Only a registered, explicit bootstrap interpretation opts into terrestrial geography.
     * Unknown/legacy settings keep their established geography and acquire no invented past. */
    val nativeCategories=setOf("MOUNTAIN_SETTLEMENT","COASTAL_SETTLEMENT","FOREST_SETTLEMENT","DESERT_SETTLEMENT","PLAINS_SETTLEMENT")
    fun nativeRules(startingCategory:String?):List<WorldMacroRegionRule> {
        if(startingCategory !in nativeCategories)return emptyList()
        val weights=mapOf("MOUNTAIN" to 3,"FOREST" to 3,"PLAINS" to 3,"DESERT" to 1,"COAST" to 2,"SEA" to 2)
        return (1L..8L).map { ordinal->WorldMacroRegionRule("RPGOS-P63:TERRESTRIAL_REGION:$ordinal",1,ordinal,weights,
            ActionDuration(ordinal*14_400_000L)) }
    }
    fun regions(skeleton:CampaignWorldSkeleton):List<LatentMacroRegion> = skeleton.macroRegionRules.sortedBy { it.ordinal }.map { rule->
        val slot=LatentWorldSlot(skeleton.initialAnchor.uid,"MACRO_REGION",WorldElementBaseKind.PLACE,rule.ordinal)
        val seed=skeleton.domainSeed("GEOGRAPHY:${rule.uid}:${rule.version}",slot.canonicalKey)
        var draw=(seed.take(15).toLong(16)%rule.terrainWeights.values.sum()).toInt()
        val category=rule.terrainWeights.toSortedMap().entries.first { (_,weight)->if(draw<weight)true else {draw-=weight;false} }.key
        LatentMacroRegion(slot.ref(skeleton),category,rule)
    }
    fun select(skeleton:CampaignWorldSkeleton,category:String,ordinal:Int?=null):LatentMacroRegion? {
        val canonical=WorldCategoryVocabulary.canonical(category)
        val matching=regions(skeleton).filter { it.categoryUid==canonical && canonical !in it.rule.excludedFeatures }
            .sortedWith(compareBy<LatentMacroRegion>{it.rule.journeyDuration.milliseconds}.thenBy{it.ref.uid})
        return matching.getOrNull((ordinal?:1)-1)
    }
    fun rule(skeleton:CampaignWorldSkeleton,slot:LatentWorldSlot,topology:String):LatentWorldGenerationRule? {
        if(slot.baseKind!=WorldElementBaseKind.PLACE || slot.ordinal!=0L)return null
        val region=regions(skeleton).singleOrNull { it.ref.uid==slot.regionUid }?:return null
        if(region.categoryUid!=slot.categoryUid || slot.categoryUid in region.rule.excludedFeatures)return null
        val expectedTopology=if(slot.categoryUid=="SEA")"SEA" else "NATURAL_FEATURE"
        if(topology!=expectedTopology)return null
        return LatentWorldGenerationRule("P63:REGION_FEATURE:${region.rule.fingerprint}",1,WorldElementBaseKind.PLACE,
            setOf(slot.categoryUid),setOf(expectedTopology),1,null)
    }
    fun connections(skeleton:CampaignWorldSkeleton,draft:WorldElementDraft,at:WorldTimeTick):List<WorldTopologyEdge> {
        val parent=draft.parentAnchorUid?:return emptyList()
        val slot=LatentWorldSlot(parent,draft.slotCategoryUid?:draft.categoryUid,draft.baseKind,draft.slotOrdinal)
        if(slot.ref(skeleton)!=draft.element || rule(skeleton,slot,draft.topologyClassUid)==null)return emptyList()
        val region=regions(skeleton).single { it.ref.uid==parent }
        val origin=WorldTopologyAnchor.canonical(skeleton.initialAnchor)
        val destination=WorldTopologyAnchor.canonical(draft.element)
        return listOf(origin to destination,destination to origin).map { (from,to)->WorldTopologyEdge(
            "P63-REGIONAL-EDGE-${phase63Hash("${skeleton.campaignUid}|${from.uid}|${to.uid}|${region.rule.fingerprint}").take(32)}",
            1,from,to,region.rule.journeyDuration,emptyMap(),if(draft.categoryUid=="SEA")setOf("SAILING") else emptySet(),at,null,
            "P63:REGIONAL-CONNECTION:${region.rule.fingerprint}") }
    }
    /** A latent remote destination is a candidate, not a known route or free movement. */
    fun candidate(skeleton:CampaignWorldSkeleton,reference:IntentReference,shape:WorldReferenceShape):UniversalWorldReferenceResolution.Latent? {
        if(shape.baseKind!=WorldElementBaseKind.PLACE || shape.kind==WorldReferenceShapeKind.NAMED_INSTANCE ||
            shape.topologyClassUid !in setOf("NATURAL_FEATURE","REGION","OCEAN","SEA","CONTINENT","REMOTE_LANDMARK"))return null
        val category=shape.categoryUid?:return null
        val region=select(skeleton,category,shape.ordinal)?:return null
        val slot=LatentWorldSlot(region.ref.uid,category,WorldElementBaseKind.PLACE)
        val draft=WorldElementDraft(skeleton.campaignUid,slot.ref(skeleton),reference.rawPhrase?:category,WorldElementBaseKind.PLACE,
            category,region.ref.uid,shape.affordanceUids,requireNotNull(shape.topologyClassUid),WorldEvidenceClassification.GENERATED_PLAUSIBLE,
            listOf("P63:REGION_RULE:${region.rule.fingerprint}"),null,null,null,slotOrdinal=0)
        return UniversalWorldReferenceResolution.Latent(draft,WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_AS_JOURNEY,
            "P63:LATENT_REMOTE_ROUTE_KNOWLEDGE_REQUIRED",region.ref.uid,draft.sourceEvidenceUids))
    }
}
