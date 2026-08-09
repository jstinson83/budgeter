package com.budgeter

import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.cloud.firestore.Query
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

interface TransactionRepository {
    // One batched write for a whole CSV import rather than N individual
    // round-trips - see FirestoreTransactionStore.
    suspend fun addAll(ownerId: String, transactions: List<ParsedTransaction>): List<Transaction>
    suspend fun all(ownerId: String): List<Transaction>
}

class FirestoreTransactionStore(private val firestore: Firestore) : TransactionRepository {
    private val collection = firestore.collection("transactions")

    override suspend fun addAll(ownerId: String, transactions: List<ParsedTransaction>): List<Transaction> {
        val batch = firestore.batch()
        val stored = transactions.map { parsed ->
            val docRef = collection.document()
            batch.set(docRef, transactionToMap(ownerId, parsed))
            Transaction(docRef.id, ownerId, parsed.date, parsed.description, parsed.amount)
        }
        batch.commit().get()
        return stored
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
