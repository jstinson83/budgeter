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
        // TRANSFER excluded here, not just left out of a bucket - it's
        // money moving between the user's own accounts, not spending or
        // income, so it shouldn't appear in analysis at all.
        val periodTransactions = all.filter { !it.date.isBefore(since) && it.category != TransactionCategory.TRANSFER }
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

        // Transfer matching runs first and is claimed (updateCategories)
        // before Gemini ever sees these rows - otherwise it'd burn a
        // request trying to categorize "TFR-TO C/C" / "PAYMENT - THANK YOU"
        // rows as ordinary spending, and could mis-bucket the credit-card
        // leg as INCOME.
        val transferMatches = TransferMatcher.match(pending)
        if (transferMatches.isNotEmpty()) {
            transactionStore.updateCategories(ownerId, transferMatches)
        }

        val remaining = pending.filterNot { it.id in transferMatches }
        val categorized = if (remaining.isEmpty()) emptyMap() else try {
            transactionCategorizer.categorize(remaining)
        } catch (e: Exception) {
            call.respondRedirect("/analysis?error=${"Categorization failed: ${e.message}".encodeURLQueryComponent()}")
            return@post
        }
        if (categorized.isNotEmpty()) {
            transactionStore.updateCategories(ownerId, categorized)
        }

        // transferMatches holds both legs of every matched pair, so its
        // size is always even - divide by 2 for the pair count. Comma +
        // lowercase continuation mirrors TransactionRoutes' "Imported N,
        // skipped M" message style.
        val message = buildString {
            if (transferMatches.isNotEmpty()) append("Matched ${transferMatches.size / 2} transfer(s)")
            if (remaining.isNotEmpty()) {
                if (transferMatches.isNotEmpty()) append(", categorized ${categorized.size} of ${remaining.size} transaction(s)")
                else append("Categorized ${categorized.size} of ${remaining.size} transaction(s)")
            }
        }
        call.respondRedirect("/analysis?message=${message.encodeURLQueryComponent()}")
    }
}
