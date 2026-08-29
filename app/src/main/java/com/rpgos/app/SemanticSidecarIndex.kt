package com.rpgos.app

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.PriorityQueue

/**
 * Per-campaign rebuildable sidecar. The metadata DB and vector file never participate in
 * canonical saves, snapshots, receipts or truth hashes.
 */
class FileSemanticIndex(
    root:File,
    override val version:SemanticIndexVersion=SemanticIndexVersion()
):SemanticIndexPort{
    private val directory=root.apply{mkdirs()}
    private val metadataFile=File(directory,"semantic-index.sqlite")
    private val vectorsFile=File(directory,"semantic-vectors.fp16")
    private val db=SQLiteDatabase.openOrCreateDatabase(metadataFile,null)
    private val vectors=RandomAccessFile(vectorsFile,"rw")
    private data class Scope(val campaign:String,val namespace:String,val audience:String,val purpose:String)
    private data class Row(
        val uid:String,val kind:String,val epistemic:String,val sourceFingerprint:String,
        val sourceVersion:Long,val asOfOrder:Long,val chunk:Int,val offset:Long,val projectedText:String
    )
    private val cachedRows=mutableMapOf<Scope,List<Row>>()
    private var mappedVectors:ByteBuffer?=null
    private var mappedLength=-1L
    private val versionFingerprint=semanticSha256(listOf(
        version.modelUid,version.modelRevision,version.modelSha256,version.dimensions,
        version.normalizationUid,version.vectorFormatUid,version.projectorVersion
    ).joinToString("|"))

    init{
        db.rawQuery("PRAGMA journal_mode=WAL",null).use{it.moveToFirst()}
        db.rawQuery("PRAGMA synchronous=NORMAL",null).use{it.moveToFirst()}
        db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_index_meta(
            meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_entries(
            campaign_uid TEXT NOT NULL, namespace_uid TEXT NOT NULL, audience_uid TEXT NOT NULL,
            purpose_uid TEXT NOT NULL, record_uid TEXT NOT NULL, record_kind_uid TEXT NOT NULL,
            epistemic_state_uid TEXT NOT NULL, as_of_order INTEGER NOT NULL, source_version INTEGER NOT NULL,
            source_fingerprint TEXT NOT NULL, chunk_ordinal INTEGER NOT NULL, vector_offset INTEGER NOT NULL,
            projected_text TEXT NOT NULL DEFAULT '',
            PRIMARY KEY(campaign_uid,namespace_uid,audience_uid,purpose_uid,record_uid,chunk_ordinal))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_semantic_scope ON semantic_entries(campaign_uid,namespace_uid,audience_uid,purpose_uid,as_of_order)")
        ensureProjectedTextColumn()
        db.execSQL("""CREATE TABLE IF NOT EXISTS semantic_checkpoints(
            campaign_uid TEXT PRIMARY KEY, commit_order INTEGER NOT NULL)""")
        val stored=meta("INDEX_VERSION")
        if(stored!=null&&stored!=versionFingerprint)clearAll()
        putMeta("INDEX_VERSION",versionFingerprint)
    }

    @Synchronized override fun upsertBatch(documents:List<SemanticIndexedDocument>){
        if(documents.isEmpty())return
        documents.forEach{document->
            require(document.vector.size==version.dimensions){"SEMANTIC_VECTOR_DIMENSION_MISMATCH"}
            require(document.vector.all{it.isFinite()}){"SEMANTIC_VECTOR_NON_FINITE"}
        }
        val vectorStart=vectors.length()
        val vectorBytes=ByteBuffer.allocate(documents.size*version.dimensions*2).order(ByteOrder.LITTLE_ENDIAN)
        val rows=documents.map{document->
            val offset=vectorStart+vectorBytes.position()
            document.vector.forEach{vectorBytes.putShort(floatToHalf(it))}
            document to offset
        }
        vectors.seek(vectorStart);vectors.write(vectorBytes.array())
        vectors.fd.sync()
        db.beginTransaction()
        try{
            db.compileStatement("""INSERT OR REPLACE INTO semantic_entries(
                campaign_uid,namespace_uid,audience_uid,purpose_uid,record_uid,record_kind_uid,
                epistemic_state_uid,as_of_order,source_version,source_fingerprint,chunk_ordinal,vector_offset,projected_text)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)""").use{statement->rows.forEach{(document,offset)->with(document.projection){
                    statement.clearBindings()
                    statement.bindString(1,campaignUid);statement.bindString(2,namespaceUid)
                    statement.bindString(3,audienceUid);statement.bindString(4,purposeUid)
                    statement.bindString(5,canonicalRecordUid);statement.bindString(6,recordKindUid)
                    statement.bindString(7,epistemicStateUid);statement.bindLong(8,asOfOrder)
                    statement.bindLong(9,sourceVersion);statement.bindString(10,sourceFingerprint)
                    statement.bindLong(11,chunkOrdinal.toLong());statement.bindLong(12,offset)
                    statement.bindString(13,text);statement.executeInsert()
                }}}
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        rows.map{it.first.projection}.map{Scope(it.campaignUid,it.namespaceUid,it.audienceUid,it.purposeUid)}
            .distinct().forEach(cachedRows::remove)
        mappedVectors=null;mappedLength=-1
    }

    @Synchronized override fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String){
        require(listOf(campaignUid,namespaceUid,canonicalRecordUid).none{it.isBlank()})
        db.delete("semantic_entries","campaign_uid=? AND namespace_uid=? AND record_uid=?",arrayOf(campaignUid,namespaceUid,canonicalRecordUid))
        cachedRows.keys.filter{it.campaign==campaignUid&&it.namespace==namespaceUid}.forEach(cachedRows::remove)
    }

    @Synchronized override fun authorizedRecordUids(
        campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long
    ):Set<String>{
        require(asOfOrder>=0)
        return rows(Scope(campaignUid,namespaceUid,audienceUid,purposeUid)).asSequence()
            .filter{it.asOfOrder<=asOfOrder}.map{it.uid}.toCollection(HashSet())
    }

    @Synchronized override fun searchAuthorized(request:SemanticSearchRequest):List<SemanticCandidate>{
        require(request.queryVector.size==version.dimensions){"SEMANTIC_QUERY_DIMENSION_MISMATCH"}
        val query=matryoshkaL2(request.queryVector,version.dimensions)
        if(vectors.length()==0L)return emptyList()
        val rows=rows(Scope(request.campaignUid,request.namespaceUid,request.audienceUid,request.purposeUid)).filter{row->
            row.asOfOrder<=request.asOfOrder&&row.uid in request.authorizedRecordUids&&
                (request.allowedRecordKinds.isEmpty()||row.kind in request.allowedRecordKinds)
        }
        if(rows.isEmpty())return emptyList()
        val map=mappedVectors()
        val scores=scoreFp16(map,rows.map{it.offset}.toLongArray(),query)
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
        rows.forEachIndexed{index,row->
            if(activeUid!=null&&activeUid!=row.uid)emit()
            activeUid=row.uid
            val score=scores[index]
            if(score>=request.minimumScore)group+=ScoredChunk(row,score)
        }
        emit()
        return top.toList().sortedWith(compareByDescending<CandidateSeed>{it.best.score}.thenBy{it.best.row.uid}).map{seed->
            val best=seed.best
            SemanticCandidate(
                best.row.uid,best.score,best.row.kind,best.row.epistemic,best.row.sourceFingerprint,
                best.row.sourceVersion,seed.evidence.map{
                    SemanticChunkEvidence(it.row.chunk,it.row.projectedText,semanticSha256(it.row.projectedText))
                },version
            )
        }
    }

    @Synchronized override fun checkpoint(campaignUid:String):Long=db.rawQuery(
        "SELECT commit_order FROM semantic_checkpoints WHERE campaign_uid=?",arrayOf(campaignUid)
    ).use{if(it.moveToFirst())it.getLong(0) else 0L}

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
        db.beginTransaction()
        try{
            db.delete("semantic_entries","campaign_uid=?",arrayOf(campaignUid))
            db.delete("semantic_checkpoints","campaign_uid=?",arrayOf(campaignUid))
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        if(db.rawQuery("SELECT COUNT(*) FROM semantic_entries",null).use{it.moveToFirst();it.getLong(0)}==0L)vectors.setLength(0)
        cachedRows.keys.filter{it.campaign==campaignUid}.forEach(cachedRows::remove)
        mappedVectors=null;mappedLength=-1
    }

    @Synchronized override fun close(){runCatching{vectors.close()};runCatching{db.close()}}

    private fun clearAll(){
        db.delete("semantic_entries",null,null);db.delete("semantic_checkpoints",null,null);vectors.setLength(0)
        cachedRows.clear();mappedVectors=null;mappedLength=-1
    }
    private fun meta(key:String)=db.rawQuery("SELECT meta_value FROM semantic_index_meta WHERE meta_key=?",arrayOf(key)).use{if(it.moveToFirst())it.getString(0) else null}
    private fun putMeta(key:String,value:String)=db.execSQL("INSERT OR REPLACE INTO semantic_index_meta(meta_key,meta_value) VALUES(?,?)",arrayOf(key,value))

    private fun dotFp16(buffer:ByteBuffer,offset:Long,query:FloatArray):Float{
        require(offset>=0&&offset+query.size*2L<=buffer.limit().toLong()){"SEMANTIC_VECTOR_OFFSET_CORRUPT"}
        val view=buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);view.position(offset.toInt())
        var sum=0.0f
        for(index in query.indices)sum+=halfToFloat(view.short)*query[index]
        return sum.coerceIn(-1f,1f)
    }

    private fun rows(scope:Scope):List<Row> = cachedRows.getOrPut(scope){
        db.rawQuery("""SELECT record_uid,record_kind_uid,epistemic_state_uid,source_fingerprint,
            source_version,as_of_order,chunk_ordinal,vector_offset,projected_text FROM semantic_entries
            WHERE campaign_uid=? AND namespace_uid=? AND audience_uid=? AND purpose_uid=?
            ORDER BY record_uid,chunk_ordinal""",arrayOf(scope.campaign,scope.namespace,scope.audience,scope.purpose)).use{cursor->
            buildList{while(cursor.moveToNext())add(Row(
                cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getLong(4),
                cursor.getLong(5),cursor.getInt(6),cursor.getLong(7),cursor.getString(8)
            ))}
        }
    }

    private fun ensureProjectedTextColumn(){
        val present=db.rawQuery("PRAGMA table_info(semantic_entries)",null).use{cursor->
            var found=false
            while(cursor.moveToNext())if(cursor.getString(cursor.getColumnIndexOrThrow("name"))=="projected_text")found=true
            found
        }
        if(!present)db.execSQL("ALTER TABLE semantic_entries ADD COLUMN projected_text TEXT NOT NULL DEFAULT ''")
    }

    private fun mappedVectors():ByteBuffer{
        val length=vectors.length();require(length>0)
        mappedVectors?.takeIf{mappedLength==length}?.let{return it}
        return vectors.channel.map(FileChannel.MapMode.READ_ONLY,0,length).order(ByteOrder.LITTLE_ENDIAN).also{
            mappedVectors=it;mappedLength=length
        }
    }

    private fun scoreFp16(buffer:ByteBuffer,offsets:LongArray,query:FloatArray):FloatArray{
        if(NativeLocalInferenceBridge.available){
            runCatching{NativeLocalInferenceBridge.scoreFp16(buffer,offsets,query)}.getOrNull()
                ?.takeIf{it.size==offsets.size}?.let{return it}
        }
        return FloatArray(offsets.size){index->dotFp16(buffer,offsets[index],query)}
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
