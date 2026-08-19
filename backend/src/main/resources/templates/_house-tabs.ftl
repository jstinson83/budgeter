<#-- Shared tab bar for the "House" nav group (Projects/House documents/
     House facts). Included by each of those three pages with `activeTab`
     assigned beforehand - same pattern as _finances-tabs.ftl. -->
<div class="tab-bar">
  <a href="/projects" class="tab-link<#if (activeTab!"") == "projects"> tab-link-active</#if>">Projects</a>
  <a href="/house" class="tab-link<#if (activeTab!"") == "house"> tab-link-active</#if>">Documents</a>
  <a href="/house/facts" class="tab-link<#if (activeTab!"") == "facts"> tab-link-active</#if>">Facts by category</a>
</div>
