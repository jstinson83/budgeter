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
    // Optional - null means the change is permanent (today's original
    // behavior). Set means it reverts starting this date: the event is
    // active for months in [salaryChangeDate, salaryChangeEndDate), so the
    // end-date month itself is already back to normal - the natural
    // reading of "reverts on this date." Meaningless without
    // salaryChangeDate also set, same "inert on the wrong field" posture
    // as the RRSP fields below.
    val salaryChangeEndDate: LocalDate? = null,
    // Discussed 2026-08-23: a salary change often isn't *just* a cash-flow
    // change - a pay cut can mean contributing less to an RRSP, and both a
    // pay cut/raise can shift which marginal-rate bracket applies. This
    // app deliberately never auto-derives those (no tax-bracket table, no
    // "your income changed so we recomputed your rate" - see
    // rrspMarginalTaxRate's own doc comment below for why); instead these
    // three replace the scenario's own rrspMonthlyContribution/
    // rrspMarginalTaxRate/room-accrual-per-year figures while the salary
    // change event above is active, each defaulting to null ("no
    // change" - keep using the scenario's steady-state numbers throughout).
    // Each only takes effect if the scenario already has the mechanism it
    // modifies configured (the RRSP contribution trio / rrspAccrueRoomFromIncome
    // respectively) - the event adjusts an existing strategy's numbers for
    // its duration, it doesn't let a salary-change event conjure a
    // contribution or accrual plan that isn't otherwise there. See
    // ProjectionEngine.kt's projectScenario for exactly where each is read.
    val salaryChangeRrspContributionOverride: Double? = null,
    val salaryChangeMarginalTaxRateOverride: Double? = null,
    // A flat $ figure for that year's RRSP room accrual, replacing the
    // normally-computed 18%-of-trailing-income figure while the event is
    // active - not an override of rrspAnnualRoomAccrualCap (a real,
    // income-independent CRA dollar limit that has no reason to move just
    // because one household's income did), but of the actual accrued
    // amount, since that's the number a salary change actually affects and
    // the app has no way to project a hypothetical future income through
    // the normal 18%-of-real-trailing-transactions derivation.
    val salaryChangeRoomAccrualOverride: Double? = null,
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
    // Optional, independent of the contribution trio above: whether this
    // scenario accrues *new* RRSP room each year (18% of the household's
    // trailing-12-month income, same real-world rule the CRA uses) rather
    // than leaving rrspRoomRemaining as a fixed, only-ever-decreasing pool.
    // Independent of the contribution trio because room can accrue whether
    // or not this scenario currently plans to contribute.
    //
    // Which Category counts as income used to be picked here too
    // (rrspIncomeCategoryId, a per-scenario field) - split out to
    // HouseholdSettings.incomeCategoryId (HouseholdSettingsStore.kt) once
    // /planning grew a "Your Numbers" card that needed the same category
    // for its Income stat, regardless of which scenario (if any) has RRSP
    // strategy configured. A household only ever has one real answer to
    // "which category is my income," so this is now a plain on/off
    // strategy choice - the facet's own presence in the form is the signal
    // (see planning.ftl's rrsp-accrual facet, same disabled-fieldset
    // pattern CLAUDE.md's CSS gotchas section documents), same shape
    // rrspReinvestRefund already uses for a scenario-level boolean.
    // rrspAnnualRoomAccrualCap mirrors rrspMarginalTaxRate's reasoning -
    // the CRA also caps how much room can accrue per year, but that dollar
    // cap is real tax data that changes annually, so it's an optional
    // number the household looks up and enters rather than one this app
    // hardcodes or guesses; left null, accrual is uncapped.
    val rrspAccrueRoomFromIncome: Boolean = false,
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
        salaryChangeEndDate: LocalDate?,
        salaryChangeRrspContributionOverride: Double?,
        salaryChangeMarginalTaxRateOverride: Double?,
        salaryChangeRoomAccrualOverride: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspAccrueRoomFromIncome: Boolean,
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
        salaryChangeEndDate: LocalDate?,
        salaryChangeRrspContributionOverride: Double?,
        salaryChangeMarginalTaxRateOverride: Double?,
        salaryChangeRoomAccrualOverride: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspAccrueRoomFromIncome: Boolean,
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
        salaryChangeEndDate: LocalDate?,
        salaryChangeRrspContributionOverride: Double?,
        salaryChangeMarginalTaxRateOverride: Double?,
        salaryChangeRoomAccrualOverride: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspAccrueRoomFromIncome: Boolean,
        rrspAnnualRoomAccrualCap: Double?
    ): Scenario {
        val docRef = collection.document()
        docRef.set(
            scenarioMap(
                ownerId, name, annualMarketGrowthRate, investedSavingsFraction, recreationalSpendAdjustment,
                salaryChangeDate, salaryChangeMonthlyDelta, salaryChangeEndDate, salaryChangeRrspContributionOverride,
                salaryChangeMarginalTaxRateOverride, salaryChangeRoomAccrualOverride, rrspMonthlyContribution, rrspMarginalTaxRate,
                rrspRoomRemaining, rrspReinvestRefund, rrspAccrueRoomFromIncome, rrspAnnualRoomAccrualCap
            )
        ).get()
        return Scenario(
            docRef.id, ownerId, name, annualMarketGrowthRate, investedSavingsFraction, recreationalSpendAdjustment,
            salaryChangeDate, salaryChangeMonthlyDelta, salaryChangeEndDate, salaryChangeRrspContributionOverride,
            salaryChangeMarginalTaxRateOverride, salaryChangeRoomAccrualOverride, rrspMonthlyContribution, rrspMarginalTaxRate,
            rrspRoomRemaining, rrspReinvestRefund, rrspAccrueRoomFromIncome, rrspAnnualRoomAccrualCap
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
        salaryChangeEndDate: LocalDate?,
        salaryChangeRrspContributionOverride: Double?,
        salaryChangeMarginalTaxRateOverride: Double?,
        salaryChangeRoomAccrualOverride: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspAccrueRoomFromIncome: Boolean,
        rrspAnnualRoomAccrualCap: Double?
    ): Scenario? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).set(
            scenarioMap(
                ownerId, name, annualMarketGrowthRate, investedSavingsFraction, recreationalSpendAdjustment,
                salaryChangeDate, salaryChangeMonthlyDelta, salaryChangeEndDate, salaryChangeRrspContributionOverride,
                salaryChangeMarginalTaxRateOverride, salaryChangeRoomAccrualOverride, rrspMonthlyContribution, rrspMarginalTaxRate,
                rrspRoomRemaining, rrspReinvestRefund, rrspAccrueRoomFromIncome, rrspAnnualRoomAccrualCap
            )
        ).get()
        return existing.copy(
            name = name,
            annualMarketGrowthRate = annualMarketGrowthRate,
            investedSavingsFraction = investedSavingsFraction,
            recreationalSpendAdjustment = recreationalSpendAdjustment,
            salaryChangeDate = salaryChangeDate,
            salaryChangeMonthlyDelta = salaryChangeMonthlyDelta,
            salaryChangeEndDate = salaryChangeEndDate,
            salaryChangeRrspContributionOverride = salaryChangeRrspContributionOverride,
            salaryChangeMarginalTaxRateOverride = salaryChangeMarginalTaxRateOverride,
            salaryChangeRoomAccrualOverride = salaryChangeRoomAccrualOverride,
            rrspMonthlyContribution = rrspMonthlyContribution,
            rrspMarginalTaxRate = rrspMarginalTaxRate,
            rrspRoomRemaining = rrspRoomRemaining,
            rrspReinvestRefund = rrspReinvestRefund,
            rrspAccrueRoomFromIncome = rrspAccrueRoomFromIncome,
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
        salaryChangeEndDate: LocalDate?,
        salaryChangeRrspContributionOverride: Double?,
        salaryChangeMarginalTaxRateOverride: Double?,
        salaryChangeRoomAccrualOverride: Double?,
        rrspMonthlyContribution: Double?,
        rrspMarginalTaxRate: Double?,
        rrspRoomRemaining: Double?,
        rrspReinvestRefund: Boolean,
        rrspAccrueRoomFromIncome: Boolean,
        rrspAnnualRoomAccrualCap: Double?
    ): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "name" to name,
        "annualMarketGrowthRate" to annualMarketGrowthRate,
        "investedSavingsFraction" to investedSavingsFraction,
        "recreationalSpendAdjustment" to recreationalSpendAdjustment,
        "salaryChangeDate" to salaryChangeDate?.toString(),
        "salaryChangeMonthlyDelta" to salaryChangeMonthlyDelta,
        "salaryChangeEndDate" to salaryChangeEndDate?.toString(),
        "salaryChangeRrspContributionOverride" to salaryChangeRrspContributionOverride,
        "salaryChangeMarginalTaxRateOverride" to salaryChangeMarginalTaxRateOverride,
        "salaryChangeRoomAccrualOverride" to salaryChangeRoomAccrualOverride,
        "rrspMonthlyContribution" to rrspMonthlyContribution,
        "rrspMarginalTaxRate" to rrspMarginalTaxRate,
        "rrspRoomRemaining" to rrspRoomRemaining,
        "rrspReinvestRefund" to rrspReinvestRefund,
        "rrspAccrueRoomFromIncome" to rrspAccrueRoomFromIncome,
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
        salaryChangeEndDate = (data["salaryChangeEndDate"] as? String)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        salaryChangeRrspContributionOverride = (data["salaryChangeRrspContributionOverride"] as? Number)?.toDouble(),
        salaryChangeMarginalTaxRateOverride = (data["salaryChangeMarginalTaxRateOverride"] as? Number)?.toDouble(),
        salaryChangeRoomAccrualOverride = (data["salaryChangeRoomAccrualOverride"] as? Number)?.toDouble(),
        rrspMonthlyContribution = (data["rrspMonthlyContribution"] as? Number)?.toDouble(),
        rrspMarginalTaxRate = (data["rrspMarginalTaxRate"] as? Number)?.toDouble(),
        rrspRoomRemaining = (data["rrspRoomRemaining"] as? Number)?.toDouble(),
        rrspReinvestRefund = data["rrspReinvestRefund"] as? Boolean ?: false,
        rrspAccrueRoomFromIncome = data["rrspAccrueRoomFromIncome"] as? Boolean ?: false,
        rrspAnnualRoomAccrualCap = (data["rrspAnnualRoomAccrualCap"] as? Number)?.toDouble()
    )
}
