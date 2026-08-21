package com.budgeter

// Net worth projection line chart - same "no JS chart library, plain SVG"
// approach PieChart.kt uses (see its own doc comment for why). A
// GoalProjection's points are already monotonic in date; this just maps
// (date, netWorth) into a fixed viewBox coordinate space so planning.ftl
// can drop the result straight into a <polyline>, with no chart math in
// the template itself.

private const val CHART_WIDTH = 300.0
private const val CHART_HEIGHT = 120.0

// Keeps the polyline's own stroke and the goal's dashed line from getting
// clipped at the viewBox edge.
private const val CHART_PADDING = 6.0

data class ProjectionChartModel(
    // SVG <polyline points="..."> attribute value - "x,y x,y ..." pairs.
    val points: String,
    // y-coordinate of the flat, horizontal goal-target line.
    val goalY: String,
    val minLabel: String,
    val maxLabel: String
)

// `targetAmount` is folded into the same min/max range as the projected
// points (not just the points alone) so a goal far above or below the
// projected trajectory still renders its dashed line on-chart instead of
// clipping outside the viewBox.
fun projectionChartModel(points: List<ProjectionPoint>, targetAmount: Double): ProjectionChartModel {
    require(points.isNotEmpty()) { "projectionChartModel requires at least one point" }
    val values = points.map { it.netWorth } + targetAmount
    val minValue = values.min()
    val maxValue = values.max()
    val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0

    fun yFor(value: Double): Double =
        CHART_HEIGHT - CHART_PADDING - ((value - minValue) / range) * (CHART_HEIGHT - 2 * CHART_PADDING)

    val xStep = if (points.size > 1) (CHART_WIDTH - 2 * CHART_PADDING) / (points.size - 1) else 0.0
    val svgPoints = points.mapIndexed { index, point ->
        "%.2f,%.2f".format(CHART_PADDING + xStep * index, yFor(point.netWorth))
    }.joinToString(" ")

    return ProjectionChartModel(
        points = svgPoints,
        goalY = "%.2f".format(yFor(targetAmount)),
        minLabel = "%.2f".format(minValue),
        maxLabel = "%.2f".format(maxValue)
    )
}
