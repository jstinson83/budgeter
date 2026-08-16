package com.budgeter

import io.ktor.http.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Chunk 1 of House Projects & Recommendations (see .claude/current.md):
// manual project creation/editing only - no recommendations, no linked
// facts/documents/entries yet.
fun Route.projectRoutes(projectStore: ProjectRepository) {
    get("/projects") {
        val ownerId = call.requireUserId()
        val componentFilter = call.request.queryParameters["component"]?.let { runCatching { Component.valueOf(it) }.getOrNull() }
        val projects = projectStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = projectsPageModel(projects, componentFilter, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("projects.ftl", model))
    }

    post("/projects") {
        val ownerId = call.requireUserId()
        val formParams = call.receiveParameters()
        val name = formParams["name"]?.trim().orEmpty()
        val status = formParams["status"]?.let { runCatching { ProjectStatus.valueOf(it) }.getOrNull() }
        val component = formParams["component"]?.let { runCatching { Component.valueOf(it) }.getOrNull() }
        val priority = formParams["priority"]?.let { runCatching { Priority.valueOf(it) }.getOrNull() }
        if (name.isEmpty() || status == null || component == null || priority == null) {
            call.respondRedirect("/projects?error=${"Please fill in all fields".encodeURLQueryComponent()}")
            return@post
        }
        val project = projectStore.add(ownerId, name, status, component, priority)
        call.respondRedirect("/projects/${project.id}?message=${"Created ${project.name}".encodeURLQueryComponent()}")
    }

    get("/projects/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val project = projectStore.get(ownerId, id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = projectPageModel(project, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("project.ftl", model))
    }

    post("/projects/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val name = formParams["name"]?.trim().orEmpty()
        val status = formParams["status"]?.let { runCatching { ProjectStatus.valueOf(it) }.getOrNull() }
        val component = formParams["component"]?.let { runCatching { Component.valueOf(it) }.getOrNull() }
        val priority = formParams["priority"]?.let { runCatching { Priority.valueOf(it) }.getOrNull() }
        if (name.isEmpty() || status == null || component == null || priority == null) {
            call.respondRedirect("/projects/$id?error=${"Please fill in all fields".encodeURLQueryComponent()}")
            return@post
        }
        val updated = projectStore.update(ownerId, id, name, status, component, priority)
        if (updated != null) {
            call.respondRedirect("/projects/$id?message=${"Updated".encodeURLQueryComponent()}")
        } else {
            call.respondRedirect("/projects?error=${"Project not found".encodeURLQueryComponent()}")
        }
    }
}
