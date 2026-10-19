package dev.localledger
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localledger.capture.ReceiptParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import io.noties.markwon.Markwon

@RunWith(AndroidJUnit4::class)
class UiFlowTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val app=instrumentation.targetContext.applicationContext as LedgerApplication
 private fun find(text:String,node:AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow):AccessibilityNodeInfo? {
  if(node==null)return null
  if(node.text?.toString()==text)return node
  for(i in 0 until node.childCount)find(text,node.getChild(i))?.let{return it}
  return null
 }
 private suspend fun awaitText(text:String) {
  repeat(25){if(find(text)!=null)return;delay(160)}
  error("界面未显示：$text")
 }
 private fun click(text:String) {
  var node=find(text)?:error("UI 缺少 $text")
  while(!node.isClickable){node=node.parent?:error("不可点击 $text")}
  check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
 }
 @Test fun ledgerFiltersDetailsNavigationAndMarkdown()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  val marker="界面测试文具店"
  val ev=app.repository.ingest("manual","share","ui-v02",System.currentTimeMillis(),ReceiptParser.parse("支付成功\n实付款：6.38\n商品名称：中性笔\n商户：$marker\n订单号：UIORDER638")!!)
  if(ev!=null){val entry=app.database.book().forEvidence(ev).single();app.repository.correct(entry.id,"教育",null)}
  assertTrue(app.database.book().filtered(0,Long.MAX_VALUE,"文具",null,100).first().any{it.merchant==marker})
  instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
  awaitText("青账");awaitText(marker)
  click(marker);awaitText("账目详情");awaitText("中性笔")
  click("编辑");awaitText("保存修改");click("关闭");delay(400)
  click("分析");awaitText("收支分析");awaitText("每日支出")
  click("对话");awaitText("新对话");awaitText("历史")
  val markdown=withContext(Dispatchers.Main){Markwon.create(app).toMarkdown("**金额**：6.38 元\n\n- 中性笔\n\n`638` 分")}
  assertFalse(markdown.toString().contains("**"));assertTrue(markdown.getSpans(0,markdown.length,android.text.style.MetricAffectingSpan::class.java).isNotEmpty())
  click("账户");delay(300);assertNotNull(find("账户"))
 }
}
