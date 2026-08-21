package com.budgeter

import java.time.LocalDate

// Same flattening rationale as CategoriesPage.kt/TransactionPage.kt -
// pre-formats everything /planning.ftl needs: the asset/liability entries
// split into two lists (already sorted assets-then-liabilities by
// NetWorthEntryRepository.all), the running totals, the type options for
// the add/edit form's dropdown, and the household's FinancialGoal rows -
// same "one page model per page" convention CategoriesPage.kt uses for its
// own two sub-features (categories + rules). As of slice 3, each goal also
// carries its ProjectionEngine.kt projection (baseline scenario only, no
// Gemini/scenario knobs yet) and a ProjectionChart.kt-ready chart.
fun netWorthPageModel(
    entries: List<NetWorthEntry>,
    goals: List<FinancialGoal>,
    transactions: List<Transaction>,
    message: String?,
    error: String?,
    today: LocalDate = LocalDate.now()
): Map<String, Any?> {
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
        "goals" to goals.map { goal -> financialGoalRowModel(goal, entries, transactions, today) },
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

private fun financialGoalRowModel(goal: FinancialGoal, entries: List<NetWorthEntry>, transactions: List<Transaction>, today: LocalDate): Map<String, Any?> {
    val projection = projectGoal(goal, entries, transactions, today)
    val chart = projectionChartModel(projection.points, goal.resolvedTargetAmount())
    return mapOf(
        "id" to goal.id,
        "name" to goal.name,
        "typeName" to goal.type.name,
        "isRetirement" to (goal.type == FinancialGoalType.RETIREMENT),
        "targetDate" to goal.targetDate.toString(),
        "targetAmount" to (goal.targetAmount?.let { "%.2f".format(it) } ?: ""),
        "annualSpend" to (goal.annualSpend?.let { "%.2f".format(it) } ?: ""),
        "withdrawalRatePercent" to "%.1f".format((goal.withdrawalRate ?: DEFAULT_WITHDRAWAL_RATE) * 100),
        "resolvedTargetAmount" to "%.2f".format(goal.resolvedTargetAmount()),
        "monthlySavingsRate" to formatSignedAmount(projection.monthlySavingsRate),
        "projectedFinal" to "%.2f".format(projection.finalNetWorth),
        "onTrack" to projection.onTrack,
        "shortfallOrSurplus" to "%.2f".format(projection.shortfallOrSurplus),
        "chartPoints" to chart.points,
        "chartGoalY" to chart.goalY,
        "chartMinLabel" to chart.minLabel,
        "chartMaxLabel" to chart.maxLabel
    )
}
