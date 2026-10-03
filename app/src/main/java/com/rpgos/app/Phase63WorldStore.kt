package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.*

internal const val PHASE63_WORLD_CHANGE_KIND="RPGOS-CHANGE:WORLD_SIMULATION"

/** A bounded optimistic batch. It is never a full global-world dump. */
data class WorldSimulationChange internal constructor(
    val campaignUid:String,
    val historyGenerationUid:HistoryGenerationUid,
    val expectedVersion:Long,
    val skeleton:CampaignWorldSkeleton?,
    val edges:List<WorldTopologyEdge> = emptyList(),
    val actorExpansions:List<MechanicalActorExpansion> = emptyList(),
    val populationManifests:List<WorldPopulationManifest> = emptyList(),
    val populationExtractions:List<WorldPopulationExtraction> = emptyList()
):PlayerDomainChangePayload {
    init {
        require(campaignUid.isNotBlank() && expectedVersion>=0 && expectedVersion<Long.MAX_VALUE)
        require(skeleton!=null || edges.isNotEmpty() || actorExpansions.isNotEmpty() || populationManifests.isNotEmpty() || populationExtractions.isNotEmpty())
        require(skeleton==null || (skeleton.campaignUid==campaignUid && expectedVersion==0L))
        require(edges.size<=32 && edges.map { it.uid }.distinct().size==edges.size)
        require(actorExpansions.size<=32 && actorExpansions.map { it.actor }.distinct().size==actorExpansions.size)
        require(populationManifests.size<=32 && populationManifests.map { it.uid }.distinct().size==populationManifests.size)
        require(populationExtractions.size<=32 && populationExtractions.map { it.member }.distinct().size==populationExtractions.size)
    }
}

internal fun phase63WorldChangeCodec()=object:TypedPlayerChangeCodec<WorldSimulationChange>(WorldSimulationChange::class,
    ChangeIntentClassification.AUTHORITATIVE_MUTATION_INTENT,setOf("campaign","generation","expected_version","skeleton","edges","actor_expansions","population_manifests","population_extractions")) {
    override fun encode(payload:WorldSimulationChange)=buildJsonObject {
        put("campaign",payload.campaignUid);put("generation",payload.historyGenerationUid.value);put("expected_version",payload.expectedVersion)
        put("skeleton",payload.skeleton?.let(Phase63WorldCodec::skeleton)?:JsonNull)
        // Preserve the typed batch exactly. Sorting a List during command canonicalization
        // changes the candidate that Phase60 must settle, even when every edge is retained.
        put("edges",JsonArray(payload.edges.map(Phase63WorldCodec::edge)))
        if(payload.actorExpansions.isNotEmpty())put("actor_expansions",JsonArray(payload.actorExpansions.map(Phase50ActorExpansion::encode)))
        if(payload.populationManifests.isNotEmpty())put("population_manifests",JsonArray(payload.populationManifests.map(Phase63PopulationCodec::manifest)))
        if(payload.populationExtractions.isNotEmpty())put("population_extractions",JsonArray(payload.populationExtractions.map(Phase63PopulationCodec::extraction)))
    }
    override fun decodeKnownFields(obj:JsonObject)=WorldSimulationChange(Phase63WorldCodec.text(obj,"campaign"),
        HistoryGenerationUid(Phase63WorldCodec.text(obj,"generation")),Phase63WorldCodec.number(obj,"expected_version"),
        obj.getValue("skeleton").takeUnless { it==JsonNull }?.jsonObject?.let(Phase63WorldCodec::readSkeleton),
        obj.getValue("edges").jsonArray.map { Phase63WorldCodec.readEdge(it.jsonObject) },
        obj["actor_expansions"]?.jsonArray?.map { Phase50ActorExpansion.decode(it.jsonObject) }?:emptyList(),
        obj["population_manifests"]?.jsonArray?.map { Phase63PopulationCodec.readManifest(it.jsonObject) }?:emptyList(),
        obj["population_extractions"]?.jsonArray?.map { Phase63PopulationCodec.readExtraction(it.jsonObject) }?:emptyList())
    override fun conflictKeys(payload:WorldSimulationChange)=setOf("P63:WORLD_REVISION:${payload.campaignUid}:${payload.expectedVersion}")
}

/** Versions are retained as separate typed changes, not merged into a lossy scalar effect. */
internal fun requireWorldSimulationChain(changes:List<WorldSimulationChange>) {
    require(changes.size<=256) { "P63:WORLD_CHANGE_BUDGET" }
    if(changes.isEmpty())return
    val first=changes.first()
    require(changes.all { it.campaignUid==first.campaignUid && it.historyGenerationUid==first.historyGenerationUid }) { "P63:WORLD_CHAIN_SCOPE" }
    require(changes.zipWithNext().all { (before,after)->after.expectedVersion==Math.addExact(before.expectedVersion,1) }) { "P63:WORLD_CHAIN_VERSION" }
    require(changes.drop(1).all { it.skeleton==null }) { "P63:WORLD_CHAIN_REINITIALIZATION" }
}

internal object Phase63WorldSchema {
    const val VERSION=2
    const val ROOTS="phase63_world_roots"
    const val EDGES="phase63_world_topology_edges"
    val authoritativeTables=setOf(ROOTS,EDGES)+Phase63PopulationSchema.worldTables
    fun ensureReady(db:SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ROOTS(
            campaign_uid TEXT PRIMARY KEY NOT NULL,
            state_version INTEGER NOT NULL CHECK(state_version>0),
            skeleton_canonical TEXT NOT NULL,
            skeleton_fingerprint TEXT NOT NULL,
            updated_order INTEGER NOT NULL CHECK(updated_order>=0)
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $EDGES(
            campaign_uid TEXT NOT NULL,
            edge_uid TEXT NOT NULL,
            edge_version INTEGER NOT NULL CHECK(edge_version>0),
            origin_kind_uid TEXT NOT NULL,
            origin_uid TEXT NOT NULL,
            destination_kind_uid TEXT NOT NULL,
            destination_uid TEXT NOT NULL,
            edge_canonical TEXT NOT NULL,
            edge_fingerprint TEXT NOT NULL,
            updated_order INTEGER NOT NULL CHECK(updated_order>0),
            PRIMARY KEY(campaign_uid,edge_uid)
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p63_topology_origin ON $EDGES(campaign_uid,origin_kind_uid,origin_uid,edge_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_p63_topology_destination ON $EDGES(campaign_uid,destination_kind_uid,destination_uid,edge_uid)")
        Phase63PopulationSchema.ensureReady(db)
    }
    fun isReady(db:SQLiteDatabase,version:Int=VERSION):Boolean {
        fun columns(table:String)=db.rawQuery("PRAGMA table_info($table)",null).use { c->buildSet { while(c.moveToNext())add(c.getString(1)) } }
        return columns(ROOTS)==setOf("campaign_uid","state_version","skeleton_canonical","skeleton_fingerprint","updated_order") &&
            columns(EDGES)==setOf("campaign_uid","edge_uid","edge_version","origin_kind_uid","origin_uid",
                "destination_kind_uid","destination_uid","edge_canonical","edge_fingerprint","updated_order") &&
            (version==1 || Phase63PopulationSchema.isReady(db))
    }
}

internal data class CanonicalWorldRoot(val version:Long,val skeleton:CampaignWorldSkeleton)

/** Reading a missing root is intentionally non-mutating. */
internal class Phase63WorldStore(private val db:SQLiteDatabase,private val campaignUid:String) {
    init { require(campaignUid.isNotBlank()) }
    fun root():CanonicalWorldRoot? {
        if(!Phase63WorldSchema.isReady(db))return null
        return db.rawQuery("SELECT state_version,skeleton_canonical,skeleton_fingerprint FROM ${Phase63WorldSchema.ROOTS} WHERE campaign_uid=?",arrayOf(campaignUid)).use { c->
            if(!c.moveToFirst())null else {
                val canonical=c.getString(1);val value=Phase63WorldCodec.readSkeleton(Json.parseToJsonElement(canonical).jsonObject)
                require(value.campaignUid==campaignUid && Phase63WorldCodec.skeleton(value).toString()==canonical && value.fingerprint==c.getString(2)) { "P63:ROOT_CORRUPT" }
                CanonicalWorldRoot(c.getLong(0).also { require(it>0) { "P63:ROOT_VERSION_CORRUPT" } },value)
            }
        }
    }
    /** This is a Core-only read. Model/player consumers must use WorldTopologyPort authorization. */
    fun edgesFrom(origin:DomainRef,limit:Int=512):List<WorldTopologyEdge> {
        require(limit in 1..512)
        if(!Phase63WorldSchema.isReady(db))return emptyList()
        return db.rawQuery("SELECT edge_canonical,edge_fingerprint FROM ${Phase63WorldSchema.EDGES} WHERE campaign_uid=? AND origin_kind_uid=? AND origin_uid=? ORDER BY edge_uid LIMIT ?",
            arrayOf(campaignUid,origin.kindUid,origin.uid,(limit+1).toString())).use { c->buildList {
            while(c.moveToNext()) {
                require(size<limit) { "P63:EDGE_READ_BUDGET" }
                val canonical=c.getString(0);val edge=Phase63WorldCodec.readEdge(Json.parseToJsonElement(canonical).jsonObject)
                require(edge.origin==origin && Phase63WorldCodec.edge(edge).toString()==canonical && edge.fingerprint==c.getString(1)) { "P63:EDGE_CORRUPT" }
                add(edge)
            }
        } }
    }
    fun apply(identity:TurnTransactionIdentity,change:WorldSimulationChange,order:Long,changeSet:PlayerChangeSet) {
        require(db.inTransaction() && identity.campaignUid==campaignUid && change.campaignUid==campaignUid && order>0) { "P63:TURN_REQUIRED" }
        requireCanonicalGameplayMutation(db,campaignUid)
        // Live generation is checked by TurnTransaction admission. It is deliberately not
        // replayed canonical state: after undo, retained historical payloads keep their generation.
        val before=root()
        require((before?.version?:0L)==change.expectedVersion) { "P63:STALE_WORLD_VERSION" }
        val skeleton=change.skeleton?:before?.skeleton?:error("P63:ROOT_REQUIRED")
        // Only the registered local connection rule is currently executable. An arbitrary AI
        // edge, external citation or shared parent cannot bypass this materialization evidence.
        if(change.edges.isNotEmpty()) {
            val at=changeSet.changes.mapNotNull { it.payload as? TemporalStateChange }.singleOrNull()?.expectedTime
                ?:Phase60TemporalStateStore(db,campaignUid).read().time
            val facts=changeSet.changes.mapNotNull { it.payload as? CampaignTruthChange }
                .filter { it.kind==TruthKind.FACT && it.subjectUid!=null }.groupBy { it.subjectUid!! }
            val drafts=facts.mapNotNull { (uid,values)->
                fun one(predicate:String)=values.singleOrNull { it.predicate==predicate }?.objectValue
                if(one(CampaignWorldFacts.KIND)!="PLACE" || one(CampaignWorldFacts.SOURCE_CLASSIFICATION)!=WorldEvidenceClassification.GENERATED_PLAUSIBLE.name)return@mapNotNull null
                WorldElementDraft(campaignUid,DomainRef("PLACE",uid),one(CampaignWorldFacts.NAME)?:return@mapNotNull null,WorldElementBaseKind.PLACE,
                    one(CampaignWorldFacts.CATEGORY)?:return@mapNotNull null,one(CampaignWorldFacts.PARENT),
                    values.filter { it.predicate==CampaignWorldFacts.AFFORDANCE }.mapNotNull { it.objectValue }.toSet(),
                    one(CampaignWorldFacts.TOPOLOGY)?:return@mapNotNull null,WorldEvidenceClassification.GENERATED_PLAUSIBLE,emptyList(),null,null,null,
                    one(CampaignWorldFacts.MATERIALIZATION_LEVEL)?:"PARTIAL",one(CampaignWorldFacts.SLOT_ORDINAL)?.toLong()?:0L,one(CampaignWorldFacts.SLOT_CATEGORY))
            }
            val allowed=drafts.flatMap { CoreLatentWorldRules.localEdges(skeleton,it,at) }.associateBy { it.uid }
            require(change.edges.all { allowed[it.uid]==it }) { "P63:TOPOLOGY_OWNER_EVIDENCE_REQUIRED" }
        }
        if(before==null) {
            db.execSQL("INSERT INTO ${Phase63WorldSchema.ROOTS} VALUES(?,?,?,?,?)",arrayOf(campaignUid,1L,Phase63WorldCodec.skeleton(skeleton).toString(),skeleton.fingerprint,order))
        } else {
            require(change.skeleton==null) { "P63:ROOT_REGENERATION_FORBIDDEN" }
            db.execSQL("UPDATE ${Phase63WorldSchema.ROOTS} SET state_version=?,updated_order=? WHERE campaign_uid=?",arrayOf(Math.addExact(before.version,1L),order,campaignUid))
        }
        change.edges.sortedBy { it.uid }.forEach { edge->
            val previous=db.rawQuery("SELECT edge_version FROM ${Phase63WorldSchema.EDGES} WHERE campaign_uid=? AND edge_uid=?",arrayOf(campaignUid,edge.uid))
                .use { if(it.moveToFirst())it.getLong(0) else null }
            require(edge.version==(previous?.let { Math.addExact(it,1L) }?:1L)) { "P63:EDGE_VERSION_CONFLICT" }
            db.execSQL("INSERT OR REPLACE INTO ${Phase63WorldSchema.EDGES} VALUES(?,?,?,?,?,?,?,?,?,?)",
                arrayOf(campaignUid,edge.uid,edge.version,edge.origin.kindUid,edge.origin.uid,edge.destination.kindUid,edge.destination.uid,
                    Phase63WorldCodec.edge(edge).toString(),edge.fingerprint,order))
        }
        change.actorExpansions.sortedBy { it.actor.uid }.forEach { Phase50ActorExpansion.apply(db,identity,it,order) }
        val populations=WorldPopulationStore(db,campaignUid)
        change.populationManifests.forEach { populations.register(identity,it,order) }
        change.populationExtractions.forEach { populations.validateExtraction(it,changeSet);populations.extract(identity,it,order) }
    }
}
