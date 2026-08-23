package com.budgeter

import java.time.LocalDate
import kotlin.test.*

class ProjectionChartTest {
    @Test
    fun testProjectionChartModelProducesOneLinePerSeriesWithBaselineFirst() {
        val baseline = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 100000.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 101000.0)
        )
        val scenario = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 100000.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 103000.0)
        )
        val chart = projectionChartModel(baseline, listOf("Aggressive" to scenario), targetAmount = 105000.0)

        assertEquals(2, chart.lines.size)
        assertEquals("Baseline", chart.lines[0].label)
        assertEquals("projection-chart-line", chart.lines[0].cssClass)
        assertEquals("Aggressive", chart.lines[1].label)
        assertEquals("projection-chart-line-scenario-1", chart.lines[1].cssClass)
    }

    @Test
    fun testProjectionChartModelWithNoScenariosProducesJustTheBaselineLine() {
        val baseline = listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 100000.0))
        val chart = projectionChartModel(baseline, emptyList(), targetAmount = 105000.0)

        assertEquals(1, chart.lines.size)
        assertEquals("Baseline", chart.lines[0].label)
    }

    @Test
    fun testProjectionChartModelPlacesTheHighestValueNearestTheTopOfTheViewBox() {
        val baseline = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 0.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 100.0)
        )
        val chart = projectionChartModel(baseline, emptyList(), targetAmount = 100.0)

        val yCoords = chart.lines[0].points.trim().split(" ").map { it.split(",")[1].toDouble() }
        // Higher net worth -> smaller y (SVG y grows downward), so the
        // second point (100.0) should sit above (smaller y than) the first (0.0).
        assertTrue(yCoords[1] < yCoords[0])
    }

    @Test
    fun testProjectionChartModelHandlesAFlatSeriesWithoutDividingByZero() {
        val baseline = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 5000.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 5000.0)
        )
        val chart = projectionChartModel(baseline, emptyList(), targetAmount = 5000.0)

        assertFalse(chart.lines[0].points.contains("NaN"))
        assertFalse(chart.goalY.contains("NaN"))
    }

    @Test
    fun testProjectionChartModelIncludesTheTargetAndEveryScenarioInItsRange() {
        val baseline = listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 1000.0))
        val scenario = listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 2000000.0))
        val chart = projectionChartModel(baseline, listOf("Big scenario" to scenario), targetAmount = 1000000.0)

        assertEquals("2000000.00", chart.maxLabel)
    }

    @Test
    fun testProjectionChartModelRejectsAnEmptyBaselineList() {
        assertFailsWith<IllegalArgumentException> {
            projectionChartModel(emptyList(), emptyList(), targetAmount = 1000.0)
        }
    }

    @Test
    fun testProjectionChartModelCyclesScenarioCssClassesPastFive() {
        val baseline = listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 0.0))
        val scenarios = (1..6).map { "Scenario $it" to listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), it.toDouble())) }
        val chart = projectionChartModel(baseline, scenarios, targetAmount = 0.0)

        assertEquals("projection-chart-line-scenario-1", chart.lines[1].cssClass)
        assertEquals("projection-chart-line-scenario-5", chart.lines[5].cssClass)
        assertEquals("projection-chart-line-scenario-1", chart.lines[6].cssClass) // wraps around
    }

    @Test
    fun testWealthChartModelHasNoGoalLineAndBaselineFirst() {
        val baseline = listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 100000.0))
        val chart = wealthChartModel(baseline, listOf("Aggressive" to baseline))

        assertEquals(2, chart.lines.size)
        assertEquals("Baseline", chart.lines[0].label)
        assertEquals("projection-chart-line", chart.lines[0].cssClass)
        assertEquals("projection-chart-line-scenario-1", chart.lines[1].cssClass)
    }

    @Test
    fun testWealthChartModelWidensWithMorePointsRatherThanStayingFixed() {
        val fewPoints = (0..2).map { ProjectionPoint(LocalDate.of(2026, 8, 1).plusMonths(it.toLong()), 0.0) }
        val manyPoints = (0..120).map { ProjectionPoint(LocalDate.of(2026, 8, 1).plusMonths(it.toLong()), 0.0) }

        val narrow = wealthChartModel(fewPoints, emptyList())
        val wide = wealthChartModel(manyPoints, emptyList())

        assertTrue(wide.widthPx > narrow.widthPx)
    }

    @Test
    fun testWealthChartModelNeverGoesNarrowerThanItsMinimumWidth() {
        val chart = wealthChartModel(listOf(ProjectionPoint(LocalDate.of(2026, 8, 1), 0.0)), emptyList())

        assertTrue(chart.widthPx >= 300)
    }

    @Test
    fun testWealthChartModelRejectsAnEmptyBaselineList() {
        assertFailsWith<IllegalArgumentException> {
            wealthChartModel(emptyList(), emptyList())
        }
    }

    @Test
    fun testWealthChartModelGridLinesRunFromMaxAtTopToMinAtBottom() {
        val baseline = listOf(
            ProjectionPoint(LocalDate.of(2026, 8, 1), 100000.0),
            ProjectionPoint(LocalDate.of(2026, 9, 1), 200000.0)
        )
        val chart = wealthChartModel(baseline, emptyList())

        assertEquals("200000.00", chart.gridLines.first().label)
        assertEquals("100000.00", chart.gridLines.last().label)
        // Higher value -> smaller y (SVG y grows downward), so the top
        // gridline's y should be smaller than the bottom gridline's.
        assertTrue(chart.gridLines.first().y.toDouble() < chart.gridLines.last().y.toDouble())
    }

    @Test
    fun testWealthChartModelXAxisTicksLandOnEveryTwelfthPointWithItsOwnYear() {
        // Starts mid-year (August) on purpose - ticks should follow each
        // point's actual date, not assume the horizon starts in January.
        val baseline = (0..24).map { ProjectionPoint(LocalDate.of(2026, 8, 1).plusMonths(it.toLong()), 0.0) }
        val chart = wealthChartModel(baseline, emptyList())

        assertEquals(listOf("2026", "2027", "2028"), chart.xAxisTicks.map { it.label })
        // Not exactly 0%/100% - CHART_PADDING keeps the first/last points
        // (and their ticks) inset from the very edge of the chart, same as
        // every other point.
        assertEquals("2.00", chart.xAxisTicks.first().leftPercent)
        assertEquals("98.00", chart.xAxisTicks.last().leftPercent)
    }
}
