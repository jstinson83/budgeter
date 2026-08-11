package com.budgeter

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.freemarker.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.toByteArray
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

// The "MVP: House Knowledge" workflow from product_spec.md: upload a
// document, extract candidate facts via Gemini, let the homeowner resolve
// the ambiguous ones. Extraction runs on HouseFactExtractionJobManager's
// background scope, not inline in the upload request - see
// HouseFactExtractionJob.kt for why (a large/slow document could otherwise
// run Gemini long enough to hit Cloud Run's request timeout).
fun Route.houseRoutes(
    houseDocumentStore: HouseDocumentRepository,
    houseFactStore: HouseFactRepository,
    documentBlobStore: DocumentBlobStore,
    houseFactExtractionJobManager: HouseFactExtractionJobManager
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

        // Marked EXTRACTING here, synchronously, before this request ends -
        // the background job (HouseFactExtractionJob.kt) only owns the
        // Gemini call itself and the EXTRACTED/FAILED transition once it's
        // done. Redirects immediately rather than waiting for extraction to
        // finish; house-document.ftl polls GET /house/documents/{id}/status
        // while EXTRACTING and reloads once it isn't.
        houseDocumentStore.updateStatus(ownerId, document.id, HouseDocumentStatus.EXTRACTING)
        houseFactExtractionJobManager.start(ownerId, document.id, name, pdfBytes)

        call.respondRedirect("/house/documents/${document.id}")
    }

    get("/house/documents/{id}") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val document = houseDocumentStore.get(ownerId, id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val facts = houseFactStore.forDocument(ownerId, id)
        // consumeTerminal rather than status: this is the one page render
        // that ever shows a just-finished extraction job's outcome, so it
        // also forgets that job once shown - otherwise a later unrelated
        // visit would keep re-displaying the same "Found N thing(s)..."
        // banner indefinitely (see HouseFactExtractionJob.kt). Query params
        // still take priority - e.g. the "Updated"/"Fact not found" messages
        // POST /house/facts/{id}/resolve redirects back with.
        val jobState = houseFactExtractionJobManager.consumeTerminal(id)
        val message = call.request.queryParameters["message"]
            ?: jobState?.takeIf { it.status == ExtractionJobStatus.DONE }
                ?.let { "Found ${it.factCount} thing(s) worth remembering about your house" }
        val error = call.request.queryParameters["error"]
            ?: jobState?.takeIf { it.status == ExtractionJobStatus.FAILED }?.let { "Extraction failed - see status below" }
        val model = houseDocumentPageModel(document, facts, message, error) + call.currentUserModel()
        call.respond(FreeMarkerContent("house-document.ftl", model))
    }

    // Polled by house-document.ftl's inline script while the document is
    // EXTRACTING (see HouseFactExtractionJob.kt for why polling matters
    // beyond just the UI on Cloud Run). Reads document status straight from
    // HouseDocumentRepository rather than the job manager - that's already
    // the persisted source of truth the document page itself renders from,
    // and it's what the background job updates on completion regardless of
    // whether this process instance is the one that observes it.
    get("/house/documents/{id}/status") {
        val ownerId = call.requireUserId()
        val id = call.parameters["id"] ?: return@get call.respond(HttpStatusCode.NotFound)
        val document = houseDocumentStore.get(ownerId, id) ?: return@get call.respond(HttpStatusCode.NotFound)
        val response = ExtractionJobStatusResponse(status = document.status.name, error = document.error)
        call.respondText(Json.encodeToString(response), ContentType.Application.Json)
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
