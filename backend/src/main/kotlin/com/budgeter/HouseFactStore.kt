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
    // A value or condition assumed for design/calculation/planning purposes
    // rather than measured or verified - kept distinct from OBSERVATION so
    // an engineering assumption can never be mistaken for a confirmed
    // property of the house.
    ASSUMPTION,
    UNKNOWN,
    // Something the document explicitly says was outside the inspection,
    // investigation, or professional mandate - distinct from UNKNOWN, which
    // is for things the document tried to determine and couldn't.
    SCOPE_LIMITATION
}

// Flat top-level slice of product_spec.md's component hierarchy (Foundation,
// Structure, Exterior, Roof, Plumbing, Electrical, HVAC, Safety, and their
// sub-parts under Property -> Building) - deliberately no sub-component
// nesting yet, same "narrowest useful slice" posture as the rest of
// HouseFact. This is what lets facts from different documents be browsed
// together ("everything about the roof") without building the full
// components/events/relationships graph the spec describes.
enum class Component {
    FOUNDATION,
    STRUCTURE,
    EXTERIOR,
    ROOF,
    PLUMBING,
    ELECTRICAL,
    HVAC,
    SAFETY,
    OTHER
}

// A fact's lifecycle status at the level the second extraction pass settles
// on (see HouseFactNormalizer.kt) - a narrower cousin of CandidateStatus
// (HouseFactCandidateExtractor.kt's first-pass vocabulary), which also
// allows ASSUMED for a still-raw candidate. By the time a candidate has been
// normalized into a fact, "this was assumed" is already captured by
// FactType.ASSUMPTION, so FactStatus doesn't need its own ASSUMED value.
enum class FactStatus {
    EXISTING,
    NEW,
    MODIFIED,
    REMOVED,
    PROPOSED,
    UNKNOWN,
    NOT_APPLICABLE
}

// How durably important a fact is to remember, assigned by the normalizer
// pass so a future UI can prioritize what's shown without a separate
// ranking pass. Shared with CandidateStatus's sibling ExtractedCandidate -
// pass 1 assigns a first guess, pass 2 (which sees the full, deduplicated
// picture) can revise it.
enum class Importance { HIGH, MEDIUM, LOW }

// How a fact's statement is backed by its source - distinct from FactType,
// which classifies what the fact is *about*, not the nature of the evidence
// behind it. INFERRED covers a fact the normalizer synthesized by reasoning
// across multiple candidates rather than reading directly off one of them.
enum class EvidenceType { DOCUMENTED, MEASURED, OBSERVED, DESIGNED, ASSUMED, REPORTED, INFERRED }

// A single knowledge item extracted from (or about) the house. This is a
// deliberately narrow slice of the full Fact model in product_spec.md -
// what/type/source/evidence(sourceQuote)/interpretation(homeownerContext) -
// leaving time/related components-events-facts-tasks/photos for later once
// there's real usage to design them against. status/importance/
// sourceLocation/evidenceType were added when extraction moved to the
// two-pass candidate-then-normalize pipeline (see HouseFactExtractor.kt).
data class HouseFact(
    val id: String,
    val ownerId: String,
    val documentId: String,
    val what: String,
    val type: FactType,
    // What part of the house this fact is about - see Component. Assigned
    // by the extractor alongside type, not a separate pass.
    val component: Component,
    // EXISTING/NEW/MODIFIED/REMOVED/PROPOSED/UNKNOWN/NOT_APPLICABLE - see
    // FactStatus. Unrelated to HouseDocumentStatus (a document's own
    // upload/extraction lifecycle) despite the similar name.
    val status: FactStatus,
    val importance: Importance,
    // Short verbatim excerpt from the source document backing this fact,
    // when Gemini could point to one - the "why do we believe this"
    // provenance the spec calls for, even in this narrow slice.
    val sourceQuote: String?,
    // Page, drawing number, section, or other locator within the source
    // document, when Gemini could determine one - kept separate from
    // sourceQuote since a location isn't always a quotable string.
    val sourceLocation: String?,
    // See EvidenceType. Null (rather than a defaulted member) for a value
    // the normalizer's response didn't parse cleanly - none of
    // EvidenceType's members mean "not stated" the way FactStatus.UNKNOWN
    // does for status, so there's no good non-null fallback to pick.
    val evidenceType: EvidenceType?,
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

    // Used by document deletion/retry (HouseRoutes.kt) - deletion removes a
    // document's facts along with it, and retry clears out any facts a
    // previous attempt already wrote before re-running extraction, so a
    // second attempt on the same document can never leave duplicates
    // behind.
    suspend fun deleteForDocument(ownerId: String, documentId: String)
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
                component = extracted.component,
                status = extracted.status,
                importance = extracted.importance,
                sourceQuote = extracted.sourceQuote,
                sourceLocation = extracted.sourceLocation,
                evidenceType = extracted.evidenceType,
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

    override suspend fun deleteForDocument(ownerId: String, documentId: String) {
        val existing = forDocument(ownerId, documentId)
        if (existing.isEmpty()) return
        val batch = firestore.batch()
        existing.forEach { batch.delete(collection.document(it.id)) }
        batch.commit().get()
    }

    private fun factToMap(ownerId: String, documentId: String, extracted: ExtractedFact, createdAt: Instant): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "documentId" to documentId,
        "what" to extracted.what,
        "type" to extracted.type.name,
        "component" to extracted.component.name,
        "status" to extracted.status.name,
        "importance" to extracted.importance.name,
        "sourceQuote" to extracted.sourceQuote,
        "sourceLocation" to extracted.sourceLocation,
        "evidenceType" to extracted.evidenceType?.name,
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
        component = (data["component"] as? String)?.let { runCatching { Component.valueOf(it) }.getOrNull() } ?: Component.OTHER,
        status = (data["status"] as? String)?.let { runCatching { FactStatus.valueOf(it) }.getOrNull() } ?: FactStatus.UNKNOWN,
        importance = (data["importance"] as? String)?.let { runCatching { Importance.valueOf(it) }.getOrNull() } ?: Importance.MEDIUM,
        sourceQuote = data["sourceQuote"] as? String,
        sourceLocation = data["sourceLocation"] as? String,
        evidenceType = (data["evidenceType"] as? String)?.let { runCatching { EvidenceType.valueOf(it) }.getOrNull() },
        needsReview = data["needsReview"] as? Boolean ?: false,
        reviewQuestion = data["reviewQuestion"] as? String,
        homeownerContext = data["homeownerContext"] as? String,
        createdAt = (data["createdAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}
