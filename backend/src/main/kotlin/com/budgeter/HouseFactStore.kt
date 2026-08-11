package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// The fact type taxonomy from product_spec.md's House Knowledge section -
// an observation is not the same thing as an interpretation, and Unknown is
// a valid, first-class state rather than an absent field.
enum class FactType {
    OBSERVATION,
    CONDITION,
    DIAGNOSIS,
    DECISION,
    EVENT,
    SPECIFICATION,
    MAINTENANCE_REQUIREMENT,
    WARRANTY,
    UNKNOWN
}

// A single knowledge item extracted from (or about) the house. This is a
// deliberately narrow slice of the full Fact model in product_spec.md -
// what/type/source/evidence(sourceQuote)/interpretation(homeownerContext) -
// leaving status/time/location/confidence/related components-events-facts-
// tasks/photos for later once there's real usage to design them against.
data class HouseFact(
    val id: String,
    val ownerId: String,
    val documentId: String,
    val what: String,
    val type: FactType,
    // Short verbatim excerpt from the source document backing this fact,
    // when Gemini could point to one - the "why do we believe this"
    // provenance the spec calls for, even in this narrow slice.
    val sourceQuote: String?,
    // True for facts the extractor flagged as ambiguous/interpretive
    // (spec's "Identify ambiguity" MVP step) - most facts are NOT flagged
    // and are treated as accepted automatically.
    val needsReview: Boolean,
    val reviewQuestion: String?,
    // Set once the homeowner answers reviewQuestion - never overwrites
    // sourceQuote (the original source statement stays intact per the
    // spec's "never overwrite historical source material" principle); this
    // is a second, separate layer of context alongside it.
    val homeownerContext: String? = null,
    val createdAt: Instant
)

interface HouseFactRepository {
    // One batched write per extracted document, mirroring
    // TransactionRepository.addAll's per-import batching.
    suspend fun addAll(ownerId: String, documentId: String, facts: List<ExtractedFact>): List<HouseFact>
    suspend fun all(ownerId: String): List<HouseFact>

    // Default in-memory filter over all(ownerId), same reasoning as
    // TransactionRepository.uncategorized() - avoids a second Firestore
    // query shape (and the composite-index question that comes with it)
    // for what's a small per-household dataset.
    suspend fun forDocument(ownerId: String, documentId: String): List<HouseFact> =
        all(ownerId).filter { it.documentId == documentId }

    suspend fun resolve(ownerId: String, id: String, homeownerContext: String): HouseFact?
}

class FirestoreHouseFactStore(private val firestore: Firestore) : HouseFactRepository {
    private val collection = firestore.collection("houseFacts")

    override suspend fun addAll(ownerId: String, documentId: String, facts: List<ExtractedFact>): List<HouseFact> {
        if (facts.isEmpty()) return emptyList()
        val batch = firestore.batch()
        val stored = facts.map { extracted ->
            val docRef = collection.document()
            val createdAt = Instant.now()
            batch.set(docRef, factToMap(ownerId, documentId, extracted, createdAt))
            HouseFact(
                id = docRef.id,
                ownerId = ownerId,
                documentId = documentId,
                what = extracted.what,
                type = extracted.type,
                sourceQuote = extracted.sourceQuote,
                needsReview = extracted.needsReview,
                reviewQuestion = extracted.reviewQuestion,
                createdAt = createdAt
            )
        }
        batch.commit().get()
        return stored
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as FirestoreCategoryStore/forDocument above.
    override suspend fun all(ownerId: String): List<HouseFact> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toHouseFact(it.id, it.data) }.sortedByDescending { it.createdAt }
    }

    override suspend fun resolve(ownerId: String, id: String, homeownerContext: String): HouseFact? {
        val existing = all(ownerId).find { it.id == id } ?: return null
        collection.document(id).update(mapOf("homeownerContext" to homeownerContext, "needsReview" to false)).get()
        return existing.copy(homeownerContext = homeownerContext, needsReview = false)
    }

    private fun factToMap(ownerId: String, documentId: String, extracted: ExtractedFact, createdAt: Instant): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "documentId" to documentId,
        "what" to extracted.what,
        "type" to extracted.type.name,
        "sourceQuote" to extracted.sourceQuote,
        "needsReview" to extracted.needsReview,
        "reviewQuestion" to extracted.reviewQuestion,
        "homeownerContext" to null,
        "createdAt" to createdAt.toString()
    )

    private fun toHouseFact(id: String, data: Map<String, Any?>): HouseFact = HouseFact(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        documentId = data["documentId"] as? String ?: "",
        what = data["what"] as? String ?: "",
        type = (data["type"] as? String)?.let { runCatching { FactType.valueOf(it) }.getOrNull() } ?: FactType.UNKNOWN,
        sourceQuote = data["sourceQuote"] as? String,
        needsReview = data["needsReview"] as? Boolean ?: false,
        reviewQuestion = data["reviewQuestion"] as? String,
        homeownerContext = data["homeownerContext"] as? String,
        createdAt = (data["createdAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}
