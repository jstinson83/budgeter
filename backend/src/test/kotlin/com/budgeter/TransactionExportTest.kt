package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class TransactionExportTest {
    private fun tx(
        id: String,
        accountType: AccountType = AccountType.BANK,
        date: LocalDate,
        description: String = "Transaction",
        amount: Double,
        category: String? = null
    ): Transaction = Transaction(id, "owner", accountType, date, description, amount, category)

    private fun category(id: String, label: String): Category = Category(id, "owner", label)

    @Test
    fun testTransactionsToCsvIncludesAHeaderAndOneRowPerTransactionSortedByDate() {
        val csv = transactionsToCsv(
            listOf(
                tx("2", date = LocalDate.of(2026, 6, 15), description = "Later", amount = -10.0),
                tx("1", date = LocalDate.of(2026, 6, 1), description = "Earlier", amount = 50.0)
            ),
            emptyList()
        )

        val lines = csv.trim().lines()
        assertEquals("Date,Account,Description,Amount,Category", lines[0])
        assertTrue(lines[1].startsWith("2026-06-01,Bank,Earlier,50.0"))
        assertTrue(lines[2].startsWith("2026-06-15,Bank,Later,-10.0"))
    }

    @Test
    fun testTransactionsToCsvResolvesCategoryIdsToLabels() {
        val csv = transactionsToCsv(
            listOf(tx("1", date = LocalDate.of(2026, 6, 1), amount = -10.0, category = "GROCERIES")),
            listOf(category("GROCERIES", "Groceries"))
        )

        assertTrue(csv.contains("Groceries"))
    }

    @Test
    fun testTransactionsToCsvMarksNullCategoryAsUncategorized() {
        val csv = transactionsToCsv(
            listOf(tx("1", date = LocalDate.of(2026, 6, 1), amount = -10.0, category = null)),
            emptyList()
        )

        assertTrue(csv.contains("Uncategorized"))
    }

    // The whole point of surfacing this at all - TRANSFER isn't a real
    // Category row (see CategoryStore.kt), so without this special case a
    // transfer would either show up blank or as the raw "TRANSFER" id.
    @Test
    fun testTransactionsToCsvIdentifiesTransfersByLabel() {
        val csv = transactionsToCsv(
            listOf(tx("1", date = LocalDate.of(2026, 6, 1), amount = -500.0, category = TRANSFER_CATEGORY_ID)),
            emptyList()
        )

        assertTrue(csv.contains("Transfer"))
        assertFalse(csv.contains("TRANSFER"))
    }
}
