package dev.localledger

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localledger.capture.ReceiptParser
import dev.localledger.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureServiceTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LedgerApplication
 private val pkg="dev.localledger.fixtures"
 private val prefix="QA${System.currentTimeMillis()}"
 private fun shell(command:String) {InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }}
 private fun launch(text:String,notification:Boolean=false,id:Int=1) {
  app.startActivity(Intent().setClassName(pkg,"$pkg.ScenarioActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra("receipt",text).putExtra("notification",notification).putExtra("notificationId",id))
 }
 private suspend fun awaitEntries(order:String,expected:Int=1):List<LedgerEntry> {
  repeat(50) {
   val rows=app.database.book().ordersByExternalId(order).flatMap {app.database.book().forOrder(it.id)}
   if(rows.size==expected)return rows
   delay(200)
  }
  error("采集服务未生成预期记录：$order")
 }
 @Test fun actualNotificationAndAccessibilityPaths()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"))
  app.settings.allow(pkg,true)
  shell("pm grant $pkg android.permission.POST_NOTIFICATIONS")
  shell("cmd notification allow_listener dev.localledger/dev.localledger.capture.PaymentNotificationService")
  shell("settings put secure enabled_accessibility_services null")
  delay(300)
  shell("settings put secure enabled_accessibility_services dev.localledger/dev.localledger.capture.PaymentAccessibilityService")
  shell("settings put secure accessibility_enabled 1")
  delay(2000)
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
