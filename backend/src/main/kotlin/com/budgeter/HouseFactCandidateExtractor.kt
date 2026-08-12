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

private val lenientCandidateJson = Json { ignoreUnknownKeys = true }

// The first pass's broader status vocabulary (see HouseFactExtractor.kt's
// two-pass design comment) - a candidate can be ASSUMED before the second
// pass has decided whether it becomes a first-class ASSUMPTION-typed fact;
// FactStatus (HouseFactStore.kt) drops ASSUMED once a candidate has actually
// been normalized, since ASSUMPTION already lives on FactType by then.
enum class CandidateStatus { EXISTING, NEW, MODIFIED, REMOVED, PROPOSED, ASSUMED, UNKNOWN, NOT_APPLICABLE }

// Raw output of extraction pass 1 (recall-oriented candidate identification)
// - not persisted directly; HouseFactNormalizer.kt's pass 2 turns a batch of
// these into the final ExtractedFact list that actually gets stored.
data class ExtractedCandidate(
    val candidate: String,
    val context: String?,
    val sourceQuote: String?,
    val sourceLocation: String?,
    val status: CandidateStatus,
    val importance: Importance
)

interface HouseFactCandidateExtractor {
    suspend fun extractCandidates(filename: String, pdfBytes: ByteArray): List<ExtractedCandidate>
}

// DTOs below are prefixed CandidateExtraction* rather than reusing
// GeminiCategorizer.kt's/HouseFactNormalizer.kt's Gemini*/*Item names -
// Kotlin's top-level `private` is file-scoped for name *resolution*, but the
// JVM class each one compiles to is still a plain, package-unqualified
// class, so two files in this package privately declaring the same class
// name collide at the bytecode level even though neither file can see the
// other's declaration (see HouseFactExtractor.kt's history for where this
// was first hit).
//
// Pass 1 of the two-pass extraction pipeline (see HouseFactExtractor.kt):
// reads the whole document and identifies candidate knowledge, favoring
// recall over precision - HouseFactNormalizer.kt's pass 2 is what removes
// redundancy, reconciles conflicts, and decides what's actually worth
// keeping.
class GeminiHouseFactCandidateExtractor(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val model: String = "gemini-3.5-flash"
) : HouseFactCandidateExtractor {

    override suspend fun extractCandidates(filename: String, pdfBytes: ByteArray): List<ExtractedCandidate> {
        check(apiKey.isNotBlank()) { "GEMINI_API_KEY is not set" }
        // Same inline-upload size cap and reasoning as the original
        // single-pass extractor - see CLAUDE.md's House Knowledge gotchas.
        check(pdfBytes.size <= MAX_INLINE_BYTES) {
            "Document is too large to extract (${pdfBytes.size} bytes, limit $MAX_INLINE_BYTES) - " +
                "inline upload only, no File API support yet"
        }

        // Built as raw JsonElements, not the shared encodeDefaults=true
        // geminiHttpClient config's typed defaults - see CLAUDE.md's Gemini
        // gotchas (Part is a strict text-XOR-inlineData oneof).
        val parts = listOf(
            lenientCandidateJson.encodeToJsonElement(
                CandidateInlineDataPart(CandidateInlineData("application/pdf", Base64.getEncoder().encodeToString(pdfBytes)))
            ),
            lenientCandidateJson.encodeToJsonElement(CandidateTextPart(buildPrompt(filename)))
        )

        val requestBody = CandidateExtractionRequest(
            contents = listOf(CandidateExtractionContent(parts)),
            generationConfig = CandidateExtractionGenerationConfig(
                thinkingConfig = CandidateExtractionThinkingConfig(thinkingBudget = 0),
                responseSchema = CandidateExtractionSchema(
                    items = CandidateExtractionSchemaItem(
                        properties = mapOf(
                            "candidate" to CandidateExtractionSchemaProperty(type = "STRING"),
                            "context" to CandidateExtractionSchemaProperty(type = "STRING"),
                            "sourceQuote" to CandidateExtractionSchemaProperty(type = "STRING"),
                            "sourceLocation" to CandidateExtractionSchemaProperty(type = "STRING"),
                            "status" to CandidateExtractionSchemaProperty(type = "STRING", enum = CandidateStatus.entries.map { it.name }),
                            "importance" to CandidateExtractionSchemaProperty(type = "STRING", enum = Importance.entries.map { it.name })
                        ),
                        // All six required so every item always has every
                        // key to read, even when empty - same "simpler
                        // decoding" choice the rest of this codebase's
                        // Gemini callers make.
                        required = listOf("candidate", "context", "sourceQuote", "sourceLocation", "status", "importance")
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
        // CandidateExtractionResponse.candidates defaults to emptyList()
        // when absent, so decoding an error response "succeeds" with zero
        // candidates unless the status is checked first.
        if (!httpResponse.status.isSuccess()) {
            error("Gemini API request failed (${httpResponse.status}): ${httpResponse.bodyAsText().take(500)}")
        }

        val response = httpResponse.body<CandidateExtractionResponse>()
        val candidate = response.candidates.firstOrNull()
            ?: error("Gemini returned no candidates (promptFeedback=${response.promptFeedback?.blockReason ?: "none"})")
        val text = candidate.content?.parts?.firstOrNull()?.text
        if (text.isNullOrBlank()) {
            error("Gemini returned no output (finishReason=${candidate.finishReason ?: "unknown"})")
        }

        val items = lenientCandidateJson.decodeFromString<List<CandidateItem>>(text)
        return items.map { item ->
            ExtractedCandidate(
                candidate = item.candidate,
                context = item.context.trim().ifEmpty { null },
                sourceQuote = item.sourceQuote.trim().ifEmpty { null },
                sourceLocation = item.sourceLocation.trim().ifEmpty { null },
                // Falls back to UNKNOWN/MEDIUM for a malformed/unrecognized
                // value rather than dropping the candidate - same "fall back
                // rather than drop" reasoning the rest of this codebase's
                // Gemini callers use.
                status = runCatching { CandidateStatus.valueOf(item.status) }.getOrDefault(CandidateStatus.UNKNOWN),
                importance = runCatching { Importance.valueOf(item.importance) }.getOrDefault(Importance.MEDIUM)
            )
        }
    }

    private fun buildPrompt(filename: String): String = """
        You are reading a home-related document ($filename) for a household knowledge system.

        Extract the durable knowledge this document contributes to the household's understanding of the house.

        The goal is NOT to summarize the document. Identify discrete pieces of information that could become useful, durable knowledge about the house and its history.

        A useful candidate is something that would still be relevant to the homeowner, contractor, engineer, or AI assistant years from now, particularly when making decisions about maintenance, repairs, renovations, diagnosis, safety, or resale.

        Look for:

        - Physical characteristics of the house
        - Existing structural, mechanical, electrical, plumbing, HVAC, exterior, roof, and safety components
        - Renovations, construction, repairs, replacements, and other historical events
        - New, proposed, removed, or modified components
        - Technical specifications, materials, dimensions, capacities, and measurements
        - Observed conditions and defects
        - Diagnoses or interpretations explicitly stated by the document
        - Engineering or design assumptions
        - Things explicitly described as unknown, unverified, inaccessible, or not investigated
        - Things explicitly outside the inspection, investigation, or professional mandate
        - Document-specific maintenance requirements
        - Warranties and guarantees
        - Relationships between components
        - Relationships between components and renovations or other historical events
        - Recommendations or unresolved issues that may matter to the homeowner

        Preserve meaningful relationships rather than reducing everything to isolated components.

        For example, if a document establishes that a beam is supported by columns and the columns transfer load to footings, preserve that structural relationship.

        If a document establishes that a condition predates, resulted from, or was addressed by a renovation or repair, preserve that temporal or causal relationship.

        ## Orient yourself before extracting

        Before pulling out individual facts, get oriented on what the document is actually about. This matters because a document describing more than one independent system or area of work is easy to under-extract from if you stop as soon as you've found one satisfying answer.

        First, capture the document's overall scope as a candidate of its own: what is this document, and what does it actually do to the house? State it in plain language a homeowner would understand, not professional jargon or a restatement of the title block - for example, "This 2017 renovation added a new window opening and a new steel structural support system along the first floor," not "Structural drawings for a residential reamenagement project."

        Next, identify the major moving pieces - the distinct systems, components, or areas of work the document actually addresses. For structural, engineering, or renovation drawings, a piece is typically one system: one beam-column-footing assembly, one new opening and its header, one wood-framed assembly, one mechanical system, and so on - a document is often doing more than one of these at once, in different locations, using different materials. For an inspection report, a piece is typically a major building system (roof, foundation, electrical, plumbing, HVAC, etc.). For an invoice or permit, a piece is typically one distinct scope of work. List every piece you find, including ones that only appear in a legend, schedule, or table rather than the main narrative, and ones that look similar to another piece - two independently-sized beams in two different locations are two pieces, not one. Do not stop after finding the first or most prominent piece; keep checking every legend, schedule, and table in the document against the pieces you've listed so far.

        Then, for each piece, extract candidates covering what it is (plain language, with the technical specification attached as supporting detail), what constraints or assumptions were examined for it, what was actually decided or specified for it, and how it relates to other pieces (what it supports, what supports it, what it replaces, what it's part of).

        This piece-by-piece pass is in addition to the general categories below, not a replacement for them - some candidates (a warranty, a general maintenance requirement, a fact about the existing house unrelated to any piece of this document's work) won't belong to any single piece and should still be captured.

        ## Renovation, construction, and engineering documents

        For renovation, engineering, architectural, and construction documents, distinguish between:

        - EXISTING elements
        - NEW elements
        - MODIFIED elements
        - REMOVED elements
        - PROPOSED or OPTIONAL elements

        In this household knowledge system, assume that documented renovation or construction work was completed unless the document itself indicates that the work was proposed, optional, cancelled, removed, or otherwise not completed.

        Therefore, when a renovation document describes work that appears to be the scope of an actual renovation, extract the resulting house knowledge rather than unnecessarily describing everything as merely "proposed."

        For example, prefer:

        > "The 2017 structural renovation introduced a W10x30 steel beam."

        over:

        > "The 2017 structural drawings proposed a W10x30 steel beam."

        However, preserve uncertainty when the document itself indicates that something was not completed or its status is unclear.

        For engineering documents, when extracting each piece's constraints (see "Orient yourself before extracting" above), distinguish between:

        - A value that was measured or verified
        - A value assumed for design purposes
        - A condition that was not investigated
        - An existing element whose properties were not determined
        - An existing element explicitly outside the engineer's mandate

        Do not turn an engineering assumption into a confirmed physical property of the house.

        Do not infer causes, conditions, or structural conclusions that are not supported by the document.

        ## Home inspection documents

        For inspection reports, pay particular attention to:

        - Actual observations
        - Defects and deficiencies
        - Conditions that may require monitoring
        - Recommendations for repair or further investigation
        - Areas that could not be inspected
        - Conditions whose cause or significance was not determined
        - Statements about what was or was not observed
        - Photographs documenting physical conditions
        - Maintenance recommendations
        - Limitations of the inspection

        Do not turn an inspector's recommendation into evidence that work is required or that a defect is severe unless the document supports that conclusion.

        ## Invoices, receipts, quotes, permits, and renovation records

        Look for:

        - Work actually performed
        - Components installed, repaired, replaced, or serviced
        - Dates
        - Costs
        - Vendors and contractors
        - Materials
        - Project or renovation associations
        - Warranties
        - Proposed work versus completed work
        - Evidence that a renovation or repair occurred

        Do not extract prices, vendor names, invoice numbers, or other administrative details as house knowledge unless they contribute to a meaningful project, financial, warranty, or historical record.

        ## General extraction rules

        Do not extract document metadata as house facts.

        This includes property addresses, document titles, drawing numbers, page numbers, author names, project numbers, document preparation dates, and similar administrative information.

        Preserve useful document metadata as source metadata, but only create a house fact when the information itself describes the house or its history.

        Prefer information that could affect:

        - Future decisions
        - Maintenance
        - Repair
        - Renovation
        - Diagnosis
        - Safety
        - Understanding the building
        - Resale or disclosure
        - Future AI reasoning about the property

        Omit incidental, administrative, or trivial information even if it is technically true.

        Do not summarize the entire document.

        Do not merge unrelated facts merely to reduce the number of candidates.

        At this stage, prefer recall over aggressive filtering. Preserve potentially useful candidates; a later pass will remove redundancy and decide what is actually worth remembering.

        For every candidate, preserve enough context to understand what the document actually says.

        Return a JSON array. Each object should contain:

        - candidate: a concise statement of the potential knowledge
        - context: additional context needed to interpret the candidate correctly
        - sourceQuote: a short, verbatim quote supporting it, when available
        - sourceLocation: page, drawing number, section, or other useful source location when available
        - status: one of EXISTING, NEW, MODIFIED, REMOVED, PROPOSED, ASSUMED, UNKNOWN, or NOT_APPLICABLE
        - importance: HIGH, MEDIUM, or LOW

        Do not attempt to produce the final Home OS fact types yet.

        Do not add information that is not supported by the document.
    """.trimIndent()

    private companion object {
        const val MAX_INLINE_BYTES = 15_000_000
    }
}

@Serializable
private data class CandidateExtractionRequest(val contents: List<CandidateExtractionContent>, val generationConfig: CandidateExtractionGenerationConfig)

@Serializable
private data class CandidateExtractionContent(val parts: List<JsonElement>)

@Serializable
private data class CandidateTextPart(val text: String)

@Serializable
private data class CandidateInlineDataPart(val inlineData: CandidateInlineData)

@Serializable
private data class CandidateInlineData(val mimeType: String, val data: String)

@Serializable
private data class CandidateExtractionGenerationConfig(
    val responseMimeType: String = "application/json",
    val responseSchema: CandidateExtractionSchema,
    val thinkingConfig: CandidateExtractionThinkingConfig
)

@Serializable
private data class CandidateExtractionThinkingConfig(val thinkingBudget: Int)

@Serializable
private data class CandidateExtractionSchema(val type: String = "ARRAY", val items: CandidateExtractionSchemaItem)

@Serializable
private data class CandidateExtractionSchemaItem(
    val type: String = "OBJECT",
    val properties: Map<String, CandidateExtractionSchemaProperty>,
    val required: List<String>
)

@Serializable
private data class CandidateExtractionSchemaProperty(val type: String, val enum: List<String>? = null)

@Serializable
private data class CandidateExtractionResponse(
    val candidates: List<CandidateExtractionCandidate> = emptyList(),
    val promptFeedback: CandidateExtractionPromptFeedback? = null
)

@Serializable
private data class CandidateExtractionCandidate(val content: CandidateExtractionResponseContent? = null, val finishReason: String? = null)

@Serializable
private data class CandidateExtractionResponseContent(val parts: List<CandidateExtractionResponsePart> = emptyList())

@Serializable
private data class CandidateExtractionResponsePart(val text: String? = null)

@Serializable
private data class CandidateExtractionPromptFeedback(val blockReason: String? = null)

@Serializable
private data class CandidateItem(
    val candidate: String,
    val context: String,
    val sourceQuote: String,
    val sourceLocation: String,
    val status: String,
    val importance: String
)
