package dev.localledger
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.agent.CheckedModelTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.*
@RunWith(AndroidJUnit4::class)
class AnswerValidationTest {
 private val request=JSONObject().put("messages",JSONArray().put(JSONObject().put("role","tool").put("content","{\"amountMinor\":7556,\"expenseMinor\":7556,\"refundMinor\":0}"))).toString()
 private fun answer(text:String)=JSONObject().put("choices",JSONArray().put(JSONObject().put("message",JSONObject().put("content",text)))).toString()
 @Test fun unsupportedMoneyIsRepairedBeforeDisplaying()=runBlocking {
  assertEquals(setOf(6356L),CheckedModelTransport.unsupportedAmounts(request,answer("本月 ¥75.56，其中支出 ¥63.56")))
  var calls=0
  val transport=CheckedModelTransport {body->calls++;if(calls==1)answer("支出 ¥63.56") else {assertTrue(body.contains("63.56"));answer("本月净支出 ¥75.56，退款 0.00 元")}}
  assertTrue(transport.request(request).contains("75.56"));assertEquals(2,calls)
 }
 @Test fun repeatedUnsupportedMoneyFailsClosed()=runBlocking {
  try {CheckedModelTransport{answer("共 ￥99.00")}.request(request);fail("Must not show unsupported total")}catch(_:IllegalStateException){}
 }
}
