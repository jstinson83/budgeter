package com.budgeter

import kotlin.test.*

class NetWorthStoreTest {
    @Test
    fun testNetWorthTotalSumsAssetsAndSubtractsLiabilities() {
        val entries = listOf(
            NetWorthEntry("1", "owner", "Chequing", NetWorthEntryType.BANK, 5000.0),
            NetWorthEntry("2", "owner", "Brokerage", NetWorthEntryType.INVESTMENT, 20000.0),
            NetWorthEntry("3", "owner", "Mortgage", NetWorthEntryType.MORTGAGE, 18000.0)
        )
        assertEquals(7000.0, netWorthTotal(entries), 0.001)
    }

    @Test
    fun testNetWorthTotalOfNoEntriesIsZero() {
        assertEquals(0.0, netWorthTotal(emptyList()), 0.001)
    }

    @Test
    fun testFakeNetWorthEntryRepositoryAddCreatesAnEntryOwnedByTheGivenOwner() = kotlinx.coroutines.runBlocking {
        val store = FakeNetWorthEntryRepository()

        val added = store.add("owner-a", "Chequing", NetWorthEntryType.BANK, 5000.0)

        assertEquals("Chequing", added.label)
        assertEquals(NetWorthEntryType.BANK, added.type)
        assertTrue(store.all("owner-a").any { it.id == added.id })
        // A different owner's list stays empty - no cross-owner leakage.
        assertTrue(store.all("owner-b").isEmpty())
    }

    @Test
    fun testFakeNetWorthEntryRepositorySortsAssetsBeforeLiabilitiesThenByLabel() = kotlinx.coroutines.runBlocking {
        val store = FakeNetWorthEntryRepository()
        store.add("owner", "Mortgage", NetWorthEntryType.MORTGAGE, 18000.0)
        store.add("owner", "Zebra Fund", NetWorthEntryType.INVESTMENT, 1000.0)
        store.add("owner", "Ally Chequing", NetWorthEntryType.BANK, 500.0)

        val labels = store.all("owner").map { it.label }

        assertEquals(listOf("Ally Chequing", "Zebra Fund", "Mortgage"), labels)
    }

    @Test
    fun testFakeNetWorthEntryRepositoryUpdateChangesLabelTypeAndValue() = kotlinx.coroutines.runBlocking {
        val store = FakeNetWorthEntryRepository()
        val added = store.add("owner", "Chequing", NetWorthEntryType.BANK, 5000.0)

        val updated = store.update("owner", added.id, "Savings", NetWorthEntryType.BANK, 6000.0)

        assertNotNull(updated)
        assertEquals("Savings", updated.label)
        assertEquals(6000.0, updated.value, 0.001)
    }

    @Test
    fun testFakeNetWorthEntryRepositoryUpdateOfUnknownEntryReturnsNull() = kotlinx.coroutines.runBlocking {
        val store = FakeNetWorthEntryRepository()
        assertNull(store.update("owner", "not-real", "x", NetWorthEntryType.BANK, 1.0))
    }

    @Test
    fun testFakeNetWorthEntryRepositoryDeleteRemovesOnlyThatOwnersEntry() = kotlinx.coroutines.runBlocking {
        val store = FakeNetWorthEntryRepository()
        val added = store.add("owner", "Chequing", NetWorthEntryType.BANK, 5000.0)

        store.delete("other-owner", added.id) // no-op, wrong owner
        assertEquals(1, store.all("owner").size)

        store.delete("owner", added.id)
        assertTrue(store.all("owner").isEmpty())
    }
}
