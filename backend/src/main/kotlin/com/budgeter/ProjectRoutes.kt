package com.budgeter

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import java.util.UUID

// Chunks 1-3 of House Projects & Recommendations (see .claude/current.md):
// manual project creation/editing, linking existing HouseFact/HouseDocument
// rows to a project, and a notes/decisions/quotes/photos/links feed. No
// recommendations yet.
fun Route.projectRoutes(
    projectStore: ProjectRepository,
    houseFactStore: HouseFactRepository,
    houseDocumentStore: HouseDocumentRepository,
    projectEntryStore: ProjectEntryRepository,
    // Reused for QUOTE/PHOTO attachments rather than a dedicated bucket -
    // same GCS bucket HouseDocument uploads use, but these uploads never
    // become a HouseDocument row and never go through
    // HouseFactExtractionJobManager. See ProjectEntryStore.kt.
    entryBlobStore: DocumentBlobStore
) {
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
        val facts = houseFactStore.all(ownerId)
        val documents = houseDocumentStore.all(ownerId)
        val entries = projectEntryStore.forProject(ownerId, id)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = projectPageModel(project, facts, documents, entries, message, error) + call.currentUserModel()
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

    // factId/documentId are client-supplied (the picker's <select> value) -
    // re-resolved against this owner's own facts/documents rather than
    // trusted directly, same "confirm ownership before touching it" posture
    // as /analysis/recategorize's transactionId handling.
    post("/projects/{id}/facts") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val fact = formParams["factId"]?.let { factId -> houseFactStore.all(ownerId).find { it.id == factId } }
            ?: return@post call.respondRedirect("/projects/$id?error=${"Fact not found".encodeURLQueryComponent()}")
        val updated = projectStore.attachFact(ownerId, id, fact.id)
            ?: return@post call.respondRedirect("/projects?error=${"Project not found".encodeURLQueryComponent()}")
        call.respondRedirect("/projects/${updated.id}?message=${"Linked fact".encodeURLQueryComponent()}")
    }

    post("/projects/{id}/facts/{factId}/detach") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val factId = call.parameters["factId"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val updated = projectStore.detachFact(ownerId, id, factId) ?: return@post call.respond(HttpStatusCode.NotFound)
        call.respondRedirect("/projects/${updated.id}?message=${"Removed fact".encodeURLQueryComponent()}")
    }

    post("/projects/{id}/documents") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val document = formParams["documentId"]?.let { houseDocumentStore.get(ownerId, it) }
            ?: return@post call.respondRedirect("/projects/$id?error=${"Document not found".encodeURLQueryComponent()}")
        val updated = projectStore.attachDocument(ownerId, id, document.id)
            ?: return@post call.respondRedirect("/projects?error=${"Project not found".encodeURLQueryComponent()}")
        call.respondRedirect("/projects/${updated.id}?message=${"Linked document".encodeURLQueryComponent()}")
    }

    post("/projects/{id}/documents/{documentId}/detach") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val documentId = call.parameters["documentId"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val updated = projectStore.detachDocument(ownerId, id, documentId) ?: return@post call.respond(HttpStatusCode.NotFound)
        call.respondRedirect("/projects/${updated.id}?message=${"Removed document".encodeURLQueryComponent()}")
    }

    // One multipart form on project.ftl covers all five entry types - type
    // picks which fields actually matter (text for Note/Decision, url for
    // Link, an uploaded file for Quote/Photo); the rest are ignored rather
    // than the template conditionally hiding fields with JS, matching this
    // app's no-framework posture elsewhere.
    post("/projects/{id}/entries") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        projectStore.get(ownerId, id) ?: return@post call.respond(HttpStatusCode.NotFound)

        var type: ProjectEntryType? = null
        var text: String? = null
        var url: String? = null
        var filename: String? = null
        var bytes: ByteArray? = null
        call.receiveMultipart().forEachPart { part ->
            when {
                part is PartData.FormItem && part.name == "type" ->
                    type = runCatching { ProjectEntryType.valueOf(part.value) }.getOrNull()
                part is PartData.FormItem && part.name == "text" -> text = part.value.trim().ifEmpty { null }
                part is PartData.FormItem && part.name == "url" -> url = part.value.trim().ifEmpty { null }
                part is PartData.FileItem && bytes == null && !part.originalFileName.isNullOrBlank() -> {
                    filename = part.originalFileName
                    bytes = part.provider().toByteArray()
                }
            }
            part.dispose()
        }

        val entryType = type ?: return@post call.respondRedirect("/projects/$id?error=${"Choose an entry type".encodeURLQueryComponent()}")
        when (entryType) {
            ProjectEntryType.NOTE, ProjectEntryType.DECISION -> {
                if (text.isNullOrEmpty()) {
                    return@post call.respondRedirect("/projects/$id?error=${"Please add some text".encodeURLQueryComponent()}")
                }
            }
            ProjectEntryType.LINK -> {
                if (url.isNullOrEmpty()) {
                    return@post call.respondRedirect("/projects/$id?error=${"Please add a URL".encodeURLQueryComponent()}")
                }
            }
            ProjectEntryType.QUOTE, ProjectEntryType.PHOTO -> {
                if (bytes == null || bytes!!.isEmpty() || filename.isNullOrEmpty()) {
                    return@post call.respondRedirect("/projects/$id?error=${"Please choose a file to attach".encodeURLQueryComponent()}")
                }
            }
        }

        val storagePath = if (entryType == ProjectEntryType.QUOTE || entryType == ProjectEntryType.PHOTO) {
            entryBlobStore.upload(ownerId, UUID.randomUUID().toString(), filename!!, bytes!!)
        } else null
        val entryFilename = if (storagePath != null) filename else null
        val entryUrl = if (entryType == ProjectEntryType.LINK) url else null

        projectEntryStore.add(ownerId, id, entryType, text, entryUrl, storagePath, entryFilename)
        call.respondRedirect("/projects/$id?message=${"Added".encodeURLQueryComponent()}")
    }

    post("/projects/{id}/entries/{entryId}/delete") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val entryId = call.parameters["entryId"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val entry = projectEntryStore.get(ownerId, entryId)?.takeIf { it.projectId == id }
            ?: return@post call.respond(HttpStatusCode.NotFound)

        entry.storagePath?.let { entryBlobStore.delete(it) }
        projectEntryStore.delete(ownerId, entryId)
        call.respondRedirect("/projects/$id?message=${"Removed".encodeURLQueryComponent()}")
    }
}
