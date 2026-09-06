package com.rpgos.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class Phase59CanonicalRehydrationTest {
    private val campaign = "C1"
    private val audience = VisibilityAudienceFactory.player(campaign)
    private val purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION)

    @Test
    fun consolidatedHolderMemoryIsProjectedOnlyForItsActiveHolderAndKeepsBeliefEpistemicKind(){
        val artifact=ActiveMemoryArtifactRevision(
            campaignUid=campaign,historyGenerationUid=HistoryGenerationUid("GEN-MEMORY"),
            logicalArtifactUid="ASSERT-LOGICAL",artifactRevisionUid="ASSERT-REV",
            artifactKind=MemoryArtifactKind.SEMANTIC_ASSERTION,sourceLeafSetFingerprint="LEAF-FP",
            derivationVersion=1,asOfCommittedOrder=7,
            payloadJson=JSONObject().apply{
                put("holder_kind_uid",KnowledgeHolderKinds.CHARACTER);put("holder_uid","PLAYER-1")
                put("subject_kind_uid","NPC");put("subject_uid","NPC-1");put("predicate_uid","OWES")
                put("object_value","FAVOR");put("polarity",SemanticAssertionPolarity.AFFIRMED.name)
                put("epistemic_kind",SemanticAssertionEpistemicKind.BELIEF.name)
                put("lifecycle",SemanticAssertionLifecycle.ACTIVE.name)
            }.toString()
        )
        val playerAudience=VisibilityAudienceFactory.player(campaign)
        val owner=ConsolidatedMemorySemanticProjector(activePlayerUid={"PLAYER-1"})
            .project(artifact,playerAudience,purpose)
        val stranger=ConsolidatedMemorySemanticProjector(activePlayerUid={"PLAYER-2"})
            .project(artifact,playerAudience,purpose)

        assertEquals(1,owner.size)
        assertEquals("MEMORY:ASSERT-REV",owner.single().canonicalRecordUid)
        assertEquals("BELIEF",owner.single().epistemicStateUid)
        assertFalse(owner.single().epistemicStateUid=="FACT")
        assertTrue(stranger.isEmpty())
    }

    @Test
    fun episodeInterpretationRemainsNarrativeAndIsAvailableOnlyToPrivilegedGm(){
        val artifact=ActiveMemoryArtifactRevision(
            campaignUid=campaign,historyGenerationUid=HistoryGenerationUid("GEN-MEMORY"),
            logicalArtifactUid="INT-LOGICAL",artifactRevisionUid="INT-REV",
            artifactKind=MemoryArtifactKind.EPISODE_INTERPRETATION,sourceLeafSetFingerprint="LEAF-FP",
            derivationVersion=1,asOfCommittedOrder=7,
            payloadJson=JSONObject().apply{
                put("episode_logical_uid","EP-1");put("title","Most");put("summary","Spotkanie na moście")
                put("tags",org.json.JSONArray(listOf("spotkanie")))
            }.toString()
        )
        val projector=ConsolidatedMemorySemanticProjector(activePlayerUid={"PLAYER-1"})
        val player=projector.project(artifact,audience,purpose)
        val gmAudience=AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM"))
        val gm=projector.project(artifact,gmAudience,PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION))

        assertTrue(player.isEmpty())
        assertEquals("NARRATIVE",gm.single().epistemicStateUid)
        assertTrue(gm.single().text.contains("Spotkanie na moście"))
    }

    @Test
    fun contextBuilderMapsBeliefAndConstraintEpistemicStatesWithoutUnconditionalProjectedFactFlattening(){
        val provider=StructuredProviderBinding(
            "TEST",setOf("STATE"),StructuredQueryProvider{request->
                val payload = when(request.filters["query_text"]){
                    "BELIEF"->"BELIEF"
                    "RULE"->"SYSTEM_CONSTRAINT"
                    "UNKNOWN"->"UNKNOWN"
                    "FACT"->"FACT"
                    "MEMORY"->"MEMORY"
                    else->"OTHER"
                }
                StructuredRetrievalResult.Value(listOf(RetrievalRecord("R-1",mapOf("epistemic_state_uid" to payload),"P")),complete=true)
            }
        )
        val retriever = StructuredSqlRetriever(listOf(provider))
        val builder = ContextIntegrityBuilder(retriever)

        assertEquals(ContextEpistemicState.HOLDER_BELIEF, build(builder,"BELIEF").records.single().epistemicState)
        assertEquals(ContextEpistemicState.MEMORY, build(builder,"MEMORY").records.single().epistemicState)
        assertEquals(ContextEpistemicState.SYSTEM_CONSTRAINT, build(builder,"RULE").records.single().epistemicState)
        assertEquals(ContextEpistemicState.UNKNOWN, build(builder,"UNKNOWN").records.single().epistemicState)
        assertEquals(ContextEpistemicState.PROJECTED_FACT, build(builder,"FACT").records.single().epistemicState)
        assertEquals(ContextEpistemicState.UNKNOWN, build(builder,"OTHER").records.single().epistemicState)
    }

    @Test
    fun semanticProviderRejectsHistoryGenerationMismatchBeforeRehydration(){
        val request = StructuredRetrievalRequest(
            "Q",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "straw"),1,audience,purpose,atOrder=10
        )
        val staleCandidate = SemanticCandidate(
            canonicalRecordUid = "HIST-1",
            score = SemanticSimilarityScore(0.8f),
            recordKindUid = "EVENT",
            epistemicStateUid = "BELIEF",
            sourceFingerprint = "FP-STALE",
            sourceVersion = 2L,
            chunkEvidence = listOf(SemanticChunkEvidence(0,"stale","TEXT-STALE")),
            indexVersion = SemanticIndexVersion(),
            sourceAsOfOrder = 5L
        )
        val staleProjection = SemanticSourceProjectionState(
            sourceAsOfOrder = 5L,
            sourceVersion = 2L,
            sourceFingerprint = "FP-STALE",
            principalUid = "HUMAN_PLAYER",
            holderSetFingerprint = "GLOBAL",
            historyGenerationUid = HistoryGenerationUid("OLD-GEN"),
            accessPolicyVersion = 0L,
            activePlayerUid = null,
            projectionVersionUid = VisibilityAuthorityService.PROJECTION_VERSION_UID
        )
        val scopeRequestGeneration = HistoryGenerationUid("ACTIVE-GEN")
        var rehydrated = false
        val index = object: SemanticIndexPort{
            override val version = SemanticIndexVersion()
            override fun upsertBatch(documents:List<SemanticIndexedDocument>){}
            override fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String){}
            override fun authorizedRecordUids(campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long)=
                setOf(staleCandidate.canonicalRecordUid)
            override fun searchAuthorized(request:SemanticSearchRequest)=listOf(staleCandidate)
            override fun currentProjections(request:SemanticSearchRequest):Map<String,SemanticSourceProjectionState>{
                return mapOf(staleCandidate.canonicalRecordUid to staleProjection)
            }
            override fun checkpoint(campaignUid:String)=0L
            override fun advanceCheckpoint(campaignUid:String,committedOrder:Long)=Unit
            override fun status(campaignUid:String)=SemanticIndexStatus(
                ready=true,recordCount=1,chunkCount=1,lastIndexedCommitOrder=1,version=version
            )
            override fun clear(campaignUid:String)=Unit
            override fun close()=Unit
        }
        val provider = SemanticStructuredQueryProvider(
            StubEmbeddingProvider(),
            index,
            object: SemanticRuntimeScopeResolver{
                override fun resolve(request:StructuredRetrievalRequest,namespaceUid:String)=
                    SemanticRuntimeScope(
                        historyGenerationUid = scopeRequestGeneration,
                        principalUid = "HUMAN_PLAYER",
                        holderSetFingerprint = "GLOBAL",
                        accessPolicyVersion = 0L,
                        activePlayerUid = null
                    )
            },
            object: SemanticCanonicalRehydrationPort{
                override fun rehydrate(
                    request:SemanticSearchRequest,
                    candidates:List<SemanticCandidate>
                ):Map<String, CanonicallyRehydratedSemanticRecord>{
                    rehydrated = true
                    return mapOf(staleCandidate.canonicalRecordUid to CanonicallyRehydratedSemanticRecord(
                        canonicalRecordUid = staleCandidate.canonicalRecordUid,
                        recordKindUid = staleCandidate.recordKindUid,
                        epistemicStateUid = staleCandidate.epistemicStateUid,
                        sourceFingerprint = staleCandidate.sourceFingerprint,
                        sourceVersion = staleCandidate.sourceVersion,
                        sourceAsOfOrder = staleCandidate.sourceAsOfOrder,
                        projectedText = "CANONICAL-STALE",
                        chunkEvidence = staleCandidate.chunkEvidence,
                        projectionBoundaryUid = "BEKKO-REHYDRATED:STALE"
                    ))
                }
            }
        )
        val result = provider.retrieve(request)
        assertEquals(StructuredRetrievalResult.NoData::class.java, result::class.java)
        assertFalse(rehydrated)
    }

    @Test
    fun requiredAndSafetyRecordsHavePriorityOverHigherScoredSemanticCandidatesBeforeRelevanceOrdering(){
        val scope = WorkingMemoryScope(
            campaignUid = campaign,
            historyGenerationUid = HistoryGenerationUid("WORKING-GEN"),
            audienceKindUid = AudienceKinds.PLAYER,
            principalUid = "PLAYER-ONE",
            purposeUid = VisibilityPurposeKinds.GAMEPLAY_NARRATION,
            sceneUid = null,
            asOfCommittedOrder = 10L,
            accessPolicyVersion = 1L
        )
        val required = workingSegment("REQ","R-COMMON",RequirementImportance.REQUIRED,0.01, "BOUNDARY-REQ")
        val safety = workingSegment("SAFE","R-COMMON",RequirementImportance.SAFETY,0.01,"BOUNDARY-SAFE")
        val exactLike = workingSegment("EXACT","R-EXACT",RequirementImportance.OPTIONAL,1.0,"BOUNDARY-EXACT")
        val candidate = CanonicalContextCandidate(
            plan = CanonicalTurnPlan(
                planUid="PLAN-1",campaignUid=campaign,
                intent=IntentDocument(
                    campaignUid = campaign,actor = CommandActorRef("PLAYER","PLAYER-ONE"),rawInput = "query",
                    meaningState = MeaningState.UNDERSTOOD,nodes = emptyList(),
                    provenance = IntentInterpretationProvenance(
                        IntentInterpretationSource.LEGACY_RULE,
                        "LEGACY","1","legacy:test"
                    )
                ),
                audience = AudienceContext(campaign,AudienceKinds.PLAYER),
                purpose = PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),
                steps=listOf(
                    CanonicalTurnPlanStep(
                        stepUid="STEP",nodeUid="NODE",capabilityUid="CAP",matchState=CapabilityMatchState.EXACT,
                        dependencyNodeUids=emptyList(),requirements=listOf(required.requirement,safety.requirement,exactLike.requirement),
                        executionKind=CapabilityExecutionKind.READ_CONTEXT,
                        sideEffectClass=CapabilitySideEffectClass.NONE,mechanicsOwnerUid=null
                    )
                )
            ),
            core = SemanticCoreCapsule(
                campaignUid = campaign,
                planUid = "PLAN-1",
                intentHash = "INTENT-HASH",
                intentCanonicalPayload = "{\"intent\":\"legacy\"}",
                planSemanticPayload = "PLAN-PAYLOAD",
                activeNodeUids = listOf("NODE"),
                capabilityUids = listOf("CAP"),
                dependencyEdges = emptyList(),
                hardDirectiveUids = emptyList()
            ),
            segments = listOf(required,safety,exactLike)
        )
        val budgeted = BudgetedCanonicalContext(
            candidate = candidate,
            includedSegments = listOf(required,safety,exactLike),
            omissions = emptyList(),
            coreUnits = 1,
            segmentUnits = 1,
            payloadCapacityUnits = 100_000,
            finalSerializedUnits = 1,
            safeForAi = true,
            reasonUids = emptyList()
        )
        val snapshot = WorkingMemoryOwner.materialize(scope,budgeted)

        assertEquals("R-COMMON",snapshot.records.first().canonicalRecordUid)
        assertTrue(snapshot.records[0].pinned)
        // REQUIRED and SAFETY point at the same canonical UID, so Working Memory keeps one pinned
        // record rather than duplicating the same authority payload.
        assertEquals(1,snapshot.records.count{it.pinned})
        assertEquals("R-EXACT",snapshot.records.singleOrNull{!it.pinned}?.canonicalRecordUid)
    }

    @Test
    fun semanticSearchRequestCarriesHistoryPrincipalActivePlayerAndPolicyToCanonicalScope(){
        val scopeGeneration=HistoryGenerationUid("GEN-SCOPE")
        val requested=SemanticScopedIndex(
            currentProjection = SemanticSourceProjectionState(
                sourceAsOfOrder = 4L,
                sourceVersion = 1L,
                sourceFingerprint = "FP_SCOPE",
                principalUid = "ACTIVE_PRINCIPAL",
                holderSetFingerprint = "HOLDER-SCOPE",
                historyGenerationUid = scopeGeneration,
                accessPolicyVersion = 12L,
                activePlayerUid = "ACTIVE_PLAYER",
                projectionVersionUid = VisibilityAuthorityService.PROJECTION_VERSION_UID
            ),
            staleCandidate = SemanticCandidate(
                canonicalRecordUid = "S1",
                score = SemanticSimilarityScore(0.8f),
                recordKindUid = "EVENT",
                epistemicStateUid = "FACT",
                sourceFingerprint = "FP_SCOPE",
                sourceVersion = 1L,
                chunkEvidence = listOf(SemanticChunkEvidence(0,"scoped","TEXT-SCOPED")),
                indexVersion = SemanticIndexVersion(),
                sourceAsOfOrder = 4L
            ),
            expectedScope = {scope ->
                assertEquals(scopeGeneration, scope.historyGenerationUid)
                assertEquals("ACTIVE_PRINCIPAL", scope.principalUid)
                assertEquals("HOLDER-SCOPE", scope.holderSetFingerprint)
                assertEquals(12L, scope.accessPolicyVersion)
                assertEquals("ACTIVE_PLAYER", scope.activePlayerUid)
            }
        )
        val request = StructuredRetrievalRequest(
            "S",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "straw"),1,audience,purpose,atOrder=10
        )
        val provider = SemanticStructuredQueryProvider(
            StubEmbeddingProvider(),
            requested,
            object:SemanticRuntimeScopeResolver{
                override fun resolve(request:StructuredRetrievalRequest,namespaceUid:String)=SemanticRuntimeScope(
                    historyGenerationUid = scopeGeneration,
                    principalUid = "ACTIVE_PRINCIPAL",
                    holderSetFingerprint = "HOLDER-SCOPE",
                    accessPolicyVersion = 12L,
                    activePlayerUid = "ACTIVE_PLAYER"
                )
            }
        )
        val result=provider.retrieve(request)
        assertEquals(StructuredRetrievalResult.Value::class.java,result::class.java)
        assertEquals(scopeGeneration, requested.requestedScope?.historyGenerationUid)
        assertEquals("ACTIVE_PRINCIPAL", requested.requestedScope?.principalUid)
        assertEquals("HOLDER-SCOPE", requested.requestedScope?.holderSetFingerprint)
        assertEquals(12L, requested.requestedScope?.accessPolicyVersion)
        assertEquals("ACTIVE_PLAYER", requested.requestedScope?.activePlayerUid)
    }

    @Test
    fun semanticProviderRejectsMismatchedCanonicalRehydrationAndReturnsCanonicalTextWhenMatching(){
        val embedding = StubEmbeddingProvider()
        val request = StructuredRetrievalRequest(
            "Q",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "straw"),1,audience,purpose,atOrder=10
        )
        val expectedProjection = SemanticSourceProjectionState(
            sourceAsOfOrder = 3L,
            sourceVersion = 2L,
            sourceFingerprint = "FP_CANONICAL",
            principalUid = "HUMAN_PLAYER",
            holderSetFingerprint = "GLOBAL",
            historyGenerationUid = HistoryGenerationUid("GEN-1"),
            accessPolicyVersion = 8L,
            activePlayerUid = "HUMAN_PLAYER",
            projectionVersionUid = VisibilityAuthorityService.PROJECTION_VERSION_UID
        )
        val candidate = SemanticCandidate(
            canonicalRecordUid = "OLD",
            score = SemanticSimilarityScore(0.6f),
            recordKindUid = "EVENT",
            epistemicStateUid = "BELIEF",
            sourceFingerprint = "FP_CANONICAL",
            sourceVersion = 2L,
            chunkEvidence = listOf(SemanticChunkEvidence(0,"stale","TEXT-OLD")),
            indexVersion = SemanticIndexVersion(),
            sourceAsOfOrder = 3L
        )
        val index = HistoricProjectionIndex(expectedProjection,candidate)
        val staleCanonical = SemanticCanonicalRehydrationPort{_,_ ->
            mapOf("OLD" to CanonicallyRehydratedSemanticRecord(
                canonicalRecordUid = "OLD",
                recordKindUid = "EVENT",
                epistemicStateUid = "BELIEF",
                sourceFingerprint = "FP_DIFFERENT",
                sourceVersion = 1L,
                sourceAsOfOrder = 2L,
                projectedText = "CANONICAL-OLD",
                chunkEvidence = listOf(SemanticChunkEvidence(0,"CANONICAL-OLD","FP_DIFFERENT")),
                projectionBoundaryUid = "BEKKO-REHYDRATED:DIFF"
            ))
        }
        val freshCanonical = SemanticCanonicalRehydrationPort{_,_ ->
            mapOf("OLD" to CanonicallyRehydratedSemanticRecord(
                canonicalRecordUid = "OLD",
                recordKindUid = "EVENT",
                epistemicStateUid = "MEMORY",
                sourceFingerprint = "FP_CANONICAL",
                sourceVersion = 2L,
                sourceAsOfOrder = 3L,
                projectedText = "CANONICAL-LATEST",
                chunkEvidence = listOf(
                    SemanticChunkEvidence(0,"CANONICAL-LATEST-A","HASH-A"),
                    SemanticChunkEvidence(1,"CANONICAL-LATEST-B","HASH-B")
                ),
                projectionBoundaryUid = "REHYDRATED-PROJECTION-BOUNDARY"
            ))
        }

        var result=SemanticStructuredQueryProvider(embedding,index,canonicalRehydration = staleCanonical).retrieve(request)
        assertEquals(StructuredRetrievalResult.NoData::class.java,result::class.java)

        result=SemanticStructuredQueryProvider(embedding,index,canonicalRehydration = freshCanonical).retrieve(request)
        val value = result as StructuredRetrievalResult.Value
        assertEquals("CANONICAL-LATEST",value.records.single().values["projected_text"])
        assertEquals("REHYDRATED-PROJECTION-BOUNDARY",value.records.single().provenanceUid)
        assertEquals(2L,value.records.single().values["source_version"])
        assertEquals(listOf("OLD"),value.records.map{it.recordUid})
    }

    @Test
    fun worldPackScopeIsGlobalAndWithoutHistoryGeneration(){
        val projected = SemanticSourceProjectionState(
            sourceAsOfOrder = 1L,
            sourceVersion = 1L,
            sourceFingerprint = "WORLD_FP",
            principalUid = "HUMAN_PLAYER",
            holderSetFingerprint = "GLOBAL",
            historyGenerationUid = HistoryGenerationUid("MUST_BE_STRIPPED"),
            accessPolicyVersion = 5L,
            activePlayerUid = null,
            projectionVersionUid = VisibilityAuthorityService.PROJECTION_VERSION_UID
        )
        val index = SemanticScopedIndex(
            currentProjection = projected,
            staleCandidate = SemanticCandidate(
                canonicalRecordUid = "WORLD_SCOPE",
                score = SemanticSimilarityScore(0.7f),
                recordKindUid = "WORLD_PACK",
                epistemicStateUid = "MEMORY",
                sourceFingerprint = "WORLD_FP",
                sourceVersion = 1L,
                chunkEvidence = listOf(SemanticChunkEvidence(0,"world","WORLD-TEXT")),
                indexVersion = SemanticIndexVersion(),
                sourceAsOfOrder = 1L
            ),
            expectedScope = {scope ->
                assertEquals("GLOBAL", scope.holderSetFingerprint)
                assertEquals(0L, scope.accessPolicyVersion)
                assertEquals(null, scope.historyGenerationUid)
                assertEquals(null, scope.activePlayerUid)
            }
        )
        val request = StructuredRetrievalRequest(
            "W",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_WORLD_PACK,
            mapOf("query_text" to "world"),1,audience,purpose,atOrder=10
        )
        val provider = SemanticStructuredQueryProvider(
            StubEmbeddingProvider(),
            index,
            object:SemanticRuntimeScopeResolver{
                override fun resolve(request:StructuredRetrievalRequest,namespaceUid:String)=SemanticRuntimeScope(
                    historyGenerationUid = if(namespaceUid==SEMANTIC_NAMESPACE_WORLD_PACK) null else HistoryGenerationUid("HUMAN"),
                    principalUid = request.audience.principal?.uid ?: request.audience.audienceKindUid,
                    holderSetFingerprint = if(namespaceUid==SEMANTIC_NAMESPACE_WORLD_PACK) "GLOBAL" else "GLOBAL",
                    accessPolicyVersion = if(namespaceUid==SEMANTIC_NAMESPACE_WORLD_PACK) 0L else 4L,
                    activePlayerUid = if(namespaceUid==SEMANTIC_NAMESPACE_WORLD_PACK) null else "PLAYER"
                )
            }
        )

        val result=provider.retrieve(request)
        assertEquals(StructuredRetrievalResult.Value::class.java,result::class.java)
        assertEquals(null,index.requestedScope?.historyGenerationUid)
        assertEquals("GLOBAL",index.requestedScope?.holderSetFingerprint)
        assertEquals(0L,index.requestedScope?.accessPolicyVersion)
        assertEquals(null,index.requestedScope?.activePlayerUid)
    }

    @Test
    fun semanticProviderRehydratesFromCurrentProjectionsAndRejectsStaleProjectionRows(){
        val embedding = StubEmbeddingProvider()
        val request = StructuredRetrievalRequest(
            "Q",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "straw"),1,audience,purpose,atOrder=10
        )
        val requested=HistoricProjectionIndex(
            currentProjection = SemanticSourceProjectionState(
                sourceAsOfOrder = 3L,
                sourceVersion = 2L,
                sourceFingerprint = "FP_NEW",
                principalUid = "HUMAN_PLAYER",
                holderSetFingerprint = "GLOBAL",
                historyGenerationUid = HistoryGenerationUid("GEN-1"),
                accessPolicyVersion = 8L,
                activePlayerUid = "HUMAN_PLAYER",
                projectionVersionUid = VisibilityAuthorityService.PROJECTION_VERSION_UID
            ),
            staleCandidate = SemanticCandidate(
                canonicalRecordUid = "OLD",
                score = SemanticSimilarityScore(0.6f),
                recordKindUid = "EVENT",
                epistemicStateUid = "BELIEF",
                sourceFingerprint = "FP_OLD",
                sourceVersion = 1L,
                chunkEvidence = listOf(SemanticChunkEvidence(0,"stale","TEXT-OLD")),
                indexVersion = SemanticIndexVersion(),
                sourceAsOfOrder = 2L
            )
        )
        var result=SemanticStructuredQueryProvider(embedding,requested).retrieve(request)
        assertEquals(StructuredRetrievalResult.NoData::class.java,result::class.java)

        val accepted=HistoricProjectionIndex(
            currentProjection = requested.currentProjection,
            staleCandidate = requested.staleCandidate.copy(
                sourceFingerprint = "FP_NEW",
                sourceVersion = 2L,
                sourceAsOfOrder = 3L
            )
        )
        result=SemanticStructuredQueryProvider(embedding,accepted).retrieve(request)
        val value = result as StructuredRetrievalResult.Value
        assertEquals(listOf("OLD"),value.records.map{it.recordUid})
    }

    @Test
    fun semanticProviderRejectsPrincipalMismatchEvenWhenCandidateIsAuthorized(){
        val embedding = StubEmbeddingProvider()
        val request = StructuredRetrievalRequest(
            "Q",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "straw"),1,audience,purpose,atOrder=10
        )
        val mismatchPrincipal=HistoricProjectionIndex(
            currentProjection = SemanticSourceProjectionState(
                sourceAsOfOrder = 1L,
                sourceVersion = 1L,
                sourceFingerprint = "FP",
                principalUid = "OTHER_PRINCIPAL",
                holderSetFingerprint = "GLOBAL",
                historyGenerationUid = HistoryGenerationUid("GEN-1"),
                accessPolicyVersion = 0L,
                activePlayerUid = "OTHER_PRINCIPAL",
                projectionVersionUid = VisibilityAuthorityService.PROJECTION_VERSION_UID
            ),
            staleCandidate = SemanticCandidate(
                canonicalRecordUid = "C1",
                score = SemanticSimilarityScore(0.6f),
                recordKindUid = "EVENT",
                epistemicStateUid = "FACT",
                sourceFingerprint = "FP",
                sourceVersion = 1L,
                chunkEvidence = listOf(SemanticChunkEvidence(0,"same","TEXT")),
                indexVersion = SemanticIndexVersion(),
                sourceAsOfOrder = 1L
            )
        )
        val result=SemanticStructuredQueryProvider(embedding,mismatchPrincipal).retrieve(request)
        assertEquals(StructuredRetrievalResult.NoData::class.java,result::class.java)
    }

    private fun build(builder:ContextIntegrityBuilder,queryText:String):CanonicalContextSegment{
        val request=StructuredRetrievalRequest(
            "R-$queryText",campaign,"TEST","STATE",mapOf("query_text" to queryText),1,
            audience,purpose
        )
        val envelope=CapabilityEnvelope("E-$queryText",campaign,"TEST","STATE",setOf("query_text"),maximumLimit=1,audience=audience,purpose=purpose)
        val requirement=PlannedRequirement("REQ-$queryText","N",RequirementImportance.REQUIRED,request,envelope)
        return builder.read(requirement,request)
    }

    private fun workingSegment(
        requirementUid: String,
        recordUid: String,
        importance: RequirementImportance,
        semanticScore: Double,
        boundaryUid: String
    )=CanonicalContextSegment(
        segmentUid = "SEGMENT-$requirementUid",
        requirement = plannedRequirement(requirementUid,importance),
        state = RetrievalState.VALUE,
        records = listOf(
            CanonicalContextRecord(
                record = RetrievalRecord(recordUid,mapOf("semantic_score" to semanticScore)),
                epistemicState = ContextEpistemicState.PROJECTED_FACT,
                projectionBoundaryUid = boundaryUid,
                sourceRequirementUid = "REQ-$requirementUid"
            )
        ),
        complete = true,
        continuation = RetrievalContinuation.COMPLETE
    )

    private fun plannedRequirement(requirementUid: String, importance: RequirementImportance): PlannedRequirement {
        val audience = AudienceContext(campaign, AudienceKinds.PLAYER)
        val purpose = PurposeContext(campaign, VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val request = StructuredRetrievalRequest("REQ-$requirementUid", campaign, "WORLD", "READ", emptyMap(), 100, audience, purpose)
        val envelope = CapabilityEnvelope(
            "ENV-$requirementUid",
            campaign,
            "WORLD",
            "READ",
            emptySet(),
            maximumLimit = 100,
            audience = audience,
            purpose = purpose
        )
        return PlannedRequirement("REQ-$requirementUid", "NODE", importance, request, envelope)
    }

    private class StubEmbeddingProvider:EmbeddingProviderPort{
        override val capabilities=EmbeddingCapabilities(
            "TEST","TEST", "1",256,setOf(256),8192,8,setOf(EmbeddingBackend.CPU)
        )
        override fun availability()=EmbeddingAvailability(EmbeddingAvailabilityState.READY,"R")
        override fun open()=availability()
        override fun embedBatch(request:EmbeddingRequest)=EmbeddingBatchResult.Success(listOf(
            run {
                val vector=FloatArray(256)
                vector[0]=1f
                matryoshkaL2(vector,256)
            }
        ),"T")
        override fun cancel(requestUid:String)=Unit
        override fun close()=Unit
    }

    private class HistoricProjectionIndex(
        val currentProjection:SemanticSourceProjectionState,
        val staleCandidate:SemanticCandidate
    ):SemanticIndexPort{
        override val version=SemanticIndexVersion()

        override fun upsertBatch(documents:List<SemanticIndexedDocument>){}
        override fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String){}
        override fun authorizedRecordUids(
            campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long
        )=setOf(staleCandidate.canonicalRecordUid)

        override fun searchAuthorized(request:SemanticSearchRequest):List<SemanticCandidate>{
            assertEquals(VisibilityAuthorityService.PROJECTION_VERSION_UID,request.projectionVersionUid)
            assertEquals("HUMAN_PLAYER",request.principalUid)
            assertEquals("GLOBAL",request.holderSetFingerprint)
            return listOf(staleCandidate)
        }

        override fun currentProjections(request:SemanticSearchRequest):Map<String,SemanticSourceProjectionState>{
            return mapOf(staleCandidate.canonicalRecordUid to currentProjection)
        }
        override fun checkpoint(campaignUid:String)=0L
        override fun advanceCheckpoint(campaignUid:String,committedOrder:Long)=Unit
        override fun status(campaignUid:String)=
            SemanticIndexStatus(true,1,1,0,version)
        override fun clear(campaignUid:String)=Unit
        override fun close()=Unit
    }

    private class SemanticScopedIndex(
        private val currentProjection:SemanticSourceProjectionState,
        private val staleCandidate:SemanticCandidate,
        private val expectedScope:(SemanticSearchRequest)->Unit
    ):SemanticIndexPort{
        override val version=SemanticIndexVersion()
        var requestedScope:SemanticSearchRequest?=null

        override fun upsertBatch(documents:List<SemanticIndexedDocument>){}
        override fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String){}
        override fun authorizedRecordUids(
            campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long
        )=setOf(staleCandidate.canonicalRecordUid)

        override fun searchAuthorized(request:SemanticSearchRequest):List<SemanticCandidate>{
            requestedScope=request
            expectedScope(request)
            return listOf(staleCandidate)
        }

        override fun currentProjections(request:SemanticSearchRequest):Map<String,SemanticSourceProjectionState>{
            requestedScope=request
            expectedScope(request)
            return mapOf(staleCandidate.canonicalRecordUid to currentProjection)
        }
        override fun checkpoint(campaignUid:String)=0L
        override fun advanceCheckpoint(campaignUid:String,committedOrder:Long)=Unit
        override fun status(campaignUid:String)=
            SemanticIndexStatus(true,1,1,0,version)
        override fun clear(campaignUid:String)=Unit
        override fun close()=Unit
    }
}
