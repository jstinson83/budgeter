package com.budgeter

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

private fun pdfFormData(filename: String = "inspection.pdf") = formData {
    append("file", "%PDF-1.4 fake".toByteArray(), Headers.build {
        append(HttpHeaders.ContentType, "application/pdf")
        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
    })
}

private suspend fun HttpClient.createProject(name: String, component: String = "ROOF"): String {
    val response = post("/projects") {
        contentType(ContentType.Application.FormUrlEncoded)
        setBody("name=$name&status=PLANNED&component=$component&priority=MEDIUM")
    }
    return response.headers[HttpHeaders.Location]!!.substringAfter("/projects/").substringBefore("?")
}

class ProjectRoutesTest {
    @Test
    fun testProjectsPageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/projects")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testEmptyStateBeforeAnyProjectCreated() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No projects yet"))
    }

    @Test
    fun testCreatingAProjectRedirectsToItsDetailPageAndListsItUnderItsStatus() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof&status=PLANNED&component=ROOF&priority=HIGH")
        }
        assertEquals(HttpStatusCode.Found, response.status)
        val redirect = response.headers[HttpHeaders.Location]!!
        assertTrue(redirect.startsWith("/projects/"))
        assertEquals("Created Replace roof", Url(redirect).parameters["message"])

        val listPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }
        val body = listPage.bodyAsText()
        assertTrue(body.contains("Replace roof"))
        assertTrue(body.contains("Planned"))
    }

    @Test
    fun testCreatingAProjectWithMissingFieldsIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=&status=PLANNED&component=ROOF&priority=HIGH")
        }
        val redirect = response.headers[HttpHeaders.Location]!!
        assertEquals("/projects", Url(redirect).encodedPath)
        assertEquals("Please fill in all fields", Url(redirect).parameters["error"])
    }

    @Test
    fun testViewingAnUnknownProjectIs404() = testApplication {
        testModule()
        val client = signInFakeUser()

        assertEquals(HttpStatusCode.NotFound, client.get("/projects/not-real").status)
    }

    @Test
    fun testEditingAProjectUpdatesItsFieldsAndMovesItBetweenStatusGroups() = testApplication {
        testModule()
        val client = signInFakeUser()

        val createResponse = client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof&status=PLANNED&component=ROOF&priority=MEDIUM")
        }
        val projectId = createResponse.headers[HttpHeaders.Location]!!.substringAfter("/projects/").substringBefore("?")

        val editResponse = client.post("/projects/$projectId") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof (2026)&status=ACTIVE&component=ROOF&priority=HIGH")
        }
        assertEquals("Updated", Url(editResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val detailPage = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }
        assertTrue(detailPage.bodyAsText().contains("Replace roof (2026)"))

        val listPage = client.get("/projects") { header(HttpHeaders.Accept, "text/html") }
        val body = listPage.bodyAsText()
        assertTrue(body.contains("<h2>Active</h2>"))
        // The status has no PLANNED projects left, so its group heading
        // shouldn't render - "Planned" as plain text still appears in the
        // "Add project" form's option, so check for the group heading
        // specifically rather than the word anywhere on the page.
        assertFalse(body.contains("<h2>Planned</h2>"))
    }

    @Test
    fun testFilteringByComponentOnlyShowsMatchingProjects() = testApplication {
        testModule()
        val client = signInFakeUser()

        client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Replace roof&status=PLANNED&component=ROOF&priority=MEDIUM")
        }
        client.post("/projects") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("name=Rewire panel&status=PLANNED&component=ELECTRICAL&priority=MEDIUM")
        }

        val filtered = client.get("/projects?component=ROOF") { header(HttpHeaders.Accept, "text/html") }
        val body = filtered.bodyAsText()
        assertTrue(body.contains("Replace roof"))
        assertFalse(body.contains("Rewire panel"))
    }

    @Test
    fun testLinkingAFactMovesItFromAvailableToLinkedAndDetachMovesItBack() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentId = uploadResponse.headers[HttpHeaders.Location]!!.substringAfterLast("/")
        client.waitForExtractionToFinish(documentId)

        val beforePage = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(beforePage.contains("No facts linked yet"))
        val factId = Regex("""<option value="(house-fact-\d+)">""").find(beforePage)!!.groupValues[1]

        val attachResponse = client.post("/projects/$projectId/facts") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("factId=$factId")
        }
        assertEquals("Linked fact", Url(attachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterAttach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(afterAttach.contains("No facts linked yet"))
        assertTrue(afterAttach.contains("steel structural columns"))

        val detachResponse = client.post("/projects/$projectId/facts/$factId/detach")
        assertEquals("Removed fact", Url(detachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDetach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDetach.contains("No facts linked yet"))
    }

    @Test
    fun testAttachingAFactThatDoesNotBelongToTheOwnerIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val response = client.post("/projects/$projectId/facts") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("factId=not-real")
        }
        assertEquals("Fact not found", Url(response.headers[HttpHeaders.Location]!!).parameters["error"])
    }

    @Test
    fun testLinkingADocumentMovesItFromAvailableToLinkedAndDetachMovesItBack() = testApplication {
        testModule()
        val client = signInFakeUser()
        val projectId = client.createProject("Replace roof")

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData("inspection.pdf"))
        val documentId = uploadResponse.headers[HttpHeaders.Location]!!.substringAfterLast("/")
        client.waitForExtractionToFinish(documentId)

        val beforePage = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(beforePage.contains("No documents linked yet"))

        val attachResponse = client.post("/projects/$projectId/documents") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("documentId=$documentId")
        }
        assertEquals("Linked document", Url(attachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterAttach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertFalse(afterAttach.contains("No documents linked yet"))
        assertTrue(afterAttach.contains("inspection.pdf"))

        val detachResponse = client.post("/projects/$projectId/documents/$documentId/detach")
        assertEquals("Removed document", Url(detachResponse.headers[HttpHeaders.Location]!!).parameters["message"])

        val afterDetach = client.get("/projects/$projectId") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(afterDetach.contains("No documents linked yet"))
    }
}
