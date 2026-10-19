package dev.localledger

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.agent.*
import dev.localledger.capture.*
import dev.localledger.data.*
import dev.localledger.ledger.*
import dev.localledger.security.BackupManager
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LedgerIntegrationTest {
 private val app=ApplicationProvider.getApplicationContext<LedgerApplication>()
 private lateinit var db:LedgerDatabase
 private lateinit var repo:LedgerRepository
 private lateinit var name:String
 @Before fun setup() {name="book-test-${UUID.randomUUID()}.db";db=LedgerDatabase.open(app,name);repo=LedgerRepository(db,db.book())}
 @After fun close(){db.close();app.deleteDatabase(name)}
 private fun receipt(amount:String="12.34",order:String?=null,status:String="支付成功")=ReceiptParser.parse("$status\n金额：$amount\n商户：早餐店"+(order?.let{"\n订单号：$it"}?:""))!!
 @Test fun duplicateSourceIsIdempotentButEqualPaymentsRemainSeparate()=runBlocking {
  repo.ingest("wx","notification","one",1000,receipt())
  repo.ingest("wx","notification","one",1000,receipt())
  repo.ingest("wx","notification","two",1001,receipt())
  assertEquals(2468L,db.book().total(0,2000))
  assertEquals(2,db.book().search(0,2000,"CNY",null,null,null,null,null).size)
 }
 @Test fun crossSourceCommonOrderDoesNotDoubleCount()=runBlocking {
  repo.ingest("taobao","page","order",1000,receipt(order="ORDER123456"))
  repo.ingest("alipay","notification","payment",1001,receipt(order="ORDER123456"))
  assertEquals(1234L,db.book().total(0,2000))
  val entries=db.book().search(0,2000,"CNY",null,null,null,null,null)
  assertEquals(1,entries.size);assertEquals(2,db.book().allocations(entries.single().id).size)
 }
 @Test fun unpaidRefundRepaymentAndTopupAccounting()=runBlocking {
  repo.ingest("wx","page","unpaid",1000,ReceiptParser.parse("订单详情\n待付款\n金额：88.00")!!)
  repo.ingest("meituan","page","expense",1000,receipt("30.00"))
  repo.ingest("meituan","page","repayment",1001,receipt("30.00",status="还款成功"))
  repo.ingest("wx","page","topup",1002,ReceiptParser.parse("充值成功\n金额：50.00\n校园卡")!!)
  repo.ingest("meituan","page","refund",1003,receipt("5.00",status="退款成功"))
  assertEquals(2500L,db.book().total(0,2000))
 }
 @Test fun userCorrectionWinsAndCanBeUndone()=runBlocking {
  val evidence=repo.ingest("wx","page","entry",1000,receipt())!!
  db.book().saveCategory(Category("餐饮"));db.book().saveCategory(Category("生活"))
  val entry=db.book().forEvidence(evidence).single()
  repo.correct(entry.id,"生活","手动修正")
  LedgerTools(app,"tools",evidenceScope=evidence,database=db).execute("classify_entry",JSONObject().put("id",entry.id).put("category","餐饮").put("quote","早餐店"),"1")
  assertEquals("生活",db.book().entry(entry.id)?.category)
  repo.undo(db.book().corrections(entry.id).single().id)
  assertNull(db.book().entry(entry.id)?.category)
 }
 @Test fun itemPricesRequireEvidenceAndMixedCategoriesRemainStructured()=runBlocking {
  val evidence=repo.ingest("shop","page","mixed",1000,ReceiptParser.parse("支付成功\n金额：20.00\n订单号：MIXED12345\n商品：面包 8.00\n商品：牙刷 12.00")!!)!!
  db.book().saveCategory(Category("餐饮"));db.book().saveCategory(Category("生活"))
  val entry=db.book().forEvidence(evidence).single()
  val tools=LedgerTools(app,"items",evidenceScope=evidence,database=db)
  tools.execute("attach_items",JSONObject().put("orderId",entry.orderId).put("items",JSONArray().put(JSONObject().put("name","面包").put("quote","商品：面包 8.00").put("totalMinor",800).put("category","餐饮")).put(JSONObject().put("name","牙刷").put("quote","商品：牙刷 12.00").put("totalMinor",1200).put("category","生活"))),"one")
  assertEquals(2,db.book().items(entry.orderId!!).size)
  try {tools.execute("attach_items",JSONObject().put("orderId",entry.orderId).put("items",JSONArray().put(JSONObject().put("name","面包").put("quote","商品：面包 8.00").put("totalMinor",900))),"two");fail("Invented price must fail")}catch(_:IllegalArgumentException){}
 }
 @Test fun encryptedBackupRestoresAndWrongPasswordChangesNothing()=runBlocking {
  val file=File(app.cacheDir,"backup-${UUID.randomUUID()}.ledger")
  try {
   repo.ingest("wx","page","original",1000,receipt())
   val backup=BackupManager(app,db);val uri=android.net.Uri.fromFile(file)
   backup.export(uri,"test-password".toCharArray())
   assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("transactions"))
   repo.ingest("wx","page","second",1001,receipt())
   try {backup.restore(uri,"wrong-password".toCharArray());fail("Wrong password")}catch(_:IllegalStateException){}
   assertEquals(2468L,db.book().total(0,2000))
   backup.restore(uri,"test-password".toCharArray())
   assertEquals(1234L,db.book().total(0,2000))
  } finally {file.delete()}
 }
 @Test fun versionOneProbeDatabaseMigratesWithoutLosingRecords()=runBlocking {
  db.close();app.deleteDatabase(name)
  val helper=net.zetetic.database.sqlcipher.SupportOpenHelperFactory(app.vault.databaseKey()).create(
   androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(app).name(name).callback(object:androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1){
    override fun onCreate(old:androidx.sqlite.db.SupportSQLiteDatabase){
     old.execSQL("CREATE TABLE agent_runs (id TEXT NOT NULL PRIMARY KEY,messages TEXT NOT NULL,state TEXT NOT NULL,updatedAt INTEGER NOT NULL)")
     old.execSQL("CREATE TABLE tool_receipts (sessionId TEXT NOT NULL,callId TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(sessionId,callId))")
     old.execSQL("CREATE TABLE probe_records (id TEXT NOT NULL PRIMARY KEY,amountMinor INTEGER NOT NULL,label TEXT NOT NULL)")
     old.execSQL("INSERT INTO probe_records VALUES ('legacy',1234,'验证')")
    }
    override fun onUpgrade(old:androidx.sqlite.db.SupportSQLiteDatabase,from:Int,to:Int){}
   }).build())
  helper.writableDatabase;helper.close()
  db=LedgerDatabase.open(app,name)
  assertEquals(1234L,db.dao().probe("legacy")?.amountMinor)
  assertEquals(0L,db.book().total(0,Long.MAX_VALUE))
 }
 @Test fun stablePaymentKeepsBothEvidenceAndEnrichesOrder()=runBlocking {
  val first=repo.ingest("wx","notification","n",1000,ReceiptParser.parse("支付成功\n金额：20.00\n交易单号：PAY123456")!!)!!
  val second=repo.ingest("wx","page","p",2000,ReceiptParser.parse("支付成功\n金额：20.00\n交易单号：PAY123456\n订单号：ORDER12345\n商品：牙刷\n运费：2.00\n优惠：1.00")!!)!!
  val entry=db.book().forEvidence(first).single()
  assertEquals(2000L,db.book().total(0,3000))
  assertEquals(entry.id,db.book().forEvidence(second).single().id)
  assertEquals(2,db.book().evidenceForEntry(entry.id).size)
  assertEquals(setOf("shipping","discount"),db.book().charges(entry.orderId!!).map {it.kind}.toSet())
 }
 @Test fun creditRepaymentFeeAndPartialRefundHaveCorrectCashflow()=runBlocking {
  val purchase=repo.ingest("mt","page","purchase",1000,ReceiptParser.parse("支付成功\n金额：100.00\n订单号：CREDIT1234\n美团月付")!!)!!
  repo.ingest("mt","page","payback",1001,ReceiptParser.parse("还款成功\n金额：101.00\n手续费：1.00\n美团月付")!!)
  val refund=repo.ingest("mt","page","refund",1002,ReceiptParser.parse("退款成功\n金额：20.00\n订单号：CREDIT1234\n退款单号：REFUND1234")!!)!!
  val original=db.book().forEvidence(purchase).single();val returned=db.book().forEvidence(refund).single()
  LedgerTools(app,"refund",evidenceScope=refund,database=db).execute("link_refund",JSONObject().put("refundId",returned.id).put("originalId",original.id),"link")
  repo.ingest("mt","notification","refund-repost",1003,ReceiptParser.parse("退款成功\n金额：20.00\n退款单号：REFUND1234")!!)
  assertEquals(8100L,db.book().total(0,2000))
  assertEquals(-2000L,db.book().accountOutflow("meituan-credit",2000))
  assertEquals(10000L,db.book().search(0,2000,"CNY",null,null,"repayment",null,null).single().amountMinor)
 }
 @Test fun completeItemAllocationChangesCategoryTotalsWithoutInventingDiscounts()=runBlocking {
  val ev=repo.ingest("shop","page","items-stat",1000,ReceiptParser.parse("支付成功\n金额：20.00\n订单号：ITEMS12345\n商品：面包 8.00\n商品：牙刷 12.00")!!)!!
  val order=db.book().forEvidence(ev).single().orderId!!
  db.book().saveItem(OrderItem("bread",order,"面包",null,null,800,"餐饮",ev))
  assertEquals(2000L,db.book().total(0,2000));assertEquals(0L,db.book().total(0,2000,category="餐饮"))
  assertEquals(0L,db.book().productTotal(0,2000,"面包",null,null,null));assertEquals(1,db.book().productUnknownOrders(0,2000,"面包",null,null))
  db.book().saveItem(OrderItem("brush",order,"牙刷",null,null,1200,"生活",ev))
  assertEquals(800L,db.book().total(0,2000,category="餐饮"));assertEquals(1200L,db.book().total(0,2000,category="生活"))
  assertEquals(1200L,db.book().productTotal(0,2000,"牙刷",null,null,null));assertEquals(0,db.book().productUnknownOrders(0,2000,"牙刷",null,null))
  assertEquals(1,db.book().search(0,2000,"CNY",null,"餐饮",null,null,null).size)
  assertEquals(2000L,db.book().total(0,2000))
 }
 @Test fun liveDeepSeekClassifiesAndQueriesRealLedger()=runBlocking {
  val configFile=File(app.filesDir,"probe-config.json")
  Assume.assumeTrue(configFile.exists())
  val config=ModelConfig.fromJson(configFile.readText())
  db.book().saveCategory(Category("餐饮"))
  val evidence=repo.ingest("wx","page","live-book",1000,receipt())!!
  val entry=db.book().forEvidence(evidence).single()
  val tools=LedgerTools(app,"live-book",evidenceScope=evidence,database=db)
  PiRuntime(app,db).run("live-book","读取证据 $evidence。账目 id=${entry.id}，商户为早餐店。根据证据归入餐饮（唯一可用分类），然后使用 summarize 查询 start=0,end=2000 的净支出。",config.model,ModelClient(config)::request,
   extra=JSONObject().put("tools",tools.definitions).put("system",AnalysisWorker.SYSTEM),toolHandler=tools::execute)
  assertEquals("餐饮",db.book().entry(entry.id)?.category)
  assertEquals(1234L,db.book().total(0,2000))
  val transcript=db.dao().run("live-book")!!.messages
  assertTrue(transcript.contains("summarize"));assertTrue(transcript.contains("1234"))
 }
 @Test fun localOcrNeverReturnsAnUnredactedScreenshot()=runBlocking {
  val bitmap=android.graphics.Bitmap.createBitmap(900,600,android.graphics.Bitmap.Config.ARGB_8888)
  val canvas=android.graphics.Canvas(bitmap);canvas.drawColor(android.graphics.Color.WHITE)
  val paint=android.graphics.Paint().apply {color=android.graphics.Color.BLACK;textSize=48f;isAntiAlias=true}
  canvas.drawText("支付成功",40f,90f,paint);canvas.drawText("金额：12.34",40f,180f,paint)
  canvas.drawText("手机号：13812345678",40f,270f,paint)
  try {
   val result=LocalOcr(app).recognizeReceipt(bitmap)
   assertTrue(result.text.contains("12.34"))
   assertFalse(ReceiptParser.parse(result.text)!!.text.contains("13812345678"))
   if(result.redactedImage!=null) {
    val redacted=android.graphics.BitmapFactory.decodeByteArray(result.redactedImage,0,result.redactedImage.size)
    assertTrue(redacted.height<bitmap.height);redacted.recycle()
   }
  } finally {bitmap.recycle()}
 }
 @Test fun deferredLifecycleAndCancellationDoNotDoubleCount()=runBlocking {
  val p=repo.ingest("pdd","page","pending",1000,ReceiptParser.parse("订单详情\n先用后付\n下单成功\n待扣款金额：28.80\n订单号：PDD123456")!!)!!
  val original=db.book().forEvidence(p).single()
  assertEquals("deferred",original.kind);assertEquals(0L,db.book().total(0,3000))
  repo.ingest("pdd","page","paid",2000,ReceiptParser.parse("订单详情\n先用后付\n扣款成功\n扣款金额：28.80\n订单号：PDD123456")!!)
  assertEquals("expense",db.book().entry(original.id)?.kind);assertEquals(2880L,db.book().total(0,3000))
  repo.ingest("pdd","page","revisit",2100,ReceiptParser.parse("订单详情\n扣款成功\n金额：28.80\n订单号：PDD123456")!!)
  assertEquals(2880L,db.book().total(0,3000))
  val cancelled=repo.ingest("pdd","page","pending2",1000,ReceiptParser.parse("订单详情\n先用后付\n下单成功\n待扣款金额：10.00\n订单号：PDD654321")!!)!!
  repo.ingest("pdd","page","cancelled",2200,ReceiptParser.parse("订单详情\n订单已取消\n订单号：PDD654321")!!)
  assertEquals("cancelled",db.book().entry(db.book().forEvidence(cancelled).single().id)?.kind)
  assertEquals(2880L,db.book().total(0,3000))
 }
 @Test fun amountCorrectionIsAuditedAndUndoRestoresIt()=runBlocking {
  val ev=repo.ingest("jd","page","wrong-old-version",1000,receipt("10.00"))!!
  val id=db.book().forEvidence(ev).single().id
  repo.correct(id,"教育",null,amountMinor=638,merchant="文具店")
  assertEquals(638L,db.book().total(0,2000));assertTrue(db.book().entry(id)!!.userLocked)
  repo.undo(db.book().corrections(id).single().id)
  assertEquals(1000L,db.book().total(0,2000));assertEquals("早餐店",db.book().entry(id)!!.merchant)
 }
 @Test fun twoStrongPaymentIdsForOneOrderRemainSeparate()=runBlocking {
  repo.ingest("jd","page","installment1",1000,ReceiptParser.parse("支付成功\n金额：5.00\n订单号：ORDER55555\n支付单号：PAY111111")!!)
  repo.ingest("jd","page","installment2",1001,ReceiptParser.parse("支付成功\n金额：5.00\n订单号：ORDER55555\n支付单号：PAY222222")!!)
  assertEquals(1000L,db.book().total(0,2000))
 }
 @Test fun liveDiscountedGoodsAndPersistentConversation()=runBlocking {
  val configFile=File(app.filesDir,"probe-config.json");Assume.assumeTrue(configFile.exists())
  val config=ModelConfig.fromJson(configFile.readText())
  listOf("餐饮","购物","教育","生活").forEach {db.book().saveCategory(Category(it))}
  val ev=repo.ingest("jd","page","live-jd",1000,ReceiptParser.parse("订单详情\n支付成功\n商品金额：10.00\n优惠金额：3.62\n实付款：6.38\n商品名称：中性笔\n店铺：青光文具\n订单号：LIVEJD638")!!)!!
  val entry=db.book().forEvidence(ev).single()
  val tools=LedgerTools(app,"live-classify-v02",evidenceScope=ev,database=db)
  PiRuntime(app,db).run("live-classify-v02","读取证据 $ev，根据证据给账目 ${entry.id} 分类，从教育/购物/餐饮/生活中选择。给订单 ${entry.orderId} 保存商品名称和商品分类。实付款638分，商品原价不能当实付价。",config.model,ModelClient(config)::request,extra=JSONObject().put("tools",tools.definitions).put("system",AnalysisWorker.SYSTEM),toolHandler=tools::execute)
  File(app.cacheDir,"live-v02-transcript.json").writeText(db.dao().run("live-classify-v02")!!.messages)
  assertNotNull(db.book().entry(entry.id)!!.category);assertEquals(638L,db.book().entry(entry.id)!!.amountMinor)
  assertTrue(db.book().items(entry.orderId!!).any {it.name=="中性笔"&&it.category!=null})
  val query=LedgerTools(app,"chat:live-v02",userIntent="查账",database=db)
  val runtime=PiRuntime(app,db)
  runtime.run("chat:live-v02","调用 summarize，查询 start=0,end=2000 的支出，以 Markdown 回复金额。",config.model,ModelClient(config)::request,extra=JSONObject().put("tools",query.definitions).put("system",AnalysisWorker.SYSTEM),toolHandler=query::execute)
  runtime.run("chat:live-v02","刚才的支出是多少钱？再用 query_entries 找出那笔账的商户。",config.model,ModelClient(config)::request,extra=JSONObject().put("tools",query.definitions).put("system",AnalysisWorker.SYSTEM),toolHandler=query::execute)
  val messages=JSONArray(db.dao().run("chat:live-v02")!!.messages)
  assertEquals(2,(0 until messages.length()).count {messages.getJSONObject(it).optString("role")=="user"})
  assertTrue(messages.toString().contains("青光文具"));assertTrue(messages.toString().contains("6.38"))
 }
 @Test fun conversationalWritesAreIdempotentAndRequireExplicitAmount()=runBlocking {
  val prompt="记一笔，食堂午饭花了18.50元"
  val tools=LedgerTools(app,"chat:test-create",userIntent=prompt,database=db)
  val args=JSONObject().put("amountMinor",1850).put("merchant","食堂").put("kind","expense").put("quote",prompt).put("category","餐饮")
  tools.execute("create_entry",args,"create1");tools.execute("create_entry",args,"create1")
  assertEquals(1850L,db.book().total(0,Long.MAX_VALUE))
  try{tools.execute("create_entry",JSONObject(args.toString()).put("amountMinor",3000),"create2");fail("Fabricated amount")}catch(_:IllegalArgumentException){}
  assertEquals(1850L,db.book().total(0,Long.MAX_VALUE))
 }
 @Test fun legacyAmountsAreFlaggedAndUnknownAmountsCanBeResolved()=runBlocking {
  val candidate=ReceiptParser.parse("支付成功\n商品金额：10.00\n实付款：6.38\n订单号：LEGACY638")!!
  val ev=repo.ingest("jd","page","legacy",1000,candidate.copy(amountMinor=1000))!!
  repo.flagLegacyAmountConflicts()
  val entry=db.book().forEvidence(ev).single();assertEquals(1000L,entry.amountMinor);assertTrue(entry.reviewReason!!.contains("6.38"))
  val ambiguous=repo.ingest("jd","page","ambiguous",2000,ReceiptParser.parse("支付成功\n实付款：6.38\n实付金额：10.00\n订单号：UNKNOWN638")!!)!!
  assertTrue(db.book().forEvidence(ambiguous).isEmpty())
  val resolved=repo.resolveAmount(db.book().evidence(ambiguous)!!,638,"确认商户")
  assertEquals(638L,db.book().forEvidence(resolved).single().amountMinor)
  assertTrue(db.book().forEvidence(ambiguous).single().userLocked)
 }
 @Test fun namedQueryPeriodsUseDeviceCalendar() {
  val today=java.time.LocalDate.of(2024,3,10);val zone=java.time.ZoneId.of("America/New_York")
  val range=QueryPeriod.resolve(JSONObject().put("period","last_month"),today=today,zone=zone)
  assertEquals(java.time.LocalDate.of(2024,2,1).atStartOfDay(zone).toInstant().toEpochMilli(),range.first)
  assertEquals(java.time.LocalDate.of(2024,3,1).atStartOfDay(zone).toInstant().toEpochMilli(),range.second)
  val day=QueryPeriod.resolve(JSONObject().put("period","today"),today=today,zone=zone)
  assertEquals(23*60*60*1000L,day.second-day.first)
 }
 @Test fun incomeTotalsAndDeferredAccountsUseTheirOwnSemantics()=runBlocking {
  val now=System.currentTimeMillis()
  val ev=repo.ingest("pdd","page","pending-account",now,ReceiptParser.parse("订单详情\n先用后付\n下单成功\n待扣款金额：28.80\n订单号：ACCOUNT1234")!!)!!
  assertEquals(0L,db.book().accountOutflow("payment:pdd",Long.MAX_VALUE))
  val tools=LedgerTools(app,"chat:income",userIntent="记一笔，工资收入了1000元",database=db)
  tools.execute("create_entry",JSONObject().put("amountMinor",100000).put("merchant","工资").put("kind","income").put("quote","记一笔，工资收入了1000元").put("category","工资"),"one")
  val result=JSONObject(tools.execute("summarize",JSONObject().put("period","this_month").put("kind","income").put("category","工资"),"sum"))
  assertEquals(100000L,result.getLong("amountMinor"))
  assertEquals(1,db.book().search(0,Long.MAX_VALUE,"CNY",null,"工资","income",null,null).size)
 }
}
