package com.budgeter

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class CategoryRoutesTest {
    @Test
    fun testCategoriesPageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/categories")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testCategoriesPageListsTheSeededBuiltInCategories() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/categories") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        assertTrue(body.contains("Groceries"))
        assertTrue(body.contains("Clothing"))
        assertTrue(body.contains("Investment"))
        // TRANSFER is never a real Category row - it must never appear as
        // a manageable row on this page.
        assertFalse(body.contains(">Transfer<"))
    }

    @Test
    fun testAddingACategoryMakesItAppearOnThePage() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/categories") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Hobby Supplies")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("Added Hobby Supplies", Url(redirect).parameters["message"])

        val page = client.get("/categories") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(page.bodyAsText().contains("Hobby Supplies"))
    }

    @Test
    fun testAddingACategoryWithABlankLabelIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/categories") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=   ")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("Category name is required", Url(redirect).parameters["error"])
    }

    @Test
    fun testTogglingACategoryDisablesThenReenablesIt() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.get("/categories") // seeds built-ins

        val disableResponse = client.post("/categories/GROCERIES/toggle")
        assertEquals("Disabled Groceries", Url(disableResponse.headers[HttpHeaders.Location]!!).parameters["message"])
        assertTrue(client.get("/categories") { header(HttpHeaders.Accept, "text/html") }.bodyAsText().contains("Groceries (disabled)"))

        val enableResponse = client.post("/categories/GROCERIES/toggle")
        assertEquals("Enabled Groceries", Url(enableResponse.headers[HttpHeaders.Location]!!).parameters["message"])
        assertFalse(client.get("/categories") { header(HttpHeaders.Accept, "text/html") }.bodyAsText().contains("Groceries (disabled)"))
    }

    @Test
    fun testTogglingAnUnknownCategoryIs404() = testApplication {
        testModule()
        val client = signInFakeUser()

        assertEquals(HttpStatusCode.NotFound, client.post("/categories/NOT_REAL/toggle").status)
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
    fun testDisablingACategoryRemovesItFromTheRecategorizeDropdownButKeepsPastTransactionsVisible() = testApplication {
        val categorizer = FakeTransactionCategorizer("GROCERIES")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()

        client.importCsv("2026-06-15,Metro Grocery,15.00,,985.00")
        client.get("/analysis") { header(HttpHeaders.Accept, "text/html") } // triggers categorization
        client.waitForCategorizationToFinish()
        client.post("/categories/GROCERIES/toggle") // disable it

        val drillDown = client.get("/analysis/category/groceries?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        val body = drillDown.bodyAsText()
        // Past transaction still shows under its (now disabled) category.
        assertTrue(body.contains("Metro Grocery"))
        // But it's no longer offered as a target in the recategorize form.
        assertFalse(body.contains("""value="GROCERIES""""))
    }

    @Test
    fun testAddingARuleFromTheCategoriesPageAppliesItOnTheNextCategorizeRun() = testApplication {
        val categorizer = FakeTransactionCategorizer("OTHER")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()
        client.get("/categories") // seeds built-ins

        val addRuleResponse = client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=NETFLIX&matchType=SUBSTRING&category=SUBSCRIPTIONS")
        }
        assertEquals("Added rule", Url(addRuleResponse.headers[HttpHeaders.Location]!!).parameters["message"])
        assertTrue(client.get("/categories") { header(HttpHeaders.Accept, "text/html") }.bodyAsText().contains("NETFLIX"))

        client.importCsv("2026-06-15,NETFLIX.COM,15.99,,984.01")
        // Rule matching is synchronous, so its result shows up on the very
        // page load that triggers categorization (see CategorizationJob.kt).
        val page = client.get("/analysis?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(page.bodyAsText().contains("Applied 1 rule(s)"))
        assertEquals(0, categorizer.callCount)
    }

    @Test
    fun testAddingARuleWithRescanRecategorizesAlreadyCategorizedTransactions() = testApplication {
        val categorizer = FakeTransactionCategorizer("OTHER")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()
        client.get("/categories") // seeds built-ins

        client.importCsv("2026-06-15,NETFLIX.COM,15.99,,984.01")
        // Falls to Gemini (the fake) and lands in OTHER, since no rule exists yet.
        client.triggerCategorizeAndAwaitResult()
        val beforeRescan = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(beforeRescan.bodyAsText().contains("NETFLIX.COM"))

        val addRuleResponse = client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=NETFLIX&matchType=SUBSTRING&category=SUBSCRIPTIONS&rescan=on")
        }
        assertEquals(
            "Added rule, recategorized 1 existing transaction(s)",
            Url(addRuleResponse.headers[HttpHeaders.Location]!!).parameters["message"]
        )

        val afterRescan = client.get("/analysis/category/subscriptions?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(afterRescan.bodyAsText().contains("NETFLIX.COM"))
        val stillOther = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertFalse(stillOther.bodyAsText().contains("NETFLIX.COM"))
    }

    @Test
    fun testAddingARuleWithoutRescanLeavesAlreadyCategorizedTransactionsAlone() = testApplication {
        val categorizer = FakeTransactionCategorizer("OTHER")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()
        client.get("/categories") // seeds built-ins

        client.importCsv("2026-06-15,NETFLIX.COM,15.99,,984.01")
        client.triggerCategorizeAndAwaitResult()

        val addRuleResponse = client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=NETFLIX&matchType=SUBSTRING&category=SUBSCRIPTIONS")
        }
        assertEquals("Added rule", Url(addRuleResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val stillOther = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(stillOther.bodyAsText().contains("NETFLIX.COM"))
    }

    @Test
    fun testRecategorizeAllClearsStaleCategoriesAndRedirectsToAnalysis() = testApplication {
        val categorizer = FakeTransactionCategorizer("OTHER")
        testModule(transactionCategorizer = categorizer)
        val client = signInFakeUser()
        client.get("/categories") // seeds built-ins

        client.importCsv("2026-06-15,NETFLIX.COM,15.99,,984.01")
        client.triggerCategorizeAndAwaitResult() // falls to Gemini, lands in OTHER

        // Same "rule added after the transaction was already categorized"
        // situation as testAddingARuleWithoutRescanLeavesAlreadyCategorizedTransactionsAlone -
        // without rescan, the existing transaction stays stuck in OTHER.
        client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=NETFLIX&matchType=SUBSTRING&category=SUBSCRIPTIONS")
        }
        val stillOtherBeforeReset = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(stillOtherBeforeReset.bodyAsText().contains("NETFLIX.COM"))

        val resetResponse = client.post("/categories/recategorize-all")
        assertEquals("/analysis", resetResponse.headers[HttpHeaders.Location])

        // The reset transaction is uncategorized again, so this run's rule
        // pass (not Gemini) picks it up under the now-existing rule.
        client.triggerCategorizeAndAwaitResult()
        val nowSubscriptions = client.get("/analysis/category/subscriptions?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(nowSubscriptions.bodyAsText().contains("NETFLIX.COM"))
        val noLongerOther = client.get("/analysis/category/other?year=2026&month=6") { header(HttpHeaders.Accept, "text/html") }
        assertFalse(noLongerOther.bodyAsText().contains("NETFLIX.COM"))
    }

    @Test
    fun testAddingARuleRejectsADisabledCategoryAsTheTarget() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.get("/categories")
        client.post("/categories/GROCERIES/toggle") // disable it

        val response = client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=METRO&matchType=SUBSTRING&category=GROCERIES")
        }
        assertEquals("Invalid rule", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testEditingARuleUpdatesItsPatternAndCategory() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.get("/categories")

        val addResponse = client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=NETFLIX&matchType=SUBSTRING&category=SUBSCRIPTIONS")
        }
        val page = client.get("/categories") { header(HttpHeaders.Accept, "text/html") }
        val ruleId = Regex("""/categories/rules/([^"]+)"""").find(page.bodyAsText())!!.groupValues[1]

        val editResponse = client.post("/categories/rules/$ruleId") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=HULU&matchType=EXACT&category=ENTERTAINMENT")
        }
        assertEquals("Updated rule", Url(editResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val updatedPage = client.get("/categories") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(updatedPage.bodyAsText().contains("HULU"))
        assertFalse(updatedPage.bodyAsText().contains("value=\"NETFLIX\""))
    }

    @Test
    fun testDeletingARuleRemovesItFromThePage() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.get("/categories")
        client.post("/categories/rules") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("pattern=NETFLIX&matchType=SUBSTRING&category=SUBSCRIPTIONS")
        }

        val page = client.get("/categories") { header(HttpHeaders.Accept, "text/html") }
        val ruleId = Regex("""/categories/rules/([^"]+)"""").find(page.bodyAsText())!!.groupValues[1]

        val deleteResponse = client.post("/categories/rules/$ruleId/delete")
        assertEquals("Deleted rule", Url(deleteResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDelete = client.get("/categories") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(afterDelete.bodyAsText().contains("No rules yet"))
    }
}
