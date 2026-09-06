package com.rpgos.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

const val PHASE55_TO_58_MEMORY_SCHEMA_VERSION = 1

@JvmInline value class HistoryGenerationUid(val value:String){init{require(value.isNotBlank())}}

private fun boundedScore(value:Double,label:String):Double{
    require(value.isFinite()&&value in 0.0..1.0){"RPGOS-MEMORY:$label"}
    return value
}
@JvmInline value class CampaignImportance(val value:Double){init{boundedScore(value,"CAMPAIGN_IMPORTANCE_INVALID")}}
@JvmInline value class HolderSalience(val value:Double){init{boundedScore(value,"HOLDER_SALIENCE_INVALID")}}
@JvmInline value class QueryRelevance(val value:Double){init{boundedScore(value,"QUERY_RELEVANCE_INVALID")}}
@JvmInline value class RecallStrength(val value:Double){init{boundedScore(value,"RECALL_STRENGTH_INVALID")}}
@JvmInline value class MemoryAccuracy(val value:Double){init{boundedScore(value,"MEMORY_ACCURACY_INVALID")}}
@JvmInline value class BeliefConfidence(val value:Double){init{boundedScore(value,"BELIEF_CONFIDENCE_INVALID")}}
@JvmInline value class SourceReliability(val value:Double){init{boundedScore(value,"SOURCE_RELIABILITY_INVALID")}}
@JvmInline value class SemanticSimilarityScore(val value:Float){init{require(value.isFinite()&&value in -1f..1f){"RPGOS-MEMORY:SEMANTIC_SCORE_INVALID"}}}

enum class MemoryArtifactKind { EPISODE_MANIFEST, EPISODE_INTERPRETATION, HOLDER_EPISODE_MEMORY, SEMANTIC_ASSERTION }
enum class MemoryArtifactStatus { CLEAN, DIRTY, REBUILDING, FAILED, SUPERSEDED, ORPHANED }
enum class MemoryDependencyKind { DERIVED_FROM, MEMBERSHIP_SOURCE, SUPPORTED_BY, PROJECTED_FROM, INDEXED_FROM, SUPERSEDES, MATERIALIZED_FROM }

data class MemorySourceLeafRef(
    val sourceKind:String,
    val sourceUid:String,
    val sourceVersion:Long,
    val committedOrder:Long,
    val fingerprint:String
){init{
    require(listOf(sourceKind,sourceUid,fingerprint).none{it.isBlank()})
    require(sourceVersion>=0&&committedOrder>=0)
}}

data class MemoryArtifactIdentity(
    val campaignUid:String,
    val historyGenerationUid:HistoryGenerationUid,
    val logicalArtifactUid:String,
    val artifactRevisionUid:String,
    val artifactKind:MemoryArtifactKind,
    val sourceLeafRefs:List<MemorySourceLeafRef>,
    val sourceLeafSetFingerprint:String,
    val derivationRuleUid:String,
    val derivationVersion:Int,
    val asOfCommittedOrder:Long,
    val createdFromOrder:Long,
    val createdThroughOrder:Long
){init{
    require(listOf(campaignUid,logicalArtifactUid,artifactRevisionUid,sourceLeafSetFingerprint,derivationRuleUid).none{it.isBlank()})
    require(sourceLeafRefs.isNotEmpty()&&sourceLeafRefs.map{it.sourceKind to it.sourceUid}.distinct().size==sourceLeafRefs.size)
    require(derivationVersion>0&&createdFromOrder>=0&&createdThroughOrder>=createdFromOrder&&asOfCommittedOrder>=createdThroughOrder)
    require(sourceLeafSetFingerprint==memoryLeafFingerprint(sourceLeafRefs)){"RPGOS-MEMORY:SOURCE_FINGERPRINT_MISMATCH"}
}}

enum class WorkingMemoryAudience { GAME_MASTER, PLAYER, WORLD_ACTOR, DIRECTOR }
data class WorkingMemoryScope(
    val campaignUid:String,
    val historyGenerationUid:HistoryGenerationUid,
    val audienceKindUid:String,
    val principalUid:String,
    val purposeUid:String,
    val sceneUid:String?,
    val asOfCommittedOrder:Long,
    val accessPolicyVersion:Long
){init{
    require(listOf(campaignUid,audienceKindUid,principalUid,purposeUid).none{it.isBlank()})
    require(sceneUid?.isBlank()!=true&&asOfCommittedOrder>=0&&accessPolicyVersion>=0)
}}

data class WorkingMemoryRecord(
    val canonicalRecordUid:String,
    val sourceEpistemicStateUid:String,
    val projectionBoundaryUid:String,
    val relevance:QueryRelevance,
    val pinned:Boolean=false
){init{require(listOf(canonicalRecordUid,sourceEpistemicStateUid,projectionBoundaryUid).none{it.isBlank()})}}

data class WorkingMemorySnapshot(
    val scope:WorkingMemoryScope,
    val records:List<WorkingMemoryRecord>,
    val sourceFingerprint:String
){init{
    require(records.map{it.canonicalRecordUid}.distinct().size==records.size&&sourceFingerprint.isNotBlank())
}}

data class MemoryEventLeaf(
    val eventUid:String,
    val turnUid:String,
    val committedOrder:Long,
    val eventOrdinal:Int,
    val participantRefs:List<DomainRef>,
    val locationRefs:List<DomainRef>,
    val explicitBoundaryBefore:Boolean=false,
    val fingerprint:String
){init{
    require(listOf(eventUid,turnUid,fingerprint).none{it.isBlank()})
    require(committedOrder>0&&eventOrdinal>=0)
}}

data class EpisodeManifest(
    val identity:MemoryArtifactIdentity,
    val eventUids:List<String>,
    val startOrder:Long,
    val endOrder:Long,
    val participantRefs:List<DomainRef>,
    val locationRefs:List<DomainRef>,
    val segmentationRuleUid:String,
    val segmentationVersion:Int
){init{
    require(identity.artifactKind==MemoryArtifactKind.EPISODE_MANIFEST&&eventUids.isNotEmpty()&&eventUids.distinct().size==eventUids.size)
    require(startOrder>0&&endOrder>=startOrder&&segmentationRuleUid.isNotBlank()&&segmentationVersion>0)
}}

data class EpisodeInterpretation(
    val identity:MemoryArtifactIdentity,
    val episodeLogicalUid:String,
    val localeUid:String,
    val title:String,
    val summary:String,
    val presentationTags:Set<String>
){init{
    require(identity.artifactKind==MemoryArtifactKind.EPISODE_INTERPRETATION)
    require(listOf(episodeLogicalUid,localeUid,title,summary).none{it.isBlank()}&&presentationTags.none{it.isBlank()})
}}

data class HolderEpisodeMemory(
    val identity:MemoryArtifactIdentity,
    val episodeLogicalUid:String,
    val holder:KnowledgeHolderRef,
    val rememberedEventUids:List<String>,
    val omittedEventUids:List<String>,
    val accuracy:MemoryAccuracy,
    val salience:HolderSalience,
    val recallStrength:RecallStrength,
    val acquisitionUids:List<String>
){init{
    require(identity.artifactKind==MemoryArtifactKind.HOLDER_EPISODE_MEMORY&&episodeLogicalUid.isNotBlank())
    require((rememberedEventUids+omittedEventUids).distinct().size==rememberedEventUids.size+omittedEventUids.size)
    require(acquisitionUids.isNotEmpty()&&acquisitionUids.none{it.isBlank()})
}}

enum class SemanticAssertionPolarity { AFFIRMED, DENIED, UNKNOWN, PARTIAL }
enum class SemanticAssertionEpistemicKind { DERIVED_CONCLUSION, KNOWLEDGE, BELIEF, MEMORY, HYPOTHESIS }
enum class SemanticAssertionLifecycle { ACTIVE, EXPIRED, CONTESTED, SUPERSEDED, RETRACTED }

data class SemanticMemoryAssertion(
    val identity:MemoryArtifactIdentity,
    val assertionUid:String,
    val holder:KnowledgeHolderRef?,
    val subjectRef:DomainRef,
    val predicateUid:String,
    val objectValue:String,
    val polarity:SemanticAssertionPolarity,
    val epistemicKind:SemanticAssertionEpistemicKind,
    val validFromOrder:Long,
    val validUntilOrder:Long?,
    val lifecycleState:SemanticAssertionLifecycle,
    val supportingLeafRefs:List<MemorySourceLeafRef>,
    val contradictingLeafRefs:List<MemorySourceLeafRef>,
    val closedWorldRuleUid:String?=null
){init{
    require(identity.artifactKind==MemoryArtifactKind.SEMANTIC_ASSERTION)
    require(listOf(assertionUid,predicateUid,objectValue).none{it.isBlank()}&&validFromOrder>=0)
    require(validUntilOrder==null||validUntilOrder>=validFromOrder)
    require(closedWorldRuleUid?.isBlank()!=true)
    require(
        supportingLeafRefs.isNotEmpty() ||
            (closedWorldRuleUid!=null && polarity in setOf(SemanticAssertionPolarity.UNKNOWN,SemanticAssertionPolarity.DENIED))
    ){"RPGOS-MEMORY:ABSENCE_IS_NOT_EVIDENCE"}
}}

/** Read-only projection source for Phase59. Payload remains owned by its Phase55-58 artifact
 * owner; Bekko receives only a legal, audience-scoped textual projection built from this row. */
internal data class ActiveMemoryArtifactRevision(
    val campaignUid:String,
    val historyGenerationUid:HistoryGenerationUid,
    val logicalArtifactUid:String,
    val artifactRevisionUid:String,
    val artifactKind:MemoryArtifactKind,
    val sourceLeafSetFingerprint:String,
    val derivationVersion:Long,
    val asOfCommittedOrder:Long,
    val payloadJson:String
){init{
    require(listOf(campaignUid,logicalArtifactUid,artifactRevisionUid,sourceLeafSetFingerprint,payloadJson).none{it.isBlank()})
    require(derivationVersion>0&&asOfCommittedOrder>=0)
}}

data class ConsolidationWorkBudget(
    val maxLeafRecords:Int=256,
    val maxEpisodes:Int=32,
    // A single legal episode may yield more than one holder memory/assertion per leaf. Keep the
    // input slice fixed at 256, but do not let the old 128-output default create a retry loop that
    // can never advance its watermark for a dense episode.
    val maxCandidateRelations:Int=1_024,
    val maxOutputArtifacts:Int=1_024,
    val maxSerializedBytes:Int=2*1024*1024,
    val maxWorkUnits:Int=10_000,
    val maxWallClockMillis:Long=500
){init{
    require(maxLeafRecords in 1..10_000&&maxEpisodes in 1..1_000&&maxCandidateRelations in 1..10_000)
    require(maxOutputArtifacts in 1..10_000&&maxSerializedBytes>0&&maxWorkUnits>0&&maxWallClockMillis in 1..60_000)
}}

enum class ConsolidationStatus { COMMITTED, FAILED }
data class ConsolidationReceipt(
    val consolidationUid:String,
    val campaignUid:String,
    val historyGenerationUid:HistoryGenerationUid,
    val fromOrder:Long,
    val throughOrder:Long,
    val inputLeafSetFingerprint:String,
    val derivationRuleUid:String,
    val derivationVersion:Int,
    val producedArtifactRevisionUids:List<String>,
    val updatedArtifactRevisionUids:List<String>,
    val invalidatedArtifactRevisionUids:List<String>,
    val canonicalMutationReceiptUids:List<String>,
    val previousWatermark:Long,
    val resultingWatermark:Long,
    val resumeCursor:String?,
    val status:ConsolidationStatus,
    val reasonUid:String?=null
){init{
    require(listOf(consolidationUid,campaignUid,inputLeafSetFingerprint,derivationRuleUid).none{it.isBlank()})
    require(fromOrder>=0&&throughOrder>=fromOrder&&derivationVersion>0&&previousWatermark>=0&&resultingWatermark>=previousWatermark)
    require(resumeCursor?.isBlank()!=true&&reasonUid?.isBlank()!=true)
}}

data class MemoryEnrichmentRequest(val requestUid:String,val manifest:EpisodeManifest,val localeUid:String){init{require(requestUid.isNotBlank()&&localeUid.isNotBlank())}}
sealed interface MemoryEnrichmentResult{
    data class Success(val title:String,val summary:String,val tags:Set<String>):MemoryEnrichmentResult{init{require(title.isNotBlank()&&summary.isNotBlank()&&tags.none{it.isBlank()})}}
    data class Failure(val reasonUid:String,val retryable:Boolean=false):MemoryEnrichmentResult{init{require(reasonUid.isNotBlank())}}
}
fun interface MemoryEnrichmentPort{fun enrich(request:MemoryEnrichmentRequest,cancellation:AiCancellationSignal):MemoryEnrichmentResult}

internal fun encodeMemoryEnrichmentRequest(request:MemoryEnrichmentRequest)=JSONObject().apply{
    put("schema_version",1);put("request_uid",request.requestUid);put("locale_uid",request.localeUid)
    put("episode_uid",request.manifest.identity.logicalArtifactUid)
    put("event_uids",JSONArray(request.manifest.eventUids))
    put("start_order",request.manifest.startOrder);put("end_order",request.manifest.endOrder)
    put("participant_refs",JSONArray(request.manifest.participantRefs.map{"${it.kindUid}:${it.uid}"}))
    put("location_refs",JSONArray(request.manifest.locationRefs.map{"${it.kindUid}:${it.uid}"}))
    put("instruction","Return a presentation-only title, summary and tags. Do not add facts or mutations.")
}.toString()

internal fun decodeMemoryEnrichmentPresentation(payload:String):MemoryEnrichmentResult.Success{
    val root=JSONObject(payload)
    require(root.optInt("schema_version",1)==1){"RPGOS-MEMORY:ENRICHMENT_SCHEMA_UNSUPPORTED"}
    val title=root.getString("title").trim();val summary=root.getString("summary").trim()
    val tags=root.optJSONArray("tags")?.let{array->(0 until array.length()).map{array.getString(it).trim()}.filter{it.isNotBlank()}.toSet()}.orEmpty()
    require(title.isNotBlank()&&summary.isNotBlank()){"RPGOS-MEMORY:ENRICHMENT_EMPTY_PRESENTATION"}
    return MemoryEnrichmentResult.Success(title.take(160),summary.take(2_048),tags.map{it.take(64)}.toSet())
}

internal object Phase55To58MemorySchema{
    const val GENERATIONS="campaign_history_generations"
    const val ARTIFACTS="memory_artifact_revisions"
    const val LEAVES="memory_artifact_source_leaves"
    const val DEPENDENCIES="memory_dependency_edges"
    const val EPISODE_MEMBERSHIP="memory_episode_membership"
    const val RECEIPTS="memory_consolidation_receipts"
    const val STATE="memory_consolidation_state"
    val tables=setOf(GENERATIONS,ARTIFACTS,LEAVES,DEPENDENCIES,EPISODE_MEMBERSHIP,RECEIPTS,STATE)

    fun ensureReady(db:SQLiteDatabase,campaignUid:String){
        require(campaignUid.isNotBlank())
        db.execSQL("""CREATE TABLE IF NOT EXISTS $GENERATIONS(
            campaign_uid TEXT PRIMARY KEY,history_generation_uid TEXT NOT NULL,generation_ordinal INTEGER NOT NULL CHECK(generation_ordinal>=0),
            changed_at_epoch_ms INTEGER NOT NULL,change_reason_uid TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $ARTIFACTS(
            campaign_uid TEXT NOT NULL,history_generation_uid TEXT NOT NULL,logical_artifact_uid TEXT NOT NULL,artifact_revision_uid TEXT NOT NULL,
            artifact_kind_uid TEXT NOT NULL,source_leaf_set_fingerprint TEXT NOT NULL,derivation_rule_uid TEXT NOT NULL,derivation_version INTEGER NOT NULL,
            as_of_committed_order INTEGER NOT NULL,created_from_order INTEGER NOT NULL,created_through_order INTEGER NOT NULL,status_uid TEXT NOT NULL,
            payload_json TEXT NOT NULL,created_at_epoch_ms INTEGER NOT NULL,PRIMARY KEY(campaign_uid,artifact_revision_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $LEAVES(
            campaign_uid TEXT NOT NULL,artifact_revision_uid TEXT NOT NULL,source_kind_uid TEXT NOT NULL,source_uid TEXT NOT NULL,
            source_version INTEGER NOT NULL,committed_order INTEGER NOT NULL,source_fingerprint TEXT NOT NULL,
            PRIMARY KEY(campaign_uid,artifact_revision_uid,source_kind_uid,source_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $DEPENDENCIES(
            campaign_uid TEXT NOT NULL,history_generation_uid TEXT NOT NULL,source_revision_uid TEXT NOT NULL,target_revision_uid TEXT NOT NULL,
            dependency_kind_uid TEXT NOT NULL,PRIMARY KEY(campaign_uid,source_revision_uid,target_revision_uid,dependency_kind_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $EPISODE_MEMBERSHIP(
            campaign_uid TEXT NOT NULL,episode_revision_uid TEXT NOT NULL,event_uid TEXT NOT NULL,event_ordinal INTEGER NOT NULL,
            committed_order INTEGER NOT NULL,PRIMARY KEY(campaign_uid,episode_revision_uid,event_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $RECEIPTS(
            campaign_uid TEXT NOT NULL,consolidation_uid TEXT NOT NULL,history_generation_uid TEXT NOT NULL,from_order INTEGER NOT NULL,
            through_order INTEGER NOT NULL,input_leaf_set_fingerprint TEXT NOT NULL,derivation_rule_uid TEXT NOT NULL,derivation_version INTEGER NOT NULL,
            previous_watermark INTEGER NOT NULL,resulting_watermark INTEGER NOT NULL,resume_cursor TEXT,status_uid TEXT NOT NULL,reason_uid TEXT,
            receipt_json TEXT NOT NULL,PRIMARY KEY(campaign_uid,consolidation_uid))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS $STATE(
            campaign_uid TEXT PRIMARY KEY,history_generation_uid TEXT NOT NULL,watermark_order INTEGER NOT NULL,resume_cursor TEXT,
            last_input_fingerprint TEXT NOT NULL,last_status_uid TEXT NOT NULL,updated_at_epoch_ms INTEGER NOT NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_artifact_logical ON $ARTIFACTS(campaign_uid,history_generation_uid,logical_artifact_uid,status_uid,as_of_committed_order)")
        // Keyset pagination for Phase58 -> Phase59 projection. Keeping logical_artifact_uid out
        // of this prefix lets SQLite seek directly to the next CLEAN revision at 200k+ rows.
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_artifact_active_page ON $ARTIFACTS(campaign_uid,history_generation_uid,status_uid,as_of_committed_order,artifact_revision_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_leaf_reverse ON $LEAVES(campaign_uid,source_kind_uid,source_uid,artifact_revision_uid)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_memory_dependency_reverse ON $DEPENDENCIES(campaign_uid,source_revision_uid,target_revision_uid)")
        db.execSQL("INSERT OR IGNORE INTO $GENERATIONS(campaign_uid,history_generation_uid,generation_ordinal,changed_at_epoch_ms,change_reason_uid) VALUES(?,?,?,?,?)",
            arrayOf<Any?>(campaignUid,"HGEN-$campaignUid-0",0,System.currentTimeMillis(),"CAMPAIGN_BOOTSTRAP"))
        db.execSQL("INSERT OR IGNORE INTO $STATE(campaign_uid,history_generation_uid,watermark_order,resume_cursor,last_input_fingerprint,last_status_uid,updated_at_epoch_ms) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(campaignUid,"HGEN-$campaignUid-0",0,null,"EMPTY","IDLE",System.currentTimeMillis()))
    }

    fun isReady(db:SQLiteDatabase)=tables.all{table->db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",arrayOf(table)).use{it.moveToFirst()}}
}

class HistoryGenerationStore(private val db:SQLiteDatabase,private val campaignUid:String){
    init{require(campaignUid.isNotBlank())}
    fun current():HistoryGenerationUid=db.rawQuery(
        "SELECT history_generation_uid FROM ${Phase55To58MemorySchema.GENERATIONS} WHERE campaign_uid=?",arrayOf(campaignUid)
    ).use{c->check(c.moveToFirst()){"RPGOS-MEMORY:HISTORY_GENERATION_MISSING"};HistoryGenerationUid(c.getString(0))}

    fun advance(reasonUid:String):HistoryGenerationUid{
        require(reasonUid.isNotBlank())
        val ordinal=db.rawQuery("SELECT generation_ordinal FROM ${Phase55To58MemorySchema.GENERATIONS} WHERE campaign_uid=?",arrayOf(campaignUid))
            .use{c->check(c.moveToFirst());c.getLong(0)+1}
        val next=HistoryGenerationUid("HGEN-$campaignUid-$ordinal-${UUID.randomUUID()}")
        db.execSQL("UPDATE ${Phase55To58MemorySchema.GENERATIONS} SET history_generation_uid=?,generation_ordinal=?,changed_at_epoch_ms=?,change_reason_uid=? WHERE campaign_uid=?",
            arrayOf<Any?>(next.value,ordinal,System.currentTimeMillis(),reasonUid,campaignUid))
        db.execSQL("UPDATE ${Phase55To58MemorySchema.STATE} SET history_generation_uid=?,watermark_order=0,resume_cursor=NULL,last_input_fingerprint='EMPTY',last_status_uid='DIRTY',updated_at_epoch_ms=? WHERE campaign_uid=?",
            arrayOf<Any?>(next.value,System.currentTimeMillis(),campaignUid))
        return next
    }
}

class MemoryArtifactStore(private val db:SQLiteDatabase){
    internal fun activeCleanRevisionsByUid(
        campaignUid:String,
        historyGenerationUid:HistoryGenerationUid,
        artifactRevisionUids:Set<String>,
        asOfOrder:Long
    ):List<ActiveMemoryArtifactRevision>{
        require(campaignUid.isNotBlank()&&asOfOrder>=0)
        require(artifactRevisionUids.size in 1..200){"RPGOS-MEMORY:ARTIFACT_REVISION_LOOKUP_LIMIT"}
        require(artifactRevisionUids.none{it.isBlank()})
        val orderedRevisionUids=artifactRevisionUids.sorted()
        val placeholders=orderedRevisionUids.joinToString(","){"?"}
        return db.rawQuery(
            """SELECT logical_artifact_uid,artifact_revision_uid,artifact_kind_uid,
                source_leaf_set_fingerprint,derivation_version,as_of_committed_order,payload_json
                FROM ${Phase55To58MemorySchema.ARTIFACTS}
                WHERE campaign_uid=? AND history_generation_uid=? AND status_uid=?
                AND as_of_committed_order<=? AND artifact_revision_uid IN ($placeholders)
                ORDER BY as_of_committed_order,artifact_revision_uid""",
            arrayOf(
                campaignUid,historyGenerationUid.value,MemoryArtifactStatus.CLEAN.name,asOfOrder.toString(),
                *orderedRevisionUids.toTypedArray()
            )
        ).use{cursor->buildList{
            while(cursor.moveToNext())add(ActiveMemoryArtifactRevision(
                campaignUid,historyGenerationUid,cursor.getString(0),cursor.getString(1),
                MemoryArtifactKind.valueOf(cursor.getString(2)),cursor.getString(3),cursor.getLong(4),
                cursor.getLong(5),cursor.getString(6)
            ))
        }}
    }

    fun supersedePriorRevisions(identity:MemoryArtifactIdentity):List<String>{
        val prior=db.rawQuery("""SELECT artifact_revision_uid FROM ${Phase55To58MemorySchema.ARTIFACTS}
            WHERE campaign_uid=? AND history_generation_uid=? AND logical_artifact_uid=? AND artifact_kind_uid=?
            AND artifact_revision_uid!=? AND status_uid!=? ORDER BY artifact_revision_uid""",arrayOf(
            identity.campaignUid,identity.historyGenerationUid.value,identity.logicalArtifactUid,identity.artifactKind.name,
            identity.artifactRevisionUid,MemoryArtifactStatus.SUPERSEDED.name
        )).use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
        prior.forEach{revision->db.execSQL("UPDATE ${Phase55To58MemorySchema.ARTIFACTS} SET status_uid=? WHERE campaign_uid=? AND artifact_revision_uid=?",
            arrayOf<Any?>(MemoryArtifactStatus.SUPERSEDED.name,identity.campaignUid,revision))}
        return prior
    }
    fun upsert(identity:MemoryArtifactIdentity,status:MemoryArtifactStatus,payloadJson:String,dependencies:List<Pair<String,MemoryDependencyKind>> = emptyList()){
        require(payloadJson.isNotBlank())
        val existing=db.rawQuery("SELECT source_leaf_set_fingerprint,payload_json FROM ${Phase55To58MemorySchema.ARTIFACTS} WHERE campaign_uid=? AND artifact_revision_uid=?",
            arrayOf(identity.campaignUid,identity.artifactRevisionUid)).use{c->if(c.moveToFirst())c.getString(0) to c.getString(1) else null}
        if(existing!=null){require(existing.first==identity.sourceLeafSetFingerprint&&existing.second==payloadJson){"RPGOS-MEMORY:ARTIFACT_REVISION_CONFLICT"};return}
        db.insertOrThrow(Phase55To58MemorySchema.ARTIFACTS,null,ContentValues().apply{
            put("campaign_uid",identity.campaignUid);put("history_generation_uid",identity.historyGenerationUid.value)
            put("logical_artifact_uid",identity.logicalArtifactUid);put("artifact_revision_uid",identity.artifactRevisionUid);put("artifact_kind_uid",identity.artifactKind.name)
            put("source_leaf_set_fingerprint",identity.sourceLeafSetFingerprint);put("derivation_rule_uid",identity.derivationRuleUid);put("derivation_version",identity.derivationVersion)
            put("as_of_committed_order",identity.asOfCommittedOrder);put("created_from_order",identity.createdFromOrder);put("created_through_order",identity.createdThroughOrder)
            put("status_uid",status.name);put("payload_json",payloadJson);put("created_at_epoch_ms",System.currentTimeMillis())
        })
        identity.sourceLeafRefs.forEach{leaf->db.insertOrThrow(Phase55To58MemorySchema.LEAVES,null,ContentValues().apply{
            put("campaign_uid",identity.campaignUid);put("artifact_revision_uid",identity.artifactRevisionUid);put("source_kind_uid",leaf.sourceKind);put("source_uid",leaf.sourceUid)
            put("source_version",leaf.sourceVersion);put("committed_order",leaf.committedOrder);put("source_fingerprint",leaf.fingerprint)
        })}
        dependencies.forEach{(source,kind)->db.insertWithOnConflict(Phase55To58MemorySchema.DEPENDENCIES,null,ContentValues().apply{
            put("campaign_uid",identity.campaignUid);put("history_generation_uid",identity.historyGenerationUid.value);put("source_revision_uid",source)
            put("target_revision_uid",identity.artifactRevisionUid);put("dependency_kind_uid",kind.name)
        },SQLiteDatabase.CONFLICT_IGNORE)}
    }

    fun markDependentsDirty(campaignUid:String,sourceKind:String,sourceUid:String):Set<String>{
        val direct=db.rawQuery("SELECT artifact_revision_uid FROM ${Phase55To58MemorySchema.LEAVES} WHERE campaign_uid=? AND source_kind_uid=? AND source_uid=?",
            arrayOf(campaignUid,sourceKind,sourceUid)).use{c->buildSet{while(c.moveToNext())add(c.getString(0))}}.toMutableSet()
        val queue=ArrayDeque(direct)
        while(queue.isNotEmpty()){
            val source=queue.removeFirst()
            db.rawQuery("SELECT target_revision_uid FROM ${Phase55To58MemorySchema.DEPENDENCIES} WHERE campaign_uid=? AND source_revision_uid=?",
                arrayOf(campaignUid,source)).use{c->while(c.moveToNext()){val target=c.getString(0);if(direct.add(target))queue.add(target)}}
        }
        direct.forEach{revision->db.execSQL("UPDATE ${Phase55To58MemorySchema.ARTIFACTS} SET status_uid=? WHERE campaign_uid=? AND artifact_revision_uid=? AND status_uid!=?",
            arrayOf<Any?>(MemoryArtifactStatus.DIRTY.name,campaignUid,revision,MemoryArtifactStatus.SUPERSEDED.name))}
        return direct
    }
}

class DeterministicEpisodeSegmenter(
    private val maximumTurns:Int=20,
    private val maximumEvents:Int=128,
    private val ruleUid:String="RPGOS-P56-PRIMARY-EPISODE",
    private val ruleVersion:Int=1
){init{require(maximumTurns>0&&maximumEvents>0&&ruleUid.isNotBlank()&&ruleVersion>0)}
    fun segment(campaignUid:String,generation:HistoryGenerationUid,events:List<MemoryEventLeaf>):List<EpisodeManifest>{
        if(events.isEmpty())return emptyList()
        val ordered=events.sortedWith(compareBy<MemoryEventLeaf>{it.committedOrder}.thenBy{it.eventOrdinal}.thenBy{it.eventUid})
        require(ordered.map{it.eventUid}.distinct().size==ordered.size)
        val groups=mutableListOf<MutableList<MemoryEventLeaf>>();var current=mutableListOf<MemoryEventLeaf>();var turns=linkedSetOf<String>()
        ordered.forEach{event->
            val boundary=current.isNotEmpty()&&(event.explicitBoundaryBefore||current.size>=maximumEvents||(event.turnUid !in turns&&turns.size>=maximumTurns))
            if(boundary){groups+=current;current=mutableListOf();turns=linkedSetOf()}
            current+=event;turns+=event.turnUid
        }
        if(current.isNotEmpty())groups+=current
        return groups.map{group->
            val leaves=group.map{MemorySourceLeafRef("EVENT",it.eventUid,1,it.committedOrder,it.fingerprint)}
            val logical="EPISODE:$campaignUid:$ruleUid:${group.first().eventUid}"
            val revision="EPREV:${memorySha256("$logical|${generation.value}|${memoryLeafFingerprint(leaves)}|$ruleVersion")}"
            val identity=MemoryArtifactIdentity(campaignUid,generation,logical,revision,MemoryArtifactKind.EPISODE_MANIFEST,leaves,memoryLeafFingerprint(leaves),ruleUid,ruleVersion,group.maxOf{it.committedOrder},group.minOf{it.committedOrder},group.maxOf{it.committedOrder})
            EpisodeManifest(identity,group.map{it.eventUid},group.minOf{it.committedOrder},group.maxOf{it.committedOrder},
                group.flatMap{it.participantRefs}.distinctBy{it.kindUid to it.uid},group.flatMap{it.locationRefs}.distinctBy{it.kindUid to it.uid},ruleUid,ruleVersion)
        }
    }
}

internal fun episodeManifestJson(manifest:EpisodeManifest)=JSONObject().apply{
    put("episode_uid",manifest.identity.logicalArtifactUid);put("revision_uid",manifest.identity.artifactRevisionUid)
    put("event_uids",JSONArray(manifest.eventUids));put("start_order",manifest.startOrder);put("end_order",manifest.endOrder)
    put("participants",JSONArray(manifest.participantRefs.map{"${it.kindUid}:${it.uid}"}));put("locations",JSONArray(manifest.locationRefs.map{"${it.kindUid}:${it.uid}"}))
    put("segmentation_rule_uid",manifest.segmentationRuleUid);put("segmentation_version",manifest.segmentationVersion)
}.toString()

internal fun memoryLeafFingerprint(leaves:List<MemorySourceLeafRef>)=memorySha256(leaves.sortedWith(compareBy<MemorySourceLeafRef>{it.committedOrder}.thenBy{it.sourceKind}.thenBy{it.sourceUid})
    .joinToString("\u001f"){"${it.sourceKind}|${it.sourceUid}|${it.sourceVersion}|${it.committedOrder}|${it.fingerprint}"})
internal fun memorySha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
