package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientJson = Json { ignoreUnknownKeys = true }

// Fixed, small category set rather than something Gemini invents per call -
// keeps the analysis screen's grouping stable across categorization runs.
// Covers the maintainer's examples (groceries, alcohol, entertainment,
// mortgage, house expenses) plus the handful of other buckets a personal
// household budget obviously needs.
enum class TransactionCategory(val label: String) {
    GROCERIES("Groceries"),
    ALCOHOL("Alcohol"),
    DINING_OUT("Dining Out"),
    ENTERTAINMENT("Entertainment"),
    MORTGAGE("Mortgage"),
    HOUSE_EXPENSES("House Expenses"),
    UTILITIES("Utilities"),
    TRANSPORTATION("Transportation"),
    HEALTH("Health"),
    SUBSCRIPTIONS("Subscriptions"),
    INCOME("Income"),
    OTHER("Other")
}

interface TransactionCategorizer {
    // Keyed by transaction id. A transaction Gemini couldn't confidently
    // place, or that dropped out of a malformed/truncated response, is
    // simply absent from the result rather than defaulted to OTHER - it
    // stays uncategorized and gets picked up again by the next button press
    // (TransactionRepository.uncategorized), rather than being silently
    // mis-filed.
    suspend fun categorize(transactions: List<Transaction>): Map<String, TransactionCategory>
}

class GeminiTransactionCategorizer(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-2.5-flash"
) : TransactionCategorizer {

    override suspend fun categorize(transactions: List<Transaction>): Map<String, TransactionCategory> {
        if (transactions.isEmpty()) return emptyMap()
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }

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
                            "category" to GeminiSchemaProperty(type = "STRING", enum = TransactionCategory.entries.map { it.name })
                        ),
                        required = listOf("index", "category")
                    )
                )
            )
        )

        val response = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }.body<GeminiGenerateContentResponse>()

        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates")
        val text = candidate.content?.parts?.firstOrNull()?.text
        // A missing/blank text part with no exception is exactly how a
        // MAX_TOKENS cutoff (or a safety block) looks - surface it as a
        // real failure instead of quietly returning no categorizations,
        // which read at the call site as "0 of N categorized" with nothing
        // to indicate why.
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        val items = lenientJson.decodeFromString<List<CategorizedItem>>(text)
        return items
            .mapNotNull { item ->
                val transaction = transactions.getOrNull(item.index) ?: return@mapNotNull null
                runCatching { TransactionCategory.valueOf(item.category) }.getOrNull()?.let { transaction.id to it }
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
private data class GeminiGenerateContentResponse(val candidates: List<GeminiResponseCandidate> = emptyList())

@Serializable
private data class GeminiResponseCandidate(val content: GeminiContent? = null, val finishReason: String? = null)

@Serializable
private data class CategorizedItem(val index: Int, val category: String)
