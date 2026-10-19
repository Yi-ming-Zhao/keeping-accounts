package dev.localledger.data

import androidx.room.*

@Entity(tableName="accounts")
data class Account(@PrimaryKey val id:String,val name:String,val kind:String,val currency:String="CNY")
@Entity(tableName="transactions",indices=[Index(value=["identityKey"],unique=true),Index("occurredAt"),Index("orderId")])
data class LedgerEntry(
 @PrimaryKey val id:String,val identityKey:String,val kind:String,val amountMinor:Long,val currency:String="CNY",
 val merchant:String?=null,val occurredAt:Long,val capturedAt:Long=System.currentTimeMillis(),val sourcePackage:String,
 val orderId:String?=null,val originalEntryId:String?=null,val fromAccountId:String?=null,val toAccountId:String?=null,
 val category:String?=null,val note:String?=null,val evidenceId:String,val reviewReason:String?=null,
 val userLocked:Boolean=false,val revision:Int=0
)
@Entity(tableName="orders",indices=[Index(value=["sourcePackage","externalId"],unique=true)])
data class OrderRecord(@PrimaryKey val id:String,val sourcePackage:String,val externalId:String?,val merchant:String?,val totalMinor:Long?,val state:String,val evidenceId:String)
@Entity(tableName="items",indices=[Index("orderId")])
data class OrderItem(@PrimaryKey val id:String,val orderId:String,val name:String,val quantity:String?,val unitPriceMinor:Long?,val totalMinor:Long?,val category:String?,val evidenceId:String)
@Entity(tableName="allocations",primaryKeys=["entryId","orderId"])
data class PaymentAllocation(val entryId:String,val orderId:String,val amountMinor:Long,val evidenceId:String)
@Entity(tableName="evidence",indices=[Index(value=["sourceKey"],unique=true)])
data class Evidence(@PrimaryKey val id:String,val sourceKey:String,val sourcePackage:String,val channel:String,val capturedAt:Long,val text:String,val imagePath:String?=null,val imageExpiresAt:Long?=null)
@Entity(tableName="categories")
data class Category(@PrimaryKey val name:String)
@Entity(tableName="rules")
data class CategoryRule(@PrimaryKey val id:String,val merchantEquals:String,val category:String,val createdAt:Long=System.currentTimeMillis())
@Entity(tableName="corrections",indices=[Index("entryId")])
data class Correction(@PrimaryKey val id:String,val entryId:String,val beforeJson:String,val afterJson:String,val createdAt:Long=System.currentTimeMillis(),val undone:Boolean=false)
@Entity(tableName="jobs",indices=[Index("state")])
data class LedgerJob(@PrimaryKey val id:String,val evidenceId:String,val state:String="pending",val attempts:Int=0,val error:String?=null,val updatedAt:Long=System.currentTimeMillis())
@Entity(tableName="audit",indices=[Index("entryId")])
data class Audit(@PrimaryKey val id:String,val entryId:String?,val action:String,val details:String,val createdAt:Long=System.currentTimeMillis())

@Entity(tableName="entry_evidence",primaryKeys=["entryId","evidenceId"])
data class EntryEvidence(val entryId:String,val evidenceId:String)
@Entity(tableName="order_charges",primaryKeys=["orderId","kind"])
data class OrderCharge(val orderId:String,val kind:String,val amountMinor:Long,val evidenceId:String)
