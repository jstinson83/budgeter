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
    fun testMatchesABankTransferLegWithItsCreditCardPaymentLeg() {
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 7, 1))
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 7, 3))

        val result = TransferMatcher.match(listOf(bank, creditCard))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "cc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testIgnoresBankDescriptionsThatDoNotLookLikeACardTransfer() {
        val bank = transaction("bank-1", AccountType.BANK, "AMAZON.CA", -200.00)
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00)

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, creditCard)))
    }

    @Test
    fun testIgnoresCreditCardDescriptionsThatAreNotThePaymentReceivedTemplate() {
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00)
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "STARBUCKS", 200.00)

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, creditCard)))
    }

    @Test
    fun testRequiresOppositeSigns() {
        // A refund/credit on the bank side (positive) can't be the "paid
        // the card" leg no matter what the description says.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", 200.00)
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00)

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, creditCard)))
    }

    @Test
    fun testRequiresMatchingAccountTypesNotJustDescriptions() {
        // Both rows on the bank account - not a bank/card pair at all.
        val bank1 = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00)
        val bank2 = transaction("bank-2", AccountType.BANK, "PAYMENT - THANK YOU", 200.00)

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank1, bank2)))
    }

    @Test
    fun testDoesNotMatchWhenAmountsDiffer() {
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00)
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 199.00)

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, creditCard)))
    }

    @Test
    fun testDoesNotMatchWhenDatesAreTooFarApart() {
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 7, 1))
        val creditCard = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 7, 20))

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, creditCard)))
    }

    @Test
    fun testLeavesAnAmbiguousPairUnmatchedRatherThanGuessing() {
        // Two candidates on the credit-card side for the same bank leg,
        // same amount, both within the date window - genuinely ambiguous
        // which one is the real match, so neither should be auto-matched.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 7, 1))
        val creditCard1 = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 7, 2))
        val creditCard2 = transaction("cc-2", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 7, 3))

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, creditCard1, creditCard2)))
    }

    @Test
    fun testMatchesMultipleDistinctTransferPairsIndependently() {
        val bank1 = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 6, 1))
        val creditCard1 = transaction("cc-1", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 200.00, LocalDate.of(2026, 6, 2))
        val bank2 = transaction("bank-2", AccountType.BANK, ".....TFR-TO C/C", -75.00, LocalDate.of(2026, 7, 1))
        val creditCard2 = transaction("cc-2", AccountType.CREDIT_CARD, "PAYMENT - THANK YOU", 75.00, LocalDate.of(2026, 7, 2))

        val result = TransferMatcher.match(listOf(bank1, creditCard1, bank2, creditCard2))

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
    fun testMatchesABankTransferLegWithItsLocPayDownLeg() {
        // Money leaves the bank account and pays down the LOC.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO 1234567", -200.00, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, ".....TFR-FR 000998877", 200.00, LocalDate.of(2026, 7, 3))

        val result = TransferMatcher.match(listOf(bank, loc))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "loc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testMatchesABankTransferLegWithItsLocDrawLeg() {
        // Money is drawn from the LOC and arrives in the bank account.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-FR 000998877", 200.00, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, ".....TFR-TO 1234567", -200.00, LocalDate.of(2026, 7, 3))

        val result = TransferMatcher.match(listOf(bank, loc))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "loc-1" to TRANSFER_CATEGORY_ID), result)
    }

    @Test
    fun testDoesNotMistakeACreditCardTransferForALocTransfer() {
        // "TFR-TO C/C" contains "TFR-TO" but is a credit-card transfer, not
        // a LOC one - the "not C/C" guard on the bank-side LOC marker must
        // exclude it so it's only ever matched against the CC candidate set.
        val bank = transaction("bank-1", AccountType.BANK, ".....TFR-TO C/C", -200.00, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, ".....TFR-FR 000998877", 200.00, LocalDate.of(2026, 7, 3))

        assertEquals(emptyMap(), TransferMatcher.match(listOf(bank, loc)))
    }

    @Test
    fun testMatchesLocInterestWithItsBankPaymentLegAsInterestNotTransfer() {
        val bank = transaction("bank-1", AccountType.BANK, "PYT TO: 000998877", -12.34, LocalDate.of(2026, 7, 1))
        val loc = transaction("loc-1", AccountType.LOC, "INTEREST", -12.34, LocalDate.of(2026, 7, 1))

        val result = TransferMatcher.match(listOf(bank, loc))

        assertEquals(mapOf("bank-1" to TRANSFER_CATEGORY_ID, "loc-1" to INTEREST_CATEGORY_ID), result)
    }
}
