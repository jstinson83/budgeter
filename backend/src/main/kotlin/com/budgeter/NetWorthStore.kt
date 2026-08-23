package com.budgeter

import com.google.cloud.firestore.Firestore

// First slice of the Financial Planning Projections feature (see
// .claude/current.md) - a manual, editable-anytime net worth baseline for
// the projection engine (slice 3) to project forward from. Deliberately
// manual rather than derived from Transaction balances: the earlier
// "net position" dashboard feature did that (summed CSV-imported account
// balances) and was pulled for silently understating net worth whenever an
// account wasn't imported - investments, real estate, other debts never
// flow through a TD CSV export at all. See CLAUDE.md's "Dashboard net
// position gotcha" for the full writeup this is deliberately avoiding.
//
// value is always entered as a positive amount regardless of asset vs.
// liability - net worth is computed as sum(asset values) - sum(liability
// values) rather than relying on a signed convention, which sidesteps the
// other half of that same pulled feature's bug (it treated a
// CSV-already-negative credit-card balance as a positive "amount owed" and
// subtracted it, flipping the sign and inflating net worth). There's no
// CSV import here to inherit a sign convention from, so there's no reason
// to introduce one.
enum class NetWorthEntryType(val label: String, val isAsset: Boolean) {
    BANK("Bank", true),
    INVESTMENT("Investment", true),
    REAL_ESTATE("Real Estate", true),
    OTHER_ASSET("Other Asset", true),
    CREDIT_CARD("Credit Card", false),
    LOC("Line of Credit", false),
    MORTGAGE("Mortgage", false),
    OTHER_LIABILITY("Other Liability", false)
}

data class NetWorthEntry(
    val id: String,
    val ownerId: String,
    val label: String,
    val type: NetWorthEntryType,
    val value: Double,
    // Mortgage amortization inputs, meaningful on MORTGAGE entries only -
    // both set together or both left null, same "coherent group" pattern
    // Scenario's salary-change pair uses (a rate with no payment category,
    // or vice versa, isn't a runnable amortization schedule). Left null, the
    // entry stays frozen at `value` for the whole projection - the behavior
    // every entry had before this existed. See ProjectionEngine.kt's
    // DynamicEntrySchedules for how these actually get used, and
    // CLAUDE.md/current.md's "Fix 1" writeup for why this was added (a
    // reviewed projection export showed home equity pinned at one constant
    // value for the entire horizon - the projection loop updated the
    // invested balance every month but never touched a MORTGAGE or
    // REAL_ESTATE entry).
    //
    // annualInterestRate is manually entered - a mortgage statement states
    // it outright, there's no transaction history to derive it from.
    // monthlyPayment is deliberately *not* a manually-typed field, though:
    // the payment is already sitting in tracked transaction history under a
    // category tag (mortgagePaymentCategoryId points at a household
    // Category, same as Scenario.rrspIncomeCategoryId - "we already do this
    // for the RRSP income category, find the amount the same way" rather
    // than asking the household to type a number in by hand). See
    // ProjectionEngine.kt's resolvedMonthlyMortgagePayment for how the
    // actual payment figure gets derived from it.
    val annualInterestRate: Double? = null,
    val mortgagePaymentCategoryId: String? = null,
    // Real estate appreciation, meaningful on REAL_ESTATE entries only -
    // independent of the mortgage pair above (a paid-off property can
    // appreciate with no MORTGAGE entry at all, and a mortgage amortizes
    // the same way regardless of whether its property's appreciation rate
    // is set). Its own field rather than reusing a Scenario's
    // annualMarketGrowthRate - real estate and equities shouldn't be forced
    // to move together in the model.
    val annualAppreciationRate: Double? = null
)

// Sum(asset values) - sum(liability values) - the one place this
// calculation lives, reused by the projection engine (slice 3) as the
// baseline it projects forward from and by the planning page for the
// current-net-worth summary. Always uses each entry's own static `value` -
// the current, as-of-today snapshot - never the amortized/appreciated
// schedule ProjectionEngine.kt projects forward; that's a distinct concern
// from "what's true right now."
fun netWorthTotal(entries: List<NetWorthEntry>): Double =
    entries.sumOf { if (it.type.isAsset) it.value else -it.value }

// Whether this entry has a full mortgage amortization schedule to run
// (see ProjectionEngine.kt's DynamicEntrySchedules) rather than sitting
// frozen at `value` - both annualInterestRate and mortgagePaymentCategoryId
// set, on an entry actually typed as a mortgage. Doesn't guarantee a
// resolvable payment amount (the tagged category might have no trailing
// transaction history yet) - ProjectionEngine.kt's
// resolvedMonthlyMortgagePayment is what actually decides that.
val NetWorthEntry.isAmortizingMortgage: Boolean
    get() = type == NetWorthEntryType.MORTGAGE && annualInterestRate != null && mortgagePaymentCategoryId != null

// Whether this entry appreciates over the projection instead of sitting
// frozen at `value` - annualAppreciationRate set, on an entry actually
// typed as real estate.
val NetWorthEntry.isAppreciatingRealEstate: Boolean
    get() = type == NetWorthEntryType.REAL_ESTATE && annualAppreciationRate != null

interface NetWorthEntryRepository {
    suspend fun all(ownerId: String): List<NetWorthEntry>
    suspend fun add(
        ownerId: String,
        label: String,
        type: NetWorthEntryType,
        value: Double,
        annualInterestRate: Double? = null,
        mortgagePaymentCategoryId: String? = null,
        annualAppreciationRate: Double? = null
    ): NetWorthEntry
    suspend fun update(
        ownerId: String,
        id: String,
        label: String,
        type: NetWorthEntryType,
        value: Double,
        annualInterestRate: Double? = null,
        mortgagePaymentCategoryId: String? = null,
        annualAppreciationRate: Double? = null
    ): NetWorthEntry?
    suspend fun delete(ownerId: String, id: String)
}

class FirestoreNetWorthEntryStore(private val firestore: Firestore) : NetWorthEntryRepository {
    private val collection = firestore.collection("netWorthEntries")

    override suspend fun add(
        ownerId: String,
        label: String,
        type: NetWorthEntryType,
        value: Double,
        annualInterestRate: Double?,
        mortgagePaymentCategoryId: String?,
        annualAppreciationRate: Double?
    ): NetWorthEntry {
        val docRef = collection.document()
        docRef.set(entryMap(ownerId, label, type, value, annualInterestRate, mortgagePaymentCategoryId, annualAppreciationRate)).get()
        return NetWorthEntry(docRef.id, ownerId, label, type, value, annualInterestRate, mortgagePaymentCategoryId, annualAppreciationRate)
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as the rest of this app's per-owner
    // collections. Sorted by type (assets before liabilities, matching how
    // /planning groups them) then label in memory instead.
    override suspend fun all(ownerId: String): List<NetWorthEntry> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toEntry(it.id, it.data) }
            .sortedWith(compareByDescending<NetWorthEntry> { it.type.isAsset }.thenBy { it.label.lowercase() })
    }

    override suspend fun update(
        ownerId: String,
        id: String,
        label: String,
        type: NetWorthEntryType,
        value: Double,
        annualInterestRate: Double?,
        mortgagePaymentCategoryId: String?,
        annualAppreciationRate: Double?
    ): NetWorthEntry? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).set(entryMap(ownerId, label, type, value, annualInterestRate, mortgagePaymentCategoryId, annualAppreciationRate)).get()
        return existing.copy(
            label = label, type = type, value = value,
            annualInterestRate = annualInterestRate, mortgagePaymentCategoryId = mortgagePaymentCategoryId, annualAppreciationRate = annualAppreciationRate
        )
    }

    override suspend fun delete(ownerId: String, id: String) {
        val existing = get(ownerId, id) ?: return
        collection.document(existing.id).delete().get()
    }

    private suspend fun get(ownerId: String, id: String): NetWorthEntry? {
        val snapshot = collection.document(id).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        if (data["ownerId"] as? String != ownerId) return null
        return toEntry(snapshot.id, data)
    }

    private fun entryMap(
        ownerId: String,
        label: String,
        type: NetWorthEntryType,
        value: Double,
        annualInterestRate: Double?,
        mortgagePaymentCategoryId: String?,
        annualAppreciationRate: Double?
    ): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "label" to label,
        "type" to type.name,
        "value" to value,
        "annualInterestRate" to annualInterestRate,
        "mortgagePaymentCategoryId" to mortgagePaymentCategoryId,
        "annualAppreciationRate" to annualAppreciationRate
    )

    private fun toEntry(id: String, data: Map<String, Any?>): NetWorthEntry = NetWorthEntry(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        label = data["label"] as? String ?: "",
        type = (data["type"] as? String)?.let { runCatching { NetWorthEntryType.valueOf(it) }.getOrNull() } ?: NetWorthEntryType.OTHER_ASSET,
        value = (data["value"] as? Number)?.toDouble() ?: 0.0,
        annualInterestRate = (data["annualInterestRate"] as? Number)?.toDouble(),
        mortgagePaymentCategoryId = data["mortgagePaymentCategoryId"] as? String,
        annualAppreciationRate = (data["annualAppreciationRate"] as? Number)?.toDouble()
    )
}
