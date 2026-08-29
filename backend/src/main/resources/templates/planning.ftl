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
<link rel="manifest" href="/manifest.webmanifest">
<meta name="theme-color" content="#241f16">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="Home OS">
<script src="/register-sw.js" defer></script>
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

    <a href="/planning/export" class="export-link">Download verification export (.zip)</a>
    <p class="dashboard-card-note">
      Monthly category totals, net worth, goals, scenarios, and projections as plain CSVs, for sharing with someone (accountant, lender) you don't want to give app access to. No individual transaction descriptions are included.
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
      <div class="wealth-chart-frame">
        <div class="wealth-chart-yaxis">
          <#list wealthChart.gridLines as grid>
          <span>${grid.label}</span>
          </#list>
        </div>
        <div class="wealth-chart-scroll">
          <svg viewBox="0 0 ${wealthChart.widthPx} 120" preserveAspectRatio="none" style="min-width:${wealthChart.widthPx}px" class="wealth-chart">
            <#list wealthChart.gridLines as grid>
            <line x1="0" x2="${wealthChart.widthPx}" y1="${grid.y}" y2="${grid.y}" class="wealth-chart-grid-line"/>
            </#list>
            <#list wealthChart.lines as line>
            <polyline points="${line.points}" class="${line.cssClass}"/>
            </#list>
          </svg>
          <div class="wealth-chart-xaxis" style="min-width:${wealthChart.widthPx}px">
            <#list wealthChart.xAxisTicks as tick>
            <span style="left:${tick.leftPercent}%">${tick.label}</span>
            </#list>
          </div>
        </div>
      </div>
    </div>

<#-- What every scenario below actually starts from, read straight out of
     transaction history rather than typed in anywhere - previously
     invisible (baselineMonthlySavingsRate/the income figure behind RRSP
     room accrual were both computed but never shown), which made a
     scenario's numbers hard to sanity-check against reality. See
     NetWorthPage.kt's yourNumbersCardModel and HouseholdSettingsStore.kt
     for where these come from; incomeCategoryId is a single household-wide
     setting saved here, read by both this card and every scenario's RRSP
     room accrual facet below. -->
    <div class="form-card">
      <h2>Your Numbers</h2>
      <p class="dashboard-card-note">
        What every scenario below actually starts from, read straight out of your transaction history &mdash; not typed in anywhere. If a scenario looks off, this is the first thing to check.
      </p>

      <div class="month-summary" style="margin-bottom:0.75rem;">
        <span class="month-summary-label">Income <span class="month-summary-note"><#if yourNumbers.incomeCategoryId != "">tagged &ldquo;${yourNumbers.incomeCategoryLabel}&rdquo; &middot; trailing 3 months<#else>no category set yet</#if></span></span>
        <#if yourNumbers.hasRecentIncome>
        <span class="transaction-amount month-summary-amount ${yourNumbers.recentIncomeClass}">${yourNumbers.recentIncome}/mo</span>
        <#else>
        <span class="month-summary-amount month-summary-note">Not enough tagged transactions yet</span>
        </#if>
      </div>
      <div class="month-summary">
        <span class="month-summary-label">Savings rate <span class="month-summary-note">income minus spending &middot; trailing 3 months</span></span>
        <span class="transaction-amount month-summary-amount ${yourNumbers.savingsRateClass}">${yourNumbers.savingsRate}/mo</span>
      </div>

      <#if !yourNumbers.hasRecentIncome>
      <p class="dashboard-card-note" style="margin-top:0.75rem;">
        <#if yourNumbers.incomeCategoryId == "">
        No income category set yet - pick one below, then tag your paycheck deposits with it on <a href="/categories">Categories</a>.
        <#else>
        No transactions tagged &ldquo;${yourNumbers.incomeCategoryLabel}&rdquo; in the last 3 months - go tag some on <a href="/categories">Categories</a>, or pick a different category below.
        </#if>
      </p>
      </#if>

      <div class="facet-block" style="margin-top:1.1rem;">
        <div class="facet-block-header">
          <span class="facet-block-title-group">
            <span class="facet-block-title">Which category is Income?</span>
            <button type="button" class="facet-help-btn" data-open-help="help-your-numbers-income" aria-label="What does this control?">?</button>
          </span>
        </div>
        <form method="post" action="/planning/household-settings">
          <fieldset class="facet-fieldset">
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Category</span>
                <select name="incomeCategoryId">
                  <option value="">None</option>
                  <#list categoryOptions as option>
                  <option value="${option.id}" <#if option.id == yourNumbers.incomeCategoryId>selected</#if>>${option.label}</option>
                  </#list>
                </select>
              </div>
              <div class="form-field" style="justify-content:flex-end; display:flex;">
                <button type="submit" class="button button-secondary">Save</button>
              </div>
            </div>
          </fieldset>
        </form>
        <p class="dashboard-card-note" style="margin-top:0.5rem;">
          Used here, and by any scenario below with RRSP room accrual turned on - one setting instead of picking it per scenario.
        </p>
      </div>

      <dialog class="recategorize-dialog" id="help-your-numbers-income">
        <h3 class="recategorize-dialog-title">Which category is Income?</h3>
        <p class="recategorize-dialog-description">Tells this card (and RRSP room accrual, if any scenario below has it turned on) which Category your paycheck deposits are tagged with, so it can read your real income straight out of your transaction history.</p>
        <p class="recategorize-dialog-description">Tag your salary transactions with a category first (on <a href="/categories">Categories</a>), then pick it here. One setting for the whole household, not per scenario.</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>
    </div>

<#-- Scenario form facets - each optional group (everything but the core
     name/growth-rate/invested-% fields) starts collapsed behind a "+ Add"
     chip unless already configured, matching how the same optional groups
     are already validated server-side in NetWorthRoutes.kt's
     parseScenarioForm (a group missing from the submitted form is already
     read as "not set", same as it being present-but-blank). Removing a
     facet disables its <fieldset> rather than just hiding it, since a
     disabled control is excluded from form submission - hidden alone
     wouldn't stop a removed facet's old values from still being saved.
     Recreational spend adjustment is the one exception with no natural
     "unset" state (it's a plain required Double, defaulting to 0) - its
     facet open/closed state is keyed off value != 0 instead of a hasX
     flag, same convention the summary tags below already used before this
     redesign. -->
    <div class="form-card">
      <h2>Scenarios</h2>
      <p class="dashboard-card-note">Each scenario adds its own line to every goal's chart above, alongside the always-shown baseline (which always assumes today's real savings rate, no growth, and no changes). Use the chips above the goals to show or hide a line. Click a "?" on any section below for what it does.</p>
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
                <#if scenario.hasSalaryChange>
                <span class="tag">
                  salary change ${scenario.salaryChangeDate}<#if scenario.salaryChangeEndDate != ""> &rarr; ${scenario.salaryChangeEndDate}</#if>
                </span>
                </#if>
                <#if scenario.hasRrspStrategy><span class="tag">${scenario.rrspMonthlyContribution}/mo RRSP, ${scenario.rrspRoomRemaining} room left</span></#if>
                <#if scenario.rrspAccrueRoomFromIncome><span class="tag">room accrual</span></#if>
              </span>
            </span>
          </summary>
          <div class="acc-body">
          <form method="post" action="/planning/scenarios/${scenario.id}">
          <div class="facet-scope" data-facet-scope>
            <div class="facet-core">
              <div class="facet-core-header">
                <span class="facet-block-title-group">
                  <span class="facet-block-title">Core assumptions</span>
                  <button type="button" class="facet-help-btn" data-open-help="help-core" aria-label="What do these mean?">?</button>
                </span>
              </div>
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
              </div>
            </div>

            <div class="facet-block" data-facet="recreational"<#if scenario.recreationalSpendAdjustment == "0.00"> hidden</#if>>
              <div class="facet-block-header">
                <span class="facet-block-title-group">
                  <span class="facet-block-title">Recreational spend adjustment</span>
                  <button type="button" class="facet-help-btn" data-open-help="help-recreational" aria-label="What does this adjust?">?</button>
                </span>
                <button type="button" class="facet-remove" data-remove-facet="recreational">Remove</button>
              </div>
              <fieldset class="facet-fieldset"<#if scenario.recreationalSpendAdjustment == "0.00"> disabled</#if>>
                <div class="form-row">
                  <div class="form-field">
                    <span class="form-field-label">Adjustment ($/mo)</span>
                    <input type="number" name="recreationalSpendAdjustment" value="${scenario.recreationalSpendAdjustment}" step="0.01">
                  </div>
                </div>
              </fieldset>
            </div>

            <div class="facet-block" data-facet="salary"<#if !scenario.hasSalaryChange> hidden</#if>>
              <div class="facet-block-header">
                <span class="facet-block-title-group">
                  <span class="facet-block-title">Salary change</span>
                  <button type="button" class="facet-help-btn" data-open-help="help-salary" aria-label="What does Salary change do?">?</button>
                </span>
                <button type="button" class="facet-remove" data-remove-facet="salary">Remove</button>
              </div>
              <fieldset class="facet-fieldset"<#if !scenario.hasSalaryChange> disabled</#if>>
                <div class="form-row">
                  <div class="form-field">
                    <span class="form-field-label">Date</span>
                    <input type="date" name="salaryChangeDate" value="${scenario.salaryChangeDate}">
                  </div>
                  <div class="form-field">
                    <span class="form-field-label">Amount ($/mo)</span>
                    <input type="number" name="salaryChangeMonthlyDelta" value="${scenario.salaryChangeMonthlyDelta}" step="0.01">
                  </div>
                  <div class="form-field">
                    <span class="form-field-label">Reverts on (optional)</span>
                    <input type="date" name="salaryChangeEndDate" value="${scenario.salaryChangeEndDate}">
                  </div>
                </div>
                <div class="facet-core-header" style="margin-top:0.9rem;">
                  <span class="facet-block-title-group">
                    <span class="field-label" style="margin:0;">While in effect, override RRSP strategy's numbers (optional)</span>
                    <button type="button" class="facet-help-btn" data-open-help="help-salary-override" aria-label="What does this override?">?</button>
                  </span>
                </div>
                <p class="override-hint" data-override-hint="rrsp-strategy"<#if scenario.hasRrspStrategy> hidden</#if>>Add RRSP strategy to enable these.</p>
                <fieldset class="facet-fieldset" data-override-fields="rrsp-strategy"<#if !scenario.hasRrspStrategy> disabled</#if>>
                  <div class="form-row">
                    <div class="form-field">
                      <span class="form-field-label">RRSP contribution ($/mo)</span>
                      <input type="number" name="salaryChangeRrspContributionOverride" value="${scenario.salaryChangeRrspContributionOverride}" step="0.01" min="0" placeholder="Same as strategy">
                    </div>
                    <div class="form-field">
                      <span class="form-field-label">Marginal tax rate (%)</span>
                      <input type="number" name="salaryChangeMarginalTaxRateOverridePercent" value="${scenario.salaryChangeMarginalTaxRateOverridePercent}" step="0.1" min="0" max="100" placeholder="Same as strategy">
                    </div>
                    <div class="form-field">
                      <span class="form-field-label">RRSP room accrued/yr</span>
                      <input type="number" name="salaryChangeRoomAccrualOverride" value="${scenario.salaryChangeRoomAccrualOverride}" step="0.01" min="0" placeholder="Same as strategy">
                    </div>
                  </div>
                </fieldset>
              </fieldset>
            </div>

            <div class="facet-block" data-facet="rrsp-strategy"<#if !scenario.hasRrspStrategy> hidden</#if>>
              <div class="facet-block-header">
                <span class="facet-block-title-group">
                  <span class="facet-block-title">RRSP strategy</span>
                  <button type="button" class="facet-help-btn" data-open-help="help-rrsp-strategy" aria-label="What does RRSP strategy do?">?</button>
                </span>
                <button type="button" class="facet-remove" data-remove-facet="rrsp-strategy">Remove</button>
              </div>
              <fieldset class="facet-fieldset"<#if !scenario.hasRrspStrategy> disabled</#if>>
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
              </fieldset>
            </div>

            <div class="facet-block" data-facet="rrsp-accrual"<#if !scenario.rrspAccrueRoomFromIncome> hidden</#if>>
              <div class="facet-block-header">
                <span class="facet-block-title-group">
                  <span class="facet-block-title">RRSP room accrual</span>
                  <button type="button" class="facet-help-btn" data-open-help="help-rrsp-accrual" aria-label="What does this do?">?</button>
                </span>
                <button type="button" class="facet-remove" data-remove-facet="rrsp-accrual">Remove</button>
              </div>
              <fieldset class="facet-fieldset"<#if !scenario.rrspAccrueRoomFromIncome> disabled</#if>>
                <#-- No visible checkbox - this facet's own presence (hidden/
                     disabled together, see the shared add/remove-facet script
                     below) already is the on/off signal, same as
                     recreationalSpendAdjustment's facet. Which category counts
                     as income is a household-wide setting now (Your Numbers,
                     above) rather than picked per scenario - see
                     ScenarioStore.kt's rrspAccrueRoomFromIncome doc comment. -->
                <input type="hidden" name="rrspAccrueRoomFromIncome" value="true">
                <div class="form-row">
                  <div class="form-field">
                    <span class="form-field-label">Annual accrual cap ($, optional)</span>
                    <input type="number" name="rrspAnnualRoomAccrualCap" value="${scenario.rrspAnnualRoomAccrualCap}" step="0.01" min="0">
                  </div>
                </div>
              </fieldset>
            </div>

            <div class="facet-add-row">
              <#if scenario.recreationalSpendAdjustment == "0.00">
              <button type="button" class="facet-add-chip" data-add-facet="recreational"><span class="facet-add-chip-plus">+</span> Recreational spend adjustment</button>
              </#if>
              <#if !scenario.hasSalaryChange>
              <button type="button" class="facet-add-chip" data-add-facet="salary"><span class="facet-add-chip-plus">+</span> Salary change</button>
              </#if>
              <#if !scenario.hasRrspStrategy>
              <button type="button" class="facet-add-chip" data-add-facet="rrsp-strategy"><span class="facet-add-chip-plus">+</span> RRSP strategy</button>
              </#if>
              <#if !scenario.rrspAccrueRoomFromIncome>
              <button type="button" class="facet-add-chip" data-add-facet="rrsp-accrual"><span class="facet-add-chip-plus">+</span> RRSP room accrual</button>
              </#if>
            </div>
          </div>
            <button type="submit" class="button button-small button-save" style="margin-top:1.1rem;">Save</button>
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
      <div class="facet-scope" data-facet-scope>
        <div class="facet-core">
          <div class="facet-core-header">
            <span class="facet-block-title-group">
              <span class="facet-block-title">Core assumptions</span>
              <button type="button" class="facet-help-btn" data-open-help="help-core" aria-label="What do these mean?">?</button>
            </span>
          </div>
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
          </div>
        </div>

        <div class="facet-block" data-facet="recreational" hidden>
          <div class="facet-block-header">
            <span class="facet-block-title-group">
              <span class="facet-block-title">Recreational spend adjustment</span>
              <button type="button" class="facet-help-btn" data-open-help="help-recreational" aria-label="What does this adjust?">?</button>
            </span>
            <button type="button" class="facet-remove" data-remove-facet="recreational">Remove</button>
          </div>
          <fieldset class="facet-fieldset" disabled>
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Adjustment ($/mo)</span>
                <input type="number" name="recreationalSpendAdjustment" value="0" step="0.01">
              </div>
            </div>
          </fieldset>
        </div>

        <div class="facet-block" data-facet="salary" hidden>
          <div class="facet-block-header">
            <span class="facet-block-title-group">
              <span class="facet-block-title">Salary change</span>
              <button type="button" class="facet-help-btn" data-open-help="help-salary" aria-label="What does Salary change do?">?</button>
            </span>
            <button type="button" class="facet-remove" data-remove-facet="salary">Remove</button>
          </div>
          <fieldset class="facet-fieldset" disabled>
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Date</span>
                <input type="date" name="salaryChangeDate">
              </div>
              <div class="form-field">
                <span class="form-field-label">Amount ($/mo)</span>
                <input type="number" name="salaryChangeMonthlyDelta" step="0.01">
              </div>
              <div class="form-field">
                <span class="form-field-label">Reverts on (optional)</span>
                <input type="date" name="salaryChangeEndDate">
              </div>
            </div>
            <div class="facet-core-header" style="margin-top:0.9rem;">
              <span class="facet-block-title-group">
                <span class="field-label" style="margin:0;">While in effect, override RRSP strategy's numbers (optional)</span>
                <button type="button" class="facet-help-btn" data-open-help="help-salary-override" aria-label="What does this override?">?</button>
              </span>
            </div>
            <p class="override-hint" data-override-hint="rrsp-strategy">Add RRSP strategy to enable these.</p>
            <fieldset class="facet-fieldset" data-override-fields="rrsp-strategy" disabled>
              <div class="form-row">
                <div class="form-field">
                  <span class="form-field-label">RRSP contribution ($/mo)</span>
                  <input type="number" name="salaryChangeRrspContributionOverride" step="0.01" min="0" placeholder="Same as strategy">
                </div>
                <div class="form-field">
                  <span class="form-field-label">Marginal tax rate (%)</span>
                  <input type="number" name="salaryChangeMarginalTaxRateOverridePercent" step="0.1" min="0" max="100" placeholder="Same as strategy">
                </div>
                <div class="form-field">
                  <span class="form-field-label">RRSP room accrued/yr</span>
                  <input type="number" name="salaryChangeRoomAccrualOverride" step="0.01" min="0" placeholder="Same as strategy">
                </div>
              </div>
            </fieldset>
          </fieldset>
        </div>

        <div class="facet-block" data-facet="rrsp-strategy" hidden>
          <div class="facet-block-header">
            <span class="facet-block-title-group">
              <span class="facet-block-title">RRSP strategy</span>
              <button type="button" class="facet-help-btn" data-open-help="help-rrsp-strategy" aria-label="What does RRSP strategy do?">?</button>
            </span>
            <button type="button" class="facet-remove" data-remove-facet="rrsp-strategy">Remove</button>
          </div>
          <fieldset class="facet-fieldset" disabled>
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
          </fieldset>
        </div>

        <div class="facet-block" data-facet="rrsp-accrual" hidden>
          <div class="facet-block-header">
            <span class="facet-block-title-group">
              <span class="facet-block-title">RRSP room accrual</span>
              <button type="button" class="facet-help-btn" data-open-help="help-rrsp-accrual" aria-label="What does this do?">?</button>
            </span>
            <button type="button" class="facet-remove" data-remove-facet="rrsp-accrual">Remove</button>
          </div>
          <fieldset class="facet-fieldset" disabled>
            <input type="hidden" name="rrspAccrueRoomFromIncome" value="true">
            <div class="form-row">
              <div class="form-field">
                <span class="form-field-label">Annual accrual cap ($, optional)</span>
                <input type="number" name="rrspAnnualRoomAccrualCap" step="0.01" min="0">
              </div>
            </div>
          </fieldset>
        </div>

        <div class="facet-add-row">
          <button type="button" class="facet-add-chip" data-add-facet="recreational"><span class="facet-add-chip-plus">+</span> Recreational spend adjustment</button>
          <button type="button" class="facet-add-chip" data-add-facet="salary"><span class="facet-add-chip-plus">+</span> Salary change</button>
          <button type="button" class="facet-add-chip" data-add-facet="rrsp-strategy"><span class="facet-add-chip-plus">+</span> RRSP strategy</button>
          <button type="button" class="facet-add-chip" data-add-facet="rrsp-accrual"><span class="facet-add-chip-plus">+</span> RRSP room accrual</button>
        </div>

        <button type="submit" class="button" style="margin-top:1.1rem;">Add scenario</button>
      </div>
      </form>

      <dialog class="recategorize-dialog" id="help-core">
        <h3 class="recategorize-dialog-title">Core assumptions</h3>
        <p class="recategorize-dialog-description"><strong>Growth rate</strong> compounds today's existing investments plus whatever share of each month's new savings you mark as invested &mdash; the rest sits as cash and never grows.</p>
        <p class="recategorize-dialog-description">Both numbers are hypothetical, for this scenario's projection only. Your real transaction history and account balances aren't touched.</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>

      <dialog class="recategorize-dialog" id="help-recreational">
        <h3 class="recategorize-dialog-title">Recreational spend adjustment</h3>
        <p class="recategorize-dialog-description">A flat $/mo added straight onto your real trailing savings rate, for this scenario's projection only &mdash; it doesn't read or change any transaction or category.</p>
        <p class="recategorize-dialog-description"><strong>Positive</strong> means saving more each month than your real history shows (imagine cutting recreational spending). <strong>Negative</strong> means saving less (imagine spending more).</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>

      <dialog class="recategorize-dialog" id="help-salary">
        <h3 class="recategorize-dialog-title">Salary change</h3>
        <p class="recategorize-dialog-description">Amount is a <strong>change</strong> from what you make today, not your new total salary &mdash; check Your Numbers' Income stat above to see what that baseline actually is. Models a one-time, dated change to your monthly savings rate &mdash; a raise, a pay cut, a career switch. Takes effect from its date onward. A negative amount models a deliberate pay cut, not just a raise.</p>
        <p class="recategorize-dialog-description">Set <strong>Reverts on</strong> if it's temporary (e.g. a year of reduced hours); leave it blank for a permanent change.</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>

      <dialog class="recategorize-dialog" id="help-salary-override">
        <h3 class="recategorize-dialog-title">Overriding RRSP numbers during a salary change</h3>
        <p class="recategorize-dialog-description">A raise or pay cut can also mean contributing differently to RRSP, or landing in a different tax bracket, while it's in effect.</p>
        <p class="recategorize-dialog-description">These three fields replace RRSP strategy's own numbers only for the duration of this change, then revert automatically once it ends (or run indefinitely if it never does). Leave any of them blank to keep using RRSP strategy's normal numbers throughout.</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>

      <dialog class="recategorize-dialog" id="help-rrsp-strategy">
        <h3 class="recategorize-dialog-title">RRSP strategy</h3>
        <p class="recategorize-dialog-description">Diverts part of your monthly savings into an RRSP, capped by the room you enter. Once a year it triggers a refund &mdash; that year's contributions &times; your marginal tax rate &mdash; which you can keep as cash or reinvest.</p>
        <p class="recategorize-dialog-description">Your marginal rate and RRSP room are numbers you look up and enter yourself; this app never fetches or guesses tax figures.</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>

      <dialog class="recategorize-dialog" id="help-rrsp-accrual">
        <h3 class="recategorize-dialog-title">RRSP room accrual</h3>
        <p class="recategorize-dialog-description">Grows your remaining RRSP room automatically each year &mdash; 18% of your Income (set in Your Numbers, above), summed over the trailing 12 months &mdash; instead of leaving it fixed at whatever you typed in.</p>
        <p class="recategorize-dialog-description">Set which category counts as Income once, in Your Numbers, and every scenario with this turned on reads the same number. The optional annual cap mirrors a real CRA limit, which changes yearly, so it's a number you look up and enter rather than one this app assumes.</p>
        <div class="recategorize-dialog-actions">
          <button type="button" class="button button-secondary" data-close-help>Got it</button>
        </div>
      </dialog>
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

    // Scenario facets - add/remove toggles a facet's visibility and its
    // <fieldset>'s disabled state together: a disabled fieldset's controls
    // are excluded from form submission, which is what actually makes
    // "Remove" drop a facet's values instead of just hiding stale ones
    // that would still get saved. Salary change's RRSP-override sub-fields
    // are additionally gated on RRSP strategy being present in the same
    // scenario - re-synced after every add/remove since either facet can
    // toggle independently in either order.
    (function () {
      function syncOverrides(scope) {
        var hasStrategy = !!scope.querySelector('[data-facet="rrsp-strategy"]:not([hidden])');
        var fieldset = scope.querySelector('[data-override-fields="rrsp-strategy"]');
        var hint = scope.querySelector('[data-override-hint="rrsp-strategy"]');
        if (fieldset) fieldset.disabled = !hasStrategy;
        if (hint) hint.hidden = hasStrategy;
      }

      document.querySelectorAll('[data-facet-scope]').forEach(syncOverrides);

      document.querySelectorAll('[data-add-facet]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var facet = btn.getAttribute('data-add-facet');
          var scope = btn.closest('[data-facet-scope]');
          if (!scope) return;
          var section = scope.querySelector('[data-facet="' + facet + '"]');
          if (section) {
            section.hidden = false;
            var fieldset = section.querySelector(':scope > fieldset.facet-fieldset');
            if (fieldset) fieldset.disabled = false;
          }
          btn.hidden = true;
          syncOverrides(scope);
        });
      });

      document.querySelectorAll('[data-remove-facet]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var facet = btn.getAttribute('data-remove-facet');
          var scope = btn.closest('[data-facet-scope]');
          if (!scope) return;
          var section = scope.querySelector('[data-facet="' + facet + '"]');
          var chip = scope.querySelector('[data-add-facet="' + facet + '"]');
          if (section) {
            section.hidden = true;
            var fieldset = section.querySelector(':scope > fieldset.facet-fieldset');
            if (fieldset) fieldset.disabled = true;
          }
          if (chip) chip.hidden = false;
          syncOverrides(scope);
        });
      });

      document.querySelectorAll('[data-open-help]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var dialog = document.getElementById(btn.getAttribute('data-open-help'));
          if (dialog) dialog.showModal();
        });
      });
      document.querySelectorAll('[data-close-help]').forEach(function (btn) {
        btn.addEventListener('click', function () {
          var dialog = btn.closest('dialog');
          if (dialog) dialog.close();
        });
      });
    })();
  </script>
</body>
</html>
