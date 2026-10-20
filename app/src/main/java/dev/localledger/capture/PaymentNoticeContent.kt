package dev.localledger.capture

data class NoticeMessage(val sender:String?,val text:String,val time:Long)
data class NoticeEnvelope(
 val title:String="",val bigTitle:String="",val text:String="",val bigText:String="",
 val lines:List<String> = emptyList(),val messages:List<NoticeMessage> = emptyList(),val summary:Boolean=false
)
data class ReceiptNotice(val text:String,val time:Long,val message:Boolean=false)

/** Select payment messages before any parsing/persistence; never join different senders. */
object PaymentNoticeContent {
 private val senderPattern=Regex("^(?:\\[\\d+条(?:新消息)?\\]\\s*)?(微信支付|微信支付凭证|支付通知)(?:\\s*[（(]\\d+条(?:新消息)?[）)])?$")
 fun isPaymentSender(value:String?)=value!=null&&value.length<=80&&senderPattern.matches(value.trim())
 private fun prefixed(text:String):String? {
  val index=text.indexOfFirst{it==':'||it=='：'}
  if(index<0||!isPaymentSender(text.substring(0,index)))return null
  return text.substring(index+1).trim()
 }
 fun extract(pkg:String,envelope:NoticeEnvelope,time:Long):List<ReceiptNotice> {
  val chat=pkg=="com.tencent.mm"
  if(envelope.messages.isNotEmpty()) {
   return envelope.messages.takeLast(100).filter{!chat||isPaymentSender(it.sender)}.map {
    ReceiptNotice(it.text,it.time.takeIf{t->t>0}?:time,true)
   }.filter {notice->
    // A summary and its child may contain the same transaction. Process summaries
    // only when a strong transaction/order identifier permits native deduplication.
    !envelope.summary || ReceiptParser.parseNotification(notice.text)?.let{it.orderId!=null||it.externalId!=null}==true
   }
  }
  if(envelope.summary)return emptyList()
  val trustedTitle=isPaymentSender(envelope.title)||isPaymentSender(envelope.bigTitle)
  val bodies=if(envelope.lines.isNotEmpty())envelope.lines else listOf(envelope.bigText.ifBlank{envelope.text})
  return bodies.flatMap {body->
   when {
    !chat->listOf(ReceiptNotice(envelope.title+"\n"+body,time))
    trustedTitle->listOf(ReceiptNotice(prefixed(body)?:body,time))
    // Generic app titles can carry multiple senders. Only a self-contained line
    // with an explicit payment sender is selected; never append adjacent chat text.
    else->body.lines().mapNotNull{prefixed(it)?.let{receipt->ReceiptNotice(receipt,time)}}
   }
  }.filter{it.text.isNotBlank()&&it.text.length<=30_000}
 }
}
