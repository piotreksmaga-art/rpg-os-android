package com.rpgos.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

data class BekkoSettings(
    val enabled:Boolean=true,
    val backend:EmbeddingBackend=EmbeddingBackend.CPU
)

class BekkoSettingsStore(context:Context){
    private val prefs=context.getSharedPreferences("rpgos_bekko_settings",Context.MODE_PRIVATE)
    fun load()=BekkoSettings(
        enabled=prefs.getBoolean("enabled",true),
        backend=runCatching{EmbeddingBackend.valueOf(prefs.getString("backend",EmbeddingBackend.CPU.name)!!)}.getOrDefault(EmbeddingBackend.CPU)
    )
    fun save(settings:BekkoSettings){prefs.edit().putBoolean("enabled",settings.enabled).putString("backend",settings.backend.name).apply()}
}

data class BekkoDownloadProgress(val downloadedBytes:Long,val totalBytes:Long){
    val fraction:Float get()=if(totalBytes<=0)0f else (downloadedBytes.toDouble()/totalBytes).toFloat().coerceIn(0f,1f)
}

class BekkoModelManager(context:Context){
    private val root=File(context.applicationContext.filesDir,"semantic-models/$BEKKO_MODEL_UID").apply{mkdirs()}
    private val target=File(root,BEKKO_MODEL_FILE)
    private val partial=File(root,"$BEKKO_MODEL_FILE.partial")
    private val digestMarker=File(root,"$BEKKO_MODEL_FILE.sha256")
    private val client=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(5,TimeUnit.MINUTES).build()

    fun modelFile():File=target
    fun installed():Boolean{
        if(!target.isFile||target.length()!=BEKKO_MODEL_BYTES)return false
        if(digestMarker.isFile&&digestMarker.readText().trim().equals(BEKKO_MODEL_SHA256,true))return true
        val digest=sha256(target)
        if(digest.equals(BEKKO_MODEL_SHA256,true)){digestMarker.writeText(BEKKO_MODEL_SHA256);return true}
        return false
    }

    suspend fun download(onProgress:(BekkoDownloadProgress)->Unit={}):File=withContext(Dispatchers.IO){
        if(installed())return@withContext target
        var lastFailure:Throwable?=null
        for(url in listOf(BEKKO_RELEASE_URL,BEKKO_UPSTREAM_URL)){
            try{
                downloadFrom(url,onProgress)
                if(partial.length()!=BEKKO_MODEL_BYTES)error("BEKKO_DOWNLOAD_SIZE_MISMATCH:${partial.length()}")
                val digest=sha256(partial)
                if(!digest.equals(BEKKO_MODEL_SHA256,true))error("BEKKO_DOWNLOAD_SHA256_MISMATCH")
                atomicInstall()
                digestMarker.writeText(BEKKO_MODEL_SHA256)
                return@withContext target
            }catch(failure:Throwable){
                if(failure is CancellationException)throw failure
                if(failure.message?.contains("SHA256_MISMATCH")==true||partial.length()>=BEKKO_MODEL_BYTES)partial.delete()
                lastFailure=failure
            }
        }
        throw IllegalStateException(lastFailure?.message?:"BEKKO_DOWNLOAD_FAILED",lastFailure)
    }

    fun remove():Boolean{
        val removed=listOf(target,partial,digestMarker).all{!it.exists()||it.delete()}
        return removed
    }

    private fun downloadFrom(url:String,onProgress:(BekkoDownloadProgress)->Unit){
        val existing=partial.length().coerceAtMost(BEKKO_MODEL_BYTES)
        val request=Request.Builder().url(url).apply{if(existing>0)header("Range","bytes=$existing-")}.build()
        client.newCall(request).execute().use{response->
            if(!response.isSuccessful)error("BEKKO_DOWNLOAD_HTTP_${response.code}")
            val append=existing>0&&response.code==206
            if(!append&&partial.exists())partial.delete()
            val start=if(append)existing else 0L
            val body=response.body
            FileOutputStream(partial,append).use{output->body.byteStream().use{input->
                val buffer=ByteArray(1024*1024);var total=start;var lastReported=-1L
                while(true){
                    val count=input.read(buffer);if(count<0)break
                    output.write(buffer,0,count);total+=count
                    require(total<=BEKKO_MODEL_BYTES){"BEKKO_DOWNLOAD_TOO_LARGE"}
                    if(total-lastReported>=4L*1024*1024||total==BEKKO_MODEL_BYTES){onProgress(BekkoDownloadProgress(total,BEKKO_MODEL_BYTES));lastReported=total}
                }
                output.fd.sync()
            }}
        }
    }

    private fun atomicInstall(){
        try{
            Files.move(partial.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        }catch(_:AtomicMoveNotSupportedException){
            Files.move(partial.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file:File):String{
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{input->val buffer=ByteArray(1024*1024);while(true){val count=input.read(buffer);if(count<0)break;digest.update(buffer,0,count)}}
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
}
