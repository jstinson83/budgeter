<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Projects · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="container">
    <#include "_nav.ftl">

    <#assign activeTab = "projects">
    <#include "_house-tabs.ftl">

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>Projects</h1>

    <#if isGenerating>
    <p class="empty-state">Generating recommendations&hellip;</p>
    <#else>
    <form method="post" action="/projects/recommendations/generate">
      <button type="submit" class="button button-secondary">Generate recommendations</button>
    </form>
    </#if>

    <#if (recommendations?size gt 0)>
    <h2>Recommendations</h2>
    <div class="card-list">
      <#list recommendations as rec>
      <div class="info-card">
        <div class="info-card-header">
          <span class="transaction-description">${rec.name}</span>
          <span class="transaction-account">${rec.component?lower_case?cap_first}</span>
          <span class="priority-badge priority-badge-${rec.priority?lower_case}">${rec.priority?lower_case?cap_first}</span>
        </div>
        <p class="fact-context">${rec.rationale}</p>
        <#if (rec.supportingFacts?size gt 0)>
        <ul class="fact-context">
          <#list rec.supportingFacts as fact>
          <li>${fact}</li>
          </#list>
        </ul>
        </#if>
        <div class="document-actions">
          <form method="post" action="/projects/recommendations/${rec.id}/accept">
            <button type="submit" class="button button-small">Create project</button>
          </form>
          <form method="post" action="/projects/recommendations/${rec.id}/reject">
            <button type="submit" class="button button-small button-secondary">Reject</button>
          </form>
        </div>
      </div>
      </#list>
    </div>
    </#if>

    <form method="get" action="/projects" class="form-row filter-row">
      <div class="form-field">
        <span class="form-field-label">Component</span>
        <select name="component">
          <option value="">All components</option>
          <#list componentOptions as option>
          <option value="${option}" <#if selectedComponent?? && option == selectedComponent>selected</#if>>${option?lower_case?cap_first}</option>
          </#list>
        </select>
      </div>
      <button type="submit" class="button button-secondary">Filter</button>
    </form>

    <#if (groups?size == 0)>
    <p class="empty-state">No projects yet. Add one below.</p>
    <#else>
    <div class="project-board">
    <#list groups as group>
    <section class="project-column">
      <h2 class="project-column-title">${group.status?lower_case?cap_first} <span class="project-column-count">${group.projects?size}</span></h2>
      <#list group.projects as project>
      <div class="project-card">
        <a href="/projects/${project.id}" class="project-card-name">${project.name}</a>
        <span class="project-card-meta">
          <span>${project.component?lower_case?cap_first}</span>
          <span class="priority-badge priority-badge-${project.priority?lower_case}">${project.priority?lower_case?cap_first}</span>
        </span>
        <#if (project.subprojects?size gt 0)>
        <details class="subproject-disclosure">
          <summary>${project.subprojects?size} subproject<#if (project.subprojects?size gt 1)>s</#if></summary>
          <ul class="subproject-list">
            <#list project.subprojects as sub>
            <li><a href="/projects/${sub.id}">${sub.name}</a></li>
            </#list>
          </ul>
        </details>
        </#if>
      </div>
      </#list>
    </section>
    </#list>
    </div>
    </#if>

    <div class="form-card">
      <h2>Add project</h2>
      <form method="post" action="/projects" class="form-row">
        <div class="form-field form-field-wide">
          <span class="form-field-label">Name</span>
          <input type="text" name="name" placeholder="Project name" required>
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
        <button type="submit" class="button">Add project</button>
      </form>
    </div>
  </div>

  <#if isGenerating>
  <script>
    // No framework in this app - same polling pattern as
    // house-document.ftl. Keeps RecommendationJobManager's background
    // coroutine's CPU allocated on Cloud Run between the request that
    // started it and the one that observes it finished, in addition to
    // driving the UI.
    (function poll() {
      fetch('/projects/recommendations/status')
        .then((response) => response.json())
        .then((status) => {
          if (status.status === 'RUNNING') {
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
