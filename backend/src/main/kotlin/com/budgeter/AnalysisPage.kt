package com.budgeter

// Same flattening rationale as TransactionPage.kt: FreeMarker's default
// object wrapper can't stringify Kotlin data classes/enums on its own, so
// this pre-formats everything the template needs.
fun analysisPageModel(
    periodTransactions: List<Transaction>,
    year: Int,
    month: Int,
    monthLabel: String,
    prevHref: String,
    nextHref: String,
    uncategorizedCount: Int,
    message: String?,
    error: String?
): Map<String, Any?> {
    val categoryTotals = periodTransactions
        .groupBy { it.category }
        .map { (category, transactions) -> Triple(category, transactions.sumOf { it.amount }, transactions.size) }
        .sortedBy { (_, total, _) -> total }
        .map { (category, total, count) ->
            // "uncategorized" is a synthetic slug (null has no enum name) -
            // AnalysisRoutes.kt's category drill-down route special-cases it
            // back to a null category filter.
            val slug = category?.name?.lowercase() ?: "uncategorized"
            mapOf(
                "category" to (category?.label ?: "Uncategorized"),
                "total" to formatSignedAmount(total),
                "count" to count,
                "totalClass" to amountClass(total),
                "href" to "/analysis/category/$slug?year=$year&month=$month"
            )
        }

    return mapOf(
        "categoryTotals" to categoryTotals,
        "year" to year,
        "month" to month,
        "monthLabel" to monthLabel,
        "prevHref" to prevHref,
        "nextHref" to nextHref,
        "uncategorizedCount" to uncategorizedCount,
        "message" to message,
        "error" to error
    )
}

// Drill-down page behind a category row on /analysis - same
// per-transaction row shape as /transactions (transactionRowModel), just
// scoped to one category and month instead of everything. Also carries what
// the inline "Recategorize" form (see analysis-category.ftl and
// AnalysisRoutes.kt's POST /analysis/recategorize) needs: year/month and
// this page's own category slug to redirect back here afterward, plus the
// pickable category list for its dropdown.
fun analysisCategoryPageModel(
    transactions: List<Transaction>,
    categoryLabel: String,
    monthLabel: String,
    backHref: String,
    year: Int,
    month: Int,
    categorySlug: String
): Map<String, Any?> = mapOf(
    "transactions" to transactions.sortedByDescending { it.date }.map(::transactionRowModel),
    "categoryLabel" to categoryLabel,
    "monthLabel" to monthLabel,
    "backHref" to backHref,
    "year" to year,
    "month" to month,
    "categorySlug" to categorySlug,
    // TRANSFER excluded - it's assigned only by TransferMatcher's
    // deterministic amount/date pairing, never a manual/rule-based pick.
    "categoryOptions" to TransactionCategory.entries
        .filter { it != TransactionCategory.TRANSFER }
        .map { mapOf("name" to it.name, "label" to it.label) }
)
