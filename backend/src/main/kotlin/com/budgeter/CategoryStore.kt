package com.budgeter

import com.google.cloud.firestore.Firestore

// Replaces the old fixed TransactionCategory enum with real per-owner rows,
// so a household can add its own categories and disable ones it doesn't
// use, from the /categories page (CategoryRoutes.kt). Ids intentionally
// match the old enum constant names exactly (GROCERIES, DINING_OUT, ...) so
// existing Transaction.category / CategorizationRule.category strings
// already sitting in Firestore keep resolving without a data migration -
// see CLAUDE.md's Firestore gotchas.
// internal rather than private: FakeCategoryRepository (TestFixtures.kt)
// reuses this and the slug helpers below so the in-memory test double seeds
// and mints ids exactly like FirestoreCategoryStore does.
internal val BUILT_IN_CATEGORIES = listOf(
    "GROCERIES" to "Groceries",
    "ALCOHOL" to "Alcohol",
    "DINING_OUT" to "Dining Out",
    "ENTERTAINMENT" to "Entertainment",
    "MORTGAGE" to "Mortgage",
    "HOUSE_EXPENSES" to "House Expenses",
    "UTILITIES" to "Utilities",
    "TRANSPORTATION" to "Transportation",
    "HEALTH" to "Health",
    "SUBSCRIPTIONS" to "Subscriptions",
    "CLOTHING" to "Clothing",
    "EDUCATION" to "Education",
    "INCOME" to "Income",
    "INVESTMENT" to "Investment",
    "OTHER" to "Other"
)

// Assigned only by TransferMatcher's deterministic bank/credit-card pairing
// (see TransferMatcher.kt) - never a real Category row, never seeded, never
// shown or selectable anywhere a user picks a category from. Kept as a
// plain id constant rather than promoted into the categories table, the
// same "stays invisible" behavior TRANSFER had as an enum constant before
// this feature.
const val TRANSFER_CATEGORY_ID = "TRANSFER"

data class Category(
    val id: String,
    val ownerId: String,
    val label: String,
    val active: Boolean = true
)

interface CategoryRepository {
    // Seeds the built-in set on first call for an owner with no rows yet,
    // then returns everything - active and inactive alike, callers filter
    // for active where a category needs to be pickable.
    suspend fun all(ownerId: String): List<Category>
    suspend fun add(ownerId: String, label: String): Category
    suspend fun setActive(ownerId: String, id: String, active: Boolean)
}

// Uppercase-snake-case, matching the shape of the old enum constant names -
// keeps built-in and custom category ids in one consistent format, and
// keeps /analysis/category/{slug} working unchanged (slug is just id
// lowercased, same as it was for the enum's .name before).
internal fun slugify(label: String): String {
    val base = label.trim().uppercase().replace(Regex("[^A-Z0-9]+"), "_").trim('_')
    return base.ifEmpty { "CATEGORY" }
}

internal fun uniqueSlug(label: String, existingIds: Set<String>): String {
    val base = slugify(label)
    if (base !in existingIds) return base
    var suffix = 2
    while ("${base}_$suffix" in existingIds) suffix++
    return "${base}_$suffix"
}

class FirestoreCategoryStore(private val firestore: Firestore) : CategoryRepository {
    private val collection = firestore.collection("categories")

    override suspend fun all(ownerId: String): List<Category> = ensureSeededDocs(ownerId).map { it.second }

    override suspend fun add(ownerId: String, label: String): Category {
        // Routed through ensureSeededDocs (not a raw fetchDocs) so calling
        // add() as an owner's very first interaction - before /categories
        // or /analysis has ever called all(ownerId) - still seeds the
        // built-ins rather than leaving them permanently un-seeded (all()
        // only seeds when the collection comes back empty, and this custom
        // category's own document would make it non-empty from then on).
        val existingIds = ensureSeededDocs(ownerId).map { it.second.id }.toSet()
        val id = uniqueSlug(label, existingIds)
        val docRef = collection.document()
        docRef.set(categoryMap(ownerId, id, label, active = true)).get()
        return Category(id, ownerId, label, active = true)
    }

    override suspend fun setActive(ownerId: String, id: String, active: Boolean) {
        val (docId, _) = ensureSeededDocs(ownerId).find { it.second.id == id } ?: return
        collection.document(docId).update("active", active).get()
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index-needed shape as FirestoreCategorizationRuleStore.
    private fun fetchDocs(ownerId: String): List<Pair<String, Category>> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { it.id to toCategory(it.id, it.data) }
    }

    private fun ensureSeededDocs(ownerId: String): List<Pair<String, Category>> {
        val existing = fetchDocs(ownerId)
        if (existing.isNotEmpty()) return existing
        return seedBuiltIns(ownerId)
    }

    private fun seedBuiltIns(ownerId: String): List<Pair<String, Category>> {
        val batch = firestore.batch()
        val seeded = BUILT_IN_CATEGORIES.map { (id, label) ->
            val docRef = collection.document()
            batch.set(docRef, categoryMap(ownerId, id, label, active = true))
            docRef.id to Category(id, ownerId, label, active = true)
        }
        batch.commit().get()
        return seeded
    }

    private fun categoryMap(ownerId: String, id: String, label: String, active: Boolean): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "id" to id,
        "label" to label,
        "active" to active
    )

    private fun toCategory(docId: String, data: Map<String, Any?>): Category = Category(
        id = data["id"] as? String ?: docId,
        ownerId = data["ownerId"] as? String ?: "",
        label = data["label"] as? String ?: "",
        active = data["active"] as? Boolean ?: true
    )
}
