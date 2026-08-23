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
    categories: List<Category>,
    message: String?,
    error: String?,
    today: LocalDate = LocalDate.now()
): Map<String, Any?> {
    val assets = entries.filter { it.type.isAsset }
    val liabilities = entries.filter { !it.type.isAsset }
    val activeCategories = categories.filter { it.active }.sortedBy { it.label.lowercase() }
    val categoryLabelById = categories.associateBy({ it.id }, { it.label })
    return mapOf(
        "assets" to assets.map(::netWorthEntryRowModel),
        "liabilities" to liabilities.map(::netWorthEntryRowModel),
        "totalAssets" to "%.2f".format(assets.sumOf { it.value }),
        "totalLiabilities" to "%.2f".format(liabilities.sumOf { it.value }),
        "netWorth" to "%.2f".format(netWorthTotal(entries)),
        "assetTypeOptions" to NetWorthEntryType.entries.filter { it.isAsset }.map { mapOf("name" to it.name, "label" to it.label) },
        "liabilityTypeOptions" to NetWorthEntryType.entries.filter { !it.isAsset }.map { mapOf("name" to it.name, "label" to it.label) },
        // Goals themselves are hidden from /planning's UI for now (felt
        // under-baked - a target/on-track judgment call, not the "watch
        // your wealth grow" view the maintainer actually wanted day to
        // day - see CLAUDE.md and current.md, 2026-08-23). Still computed
        // here since FinancialGoal/the goal-scoped chart remain wired into
        // PlanningExport.kt's verification export and the CRUD routes
        // still work - planning.ftl just doesn't render this key anymore.
        "goals" to goals.map { goal -> financialGoalRowModel(goal, entries, scenarios, transactions, today) },
        "scenarios" to scenarios.mapIndexed { index, scenario -> scenarioRowModel(scenario, categoryLabelById, index) },
        "wealthChart" to wealthChartRowModel(entries, scenarios, transactions, today),
        "wealthChartHorizonYears" to WEALTH_CHART_HORIZON_YEARS,
        "growthPresets" to MarketGrowthPreset.entries.map { mapOf("name" to it.name, "label" to it.label, "annualRatePercent" to "%.1f".format(it.annualRate * 100)) },
        // Only active categories are offered anywhere a household tags a
        // Category onto something else (the RRSP income selector, and a
        // mortgage entry's payment category below) - same "disabling stops
        // new assignment" rule /categories uses elsewhere. One shared list
        // rather than one per selector, since it's the same set of rows
        // either way.
        "categoryOptions" to activeCategories.map { mapOf("id" to it.id, "label" to it.label) },
        // Preselected on the add-liability form's mortgage payment-category
        // select - "MORTGAGE" is already a built-in category id most
        // households' mortgage payment transactions are already tagged
        // with, so this is right the overwhelming majority of the time
        // without the household having to hunt for it in the dropdown.
        "defaultMortgagePaymentCategoryId" to MORTGAGE_CATEGORY_ID,
        "message" to message,
        "error" to error
    )
}

private fun netWorthEntryRowModel(entry: NetWorthEntry): Map<String, Any?> = mapOf(
    "id" to entry.id,
    "label" to entry.label,
    "typeName" to entry.type.name,
    "typeLabel" to entry.type.label,
    "value" to "%.2f".format(entry.value),
    "annualInterestRatePercent" to (entry.annualInterestRate?.let { "%.2f".format(it * 100) } ?: ""),
    "mortgagePaymentCategoryId" to (entry.mortgagePaymentCategoryId ?: ""),
    "annualAppreciationRatePercent" to (entry.annualAppreciationRate?.let { "%.2f".format(it * 100) } ?: "")
)

// The goal-independent "Wealth over time" chart /planning now leads with -
// the always-shown baseline plus one line per Scenario, projected a fixed
// WEALTH_CHART_HORIZON_YEARS forward from today rather than to any goal's
// own target date (there is no goal driving this chart). Reuses the same
// projectScenario the goal-scoped chart uses, via the targetDate overload
// (ProjectionEngine.kt) instead of passing a FinancialGoal.
private fun wealthChartRowModel(entries: List<NetWorthEntry>, scenarios: List<Scenario>, transactions: List<Transaction>, today: LocalDate): Map<String, Any?> {
    val horizonEnd = today.plusYears(WEALTH_CHART_HORIZON_YEARS)
    val baselinePoints = projectNetWorth(entries, transactions, baselineMonthlySavingsRate(transactions, today), today, horizonEnd)
    val scenarioLines = scenarios.map { scenario ->
        scenario.name to projectScenario(scenario, entries, transactions, horizonEnd, today).points.map { ProjectionPoint(it.date, it.netWorth) }
    }
    val chart = wealthChartModel(baselinePoints, scenarioLines)
    return mapOf(
        "lines" to chart.lines.map { mapOf("label" to it.label, "cssClass" to it.cssClass, "points" to it.points) },
        "minLabel" to chart.minLabel,
        "maxLabel" to chart.maxLabel,
        "widthPx" to chart.widthPx
    )
}

private fun financialGoalRowModel(goal: FinancialGoal, entries: List<NetWorthEntry>, scenarios: List<Scenario>, transactions: List<Transaction>, today: LocalDate): Map<String, Any?> {
    val projection = projectGoal(goal, entries, transactions, today)
    val scenarioProjections = scenarios.map { scenario -> scenario to projectScenario(scenario, entries, transactions, goal, today) }
    val chart = projectionChartModel(
        projection.points,
        scenarioProjections.map { (scenario, result) -> scenario.name to result.points.map { ProjectionPoint(it.date, it.netWorth) } },
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
        "scenarioOutcomes" to scenarioProjections.map { (scenario, result) ->
            val finalNetWorth = result.points.last().netWorth
            mapOf(
                "name" to scenario.name,
                "projectedFinal" to "%.2f".format(finalNetWorth),
                "onTrack" to (finalNetWorth >= goal.resolvedTargetAmount()),
                "hasRrspStrategy" to (scenario.rrspMonthlyContribution != null),
                "totalRrspRefunds" to "%.2f".format(result.totalRrspRefunds),
                "finalRrspRoomRemaining" to "%.2f".format(result.finalRrspRoomRemaining)
            )
        }
    )
}

private fun scenarioRowModel(scenario: Scenario, categoryLabelById: Map<String, String>, index: Int): Map<String, Any?> = mapOf(
    "id" to scenario.id,
    "name" to scenario.name,
    // 1-based position within SCENARIO_LINE_CSS_CLASSES (ProjectionChart.kt) -
    // lets the scenario visibility chip planning.ftl renders for this row
    // target the exact same chart-line class the goal charts use, without
    // duplicating the cycling-by-position logic in the template itself.
    "chartColorIndex" to (index % SCENARIO_LINE_CSS_CLASSES.size) + 1,
    "annualMarketGrowthRatePercent" to "%.1f".format(scenario.annualMarketGrowthRate * 100),
    "investedSavingsFractionPercent" to "%.0f".format(scenario.investedSavingsFraction * 100),
    "recreationalSpendAdjustment" to "%.2f".format(scenario.recreationalSpendAdjustment),
    "hasSalaryChange" to (scenario.salaryChangeDate != null),
    "salaryChangeDate" to (scenario.salaryChangeDate?.toString() ?: ""),
    "salaryChangeMonthlyDelta" to (scenario.salaryChangeMonthlyDelta?.let { "%.2f".format(it) } ?: ""),
    "hasRrspStrategy" to (scenario.rrspMonthlyContribution != null),
    "rrspMonthlyContribution" to (scenario.rrspMonthlyContribution?.let { "%.2f".format(it) } ?: ""),
    "rrspMarginalTaxRatePercent" to (scenario.rrspMarginalTaxRate?.let { "%.1f".format(it * 100) } ?: ""),
    "rrspRoomRemaining" to (scenario.rrspRoomRemaining?.let { "%.2f".format(it) } ?: ""),
    "rrspReinvestRefund" to scenario.rrspReinvestRefund,
    "rrspIncomeCategoryId" to (scenario.rrspIncomeCategoryId ?: ""),
    "rrspIncomeCategoryLabel" to (scenario.rrspIncomeCategoryId?.let { categoryLabelById[it] ?: it }),
    "rrspAnnualRoomAccrualCap" to (scenario.rrspAnnualRoomAccrualCap?.let { "%.2f".format(it) } ?: "")
)
