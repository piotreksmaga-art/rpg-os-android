package com.rpgos.app

import org.json.JSONArray
import org.json.JSONObject

enum class WorldDraftPurpose { BOOTSTRAP, ELEMENT }
enum class WorldDraftState { DRAFT, CLARIFICATION, UNKNOWN }

/** Candidate-only input. Neither a world seed nor private canonical state belongs here. */
data class AiWorldDraftRequest(
    val requestUid:String,
    val scope:WorldResolutionScope,
    val purpose:WorldDraftPurpose,
    val playerDescription:String,
    val referencePhrase:String?,
    val era:String,
    val baseKind:WorldElementBaseKind?,
    val categoryUid:String?,
    val topologyClassUid:String?
) {
    init {
        require(requestUid.isNotBlank() && playerDescription.isNotBlank() && playerDescription.length<=2048)
        require(referencePhrase==null || referencePhrase.isNotBlank() && referencePhrase.length<=256)
        require(era.isNotBlank() && era.length<=120)
        require((purpose==WorldDraftPurpose.ELEMENT)==(referencePhrase!=null))
        require(categoryUid==null || categoryUid.matches(Regex("[A-Z0-9:_-]{1,96}")))
    }
    val fingerprint:String get()=phase63Hash(kotlinx.serialization.json.buildJsonArray {
        add(kotlinx.serialization.json.JsonPrimitive(requestUid))
        add(kotlinx.serialization.json.JsonPrimitive(scope.campaignUid));add(kotlinx.serialization.json.JsonPrimitive(scope.historyGenerationUid.value))
        add(kotlinx.serialization.json.JsonPrimitive(scope.asOfCommittedOrder));add(kotlinx.serialization.json.JsonPrimitive(scope.principalUid))
        add(kotlinx.serialization.json.JsonPrimitive(scope.purposeUid))
        add(kotlinx.serialization.json.JsonObject(scope.sourceVersions.toSortedMap().mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
        add(kotlinx.serialization.json.JsonPrimitive(purpose.name));add(kotlinx.serialization.json.JsonPrimitive(playerDescription))
        add(kotlinx.serialization.json.JsonPrimitive(referencePhrase.orEmpty()));add(kotlinx.serialization.json.JsonPrimitive(era))
        add(kotlinx.serialization.json.JsonPrimitive(baseKind?.name.orEmpty()));add(kotlinx.serialization.json.JsonPrimitive(categoryUid.orEmpty()))
        add(kotlinx.serialization.json.JsonPrimitive(topologyClassUid.orEmpty()))
    }.toString())
}

/** No identities, spatial anchors, stats, rewards, costs or past events can be returned. */
data class WorldDraftCandidate(
    val state:WorldDraftState,
    val displayName:String?,
    val baseKind:WorldElementBaseKind?,
    val categoryUid:String?,
    val affordanceUids:Set<String>,
    val topologyClassUid:String?,
    val description:String?,
    val question:String?
) {
    init {
        require(displayName==null || displayName.isNotBlank() && displayName.length<=160)
        require(description==null || description.isNotBlank() && description.length<=1024)
        require(question==null || question.isNotBlank() && question.length<=256)
        require(categoryUid==null || categoryUid.matches(Regex("[A-Z0-9:_-]{1,96}")))
        require(affordanceUids.size<=16 && affordanceUids.all { it.matches(Regex("[A-Z0-9:_-]{1,96}")) })
        require(topologyClassUid==null || topologyClassUid in WorldDraftCodec.topologies)
        require(state!=WorldDraftState.DRAFT || displayName!=null && baseKind!=null && categoryUid!=null && topologyClassUid!=null)
        require(state!=WorldDraftState.CLARIFICATION || question!=null)
    }
}

object WorldDraftCodec {
    val topologies=setOf("SETTLEMENT_FACILITY","SERVICE_VENUE","INTERIOR","LOCAL_SITE","NATURAL_FEATURE","REGION","OCEAN","SEA","CONTINENT","REMOTE_LANDMARK")
    fun encode(request:AiWorldDraftRequest)=JSONObject()
        .put("contract","RPGOS_WORLD_DRAFT_V1").put("request_uid",request.requestUid)
        .put("request_fingerprint",request.fingerprint)
        .put("campaign_uid",request.scope.campaignUid).put("history_generation_uid",request.scope.historyGenerationUid.value)
        .put("as_of_order",request.scope.asOfCommittedOrder).put("purpose",request.purpose.name)
        .put("player_description",request.playerDescription).put("reference_phrase",request.referencePhrase?:JSONObject.NULL)
        .put("era",request.era).put("base_kind",request.baseKind?.name?:JSONObject.NULL)
        .put("category_uid",request.categoryUid?:JSONObject.NULL).put("topology_class_uid",request.topologyClassUid?:JSONObject.NULL)
        .put("requirements",JSONArray(listOf(
            "Return only a candidate, clarification or UNKNOWN. Core decides identity, geography and mechanics.",
            "Never return canonical UIDs, anchors, coordinates, stats, prices, costs, outcomes or invented past events.",
            "Preserve supplied base kind and topology. Lack of evidence is unknown, not nonexistence.",
            "Category and affordances are interpretation hints, not grants of executable abilities.",
            "For BOOTSTRAP classify only the explicitly supplied starting place. Use MOUNTAIN_SETTLEMENT, COASTAL_SETTLEMENT, FOREST_SETTLEMENT, DESERT_SETTLEMENT or PLAINS_SETTLEMENT only when the supplied premise clearly states that terrain. Otherwise return UNKNOWN. A space station, subterranean world, isolated island or a world excluding terrain is not a neutral terrestrial preset. Do not reinterpret the premise to force one of those categories.",
            "Do not override campaign facts or invent a conveniently nearby destination.")))
        .toString()
    fun decode(payload:String,request:AiWorldDraftRequest):WorldDraftCandidate {
        val value=JSONObject(payload)
        val expected=setOf("request_uid","request_fingerprint","state","display_name","base_kind","category_uid","affordance_uids","topology_class_uid","description","question")
        require(value.keys().asSequence().toSet()==expected) { "P63:WORLD_DRAFT_FIELDS" }
        require(value.getString("request_uid")==request.requestUid && value.getString("request_fingerprint")==request.fingerprint) { "P63:WORLD_DRAFT_CORRELATION" }
        fun text(key:String)=if(value.isNull(key))null else value.getString(key)
        val kind=text("base_kind")?.let { enumValueOf<WorldElementBaseKind>(it) }
        val topology=text("topology_class_uid")
        require(request.baseKind==null || kind==null || request.baseKind==kind) { "P63:WORLD_DRAFT_KIND_CHANGE" }
        require(request.topologyClassUid==null || topology==null || request.topologyClassUid==topology) { "P63:WORLD_DRAFT_TOPOLOGY_CHANGE" }
        val items=value.getJSONArray("affordance_uids")
        require(items.length()<=16)
        val affordances=(0 until items.length()).map { items.getString(it) }
        require(affordances.distinct().size==affordances.size)
        return WorldDraftCandidate(enumValueOf(value.getString("state")),text("display_name"),kind,
            text("category_uid"),affordances.toSet(),topology,text("description"),text("question"))
    }
}

/** Preparation precedes staging and uses the same optional provider workload. An unavailable
 * provider leaves an honest neutral/unknown skeleton; it never switches to another model. */
internal class NativeWorldBootstrapInterpretation(private val route:AiModelRoutePort) {
    fun prepare(spec:NativeWorldCreationSpec,scope:WorldResolutionScope,current:()->Boolean):NativeWorldCreationSpec {
        if(spec.startingCategory!=null || !current())return spec
        val input="${spec.description}\nMiejsce startowe: ${spec.startingPlace}"
        if(input.length>2048)return spec
        val request=AiWorldDraftRequest("WORLD-BOOTSTRAP:${phase63Hash("${scope.fingerprint}|$input|${spec.era}")}",scope,
            WorldDraftPurpose.BOOTSTRAP,input,null,spec.era,WorldElementBaseKind.PLACE,null,"LOCAL_SITE")
        val units=(WorldDraftCodec.encode(request).length+3)/4+512
        if(units>2048)return spec
        val provider=(route.route(AiRole.GAME_MASTER,AiWorkload.WORLD_DRAFT,units) as? AiRouteResult.Selected)?.provider?:return spec
        val result=runCatching { provider.draftWorld(request) }.getOrNull() as? AiProviderResult.Success?:return spec
        val candidate=result.value
        if(!current() || candidate.state!=WorldDraftState.DRAFT || candidate.baseKind!=WorldElementBaseKind.PLACE ||
            candidate.topologyClassUid!="LOCAL_SITE" || candidate.categoryUid !in LatentWorldGeography.nativeCategories)return spec
        return spec.copy(startingCategory=candidate.categoryUid)
    }
}

internal fun interface WorldDraftInterpretationPort {
    fun interpret(document:IntentDocument,reference:IntentReference,consumers:List<IntentNode>):IntentReference?
    companion object { val NONE=WorldDraftInterpretationPort { _,_,_->null } }
}

/** Sends the player's own bounded words only. No world dump or hidden source is available to
 * this adapter. A returned hint must pass the normal resolver/rule/topology gates again. */
internal class RoutedWorldDraftInterpretation(
    private val repository:UnifiedGameRepository,
    private val route:AiModelRoutePort
):WorldDraftInterpretationPort {
    override fun interpret(document:IntentDocument,reference:IntentReference,consumers:List<IntentNode>):IntentReference? {
        if(reference.kind in setOf(IntentReferenceKind.DISCOURSE,IntentReferenceKind.DEICTIC))return null
        val phrase=reference.rawPhrase?.takeIf { it.isNotBlank() && it.length<=256 }?:return null
        if(document.rawInput.length>2048)return null
        val snapshot=repository.infrastructureTemporalRead()
        if(snapshot.scope.campaignUid!=document.campaignUid)return null
        val skeleton=repository.infrastructureWorldSkeletonCandidate()?:return null
        val shape=WorldReferenceShapeClassifier.classify(reference,consumers)
        val scope=WorldResolutionScope(document.campaignUid,HistoryGenerationUid(snapshot.scope.historyGenerationUid),
            snapshot.scope.baseCommitOrder,document.actor.actorUid,"WORLD_DRAFT",mapOf(skeleton.ruleSource.uid to skeleton.ruleSource.version))
        val input=AiWorldDraftRequest("WORLD-DRAFT:${phase63Hash("${scope.fingerprint}|${reference.referenceUid}|${document.canonicalFingerprint()}")}",
            scope,WorldDraftPurpose.ELEMENT,document.rawInput,phrase,skeleton.era,shape.baseKind,
            shape.categoryUid?.takeUnless { it.startsWith("GENERIC_") },shape.topologyClassUid)
        val encoded=WorldDraftCodec.encode(input)
        val profile=ContextModelProfile("PHASE45:WORLD_DRAFT_MINIMAL",2048,512)
        if((encoded.length+3)/4>profile.effectiveContextUnits-profile.reservedOutputUnits)return null
        val provider=(route.route(AiRole.GAME_MASTER,AiWorkload.WORLD_DRAFT,(encoded.length+3)/4+profile.reservedOutputUnits) as? AiRouteResult.Selected)?.provider?:return null
        val answer=runCatching { provider.draftWorld(input) }.getOrNull() as? AiProviderResult.Success?:return null
        if(repository.infrastructureTemporalRead().scope!=snapshot.scope)return null
        return worldDraftInterpretationHint(reference,shape,answer.value)
    }
}

/** Clarification stays a presentation hint and must stop resolution, not become a successful
 * generic replacement. The provider's question never becomes a fact or a mechanical effect. */
internal fun worldDraftInterpretationHint(reference:IntentReference,shape:WorldReferenceShape,candidate:WorldDraftCandidate):IntentReference? {
        if(candidate.state==WorldDraftState.CLARIFICATION)return reference.copy(state=IntentReferenceState.UNRESOLVED,
            resolvedProjectedRef=null,candidateProjectedRefs=emptyList(),resolutionEvidenceUid=null,
            descriptorHints=reference.descriptorHints+("world_resolution_reason" to "P63:WORLD_DRAFT_REQUIRES_CLARIFICATION"))
        if(candidate.state!=WorldDraftState.DRAFT)return null
        // No model-created evidence/UID/presentation is promoted to canonical state. Retain the
        // player's phrase and use only the missing interpretation hints.
        val hints=reference.descriptorHints.toMutableMap()
        if(shape.categoryUid==null || shape.categoryUid.startsWith("GENERIC_"))candidate.categoryUid?.let { hints["category"]=WorldCategoryVocabulary.canonical(it) }
        if(shape.topologyClassUid==null)candidate.topologyClassUid?.let { hints["topology"]=it }
        if(shape.affordanceUids.isEmpty() && candidate.affordanceUids.isNotEmpty())hints["affordances"]=candidate.affordanceUids.sorted().joinToString(",")
        return reference.copy(descriptorHints=hints)
}
