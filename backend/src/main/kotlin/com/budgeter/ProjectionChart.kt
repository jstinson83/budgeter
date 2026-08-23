package com.budgeter

// Net worth projection line chart - same "no JS chart library, plain SVG"
// approach PieChart.kt uses (see its own doc comment for why). A
// projection's points are already monotonic in date; this just maps
// (date, netWorth) into a fixed viewBox coordinate space so planning.ftl
// can drop the result straight into <polyline> elements, with no chart
// math in the template itself. As of slice 4, a chart can hold more than
// one line (the always-shown baseline plus zero or more Scenario lines,
// see ProjectionEngine.kt's projectScenario) sharing one coordinate scale
// so they're actually comparable at a glance.

private const val CHART_WIDTH = 300.0
private const val CHART_HEIGHT = 120.0

// Keeps a polyline's own stroke and the goal's dashed line from getting
// clipped at the viewBox edge.
private const val CHART_PADDING = 6.0

// Cycles through the same categorical palette PieChart.kt uses
// (styles.css's --pie-1.."--pie-5) so a scenario line and a pie slice
// never fight over what a given hue means - assigned by position (first
// scenario added, second, ...) rather than a stable per-scenario color
// map, same reasoning PieChart.kt gives for category colors. Not private -
// NetWorthPage.kt's scenarioRowModel() reuses this same position -> class
// mapping to give planning.ftl's scenario visibility chips a CSS class to
// target, so a chip and the chart lines it toggles never drift out of sync.
val SCENARIO_LINE_CSS_CLASSES = listOf(
    "projection-chart-line-scenario-1",
    "projection-chart-line-scenario-2",
    "projection-chart-line-scenario-3",
    "projection-chart-line-scenario-4",
    "projection-chart-line-scenario-5"
)

data class ChartLine(
    val label: String,
    val cssClass: String,
    // SVG <polyline points="..."> attribute value - "x,y x,y ..." pairs.
    val points: String
)

data class ProjectionChartModel(
    val lines: List<ChartLine>,
    // y-coordinate of the flat, horizontal goal-target line.
    val goalY: String,
    val minLabel: String,
    val maxLabel: String
)

// `baseline` is always rendered (the "what happens if nothing changes"
// line every goal already showed before slice 4); `scenarios` are
// name -> points pairs for whichever Scenario rows the household has
// defined, all expected to share baseline's date range (see
// ProjectionEngine.kt's projectGoal/projectScenario, both driven by the
// same goal). `targetAmount` is folded into the same min/max range as
// every line's own points (not just the points alone) so a goal far
// above or below the projected trajectories still renders its dashed
// line on-chart instead of clipping outside the viewBox.
fun projectionChartModel(baseline: List<ProjectionPoint>, scenarios: List<Pair<String, List<ProjectionPoint>>>, targetAmount: Double): ProjectionChartModel {
    require(baseline.isNotEmpty()) { "projectionChartModel requires at least one baseline point" }
    val allSeries = listOf("Baseline" to baseline) + scenarios
    val allValues = allSeries.flatMap { (_, points) -> points.map { it.netWorth } } + targetAmount
    val minValue = allValues.min()
    val maxValue = allValues.max()
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

    fun yFor(value: Double): Double =
        CHART_HEIGHT - CHART_PADDING - ((value - minValue) / range) * (CHART_HEIGHT - 2 * CHART_PADDING)

    fun svgPointsFor(points: List<ProjectionPoint>): String {
        val xStep = if (points.size > 1) (CHART_WIDTH - 2 * CHART_PADDING) / (points.size - 1) else 0.0
        return points.mapIndexed { index, point ->
            "%.2f,%.2f".format(CHART_PADDING + xStep * index, yFor(point.netWorth))
        }.joinToString(" ")
    }

    val lines = allSeries.mapIndexed { index, (label, points) ->
        val cssClass = if (index == 0) "projection-chart-line" else SCENARIO_LINE_CSS_CLASSES[(index - 1) % SCENARIO_LINE_CSS_CLASSES.size]
        ChartLine(label, cssClass, svgPointsFor(points))
    }

    return ProjectionChartModel(
        lines = lines,
        goalY = "%.2f".format(yFor(targetAmount)),
        minLabel = "%.2f".format(minValue),
        maxLabel = "%.2f".format(maxValue)
    )
}

// Pixel width given to each monthly point on the "Wealth over time" chart
// (NetWorthPage.kt's wealthChartRowModel) - unlike projectionChartModel
// above, this chart isn't squeezed into one fixed viewBox width via
// preserveAspectRatio - it's given a real, scrollable pixel width (one
// SVG unit == one CSS pixel, so no scaling/stretching happens at all) so
// a multi-year monthly projection stays readable instead of cramming
// hundreds of points into ~300px. planning.ftl wraps the chart in a
// horizontally-scrolling container.
private const val WEALTH_CHART_POINT_SPACING_PX = 6.0

// A short horizon (few scenarios, no growth) shouldn't render narrower
// than the card itself - this is the same floor projectionChartModel's
// fixed CHART_WIDTH effectively is.
private const val WEALTH_CHART_MIN_WIDTH_PX = 300.0

// One horizontal gridline plus its y-axis label - WEALTH_CHART_GRID_STEPS
// of these, evenly dividing the chart's value range from maxValue (first,
// top) down to minValue (last, bottom). `y` is an SVG coordinate (goes
// inside the chart's own scaled viewBox, same as ChartLine.points);
// `label` is plain formatted text rendered outside the SVG (see the
// x-axis tick comment below for why labels live outside it).
data class WealthChartGridLine(val y: String, val label: String)

// One x-axis year tick. `leftPercent` is a CSS left-offset percentage of
// the chart's full width, not an SVG coordinate - planning.ftl renders
// these as an absolutely-positioned HTML row layered under the <svg>
// rather than as SVG <text>. That split matters once the chart is
// stretched to fill its card (see wealthChartModel's width doc below):
// the <svg> gets `preserveAspectRatio="none"` so its polylines/gridlines
// can stretch horizontally to fill whatever width the card gives them,
// but SVG <text> glyphs would stretch right along with it and come out
// visibly squashed or smeared. Plain HTML text never does, and a
// percentage offset tracks the same stretched/scrolled width identically
// whether the chart is showing its natural per-point spacing or is
// stretched to fill a wider card.
data class WealthChartXAxisTick(val leftPercent: String, val label: String)

data class WealthChartModel(
    val lines: List<ChartLine>,
    val gridLines: List<WealthChartGridLine>,
    val xAxisTicks: List<WealthChartXAxisTick>,
    // The chart's natural pixel width at one point per
    // WEALTH_CHART_POINT_SPACING_PX - planning.ftl uses this as a
    // `min-width` (not a fixed width) so a short horizon stretches to
    // fill the card instead of leaving dead space next to a narrow chart,
    // while a long horizon still gets its full natural width and scrolls
    // rather than cramming hundreds of points into the card.
    val widthPx: Int
)

// Number of gridlines dividing the wealth chart's value range - 5 labels
// (WEALTH_CHART_GRID_STEPS + 1) from maxValue down to minValue, replacing
// the old bare min/max-only labels with a real, if unrounded, y-axis.
private const val WEALTH_CHART_GRID_STEPS = 4

// Same "baseline plus zero or more named scenario lines, one shared
// coordinate scale" shape as projectionChartModel, deliberately kept as
// its own function rather than unified with it: this chart has no goal
// target line (there's no goal driving it) and its width scales with
// point count instead of staying fixed, so the two diverge enough that
// sharing one function would need a branchy "is this the goal chart or
// the wealth chart" parameter rather than two small, separately
// readable ones.
fun wealthChartModel(baseline: List<ProjectionPoint>, scenarios: List<Pair<String, List<ProjectionPoint>>>): WealthChartModel {
    require(baseline.isNotEmpty()) { "wealthChartModel requires at least one baseline point" }
    val allSeries = listOf("Baseline" to baseline) + scenarios
    val allValues = allSeries.flatMap { (_, points) -> points.map { it.netWorth } }
    val minValue = allValues.min()
    val maxValue = allValues.max()
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val width = maxOf(WEALTH_CHART_MIN_WIDTH_PX, 2 * CHART_PADDING + WEALTH_CHART_POINT_SPACING_PX * (baseline.size - 1))
    val xStep = if (baseline.size > 1) (width - 2 * CHART_PADDING) / (baseline.size - 1) else 0.0

    fun yFor(value: Double): Double =
        CHART_HEIGHT - CHART_PADDING - ((value - minValue) / range) * (CHART_HEIGHT - 2 * CHART_PADDING)

    fun svgPointsFor(points: List<ProjectionPoint>): String {
        return points.mapIndexed { index, point ->
            "%.2f,%.2f".format(CHART_PADDING + xStep * index, yFor(point.netWorth))
        }.joinToString(" ")
    }

    val lines = allSeries.mapIndexed { index, (label, points) ->
        val cssClass = if (index == 0) "projection-chart-line" else SCENARIO_LINE_CSS_CLASSES[(index - 1) % SCENARIO_LINE_CSS_CLASSES.size]
        ChartLine(label, cssClass, svgPointsFor(points))
    }

    val gridLines = (0..WEALTH_CHART_GRID_STEPS).map { step ->
        val value = maxValue - (step.toDouble() / WEALTH_CHART_GRID_STEPS) * range
        WealthChartGridLine(y = "%.2f".format(yFor(value)), label = "%.2f".format(value))
    }

    // One tick per calendar year the baseline crosses (every 12 monthly
    // points, see wealthChartRowModel), labeled with that point's actual
    // year rather than an offset from today - correct regardless of
    // whether today's month is January.
    val xAxisTicks = baseline.indices.filter { it % 12 == 0 }.map { index ->
        val xPixel = CHART_PADDING + xStep * index
        WealthChartXAxisTick(leftPercent = "%.2f".format(xPixel / width * 100.0), label = baseline[index].date.year.toString())
    }

    return WealthChartModel(
        lines = lines,
        gridLines = gridLines,
        xAxisTicks = xAxisTicks,
        widthPx = width.toInt()
    )
}
