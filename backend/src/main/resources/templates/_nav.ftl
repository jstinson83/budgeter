<div class="nav">
  <a href="/" class="wordmark">budgeter</a>
  <div class="nav-right">
    <#if currentUser??>
    <a href="/transactions" class="nav-link">Transactions</a>
    <a href="/analysis" class="nav-link">Analysis</a>
    <a href="/categories" class="nav-link">Categories</a>
    <span class="nav-user">${currentUser.name}</span>
    <form method="post" action="/logout" class="nav-logout"><button type="submit">Sign out</button></form>
    </#if>
  </div>
</div>
