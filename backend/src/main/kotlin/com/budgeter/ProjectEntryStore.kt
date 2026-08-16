package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// A single item in a project's accumulating feed - notes, quotes/invoices,
// photos, and links to things being considered (chunk 3 of House Projects &
// Recommendations, see .claude/current.md). Kept as one type with a
// discriminator rather than four parallel collections, since they all
// render as one chronological feed on the project detail page.
//
// The entry form itself is a single free-form text field plus an optional
// file attachment - type is inferred server-side (ProjectRoutes.kt), not
// picked by the user: a URL found in the text makes it LINK, an attached
// image makes it PHOTO, any other attached file makes it QUOTE, otherwise
// it's NOTE. There's no separate DECISION type - a decision has no
// reliable textual signal to detect it from (unlike a URL or a file), and
// once you strip that down a "decision" is just a NOTE whose text happens
// to record one. Revisit only if that turns out to lose something real.
enum class ProjectEntryType { NOTE, QUOTE, PHOTO, LINK }

// Shared between ProjectRoutes.kt (deciding an entry's type, and what
// substring becomes ProjectEntry.url) and ProjectPage.kt (splitting text
// into segments so project.ftl can linkify any URL in place, not just a
// whole-field bare one) - a URL is "http(s):// followed by a run of
// non-whitespace characters." Every match should be passed through
// trimTrailingUrlPunctuation below before being treated as a real URL.
internal val projectEntryUrlRegex = Regex("""https?://\S+""")

private val trailingUrlPunctuation = ".,;:!?'\"".toSet()

// \S+ happily sweeps up sentence punctuation directly after a URL with no
// space ("check the quote: https://example.com/x." -> the trailing '.'
// isn't part of the URL) - left uncorrected, that punctuation ends up
// inside the href and the link 404s. Strips generic trailing punctuation
// unconditionally, and a trailing ')' only when it's unbalanced within the
// match (so a URL that legitimately ends in a balanced parenthetical, e.g.
// a Wikipedia "(disambiguation)" link, is left alone).
internal fun trimTrailingUrlPunctuation(url: String): String {
    var end = url.length
    while (end > 0) {
        val c = url[end - 1]
        val isUnbalancedCloseParen = c == ')' &&
            url.substring(0, end).count { it == '(' } < url.substring(0, end).count { it == ')' }
        if (c in trailingUrlPunctuation || isUnbalancedCloseParen) end-- else break
    }
    return url.substring(0, end)
}

data class ProjectEntry(
    val id: String,
    val ownerId: String,
    val projectId: String,
    val type: ProjectEntryType,
    // The raw free-form text the homeowner typed - present for every entry
    // that had any text (including LINK, where it's the full typed text,
    // not just the extracted URL below).
    val text: String?,
    // Only set for LINK - the URL extracted from text (ProjectRoutes.kt),
    // kept separately so project.ftl can render it as a real link without
    // re-parsing text itself.
    val url: String? = null,
    // Only set for QUOTE/PHOTO - an attached file, uploaded straight to
    // DocumentBlobStore (via ProjectRoutes.kt's entryBlobStore), *not*
    // routed through HouseDocument/HouseFactExtractor. A quote or photo
    // attached here isn't house-knowledge source material to extract facts
    // from, and unlike HouseDocument's upload path it isn't PDF-only - a
    // photo is typically a JPEG/PNG, which HouseRoutes.kt's upload handler
    // explicitly rejects.
    val storagePath: String? = null,
    val filename: String? = null,
    val createdAt: Instant
)

interface ProjectEntryRepository {
    suspend fun forProject(ownerId: String, projectId: String): List<ProjectEntry>
    suspend fun get(ownerId: String, id: String): ProjectEntry?
    suspend fun add(
        ownerId: String,
        projectId: String,
        type: ProjectEntryType,
        text: String?,
        url: String? = null,
        storagePath: String? = null,
        filename: String? = null
    ): ProjectEntry
    suspend fun delete(ownerId: String, id: String)
}

class FirestoreProjectEntryStore(private val firestore: Firestore) : ProjectEntryRepository {
    private val collection = firestore.collection("projectEntries")

    override suspend fun add(
        ownerId: String,
        projectId: String,
        type: ProjectEntryType,
        text: String?,
        url: String?,
        storagePath: String?,
        filename: String?
    ): ProjectEntry {
        val docRef = collection.document()
        val createdAt = Instant.now()
        docRef.set(
            mapOf(
                "ownerId" to ownerId,
                "projectId" to projectId,
                "type" to type.name,
                "text" to text,
                "url" to url,
                "storagePath" to storagePath,
                "filename" to filename,
                "createdAt" to createdAt.toString()
            )
        ).get()
        return ProjectEntry(docRef.id, ownerId, projectId, type, text, url, storagePath, filename, createdAt)
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as the rest of this app's per-owner
    // collections (see HouseFactRepository.forDocument). Filtered to this
    // project and sorted most-recent-first in memory instead.
    override suspend fun forProject(ownerId: String, projectId: String): List<ProjectEntry> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents
            .map { toProjectEntry(it.id, it.data) }
            .filter { it.projectId == projectId }
            .sortedByDescending { it.createdAt }
    }

    override suspend fun get(ownerId: String, id: String): ProjectEntry? {
        val snapshot = collection.document(id).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        if (data["ownerId"] as? String != ownerId) return null
        return toProjectEntry(snapshot.id, data)
    }

    override suspend fun delete(ownerId: String, id: String) {
        get(ownerId, id) ?: return
        collection.document(id).delete().get()
    }

    private fun toProjectEntry(id: String, data: Map<String, Any?>): ProjectEntry = ProjectEntry(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        projectId = data["projectId"] as? String ?: "",
        type = (data["type"] as? String)?.let { runCatching { ProjectEntryType.valueOf(it) }.getOrNull() } ?: ProjectEntryType.NOTE,
        text = data["text"] as? String,
        url = data["url"] as? String,
        storagePath = data["storagePath"] as? String,
        filename = data["filename"] as? String,
        createdAt = (data["createdAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH
    )
}
