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

// Owns a household's whole categorize pass - not a generic job runner that
// some caller hands work to, but the thing that actually knows how to
// categorize (TransferMatcher, then CategorizationRuleMatcher, then
// Gemini), same as GeminiTransactionCategorizer owns how to talk to
// Gemini. AnalysisRoutes.kt's POST /analysis/categorize just calls
// categorize(ownerId) and redirects with whatever message comes back.
//
// TransferMatcher/CategorizationRuleMatcher run synchronously (fast,
// deterministic, no reason to leave the request for them). Only the Gemini
// leg continues on an application-scoped coroutine that outlives the POST,
// so a large pending batch isn't bounded by Cloud Run's request timeout.
// Cloud Run only allocates CPU to an instance while it has a request in
// flight (this service doesn't have "CPU always allocated" on), so the job
// only makes real progress while some request - the original POST, or one
// of /analysis's status polls (see analysis.ftl) - is being served;
// polling isn't just for the UI, it's what keeps this job's CPU allocated
// between the chunked Gemini calls GeminiTransactionCategorizer now makes
// (see GeminiCategorizer.kt). One job per owner at a time, kept in-memory
// rather than Firestore - it's transient progress state, not data worth
// surviving a restart, and this app runs a single Cloud Run instance's
// worth of traffic.
class CategorizationJobManager(
    private val scope: CoroutineScope,
    private val transactionStore: TransactionRepository,
    private val categorizationRuleStore: CategorizationRuleRepository,
    private val categoryStore: CategoryRepository,
    private val transactionCategorizer: TransactionCategorizer
) {
    private val jobs = ConcurrentHashMap<String, CategorizationJobState>()

    // Fetches this owner's pending transactions and runs the full
    // categorize pass, returning the message AnalysisRoutes.kt redirects
    // with. Transfer/rule matches (and their combined message) are applied
    // before this suspends on anything Gemini-related, so a caller with
    // nothing left to categorize gets its final answer immediately instead
    // of a "started" message for a job that has nothing to do.
    suspend fun categorize(ownerId: String): String {
        val pending = transactionStore.uncategorized(ownerId)
        if (pending.isEmpty()) return "No new transactions to categorize"

        // Claimed before Gemini ever sees these rows - otherwise it'd burn
        // a request trying to categorize "TFR-TO C/C" / "PAYMENT - THANK
        // YOU" rows as ordinary spending, and could mis-bucket the
        // credit-card leg as INCOME.
        val transferMatches = TransferMatcher.match(pending)
        if (transferMatches.isNotEmpty()) transactionStore.updateCategories(ownerId, transferMatches)

        // Household-defined rules run next, also before Gemini - same
        // "deterministic and free beats a guess that costs an API call"
        // reasoning, just for patterns the user picked via the
        // /analysis/category/{slug} "Recategorize" action instead of
        // TransferMatcher's fixed templates.
        val afterTransferMatch = pending.filterNot { it.id in transferMatches }
        val rules = categorizationRuleStore.all(ownerId)
        val ruleMatches = CategorizationRuleMatcher.match(rules, afterTransferMatch)
        if (ruleMatches.isNotEmpty()) transactionStore.updateCategories(ownerId, ruleMatches)

        // transferMatches holds both legs of every matched pair, so its
        // size is always even - divide by 2 for the pair count.
        val syncMessageParts = mutableListOf<String>()
        if (transferMatches.isNotEmpty()) syncMessageParts += "Matched ${transferMatches.size / 2} transfer(s)"
        if (ruleMatches.isNotEmpty()) syncMessageParts += "Applied ${ruleMatches.size} rule(s)"

        val remaining = afterTransferMatch.filterNot { it.id in ruleMatches }
        if (remaining.isEmpty()) return syncMessageParts.joinToString(", ")

        if (jobs[ownerId]?.status == CategorizationJobStatus.RUNNING) return "Categorization already in progress"

        jobs[ownerId] = CategorizationJobState(CategorizationJobStatus.RUNNING)
        scope.launch {
            jobs[ownerId] = try {
                val categories = categoryStore.all(ownerId).filter { it.active }
                val categorized = transactionCategorizer.categorize(remaining, categories)
                if (categorized.isNotEmpty()) transactionStore.updateCategories(ownerId, categorized)
                val message = (syncMessageParts + "Categorized ${categorized.size} of ${remaining.size} transaction(s)").joinToString(", ")
                CategorizationJobState(CategorizationJobStatus.DONE, message = message)
            } catch (e: Exception) {
                CategorizationJobState(CategorizationJobStatus.FAILED, error = "Categorization failed: ${e.message}")
            }
        }
        return "Categorizing ${remaining.size} transaction(s)…"
    }

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

    fun cancel() {
        scope.cancel()
    }
}
