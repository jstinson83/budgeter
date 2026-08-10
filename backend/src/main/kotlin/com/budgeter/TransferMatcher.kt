package com.budgeter

import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Finds the bank-side "paid the credit card" leg and the credit-card-side
// "received a payment" leg of the same transfer, so both can be excluded
// from spending/income analysis instead of double-counting money that never
// left the household.
//
// Deterministic rather than Gemini-driven on purpose: both description
// markers below are fixed statement-generator templates (confirmed against
// real TD statements), not free text with genuine ambiguity for a model to
// resolve - a keyword match plus amount/date confirmation is exact, cheaper,
// and has none of GeminiTransactionCategorizer's failure modes (see
// CLAUDE.md's Gemini gotchas).
//
// Only mutually-unique pairs are matched: a bank candidate with more than
// one plausible credit-card counterpart (or vice versa) is left alone
// rather than guessed, since a wrong TRANSFER match would silently vanish a
// real transaction from analysis rather than just leave it uncategorized.
object TransferMatcher {
    private const val BANK_TRANSFER_MARKER = "TFR-TO C/C"
    private const val CREDIT_CARD_PAYMENT_MARKER = "PAYMENT - THANK YOU"
    private const val AMOUNT_EPSILON = 0.01
    private const val DATE_WINDOW_DAYS = 5L

    // Keyed by transaction id; every matched transaction (both legs of every
    // matched pair) maps to TRANSFER_CATEGORY_ID.
    fun match(transactions: List<Transaction>): Map<String, String> {
        val bankCandidates = transactions.filter {
            it.accountType == AccountType.BANK && it.amount < 0 &&
                it.description.contains(BANK_TRANSFER_MARKER, ignoreCase = true)
        }
        val creditCardCandidates = transactions.filter {
            it.accountType == AccountType.CREDIT_CARD && it.amount > 0 &&
                it.description.contains(CREDIT_CARD_PAYMENT_MARKER, ignoreCase = true)
        }

        val plausiblePairs = bankCandidates.flatMap { bank ->
            creditCardCandidates.filter { creditCard -> isPlausiblePair(bank, creditCard) }.map { creditCard -> bank to creditCard }
        }

        val bankMatchCounts = plausiblePairs.groupingBy { it.first.id }.eachCount()
        val creditCardMatchCounts = plausiblePairs.groupingBy { it.second.id }.eachCount()

        val matches = mutableMapOf<String, String>()
        for ((bank, creditCard) in plausiblePairs) {
            if (bankMatchCounts[bank.id] == 1 && creditCardMatchCounts[creditCard.id] == 1) {
                matches[bank.id] = TRANSFER_CATEGORY_ID
                matches[creditCard.id] = TRANSFER_CATEGORY_ID
            }
        }
        return matches
    }

    private fun isPlausiblePair(bank: Transaction, creditCard: Transaction): Boolean {
        val amountsMatch = abs(abs(bank.amount) - creditCard.amount) < AMOUNT_EPSILON
        val daysApart = abs(ChronoUnit.DAYS.between(bank.date, creditCard.date))
        return amountsMatch && daysApart <= DATE_WINDOW_DAYS
    }
}
