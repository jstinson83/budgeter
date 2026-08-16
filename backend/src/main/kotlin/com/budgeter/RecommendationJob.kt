package com.budgeter

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

enum class RecommendationJobStatus { RUNNING, DONE, FAILED }

data class RecommendationJobState(
    val status: RecommendationJobStatus,
    val message: String? = null,
    val error: String? = null
)

@Serializable
data class RecommendationJobStatusResponse(val status: String, val message: String? = null, val error: String? = null)

// Drives the "Generate recommendations" button (ProjectRoutes.kt) - loops
// every Component, skips any with no new facts since its own last
// generation (RecommendationGenerationMarkerRepository), and makes one
// Gemini call per stale component. Same async-job-plus-poll shape as
// HouseFactExtractionJobManager/CategorizationJobManager: a full loop
// across every component can make several sequential Gemini calls, well
// past what a single Cloud Run request should be left holding open for.
// One job per owner, in-memory - same "transient progress state, not data
// worth surviving a restart" reasoning as CategorizationJobManager.
class RecommendationJobManager(
    private val scope: CoroutineScope,
    private val houseFactStore: HouseFactRepository,
    private val projectStore: ProjectRepository,
    private val recommendationStore: RecommendationRepository,
    private val markerStore: RecommendationGenerationMarkerRepository,
    private val recommendationGenerator: RecommendationGenerator
) {
    private val jobs = ConcurrentHashMap<String, RecommendationJobState>()

    fun start(ownerId: String) {
        if (jobs[ownerId]?.status == RecommendationJobStatus.RUNNING) return
        jobs[ownerId] = RecommendationJobState(RecommendationJobStatus.RUNNING)
        scope.launch {
            jobs[ownerId] = try {
                runGeneration(ownerId)
            } catch (e: Exception) {
                RecommendationJobState(RecommendationJobStatus.FAILED, error = "Recommendation generation failed: ${e.message}")
            }
        }
    }

    // Each component's recommendations and marker are persisted as soon as
    // that component finishes, not batched until the whole loop completes
    // - so a failure partway through (e.g. a transient Gemini error on the
    // fourth component) doesn't lose the first three components' already-
    // persisted work, and a retry only redoes whichever components still
    // need it.
    private suspend fun runGeneration(ownerId: String): RecommendationJobState {
        val facts = houseFactStore.all(ownerId)
        val projects = projectStore.all(ownerId)
        val activeAndPlanned = projects.filter { it.status == ProjectStatus.ACTIVE || it.status == ProjectStatus.PLANNED }
        val deprioritized = projects.filter { it.status == ProjectStatus.DEPRIORITIZED }
        val pastRecommendations = recommendationStore.all(ownerId)

        var generatedCount = 0
        var staleComponentCount = 0
        for (component in Component.entries) {
            val componentFacts = facts.filter { it.component == component }
            if (componentFacts.isEmpty()) continue

            val marker = markerStore.get(ownerId, component)
            if (marker != null && marker.factCount == componentFacts.size) continue

            staleComponentCount++
            val pastForComponent = pastRecommendations.filter { it.component == component }
            val generated = recommendationGenerator.generate(component, componentFacts, activeAndPlanned, deprioritized, pastForComponent)
            if (generated.isNotEmpty()) {
                recommendationStore.addAll(ownerId, component, generated)
                generatedCount += generated.size
            }
            markerStore.save(ownerId, component, componentFacts.size)
        }

        val message = if (staleComponentCount == 0) {
            "Nothing new to recommend"
        } else {
            "Generated $generatedCount recommendation(s) across $staleComponentCount component(s)"
        }
        return RecommendationJobState(RecommendationJobStatus.DONE, message = message)
    }

    // Non-consuming - the frontend's poll loop reads this repeatedly and
    // must see the same terminal state on every read up until it stops
    // polling.
    fun status(ownerId: String): RecommendationJobState? = jobs[ownerId]

    // Consuming - GET /projects calls this once to fold a just-finished
    // job's message/error into the page like any other one-shot banner,
    // then forgets it so a later unrelated visit doesn't keep re-showing
    // the same result.
    fun consumeTerminal(ownerId: String): RecommendationJobState? {
        val current = jobs[ownerId] ?: return null
        if (current.status != RecommendationJobStatus.RUNNING) jobs.remove(ownerId)
        return current
    }

    fun cancel() {
        scope.cancel()
    }
}
