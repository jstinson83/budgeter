package com.budgeter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

enum class CategorizationJobStatus { RUNNING, DONE, FAILED }

data class CategorizationJobState(
    val status: CategorizationJobStatus,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class CategorizationJobStatusResponse(val status: String, val message: String? = null, val error: String? = null)

// Runs the slow (Gemini) leg of a household's categorize pass on an
// application-scoped coroutine instead of inline in the POST handler, so a
// large pending batch isn't bounded by Cloud Run's request timeout. Cloud
// Run only allocates CPU to an instance while it has a request in flight
// (this service doesn't have "CPU always allocated" on), so the job only
// makes real progress while some request - the POST that started it, or one
// of /analysis's status polls (see analysis.ftl) - is being served; polling
// isn't just for the UI, it's what keeps this job's CPU allocated between
// the chunked Gemini calls GeminiTransactionCategorizer now makes (see
// GeminiCategorizer.kt). One job per owner at a time, kept in-memory rather
// than Firestore - it's transient progress state, not data worth surviving
// a restart, and this app runs a single Cloud Run instance's worth of
// traffic - no cross-instance fan-out to worry about.
class CategorizationJobManager(private val scope: CoroutineScope) {
    private val jobs = ConcurrentHashMap<String, CategorizationJobState>()

    // Non-consuming - the frontend's poll loop reads this repeatedly and
    // must see the same terminal state on every read up until it stops
    // polling.
    fun status(ownerId: String): CategorizationJobState? = jobs[ownerId]

    // Consuming - AnalysisRoutes.kt's GET /analysis calls this once to fold
    // a just-finished job's message/error into the page like any other
    // one-shot banner, then forgets it so a later unrelated visit doesn't
    // keep re-showing the same result. A still-RUNNING job is left in place
    // since there's nothing to consume yet.
    fun consumeTerminal(ownerId: String): CategorizationJobState? {
        val current = jobs[ownerId] ?: return null
        if (current.status != CategorizationJobStatus.RUNNING) jobs.remove(ownerId)
        return current
    }

    // Returns false without starting anything if a job's already running
    // for this owner - the caller decides what that means for the user
    // (AnalysisRoutes.kt reports "already in progress" rather than silently
    // queueing a second pass over the same transactions).
    fun start(ownerId: String, work: suspend () -> String): Boolean {
        if (jobs[ownerId]?.status == CategorizationJobStatus.RUNNING) return false
        jobs[ownerId] = CategorizationJobState(CategorizationJobStatus.RUNNING)
        scope.launch {
            jobs[ownerId] = try {
                CategorizationJobState(CategorizationJobStatus.DONE, message = work())
            } catch (e: Exception) {
                CategorizationJobState(CategorizationJobStatus.FAILED, error = "Categorization failed: ${e.message}")
            }
        }
        return true
    }

    fun cancel() {
        scope.cancel()
    }
}
