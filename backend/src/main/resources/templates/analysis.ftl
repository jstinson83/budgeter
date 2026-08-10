<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Analysis · Budgeter</title>
<link rel="stylesheet" href="/styles.css">
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
      <a href="${prevHref}" class="month-nav-arrow" aria-label="Previous month">&larr;</a>
      <span class="month-nav-label">${monthLabel}</span>
      <a href="${nextHref}" class="month-nav-arrow" aria-label="Next month">&rarr;</a>
    </div>

    <#if uncategorizedCount gt 0>
    <form method="post" action="/analysis/categorize" class="upload-form">
      <input type="hidden" name="year" value="${year?c}">
      <input type="hidden" name="month" value="${month?c}">
      <button type="submit" class="button">Process ${uncategorizedCount} new transaction(s)</button>
    </form>
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
</body>
</html>
