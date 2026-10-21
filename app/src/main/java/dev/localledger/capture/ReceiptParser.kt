package dev.localledger.capture

import dev.localledger.ledger.Money
import java.security.MessageDigest

/** Only payment facts are retained. A notification sender or page boundary must be checked first. */
data class ReceiptCandidate(val text:String,val amountMinor:Long?,val merchant:String?,val externalId:String?,val orderId:String?,val kind:String,val confirmed:Boolean,val occurredAt:Long?=null)
object PrivacyFilter {
 private val sensitive=Regex("收货人|收货地址|收货信息|收件人|收件地址|联系人|联系地址|手机号|手机号码|验证码|密码|银行卡号|身份证|账号|姓名|配送地址|详细地址")
 fun safeLine(line:String):String? {
  if(sensitive.containsMatchIn(line)) return null
  return line.replace(Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),"[电话]")
   .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),"[邮箱]")
   .replace(Regex("(?<!\\d)\\d{16,19}(?!\\d)"),"[编号]").take(200)
 }
}
object ReceiptParser {
 private val positive=Regex("支付成功|付款成功|交易成功|已付款|已支付|退款成功|还款成功|充值成功|交易完成|扣款成功")
 private val negative=Regex("待付款|等待付款|付款失败|支付失败|退款申请|退款处理中|还款失败|订单已取消|交易关闭|已取消订单")
 private val deferredStatus=Regex("下单成功|待发货|待收货|待扣款|确认收货后付款|确认收货后扣款|订单已确认|等待商家发货|等待收货|等待买家确认收货|商家已发货")
 private val moneyValue=Regex("^[¥￥]?\\s*([0-9,]+(?:\\.[0-9]{1,2})?)(?:\\s*元)?$")
 private val currencyAmount=Regex("[¥￥]\\s*([0-9,]+(?:\\.[0-9]{1,2})?)(?![0-9.])")
 private val amountLabels=listOf("实付款","实付金额","实际付款","实际支付","实付","支付金额","付款金额","本次支付","合计实付","退款金额","还款金额","充值金额","扣款金额","待扣款金额","待付款金额","合计","应付金额","应付款","订单金额","商品金额","金额")
 private val dataLabels=listOf("商户","商家","店铺","收款方","商户全称","订单状态","商品","商品名称","数量","单价","运费","优惠","优惠金额","手续费","支付方式","付款方式","交易时间","付款时间","支付时间","退款时间","还款时间","订单编号","订单号","商户单号","交易单号","支付单号","交易号","退款单号","退款流水号")
 val pageTitles=setOf("支付成功","付款成功","交易详情","账单详情","订单详情","退款详情","还款成功","充值成功","下单成功","先用后付","付款详情","扣款成功")
 // Fields are anchored to a label. 商品金额 and 优惠金额 must never match 金额.
 private fun values(lines:List<String>,keys:List<String>):List<String> = lines.flatMapIndexed { index,line->
  keys.mapNotNull { key->
   when {
    line==key||line=="$key："||line=="$key:"->lines.getOrNull(index+1)?.let {if(it in setOf("¥","￥")) it+lines.getOrNull(index+2).orEmpty() else it}
    line.startsWith("$key：")||line.startsWith("$key:")->line.substring(key.length+1).trim()
    line.startsWith("$key ")->line.substring(key.length).trim()
    line.startsWith("$key¥")||line.startsWith("$key￥")->line.substring(key.length)
    else->null
   }?.take(160)
  }
 }
 private fun field(lines:List<String>,keys:List<String>)=values(lines,keys).firstOrNull()
 private fun money(value:String):Long?=moneyValue.matchEntire(value.trim())?.groupValues?.get(1)?.let(Money::parse)
 private val notificationFieldBoundary by lazy {
  val labels=(amountLabels+dataLabels).joinToString("|"){Regex.escape(it)}
  Regex("[，；;]\\s*(?=(?:$labels)[:：])")
 }
 fun parseNotification(raw:String):ReceiptCandidate? {
  if(raw.length>30_000)return null
  // Split only before known labelled fields, not at thousands separators or in merchant names.
  val normalized=ReceiptText.notification(raw).replace(notificationFieldBoundary,"\n")
   .lines().joinToString("\n"){it.trim().removeSuffix("。")}
  // A flat summary with several transactions cannot be treated as one payment.
  val lines=normalized.lines()
  if(values(lines,listOf("订单号","订单编号","商户单号")).distinct().size>1 ||
   values(lines,listOf("交易号","交易单号","支付单号")).distinct().size>1)return null
  return parse(normalized)
 }
 fun parse(raw:String):ReceiptCandidate? {
  if(raw.length>30_000)return null
  val lines=ReceiptText.lines(raw)
  val joined=lines.joinToString("\n")
  if(Regex("美元|港币|港元|日元|欧元|USD|HKD|JPY|EUR|\\$").containsMatchIn(joined))return null
  val cancelled=Regex("订单已取消|交易关闭|已取消订单").containsMatchIn(joined)
  val zeroCheckout=values(lines,listOf("实付款","实付金额","支付金额")).any{money(it)==0L}
  val deferred=joined.contains("先用后付")&&!cancelled&&!Regex("扣款成功|已扣款|退款成功|还款成功").containsMatchIn(joined)&&
   ((deferredStatus.containsMatchIn(joined)&&!positive.containsMatchIn(joined))||(zeroCheckout&&values(lines,listOf("待扣款金额","待付款金额","合计","应付金额","订单金额")).any{(money(it)?:0)>0}))
  val actualPayment=values(lines,listOf("实付款","实付金额","实际付款","实际支付","实付","合计实付"))
  val paidAmountOnOrder=lines.any{it in pageTitles} && actualPayment.any{(money(it)?:0L)>0L} &&
   !Regex("先用后付|货到付款|到付|待扣款").containsMatchIn(joined)
  val paid=(positive.containsMatchIn(joined)||paidAmountOnOrder)&&!negative.containsMatchIn(joined)&&!deferred
  if(!paid&&!deferred&&!lines.any {it in pageTitles})return null
  val kind=when {
   cancelled->"cancelled"
   joined.contains("退款成功")->"refund"
   joined.contains("还款成功")->"repayment"
   joined.contains("充值成功")||joined.contains("转账")->"transfer_candidate"
   deferred->"deferred"
   else->"expense"
  }
  val priorities=when(kind) {
   "refund"->listOf(listOf("退款金额"),listOf("金额"))
   "repayment"->listOf(listOf("还款金额","本次还款"),listOf("实付款","实付金额","金额"))
   "deferred"->listOf(listOf("待扣款金额","待付款金额","应付金额","应付款"),listOf("订单金额","合计"),listOf("实付款","实付金额"))
   else->listOf(listOf("实付款","实付金额","实际付款","实际支付","实付","合计实付"),listOf("支付金额","付款金额","本次支付","扣款金额","充值金额"),listOf("金额"))
  }
  var minor:Long?=null;var labeled=false
  for(keys in priorities) {
   val candidates=values(lines,keys)
   if(candidates.isNotEmpty()) { labeled=true;minor=candidates.mapNotNull(::money).distinct().singleOrNull().takeIf {candidates.all {money(it)!=null}};break }
  }
  // A single currency token is safe only when no list price, discount or deferred zero-payment label exists.
  if(!labeled&&kind!="deferred"&&!Regex("商品金额|优惠|原价|单价|运费|0元下单").containsMatchIn(joined))
   minor=currencyAmount.findAll(joined).mapNotNull {Money.parse(it.groupValues[1])}.distinct().toList().singleOrNull()
  if(kind=="deferred"&&minor==0L)minor=null
  val merchant=field(lines,listOf("商户","商家","店铺","收款方","商户全称"))?.let(PrivacyFilter::safeLine)
  val external=field(lines,if(kind=="refund")listOf("退款单号","退款流水号") else listOf("交易单号","支付单号","交易号"))?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{6,80}"))}
  val order=field(lines,listOf("商户单号","订单编号","订单号"))?.takeIf {it.matches(Regex("[A-Za-z0-9_-]{6,80}"))}
  val selected=mutableListOf<String>()
  // Keep the inferred state grounded in explicit actual-payment fields across re-parsing.
  if(paid&&paidAmountOnOrder&&!positive.containsMatchIn(joined))selected.add("支付成功")
  if(deferred)selected.add("订单已确认")
  if(!paid&&!deferred)lines.firstOrNull{it in pageTitles}?.let{selected.add(it)}
  for(line in lines) {
   (positive.find(line)?:negative.find(line)?:deferredStatus.find(line))?.value?.let {selected.add(it)}
  }
  // Preserve item/quantity/price adjacency. Grouping by label would mix different products.
  for((index,line) in lines.withIndex()) for(label in amountLabels+dataLabels) {
   if(line==label||line.startsWith("$label：")||line.startsWith("$label:")||line.startsWith("$label ")||line.startsWith("$label¥")||line.startsWith("$label￥")) {
    field(lines.drop(index).take(3),listOf(label))?.let(PrivacyFilter::safeLine)?.let{selected.add("$label：$it")}
   }
  }
  if(minor!=null)selected.add("识别金额：${Money.display(minor)}元")
  if(joined.contains("先用后付"))selected.add("付款方式：先用后付")
  if(joined.contains("美团月付"))selected.add("付款方式：美团月付")
  if(joined.contains("校园卡"))selected.add(if(joined.contains("本人校园卡"))"账户类型：本人校园卡" else "账户类型：校园卡")
  if(selected.isEmpty())return null
  val dateValue=field(lines,listOf("退款时间","还款时间","交易时间","付款时间","支付时间"))
  val occurred=dateValue?.let {value->runCatching {
   val cleaned=value.replace('/','-').replace('年','-').replace('月','-').replace("日","").trim()
   java.time.LocalDateTime.parse(cleaned,java.time.format.DateTimeFormatter.ofPattern(if(cleaned.length==16)"yyyy-MM-dd HH:mm" else "yyyy-MM-dd HH:mm:ss")).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
  }.getOrNull()}
  return ReceiptCandidate(selected.distinct().joinToString("\n").take(8000),minor,merchant,external,order,kind,paid||deferred||cancelled,occurred)
 }
 fun identityFingerprint(candidate:ReceiptCandidate)=fingerprint(candidate.text+"\u0000"+candidate.orderId.orEmpty()+"\u0000"+candidate.externalId.orEmpty())
 fun fingerprint(text:String):String=MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString(""){"%02x".format(it)}
}
