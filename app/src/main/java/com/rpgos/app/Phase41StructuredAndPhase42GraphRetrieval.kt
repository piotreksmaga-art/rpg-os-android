package com.rpgos.app

import java.security.MessageDigest

enum class RetrievalState { VALUE, NO_DATA, DENIED, NOT_DISCLOSED, UNKNOWN, UNSUPPORTED, CORRUPTION }
enum class RetrievalContinuation { COMPLETE, CURSOR, UNSUPPORTED }
enum class CursorSupport { NONE, SCOPED_OPAQUE }
data class StructuredRetrievalRequest(
    val requestUid:String,val campaignUid:String,val providerUid:String,val operationUid:String,val filters:Map<String,String>,val limit:Int,
    val audience:AudienceContext,val purpose:PurposeContext,val atOrder:Long?=null,val cursor:String?=null
){init{require(requestUid.isNotBlank()&&campaignUid.isNotBlank()&&providerUid.isNotBlank()&&operationUid.isNotBlank());require(limit in 1..200&&atOrder?.let{it>=0L}!=false);require(audience.campaignUid==campaignUid&&purpose.campaignUid==campaignUid){"RPGOS-P41:CROSS_CAMPAIGN"};require(filters.keys.none{it.isBlank()}&&filters.values.none{it.length>512})}
    fun fingerprint():String=listOf(campaignUid,providerUid,operationUid,filters.toSortedMap(),limit,atOrder,cursor,audience.audienceKindUid,audience.principal,purpose.purposeUid).joinToString("|")
    internal fun cursorScopeFingerprint():String=sha256(listOf(campaignUid,providerUid,operationUid,filters.toSortedMap(),limit,atOrder,audience.audienceKindUid,audience.principal,purpose.purposeUid).joinToString("|"))
}
data class RetrievalRecord(val recordUid:String,val values:Map<String,Any?>,val provenanceUid:String?=null){init{require(recordUid.isNotBlank())}}
sealed interface StructuredRetrievalResult{
    val state:RetrievalState
    data class Value(
        val records:List<RetrievalRecord>,
        val complete:Boolean,
        val nextCursor:String?=null,
        val continuation:RetrievalContinuation=if(complete)RetrievalContinuation.COMPLETE else RetrievalContinuation.UNSUPPORTED
    ):StructuredRetrievalResult{override val state=RetrievalState.VALUE}
    data object NoData:StructuredRetrievalResult{override val state=RetrievalState.NO_DATA}
    data class Denied(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.DENIED}
    data class NotDisclosed(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.NOT_DISCLOSED}
    data class Unknown(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.UNKNOWN}
    data class Unsupported(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.UNSUPPORTED}
    data class Corruption(val reasonUid:String):StructuredRetrievalResult{override val state=RetrievalState.CORRUPTION}
}
fun interface StructuredQueryProvider{fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult}
data class StructuredProviderBinding(
    val providerUid:String,
    val operations:Set<String>,
    val provider:StructuredQueryProvider,
    val cursorSupport:CursorSupport=CursorSupport.NONE,
    val orderingContractUid:String="PROVIDER_SEMANTIC_ORDER_V1"
){init{require(providerUid.isNotBlank()&&operations.isNotEmpty()&&operations.none{it.isBlank()});require(orderingContractUid.isNotBlank())}}

/** Expected provider/read failure. Programmer defects and fatal JVM failures cross this boundary unchanged. */
class StructuredProviderReadException(val reasonUid:String,cause:Throwable?=null):RuntimeException(reasonUid,cause){init{require(reasonUid.isNotBlank())}}

internal object ScopedRetrievalCursor{
    private const val PREFIX="RPGOS-CURSOR-V1"
    fun issue(request:StructuredRetrievalRequest,providerToken:String):String{
        require(providerToken.isNotBlank()&&!providerToken.contains('\n'))
        return "$PREFIX:${request.cursorScopeFingerprint()}:$providerToken"
    }
    fun providerToken(request:StructuredRetrievalRequest,cursor:String):String?{
        val split=cursor.split(':',limit=3)
        if(split.size!=3||split[0]!=PREFIX||split[1]!=request.copy(cursor=null).cursorScopeFingerprint())return null
        return split[2].takeIf{it.isNotBlank()}
    }
}

/** Created only by the trusted Core composition root; content and model output never register providers. */
internal class TrustedStructuredProviderRegistry private constructor(bindings:List<StructuredProviderBinding>){
    private val providers=bindings.associateBy{it.providerUid}
    init{require(providers.size==bindings.size){"RPGOS-P41:DUPLICATE_PROVIDER"}}
    fun binding(uid:String)=providers[uid]
    companion object{fun fromCore(bindings:List<StructuredProviderBinding>)=TrustedStructuredProviderRegistry(bindings.toList())}
}

class StructuredSqlRetriever internal constructor(private val registry:TrustedStructuredProviderRegistry){
    internal constructor(bindings:List<StructuredProviderBinding>):this(TrustedStructuredProviderRegistry.fromCore(bindings))
    fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        val binding=registry.binding(request.providerUid)?:return StructuredRetrievalResult.Unsupported("PROVIDER_NOT_REGISTERED")
        if(request.operationUid !in binding.operations)return StructuredRetrievalResult.Unsupported("OPERATION_NOT_ALLOWLISTED")
        val providerCursor=if(request.cursor!=null){
            if(binding.cursorSupport==CursorSupport.NONE)return StructuredRetrievalResult.Unsupported("CURSOR_UNSUPPORTED")
            ScopedRetrievalCursor.providerToken(request,request.cursor)?:return StructuredRetrievalResult.Unsupported("CURSOR_SCOPE_MISMATCH")
        }else null
        val providerRequest=if(providerCursor==null)request else request.copy(cursor=providerCursor)
        return try{when(val result=binding.provider.retrieve(providerRequest)){
            is StructuredRetrievalResult.Value->{
                if(result.records.isEmpty())StructuredRetrievalResult.NoData
                else if(result.records.map{it.recordUid}.distinct().size!=result.records.size)StructuredRetrievalResult.Corruption("DUPLICATE_RECORD_UID")
                else if(result.records.size>request.limit&&result.nextCursor!=null)StructuredRetrievalResult.Corruption("PROVIDER_EXCEEDED_LIMIT_WITH_UNSAFE_CURSOR")
                else if(result.records.size>request.limit)StructuredRetrievalResult.Value(
                    result.records.take(request.limit).map{it.copy(values=it.values.toSortedMap())},false,null,RetrievalContinuation.UNSUPPORTED
                )
                else if(result.complete&&result.nextCursor!=null)StructuredRetrievalResult.Corruption("CURSOR_ON_COMPLETE_RESULT")
                else if(!result.complete&&binding.cursorSupport==CursorSupport.SCOPED_OPAQUE&&result.nextCursor==null)StructuredRetrievalResult.Corruption("MISSING_CONTINUATION_CURSOR")
                else if(result.nextCursor?.startsWith("RPGOS-CURSOR-V1:")==true)StructuredRetrievalResult.Corruption("PROVIDER_RETURNED_SCOPED_CURSOR")
                else{
                    val cursor=when{
                        result.complete->null
                        binding.cursorSupport==CursorSupport.NONE->null
                        else->ScopedRetrievalCursor.issue(request,result.nextCursor!!)
                    }
                    val continuation=when{result.complete->RetrievalContinuation.COMPLETE;cursor!=null->RetrievalContinuation.CURSOR;else->RetrievalContinuation.UNSUPPORTED}
                    result.copy(records=result.records.map{it.copy(values=it.values.toSortedMap())},nextCursor=cursor,continuation=continuation)
                }
            }
            else->result
        }}catch(_:VisibilityAuthorityFailure.CrossCampaign){throw VisibilityAuthorityFailure.CrossCampaign()}
        catch(failure:StructuredProviderReadException){StructuredRetrievalResult.Corruption(failure.reasonUid)}
    }
}

data class CausalTraversalSpec(val startEventUid:String,val directionUid:String,val relationKinds:Set<String> = emptySet(),val maxDepth:Int=3,val maxEdges:Int=100){init{require(startEventUid.isNotBlank());require(directionUid in setOf("OUTGOING","INCOMING","BOTH"));require(maxDepth in 1..8&&maxEdges in 1..200)}}

/** Phase42 is a thin retrieval adapter. CampaignCausalGraph remains the graph owner; Phase38 owns disclosure. */
class Phase42CausalQueryProvider(
    private val protectedReads:ProtectedCampaignReadRepository,
    private val campaignUid:String
):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        if(request.campaignUid!=campaignUid)throw VisibilityAuthorityFailure.CrossCampaign()
        if(request.operationUid!="TRAVERSE_CAUSAL")return StructuredRetrievalResult.Unsupported("OPERATION_UNSUPPORTED")
        val start=request.filters["start_event_uid"]?:return StructuredRetrievalResult.Unknown("START_EVENT_REQUIRED")
        val direction=request.filters["direction_uid"]?:"BOTH"
        val requestedDepth=request.filters["max_depth"]
        val depth=when{
            requestedDepth==null->3
            requestedDepth.toIntOrNull()==null->return StructuredRetrievalResult.Unknown("MAX_DEPTH_INVALID")
            requestedDepth.toInt() !in 1..8->return StructuredRetrievalResult.Unsupported("MAX_DEPTH_UNSUPPORTED")
            else->requestedDepth.toInt()
        }
        val kinds=request.filters["relation_kinds"]?.split(',')?.filter{it.isNotBlank()}?.toSet().orEmpty()
        if(direction !in setOf("OUTGOING","INCOMING","BOTH"))return StructuredRetrievalResult.Unknown("DIRECTION_UNSUPPORTED")

        // Visibility constrains frontier expansion. Every owner read is one hop; an undisclosed endpoint is never enqueued.
        val frontier=ArrayDeque<Pair<String,Int>>()
        frontier.add(start to 0)
        val visited=linkedSetOf(start)
        val seenRelations=linkedSetOf<String>()
        val projected=mutableListOf<ProjectedCausalRelation>()
        while(frontier.isNotEmpty()&&projected.size<request.limit){
            val(node,nodeDepth)=frontier.removeFirst()
            if(nodeDepth>=depth)continue
            val remaining=request.limit-projected.size
            val oneHop=CanonicalCausalTraversalQuery(node,direction,kinds,1,remaining)
            when(val result=protectedReads.causalTraversal(request.audience,request.purpose,oneHop)){
                is ProtectedReadResult.Allow -> {
                    if(node==start){
                        val startDisclosed=result.value.any{row->when(direction){
                            "OUTGOING"->row.source.state==ProjectionDataState.DISCLOSED&&row.source.eventUid==start
                            "INCOMING"->row.target.state==ProjectionDataState.DISCLOSED&&row.target.eventUid==start
                            else->(row.source.state==ProjectionDataState.DISCLOSED&&row.source.eventUid==start)||(row.target.state==ProjectionDataState.DISCLOSED&&row.target.eventUid==start)
                        }}
                        if(!startDisclosed)return StructuredRetrievalResult.NotDisclosed("START_EVENT_NOT_DISCLOSED")
                    }
                    result.value.forEach{row->
                    if(projected.size>=request.limit||!seenRelations.add(row.relationUid))return@forEach
                    projected+=row
                    val next=when(direction){
                        "OUTGOING"->row.target
                        "INCOMING"->row.source
                        else->when{
                            row.source.state==ProjectionDataState.DISCLOSED&&row.source.eventUid==node->row.target
                            row.target.state==ProjectionDataState.DISCLOSED&&row.target.eventUid==node->row.source
                            else->null
                        }
                    }
                    val nextUid=next?.eventUid
                    if(next?.state==ProjectionDataState.DISCLOSED&&nextUid!=null&&visited.add(nextUid))frontier.add(nextUid to nodeDepth+1)
                    }
                }
                ProtectedReadResult.NoData -> Unit
                is ProtectedReadResult.Deny -> return StructuredRetrievalResult.Denied(result.reasonCode)
                is ProtectedReadResult.NotDisclosed -> return StructuredRetrievalResult.NotDisclosed(result.reasonCode)
                is ProtectedReadResult.Unknown -> return StructuredRetrievalResult.Unknown(result.reasonCode)
                is ProtectedReadResult.Corruption -> return StructuredRetrievalResult.Corruption(result.reasonCode)
            }
        }
        if(projected.isEmpty())return StructuredRetrievalResult.NoData
        val records=projected.map{row->
            val values=linkedMapOf<String,Any?>(
                "relation_class_uid" to row.relationClassUid,
                "relation_kind_uid" to row.relationKindUid,
                "source_disclosure_state" to row.source.state.name,
                "target_disclosure_state" to row.target.state.name,
                "committed_order" to row.committedOrder
            )
            if(row.source.state==ProjectionDataState.DISCLOSED){
                values["source_event_uid"]=row.source.eventUid
                values["source_subject_kind_uid"]=row.source.subjectKindUid
                values["source_subject_uid"]=row.source.subjectUid
            }
            if(row.target.state==ProjectionDataState.DISCLOSED){
                values["target_event_uid"]=row.target.eventUid
                values["target_subject_kind_uid"]=row.target.subjectKindUid
                values["target_subject_uid"]=row.target.subjectUid
            }
            val fullyDisclosed=row.source.state==ProjectionDataState.DISCLOSED&&row.target.state==ProjectionDataState.DISCLOSED
            // relationUid is itself the authorized relation carrier; canonical semantic fingerprint is not disclosed on partial rows.
            val provenance=if(fullyDisclosed)"CAUSAL_RELATION:${row.semanticFingerprint}" else "CAUSAL_RELATION_PROJECTED"
            RetrievalRecord(row.relationUid,values,provenance)
        }
        return StructuredRetrievalResult.Value(records,frontier.isEmpty(),continuation=if(frontier.isEmpty())RetrievalContinuation.COMPLETE else RetrievalContinuation.UNSUPPORTED)
    }
}

private fun sha256(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}

class Phase41TemporalQueryProvider(private val engine:TemporalEngine):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{val at=request.atOrder?:return StructuredRetrievalResult.Unknown("TEMPORAL_ORDER_REQUIRED");val subjectKind=request.filters["subject_kind_uid"]?:return StructuredRetrievalResult.Unknown("SUBJECT_KIND_REQUIRED");val subject=request.filters["subject_uid"]?:return StructuredRetrievalResult.Unknown("SUBJECT_REQUIRED");return when(val result=engine.query(TemporalQuery(request.campaignUid,request.operationUid,subjectKind,subject,at,request.audience,request.purpose,request.limit))){is TemporalResult.Value->StructuredRetrievalResult.Value(result.records.map{RetrievalRecord(it.recordUid,it.values,it.provenanceUid)},result.complete);TemporalResult.NoData->StructuredRetrievalResult.NoData;is TemporalResult.Denied->StructuredRetrievalResult.Denied(result.reasonUid);is TemporalResult.NotDisclosed->StructuredRetrievalResult.NotDisclosed(result.reasonUid);is TemporalResult.Unknown->StructuredRetrievalResult.Unknown(result.reasonUid);is TemporalResult.Unsupported->StructuredRetrievalResult.Unsupported(result.reasonUid);is TemporalResult.Corruption->StructuredRetrievalResult.Corruption(result.reasonUid)}}
}
