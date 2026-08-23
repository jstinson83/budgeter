package com.budgeter

// Shared with AnalysisPage.kt: color alone shouldn't be the only signal for
// money in vs. money out, so positive amounts get an explicit "+" alongside
// the sign Double's own formatting already puts on negatives.
fun formatSignedAmount(amount: Double): String = (if (amount >= 0) "+" else "") + "%.2f".format(amount)

fun amountClass(amount: Double): String = if (amount < 0) "transaction-amount-negative" else "transaction-amount-positive"

// FreeMarker's default object wrapper can't stringify java.time.LocalDate
// (or Kotlin data classes generally) on its own, so - same as foodie's
// *PageModel builders - this flattens a Transaction into a plain
// Map<String, Any?> of already-display-ready values before it reaches a
// template. Shared between transactionsPageModel and
// AnalysisPage.kt's analysisCategoryPageModel - both render the same
// date/description/account/amount row shape.
fun transactionRowModel(transaction: Transaction): Map<String, Any?> = mapOf(
    "id" to transaction.id,
    "date" to transaction.date.toString(),
    "description" to transaction.description,
    "accountType" to transaction.accountType.label,
    "amount" to formatSignedAmount(transaction.amount),
    "amountClass" to amountClass(transaction.amount)
)

fun transactionsPageModel(transactions: List<Transaction>, message: String?, error: String?): Map<String, Any?> = mapOf(
    "transactions" to transactions.map(::transactionRowModel),
    "message" to message,
    "error" to error
)

// Groups for manual review on /transactions/duplicates - every group here
// shares (accountType, date, description, amount). Grouping on content
// alone isn't enough, though: two rows in the *same* uploaded file that
// happen to match are always genuinely separate transactions (e.g. two
// real same-day identical charges - transactionFingerprint keys on row
// position, so they're never merged, and withoutContentOverlap only ever
// drops a new row that matches something *already stored*, never two rows
// within the same new file against each other - see
// testIdenticalRowsInTheSameFileAreNotTreatedAsDuplicatesOfEachOther in
// TransactionRoutesTest). So a content-key group is only flagged here when
// its members don't all come from one single file - i.e. fileHash isn't
// the same non-null value across the whole group. That's the actual
// signature of a cross-import duplicate (or, for rows written before
// fileHash was tracked, an unknown provenance we can't rule out - treated
// as flaggable rather than silently skipped, since that's exactly the
// legacy-duplicate population this page exists to help clean up).
// Flagging a group is never destructive on its own - it just means a human
// reviews the members and deletes whichever aren't real, so a genuine
// same-day repeat that happens to also appear in another file (all of File
// A's copies plus a stray duplicate from File B) still shows every member
// rather than being auto-collapsed to one.
fun duplicateGroupsPageModel(transactions: List<Transaction>): Map<String, Any?> {
    val groups = transactions.groupBy { it.contentKey() }.values
        .filter { group -> group.size > 1 && !allFromOneKnownFile(group) }
        .sortedByDescending { it.first().date }
        .map { group -> mapOf("transactions" to group.map(::transactionRowModel)) }
    return mapOf("duplicateGroups" to groups)
}

private fun allFromOneKnownFile(group: List<Transaction>): Boolean {
    val fileHash = group.first().fileHash ?: return false
    return group.all { it.fileHash == fileHash }
}
