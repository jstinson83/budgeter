package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.*

class RecommendationGeneratorTest {
    private val facts = listOf(
        HouseFact(
            id = "fact-0",
            ownerId = "owner-1",
            documentId = "doc-1",
            what = "Roof shingles are original from 1998 and showing wear",
            type = FactType.CONDITION,
            component = Component.ROOF,
            status = FactStatus.EXISTING,
            importance = Importance.HIGH,
            sourceQuote = "shingles original, granule loss observed",
            sourceLocation = null,
            evidenceType = EvidenceType.OBSERVED,
            needsReview = false,
            reviewQuestion = null,
            createdAt = Instant.now()
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
    fun testMapsGeminiResponseIndicesBackToRealFactIds() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"name\":\"Replace roof shingles\",\"rationale\":\"Original shingles are worn.\",\"supportingFactIndices\":[0],\"suggestedPriority\":\"HIGH\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val recommendations = GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())

        assertEquals(1, recommendations.size)
        assertEquals("Replace roof shingles", recommendations[0].name)
        assertEquals("Original shingles are worn.", recommendations[0].rationale)
        assertEquals(listOf("fact-0"), recommendations[0].supportingFactIds)
        assertEquals(Priority.HIGH, recommendations[0].suggestedPriority)
    }

    @Test
    fun testRecommendationWithNoValidSupportingFactIndicesIsDropped() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"name\":\"Ungrounded idea\",\"rationale\":\"No real support.\",\"supportingFactIndices\":[99],\"suggestedPriority\":\"MEDIUM\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val recommendations = GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())

        assertEquals(emptyList(), recommendations)
    }

    @Test
    fun testUnrecognizedPriorityFallsBackToMedium() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"name\":\"Replace roof shingles\",\"rationale\":\"Worn.\",\"supportingFactIndices\":[0],\"suggestedPriority\":\"NOT_A_PRIORITY\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val recommendations = GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())

        assertEquals(Priority.MEDIUM, recommendations[0].suggestedPriority)
    }

    @Test
    fun testThrowsInsteadOfSilentlyReturningEmptyWhenGeminiProducesNoTextOutput() = runBlocking {
        val client = mockClientRespondingWith("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")

        val exception = assertFailsWith<IllegalStateException> {
            GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())
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
            GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())
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
            GeminiRecommendationGenerator(client, "").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())
        }
        assertTrue(exception.message!!.contains("GEMINI_API_KEY"))
    }

    @Test
    fun testEmptyFactListReturnsEmptyBeforeMakingAnyRequest() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called with no facts") } }
        }

        val recommendations = GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, emptyList(), emptyList(), emptyList(), emptyList())

        assertEquals(emptyList(), recommendations)
    }

    @Test
    fun testRequestBodyCarriesFactsAndSchemaFields() = runBlocking {
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

        GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, emptyList(), emptyList(), emptyList())

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains("Roof shingles are original"), "expected the fact's text in the prompt: $body")
        assertTrue(body.contains(""""suggestedPriority":{"type":"STRING""""), "expected suggestedPriority to be typed STRING: $body")
        assertTrue(body.contains(""""supportingFactIndices":{"type":"ARRAY""""), "expected supportingFactIndices to be typed ARRAY: $body")
        assertTrue(body.contains(""""HIGH""""), "expected the priority enum values to include HIGH: $body")
    }

    @Test
    fun testActiveDeprioritizedAndPastRecommendationsAreWovenIntoThePrompt() = runBlocking {
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

        val activeProject = Project("p1", "owner-1", "Already replacing the roof", ProjectStatus.ACTIVE, Component.ROOF, Priority.HIGH, Instant.now())
        val deprioritizedProject = Project("p2", "owner-1", "Repaint shutters", ProjectStatus.DEPRIORITIZED, Component.EXTERIOR, Priority.LOW, Instant.now())
        val pastRecommendation = Recommendation(
            id = "r1", ownerId = "owner-1", component = Component.ROOF, name = "Inspect roof flashing",
            rationale = "Old flashing.", supportingFactIds = listOf("fact-0"), suggestedPriority = Priority.MEDIUM,
            status = RecommendationStatus.REJECTED, createdAt = Instant.now()
        )

        GeminiRecommendationGenerator(client, "fake-key").generate(Component.ROOF, facts, listOf(activeProject), listOf(deprioritizedProject), listOf(pastRecommendation))

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains("Already replacing the roof"), "expected the active project name in the prompt: $body")
        assertTrue(body.contains("Repaint shutters"), "expected the deprioritized project name in the prompt: $body")
        assertTrue(body.contains("Inspect roof flashing"), "expected the past recommendation name in the prompt: $body")
    }
}
