package dev.localledger.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.localledger.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ReleaseUpdates {
 const val REPOSITORY="https://github.com/Yi-ming-Zhao/keeping-accounts"
 data class Release(val tag:String,val notes:String,val url:String,val newer:Boolean)
 fun newer(tag:String,current:String):Boolean {
  fun parts(value:String)=value.removePrefix("v").split('.').map{it.toIntOrNull()?:-1}
  val a=parts(tag);val b=parts(current)
  if(a.size!=3||b.size!=3||a.any{it<0})return false
  for(i in 0..2){if(a[i]!=b[i])return a[i]>b[i]}
  return false
 }
 suspend fun check():Release=withContext(Dispatchers.IO) {
  val client=OkHttpClient.Builder().callTimeout(12,TimeUnit.SECONDS).followRedirects(false).build()
  client.newCall(Request.Builder().url("https://api.github.com/repos/Yi-ming-Zhao/keeping-accounts/releases/latest").header("Accept","application/vnd.github+json").header("User-Agent","QingZhang/${BuildConfig.VERSION_NAME}").build()).execute().use {response->
   check(response.isSuccessful){if(response.code==404)"还没有已发布的版本" else "暂时无法检查更新"}
   val source=response.body?.source()?:error("版本信息为空")
   val buffer=okio.Buffer();var total=0L
   while(true){val count=source.read(buffer,8192);if(count<0)break;total+=count;require(total<=262144){"版本信息过大"}}
   val bytes=buffer.readByteArray()
   val json=JSONObject(bytes.toString(Charsets.UTF_8));val tag=json.getString("tag_name")
   require(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) {"版本格式不正确"}
   Release(tag,json.optString("body").take(12000),"$REPOSITORY/releases/tag/$tag",newer(tag,BuildConfig.VERSION_NAME))
  }
 }
}

@Composable fun UpdatePanel() {
 val context=LocalContext.current;val scope=rememberCoroutineScope()
 var busy by remember{mutableStateOf(false)};var result by remember{mutableStateOf<ReleaseUpdates.Release?>(null)};var error by remember{mutableStateOf<String?>(null)}
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
  Text("版本 ${BuildConfig.VERSION_NAME}",style=MaterialTheme.typography.titleMedium)
  Row {
   TextButton(enabled=!busy,onClick={scope.launch{busy=true;error=null;try{result=ReleaseUpdates.check()}catch(_:Exception){error="检查失败，请稍后再试"}finally{busy=false}}}){Text(if(busy)"检查中" else "检查更新")}
   TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ReleaseUpdates.REPOSITORY)))}){Text("源码与发布记录")}
  }
  error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
 }
 result?.let{release->AlertDialog(onDismissRequest={result=null},title={Text(if(release.newer)"发现新版本 ${release.tag}" else "当前已是最新版本")},text={MarkdownText(release.notes,Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(release.url)));result=null}){Text(if(release.newer)"下载新版" else "发布详情")}},dismissButton={TextButton(onClick={result=null}){Text("关闭")}})}
}
