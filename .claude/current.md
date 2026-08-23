# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Completed: House Projects & Recommendations

All five chunks done (project core, fact/document linking, entry feed,
recommendation generation, recommendation review UI) - see `context.md`'s
House Projects & Recommendations subsystem section for the full writeup.
Core loop (Facts -> Recommendations -> Projects -> Work) is live end to
end.

**Deferred, noted for later, not part of any active chunk sequence:** a
"merge project" action for combining multiple single-component projects
into one multi-component project - decided to keep `Project` single-tag
and revisit merging as its own follow-up feature if it comes up again.

## Active task: Financial Planning Projections (net worth + scenarios)

**2026-08-23: Goals hidden from `/planning`'s UI, revisit later.** The
maintainer's actual day-to-day want is watching wealth grow over time, not
a target/on-track judgment - "goals" felt under-baked as a concept and got
in the way of that. `/planning` now leads with a goal-independent "Wealth
over time" chart (baseline + one line per Scenario, projected a fixed
`WEALTH_CHART_HORIZON_YEARS` = 10 years out, `ProjectionChart.kt`'s
`wealthChartModel`/`NetWorthPage.kt`'s `wealthChartRowModel`) instead of
one chart per goal. `FinancialGoal` CRUD, its own goal-scoped chart, and
the RRSP-refund-vs-goal outcome text are untouched in the backend (still
exercised by `PlanningExport.kt`'s verification export and reachable via
`POST /planning/goals*`) - only `planning.ftl`'s Goals card and "Add goal"
form were removed. Revisit if/when goals come back as a real feature
(possibly reshaped, given the "under-baked" feedback) rather than treating
this as a completed decision either way.

**Same session: the wealth chart scrolls horizontally instead of being
squeezed to fit.** Its SVG width now scales with point count (one SVG
unit == one real CSS pixel, no `preserveAspectRatio` stretching) and is
wrapped in an `overflow-x: auto` container (`.wealth-chart-scroll` in
`styles.css`) - a multi-year monthly projection would otherwise cram
hundreds of points into one fixed ~300px-wide viewBox. The goal-scoped
chart (hidden, above) keeps its original fixed-width/no-scroll behavior
unchanged.

A deterministic net worth projection engine (no Gemini in the math) that
goals are checked against - net worth target or retirement, not just a
flat savings-rate target. Design discussed and agreed 2026-08-21.
Supersedes Phase 1 below (`SavingsGoal` + flat savings-rate projection):
the maintainer's real goals are net-worth-shaped, so a real starting net
worth (manual asset/liability entry, chosen over deriving bank/CC/LOC
balances from transactions the way the pulled "net position" dashboard
did - see `CLAUDE.md`'s gotcha writeup on why that was inaccurate) plus a
proper projection engine need to come first. Explicitly a prerequisite for
Phase 2 onward below, not a separate feature - "insert a project's cost
into the goal projection" becomes "run the engine with the project's cost
added as an event" once this lands. Slices 1-4 are done - `NetWorthEntry`
CRUD, `FinancialGoal` CRUD, the baseline projection engine, and `Scenario`
CRUD (named what-ifs: a market growth rate, an invested-vs-cash split of
ongoing savings, a recreational-spend-vs-savings $/mo knob, one optional
dated salary-change event, one optional RRSP contribution/refund
strategy, and optional RRSP room accrual keyed off a household-tagged
income category (18%/yr, optionally capped) - each scenario rendered as
its own line on every goal's chart alongside the always-shown baseline),
see `context.md`'s Financial
Planning Projections subsystem section. Working in slices, smallest first:
- [x] Mortgage amortization + real estate appreciation ("Fix 1" from a
      2026-08-22 review of a projections export) - `NetWorthEntry` gained
      optional `annualInterestRate`/`mortgagePaymentCategoryId` (MORTGAGE)
      and `annualAppreciationRate` (REAL_ESTATE) fields; both
      `projectNetWorth` (baseline) and `projectScenario` now
      amortize/appreciate those entries month by month instead of leaving
      them frozen at their snapshot value for the whole horizon. The
      monthly payment is derived from the tagged category's own trailing
      transaction history (median, not average) rather than typed in by
      hand - a same-day maintainer follow-up ("mortgage payments are
      already tracked by category, find that amount, we do this elsewhere")
      on top of the initial landing, which still had a raw `monthlyPayment`
      field. See `context.md`'s Financial Planning Projections subsystem
      section for the full writeup, including the payoff-frees-the-payment
      behavior and why deriving the payment from the same transaction
      history the baseline savings rate reads isn't double-counting.
- [ ] Coast/downshift analysis ("Fix 2" from the same review, explicitly
      gated on Fix 1 above landing first - a "full coast, ignoring home
      equity" test run never crossed over within the 10-year horizon, since
      home equity was doing real work toward the goal): earliest date the
      household could (a) stop new contributions entirely and still hit a
      goal via pure compounding + projected home equity, or (b) cut
      contributions by some amount (the scenario schema's already-unused
      `salaryChangeDate`/`salaryChangeMonthlyDelta` fields, applied with a
      negative delta) and still land on target. Reference pseudocode for
      both (`findCoastDate`/`earliestSafeDropDate`) is in the maintainer's
      uploaded notes from that review.
- [ ] 5. Gemini-suggested marginal tax rate for the RRSP strategy's rate
      field (optional, additive - a "suggest my rate" button using
      province/income, reviewable before saving, never a live/authoritative
      lookup since this app's Gemini calls have no web tool - see
      `context.md`). Broaden to other scenario-parameter suggestions
      (spending-trend-based growth/spend-adjustment guesses) if that still
      seems worth it once this lands.
- [ ] 6. Real estate/leverage scenario (borrow against net worth at a
      rate, invest the proceeds, service via monthly interest) - raised as
      an idea, not yet scoped into concrete fields.
- [ ] 7. **Income-change event** (discussed 2026-08-23): a dated $/mo delta
      to projected savings, with an *optional end date* - generalizes the
      existing single `salaryChangeDate`/`salaryChangeMonthlyDelta` step,
      which is permanent-from-its-date-onward with no way to say "reverts
      on this date" (needed for "I'll earn less for N months," not just
      "I got a raise"). Bespoke UI (not shared with item 8 below): the add
      form explicitly asks whether the event also changes the scenario's
      RRSP monthly contribution / RRSP room accrual base or cap / marginal
      tax rate - each defaults to "no change" so a plain pay-change event
      stays a one-field action, but the fields are shown up front (not
      behind an "advanced" toggle) so the household has to consciously
      decide rather than those numbers silently drifting out of sync with
      a changed income. Deliberately never auto-derived - this app doesn't
      model tax brackets (see `context.md`'s RRSP marginal-rate reasoning)
      and isn't going to start inferring one from an income delta.
- [ ] 8. **Project-payment event**: N payments on specific dates. Same idea
      as Phase 2 item 6 ("recurring cost delta") + Phase 3 item 7 ("planned
      spend timing") below, generalized from one recurring delta to an
      arbitrary payment schedule - reconcile into one mechanism when this
      is actually built rather than shipping two that do almost the same
      thing.

**Deferred event ideas, raised 2026-08-23 alongside the two above, not
scoped - revisit only if they come up:**
- One-time lump sum in/out (bonus, inheritance, tax refund, asset sale) -
  technically N=1 of item 8's payment shape, but common enough it might
  deserve its own quick-entry UI rather than "a project with one payment."
- A purchase financed by new debt (e.g. a car loan) rather than paid from
  savings - mechanically different from either event above: doesn't touch
  cash immediately, instead creates a new liability + recurring payment
  starting at a future date.
- Retirement/decumulation phase transition - the projection engine has no
  withdrawal-phase modeling at all today; a "stop earning, start spending
  down" event needs that groundwork first, not just a new event type.

**Phase 4/5 below talk about "goal status"/"the goal projection" - stale
wording now that goals are hidden from `/planning`'s UI (see the
2026-08-23 note at the top of this section).** Not rewritten yet since
that work isn't active; revisit the wording (and whether feasibility even
makes sense without a goal to compare against) if/when Phase 4 actually
starts.

**Phase 2 onward below now builds on the engine above instead of the old
Phase 1** (a project's cost/timing gets inserted into the new engine's
projection instead of a flat-rate one) - update the "SavingsGoal"/"goal
projection" wording in those items to match once slice 3 lands.

**Phase 2 - project cost estimation**
- [ ] **4. Single cost estimate on `Project`** - `estimatedCost` (amount +
      source: Quote/Link/Guess) as the simplest first cut.
- [ ] **5. Itemized costs** - a project can have multiple cost line items,
      each with its own source, summed to a total - what actually
      satisfies "different levels of automation."
- [ ] **6. Recurring cost delta** - optional ongoing monthly amount on a
      project's cost, separate from the one-time spend.

**Phase 3 - timing**
- [ ] **7. Planned spend timing** - a planned month on a project, so its
      cost can be placed on the projection timeline instead of assumed
      immediate.

**Phase 4 - feasibility**
- [ ] **8. Single-project feasibility** - insert one project's cost (+
      recurring delta) at its planned month into the goal projection, show
      before/after impact.
- [ ] **9. Portfolio feasibility** - same projection across all
      ACTIVE/selected projects combined, since projects compete for the
      same savings.

**Phase 5 - surfacing**
- [ ] **10. Dashboard integration** - goal status + at-risk projects
      visible on `/` without opening each project.
