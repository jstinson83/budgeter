package com.budgeter

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// Slice 3 of the Financial Planning Projections feature (see
// .claude/current.md) - the actual projection math. Pure Kotlin, no I/O,
// no Gemini anywhere in this file: a monthly time-step from today to a
// goal's target date, starting from the NetWorthEntry baseline
// (NetWorthStore.kt) and adding a constant monthly rate derived from real
// transaction history. No scenario parameters yet (salary-change events,
// market growth, the recreational-spend-vs-savings knob) - that's slice
// 4; this is deliberately just the "what happens if nothing changes"
// baseline scenario, with no compounding/growth term at all.

const val BASELINE_TRAILING_MONTHS = 3

// Average monthly net change (income - expense, TRANSFER/INVESTMENT
// excluded) over the trailing BASELINE_TRAILING_MONTHS calendar months
// ending with the month containing `asOf` - reuses DashboardPage.kt's own
// monthlyNetChange (and its analysisEligible exclusions) rather than
// recomputing the same TRANSFER/INVESTMENT exclusion a second way. This is
// the one number slice 4's scenarios will perturb (salary changes, the
// spend-vs-save knob); slice 3 uses it as-is, unadjusted.
fun baselineMonthlySavingsRate(transactions: List<Transaction>, asOf: LocalDate = LocalDate.now()): Double {
    val series = monthlyNetChange(transactions, YearMonth.from(asOf), BASELINE_TRAILING_MONTHS)
    return series.map { it.second }.average()
}

data class ProjectionPoint(val date: LocalDate, val netWorth: Double)

// Projects net worth forward from `startingNetWorth` at `from`, adding
// `monthlySavingsRate` for every whole calendar month up to (and
// including) `to` - a straight-line projection, deliberately with no
// compounding/market-growth term (see the file doc comment above). One
// point per month, `from`'s own month included as offset 0 so a chart has
// a starting point to draw from. `to` before `from` (a past-due goal)
// yields the single starting point rather than projecting backward.
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
// date - the thing /planning actually renders per goal.
fun projectGoal(goal: FinancialGoal, entries: List<NetWorthEntry>, transactions: List<Transaction>, today: LocalDate = LocalDate.now()): GoalProjection {
    val monthlySavingsRate = baselineMonthlySavingsRate(transactions, today)
    val points = projectNetWorth(netWorthTotal(entries), monthlySavingsRate, today, goal.targetDate)
    return GoalProjection(goal, points, monthlySavingsRate)
}
