package dev.localledger.agent

import dev.localledger.capture.*
import dev.localledger.data.*
import dev.localledger.ledger.*
import org.json.*
import java.util.UUID

/** Called inside LedgerTools' transaction. The model chooses meaning; native code checks provenance. */
class RecognitionTool(private val db:LedgerDatabase) {
 suspend fun apply(evidenceId:String,args:JSONObject):JSONObject {
  val book=db.book()
  val ev=book.evidence(evidenceId)?:error("证据不存在")
  val existing=book.forEvidence(ev.id)
  if(existing.isNotEmpty())return result(ev.id)
  val kind=args.getString("kind")
  require(kind in setOf("expense","deferred","refund","repayment","transfer_candidate","unpaid","not_transaction","uncertain"))
  val quote=args.getString("statusQuote")
  require(quote.length in 2..200&&ev.text.contains(quote)) {"状态必须引用证据原文"}
  val job=book.job(ev.id)?:error("识别任务不存在")
  if(kind in setOf("unpaid","not_transaction","uncertain")) {
   book.saveJob(job.copy(state=if(kind=="not_transaction")"ignored" else "needs_review",error=when(kind){"unpaid"->"尚未付款";"uncertain"->"交易状态待确认";else->null},updatedAt=System.currentTimeMillis()))
   book.saveAudit(Audit(UUID.randomUUID().toString(),null,"recognition",JSONObject().put("evidenceId",ev.id).put("kind",kind).put("quote",quote).toString()))
   return JSONObject().put("state",kind)
  }
  if(!args.has("amountMinor")||args.isNull("amountMinor")) {
   book.saveJob(job.copy(state="needs_review",error="金额待确认",updatedAt=System.currentTimeMillis()))
   return JSONObject().put("state","needs_review")
  }
  require(args.optString("currency","CNY")=="CNY") {"当前仅自动处理人民币"}
  require(!Regex("USD|HKD|JPY|EUR|美元|港币|港元|日元|欧元|\\$").containsMatchIn(ev.text)) {"外币需手动确认"}
  val amount=args.getLong("amountMinor")
  val amountQuote=args.getString("amountQuote")
  require(amount in 1..999999999999L&&amountQuote.length in 1..200&&ev.text.contains(amountQuote)) {"金额必须有原文证据"}
  require(Regex("(?<![0-9.,])[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?(?![0-9.,])").findAll(amountQuote).any {Money.parse(it.value)==amount}) {"金额与引用数值不一致"}
  // Reject known contradictions; unknown wording remains the agent's semantic task.
  require(!Regex("失败|交易关闭|订单已取消|已取消订单|未付款|待付款|等待付款|预授权|冻结").containsMatchIn(quote)||kind=="deferred") {"未完成付款不能记为现金支出"}
  require(kind!="expense"||!Regex("先用后付|货到付款").containsMatchIn(quote)||Regex("扣款成功|已扣款").containsMatchIn(quote)) {"先用后付未扣款时应记为 deferred"}
  val parsed=ReceiptParser.parse(ev.text)
  if(parsed?.confirmed==true&&parsed.amountMinor!=null)require(parsed.amountMinor==amount&&parsed.kind==kind) {"与已确定的付款证据冲突"}
  fun quoted(key:String,max:Int):String?=args.nullable(key)?.takeIf{it.isNotBlank()}?.also {
   require(it.length<=max&&ev.text.contains(it)&&PrivacyFilter.safeLine(it)==it) {"$key 必须来自原始证据"}
  }
  val merchant=quoted("merchant",120)
  val order=quoted("orderId",80)?.also{require(it.matches(Regex("[A-Za-z0-9_-]{6,80}")))}
  val external=quoted("paymentId",80)?.also{require(it.matches(Regex("[A-Za-z0-9_-]{6,80}")))}
  val candidate=ReceiptCandidate(ev.text,amount,merchant,external,order,kind,true)
  val generated=LedgerRepository(db,book).ingest(ev.sourcePackage,ev.channel,"recognized:${ev.id}",ev.capturedAt,candidate)
  if(generated!=null) {
   book.forEvidence(generated).forEach{book.linkEvidence(EntryEvidence(it.id,ev.id))}
   // The current run continues classification; do not schedule a second model loop.
   book.job(generated)?.let{book.saveJob(it.copy(state="complete"))}
  }
  book.saveAudit(Audit(UUID.randomUUID().toString(),book.forEvidence(ev.id).firstOrNull()?.id,"recognition",JSONObject().put("evidenceId",ev.id).put("kind",kind).put("amountQuote",amountQuote).put("statusQuote",quote).toString()))
  return result(ev.id)
 }
 private suspend fun result(id:String):JSONObject {
  val entries=db.book().forEvidence(id)
  return JSONObject().put("entries",JSONArray(entries.map{LedgerRepository.entryJson(it)}))
   .put("orders",JSONArray(entries.mapNotNull{it.orderId}.distinct().map{JSONObject().put("id",it)}))
 }
}
