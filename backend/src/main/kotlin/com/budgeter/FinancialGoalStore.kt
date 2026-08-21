package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.LocalDate

// Second slice of the Financial Planning Projections feature (see
// .claude/current.md) - a goal the net worth baseline (NetWorthStore.kt)
// and, once slice 3 lands, the projection engine get checked against. No
// projection/feasibility logic here yet, just CRUD plus the one derived
// value (resolvedTargetAmount) a goal needs regardless of its type.
enum class FinancialGoalType { NET_WORTH_TARGET, RETIREMENT }

// The classic "4% rule" - a commonly cited safe-withdrawal-rate default,
// not a computed/fetched value, same "manual, not market-data-derived"
// choice already made for scenario growth rates (see context.md's
// Financial Planning Projections subsystem section). Applied only when a
// RETIREMENT goal doesn't specify its own withdrawalRate.
const val DEFAULT_WITHDRAWAL_RATE = 0.04

data class FinancialGoal(
    val id: String,
    val ownerId: String,
    val name: String,
    val type: FinancialGoalType,
    val targetDate: LocalDate,
    // NET_WORTH_TARGET only - the target amount, stated directly.
    val targetAmount: Double? = null,
    // RETIREMENT only - the annual spend the withdrawal rule needs to
    // sustain, combined with withdrawalRate to derive the target amount
    // instead of the household having to guess a lump sum themselves.
    val annualSpend: Double? = null,
    val withdrawalRate: Double? = null
) {
    // The one number a projection gets compared against, regardless of
    // which type of goal this is - NET_WORTH_TARGET states it directly,
    // RETIREMENT derives it (annualSpend / withdrawalRate, e.g. $80k/yr at
    // 4% -> a ~$2M target).
    fun resolvedTargetAmount(): Double = when (type) {
        FinancialGoalType.NET_WORTH_TARGET -> targetAmount ?: 0.0
        FinancialGoalType.RETIREMENT -> (annualSpend ?: 0.0) / (withdrawalRate ?: DEFAULT_WITHDRAWAL_RATE)
    }
}

interface FinancialGoalRepository {
    suspend fun all(ownerId: String): List<FinancialGoal>
    suspend fun add(
        ownerId: String,
        name: String,
        type: FinancialGoalType,
        targetDate: LocalDate,
        targetAmount: Double?,
        annualSpend: Double?,
        withdrawalRate: Double?
    ): FinancialGoal

    suspend fun update(
        ownerId: String,
        id: String,
        name: String,
        type: FinancialGoalType,
        targetDate: LocalDate,
        targetAmount: Double?,
        annualSpend: Double?,
        withdrawalRate: Double?
    ): FinancialGoal?

    suspend fun delete(ownerId: String, id: String)
}

class FirestoreFinancialGoalStore(private val firestore: Firestore) : FinancialGoalRepository {
    private val collection = firestore.collection("financialGoals")

    override suspend fun add(
        ownerId: String,
        name: String,
        type: FinancialGoalType,
        targetDate: LocalDate,
        targetAmount: Double?,
        annualSpend: Double?,
        withdrawalRate: Double?
    ): FinancialGoal {
        val docRef = collection.document()
        docRef.set(goalMap(ownerId, name, type, targetDate, targetAmount, annualSpend, withdrawalRate)).get()
        return FinancialGoal(docRef.id, ownerId, name, type, targetDate, targetAmount, annualSpend, withdrawalRate)
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as the rest of this app's per-owner
    // collections. Sorted by target date (soonest first) in memory instead.
    override suspend fun all(ownerId: String): List<FinancialGoal> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toGoal(it.id, it.data) }.sortedBy { it.targetDate }
    }

    override suspend fun update(
        ownerId: String,
        id: String,
        name: String,
        type: FinancialGoalType,
        targetDate: LocalDate,
        targetAmount: Double?,
        annualSpend: Double?,
        withdrawalRate: Double?
    ): FinancialGoal? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).set(goalMap(ownerId, name, type, targetDate, targetAmount, annualSpend, withdrawalRate)).get()
        return existing.copy(
            name = name,
            type = type,
            targetDate = targetDate,
            targetAmount = targetAmount,
            annualSpend = annualSpend,
            withdrawalRate = withdrawalRate
        )
    }

    override suspend fun delete(ownerId: String, id: String) {
        val existing = get(ownerId, id) ?: return
        collection.document(existing.id).delete().get()
    }

    private suspend fun get(ownerId: String, id: String): FinancialGoal? {
        val snapshot = collection.document(id).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        if (data["ownerId"] as? String != ownerId) return null
        return toGoal(snapshot.id, data)
    }

    private fun goalMap(
        ownerId: String,
        name: String,
        type: FinancialGoalType,
        targetDate: LocalDate,
        targetAmount: Double?,
        annualSpend: Double?,
        withdrawalRate: Double?
    ): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "name" to name,
        "type" to type.name,
        "targetDate" to targetDate.toString(),
        "targetAmount" to targetAmount,
        "annualSpend" to annualSpend,
        "withdrawalRate" to withdrawalRate
    )

    private fun toGoal(id: String, data: Map<String, Any?>): FinancialGoal = FinancialGoal(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        name = data["name"] as? String ?: "",
        type = (data["type"] as? String)?.let { runCatching { FinancialGoalType.valueOf(it) }.getOrNull() } ?: FinancialGoalType.NET_WORTH_TARGET,
        targetDate = (data["targetDate"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(),
        targetAmount = (data["targetAmount"] as? Number)?.toDouble(),
        annualSpend = (data["annualSpend"] as? Number)?.toDouble(),
        withdrawalRate = (data["withdrawalRate"] as? Number)?.toDouble()
    )
}
