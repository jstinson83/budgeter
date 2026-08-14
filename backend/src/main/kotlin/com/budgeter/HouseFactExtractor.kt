package com.budgeter

import org.slf4j.LoggerFactory

// Raw output of the full two-pass extraction pipeline (pass 1:
// HouseFactCandidateExtractor.kt, pass 2: HouseFactNormalizer.kt), before it
// becomes a persisted HouseFact (HouseFactStore.kt) - the "MVP: House
// Knowledge" workflow's step 2 (extract things worth remembering) and step 3
// (identify ambiguity) from product_spec.md.
data class ExtractedFact(
    val what: String,
    val type: FactType,
    val component: Component,
    val status: FactStatus,
    val importance: Importance,
    // Blank sourceQuote/sourceLocation/reviewQuestion from Gemini are
    // normalized to null here - see GeminiHouseFactNormalizer.normalize().
    val sourceQuote: String?,
    val sourceLocation: String?,
    val evidenceType: EvidenceType?,
    val needsReview: Boolean,
    val reviewQuestion: String?
)

// debugNotes is freeform diagnostic text an implementation can leave behind
// for a human to read - TwoPassHouseFactExtractor fills it with pass 1's
// document walkthrough and candidate list plus pass 2's fact count, the
// same information it logs at INFO level, so recall gaps can be diagnosed
// from HouseDocument.debugNotes (rendered on house-document.ftl) without
// needing Cloud Logging access. Null is a legitimate value - an
// implementation with nothing useful to add just returns null.
data class ExtractionResult(
    val facts: List<ExtractedFact>,
    val debugNotes: String? = null
)

interface HouseFactExtractor {
    // documentContext is the homeowner's optional free-text background from
    // upload time (HouseDocument.context) - grounding for the extraction,
    // not document content itself. See HouseFactCandidateExtractor.kt's and
    // HouseFactNormalizer.kt's prompts for how it's used.
    suspend fun extract(filename: String, pdfBytes: ByteArray, documentContext: String?): ExtractionResult
}

// Runs the two-pass extraction pipeline: pass 1
// (HouseFactCandidateExtractor.kt) reads the whole document and identifies a
// broad, recall-favoring set of raw candidates; pass 2
// (HouseFactNormalizer.kt) reconciles/dedupes them and decides the final,
// concise set of facts actually worth persisting. Split into two Gemini
// calls rather than one single extraction pass because pass 2 is designed to
// eventually reconcile candidates gathered across multiple documents at
// once, not just re-shape one document's output - keeping it a separate
// step now means that later extension doesn't require re-touching pass 1 or
// this orchestrator's callers (HouseFactExtractionJob.kt).
class TwoPassHouseFactExtractor(
    private val candidateExtractor: HouseFactCandidateExtractor,
    private val normalizer: HouseFactNormalizer
) : HouseFactExtractor {
    override suspend fun extract(filename: String, pdfBytes: ByteArray, documentContext: String?): ExtractionResult {
        val batch = candidateExtractor.extractCandidates(filename, pdfBytes, documentContext)
        // Recall/completeness has been tuned empirically against real
        // documents (see CLAUDE.md's House Knowledge gotchas) without any
        // visibility into pass 1's raw output - every round so far has been
        // a guess at whether pass 1 failed to find something or pass 2
        // dropped it. The walkthrough is logged separately from the
        // candidate list so it's possible to tell "pass 1 never noticed
        // this" (missing from the walkthrough too) apart from "pass 1
        // noticed it but didn't turn it into a candidate" (present in the
        // walkthrough, absent from the list below) - two different bugs
        // with two different fixes. Logged at INFO (not DEBUG) so it shows
        // up in Cloud Logging by default, and also returned as debugNotes
        // (see ExtractionResult) so it's readable on house-document.ftl
        // without needing Cloud Logging access at all - added after the
        // first few rounds of "paste me the logs" turned out to be a lot
        // of friction for what's otherwise a self-serve retry loop.
        val candidateSummary = batch.candidates.joinToString("\n") { "  - [${it.status}/${it.importance}] ${it.candidate}" }
        logger.info("House fact extraction pass 1 for '{}' (context provided: {}) walkthrough:\n{}", filename, !documentContext.isNullOrBlank(), batch.documentWalkthrough)
        logger.info("House fact extraction pass 1 for '{}' found {} candidate(s):\n{}", filename, batch.candidates.size, candidateSummary)
        val facts = normalizer.normalize(batch.candidates, documentContext)
        logger.info("House fact extraction pass 2 for '{}' produced {} fact(s) from {} candidate(s)", filename, facts.size, batch.candidates.size)

        val debugNotes = """
            Document walkthrough:
            ${batch.documentWalkthrough}

            Pass 1 candidates (${batch.candidates.size}):
            $candidateSummary

            Pass 2 produced ${facts.size} fact(s) from ${batch.candidates.size} candidate(s).
        """.trimIndent()
        return ExtractionResult(facts = facts, debugNotes = debugNotes)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(TwoPassHouseFactExtractor::class.java)
    }
}
