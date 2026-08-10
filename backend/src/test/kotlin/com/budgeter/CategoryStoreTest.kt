package com.budgeter

import kotlin.test.*

class CategoryStoreTest {
    @Test
    fun testSlugifyUppercasesAndReplacesNonAlphanumericsWithUnderscores() {
        assertEquals("HOBBY_SUPPLIES", slugify("Hobby Supplies"))
        assertEquals("KIDS_ACTIVITIES", slugify("  Kids & Activities! "))
    }

    @Test
    fun testSlugifyFallsBackToCategoryWhenLabelHasNoAlphanumerics() {
        assertEquals("CATEGORY", slugify("!!!"))
    }

    @Test
    fun testUniqueSlugReturnsTheBaseSlugWhenNotTaken() {
        assertEquals("CLOTHING", uniqueSlug("Clothing", existingIds = emptySet()))
    }

    @Test
    fun testUniqueSlugAppendsANumericSuffixOnCollision() {
        assertEquals("CLOTHING_2", uniqueSlug("Clothing", existingIds = setOf("CLOTHING")))
        assertEquals("CLOTHING_3", uniqueSlug("Clothing", existingIds = setOf("CLOTHING", "CLOTHING_2")))
    }

    @Test
    fun testFakeCategoryRepositorySeedsBuiltInsOnFirstCallOnlyForThatOwner() = kotlinx.coroutines.runBlocking {
        val store = FakeCategoryRepository()

        val ownerACategories = store.all("owner-a")
        assertEquals(BUILT_IN_CATEGORIES.size, ownerACategories.size)
        assertTrue(ownerACategories.all { it.active })
        assertTrue(ownerACategories.any { it.id == "GROCERIES" && it.label == "Groceries" })

        // A different owner gets their own independent seeded set, not
        // owner-a's rows.
        val ownerBCategories = store.all("owner-b")
        assertEquals(BUILT_IN_CATEGORIES.size, ownerBCategories.size)
        assertNotEquals(ownerACategories.map { it.id }.toSet(), emptySet())
    }

    @Test
    fun testFakeCategoryRepositoryAddCreatesACustomCategoryWithAUniqueSlugId() = kotlinx.coroutines.runBlocking {
        val store = FakeCategoryRepository()
        store.all("owner") // seed built-ins first

        val added = store.add("owner", "Hobby Supplies")

        assertEquals("HOBBY_SUPPLIES", added.id)
        assertTrue(added.active)
        assertTrue(store.all("owner").any { it.id == "HOBBY_SUPPLIES" })
    }

    @Test
    fun testFakeCategoryRepositorySetActiveTogglesWithoutAffectingOtherCategories() = kotlinx.coroutines.runBlocking {
        val store = FakeCategoryRepository()
        store.all("owner")

        store.setActive("owner", "GROCERIES", active = false)

        val categories = store.all("owner")
        assertFalse(categories.first { it.id == "GROCERIES" }.active)
        assertTrue(categories.first { it.id == "DINING_OUT" }.active)
    }
}
