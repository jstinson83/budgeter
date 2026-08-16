package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientRecommendationJson = Json { ignoreUnknownKeys = true }

// Gemini's raw output for one recommendation, before supportingFactIndices
// gets resolved back to real HouseFact ids - see
// GeminiRecommendationGenerator.generate().
data class GeneratedRecommendation(
    val name: String,
    val rationale: String,
    val supportingFactIds: List<String>,
    val suggestedPriority: Priority
)

interface RecommendationGenerator {
    // activeAndPlannedProjects/deprioritizedProjects are the household's
    // whole project list, not scoped to `component` - a project's
    // component tag is just a browsing convenience (ProjectStore.kt), not a
    // hard partition, so avoiding a duplicate recommendation needs the full
    // picture regardless of which component bucket an existing project
    // happens to sit under. pastRecommendations should already be filtered
    // to this component by the caller (RecommendationJobManager) - every
    // recommendation ever surfaced for it, regardless of status, so
    // something already pending review isn't suggested a second time.
    suspend fun generate(
        component: Component,
        facts: List<HouseFact>,
        activeAndPlannedProjects: List<Project>,
        deprioritizedProjects: List<Project>,
        pastRecommendations: List<Recommendation>
    ): List<GeneratedRecommendation>
}

// Text-only Gemini call (no PDF, no document context - reasons purely over
// already-extracted HouseFacts), same pattern as GeminiComponentSummarizer/
// GeminiHouseFactNormalizer. Shares geminiHttpClient, so it inherits the
// same status-before-decode and empty-output safeguards those already had
// to learn the hard way - see CLAUDE.md's Gemini gotchas.
class GeminiRecommendationGenerator(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : RecommendationGenerator {

    override suspend fun generate(
        component: Component,
        facts: List<HouseFact>,
        activeAndPlannedProjects: List<Project>,
        deprioritizedProjects: List<Project>,
        pastRecommendations: List<Recommendation>
    ): List<GeneratedRecommendation> {
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        if (facts.isEmpty()) return emptyList()

        val requestBody = RecommendationRequest(
            contents = listOf(
                RecommendationContent(
                    listOf(RecommendationPart(buildPrompt(component, facts, activeAndPlannedProjects, deprioritizedProjects, pastRecommendations)))
                )
            ),
            generationConfig = RecommendationGenerationConfig(
                thinkingConfig = RecommendationThinkingConfig(thinkingBudget = 0),
                responseSchema = RecommendationSchema(
                    items = RecommendationSchemaItem(
                        properties = mapOf(
                            "name" to RecommendationSchemaProperty(type = "STRING"),
                            "rationale" to RecommendationSchemaProperty(type = "STRING"),
                            "supportingFactIndices" to RecommendationSchemaProperty(
                                type = "ARRAY",
                                items = RecommendationSchemaProperty(type = "INTEGER")
                            ),
                            "suggestedPriority" to RecommendationSchemaProperty(type = "STRING", enum = Priority.entries.map { it.name })
                        ),
                        required = listOf("name", "rationale", "supportingFactIndices", "suggestedPriority")
                    )
                )
            )
        )

        val httpResponse = httpClient.post("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent") {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        // Must check status before decoding - see CLAUDE.md's Gemini
        // gotchas: an error body has no "candidates" key, and
        // RecommendationResponse.candidates defaults to emptyList() when
        // absent, so decoding an error response "succeeds" with zero
        // candidates unless the status is checked first.
        if (!httpResponse.status.isSuccess()) {
            error("Gemini API request failed (${httpResponse.status}): ${httpResponse.bodyAsText().take(500)}")
        }

        val response = httpResponse.body<RecommendationResponse>()
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates (promptFeedback=${response.promptFeedback?.blockReason ?: "none"})")
        val text = candidate.content?.parts?.firstOrNull()?.text
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        val items = lenientRecommendationJson.decodeFromString<List<RecommendationItem>>(text)
        return items.mapNotNull { item ->
            val factIds = item.supportingFactIndices.mapNotNull { index -> facts.getOrNull(index)?.id }
            // An out-of-range index (or Gemini returning none at all) means
            // this recommendation isn't actually grounded in anything real
            // - dropped rather than persisted with no provenance to show.
            if (factIds.isEmpty()) return@mapNotNull null
            GeneratedRecommendation(
                name = item.name.trim(),
                rationale = item.rationale.trim(),
                supportingFactIds = factIds,
                suggestedPriority = runCatching { Priority.valueOf(item.suggestedPriority) }.getOrDefault(Priority.MEDIUM)
            )
        }
    }

    private fun buildPrompt(
        component: Component,
        facts: List<HouseFact>,
        activeAndPlannedProjects: List<Project>,
        deprioritizedProjects: List<Project>,
        pastRecommendations: List<Recommendation>
    ): String {
        val factLines = facts.mapIndexed { index, fact ->
            buildString {
                append("$index. [${fact.type.name}] ${fact.what}")
                fact.sourceQuote?.let { append(" (source: \"$it\")") }
                fact.homeownerContext?.let { append(" (homeowner: \"$it\")") }
            }
        }.joinToString("\n")

        return """
            You are analyzing what is currently known about the $component of a house to suggest possible home improvement or maintenance projects, for the homeowner.

            Facts about $component (0-based index, use these indices in your response):
            $factLines
${projectListBlock("Projects already active or planned (any component of the house) - do not suggest anything that duplicates or substantially overlaps with these", activeAndPlannedProjects)}
${projectListBlock("Projects the household has deliberately deprioritized (any component) - do not suggest these again unless the facts above indicate something materially different from what was deprioritized", deprioritizedProjects)}
${recommendationListBlock(pastRecommendations)}
            Your job: suggest a small, prioritized set of project recommendations for $component based on the facts above.

            Only suggest something with real support from the facts - do not invent speculative "nice to have" ideas the facts don't back up. Prefer a small set of meaningful recommendations over padding the list; return an empty array if there is nothing worth recommending.

            For each recommendation, provide:
            - name: a short, concrete project name (e.g. "Replace aging roof shingles", not "Roof maintenance")
            - rationale: 1-3 sentences explaining why this is recommended, referencing what the facts establish
            - supportingFactIndices: the 0-based indices (from the numbered list above) of every fact that supports this recommendation - every recommendation must be grounded in at least one real fact, never an empty list
            - suggestedPriority: HIGH, MEDIUM, or LOW based on urgency/importance
        """.trimIndent()
    }

    private fun projectListBlock(heading: String, projects: List<Project>): String {
        if (projects.isEmpty()) return ""
        val lines = projects.joinToString("\n") { "- \"${it.name}\" (${it.component}, ${it.priority} priority)" }
        return "\n            $heading:\n            $lines\n"
    }

    private fun recommendationListBlock(recommendations: List<Recommendation>): String {
        if (recommendations.isEmpty()) return ""
        val lines = recommendations.joinToString("\n") { "- \"${it.name}\": ${it.rationale} (${it.status})" }
        return "\n            Recommendations already surfaced for this component before (pending, accepted, or rejected) - do not repeat these:\n            $lines\n"
    }
}

@Serializable
private data class RecommendationRequest(val contents: List<RecommendationContent>, val generationConfig: RecommendationGenerationConfig)

@Serializable
private data class RecommendationContent(val parts: List<RecommendationPart>)

@Serializable
private data class RecommendationPart(val text: String)

@Serializable
private data class RecommendationGenerationConfig(
    val responseMimeType: String = "application/json",
    val responseSchema: RecommendationSchema,
    val thinkingConfig: RecommendationThinkingConfig
)

@Serializable
private data class RecommendationThinkingConfig(val thinkingBudget: Int)

@Serializable
private data class RecommendationSchema(val type: String = "ARRAY", val items: RecommendationSchemaItem)

@Serializable
private data class RecommendationSchemaItem(
    val type: String = "OBJECT",
    val properties: Map<String, RecommendationSchemaProperty>,
    val required: List<String>
)

@Serializable
private data class RecommendationSchemaProperty(
    val type: String,
    val enum: List<String>? = null,
    // Only set (and only meaningful) when type == "ARRAY" -
    // supportingFactIndices is the one property that needs it.
    val items: RecommendationSchemaProperty? = null
)

@Serializable
private data class RecommendationResponse(
    val candidates: List<RecommendationCandidate> = emptyList(),
    val promptFeedback: RecommendationPromptFeedback? = null
)

@Serializable
private data class RecommendationCandidate(val content: RecommendationResponseContent? = null, val finishReason: String? = null)

@Serializable
private data class RecommendationResponseContent(val parts: List<RecommendationResponsePart> = emptyList())

@Serializable
private data class RecommendationResponsePart(val text: String? = null)

@Serializable
private data class RecommendationPromptFeedback(val blockReason: String? = null)

@Serializable
private data class RecommendationItem(
    val name: String,
    val rationale: String,
    val supportingFactIndices: List<Int>,
    val suggestedPriority: String
)
