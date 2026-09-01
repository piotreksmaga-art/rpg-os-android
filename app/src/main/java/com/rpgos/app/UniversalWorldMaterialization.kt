package com.rpgos.app

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

enum class WorldElementBaseKind { PLACE, ACTOR, OBJECT, GROUP, ORGANIZATION, EVENT, PROCESS, CONCEPT }
enum class WorldReferenceShapeKind { NAMED_INSTANCE, CATEGORY, QUANTITY, ROLE, AFFORDANCE, UNKNOWN }
enum class WorldEvidenceClassification {
    CAMPAIGN_FACT, SOURCE_CANON, GENERATED_PLAUSIBLE, BELIEF_OR_RUMOR, CONFLICTING_EVIDENCE, UNKNOWN
}
enum class WorldFeasibilityState { FEASIBLE_NEARBY, FEASIBLE_AS_JOURNEY, CONTRADICTED, UNKNOWN }

/** Open action vocabulary: known families keep their specialised owners; every other verb is
 * preserved as its canonical action and routed through OPEN_WORLD_ACTION. */
internal object UniversalIntentFamilies{
    const val PROVIDER_ACTION_ATTRIBUTE="provider_action"
    private val openActionToken=Regex("[A-Z][A-Z0-9_:-]{0,95}")
    val COMBAT=setOf("ATTACK","COMBAT","STRIKE","FIGHT","DEFEND","AREA_ATTACK","BLAST","EXPLOSION","AOE","CONE_ATTACK","LINE_ATTACK","ZONE_ATTACK","SWEEP_ATTACK")
    val MOVEMENT=setOf("MOVE","TRAVEL","ESCAPE","REACH","PUSH")
    val ACTION=setOf(
        "USE","CRAFT","FISH","BUY","SELL","HEAL","REST","CAPTURE","PROTECT","HOLD","DISABLE","DESTROY","TRAIN","PRACTICE","LEARN","WORK",
        "SEARCH","EXPLORE","ENTER","LEAVE","TAKE","DROP","GIVE","FOLLOW","HIDE","SLEEP","EAT","DRINK","BUILD","CREATE","REPAIR","STEAL",
        "PERSUADE","INTIMIDATE","HELP","RESCUE","CLIMB","SWIM","SAIL","FLY","READ","WRITE","COOK","LOOK","WAIT","QUERY","TALK","OPEN_WORLD_ACTION"
    )
    val REGISTERED=COMBAT+MOVEMENT+ACTION
    private val providerActionAliases=mapOf(
        "ODLOZYC" to "DROP","ODKLADAC" to "DROP","ODKLADAM" to "DROP","ODKLADA" to "DROP","ODLOZE" to "DROP",
        "UPUSCIC" to "DROP","UPUSZCZAM" to "DROP","PORZUCIC" to "DROP","PORZUCAM" to "DROP",
        "BIOR" to "TAKE","BIORE" to "TAKE","BRAC" to "TAKE","WEZ" to "TAKE","WZIAC" to "TAKE",
        "PODNIESC" to "TAKE","PODNOSZE" to "TAKE","ZABRAC" to "TAKE","ZABIERAM" to "TAKE"
    )
    fun routedFamily(providerAction:String,routeHint:String?=null)=when(routeHint){
        "MOVEMENT"->providerAction.takeIf{it in MOVEMENT}?:"TRAVEL"
        "COMBAT"->providerAction.takeIf{it in COMBAT}?:"COMBAT"
        "TRAINING"->providerAction.takeIf{it in setOf("TRAIN","PRACTICE","LEARN")}?:"TRAIN"
        "QUERY"->"QUERY"
        "COMMUNICATION"->providerAction.takeIf{it in setOf("TALK","PERSUADE","INTIMIDATE")}?:"TALK"
        "ACTION"->providerAction.takeIf{it in ACTION}?:"OPEN_WORLD_ACTION"
        else->providerAction.takeIf{it in REGISTERED}?:"OPEN_WORLD_ACTION"
    }
    fun trustProviderAction(action:SemanticAction):SemanticAction{
        if(action.canonicalActionUid!=null)return action
        val providerAction=action.attributes[PROVIDER_ACTION_ATTRIBUTE]?.takeIf{it.matches(openActionToken)}?:return action
        val canonical=providerActionAliases[providerAction]?:providerAction
        return action.copy(
            canonicalActionUid=canonical,
            semanticFamilyUid=canonical.takeIf{it in REGISTERED}?:action.semanticFamilyUid
        )
    }
}

data class WorldReferenceShape(
    val kind:WorldReferenceShapeKind,
    val baseKind:WorldElementBaseKind,
    val categoryUid:String?,
    val affordanceUids:Set<String>,
    val topologyClassUid:String?,
    val quantity:Int?=null,
    val ordinal:Int?=null
){
    init{
        require(categoryUid?.isBlank()!=true&&topologyClassUid?.isBlank()!=true)
        require(affordanceUids.none{it.isBlank()}&&quantity?.let{it>0}!=false&&ordinal?.let{it>=0}!=false)
    }
}

data class WorldEvidenceRequest(
    val campaignUid:String,
    val phrase:String,
    val shape:WorldReferenceShape,
    val worldContextHint:String?,
    val maximumCandidates:Int=5
){init{require(campaignUid.isNotBlank()&&phrase.isNotBlank()&&maximumCandidates in 1..20)}}

data class WorldEvidenceCandidate(
    val evidenceUid:String,
    val displayName:String,
    val classification:WorldEvidenceClassification,
    val confidence:Double,
    val sourceUri:String?,
    val sourceRevision:String?,
    val sourceHash:String?,
    val baseKind:WorldElementBaseKind,
    val categoryUid:String?,
    val parentAnchorUid:String?,
    val affordanceUids:Set<String> = emptySet(),
    val topologyClassUid:String?=null
){
    init{
        require(evidenceUid.isNotBlank()&&displayName.isNotBlank()&&confidence in 0.0..1.0)
        require(sourceUri?.isBlank()!=true&&sourceRevision?.isBlank()!=true&&sourceHash?.isBlank()!=true)
        require(categoryUid?.isBlank()!=true&&parentAnchorUid?.isBlank()!=true&&topologyClassUid?.isBlank()!=true)
        require(affordanceUids.none{it.isBlank()})
    }
}

fun interface WorldEvidenceProviderPort{
    fun candidates(request:WorldEvidenceRequest):List<WorldEvidenceCandidate>
    companion object{val NONE=WorldEvidenceProviderPort{emptyList()}}
}

/** Candidate-only semantic lookup over already authorised World Pack definitions.  The consumer
 * must still validate every returned UID against its canonical typed store before resolving an
 * intent reference; Bekko never creates an identifier or settles ambiguity. */
fun interface SemanticWorldPackReferenceCandidatePort{
    fun candidates(campaignUid:String,reference:IntentReference,consumerNodes:List<IntentNode>):List<DomainRef>
    companion object{val NONE=SemanticWorldPackReferenceCandidatePort{_,_,_->emptyList()}}
}

class CompositeWorldEvidenceProvider(private val providers:List<WorldEvidenceProviderPort>):WorldEvidenceProviderPort{
    override fun candidates(request:WorldEvidenceRequest)=providers.flatMap{provider->
        runCatching{provider.candidates(request)}.getOrDefault(emptyList())
    }.filter{it.baseKind==request.shape.baseKind}
        .distinctBy{it.evidenceUid}
        .sortedWith(compareByDescending<WorldEvidenceCandidate>{it.confidence}.thenBy{it.evidenceUid})
        .take(request.maximumCandidates)
}

data class WorldFeasibilityDecision(
    val state:WorldFeasibilityState,
    val reasonUid:String,
    val parentAnchorUid:String?,
    val evidenceUids:List<String> = emptyList()
){init{require(reasonUid.isNotBlank()&&parentAnchorUid?.isBlank()!=true&&evidenceUids.none{it.isBlank()})}}

data class WorldElementDraft(
    val campaignUid:String,
    val element:DomainRef,
    val displayName:String,
    val baseKind:WorldElementBaseKind,
    val categoryUid:String,
    val parentAnchorUid:String?,
    val affordanceUids:Set<String>,
    val topologyClassUid:String,
    val sourceClassification:WorldEvidenceClassification,
    val sourceEvidenceUids:List<String>,
    val sourceUri:String?,
    val sourceRevision:String?,
    val sourceHash:String?,
    val materializationLevelUid:String="PARTIAL"
){
    init{
        require(campaignUid.isNotBlank()&&displayName.isNotBlank()&&categoryUid.isNotBlank()&&topologyClassUid.isNotBlank())
        require(element.kindUid==baseKind.name&&affordanceUids.none{it.isBlank()}&&sourceEvidenceUids.none{it.isBlank()})
        require(sourceUri?.isBlank()!=true&&sourceRevision?.isBlank()!=true&&sourceHash?.isBlank()!=true)
        require(materializationLevelUid in setOf("SEED_ONLY","PARTIAL","FULL"))
    }

    fun fingerprint():String=worldSha256(listOf(
        campaignUid,element.kindUid,element.uid,displayName,baseKind.name,categoryUid,parentAnchorUid.orEmpty(),
        affordanceUids.sorted().joinToString(","),topologyClassUid,sourceClassification.name,
        sourceEvidenceUids.sorted().joinToString(","),sourceUri.orEmpty(),sourceRevision.orEmpty(),sourceHash.orEmpty(),materializationLevelUid
    ).joinToString("|"))
}

data class CampaignWorldElement(
    val element:DomainRef,
    val displayName:String,
    val categoryUid:String,
    val parentAnchorUid:String?,
    val affordanceUids:Set<String>,
    val topologyClassUid:String,
    val sourceClassification:WorldEvidenceClassification,
    val audienceScopeUid:String=CampaignWorldAudience.PLAYER_VISIBLE,
    val sourceVersion:Long=0
)

object CampaignWorldAudience{const val PLAYER_VISIBLE="PLAYER_VISIBLE"}

object CampaignWorldFacts{
    const val KIND="RPGOS-WORLD:ELEMENT_KIND"
    const val NAME="RPGOS-WORLD:DISPLAY_NAME"
    const val CATEGORY="RPGOS-WORLD:CATEGORY"
    const val PARENT="RPGOS-WORLD:PARENT"
    const val AFFORDANCE="RPGOS-WORLD:AFFORDANCE"
    const val TOPOLOGY="RPGOS-WORLD:TOPOLOGY_CLASS"
    const val SOURCE_CLASSIFICATION="RPGOS-WORLD:SOURCE_CLASSIFICATION"
    const val SOURCE_URI="RPGOS-WORLD:SOURCE_URI"
    const val SOURCE_REVISION="RPGOS-WORLD:SOURCE_REVISION"
    const val SOURCE_HASH="RPGOS-WORLD:SOURCE_HASH"
    const val MATERIALIZATION_LEVEL="RPGOS-WORLD:MATERIALIZATION_LEVEL"
    const val AUDIENCE_SCOPE="RPGOS-WORLD:AUDIENCE_SCOPE"
    val ALL=setOf(KIND,NAME,CATEGORY,PARENT,AFFORDANCE,TOPOLOGY,SOURCE_CLASSIFICATION,SOURCE_URI,SOURCE_REVISION,SOURCE_HASH,MATERIALIZATION_LEVEL,AUDIENCE_SCOPE)

    fun project(records:List<CampaignTruthRecord>):List<CampaignWorldElement> = records.asSequence()
        .filter{it.active&&it.kind==TruthKind.FACT&&it.subjectUid!=null&&it.predicate in ALL}
        .groupBy{requireNotNull(it.subjectUid)}
        .mapNotNull{(uid,facts)->
            fun one(predicate:String)=facts.filter{it.predicate==predicate}.maxByOrNull{it.createdAt}?.objectValue
            val kind=runCatching{WorldElementBaseKind.valueOf(one(KIND)?:return@mapNotNull null)}.getOrNull()?:return@mapNotNull null
            val name=one(NAME)?.takeIf{it.isNotBlank()}?:return@mapNotNull null
            val category=one(CATEGORY)?.takeIf{it.isNotBlank()}?:return@mapNotNull null
            val topology=one(TOPOLOGY)?.takeIf{it.isNotBlank()}?:"OPEN"
            val source=runCatching{WorldEvidenceClassification.valueOf(one(SOURCE_CLASSIFICATION)?:"CAMPAIGN_FACT")}
                .getOrDefault(WorldEvidenceClassification.CAMPAIGN_FACT)
            CampaignWorldElement(DomainRef(kind.name,uid),name,category,one(PARENT),facts.filter{it.predicate==AFFORDANCE}.mapNotNull{it.objectValue}.toSet(),topology,source)
        }.sortedWith(compareBy<CampaignWorldElement>{it.element.kindUid}.thenBy{it.element.uid}).toList()
}

sealed interface UniversalWorldReferenceResolution{
    data class Existing(val element:CampaignWorldElement,val evidenceUid:String):UniversalWorldReferenceResolution
    data class Latent(val draft:WorldElementDraft,val feasibility:WorldFeasibilityDecision):UniversalWorldReferenceResolution
    data class Rejected(val reasonUid:String):UniversalWorldReferenceResolution
    data class Unresolved(val reasonUid:String):UniversalWorldReferenceResolution
}

/** Pure, world-agnostic classification. AI hints remain untrusted until this output passes the feasibility gate. */
object WorldReferenceShapeClassifier{
    private val token=Regex("[A-Z0-9:_-]{1,96}")

    private fun ordinal(value:String?):Int?{
        val raw=value?.trim()?.takeIf{it.isNotBlank()}?:return null
        raw.toIntOrNull()?.takeIf{it>0}?.let{return it}
        return when(normalizedWorldToken(raw)){
            "FIRST","PIERWSZY","PIERWSZA","PIERWSZE"->1
            "SECOND","DRUGI","DRUGA","DRUGIE"->2
            "THIRD","TRZECI","TRZECIA","TRZECIE"->3
            "FOURTH","CZWARTY","CZWARTA","CZWARTE"->4
            "FIFTH","PIATY","PIATA","PIATE"->5
            "SIXTH","SZOSTY","SZOSTA","SZOSTE"->6
            "SEVENTH","SIODMY","SIODMA","SIODME"->7
            "EIGHTH","OSMY","OSMA","OSME"->8
            "NINTH","DZIEWIATY","DZIEWIATA","DZIEWIATE"->9
            "TENTH","DZIESIATY","DZIESIATA","DZIESIATE"->10
            else->null
        }
    }

    private val actorTypes=setOf("ACTOR","NPC","PERSON","CHARACTER","CREATURE","TEACHER","GUIDE","MERCHANT","ENEMY","ALLY")
    private val objectTypes=setOf("OBJECT","ITEM","TOOL","WEAPON","DEVICE","FOOD","DOCUMENT","RESOURCE")
    private val groupTypes=setOf("GROUP","CROWD","UNIT","TEAM","SQUAD","POPULATION")
    private val organizationTypes=setOf("ORGANIZATION","ORGANISATION","CLAN","FACTION","GUILD","INSTITUTION")
    private val eventTypes=setOf("EVENT","INCIDENT","CEREMONY","MEETING","BATTLE")
    private val processTypes=setOf("PROCESS","ACTIVITY","TASK","LESSON","TRAINING","WORK")
    private val conceptTypes=setOf("CONCEPT","INFORMATION","QUESTION","ANSWER","IDEA","TOPIC")
    private val placeTypes=setOf(
        "PLACE","LOCATION","DESTINATION","AREA","SITE","VENUE","BUILDING","ROOM","SETTLEMENT","VILLAGE","CITY","REGION",
        "ACTIVITY_LOCATION","ROUTE","PATH","ROAD","SEA","OCEAN","RIVER","MOUNTAIN","FOREST"
    )
    private val naturalTopologies=mapOf(
        "SEA" to "SEA","OCEAN" to "OCEAN","CONTINENT" to "CONTINENT","REGION" to "REGION",
        "RIVER" to "NATURAL_FEATURE","MOUNTAIN" to "NATURAL_FEATURE","FOREST" to "NATURAL_FEATURE","LAKE" to "NATURAL_FEATURE"
    )

    fun classify(reference:IntentReference,consumerNodes:List<IntentNode>):WorldReferenceShape{
        fun hint(key:String)=reference.descriptorHints[key]?.trim()?.takeIf{it.isNotBlank()}?.let(::normalizedWorldToken)?.takeIf{it.matches(token)}
        val semanticHints=reference.semanticTypeHints.map(::normalizedWorldToken).filter{it.matches(token)}.toSortedSet()
        val actions=consumerNodes.mapNotNull{it.semanticAction.canonicalActionUid?:it.semanticAction.attributes[UniversalIntentFamilies.PROVIDER_ACTION_ATTRIBUTE]
            ?:it.semanticAction.semanticFamilyUid}.map(String::uppercase).filter{it.matches(token)}.toSortedSet()
        val explicitBase=hint("world_base_kind")?.let{runCatching{WorldElementBaseKind.valueOf(it)}.getOrNull()}
            ?:hint("kind")?.let{runCatching{WorldElementBaseKind.valueOf(it)}.getOrNull()}
        val base=explicitBase?:when{
            semanticHints.any{it in actorTypes}->WorldElementBaseKind.ACTOR
            semanticHints.any{it in objectTypes}->WorldElementBaseKind.OBJECT
            semanticHints.any{it in groupTypes}->WorldElementBaseKind.GROUP
            semanticHints.any{it in organizationTypes}->WorldElementBaseKind.ORGANIZATION
            semanticHints.any{it in eventTypes}->WorldElementBaseKind.EVENT
            semanticHints.any{it in processTypes}->WorldElementBaseKind.PROCESS
            semanticHints.any{it in conceptTypes}->WorldElementBaseKind.CONCEPT
            semanticHints.any{it in placeTypes}->WorldElementBaseKind.PLACE
            else->WorldElementBaseKind.PLACE
        }
        val scope=hint("spatial_scope")
        val phrase=reference.rawPhrase.orEmpty().trim()
        val rawKind=hint("kind")?.takeUnless{runCatching{WorldElementBaseKind.valueOf(it)}.isSuccess}
        val category=hint("category")?:semanticHints.firstOrNull()?:rawKind?:"GENERIC_${base.name}"
        val affordances=(reference.descriptorHints["affordances"].orEmpty().split(',').map(::normalizedWorldToken).filter{it.matches(token)}+actions).toSortedSet()
        val topology=hint("topology")?:naturalTopologies[category]?:semanticHints.firstNotNullOfOrNull{naturalTopologies[it]}?:when{
            scope=="REMOTE"->"REMOTE_LANDMARK"
            category in setOf("SETTLEMENT","VILLAGE","CITY")->"REGION"
            category in setOf("ACTIVITY_LOCATION","SCHOOL","ACADEMY","WORKPLACE","SERVICE_VENUE")->"SETTLEMENT_FACILITY"
            base==WorldElementBaseKind.PLACE->"LOCAL_SITE"
            else->"LOCAL_SITE"
        }
        val requestedShape=runCatching{WorldReferenceShapeKind.valueOf(hint("shape")?:"")}.getOrNull()
        val requestedOrdinal=ordinal(reference.descriptorHints["ordinal"])
        // Providers tend to mark every capitalised noun as a named instance. In languages where
        // ordinary nouns are capitalised by sentence position (and in UI labels), that turns a
        // generic local school/shop/workshop into an evidence-only proper name. A single bare
        // local facility has no proper-name evidence and is therefore a category candidate. Exact
        // World Pack/campaign name matching has already run before materialisation, while quoted,
        // numbered, multi-word, remote and natural names remain strict named instances.
        val explicitShape=requestedShape.takeUnless{
            it==WorldReferenceShapeKind.NAMED_INSTANCE&&(
                (base==WorldElementBaseKind.PLACE&&
                    topology in setOf("SETTLEMENT_FACILITY","SERVICE_VENUE","INTERIOR","LOCAL_SITE")&&
                    phrase.split(Regex("\\s+")).filter(String::isNotBlank).size==1&&
                    !phrase.startsWith('"')&&!phrase.any(Char::isDigit))||
                // "Drugi treningowy kunai" identifies an ordinal member of a known category,
                // not a proper-named artefact. Keep genuinely named instances evidence-gated.
                (requestedOrdinal!=null&&base!=WorldElementBaseKind.PLACE&&category.isNotBlank())
            )
        }?:requestedShape?.let{WorldReferenceShapeKind.CATEGORY}
        val shape=explicitShape?:when{
            reference.kind==IntentReferenceKind.SET->WorldReferenceShapeKind.CATEGORY
            scope in setOf("LOCAL","REMOTE")->WorldReferenceShapeKind.CATEGORY
            base==WorldElementBaseKind.ACTOR&&phrase.split(Regex("\\s+")).size<=3&&phrase.firstOrNull()?.isUpperCase()==true->WorldReferenceShapeKind.NAMED_INSTANCE
            category.isNotBlank()||affordances.isNotEmpty()->WorldReferenceShapeKind.CATEGORY
            phrase.startsWith('"')||phrase.any(Char::isDigit)->WorldReferenceShapeKind.NAMED_INSTANCE
            else->WorldReferenceShapeKind.UNKNOWN
        }
        return WorldReferenceShape(shape,base,category,affordances,topology,
            reference.descriptorHints["quantity"]?.toIntOrNull(),requestedOrdinal)
    }
}

object WorldFeasibilityAndTopologyGate{
    private val localTopology=setOf("SETTLEMENT_FACILITY","SERVICE_VENUE","INTERIOR","LOCAL_SITE")
    private val nonLocalTopology=setOf("NATURAL_FEATURE","REGION","OCEAN","SEA","CONTINENT","REMOTE_LANDMARK")

    fun evaluate(shape:WorldReferenceShape,currentAnchorUid:String?,evidence:List<WorldEvidenceCandidate>):WorldFeasibilityDecision{
        val exact=evidence.firstOrNull{it.confidence>=0.85&&it.classification in setOf(WorldEvidenceClassification.CAMPAIGN_FACT,WorldEvidenceClassification.SOURCE_CANON)}
        val parent=exact?.parentAnchorUid?:currentAnchorUid
        if(shape.kind==WorldReferenceShapeKind.NAMED_INSTANCE){
            return if(exact?.parentAnchorUid!=null)WorldFeasibilityDecision(
                if(exact.parentAnchorUid==currentAnchorUid)WorldFeasibilityState.FEASIBLE_NEARBY else WorldFeasibilityState.FEASIBLE_AS_JOURNEY,
                "VERIFIED_NAMED_INSTANCE",exact.parentAnchorUid,listOf(exact.evidenceUid)
            ) else WorldFeasibilityDecision(WorldFeasibilityState.UNKNOWN,"NAMED_INSTANCE_EVIDENCE_REQUIRED",null,evidence.map{it.evidenceUid})
        }
        if(shape.topologyClassUid in nonLocalTopology){
            return if(exact?.parentAnchorUid!=null)WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_AS_JOURNEY,"EXTERNAL_TOPOLOGY_EVIDENCE",exact.parentAnchorUid,listOf(exact.evidenceUid))
            else WorldFeasibilityDecision(WorldFeasibilityState.UNKNOWN,"NATURAL_OR_REMOTE_TOPOLOGY_UNRESOLVED",null,evidence.map{it.evidenceUid})
        }
        if(parent==null)return WorldFeasibilityDecision(WorldFeasibilityState.UNKNOWN,"CURRENT_SPATIAL_ANCHOR_REQUIRED",null,evidence.map{it.evidenceUid})
        if(shape.topologyClassUid in localTopology&&shape.categoryUid!=null&&shape.affordanceUids.isNotEmpty()){
            return WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_NEARBY,"LOCAL_CATEGORY_AFFORDANCE_SUPPORTED",parent,evidence.map{it.evidenceUid})
        }
        if(shape.baseKind!=WorldElementBaseKind.PLACE&&shape.categoryUid!=null&&shape.affordanceUids.isNotEmpty()){
            return WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_NEARBY,"ANCHORED_NON_SPATIAL_CATEGORY_SUPPORTED",parent,evidence.map{it.evidenceUid})
        }
        if(exact!=null&&exact.parentAnchorUid!=null)return WorldFeasibilityDecision(WorldFeasibilityState.FEASIBLE_AS_JOURNEY,"VERIFIED_EXTERNAL_ELEMENT",exact.parentAnchorUid,listOf(exact.evidenceUid))
        return WorldFeasibilityDecision(WorldFeasibilityState.UNKNOWN,"INSUFFICIENT_REQUIRED_FACTS",parent,evidence.map{it.evidenceUid})
    }
}

class UniversalWorldMaterializationResolver(
    private val evidenceProvider:WorldEvidenceProviderPort=WorldEvidenceProviderPort.NONE
){
    fun resolve(
        campaignUid:String,
        reference:IntentReference,
        consumerNodes:List<IntentNode>,
        currentAnchorUid:String?,
        existing:List<CampaignWorldElement>,
        worldContextHint:String?
    ):UniversalWorldReferenceResolution{
        val phrase=(reference.rawPhrase?:reference.descriptorHints["surface"]).orEmpty().trim()
        if(phrase.isBlank())return UniversalWorldReferenceResolution.Unresolved("EMPTY_WORLD_REFERENCE")
        val shape=WorldReferenceShapeClassifier.classify(reference,consumerNodes)
        val exact=existing.filter{it.element.kindUid==shape.baseKind.name||(shape.baseKind==WorldElementBaseKind.PLACE&&it.element.kindUid=="LOCATION")}.filter{element->
            worldNamesEquivalent(element.displayName,phrase)||
                (shape.kind==WorldReferenceShapeKind.CATEGORY&&shape.categoryUid!=null&&!shape.categoryUid.startsWith("GENERIC_")&&element.categoryUid==shape.categoryUid&&
                    element.affordanceUids.containsAll(shape.affordanceUids))
        }.sortedWith(compareByDescending<CampaignWorldElement>{it.parentAnchorUid==currentAnchorUid}.thenBy{it.element.uid})
        if(shape.kind==WorldReferenceShapeKind.NAMED_INSTANCE&&exact.size>1)return UniversalWorldReferenceResolution.Rejected("REFERENCE_AMBIGUOUS")
        val selectedExisting=if(shape.ordinal!=null)exact.getOrNull(shape.ordinal-1) else exact.firstOrNull()
        if(selectedExisting!=null)return UniversalWorldReferenceResolution.Existing(selectedExisting,"CAMPAIGN-WORLD-MODEL:${selectedExisting.element.uid}")
        val evidence=if(shape.topologyClassUid in setOf("SETTLEMENT_FACILITY","SERVICE_VENUE","INTERIOR","LOCAL_SITE")&&shape.kind==WorldReferenceShapeKind.CATEGORY)emptyList()
            else runCatching{evidenceProvider.candidates(WorldEvidenceRequest(campaignUid,phrase,shape,worldContextHint))}.getOrDefault(emptyList())
        val feasibility=WorldFeasibilityAndTopologyGate.evaluate(shape,currentAnchorUid,evidence)
        if(feasibility.state==WorldFeasibilityState.CONTRADICTED)return UniversalWorldReferenceResolution.Rejected(feasibility.reasonUid)
        if(feasibility.state !in setOf(WorldFeasibilityState.FEASIBLE_NEARBY,WorldFeasibilityState.FEASIBLE_AS_JOURNEY))
            return UniversalWorldReferenceResolution.Unresolved(feasibility.reasonUid)
        val category=shape.categoryUid?:evidence.firstOrNull()?.categoryUid?:return UniversalWorldReferenceResolution.Unresolved("CATEGORY_REQUIRED")
        val parent=feasibility.parentAnchorUid
        val selectedEvidence=evidence.firstOrNull{
            it.confidence>=0.85&&it.classification in setOf(
                WorldEvidenceClassification.CAMPAIGN_FACT,
                WorldEvidenceClassification.SOURCE_CANON
            )
        }
        val classification=selectedEvidence?.classification?:WorldEvidenceClassification.GENERATED_PLAUSIBLE
        val normalizedCategory=normalizedWorldToken(category)
        val normalizedPhrase=normalizedWorldToken(phrase)
        val slot=shape.ordinal?:0
        val identityToken=if(shape.kind==WorldReferenceShapeKind.CATEGORY&&shape.ordinal!=null)
            "$normalizedCategory|ORDINAL:$slot" else normalizedPhrase
        val uid="DYN-${shape.baseKind.name}-${worldSha256("$campaignUid|${parent.orEmpty()}|${shape.baseKind.name}|$identityToken|$slot").take(24).uppercase()}"
        val topology=shape.topologyClassUid?:selectedEvidence?.topologyClassUid?:return UniversalWorldReferenceResolution.Unresolved("TOPOLOGY_CLASS_REQUIRED")
        val draft=WorldElementDraft(
            campaignUid,DomainRef(shape.baseKind.name,uid),selectedEvidence?.displayName?:phrase,
            shape.baseKind,normalizedCategory,parent,(shape.affordanceUids+selectedEvidence?.affordanceUids.orEmpty()).toSortedSet(),topology,
            classification,feasibility.evidenceUids,selectedEvidence?.sourceUri,selectedEvidence?.sourceRevision,selectedEvidence?.sourceHash
        )
        return UniversalWorldReferenceResolution.Latent(draft,feasibility)
    }
}

object LatentWorldReferenceCodec{
    const val STATE="world_materialization_state"
    private const val PREFIX="world_draft_"

    fun attach(reference:IntentReference,draft:WorldElementDraft,feasibility:WorldFeasibilityDecision):IntentReference=reference.copy(
        semanticTypeHints=reference.semanticTypeHints+draft.baseKind.name+draft.categoryUid+draft.affordanceUids,
        descriptorHints=reference.descriptorHints+mapOf(
            STATE to "LATENT",PREFIX+"display_name" to draft.displayName,PREFIX+"base_kind" to draft.baseKind.name,
            PREFIX+"category" to draft.categoryUid,PREFIX+"parent" to draft.parentAnchorUid.orEmpty(),
            PREFIX+"affordances" to draft.affordanceUids.sorted().joinToString(","),PREFIX+"topology" to draft.topologyClassUid,
            PREFIX+"source_classification" to draft.sourceClassification.name,PREFIX+"source_evidence" to draft.sourceEvidenceUids.sorted().joinToString(","),
            PREFIX+"source_uri" to draft.sourceUri.orEmpty(),PREFIX+"source_revision" to draft.sourceRevision.orEmpty(),
            PREFIX+"source_hash" to draft.sourceHash.orEmpty(),PREFIX+"level" to draft.materializationLevelUid,
            PREFIX+"fingerprint" to draft.fingerprint(),PREFIX+"feasibility" to feasibility.state.name
        ),
        state=IntentReferenceState.RESOLVED_LATENT,resolvedProjectedRef=draft.element,candidateProjectedRefs=emptyList(),
        resolutionEvidenceUid="RPGOS-CORE:LATENT-WORLD:${draft.fingerprint()}"
    )

    fun decode(campaignUid:String,reference:IntentReference):WorldElementDraft?{
        if(reference.state!=IntentReferenceState.RESOLVED_LATENT||reference.descriptorHints[STATE]!="LATENT")return null
        val ref=reference.resolvedProjectedRef?:return null
        fun field(name:String)=reference.descriptorHints[PREFIX+name]
        val base=runCatching{WorldElementBaseKind.valueOf(field("base_kind")?:return null)}.getOrNull()?:return null
        val draft=runCatching{WorldElementDraft(
            campaignUid,ref,field("display_name")?:return null,base,field("category")?:return null,field("parent")?.takeIf{it.isNotBlank()},
            field("affordances").orEmpty().split(',').filter{it.isNotBlank()}.toSet(),field("topology")?:return null,
            WorldEvidenceClassification.valueOf(field("source_classification")?:return null),field("source_evidence").orEmpty().split(',').filter{it.isNotBlank()},
            field("source_uri")?.takeIf{it.isNotBlank()},field("source_revision")?.takeIf{it.isNotBlank()},field("source_hash")?.takeIf{it.isNotBlank()},field("level")?:"PARTIAL"
        )}.getOrNull()?:return null
        return draft.takeIf{it.fingerprint()==field("fingerprint")&&reference.resolutionEvidenceUid=="RPGOS-CORE:LATENT-WORLD:${it.fingerprint()}"}
    }
}

internal fun normalizedWorldText(value:String)=value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
internal fun normalizedWorldToken(value:String)=normalizedWorldText(value).uppercase(Locale.ROOT).replace(' ','_').take(96)
internal fun worldNamesEquivalent(left:String,right:String):Boolean{
    fun plain(value:String)=Normalizer.normalize(normalizedWorldText(value),Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"),"")
    val leftWords=plain(left).split(' ').filter(String::isNotBlank)
    val rightWords=plain(right).split(' ').filter(String::isNotBlank)
    if(leftWords.size!=rightWords.size||leftWords.isEmpty())return false
    return leftWords.zip(rightWords).all{(a,b)->
        if(a==b)true else{
            val common=a.zip(b).takeWhile{(x,y)->x==y}.size
            val shortest=minOf(a.length,b.length)
            shortest>=4&&common>=maxOf(3,shortest-2)&&kotlin.math.abs(a.length-b.length)<=3
        }
    }
}
internal fun worldSha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
