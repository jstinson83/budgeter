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
`AnalysisRoutes.kt`'s `resolveYearMonth` is the single place both routes
(and the categorize POST, which carries the viewed month via hidden form
fields so its redirect doesn't jump back to the current month) resolve
`year`/`month` from. Originally shipped as rolling "last week/month/year"
windows from `LocalDate.now()`; replaced with calendar-month paging plus
the drill-down page since the rolling windows were too coarse to actually
browse spending by. Deliberately a "quick version" otherwise:
categorization is triggered by a button press and runs synchronously in
the request, not a background/cron job. The maintainer has said they want
a persistent, precomputed version later (a cron-job-like thing); this
first pass intentionally doesn't build that yet.

- `Transaction.category: String?` (`TransactionStore.kt`) is null until
  categorized, and holds a `Category.id` (see "Fifth feature" below) rather
  than a fixed enum as of the categories/rules management page - categories
  are per-owner and user-editable now, not a single compile-time set.
- `TransactionRepository.uncategorized(ownerId)` (default method: filters
  `all(ownerId)` in memory) is what makes categorization idempotent - once a
  transaction has a category it's permanently excluded from future
  `/analysis/categorize` calls, so pressing the button repeatedly never
  re-analyzes (or re-bills Gemini for) the same transaction twice.
- `GeminiTransactionCategorizer` (`GeminiCategorizer.kt`) calls the Gemini
  API directly over REST (`generativelanguage.googleapis.com`, model
  `gemini-3.5-flash`) using a `responseSchema` that constrains output to
  `{index, category}` pairs (index into the request's transaction list, not
  the transaction's real id - see CLAUDE.md gotcha below) from the caller's
  own active category set (passed into `categorize()`, not hardcoded - see
  "Fifth feature" below) - no Google AI SDK dependency added, same "just use
  ktor's HttpClient" pattern as the OAuth userinfo call in `Auth.kt`. Requires the
  `GEMINI_API_KEY` env var; not yet set on the deployed Cloud Run service
  (see `CLAUDE.md`'s deploy pipeline section - same manual-env-var pattern
  as the OAuth secrets). Thinking is explicitly disabled
  (`thinkingConfig.thinkingBudget = 0`) - see CLAUDE.md gotcha.
- The viewed period is always one calendar month (`year`/`month` query
  params, default to the current month) - no rolling-window or
  all-time option.

## Third feature: bank/credit-card account labeling + transfer matching

Transactions now carry `accountType: AccountType` (`BANK` or `CREDIT_CARD`,
`TransactionStore.kt`) - the CSV import format and sign convention are
identical either way (see `CsvTransactionParser.kt`), so this is purely
caller-supplied metadata, chosen via a radio selector on the `/transactions`
upload form. Defaults to `BANK` when the field is absent (an API call
bypassing the form); a present-but-invalid value is a hard error, not
silently coerced. Firestore documents written before this field existed
read back as `BANK` (every transaction imported before now came from the
original bank-only TD export).

The reason for labeling accounts: paying a credit card from the bank
account shows up as two separate transactions (a bank outflow, a
credit-card inflow) that both need to be excluded from spending/income
analysis rather than double-counted. `TransferMatcher.kt` finds these pairs
and marks both with `TRANSFER_CATEGORY_ID` (`"TRANSFER"`, `CategoryStore.kt`
- excluded from `/analysis` entirely, not just grouped into its own bucket)
- deterministically, not via Gemini. It matches on the bank leg's description containing
`TFR-TO C/C` and the credit-card leg's containing `PAYMENT - THANK YOU`
(both fixed TD statement-generator templates, confirmed against real
statements - not guessed), plus amount equality and dates within 5 days.
Only mutually-unique pairs match; anything with more than one plausible
counterpart on either side is left uncategorized rather than guessed, since
a wrong match would silently vanish a real transaction from analysis.
Runs automatically as the first step of the existing `/analysis/categorize`
button (`AnalysisRoutes.kt`), before the remaining transactions go to
Gemini - not a separate button. Currently assumes at most one bank account
and one credit card (no per-account scoping beyond `accountType`); revisit
if a second account of either type is ever added.

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
  income, investment, other) gets written as real `categories` documents the
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
