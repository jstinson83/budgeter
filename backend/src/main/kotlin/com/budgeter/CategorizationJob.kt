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

// Owns a household's whole categorize pass - not a generic job runner some
// caller hands work to, but the thing that actually knows how to
// categorize (TransferMatcher, then CategorizationRuleMatcher, then
// Gemini), same as GeminiTransactionCategorizer owns how to talk to
// Gemini. There's no manual trigger anymore: AnalysisRoutes.kt's GET
// /analysis calls categorize(ownerId) on every load, and it's a safe
// no-op when there's nothing pending or a job's already running - so
// loading the page is what replaces the old "Process N transactions"
// button.
//
// TransferMatcher/CategorizationRuleMatcher run synchronously (fast,
// deterministic, no reason to leave the request for them, and their
// combined result is recorded as an already-DONE job so it shows up in
// the very page load that triggered it). Only the Gemini leg continues on
// an application-scoped coroutine that outlives the request, so a large
// pending batch isn't bounded by Cloud Run's request timeout. Cloud Run
// only allocates CPU to an instance while it has a request in flight
// (this service doesn't have "CPU always allocated" on), so the job only
// makes real progress while some request - the page load that started it,
// or one of /analysis's status polls (see analysis.ftl) - is being
// served; polling isn't just for the UI, it's what keeps this job's CPU
// allocated between the chunked Gemini calls GeminiTransactionCategorizer
// now makes (see GeminiCategorizer.kt). One job per owner at a time, kept
// in-memory rather than Firestore - it's transient progress state, not
// data worth surviving a restart, and this app runs a single Cloud Run
// instance's worth of traffic.
class CategorizationJobManager(
    private val scope: CoroutineScope,
    private val transactionStore: TransactionRepository,
    private val categorizationRuleStore: CategorizationRuleRepository,
    private val categoryStore: CategoryRepository,
    private val transactionCategorizer: TransactionCategorizer
) {
    private val jobs = ConcurrentHashMap<String, CategorizationJobState>()

    // Fetches this owner's pending transactions and runs the full
    // categorize pass. A no-op if there's nothing pending, or if a job's
    // already running for this owner (left alone rather than clobbered,
    // even if this call's own matching below would otherwise resolve
    // synchronously, so its eventual DONE/FAILED write isn't stomped on by
    // a stale one from this call). Called from AnalysisRoutes.kt's GET
    // /analysis on every page load.
    suspend fun categorize(ownerId: String) {
        val pending = transactionStore.uncategorized(ownerId)
        if (pending.isEmpty()) return
        if (jobs[ownerId]?.status == CategorizationJobStatus.RUNNING) return

        // Claimed before Gemini ever sees these rows - otherwise it'd burn
        // a request trying to categorize "TFR-TO C/C" / "PAYMENT - THANK
        // YOU" rows as ordinary spending, and could mis-bucket the
        // credit-card leg as INCOME.
        //
        // The candidate pool for this isn't just `pending`: the two legs of
        // a real transfer are routinely uploaded in separate sessions (bank
        // statement today, credit-card statement next week), and by the
        // time the second leg arrives the first has usually already been
        // auto-categorized as ordinary spending. So this widens the pool to
        // every transaction - regardless of its current category - within
        // TransferMatcher's own date window of what's pending, letting a
        // previously mis-categorized leg still be found and corrected.
        // Bounded by that date window rather than the owner's whole history
        // so this stays a targeted lookup, not a full rescan.
        val windowStart = pending.minOf { it.date }.minusDays(TransferMatcher.DATE_WINDOW_DAYS)
        val windowEnd = pending.maxOf { it.date }.plusDays(TransferMatcher.DATE_WINDOW_DAYS)
        val transferCandidates = transactionStore.all(ownerId).filter { it.date in windowStart..windowEnd }
        val transferMatches = TransferMatcher.match(transferCandidates)
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
        if (remaining.isEmpty()) {
            // pending was non-empty and remaining is empty, so at least one
            // of transferMatches/ruleMatches matched something - syncMessageParts
            // is guaranteed non-empty here.
            jobs[ownerId] = CategorizationJobState(CategorizationJobStatus.DONE, message = syncMessageParts.joinToString(", "))
            return
        }

        // Fetched here, synchronously, rather than from inside the launched
        // coroutine below: this call and the GET /analysis handler's own
        // categoryStore.all(ownerId) (for rendering) would otherwise run
        // concurrently, and a repository that lazily seeds built-in
        // categories on first read (see CategoryStore.kt) isn't
        // necessarily safe against two concurrent first-reads both seeding
        // at once and creating duplicates.
        val categories = categoryStore.all(ownerId).filter { it.active }

        jobs[ownerId] = CategorizationJobState(CategorizationJobStatus.RUNNING)
        scope.launch {
            jobs[ownerId] = try {
                val categorized = transactionCategorizer.categorize(remaining, categories)
                if (categorized.isNotEmpty()) transactionStore.updateCategories(ownerId, categorized)
                val message = (syncMessageParts + "Categorized ${categorized.size} of ${remaining.size} transaction(s)").joinToString(", ")
                CategorizationJobState(CategorizationJobStatus.DONE, message = message)
            } catch (e: Exception) {
                CategorizationJobState(CategorizationJobStatus.FAILED, error = "Categorization failed: ${e.message}")
            }
        }
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
