package com.budgeter

// Same flattening rationale as CategoriesPage.kt/AnalysisPage.kt -
// pre-formats what house.ftl needs: the upload form plus a per-document
// list with fact counts, so the list reads as "12 facts, 2 need review"
// without the template doing any counting itself.
fun housePageModel(documents: List<HouseDocument>, facts: List<HouseFact>, message: String?, error: String?): Map<String, Any?> {
    val factsByDocument = facts.groupBy { it.documentId }
    return mapOf(
        "documents" to documents.map { document ->
            val documentFacts = factsByDocument[document.id].orEmpty()
            mapOf(
                "id" to document.id,
                "filename" to document.filename,
                "status" to document.status.name,
                "error" to document.error,
                "factCount" to documentFacts.size,
                "needsReviewCount" to documentFacts.count { it.needsReview }
            )
        },
        "message" to message,
        "error" to error
    )
}

// needsReviewFacts/facts split lets house-document.ftl put the ambiguous
// ones (spec's "Identify ambiguity" step) above the already-accepted ones,
// rather than the template re-deriving the split itself.
fun houseDocumentPageModel(document: HouseDocument, facts: List<HouseFact>, message: String?, error: String?): Map<String, Any?> {
    val (needsReview, accepted) = facts.partition { it.needsReview }
    return mapOf(
        "document" to mapOf(
            "id" to document.id,
            "filename" to document.filename,
            "status" to document.status.name,
            "context" to document.context,
            "error" to document.error
        ),
        "needsReviewFacts" to needsReview.map { factModel(it) },
        "facts" to accepted.map { factModel(it) },
        "message" to message,
        "error" to error
    )
}

private fun factModel(fact: HouseFact): Map<String, Any?> = mapOf(
    "id" to fact.id,
    "documentId" to fact.documentId,
    "what" to fact.what,
    "type" to fact.type.name,
    "component" to fact.component.name,
    "sourceQuote" to fact.sourceQuote,
    "reviewQuestion" to fact.reviewQuestion,
    "homeownerContext" to fact.homeownerContext
)

// /house/facts - the cross-document view: every fact the household has
// ever extracted, grouped by component instead of by document, plus
// whatever Gemini-generated summary exists for that component (see
// HouseComponentSummarizer.kt). documents is only used to resolve each
// fact's originating filename for the "from <filename>" link back to its
// source document - review/resolve actions still live on the per-document
// page, not duplicated here.
fun houseFactsPageModel(
    facts: List<HouseFact>,
    documents: List<HouseDocument>,
    summaries: Map<Component, ComponentSummary>,
    message: String?,
    error: String?
): Map<String, Any?> {
    val filenameById = documents.associate { it.id to it.filename }
    val factsByComponent = facts.groupBy { it.component }
    val groups = Component.entries.mapNotNull { component ->
        val componentFacts = factsByComponent[component].orEmpty()
        if (componentFacts.isEmpty()) return@mapNotNull null
        val summary = summaries[component]
        mapOf(
            "component" to component.name,
            "factCount" to componentFacts.size,
            "needsReviewCount" to componentFacts.count { it.needsReview },
            "summary" to summary?.summary,
            "summaryStale" to (summary != null && summary.factCount != componentFacts.size),
            "facts" to componentFacts.map { fact ->
                factModel(fact) + mapOf("filename" to (filenameById[fact.documentId] ?: "deleted document"))
            }
        )
    }
    return mapOf(
        "groups" to groups,
        "message" to message,
        "error" to error
    )
}
