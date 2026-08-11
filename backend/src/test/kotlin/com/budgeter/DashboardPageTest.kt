package com.budgeter

import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.*

class DashboardPageTest {
    private fun tx(
        id: String,
        accountType: AccountType = AccountType.BANK,
        date: LocalDate,
        description: String = "Transaction",
        amount: Double,
        category: String? = null
    ): Transaction = Transaction(id, "owner", accountType, date, description, amount, category)

    @Test
    fun testAccountCoverageReportsEarliestLatestAndDaysSinceLastImport() {
        val transactions = listOf(
            tx("1", AccountType.BANK, date = LocalDate.of(2026, 5, 1), amount = -10.0),
            tx("2", AccountType.BANK, date = LocalDate.of(2026, 6, 1), amount = -10.0)
        )

        val coverage = accountCoverage(transactions, today = LocalDate.of(2026, 6, 11))

        val bank = coverage.single()
        assertEquals(LocalDate.of(2026, 5, 1), bank.earliest)
        assertEquals(LocalDate.of(2026, 6, 1), bank.latest)
        assertEquals(10L, bank.daysSinceLastImport)
    }

    @Test
    fun testAccountCoverageFlagsAGapLongerThanTheThresholdButNotAShortOne() {
        val withGap = accountCoverage(
            listOf(
                tx("1", date = LocalDate.of(2026, 1, 1), amount = -10.0),
                tx("2", date = LocalDate.of(2026, 3, 1), amount = -10.0)
            ),
            today = LocalDate.of(2026, 3, 5)
        ).single()
        assertEquals(1, withGap.gaps.size)

        val withoutGap = accountCoverage(
            listOf(
                tx("1", date = LocalDate.of(2026, 1, 1), amount = -10.0),
                tx("2", date = LocalDate.of(2026, 1, 10), amount = -10.0)
            ),
            today = LocalDate.of(2026, 1, 15)
        ).single()
        assertEquals(emptyList(), withoutGap.gaps)
    }

    @Test
    fun testMonthlyNetChangeExcludesTransfersAndInvestmentsAndCoversTheRequestedMonths() {
        val transactions = listOf(
            tx("1", date = LocalDate.of(2026, 6, 5), amount = -50.0),
            tx("2", date = LocalDate.of(2026, 6, 10), amount = 200.0),
            tx("3", date = LocalDate.of(2026, 6, 15), amount = -1000.0, category = TRANSFER_CATEGORY_ID),
            tx("4", date = LocalDate.of(2026, 6, 20), amount = -300.0, category = INVESTMENT_CATEGORY_ID),
            tx("5", date = LocalDate.of(2026, 5, 5), amount = 100.0)
        )

        val series = monthlyNetChange(transactions, endMonth = YearMonth.of(2026, 6), months = 2)

        assertEquals(listOf(YearMonth.of(2026, 5), YearMonth.of(2026, 6)), series.map { it.first })
        assertEquals(100.0, series[0].second)
        assertEquals(150.0, series[1].second) // -50 + 200, transfer and investment rows excluded
    }

    @Test
    fun testCategoryMoversSurfacesASpendingIncreaseAboveTheBaselineAndSkipsSmallOrUncategorizedOnes() {
        val transactions = buildList {
            // Dining: averaged $30/mo over the prior 3 months, jumps to $90 this month - a real mover.
            for (month in 3..5) add(tx("dining-$month", date = LocalDate.of(2026, month, 10), amount = -30.0, category = "DINING_OUT"))
            add(tx("dining-6", date = LocalDate.of(2026, 6, 10), amount = -90.0, category = "DINING_OUT"))
            // Tiny category under the noise floor - a 4x jump but only a few dollars.
            add(tx("misc-3", date = LocalDate.of(2026, 3, 10), amount = -2.0, category = "OTHER"))
            add(tx("misc-6", date = LocalDate.of(2026, 6, 10), amount = -8.0, category = "OTHER"))
            // Uncategorized spend - excluded regardless of size.
            add(tx("uncat-6", date = LocalDate.of(2026, 6, 10), amount = -500.0, category = null))
        }

        val movers = categoryMovers(transactions, emptyList(), currentMonth = YearMonth.of(2026, 6))

        assertEquals(1, movers.size)
        assertEquals("DINING_OUT", movers.single().label) // falls back to the raw id when no Category label is supplied
        assertEquals(-90.0, movers.single().currentTotal)
        assertEquals(-30.0, movers.single().priorAverage)
    }

    @Test
    fun testBiggestExpensePicksTheLargestSpendExcludingTransfersAndInvestments() {
        val transactions = listOf(
            tx("1", date = LocalDate.of(2026, 6, 5), amount = -40.0, description = "Groceries"),
            tx("2", date = LocalDate.of(2026, 6, 10), amount = -900.0, description = "Rent"),
            tx("3", date = LocalDate.of(2026, 6, 15), amount = -5000.0, description = "Card Payment", category = TRANSFER_CATEGORY_ID)
        )

        val biggest = biggestExpense(transactions, YearMonth.of(2026, 6))

        assertEquals("Rent", biggest?.description)
        assertEquals(-900.0, biggest?.amount)
    }

    @Test
    fun testDashboardPageModelReflectsEmptyStateWithNoTransactions() {
        val model = dashboardPageModel(emptyList(), emptyList())

        assertEquals(false, model["hasTransactions"])
    }

    @Test
    fun testDashboardPageModelDefaultsTheNetChangeTrendToThreeMonths() {
        val model = dashboardPageModel(
            listOf(tx("1", date = LocalDate.of(2026, 6, 15), amount = -10.0)),
            emptyList(),
            today = LocalDate.of(2026, 6, 20)
        )

        @Suppress("UNCHECKED_CAST")
        val series = model["netChangeSeries"] as List<Map<String, Any?>>
        assertEquals(3, series.size)
        assertEquals(3, model["netChangeTrendMonths"])
    }

    @Test
    fun testDashboardPageModelNetChangeSeriesLinksEachBarToItsMonthOnAnalysis() {
        val model = dashboardPageModel(
            listOf(tx("1", date = LocalDate.of(2026, 6, 15), amount = -10.0)),
            emptyList(),
            today = LocalDate.of(2026, 6, 20)
        )

        @Suppress("UNCHECKED_CAST")
        val series = model["netChangeSeries"] as List<Map<String, Any?>>
        assertEquals(
            listOf("/analysis?year=2026&month=4", "/analysis?year=2026&month=5", "/analysis?year=2026&month=6"),
            series.map { it["href"] }
        )
    }

    @Test
    fun testDashboardPageModelPieSlicesRollUpTheFullThreeMonthTrendWindow() {
        val transactions = listOf(
            // Within the 3-month window ending June: April, May, June.
            tx("1", date = LocalDate.of(2026, 4, 10), amount = -50.0, category = "GROCERIES"),
            tx("2", date = LocalDate.of(2026, 5, 10), amount = -30.0, category = "GROCERIES"),
            tx("3", date = LocalDate.of(2026, 6, 10), amount = -20.0, category = "GROCERIES"),
            // Outside the window - shouldn't contribute to the pie chart.
            tx("4", date = LocalDate.of(2026, 1, 10), amount = -900.0, category = "GROCERIES")
        )

        val model = dashboardPageModel(transactions, emptyList(), today = LocalDate.of(2026, 6, 20))

        @Suppress("UNCHECKED_CAST")
        val pieSlices = model["pieSlices"] as List<Map<String, Any?>>
        val groceries = pieSlices.single { it["label"] == "GROCERIES" }
        assertEquals("100.00", groceries["amount"]) // 50 + 30 + 20 across the 3-month window, January excluded
    }

    @Test
    fun testDashboardPageModelSummarizesTheTrendAsASingleTotal() {
        val transactions = listOf(
            // Within the 3-month window ending June: April, May, June.
            tx("1", date = LocalDate.of(2026, 4, 10), amount = -1000.0),
            tx("2", date = LocalDate.of(2026, 5, 10), amount = 500.0),
            tx("3", date = LocalDate.of(2026, 6, 10), amount = 2500.0),
            // Outside the window - shouldn't contribute to the total.
            tx("4", date = LocalDate.of(2026, 1, 10), amount = 100000.0)
        )

        val model = dashboardPageModel(transactions, emptyList(), today = LocalDate.of(2026, 6, 20))

        // -1000 + 500 + 2500 = 2000, the January row excluded.
        assertEquals("+2000.00", model["netChangeTotal"])
        assertEquals("transaction-amount-positive", model["netChangeTotalClass"])
    }
}
