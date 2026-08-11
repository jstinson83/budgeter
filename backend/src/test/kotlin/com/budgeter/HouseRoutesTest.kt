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

// Fails on its first call, then succeeds - lets a test drive a document to
// FAILED and then exercise the retry button recovering it, without needing
// two separate extractor instances wired into two separate requests.
private class FailOnceThenSucceedHouseFactExtractor : HouseFactExtractor {
    var callCount: Int = 0
        private set

    override suspend fun extract(filename: String, pdfBytes: ByteArray): List<ExtractedFact> {
        callCount++
        if (callCount == 1) error("Gemini API request failed (503): upstream unavailable")
        return listOf(ExtractedFact("Roof replaced in 2019", FactType.EVENT, "roof replaced 2019", false, null))
    }
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
        val documentId = redirect.substringAfterLast("/")

        // Extraction runs on a background coroutine now (see
        // HouseFactExtractionJob.kt) - the upload redirect happens before
        // it's done.
        client.waitForExtractionToFinish(documentId)

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
        client.waitForExtractionToFinish(documentUrl.substringAfterLast("/"))
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
        client.waitForExtractionToFinish(documentId)
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
        client.waitForExtractionToFinish(documentId)
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
        val documentId = redirect.substringAfterLast("/")

        val status = client.waitForExtractionToFinish(documentId)
        assertEquals("FAILED", status.status)

        val documentPage = client.get(redirect).bodyAsText()
        assertTrue(documentPage.contains("Extraction failed"))
        assertTrue(documentPage.contains("upstream unavailable"))
    }

    @Test
    fun testRetryingAFailedDocumentReextractsFromTheAlreadyUploadedBlob() = testApplication {
        val extractor = FailOnceThenSucceedHouseFactExtractor()
        testModule(houseFactExtractor = extractor)
        val client = signInFakeUser()

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentUrl = uploadResponse.headers[HttpHeaders.Location]!!
        val documentId = documentUrl.substringAfterLast("/")
        client.waitForExtractionToFinish(documentId)
        assertTrue(client.get(documentUrl).bodyAsText().contains("Extraction failed"))

        val retryResponse = client.submitForm(url = "/house/documents/$documentId/retry", formParameters = parameters {})
        assertEquals(HttpStatusCode.Found, retryResponse.status)
        assertEquals("/house/documents/$documentId", retryResponse.headers[HttpHeaders.Location])

        val status = client.waitForExtractionToFinish(documentId)
        assertEquals("EXTRACTED", status.status)
        // No re-upload happened - the retry re-read the same blob
        // documentBlobStore.upload() wrote on the original request.
        assertEquals(2, extractor.callCount)

        val documentPage = client.get(documentUrl).bodyAsText()
        assertTrue(documentPage.contains("Roof replaced in 2019"))
        assertFalse(documentPage.contains("Extraction failed"))
    }

    @Test
    fun testDeletingADocumentRemovesItAndItsFacts() = testApplication {
        testModule()
        val client = signInFakeUser()

        val uploadResponse = client.submitFormWithBinaryData(url = "/house/documents/upload", formData = pdfFormData())
        val documentUrl = uploadResponse.headers[HttpHeaders.Location]!!
        val documentId = documentUrl.substringAfterLast("/")
        client.waitForExtractionToFinish(documentId)

        val deleteResponse = client.submitForm(url = "/house/documents/$documentId/delete", formParameters = parameters {})
        assertEquals(HttpStatusCode.Found, deleteResponse.status)
        assertTrue(deleteResponse.headers[HttpHeaders.Location]!!.startsWith("/house?message="))

        assertEquals(HttpStatusCode.NotFound, client.get(documentUrl).status)
        val housePage = client.get("/house") { header(HttpHeaders.Accept, "text/html") }.bodyAsText()
        assertTrue(housePage.contains("No documents yet"))
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
