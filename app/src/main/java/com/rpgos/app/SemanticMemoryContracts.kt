package com.rpgos.app

import java.security.MessageDigest
import kotlin.math.sqrt

const val BEKKO_MODEL_UID="BEKKO-EMBEDDING-V1-A8M"
const val BEKKO_VARIANT_UID="GGUF-Q8_0"
const val BEKKO_SOURCE_REVISION="4acacdea60b9393ae247a6ebf5b5707938c9755b"
const val BEKKO_MODEL_BYTES=118_639_520L
const val BEKKO_MODEL_SHA256="0b0214214287e90ffb98ffb47e1071e5b193fb420d1d99b2ec8aab0ebb507fc0"
const val BEKKO_MODEL_FILE="bekko-embedding-v1-a8m-Q8_0.gguf"
const val BEKKO_UPSTREAM_URL="https://huggingface.co/hotchpotch/bekko-embedding-v1-a8m-GGUF/resolve/$BEKKO_SOURCE_REVISION/$BEKKO_MODEL_FILE"
const val BEKKO_RELEASE_URL="https://github.com/piotreksmaga-art/rpg-os-android/releases/download/models-bekko-a8m-v1/$BEKKO_MODEL_FILE"

enum class EmbeddingBackend { CPU, VULKAN }
enum class EmbeddingAvailabilityState { READY, NOT_INSTALLED, UNAVAILABLE, DEGRADED }

data class EmbeddingCapabilities(
    val providerUid:String,
    val modelUid:String,
    val modelRevision:String,
    val sourceDimensions:Int,
    val supportedDimensions:Set<Int>,
    val maximumContextUnits:Int,
    val maximumBatchSize:Int,
    val supportedBackends:Set<EmbeddingBackend>
){init{
    require(listOf(providerUid,modelUid,modelRevision).none{it.isBlank()})
    require(sourceDimensions>0&&sourceDimensions in supportedDimensions&&maximumContextUnits>0&&maximumBatchSize>0)
}}

data class EmbeddingAvailability(val state:EmbeddingAvailabilityState,val reasonUid:String){init{require(reasonUid.isNotBlank())}}
data class EmbeddingRequest(
    val requestUid:String,val texts:List<String>,val maximumInputUnits:Int=8192
){init{
    require(requestUid.isNotBlank()&&texts.isNotEmpty()&&texts.size<=64)
    require(texts.none{it.isBlank()}&&maximumInputUnits in 1..8192)
}}

sealed interface EmbeddingBatchResult{
    data class Success(val vectors:List<FloatArray>,val traceUid:String):EmbeddingBatchResult{
        init{require(vectors.isNotEmpty()&&traceUid.isNotBlank());require(vectors.map{it.size}.distinct().size==1)}
    }
    data class Failure(val reasonUid:String,val retryable:Boolean):EmbeddingBatchResult{init{require(reasonUid.isNotBlank())}}
}

interface EmbeddingProviderPort:AutoCloseable{
    val capabilities:EmbeddingCapabilities
    fun availability():EmbeddingAvailability
    /** Opens and validates the isolated embedding runtime without generating text. */
    fun open():EmbeddingAvailability
    fun embedBatch(request:EmbeddingRequest):EmbeddingBatchResult
    fun cancel(requestUid:String)
    override fun close()
}

data class SemanticIndexVersion(
    val modelUid:String=BEKKO_MODEL_UID,
    val modelRevision:String=BEKKO_SOURCE_REVISION,
    val modelSha256:String=BEKKO_MODEL_SHA256,
    val dimensions:Int=256,
    val normalizationUid:String="L2_AFTER_MATRYOSHKA_TRUNCATION",
    val vectorFormatUid:String="FP16_LE",
    val projectorVersion:Int=2
){init{
    require(listOf(modelUid,modelRevision,modelSha256,normalizationUid,vectorFormatUid).none{it.isBlank()})
    require(dimensions in setOf(64,128,256,384)&&projectorVersion>0)
}}

data class SemanticDocumentProjection(
    val campaignUid:String,
    val namespaceUid:String,
    val audienceUid:String,
    val purposeUid:String,
    val canonicalRecordUid:String,
    val recordKindUid:String,
    val epistemicStateUid:String,
    val asOfOrder:Long,
    val sourceVersion:Long,
    val sourceFingerprint:String,
    val chunkOrdinal:Int,
    val text:String
){init{
    require(listOf(campaignUid,namespaceUid,audienceUid,purposeUid,canonicalRecordUid,recordKindUid,epistemicStateUid,sourceFingerprint,text).none{it.isBlank()})
    require(asOfOrder>=0&&sourceVersion>=0&&chunkOrdinal>=0&&text.length<=16_384)
}}

fun interface SemanticDocumentProjector{
    fun project(source:Any,audience:AudienceContext,purpose:PurposeContext):List<SemanticDocumentProjection>
}

data class SemanticIndexedDocument(val projection:SemanticDocumentProjection,val vector:FloatArray)

data class SemanticSearchRequest(
    val campaignUid:String,
    val namespaceUid:String,
    val audienceUid:String,
    val purposeUid:String,
    val asOfOrder:Long,
    val authorizedRecordUids:Set<String>,
    val allowedRecordKinds:Set<String> = emptySet(),
    val queryVector:FloatArray,
    val topK:Int=20,
    val minimumScore:Float=0.25f
){init{
    require(listOf(campaignUid,namespaceUid,audienceUid,purposeUid).none{it.isBlank()})
    require(asOfOrder>=0&&authorizedRecordUids.isNotEmpty()&&authorizedRecordUids.none{it.isBlank()})
    require(queryVector.isNotEmpty()&&topK in 1..200&&minimumScore.isFinite()&&minimumScore in -1f..1f)
}}

data class SemanticCandidate(
    val canonicalRecordUid:String,
    val score:Float,
    val recordKindUid:String,
    val epistemicStateUid:String,
    val sourceFingerprint:String,
    val sourceVersion:Long,
    val chunkEvidence:List<SemanticChunkEvidence>,
    val indexVersion:SemanticIndexVersion
){init{
    require(listOf(canonicalRecordUid,recordKindUid,epistemicStateUid,sourceFingerprint).none{it.isBlank()})
    require(score.isFinite()&&sourceVersion>=0&&chunkEvidence.isNotEmpty())
}}

data class SemanticChunkEvidence(
    val chunkOrdinal:Int,
    val projectedText:String,
    val projectedTextFingerprint:String
){init{
    require(chunkOrdinal>=0&&projectedText.isNotBlank()&&projectedText.length<=2_048)
    require(projectedTextFingerprint.isNotBlank())
}}

data class SemanticIndexStatus(
    val ready:Boolean,
    val recordCount:Long,
    val chunkCount:Long,
    val lastIndexedCommitOrder:Long,
    val version:SemanticIndexVersion,
    val reasonUid:String?=null
){init{require(recordCount>=0&&chunkCount>=0&&lastIndexedCommitOrder>=0&&reasonUid?.isBlank()!=true)}}

data class SemanticIndexProgress(
    val active:Boolean=false,
    val stageUid:String="IDLE",
    val processedCommits:Int=0,
    val totalCommits:Int=0,
    val lastIndexedCommitOrder:Long=0,
    val reasonUid:String?=null
){init{
    require(stageUid.isNotBlank()&&processedCommits>=0&&totalCommits>=0&&processedCommits<=totalCommits)
    require(lastIndexedCommitOrder>=0&&reasonUid?.isBlank()!=true)
}}

interface SemanticIndexPort:AutoCloseable{
    val version:SemanticIndexVersion
    fun upsertBatch(documents:List<SemanticIndexedDocument>)
    fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String)
    fun authorizedRecordUids(campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long):Set<String>
    fun searchAuthorized(request:SemanticSearchRequest):List<SemanticCandidate>
    fun checkpoint(campaignUid:String):Long
    fun advanceCheckpoint(campaignUid:String,committedOrder:Long)
    fun status(campaignUid:String):SemanticIndexStatus
    fun clear(campaignUid:String)
    override fun close()
}

/** Candidate-only ports. Their future owners validate and materialize; none can mutate canonical state. */
fun interface GmSemanticMemoryPort{fun search(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface DirectorSemanticScoutPort{fun search(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface MemoryConsolidationCandidatePort{fun candidates(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface NpcSemanticMemoryPort{fun memories(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface LivingWorldSemanticRelationPort{fun related(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface PromiseSemanticMatcherPort{fun matches(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface AntiRepetitionSemanticPort{fun similarNarratives(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface SemanticAliasCandidatePort{fun aliases(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface SemanticContradictionCandidatePort{fun possibleContradictions(request:SemanticSearchRequest):List<SemanticCandidate>}
fun interface SemanticCausalCandidatePort{fun possibleRelations(request:SemanticSearchRequest):List<SemanticCandidate>}

/**
 * One read-only adapter set for future phase owners. The ports expose ranked candidates only;
 * they cannot create facts, aliases, causal edges, consolidations or world mutations.
 */
data class SemanticFutureCandidatePorts(
    val memoryConsolidation:MemoryConsolidationCandidatePort,
    val npcMemory:NpcSemanticMemoryPort,
    val livingWorld:LivingWorldSemanticRelationPort,
    val promises:PromiseSemanticMatcherPort,
    val antiRepetition:AntiRepetitionSemanticPort,
    val aliases:SemanticAliasCandidatePort,
    val contradictions:SemanticContradictionCandidatePort,
    val causalRelations:SemanticCausalCandidatePort
){
    companion object{
        fun candidateOnly(search:(SemanticSearchRequest)->List<SemanticCandidate>)=SemanticFutureCandidatePorts(
            MemoryConsolidationCandidatePort(search),NpcSemanticMemoryPort(search),
            LivingWorldSemanticRelationPort(search),PromiseSemanticMatcherPort(search),
            AntiRepetitionSemanticPort(search),SemanticAliasCandidatePort(search),
            SemanticContradictionCandidatePort(search),SemanticCausalCandidatePort(search)
        )
    }
}

/** Deterministic production projection: truncate first, then normalize the retained vector. */
internal fun matryoshkaL2(source:FloatArray,dimensions:Int):FloatArray{
    require(dimensions in setOf(64,128,256,384)&&source.size>=dimensions)
    var squared=0.0
    for(index in 0 until dimensions){val value=source[index];require(value.isFinite());squared+=value.toDouble()*value}
    require(squared>0.0){"BEKKO_ZERO_VECTOR"}
    val scale=(1.0/sqrt(squared)).toFloat()
    return FloatArray(dimensions){source[it]*scale}
}

internal fun semanticSha256(value:String):String=MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
