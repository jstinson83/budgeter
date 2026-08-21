package com.budgeter

// Same flattening rationale as CategoriesPage.kt/TransactionPage.kt -
// pre-formats everything /planning.ftl needs: the asset/liability entries
// split into two lists (already sorted assets-then-liabilities by
// NetWorthEntryRepository.all), the running totals, the type options for
// the add/edit form's dropdown, and (as of slice 2) the household's
// FinancialGoal rows - same "one page model per page" convention
// CategoriesPage.kt uses for its own two sub-features (categories + rules).
fun netWorthPageModel(entries: List<NetWorthEntry>, goals: List<FinancialGoal>, message: String?, error: String?): Map<String, Any?> {
    val assets = entries.filter { it.type.isAsset }
    val liabilities = entries.filter { !it.type.isAsset }
    return mapOf(
        "assets" to assets.map(::netWorthEntryRowModel),
        "liabilities" to liabilities.map(::netWorthEntryRowModel),
        "totalAssets" to "%.2f".format(assets.sumOf { it.value }),
        "totalLiabilities" to "%.2f".format(liabilities.sumOf { it.value }),
        "netWorth" to "%.2f".format(netWorthTotal(entries)),
        "assetTypeOptions" to NetWorthEntryType.entries.filter { it.isAsset }.map { mapOf("name" to it.name, "label" to it.label) },
        "liabilityTypeOptions" to NetWorthEntryType.entries.filter { !it.isAsset }.map { mapOf("name" to it.name, "label" to it.label) },
        "goals" to goals.map(::financialGoalRowModel),
        "message" to message,
        "error" to error
    )
}

private fun netWorthEntryRowModel(entry: NetWorthEntry): Map<String, Any?> = mapOf(
    "id" to entry.id,
    "label" to entry.label,
    "typeName" to entry.type.name,
    "typeLabel" to entry.type.label,
    "value" to "%.2f".format(entry.value)
)

private fun financialGoalRowModel(goal: FinancialGoal): Map<String, Any?> = mapOf(
    "id" to goal.id,
    "name" to goal.name,
    "typeName" to goal.type.name,
    "isRetirement" to (goal.type == FinancialGoalType.RETIREMENT),
    "targetDate" to goal.targetDate.toString(),
    "targetAmount" to (goal.targetAmount?.let { "%.2f".format(it) } ?: ""),
    "annualSpend" to (goal.annualSpend?.let { "%.2f".format(it) } ?: ""),
    "withdrawalRatePercent" to "%.1f".format((goal.withdrawalRate ?: DEFAULT_WITHDRAWAL_RATE) * 100),
    "resolvedTargetAmount" to "%.2f".format(goal.resolvedTargetAmount())
)
