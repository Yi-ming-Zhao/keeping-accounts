package dev.localledger.capture

/** Local normalization only. Original notification prose never becomes model input. */
object ReceiptText {
 private val money="([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
 private val bankAmount=Regex("(?:消费|支出|支付)(?:金额)?[:：\\s]*(?:人民币|RMB|CNY|[¥￥])\\s*$money(?:元)?",RegexOption.IGNORE_CASE)
 private val bankSource=Regex("招商银行|掌上生活|尾号|尾数")
 private val rejectedBank=Regex("失败|未成功|撤销|冲正|退货|退款|退回|预授权|冻结|解冻|验证码|美元|港币|港元|日元|欧元|USD|HKD|JPY|EUR|满[0-9]|每满|最高|返现|优惠活动|赢好礼|消费提醒设置")
 private val useNotice=Regex("先用后付使用通知|先用后付订单通知|有一笔先用后付|成功使用.{0,15}先用后付|先用后付.{0,10}下单成功")
 fun notification(raw:String):String {
  if(raw.length>30_000)return raw
  if(bankSource.containsMatchIn(raw)&&!rejectedBank.containsMatchIn(raw)) {
   val amounts=bankAmount.findAll(raw).map{it.groupValues[1]}.toList()
   if(amounts.size==1) {
    // Restrict output to the debit. Do not persist card suffix, available credit or balance.
    val merchant=Regex("(?:商户|商户名称)[:：]([^，；;。\\n]{1,100})").find(raw)?.groupValues?.get(1)
      ?:Regex("(?:在|于)([^，；;。\\n]{1,60}?)(?:消费|支出|支付人民币)").find(raw)?.groupValues?.get(1)
    return buildString {
     append("支付成功\n付款金额：${amounts.single()}\n付款方式：银行卡")
     merchant?.let(PrivacyFilter::safeLine)?.let{append("\n商户：$it")}
    }
   }
  }
  if(useNotice.containsMatchIn(raw)&&!Regex("取消|失败|攻略|开通|申请|广告|退款|已扣款|扣款成功").containsMatchIn(raw))
   return "先用后付\n订单已确认\n"+raw
  return raw
 }
 fun lines(raw:String):List<String> {
  val source=raw.replace('\u00a0',' ').replace('\u200b', ' ').lines().map{it.trim()}.filter{it.isNotEmpty()}
  val result=mutableListOf<String>()
  var i=0
  while(i<source.size) {
   var line=source[i++]
   // Price typography often exposes currency, integer and fractional parts as separate nodes.
   if(line in setOf("¥","￥")&&i<source.size&&source[i].matches(Regex("[0-9,]+(?:\\.[0-9]{1,2})?")))line+=source[i++]
   if(line.matches(Regex("[¥￥]?[0-9,]+"))&&i<source.size&&source[i].matches(Regex("\\.[0-9]{1,2}")))line+=source[i++]
   result.add(line)
  }
  return result
 }
}
