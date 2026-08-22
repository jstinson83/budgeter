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
    today: LocalDate = LocalDate.now()
): ByteArray {
    val categoryLabelById = categories.associateBy({ it.id }, { it.label })
    val exportStart = today.minusMonths(EXPORT_TRAILING_MONTHS)
    val exportTransactions = transactions.filter { !it.date.isBefore(exportStart) && !it.date.isAfter(today) }

    val goalProjections = goals.map { goal -> goal to projectGoal(goal, entries, transactions, today) }
    val scenarioProjectionsByGoal = goals.associateWith { goal ->
        scenarios.map { scenario -> scenario to projectScenario(scenario, entries, transactions, goal, today) }
    }

    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry("summary.csv"))
        zip.write(summaryCsv(entries, exportTransactions, exportStart, today).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("net_worth.csv"))
        zip.write(netWorthCsv(entries).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("monthly_category_totals.csv"))
        zip.write(categoryTotalsCsv(exportTransactions, categories).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("goals.csv"))
        zip.write(goalsCsv(goalProjections).toByteArray())
        zip.closeEntry()

        zip.putNextEntry(ZipEntry("scenarios.csv"))
        zip.write(scenariosCsv(scenarios, categoryLabelById).toByteArray())
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

private fun summaryCsv(entries: List<NetWorthEntry>, exportTransactions: List<Transaction>, exportStart: LocalDate, today: LocalDate): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader("ExportDate", "NetWorthAsOf", "NetWorth", "TotalAssets", "TotalLiabilities", "TransactionsFrom", "TransactionsTo", "TransactionCount")
        .build()
        .print(writer)
        .use { printer ->
            val assetsTotal = entries.filter { it.type.isAsset }.sumOf { it.value }
            val liabilitiesTotal = entries.filter { !it.type.isAsset }.sumOf { it.value }
            printer.printRecord(
                today, today, netWorthTotal(entries), assetsTotal, liabilitiesTotal,
                exportStart, today, exportTransactions.size
            )
        }
    return writer.toString()
}

// Value is always entered as a positive magnitude regardless of asset vs.
// liability - see NetWorthStore.kt's doc comment. AssetOrLiability is
// exported alongside Type so a verifier doesn't need to know this app's enum
// (NetWorthEntryType) to tell which way each row counts.
private fun netWorthCsv(entries: List<NetWorthEntry>): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader("Label", "Type", "AssetOrLiability", "Value")
        .build()
        .print(writer)
        .use { printer ->
            entries.forEach { entry ->
                printer.printRecord(entry.label, entry.type.label, if (entry.type.isAsset) "Asset" else "Liability", entry.value)
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

// RrspIncomeCategory is resolved to its display label (not just the raw
// Category id) so a verifier without app access can tell what it means -
// same reasoning transactionsToCsv already applies to Transaction.category.
private fun scenariosCsv(scenarios: List<Scenario>, categoryLabelById: Map<String, String>): String {
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader(
            "Name", "AnnualMarketGrowthRatePercent", "InvestedSavingsFractionPercent", "RecreationalSpendAdjustment",
            "SalaryChangeDate", "SalaryChangeMonthlyDelta", "RrspMonthlyContribution", "RrspMarginalTaxRatePercent",
            "RrspRoomRemaining", "RrspReinvestRefund", "RrspIncomeCategory", "RrspAnnualRoomAccrualCap"
        )
        .build()
        .print(writer)
        .use { printer ->
            scenarios.forEach { scenario ->
                printer.printRecord(
                    scenario.name, scenario.annualMarketGrowthRate * 100, scenario.investedSavingsFraction * 100,
                    scenario.recreationalSpendAdjustment, scenario.salaryChangeDate ?: "", scenario.salaryChangeMonthlyDelta ?: "",
                    scenario.rrspMonthlyContribution ?: "", scenario.rrspMarginalTaxRate?.let { it * 100 } ?: "",
                    scenario.rrspRoomRemaining ?: "", scenario.rrspReinvestRefund,
                    scenario.rrspIncomeCategoryId?.let { categoryLabelById[it] ?: it } ?: "",
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
