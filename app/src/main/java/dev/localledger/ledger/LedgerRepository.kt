package dev.localledger.ledger

import androidx.room.withTransaction
import dev.localledger.capture.*
import dev.localledger.data.*
import org.json.JSONObject
import java.util.UUID

class LedgerRepository(private val database:LedgerDatabase,private val book:BookDao) {
 suspend fun ingest(sourcePackage:String,channel:String,sourceKey:String,capturedAt:Long,candidate:ReceiptCandidate):String? = database.withTransaction {
  if(book.evidenceBySource(sourceKey)!=null) return@withTransaction null
  val evidenceId=UUID.randomUUID().toString()
  book.insertEvidence(Evidence(evidenceId,sourceKey,sourcePackage,channel,capturedAt,candidate.text))
  var orderId:String?=null
  if(candidate.orderId!=null || candidate.text.contains("商品") || Regex("运费|优惠|手续费").containsMatchIn(candidate.text)) {
   orderId="order:${ReceiptParser.fingerprint(sourcePackage+":"+(candidate.orderId?:sourceKey))}"
   val old=book.order(orderId)
   book.saveOrder(old?.copy(evidenceId=evidenceId,totalMinor=if(candidate.kind=="refund")old.totalMinor else candidate.amountMinor?:old.totalMinor,state=if(candidate.confirmed) "paid" else old.state)?:OrderRecord(orderId,sourcePackage,candidate.orderId,candidate.merchant,if(candidate.kind=="refund")null else candidate.amountMinor,if(candidate.confirmed) "paid" else "unconfirmed",evidenceId))
  }
  if(orderId!=null) for((label,kind) in listOf("运费" to "shipping","优惠" to "discount","手续费" to "fee")) {
   val value=Regex("(?:^|\\n)$label[:：]\\s*([0-9]+(?:\\.[0-9]{1,2})?)").find(candidate.text)?.groupValues?.get(1)?.let(Money::parse)
   if(value!=null)book.saveCharge(OrderCharge(orderId,kind,value,evidenceId))
  }
  // Cancellation of an unpaid deferred order must not create cash flow.
  if(candidate.kind=="cancelled" && orderId!=null) {
   book.forOrder(orderId).filter {it.kind=="deferred"&&!it.userLocked}.forEach {
    book.updateEntry(it.copy(kind="cancelled",reviewReason=null,revision=it.revision+1))
    book.linkEvidence(EntryEvidence(it.id,evidenceId))
   }
  }
  if(candidate.confirmed && candidate.amountMinor!=null && candidate.kind!="cancelled") {
   // A strong ID is scoped to its provider. Different source events without a common ID remain separate.
   val identity=if(candidate.externalId!=null) "${candidate.kind}:$sourcePackage:${candidate.externalId}" else "event:$sourceKey"
   val exact=book.byIdentity(identity)
   val crossSource=if(exact==null&&candidate.orderId!=null&&candidate.kind=="expense") {
    book.ordersByExternalId(candidate.orderId).filter {it.sourcePackage!=sourcePackage}.flatMap {book.forOrder(it.id)}
     .filter {it.kind==candidate.kind&&it.amountMinor==candidate.amountMinor&&it.currency=="CNY"}.distinctBy {it.id}.singleOrNull()
   } else null
   val sameOrder=if(orderId!=null&&candidate.kind in setOf("expense","deferred")) book.forOrder(orderId).filter {it.kind in setOf("expense","deferred") && (candidate.externalId==null||it.identityKey.startsWith("event:")||it.kind=="deferred")}.singleOrNull() else null
   val existing=exact?:sameOrder?:crossSource
   val repaymentFee=if(candidate.kind=="repayment")Regex("(?:^|\\n)手续费[:：]\\s*([0-9]+(?:\\.[0-9]{1,2})?)").find(candidate.text)?.groupValues?.get(1)?.let(Money::parse)?.takeIf {it<candidate.amountMinor}?:0L else 0L
   val principal=candidate.amountMinor-repaymentFee
   if(existing!=null) {
    book.linkEvidence(EntryEvidence(existing.id,evidenceId))
    if(orderId!=null)book.saveAllocation(PaymentAllocation(existing.id,orderId,candidate.amountMinor,evidenceId))
    val promoted=existing.kind=="deferred"&&candidate.kind=="expense"
    val updated=existing.copy(orderId=existing.orderId?:orderId,merchant=existing.merchant?:candidate.merchant,
     kind=if(promoted&&!existing.userLocked) "expense" else existing.kind,
     occurredAt=if(promoted&&!existing.userLocked)candidate.occurredAt?:capturedAt else existing.occurredAt,
     amountMinor=if(promoted&&!existing.userLocked)candidate.amountMinor else existing.amountMinor,
     reviewReason=if(promoted) "待分类" else existing.reviewReason,revision=existing.revision+1)
    book.updateEntry(updated)
    if(promoted)book.saveAudit(Audit(UUID.randomUUID().toString(),existing.id,"deferred_paid",JSONObject().put("evidenceId",evidenceId).toString()))
   }
   if(existing==null) {
    val id=UUID.randomUUID().toString()
    val credit=candidate.text.contains("美团月付")
    val method=if(credit) "meituan-credit" else "payment:$sourcePackage"
    book.saveAccount(Account("payment:$sourcePackage",sourcePackage,"payment"))
    book.saveAccount(Account(method,if(method=="meituan-credit") "美团月付" else sourcePackage,if(method=="meituan-credit") "liability" else "payment"))
    val campus=candidate.kind=="transfer_candidate"&&candidate.text.contains("本人校园卡")
    if(campus) book.saveAccount(Account("campus","校园卡","stored_value"))
    val kind=if(campus) "transfer" else candidate.kind
    val category=book.rules().firstOrNull { it.merchantEquals==candidate.merchant }?.category
    val review=when(kind) {
     "deferred"->"待扣款"
     "refund"->"待关联原付款"
     "transfer_candidate"->"待确认用途"
     "repayment"->if(credit) null else "待确认还款账户"
     "transfer"->null
     else->if(category==null) "待分类" else "待补商品"
    }
    val entry=LedgerEntry(id,identity,kind,principal,merchant=candidate.merchant,occurredAt=candidate.occurredAt?:capturedAt,
     sourcePackage=sourcePackage,orderId=orderId,fromAccountId=if(kind=="refund") null else if(kind=="repayment"&&credit) "payment:$sourcePackage" else method,toAccountId=if(campus) "campus" else if(kind=="repayment"&&credit) "meituan-credit" else null,
     category=category,evidenceId=evidenceId,reviewReason=review)
    book.insertEntry(entry)
    book.linkEvidence(EntryEvidence(id,evidenceId))
    if(repaymentFee>0) {
     val feeId=UUID.randomUUID().toString()
     book.insertEntry(LedgerEntry(feeId,"$identity:fee","fee",repaymentFee,merchant=candidate.merchant,occurredAt=candidate.occurredAt?:capturedAt,sourcePackage=sourcePackage,fromAccountId="payment:$sourcePackage",evidenceId=evidenceId,reviewReason="待分类"))
     book.linkEvidence(EntryEvidence(feeId,evidenceId))
     book.saveAudit(Audit(UUID.randomUUID().toString(),feeId,"capture",JSONObject().put("evidenceId",evidenceId).toString()))
    }
    if(orderId!=null) book.saveAllocation(PaymentAllocation(id,orderId,candidate.amountMinor,evidenceId))
    book.saveAudit(Audit(UUID.randomUUID().toString(),id,"capture",JSONObject().put("evidenceId",evidenceId).toString()))
   } else if(existing.amountMinor!=principal && !(existing.kind=="deferred"&&candidate.kind=="expense")) {
    book.updateEntry(existing.copy(reviewReason="金额证据冲突",revision=existing.revision+1))
    book.saveAudit(Audit(UUID.randomUUID().toString(),existing.id,"conflict",JSONObject().put("evidenceId",evidenceId).toString()))
   }
  }
  // Product names from explicit page fields survive even when the model is offline.
  if(orderId!=null) {
   Regex("(?:^|\\n)商品名称[:：]([^\\n]+)").findAll(candidate.text).forEach {match->
    val name=match.groupValues[1].trim().take(120)
    if(name.isNotBlank()) {
     val itemId=ReceiptParser.fingerprint("$orderId:$name")
     if(book.items(orderId).none {it.id==itemId})book.saveItem(OrderItem(itemId,orderId,name,null,null,null,null,evidenceId))
    }
   }
  }
  book.saveJob(LedgerJob(evidenceId,evidenceId))
  evidenceId
 }
 suspend fun resolveAmount(evidence:Evidence,amount:Long,merchant:String?,confirmedKind:String?=null):String = database.withTransaction {
  val candidate=if(confirmedKind!=null) {
   require(confirmedKind in setOf("expense","deferred","refund","repayment","transfer_candidate"))
   ReceiptCandidate(evidence.text,amount,merchant,null,null,confirmedKind,true)
  } else ReceiptParser.parse(evidence.text)?:error("交易状态不明确")
  require(candidate.confirmed&&candidate.kind!="cancelled"&&amount>0)
  require(book.forEvidence(evidence.id).isEmpty()) {"这条证据已有账目"}
  val id=ingest(evidence.sourcePackage,"share","resolved:${evidence.id}",evidence.capturedAt,candidate.copy(amountMinor=amount,merchant=merchant?:candidate.merchant))?:error("已处理")
  val entry=book.forEvidence(id).single()
  book.linkEvidence(EntryEvidence(entry.id,evidence.id))
  correct(entry.id,entry.category,entry.note)
  id
 }
 suspend fun flagLegacyAmountConflicts() {
  var offset=0
  while(true) {
   val rows=book.search(0,Long.MAX_VALUE,"CNY",null,null,null,null,null,200,offset)
   for(entry in rows.filter{!it.userLocked&&it.kind in setOf("expense","refund","repayment")}) {
    val amounts=book.evidenceForEntry(entry.id).mapNotNull{ReceiptParser.parse(it.text)}.filter{it.confirmed&&it.kind==entry.kind}.mapNotNull{it.amountMinor}.distinct()
    if(amounts.size==1&&amounts.single()!=entry.amountMinor) {
     val reason="实付证据为 ${Money.display(amounts.single())} 元"
     if(entry.reviewReason!=reason)database.withTransaction {
      book.updateEntry(entry.copy(reviewReason=reason,revision=entry.revision+1))
      book.saveAudit(Audit(UUID.randomUUID().toString(),entry.id,"conflict","{\"source\":\"parser_v2\"}"))
     }
    }
   }
   if(rows.size<200)break
   offset+=200
  }
 }
 suspend fun correct(id:String,category:String?,note:String?,kind:String?=null,remember:Boolean=false,amountMinor:Long?=null,merchant:String?=null) = database.withTransaction {
  val entry=book.entry(id)?:error("账目不存在")
  require(category==null||category.length in 1..30)
  require(note==null||note.length<=200)
  require(amountMinor==null||amountMinor in 1..999999999999L)
  require(merchant==null||merchant.length in 1..120)
  require(kind==null||kind in setOf("expense","income","refund","fee","transfer","repayment","transfer_candidate","deferred","cancelled"))
  if(category!=null) book.saveCategory(Category(category))
  val changed=entry.copy(amountMinor=amountMinor?:entry.amountMinor,merchant=merchant?:entry.merchant,category=category,note=note,kind=kind?:entry.kind,userLocked=true,reviewReason=null,revision=entry.revision+1)
  book.updateEntry(changed)
  book.saveCorrection(Correction(UUID.randomUUID().toString(),id,entryJson(entry).toString(),entryJson(changed).toString()))
  book.saveAudit(Audit(UUID.randomUUID().toString(),id,"user_correction","{}"))
  if(remember) {
   require(!entry.merchant.isNullOrBlank()&&!category.isNullOrBlank())
   book.saveRule(CategoryRule(ReceiptParser.fingerprint(entry.merchant!!),entry.merchant,category!!))
  }
 }
 suspend fun undo(correctionId:String) = database.withTransaction {
  val change=book.correction(correctionId)?:error("修改记录不存在")
  require(!change.undone)
  val entry=book.entry(change.entryId)?:error("账目不存在")
  val before=JSONObject(change.beforeJson); val after=JSONObject(change.afterJson)
  require(entry.revision==after.getInt("revision")) { "这笔账已再次修改，不能直接撤销" }
  book.updateEntry(entry.copy(amountMinor=before.optLong("amountMinor",entry.amountMinor),merchant=before.nullable("merchant"),category=before.nullable("category"),note=before.nullable("note"),kind=before.getString("kind"),
   userLocked=before.getBoolean("userLocked"),reviewReason=before.nullable("reviewReason"),revision=entry.revision+1))
  book.markUndone(correctionId)
  book.saveAudit(Audit(UUID.randomUUID().toString(),entry.id,"undo",JSONObject().put("correctionId",correctionId).toString()))
 }
 companion object {
  fun entryJson(e:LedgerEntry)=JSONObject().put("id",e.id).put("amountMinor",e.amountMinor).put("currency",e.currency)
   .put("merchant",e.merchant?:JSONObject.NULL).put("kind",e.kind).put("occurredAt",e.occurredAt).put("category",e.category?:JSONObject.NULL)
   .put("note",e.note?:JSONObject.NULL).put("evidenceId",e.evidenceId).put("orderId",e.orderId?:JSONObject.NULL)
   .put("reviewReason",e.reviewReason?:JSONObject.NULL).put("userLocked",e.userLocked).put("revision",e.revision)
 }
}
fun JSONObject.nullable(key:String):String? = if(!has(key)||isNull(key)) null else getString(key)
