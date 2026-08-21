package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.LocalDate

// Slice 4 of the Financial Planning Projections feature (see
// .claude/current.md) - a named "what if" parameter set run through the
// same baseline projection engine (ProjectionEngine.kt's
// projectScenario), so a goal's /planning chart can show multiple lines
// side by side instead of just the always-shown baseline. No Gemini here
// - a scenario's numbers are typed in directly or picked from a preset;
// Gemini only *suggests* values in a later, optional slice, and even then
// never touches the arithmetic itself.

// Historical-benchmark presets, not fetched from any market data source -
// see context.md's Financial Planning Projections subsystem section for
// why (the number needed is an assumed *future* rate, not today's price).
// Only seeds the add form's default - the stored value is always a plain
// rate, so a hand-typed custom number works exactly like a preset once
// saved.
enum class MarketGrowthPreset(val label: String, val annualRate: Double) {
    CONSERVATIVE("Conservative (4%)", 0.04),
    MODERATE("Moderate (7%)", 0.07),
    AGGRESSIVE("Aggressive (10%)", 0.10)
}

data class Scenario(
    val id: String,
    val ownerId: String,
    val name: String,
    val annualMarketGrowthRate: Double,
    // What fraction of each month's savings this scenario assumes gets
    // invested (and therefore grows at annualMarketGrowthRate) vs. sits as
    // cash earning nothing - 1.0 = fully invested, 0.0 = none of it. See
    // ProjectionEngine.kt's projectScenario/ScenarioProjectionPoint for
    // why net worth is tracked as two separate balances rather than one.
    val investedSavingsFraction: Double,
    // Flat $/month redirected from discretionary/recreational spend into
    // savings (negative = spend more instead) - the "recreational spend
    // vs. savings" knob. A flat dollar adjustment rather than a percentage
    // of a specific spending category, since categories are per-owner/
    // user-defined (CategoryStore.kt) with no fixed "this one is
    // recreational" tag to key off of.
    val recreationalSpendAdjustment: Double,
    // At most one dated step change to the monthly savings rate (e.g. a
    // raise, or a negative delta for a deliberate pay cut - e.g. switching
    // to a lower-stress role), effective from its date onward - deliberately
    // singular for this slice rather than a list, to avoid a dynamic
    // add-row form in an app with no JS framework (see analysis.ftl/
    // CLAUDE.md); revisit if a household actually needs to model more than
    // one change. Both null, or both set - never just one.
    val salaryChangeDate: LocalDate? = null,
    val salaryChangeMonthlyDelta: Double? = null,
    // At most one RRSP contribution strategy - all three null, or all
    // three set (rrspReinvestRefund always has a value since it's a plain
    // boolean, but only matters when the other three are set). Modeled as
    // its own group rather than folded into recreationalSpendAdjustment
    // because it has effects that plain savings don't: it draws down a
    // finite pool (rrspRoomRemaining) and triggers a once-a-year tax
    // refund rather than growing continuously - see
    // ProjectionEngine.kt's projectScenario for the mechanics.
    //
    // rrspMarginalTaxRate is a single flat rate the household provides,
    // not a real federal+provincial bracket table - see context.md's
    // Financial Planning Projections subsystem section for why this app
    // doesn't hardcode tax law (it changes yearly/by province, and this
    // app has no web-connected Gemini call, only training-data recall -
    // wrong or stale numbers here have real financial consequences in a
    // way a growth-rate guess doesn't). A Gemini-suggested starting value
    // is a later, clearly-labeled-as-an-estimate addition, not a live
    // lookup.
    val rrspMonthlyContribution: Double? = null,
    val rrspMarginalTaxRate: Double? = null,
    val rrspRoomRemaining: Double? = null,
    val rrspReinvestRefund: Boolean = false,
    // Optional, independent of the contribution trio above: a household
    // Category (CategoryStore.kt) tagging their earned-income transactions
    // (e.g. a "Salary" category), used to accrue *new* RRSP room each year
    // (18% of that category's trailing-12-month total, same real-world
    // rule the CRA uses) rather than leaving rrspRoomRemaining as a
    // fixed, only-ever-decreasing pool. Independent of the contribution
    // trio because room can accrue whether or not this scenario currently
    // plans to contribute. rrspAnnualRoomAccrualCap mirrors
    // rrspMarginalTaxRate's reasoning - the CRA also caps how much room
    // can accrue per year, but that dollar cap is real tax data that
    // changes annually, so it's an optional number the household looks up
    // and enters rather than one this app hardcodes or guesses; left null,
    // accrual is uncapped.
    val rrspIncomeCategoryId: String? = null,
    val rrspAnnualRoomAccrualCap: Double? = null
)

interface ScenarioRepository {
    suspend fun all(ownerId: String): List<Scenario>
    suspend fun add(
        ownerId: String,
        name: String,
        annualMarketGrowthRate: Double,
        investedSavingsFraction: Double,
        recreationalSpendAdjustment: Double,
        salaryChangeDate: LocalDate?,
        salaryChangeMonthlyDelta: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspIncomeCategoryId: String?,
        rrspAnnualRoomAccrualCap: Double?
    ): Scenario

    suspend fun update(
        ownerId: String,
        id: String,
        name: String,
        annualMarketGrowthRate: Double,
        investedSavingsFraction: Double,
        recreationalSpendAdjustment: Double,
        salaryChangeDate: LocalDate?,
        salaryChangeMonthlyDelta: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspIncomeCategoryId: String?,
        rrspAnnualRoomAccrualCap: Double?
    ): Scenario?

    suspend fun delete(ownerId: String, id: String)
}

class FirestoreScenarioStore(private val firestore: Firestore) : ScenarioRepository {
    private val collection = firestore.collection("scenarios")

    override suspend fun add(
        ownerId: String,
        name: String,
        annualMarketGrowthRate: Double,
        investedSavingsFraction: Double,
        recreationalSpendAdjustment: Double,
        salaryChangeDate: LocalDate?,
        salaryChangeMonthlyDelta: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspIncomeCategoryId: String?,
        rrspAnnualRoomAccrualCap: Double?
    ): Scenario {
        val docRef = collection.document()
        docRef.set(
            scenarioMap(
                ownerId, name, annualMarketGrowthRate, investedSavingsFraction, recreationalSpendAdjustment,
                salaryChangeDate, salaryChangeMonthlyDelta, rrspMonthlyContribution, rrspMarginalTaxRate,
                rrspRoomRemaining, rrspReinvestRefund, rrspIncomeCategoryId, rrspAnnualRoomAccrualCap
            )
        ).get()
        return Scenario(
            docRef.id, ownerId, name, annualMarketGrowthRate, investedSavingsFraction, recreationalSpendAdjustment,
            salaryChangeDate, salaryChangeMonthlyDelta, rrspMonthlyContribution, rrspMarginalTaxRate,
            rrspRoomRemaining, rrspReinvestRefund, rrspIncomeCategoryId, rrspAnnualRoomAccrualCap
        )
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as the rest of this app's per-owner
    // collections. Sorted by name in memory instead.
    override suspend fun all(ownerId: String): List<Scenario> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toScenario(it.id, it.data) }.sortedBy { it.name.lowercase() }
    }

    override suspend fun update(
        ownerId: String,
        id: String,
        name: String,
        annualMarketGrowthRate: Double,
        investedSavingsFraction: Double,
        recreationalSpendAdjustment: Double,
        salaryChangeDate: LocalDate?,
        salaryChangeMonthlyDelta: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspIncomeCategoryId: String?,
        rrspAnnualRoomAccrualCap: Double?
    ): Scenario? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).set(
            scenarioMap(
                ownerId, name, annualMarketGrowthRate, investedSavingsFraction, recreationalSpendAdjustment,
                salaryChangeDate, salaryChangeMonthlyDelta, rrspMonthlyContribution, rrspMarginalTaxRate,
                rrspRoomRemaining, rrspReinvestRefund, rrspIncomeCategoryId, rrspAnnualRoomAccrualCap
            )
        ).get()
        return existing.copy(
            name = name,
            annualMarketGrowthRate = annualMarketGrowthRate,
            investedSavingsFraction = investedSavingsFraction,
            recreationalSpendAdjustment = recreationalSpendAdjustment,
            salaryChangeDate = salaryChangeDate,
            salaryChangeMonthlyDelta = salaryChangeMonthlyDelta,
            rrspMonthlyContribution = rrspMonthlyContribution,
            rrspMarginalTaxRate = rrspMarginalTaxRate,
            rrspRoomRemaining = rrspRoomRemaining,
            rrspReinvestRefund = rrspReinvestRefund,
            rrspIncomeCategoryId = rrspIncomeCategoryId,
            rrspAnnualRoomAccrualCap = rrspAnnualRoomAccrualCap
        )
    }

    override suspend fun delete(ownerId: String, id: String) {
        val existing = get(ownerId, id) ?: return
        collection.document(existing.id).delete().get()
    }

    private suspend fun get(ownerId: String, id: String): Scenario? {
        val snapshot = collection.document(id).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        if (data["ownerId"] as? String != ownerId) return null
        return toScenario(snapshot.id, data)
    }

    private fun scenarioMap(
        ownerId: String,
        name: String,
        annualMarketGrowthRate: Double,
        investedSavingsFraction: Double,
        recreationalSpendAdjustment: Double,
        salaryChangeDate: LocalDate?,
        salaryChangeMonthlyDelta: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspIncomeCategoryId: String?,
        rrspAnnualRoomAccrualCap: Double?
    ): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "name" to name,
        "annualMarketGrowthRate" to annualMarketGrowthRate,
        "investedSavingsFraction" to investedSavingsFraction,
        "recreationalSpendAdjustment" to recreationalSpendAdjustment,
        "salaryChangeDate" to salaryChangeDate?.toString(),
        "salaryChangeMonthlyDelta" to salaryChangeMonthlyDelta,
        "rrspMonthlyContribution" to rrspMonthlyContribution,
        "rrspMarginalTaxRate" to rrspMarginalTaxRate,
        "rrspRoomRemaining" to rrspRoomRemaining,
        "rrspReinvestRefund" to rrspReinvestRefund,
        "rrspIncomeCategoryId" to rrspIncomeCategoryId,
        "rrspAnnualRoomAccrualCap" to rrspAnnualRoomAccrualCap
    )

    private fun toScenario(id: String, data: Map<String, Any?>): Scenario = Scenario(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        name = data["name"] as? String ?: "",
        annualMarketGrowthRate = (data["annualMarketGrowthRate"] as? Number)?.toDouble() ?: MarketGrowthPreset.MODERATE.annualRate,
        investedSavingsFraction = (data["investedSavingsFraction"] as? Number)?.toDouble() ?: 1.0,
        recreationalSpendAdjustment = (data["recreationalSpendAdjustment"] as? Number)?.toDouble() ?: 0.0,
        salaryChangeDate = (data["salaryChangeDate"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        salaryChangeMonthlyDelta = (data["salaryChangeMonthlyDelta"] as? Number)?.toDouble(),
        rrspMonthlyContribution = (data["rrspMonthlyContribution"] as? Number)?.toDouble(),
        rrspMarginalTaxRate = (data["rrspMarginalTaxRate"] as? Number)?.toDouble(),
        rrspRoomRemaining = (data["rrspRoomRemaining"] as? Number)?.toDouble(),
        rrspReinvestRefund = data["rrspReinvestRefund"] as? Boolean ?: false,
        rrspIncomeCategoryId = data["rrspIncomeCategoryId"] as? String,
        rrspAnnualRoomAccrualCap = (data["rrspAnnualRoomAccrualCap"] as? Number)?.toDouble()
    )
}
