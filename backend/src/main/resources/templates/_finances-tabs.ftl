<#-- Shared tab bar for the "Finances" nav group (Analysis/Transactions/
     Categories). Included by each of those three pages with `activeTab`
     assigned beforehand (see _nav.ftl for the same include-shares-model
     pattern this relies on). -->
<div class="tab-bar">
  <a href="/analysis" class="tab-link<#if (activeTab!"") == "analysis"> tab-link-active</#if>">Analysis</a>
  <a href="/transactions" class="tab-link<#if (activeTab!"") == "transactions"> tab-link-active</#if>">Transactions</a>
  <a href="/categories" class="tab-link<#if (activeTab!"") == "categories"> tab-link-active</#if>">Categories</a>
</div>
