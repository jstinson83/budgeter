package com.budgeter

// Same flattening rationale as TransactionPage.kt: FreeMarker's default
// object wrapper can't stringify Kotlin data classes/enums on its own, so
// this pre-formats everything the template needs.
fun analysisPageModel(
    periodTransactions: List<Transaction>,
    period: String,
    uncategorizedCount: Int,
    message: String?,
    error: String?
): Map<String, Any?> {
    val categoryTotals = periodTransactions
        .groupBy { it.category?.label ?: "Uncategorized" }
        .map { (label, transactions) -> Triple(label, transactions.sumOf { it.amount }, transactions.size) }
        .sortedBy { (_, total, _) -> total }
        .map { (label, total, count) ->
            mapOf(
                "category" to label,
                "total" to formatSignedAmount(total),
                "count" to count,
                "totalClass" to amountClass(total)
            )
        }

    return mapOf(
        "categoryTotals" to categoryTotals,
        "period" to period,
        "uncategorizedCount" to uncategorizedCount,
        "message" to message,
        "error" to error
    )
}
