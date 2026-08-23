<#-- Shared tab bar for the "Finances" nav group (Analysis/Transactions/
     Categories/Planning). Included by each of those pages with `activeTab`
     assigned beforehand (see _nav.ftl for the same include-shares-model
     pattern this relies on).

     Transactions/Categories are administrative (import a CSV, manage
     rules) rather than something checked often, so they're tucked behind a
     "More" dropdown instead of sitting as equal-weight tabs next to
     Analysis/Planning - crowded the row on mobile in particular. Reuses the
     same zero-JS <details>/<summary> dropdown pattern as _nav.ftl's
     topbar-account menu. -->
<#assign moreActive = (activeTab!"") == "transactions" || (activeTab!"") == "categories">
<div class="tab-bar">
  <a href="/analysis" class="tab-link<#if (activeTab!"") == "analysis"> tab-link-active</#if>">Analysis</a>
  <a href="/planning" class="tab-link<#if (activeTab!"") == "planning"> tab-link-active</#if>">Planning</a>
  <details class="tab-more">
    <summary class="tab-link tab-more-summary<#if moreActive> tab-link-active</#if>">
      More
      <svg class="tab-more-chevron" viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 9 12 15 18 9"/></svg>
    </summary>
    <div class="tab-more-panel">
      <a href="/transactions" class="tab-more-link<#if (activeTab!"") == "transactions"> tab-more-link-active</#if>">Transactions</a>
      <a href="/categories" class="tab-more-link<#if (activeTab!"") == "categories"> tab-more-link-active</#if>">Categories</a>
    </div>
  </details>
</div>
