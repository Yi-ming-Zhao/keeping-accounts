package dev.localledger

import android.app.Application
import dev.localledger.data.*
import dev.localledger.security.Vault
import dev.localledger.ledger.LedgerRepository
import dev.localledger.agent.AnalysisWorker
import kotlinx.coroutines.*

class LedgerApplication: Application() {
 val database by lazy { LedgerDatabase.open(this) }
 val vault by lazy { Vault(this) }
 val settings by lazy { AppSettings(this) }
 val captureDiagnostics by lazy { dev.localledger.capture.CaptureDiagnostics(this) }
 val repository by lazy { LedgerRepository(database,database.book()) }
 val conversation by lazy {dev.localledger.agent.ConversationController(this)}
 val maintenance=kotlinx.coroutines.sync.Mutex()
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO+CoroutineExceptionHandler { _, _ -> /* Never expose receipt or provider exception bodies to logs. Persisted jobs remain recoverable. */ })
 override fun onCreate() {
  super.onCreate()
  dev.localledger.capture.EvidenceCleanupWorker.schedule(this)
  scope.launch {
   listOf("餐饮","交通","购物","生活","住房","教育","医疗","娱乐","其他").forEach { database.book().saveCategory(Category(it)) }
   if(settings.preferences.getInt("parser_version",0)<2){repository.flagLegacyAmountConflicts();settings.preferences.edit().putInt("parser_version",2).apply()}
   AnalysisWorker.recover(this@LedgerApplication)
  }
 }
}
