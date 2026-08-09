package com.budgeter

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
import java.security.MessageDigest
import java.time.LocalDate

data class Transaction(
    val id: String,
    // The Google sub of whoever imported this transaction - every read/write
    // below is scoped to it so one account never sees another's data.
    val ownerId: String,
    val date: LocalDate,
    val description: String,
    // Signed: negative = money out, positive = money in. Stored as a plain
    // Double for now, same simplification foodie's Ingredient.quantity
    // uses - revisit (e.g. integer cents) if floating-point drift ever
    // actually shows up in a balance/total.
    val amount: Double
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

interface TransactionRepository {
    // One batched write for a whole CSV import rather than N individual
    // round-trips - see FirestoreTransactionStore. fileHash identifies the
    // uploaded file as a whole (see transactionFingerprint) so dedup can be
    // scoped to "this row of this file" rather than a row's content alone.
    suspend fun addAll(ownerId: String, fileHash: String, transactions: List<ParsedTransaction>): TransactionImportResult
    suspend fun all(ownerId: String): List<Transaction>
}

class FirestoreTransactionStore(private val firestore: Firestore) : TransactionRepository {
    private val collection = firestore.collection("transactions")

    override suspend fun addAll(ownerId: String, fileHash: String, transactions: List<ParsedTransaction>): TransactionImportResult {
        if (transactions.isEmpty()) return TransactionImportResult(emptyList(), 0)

        val withFingerprints = transactions.map { it to transactionFingerprint(ownerId, fileHash, it) }
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
            stored += Transaction(fingerprint, ownerId, parsed.date, parsed.description, parsed.amount)
        }
        if (stored.isNotEmpty()) batch.commit().get()
        return TransactionImportResult(stored, duplicateCount)
    }

    override suspend fun all(ownerId: String): List<Transaction> {
        // Composite index on (ownerId, date) required - same Firestore
        // gotcha as foodie's RecipeRepository.all(), see CLAUDE.md.
        val snapshot = collection.whereEqualTo("ownerId", ownerId)
            .orderBy("date", Query.Direction.DESCENDING).get().get()
        return snapshot.documents.map { toTransaction(it.id, it.data) }
    }

    private fun transactionToMap(ownerId: String, parsed: ParsedTransaction): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
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
        date = LocalDate.parse(data["date"] as? String ?: "1970-01-01"),
        description = data["description"] as? String ?: "",
        amount = (data["amount"] as? Number)?.toDouble() ?: 0.0
    )
}
