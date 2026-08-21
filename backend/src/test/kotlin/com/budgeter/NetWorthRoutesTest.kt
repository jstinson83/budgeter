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
        assertTrue(body.contains("No assets yet."))
        assertTrue(body.contains("No liabilities yet."))
        assertTrue(body.contains("0.00"))
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
        assertTrue(afterDelete.contains("No assets yet."))
    }

    @Test
    fun testPlanningPageShowsNoGoalsEmptyState() = testApplication {
        testModule()
        val client = signInFakeUser()

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("No goals yet."))
    }

    @Test
    fun testAddingANetWorthTargetGoalShowsItsResolvedTargetAmount() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=House fund&type=NET_WORTH_TARGET&targetDate=2031-08-21&targetAmount=500000.00")
        }
        assertEquals("Added House fund", Url(response.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("House fund"))
        assertTrue(page.contains("Target: 500000.00 by 2031-08-21"))
    }

    @Test
    fun testAddingARetirementGoalDerivesItsTargetAmountFromAnnualSpendAndWithdrawalRate() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Retire early&type=RETIREMENT&targetDate=2050-01-01&annualSpend=80000.00&withdrawalRatePercent=4")
        }
        assertEquals("Added Retire early", Url(response.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Target: 2000000.00 by 2050-01-01"))
    }

    @Test
    fun testAddingARetirementGoalWithoutAWithdrawalRateFallsBackToTheDefault() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Retire&type=RETIREMENT&targetDate=2050-01-01&annualSpend=40000.00")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Target: 1000000.00 by 2050-01-01")) // 40000 / 0.04
    }

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
    fun testEditingAGoalUpdatesItsNameAndTargetAmount() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=House fund&type=NET_WORTH_TARGET&targetDate=2031-08-21&targetAmount=500000.00")
        }
        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val goalId = Regex("""/planning/goals/([^"/]+)"""").find(page)!!.groupValues[1]

        val editResponse = client.post("/planning/goals/$goalId") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Bigger house fund&type=NET_WORTH_TARGET&targetDate=2032-01-01&targetAmount=600000.00")
        }
        assertEquals("Updated Bigger house fund", Url(editResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val updatedPage = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(updatedPage.contains("Bigger house fund"))
        assertTrue(updatedPage.contains("Target: 600000.00 by 2032-01-01"))
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
    fun testDeletingAGoalRemovesItFromThePage() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=House fund&type=NET_WORTH_TARGET&targetDate=2031-08-21&targetAmount=500000.00")
        }
        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val goalId = Regex("""/planning/goals/([^"/]+)"""").find(page)!!.groupValues[1]

        val deleteResponse = client.post("/planning/goals/$goalId/delete")
        assertEquals("Deleted goal", Url(deleteResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDelete = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDelete.contains("No goals yet."))
    }

    @Test
    fun testAGoalAlreadyAboveItsTargetRendersAsOnTrackWithAProjectionChart() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/entries") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("label=Chequing&type=BANK&value=100000.00")
        }
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Easy goal&type=NET_WORTH_TARGET&targetDate=2026-09-01&targetAmount=50000.00")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("class=\"projection-chart\""))
        assertTrue(page.contains("on track"))
        assertFalse(page.contains("short by"))
    }

    @Test
    fun testAGoalFarBelowItsTargetRendersAsShortBy() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Hard goal&type=NET_WORTH_TARGET&targetDate=2026-09-01&targetAmount=1000000.00")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("short by"))
    }

    @Test
    fun testPlanningPageShowsNoScenariosEmptyState() = testApplication {
        testModule()
        val client = signInFakeUser()

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("No scenarios yet."))
    }

    @Test
    fun testAddingAScenarioMakesItAppearAndAddsALineToEveryGoalsChart() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Goal&type=NET_WORTH_TARGET&targetDate=2027-08-21&targetAmount=50000.00")
        }

        val response = client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Aggressive growth&annualMarketGrowthRatePercent=10&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }
        assertEquals("Added Aggressive growth", Url(response.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Aggressive growth"))
        assertTrue(page.contains("class=\"projection-chart-legend\""))
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
    fun testAddingAScenarioWithAnRrspStrategyShowsItsRefundsOnTheGoalCard() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Goal&type=NET_WORTH_TARGET&targetDate=2027-08-21&targetAmount=50000.00")
        }

        val response = client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "name=Max RRSP&annualMarketGrowthRatePercent=0&investedSavingsFractionPercent=0&recreationalSpendAdjustment=0" +
                    "&rrspMonthlyContribution=1000&rrspMarginalTaxRatePercent=40&rrspRoomRemaining=100000"
            )
        }
        assertEquals("Added Max RRSP", Url(response.headers[HttpHeaders.Location]!!).parameters["message"])

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("in RRSP refunds"))
        assertTrue(page.contains("room left"))
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
    fun testAddingAScenarioWithNoRrspStrategyStillShowsOnTrackOffTrackWithoutRefundText() = testApplication {
        testModule()
        val client = signInFakeUser()
        client.post("/planning/goals") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Goal&type=NET_WORTH_TARGET&targetDate=2027-08-21&targetAmount=50000.00")
        }
        client.post("/planning/scenarios") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Plain growth&annualMarketGrowthRatePercent=7&investedSavingsFractionPercent=100&recreationalSpendAdjustment=0")
        }

        val page = client.get("/planning") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(page.contains("Plain growth"))
        assertFalse(page.contains("in RRSP refunds"))
    }
}
