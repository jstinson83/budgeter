package com.budgeter

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class TransactionRoutesTest {
    @Test
    fun testTransactionsPageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/transactions")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testEmptyStateBeforeAnyImport() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/transactions") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No transactions yet"))
    }

    @Test
    fun testImportingCsvStoresTransactionsAndShowsThemOnTheListPage() = testApplication {
        testModule()
        val client = signInFakeUser()

        val csv = "2026-01-15,Starbucks,-4.75\n2026-01-16,Payroll,2500.00"
        val importResponse = client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("file", csv.toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        assertEquals(HttpStatusCode.Found, importResponse.status)
        val redirectLocation = importResponse.headers[HttpHeaders.Location]
        assertNotNull(redirectLocation)
        assertTrue(redirectLocation.startsWith("/transactions?message="))

        val listResponse = client.get(redirectLocation)
        val body = listResponse.bodyAsText()
        assertTrue(body.contains("Starbucks"))
        assertTrue(body.contains("Payroll"))
        assertTrue(body.contains("Imported 2 transaction(s)"))
    }

    @Test
    fun testImportReportsSkippedRows() = testApplication {
        testModule()
        val client = signInFakeUser()

        val csv = "2026-01-15,Starbucks,-4.75\nnot-a-date,Broken,1.00"
        val importResponse = client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("file", csv.toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        val redirectLocation = importResponse.headers[HttpHeaders.Location]
        assertNotNull(redirectLocation)
        val listResponse = client.get(redirectLocation)
        assertTrue(listResponse.bodyAsText().contains("skipped 1 row(s) with errors"))
    }

    @Test
    fun testImportWithNoFileRedirectsWithError() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {}
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers[HttpHeaders.Location]!!.startsWith("/transactions?error="))
    }

    @Test
    fun testTransactionsAreScopedPerAccount() {
        // Same shape as foodie's testDataIsIsolatedPerAccount: two
        // testApplication instances sharing one fake repository, signed in
        // as two different fake identities.
        val transactionRepo = FakeTransactionRepository()

        testApplication {
            testModule(transactionStore = transactionRepo, oauthClient = fakeGoogleOAuthClient("owner-sub", "owner@example.com", "Owner"))
            val client = signInFakeUser()
            client.submitFormWithBinaryData(
                url = "/transactions/import",
                formData = formData {
                    append("file", "2026-01-15,Starbucks,-4.75".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "text/csv")
                        append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                    })
                }
            )
        }

        testApplication {
            testModule(transactionStore = transactionRepo, oauthClient = fakeGoogleOAuthClient("other-sub", "other@example.com", "Other"))
            val client = signInFakeUser()

            val otherListResponse = client.get("/transactions") { header(HttpHeaders.Accept, "text/html") }
            assertTrue(otherListResponse.bodyAsText().contains("No transactions yet"))
        }
    }
}
