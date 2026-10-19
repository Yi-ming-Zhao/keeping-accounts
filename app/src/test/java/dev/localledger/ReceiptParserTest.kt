package dev.localledger
import dev.localledger.capture.*
import org.junit.Assert.*
import org.junit.Test
class ReceiptParserTest {
 @Test fun paymentFactSurvivesButAddressAndPhoneDoNot() {
  val parsed=ReceiptParser.parse("支付成功\n实付款\n￥12.34\n商户\n早餐店\n收货地址：某市某街\n手机号：13812345678\n订单号：ORDER12345")!!
  assertEquals(1234L,parsed.amountMinor);assertEquals("早餐店",parsed.merchant)
  assertFalse(parsed.text.contains("某街"));assertFalse(parsed.text.contains("13812345678"));assertEquals("ORDER12345",parsed.orderId)
 }
 @Test fun unpaidAndFailedAreNotExpenses() {
  assertFalse(ReceiptParser.parse("订单详情\n待付款\n实付款：12.34")!!.confirmed)
  assertFalse(ReceiptParser.parse("交易详情\n支付成功\n退款处理中\n金额：12.34")!!.confirmed)
  assertNull(ReceiptParser.parse("你好，今晚吃什么？"))
 }
 @Test fun ambiguousCurrencyValuesDoNotInventAmount() {
  assertNull(ReceiptParser.parse("支付成功\n￥12.00\n￥18.00")!!.amountMinor)
 }
 @Test fun refundsRepaymentsAndTransfersRemainDistinct() {
  assertEquals("refund",ReceiptParser.parse("退款成功\n退款金额：5.00")!!.kind)
  assertEquals("repayment",ReceiptParser.parse("还款成功\n还款金额：30.00")!!.kind)
  assertEquals("transfer_candidate",ReceiptParser.parse("充值成功\n充值金额：50.00\n校园卡")!!.kind)
 }
 @Test fun explicitForeignCurrencyIsNeverRecordedAsCny() {
  assertNull(ReceiptParser.parse("支付成功\n金额：12.00 USD"))
 }
 @Test fun historicalPaymentDateAndRefundIdsArePreserved() {
  val receipt=ReceiptParser.parse("支付成功\n金额：12.00\n支付时间：2026-09-01 12:30:00")!!
  val expected=java.time.LocalDateTime.of(2026,9,1,12,30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
  assertEquals(expected,receipt.occurredAt)
  assertEquals("REFUND12345",ReceiptParser.parse("退款成功\n金额：2.00\n交易单号：ORIGINAL12345\n退款单号：REFUND12345")!!.externalId)
 }
 @Test fun jdActualPaymentWinsOverListPriceAndDiscount() {
  val r=ReceiptParser.parse("订单详情\n支付成功\n商品金额：10.00\n优惠金额：3.62\n实付款\n￥6.38\n商品名称：中性笔")!!
  assertEquals(638L,r.amountMinor);assertTrue(r.text.contains("中性笔"))
 }
 @Test fun deferredOrderIsVisibleBeforeCashPayment() {
  val r=ReceiptParser.parse("订单详情\n先用后付\n下单成功\n实付款：0.00\n待扣款金额：28.80")!!
  assertEquals("deferred",r.kind);assertTrue(r.confirmed);assertEquals(2880L,r.amountMinor)
 }
 @Test fun conflictingActualAmountsStayUnknown() {
  assertNull(ReceiptParser.parse("支付成功\n实付款：6.38\n实付金额：10.00")!!.amountMinor)
  assertNull(ReceiptParser.parse("支付成功\n商品金额：10.00\n优惠金额：3.62")!!.amountMinor)
 }
 @Test fun deferredAdvertisementDoesNotMakeAnOrder() {
  assertNull(ReceiptParser.parse("开通先用后付，享0元下单\n商品金额：20.00"))
  assertFalse(ReceiptParser.parse("订单详情\n先用后付\n待付款\n金额：20.00")!!.confirmed)
 }
 @Test fun zeroYuanCreditCheckoutIsNotZeroExpense() {
  val r=ReceiptParser.parse("订单详情\n支付成功\n先用后付\n实付款：0.00\n待扣款金额：28.80")!!
  assertEquals("deferred",r.kind);assertEquals(2880L,r.amountMinor)
 }
 @Test fun itemEvidenceKeepsPricesBesideTheirOwnProduct() {
  val r=ReceiptParser.parse("支付成功\n实付款：20.00\n商品名称：面包\n单价：8.00\n商品名称：牙刷\n单价：12.00")!!
  assertTrue(r.text.contains("商品名称：面包\n单价：8.00\n商品名称：牙刷\n单价：12.00"))
 }
 @Test fun redactedNumericIdsDoNotCollapseDistinctPayments() {
  val a=ReceiptParser.parse("支付成功\n实付款：8.80\n订单号：123456789012345678")!!
  val b=ReceiptParser.parse("支付成功\n实付款：8.80\n订单号：123456789012345679")!!
  assertEquals(a.text,b.text)
  assertNotEquals(ReceiptParser.identityFingerprint(a),ReceiptParser.identityFingerprint(b))
 }
}
