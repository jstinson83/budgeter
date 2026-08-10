<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Categories · Budgeter</title>
<link rel="stylesheet" href="/styles.css">
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

    <h1>Categories</h1>
    <div class="transaction-list">
      <#list categories as c>
      <div class="transaction-row">
        <span class="transaction-description">${c.label}<#if !c.active> (disabled)</#if></span>
        <form method="post" action="/categories/${c.id}/toggle">
          <button type="submit" class="button button-small"><#if c.active>Disable<#else>Enable</#if></button>
        </form>
      </div>
      </#list>
    </div>

    <form method="post" action="/categories" class="upload-form">
      <input type="text" name="label" placeholder="New category name" required>
      <button type="submit" class="button">Add category</button>
    </form>

    <h1>Rules</h1>
    <#if (rules?size == 0)>
    <p class="empty-state">No rules yet.</p>
    <#else>
    <div class="transaction-list">
      <#list rules as rule>
      <div class="transaction-item">
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
          <button type="submit" class="button button-small">Save</button>
        </form>
        <form method="post" action="/categories/rules/${rule.id}/delete">
          <button type="submit" class="button button-small button-danger">Delete</button>
        </form>
      </div>
      </#list>
    </div>
    </#if>

    <h2>Add rule</h2>
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
      <button type="submit" class="button">Add rule</button>
    </form>
  </div>
</body>
</html>
