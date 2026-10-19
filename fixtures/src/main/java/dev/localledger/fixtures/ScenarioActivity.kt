package dev.localledger.fixtures

import android.app.*
import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.graphics.Color

/** Separate test APK. No payments, network, accounts or third-party apps are involved. */
class ScenarioActivity:Activity() {
 override fun onCreate(state:Bundle?) {super.onCreate(state);render(intent)}
 override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);render(intent)}
 private fun render(intent:Intent) {
  val text=intent.getStringExtra("receipt")?:"测试交易"
  val layout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(32,70,32,32);setBackgroundColor(Color.WHITE)}
  text.lines().forEach {line->layout.addView(TextView(this).apply {this.text=line;textSize=18f;setTextColor(Color.BLACK);setPadding(0,5,0,5)})}
  setContentView(ScrollView(this).apply {addView(layout)})
  if(intent.getBooleanExtra("notification",false)) {
   val manager=getSystemService(NotificationManager::class.java)
   manager.createNotificationChannel(NotificationChannel("payments","交易",NotificationManager.IMPORTANCE_DEFAULT))
   manager.notify(intent.getIntExtra("notificationId",1),Notification.Builder(this,"payments").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("支付通知").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).build())
  }
 }
}
