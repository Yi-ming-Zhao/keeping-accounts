package dev.localledger.data
import androidx.sqlite.db.SupportSQLiteDatabase
/** Explicit migration from the independently verified Pi probe database. */
object BookSchema {
 fun create(db:SupportSQLiteDatabase) {
  val tables=listOf(
   "accounts" to "id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, currency TEXT NOT NULL",
   "transactions" to "id TEXT NOT NULL PRIMARY KEY, identityKey TEXT NOT NULL, kind TEXT NOT NULL, amountMinor INTEGER NOT NULL, currency TEXT NOT NULL, merchant TEXT, occurredAt INTEGER NOT NULL, capturedAt INTEGER NOT NULL, sourcePackage TEXT NOT NULL, orderId TEXT, originalEntryId TEXT, fromAccountId TEXT, toAccountId TEXT, category TEXT, note TEXT, evidenceId TEXT NOT NULL, reviewReason TEXT, userLocked INTEGER NOT NULL, revision INTEGER NOT NULL",
   "orders" to "id TEXT NOT NULL PRIMARY KEY, sourcePackage TEXT NOT NULL, externalId TEXT, merchant TEXT, totalMinor INTEGER, state TEXT NOT NULL, evidenceId TEXT NOT NULL",
   "items" to "id TEXT NOT NULL PRIMARY KEY, orderId TEXT NOT NULL, name TEXT NOT NULL, quantity TEXT, unitPriceMinor INTEGER, totalMinor INTEGER, category TEXT, evidenceId TEXT NOT NULL",
   "allocations" to "entryId TEXT NOT NULL, orderId TEXT NOT NULL, amountMinor INTEGER NOT NULL, evidenceId TEXT NOT NULL, PRIMARY KEY(entryId,orderId)",
   "evidence" to "id TEXT NOT NULL PRIMARY KEY, sourceKey TEXT NOT NULL, sourcePackage TEXT NOT NULL, channel TEXT NOT NULL, capturedAt INTEGER NOT NULL, text TEXT NOT NULL, imagePath TEXT, imageExpiresAt INTEGER",
   "categories" to "name TEXT NOT NULL PRIMARY KEY",
   "rules" to "id TEXT NOT NULL PRIMARY KEY, merchantEquals TEXT NOT NULL, category TEXT NOT NULL, createdAt INTEGER NOT NULL",
   "corrections" to "id TEXT NOT NULL PRIMARY KEY, entryId TEXT NOT NULL, beforeJson TEXT NOT NULL, afterJson TEXT NOT NULL, createdAt INTEGER NOT NULL, undone INTEGER NOT NULL",
   "jobs" to "id TEXT NOT NULL PRIMARY KEY, evidenceId TEXT NOT NULL, state TEXT NOT NULL, attempts INTEGER NOT NULL, error TEXT, updatedAt INTEGER NOT NULL",
   "audit" to "id TEXT NOT NULL PRIMARY KEY, entryId TEXT, action TEXT NOT NULL, details TEXT NOT NULL, createdAt INTEGER NOT NULL"
  )
  tables.forEach { (name,columns)->db.execSQL("CREATE TABLE IF NOT EXISTS `$name` ($columns)") }
  listOf("transactions" to "occurredAt","transactions" to "orderId","items" to "orderId","corrections" to "entryId","jobs" to "state","audit" to "entryId").forEach { (t,c)->db.execSQL("CREATE INDEX IF NOT EXISTS `index_${t}_${c}` ON `$t` (`$c`)") }
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_transactions_identityKey ON transactions(identityKey)")
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_orders_sourcePackage_externalId ON orders(sourcePackage,externalId)")
  db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_evidence_sourceKey ON evidence(sourceKey)")
 }
}
