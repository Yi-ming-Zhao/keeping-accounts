package dev.localledger
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.capture.ReceiptParser
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** Emulator-only synthetic preview fixture. Never runs on a physical phone. */
@RunWith(AndroidJUnit4::class)
class UiPreviewTest {
 @Test fun seedSyntheticPreview()=runBlocking {
  check(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk_gphone"))
  val app=ApplicationProvider.getApplicationContext<LedgerApplication>()
  app.database.clearAllTables()
  val samples=listOf(Triple("瑞幸咖啡","18.50","餐饮"),Triple("青光文具","6.38","教育"),Triple("地铁","3.00","交通"),Triple("校园食堂","16.80","餐饮"),Triple("便利店","24.50","生活"))
  for((index,row) in samples.withIndex()) {
   val timestamp=LocalDate.now().minusDays((index/2).toLong()).atTime(12,30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
   val id=app.repository.ingest("manual","share","preview-$index",timestamp,ReceiptParser.parse("支付成功\n金额：${row.second}\n商户：${row.first}")!!)
   if(id!=null) {val entry=app.database.book().forEvidence(id).single();app.repository.correct(entry.id,row.third,null);app.database.book().job(id)?.let{app.database.book().saveJob(it.copy(state="complete"))}}
  }
  val deferred=app.repository.ingest("com.xunmeng.pinduoduo","share","preview-deferred",System.currentTimeMillis(),ReceiptParser.parse("订单详情\n先用后付\n下单成功\n待扣款金额：28.80\n店铺：日用生活馆\n商品名称：保温杯\n订单号：PREVIEWPDD123")!!)
  deferred?.let{app.database.book().saveJob(dev.localledger.data.LedgerJob(it,it,"complete"))}
  app.settings.preferences.edit().putLong("monthly_budget",200000).commit()
  Unit
 }
}
