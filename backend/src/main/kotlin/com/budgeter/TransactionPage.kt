package com.budgeter

// Shared with AnalysisPage.kt: color alone shouldn't be the only signal for
// money in vs. money out, so positive amounts get an explicit "+" alongside
// the sign Double's own formatting already puts on negatives.
fun formatSignedAmount(amount: Double): String = (if (amount >= 0) "+" else "") + "%.2f".format(amount)

fun amountClass(amount: Double): String = if (amount < 0) "transaction-amount-negative" else "transaction-amount-positive"

// FreeMarker's default object wrapper can't stringify java.time.LocalDate
// (or Kotlin data classes generally) on its own, so - same as foodie's
// *PageModel builders - this flattens each Transaction into a plain
// Map<String, Any?> of already-display-ready values before it reaches the
// template.
fun transactionsPageModel(transactions: List<Transaction>, message: String?, error: String?): Map<String, Any?> = mapOf(
    "transactions" to transactions.map {
        mapOf(
            "date" to it.date.toString(),
            "description" to it.description,
            "amount" to formatSignedAmount(it.amount),
            "amountClass" to amountClass(it.amount)
        )
    },
    "message" to message,
    "error" to error
)
