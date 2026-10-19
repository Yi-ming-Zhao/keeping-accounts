package dev.localledger.capture

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrReceipt(val text:String,val redactedImage:ByteArray?)
class LocalOcr(private val context:Context) {
 suspend fun recognize(original:Bitmap):String = recognizeReceipt(original).text
 suspend fun recognizeReceipt(original:Bitmap):OcrReceipt = mutex.withLock {
  withTimeout(30_000) {
   val directory=File(context.noBackupFilesDir,"ocr")
   val data=File(directory,"tessdata").apply{mkdirs()}
   for(language in listOf("chi_sim","eng")) {
    val target=File(data,"$language.traineddata")
    if(!target.exists()) context.assets.open("tessdata/$language.traineddata").use { input->target.outputStream().use { input.copyTo(it) } }
   }
   val scaled=if(original.width>1600) Bitmap.createScaledBitmap(original,1600,(original.height*1600L/original.width).toInt(),true) else original.copy(Bitmap.Config.ARGB_8888,false)
   suspendCancellableCoroutine { continuation ->
     val api=TessBaseAPI()
     val lifecycle=Any();var closed=false
     continuation.invokeOnCancellation { synchronized(lifecycle) {if(!closed)runCatching { api.stop() }} }
     executor.execute {
      try {
       check(api.init(directory.absolutePath,"chi_sim+eng")) { "本地识别模型未就绪" }
       api.setImage(scaled)
       val text=api.utF8Text.orEmpty().replace(Regex("(?<=[\\u4e00-\\u9fff]) +(?=[\\u4e00-\\u9fff])"),"")
       val redacted=redactImage(api,scaled,text)
       if(continuation.isActive) continuation.resume(OcrReceipt(text,redacted))
      } catch(e:Exception) { if(continuation.isActive) continuation.resumeWithException(IllegalStateException("本地文字识别失败")) }
      finally { synchronized(lifecycle) {closed=true;api.recycle()}; scaled.recycle() }
     }
   }
  }
 }
 private fun redactImage(api:TessBaseAPI,bitmap:Bitmap,text:String):ByteArray? {
  if(ReceiptParser.parse(text)==null)return null
  val regions=mutableListOf<android.graphics.Rect>()
  val iterator=api.resultIterator?:return null
  try {
   iterator.begin()
   do {
    val line=iterator.getUTF8Text(2)?.trim().orEmpty().replace(Regex("(?<=[\\u4e00-\\u9fff]) +(?=[\\u4e00-\\u9fff])"),"")
    // Only payment amount/status regions may leave the device. Product, person and address pixels stay masked.
    val safe=line.matches(Regex("(?:支付成功|付款成功|退款成功|还款成功|充值成功|(?:实付款|实付金额|支付金额|退款金额|还款金额|金额)?[:：\\s]*[¥￥]?\\s*[0-9,]+(?:\\.[0-9]{1,2})?元?)"))
    if(safe&&PrivacyFilter.safeLine(line)==line&&iterator.confidence(2)>=90) {
     val rect=iterator.getBoundingRect(2)
     if(rect.intersect(0,0,bitmap.width,bitmap.height)&&rect.width()>0&&rect.height()>0)regions.add(rect)
    }
   } while(iterator.next(2))
  } finally {iterator.delete()}
  if(regions.isEmpty())return null
  val width=regions.maxOf {it.width()}.coerceAtMost(1600)
  val height=regions.sumOf {it.height()+12}.coerceAtMost(4000)
  val output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
  val canvas=android.graphics.Canvas(output);canvas.drawColor(android.graphics.Color.WHITE)
  var y=0
  for(r in regions) {if(y+r.height()>height)break;canvas.drawBitmap(bitmap,r,android.graphics.Rect(0,y,r.width().coerceAtMost(width),y+r.height()),null);y+=r.height()+12}
  return try {java.io.ByteArrayOutputStream().also {output.compress(Bitmap.CompressFormat.PNG,100,it)}.toByteArray()} finally {output.recycle()}
 }
 companion object { private val mutex=Mutex();private val executor=Executors.newSingleThreadExecutor() }
}
