<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${categoryLabel} · Analysis · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="container">
    <#include "_nav.ftl">

    <a href="${backHref}" class="back-link">&larr; ${monthLabel}</a>
    <h1>${categoryLabel} <span class="transaction-amount ${totalClass}">${total}</span></h1>

    <#if (transactions?size == 0)>
    <p class="empty-state">No transactions in this category for this period.</p>
    <#else>
    <div class="transaction-list">
      <#list transactions as transaction>
      <div class="transaction-item">
        <div class="transaction-row">
          <span class="transaction-date">${transaction.date}</span>
          <span class="transaction-description">${transaction.description}</span>
          <span class="transaction-account">${transaction.accountType}</span>
          <span class="transaction-amount ${transaction.amountClass}">${transaction.amount}</span>
        </div>
        <form method="post" action="/analysis/recategorize" class="recategorize-form">
          <input type="hidden" name="transactionId" value="${transaction.id}">
          <input type="hidden" name="fromSlug" value="${categorySlug}">
          <input type="hidden" name="year" value="${year?c}">
          <input type="hidden" name="month" value="${month?c}">
          <select name="category" required>
            <#list categoryOptions as option>
            <option value="${option.name}">${option.label}</option>
            </#list>
          </select>
          <label><input type="radio" name="matchType" value="EXACT" checked> Exact</label>
          <label><input type="radio" name="matchType" value="SUBSTRING"> Contains</label>
          <input type="text" name="pattern" value="${transaction.description}" class="recategorize-pattern">
          <button type="submit" class="button button-small">Recategorize</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>
  </div>
</body>
</html>
