package com.budgeter

import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val entryDateFormatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())

// Pre-formats what projects.ftl needs: an optional component filter, plus
// projects grouped by status (Active/Planned/Deprioritized/Completed, in
// that display order) - same "skip empty groups" pattern
// houseFactsPageModel uses for its component groups.
fun projectsPageModel(projects: List<Project>, componentFilter: Component?, message: String?, error: String?): Map<String, Any?> {
    val filtered = if (componentFilter != null) projects.filter { it.component == componentFilter } else projects
    val byStatus = filtered.groupBy { it.status }
    val groups = ProjectStatus.entries.mapNotNull { status ->
        val statusProjects = byStatus[status].orEmpty()
        if (statusProjects.isEmpty()) return@mapNotNull null
        mapOf(
            "status" to status.name,
            "projects" to statusProjects.map { projectSummaryModel(it) }
        )
    }
    return mapOf(
        "groups" to groups,
        "componentOptions" to Component.entries.map { it.name },
        "selectedComponent" to componentFilter?.name,
        "message" to message,
        "error" to error
    )
}

private fun projectSummaryModel(project: Project): Map<String, Any?> = mapOf(
    "id" to project.id,
    "name" to project.name,
    "component" to project.component.name,
    "priority" to project.priority.name
)

// allFacts/allDocuments are the owner's whole collections (not pre-filtered
// to this project) - split here into what's already linked vs. what's still
// available to attach, so project.ftl's picker never offers something
// that's already on the project.
fun projectPageModel(
    project: Project,
    allFacts: List<HouseFact>,
    allDocuments: List<HouseDocument>,
    entries: List<ProjectEntry>,
    message: String?,
    error: String?
): Map<String, Any?> {
    val filenameById = allDocuments.associate { it.id to it.filename }
    val (linkedFacts, availableFacts) = allFacts.partition { it.id in project.factIds }
    val (linkedDocuments, availableDocuments) = allDocuments.partition { it.id in project.documentIds }
    return mapOf(
        "project" to mapOf(
            "id" to project.id,
            "name" to project.name,
            "status" to project.status.name,
            "component" to project.component.name,
            "priority" to project.priority.name
        ),
        "statusOptions" to ProjectStatus.entries.map { it.name },
        "componentOptions" to Component.entries.map { it.name },
        "priorityOptions" to Priority.entries.map { it.name },
        "linkedFacts" to linkedFacts.map { factSummaryModel(it, filenameById) },
        "availableFacts" to availableFacts.map { factSummaryModel(it, filenameById) },
        "linkedDocuments" to linkedDocuments.map { documentSummaryModel(it) },
        "availableDocuments" to availableDocuments.map { documentSummaryModel(it) },
        "entries" to entries.map { entrySummaryModel(it) },
        "message" to message,
        "error" to error
    )
}

private fun entrySummaryModel(entry: ProjectEntry): Map<String, Any?> = mapOf(
    "id" to entry.id,
    "type" to entry.type.name,
    "text" to entry.text,
    "url" to entry.url,
    "filename" to entry.filename,
    "createdAt" to entryDateFormatter.format(entry.createdAt)
)

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
