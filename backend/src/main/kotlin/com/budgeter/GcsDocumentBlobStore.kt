package com.budgeter

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage

// Real GCS-backed DocumentBlobStore. The bucket is provisioned manually in
// the GCP console, same as the "home-os" Firestore database and the
// Cloud Run env vars - see CLAUDE.md's deploy pipeline section - since it
// needs to exist before HOUSE_DOCUMENTS_BUCKET can be set on the deployed
// service. Objects are namespaced by owner then document id so one
// household's files can never collide with another's regardless of
// filename.
class GcsDocumentBlobStore(private val storage: Storage, private val bucket: String) : DocumentBlobStore {

    override suspend fun upload(ownerId: String, documentId: String, filename: String, bytes: ByteArray): String {
        check(bucket.isNotBlank()) { "HOUSE_DOCUMENTS_BUCKET is not set" }
        val objectPath = "$ownerId/$documentId/$filename"
        val blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, objectPath))
            .setContentType("application/pdf")
            .build()
        storage.create(blobInfo, bytes)
        return objectPath
    }

    override suspend fun download(storagePath: String): ByteArray {
        check(bucket.isNotBlank()) { "HOUSE_DOCUMENTS_BUCKET is not set" }
        val blob = storage.get(BlobId.of(bucket, storagePath))
            ?: error("House document blob not found: $storagePath")
        return blob.getContent()
    }
}
