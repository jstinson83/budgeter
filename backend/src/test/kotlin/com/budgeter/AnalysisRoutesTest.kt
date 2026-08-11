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
    fun testUncategorizedTransactionsAreCategorizedAutomaticallyOnPageLoad() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        client.importCsv("$today,Metro Grocery,42.10,,957.90\n$today,Payroll,,2500.00,3457.90")

        // No button to click - loading the page is what triggers
        // categorization now (see CategorizationJob.kt).
        val result = client.triggerCategorizeAndAwaitResult()
        assertEquals("Categorized 2 of 2 transaction(s)", result.message)

        // The message banner is one-shot (see CategorizationJobManager.
        // consumeTerminal) - a later, unrelated page load shows the
        // now-categorized totals but not a repeat of the same banner.
        val afterCategorize = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(afterCategorize.bodyAsText().contains("Groceries"))
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testNetChangeExcludesInvestmentsFromTheMonthTotal() = testApplication {
        val categorizer = FakeTransactionCategorizer("OTHER")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv(
            "2026-06-05,Metro Grocery,42.10,,957.90\n" +
                "2026-06-10,Payroll,,2500.00,3457.90\n" +
                "2026-06-15,Brokerage Contribution,300.00,,3157.90"
        )
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        client.waitForCategorizationToFinish()

        // Move the investment contribution out of OTHER (where the fake
        // categorizer dumped everything) and into INVESTMENT, the only
        // category the net change figure should ignore.
        val otherPage = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        // Each row is a button that opens its own dialog by id
        // (data-open-dialog="recategorize-{transactionId}") - matching that
        // attribute right before the row's own description text finds the
        // id for this specific transaction rather than just the first one
        // on the page.
        val transactionId = Regex(
            """data-open-dialog="recategorize-([^"]+)">.*?Brokerage Contribution""",
            RegexOption.DOT_MATCHES_ALL
        ).find(otherPage.bodyAsText())!!.groupValues[1]
        client.post("/analysis/recategorize") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("transactionId=$transactionId&category=INVESTMENT&matchType=EXACT&pattern=Brokerage Contribution&fromSlug=other&year=2026&month=6")
        }

        val page = client.get("/analysis?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        val body = page.bodyAsText()
        // Grocery (-42.10) and payroll (+2500.00) net to +2457.90; the $300
        // investment contribution is excluded from this figure even though
        // it still shows as its own category row below.
        assertTrue(body.contains("""<span class="transaction-amount month-summary-amount transaction-amount-positive">+2457.90</span>"""))
        assertTrue(body.contains("Investment"))
        // The investment row itself is a real (negative) outflow, but it
        // shouldn't be styled like an expense - neutral color, not red.
        assertTrue(body.contains("""<span class="transaction-amount transaction-amount-neutral">-300.00</span>"""))
    }

    @Test
    fun testCategorizingTwiceDoesNotReanalyzeAlreadyCategorizedTransactions() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("${java.time.LocalDate.now()},Metro Grocery,42.10,,957.90")
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        client.waitForCategorizationToFinish()
        assertEquals(1, categorizer.callCount)

        // Loading the page again finds nothing new to categorize.
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testMatchesCreditCardPaymentAsATransferAndExcludesItFromAnalysis() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // The bank's "paid the card" leg and the card's "received a
        // payment" leg of the same real-world transfer - same amount,
        // close dates, the two fixed description markers TransferMatcher
        // looks for.
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")
        client.importCsv("$today,PAYMENT - THANK YOU,,200.00,300.00", accountType = "CREDIT_CARD")

        // Transfer matching is synchronous, so its result shows up on the
        // very page load that triggered it (see CategorizationJob.kt).
        val page = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val body = page.bodyAsText()
        assertTrue(body.contains("Marked 2 transaction(s) as transfers"))
        // Neither leg went to the (fake) Gemini categorizer.
        assertEquals(0, categorizer.callCount)
        // Excluded from analysis entirely - no "Transfer" bucket.
        assertFalse(body.contains("Transfer"))
        assertTrue(body.contains("No transactions in this period"))
    }

    @Test
    fun testCategorizesALoneTransferLegImmediatelyEvenWithoutItsOtherLegPresent() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // Only the bank side was imported - nothing on the credit card side
        // to pair it with. Unlike the old pair-matching design, that's not
        // a reason to wait: the description alone is a fixed, unambiguous
        // TD template, so this is marked a transfer immediately rather than
        // falling through to Gemini (see TransferMatcher.kt).
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")

        val page = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val body = page.bodyAsText()
        assertTrue(body.contains("Marked 1 transaction(s) as transfers"))
        assertEquals(0, categorizer.callCount)
        assertFalse(body.contains("Transfer"))
        assertTrue(body.contains("No transactions in this period"))
    }

    @Test
    fun testEachLegOfATransferUploadedInSeparateSessionsIsCategorizedOnItsOwnUpload() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // The bank leg is uploaded and viewed first - marked a transfer
        // immediately on its own, with no credit-card leg present yet.
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")
        val firstPage = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(firstPage.bodyAsText().contains("Marked 1 transaction(s) as transfers"))
        assertEquals(0, categorizer.callCount)

        // The credit-card leg shows up in a later, separate upload session -
        // also marked a transfer immediately on its own, independent of the
        // bank leg uploaded earlier.
        client.importCsv("$today,PAYMENT - THANK YOU,,200.00,300.00", accountType = "CREDIT_CARD")
        val secondPage = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(secondPage.bodyAsText().contains("Marked 1 transaction(s) as transfers"))
        // Never sent to Gemini at any point.
        assertEquals(0, categorizer.callCount)

        val page = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val body = page.bodyAsText()
        assertFalse(body.contains("Transfer"))
        assertTrue(body.contains("No transactions in this period"))
    }

    @Test
    fun testTransferMatchingStillRunsWhileAGeminiJobIsAlreadyInFlightForThisOwner() = testApplication {
        val categorizer = SlowTransactionCategorizer("GROCERIES", delayMs = 300)
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        // An ordinary transaction starts a slow background Gemini job.
        client.importCsv("$today,Metro Grocery,42.10,,957.90")
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") } // starts the job, still RUNNING

        // Both legs of a transfer arrive and get viewed while that job is
        // still in flight - now that categorization is automatic on every
        // page load, this race is routine rather than a rare edge case
        // (see CategorizationJob.kt). It must not stop transfer matching
        // from running.
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")
        client.importCsv("$today,PAYMENT - THANK YOU,,200.00,300.00", accountType = "CREDIT_CARD")
        val whileRunning = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(whileRunning.bodyAsText().contains("Categorizing"))

        client.waitForCategorizationToFinish()

        val page = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val body = page.bodyAsText()
        // The transfer pair was matched despite the concurrent Gemini job -
        // not silently left pending and later mis-sent to Gemini as
        // ordinary spending.
        assertFalse(body.contains("Transfer"))
        assertTrue(body.contains("Groceries"))
        // Only ever one Gemini call - for Metro Grocery. The transfer legs
        // never got sent for analysis.
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testCorrectsAnAlreadyCategorizedTransferPairWithNothingElsePending() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        val transactionStore = FakeTransactionRepository()
        testModule(transactionStore = transactionStore, transactionCategorizer = categorizer)
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        client.importCsv("$today,.....TFR-TO C/C,200.00,,795.00", accountType = "BANK")
        client.importCsv("$today,PAYMENT - THANK YOU,,200.00,300.00", accountType = "CREDIT_CARD")

        // Simulate both legs already having been (wrongly) categorized by
        // an earlier pass - before transfer matching existed, or before a
        // bug in it was fixed - with nothing new left pending to anchor a
        // re-check on. uncategorized() alone would never surface this pair
        // again; only a full-history sweep does (see CategorizationJob.kt).
        val all = transactionStore.all("test-sub")
        val bankLegId = all.first { it.description.contains("TFR-TO C/C") }.id
        val cardLegId = all.first { it.description.contains("PAYMENT - THANK YOU") }.id
        transactionStore.updateCategories("test-sub", mapOf(bankLegId to "OTHER", cardLegId to "INCOME"))

        val page = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val body = page.bodyAsText()
        assertTrue(body.contains("Marked 2 transaction(s) as transfers"))
        assertFalse(body.contains("Transfer"))
        // Never sent to Gemini - both legs already had a category, so
        // neither was ever "pending".
        assertEquals(0, categorizer.callCount)
    }

    @Test
    fun testMonthFilterExcludesTransactionsOutsideTheSelectedCalendarMonth() = testApplication {
        testModule()
        val client = signInFakeUser()

        val today = java.time.LocalDate.now()
        val twoMonthsAgo = today.minusMonths(2)
        client.importCsv("$today,Recent Purchase,10.00,,990.00\n$twoMonthsAgo,Old Purchase,20.00,,1010.00")

        // No year/month params - defaults to the current calendar month.
        val currentMonthView = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val body = currentMonthView.bodyAsText()
        // Only the recent transaction falls within the current month, so
        // the "Uncategorized" bucket total should reflect just its -10.00,
        // not both transactions combined (-30.00).
        assertTrue(body.contains("-10.00"))
        assertFalse(body.contains("-30.00"))
    }

    @Test
    fun testMonthNavLinksToAdjacentMonthsPreservingTheSelectedYear() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/analysis?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        // FreeMarker's HTML output format escapes "&" to "&amp;" inside
        // attribute values, so the rendered href isn't a literal query
        // string.
        assertTrue(body.contains("/analysis?year=2026&amp;month=5"))
        assertTrue(body.contains("/analysis?year=2026&amp;month=7"))
        assertTrue(body.contains("June 2026"))
    }

    @Test
    fun testDrillingIntoACategoryShowsOnlyItsTransactionsForTheSelectedMonth() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("2026-06-15,June Groceries,15.00,,985.00\n2026-07-15,July Groceries,25.00,,960.00")
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        client.waitForCategorizationToFinish()

        val juneCategory = client.get("/analysis/category/groceries?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        val body = juneCategory.bodyAsText()
        assertTrue(body.contains("June Groceries"))
        assertFalse(body.contains("July Groceries"))
        // Back link returns to the same month that was drilled into
        // (rendered value has FreeMarker's HTML-escaped "&amp;").
        assertTrue(body.contains("/analysis?year=2026&amp;month=6"))
        // Same total shown on this category's row on /analysis - here just
        // the one June transaction, not the July one outside this month.
        assertTrue(body.contains("-15.00"), "Category detail page should show the category's total for the month")
    }

    @Test
    fun testDrillingIntoUncategorizedShowsTransactionsWithNoCategory() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.importCsv("2026-06-15,Mystery Charge,15.00,,985.00")

        val response = client.get("/analysis/category/uncategorized?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(response.bodyAsText().contains("Mystery Charge"))
    }

    @Test
    fun testDrillingIntoAnUnknownOrTransferCategorySlugIs404() = testApplication {
        testModule()
        val client = signInFakeUser()

        assertEquals(HttpStatusCode.NotFound, client.get("/analysis/category/not-a-real-category").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/analysis/category/transfer").status)
    }

    @Test
    fun testRecategorizeUpdatesTheTransactionAndSavesARuleForFutureImports() = testApplication {
        val categorizer = FakeTransactionCategorizer("OTHER")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("2026-06-15,COFFEE SHOP 1234,4.50,,995.50")
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        client.waitForCategorizationToFinish()

        val otherPage = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        val transactionId = Regex("""name="transactionId" value="([^"]+)"""").find(otherPage.bodyAsText())!!.groupValues[1]

        val recategorizeResponse = client.post("/analysis/recategorize") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("transactionId=$transactionId&category=DINING_OUT&matchType=SUBSTRING&pattern=COFFEE SHOP&fromSlug=other&year=2026&month=6")
        }
        val redirect = recategorizeResponse.headers[HttpHeaders.Location]!!
        assertTrue(redirect.startsWith("/analysis/category/other"))
        assertEquals("Recategorized as Dining Out", Url(redirect).parameters["message"])

        val diningOutPage = client.get("/analysis/category/dining_out?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(diningOutPage.bodyAsText().contains("COFFEE SHOP 1234"))

        // A second, differently-worded coffee-shop transaction should now be
        // caught by the saved rule instead of falling through to Gemini.
        client.importCsv("2026-06-20,COFFEE SHOP 5678,5.25,,990.25")
        val secondPage = client.get("/analysis?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(secondPage.bodyAsText().contains("Applied 1 rule(s)"))
        // Called once for the first coffee transaction (no rule existed
        // yet at that point) - not called again for the second, which the
        // rule created from the first catches instead.
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testRecategorizeRejectsATransactionThatDoesNotBelongToTheCaller() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/analysis/recategorize") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("transactionId=not-mine&category=DINING_OUT&matchType=EXACT&pattern=COFFEE&fromSlug=other&year=2026&month=6")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("Transaction not found", Url(redirect).parameters["error"])
    }

    @Test
    fun testRecategorizeRejectsTransferAsATargetCategory() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.importCsv("2026-06-15,COFFEE SHOP,4.50,,995.50")
        val otherPage = client.get("/analysis/category/uncategorized?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        val transactionId = Regex("""name="transactionId" value="([^"]+)"""").find(otherPage.bodyAsText())!!.groupValues[1]

        val response = client.post("/analysis/recategorize") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("transactionId=$transactionId&category=TRANSFER&matchType=EXACT&pattern=COFFEE SHOP&fromSlug=uncategorized&year=2026&month=6")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("Invalid recategorize request", Url(redirect).parameters["error"])
    }

    @Test
    fun testCategorizePageShowsRunningPanelWhileJobIsInFlightThenClearsOnCompletion() = testApplication {
        val categorizer = SlowTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("${java.time.LocalDate.now()},Metro Grocery,42.10,,957.90")

        // This same page load both starts the job and shows it running.
        val whileRunning = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(whileRunning.bodyAsText().contains("Categorizing"))

        client.waitForCategorizationToFinish()

        val afterFinish = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        val finishedBody = afterFinish.bodyAsText()
        assertTrue(finishedBody.contains("Categorized 1 of 1 transaction(s)"))
        assertFalse(finishedBody.contains("Categorizing"))
    }

    @Test
    fun testSecondPageLoadWhileCategorizationIsRunningDoesNotStartADuplicateJob() = testApplication {
        val categorizer = SlowTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("${java.time.LocalDate.now()},Metro Grocery,42.10,,957.90")
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") } // starts the job

        val secondLoad = client.get("/analysis") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(secondLoad.bodyAsText().contains("Categorizing"))

        client.waitForCategorizationToFinish()
        // Only the first page load's job ever actually ran the categorizer.
        assertEquals(1, categorizer.callCount)
    }

    @Test
    fun testCategorizationFailureShowsErrorBanner() = testApplication {
        val failingCategorizer = object : TransactionCategorizer {
            override suspend fun categorize(transactions: List<Transaction>, categories: List<Category>): Map<String, String> =
                throw IllegalStateException("GEMINI_API_KEY is not set")
        }
        testModule(transactionCategorizer = failingCategorizer)
        val client = signInFakeUser()

        client.importCsv("${java.time.LocalDate.now()},Metro Grocery,42.10,,957.90")
        val result = client.triggerCategorizeAndAwaitResult()
        assertTrue(result.error!!.contains("Categorization failed"))
    }
}
