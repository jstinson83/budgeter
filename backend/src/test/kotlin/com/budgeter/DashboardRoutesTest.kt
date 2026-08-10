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
