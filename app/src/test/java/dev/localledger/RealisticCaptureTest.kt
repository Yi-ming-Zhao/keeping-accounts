package dev.localledger

import dev.localledger.capture.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic variants of the reported paths, not samples claimed to come from the user's phone. */
class RealisticCaptureTest {
 @Test fun wechatCreditUseNoticeWithoutPaymentSuccessIsPending() {
  val notices=PaymentNoticeContent.extract("com.tencent.mm",NoticeEnvelope(title="微信支付分",text="先用后付使用通知\n你在拼多多有一笔先用后付订单，待付款金额：28.80元，订单号：PDD20261022001"),100)
  assertEquals(1,notices.size)
  val c=ReceiptParser.parseNotification(notices.single().text)!!
  assertEquals("deferred",c.kind);assertTrue(c.confirmed);assertEquals(2880L,c.amountMinor)
  assertEquals("deferred",ReceiptParser.parse(c.text)!!.kind)
 }
 @Test fun bankConsumptionSentenceExtractsDebitNotBalanceOrCardNumber() {
  val c=ReceiptParser.parseNotification("招商银行\n您尾号1234的储蓄卡10月22日于京东快捷支付消费人民币6.38元，余额10,000.00元。")!!
  assertEquals(638L,c.amountMinor);assertEquals("expense",c.kind);assertTrue(c.confirmed)
  assertFalse(c.text.contains("1234"));assertFalse(c.text.contains("10,000"))
  assertEquals(638L,ReceiptParser.parse(c.text)!!.amountMinor)
 }
 @Test fun bankSenderInsideWechatIsSelectedButFriendIsNot() {
  val body="您尾号1234的信用卡消费人民币6.38元，商户：京东"
  val notices=PaymentNoticeContent.extract("com.tencent.mm",NoticeEnvelope(messages=listOf(NoticeMessage("招商银行信用卡",body,100),NoticeMessage("朋友",body,101))),200)
  assertEquals(1,notices.size);assertEquals(638L,ReceiptParser.parseNotification(notices.single().text)!!.amountMinor)
 }
 @Test fun fulfilledOrderWithActualPaymentButNoSuccessBannerIsRecorded() {
  val c=ReceiptParser.parse("订单详情\n等待收货\n实付款：¥6.38\n商品金额：¥10.00\n优惠：¥3.62\n订单编号：JD20261022001")!!
  assertTrue(c.confirmed);assertEquals(638L,c.amountMinor)
  assertTrue(ReceiptParser.parse(c.text)!!.confirmed)
 }
 @Test fun splitCurrencyAndDecimalNodesKeepActualAmount() {
  val c=ReceiptParser.parse("订单详情\n等待商家发货\n实付\n¥\n6\n.38\n订单编号\nJD20261022002")!!
  assertEquals(638L,c.amountMinor);assertTrue(c.confirmed)
 }
 @Test fun pddPendingPageUsesOrderAmountNotZeroCheckout() {
  val c=ReceiptParser.parse("订单详情\n等待买家确认收货\n微信支付分\n先用后付\n合计：￥28.80\n本次支付：￥0.00\n订单编号：PDD20261022002")!!
  assertEquals("deferred",c.kind);assertEquals(2880L,c.amountMinor);assertTrue(c.confirmed)
 }
 @Test fun bankFailuresAdsForeignCurrencyAndDeferredAdsCannotBecomeExpense() {
  assertNull(ReceiptParser.parseNotification("招商银行\n您尾号1234的卡消费人民币6.38元失败，余额100元"))
  assertNull(ReceiptParser.parseNotification("招商银行\n消费人民币满100元返10元，立即参与"))
  assertNull(ReceiptParser.parseNotification("招商银行\n您尾号1234的卡消费USD6.38，折合人民币45.00元"))
  assertNull(ReceiptParser.parseNotification("微信支付分\n先用后付使用攻略，最高额度1000元"))
  assertFalse(ReceiptParser.parse("订单详情\n待付款\n实付款：6.38\n订单号：UNPAID20261022")!!.confirmed)
  assertFalse(ReceiptParser.parse("订单详情\n货到付款\n待收货\n实付款：6.38\n订单号：COD20261022")!!.confirmed)
 }
}
