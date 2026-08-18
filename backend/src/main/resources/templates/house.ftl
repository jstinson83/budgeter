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

    <h1>House Knowledge</h1>

    <div class="tab-bar">
      <a href="/house" class="tab-link tab-link-active">Documents</a>
      <a href="/house/facts" class="tab-link">Facts by category</a>
    </div>

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
  </div>
</body>
</html>
