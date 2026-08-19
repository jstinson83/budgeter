package com.budgeter

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

// Resolves the dashboard's time-period selector from query params
// (?period=3m|6m|custom&start=...&end=...). All the actual date-math/
// clamping lives on DashboardPeriod's companion (DashboardPage.kt) so it's
// covered by DashboardPageTest rather than duplicated here; this just reads
// the raw params and falls back to the 3-month default on anything missing
// or unparseable, same forgiving-fallback style as AnalysisRoutes.kt's
// resolveYearMonth.
private fun resolveDashboardPeriod(params: Parameters, today: LocalDate): DashboardPeriod =
    when (params["period"]) {
        "6m" -> DashboardPeriod.sixMonths(today)
        "custom" -> {
            val start = params["start"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val end = params["end"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (start != null && end != null) DashboardPeriod.custom(start, end, today) else DashboardPeriod.default(today)
        }
        else -> DashboardPeriod.default(today)
    }

// New landing page ("/") - replaces the old bare redirect to /analysis. See
// DashboardPage.kt for the actual summary logic; this route just fetches the
// owner's transactions/categories once and hands them to the model builder.
fun Route.dashboardRoutes(transactionStore: TransactionRepository, categoryStore: CategoryRepository) {
    get("/") {
        val ownerId = call.requireUserId()
        val transactions = transactionStore.all(ownerId)
        val categories = categoryStore.all(ownerId)
        val today = LocalDate.now()
        val period = resolveDashboardPeriod(call.request.queryParameters, today)
        val model = dashboardPageModel(transactions, categories, today, period) + call.currentUserModel()
        call.respond(FreeMarkerContent("dashboard.ftl", model))
    }
}
