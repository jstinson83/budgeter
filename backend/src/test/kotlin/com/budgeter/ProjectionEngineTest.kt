package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class ProjectionEngineTest {
    private fun tx(id: String, date: String, amount: Double, category: String? = null): Transaction =
        Transaction(id, "owner", AccountType.BANK, LocalDate.parse(date), "desc", amount, category)

    @Test
    fun testBaselineMonthlySavingsRateAveragesTrailingThreeMonthsOfNetChange() {
        val transactions = listOf(
            tx("1", "2026-06-15", 1000.0), // June net: +1000
            tx("2", "2026-07-15", 2000.0), // July net: +2000
            tx("3", "2026-08-15", 3000.0)  // Aug net: +3000
        )
        val rate = baselineMonthlySavingsRate(transactions, LocalDate.of(2026, 8, 21))
        assertEquals(2000.0, rate, 0.001) // (1000 + 2000 + 3000) / 3
    }

    @Test
    fun testBaselineMonthlySavingsRateExcludesTransferAndInvestmentCategories() {
        val transactions = listOf(
            tx("1", "2026-08-15", 5000.0),
            tx("2", "2026-08-16", -1000.0, TRANSFER_CATEGORY_ID),
            tx("3", "2026-08-17", -2000.0, INVESTMENT_CATEGORY_ID)
        )
        val rate = baselineMonthlySavingsRate(transactions, LocalDate.of(2026, 8, 21))
        // Only the +5000 counts; the other two months in the trailing window are empty.
        assertEquals(5000.0 / 3, rate, 0.001)
    }

    @Test
    fun testBaselineMonthlySavingsRateOfNoTransactionsIsZero() {
        assertEquals(0.0, baselineMonthlySavingsRate(emptyList(), LocalDate.of(2026, 8, 21)), 0.001)
    }

    @Test
    fun testProjectNetWorthStepsForwardOneMonthAtATimeWithNoCompounding() {
        val points = projectNetWorth(10000.0, 500.0, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 11, 1))

        assertEquals(4, points.size) // Aug, Sep, Oct, Nov
        assertEquals(10000.0, points[0].netWorth, 0.001)
        assertEquals(10500.0, points[1].netWorth, 0.001)
        assertEquals(11000.0, points[2].netWorth, 0.001)
        assertEquals(11500.0, points[3].netWorth, 0.001)
    }

    @Test
    fun testProjectNetWorthOfATargetDateInThePastYieldsOnlyTheStartingPoint() {
        val points = projectNetWorth(10000.0, 500.0, LocalDate.of(2026, 8, 21), LocalDate.of(2020, 1, 1))
        assertEquals(1, points.size)
        assertEquals(10000.0, points[0].netWorth, 0.001)
    }

    @Test
    fun testProjectGoalMarksOnTrackWhenProjectedFinalMeetsTheTarget() {
        val entries = listOf(NetWorthEntry("1", "owner", "Chequing", NetWorthEntryType.BANK, 100000.0))
        // A single +5000 transaction averaged over the trailing 3-month
        // window (with two empty months) gives a ~1666.67/mo baseline.
        val transactions = listOf(tx("1", "2026-08-15", 5000.0))
        val goal = FinancialGoal(
            "g1", "owner", "Small goal", FinancialGoalType.NET_WORTH_TARGET,
            LocalDate.of(2026, 9, 1), targetAmount = 100000.0
        )

        val projection = projectGoal(goal, entries, transactions, LocalDate.of(2026, 8, 21))

        // Already starts at the target and only grows from there, so it's
        // on track with a surplus rather than landing exactly on it.
        assertTrue(projection.onTrack)
        assertEquals(5000.0 / 3, projection.shortfallOrSurplus, 0.001)
    }

    @Test
    fun testProjectGoalMarksOffTrackAndReportsAPositiveShortfallWhenProjectedFinalMissesTheTarget() {
        val entries = listOf(NetWorthEntry("1", "owner", "Chequing", NetWorthEntryType.BANK, 1000.0))
        val transactions = emptyList<Transaction>() // 0/mo baseline - net worth never grows
        val goal = FinancialGoal(
            "g1", "owner", "Big goal", FinancialGoalType.NET_WORTH_TARGET,
            LocalDate.of(2026, 12, 1), targetAmount = 1000000.0
        )

        val projection = projectGoal(goal, entries, transactions, LocalDate.of(2026, 8, 21))

        assertFalse(projection.onTrack)
        assertEquals(999000.0, projection.shortfallOrSurplus, 0.001)
    }
}
