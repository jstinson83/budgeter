package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable

interface ComponentSummarizer {
    suspend fun summarize(component: Component, facts: List<HouseFact>): String
}

// Synthesizes one component's facts (across every document, not just one)
// into a short plain-language summary - a text-only Gemini call, no PDF and
// no responseSchema needed since the output is just a paragraph rather than
// structured JSON. Shares geminiHttpClient with GeminiTransactionCategorizer
// and the two-pass house fact extractors (HouseFactCandidateExtractor.kt,
// HouseFactNormalizer.kt), so it inherits the same status-before-decode and
// empty-candidates/blank-text safeguards those already had to learn the hard
// way - see CLAUDE.md's Gemini gotchas.
class GeminiComponentSummarizer(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : ComponentSummarizer {

    override suspend fun summarize(component: Component, facts: List<HouseFact>): String {
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        check(facts.isNotEmpty()) { "No facts to summarize for $component" }

        val requestBody = ComponentSummaryRequest(
            contents = listOf(ComponentSummaryContent(listOf(ComponentSummaryPart(buildPrompt(component, facts))))),
            generationConfig = ComponentSummaryGenerationConfig(
                thinkingConfig = ComponentSummaryThinkingConfig(thinkingBudget = 0)
            )
        )

        val httpResponse = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (!httpResponse.status.isSuccess()) {
            error("Gemini API request failed (${httpResponse.status}): ${httpResponse.bodyAsText().take(500)}")
        }

        val response = httpResponse.body<ComponentSummaryResponse>()
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates (promptFeedback=${response.promptFeedback?.blockReason ?: "none"})")
        val text = candidate.content?.parts?.firstOrNull()?.text
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        return text.trim()
    }

    private fun buildPrompt(component: Component, facts: List<HouseFact>): String {
        val factLines = facts.joinToString("\n") { fact ->
            buildString {
                append("- [${fact.type.name}] ${fact.what}")
                fact.sourceQuote?.let { append(" (source: \"$it\")") }
                fact.homeownerContext?.let { append(" (homeowner: \"$it\")") }
                if (fact.needsReview) append(" (still needs homeowner review)")
            }
        }
        return """
            You are summarizing everything currently known about the $component of a house, for the homeowner, based on facts already extracted from their documents (inspections, drawings, invoices, etc.).

            Facts:
            $factLines

            Write a short summary (2-4 sentences, plain language, no bullet points) synthesizing what's known about this component. Mention any notable unresolved questions or maintenance needs if the facts include them. Do not invent anything the facts don't support.
        """.trimIndent()
    }
}

@Serializable
private data class ComponentSummaryRequest(val contents: List<ComponentSummaryContent>, val generationConfig: ComponentSummaryGenerationConfig)

@Serializable
private data class ComponentSummaryContent(val parts: List<ComponentSummaryPart>)

@Serializable
private data class ComponentSummaryPart(val text: String)

@Serializable
private data class ComponentSummaryGenerationConfig(val thinkingConfig: ComponentSummaryThinkingConfig)

@Serializable
private data class ComponentSummaryThinkingConfig(val thinkingBudget: Int)

@Serializable
private data class ComponentSummaryResponse(
    val candidates: List<ComponentSummaryCandidate> = emptyList(),
    val promptFeedback: ComponentSummaryPromptFeedback? = null
)

@Serializable
private data class ComponentSummaryCandidate(val content: ComponentSummaryResponseContent? = null, val finishReason: String? = null)

@Serializable
private data class ComponentSummaryResponseContent(val parts: List<ComponentSummaryResponsePart> = emptyList())

@Serializable
private data class ComponentSummaryResponsePart(val text: String? = null)

@Serializable
private data class ComponentSummaryPromptFeedback(val blockReason: String? = null)
