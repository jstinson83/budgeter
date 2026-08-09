package com.budgeter

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class ParsedTransaction(val rowNumber: Int, val date: LocalDate, val description: String, val amount: Double)

data class CsvRowError(val rowNumber: Int, val rawLine: String, val reason: String)

data class CsvParseResult(val transactions: List<ParsedTransaction>, val errors: List<CsvRowError>)

// Headerless CSV: date,description,amount (a 4th balance column, if present,
// is ignored - see .claude/context.md). Date accepts either ISO-8601
// (yyyy-MM-dd, for synthetic/AI-generated test data) or MM/dd/yyyy (TD
// Canada Trust's real export format) - see SUPPORTED_DATE_FORMATS. TD's
// export also uses separate debit/credit columns rather than one signed
// amount; that part is still unhandled since no real statement has been
// tried against this yet.
//
// Field-level splitting/quoting/escaping is delegated to Apache Commons CSV
// rather than a hand-rolled line parser - RFC4180 edge cases (quoted
// newlines, escaped quotes, CRLF vs LF) are exactly the kind of thing not
// worth re-deriving.
object CsvTransactionParser {
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

    fun parse(csvText: String): CsvParseResult {
        val transactions = mutableListOf<ParsedTransaction>()
        val errors = mutableListOf<CsvRowError>()

        CSVParser.parse(csvText, format).use { parser ->
            for (record in parser) {
                val rowNumber = record.recordNumber.toInt()

                if (record.size() < 3) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Expected at least 3 fields (date, description, amount), got ${record.size()}")
                    continue
                }

                val date = parseDate(record.get(0))
                if (date == null) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Invalid date: ${record.get(0)}")
                    continue
                }

                val amount = record.get(2).toDoubleOrNull()
                if (amount == null) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Invalid amount: ${record.get(2)}")
                    continue
                }

                transactions += ParsedTransaction(rowNumber, date, record.get(1), amount)
            }
        }

        return CsvParseResult(transactions, errors)
    }

    // CSVRecord doesn't retain the original source line, only the split
    // fields - reconstruct a close-enough approximation for error messages.
    private fun CSVRecord.rawLine(): String = toList().joinToString(",")
}
