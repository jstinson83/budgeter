package com.budgeter

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

// Landing-page ("/") summary: net position, per-account coverage/staleness,
// and a few deterministic spending trends - the first "section" of what the
// spec calls a Household OS dashboard, computed straight from existing
// transaction data rather than a new precomputed/cron layer.

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

data class AccountBalance(val accountType: AccountType, val balance: Double, val asOf: LocalDate)

data class AccountCoverage(
    val accountType: AccountType,
    val earliest: LocalDate,
    val latest: LocalDate,
    val daysSinceLastImport: Long,
    val gaps: List<Pair<LocalDate, LocalDate>>
)

data class CategoryMover(val label: String, val currentTotal: Double, val priorAverage: Double, val delta: Double)

data class NotableTransaction(val description: String, val amount: Double, val date: LocalDate, val accountType: AccountType)

// Latest known balance per account type - the balance on that account's
// most-recent-dated transaction that actually carried one (older rows, or
// ones imported before balance capture existed, may have none). Ties on the
// same date fall back to `transactions`' own order (all(ownerId) returns
// date-descending) - there's no persisted intra-day row-order field to break
// them correctly, and this is a display convenience, not a source of truth.
fun latestBalances(transactions: List<Transaction>): List<AccountBalance> =
    transactions
        .filter { it.balance != null }
        .groupBy { it.accountType }
        .mapNotNull { (accountType, rows) ->
            rows.maxByOrNull { it.date }?.let { AccountBalance(accountType, it.balance!!, it.date) }
        }
        .sortedBy { it.accountType.label }

// Bank balance is cash on hand (an asset); credit-card and LOC balances are
// what's owed (a liability) - this is how TD's own statement export reports
// each, not a sign flip applied here. Only account types with a captured
// balance contribute; one with none yet (nothing re-imported since balance
// capture shipped) is left out rather than assumed to be zero.
fun netPosition(balances: List<AccountBalance>): Double =
    balances.sumOf { if (it.accountType == AccountType.BANK) it.balance else -it.balance }

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
fun dashboardPageModel(transactions: List<Transaction>, categories: List<Category>, today: LocalDate = LocalDate.now()): Map<String, Any?> {
    val currentMonth = YearMonth.from(today)
    val balances = latestBalances(transactions)
    val coverage = accountCoverage(transactions, today)
    val netChangeSeries = monthlyNetChange(transactions, currentMonth, months = 6)
    val maxAbsNetChange = netChangeSeries.maxOfOrNull { kotlin.math.abs(it.second) }?.takeIf { it > 0 } ?: 1.0
    val movers = categoryMovers(transactions, categories, currentMonth)
    val biggest = biggestExpense(transactions, currentMonth)

    return mapOf(
        "hasTransactions" to transactions.isNotEmpty(),
        "hasBalances" to balances.isNotEmpty(),
        "balances" to balances.map {
            mapOf(
                "accountType" to it.accountType.label,
                "balance" to "%.2f".format(it.balance),
                "asOf" to it.asOf.toString()
            )
        },
        "netPosition" to formatSignedAmount(netPosition(balances)),
        "netPositionClass" to amountClass(netPosition(balances)),
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
                "label" to month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                "amount" to formatSignedAmount(total),
                "amountClass" to amountClass(total),
                "barPercent" to ((kotlin.math.abs(total) / maxAbsNetChange) * 100).toInt(),
                "isNegative" to (total < 0)
            )
        },
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
