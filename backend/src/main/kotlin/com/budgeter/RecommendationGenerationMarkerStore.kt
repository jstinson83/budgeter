package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// Tracks the last time recommendations were generated for one component,
// keyed by the fact count seen at that time - same "stale if the fact
// count has since changed" idea as ComponentSummary.factCount
// (HouseComponentSummaryStore.kt), reused here so RecommendationJobManager
// can skip a component with nothing new to reason about instead of
// re-billing a Gemini call for facts it's already generated
// recommendations from.
data class RecommendationGenerationMarker(
    val ownerId: String,
    val component: Component,
    val factCount: Int,
    val generatedAt: Instant
)

interface RecommendationGenerationMarkerRepository {
    suspend fun get(ownerId: String, component: Component): RecommendationGenerationMarker?
    suspend fun save(ownerId: String, component: Component, factCount: Int): RecommendationGenerationMarker
}

// One document per (ownerId, component), keyed deterministically - same
// shape as FirestoreHouseComponentSummaryStore: always an upsert, no
// history kept, no query needed to find it.
class FirestoreRecommendationGenerationMarkerStore(private val firestore: Firestore) : RecommendationGenerationMarkerRepository {
    private val collection = firestore.collection("recommendationGenerationMarkers")

    override suspend fun get(ownerId: String, component: Component): RecommendationGenerationMarker? {
        val snapshot = collection.document(docId(ownerId, component)).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        return toMarker(ownerId, component, data)
    }

    override suspend fun save(ownerId: String, component: Component, factCount: Int): RecommendationGenerationMarker {
        val generatedAt = Instant.now()
        collection.document(docId(ownerId, component)).set(
            mapOf(
                "ownerId" to ownerId,
                "component" to component.name,
                "factCount" to factCount,
                "generatedAt" to generatedAt.toString()
            )
        ).get()
        return RecommendationGenerationMarker(ownerId, component, factCount, generatedAt)
    }

    private fun docId(ownerId: String, component: Component) = "${ownerId}_${component.name}"

    private fun toMarker(ownerId: String, component: Component, data: Map<String, Any?>): RecommendationGenerationMarker = RecommendationGenerationMarker(
        ownerId = ownerId,
        component = component,
        factCount = (data["factCount"] as? Number)?.toInt() ?: 0,
        generatedAt = (data["generatedAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}
