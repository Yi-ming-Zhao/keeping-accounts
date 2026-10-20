package dev.localledger

import dev.localledger.capture.*
import org.junit.Assert.*
import org.junit.Test

class PaymentNoticeContentTest {
 private val pkg="com.tencent.mm"
 private val paid="支付成功，商品金额：10.00，优惠金额：3.62，实付款：6.38元，商户：京东，订单号：JD123456"
 @Test fun numberedPaymentTitleAndInlineFieldsAreSupported() {
  val notice=PaymentNoticeContent.extract(pkg,NoticeEnvelope(title="[1条]微信支付",text=paid),100).single()
  val receipt=ReceiptParser.parseNotification(notice.text)!!
  assertEquals(638L,receipt.amountMinor);assertEquals("京东",receipt.merchant);assertEquals("JD123456",receipt.orderId)
  assertTrue(PaymentNoticeContent.isPaymentSender("微信支付（2条新消息）"))
  assertFalse(PaymentNoticeContent.isPaymentSender("微信支付助手"))
 }
 @Test fun mixedMessagesSelectOnlyPaymentSenderAndNeverFallbackToCombinedText() {
  val envelope=NoticeEnvelope(title="微信",bigText="普通聊天隐私",messages=listOf(
   NoticeMessage("朋友","支付成功，金额：99.00元，订单号：PRIVATE123",100),
   NoticeMessage("微信支付",paid,200),NoticeMessage(null,paid,300)))
  val notice=PaymentNoticeContent.extract(pkg,envelope,500).single()
  assertEquals(200L,notice.time);assertTrue(notice.message)
  assertFalse(notice.text.contains("PRIVATE"));assertFalse(notice.text.contains("隐私"))
 }
 @Test fun genericTitleSelectsOnlySelfContainedPaymentLine() {
  val envelope=NoticeEnvelope(title="微信",text="朋友：私人聊天\n微信支付：$paid\n商户：聊天里的商户不应保存")
  val notice=PaymentNoticeContent.extract(pkg,envelope,100).single()
  assertEquals("京东",ReceiptParser.parseNotification(notice.text)!!.merchant)
  assertFalse(notice.text.contains("私人"));assertFalse(notice.text.contains("不应保存"))
  assertTrue(PaymentNoticeContent.extract(pkg,NoticeEnvelope(title="朋友",text=paid),100).isEmpty())
 }
 @Test fun summariesNeedStrongIdsAndKeepMessagesSeparate() {
  val envelope=NoticeEnvelope(summary=true,messages=listOf(NoticeMessage("微信支付",paid,100),NoticeMessage("微信支付","支付成功\n金额：6.38",101)))
  assertEquals(1,PaymentNoticeContent.extract(pkg,envelope,200).size)
  assertTrue(PaymentNoticeContent.extract(pkg,NoticeEnvelope(title="微信支付",text=paid,summary=true),200).isEmpty())
  assertNull(ReceiptParser.parseNotification("支付成功\n金额：6.38\n订单号：ORDER111\n订单号：ORDER222"))
 }
 @Test fun inlineFormattingDoesNotWeakenAmountOrPaymentValidation() {
  assertNull(ReceiptParser.parseNotification("支付成功，实付款：6.38元，实付金额：10.00元")!!.amountMinor)
  assertNull(ReceiptParser.parseNotification("支付成功，商品金额：10.00，优惠金额：3.62")!!.amountMinor)
  assertFalse(ReceiptParser.parseNotification("订单详情\n待付款，实付款：6.38")!!.confirmed)
  assertNull(ReceiptParser.parseNotification("支付成功，实付款：6.38 USD"))
  assertEquals(123456L,ReceiptParser.parseNotification("支付成功，实付款：1,234.56元。")!!.amountMinor)
 }
 @Test fun inboxLinesDoNotFuseTwoPayments() {
  val notices=PaymentNoticeContent.extract(pkg,NoticeEnvelope(title="微信",lines=listOf("朋友：私人聊天","微信支付：$paid","微信支付：${paid.replace("JD123456","JD123457")}")),100)
  assertEquals(2,notices.size)
  assertEquals(listOf("JD123456","JD123457"),notices.map{ReceiptParser.parseNotification(it.text)!!.orderId})
 }
}
