package com.budgeter

import java.time.LocalDate

// Same flattening rationale as CategoriesPage.kt/TransactionPage.kt -
// pre-formats everything /planning.ftl needs: the asset/liability entries
// split into two lists (already sorted assets-then-liabilities by
// NetWorthEntryRepository.all), the running totals, the type options for
// the add/edit form's dropdown, the household's FinancialGoal rows, and
// (as of slice 4) its Scenario rows - same "one page model per page"
// convention CategoriesPage.kt uses for its own two sub-features
// (categories + rules). Each goal carries its baseline projection
// (ProjectionEngine.kt, no Gemini/scenario knobs) plus one projected line
// per Scenario, all sharing one chart (ProjectionChart.kt).
fun netWorthPageModel(
    entries: List<NetWorthEntry>,
    goals: List<FinancialGoal>,
    scenarios: List<Scenario>,
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
        "goals" to goals.map { goal -> financialGoalRowModel(goal, entries, scenarios, transactions, today) },
        "scenarios" to scenarios.map(::scenarioRowModel),
        "growthPresets" to MarketGrowthPreset.entries.map { mapOf("name" to it.name, "label" to it.label, "annualRatePercent" to "%.1f".format(it.annualRate * 100)) },
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

private fun financialGoalRowModel(goal: FinancialGoal, entries: List<NetWorthEntry>, scenarios: List<Scenario>, transactions: List<Transaction>, today: LocalDate): Map<String, Any?> {
    val projection = projectGoal(goal, entries, transactions, today)
    val scenarioProjections = scenarios.map { scenario -> scenario to projectScenario(scenario, entries, transactions, goal, today) }
    val chart = projectionChartModel(
        projection.points,
        scenarioProjections.map { (scenario, points) -> scenario.name to points.map { ProjectionPoint(it.date, it.netWorth) } },
        goal.resolvedTargetAmount()
    )
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
        "chartLines" to chart.lines.map { mapOf("label" to it.label, "cssClass" to it.cssClass, "points" to it.points) },
        "chartGoalY" to chart.goalY,
        "chartMinLabel" to chart.minLabel,
        "chartMaxLabel" to chart.maxLabel,
        "scenarioOutcomes" to scenarioProjections.map { (scenario, points) ->
            val finalNetWorth = points.last().netWorth
            mapOf(
                "name" to scenario.name,
                "projectedFinal" to "%.2f".format(finalNetWorth),
                "onTrack" to (finalNetWorth >= goal.resolvedTargetAmount())
            )
        }
    )
}

private fun scenarioRowModel(scenario: Scenario): Map<String, Any?> = mapOf(
    "id" to scenario.id,
    "name" to scenario.name,
    "annualMarketGrowthRatePercent" to "%.1f".format(scenario.annualMarketGrowthRate * 100),
    "investedSavingsFractionPercent" to "%.0f".format(scenario.investedSavingsFraction * 100),
    "recreationalSpendAdjustment" to "%.2f".format(scenario.recreationalSpendAdjustment),
    "hasSalaryChange" to (scenario.salaryChangeDate != null),
    "salaryChangeDate" to (scenario.salaryChangeDate?.toString() ?: ""),
    "salaryChangeMonthlyDelta" to (scenario.salaryChangeMonthlyDelta?.let { "%.2f".format(it) } ?: "")
)
