package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.*

class HouseFactCandidateExtractorTest {
    private val pdfBytes = "%PDF-1.4 fake pdf content".toByteArray()

    private fun mockClientRespondingWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient = HttpClient(MockEngine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
        engine {
            addHandler {
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
    }

    @Test
    fun testMapsGeminiResponseItemsToExtractedCandidates() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"candidate\":\"House contains steel columns\",\"context\":\"observed in the crawlspace\",\"sourceQuote\":\"steel columns observed\",\"sourceLocation\":\"page 4\",\"status\":\"EXISTING\",\"importance\":\"MEDIUM\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val candidates = GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)

        assertEquals(1, candidates.size)
        assertEquals("House contains steel columns", candidates[0].candidate)
        assertEquals("observed in the crawlspace", candidates[0].context)
        assertEquals("steel columns observed", candidates[0].sourceQuote)
        assertEquals("page 4", candidates[0].sourceLocation)
        assertEquals(CandidateStatus.EXISTING, candidates[0].status)
        assertEquals(Importance.MEDIUM, candidates[0].importance)
    }

    @Test
    fun testBlankOptionalFieldsAreNormalizedToNull() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"candidate\":\"Cause not determined\",\"context\":\"\",\"sourceQuote\":\"\",\"sourceLocation\":\"\",\"status\":\"UNKNOWN\",\"importance\":\"LOW\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val candidates = GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)

        assertNull(candidates[0].context)
        assertNull(candidates[0].sourceQuote)
        assertNull(candidates[0].sourceLocation)
    }

    @Test
    fun testUnrecognizedStatusFallsBackToUnknownRatherThanDroppingTheCandidate() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"candidate\":\"Something odd\",\"context\":\"\",\"sourceQuote\":\"\",\"sourceLocation\":\"\",\"status\":\"NOT_A_REAL_STATUS\",\"importance\":\"LOW\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val candidates = GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)

        assertEquals(1, candidates.size)
        assertEquals(CandidateStatus.UNKNOWN, candidates[0].status)
    }

    @Test
    fun testUnrecognizedImportanceFallsBackToMediumRatherThanDroppingTheCandidate() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"candidate\":\"Something odd\",\"context\":\"\",\"sourceQuote\":\"\",\"sourceLocation\":\"\",\"status\":\"UNKNOWN\",\"importance\":\"NOT_A_REAL_IMPORTANCE\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val candidates = GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)

        assertEquals(1, candidates.size)
        assertEquals(Importance.MEDIUM, candidates[0].importance)
    }

    @Test
    fun testThrowsInsteadOfSilentlyReturningEmptyWhenGeminiProducesNoTextOutput() = runBlocking {
        val client = mockClientRespondingWith("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")

        val exception = assertFailsWith<IllegalStateException> {
            GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)
        }
        assertTrue(exception.message!!.contains("MAX_TOKENS"))
    }

    @Test
    fun testSurfacesGeminiApiErrorBodyInsteadOfGenericNoCandidatesMessage() = runBlocking {
        val client = mockClientRespondingWith(
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""",
            HttpStatusCode.BadRequest
        )

        val exception = assertFailsWith<IllegalStateException> {
            GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)
        }
        assertTrue(exception.message!!.contains("400"))
        assertTrue(exception.message!!.contains("API key not valid"))
    }

    @Test
    fun testMissingApiKeyThrowsBeforeMakingAnyRequest() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called without an API key") } }
        }

        val exception = assertFailsWith<IllegalStateException> {
            GeminiHouseFactCandidateExtractor(client, "").extractCandidates("inspection.pdf", pdfBytes, null)
        }
        assertTrue(exception.message!!.contains("GEMINI_API_KEY"))
    }

    @Test
    fun testDocumentAboveInlineSizeLimitThrowsBeforeMakingAnyRequest() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called for an oversized document") } }
        }
        val oversized = ByteArray(15_000_001)

        val exception = assertFailsWith<IllegalStateException> {
            GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", oversized, null)
        }
        assertTrue(exception.message!!.contains("too large"))
    }

    @Test
    fun testRequestBodyCarriesTheDocumentAsInlineDataWithNoStrayNullFields() = runBlocking {
        // The Gemini Part message is a strict oneof (text XOR inlineData) -
        // see CLAUDE.md's Gemini gotchas. Assert on the literal outgoing
        // JSON so a future refactor can't silently reintroduce that.
        var capturedRequestBody: String? = null
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            engine {
                addHandler { request ->
                    capturedRequestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"candidates":[{"content":{"parts":[{"text":"[]"}]},"finishReason":"STOP"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }

        GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains(""""mimeType":"application/pdf""""), "expected an inlineData part with the PDF mime type: $body")
        assertTrue(body.contains(Base64.getEncoder().encodeToString(pdfBytes)), "expected the base64-encoded PDF bytes: $body")
        assertFalse(body.contains(""""text":null""""), "the inlineData part must not also emit a null text field: $body")
        assertFalse(body.contains(""""inlineData":null""""), "the text part must not also emit a null inlineData field: $body")
        assertTrue(body.contains(""""status":{"type":"STRING""""), "expected a status schema property: $body")
        assertTrue(body.contains(""""ASSUMED""""), "expected the status enum values to include ASSUMED: $body")
    }

    @Test
    fun testDocumentContextIsWovenIntoThePromptWhenProvided() = runBlocking {
        var capturedRequestBody: String? = null
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            engine {
                addHandler { request ->
                    capturedRequestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"candidates":[{"content":{"parts":[{"text":"[]"}]},"finishReason":"STOP"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }

        GeminiHouseFactCandidateExtractor(client, "fake-key")
            .extractCandidates("inspection.pdf", pdfBytes, "This is the 2017 kitchen renovation")

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains("This is the 2017 kitchen renovation"), "expected the homeowner context in the prompt text: $body")
    }

    @Test
    fun testAbsentDocumentContextAddsNoExtraPromptText() = runBlocking {
        var capturedRequestBody: String? = null
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            engine {
                addHandler { request ->
                    capturedRequestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"candidates":[{"content":{"parts":[{"text":"[]"}]},"finishReason":"STOP"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }

        GeminiHouseFactCandidateExtractor(client, "fake-key").extractCandidates("inspection.pdf", pdfBytes, null)

        val body = assertNotNull(capturedRequestBody)
        assertFalse(body.contains("The homeowner has provided"), "expected no homeowner-context block when none was supplied: $body")
    }
}
