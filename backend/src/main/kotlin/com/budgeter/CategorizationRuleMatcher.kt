package com.budgeter

// Applies the household's own saved categorization rules to
// still-uncategorized transactions, checked after TransferMatcher and
// before Gemini ever sees them - deterministic and free, same rationale as
// TransferMatcher's fixed description markers, just for patterns the user
// chose instead of hardcoded statement templates.
object CategorizationRuleMatcher {
    // First matching rule wins per transaction, in `rules` order - keeps
    // the result deterministic without requiring the user's rules to be
    // mutually exclusive of each other.
    fun match(rules: List<CategorizationRule>, transactions: List<Transaction>): Map<String, TransactionCategory> {
        if (rules.isEmpty()) return emptyMap()
        val matches = mutableMapOf<String, TransactionCategory>()
        for (transaction in transactions) {
            val rule = rules.firstOrNull { it.matches(transaction.description) } ?: continue
            matches[transaction.id] = rule.category
        }
        return matches
    }

    private fun CategorizationRule.matches(description: String): Boolean = when (matchType) {
        MatchType.EXACT -> description.equals(pattern, ignoreCase = true)
        MatchType.SUBSTRING -> description.contains(pattern, ignoreCase = true)
    }
}
