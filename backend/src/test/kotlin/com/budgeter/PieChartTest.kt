package com.budgeter

import kotlin.test.*

class PieChartTest {
    @Test
    fun testEmptyWhenNoNetOutflowCategories() {
        val slices = pieChartSlices(mapOf("Income" to 1000.0, "Refunded Category" to 5.0))
        assertEquals(emptyList(), slices)
    }

    @Test
    fun testSlicesAreSortedBiggestSpendFirstWithPercentAndFullCircumference() {
        val slices = pieChartSlices(
            mapOf("Groceries" to -300.0, "Dining Out" to -100.0, "Income" to 2000.0),
            maxSlices = 5
        )

        assertEquals(listOf("Groceries", "Dining Out"), slices.map { it.label })
        assertEquals(300.0, slices[0].amount)
        assertEquals(100.0, slices[1].amount)
        assertEquals(75.0, slices[0].percent)
        assertEquals(25.0, slices[1].percent)
        assertEquals("pie-slice-1", slices[0].cssClass)
        assertEquals("pie-slice-2", slices[1].cssClass)
        // No overflow beyond maxSlices - no "Other categories" bucket.
        assertTrue(slices.none { it.label == "Other categories" })
    }

    @Test
    fun testOverflowCategoriesFoldIntoOtherBucketRankedLast() {
        val totals = mapOf(
            "A" to -50.0, "B" to -40.0, "C" to -30.0, "D" to -20.0, "E" to -15.0,
            "F" to -10.0, "G" to -5.0
        )

        val slices = pieChartSlices(totals, maxSlices = 5)

        assertEquals(listOf("A", "B", "C", "D", "E", "Other categories"), slices.map { it.label })
        assertEquals("pie-slice-other", slices.last().cssClass)
        // F + G folded together.
        assertEquals(15.0, slices.last().amount)
    }

    @Test
    fun testOtherBucketDoesNotCollideWithARealOtherCategory() {
        val totals = mapOf(
            "A" to -50.0, "B" to -40.0, "C" to -30.0, "D" to -20.0, "Other" to -15.0,
            "F" to -10.0
        )

        val slices = pieChartSlices(totals, maxSlices = 5)

        // The real "Other" category (built-in OTHER) ranks in the top 5 and
        // keeps its own label distinct from the synthetic overflow bucket.
        assertEquals(listOf("A", "B", "C", "D", "Other", "Other categories"), slices.map { it.label })
    }

    @Test
    fun testDashArrayAndOffsetTraceTheFullCircumferenceWithNoGaps() {
        val slices = pieChartSlices(mapOf("A" to -50.0, "B" to -50.0))

        val circumference = 2 * Math.PI * 35.0
        assertEquals(0.0, slices[0].dashOffset.toDouble(), 0.001)
        val firstLength = slices[0].dashArray.split(" ")[0].toDouble()
        assertEquals(circumference / 2, firstLength, 0.01)
        assertEquals(-(circumference / 2), slices[1].dashOffset.toDouble(), 0.01)
    }

    @Test
    fun testPieChartModelFlattensToDisplayReadyStrings() {
        val model = pieChartModel(mapOf("Groceries" to -33.333))

        assertEquals("Groceries", model.single()["label"])
        assertEquals("33.33", model.single()["amount"])
        assertEquals(100, model.single()["percent"])
    }
}
