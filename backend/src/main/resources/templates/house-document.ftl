<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${document.filename} · House Knowledge · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="container">
    <#include "_nav.ftl">

    <a href="/house" class="back-link">&larr; House Knowledge</a>

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>${document.filename}</h1>

    <#if document.status == "FAILED">
    <p class="banner-error">Extraction failed<#if document.error??>: ${document.error}</#if></p>
    <#elseif document.status == "EXTRACTING">
    <p class="empty-state">Extracting facts from this document&hellip;</p>
    </#if>

    <div class="document-actions">
      <#if document.status == "FAILED">
      <form method="post" action="/house/documents/${document.id}/retry">
        <button type="submit" class="button button-small">Retry extraction</button>
      </form>
      </#if>
      <#if document.status != "EXTRACTING">
      <form method="post" action="/house/documents/${document.id}/delete" onsubmit="return confirm('Delete ${document.filename} and its facts? This can&#39;t be undone.');">
        <button type="submit" class="button button-small button-secondary">Delete document</button>
      </form>
      </#if>
    </div>

    <#if (needsReviewFacts?size gt 0)>
    <h2>Needs your input</h2>
    <div class="transaction-list">
      <#list needsReviewFacts as fact>
      <div class="transaction-item">
        <div class="transaction-row">
          <span class="transaction-description">${fact.what}</span>
          <span class="fact-type-badge">${fact.type?replace("_", " ")?lower_case}</span>
        </div>
        <#if fact.sourceQuote??>
        <p class="fact-quote">&ldquo;${fact.sourceQuote}&rdquo;<#if fact.sourceLocation??> <span class="fact-location">(${fact.sourceLocation})</span></#if></p>
        </#if>
        <#if fact.reviewQuestion??>
        <p class="fact-question">${fact.reviewQuestion}</p>
        </#if>
        <div class="fact-review-actions">
          <#list ["Longstanding condition", "It was repaired", "Still investigating", "I don't know"] as preset>
          <form method="post" action="/house/facts/${fact.id}/resolve">
            <input type="hidden" name="documentId" value="${fact.documentId}">
            <input type="hidden" name="homeownerContext" value="${preset}">
            <button type="submit" class="button button-small button-secondary">${preset}</button>
          </form>
          </#list>
        </div>
        <form method="post" action="/house/facts/${fact.id}/resolve" class="fact-review-freeform">
          <input type="hidden" name="documentId" value="${fact.documentId}">
          <input type="text" name="homeownerContext" placeholder="Add your own explanation">
          <button type="submit" class="button button-small">Save</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>

    <h2>Known</h2>
    <#if (facts?size == 0)>
    <p class="empty-state">Nothing here yet.</p>
    <#else>
    <div class="transaction-list">
      <#list facts as fact>
      <div class="transaction-item">
        <div class="transaction-row">
          <span class="transaction-description">${fact.what}</span>
          <span class="fact-type-badge">${fact.type?replace("_", " ")?lower_case}</span>
        </div>
        <#if fact.sourceQuote??>
        <p class="fact-quote">&ldquo;${fact.sourceQuote}&rdquo;<#if fact.sourceLocation??> <span class="fact-location">(${fact.sourceLocation})</span></#if></p>
        </#if>
        <#if fact.homeownerContext??>
        <p class="fact-context">You said: ${fact.homeownerContext}</p>
        </#if>
      </div>
      </#list>
    </div>
    </#if>
  </div>

  <#if document.status == "EXTRACTING">
  <script>
    // No framework in this app - see analysis.ftl/CLAUDE.md, same pattern.
    // Polls this document's extraction status every 2s and reloads once it
    // leaves EXTRACTING - the page reload itself is what picks up the
    // extracted facts and the one-shot "Found N thing(s)..."/error banner
    // (see HouseRoutes.kt's GET /house/documents/{id}). Each poll is also
    // what keeps the background extraction coroutine's CPU allocated on
    // Cloud Run between the upload request that launched it and the
    // request that observes it finished - see HouseFactExtractionJob.kt.
    (function poll() {
      fetch('/house/documents/${document.id}/status')
        .then((response) => response.json())
        .then((status) => {
          if (status.status === 'EXTRACTING') {
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
