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
    "sourceQuote" to fact.sourceQuote,
    "reviewQuestion" to fact.reviewQuestion,
    "homeownerContext" to fact.homeownerContext
)
