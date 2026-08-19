package com.budgeter

import org.apache.commons.csv.CSVFormat
import java.io.StringWriter

// CSV export for the dashboard's "Export" link (DashboardRoutes.kt) - one
// row per transaction, already filtered by the caller to whatever period
// is selected. Not a general full-history dump; that's a different feature
// if it's ever wanted.
//
// Category is resolved to a display label the same way the dashboard's pie
// chart does it (categoryTotalsByLabel in DashboardPage.kt): TRANSFER isn't
// a real Category row (see CategoryStore.kt's TRANSFER_CATEGORY_ID doc), so
// it's special-cased to read "Transfer" - the whole point of surfacing it
// at all here is so a transfer between the household's own accounts is
// identifiable in the exported data, not silently blank or a raw id.
fun transactionsToCsv(transactions: List<Transaction>, categories: List<Category>): String {
    val labelById = categories.associateBy({ it.id }, { it.label })
    val writer = StringWriter()
    CSVFormat.DEFAULT.builder()
        .setHeader("Date", "Account", "Description", "Amount", "Category")
        .build()
        .print(writer)
        .use { printer ->
            transactions.sortedBy { it.date }.forEach { transaction ->
                val category = when (transaction.category) {
                    null -> "Uncategorized"
                    TRANSFER_CATEGORY_ID -> "Transfer"
                    else -> labelById[transaction.category] ?: transaction.category
                }
                printer.printRecord(transaction.date, transaction.accountType.label, transaction.description, transaction.amount, category)
            }
        }
    return writer.toString()
}
