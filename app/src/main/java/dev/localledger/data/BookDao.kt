package dev.localledger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class CategoryTotal(val category:String,val amountMinor:Long)

data class DayTotal(val day:String,val amountMinor:Long)
data class PeriodSummary(val expenses:Long,val refunds:Long,val income:Long,val deferred:Long,val count:Int)
@Dao interface BookDao {
 @Query("SELECT COALESCE(SUM(CASE WHEN kind IN ('expense','fee') THEN amountMinor ELSE 0 END),0) AS expenses,COALESCE(SUM(CASE WHEN kind='refund' THEN amountMinor ELSE 0 END),0) AS refunds,COALESCE(SUM(CASE WHEN kind='income' THEN amountMinor ELSE 0 END),0) AS income,COALESCE(SUM(CASE WHEN kind='deferred' THEN amountMinor ELSE 0 END),0) AS `deferred`,COUNT(*) AS count FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND currency='CNY'") fun summary(start:Long,end:Long):Flow<PeriodSummary>
 @Query("SELECT * FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND (:filter IS NULL OR kind=:filter OR (:filter='review' AND reviewReason IS NOT NULL AND kind!='deferred')) AND (:query='' OR merchant LIKE '%'||:query||'%' OR note LIKE '%'||:query||'%' OR category LIKE '%'||:query||'%' OR orderId IN (SELECT orderId FROM items WHERE name LIKE '%'||:query||'%')) ORDER BY occurredAt DESC LIMIT :limit") fun filtered(start:Long,end:Long,query:String,filter:String?,limit:Int):Flow<List<LedgerEntry>>
 @Query("SELECT strftime('%d',occurredAt/1000,'unixepoch','localtime') AS day, SUM(CASE WHEN kind IN ('expense','fee') THEN amountMinor WHEN kind='refund' THEN -amountMinor ELSE 0 END) AS amountMinor FROM transactions WHERE occurredAt>=:start AND occurredAt<:end GROUP BY day ORDER BY day") fun daily(start:Long,end:Long):Flow<List<DayTotal>>

 @Query("SELECT COALESCE(SUM(i.totalMinor),0) FROM items i JOIN transactions t ON t.orderId=i.orderId WHERE " + ITEM_COVERAGE + " AND t.occurredAt>=:start AND t.occurredAt<:end AND t.currency='CNY' AND i.name LIKE '%' || :product || '%' AND (:category IS NULL OR i.category=:category) AND (:merchant IS NULL OR t.merchant LIKE '%' || :merchant || '%') AND (:source IS NULL OR t.sourcePackage=:source)") suspend fun productTotal(start:Long,end:Long,product:String,category:String?,merchant:String?,source:String?):Long
 @Query("SELECT COUNT(DISTINCT t.orderId) FROM transactions t JOIN items i ON i.orderId=t.orderId WHERE t.kind='expense' AND NOT (" + ITEM_COVERAGE + ") AND t.occurredAt>=:start AND t.occurredAt<:end AND t.currency='CNY' AND i.name LIKE '%' || :product || '%' AND (:merchant IS NULL OR t.merchant LIKE '%' || :merchant || '%') AND (:source IS NULL OR t.sourcePackage=:source)") suspend fun productUnknownOrders(start:Long,end:Long,product:String,merchant:String?,source:String?):Int
 @Query("SELECT COALESCE(SUM(CASE WHEN fromAccountId=:id THEN amountMinor ELSE 0 END),0)-COALESCE(SUM(CASE WHEN toAccountId=:id THEN amountMinor ELSE 0 END),0) FROM transactions WHERE occurredAt<:end AND kind NOT IN ('transfer_candidate','deferred','cancelled')") suspend fun accountOutflow(id:String,end:Long):Long
 @Query("UPDATE evidence SET text=:text WHERE id=:id") suspend fun updateEvidenceText(id:String,text:String)
 @Query("SELECT COALESCE(SUM(CASE WHEN kind IN ('expense','fee') THEN amountMinor WHEN kind='refund' THEN -amountMinor ELSE 0 END),0) FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND currency='CNY'") fun observeTotal(start:Long,end:Long):Flow<Long>
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun linkEvidence(value:EntryEvidence)
 @Query("SELECT * FROM evidence WHERE id IN (SELECT evidenceId FROM entry_evidence WHERE entryId=:id) ORDER BY capturedAt") suspend fun evidenceForEntry(id:String):List<Evidence>
 @Upsert suspend fun saveCharge(value:OrderCharge)
 @Query("SELECT * FROM order_charges WHERE orderId=:id") suspend fun charges(id:String):List<OrderCharge>
 @Query("SELECT COUNT(*) FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND reviewReason IS NOT NULL AND kind!='deferred'") fun observeReviewCount(start:Long,end:Long):Flow<Int>
 @Query("SELECT category,SUM(amountMinor) AS amountMinor FROM spending_parts WHERE occurredAt>=:start AND occurredAt<:end AND currency='CNY' GROUP BY category ORDER BY amountMinor DESC") fun categoryTotals(start:Long,end:Long):Flow<List<CategoryTotal>>
 @Query("SELECT * FROM transactions WHERE originalEntryId=:id") suspend fun refunds(id:String):List<LedgerEntry>
 @Query("SELECT * FROM transactions WHERE occurredAt>=:start AND occurredAt<:end ORDER BY occurredAt DESC LIMIT :limit") fun observeEntries(start:Long,end:Long,limit:Int):Flow<List<LedgerEntry>>
 @Query("SELECT * FROM transactions WHERE id=:id") suspend fun entry(id:String):LedgerEntry?
 @Query("SELECT * FROM transactions WHERE identityKey=:identity") suspend fun byIdentity(identity:String):LedgerEntry?
 @Query("SELECT * FROM transactions WHERE evidenceId=:id OR id IN (SELECT entryId FROM entry_evidence WHERE evidenceId=:id) OR id IN (SELECT entryId FROM allocations WHERE evidenceId=:id)") suspend fun forEvidence(id:String):List<LedgerEntry>
 @Query("SELECT * FROM transactions WHERE orderId=:id") suspend fun forOrder(id:String):List<LedgerEntry>
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertEntry(value:LedgerEntry):Long
 @Update suspend fun updateEntry(value:LedgerEntry)
 @Query("SELECT * FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND currency=:currency AND (:merchant IS NULL OR merchant LIKE '%' || :merchant || '%') AND (:category IS NULL OR (kind NOT IN ('expense','refund','fee') AND category=:category) OR id IN (SELECT entryId FROM spending_parts WHERE category=:category)) AND (:kind IS NULL OR kind=:kind) AND (:source IS NULL OR sourcePackage=:source) AND (:product IS NULL OR orderId IN (SELECT orderId FROM items WHERE name LIKE '%' || :product || '%')) ORDER BY occurredAt DESC LIMIT :limit OFFSET :offset")
 suspend fun search(start:Long,end:Long,currency:String,merchant:String?,category:String?,kind:String?,source:String?,product:String?,limit:Int=50,offset:Int=0):List<LedgerEntry>
 @Query("SELECT COALESCE(SUM(amountMinor),0) FROM spending_parts WHERE occurredAt>=:start AND occurredAt<:end AND currency=:currency AND (:category IS NULL OR category=:category) AND (:merchant IS NULL OR merchant LIKE '%' || :merchant || '%') AND (:source IS NULL OR sourcePackage=:source) AND (:kind IS NULL OR kind=:kind) AND (:product IS NULL OR orderId IN (SELECT orderId FROM items WHERE name LIKE '%' || :product || '%'))")
 suspend fun total(start:Long,end:Long,currency:String="CNY",category:String?=null,merchant:String?=null,source:String?=null,kind:String?=null,product:String?=null):Long
 @Query("SELECT COALESCE(SUM(CASE WHEN kind IN ('expense','fee') THEN amountMinor ELSE 0 END),0) AS expenses,COALESCE(SUM(CASE WHEN kind='refund' THEN amountMinor ELSE 0 END),0) AS refunds,COALESCE(SUM(CASE WHEN kind='income' THEN amountMinor ELSE 0 END),0) AS income,COALESCE(SUM(CASE WHEN kind='deferred' THEN amountMinor ELSE 0 END),0) AS `deferred`,COUNT(*) AS count FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND currency='CNY' AND (:merchant IS NULL OR merchant LIKE '%'||:merchant||'%') AND (:source IS NULL OR sourcePackage=:source)") suspend fun periodSummary(start:Long,end:Long,merchant:String?,source:String?):PeriodSummary
 @Query("SELECT COALESCE(SUM(amountMinor),0) FROM transactions WHERE occurredAt>=:start AND occurredAt<:end AND kind=:kind AND currency='CNY' AND (:category IS NULL OR category=:category) AND (:merchant IS NULL OR merchant LIKE '%'||:merchant||'%') AND (:source IS NULL OR sourcePackage=:source)") suspend fun kindTotal(start:Long,end:Long,kind:String,category:String?,merchant:String?,source:String?):Long
 @Query("SELECT * FROM evidence WHERE id NOT IN (SELECT evidenceId FROM entry_evidence) ORDER BY capturedAt DESC LIMIT 100") fun unmatchedEvidence():Flow<List<Evidence>>
 @Query("SELECT * FROM evidence WHERE id=:id") suspend fun evidence(id:String):Evidence?
 @Query("SELECT * FROM evidence WHERE sourceKey=:key") suspend fun evidenceBySource(key:String):Evidence?
 @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insertEvidence(value:Evidence):Long
 @Query("SELECT * FROM evidence WHERE capturedAt>=:start AND capturedAt<:end ORDER BY capturedAt DESC LIMIT :limit") suspend fun evidenceSearch(start:Long,end:Long,limit:Int=10):List<Evidence>
 @Query("SELECT * FROM orders WHERE id=:id") suspend fun order(id:String):OrderRecord?
 @Query("SELECT * FROM orders WHERE externalId=:id") suspend fun ordersByExternalId(id:String):List<OrderRecord>
 @Query("SELECT * FROM orders WHERE evidenceId=:id") suspend fun ordersForEvidence(id:String):List<OrderRecord>
 @Query("SELECT COALESCE(SUM(amountMinor),0) FROM transactions WHERE kind='refund' AND originalEntryId=:original AND id!=:excluding") suspend fun refunded(original:String,excluding:String):Long
 @Upsert suspend fun saveOrder(value:OrderRecord)
 @Query("SELECT * FROM items WHERE orderId=:order") suspend fun items(order:String):List<OrderItem>
 @Upsert suspend fun saveItem(value:OrderItem)
 @Upsert suspend fun saveAllocation(value:PaymentAllocation)
 @Query("SELECT * FROM allocations WHERE entryId=:id") suspend fun allocations(id:String):List<PaymentAllocation>
 @Upsert suspend fun saveAccount(value:Account)
 @Query("SELECT * FROM accounts") suspend fun accounts():List<Account>
 @Upsert suspend fun saveCategory(value:Category)
 @Query("SELECT * FROM categories ORDER BY name") suspend fun categories():List<Category>
 @Upsert suspend fun saveRule(value:CategoryRule)
 @Query("SELECT * FROM rules ORDER BY createdAt DESC") suspend fun rules():List<CategoryRule>
 @Query("DELETE FROM rules WHERE id=:id") suspend fun deleteRule(id:String)
 @Insert suspend fun saveCorrection(value:Correction)
 @Query("SELECT * FROM corrections WHERE entryId=:id ORDER BY createdAt DESC") suspend fun corrections(id:String):List<Correction>
 @Query("SELECT * FROM corrections WHERE id=:id") suspend fun correction(id:String):Correction?
 @Query("UPDATE corrections SET undone=1 WHERE id=:id") suspend fun markUndone(id:String)
 @Insert suspend fun saveAudit(value:Audit)
 @Query("SELECT * FROM audit WHERE entryId=:id ORDER BY createdAt DESC LIMIT 50") suspend fun audits(id:String):List<Audit>
 @Upsert suspend fun saveJob(value:LedgerJob)
 @Query("SELECT * FROM jobs WHERE id=:id") suspend fun job(id:String):LedgerJob?
 @Query("SELECT * FROM jobs WHERE state IN ('pending','waiting_config','processing')") suspend fun pendingJobs():List<LedgerJob>
 @Query("SELECT * FROM jobs WHERE state!='complete' ORDER BY updatedAt DESC") fun observeJobs():Flow<List<LedgerJob>>
 @Query("UPDATE evidence SET imagePath=:path,imageExpiresAt=:expires,text=:text WHERE id=:id") suspend fun attachImage(id:String,path:String,expires:Long,text:String)
 @Query("SELECT * FROM evidence WHERE imagePath IS NOT NULL") suspend fun allImages():List<Evidence>
 @Query("SELECT * FROM evidence WHERE imageExpiresAt IS NOT NULL AND imageExpiresAt<:time") suspend fun expiredImages(time:Long):List<Evidence>
 @Query("UPDATE evidence SET imagePath=NULL,imageExpiresAt=NULL WHERE id=:id") suspend fun clearImage(id:String)
}
