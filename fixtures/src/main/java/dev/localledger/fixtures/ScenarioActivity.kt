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
  if(intent.getBooleanExtra("passwordField",false)) layout.addView(EditText(this).apply {inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;setText("private-password")})
  if(intent.getBooleanExtra("searchField",false)) layout.addView(EditText(this).apply {hint="搜索订单";setText("不应采集的输入内容")})
  (if(intent.getBooleanExtra("combinedNode",false)) listOf(text) else text.lines()).forEach {line->layout.addView(TextView(this).apply {this.text=line;textSize=18f;setTextColor(Color.BLACK);setPadding(0,5,0,5)})}
  if(intent.getBooleanExtra("canvasReceipt",false))setContentView(object:android.view.View(this) {
   private val ink=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=52f}
   override fun onDraw(canvas:android.graphics.Canvas){super.onDraw(canvas);canvas.drawColor(Color.WHITE);text.lines().forEachIndexed{i,line->canvas.drawText(line,70f,180f+i*90f,ink)}}
  })else setContentView(ScrollView(this).apply {addView(layout)})
  if(intent.getBooleanExtra("churn",false)) {
   val ticker=TextView(this);layout.addView(ticker)
   val handler=android.os.Handler(mainLooper)
   var count=0
   val tick=object:Runnable {override fun run(){ticker.text="刷新 ${count++}";layout.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);if(count<100)handler.postDelayed(this,150)}}
   handler.post(tick)
  }
  if(intent.getBooleanExtra("notification",false)) {
   val notificationText=intent.getStringExtra("notificationReceipt")?:text
   val manager=getSystemService(NotificationManager::class.java)
   manager.createNotificationChannel(NotificationChannel("payments","交易",NotificationManager.IMPORTANCE_DEFAULT))
   val builder=Notification.Builder(this,"payments").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(intent.getStringExtra("notificationTitle")?:"支付通知").setContentText(notificationText)
   val messages=intent.getStringArrayExtra("paymentMessages")
   if(messages!=null) {
    val style=Notification.MessagingStyle(Person.Builder().setName("样本用户").build())
    val time=intent.getLongExtra("messageTime",System.currentTimeMillis())
    messages.forEachIndexed {index,message->style.addMessage(message,time+index,Person.Builder().setName("微信支付").build())}
    style.addMessage("普通聊天内容不应保存",time+messages.size,Person.Builder().setName("朋友").build())
    builder.setStyle(style)
   } else builder.setStyle(Notification.BigTextStyle().bigText(notificationText))
   manager.notify(intent.getIntExtra("notificationId",1),builder.build())
  }
 }
}
