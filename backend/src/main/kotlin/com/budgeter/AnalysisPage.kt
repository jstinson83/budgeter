package com.budgeter

// Same flattening rationale as TransactionPage.kt: FreeMarker's default
// object wrapper can't stringify Kotlin data classes/enums on its own, so
// this pre-formats everything the template needs.
fun analysisPageModel(
    periodTransactions: List<Transaction>,
    categories: List<Category>,
    year: Int,
    month: Int,
    monthLabel: String,
    prevHref: String,
    nextHref: String,
    uncategorizedCount: Int,
    message: String?,
    error: String?
): Map<String, Any?> {
    // Investments excluded here, not from periodTransactions itself - an
    // INVESTMENT row still shows in categoryTotals below, it just shouldn't
    // count as income or spending in the net change figure.
    val netChange = periodTransactions
        .filter { it.category != INVESTMENT_CATEGORY_ID }
        .sumOf { it.amount }

    val labelById = categories.associateBy({ it.id }, { it.label })
    val categoryTotals = periodTransactions
        .groupBy { it.category }
        .map { (categoryId, transactions) -> Triple(categoryId, transactions.sumOf { it.amount }, transactions.size) }
        .sortedBy { (_, total, _) -> total }
        .map { (categoryId, total, count) ->
            // "uncategorized" is a synthetic slug (null has no category id
            // to use) - AnalysisRoutes.kt's category drill-down route
            // special-cases it back to a null category filter.
            val slug = categoryId?.lowercase() ?: "uncategorized"
            mapOf(
                // Falls back to the raw id if it doesn't resolve to a known
                // category - shouldn't happen (categories are disabled, not
                // deleted) but keeps a stray id readable rather than blank.
                "category" to (categoryId?.let { labelById[it] ?: it } ?: "Uncategorized"),
                "total" to formatSignedAmount(total),
                "count" to count,
                "totalClass" to amountClass(total),
                "href" to "/analysis/category/$slug?year=$year&month=$month"
            )
        }

    return mapOf(
        "categoryTotals" to categoryTotals,
        "netChange" to formatSignedAmount(netChange),
        "netChangeClass" to amountClass(netChange),
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
    categorySlug: String,
    categoryOptions: List<Category>
): Map<String, Any?> {
    val total = transactions.sumOf { it.amount }
    return mapOf(
        "transactions" to transactions.sortedByDescending { it.date }.map(::transactionRowModel),
        "categoryLabel" to categoryLabel,
        "monthLabel" to monthLabel,
        "backHref" to backHref,
        "year" to year,
        "month" to month,
        "categorySlug" to categorySlug,
        // Same total shown on this category's row on /analysis - recomputed
        // here from the same filtered transaction list rather than passed
        // through, since the route already has it in scope.
        "total" to formatSignedAmount(total),
        "totalClass" to amountClass(total),
        // Only active categories are offered as a recategorize target - a
        // disabled category can still be viewed (its past transactions keep
        // their assignment) but shouldn't collect new ones. TRANSFER never
        // appears here since it isn't a real Category row to begin with.
        "categoryOptions" to categoryOptions.filter { it.active }.map { mapOf("name" to it.id, "label" to it.label) }
    )
}
