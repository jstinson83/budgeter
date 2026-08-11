<#-- Spending-by-category pie/donut chart, shared by dashboard.ftl and
     analysis.ftl - expects `pieSlices` in context (PieChart.kt's
     pieChartModel). Pure SVG + CSS, no JS, matching the rest of this app. -->
<#if (pieSlices?size > 0)>
<div class="pie-chart-section">
  <svg viewBox="0 0 100 100" class="pie-chart" role="img" aria-label="Spending by category">
    <#list pieSlices as slice>
    <circle class="pie-chart-slice ${slice.cssClass}" cx="50" cy="50" r="35"
      stroke-dasharray="${slice.dashArray}" stroke-dashoffset="${slice.dashOffset}">
      <title>${slice.label}: ${slice.amount} (${slice.percent}%)</title>
    </circle>
    </#list>
  </svg>
  <ul class="pie-chart-legend">
    <#list pieSlices as slice>
    <li class="pie-chart-legend-item">
      <span class="pie-chart-swatch ${slice.cssClass}" aria-hidden="true"></span>
      <span class="pie-chart-legend-label">${slice.label}</span>
      <span class="pie-chart-legend-value">${slice.amount} <span class="pie-chart-legend-percent">(${slice.percent}%)</span></span>
    </li>
    </#list>
  </ul>
</div>
</#if>
