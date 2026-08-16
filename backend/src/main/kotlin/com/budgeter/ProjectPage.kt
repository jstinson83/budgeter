package com.budgeter

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

fun projectPageModel(project: Project, message: String?, error: String?): Map<String, Any?> = mapOf(
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
    "message" to message,
    "error" to error
)
