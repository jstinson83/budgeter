package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class CsvTransactionParserTest {
    @Test
    fun testParsesWellFormedRows() {
        val csv = """
            2026-01-15,Starbucks,4.75,,995.25
            2026-01-16,Payroll,,2500.00,3495.25
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
    fun testIgnoresBalanceAndExtraColumns() {
        val csv = "2026-01-15,Starbucks,4.75,,995.25,extra-column"

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(
            listOf(ParsedTransaction(1, LocalDate.of(2026, 1, 15), "Starbucks", -4.75)),
            result.transactions
        )
    }

    @Test
    fun testHandlesQuotedDescriptionWithComma() {
        val csv = """2026-01-15,"Starbucks, Inc",4.75,,995.25"""

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals("Starbucks, Inc", result.transactions.single().description)
    }

    @Test
    fun testSkipsRowWithTooFewFields() {
        val csv = "2026-01-15,Starbucks,4.75"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertEquals(1, result.errors.single().rowNumber)
        assertTrue(result.errors.single().reason.contains("Expected at least 5 fields"))
    }

    @Test
    fun testSkipsRowWithInvalidDate() {
        val csv = "not-a-date,Starbucks,4.75,,995.25"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().reason.contains("Invalid date"))
    }

    @Test
    fun testSkipsRowWithInvalidMoneyOut() {
        val csv = "2026-01-15,Starbucks,not-a-number,,995.25"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().reason.contains("Invalid amount"))
    }

    @Test
    fun testSkipsRowWithBothMoneyOutAndMoneyInPopulated() {
        val csv = "2026-01-15,Starbucks,4.75,10.00,995.25"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().reason.contains("Invalid amount"))
    }

    @Test
    fun testSkipsRowWithNeitherMoneyOutNorMoneyInPopulated() {
        val csv = "2026-01-15,Starbucks,,,995.25"

        val result = CsvTransactionParser.parse(csv)

        assertTrue(result.transactions.isEmpty())
        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().reason.contains("Invalid amount"))
    }

    @Test
    fun testValidRowsStillParseAroundABadOne() {
        val csv = """
            2026-01-15,Starbucks,4.75,,995.25
            garbage row
            2026-01-16,Payroll,,2500.00,3495.25
        """.trimIndent()

        val result = CsvTransactionParser.parse(csv)

        assertEquals(2, result.transactions.size)
        assertEquals(1, result.errors.size)
        assertEquals(2, result.errors.single().rowNumber)
    }

    @Test
    fun testBlankLinesAreIgnoredNotErrors() {
        val csv = "2026-01-15,Starbucks,4.75,,995.25\n\n\n2026-01-16,Payroll,,2500.00,3495.25"

        val result = CsvTransactionParser.parse(csv)

        assertEquals(2, result.transactions.size)
        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun testParsesSlashDelimitedDate() {
        val csv = "07/01/2026,STM MONTREAL RECHARGE,66.19,,916.69"

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(LocalDate.of(2026, 7, 1), result.transactions.single().date)
    }

    // TD's real chequing/savings export, confirmed against an actual
    // statement: date,description,moneyOut,moneyIn,balance, MM/dd/yyyy
    // dates, exactly one of moneyOut/moneyIn populated per row.
    @Test
    fun testParsesRealTdExport() {
        val csv = """
            07/01/2026,STM MONTREAL RECHARGE,66.19,,916.69
            07/01/2026,BARBER SHOP DU FORT,52.85,,969.54
            07/11/2026,ONLINE PAYMENT - THANK YOU,,450.00,3574.68
            07/04/2026,PHARMAPRIX MONTRÉAL,26.02,,1956.35
        """.trimIndent()

        val result = CsvTransactionParser.parse(csv)

        assertEquals(emptyList(), result.errors)
        assertEquals(4, result.transactions.size)
        assertEquals(-66.19, result.transactions[0].amount)
        assertEquals(450.00, result.transactions[2].amount)
        assertEquals("PHARMAPRIX MONTRÉAL", result.transactions[3].description)
    }
}
