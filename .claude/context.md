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

## Architecture at a glance

- **Backend**: Kotlin + Ktor, single service (`backend/`), same stack/patterns
  as the maintainer's other project (`foodie`).
- **Deploy**: Cloud Run, deployed via a GitHub-triggered Cloud Build trigger
  using `cloudbuild.yaml` at the repo root → Artifact Registry → Cloud Run.
  Not live yet — `cloudbuild.yaml`/`Dockerfile` exist, but the Cloud Build
  trigger hasn't been created (waiting on this to merge to `main` first),
  and no deploy has run.
- **Storage**: Firestore.

## Configuration reference

Concrete IDs and config values — the single source of truth for these
facts. `CLAUDE.md` keeps the operational gotchas: what breaks and how to
debug it, not the values themselves.

- **GCP project**: `foodie-503510` — shared with the `foodie` project, not
  a dedicated project for this app. Don't confuse the project *id* with
  either app's own name.
- **Region**: `northamerica-northeast1` (Montreal) for both Cloud Run and
  Firestore, matching foodie's region.
- **Cloud Run service**: `budgeter` (region above). Created automatically
  on first deploy — not manually provisioned.
- **Artifact Registry**: reuses foodie's existing `cloud-run-source-deploy`
  repo (also `northamerica-northeast1`), image name `budgeter-backend`
  (see `_IMAGE` in `cloudbuild.yaml`) rather than a dedicated AR repo.
- **Firestore database ID**: `home-os` (`northamerica-northeast1`) — a
  separate named database from foodie's `foodie-nne1`, in the same shared
  project. Not yet wired into any code (`FIRESTORE_DATABASE_ID` env var
  convention TBD once persistence code exists, following foodie's
  pattern).
- **OAuth client / `SESSION_SECRET` / `OAUTH_REDIRECT_BASE_URL`**: not
  created yet — the maintainer is setting these up manually after the
  first deploy (need the Cloud Run URL for the OAuth redirect URI first).

## Maintenance

Update this file when: infra/architecture changes, a new major feature
lands, or a past decision needs to be recorded so it doesn't get
relitigated. Keep it high-level — implementation gotchas belong in
`CLAUDE.md`, not here.
