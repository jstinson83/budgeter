package com.budgeter

import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Finds the two ledger entries of a single real-world money movement between
// the user's own accounts (or, for interest, the two entries of a single
// interest charge) so both can be excluded from spending/income analysis
// instead of double-counting money that never left the household - or, for
// interest, counted exactly once instead of twice.
//
// Deterministic rather than Gemini-driven on purpose: every description
// marker below is a fixed statement-generator template (confirmed against
// real statements), not free text with genuine ambiguity for a model to
// resolve - a keyword match plus amount/date confirmation is exact, cheaper,
// and has none of GeminiTransactionCategorizer's failure modes (see
// CLAUDE.md's Gemini gotchas).
//
// Only mutually-unique pairs are matched: a candidate with more than one
// plausible counterpart on the other side (or vice versa) is left alone
// rather than guessed, since a wrong match would silently vanish (or
// mis-bucket) a real transaction rather than just leave it uncategorized.
object TransferMatcher {
    private const val CC_BANK_TRANSFER_MARKER = "TFR-TO C/C"
    private const val CREDIT_CARD_PAYMENT_MARKER = "PAYMENT - THANK YOU"

    // A LOC transfer can go either direction (draw from it or pay it down),
    // unlike a credit card (which only ever receives payments). The bank
    // side can't be matched against a fixed account number the way "C/C" is
    // fixed for a credit card - only that *something other than* "C/C"
    // follows the marker, since "C/C" specifically means the other leg is a
    // credit-card payment, not a LOC transfer.
    private val BANK_TO_NON_CC_MARKER = Regex("""TFR-TO\s+(?!C/C)\S""", RegexOption.IGNORE_CASE)
    private val BANK_FR_NON_CC_MARKER = Regex("""TFR-FR\s+(?!C/C)\S""", RegexOption.IGNORE_CASE)
    private const val LOC_OUTGOING_MARKER = "TFR-TO"
    private const val LOC_INCOMING_MARKER = "TFR-FR"

    // LOC interest: the interest charge itself is real spending (unlike a
    // transfer), but it's booked as two ledger entries for one economic
    // event - the LOC's own "interest" charge and the bank's "PYT TO:
    // <account>" payment that covers it - so only the LOC leg is counted
    // (INTEREST_CATEGORY_ID); the bank leg is excluded like any other
    // transfer to avoid counting the same interest twice.
    private const val LOC_INTEREST_MARKER = "interest"
    private const val BANK_INTEREST_PAYMENT_MARKER = "PYT TO:"

    private const val AMOUNT_EPSILON = 0.01

    // Not private - CategorizationJob uses this to bound how far back/forward
    // it widens its candidate pool when looking for a previously-categorized
    // transfer leg, so that window stays in sync with what this matcher will
    // actually consider plausible.
    const val DATE_WINDOW_DAYS = 5L

    // Keyed by transaction id; every matched transaction (both legs of every
    // matched pair) maps to a category id.
    fun match(transactions: List<Transaction>): Map<String, String> {
        val matches = mutableMapOf<String, String>()

        matches += matchPairs(
            first = transactions.filter {
                it.accountType == AccountType.BANK && it.amount < 0 &&
                    it.description.contains(CC_BANK_TRANSFER_MARKER, ignoreCase = true)
            },
            firstCategory = TRANSFER_CATEGORY_ID,
            second = transactions.filter {
                it.accountType == AccountType.CREDIT_CARD && it.amount > 0 &&
                    it.description.contains(CREDIT_CARD_PAYMENT_MARKER, ignoreCase = true)
            },
            secondCategory = TRANSFER_CATEGORY_ID
        )

        // Paying down the LOC: money leaves the bank account and arrives at
        // the LOC.
        matches += matchPairs(
            first = transactions.filter {
                it.accountType == AccountType.BANK && it.amount < 0 && BANK_TO_NON_CC_MARKER.containsMatchIn(it.description)
            },
            firstCategory = TRANSFER_CATEGORY_ID,
            second = transactions.filter {
                it.accountType == AccountType.LOC && it.amount > 0 &&
                    it.description.contains(LOC_INCOMING_MARKER, ignoreCase = true)
            },
            secondCategory = TRANSFER_CATEGORY_ID
        )

        // Drawing from the LOC: money leaves the LOC and arrives at the bank
        // account.
        matches += matchPairs(
            first = transactions.filter {
                it.accountType == AccountType.BANK && it.amount > 0 && BANK_FR_NON_CC_MARKER.containsMatchIn(it.description)
            },
            firstCategory = TRANSFER_CATEGORY_ID,
            second = transactions.filter {
                it.accountType == AccountType.LOC && it.amount < 0 &&
                    it.description.contains(LOC_OUTGOING_MARKER, ignoreCase = true)
            },
            secondCategory = TRANSFER_CATEGORY_ID
        )

        matches += matchPairs(
            first = transactions.filter {
                it.accountType == AccountType.BANK &&
                    it.description.contains(BANK_INTEREST_PAYMENT_MARKER, ignoreCase = true)
            },
            firstCategory = TRANSFER_CATEGORY_ID,
            second = transactions.filter {
                it.accountType == AccountType.LOC &&
                    it.description.contains(LOC_INTEREST_MARKER, ignoreCase = true)
            },
            secondCategory = INTEREST_CATEGORY_ID
        )

        return matches
    }

    private fun matchPairs(
        first: List<Transaction>,
        firstCategory: String,
        second: List<Transaction>,
        secondCategory: String
    ): Map<String, String> {
        val plausiblePairs = first.flatMap { a ->
            second.filter { b -> isPlausiblePair(a, b) }.map { b -> a to b }
        }

        val firstMatchCounts = plausiblePairs.groupingBy { it.first.id }.eachCount()
        val secondMatchCounts = plausiblePairs.groupingBy { it.second.id }.eachCount()

        val matches = mutableMapOf<String, String>()
        for ((a, b) in plausiblePairs) {
            if (firstMatchCounts[a.id] == 1 && secondMatchCounts[b.id] == 1) {
                matches[a.id] = firstCategory
                matches[b.id] = secondCategory
            }
        }
        return matches
    }

    private fun isPlausiblePair(a: Transaction, b: Transaction): Boolean {
        val amountsMatch = abs(abs(a.amount) - abs(b.amount)) < AMOUNT_EPSILON
        val daysApart = abs(ChronoUnit.DAYS.between(a.date, b.date))
        return amountsMatch && daysApart <= DATE_WINDOW_DAYS
    }
}
