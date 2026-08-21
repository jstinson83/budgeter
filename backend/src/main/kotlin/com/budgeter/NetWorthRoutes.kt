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
    transactionStore: TransactionRepository
) {
    get("/planning") {
        val ownerId = call.requireUserId()
        val entries = netWorthEntryStore.all(ownerId)
        val goals = financialGoalStore.all(ownerId)
        val scenarios = scenarioStore.all(ownerId)
        val transactions = transactionStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = netWorthPageModel(entries, goals, scenarios, transactions, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("planning.ftl", model))
    }

    post("/planning/entries") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val input = parseEntryForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid entry".encodeURLQueryComponent()}")
        val entry = netWorthEntryStore.add(ownerId, input.label, input.type, input.value)
        call.respondRedirect("/planning?message=${"Added ${entry.label}".encodeURLQueryComponent()}")
    }

    post("/planning/entries/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val input = parseEntryForm(formParams)
            ?: return@post call.respondRedirect("/planning?error=${"Invalid entry".encodeURLQueryComponent()}")
        val updated = netWorthEntryStore.update(ownerId, id, input.label, input.type, input.value)
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
            input.rrspMonthlyContribution, input.rrspMarginalTaxRate, input.rrspRoomRemaining, input.rrspReinvestRefund
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
            input.rrspMonthlyContribution, input.rrspMarginalTaxRate, input.rrspRoomRemaining, input.rrspReinvestRefund
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

private data class EntryFormInput(val label: String, val type: NetWorthEntryType, val value: Double)

private fun parseEntryForm(formParams: Parameters): EntryFormInput? {
    val label = formParams["label"]?.trim().orEmpty()
    if (label.isEmpty()) return null
    val type = formParams["type"]?.let { runCatching { NetWorthEntryType.valueOf(it) }.getOrNull() } ?: return null
    // Manual entry is always a non-negative magnitude regardless of asset
    // vs. liability - see NetWorthStore.kt's netWorthTotal doc comment for
    // why sign is derived from type rather than the input.
    val value = formParams["value"]?.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
    return EntryFormInput(label, type, value)
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
    val rrspMonthlyContribution: Double?,
    val rrspMarginalTaxRate: Double?,
    val rrspRoomRemaining: Double?,
    val rrspReinvestRefund: Boolean
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

    return ScenarioFormInput(
        name,
        annualMarketGrowthRatePercent / 100.0,
        investedSavingsFractionPercent / 100.0,
        recreationalSpendAdjustment,
        salaryChangeDate,
        salaryChangeMonthlyDelta,
        rrspMonthlyContribution,
        rrspMarginalTaxRate,
        rrspRoomRemaining,
        rrspReinvestRefund
    )
}
