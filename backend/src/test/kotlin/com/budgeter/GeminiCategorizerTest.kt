package com.budgeter

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.LocalDate
import kotlin.test.*

class GeminiCategorizerTest {
    private fun transaction(id: String, description: String, amount: Double): Transaction =
        Transaction(id, "owner", AccountType.BANK, LocalDate.of(2026, 1, 15), description, amount)

    private fun categories(vararg ids: String): List<Category> =
        ids.map { Category(it, "owner", it) }

    // encodeDefaults = true here mirrors Application.kt's real
    // geminiHttpClient config - see testRequestBodyIncludesSchemaTypeFields...
    // below for why that setting matters.
    private fun mockClientRespondingWith(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient = HttpClient(MockEngine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
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

        val result = GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES", "INCOME"))

        assertEquals("GROCERIES", result["tx-1"])
        assertEquals("INCOME", result["tx-2"])
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
            GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES"))
        }
        assertTrue(exception.message!!.contains("MAX_TOKENS"))
    }

    @Test
    fun testIgnoresOutOfRangeIndicesRatherThanCrashing() = runBlocking {
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = mockClientRespondingWith(
            """{"candidates":[{"content":{"parts":[{"text":"[{\"index\":5,\"category\":\"GROCERIES\"}]"}]},"finishReason":"STOP"}]}"""
        )

        val result = GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES"))

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
            GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES"))
        }
        assertTrue(exception.message!!.contains("400"))
        assertTrue(exception.message!!.contains("API key not valid"))
    }

    @Test
    fun testRequestBodyIncludesSchemaTypeFieldsDespiteBeingKotlinDefaultValues() = runBlocking {
        // Reproduces a third real-world report: "field predicate failed:
        // $type == Type.ARRAY" / "only allowed for OBJECT type". Root
        // cause: GeminiSchema.type ("ARRAY") and GeminiSchemaItem.type
        // ("OBJECT") are Kotlin default values, and kotlinx.serialization
        // omits any field left at its default unless encodeDefaults is
        // set - so without it, those "type" fields (and
        // responseMimeType) never actually reached Gemini, and the
        // request 400'd. Assert on the literal outgoing JSON so a future
        // change to the Json config can't silently reintroduce this.
        var capturedRequestBody: String? = null
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            engine {
                addHandler { request ->
                    capturedRequestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"candidates":[{"content":{"parts":[{"text":"[{\"index\":0,\"category\":\"GROCERIES\"}]"}]},"finishReason":"STOP"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }

        GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES"))

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains(""""type":"ARRAY""""), "expected the outer response schema's type to be present: $body")
        assertTrue(body.contains(""""type":"OBJECT""""), "expected the item schema's type to be present: $body")
        assertTrue(body.contains(""""responseMimeType":"application/json""""), "expected responseMimeType to be present: $body")
    }

    @Test
    fun testSchemaEnumReflectsExactlyTheGivenCategoriesNothingHardcoded() = runBlocking {
        // Categories are per-owner and dynamic now (CategoryStore.kt), not
        // a fixed enum the categorizer knows about - the schema's allowed
        // values must come entirely from what the caller passes in.
        // TRANSFER in particular is never a real Category row (assigned
        // only by TransferMatcher), so a caller would never pass it, and it
        // must not leak in some other way.
        var capturedRequestBody: String? = null
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            engine {
                addHandler { request ->
                    capturedRequestBody = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    respond(
                        """{"candidates":[{"content":{"parts":[{"text":"[{\"index\":0,\"category\":\"GROCERIES\"}]"}]},"finishReason":"STOP"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }

        GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES", "DINING_OUT"))

        val body = assertNotNull(capturedRequestBody)
        assertTrue(body.contains("GROCERIES"))
        assertTrue(body.contains("DINING_OUT"))
        assertFalse(body.contains("TRANSFER"), "expected TRANSFER to never appear in the enum sent to Gemini: $body")
        assertFalse(body.contains("INCOME"), "expected only the given categories to appear in the enum sent to Gemini: $body")
    }

    @Test
    fun testSplitsALargeBatchIntoMultipleRequestsAndMergesTheResults() = runBlocking {
        // Reproduces the real-world failure mode this fixes: sending every
        // pending transaction in one Gemini call risks the JSON response
        // itself exhausting the model's output-token budget once the batch
        // is big enough (see CLAUDE.md's Gemini categorization gotchas,
        // and the MAX_TOKENS test above). 85 transactions is more than
        // twice GeminiTransactionCategorizer's internal batch size, so this
        // must come back as multiple requests, each with its own local
        // 0-based indices, merged into one result keyed by transaction id.
        val transactions = (0 until 85).map { transaction("tx-$it", "Purchase $it", -it.toDouble()) }
        var requestCount = 0
        val client = HttpClient(MockEngine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            engine {
                addHandler { request ->
                    requestCount++
                    val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                    // Count how many transactions this particular chunk's
                    // prompt actually lists, so the response only echoes
                    // back indices this chunk itself sent - a batch size
                    // change shouldn't require this test to know the exact
                    // number.
                    val itemCount = Regex("""\d+: Purchase \d+""").findAll(body).count()
                    val items = (0 until itemCount).joinToString(",") { """{\"index\":$it,\"category\":\"GROCERIES\"}""" }
                    respond(
                        """{"candidates":[{"content":{"parts":[{"text":"[$items]"}]},"finishReason":"STOP"}]}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    )
                }
            }
        }

        val result = GeminiTransactionCategorizer(client, "fake-key").categorize(transactions, categories("GROCERIES"))

        assertTrue(requestCount > 1, "expected the 85-transaction batch to be split across multiple requests, got $requestCount")
        assertEquals(85, result.size)
        assertEquals("GROCERIES", result["tx-0"])
        assertEquals("GROCERIES", result["tx-84"])
    }

    @Test
    fun testMissingApiKeyThrowsBeforeMakingAnyRequest() = runBlocking {
        val transactions = listOf(transaction("tx-1", "Metro Grocery", -42.10))
        val client = HttpClient(MockEngine) {
            engine { addHandler { error("Gemini should not be called without an API key") } }
        }

        val exception = assertFailsWith<IllegalStateException> {
            GeminiTransactionCategorizer(client, "").categorize(transactions, categories("GROCERIES"))
        }
        assertTrue(exception.message!!.contains("GEMINI_API_KEY"))
    }
}
