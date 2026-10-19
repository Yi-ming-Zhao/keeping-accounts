package dev.localledger.capture

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.localledger.LedgerApplication
import kotlinx.coroutines.launch

class PaymentNotificationService:NotificationListenerService() {
 override fun onNotificationPosted(sbn:StatusBarNotification) {
  val app=application as LedgerApplication
  if(sbn.packageName !in app.settings.allowedPackages()) return
  if(sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
  val extras=sbn.notification.extras
  val title=extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
  if(sbn.packageName=="com.tencent.mm" && title !in setOf("微信支付","微信支付凭证","支付通知")) return
  val body=extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
   ?:extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
  val raw="$title\n$body"
  val candidate=ReceiptParser.parse(raw)?:return
  val stableTime=sbn.notification.`when`.takeIf { it>0 }?:sbn.postTime
  val key="notification:${sbn.packageName}:${sbn.key}:$stableTime:${ReceiptParser.identityFingerprint(candidate)}"
  app.scope.launch { CaptureCoordinator(app).accept(sbn.packageName,"notification",key,raw,stableTime) }
 }
}
