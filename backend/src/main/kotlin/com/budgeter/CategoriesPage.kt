package com.budgeter

// Same flattening rationale as AnalysisPage.kt/TransactionPage.kt -
// pre-formats everything /categories.ftl needs (category management plus
// full CRUD over the household's saved CategorizationRules).
fun categoriesPageModel(
    categories: List<Category>,
    rules: List<CategorizationRule>,
    message: String?,
    error: String?
): Map<String, Any?> {
    val labelById = categories.associateBy({ it.id }, { it.label })
    val sortedCategories = categories.sortedBy { it.label.lowercase() }
    return mapOf(
        "categories" to sortedCategories.map {
            mapOf("id" to it.id, "label" to it.label, "active" to it.active)
        },
        // Only active categories are offered as a rule's target - same
        // "disabling stops new assignment" rule /analysis/category uses.
        "categoryOptions" to sortedCategories.filter { it.active }.map { mapOf("name" to it.id, "label" to it.label) },
        "rules" to rules.map {
            mapOf(
                "id" to it.id,
                "pattern" to it.pattern,
                "matchType" to it.matchType.name,
                "categoryId" to it.category,
                "categoryLabel" to (labelById[it.category] ?: it.category)
            )
        },
        "message" to message,
        "error" to error
    )
}
