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
        if(request.shape.kind!=WorldReferenceShapeKind.NAMED_INSTANCE&&request.shape.topologyClassUid=="SETTLEMENT_FACILITY")return emptyList()
        val query=listOfNotNull(request.phrase,request.worldContextHint?.take(160)).joinToString(" ")
        val url=endpoint.toHttpUrl().newBuilder()
            .addQueryParameter("action","query").addQueryParameter("list","search").addQueryParameter("format","json")
            .addQueryParameter("utf8","1").addQueryParameter("srlimit",request.maximumCandidates.toString()).addQueryParameter("srsearch",query).build()
        val response=client.newCall(Request.Builder().url(url).header("User-Agent","RPG-OS-Android/1.0 semantic-world-evidence").build()).execute()
        response.use{
            if(!it.isSuccessful)return emptyList()
            val root=JSONObject(it.body.string());val array=root.optJSONObject("query")?.optJSONArray("search")?:return emptyList()
            return buildList{
                for(index in 0 until array.length()){
                    val item=array.optJSONObject(index)?:continue
                    val title=item.optString("title").trim().takeIf(String::isNotBlank)?:continue
                    val pageId=item.optLong("pageid",-1L).takeIf{value->value>=0}?:continue
                    val snippet=item.optString("snippet").replace(Regex("<[^>]+>")," ").replace(Regex("\\s+")," ").trim()
                    add(WorldEvidenceCandidate(
                        evidenceUid="WIKIPEDIA:$pageId",displayName=title,classification=WorldEvidenceClassification.SOURCE_CANON,
                        confidence=if(normalizedWorldText(title)==normalizedWorldText(request.phrase))0.9 else 0.72,
                        sourceUri="https://pl.wikipedia.org/?curid=$pageId",sourceRevision=pageId.toString(),sourceHash=worldSha256("$title|$snippet"),
                        baseKind=request.shape.baseKind,categoryUid=request.shape.categoryUid,parentAnchorUid=null,
                        affordanceUids=request.shape.affordanceUids,topologyClassUid=request.shape.topologyClassUid
                    ))
                }
            }
        }
    }
}
