package com.budgeter

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class ParsedTransaction(val rowNumber: Int, val accountType: AccountType, val date: LocalDate, val description: String, val amount: Double)

data class CsvRowError(val rowNumber: Int, val rawLine: String, val reason: String)

data class CsvParseResult(val transactions: List<ParsedTransaction>, val errors: List<CsvRowError>)

// Headerless CSV, TD's real export format for both chequing/savings and
// credit card accounts - the same 5 columns and sign convention either way:
// date,description,moneyOut,moneyIn,balance (5 columns; anything past
// column 4 is ignored). Exactly one of moneyOut/moneyIn is populated per
// row - the other is blank. moneyOut becomes a negative amount (money
// leaving the account - a purchase, on either account type), moneyIn a
// positive one (money coming in - a deposit on a bank account, a payment on
// a credit card); balance is discarded. The CSV itself never states which
// account it came from, so the caller passes accountType based on what the
// user selected at upload time.
//
// Date accepts either ISO-8601 (yyyy-MM-dd, for synthetic test data) or
// MM/dd/yyyy (TD's real date format) - see supportedDateFormats.
//
// Field-level splitting/quoting/escaping is delegated to Apache Commons CSV
// rather than a hand-rolled line parser - RFC4180 edge cases (quoted
// newlines, escaped quotes, CRLF vs LF) are exactly the kind of thing not
// worth re-deriving.
object CsvTransactionParser {
    private const val EXPECTED_FIELD_COUNT = 5

    private val format: CSVFormat = CSVFormat.DEFAULT.builder()
        .setTrim(true)
        .setIgnoreEmptyLines(true)
        .build()

    private val supportedDateFormats = listOf(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("MM/dd/yyyy")
    )

    private fun parseDate(value: String): LocalDate? {
        for (dateFormat in supportedDateFormats) {
            try {
                return LocalDate.parse(value, dateFormat)
            } catch (e: DateTimeParseException) {
                // Try the next supported format.
            }
        }
        return null
    }

    // Exactly one of moneyOut/moneyIn should be populated per row; the other
    // is blank. Returns null (an error) if both are blank, both are
    // populated, or a populated field isn't a valid number.
    private fun parseDebitCreditAmount(moneyOutRaw: String, moneyInRaw: String): Double? {
        val moneyOut = moneyOutRaw.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val moneyIn = moneyInRaw.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        if (moneyOutRaw.isNotEmpty() && moneyOut == null) return null
        if (moneyInRaw.isNotEmpty() && moneyIn == null) return null

        return when {
            moneyOut != null && moneyIn != null -> null
            moneyOut != null -> -moneyOut
            moneyIn != null -> moneyIn
            else -> null
        }
    }

    // accountType defaults to BANK so the many parser tests that aren't
    // about account type at all don't need to state one - real callers
    // (TransactionRoutes) always pass it explicitly, based on the upload
    // form's selection.
    fun parse(csvText: String, accountType: AccountType = AccountType.BANK): CsvParseResult {
        val transactions = mutableListOf<ParsedTransaction>()
        val errors = mutableListOf<CsvRowError>()

        CSVParser.parse(csvText, format).use { parser ->
            for (record in parser) {
                val rowNumber = record.recordNumber.toInt()

                if (record.size() < EXPECTED_FIELD_COUNT) {
                    errors += CsvRowError(
                        rowNumber,
                        record.rawLine(),
                        "Expected at least $EXPECTED_FIELD_COUNT fields (date, description, moneyOut, moneyIn, balance), got ${record.size()}"
                    )
                    continue
                }

                val date = parseDate(record.get(0))
                if (date == null) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Invalid date: ${record.get(0)}")
                    continue
                }

                val moneyOut = record.get(2)
                val moneyIn = record.get(3)
                val amount = parseDebitCreditAmount(moneyOut, moneyIn)
                if (amount == null) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Invalid amount: moneyOut=$moneyOut, moneyIn=$moneyIn")
                    continue
                }

                transactions += ParsedTransaction(rowNumber, accountType, date, record.get(1), amount)
            }
        }

        return CsvParseResult(transactions, errors)
    }

    // CSVRecord doesn't retain the original source line, only the split
    // fields - reconstruct a close-enough approximation for error messages.
    private fun CSVRecord.rawLine(): String = toList().joinToString(",")
}
