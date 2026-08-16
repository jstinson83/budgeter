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

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>Projects</h1>

    <form method="get" action="/projects" class="upload-form">
      <select name="component">
        <option value="">All components</option>
        <#list componentOptions as option>
        <option value="${option}" <#if selectedComponent?? && option == selectedComponent>selected</#if>>${option?lower_case?cap_first}</option>
        </#list>
      </select>
      <button type="submit" class="button button-small button-secondary">Filter</button>
    </form>

    <#if (groups?size == 0)>
    <p class="empty-state">No projects yet. Add one below.</p>
    <#else>
    <#list groups as group>
    <section class="component-group">
      <h2>${group.status?lower_case?cap_first}</h2>
      <div class="transaction-list">
        <#list group.projects as project>
        <a href="/projects/${project.id}" class="transaction-row transaction-row-link">
          <span class="transaction-description">${project.name}</span>
          <span class="transaction-account">${project.component?lower_case?cap_first} &middot; ${project.priority?lower_case?cap_first}</span>
        </a>
        </#list>
      </div>
    </section>
    </#list>
    </#if>

    <h2>Add project</h2>
    <form method="post" action="/projects" class="upload-form">
      <input type="text" name="name" placeholder="Project name" required>
      <select name="status">
        <option value="PLANNED" selected>Planned</option>
        <option value="ACTIVE">Active</option>
        <option value="DEPRIORITIZED">Deprioritized</option>
        <option value="COMPLETED">Completed</option>
      </select>
      <select name="component">
        <#list componentOptions as option>
        <option value="${option}">${option?lower_case?cap_first}</option>
        </#list>
      </select>
      <select name="priority">
        <option value="MEDIUM" selected>Medium</option>
        <option value="HIGH">High</option>
        <option value="LOW">Low</option>
      </select>
      <button type="submit" class="button">Add project</button>
    </form>
  </div>
</body>
</html>
