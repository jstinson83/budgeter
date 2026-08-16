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

- [x] **1. Project core (manual only)** - `Project` entity + Firestore
      store (`id`, `ownerId`, `name`, `status`: Active/Planned/
      Deprioritized/Completed, `component`: single `Component` tag,
      `priority`: High/Medium/Low, mutable), `/projects` page (list by
      status, filter by component), create/edit form, change status. No
      recommendations, no linked facts yet.
- [x] **2. Link facts & documents to a project** - project detail page
      gains attach/detach pickers for existing `HouseFact`/`HouseDocument`
      rows (`factIds`/`documentIds` on `Project`).
- [x] **3. Project entry feed** - `ProjectEntry` (Note/Quote/Photo/Link
      types) as one chronological feed on the project detail page. Revised
      after the first pass to a single free-form text field + optional file
      attachment, with type inferred server-side (a URL in the text ->
      Link, an attached image -> Photo, any other attached file -> Quote,
      otherwise -> Note) rather than a type picker - the maintainer's call.
      No separate Decision type: dropped since there's no reliable textual
      signal to detect one, so an undetected "decision" is just a Note.
      Quote/Photo upload straight to the same GCS bucket `HouseDocument`
      uses, *not* as a `HouseDocument` row - a quote/photo isn't
      house-knowledge source material to extract facts from, and isn't
      PDF-only. See `context.md`.
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
