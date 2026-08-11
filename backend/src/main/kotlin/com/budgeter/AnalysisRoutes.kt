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

// Resolves the calendar month being viewed from either query params (GET
// /analysis) or form params (POST /analysis/recategorize) - both are a
// Ktor Parameters, so one helper covers both call sites. Falls back to the
// current month on anything missing, unparseable, or out of range
// (including a year extreme enough that LocalDate.of itself throws)
// rather than erroring the whole page over a malformed link. Also clamps
// anything after the current month back to the current month - there's no
// such thing as next month's transactions, and this is the one place both
// the nav arrows and a hand-edited URL go through, so clamping here covers
// both.
private fun resolveYearMonth(params: Parameters): Pair<Int, Int> {
    val today = LocalDate.now()
    val year = params["year"]?.toIntOrNull() ?: today.year
    val month = params["month"]?.toIntOrNull()?.takeIf { it in 1..12 } ?: today.monthValue
    return try {
        val resolved = LocalDate.of(year, month, 1)
        if (resolved.isAfter(LocalDate.of(today.year, today.monthValue, 1))) {
            today.year to today.monthValue
        } else {
            year to month
        }
    } catch (e: DateTimeException) {
        today.year to today.monthValue
    }
}

private fun monthLabel(monthStart: LocalDate): String =
    "${monthStart.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${monthStart.year}"

// Short "Jul 2026" form used on the prev/next nav links themselves, so
// which way is forward in time doesn't depend on remembering an arrow
// convention - the target month is right there next to the arrow.
private fun shortMonthLabel(monthStart: LocalDate): String =
    "${monthStart.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${monthStart.year}"

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

        // Categorization is no longer a manual step - every load of this
        // page is also a chance to run it, including a fresh sweep for any
        // transfer pair that matches TransferMatcher's templates but hasn't
        // been bucketed as one yet, regardless of whether it already has
        // some other category (see CategorizationJob.kt). Safe to call
        // unconditionally: it's a cheap no-op once nothing's left to fix,
        // and it never re-bills Gemini for an already-categorized
        // transaction or duplicates an in-flight job.
        categorizationJobManager.categorize(ownerId)

        val all = transactionStore.all(ownerId)
        // TRANSFER excluded here, not just left out of a bucket - it's
        // money moving between the user's own accounts, not spending or
        // income, so it shouldn't appear in analysis at all.
        val periodTransactions = all.filter {
            !it.date.isBefore(monthStart) && it.date.isBefore(monthEnd) && it.category != TRANSFER_CATEGORY_ID
        }

        val prev = monthStart.minusMonths(1)
        val next = monthStart.plusMonths(1)
        // Current calendar month is as far forward as navigation goes -
        // there's nothing to show past it, so the "next" link disappears
        // entirely here rather than linking to a month resolveYearMonth
        // would just clamp back down anyway.
        val today = LocalDate.now()
        val isCurrentMonth = year == today.year && month == today.monthValue

        // consumeTerminal rather than status: this is the one page render
        // that ever shows a finished job's outcome, so it also forgets that
        // job once shown - otherwise a later unrelated visit would keep
        // re-displaying the same "Categorized ..." banner indefinitely (see
        // CategorizationJob.kt). For a job that finished synchronously
        // above (transfer/rule matches with nothing left for Gemini),
        // this is also what surfaces its message on this very page load.
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
            prevMonthLabel = shortMonthLabel(prev),
            nextHref = if (isCurrentMonth) null else "/analysis?year=${next.year}&month=${next.monthValue}",
            nextMonthLabel = if (isCurrentMonth) null else shortMonthLabel(next),
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

    // Inline "Recategorize" action from an /analysis/category/{slug}
    // drill-down row (analysis-category.ftl): fixes this one transaction
    // immediately (it's already categorized - OTHER, or whatever the
    // pending-transaction pass picked - so it won't come back through
    // CategorizationJobManager's uncategorized() pass on its own) and saves
    // a CategorizationRule so the same description auto-applies going
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
