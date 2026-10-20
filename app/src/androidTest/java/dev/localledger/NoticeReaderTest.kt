package dev.localledger

import android.app.Notification
import android.app.Person
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localledger.capture.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoticeReaderTest {
 @Test fun androidMessageBundlesKeepSenderAndTimestamp() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val style=Notification.MessagingStyle(Person.Builder().setName("本机样本").build())
   .addMessage("支付成功，实付款：6.38元，订单号：PLATFORM111",1000,Person.Builder().setName("微信支付").build())
   .addMessage("支付成功，实付款：99.00元，订单号：PRIVATE111",1001,Person.Builder().setName("朋友").build())
  val notification=Notification.Builder(context,"payments").setSmallIcon(android.R.drawable.ic_dialog_info).setStyle(style).build()
  val notices=PaymentNoticeContent.extract("com.tencent.mm",AndroidNoticeReader.read(notification),2000)
  assertEquals(1,notices.size);assertEquals(1000L,notices.single().time)
  assertEquals(638L,ReceiptParser.parseNotification(notices.single().text)!!.amountMinor)
  assertFalse(notices.single().text.contains("PRIVATE"))
 }
 @Test fun androidInboxDoesNotJoinDifferentSenders() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val notification=Notification.Builder(context,"payments").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("微信")
   .setStyle(Notification.InboxStyle().addLine("朋友：支付成功，金额：99.00元").addLine("微信支付：支付成功，实付款：6.38元，订单号：PLATFORM222")).build()
  val notices=PaymentNoticeContent.extract("com.tencent.mm",AndroidNoticeReader.read(notification),2000)
  assertEquals(1,notices.size)
  assertEquals(638L,ReceiptParser.parseNotification(notices.single().text)!!.amountMinor)
 }
}
