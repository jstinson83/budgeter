# Budgeter

A personal spending-insights app. Goal: pull in bank transactions and surface
useful insight into spending habits.

> **Longer-term direction:** this project's scope is expanding beyond
> budgeting into a broader "Household OS" concept — see
> [`docs/HOUSEHOLD_OS.md`](docs/HOUSEHOLD_OS.md) for the vision. Nothing in
> this codebase has changed yet; this is a note for later, not a rewrite.

## Status

- [x] Sign-in (Google OAuth) gating a (currently blank) home page
- [x] CSV transaction import (headerless `date,description,amount`,
      Firestore-backed, no analysis yet — see `/transactions`)
- [ ] Bank connection via a data aggregator (Plaid / Flinks), as a
      convenience layer on top of CSV import
- [ ] Spending analysis / categorization

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

Tests use a mocked HTTP client standing in for Google's OAuth endpoints, and
an in-memory `FakeTransactionRepository` standing in for Firestore, so the
suite never makes a real network call.

## Project layout

```
backend/src/main/kotlin/com/budgeter/
  Application.kt          wiring: routing, DI defaults, plugin install
  Auth.kt                 Google OAuth + session handling
  TransactionStore.kt     Transaction model, repository interface + Firestore impl
  CsvTransactionParser.kt headerless CSV -> ParsedTransaction, tolerant of bad rows
  TransactionRoutes.kt    /transactions (list) and /transactions/import (upload)
  TransactionPage.kt      Transaction -> FreeMarker view model
backend/src/main/resources/
  templates/*.ftl  server-rendered pages
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

`/transactions` shows an upload form and the current account's transactions
(most recent first). `POST /transactions/import` parses the uploaded file as
headerless `date,description,amount` (ISO-8601 date, one signed amount
column, an optional 4th balance column is ignored) via
`CsvTransactionParser`, skips unparseable rows without failing the whole
import, and reports how many rows landed vs. were skipped. No categorization
or analysis yet — see `docs/HOUSEHOLD_OS.md` for where this is headed.
