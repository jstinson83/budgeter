package com.budgeter

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

// The /planning page - manual net worth tracking (NetWorthStore.kt, slice
// 1), FinancialGoal CRUD (FinancialGoalStore.kt, slice 2), each goal's
// baseline projection (ProjectionEngine.kt/ProjectionChart.kt, slice 3 -
// a straight-line "what happens if nothing changes" scenario derived from
// real transaction history), and, as of slice 4, Scenario CRUD
// (ScenarioStore.kt) - named "what if" parameter sets that add their own
// line to every goal's chart. All four live in one route file/one page,
// same "one file per page" shape CategoryRoutes.kt uses for /categories'
// categories + rules. Entries/goals/scenarios are never system-derived (no
// CSV/Transaction linkage) - a household adds/edits/deletes its own rows
// here, same "you're the source of truth" shape as /categories, just
// without the built-in seeding since there's no sensible default set of
// assets/liabilities/goals/scenarios the way there is for spending
// categories.
fun Route.netWorthRoutes(
    netWorthEntryStore: NetWorthEntryRepository,
    financialGoalStore: FinancialGoalRepository,
    scenarioStore: ScenarioRepository,
    transactionStore: TransactionRepository,
    categoryStore: CategoryRepository
) {
    get("/planning") {
        val ownerId = call.requireUserId()
        val entries = netWorthEntryStore.all(ownerId)
        val goals = financialGoalStore.all(ownerId)
        val scenarios = scenarioStore.all(ownerId)
        val transactions = transactionStore.all(ownerId)
        val categories = categoryStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = netWorthPageModel(entries, goals, scenarios, transactions, categories, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("planning.ftl", model))
    }

    // Zip of plain CSVs for a third party to verify without app access - see
    // PlanningExport.kt. Reuses the same stores as the page above.
    get("/planning/export") {
        val ownerId = call.requireUserId()
        val entries = netWorthEntryStore.all(ownerId)
        val goals = financialGoalStore.all(ownerId)
        val scenarios = scenarioStore.all(ownerId)
        val transactions = transactionStore.all(ownerId)
        val categories = categoryStore.all(ownerId)
        val today = LocalDate.now()
        val zipBytes = planningExportZip(entries, goals, scenarios, transactions, categories, today)
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, "planning-export-$today.zip").toString()
        )
        call.respondBytes(zipBytes, ContentType.Application.Zip)
    }

    post("/planning/entries") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val input = parseEntryForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid entry".encodeURLQueryComponent()}")
        val entry = netWorthEntryStore.add(
            ownerId, input.label, input.type, input.value,
            input.annualInterestRate, input.mortgagePaymentCategoryId, input.annualAppreciationRate
        )
        call.respondRedirect("/planning?message=${"Added ${entry.label}".encodeURLQueryComponent()}")
    }

    post("/planning/entries/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val input = parseEntryForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid entry".encodeURLQueryComponent()}")
        val updated = netWorthEntryStore.update(
            ownerId, id, input.label, input.type, input.value,
            input.annualInterestRate, input.mortgagePaymentCategoryId, input.annualAppreciationRate
        )
        val message = if (updated != null) "Updated ${updated.label}" else "Entry not found"
        val param = if (updated != null) "message" else "error"
        call.respondRedirect("/planning?$param=${message.encodeURLQueryComponent()}")
    }

    post("/planning/entries/{id}/delete") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        netWorthEntryStore.delete(ownerId, id)
        call.respondRedirect("/planning?message=${"Deleted entry".encodeURLQueryComponent()}")
    }

    post("/planning/goals") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val input = parseGoalForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid goal".encodeURLQueryComponent()}")
        val goal = financialGoalStore.add(ownerId, input.name, input.type, input.targetDate, input.targetAmount, input.annualSpend, input.withdrawalRate)
        call.respondRedirect("/planning?message=${"Added ${goal.name}".encodeURLQueryComponent()}")
    }

    post("/planning/goals/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val input = parseGoalForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid goal".encodeURLQueryComponent()}")
        val updated = financialGoalStore.update(ownerId, id, input.name, input.type, input.targetDate, input.targetAmount, input.annualSpend, input.withdrawalRate)
        val message = if (updated != null) "Updated ${updated.name}" else "Goal not found"
        val param = if (updated != null) "message" else "error"
        call.respondRedirect("/planning?$param=${message.encodeURLQueryComponent()}")
    }

    post("/planning/goals/{id}/delete") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        financialGoalStore.delete(ownerId, id)
        call.respondRedirect("/planning?message=${"Deleted goal".encodeURLQueryComponent()}")
    }

    post("/planning/scenarios") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val input = parseScenarioForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid scenario".encodeURLQueryComponent()}")
        val scenario = scenarioStore.add(
            ownerId, input.name, input.annualMarketGrowthRate, input.investedSavingsFraction,
            input.recreationalSpendAdjustment, input.salaryChangeDate, input.salaryChangeMonthlyDelta,
            input.salaryChangeEndDate, input.salaryChangeRrspContributionOverride, input.salaryChangeMarginalTaxRateOverride,
            input.salaryChangeRoomAccrualOverride, input.rrspMonthlyContribution, input.rrspMarginalTaxRate,
            input.rrspRoomRemaining, input.rrspReinvestRefund, input.rrspIncomeCategoryId, input.rrspAnnualRoomAccrualCap
        )
        call.respondRedirect("/planning?message=${"Added ${scenario.name}".encodeURLQueryComponent()}")
    }

    post("/planning/scenarios/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val input = parseScenarioForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid scenario".encodeURLQueryComponent()}")
        val updated = scenarioStore.update(
            ownerId, id, input.name, input.annualMarketGrowthRate, input.investedSavingsFraction,
            input.recreationalSpendAdjustment, input.salaryChangeDate, input.salaryChangeMonthlyDelta,
            input.salaryChangeEndDate, input.salaryChangeRrspContributionOverride, input.salaryChangeMarginalTaxRateOverride,
            input.salaryChangeRoomAccrualOverride, input.rrspMonthlyContribution, input.rrspMarginalTaxRate,
            input.rrspRoomRemaining, input.rrspReinvestRefund, input.rrspIncomeCategoryId, input.rrspAnnualRoomAccrualCap
        )
        val message = if (updated != null) "Updated ${updated.name}" else "Scenario not found"
        val param = if (updated != null) "message" else "error"
        call.respondRedirect("/planning?$param=${message.encodeURLQueryComponent()}")
    }

    post("/planning/scenarios/{id}/delete") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        scenarioStore.delete(ownerId, id)
        call.respondRedirect("/planning?message=${"Deleted scenario".encodeURLQueryComponent()}")
    }
}

private data class EntryFormInput(
    val label: String,
    val type: NetWorthEntryType,
    val value: Double,
    val annualInterestRate: Double?,
    val mortgagePaymentCategoryId: String?,
    val annualAppreciationRate: Double?
)

// The mortgage rate/payment-category fields are a coherent pair, same
// "both or neither" rule as Scenario's salary-change fields - a rate with
// no category to derive a payment from (or vice versa) can't drive an
// amortization schedule. The payment itself is never typed in by hand - see
// NetWorthEntry's doc comment for why this points at a household Category
// (the same tagged-category pattern Scenario.rrspIncomeCategoryId already
// uses) instead of a raw dollar figure. Appreciation is its own independent
// optional field. All three are accepted regardless of the chosen `type`
// (same posture planning.ftl already takes with Scenario's RRSP fields) -
// they're only ever read for MORTGAGE/REAL_ESTATE entries respectively (see
// NetWorthEntry.isAmortizingMortgage/isAppreciatingRealEstate), so a stray
// value on the wrong type is inert rather than a validation error.
private fun parseEntryForm(formParams: Parameters): EntryFormInput? {
    val label = formParams["label"]?.trim().orEmpty()
    if (label.isEmpty()) return null
    val type = formParams["type"]?.let { runCatching { NetWorthEntryType.valueOf(it) }.getOrNull() } ?: return null
    // Manual entry is always a non-negative magnitude regardless of asset
    // vs. liability - see NetWorthStore.kt's netWorthTotal doc comment for
    // why sign is derived from type rather than the input.
    val value = formParams["value"]?.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null

    val interestRateInput = formParams["annualInterestRatePercent"]?.trim().orEmpty()
    val paymentCategoryInput = formParams["mortgagePaymentCategoryId"]?.trim().orEmpty()
    val (annualInterestRate, mortgagePaymentCategoryId) = when {
        interestRateInput.isEmpty() && paymentCategoryInput.isEmpty() -> null to null
        else -> {
            val rate = interestRateInput.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
            if (paymentCategoryInput.isEmpty()) return null
            (rate / 100.0) to paymentCategoryInput
        }
    }
    val annualAppreciationRate = formParams["annualAppreciationRatePercent"]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    return EntryFormInput(label, type, value, annualInterestRate, mortgagePaymentCategoryId, annualAppreciationRate?.div(100.0))
}

private data class GoalFormInput(
    val name: String,
    val type: FinancialGoalType,
    val targetDate: LocalDate,
    val targetAmount: Double?,
    val annualSpend: Double?,
    val withdrawalRate: Double?
)

// The add form's two field groups (target amount vs. annual spend +
// withdrawal rate) are toggled client-side by planning.ftl's script based
// on the selected type - this is what actually enforces "the right fields
// for the type" server-side, since a client toggle alone can't be trusted.
// Edit forms carry their goal's type as a hidden field rather than letting
// it be changed - switching a goal's type after creation isn't supported
// yet, so there's no case here that needs to translate one type's fields
// into the other's.
private fun parseGoalForm(formParams: Parameters): GoalFormInput? {
    val name = formParams["name"]?.trim().orEmpty()
    if (name.isEmpty()) return null
    val type = formParams["type"]?.let { runCatching { FinancialGoalType.valueOf(it) }.getOrNull() } ?: return null
    val targetDate = formParams["targetDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    return when (type) {
        FinancialGoalType.NET_WORTH_TARGET -> {
            val targetAmount = formParams["targetAmount"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            GoalFormInput(name, type, targetDate, targetAmount, null, null)
        }
        FinancialGoalType.RETIREMENT -> {
            val annualSpend = formParams["annualSpend"]?.toDoubleOrNull()?.takeIf { it > 0 } ?: return null
            val withdrawalRatePercent = formParams["withdrawalRatePercent"]?.toDoubleOrNull()?.takeIf { it > 0 }
            GoalFormInput(name, type, targetDate, null, annualSpend, withdrawalRatePercent?.let { it / 100.0 })
        }
    }
}

private data class ScenarioFormInput(
    val name: String,
    val annualMarketGrowthRate: Double,
    val investedSavingsFraction: Double,
    val recreationalSpendAdjustment: Double,
    val salaryChangeDate: LocalDate?,
    val salaryChangeMonthlyDelta: Double?,
    val salaryChangeEndDate: LocalDate?,
    val salaryChangeRrspContributionOverride: Double?,
    val salaryChangeMarginalTaxRateOverride: Double?,
    val salaryChangeRoomAccrualOverride: Double?,
    val rrspMonthlyContribution: Double?,
    val rrspMarginalTaxRate: Double?,
    val rrspRoomRemaining: Double?,
    val rrspReinvestRefund: Boolean,
    val rrspIncomeCategoryId: String?,
    val rrspAnnualRoomAccrualCap: Double?
)

// The salary-change fields are optional as a pair, and the RRSP fields as
// a trio - all present or all absent/blank within their own group, never
// partially filled in (a date with no delta isn't a coherent salary
// event; a contribution with no room cap isn't a coherent RRSP strategy)
// - see ScenarioStore.kt's Scenario doc comment for why both are single
// optional groups rather than lists.
private fun parseScenarioForm(formParams: Parameters): ScenarioFormInput? {
    val name = formParams["name"]?.trim().orEmpty()
    if (name.isEmpty()) return null
    val annualMarketGrowthRatePercent = formParams["annualMarketGrowthRatePercent"]?.toDoubleOrNull() ?: return null
    val investedSavingsFractionPercent = formParams["investedSavingsFractionPercent"]?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return null
    val recreationalSpendAdjustment = formParams["recreationalSpendAdjustment"]?.toDoubleOrNull() ?: return null

    val salaryChangeDateInput = formParams["salaryChangeDate"]?.trim().orEmpty()
    val salaryChangeDeltaInput = formParams["salaryChangeMonthlyDelta"]?.trim().orEmpty()
    val (salaryChangeDate, salaryChangeMonthlyDelta) = when {
        salaryChangeDateInput.isEmpty() && salaryChangeDeltaInput.isEmpty() -> null to null
        else -> {
            val date = runCatching { LocalDate.parse(salaryChangeDateInput) }.getOrNull() ?: return null
            val delta = salaryChangeDeltaInput.toDoubleOrNull() ?: return null
            date to delta
        }
    }

    // Optional even when the pair above is set - blank means permanent,
    // same as before this field existed. Only meaningful with a
    // salary-change event to attach to; a stray value with no event
    // configured is inert (ProjectionEngine.kt only ever reads it via
    // salaryChangeDate), so it's only parsed/validated when that's set.
    val salaryChangeEndDateInput = formParams["salaryChangeEndDate"]?.trim().orEmpty()
    val salaryChangeEndDate = if (salaryChangeDate != null && salaryChangeEndDateInput.isNotEmpty()) {
        val endDate = runCatching { LocalDate.parse(salaryChangeEndDateInput) }.getOrNull() ?: return null
        if (!endDate.isAfter(salaryChangeDate)) return null // can't revert before (or the same month) it starts
        endDate
    } else {
        null
    }
    // Each independently optional (blank = "no change," the default) -
    // see Scenario.kt's doc comment for why these replace the RRSP
    // trio's own numbers only while the salary-change event is active,
    // rather than being validated as a coherent group the way the RRSP
    // trio itself is.
    val salaryChangeRrspContributionOverride = formParams["salaryChangeRrspContributionOverride"]?.trim()
        ?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it >= 0 }
    val salaryChangeMarginalTaxRateOverride = formParams["salaryChangeMarginalTaxRateOverridePercent"]?.trim()
        ?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }?.div(100.0)
    val salaryChangeRoomAccrualOverride = formParams["salaryChangeRoomAccrualOverride"]?.trim()
        ?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it >= 0 }

    val rrspContributionInput = formParams["rrspMonthlyContribution"]?.trim().orEmpty()
    val rrspTaxRateInput = formParams["rrspMarginalTaxRatePercent"]?.trim().orEmpty()
    val rrspRoomInput = formParams["rrspRoomRemaining"]?.trim().orEmpty()
    val (rrspMonthlyContribution, rrspMarginalTaxRate, rrspRoomRemaining) = when {
        rrspContributionInput.isEmpty() && rrspTaxRateInput.isEmpty() && rrspRoomInput.isEmpty() -> Triple(null, null, null)
        else -> {
            val contribution = rrspContributionInput.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
            val taxRatePercent = rrspTaxRateInput.toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return null
            val room = rrspRoomInput.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
            Triple(contribution, taxRatePercent / 100.0, room)
        }
    }
    val rrspReinvestRefund = formParams["rrspReinvestRefund"] != null
    // Independent of the contribution trio above - a household can track
    // room accrual without necessarily contributing in this scenario, or
    // vice versa. "" (the add form's default "no category selected"
    // option) means no accrual, same as never selecting one.
    val rrspIncomeCategoryId = formParams["rrspIncomeCategoryId"]?.trim()?.takeIf { it.isNotEmpty() }
    val rrspAnnualRoomAccrualCap = formParams["rrspAnnualRoomAccrualCap"]?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it >= 0 }

    return ScenarioFormInput(
        name,
        annualMarketGrowthRatePercent / 100.0,
        investedSavingsFractionPercent / 100.0,
        recreationalSpendAdjustment,
        salaryChangeDate,
        salaryChangeMonthlyDelta,
        salaryChangeEndDate,
        salaryChangeRrspContributionOverride,
        salaryChangeMarginalTaxRateOverride,
        salaryChangeRoomAccrualOverride,
        rrspMonthlyContribution,
        rrspMarginalTaxRate,
        rrspRoomRemaining,
        rrspReinvestRefund,
        rrspIncomeCategoryId,
        rrspAnnualRoomAccrualCap
    )
}
