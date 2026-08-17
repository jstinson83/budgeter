package com.budgeter

import com.google.cloud.firestore.Firestore
import java.time.Instant

// A project the household is running against the house - created manually
// (chunk 1 of House Projects & Recommendations, see .claude/current.md) or,
// later, accepted from a Recommendation. Can now link to existing
// HouseFact/HouseDocument rows (chunk 2). Still no
// notes/decisions/quotes/photos/links feed (chunk 3) yet.
enum class ProjectStatus { ACTIVE, PLANNED, DEPRIORITIZED, COMPLETED }

// Kept as its own enum rather than reusing HouseFact's Importance, even
// though both are High/Medium/Low - Importance is Gemini-assigned at
// extraction time and never edited, while a project's priority is meant to
// be freely user-editable after creation (and, once recommendations exist,
// only *seeded* from Gemini's suggestion, not owned by it going forward).
enum class Priority { HIGH, MEDIUM, LOW }

data class Project(
    val id: String,
    val ownerId: String,
    val name: String,
    val status: ProjectStatus,
    // Single top-level Component tag, same enum HouseFact uses - keeps
    // /projects filterable/browsable the same way /house/facts is. A
    // project spanning multiple components (e.g. a kitchen reno touching
    // structure/electrical/plumbing) just picks one dominant tag for now;
    // combining multiple single-component projects is deferred to a future
    // "merge projects" action, see current.md.
    val component: Component,
    val priority: Priority,
    val createdAt: Instant,
    // HouseFact/HouseDocument ids this project draws on - many-to-many, no
    // ownership implied: the same fact/document can back more than one
    // project. Order isn't meaningful; attach appends, detach removes.
    // Trail the constructor (with defaults) so every existing positional
    // Project(...) call site keeps compiling unchanged.
    val factIds: List<String> = emptyList(),
    val documentIds: List<String> = emptyList(),
    // Parent project this is a subproject of, or null for a top-level
    // project. Capped at one level (a subproject can't itself have
    // subprojects) - enforced by ProjectRoutes, not here; a subproject
    // still keeps its own status/component/priority/facts/documents/entries
    // in full, nothing about it is restricted just because it has a parent.
    val parentId: String? = null
)

interface ProjectRepository {
    suspend fun all(ownerId: String): List<Project>
    suspend fun get(ownerId: String, id: String): Project?
    suspend fun add(ownerId: String, name: String, status: ProjectStatus, component: Component, priority: Priority, parentId: String? = null): Project
    suspend fun update(ownerId: String, id: String, name: String, status: ProjectStatus, component: Component, priority: Priority): Project?
    suspend fun attachFact(ownerId: String, id: String, factId: String): Project?
    suspend fun detachFact(ownerId: String, id: String, factId: String): Project?
    suspend fun attachDocument(ownerId: String, id: String, documentId: String): Project?
    suspend fun detachDocument(ownerId: String, id: String, documentId: String): Project?
    // Sets or clears (parentId = null) this project's parent. A single
    // setter rather than separate attach/detach methods, unlike
    // fact/document links, since a project has at most one parent rather
    // than a list.
    suspend fun setParent(ownerId: String, id: String, parentId: String?): Project?
}

class FirestoreProjectStore(private val firestore: Firestore) : ProjectRepository {
    private val collection = firestore.collection("projects")

    override suspend fun add(ownerId: String, name: String, status: ProjectStatus, component: Component, priority: Priority, parentId: String?): Project {
        val docRef = collection.document()
        val createdAt = Instant.now()
        docRef.set(projectMap(ownerId, name, status, component, priority, createdAt, parentId)).get()
        return Project(docRef.id, ownerId, name, status, component, priority, createdAt, parentId = parentId)
    }

    // Single-field ownerId equality filter, no orderBy - same
    // no-composite-index shape as the rest of this app's per-owner
    // collections. Sorted most-recent-first in memory instead.
    override suspend fun all(ownerId: String): List<Project> {
        val snapshot = collection.whereEqualTo("ownerId", ownerId).get().get()
        return snapshot.documents.map { toProject(it.id, it.data) }.sortedByDescending { it.createdAt }
    }

    override suspend fun get(ownerId: String, id: String): Project? {
        val snapshot = collection.document(id).get().get()
        if (!snapshot.exists()) return null
        val data = snapshot.data ?: return null
        if (data["ownerId"] as? String != ownerId) return null
        return toProject(snapshot.id, data)
    }

    override suspend fun update(ownerId: String, id: String, name: String, status: ProjectStatus, component: Component, priority: Priority): Project? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).update(
            mapOf(
                "name" to name,
                "status" to status.name,
                "component" to component.name,
                "priority" to priority.name
            )
        ).get()
        return existing.copy(name = name, status = status, component = component, priority = priority)
    }

    override suspend fun attachFact(ownerId: String, id: String, factId: String): Project? {
        val existing = get(ownerId, id) ?: return null
        if (factId in existing.factIds) return existing
        val factIds = existing.factIds + factId
        collection.document(id).update("factIds", factIds).get()
        return existing.copy(factIds = factIds)
    }

    override suspend fun detachFact(ownerId: String, id: String, factId: String): Project? {
        val existing = get(ownerId, id) ?: return null
        val factIds = existing.factIds - factId
        collection.document(id).update("factIds", factIds).get()
        return existing.copy(factIds = factIds)
    }

    override suspend fun attachDocument(ownerId: String, id: String, documentId: String): Project? {
        val existing = get(ownerId, id) ?: return null
        if (documentId in existing.documentIds) return existing
        val documentIds = existing.documentIds + documentId
        collection.document(id).update("documentIds", documentIds).get()
        return existing.copy(documentIds = documentIds)
    }

    override suspend fun detachDocument(ownerId: String, id: String, documentId: String): Project? {
        val existing = get(ownerId, id) ?: return null
        val documentIds = existing.documentIds - documentId
        collection.document(id).update("documentIds", documentIds).get()
        return existing.copy(documentIds = documentIds)
    }

    override suspend fun setParent(ownerId: String, id: String, parentId: String?): Project? {
        val existing = get(ownerId, id) ?: return null
        collection.document(id).update("parentId", parentId).get()
        return existing.copy(parentId = parentId)
    }

    private fun projectMap(ownerId: String, name: String, status: ProjectStatus, component: Component, priority: Priority, createdAt: Instant, parentId: String?): Map<String, Any?> = mapOf(
        "ownerId" to ownerId,
        "name" to name,
        "status" to status.name,
        "component" to component.name,
        "priority" to priority.name,
        "createdAt" to createdAt.toString(),
        "factIds" to emptyList<String>(),
        "documentIds" to emptyList<String>(),
        "parentId" to parentId
    )

    private fun toProject(id: String, data: Map<String, Any?>): Project = Project(
        id = id,
        ownerId = data["ownerId"] as? String ?: "",
        name = data["name"] as? String ?: "",
        status = (data["status"] as? String)?.let { runCatching { ProjectStatus.valueOf(it) }.getOrNull() } ?: ProjectStatus.PLANNED,
        component = (data["component"] as? String)?.let { runCatching { Component.valueOf(it) }.getOrNull() } ?: Component.OTHER,
        priority = (data["priority"] as? String)?.let { runCatching { Priority.valueOf(it) }.getOrNull() } ?: Priority.MEDIUM,
        createdAt = (data["createdAt"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: Instant.EPOCH,
        factIds = (data["factIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        documentIds = (data["documentIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        parentId = data["parentId"] as? String
    )
}
