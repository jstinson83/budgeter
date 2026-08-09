package com.budgeter

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class AnalysisRoutesTest {
    @Test
    fun testAnalysisPageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/analysis")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testEmptyStateBeforeAnyTransactions() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No transactions in this period"))
    }

    private suspend fun HttpClient.importCsv(csv: String): String {
        val response = submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("file", csv.toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )
        return response.headers[HttpHeaders.Location]!!
    }

    @Test
    fun testCategorizeButtonAppearsForUncategorizedTransactionsAndGroupsTotalsAfterCategorizing() = testApplication {
        val categorizer = FakeTransactionCategorizer(TransactionCategory.GROCERIES)
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        client.importCsv("$today,Metro Grocery,42.10,,957.90\n$today,Payroll,,2500.00,3457.90")

        val beforeCategorize = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(beforeCategorize.bodyAsText().contains("Categorize 2 new transaction(s) with Gemini"))

        val categorizeResponse = client.post("/analysis/categorize")
        val redirect = categorizeResponse.headers[HttpHeaders.Location]!!
        assertEquals("Categorized 2 of 2 transaction(s)", Url(redirect).parameters["message"])

        val afterCategorize = client.get(redirect)
        val body = afterCategorize.bodyAsText()
        assertTrue(body.contains("Groceries"))
        // The "categorize" button (distinct from the "Categorized ..." success
        // banner also on this page) is gone now that nothing's left pending.
        assertFalse(body.contains("with Gemini"))
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testCategorizingTwiceDoesNotReanalyzeAlreadyCategorizedTransactions() = testApplication {
        val categorizer = FakeTransactionCategorizer(TransactionCategory.GROCERIES)
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("${java.time.LocalDate.now()},Metro Grocery,42.10,,957.90")
        client.post("/analysis/categorize")
        assertEquals(1, categorizer.callCount)

        val secondAttempt = client.post("/analysis/categorize")
        val redirect = secondAttempt.headers[HttpHeaders.Location]!!
        assertEquals("No new transactions to categorize", Url(redirect).parameters["message"])
        // No second call to the categorizer - nothing left to categorize.
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testPeriodFilterExcludesOlderTransactions() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        val twoYearsAgo = today.minusYears(2)
        client.importCsv("$today,Recent Purchase,10.00,,990.00\n$twoYearsAgo,Old Purchase,20.00,,1010.00")

        val weekView = client.get("/analysis?period=week") { header(HttpHeaders.Accept, "text/html") }
        val body = weekView.bodyAsText()
        // Only the recent transaction falls within the last-week window, so
        // the "Uncategorized" bucket total should reflect just its -10.00,
        // not both transactions combined (-30.00).
        assertTrue(body.contains("-10.00"))
        assertFalse(body.contains("-30.00"))
    }

    @Test
    fun testCategorizationFailureShowsErrorBanner() = testApplication {
        val failingCategorizer = object : TransactionCategorizer {
            override suspend fun categorize(transactions: List<Transaction>): Map<String, TransactionCategory> =
                throw IllegalStateException("GEMINI_API_KEY is not set")
        }
        testModule(transactionCategorizer = failingCategorizer)
        val client = signInFakeUser()

        client.importCsv("${java.time.LocalDate.now()},Metro Grocery,42.10,,957.90")
        val response = client.post("/analysis/categorize")
        val redirect = response.headers[HttpHeaders.Location]!!
        assertTrue(redirect.startsWith("/analysis?error="))

        val page = client.get(redirect)
        assertTrue(page.bodyAsText().contains("Categorization failed"))
    }
}
