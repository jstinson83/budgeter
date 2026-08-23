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
<link rel="manifest" href="/manifest.webmanifest">
<meta name="theme-color" content="#241f16">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Home OS">
<script src="/register-sw.js" defer></script>
</head>
<body>
  <div class="app-shell">
    <#assign activeSection = "finances">
    <#include "_nav.ftl">

    <main class="app-main">
    <#assign activeTab = "transactions">
    <#include "_finances-tabs.ftl">

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
    <div class="upload-form">
      <a href="/transactions/duplicates" class="button button-small button-secondary">Review duplicates</a>
      <form method="post" action="/transactions/delete-all" onsubmit="return confirm('Delete all transactions? This cannot be undone.');">
        <button type="submit" class="button button-danger">Delete all transactions</button>
      </form>
    </div>
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
        <form method="post" action="/transactions/${transaction.id}/delete" onsubmit="return confirm('Delete this transaction? This cannot be undone.');">
          <button type="submit" class="button button-small button-danger">Delete</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>
    </main>
  </div>
</body>
</html>
