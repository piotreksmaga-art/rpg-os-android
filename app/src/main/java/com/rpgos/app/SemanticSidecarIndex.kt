package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.PriorityQueue
import java.util.UUID

/**
 * Per-campaign rebuildable sidecar. The metadata DB and vector file never participate in
 * canonical saves, snapshots, receipts, or truth hashes.
 */
class FileSemanticIndex(
    root:File,
    override val version:SemanticIndexVersion=SemanticIndexVersion()
):SemanticIndexPort{
    private val directory=root.apply{mkdirs()}
    private val directoryKey=directory.canonicalPath
    private val metadataFile=File(directory,"semantic-index.sqlite")
    private val vectorsFile=File(directory,"semantic-vectors.fp16")
    private val db=SQLiteDatabase.openOrCreateDatabase(metadataFile,null)
    private lateinit var vectors:RandomAccessFile
    private companion object{
        /** Multiple lab/UI repository compositions may point at the same sidecar. SQLite is
         * process-safe, the companion fp16 file is not; serialize every vector lifecycle op. */
        val PROCESS_VECTOR_IO_LOCK=Any()
        val OPEN_INSTANCE_COUNTS=mutableMapOf<String,Int>()
        const val DEFAULT_HISTORY_GENERATION_UID="GLOBAL"
        const val DEFAULT_PRINCIPAL_UID="GLOBAL"
        const val DEFAULT_HOLDER_FINGERPRINT="GLOBAL"
        const val DEFAULT_ACTIVE_PLAYER_UID=""
        const val COMPACTION_MARKER="VECTOR_COMPACTION_IN_PROGRESS"
        const val COMPACTION_MIN_ORPHAN_BYTES=32L*1024L*1024L
    }
    private data class Scope(
        val campaign:String,val namespace:String,val audience:String,val purpose:String,val asOfOrder:Long,
        val historyGenerationUid:String?,val principalUid:String,val holderSetFingerprint:String,
        val accessPolicyVersion:Long,val activePlayerUid:String?,val projectionVersionUid:String
    )
    private data class Row(
        val uid:String,val kind:String,val epistemic:String,val sourceFingerprint:String,val sourceVersion:Long,
        val sourceAsOfOrder:Long,val historyGenerationUid:String,val principalUid:String,val holderSetFingerprint:String,
        val accessPolicyVersion:Long,val activePlayerUid:String,val projectionVersionUid:String,
        val chunk:Int,val offset:Long,val projectedText:String
    )
    private var mappedVectors:ByteBuffer?=null
    private var mappedLength=-1L
    private var closed=false
    private val versionFingerprint=semanticSha256(listOf(
        version.modelUid,version.modelRevision,version.modelSha256,version.dimensions,
        version.normalizationUid,version.vectorFormatUid,version.projectorVersion
    ).joinToString("|"))

    init{synchronized(PROCESS_VECTOR_IO_LOCK){
        var registered=false
        try{
            // Opening the handle and publishing it in the instance count are one lock-protected
            // lifecycle transition. A compactor can therefore never choose atomic replacement
            // while an uncounted handle to the file is being constructed.
            vectors=RandomAccessFile(vectorsFile,"rw")
            OPEN_INSTANCE_COUNTS[directoryKey]=(OPEN_INSTANCE_COUNTS[directoryKey]?:0)+1
            registered=true
            db.rawQuery("PRAGMA journal_mode=WAL",null).use{it.moveToFirst()}
            db.rawQuery("PRAGMA synchronous=NORMAL",null).use{it.moveToFirst()}
            createSemanticEntriesTable()
            db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_index_meta(meta_key TEXT PRIMARY KEY,meta_value TEXT NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_checkpoints(
                campaign_uid TEXT PRIMARY KEY, commit_order INTEGER NOT NULL)""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_reconciliation_state(
                campaign_uid TEXT NOT NULL,namespace_uid TEXT NOT NULL,audience_uid TEXT NOT NULL,purpose_uid TEXT NOT NULL,
                record_uid_prefix TEXT NOT NULL,session_uid TEXT NOT NULL,
                PRIMARY KEY(campaign_uid,namespace_uid,audience_uid,purpose_uid,record_uid_prefix))""")
            db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_reconciliation_live(
                session_uid TEXT NOT NULL,record_uid TEXT NOT NULL,PRIMARY KEY(session_uid,record_uid))""")
            ensureScopeColumns()
            ensureProjectedTextColumn()
            ensureIsolationPrimaryKey()
            db.execSQL("""CREATE INDEX IF NOT EXISTS idx_semantic_scope ON semantic_entries(
                campaign_uid,namespace_uid,audience_uid,purpose_uid,history_generation_uid,principal_uid,as_of_order)""")
            recoverIncompleteCompaction()
            recoverUncommittedVectorTail()
            val stored=meta("INDEX_VERSION")
            if(stored!=null&&stored!=versionFingerprint)clearAll()
            putMeta("INDEX_VERSION",versionFingerprint)
        }catch(failure:Throwable){
            if(::vectors.isInitialized)runCatching{vectors.close()}
            if(registered){
                val remaining=(OPEN_INSTANCE_COUNTS[directoryKey]?:1)-1
                if(remaining<=0)OPEN_INSTANCE_COUNTS.remove(directoryKey) else OPEN_INSTANCE_COUNTS[directoryKey]=remaining
            }
            runCatching{db.close()}
            throw failure
        }
    }}

    @Synchronized override fun upsertBatch(documents:List<SemanticIndexedDocument>)=writeBatch(documents,false)

    @Synchronized override fun replaceRecord(documents:List<SemanticIndexedDocument>){
        if(documents.isEmpty())return
        val first=documents.first().projection
        require(documents.all{sameRecordRevision(first,it.projection)}){"SEMANTIC_REPLACE_MIXED_RECORD_REVISIONS"}
        writeBatch(documents,true)
    }

    private fun writeBatch(documents:List<SemanticIndexedDocument>,replaceWholeRecord:Boolean)=
        synchronized(PROCESS_VECTOR_IO_LOCK){writeBatchLocked(documents,replaceWholeRecord)}

    private fun writeBatchLocked(documents:List<SemanticIndexedDocument>,replaceWholeRecord:Boolean){
        if(documents.isEmpty())return
        documents.forEach{document->
            require(document.vector.size==version.dimensions){"SEMANTIC_VECTOR_DIMENSION_MISMATCH"}
            require(document.vector.all{it.isFinite()}){"SEMANTIC_VECTOR_NON_FINITE"}
        }
        val requestedOrdinals=documents.mapTo(linkedSetOf()){it.projection.chunkOrdinal}
        val alreadyExact=replaceWholeRecord&&documents.all{identicalEntry(it.projection)}&&
            existingOrdinals(documents.first().projection)==requestedOrdinals
        if(alreadyExact)return
        val pending=if(replaceWholeRecord)documents else documents.filterNot{identicalEntry(it.projection)}
        if(pending.isEmpty())return
        val replacesExisting=pending.any{entryExists(it.projection)}
        val vectorStart=vectors.length()
        val vectorBytes=ByteBuffer.allocate(pending.size*version.dimensions*2).order(ByteOrder.LITTLE_ENDIAN)
        val rows=pending.map { document ->
            val offset=vectorStart+vectorBytes.position()
            document.vector.forEach { value-> vectorBytes.putShort(floatToHalf(value)) }
            Triple(document,offset,existingRetirement(document.projection))
        }
        vectors.seek(vectorStart);vectors.write(vectorBytes.array())
        vectors.fd.sync()
        db.beginTransaction()
        try{
            if(replaceWholeRecord)deleteRecordRevision(documents.first().projection)
            db.compileStatement("""INSERT OR REPLACE INTO semantic_entries(
                campaign_uid,namespace_uid,audience_uid,purpose_uid,record_uid,record_kind_uid,epistemic_state_uid,
                as_of_order,source_version,source_fingerprint,history_generation_uid,principal_uid,holder_set_fingerprint,
                access_policy_version,active_player_uid,projection_version_uid,chunk_ordinal,vector_offset,projected_text,retired_at_order
            ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""").use{statement->
                rows.forEach{(document,offset,retiredAtOrder)->
                    val projection=document.projection
                    val historyUid=projection.historyGenerationUid?.value ?: DEFAULT_HISTORY_GENERATION_UID
                    val principalUid=projection.principalUid.takeIf{it.isNotBlank()} ?: DEFAULT_PRINCIPAL_UID
                    val holderFingerprint=projection.holderSetFingerprint.takeIf{it.isNotBlank()} ?: DEFAULT_HOLDER_FINGERPRINT
                    statement.clearBindings()
                    statement.bindString(1,projection.campaignUid)
                    statement.bindString(2,projection.namespaceUid)
                    statement.bindString(3,projection.audienceUid)
                    statement.bindString(4,projection.purposeUid)
                    statement.bindString(5,projection.canonicalRecordUid)
                    statement.bindString(6,projection.recordKindUid)
                    statement.bindString(7,projection.epistemicStateUid)
                    statement.bindLong(8,projection.asOfOrder)
                    statement.bindLong(9,projection.sourceVersion)
                    statement.bindString(10,projection.sourceFingerprint)
                    statement.bindString(11,historyUid)
                    statement.bindString(12,principalUid)
                    statement.bindString(13,holderFingerprint)
                    statement.bindLong(14,projection.accessPolicyVersion)
                    statement.bindString(15,projection.activePlayerUid.orEmpty())
                    statement.bindString(16,projection.projectionVersionUid)
                    statement.bindLong(17,projection.chunkOrdinal.toLong())
                    statement.bindLong(18,offset)
                    statement.bindString(19,projection.text)
                    if(retiredAtOrder==null)statement.bindNull(20) else statement.bindLong(20,retiredAtOrder)
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        releaseMappedVectors()
        if(replacesExisting)maybeCompactVectors()
    }

    @Synchronized override fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String){
        synchronized(PROCESS_VECTOR_IO_LOCK){
        require(listOf(campaignUid,namespaceUid,canonicalRecordUid).none{it.isBlank()})
        val deleted=db.delete("semantic_entries","campaign_uid=? AND namespace_uid=? AND record_uid=?",arrayOf(campaignUid,namespaceUid,canonicalRecordUid))
        if(deleted>0)maybeCompactVectors()
        }
    }

    @Synchronized override fun removeProjection(
        campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,canonicalRecordUid:String
    ){
        synchronized(PROCESS_VECTOR_IO_LOCK){
        require(listOf(campaignUid,namespaceUid,audienceUid,purposeUid,canonicalRecordUid).none{it.isBlank()})
        val deleted=db.delete("semantic_entries","campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=? AND record_uid=?",
            arrayOf(campaignUid,namespaceUid,audienceUid,purposeUid,canonicalRecordUid))
        if(deleted>0)maybeCompactVectors()
        }
    }

    @Synchronized override fun retireProjection(
        campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,
        canonicalRecordUid:String,retiredAtOrder:Long
    ){
        synchronized(PROCESS_VECTOR_IO_LOCK){
        require(listOf(campaignUid,namespaceUid,audienceUid,purposeUid,canonicalRecordUid).none{it.isBlank()}&&retiredAtOrder>=0)
        db.execSQL("""UPDATE semantic_entries SET retired_at_order=CASE
            WHEN retired_at_order IS NULL OR retired_at_order>? THEN ? ELSE retired_at_order END
            WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=? AND record_uid=? AND as_of_order<?""",
            arrayOf<Any?>(retiredAtOrder,retiredAtOrder,campaignUid,namespaceUid,audienceUid,purposeUid,canonicalRecordUid,retiredAtOrder))
        }
    }

    @Synchronized override fun authorizedRecordUids(
        campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long
    ):Set<String>{
        require(asOfOrder>=0)
        val scope=Scope(campaignUid,namespaceUid,audienceUid,purposeUid,asOfOrder,null,"","",0L,null,VisibilityAuthorityService.PROJECTION_VERSION_UID)
        val (where,args)=scopeWhere(scope)
        return db.rawQuery("SELECT DISTINCT record_uid FROM semantic_entries WHERE $where",args).use{cursor->
            buildSet{while(cursor.moveToNext())add(cursor.getString(0))}
        }
    }

    @Synchronized override fun searchAuthorized(request:SemanticSearchRequest):List<SemanticCandidate> =
        synchronized(PROCESS_VECTOR_IO_LOCK){try{searchAuthorizedLocked(request)}finally{releaseMappedVectors()}}

    private fun searchAuthorizedLocked(request:SemanticSearchRequest):List<SemanticCandidate>{
        require(request.topK in 1..200){"SEMANTIC_TOP_K_OUT_OF_RANGE"}
        require(request.queryVector.size==version.dimensions){"SEMANTIC_QUERY_DIMENSION_MISMATCH"}
        val query=matryoshkaL2(request.queryVector,version.dimensions)
        if(vectors.length()==0L)return emptyList()
        val scope=scope(request)
        val (where,args)=scopeWhere(scope,request.authorizedRecordUids)
        val allowed=request.authorizedRecordUids
        data class ScoredChunk(val row:Row,val score:Float)
        data class CandidateSeed(val best:ScoredChunk,val evidence:List<ScoredChunk>)
        val worstFirst=Comparator<CandidateSeed>{left,right->
            val score=left.best.score.compareTo(right.best.score)
            if(score!=0)score else right.best.row.uid.compareTo(left.best.row.uid)
        }
        val top=PriorityQueue(request.topK,worstFirst)
        val group=mutableListOf<ScoredChunk>()
        fun emit(){
            if(group.isEmpty())return
            val ordered=group.sortedWith(compareByDescending<ScoredChunk>{it.score}.thenBy{it.row.chunk})
            val seed=CandidateSeed(ordered.first(),ordered.take(4).distinctBy{it.row.chunk})
            if(top.size<request.topK)top+=seed
            else if(worstFirst.compare(seed,top.peek())>0){top.poll();top+=seed}
            group.clear()
        }
        var activeUid:String?=null
        val pendingRows=ArrayList<Row>(4_096)
        fun scorePending(){
            if(pendingRows.isEmpty())return
            val scores=scoreFp16(mappedVectors(),pendingRows.map{it.offset}.toLongArray(),query)
            pendingRows.forEachIndexed{index,row->
                if(activeUid!=null&&activeUid!=row.uid)emit()
                activeUid=row.uid
                val score=scores[index]
                if(score>=request.minimumScore)group+=ScoredChunk(row,score)
            }
            pendingRows.clear()
        }
        db.rawQuery("""SELECT record_uid,record_kind_uid,epistemic_state_uid,source_fingerprint,
            source_version,as_of_order,history_generation_uid,principal_uid,holder_set_fingerprint,
            access_policy_version,active_player_uid,projection_version_uid,chunk_ordinal,vector_offset,projected_text
            FROM semantic_entries WHERE $where
            ORDER BY record_uid,as_of_order DESC,source_version DESC,chunk_ordinal""",args).use{cursor->
            var versionUid:String?=null;var versionAsOf=-1L;var versionNumber=-1L
            while(cursor.moveToNext()){
                val row=decodeRow(cursor)
                if(row.uid!=versionUid){versionUid=row.uid;versionAsOf=row.sourceAsOfOrder;versionNumber=row.sourceVersion}
                if(row.sourceAsOfOrder!=versionAsOf||row.sourceVersion!=versionNumber)continue
                if(allowed.isNotEmpty()&&row.uid !in allowed)continue
                if(request.allowedRecordKinds.isNotEmpty()&&row.kind !in request.allowedRecordKinds)continue
                pendingRows+=row
                if(pendingRows.size>=4_096)scorePending()
            }
        }
        scorePending()
        emit()
        return top.toList().sortedWith(compareByDescending<CandidateSeed>{it.best.score}.thenBy{it.best.row.uid}).map{seed->
            val best=seed.best
            SemanticCandidate(
                best.row.uid,SemanticSimilarityScore(best.score),best.row.kind,best.row.epistemic,best.row.sourceFingerprint,
                best.row.sourceVersion,seed.evidence.map{
                    SemanticChunkEvidence(it.row.chunk, it.row.projectedText, semanticSha256(it.row.projectedText))
                },version,best.row.sourceAsOfOrder
            )
        }
    }

    @Synchronized override fun currentProjections(request:SemanticSearchRequest):Map<String, SemanticSourceProjectionState>{
        val scope=scope(request);val (where,args)=scopeWhere(scope,request.authorizedRecordUids)
        val allowed=request.authorizedRecordUids
        return db.rawQuery("""SELECT record_uid,record_kind_uid,epistemic_state_uid,source_fingerprint,
            source_version,as_of_order,history_generation_uid,principal_uid,holder_set_fingerprint,
            access_policy_version,active_player_uid,projection_version_uid,chunk_ordinal,vector_offset,projected_text
            FROM semantic_entries WHERE $where
            ORDER BY record_uid,as_of_order DESC,source_version DESC,chunk_ordinal""",args).use{cursor->
            buildMap{
                while(cursor.moveToNext()){
                    val row=decodeRow(cursor)
                    if((allowed.isNotEmpty()&&row.uid !in allowed)||containsKey(row.uid))continue
                    put(row.uid,SemanticSourceProjectionState(
                        sourceAsOfOrder=row.sourceAsOfOrder,sourceVersion=row.sourceVersion,
                        sourceFingerprint=row.sourceFingerprint,principalUid=row.principalUid,
                        holderSetFingerprint=row.holderSetFingerprint,
                        historyGenerationUid=HistoryGenerationUid(row.historyGenerationUid),
                        accessPolicyVersion=row.accessPolicyVersion,activePlayerUid=row.activePlayerUid.ifBlank{null},
                        projectionVersionUid=row.projectionVersionUid
                    ))
                }
            }
        }
    }

    @Synchronized override fun checkpoint(campaignUid:String):Long=db.rawQuery(
        "SELECT commit_order FROM semantic_checkpoints WHERE campaign_uid=?",arrayOf(campaignUid)
    ).use{if(it.moveToFirst())it.getLong(0) else 0L}

    @Synchronized override fun bindCheckpointScope(campaignUid:String,scopeFingerprint:String):Boolean{
        synchronized(PROCESS_VECTOR_IO_LOCK){
        require(campaignUid.isNotBlank()&&scopeFingerprint.isNotBlank())
        val key="CHECKPOINT_SCOPE:${semanticSha256(campaignUid)}"
        if(meta(key)==scopeFingerprint)return false
        db.beginTransaction()
        try{
            db.delete("semantic_entries","campaign_uid=?",arrayOf(campaignUid))
            db.delete("semantic_checkpoints","campaign_uid=?",arrayOf(campaignUid))
            val sessions=db.rawQuery("SELECT session_uid FROM semantic_reconciliation_state WHERE campaign_uid=?",arrayOf(campaignUid)).use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
            sessions.forEach{db.delete("semantic_reconciliation_live","session_uid=?",arrayOf(it))}
            db.delete("semantic_reconciliation_state","campaign_uid=?",arrayOf(campaignUid))
            db.execSQL("INSERT OR REPLACE INTO semantic_index_meta(meta_key,meta_value) VALUES(?,?)",arrayOf(key,scopeFingerprint))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        releaseMappedVectors()
        if(db.rawQuery("SELECT COUNT(*) FROM semantic_entries",null).use{it.moveToFirst();it.getLong(0)}==0L)vectors.setLength(0)
        else maybeCompactVectors()
        return true
        }
    }

    @Synchronized override fun beginProjectionReconciliation(scope:SemanticProjectionScope,recordUidPrefix:String):String{
        require(recordUidPrefix.isNotBlank())
        val session=UUID.randomUUID().toString()
        db.beginTransaction()
        try{
            val previous=db.rawQuery("""SELECT session_uid FROM semantic_reconciliation_state
                WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=? AND record_uid_prefix=?""",
                arrayOf(scope.campaignUid,scope.namespaceUid,scope.audienceUid,scope.purposeUid,recordUidPrefix)
            ).use{if(it.moveToFirst())it.getString(0) else null}
            if(previous!=null)db.delete("semantic_reconciliation_live","session_uid=?",arrayOf(previous))
            db.execSQL("""INSERT OR REPLACE INTO semantic_reconciliation_state(
                campaign_uid,namespace_uid,audience_uid,purpose_uid,record_uid_prefix,session_uid) VALUES(?,?,?,?,?,?)""",
                arrayOf(scope.campaignUid,scope.namespaceUid,scope.audienceUid,scope.purposeUid,recordUidPrefix,session))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return session
    }

    @Synchronized override fun markProjectionReconciliation(sessionUid:String,recordUids:Set<String>){
        require(sessionUid.isNotBlank()&&recordUids.size<=512&&recordUids.none{it.isBlank()})
        if(recordUids.isEmpty())return
        val current=db.rawQuery("SELECT 1 FROM semantic_reconciliation_state WHERE session_uid=? LIMIT 1",arrayOf(sessionUid)).use{it.moveToFirst()}
        check(current){"SEMANTIC_RECONCILIATION_SESSION_STALE"}
        db.beginTransaction()
        try{
            db.compileStatement("INSERT OR IGNORE INTO semantic_reconciliation_live(session_uid,record_uid) VALUES(?,?)").use{statement->
                recordUids.sorted().forEach{uid->statement.clearBindings();statement.bindString(1,sessionUid);statement.bindString(2,uid);statement.executeInsert()}
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
    }

    @Synchronized override fun finishProjectionReconciliation(
        scope:SemanticProjectionScope,recordUidPrefix:String,sessionUid:String,retiredAtOrder:Long
    ):Boolean=synchronized(PROCESS_VECTOR_IO_LOCK){
        require(recordUidPrefix.isNotBlank()&&sessionUid.isNotBlank()&&retiredAtOrder>=0)
        db.beginTransaction()
        try{
            val current=db.rawQuery("""SELECT session_uid FROM semantic_reconciliation_state
                WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=? AND record_uid_prefix=?""",
                arrayOf(scope.campaignUid,scope.namespaceUid,scope.audienceUid,scope.purposeUid,recordUidPrefix)
            ).use{if(it.moveToFirst())it.getString(0) else null}
            if(current!=sessionUid)return@synchronized false
            db.execSQL("""UPDATE semantic_entries SET retired_at_order=CASE
                WHEN retired_at_order IS NULL OR retired_at_order>? THEN ? ELSE retired_at_order END
                WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=?
                  AND record_uid LIKE ? AND as_of_order<=?
                  AND NOT EXISTS(SELECT 1 FROM semantic_reconciliation_live l
                    WHERE l.session_uid=? AND l.record_uid=semantic_entries.record_uid)""",
                arrayOf<Any?>(retiredAtOrder,retiredAtOrder,scope.campaignUid,scope.namespaceUid,scope.audienceUid,
                    scope.purposeUid,"$recordUidPrefix%",retiredAtOrder,sessionUid))
            db.delete("semantic_reconciliation_live","session_uid=?",arrayOf(sessionUid))
            db.delete("semantic_reconciliation_state","session_uid=?",arrayOf(sessionUid))
            db.setTransactionSuccessful()
            true
        }finally{db.endTransaction()}
    }

    @Synchronized override fun advanceCheckpoint(campaignUid:String,committedOrder:Long){
        require(campaignUid.isNotBlank()&&committedOrder>=0)
        require(committedOrder>=checkpoint(campaignUid)){"SEMANTIC_CHECKPOINT_REGRESSION"}
        db.execSQL(
            "INSERT OR REPLACE INTO semantic_checkpoints(campaign_uid,commit_order) VALUES(?,?)",
            arrayOf<Any?>(campaignUid,committedOrder)
        )
    }

    @Synchronized override fun status(campaignUid:String):SemanticIndexStatus{
        val chunks=db.rawQuery("SELECT COUNT(*) FROM semantic_entries WHERE campaign_uid=?",arrayOf(campaignUid)).use{it.moveToFirst();it.getLong(0)}
        val records=db.rawQuery("SELECT COUNT(DISTINCT record_uid) FROM semantic_entries WHERE campaign_uid=?",arrayOf(campaignUid)).use{it.moveToFirst();it.getLong(0)}
        return SemanticIndexStatus(true,records,chunks,checkpoint(campaignUid),version)
    }

    @Synchronized override fun clear(campaignUid:String){
        synchronized(PROCESS_VECTOR_IO_LOCK){
        db.beginTransaction()
        try{
            db.delete("semantic_entries","campaign_uid=?",arrayOf(campaignUid))
            db.delete("semantic_checkpoints","campaign_uid=?",arrayOf(campaignUid))
            val sessions=db.rawQuery("SELECT session_uid FROM semantic_reconciliation_state WHERE campaign_uid=?",arrayOf(campaignUid)).use{cursor->buildList{while(cursor.moveToNext())add(cursor.getString(0))}}
            sessions.forEach{db.delete("semantic_reconciliation_live","session_uid=?",arrayOf(it))}
            db.delete("semantic_reconciliation_state","campaign_uid=?",arrayOf(campaignUid))
            db.delete("semantic_index_meta","meta_key=?",arrayOf("CHECKPOINT_SCOPE:${semanticSha256(campaignUid)}"))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        releaseMappedVectors()
        if(db.rawQuery("SELECT COUNT(*) FROM semantic_entries",null).use{it.moveToFirst();it.getLong(0)}==0L)vectors.setLength(0)
        else maybeCompactVectors()
        releaseMappedVectors()
        }
    }

    @Synchronized override fun close(){synchronized(PROCESS_VECTOR_IO_LOCK){
        if(closed)return@synchronized
        closed=true;releaseMappedVectors();runCatching{vectors.close()};runCatching{db.close()}
        val remaining=(OPEN_INSTANCE_COUNTS[directoryKey]?:1)-1
        if(remaining<=0)OPEN_INSTANCE_COUNTS.remove(directoryKey) else OPEN_INSTANCE_COUNTS[directoryKey]=remaining
    }}

    private fun clearAll(){
        db.delete("semantic_entries",null,null);db.delete("semantic_checkpoints",null,null)
        db.delete("semantic_reconciliation_live",null,null);db.delete("semantic_reconciliation_state",null,null)
        releaseMappedVectors();vectors.setLength(0)
    }
    private fun meta(key:String)=db.rawQuery("SELECT meta_value FROM semantic_index_meta WHERE meta_key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun putMeta(key:String,value:String)=db.execSQL("INSERT OR REPLACE INTO semantic_index_meta(meta_key,meta_value) VALUES(?,?)",arrayOf(key,value))

    private fun normalizedScope(projection:SemanticDocumentProjection)=arrayOf(
        projection.campaignUid,projection.namespaceUid,projection.audienceUid,projection.purposeUid,
        projection.historyGenerationUid?.value?:DEFAULT_HISTORY_GENERATION_UID,
        projection.principalUid.takeIf{it.isNotBlank()}?:DEFAULT_PRINCIPAL_UID,
        projection.holderSetFingerprint.takeIf{it.isNotBlank()}?:DEFAULT_HOLDER_FINGERPRINT,
        projection.accessPolicyVersion.toString(),projection.activePlayerUid.orEmpty(),projection.projectionVersionUid,
        projection.asOfOrder.toString(),projection.sourceVersion.toString(),
        projection.canonicalRecordUid,projection.chunkOrdinal.toString()
    )

    private fun sameRecordRevision(left:SemanticDocumentProjection,right:SemanticDocumentProjection):Boolean=
        left.campaignUid==right.campaignUid&&left.namespaceUid==right.namespaceUid&&
            left.audienceUid==right.audienceUid&&left.purposeUid==right.purposeUid&&
            left.historyGenerationUid==right.historyGenerationUid&&left.principalUid==right.principalUid&&
            left.holderSetFingerprint==right.holderSetFingerprint&&left.accessPolicyVersion==right.accessPolicyVersion&&
            left.activePlayerUid==right.activePlayerUid&&left.projectionVersionUid==right.projectionVersionUid&&
            left.asOfOrder==right.asOfOrder&&left.sourceVersion==right.sourceVersion&&
            left.canonicalRecordUid==right.canonicalRecordUid

    private fun projectionScopePrefix(projection:SemanticDocumentProjection)=Triple(
        projection.campaignUid,projection.namespaceUid,projection.audienceUid to projection.purposeUid
    )

    private fun entryExists(projection:SemanticDocumentProjection):Boolean{
        val s=normalizedScope(projection)
        return db.rawQuery("""SELECT 1 FROM semantic_entries WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=?
            AND history_generation_uid=? AND principal_uid=? AND holder_set_fingerprint=? AND access_policy_version=?
            AND active_player_uid=? AND projection_version_uid=? AND as_of_order=? AND source_version=?
            AND record_uid=? AND chunk_ordinal=? LIMIT 1""",s
        ).use{it.moveToFirst()}
    }

    private fun identicalEntry(projection:SemanticDocumentProjection):Boolean{
        val s=normalizedScope(projection)
        return db.rawQuery("""SELECT record_kind_uid,epistemic_state_uid,as_of_order,source_version,source_fingerprint,projected_text
            FROM semantic_entries WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=?
            AND history_generation_uid=? AND principal_uid=? AND holder_set_fingerprint=? AND access_policy_version=?
            AND active_player_uid=? AND projection_version_uid=? AND as_of_order=? AND source_version=?
            AND record_uid=? AND chunk_ordinal=? LIMIT 1""",s
        ).use{cursor->cursor.moveToFirst()&&
            cursor.getString(0)==projection.recordKindUid&&cursor.getString(1)==projection.epistemicStateUid&&
            cursor.getLong(2)==projection.asOfOrder&&cursor.getLong(3)==projection.sourceVersion&&
            cursor.getString(4)==projection.sourceFingerprint&&cursor.getString(5)==projection.text
        }
    }

    private fun existingOrdinals(projection:SemanticDocumentProjection):Set<Int>{
        val scope=normalizedScope(projection).dropLast(1).toTypedArray()
        return db.rawQuery("""SELECT chunk_ordinal FROM semantic_entries WHERE campaign_uid=? AND namespace_uid=?
            AND audience_uid=? AND purpose_uid=? AND history_generation_uid=? AND principal_uid=?
            AND holder_set_fingerprint=? AND access_policy_version=? AND active_player_uid=? AND projection_version_uid=?
            AND as_of_order=? AND source_version=? AND record_uid=?""",scope
        ).use{cursor->buildSet{while(cursor.moveToNext())add(cursor.getInt(0))}}
    }

    /** A stale asynchronous worker may try to re-upsert a revision after a newer worker retired
     * it. Preserve the tombstone while replacing that exact primary-key row; checkpoint
     * monotonicity will then reject the stale worker without allowing it to poison current topK. */
    private fun existingRetirement(projection:SemanticDocumentProjection):Long?{
        val scope=normalizedScope(projection)
        return db.rawQuery("""SELECT retired_at_order FROM semantic_entries WHERE campaign_uid=? AND namespace_uid=?
            AND audience_uid=? AND purpose_uid=? AND history_generation_uid=? AND principal_uid=?
            AND holder_set_fingerprint=? AND access_policy_version=? AND active_player_uid=? AND projection_version_uid=?
            AND as_of_order=? AND source_version=? AND record_uid=? AND chunk_ordinal=? LIMIT 1""",scope
        ).use{cursor->
            if(!cursor.moveToFirst()||cursor.isNull(0))null else cursor.getLong(0)
        }
    }

    private fun deleteRecordRevision(projection:SemanticDocumentProjection){
        val scope=normalizedScope(projection).dropLast(1).toTypedArray()
        db.delete("semantic_entries","""campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=?
            AND history_generation_uid=? AND principal_uid=? AND holder_set_fingerprint=? AND access_policy_version=?
            AND active_player_uid=? AND projection_version_uid=? AND as_of_order=? AND source_version=? AND record_uid=?""",scope)
    }

    private fun recoverIncompleteCompaction(){
        if(meta(COMPACTION_MARKER)==null)return
        db.beginTransaction()
        try{
            db.delete("semantic_entries",null,null);db.delete("semantic_checkpoints",null,null)
            db.delete("semantic_index_meta","meta_key=?",arrayOf(COMPACTION_MARKER))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        releaseMappedVectors();vectors.setLength(0)
        File(directory,".semantic-vectors.compacting").delete()
    }

    /** Vector bytes are flushed before SQLite metadata. A process death between those steps can
     * leave only an unreferenced tail; derive the durable boundary from committed rows. */
    private fun recoverUncommittedVectorTail(){
        val vectorBytes=version.dimensions*2L
        val durableLength=db.rawQuery("SELECT MAX(vector_offset) FROM semantic_entries",null).use{cursor->
            cursor.moveToFirst()
            if(cursor.isNull(0))0L else cursor.getLong(0)+vectorBytes
        }
        when{
            vectors.length()>durableLength->vectors.setLength(durableLength)
            vectors.length()<durableLength->clearAll()
        }
        releaseMappedVectors()
    }

    private fun maybeCompactVectors(){
        val rowCount=db.rawQuery("SELECT COUNT(*) FROM semantic_entries",null).use{it.moveToFirst();it.getLong(0)}
        val liveBytes=rowCount*version.dimensions*2L
        val orphanBytes=(vectors.length()-liveBytes).coerceAtLeast(0L)
        if(orphanBytes<COMPACTION_MIN_ORPHAN_BYTES&&orphanBytes*4L<liveBytes)return
        // UI, Bridge and Director can own separate index facades. Renaming the vector file while
        // another RandomAccessFile is open would detach that handle, so multi-instance mode uses
        // a crash-marked in-place compaction under the process-wide vector lock.
        if((OPEN_INSTANCE_COUNTS[directoryKey]?:1)>1)compactVectorsInPlace() else compactVectors()
    }

    private fun compactVectorsInPlace(){
        val rows=db.rawQuery("SELECT rowid,vector_offset FROM semantic_entries ORDER BY vector_offset,rowid",null).use{cursor->
            buildList{while(cursor.moveToNext())add(cursor.getLong(0) to cursor.getLong(1))}
        }
        if(rows.isEmpty()){releaseMappedVectors();vectors.setLength(0);return}
        putMeta(COMPACTION_MARKER,UUID.randomUUID().toString())
        val vectorBytes=version.dimensions*2
        val bytes=ByteArray(vectorBytes)
        val newOffsets=ArrayList<Pair<Long,Long>>(rows.size)
        try{
            releaseMappedVectors()
            rows.forEachIndexed{index,(rowId,oldOffset)->
                vectors.seek(oldOffset);vectors.readFully(bytes)
                val newOffset=index.toLong()*vectorBytes
                vectors.seek(newOffset);vectors.write(bytes)
                newOffsets+=rowId to newOffset
            }
            vectors.fd.sync()
            vectors.setLength(rows.size.toLong()*vectorBytes)
            vectors.fd.sync()
            db.beginTransaction()
            try{
                db.compileStatement("UPDATE semantic_entries SET vector_offset=? WHERE rowid=?").use{statement->
                    newOffsets.forEach{(rowId,offset)->
                        statement.clearBindings();statement.bindLong(1,offset);statement.bindLong(2,rowId);statement.executeUpdateDelete()
                    }
                }
                db.delete("semantic_index_meta","meta_key=?",arrayOf(COMPACTION_MARKER))
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
            releaseMappedVectors()
        }catch(failure:Throwable){
            // Metadata still carries the compaction marker. This cache is rebuildable: remove
            // potentially half-moved rows/vectors rather than interpreting a mixed layout.
            recoverIncompleteCompaction()
            throw failure
        }
    }

    private fun compactVectors(){
        val rows=db.rawQuery("SELECT rowid,vector_offset FROM semantic_entries ORDER BY vector_offset,rowid",null).use{cursor->
            buildList{while(cursor.moveToNext())add(cursor.getLong(0) to cursor.getLong(1))}
        }
        if(rows.isEmpty()){releaseMappedVectors();vectors.setLength(0);return}
        val temp=File(directory,".semantic-vectors.compacting")
        if(temp.exists())check(temp.delete()){"SEMANTIC_COMPACTION_TEMP_STALE"}
        putMeta(COMPACTION_MARKER,UUID.randomUUID().toString())
        val vectorBytes=version.dimensions*2
        val newOffsets=ArrayList<Pair<Long,Long>>(rows.size)
        try{
            RandomAccessFile(temp,"rw").use{target->
                val bytes=ByteArray(vectorBytes)
                rows.forEach{(rowId,oldOffset)->
                    vectors.seek(oldOffset);vectors.readFully(bytes)
                    val newOffset=target.filePointer;target.write(bytes);newOffsets+=rowId to newOffset
                }
                target.fd.sync()
            }
            releaseMappedVectors();vectors.close()
            try{
                Files.move(temp.toPath(),vectorsFile.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            }catch(_:AtomicMoveNotSupportedException){
                Files.move(temp.toPath(),vectorsFile.toPath(),StandardCopyOption.REPLACE_EXISTING)
            }
            vectors=RandomAccessFile(vectorsFile,"rw")
            db.beginTransaction()
            try{
                db.compileStatement("UPDATE semantic_entries SET vector_offset=? WHERE rowid=?").use{statement->
                    newOffsets.forEach{(rowId,offset)->statement.clearBindings();statement.bindLong(1,offset);statement.bindLong(2,rowId);statement.executeUpdateDelete()}
                }
                db.delete("semantic_index_meta","meta_key=?",arrayOf(COMPACTION_MARKER))
                db.setTransactionSuccessful()
            }finally{db.endTransaction()}
            releaseMappedVectors()
        }catch(failure:Throwable){
            runCatching{vectors.close()};vectors=RandomAccessFile(vectorsFile,"rw")
            recoverIncompleteCompaction()
            throw failure
        }finally{if(temp.exists())temp.delete()}
    }

    private fun scope(request:SemanticSearchRequest):Scope=Scope(
        request.campaignUid,request.namespaceUid,request.audienceUid,request.purposeUid,request.asOfOrder,
        request.historyGenerationUid?.value,request.principalUid,request.holderSetFingerprint,
        request.accessPolicyVersion,request.activePlayerUid,request.projectionVersionUid
    )

    private fun scopeWhere(scope:Scope,restrictedUids:Set<String> = emptySet()):Pair<String,Array<String>>{
        val clauses=mutableListOf(
            "campaign_uid=?","namespace_uid=?","audience_uid=?","purpose_uid=?","as_of_order<=?",
            "(retired_at_order IS NULL OR retired_at_order>?)","projection_version_uid=?"
        )
        val args=mutableListOf(
            scope.campaign,scope.namespace,scope.audience,scope.purpose,scope.asOfOrder.toString(),scope.asOfOrder.toString(),scope.projectionVersionUid
        )
        if(scope.historyGenerationUid!=null){
            clauses+=listOf("history_generation_uid=?","principal_uid=?","holder_set_fingerprint=?","access_policy_version=?","active_player_uid=?")
            args+=listOf(scope.historyGenerationUid,scope.principalUid,scope.holderSetFingerprint,scope.accessPolicyVersion.toString(),scope.activePlayerUid.orEmpty())
        }else{
            if(scope.principalUid.isNotBlank()){
                clauses+="(principal_uid=? OR principal_uid=?)";args+=listOf(scope.principalUid,DEFAULT_PRINCIPAL_UID)
            }
            if(scope.holderSetFingerprint.isNotBlank()){
                clauses+="(holder_set_fingerprint=? OR holder_set_fingerprint=?)";args+=listOf(scope.holderSetFingerprint,DEFAULT_HOLDER_FINGERPRINT)
            }
            if(scope.accessPolicyVersion>0){
                clauses+="(access_policy_version=? OR access_policy_version=0)";args+=scope.accessPolicyVersion.toString()
            }
            if(!scope.activePlayerUid.isNullOrBlank()){
                clauses+="(active_player_uid=? OR active_player_uid='')";args+=scope.activePlayerUid
            }
        }
        if(restrictedUids.isNotEmpty()&&restrictedUids.size<=700){
            clauses+="record_uid IN (${restrictedUids.joinToString(","){"?"}})";args+=restrictedUids.sorted()
        }
        return clauses.joinToString(" AND ") to args.toTypedArray()
    }

    private fun decodeRow(cursor:android.database.Cursor)=Row(
        cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getLong(4),cursor.getLong(5),
        cursor.getString(6),cursor.getString(7),cursor.getString(8),cursor.getLong(9),cursor.getString(10),cursor.getString(11),
        cursor.getInt(12),cursor.getLong(13),cursor.getString(14)
    )

    private fun ensureScopeColumns(){
        val existing=db.rawQuery("PRAGMA table_info(semantic_entries)",null).use{cursor->
            generateSequence{
                if(!cursor.moveToNext())null
                else cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }.toList()
        }
        if("history_generation_uid" !in existing)db.execSQL(
            "ALTER TABLE semantic_entries ADD COLUMN history_generation_uid TEXT NOT NULL DEFAULT '$DEFAULT_HISTORY_GENERATION_UID'"
        )
        if("principal_uid" !in existing)db.execSQL(
            "ALTER TABLE semantic_entries ADD COLUMN principal_uid TEXT NOT NULL DEFAULT '$DEFAULT_PRINCIPAL_UID'"
        )
        if("holder_set_fingerprint" !in existing)db.execSQL(
            "ALTER TABLE semantic_entries ADD COLUMN holder_set_fingerprint TEXT NOT NULL DEFAULT '$DEFAULT_HOLDER_FINGERPRINT'"
        )
        if("access_policy_version" !in existing)db.execSQL("ALTER TABLE semantic_entries ADD COLUMN access_policy_version INTEGER NOT NULL DEFAULT 0")
        if("active_player_uid" !in existing)db.execSQL("ALTER TABLE semantic_entries ADD COLUMN active_player_uid TEXT NOT NULL DEFAULT '$DEFAULT_ACTIVE_PLAYER_UID'")
        if("projection_version_uid" !in existing)db.execSQL(
            "ALTER TABLE semantic_entries ADD COLUMN projection_version_uid TEXT NOT NULL DEFAULT '${VisibilityAuthorityService.PROJECTION_VERSION_UID}'"
        )
        if("retired_at_order" !in existing)db.execSQL("ALTER TABLE semantic_entries ADD COLUMN retired_at_order INTEGER")
    }

    private fun ensureProjectedTextColumn(){
        val present=db.rawQuery("PRAGMA table_info(semantic_entries)",null).use{cursor->
            var found=false
            while(cursor.moveToNext())if(cursor.getString(cursor.getColumnIndexOrThrow("name"))=="projected_text")found=true
            found
        }
        if(!present)db.execSQL("ALTER TABLE semantic_entries ADD COLUMN projected_text TEXT NOT NULL DEFAULT ''")
    }

    private fun createSemanticEntriesTable(){
        db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_entries(
            campaign_uid TEXT NOT NULL, namespace_uid TEXT NOT NULL, audience_uid TEXT NOT NULL, purpose_uid TEXT NOT NULL,
            record_uid TEXT NOT NULL, record_kind_uid TEXT NOT NULL, epistemic_state_uid TEXT NOT NULL, as_of_order INTEGER NOT NULL,
            source_version INTEGER NOT NULL, source_fingerprint TEXT NOT NULL, history_generation_uid TEXT NOT NULL DEFAULT '$DEFAULT_HISTORY_GENERATION_UID',
            principal_uid TEXT NOT NULL DEFAULT '$DEFAULT_PRINCIPAL_UID', holder_set_fingerprint TEXT NOT NULL DEFAULT '$DEFAULT_HOLDER_FINGERPRINT',
            access_policy_version INTEGER NOT NULL DEFAULT 0, active_player_uid TEXT NOT NULL DEFAULT '$DEFAULT_ACTIVE_PLAYER_UID',
            projection_version_uid TEXT NOT NULL DEFAULT '${VisibilityAuthorityService.PROJECTION_VERSION_UID}', chunk_ordinal INTEGER NOT NULL,
            vector_offset INTEGER NOT NULL, projected_text TEXT NOT NULL DEFAULT '', retired_at_order INTEGER,
            PRIMARY KEY(
                campaign_uid,namespace_uid,audience_uid,purpose_uid,history_generation_uid,principal_uid,
                holder_set_fingerprint,access_policy_version,active_player_uid,projection_version_uid,
                as_of_order,source_version,record_uid,chunk_ordinal
            ))""")
    }

    /**
     * Scope metadata is part of cache identity, not merely a search-time filter. Older sidecars
     * keyed only by record UID could silently replace one principal/history projection with
     * another. The sidecar is rebuildable, so an incompatible key is discarded rather than
     * attempting to reinterpret potentially cross-scope rows.
     */
    private fun ensureIsolationPrimaryKey(){
        val expected=listOf(
            "campaign_uid","namespace_uid","audience_uid","purpose_uid","history_generation_uid","principal_uid",
            "holder_set_fingerprint","access_policy_version","active_player_uid","projection_version_uid",
            "as_of_order","source_version","record_uid","chunk_ordinal"
        )
        val actual=db.rawQuery("PRAGMA table_info(semantic_entries)",null).use{cursor->
            buildList{
                val nameIndex=cursor.getColumnIndexOrThrow("name")
                val pkIndex=cursor.getColumnIndexOrThrow("pk")
                val keyed=mutableListOf<Pair<Int,String>>()
                while(cursor.moveToNext()){
                    val order=cursor.getInt(pkIndex)
                    if(order>0)keyed+=order to cursor.getString(nameIndex)
                }
                addAll(keyed.sortedBy{it.first}.map{it.second})
            }
        }
        if(actual==expected)return
        db.beginTransaction()
        try{
            db.execSQL("DROP TABLE semantic_entries")
            createSemanticEntriesTable()
            db.delete("semantic_checkpoints",null,null)
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        releaseMappedVectors();vectors.setLength(0)
    }

    private fun mappedVectors():ByteBuffer{
        val length=vectors.length();require(length>0)
        mappedVectors?.takeIf{mappedLength==length}?.let{return it}
        return vectors.channel.map(FileChannel.MapMode.READ_ONLY,0,length).order(ByteOrder.LITTLE_ENDIAN).also{
            mappedVectors=it;mappedLength=length
        }
    }

    /** Windows keeps a mapped file locked until the direct buffer is explicitly cleaned. Android
     * does not need this for ordinary append, but the best-effort release is required before
     * compaction, truncation and same-process reopen in the desktop test laboratory. */
    private fun releaseMappedVectors(){
        val mapped=mappedVectors
        mappedVectors=null;mappedLength=-1L
        if(mapped==null||!mapped.isDirect)return
        runCatching{
            val unsafeClass=Class.forName("sun.misc.Unsafe")
            val field=unsafeClass.getDeclaredField("theUnsafe").apply{isAccessible=true}
            val unsafe=field.get(null)
            unsafeClass.getMethod("invokeCleaner",ByteBuffer::class.java).invoke(unsafe,mapped)
        }.recoverCatching{
            val cleanerMethod=mapped.javaClass.getDeclaredMethod("cleaner").apply{isAccessible=true}
            val cleaner=cleanerMethod.invoke(mapped)?:return@recoverCatching
            cleaner.javaClass.getMethod("clean").invoke(cleaner)
        }
    }

    private fun scoreFp16(buffer:ByteBuffer,offsets:LongArray,query:FloatArray):FloatArray{
        if(NativeLocalInferenceBridge.available){
            runCatching{NativeLocalInferenceBridge.scoreFp16(buffer,offsets,query)}.getOrNull()
                ?.takeIf{it.size==offsets.size}?.let{return it}
        }
        return FloatArray(offsets.size){index->dotFp16(buffer,offsets[index],query)}
    }

    private fun dotFp16(buffer:ByteBuffer,offset:Long,query:FloatArray):Float{
        require(offset>=0&&offset+query.size*2L<=buffer.limit().toLong()){"SEMANTIC_VECTOR_OFFSET_CORRUPT"}
        val view=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);view.position(offset.toInt())
        var sum=0.0f
        for(index in query.indices)sum+=halfToFloat(view.short)*query[index]
        return sum.coerceIn(-1f,1f)
    }

    private fun floatToHalf(value:Float):Short{
        val bits=value.toRawBits();val sign=(bits ushr 16) and 0x8000;var exponent=((bits ushr 23) and 0xff)-127+15;var mantissa=bits and 0x7fffff
        return when{
            exponent<=0->{if(exponent< -10)sign.toShort() else{mantissa=(mantissa or 0x800000) shr (1-exponent);(sign or ((mantissa+0x1000) shr 13)).toShort()}}
            exponent>=31->(sign or 0x7c00).toShort()
            else->(sign or (exponent shl 10) or ((mantissa+0x1000) shr 13)).toShort()
        }
    }
    private fun halfToFloat(raw:Short):Float{
        val value=raw.toInt() and 0xffff;val sign=(value and 0x8000) shl 16;var exponent=(value ushr 10) and 0x1f;var mantissa=value and 0x3ff
        val bits=when(exponent){
            0->if(mantissa==0)sign else{exponent=1;while((mantissa and 0x400)==0){mantissa=mantissa shl 1;exponent--};mantissa=mantissa and 0x3ff;sign or ((exponent+127-15) shl 23) or (mantissa shl 13)}
            31->sign or 0x7f800000 or (mantissa shl 13)
            else->sign or ((exponent+127-15) shl 23) or (mantissa shl 13)
        }
        return Float.fromBits(bits)
    }

}
