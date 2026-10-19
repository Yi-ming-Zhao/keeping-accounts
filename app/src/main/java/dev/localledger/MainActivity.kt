package dev.localledger

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import dev.localledger.ui.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.lifecycleScope
import dev.localledger.agent.*
import dev.localledger.capture.*
import dev.localledger.data.*
import dev.localledger.ledger.*
import dev.localledger.security.BackupManager
import kotlinx.coroutines.*
import org.json.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

class MainActivity:ComponentActivity() {
 private val app get()=application as LedgerApplication
 private var message by mutableStateOf<String?>(null)
 private var password=charArrayOf()
 private var restoring=false
 private val export=registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri->
  if(uri==null){password.fill('\u0000');return@registerForActivityResult}
  lifecycleScope.launch { try {withContext(Dispatchers.IO){BackupManager(app).export(uri,password)};message="备份已保存"} catch(_:Exception){message="备份失败"} }
 }
 private val restore=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri->
  if(uri==null){password.fill('\u0000');return@registerForActivityResult}
  lifecycleScope.launch {
   try {withContext(Dispatchers.IO){BackupManager(app).restore(uri,password);AnalysisWorker.recover(app)};message="账本已恢复"}
   catch(e:Exception){message=if(e is IllegalArgumentException||e is IllegalStateException)e.message else "恢复失败"}
  }
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  if(!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
  setContent { LedgerTheme { LedgerScreen() } }
  acceptShare(intent)
 }
 override fun onNewIntent(intent:Intent) {super.onNewIntent(intent);acceptShare(intent)}
 private fun acceptShare(intent:Intent?) {
  if(intent?.action!=Intent.ACTION_SEND)return
  lifecycleScope.launch {
   try {
    val text=when {
     intent.type=="text/plain"->intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
     intent.type?.startsWith("image/")==true->{
      val uri=if(android.os.Build.VERSION.SDK_INT>=33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
      require(uri!=null)
      withContext(Dispatchers.IO) {
       val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true}
       contentResolver.openInputStream(uri)!!.use {BitmapFactory.decodeStream(it,null,opts)}
       require(opts.outWidth>0&&opts.outHeight>0)
       opts.inJustDecodeBounds=false;opts.inSampleSize=1
       while(opts.outWidth/opts.inSampleSize>1800||opts.outHeight/opts.inSampleSize>4000)opts.inSampleSize*=2
       val bitmap=contentResolver.openInputStream(uri)!!.use {BitmapFactory.decodeStream(it,null,opts)}?:error("图片无法读取")
       try {LocalOcr(app).recognize(bitmap)} finally {bitmap.recycle()}
      }
     }
     else->""
    }
    val id=withContext(Dispatchers.IO){CaptureCoordinator(app).accept("shared","share","share:${UUID.randomUUID()}",text)}
    message=if(id==null) "未识别到交易" else "已记录"
   } catch(_:Exception) {message="无法读取分享内容"}
  }
 }
 @Composable private fun LedgerScreen() {
  var destination by rememberSaveable {mutableStateOf("账本")}
  var month by remember {mutableStateOf(YearMonth.now())}
  var filter by remember {mutableStateOf<String?>(null)}
  var query by rememberSaveable {mutableStateOf("")}
  var search by remember {mutableStateOf(false)}
  var limit by remember(month){mutableIntStateOf(100)}
  var selected by remember {mutableStateOf<LedgerEntry?>(null)}
  var settings by remember {mutableStateOf(false)}
  var manual by remember {mutableStateOf(false)}
  var budgetDialog by remember {mutableStateOf(false)}
  var budget by remember {mutableLongStateOf(app.settings.preferences.getLong("monthly_budget",0))}
  var budgetText by remember {mutableStateOf("")}
  val start=month.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
  val end=month.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
  val entries by remember(start,end,query,filter,limit){app.database.book().filtered(start,end,query,filter,limit)}.collectAsState(emptyList())
  val summary by remember(start,end){app.database.book().summary(start,end)}.collectAsState(PeriodSummary(0,0,0,0,0))
  val categories by remember(start,end){app.database.book().categoryTotals(start,end)}.collectAsState(emptyList())
  val days by remember(start,end){app.database.book().daily(start,end)}.collectAsState(emptyList())
  val review by remember(start,end){app.database.book().observeReviewCount(start,end)}.collectAsState(0)
  val unmatched by app.database.book().unmatchedEvidence().collectAsState(emptyList())
  val totalReview=review+remember(unmatched){unmatched.count{ReceiptParser.parse(it.text)?.let{c->c.confirmed&&c.kind!="cancelled"&&c.amountMinor==null}==true}}
  val jobs by app.database.book().observeJobs().collectAsState(emptyList())
  val scope=rememberCoroutineScope()
  Surface(Modifier.fillMaxSize()) {
   Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
    Box(Modifier.weight(1f)) {
     when(destination) {
      "对话"->ChatScreen(app,onEntry={selected=it},onSettings={settings=true})
      "账户"->Column(Modifier.verticalScroll(rememberScrollState())) {AccountsPanel(app,summary.expenses+summary.refunds+summary.income)}
      else->Column {
       Row(Modifier.fillMaxWidth().padding(start=24.dp,end=16.dp,top=18.dp,bottom=14.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(if(destination=="分析")"收支分析" else "青账",fontSize=28.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
        TextButton(onClick={search=!search;destination="账本"}){Text("搜索")}
        FilledTonalIconButton(onClick={manual=true}){Text("＋",fontSize=24.sp)}
       }
       Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
        TextButton(onClick={month=month.minusMonths(1)}){Text("‹",fontSize=24.sp)}
        Text("${month.year} 年 ${month.monthValue} 月",fontWeight=FontWeight.Medium,modifier=Modifier.weight(1f))
        if(month!=YearMonth.now())TextButton(onClick={month=YearMonth.now()}){Text("本月")}
        TextButton(enabled=month<YearMonth.now(),onClick={month=month.plusMonths(1)}){Text("›",fontSize=24.sp)}
       }
       if(search)OutlinedTextField(query,{query=it},singleLine=true,placeholder={Text("商户、商品、分类或备注")},modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=8.dp),shape=RoundedCornerShape(16.dp))
       if(destination=="分析") {
        LazyColumn(contentPadding=PaddingValues(24.dp),verticalArrangement=Arrangement.spacedBy(28.dp)) {
         item {Row(Modifier.fillMaxWidth()){StatCell("支出",summary.expenses,Modifier.weight(1f));StatCell("退款",summary.refunds,Modifier.weight(1f));StatCell("收入",summary.income,Modifier.weight(1f))}}
         item {DailyChart(days,month.lengthOfMonth())}
         item {Row(verticalAlignment=Alignment.CenterVertically){Text("分类支出",fontWeight=FontWeight.SemiBold,fontSize=18.sp,modifier=Modifier.weight(1f));Text("${summary.count} 笔",color=MaterialTheme.colorScheme.onSurfaceVariant)}}
         items(categories,key={it.category}) {category->Column(Modifier.clickable{query=category.category;search=true;filter=null;destination="账本"},verticalArrangement=Arrangement.spacedBy(10.dp)) {
          Row {Text(category.category,Modifier.weight(1f));Text("¥ ${Money.display(category.amountMinor)}",fontWeight=FontWeight.Medium)}
          LinearProgressIndicator(progress={(category.amountMinor.toFloat()/(categories.maxOfOrNull{it.amountMinor}?:1).coerceAtLeast(1)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(6.dp),trackColor=MaterialTheme.colorScheme.surfaceVariant)
         }}
         if(categories.isEmpty())item{Text("暂无支出")}
        }
       } else {
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=24.dp,vertical=12.dp)) {
         item {
          if(app.settings.model()==null||app.settings.allowedPackages().isEmpty())Row(Modifier.fillMaxWidth().padding(bottom=8.dp),verticalAlignment=Alignment.CenterVertically){Text("自动记账",Modifier.weight(1f),fontWeight=FontWeight.Medium);TextButton(onClick={settings=true}){Text("完成设置 →")}}
          Surface(shape=RoundedCornerShape(26.dp),color=MaterialTheme.colorScheme.primary,contentColor=MaterialTheme.colorScheme.onPrimary) {
           Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("本月净支出",fontSize=14.sp)
            Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text("¥",fontSize=22.sp,modifier=Modifier.padding(bottom=7.dp));Text(Money.display(summary.expenses-summary.refunds),fontSize=40.sp,fontWeight=FontWeight.Medium,letterSpacing=(-1).sp)}
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
             Text("收入 ${Money.display(summary.income)}",fontSize=13.sp,modifier=Modifier.weight(1f))
             Text("待扣款 ${Money.display(summary.deferred)}",fontSize=13.sp,modifier=Modifier.clickable{filter="deferred"})
            }
            if(budget>0){LinearProgressIndicator(progress={((summary.expenses-summary.refunds).toFloat()/budget).coerceIn(0f,1f)},color=MaterialTheme.colorScheme.onPrimary,trackColor=MaterialTheme.colorScheme.onPrimary.copy(alpha=.2f),modifier=Modifier.fillMaxWidth());Text("预算余额 ${Money.display(budget-summary.expenses+summary.refunds)}",fontSize=13.sp,modifier=Modifier.clickable{budgetText=Money.display(budget);budgetDialog=true})}
           }
          }
          Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
           TextButton(onClick={budgetText=if(budget>0)Money.display(budget)else"";budgetDialog=true}){Text(if(budget>0)"调整预算" else "设置月预算")}
           Spacer(Modifier.weight(1f));TextButton(onClick={filter=if(filter=="review")null else "review"}){Text("待核对 $totalReview")}
          }
          Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
           listOf(null to "全部","expense" to "支出","refund" to "退款","deferred" to "待扣款","review" to "待核对").forEach {(value,label)->FilterChip(selected=filter==value,onClick={filter=value},label={Text(label)})}
          }
         }
         if(filter=="review")item{UnresolvedEvidence(app)}
         if(entries.isEmpty())item{Text(if(query.isBlank())"暂无账目" else "没有找到相关账目",Modifier.padding(vertical=30.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)}
         entries.groupBy {Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()}.forEach {(day,rows)->
          item {Row(Modifier.padding(top=22.dp,bottom=4.dp)){Text("${day.monthValue} 月 ${day.dayOfMonth} 日",fontSize=13.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f));Text("${rows.size} 笔",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
          items(rows,key={it.id}){entry->BookRow(entry){selected=entry};HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))}
         }
         if(entries.size==limit)item{TextButton(onClick={limit+=100}){Text("加载更多")}}
         if(filter=="review"&&jobs.isNotEmpty())item{
          TextButton(onClick={scope.launch{jobs.filter{it.state in setOf("failed","waiting_config")}.forEach{app.database.book().saveJob(it.copy(state="pending",error=null));AnalysisWorker.enqueue(app,it.id)}}}){Text("重新分析 · ${jobs.size}")}
         }
        }
       }
      }
     }
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.5f))
    NavigationBar(containerColor=MaterialTheme.colorScheme.surface,tonalElevation=0.dp) {
     listOf("账本" to "▤","分析" to "▥","对话" to "◎","账户" to "▣","设置" to "⚙").forEach {(title,icon)->NavigationBarItem(selected=destination==title,onClick={if(title=="设置")settings=true else destination=title},icon={NavIcon(title)},label={Text(title,fontSize=11.sp)})}
    }
   }
  }
  if(settings)SettingsSheet(onDismiss={settings=false},onBackup={isRestore,pwd->password=pwd.toCharArray();restoring=isRestore;if(isRestore)restore.launch(arrayOf("application/octet-stream","*/*"))else export.launch("账本-${LocalDate.now()}.ledger")})
  selected?.let{entry->EntryDialog(entries.find{it.id==entry.id}?:entry){selected=null}}
  if(manual)ManualDialog{manual=false}
  if(budgetDialog)AlertDialog(onDismissRequest={budgetDialog=false},title={Text("月预算")},text={OutlinedTextField(budgetText,{budgetText=it},label={Text("金额")},keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Decimal))},confirmButton={TextButton(onClick={val value=Money.parse(budgetText);if(value!=null){budget=value;app.settings.preferences.edit().putLong("monthly_budget",value).apply();budgetDialog=false}}){Text("保存")}},dismissButton={TextButton(onClick={budget=0;app.settings.preferences.edit().remove("monthly_budget").apply();budgetDialog=false}){Text("取消预算")}})
  message?.let{text->AlertDialog(onDismissRequest={message=null},text={Text(text)},confirmButton={TextButton(onClick={message=null}){Text("好")}})}
 }
 @Composable private fun EntryRow(entry:LedgerEntry,onClick:()->Unit) {
  Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=16.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)) {
    Text(entry.merchant?:when(entry.kind){"refund"->"退款";"repayment"->"还款";"deferred"->"待扣款";"cancelled"->"已取消";"transfer"->"账户转移";else->"付款"},fontSize=17.sp,maxLines=1)
    Text(entry.category?:entry.reviewReason?:kindLabel(entry.kind),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
   }
   Text((if(entry.kind=="refund"||entry.kind=="income") "+" else "")+Money.display(entry.amountMinor),fontSize=19.sp,fontWeight=FontWeight.Medium,color=if(entry.kind=="refund")MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
  }
 }
 @Composable private fun EntryDialog(entry:LedgerEntry,onDismiss:()->Unit) {
  var amount by remember(entry.id){mutableStateOf(Money.display(entry.amountMinor))}
  var merchant by remember(entry.id){mutableStateOf(entry.merchant.orEmpty())}
  var category by remember(entry.id){mutableStateOf(entry.category.orEmpty())}
  var note by remember(entry.id){mutableStateOf(entry.note.orEmpty())}
  var kind by remember(entry.id){mutableStateOf(entry.kind)}
  var rememberRule by remember{mutableStateOf(false)}
  var expanded by remember{mutableStateOf(false)}
  var detailError by remember{mutableStateOf<String?>(null)}
  var editing by remember{mutableStateOf(false)}
  var goods by remember{mutableStateOf(emptyList<OrderItem>())}
  var charges by remember{mutableStateOf(emptyList<OrderCharge>())}
  var refunds by remember{mutableStateOf(emptyList<LedgerEntry>())}
  var sources by remember{mutableStateOf(emptyList<Evidence>())}
  var corrections by remember{mutableStateOf(emptyList<Correction>())}
  var history by remember{mutableStateOf(emptyList<Audit>())}
  var categories by remember{mutableStateOf(emptyList<Category>())}
  val scope=rememberCoroutineScope()
  LaunchedEffect(entry) {
   val book=app.database.book();val orders=(book.allocations(entry.id).map{it.orderId}+listOfNotNull(entry.orderId)).distinct()
   goods=orders.flatMap{book.items(it)}.distinctBy{it.id};charges=orders.flatMap{book.charges(it)}
   refunds=book.refunds(entry.id);sources=book.evidenceForEntry(entry.id);corrections=book.corrections(entry.id);history=book.audits(entry.id);categories=book.categories()
  }
  androidx.compose.ui.window.Dialog(onDismissRequest=onDismiss,properties=androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth=false)) {
   Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
    Column(Modifier.statusBarsPadding().navigationBarsPadding().imePadding()) {
     Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=Alignment.CenterVertically) {
      TextButton(onClick=onDismiss){Text("关闭")};Text("账目详情",Modifier.weight(1f),fontWeight=FontWeight.SemiBold,fontSize=18.sp)
      TextButton(onClick={editing=!editing}){Text(if(editing)"预览" else "编辑")}
     }
     Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
      Column(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
       Text(kindTitle(kind),color=MaterialTheme.colorScheme.primary,fontSize=15.sp)
       Text("¥ $amount",fontSize=42.sp,fontWeight=FontWeight.Medium,letterSpacing=(-1).sp)
       Text(merchant.ifBlank{kindTitle(kind)},fontSize=19.sp,fontWeight=FontWeight.Medium)
       Text(Instant.ofEpochMilli(entry.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=14.sp)
      }
      detailError?.let{Text(it,color=MaterialTheme.colorScheme.error)}
      if(editing) {
       OutlinedTextField(amount,{amount=it},label={Text("金额")},modifier=Modifier.fillMaxWidth(),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Decimal))
       OutlinedTextField(merchant,{merchant=it},label={Text("商户")},modifier=Modifier.fillMaxWidth())
       OutlinedTextField(category,{category=it},label={Text("分类")},modifier=Modifier.fillMaxWidth())
       Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){categories.forEach{c->FilterChip(selected=category==c.name,onClick={category=c.name},label={Text(c.name)})}}
       OutlinedTextField(note,{note=it},label={Text("备注")},modifier=Modifier.fillMaxWidth())
       if(entry.kind=="transfer_candidate")Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("expense","transfer").forEach{k->FilterChip(selected=kind==k,onClick={kind=k},label={Text(kindTitle(k))})}}
       if(entry.merchant!=null)Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rememberRule,{rememberRule=it});Text("以后按此分类")}
      } else {
       Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
         Row{Text("分类",Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(category.ifBlank{"未分类"})}
         Row{Text("来源",Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(AppSettings.label(entry.sourcePackage))}
         Row{Text("状态",Modifier.weight(1f),color=MaterialTheme.colorScheme.onSurfaceVariant);Text(entry.reviewReason?:if(entry.userLocked)"已确认" else "已记录")}
         if(note.isNotBlank()){HorizontalDivider();Text(note)}
        }
       }
      }
      if(goods.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
       Text("商品",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
       goods.forEach{item->Row(verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){Text(item.name);Text(listOfNotNull(item.quantity?.let{"× $it"},item.category).joinToString(" · "),color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=13.sp)}
        Text(item.totalMinor?.let{"¥ ${Money.display(it)}"}?:"未分摊")
       }}
      }
      if(charges.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(12.dp)){charges.forEach{charge->Row{Text(when(charge.kind){"shipping"->"运费";"discount"->"优惠";else->"手续费"},Modifier.weight(1f));Text("¥ ${Money.display(charge.amountMinor)}")}}}
      if(refunds.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
       Text("退款",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
       refunds.forEach{returned->Row{Text(Instant.ofEpochMilli(returned.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd")),Modifier.weight(1f));Text("+ ${Money.display(returned.amountMinor)}",color=MaterialTheme.colorScheme.primary)}}
       HorizontalDivider();Row{Text("净支出",Modifier.weight(1f));Text("¥ ${Money.display(entry.amountMinor-refunds.sumOf{it.amountMinor})}")}
      }
      TextButton(onClick={expanded=!expanded}){Text(if(expanded)"收起来源与修改记录" else "来源与修改记录")}
      if(expanded) {
       sources.forEach{source->Surface(shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow){Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(if(source.channel=="notification")"通知" else if(source.channel=="page")"页面" else "交易证据",fontWeight=FontWeight.Medium);Text(source.text,fontSize=14.sp,lineHeight=22.sp)}}}
       history.forEach{change->Text(when(change.action){"capture"->"已采集交易";"classification"->"已更新分类";"user_correction"->"你修改了账目";"undo"->"已撤销修改";"deferred_paid"->"待扣款转为实际支出";"conflict"->"发现不同金额的证据";else->"账目已更新"},fontSize=14.sp)}
       corrections.filter{!it.undone}.forEach{change->TextButton(onClick={scope.launch{runCatching{app.repository.undo(change.id)}.onSuccess{onDismiss()}.onFailure{detailError=it.message}}}){Text("撤销 ${Instant.ofEpochMilli(change.createdAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))} 的修改")}}
      }
      Spacer(Modifier.height(20.dp))
     }
     if(editing)Button(modifier=Modifier.fillMaxWidth().padding(20.dp).height(52.dp),shape=RoundedCornerShape(16.dp),onClick={scope.launch{
      runCatching{app.repository.correct(entry.id,category.ifBlank{null},note.ifBlank{null},kind,rememberRule,Money.parse(amount)?:error("请输入正确金额"),merchant.ifBlank{null})}.onSuccess{onDismiss()}.onFailure{detailError=it.message}
     }}){Text("保存修改")}
    }
   }
  }
 }
 @Composable private fun ManualDialog(onDismiss:()->Unit) {
  var amount by remember{mutableStateOf("")};var merchant by remember{mutableStateOf("")};var category by remember{mutableStateOf("")};var note by remember{mutableStateOf("")};var kind by remember{mutableStateOf("expense")}
  val scope=rememberCoroutineScope();val session=remember{"manual:${UUID.randomUUID()}"}
  AlertDialog(onDismissRequest=onDismiss,title={Text("记一笔")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("expense" to "支出","income" to "收入").forEach{(value,label)->FilterChip(selected=kind==value,onClick={kind=value},label={Text(label)})}}
   OutlinedTextField(amount,{amount=it},label={Text("金额")},keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Decimal))
   OutlinedTextField(merchant,{merchant=it},label={Text(if(kind=="income")"来源" else "商户")})
   OutlinedTextField(category,{category=it},label={Text("分类")})
   OutlinedTextField(note,{note=it},label={Text("备注")})
  }},confirmButton={TextButton(onClick={scope.launch {
   runCatching {
    val value=Money.parse(amount)?.takeIf{it>0}?:error("请输入正确金额")
    val label=merchant.ifBlank{"手动记录"}
    val quote="记一笔${kindTitle(kind)}：${Money.display(value)}元，$label"
    val args=JSONObject().put("amountMinor",value).put("merchant",label).put("kind",kind).put("quote",quote)
    if(category.isNotBlank())args.put("category",category)
    if(note.isNotBlank())args.put("note",note)
    LedgerTools(app,session,userIntent=quote).execute("create_entry",args,"manual-submit")
   }.onSuccess{onDismiss()}.onFailure{message=it.message?:"保存失败"}
  }}){Text("记录")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
 }
 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun SettingsSheet(onDismiss:()->Unit,onBackup:(Boolean,String)->Unit) {
  val saved=remember{app.settings.model()}
  var base by remember {mutableStateOf(saved?.baseUrl?:"https://api.deepseek.com")}
  var model by remember {mutableStateOf(saved?.model?:"deepseek-flash")}
  var key by remember {mutableStateOf("")}
  var images by remember {mutableStateOf(saved?.images?:false)}
  var screenshots by remember {mutableStateOf(app.settings.screenshots)}
  var allowed by remember {mutableStateOf(app.settings.allowedPackages())}
  var consent by remember {mutableStateOf<String?>(null)}
  var backup by remember {mutableStateOf<Boolean?>(null)}
  var pwd by remember {mutableStateOf("")}
  var busy by remember {mutableStateOf(false)}
  var status by remember {mutableStateOf("")}
  var rules by remember {mutableStateOf(emptyList<CategoryRule>())}
  var editingRule by remember {mutableStateOf<CategoryRule?>(null)}
  var ruleCategory by remember {mutableStateOf("")}
  var clearBook by remember {mutableStateOf(false)}
  val scope=rememberCoroutineScope()
  val apps=remember {
   val all=AppSettings.applications.toMutableMap()
   packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
    .filter {it.activityInfo.packageName!=packageName}.forEach {all.putIfAbsent(it.activityInfo.packageName,it.loadLabel(packageManager).toString())}
   all.toList()
  }
  LaunchedEffect(Unit){rules=app.database.book().rules()}
  ModalBottomSheet(onDismissRequest=onDismiss) {
   Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp).imePadding(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
    Text("设置",style=MaterialTheme.typography.headlineMedium)
    Text("模型",style=MaterialTheme.typography.titleLarge)
    OutlinedTextField(base,{base=it},label={Text("接口地址")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(model,{model=it},label={Text("模型名")},singleLine=true,modifier=Modifier.fillMaxWidth())
    OutlinedTextField(key,{key=it},label={Text(if(saved!=null) "密钥 · 已保存" else "密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(verticalAlignment=Alignment.CenterVertically){Text("允许发送脱敏图片",Modifier.weight(1f));Switch(images,{images=it})}
    Button(enabled=!busy,onClick={scope.launch {
     busy=true;status="正在验证"
     try {
      val config=ModelConfig(base,model,key.ifBlank {saved?.apiKey.orEmpty()},images).checked()
      val id="check_${UUID.randomUUID().toString().take(8)}"
      PiRuntime(app,app.database).run(id,"调用 record_probe 保存 id=$id、amountMinor=1、label=接口验证，完成后回复已保存。",config.model,ModelClient(config)::request)
      check(app.database.dao().probe(id)?.amountMinor==1L)
      app.settings.saveModel(config);key="";status="已保存";AnalysisWorker.recover(app)
     } catch(_:Exception){status="验证失败，请检查接口和密钥"}
     finally{busy=false}
    }},modifier=Modifier.fillMaxWidth()){Text(if(busy)"验证中" else "验证并保存")}
    if(status.isNotBlank())Text(status)
    HorizontalDivider()
    Text("采集",style=MaterialTheme.typography.titleLarge)
    CaptureStatus(app)
    Row {TextButton(onClick={consent="notification"}){Text("通知权限")};TextButton(onClick={consent="accessibility"}){Text("页面权限")};TextButton(onClick={startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$packageName")))}){Text("后台运行")}}
    Row(verticalAlignment=Alignment.CenterVertically){Text("本机图片识别",Modifier.weight(1f));Switch(screenshots,{screenshots=it;app.settings.screenshots=it})}
    apps.forEach {(pkg,label)->Row(verticalAlignment=Alignment.CenterVertically) {
     Text(label,Modifier.weight(1f));Switch(pkg in allowed,{enable->if(enable)consent=pkg else{app.settings.allow(pkg,false);allowed=app.settings.allowedPackages()}})
    }}
    HorizontalDivider()
    Text("分类规则",style=MaterialTheme.typography.titleLarge)
    rules.forEach {r->Row(verticalAlignment=Alignment.CenterVertically){Text("${r.merchantEquals} → ${r.category}",Modifier.weight(1f).clickable {editingRule=r;ruleCategory=r.category});TextButton(onClick={scope.launch{app.database.book().deleteRule(r.id);rules=app.database.book().rules()}}){Text("删除")}}}
    HorizontalDivider()
    UpdatePanel()
    HorizontalDivider()
    Text("数据",style=MaterialTheme.typography.titleLarge)
    Row {TextButton(onClick={backup=false;pwd=""}){Text("加密备份")};TextButton(onClick={backup=true;pwd=""}){Text("恢复备份")}}
    TextButton(onClick={scope.launch {withContext(Dispatchers.IO){app.database.book().allImages().forEach {ev->ev.imagePath?.let {java.io.File(app.filesDir,it).delete()};app.database.book().clearImage(ev.id)}};message="图片已清除"}}){Text("清除证据图片")}
    TextButton(onClick={clearBook=true}){Text("清空账本")}
    Spacer(Modifier.height(30.dp))
   }
  }
  editingRule?.let {rule->AlertDialog(onDismissRequest={editingRule=null},title={Text(rule.merchantEquals)},text={OutlinedTextField(ruleCategory,{ruleCategory=it},label={Text("分类")})},confirmButton={TextButton(enabled=ruleCategory.isNotBlank()&&ruleCategory.length<=30,onClick={scope.launch {app.database.book().saveCategory(Category(ruleCategory));app.database.book().saveRule(rule.copy(category=ruleCategory));rules=app.database.book().rules();editingRule=null}}){Text("保存")}},dismissButton={TextButton(onClick={editingRule=null}){Text("取消")}})}
  if(clearBook)AlertDialog(onDismissRequest={clearBook=false},title={Text("清空账本？")},text={Text("所有账目、证据和修改记录将被删除。")},confirmButton={TextButton(onClick={scope.launch {
   withContext(Dispatchers.IO){app.maintenance.lock();try{app.database.clearAllTables();java.io.File(app.filesDir,"evidence").deleteRecursively();listOf("餐饮","交通","购物","生活","住房","教育","医疗","娱乐","其他").forEach{app.database.book().saveCategory(Category(it))}}finally{app.maintenance.unlock()}}
   clearBook=false;message="账本已清空"
  }}){Text("清空")}},dismissButton={TextButton(onClick={clearBook=false}){Text("取消")}})
  consent?.let {target->
   val text=when(target){"notification"->"读取已授权应用的支付通知，用于记账。只有交易相关字段会保存到本机。";"accessibility"->"读取已授权应用中你打开的订单和付款页面。不会点击或操作其他应用。开启本机图片识别后，必要时会读取页面截图。";else->"允许读取${AppSettings.label(target)}的交易通知和已打开的订单页面。分析时，必要的脱敏交易信息会发送到你选择的模型接口。"}
   AlertDialog(onDismissRequest={consent=null},title={Text("授权采集")},text={Text(text)},confirmButton={TextButton(onClick={
    when(target){"notification"->startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));"accessibility"->startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));else->{app.settings.allow(target,true);allowed=app.settings.allowedPackages()}}
    consent=null
   }){Text("允许")}},dismissButton={TextButton(onClick={consent=null}){Text("取消")}})
  }
  backup?.let {isRestore->AlertDialog(onDismissRequest={backup=null;pwd=""},title={Text(if(isRestore)"恢复并替换账本" else "加密备份")},text={Column {
   if(isRestore)Text("恢复会替换当前账本。")
   OutlinedTextField(pwd,{pwd=it},label={Text("备份密码 · 至少 8 位")},visualTransformation=PasswordVisualTransformation())
  }},confirmButton={TextButton(enabled=pwd.length>=8,onClick={onBackup(isRestore,pwd);pwd="";backup=null}){Text(if(isRestore)"选择备份" else "保存")}},dismissButton={TextButton(onClick={backup=null;pwd=""}){Text("取消")}})}
 }
}

private fun kindLabel(kind:String)=when(kind){"expense"->"支出";"fee"->"手续费";"income"->"收入";"refund"->"退款";"repayment"->"还款";"deferred"->"待扣款";"cancelled"->"已取消";"transfer"->"账户转移";else->"待确认"}
@Composable private fun LedgerTheme(content: @Composable () -> Unit) {
 val dark=isSystemInDarkTheme()
 MaterialTheme(colorScheme=if(dark) darkColorScheme(primaryContainer=Color(0xFF264C39),onPrimaryContainer=Color(0xFFD0E9D9),secondaryContainer=Color(0xFF264C39),onSecondaryContainer=Color(0xFFD0E9D9),onPrimary=Color(0xFF102C1F),primary=Color(0xFFA5D5BC),background=Color(0xFF131A16),surface=Color(0xFF131A16),secondary=Color(0xFFA5D5BC),tertiary=Color(0xFFA5D5BC),surfaceContainerLow=Color(0xFF1A231D),surfaceContainer=Color(0xFF202B24),surfaceContainerHigh=Color(0xFF263229),surfaceContainerHighest=Color(0xFF2E3B31),surfaceTint=Color(0xFFA5D5BC)) else lightColorScheme(primaryContainer=Color(0xFFDDEADF),onPrimaryContainer=Color(0xFF183B28),secondaryContainer=Color(0xFFDDEADF),onSecondaryContainer=Color(0xFF183B28),onPrimary=Color.White,primary=Color(0xFF286149),background=Color(0xFFF8F8F3),surface=Color(0xFFF8F8F3),surfaceVariant=Color(0xFFE4E9E2),secondary=Color(0xFF286149),tertiary=Color(0xFF286149),surfaceContainerLow=Color(0xFFF8F8F3),surfaceContainer=Color(0xFFF2F3EC),surfaceContainerHigh=Color(0xFFF2F3EC),surfaceContainerHighest=Color(0xFFE4E9E2),surfaceTint=Color(0xFF286149)),content=content)
}
