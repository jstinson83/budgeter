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
dated salary-change event - each rendered as its own line on every goal's
chart alongside the always-shown baseline), see `context.md`'s Financial
Planning Projections subsystem section. Working in slices, smallest first:
- [ ] 5. Gemini scenario-parameter suggestions (optional, additive, last -
      suggests parameter values from spending trends via a constrained
      schema, never touches the engine's arithmetic).

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
