package dev.localledger.data

import android.content.Context
import androidx.room.*
import dev.localledger.security.Vault
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "agent_runs")
data class AgentRun(@PrimaryKey val id: String, val messages: String = "[]", val state: String = "pending", val updatedAt: Long = System.currentTimeMillis())
@Entity(tableName = "tool_receipts", primaryKeys = ["sessionId", "callId"])
data class ToolReceipt(val sessionId: String, val callId: String, val result: String)
@Entity(tableName = "probe_records")
data class ProbeRecord(@PrimaryKey val id: String, val amountMinor: Long, val label: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM agent_runs WHERE id LIKE 'chat:%' OR id='chat' ORDER BY updatedAt DESC") fun conversations(): kotlinx.coroutines.flow.Flow<List<AgentRun>>
    @Query("SELECT * FROM agent_runs WHERE id=:id") fun observeRun(id:String): kotlinx.coroutines.flow.Flow<AgentRun?>

    @Query("SELECT * FROM agent_runs WHERE id = :id") suspend fun run(id: String): AgentRun?
    @Upsert suspend fun saveRun(run: AgentRun)
    @Query("SELECT * FROM tool_receipts WHERE sessionId = :session AND callId = :call") suspend fun receipt(session: String, call: String): ToolReceipt?
    @Insert suspend fun saveReceipt(receipt: ToolReceipt)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun saveProbe(record: ProbeRecord): Long
    @Query("SELECT * FROM probe_records WHERE id = :id") suspend fun probe(id: String): ProbeRecord?
    @Query("SELECT COUNT(*) FROM probe_records WHERE id = :id") suspend fun probeCount(id: String): Int
}

@Database(entities = [AgentRun::class, ToolReceipt::class, ProbeRecord::class, Account::class, LedgerEntry::class, OrderRecord::class, OrderItem::class, PaymentAllocation::class, Evidence::class, Category::class, CategoryRule::class, Correction::class, LedgerJob::class, Audit::class, EntryEvidence::class, OrderCharge::class], views = [SpendingPart::class], version = 3, exportSchema = true)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao
    abstract fun book(): BookDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1,2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                BookSchema.create(db)
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2,3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS entry_evidence (entryId TEXT NOT NULL, evidenceId TEXT NOT NULL, PRIMARY KEY(entryId,evidenceId))")
                db.execSQL("INSERT OR IGNORE INTO entry_evidence SELECT id,evidenceId FROM transactions")
                db.execSQL("INSERT OR IGNORE INTO entry_evidence SELECT entryId,evidenceId FROM allocations")
                db.execSQL("CREATE TABLE IF NOT EXISTS order_charges (orderId TEXT NOT NULL, kind TEXT NOT NULL, amountMinor INTEGER NOT NULL, evidenceId TEXT NOT NULL, PRIMARY KEY(orderId,kind))")
                db.execSQL("CREATE VIEW `spending_parts` AS " + SPENDING_VIEW)
            }
        }
        fun open(context: Context, name: String = "ledger.db"): LedgerDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, LedgerDatabase::class.java, name)
                .openHelperFactory(SupportOpenHelperFactory(Vault(context).databaseKey())).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
        }
    }
}
