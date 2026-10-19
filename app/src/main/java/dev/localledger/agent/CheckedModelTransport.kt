package dev.localledger.agent

import dev.localledger.ledger.Money
import org.json.*

/** Reject unsupported monetary claims instead of displaying a guessed total. */
class CheckedModelTransport(private val send:suspend(String)->String) {
 private var requests=0
 private suspend fun requestOnce(body:String):String {check(++requests<=6){"达到六轮模型交互上限"};return send(body)}
 suspend fun request(body:String):String {
  val response=requestOnce(body)
  val unsupported=unsupportedAmounts(body,response)
  if(unsupported.isEmpty())return response
  val input=JSONObject(body);val messages=input.getJSONArray("messages")
  val content=JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
  messages.put(JSONObject().put("role","assistant").put("content",content))
  messages.put(JSONObject().put("role","user").put("content","金额校验发现以下数字没有工具结果支持：${unsupported.joinToString{Money.display(it)}} 元。请重新调用统计工具或纠正回答，只引用工具实际返回的金额；不要自行口算、编造分项，也不要向用户解释此次校验过程。"))
  val corrected=requestOnce(input.toString())
  check(unsupportedAmounts(body,corrected).isEmpty()){ "回答中的金额与账本不一致，请重试" }
  return corrected
 }
 companion object {
  fun unsupportedAmounts(request:String,response:String):Set<Long> {
   val allowed=mutableSetOf<Long>()
   fun collect(value:Any?) {
    when(value) {
     is JSONObject->value.keys().forEach {key->
      val child=value.opt(key)
      if(key.endsWith("Minor")&&child is Number)allowed.add(child.toLong())
      else collect(child)
     }
     is JSONArray->for(i in 0 until value.length())collect(value.opt(i))
    }
   }
   val messages=JSONObject(request).getJSONArray("messages")
   for(i in 0 until messages.length()) {
    val message=messages.getJSONObject(i)
    if(message.optString("role")=="tool")runCatching {collect(JSONObject(message.getString("content")))}
   }
   if(allowed.isEmpty())return emptySet()
   val message=JSONObject(response).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?:return emptySet()
   if(message.optJSONArray("tool_calls")?.length()?.let{it>0}==true)return emptySet()
   val text=message.optString("content")
   val money=Regex("[¥￥]\\s*(-?[0-9,]+(?:\\.[0-9]{1,2})?)|(?<![0-9.])(-?[0-9,]+(?:\\.[0-9]{1,2})?)\\s*元")
   return money.findAll(text).mapNotNull {match->
    val raw=match.groupValues[1].ifBlank{match.groupValues[2]}
    Money.parse(raw.removePrefix("-"))?.let{if(raw.startsWith('-'))-it else it}
   }.filter{it !in allowed}.toSet()
  }
 }
}
