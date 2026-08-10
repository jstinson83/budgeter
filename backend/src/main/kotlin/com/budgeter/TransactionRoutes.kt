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
        var accountTypeRaw: String? = null
        call.receiveMultipart().forEachPart { part ->
            when {
                part is PartData.FileItem && csvText == null -> csvText = part.provider().toByteArray().toString(Charsets.UTF_8)
                part is PartData.FormItem && part.name == "accountType" -> accountTypeRaw = part.value
            }
            part.dispose()
        }

        if (csvText.isNullOrBlank()) {
            call.respondRedirect("/transactions?error=${"No CSV file uploaded".encodeURLQueryComponent()}")
            return@post
        }

        // Defaults to BANK when the form field is absent entirely (e.g. an
        // API call bypassing the upload form) - the upload form itself
        // always submits a checked radio value, defaulted to Bank, so this
        // only matters for callers that skip the form. A present-but-junk
        // value is a real error, not silently coerced.
        val accountType = if (accountTypeRaw == null) {
            AccountType.BANK
        } else {
            runCatching { AccountType.valueOf(accountTypeRaw!!) }.getOrNull() ?: run {
                call.respondRedirect("/transactions?error=${"Invalid account type: $accountTypeRaw".encodeURLQueryComponent()}")
                return@post
            }
        }

        val result = CsvTransactionParser.parse(csvText!!, accountType)
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

    post("/transactions/delete-all") {
        val ownerId = call.requireUserId()
        transactionStore.deleteAll(ownerId)
        call.respondRedirect("/transactions?message=${"All transactions deleted".encodeURLQueryComponent()}")
    }
}
