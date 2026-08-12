package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.*

class HouseFactNormalizerTest {
    private val candidates = listOf(
        ExtractedCandidate(
            candidate = "House contains steel columns",
            context = null,
            sourceQuote = "steel columns observed",
            sourceLocation = "page 4",
            status = CandidateStatus.EXISTING,
            importance = Importance.MEDIUM
        )
    )

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
            """{"candidates":[{"content":{"parts":[{"text":"[{\"statement\":\"House contains steel structural columns\",\"type\":\"SPECIFICATION\",\"component\":\"STRUCTURE\",\"status\":\"EXISTING\",\"importance\":\"MEDIUM\",\"needsReview\":false,\"reviewQuestion\":\"\",\"sourceQuote\":\"steel columns observed\",\"sourceLocation\":\"page 4\",\"evidenceType\":\"OBSERVED\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)

        assertEquals(1, facts.size)
        assertEquals("House contains steel structural columns", facts[0].what)
        assertEquals(FactType.SPECIFICATION, facts[0].type)
        assertEquals(Component.STRUCTURE, facts[0].component)
        assertEquals(FactStatus.EXISTING, facts[0].status)
        assertEquals(Importance.MEDIUM, facts[0].importance)
        assertFalse(facts[0].needsReview)
        assertNull(facts[0].reviewQuestion)
        assertEquals("steel columns observed", facts[0].sourceQuote)
        assertEquals("page 4", facts[0].sourceLocation)
        assertEquals(EvidenceType.OBSERVED, facts[0].evidenceType)
    }

    @Test
    fun testBlankSourceQuoteLocationAndReviewQuestionAreNormalizedToNull() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"statement\":\"Cause not determined\",\"type\":\"CONDITION\",\"component\":\"STRUCTURE\",\"status\":\"EXISTING\",\"importance\":\"HIGH\",\"needsReview\":true,\"reviewQuestion\":\"What do you know about this?\",\"sourceQuote\":\"\",\"sourceLocation\":\"\",\"evidenceType\":\"OBSERVED\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)

        assertNull(facts[0].sourceQuote)
        assertNull(facts[0].sourceLocation)
        assertTrue(facts[0].needsReview)
        assertEquals("What do you know about this?", facts[0].reviewQuestion)
    }

    @Test
    fun testUnrecognizedTypeComponentStatusAndImportanceFallBackRatherThanDroppingTheFact() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"statement\":\"Something odd\",\"type\":\"NOT_A_TYPE\",\"component\":\"NOT_A_COMPONENT\",\"status\":\"NOT_A_STATUS\",\"importance\":\"NOT_AN_IMPORTANCE\",\"needsReview\":false,\"reviewQuestion\":\"\",\"sourceQuote\":\"\",\"sourceLocation\":\"\",\"evidenceType\":\"REPORTED\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)

        assertEquals(1, facts.size)
        assertEquals(FactType.UNKNOWN, facts[0].type)
        assertEquals(Component.OTHER, facts[0].component)
        assertEquals(FactStatus.UNKNOWN, facts[0].status)
        assertEquals(Importance.MEDIUM, facts[0].importance)
    }

    @Test
    fun testUnrecognizedEvidenceTypeFallsBackToNullRatherThanDroppingTheFact() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"statement\":\"Something odd\",\"type\":\"UNKNOWN\",\"component\":\"OTHER\",\"status\":\"UNKNOWN\",\"importance\":\"LOW\",\"needsReview\":false,\"reviewQuestion\":\"\",\"sourceQuote\":\"\",\"sourceLocation\":\"\",\"evidenceType\":\"NOT_A_REAL_EVIDENCE_TYPE\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val facts = GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)

        assertEquals(1, facts.size)
        assertNull(facts[0].evidenceType)
    }

    @Test
    fun testThrowsInsteadOfSilentlyReturningEmptyWhenGeminiProducesNoTextOutput() = runBlocking {
        val client = mockClientRespondingWith("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")

        val exception = assertFailsWith<IllegalStateException> {
            GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)
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
            GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)
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
            GeminiHouseFactNormalizer(client, "").normalize(candidates)
        }
        assertTrue(exception.message!!.contains("GEMINI_API_KEY"))
    }

    @Test
    fun testEmptyCandidateListReturnsEmptyBeforeMakingAnyRequest() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called with no candidates") } }
        }

        val facts = GeminiHouseFactNormalizer(client, "fake-key").normalize(emptyList())

        assertEquals(emptyList(), facts)
    }

    @Test
    fun testRequestBodyCarriesTheCandidatesAndSchemaFields() = runBlocking {
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

        GeminiHouseFactNormalizer(client, "fake-key").normalize(candidates)

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains("House contains steel columns"), "expected the candidate's statement in the prompt text: $body")
        assertTrue(body.contains(""""needsReview":{"type":"BOOLEAN""""), "expected the needsReview schema property to be typed BOOLEAN: $body")
        assertTrue(body.contains(""""SCOPE_LIMITATION""""), "expected the type enum values to include SCOPE_LIMITATION: $body")
        assertTrue(body.contains(""""INFERRED""""), "expected the evidenceType enum values to include INFERRED: $body")
    }
}
