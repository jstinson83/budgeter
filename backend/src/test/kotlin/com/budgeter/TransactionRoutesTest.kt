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

        val csv = "2026-01-15,Starbucks,4.75,,995.25\n2026-01-16,Payroll,,2500.00,3495.25"
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

        val csv = "2026-01-15,Starbucks,4.75,,995.25\nnot-a-date,Broken,1.00,,999.00"
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
    fun testReimportingSameCsvSkipsDuplicates() = testApplication {
        testModule()
        val client = signInFakeUser()

        val csv = "2026-01-15,Starbucks,4.75,,995.25\n2026-01-16,Payroll,,2500.00,3495.25"
        val importCsv: suspend () -> String = {
            val response = client.submitFormWithBinaryData(
                url = "/transactions/import",
                formData = formData {
                    append("file", csv.toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "text/csv")
                        append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                    })
                }
            )
            response.headers[HttpHeaders.Location]!!
        }

        importCsv()
        val secondRedirect = importCsv()

        val listResponse = client.get(secondRedirect)
        val body = listResponse.bodyAsText()
        assertTrue(body.contains("Imported 0 transaction(s), skipped 2 duplicate(s)"))
        assertEquals(1, Regex("Starbucks").findAll(body).count())
        assertEquals(1, Regex("Payroll").findAll(body).count())
    }

    @Test
    fun testIdenticalRowsInTheSameFileAreNotTreatedAsDuplicatesOfEachOther() = testApplication {
        testModule()
        val client = signInFakeUser()

        // Two genuinely separate $4.75 Starbucks charges the same day -
        // identical content, different rows of the same upload. Dedup is
        // keyed on (file, row position), not row content, so both must be
        // kept.
        val csv = "2026-01-15,Starbucks,4.75,,995.25\n2026-01-15,Starbucks,4.75,,995.25"
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
        val body = listResponse.bodyAsText()
        assertTrue(body.contains("Imported 2 transaction(s)"))
        assertFalse(body.contains("duplicate"))
        assertEquals(2, Regex("Starbucks").findAll(body).count())
    }

    @Test
    fun testImportDefaultsToBankAccountTypeWhenFieldIsAbsent() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("file", "2026-01-15,Starbucks,4.75,,995.25".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        val listResponse = client.get("/transactions") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(listResponse.bodyAsText().contains("Bank"))
    }

    @Test
    fun testImportAcceptsExplicitCreditCardAccountType() = testApplication {
        testModule()
        val client = signInFakeUser()

        val importResponse = client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("accountType", "CREDIT_CARD")
                append("file", "2026-01-15,PAYMENT - THANK YOU,,200.00,300.00".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        val redirectLocation = importResponse.headers[HttpHeaders.Location]
        assertNotNull(redirectLocation)
        val listResponse = client.get(redirectLocation)
        assertTrue(listResponse.bodyAsText().contains("Credit Card"))
    }

    @Test
    fun testImportAcceptsExplicitLocAccountType() = testApplication {
        testModule()
        val client = signInFakeUser()

        val importResponse = client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("accountType", "LOC")
                append("file", "2026-01-15,INTEREST,12.34,,300.00".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        val redirectLocation = importResponse.headers[HttpHeaders.Location]
        assertNotNull(redirectLocation)
        val listResponse = client.get(redirectLocation)
        assertTrue(listResponse.bodyAsText().contains("Line of Credit"))
    }

    @Test
    fun testImportRejectsAnInvalidAccountType() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("accountType", "SAVINGS")
                append("file", "2026-01-15,Starbucks,4.75,,995.25".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        assertEquals(HttpStatusCode.Found, response.status)
        val redirectLocation = response.headers[HttpHeaders.Location]
        assertNotNull(redirectLocation)
        assertTrue(redirectLocation.startsWith("/transactions?error="))
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
    fun testDeleteAllClearsTransactionsForCallingOwner() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("file", "2026-01-15,Starbucks,4.75,,995.25".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        val deleteResponse = client.post("/transactions/delete-all")
        assertEquals(HttpStatusCode.Found, deleteResponse.status)
        val redirectLocation = deleteResponse.headers[HttpHeaders.Location]
        assertNotNull(redirectLocation)

        val listResponse = client.get(redirectLocation)
        assertTrue(listResponse.bodyAsText().contains("No transactions yet"))
    }

    @Test
    fun testDeleteAllDoesNotAffectOtherAccounts() {
        // Same shared-repo, two-identity shape as
        // testTransactionsAreScopedPerAccount, but exercising deleteAll's
        // ownerId scoping specifically rather than addAll/all's.
        val transactionRepo = FakeTransactionRepository()

        testApplication {
            testModule(transactionStore = transactionRepo, oauthClient = fakeGoogleOAuthClient("owner-sub", "owner@example.com", "Owner"))
            val client = signInFakeUser()
            client.submitFormWithBinaryData(
                url = "/transactions/import",
                formData = formData {
                    append("file", "2026-01-15,Bagel Shop,3.50,,500.00".toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "text/csv")
                        append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                    })
                }
            )
        }

        testApplication {
            testModule(transactionStore = transactionRepo, oauthClient = fakeGoogleOAuthClient("other-sub", "other@example.com", "Other"))
            val client = signInFakeUser()
            client.post("/transactions/delete-all")
        }

        testApplication {
            testModule(transactionStore = transactionRepo, oauthClient = fakeGoogleOAuthClient("owner-sub", "owner@example.com", "Owner"))
            val client = signInFakeUser()
            val listResponse = client.get("/transactions") { header(HttpHeaders.Accept, "text/html") }
            assertTrue(listResponse.bodyAsText().contains("Bagel Shop"))
        }
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
                    append("file", "2026-01-15,Starbucks,4.75,,995.25".toByteArray(), Headers.build {
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
