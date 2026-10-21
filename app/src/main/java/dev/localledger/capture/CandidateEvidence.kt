package dev.localledger.capture

/** Admission protects privacy; it does not decide transaction status, amount or category. */
object CandidateEvidence {
 private val money=Regex("[¥￥]\\s*\\d|\\d[\\d,.]*\\s*元|人民币|CNY|RMB",RegexOption.IGNORE_CASE)
 private val privateField=Regex("收货人|收货地址|收货信息|收件人|收件地址|联系人|联系地址|手机号|手机号码|验证码|密码|银行卡号|身份证|账号|姓名|配送地址|详细地址|尾号|尾数")
 private val address=Regex("(?:省|市|区|县|街道|街|路|小区|大厦|栋|单元|楼|室).{0,12}\\d|\\d+号|\\d+室")
 fun prepare(raw:String,receiptPage:Boolean=false):String? {
  if(raw.length>30_000)return null
  // Notifications must already have passed source/sender isolation. Pages may only
  // reach here after a receipt boundary or a financial-layout boundary is established.
  if(!receiptPage&&!money.containsMatchIn(raw)&&!Regex("先用后付|支付分|交易|扣款|还款").containsMatchIn(raw))return null
  var skipValue=false
  val safe=raw.lines().mapNotNull {line->
   val trimmed=line.trim()
   if(trimmed.isBlank())return@mapNotNull null
   if(skipValue){skipValue=false;return@mapNotNull null}
   if(privateField.containsMatchIn(trimmed)) {
    // Preserve only financial clauses from bank prose containing a masked card suffix.
    if(Regex("尾号|尾数").containsMatchIn(trimmed)) {
     val financial=trimmed.replace(Regex("(?:尾号|尾数)[:：\\s]*[0-9*]{4,}"),"[卡]")
      .replace(Regex("尊敬的[^，,:：\\n]{1,30}[，,:：]"),"")
     return@mapNotNull PrivacyFilter.safeLine(financial)
    }
    if(trimmed.matches(Regex("(?:收货人|收件人|联系人|联系电话|手机号|手机号码|姓名|收货地址|配送地址|详细地址|账号)[:：]?")))skipValue=true
    return@mapNotNull null
   }
   if(trimmed.matches(Regex("[\\u4e00-\\u9fff]{1,6}(?:先生|女士)")))return@mapNotNull null
   if(address.containsMatchIn(trimmed)||Regex("[\\u4e00-\\u9fff]{1,6}[（(](?:先生|女士)[）)]").containsMatchIn(trimmed))return@mapNotNull null
   if(trimmed.matches(Regex("(?:订单号|订单编号|交易号|交易单号|支付单号)[:：]\\s*[A-Za-z0-9_-]{6,80}")))trimmed
   else PrivacyFilter.safeLine(trimmed)?.replace(Regex("(?<![A-Za-z0-9])\\d{12,}(?![A-Za-z0-9])"),"[编号]")
  }.distinct().joinToString("\n").take(6000)
  return safe.takeIf{it.isNotBlank()&&(receiptPage||money.containsMatchIn(it)||it.contains("先用后付"))}
 }
 fun financialPage(texts:List<String>,pkg:String):Boolean {
  if(pkg in PaymentNoticeContent.privateMessageApps)return false // Never turn arbitrary chat messages into candidate pages.
  val raw=texts.joinToString("\n").take(30_000)
  // Currency is only a cheap admission signal. The agent, not a page-title
  // allowlist, decides whether this is a purchase, advertisement or unpaid order.
  return money.containsMatchIn(raw)&&!Regex("搜索结果|聊天|私信|通讯录|发送消息|购物车").containsMatchIn(raw)
 }
}
