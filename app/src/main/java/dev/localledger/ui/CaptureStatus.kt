package dev.localledger.ui

import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.localledger.LedgerApplication

@Composable fun CaptureStatus(app:LedgerApplication) {
 // Re-read after returning from system permission screens.
 var revision by remember {mutableIntStateOf(0)}
 val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current
 DisposableEffect(owner) {
  val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)revision++}
  owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}
 }
 val listener=remember(revision){NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)}
 val accessibility=remember(revision){Settings.Secure.getString(app.contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().contains("dev.localledger.capture.PaymentAccessibilityService")}
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("通知读取");Text(if(listener)"已开启" else "未开启",color=if(listener)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("页面读取");Text(if(accessibility)"已开启" else "未开启",color=if(accessibility)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)}
 }
}
