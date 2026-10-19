package dev.localledger.ledger

import java.math.BigDecimal
import java.math.RoundingMode

object Money {
 fun parse(text:String):Long? = try {
  val clean=text.trim().replace(",", "").replace("¥", "").replace("￥", "").removeSuffix("元").trim()
  if(!clean.matches(Regex("[0-9]{1,12}(\\.[0-9]{1,2})?"))) null
  else BigDecimal(clean).movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact()
 } catch(_:Exception) { null }
 fun display(minor:Long):String = BigDecimal.valueOf(minor,2).setScale(2).toPlainString()
 fun expenseImpact(kind:String,amount:Long):Long = when(kind) {
  "expense","fee" -> amount
  "refund" -> -amount
  else -> 0
 }
}
