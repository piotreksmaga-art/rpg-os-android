package com.rpgos.app

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Manual/emulator and real-device acceptance suite. The 200k timing is recorded on every device,
 * but only a physical Galaxy S24 result may be used to accept the published performance limits.
 */
@RunWith(AndroidJUnit4::class)
class BekkoDeviceAcceptanceTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val modelFile=File(context.filesDir,"semantic-models/$BEKKO_MODEL_UID/$BEKKO_MODEL_FILE")

    @Test fun pinnedQ8GoldenPoolingBatchMatryoshkaAndCpuVulkanAgreement(){
        assertTrue("Install the pinned Bekko model before this test",modelFile.isFile)
        assertEquals(BEKKO_MODEL_BYTES,modelFile.length())
        val texts=listOf(
            "Smagi uratował kupca Takeshiego podczas zasadzki bandytów.",
            "Takeshi przeżył napad dzięki interwencji Smagiego.",
            "Nad ranem w porcie zaczął padać deszcz."
        )
        val cpu=LlamaCppBekkoEmbeddingProvider(context,modelFile,EmbeddingBackend.CPU)
        val cpuOpen=cpu.open();assertEquals(cpuOpen.reasonUid,EmbeddingAvailabilityState.READY,cpuOpen.state)
        val bounded=cpu.embedBatch(EmbeddingRequest("GOLDEN-TOKEN-LIMIT",listOf(texts.first()),2))
        assertTrue(bounded is EmbeddingBatchResult.Failure)
        assertTrue((bounded as EmbeddingBatchResult.Failure).reasonUid.contains("CONTEXT_OVERFLOW"))
        val started=SystemClock.elapsedRealtimeNanos()
        val batch=(cpu.embedBatch(EmbeddingRequest("GOLDEN-CPU-BATCH",texts)) as EmbeddingBatchResult.Success).vectors
        val elapsedMs=(SystemClock.elapsedRealtimeNanos()-started)/1_000_000.0
        texts.forEachIndexed{index,text->
            val single=(cpu.embedBatch(EmbeddingRequest("GOLDEN-CPU-SINGLE-$index",listOf(text))) as EmbeddingBatchResult.Success).vectors.single()
            assertTrue("batch/single mismatch at $index",cosine(batch[index],single)>=0.999999f)
            assertTrue(kotlin.math.abs(norm(single)-1.0)<1e-5)
            assertEquals(256,matryoshkaL2(single,256).size)
        }
        assertTrue("Polish paraphrase golden ordering failed",cosine(batch[0],batch[1])>cosine(batch[0],batch[2]))
        cpu.close()

        val vulkan=LlamaCppBekkoEmbeddingProvider(context,modelFile,EmbeddingBackend.VULKAN)
        val vulkanOpen=vulkan.open();assertEquals(vulkanOpen.reasonUid,EmbeddingAvailabilityState.READY,vulkanOpen.state)
        val gpu=(vulkan.embedBatch(EmbeddingRequest("GOLDEN-VULKAN",texts)) as EmbeddingBatchResult.Success).vectors
        batch.indices.forEach{assertTrue("CPU/Vulkan cosine below contract",cosine(batch[it],gpu[it])>=0.999f)}
        vulkan.close()
        Log.i("RPGOS-BEKKO","golden batchMs=$elapsedMs cpuVulkanMin=${batch.indices.minOf{cosine(batch[it],gpu[it]).toDouble()}}")
    }

    @Test fun embeddingDecodeCanBeCancelled(){
        assertTrue("Install the pinned Bekko model before this test",modelFile.isFile)
        val provider=LlamaCppBekkoEmbeddingProvider(context,modelFile,EmbeddingBackend.CPU)
        assertEquals(EmbeddingAvailabilityState.READY,provider.open().state)
        val executor=Executors.newSingleThreadExecutor()
        try{
            val request=EmbeddingRequest("GOLDEN-CANCEL",listOf("pamięć semantyczna ".repeat(96)),512)
            val result=executor.submit<EmbeddingBatchResult>{provider.embedBatch(request)}
            SystemClock.sleep(10);provider.cancel(request.requestUid)
            val cancelled=result.get(10,TimeUnit.SECONDS)
            assertTrue(cancelled is EmbeddingBatchResult.Failure)
            assertTrue((cancelled as EmbeddingBatchResult.Failure).reasonUid.contains("CANCELLED"))
        }finally{executor.shutdownNow();provider.close()}
    }

    @Test fun exactAuthorizedScanHandlesTwoHundredThousandRecordsWithoutOom(){
        val root=File(context.cacheDir,"bekko-200k-${System.nanoTime()}")
        try{
            FileSemanticIndex(root).use{index->
                val total=200_000
                (0 until total step 10_000).forEach{start->
                    index.upsertBatch((start until (start+10_000).coerceAtMost(total)).map{ordinal->
                        val vector=FloatArray(256).also{it[ordinal%256]=1f}
                        SemanticIndexedDocument(
                            SemanticDocumentProjection(
                                "BENCH",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                                VisibilityPurposeKinds.INTERNAL_SIMULATION,"REC-%06d".format(ordinal),"EVENT","FACT",
                                ordinal.toLong(),1,"FP-$ordinal",0,"rekord testowy $ordinal"
                            ),vector
                        )
                    })
                }
                val authorized=index.authorizedRecordUids(
                    "BENCH",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                    VisibilityPurposeKinds.INTERNAL_SIMULATION,Long.MAX_VALUE
                )
                assertEquals(total,authorized.size)
                val request=SemanticSearchRequest(
                    "BENCH",SEMANTIC_NAMESPACE_CAMPAIGN,AudienceKinds.GM_RUNTIME,
                    VisibilityPurposeKinds.INTERNAL_SIMULATION,Long.MAX_VALUE,authorized,
                    queryVector=FloatArray(256).also{it[17]=1f},topK=10,minimumScore=-1f
                )
                index.searchAuthorized(request) // warm metadata and mmap
                // x86 emulators do not have the ARM FP16/NEON path used by the target phone.
                // Keep one correctness/OOM sample there; physical ARM64 acceptance records p95 from 7 runs.
                val sampleCount=if(android.os.Build.SUPPORTED_ABIS.any{it.startsWith("x86")})1 else 7
                val timings=LongArray(sampleCount){
                    val start=SystemClock.elapsedRealtimeNanos();index.searchAuthorized(request)
                    (SystemClock.elapsedRealtimeNanos()-start)/1_000_000
                }.sorted()
                val p95=timings[((timings.size-1)*0.95).toInt()]
                val memory=Debug.MemoryInfo().also{Debug.getMemoryInfo(it)}
                assertEquals(10,index.searchAuthorized(request).size)
                Log.i("RPGOS-BEKKO","scan200k p95Ms=$p95 samples=$sampleCount totalPssKb=${memory.totalPss} device=${android.os.Build.MODEL}")
            }
        }finally{root.deleteRecursively()}
    }

    @Test fun polishAndMultilingualRetrievalGoldSetBeatsLexicalBaseline(){
        assertTrue("Install the pinned Bekko model before this test",modelFile.isFile)
        val relevant=listOf(
            "Takeshi przeżył zasadzkę dzięki interwencji Smagiego.",
            "Mira ukryła srebrny klucz pod spróchniałym dębem.",
            "Alchemik skaził wiejską studnię trującym wywarem.",
            "Kowal naprawił pękniętą klingę przed wyprawą.",
            "The village council signed a peace treaty with the northern clan.",
            "Łucznik przyrzekł, że wróci do twierdzy przed zimą.",
            "A healer cured the child after a dangerous fever.",
            "Strażnicy znaleźli tajne przejście za biblioteką."
        )
        val queries=listOf(
            "Który handlarz zawdzięcza bohaterowi życie po napadzie?",
            "Gdzie schowano metalowy przedmiot otwierający zamek?",
            "Dlaczego mieszkańcy nie mogą bezpiecznie pić wody?",
            "Kto przywrócił sprawność uszkodzonemu mieczowi?",
            "Która osada zawarła rozejm z rodem z północy?",
            "Who promised to come back to the fortress before winter?",
            "Kto uratował chore dziecko przed skutkami gorączki?",
            "Where did the guards discover a concealed passage?"
        )
        val distractors=(0 until 24).map{index->
            listOf(
                "Na rynku zmieniono cenę zboża o świcie.",
                "Posłaniec policzył konie w południowej stajni.",
                "Rybacy wrócili do portu przed burzą.",
                "Kartograf narysował nową mapę pustynnego szlaku."
            )[index%4]+" Zapis poboczny numer $index."
        }
        val documents=distractors+relevant
        val provider=LlamaCppBekkoEmbeddingProvider(context,modelFile,EmbeddingBackend.CPU)
        assertEquals(EmbeddingAvailabilityState.READY,provider.open().state)
        try{
            val documentVectors=(provider.embedBatch(EmbeddingRequest("GOLD-DOCUMENTS",documents)) as EmbeddingBatchResult.Success).vectors
            val queryVectors=(provider.embedBatch(EmbeddingRequest("GOLD-QUERIES",queries)) as EmbeddingBatchResult.Success).vectors
            var semanticHits=0
            var reciprocalRank=0.0
            var lexicalHits=0
            queries.indices.forEach{queryIndex->
                val expected=distractors.size+queryIndex
                val semanticRanking=documents.indices.sortedWith(
                    compareByDescending<Int>{cosine(queryVectors[queryIndex],documentVectors[it])}.thenBy{it}
                ).take(10)
                val semanticRank=semanticRanking.indexOf(expected)
                if(semanticRank>=0){semanticHits++;reciprocalRank+=1.0/(semanticRank+1)}
                val queryTerms=terms(queries[queryIndex])
                val lexicalRanking=documents.indices.sortedWith(
                    compareByDescending<Int>{candidate->terms(documents[candidate]).count{it in queryTerms}}.thenBy{it}
                ).take(10)
                if(expected in lexicalRanking)lexicalHits++
            }
            val recall=semanticHits.toDouble()/queries.size
            val mrr=reciprocalRank/queries.size
            val lexicalRecall=lexicalHits.toDouble()/queries.size
            Log.i("RPGOS-BEKKO","gold recall10=$recall mrr10=$mrr lexicalRecall10=$lexicalRecall")
            assertTrue("Recall@10 below contract: $recall",recall>=0.85)
            assertTrue("MRR@10 below contract: $mrr",mrr>=0.75)
            assertTrue("Semantic improvement below 10 percentage points",recall-lexicalRecall>=0.10)
        }finally{provider.close()}
    }

    private fun norm(value:FloatArray)=sqrt(value.sumOf{it.toDouble()*it})
    private fun cosine(left:FloatArray,right:FloatArray):Float{
        require(left.size==right.size)
        var dot=0.0;var leftSquared=0.0;var rightSquared=0.0
        for(index in left.indices){
            dot+=left[index]*right[index];leftSquared+=left[index]*left[index];rightSquared+=right[index]*right[index]
        }
        return (dot/sqrt(leftSquared*rightSquared)).toFloat()
    }
    private fun terms(value:String)=value.lowercase(java.util.Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter{it.length>=3}.toSet()
}
