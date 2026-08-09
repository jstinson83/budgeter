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

    private suspend fun HttpClient.importCsv(csv: String, accountType: String? = null): String {
        val response = submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                if (accountType != null) append("accountType", accountType)
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
        assertTrue(beforeCategorize.bodyAsText().contains("Process 2 new transaction(s)"))

        val categorizeResponse = client.post("/analysis/categorize")
        val redirect = categorizeResponse.headers[HttpHeaders.Location]!!
        assertEquals("Categorized 2 of 2 transaction(s)", Url(redirect).parameters["message"])

        val afterCategorize = client.get(redirect)
        val body = afterCategorize.bodyAsText()
        assertTrue(body.contains("Groceries"))
        // The "process" button (distinct from the "Categorized ..." success
        // banner also on this page) is gone now that nothing's left pending.
        assertFalse(body.contains("new transaction(s)"))
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
    fun testMatchesCreditCardPaymentAsATransferAndExcludesItFromAnalysis() = testApplication {
        val categorizer = FakeTransactionCategorizer(TransactionCategory.GROCERIES)
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // The bank's "paid the card" leg and the card's "received a
        // payment" leg of the same real-world transfer - same amount,
        // close dates, the two fixed description markers TransferMatcher
        // looks for.
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")
        client.importCsv("$today,PAYMENT - THANK YOU,,200.00,300.00", accountType = "CREDIT_CARD")

        val categorizeResponse = client.post("/analysis/categorize")
        val redirect = categorizeResponse.headers[HttpHeaders.Location]!!
        assertEquals("Matched 1 transfer(s)", Url(redirect).parameters["message"])
        // Neither leg went to the (fake) Gemini categorizer.
        assertEquals(0, categorizer.callCount)

        val page = client.get(redirect)
        val body = page.bodyAsText()
        // Excluded from analysis entirely - no "Transfer" bucket, and no
        // leftover "process" button since both rows are now categorized.
        assertFalse(body.contains("Transfer"))
        assertTrue(body.contains("No transactions in this period"))
    }

    @Test
    fun testDoesNotMatchATransferWhenOnlyOneLegIsPresent() = testApplication {
        val categorizer = FakeTransactionCategorizer(TransactionCategory.GROCERIES)
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // Only the bank side was imported - nothing on the credit card side
        // to pair it with, so it should fall through to ordinary
        // categorization rather than being silently dropped.
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")

        val categorizeResponse = client.post("/analysis/categorize")
        val redirect = categorizeResponse.headers[HttpHeaders.Location]!!
        assertEquals("Categorized 1 of 1 transaction(s)", Url(redirect).parameters["message"])
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
