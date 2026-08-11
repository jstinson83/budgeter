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
// /analysis calls categorize(ownerId) on every load - so loading the page
// is what replaces the old "Process N transactions" button, and it's a
// safe no-op when there's nothing left to fix. A job already RUNNING for
// this owner only skips the Gemini launch, not the whole call - see
// categorize()'s own comment for why.
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
    // categorize pass. Called from AnalysisRoutes.kt's GET /analysis on
    // every page load - now that that's automatic instead of a manual
    // button click, a background Gemini job for this owner is often still
    // RUNNING when a later load comes in (e.g. the bank leg of a transfer
    // is still being categorized when the credit-card leg gets uploaded and
    // the page reloads). TransferMatcher/CategorizationRuleMatcher must
    // still run on every call regardless of that - they're synchronous and
    // idempotent, and skipping them just because Gemini is busy elsewhere
    // is what silently left real transfers unmatched. Only the Gemini
    // launch itself is gated behind the RUNNING check, further down - one
    // job per owner at a time, and this call's own jobs[] write is skipped
    // rather than stomping the in-flight job's eventual DONE/FAILED result.
    suspend fun categorize(ownerId: String) {
        val pending = transactionStore.uncategorized(ownerId)

        // Claimed before Gemini ever sees these rows - otherwise it'd burn
        // a request trying to categorize "TFR-TO C/C" / "PAYMENT - THANK
        // YOU" rows as ordinary spending, and could mis-bucket the
        // credit-card leg as INCOME.
        //
        // TransferMatcher.categoryFor() is single-row and deterministic: a
        // transaction matching one of its fixed templates is a transfer
        // whether or not its other leg exists yet, so this isn't scoped to
        // `pending` (or windowed by date) at all - every one of the owner's
        // transactions is a candidate, regardless of its current category.
        // That's what makes a single upload of just one leg immediately
        // correct (nothing to wait for) and also self-healing for rows that
        // got mis-categorized as ordinary spending before this matching
        // existed or before a bug in it was fixed - there's no reliable way
        // to bound "how far back could a stale mismatch be" otherwise. This
        // is cheap to run unconditionally: `all(ownerId)` is already being
        // fetched by the GET /analysis handler itself for rendering, so
        // this doesn't cost an extra Firestore read, and the matching
        // itself is in-memory and fast (no network call, unlike Gemini).
        // LOC interest is the one exception still requiring a matched pair
        // (matchInterestPairs) - see TransferMatcher's own comment for why
        // "PYT TO:" alone isn't definitive the way every other marker is.
        val transferCandidates = transactionStore.all(ownerId)
        val categoryBeforeMatch = transferCandidates.associate { it.id to it.category }
        // Only rows whose category is actually changing - re-deriving the
        // same category an already-correct row already has would otherwise
        // mean a wasted Firestore write, for every transfer, on every
        // single page load.
        val transferMatches = (TransferMatcher.categorize(transferCandidates) + TransferMatcher.matchInterestPairs(transferCandidates))
            .filter { (id, category) -> categoryBeforeMatch[id] != category }
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

        // Unlike the old pair-matching design, transferMatches isn't
        // guaranteed to hold both legs of a pair - a lone unmatched leg
        // counts too - so this reports a plain transaction count, not a
        // pair count.
        val syncMessageParts = mutableListOf<String>()
        if (transferMatches.isNotEmpty()) syncMessageParts += "Marked ${transferMatches.size} transaction(s) as transfers"
        if (ruleMatches.isNotEmpty()) syncMessageParts += "Applied ${ruleMatches.size} rule(s)"

        // A background job for this owner is already in flight - don't
        // touch jobs[ownerId] below (whether that would be a synchronous
        // DONE from this call or a new RUNNING launch): whatever the
        // in-flight job (or its own completion write) leaves there is the
        // authoritative outcome to show. The transfer/rule matches applied
        // above are unaffected by this and are already persisted either way.
        if (jobs[ownerId]?.status == CategorizationJobStatus.RUNNING) return

        val remaining = afterTransferMatch.filterNot { it.id in ruleMatches }
        if (remaining.isEmpty()) {
            // Unlike before transfer matching covered already-categorized
            // rows, syncMessageParts isn't guaranteed non-empty here:
            // pending itself might have been empty all along, with nothing
            // but an already-fully-categorized history and no stale
            // transfer left to fix in it.
            if (syncMessageParts.isNotEmpty()) {
                jobs[ownerId] = CategorizationJobState(CategorizationJobStatus.DONE, message = syncMessageParts.joinToString(", "))
            }
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
