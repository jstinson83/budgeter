package com.budgeter

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val lenientNormalizerJson = Json { ignoreUnknownKeys = true }

interface HouseFactNormalizer {
    suspend fun normalize(candidates: List<ExtractedCandidate>): List<ExtractedFact>
}

// DTOs below are prefixed FactNormalization* for the same package-scoped
// private-class-name-collision reason explained in
// HouseFactCandidateExtractor.kt.
//
// Pass 2 of the two-pass extraction pipeline (see HouseFactExtractor.kt):
// takes pass 1's recall-oriented candidates and turns them into the final,
// concise set of durable house facts - removing redundancy, reconciling
// conflicting candidates, and assigning the persisted FactType/Component/
// FactStatus/Importance/EvidenceType taxonomy. Text-only Gemini call (no PDF
// - the source document was already read in pass 1), same pattern as
// GeminiComponentSummarizer.
class GeminiHouseFactNormalizer(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : HouseFactNormalizer {

    override suspend fun normalize(candidates: List<ExtractedCandidate>): List<ExtractedFact> {
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        // No candidates in means no facts out - matches
        // GeminiComponentSummarizer's "nothing to do" guard, and avoids a
        // Gemini call that would just get an empty array back anyway.
        if (candidates.isEmpty()) return emptyList()

        val requestBody = FactNormalizationRequest(
            contents = listOf(FactNormalizationContent(listOf(FactNormalizationPart(buildPrompt(candidates))))),
            generationConfig = FactNormalizationGenerationConfig(
                thinkingConfig = FactNormalizationThinkingConfig(thinkingBudget = 0),
                responseSchema = FactNormalizationSchema(
                    items = FactNormalizationSchemaItem(
                        properties = mapOf(
                            "statement" to FactNormalizationSchemaProperty(type = "STRING"),
                            "type" to FactNormalizationSchemaProperty(type = "STRING", enum = FactType.entries.map { it.name }),
                            "component" to FactNormalizationSchemaProperty(type = "STRING", enum = Component.entries.map { it.name }),
                            "status" to FactNormalizationSchemaProperty(type = "STRING", enum = FactStatus.entries.map { it.name }),
                            "importance" to FactNormalizationSchemaProperty(type = "STRING", enum = Importance.entries.map { it.name }),
                            "needsReview" to FactNormalizationSchemaProperty(type = "BOOLEAN"),
                            "reviewQuestion" to FactNormalizationSchemaProperty(type = "STRING"),
                            "sourceQuote" to FactNormalizationSchemaProperty(type = "STRING"),
                            "sourceLocation" to FactNormalizationSchemaProperty(type = "STRING"),
                            "evidenceType" to FactNormalizationSchemaProperty(type = "STRING", enum = EvidenceType.entries.map { it.name })
                        ),
                        // All ten required so every item always has every
                        // key to read, even when empty - same "simpler
                        // decoding" choice the rest of this codebase's
                        // Gemini callers make.
                        required = listOf(
                            "statement", "type", "component", "status", "importance",
                            "needsReview", "reviewQuestion", "sourceQuote", "sourceLocation", "evidenceType"
                        )
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
        // FactNormalizationResponse.candidates defaults to emptyList() when
        // absent, so decoding an error response "succeeds" with zero
        // candidates unless the status is checked first.
        if (!httpResponse.status.isSuccess()) {
            error("Gemini API request failed (${httpResponse.status}): ${httpResponse.bodyAsText().take(500)}")
        }

        val response = httpResponse.body<FactNormalizationResponse>()
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates (promptFeedback=${response.promptFeedback?.blockReason ?: "none"})")
        val text = candidate.content?.parts?.firstOrNull()?.text
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        val items = lenientNormalizerJson.decodeFromString<List<FactNormalizationItem>>(text)
        return items.map { item ->
            ExtractedFact(
                what = item.statement,
                // Falls back to UNKNOWN/OTHER/MEDIUM for a malformed/
                // unrecognized value rather than dropping the fact - same
                // "fall back rather than drop" reasoning the rest of this
                // codebase's Gemini callers use.
                type = runCatching { FactType.valueOf(item.type) }.getOrDefault(FactType.UNKNOWN),
                component = runCatching { Component.valueOf(item.component) }.getOrDefault(Component.OTHER),
                status = runCatching { FactStatus.valueOf(item.status) }.getOrDefault(FactStatus.UNKNOWN),
                importance = runCatching { Importance.valueOf(item.importance) }.getOrDefault(Importance.MEDIUM),
                sourceQuote = item.sourceQuote.trim().ifEmpty { null },
                sourceLocation = item.sourceLocation.trim().ifEmpty { null },
                // No non-null fallback makes sense here - see
                // HouseFactStore.kt's HouseFact.evidenceType comment.
                evidenceType = runCatching { EvidenceType.valueOf(item.evidenceType) }.getOrNull(),
                needsReview = item.needsReview,
                reviewQuestion = item.reviewQuestion.trim().ifEmpty { null }
            )
        }
    }

    private fun buildPrompt(candidates: List<ExtractedCandidate>): String {
        val candidateLines = candidates.mapIndexed { index, item ->
            buildString {
                append("${index + 1}. ${item.candidate}")
                append(" [status: ${item.status}, importance: ${item.importance}]")
                item.context?.let { append("\n   context: $it") }
                item.sourceQuote?.let { append("\n   quote: \"$it\"") }
                item.sourceLocation?.let { append("\n   location: $it") }
            }
        }.joinToString("\n")

        return """
            You are building the persistent knowledge model of a house.

            You are given candidate knowledge extracted from one or more home-related documents.

            Candidates:
            $candidateLines

            Your job is to turn those candidates into a concise set of durable, high-value house facts.

            The goal is NOT to summarize the documents.

            The goal is to decide what Home OS should remember about this house years from now.

            Reason across all supplied candidates and identify relationships between them. Remove incidental information, merge redundant candidates, and preserve important relationships.

            A good final fact should answer:

            > "Would this be useful to know about this house in five or ten years?"

            Prioritize facts that could affect:

            - Future renovation or repair decisions
            - Structural understanding
            - Maintenance
            - Diagnosing future problems
            - Understanding previous work
            - Understanding the current physical state of the house
            - Understanding what is unknown
            - Safety
            - Resale or disclosure
            - Future AI reasoning about the property

            ## Evidence and epistemic rules

            Distinguish carefully between:

            1. What the document explicitly observed or measured
            2. What the document establishes exists
            3. What was designed or specified
            4. What was assumed for purposes of design or calculation
            5. What a professional concluded or diagnosed
            6. What was recommended
            7. What was explicitly not investigated or verified
            8. What remains unknown
            9. What actually happened as part of a renovation, repair, or replacement

            For renovation, engineering, architectural, and construction documents, assume that documented renovation or construction work was completed unless the supplied evidence indicates that the work was proposed, optional, cancelled, removed, or otherwise not completed.

            Do not unnecessarily downgrade completed renovation work to "proposed" merely because its source is a drawing.

            For example, if the evidence is a set of structural drawings documenting the scope of a renovation, prefer:

            > "The 2017 structural renovation introduced a W10x30 steel beam."

            rather than:

            > "The 2017 structural drawings proposed a W10x30 steel beam."

            However, preserve uncertainty if the evidence itself indicates that completion is uncertain.

            Do not turn an engineering assumption into a confirmed property of the house.

            For example:

            BAD:
            > "The soil has a bearing capacity of 1,500 psf."

            when the engineering document only says that 1,500 psf was assumed for design.

            BETTER:
            > "The footing design assumes a net soil bearing capacity of 1,500 psf."

            Likewise, do not turn a recommendation into evidence that the work was performed.

            ## Reconcile information across documents

            Multiple documents may describe the same component, event, condition, or renovation.

            Combine them when doing so creates a more useful and accurate understanding of the house.

            For example:

            Engineering plans:
            > A W10x30 beam was designed as part of the 2017 renovation.

            Inspection:
            > Steel structural columns were observed in 2022.

            Homeowner:
            > The renovation was completed in 2017.

            These can contribute to a coherent fact such as:

            > "The 2017 structural renovation introduced a W10x30 beam supported by steel structural columns."

            Preserve the individual evidence supporting the conclusion.

            If documents appear to contradict one another, do not silently choose one.

            Create a fact representing the conflict or uncertainty and preserve both sources.

            For example:

            > "The 2017 plans specify a W10x30 beam, while a later document describes a different beam at the same location; the current beam specification is uncertain."

            ## Preserve meaningful relationships

            Do not reduce meaningful systems to disconnected component specifications.

            If the evidence establishes that:

            - A beam supports a floor
            - Columns support the beam
            - Footings support the columns

            then preserve those relationships in the relevant facts.

            Likewise preserve relationships such as:

            - Component A was replaced by component B
            - Component A was modified during renovation X
            - Condition A predates renovation X
            - Repair A addressed condition B
            - Component A is located beneath, within, or adjacent to component B
            - Document A confirms or provides additional evidence about something described in document B

            Create separate facts when the individual pieces are independently useful, but do not lose the relationship connecting them.

            ## Fact types

            Assign exactly one type to each final fact:

            - OBSERVATION: something someone actually saw, measured, or documented
            - CONDITION: an ongoing physical characteristic of the house
            - DIAGNOSIS: an explanation or interpretation explicitly stated by a professional or other source
            - DECISION: a decision made by the homeowner
            - EVENT: something that happened to the house, such as construction, renovation, repair, or replacement
            - SPECIFICATION: a technical property, dimension, material, capacity, or design specification explicitly stated by the document
            - MAINTENANCE_REQUIREMENT: a recurring maintenance requirement specific to the house
            - WARRANTY: a time-bound guarantee or coverage
            - ASSUMPTION: a value or condition assumed for design, calculation, or planning
            - UNKNOWN: something explicitly stated to be unknown or not determined
            - SCOPE_LIMITATION: something explicitly stated to be outside an inspection, investigation, or professional mandate

            ## House components

            Assign exactly one primary component:

            - FOUNDATION
            - STRUCTURE
            - EXTERIOR
            - ROOF
            - PLUMBING
            - ELECTRICAL
            - HVAC
            - SAFETY
            - OTHER

            If a fact concerns multiple components, choose the component most directly affected and preserve the relationship in the statement.

            ## Status

            Assign exactly one status:

            - EXISTING
            - NEW
            - MODIFIED
            - REMOVED
            - PROPOSED
            - UNKNOWN
            - NOT_APPLICABLE

            For historical events, use the status of the resulting physical work when meaningful.

            Do not use PROPOSED when the supplied evidence establishes that the work was completed.

            ## Review questions

            Set needsReview to true only when homeowner knowledge could materially improve or change the interpretation of the fact.

            Examples:

            - A condition's cause is unknown and the homeowner may know its history.
            - A condition may predate a renovation.
            - A recommended repair may have subsequently been completed.
            - A renovation document describes work but the supplied evidence does not establish whether it was completed.
            - The document describes an existing condition whose current status is likely known by the homeowner.
            - Multiple sources disagree and the homeowner may know which reflects the current house.

            Do NOT request review simply because something is technical, unusual, important, or uncertain in a purely professional sense.

            When needsReview is true, write one short question asking for concrete information that could resolve the uncertainty.

            Good:

            > "Do you know when this condition first appeared?"

            Good:

            > "Was this work completed?"

            Good:

            > "Was this repaired after the inspection?"

            Bad:

            > "What do you think about this?"

            Bad:

            > "Can you provide more context?"

            ## Provenance

            Every final fact must preserve its evidence.

            Include:

            - sourceQuote: a short verbatim quote from the supporting document, when available
            - sourceLocation: page, drawing number, section, or other useful source location when available
            - evidenceType: one of DOCUMENTED, MEASURED, OBSERVED, DESIGNED, ASSUMED, REPORTED, or INFERRED

            If a fact is supported by multiple candidates, include the relevant sources.

            If a fact is a synthesis across multiple pieces of evidence, preserve enough provenance to understand why Home OS believes it.

            Do not fabricate quotes.

            Do not present an inference as if it were directly stated by a source.

            ## Importance

            Assign exactly one:

            - HIGH: likely to materially affect future house decisions, repairs, renovations, safety, resale, or understanding of the building
            - MEDIUM: useful durable knowledge but unlikely to drive major decisions
            - LOW: technically true but relatively minor

            Prefer a smaller set of meaningful facts over a large set of trivial facts.

            ## Final filtering

            Before returning a fact, ask:

            > Would this still be useful to know about this house in five or ten years?

            If not, omit it.

            Do not include:

            - Document metadata
            - Addresses merely repeated from documents
            - Drawing numbers
            - Page numbers as facts
            - Administrative details
            - Incidental measurements with no durable relevance
            - Generic professional disclaimers
            - Generic advice
            - Redundant facts that add no meaningful information

            Do include important assumptions, unknowns, scope limitations, historical events, and relationships when they materially contribute to understanding the house.

            ## Output

            Return a JSON array with one object per final fact.

            Each object must contain exactly these fields:

            - statement
            - type
            - component
            - status
            - importance
            - needsReview
            - reviewQuestion
            - sourceQuote
            - sourceLocation
            - evidenceType

            Do not add facts that are not supported by the supplied candidates or their sources.
        """.trimIndent()
    }
}

@Serializable
private data class FactNormalizationRequest(val contents: List<FactNormalizationContent>, val generationConfig: FactNormalizationGenerationConfig)

@Serializable
private data class FactNormalizationContent(val parts: List<FactNormalizationPart>)

@Serializable
private data class FactNormalizationPart(val text: String)

@Serializable
private data class FactNormalizationGenerationConfig(
    val responseMimeType: String = "application/json",
    val responseSchema: FactNormalizationSchema,
    val thinkingConfig: FactNormalizationThinkingConfig
)

@Serializable
private data class FactNormalizationThinkingConfig(val thinkingBudget: Int)

@Serializable
private data class FactNormalizationSchema(val type: String = "ARRAY", val items: FactNormalizationSchemaItem)

@Serializable
private data class FactNormalizationSchemaItem(
    val type: String = "OBJECT",
    val properties: Map<String, FactNormalizationSchemaProperty>,
    val required: List<String>
)

@Serializable
private data class FactNormalizationSchemaProperty(val type: String, val enum: List<String>? = null)

@Serializable
private data class FactNormalizationResponse(
    val candidates: List<FactNormalizationCandidate> = emptyList(),
    val promptFeedback: FactNormalizationPromptFeedback? = null
)

@Serializable
private data class FactNormalizationCandidate(val content: FactNormalizationResponseContent? = null, val finishReason: String? = null)

@Serializable
private data class FactNormalizationResponseContent(val parts: List<FactNormalizationResponsePart> = emptyList())

@Serializable
private data class FactNormalizationResponsePart(val text: String? = null)

@Serializable
private data class FactNormalizationPromptFeedback(val blockReason: String? = null)

@Serializable
private data class FactNormalizationItem(
    val statement: String,
    val type: String,
    val component: String,
    val status: String,
    val importance: String,
    val needsReview: Boolean,
    val reviewQuestion: String,
    val sourceQuote: String,
    val sourceLocation: String,
    val evidenceType: String
)
