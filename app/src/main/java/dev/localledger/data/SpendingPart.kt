package dev.localledger.data

import androidx.room.DatabaseView

// Item categories affect totals only when the evidence covers the entire paid amount.
// Otherwise keep the transaction amount intact; never distribute an unknown discount.
const val ITEM_COVERAGE = "t.kind='expense' AND t.userLocked=0 AND t.orderId IS NOT NULL AND (SELECT COUNT(*) FROM transactions x WHERE x.orderId=t.orderId AND x.kind='expense')=1 AND COALESCE((SELECT SUM(i.totalMinor) FROM items i WHERE i.orderId=t.orderId),-1)=t.amountMinor AND NOT EXISTS(SELECT 1 FROM items i WHERE i.orderId=t.orderId AND i.totalMinor IS NULL)"
const val SPENDING_VIEW = "SELECT t.id AS entryId,t.occurredAt,t.currency,t.merchant,t.sourcePackage,t.kind,t.orderId,COALESCE(i.category,'未分类') AS category,i.totalMinor AS amountMinor FROM transactions t JOIN items i ON i.orderId=t.orderId WHERE " + ITEM_COVERAGE + " UNION ALL SELECT t.id AS entryId,t.occurredAt,t.currency,t.merchant,t.sourcePackage,t.kind,t.orderId,COALESCE(t.category,'未分类') AS category,CASE WHEN t.kind='refund' THEN -t.amountMinor ELSE t.amountMinor END AS amountMinor FROM transactions t WHERE t.kind IN ('expense','fee','refund') AND NOT (" + ITEM_COVERAGE + ")"
@DatabaseView(value=SPENDING_VIEW,viewName="spending_parts")
data class SpendingPart(val entryId:String,val occurredAt:Long,val currency:String,val merchant:String?,val sourcePackage:String,val kind:String,val orderId:String?,val category:String,val amountMinor:Long?)
