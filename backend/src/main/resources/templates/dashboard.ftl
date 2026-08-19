<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="app-shell">
    <#assign activeSection = "dashboard">
    <#include "_nav.ftl">

    <main class="app-main">
    <#if !hasTransactions>
    <p class="empty-state">No transactions yet. <a href="/transactions">Import a CSV</a> to see your summary here.</p>
    <#else>

    <form method="get" action="/" class="period-selector" id="period-form">
      <div class="segmented-control period-segmented-control" role="radiogroup" aria-label="Time period">
        <input type="radio" name="period" value="3m" id="period-3m"<#if periodOption == "3m"> checked</#if>>
        <label for="period-3m">3 months</label>
        <input type="radio" name="period" value="6m" id="period-6m"<#if periodOption == "6m"> checked</#if>>
        <label for="period-6m">6 months</label>
        <input type="radio" name="period" value="custom" id="period-custom"<#if periodOption == "custom"> checked</#if>>
        <label for="period-custom">Custom</label>
      </div>
      <div class="period-custom-range" id="period-custom-range"<#if periodOption != "custom"> hidden</#if>>
        <div class="form-field">
          <label class="form-field-label" for="period-start">Start</label>
          <input type="date" name="start" id="period-start" value="${periodStartInput}">
        </div>
        <div class="form-field">
          <label class="form-field-label" for="period-end">End</label>
          <input type="date" name="end" id="period-end" value="${periodEndInput}">
        </div>
        <button type="submit" class="button button-small">Apply</button>
      </div>
    </form>

    <a class="export-link" href="/export/transactions?period=${periodOption}&start=${periodStartInput}&end=${periodEndInput}">Export CSV (${periodLabel})</a>

    <h2 class="dashboard-section-title">Money in/out</h2>
    <div class="month-summary">
      <span class="month-summary-label">${periodLabel}</span>
      <span class="transaction-amount month-summary-amount ${netChangeTotalClass}">${netChangeTotal}</span>
    </div>
    <div class="dashboard-trend-bars">
      <#list netChangeSeries as m>
      <a class="dashboard-trend-bar-col" href="${m.href}" aria-label="View ${m.label} summary">
        <div class="dashboard-trend-bar-track">
          <div class="dashboard-trend-bar <#if m.isNegative>dashboard-trend-bar-negative<#else>dashboard-trend-bar-positive</#if>" style="height: ${m.barPercent}%;"></div>
        </div>
        <span class="dashboard-trend-bar-label">${m.label}</span>
      </a>
      </#list>
    </div>

    <#if (pieSlices?size > 0)>
    <h2 class="dashboard-section-title">Where it went</h2>
    <div class="month-summary">
      <span class="month-summary-label">${periodLabel}</span>
    </div>
    <#include "_pie-chart.ftl">
    </#if>

    <h2 class="dashboard-section-title">Coverage</h2>
    <div class="dashboard-card-grid">
      <#list coverage as c>
      <div class="dashboard-card<#if c.isStale> dashboard-card-warning</#if>">
        <span class="dashboard-card-label">${c.accountType}</span>
        <span class="dashboard-card-note">${c.earliest} &ndash; ${c.latest}</span>
        <span class="dashboard-card-note">${c.daysSinceLastImport?c} day(s) since last import<#if c.isStale> &mdash; stale</#if></span>
        <#list c.gaps as gap>
        <span class="dashboard-card-gap">Possible gap: ${gap.start} &ndash; ${gap.end} (${gap.days?c} days)</span>
        </#list>
      </div>
      </#list>
    </div>

    <#if (movers?size gt 0)>
    <p class="dashboard-card-label">Categories trending up</p>
    <div class="transaction-list">
      <#list movers as mover>
      <div class="transaction-row">
        <span class="transaction-description">${mover.label} <span class="category-count">vs. ${mover.priorAverage} avg</span></span>
        <span class="transaction-amount transaction-amount-negative">${mover.currentTotal}</span>
      </div>
      </#list>
    </div>
    </#if>

    <#if biggestExpense??>
    <p class="dashboard-card-note">Biggest expense this month: ${biggestExpense.description} (${biggestExpense.accountType}) &mdash; <span class="transaction-amount-negative">${biggestExpense.amount}</span> on ${biggestExpense.date}</p>
    </#if>

    </#if>
    </main>
  </div>

  <#if hasTransactions>
  <script>
    // No framework in this app - see analysis.ftl/CLAUDE.md. A 3/6-month
    // preset applies immediately; "Custom" just reveals the date fields
    // instead, since the maintainer still needs to pick dates before there's
    // anything to submit.
    (function () {
      var customRange = document.getElementById('period-custom-range');
      document.querySelectorAll('#period-form input[name="period"]').forEach(function (radio) {
        radio.addEventListener('change', function () {
          if (radio.value === 'custom') {
            customRange.hidden = false;
          } else {
            customRange.hidden = true;
            document.getElementById('period-form').submit();
          }
        });
      });
    })();
  </script>
  </#if>
</body>
</html>
