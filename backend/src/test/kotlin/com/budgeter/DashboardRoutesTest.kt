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
    fun testShowsNetPositionFromLatestCapturedBalances() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.importCsv("2026-06-01,Groceries,50.00,,950.00\n2026-06-10,Payroll,,200.00,1150.00", accountType = "BANK")
        client.importCsv("2026-06-05,Amazon,25.00,,325.00", accountType = "CREDIT_CARD")

        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        assertTrue(body.contains("1150.00"))
        assertTrue(body.contains("325.00"))
        // Net position: 1150.00 bank asset minus 325.00 credit-card liability.
        assertTrue(body.contains("825.00"))
    }

    @Test
    fun testFlagsAPossibleCoverageGap() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.importCsv("2026-01-01,January Charge,10.00,,990.00\n2026-04-01,April Charge,10.00,,980.00", accountType = "BANK")

        val response = client.get("/") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(response.bodyAsText().contains("Possible gap"))
    }
}
