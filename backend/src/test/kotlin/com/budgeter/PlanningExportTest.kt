package com.budgeter

import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import kotlin.test.*

class PlanningExportTest {
    private val today = LocalDate.of(2026, 8, 22)

    private fun entry(label: String, type: NetWorthEntryType, value: Double): NetWorthEntry =
        NetWorthEntry("e-$label", "owner", label, type, value)

    private fun tx(id: String, date: LocalDate, amount: Double, category: String? = null): Transaction =
        Transaction(id, "owner", AccountType.BANK, date, "Transaction $id", amount, category)

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
            setOf("summary.csv", "net_worth.csv", "transactions.csv", "goals.csv", "scenarios.csv", "projections.csv", "outcomes.csv"),
            files.keys
        )
    }

    @Test
    fun testTransactionsCsvOnlyIncludesTrailingSixMonths() {
        val transactions = listOf(
            tx("in-range", date = today.minusMonths(1), amount = -10.0),
            tx("too-old", date = today.minusMonths(7), amount = -20.0)
        )
        val bytes = planningExportZip(emptyList(), emptyList(), emptyList(), transactions, emptyList(), today)
        val csv = readZip(bytes)["transactions.csv"]!!

        assertTrue(csv.contains("in-range"))
        assertFalse(csv.contains("too-old"))
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
