# Repository Instructions

## Session continuity (`.claude/context.md` and `.claude/current.md`)

- `.claude/context.md` is the stable project overview (architecture, major
  features, decisions already made).
- `.claude/current.md` holds the maintainer's active **sprint plan**: a
  checklist of tasks they've laid out in conversation, not a "last thing
  done" log. It's maintainer-authored — when they describe a plan, write it
  down as a checklist; don't add tasks to it on your own initiative.
- Don't rewrite `current.md` at the start of every task — it persists
  across tasks/sessions untouched by default. It only changes when:
  - The maintainer communicates a new or updated plan — write it down
    (replacing what's there).
  - A task gets finished — check the plan for a matching item and remove
    it if present. If the finished task isn't on the plan, leave the file
    alone; not everything has to be planned.
- If the maintainer asks you to "consult the plan," read `current.md` to
  see what's left and use it to decide what's next.
- Keep entries as a short checklist (one line per task), not a narrative
  status writeup — commit history and PR descriptions already capture the
  "what happened"; this file is just "what's still open."
- Update `context.md` (separately from the sprint plan) when a task changed
  architecture, added a major feature, or made a decision worth not
  relitigating later.
- Keep `context.md` high-level: architecture, decisions, and concrete
  config facts (IDs, regions, key/secret locations) other tasks need
  without re-deriving them. Operational gotchas — what breaks, how it bit
  us before, how to debug it — belong in this file (`CLAUDE.md`) instead.
  Don't restate `context.md`'s facts here; reference them.

## Pull Requests

- Always create a pull request after pushing a commit to a feature branch, unless one already exists for that branch.

## Deploy pipeline

`cloudbuild.yaml` (repo root) + `backend/Dockerfile` exist, mirroring the
maintainer's other project's (`foodie`) setup. Concrete project/region/
service/database facts live in `.claude/context.md`'s Configuration
reference — don't restate them here.

- The Cloud Build trigger is created **manually** in the console, and only
  after `cloudbuild.yaml` is on `main` (the trigger points at a config
  file path on a branch, so it needs to exist there first). Same gotcha as
  foodie: the trigger's build config must be set to "Cloud Build
  configuration file", not "Dockerfile" — a Dockerfile-only trigger builds
  and pushes the image but never deploys it.
- Cloud Run env vars (OAuth client id/secret, `SESSION_SECRET`,
  `OAUTH_REDIRECT_BASE_URL`, `FIRESTORE_DATABASE_ID`, `GEMINI_API_KEY`) are
  set manually on the Cloud Run service after the first deploy, not via
  `cloudbuild.yaml` — same pattern as foodie, since the OAuth redirect URI
  needs the actual Cloud Run URL to exist first. `GEMINI_API_KEY` isn't set
  yet as of the Gemini categorization feature landing — the "Categorize"
  button on `/analysis` will fail with a "GEMINI_API_KEY is not set" error
  banner on the deployed app until it's added.
- This project shares a GCP project with `foodie`. Firestore's
  `roles/datastore.user` is project-scoped, so the runtime service account
  already had it from foodie's setup — no new IAM grant was needed for the
  `home-os` database.
- **Live as of the CSV-import feature**: the trigger builds and deploys on
  push to `main`, and Google sign-in works end to end on the deployed URL.
  Record new gotchas here as they bite, following foodie's CLAUDE.md as the
  template (Cloud Run CPU-allocation behavior, Firestore composite-index
  errors, etc. — check there if something in this pipeline looks
  unfamiliar).

## Persistence (Firestore) gotchas

Firestore database id and the repository/store class names live in
`.claude/context.md` — this section is what to check when persistence
breaks, not the facts themselves.

- `TransactionRepository.all()` (`whereEqualTo("ownerId", ...).orderBy("date", ...)`)
  needs a composite index on `(ownerId, date)` - Firestore won't create it
  automatically, and the first time this query shape runs against the real
  database it throws `FAILED_PRECONDITION` with a direct link in the error
  message to create the missing index. Same gotcha as foodie's
  `RecipeRepository.all()`, see foodie's CLAUDE.md for the full explanation.
- Local dev needs `gcloud auth application-default login` (see README) -
  without ADC, constructing the Firestore client throws a
  `NullPointerException` down inside `FirestoreOptions`/`GrpcFirestoreRpc`
  (not an obviously-Firestore-shaped error) rather than a clear
  "not authenticated" message. On Cloud Run this resolves automatically via
  the metadata server, so this only bites local runs.
- `Transaction.date` is stored as a plain ISO-8601 string (`yyyy-MM-dd`),
  not a Firestore `Timestamp` - it's a calendar date with no meaningful
  time/timezone component, and ISO-8601's zero-padded format still sorts
  correctly as a string, so `orderBy("date", ...)` works unchanged.
- `Transaction.amount` is stored as a plain `Double`, same simplification
  foodie's `Ingredient.quantity` uses. Revisit (e.g. integer cents) if
  floating-point drift ever actually shows up in a sum/balance - not
  addressed preemptively.
- Dedup on CSV import is keyed on `(ownerId, fileHash, rowNumber)` -
  `transactionFingerprint()` in `TransactionStore.kt`, used as the Firestore
  document ID. Identity is tied to a row's position within a specific
  uploaded file, not to its date/description/amount: two transactions that
  happen to share all three (e.g. two identical same-day coffees) are kept
  as separate rows since they sit at different positions in the file.
  Re-uploading the exact same file is idempotent (same fileHash, same row
  positions, same IDs); re-exporting the same statement with even a minor
  formatting change (e.g. an added header row) hashes differently and
  re-imports everything as new - there's no real per-transaction ID from
  the source data to do better than this without one.
