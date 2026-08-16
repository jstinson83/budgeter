# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Active task: House Projects & Recommendations

Turn house facts into a manageable set of projects, with Gemini-generated
project recommendations. Design discussed and agreed 2026-08-16 - see
`context.md`'s House Knowledge section for the existing `HouseFact`/
`HouseDocument`/`Component` model this builds on. Working in chunks,
smallest first:

- [ ] **1. Project core (manual only)** - `Project` entity + Firestore
      store (`id`, `ownerId`, `name`, `status`: Active/Planned/
      Deprioritized/Completed, `component`: single `Component` tag,
      `priority`: High/Medium/Low, mutable), `/projects` page (list by
      status, filter by component), create/edit form, change status. No
      recommendations, no linked facts yet.
- [ ] **2. Link facts & documents to a project** - project detail page
      gains attach/detach pickers for existing `HouseFact`/`HouseDocument`
      rows (`factIds`/`documentIds` on `Project`).
- [ ] **3. Project entry feed** - `ProjectEntry` (Note/Decision/Quote/
      Photo/Link types) as one chronological feed on the project detail
      page; Link carries a URL, Quote/Photo can attach a `HouseDocument`
      via the existing upload path.
- [ ] **4. Recommendation generation** - `Recommendation` entity/store
      (`component`, `name`, rationale, `supportingFactIds`,
      `suggestedPriority`, `status`: Pending/Accepted/Rejected - Rejected
      is one merged state covering both "deprioritize" and "dismiss", no
      distinction). Async job loops `Component.values()`, skipping any
      component with no new facts since its own last generation (a stored
      per-`(ownerId, component)` staleness marker), one Gemini call per
      stale component - same async-job-plus-poll shape as document
      extraction. One "Generate recommendations" button drives the whole
      loop, not a per-component button.
- [ ] **5. Recommendation review UI** - screen listing pending
      recommendations (name/rationale/supporting facts/priority) with
      actions Create project (pre-fills from the rec, including linked
      facts and seeded priority) and Reject (persisted, browsable), plus a
      way to browse past accepted/rejected recommendations.

**Deferred, noted for later, not part of this chunk sequence:** a "merge
project" action for combining multiple single-component projects into one
multi-component project - came up when discussing whether `Project` should
support more than one `component` tag; decided to keep it single-tag for
now and revisit merging as its own follow-up feature once chunk 1 is
solid.
