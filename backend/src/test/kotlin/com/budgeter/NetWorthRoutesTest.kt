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
}
