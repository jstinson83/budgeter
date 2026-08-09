package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class CsvTransactionParserTest {
    @Test
    fun testParsesWellFormedRows() {
        val csv = """
            2026-01-15,Starbucks,-4.75
            2026-01-16,Payroll,2500.00
        """.trimIndent()

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(
            listOf(
                ParsedTransaction(1, LocalDate.of(2026, 1, 15), "Starbucks", -4.75),
                ParsedTransaction(2, LocalDate.of(2026, 1, 16), "Payroll", 2500.00)
            ),
            result.transactions
        )
    }

    @Test
    fun testIgnoresExtraBalanceColumn() {
        val csv = "2026-01-15,Starbucks,-4.75,1000.00"

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(
            listOf(ParsedTransaction(1, LocalDate.of(2026, 1, 15), "Starbucks", -4.75)),
            result.transactions
        )
    }

    @Test
    fun testHandlesQuotedDescriptionWithComma() {
        val csv = """2026-01-15,"Starbucks, Inc",-4.75"""

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals("Starbucks, Inc", result.transactions.single().description)
    }

    @Test
    fun testSkipsRowWithTooFewFields() {
        val csv = "2026-01-15,Starbucks"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertEquals(1, result.errors.single().rowNumber)
    }

    @Test
    fun testSkipsRowWithInvalidDate() {
        val csv = "not-a-date,Starbucks,-4.75"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().reason.contains("Invalid date"))
    }

    @Test
    fun testSkipsRowWithInvalidAmount() {
        val csv = "2026-01-15,Starbucks,not-a-number"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().reason.contains("Invalid amount"))
    }

    @Test
    fun testValidRowsStillParseAroundABadOne() {
        val csv = """
            2026-01-15,Starbucks,-4.75
            garbage row
            2026-01-16,Payroll,2500.00
        """.trimIndent()

        val result = CsvTransactionParser.parse(csv)

        assertEquals(2, result.transactions.size)
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors.single().rowNumber)
    }

    @Test
    fun testBlankLinesAreIgnoredNotErrors() {
        val csv = "2026-01-15,Starbucks,-4.75\n\n\n2026-01-16,Payroll,2500.00"

        val result = CsvTransactionParser.parse(csv)

        assertEquals(2, result.transactions.size)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun testParsesSlashDelimitedDate() {
        val csv = "07/01/2026,STM MONTREAL RECHARGE,66.19,916.69"

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(LocalDate.of(2026, 7, 1), result.transactions.single().date)
    }

    // A real sample export (headerless date,description,amount,balance,
    // MM/dd/yyyy dates, unsigned "charge" amounts with balance increasing -
    // a credit-card-style statement, not a chequing account) surfaced the
    // MM/dd/yyyy gap this test locks in.
    @Test
    fun testParsesSampleCreditCardStyleExport() {
        val csv = """
            07/01/2026,STM MONTREAL RECHARGE,66.19,916.69
            07/01/2026,BARBER SHOP DU FORT,52.85,969.54
            07/11/2026,ONLINE PAYMENT - THANK YOU,-450,3574.68
            07/04/2026,PHARMAPRIX MONTRÉAL,26.02,1956.35
        """.trimIndent()

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(4, result.transactions.size)
        assertEquals(-450.0, result.transactions[2].amount)
        assertEquals("PHARMAPRIX MONTRÉAL", result.transactions[3].description)
    }
}
