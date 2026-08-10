package com.budgeter

// Shared with AnalysisPage.kt: color alone shouldn't be the only signal for
// money in vs. money out, so positive amounts get an explicit "+" alongside
// the sign Double's own formatting already puts on negatives.
fun formatSignedAmount(amount: Double): String = (if (amount >= 0) "+" else "") + "%.2f".format(amount)

fun amountClass(amount: Double): String = if (amount < 0) "transaction-amount-negative" else "transaction-amount-positive"

// FreeMarker's default object wrapper can't stringify java.time.LocalDate
// (or Kotlin data classes generally) on its own, so - same as foodie's
// *PageModel builders - this flattens a Transaction into a plain
// Map<String, Any?> of already-display-ready values before it reaches a
// template. Shared between transactionsPageModel and
// AnalysisPage.kt's analysisCategoryPageModel - both render the same
// date/description/account/amount row shape.
fun transactionRowModel(transaction: Transaction): Map<String, Any?> = mapOf(
    "id" to transaction.id,
    "date" to transaction.date.toString(),
    "description" to transaction.description,
    "accountType" to transaction.accountType.label,
    "amount" to formatSignedAmount(transaction.amount),
    "amountClass" to amountClass(transaction.amount)
)

fun transactionsPageModel(transactions: List<Transaction>, message: String?, error: String?): Map<String, Any?> = mapOf(
    "transactions" to transactions.map(::transactionRowModel),
    "message" to message,
    "error" to error
)
