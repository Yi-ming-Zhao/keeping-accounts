package dev.localledger

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localledger.agent.*
import dev.localledger.capture.*
import dev.localledger.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveAgentCaptureTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LedgerApplication
 private val pkg="dev.localledger.fixtures"
 private fun shell(cmd:String){InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(cmd).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
 @Test fun unknownNotificationsReachRealPiAndModelWithoutOrderPage()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  val file=File(app.filesDir,"probe-config.json");check(file.exists())
  val config=ModelConfig.fromJson(file.readText()).checked()
  val oldScreenshots=app.settings.screenshots
  val old=app.getSharedPreferences("vault",0).getString("model_config",null)
  androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
  app.database.book().pendingJobs().forEach{app.database.book().saveJob(it.copy(state="complete"))}
  app.settings.allow(pkg,true)
  shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
  shell("cmd notification allow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  delay(2000)
  app.settings.saveModel(config)
  val token="LIVE${System.currentTimeMillis()}"
  val cases=listOf(
   Triple("银行卡动账\n这笔在京东的购物款已从卡中划走：人民币 6.38 元。商品原价 10.00 元，折扣 3.62 元。\n订单号：${token}BANK","expense",638L),
   Triple("订单服务\n拼多多的保温杯已替你保留，微信将在确认收货后自动向你收取 28.80 元，现在还没有扣钱。\n订单号：${token}CREDIT","deferred",2880L),
   Triple("好物推荐\n全新保温杯只要28.80元，欢迎来店选购，尚未下单。\n活动编号：${token}AD","not_transaction",0L)
  )
  try {
   for((index,c) in cases.withIndex()) {
    assertNull("Fixture must bypass fixed receipt parser",ReceiptParser.parseNotification(c.first))
    app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
     .putExtra("receipt","首页").putExtra("notification",true).putExtra("notificationTitle","交易样本")
     .putExtra("notificationId",91+index).putExtra("notificationReceipt",c.first))
    val evidence=withTimeout(15_000){var ev:Evidence?=null;while(ev==null){ev=app.database.book().evidenceSearch(0,Long.MAX_VALUE,100).firstOrNull{it.text.contains(token)&&it.text.contains(if(index==0)"BANK" else if(index==1)"CREDIT" else "AD")};if(ev==null)delay(200)};ev}
    assertEquals("notification",evidence.channel)
    val job=withTimeout(150_000){var j=app.database.book().job(evidence.id)!!;while(j.state in setOf("pending","processing")){delay(1000);j=app.database.book().job(evidence.id)!!};j}
    if(c.second=="not_transaction") {
     assertEquals("ignored",job.state);assertTrue(app.database.book().forEvidence(evidence.id).isEmpty())
    } else {
     val entry=app.database.book().forEvidence(evidence.id).single()
     assertEquals(c.second,entry.kind);assertEquals(c.third,entry.amountMinor)
     assertEquals("complete",job.state)
     if(c.second=="expense")assertNotNull(entry.category)
    }
    assertTrue(app.database.dao().run("capture:${evidence.id}")!!.messages.contains("recognize_transaction"))
   }
   // Real screenshot -> local OCR (including imperfect labels) -> persisted evidence -> Pi -> ledger.
   shell("settings put secure enabled_accessibility_services null")
   shell("settings put secure enabled_accessibility_services dev.localledger/dev.localledger.capture.PaymentAccessibilityService")
   shell("settings put secure accessibility_enabled 1")
   app.settings.screenshots=true
   delay(2000)
   val start=System.currentTimeMillis()
   app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    .putExtra("canvasReceipt",true).putExtra("receipt","订单详情\n支付成功\n实付款：6.38元\n商户：京东"))
   val imageEvidence=withTimeout(45_000){var found:Evidence?=null;while(found==null){found=app.database.book().evidenceSearch(start,Long.MAX_VALUE,50).firstOrNull{it.channel=="ocr"};if(found==null)delay(500)};found}
   val imageJob=withTimeout(150_000){var j=app.database.book().job(imageEvidence.id)!!;while(j.state in setOf("pending","processing")){delay(1000);j=app.database.book().job(imageEvidence.id)!!};j}
   assertEquals("complete",imageJob.state)
   assertEquals(638L,app.database.book().forEvidence(imageEvidence.id).single().amountMinor)
  } finally {
   app.settings.screenshots=oldScreenshots
   androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
   app.getSharedPreferences("vault",0).edit().apply{if(old==null)remove("model_config")else putString("model_config",old)}.commit()
  }
 }
}
