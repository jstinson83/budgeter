<#-- App chrome: a dark sidebar for desktop, replaced by a top bar + bottom
     tab bar on mobile (see .app-shell's breakpoint in styles.css). Included
     once per page, right after <div class="app-shell">, with `activeSection`
     assigned beforehand to "dashboard" | "finances" | "house" so the current
     section highlights in both. -->

<#macro iconDashboard>
<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></svg>
</#macro>
<#macro iconFinances>
<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 7v10M15 9.5c0-1.4-1.3-2.5-3-2.5s-3 1-3 2.3c0 3 6 1.5 6 4.5 0 1.4-1.3 2.5-3 2.5s-3-1-3-2.5"/></svg>
</#macro>
<#macro iconHouse>
<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M4 11l8-7 8 7"/><path d="M6 10v9a1 1 0 0 0 1 1h10a1 1 0 0 0 1-1v-9"/><path d="M10 20v-6h4v6"/></svg>
</#macro>
<#macro logoMark barColor>
<svg viewBox="0 0 100 100" width="26" height="26" aria-hidden="true">
  <polygon points="50,29.3 67.1,46.4 32.9,46.4" fill="#c9a24b"/>
  <polygon points="27.9,51.1 72.1,51.1 66.4,57.7 33.6,57.7" fill="${barColor}"/>
  <polygon points="22.5,62.3 77.5,62.3 71.3,70.7 28.7,70.7" fill="${barColor}"/>
</svg>
</#macro>

<div class="sidebar">
  <a href="/" class="sidebar-brand">
    <@logoMark barColor="#ede4d2"/>
    <span class="brand-text">Home OS</span>
  </a>
  <#if currentUser??>
  <nav class="sidebar-nav">
    <a href="/" class="sidebar-item<#if (activeSection!"") == "dashboard"> sidebar-item-active</#if>"><@iconDashboard/> Dashboard</a>
    <a href="/analysis" class="sidebar-item<#if (activeSection!"") == "finances"> sidebar-item-active</#if>"><@iconFinances/> Finances</a>
    <a href="/projects" class="sidebar-item<#if (activeSection!"") == "house"> sidebar-item-active</#if>"><@iconHouse/> House</a>
  </nav>
  <div class="sidebar-user">
    <div class="sidebar-avatar">${currentUser.name?substring(0,1)?upper_case}</div>
    <div class="sidebar-user-meta">
      <span class="sidebar-user-name">${currentUser.name}</span>
      <form method="post" action="/logout" class="sidebar-signout"><button type="submit">Sign out</button></form>
    </div>
  </div>
  </#if>
</div>

<div class="topbar">
  <a href="/" class="topbar-brand">
    <@logoMark barColor="#221f19"/>
    <span class="brand-text">Home OS</span>
  </a>
  <#if currentUser??>
  <details class="topbar-account">
    <summary class="topbar-avatar" aria-label="Account menu">${currentUser.name?substring(0,1)?upper_case}</summary>
    <div class="topbar-account-panel">
      <span class="topbar-user-name">${currentUser.name}</span>
      <form method="post" action="/logout"><button type="submit">Sign out</button></form>
    </div>
  </details>
  </#if>
</div>

<#if currentUser??>
<nav class="tabbar">
  <a href="/" class="tab-item<#if (activeSection!"") == "dashboard"> tab-item-active</#if>">
    <span class="tab-icon-wrap"><@iconDashboard/></span>
    <span class="tab-label">Dashboard</span>
  </a>
  <a href="/analysis" class="tab-item<#if (activeSection!"") == "finances"> tab-item-active</#if>">
    <span class="tab-icon-wrap"><@iconFinances/></span>
    <span class="tab-label">Finances</span>
  </a>
  <a href="/projects" class="tab-item<#if (activeSection!"") == "house"> tab-item-active</#if>">
    <span class="tab-icon-wrap"><@iconHouse/></span>
    <span class="tab-label">House</span>
  </a>
</nav>
</#if>
