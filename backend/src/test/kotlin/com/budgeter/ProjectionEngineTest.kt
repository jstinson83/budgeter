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
        // Both are excluded from the sum, not subtracted - so the -2000
        // RRSP-style contribution doesn't reduce the rate at all. Only the
        // +5000 counts; see baselineMonthlySavingsRate's doc comment for why
        // "excluded from the sum" already correctly counts an investment
        // contribution as saved rather than erasing it.
        assertEquals(5000.0 / 3, rate, 0.001)
    }

    @Test
    fun testBaselineMonthlySavingsRateOfNoTransactionsIsZero() {
        assertEquals(0.0, baselineMonthlySavingsRate(emptyList(), LocalDate.of(2026, 8, 21)), 0.001)
    }

    private fun entry(label: String, type: NetWorthEntryType, value: Double): NetWorthEntry =
        NetWorthEntry("e-$label", "owner", label, type, value)

    @Test
    fun testProjectNetWorthStepsForwardOneMonthAtATimeWithNoCompounding() {
        val entries = listOf(entry("Chequing", NetWorthEntryType.BANK, 10000.0))
        val points = projectNetWorth(entries, 500.0, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 11, 1))

        assertEquals(4, points.size) // Aug, Sep, Oct, Nov
        assertEquals(10000.0, points[0].netWorth, 0.001)
        assertEquals(10500.0, points[1].netWorth, 0.001)
        assertEquals(11000.0, points[2].netWorth, 0.001)
        assertEquals(11500.0, points[3].netWorth, 0.001)
    }

    @Test
    fun testProjectNetWorthOfATargetDateInThePastYieldsOnlyTheStartingPoint() {
        val entries = listOf(entry("Chequing", NetWorthEntryType.BANK, 10000.0))
        val points = projectNetWorth(entries, 500.0, LocalDate.of(2026, 8, 21), LocalDate.of(2020, 1, 1))
        assertEquals(1, points.size)
        assertEquals(10000.0, points[0].netWorth, 0.001)
    }

    @Test
    fun testProjectNetWorthAmortizesAMortgageEntryInsteadOfLeavingItFrozen() {
        val entries = listOf(
            NetWorthEntry(
                "m1", "owner", "Mortgage", NetWorthEntryType.MORTGAGE, 100000.0,
                annualInterestRate = 0.06, monthlyPayment = 1000.0
            )
        )
        // 0/mo baseline savings so only the mortgage amortization moves
        // net worth - starts at -100000 (a lone liability).
        val points = projectNetWorth(entries, 0.0, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 21))

        // Month 1: interest = 100000 * 0.06/12 = 500, principal = 500,
        // balance = 99500 -> net worth improves by exactly the principal paid.
        assertEquals(-100000.0, points[0].netWorth, 0.001)
        assertEquals(-99500.0, points[1].netWorth, 0.01)
    }

    @Test
    fun testProjectNetWorthAppreciatesARealEstateEntryInsteadOfLeavingItFrozen() {
        val entries = listOf(
            NetWorthEntry(
                "h1", "owner", "House", NetWorthEntryType.REAL_ESTATE, 600000.0,
                annualAppreciationRate = 0.06
            )
        )
        val points = projectNetWorth(entries, 0.0, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 9, 21))

        assertEquals(600000.0, points[0].netWorth, 0.001)
        assertEquals(603000.0, points[1].netWorth, 0.01) // 600000 * 1.005
    }

    @Test
    fun testProjectNetWorthDoesNotMoveAMortgageOrRealEstateEntryWithNoRateFieldsSet() {
        val entries = listOf(
            NetWorthEntry("m1", "owner", "Mortgage", NetWorthEntryType.MORTGAGE, 100000.0),
            NetWorthEntry("h1", "owner", "House", NetWorthEntryType.REAL_ESTATE, 600000.0)
        )
        val points = projectNetWorth(entries, 0.0, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 12, 21))

        // Frozen entries with no rate fields set - net worth never moves,
        // same behavior as before mortgage amortization/appreciation existed.
        points.forEach { assertEquals(500000.0, it.netWorth, 0.001) }
    }

    @Test
    fun testProjectNetWorthFreesTheMortgagePaymentAsSavingsOnceItsPaidOff() {
        // A small mortgage that fully amortizes in 2 months: 500 balance,
        // 0% interest, 250/mo payment -> paid off after month 2.
        val entries = listOf(
            NetWorthEntry(
                "m1", "owner", "Mortgage", NetWorthEntryType.MORTGAGE, 500.0,
                annualInterestRate = 0.0, monthlyPayment = 250.0
            )
        )
        val points = projectNetWorth(entries, 0.0, LocalDate.of(2026, 8, 21), LocalDate.of(2026, 11, 21))

        assertEquals(-500.0, points[0].netWorth, 0.001) // Aug: starting balance
        assertEquals(-250.0, points[1].netWorth, 0.001) // Sep: one payment in
        assertEquals(0.0, points[2].netWorth, 0.001)    // Oct: paid off exactly
        // Nov: no more balance to pay down, and the freed $250/mo payment
        // now counts as extra savings instead of vanishing from the model.
        assertEquals(250.0, points[3].netWorth, 0.001)
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

    @Test
    fun testProjectScenarioCompoundsGrowthOnlyOnTheInvestedBalanceNotCash() {
        val entries = listOf(
            NetWorthEntry("1", "owner", "Brokerage", NetWorthEntryType.INVESTMENT, 10000.0),
            NetWorthEntry("2", "owner", "Chequing", NetWorthEntryType.BANK, 5000.0)
        )
        val scenario = Scenario("s1", "owner", "1%/mo growth", annualMarketGrowthRate = 0.12, investedSavingsFraction = 1.0, recreationalSpendAdjustment = 0.0)
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 10, 21), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21)).points

        assertEquals(3, points.size) // Aug, Sep, Oct
        assertEquals(10000.0, points[0].invested, 0.001)
        assertEquals(5000.0, points[0].cash, 0.001)
        assertEquals(10100.0, points[1].invested, 0.01) // 10000 * 1.01
        assertEquals(5000.0, points[1].cash, 0.001) // cash never grows
        assertEquals(10201.0, points[2].invested, 0.01) // 10100 * 1.01
    }

    @Test
    fun testProjectScenarioSplitsOngoingSavingsBetweenInvestedAndCashByFraction() {
        val entries = emptyList<NetWorthEntry>()
        // A single +3000 transaction averaged over the trailing 3-month
        // window gives a 1000/mo baseline.
        val transactions = listOf(tx("1", "2026-08-15", 3000.0))
        val scenario = Scenario("s1", "owner", "40% invested", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.4, recreationalSpendAdjustment = 0.0)
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 10, 1), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, transactions, goal, LocalDate.of(2026, 8, 21)).points

        val last = points.last()
        assertEquals(800.0, last.invested, 0.01) // 2 months * 1000/mo * 0.4
        assertEquals(1200.0, last.cash, 0.01) // 2 months * 1000/mo * 0.6
        assertEquals(2000.0, last.netWorth, 0.01) // matches total savings since growth is 0
    }

    @Test
    fun testProjectScenarioAppliesTheRecreationalSpendAdjustmentToMonthlySavings() {
        val entries = emptyList<NetWorthEntry>()
        val scenario = Scenario("s1", "owner", "Cut spending", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 200.0)
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 9, 21), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21)).points

        assertEquals(200.0, points.last().cash, 0.001) // 0 baseline + 200 redirected
    }

    @Test
    fun testProjectScenarioAppliesTheSalaryChangeEventOnlyFromItsDateOnward() {
        val entries = emptyList<NetWorthEntry>()
        val scenario = Scenario(
            "s1", "owner", "Raise", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            salaryChangeDate = LocalDate.of(2026, 9, 1), salaryChangeMonthlyDelta = 500.0
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 10, 1), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21)).points

        assertEquals(0.0, points[0].cash, 0.001) // Aug: before the raise
        assertEquals(500.0, points[1].cash, 0.001) // Sep: raise applies
        assertEquals(1000.0, points[2].cash, 0.001) // Oct: raise still in effect
    }

    @Test
    fun testProjectScenarioNeverAppliesASalaryChangeEventBeforeItsDate() {
        val entries = emptyList<NetWorthEntry>()
        val scenario = Scenario(
            "s1", "owner", "Future raise", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            salaryChangeDate = LocalDate.of(2030, 1, 1), salaryChangeMonthlyDelta = 500.0
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 9, 21), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21)).points

        assertEquals(0.0, points.last().cash, 0.001)
    }

    @Test
    fun testProjectScenarioAppliesAnRrspStrategyGeneratingAnAnnualRefund() {
        val entries = emptyList<NetWorthEntry>()
        // A single +6000 transaction averaged over the trailing 3-month
        // window gives a 2000/mo baseline.
        val transactions = listOf(tx("1", "2026-08-15", 6000.0))
        val scenario = Scenario(
            "s1", "owner", "Max RRSP", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            rrspMonthlyContribution = 1000.0, rrspMarginalTaxRate = 0.40, rrspRoomRemaining = 100000.0, rrspReinvestRefund = false
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2027, 8, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, transactions, goal, LocalDate.of(2026, 8, 21))

        assertEquals(12000.0, result.points.last().invested, 0.01) // 12 months * $1000 contributed, no growth
        assertEquals(16800.0, result.points.last().cash, 0.01) // 12 * $1000 non-RRSP savings + $4800 refund
        assertEquals(4800.0, result.totalRrspRefunds, 0.01) // $12000 contributed * 40%
        assertEquals(88000.0, result.finalRrspRoomRemaining, 0.01) // 100000 - 12000
    }

    @Test
    fun testProjectScenarioReinvestsTheRefundWhenReinvestRefundIsTrue() {
        val entries = emptyList<NetWorthEntry>()
        val transactions = listOf(tx("1", "2026-08-15", 6000.0))
        val scenario = Scenario(
            "s1", "owner", "Max RRSP, reinvest", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            rrspMonthlyContribution = 1000.0, rrspMarginalTaxRate = 0.40, rrspRoomRemaining = 100000.0, rrspReinvestRefund = true
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2027, 8, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, transactions, goal, LocalDate.of(2026, 8, 21))

        assertEquals(16800.0, result.points.last().invested, 0.01) // 12000 contributed + 4800 refund reinvested
        assertEquals(12000.0, result.points.last().cash, 0.01) // just the 12 * $1000 non-RRSP savings
    }

    @Test
    fun testProjectScenarioStopsRrspContributionsOnceRoomIsExhausted() {
        val entries = emptyList<NetWorthEntry>()
        val scenario = Scenario(
            "s1", "owner", "Limited room", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            rrspMonthlyContribution = 1000.0, rrspMarginalTaxRate = 0.40, rrspRoomRemaining = 2500.0, rrspReinvestRefund = false
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 12, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21))

        assertEquals(0.0, result.finalRrspRoomRemaining, 0.01)
        assertEquals(2500.0, result.points.last().invested, 0.01) // capped at the available room, not 4 * 1000
        assertEquals(0.0, result.totalRrspRefunds, 0.01) // no 12-month boundary reached yet
    }

    @Test
    fun testProjectScenarioWithNoRrspStrategyReportsZeroRefundsAndZeroRoom() {
        val entries = emptyList<NetWorthEntry>()
        val scenario = Scenario("s1", "owner", "No RRSP", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0)
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 9, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21))

        assertEquals(0.0, result.totalRrspRefunds, 0.001)
        assertEquals(0.0, result.finalRrspRoomRemaining, 0.001)
    }

    @Test
    fun testProjectScenarioAccruesRrspRoomFromTheTaggedIncomeCategoryOnceAYear() {
        val entries = emptyList<NetWorthEntry>()
        // Tagged as the household's income category, within the trailing
        // 12-month window the accrual is computed from.
        val transactions = listOf(tx("1", "2026-08-01", 500000.0, "SALARY"))
        val scenario = Scenario(
            "s1", "owner", "Catch-up", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            rrspRoomRemaining = 0.0, rrspIncomeCategoryId = "SALARY"
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2027, 8, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, transactions, goal, LocalDate.of(2026, 8, 21))

        assertEquals(90000.0, result.finalRrspRoomRemaining, 0.01) // 500000 * 18%
    }

    @Test
    fun testProjectScenarioCapsRrspRoomAccrualAtTheAnnualCapWhenSet() {
        val entries = emptyList<NetWorthEntry>()
        val transactions = listOf(tx("1", "2026-08-01", 500000.0, "SALARY")) // 18% would be 90000, well above the cap
        val scenario = Scenario(
            "s1", "owner", "Catch-up", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            rrspRoomRemaining = 0.0, rrspIncomeCategoryId = "SALARY", rrspAnnualRoomAccrualCap = 31560.0
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2027, 8, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, transactions, goal, LocalDate.of(2026, 8, 21))

        assertEquals(31560.0, result.finalRrspRoomRemaining, 0.01)
    }

    @Test
    fun testProjectScenarioWithNoIncomeCategorySetNeverAccruesRoom() {
        val entries = emptyList<NetWorthEntry>()
        val transactions = listOf(tx("1", "2026-08-01", 500000.0, "SALARY")) // present, but not tagged in the scenario
        val scenario = Scenario(
            "s1", "owner", "No accrual", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0,
            rrspRoomRemaining = 5000.0
        )
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2027, 8, 21), targetAmount = 1.0)

        val result = projectScenario(scenario, entries, transactions, goal, LocalDate.of(2026, 8, 21))

        assertEquals(5000.0, result.finalRrspRoomRemaining, 0.01) // unchanged - no rrspIncomeCategoryId set
    }

    @Test
    fun testProjectScenarioAmortizesAMortgageAndAppreciatesRealEstateOnTopOfCash() {
        val entries = listOf(
            NetWorthEntry(
                "m1", "owner", "Mortgage", NetWorthEntryType.MORTGAGE, 100000.0,
                annualInterestRate = 0.06, monthlyPayment = 1000.0
            ),
            NetWorthEntry(
                "h1", "owner", "House", NetWorthEntryType.REAL_ESTATE, 600000.0,
                annualAppreciationRate = 0.06
            )
        )
        val scenario = Scenario("s1", "owner", "No savings", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0)
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 9, 21), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21)).points

        assertEquals(500000.0, points[0].cash, 0.001) // 600000 house - 100000 mortgage, no savings yet
        // Month 1: mortgage principal paid = 1000 - (100000*0.06/12=500) = 500;
        // house appreciates 600000 * 0.005 = 3000. Cash moves by both.
        assertEquals(503500.0, points[1].cash, 0.01)
    }

    @Test
    fun testProjectScenarioFreesTheMortgagePaymentAsExtraSavingsOnceItsPaidOff() {
        val entries = listOf(
            NetWorthEntry(
                "m1", "owner", "Mortgage", NetWorthEntryType.MORTGAGE, 500.0,
                annualInterestRate = 0.0, monthlyPayment = 250.0
            )
        )
        val scenario = Scenario("s1", "owner", "No savings", annualMarketGrowthRate = 0.0, investedSavingsFraction = 0.0, recreationalSpendAdjustment = 0.0)
        val goal = FinancialGoal("g1", "owner", "Goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2026, 11, 21), targetAmount = 1.0)

        val points = projectScenario(scenario, entries, emptyList(), goal, LocalDate.of(2026, 8, 21)).points

        assertEquals(-500.0, points[0].cash, 0.001) // Aug: starting balance
        assertEquals(-250.0, points[1].cash, 0.001) // Sep: one payment in
        assertEquals(0.0, points[2].cash, 0.001)    // Oct: paid off exactly
        assertEquals(250.0, points[3].cash, 0.001)  // Nov: freed payment counted as savings
    }
}
