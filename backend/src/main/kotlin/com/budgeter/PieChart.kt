package com.budgeter

import kotlin.math.roundToInt

// Spending-by-category pie/donut chart, shared by the dashboard ("/") and
// the monthly breakdown ("/analysis") - both already compute a per-category
// total for some period, this just turns that into chartable slices. No JS
// chart library in this app (see analysis.ftl/CLAUDE.md), so the chart
// itself is a handful of SVG <circle> elements using the stroke-dasharray/
// stroke-dashoffset technique: each slice is one full-circumference circle
// whose dash pattern reveals only its own arc, offset by the cumulative
// length of every slice before it. No trig needed - only arc lengths.

private const val PIE_CHART_MAX_SLICES = 5

// Keep in sync with styles.css's .pie-chart-slice stroke-width (both in the
// same 100x100 viewBox units) - radius 35 + half the 20-wide stroke leaves a
// few units of padding inside the viewBox rather than touching its edge.
private const val PIE_CHART_RADIUS = 35.0
private val PIE_CHART_CIRCUMFERENCE = 2 * Math.PI * PIE_CHART_RADIUS

// Fixed CSS class per rank (styles.css defines the actual colors, from the
// app's validated categorical palette) - assigned by spend rank on every
// render rather than a stable per-category color map. Categories are
// per-owner and user-creatable (see CategoryStore.kt), so there's no fixed
// universe of categories to assign stable colors to; the legend's text label
// is what carries identity, not the color alone.
private val PIE_SLICE_CSS_CLASSES = listOf("pie-slice-1", "pie-slice-2", "pie-slice-3", "pie-slice-4", "pie-slice-5")
private const val PIE_SLICE_OTHER_CSS_CLASS = "pie-slice-other"

// Labeled "Other categories", not just "Other" - CategoryStore.kt's built-in
// OTHER category already renders as the label "Other", and this bucket needs
// to stay visually distinct from a real category that happens to chart in
// the top slices too.
private const val OTHER_BUCKET_LABEL = "Other categories"

data class PieSlice(
    val label: String,
    val amount: Double,
    val percent: Double,
    val cssClass: String,
    val dashArray: String,
    val dashOffset: String
)

// `categoryTotals` is label -> net total for the period (the same
// sign convention as Transaction.amount: negative = money out). Only a net
// *outflow* category is chartable - "where the money went" - so a
// net-positive category (refunds outweighing spend, or an income category
// sharing this map) is dropped rather than shown as a negative-size slice.
// Beyond `maxSlices`, the smallest remaining categories are folded into one
// "Other categories" slice - keeps the chart and its legend readable
// regardless of how many categories a household has set up.
fun pieChartSlices(categoryTotals: Map<String, Double>, maxSlices: Int = PIE_CHART_MAX_SLICES): List<PieSlice> {
    val spend = categoryTotals
        .filterValues { it < 0 }
        .map { (label, total) -> label to -total }
        .sortedByDescending { it.second }
    if (spend.isEmpty()) return emptyList()

    val top = spend.take(maxSlices)
    val otherTotal = spend.drop(maxSlices).sumOf { it.second }
    val grandTotal = spend.sumOf { it.second }
    val entries = if (otherTotal > 0) top + (OTHER_BUCKET_LABEL to otherTotal) else top

    var cumulativeLength = 0.0
    return entries.mapIndexed { index, (label, amount) ->
        val length = amount / grandTotal * PIE_CHART_CIRCUMFERENCE
        val slice = PieSlice(
            label = label,
            amount = amount,
            percent = amount / grandTotal * 100,
            cssClass = if (index < top.size) PIE_SLICE_CSS_CLASSES[index % PIE_SLICE_CSS_CLASSES.size] else PIE_SLICE_OTHER_CSS_CLASS,
            // The undrawn remainder after this slice's own arc - together
            // with stroke-dashoffset below, this is what limits each circle
            // to drawing just its own segment of the ring.
            dashArray = "%.3f %.3f".format(length, PIE_CHART_CIRCUMFERENCE - length),
            // Negative offset shifts this slice's start forward (clockwise)
            // by the combined length of every slice already placed.
            dashOffset = "%.3f".format(-cumulativeLength)
        )
        cumulativeLength += length
        slice
    }
}

// Same flattening rationale as AnalysisPage.kt/DashboardPage.kt: FreeMarker
// needs display-ready values, not a data class.
fun pieChartModel(categoryTotals: Map<String, Double>, maxSlices: Int = PIE_CHART_MAX_SLICES): List<Map<String, Any?>> =
    pieChartSlices(categoryTotals, maxSlices).map {
        mapOf(
            "label" to it.label,
            "amount" to "%.2f".format(it.amount),
            "percent" to it.percent.roundToInt(),
            "cssClass" to it.cssClass,
            "dashArray" to it.dashArray,
            "dashOffset" to it.dashOffset
        )
    }
