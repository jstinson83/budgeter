package com.budgeter

import com.google.cloud.firestore.Firestore

// How a saved rule's pattern is compared against a transaction's
// description - EXACT for a merchant that always posts the identical
// string, SUBSTRING for one with a varying suffix (order numbers,
// reference codes, dates) where only a fragment is stable.
enum class MatchType { EXACT, SUBSTRING }

// A household's own manually-created categorization rule - originally only
// saveable from the inline "Recategorize" action on an
// /analysis/category/{slug} drill-down page, now also fully manageable
// (create/edit/delete) from the /categories page (see CategoryRoutes.kt).
// Applied by CategorizationRuleMatcher to still-uncategorized transactions
// on the next "Categorize" run, alongside TransferMatcher and ahead of
// Gemini - the household knows its own recurring "Other" merchants better
// than Gemini could guess, and a saved rule is free/instant compared to
// another Gemini call.
data class CategorizationRule(
    val id: String,
    val ownerId: String,
    val pattern: String,
    val matchType: MatchType,
    // A Category.id (CategoryStore.kt), not the old TransactionCategory enum.
    val category: String
)

interface CategorizationRuleRepository {
    suspend fun all(ownerId: String): List<CategorizationRule>
    suspend fun add(ownerId: String, pattern: String, matchType: MatchType, category: String): CategorizationRule

    // Returns null (rather than throwing) when the rule doesn't exist or
    // doesn't belong to this owner - same "just don't apply it" shape as a
    // failed lookup elsewhere in this codebase (e.g. the recategorize
    // handler's transaction-ownership check).
    suspend fun update(ownerId: String, id: String, pattern: String, matchType: MatchType, category: String): CategorizationRule?

    suspend fun delete(ownerId: String, id: String)
}

class FirestoreCategorizationRuleStore(private val firestore: Firestore) : CategorizationRuleRepository {
    private val collection = firestore.collection("categorizationRules")

    // Single-field equality filter, no orderBy - unlike TransactionRepository.all(),
    // this doesn't need a composite index (see CLAUDE.md's Firestore gotchas).
    override suspend fun all(ownerId: String): List<CategorizationRule> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toRule(it.id, it.data) }
    }

    override suspend fun add(ownerId: String, pattern: String, matchType: MatchType, category: String): CategorizationRule {
        val docRef = collection.document()
        docRef.set(ruleMap(ownerId, pattern, matchType, category)).get()
        return CategorizationRule(docRef.id, ownerId, pattern, matchType, category)
    }

    override suspend fun update(ownerId: String, id: String, pattern: String, matchType: MatchType, category: String): CategorizationRule? {
        val docRef = collection.document(id)
        val snapshot = docRef.get().get()
        if (!snapshot.exists() || snapshot.getString("ownerId") != ownerId) return null
        docRef.set(ruleMap(ownerId, pattern, matchType, category)).get()
        return CategorizationRule(id, ownerId, pattern, matchType, category)
    }

    override suspend fun delete(ownerId: String, id: String) {
        val docRef = collection.document(id)
        val snapshot = docRef.get().get()
        if (!snapshot.exists() || snapshot.getString("ownerId") != ownerId) return
        docRef.delete().get()
    }

    private fun ruleMap(ownerId: String, pattern: String, matchType: MatchType, category: String): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "pattern" to pattern,
        "matchType" to matchType.name,
        "category" to category
    )

    private fun toRule(id: String, data: Map<String, Any?>): CategorizationRule = CategorizationRule(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        pattern = data["pattern"] as? String ?: "",
        matchType = (data["matchType"] as? String)?.let { runCatching { MatchType.valueOf(it) }.getOrNull() } ?: MatchType.EXACT,
        category = data["category"] as? String ?: "OTHER"
    )
}
