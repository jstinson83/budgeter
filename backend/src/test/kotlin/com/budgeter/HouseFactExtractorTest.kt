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

class HouseFactExtractorTest {
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
    fun testMapsGeminiResponseItemsToExtractedFacts() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"what\":\"House contains steel columns\",\"type\":\"SPECIFICATION\",\"sourceQuote\":\"steel columns observed\",\"needsReview\":false,\"reviewQuestion\":\"\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", pdfBytes)

        assertEquals(1, facts.size)
        assertEquals("House contains steel columns", facts[0].what)
        assertEquals(FactType.SPECIFICATION, facts[0].type)
        assertEquals("steel columns observed", facts[0].sourceQuote)
        assertFalse(facts[0].needsReview)
        assertNull(facts[0].reviewQuestion)
    }

    @Test
    fun testBlankSourceQuoteAndReviewQuestionAreNormalizedToNull() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"what\":\"Cause not determined\",\"type\":\"CONDITION\",\"sourceQuote\":\"\",\"needsReview\":true,\"reviewQuestion\":\"What do you know about this?\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", pdfBytes)

        assertNull(facts[0].sourceQuote)
        assertTrue(facts[0].needsReview)
        assertEquals("What do you know about this?", facts[0].reviewQuestion)
    }

    @Test
    fun testUnrecognizedTypeFallsBackToUnknownRatherThanDroppingTheFact() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"what\":\"Something odd\",\"type\":\"NOT_A_REAL_TYPE\",\"sourceQuote\":\"\",\"needsReview\":false,\"reviewQuestion\":\"\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", pdfBytes)

        assertEquals(1, facts.size)
        assertEquals(FactType.UNKNOWN, facts[0].type)
    }

    @Test
    fun testThrowsInsteadOfSilentlyReturningEmptyWhenGeminiProducesNoTextOutput() = runBlocking {
        val client = mockClientRespondingWith("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")

        val exception = assertFailsWith<IllegalStateException> {
            GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", pdfBytes)
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
            GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", pdfBytes)
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
            GeminiHouseFactExtractor(client, "").extract("inspection.pdf", pdfBytes)
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
            GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", oversized)
        }
        assertTrue(exception.message!!.contains("too large"))
    }

    @Test
    fun testRequestBodyCarriesTheDocumentAsInlineDataWithNoStrayNullFields() = runBlocking {
        // The Gemini Part message is a strict oneof (text XOR inlineData) -
        // this codebase already hit exactly this kind of schema strictness
        // once before (see CLAUDE.md's Gemini gotchas), so the two parts
        // are built to never emit the other's field as an explicit null
        // (see GeminiHouseFactExtractor's own comment). Assert on the
        // literal outgoing JSON so a future refactor can't silently
        // reintroduce that.
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

        GeminiHouseFactExtractor(client, "fake-key").extract("inspection.pdf", pdfBytes)

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains(""""mimeType":"application/pdf""""), "expected an inlineData part with the PDF mime type: $body")
        assertTrue(body.contains(Base64.getEncoder().encodeToString(pdfBytes)), "expected the base64-encoded PDF bytes: $body")
        assertFalse(body.contains(""""text":null""""), "the inlineData part must not also emit a null text field: $body")
        assertFalse(body.contains(""""inlineData":null""""), "the text part must not also emit a null inlineData field: $body")
        assertTrue(body.contains(""""type":"BOOLEAN""""), "expected the needsReview schema property to be typed BOOLEAN: $body")
    }
}
