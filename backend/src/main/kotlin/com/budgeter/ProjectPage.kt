package com.budgeter

import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val entryDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

// Pre-formats what projects.ftl needs: an optional component filter, plus
// projects grouped by status (Active/Planned/Deprioritized/Completed, in
// that display order) - same "skip empty groups" pattern
// houseFactsPageModel uses for its component groups.
fun projectsPageModel(
    projects: List<Project>,
    componentFilter: Component?,
    isGenerating: Boolean,
    pendingRecommendations: List<Recommendation>,
    facts: List<HouseFact>,
    message: String?,
    error: String?
): Map<String, Any?> {
    // Subprojects don't get their own row here - the maintainer's call was
    // that they clutter the main list. Each top-level project instead
    // carries its own subprojects (titles only) for projects.ftl to render
    // as a <details> disclosure the homeowner can expand in place, without
    // navigating to the parent's own page. Note this means a component
    // filter only ever matches on a *top-level* project's own component - a
    // subproject whose component differs from its parent's can't currently
    // be surfaced by the filter on its own; not addressed since hiding
    // subprojects from the list at all was the more important ask.
    val topLevelProjects = projects.filter { it.parentId == null }
    val subprojectsByParentId = projects.filter { it.parentId != null }.groupBy { it.parentId }
    val filtered = if (componentFilter != null) topLevelProjects.filter { it.component == componentFilter } else topLevelProjects
    val byStatus = filtered.groupBy { it.status }
    val groups = ProjectStatus.entries.mapNotNull { status ->
        val statusProjects = byStatus[status].orEmpty()
        if (statusProjects.isEmpty()) return@mapNotNull null
        mapOf(
            "status" to status.name,
            "projects" to statusProjects.map { projectSummaryModel(it, subprojectsByParentId[it.id].orEmpty()) }
        )
    }
    val factsById = facts.associateBy { it.id }
    return mapOf(
        "groups" to groups,
        "componentOptions" to Component.entries.map { it.name },
        "selectedComponent" to componentFilter?.name,
        "isGenerating" to isGenerating,
        "recommendations" to pendingRecommendations.map { recommendationSummaryModel(it, factsById) },
        "message" to message,
        "error" to error
    )
}

private fun recommendationSummaryModel(recommendation: Recommendation, factsById: Map<String, HouseFact>): Map<String, Any?> = mapOf(
    "id" to recommendation.id,
    "name" to recommendation.name,
    "component" to recommendation.component.name,
    "rationale" to recommendation.rationale,
    "priority" to recommendation.suggestedPriority.name,
    "supportingFacts" to recommendation.supportingFactIds.mapNotNull { factsById[it]?.what }
)

private fun projectSummaryModel(project: Project, subprojects: List<Project>): Map<String, Any?> = mapOf(
    "id" to project.id,
    "name" to project.name,
    "component" to project.component.name,
    "priority" to project.priority.name,
    "subprojects" to subprojects.map { mapOf("id" to it.id, "name" to it.name) }
)

// allProjects is the owner's whole collection (not pre-filtered) - used to
// resolve this project's parent's name, list its current subprojects, and
// offer a picker of other projects still eligible to become one. Capped at
// one level (see ProjectRoutes' /subprojects validation): a project that
// already has a parent shows that parent instead of a subprojects section,
// since it can't have subprojects of its own.
//
// allFacts/allDocuments are the owner's whole collections (not pre-filtered
// to this project) - split here into what's already linked vs. what's still
// available to attach, so project.ftl's picker never offers something
// that's already on the project.
fun projectPageModel(
    project: Project,
    allProjects: List<Project>,
    allFacts: List<HouseFact>,
    allDocuments: List<HouseDocument>,
    entries: List<ProjectEntry>,
    message: String?,
    error: String?
): Map<String, Any?> {
    val filenameById = allDocuments.associate { it.id to it.filename }
    val (linkedFacts, availableFacts) = allFacts.partition { it.id in project.factIds }
    val (linkedDocuments, availableDocuments) = allDocuments.partition { it.id in project.documentIds }
    val parent = project.parentId?.let { parentId -> allProjects.find { it.id == parentId } }
    val subprojects = allProjects.filter { it.parentId == project.id }
    // Only other top-level projects (no parent) with no subprojects of
    // their own are eligible to become a subproject here - mirrors the
    // /subprojects route's own validation so the picker never offers
    // something the route would reject.
    val projectsWithChildren = allProjects.mapNotNull { it.parentId }.toSet()
    val availableAsSubproject = allProjects.filter {
        it.id != project.id && it.parentId == null && it.id !in projectsWithChildren
    }
    return mapOf(
        "project" to mapOf(
            "id" to project.id,
            "name" to project.name,
            "status" to project.status.name,
            "component" to project.component.name,
            "priority" to project.priority.name,
            "parent" to parent?.let { mapOf("id" to it.id, "name" to it.name) },
            // Plain boolean rather than leaning on project.ftl doing
            // `!project.parent??` - see CLAUDE.md's FreeMarker gotchas on
            // nullable-value comparisons/existence tests being easy to get
            // wrong in templates.
            "hasParent" to (parent != null)
        ),
        "statusOptions" to ProjectStatus.entries.map { it.name },
        "componentOptions" to Component.entries.map { it.name },
        "priorityOptions" to Priority.entries.map { it.name },
        "linkedFacts" to linkedFacts.map { factSummaryModel(it, filenameById) },
        "availableFacts" to availableFacts.map { factSummaryModel(it, filenameById) },
        "linkedDocuments" to linkedDocuments.map { documentSummaryModel(it) },
        "availableDocuments" to availableDocuments.map { documentSummaryModel(it) },
        "entries" to entries.map { entrySummaryModel(it) },
        "subprojects" to subprojects.map { subprojectSummaryModel(it) },
        "availableAsSubproject" to availableAsSubproject.map { mapOf("id" to it.id, "name" to it.name) },
        "message" to message,
        "error" to error
    )
}

private fun subprojectSummaryModel(project: Project): Map<String, Any?> = mapOf(
    "id" to project.id,
    "name" to project.name,
    "status" to project.status.name,
    "priority" to project.priority.name
)

private fun entrySummaryModel(entry: ProjectEntry): Map<String, Any?> = mapOf(
    "id" to entry.id,
    "type" to entry.type.name,
    "textSegments" to entry.text?.let { linkifySegments(it) }.orEmpty(),
    "filename" to entry.filename,
    "createdAt" to entryDateFormatter.format(entry.createdAt)
)

// Splits an entry's raw text into alternating plain-text/URL segments so
// project.ftl can render every embedded link in place - not just a
// whole-field bare URL, and not just the first one, which is what a
// separate "show the extracted url, then the full text again below it"
// paragraph could do. Each segment still goes through FreeMarker's normal
// `${...}` auto-escaping in the template; this only decides *where* the
// anchor tag goes, it never builds raw HTML itself.
private fun linkifySegments(text: String): List<Map<String, Any?>> {
    val segments = mutableListOf<Map<String, Any?>>()
    var lastIndex = 0
    for (match in projectEntryUrlRegex.findAll(text)) {
        if (match.range.first > lastIndex) {
            segments += mapOf("isUrl" to false, "value" to text.substring(lastIndex, match.range.first))
        }
        // Trimmed punctuation (e.g. a trailing comma or period) falls back
        // into the next plain-text segment via lastIndex, rather than
        // being dropped.
        val url = trimTrailingUrlPunctuation(match.value)
        segments += mapOf("isUrl" to true, "value" to url)
        lastIndex = match.range.first + url.length
    }
    if (lastIndex < text.length) {
        segments += mapOf("isUrl" to false, "value" to text.substring(lastIndex))
    }
    return segments
}

private fun factSummaryModel(fact: HouseFact, filenameById: Map<String, String>): Map<String, Any?> = mapOf(
    "id" to fact.id,
    "what" to fact.what,
    "component" to fact.component.name,
    "sourceQuote" to fact.sourceQuote,
    "filename" to (filenameById[fact.documentId] ?: "deleted document")
)

private fun documentSummaryModel(document: HouseDocument): Map<String, Any?> = mapOf(
    "id" to document.id,
    "filename" to document.filename,
    "status" to document.status.name
)
