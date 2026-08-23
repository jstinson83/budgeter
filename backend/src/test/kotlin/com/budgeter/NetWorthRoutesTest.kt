package com.budgeter

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class NetWorthRoutesTest {
    @Test
    fun testPlanningPageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/planning")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testPlanningPageShowsNoEntriesEmptyStateAndZeroNetWorth() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }
        val body = response.bodyAsText()
        assertTrue(body.contains("No assets or liabilities yet."))
        assertTrue(body.contains("0.00"))
    }

    @Test
    fun testPlanningPageRendersAScrollableWealthChartWithJustTheBaselineLineByDefault() = testApplication {
        testModule()
        val client = signInFakeUser()

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Wealth over time"))
        assertTrue(page.contains("class=\"wealth-chart-scroll\""))
        assertTrue(page.contains("class=\"wealth-chart\""))
        assertTrue(page.contains("id=\"scenario-chip-baseline\""))
        assertTrue(page.contains("projection-chart-line\""))
        assertFalse(page.contains("id=\"goals-card\""))
    }

    @Test
    fun testPlanningExportRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/planning/export")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testPlanningExportReturnsAZipAttachment() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Brokerage&type=INVESTMENT&value=20000.00")
        }

        val response = client.get("/planning/export")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Application.Zip, response.contentType()?.withoutParameters())
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains("planning-export-"))
        assertTrue(response.headers[HttpHeaders.ContentDisposition]!!.contains(".zip"))
    }

    @Test
    fun testAddingAnAssetMakesItAppearAndUpdatesNetWorth() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Brokerage&type=INVESTMENT&value=20000.00")
        }
        assertEquals("Added Brokerage", Url(response.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Brokerage"))
        assertTrue(page.contains("20000.00"))
    }

    @Test
    fun testAddingALiabilitySubtractsFromNetWorth() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Brokerage&type=INVESTMENT&value=20000.00")
        }
        client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Mortgage&type=MORTGAGE&value=18000.00")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("2000.00")) // net worth: 20000 - 18000
    }

    @Test
    fun testAddingAnEntryWithABlankLabelIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=   &type=BANK&value=100.00")
        }
        assertEquals("Invalid entry", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testAddingAnEntryWithANegativeValueIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Chequing&type=BANK&value=-5.00")
        }
        assertEquals("Invalid entry", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testEditingAnEntryUpdatesItsLabelAndValue() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Chequing&type=BANK&value=5000.00")
        }
        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val entryId = Regex("""/planning/entries/([^"/]+)"""").find(page)!!.groupValues[1]

        val editResponse = client.post("/planning/entries/$entryId") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Savings&type=BANK&value=6000.00")
        }
        assertEquals("Updated Savings", Url(editResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val updatedPage = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(updatedPage.contains("Savings"))
        assertTrue(updatedPage.contains("6000.00"))
    }

    @Test
    fun testEditingAnUnknownEntryIsReportedAsNotFound() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/entries/not-real") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Savings&type=BANK&value=6000.00")
        }
        assertEquals("Entry not found", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testDeletingAnEntryRemovesItFromThePage() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Chequing&type=BANK&value=5000.00")
        }
        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val entryId = Regex("""/planning/entries/([^"/]+)"""").find(page)!!.groupValues[1]

        val deleteResponse = client.post("/planning/entries/$entryId/delete")
        assertEquals("Deleted entry", Url(deleteResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDelete = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDelete.contains("No assets or liabilities yet."))
    }

    // Goals themselves are hidden from /planning's UI for now (see
    // CLAUDE.md/current.md, 2026-08-23) - the store/resolvedTargetAmount
    // logic is covered directly in FinancialGoalStoreTest.kt, so the tests
    // that used to scrape a goal's rendered card off the page were removed
    // rather than adjusted. The routes themselves are still reachable
    // (kept for PlanningExport.kt and in case this gets revisited), so
    // their own validation behavior is still worth covering here.

    @Test
    fun testAddingAGoalWithABlankNameIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=   &type=NET_WORTH_TARGET&targetDate=2031-08-21&targetAmount=500000.00")
        }
        assertEquals("Invalid goal", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testAddingANetWorthTargetGoalWithoutATargetAmountIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=House fund&type=NET_WORTH_TARGET&targetDate=2031-08-21")
        }
        assertEquals("Invalid goal", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testEditingAnUnknownGoalIsReportedAsNotFound() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/goals/not-real") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=X&type=NET_WORTH_TARGET&targetDate=2031-08-21&targetAmount=1.00")
        }
        assertEquals("Goal not found", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testPlanningPageShowsNoScenariosEmptyState() = testApplication {
        testModule()
        val client = signInFakeUser()

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("No scenarios yet."))
    }

    @Test
    fun testAddingAScenarioMakesItAppearAndAddsALineToTheWealthChart() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Aggressive growth&annualMarketGrowthRatePercent=10&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        assertEquals("Added Aggressive growth", Url(response.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Aggressive growth"))
        assertTrue(page.contains("id=\"scenario-chip-scenario-1\""))
        assertTrue(page.contains("projection-chart-line-scenario-1"))
    }

    @Test
    fun testAddingAScenarioWithASalaryChangeEventPersistsBothFields() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Raise in 2027&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=50&recreationalSpendAdjustment=0&salaryChangeDate=2027-01-01&salaryChangeMonthlyDelta=500")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("value=\"2027-01-01\""))
        assertTrue(page.contains("value=\"500.00\""))
    }

    @Test
    fun testAddingAScenarioWithABlankNameIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=   &annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        assertEquals("Invalid scenario", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testAddingAScenarioWithAnInvestedFractionOutOfRangeIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Bad&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=150&recreationalSpendAdjustment=0")
        }
        assertEquals("Invalid scenario", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testEditingAScenarioUpdatesItsFields() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Growth&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val scenarioId = Regex("""/planning/scenarios/([^"/]+)"""").find(page)!!.groupValues[1]

        val editResponse = client.post("/planning/scenarios/$scenarioId") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Faster growth&annualMarketGrowthRatePercent=10&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        assertEquals("Updated Faster growth", Url(editResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val updatedPage = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(updatedPage.contains("Faster growth"))
    }

    @Test
    fun testEditingAnUnknownScenarioIsReportedAsNotFound() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/scenarios/not-real") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=X&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        assertEquals("Scenario not found", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testDeletingAScenarioRemovesItFromThePage() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Growth&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val scenarioId = Regex("""/planning/scenarios/([^"/]+)"""").find(page)!!.groupValues[1]

        val deleteResponse = client.post("/planning/scenarios/$scenarioId/delete")
        assertEquals("Deleted scenario", Url(deleteResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDelete = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDelete.contains("No scenarios yet."))
    }

    @Test
    fun testAddingAScenarioWithAnRrspStrategyPersistsTheReinvestRefundCheckbox() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "name=Max RRSP&annualMarketGrowthRatePercent=0&investedSavingsFractionPercent=0&recreationalSpendAdjustment=0" +
                    "&rrspMonthlyContribution=1000&rrspMarginalTaxRatePercent=40&rrspRoomRemaining=100000&rrspReinvestRefund=on"
            )
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("name=\"rrspReinvestRefund\" checked"))
    }

    @Test
    fun testAddingAScenarioWithAPartialRrspStrategyIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        // Contribution amount with no room cap or tax rate isn't a
        // coherent strategy - all three or none.
        val response = client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Bad&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0&rrspMonthlyContribution=1000")
        }
        assertEquals("Invalid scenario", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testAddingAScenarioWithNoRrspStrategyDoesNotShowRefundText() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Plain growth&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Plain growth"))
        assertFalse(page.contains("in RRSP refunds"))
    }

    @Test
    fun testPlanningPageOffersTheHouseholdsActiveCategoriesForRrspRoomAccrual() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.get("/categories") // seeds the built-in categories, including INCOME

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("""<option value="INCOME">Income</option>"""))
    }

    @Test
    fun testAddingAScenarioWithAnIncomeCategoryPersistsTheSelection() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.get("/categories") // seeds the built-in categories

        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "name=Catch-up&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0" +
                    "&rrspIncomeCategoryId=INCOME&rrspAnnualRoomAccrualCap=31560"
            )
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("""value="INCOME" selected"""))
        assertTrue(page.contains("value=\"31560.00\""))
    }

    @Test
    fun testAddingAScenarioWithNoIncomeCategorySelectedLeavesItUnset() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=No accrual&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(page.contains("selected>Income"))
    }
}
