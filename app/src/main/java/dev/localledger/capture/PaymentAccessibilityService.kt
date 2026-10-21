package dev.localledger.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 private var screenshotRetries=0
 private val debounce=CaptureDebounce()
 private var lastWindow:Int?=null
 private var lastPackage:String?=null
 private var rootRetries=0
 private val inFlight=mutableSetOf<String>()
 private val app get()=application as LedgerApplication
 private val scan=Runnable { debounce.reset();inspectPage() }
 override fun onServiceConnected() {
  app.settings.preferences.registerOnSharedPreferenceChangeListener(this)
  configurePackages()
  app.captureDiagnostics.pageConnected(true)
  handler.postDelayed(scan,700)
 }
 override fun onSharedPreferenceChanged(prefs:SharedPreferences?,key:String?) { if(key=="allowed_packages") handler.post {configurePackages();handler.removeCallbacks(scan);handler.postDelayed(scan,700)} }
 private fun configurePackages() {
  val current=serviceInfo?:return
  current.packageNames=app.settings.allowedPackages().ifEmpty { setOf(packageName) }.toTypedArray()
  current.eventTypes=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED
  current.flags=current.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
  current.notificationTimeout=400
  serviceInfo=current
 }
 override fun onAccessibilityEvent(event:AccessibilityEvent?) {
  val pkg=event?.packageName?.toString()?:return
  if(pkg !in app.settings.allowedPackages()) return
  if(lastWindow!=event.windowId || lastPackage!=pkg) {
   session=UUID.randomUUID().toString();previous=null;debounce.reset();lastScreenshotAt=0L;screenshotRetries=0
   lastWindow=event.windowId;lastPackage=pkg
  }
  rootRetries=0
  handler.removeCallbacks(scan);handler.postDelayed(scan,debounce.delay(SystemClock.uptimeMillis()))
 }
 private fun inspectPage() {
  val root=rootInActiveWindow?:run {
   lastPackage?.takeIf{it in app.settings.allowedPackages()}?.let{app.captureDiagnostics.record(it,"page",CaptureResult.NO_WINDOW)}
   if(rootRetries++<3)handler.postDelayed(scan,250)
   return
  }
  val pkg=root.packageName?.toString()?:return
  if(pkg !in app.settings.allowedPackages()) return
  val texts=ArrayList<String>();var password=false;var editable=false;var visited=0
  fun visit(node:AccessibilityNodeInfo,depth:Int) {
   if(++visited>1200||depth>35)return
   if(node.isPassword){if(node.isVisibleToUser)password=true;return}
   if(Regex("address|consignee|receiver|recipient|phone|mobile|contact",RegexOption.IGNORE_CASE).containsMatchIn(node.viewIdResourceName.orEmpty().substringAfter(":id/")))return
   // A search box outside a receipt must not discard the entire order. Its subtree
   // is excluded. Screenshots remain disabled for any page containing inputs.
   if(node.isEditable){if(node.isVisibleToUser)editable=true;return}
   val value=node.text?.toString()?.takeIf{it.isNotBlank()}?:node.contentDescription?.toString()?.takeIf{it.isNotBlank()}
   value?.takeIf{node.isVisibleToUser}?.let {
    val id=node.viewIdResourceName.orEmpty().substringAfter(":id/")
    val label=when {
     Regex("(^|_)(goods|product|ware|sku)_(name|title)($|_)").containsMatchIn(id)->"商品名称："
     Regex("(^|_)(shop|store|merchant)_(name|title)($|_)").containsMatchIn(id)->"店铺："
     else->""
    }
    texts.add(label+it.take(8000))
   }
   for(i in 0 until node.childCount) node.getChild(i)?.let { visit(it,depth+1) }
  }
  visit(root,0)
  if(password || (editable&&pkg in PaymentNoticeContent.privateMessageApps)) { previous=null;app.captureDiagnostics.record(pkg,"page",CaptureResult.PRIVATE_PAGE);return }
  val raw=texts.distinct().joinToString("\n").take(30_000)
  // WeChat shares its process with chats. Require receipt/order page structure, never scan arbitrary chat bodies.
  val receiptPage=PageReceiptBoundary.isReceipt(texts,pkg)||(!editable&&CandidateEvidence.financialPage(texts,pkg))
  val imageOnly=app.settings.screenshots&&android.os.Build.VERSION.SDK_INT>=34&&!editable&&pkg !in PaymentNoticeContent.privateMessageApps&&texts.size<3
  if(!receiptPage&&!imageOnly) { previous=null;session=UUID.randomUUID().toString();app.captureDiagnostics.record(pkg,"page",CaptureResult.NOT_RECEIPT);return }
  val candidate=ReceiptParser.parse(raw)
  val fingerprint=candidate?.let(ReceiptParser::identityFingerprint)?:ReceiptParser.fingerprint(raw)
  val capturedSession=session
  val attempt="$pkg:$capturedSession:$fingerprint"
  if(previous==fingerprint || attempt in inFlight)return
  inFlight.add(attempt)
  val capture=app.scope.async {
   try {
    if(!receiptPage)return@async null
    CaptureCoordinator(app).accept(pkg,"page","page:$pkg:$capturedSession:$fingerprint",raw,receiptPage=true).also {
     handler.post {if(session==capturedSession)previous=fingerprint}
    }
   } finally {handler.post{inFlight.remove(attempt)}}
  }
  if(!editable && android.os.Build.VERSION.SDK_INT>=34 && app.settings.screenshots && (candidate?.amountMinor==null || !candidate.text.contains("商品")) && System.currentTimeMillis()-lastScreenshotAt>15_000) {
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
      } catch(e:CancellationException){throw e}
      catch(_:Exception){app.captureDiagnostics.record(pkg,"ocr",CaptureResult.OCR_FAILED)}
      finally { bitmap.recycle() }
     }
    }
    override fun onFailure(errorCode:Int) {
     app.captureDiagnostics.record(pkg,"ocr",when(errorCode){
      ERROR_TAKE_SCREENSHOT_SECURE_WINDOW->CaptureResult.IMAGE_SECURE
      ERROR_TAKE_SCREENSHOT_INVALID_WINDOW->CaptureResult.IMAGE_WINDOW_NOT_READY
      ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT->CaptureResult.IMAGE_RATE_LIMITED
      else->CaptureResult.IMAGE_DENIED
     })
     if(imageOnly&&errorCode in setOf(ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,ERROR_TAKE_SCREENSHOT_INVALID_WINDOW)&&session==capturedSession&&screenshotRetries++<2) {
      lastScreenshotAt=0;handler.removeCallbacks(scan);handler.postDelayed(scan,1000)
     }
    }
   })
  }
 }
 override fun onInterrupt() { handler.removeCallbacks(scan) }
 override fun onUnbind(intent:android.content.Intent?):Boolean {app.captureDiagnostics.pageConnected(false);return super.onUnbind(intent)}
 override fun onDestroy() { app.captureDiagnostics.pageConnected(false);handler.removeCallbacks(scan);app.settings.preferences.unregisterOnSharedPreferenceChangeListener(this);super.onDestroy() }
}
