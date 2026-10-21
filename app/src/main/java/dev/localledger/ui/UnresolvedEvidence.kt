package dev.localledger.ui
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.localledger.LedgerApplication
import dev.localledger.capture.ReceiptParser
import dev.localledger.data.Evidence
import dev.localledger.ledger.Money
import kotlinx.coroutines.launch

@Composable fun UnresolvedEvidence(app:LedgerApplication) {
 val sources by app.database.book().unmatchedEvidence().collectAsState(emptyList())
 val jobs by app.database.book().observeJobs().collectAsState(emptyList())
 val unresolved=remember(sources,jobs){sources.filter{e->jobs.firstOrNull{it.id==e.id}?.state!="ignored"&&ReceiptParser.parse(e.text)?.kind!="cancelled"}}
 var kind by remember{mutableStateOf("expense")}
 var selected by remember{mutableStateOf<Evidence?>(null)};var amount by remember{mutableStateOf("")};var failure by remember{mutableStateOf<String?>(null)}
 val scope=rememberCoroutineScope()
 if(unresolved.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Text("待识别与核对 · ${unresolved.size}",style=MaterialTheme.typography.titleMedium)
  unresolved.forEach{evidence->OutlinedButton(onClick={selected=evidence;amount=ReceiptParser.parse(evidence.text)?.amountMinor?.let(Money::display).orEmpty();kind=ReceiptParser.parse(evidence.text)?.kind?.takeIf{it in setOf("expense","deferred","refund","repayment","transfer_candidate")}?:"expense";failure=null},modifier=Modifier.fillMaxWidth()){Text((ReceiptParser.parse(evidence.text)?.merchant?:dev.localledger.AppSettings.label(evidence.sourcePackage))+" · "+when(jobs.firstOrNull{it.id==evidence.id}?.state){"waiting_config"->"等待模型配置";"pending","processing"->"等待智能识别";else->jobs.firstOrNull{it.id==evidence.id}?.error?:"待确认"})}}
 }
 selected?.let{evidence->AlertDialog(onDismissRequest={selected=null},title={Text("核对交易")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(evidence.text.take(1500))
  listOf("expense" to "已付款","deferred" to "先用后付 · 待扣款","refund" to "已退款","repayment" to "已还款","transfer_candidate" to "转账 / 充值").forEach{(value,label)->FilterChip(selected=kind==value,onClick={kind=value},label={Text(label)})}
  OutlinedTextField(amount,{amount=it},label={Text(if(kind=="deferred")"待扣款金额" else "金额")},keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Decimal))
  failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 }},confirmButton={TextButton(onClick={scope.launch{runCatching{app.repository.resolveAmount(evidence,Money.parse(amount)?:error("请输入正确金额"),null,confirmedKind=kind)}.onSuccess{selected=null}.onFailure{failure=it.message}}}){Text("确认入账")}},dismissButton={TextButton(onClick={selected=null}){Text("取消")}})}
}
