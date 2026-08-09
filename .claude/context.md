# Household OS — Project Context

Stable overview of the project. Update this when architecture, infra, or
major conventions change — not for day-to-day task status (see `current.md`
for that).

## What this is

See `product_spec.md` at the repo root for the full product vision. In
short: an AI-powered personal context layer that continuously builds and
maintains a structured model of a household — assets, finances,
maintenance, projects, decisions, documents — from photos, receipts, and
conversation, so the reasoning behind past decisions isn't lost to
scattered emails, folders, and memory.

This repo previously scoped a budgeting app. `product_spec.md` (added
2026-08-09) captures the pivot to the broader Household OS concept.
Implementation has not started yet — no code, schema, or infra reflects it.

## Intended architecture (not yet implemented)

- **Deploy**: Cloud Run, deployed via a GitHub-triggered Cloud Build
  trigger using a `cloudbuild.yaml` at the repo root — same deployment
  pattern as the maintainer's other project.
- **Storage**: Firestore.

These are the maintainer's stated direction, not decisions made in this
repo yet — no GCP project, Cloud Run service, or Firestore database has
been provisioned. Once real infra exists, record the concrete facts here
(project id, service name, region, database id, env var names) and keep
operational gotchas (what breaks, how to debug it) in `CLAUDE.md` instead,
referencing rather than restating these facts.

## Maintenance

Update this file when: infra/architecture changes, a new major feature
lands, or a past decision needs to be recorded so it doesn't get
relitigated. Keep it high-level — implementation gotchas belong in
`CLAUDE.md`, not here.
