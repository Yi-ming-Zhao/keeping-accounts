package dev.localledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.agent.*
import dev.localledger.capture.*
import dev.localledger.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AgentRecognitionTest {
 private val app=ApplicationProvider.getApplicationContext<LedgerApplication>()
 private lateinit var db:LedgerDatabase
 private lateinit var name:String
 @Before fun setup(){name="recognition-${UUID.randomUUID()}.db";db=LedgerDatabase.open(app,name)}
 @After fun close(){db.close();app.deleteDatabase(name)}
 private suspend fun seed(text:String):String {
  val id=UUID.randomUUID().toString()
  db.book().insertEvidence(Evidence(id,id,"shop","notification",1000,text))
  db.book().saveJob(LedgerJob(id,id))
  return id
 }
 @Test fun recognitionValidatesQuotesAndIsIdempotent()=runBlocking {
  val id=seed("这笔购物款已经从卡中划走\n结算总计 6.38 元\n商户：京东\n订单号：UNKNOWN20261022")
  val tools=LedgerTools(app,"recognize",evidenceScope=id,database=db)
  val args=JSONObject().put("kind","expense").put("statusQuote","这笔购物款已经从卡中划走").put("amountQuote","结算总计 6.38 元").put("amountMinor",638).put("merchant","京东").put("orderId","UNKNOWN20261022")
  tools.execute("recognize_transaction",args,"first")
  tools.execute("recognize_transaction",args,"replayed")
  assertEquals(638L,db.book().forEvidence(id).single().amountMinor)
  assertEquals(638L,db.book().total(0,2000))
  assertNotNull(db.book().forEvidence(id).single().orderId)
 }
 @Test fun fabricatedAmountAndMissingQuoteCannotWrite()=runBlocking {
  val id=seed("购物款已结清\n本单合计 6.38 元")
  val tools=LedgerTools(app,"reject",evidenceScope=id,database=db)
  for(args in listOf(
   JSONObject().put("kind","expense").put("statusQuote","购物款已结清").put("amountQuote","本单合计 6.38 元").put("amountMinor",1000),
   JSONObject().put("kind","expense").put("statusQuote","支付成功").put("amountQuote","本单合计 6.38 元").put("amountMinor",638))) {
   try{tools.execute("recognize_transaction",args,UUID.randomUUID().toString());fail("Must reject unsupported evidence")}catch(_:IllegalArgumentException){}
  }
  assertTrue(db.book().forEvidence(id).isEmpty())
 }
 @Test fun uncertaintyRemainsVisibleWithoutInventingCashFlow()=runBlocking {
  val id=seed("一笔付款等待银行确认，涉及6.38元")
  LedgerTools(app,"uncertain",evidenceScope=id,database=db).execute("recognize_transaction",JSONObject().put("kind","uncertain").put("statusQuote","等待银行确认"),"one")
  assertEquals("needs_review",db.book().job(id)!!.state)
  assertTrue(db.book().forEvidence(id).isEmpty())
 }
 @Test fun bankPrivacyAndAddressValuesAreRemovedBeforeStorage() {
  val safe=CandidateEvidence.prepare("订单详情\n张某先生\n姓名\n张三\n收货地址\n广东省广州市测试路123号\n手机号：13812345678\n银行卡号：6212345678901234\n商品：保温杯\n交易款已结清：6.38元",true)!!
  assertFalse(safe.contains("张三"));assertFalse(safe.contains("123号"));assertFalse(safe.contains("13812345678"));assertFalse(safe.contains("621234"))
  assertTrue(safe.contains("保温杯"));assertTrue(safe.contains("6.38"))
 }
}
