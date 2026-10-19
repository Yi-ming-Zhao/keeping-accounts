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
 val unresolved=remember(sources){sources.filter{e->ReceiptParser.parse(e.text)?.let{it.confirmed&&it.amountMinor==null&&it.kind!="cancelled"}==true}}
 var selected by remember{mutableStateOf<Evidence?>(null)};var amount by remember{mutableStateOf("")};var failure by remember{mutableStateOf<String?>(null)}
 val scope=rememberCoroutineScope()
 if(unresolved.isNotEmpty())Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
  Text("金额待确认 · ${unresolved.size}",style=MaterialTheme.typography.titleMedium)
  unresolved.forEach{evidence->OutlinedButton(onClick={selected=evidence;amount="";failure=null},modifier=Modifier.fillMaxWidth()){Text(ReceiptParser.parse(evidence.text)?.merchant?:"交易金额待确认")}}
 }
 selected?.let{evidence->AlertDialog(onDismissRequest={selected=null},title={Text("确认交易金额")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
  Text(evidence.text.take(1500))
  OutlinedTextField(amount,{amount=it},label={Text("实付金额")},keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=KeyboardType.Decimal))
  failure?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 }},confirmButton={TextButton(onClick={scope.launch{runCatching{app.repository.resolveAmount(evidence,Money.parse(amount)?:error("请输入正确金额"),null)}.onSuccess{selected=null}.onFailure{failure=it.message}}}){Text("确认入账")}},dismissButton={TextButton(onClick={selected=null}){Text("取消")}})}
}
