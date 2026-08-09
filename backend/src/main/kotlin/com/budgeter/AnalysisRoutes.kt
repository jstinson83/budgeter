package com.budgeter

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.time.LocalDate

private val SUPPORTED_PERIODS = setOf("week", "month", "year")

private fun periodStart(period: String): LocalDate = when (period) {
    "week" -> LocalDate.now().minusWeeks(1)
    "year" -> LocalDate.now().minusYears(1)
    else -> LocalDate.now().minusMonths(1)
}

fun Route.analysisRoutes(transactionStore: TransactionRepository, transactionCategorizer: TransactionCategorizer) {
    get("/analysis") {
        val ownerId = call.requireUserId()
        val period = call.request.queryParameters["period"]?.takeIf { it in SUPPORTED_PERIODS } ?: "month"

        val all = transactionStore.all(ownerId)
        val since = periodStart(period)
        val periodTransactions = all.filter { !it.date.isBefore(since) }
        val uncategorizedCount = all.count { it.category == null }

        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = analysisPageModel(periodTransactions, period, uncategorizedCount, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("analysis.ftl", model))
    }

    post("/analysis/categorize") {
        val ownerId = call.requireUserId()
        val pending = transactionStore.uncategorized(ownerId)
        if (pending.isEmpty()) {
            call.respondRedirect("/analysis?message=${"No new transactions to categorize".encodeURLQueryComponent()}")
            return@post
        }

        val categorized = try {
            transactionCategorizer.categorize(pending)
        } catch (e: Exception) {
            call.respondRedirect("/analysis?error=${"Categorization failed: ${e.message}".encodeURLQueryComponent()}")
            return@post
        }
        transactionStore.updateCategories(ownerId, categorized)

        val message = "Categorized ${categorized.size} of ${pending.size} transaction(s)"
        call.respondRedirect("/analysis?message=${message.encodeURLQueryComponent()}")
    }
}
