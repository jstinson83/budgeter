package com.budgeter

import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// New landing page ("/") - replaces the old bare redirect to /analysis. See
// DashboardPage.kt for the actual summary logic; this route just fetches the
// owner's transactions/categories once and hands them to the model builder.
fun Route.dashboardRoutes(transactionStore: TransactionRepository, categoryStore: CategoryRepository) {
    get("/") {
        val ownerId = call.requireUserId()
        val transactions = transactionStore.all(ownerId)
        val categories = categoryStore.all(ownerId)
        val model = dashboardPageModel(transactions, categories) + call.currentUserModel()
        call.respond(FreeMarkerContent("dashboard.ftl", model))
    }
}
