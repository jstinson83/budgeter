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
      <button type="button" class="transaction-row transaction-row-button" data-open-dialog="recategorize-${transaction.id}">
        <span class="transaction-date">${transaction.date}</span>
        <span class="transaction-description">${transaction.description}</span>
        <span class="transaction-account">${transaction.accountType}</span>
        <span class="transaction-amount ${transaction.amountClass}">${transaction.amount}</span>
      </button>

      <dialog id="recategorize-${transaction.id}" class="recategorize-dialog">
        <form method="post" action="/analysis/recategorize" class="recategorize-form">
          <h2 class="recategorize-dialog-title">Recategorize</h2>
          <p class="recategorize-dialog-description">${transaction.description}</p>

          <input type="hidden" name="transactionId" value="${transaction.id}">
          <input type="hidden" name="fromSlug" value="${categorySlug}">
          <input type="hidden" name="year" value="${year?c}">
          <input type="hidden" name="month" value="${month?c}">

          <label class="field-label" for="category-${transaction.id}">Category</label>
          <select name="category" id="category-${transaction.id}" required>
            <#list categoryOptions as option>
            <option value="${option.name}">${option.label}</option>
            </#list>
          </select>

          <div class="segmented-control" role="radiogroup" aria-label="Match type">
            <input type="radio" name="matchType" value="EXACT" id="matchType-exact-${transaction.id}" checked>
            <label for="matchType-exact-${transaction.id}">Exact</label>
            <input type="radio" name="matchType" value="SUBSTRING" id="matchType-substring-${transaction.id}">
            <label for="matchType-substring-${transaction.id}">Contains</label>
          </div>

          <label class="field-label" for="pattern-${transaction.id}">Match pattern</label>
          <input type="text" name="pattern" id="pattern-${transaction.id}" value="${transaction.description}" class="recategorize-pattern">

          <div class="recategorize-dialog-actions">
            <button type="button" class="button button-secondary" data-close-dialog>Cancel</button>
            <button type="submit" class="button">Recategorize</button>
          </div>
        </form>
      </dialog>
      </#list>
    </div>
    </#if>
  </div>
  <script>
    // Each transaction row opens its own <dialog> (no framework in this
    // app - see styles.css/CLAUDE.md for the "plain server-rendered HTML"
    // approach elsewhere). Wired generically off data attributes rather
    // than per-transaction inline handlers so this stays a single small
    // script regardless of how many rows the page renders.
    document.querySelectorAll('[data-open-dialog]').forEach((trigger) => {
      trigger.addEventListener('click', () => {
        document.getElementById(trigger.dataset.openDialog).showModal();
      });
    });
    document.querySelectorAll('.recategorize-dialog').forEach((dialog) => {
      dialog.querySelectorAll('[data-close-dialog]').forEach((btn) => {
        btn.addEventListener('click', () => dialog.close());
      });
      // Click on the ::backdrop lands directly on the <dialog> element
      // itself (it has no content box there) - clicks inside the form
      // bubble up with a different target, so this only fires for
      // outside-the-card clicks.
      dialog.addEventListener('click', (event) => {
        if (event.target === dialog) dialog.close();
      });
    });
  </script>
</body>
</html>
