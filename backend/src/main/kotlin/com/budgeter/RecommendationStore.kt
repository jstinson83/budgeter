package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// A Gemini-generated project suggestion (chunk 4 of House Projects &
// Recommendations, see .claude/current.md) - one per Component per
// generation run, grounded in that component's current HouseFacts.
// Rejected is a single merged state covering what the original feature
// request called out as two separate actions ("Deprioritize"/"Dismiss") -
// see current.md for why: no real distinction was worth keeping between
// them at the recommendation level (Project itself still has its own real
// DEPRIORITIZED status for a project someone actively decided to shelve).
enum class RecommendationStatus { PENDING, ACCEPTED, REJECTED }

data class Recommendation(
    val id: String,
    val ownerId: String,
    val component: Component,
    val name: String,
    val rationale: String,
    // HouseFact ids this recommendation is grounded in - always at least
    // one; GeminiRecommendationGenerator drops anything it can't tie back
    // to a real fact rather than persisting an ungrounded suggestion.
    val supportingFactIds: List<String>,
    // Gemini's suggestion only - once accepted into a Project, priority
    // becomes freely user-editable there and this field is never read back
    // (see ProjectStore.kt's Priority comment for why the two are kept as
    // separate concepts).
    val suggestedPriority: Priority,
    val status: RecommendationStatus,
    val createdAt: Instant
)

interface RecommendationRepository {
    suspend fun all(ownerId: String): List<Recommendation>
    // One batched write per component's generation pass, mirroring
    // HouseFactRepository.addAll's per-document batching.
    suspend fun addAll(ownerId: String, component: Component, recommendations: List<GeneratedRecommendation>): List<Recommendation>
}

class FirestoreRecommendationStore(private val firestore: Firestore) : RecommendationRepository {
    private val collection = firestore.collection("recommendations")

    override suspend fun addAll(ownerId: String, component: Component, recommendations: List<GeneratedRecommendation>): List<Recommendation> {
        if (recommendations.isEmpty()) return emptyList()
        val batch = firestore.batch()
        val stored = recommendations.map { generated ->
            val docRef = collection.document()
            val createdAt = Instant.now()
            batch.set(docRef, recommendationMap(ownerId, component, generated, createdAt))
            Recommendation(
                id = docRef.id,
                ownerId = ownerId,
                component = component,
                name = generated.name,
                rationale = generated.rationale,
                supportingFactIds = generated.supportingFactIds,
                suggestedPriority = generated.suggestedPriority,
                status = RecommendationStatus.PENDING,
                createdAt = createdAt
            )
        }
        batch.commit().get()
        return stored
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as the rest of this app's per-owner
    // collections.
    override suspend fun all(ownerId: String): List<Recommendation> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toRecommendation(it.id, it.data) }.sortedByDescending { it.createdAt }
    }

    private fun recommendationMap(ownerId: String, component: Component, generated: GeneratedRecommendation, createdAt: Instant): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "component" to component.name,
        "name" to generated.name,
        "rationale" to generated.rationale,
        "supportingFactIds" to generated.supportingFactIds,
        "suggestedPriority" to generated.suggestedPriority.name,
        "status" to RecommendationStatus.PENDING.name,
        "createdAt" to createdAt.toString()
    )

    private fun toRecommendation(id: String, data: Map<String, Any?>): Recommendation = Recommendation(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        component = (data["component"] as? String)?.let { runCatching { Component.valueOf(it) }.getOrNull() } ?: Component.OTHER,
        name = data["name"] as? String ?: "",
        rationale = data["rationale"] as? String ?: "",
        supportingFactIds = (data["supportingFactIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        suggestedPriority = (data["suggestedPriority"] as? String)?.let { runCatching { Priority.valueOf(it) }.getOrNull() } ?: Priority.MEDIUM,
        status = (data["status"] as? String)?.let { runCatching { RecommendationStatus.valueOf(it) }.getOrNull() } ?: RecommendationStatus.PENDING,
        createdAt = (data["createdAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}
