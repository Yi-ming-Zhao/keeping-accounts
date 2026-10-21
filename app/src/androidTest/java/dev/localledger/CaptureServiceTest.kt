package dev.localledger

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localledger.capture.ReceiptParser
import dev.localledger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureServiceTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LedgerApplication
 private val pkg="dev.localledger.fixtures"
 private val prefix="QA${System.currentTimeMillis()}"
 private fun shell(command:String) {InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }}
 private fun launch(text:String,notification:Boolean=false,id:Int=1,searchField:Boolean=false,combinedNode:Boolean=false,churn:Boolean=false,passwordField:Boolean=false,notificationReceipt:String?=null) {
  app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("notificationReceipt",notificationReceipt).putExtra("passwordField",passwordField).putExtra("searchField",searchField).putExtra("combinedNode",combinedNode).putExtra("churn",churn).putExtra("receipt",text).putExtra("notification",notification).putExtra("notificationId",id))
 }
 private suspend fun awaitEntries(order:String,expected:Int=1):List<LedgerEntry> {
  repeat(50) {
   val rows=app.database.book().ordersByExternalId(order).flatMap {app.database.book().forOrder(it.id)}
   if(rows.size==expected)return rows
   delay(200)
  }
  error("采集服务未生成预期记录：$order")
 }
 @Before fun connectServices()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  app.settings.allow(pkg,true)
  shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
  shell("cmd notification allow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  shell("settings put secure enabled_accessibility_services null")
  delay(300)
  shell("settings put secure enabled_accessibility_services dev.localledger/dev.localledger.capture.PaymentAccessibilityService")
  shell("settings put secure accessibility_enabled 1")
  delay(2000)
 }
 @Test fun inlineNotificationRecordsPaymentWithoutOrderPage()=runBlocking {
  val order="$prefix-INLINE"
  launch("首页",notification=true,id=31,notificationReceipt="支付成功，商品金额：10.00，优惠金额：3.62，实付款：6.38元，商户：京东，订单号：$order")
  val row=awaitEntries(order).single()
  assertEquals(638L,row.amountMinor)
  assertEquals("notification",app.database.book().evidence(row.evidenceId)!!.channel)
 }
 @Test fun messagingNotificationsKeepTwoPaymentsAndReplayWithoutDuplicates()=runBlocking {
  val first="$prefix-MESSAGE1";val second="$prefix-MESSAGE2"
  val messageTime=System.currentTimeMillis()
  fun post() {
   app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    .putExtra("receipt","首页").putExtra("notification",true).putExtra("notificationId",32).putExtra("messageTime",messageTime)
    .putExtra("paymentMessages",arrayOf("支付成功\n实付款：6.38\n订单号：$first","支付成功\n实付款：6.38\n订单号：$second")))
  }
  post()
  val a=awaitEntries(first).single();val b=awaitEntries(second).single()
  assertNotEquals(a.id,b.id);assertEquals(638L,a.amountMinor);assertEquals(638L,b.amountMinor)
  assertEquals("notification",app.database.book().evidence(a.evidenceId)!!.channel)
  post();delay(1200)
  assertEquals(1,awaitEntries(first).size);assertEquals(1,awaitEntries(second).size)
  assertFalse(app.database.book().evidence(a.evidenceId)!!.text.contains("普通聊天"))
 }
 @Test fun idlessMessagesKeepEqualAmountPaymentsSeparateAndReplayOnce()=runBlocking {
  val merchant="$prefix-商户";val time=System.currentTimeMillis()
  fun post() {
   app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    .putExtra("receipt","首页").putExtra("notification",true).putExtra("notificationId",33).putExtra("messageTime",time)
    .putExtra("paymentMessages",arrayOf("支付成功，实付款：6.38元，商户：$merchant","支付成功，实付款：6.38元，商户：$merchant")))
  }
  post()
  var entries=emptyList<LedgerEntry>()
  repeat(50){if(entries.size!=2){delay(200);entries=app.database.book().filtered(0,Long.MAX_VALUE,merchant,null,10).first()}}
  assertEquals(2,entries.size)
  assertTrue(entries.all{it.amountMinor==638L&&it.orderId==null})
  assertEquals(setOf(time,time+1),entries.map{it.occurredAt}.toSet())
  post();delay(1500)
  assertEquals(2,app.database.book().filtered(0,Long.MAX_VALUE,merchant,null,10).first().size)
 }
 @Test fun bankProseNotificationAndFulfilledOrderAreBothCaptured()=runBlocking {
  val merchant="$prefix-京东"
  launch("首页",notification=true,id=34,notificationReceipt="招商银行\n您尾号1234的储蓄卡于${merchant}消费人民币6.38元，余额10,000.00元。")
  var rows=emptyList<LedgerEntry>()
  repeat(50){if(rows.isEmpty()){delay(200);rows=app.database.book().filtered(0,Long.MAX_VALUE,merchant,null,10).first()}}
  assertEquals(638L,rows.single().amountMinor)
  assertFalse(app.database.book().evidence(rows.single().evidenceId)!!.text.contains("1234"))
  val order="$prefix-FULFILLED"
  launch("订单详情\n等待收货\n实付款：¥6.38\n商品金额：¥10.00\n订单编号：$order")
  assertEquals(638L,awaitEntries(order).single().amountMinor)
 }
 @Test fun unknownPageWordingPersistsForAgentWithoutFixedTitle()=runBlocking {
  val token="$prefix-UNKNOWN"
  launch("本次结算\n款项已从账户划走 6.38 元\n业务编号：$token")
  var evidence:Evidence?=null
  repeat(50){if(evidence==null){delay(200);evidence=app.database.book().evidenceSearch(0,Long.MAX_VALUE,100).firstOrNull{it.text.contains(token)}}}
  assertNotNull(evidence)
  assertEquals("page",evidence!!.channel)
  assertNotNull(app.database.book().job(evidence!!.id))
  assertTrue(app.database.book().forEvidence(evidence!!.id).isEmpty())
 }
 @Test fun deferredUseNoticeDoesNotRequirePaymentSuccessBanner()=runBlocking {
  val order="$prefix-DEFERNOTICE"
  launch("首页",notification=true,id=35,notificationReceipt="微信支付分\n先用后付使用通知\n你在拼多多有一笔先用后付订单，待付款金额：28.80元，订单号：$order")
  val row=awaitEntries(order).single()
  assertEquals("deferred",row.kind);assertEquals(2880L,row.amountMinor)
 }
 @Test fun optedInLocalOcrReadsImageOnlyReceipt()=runBlocking {
  val old=app.settings.screenshots
  app.settings.screenshots=true
  val start=System.currentTimeMillis()
  try {
   app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    .putExtra("canvasReceipt",true).putExtra("receipt","订单详情\n支付成功\n实付款：6.38元\n商户：京东"))
   val evidence=withTimeout(45_000){var found:Evidence?=null;while(found==null){found=app.database.book().evidenceSearch(start,Long.MAX_VALUE,50).firstOrNull{it.channel=="ocr"};if(found==null)delay(500)};found}
   assertTrue("OCR must preserve the amount for agent recognition",evidence.text.contains("6.38"))
   assertNotNull(app.database.book().job(evidence.id))
   app.database.book().forEvidence(evidence.id).singleOrNull()?.let{assertEquals(638L,it.amountMinor)}
   Unit
  } finally {app.settings.screenshots=old}
 }
 @Test fun receiptWithSearchField()=runBlocking {
  val order="$prefix-SEARCH"
  launch("订单详情\n支付成功\n实付款：6.38\n订单号：$order",searchField=true)
  val row=awaitEntries(order).single()
  assertEquals(638L,row.amountMinor)
  assertFalse(app.database.book().evidence(row.evidenceId)!!.text.contains("不应采集"))
 }
 @Test fun combinedAccessibleNode()=runBlocking {
  val order="$prefix-COMBINED"
  launch("订单详情\n先用后付\n下单成功\n待扣款金额：28.80\n订单号：$order",combinedNode=true)
  val row=awaitEntries(order).single()
  assertEquals("deferred",row.kind);assertEquals(2880L,row.amountMinor)
 }
 @Test fun continuousPageUpdatesCannotStarveCapture()=runBlocking {
  val order="$prefix-CHURN"
  launch("订单详情\n付款成功\n实付款：6.38\n订单号：$order",churn=true)
  assertEquals(638L,awaitEntries(order).single().amountMinor)
 }
 private suspend fun awaitCondition(condition:()->Boolean) {
  repeat(50){if(condition())return;delay(200)}
  check(condition())
 }
 @Test fun accessibilityReconnectReadsAlreadyOpenOrder()=runBlocking {
  shell("settings put secure enabled_accessibility_services null")
  awaitCondition{!app.captureDiagnostics.health.value.pageConnected}
  val order="$prefix-PAGE-RECONNECT"
  launch("订单详情\n先用后付\n下单成功\n待扣款金额：28.80\n订单号：$order",combinedNode=true)
  delay(900)
  assertTrue(app.database.book().ordersByExternalId(order).isEmpty())
  shell("settings put secure enabled_accessibility_services dev.localledger/dev.localledger.capture.PaymentAccessibilityService")
  shell("settings put secure accessibility_enabled 1")
  assertEquals("deferred",awaitEntries(order).single().kind)
  assertTrue(app.captureDiagnostics.health.value.pageConnected)
 }
 @Test fun reconnectReadsExistingNotificationWithoutOpeningOrder()=runBlocking {
  shell("cmd notification disallow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  awaitCondition{!app.captureDiagnostics.health.value.notificationConnected}
  val order="$prefix-RECONNECT"
  launch("首页",notification=true,id=17,notificationReceipt="付款成功\n实付款：6.38\n订单号：$order")
  delay(700)
  assertTrue(app.database.book().ordersByExternalId(order).isEmpty())
  shell("cmd notification allow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  val row=awaitEntries(order).single()
  assertEquals(638L,row.amountMinor)
  assertEquals("notification",app.database.book().evidence(row.evidenceId)!!.channel)
  assertTrue(app.captureDiagnostics.health.value.notificationConnected)
  // A second connection replays the same notification, without creating another entry.
  shell("cmd notification disallow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  awaitCondition{!app.captureDiagnostics.health.value.notificationConnected}
  shell("cmd notification allow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  awaitCondition{app.captureDiagnostics.health.value.notificationConnected}
  delay(700)
  assertEquals(1,awaitEntries(order).size)
 }
 @Test fun authorizingAppReplaysItsVisibleNotification()=runBlocking {
  app.settings.allow(pkg,false)
  val order="$prefix-AUTHORIZE"
  launch("首页",notification=true,id=18,notificationReceipt="下单成功\n先用后付\n待扣款金额：28.80\n订单号：$order")
  delay(700)
  assertTrue(app.database.book().ordersByExternalId(order).isEmpty())
  app.settings.allow(pkg,true)
  assertEquals("deferred",awaitEntries(order).single().kind)
 }
 @Test fun passwordAndUnrelatedPageDoNotPersistReceipts()=runBlocking {
  val order="$prefix-PRIVATE"
  launch("订单详情\n支付成功\n实付款：6.38\n订单号：$order",passwordField=true)
  awaitCondition{app.captureDiagnostics.observations.value.any{it.pkg==pkg&&it.result==dev.localledger.capture.CaptureResult.PRIVATE_PAGE}}
  assertTrue(app.database.book().ordersByExternalId(order).isEmpty())
  launch("我的订单搜索\n请打开订单详情查看支付成功截图\n金额：6.38\n订单号：$order",searchField=true)
  awaitCondition{app.captureDiagnostics.observations.value.any{it.pkg==pkg&&it.result==dev.localledger.capture.CaptureResult.NOT_RECEIPT}}
  assertTrue(app.database.book().ordersByExternalId(order).isEmpty())
  val metadata=app.getSharedPreferences("capture_health",android.content.Context.MODE_PRIVATE).all.values.joinToString()
  assertFalse(metadata.contains(order));assertFalse(metadata.contains("6.38"));assertFalse(metadata.contains("private-password"))
 }
 @Test fun actualNotificationAndAccessibilityPaths()=runBlocking {
  val jd="$prefix-JD"
  val receipt="订单详情\n支付成功\n商品金额：10.00\n优惠金额：3.62\n实付款\n￥6.38\n店铺：青光文具\n商品名称：中性笔\n订单编号：$jd\n收货地址：测试私密地址\n手机号：13812345678"
  launch(receipt)
  val row=awaitEntries(jd).single()
  assertEquals(638L,row.amountMinor)
  assertEquals("中性笔",app.database.book().items(row.orderId!!).single().name)
  val evidence=app.database.book().evidence(row.evidenceId)!!
  assertEquals("page",evidence.channel);assertFalse(evidence.text.contains("私密地址"));assertFalse(evidence.text.contains("13812345678"))
  // Revisiting the same order and receiving its notification must enrich, not duplicate it.
  launch("首页");delay(800);launch(receipt,true,11);delay(2000)
  assertEquals(1,awaitEntries(jd).size)
  val pdd="$prefix-PDD"
  launch("订单详情\n先用后付\n下单成功\n实付款：0.00\n待扣款金额：28.80\n商品名称：保温杯\n店铺：日用店\n订单号：$pdd")
  val pending=awaitEntries(pdd).single();assertEquals("deferred",pending.kind);assertEquals(2880L,pending.amountMinor)
  launch("订单详情\n先用后付\n扣款成功\n扣款金额：28.80\n商品名称：保温杯\n店铺：日用店\n订单号：$pdd")
  repeat(50) {if(app.database.book().entry(pending.id)?.kind!="expense")delay(200)}
  assertEquals("expense",app.database.book().entry(pending.id)!!.kind)
  assertEquals(1,awaitEntries(pdd).size)
  val notify="$prefix-NOTIFY"
  // No page title: only the notification service can see this receipt.
  launch("付款成功\n付款金额：8.80\n商户：早餐铺\n订单号：$notify",true,12)
  awaitEntries(notify)
  delay(1000)
  val evs=app.database.book().evidenceForEntry(awaitEntries(notify).single().id)
  assertTrue(evs.any {it.channel=="notification"})
  // Two real events of the same amount with different IDs remain separate.
  val second="$prefix-SECOND"
  launch("订单详情\n付款成功\n付款金额：8.80\n订单号：$second")
  assertNotEquals(awaitEntries(notify).single().id,awaitEntries(second).single().id)
  val unpaid="$prefix-UNPAID"
  launch("订单详情\n待付款\n实付款：99.00\n订单号：$unpaid");delay(1800)
  assertTrue(app.database.book().ordersByExternalId(unpaid).flatMap {app.database.book().forOrder(it.id)}.isEmpty())
  app.settings.allow(pkg,false)
  val unauthorized="$prefix-DENIED"
  launch("订单详情\n支付成功\n金额：99.00\n订单号：$unauthorized",true,13);delay(1800)
  assertTrue(app.database.book().ordersByExternalId(unauthorized).isEmpty())
 }
}
