package com.budgeter

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.YearMonth
import java.util.zip.ZipInputStream
import kotlin.test.*

class PlanningExportTest {
    private val today = LocalDate.of(2026, 8, 22)

    private fun entry(label: String, type: NetWorthEntryType, value: Double): NetWorthEntry =
        NetWorthEntry("e-$label", "owner", label, type, value)

    private fun tx(id: String, date: LocalDate, amount: Double, category: String? = null, description: String = "Transaction $id"): Transaction =
        Transaction(id, "owner", AccountType.BANK, date, description, amount, category)

    private fun goal(name: String, targetAmount: Double, targetDate: LocalDate): FinancialGoal =
        FinancialGoal("g-$name", "owner", name, FinancialGoalType.NET_WORTH_TARGET, targetDate, targetAmount = targetAmount)

    private fun scenario(name: String): Scenario =
        Scenario("s-$name", "owner", name, annualMarketGrowthRate = 0.07, investedSavingsFraction = 1.0, recreationalSpendAdjustment = 0.0)

    private fun readZip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var next = zip.nextEntry
            while (next != null) {
                entries[next.name] = zip.readBytes().decodeToString()
                next = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun testExportContainsOneCsvPerSection() {
        val bytes = planningExportZip(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), today)
        val files = readZip(bytes)

        assertEquals(
            setOf("summary.csv", "net_worth.csv", "monthly_category_totals.csv", "goals.csv", "scenarios.csv", "projections.csv", "outcomes.csv"),
            files.keys
        )
    }

    @Test
    fun testCategoryTotalsCsvOnlyIncludesTrailingSixMonthsRolledUpByMonthAndCategory() {
        val transactions = listOf(
            tx("in-1", date = today.minusMonths(1), amount = -10.0, category = "GROCERIES"),
            tx("in-2", date = today.minusMonths(1), amount = -15.0, category = "GROCERIES"),
            tx("too-old", date = today.minusMonths(7), amount = -20.0, category = "GROCERIES")
        )
        val categories = listOf(Category("GROCERIES", "owner", "Groceries"))
        val bytes = planningExportZip(emptyList(), emptyList(), emptyList(), transactions, categories, today)
        val csv = readZip(bytes)["monthly_category_totals.csv"]!!
        val lines = csv.trim().lines()

        assertEquals("Month,Category,TotalAmount,TransactionCount", lines[0])
        assertEquals(listOf("${YearMonth.from(today.minusMonths(1))},Groceries,-25.0,2"), lines.drop(1))
    }

    // The whole point of this rollup - a third party gets the numbers the
    // projection math is built from, never merchant-level detail.
    @Test
    fun testCategoryTotalsCsvNeverLeaksTransactionDescriptionsOrIds() {
        val transactions = listOf(
            tx("secret-id", date = today.minusMonths(1), amount = -10.0, description = "Planned Parenthood")
        )
        val bytes = planningExportZip(emptyList(), emptyList(), emptyList(), transactions, emptyList(), today)
        val csv = readZip(bytes)["monthly_category_totals.csv"]!!

        assertFalse(csv.contains("Planned Parenthood"))
        assertFalse(csv.contains("secret-id"))
    }

    @Test
    fun testNetWorthCsvLabelsAssetsAndLiabilities() {
        val entries = listOf(
            entry("Brokerage", NetWorthEntryType.INVESTMENT, 20000.0),
            entry("Mortgage", NetWorthEntryType.MORTGAGE, 18000.0)
        )
        val bytes = planningExportZip(entries, emptyList(), emptyList(), emptyList(), emptyList(), today)
        val files = readZip(bytes)

        val netWorthCsv = files["net_worth.csv"]!!
        assertTrue(netWorthCsv.contains("Brokerage,Investment,Asset,20000.0"))
        assertTrue(netWorthCsv.contains("Mortgage,Mortgage,Liability,18000.0"))

        val summaryCsv = files["summary.csv"]!!
        assertTrue(summaryCsv.contains("2000.0")) // net worth: 20000 - 18000
    }

    @Test
    fun testOutcomesCsvIncludesBaselineAndEachScenarioForEveryGoal() {
        val entries = listOf(entry("Bank", NetWorthEntryType.BANK, 10000.0))
        val goals = listOf(goal("House", targetAmount = 50000.0, targetDate = today.plusYears(2)))
        val scenarios = listOf(scenario("Aggressive growth"))

        val bytes = planningExportZip(entries, goals, scenarios, emptyList(), emptyList(), today)
        val outcomesCsv = readZip(bytes)["outcomes.csv"]!!
        val lines = outcomesCsv.trim().lines()

        assertEquals("Goal,Line,ProjectedFinal,OnTrack,ShortfallOrSurplus,TotalRrspRefunds,FinalRrspRoomRemaining", lines[0])
        assertTrue(lines.any { it.startsWith("House,Baseline,") })
        assertTrue(lines.any { it.startsWith("House,Aggressive growth,") })
    }

    @Test
    fun testScenariosCsvResolvesRrspIncomeCategoryToItsLabel() {
        val scenarios = listOf(
            scenario("With RRSP").copy(rrspIncomeCategoryId = "SALARY")
        )
        val categories = listOf(Category("SALARY", "owner", "Salary"))

        val bytes = planningExportZip(emptyList(), emptyList(), scenarios, emptyList(), categories, today)
        val csv = readZip(bytes)["scenarios.csv"]!!

        assertTrue(csv.contains("Salary"))
        assertFalse(csv.contains("SALARY"))
    }
}
