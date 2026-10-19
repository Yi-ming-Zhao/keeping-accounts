package dev.localledger
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.agent.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.json.JSONArray
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveConversationUiTest {
 @Test fun actualChatControllerUsesRealModelAndRestoresHistory()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val app=instrumentation.targetContext.applicationContext as LedgerApplication
  val file=File(app.filesDir,"probe-config.json");Assume.assumeTrue(file.exists())
  val vaultPrefs=app.getSharedPreferences("vault",0);val original=vaultPrefs.getString("model_config",null)
  try {
   val evidence=app.repository.ingest("manual","share","live-ui-known",System.currentTimeMillis(),dev.localledger.capture.ReceiptParser.parse("支付成功\n金额：6.38\n商户：对话验证文具店\n订单号：CHATKNOWN638")!!)
   if(evidence!=null){app.database.book().saveJob(dev.localledger.data.LedgerJob(evidence,evidence,"complete"));app.repository.correct(app.database.book().forEvidence(evidence).single().id,"教育",null)}
   val (start,end)=QueryPeriod.resolve(org.json.JSONObject().put("period","this_month"))
   val expected=app.database.book().total(start,end)
   assertTrue(expected>=638)
   app.settings.saveModel(ModelConfig.fromJson(file.readText()))
   val session="chat:ui-${UUID.randomUUID()}"
   app.settings.preferences.edit().putString("chat_session",session).commit()
   app.conversation.send(session,"这个月花了多少钱？请查询账本，用 Markdown 表格列出金额和统计口径。")
   withTimeout(125000){while(app.conversation.state.value.busy)delay(200)}
   assertNull(app.conversation.state.value.error)
   app.conversation.send(session,"刚才的金额是扣除退款后的吗？再找出本月最近的一笔账。")
   withTimeout(125000){while(app.conversation.state.value.busy)delay(200)}
   assertNull(app.conversation.state.value.error)
   val run=app.database.dao().run(session)!!
   assertEquals("complete",run.state)
   val messages=JSONArray(run.messages)
   assertEquals(2,(0 until messages.length()).count{messages.getJSONObject(it).optString("role")=="user"})
   assertTrue(run.messages.contains("summarize"));assertTrue(run.messages.contains("query_entries"))
   assertTrue(run.messages.contains(dev.localledger.ledger.Money.display(expected)))
   File(app.cacheDir,"live-ui-transcript.json").writeText(run.messages)
   instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
   fun find(text:String,node:AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow):AccessibilityNodeInfo? {
    if(node==null)return null
    if(node.text?.toString()?.contains(text)==true)return node
    for(i in 0 until node.childCount)find(text,node.getChild(i))?.let{return it}
    return null
   }
   withTimeout(10000){while(find("对话")==null)delay(100)}
   var tab=find("对话")!!;while(!tab.isClickable)tab=tab.parent
   assertTrue(tab.performAction(AccessibilityNodeInfo.ACTION_CLICK))
   val last=(messages.length()-1 downTo 0).map{messages.getJSONObject(it)}.first{it.optString("role")=="assistant"}
   val answer=last.getJSONArray("content").let{parts->(0 until parts.length()).map{parts.getJSONObject(it)}.filter{it.optString("type")=="text"}.joinToString("\n"){it.getString("text")}}
   val prefix=withContext(Dispatchers.Main){io.noties.markwon.Markwon.create(app).toMarkdown(answer).toString().trim().take(8)}
   withTimeout(10000){while(find(prefix)==null)delay(200)}
   assertNotNull(find("历史"));assertNotNull(find("新对话"))
  } finally {
   if(original==null)vaultPrefs.edit().remove("model_config").commit() else vaultPrefs.edit().putString("model_config",original).commit()
  }
 }
}
