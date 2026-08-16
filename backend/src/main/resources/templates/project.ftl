<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>${project.name} · Projects · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="container">
    <#include "_nav.ftl">

    <a href="/projects" class="back-link">&larr; Projects</a>

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>${project.name}</h1>

    <form method="post" action="/projects/${project.id}" class="upload-form">
      <input type="text" name="name" value="${project.name}" required>
      <select name="status">
        <#list statusOptions as option>
        <option value="${option}" <#if option == project.status>selected</#if>>${option?lower_case?cap_first}</option>
        </#list>
      </select>
      <select name="component">
        <#list componentOptions as option>
        <option value="${option}" <#if option == project.component>selected</#if>>${option?lower_case?cap_first}</option>
        </#list>
      </select>
      <select name="priority">
        <#list priorityOptions as option>
        <option value="${option}" <#if option == project.priority>selected</#if>>${option?lower_case?cap_first}</option>
        </#list>
      </select>
      <button type="submit" class="button">Save</button>
    </form>

    <h2>Facts</h2>
    <#if (linkedFacts?size == 0)>
    <p class="empty-state">No facts linked yet.</p>
    <#else>
    <div class="transaction-list">
      <#list linkedFacts as fact>
      <div class="transaction-item">
        <div class="transaction-row">
          <span class="transaction-description">${fact.what}</span>
          <span class="fact-type-badge">${fact.component?lower_case?cap_first}</span>
        </div>
        <#if fact.sourceQuote??>
        <p class="fact-quote">&ldquo;${fact.sourceQuote}&rdquo;</p>
        </#if>
        <p class="fact-context">From ${fact.filename}</p>
        <form method="post" action="/projects/${project.id}/facts/${fact.id}/detach">
          <button type="submit" class="button button-small button-secondary">Remove</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>

    <#if (availableFacts?size gt 0)>
    <form method="post" action="/projects/${project.id}/facts" class="upload-form">
      <select name="factId">
        <#list availableFacts as fact>
        <option value="${fact.id}">[${fact.component?lower_case?cap_first}] ${fact.what}</option>
        </#list>
      </select>
      <button type="submit" class="button button-small">Link fact</button>
    </form>
    </#if>

    <h2>Documents</h2>
    <#if (linkedDocuments?size == 0)>
    <p class="empty-state">No documents linked yet.</p>
    <#else>
    <div class="transaction-list">
      <#list linkedDocuments as doc>
      <div class="transaction-row">
        <a href="/house/documents/${doc.id}" class="transaction-description">${doc.filename}</a>
        <form method="post" action="/projects/${project.id}/documents/${doc.id}/detach">
          <button type="submit" class="button button-small button-secondary">Remove</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>

    <#if (availableDocuments?size gt 0)>
    <form method="post" action="/projects/${project.id}/documents" class="upload-form">
      <select name="documentId">
        <#list availableDocuments as doc>
        <option value="${doc.id}">${doc.filename}</option>
        </#list>
      </select>
      <button type="submit" class="button button-small">Link document</button>
    </form>
    </#if>

    <h2>Notes, quotes &amp; links</h2>
    <#if (entries?size == 0)>
    <p class="empty-state">Nothing added yet.</p>
    <#else>
    <div class="transaction-list">
      <#list entries as entry>
      <div class="transaction-item">
        <div class="transaction-row">
          <span class="fact-type-badge">${entry.type?lower_case?cap_first}</span>
          <span class="transaction-date">${entry.createdAt}</span>
        </div>
        <#if entry.filename??>
        <p class="fact-context">Attached: ${entry.filename}</p>
        </#if>
        <#if (entry.textSegments?size gt 0)>
        <p>
          <#list entry.textSegments as segment>
          <#if segment.isUrl><a href="${segment.value}" target="_blank" rel="noopener noreferrer">${segment.value}</a><#else>${segment.value}</#if>
          </#list>
        </p>
        </#if>
        <form method="post" action="/projects/${project.id}/entries/${entry.id}/delete">
          <button type="submit" class="button button-small button-secondary">Remove</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>

    <form method="post" action="/projects/${project.id}/entries" enctype="multipart/form-data" class="upload-form">
      <textarea name="text" placeholder="Add a note, decision, or paste a link&hellip;" rows="2"></textarea>
      <input type="file" name="file">
      <button type="submit" class="button button-small">Add</button>
    </form>
  </div>
</body>
</html>
