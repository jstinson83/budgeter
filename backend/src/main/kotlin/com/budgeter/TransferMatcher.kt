package com.budgeter

import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Recognizes the fixed statement-generator templates a household's own
// bank/credit-card/LOC transfers use, so those rows can be excluded from
// spending/income analysis entirely - money moving between the user's own
// accounts is neither income nor an expense.
//
// Deterministic rather than Gemini-driven on purpose: every marker below
// (except LOC interest - see matchInterestPairs) is a fixed TD
// statement-generator template (confirmed against real statements) that
// only ever means "this is a transfer" - unlike an amount/date coincidence,
// the description match alone is definitive. That's what makes
// categoryFor() a pure, single-row function with no notion of "matching" at
// all: a "TFR-TO C/C" bank row is a transfer whether or not its
// credit-card-side "PAYMENT - THANK YOU" counterpart has been imported (or
// even correctly categorized) yet - there's nothing to wait for.
object TransferMatcher {
    private const val CC_BANK_TRANSFER_MARKER = "TFR-TO C/C"
    private const val CREDIT_CARD_PAYMENT_MARKER = "PAYMENT - THANK YOU"

    // A LOC transfer can go either direction (draw from it or pay it down),
    // unlike a credit card (which only ever receives payments). The bank
    // side can't be matched against a fixed account number the way "C/C" is
    // fixed for a credit card - only that *something other than* "C/C"
    // follows the marker, since "C/C" specifically means this is a
    // credit-card payment, not a LOC transfer.
    private val BANK_TO_NON_CC_MARKER = Regex("""TFR-TO\s+(?!C/C)\S""", RegexOption.IGNORE_CASE)
    private val BANK_FR_NON_CC_MARKER = Regex("""TFR-FR\s+(?!C/C)\S""", RegexOption.IGNORE_CASE)
    private const val LOC_OUTGOING_MARKER = "TFR-TO"
    private const val LOC_INCOMING_MARKER = "TFR-FR"

    // LOC interest is the one case that genuinely needs a matching partner:
    // "PYT TO:" is TD's general-purpose bill/EFT payment template, used for
    // a payment to *any* payee - not exclusively one covering LOC interest.
    // A lone "PYT TO:" row isn't definitive the way every marker above is,
    // so only a "PYT TO:" row that actually lines up in amount and date
    // with a real LOC "interest" charge is the one confirmed to be covering
    // it (see matchInterestPairs). The interest charge itself is real
    // spending (unlike a transfer) but booked as two ledger entries for one
    // economic event, so only the LOC leg is counted (INTEREST_CATEGORY_ID);
    // the bank leg is excluded like any other transfer to avoid counting
    // the same interest twice.
    private const val LOC_INTEREST_MARKER = "interest"
    private const val BANK_INTEREST_PAYMENT_MARKER = "PYT TO:"

    private const val AMOUNT_EPSILON = 0.01
    const val DATE_WINDOW_DAYS = 5L

    // Single-row, deterministic: which category (if any) this transaction's
    // own description/accountType/amount identify it as, independent of
    // every other transaction. Null means "not a recognized transfer
    // template" - leave it for rules/Gemini as normal.
    fun categoryFor(transaction: Transaction): String? = when {
        transaction.accountType == AccountType.BANK && transaction.amount < 0 &&
            transaction.description.contains(CC_BANK_TRANSFER_MARKER, ignoreCase = true) -> TRANSFER_CATEGORY_ID
        transaction.accountType == AccountType.CREDIT_CARD && transaction.amount > 0 &&
            transaction.description.contains(CREDIT_CARD_PAYMENT_MARKER, ignoreCase = true) -> TRANSFER_CATEGORY_ID
        transaction.accountType == AccountType.BANK && transaction.amount < 0 &&
            BANK_TO_NON_CC_MARKER.containsMatchIn(transaction.description) -> TRANSFER_CATEGORY_ID
        transaction.accountType == AccountType.LOC && transaction.amount > 0 &&
            transaction.description.contains(LOC_INCOMING_MARKER, ignoreCase = true) -> TRANSFER_CATEGORY_ID
        transaction.accountType == AccountType.BANK && transaction.amount > 0 &&
            BANK_FR_NON_CC_MARKER.containsMatchIn(transaction.description) -> TRANSFER_CATEGORY_ID
        transaction.accountType == AccountType.LOC && transaction.amount < 0 &&
            transaction.description.contains(LOC_OUTGOING_MARKER, ignoreCase = true) -> TRANSFER_CATEGORY_ID
        // USD_BANK is the same TD statement format/markers as BANK (see
        // AccountType's doc comment) - just the plain non-CC TFR-TO/TFR-FR
        // markers, confirmed against a real USD account export ("RT460
        // TFR-TO 7GYK40F" funding a CAD transfer). No CC-payment marker for
        // USD_BANK - there's no evidence a USD account ever pays a CAD
        // credit card directly, so that case isn't added speculatively.
        transaction.accountType == AccountType.USD_BANK && transaction.amount < 0 &&
            BANK_TO_NON_CC_MARKER.containsMatchIn(transaction.description) -> TRANSFER_CATEGORY_ID
        transaction.accountType == AccountType.USD_BANK && transaction.amount > 0 &&
            BANK_FR_NON_CC_MARKER.containsMatchIn(transaction.description) -> TRANSFER_CATEGORY_ID
        else -> null
    }

    // Bulk form of categoryFor() - every transaction in the given list that
    // matches a transfer template, mapped to its target category. No
    // pairing, and no notion of ambiguity: unlike matchInterestPairs, a
    // description match here is definitive on its own.
    fun categorize(transactions: List<Transaction>): Map<String, String> =
        transactions.mapNotNull { transaction -> categoryFor(transaction)?.let { transaction.id to it } }.toMap()

    // Keyed by transaction id; both legs of every matched LOC-interest pair
    // map to their (different) target categories. Unlike categorize()
    // above, this still requires a matching counterpart, since "PYT TO:"
    // alone isn't definitive (see its comment) - only a mutually-unique
    // amount/date match confirms a specific "PYT TO:" row is the one
    // covering a specific "interest" charge, rather than an unrelated bill
    // payment. "Mutually-unique" meaning: a candidate with more than one
    // plausible counterpart on the other side (or vice versa) is left alone
    // rather than guessed, since a wrong guess here would misclassify a
    // real bill payment as a transfer.
    fun matchInterestPairs(transactions: List<Transaction>): Map<String, String> {
        val bankLegs = transactions.filter {
            it.accountType == AccountType.BANK && it.description.contains(BANK_INTEREST_PAYMENT_MARKER, ignoreCase = true)
        }
        val locLegs = transactions.filter {
            it.accountType == AccountType.LOC && it.description.contains(LOC_INTEREST_MARKER, ignoreCase = true)
        }

        val plausiblePairs = bankLegs.flatMap { a -> locLegs.filter { b -> isPlausiblePair(a, b) }.map { b -> a to b } }
        val bankMatchCounts = plausiblePairs.groupingBy { it.first.id }.eachCount()
        val locMatchCounts = plausiblePairs.groupingBy { it.second.id }.eachCount()

        val matches = mutableMapOf<String, String>()
        for ((a, b) in plausiblePairs) {
            if (bankMatchCounts[a.id] == 1 && locMatchCounts[b.id] == 1) {
                matches[a.id] = TRANSFER_CATEGORY_ID
                matches[b.id] = INTEREST_CATEGORY_ID
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
