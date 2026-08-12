package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.Base64

private val lenientJson = Json { ignoreUnknownKeys = true }

// Raw output of a single extraction pass, before it becomes a persisted
// HouseFact (HouseFactStore.kt) - the "MVP: House Knowledge" workflow's
// step 2 (extract things worth remembering) and step 3 (identify
// ambiguity) from product_spec.md, combined into one Gemini call.
data class ExtractedFact(
    val what: String,
    val type: FactType,
    val component: Component,
    // Blank sourceQuote/reviewQuestion from Gemini are normalized to null
    // here - see GeminiHouseFactExtractor.extract().
    val sourceQuote: String?,
    val needsReview: Boolean,
    val reviewQuestion: String?
)

interface HouseFactExtractor {
    suspend fun extract(filename: String, pdfBytes: ByteArray): List<ExtractedFact>
}

// DTOs below are prefixed FactExtraction* rather than reusing
// GeminiCategorizer.kt's Gemini*/CategorizedItem names - Kotlin's top-level
// `private` is file-scoped for name *resolution*, but the JVM class each
// one compiles to is still a plain, package-unqualified class, so two
// files in this package privately declaring e.g. `GeminiRequest` collide
// at the bytecode level even though neither file can see the other's
// declaration.
class GeminiHouseFactExtractor(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : HouseFactExtractor {

    override suspend fun extract(filename: String, pdfBytes: ByteArray): List<ExtractedFact> {
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        // Gemini's generateContent caps total (JSON-encoded) request size
        // around 20MB; base64 inflates raw bytes by ~33%, and the prompt
        // text adds a little more on top. A clear error here beats a
        // request that fails deep inside the HTTP call with a status code
        // that doesn't explain why - real inspection PDFs with embedded
        // photos can plausibly hit this. Files above the limit need the
        // Gemini File API's separate upload step instead (not implemented
        // yet - revisit if this actually bites on a real document).
        check(pdfBytes.size <= MAX_INLINE_BYTES) {
            "Document is too large to extract (${pdfBytes.size} bytes, limit $MAX_INLINE_BYTES) - " +
                "inline upload only, no File API support yet"
        }

        // Built as raw JsonElements (not the shared, encodeDefaults=true
        // geminiHttpClient config's typed defaults) so the file part never
        // emits a spurious "text": null, and the text part never emits a
        // spurious "inlineData": null - Gemini's Part message is a strict
        // oneof, and this codebase has already hit exactly this kind of
        // strictness once before (see CLAUDE.md's Gemini gotchas - the
        // missing responseSchema "type" fields). encodeDefaults is still
        // needed for the generationConfig/responseSchema below, so this
        // sidesteps the conflict rather than turning it off globally.
        val parts = listOf(
            lenientJson.encodeToJsonElement(
                FactExtractionInlineDataPart(FactExtractionInlineData("application/pdf", Base64.getEncoder().encodeToString(pdfBytes)))
            ),
            lenientJson.encodeToJsonElement(FactExtractionTextPart(buildPrompt(filename)))
        )

        val requestBody = FactExtractionRequest(
            contents = listOf(FactExtractionContent(parts)),
            generationConfig = FactExtractionGenerationConfig(
                // Same reasoning as GeminiTransactionCategorizer - this task
                // doesn't need extended reasoning, and letting it run
                // unbounded on a long document risks exhausting the output
                // token budget before any JSON is written.
                thinkingConfig = FactExtractionThinkingConfig(thinkingBudget = 0),
                responseSchema = FactExtractionSchema(
                    items = FactExtractionSchemaItem(
                        properties = mapOf(
                            "what" to FactExtractionSchemaProperty(type = "STRING"),
                            "type" to FactExtractionSchemaProperty(type = "STRING", enum = FactType.entries.map { it.name }),
                            "component" to FactExtractionSchemaProperty(type = "STRING", enum = Component.entries.map { it.name }),
                            "sourceQuote" to FactExtractionSchemaProperty(type = "STRING"),
                            "needsReview" to FactExtractionSchemaProperty(type = "BOOLEAN"),
                            "reviewQuestion" to FactExtractionSchemaProperty(type = "STRING")
                        ),
                        // All six required (rather than optional) so every
                        // item always has sourceQuote/reviewQuestion keys to
                        // read, even when empty - simpler decoding below,
                        // same "index"+"category" both-required choice
                        // GeminiTransactionCategorizer makes.
                        required = listOf("what", "type", "component", "sourceQuote", "needsReview", "reviewQuestion")
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
        // FactExtractionResponse.candidates defaults to emptyList() when
        // absent, so decoding an error response "succeeds" with zero
        // candidates unless the status is checked first.
        if (!httpResponse.status.isSuccess()) {
            error("Gemini API request failed (${httpResponse.status}): ${httpResponse.bodyAsText().take(500)}")
        }

        val response = httpResponse.body<FactExtractionResponse>()
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates (promptFeedback=${response.promptFeedback?.blockReason ?: "none"})")
        val text = candidate.content?.parts?.firstOrNull()?.text
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        val items = lenientJson.decodeFromString<List<ExtractedFactItem>>(text)
        return items.map { item ->
            ExtractedFact(
                what = item.what,
                // Falls back to UNKNOWN for a malformed/unrecognized type
                // value rather than dropping the fact - Unknown is already
                // a legitimate, meaningful fact type in this model (see
                // FactType), so this fallback loses nothing a dropped item
                // wouldn't have lost worse.
                type = runCatching { FactType.valueOf(item.type) }.getOrDefault(FactType.UNKNOWN),
                // Same "fall back rather than drop the fact" reasoning as
                // type above - OTHER is a legitimate catch-all component,
                // not an error state.
                component = runCatching { Component.valueOf(item.component) }.getOrDefault(Component.OTHER),
                sourceQuote = item.sourceQuote.trim().ifEmpty { null },
                needsReview = item.needsReview,
                reviewQuestion = item.reviewQuestion.trim().ifEmpty { null }
            )
        }
    }

    private fun buildPrompt(filename: String): String = """
        You are reading a home-related document ($filename) - a home inspection report, engineering/structural drawings, an invoice, or similar - for a household knowledge system.

        Extract the durable knowledge this document contributes to the household's understanding of the house.

        The goal is NOT to summarize the document or extract only its most notable specifications. Build a set of discrete facts that would still be useful to the homeowner, contractor, engineer, or AI assistant years from now.

        Each fact should be one specific, self-contained statement. Do not combine unrelated facts into a paragraph, but do preserve meaningful relationships between components when the relationship itself is durable knowledge.

        Extract facts about:

        - The physical characteristics of the house
        - Existing structural/mechanical/electrical components
        - Renovations, construction, repairs, replacements, and other historical events
        - New, proposed, removed, or modified components
        - Technical specifications and dimensions
        - Observed conditions and defects
        - Diagnoses or interpretations explicitly stated by the document
        - Assumptions used for engineering or design
        - Things the document explicitly says are unknown, unverified, inaccessible, or outside its scope
        - Document-specific maintenance requirements
        - Warranties and guarantees
        - Relationships between components, such as what supports what, what was replaced by what, or which component belongs to a particular renovation

        For renovation, engineering, and construction documents, distinguish between EXISTING, NEW/PROPOSED, and MODIFIED elements whenever the document makes that distinction.

        For technical drawings, do not limit extraction to individual specifications. Also capture the structural or construction system being designed: what was added or modified, where it is located when determinable, what supports it, what it supports, and how major components relate to one another.

        For engineering documents, preserve important assumptions and scope limitations. For example, distinguish between:
        - a value that was measured or verified
        - a value assumed for design purposes
        - a condition that was not investigated
        - an existing element explicitly outside the engineer's mandate

        Do not turn an assumption into a confirmed property of the house.

        Do not infer causes, conditions, or structural conclusions that are not supported by the document.

        For each fact, assign exactly one type:
        - OBSERVATION: something someone actually saw, measured, or documented
        - CONDITION: an ongoing physical characteristic
        - DIAGNOSIS: an explanation or interpretation explicitly stated by the document
        - DECISION: something the homeowner decided
        - EVENT: something that happened to the house (construction, renovation, repair)
        - SPECIFICATION: a known technical property or designed specification
        - MAINTENANCE_REQUIREMENT: something needing recurring attention
        - WARRANTY: a time-bound guarantee or coverage
        - ASSUMPTION: a value or condition assumed for purposes of design, calculation, or planning
        - UNKNOWN: something the document explicitly says is not known or could not be determined
        - SCOPE_LIMITATION: something the document explicitly says was outside the inspection, investigation, or professional mandate

        For each fact, also assign exactly one component of the house it's about:
        - FOUNDATION
        - STRUCTURE
        - EXTERIOR
        - ROOF
        - PLUMBING
        - ELECTRICAL
        - HVAC
        - SAFETY
        - OTHER: for anything that doesn't clearly belong to one of the above

        Set needsReview to true only when the document leaves a meaningful question that the homeowner may have personal knowledge about or where homeowner context could materially change the interpretation.

        Examples:
        - The document says the cause of a condition was not determined.
        - The document identifies an existing condition but the homeowner may know whether it predates a renovation.
        - The document says something was recommended for further investigation but the homeowner may know whether it was subsequently investigated or repaired.
        - A renovation document describes work but does not establish whether it was actually completed.

        Do NOT set needsReview merely because a fact is technical, unusual, or important.

        When needsReview is true, write a short, direct reviewQuestion asking the homeowner for concrete information that could resolve the uncertainty. When needsReview is false, leave reviewQuestion as an empty string.

        Include a short, verbatim sourceQuote from the document backing each fact where one exists; leave it as an empty string if the fact is your own summary rather than a direct quote.

        Return a JSON array with one object per fact.
    """.trimIndent()

    private companion object {
        const val MAX_INLINE_BYTES = 15_000_000
    }
}

@Serializable
private data class FactExtractionRequest(val contents: List<FactExtractionContent>, val generationConfig: FactExtractionGenerationConfig)

@Serializable
private data class FactExtractionContent(val parts: List<JsonElement>)

@Serializable
private data class FactExtractionTextPart(val text: String)

@Serializable
private data class FactExtractionInlineDataPart(val inlineData: FactExtractionInlineData)

@Serializable
private data class FactExtractionInlineData(val mimeType: String, val data: String)

@Serializable
private data class FactExtractionGenerationConfig(
    val responseMimeType: String = "application/json",
    val responseSchema: FactExtractionSchema,
    val thinkingConfig: FactExtractionThinkingConfig
)

@Serializable
private data class FactExtractionThinkingConfig(val thinkingBudget: Int)

@Serializable
private data class FactExtractionSchema(val type: String = "ARRAY", val items: FactExtractionSchemaItem)

@Serializable
private data class FactExtractionSchemaItem(
    val type: String = "OBJECT",
    val properties: Map<String, FactExtractionSchemaProperty>,
    val required: List<String>
)

@Serializable
private data class FactExtractionSchemaProperty(val type: String, val enum: List<String>? = null)

@Serializable
private data class FactExtractionResponse(
    val candidates: List<FactExtractionCandidate> = emptyList(),
    val promptFeedback: FactExtractionPromptFeedback? = null
)

@Serializable
private data class FactExtractionCandidate(val content: FactExtractionResponseContent? = null, val finishReason: String? = null)

@Serializable
private data class FactExtractionResponseContent(val parts: List<FactExtractionResponsePart> = emptyList())

@Serializable
private data class FactExtractionResponsePart(val text: String? = null)

@Serializable
private data class FactExtractionPromptFeedback(val blockReason: String? = null)

@Serializable
private data class ExtractedFactItem(
    val what: String,
    val type: String,
    val component: String,
    val sourceQuote: String,
    val needsReview: Boolean,
    val reviewQuestion: String
)
