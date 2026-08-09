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
                ParsedTransaction(LocalDate.of(2026, 1, 15), "Starbucks", -4.75),
                ParsedTransaction(LocalDate.of(2026, 1, 16), "Payroll", 2500.00)
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
            listOf(ParsedTransaction(LocalDate.of(2026, 1, 15), "Starbucks", -4.75)),
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
}
