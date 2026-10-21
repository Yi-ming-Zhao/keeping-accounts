package dev.localledger.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.SharedPreferences
import dev.localledger.LedgerApplication
import kotlinx.coroutines.launch

class PaymentNotificationService:NotificationListenerService(),SharedPreferences.OnSharedPreferenceChangeListener {
 private val app get()=application as LedgerApplication
 override fun onListenerConnected() {
  app.captureDiagnostics.notificationConnected(true)
  app.settings.preferences.registerOnSharedPreferenceChangeListener(this)
  replayVisibleNotifications()
 }
 private fun replayVisibleNotifications() {
  // Android can reconnect after process death. Only currently visible notifications
  // are available; this cannot recover a notification the user already dismissed.
  runCatching{activeNotifications?.forEach(::onNotificationPosted)}
 }
 override fun onSharedPreferenceChanged(prefs:SharedPreferences?,key:String?) {
  if(key=="allowed_packages")replayVisibleNotifications()
 }
 override fun onListenerDisconnected() {
  app.captureDiagnostics.notificationConnected(false)
  app.settings.preferences.unregisterOnSharedPreferenceChangeListener(this)
  requestRebind(android.content.ComponentName(this,PaymentNotificationService::class.java))
 }
 override fun onDestroy() {
  app.captureDiagnostics.notificationConnected(false)
  app.settings.preferences.unregisterOnSharedPreferenceChangeListener(this)
  super.onDestroy()
 }
 override fun onNotificationPosted(sbn:StatusBarNotification) {
  if(sbn.packageName !in app.settings.allowedPackages()) {
   if(sbn.packageName in dev.localledger.AppSettings.financialApplications)
    app.captureDiagnostics.record(sbn.packageName,"notification",CaptureResult.NOT_AUTHORIZED)
   return
  }
  val stableTime=sbn.notification.`when`.takeIf { it>0 }?:sbn.postTime
  val notices=runCatching {PaymentNoticeContent.extract(sbn.packageName,AndroidNoticeReader.read(sbn.notification),stableTime)}
   .getOrElse {app.captureDiagnostics.record(sbn.packageName,"notification",CaptureResult.NO_FIELDS);return}
  if(notices.isEmpty()) {app.captureDiagnostics.record(sbn.packageName,"notification",CaptureResult.NOT_PAYMENT_NOTICE);return}
  val occurrences=mutableMapOf<String,Int>()
  val selected=notices.mapNotNull {notice->
   val candidate=ReceiptParser.parseNotification(notice.text)
   val safe=if(candidate==null)CandidateEvidence.prepare(notice.text)?:return@mapNotNull null else null
   val identity="${if(notice.message) "message:" else ""}${notice.time}:${candidate?.let(ReceiptParser::identityFingerprint)?:ReceiptParser.fingerprint(safe!!)}"
   val occurrence=occurrences.getOrDefault(identity,0);occurrences[identity]=occurrence+1
   val key="notification:${sbn.packageName}:${sbn.key}:$identity"+if(occurrence==0) "" else ":$occurrence"
   notice to key
  }
  if(selected.isEmpty()) {app.captureDiagnostics.record(sbn.packageName,"notification",CaptureResult.NO_FIELDS);return}
  app.scope.launch {
   selected.forEach {(notice,key)->
    try {CaptureCoordinator(app).accept(sbn.packageName,"notification",key,notice.text,notice.time)}
    catch(e:kotlinx.coroutines.CancellationException){throw e}
    catch(_:Exception){app.captureDiagnostics.record(sbn.packageName,"notification",CaptureResult.FAILED)}
   }
  }
 }
}
