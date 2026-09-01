package com.rpgos.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val BEKKO_STRUCTURED_PROVIDER_UID="RPGOS-CORE:BEKKO-SEMANTIC"
const val BEKKO_OPERATION_MEMORY="SEMANTIC_MEMORY"
const val BEKKO_OPERATION_WORLD_PACK="SEMANTIC_WORLD_PACK"
const val BEKKO_OPERATION_RELATED="SEMANTIC_RELATED"
const val SEMANTIC_NAMESPACE_CAMPAIGN="CAMPAIGN_MEMORY"
const val SEMANTIC_NAMESPACE_WORLD_PACK="WORLD_PACK"
internal fun semanticWorldPackRecordUid(option:CharacterCreationDefinitionOption)=
    "WORLDPACK:${option.kind}:${option.definitionUid}:${option.dimensionUid.orEmpty()}"
private const val SEMANTIC_QUERY_MAX_CHARS=1024 // early abuse bound; llama.cpp enforces the exact 256-token limit
private const val SEMANTIC_DOCUMENT_CHUNK_CHARS=160
private const val SEMANTIC_DOCUMENT_CHUNK_OVERLAP_CHARS=24

class SemanticStructuredQueryProvider(
    private val embeddings:EmbeddingProviderPort,
    private val index:SemanticIndexPort
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
        val authorized=index.authorizedRecordUids(request.campaignUid,namespace,audienceUid,purposeUid,at)
        if(authorized.isEmpty())return StructuredRetrievalResult.NoData
        val kinds=request.filters["record_kinds"]?.split(',')?.filter{it.isNotBlank()}?.toSet().orEmpty()
        val explicitMinimum=request.filters["minimum_score"]?.toFloatOrNull()?.coerceIn(-1f,1f)
        var candidates=index.searchAuthorized(SemanticSearchRequest(
            request.campaignUid,namespace,audienceUid,purposeUid,at,authorized,kinds,vector,
            request.limit,explicitMinimum?:0.25f
        ))
        if(explicitMinimum==null)candidates=retainSemanticRelevanceBand(candidates,0.25f)
        if(candidates.isEmpty()&&explicitMinimum==null)candidates=index.searchAuthorized(SemanticSearchRequest(
            request.campaignUid,namespace,audienceUid,purposeUid,at,authorized,kinds,vector,
            minOf(3,request.limit),0.18f
        )).take(1)
        if(candidates.isEmpty())return StructuredRetrievalResult.NoData
        return StructuredRetrievalResult.Value(candidates.map{candidate->RetrievalRecord(
            candidate.canonicalRecordUid,
            linkedMapOf(
                "semantic_score" to candidate.score.toDouble(),
                "record_kind_uid" to candidate.recordKindUid,
                "epistemic_state_uid" to candidate.epistemicStateUid,
                "source_version" to candidate.sourceVersion,
                "chunk_ordinals" to candidate.chunkEvidence.joinToString(","){it.chunkOrdinal.toString()},
                "projected_text" to candidate.chunkEvidence.joinToString("\n"){it.projectedText},
                "projection_fingerprints" to candidate.chunkEvidence.joinToString(","){it.projectedTextFingerprint},
                "candidate_only" to true
            ),
            "BEKKO:${candidate.sourceFingerprint}:${candidate.indexVersion.modelSha256}"
        )},true)
    }
}

/** Keeps semantic retrieval from padding a prompt with weak neighbours merely because topK has
 * room. Explicit developer thresholds remain exact; this band is only the production default. */
internal fun retainSemanticRelevanceBand(candidates:List<SemanticCandidate>,floor:Float):List<SemanticCandidate>{
    if(candidates.isEmpty())return emptyList()
    val cutoff=maxOf(floor,candidates.maxOf{it.score}-0.035f)
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
        val documents=repository.infrastructureReplayPayloadsAfter(after).asSequence()
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
    private val index:SemanticIndexPort
):DirectorContextScoutPort{
    override fun enrich(trigger:DirectorTrigger,context:DirectorContextEnvelope):DirectorContextEnvelope{
        if(context.projectedRecordUids.isEmpty()||embeddings.availability().state!=EmbeddingAvailabilityState.READY)return context
        val text=(listOf(trigger.kind.name)+trigger.semanticEvidenceUids+context.strategicSummarySegments).joinToString(" ").take(2048)
        val result=embeddings.embedBatch(EmbeddingRequest("BEKKO-DIRECTOR:${trigger.triggerUid}",listOf(text),512)) as? EmbeddingBatchResult.Success?:return context
        val query=matryoshkaL2(result.vectors.single(),index.version.dimensions)
        val candidates=index.searchAuthorized(SemanticSearchRequest(
            trigger.campaignUid,SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
            trigger.atCommittedOrder,context.projectedRecordUids,queryVector=query,topK=16,minimumScore=0.25f
        ))
        if(candidates.isEmpty())return context
        return context.copy(strategicSummarySegments=context.strategicSummarySegments+candidates.map{
            "BEKKO_CANDIDATE uid=${it.canonicalRecordUid} score=${"%.4f".format(java.util.Locale.ROOT,it.score)} kind=${it.recordKindUid}"
        })
    }
}

/** Produces audience-specific text only after canonical replay evidence exists. */
internal class CommittedReplaySemanticProjector(
    private val activePlayerUid:()->String?={null},
    private val visibility:VisibilityAuthorityService=VisibilityAuthorityService()
):SemanticDocumentProjector{
    fun boundToActivePlayer(playerUid:String?)=CommittedReplaySemanticProjector({playerUid},visibility)

    override fun project(source:Any,audience:AudienceContext,purpose:PurposeContext):List<SemanticDocumentProjection>{
        val replay=source as? CommittedReplayPayload?:return emptyList()
        require(replay.identity.campaignUid==audience.campaignUid&&replay.identity.campaignUid==purpose.campaignUid)
        val controlledPlayer=activePlayerUid()
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
        val records=mutableListOf<Triple<String,String,String>>()
        replay.changeSet.eventIntents.forEach{event->
            val owned=controlledPlayer!=null&&event.actorRef?.uid==controlledPlayer
            val visibilityKind=if(owned)VisibilitySubjectKinds.PLAYER_STATE else VisibilitySubjectKinds.WORLD_EVENT_GM_DETAIL
            val visibilityUid=if(owned)requireNotNull(controlledPlayer) else event.eventIntentUid
            if(!disclosed(visibilityKind,visibilityUid))return@forEach
            val actor=event.actorRef?.let{"${it.kindUid}:${it.uid}"}?:"UNKNOWN_ACTOR"
            val targets=event.targetRefs.joinToString(","){"${it.kindUid}:${it.uid}"}
            val effect=(event.payload as? DomainEffectEventIntentPayload)?.let{"${it.subject.kindUid}:${it.subject.uid}:${it.effectKindUid}"}.orEmpty()
            records+=Triple("EVENT:${event.eventIntentUid}",event.eventKindUid,"Actor $actor wykonał ${event.eventKindUid}; cele: $targets; efekt: $effect")
        }
        val worldTruthGroups=replay.changeSet.changes.mapNotNull{change->
            (change.payload as? CampaignTruthChange)?.takeIf{it.subjectUid!=null&&it.predicate in CampaignWorldFacts.ALL}
        }.groupBy{requireNotNull(it.subjectUid)}
        worldTruthGroups.toSortedMap().forEach{(subjectUid,facts)->
            val explicitlyPlayerVisible=facts.any{
                it.predicate==CampaignWorldFacts.AUDIENCE_SCOPE&&it.objectValue==CampaignWorldAudience.PLAYER_VISIBLE
            }
            val gmAudience=audience.audienceKindUid==AudienceKinds.GM_RUNTIME
            if(!gmAudience&&!explicitlyPlayerVisible)return@forEach
            val visibilityKind=if(explicitlyPlayerVisible)VisibilitySubjectKinds.WORLD_PRESENTATION else VisibilitySubjectKinds.CAMPAIGN_TRUTH
            if(!disclosed(visibilityKind,subjectUid))return@forEach
            val description=facts.sortedWith(compareBy<CampaignTruthChange>{it.predicate}.thenBy{it.truthUid})
                .joinToString("; "){fact->"${fact.predicate.substringAfterLast(':')}=${fact.objectValue.orEmpty()}"}
            records+=Triple("WORLD_ELEMENT:$subjectUid","WORLD_ELEMENT","Element świata $subjectUid; $description")
        }
        replay.changeSet.changes.forEach{change->
            val payload=change.payload
            if(payload is CampaignTruthChange&&payload.subjectUid!=null&&payload.predicate in CampaignWorldFacts.ALL)return@forEach
            val subject=subject(payload)
            val owned=controlledPlayer!=null&&subject?.uid==controlledPlayer
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
            records+=Triple("CHANGE:${change.changeUid}",change.changeKindUid,describe(payload))
        }
        return records.flatMap{(recordUid,kind,text)->
            chunk(text).mapIndexed{ordinal,part->SemanticDocumentProjection(
                replay.identity.campaignUid,SEMANTIC_NAMESPACE_CAMPAIGN,audience.audienceKindUid,purpose.purposeUid,
                recordUid,kind,epistemic(kind,replay,recordUid),replay.commitOrder,replay.commitOrder,
                semanticSha256("${replay.semanticFingerprint}|$recordUid|$kind|$part"),ordinal,part
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
        is AccessAuthorityChange->payload.subjectUid?.let{DomainRef(payload.subjectKindUid!!,it)}
        is AssetChange,is CampaignTruthChange,is DevelopmentProjectChange,is FinancialChange,is OwnershipChange->null
    }

    private fun epistemic(kind:String,replay:CommittedReplayPayload,uid:String):String{
        val changeUid=uid.substringAfter("CHANGE:","")
        val truth=replay.changeSet.changes.firstOrNull{it.changeUid==changeUid}?.payload as? CampaignTruthChange
        return truth?.kind?.name?:"FACT"
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
        is AccessAuthorityChange->"Zmiana autoryzacji dostępu ${payload}"
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

/** Immediate, idempotent, post-commit indexing. No periodic scheduler is created. */
class ImmediateSemanticIndexCoordinator(
    private val repository:UnifiedGameRepository,
    private val embeddings:EmbeddingProviderPort,
    private val index:SemanticIndexPort,
    private val projector:SemanticDocumentProjector=CommittedReplaySemanticProjector(
        activePlayerUid={repository.activePlayerRef()?.playerUid}
    ),
    /** The coordinator is a per-campaign cache worker. It must never silently follow the
     * process-wide active campaign while a previous asynchronous catch-up is still running. */
    private val campaignUid:String=repository.activeCampaignRef().campaignId,
    private val executor:ExecutorService=Executors.newSingleThreadExecutor{r->Thread(r,"rpgos-bekko-index").apply{isDaemon=true}},
    private val onProgress:(SemanticIndexProgress)->Unit={}
):AutoCloseable{
    private val running=AtomicBoolean(false)
    private val readyForQueries=AtomicBoolean(false)
    private val activeEmbeddingRequest=AtomicReference<String?>(null)
    fun readyForQueries():Boolean=readyForQueries.get()
    fun onCanonicalCommit(){readyForQueries.set(false);executor.execute{catchUp()}}
    fun onCampaignOpened(){readyForQueries.set(false);executor.execute{catchUp()}}
    fun catchUp():SemanticIndexStatus{
        if(!running.compareAndSet(false,true))return safeStatus()
        readyForQueries.set(false)
        try{
            val campaign=campaignUid
            requireCampaignStillActive()
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
            val after=index.checkpoint(campaign)
            requireCampaignStillActive()
            val replays=repository.infrastructureReplayPayloadsAfter(after)
            requireCampaignStillActive()
            val catchUpProjector=(projector as? CommittedReplaySemanticProjector)
                ?.boundToActivePlayer(repository.activePlayerRef()?.playerUid)?:projector
            onProgress(SemanticIndexProgress(true,"CANONICAL_CATCH_UP",0,replays.size,after))
            replays.forEachIndexed{replayIndex,replay->
                val audiences=listOf(
                    VisibilityAudienceFactory.player(campaign) to PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),
                    AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM")) to PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION)
                )
                val documents=audiences.flatMap{(audience,purpose)->catchUpProjector.project(replay,audience,purpose)}
                val indexed=documents.chunked(embeddings.capabilities.maximumBatchSize).flatMapIndexed{batchIndex,batch->
                    when(val result=embed("BEKKO-INDEX:${replay.identity.transactionUid}:$batchIndex",batch.map{it.text})){
                        is EmbeddingBatchResult.Success->batch.zip(result.vectors).map{(document,vector)->SemanticIndexedDocument(document,matryoshkaL2(vector,index.version.dimensions))}
                        is EmbeddingBatchResult.Failure->{
                            onProgress(SemanticIndexProgress(false,"FAILED",replayIndex,replays.size,index.checkpoint(campaign),result.reasonUid))
                            return index.status(campaign).copy(ready=false,reasonUid=result.reasonUid)
                        }
                    }
                }
                index.upsertBatch(indexed);index.advanceCheckpoint(campaign,replay.commitOrder)
                onProgress(SemanticIndexProgress(true,"CANONICAL_CATCH_UP",replayIndex+1,replays.size,replay.commitOrder))
            }
            requireCampaignStillActive()
            return index.status(campaign).also{status->
                readyForQueries.set(true)
                onProgress(SemanticIndexProgress(false,"READY",replays.size,replays.size,status.lastIndexedCommitOrder))
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
        check(repository.activeCampaignRef().campaignId==campaignUid){"BEKKO_CAMPAIGN_CHANGED"}
        if(Thread.currentThread().isInterrupted)throw InterruptedException("BEKKO_INDEXING_CANCELLED")
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
        requireCampaignStillActive()
        scopes.forEach{(audience,purpose)->
            val existing=index.authorizedRecordUids(campaign,SEMANTIC_NAMESPACE_WORLD_PACK,audience,purpose,Long.MAX_VALUE)
            val missing=options.filter{option->semanticWorldPackRecordUid(option) !in existing}
            missing.chunked(embeddings.capabilities.maximumBatchSize).forEachIndexed{batchIndex,batch->
                val projections=batch.map{option->
                    val uid=semanticWorldPackRecordUid(option)
                    val text=listOf(option.kind.name,option.definitionUid,option.displayName,option.dimensionUid,option.minimumValue,option.maximumValue).filterNotNull().joinToString(" ")
                    SemanticDocumentProjection(
                        campaign,SEMANTIC_NAMESPACE_WORLD_PACK,audience,purpose,uid,"WORLD_PACK_${option.kind}","DEFINITION",
                        0,1,semanticSha256(text),0,text
                    )
                }
                when(val result=embed("BEKKO-WORLDPACK:$campaign:$audience:$batchIndex",projections.map{it.text})){
                    is EmbeddingBatchResult.Success->index.upsertBatch(projections.zip(result.vectors).map{(projection,vector)->
                        SemanticIndexedDocument(projection,matryoshkaL2(vector,index.version.dimensions))
                    })
                    is EmbeddingBatchResult.Failure->return result.reasonUid
                }
            }
        }
        return null
    }
    private fun embed(requestUid:String,texts:List<String>):EmbeddingBatchResult{
        activeEmbeddingRequest.set(requestUid)
        return try{embeddings.embedBatch(EmbeddingRequest(requestUid,texts,512))}
        finally{activeEmbeddingRequest.compareAndSet(requestUid,null)}
    }
    override fun close(){
        readyForQueries.set(false)
        activeEmbeddingRequest.get()?.let(embeddings::cancel)
        executor.shutdownNow()
        // Do not close JNI/index resources underneath a catch-up that is still unwinding. The
        // bounded wait keeps campaign activation deterministic without creating a periodic task.
        if(!Thread.currentThread().name.startsWith("rpgos-bekko-index")){
            runCatching{executor.awaitTermination(15,TimeUnit.SECONDS)}
        }
        embeddings.close();index.close()
    }
}
