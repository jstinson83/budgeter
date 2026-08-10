package com.budgeter

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.DateTimeException
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// Resolves the calendar month being viewed from either query params (GET)
// or form params (POST, see /analysis/categorize) - both are a Ktor
// Parameters, so one helper covers both call sites. Falls back to the
// current month on anything missing, unparseable, or out of range
// (including a year extreme enough that LocalDate.of itself throws)
// rather than erroring the whole page over a malformed link.
private fun resolveYearMonth(params: Parameters): Pair<Int, Int> {
    val today = LocalDate.now()
    val year = params["year"]?.toIntOrNull() ?: today.year
    val month = params["month"]?.toIntOrNull()?.takeIf { it in 1..12 } ?: today.monthValue
    return try {
        LocalDate.of(year, month, 1)
        year to month
    } catch (e: DateTimeException) {
        today.year to today.monthValue
    }
}

private fun monthLabel(monthStart: LocalDate): String =
    "${monthStart.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${monthStart.year}"

fun Route.analysisRoutes(
    transactionStore: TransactionRepository,
    transactionCategorizer: TransactionCategorizer,
    categorizationRuleStore: CategorizationRuleRepository
) {
    get("/analysis") {
        val ownerId = call.requireUserId()
        val (year, month) = resolveYearMonth(call.request.queryParameters)
        val monthStart = LocalDate.of(year, month, 1)
        val monthEnd = monthStart.plusMonths(1)

        val all = transactionStore.all(ownerId)
        // TRANSFER excluded here, not just left out of a bucket - it's
        // money moving between the user's own accounts, not spending or
        // income, so it shouldn't appear in analysis at all.
        val periodTransactions = all.filter {
            !it.date.isBefore(monthStart) && it.date.isBefore(monthEnd) && it.category != TransactionCategory.TRANSFER
        }
        val uncategorizedCount = all.count { it.category == null }

        val prev = monthStart.minusMonths(1)
        val next = monthStart.plusMonths(1)

        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = analysisPageModel(
            periodTransactions,
            year,
            month,
            monthLabel(monthStart),
            prevHref = "/analysis?year=${prev.year}&month=${prev.monthValue}",
            nextHref = "/analysis?year=${next.year}&month=${next.monthValue}",
            uncategorizedCount,
            message,
            error
        ) + call.currentUserModel()
        call.respond(FreeMarkerContent("analysis.ftl", model))
    }

    get("/analysis/category/{category}") {
        val ownerId = call.requireUserId()
        val (year, month) = resolveYearMonth(call.request.queryParameters)
        val monthStart = LocalDate.of(year, month, 1)
        val monthEnd = monthStart.plusMonths(1)

        val slug = call.parameters["category"] ?: ""
        // null stands for "Uncategorized" - it has no TransactionCategory
        // name to parse, so it's special-cased rather than run through
        // valueOf. TRANSFER is deliberately rejected too: it's excluded
        // from analysis entirely (see the TRANSFER filter above), so no
        // category row ever links here with that slug - a request for it
        // is either a stale link or a guess, not a legitimate drill-down.
        val category = when {
            slug == "uncategorized" -> null
            else -> runCatching { TransactionCategory.valueOf(slug.uppercase()) }.getOrNull()
                ?: return@get call.respond(HttpStatusCode.NotFound)
        }
        if (category == TransactionCategory.TRANSFER) return@get call.respond(HttpStatusCode.NotFound)

        val transactions = transactionStore.all(ownerId).filter {
            it.category == category && !it.date.isBefore(monthStart) && it.date.isBefore(monthEnd)
        }
        val categoryLabel = category?.label ?: "Uncategorized"
        val model = analysisCategoryPageModel(
            transactions,
            categoryLabel,
            monthLabel(monthStart),
            backHref = "/analysis?year=$year&month=$month",
            year = year,
            month = month,
            categorySlug = slug
        ) + call.currentUserModel()
        call.respond(FreeMarkerContent("analysis-category.ftl", model))
    }

    post("/analysis/categorize") {
        val ownerId = call.requireUserId()
        // The categorize button's form always submits hidden year/month
        // fields (see analysis.ftl), but a bare POST with no body - as
        // every pre-existing test in this file sends - isn't
        // form-urlencoded at all, and receiveParameters() throws on that
        // rather than returning empty. Falling back to Parameters.Empty
        // just means resolveYearMonth defaults to the current month, same
        // as if the fields were present but blank.
        val formParams = try { call.receiveParameters() } catch (e: Exception) { Parameters.Empty }
        val (year, month) = resolveYearMonth(formParams)
        val monthParams = "year=$year&month=$month"

        val pending = transactionStore.uncategorized(ownerId)
        if (pending.isEmpty()) {
            call.respondRedirect("/analysis?$monthParams&message=${"No new transactions to categorize".encodeURLQueryComponent()}")
            return@post
        }

        // Transfer matching runs first and is claimed (updateCategories)
        // before Gemini ever sees these rows - otherwise it'd burn a
        // request trying to categorize "TFR-TO C/C" / "PAYMENT - THANK YOU"
        // rows as ordinary spending, and could mis-bucket the credit-card
        // leg as INCOME.
        val transferMatches = TransferMatcher.match(pending)
        if (transferMatches.isNotEmpty()) {
            transactionStore.updateCategories(ownerId, transferMatches)
        }

        // Household-defined rules (see CategorizationRuleMatcher) run next,
        // also before Gemini - same "deterministic and free beats a guess
        // that costs an API call" reasoning, just for patterns the user
        // picked via the /analysis/category/{slug} "Recategorize" action
        // instead of TransferMatcher's fixed templates.
        val afterTransferMatch = pending.filterNot { it.id in transferMatches }
        val rules = categorizationRuleStore.all(ownerId)
        val ruleMatches = CategorizationRuleMatcher.match(rules, afterTransferMatch)
        if (ruleMatches.isNotEmpty()) {
            transactionStore.updateCategories(ownerId, ruleMatches)
        }

        val remaining = afterTransferMatch.filterNot { it.id in ruleMatches }
        val categorized = if (remaining.isEmpty()) emptyMap() else try {
            transactionCategorizer.categorize(remaining)
        } catch (e: Exception) {
            call.respondRedirect("/analysis?$monthParams&error=${"Categorization failed: ${e.message}".encodeURLQueryComponent()}")
            return@post
        }
        if (categorized.isNotEmpty()) {
            transactionStore.updateCategories(ownerId, categorized)
        }

        // transferMatches holds both legs of every matched pair, so its
        // size is always even - divide by 2 for the pair count.
        val messageParts = mutableListOf<String>()
        if (transferMatches.isNotEmpty()) messageParts += "Matched ${transferMatches.size / 2} transfer(s)"
        if (ruleMatches.isNotEmpty()) messageParts += "Applied ${ruleMatches.size} rule(s)"
        if (remaining.isNotEmpty()) messageParts += "Categorized ${categorized.size} of ${remaining.size} transaction(s)"
        val message = messageParts.joinToString(", ")
        call.respondRedirect("/analysis?$monthParams&message=${message.encodeURLQueryComponent()}")
    }

    // Inline "Recategorize" action from an /analysis/category/{slug}
    // drill-down row (analysis-category.ftl): fixes this one transaction
    // immediately (it's already categorized - OTHER, or whatever the
    // pending-transaction pass picked - so it won't come back through
    // /analysis/categorize's uncategorized() pass on its own) and saves a
    // CategorizationRule so the same description auto-applies going
    // forward, without redoing this by hand every month.
    post("/analysis/recategorize") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val fromSlug = formParams["fromSlug"] ?: "uncategorized"
        val (year, month) = resolveYearMonth(formParams)
        val backHref = "/analysis/category/$fromSlug?year=$year&month=$month"

        val transactionId = formParams["transactionId"]
        val category = formParams["category"]?.let { runCatching { TransactionCategory.valueOf(it) }.getOrNull() }
        val matchType = formParams["matchType"]?.let { runCatching { MatchType.valueOf(it) }.getOrNull() }
        val pattern = formParams["pattern"]?.trim().orEmpty()

        if (transactionId == null || category == null || category == TransactionCategory.TRANSFER || matchType == null || pattern.isEmpty()) {
            call.respondRedirect("$backHref&error=${"Invalid recategorize request".encodeURLQueryComponent()}")
            return@post
        }

        // Confirms the transaction is actually this owner's before touching
        // it - transactionId is client-supplied (a form field), unlike the
        // ids TransferMatcher/the categorizer work with, which only ever
        // come from this owner's own uncategorized() list.
        val transaction = transactionStore.all(ownerId).find { it.id == transactionId }
        if (transaction == null) {
            call.respondRedirect("$backHref&error=${"Transaction not found".encodeURLQueryComponent()}")
            return@post
        }

        categorizationRuleStore.add(ownerId, pattern, matchType, category)
        transactionStore.updateCategories(ownerId, mapOf(transactionId to category))

        call.respondRedirect("$backHref&message=${"Recategorized as ${category.label}".encodeURLQueryComponent()}")
    }
}
