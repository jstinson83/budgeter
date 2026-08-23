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
    fun testUploadingAWiderStatementSkipsThePreviouslyImportedOverlap() = testApplication {
        testModule()
        val client = signInFakeUser()

        // First upload covers Jan-Jun. A later export of the same account
        // (a different file - different fileHash - so the fingerprint-based
        // dedup alone wouldn't catch this) covers Jan-Aug: it re-sends the
        // same two Jan-Jun rows verbatim, plus two genuinely new rows.
        val janToJune = "2026-01-15,Starbucks,4.75,,995.25\n2026-06-01,Payroll,,2500.00,3495.25"
        val janToAugust = "2026-01-15,Starbucks,4.75,,995.25\n2026-06-01,Payroll,,2500.00,3495.25\n" +
            "2026-07-04,Fireworks Store,25.00,,3470.25\n2026-08-01,Payroll,,2500.00,5970.25"

        suspend fun import(csv: String, filename: String): String {
            val response = client.submitFormWithBinaryData(
                url = "/transactions/import",
                formData = formData {
                    append("file", csv.toByteArray(), Headers.build {
                        append(HttpHeaders.ContentType, "text/csv")
                        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    })
                }
            )
            return response.headers[HttpHeaders.Location]!!
        }

        import(janToJune, "jan-to-jun.csv")
        val secondRedirect = import(janToAugust, "jan-to-aug.csv")

        val listResponse = client.get(secondRedirect)
        val body = listResponse.bodyAsText()
        assertTrue(body.contains("Imported 2 transaction(s), skipped 2 duplicate(s)"))
        assertEquals(1, Regex("Starbucks").findAll(body).count())
        assertEquals(1, Regex("Fireworks Store").findAll(body).count())
        assertEquals(2, Regex("Payroll").findAll(body).count())
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
        // Scoped to the flash banner, not the whole page - /transactions
        // also links to the "Review duplicates" page (transactions.ftl),
        // whose label itself contains the word "duplicate".
        val banner = body.substringAfter("banner-success\">").substringBefore("</p>")
        assertFalse(banner.contains("duplicate"))
        assertEquals(2, Regex("Starbucks").findAll(body).count())
    }

    @Test
    fun testDeletingATransactionRemovesItFromTheList() = testApplication {
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
        val afterImport = client.get(importResponse.headers[HttpHeaders.Location]!!).bodyAsText()
        val starbucksRow = afterImport.substringAfter("Starbucks").substringBefore("</div>")
        val transactionId = Regex("/transactions/([^/\"]+)/delete").find(starbucksRow)?.groupValues?.get(1)
        assertNotNull(transactionId)

        val deleteResponse = client.post("/transactions/$transactionId/delete")
        assertEquals(HttpStatusCode.Found, deleteResponse.status)

        val listResponse = client.get(deleteResponse.headers[HttpHeaders.Location]!!)
        val body = listResponse.bodyAsText()
        assertTrue(body.contains("Transaction deleted"))
        assertFalse(body.contains("Starbucks"))
        assertTrue(body.contains("Payroll"))
    }

    @Test
    fun testDuplicatesReviewPageIsEmptyWhenThereAreNoOverlappingImports() = testApplication {
        testModule()
        val client = signInFakeUser()

        // Two identical-content rows in the *same* file are never a
        // reviewable duplicate - they're two genuinely separate charges
        // (see testIdenticalRowsInTheSameFileAreNotTreatedAsDuplicatesOfEachOther).
        val csv = "2026-01-15,Starbucks,4.75,,995.25\n2026-01-15,Starbucks,4.75,,995.25"
        client.submitFormWithBinaryData(
            url = "/transactions/import",
            formData = formData {
                append("file", csv.toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/csv")
                    append(HttpHeaders.ContentDisposition, "filename=\"statement.csv\"")
                })
            }
        )

        val body = client.get("/transactions/duplicates") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(body.contains("No duplicate transactions found"))
    }

    @Test
    fun testDuplicatesReviewPageSurfacesAllMembersWhenAGenuineRepeatIsMixedWithARealDuplicate() = testApplication {
        val fakeTransactionRepository = FakeTransactionRepository()
        testModule(transactionStore = fakeTransactionRepository)
        val client = signInFakeUser()

        // The subtle case: file "file-a" legitimately contains two real
        // same-day $4.75 Starbucks charges (both correctly kept - see
        // testIdenticalRowsInTheSameFileAreNotTreatedAsDuplicatesOfEachOther).
        // A later overlapping re-export ("file-b") also contains a matching
        // row that, pre-fix, got stored as a third copy instead of being
        // caught as a duplicate of one of file-a's two. The review page
        // must not silently collapse this group down to one (that would
        // destroy a real transaction) - it should surface all three so a
        // human can tell which one is the actual duplicate.
        fakeTransactionRepository.seedRaw(Transaction("real-1", "test-sub", AccountType.BANK, java.time.LocalDate.parse("2026-01-15"), "Starbucks", -4.75, fileHash = "file-a"))
        fakeTransactionRepository.seedRaw(Transaction("real-2", "test-sub", AccountType.BANK, java.time.LocalDate.parse("2026-01-15"), "Starbucks", -4.75, fileHash = "file-a"))
        fakeTransactionRepository.seedRaw(Transaction("stray-dup", "test-sub", AccountType.BANK, java.time.LocalDate.parse("2026-01-15"), "Starbucks", -4.75, fileHash = "file-b"))

        val body = client.get("/transactions/duplicates") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertEquals(3, Regex("Starbucks").findAll(body).count())
    }

    @Test
    fun testDuplicatesReviewPageListsAndDeletesACrossImportDuplicate() = testApplication {
        val fakeTransactionRepository = FakeTransactionRepository()
        testModule(transactionStore = fakeTransactionRepository)
        val client = signInFakeUser()

        // Simulates a duplicate that predates cross-file overlap dedup: two
        // rows with identical content but distinct ids/fingerprints AND
        // distinct fileHash values, as if they'd come from two separate
        // statement uploads before withoutContentOverlap() existed to
        // catch it. addAll() itself can no longer produce this (it's
        // covered by testUploadingAWiderStatementSkipsThePreviouslyImportedOverlap),
        // so this seeds the fake store directly to stand in for that
        // legacy data.
        fakeTransactionRepository.seedRaw(Transaction("dup-1", "test-sub", AccountType.BANK, java.time.LocalDate.parse("2026-01-15"), "Starbucks", -4.75, fileHash = "file-a"))
        fakeTransactionRepository.seedRaw(Transaction("dup-2", "test-sub", AccountType.BANK, java.time.LocalDate.parse("2026-01-15"), "Starbucks", -4.75, fileHash = "file-b"))
        fakeTransactionRepository.seedRaw(Transaction("solo-1", "test-sub", AccountType.BANK, java.time.LocalDate.parse("2026-01-20"), "Payroll", 2500.00, fileHash = "file-a"))

        val reviewBody = client.get("/transactions/duplicates") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertEquals(2, Regex("Starbucks").findAll(reviewBody).count())
        assertFalse(reviewBody.contains("Payroll"))

        val starbucksRow = reviewBody.substringAfter("Starbucks").substringBefore("</div>")
        val transactionId = Regex("/transactions/([^/\"]+)/delete\\?returnTo=duplicates").find(starbucksRow)?.groupValues?.get(1)
        assertNotNull(transactionId)

        val deleteResponse = client.post("/transactions/$transactionId/delete?returnTo=duplicates")
        val afterDelete = client.get(deleteResponse.headers[HttpHeaders.Location]!!).bodyAsText()
        assertTrue(afterDelete.contains("No duplicate transactions found"))

        val listBody = client.get("/transactions") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertEquals(1, Regex("Starbucks").findAll(listBody).count())
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
