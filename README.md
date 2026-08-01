# Budgeter

A personal spending-insights app. Goal: pull in bank transactions and surface
useful insight into spending habits.

## Status

- [x] Sign-in page gating a (currently blank) home page
- [ ] CSV transaction import (next up)
- [ ] Bank connection via a data aggregator (Plaid / Flinks), as a
      convenience layer on top of CSV import

### Why CSV import before a live bank connection

TD Canada Trust doesn't expose a public consumer API, and screen-scraping a
bank login is a bad idea: it violates TD's terms of service, requires storing
real banking credentials in this app, and breaks constantly since it depends
on TD's HTML instead of a stable contract. Manual CSV export from TD online
banking has none of those problems and gets us a working end-to-end app
immediately. A proper aggregator (Plaid or Flinks — both support TD) is the
right next step once the core app is useful; it handles the bank-auth flow
itself so this app never touches TD credentials directly.

## Stack

- **Backend**: Kotlin + [Ktor](https://ktor.io), Gradle
- **Frontend**: Vite + TypeScript (no framework)

The backend serves the frontend's built static files directly, so in
production it's a single process on one origin (no CORS to deal with).

## Local development

### Prerequisites

- JDK 21
- Node 22

### 1. Configure the backend

```sh
cd backend
cp .env.example .env
```

Generate a password hash and session secret, then fill in `.env`:

```sh
./gradlew run --args="hash-password <your-password>"
openssl rand -hex 32   # use as BUDGETER_SESSION_SECRET
```

### 2. Run both servers

In one terminal, run the frontend dev server (hot reload, proxies `/api` to
the backend):

```sh
cd frontend
npm install
npm run dev
```

In another terminal, run the backend:

```sh
cd backend
set -a; source .env; set +a
./gradlew run
```

Visit the frontend dev server URL Vite prints (typically
`http://localhost:5173`).

### Production-like single-process run

Build the frontend, then let the backend serve it directly:

```sh
cd frontend && npm run build
cd ../backend && set -a; source .env; set +a && ./gradlew run
```

Visit `http://localhost:8080`.

## How auth works right now

Single hardcoded user (you), configured via env vars. Login sets a signed,
encrypted, `httpOnly` session cookie (Ktor `Sessions` plugin); `/api/me` is
the gate the frontend checks on load to decide whether to show the home page
or redirect to `/login.html`. No database yet — this will need to evolve once
there's actual data to store (transactions, categories, etc.).
