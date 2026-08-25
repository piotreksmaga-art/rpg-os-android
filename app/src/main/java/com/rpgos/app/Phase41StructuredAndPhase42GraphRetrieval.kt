package com.rpgos.app

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
        val depth=request.filters["max_depth"]?.toIntOrNull()?.coerceIn(1,8)?:3
        val kinds=request.filters["relation_kinds"]?.split(',')?.filter{it.isNotBlank()}?.toSet().orEmpty()
        val query=CanonicalCausalTraversalQuery(start,direction,kinds,depth,request.limit)
        return when(val result=protectedReads.causalTraversal(request.audience,request.purpose,query)){
            is ProtectedReadResult.Allow -> StructuredRetrievalResult.Value(result.value.map{row->
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
                RetrievalRecord(row.relationUid,values,"CAUSAL_RELATION:${row.semanticFingerprint}")
            },result.value.size<request.limit)
            ProtectedReadResult.NoData -> StructuredRetrievalResult.NoData
            is ProtectedReadResult.Deny -> StructuredRetrievalResult.Denied(result.reasonCode)
            is ProtectedReadResult.NotDisclosed -> StructuredRetrievalResult.NotDisclosed(result.reasonCode)
            is ProtectedReadResult.Unknown -> StructuredRetrievalResult.Unknown(result.reasonCode)
            is ProtectedReadResult.Corruption -> StructuredRetrievalResult.Corruption(result.reasonCode)
        }
    }
}

class Phase41TemporalQueryProvider(private val engine:TemporalEngine):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{val at=request.atOrder?:return StructuredRetrievalResult.Unknown("TEMPORAL_ORDER_REQUIRED");val subjectKind=request.filters["subject_kind_uid"]?:return StructuredRetrievalResult.Unknown("SUBJECT_KIND_REQUIRED");val subject=request.filters["subject_uid"]?:return StructuredRetrievalResult.Unknown("SUBJECT_REQUIRED");return when(val result=engine.query(TemporalQuery(request.campaignUid,request.operationUid,subjectKind,subject,at,request.audience,request.purpose,request.limit))){is TemporalResult.Value->StructuredRetrievalResult.Value(result.records.map{RetrievalRecord(it.recordUid,it.values,it.provenanceUid)},result.complete);TemporalResult.NoData->StructuredRetrievalResult.NoData;is TemporalResult.Denied->StructuredRetrievalResult.Denied(result.reasonUid);is TemporalResult.NotDisclosed->StructuredRetrievalResult.NotDisclosed(result.reasonUid);is TemporalResult.Unknown->StructuredRetrievalResult.Unknown(result.reasonUid);is TemporalResult.Unsupported->StructuredRetrievalResult.Unsupported(result.reasonUid);is TemporalResult.Corruption->StructuredRetrievalResult.Corruption(result.reasonUid)}}
}
