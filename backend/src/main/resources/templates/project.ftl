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
  </div>
</body>
</html>
