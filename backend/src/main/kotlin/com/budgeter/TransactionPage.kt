package com.budgeter

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
            "amount" to "%.2f".format(it.amount),
            "amountClass" to if (it.amount < 0) "transaction-amount-negative" else "transaction-amount-positive"
        )
    },
    "message" to message,
    "error" to error
)
