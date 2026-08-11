package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// A Gemini-generated synthesis of everything currently known about one
// component, across every document a household has uploaded - the "House
// Brain" idea from product_spec.md ("Structure: 12 known facts, 2
// unresolved questions"), scoped down to just a text summary for now.
// Generated on demand (a button on /house/facts), not automatically on
// every new fact - see HouseRoutes.kt. factCount is the snapshot of how
// many facts fed this summary, so a stale summary (new facts added since)
// can be surfaced later without a separate "dirty" flag.
data class ComponentSummary(
    val ownerId: String,
    val component: Component,
    val summary: String,
    val factCount: Int,
    val generatedAt: Instant
)

interface HouseComponentSummaryRepository {
    suspend fun get(ownerId: String, component: Component): ComponentSummary?
    suspend fun save(ownerId: String, component: Component, summary: String, factCount: Int): ComponentSummary
}

// One document per (ownerId, component), keyed deterministically - a
// summary is always an upsert (regenerating replaces the previous one, no
// history kept), so there's never more than one row to find and no query
// (or composite index) needed to fetch it.
class FirestoreHouseComponentSummaryStore(private val firestore: Firestore) : HouseComponentSummaryRepository {
    private val collection = firestore.collection("houseComponentSummaries")

    override suspend fun get(ownerId: String, component: Component): ComponentSummary? {
        val snapshot = collection.document(docId(ownerId, component)).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        return toComponentSummary(ownerId, component, data)
    }

    override suspend fun save(ownerId: String, component: Component, summary: String, factCount: Int): ComponentSummary {
        val generatedAt = Instant.now()
        collection.document(docId(ownerId, component)).set(
            mapOf(
                "ownerId" to ownerId,
                "component" to component.name,
                "summary" to summary,
                "factCount" to factCount,
                "generatedAt" to generatedAt.toString()
            )
        ).get()
        return ComponentSummary(ownerId, component, summary, factCount, generatedAt)
    }

    private fun docId(ownerId: String, component: Component) = "${ownerId}_${component.name}"

    private fun toComponentSummary(ownerId: String, component: Component, data: Map<String, Any?>): ComponentSummary = ComponentSummary(
        ownerId = ownerId,
        component = component,
        summary = data["summary"] as? String ?: "",
        factCount = (data["factCount"] as? Number)?.toInt() ?: 0,
        generatedAt = (data["generatedAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}
