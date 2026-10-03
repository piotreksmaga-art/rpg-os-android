package com.rpgos.app

import java.security.MessageDigest
import kotlinx.serialization.json.*

/** More detail is a processing choice, never permission to change established reality. */
enum class WorldSimulationLod { LOD0_AGGREGATE, LOD1_UNIT, LOD2_FEATURED, LOD3_INDIVIDUAL }
enum class CampaignRuleSourceKind { WORLD_PACK, CAMPAIGN_NATIVE }

data class CampaignRuleSource(val kind:CampaignRuleSourceKind,val uid:String,val version:String) {
    init { require(uid.isNotBlank() && version.isNotBlank()) }
}

data class WorldResolutionScope(
    val campaignUid:String,
    val historyGenerationUid:HistoryGenerationUid,
    val asOfCommittedOrder:Long,
    val principalUid:String,
    val purposeUid:String,
    val sourceVersions:Map<String,String>
) {
    init {
        require(campaignUid.isNotBlank() && asOfCommittedOrder>=0 && principalUid.isNotBlank() && purposeUid.isNotBlank())
        require(sourceVersions.keys.none(String::isBlank) && sourceVersions.values.none(String::isBlank))
    }
    val fingerprint:String get()=phase63Hash(buildJsonArray {
        add(campaignUid);add(historyGenerationUid.value);add(asOfCommittedOrder);add(principalUid);add(purposeUid)
        add(JsonObject(sourceVersions.toSortedMap().mapValues { JsonPrimitive(it.value) }))
    }.toString())
}

/** Authoritative when committed; a value returned by bootstrap preparation is only a candidate. */
data class CampaignWorldSkeleton(
    val campaignUid:String,
    val seed:String,
    val generatorVersion:Int,
    val ruleSource:CampaignRuleSource,
    val era:String,
    val initialAnchor:DomainRef,
    val macroAnchorUids:Set<String>,
    val constraints:Map<String,String>,
    val provenanceUid:String,
    val latentRules:List<LatentWorldGenerationRule> = emptyList(),
    val macroRegionRules:List<WorldMacroRegionRule> = emptyList()
) {
    init {
        require(campaignUid.isNotBlank() && seed.matches(Regex("[0-9a-f]{64}")) && generatorVersion==1)
        require(era.isNotBlank() && initialAnchor.kindUid in setOf("PLACE","LOCATION") && provenanceUid.isNotBlank())
        require(macroAnchorUids.size<=128 && macroAnchorUids.none(String::isBlank))
        require(constraints.size<=128 && constraints.keys.none(String::isBlank) && constraints.values.none(String::isBlank))
        require(latentRules.size<=128 && latentRules.map { it.uid }.distinct().size==latentRules.size)
        require(macroRegionRules.size<=128 && macroRegionRules.map { it.uid }.distinct().size==macroRegionRules.size &&
            macroRegionRules.map { it.ordinal }.distinct().size==macroRegionRules.size)
    }
    val fingerprint:String get()=phase63Hash(Phase63WorldCodec.skeleton(this).toString())
    fun domainSeed(domainUid:String,slotKey:String):String {
        require(domainUid.isNotBlank() && slotKey.isNotBlank())
        return phase63Hash(buildJsonArray { add("P63:DOMAIN:1");add(seed);add(domainUid);add(slotKey) }.toString())
    }
    companion object {
        /** Does not use the current actor, host clock, query order, or history generation. */
        fun legacy(campaignUid:String,source:CampaignRuleSource,era:String,anchor:DomainRef)=CampaignWorldSkeleton(
            campaignUid,phase63Hash(buildJsonArray { add("P63:LEGACY-SEED:1");add(campaignUid) }.toString()),
            1,source,era,anchor,setOf(anchor.uid),emptyMap(),"P63:EXISTING-CANONICAL-ANCHOR")
    }
}

data class LatentWorldSlot(val regionUid:String,val categoryUid:String,val baseKind:WorldElementBaseKind,val ordinal:Long=0) {
    init { require(regionUid.isNotBlank() && categoryUid.isNotBlank() && ordinal>=0) }
    val canonicalKey:String get()=buildJsonArray { add(regionUid);add(categoryUid);add(baseKind.name);add(ordinal) }.toString()
    fun ref(skeleton:CampaignWorldSkeleton)=DomainRef(baseKind.name,
        "DYN-${baseKind.name}-${phase63Hash(buildJsonArray {
            add("P63:SLOT:1");add(skeleton.campaignUid);add(skeleton.seed);add(canonicalKey)
        }.toString()).take(24).uppercase()}")
}

data class WorldTopologyEdge(
    val uid:String,val version:Long,val origin:DomainRef,val destination:DomainRef,
    val duration:ActionDuration,val resourceCosts:Map<String,Long>,
    val requiredCapabilities:Set<String>,val validFrom:WorldTimeTick,val validThrough:WorldTimeTick?,
    val provenanceUid:String
) {
    init {
        require(uid.isNotBlank() && version>0 && origin!=destination)
        require(origin.kindUid in setOf("PLACE","LOCATION") && destination.kindUid in setOf("PLACE","LOCATION"))
        require(duration.milliseconds>0 && resourceCosts.size<=32 && resourceCosts.keys.none(String::isBlank))
        require(resourceCosts.values.all { it>0 } && requiredCapabilities.none(String::isBlank))
        require(validThrough==null || validThrough>validFrom)
        require(provenanceUid.isNotBlank())
    }
    val fingerprint:String get()=phase63Hash(Phase63WorldCodec.edge(this).toString())
}

data class WorldTravelPlan(val origin:DomainRef,val destination:DomainRef,val edges:List<WorldTopologyEdge>) {
    init {
        require(edges.isNotEmpty() && edges.size<=1024 && WorldTopologyAnchor.same(edges.first().origin,origin) && WorldTopologyAnchor.same(edges.last().destination,destination))
        require(edges.zipWithNext().all { (left,right)->WorldTopologyAnchor.same(left.destination,right.origin) })
        require(edges.map { it.uid }.distinct().size==edges.size)
    }
    val duration:ActionDuration get()=ActionDuration(edges.fold(0L) { total,edge->Math.addExact(total,edge.duration.milliseconds) })
    val resourceCosts:Map<String,Long> get()=buildMap {
        edges.forEach { edge->edge.resourceCosts.toSortedMap().forEach { (uid,units)->put(uid,Math.addExact(get(uid)?:0L,units)) } }
    }
    val fingerprint:String get()=phase63Hash(JsonArray(edges.map { JsonPrimitive(it.fingerprint) }).toString())
}

sealed interface WorldResolutionResult {
    data class Existing(val element:CampaignWorldElement):WorldResolutionResult
    data class Candidate(val draft:WorldElementDraft,val slot:LatentWorldSlot):WorldResolutionResult
    data class Journey(val element:DomainRef,val plan:WorldTravelPlan):WorldResolutionResult
    data class Clarification(val reasonUid:String):WorldResolutionResult
    data class Contradicted(val reasonUid:String):WorldResolutionResult
    data class Unavailable(val reasonUid:String):WorldResolutionResult
}

fun interface LatentWorldResolverPort {
    fun resolve(scope:WorldResolutionScope,skeleton:CampaignWorldSkeleton,slot:LatentWorldSlot):WorldResolutionResult
}

/** Callers receive only edges legal for their scope, never a global map with hidden routes. */
interface WorldTopologyPort {
    /** Containment is presentation/anchoring, never an executable route. */
    fun containment(scope:WorldResolutionScope,element:DomainRef):List<DomainRef> = emptyList()
    fun legalEdges(scope:WorldResolutionScope,origin:DomainRef,at:WorldTimeTick):List<WorldTopologyEdge>
    fun travel(scope:WorldResolutionScope,origin:DomainRef,destination:DomainRef,at:WorldTimeTick):WorldResolutionResult
}

fun interface WorldMaterializationPort {
    fun prepare(scope:WorldResolutionScope,drafts:List<WorldElementDraft>):List<PlayerDomainChangePayload>
}

/** Unknown descriptive components cannot acquire execution authority by naming an owner. */
class WorldComponentOwnerRegistry(private val owners:Map<String,WorldMaterializationPort>) {
    init { require(owners.keys.none(String::isBlank)) }
    fun requireOwner(componentKindUid:String):WorldMaterializationPort=owners[componentKindUid]
        ?:error("P63:COMPONENT_OWNER_UNREGISTERED:$componentKindUid")
}

data class WorldLodInterest(val ref:DomainRef,val activePlayer:Boolean=false,val directInteraction:Boolean=false,
    val featured:Boolean=false,val formation:Boolean=false)

object WorldLodPolicy {
    fun level(interest:WorldLodInterest)=when {
        interest.activePlayer || interest.directInteraction->WorldSimulationLod.LOD3_INDIVIDUAL
        interest.featured->WorldSimulationLod.LOD2_FEATURED
        interest.formation->WorldSimulationLod.LOD1_UNIT
        else->WorldSimulationLod.LOD0_AGGREGATE
    }
}

internal object Phase63WorldCodec {
    fun ref(value:DomainRef)=buildJsonObject { put("kind",value.kindUid);put("uid",value.uid) }
    fun readRef(value:JsonElement):DomainRef {
        val o=value.jsonObject;keys(o,"kind","uid");return DomainRef(text(o,"kind"),text(o,"uid"))
    }
    fun skeleton(value:CampaignWorldSkeleton)=buildJsonObject {
        put("campaign",value.campaignUid);put("seed",value.seed);put("generator",value.generatorVersion)
        put("source_kind",value.ruleSource.kind.name);put("source_uid",value.ruleSource.uid);put("source_version",value.ruleSource.version)
        put("era",value.era);put("initial_anchor",ref(value.initialAnchor))
        put("macro_anchors",JsonArray(value.macroAnchorUids.sorted().map(::JsonPrimitive)))
        put("constraints",JsonObject(value.constraints.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("provenance",value.provenanceUid)
        if(value.latentRules.isNotEmpty())put("latent_rules",JsonArray(value.latentRules.map(Phase63LatentRuleCodec::encode)))
        if(value.macroRegionRules.isNotEmpty())put("macro_region_rules",JsonArray(value.macroRegionRules.map(Phase63MacroRegionCodec::encode)))
    }
    fun readSkeleton(value:JsonObject):CampaignWorldSkeleton {
        keys(JsonObject(value-"latent_rules"-"macro_region_rules"),"campaign","seed","generator","source_kind","source_uid","source_version","era","initial_anchor","macro_anchors","constraints","provenance")
        val anchors=value.getValue("macro_anchors").jsonArray.map { string(it) }
        require(anchors.distinct().size==anchors.size)
        val generator=number(value,"generator").also { require(it==1L) }.toInt()
        return CampaignWorldSkeleton(text(value,"campaign"),text(value,"seed"),generator,
            CampaignRuleSource(enumValueOf(text(value,"source_kind")),text(value,"source_uid"),text(value,"source_version")),
            text(value,"era"),readRef(value.getValue("initial_anchor")),anchors.toSet(),
            value.getValue("constraints").jsonObject.mapValues { string(it.value) },text(value,"provenance"),
            value["latent_rules"]?.jsonArray?.also { require(it.size<=128) }?.map { Phase63LatentRuleCodec.decode(it.jsonObject) }?:emptyList(),
            value["macro_region_rules"]?.jsonArray?.also { require(it.size<=128) }?.map { Phase63MacroRegionCodec.decode(it.jsonObject) }?:emptyList())
    }
    fun edge(value:WorldTopologyEdge)=buildJsonObject {
        put("uid",value.uid);put("version",value.version);put("origin",ref(value.origin));put("destination",ref(value.destination))
        put("duration",value.duration.milliseconds)
        put("costs",JsonObject(value.resourceCosts.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        put("capabilities",JsonArray(value.requiredCapabilities.sorted().map(::JsonPrimitive)))
        put("from",value.validFrom.milliseconds);put("through",value.validThrough?.let { JsonPrimitive(it.milliseconds) }?:JsonNull)
        put("provenance",value.provenanceUid)
    }
    fun readEdge(value:JsonObject):WorldTopologyEdge {
        keys(value,"uid","version","origin","destination","duration","costs","capabilities","from","through","provenance")
        val caps=value.getValue("capabilities").jsonArray.map { string(it) };require(caps.distinct().size==caps.size)
        return WorldTopologyEdge(text(value,"uid"),number(value,"version"),readRef(value.getValue("origin")),readRef(value.getValue("destination")),
            ActionDuration(number(value,"duration")),value.getValue("costs").jsonObject.mapValues { long(it.value) },caps.toSet(),
            WorldTimeTick(number(value,"from")),value.getValue("through").takeUnless { it==JsonNull }?.let { WorldTimeTick(long(it)) },text(value,"provenance"))
    }
    fun keys(value:JsonObject,vararg expected:String) { require(value.keys==expected.toSet()) { "P63:UNEXPECTED_FIELDS" } }
    fun string(value:JsonElement):String=value.jsonPrimitive.let { require(it.isString);it.content }
    fun text(value:JsonObject,key:String)=string(value.getValue(key))
    fun long(value:JsonElement):Long=value.jsonPrimitive.let { require(!it.isString && it.content.matches(Regex("-?[0-9]+")));it.long }
    fun number(value:JsonObject,key:String)=long(value.getValue(key))
}

internal fun phase63Hash(value:String):String {
    val bytes=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    val hex="0123456789abcdef"
    return buildString(64) { bytes.forEach { byte->val n=byte.toInt() and 255;append(hex[n ushr 4]);append(hex[n and 15]) } }
}
