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
  `OAUTH_REDIRECT_BASE_URL`, `FIRESTORE_DATABASE_ID`, `GEMINI_API_KEY`,
  `HOUSE_DOCUMENTS_BUCKET`) are set manually on the Cloud Run service after
  the first deploy, not via `cloudbuild.yaml` — same pattern as foodie,
  since the OAuth redirect URI needs the actual Cloud Run URL to exist
  first. `GEMINI_API_KEY` is now set on the deployed service — the
  "Categorize" button on `/analysis` works end to end in production.
- `HOUSE_DOCUMENTS_BUCKET` (House Knowledge document uploads, see below)
  needs a real GCS bucket created manually in the console first, same
  "provision the resource, then point the env var at it" order as the
  `home-os` Firestore database — `GcsDocumentBlobStore` throws a clear
  "not set" error on first use rather than at startup if this is skipped,
  same posture as `GEMINI_API_KEY`'s missing-key check. Not yet created as
  of this feature landing — do this before the first real document upload
  in production.
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

## Dashboard net position gotcha (feature since pulled)

A "net position" dashboard section briefly existed: `DashboardPage.kt`
combined each account's latest captured balance (`Transaction.balance`,
populated from the CSV's previously-discarded 5th column) into one
Bank-minus-debts figure. It shipped, had a real bug, got fixed, and was then
pulled entirely (see `.claude/context.md`'s dashboard-feature section for
why) - `Transaction.balance`/`ParsedTransaction.balance` no longer exist in
the codebase. Keeping the lesson here in case this is ever rebuilt:

- It shipped assuming Bank balance is an asset (added) and Credit Card/LOC
  balances are a liability that needed subtracting - i.e. treating a
  credit-card/LOC balance as a positive "amount owed." Wrong: confirmed
  against a real account, TD's export already signs credit-card/LOC
  balances the same way `Transaction.amount` is (negative = money owed), so
  subtracting an already-negative debt figure flipped it positive and added
  it to net position instead - silently inflating net position by roughly
  double the real debt on every affected account, not an off-by-a-little
  error. The fix (before the feature was pulled) was to sum the raw
  balances directly with no per-account-type sign flip at all. If this is
  rebuilt, start from that - don't reintroduce the asset/liability sign
  flip.
- Separately from the sign bug: a combined net-position figure inherently
  implies every account the household holds has been imported with balance
  data. In practice some accounts just aren't linked/uploaded, so the figure
  would quietly understate net worth with no indication on the page that
  it's incomplete - the reason it was pulled rather than just re-fixed. A
  future version should probably surface each account's own balance
  individually rather than combine them into one number, or otherwise make
  incompleteness visible.

## Gemini categorization gotchas

`GeminiTransactionCategorizer` (`GeminiCategorizer.kt`) is what `/analysis`'s
"Categorize" button calls - see `.claude/context.md` for the request/response
shape.

- First real-world run against a batch of ~120 transactions came back
  "Categorized 0 of 123" with no error banner at all - looked like a no-op,
  not a failure. Root cause: the original schema asked Gemini to echo back
  each transaction's full id (a 64-hex-char SHA-256 string) in its response,
  and `gemini-2.5-flash` has extended thinking on by default; for a batch
  that size the model spent its whole output-token budget on reasoning and
  returned a candidate with `finishReason: MAX_TOKENS` and no text part at
  all - which the code silently treated as "nothing to report" instead of
  an error. Fixed two ways: request/response now use a 0-based index into
  the batch instead of the real id (shorter output, and an index is either
  in range or it isn't - no room for a near-miss id to quietly vanish), and
  `thinkingConfig.thinkingBudget` is set to `0` since this task doesn't
  need extended reasoning. A missing/blank text part is now a thrown error
  (surfaced as the `/analysis` error banner) rather than an empty result -
  if this bites again, the error banner will say `finishReason=...`, which
  is the thing to check first.
- Second round: after the above fix, the error banner just said "Gemini
  returned no candidates" - still no real diagnostic. Root cause: the HTTP
  response was decoded straight into `GeminiGenerateContentResponse`
  without checking the status code first. Google's error body
  (`{"error": {code, message, status}}`) has no `"candidates"` key, and
  `candidates` defaults to `emptyList()` when that key's absent - so a 4xx
  (bad/missing API key, API not enabled on the project, quota exceeded,
  malformed request, ...) decoded "successfully" into a response
  indistinguishable from a genuine empty one. Fixed by checking
  `httpResponse.status.isSuccess()` before decoding and, on failure,
  throwing the raw status + response body (Google's actual error message)
  instead. If "no candidates" shows up again with the fix in place, it's a
  real 200-with-empty-candidates case (e.g. safety block) - the error
  banner will include `promptFeedback=...` for that.
- Third round: with the above fix in place, the real error finally
  surfaced: `400 INVALID_ARGUMENT` - `response_schema.items: field predicate
  failed: $type == Type.ARRAY` and `response_schema.items.properties/required:
  only allowed for OBJECT type`. Root cause: `GeminiSchema.type` (default
  `"ARRAY"`) and `GeminiSchemaItem.type` (default `"OBJECT"`) are Kotlin
  default values, and kotlinx.serialization's `Json` omits any field left at
  its default unless `encodeDefaults = true` is set - `geminiHttpClient`'s
  `Json` config never set it, so those `"type"` fields (and
  `responseMimeType`) were silently missing from every outgoing request
  the whole time. This was almost certainly the *actual* root cause behind
  both earlier rounds too - the first two fixes were real bugs and worth
  keeping, but they were masking this one rather than causing the original
  symptom. Fixed by adding `encodeDefaults = true` to `geminiHttpClient`'s
  `Json` config in `Application.kt`. `GeminiCategorizerTest` now has a test
  that inspects the literal outgoing request JSON for these fields, since
  the previous tests only ever mocked the *response* and would never have
  caught a malformed *request*.

## House Knowledge (Facts) gotchas

`GeminiHouseFactExtractor` (`HouseFactExtractor.kt`) is what `POST
/house/documents/upload` calls to turn an uploaded PDF into candidate
`HouseFact` rows - see `.claude/context.md` for the feature's shape and
request/response schema.

- **Not yet exercised against the real Gemini API.** This was built and
  tested (route tests + a mocked-HTTP extractor test) without live network
  access or a real `GEMINI_API_KEY` in the build sandbox. Route/store
  behavior is well covered; the actual Gemini call is not. The first real
  document upload in production is the real test - if it fails, check the
  three gotchas above first (status-code-before-decode, `finishReason`/
  `promptFeedback` on empty output, `encodeDefaults` on schema `"type"`
  fields) since `GeminiHouseFactExtractor` was written to already account
  for all three, but a fourth flavor of the same strictness is plausible.
- **Gemini's `Part` message is a strict oneof** (`text` XOR `inlineData`) -
  learned from the `encodeDefaults` gotcha above rather than hit fresh:
  since `geminiHttpClient`'s shared `Json` config has `encodeDefaults =
  true`, a naive nullable-both-fields `GeminiPart` would emit a spurious
  `"text": null` on the file part and `"inlineData": null` on the text
  part. Sidestepped by building each part as its own non-nullable
  `@Serializable` type and injecting it into the request as a raw
  `JsonElement` (see `FactExtractionTextPart`/`FactExtractionInlineDataPart`
  in `HouseFactExtractor.kt`) rather than turning off `encodeDefaults`
  globally. `HouseFactExtractorTest`'s
  `testRequestBodyCarriesTheDocumentAsInlineDataWithNoStrayNullFields`
  guards this.
- **Inline upload only, capped at 15MB** (`MAX_INLINE_BYTES` in
  `HouseFactExtractor.kt`) - Gemini's `generateContent` caps total request
  size around 20MB and base64 inflates raw bytes ~33%, so this throws a
  clear "too large" error rather than sending a request that fails deep
  inside the HTTP call. A real inspection PDF with embedded photos can
  plausibly hit this; the fix is the Gemini File API's separate upload
  step, not implemented yet - revisit if this actually bites on a real
  document (this is exactly the kind of document the maintainer is
  planning to upload first).
- **Extraction now runs on a background coroutine, not inline in the
  upload request** (`HouseFactExtractionJobManager` in
  `HouseFactExtractionJob.kt`) - originally synchronous, deliberately for
  the first slice, but a real document import started timing out (large
  document, slow Gemini response) once this got real use, hitting exactly
  the Cloud Run request-timeout risk this section used to warn about.
  Fixed with the same async-job-plus-poll pattern `CategorizationJobManager`
  already uses (`CategorizationJob.kt`): `POST /house/documents/upload`
  marks the document `EXTRACTING` and redirects immediately;
  `house-document.ftl` polls `GET /house/documents/{id}/status` every 2s
  and reloads once the document leaves `EXTRACTING` - the poll itself is
  what keeps the coroutine's CPU allocated on Cloud Run between the request
  that launched it and the one that observes it finished, same reasoning as
  the categorize job's polling. Unlike `CategorizationJobManager` (keyed
  per owner, in-memory RUNNING state is the only thing that matters), the
  status poll here reads `HouseDocumentRepository`'s persisted status
  directly rather than the job manager's in-memory map - it's already the
  source of truth `house-document.ftl` renders from. One edge case this
  doesn't cover: if the instance restarts mid-extraction, the persisted
  status is stuck at `EXTRACTING` forever with no coroutine left to finish
  it or mark it `FAILED` - not addressed, since Cloud Run instance restarts
  mid-extraction haven't actually been observed yet.
- **`geminiHttpClient`'s CIO engine has its own built-in 15s request
  timeout, independent of Cloud Run's** - hit right after the fix above
  shipped: moving extraction off the request thread fixed the Cloud Run
  timeout, but the very next real upload still failed, this time with
  `Request timeout has expired [url=...generateContent, request_timeout=
  unknown ms]`. The `unknown` is the tell - the `HttpTimeout` plugin was
  never installed on `geminiHttpClient`, so that's not a
  plugin-configured timeout firing (which would show the real configured
  number); it's Ktor's CIO engine itself, which has its own
  `requestTimeout` (default 15000ms) applied regardless of whether the
  plugin is installed. A large/complex house-document PDF routinely takes
  Gemini longer than 15s to respond to, even with `thinkingBudget = 0`.
  Fixed by setting `engine { requestTimeout = 300_000 }` in
  `geminiHttpClient`'s config (`Application.kt`) - raises the same limit
  for both Gemini callers (categorization and extraction) since they share
  this client. Categorization hadn't hit this yet only because its chunked
  batches happen to finish under 15s each, not because it's exempt - worth
  remembering if a larger chunk size is ever tried there.
- **Failed/stuck documents can be retried or deleted** from
  `/house/documents/{id}` (`house-document.ftl`'s "Retry extraction"/
  "Delete document" buttons, `POST /house/documents/{id}/retry` and
  `/delete` in `HouseRoutes.kt`) - added once the timeout above started
  actually happening in production and there was no way to recover a
  failed upload short of a Firestore console edit. Retry re-downloads the
  already-uploaded GCS blob (`DocumentBlobStore.download`) rather than
  requiring a re-upload - a document row only ever exists once
  `documentBlobStore.upload()` has already succeeded, since upload happens
  before the Firestore row is created (see the upload handler). Retry also
  clears any facts a previous attempt already wrote
  (`HouseFactRepository.deleteForDocument`) before re-running extraction,
  so it can't leave duplicates. Delete removes the Firestore row, its
  facts, and the GCS blob together; `house-document.ftl` hides the button
  while `EXTRACTING` (the route itself doesn't enforce this) since the
  background job would otherwise keep writing facts for a `documentId`
  whose parent record no longer exists.
- **Local dev needs the same ADC as Firestore** - `documentStorageClient`
  (`Application.kt`, `StorageOptions.getDefaultInstance().service`) fails
  the same way `firestoreClient` does without `gcloud auth
  application-default login` (see the Firestore gotcha above) - an
  unhelpful low-level error rather than an obvious "not authenticated" one.
- **This is a deliberately narrow slice of `product_spec.md`'s full Fact
  model** - `HouseFact` only has what/type/source/sourceQuote/
  needsReview/reviewQuestion/homeownerContext, not the full
  status/time/location/confidence/related-components-events-facts-tasks/
  photos shape the spec describes. See `.claude/context.md` for what's
  intentionally deferred and why.
