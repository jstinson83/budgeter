package com.budgeter

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// Slices 3-4+ of the Financial Planning Projections feature (see
// .claude/current.md) - the actual projection math. Pure Kotlin, no I/O,
// no Gemini anywhere in this file: a monthly time-step from today to a
// goal's target date, starting from the NetWorthEntry baseline
// (NetWorthStore.kt) and adding a monthly rate derived from real
// transaction history, optionally perturbed by a Scenario (salary-change
// events, market growth, the recreational-spend-vs-savings knob, an RRSP
// contribution/refund strategy - all defined in ScenarioStore.kt).

const val BASELINE_TRAILING_MONTHS = 3

// Average monthly net change (income - expense, TRANSFER/INVESTMENT
// excluded) over the trailing BASELINE_TRAILING_MONTHS calendar months
// ending with the month containing `asOf` - reuses DashboardPage.kt's own
// monthlyNetChange (and its analysisEligible exclusions).
//
// INVESTMENT is correctly excluded here, same as TRANSFER - this was
// re-examined mid-session after a "does this account for RRSP
// contributions" question raised a false alarm, worth recording so it
// isn't relitigated: excluding an INVESTMENT-categorized transaction from
// this sum does NOT erase it from the projection. An RRSP contribution
// shows up as a negative amount in the bank account, categorized
// INVESTMENT; since it's excluded (neither added nor subtracted), the sum
// is just income minus actual spending - which already equals the full
// amount NOT spent, regardless of whether that amount sits in cash or
// moved into an investment account. Subtracting the INVESTMENT-categorized
// outflow (i.e. including it in the sum, sign and all) would actually be
// the bug: it would double-count the contribution as if it left net worth
// entirely, when nothing here ever added a matching increase for the
// investment account it landed in (RRSP/brokerage balances aren't
// CSV-imported at all, so there's no "+" transaction to offset it). This
// is the one number a Scenario's salary-change events and
// recreationalSpendAdjustment perturb (see projectScenario below) -
// baseline projections (projectGoal) use it as-is, unadjusted.
fun baselineMonthlySavingsRate(transactions: List<Transaction>, asOf: LocalDate = LocalDate.now()): Double {
    val series = monthlyNetChange(transactions, YearMonth.from(asOf), BASELINE_TRAILING_MONTHS)
    return series.map { it.second }.average()
}

data class ProjectionPoint(val date: LocalDate, val netWorth: Double)

// Projects net worth forward from `startingNetWorth` at `from`, adding
// `monthlySavingsRate` for every whole calendar month up to (and
// including) `to` - a straight-line projection, deliberately with no
// compounding/market-growth term (that's what projectScenario below adds
// on top). One point per month, `from`'s own month included as offset 0
// so a chart has a starting point to draw from. `to` before `from` (a
// past-due goal) yields the single starting point rather than projecting
// backward.
fun projectNetWorth(startingNetWorth: Double, monthlySavingsRate: Double, from: LocalDate, to: LocalDate): List<ProjectionPoint> {
    val startMonth = YearMonth.from(from)
    val endMonth = YearMonth.from(to)
    val months = ChronoUnit.MONTHS.between(startMonth, endMonth).coerceAtLeast(0)
    return (0..months).map { offset ->
        ProjectionPoint(startMonth.plusMonths(offset).atDay(1), startingNetWorth + monthlySavingsRate * offset)
    }
}

data class GoalProjection(val goal: FinancialGoal, val points: List<ProjectionPoint>, val monthlySavingsRate: Double) {
    val finalNetWorth: Double get() = points.last().netWorth
    val onTrack: Boolean get() = finalNetWorth >= goal.resolvedTargetAmount()

    // Always non-negative - "how far off" regardless of direction. Which
    // one it means is carried by onTrack, not the sign of this value, so a
    // caller can't accidentally treat a shortfall as a surplus by skipping
    // the onTrack check.
    val shortfallOrSurplus: Double get() = kotlin.math.abs(finalNetWorth - goal.resolvedTargetAmount())
}

// Ties the baseline rate + starting net worth to one goal's own target
// date - the "what happens if nothing changes" line /planning always
// shows per goal, alongside whichever Scenario lines the household has
// defined (projectScenario below).
fun projectGoal(goal: FinancialGoal, entries: List<NetWorthEntry>, transactions: List<Transaction>, today: LocalDate = LocalDate.now()): GoalProjection {
    val monthlySavingsRate = baselineMonthlySavingsRate(transactions, today)
    val points = projectNetWorth(netWorthTotal(entries), monthlySavingsRate, today, goal.targetDate)
    return GoalProjection(goal, points, monthlySavingsRate)
}

// A scenario tracks net worth as two separate running balances rather
// than one combined figure, because growth only applies to one of them:
// `invested` starts from the sum of INVESTMENT-typed NetWorthEntry
// balances and compounds monthly at the scenario's growth rate; `cash`
// starts from everything else (bank, real estate, other assets, minus
// liabilities) and never grows. Each month's savings then splits between
// the two according to investedSavingsFraction - see Scenario's own doc
// comments in ScenarioStore.kt for why this split exists (without it,
// ongoing savings would silently earn 0% forever even in an "aggressive
// growth" scenario, while only pre-existing investment balances compound).
data class ScenarioProjectionPoint(val date: LocalDate, val invested: Double, val cash: Double) {
    val netWorth: Double get() = invested + cash
}

// How often an RRSP strategy's accumulated contributions turn into a cash
// refund - a fixed 12-month anniversary of the projection's own start
// date, not the real Jan-Dec tax year or actual filing/refund timing
// (which depends on when a return is filed). A deliberate simplification,
// same spirit as the rest of this engine's monthly-granularity modeling -
// see Scenario's doc comment in ScenarioStore.kt.
private const val RRSP_REFUND_INTERVAL_MONTHS = 12

// The real CRA rule: RRSP room accrues each year as 18% of earned income
// (capped at an annual dollar maximum that changes yearly - see
// Scenario's rrspAnnualRoomAccrualCap doc comment in ScenarioStore.kt for
// why that cap is a user-entered optional number, not hardcoded here).
// 18% itself is a stable, long-standing rule (unlike the dollar cap or a
// tax bracket), so it's safe to encode directly rather than asking the
// household to type it in.
private const val RRSP_ROOM_ACCRUAL_RATE = 0.18

// totalRrspRefunds/finalRrspRoomRemaining are 0.0/the starting room
// respectively for a scenario with no RRSP strategy - always present
// (rather than nullable) so /planning's page model doesn't need to know
// which scenarios opted in before deciding whether to show them.
data class ScenarioProjection(
    val points: List<ScenarioProjectionPoint>,
    val totalRrspRefunds: Double,
    val finalRrspRoomRemaining: Double
)

fun projectScenario(scenario: Scenario, entries: List<NetWorthEntry>, transactions: List<Transaction>, goal: FinancialGoal, today: LocalDate = LocalDate.now()): ScenarioProjection {
    val startingInvested = entries.filter { it.type == NetWorthEntryType.INVESTMENT }.sumOf { it.value }
    val startingCash = netWorthTotal(entries) - startingInvested
    val baselineRate = baselineMonthlySavingsRate(transactions, today)
    val monthlyGrowthRate = scenario.annualMarketGrowthRate / 12.0

    // Held constant for the whole projection (the same "trailing history,
    // assumed to repeat" simplification baselineMonthlySavingsRate already
    // uses) - the engine has no way to project future income growth, only
    // to read what a household has already tagged with this category.
    val annualIncome = scenario.rrspIncomeCategoryId?.let { categoryId ->
        val start = today.minusMonths(12)
        transactions.filter { it.category == categoryId && !it.date.isBefore(start) && it.date.isBefore(today) }.sumOf { it.amount }
    } ?: 0.0

    val startMonth = YearMonth.from(today)
    val endMonth = YearMonth.from(goal.targetDate)
    val months = ChronoUnit.MONTHS.between(startMonth, endMonth).coerceAtLeast(0)

    val points = mutableListOf(ScenarioProjectionPoint(startMonth.atDay(1), startingInvested, startingCash))
    var invested = startingInvested
    var cash = startingCash
    var rrspRoomRemaining = scenario.rrspRoomRemaining ?: 0.0
    var annualRrspContributions = 0.0
    var totalRrspRefunds = 0.0
    for (offset in 1..months) {
        val monthStart = startMonth.plusMonths(offset).atDay(1)
        // Salary-change event applies from its own date onward - a
        // one-time step change to the baseline rate, not a fresh average.
        val salaryDelta = if (scenario.salaryChangeDate != null && !monthStart.isBefore(scenario.salaryChangeDate)) {
            scenario.salaryChangeMonthlyDelta ?: 0.0
        } else {
            0.0
        }
        val monthlySavings = baselineRate + scenario.recreationalSpendAdjustment + salaryDelta

        // The RRSP contribution redirects part of this month's savings
        // into a fully-invested, room-limited bucket rather than adding
        // extra money on top - capped by whatever room is left, so the
        // simulation can't keep contributing once the room is exhausted.
        // Room only decreases here unless rrspIncomeCategoryId is set (see
        // the accrual step below), which is why "resume normal investing
        // once caught up" falls out of this cap alone with no separate
        // switch needed.
        val rrspContribution = if (scenario.rrspMonthlyContribution != null) {
            minOf(scenario.rrspMonthlyContribution, rrspRoomRemaining).coerceAtLeast(0.0)
        } else {
            0.0
        }
        rrspRoomRemaining -= rrspContribution
        annualRrspContributions += rrspContribution

        val remainingSavings = monthlySavings - rrspContribution
        invested = invested * (1 + monthlyGrowthRate) + remainingSavings * scenario.investedSavingsFraction + rrspContribution
        cash += remainingSavings * (1 - scenario.investedSavingsFraction)

        if (offset % RRSP_REFUND_INTERVAL_MONTHS == 0L) {
            if (scenario.rrspMonthlyContribution != null) {
                val refund = annualRrspContributions * (scenario.rrspMarginalTaxRate ?: 0.0)
                if (scenario.rrspReinvestRefund) invested += refund else cash += refund
                totalRrspRefunds += refund
                annualRrspContributions = 0.0
            }
            // New room accrues independently of whether this scenario is
            // actively contributing - a household might track accrual
            // without a current contribution plan, or (the catch-up case
            // this was built for) keep accruing a little more room on top
            // of a large existing pool being drawn down.
            if (scenario.rrspIncomeCategoryId != null) {
                val rawAccrual = annualIncome * RRSP_ROOM_ACCRUAL_RATE
                val accrual = scenario.rrspAnnualRoomAccrualCap?.let { cap -> minOf(rawAccrual, cap) } ?: rawAccrual
                rrspRoomRemaining += accrual.coerceAtLeast(0.0)
            }
        }

        points += ScenarioProjectionPoint(monthStart, invested, cash)
    }
    return ScenarioProjection(points, totalRrspRefunds, rrspRoomRemaining)
}
