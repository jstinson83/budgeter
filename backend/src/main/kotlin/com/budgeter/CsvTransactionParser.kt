package com.budgeter

import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class ParsedTransaction(val date: LocalDate, val description: String, val amount: Double)

data class CsvRowError(val rowNumber: Int, val rawLine: String, val reason: String)

data class CsvParseResult(val transactions: List<ParsedTransaction>, val errors: List<CsvRowError>)

// Headerless CSV: date,description,amount (a 4th balance column, if present,
// is ignored - see .claude/context.md). Date is assumed ISO-8601
// (yyyy-MM-dd) for now, since there's no real bank export to match against
// yet; TD's actual export uses MM/DD/YYYY with separate debit/credit
// columns instead of one signed amount, so this will need revisiting once
// real statements are used instead of synthetic data.
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

                val date = try {
                    LocalDate.parse(record.get(0))
                } catch (e: DateTimeParseException) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Invalid date: ${record.get(0)}")
                    continue
                }

                val amount = record.get(2).toDoubleOrNull()
                if (amount == null) {
                    errors += CsvRowError(rowNumber, record.rawLine(), "Invalid amount: ${record.get(2)}")
                    continue
                }

                transactions += ParsedTransaction(date, record.get(1), amount)
            }
        }

        return CsvParseResult(transactions, errors)
    }

    // CSVRecord doesn't retain the original source line, only the split
    // fields - reconstruct a close-enough approximation for error messages.
    private fun CSVRecord.rawLine(): String = toList().joinToString(",")
}
