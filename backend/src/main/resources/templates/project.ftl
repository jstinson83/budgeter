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
  <div class="app-shell">
    <#assign activeSection = "house">
    <#include "_nav.ftl">

    <main class="app-main">
    <a href="/projects" class="back-link">&larr; Projects</a>

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>${project.name}</h1>

    <#if project.parent??>
    <p class="fact-context">
      Subproject of <a href="/projects/${project.parent.id}">${project.parent.name}</a>
    </p>
    <form method="post" action="/projects/${project.parent.id}/subprojects/${project.id}/detach">
      <button type="submit" class="button button-small button-secondary">Remove from ${project.parent.name}</button>
    </form>
    </#if>

    <div class="form-card">
      <form method="post" action="/projects/${project.id}" class="form-row">
        <div class="form-field form-field-wide">
          <span class="form-field-label">Name</span>
          <input type="text" name="name" value="${project.name}" required>
        </div>
        <div class="form-field">
          <span class="form-field-label">Status</span>
          <select name="status">
            <#list statusOptions as option>
            <option value="${option}" <#if option == project.status>selected</#if>>${option?lower_case?cap_first}</option>
            </#list>
          </select>
        </div>
        <div class="form-field">
          <span class="form-field-label">Component</span>
          <select name="component">
            <#list componentOptions as option>
            <option value="${option}" <#if option == project.component>selected</#if>>${option?lower_case?cap_first}</option>
            </#list>
          </select>
        </div>
        <div class="form-field">
          <span class="form-field-label">Priority</span>
          <select name="priority">
            <#list priorityOptions as option>
            <option value="${option}" <#if option == project.priority>selected</#if>>${option?lower_case?cap_first}</option>
            </#list>
          </select>
        </div>
        <button type="submit" class="button">Save</button>
      </form>
    </div>

    <#if !project.hasParent>
    <details class="detail-section"<#if (subprojects?size gt 0)> open</#if>>
      <summary>Subprojects<#if (subprojects?size gt 0)> (${subprojects?size})</#if></summary>

      <#if (subprojects?size == 0)>
      <p class="empty-state">No subprojects yet.</p>
      <#else>
      <div class="card-list">
        <#list subprojects as sub>
        <a href="/projects/${sub.id}" class="info-card">
          <span class="info-card-header">
            <span class="transaction-description">${sub.name}</span>
            <span class="transaction-account">${sub.status?lower_case?cap_first}</span>
            <span class="priority-badge priority-badge-${sub.priority?lower_case}">${sub.priority?lower_case?cap_first}</span>
          </span>
        </a>
        </#list>
      </div>
      <div class="document-actions">
        <#list subprojects as sub>
        <form method="post" action="/projects/${project.id}/subprojects/${sub.id}/detach">
          <button type="submit" class="button button-small button-secondary">Remove ${sub.name}</button>
        </form>
        </#list>
      </div>
      </#if>

      <#if (availableAsSubproject?size gt 0)>
      <form method="post" action="/projects/${project.id}/subprojects" class="form-row">
        <div class="form-field form-field-wide">
          <span class="form-field-label">Add an existing project as a subproject</span>
          <select name="childId">
            <#list availableAsSubproject as candidate>
            <option value="${candidate.id}">${candidate.name}</option>
            </#list>
          </select>
        </div>
        <button type="submit" class="button button-secondary">Add subproject</button>
      </form>
      </#if>

      <div class="form-card">
        <h3>Or create a new subproject</h3>
        <form method="post" action="/projects" class="form-row">
          <input type="hidden" name="parentId" value="${project.id}">
          <div class="form-field form-field-wide">
            <span class="form-field-label">Name</span>
            <input type="text" name="name" placeholder="Subproject name" required>
          </div>
          <div class="form-field">
            <span class="form-field-label">Status</span>
            <select name="status">
              <option value="PLANNED" selected>Planned</option>
              <option value="ACTIVE">Active</option>
              <option value="DEPRIORITIZED">Deprioritized</option>
              <option value="COMPLETED">Completed</option>
            </select>
          </div>
          <div class="form-field">
            <span class="form-field-label">Component</span>
            <select name="component">
              <#list componentOptions as option>
              <option value="${option}">${option?lower_case?cap_first}</option>
              </#list>
            </select>
          </div>
          <div class="form-field">
            <span class="form-field-label">Priority</span>
            <select name="priority">
              <option value="MEDIUM" selected>Medium</option>
              <option value="HIGH">High</option>
              <option value="LOW">Low</option>
            </select>
          </div>
          <button type="submit" class="button">Add subproject</button>
        </form>
      </div>
    </details>
    </#if>

    <details class="detail-section" open>
      <summary>Notes, quotes &amp; links<#if (entries?size gt 0)> (${entries?size})</#if></summary>

      <#if (entries?size == 0)>
      <p class="empty-state">Nothing added yet.</p>
      <#else>
      <div class="card-list">
        <#list entries as entry>
        <div class="info-card">
          <div class="info-card-header">
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

      <div class="form-card">
        <form method="post" action="/projects/${project.id}/entries" enctype="multipart/form-data" class="form-row">
          <div class="form-field form-field-wide">
            <span class="form-field-label">Add a note, quote, or link</span>
            <textarea name="text" placeholder="Type a note, or paste a link&hellip;" rows="2"></textarea>
          </div>
          <div class="form-field form-field-wide">
            <span class="form-field-label">Attach a file (optional)</span>
            <input type="file" name="file">
          </div>
          <button type="submit" class="button">Add</button>
        </form>
      </div>
    </details>

    <details class="detail-section"<#if (linkedFacts?size gt 0)> open</#if>>
      <summary>Facts<#if (linkedFacts?size gt 0)> (${linkedFacts?size})</#if></summary>

      <#if (linkedFacts?size == 0)>
      <p class="empty-state">No facts linked yet.</p>
      <#else>
      <div class="card-list">
        <#list linkedFacts as fact>
        <div class="info-card">
          <div class="info-card-header">
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
      <form method="post" action="/projects/${project.id}/facts" class="form-row">
        <div class="form-field form-field-wide">
          <span class="form-field-label">Link an existing fact</span>
          <select name="factId">
            <#list availableFacts as fact>
            <option value="${fact.id}">[${fact.component?lower_case?cap_first}] ${fact.what}</option>
            </#list>
          </select>
        </div>
        <button type="submit" class="button button-secondary">Link fact</button>
      </form>
      </#if>
    </details>

    <details class="detail-section"<#if (linkedDocuments?size gt 0)> open</#if>>
      <summary>Documents<#if (linkedDocuments?size gt 0)> (${linkedDocuments?size})</#if></summary>

      <#if (linkedDocuments?size == 0)>
      <p class="empty-state">No documents linked yet.</p>
      <#else>
      <div class="card-list">
        <#list linkedDocuments as doc>
        <div class="info-card-header">
          <a href="/house/documents/${doc.id}" class="transaction-description">${doc.filename}</a>
          <form method="post" action="/projects/${project.id}/documents/${doc.id}/detach">
            <button type="submit" class="button button-small button-secondary">Remove</button>
          </form>
        </div>
        </#list>
      </div>
      </#if>

      <#if (availableDocuments?size gt 0)>
      <form method="post" action="/projects/${project.id}/documents" class="form-row">
        <div class="form-field form-field-wide">
          <span class="form-field-label">Link an existing document</span>
          <select name="documentId">
            <#list availableDocuments as doc>
            <option value="${doc.id}">${doc.filename}</option>
            </#list>
          </select>
        </div>
        <button type="submit" class="button button-secondary">Link document</button>
      </form>
      </#if>
    </details>
    </main>
  </div>
</body>
</html>
