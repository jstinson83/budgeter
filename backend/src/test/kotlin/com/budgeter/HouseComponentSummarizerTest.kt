package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Instant
import kotlin.test.*

class HouseComponentSummarizerTest {
    private val fact = HouseFact(
        id = "fact-1",
        ownerId = "owner-1",
        documentId = "doc-1",
        what = "Roof flashing sealant should be inspected periodically",
        type = FactType.MAINTENANCE_REQUIREMENT,
        component = Component.ROOF,
        sourceQuote = "flashing sealant recommended for periodic inspection",
        needsReview = false,
        reviewQuestion = null,
        createdAt = Instant.now()
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
    fun testReturnsTrimmedSummaryText() = runBlocking {
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"  The roof has a documented flashing sealant maintenance need.  "}]},"finishReason":"STOP"}]}"""
        )

        val summary = GeminiComponentSummarizer(client, "fake-key").summarize(Component.ROOF, listOf(fact))

        assertEquals("The roof has a documented flashing sealant maintenance need.", summary)
    }

    @Test
    fun testThrowsInsteadOfSilentlyReturningEmptyWhenGeminiProducesNoTextOutput() = runBlocking {
        val client = mockClientRespondingWith("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")

        val exception = assertFailsWith<IllegalStateException> {
            GeminiComponentSummarizer(client, "fake-key").summarize(Component.ROOF, listOf(fact))
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
            GeminiComponentSummarizer(client, "fake-key").summarize(Component.ROOF, listOf(fact))
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
            GeminiComponentSummarizer(client, "").summarize(Component.ROOF, listOf(fact))
        }
        assertTrue(exception.message!!.contains("GEMINI_API_KEY"))
    }

    @Test
    fun testEmptyFactListThrowsBeforeMakingAnyRequest() = runBlocking {
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called with no facts") } }
        }

        val exception = assertFailsWith<IllegalStateException> {
            GeminiComponentSummarizer(client, "fake-key").summarize(Component.ROOF, emptyList())
        }
        assertTrue(exception.message!!.contains("No facts"))
    }
}
