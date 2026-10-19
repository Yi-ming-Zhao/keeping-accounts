package dev.localledger
import dev.localledger.ledger.Money
import org.junit.Assert.*
import org.junit.Test
class MoneyTest {
 @Test fun currencyNeverUsesFloatingPoint() {
  assertEquals(1234L,Money.parse("￥12.34"));assertEquals(100001L,Money.parse("1,000.01元"))
  assertNull(Money.parse("12.345"));assertNull(Money.parse("-1"));assertNull(Money.parse("约12"))
 }
 @Test fun repaymentAndTopupDoNotDoubleCountExpenses() {
  val purchase=Money.expenseImpact("expense",3000)
  val repayment=Money.expenseImpact("repayment",3000)
  val topup=Money.expenseImpact("transfer",5000)
  val partialRefund=Money.expenseImpact("refund",500)
  assertEquals(2500L,purchase+repayment+topup+partialRefund)
 }
}
