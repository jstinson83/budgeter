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

## CSS / layout gotchas

- **A flex item with no explicit `min-width` can be blown wide open by a
  deeply-nested descendant's inline `min-width`, even through an
  `overflow-x: auto` container that's supposed to contain it.** Hit on
  `planning.ftl`: `.app-main` (`styles.css`) is a flex item of
  `.app-shell` (`flex: 1`, no `min-width` set) at viewport widths between
  the mobile breakpoint (760px) and its own `max-width` (960px) - a narrow
  desktop window, or a tablet/phone in landscape. Its automatic flex-item
  minimum width defaulted to its content's min-content size, which
  included the wealth chart's `min-width:${wealthChart.widthPx}px` inline
  style (hundreds to 1000+px for a longer horizon/more scenarios) several
  levels down inside `.form-card` > `.wealth-chart-frame` >
  `.wealth-chart-scroll`. Despite `.wealth-chart-scroll` itself correctly
  having `min-width: 0` + `overflow-x: auto` (which *does* contain the
  chart within its own box - scrolls internally, doesn't force
  `.wealth-chart-frame` wider), that containment only applies within
  `.wealth-chart-frame`'s own flex context; it does nothing for
  `.app-main`'s *own* automatic-minimum-size calculation several levels
  up, since `.app-main` never had `overflow` or `min-width` set and so
  fell back to its full subtree's min-content size. Result: `.app-main` -
  and every card inside it - rendered wider than the space actually
  available next to the 220px sidebar, with no `overflow-x: hidden`
  anywhere in the ancestor chain to catch it, so the whole page grew
  wider than the viewport and every card looked "cut off" at the same
  right edge. Fixed by adding `min-width: 0` to `.app-main` directly, the
  same fix pattern - not just relying on it working transitively through
  an unrelated flex/overflow container several levels down. No other page
  has an element with a large explicit inline `min-width` this deep in
  its DOM, which is why this was planning-only ("different behaviour than
  other pages," as reported) - worth checking first if a similar
  "cards/content cut off narrower than the sidebar layout, but fine on
  full mobile width" report comes up again on a page with its own
  wide/scrollable content (a future chart, a wide table, etc.).
- **Debugging technique that found the bug above**: this repo has no dev
  server that runs without real GCP credentials (see the Persistence
  gotchas section), so layout bugs like this can't be checked with a
  quick `run`. Instead: build a static HTML file reusing the real
  `styles.css` and the actual template markup (real classes/structure,
  representative sample data), then screenshot it with the sandbox's
  pre-installed headless Chromium
  (`/opt/pw-browsers/chromium-1194/chrome-linux/chrome --headless=new
  --window-size=W,H --screenshot=out.png file://...`). A plain screenshot
  alone can mislead, though - this sandbox's headless Chrome has no window
  manager and silently floors the layout viewport at roughly 485 CSS px
  regardless of a smaller requested `--window-size` (the screenshot file
  itself is still saved at the requested pixel size, so it just looks
  like clipped/cut-off content, which isn't a real bug - just the render
  being wider than the crop). `--headless=new` (not legacy headless)
  behaves better and this floor only affects widths below roughly 500px,
  not the 760-960px range where the actual bug above lived. To get a real
  signal instead of a screenshot, inject a small diagnostic `<script>` that
  compares `document.documentElement.scrollWidth` against `clientWidth`
  (a real gap means genuine page-level overflow, not just a legitimately
  scrollable inner container) and dumps the offending elements'
  `getBoundingClientRect()` - read it back via `--dump-dom` grepping the
  injected content (e.g. stuffed into `document.title` or a `<pre>`).
  Bisect the CSS itself (copy `styles.css`, patch one property, re-check
  the same diagnostic) rather than guessing from the DOM/CSS alone -
  confirmed the fix in minutes once this loop was in place, after
  significant purely-theoretical back-and-forth about flexbox
  min-content propagation rules that didn't converge on its own.

- **A `hidden` attribute (or `display:none`) does not exclude a form
  control from submission - only `disabled` (or no `name`, or living
  inside a `<fieldset disabled>`) does.** Relevant wherever a form
  progressively shows/hides optional field groups with JS, e.g.
  `planning.ftl`'s Scenario facets (2026-08-23 redesign - see
  `context.md`'s Financial Planning Projections section for the full
  writeup): visually hiding a removed facet's `.facet-block` alone would
  still submit its old values on Save, silently un-removing it server-side
  even though the UI showed it gone. Fixed by wrapping each facet's actual
  inputs in a `<fieldset class="facet-fieldset">` and toggling `.disabled`
  on the fieldset itself (in lockstep with the wrapping div's `hidden`)
  whenever a facet is added/removed - a disabled fieldset's descendant
  controls are excluded from the submitted form entirely, checkboxes and
  selects included. Worth checking first if a future "toggle these fields
  ± JS" pattern reports fields silently persisting after being hidden.

## FreeMarker gotchas

- **Comparing a nullable model value directly (`<#if x == y>` or `x != y`)
  throws `InvalidReferenceException` the moment either side is null** -
  FreeMarker doesn't treat a null map value as falsy/absent for comparison
  purposes the way you'd expect from most templating languages; it's
  treated the same as a genuinely missing key, and evaluating it in a
  non-`??` context is a hard error, not a silent `false`. Hit in
  `projects.ftl` (`option == selectedComponent`, where `selectedComponent`
  is null when no `?component=` filter is active - fixed by guarding with
  `selectedComponent?? && option == selectedComponent`), and once more in an
  early version of `project.ftl` (`entry.text != entry.url`, comparing a
  project entry's text against its URL to avoid showing a bare link's text
  twice - fixed at the time with `entry.text != (entry.url!"")`, using `!`
  to supply a default instead of comparing directly). That specific
  `project.ftl` comparison doesn't exist anymore - it was replaced by
  `ProjectPage.kt`'s `linkifySegments()` splitting text into segments
  instead of comparing it against a separate url field (see `context.md`'s
  chunk 3 writeup) - but the general fix still applies wherever this bites
  next: guard with `??` first, or give the nullable side a same-type
  default via `!` before comparing. If a page 500s with this exception, the
  fix is almost always in the `.ftl` file at the reported line/column, not
  the Kotlin model code that produced the null.

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
- **Cross-file overlap is handled separately, by content, not by
  fingerprint.** The fingerprint above only catches re-uploading the exact
  same file. It does nothing for a *different* export whose date range
  overlaps previously-imported statements for the same account - e.g.
  uploading a fresh "Jan-Aug" export after "Jan-Jun" was already imported:
  every row hashes as new (different fileHash) and would re-import the
  whole overlapping range as duplicates. `withoutContentOverlap()` in
  `TransactionStore.kt` closes this gap as a deliberate, isolated heuristic
  (not a general solution - see its doc comment): claim-once content
  matching keyed on `(accountType, date, description, amount)`, run in
  `FirestoreTransactionStore.addAll()` before the fingerprint check. It
  assumes date/description/amount are stable across re-exports of the same
  underlying data, not that rows appear in any particular order. Rows this
  step drops count toward the same `duplicateCount` the fingerprint check
  reports - the import summary banner doesn't distinguish the two. Mirrored
  in `FakeTransactionRepository` (`TestFixtures.kt`) so tests exercise the
  same behavior; see `testUploadingAWiderStatementSkipsThePreviouslyImportedOverlap`
  in `TransactionRoutesTest.kt`.
- **`withoutContentOverlap()` only prevents *new* cross-import duplicates -
  it doesn't retroactively clean up rows already duplicated before it
  shipped (2026-08-19).** Hit for real: a maintainer report of two stored
  transactions with identical account/date/description/amount that "came
  from separate uploads." The forward dedup logic was confirmed correct
  (tests cover exactly that overlap scenario) - the pair predated the fix.
  There was no way to remove just the stray row (`/transactions/delete-all`
  wipes everything), so `/transactions/duplicates` was added:
  `duplicateGroupsPageModel` (`TransactionPage.kt`) groups stored
  transactions by content key and flags a group only when its members
  *don't* all share one known `Transaction.fileHash` - a same-file group
  (two genuine same-day identical charges) is never flagged, matching
  `withoutContentOverlap`'s own invariant that a duplicate never exists
  within one file. `fileHash` is a new field (`TransactionStore.kt`,
  persisted from `FirestoreTransactionStore.addAll`'s `fileHash` param);
  rows written before this field existed read back `null` and are
  conservatively flagged rather than excluded, since old pre-fix duplicates
  are exactly the population with no fileHash. Flagging is never
  destructive by itself - the page lists every member of a flagged group
  with the same per-row delete (`POST /transactions/{id}/delete`) used
  elsewhere, so a human decides what to remove; a group that mixes a
  legitimate same-day repeat with one real stray duplicate still shows all
  members rather than being auto-collapsed to one (see
  `testDuplicatesReviewPageSurfacesAllMembersWhenAGenuineRepeatIsMixedWithARealDuplicate`
  in `TransactionRoutesTest.kt`).

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
  and `gemini-3.5-flash` has extended thinking on by default; for a batch
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

Extraction is a two-pass pipeline that `POST /house/documents/upload`
kicks off to turn an uploaded PDF into candidate `HouseFact` rows - pass 1
(`HouseFactCandidateExtractor.kt`'s `GeminiHouseFactCandidateExtractor`)
reads the PDF and produces recall-favoring candidates, pass 2
(`HouseFactNormalizer.kt`'s `GeminiHouseFactNormalizer`) reconciles them
into the final fact list, and `HouseFactExtractor.kt`'s
`TwoPassHouseFactExtractor` wires the two together. See `.claude/context.md`
for the full shape and request/response schemas. The gotchas below predate
the pass 1/pass 2 split (from the original single-call
`GeminiHouseFactExtractor`) but apply equally to whichever of the two
Gemini calls is doing PDF/JSON work at the time - pass 1 owns the PDF
`inlineData` part and its size cap, pass 2 is text-only (no PDF, no size
cap) but shares the same schema/response-decoding shape.

- **Now exercised against the real Gemini API - request/response plumbing
  works, extraction completeness is the live concern.** The three gotchas
  below (status-code-before-decode, `finishReason`/`promptFeedback` on
  empty output, `encodeDefaults` on schema `"type"` fields) never actually
  bit in production - both passes ran cleanly against a real 11-page
  structural engineering drawing set (2017 renovation) the first time a
  real document was uploaded. What the mocked-HTTP tests couldn't have
  caught, because it's not a wire-format bug: the first real run came back
  with only 6 facts, missing an entire second new structural system (a
  wood beam on wood posts, parallel to the steel system that *was*
  captured), a design load table, new-material specs, and several specific
  "existing element, out of mandate" callouts. Root cause was pass 1's
  prompt having nothing forcing systematic document coverage - see the next
  gotcha for what's been tried so far. If a future upload comes back
  suspiciously thin, this - not a wire-format failure - is the first thing
  to suspect; check the actual PDF against what came out before assuming
  the pipeline itself is broken.
- **Recall/completeness is tuned empirically against real documents, not
  fully solved.** Two rounds against the same real structural drawing set
  so far: (1) added an "orient yourself before extracting" step to pass 1 -
  state the document's scope in plain language, enumerate every major
  piece/system the document addresses (checking every legend/schedule/
  table, not just the narrative), then extract each piece's constraints and
  decisions - which caught a previously-missed wood support post but not
  the wood beam it actually carries, plus new material/concrete specs, but
  *still* missed the design load table. (2) strengthened pass 1 further:
  a piece named only via a legend/schedule entry (e.g. a column labeled
  "C-4") must also state what member it actually supports, not just restate
  the legend entry; and document-wide inputs that don't belong to any single
  piece (load tables, code editions, blanket material assumptions) are now
  called out as their own explicit category to check for, alongside
  "orient yourself"'s piece enumeration. Also added `HouseDocument.context`
  - an optional homeowner-typed background field at upload time (`house.ftl`
  form, threaded through both passes as grounding, never as a substitute for
  what the document itself states) - since a structural drawing set alone
  frequently doesn't state its own architectural intent (why the work was
  done), only what was built. (3) Round 2 was re-tested: the design load
  table finally came through (the document-wide-inputs fix worked), and a
  homeowner-typed context of "2017 kitchen renovation, removed a load-bearing
  wall" produced the first real plain-language scope fact any round had
  managed - but the same run also *dropped* several facts that had appeared
  in every single prior attempt (the LVL window header, the continuous
  W10x45 beam, the new material grade specs), with no prompt change that
  explains why. This is the important finding: extraction quality isn't
  varying monotonically round to round, it's varying *run to run on the same
  document* - the "easy," previously-reliable facts aren't actually
  reliable. That rules out "just tune the prompt more" as a complete
  strategy on its own, since a prompt fix that helps one run can't be
  confirmed without seeing whether it costs something elsewhere on the next
  run.
- **Pass 1's raw candidate count and content are now logged**
  (`TwoPassHouseFactExtractor.extract()` in `HouseFactExtractor.kt`, added
  after the round 2 retest above made it clear guessing which pass was
  responsible for a gap wasn't working) - every extraction logs pass 1's
  candidate count plus each candidate's status/importance/text at INFO
  level (visible in Cloud Logging with no config change), then a second
  line with pass 2's final fact count against that same candidate count.
  Candidates themselves are never persisted anywhere else - this log line
  is the only record of what pass 1 actually produced before pass 2 got to
  it. When recall looks thin on a future upload, check this log first:
  if a missing fact's underlying candidate isn't in pass 1's list, the bug
  is in pass 1's prompt/recall; if it *is* there but didn't survive to the
  final fact list, the bug is in pass 2's reconciliation/filtering instead
  of another pass-1 prompt tweak.
- **Round 4, with the logging above in place: pass 2 is confirmed not to be
  the bottleneck.** Two fresh real-document runs (one with homeowner
  context, one without) logged cleanly: 10 candidates -> 9 facts, and 9
  candidates -> 9 facts. The single drop was a legitimate merge (two
  candidates both describing the W10x30/footing assembly, folded into one
  fact) - not real information loss. Across both runs pass 2 preserved 18
  of 19 candidates. This settles what the round-2/round-3 gotchas above
  could only speculate about: the wood-beam-on-wood-posts system that has
  never once appeared in a final fact list also never appeared as a pass-1
  candidate in either run - a genuine pass-1 recall miss, not pass-2
  pruning. Prompt wordsmithing (the "orient yourself" step, the
  piece-supports-what and document-wide-inputs strengthening) had already
  been tried three times on this exact gap without moving it, which is why
  the next change was a different kind of lever entirely - see below.
- **Pass 1 now writes a `documentWalkthrough` before extracting candidates**
  (`CandidateBatch` in `HouseFactCandidateExtractor.kt`) - still one Gemini
  call, not a third pass. The response schema changed from a bare array to
  an object with two fields, `documentWalkthrough` (a systematic,
  section-by-section, page-by-page description of everything in the
  document - narrative text, tables, legends, and what each drawing shows)
  and `candidates` (the same array as before, now nested). Schema field
  order pushes Gemini to write the walkthrough first, in the same way
  extended thinking would, but as a normal bounded schema field rather than
  opaque reasoning tokens - deliberately chosen over just raising
  `thinkingBudget` above its current `0` (see the categorization gotcha
  above) because a required field is visible and loggable, and doesn't
  carry the same "silently exhausts the output budget and returns
  `MAX_TOKENS` with nothing" risk unbounded extended thinking already bit
  this codebase with once. `TwoPassHouseFactExtractor.extract()` logs the
  walkthrough as its own INFO line, separate from the candidate list, so a
  future gap can be diagnosed more precisely than "pass 1 vs pass 2": if
  something is missing from the walkthrough too, pass 1 never noticed it
  at all; if it's in the walkthrough but never became a candidate, pass 1
  noticed but failed to extract it - two different bugs. **Not yet tested
  against a real document** - the next real upload/retry is what tells us
  whether this actually moves the wood-system gap, or whether it needs the
  `thinkingBudget` change too.
- **Round 5: retested against the real document, wood system still
  missing.** Two fresh runs (with and without homeowner context) after the
  walkthrough change above - both came back clean and complete on
  everything else (LVL header, both steel beams, all HSS columns/footings,
  the design load table, both assumptions all present in both runs), but
  the wood-beam-on-wood-posts system was absent from both final fact lists
  again. That's six consecutive real-document attempts now. The next
  diagnostic step is checking whether the *walkthrough itself* mentions the
  wood system on a future run - if it doesn't, this has stopped being an
  extraction-logic problem and become a vision/perception one (the model
  isn't reading that part of the drawing at all, which no amount of
  candidate-extraction prompting can fix); if the walkthrough does mention
  it but no candidate follows, it's still a fixable extraction-logic gap.
  Getting to that answer used to require pulling Cloud Logging output by
  hand - see the next gotcha for why that friction is gone now. Separately,
  footing E1's dimensions have been read three different ways across three
  different attempts (6'×6'×12", 8"×8"×12", 18"×18"×12") - a real
  OCR/reading-fidelity issue on a small table value, distinct from the
  recall question above and not yet investigated.
- **Extraction debug notes are now visible on the document page itself**,
  not just Cloud Logging (`HouseDocument.debugNotes`, populated from
  `HouseFactExtractor.ExtractionResult.debugNotes` by
  `HouseFactExtractionJobManager` right after a successful extraction,
  rendered in a collapsible "Extraction debug info" section on
  `house-document.ftl`). Added because the round 4/5 diagnostic loop (ask
  the maintainer to open Cloud Logging, filter, and paste lines back) was
  real friction for what's otherwise a self-serve retry loop. Contains the
  same text as the three INFO log lines
  (`TwoPassHouseFactExtractor.extract()` still logs them too, unchanged) -
  the document walkthrough, the full candidate list with status/importance,
  and the pass 1 -> pass 2 count. Only written on a *successful* extraction
  (same as facts) - a failed retry leaves whatever debug notes an earlier
  successful attempt produced rather than clearing them, which is still
  useful context but can be stale; not addressed further since it hasn't
  been a real problem yet.
- **Gemini's `Part` message is a strict oneof** (`text` XOR `inlineData`) -
  learned from the `encodeDefaults` gotcha above rather than hit fresh:
  since `geminiHttpClient`'s shared `Json` config has `encodeDefaults =
  true`, a naive nullable-both-fields `GeminiPart` would emit a spurious
  `"text": null` on the file part and `"inlineData": null` on the text
  part. Sidestepped by building each part as its own non-nullable
  `@Serializable` type and injecting it into the request as a raw
  `JsonElement` (see `CandidateTextPart`/`CandidateInlineDataPart` in
  `HouseFactCandidateExtractor.kt`) rather than turning off
  `encodeDefaults` globally. Only pass 1 needs this - pass 2
  (`HouseFactNormalizer.kt`) only ever sends a single text part, so its
  `FactNormalizationPart` doesn't have an `inlineData` field to spuriously
  null out and can use the shared `encodeDefaults` config directly, same as
  `ComponentSummaryPart`. `HouseFactCandidateExtractorTest`'s
  `testRequestBodyCarriesTheDocumentAsInlineDataWithNoStrayNullFields`
  guards pass 1's half of this.
- **Inline upload only, capped at 15MB** (`MAX_INLINE_BYTES` in
  `HouseFactCandidateExtractor.kt`) - Gemini's `generateContent` caps total
  request size around 20MB and base64 inflates raw bytes ~33%, so this
  throws a clear "too large" error rather than sending a request that fails
  deep inside the HTTP call. A real inspection PDF with embedded photos can
  plausibly hit this; the fix is the Gemini File API's separate upload
  step, not implemented yet - revisit if this actually bites on a real
  document (this is exactly the kind of document the maintainer is
  planning to upload first). Only applies to pass 1 - pass 2 never sees the
  PDF, only pass 1's candidate list, which is orders of magnitude smaller.
- **Two sequential Gemini calls means roughly double the wall-clock time**
  per document compared to the original single-pass extractor - pass 2
  can't start until pass 1's response is fully in hand. Each call still has
  its own 300s CIO timeout (see below), so a pathological document could
  now take up to ~10 minutes rather than ~5 before the job manager marks it
  `FAILED`. Not addressed with a shorter per-call budget or a combined
  timeout, since this hasn't been a real problem yet - worth revisiting if
  extraction starts timing out in a way the single-pass version didn't.
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
