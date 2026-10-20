package dev.localledger.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

enum class CaptureResult(val label:String) {
 NO_WINDOW("页面暂不可读"), PRIVATE_PAGE("已跳过输入或密码页面"),
 NOT_RECEIPT("未发现订单或付款页面"), NO_FIELDS("未识别到交易字段"),
 SAVED("交易证据已保存"), NEED_AMOUNT("已保存，待确认金额"),
 UNCONFIRMED("已保存，尚未确认付款"), DUPLICATE("相同证据已保存"),
 FAILED("保存失败，请重新打开页面"), NOT_PAYMENT_NOTICE("未识别到支付通知来源"),
 IMAGE_DENIED("系统未允许截图"), OCR_FAILED("本机图片识别失败")
}
data class CaptureObservation(val pkg:String,val channel:String,val result:CaptureResult,val time:Long)
data class CaptureHealth(val pageConnected:Boolean=false,val notificationConnected:Boolean=false)

/** Bounded local metadata only. Never accepts receipt text, identifiers, amounts or exception messages. */
class CaptureDiagnostics(context:Context) {
 private val prefs=context.getSharedPreferences("capture_health",Context.MODE_PRIVATE)
 private val mutableHealth=MutableStateFlow(CaptureHealth())
 val health=mutableHealth.asStateFlow()
 private val mutableObservations=MutableStateFlow(read())
 val observations=mutableObservations.asStateFlow()
 @Synchronized fun pageConnected(value:Boolean){mutableHealth.value=mutableHealth.value.copy(pageConnected=value)}
 @Synchronized fun notificationConnected(value:Boolean){mutableHealth.value=mutableHealth.value.copy(notificationConnected=value)}
 @Synchronized fun record(pkg:String,channel:String,result:CaptureResult) {
  val now=System.currentTimeMillis()
  val old=mutableObservations.value
  val last=old.firstOrNull{it.pkg==pkg&&it.channel==channel}
  if(last?.result==result&&now-last.time<30_000)return
  val next=(listOf(CaptureObservation(pkg,channel,result,now))+old.filterNot{it.pkg==pkg&&it.channel==channel}).take(32)
  mutableObservations.value=next
  val json=org.json.JSONArray()
  next.forEach {json.put(JSONObject().put("pkg",it.pkg).put("channel",it.channel).put("result",it.result.name).put("time",it.time))}
  prefs.edit().putString("observations",json.toString()).apply()
 }
 private fun read():List<CaptureObservation> = runCatching {
  val json=org.json.JSONArray(prefs.getString("observations","[]"))
  (0 until minOf(json.length(),32)).map {i->json.getJSONObject(i).let{CaptureObservation(it.getString("pkg"),it.getString("channel"),CaptureResult.valueOf(it.getString("result")),it.getLong("time"))}}
 }.getOrDefault(emptyList())
}
