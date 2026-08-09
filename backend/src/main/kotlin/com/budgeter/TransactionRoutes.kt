package com.budgeter

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray

fun Route.transactionRoutes(transactionStore: TransactionRepository) {
    get("/transactions") {
        val ownerId = call.requireUserId()
        val transactions = transactionStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = transactionsPageModel(transactions, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("transactions.ftl", model))
    }

    post("/transactions/import") {
        val ownerId = call.requireUserId()

        var csvText: String? = null
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && csvText == null) {
                csvText = part.provider().toByteArray().toString(Charsets.UTF_8)
            }
            part.dispose()
        }

        if (csvText.isNullOrBlank()) {
            call.respondRedirect("/transactions?error=${"No CSV file uploaded".encodeURLQueryComponent()}")
            return@post
        }

        val result = CsvTransactionParser.parse(csvText!!)
        val importResult = transactionStore.addAll(ownerId, sha256Hex(csvText!!), result.transactions)

        val message = buildString {
            append("Imported ${importResult.stored.size} transaction(s)")
            if (importResult.duplicateCount > 0) {
                append(", skipped ${importResult.duplicateCount} duplicate(s)")
            }
            if (result.errors.isNotEmpty()) {
                append(", skipped ${result.errors.size} row(s) with errors")
            }
        }
        call.respondRedirect("/transactions?message=${message.encodeURLQueryComponent()}")
    }
}
