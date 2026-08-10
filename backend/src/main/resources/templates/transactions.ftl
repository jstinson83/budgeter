<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Transactions · Home OS</title>
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

    <form method="post" action="/transactions/import" enctype="multipart/form-data" class="upload-form">
      <label><input type="radio" name="accountType" value="BANK" checked> Bank</label>
      <label><input type="radio" name="accountType" value="CREDIT_CARD"> Credit card</label>
      <label><input type="radio" name="accountType" value="LOC"> Line of credit</label>
      <input type="file" name="file" accept=".csv" required>
      <button type="submit" class="button">Import CSV</button>
    </form>

    <#if (transactions?size gt 0)>
    <form method="post" action="/transactions/delete-all" class="upload-form" onsubmit="return confirm('Delete all transactions? This cannot be undone.');">
      <button type="submit" class="button button-danger">Delete all transactions</button>
    </form>
    </#if>

    <#if (transactions?size == 0)>
    <p class="empty-state">No transactions yet. Import a CSV to get started.</p>
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
