package com.rpgos.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Optional public-source scout. Its output is evidence only: topology, campaign truth and materialization
 * remain Core decisions. Network failure is intentionally equivalent to no evidence.
 */
class MediaWikiWorldEvidenceProvider(
    private val endpoint:String="https://pl.wikipedia.org/w/api.php",
    private val client:OkHttpClient=OkHttpClient.Builder()
        .connectTimeout(3,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS).callTimeout(5,TimeUnit.SECONDS).build()
):WorldEvidenceProviderPort{
    override fun candidates(request:WorldEvidenceRequest):List<WorldEvidenceCandidate>{
        return candidates(request,5_000L)
    }
    internal fun candidates(request:WorldEvidenceRequest,budgetMillis:Long):List<WorldEvidenceCandidate>{
        require(budgetMillis in 1..5_000)
        if(request.shape.kind!=WorldReferenceShapeKind.NAMED_INSTANCE&&request.shape.topologyClassUid=="SETTLEMENT_FACILITY")return emptyList()
        val query=listOfNotNull(request.phrase,request.worldContextHint?.take(160)).joinToString(" ")
        val url=endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("action","query").addQueryParameter("generator","search").addQueryParameter("format","json")
            .addQueryParameter("formatversion","2").addQueryParameter("utf8","1")
            .addQueryParameter("gsrlimit",minOf(request.maximumCandidates,5).toString()).addQueryParameter("gsrsearch",query)
            .addQueryParameter("prop","revisions|extracts").addQueryParameter("rvprop","ids|timestamp")
            .addQueryParameter("exintro","1").addQueryParameter("explaintext","1").addQueryParameter("exchars","512").build()
        val call=client.newCall(Request.Builder().url(url).header("User-Agent","RPG-OS-Android/1.0 semantic-world-evidence").build())
        call.timeout().timeout(budgetMillis,TimeUnit.MILLISECONDS)
        val response=call.execute()
        response.use{
            if(!it.isSuccessful)return emptyList()
            val body=it.body.source()
            body.request(1_048_577)
            if(body.buffer.size>1_048_576)return emptyList()
            val root=JSONObject(body.readUtf8());val array=root.optJSONObject("query")?.optJSONArray("pages")?:return emptyList()
            return buildList{
                for(index in 0 until array.length()){
                    val item=array.optJSONObject(index)?:continue
                    val title=item.optString("title").trim().takeIf(String::isNotBlank)?:continue
                    val pageId=item.optLong("pageid",-1L).takeIf{value->value>=0}?:continue
                    val revision=item.optJSONArray("revisions")?.optJSONObject(0)?.optLong("revid",-1L)?.takeIf{value->value>0}?:continue
                    val snippet=item.optString("extract").trim().take(512)
                    add(WorldEvidenceCandidate(
                        evidenceUid="WIKIPEDIA:$pageId:$revision",displayName=title,classification=WorldEvidenceClassification.UNKNOWN,
                        confidence=if(normalizedWorldText(title)==normalizedWorldText(request.phrase))0.9 else 0.72,
                        sourceUri="https://pl.wikipedia.org/?oldid=$revision",sourceRevision=revision.toString(),sourceHash=worldSha256("$title|$revision|$snippet"),
                        baseKind=request.shape.baseKind,categoryUid=request.shape.categoryUid,parentAnchorUid=null,
                        affordanceUids=request.shape.affordanceUids,topologyClassUid=request.shape.topologyClassUid
                    ))
                }
            }
        }
    }
}
