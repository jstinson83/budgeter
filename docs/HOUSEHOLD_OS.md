# Household OS: a personal operating system for your life

This document captures a scope change for this project: from a spending-insights
budgeting app to something broader. It's the reference for *why* and *what*,
so implementation work later has a direction to build toward instead of
guessing at it turn by turn.

## Core idea

A Household OS is an AI-powered personal context layer that continuously
builds and maintains a model of your household: finances, home ownership,
possessions, maintenance, projects, preferences, decisions, documents, and
history.

The goal is not to build another budgeting app or another home-inventory app.
The goal is to **preserve the context of a household so future decisions can
be made intelligently.** Households accumulate enormous amounts of knowledge
over years, but it's scattered across email, PDFs, photos, paper folders,
bank statements, and people's memories — and it decays.

This is the same missing ingredient as Architectural Persistence, applied to
a household instead of a codebase: the artifact people keep isn't the
valuable part. The reasoning behind it is. "We chose maple floors" is not
useful. "We chose maple because darker floors showed scratches from kids and
pets" is useful, because it's the only version that lets a future decision
reuse the judgment instead of re-deriving it.

## Why now

The key change isn't "AI can chat." It's that the economics of capturing and
maintaining structured personal knowledge have flipped.

Previously: `cost of capturing information > value of having it`. Nobody was
going to manually maintain a home inventory, a warranty database, a
renovation history, or a financial model by hand.

Now: `cost of capturing information < value of having it`, because AI can
extract structure from things people already produce — photos, receipts,
emails, conversations — instead of requiring manual data entry.

## The household model

The core artifact is a semantic model of the household:

```
Household
├── Assets
│   ├── Home
│   ├── Appliances
│   ├── Vehicles
│   └── Possessions
├── Financial Model
│   ├── Income
│   ├── Expenses
│   ├── Investments
│   ├── Debt
│   └── Cash Flow
├── Projects
│   ├── Renovations
│   ├── Repairs
│   └── Future Plans
├── Knowledge
│   ├── Decisions
│   ├── Preferences
│   ├── Vendors
│   ├── Lessons Learned
│   └── Constraints
└── Documents
    ├── Receipts
    ├── Warranties
    ├── Manuals
    └── Contracts
```

The database is not the product — it's substrate. The intelligence sitting
on top of it (a reasoning layer that can answer "should we replace the
dishwasher?" using age, repair history, and cost) is the product. Browsing
records is not the interaction model; asking questions is.

## Bootstrap experience

Onboarding should feel like documenting your life, not filling out a
database. A user walks around their house taking photos. The system
recognizes appliances, fixtures, serial numbers, and labels, extracts a
best-effort asset record, and then asks only the questions it can't answer
itself:

> "I found a Bosch dishwasher installed around 2019. Do you know who
> installed it?"

— not a 25-field form.

## Capture model

Two capture modes, both feeding the same model:

- **Passive**: bank transactions, emails, receipts, calendars, photos,
  documents.
- **Active**: conversations, voice notes, photos, direct questions.

Example: user says "Plumber came today and fixed the leak." The system
identifies the plumber from prior vendor records and asks: "Should I add
this as a repair event for the basement bathroom?"

## Relationship to other domains

Finance/home isn't a one-off — it's one instance of a repeatable shape that
also applies to food (recipes, shopping, cooking history → a food model → a
cooking assistant) or media (ratings, history, taste → a preference model →
a recommendation engine):

```
Messy life artifacts → AI extraction → Personal semantic model → Reasoning layer
```

Household OS is the first domain being built because the payoff is most
obvious there, not because it's the only one that fits.

## MVP phasing

**V1 — capture the basics**
- Photo capture of the home and its assets
- Object recognition → draft asset records (manufacturer, model, location,
  approximate age)
- Document upload (receipts, manuals, warranties) attached to assets
- Simple chat over what's been captured so far

**V2 — build the financial and maintenance layers**
- Bank transaction import (this is where the original budgeter scope folds
  in, as the financial layer of the household model rather than a
  standalone product)
- Maintenance event tracking tied to assets
- Warranty tracking with expiry awareness

**V3 — proactive intelligence**
- Proactive reminders ("furnace filter is 8 months overdue")
- Financial insight ("spending increased mainly due to the kitchen reno, not
  baseline costs")
- Renovation/decision planning informed by past budgets, vendors, and stated
  preferences

## What this means for the current codebase

The existing app (Kotlin + Ktor, Google OAuth, server-rendered FreeMarker,
no database yet) is the right foundation to build on, not something to
throw away: auth and session handling carry over unchanged, and the
transaction-import work already planned becomes the V2 financial layer
instead of a separate concern.

What's new is the domain model. There's no database or persistence layer
yet, so the household schema (`Household`, `Asset`, `Document`, `Project`,
vendor/decision records) can be designed from scratch rather than retrofit
onto existing tables. Photo-based object recognition and the chat/reasoning
layer both require picking an AI provider/API, which is a separate decision
from this document's scope.

## Open questions to resolve before V1 implementation starts

- Data store choice (Postgres is the likely default given the `foodie`
  sibling project's patterns, but unconfirmed here).
- AI provider for image recognition and chat/reasoning (Claude, in keeping
  with the rest of this project's tooling, is the natural default but
  unconfirmed).
- File/photo storage (local disk vs. object storage) for the V1 MVP.
- Whether V1 ships single-household/single-user or needs multi-user
  household sharing from day one.
