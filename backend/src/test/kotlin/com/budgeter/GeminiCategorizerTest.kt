package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.*

class GeminiCategorizerTest {
    private fun transaction(id: String, description: String, amount: Double): Transaction =
        Transaction(id, "owner", LocalDate.of(2026, 1, 15), description, amount)

    private fun mockClientRespondingWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient = HttpClient(MockEngine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        engine {
            addHandler {
                respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
    }

    @Test
    fun testMapsGeminiResponseIndicesBackToTransactionIds() = runBlocking {
        val transactions = listOf(
            transaction("tx-1", "Metro Grocery", -42.10),
            transaction("tx-2", "Payroll", 2500.00)
        )
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"index\":0,\"category\":\"GROCERIES\"},{\"index\":1,\"category\":\"INCOME\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val result = GeminiTransactionCategorizer(client, "fake-key").categorize(transactions)

        assertEquals(TransactionCategory.GROCERIES, result["tx-1"])
        assertEquals(TransactionCategory.INCOME, result["tx-2"])
    }

    @Test
    fun testThrowsInsteadOfSilentlyReturningEmptyWhenGeminiProducesNoTextOutput() = runBlocking {
        // Reproduces the real-world bug report: a candidate with a
        // finishReason but no text part at all (e.g. the whole
        // output-token budget got spent before any visible output) used to
        // read as "0 of N categorized" with no indication anything was
        // wrong. It should now fail loudly instead.
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = mockClientRespondingWith("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""")

        val exception = assertFailsWith<IllegalStateException> {
            GeminiTransactionCategorizer(client, "fake-key").categorize(transactions)
        }
        assertTrue(exception.message!!.contains("MAX_TOKENS"))
    }

    @Test
    fun testIgnoresOutOfRangeIndicesRatherThanCrashing() = runBlocking {
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"index\":5,\"category\":\"GROCERIES\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val result = GeminiTransactionCategorizer(client, "fake-key").categorize(transactions)

        assertTrue(result.isEmpty())
    }

    @Test
    fun testSurfacesGeminiApiErrorBodyInsteadOfGenericNoCandidatesMessage() = runBlocking {
        // Reproduces a second real-world report: "Categorization failed:
        // Gemini returned no candidates" with no further detail. Root
        // cause was that a non-2xx response (Google's error body has no
        // "candidates" key) used to get decoded straight into
        // GeminiGenerateContentResponse anyway, where candidates defaults
        // to emptyList() - indistinguishable from a genuine empty success.
        // The actual error (here, an invalid API key) must now surface.
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = mockClientRespondingWith(
            """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""",
            HttpStatusCode.BadRequest
        )

        val exception = assertFailsWith<IllegalStateException> {
            GeminiTransactionCategorizer(client, "fake-key").categorize(transactions)
        }
        assertTrue(exception.message!!.contains("400"))
        assertTrue(exception.message!!.contains("API key not valid"))
    }

    @Test
    fun testMissingApiKeyThrowsBeforeMakingAnyRequest() = runBlocking {
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called without an API key") } }
        }

        val exception = assertFailsWith<IllegalStateException> {
            GeminiTransactionCategorizer(client, "").categorize(transactions)
        }
        assertTrue(exception.message!!.contains("GEMINI_API_KEY"))
    }
}
