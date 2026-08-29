package com.rpgos.app

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class BekkoSemanticMemoryTest {
    private lateinit var root:File
    private val context:Context get()=RuntimeEnvironment.getApplication()

    @Before fun setUp(){root=File.createTempFile("bekko-sidecar-","").apply{delete();mkdirs()}}
    @After fun tearDown(){root.deleteRecursively()}

    @Test fun matryoshka384To256RenormalizesAndIsDeterministic(){
        val source=FloatArray(384){index->((index%17)-8).toFloat()/17f}
        val first=matryoshkaL2(source,256)
        val second=matryoshkaL2(source.copyOf(),256)
        assertEquals(first.toList(),second.toList())
        assertEquals(256,first.size)
        val norm=sqrt(first.sumOf{it.toDouble()*it})
        assertTrue(abs(norm-1.0)<1e-6)
    }

    @Test fun exactScanUsesOnlyPreAuthorizedCampaignAudienceAndPurposeAndBreaksTiesByUid(){
        FileSemanticIndex(root).use{index->
            val common=unitVector(0)
            index.upsertBatch(listOf(
                indexed("C1","PLAYER","GAMEPLAY_NARRATION","B",common),
                indexed("C1","PLAYER","GAMEPLAY_NARRATION","A",common),
                indexed("C1","GM_RUNTIME","INTERNAL_SIMULATION","HIDDEN",common,epistemic="BELIEF"),
                indexed("C2","PLAYER","GAMEPLAY_NARRATION","OTHER_CAMPAIGN",common)
            ))
            val authorized=index.authorizedRecordUids("C1",SEMANTIC_NAMESPACE_CAMPAIGN,"PLAYER","GAMEPLAY_NARRATION",10)
            assertEquals(setOf("A","B"),authorized)
            val result=index.searchAuthorized(SemanticSearchRequest(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,"PLAYER","GAMEPLAY_NARRATION",10,
                authorized+"HIDDEN"+"OTHER_CAMPAIGN",queryVector=common,topK=10,minimumScore=-1f
            ))
            assertEquals(listOf("A","B"),result.map{it.canonicalRecordUid})
            assertFalse(result.any{it.canonicalRecordUid=="HIDDEN"||it.canonicalRecordUid=="OTHER_CAMPAIGN"})
        }
    }

    @Test fun exactScanMergesChunksToCanonicalUidAndKeepsBestEvidence(){
        FileSemanticIndex(root).use{index->
            val first=indexed("C1","PLAYER","GAMEPLAY_NARRATION","EVENT",unitVector(0))
            val second=first.copy(
                projection=first.projection.copy(chunkOrdinal=1,text="drugi fragment",sourceFingerprint="FP-C1-EVENT-2"),
                vector=unitVector(1)
            )
            index.upsertBatch(listOf(first,second,indexed("C1","PLAYER","GAMEPLAY_NARRATION","OTHER",unitVector(2))))
            val authorized=index.authorizedRecordUids("C1",SEMANTIC_NAMESPACE_CAMPAIGN,"PLAYER","GAMEPLAY_NARRATION",10)
            val result=index.searchAuthorized(SemanticSearchRequest(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,"PLAYER","GAMEPLAY_NARRATION",10,authorized,
                queryVector=unitVector(1),topK=1,minimumScore=-1f
            ))
            assertEquals(listOf("EVENT"),result.map{it.canonicalRecordUid})
            assertEquals(listOf(1,0),result.single().chunkEvidence.map{it.chunkOrdinal})
            assertEquals("drugi fragment",result.single().chunkEvidence.first().projectedText)
        }
    }

    @Test fun fp16SidecarReopensIdempotentlyAndVersionMismatchRebuildsInsteadOfMixingVectors(){
        val document=indexed("C1","PLAYER","GAMEPLAY_NARRATION","A",unitVector(1))
        FileSemanticIndex(root).use{index->index.upsertBatch(listOf(document));index.advanceCheckpoint("C1",7)}
        FileSemanticIndex(root).use{index->
            assertEquals(1,index.status("C1").recordCount)
            index.upsertBatch(listOf(document));index.advanceCheckpoint("C1",7)
            assertEquals(1,index.status("C1").recordCount)
            assertEquals(7,index.checkpoint("C1"))
        }
        FileSemanticIndex(root,SemanticIndexVersion(projectorVersion=SemanticIndexVersion().projectorVersion+1)).use{changed->
            assertEquals(0,changed.status("C1").recordCount)
            assertEquals(0,changed.checkpoint("C1"))
        }
    }

    @Test fun semanticProviderReturnsCandidateEvidenceOnlyAndFailsClosedToTypedFallback(){
        FileSemanticIndex(root).use{index->
            index.upsertBatch(listOf(indexed("C1","PLAYER","GAMEPLAY_NARRATION","EVENT-1",unitVector(0),epistemic="BELIEF")))
            val request=StructuredRetrievalRequest(
                "REQ","C1",BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
                mapOf("query_text" to "stary dług wdzięczności","minimum_score" to "-1"),10,
                VisibilityAudienceFactory.player("C1"),PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION),atOrder=10
            )
            val unavailable=SemanticStructuredQueryProvider(FakeEmbeddingProvider(ready=false),index).retrieve(request)
            assertTrue(unavailable is StructuredRetrievalResult.Unsupported)
            val available=SemanticStructuredQueryProvider(FakeEmbeddingProvider(),index).retrieve(request)
                as StructuredRetrievalResult.Value
            assertEquals("EVENT-1",available.records.single().recordUid)
            assertEquals(true,available.records.single().values["candidate_only"])
            assertEquals("BELIEF",available.records.single().values["epistemic_state_uid"])
            assertFalse(available.records.single().values.containsKey("canonical_truth"))
            assertFalse(available.records.single().values.containsKey("causes"))
        }
    }

    @Test fun futurePhasePortsAreCandidateOnlyViewsOfTheSameReadOnlySearch(){
        val candidate=SemanticCandidate(
            "EVENT-1",0.9f,"EVENT","BELIEF","FP",1,
            listOf(SemanticChunkEvidence(0,"wspomnienie","TEXT-FP")),SemanticIndexVersion()
        )
        var calls=0
        val ports=SemanticFutureCandidatePorts.candidateOnly{calls++;listOf(candidate)}
        val request=SemanticSearchRequest(
            "C1",SEMANTIC_NAMESPACE_CAMPAIGN,"PLAYER","GAMEPLAY_NARRATION",1,setOf("EVENT-1"),
            queryVector=unitVector(0)
        )
        val results=listOf(
            ports.memoryConsolidation.candidates(request),ports.npcMemory.memories(request),
            ports.livingWorld.related(request),ports.promises.matches(request),
            ports.antiRepetition.similarNarratives(request),ports.aliases.aliases(request),
            ports.contradictions.possibleContradictions(request),ports.causalRelations.possibleRelations(request)
        )
        assertEquals(8,calls)
        assertTrue(results.all{it.single()==candidate&&it.single().epistemicStateUid=="BELIEF"})
    }

    @Test fun indexFilesAreRebuildableCacheAndNeverChangeCanonicalSaveBytes(){
        val canonical=File(root,"campaign.db").apply{writeBytes(ByteArray(1024){(it%251).toByte()})}
        val before=canonical.readBytes().contentHashCode()
        FileSemanticIndex(File(root,"semantic-sidecar")).use{index->
            index.upsertBatch(listOf(indexed("C1","PLAYER","GAMEPLAY_NARRATION","A",unitVector(3))))
            index.clear("C1")
        }
        assertEquals(before,canonical.readBytes().contentHashCode())
        val contract=RuntimeTruthLayerRegistry.requireFamily("SEMANTIC_SIDECAR_CACHE")
        assertTrue(RuntimeTruthLayer.CACHE in contract.layers)
        assertFalse(contract.isAuthoritative)
    }

    @Test fun rollbackRetryHundredTurnsAndReopenCatchUpRemainIdempotent(){
        val testContext=isolatedContext()
        cleanupCampaign(testContext)
        val repository=UnifiedGameRepository(testContext);repository.bootstrap()
        val campaign=repository.activeCampaignRef().campaignId
        LocalGameStore(testContext).openGameplaySaveDb().use{db->GroupATransactionTestFixtures.setupFinance(db,campaign)}
        val provider=FakeEmbeddingProvider()
        val index=FileSemanticIndex(File(root,"catchup"))
        val coordinator=ImmediateSemanticIndexCoordinator(repository,provider,index)
        val rollbackCommand="CMD-BEKKO-ROLLBACK"
        val rollbackIdentity=TurnTransactionIdentity(campaign,"TURN-BEKKO-ROLLBACK",rollbackCommand,"TX-BEKKO-ROLLBACK")
        val rollbackProposal=GroupATransactionTestFixtures.admittedFinancialProposal(campaign,rollbackCommand,amountMinor=1)
        LocalGameStore(testContext).openGameplaySaveDb().use{db->
            val failed=runCatching{TurnTransactionBoundary.create(
                db,rollbackIdentity,rollbackProposal,
                TurnFailureInjector{point->if(point==TurnFailurePoint.AFTER_FIRST_WRITE)error("EXPECTED_ROLLBACK")}
            ).commit()}
            assertTrue(failed.isFailure)
        }
        coordinator.catchUp()
        assertEquals(0L,index.checkpoint(campaign))
        assertTrue(index.authorizedRecordUids(
            campaign,SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
            VisibilityPurposeKinds.INTERNAL_SIMULATION,Long.MAX_VALUE
        ).isEmpty())

        val command="CMD-BEKKO"
        val identity=TurnTransactionIdentity(campaign,"TURN-BEKKO",command,"TX-BEKKO")
        val proposal=GroupATransactionTestFixtures.admittedFinancialProposal(campaignUid=campaign,commandUid=command,amountMinor=1)
        assertTrue(repository.commitTurn(identity,proposal) is TurnExecutionResult.Committed)
        assertTrue(repository.commitTurn(identity,proposal) is TurnExecutionResult.AlreadyCommitted)

        val projector=CommittedReplaySemanticProjector(activePlayerUid={null})
        val playerTail=SemanticHotTailProvider(repository,projector).retrieve(StructuredRetrievalRequest(
            "TAIL-PLAYER",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "Transfer"),20,VisibilityAudienceFactory.player(campaign),
            PurposeContext(campaign,VisibilityPurposeKinds.GAMEPLAY_NARRATION),atOrder=Long.MAX_VALUE
        ))
        assertTrue(playerTail is StructuredRetrievalResult.NoData)
        val gmTail=SemanticHotTailProvider(repository,projector).retrieve(StructuredRetrievalRequest(
            "TAIL-GM",campaign,BEKKO_STRUCTURED_PROVIDER_UID,BEKKO_OPERATION_MEMORY,
            mapOf("query_text" to "Transfer"),20,
            AudienceContext(campaign,AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM")),
            PurposeContext(campaign,VisibilityPurposeKinds.INTERNAL_SIMULATION),atOrder=Long.MAX_VALUE
        ))
        assertTrue(gmTail is StructuredRetrievalResult.Value)

        provider.failOnce()
        val failed=coordinator.catchUp()
        assertFalse(failed.ready)
        assertEquals(0,index.checkpoint(campaign))
        val recovered=coordinator.catchUp()
        assertTrue(recovered.ready)
        assertTrue(index.checkpoint(campaign)>0)
        assertTrue(index.authorizedRecordUids(
            campaign,SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.PLAYER,
            VisibilityPurposeKinds.GAMEPLAY_NARRATION,Long.MAX_VALUE
        ).isEmpty())
        assertTrue(index.authorizedRecordUids(
            campaign,SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
            VisibilityPurposeKinds.INTERNAL_SIMULATION,Long.MAX_VALUE
        ).isNotEmpty())
        val firstCount=recovered.chunkCount
        assertEquals(firstCount,coordinator.catchUp().chunkCount)
        coordinator.close()

        LocalGameStore(testContext).openGameplaySaveDb().use{db->(2..100).forEach{turn->
            val nextCommand="CMD-BEKKO-100-$turn"
            val result=TurnTransactionBoundary.create(
                db,TurnTransactionIdentity(campaign,"TURN-BEKKO-100-$turn",nextCommand,"TX-BEKKO-100-$turn"),
                GroupATransactionTestFixtures.admittedFinancialProposal(campaign,nextCommand,amountMinor=1)
            ).commit()
            assertTrue(result is TurnExecutionResult.Committed)
        }}
        val reopenedIndex=FileSemanticIndex(File(root,"catchup"))
        val reopenedCoordinator=ImmediateSemanticIndexCoordinator(repository,FakeEmbeddingProvider(),reopenedIndex)
        val finalStatus=reopenedCoordinator.catchUp()
        assertTrue(finalStatus.ready);assertEquals(100L,reopenedIndex.checkpoint(campaign))
        assertTrue(finalStatus.chunkCount>=firstCount+99)
        val finalCount=finalStatus.chunkCount
        assertEquals(finalCount,reopenedCoordinator.catchUp().chunkCount)
        reopenedCoordinator.close()

        FileSemanticIndex(File(root,"catchup")).use{finalReopen->
            assertEquals(100L,finalReopen.checkpoint(campaign))
            assertEquals(finalCount,finalReopen.status(campaign).chunkCount)
        }
        cleanupCampaign(testContext)
    }

    @Test fun pinnedModelManifestMatchesRuntimeContractAndKeepsModelOutsideApk(){
        val manifestFile=generateSequence(File(requireNotNull(System.getProperty("user.dir")))){it.parentFile}
            .map{File(it,"content/bekko-a8m-model-manifest.json")}.first{it.isFile}
        val manifest=JSONObject(manifestFile.readText())
        assertEquals(BEKKO_MODEL_BYTES,manifest.getLong("sizeBytes"))
        assertEquals(BEKKO_MODEL_SHA256,manifest.getString("sha256"))
        assertEquals(BEKKO_SOURCE_REVISION,manifest.getJSONObject("source").getString("revision"))
        assertEquals("MIT",manifest.getString("license"))
        assertFalse(manifest.getJSONObject("distribution").getBoolean("bundledInApk"))
        val embedding=manifest.getJSONObject("embedding")
        assertEquals(384,embedding.getInt("sourceDimensions"))
        assertEquals(256,embedding.getInt("storedDimensions"))
        assertEquals(SemanticIndexVersion().projectorVersion,manifest.getJSONObject("index").getInt("projectorVersion"))
    }

    private fun indexed(
        campaign:String,audience:String,purpose:String,uid:String,vector:FloatArray,epistemic:String="FACT"
    )=SemanticIndexedDocument(
        SemanticDocumentProjection(campaign,SEMANTIC_NAMESPACE_CAMPAIGN,audience,purpose,uid,"EVENT",epistemic,5,1,"FP-$campaign-$uid",0,"tekst $uid"),
        vector
    )

    private fun unitVector(index:Int)=FloatArray(256).also{it[index]=1f}

    private fun isolatedContext():Context=object:ContextWrapper(context){
        private val isolatedFiles=File(root,"app-files").apply{mkdirs()}
        override fun getApplicationContext():Context=this
        override fun getFilesDir():File=isolatedFiles
    }

    private fun cleanupCampaign(targetContext:Context=context){
        targetContext.getSharedPreferences("rpgos_selection",Context.MODE_PRIVATE).edit().clear().commit()
        val campaignRoot=File(targetContext.filesDir,"rpgos")
        repeat(4){
            if(!campaignRoot.exists()||campaignRoot.deleteRecursively())return
            System.gc();System.runFinalization();Thread.sleep(50)
        }
        check(!campaignRoot.exists()){"TEST_CAMPAIGN_CLEANUP_FAILED:${campaignRoot.absolutePath}"}
    }

    private class FakeEmbeddingProvider(
        private val ready:Boolean=true,
        private var failNext:Boolean=false
    ):EmbeddingProviderPort{
        override val capabilities=EmbeddingCapabilities(
            "RPGOS-LOCAL:BEKKO-EMBEDDING",BEKKO_MODEL_UID,BEKKO_SOURCE_REVISION,384,setOf(64,128,256,384),8192,32,
            setOf(EmbeddingBackend.CPU,EmbeddingBackend.VULKAN)
        )
        override fun availability()=EmbeddingAvailability(
            if(ready)EmbeddingAvailabilityState.READY else EmbeddingAvailabilityState.NOT_INSTALLED,
            if(ready)"READY" else "BEKKO_NOT_INSTALLED"
        )
        override fun open()=availability()
        override fun embedBatch(request:EmbeddingRequest):EmbeddingBatchResult{
            if(failNext){failNext=false;return EmbeddingBatchResult.Failure("BEKKO_TEST_PROCESS_DIED",true)}
            return EmbeddingBatchResult.Success(request.texts.map{text->
                FloatArray(384).also{vector->vector[(text.hashCode() and Int.MAX_VALUE)%256]=1f}
            },request.requestUid)
        }
        fun failOnce(){failNext=true}
        override fun cancel(requestUid:String)=Unit
        override fun close()=Unit
    }
}
