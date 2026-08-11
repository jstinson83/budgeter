<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Facts by category · House Knowledge · Home OS</title>
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

    <h1>Facts by category</h1>

    <#if (groups?size == 0)>
    <p class="empty-state">No facts yet. Upload a document from House Knowledge to get started.</p>
    <#else>
    <#list groups as group>
    <section class="component-group">
      <h2>${group.component?lower_case?cap_first}
        <span class="component-group-count">${group.factCount} fact(s)<#if group.needsReviewCount gt 0>, ${group.needsReviewCount} need review</#if></span>
      </h2>

      <div class="component-summary">
        <#if group.summary??>
        <p<#if group.summaryStale> class="component-summary-stale"</#if>>${group.summary}</p>
        <#if group.summaryStale>
        <p class="fact-context">New facts have been added since this summary was generated.</p>
        </#if>
        <#else>
        <p class="empty-state">No summary yet.</p>
        </#if>
        <form method="post" action="/house/facts/summary/${group.component}">
          <button type="submit" class="button button-small button-secondary"><#if group.summary??>Regenerate<#else>Generate</#if> summary</button>
        </form>
      </div>

      <div class="transaction-list">
        <#list group.facts as fact>
        <div class="transaction-item">
          <div class="transaction-row">
            <span class="transaction-description">${fact.what}</span>
            <span class="fact-type-badge">${fact.type?replace("_", " ")?lower_case}</span>
          </div>
          <#if fact.sourceQuote??>
          <p class="fact-quote">&ldquo;${fact.sourceQuote}&rdquo;</p>
          </#if>
          <#if fact.homeownerContext??>
          <p class="fact-context">You said: ${fact.homeownerContext}</p>
          </#if>
          <p class="fact-context">From <a href="/house/documents/${fact.documentId}">${fact.filename}</a></p>
        </div>
        </#list>
      </div>
    </section>
    </#list>
    </#if>
  </div>
</body>
</html>
