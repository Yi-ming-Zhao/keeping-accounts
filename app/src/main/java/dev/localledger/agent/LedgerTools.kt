package dev.localledger.agent

import androidx.room.withTransaction
import dev.localledger.LedgerApplication
import dev.localledger.capture.PrivacyFilter
import dev.localledger.capture.ReceiptParser
import dev.localledger.data.*
import dev.localledger.ledger.*
import org.json.*
import java.util.UUID

class LedgerTools(private val app:LedgerApplication,private val session:String,private val evidenceScope:String?=null,private val userIntent:String?=null,private val database:LedgerDatabase=app.database) {
 private val db=database;private val book=db.book()
 val definitions:JSONArray get()=JSONArray().apply {
  put(tool("query_entries","按条件查询账目，金额单位为分，最多返回50条。时间优先用 period: today/this_month/last_month/last_7_days/all，或 month: YYYY-MM，由手机计算。",mapOf("period" to string(),"month" to string(),"start" to integer(),"end" to integer(),"merchant" to string(),"category" to string(),"kind" to string(),"product" to string(),"sourcePackage" to string(),"offset" to integer()),emptyList()))
  put(tool("summarize","数据库计算期间净支出。时间优先用 period: today/this_month/last_month/last_7_days/all，或 month: YYYY-MM；不要心算毫秒。不重复计算还款或充值。指定 product 时仅统计有完整价格分配的匹配商品实付款，返回未知订单数；不扣减无法分配到商品的退款，不得称为商品净支出。",mapOf("period" to string(),"month" to string(),"start" to integer(),"end" to integer(),"category" to string(),"merchant" to string(),"sourcePackage" to string(),"kind" to string(),"product" to string()),emptyList()))
  put(tool("account_changes","查询已记录的账户净变动；负债账户正值为新增欠款，不含未知期初余额。",mapOf("end" to integer()),emptyList()))
  put(tool("read_entry","读取一笔账的商品和证据。",mapOf("id" to string()),listOf("id")))
  put(tool("read_evidence","读取指定交易的脱敏证据。",mapOf("id" to string()),listOf("id")))
  if(evidenceScope!=null) {
   put(tool("recognize_transaction","识别当前证据并提交交易事实。kind: expense已实际付款/deferred先用后付待扣款/refund已退款/repayment已还款/transfer_candidate转账充值/unpaid未支付/not_transaction非交易/uncertain不确定。必须给 statusQuote 原文；入账还需 amountMinor(分)、amountQuote 原文、currency=CNY。金额未知省略，绝不猜测或用原价/余额/优惠替代。merchant/orderId/paymentId 仅填原文已有的值。每条证据最多一笔，多笔混杂无法隔离时 uncertain。工具返回账目和订单ID供后续分类。",mapOf("kind" to string(),"statusQuote" to string(),"amountMinor" to integer(),"amountQuote" to string(),"currency" to string(),"merchant" to string(),"orderId" to string(),"paymentId" to string()),listOf("kind","statusQuote")))
   put(tool("classify_entry","根据证据设置分类；不覆盖用户修正。证据不足时不要调用。",mapOf("id" to string(),"category" to string(),"quote" to string()),listOf("id","category","quote")))
   put(tool("attach_items","保存证据中明确出现的商品，不能补造数量或价格。",mapOf("orderId" to string(),"items" to JSONObject().put("type","array").put("maxItems",50).put("items",JSONObject().put("type","object").put("properties",JSONObject().put("name",string()).put("quote",string()).put("category",string()).put("quantity",string()).put("unitPriceMinor",integer()).put("totalMinor",integer())).put("required",JSONArray(listOf("name","quote"))).put("additionalProperties",false))),listOf("orderId","items")))
   put(tool("link_refund","将退款与原支出关联，须有共同订单号证据。",mapOf("refundId" to string(),"originalId" to string()),listOf("refundId","originalId")))
  }
  if(userIntent!=null && canCreate(userIntent))
   put(tool("create_entry","按用户明确陈述记一笔。只接受用户实际写出的金额；不能把查询当作记账。amountMinor 单位为分。",mapOf("amountMinor" to integer(),"merchant" to string(),"category" to string(),"note" to string(),"kind" to string(),"quote" to string()),listOf("amountMinor","kind","quote")))
  if(userIntent!=null && Regex("改|归类|分类|记为|备注|以后").containsMatchIn(userIntent))
   put(tool("correct_entry","按用户指令纠正分类或备注。只有用户明确说以后都这样时才能 remember=true。",mapOf("id" to string(),"category" to string(),"note" to string(),"amountMinor" to integer(),"merchant" to string(),"remember" to JSONObject().put("type","boolean")),listOf("id","category")))
 }
 suspend fun execute(name:String,args:JSONObject,callId:String):String = db.withTransaction {
  if(evidenceScope!=null&&db===app.database) {
   val source=book.evidence(evidenceScope)?:error("证据不存在")
   require(source.sourcePackage in setOf("manual","shared")||source.channel=="share"||source.sourcePackage in app.settings.allowedPackages()) {"来源授权已撤销"}
  }
  if(userIntent!=null)app.conversation.progress(session,name)
  val receiptKey=ReceiptParser.fingerprint(name+args.toString()+callId)
  db.dao().receipt(session,receiptKey)?.let { return@withTransaction it.result }
  val result=when(name) {
   "query_entries" -> {
    val (start,end)=QueryPeriod.resolve(args)
    val rows=book.search(start,end,"CNY",args.nullable("merchant"),args.nullable("category"),args.nullable("kind"),args.nullable("sourcePackage"),args.nullable("product"),50,args.optInt("offset",0).coerceIn(0,100_000))
    JSONObject().put("entries",JSONArray(rows.map { safeEntry(it) })).put("nextOffset",if(rows.size==50) args.optInt("offset",0)+50 else JSONObject.NULL)
   }
   "summarize" -> {
    val (start,end)=QueryPeriod.resolve(args,"this_month")
    val product=args.nullable("product")
    if(product!=null) {
     require(args.nullable("kind")==null||args.nullable("kind")=="expense") { "商品退款缺少分配，请查询退款流水" }
     JSONObject().put("amountMinor",book.productTotal(start,end,product,args.nullable("category"),args.nullable("merchant"),args.nullable("sourcePackage")))
      .put("unknownOrders",book.productUnknownOrders(start,end,product,args.nullable("merchant"),args.nullable("sourcePackage"))).put("metric","known_item_paid_amount").put("refundsAllocated",false).put("currency","CNY").put("start",start).put("end",end)
    } else {
     val summary=book.periodSummary(start,end,args.nullable("merchant"),args.nullable("sourcePackage"))
     val amount=if(args.nullable("kind") in setOf("income","refund","deferred","repayment","transfer"))book.kindTotal(start,end,args.getString("kind"),args.nullable("category"),args.nullable("merchant"),args.nullable("sourcePackage")) else book.total(start,end,category=args.nullable("category"),merchant=args.nullable("merchant"),source=args.nullable("sourcePackage"),kind=args.nullable("kind"))
     JSONObject().put("amountMinor",amount).put("metric",args.nullable("kind")?:"net_expense").put("currency","CNY").put("start",start).put("end",end).apply {
      if(args.nullable("category")==null&&args.nullable("kind")==null) {
       put("expenseMinor",summary.expenses);put("refundMinor",summary.refunds);put("incomeMinor",summary.income);put("deferredMinor",summary.deferred);put("entryCount",summary.count)
      }
     }
    }
   }
   "account_changes" -> JSONObject().put("accounts",JSONArray(book.accounts().map {JSONObject().put("id",it.id).put("name",it.name).put("kind",it.kind).put("netOutflowMinor",book.accountOutflow(it.id,args.optLong("end",Long.MAX_VALUE)))})).put("openingBalanceKnown",false)
   "read_entry" -> {
    val entry=book.entry(args.getString("id"))?:error("账目不存在")
    safeEntry(entry).put("items",JSONArray((book.allocations(entry.id).map {it.orderId}+listOfNotNull(entry.orderId)).distinct().flatMap {book.items(it)}.distinctBy {it.id}.let { goods->goods.map { JSONObject().put("name",it.name).put("quantity",it.quantity?:JSONObject.NULL).put("unitPriceMinor",it.unitPriceMinor?:JSONObject.NULL).put("totalMinor",it.totalMinor?:JSONObject.NULL).put("category",it.category?:JSONObject.NULL) } }?:emptyList<JSONObject>()))
   }
   "read_evidence" -> {
    val id=args.getString("id");require(evidenceScope==null||id==evidenceScope)
    val ev=book.evidence(id)?:error("证据不存在")
    JSONObject().put("id",id).put("sourcePackage",ev.sourcePackage).put("text",ev.text)
   }
   "recognize_transaction" -> RecognitionTool(db).apply(evidenceScope?:error("没有交易证据范围"),args)
   "classify_entry" -> {
    val ev=book.evidence(evidenceScope?:error("不允许自动修改"))?:error("证据不存在")
    val entry=book.entry(args.getString("id"))?:error("账目不存在")
    require(book.forEvidence(ev.id).any { it.id==entry.id });val quote=args.getString("quote");require(quote.length>=2&&containsQuote(ev.text,quote)) {"quote 必须是证据里的原文片段，请直接引用商品名或商户名"}
    val category=args.getString("category");require(book.categories().any { it.name==category }) {"请使用已有分类："+book.categories().joinToString {it.name}}
    if(!entry.userLocked) {
     book.updateEntry(entry.copy(category=category,reviewReason=entry.reviewReason?.takeUnless { it=="待分类"||it=="待补商品" },revision=entry.revision+1))
     book.saveAudit(Audit(UUID.randomUUID().toString(),entry.id,"classification",JSONObject().put("category",category).put("evidenceId",ev.id).put("quote",quote).toString()))
    }
    JSONObject().put("updated",!entry.userLocked)
   }
   "attach_items" -> {
    val ev=book.evidence(evidenceScope?:error("无证据范围"))?:error("证据不存在")
    val order=book.order(args.getString("orderId"))?:error("订单不存在")
    require(order.evidenceId==ev.id || book.forEvidence(ev.id).any { it.orderId==order.id })
    val items=args.getJSONArray("items");require(items.length() in 1..50)
    val prepared=(0 until items.length()).map { index->
     val item=items.getJSONObject(index);val label=item.getString("name");val quote=item.getString("quote")
     require(label.length in 1..120&&quote.contains(label)&&containsQuote(ev.text,quote)) {"商品名称和 quote 必须出现在原始证据中"}
     fun price(key:String):Long? = if(item.has(key)&&!item.isNull(key)) item.getLong(key).also { require(it>=0&&quote.contains(Money.display(it))) {"$key 缺少同一商品的价格证据。请省略价格字段，只保存商品名称和分类；订单实付款不能自动分摊。"} } else null
     val quantity=item.nullable("quantity")?.also { require(it.matches(Regex("[0-9]+(\\.[0-9]+)?"))&&(quote.contains("×$it")||quote.contains("数量：$it")||quote.contains("数量 $it"))) }
     val category=item.nullable("category")?.also { require(book.categories().any { c->c.name==it }) }
     OrderItem(ReceiptParser.fingerprint("${order.id}:$label"),order.id,label,quantity,price("unitPriceMinor"),price("totalMinor"),category,ev.id)
    }
    val combined=book.items(order.id).associateBy {it.id}.toMutableMap().apply {prepared.forEach {put(it.id,it)}}
    if(order.totalMinor!=null) require(combined.values.sumOf { it.totalMinor?:0 }<=order.totalMinor) {"商品实付价合计超过订单金额；原价不能当实付价。请省略没有分摊证据的 totalMinor"}
    prepared.forEach { book.saveItem(it) }
    book.forOrder(order.id).filter { !it.userLocked&&it.category!=null }.forEach { book.updateEntry(it.copy(reviewReason=it.reviewReason?.takeUnless{reason->reason=="待补商品"||reason=="待分类"},revision=it.revision+1)) }
    JSONObject().put("saved",prepared.size)
   }
   "link_refund" -> {
    val refund=book.entry(args.getString("refundId"))?:error("退款不存在")
    val original=book.entry(args.getString("originalId"))?:error("原付款不存在")
    require(refund.evidenceId==evidenceScope&&refund.kind=="refund"&&original.kind in setOf("expense","fee"))
    require(book.refunded(original.id,refund.id)+refund.amountMinor<=original.amountMinor)
    require(refund.currency==original.currency&&!refund.userLocked&&refund.amountMinor<=original.amountMinor)
    val refundOrder=refund.orderId?.let { book.order(it) };val originalOrder=original.orderId?.let { book.order(it) }
    require(refundOrder?.externalId!=null&&refundOrder.externalId==originalOrder?.externalId)
    book.updateEntry(refund.copy(originalEntryId=original.id,toAccountId=original.fromAccountId,category=original.category,reviewReason=null,revision=refund.revision+1))
    JSONObject().put("linked",true)
   }
   "create_entry" -> {
    require(userIntent!=null&&canCreate(userIntent))
    val quote=args.getString("quote");require(userIntent.contains(quote))
    val amount=args.getLong("amountMinor");require(amount in 1..999999999999L)
    require(Regex("(?<![0-9.])[0-9]+(?:\\.[0-9]{1,2})?(?![0-9.])").findAll(quote).any {Money.parse(it.value)==amount})
    val kind=args.getString("kind");require(kind in setOf("expense","income"))
    val merchant=args.nullable("merchant")?.takeIf{it.isNotBlank()}
    require(merchant==null||(merchant.length<=120&&PrivacyFilter.safeLine(merchant)==merchant&&userIntent.contains(merchant))) {"商户必须出现在用户原话里；未知时省略 merchant"}
    val category=args.nullable("category");require(category==null||category.length in 1..30)
    val id="manual:${ReceiptParser.fingerprint(session+receiptKey)}"
    val now=System.currentTimeMillis()
    book.insertEvidence(Evidence(id,id,"manual","conversation",now,PrivacyFilter.safeLine(quote)?:"用户记账"))
    val entry=LedgerEntry(id,id,kind,amount,merchant=merchant,occurredAt=now,sourcePackage="manual",category=category,note=args.nullable("note")?.also{require(it.length<=200)},evidenceId=id,userLocked=true)
    book.insertEntry(entry);book.linkEvidence(EntryEvidence(id,id))
    if(category!=null)book.saveCategory(Category(category))
    book.saveAudit(Audit(UUID.randomUUID().toString(),id,"user_create","{}"))
    safeEntry(entry)
   }
   "correct_entry" -> {
    require(userIntent!=null&&Regex("改|归类|分类|记为|备注|以后").containsMatchIn(userIntent))
    if(args.has("amountMinor"))require(Regex("(?<![0-9.])[0-9]+(?:\\.[0-9]{1,2})?(?![0-9.])").findAll(userIntent).any{Money.parse(it.value)==args.getLong("amountMinor")}) {"修改金额必须在用户指令中明确给出"}
    val remember=args.optBoolean("remember",false)
    require(!remember||Regex("以后|今后|总是").containsMatchIn(userIntent))
    LedgerRepository(db,book).correct(args.getString("id"),args.getString("category"),args.nullable("note"),remember=remember,amountMinor=if(args.has("amountMinor"))args.getLong("amountMinor") else null,merchant=args.nullable("merchant"))
    JSONObject().put("updated",true)
   }
   else->error("未授权工具")
  }.toString()
  db.dao().saveReceipt(ToolReceipt(session,receiptKey,result));result
 }
 private fun safeEntry(entry:LedgerEntry):JSONObject = LedgerRepository.entryJson(entry)
  .put("note",entry.note?.let(PrivacyFilter::safeLine)?:JSONObject.NULL)
  .put("merchant",entry.merchant?.let(PrivacyFilter::safeLine)?:JSONObject.NULL)
  .put("category",entry.category?.let(PrivacyFilter::safeLine)?:JSONObject.NULL)
 companion object {
  private fun canCreate(text:String)=Regex("记一笔|记账|帮我记|记录|新增").containsMatchIn(text)||(!Regex("[？?]|多少|有没有|是否|了吗").containsMatchIn(text)&&Regex("花了|付了|买了|收入了|收到").containsMatchIn(text))
  private fun containsQuote(text:String,quote:String)=text.replace(Regex("\\s+")," ").contains(quote.replace(Regex("\\s+")," "))
  private fun string()=JSONObject().put("type","string").put("maxLength",200)
  private fun integer()=JSONObject().put("type","integer").put("minimum",0)
  private fun tool(name:String,description:String,props:Map<String,JSONObject>,required:List<String>)=JSONObject().put("name",name).put("description",description).put("parameters",JSONObject().put("type","object").put("properties",JSONObject(props)).put("required",JSONArray(required)).put("additionalProperties",false))
 }
}
