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

`product_spec.md`'s "House Knowledge" section (added 2026-08-11) is a much
deeper spec for the home-specific slice of the Knowledge/Documents model:
a `Fact` object with typed provenance/evidence/epistemic-status, events and
house components as first-class objects, and a document-upload →
fact-extraction MVP workflow. Not implemented yet — this is vision only, same
status as the rest of `product_spec.md` beyond the shipped
CSV-import/categorization/dashboard features below.

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

- CSV is headerless, one format only - TD's real chequing/savings account
  export, confirmed against an actual statement (`CsvTransactionParser`):
  `date,description,moneyOut,moneyIn,balance` (5 columns; anything past
  column 4 is ignored). Exactly one of `moneyOut`/`moneyIn` is populated
  per row; the other is blank. `moneyOut` becomes a negative amount
  (money out, red in the UI), `moneyIn` a positive one (money in, green).
  Balance is discarded. A row with both or neither populated is a parse
  error, not silently guessed. An earlier 3-4 column single-signed-amount
  format (for synthetic test data and a guessed "credit-card-style"
  export) was dropped - the maintainer confirmed only the 5-column TD
  format is actually needed, and it had a real design problem anyway: its
  sign convention tracked *debt owed* (charge=+, payment=-), inverted from
  the 5-column format's *cash in the account* convention (out=-, in=+) -
  mixed together the same color meant opposite things.
  - Date accepts either ISO-8601 (`yyyy-MM-dd`) or `MM/dd/yyyy`
    (`CsvTransactionParser.supportedDateFormats`) - the latter is TD's
    real date format.
  - `amount` is always one signed `Double` in `ParsedTransaction` -
    the debit/credit split only exists in the raw CSV and is collapsed to
    a sign during parsing.
  - Display makes the sign explicit rather than relying on color alone:
    `formatSignedAmount`/`amountClass` (`TransactionPage.kt`, shared with
    `AnalysisPage.kt`) prefix positive amounts with `+` on top of the
    negative sign `Double` formatting already provides, plus the existing
    red/negative vs. green/positive CSS classes.
- `TransactionRepository.deleteAll(ownerId)` wipes all of one owner's
  transactions (and, since categories/analysis are computed from
  transactions rather than a separate collection, effectively "all
  analysis" too) - exposed as a "Delete all transactions" button on
  `/transactions` while the import format is still being validated
  against real statements, so a bad import doesn't have to be untangled
  row-by-row (dedup only skips existing docs, it never overwrites - see
  the Firestore gotchas section in CLAUDE.md).
- Bad rows are skipped, not fatal — `CsvTransactionParser.parse` returns
  both the successfully parsed transactions and a list of per-row errors;
  the import route reports "Imported N, skipped M" rather than
  all-or-nothing failing.
- Dedup on re-upload: identity is `(ownerId, fileHash, rowNumber)`, not row
  content, so re-uploading the exact same file is idempotent while two
  distinct same-day/same-amount/same-description transactions within one
  file both still get kept. See `CLAUDE.md`'s Firestore gotchas section for
  the mechanics and the formatting-change tradeoff.
## Second feature: Gemini spending analysis

`/analysis` — category totals for a calendar month, paged one month at a
time (Prev/Next), with a button to categorize any new transactions via
Gemini. It's also the app's landing page: `GET /` (`Application.kt`) just
redirects here rather than rendering its own content - `home.ftl` was
deleted as unused once nothing pointed at it. Clicking a category total
drills into `/analysis/category/{slug}?year=&month=` (`{slug}` is the
category's lowercase enum name, or the literal `uncategorized` for a null
category), listing that category's individual transactions for the same
month, with the same category total repeated at the top of the page
(`analysisCategoryPageModel` in `AnalysisPage.kt` recomputes it from the
same filtered transaction list, rather than passing it through from
`/analysis`) -
`AnalysisRoutes.kt`'s `resolveYearMonth` is the single place both `GET`
routes resolve `year`/`month` from. Originally shipped as rolling "last
week/month/year" windows from `LocalDate.now()`; replaced with
calendar-month paging plus the drill-down page since the rolling windows
were too coarse to actually browse spending by. The maintainer has said
they want a persistent, precomputed version later (a cron-job-like thing,
computing and storing category totals instead of recomputing per page
load); not built yet.

- **Categorization is fully automatic now - there is no button and no
  `POST /analysis/categorize`** (`AnalysisRoutes.kt`, `CategorizationJob.kt`).
  `CategorizationJobManager.categorize(ownerId)` owns the whole pass and is
  called unconditionally at the top of every `GET /analysis` (loading the
  page is what replaces the old button) - a cheap no-op once there's
  nothing left to fix. Three steps, in order, each covered in more detail
  where noted:
  1. **Transfer/interest categorization** (`TransferMatcher` - see Third
     feature below for the full mechanics). Synchronous, deterministic, no
     network call, and scans the owner's *entire* transaction history every
     time regardless of current category - not scoped to what's newly
     pending.
  2. **Household rules** (`CategorizationRuleMatcher`), applied to whatever
     the step above didn't claim and is still `uncategorized`. Also
     synchronous and free.
  3. **Gemini**, for whatever's left after steps 1-2 - the only network-bound
     step, so the only one backgrounded (below).
  If steps 1-2 alone clear everything pending, their combined result is
  recorded as an already-`DONE` job, so it shows up as the message banner
  on that very page load with no Gemini call at all. Otherwise step 3 runs
  on an application-scoped coroutine (`CategorizationJobManager`, one job
  per `ownerId` at a time, in-memory) that outlives the request. `GET
  /analysis` shows a "Categorizing…" panel while a job is `RUNNING`
  (checked via `CategorizationJobManager.consumeTerminal`, which also folds
  a just-finished job's message/error into the page once, like the
  existing query-param message/error banners, then forgets it - a `FAILED`
  job is not remembered past that one display, so the very next page load
  retries it automatically) and an inline script in `analysis.ftl` polls
  `GET /analysis/categorize/status` (JSON, `CategorizationJobStatusResponse`
  - the app's first JSON endpoint) every 2s, reloading the page once the
  job leaves `RUNNING`. This was a deliberate choice over adding real
  infrastructure (a queue, Cloud Tasks, etc.): Cloud Run only allocates CPU
  to an instance while it has a request in flight (this service doesn't
  have "CPU always allocated" on), so the job only makes real progress
  while some request - the page load that started it, or one of the status
  polls - is being served. The polling isn't just for the UI; it's what
  keeps the job's CPU allocated between Gemini calls. The maintainer has
  used this pattern successfully on other Cloud Run services before. The
  maintainer's own reasoning for dropping the manual step: categorization
  is idempotent (steps 2-3, via `uncategorized(ownerId)` below - step 1 is
  its own kind of idempotent, see Third feature) so re-triggering it costs
  nothing when there's nothing new, and there's no legitimate reason to
  ever *want* transactions left uncategorized.
  - A Gemini job already `RUNNING` for this owner only blocks *launching a
    new* Gemini job (step 3) - steps 1-2 always run to completion on every
    `categorize()` call regardless of another job's status, so an unrelated
    in-flight Gemini pass never blocks transfer detection or rules. Bit by
    this exact gap once: the `RUNNING` guard originally sat at the top of
    `categorize()`, before steps 1-2 ran at all - see Third feature's
    gotcha writeup for the full story.
  - Gotcha hit while building this: `CategorizationJobManager`'s
    background coroutine used to call `categoryStore.all(ownerId)` itself
    (for the Gemini leg's allowed-category list) *concurrently* with
    `GET /analysis`'s own `categoryStore.all(ownerId)` call for rendering.
    `CategoryRepository.all()` lazily seeds `BUILT_IN_CATEGORIES` on first
    read per owner with a plain check-then-write (`FakeCategoryRepository`
    in tests; `FirestoreCategoryStore` should be checked for the same
    pattern if this ever bites in production) - two concurrent first-reads
    for the same owner both see "not seeded yet" and both write the full
    built-in set, creating duplicate `Category` rows with the same id.
    Fixed by having `categorize()` fetch the category list synchronously
    *before* launching the background coroutine, so it's sequenced before
    (not concurrent with) the route handler's own call. Caught by
    `CategoryRoutesTest`'s disable-category test, which started failing
    ~100% of the time once this feature's background job made the race
    trivially easy to hit (previously only two real concurrent requests -
    e.g. two browser tabs - could trigger it).
- `GeminiTransactionCategorizer.categorize()` also now chunks internally
  (`BATCH_SIZE = 40`, `GeminiCategorizer.kt`) instead of sending every
  pending transaction in one Gemini call - this is what actually fixes
  "categorization fails when there are too many transactions": a big
  enough batch's JSON *response* (not the request) can exhaust the model's
  output-token budget and come back as a `MAX_TOKENS` cutoff with no text
  part (see CLAUDE.md's Gemini categorization gotchas - this already bit
  at ~120 transactions in one call). Each chunk is its own request with its
  own local 0-based indices, merged into one id-keyed result; the public
  `categorize()` signature is unchanged. The background-job piece above
  and this chunking piece are independent fixes for the same underlying
  symptom - chunking is what makes a large batch succeed at all, the job
  is what keeps a many-chunk batch from being cut off by Cloud Run's
  request timeout.

- `Transaction.category: String?` (`TransactionStore.kt`) is null until
  categorized, and holds a `Category.id` (see "Fifth feature" below) rather
  than a fixed enum as of the categories/rules management page - categories
  are per-owner and user-editable now, not a single compile-time set.
- `TransactionRepository.uncategorized(ownerId)` (default method: filters
  `all(ownerId)` in memory) is what makes rules/Gemini (steps 2-3 above)
  idempotent - once a transaction has a category it's permanently excluded
  from both, so loading `/analysis` repeatedly never re-analyzes (or
  re-bills Gemini for) the same transaction twice. Step 1 (transfer/interest
  categorization) deliberately does *not* use this - see Third feature.
- `GeminiTransactionCategorizer` (`GeminiCategorizer.kt`) calls the Gemini
  API directly over REST (`generativelanguage.googleapis.com`, model
  `gemini-3.5-flash`) using a `responseSchema` that constrains output to
  `{index, category}` pairs (index into the request's transaction list, not
  the transaction's real id - see CLAUDE.md gotcha below) from the caller's
  own active category set (passed into `categorize()`, not hardcoded - see
  "Fifth feature" below) - no Google AI SDK dependency added, same "just use
  ktor's HttpClient" pattern as the OAuth userinfo call in `Auth.kt`. Requires the
  `GEMINI_API_KEY` env var, now set on the deployed Cloud Run service (see
  `CLAUDE.md`'s deploy pipeline section - same manual-env-var pattern as the
  OAuth secrets) - categorization works end to end in production. Thinking
  is explicitly disabled (`thinkingConfig.thinkingBudget = 0`) - see
  CLAUDE.md gotcha.
- The viewed period is always one calendar month (`year`/`month` query
  params, default to the current month) - no rolling-window or
  all-time option.

## Third feature: bank/credit-card/LOC account labeling + transfer matching

Transactions now carry `accountType: AccountType` (`BANK`, `CREDIT_CARD`, or
`LOC`, `TransactionStore.kt`) - the CSV import format and sign convention are
identical across all three (see `CsvTransactionParser.kt`), so this is purely
caller-supplied metadata, chosen via a radio selector on the `/transactions`
upload form. Defaults to `BANK` when the field is absent (an API call
bypassing the form); a present-but-invalid value is a hard error, not
silently coerced. Firestore documents written before this field existed
read back as `BANK` (every transaction imported before now came from the
original bank-only TD export).

The reason for labeling accounts: paying a credit card (or a line of
credit) from the bank account shows up as two separate transactions (a bank
outflow, a credit-card/LOC inflow) that both need to be excluded from
spending/income analysis rather than double-counted. `TransferMatcher.kt`
recognizes these deterministically (not via Gemini), on fixed TD
statement-generator templates confirmed against real statements.

**Single-row, not pair-matched** (redesigned from an earlier pair-matching
version - see the gotcha below for why): `TransferMatcher.categoryFor(tx)`
decides a transaction's category from its own description/accountType/amount
alone, with no need to find its other leg first. A `TFR-TO C/C` bank row is
a transfer whether or not its credit-card-side `PAYMENT - THANK YOU`
counterpart has even been imported yet - each of these markers is a fixed,
unambiguous TD template, so the description match alone is definitive:

- **Bank → credit card**: bank leg's description contains `TFR-TO C/C` ->
  `TRANSFER_CATEGORY_ID`. Credit-card leg's description contains
  `PAYMENT - THANK YOU` -> `TRANSFER_CATEGORY_ID`. Credit cards only ever
  receive payments (one direction).
- **Bank ↔ LOC**: unlike a credit card, a LOC transfer goes either
  direction (draw from it or pay it down). The bank leg can't be matched
  against a fixed account number the way `C/C` is fixed for a credit
  card - only that *something other than* `C/C` follows `TFR-TO`/`TFR-FR`,
  since `C/C` specifically means it's a credit-card payment, not a LOC
  transfer. The LOC leg's description just needs to contain `TFR-TO`
  (draw) or `TFR-FR` (pay-down).

**LOC interest is the one exception**, still requiring a matched pair
(`TransferMatcher.matchInterestPairs`, amount equality + dates within
`DATE_WINDOW_DAYS` = 5, mutually-unique matches only): unlike every marker
above, the bank-side `PYT TO: <account>` marker is TD's *general-purpose*
bill/EFT payment template - used for a payment to any payee, not
specifically one covering LOC interest - so a lone `PYT TO:` row isn't
definitive the way `TFR-TO C/C` is. Only a `PYT TO:` row that actually
lines up in amount and date with a real LOC `interest` charge is confirmed
to be the one covering it. The interest charge itself is real spending
(unlike a transfer) but booked as two ledger entries for one economic
event, so only the LOC leg is categorized `INTEREST_CATEGORY_ID` (a real,
seeded `Category` - see Fifth feature); the bank leg is excluded like any
other transfer (`TRANSFER_CATEGORY_ID`) so the same interest isn't counted
twice.

All transfer/interest categorization (`TRANSFER_CATEGORY_ID`, `"TRANSFER"`,
`CategoryStore.kt`) is excluded from `/analysis` entirely, not just grouped
into its own bucket. Runs automatically as the first step of every
`categorize()` pass (`CategorizationJob.kt`), which itself now runs on
every `GET /analysis` load rather than a manual button (see Second feature
above). Currently assumes at most one bank account, one credit card, and
one LOC (no per-account scoping beyond `accountType`); revisit if a second
account of any of these types is ever added.

`categorize()`'s candidate pool for this is the owner's *entire* transaction
history (`transactionStore.all(ownerId)`), not just this pass's
uncategorized ones, and not windowed by date - matched regardless of
current category. Deliberately not batched/paginated even though that
means a full scan every page load: `all(ownerId)` is already being fetched
unconditionally by the `GET /analysis` handler itself for rendering, so
this doesn't cost an extra Firestore read, and the matching itself is
in-memory and fast (no network call, unlike Gemini) - repeating it on every
load is what gives this the same "keeps trying until it converges" property
Gemini's background-job polling gives categorization generally, without a
second job type or poll loop. A diff check (only rows whose category is
actually changing get written) keeps this from re-billing a wasted
Firestore write for every already-correct transfer on every single load.
Sized for one household's realistic transaction volume (hundreds to a few
thousand rows over years) - revisit with real batching/pagination only if
that assumption ever stops holding, not preemptively.

Gotcha, now resolved by the single-row redesign above but worth keeping for
the reasoning: the *original* version of this feature matched transfers in
pairs (`matchPairs`, mutual-uniqueness required, both legs needed
simultaneously to categorize either one) as a deliberate anti-false-positive
safeguard. In practice this caused the actual bug it was meant to prevent:
whenever only one leg of a real transfer existed yet - the routine case,
since bank and credit-card statements are uploaded in separate sessions -
that leg fell through to Gemini and got bucketed as ordinary income/expense
instead of waiting as "transfer, unmatched." Once mis-categorized this way,
nothing ever revisited it without a full-history rescan, which several
narrower fixes (a `pending`-anchored date window, then a viewed-month
union) tried and failed to fully close - there was no way to bound "how
stale could a mismatch be" that didn't eventually re-open a gap. The fix
was to stop pairing altogether for the markers that don't need it (every
one except LOC interest, per above) and decide each row's category from its
own description alone - eliminating the whole class of "haven't found its
partner yet" bug rather than chasing each way that gap could reopen.
Separately, once categorization became automatic (see Second feature
above), a background Gemini job left `RUNNING` for an unrelated owner used
to block transfer/rule matching entirely (the guard sat at the top of
`categorize()`); fixed by moving that guard to only gate the Gemini launch,
its original purpose, so transfer matching always runs to completion
regardless of another job's status.

### `INVESTMENT` category

Tracks money moved into an investment/brokerage account, without modeling
investment accounts/holdings themselves - deliberately a shallow "how much
did I invest" number, not a portfolio tracker. Unlike `TRANSFER`, it's a
normal category: manually assignable via the recategorize dropdown and
guessable by Gemini, and it shows its own bucket in `/analysis`'s category
totals like any other category today. The one deliberate special case is
future: when a spent-vs-earned/savings-rate calculation gets built (doesn't
exist yet - see Second feature above), it should treat `INVESTMENT` as
neutral (neither income nor expense) and exclude it from that split, the
same way `TRANSFER` is excluded from `/analysis` entirely now.

## Fourth feature: manual recategorization + household rules

Gemini inevitably dumps some recurring merchants into `OTHER` (or leaves
them uncategorized) that the household knows how to bucket better than a
guess. Each transaction row on an `/analysis/category/{slug}` drill-down
page (`analysis-category.ftl`) has an inline "Recategorize" form: pick a
target category, a match type (`EXACT` or `SUBSTRING`), and a pattern text
box pre-filled with the transaction's own description (editable, so a
`SUBSTRING` rule can be trimmed down to just the stable merchant-name
fragment of a description that otherwise varies per transaction - order
numbers, per-visit reference codes, etc.).

Submitting it (`POST /analysis/recategorize`, `AnalysisRoutes.kt`) does two
things: recategorizes that one transaction immediately (it's often already
categorized as `OTHER`, not null, so it wouldn't be picked up by the
uncategorized-only `/analysis/categorize` pass on its own), and saves a
`CategorizationRule` (`CategorizationRuleStore.kt` -
`pattern`/`matchType`/`category`, Firestore collection
`categorizationRules`, single-field `ownerId` equality query - no composite
index needed, unlike `TransactionRepository.all()`). The target category
must be one of the owner's own active `Category` rows (see "Fifth feature"
below) - `TRANSFER` is never offered since it isn't a real `Category` row to
begin with, and a disabled category is rejected too.

Saved rules apply **going forward only**, not retroactively: on every
future `/analysis/categorize` run, `CategorizationRuleMatcher.kt` checks
the household's saved rules against that run's still-uncategorized
transactions - after `TransferMatcher`, before Gemini, same "deterministic
and free beats a guessed API call" reasoning. A transaction matching
multiple rules takes the first match in `all(ownerId)`'s return order;
rules aren't required to be mutually exclusive. Transactions already sorted
into some other category before a rule existed are untouched by that rule
unless recategorized by hand again - deliberately, so creating a rule can't
silently rewrite past analysis totals.

`transactionId` on the recategorize form is client-supplied (unlike the ids
`TransferMatcher`/the categorizer work with, which only ever come from that
owner's own `uncategorized()` list) - the handler re-fetches
`transactionStore.all(ownerId)` and confirms the id belongs to the caller
before touching it, rather than trusting the form value directly.

## Fifth feature: categories + rules management page (`/categories`)

Replaced the old fixed `TransactionCategory` enum with `Category`
(`CategoryStore.kt`) - real per-owner Firestore rows (collection
`categories`, single-field `ownerId` equality query, same
no-composite-index shape as `categorizationRules`) instead of a
compile-time set, so a household can add its own categories from the UI.
`Transaction.category` and `CategorizationRule.category` are now plain
`String` ids (a `Category.id`) rather than the enum.

- **Built-ins seeded lazily, per owner, on first read**: `BUILT_IN_CATEGORIES`
  (groceries, alcohol, dining out, entertainment, mortgage, house expenses,
  utilities, transportation, health, subscriptions, clothing, education,
  income, investment, interest, other) gets written as real `categories`
  documents the
  first time `CategoryRepository.all(ownerId)` finds none for that owner.
  Ids intentionally match the old enum constant names exactly
  (`"GROCERIES"`, `"DINING_OUT"`, ...) so `Transaction`/`CategorizationRule`
  documents written before this feature keep resolving without a data
  migration - this was the main constraint the whole design worked backward
  from.
- **Custom categories get a generated id**: `slugify()`/`uniqueSlug()` turn
  a label into the same uppercase-snake-case shape as the built-ins (e.g.
  "Hobby Supplies" → `HOBBY_SUPPLIES`), with a `_2`/`_3`/... suffix on
  collision. This keeps `/analysis/category/{slug}` working unchanged - the
  slug is just `id.lowercase()`, same as it was for the enum's `.name`
  before.
- **Categories are disabled, never deleted**: the maintainer explicitly
  chose this over full rename/delete when this feature was scoped (delete
  would orphan existing transactions/rules pointing at it). Disabling only
  removes a category from *future* assignment - it drops out of the
  `/analysis` recategorize dropdown, the `/categories` "Add rule" dropdown,
  and Gemini's allowed schema values (`categoryStore.all(ownerId).filter {
  it.active }` at each of those three call sites). Transactions and rules
  already pointing at a disabled category are completely unaffected - a
  disabled category's own `/analysis/category/{slug}` drill-down still
  works.
- **`TRANSFER` stays outside this system entirely** - it's not a `Category`
  row, seeded or otherwise (`TRANSFER_CATEGORY_ID` is just a string
  constant `CategoryStore.kt` exports for `TransferMatcher.kt` and the
  `/analysis` filters to use). This was a deliberate maintainer decision:
  transfer-matching is fully automatic and never user-facing today, so
  there's nothing to manage on this page. If that ever needs to change,
  promoting it to a real (permanently-disabled-by-default?) `Category` row
  is the likely path, not the reverse.
- **`GeminiTransactionCategorizer.categorize()` takes the category list as a
  parameter now** (`categories: List<Category>`) instead of deriving it from
  a hardcoded enum minus `TRANSFER` - the caller (`AnalysisRoutes.kt`)
  passes the owner's own active categories, so the Gemini schema's allowed
  values are per-owner and per-call rather than global.
- **`CategorizationRuleRepository` gained `update`/`delete`** (previously
  add-only, created only as a side effect of `/analysis/recategorize`).
  `/categories` (`CategoryRoutes.kt`, `categories.ftl`) is now full CRUD for
  rules - add, edit (pattern/matchType/category), delete - alongside the
  category list/add/toggle-active UI. Both rule-creation paths
  (`/analysis/recategorize` and `/categories/rules`) apply the same "target
  must be one of the owner's own active categories" validation.

## Sixth feature: dashboard landing page (`/`)

`/` now renders a real summary page (`DashboardPage.kt`/`DashboardRoutes.kt`,
`dashboard.ftl`) instead of just redirecting to `/analysis` - the first
"section" of the Household OS dashboard concept from `product_spec.md`, with
room for other domains (assets, maintenance, ...) to become additional
sections later rather than additional top-level pages. `/analysis` is
unchanged and still does the calendar-month category breakdown/drill-down;
the dashboard links out to it rather than replacing it.

Two parts, both computed on the fly from `all(ownerId)` (no new
precomputed/cron layer, same "quick version first" posture as `/analysis`):

- **Money in/out**: deterministic, not Gemini-generated - a 3-month net-change
  bar series plus a single total summarizing it (`monthlyNetChange`, same
  TRANSFER/INVESTMENT exclusions as `/analysis`'s net change; the total is
  just `netChangeSeries.sumOf { it.second }`, shown above the bars the same
  way `/analysis`'s own net change is displayed), categories whose
  current-month spend jumped meaningfully vs. their own trailing 3-month
  average (`categoryMovers`, with a $20 baseline floor so a tiny category's
  noise doesn't read as a dramatic swing), and the single biggest expense
  this month (`biggestExpense`). Deliberately just 3 months, not a longer
  lookback (`NET_CHANGE_TREND_MONTHS` in `DashboardPage.kt`) - this whole
  section only reasons over whatever transactions have actually been
  imported, with no assumption that every account is fully linked/imported,
  so a short window keeps that promise honest.
- **Coverage**: per account type, earliest/latest transaction date, days
  since the last import (flagged stale past 35 days), and any internal gap
  longer than 21 days between consecutive transactions surfaced as a
  "possible missing statement" - a proxy, not a certainty, since a real
  account can legitimately go quiet for a few weeks (`accountCoverage`).

**A net-position (total assets/debt across Bank/Credit Card/LOC) section was
built, shipped, found to have a real sign-handling bug, fixed, and then
pulled entirely** - not for the bug, but because it inherently assumes every
account the household holds has been imported with balance data, which
isn't true in practice (some accounts just aren't linked/uploaded), so the
figure quietly understates net worth in a way that's not obvious from the
page itself. See `CLAUDE.md`'s dashboard gotcha for the sign-convention
lesson (TD signs credit-card/LOC balances the same way `Transaction.amount`
is - negative = money owed - not as a positive "amount owed" to subtract),
kept in case this is revisited later. The CSV's balance column capture that
this depended on (`Transaction.balance`, `ParsedTransaction.balance`) was
reverted along with it - the CSV's 5th column goes back to being parsed and
discarded, same as before this feature.

## Seventh feature: House Knowledge (document upload + fact extraction)

The first real slice of `product_spec.md`'s "House Knowledge" section
(added 2026-08-11 as vision, implemented 2026-08-11) - the "MVP: House
Knowledge" workflow from that section: upload a document, extract
candidate facts via Gemini, resolve the ambiguous ones. New top-level
`/house` area, linked from `_nav.ftl`.

- **`HouseDocument`** (`HouseDocumentStore.kt`, Firestore collection
  `houseDocuments`) is upload metadata/status
  (`UPLOADED`/`EXTRACTING`/`EXTRACTED`/`FAILED`) - the raw PDF bytes live
  separately in GCS (`DocumentBlobStore`/`GcsDocumentBlobStore.kt`), not in
  Firestore, since Firestore documents cap at 1MB and real inspection PDFs
  routinely exceed that. Object path is `{ownerId}/{uploadId}/{filename}`,
  `uploadId` a fresh random UUID rather than the eventual Firestore
  document id (avoids a two-phase create-then-patch just to learn an id).
  Bucket name comes from `HOUSE_DOCUMENTS_BUCKET` (see `CLAUDE.md`'s deploy
  pipeline section - not yet created in GCP as of this feature landing).
  Also carries an optional `context: String?` - free-text background the
  homeowner can type into `house.ftl`'s upload form (e.g. "this is the 2017
  kitchen renovation, we removed the wall between the kitchen and dining
  room"). Added once real-document testing showed that documents like
  structural engineering drawings often don't state their own architectural
  intent - the drawings show *what* was built, not *why*. Threaded through
  as `documentContext` to both extraction passes (see below) as grounding,
  not document content - each pass's prompt is explicit that it must not
  override or be treated as equivalent to what the document itself states.
  `HouseRoutes.kt`'s upload handler reads it from a `PartData.FormItem`
  named `context` alongside the file part; retry re-passes the persisted
  `document.context` rather than requiring it to be re-typed.
- **`HouseFact`** (`HouseFactStore.kt`, Firestore collection `houseFacts`)
  is a deliberately narrow slice of the spec's full `Fact` model:
  what/type/component/status/importance/sourceQuote/sourceLocation/
  evidenceType/needsReview/reviewQuestion/homeownerContext - no
  time/confidence/related-components-events-facts-tasks/photos yet.
  `FactType` started as the spec's 9-value taxonomy (Observation/Condition/
  Diagnosis/Decision/Event/Specification/MaintenanceRequirement/Warranty/
  Unknown) and has since grown two more - `Assumption` (a value/condition
  assumed for design/calculation/planning, as opposed to measured or
  verified) and `ScopeLimitation` (something the document explicitly says
  was outside the inspection/investigation/professional mandate, as opposed
  to `Unknown`'s "tried to determine and couldn't"). `FactStatus`
  (Existing/New/Modified/Removed/Proposed/Unknown/NotApplicable),
  `Importance` (High/Medium/Low), and `EvidenceType` (Documented/Measured/
  Observed/Designed/Assumed/Reported/Inferred) were added together when
  extraction moved to the two-pass pipeline below - `sourceLocation` (page/
  drawing number/section) came back at the same time, having briefly been
  cut from the original single-pass prompt (see `CLAUDE.md`'s House
  Knowledge gotchas) once there was a real field to hold it in rather than
  burying it in prose. Deferred fields are deferred because there's no real
  usage to design them against yet, not because they were rejected - see
  `product_spec.md`'s House Knowledge section for the target shape this
  should grow toward.
- **Extraction is a two-pass pipeline**, not one Gemini call:
  `HouseFactCandidateExtractor.kt`'s `GeminiHouseFactCandidateExtractor`
  (pass 1) sends the whole PDF inline (base64, `application/pdf`
  `inlineData` part) plus a recall-favoring prompt to `gemini-3.5-flash`'s
  `generateContent`, same direct-REST pattern as `GeminiTransactionCategorizer`
  (no Google AI SDK dependency) - capped at 15MB inline (`MAX_INLINE_BYTES`)
  with a clear error beyond that, larger documents needing the Gemini File
  API's separate upload step (not implemented). It returns a
  `CandidateBatch`: a `documentWalkthrough: String` plus a broad list of
  `ExtractedCandidate` (`candidate`/`context`/`sourceQuote`/`sourceLocation`/
  `status: CandidateStatus`/`importance`) - `CandidateStatus` is
  `FactStatus`'s superset, adding `Assumed` for a candidate that hasn't yet
  been decided to be a first-class `Assumption`-typed fact. The walkthrough
  is Gemini's own systematic, section-by-section description of the whole
  document, written before candidates in the same schema-constrained
  response (not a third Gemini call) - forces the model to actually
  describe every page/drawing/table before it starts extracting, rather
  than pattern-matching straight to the first satisfying answer. Not
  persisted as a `HouseFact`; only logged (see below) for debugging recall.
  `HouseFactNormalizer.kt`'s `GeminiHouseFactNormalizer` (pass 2) is a
  second, text-only Gemini call (no PDF - pass 1 already read the document)
  that takes those candidates and reconciles/dedupes/classifies them into
  the final `ExtractedFact` list actually persisted, assigning
  `FactType`/`Component`/`FactStatus`/`Importance`/`EvidenceType` and
  deciding `needsReview`. `HouseFactExtractor.kt`'s `TwoPassHouseFactExtractor`
  just wires the two calls together behind the unchanged `HouseFactExtractor`
  interface, so `HouseFactExtractionJobManager`/`HouseRoutes.kt` didn't need
  to change at all. `TwoPassHouseFactExtractor.extract()` logs pass 1's
  candidate count/content and pass 2's final fact count at INFO level (see
  `CLAUDE.md`'s House Knowledge gotchas for why - candidates are otherwise
  never persisted anywhere, so this is the only way to tell which pass is
  responsible when recall looks thin). Pass 2's prompt is written to take
  candidates "extracted from one or more home-related documents" -
  deliberately generalized for a future where it reconciles candidates
  gathered across a household's whole
  document set, not just one document's pass-1 output, though nothing wires
  that up yet; today it only ever sees one document's candidates per run.
  **Not yet exercised against the real Gemini API** - see `CLAUDE.md`'s
  House Knowledge gotchas for what to check first if the first real upload
  fails.
- **Extraction runs on a background coroutine** (`HouseFactExtractionJobManager`,
  `HouseFactExtractionJob.kt`), same async-job-plus-poll pattern as Gemini
  categorization (`CategorizationJobManager`) - originally synchronous in
  the upload request for this first slice, but a real document import hit
  Cloud Run's request timeout on a large/slow document, so it was moved to
  match. `POST /house/documents/upload` marks the document `EXTRACTING` and
  redirects immediately; `house-document.ftl` polls `GET
  /house/documents/{id}/status` every 2s and reloads once the document
  leaves `EXTRACTING`. See `CLAUDE.md`'s House Knowledge gotchas for the
  full reasoning and the one known gap (a mid-extraction instance restart
  leaves the document stuck at `EXTRACTING`). `geminiHttpClient`'s CIO
  engine also needed an explicit `requestTimeout` (300s) - its built-in
  15s default was firing on large documents independently of the above;
  see `CLAUDE.md` for the full story.
- **Retry/delete**: `/house/documents/{id}` has "Retry extraction" (shown
  only when `FAILED`) and "Delete document" buttons
  (`POST /house/documents/{id}/retry`/`/delete` in `HouseRoutes.kt`) - retry
  re-extracts from the already-uploaded GCS blob, delete removes the
  document, its facts, and the blob together. See `CLAUDE.md` for why each
  exists and their edge-case handling.
- **Review flow**: `/house/documents/{id}` splits a document's facts into
  "Needs your input" (`needsReview`) and "Known" (everything else).
  Ambiguous facts get four preset quick-answer buttons (Longstanding
  condition / It was repaired / Still investigating / I don't know) plus a
  free-text field, matching the spec's MVP step 3 example - all five submit
  the same `POST /house/facts/{id}/resolve` form field
  (`homeownerContext`), which clears `needsReview` and never touches
  `sourceQuote` (the spec's "never overwrite historical source material"
  principle, applied even in this narrow slice).
- **Not built in this slice** (left for later, per the spec's own "don't
  build the entire knowledge graph initially" MVP framing): photo
  extraction from documents, events/components as separate objects (only a
  flat `component` tag exists, see below), provenance beyond a single
  `sourceQuote` string, and cross-document contradiction/connection
  detection.

### Component tagging, cross-document browse, and per-component summaries

Added after the initial slice above, as the smallest step toward the
spec's "what do I know about my roof" bar without building the full
components/events/relationships graph:

- **`HouseFact.component: Component`** (`HouseFactStore.kt`) - a flat
  9-value enum (`FOUNDATION`, `STRUCTURE`, `EXTERIOR`, `ROOF`, `PLUMBING`,
  `ELECTRICAL`, `HVAC`, `SAFETY`, `OTHER`), the top level of the spec's
  component hierarchy with no sub-part nesting. `GeminiHouseFactExtractor`
  assigns it in the same extraction call as `type` (one more schema
  field/prompt instruction, not a separate pass). Facts extracted before
  this field existed read back as `OTHER` (Firestore fallback, same pattern
  as an unrecognized `type` string) - re-running "Retry extraction" on an
  already-extracted document re-populates real components for its facts,
  since retry re-runs the whole extraction from the stored blob and
  replaces the old fact rows (see the retry gotcha above). That's the
  intended backfill path for documents uploaded before this feature landed
  - no separate migration script.
- **`GET /house/facts`** (`house-facts.ftl`, `houseFactsPageModel` in
  `HousePage.kt`) - every fact across every document, grouped by component
  instead of by document, each group showing its fact/needs-review counts
  and linking each fact back to its source document. This is the
  cross-document view `/house/documents/{id}` intentionally doesn't
  provide (that page is still per-document only).
- **Per-component Gemini summary** (`ComponentSummarizer`/
  `GeminiComponentSummarizer` in `HouseComponentSummarizer.kt`,
  `HouseComponentSummaryRepository`/`FirestoreHouseComponentSummaryStore` in
  `HouseComponentSummaryStore.kt`, Firestore collection
  `houseComponentSummaries`, one doc per `(ownerId, component)` keyed
  deterministically as `{ownerId}_{component}` - always an upsert, no
  history kept) - a "Generate/Regenerate summary" button on `/house/facts`
  per component synthesizes that component's current facts (across every
  document) into a short plain-language paragraph via a text-only Gemini
  call (no PDF, no `responseSchema` - just a prompt listing each fact's
  what/type/sourceQuote/homeownerContext). **Manually triggered only** -
  the maintainer explicitly deferred auto-regeneration on every new fact to
  a later step. The stored summary keeps the fact count it was generated
  from (`factCount`), so `houseFactsPageModel` can flag a summary as stale
  (`summaryStale`) once new facts have been added to that component since -
  shown on the page but doesn't block anything or auto-regenerate.
  Synchronous (unlike document extraction), since a component's fact list
  is a handful of short lines, not a whole PDF - well inside
  `geminiHttpClient`'s 300s CIO timeout without needing the same
  async-job-plus-poll treatment; revisit only if a component's fact count
  grows enough for that to stop holding.

## Eighth feature: spending pie chart (dashboard + `/analysis`)

Both landing-page sections that already computed a per-category spend total
for some period - the dashboard's current month (`DashboardPage.kt`) and
`/analysis`'s viewed month (`AnalysisPage.kt`) - now also render a donut
chart of where that spend went, via a shared `PieChart.kt` +
`_pie-chart.ftl` partial (included from both `dashboard.ftl`, below "Money
in/out" and above "Coverage", and `analysis.ftl`, below the net-change total
and above the category list).

- **Chartable set**: only categories with a net *outflow* for the period
  (`pieChartSlices` in `PieChart.kt`) - a net-positive category (an income
  category, or refunds outweighing spend) isn't "where the money went" and
  is silently dropped rather than shown as a negative-size slice. Unlike
  `analysisEligible`'s netChange calculation, `INVESTMENT` **is** included
  here - a contribution is a real outflow from checking, which is what this
  chart is answering, even though it's treated as neutral for net-change
  purposes.
- **Top 5 + "Other categories"**: beyond the 5 biggest slices, the rest are
  summed into one bucket, ranked last regardless of its own size. Named
  "Other categories" specifically (not "Other") so it doesn't collide with
  the real built-in `OTHER` category (`CategoryStore.kt`), which can still
  appear as its own top-5 slice.
- **No JS chart library** (same "no framework" posture as the rest of the
  app - see `analysis-category.ftl`/CLAUDE.md): each slice is one SVG
  `<circle>` using the `stroke-dasharray`/`stroke-dashoffset` technique
  (full-circumference dash pattern, offset by every earlier slice's
  cumulative arc length) - only arc-length math, no trig. A native `<title>`
  gives each slice a hover tooltip for free; the legend list (label, amount,
  percent) is what actually carries identity, not color alone.
- **Colors assigned by spend rank, not a stable per-category map**:
  categories are per-owner and user-creatable (Fifth feature above), so
  there's no fixed universe to hand a permanent color to. The 5-slot
  categorical palette (`--pie-1`..`--pie-5` in `styles.css`, `--pie-other`
  for the overflow bucket) was run through the data-viz skill's
  `validate_palette.js` for both light and dark surfaces - passes CVD/
  normal-vision separation; the light-mode contrast WARN on a few slots is
  covered by the always-present text legend (the skill's "relief" rule).

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
