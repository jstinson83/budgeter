package com.budgeter

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Manual net worth tracking (NetWorthStore.kt) - the baseline slice 1 of
// the Financial Planning Projections feature, see .claude/current.md.
// Entries are never system-derived (no CSV/Transaction linkage) - a
// household adds/edits/deletes its own asset and liability line items
// here, same "you're the source of truth" shape as /categories, just
// without the built-in seeding since there's no sensible default set of
// assets/liabilities the way there is for spending categories.
fun Route.netWorthRoutes(netWorthEntryStore: NetWorthEntryRepository) {
    get("/planning") {
        val ownerId = call.requireUserId()
        val entries = netWorthEntryStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = netWorthPageModel(entries, message, error) + call.currentUserModel()
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
