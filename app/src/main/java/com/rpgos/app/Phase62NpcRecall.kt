package com.rpgos.app

/** Phase59 sees an already authorized projection, not a request for a global NPC/world dump. */
internal data class NpcRecallRequest(val scope:NpcDecisionScope,val holder:KnowledgeHolderRef,
                                   val query:String,val records:List<NpcKnownRecord>) {
    init {
        require(holder.campaignUid==scope.temporal.campaignUid && holder.holderUid==scope.actor.uid)
        require(query.isNotBlank() && query.length<=1024 && records.size<=64 && records.map{it.uid}.distinct().size==records.size)
    }
    val fingerprint:String get()=phase60Hash("${scope.temporal}|${scope.actor}|$holder|$query|"+
        records.sortedBy{it.uid}.joinToString("|"){"${it.uid}:${npcRecallFingerprint(it)}"})
}
internal fun npcRecallFingerprint(record:NpcKnownRecord)=phase60Hash(
    "${record.uid}|${record.epistemicState}|${record.acquisitionUid}|${record.sourceVersion}|${record.sourceCommittedOrder}|${record.projectedText}|"+
        record.subjectRefs.sortedWith(compareBy<DomainRef>{it.kindUid}.thenBy{it.uid})+"|${record.memoryKind}")
internal data class NpcRecallHit(val uid:String,val score:SemanticSimilarityScore,val sourceFingerprint:String)
internal sealed interface NpcRecallResult {
    data class Ranked(val requestFingerprint:String,val hits:List<NpcRecallHit>):NpcRecallResult
    data class Fallback(val reasonUid:String):NpcRecallResult
}
internal fun interface NpcRecallPort {
    fun rank(request:NpcRecallRequest):NpcRecallResult
    companion object { val NONE=NpcRecallPort{NpcRecallResult.Fallback("P62:STRUCTURED_RECALL")} }
}

/** Reuses the rebuildable sidecar. Cache text is NEVER returned to the decision model. The caller
 * re-reads Phase37/38 after embedding and maps these UID/score pairs back to current owner records. */
internal class BekkoNpcRecall(private val embeddings:EmbeddingProviderPort,private val index:SemanticIndexPort,
                              private val current:()->TemporalScope,
                              private val embeddingStarted:(String)->Unit={},private val embeddingFinished:(String)->Unit={}) {
    private fun embed(request:EmbeddingRequest):EmbeddingBatchResult {
        embeddingStarted(request.requestUid)
        return try{embeddings.embedBatch(request)}finally{embeddingFinished(request.requestUid)}
    }
    private val namespace="NPC_KNOWLEDGE"
    private fun documents(request:NpcRecallRequest)=request.records.map { record ->
        SemanticDocumentProjection(request.scope.temporal.campaignUid,namespace,AudienceKinds.WORLD_ACTOR,
            VisibilityPurposeKinds.WORLD_ACTOR_REASONING,record.uid,"KNOWLEDGE_STATE",
            if(record.memoryKind==NpcMemoryRecordKind.HISTORICAL_ACQUISITION)"MEMORY" else record.epistemicState.name,
            request.scope.temporal.baseCommitOrder,record.sourceVersion,npcRecallFingerprint(record),0,record.projectedText,
            HistoryGenerationUid(request.scope.temporal.historyGenerationUid),request.scope.actor.uid,
            holderFingerprint(request),1,request.scope.activePlayerUid)
    }
    private fun holderFingerprint(request:NpcRecallRequest)=phase60Hash("P62:RECALL:1|${request.holder}|${request.scope.actor.kindUid}")
    private fun search(request:NpcRecallRequest,vector:FloatArray)=SemanticSearchRequest(
        request.scope.temporal.campaignUid,namespace,AudienceKinds.WORLD_ACTOR,VisibilityPurposeKinds.WORLD_ACTOR_REASONING,
        request.scope.temporal.baseCommitOrder,request.records.mapTo(linkedSetOf()){it.uid},setOf("KNOWLEDGE_STATE"),vector,
        topK=request.records.size.coerceAtLeast(1),minimumScore=-1f,
        historyGenerationUid=HistoryGenerationUid(request.scope.temporal.historyGenerationUid),principalUid=request.scope.actor.uid,
        holderSetFingerprint=holderFingerprint(request),accessPolicyVersion=1,activePlayerUid=request.scope.activePlayerUid)
    private fun currentDocuments(request:NpcRecallRequest):Map<String,SemanticSourceProjectionState> =
        index.currentProjections(search(request,FloatArray(index.version.dimensions).also{it[0]=1f}))
    private fun matches(document:SemanticDocumentProjection,state:SemanticSourceProjectionState?)=state!=null &&
        state.sourceFingerprint==document.sourceFingerprint && state.sourceVersion==document.sourceVersion &&
        state.historyGenerationUid==document.historyGenerationUid && state.sourceAsOfOrder<=document.asOfOrder

    /** Called on the semantic worker, never under a database transaction or inside the model turn. */
    fun prepare(request:NpcRecallRequest):String? {
        if(request.records.isEmpty())return null
        if(current()!=request.scope.temporal)return "P62:STALE_RECALL"
        val existing=currentDocuments(request)
        val missing=documents(request).filterNot{matches(it,existing[it.canonicalRecordUid])}
        for(batch in missing.chunked(embeddings.capabilities.maximumBatchSize.coerceIn(1,64))) {
            if(current()!=request.scope.temporal)return "P62:STALE_RECALL"
            val embedded=embed(EmbeddingRequest("P62:INDEX:${request.fingerprint}:${batch.first().canonicalRecordUid}",batch.map{it.text},512))
            if(embedded !is EmbeddingBatchResult.Success)return "P62:RECALL_EMBEDDING_FAILED"
            if(embedded.vectors.size!=batch.size)return "P62:RECALL_EMBEDDING_CARDINALITY"
            if(current()!=request.scope.temporal)return "P62:STALE_RECALL"
            index.upsertBatch(batch.zip(embedded.vectors).map{(document,vector)->SemanticIndexedDocument(document,matryoshkaL2(vector,index.version.dimensions))})
        }
        return null
    }
    fun rank(request:NpcRecallRequest):NpcRecallResult {
        fun fallback(reason:String)=NpcRecallResult.Fallback("P62:$reason")
        if(request.records.isEmpty())return fallback("EMPTY_RECALL")
        if(current()!=request.scope.temporal)return fallback("STALE_RECALL")
        val existing=currentDocuments(request)
        if(documents(request).any{!matches(it,existing[it.canonicalRecordUid])})return fallback("RECALL_INDEX_NOT_READY")
        val encoded=embed(EmbeddingRequest("P62:QUERY:${request.fingerprint}",listOf(request.query),256))
        if(encoded !is EmbeddingBatchResult.Success || encoded.vectors.size!=1)return fallback("RECALL_QUERY_FAILED")
        if(current()!=request.scope.temporal)return fallback("STALE_RECALL")
        val records=request.records.associateBy{it.uid}
        val candidates=index.searchAuthorized(search(request,matryoshkaL2(encoded.vectors.single(),index.version.dimensions)))
        if(current()!=request.scope.temporal)return fallback("STALE_RECALL")
        val hits=candidates.mapNotNull { candidate ->
            val owner=records[candidate.canonicalRecordUid]?:return@mapNotNull null
            if(candidate.sourceVersion!=owner.sourceVersion || candidate.sourceFingerprint!=npcRecallFingerprint(owner))return@mapNotNull null
            NpcRecallHit(owner.uid,candidate.score,candidate.sourceFingerprint)
        }.sortedWith(compareByDescending<NpcRecallHit>{it.score.value}.thenBy{it.uid})
        return NpcRecallResult.Ranked(request.fingerprint,hits)
    }
}
