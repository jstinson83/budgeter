package com.budgeter

import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

private fun pdfFormData(filename: String = "inspection.pdf", bytes: ByteArray = "%PDF-1.4 fake".toByteArray()) = formData {
    append("file", bytes, Headers.build {
        append(HttpHeaders.ContentType, "application/pdf")
        append(HttpHeaders.ContentDisposition, "filename=\"$filename\"")
    })
}

private class ThrowingHouseFactExtractor : HouseFactExtractor {
    override suspend fun extract(filename: String, pdfBytes: ByteArray): List<ExtractedFact> =
        error("Gemini API request failed (503): upstream unavailable")
}

class HouseRoutesTest {
    @Test
    fun testHousePageRequiresSignIn() = testApplication {
        testModule()

        val response = client.get("/house")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun testEmptyStateBeforeAnyUpload() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.get("/house") { header(HttpHeaders.Accept, "text/html") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("No documents yet"))
    }

    @Test
    fun testUploadingNonPdfIsRejected() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(
            url = "/house/documents/upload",
            formData = formData {
                append("file", "not a pdf".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"notes.txt\"")
                })
            }
        )

        assertEquals(HttpStatusCode.Found, response.status)
        val redirect = response.headers[HttpHeaders.Location]
        assertNotNull(redirect)
        assertTrue(redirect.startsWith("/house?error="))
        assertTrue(redirect.contains("PDF"))
    }

    @Test
    fun testUploadingWithNoFileRedirectsWithError() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = formData {})

        assertEquals(HttpStatusCode.Found, response.status)
        assertTrue(response.headers[HttpHeaders.Location]!!.startsWith("/house?error="))
    }

    @Test
    fun testUploadingPdfExtractsFactsAndRedirectsToDocumentPageShowingFactCount() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())

        assertEquals(HttpStatusCode.Found, response.status)
        val redirect = response.headers[HttpHeaders.Location]
        assertNotNull(redirect)
        assertTrue(redirect.startsWith("/house/documents/"))
        assertTrue(redirect.contains("message="))

        val documentPage = client.get(redirect).bodyAsText()
        // FakeHouseFactExtractor's default facts: one plain, one needing
        // review - see TestFixtures.kt.
        assertTrue(documentPage.contains("Found 2 thing(s) worth remembering about your house"))
        assertTrue(documentPage.contains("The house contains steel structural columns"))
        assertTrue(documentPage.contains("Central floor bulge cause not determined"))
    }

    @Test
    fun testDocumentPageSplitsFactsNeedingReviewFromAcceptedOnes() = testApplication {
        testModule()
        val client = signInFakeUser()

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentUrl = uploadResponse.headers[HttpHeaders.Location]!!.substringBefore("?")
        val body = client.get(documentUrl) { header(HttpHeaders.Accept, "text/html") }.bodyAsText()

        assertTrue(body.contains("Needs your input"))
        // Apostrophe is HTML-escaped by FreeMarker's auto-escaping - assert
        // around it rather than on the literal raw string.
        assertTrue(body.contains("your understanding of this?"))
        assertTrue(body.contains("Longstanding condition"))
        assertTrue(body.contains("It was repaired"))
    }

    @Test
    fun testResolvingAFactWithAPresetAnswerMovesItToKnownWithHomeownerContext() = testApplication {
        testModule()
        val client = signInFakeUser()

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentUrl = uploadResponse.headers[HttpHeaders.Location]!!.substringBefore("?")
        val documentId = documentUrl.substringAfterLast("/")
        val beforeBody = client.get(documentUrl) { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val factId = Regex("""/house/facts/([^/]+)/resolve""").find(beforeBody)!!.groupValues[1]

        val resolveResponse = client.submitForm(
            url = "/house/facts/$factId/resolve",
            formParameters = parameters {
                append("documentId", documentId)
                append("homeownerContext", "Longstanding condition")
            }
        )

        assertEquals(HttpStatusCode.Found, resolveResponse.status)
        val redirect = resolveResponse.headers[HttpHeaders.Location]
        assertNotNull(redirect)
        assertTrue(redirect.startsWith("/house/documents/$documentId?message="))

        val afterBody = client.get(redirect).bodyAsText()
        assertFalse(afterBody.contains("Needs your input"))
        assertTrue(afterBody.contains("You said: Longstanding condition"))
    }

    @Test
    fun testResolvingWithEmptyContextRedirectsWithError() = testApplication {
        testModule()
        val client = signInFakeUser()

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentUrl = uploadResponse.headers[HttpHeaders.Location]!!.substringBefore("?")
        val documentId = documentUrl.substringAfterLast("/")
        val beforeBody = client.get(documentUrl) { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        val factId = Regex("""/house/facts/([^/]+)/resolve""").find(beforeBody)!!.groupValues[1]

        val response = client.submitForm(
            url = "/house/facts/$factId/resolve",
            formParameters = parameters {
                append("documentId", documentId)
                append("homeownerContext", "   ")
            }
        )

        assertTrue(response.headers[HttpHeaders.Location]!!.startsWith("/house/documents/$documentId?error="))
    }

    @Test
    fun testResolvingAnUnknownFactRedirectsWithError() = testApplication {
        testModule()
        val client = signInFakeUser()

        val response = client.submitForm(
            url = "/house/facts/does-not-exist/resolve",
            formParameters = parameters {
                append("documentId", "some-doc")
                append("homeownerContext", "It was repaired")
            }
        )

        assertTrue(response.headers[HttpHeaders.Location]!!.startsWith("/house/documents/some-doc?error="))
    }

    @Test
    fun testExtractionFailureMarksDocumentFailedAndShowsErrorBanner() = testApplication {
        testModule(houseFactExtractor = ThrowingHouseFactExtractor())
        val client = signInFakeUser()

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val redirect = uploadResponse.headers[HttpHeaders.Location]
        assertNotNull(redirect)
        assertTrue(redirect.contains("error="))

        val documentPage = client.get(redirect).bodyAsText()
        assertTrue(documentPage.contains("Extraction failed"))
        assertTrue(documentPage.contains("upstream unavailable"))
    }

    @Test
    fun testDocumentsAreScopedPerOwner() {
        val houseDocumentStore = FakeHouseDocumentRepository()
        val houseFactStore = FakeHouseFactRepository()

        testApplication {
            testModule(
                houseDocumentStore = houseDocumentStore,
                houseFactStore = houseFactStore,
                oauthClient = fakeGoogleOAuthClient("owner-sub", "owner@example.com", "Owner")
            )
            val client = signInFakeUser()
            client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData("owner-inspection.pdf"))
        }

        testApplication {
            testModule(
                houseDocumentStore = houseDocumentStore,
                houseFactStore = houseFactStore,
                oauthClient = fakeGoogleOAuthClient("other-sub", "other@example.com", "Other")
            )
            val client = signInFakeUser()
            val body = client.get("/house") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
            assertTrue(body.contains("No documents yet"))
            assertFalse(body.contains("owner-inspection.pdf"))
        }
    }
}
