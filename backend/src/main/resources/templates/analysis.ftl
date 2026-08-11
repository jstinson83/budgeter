<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Analysis · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="container">
    <#include "_nav.ftl">

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <div class="month-nav">
      <a href="${prevHref}" class="month-nav-arrow" aria-label="Go back to ${prevMonthLabel}">&larr; ${prevMonthLabel}</a>
      <span class="month-nav-label">${monthLabel}</span>
      <#if nextHref??>
      <a href="${nextHref}" class="month-nav-arrow" aria-label="Go forward to ${nextMonthLabel}">${nextMonthLabel} &rarr;</a>
      <#else>
      <span class="month-nav-arrow month-nav-arrow-disabled" aria-hidden="true">&rarr;</span>
      </#if>
    </div>

    <div class="month-summary">
      <span class="month-summary-label">Net change <span class="month-summary-note">(excl. investments)</span></span>
      <span class="transaction-amount month-summary-amount ${netChangeClass}">${netChange}</span>
    </div>

    <#include "_pie-chart.ftl">

    <#if jobRunning>
    <p class="empty-state" id="categorize-status">Categorizing… this keeps running in the background, feel free to navigate away.</p>
    </#if>

    <#if (categoryTotals?size == 0)>
    <p class="empty-state">No transactions in this period.</p>
    <#else>
    <div class="transaction-list">
      <#list categoryTotals as row>
      <a href="${row.href}" class="transaction-row transaction-row-link">
        <span class="transaction-description">${row.category} <span class="category-count">(${row.count})</span></span>
        <span class="transaction-amount ${row.totalClass}">${row.total}</span>
      </a>
      </#list>
    </div>
    </#if>
  </div>

  <#if jobRunning>
  <script>
    // No framework in this app - see analysis-category.ftl/CLAUDE.md. This
    // just polls the categorize job's status every 2s and reloads once it
    // leaves RUNNING - the page reload itself is what picks up the job's
    // final message/error (see AnalysisRoutes.kt's GET /analysis) and
    // refreshes the category totals below. Each poll is also what keeps
    // this job's CPU allocated on Cloud Run between chunks - see
    // CategorizationJob.kt.
    (function poll() {
      fetch('/analysis/categorize/status')
        .then((response) => response.json())
        .then((status) => {
          if (status.status === 'RUNNING') {
            setTimeout(poll, 2000);
          } else {
            window.location.reload();
          }
        });
    })();
  </script>
  </#if>
</body>
</html>
