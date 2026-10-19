package dev.localledger.agent

import dev.localledger.LedgerApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/** Runs survive navigation; the encrypted Pi transcript is the source of conversation history. */
class ConversationController(private val app:LedgerApplication) {
 data class State(val session:String?=null,val busy:Boolean=false,val status:String="",val error:String?=null)
 val state=MutableStateFlow(State())
 private var job:Job?=null
 fun send(session:String,text:String,resume:Boolean=false) {
  if(job?.isActive==true)return
  state.value=State(session,true,"正在查看账本")
  job=app.scope.launch {
   try {ChatAgent(app).ask(text,session,resume);state.value=State(session)}
   catch(_:CancellationException){state.value=State(session,error="已停止。已完成的记账操作保留在修改记录中。")}
   catch(e:Exception){state.value=State(session,error=e.message?:"暂时无法回复，请重试")}
  }
 }
 fun cancel(){job?.cancel()}
 fun progress(session:String,name:String) {if(state.value.session==session) state.value=state.value.copy(status=when(name){"summarize"->"正在计算统计";"query_entries","read_entry"->"正在查找账目";"create_entry"->"正在记账";"correct_entry"->"正在修改账目";else->"正在整理结果"})}
 companion object {
  fun lastUser(messages:String):String {
   val array=JSONArray(messages)
   for(i in array.length()-1 downTo 0) {
    val item=array.getJSONObject(i)
    if(item.optString("role")=="user") {
     val content=item.opt("content")
     if(content is String)return content
     if(content is JSONArray)return (0 until content.length()).joinToString("\n"){content.getJSONObject(it).optString("text")}
    }
   }
   return ""
  }
 }
}
