package com.budgeter

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray

// The original USD amount has nowhere else to live - Transaction has no
// currency field (see AccountType's doc comment) - so it's kept in the
// description rather than silently discarded, in case the conversion rate
// needs double-checking later.
private fun ParsedTransaction.convertedToCad(conversionRate: Double): ParsedTransaction {
    val usdAmount = amount
    return copy(
        amount = usdAmount * conversionRate,
        description = "$description (USD ${"%.2f".format(usdAmount)} @ $conversionRate)"
    )
}

fun Route.transactionRoutes(transactionStore: TransactionRepository) {
    get("/transactions") {
        val ownerId = call.requireUserId()
        val transactions = transactionStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = transactionsPageModel(transactions, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("transactions.ftl", model))
    }

    get("/transactions/duplicates") {
        val ownerId = call.requireUserId()
        val transactions = transactionStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val model = duplicateGroupsPageModel(transactions) + mapOf("message" to message) + call.currentUserModel()
        call.respond(FreeMarkerContent("transaction-duplicates.ftl", model))
    }

    post("/transactions/import") {
        val ownerId = call.requireUserId()

        var csvText: String? = null
        var accountTypeRaw: String? = null
        var conversionRateRaw: String? = null
        call.receiveMultipart().forEachPart { part ->
            when {
                part is PartData.FileItem && csvText == null -> csvText = part.provider().toByteArray().toString(Charsets.UTF_8)
                part is PartData.FormItem && part.name == "accountType" -> accountTypeRaw = part.value
                part is PartData.FormItem && part.name == "conversionRate" -> conversionRateRaw = part.value
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

        // USD_BANK is the one account type whose CSV amounts aren't already
        // in CAD - see AccountType's doc comment. There's no FX-rate
        // lookup anywhere in this app, so the rate is a one-off value the
        // user supplies per upload (a flat rate applied to every row in
        // the file, not a per-transaction/per-day rate - an accepted
        // approximation given this app's existing amount precision, see
        // Transaction.amount's own doc comment) rather than a stored,
        // reusable setting.
        val conversionRate = if (accountType == AccountType.USD_BANK) {
            conversionRateRaw?.toDoubleOrNull()?.takeIf { it > 0 } ?: run {
                call.respondRedirect("/transactions?error=${"USD Bank requires a conversion rate (CAD per USD)".encodeURLQueryComponent()}")
                return@post
            }
        } else {
            null
        }

        val result = CsvTransactionParser.parse(csvText!!, accountType)
        val convertedTransactions = if (conversionRate != null) {
            result.transactions.map { it.convertedToCad(conversionRate) }
        } else {
            result.transactions
        }
        val importResult = transactionStore.addAll(ownerId, sha256Hex(csvText!!), convertedTransactions)

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

    post("/transactions/{id}/delete") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        transactionStore.delete(ownerId, id)
        // Deleting from the duplicates review list (?returnTo=duplicates)
        // sends the user back there rather than to the full /transactions
        // list, so reviewing the rest of a group doesn't mean re-finding
        // your place after every delete.
        val returnPath = if (call.request.queryParameters["returnTo"] == "duplicates") "/transactions/duplicates" else "/transactions"
        call.respondRedirect("$returnPath?message=${"Transaction deleted".encodeURLQueryComponent()}")
    }
}
