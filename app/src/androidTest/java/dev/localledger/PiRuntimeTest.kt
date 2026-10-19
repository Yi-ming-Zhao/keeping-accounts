package dev.localledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.agent.*
import dev.localledger.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PiRuntimeTest {
    private val context=ApplicationProvider.getApplicationContext<LedgerApplication>()
    private lateinit var db: LedgerDatabase
    private lateinit var name: String
    @Before fun setup() { name="test-${UUID.randomUUID()}.db"; db=LedgerDatabase.open(context,name) }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun textResponse() = """{"choices":[{"message":{"content":"已完成"},"finish_reason":"stop"}]}"""
    private fun callResponse(id: String) = JSONObject().put("choices",org.json.JSONArray().put(JSONObject().put("message",JSONObject().put("tool_calls",org.json.JSONArray().put(JSONObject().put("id","call_1").put("type","function").put("function",JSONObject().put("name","record_probe").put("arguments",JSONObject().put("id",id).put("amountMinor",1234).put("label","验证").toString()))))).put("finish_reason","tool_calls"))).toString()
    @Test fun piExecutesAndRestoresEncryptedTranscript() = runBlocking {
        var calls=0
        PiRuntime(context,db).run("session","验证","fixture",{if(calls++==0) callResponse("sample") else textResponse()})
        assertEquals(1234L,db.dao().probe("sample")?.amountMinor)
        assertTrue(db.dao().run("session")!!.messages.contains("toolResult"))
        db.close(); db=LedgerDatabase.open(context,name)
        assertEquals(1,db.dao().probeCount("sample"))
        PiRuntime(context,db).run("session","", "fixture",{error("已完成会话不应重新请求")},resume=true)
        assertEquals(1,db.dao().probeCount("sample"))
        val prefix=File(context.getDatabasePath(name).path).inputStream().use { it.readNBytes(16) }.toString(Charsets.US_ASCII)
        assertFalse(prefix.startsWith("SQLite format 3"))
    }
    @Test fun timeoutCancelsHostAndPreservesRecoverableRun() = runBlocking {
        try { PiRuntime(context,db).run("timeout","验证","fixture",{delay(60_000);textResponse()},timeoutMs=3000); fail("Expected timeout") }
        catch(_: TimeoutCancellationException) { }
        assertEquals("interrupted",db.dao().run("timeout")?.state)
        PiRuntime(context,db).run("timeout","","fixture",{textResponse()},resume=true)
        assertEquals("complete",db.dao().run("timeout")?.state)
    }
    @Test fun explicitCancellationReleasesEngine() = runBlocking {
        val entered=CompletableDeferred<Unit>()
        val job=launch { PiRuntime(context,db).run("cancel","验证","fixture",{entered.complete(Unit);awaitCancellation()}) }
        entered.await(); job.cancelAndJoin()
        assertEquals("interrupted",db.dao().run("cancel")?.state)
        PiRuntime(context,db).run("after","验证","fixture",{textResponse()})
        assertEquals("complete",db.dao().run("after")?.state)
    }
    @Test fun interruptedToolCommitDoesNotDuplicateWrite() = runBlocking {
        db.dao().saveProbe(ProbeRecord("crash",1234,"验证"))
        db.dao().saveReceipt(ToolReceipt("crash","call_1","{\"saved\":true}"))
        val messages="""[{"role":"user","content":"保存验证","timestamp":1},{"role":"assistant","content":[{"type":"toolCall","id":"call_1","name":"record_probe","arguments":{"id":"crash","amountMinor":1234,"label":"验证"}}],"stopReason":"toolUse","timestamp":2}]"""
        db.dao().saveRun(AgentRun("crash",messages,"interrupted"))
        PiRuntime(context,db).run("crash","","fixture",{textResponse()},resume=true)
        assertEquals(1,db.dao().probeCount("crash"))
        assertTrue(db.dao().run("crash")!!.messages.contains("toolResult"))
    }
    @Test fun liveDeepSeekToolAndRecovery() = runBlocking {
        val file=File(context.filesDir,"probe-config.json")
        Assume.assumeTrue("未注入真实模型配置",file.exists())
        val config=ModelConfig.fromJson(file.readText()).checked()
        val client=ModelClient(config)
        val id="live_${UUID.randomUUID().toString().take(8)}"
        PiRuntime(context,db).run("live","调用 record_probe 保存 id=$id、amountMinor=1234、label=验证。成功后只回复已保存。",config.model,client::request)
        assertEquals(1234L,db.dao().probe(id)?.amountMinor)
        val saved=db.dao().run("live")!!.messages
        assertTrue(saved.contains("toolResult"))
        db.close();db=LedgerDatabase.open(context,name)
        PiRuntime(context,db).run("live","调用 read_probe 读取 id=$id 并报告保存的金额。",config.model,client::request)
        val transcript=db.dao().run("live")!!.messages
        assertTrue(transcript.contains("read_probe"));assertTrue(transcript.contains("1234"))
        assertEquals(1,db.dao().probeCount(id))
    }
}
