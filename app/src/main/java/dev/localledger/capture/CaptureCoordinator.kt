package dev.localledger.capture
import dev.localledger.LedgerApplication
import dev.localledger.agent.AnalysisWorker
import androidx.room.withTransaction
import dev.localledger.data.*

class CaptureCoordinator(private val app:LedgerApplication) {
 suspend fun accept(pkg:String,channel:String,key:String,text:String,time:Long=System.currentTimeMillis()):String? {
  if(channel!="share" && pkg !in app.settings.allowedPackages()) return null
  val candidate=(if(channel=="notification")ReceiptParser.parseNotification(text) else ReceiptParser.parse(text))?:run {app.captureDiagnostics.record(pkg,channel,CaptureResult.NO_FIELDS);return null}
  try {
   val id=app.repository.ingest(pkg,channel,key,time,candidate)
   app.captureDiagnostics.record(pkg,channel,when {
    id==null->CaptureResult.DUPLICATE
    !candidate.confirmed->CaptureResult.UNCONFIRMED
    candidate.amountMinor==null->CaptureResult.NEED_AMOUNT
    else->CaptureResult.SAVED
   })
   if(id!=null)AnalysisWorker.enqueue(app,id)
   return id
  } catch(e:kotlinx.coroutines.CancellationException){throw e}
  catch(e:Exception){app.captureDiagnostics.record(pkg,channel,CaptureResult.FAILED);throw e}
 }
 suspend fun enhance(id:String,result:OcrReceipt) {
  val book=app.database.book();val ev=book.evidence(id)?:return
  if(ev.channel!="share" && ev.sourcePackage !in app.settings.allowedPackages())return
  val candidate=ReceiptParser.parse(result.text)?:return
  val safe=candidate.text
  val combined=(ev.text.lines()+safe.lines()).distinct().joinToString("\n").take(8000)
  app.database.withTransaction {
   book.updateEvidenceText(id,combined)
   if(safe.contains("商品")) {
    val entries=book.forEvidence(id)
    val orderId=entries.firstOrNull()?.orderId?:"order:${ReceiptParser.fingerprint(ev.sourcePackage+":"+(candidate.orderId?:ev.sourceKey))}"
    if(book.order(orderId)==null)book.saveOrder(OrderRecord(orderId,ev.sourcePackage,candidate.orderId,candidate.merchant,entries.singleOrNull()?.amountMinor,"paid",id))
    for(entry in entries) {
     if(entry.orderId==null)book.updateEntry(entry.copy(orderId=orderId,revision=entry.revision+1))
     book.saveAllocation(PaymentAllocation(entry.id,orderId,entry.amountMinor,id))
    }
   }
  }
  val image=result.redactedImage
  if(image!=null) {
  val relative="evidence/$id.enc"
  java.io.File(app.filesDir,"evidence").mkdirs()
  java.io.File(app.filesDir,relative).writeBytes(app.vault.seal(image))
  book.attachImage(id,relative,System.currentTimeMillis()+7L*24*60*60*1000,combined)
  }
  book.job(id)?.let {book.saveJob(it.copy(state="pending",updatedAt=System.currentTimeMillis()))}
  AnalysisWorker.enqueue(app,id)
 }
}
