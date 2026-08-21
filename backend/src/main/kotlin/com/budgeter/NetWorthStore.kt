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
    val value: Double
)

// Sum(asset values) - sum(liability values) - the one place this
// calculation lives, reused by the projection engine (slice 3) as the
// baseline it projects forward from and by the planning page for the
// current-net-worth summary.
fun netWorthTotal(entries: List<NetWorthEntry>): Double =
    entries.sumOf { if (it.type.isAsset) it.value else -it.value }

interface NetWorthEntryRepository {
    suspend fun all(ownerId: String): List<NetWorthEntry>
    suspend fun add(ownerId: String, label: String, type: NetWorthEntryType, value: Double): NetWorthEntry
    suspend fun update(ownerId: String, id: String, label: String, type: NetWorthEntryType, value: Double): NetWorthEntry?
    suspend fun delete(ownerId: String, id: String)
}

class FirestoreNetWorthEntryStore(private val firestore: Firestore) : NetWorthEntryRepository {
    private val collection = firestore.collection("netWorthEntries")

    override suspend fun add(ownerId: String, label: String, type: NetWorthEntryType, value: Double): NetWorthEntry {
        val docRef = collection.document()
        docRef.set(entryMap(ownerId, label, type, value)).get()
        return NetWorthEntry(docRef.id, ownerId, label, type, value)
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

    override suspend fun update(ownerId: String, id: String, label: String, type: NetWorthEntryType, value: Double): NetWorthEntry? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).set(entryMap(ownerId, label, type, value)).get()
        return existing.copy(label = label, type = type, value = value)
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

    private fun entryMap(ownerId: String, label: String, type: NetWorthEntryType, value: Double): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "label" to label,
        "type" to type.name,
        "value" to value
    )

    private fun toEntry(id: String, data: Map<String, Any?>): NetWorthEntry = NetWorthEntry(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        label = data["label"] as? String ?: "",
        type = (data["type"] as? String)?.let { runCatching { NetWorthEntryType.valueOf(it) }.getOrNull() } ?: NetWorthEntryType.OTHER_ASSET,
        value = (data["value"] as? Number)?.toDouble() ?: 0.0
    )
}
