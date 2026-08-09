package com.budgeter

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
object CsvTransactionParser {
    fun parse(csvText: String): CsvParseResult {
        val transactions = mutableListOf<ParsedTransaction>()
        val errors = mutableListOf<CsvRowError>()

        csvText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEachIndexed { index, line ->
                val rowNumber = index + 1
                val fields = parseCsvLine(line)
                if (fields.size < 3) {
                    errors += CsvRowError(rowNumber, line, "Expected at least 3 fields (date, description, amount), got ${fields.size}")
                    return@forEachIndexed
                }

                val date = try {
                    LocalDate.parse(fields[0].trim())
                } catch (e: DateTimeParseException) {
                    errors += CsvRowError(rowNumber, line, "Invalid date: ${fields[0]}")
                    return@forEachIndexed
                }

                val amount = fields[2].trim().toDoubleOrNull()
                if (amount == null) {
                    errors += CsvRowError(rowNumber, line, "Invalid amount: ${fields[2]}")
                    return@forEachIndexed
                }

                transactions += ParsedTransaction(date, fields[1].trim(), amount)
            }

        return CsvParseResult(transactions, errors)
    }

    // Minimal RFC4180-style line parser: handles double-quoted fields (real
    // merchant descriptions routinely contain commas, e.g. "STARBUCKS, INC")
    // and "" as an escaped quote inside one. Not a full CSV parser (no
    // multi-line quoted fields) - fine since each transaction is one line.
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    current.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }
}
