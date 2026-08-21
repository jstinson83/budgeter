package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class ScenarioStoreTest {
    @Test
    fun testFakeScenarioRepositoryAddCreatesAScenarioOwnedByTheGivenOwner() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()

        val added = store.add("owner-a", "Aggressive growth", 0.10, 1.0, 0.0, null, null, null, null, null, false)

        assertEquals("Aggressive growth", added.name)
        assertTrue(store.all("owner-a").any { it.id == added.id })
        assertTrue(store.all("owner-b").isEmpty())
    }

    @Test
    fun testFakeScenarioRepositorySortsByNameCaseInsensitively() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()
        store.add("owner", "zebra scenario", 0.07, 1.0, 0.0, null, null, null, null, null, false)
        store.add("owner", "Alpha scenario", 0.07, 1.0, 0.0, null, null, null, null, null, false)

        val names = store.all("owner").map { it.name }

        assertEquals(listOf("Alpha scenario", "zebra scenario"), names)
    }

    @Test
    fun testFakeScenarioRepositoryUpdateChangesFieldsIncludingTheSalaryChangeEvent() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()
        val added = store.add("owner", "Baseline+", 0.07, 1.0, 0.0, null, null, null, null, null, false)

        val updated = store.update("owner", added.id, "Baseline+ raise", 0.08, 0.5, 100.0, LocalDate.of(2027, 1, 1), 500.0, null, null, null, false)

        assertNotNull(updated)
        assertEquals("Baseline+ raise", updated.name)
        assertEquals(0.08, updated.annualMarketGrowthRate, 0.001)
        assertEquals(0.5, updated.investedSavingsFraction, 0.001)
        assertEquals(100.0, updated.recreationalSpendAdjustment, 0.001)
        assertEquals(LocalDate.of(2027, 1, 1), updated.salaryChangeDate)
        assertEquals(500.0, updated.salaryChangeMonthlyDelta)
    }

    @Test
    fun testFakeScenarioRepositoryAddCreatesAScenarioWithAnRrspStrategy() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()

        val added = store.add("owner", "Max RRSP", 0.07, 1.0, 0.0, null, null, 1000.0, 0.43, 45000.0, true)

        assertEquals(1000.0, added.rrspMonthlyContribution)
        assertEquals(0.43, added.rrspMarginalTaxRate)
        assertEquals(45000.0, added.rrspRoomRemaining)
        assertTrue(added.rrspReinvestRefund)
    }

    @Test
    fun testFakeScenarioRepositoryUpdateChangesTheRrspStrategy() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()
        val added = store.add("owner", "Max RRSP", 0.07, 1.0, 0.0, null, null, 1000.0, 0.43, 45000.0, true)

        val updated = store.update("owner", added.id, "Max RRSP", 0.07, 1.0, 0.0, null, null, 1500.0, 0.4, 40000.0, false)

        assertNotNull(updated)
        assertEquals(1500.0, updated.rrspMonthlyContribution)
        assertEquals(0.4, updated.rrspMarginalTaxRate)
        assertEquals(40000.0, updated.rrspRoomRemaining)
        assertFalse(updated.rrspReinvestRefund)
    }

    @Test
    fun testFakeScenarioRepositoryUpdateOfUnknownScenarioReturnsNull() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()
        assertNull(store.update("owner", "not-real", "x", 0.07, 1.0, 0.0, null, null, null, null, null, false))
    }

    @Test
    fun testFakeScenarioRepositoryDeleteRemovesOnlyThatOwnersScenario() = kotlinx.coroutines.runBlocking {
        val store = FakeScenarioRepository()
        val added = store.add("owner", "Aggressive growth", 0.10, 1.0, 0.0, null, null, null, null, null, false)

        store.delete("other-owner", added.id) // no-op, wrong owner
        assertEquals(1, store.all("owner").size)

        store.delete("owner", added.id)
        assertTrue(store.all("owner").isEmpty())
    }
}
