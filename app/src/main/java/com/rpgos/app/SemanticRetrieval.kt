package com.rpgos.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock

const val BEKKO_STRUCTURED_PROVIDER_UID="RPGOS-CORE:BEKKO-SEMANTIC"
const val BEKKO_OPERATION_MEMORY="SEMANTIC_MEMORY"
const val BEKKO_OPERATION_WORLD_PACK="SEMANTIC_WORLD_PACK"
const val BEKKO_OPERATION_RELATED="SEMANTIC_RELATED"
const val SEMANTIC_NAMESPACE_CAMPAIGN="CAMPAIGN_MEMORY"
const val SEMANTIC_NAMESPACE_WORLD_PACK="WORLD_PACK"
internal fun semanticWorldPackRecordUid(option:CharacterCreationDefinitionOption)=
    "WORLDPACK:${option.kind}:${option.definitionUid}:${option.dimensionUid.orEmpty()}"
internal fun semanticWorldPackProjection(
    campaignUid:String,audienceUid:String,purposeUid:String,option:CharacterCreationDefinitionOption
):SemanticDocumentProjection{
    val uid=semanticWorldPackRecordUid(option)
    val text=listOf(option.kind.name,option.definitionUid,option.displayName,option.dimensionUid,option.minimumValue,option.maximumValue)
        .filterNotNull().joinToString(" ")
    return SemanticDocumentProjection(
        campaignUid,SEMANTIC_NAMESPACE_WORLD_PACK,audienceUid,purposeUid,uid,"WORLD_PACK_${option.kind}","DEFINITION",
        0,1,semanticSha256(text),0,text,principalUid="GLOBAL",holderSetFingerprint="GLOBAL"
    )
}
private const val SEMANTIC_QUERY_MAX_CHARS=1024 // early abuse bound; llama.cpp enforces the exact 256-token limit
private const val SEMANTIC_DOCUMENT_CHUNK_CHARS=160
private const val SEMANTIC_DOCUMENT_CHUNK_OVERLAP_CHARS=24

internal data class CanonicalWorldElementSemanticState(
    val campaignUid:String,val subjectUid:String,val presentationFacts:Map<String,String>,val sourceAsOfOrder:Long
){init{
    require(campaignUid.isNotBlank()&&subjectUid.isNotBlank()&&sourceAsOfOrder>=0)
    require(presentationFacts.isNotEmpty()&&presentationFacts.keys.all{it in setOf(
        CampaignWorldFacts.KIND,CampaignWorldFacts.NAME,CampaignWorldFacts.CATEGORY,CampaignWorldFacts.PARENT,
        CampaignWorldFacts.AFFORDANCE,CampaignWorldFacts.TOPOLOGY,CampaignWorldFacts.AUDIENCE_SCOPE
    )})
}}

internal data class CanonicalWorldEpistemicSemanticState(
    val campaignUid:String,val truthUid:String,val epistemicKind:TruthKind,val subjectUid:String?,
    val predicate:String,val objectValue:String?,val perspectiveUid:String?,val narrativeText:String?,
    val sourceAsOfOrder:Long
){init{
    require(campaignUid.isNotBlank()&&truthUid.isNotBlank()&&predicate.isNotBlank()&&sourceAsOfOrder>=0)
    require(epistemicKind in setOf(TruthKind.BELIEF,TruthKind.NARRATIVE))
}}

class SemanticStructuredQueryProvider(
    private val embeddings:EmbeddingProviderPort,
    private val index:SemanticIndexPort,
    private val scopeResolver:SemanticRuntimeScopeResolver=SemanticRuntimeScopeResolver{request,_->
        SemanticRuntimeScope(
            historyGenerationUid=null,
            principalUid=request.audience.principal?.uid?:request.audience.audienceKindUid,
            holderSetFingerprint="GLOBAL",
            accessPolicyVersion=0,
            activePlayerUid=request.audience.principal?.uid
        )
    },
    private val canonicalRehydration:SemanticCanonicalRehydrationPort?=null
):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        val namespace=when(request.operationUid){
            BEKKO_OPERATION_MEMORY,BEKKO_OPERATION_RELATED->SEMANTIC_NAMESPACE_CAMPAIGN
            BEKKO_OPERATION_WORLD_PACK->SEMANTIC_NAMESPACE_WORLD_PACK
            else->return StructuredRetrievalResult.Unsupported("BEKKO_OPERATION_UNSUPPORTED")
        }
        val query=request.filters["query_text"]?.trim()?.takeIf{it.isNotEmpty()}
            ?:return StructuredRetrievalResult.Unknown("BEKKO_QUERY_REQUIRED")
        if(query.length>SEMANTIC_QUERY_MAX_CHARS)return StructuredRetrievalResult.Unsupported("BEKKO_QUERY_TOO_LARGE")
        val availability=embeddings.availability()
        if(availability.state!=EmbeddingAvailabilityState.READY)return StructuredRetrievalResult.Unsupported(availability.reasonUid)
        val embedded=embeddings.embedBatch(EmbeddingRequest(request.requestUid,listOf(query),256))
        val source=(embedded as? EmbeddingBatchResult.Success)?.vectors?.singleOrNull()
            ?:return StructuredRetrievalResult.Unsupported((embedded as? EmbeddingBatchResult.Failure)?.reasonUid?:"BEKKO_QUERY_EMBEDDING_FAILED")
        val vector=runCatching{matryoshkaL2(source,index.version.dimensions)}.getOrElse{
            return StructuredRetrievalResult.Corruption("BEKKO_QUERY_VECTOR_INVALID")
        }
        val purposeUid=request.purpose.purposeUid
        val audienceUid=request.audience.audienceKindUid
        val at=request.atOrder?:index.checkpoint(request.campaignUid)
        val kinds=request.filters["record_kinds"]?.split(',')?.filter{it.isNotBlank()}?.toSet().orEmpty()
        val explicitMinimum=request.filters["minimum_score"]?.toFloatOrNull()?.coerceIn(-1f,1f)
        val runtimeScope=scopeResolver.resolve(request,namespace)
        val exactScopeAuthorized=runtimeScope.historyGenerationUid!=null&&runtimeScope.principalUid.isNotBlank()&&
            runtimeScope.holderSetFingerprint.isNotBlank()&&runtimeScope.accessPolicyVersion>0
        // Production projections are already isolated by history, principal, holder set,
        // policy, active player and projector version. Scan that exact legal scope directly;
        // legacy/test scopes retain the explicit UID authorization path.
        val authorized=if(exactScopeAuthorized)emptySet() else
            index.authorizedRecordUids(request.campaignUid,namespace,audienceUid,purposeUid,at)
        if(!exactScopeAuthorized&&authorized.isEmpty())return StructuredRetrievalResult.NoData
        val scopeRequest=SemanticSearchRequest(
            request.campaignUid,namespace,audienceUid,purposeUid,at,authorized,kinds,vector,
            request.limit,explicitMinimum?:0.25f,
            historyGenerationUid=runtimeScope.historyGenerationUid,
            principalUid=runtimeScope.principalUid,holderSetFingerprint=runtimeScope.holderSetFingerprint,
            accessPolicyVersion=runtimeScope.accessPolicyVersion,activePlayerUid=runtimeScope.activePlayerUid,
            projectionVersionUid=runtimeScope.projectionVersionUid,exactScopeAuthorized=exactScopeAuthorized
        )
        var candidates=searchAndVerify(scopeRequest)
        if(candidates.isEmpty()&&explicitMinimum==null)candidates=searchAndVerify(SemanticSearchRequest(
            request.campaignUid,namespace,audienceUid,purposeUid,at,authorized,kinds,vector,
            minOf(3,request.limit),0.18f,
            historyGenerationUid=runtimeScope.historyGenerationUid,
            principalUid=runtimeScope.principalUid,holderSetFingerprint=runtimeScope.holderSetFingerprint,
            accessPolicyVersion=runtimeScope.accessPolicyVersion,activePlayerUid=runtimeScope.activePlayerUid,
            projectionVersionUid=runtimeScope.projectionVersionUid,exactScopeAuthorized=exactScopeAuthorized
        ))
        if(explicitMinimum==null)candidates=retainSemanticRelevanceBand(candidates,0.25f)
        if(candidates.isEmpty())return StructuredRetrievalResult.NoData
        val verifiedRequest=scopeRequest.copy(
            authorizedRecordUids=candidates.mapTo(linkedSetOf()){it.canonicalRecordUid},
            exactScopeAuthorized=false
        )
        val rehydrated=canonicalRehydration?.rehydrate(verifiedRequest,candidates)
        if(canonicalRehydration!=null&&rehydrated.isNullOrEmpty())return StructuredRetrievalResult.NoData
        val records=candidates.mapNotNull{candidate->
            val current=rehydrated?.get(candidate.canonicalRecordUid)
            if(rehydrated!=null&&current==null)return@mapNotNull null
            if(current!=null&&(current.sourceVersion!=candidate.sourceVersion||current.sourceFingerprint!=candidate.sourceFingerprint||current.sourceAsOfOrder!=candidate.sourceAsOfOrder))return@mapNotNull null
            val kind=current?.recordKindUid?:candidate.recordKindUid
            val epistemic=current?.epistemicStateUid?:candidate.epistemicStateUid
            val version=current?.sourceVersion?:candidate.sourceVersion
            val evidence=current?.chunkEvidence?:candidate.chunkEvidence
            val text=current?.projectedText?:evidence.joinToString("\n"){it.projectedText}
            RetrievalRecord(
            candidate.canonicalRecordUid,
            linkedMapOf(
                "semantic_score" to candidate.score.toDouble(),
                "record_kind_uid" to kind,
                "epistemic_state_uid" to epistemic,
                "source_version" to version,
                "chunk_ordinals" to evidence.joinToString(","){it.chunkOrdinal.toString()},
                "projected_text" to text,
                "projection_fingerprints" to evidence.joinToString(","){it.projectedTextFingerprint},
                "candidate_only" to true
            ),
            current?.projectionBoundaryUid?:"BEKKO:${candidate.sourceFingerprint}:${candidate.indexVersion.modelSha256}"
        )}.take(request.limit)
        return if(records.isEmpty())StructuredRetrievalResult.NoData else StructuredRetrievalResult.Value(records,true)
    }

    private fun searchAndVerify(request:SemanticSearchRequest):List<SemanticCandidate>{
        val ranked=index.searchAuthorized(request)
        if(ranked.isEmpty())return emptyList()
        val narrowed=request.copy(
            authorizedRecordUids=ranked.mapTo(linkedSetOf()){it.canonicalRecordUid},
            exactScopeAuthorized=false
        )
        val projections=index.currentProjections(narrowed)
        return ranked.mapNotNull{candidate->
        val projection=projections[candidate.canonicalRecordUid] ?: return@mapNotNull null
        if(projection.sourceVersion!=candidate.sourceVersion) return@mapNotNull null
        if(projection.sourceFingerprint!=candidate.sourceFingerprint) return@mapNotNull null
        if(projection.sourceAsOfOrder!=candidate.sourceAsOfOrder) return@mapNotNull null
        if(request.historyGenerationUid!=null&&projection.historyGenerationUid!=request.historyGenerationUid) return@mapNotNull null
        val requestedPrincipal=request.principalUid
        if(requestedPrincipal.isNotBlank()&&projection.principalUid.isNotBlank()
            &&projection.principalUid!="GLOBAL"&&projection.principalUid!=requestedPrincipal) return@mapNotNull null
        if(request.holderSetFingerprint.isNotBlank()&&projection.holderSetFingerprint.isNotBlank()
            &&projection.holderSetFingerprint!=request.holderSetFingerprint&&projection.holderSetFingerprint!="GLOBAL") return@mapNotNull null
        if(request.accessPolicyVersion>0L&&projection.accessPolicyVersion!=request.accessPolicyVersion&&projection.accessPolicyVersion!=0L) return@mapNotNull null
        if(request.projectionVersionUid.isNotBlank()&&projection.projectionVersionUid!=request.projectionVersionUid) return@mapNotNull null
        val requestedActive=request.activePlayerUid
        if(requestedActive!=null&&requestedActive.isNotBlank()&&projection.activePlayerUid!=null&&projection.activePlayerUid.isNotBlank()
            &&projection.activePlayerUid!=requestedActive) return@mapNotNull null
            candidate.copy(
            sourceVersion=projection.sourceVersion,
            sourceFingerprint=projection.sourceFingerprint,
            sourceAsOfOrder=projection.sourceAsOfOrder
        )
        }
    }

}

/** Keeps semantic retrieval from padding a prompt with weak neighbours merely because topK has
 * room. Explicit developer thresholds remain exact; this band is only the production default. */
internal fun retainSemanticRelevanceBand(candidates:List<SemanticCandidate>,floor:Float):List<SemanticCandidate>{
    if(candidates.isEmpty())return emptyList()
    val cutoff=maxOf(floor,candidates.maxOf{it.score.value}-0.035f)
    return candidates.filter{it.score>=cutoff}
}

/**
 * Typed structured fallback for commits newer than the semantic checkpoint. It never embeds,
 * never reads an unscoped row and never mutates canonical state.
 */
class SemanticHotTailProvider(
    private val repository:UnifiedGameRepository,
    private val projector:SemanticDocumentProjector
):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        if(request.operationUid !in setOf(BEKKO_OPERATION_MEMORY,BEKKO_OPERATION_RELATED))return StructuredRetrievalResult.NoData
        val query=request.filters["query_text"]?.trim()?.takeIf{it.isNotBlank()}
            ?:return StructuredRetrievalResult.Unknown("BEKKO_QUERY_REQUIRED")
        val queryTerms=terms(query)
        if(queryTerms.isEmpty())return StructuredRetrievalResult.NoData
        val after=request.filters["index_checkpoint"]?.toLongOrNull()?.coerceAtLeast(0)?:0L
        val at=request.atOrder?:Long.MAX_VALUE
        val documents=repository.infrastructureReplayTailAfterLimited(after,100).asSequence()
            .filter{it.commitOrder<=at}
            .flatMap{projector.project(it,request.audience,request.purpose).asSequence()}
            .filter{it.namespaceUid==SEMANTIC_NAMESPACE_CAMPAIGN}
            .toList()
        data class Match(val document:SemanticDocumentProjection,val score:Double)
        val matches=documents.mapNotNull{document->
            val documentTerms=terms(document.text)
            val overlap=queryTerms.count{it in documentTerms}
            val exact=document.text.contains(query,ignoreCase=true)
            val score=when{exact->1.0;overlap==0->return@mapNotNull null;else->overlap.toDouble()/queryTerms.size}
            Match(document,score)
        }.groupBy{it.document.canonicalRecordUid}.map{(_,group)->
            group.sortedWith(compareByDescending<Match>{it.score}.thenBy{it.document.chunkOrdinal}).first()
        }.sortedWith(compareByDescending<Match>{it.document.asOfOrder}.thenByDescending{it.score}.thenBy{it.document.canonicalRecordUid})
            .take(request.limit)
        if(matches.isEmpty())return StructuredRetrievalResult.NoData
        return StructuredRetrievalResult.Value(matches.map{match->RetrievalRecord(
            match.document.canonicalRecordUid,
            linkedMapOf(
                "semantic_score" to match.score,
                "record_kind_uid" to match.document.recordKindUid,
                "epistemic_state_uid" to match.document.epistemicStateUid,
                "source_version" to match.document.sourceVersion,
                "projected_text" to match.document.text,
                "hot_tail" to true,
                "candidate_only" to true
            ),
            "BEKKO-HOT-TAIL:${match.document.sourceFingerprint}"
        )},true)
    }

    private fun terms(value:String):Set<String> = value.lowercase(java.util.Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}_:-]+"))
        .asSequence().map{it.trim()}.filter{it.length>=2}.take(128).toSet()
}

class HybridSemanticStructuredQueryProvider(
    private val semantic:SemanticStructuredQueryProvider,
    private val hotTail:SemanticHotTailProvider,
    private val index:SemanticIndexPort
):StructuredQueryProvider{
    override fun retrieve(request:StructuredRetrievalRequest):StructuredRetrievalResult{
        val semanticResult=semantic.retrieve(request)
        val tailRequest=request.copy(filters=request.filters+("index_checkpoint" to index.checkpoint(request.campaignUid).toString()))
        val tailResult=hotTail.retrieve(tailRequest)
        val semanticRecords=(semanticResult as? StructuredRetrievalResult.Value)?.records.orEmpty()
        val tailRecords=(tailResult as? StructuredRetrievalResult.Value)?.records.orEmpty()
        val combined=(tailRecords+semanticRecords).distinctBy{it.recordUid}.take(request.limit)
        if(combined.isNotEmpty())return StructuredRetrievalResult.Value(combined,true)
        return when{
            semanticResult !is StructuredRetrievalResult.NoData->semanticResult
            else->tailResult
        }
    }
}

class BekkoDirectorContextScout(
    private val embeddings:EmbeddingProviderPort,
    private val index:SemanticIndexPort,
    private val runtimeScope:(String)->SemanticRuntimeScope={SemanticRuntimeScope(null,"LOCAL_GM","GLOBAL",0,null)},
    private val canonicalRehydration:SemanticCanonicalRehydrationPort?=null
):DirectorContextScoutPort{
    override fun enrich(trigger:DirectorTrigger,context:DirectorContextEnvelope):DirectorContextEnvelope{
        if(context.projectedRecordUids.isEmpty()||embeddings.availability().state!=EmbeddingAvailabilityState.READY)return context
        val text=(listOf(trigger.kind.name)+trigger.semanticEvidenceUids+context.strategicSummarySegments).joinToString(" ").take(2048)
        val result=embeddings.embedBatch(EmbeddingRequest("BEKKO-DIRECTOR:${trigger.triggerUid}",listOf(text),512)) as? EmbeddingBatchResult.Success?:return context
        val query=matryoshkaL2(result.vectors.single(),index.version.dimensions)
        val scope=runtimeScope(trigger.campaignUid)
        val request=SemanticSearchRequest(
            trigger.campaignUid,SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
            trigger.atCommittedOrder,context.projectedRecordUids,queryVector=query,topK=16,minimumScore=0.25f,
            historyGenerationUid=scope.historyGenerationUid,principalUid=scope.principalUid,
            holderSetFingerprint=scope.holderSetFingerprint,accessPolicyVersion=scope.accessPolicyVersion,
            activePlayerUid=scope.activePlayerUid,projectionVersionUid=scope.projectionVersionUid
        )
        val current=index.currentProjections(request)
        val candidates=index.searchAuthorized(request).filter{candidate->
            val projection=current[candidate.canonicalRecordUid]?:return@filter false
            projection.sourceVersion==candidate.sourceVersion&&
                projection.sourceFingerprint==candidate.sourceFingerprint&&
                projection.sourceAsOfOrder==candidate.sourceAsOfOrder&&
                (request.historyGenerationUid==null||projection.historyGenerationUid==request.historyGenerationUid)
        }
        if(candidates.isEmpty())return context
        val rehydrated=canonicalRehydration?.rehydrate(request,candidates)
        val acceptedOwners:Map<String,CanonicallyRehydratedSemanticRecord> = if(canonicalRehydration==null)emptyMap() else candidates.mapNotNull{candidate->
            val currentRecord=rehydrated?.get(candidate.canonicalRecordUid)?:return@mapNotNull null
            currentRecord.takeIf{currentRecord.sourceVersion==candidate.sourceVersion&&
                currentRecord.sourceFingerprint==candidate.sourceFingerprint&&
                currentRecord.sourceAsOfOrder==candidate.sourceAsOfOrder}?.let{candidate.canonicalRecordUid to it}
        }.toMap()
        val accepted=candidates.filter{canonicalRehydration==null||it.canonicalRecordUid in acceptedOwners}
        if(accepted.isEmpty())return context
        return context.copy(strategicSummarySegments=context.strategicSummarySegments+accepted.map{candidate->
            val owner=acceptedOwners[candidate.canonicalRecordUid]
            // The Director may only receive text rehydrated from the current canonical owner.
            // Sidecar text is deliberately never used here. Keep every segment bounded so a
            // broad semantic match cannot consume the strategic context budget by itself.
            val canonicalText=owner?.projectedText
                ?.replace(Regex("\\s+")," ")
                ?.trim()
                ?.take(512)
            "BEKKO_CANDIDATE uid=${owner?.canonicalRecordUid?:candidate.canonicalRecordUid} " +
                "score=${"%.4f".format(java.util.Locale.ROOT,candidate.score)} " +
                "kind=${owner?.recordKindUid?:candidate.recordKindUid} " +
                "epistemic=${owner?.epistemicStateUid?:candidate.epistemicStateUid}" +
                canonicalText?.takeIf{it.isNotBlank()}?.let{" text=$it"}.orEmpty()
        })
    }
}

/** Produces audience-specific text only after canonical replay evidence exists. */
internal class CommittedReplaySemanticProjector(
    private val activePlayerUid:()->String?={null},
    private val historyGenerationUid:()->HistoryGenerationUid?={null},
    private val accessPolicyVersion:Long=1L,
    private val visibility:VisibilityAuthorityService=VisibilityAuthorityService()
):SemanticDocumentProjector{
    private companion object{
        val PLAYER_OWNED_DOMAIN_KINDS=setOf("PLAYER","CHARACTER",KnowledgeHolderKinds.PLAYER_CHARACTER)
    }
    private data class ProjectedRecord(
        val recordUid:String,
        val recordKindUid:String,
        val epistemicStateUid:String,
        val text:String
    )

    fun boundToActivePlayer(playerUid:String?,generationUid:HistoryGenerationUid?=historyGenerationUid())=
        CommittedReplaySemanticProjector({playerUid},{generationUid},accessPolicyVersion,visibility)

    override fun project(source:Any,audience:AudienceContext,purpose:PurposeContext):List<SemanticDocumentProjection>{
        val replay=source as? CommittedReplayPayload?:return emptyList()
        require(replay.identity.campaignUid==audience.campaignUid&&replay.identity.campaignUid==purpose.campaignUid)
        val controlledPlayer=activePlayerUid()
        val principalUid=audience.principal?.uid?:audience.audienceKindUid
        val holderFingerprint=semanticHolderSetFingerprint(audience.audienceKindUid,principalUid,controlledPlayer)
        val trusted=when(audience.audienceKindUid){
            AudienceKinds.GM_RUNTIME->Phase38RuntimeAuthority.privileged(audience,Phase38RuntimeAuthority.PRIV_GM)
            AudienceKinds.PLAYER,AudienceKinds.PLAYER_CHARACTER->Phase38RuntimeAuthority.application(
                audience,controlledSubjectUids=controlledPlayer?.let(::setOf).orEmpty()
            )
            else->null
        }
        fun disclosed(subjectKind:String,subjectUid:String):Boolean{
            if(trusted==null)return false
            val request=VisibilityRequest(
                audience,purpose,VisibilitySubjectRef(replay.identity.campaignUid,subjectKind,subjectUid)
            )
            return visibility.project(request,trusted){true}.value==true
        }
        val records=mutableListOf<ProjectedRecord>()
        replay.changeSet.eventIntents.forEach{event->
            val owned=controlledPlayer!=null&&event.actorRef?.uid==controlledPlayer&&
                event.actorRef.kindUid in PLAYER_OWNED_DOMAIN_KINDS
            val visibilityKind=if(owned)VisibilitySubjectKinds.PLAYER_STATE else VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL
            val visibilityUid=if(owned)requireNotNull(controlledPlayer) else event.eventIntentUid
            if(!disclosed(visibilityKind,visibilityUid))return@forEach
            val gm=audience.audienceKindUid==AudienceKinds.GM_RUNTIME
            val description=if(gm){
                val actor=event.actorRef?.let{"${it.kindUid}:${it.uid}"}?:"UNKNOWN_ACTOR"
                val targets=event.targetRefs.joinToString(","){"${it.kindUid}:${it.uid}"}
                val effect=(event.payload as? DomainEffectEventIntentPayload)?.let{"${it.subject.kindUid}:${it.subject.uid}:${it.effectKindUid}"}.orEmpty()
                "Actor $actor wykonał ${event.eventKindUid}; cele: $targets; efekt: $effect"
            }else "Kontrolowana postać wykonała ${event.eventKindUid}"
            records+=ProjectedRecord("EVENT:${event.eventIntentUid}",event.eventKindUid,"FACT",description)
        }
        replay.changeSet.changes.forEach{change->
            val payload=change.payload
            // Phase38 access/binding records are authority metadata. They must never be
            // re-labelled as player state or serialized into semantic gameplay documents.
            if(payload is AccessAuthorityChange)return@forEach
            if(payload is CampaignTruthChange&&payload.subjectUid!=null&&payload.predicate in CampaignWorldFacts.ALL)return@forEach
            val subject=subject(payload)
            val owned=controlledPlayer!=null&&subject?.uid==controlledPlayer&&subject.kindUid in PLAYER_OWNED_DOMAIN_KINDS
            val visibilityKind=when{
                payload is CampaignTruthChange->VisibilitySubjectKinds.CAMPAIGN_TRUTH
                owned->VisibilitySubjectKinds.PLAYER_STATE
                else->VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL
            }
            val visibilityUid=when{
                payload is CampaignTruthChange->payload.truthUid
                owned->requireNotNull(controlledPlayer)
                else->change.changeUid
            }
            if(!disclosed(visibilityKind,visibilityUid))return@forEach
            records+=ProjectedRecord(
                "CHANGE:${change.changeUid}",
                change.changeKindUid,
                when(payload){is CampaignTruthChange->payload.kind.name else->"FACT"},
                if(owned&&audience.audienceKindUid!=AudienceKinds.GM_RUNTIME)"Zmiana stanu kontrolowanej postaci ${change.changeKindUid}"
                else describe(payload)
            )
        }
        return records.flatMap{projected->
            val (recordUid,kind,epistemic,text)=projected
            val recordFingerprint=semanticSha256("${replay.semanticFingerprint}|$recordUid|$kind|$epistemic|$text")
            chunk(text).mapIndexed{ordinal,part->SemanticDocumentProjection(
                replay.identity.campaignUid,SEMANTIC_NAMESPACE_CAMPAIGN,audience.audienceKindUid,purpose.purposeUid,
                recordUid,kind,epistemic,replay.commitOrder,replay.commitOrder,
                recordFingerprint,ordinal,part,
                historyGenerationUid=historyGenerationUid(),principalUid=principalUid,activePlayerUid=controlledPlayer,
                holderSetFingerprint=holderFingerprint,accessPolicyVersion=accessPolicyVersion
            )}
        }
    }

    private fun subject(payload:PlayerDomainChangePayload):DomainRef?=when(payload){
        is StatChange->payload.subject
        is ResourceChange->payload.subject
        is SkillChange->payload.subject
        is TechniqueChange->payload.subject
        is InnateChange->payload.subject
        is InventoryChange->payload.subject
        is EquipmentChange->payload.subject
        is ConditionChange->payload.subject
        is RuntimeChange->payload.subject
        is WoundChange->payload.subject
        is SpatialChange->payload.subject
        is EquipmentIntegrityChange->payload.subject
        is StructureIntegrityChange->payload.subject
        is MechanicalTrackChange->payload.subject
        is AggregatePopulationChange->payload.subject
        is KnowledgeAcquisitionChange->DomainRef(
            payload.acquisition.holder.holderKindUid,payload.acquisition.holder.holderUid
        )
        is AccessAuthorityChange->null
        is AssetChange,is CampaignTruthChange,is DevelopmentProjectChange,is FinancialChange,is OwnershipChange->null
    }

    private fun describe(payload:PlayerDomainChangePayload):String=when(payload){
        is StatChange->"${payload.subject.kindUid}:${payload.subject.uid} statystyka ${payload.statUid} zmiana ${payload.delta.units}"
        is ResourceChange->"${payload.subject.kindUid}:${payload.subject.uid} zasób ${payload.resourceUid} zmiana ${payload.delta.units}"
        is SkillChange->"${payload.subject.kindUid}:${payload.subject.uid} umiejętność ${payload.skillUid} postęp ${payload.progressDelta.units}"
        is TechniqueChange->"${payload.subject.kindUid}:${payload.subject.uid} technika ${payload.techniqueUid} postęp ${payload.progressDelta.units}"
        is InnateChange->"${payload.subject.kindUid}:${payload.subject.uid} cecha ${payload.innateUid} stan ${payload.proposedStateUid}"
        is InventoryChange->"${payload.subject.kindUid}:${payload.subject.uid} przedmiot ${payload.itemInstanceUid} ilość ${payload.quantityDelta.units}"
        is EquipmentChange->"${payload.subject.kindUid}:${payload.subject.uid} wyposażenie ${payload.slotUid} ${payload.operation} ${payload.itemInstanceUid.orEmpty()}"
        is FinancialChange->"Transfer ${payload.amountMinor} ${payload.currencyUid} z ${payload.fromAccountUid} do ${payload.toAccountUid}"
        is AssetChange->"Aktywo ${payload.asset.assetKindUid}:${payload.asset.assetUid} stan ${payload.proposedLifecycleStateUid}"
        is OwnershipChange->"Własność ${payload.asset.assetKindUid}:${payload.asset.assetUid} z ${payload.fromOwner.ownerUid} do ${payload.toOwner.ownerUid}"
        is CampaignTruthChange->listOf(payload.kind.name,payload.subjectUid,payload.predicate,payload.objectValue,payload.narrativeText).filterNotNull().joinToString(" ")
        is ConditionChange->"${payload.subject.kindUid}:${payload.subject.uid} status ${payload.conditionUid} ${payload.operation}"
        is RuntimeChange->"${payload.subject.kindUid}:${payload.subject.uid} licznik ${payload.runtimeCounterUid} ${payload.delta.units}"
        is WoundChange->"${payload.subject.kindUid}:${payload.subject.uid} rana ${payload.severityUid.orEmpty()} ${payload.severityDelta.units}"
        is SpatialChange->"${payload.subject.kindUid}:${payload.subject.uid} ruch ${payload.destinationLocation?.let{"do ${it.kindUid}:${it.uid}"}?:"${payload.deltaXMillimetres},${payload.deltaYMillimetres}"}"
        is EquipmentIntegrityChange->"${payload.subject.kindUid}:${payload.subject.uid} uszkodzenie wyposażenia ${payload.componentUid} ${payload.damageDelta.units}"
        is StructureIntegrityChange->"${payload.subject.kindUid}:${payload.subject.uid} uszkodzenie struktury ${payload.componentUid.orEmpty()} ${payload.damageDelta.units}"
        is MechanicalTrackChange->"${payload.subject.kindUid}:${payload.subject.uid} tor ${payload.trackUid} ${payload.delta.units}"
        is AggregatePopulationChange->"${payload.subject.kindUid}:${payload.subject.uid} grupa wyeliminowani ${payload.eliminatedDelta} ranni ${payload.woundedDelta} status ${payload.conditionUid.orEmpty()} ${payload.conditionAffectedDelta}"
        is DevelopmentProjectChange->"Projekt ${payload.projectUid} wynik ${payload.workResultKindUid}"
        is AccessAuthorityChange->"Zmiana autoryzacji dostępu"
        else->"Zmiana canonical ${payload::class.java.simpleName}"
    }

    private fun chunk(text:String):List<String>{
        val normalized=text.replace(Regex("\\s+")," ").trim()
        if(normalized.length<=SEMANTIC_DOCUMENT_CHUNK_CHARS)return listOf(normalized)
        val out=mutableListOf<String>();var start=0
        while(start<normalized.length){
            val end=(start+SEMANTIC_DOCUMENT_CHUNK_CHARS).coerceAtMost(normalized.length)
            out+=normalized.substring(start,end)
            if(end==normalized.length)break
            start=(end-SEMANTIC_DOCUMENT_CHUNK_OVERLAP_CHARS).coerceAtLeast(start+1)
        }
        return out
    }
}

/** Typed projection of the canonical CampaignTruth owner at a requested commit order. Mutable
 * world elements are deliberately not projected from a single replay payload: their visibility
 * and latest scalar values may have changed in a later commit. */
internal class CanonicalWorldElementSemanticProjector(
    private val activePlayerUid:()->String?={null},
    private val historyGenerationUid:()->HistoryGenerationUid?={null},
    private val accessPolicyVersion:Long=1L,
    private val visibility:VisibilityAuthorityService=VisibilityAuthorityService()
):SemanticDocumentProjector{
    override fun project(source:Any,audience:AudienceContext,purpose:PurposeContext):List<SemanticDocumentProjection>{
        val state=source as? CanonicalWorldElementSemanticState?:return emptyList()
        require(state.campaignUid==audience.campaignUid&&state.campaignUid==purpose.campaignUid)
        val gm=audience.audienceKindUid==AudienceKinds.GM_RUNTIME
        val playerVisible=state.presentationFacts[CampaignWorldFacts.AUDIENCE_SCOPE]==CampaignWorldAudience.PLAYER_VISIBLE
        if(!gm&&!playerVisible)return emptyList()
        val player=activePlayerUid();val principal=audience.principal?.uid?:audience.audienceKindUid
        val trusted=if(gm)Phase38RuntimeAuthority.privileged(audience,Phase38RuntimeAuthority.PRIV_GM)
        else Phase38RuntimeAuthority.application(audience,controlledSubjectUids=player?.let(::setOf).orEmpty())
        val subject=VisibilitySubjectRef(
            state.campaignUid,
            if(gm)VisibilitySubjectKinds.CAMPAIGN_TRUTH else VisibilitySubjectKinds.WORLD_PRESENTATION,
            state.subjectUid
        )
        if(visibility.project(VisibilityRequest(audience,purpose,subject),trusted){true}.value!=true)return emptyList()
        val description=state.presentationFacts.toSortedMap().entries.joinToString("; "){(predicate,value)->
            "${predicate.substringAfterLast(':')}=$value"
        }
        val text="Element świata ${state.subjectUid}; $description"
        val fingerprint=semanticSha256("${state.subjectUid}|${state.sourceAsOfOrder}|$text")
        return chunkSemanticText(text).mapIndexed{ordinal,part->SemanticDocumentProjection(
            state.campaignUid,SEMANTIC_NAMESPACE_CAMPAIGN,audience.audienceKindUid,purpose.purposeUid,
            "WORLD_ELEMENT:${state.subjectUid}","WORLD_ELEMENT",TruthKind.FACT.name,
            state.sourceAsOfOrder,state.sourceAsOfOrder,fingerprint,ordinal,part,
            historyGenerationUid=historyGenerationUid(),principalUid=principal,
            holderSetFingerprint=semanticHolderSetFingerprint(audience.audienceKindUid,principal,player),
            accessPolicyVersion=accessPolicyVersion,activePlayerUid=player
        )}
    }
}

/** Non-FACT CampaignTruth remains explicitly epistemic and GM-only. Holder-visible beliefs are
 * owned by Phase37/HolderEpisodeMemory, never inferred here from a campaign-wide truth row. */
internal class CanonicalWorldEpistemicSemanticProjector(
    private val historyGenerationUid:()->HistoryGenerationUid?={null},
    private val activePlayerUid:()->String?={null},
    private val accessPolicyVersion:Long=1L,
    private val visibility:VisibilityAuthorityService=VisibilityAuthorityService()
):SemanticDocumentProjector{
    override fun project(source:Any,audience:AudienceContext,purpose:PurposeContext):List<SemanticDocumentProjection>{
        val state=source as? CanonicalWorldEpistemicSemanticState?:return emptyList()
        require(state.campaignUid==audience.campaignUid&&state.campaignUid==purpose.campaignUid)
        if(audience.audienceKindUid!=AudienceKinds.GM_RUNTIME)return emptyList()
        val trusted=Phase38RuntimeAuthority.privileged(audience,Phase38RuntimeAuthority.PRIV_GM)
        val subject=VisibilitySubjectRef(state.campaignUid,VisibilitySubjectKinds.CAMPAIGN_TRUTH,state.truthUid)
        if(visibility.project(VisibilityRequest(audience,purpose,subject),trusted){true}.value!=true)return emptyList()
        val text=listOf(
            state.epistemicKind.name,state.subjectUid,state.predicate,state.objectValue,state.perspectiveUid,state.narrativeText
        ).filterNotNull().filter{it.isNotBlank()}.joinToString(" ")
        val fingerprint=semanticSha256("${state.truthUid}|${state.sourceAsOfOrder}|$text")
        val principal=audience.principal?.uid?:audience.audienceKindUid
        val player=activePlayerUid()
        return chunkSemanticText(text).mapIndexed{ordinal,part->SemanticDocumentProjection(
            state.campaignUid,SEMANTIC_NAMESPACE_CAMPAIGN,audience.audienceKindUid,purpose.purposeUid,
            "WORLD_ASSERTION:${state.truthUid}","CAMPAIGN_TRUTH",state.epistemicKind.name,
            state.sourceAsOfOrder,state.sourceAsOfOrder,fingerprint,ordinal,part,
            historyGenerationUid=historyGenerationUid(),principalUid=principal,
            holderSetFingerprint=semanticHolderSetFingerprint(audience.audienceKindUid,principal,player),
            accessPolicyVersion=accessPolicyVersion,activePlayerUid=player
        )}
    }
}

private fun chunkSemanticText(text:String):List<String>{
    val normalized=text.replace(Regex("\\s+")," ").trim()
    if(normalized.isBlank())return emptyList()
    if(normalized.length<=SEMANTIC_DOCUMENT_CHUNK_CHARS)return listOf(normalized)
    val result=mutableListOf<String>();var start=0
    while(start<normalized.length){
        val end=(start+SEMANTIC_DOCUMENT_CHUNK_CHARS).coerceAtMost(normalized.length)
        result+=normalized.substring(start,end)
        if(end==normalized.length)break
        start=(end-SEMANTIC_DOCUMENT_CHUNK_OVERLAP_CHARS).coerceAtLeast(start+1)
    }
    return result
}

/** Audience-scoped projection of the durable-but-rebuildable owners created by Phase55-58.
 * Phase59 never reads their tables as truth and never exposes raw payload JSON. Holder memories
 * are visible only to the matching active character (or privileged GM), and every projection is
 * re-created from the current CLEAN revision before a semantic hit can leave the cache. */
internal class ConsolidatedMemorySemanticProjector(
    private val activePlayerUid:()->String?={null},
    private val accessPolicyVersion:Long=1L,
    private val visibility:VisibilityAuthorityService=VisibilityAuthorityService(),
    private val evaluationOrder:(ActiveMemoryArtifactRevision)->Long={it.asOfCommittedOrder}
):SemanticDocumentProjector{
    override fun project(source:Any,audience:AudienceContext,purpose:PurposeContext):List<SemanticDocumentProjection>{
        val artifact=source as? ActiveMemoryArtifactRevision?:return emptyList()
        require(artifact.campaignUid==audience.campaignUid&&artifact.campaignUid==purpose.campaignUid)
        val payload=runCatching{org.json.JSONObject(artifact.payloadJson)}.getOrNull()?:return emptyList()
        val controlledPlayer=activePlayerUid()
        val principalUid=audience.principal?.uid?:audience.audienceKindUid
        val gm=audience.audienceKindUid==AudienceKinds.GM_RUNTIME
        val holderUid=payload.optString("holder_uid").takeIf{it.isNotBlank()}
        val holderKind=payload.optString("holder_kind_uid").takeIf{it.isNotBlank()}
        if(!gm&&(holderKind!=KnowledgeHolderKinds.CHARACTER||holderUid!=controlledPlayer))return emptyList()
        val trusted=when{
            gm->Phase38RuntimeAuthority.privileged(audience,Phase38RuntimeAuthority.PRIV_GM)
            audience.audienceKindUid in setOf(AudienceKinds.PLAYER,AudienceKinds.PLAYER_CHARACTER)->
                Phase38RuntimeAuthority.application(audience,controlledSubjectUids=controlledPlayer?.let(::setOf).orEmpty())
            else->return emptyList()
        }
        val visibilitySubject=when{
            holderUid!=null->VisibilitySubjectRef(artifact.campaignUid,VisibilitySubjectKinds.PLAYER_STATE,holderUid)
            else->VisibilitySubjectRef(artifact.campaignUid,VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL,artifact.artifactRevisionUid)
        }
        if(visibility.project(VisibilityRequest(audience,purpose,visibilitySubject),trusted){true}.value!=true)return emptyList()
        val material=materialize(artifact,payload,gm,evaluationOrder(artifact))?:return emptyList()
        val holderFingerprint=semanticHolderSetFingerprint(audience.audienceKindUid,principalUid,controlledPlayer)
        val recordFingerprint=semanticSha256("${artifact.sourceLeafSetFingerprint}|${artifact.artifactRevisionUid}|${material.second}|${material.third}")
        return chunks(material.third).mapIndexed{ordinal,text->SemanticDocumentProjection(
            campaignUid=artifact.campaignUid,namespaceUid=SEMANTIC_NAMESPACE_CAMPAIGN,
            audienceUid=audience.audienceKindUid,purposeUid=purpose.purposeUid,
            canonicalRecordUid="MEMORY:${artifact.artifactRevisionUid}",recordKindUid="MEMORY:${artifact.artifactKind.name}",
            epistemicStateUid=material.second,asOfOrder=artifact.asOfCommittedOrder,
            sourceVersion=artifact.derivationVersion,
            sourceFingerprint=recordFingerprint,
            chunkOrdinal=ordinal,text=text,historyGenerationUid=artifact.historyGenerationUid,
            principalUid=principalUid,holderSetFingerprint=holderFingerprint,accessPolicyVersion=accessPolicyVersion,
            activePlayerUid=controlledPlayer
        )}
    }

    private fun materialize(
        artifact:ActiveMemoryArtifactRevision,payload:org.json.JSONObject,gm:Boolean,evaluationOrder:Long
    ):Triple<String,String,String>? = when(artifact.artifactKind){
        MemoryArtifactKind.EPISODE_MANIFEST->if(!gm)null else Triple("manifest","FACT",listOf(
            "Epizod ${payload.optString("episode_uid")}",
            "wydarzenia ${jsonStrings(payload,"event_uids").joinToString(",")}",
            "uczestnicy ${jsonStrings(payload,"participants").joinToString(",")}",
            "lokacje ${jsonStrings(payload,"locations").joinToString(",")}",
            "kolejność ${payload.optLong("start_order")}-${payload.optLong("end_order")}"
        ).joinToString("; "))
        MemoryArtifactKind.EPISODE_INTERPRETATION->if(!gm)null else Triple("interpretation","NARRATIVE",listOf(
            payload.optString("title"),payload.optString("summary"),jsonStrings(payload,"tags").joinToString(" ")
        ).filter{it.isNotBlank()}.joinToString("; "))
        MemoryArtifactKind.HOLDER_EPISODE_MEMORY->Triple("holder","MEMORY",listOf(
            "Wspomnienie ${payload.optString("holder_kind_uid")}:${payload.optString("holder_uid")}",
            "epizod ${payload.optString("episode_uid")}",
            "zapamiętane ${jsonStrings(payload,"remembered_event_uids").joinToString(",")}",
            "siła ${payload.optDouble("recall_strength",0.0)}",
            "dokładność ${payload.optDouble("accuracy",0.0)}"
        ).joinToString("; "))
        MemoryArtifactKind.SEMANTIC_ASSERTION->{
            val validUntil=payload.optLong("valid_until_order",Long.MAX_VALUE)
            if(payload.optString("lifecycle")!=SemanticAssertionLifecycle.ACTIVE.name||validUntil<=evaluationOrder)null
            else{
                val epistemic=payload.optString("epistemic_kind",SemanticAssertionEpistemicKind.MEMORY.name)
                Triple("assertion",epistemic,listOf(
                    "Pamięć ${payload.optString("holder_kind_uid")}:${payload.optString("holder_uid")}",
                    "${payload.optString("subject_kind_uid")}:${payload.optString("subject_uid")}",
                    payload.optString("predicate_uid"),payload.optString("polarity"),payload.optString("object_value")
                ).filter{it.isNotBlank()}.joinToString(" "))
            }
        }
    }

    private fun jsonStrings(payload:org.json.JSONObject,key:String):List<String>{
        val array=payload.optJSONArray(key)?:return emptyList()
        return (0 until array.length()).mapNotNull{index->array.optString(index).takeIf{it.isNotBlank()}}
    }

    private fun chunks(text:String):List<String>{
        val normalized=text.replace(Regex("\\s+")," ").trim()
        if(normalized.isBlank())return emptyList()
        if(normalized.length<=SEMANTIC_DOCUMENT_CHUNK_CHARS)return listOf(normalized)
        val result=mutableListOf<String>();var start=0
        while(start<normalized.length){
            val end=(start+SEMANTIC_DOCUMENT_CHUNK_CHARS).coerceAtMost(normalized.length)
            result+=normalized.substring(start,end)
            if(end==normalized.length)break
            start=(end-SEMANTIC_DOCUMENT_CHUNK_OVERLAP_CHARS).coerceAtLeast(start+1)
        }
        return result
    }
}

internal fun semanticHolderSetFingerprint(audienceKindUid:String,principalUid:String,activePlayerUid:String?)=
    semanticSha256("$audienceKindUid|$principalUid|${activePlayerUid.orEmpty()}|${VisibilityAuthorityService.PROJECTION_VERSION_UID}")

internal class RepositorySemanticRuntimeScopeResolver(
    private val repository:UnifiedGameRepository,
    private val accessPolicyVersion:Long=1L
):SemanticRuntimeScopeResolver{
    override fun resolve(request:StructuredRetrievalRequest,namespaceUid:String):SemanticRuntimeScope{
        require(request.campaignUid==repository.activeCampaignRef().campaignId){"BEKKO_SCOPE_CAMPAIGN_CHANGED"}
        val principal=request.audience.principal?.uid?:request.audience.audienceKindUid
        val activePlayer=repository.activePlayerRef()?.playerUid
        return SemanticRuntimeScope(
            historyGenerationUid=if(namespaceUid==SEMANTIC_NAMESPACE_CAMPAIGN)repository.infrastructureHistoryGenerationUid() else null,
            principalUid=principal,
            holderSetFingerprint=if(namespaceUid==SEMANTIC_NAMESPACE_CAMPAIGN)
                semanticHolderSetFingerprint(request.audience.audienceKindUid,principal,activePlayer) else "GLOBAL",
            accessPolicyVersion=if(namespaceUid==SEMANTIC_NAMESPACE_CAMPAIGN)accessPolicyVersion else 0L,
            activePlayerUid=if(namespaceUid==SEMANTIC_NAMESPACE_CAMPAIGN)activePlayer else null
        )
    }
}

/** Re-opens each selected UID through its current canonical owner and Phase38 projection. Cached
 * text is never returned when the authoritative source version/fingerprint no longer matches. */
internal class RepositorySemanticCanonicalRehydrator(
    private val repository:UnifiedGameRepository,
    private val accessPolicyVersion:Long=1L
):SemanticCanonicalRehydrationPort{
    override fun rehydrate(
        request:SemanticSearchRequest,
        candidates:List<SemanticCandidate>
    ):Map<String,CanonicallyRehydratedSemanticRecord>{
        if(request.campaignUid!=repository.activeCampaignRef().campaignId)return emptyMap()
        val candidateByUid=candidates.associateBy{it.canonicalRecordUid}
        if(candidateByUid.isEmpty())return emptyMap()
        val documents=when(request.namespaceUid){
            SEMANTIC_NAMESPACE_CAMPAIGN->campaignDocuments(request,candidateByUid)
            SEMANTIC_NAMESPACE_WORLD_PACK->worldPackDocuments(request,candidateByUid.keys)
            else->emptyList()
        }
        return candidateByUid.mapNotNull{(uid,candidate)->
            val current=documents.filter{it.canonicalRecordUid==uid&&it.sourceVersion==candidate.sourceVersion&&it.asOfOrder==candidate.sourceAsOfOrder}
            val best=current.firstOrNull{it.sourceFingerprint==candidate.sourceFingerprint}?:return@mapNotNull null
            val evidence=current.map{document->SemanticChunkEvidence(document.chunkOrdinal,document.text,semanticSha256(document.text))}
                .sortedBy{it.chunkOrdinal}.take(4)
            uid to CanonicallyRehydratedSemanticRecord(
                uid,best.recordKindUid,best.epistemicStateUid,best.sourceFingerprint,best.sourceVersion,best.asOfOrder,
                evidence.joinToString("\n"){it.projectedText},evidence,
                "BEKKO-REHYDRATED:${best.projectionVersionUid}:${best.sourceFingerprint}"
            )
        }.toMap()
    }

    private fun campaignDocuments(request:SemanticSearchRequest,candidates:Map<String,SemanticCandidate>):List<SemanticDocumentProjection>{
        val uids=candidates.keys
        val principal=request.principalUid
        val audience=AudienceContext(
            request.campaignUid,request.audienceUid,
            VisibilityPrincipalRef(request.audienceUid,principal)
        )
        val purpose=PurposeContext(request.campaignUid,request.purposeUid)
        val activePlayer=repository.activePlayerRef()?.playerUid
        val generation=repository.infrastructureHistoryGenerationUid()
        if(request.historyGenerationUid!=null&&request.historyGenerationUid!=generation)return emptyList()
        val replayProjector=CommittedReplaySemanticProjector({activePlayer},{generation},accessPolicyVersion)
        val replayOrders=candidates.values.asSequence()
            .filter{it.sourceAsOfOrder<=request.asOfOrder&&
                !it.canonicalRecordUid.startsWith("MEMORY:")&&!it.canonicalRecordUid.startsWith("WORLD_ELEMENT:")}
            .map{it.sourceAsOfOrder}.toCollection(linkedSetOf())
        val replayDocuments=repository.infrastructureReplayPayloadsAtOrders(replayOrders)
            .asSequence()
            .flatMap{replayProjector.project(it,audience,purpose).asSequence()}
            .filter{it.canonicalRecordUid in uids}
            .toList()
        val worldSubjectUids=uids.asSequence().filter{it.startsWith("WORLD_ELEMENT:")}
            .map{it.removePrefix("WORLD_ELEMENT:")}.toSet()
        val worldDocuments=worldSubjectUids.chunked(200).flatMap{subjects->
            val owner=repository.infrastructureCanonicalWorldElementsAt(subjects.toSet(),request.asOfOrder)
            val ownerProjector=CanonicalWorldElementSemanticProjector({activePlayer},{generation},accessPolicyVersion)
            owner.flatMap{ownerProjector.project(it,audience,purpose)}
        }
        val worldAssertionUids=uids.asSequence().filter{it.startsWith("WORLD_ASSERTION:")}
            .map{it.removePrefix("WORLD_ASSERTION:")}.toSet()
        val worldAssertionDocuments=worldAssertionUids.chunked(200).flatMap{truths->
            val owner=repository.infrastructureCanonicalWorldEpistemicAssertionsAt(truths.toSet(),request.asOfOrder)
            val ownerProjector=CanonicalWorldEpistemicSemanticProjector({generation},{repository.activePlayerRef()?.playerUid},accessPolicyVersion)
            owner.flatMap{ownerProjector.project(it,audience,purpose)}
        }
        val memoryRevisionUids=uids.asSequence().filter{it.startsWith("MEMORY:")}.map{it.removePrefix("MEMORY:")}.toSet()
        if(memoryRevisionUids.isEmpty())return replayDocuments+worldDocuments+worldAssertionDocuments
        val memoryProjector=ConsolidatedMemorySemanticProjector({activePlayer},accessPolicyVersion,evaluationOrder={request.asOfOrder})
        val memoryDocuments=repository.infrastructureActiveMemoryArtifacts(request.asOfOrder,memoryRevisionUids)
            .flatMap{memoryProjector.project(it,audience,purpose)}
            .filter{it.canonicalRecordUid in uids}
        return replayDocuments+worldDocuments+worldAssertionDocuments+memoryDocuments
    }

    private fun worldPackDocuments(request:SemanticSearchRequest,uids:Set<String>):List<SemanticDocumentProjection> =
        repository.characterCreationCatalog().options.asSequence()
            .map{semanticWorldPackProjection(request.campaignUid,request.audienceUid,request.purposeUid,it)}
            .filter{it.canonicalRecordUid in uids}.toList()
}

/** Immediate, idempotent, post-commit indexing. No periodic scheduler is created. */
class ImmediateSemanticIndexCoordinator(
    private val repository:UnifiedGameRepository,
    private val embeddings:EmbeddingProviderPort,
    private val index:SemanticIndexPort,
    private val projector:SemanticDocumentProjector=CommittedReplaySemanticProjector(
        activePlayerUid={repository.activePlayerRef()?.playerUid},
        historyGenerationUid={repository.infrastructureHistoryGenerationUid()}
    ),
    /** The coordinator is a per-campaign cache worker. It must never silently follow the
     * process-wide active campaign while a previous asynchronous catch-up is still running. */
    private val campaignUid:String=repository.activeCampaignRef().campaignId,
    private val executor:ExecutorService=Executors.newSingleThreadExecutor{r->Thread(r,"rpgos-bekko-index").apply{isDaemon=true}},
    private val onProgress:(SemanticIndexProgress)->Unit={}
):AutoCloseable{
    /** A coordinator is a lease for one concrete canonical history generation. It must never
     * follow the same campaign UID across undo/restore, because those operations replace the
    * backing DB and invalidate every derived offset/checkpoint. */
    private val historyGenerationUid=repository.infrastructureHistoryGenerationUid()
    private val closed=AtomicBoolean(false)
    private val resourcesClosed=AtomicBoolean(false)
    private val lifecycleLock=ReentrantReadWriteLock(true)
    private val running=AtomicBoolean(false)
    private val readyForQueries=AtomicBoolean(false)
    private val activeEmbeddingRequest=AtomicReference<String?>(null)
    fun readyForQueries():Boolean=readyForQueries.get()
    fun onCanonicalCommit(){if(!closed.get()){readyForQueries.set(false);runCatching{executor.execute{catchUp()}}}}
    fun onCampaignOpened(){if(!closed.get()){readyForQueries.set(false);runCatching{executor.execute{catchUp()}}}}
    /** Non-blocking lifecycle signal used before a storage/configuration writer waits for the
     * semantic read lease. The isolated llama.cpp service receives cancellation while the active
     * request UID is still known; resource destruction happens only after the reader exits. */
    fun requestCancellation(){
        closed.set(true)
        readyForQueries.set(false)
        executor.shutdownNow()
        activeEmbeddingRequest.get()?.let{requestUid->runCatching{embeddings.cancel(requestUid)}}
    }
    fun catchUp():SemanticIndexStatus=SemanticCampaignTransitionRegistry.withSemanticRuntimeAccess{
        val read=lifecycleLock.readLock();read.lock()
        try{if(closed.get())safeStatus("BEKKO_COORDINATOR_CLOSED") else catchUpWithRuntimeLease()}
        finally{read.unlock()}
    }
    private fun catchUpWithRuntimeLease():SemanticIndexStatus{
        if(!running.compareAndSet(false,true))return safeStatus()
        readyForQueries.set(false)
        try{
            val campaign=campaignUid
            requireCampaignStillActive()
            val checkpointScope=semanticSha256(listOf(
                historyGenerationUid.value,
                repository.activePlayerRef()?.playerUid.orEmpty(),
                1L,
                VisibilityAuthorityService.PROJECTION_VERSION_UID,
                index.version.projectorVersion
            ).joinToString("|"))
            index.bindCheckpointScope(campaign,checkpointScope)
            val initial=index.status(campaign)
            if(embeddings.availability().state!=EmbeddingAvailabilityState.READY){
                onProgress(SemanticIndexProgress(false,"FALLBACK",lastIndexedCommitOrder=initial.lastIndexedCommitOrder,reasonUid=embeddings.availability().reasonUid))
                return initial
            }
            onProgress(SemanticIndexProgress(true,"WORLD_PACK",lastIndexedCommitOrder=initial.lastIndexedCommitOrder))
            ensureWorldPack(campaign)?.let{reason->
                onProgress(SemanticIndexProgress(false,"FAILED",lastIndexedCommitOrder=index.checkpoint(campaign),reasonUid=reason))
                return index.status(campaign).copy(ready=false,reasonUid=reason)
            }
            var after=index.checkpoint(campaign)
            var processedReplays=0
            val catchUpProjector=(projector as? CommittedReplaySemanticProjector)
                ?.boundToActivePlayer(repository.activePlayerRef()?.playerUid,historyGenerationUid)?:projector
            while(true){
                requireCampaignStillActive()
                val replays=repository.infrastructureReplayPayloadsAfterLimited(after,64)
                if(replays.isEmpty())break
                onProgress(SemanticIndexProgress(true,"CANONICAL_CATCH_UP",processedReplays,processedReplays+replays.size,after))
                replays.forEachIndexed{pageIndex,replay->
                    val audiences=listOf(
                        VisibilityAudienceFactory.player(campaign) to PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),
                        AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM")) to PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
                    )
                    val documents=audiences.flatMap{(audience,purpose)->catchUpProjector.project(replay,audience,purpose)}
                    val indexed=documents.chunked(embeddings.capabilities.maximumBatchSize).flatMapIndexed{batchIndex,batch->
                        when(val result=embed("BEKKO-INDEX:${replay.identity.transactionUid}:$batchIndex",batch.map{it.text})){
                            is EmbeddingBatchResult.Success->batch.zip(result.vectors).map{(document,vector)->SemanticIndexedDocument(document,matryoshkaL2(vector,index.version.dimensions))}
                            is EmbeddingBatchResult.Failure->{
                                onProgress(SemanticIndexProgress(false,"FAILED",processedReplays+pageIndex,processedReplays+replays.size,index.checkpoint(campaign),result.reasonUid))
                                return index.status(campaign).copy(ready=false,reasonUid=result.reasonUid)
                            }
                        }
                    }
                    requireCampaignStillActive()
                    index.upsertBatch(indexed)
                    ensureCanonicalWorldElements(campaign,replay)?.let{reason->
                        onProgress(SemanticIndexProgress(false,"FAILED",processedReplays+pageIndex,processedReplays+replays.size,index.checkpoint(campaign),reason))
                        return index.status(campaign).copy(ready=false,reasonUid=reason)
                    }
                    index.advanceCheckpoint(campaign,replay.commitOrder)
                    after=replay.commitOrder
                    onProgress(SemanticIndexProgress(true,"CANONICAL_CATCH_UP",processedReplays+pageIndex+1,processedReplays+replays.size,replay.commitOrder))
                }
                processedReplays+=replays.size
            }
            requireCampaignStillActive()
            onProgress(SemanticIndexProgress(true,"CONSOLIDATED_MEMORY",lastIndexedCommitOrder=index.checkpoint(campaign)))
            ensureConsolidatedMemory(campaign)?.let{reason->
                onProgress(SemanticIndexProgress(false,"FAILED",lastIndexedCommitOrder=index.checkpoint(campaign),reasonUid=reason))
                return index.status(campaign).copy(ready=false,reasonUid=reason)
            }
            requireCampaignStillActive()
            return index.status(campaign).also{status->
                readyForQueries.set(true)
                onProgress(SemanticIndexProgress(false,"READY",processedReplays,processedReplays,status.lastIndexedCommitOrder))
            }
        }catch(failure:Throwable){
            // This worker owns only a rebuildable cache. A campaign switch, SQLite race, model
            // failure or closed transport must therefore degrade to the structured/lexical hot
            // tail, never escape an executor thread and terminate the Android application.
            val reason=when(failure){
                is InterruptedException->"BEKKO_INDEXING_CANCELLED"
                else->"BEKKO_INDEXING_FAILED:${failure::class.java.simpleName}"
            }
            val failed=safeStatus(reason)
            runCatching{onProgress(SemanticIndexProgress(false,"FAILED",lastIndexedCommitOrder=failed.lastIndexedCommitOrder,reasonUid=reason))}
            return failed
        }finally{running.set(false)}
    }

    private fun requireCampaignStillActive(){
        if(closed.get()||Thread.currentThread().isInterrupted)throw InterruptedException("BEKKO_INDEXING_CANCELLED")
        check(repository.activeCampaignRef().campaignId==campaignUid){"BEKKO_CAMPAIGN_CHANGED"}
        check(repository.infrastructureHistoryGenerationUid()==historyGenerationUid){"BEKKO_HISTORY_GENERATION_CHANGED"}
    }

    private fun safeStatus(reasonUid:String?=null):SemanticIndexStatus =
        runCatching{index.status(campaignUid).let{status->
            if(reasonUid==null)status else status.copy(ready=false,reasonUid=reasonUid)
        }}
            .getOrElse{SemanticIndexStatus(false,0,0,0,index.version,reasonUid?:"BEKKO_INDEX_STATUS_UNAVAILABLE")}

    private fun ensureWorldPack(campaign:String):String?{
        requireCampaignStillActive()
        val scopes=listOf(
            AudienceKinds.PLAYER to VisibilityPurposeKinds.GAMEPLAY_NARRATION,
            AudienceKinds.GM_RUNTIME to VisibilityPurposeKinds.INTERNAL_SIMULATION
        )
        val options=repository.characterCreationCatalog().options
        val catalogUids=options.mapTo(linkedSetOf(),::semanticWorldPackRecordUid)
        requireCampaignStillActive()
        val indexedUids=scopes.flatMapTo(linkedSetOf()){(audience,purpose)->
            index.authorizedRecordUids(campaign,SEMANTIC_NAMESPACE_WORLD_PACK,audience,purpose,Long.MAX_VALUE)
        }
        (indexedUids-catalogUids).forEach{stale->index.remove(campaign,SEMANTIC_NAMESPACE_WORLD_PACK,stale)}
        scopes.forEach{(audience,purpose)->
            val projections=options.map{option->semanticWorldPackProjection(campaign,audience,purpose,option)}
            if(projections.isEmpty())return@forEach
            val sample=projections.first()
            val request=SemanticSearchRequest(
                campaign,SEMANTIC_NAMESPACE_WORLD_PACK,audience,purpose,Long.MAX_VALUE,catalogUids,
                queryVector=FloatArray(index.version.dimensions),topK=1,minimumScore=-1f,
                principalUid=sample.principalUid,holderSetFingerprint=sample.holderSetFingerprint,
                projectionVersionUid=sample.projectionVersionUid
            )
            val current=index.currentProjections(request)
            val changed=projections.filter{projection->
                val state=current[projection.canonicalRecordUid]
                state==null||state.sourceFingerprint!=projection.sourceFingerprint||state.sourceVersion!=projection.sourceVersion||
                    state.sourceAsOfOrder!=projection.asOfOrder
            }
            changed.forEachIndexed{recordIndex,projection->
                indexRecordDocuments("BEKKO-WORLDPACK:$campaign:$audience:$recordIndex",listOf(projection))?.let{return it}
            }
        }
        return null
    }

    private fun ensureConsolidatedMemory(campaign:String):String?{
        requireCampaignStillActive()
        val generation=historyGenerationUid
        val activePlayer=repository.activePlayerRef()?.playerUid
        val scopes=listOf(
            VisibilityAudienceFactory.player(campaign) to PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),
            AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM")) to
                PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        )
        val retirementOrder=repository.infrastructureLastCommitOrder()
        val projector=ConsolidatedMemorySemanticProjector({activePlayer})
        val reconciliation=scopes.map{(audience,purpose)->
            val scope=SemanticProjectionScope(campaign,SEMANTIC_NAMESPACE_CAMPAIGN,audience.audienceKindUid,purpose.purposeUid)
            scope to index.beginProjectionReconciliation(scope,"MEMORY:")
        }
        var afterOrder=-1L;var afterRevision="";var pageNumber=0
        while(true){
            requireCampaignStillActive()
            val artifacts=repository.infrastructureActiveMemoryArtifactsPage(afterOrder,afterRevision,128)
            if(artifacts.isEmpty())break
            scopes.forEachIndexed{scopeIndex,(audience,purpose)->
                val documents=artifacts.flatMap{projector.project(it,audience,purpose)}
                val recordUids=documents.mapTo(linkedSetOf()){it.canonicalRecordUid}
                reconciliation[scopeIndex].second?.let{session->index.markProjectionReconciliation(session,recordUids)}
                if(documents.isEmpty())return@forEachIndexed
                val first=documents.first()
                val request=SemanticSearchRequest(
                    campaignUid=campaign,namespaceUid=SEMANTIC_NAMESPACE_CAMPAIGN,
                    audienceUid=audience.audienceKindUid,purposeUid=purpose.purposeUid,
                    asOfOrder=Long.MAX_VALUE,authorizedRecordUids=recordUids,
                    queryVector=FloatArray(index.version.dimensions),topK=1,minimumScore=-1f,
                    historyGenerationUid=generation,principalUid=first.principalUid,
                    holderSetFingerprint=first.holderSetFingerprint,accessPolicyVersion=first.accessPolicyVersion,
                    activePlayerUid=activePlayer,projectionVersionUid=first.projectionVersionUid
                )
                val current=index.currentProjections(request)
                val missing=documents.groupBy{it.canonicalRecordUid}.filter{(uid,chunks)->
                    val state=current[uid];val sample=chunks.first()
                    state==null||state.sourceFingerprint!=sample.sourceFingerprint||state.sourceVersion!=sample.sourceVersion||
                        state.sourceAsOfOrder!=sample.asOfOrder||state.historyGenerationUid!=generation
                }.values
                missing.forEachIndexed{recordIndex,chunks->
                    indexRecordDocuments("BEKKO-MEMORY:$campaign:$pageNumber:$scopeIndex:$recordIndex",chunks)?.let{return it}
                }
            }
            val last=artifacts.last();afterOrder=last.asOfCommittedOrder;afterRevision=last.artifactRevisionUid;pageNumber++
        }
        reconciliation.forEach{(scope,session)->
            if(session!=null&&!index.finishProjectionReconciliation(scope,"MEMORY:",session,retirementOrder))
                return "BEKKO_MEMORY_RECONCILIATION_SUPERSEDED"
        }
        return null
    }
    private fun ensureCanonicalWorldElements(campaign:String,replay:CommittedReplayPayload):String?{
        val allTruthChanges=replay.changeSet.changes.asSequence().mapNotNull{it.payload as? CampaignTruthChange}.toList()
        val truthChanges=allTruthChanges.filter{it.predicate in CampaignWorldFacts.ALL}
        val supersededTruthUids=allTruthChanges.mapNotNullTo(linkedSetOf()){it.supersedesTruthUid}
        val supersededSubjects=supersededTruthUids.chunked(200)
            .flatMap{repository.infrastructureWorldTruthSubjects(it.toSet()).values}
        val changedSubjects=(truthChanges.mapNotNull{it.subjectUid}+supersededSubjects).toSet()
        val changedEpistemicTruthUids=buildSet{
            truthChanges.filter{it.kind!=TruthKind.FACT}.forEach{add(it.truthUid)}
            addAll(supersededTruthUids)
        }
        if(changedSubjects.isEmpty()&&changedEpistemicTruthUids.isEmpty())return null
        val generation=historyGenerationUid;val activePlayer=repository.activePlayerRef()?.playerUid
        val scopes=listOf(
            VisibilityAudienceFactory.player(campaign) to PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),
            AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM")) to
                PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        )
        val projector=CanonicalWorldElementSemanticProjector({activePlayer},{generation})
        changedSubjects.chunked(200).forEachIndexed{groupIndex,subjectGroup->
            val ownerStates=repository.infrastructureCanonicalWorldElementsAt(subjectGroup.toSet(),replay.commitOrder)
            scopes.forEachIndexed{scopeIndex,(audience,purpose)->
                val byRecord=ownerStates.flatMap{projector.project(it,audience,purpose)}.groupBy{it.canonicalRecordUid}
                subjectGroup.forEach{subjectUid->
                    val uid="WORLD_ELEMENT:$subjectUid"
                    if(uid !in byRecord)index.retireProjection(
                        campaign,SEMANTIC_NAMESPACE_CAMPAIGN,audience.audienceKindUid,purpose.purposeUid,uid,replay.commitOrder
                    )
                }
                byRecord.values.forEachIndexed{recordIndex,documents->
                        indexRecordDocuments(
                            "BEKKO-WORLD:$campaign:${replay.commitOrder}:$groupIndex:$scopeIndex:$recordIndex",documents
                        )?.let{return it}
                    }
            }
        }
        val gmAudience=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM"))
        val gmPurpose=PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val epistemicProjector=CanonicalWorldEpistemicSemanticProjector({generation},{activePlayer})
        changedEpistemicTruthUids.chunked(200).forEachIndexed{groupIndex,truthGroup->
            val owners=repository.infrastructureCanonicalWorldEpistemicAssertionsAt(truthGroup.toSet(),replay.commitOrder)
            val byRecord=owners.flatMap{epistemicProjector.project(it,gmAudience,gmPurpose)}.groupBy{it.canonicalRecordUid}
            truthGroup.forEach{truthUid->if("WORLD_ASSERTION:$truthUid" !in byRecord)index.retireProjection(
                campaign,SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                "WORLD_ASSERTION:$truthUid",replay.commitOrder
            )}
            byRecord.values.forEachIndexed{recordIndex,documents->
                indexRecordDocuments("BEKKO-WORLD-EPISTEMIC:$campaign:${replay.commitOrder}:$groupIndex:$recordIndex",documents)
                    ?.let{return it}
            }
        }
        return null
    }
    /** Embedding may require several native batches, but metadata is published only after every
     * chunk of the record is ready. A retry can therefore never mistake a partial record for a
     * complete projection. */
    private fun indexRecordDocuments(requestPrefix:String,documents:List<SemanticDocumentProjection>):String?{
        if(documents.isEmpty())return null
        val indexed=mutableListOf<SemanticIndexedDocument>()
        documents.chunked(embeddings.capabilities.maximumBatchSize).forEachIndexed{batchIndex,batch->
            when(val result=embed("$requestPrefix:$batchIndex",batch.map{it.text})){
                is EmbeddingBatchResult.Success->indexed+=batch.zip(result.vectors).map{(document,vector)->
                    SemanticIndexedDocument(document,matryoshkaL2(vector,index.version.dimensions))
                }
                is EmbeddingBatchResult.Failure->return result.reasonUid
            }
        }
        requireCampaignStillActive()
        index.replaceRecord(indexed)
        return null
    }
    private fun embed(requestUid:String,texts:List<String>):EmbeddingBatchResult{
        if(closed.get())return EmbeddingBatchResult.Failure("BEKKO_INDEXING_CANCELLED",true)
        activeEmbeddingRequest.set(requestUid)
        return try{
            val result=embeddings.embedBatch(EmbeddingRequest(requestUid,texts,512))
            if(closed.get())EmbeddingBatchResult.Failure("BEKKO_INDEXING_CANCELLED",true) else result
        }
        finally{activeEmbeddingRequest.compareAndSet(requestUid,null)}
    }
    override fun close(){
        requestCancellation()
        val write=lifecycleLock.writeLock()
        while(!write.tryLock(100,TimeUnit.MILLISECONDS))requestCancellation()
        try{
            if(!resourcesClosed.compareAndSet(false,true))return
            // The coordinator-local writer proves that cancelled catch-up work has left its index
            // and provider. Application consumers are separately quiesced by the process writer.
            embeddings.close();index.close()
        }finally{write.unlock()}
    }
}
