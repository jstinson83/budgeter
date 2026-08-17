# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Completed: House Projects & Recommendations

All five chunks done (project core, fact/document linking, entry feed,
recommendation generation, recommendation review UI) - see `context.md`'s
Ninth feature for the full writeup. Core loop (Facts -> Recommendations ->
Projects -> Work) is live end to end.

**Deferred, noted for later, not part of any active chunk sequence:** a
"merge project" action for combining multiple single-component projects
into one multi-component project - decided to keep `Project` single-tag
and revisit merging as its own follow-up feature if it comes up again.

## Active task: Financial Goals & Project Feasibility

Connect the financial side (transactions/analysis) to the project side
(`Project`/`Recommendation`) - track a savings goal, compute a rolling
savings rate, and judge project feasibility as a forward projection rather
than a static cost comparison. Design discussed and agreed 2026-08-17 -
see `context.md`'s Tenth feature for the full primitive list and reasoning
this builds on. Working in chunks, smallest first:

**Phase 1 - goal & rate (no project linkage yet)**
- [ ] **1. `SavingsGoal` entity** - name, target amount, target date, start
      date + Firestore store + minimal create/view UI.
- [ ] **2. Savings rate calculation** - reuse `DashboardPage.kt`'s
      `monthlyNetChange` aggregation, split into income vs. expense per
      month, compute a 3-month rolling savings rate.
- [ ] **3. Goal progress view** - given the rate + amount saved since the
      goal's start date, project forward to the target date -> "on track /
      behind by $X / ahead by $X". Useful standalone before any project
      logic exists.

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
