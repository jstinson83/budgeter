# Current Sprint

The maintainer's active task checklist, laid out by them in conversation —
not a log of what was last worked on. See `CLAUDE.md`'s "Session
continuity" section for how this file gets maintained: written down when a
plan is communicated, items removed as they're finished, otherwise left
alone. See `context.md` for the stable project overview instead.

## Active task

None currently. Deploy pipeline, CSV transaction import, and the
quick-version Gemini categorization + `/analysis` screen are all done — see
`context.md` for what's actually live. `GEMINI_API_KEY` still needs to be
set on the Cloud Run service (see `CLAUDE.md`) before categorization works
on the deployed app.

Mentioned as a real next step, not yet a committed plan: a persistent,
precomputed version of the analysis (cron-job-like — compute and store
category totals instead of recomputing per button press). Write it down as
a checklist once the maintainer actually lays one out.
