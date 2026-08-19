package com.budgeter

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class DashboardRoutesTest {
    @Test
    fun testDashboardRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testEmptyStateBeforeAnyTransactions() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No transactions yet"))
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
    fun testFlagsAPossibleCoverageGap() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.importCsv("2026-01-01,January Charge,10.00,,990.00\n2026-04-01,April Charge,10.00,,980.00", accountType = "BANK")

        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(response.bodyAsText().contains("Possible gap"))
    }

    @Test
    fun testShowsATotalSummarizingTheMoneyInOutTrend() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        client.importCsv("$today,Payroll,,2500.00,3457.90\n$today,Groceries,42.10,,3415.80", accountType = "BANK")

        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        // -42.10 + 2500.00 = 2457.90
        assertTrue(body.contains("Last 3 months"))
        assertTrue(body.contains("+2457.90"))
    }

    @Test
    fun testSixMonthPeriodSelectorExpandsTheTrendWindow() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        val fiveMonthsAgo = today.minusMonths(5)
        client.importCsv(
            "$fiveMonthsAgo,Old Groceries,50.00,,3457.90\n$today,Groceries,42.10,,3415.80",
            accountType = "BANK"
        )

        val response = client.get("/?period=6m") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        assertTrue(body.contains("Last 6 months"))
        assertTrue(body.contains("id=\"period-6m\" checked")) // the 6-month radio should render pre-selected
    }

    @Test
    fun testCustomPeriodShowsTheResolvedDateRangeLabel() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        client.importCsv("$today,Groceries,42.10,,3415.80", accountType = "BANK")

        val response = client.get("/?period=custom&start=2020-01-01&end=2020-03-31") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        assertTrue(body.contains("Jan 2020"))
        assertTrue(body.contains("Mar 2020"))
    }

    @Test
    fun testCustomPeriodWithMissingDatesFallsBackToTheThreeMonthDefault() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        client.importCsv("$today,Groceries,42.10,,3415.80", accountType = "BANK")

        val response = client.get("/?period=custom") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        assertTrue(body.contains("Last 3 months"))
    }

    @Test
    fun testExportRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/export/transactions")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testExportOnlyIncludesTransactionsWithinTheSelectedPeriod() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        val eightMonthsAgo = today.minusMonths(8)
        client.importCsv(
            "$eightMonthsAgo,Old Groceries,50.00,,3457.90\n$today,Recent Groceries,42.10,,3415.80",
            accountType = "BANK"
        )

        // Default period is 3 months - the 8-month-old row should be excluded.
        val response = client.get("/export/transactions")
        val body = response.bodyAsText()
        assertTrue(body.contains("Recent Groceries"))
        assertFalse(body.contains("Old Groceries"))
    }

    @Test
    fun testExportRespectsTheSixMonthPeriod() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        val fiveMonthsAgo = today.minusMonths(5)
        client.importCsv(
            "$fiveMonthsAgo,Old Groceries,50.00,,3457.90\n$today,Recent Groceries,42.10,,3415.80",
            accountType = "BANK"
        )

        val response = client.get("/export/transactions?period=6m")
        val body = response.bodyAsText()
        assertTrue(body.contains("Old Groceries"))
        assertTrue(body.contains("Recent Groceries"))
    }

    @Test
    fun testExportServesACsvAttachmentWithATransferLabelAndACategoryLabel() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // A bank "TFR-TO C/C" line paired with a matching credit-card
        // "PAYMENT - THANK YOU" line is exactly what TransferMatcher pairs
        // as a transfer - see TransferMatcher.kt.
        client.importCsv("$today,TFR-TO C/C,500.00,,1000.00", accountType = "BANK")
        client.importCsv("$today,PAYMENT - THANK YOU,,500.00,500.00", accountType = "CREDIT_CARD")
        // TransferMatcher only runs as part of the categorize pass, which
        // /analysis triggers on load (see CategorizationJob.kt) - the
        // dashboard/export routes don't trigger it themselves.
        client.get("/analysis")

        val response = client.get("/export/transactions")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentDisposition]?.contains("attachment") == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("Date,Account,Description,Amount,Category"))
        assertTrue(body.contains("Transfer"))
    }

    // Net position (total assets/debt combined across accounts) was tried
    // and pulled - see CLAUDE.md's dashboard gotcha - since it silently
    // implies every account is fully linked/imported, which isn't a safe
    // assumption. This just guards against it quietly coming back.
    @Test
    fun testDoesNotShowANetPositionFigure() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.importCsv("2026-06-01,Groceries,50.00,,950.00", accountType = "BANK")

        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertFalse(response.bodyAsText().contains("Net position"))
    }
}
