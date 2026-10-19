package dev.localledger.agent

import android.content.Context
import androidx.room.withTransaction
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.asyncFunction
import dev.localledger.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

class PiRuntime(private val context: Context, private val database: LedgerDatabase) {
    suspend fun run(sessionId: String, prompt: String, model: String, request: suspend (String)->String,
        resume: Boolean = false, extra: JSONObject? = null, toolHandler: (suspend (String, JSONObject, String)->String)? = null,
        timeoutMs: Long = 120_000): JSONObject = withTimeout(timeoutMs) {
        val dao=database.dao()
        val previous=dao.run(sessionId)
        val engine=QuickJs.create(Dispatchers.Default)
        try {
            engine.function("hostRandom") { args: Array<Any?> ->
                val n=(args[0] as Number).toInt(); require(n in 1..1024)
                JSONArray(ByteArray(n).also { SecureRandom().nextBytes(it) }.map { it.toInt() and 255 }).toString()
            }
            engine.function("hostNow") { _: Array<Any?> -> android.os.SystemClock.elapsedRealtime().toDouble() }
            engine.function("hostUuid") { _: Array<Any?> -> UUID.randomUUID().toString() }
            engine.asyncFunction("hostRequest") { args: Array<Any?> -> request(args[0] as String) }
            engine.asyncFunction("hostSave") { args: Array<Any?> ->
                dao.saveRun(AgentRun(sessionId,args[0] as String,"running")); "ok"
            }
            engine.asyncFunction("hostTool") { args: Array<Any?> ->
                val name=args[0] as String; val params=JSONObject(args[1] as String); val callId=args[2] as String
                if(toolHandler!=null) toolHandler(name,params,callId) else probeTool(sessionId,name,params,callId)
            }
            engine.evaluate<Any?>(context.assets.open("agent.js").bufferedReader().use { it.readText() })
            val input=extra ?: JSONObject()
            input.put("model",model).put("prompt",prompt).put("resume",resume)
            if(previous!=null) input.put("messages",JSONArray(previous.messages))
            engine.evaluate<Any?>("globalThis.__ledgerResult = await LedgerAgent.run(${input});", asModule=true)
            val result=engine.evaluate<String>("globalThis.__ledgerResult")
            val saved=dao.run(sessionId) ?: error("会话未持久化")
            dao.saveRun(saved.copy(state="complete",updatedAt=System.currentTimeMillis()))
            JSONObject(result)
        } catch(e: CancellationException) {
            withContext(NonCancellable) { dao.run(sessionId)?.let { dao.saveRun(it.copy(state="interrupted")) } }
            throw e
        } catch(e: Exception) {
            dao.run(sessionId)?.let { dao.saveRun(it.copy(state="failed")) }
            // JS and provider exceptions can include model output; do not log or expose raw messages.
            throw IllegalStateException("Agent 执行失败，请检查接口或重试", e)
        } finally { engine.close() }
    }
    private suspend fun probeTool(session: String, name: String, args: JSONObject, call: String): String = database.withTransaction {
        val dao=database.dao()
        dao.receipt(session,call)?.let { return@withTransaction it.result }
        val id=args.getString("id"); require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")))
        val result=when(name) {
            "record_probe" -> {
                val amount=args.getLong("amountMinor"); require(amount in 0..100_000_000)
                val label=args.getString("label"); require(label.length in 1..100)
                val existing=dao.probe(id)
                require(existing==null || (existing.amountMinor==amount && existing.label==label))
                dao.saveProbe(ProbeRecord(id,amount,label))
                JSONObject().put("id",id).put("saved",true).toString()
            }
            "read_probe" -> {
                val value=dao.probe(id) ?: error("记录不存在")
                JSONObject().put("id",value.id).put("amountMinor",value.amountMinor).put("label",value.label).toString()
            }
            else -> error("未授权工具")
        }
        dao.saveReceipt(ToolReceipt(session,call,result)); result
    }
}
