package com.budgeter

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Landing-page ("/") summary: per-account coverage/staleness and a few
// deterministic spending trends - the first "section" of what the spec calls
// a Household OS dashboard, computed straight from existing transaction data
// rather than a new precomputed/cron layer. A net-position (total
// assets/debt) section was tried and pulled - see CLAUDE.md's dashboard
// gotcha for what was learned and why, if it comes back later.

// A stretch with no transactions on one account longer than this is surfaced
// as a "possible missing statement" rather than assumed to be a quiet
// period - one monthly statement cycle plus slack, not a guarantee either
// way (a real account can legitimately go quiet for a few weeks).
private const val GAP_THRESHOLD_DAYS = 21L

// How many days since an account's most recent transaction before its
// coverage card is flagged stale - slightly more than a month, so a single
// statement cycle running a few days late doesn't trigger a false alarm.
private const val STALE_THRESHOLD_DAYS = 35L

// A category needs at least this much trailing-average spend to be surfaced
// as a "mover" - otherwise a category that went from $2 to $8 reads as a
// dramatic 300% swing that isn't actually meaningful.
private const val MOVER_MIN_BASELINE = 20.0

// How many trailing months the "money in/out" trend (bar chart + its single
// summary total) covers by default, before the maintainer picks a longer
// window via the period selector below.
private const val DEFAULT_TREND_MONTHS = 3
private const val SIX_MONTH_TREND_MONTHS = 6

// Upper bound on a custom period's span, so a fat-fingered start date (or a
// deliberately huge range) doesn't render a bar for every month since 1990 -
// three years is already far beyond what "understand longer trends" needs
// given the household's import history starts in 2026.
private const val MAX_CUSTOM_TREND_MONTHS = 36

// The dashboard's time-period selector: two fixed presets plus a custom
// range the maintainer picks explicit dates for. Only the "Money in/out"
// bar chart and "Where it went" pie chart (both below) respect this - the
// coverage cards look at all-time data regardless, and "Categories trending
// up"/"Biggest expense" are always about the real current month, not
// whichever period is selected (see dashboardPageModel).
enum class DashboardPeriodOption(val paramValue: String, val label: String) {
    THREE_MONTHS("3m", "Last 3 months"),
    SIX_MONTHS("6m", "Last 6 months"),
    CUSTOM("custom", "Custom")
}

// startMonth/endMonth are whole calendar months (inclusive) - the trend bar
// chart is inherently a monthly rollup, so a custom range is resolved down
// to the months it touches rather than tracked at day granularity. See
// DashboardRoutes.kt's resolveDashboardPeriod for how user input becomes
// this.
data class DashboardPeriod(val option: DashboardPeriodOption, val startMonth: YearMonth, val endMonth: YearMonth) {
    companion object {
        private fun preset(option: DashboardPeriodOption, months: Int, today: LocalDate): DashboardPeriod {
            val endMonth = YearMonth.from(today)
            return DashboardPeriod(option, endMonth.minusMonths((months - 1).toLong()), endMonth)
        }

        fun default(today: LocalDate): DashboardPeriod = preset(DashboardPeriodOption.THREE_MONTHS, DEFAULT_TREND_MONTHS, today)

        fun sixMonths(today: LocalDate): DashboardPeriod = preset(DashboardPeriodOption.SIX_MONTHS, SIX_MONTH_TREND_MONTHS, today)

        // Resolves a maintainer-picked custom range down to whole calendar
        // months: the end clamps to the current month (no such thing as a
        // future transaction) and the span clamps to MAX_CUSTOM_TREND_MONTHS
        // regardless of what was picked - a swapped/typo'd start date still
        // resolves to something renderable instead of erroring the page.
        fun custom(start: LocalDate, end: LocalDate, today: LocalDate): DashboardPeriod {
            val endMonth = minOf(YearMonth.from(end), YearMonth.from(today))
            val earliestAllowedStart = endMonth.minusMonths((MAX_CUSTOM_TREND_MONTHS - 1).toLong())
            val startMonth = maxOf(minOf(YearMonth.from(start), endMonth), earliestAllowedStart)
            return DashboardPeriod(DashboardPeriodOption.CUSTOM, startMonth, endMonth)
        }
    }
}

data class AccountCoverage(
    val accountType: AccountType,
    val earliest: LocalDate,
    val latest: LocalDate,
    val daysSinceLastImport: Long,
    val gaps: List<Pair<LocalDate, LocalDate>>
)

data class CategoryMover(val label: String, val currentTotal: Double, val priorAverage: Double, val delta: Double)

private fun monthYearLabel(month: YearMonth): String =
    "${month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${month.year}"

data class NotableTransaction(val description: String, val amount: Double, val date: LocalDate, val accountType: AccountType)

// Per account type: the span of dates actually covered by imports, how
// stale the most recent one is, and any internal stretches longer than
// GAP_THRESHOLD_DAYS with no transactions - a proxy for "you're probably
// missing a statement here."
fun accountCoverage(transactions: List<Transaction>, today: LocalDate): List<AccountCoverage> =
    transactions
        .groupBy { it.accountType }
        .map { (accountType, rows) ->
            val dates = rows.map { it.date }.sorted()
            val gaps = dates.zipWithNext().filter { (a, b) -> ChronoUnit.DAYS.between(a, b) > GAP_THRESHOLD_DAYS }
            AccountCoverage(
                accountType,
                earliest = dates.first(),
                latest = dates.last(),
                daysSinceLastImport = ChronoUnit.DAYS.between(dates.last(), today),
                gaps = gaps
            )
        }
        .sortedBy { it.accountType.label }

// TRANSFER/INVESTMENT excluded here the same way AnalysisPage.kt's own
// netChange excludes them - money moving between the user's own accounts (or
// into an investment) isn't spending or income.
private fun analysisEligible(transactions: List<Transaction>): List<Transaction> =
    transactions.filter { it.category != TRANSFER_CATEGORY_ID && it.category != INVESTMENT_CATEGORY_ID }

private fun monthTotal(transactions: List<Transaction>, month: YearMonth): Double {
    val start = month.atDay(1)
    val end = month.plusMonths(1).atDay(1)
    return transactions.filter { !it.date.isBefore(start) && it.date.isBefore(end) }.sumOf { it.amount }
}

// Month-over-month net change for the trailing `months` calendar months
// ending with `endMonth`, oldest first - a cheap, deterministic trend rather
// than anything Gemini-generated.
fun monthlyNetChange(transactions: List<Transaction>, endMonth: YearMonth, months: Int): List<Pair<YearMonth, Double>> {
    val eligible = analysisEligible(transactions)
    return (months - 1 downTo 0).map { offset ->
        val month = endMonth.minusMonths(offset.toLong())
        month to monthTotal(eligible, month)
    }
}

// Categories whose current-month spend has moved meaningfully against their
// own trailing 3-month average, worst (biggest spend increase) first - a
// cheap "what's different this month" signal without an LLM call.
// "Uncategorized" is excluded: it's a bucket to clear out, not a spending
// trend to track.
fun categoryMovers(transactions: List<Transaction>, categories: List<Category>, currentMonth: YearMonth, limit: Int = 3): List<CategoryMover> {
    val labelById = categories.associateBy({ it.id }, { it.label })
    val eligible = analysisEligible(transactions)
    fun totalsFor(month: YearMonth): Map<String, Double> {
        val start = month.atDay(1)
        val end = month.plusMonths(1).atDay(1)
        return eligible
            .filter { !it.date.isBefore(start) && it.date.isBefore(end) && it.category != null }
            .groupBy { it.category!! }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
    }

    val current = totalsFor(currentMonth)
    val priorMonths = (1..3).map { totalsFor(currentMonth.minusMonths(it.toLong())) }
    val categoryIds = current.keys + priorMonths.flatMap { it.keys }

    return categoryIds
        .mapNotNull { categoryId ->
            val currentTotal = current[categoryId] ?: 0.0
            val priorAverage = priorMonths.map { it[categoryId] ?: 0.0 }.average()
            // Only a spending category (net negative) is a "mover" here -
            // an income/investment category swinging isn't the same signal.
            if (currentTotal >= 0 || priorAverage >= 0) return@mapNotNull null
            if (kotlin.math.max(-currentTotal, -priorAverage) < MOVER_MIN_BASELINE) return@mapNotNull null
            CategoryMover(
                label = labelById[categoryId] ?: categoryId,
                currentTotal = currentTotal,
                priorAverage = priorAverage,
                delta = currentTotal - priorAverage
            )
        }
        .sortedBy { it.delta } // most negative delta = spending increased the most
        .take(limit)
}

// Spend by category over [start, end), keyed by label - feeds the
// dashboard's pie chart, which rolls up the same selected period as the
// money in/out bar chart rather than just the current month. TRANSFER is
// excluded (never real spending, see
// analysisEligible above); unlike analysisEligible, INVESTMENT is kept -
// a pie chart answering "where did the money go" should show an investment
// contribution as a real outflow, the same reasoning /analysis's own pie
// chart uses (AnalysisPage.kt).
private fun categoryTotalsByLabel(transactions: List<Transaction>, categories: List<Category>, start: LocalDate, end: LocalDate): Map<String, Double> {
    val labelById = categories.associateBy({ it.id }, { it.label })
    return transactions
        .filter { it.category != TRANSFER_CATEGORY_ID && !it.date.isBefore(start) && it.date.isBefore(end) }
        .groupBy { it.category?.let { id -> labelById[id] ?: id } ?: "Uncategorized" }
        .mapValues { (_, rows) -> rows.sumOf { it.amount } }
}

fun biggestExpense(transactions: List<Transaction>, month: YearMonth): NotableTransaction? {
    val start = month.atDay(1)
    val end = month.plusMonths(1).atDay(1)
    return analysisEligible(transactions)
        .filter { !it.date.isBefore(start) && it.date.isBefore(end) && it.amount < 0 }
        .minByOrNull { it.amount } // most negative = biggest expense
        ?.let { NotableTransaction(it.description, it.amount, it.date, it.accountType) }
}

// Same flattening rationale as AnalysisPage.kt/TransactionPage.kt: FreeMarker
// needs already-display-ready values, not java.time types or data classes.
fun dashboardPageModel(
    transactions: List<Transaction>,
    categories: List<Category>,
    today: LocalDate = LocalDate.now(),
    period: DashboardPeriod = DashboardPeriod.default(today)
): Map<String, Any?> {
    // Movers/biggestExpense are always about the real current month - they
    // don't move with the period selector, see DashboardPeriodOption's doc.
    val currentMonth = YearMonth.from(today)
    val coverage = accountCoverage(transactions, today)
    val trendMonths = (ChronoUnit.MONTHS.between(period.startMonth, period.endMonth) + 1).toInt()
    val netChangeSeries = monthlyNetChange(transactions, period.endMonth, months = trendMonths)
    val netChangeTotal = netChangeSeries.sumOf { it.second }
    val maxAbsNetChange = netChangeSeries.maxOfOrNull { kotlin.math.abs(it.second) }?.takeIf { it > 0 } ?: 1.0
    val movers = categoryMovers(transactions, categories, currentMonth)
    val biggest = biggestExpense(transactions, currentMonth)
    val pieRangeStart = period.startMonth.atDay(1)
    val pieRangeEnd = period.endMonth.plusMonths(1).atDay(1)
    val pieSlices = pieChartModel(categoryTotalsByLabel(transactions, categories, pieRangeStart, pieRangeEnd))
    // A custom range spanning more than one calendar year is ambiguous with
    // a bare "Jun" bar label (which June?) - the two fixed presets never
    // span more than 6 months so this only ever kicks in for custom.
    val includeYearInBarLabel = period.startMonth.year != period.endMonth.year
    val periodLabel = when (period.option) {
        DashboardPeriodOption.CUSTOM -> "${monthYearLabel(period.startMonth)} – ${monthYearLabel(period.endMonth)}"
        else -> period.option.label
    }

    return mapOf(
        "hasTransactions" to transactions.isNotEmpty(),
        "netChangeTrendMonths" to trendMonths,
        "netChangeTotal" to formatSignedAmount(netChangeTotal),
        "netChangeTotalClass" to amountClass(netChangeTotal),
        "periodOption" to period.option.paramValue,
        "periodLabel" to periodLabel,
        "periodStartInput" to period.startMonth.atDay(1).toString(),
        "periodEndInput" to period.endMonth.atEndOfMonth().toString(),
        "coverage" to coverage.map { c ->
            mapOf(
                "accountType" to c.accountType.label,
                "earliest" to c.earliest.toString(),
                "latest" to c.latest.toString(),
                "daysSinceLastImport" to c.daysSinceLastImport,
                "isStale" to (c.daysSinceLastImport > STALE_THRESHOLD_DAYS),
                "gaps" to c.gaps.map { (start, end) ->
                    mapOf(
                        "start" to start.toString(),
                        "end" to end.toString(),
                        "days" to ChronoUnit.DAYS.between(start, end)
                    )
                }
            )
        },
        "netChangeSeries" to netChangeSeries.map { (month, total) ->
            mapOf(
                "label" to if (includeYearInBarLabel) monthYearLabel(month) else month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                "amount" to formatSignedAmount(total),
                "amountClass" to amountClass(total),
                "barPercent" to ((kotlin.math.abs(total) / maxAbsNetChange) * 100).toInt(),
                "isNegative" to (total < 0),
                "href" to "/analysis?year=${month.year}&month=${month.monthValue}"
            )
        },
        "pieSlices" to pieSlices,
        "movers" to movers.map {
            mapOf(
                "label" to it.label,
                "currentTotal" to formatSignedAmount(it.currentTotal),
                "priorAverage" to formatSignedAmount(it.priorAverage)
            )
        },
        "biggestExpense" to biggest?.let {
            mapOf(
                "description" to it.description,
                "amount" to formatSignedAmount(it.amount),
                "date" to it.date.toString(),
                "accountType" to it.accountType.label
            )
        }
    )
}
