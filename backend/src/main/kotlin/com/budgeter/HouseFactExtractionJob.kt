package com.budgeter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

enum class ExtractionJobStatus { RUNNING, DONE, FAILED }

data class ExtractionJobState(
    val status: ExtractionJobStatus,
    val factCount: Int? = null,
    val error: String? = null
)

@Serializable
data class ExtractionJobStatusResponse(val status: String, val error: String? = null)

// Owns running one uploaded document's Gemini extraction off the upload
// request's own thread - see CLAUDE.md's House Knowledge gotchas.
// Extraction used to run synchronously inside POST
// /house/documents/upload; a large or slow document could run Gemini long
// enough to hit Cloud Run's request timeout before ever getting a response,
// failing the upload outright with no useful error. Modeled directly on
// CategorizationJobManager (CategorizationJob.kt): the upload request now
// only marks the document EXTRACTING and kicks the actual extract() call
// onto a coroutine scope that outlives the request, then redirects
// immediately. house-document.ftl polls GET /house/documents/{id}/status
// while EXTRACTING and reloads once it isn't - same "polling also keeps
// this coroutine's CPU allocated on Cloud Run between the request that
// launched it and the one that observes it finished" reasoning as the
// categorize job's polling.
//
// Keyed by document id, not owner - unlike categorization (one job per
// owner covers their whole pending queue at once), a household can have
// more than one document extracting at the same time.
class HouseFactExtractionJobManager(
    private val scope: CoroutineScope,
    private val houseDocumentStore: HouseDocumentRepository,
    private val houseFactStore: HouseFactRepository,
    private val houseFactExtractor: HouseFactExtractor
) {
    private val jobs = ConcurrentHashMap<String, ExtractionJobState>()

    // Document status is already EXTRACTING in Firestore by the time this
    // is called (set synchronously in the upload handler, before this job
    // is started) - this only owns the actual Gemini call and the
    // EXTRACTED/FAILED transition once it's done.
    fun start(ownerId: String, documentId: String, filename: String, pdfBytes: ByteArray, documentContext: String?) {
        jobs[documentId] = ExtractionJobState(ExtractionJobStatus.RUNNING)
        scope.launch {
            jobs[documentId] = try {
                val result = houseFactExtractor.extract(filename, pdfBytes, documentContext)
                houseFactStore.addAll(ownerId, documentId, result.facts)
                houseDocumentStore.updateStatus(ownerId, documentId, HouseDocumentStatus.EXTRACTED)
                houseDocumentStore.updateDebugNotes(ownerId, documentId, result.debugNotes)
                ExtractionJobState(ExtractionJobStatus.DONE, factCount = result.facts.size)
            } catch (e: Exception) {
                houseDocumentStore.updateStatus(ownerId, documentId, HouseDocumentStatus.FAILED, e.message)
                ExtractionJobState(ExtractionJobStatus.FAILED, error = e.message)
            }
        }
    }

    // Non-consuming - the status poll reads this repeatedly and must see
    // the same terminal state on every read up until it stops polling.
    fun status(documentId: String): ExtractionJobState? = jobs[documentId]

    // Consuming - GET /house/documents/{id} calls this once to fold a
    // just-finished job's message/error into the page like any other
    // one-shot banner, then forgets it so a later unrelated visit doesn't
    // keep re-showing the same result.
    fun consumeTerminal(documentId: String): ExtractionJobState? {
        val current = jobs[documentId] ?: return null
        if (current.status != ExtractionJobStatus.RUNNING) jobs.remove(documentId)
        return current
    }

    fun cancel() {
        scope.cancel()
    }
}
