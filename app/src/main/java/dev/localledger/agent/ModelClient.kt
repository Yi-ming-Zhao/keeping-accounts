package dev.localledger.agent

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ModelConfig(val baseUrl: String, val model: String, val apiKey: String, val images: Boolean = false) {
    fun checked(): ModelConfig {
        val url = baseUrl.trimEnd('/').toHttpUrl()
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "接口需使用 HTTPS" }
        require(model.isNotBlank() && apiKey.isNotBlank()) { "请填写模型和密钥" }
        return copy(baseUrl = url.toString().trimEnd('/'))
    }
    companion object {
        fun fromJson(s: String): ModelConfig { val j=JSONObject(s); return ModelConfig(j.getString("baseUrl"),j.getString("model"),j.getString("apiKey"),j.optBoolean("images")) }
    }
}
class ModelClient(private val config: ModelConfig) {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    suspend fun request(body: String): String {
        config.checked()
        val payload=JSONObject(body)
        if (config.baseUrl.toHttpUrl().host == "api.deepseek.com") payload.put("thinking", JSONObject().put("type", "disabled"))
        val endpoint=config.baseUrl.trimEnd('/').let { if(it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        for (attempt in 0..2) {
            try {
                return call(Request.Builder().url(endpoint).header("Authorization", "Bearer ${config.apiKey}")
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build())
            } catch(e: ModelHttpException) {
                if(attempt==2 || (e.status!=429 && e.status<500)) throw e
            } catch(e: IOException) { if(attempt==2) throw IOException("模型网络连接失败") }
            delay(1000L shl attempt)
        }
        error("模型请求失败")
    }
    private suspend fun call(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call=client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) { if(continuation.isActive) continuation.resumeWithException(IOException("模型网络连接失败")) }
            override fun onResponse(call: Call, response: Response) {
                try { response.use {
                    if(!it.isSuccessful) throw ModelHttpException(it.code)
                    val source=it.body?.source() ?: error("模型响应为空")
                    source.request(1_048_577)
                    require(source.buffer.size <= 1_048_576) { "模型响应过大" }
                    val text=source.readUtf8()
                    if(continuation.isActive) continuation.resume(text)
                }} catch(e: Exception) { if(continuation.isActive) continuation.resumeWithException(e) }
            }
        })
    }
}
class ModelHttpException(val status: Int): IOException("模型接口返回 HTTP $status")
