package com.budgeter

import kotlinx.coroutines.runBlocking
import kotlin.test.*

// TwoPassHouseFactExtractor is pure orchestration - the actual Gemini calls
// are covered by HouseFactCandidateExtractorTest.kt (pass 1) and
// HouseFactNormalizerTest.kt (pass 2), so this only verifies pass 1's output
// is what gets fed into pass 2, and pass 2's output is what comes back out.
class HouseFactExtractorTest {
    private val candidate = ExtractedCandidate(
        candidate = "House contains steel columns",
        context = null,
        sourceQuote = "steel columns observed",
        sourceLocation = "page 4",
        status = CandidateStatus.EXISTING,
        importance = Importance.MEDIUM
    )

    private val fact = ExtractedFact(
        what = "The house contains steel structural columns",
        type = FactType.SPECIFICATION,
        component = Component.STRUCTURE,
        status = FactStatus.EXISTING,
        importance = Importance.MEDIUM,
        sourceQuote = "steel columns observed",
        sourceLocation = "page 4",
        evidenceType = EvidenceType.OBSERVED,
        needsReview = false,
        reviewQuestion = null
    )

    private class FakeCandidateExtractor(private val candidates: List<ExtractedCandidate>) : HouseFactCandidateExtractor {
        var lastFilename: String? = null
        var lastPdfBytes: ByteArray? = null

        override suspend fun extractCandidates(filename: String, pdfBytes: ByteArray): List<ExtractedCandidate> {
            lastFilename = filename
            lastPdfBytes = pdfBytes
            return candidates
        }
    }

    private class FakeNormalizer(private val facts: List<ExtractedFact>) : HouseFactNormalizer {
        var lastCandidates: List<ExtractedCandidate>? = null

        override suspend fun normalize(candidates: List<ExtractedCandidate>): List<ExtractedFact> {
            lastCandidates = candidates
            return facts
        }
    }

    @Test
    fun testFeedsPassOneCandidatesIntoPassTwoAndReturnsItsOutput() = runBlocking {
        val candidateExtractor = FakeCandidateExtractor(listOf(candidate))
        val normalizer = FakeNormalizer(listOf(fact))
        val pdfBytes = "%PDF-1.4 fake".toByteArray()

        val result = TwoPassHouseFactExtractor(candidateExtractor, normalizer).extract("inspection.pdf", pdfBytes)

        assertEquals("inspection.pdf", candidateExtractor.lastFilename)
        assertSame(pdfBytes, candidateExtractor.lastPdfBytes)
        assertEquals(listOf(candidate), normalizer.lastCandidates)
        assertEquals(listOf(fact), result)
    }

    @Test
    fun testEmptyCandidateListStillReachesTheNormalizer() = runBlocking {
        val candidateExtractor = FakeCandidateExtractor(emptyList())
        val normalizer = FakeNormalizer(emptyList())

        val result = TwoPassHouseFactExtractor(candidateExtractor, normalizer).extract("inspection.pdf", "%PDF-1.4".toByteArray())

        assertEquals(emptyList(), normalizer.lastCandidates)
        assertEquals(emptyList(), result)
    }
}
