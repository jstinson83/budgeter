<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Categories · Home OS</title>
<link rel="stylesheet" href="/styles.css">
<link rel="icon" type="image/svg+xml" href="/favicon.svg">
<link rel="alternate icon" type="image/png" sizes="32x32" href="/favicon-32x32.png">
<link rel="apple-touch-icon" sizes="180x180" href="/apple-touch-icon.png">
</head>
<body>
  <div class="app-shell">
    <#assign activeSection = "finances">
    <#include "_nav.ftl">

    <main class="app-main">
    <#assign activeTab = "categories">
    <#include "_finances-tabs.ftl">

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>Categories</h1>

    <div class="form-card">
      <h2>Manage categories</h2>
      <div class="pill-grid">
        <#list categories as c>
        <div class="cat-pill<#if !c.active> cat-pill-disabled</#if>">
          <span>${c.label}<#if !c.active> (disabled)</#if></span>
          <form method="post" action="/categories/${c.id}/toggle">
            <button type="submit" class="cat-toggle<#if c.active> cat-toggle-on</#if>" aria-label="<#if c.active>Disable<#else>Enable</#if> ${c.label}"></button>
          </form>
        </div>
        </#list>
      </div>

      <form method="post" action="/categories" class="upload-form">
        <input type="text" name="label" placeholder="New category name" required>
        <button type="submit" class="button">Add category</button>
      </form>
    </div>

    <div class="form-card">
      <h2>Rules</h2>
      <#if (rules?size == 0)>
      <p class="empty-state">No rules yet.</p>
      <#else>
      <div class="card-list">
        <#list rules as rule>
        <div class="info-card">
          <form method="post" action="/categories/rules/${rule.id}" class="recategorize-form">
            <select name="matchType">
              <option value="EXACT" <#if rule.matchType == "EXACT">selected</#if>>Exact</option>
              <option value="SUBSTRING" <#if rule.matchType == "SUBSTRING">selected</#if>>Contains</option>
            </select>
            <input type="text" name="pattern" value="${rule.pattern}">
            <select name="category">
              <#list categoryOptions as option>
              <option value="${option.name}" <#if option.name == rule.categoryId>selected</#if>>${option.label}</option>
              </#list>
            </select>
            <button type="submit" class="button button-small button-save">Save</button>
          </form>
          <form method="post" action="/categories/rules/${rule.id}/delete">
            <button type="submit" class="button button-small button-danger">Delete</button>
          </form>
        </div>
        </#list>
      </div>
      </#if>

      <h3>Add rule</h3>
      <form method="post" action="/categories/rules" class="recategorize-form">
        <select name="matchType">
          <option value="EXACT">Exact</option>
          <option value="SUBSTRING">Contains</option>
        </select>
        <input type="text" name="pattern" placeholder="Pattern" required>
        <select name="category" required>
          <#list categoryOptions as option>
          <option value="${option.name}">${option.label}</option>
          </#list>
        </select>
        <label><input type="checkbox" name="rescan"> Apply to existing transactions</label>
        <button type="submit" class="button">Add rule</button>
      </form>
    </div>
    </main>
  </div>
</body>
</html>
