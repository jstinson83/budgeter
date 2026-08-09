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
2026-08-09) captures the pivot to the broader Household OS concept. The
codebase itself is still budgeting-app-shaped (Google sign-in + CSV
transaction import) — the first sprint toward Household OS deliberately
started there (see "First feature" below) since it's the roadmap's own
stated Version 1 groundwork: real persistence, real deploy, before any AI
extraction work.

## Architecture at a glance

- **Backend**: Kotlin + Ktor, single service (`backend/`), same stack/patterns
  as the maintainer's other project (`foodie`).
- **Deploy**: Cloud Run, via a GitHub-triggered Cloud Build trigger using
  `cloudbuild.yaml` at the repo root → Artifact Registry → Cloud Run. Live:
  the trigger builds and deploys on push to `main`, and Google sign-in works
  end to end on the deployed URL.
- **Storage**: Firestore, database `home-os`. `TransactionRepository` /
  `FirestoreTransactionStore` (`backend/src/main/kotlin/com/budgeter/TransactionStore.kt`)
  is the first collection, following foodie's repository-interface +
  Firestore-impl + in-memory-test-fake pattern.

## First feature: CSV transaction import

`/transactions` — upload a CSV, see imported transactions. Deliberately
chosen as the first slice of work over spec's original "scan your home"
Version 1 (photo → AI asset extraction) since the maintainer needed
something buildable while away from home; it's also genuine groundwork
either way (real Firestore persistence, real deploy path) that later
photo/document-capture features will reuse.

- CSV is assumed **headerless**: `date,description,amount[,balance]`.
  Balance (if present) is parsed but discarded. Date is ISO-8601
  (`yyyy-MM-dd`) since there's no real bank export yet to match against —
  TD's actual export uses `MM/DD/YYYY` with separate debit/credit columns
  instead of one signed amount, so `CsvTransactionParser` will need
  updating once real statements replace synthetic test data.
  `amount` is one signed `Double` (negative = money out).
- Bad rows are skipped, not fatal — `CsvTransactionParser.parse` returns
  both the successfully parsed transactions and a list of per-row errors;
  the import route reports "Imported N, skipped M" rather than
  all-or-nothing failing.
- No categorization, dedup, or analysis yet — a re-uploaded CSV will import
  the same rows again as duplicates. Categorization is planned to be
  AI-driven off the description field (see product_spec.md's "Financial
  assistant"), not built yet.

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
  project. Read via the `FIRESTORE_DATABASE_ID` env var
  (`Application.kt`), falling back to `"home-os"` if unset, same pattern
  as foodie's `FIRESTORE_DATABASE_ID`/`foodie` fallback.
- **OAuth client / `SESSION_SECRET` / `OAUTH_REDIRECT_BASE_URL`**: created
  and set on the Cloud Run service — sign-in is confirmed working on the
  deployed URL.

## Maintenance

Update this file when: infra/architecture changes, a new major feature
lands, or a past decision needs to be recorded so it doesn't get
relitigated. Keep it high-level — implementation gotchas belong in
`CLAUDE.md`, not here.
