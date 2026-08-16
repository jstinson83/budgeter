<div class="nav">
  <a href="/" class="wordmark"><img src="/favicon.svg" class="logo-mark" alt=""> Home OS</a>
  <div class="nav-right">
    <#if currentUser??>
    <div class="nav-links">
      <a href="/analysis" class="nav-link">Analysis</a>
      <a href="/transactions" class="nav-link">Transactions</a>
      <a href="/categories" class="nav-link">Categories</a>
      <a href="/house" class="nav-link">House</a>
      <a href="/projects" class="nav-link">Projects</a>
    </div>
    <details class="nav-menu">
      <summary class="nav-menu-toggle" aria-label="Menu">&#9776;</summary>
      <div class="nav-menu-panel">
        <a href="/analysis" class="nav-link">Analysis</a>
        <a href="/transactions" class="nav-link">Transactions</a>
        <a href="/categories" class="nav-link">Categories</a>
        <a href="/house" class="nav-link">House</a>
        <a href="/projects" class="nav-link">Projects</a>
      </div>
    </details>
    <details class="account-menu">
      <summary class="account-toggle" aria-label="Account menu">${currentUser.name?substring(0,1)?upper_case}</summary>
      <div class="account-menu-panel">
        <span class="nav-user">${currentUser.name}</span>
        <form method="post" action="/logout" class="nav-logout"><button type="submit">Sign out</button></form>
      </div>
    </details>
    </#if>
  </div>
</div>
