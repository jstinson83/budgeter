package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// A document the household has uploaded (home inspection, engineering
// drawings, invoices, ...) - the raw source material House Knowledge
// extracts HouseFacts from (HouseFactExtractor.kt). The PDF bytes
// themselves live in DocumentBlobStore (GCS), not here - Firestore
// documents are capped at 1MB and most real inspection PDFs exceed that;
// this is just the metadata/status record.
enum class HouseDocumentStatus { UPLOADED, EXTRACTING, EXTRACTED, FAILED }

data class HouseDocument(
    val id: String,
    val ownerId: String,
    val filename: String,
    // DocumentBlobStore path, not a public URL - see GcsDocumentBlobStore.
    val storagePath: String,
    val status: HouseDocumentStatus,
    val error: String? = null,
    val uploadedAt: Instant
)

interface HouseDocumentRepository {
    suspend fun add(ownerId: String, filename: String, storagePath: String): HouseDocument
    suspend fun all(ownerId: String): List<HouseDocument>
    suspend fun get(ownerId: String, id: String): HouseDocument?
    suspend fun updateStatus(ownerId: String, id: String, status: HouseDocumentStatus, error: String? = null)
    suspend fun delete(ownerId: String, id: String)
}

class FirestoreHouseDocumentStore(private val firestore: Firestore) : HouseDocumentRepository {
    private val collection = firestore.collection("houseDocuments")

    override suspend fun add(ownerId: String, filename: String, storagePath: String): HouseDocument {
        val docRef = collection.document()
        val uploadedAt = Instant.now()
        docRef.set(
            mapOf(
                "ownerId" to ownerId,
                "filename" to filename,
                "storagePath" to storagePath,
                "status" to HouseDocumentStatus.UPLOADED.name,
                "uploadedAt" to uploadedAt.toString()
            )
        ).get()
        return HouseDocument(docRef.id, ownerId, filename, storagePath, HouseDocumentStatus.UPLOADED, uploadedAt = uploadedAt)
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as FirestoreCategoryStore. Sorted
    // most-recent-first in memory instead.
    override suspend fun all(ownerId: String): List<HouseDocument> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toHouseDocument(it.id, it.data) }.sortedByDescending { it.uploadedAt }
    }

    override suspend fun get(ownerId: String, id: String): HouseDocument? {
        val snapshot = collection.document(id).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        if (data["ownerId"] as? String != ownerId) return null
        return toHouseDocument(snapshot.id, data)
    }

    override suspend fun updateStatus(ownerId: String, id: String, status: HouseDocumentStatus, error: String?) {
        // Re-fetches to confirm the doc belongs to this owner before writing
        // - same "trust nothing client-supplied" posture as
        // TransactionRoutes.kt's recategorize handler.
        get(ownerId, id) ?: return
        collection.document(id).update(mapOf("status" to status.name, "error" to error)).get()
    }

    override suspend fun delete(ownerId: String, id: String) {
        get(ownerId, id) ?: return
        collection.document(id).delete().get()
    }

    private fun toHouseDocument(id: String, data: Map<String, Any?>): HouseDocument = HouseDocument(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        filename = data["filename"] as? String ?: "",
        storagePath = data["storagePath"] as? String ?: "",
        status = (data["status"] as? String)?.let { runCatching { HouseDocumentStatus.valueOf(it) }.getOrNull() } ?: HouseDocumentStatus.UPLOADED,
        error = data["error"] as? String,
        uploadedAt = (data["uploadedAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}

// Raw PDF bytes, kept separate from HouseDocumentRepository's Firestore
// metadata - see HouseDocument's comment for why.
interface DocumentBlobStore {
    // Returns the storage path to persist on the HouseDocument record, not
    // a public URL - the bucket isn't public, callers read it back via
    // download().
    suspend fun upload(ownerId: String, documentId: String, filename: String, bytes: ByteArray): String
    suspend fun download(storagePath: String): ByteArray
    suspend fun delete(storagePath: String)
}
