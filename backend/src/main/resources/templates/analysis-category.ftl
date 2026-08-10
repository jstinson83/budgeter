<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${categoryLabel} · Analysis · Budgeter</title>
<link rel="stylesheet" href="/styles.css">
</head>
<body>
  <div class="container">
    <#include "_nav.ftl">

    <a href="${backHref}" class="back-link">&larr; ${monthLabel}</a>
    <h1>${categoryLabel}</h1>

    <#if (transactions?size == 0)>
    <p class="empty-state">No transactions in this category for this period.</p>
    <#else>
    <div class="transaction-list">
      <#list transactions as transaction>
      <div class="transaction-row">
        <span class="transaction-date">${transaction.date}</span>
        <span class="transaction-description">${transaction.description}</span>
        <span class="transaction-account">${transaction.accountType}</span>
        <span class="transaction-amount ${transaction.amountClass}">${transaction.amount}</span>
      </div>
      </#list>
    </div>
    </#if>
  </div>
</body>
</html>
