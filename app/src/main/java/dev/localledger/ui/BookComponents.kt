package dev.localledger.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import dev.localledger.*
import dev.localledger.data.*
import dev.localledger.ledger.Money
import java.time.*

fun kindTitle(kind:String)=when(kind){"expense"->"支出";"fee"->"手续费";"income"->"收入";"refund"->"退款";"repayment"->"还款";"transfer"->"转移";"deferred"->"待扣款";"cancelled"->"已取消";else->"待确认"}
fun categoryMark(category:String?)=when(category){"餐饮"->"食";"交通"->"行";"购物"->"物";"生活"->"居";"住房"->"家";"教育"->"学";"医疗"->"医";"娱乐"->"乐";else->"账"}
@Composable fun BookRow(entry:LedgerEntry,onClick:()->Unit) {
 Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
  Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.size(46.dp)) {Box(contentAlignment=Alignment.Center){Text(if(entry.kind=="refund")"退" else if(entry.kind=="deferred")"候" else categoryMark(entry.category),color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Medium)}}
  Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
   Text(entry.merchant?:kindTitle(entry.kind),fontSize=16.sp,fontWeight=FontWeight.Medium,maxLines=1)
   Text(listOfNotNull(entry.category,if(entry.kind !in setOf("expense","income"))kindTitle(entry.kind) else null,AppSettings.label(entry.sourcePackage).takeUnless{it.contains('.')}).joinToString(" · ").ifBlank{entry.reviewReason?:"未分类"},style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)
  }
  Column(horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(5.dp)) {
   Text((if(entry.kind in setOf("refund","income"))"+" else "")+Money.display(entry.amountMinor),fontSize=18.sp,fontWeight=FontWeight.SemiBold,color=if(entry.kind in setOf("refund","income"))MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
   if(entry.reviewReason!=null&&entry.kind!="deferred")Text("待核对",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.tertiary)
  }
 }
}
@Composable fun StatCell(title:String,amount:Long,modifier:Modifier=Modifier) {
 Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)){Text(title,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp);Text(Money.display(amount),fontSize=20.sp,fontWeight=FontWeight.Medium)}
}
@Composable fun DailyChart(days:List<DayTotal>,length:Int) {
 val green=MaterialTheme.colorScheme.primary;val muted=MaterialTheme.colorScheme.surfaceVariant
 Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
  Text("每日支出",fontWeight=FontWeight.SemiBold,fontSize=18.sp)
  Canvas(Modifier.fillMaxWidth().height(110.dp)) {
   val maximum=days.maxOfOrNull{it.amountMinor}?.coerceAtLeast(1)?:1
   val slot=size.width/length
   for(day in 1..length){val value=days.firstOrNull{it.day.toIntOrNull()==day}?.amountMinor?:0;val height=if(value>0)(value.toFloat()/maximum*size.height).coerceAtLeast(3f) else 3f
    drawRoundRect(if(value>0)green else muted,Offset((day-1)*slot,size.height-height),Size(slot*0.62f,height),CornerRadius(3f,3f))}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("1 日",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("$length 日",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
 }
}
@Composable fun AccountsPanel(app:LedgerApplication,updated:Long) {
 var accounts by remember {mutableStateOf(emptyList<Pair<Account,Long>>())}
 LaunchedEffect(updated){accounts=app.database.book().accounts().map{it to app.database.book().accountOutflow(it.id,Long.MAX_VALUE)}}
 Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
  Text("账户",fontSize=28.sp,fontWeight=FontWeight.SemiBold)
  if(accounts.isEmpty())Text("暂无账户")
  accounts.forEach {(account,value)->Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
   Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
    Text(AppSettings.label(account.name),fontSize=18.sp,fontWeight=FontWeight.Medium)
    Text(if(account.kind=="liability")"已记录欠款变动" else "已记录净流出",color=MaterialTheme.colorScheme.onSurfaceVariant)
    Text("¥ ${Money.display(value)}",fontSize=28.sp,fontWeight=FontWeight.Medium)
   }
  }}
 }
}
