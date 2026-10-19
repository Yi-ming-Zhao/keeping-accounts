package dev.localledger.capture
import android.content.Context
import androidx.work.*
import dev.localledger.LedgerApplication
import java.io.File
import java.util.concurrent.TimeUnit
class EvidenceCleanupWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
 override suspend fun doWork():Result {
  val app=applicationContext as LedgerApplication
  for(ev in app.database.book().expiredImages(System.currentTimeMillis())) {
   ev.imagePath?.let {relative->val file=File(app.filesDir,relative);if(file.canonicalPath.startsWith(File(app.filesDir,"evidence").canonicalPath+"/"))file.delete()}
   app.database.book().clearImage(ev.id)
  }
  return Result.success()
 }
 companion object {
  fun schedule(context:Context) {WorkManager.getInstance(context).enqueueUniquePeriodicWork("evidence-expiry",ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<EvidenceCleanupWorker>(1,TimeUnit.DAYS).build())}
 }
}
