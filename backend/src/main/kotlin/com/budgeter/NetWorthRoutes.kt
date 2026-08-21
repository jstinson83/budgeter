package com.budgeter

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

// The /planning page - manual net worth tracking (NetWorthStore.kt, slice
// 1) plus, as of slice 2, FinancialGoal CRUD (FinancialGoalStore.kt). Both
// sub-features live in one route file/one page, same "one file per page"
// shape CategoryRoutes.kt uses for /categories' categories + rules.
// Entries and goals are never system-derived (no CSV/Transaction linkage)
// - a household adds/edits/deletes its own rows here, same "you're the
// source of truth" shape as /categories, just without the built-in seeding
// since there's no sensible default set of assets/liabilities/goals the
// way there is for spending categories.
fun Route.netWorthRoutes(netWorthEntryStore: NetWorthEntryRepository, financialGoalStore: FinancialGoalRepository) {
    get("/planning") {
        val ownerId = call.requireUserId()
        val entries = netWorthEntryStore.all(ownerId)
        val goals = financialGoalStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = netWorthPageModel(entries, goals, message, error) + call.currentUserModel()
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
