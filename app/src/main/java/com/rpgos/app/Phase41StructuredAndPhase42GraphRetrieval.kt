package com.rpgos.app

import android.database.sqlite.SQLiteDatabase

enum class RetrievalState { VALUE, NO_DATA, DENIED, NOT_DISCLOSED, UNKNOWN, UNSUPPORTED, CORRUPTION }
data class StructuredRetrievalRequest(
    val requestUid:String,val campaignUid:String,val providerUid:String,val operationUid:String,val filters:Map<String,String>,val limit:Int,
    val audience:AudienceContext,val purpose:PurposeContext,val atOrder:Long?=null,val cursor:String?=null
){init{require(requestUid.isNotBlank()&&campaignUid.isNotBlank()&&providerUid.isNotBlank()&&operationUid.isNotBlank());require(limit in 1..200&&atOrder?.let{it>=0L}!=false);require(audience.campaignUid==campaignUid&&purpose.campaignUid==campaignUid){"RPGOS-P41:CROSS_CAMPAIGN"};require(filters.keys.none{it.isBlank()}&&filters.values.none{it.length>512})}
    fun fingerprint():String=listOf(campaignUid,providerUid,operationUid,filters.toSortedMap(),limit,atOrder,cursor,audience.audienceKindUid,audience.principal,purpose.purposeUid).joinToString("|")
}
data class RetrievalRecord(val recordUid:String,val values:Map<String,Any?>,val provenanceUid:String?=null){init{require(recordUid.isNotBlank())}}
sealed interface StructuredRetrievalResult{
    val state:RetrievalState
    data class Value(val records:List<RetrievalRecord>,val complete:Boolean,val nextCursor:String?=null):StructuredRetrievalResult{override val state=RetrievalState.VALUE}
    data object NoData:StructuredRetrievalResult{override val state=RetrievalState.NO_DATA}
    data class Denied(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.DENIED}
    data class NotDisclosed(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.NOT_DISCLOSED}
    data class Unknown(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.UNKNOWN}
    data class Unsupported(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.UNSUPPORTED}
    data class Corruption(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.CORRUPTION}
}
fun interface StructuredQueryProvider{fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult}
data class StructuredProviderBinding(val providerUid:String,val operations:Set<String>,val provider:StructuredQueryProvider){init{require(providerUid.isNotBlank()&&operations.isNotEmpty()&&operations.none{it.isBlank()})}}
class StructuredSqlRetriever(bindings:List<StructuredProviderBinding>){
    private val providers=bindings.associateBy{it.providerUid};init{require(providers.size==bindings.size){"RPGOS-P41:DUPLICATE_PROVIDER"}}
    fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        val binding=providers[request.providerUid]?:return StructuredRetrievalResult.Unsupported("PROVIDER_NOT_REGISTERED");if(request.operationUid !in binding.operations)return StructuredRetrievalResult.Unsupported("OPERATION_NOT_ALLOWLISTED")
        return try{when(val result=binding.provider.retrieve(request)){is StructuredRetrievalResult.Value->if(result.records.isEmpty())StructuredRetrievalResult.NoData else result.copy(records=result.records.take(request.limit));else->result}}catch(_:VisibilityAuthorityFailure.CrossCampaign){throw VisibilityAuthorityFailure.CrossCampaign()}catch(_:Throwable){StructuredRetrievalResult.Corruption("PROVIDER_FAILURE")}
    }
}

data class CausalTraversalSpec(val startEventUid:String,val directionUid:String,val relationKinds:Set<String> = emptySet(),val maxDepth:Int=3,val maxEdges:Int=100){init{require(startEventUid.isNotBlank());require(directionUid in setOf("OUTGOING","INCOMING","BOTH"));require(maxDepth in 1..8&&maxEdges in 1..200)}}
/** Bounded read-only traversal. It never infers or writes a canonical relation. */
internal class Phase42CausalGraphRetriever(private val db:SQLiteDatabase,private val campaignUid:String){
    fun traverse(spec:CausalTraversalSpec):List<RetrievalRecord>{
        val frontier=ArrayDeque<Pair<String,Int>>()
        frontier.add(spec.startEventUid to 0)
        val visited=linkedSetOf(spec.startEventUid)
        val edges=linkedSetOf<String>()
        val out=mutableListOf<RetrievalRecord>()
        while(frontier.isNotEmpty()&&out.size<spec.maxEdges){
            val(node,depth)=frontier.removeFirst()
            if(depth>=spec.maxDepth)continue
            val where=when(spec.directionUid){"OUTGOING"->"source_event_uid=?";"INCOMING"->"target_event_uid=?";else->"(source_event_uid=? OR target_event_uid=?)"}
            val args=if(spec.directionUid=="BOTH")arrayOf(campaignUid,node,node)else arrayOf(campaignUid,node)
            db.rawQuery("SELECT relation_uid,relation_class_uid,relation_kind_uid,source_event_uid,target_event_uid,committed_order,semantic_fingerprint FROM ${CampaignCausalGraphSchema.TABLE} WHERE campaign_uid=? AND $where ORDER BY committed_order,relation_ordinal,relation_uid",args).use{c->
                while(c.moveToNext()&&out.size<spec.maxEdges){
                    val kind=c.getString(2)
                    if(spec.relationKinds.isNotEmpty()&&kind !in spec.relationKinds)continue
                    val uid=c.getString(0)
                    if(!edges.add(uid))continue
                    val source=c.getString(3)
                    val target=c.getString(4)
                    out+=RetrievalRecord(uid,mapOf("relation_class_uid" to c.getString(1),"relation_kind_uid" to kind,"source_event_uid" to source,"target_event_uid" to target,"committed_order" to if(c.isNull(5))null else c.getLong(5)),"CAUSAL_RELATION:${c.getString(6)}")
                    val next=when(spec.directionUid){"OUTGOING"->target;"INCOMING"->source;else->if(node==source)target else source}
                    if(visited.add(next))frontier.add(next to depth+1)
                }
            }
        }
        return out
    }
}
class Phase42CausalQueryProvider(private val db:SQLiteDatabase,private val campaignUid:String):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{if(request.campaignUid!=campaignUid)throw VisibilityAuthorityFailure.CrossCampaign();if(request.operationUid!="TRAVERSE_CAUSAL")return StructuredRetrievalResult.Unsupported("OPERATION_UNSUPPORTED");val start=request.filters["start_event_uid"]?:return StructuredRetrievalResult.Unknown("START_EVENT_REQUIRED");val direction=request.filters["direction_uid"]?:"BOTH";val depth=request.filters["max_depth"]?.toIntOrNull()?.coerceIn(1,8)?:3;val kinds=request.filters["relation_kinds"]?.split(',')?.filter{it.isNotBlank()}?.toSet().orEmpty();val rows=Phase42CausalGraphRetriever(db,campaignUid).traverse(CausalTraversalSpec(start,direction,kinds,depth,request.limit));return if(rows.isEmpty())StructuredRetrievalResult.NoData else StructuredRetrievalResult.Value(rows,rows.size<request.limit)}
}
class Phase41TemporalQueryProvider(private val engine:TemporalEngine):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{val at=request.atOrder?:return StructuredRetrievalResult.Unknown("TEMPORAL_ORDER_REQUIRED");val subjectKind=request.filters["subject_kind_uid"]?:return StructuredRetrievalResult.Unknown("SUBJECT_KIND_REQUIRED");val subject=request.filters["subject_uid"]?:return StructuredRetrievalResult.Unknown("SUBJECT_REQUIRED");return when(val result=engine.query(TemporalQuery(request.campaignUid,request.operationUid,subjectKind,subject,at,request.audience,request.purpose,request.limit))){is TemporalResult.Value->StructuredRetrievalResult.Value(result.records.map{RetrievalRecord(it.recordUid,it.values,it.provenanceUid)},result.complete);TemporalResult.NoData->StructuredRetrievalResult.NoData;is TemporalResult.Denied->StructuredRetrievalResult.Denied(result.reasonUid);is TemporalResult.NotDisclosed->StructuredRetrievalResult.NotDisclosed(result.reasonUid);is TemporalResult.Unknown->StructuredRetrievalResult.Unknown(result.reasonUid);is TemporalResult.Unsupported->StructuredRetrievalResult.Unsupported(result.reasonUid);is TemporalResult.Corruption->StructuredRetrievalResult.Corruption(result.reasonUid)}}
}
