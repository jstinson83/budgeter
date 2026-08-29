package com.budgeter

import org.apache.commons.csv.CSVFormat
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// "Verification export" for /planning - a zip of plain CSVs a third party
// (accountant, lender, whoever) can check without ever getting app access.
// Deliberately a bundle of small, single-shape tables rather than one mixed
// CSV: transactions/net worth entries/goals/scenarios/projection points are
// five different row shapes, and a verifier reading this by hand benefits
// from each staying a clean table in its own file. Reuses ProjectionEngine's
// projectGoal/projectScenario directly (the same functions NetWorthPage.kt
// calls for the /planning chart) rather than recomputing anything, so the
// export can't drift from what the page itself shows.
//
// Deliberately no per-transaction detail (raw description/merchant text) -
// the projection math itself only ever consumes a monthly net number
// (ProjectionEngine.kt's baselineMonthlySavingsRate sums income minus
// expense per month, transfers/investments excluded), never an individual
// row, so a month+category rollup (categoryTotalsCsv below) gives a
// verifier everything the numbers are built from without exposing where
// specific money was spent - the earlier per-row transactions.csv did that
// unnecessarily.
const val EXPORT_TRAILING_MONTHS = 6L

fun planningExportZip(
    entries: List<NetWorthEntry>,
    goals: List<FinancialGoal>,
    scenarios: List<Scenario>,
    transactions: List<Transaction>,
    categories: List<Category>,
    incomeCategoryId: String? = null,
    today: LocalDate = LocalDate.now()
): ByteArray {
    val categoryLabelById = categories.associateBy({ it.id }, { it.label })
    val exportStart = today.minusMonths(EXPORT_TRAILING_MONTHS)
    val exportTransactions = transactions.filter { !it.date.isBefore(exportStart) && !it.date.isAfter(today) }

    val goalProjections = goals.map { goal -> goal to projectGoal(goal, entries, transactions, today) }
    val scenarioProjectionsByGoal = goals.associateWith { goal ->
        scenarios.map { scenario -> scenario to projectScenario(scenario, entries, transactions, goal, incomeCategoryId, today) }
    }

    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("summary.csv"))
        zip.write(summaryCsv(entries, transactions, exportTransactions, categoryLabelById, incomeCategoryId, exportStart, today).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("net_worth.csv"))
        zip.write(netWorthCsv(entries, transactions, categoryLabelById, today).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("monthly_category_totals.csv"))
        zip.write(categoryTotalsCsv(exportTransactions, categories).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("goals.csv"))
        zip.write(goalsCsv(goalProjections).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("scenarios.csv"))
        zip.write(scenariosCsv(scenarios).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("projections.csv"))
        zip.write(projectionsCsv(goalProjections, scenarioProjectionsByGoal).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("outcomes.csv"))
        zip.write(outcomesCsv(goalProjections, scenarioProjectionsByGoal).toByteArray())
        zip.closeEntry()
    }
    return bytes.toByteArray()
}

// IncomeCategory/RecentMonthlyIncome/RecentMonthlySavingsRate mirror
// /planning's "Your Numbers" card (NetWorthPage.kt) exactly - same
// recentCategoryMonthlyAverage/baselineMonthlySavingsRate calls, so this
// export can't drift from what the page itself shows. RecentMonthlyIncome
// is blank (not 0) when no income category is set or it has no
// transactions in the trailing window - see recentCategoryMonthlyAverage's
// doc comment for why that's a distinct state from a real $0.
private fun summaryCsv(
    entries: List<NetWorthEntry>,
    transactions: List<Transaction>,
    exportTransactions: List<Transaction>,
    categoryLabelById: Map<String, String>,
    incomeCategoryId: String?,
    exportStart: LocalDate,
    today: LocalDate
): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader(
            "ExportDate", "NetWorthAsOf", "NetWorth", "TotalAssets", "TotalLiabilities", "TransactionsFrom", "TransactionsTo", "TransactionCount",
            "IncomeCategory", "RecentMonthlyIncome", "RecentMonthlySavingsRate"
        )
        .build()
        .print(writer)
        .use { printer ->
            val assetsTotal = entries.filter { it.type.isAsset }.sumOf { it.value }
            val liabilitiesTotal = entries.filter { !it.type.isAsset }.sumOf { it.value }
            printer.printRecord(
                today, today, netWorthTotal(entries), assetsTotal, liabilitiesTotal,
                exportStart, today, exportTransactions.size,
                incomeCategoryId?.let { categoryLabelById[it] ?: it } ?: "",
                recentCategoryMonthlyAverage(transactions, incomeCategoryId, today) ?: "",
                baselineMonthlySavingsRate(transactions, today)
            )
        }
    return writer.toString()
}

// Value is always entered as a positive magnitude regardless of asset vs.
// liability - see NetWorthStore.kt's doc comment. AssetOrLiability is
// exported alongside Type so a verifier doesn't need to know this app's enum
// (NetWorthEntryType) to tell which way each row counts. The four trailing
// columns are blank unless the entry actually amortizes/appreciates
// (NetWorthEntry.isAmortizingMortgage/isAppreciatingRealEstate) - included
// so a verifier can reproduce projections.csv's mortgage-paydown/
// appreciation numbers from this file alone, same "verify without app
// access" goal the rest of this export exists for. MortgagePaymentCategory
// is the household's tagged category (a label, not this app's internal id -
// same resolution transactionsToCsv/scenariosCsv already give a category
// reference); ResolvedMonthlyPayment is the actual figure
// resolvedMonthlyMortgagePayment (ProjectionEngine.kt) derived from that
// category's own trailing transaction history as of this export, since the
// payment is never a number the household typed in directly - see
// NetWorthEntry.mortgagePaymentCategoryId's doc comment.
private fun netWorthCsv(entries: List<NetWorthEntry>, transactions: List<Transaction>, categoryLabelById: Map<String, String>, today: LocalDate): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader(
            "Label", "Type", "AssetOrLiability", "Value", "AnnualInterestRatePercent",
            "MortgagePaymentCategory", "ResolvedMonthlyPayment", "AnnualAppreciationRatePercent"
        )
        .build()
        .print(writer)
        .use { printer ->
            entries.forEach { entry ->
                printer.printRecord(
                    entry.label, entry.type.label, if (entry.type.isAsset) "Asset" else "Liability", entry.value,
                    entry.annualInterestRate?.let { it * 100 } ?: "",
                    entry.mortgagePaymentCategoryId?.let { categoryLabelById[it] ?: it } ?: "",
                    resolvedMonthlyMortgagePayment(entry, transactions, today) ?: "",
                    entry.annualAppreciationRate?.let { it * 100 } ?: ""
                )
            }
        }
    return writer.toString()
}

// Same category-label resolution as transactionsToCsv (TransactionExport.kt)
// - TRANSFER isn't a real Category row (CategoryStore.kt), so it's
// special-cased to read "Transfer" rather than a raw id or a blank cell.
// Grouped by calendar month + category rather than emitted as a running
// total, so a verifier can see the trend shape as well as recompute
// baselineMonthlySavingsRate's trailing-month average from these rows.
private fun categoryTotalsCsv(transactions: List<Transaction>, categories: List<Category>): String {
    val labelById = categories.associateBy({ it.id }, { it.label })
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader("Month", "Category", "TotalAmount", "TransactionCount")
        .build()
        .print(writer)
        .use { printer ->
            transactions.groupBy { transaction ->
                val category = when (transaction.category) {
                    null -> "Uncategorized"
                    TRANSFER_CATEGORY_ID -> "Transfer"
                    else -> labelById[transaction.category] ?: transaction.category
                }
                YearMonth.from(transaction.date) to category
            }.toSortedMap(compareBy({ it.first }, { it.second })).forEach { (key, group) ->
                val (month, category) = key
                printer.printRecord(month, category, group.sumOf { it.amount }, group.size)
            }
        }
    return writer.toString()
}

private fun goalsCsv(goalProjections: List<Pair<FinancialGoal, GoalProjection>>): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader(
            "Name", "Type", "TargetDate", "TargetAmount", "AnnualSpend", "WithdrawalRatePercent", "ResolvedTargetAmount",
            "BaselineMonthlySavingsRate", "BaselineProjectedFinal", "BaselineOnTrack", "BaselineShortfallOrSurplus"
        )
        .build()
        .print(writer)
        .use { printer ->
            goalProjections.forEach { (goal, projection) ->
                printer.printRecord(
                    goal.name, goal.type.name, goal.targetDate,
                    goal.targetAmount ?: "", goal.annualSpend ?: "",
                    (goal.withdrawalRate ?: DEFAULT_WITHDRAWAL_RATE) * 100, goal.resolvedTargetAmount(),
                    projection.monthlySavingsRate, projection.finalNetWorth, projection.onTrack, projection.shortfallOrSurplus
                )
            }
        }
    return writer.toString()
}

// RrspAccrueRoomFromIncome no longer carries its own category (that's now
// summary.csv's IncomeCategory, one household-wide setting rather than
// picked per scenario - see HouseholdSettingsStore.kt) - just whether this
// scenario's room accrual is on.
private fun scenariosCsv(scenarios: List<Scenario>): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader(
            "Name", "AnnualMarketGrowthRatePercent", "InvestedSavingsFractionPercent", "RecreationalSpendAdjustment",
            "SalaryChangeDate", "SalaryChangeMonthlyDelta", "SalaryChangeEndDate",
            "SalaryChangeRrspContributionOverride", "SalaryChangeMarginalTaxRateOverridePercent", "SalaryChangeRoomAccrualOverride",
            "RrspMonthlyContribution", "RrspMarginalTaxRatePercent",
            "RrspRoomRemaining", "RrspReinvestRefund", "RrspAccrueRoomFromIncome", "RrspAnnualRoomAccrualCap"
        )
        .build()
        .print(writer)
        .use { printer ->
            scenarios.forEach { scenario ->
                printer.printRecord(
                    scenario.name, scenario.annualMarketGrowthRate * 100, scenario.investedSavingsFraction * 100,
                    scenario.recreationalSpendAdjustment, scenario.salaryChangeDate ?: "", scenario.salaryChangeMonthlyDelta ?: "",
                    scenario.salaryChangeEndDate ?: "",
                    scenario.salaryChangeRrspContributionOverride ?: "",
                    scenario.salaryChangeMarginalTaxRateOverride?.let { it * 100 } ?: "",
                    scenario.salaryChangeRoomAccrualOverride ?: "",
                    scenario.rrspMonthlyContribution ?: "", scenario.rrspMarginalTaxRate?.let { it * 100 } ?: "",
                    scenario.rrspRoomRemaining ?: "", scenario.rrspReinvestRefund,
                    scenario.rrspAccrueRoomFromIncome,
                    scenario.rrspAnnualRoomAccrualCap ?: ""
                )
            }
        }
    return writer.toString()
}

// One row per (goal, line, month) - "Baseline" plus one row-set per
// scenario, each scenario projected against that specific goal's target
// date (ProjectionEngine.projectScenario takes a goal). Invested/Cash are
// blank on Baseline rows since projectGoal only tracks one combined figure
// (see ProjectionPoint) - only a Scenario's two-balance model
// (ScenarioProjectionPoint) has that split.
private fun projectionsCsv(
    goalProjections: List<Pair<FinancialGoal, GoalProjection>>,
    scenarioProjectionsByGoal: Map<FinancialGoal, List<Pair<Scenario, ScenarioProjection>>>
): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader("Goal", "Line", "Date", "NetWorth", "Invested", "Cash")
        .build()
        .print(writer)
        .use { printer ->
            goalProjections.forEach { (goal, projection) ->
                projection.points.forEach { point ->
                    printer.printRecord(goal.name, "Baseline", point.date, point.netWorth, "", "")
                }
                scenarioProjectionsByGoal[goal].orEmpty().forEach { (scenario, result) ->
                    result.points.forEach { point ->
                        printer.printRecord(goal.name, scenario.name, point.date, point.netWorth, point.invested, point.cash)
                    }
                }
            }
        }
    return writer.toString()
}

// The "final answer" row per (goal, line) - what a verifier checks their own
// recomputation of projections.csv against, rather than having to eyeball
// every monthly point.
private fun outcomesCsv(
    goalProjections: List<Pair<FinancialGoal, GoalProjection>>,
    scenarioProjectionsByGoal: Map<FinancialGoal, List<Pair<Scenario, ScenarioProjection>>>
): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader("Goal", "Line", "ProjectedFinal", "OnTrack", "ShortfallOrSurplus", "TotalRrspRefunds", "FinalRrspRoomRemaining")
        .build()
        .print(writer)
        .use { printer ->
            goalProjections.forEach { (goal, projection) ->
                printer.printRecord(goal.name, "Baseline", projection.finalNetWorth, projection.onTrack, projection.shortfallOrSurplus, "", "")
                scenarioProjectionsByGoal[goal].orEmpty().forEach { (scenario, result) ->
                    val finalNetWorth = result.points.last().netWorth
                    val onTrack = finalNetWorth >= goal.resolvedTargetAmount()
                    val shortfallOrSurplus = kotlin.math.abs(finalNetWorth - goal.resolvedTargetAmount())
                    printer.printRecord(goal.name, scenario.name, finalNetWorth, onTrack, shortfallOrSurplus, result.totalRrspRefunds, result.finalRrspRoomRemaining)
                }
            }
        }
    return writer.toString()
}
