package dev.localledger
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.localledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeTest {
 private val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as LedgerApplication
 private fun version()=app.packageManager.getPackageInfo(app.packageName,0).versionName
 @Test fun seedOldRelease()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"));assertEquals(InstrumentationRegistry.getArguments().getString("oldVersion","0.1.0"),version())
  app.database.book().insertEvidence(Evidence("upgrade-proof","upgrade-proof","manual","share",1000,"支付成功\n金额：6.38"))
  app.database.book().insertEntry(LedgerEntry("upgrade-proof","upgrade-proof","expense",638,merchant="升级保留测试",occurredAt=1000,sourcePackage="manual",evidenceId="upgrade-proof",userLocked=true))
  app.settings.preferences.edit().putString("upgrade_sentinel","keep-me").commit()
  Unit
 }
 @Test fun newReleasePreservesOldBook()=runBlocking {
  check(android.os.Build.MODEL.contains("sdk_gphone"));assertEquals(InstrumentationRegistry.getArguments().getString("newVersion","0.2.0"),version())
  val entry=app.database.book().entry("upgrade-proof")!!
  assertEquals(638L,entry.amountMinor);assertEquals("升级保留测试",entry.merchant);assertTrue(entry.userLocked)
  assertEquals("keep-me",app.settings.preferences.getString("upgrade_sentinel",null))
  assertNotNull(app.database.book().evidence("upgrade-proof"))
 }
}
