package com.rpgos.app

import kotlinx.serialization.json.*

/** Persisted source data. A category stays open-ended; the rule grants no ability or inventory. */
data class LatentWorldGenerationRule(
    val uid:String,val version:Int,val baseKind:WorldElementBaseKind,val categoryAliases:Set<String>,
    val topologyClasses:Set<String>,val maximumInstances:Long,val localConnectionDuration:ActionDuration?,
    val requiredAnchorTags:Set<String> = emptySet(),val excludedAnchorTags:Set<String> = emptySet(),
    val localResourceCosts:Map<String,Long> = emptyMap(),val localRequiredCapabilities:Set<String> = emptySet(),
    val localValidityDuration:ActionDuration?=null
) {
    init {
        require(uid.isNotBlank() && version>0 && maximumInstances in 1..100_000)
        require(categoryAliases.isNotEmpty() && categoryAliases.none(String::isBlank))
        require(topologyClasses.isNotEmpty() && topologyClasses.none(String::isBlank))
        require(localConnectionDuration==null || (baseKind==WorldElementBaseKind.PLACE && localConnectionDuration.milliseconds>0))
        require(requiredAnchorTags.none(String::isBlank) && excludedAnchorTags.none(String::isBlank) &&
            requiredAnchorTags.intersect(excludedAnchorTags).isEmpty())
        require(localResourceCosts.size<=32 && localResourceCosts.keys.none(String::isBlank) && localResourceCosts.values.all { it>0 })
        require(localRequiredCapabilities.size<=32 && localRequiredCapabilities.none(String::isBlank))
        require(localValidityDuration==null || localValidityDuration.milliseconds>0)
        require(localConnectionDuration!=null || (localResourceCosts.isEmpty() && localRequiredCapabilities.isEmpty() && localValidityDuration==null))
    }
    fun admits(slot:LatentWorldSlot,topology:String,tags:Set<String>)=slot.baseKind==baseKind &&
        ("*" in categoryAliases || slot.categoryUid.substringBefore('@') in categoryAliases) && topology in topologyClasses &&
        slot.ordinal<maximumInstances && tags.containsAll(requiredAnchorTags) && tags.intersect(excludedAnchorTags).isEmpty()
    val fingerprint:String get()=phase63Hash(Phase63LatentRuleCodec.encode(this).toString())
}

internal object Phase63LatentRuleCodec {
    fun encode(rule:LatentWorldGenerationRule)=buildJsonObject {
        put("uid",rule.uid);put("version",rule.version);put("kind",rule.baseKind.name)
        put("categories",JsonArray(rule.categoryAliases.sorted().map(::JsonPrimitive)))
        put("topologies",JsonArray(rule.topologyClasses.sorted().map(::JsonPrimitive)))
        put("maximum",rule.maximumInstances);put("local_duration",rule.localConnectionDuration?.milliseconds?.let(::JsonPrimitive)?:JsonNull)
        put("required",JsonArray(rule.requiredAnchorTags.sorted().map(::JsonPrimitive)))
        put("excluded",JsonArray(rule.excludedAnchorTags.sorted().map(::JsonPrimitive)))
        if(rule.localResourceCosts.isNotEmpty())put("local_costs",JsonObject(rule.localResourceCosts.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        if(rule.localRequiredCapabilities.isNotEmpty())put("local_capabilities",JsonArray(rule.localRequiredCapabilities.sorted().map(::JsonPrimitive)))
        rule.localValidityDuration?.let { put("local_validity_ms",it.milliseconds) }
    }
    fun decode(value:JsonObject):LatentWorldGenerationRule {
        Phase63WorldCodec.keys(JsonObject(value-"local_costs"-"local_capabilities"-"local_validity_ms"),"uid","version","kind","categories","topologies","maximum","local_duration","required","excluded")
        fun set(key:String)=value.getValue(key).jsonArray.map(Phase63WorldCodec::string).also { require(it.distinct().size==it.size) }.toSet()
        val version=Phase63WorldCodec.number(value,"version");require(version in 1..Int.MAX_VALUE)
        return LatentWorldGenerationRule(Phase63WorldCodec.text(value,"uid"),version.toInt(),enumValueOf(Phase63WorldCodec.text(value,"kind")),
            set("categories"),set("topologies"),Phase63WorldCodec.number(value,"maximum"),
            value.getValue("local_duration").takeUnless { it==JsonNull }?.let { ActionDuration(Phase63WorldCodec.long(it)) },set("required"),set("excluded"),
            value["local_costs"]?.jsonObject?.mapValues { Phase63WorldCodec.long(it.value) }?:emptyMap(),
            if("local_capabilities" in value)set("local_capabilities") else emptySet(),
            value["local_validity_ms"]?.let { ActionDuration(Phase63WorldCodec.long(it)) })
    }
}

internal object CoreLatentWorldRules {
    fun draft(campaign:String,effect:VerifiedMechanicsCommandEffect):WorldElementDraft? {
        if(effect.effectKindUid!="WORLD_ELEMENT_MATERIALIZE" || effect.mechanicsOwnerUid!="RPGOS-CORE:WORLD-MATERIALIZER")return null
        val p=effect.canonicalPayload
        val result=WorldElementDraft(campaign,effect.target,requireNotNull(p["display_name"]),enumValueOf(requireNotNull(p["world_base_kind"])),
            requireNotNull(p["category_uid"]),p["parent_anchor_uid"],p["affordance_uids"].orEmpty().split(',').filter(String::isNotBlank).toSet(),
            requireNotNull(p["topology_class_uid"]),enumValueOf(requireNotNull(p["source_classification"])),p["source_evidence_uids"].orEmpty().split(',').filter(String::isNotBlank),
            p["source_uri"],p["source_revision"],p["source_hash"],p["materialization_level_uid"]?:"PARTIAL",p["slot_ordinal"]?.toLong()?:0,p["slot_category_uid"])
        // Only locally generated drafts have no external evidence list to reconstruct here.
        if(result.sourceClassification!=WorldEvidenceClassification.GENERATED_PLAUSIBLE)return null
        require(result.fingerprint()==p["draft_fingerprint"] && effect.proofUid=="RPGOS-CORE:WORLD-MATERIALIZATION:${result.fingerprint()}")
        return result
    }
    /** Generic descriptive representation is legal without a setting-specific template. Natural
     * geography and distant landmarks are deliberately absent: unknown is not local terrain. */
    fun initial()=WorldElementBaseKind.entries.map { kind->LatentWorldGenerationRule(
        "RPGOS-P63:LOCAL-DESCRIPTIVE:${kind.name}",1,kind,setOf("*"),
        setOf("LOCAL_SITE","INTERIOR","SERVICE_VENUE","SETTLEMENT_FACILITY"),1024,
        if(kind==WorldElementBaseKind.PLACE)ActionDuration(300_000) else null)
    }.sortedBy { it.uid }
    fun select(skeleton:CampaignWorldSkeleton,slot:LatentWorldSlot,topology:String,tags:Set<String> = emptySet())=
        skeleton.latentRules.filter { it.admits(slot,topology,tags) }.sortedBy { it.uid }.firstOrNull()
            ?:LatentWorldGeography.rule(skeleton,slot,topology)
    fun localEdges(skeleton:CampaignWorldSkeleton,draft:WorldElementDraft,at:WorldTimeTick):List<WorldTopologyEdge> {
        if(draft.baseKind!=WorldElementBaseKind.PLACE || draft.parentAnchorUid==null)return emptyList()
        val slot=LatentWorldSlot(draft.parentAnchorUid,draft.slotCategoryUid?:draft.categoryUid,draft.baseKind,draft.slotOrdinal)
        if(slot.ref(skeleton)!=draft.element)return emptyList()
        val rule=select(skeleton,slot,draft.topologyClassUid)?:return emptyList()
        val duration=rule.localConnectionDuration?:return LatentWorldGeography.connections(skeleton,draft,at)
        val parent=DomainRef("LOCATION",draft.parentAnchorUid)
        val place=DomainRef("LOCATION",draft.element.uid)
        return listOf(parent to place,place to parent).map { (from,to)->WorldTopologyEdge(
            "P63-EDGE-${phase63Hash("${skeleton.campaignUid}|${from.uid}|${to.uid}|${rule.fingerprint}").take(32)}",1,from,to,
            duration,rule.localResourceCosts,rule.localRequiredCapabilities,at,rule.localValidityDuration?.let { at+it },"P63:LOCAL-CONNECTION:${rule.fingerprint}") }
    }
}
