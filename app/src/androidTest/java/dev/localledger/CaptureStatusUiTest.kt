package dev.localledger

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureStatusUiTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val app=instrumentation.targetContext.applicationContext as LedgerApplication
 private fun find(text:String,node:AccessibilityNodeInfo?=instrumentation.uiAutomation.rootInActiveWindow):AccessibilityNodeInfo? {
  if(node==null)return null
  if(node.text?.toString()==text)return node
  for(i in 0 until node.childCount)find(text,node.getChild(i))?.let{return it}
  return null
 }
 private suspend fun awaitText(text:String) {
  repeat(18) {
   val node=find(text)
   if(node?.isVisibleToUser==true){delay(250);return}
   if(node!=null)node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
   else if(it>2) {
    val bounds=android.graphics.Rect();instrumentation.uiAutomation.rootInActiveWindow?.getBoundsInScreen(bounds)
    if(!bounds.isEmpty) {
     val x=bounds.centerX();val start=bounds.top+bounds.height()*7/10;val end=start-bounds.height()/7
     instrumentation.uiAutomation.executeShellCommand("input swipe $x $start $x $end 250").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
    }
   }
   delay(450)
  }
  fun visible(node:AccessibilityNodeInfo?):List<String> {
   if(node==null)return emptyList()
   return listOfNotNull(node.text?.toString()?.takeIf{node.isVisibleToUser})+(0 until node.childCount).flatMap{visible(node.getChild(it))}
  }
  error("界面未显示：$text; 当前标签：${visible(instrumentation.uiAutomation.rootInActiveWindow).take(40)}")
 }
 private suspend fun click(text:String) {
  var node=find(text)?:error("UI 缺少 $text")
  node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
  delay(500)
  node=find(text)?:error("UI 缺少 $text")
  val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds)
  check(!bounds.isEmpty)
  val time=android.os.SystemClock.uptimeMillis()
  for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
   val event=android.view.MotionEvent.obtain(time,time+if(action==android.view.MotionEvent.ACTION_UP)50 else 0,action,bounds.exactCenterX(),bounds.exactCenterY(),0)
   try{check(instrumentation.uiAutomation.injectInputEvent(event,true))}finally{event.recycle()}
  }
 }
 @Test fun pendingEvidenceAndMissingBankPermissionAreVisible()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  val id="ui-pending-${System.currentTimeMillis()}"
  app.database.book().insertEvidence(dev.localledger.data.Evidence(id,id,"dev.localledger.fixtures","notification",System.currentTimeMillis(),"本次购物款将在收货后收取28.80元"))
  app.database.book().saveJob(dev.localledger.data.LedgerJob(id,id,state="needs_review",error="交易状态待确认"))
  app.settings.allow("cmb.pb",false)
  app.captureDiagnostics.record("cmb.pb","notification",dev.localledger.capture.CaptureResult.NOT_AUTHORIZED)
  instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
  awaitText("招商银行通知未授权 · 去开启")
  fun reviewText(node:AccessibilityNodeInfo?):String? {
   if(node==null)return null
   node.text?.toString()?.takeIf{it.startsWith("查看待识别与核对 · ")}?.let{return it}
   for(i in 0 until node.childCount)reviewText(node.getChild(i))?.let{return it}
   return null
  }
  val label=reviewText(instrumentation.uiAutomation.rootInActiveWindow)?:error("待识别入口不可见")
  click(label)
  awaitText("交易样本 · 交易状态待确认");click("交易样本 · 交易状态待确认")
  awaitText("核对交易");awaitText("先用后付 · 待扣款")
  assertTrue(app.database.book().forEvidence(id).isEmpty())
  click("取消");delay(400)
 }
 @Test fun disabledServicesAndPrivacySafeDiagnosisAreVisible()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  // Verify the real settings screen. UiAutomation suppresses accessibility;
  // revoke notification access to ensure it really is disconnected too.
  instrumentation.uiAutomation.executeShellCommand("cmd notification disallow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
  delay(500)
  app.settings.allow("dev.localledger.fixtures",true)
  app.captureDiagnostics.record("dev.localledger.fixtures","page",dev.localledger.capture.CaptureResult.NO_FIELDS)
  instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
  awaitText("设置");click("设置")
  awaitText("通知读取");awaitText("页面读取")
  // The status must never claim an authorized-but-disconnected service is running.
  assertNull(find("运行中"))
  awaitText("查看采集状态");click("查看采集状态");awaitText("交易样本");awaitText("复制状态")
  click("复制状态");awaitText("已复制状态")
  val clipboard=withContext(Dispatchers.Main){app.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()}
  assertTrue(clipboard.contains("未识别到交易字段"))
  assertFalse(clipboard.contains("实付款"));assertFalse(clipboard.contains("apiKey"))
 }
}
