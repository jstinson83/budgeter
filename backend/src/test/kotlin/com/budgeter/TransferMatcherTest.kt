package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class TransferMatcherTest {
    private fun transaction(
        id: String,
        accountType: AccountType,
        description: String,
        amount: Double,
        date: LocalDate = LocalDate.of(2026, 7, 1)
    ): Transaction = Transaction(id, "owner", accountType, date, description, amount)

    @Test
    fun testCategorizesABankTransferLegEvenWithNoCreditCardLegPresent() {
        // No partner needed - the description alone is a fixed,
        // unambiguous TD template.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00)

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID), TransferMatcher.categorize(listOf(bank)))
    }

    @Test
    fun testCategorizesACreditCardPaymentLegEvenWithNoBankLegPresent() {
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00)

        assertEquals(mapOf("cc-1" to TRANSFER_CATEGORY_ID), TransferMatcher.categorize(listOf(creditCard)))
    }

    @Test
    fun testCategorizesBothLegsOfABankToCreditCardTransferIndependently() {
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 7, 1))
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 7, 3))

        val result = TransferMatcher.categorize(listOf(bank, creditCard))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "cc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testIgnoresBankDescriptionsThatDoNotLookLikeACardTransfer() {
        val bank = transaction("bank-1", AccountType.BANK, "AMAZON.CA", -200.00)

        assertEquals(emptyMap(), TransferMatcher.categorize(listOf(bank)))
    }

    @Test
    fun testIgnoresCreditCardDescriptionsThatAreNotThePaymentReceivedTemplate() {
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "STARBUCKS", 200.00)

        assertEquals(emptyMap(), TransferMatcher.categorize(listOf(creditCard)))
    }

    @Test
    fun testRequiresOppositeSigns() {
        // A refund/credit on the bank side (positive) can't be the "paid
        // the card" leg no matter what the description says.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", 200.00)

        assertEquals(emptyMap(), TransferMatcher.categorize(listOf(bank)))
    }

    @Test
    fun testRequiresMatchingAccountType() {
        // The bank-side marker is still recognized on its own; the same
        // text on a BANK-typed row instead of CREDIT_CARD is not.
        val bank1 = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00)
        val bank2 = transaction("bank-2", AccountType.BANK, "PAYMENT - THANK YOU", 200.00)

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID), TransferMatcher.categorize(listOf(bank1, bank2)))
    }

    @Test
    fun testAmountOrDateDifferencesDoNotPreventEitherLegFromBeingCategorized() {
        // Unlike the old pair-matching design, there's no cross-referencing
        // here at all - each leg is judged purely on its own description/
        // accountType/amount, so a mismatched counterpart (or none at all)
        // never blocks a genuine transfer template from being recognized.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 7, 1))
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 199.00, LocalDate.of(2026, 7, 20))

        val result = TransferMatcher.categorize(listOf(bank, creditCard))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "cc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testCategorizesMultipleDistinctTransferLegsIndependently() {
        val bank1 = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 6, 1))
        val creditCard1 = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 6, 2))
        val bank2 = transaction("bank-2", AccountType.BANK, ".....TFR-TO C/C", -75.00, LocalDate.of(2026, 7, 1))
        val creditCard2 = transaction("cc-2", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 75.00, LocalDate.of(2026, 7, 2))

        val result = TransferMatcher.categorize(listOf(bank1, creditCard1, bank2, creditCard2))

        assertEquals(
            mapOf(
                "bank-1" to TRANSFER_CATEGORY_ID,
                "cc-1" to TRANSFER_CATEGORY_ID,
                "bank-2" to TRANSFER_CATEGORY_ID,
                "cc-2" to TRANSFER_CATEGORY_ID
            ),
            result
        )
    }

    @Test
    fun testCategorizesABankTransferLegWithLocPayDownMarker() {
        // Money leaves the bank account and pays down the LOC - each leg
        // recognized on its own.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO 1234567", -200.00, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, ".....TFR-FR 000998877", 200.00, LocalDate.of(2026, 7, 3))

        val result = TransferMatcher.categorize(listOf(bank, loc))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "loc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testCategorizesABankTransferLegWithLocDrawMarker() {
        // Money is drawn from the LOC and arrives in the bank account.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-FR 000998877", 200.00, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, ".....TFR-TO 1234567", -200.00, LocalDate.of(2026, 7, 3))

        val result = TransferMatcher.categorize(listOf(bank, loc))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "loc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testDoesNotMistakeACreditCardTransferMarkerForALocTransferMarker() {
        // "TFR-TO C/C" contains "TFR-TO" but is a credit-card transfer, not
        // a LOC one - the "not C/C" guard on the bank-side LOC marker must
        // exclude it, so this bank row is categorized via the credit-card
        // branch only, not double-matched or mismatched via the LOC one.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00)

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID), TransferMatcher.categorize(listOf(bank)))
    }

    @Test
    fun testMatchesLocInterestWithItsBankPaymentLegAsInterestNotTransfer() {
        val bank = transaction("bank-1", AccountType.BANK, "PYT TO: 000998877", -12.34, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, "INTEREST", -12.34, LocalDate.of(2026, 7, 1))

        val result = TransferMatcher.matchInterestPairs(listOf(bank, loc))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "loc-1" to INTEREST_CATEGORY_ID), result)
    }

    @Test
    fun testDoesNotCategorizeALoneBankBillPaymentAsInterestTransfer() {
        // "PYT TO:" alone is TD's general bill-payment template, not
        // transfer-specific - with no LOC "interest" charge to confirm it,
        // it must NOT be swept into the transfer bucket by categorize().
        val bank = transaction("bank-1", AccountType.BANK, "PYT TO: Hydro Company", -85.00)

        assertEquals(emptyMap(), TransferMatcher.categorize(listOf(bank)))
        assertEquals(emptyMap(), TransferMatcher.matchInterestPairs(listOf(bank)))
    }

    @Test
    fun testInterestPairDoesNotMatchWhenAmountsDiffer() {
        val bank = transaction("bank-1", AccountType.BANK, "PYT TO: 000998877", -12.34)
        val loc = transaction("loc-1", AccountType.LOC, "INTEREST", -10.00)

        assertEquals(emptyMap(), TransferMatcher.matchInterestPairs(listOf(bank, loc)))
    }

    @Test
    fun testInterestPairDoesNotMatchWhenDatesAreTooFarApart() {
        val bank = transaction("bank-1", AccountType.BANK, "PYT TO: 000998877", -12.34, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, "INTEREST", -12.34, LocalDate.of(2026, 7, 20))

        assertEquals(emptyMap(), TransferMatcher.matchInterestPairs(listOf(bank, loc)))
    }

    @Test
    fun testInterestPairLeavesAnAmbiguousMatchUnresolvedRatherThanGuessing() {
        // Two candidate LOC interest charges for the same bank payment,
        // same amount, both within the date window - genuinely ambiguous
        // which one this "PYT TO:" row actually covers, so neither should
        // be auto-matched (a wrong guess here would misclassify a real
        // bill payment as a transfer).
        val bank = transaction("bank-1", AccountType.BANK, "PYT TO: 000998877", -12.34, LocalDate.of(2026, 7, 1))
        val loc1 = transaction("loc-1", AccountType.LOC, "INTEREST", -12.34, LocalDate.of(2026, 7, 2))
        val loc2 = transaction("loc-2", AccountType.LOC, "INTEREST", -12.34, LocalDate.of(2026, 7, 3))

        assertEquals(emptyMap(), TransferMatcher.matchInterestPairs(listOf(bank, loc1, loc2)))
    }
}
