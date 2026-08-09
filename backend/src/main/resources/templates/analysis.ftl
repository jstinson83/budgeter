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

    <div class="period-tabs">
      <a href="/analysis?period=week" class="period-tab<#if period == 'week'> period-tab-active</#if>">Last week</a>
      <a href="/analysis?period=month" class="period-tab<#if period == 'month'> period-tab-active</#if>">Last month</a>
      <a href="/analysis?period=year" class="period-tab<#if period == 'year'> period-tab-active</#if>">Last year</a>
    </div>

    <#if uncategorizedCount gt 0>
    <form method="post" action="/analysis/categorize" class="upload-form">
      <button type="submit" class="button">Categorize ${uncategorizedCount} new transaction(s) with Gemini</button>
    </form>
    </#if>

    <#if (categoryTotals?size == 0)>
    <p class="empty-state">No transactions in this period.</p>
    <#else>
    <div class="transaction-list">
      <#list categoryTotals as row>
      <div class="transaction-row">
        <span class="transaction-description">${row.category} <span class="category-count">(${row.count})</span></span>
        <span class="transaction-amount ${row.totalClass}">${row.total}</span>
      </div>
      </#list>
    </div>
    </#if>
  </div>
</body>
</html>
