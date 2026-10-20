package dev.localledger

import dev.localledger.capture.CaptureDebounce
import dev.localledger.capture.PageReceiptBoundary
import org.junit.Assert.*
import org.junit.Test

class CaptureSchedulingTest {
 @Test fun continuousEventsHaveBoundedWait() {
  val debounce=CaptureDebounce()
  assertEquals(700L,debounce.delay(0))
  assertEquals(700L,debounce.delay(1000))
  assertEquals(200L,debounce.delay(1800))
  assertEquals(0L,debounce.delay(2100))
  debounce.reset()
  assertEquals(700L,debounce.delay(2200))
 }
 @Test fun boundaryRequiresCompleteTitleLine() {
  assertTrue(PageReceiptBoundary.isReceipt(listOf("订单详情\n实付款：6.38")))
  assertTrue(PageReceiptBoundary.isReceipt(listOf("  订单详情  ")))
  assertFalse(PageReceiptBoundary.isReceipt(listOf("请打开订单详情查看支付成功截图")))
  assertFalse(PageReceiptBoundary.isReceipt(listOf("搜索订单", "商品金额：10.00")))
  assertFalse(PageReceiptBoundary.isReceipt(listOf("订单详情\n支付成功\n金额：6.38"),"com.tencent.mm"))
  assertTrue(PageReceiptBoundary.isReceipt(listOf("订单详情","实付款：6.38"),"com.tencent.mm"))
 }
}
