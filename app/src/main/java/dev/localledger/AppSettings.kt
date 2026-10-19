package dev.localledger
import android.content.Context
import dev.localledger.agent.ModelConfig
import dev.localledger.security.Vault
import org.json.JSONObject

class AppSettings(context:Context) {
 val preferences=context.getSharedPreferences("settings",Context.MODE_PRIVATE)
 private val vault=Vault(context)
 fun allowedPackages():Set<String> = preferences.getStringSet("allowed_packages",emptySet())!!.toSet()
 fun allow(pkg:String,enabled:Boolean) { val set=allowedPackages().toMutableSet();if(enabled)set.add(pkg)else set.remove(pkg);preferences.edit().putStringSet("allowed_packages",set).apply() }
 fun model():ModelConfig? = vault.get("model_config")?.let { ModelConfig.fromJson(it) }
 fun saveModel(config:ModelConfig) { config.checked();vault.put("model_config",JSONObject().put("baseUrl",config.baseUrl).put("model",config.model).put("apiKey",config.apiKey).put("images",config.images).toString()) }
 var screenshots:Boolean
  get()=preferences.getBoolean("screenshots",false)
  set(value){preferences.edit().putBoolean("screenshots",value).apply()}
 companion object {
  val applications=linkedMapOf("com.tencent.mm" to "微信","com.eg.android.AlipayGphone" to "支付宝","com.sankuai.meituan" to "美团","com.taobao.taobao" to "淘宝","com.ss.android.ugc.aweme" to "抖音","com.jingdong.app.mall" to "京东","com.xunmeng.pinduoduo" to "拼多多")
  fun label(pkg:String)=applications[pkg]?:when(pkg){"manual"->"手动";"shared"->"分享导入";"dev.localledger.fixtures"->"交易样本";else->pkg}
 }
}
