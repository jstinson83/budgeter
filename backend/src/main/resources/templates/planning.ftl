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

    <p class="dashboard-card-note">
      <a href="/planning/export" class="button button-small">Download verification export (.zip)</a>
      &mdash; monthly category totals, net worth, goals, scenarios, and projections as plain CSVs, for sharing with someone (accountant, lender) you don't want to give app access to. No individual transaction descriptions are included.
    </p>

    <div class="month-summary">
      <span class="month-summary-label">Net worth</span>
      <span class="transaction-amount month-summary-amount <#if netWorth?number gte 0>transaction-amount-positive<#else>transaction-amount-negative</#if>">${netWorth}</span>
    </div>

    <div class="form-card" id="wealth-card">
      <h2>Wealth over time</h2>
      <p class="dashboard-card-note">
        The always-shown baseline assumes today's real savings rate, no growth, and no changes; each scenario below adds its own line, projected ${wealthChartHorizonYears} years out. Use the chips to show or hide a line, and scroll the chart to see further into the projection.
      </p>
      <div class="chip-row">
        <label class="chip">
          <input type="checkbox" id="scenario-chip-baseline" checked>
          <span class="chip-dot"></span>Baseline
        </label>
        <#list scenarios as scenario>
        <label class="chip">
          <input type="checkbox" id="scenario-chip-scenario-${scenario.chartColorIndex}" checked>
          <span class="chip-dot chip-dot-scenario-${scenario.chartColorIndex}"></span>${scenario.name}
        </label>
        </#list>
      </div>
      <div class="projection-chart-wrap">
        <div class="wealth-chart-scroll">
          <svg viewBox="0 0 ${wealthChart.widthPx} 120" width="${wealthChart.widthPx}" height="120" class="wealth-chart">
            <#list wealthChart.lines as line>
            <polyline points="${line.points}" class="${line.cssClass}"/>
            </#list>
          </svg>
        </div>
        <div class="projection-chart-labels">
          <span>${wealthChart.minLabel}</span>
          <span>${wealthChart.maxLabel}</span>
        </div>
      </div>
    </div>

    <div class="form-card">
      <h2>Scenarios</h2>
      <p class="dashboard-card-note">Each scenario adds its own line to every goal's chart above, alongside the always-shown baseline (which always assumes today's real savings rate, no growth, and no changes). Use the chips above the goals to show or hide a line.</p>
      <p class="dashboard-card-note">
        <strong>Growth rate</strong> compounds today's existing investments plus whatever share of each month's new savings you mark as <strong>% of new savings invested</strong> - the rest sits as cash and never grows. <strong>Recreational spend adjustment</strong> redirects $/mo from spending into savings (negative = spend more instead). <strong>Salary change</strong> is optional, one-time, and takes effect from its date onward - a negative amount models a deliberate pay cut (e.g. switching to a less demanding role), not just a raise.
      </p>
      <p class="dashboard-card-note">
        <strong>RRSP strategy</strong> (optional) models diverting part of your savings into an RRSP: it's capped by the contribution room you enter, and once a year it triggers a refund (contributions that year &times; your marginal tax rate), which you can either keep as cash or reinvest. Your marginal rate and RRSP room are numbers you look up and enter yourself - this app never fetches or guesses tax figures.
      </p>
      <p class="dashboard-card-note">
        <strong>RRSP room accrual</strong> (optional, independent of the strategy above) grows your remaining room each year instead of leaving it fixed: 18% of whatever category you tag as income, summed over the trailing 12 months. Tag your salary transactions with a category first (on <a href="/categories">Categories</a>), then pick it here. The optional annual cap mirrors the marginal tax rate above - CRA sets one, but it changes yearly, so it's a number you look up and enter rather than one this app assumes.
      </p>
      <#if (scenarios?size == 0)>
      <p class="empty-state">No scenarios yet.</p>
      <#else>
      <div class="card-list">
        <#list scenarios as scenario>
        <details class="acc">
          <summary>
            <span class="acc-summary-head">
              <span class="acc-summary-name">${scenario.name}</span>
              <span class="acc-summary-tags">
                <span class="tag">${scenario.annualMarketGrowthRatePercent}%/yr</span>
                <span class="tag">${scenario.investedSavingsFractionPercent}% invested</span>
                <#if scenario.recreationalSpendAdjustment != "0.00"><span class="tag">${scenario.recreationalSpendAdjustment}/mo spend adj.</span></#if>
                <#if scenario.hasSalaryChange><span class="tag">salary change ${scenario.salaryChangeDate}</span></#if>
                <#if scenario.hasRrspStrategy><span class="tag">${scenario.rrspMonthlyContribution}/mo RRSP, ${scenario.rrspRoomRemaining} room left</span></#if>
                <#if scenario.rrspIncomeCategoryId != ""><span class="tag">room accrual: ${scenario.rrspIncomeCategoryLabel}</span></#if>
              </span>
            </span>
          </summary>
          <div class="acc-body">
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
            <p class="field-label">RRSP room accrual (optional)</p>
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Income category</span>
                <select name="rrspIncomeCategoryId">
                  <option value="">None</option>
                  <#list categoryOptions as option>
                  <option value="${option.id}" <#if option.id == scenario.rrspIncomeCategoryId>selected</#if>>${option.label}</option>
                  </#list>
                </select>
              </div>
              <div class="form-field">
                <span class="form-field-label">Annual accrual cap ($, optional)</span>
                <input type="number" name="rrspAnnualRoomAccrualCap" value="${scenario.rrspAnnualRoomAccrualCap}" step="0.01" min="0">
              </div>
            </div>
            <button type="submit" class="button button-small button-save">Save</button>
          </form>
          <form method="post" action="/planning/scenarios/${scenario.id}/delete">
            <button type="submit" class="button button-small button-danger">Delete</button>
          </form>
          </div>
        </details>
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
        <p class="field-label">RRSP room accrual (optional)</p>
        <div class="form-row">
          <div class="form-field">
            <span class="form-field-label">Income category</span>
            <select name="rrspIncomeCategoryId">
              <option value="">None</option>
              <#list categoryOptions as option>
              <option value="${option.id}">${option.label}</option>
              </#list>
            </select>
          </div>
          <div class="form-field">
            <span class="form-field-label">Annual accrual cap ($, optional)</span>
            <input type="number" name="rrspAnnualRoomAccrualCap" step="0.01" min="0">
          </div>
        </div>
        <button type="submit" class="button">Add scenario</button>
      </form>
    </div>

    <div class="form-card">
      <h2>Net worth breakdown <span class="category-count">${totalAssets} in assets, ${totalLiabilities} in liabilities</span></h2>
      <#if (assets?size == 0 && liabilities?size == 0)>
      <p class="empty-state">No assets or liabilities yet.</p>
      <#else>
      <details class="acc">
        <summary>${assets?size} asset<#if assets?size != 1>s</#if> &middot; ${liabilities?size} liabilit<#if liabilities?size != 1>ies<#else>y</#if></summary>
        <div class="acc-body">
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
                <input type="number" name="annualAppreciationRatePercent" value="${entry.annualAppreciationRatePercent}" step="0.1" min="0" placeholder="Appreciation %/yr (real estate)">
                <button type="submit" class="button button-small button-save">Save</button>
              </form>
              <form method="post" action="/planning/entries/${entry.id}/delete">
                <button type="submit" class="button button-small button-danger">Delete</button>
              </form>
            </div>
            </#list>
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
                <input type="number" name="annualInterestRatePercent" value="${entry.annualInterestRatePercent}" step="0.01" min="0" placeholder="Interest rate %/yr (mortgage)">
                <select name="mortgagePaymentCategoryId">
                  <option value="">Payment category (mortgage)</option>
                  <#list categoryOptions as option>
                  <option value="${option.id}" <#if option.id == entry.mortgagePaymentCategoryId>selected</#if>>${option.label}</option>
                  </#list>
                </select>
                <button type="submit" class="button button-small button-save">Save</button>
              </form>
              <form method="post" action="/planning/entries/${entry.id}/delete">
                <button type="submit" class="button button-small button-danger">Delete</button>
              </form>
            </div>
            </#list>
          </div>
        </div>
      </details>
      </#if>

      <div class="two-col-forms">
        <div>
          <h3>Add asset</h3>
          <form method="post" action="/planning/entries" class="recategorize-form">
            <input type="text" name="label" placeholder="e.g. Brokerage account" required>
            <select name="type" required>
              <#list assetTypeOptions as option>
              <option value="${option.name}">${option.label}</option>
              </#list>
            </select>
            <input type="number" name="value" placeholder="Value" step="0.01" min="0" required>
            <input type="number" name="annualAppreciationRatePercent" placeholder="Appreciation %/yr (real estate)" step="0.1" min="0">
            <button type="submit" class="button">Add asset</button>
          </form>
        </div>
        <div>
          <h3>Add liability</h3>
          <form method="post" action="/planning/entries" class="recategorize-form">
            <input type="text" name="label" placeholder="e.g. Mortgage" required>
            <select name="type" required>
              <#list liabilityTypeOptions as option>
              <option value="${option.name}">${option.label}</option>
              </#list>
            </select>
            <input type="number" name="value" placeholder="Amount owed" step="0.01" min="0" required>
            <input type="number" name="annualInterestRatePercent" placeholder="Interest rate %/yr (mortgage)" step="0.01" min="0">
            <select name="mortgagePaymentCategoryId">
              <option value="">Payment category (mortgage)</option>
              <#list categoryOptions as option>
              <option value="${option.id}" <#if option.id == defaultMortgagePaymentCategoryId>selected</#if>>${option.label}</option>
              </#list>
            </select>
            <button type="submit" class="button">Add liability</button>
          </form>
        </div>
      </div>
    </div>
    </main>
  </div>

  <script>
    // No framework in this app - see analysis.ftl/CLAUDE.md.

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
