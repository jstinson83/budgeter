<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Planning · Home OS</title>
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
    <#assign activeTab = "planning">
    <#include "_finances-tabs.ftl">

    <#if message??>
    <p class="banner-success">${message}</p>
    </#if>
    <#if error??>
    <p class="banner-error">${error}</p>
    </#if>

    <h1>Planning</h1>

    <div class="month-summary">
      <span class="month-summary-label">Net worth</span>
      <span class="transaction-amount month-summary-amount <#if netWorth?number gte 0>transaction-amount-positive<#else>transaction-amount-negative</#if>">${netWorth}</span>
    </div>

    <div class="form-card">
      <h2>Assets <span class="category-count">${totalAssets}</span></h2>
      <#if (assets?size == 0)>
      <p class="empty-state">No assets yet.</p>
      <#else>
      <div class="card-list">
        <#list assets as entry>
        <div class="info-card">
          <form method="post" action="/planning/entries/${entry.id}" class="recategorize-form">
            <input type="text" name="label" value="${entry.label}" required>
            <select name="type">
              <#list assetTypeOptions as option>
              <option value="${option.name}" <#if option.name == entry.typeName>selected</#if>>${option.label}</option>
              </#list>
            </select>
            <input type="number" name="value" value="${entry.value}" step="0.01" min="0" required>
            <button type="submit" class="button button-small button-save">Save</button>
          </form>
          <form method="post" action="/planning/entries/${entry.id}/delete">
            <button type="submit" class="button button-small button-danger">Delete</button>
          </form>
        </div>
        </#list>
      </div>
      </#if>

      <h3>Add asset</h3>
      <form method="post" action="/planning/entries" class="recategorize-form">
        <input type="text" name="label" placeholder="e.g. Brokerage account" required>
        <select name="type" required>
          <#list assetTypeOptions as option>
          <option value="${option.name}">${option.label}</option>
          </#list>
        </select>
        <input type="number" name="value" placeholder="Value" step="0.01" min="0" required>
        <button type="submit" class="button">Add asset</button>
      </form>
    </div>

    <div class="form-card">
      <h2>Liabilities <span class="category-count">${totalLiabilities}</span></h2>
      <#if (liabilities?size == 0)>
      <p class="empty-state">No liabilities yet.</p>
      <#else>
      <div class="card-list">
        <#list liabilities as entry>
        <div class="info-card">
          <form method="post" action="/planning/entries/${entry.id}" class="recategorize-form">
            <input type="text" name="label" value="${entry.label}" required>
            <select name="type">
              <#list liabilityTypeOptions as option>
              <option value="${option.name}" <#if option.name == entry.typeName>selected</#if>>${option.label}</option>
              </#list>
            </select>
            <input type="number" name="value" value="${entry.value}" step="0.01" min="0" required>
            <button type="submit" class="button button-small button-save">Save</button>
          </form>
          <form method="post" action="/planning/entries/${entry.id}/delete">
            <button type="submit" class="button button-small button-danger">Delete</button>
          </form>
        </div>
        </#list>
      </div>
      </#if>

      <h3>Add liability</h3>
      <form method="post" action="/planning/entries" class="recategorize-form">
        <input type="text" name="label" placeholder="e.g. Mortgage" required>
        <select name="type" required>
          <#list liabilityTypeOptions as option>
          <option value="${option.name}">${option.label}</option>
          </#list>
        </select>
        <input type="number" name="value" placeholder="Amount owed" step="0.01" min="0" required>
        <button type="submit" class="button">Add liability</button>
      </form>
    </div>
    </main>
  </div>
</body>
</html>
