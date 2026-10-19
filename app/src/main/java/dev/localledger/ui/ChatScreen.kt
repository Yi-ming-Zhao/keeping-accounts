package dev.localledger.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.localledger.LedgerApplication
import dev.localledger.agent.ConversationController
import dev.localledger.data.LedgerEntry
import dev.localledger.ledger.Money
import kotlinx.coroutines.launch
import org.json.*
import java.util.UUID

private data class Bubble(val key:Int,val user:Boolean,val text:String,val entryIds:List<String> = emptyList())
private fun bubbles(raw:String):List<Bubble> {
 val array=JSONArray(raw);val output=mutableListOf<Bubble>();val ids=linkedSetOf<String>()
 for(i in 0 until array.length()) {
  val message=array.getJSONObject(i);val role=message.optString("role")
  val content=message.opt("content")
  val text=if(content is String)content else if(content is JSONArray)(0 until content.length()).map {content.getJSONObject(it)}.filter {it.optString("type")=="text"}.joinToString("\n"){it.optString("text")} else ""
  if(role=="user"){ids.clear();output.add(Bubble(i,true,text))}
  if(role=="toolResult")runCatching {
   val result=JSONObject(text)
   result.optJSONArray("entries")?.let {rows->for(j in 0 until rows.length())ids.add(rows.getJSONObject(j).getString("id"))}
   if(result.has("id")&&result.has("amountMinor"))ids.add(result.getString("id"))
  }
  if(role=="assistant"&&text.isNotBlank()&&message.optString("stopReason")!in setOf("error","aborted","toolUse"))output.add(Bubble(i,false,text,ids.toList().take(8)))
 }
 return output
}

@Composable fun ChatScreen(app:LedgerApplication,onEntry:(LedgerEntry)->Unit,onSettings:()->Unit) {
 var session by rememberSaveable {mutableStateOf(app.settings.preferences.getString("chat_session",null)?:"chat:${UUID.randomUUID()}")}
 var input by rememberSaveable {mutableStateOf("")}
 var history by remember {mutableStateOf(false)}
 val all by app.database.dao().conversations().collectAsState(emptyList())
 val flow=remember(session){app.database.dao().observeRun(session)}
 val run by flow.collectAsState(null)
 val state by app.conversation.state.collectAsState()
 val busy=state.busy&&state.session==session
 val rows=remember(run?.messages){bubbles(run?.messages?:"[]")}
 val list=rememberLazyListState();val scope=rememberCoroutineScope()
 LaunchedEffect(session){app.settings.preferences.edit().putString("chat_session",session).apply()}
 LaunchedEffect(rows.size,busy){if(rows.isNotEmpty())list.animateScrollToItem(rows.size-1)}
 Column(Modifier.fillMaxSize()) {
  Row(Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=18.dp),verticalAlignment=Alignment.CenterVertically) {
   Column(Modifier.weight(1f)){Text("对话",fontSize=28.sp,fontWeight=FontWeight.SemiBold)}
   TextButton(onClick={history=true}){Text("历史")}
   TextButton(enabled=!state.busy,onClick={session="chat:${UUID.randomUUID()}";input=""}){Text("新对话")}
  }
  LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
   if(rows.isEmpty())item {
    Column(Modifier.padding(top=28.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
     Text("账本对话",fontSize=26.sp,fontWeight=FontWeight.Medium)
     listOf("这个月花了多少？","最近有哪些待核对的账？","帮我找最近的退款").forEach {prompt->OutlinedButton(onClick={input=prompt},shape=RoundedCornerShape(16.dp)){Text(prompt)}}
     if(app.settings.model()==null)Button(onClick=onSettings){Text("配置模型")}
    }
   }
   items(rows,key={it.key}) {bubble->
    if(bubble.user)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
     Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=RoundedCornerShape(20.dp,20.dp,4.dp,20.dp),modifier=Modifier.widthIn(max=320.dp)) {
      Text(bubble.text,Modifier.padding(16.dp),fontSize=16.sp,lineHeight=25.sp)
     }
    } else Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
     MarkdownText(bubble.text,Modifier.fillMaxWidth())
     bubble.entryIds.forEach {id->
      var entry by remember(id){mutableStateOf<LedgerEntry?>(null)}
      LaunchedEffect(id,run?.updatedAt){entry=app.database.book().entry(id)}
      entry?.let {e->Surface(onClick={onEntry(e)},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainer) {
       Row(Modifier.fillMaxWidth().padding(14.dp)){Text(e.merchant?:"账目",Modifier.weight(1f));Text("¥ ${Money.display(e.amountMinor)}",fontWeight=FontWeight.Medium)}
      }}
     }
    }
   }
   if(busy)item {Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Text(state.status,color=MaterialTheme.colorScheme.primary)}}
   if(!busy&&state.session==session&&state.error!=null)item {Text(state.error!!,color=MaterialTheme.colorScheme.error)}
   if(!busy&&run!=null&&run?.state!="complete")item {
    Row(verticalAlignment=Alignment.CenterVertically){Text("上次回复尚未完成",Modifier.weight(1f));TextButton(enabled=!state.busy,onClick={app.conversation.send(session,ConversationController.lastUser(run!!.messages),true)}){Text("继续")}}
   }
  }
  Surface(color=MaterialTheme.colorScheme.surfaceContainerLow,tonalElevation=1.dp) {
   Row(Modifier.fillMaxWidth().imePadding().padding(16.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
    OutlinedTextField(input,{input=it},modifier=Modifier.weight(1f),placeholder={Text("问账、记一笔，或纠正账目")},maxLines=5,shape=RoundedCornerShape(24.dp))
    FilledIconButton(onClick={if(busy)app.conversation.cancel()else{app.conversation.send(session,input);input=""}},enabled=busy||(!state.busy&&input.isNotBlank()&&(run==null||run?.state=="complete")),modifier=Modifier.padding(bottom=5.dp).size(48.dp)) {Text(if(busy)"■" else "↑",fontSize=24.sp)}
   }
  }
 }
 if(history)AlertDialog(onDismissRequest={history=false},title={Text("历史对话")},text={LazyColumn {
  if(all.isEmpty())item{Text("暂无对话")}
  items(all,key={it.id}) {conversation->
   val title=remember(conversation.messages){bubbles(conversation.messages).firstOrNull {it.user}?.text?:"未完成的对话"}
   Text(title,Modifier.fillMaxWidth().clickable {session=conversation.id;history=false}.padding(vertical=16.dp),maxLines=2)
  }
 }},confirmButton={TextButton(onClick={history=false}){Text("关闭")}})
}
