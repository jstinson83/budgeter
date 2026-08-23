<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Review duplicates · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="app-shell">
    <#assign activeSection = "finances">
    <#include "_nav.ftl">

    <main class="app-main">
    <#assign activeTab = "transactions">
    <#include "_finances-tabs.ftl">

    <p><a href="/transactions">&larr; Back to transactions</a></p>

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>

    <p class="dashboard-card-note">
      Each group below shares the same date, description, amount, and account -
      the kind of overlap a re-imported statement can leave behind. This never
      includes rows from the same upload (those are always genuinely separate
      transactions), only rows that came from different imports. Review each
      group and delete whichever rows aren't real - keep them if they're
      actually separate charges that happened to match.
    </p>

    <#if (duplicateGroups?size == 0)>
    <p class="empty-state">No duplicate transactions found.</p>
    <#else>
    <#list duplicateGroups as group>
    <div class="transaction-list">
      <#list group.transactions as transaction>
      <div class="transaction-row">
        <span class="transaction-date">${transaction.date}</span>
        <span class="transaction-description">${transaction.description}</span>
        <span class="transaction-account">${transaction.accountType}</span>
        <span class="transaction-amount ${transaction.amountClass}">${transaction.amount}</span>
        <form method="post" action="/transactions/${transaction.id}/delete?returnTo=duplicates" onsubmit="return confirm('Delete this transaction? This cannot be undone.');">
          <button type="submit" class="button button-small button-danger">Delete</button>
        </form>
      </div>
      </#list>
    </div>
    </#list>
    </#if>
    </main>
  </div>
</body>
</html>
