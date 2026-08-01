<div class="nav">
  <a href="/" class="wordmark">budgeter</a>
  <div class="nav-right">
    <#if currentUser??>
    <span class="nav-user">${currentUser.name}</span>
    <form method="post" action="/logout" class="nav-logout"><button type="submit">Sign out</button></form>
    </#if>
  </div>
</div>
