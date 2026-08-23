<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>House Knowledge · Home OS</title>
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
    <#assign activeSection = "house">
    <#include "_nav.ftl">

    <main class="app-main">
    <#assign activeTab = "house">
    <#include "_house-tabs.ftl">

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>House Knowledge</h1>

    <form method="post" action="/house/documents/upload" enctype="multipart/form-data" class="upload-form">
      <input type="file" name="file" accept=".pdf" required>
      <textarea name="context" placeholder="Optional: add context to help extraction, e.g. &quot;this is the 2017 kitchen renovation, we removed the wall between the kitchen and dining room&quot;" rows="2"></textarea>
      <button type="submit" class="button">Upload document</button>
    </form>

    <#if (documents?size == 0)>
    <p class="empty-state">No documents yet. Upload a home inspection or engineering plans to get started.</p>
    <#else>
    <div class="card-list">
      <#list documents as doc>
      <a href="/house/documents/${doc.id}" class="info-card">
        <span class="info-card-header">
          <span class="transaction-description">${doc.filename}</span>
          <span class="transaction-account">
            <#if doc.status == "EXTRACTING">Extracting&hellip;
            <#elseif doc.status == "FAILED">Extraction failed
            <#elseif doc.status == "EXTRACTED">${doc.factCount} fact(s)<#if doc.needsReviewCount gt 0>, ${doc.needsReviewCount} need review</#if>
            <#else>Uploaded
            </#if>
          </span>
        </span>
      </a>
      </#list>
    </div>
    </#if>
    </main>
  </div>
</body>
</html>
