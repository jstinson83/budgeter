package com.budgeter

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

interface HouseFactExtractor {
    // documentContext is the homeowner's optional free-text background from
    // upload time (HouseDocument.context) - grounding for the extraction,
    // not document content itself. See HouseFactCandidateExtractor.kt's and
    // HouseFactNormalizer.kt's prompts for how it's used.
    suspend fun extract(filename: String, pdfBytes: ByteArray, documentContext: String?): List<ExtractedFact>
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
    override suspend fun extract(filename: String, pdfBytes: ByteArray, documentContext: String?): List<ExtractedFact> {
        val candidates = candidateExtractor.extractCandidates(filename, pdfBytes, documentContext)
        return normalizer.normalize(candidates, documentContext)
    }
}
