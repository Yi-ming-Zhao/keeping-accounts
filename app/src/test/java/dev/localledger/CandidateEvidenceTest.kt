package dev.localledger
import dev.localledger.capture.*
import org.junit.Assert.*
import org.junit.Test
class CandidateEvidenceTest {
 @Test fun unfamiliarFinancialWordingSurvivesWithoutSuccessfulParser() {
  val text="本次结算\n款项已从账户划走 6.38 元\n业务编号：BUSINESS20261022001"
  assertNull(ReceiptParser.parse(text))
  assertTrue(CandidateEvidence.financialPage(text.lines(),"shop"))
  assertTrue(CandidateEvidence.prepare(text,true)!!.contains("已从账户划走"))
 }
 @Test fun deferredMeaningIsNotRemovedAsShippingPrivacy() {
  val text="订单服务\n确认收货后自动向你收取28.80元，现在还没有扣钱。\n收货地址\n测试路123号\n订单号：123456789012345678"
  val safe=CandidateEvidence.prepare(text,true)!!
  assertTrue(safe.contains("还没有扣钱"));assertFalse(safe.contains("测试路"));assertTrue(safe.contains("123456789012345678"))
 }
 @Test fun chatAndSearchPagesCannotUseBroadFinancialBoundary() {
  assertFalse(CandidateEvidence.financialPage(listOf("付款6.38元"),"com.tencent.mm"))
  assertFalse(CandidateEvidence.financialPage(listOf("订单搜索结果","价格6.38元"),"shop"))
 }
}
