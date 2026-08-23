package com.budgeter

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Management page for the household's categories (add, enable/disable) and
// saved CategorizationRules (add/edit/delete) - see CategoryStore.kt and
// CategorizationRuleStore.kt. Categories are never deleted outright, only
// disabled: transactions and rules already pointing at one keep working,
// and disabling just removes it from future manual/Gemini assignment (see
// AnalysisPage.kt/AnalysisRoutes.kt).
fun Route.categoryRoutes(
    categoryStore: CategoryRepository,
    categorizationRuleStore: CategorizationRuleRepository,
    transactionStore: TransactionRepository
) {
    get("/categories") {
        val ownerId = call.requireUserId()
        val categories = categoryStore.all(ownerId)
        val rules = categorizationRuleStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = categoriesPageModel(categories, rules, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("categories.ftl", model))
    }

    post("/categories") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val label = formParams["label"]?.trim().orEmpty()
        if (label.isEmpty()) {
            call.respondRedirect("/categories?error=${"Category name is required".encodeURLQueryComponent()}")
            return@post
        }
        val category = categoryStore.add(ownerId, label)
        call.respondRedirect("/categories?message=${"Added ${category.label}".encodeURLQueryComponent()}")
    }

    // Forces a full recategorize pass from scratch - clears every
    // transaction's category, then hands off to /analysis, whose GET
    // handler already unconditionally runs the normal categorize() pass
    // (TransferMatcher -> rules -> Gemini, CategorizationJob.kt) on every
    // load, "Categorizing…" polling UI included. Added after enough rule
    // churn during development left some transactions sitting in stale
    // categories that the normal pass would never revisit (it only looks
    // at still-uncategorized rows) - a blunt reset-and-redo rather than
    // trying to detect which specific transactions are stale.
    post("/categories/recategorize-all") {
        val ownerId = call.requireUserId()
        transactionStore.resetCategories(ownerId)
        call.respondRedirect("/analysis")
    }

    post("/categories/{id}/toggle") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val category = categoryStore.all(ownerId).find { it.id == id } ?: return@post call.respond(HttpStatusCode.NotFound)
        categoryStore.setActive(ownerId, id, !category.active)
        val verb = if (category.active) "Disabled" else "Enabled"
        call.respondRedirect("/categories?message=${"$verb ${category.label}".encodeURLQueryComponent()}")
    }

    post("/categories/rules") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val (pattern, matchType, category) = validateRuleForm(formParams, categoryStore, ownerId)
            ?: return@post call.respondRedirect("/categories?error=${"Invalid rule".encodeURLQueryComponent()}")
        val rule = categorizationRuleStore.add(ownerId, pattern, matchType, category.id)
        val rescanCount = if (formParams["rescan"] != null) rescanExistingTransactions(ownerId, rule, transactionStore) else null
        val message = listOfNotNull("Added rule", rescanCount?.let { "recategorized $it existing transaction(s)" })
            .joinToString(", ")
        call.respondRedirect("/categories?message=${message.encodeURLQueryComponent()}")
    }

    post("/categories/rules/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val (pattern, matchType, category) = validateRuleForm(formParams, categoryStore, ownerId)
            ?: return@post call.respondRedirect("/categories?error=${"Invalid rule".encodeURLQueryComponent()}")
        val updated = categorizationRuleStore.update(ownerId, id, pattern, matchType, category.id)
        val message = if (updated != null) "Updated rule" else "Rule not found"
        val param = if (updated != null) "message" else "error"
        call.respondRedirect("/categories?$param=${message.encodeURLQueryComponent()}")
    }

    post("/categories/rules/{id}/delete") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        categorizationRuleStore.delete(ownerId, id)
        call.respondRedirect("/categories?message=${"Deleted rule".encodeURLQueryComponent()}")
    }
}

// Applies a newly-created rule against the owner's whole transaction
// history, not just still-pending ones - CategorizationRuleMatcher only
// ever sees uncategorized transactions during a normal /analysis
// categorize pass (CategorizationJob.kt), so without this a rule created
// after the fact would silently never touch transactions it should have
// caught. Deterministic in-memory string matching against however many
// transactions the household has, no external API call - synchronous is
// fine here and doesn't need CategorizationJobManager's async/poll
// machinery, which exists specifically to survive Gemini's request-bound
// latency (see CategorizationJob.kt). TRANSFER-categorized transactions
// are excluded so a rule can never override TransferMatcher's own
// classification, matching the priority TransferMatcher already has over
// rules in the normal categorize pass.
private suspend fun rescanExistingTransactions(ownerId: String, rule: CategorizationRule, transactionStore: TransactionRepository): Int {
    val candidates = transactionStore.all(ownerId).filter { it.category != TRANSFER_CATEGORY_ID && it.category != rule.category }
    val matches = CategorizationRuleMatcher.match(listOf(rule), candidates)
    if (matches.isNotEmpty()) transactionStore.updateCategories(ownerId, matches)
    return matches.size
}

private data class RuleFormInput(val pattern: String, val matchType: MatchType, val category: Category)

// Shared by both the create and edit rule handlers - a rule's target must
// be one of this owner's own *active* categories, same restriction the
// /analysis/recategorize handler applies.
private suspend fun validateRuleForm(formParams: Parameters, categoryStore: CategoryRepository, ownerId: String): RuleFormInput? {
    val pattern = formParams["pattern"]?.trim().orEmpty()
    val matchType = formParams["matchType"]?.let { runCatching { MatchType.valueOf(it) }.getOrNull() } ?: return null
    val categoryId = formParams["category"] ?: return null
    val category = categoryStore.all(ownerId).find { it.id == categoryId && it.active } ?: return null
    if (pattern.isEmpty()) return null
    return RuleFormInput(pattern, matchType, category)
}
