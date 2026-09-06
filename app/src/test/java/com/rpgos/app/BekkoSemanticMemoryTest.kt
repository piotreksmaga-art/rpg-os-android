package com.rpgos.app

import android.content.Context
import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test fun sidecarKeepsHistoricalRecordVersionsAndSelectsLatestAtRequestedOrder(){
        FileSemanticIndex(root).use{index->
            val old=SemanticDocumentProjection(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                "WORLD_ELEMENT:KONOHA","WORLD_ELEMENT","FACT",5,1,"FP-OLD",0,"stara nazwa"
            )
            val current=old.copy(asOfOrder=10,sourceVersion=2,sourceFingerprint="FP-NEW",text="nowa nazwa")
            index.replaceRecord(listOf(SemanticIndexedDocument(old,unitVector(0))))
            index.replaceRecord(listOf(SemanticIndexedDocument(current,unitVector(1))))

            fun search(at:Long,query:FloatArray)=index.searchAuthorized(SemanticSearchRequest(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                at,setOf(old.canonicalRecordUid),queryVector=query,topK=1,minimumScore=0.5f
            )).single()
            assertEquals(1L,search(5,unitVector(0)).sourceVersion)
            assertEquals("FP-OLD",search(5,unitVector(0)).sourceFingerprint)
            assertEquals(2L,search(10,unitVector(1)).sourceVersion)
            assertEquals("FP-NEW",search(10,unitVector(1)).sourceFingerprint)
        }
    }

    @Test fun projectionRetirementPreservesEarlierAsOfButHidesCurrentState(){
        FileSemanticIndex(root).use{index->
            val projection=SemanticDocumentProjection(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                "WORLD_ASSERTION:OLD","CAMPAIGN_TRUTH","BELIEF",5,1,"FP-OLD",0,"stare przekonanie"
            )
            index.replaceRecord(listOf(SemanticIndexedDocument(projection,unitVector(0))))
            index.retireProjection(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                VisibilityPurposeKinds.INTERNAL_SIMULATION,projection.canonicalRecordUid,10
            )
            assertEquals(setOf(projection.canonicalRecordUid),index.authorizedRecordUids(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,5
            ))
            assertTrue(index.authorizedRecordUids(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,10
            ).isEmpty())
        }
    }

    @Test fun staleWorkerCannotResurrectARevisionRetiredByNewerWorker(){
        FileSemanticIndex(root).use{index->
            val projection=SemanticDocumentProjection(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                "MEMORY:STALE","MEMORY:EPISODE_INTERPRETATION","NARRATIVE",5,1,"FP-STALE",0,"stara pamięć"
            )
            val document=SemanticIndexedDocument(projection,unitVector(0))
            index.replaceRecord(listOf(document))
            index.retireProjection(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                VisibilityPurposeKinds.INTERNAL_SIMULATION,projection.canonicalRecordUid,10
            )
            // Simulates a worker that fetched this old revision before the retirement committed.
            index.replaceRecord(listOf(document))
            assertTrue(index.authorizedRecordUids(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                VisibilityPurposeKinds.INTERNAL_SIMULATION,10
            ).isEmpty())
            assertEquals(setOf(projection.canonicalRecordUid),index.authorizedRecordUids(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                VisibilityPurposeKinds.INTERNAL_SIMULATION,5
            ))
        }
    }

    @Test fun reconciliationRetiresMissingDerivedRevisionAtTheSameCommitOrder(){
        FileSemanticIndex(root).use{index->
            val projection=SemanticDocumentProjection(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                "MEMORY:SUPERSEDED","MEMORY:SEMANTIC_ASSERTION","BELIEF",5,1,"FP-OLD",0,"stary wniosek"
            )
            index.replaceRecord(listOf(SemanticIndexedDocument(projection,unitVector(0))))
            val scope=SemanticProjectionScope(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION
            )
            val session=index.beginProjectionReconciliation(scope,"MEMORY:")
            assertTrue(index.finishProjectionReconciliation(scope,"MEMORY:",session,5))
            assertTrue(index.authorizedRecordUids(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                VisibilityPurposeKinds.INTERNAL_SIMULATION,5
            ).isEmpty())
        }
    }

    @Test fun checkpointScopeMismatchAtomicallyForcesRebuild(){
        FileSemanticIndex(root).use{index->
            assertTrue(index.bindCheckpointScope("C1","GEN-1|PLAYER-1"))
            index.upsertBatch(listOf(indexed("C1","PLAYER","GAMEPLAY_NARRATION","A",unitVector(0))))
            index.advanceCheckpoint("C1",7)
            assertFalse(index.bindCheckpointScope("C1","GEN-1|PLAYER-1"))
            assertEquals(7,index.checkpoint("C1"))
            assertTrue(index.bindCheckpointScope("C1","GEN-2|PLAYER-2"))
            assertEquals(0,index.checkpoint("C1"))
            assertEquals(0L,index.status("C1").recordCount)
        }
    }

    @Test fun wholeRecordReplacementIsIdempotentRemovesStaleChunksAndRecoversUncommittedTail(){
        val vectorFile=File(root,"semantic-vectors.fp16")
        val first=SemanticDocumentProjection(
            "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
            "MEMORY:R1","MEMORY:EPISODE_INTERPRETATION","NARRATIVE",5,1,"FP-1",0,"pierwszy fragment"
        )
        FileSemanticIndex(root).use{index->
            val twoChunks=listOf(
                SemanticIndexedDocument(first,unitVector(0)),
                SemanticIndexedDocument(first.copy(chunkOrdinal=1,text="drugi fragment"),unitVector(1))
            )
            index.replaceRecord(twoChunks)
            val exactLength=vectorFile.length()
            index.replaceRecord(twoChunks)
            assertEquals(exactLength,vectorFile.length())
            index.replaceRecord(listOf(SemanticIndexedDocument(
                first.copy(sourceFingerprint="FP-2",text="jeden aktualny fragment"),unitVector(2)
            )))
            val candidate=index.searchAuthorized(SemanticSearchRequest(
                "C1",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,VisibilityPurposeKinds.INTERNAL_SIMULATION,
                5,setOf(first.canonicalRecordUid),queryVector=unitVector(2),topK=1,minimumScore=0.5f
            )).single()
            assertEquals("FP-2",candidate.sourceFingerprint)
            assertEquals(listOf(0),candidate.chunkEvidence.map{it.chunkOrdinal})
        }
        val durableLength=vectorFile.length()
        vectorFile.appendBytes(ByteArray(37){7})
        assertEquals(durableLength+37,vectorFile.length())
        FileSemanticIndex(root).use{reopened->
            assertEquals(durableLength,vectorFile.length())
            assertEquals(1L,reopened.status("C1").chunkCount)
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

    @Test fun identicalRecordAndChunkRemainIsolatedAcrossPrincipalAndHistoryScopes(){
        fun scoped(principal:String,generation:String,text:String,vector:FloatArray)=SemanticIndexedDocument(
            SemanticDocumentProjection(
                campaignUid="C1",namespaceUid=SEMANTIC_NAMESPACE_CAMPAIGN,audienceUid="PLAYER",
                purposeUid="GAMEPLAY_NARRATION",canonicalRecordUid="SHARED",recordKindUid="EVENT",
                epistemicStateUid="BELIEF",asOfOrder=5,sourceVersion=1,
                sourceFingerprint="FP-$principal-$generation",chunkOrdinal=0,text=text,
                historyGenerationUid=HistoryGenerationUid(generation),principalUid=principal,
                holderSetFingerprint="HOLDER-$principal",accessPolicyVersion=1,activePlayerUid=principal
            ),vector
        )
        fun request(principal:String,generation:String,vector:FloatArray)=SemanticSearchRequest(
            campaignUid="C1",namespaceUid=SEMANTIC_NAMESPACE_CAMPAIGN,audienceUid="PLAYER",
            purposeUid="GAMEPLAY_NARRATION",asOfOrder=5,authorizedRecordUids=setOf("SHARED"),
            queryVector=vector,topK=1,minimumScore=-1f,
            historyGenerationUid=HistoryGenerationUid(generation),principalUid=principal,
            holderSetFingerprint="HOLDER-$principal",accessPolicyVersion=1,activePlayerUid=principal
        )

        FileSemanticIndex(root).use{index->
            index.upsertBatch(listOf(
                scoped("P1","HGEN-1","widok pierwszego gracza",unitVector(0)),
                scoped("P2","HGEN-2","widok drugiego gracza",unitVector(1))
            ))

            val first=index.searchAuthorized(request("P1","HGEN-1",unitVector(0))).single()
            val second=index.searchAuthorized(request("P2","HGEN-2",unitVector(1))).single()
            assertEquals("widok pierwszego gracza",first.chunkEvidence.single().projectedText)
            assertEquals("widok drugiego gracza",second.chunkEvidence.single().projectedText)
            assertTrue(first.score.value>0.99f)
            assertTrue(second.score.value>0.99f)

            val exactScope=request("P1","HGEN-1",unitVector(0)).copy(
                authorizedRecordUids=emptySet(),exactScopeAuthorized=true
            )
            val exact=index.searchAuthorized(exactScope).single()
            assertEquals("widok pierwszego gracza",exact.chunkEvidence.single().projectedText)
        }
    }

    @Test fun sidecarDefensivelyRejectsCorruptedTopKBeforeAllocatingRankingState(){
        val request=SemanticSearchRequest(
            "C1",SEMANTIC_NAMESPACE_CAMPAIGN,"PLAYER","GAMEPLAY_NARRATION",10,setOf("A"),
            queryVector=unitVector(0),topK=1,minimumScore=-1f
        )
        SemanticSearchRequest::class.java.getDeclaredField("topK").apply{
            isAccessible=true
            setInt(request,0)
        }
        assertEquals(0,request.topK)

        FileSemanticIndex(root).use{index->
            val failure=assertThrows(IllegalArgumentException::class.java){index.searchAuthorized(request)}
            assertEquals("SEMANTIC_TOP_K_OUT_OF_RANGE",failure.message)
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

    @Test fun defaultSemanticRankingDropsWeakNeighboursOutsideTheBestScoreBand(){
        fun candidate(uid:String,score:Float)=SemanticCandidate(
            uid,SemanticSimilarityScore(score),"WORLD_ELEMENT","FACT","FP-$uid",1,
            listOf(SemanticChunkEvidence(0,uid,"TEXT-$uid")),SemanticIndexVersion()
        )

        val kept=retainSemanticRelevanceBand(listOf(
            candidate("POLIGON",0.318f),candidate("CLONE",0.278f),candidate("SHOP",0.251f)
        ),0.25f)

        assertEquals(listOf("POLIGON"),kept.map{it.canonicalRecordUid})
    }

    @Test fun futurePhasePortsAreCandidateOnlyViewsOfTheSameReadOnlySearch(){
        val candidate=SemanticCandidate(
            "EVENT-1",SemanticSimilarityScore(0.9f),"EVENT","BELIEF","FP",1,
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

    @Test fun dynamicallyMaterializedWorldElementsBecomeOnePlayerSafeBekkoDocumentWithoutHiddenLeakage(){
        val public=CanonicalWorldElementSemanticState("C1","PUBLIC",mapOf(
            CampaignWorldFacts.KIND to "PLACE",CampaignWorldFacts.NAME to "warsztat garncarski",
            CampaignWorldFacts.CATEGORY to "CRAFTING_VENUE",CampaignWorldFacts.PARENT to "VILLAGE",
            CampaignWorldFacts.AFFORDANCE to "CRAFTING",
            CampaignWorldFacts.AUDIENCE_SCOPE to CampaignWorldAudience.PLAYER_VISIBLE
        ),1)
        val hidden=CanonicalWorldElementSemanticState("C1","HIDDEN",mapOf(
            CampaignWorldFacts.KIND to "ORGANIZATION",CampaignWorldFacts.NAME to "tajna rada"
        ),1)
        val projector=CanonicalWorldElementSemanticProjector()
        val playerAudience=VisibilityAudienceFactory.player("C1")
        val playerPurpose=PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        val player=listOf(public,hidden).flatMap{projector.project(it,playerAudience,playerPurpose)}
        assertEquals(listOf("WORLD_ELEMENT:PUBLIC"),player.map{it.canonicalRecordUid}.distinct())
        val playerText=player.sortedBy{it.chunkOrdinal}.joinToString(" "){it.text}
        assertTrue(playerText.contains("warsztat garncarski"))
        assertTrue(playerText.contains("CRAFTING"))
        assertFalse(playerText.contains("tajna rada"))

        val gmAudience=AudienceContext("C1",AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM"))
        val gmPurpose=PurposeContext("C1",VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val gm=listOf(public,hidden).flatMap{projector.project(it,gmAudience,gmPurpose)}
        assertEquals(setOf("WORLD_ELEMENT:PUBLIC","WORLD_ELEMENT:HIDDEN"),gm.map{it.canonicalRecordUid}.toSet())
    }

    @Test fun projectedWorldElementsPreserveBeliefAndNarrativeTruthKinds(){
        val states=listOf(
            CanonicalWorldEpistemicSemanticState("C1","S-B",TruthKind.BELIEF,"KOKRAN",CampaignWorldFacts.KIND,"BELIEF-PLACE",null,null,1),
            CanonicalWorldEpistemicSemanticState("C1","S-N",TruthKind.NARRATIVE,"KOKRAN",CampaignWorldFacts.NAME,"Cień",null,null,1)
        )
        val projector=CanonicalWorldEpistemicSemanticProjector()
        val audience=AudienceContext("C1",AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM"))
        val purpose=PurposeContext("C1",VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val gm=states.flatMap{projector.project(it,audience,purpose)}
        assertEquals(setOf("WORLD_ASSERTION:S-B","WORLD_ASSERTION:S-N"),gm.map{it.canonicalRecordUid}.toSet())
        assertEquals(setOf("BELIEF","NARRATIVE"),gm.map{it.epistemicStateUid}.toSet())
        assertTrue(gm.any{it.text.contains("BELIEF-PLACE")})
        assertTrue(gm.any{it.text.contains("Cień")})
    }

    @Test fun projectedWorldElementsNeverPromoteBeliefToFact(){
        val audience=AudienceContext("C1",AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM"))
        val purpose=PurposeContext("C1",VisibilityPurposeKinds.INTERNAL_SIMULATION)
        val belief=CanonicalWorldEpistemicSemanticProjector().project(
            CanonicalWorldEpistemicSemanticState("C1","S-B1",TruthKind.BELIEF,"NATSU",CampaignWorldFacts.KIND,"WIOSKA",null,null,1),audience,purpose
        ).single()
        val fact=CanonicalWorldElementSemanticProjector().project(
            CanonicalWorldElementSemanticState("C1","NATSU",mapOf(CampaignWorldFacts.NAME to "Dom"),1),audience,purpose
        ).single()
        assertEquals("BELIEF",belief.epistemicStateUid)
        assertEquals("FACT",fact.epistemicStateUid)
    }

    @Test fun phase38AccessAuthorityMetadataIsNeverAPlayerOrGmSemanticDocument(){
        val access=PlayerDomainChange.create(
            "ACCESS-CHANGE",PlayerChangeKinds.ACCESS_AUTHORITY,
            AccessAuthorityChange(
                AccessOperation.UPSERT_BINDING,"ACCESS-RECORD",AudienceKinds.PLAYER,"P1",
                AccessBindingKind.ROLE.name,"ROLE-SECRET",validFromOrder=1
            )
        )
        val replay=worldReplay(listOf(access))
        val projector=CommittedReplaySemanticProjector(activePlayerUid={"P1"})
        val player=projector.project(
            replay,VisibilityAudienceFactory.player("C1"),PurposeContext("C1",VisibilityPurposeKinds.GAMEPLAY_NARRATION)
        )
        val gm=projector.project(
            replay,AudienceContext("C1",AudienceKinds.GM_RUNTIME,VisibilityPrincipalRef(AudienceKinds.GM_RUNTIME,"LOCAL_GM")),
            PurposeContext("C1",VisibilityPurposeKinds.INTERNAL_SIMULATION)
        )
        assertTrue(player.isEmpty())
        assertTrue(gm.isEmpty())
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
        assertTrue(coordinator.readyForQueries())
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
        assertFalse(coordinator.readyForQueries())
        assertEquals(0,index.checkpoint(campaign))
        val recovered=coordinator.catchUp()
        assertTrue(recovered.ready)
        assertTrue(coordinator.readyForQueries())
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

    @Test fun backgroundIndexFailureReturnsTypedFallbackInsteadOfEscapingTheWorker(){
        val testContext=isolatedContext()
        cleanupCampaign(testContext)
        val repository=UnifiedGameRepository(testContext);repository.bootstrap()
        val campaign=repository.activeCampaignRef().campaignId
        val brokenIndex=object:SemanticIndexPort{
            override val version=SemanticIndexVersion()
            override fun upsertBatch(documents:List<SemanticIndexedDocument>)=error("BROKEN_INDEX")
            override fun remove(campaignUid:String,namespaceUid:String,canonicalRecordUid:String)=Unit
            override fun authorizedRecordUids(campaignUid:String,namespaceUid:String,audienceUid:String,purposeUid:String,asOfOrder:Long)=emptySet<String>()
            override fun searchAuthorized(request:SemanticSearchRequest)=emptyList<SemanticCandidate>()
            override fun checkpoint(campaignUid:String):Long=error("BROKEN_INDEX")
            override fun advanceCheckpoint(campaignUid:String,committedOrder:Long)=Unit
            override fun status(campaignUid:String):SemanticIndexStatus=error("BROKEN_INDEX")
            override fun clear(campaignUid:String)=Unit
            override fun close()=Unit
        }
        val progress=mutableListOf<SemanticIndexProgress>()
        val coordinator=ImmediateSemanticIndexCoordinator(
            repository,FakeEmbeddingProvider(),brokenIndex,
            campaignUid=campaign,onProgress=progress::add
        )

        val failed=coordinator.catchUp()

        assertFalse(failed.ready)
        assertEquals("BEKKO_INDEXING_FAILED:IllegalStateException",failed.reasonUid)
        assertEquals("FAILED",progress.last().stageUid)
        assertFalse(coordinator.readyForQueries())
        coordinator.close();repository.closeBackgroundWorkForTest();cleanupCampaign(testContext)
    }

    @Test fun coordinatorLeaseCannotFollowCampaignAcrossHistoryGenerationChange(){
        val testContext=isolatedContext()
        cleanupCampaign(testContext)
        val repository=UnifiedGameRepository(testContext);repository.bootstrap()
        val campaign=repository.activeCampaignRef().campaignId
        val index=FileSemanticIndex(File(root,"generation-lease"))
        val coordinator=ImmediateSemanticIndexCoordinator(repository,FakeEmbeddingProvider(),index)

        LocalGameStore(testContext).openGameplaySaveDb().use{db->
            db.beginTransaction()
            GameplayMutationDatabaseGuards.enterAdmin(db,campaign)
            try{
                HistoryGenerationStore(db,campaign).advance("TEST_HISTORY_REPLACEMENT")
                db.setTransactionSuccessful()
            }finally{
                GameplayMutationDatabaseGuards.leaveAdmin(db,campaign)
                db.endTransaction()
            }
        }

        val rejected=coordinator.catchUp()
        assertFalse(rejected.ready)
        assertEquals("BEKKO_INDEXING_FAILED:IllegalStateException",rejected.reasonUid)
        assertEquals(0L,index.checkpoint(campaign))

        coordinator.close();repository.closeBackgroundWorkForTest();cleanupCampaign(testContext)
    }

    @Test fun synchronousCatchUpHoldsSemanticLeaseUntilAllStorageWorkStops(){
        val testContext=isolatedContext()
        cleanupCampaign(testContext)
        val repository=UnifiedGameRepository(testContext);repository.bootstrap()
        val delegate=FakeEmbeddingProvider()
        val embeddingEntered=CountDownLatch(1)
        val releaseEmbedding=CountDownLatch(1)
        val provider=object:EmbeddingProviderPort by delegate{
            override fun embedBatch(request:EmbeddingRequest):EmbeddingBatchResult{
                embeddingEntered.countDown()
                check(releaseEmbedding.await(5,TimeUnit.SECONDS)){"TEST_EMBEDDING_RELEASE_TIMEOUT"}
                // Finish this synthetic catch-up immediately after the lease assertion. The
                // registry test concerns storage exclusion, not indexing the entire World Pack.
                return EmbeddingBatchResult.Failure("TEST_EMBEDDING_RELEASED",false)
            }
        }
        val index=FileSemanticIndex(File(root,"runtime-lease"))
        val coordinator=ImmediateSemanticIndexCoordinator(repository,provider,index)
        val worker=Thread({coordinator.catchUp()},"test-caller-run-catch-up").apply{start()}
        assertTrue(embeddingEntered.await(5,TimeUnit.SECONDS))

        val transitionAttempted=CountDownLatch(1)
        val transitionEntered=CountDownLatch(1)
        val transition=Thread({
            transitionAttempted.countDown()
            SemanticCampaignTransitionRegistry.withCampaignStorageTransition{transitionEntered.countDown()}
        },"test-storage-transition").apply{start()}
        assertTrue(transitionAttempted.await(5,TimeUnit.SECONDS))
        assertFalse("Storage replacement must wait for caller-run catch-up",transitionEntered.await(150,TimeUnit.MILLISECONDS))

        releaseEmbedding.countDown()
        assertTrue(transitionEntered.await(5,TimeUnit.SECONDS))
        worker.join(5_000);transition.join(5_000)
        assertFalse(worker.isAlive);assertFalse(transition.isAlive)

        coordinator.close();repository.closeBackgroundWorkForTest();cleanupCampaign(testContext)
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

    @Test fun historyReplacementDeletesOnlyTheCampaignSemanticSidecar(){
        val testContext=isolatedContext()
        val campaignA="C-HISTORY-A"
        val campaignB="C-HISTORY-B"
        val first=SemanticSidecarStorage.campaignDirectory(testContext,campaignA).apply{mkdirs()}
        val second=SemanticSidecarStorage.campaignDirectory(testContext,campaignB).apply{mkdirs()}
        File(first,"semantic-vectors.fp16").writeBytes(ByteArray(32){1})
        File(second,"semantic-vectors.fp16").writeBytes(ByteArray(16){2})

        SemanticSidecarStorage.invalidateCampaign(testContext,campaignA)

        assertFalse(first.exists())
        assertTrue(second.isDirectory)
        assertEquals(16L,File(second,"semantic-vectors.fp16").length())
        second.deleteRecursively()
    }

    @Test fun staleCampaignIndexAfterDetachmentIsNeverReopenedAsActiveEvenIfCleanupFails(){
        val testContext=isolatedContext()
        val campaign="C-SIDECAR-STALE-DETACH"
        val campaignDir=SemanticSidecarStorage.campaignDirectory(testContext,campaign)
        campaignDir.mkdirs()
        FileSemanticIndex(campaignDir).use{index->
            index.upsertBatch(listOf(
                indexed(campaign,"PLAYER","GAMEPLAY_NARRATION","ACTIVE-STALE",unitVector(0),epistemic="BELIEF")
            ))
            index.advanceCheckpoint(campaign,12)
        }
        assertEquals(1L,FileSemanticIndex(campaignDir).use{it.status(campaign).recordCount})
        assertTrue(campaignDir.exists())

        runCatching { SemanticSidecarStorage.invalidateCampaign(testContext,campaign) }.onFailure{
            assertTrue(it.message.orEmpty().startsWith("BEKKO_INDEX_TOMBSTONE_DELETE_FAILED") || it.message.orEmpty().startsWith("BEKKO_INDEX_INVALIDATION_FAILED"))
        }

        assertFalse(campaignDir.exists())
        assertEquals(0L,FileSemanticIndex(campaignDir).use{recovered->
            recovered.status(campaign).recordCount
        })
    }

    private fun indexed(
        campaign:String,audience:String,purpose:String,uid:String,vector:FloatArray,epistemic:String="FACT"
    )=SemanticIndexedDocument(
        SemanticDocumentProjection(campaign,SEMANTIC_NAMESPACE_CAMPAIGN,audience,purpose,uid,"EVENT",epistemic,5,1,"FP-$campaign-$uid",0,"tekst $uid"),
        vector
    )

    private fun unitVector(index:Int)=FloatArray(256).also{it[index]=1f}

    private fun worldTruth(
        changeUid:String,subjectUid:String,predicate:String,value:String,kind:TruthKind=TruthKind.FACT
    )=PlayerDomainChange.create(
        changeUid,PlayerChangeKinds.CAMPAIGN_TRUTH,
        CampaignTruthChange(
            "TRUTH-$changeUid",kind,subjectUid,predicate,value,
            if(kind==TruthKind.BELIEF)"TEST-PERSPECTIVE" else null,
            if(kind==TruthKind.NARRATIVE)value else null,
            null
        )
    )

    private fun worldReplay(changes:List<PlayerDomainChange>):CommittedReplayPayload{
        val changeSet=PlayerChangeSet.create(
            changeSetUid="CS-WORLD",campaignUid="C1",sourceCommandUid="CMD-WORLD",
            actor=CommandActorRef("PLAYER","P1"),changes=changes,
            provenance=ChangeSetProvenance("CMD-WORLD","TEST","1")
        )
        return CommittedReplayPayload(
            TurnTransactionIdentity("C1","TURN-WORLD","CMD-WORLD","TX-WORLD"),1,"SEM-WORLD",
            RequiredEventManifestSummary(0,"EMPTY"),null,1,changeSet,emptyList(),null,"PAYLOAD"
        )
    }

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
