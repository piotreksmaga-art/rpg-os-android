package com.rpgos.app

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Phase62NpcRecallTest {
    @get:Rule val temporary=TemporaryFolder()
    private val scope=NpcDecisionScope(TemporalScope("C1","G1",5,"digest"),DomainRef("NPC","N1"),1,WorldTimeTick(0),0,"P1")
    private fun request(actor:String="N1",text:String="Most")=NpcRecallRequest(scope.copy(actor=DomainRef("NPC",actor)),
        KnowledgeHolderRef(KnowledgeHolderKinds.CHARACTER,actor,"C1"),text,listOf(
            NpcKnownRecord("A:$actor",KnowledgeEpistemicState.BELIEVED,"Most","ACQ:A:$actor",1),
            NpcKnownRecord("B:$actor",KnowledgeEpistemicState.KNOWN,"Las","ACQ:B:$actor",1)))
    private class Embeddings:EmbeddingProviderPort {
        val seen=mutableListOf<String>();var fail=false;var after:()->Unit={}
        override val capabilities=EmbeddingCapabilities("BEKKO",BEKKO_MODEL_UID,BEKKO_SOURCE_REVISION,384,setOf(256,384),8192,32,setOf(EmbeddingBackend.CPU))
        override fun availability()=EmbeddingAvailability(EmbeddingAvailabilityState.READY,"READY")
        override fun open()=availability()
        override fun embedBatch(request:EmbeddingRequest):EmbeddingBatchResult {
            seen+=request.texts;after()
            return if(fail)EmbeddingBatchResult.Failure("TEST_SERVICE_DIED",true) else EmbeddingBatchResult.Success(
                request.texts.map{text->FloatArray(384).also{it[if(text=="Most")0 else 1]=1f}},request.requestUid)
        }
        override fun cancel(requestUid:String)=Unit
        override fun close()=Unit
    }
    @Test fun cacheWarmupIsSeparateAndRankingReturnsOnlyAuthorizedOwnerUids() {
        FileSemanticIndex(temporary.newFolder()).use { index ->
            val provider=Embeddings();val recall=BekkoNpcRecall(provider,index,{scope.temporal})
            val own=request();val other=request("OTHER")
            assertEquals(NpcRecallResult.Fallback("P62:RECALL_INDEX_NOT_READY"),recall.rank(own))
            assertTrue(provider.seen.isEmpty())
            assertNull(recall.prepare(other));assertNull(recall.prepare(own))
            provider.seen.clear()
            val hits=(recall.rank(own) as NpcRecallResult.Ranked).hits
            assertEquals(listOf("A:N1","B:N1"),hits.map{it.uid})
            assertEquals(listOf("Most"),provider.seen)
            assertTrue(hits.all{hit->own.records.any{it.uid==hit.uid && npcRecallFingerprint(it)==hit.sourceFingerprint}})
            assertTrue(hits.first().score.value>0.99f)
            assertEquals(KnowledgeEpistemicState.BELIEVED,own.records.first().epistemicState)
        }
    }
    @Test fun changedSourceAndUndoCannotReuseOldCache() {
        FileSemanticIndex(temporary.newFolder()).use { index ->
            var current=scope.temporal
            val provider=Embeddings();val recall=BekkoNpcRecall(provider,index,{current});val own=request()
            assertNull(recall.prepare(own))
            val changed=own.copy(records=own.records.map{it.copy(sourceVersion=2,projectedText="Zmiana")})
            assertEquals(NpcRecallResult.Fallback("P62:RECALL_INDEX_NOT_READY"),recall.rank(changed))
            provider.after={current=current.copy(historyGenerationUid="G2")}
            assertEquals(NpcRecallResult.Fallback("P62:STALE_RECALL"),recall.rank(own))
        }
    }
    @Test fun failureLeavesStructuredFallbackAndNoCanonicalWrites() {
        FileSemanticIndex(temporary.newFolder()).use { index ->
            val provider=Embeddings();val recall=BekkoNpcRecall(provider,index,{scope.temporal})
            provider.fail=true
            assertEquals("P62:RECALL_EMBEDDING_FAILED",recall.prepare(request()))
            assertEquals(NpcRecallResult.Fallback("P62:RECALL_INDEX_NOT_READY"),recall.rank(request()))
            assertEquals(0,index.checkpoint("C1"))
        }
    }
}
