package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class ProjectionChartTest {
    @Test
    fun testProjectionChartModelProducesOnePointPairPerProjectionPoint() {
        val points = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 100000.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 101000.0),
            ProjectionPoint(LocalDate.of(2026, 10, 1), 102000.0)
        )
        val chart = projectionChartModel(points, targetAmount = 105000.0)

        assertEquals(3, chart.points.trim().split(" ").size)
    }

    @Test
    fun testProjectionChartModelPlacesTheHighestValueNearestTheTopOfTheViewBox() {
        val points = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 0.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 100.0)
        )
        val chart = projectionChartModel(points, targetAmount = 100.0)

        val yCoords = chart.points.trim().split(" ").map { it.split(",")[1].toDouble() }
        // Higher net worth -> smaller y (SVG y grows downward), so the
        // second point (100.0) should sit above (smaller y than) the first (0.0).
        assertTrue(yCoords[1] < yCoords[0])
    }

    @Test
    fun testProjectionChartModelHandlesAFlatSeriesWithoutDividingByZero() {
        val points = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 5000.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 5000.0)
        )
        val chart = projectionChartModel(points, targetAmount = 5000.0)

        assertFalse(chart.points.contains("NaN"))
        assertFalse(chart.goalY.contains("NaN"))
    }

    @Test
    fun testProjectionChartModelIncludesTheTargetInItsRangeEvenWhenFarAboveThePoints() {
        val points = listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 1000.0))
        val chart = projectionChartModel(points, targetAmount = 1000000.0)

        assertEquals("1000000.00", chart.maxLabel)
    }

    @Test
    fun testProjectionChartModelRejectsAnEmptyPointsList() {
        assertFailsWith<IllegalArgumentException> {
            projectionChartModel(emptyList(), targetAmount = 1000.0)
        }
    }
}
