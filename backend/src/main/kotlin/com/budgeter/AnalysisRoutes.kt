package com.budgeter

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
    categorizationRuleStore: CategorizationRuleRepository,
    categoryStore: CategoryRepository,
    categorizationJobManager: CategorizationJobManager
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
            !it.date.isBefore(monthStart) && it.date.isBefore(monthEnd) && it.category != TRANSFER_CATEGORY_ID
        }
        val uncategorizedCount = all.count { it.category == null }

        val prev = monthStart.minusMonths(1)
        val next = monthStart.plusMonths(1)

        // consumeTerminal rather than status: this is the one page render
        // that ever shows a finished job's outcome, so it also forgets that
        // job once shown - otherwise a later unrelated visit would keep
        // re-displaying the same "Categorized ..." banner indefinitely (see
        // CategorizationJob.kt).
        val jobState = categorizationJobManager.consumeTerminal(ownerId)
        val jobRunning = jobState?.status == CategorizationJobStatus.RUNNING
        val message = call.request.queryParameters["message"]
            ?: jobState?.takeIf { it.status == CategorizationJobStatus.DONE }?.message
        val error = call.request.queryParameters["error"]
            ?: jobState?.takeIf { it.status == CategorizationJobStatus.FAILED }?.error
        val model = analysisPageModel(
            periodTransactions,
            categoryStore.all(ownerId),
            year,
            month,
            monthLabel(monthStart),
            prevHref = "/analysis?year=${prev.year}&month=${prev.monthValue}",
            nextHref = "/analysis?year=${next.year}&month=${next.monthValue}",
            uncategorizedCount,
            message,
            error,
            jobRunning
        ) + call.currentUserModel()
        call.respond(FreeMarkerContent("analysis.ftl", model))
    }

    // Polled by analysis.ftl's inline script while a categorize job is
    // running (see CategorizationJob.kt for why polling matters beyond just
    // the UI on Cloud Run). Non-consuming - every poll up to the one that
    // sees a terminal state must see the same result.
    get("/analysis/categorize/status") {
        val ownerId = call.requireUserId()
        val state = categorizationJobManager.status(ownerId)
        val response = CategorizationJobStatusResponse(
            status = state?.status?.name ?: "IDLE",
            message = state?.message,
            error = state?.error
        )
        call.respondText(Json.encodeToString(response), ContentType.Application.Json)
    }

    get("/analysis/category/{category}") {
        val ownerId = call.requireUserId()
        val (year, month) = resolveYearMonth(call.request.queryParameters)
        val monthStart = LocalDate.of(year, month, 1)
        val monthEnd = monthStart.plusMonths(1)

        val categories = categoryStore.all(ownerId)
        val slug = call.parameters["category"] ?: ""
        // null stands for "Uncategorized" - special-cased rather than
        // looked up. TRANSFER 404s here for free: it's never a real
        // Category row (see CategoryStore.kt), so no category ever
        // resolves for that slug - a request for it is either a stale link
        // or a guess, not a legitimate drill-down. A disabled category
        // still resolves and drills down fine - disabling only stops new
        // assignment, past transactions keep their category.
        val category = when {
            slug == "uncategorized" -> null
            else -> categories.find { it.id.equals(slug, ignoreCase = true) } ?: return@get call.respond(HttpStatusCode.NotFound)
        }

        val transactions = transactionStore.all(ownerId).filter {
            it.category == category?.id && !it.date.isBefore(monthStart) && it.date.isBefore(monthEnd)
        }
        val categoryLabel = category?.label ?: "Uncategorized"
        val model = analysisCategoryPageModel(
            transactions,
            categoryLabel,
            monthLabel(monthStart),
            backHref = "/analysis?year=$year&month=$month",
            year = year,
            month = month,
            categorySlug = slug,
            categoryOptions = categories
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

        // categorizationJobManager owns the whole pass (transfer/rule
        // matching, then the Gemini leg as a background job) - see
        // CategorizationJob.kt.
        val message = categorizationJobManager.categorize(ownerId)
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
        val categoryId = formParams["category"]
        // Must be one of this owner's own *active* categories - rejects a
        // client posting an arbitrary string, someone else's category, a
        // disabled one, or (since TRANSFER is never a real Category row)
        // "TRANSFER" specifically, same rejection the old enum-equality
        // check gave it.
        val category = categoryId?.let { id -> categoryStore.all(ownerId).find { it.id == id && it.active } }
        val matchType = formParams["matchType"]?.let { runCatching { MatchType.valueOf(it) }.getOrNull() }
        val pattern = formParams["pattern"]?.trim().orEmpty()

        if (transactionId == null || category == null || matchType == null || pattern.isEmpty()) {
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

        categorizationRuleStore.add(ownerId, pattern, matchType, category.id)
        transactionStore.updateCategories(ownerId, mapOf(transactionId to category.id))

        call.respondRedirect("$backHref&message=${"Recategorized as ${category.label}".encodeURLQueryComponent()}")
    }
}
