package com.budgeter

import com.google.cloud.firestore.Firestore

// One persisted setting per household, shared across every Scenario -
// replaces what used to be Scenario.rrspIncomeCategoryId, a fact
// ("which Category are your paycheck deposits tagged with") that was
// really the same answer everywhere but got picked per scenario, inside
// the RRSP strategy facet specifically. Introduced alongside /planning's
// "Your Numbers" card (NetWorthPage.kt), which reads it to show the
// household's real recent income (ProjectionEngine.kt's
// recentCategoryMonthlyAverage) right next to where Salary change's
// Amount ($/mo) field asks for a delta from it. RRSP room accrual
// (Scenario.rrspAccrueRoomFromIncome, ProjectionEngine.kt's
// projectScenario) reads the same setting instead of its own picker - one
// place to set this instead of once per scenario.
//
// Deliberately just this one field for now, not a general key-value
// settings bag - add fields here only when a second genuinely
// household-wide (not per-scenario, per-entry, or per-goal) setting shows
// up; a speculative bag would just be unused surface area until then.
data class HouseholdSettings(
    val ownerId: String,
    val incomeCategoryId: String? = null
)

interface HouseholdSettingsRepository {
    // Never null - a household that hasn't saved anything yet gets the
    // all-default HouseholdSettings(ownerId, incomeCategoryId = null)
    // rather than a separate "not configured" state every caller would
    // otherwise have to null-check for.
    suspend fun get(ownerId: String): HouseholdSettings
    suspend fun save(ownerId: String, incomeCategoryId: String?): HouseholdSettings
}

// One document per ownerId, keyed deterministically - same shape as
// FirestoreRecommendationGenerationMarkerStore: always an upsert, no
// history kept, no query needed to find it.
class FirestoreHouseholdSettingsStore(private val firestore: Firestore) : HouseholdSettingsRepository {
    private val collection = firestore.collection("householdSettings")

    override suspend fun get(ownerId: String): HouseholdSettings {
        val snapshot = collection.document(ownerId).get().get()
        if (!snapshot.exists()) return HouseholdSettings(ownerId)
        val data = snapshot.data ?: return HouseholdSettings(ownerId)
        return HouseholdSettings(ownerId, data["incomeCategoryId"] as? String)
    }

    override suspend fun save(ownerId: String, incomeCategoryId: String?): HouseholdSettings {
        collection.document(ownerId).set(
            mapOf("ownerId" to ownerId, "incomeCategoryId" to incomeCategoryId)
        ).get()
        return HouseholdSettings(ownerId, incomeCategoryId)
    }
}
