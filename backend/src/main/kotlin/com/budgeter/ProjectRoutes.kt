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
// rows to a project, and a notes/quotes/photos/links feed. No
// recommendations yet.

private val urlRegex = Regex("""https?://\S+""")
private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "svg")

// Infers a ProjectEntry's type from what was actually submitted, rather
// than asking the homeowner to pick one - see ProjectEntryStore.kt for the
// reasoning. An attached file wins over a URL-in-text (a captioned photo
// with a link in the caption is still a photo); checked by extension since
// a multipart part's declared Content-Type is client-supplied and not
// always set/accurate for images.
private fun detectEntryType(text: String?, filename: String?): ProjectEntryType {
    if (filename != null) {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return if (extension in imageExtensions) ProjectEntryType.PHOTO else ProjectEntryType.QUOTE
    }
    if (text != null && urlRegex.containsMatchIn(text)) return ProjectEntryType.LINK
    return ProjectEntryType.NOTE
}
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

    // One free-form multipart form on project.ftl covers every entry type -
    // a single text field plus an optional file attachment. Type is
    // inferred from what was actually submitted (detectEntryType above),
    // not picked by the homeowner.
    post("/projects/{id}/entries") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        projectStore.get(ownerId, id) ?: return@post call.respond(HttpStatusCode.NotFound)

        var text: String? = null
        var filename: String? = null
        var bytes: ByteArray? = null
        call.receiveMultipart().forEachPart { part ->
            when {
                part is PartData.FormItem && part.name == "text" -> text = part.value.trim().ifEmpty { null }
                part is PartData.FileItem && bytes == null && !part.originalFileName.isNullOrBlank() -> {
                    filename = part.originalFileName
                    bytes = part.provider().toByteArray()
                }
            }
            part.dispose()
        }

        val hasFile = bytes != null && bytes!!.isNotEmpty() && !filename.isNullOrEmpty()
        if (text.isNullOrEmpty() && !hasFile) {
            return@post call.respondRedirect("/projects/$id?error=${"Please add some text or a file".encodeURLQueryComponent()}")
        }

        val entryType = detectEntryType(text, filename.takeIf { hasFile })
        val storagePath = if (hasFile) entryBlobStore.upload(ownerId, UUID.randomUUID().toString(), filename!!, bytes!!) else null
        val url = if (entryType == ProjectEntryType.LINK) urlRegex.find(text!!)?.value else null

        projectEntryStore.add(ownerId, id, entryType, text, url, storagePath, filename.takeIf { hasFile })
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
