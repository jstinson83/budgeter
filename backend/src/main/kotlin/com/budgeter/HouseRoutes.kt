package com.budgeter

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import java.util.UUID

// The "MVP: House Knowledge" workflow from product_spec.md: upload a
// document, extract candidate facts via Gemini, let the homeowner resolve
// the ambiguous ones. Extraction runs synchronously in the upload request
// (unlike Gemini categorization's background job - see CategorizationJob.kt)
// since this is one Gemini call per document rather than a chunked batch;
// revisit with the same async/poll pattern if a large document's extraction
// time ever runs into Cloud Run's request timeout.
fun Route.houseRoutes(
    houseDocumentStore: HouseDocumentRepository,
    houseFactStore: HouseFactRepository,
    documentBlobStore: DocumentBlobStore,
    houseFactExtractor: HouseFactExtractor
) {
    get("/house") {
        val ownerId = call.requireUserId()
        val documents = houseDocumentStore.all(ownerId)
        val facts = houseFactStore.all(ownerId)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = housePageModel(documents, facts, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("house.ftl", model))
    }

    post("/house/documents/upload") {
        val ownerId = call.requireUserId()

        var filename: String? = null
        var bytes: ByteArray? = null
        call.receiveMultipart().forEachPart { part ->
            if (part is PartData.FileItem && bytes == null) {
                filename = part.originalFileName
                bytes = part.provider().toByteArray()
            }
            part.dispose()
        }

        val pdfBytes = bytes
        val name = filename?.trim().orEmpty()
        if (pdfBytes == null || pdfBytes.isEmpty() || name.isEmpty()) {
            call.respondRedirect("/house?error=${"No file uploaded".encodeURLQueryComponent()}")
            return@post
        }
        // PDF-only for now, same "one format, strict" posture as the CSV
        // importer - matches the household documents the spec's MVP
        // examples are all built around (inspections, engineering
        // drawings).
        if (!name.lowercase().endsWith(".pdf")) {
            call.respondRedirect("/house?error=${"Only PDF documents are supported".encodeURLQueryComponent()}")
            return@post
        }

        // Blob uploaded under a fresh random id rather than the eventual
        // Firestore document id - avoids a two-phase
        // create-then-patch-storagePath round trip just to learn an id.
        val storagePath = documentBlobStore.upload(ownerId, UUID.randomUUID().toString(), name, pdfBytes)
        val document = houseDocumentStore.add(ownerId, name, storagePath)

        val extractedCount = try {
            houseDocumentStore.updateStatus(ownerId, document.id, HouseDocumentStatus.EXTRACTING)
            val extracted = houseFactExtractor.extract(name, pdfBytes)
            houseFactStore.addAll(ownerId, document.id, extracted)
            houseDocumentStore.updateStatus(ownerId, document.id, HouseDocumentStatus.EXTRACTED)
            extracted.size
        } catch (e: Exception) {
            houseDocumentStore.updateStatus(ownerId, document.id, HouseDocumentStatus.FAILED, e.message)
            null
        }

        if (extractedCount != null) {
            val message = "Found $extractedCount thing(s) worth remembering about your house"
            call.respondRedirect("/house/documents/${document.id}?message=${message.encodeURLQueryComponent()}")
        } else {
            call.respondRedirect("/house/documents/${document.id}?error=${"Extraction failed - see status below".encodeURLQueryComponent()}")
        }
    }

    get("/house/documents/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val document = houseDocumentStore.get(ownerId, id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val facts = houseFactStore.forDocument(ownerId, id)
        val message = call.request.queryParameters["message"]
        val error = call.request.queryParameters["error"]
        val model = houseDocumentPageModel(document, facts, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("house-document.ftl", model))
    }

    // homeownerContext is either one of house-document.ftl's preset quick
    // answers or the free-text "Add my own explanation" field - both submit
    // the same form field, so this handler treats them identically. The
    // original sourceQuote/reviewQuestion are never touched: this only ever
    // adds a second, separate layer on top, per the spec's "never overwrite
    // historical source material" principle.
    post("/house/facts/{id}/resolve") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@post call.respond(HttpStatusCode.NotFound)
        val formParams = call.receiveParameters()
        val documentId = formParams["documentId"].orEmpty()
        val context = formParams["homeownerContext"]?.trim().orEmpty()
        if (context.isEmpty()) {
            call.respondRedirect("/house/documents/$documentId?error=${"Please choose or describe an answer".encodeURLQueryComponent()}")
            return@post
        }

        val resolved = houseFactStore.resolve(ownerId, id, context)
        val redirectDocumentId = resolved?.documentId ?: documentId
        if (resolved != null) {
            call.respondRedirect("/house/documents/$redirectDocumentId?message=${"Updated".encodeURLQueryComponent()}")
        } else {
            call.respondRedirect("/house/documents/$redirectDocumentId?error=${"Fact not found".encodeURLQueryComponent()}")
        }
    }
}
