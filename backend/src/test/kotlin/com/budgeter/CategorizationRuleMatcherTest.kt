package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class CategorizationRuleMatcherTest {
    private fun transaction(id: String, description: String): Transaction =
        Transaction(id, "owner", AccountType.BANK, LocalDate.of(2026, 7, 1), description, -10.00)

    private fun rule(pattern: String, matchType: MatchType, category: TransactionCategory = TransactionCategory.SUBSCRIPTIONS): CategorizationRule =
        CategorizationRule("rule-1", "owner", pattern, matchType, category)

    @Test
    fun testExactMatchRequiresTheWholeDescriptionToMatch() {
        val rules = listOf(rule("NETFLIX.COM", MatchType.EXACT))
        val exact = transaction("t-1", "NETFLIX.COM")
        val withExtra = transaction("t-2", "NETFLIX.COM 1234")

        val result = CategorizationRuleMatcher.match(rules, listOf(exact, withExtra))

        assertEquals(mapOf("t-1" to TransactionCategory.SUBSCRIPTIONS), result)
    }

    @Test
    fun testSubstringMatchAppliesToAnyDescriptionContainingThePattern() {
        val rules = listOf(rule("NETFLIX", MatchType.SUBSTRING))
        val withExtra = transaction("t-1", "NETFLIX.COM 1234")

        val result = CategorizationRuleMatcher.match(rules, listOf(withExtra))

        assertEquals(mapOf("t-1" to TransactionCategory.SUBSCRIPTIONS), result)
    }

    @Test
    fun testMatchIsCaseInsensitive() {
        val rules = listOf(rule("netflix", MatchType.SUBSTRING))
        val transaction = transaction("t-1", "NETFLIX.COM")

        assertEquals(mapOf("t-1" to TransactionCategory.SUBSCRIPTIONS), CategorizationRuleMatcher.match(rules, listOf(transaction)))
    }

    @Test
    fun testNoMatchLeavesTheTransactionOutOfTheResult() {
        val rules = listOf(rule("NETFLIX", MatchType.SUBSTRING))
        val transaction = transaction("t-1", "AMAZON.CA")

        assertEquals(emptyMap(), CategorizationRuleMatcher.match(rules, listOf(transaction)))
    }

    @Test
    fun testFirstMatchingRuleWinsWhenMoreThanOneRuleMatches() {
        val rules = listOf(
            rule("NETFLIX", MatchType.SUBSTRING, TransactionCategory.SUBSCRIPTIONS),
            rule("NETFLIX.COM", MatchType.EXACT, TransactionCategory.ENTERTAINMENT)
        )
        val transaction = transaction("t-1", "NETFLIX.COM")

        assertEquals(mapOf("t-1" to TransactionCategory.SUBSCRIPTIONS), CategorizationRuleMatcher.match(rules, listOf(transaction)))
    }

    @Test
    fun testNoRulesMatchesNothing() {
        val transaction = transaction("t-1", "NETFLIX.COM")

        assertEquals(emptyMap(), CategorizationRuleMatcher.match(emptyList(), listOf(transaction)))
    }
}
