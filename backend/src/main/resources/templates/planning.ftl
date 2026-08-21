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

    <div class="form-card">
      <h2>Goals</h2>
      <#if (goals?size == 0)>
      <p class="empty-state">No goals yet.</p>
      <#else>
      <div class="card-list">
        <#list goals as goal>
        <div class="info-card">
          <form method="post" action="/planning/goals/${goal.id}" class="recategorize-form">
            <input type="hidden" name="type" value="${goal.typeName}">
            <input type="text" name="name" value="${goal.name}" required>
            <input type="date" name="targetDate" value="${goal.targetDate}" required>
            <#if goal.isRetirement>
            <input type="number" name="annualSpend" value="${goal.annualSpend}" step="0.01" min="0.01" placeholder="Annual spend">
            <input type="number" name="withdrawalRatePercent" value="${goal.withdrawalRatePercent}" step="0.1" min="0.1" placeholder="Withdrawal rate %">
            <#else>
            <input type="number" name="targetAmount" value="${goal.targetAmount}" step="0.01" min="0.01" placeholder="Target amount">
            </#if>
            <button type="submit" class="button button-small button-save">Save</button>
          </form>
          <span class="dashboard-card-note">Target: ${goal.resolvedTargetAmount} by ${goal.targetDate}</span>

          <div class="projection-chart-wrap">
            <svg viewBox="0 0 300 120" class="projection-chart" preserveAspectRatio="none">
              <line x1="6" y1="${goal.chartGoalY}" x2="294" y2="${goal.chartGoalY}" class="projection-chart-goal-line"/>
              <#list goal.chartLines as line>
              <polyline points="${line.points}" class="${line.cssClass}"/>
              </#list>
            </svg>
            <div class="projection-chart-labels">
              <span>${goal.chartMinLabel}</span>
              <span>${goal.chartMaxLabel}</span>
            </div>
            <#if (goal.chartLines?size gt 1)>
            <ul class="projection-chart-legend">
              <#list goal.chartLines as line>
              <li><span class="projection-chart-legend-swatch ${line.cssClass}"></span>${line.label}</li>
              </#list>
            </ul>
            </#if>
          </div>
          <p class="dashboard-card-note">
            Baseline projected: ${goal.projectedFinal} by ${goal.targetDate}
            (${goal.monthlySavingsRate}/mo) &mdash;
            <#if goal.onTrack>
            <span class="transaction-amount-positive">on track</span>
            <#else>
            <span class="transaction-amount-negative">short by ${goal.shortfallOrSurplus}</span>
            </#if>
          </p>
          <#list goal.scenarioOutcomes as outcome>
          <p class="dashboard-card-note">
            ${outcome.name}: ${outcome.projectedFinal} &mdash;
            <#if outcome.onTrack>
            <span class="transaction-amount-positive">on track</span>
            <#else>
            <span class="transaction-amount-negative">off track</span>
            </#if>
            <#if outcome.hasRrspStrategy>
            &mdash; ${outcome.totalRrspRefunds} in RRSP refunds, ${outcome.finalRrspRoomRemaining} room left
            </#if>
          </p>
          </#list>

          <form method="post" action="/planning/goals/${goal.id}/delete">
            <button type="submit" class="button button-small button-danger">Delete</button>
          </form>
        </div>
        </#list>
      </div>
      </#if>

      <h3>Add goal</h3>
      <form method="post" action="/planning/goals" class="recategorize-form" id="goal-form">
        <input type="text" name="name" placeholder="Goal name" required>
        <div class="segmented-control" role="radiogroup" aria-label="Goal type">
          <input type="radio" name="type" value="NET_WORTH_TARGET" id="goal-type-networth" checked>
          <label for="goal-type-networth">Net worth target</label>
          <input type="radio" name="type" value="RETIREMENT" id="goal-type-retirement">
          <label for="goal-type-retirement">Retirement</label>
        </div>
        <input type="date" name="targetDate" required>
        <div id="goal-networth-fields">
          <input type="number" name="targetAmount" placeholder="Target amount" step="0.01" min="0.01">
        </div>
        <div id="goal-retirement-fields" hidden>
          <input type="number" name="annualSpend" placeholder="Annual spend in retirement" step="0.01" min="0.01">
          <input type="number" name="withdrawalRatePercent" placeholder="Withdrawal rate % (default 4)" step="0.1" min="0.1">
        </div>
        <button type="submit" class="button">Add goal</button>
      </form>
    </div>

    <div class="form-card">
      <h2>Scenarios</h2>
      <p class="dashboard-card-note">Each scenario adds its own line to every goal's chart above, alongside the always-shown baseline (which always assumes today's real savings rate, no growth, and no changes).</p>
      <p class="dashboard-card-note">
        <strong>Growth rate</strong> compounds today's existing investments plus whatever share of each month's new savings you mark as <strong>% of new savings invested</strong> - the rest sits as cash and never grows. <strong>Recreational spend adjustment</strong> redirects $/mo from spending into savings (negative = spend more instead). <strong>Salary change</strong> is optional, one-time, and takes effect from its date onward - a negative amount models a deliberate pay cut (e.g. switching to a less demanding role), not just a raise.
      </p>
      <p class="dashboard-card-note">
        <strong>RRSP strategy</strong> (optional) models diverting part of your savings into an RRSP: it's capped by the contribution room you enter, and once a year it triggers a refund (contributions that year &times; your marginal tax rate), which you can either keep as cash or reinvest. Your marginal rate and RRSP room are numbers you look up and enter yourself - this app never fetches or guesses tax figures.
      </p>
      <#if (scenarios?size == 0)>
      <p class="empty-state">No scenarios yet.</p>
      <#else>
      <div class="card-list">
        <#list scenarios as scenario>
        <div class="info-card">
          <form method="post" action="/planning/scenarios/${scenario.id}">
            <div class="form-row">
              <div class="form-field form-field-wide">
                <span class="form-field-label">Name</span>
                <input type="text" name="name" value="${scenario.name}" required>
              </div>
              <div class="form-field">
                <span class="form-field-label">Growth rate (%/yr)</span>
                <input type="number" name="annualMarketGrowthRatePercent" value="${scenario.annualMarketGrowthRatePercent}" step="0.1">
              </div>
              <div class="form-field">
                <span class="form-field-label">% of new savings invested</span>
                <input type="number" name="investedSavingsFractionPercent" value="${scenario.investedSavingsFractionPercent}" step="1" min="0" max="100">
              </div>
              <div class="form-field">
                <span class="form-field-label">Recreational spend adj. ($/mo)</span>
                <input type="number" name="recreationalSpendAdjustment" value="${scenario.recreationalSpendAdjustment}" step="0.01">
              </div>
            </div>
            <p class="field-label">Salary change (optional)</p>
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Date</span>
                <input type="date" name="salaryChangeDate" value="${scenario.salaryChangeDate}">
              </div>
              <div class="form-field">
                <span class="form-field-label">Amount ($/mo)</span>
                <input type="number" name="salaryChangeMonthlyDelta" value="${scenario.salaryChangeMonthlyDelta}" step="0.01">
              </div>
            </div>
            <p class="field-label">RRSP strategy (optional)</p>
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Monthly contribution ($)</span>
                <input type="number" name="rrspMonthlyContribution" value="${scenario.rrspMonthlyContribution}" step="0.01" min="0">
              </div>
              <div class="form-field">
                <span class="form-field-label">Your marginal tax rate (%)</span>
                <input type="number" name="rrspMarginalTaxRatePercent" value="${scenario.rrspMarginalTaxRatePercent}" step="0.1" min="0" max="100">
              </div>
              <div class="form-field">
                <span class="form-field-label">RRSP room remaining ($)</span>
                <input type="number" name="rrspRoomRemaining" value="${scenario.rrspRoomRemaining}" step="0.01" min="0">
              </div>
              <div class="form-field">
                <span class="form-field-label">Refund handling</span>
                <label><input type="checkbox" name="rrspReinvestRefund" <#if scenario.rrspReinvestRefund>checked</#if>> Reinvest into investments</label>
              </div>
            </div>
            <button type="submit" class="button button-small button-save">Save</button>
          </form>
          <form method="post" action="/planning/scenarios/${scenario.id}/delete">
            <button type="submit" class="button button-small button-danger">Delete</button>
          </form>
        </div>
        </#list>
      </div>
      </#if>

      <h3>Add scenario</h3>
      <form method="post" action="/planning/scenarios" id="scenario-form">
        <div class="form-row">
          <div class="form-field form-field-wide">
            <span class="form-field-label">Name</span>
            <input type="text" name="name" placeholder="e.g. Aggressive growth" required>
          </div>
          <div class="form-field">
            <span class="form-field-label">Growth rate preset</span>
            <select id="scenario-growth-preset">
              <#list growthPresets as preset>
              <option value="${preset.annualRatePercent}"<#if preset.name == "MODERATE"> selected</#if>>${preset.label}</option>
              </#list>
              <option value="">Custom</option>
            </select>
          </div>
          <div class="form-field">
            <span class="form-field-label">Growth rate (%/yr)</span>
            <input type="number" name="annualMarketGrowthRatePercent" id="scenario-growth-rate" value="7.0" step="0.1" required>
          </div>
          <div class="form-field">
            <span class="form-field-label">% of new savings invested</span>
            <input type="number" name="investedSavingsFractionPercent" value="100" step="1" min="0" max="100" required>
          </div>
          <div class="form-field">
            <span class="form-field-label">Recreational spend adj. ($/mo)</span>
            <input type="number" name="recreationalSpendAdjustment" value="0" step="0.01" required>
          </div>
        </div>
        <p class="field-label">Salary change (optional)</p>
        <div class="form-row">
          <div class="form-field">
            <span class="form-field-label">Date</span>
            <input type="date" name="salaryChangeDate">
          </div>
          <div class="form-field">
            <span class="form-field-label">Amount ($/mo)</span>
            <input type="number" name="salaryChangeMonthlyDelta" step="0.01">
          </div>
        </div>
        <p class="field-label">RRSP strategy (optional)</p>
        <div class="form-row">
          <div class="form-field">
            <span class="form-field-label">Monthly contribution ($)</span>
            <input type="number" name="rrspMonthlyContribution" step="0.01" min="0">
          </div>
          <div class="form-field">
            <span class="form-field-label">Your marginal tax rate (%)</span>
            <input type="number" name="rrspMarginalTaxRatePercent" step="0.1" min="0" max="100">
          </div>
          <div class="form-field">
            <span class="form-field-label">RRSP room remaining ($)</span>
            <input type="number" name="rrspRoomRemaining" step="0.01" min="0">
          </div>
          <div class="form-field">
            <span class="form-field-label">Refund handling</span>
            <label><input type="checkbox" name="rrspReinvestRefund"> Reinvest into investments</label>
          </div>
        </div>
        <button type="submit" class="button">Add scenario</button>
      </form>
    </div>
    </main>
  </div>

  <script>
    // No framework in this app - see analysis.ftl/CLAUDE.md. Toggles which
    // field group the add-goal form shows based on the selected type; the
    // fields that stay hidden are also the ones with no "required"
    // attribute, so submitting never blocks on a field the user can't see.
    // The server (NetWorthRoutes.kt's parseGoalForm) is what actually
    // enforces "the right fields for the type" - this is convenience only.
    (function () {
      var retirementRadio = document.getElementById('goal-type-retirement');
      var netWorthFields = document.getElementById('goal-networth-fields');
      var retirementFields = document.getElementById('goal-retirement-fields');
      document.querySelectorAll('#goal-form input[name="type"]').forEach(function (radio) {
        radio.addEventListener('change', function () {
          var isRetirement = retirementRadio.checked;
          netWorthFields.hidden = isRetirement;
          retirementFields.hidden = !isRetirement;
        });
      });
    })();

    // Picking a preset fills in the actual submitted rate field; picking
    // "Custom" just leaves whatever's already typed there alone. The
    // number input (not the select) is what's actually named/submitted,
    // so a hand-typed rate works identically to a preset once saved - see
    // ScenarioStore.kt's MarketGrowthPreset doc comment.
    (function () {
      var preset = document.getElementById('scenario-growth-preset');
      var rateInput = document.getElementById('scenario-growth-rate');
      if (!preset || !rateInput) return;
      preset.addEventListener('change', function () {
        if (preset.value !== '') rateInput.value = preset.value;
      });
    })();
  </script>
</body>
</html>
