package dev.localledger.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.localledger.LedgerApplication
import kotlinx.coroutines.*
import java.util.UUID

class PaymentAccessibilityService:AccessibilityService(),SharedPreferences.OnSharedPreferenceChangeListener {
 private val handler=Handler(Looper.getMainLooper())
 private var session=UUID.randomUUID().toString()
 private var previous:String?=null
 private var lastScreenshotAt=0L
 private val app get()=application as LedgerApplication
 private val scan=Runnable { inspectPage() }
 override fun onServiceConnected() {
  app.settings.preferences.registerOnSharedPreferenceChangeListener(this)
  configurePackages()
 }
 override fun onSharedPreferenceChanged(prefs:SharedPreferences?,key:String?) { if(key=="allowed_packages") configurePackages() }
 private fun configurePackages() {
  val current=serviceInfo?:return
  current.packageNames=app.settings.allowedPackages().ifEmpty { setOf(packageName) }.toTypedArray()
  current.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
  current.flags=current.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
  current.notificationTimeout=400
  serviceInfo=current
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {
  val pkg=event?.packageName?.toString()?:return
  if(pkg !in app.settings.allowedPackages()) return
  if(event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { session=UUID.randomUUID().toString();previous=null }
  handler.removeCallbacks(scan);handler.postDelayed(scan,700)
 }
 private fun inspectPage() {
  val root=rootInActiveWindow?:return
  val pkg=root.packageName?.toString()?:return
  if(pkg !in app.settings.allowedPackages()) return
  val texts=ArrayList<String>();var unsafe=false;var visited=0
  fun visit(node:AccessibilityNodeInfo,depth:Int) {
   if(++visited>600||depth>25||!node.isVisibleToUser)return
   if(node.isPassword||node.isEditable) unsafe=true
   val value=node.text?.toString()?.takeIf{it.isNotBlank()}?:node.contentDescription?.toString()?.takeIf{it.isNotBlank()}
   value?.let {
    val id=node.viewIdResourceName.orEmpty().substringAfter(":id/")
    val label=when {
     Regex("(^|_)(goods|product|ware|sku)_(name|title)($|_)").containsMatchIn(id)->"商品名称："
     Regex("(^|_)(shop|store|merchant)_(name|title)($|_)").containsMatchIn(id)->"店铺："
     else->""
    }
    texts.add(label+it.take(500))
   }
   for(i in 0 until node.childCount) node.getChild(i)?.let { visit(it,depth+1) }
  }
  visit(root,0)
  if(unsafe) { previous=null;return }
  val raw=texts.joinToString("\n")
  // WeChat shares its process with chats. Require receipt/order page structure, never scan arbitrary chat bodies.
  val receiptPage=texts.any { it in ReceiptParser.pageTitles }
  if(!receiptPage) { previous=null;session=UUID.randomUUID().toString();return }
  val candidate=ReceiptParser.parse(raw)
  val fingerprint=candidate?.let(ReceiptParser::identityFingerprint)?:ReceiptParser.fingerprint(raw)
  if(previous==fingerprint)return
  val capturedSession=session
  val capture=app.scope.async { CaptureCoordinator(app).accept(pkg,"page","page:$pkg:$capturedSession:$fingerprint",raw).also { previous=fingerprint } }
  if(android.os.Build.VERSION.SDK_INT>=34 && app.settings.screenshots && (candidate?.amountMinor==null || !candidate.text.contains("商品")) && System.currentTimeMillis()-lastScreenshotAt>15_000) {
   lastScreenshotAt=System.currentTimeMillis()
   takeScreenshotOfWindow(root.windowId,mainExecutor,object:TakeScreenshotCallback {
    override fun onSuccess(result:ScreenshotResult) {
     val hardware=Bitmap.wrapHardwareBuffer(result.hardwareBuffer,result.colorSpace)
     val bitmap=hardware?.copy(Bitmap.Config.ARGB_8888,false)
     hardware?.recycle();result.hardwareBuffer.close()
     if(bitmap==null)return
     // A screenshot can complete after the user switches apps. Discard unless the same page is still foreground.
     if(rootInActiveWindow?.packageName?.toString()!=pkg || session!=capturedSession){bitmap.recycle();return}
     app.scope.launch {
      try {
       val recognized=LocalOcr(app).recognizeReceipt(bitmap)
       val id=capture.await()
       if(id!=null&&candidate?.amountMinor!=null) CaptureCoordinator(app).enhance(id,recognized)
       else {
        val newId=CaptureCoordinator(app).accept(pkg,"ocr","ocr:$pkg:$capturedSession:${ReceiptParser.fingerprint(recognized.text)}",recognized.text)
        if(newId!=null)CaptureCoordinator(app).enhance(newId,recognized)
       }
      } finally { bitmap.recycle() }
     }
    }
    override fun onFailure(errorCode:Int) { /* Secure windows and system denials are respected. */ }
   })
  }
 }
 override fun onInterrupt() { handler.removeCallbacks(scan) }
 override fun onDestroy() { handler.removeCallbacks(scan);app.settings.preferences.unregisterOnSharedPreferenceChangeListener(this);super.onDestroy() }
}
