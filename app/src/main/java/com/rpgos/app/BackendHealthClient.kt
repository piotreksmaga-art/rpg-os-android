package com.rpgos.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BackendHealthClient(private val baseUrl:String){
    private val client=OkHttpClient.Builder()
        .connectTimeout(5,TimeUnit.SECONDS)
        .readTimeout(30,TimeUnit.SECONDS)
        .build()

    suspend fun check():String=withContext(Dispatchers.IO){
        val req=Request.Builder().url(baseUrl.trimEnd('/')+"/health").get().build()
        client.newCall(req).execute().use{resp->
            if(!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
            val j=JSONObject(resp.body.string())
            "Backend OK • model=${j.optString("text_model")} • key=${j.optBoolean("openai_key_configured")} • mock=${j.optBoolean("mock_mode")}"
        }
    }

    suspend fun checkOpenAI():String=withContext(Dispatchers.IO){
        val req=Request.Builder().url(baseUrl.trimEnd('/')+"/v1/openai/check").get().build()
        client.newCall(req).execute().use{resp->
            if(!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
            JSONObject(resp.body.string()).toString(2)
        }
    }
}
