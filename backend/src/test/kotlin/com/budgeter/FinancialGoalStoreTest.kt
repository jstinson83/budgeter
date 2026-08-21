package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class FinancialGoalStoreTest {
    @Test
    fun testResolvedTargetAmountOfANetWorthTargetGoalIsItsTargetAmount() {
        val goal = FinancialGoal(
            "1", "owner", "House fund", FinancialGoalType.NET_WORTH_TARGET,
            LocalDate.of(2031, 8, 21), targetAmount = 500000.0
        )
        assertEquals(500000.0, goal.resolvedTargetAmount(), 0.001)
    }

    @Test
    fun testResolvedTargetAmountOfARetirementGoalDerivesFromAnnualSpendAndWithdrawalRate() {
        val goal = FinancialGoal(
            "1", "owner", "Retire", FinancialGoalType.RETIREMENT,
            LocalDate.of(2050, 1, 1), annualSpend = 80000.0, withdrawalRate = 0.04
        )
        assertEquals(2000000.0, goal.resolvedTargetAmount(), 0.001)
    }

    @Test
    fun testResolvedTargetAmountOfARetirementGoalFallsBackToTheDefaultWithdrawalRateWhenUnset() {
        val goal = FinancialGoal(
            "1", "owner", "Retire", FinancialGoalType.RETIREMENT,
            LocalDate.of(2050, 1, 1), annualSpend = 40000.0, withdrawalRate = null
        )
        assertEquals(1000000.0, goal.resolvedTargetAmount(), 0.001) // 40000 / 0.04
    }

    @Test
    fun testFakeFinancialGoalRepositoryAddCreatesAGoalOwnedByTheGivenOwner() = kotlinx.coroutines.runBlocking {
        val store = FakeFinancialGoalRepository()

        val added = store.add("owner-a", "House fund", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2031, 8, 21), 500000.0, null, null)

        assertEquals("House fund", added.name)
        assertTrue(store.all("owner-a").any { it.id == added.id })
        assertTrue(store.all("owner-b").isEmpty())
    }

    @Test
    fun testFakeFinancialGoalRepositorySortsBySoonestTargetDateFirst() = kotlinx.coroutines.runBlocking {
        val store = FakeFinancialGoalRepository()
        store.add("owner", "Later goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2035, 1, 1), 100000.0, null, null)
        store.add("owner", "Sooner goal", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2028, 1, 1), 50000.0, null, null)

        val names = store.all("owner").map { it.name }

        assertEquals(listOf("Sooner goal", "Later goal"), names)
    }

    @Test
    fun testFakeFinancialGoalRepositoryUpdateChangesFields() = kotlinx.coroutines.runBlocking {
        val store = FakeFinancialGoalRepository()
        val added = store.add("owner", "House fund", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2031, 8, 21), 500000.0, null, null)

        val updated = store.update("owner", added.id, "Bigger house fund", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2032, 1, 1), 600000.0, null, null)

        assertNotNull(updated)
        assertEquals("Bigger house fund", updated.name)
        assertEquals(600000.0, updated.targetAmount)
    }

    @Test
    fun testFakeFinancialGoalRepositoryUpdateOfUnknownGoalReturnsNull() = kotlinx.coroutines.runBlocking {
        val store = FakeFinancialGoalRepository()
        assertNull(store.update("owner", "not-real", "x", FinancialGoalType.NET_WORTH_TARGET, LocalDate.now(), 1.0, null, null))
    }

    @Test
    fun testFakeFinancialGoalRepositoryDeleteRemovesOnlyThatOwnersGoal() = kotlinx.coroutines.runBlocking {
        val store = FakeFinancialGoalRepository()
        val added = store.add("owner", "House fund", FinancialGoalType.NET_WORTH_TARGET, LocalDate.of(2031, 8, 21), 500000.0, null, null)

        store.delete("other-owner", added.id) // no-op, wrong owner
        assertEquals(1, store.all("owner").size)

        store.delete("owner", added.id)
        assertTrue(store.all("owner").isEmpty())
    }
}
