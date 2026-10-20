package dev.localledger.ui

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.localledger.AppSettings
import dev.localledger.BuildConfig
import dev.localledger.LedgerApplication
import dev.localledger.capture.PaymentAccessibilityService
import dev.localledger.capture.PaymentNotificationService

@Composable fun CaptureStatus(app:LedgerApplication) {
 var revision by remember {mutableIntStateOf(0)}
 var details by remember {mutableStateOf(false)}
 var copied by remember {mutableStateOf(false)}
 val health by app.captureDiagnostics.health.collectAsState()
 val observations by app.captureDiagnostics.observations.collectAsState()
 val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current
 DisposableEffect(owner) {
  val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)revision++}
  owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
 }
 val listener=remember(revision){NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)}
 val accessibility=remember(revision){Settings.Secure.getString(app.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).contains(ComponentName(app,PaymentAccessibilityService::class.java))}
 fun status(granted:Boolean,connected:Boolean)=when{!granted->"未授权";connected->"运行中";else->"已授权 · 未连接"}
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("通知读取");Text(status(listener,health.notificationConnected),color=if(listener&&health.notificationConnected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("页面读取");Text(status(accessibility,health.pageConnected),color=if(accessibility&&health.pageConnected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}
  if(listener&&!health.notificationConnected)TextButton(onClick={NotificationListenerService.requestRebind(ComponentName(app,PaymentNotificationService::class.java))}){Text("重连通知服务")}
  if(accessibility&&!health.pageConnected)TextButton(onClick={app.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}){Text("重新开启页面权限")}
  TextButton(onClick={details=!details}){Text(if(details)"收起采集状态" else "查看采集状态")}
  if(details) {
   val allowed=app.settings.allowedPackages()
   val visible=observations.filter{it.pkg in allowed}
   val format=remember{java.text.SimpleDateFormat("MM-dd HH:mm:ss",java.util.Locale.CHINA)}
   if(allowed.isEmpty())Text("尚未授权应用")
   allowed.forEach {pkg->
    Text(AppSettings.label(pkg),style=MaterialTheme.typography.titleSmall)
    listOf("notification" to "通知","page" to "页面","ocr" to "图片").forEach {(channel,label)->
     val event=visible.firstOrNull{it.pkg==pkg&&it.channel==channel}
     if(channel!="ocr"||event!=null)Text("$label · "+(event?.let{"${it.result.label} · ${format.format(java.util.Date(it.time))}"}?:"未收到事件"))
    }
   }
   TextButton(onClick={
    val report=buildString {
     appendLine("本地账本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
     appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}")
     appendLine("通知：${status(listener,health.notificationConnected)}；页面：${status(accessibility,health.pageConnected)}")
     appendLine("图片识别：${app.settings.screenshots}")
     allowed.forEach {pkg->appendLine("授权：${AppSettings.label(pkg)}")}
     visible.forEach{appendLine("${AppSettings.label(it.pkg)} / ${it.channel} / ${it.result.label} / ${format.format(java.util.Date(it.time))}")}
    }
    app.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("采集状态",report));copied=true
   }){Text(if(copied)"已复制状态" else "复制状态")}
  }
 }
}
