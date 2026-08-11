package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

interface TransactionCategorizer {
    // categories is the owner's own active category set (see
    // CategoryStore.kt) - the allowed output values are now per-owner and
    // dynamic rather than a single fixed enum, so the caller supplies them
    // on every call instead of this interface hardcoding a list. TRANSFER
    // is never among them: it isn't a real Category row (see
    // CategoryStore.kt), so there's nothing to explicitly exclude here.
    //
    // Keyed by transaction id. A transaction Gemini couldn't confidently
    // place, or that dropped out of a malformed/truncated response, is
    // simply absent from the result rather than defaulted to OTHER - it
    // stays uncategorized and gets picked up again by the next button press
    // (TransactionRepository.uncategorized), rather than being silently
    // mis-filed.
    suspend fun categorize(transactions: List<Transaction>, categories: List<Category>): Map<String, String>
}

class GeminiTransactionCategorizer(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : TransactionCategorizer {

    override suspend fun categorize(transactions: List<Transaction>, categories: List<Category>): Map<String, String> {
        if (transactions.isEmpty()) return emptyMap()
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }

        // Chunked rather than one call for the whole batch - a big enough
        // pending list makes the JSON response itself (one object per
        // transaction) exceed the model's output token budget, which reads
        // as a MAX_TOKENS cutoff with no text part (see the finishReason
        // check below, and CLAUDE.md's Gemini categorization gotchas - this
        // already bit at ~120 transactions in one call). Each chunk is its
        // own request with its own local 0-based indices, so a batch this
        // size never happens again regardless of how many transactions are
        // pending overall.
        val result = mutableMapOf<String, String>()
        for (batch in transactions.chunked(BATCH_SIZE)) {
            result += categorizeBatch(batch, categories)
        }
        return result
    }

    private suspend fun categorizeBatch(transactions: List<Transaction>, categories: List<Category>): Map<String, String> {
        val requestBody = GeminiRequest(
            contents = listOf(GeminiContent(listOf(GeminiPart(buildPrompt(transactions))))),
            generationConfig = GeminiGenerationConfig(
                // Extended thinking is unnecessary overhead for a
                // straightforward classification task - and for a
                // large-ish batch, letting it run unbounded risks eating the
                // whole output-token budget on reasoning and leaving none
                // for the actual JSON, which silently looks like an empty
                // response rather than an error (see the finishReason check
                // below - this is exactly the failure mode that produced
                // it).
                thinkingConfig = GeminiThinkingConfig(thinkingBudget = 0),
                responseSchema = GeminiSchema(
                    items = GeminiSchemaItem(
                        properties = mapOf(
                            // Index into `transactions` rather than echoing
                            // back its (64-hex-char) id - shorter output per
                            // item (cheaper, less likely to exhaust the
                            // token budget on a big batch), and an index is
                            // either in range or it isn't, whereas a
                            // slightly-mistyped hash id would silently fail
                            // the old id-set membership check with no sign
                            // anything went wrong.
                            "index" to GeminiSchemaProperty(type = "INTEGER"),
                            // Built from the caller's own category list
                            // (CategoryStore.kt) rather than a fixed enum -
                            // TRANSFER is never in it since it isn't a real
                            // Category row to begin with (assigned only by
                            // TransferMatcher; transfer-matched transactions
                            // never reach this categorizer - see
                            // AnalysisRoutes.kt).
                            "category" to GeminiSchemaProperty(
                                type = "STRING",
                                enum = categories.map { it.id }
                            )
                        ),
                        required = listOf("index", "category")
                    )
                )
            )
        )

        val httpResponse = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        // Must check status before decoding: Gemini's error body
        // ({"error": {code, message, status}}) has no "candidates" key, and
        // GeminiGenerateContentResponse.candidates defaults to emptyList()
        // when that key is absent - so decoding an error response straight
        // into it "succeeds" with zero candidates, indistinguishable from a
        // real empty response. That swallowed the actual reason (bad key,
        // API not enabled, quota exceeded, malformed request, ...) behind a
        // generic "no candidates" message. Read the raw body for the error
        // case so whatever Google actually said reaches the /analysis
        // error banner.
        if (!httpResponse.status.isSuccess()) {
            error("Gemini API request failed (${httpResponse.status}): ${httpResponse.bodyAsText().take(500)}")
        }

        val response = httpResponse.body<GeminiGenerateContentResponse>()
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates (promptFeedback=${response.promptFeedback?.blockReason ?: "none"})")
        val text = candidate.content?.parts?.firstOrNull()?.text
        // A missing/blank text part with no exception is exactly how a
        // MAX_TOKENS cutoff (or a safety block) looks - surface it as a
        // real failure instead of quietly returning no categorizations,
        // which read at the call site as "0 of N categorized" with nothing
        // to indicate why.
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        val categoryIds = categories.map { it.id }.toSet()
        val items = lenientJson.decodeFromString<List<CategorizedItem>>(text)
        return items
            .mapNotNull { item ->
                val transaction = transactions.getOrNull(item.index) ?: return@mapNotNull null
                if (item.category !in categoryIds) return@mapNotNull null
                transaction.id to item.category
            }
            .toMap()
    }

    private fun buildPrompt(transactions: List<Transaction>): String = buildString {
        appendLine("You are categorizing personal bank transactions for a household budgeting app.")
        appendLine("Assign each transaction below exactly one category from the allowed list, based on its description and amount.")
        appendLine("Return a JSON array with one object per transaction, using its 0-based index exactly as given below (do not skip, reorder, or renumber).")
        appendLine()
        transactions.forEachIndexed { index, t -> appendLine("$index: ${t.description} (amount ${t.amount})") }
    }

    private companion object {
        const val BATCH_SIZE = 40
    }
}

@Serializable
private data class GeminiRequest(val contents: List<GeminiContent>, val generationConfig: GeminiGenerationConfig)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiGenerationConfig(
    val responseMimeType: String = "application/json",
    val responseSchema: GeminiSchema,
    val thinkingConfig: GeminiThinkingConfig
)

@Serializable
private data class GeminiThinkingConfig(val thinkingBudget: Int)

@Serializable
private data class GeminiSchema(val type: String = "ARRAY", val items: GeminiSchemaItem)

@Serializable
private data class GeminiSchemaItem(
    val type: String = "OBJECT",
    val properties: Map<String, GeminiSchemaProperty>,
    val required: List<String>
)

@Serializable
private data class GeminiSchemaProperty(val type: String, val enum: List<String>? = null)

@Serializable
private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiResponseCandidate> = emptyList(),
    val promptFeedback: GeminiPromptFeedback? = null
)

@Serializable
private data class GeminiResponseCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
private data class GeminiPromptFeedback(val blockReason: String? = null)

@Serializable
private data class CategorizedItem(val index: Int, val category: String)
