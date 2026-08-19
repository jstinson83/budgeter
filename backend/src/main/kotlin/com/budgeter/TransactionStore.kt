package com.budgeter

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.security.MessageDigest
import java.time.LocalDate

// Which physical account a statement was exported from. The 5-column CSV
// shape and sign convention (out=-, in=+) are identical either way - see
// CsvTransactionParser - so this doesn't change parsing. It exists for
// TransferMatcher (a bank "TFR-TO C/C" row only pairs with a credit-card
// "PAYMENT - THANK YOU" row, and a bank "TFR-TO/FR <account-num>" row pairs
// with a line-of-credit "TFR-FR/TO" row) and to keep a credit card's
// incoming payments from ever being mistaken for income in analysis.
enum class AccountType(val label: String) {
    BANK("Bank"),
    CREDIT_CARD("Credit Card"),
    LOC("Line of Credit")
}

data class Transaction(
    val id: String,
    // The Google sub of whoever imported this transaction - every read/write
    // below is scoped to it so one account never sees another's data.
    val ownerId: String,
    val accountType: AccountType,
    val date: LocalDate,
    val description: String,
    // Signed: negative = money out, positive = money in. Stored as a plain
    // Double for now, same simplification foodie's Ingredient.quantity
    // uses - revisit (e.g. integer cents) if floating-point drift ever
    // actually shows up in a balance/total.
    val amount: Double,
    // Null until a categorization pass (see GeminiTransactionCategorizer)
    // assigns one. Absent from newly-imported transactions on purpose - it's
    // filled in later, on demand, not at import time. A Category.id
    // (CategoryStore.kt) rather than an enum - categories are per-owner and
    // user-editable now, not a fixed compile-time set.
    val category: String? = null
)

data class TransactionImportResult(val stored: List<Transaction>, val duplicateCount: Int)

fun sha256Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

// Deterministic per-(owner,file,row-position) hash, used as both the
// Firestore document ID and the in-memory dedup key: re-importing the
// exact same CSV file lands each row on the same ID as before, so
// re-uploading a statement is naturally idempotent. Identity is tied to a
// row's position within a specific uploaded file, not to its
// date/description/amount - two genuinely distinct transactions that
// happen to share a date, description, and amount (e.g. two identical
// coffees bought the same day) sit at different row positions within the
// same file and are correctly treated as separate, not as duplicates of
// each other. The tradeoff: re-exporting the same statement with even a
// trivial formatting change (e.g. an added header row shifting every row's
// position, or a re-export with a wider date range) hashes differently and
// re-imports every row as new - there's no real per-transaction ID from
// the source data to do better than this without one.
fun transactionFingerprint(ownerId: String, fileHash: String, parsed: ParsedTransaction): String =
    sha256Hex("$ownerId|$fileHash|${parsed.rowNumber}")

// Cross-file overlap dedup - a deliberate heuristic, not a general solution
// (see CLAUDE.md's "Dedup on CSV import" gotcha). transactionFingerprint
// above only catches re-uploading the exact same file (same fileHash). It
// does nothing for a *different* export whose date range overlaps
// previously-imported statements for the same account - e.g. a fresh
// "Jan-Aug" export uploaded after "Jan-Jun" was already imported hashes
// every row as new and would re-import the whole overlapping range as
// duplicates. This closes that gap with claim-once content matching, keyed
// on (accountType, date, description, amount): each already-stored
// transaction can be "claimed" by at most one row of the new file. It
// doesn't assume anything about row order in the export - only that a
// transaction's date/description/amount are stable between exports of the
// same underlying data - and it still correctly keeps genuinely-repeated
// same-day/same-amount transactions (e.g. two identical coffees) as
// separate as long as the new file doesn't contain more of them than are
// already stored.
private data class TransactionContentKey(val accountType: AccountType, val date: LocalDate, val description: String, val amount: Double)

private fun ParsedTransaction.contentKey() = TransactionContentKey(accountType, date, description, amount)
private fun Transaction.contentKey() = TransactionContentKey(accountType, date, description, amount)

// Returns `parsed` with the content-overlapping subsection removed, in
// order, plus how many rows were dropped as overlap duplicates.
fun withoutContentOverlap(existing: List<Transaction>, parsed: List<ParsedTransaction>): Pair<List<ParsedTransaction>, Int> {
    val remainingClaims = existing.groupingBy { it.contentKey() }.eachCount().toMutableMap()
    var overlapCount = 0
    val newRows = parsed.filter { row ->
        val key = row.contentKey()
        val claimsLeft = remainingClaims[key] ?: 0
        if (claimsLeft > 0) {
            remainingClaims[key] = claimsLeft - 1
            overlapCount++
            false
        } else {
            true
        }
    }
    return newRows to overlapCount
}

interface TransactionRepository {
    // One batched write for a whole CSV import rather than N individual
    // round-trips - see FirestoreTransactionStore. fileHash identifies the
    // uploaded file as a whole (see transactionFingerprint) so dedup can be
    // scoped to "this row of this file" rather than a row's content alone.
    suspend fun addAll(ownerId: String, fileHash: String, transactions: List<ParsedTransaction>): TransactionImportResult
    suspend fun all(ownerId: String): List<Transaction>

    // Default in-memory filter over all(ownerId) rather than a separate
    // Firestore query - avoids needing another composite index (see
    // CLAUDE.md's Firestore gotchas) for what's a small per-user dataset.
    // This is also what keeps a categorization pass from ever re-analyzing
    // the same transaction twice: once category is set it drops out of
    // this list for good.
    suspend fun uncategorized(ownerId: String): List<Transaction> = all(ownerId).filter { it.category == null }

    suspend fun updateCategories(ownerId: String, categorized: Map<String, String>)

    // Wipes every transaction (and, since categories/analysis live on the
    // transaction record rather than a separate collection, everything
    // /analysis would show too) for one owner. Handy while the CSV import
    // format is still being nailed down against real statements - lets a
    // bad import be cleared and retried from scratch instead of piling up
    // wrongly-signed rows a re-upload's dedup would otherwise never correct.
    suspend fun deleteAll(ownerId: String)
}

class FirestoreTransactionStore(private val firestore: Firestore) : TransactionRepository {
    private val collection = firestore.collection("transactions")

    override suspend fun addAll(ownerId: String, fileHash: String, transactions: List<ParsedTransaction>): TransactionImportResult {
        if (transactions.isEmpty()) return TransactionImportResult(emptyList(), 0)

        // Fetches the owner's whole transaction history to check for
        // content overlap - same "small per-user dataset, fine to scan in
        // memory" tradeoff already made by uncategorized()/deleteAll() above.
        val (newRows, contentOverlapCount) = withoutContentOverlap(all(ownerId), transactions)
        if (newRows.isEmpty()) return TransactionImportResult(emptyList(), contentOverlapCount)

        val withFingerprints = newRows.map { it to transactionFingerprint(ownerId, fileHash, it) }
        val docRefs = withFingerprints.map { (_, fingerprint) -> collection.document(fingerprint) }
        // One batched existence check for the whole import rather than a
        // per-row read - same round-trip-minimizing idea as the write batch
        // below.
        val existingIds = firestore.getAll(*docRefs.toTypedArray()).get()
            .filter { it.exists() }
            .map { it.id }
            .toMutableSet()

        val batch = firestore.batch()
        val stored = mutableListOf<Transaction>()
        var duplicateCount = 0
        for ((parsed, fingerprint) in withFingerprints) {
            if (!existingIds.add(fingerprint)) {
                duplicateCount++
                continue
            }
            batch.set(collection.document(fingerprint), transactionToMap(ownerId, parsed))
            stored += Transaction(fingerprint, ownerId, parsed.accountType, parsed.date, parsed.description, parsed.amount)
        }
        if (stored.isNotEmpty()) batch.commit().get()
        return TransactionImportResult(stored, duplicateCount + contentOverlapCount)
    }

    override suspend fun all(ownerId: String): List<Transaction> {
        // Composite index on (ownerId, date) required - same Firestore
        // gotcha as foodie's RecipeRepository.all(), see CLAUDE.md.
        val snapshot = collection.whereEqualTo("ownerId", ownerId)
            .orderBy("date", Query.Direction.DESCENDING).get().get()
        return snapshot.documents.map { toTransaction(it.id, it.data) }
    }

    override suspend fun updateCategories(ownerId: String, categorized: Map<String, String>) {
        if (categorized.isEmpty()) return
        val batch = firestore.batch()
        categorized.forEach { (id, category) -> batch.update(collection.document(id), mapOf("category" to category)) }
        batch.commit().get()
    }

    override suspend fun deleteAll(ownerId: String) {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        if (snapshot.isEmpty) return
        val batch = firestore.batch()
        snapshot.documents.forEach { batch.delete(it.reference) }
        batch.commit().get()
    }

    private fun transactionToMap(ownerId: String, parsed: ParsedTransaction): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "accountType" to parsed.accountType.name,
        // Stored as an ISO-8601 string (yyyy-MM-dd) rather than a Firestore
        // Timestamp - it's a calendar date with no time/timezone component,
        // and ISO-8601's zero-padded format still sorts correctly as a
        // plain string, so orderBy("date", ...) above works unchanged.
        "date" to parsed.date.toString(),
        "description" to parsed.description,
        "amount" to parsed.amount,
        "createdAt" to FieldValue.serverTimestamp()
    )

    private fun toTransaction(id: String, data: Map<String, Any?>): Transaction = Transaction(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        // Falls back to BANK for documents written before AccountType
        // existed - every transaction imported before this field was added
        // came from the single TD bank export the app originally supported.
        accountType = (data["accountType"] as? String)?.let { runCatching { AccountType.valueOf(it) }.getOrNull() } ?: AccountType.BANK,
        date = LocalDate.parse(data["date"] as? String ?: "1970-01-01"),
        description = data["description"] as? String ?: "",
        amount = (data["amount"] as? Number)?.toDouble() ?: 0.0,
        category = data["category"] as? String
    )
}
