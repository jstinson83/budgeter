# Home OS

A personal spending-insights app. Goal: pull in bank transactions and surface
useful insight into spending habits.

> **Longer-term direction:** this project's scope is expanding beyond
> budgeting into a broader "Household OS" concept — see
> [`product_spec.md`](product_spec.md) for the full vision (an earlier draft
> lives at [`docs/HOUSEHOLD_OS.md`](docs/HOUSEHOLD_OS.md)). The codebase
> itself is still budgeting-app-shaped; this is the direction, not a
> rewrite that's already happened.

## Status

- [x] Sign-in (Google OAuth) gating a (currently blank) home page
- [x] CSV transaction import (TD's real 5-column debit/credit export,
      bank and credit-card accounts, Firestore-backed — see `/transactions`)
- [x] Spending analysis / categorization — `/analysis` shows category totals
      for the last week/month/year; a button triggers deterministic
      bank/credit-card transfer matching plus Gemini categorization for
      everything else. "Quick version": runs synchronously on button press,
      not precomputed/scheduled yet.
- [ ] Bank connection via a data aggregator (Plaid / Flinks), as a
      convenience layer on top of CSV import
- [ ] Persistent, precomputed analysis (recompute-on-a-schedule instead of
      recompute-on-button-press)

### Why CSV import before a live bank connection

TD Canada Trust doesn't expose a public consumer API, and screen-scraping a
bank login is a bad idea: it violates TD's terms of service, requires storing
real banking credentials in this app, and breaks constantly since it depends
on TD's HTML instead of a stable contract. Manual CSV export from TD online
banking has none of those problems and gets us a working end-to-end app
immediately. A proper aggregator (Plaid or Flinks — both support TD) is the
right next step once the core app is useful; it handles the bank-auth flow
itself so this app never touches TD credentials directly.

## Tech stack

- **Backend**: Kotlin + [Ktor](https://ktor.io), server-rendered with
  FreeMarker templates — no separate frontend build/framework, one deployable
  service. (Matches the [`foodie`](https://github.com/jstinson83/foodie)
  project's stack/patterns.)
- **Auth**: Google OAuth sign-in, signed session cookie.
- **Storage**: Firestore.
- **Categorization**: Gemini (`gemini-3.5-flash`), called directly over REST
  — no Google AI SDK dependency.

## Local development

### Prerequisites

- JDK 21
- Google Cloud SDK with Application Default Credentials set up
  (`gcloud auth application-default login`), and access to the `home-os`
  Firestore database — there's no local emulator wired up yet, so
  transaction import hits the real (shared-project) Firestore database.

### 1. Create a Google OAuth client

In [Google Cloud Console](https://console.cloud.google.com/apis/credentials):
create an OAuth 2.0 Client ID (Application type: **Web application**), and
add `http://localhost:8080/auth/google/callback` as an authorized redirect
URI. You'll get a Client ID and Client Secret.

### 2. Configure the backend

```sh
cd backend
cp .env.example .env
```

Fill in `.env` with the Client ID/Secret from step 1, and a session secret:

```sh
openssl rand -hex 32   # use as SESSION_SECRET
```

`GEMINI_API_KEY` is optional for local dev unless you want to exercise the
"Categorize" button on `/analysis` — without it, that request fails with a
"GEMINI_API_KEY is not set" error banner but the rest of the app works fine.

### 3. Run it

```sh
cd backend
set -a; source .env; set +a
./gradlew run
```

Visit `http://localhost:8080`.

### Tests

```sh
cd backend
./gradlew test
```

Tests use a mocked HTTP client standing in for Google's OAuth and Gemini
endpoints, and an in-memory `FakeTransactionRepository` standing in for
Firestore, so the suite never makes a real network call.

## Project layout

```
backend/src/main/kotlin/com/budgeter/
  Application.kt          wiring: routing, DI defaults, plugin install
  Auth.kt                 Google OAuth + session handling
  TransactionStore.kt     Transaction model, repository interface + Firestore impl
  CsvTransactionParser.kt TD 5-column CSV -> ParsedTransaction, tolerant of bad rows
  TransactionRoutes.kt    /transactions (list, import, delete-all)
  TransactionPage.kt      Transaction -> FreeMarker view model
  AnalysisRoutes.kt       /analysis (category totals, categorize button)
  AnalysisPage.kt         Analysis -> FreeMarker view model
  GeminiCategorizer.kt    Gemini REST client, categorizes transactions into a fixed enum
  TransferMatcher.kt      Deterministic bank<->credit-card transfer-pair matching
backend/src/main/resources/
  templates/*.ftl  server-rendered pages (home, transactions, analysis, ...)
  static/          CSS
```

## How auth works right now

Google sign-in via Ktor's built-in OAuth2 provider (`Auth.kt`). Identity is a
signed, `httpOnly` session cookie (`googleSub`/`email`/`name`) — there's no
`User` record in Firestore yet, so nothing about the signed-in account itself
is persisted beyond the cookie (only the data it owns, e.g. transactions, is).
That'll need to change once there's a reason to store account-level state,
at which point this should grow a `UserRepository` the same way `foodie` has
one.

## How CSV import works right now

`/transactions` shows an upload form (with a Bank / Credit Card account-type
selector) and the current account's transactions (most recent first), plus a
"Delete all transactions" button. `POST /transactions/import` parses the
uploaded file as TD's real headerless export format —
`date,description,moneyOut,moneyIn,balance` (5 columns; anything past column
4 is ignored) — via `CsvTransactionParser`. Exactly one of `moneyOut`/
`moneyIn` is populated per row; it's collapsed into a single signed `amount`
(money out is negative/red, money in is positive/green). Dates accept
ISO-8601 or TD's `MM/dd/yyyy` format. Bad rows are skipped rather than
failing the whole import, and the import reports how many rows landed,
were skipped as duplicates, or were skipped as parse errors.

Dedup on re-upload is keyed on `(ownerId, fileHash, rowNumber)`, so
re-uploading the exact same file is idempotent.

## How spending analysis works right now

`/analysis` shows category totals for the last week/month/year, plus a
"Categorize" button. Pressing it runs two steps synchronously in the
request, over any transactions that haven't been categorized yet:

1. **Transfer matching** (`TransferMatcher.kt`) — deterministically pairs up
   a bank-account "TFR-TO C/C" outflow with the matching credit-card
   "PAYMENT - THANK YOU" inflow (matching description templates, equal
   amount, dates within 5 days, only when the pairing is unambiguous), and
   marks both legs as `TRANSFER` so they're excluded from analysis entirely
   instead of being double-counted as spending and income.
2. **Gemini categorization** (`GeminiCategorizer.kt`) — everything transfer
   matching didn't claim is sent to Gemini and assigned one of a fixed set
   of categories (groceries, alcohol, dining out, entertainment, mortgage,
   house expenses, utilities, transportation, health, subscriptions, income,
   other).

Once a transaction has a category it's permanently excluded from future
categorize runs, so pressing the button repeatedly never re-processes (or
re-bills Gemini for) the same transaction twice. Requires the
`GEMINI_API_KEY` env var — see [Local development](#local-development).
