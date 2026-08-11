# Household OS: A Personal Operating System for Your Life

## Core idea

A Household Operating System is an AI-powered personal context layer that continuously builds and maintains a model of your household.

It combines:

- personal finance
- home ownership
- possessions
- maintenance
- projects
- preferences
- decisions
- documents
- history

The goal is not to create another budgeting app or inventory app.

The goal is:

> Preserve the context of your household so future decisions can be made intelligently.

The fundamental insight is that households accumulate enormous amounts of knowledge, but it is scattered and decays.

## The problem

A household is a long-lived system.

Over years, you accumulate:

**Assets**

- house
- appliances
- vehicles
- furniture
- electronics
- tools
- investments

**Financial history**

- purchases
- recurring costs
- renovations
- maintenance
- insurance
- taxes
- subscriptions

**Decisions**

- Why did we choose this appliance?
- Why did we renovate this way?
- Which contractor was good?
- Which products worked?
- Which mistakes should we avoid repeating?

**Documents**

- receipts
- warranties
- manuals
- quotes
- permits
- inspection reports
- invoices

Today this information exists, but poorly:

- email
- PDFs
- photos
- paper folders
- bank statements
- people's memories

The household has a knowledge problem.

## Why now?

The key change is not "AI can chat."

The key change is:

> The economics of capturing and maintaining structured personal knowledge have changed.

Previously:

```
Cost of capturing information > Value of having information
```

Nobody was going to manually maintain:

- a home inventory
- a warranty database
- a renovation history
- a financial model

Now:

```
Cost of capturing information < Value of having information
```

because AI can extract structure from things people already produce.

## The bootstrap experience

The onboarding should not feel like creating a database.

It should feel like documenting your life.

### First hour

User walks around their house. They take photos.

AI recognizes:

- furnace
- water heater
- dishwasher
- refrigerator
- electrical panel
- appliances
- serial numbers
- labels
- renovation details

It creates:

```
Asset: Dishwasher
Manufacturer: Bosch
Model: XYZ123
Location: Kitchen
Approximate age: 2019
Unknown: Warranty, Maintenance history
```

Then it asks only useful questions.

Not: "Fill out 25 fields."

More like: "I found a Bosch dishwasher installed around 2019. Do you know who installed it?"

## The household model

The core artifact is a semantic model.

Something like:

```
Household
|
├── Assets
│   ├── Home
│   ├── Appliances
│   ├── Vehicles
│   └── Possessions
│
├── Financial Model
│   ├── Income
│   ├── Expenses
│   ├── Investments
│   ├── Debt
│   ├── Cash Flow
│   └── Budgets & Goals
│
├── Projects
│   ├── Renovations
│   ├── Repairs
│   └── Future Plans
│
├── Knowledge
│   ├── Decisions
│   ├── Preferences
│   ├── Vendors
│   ├── Lessons Learned
│   └── Constraints
│
└── Documents
    ├── Receipts
    ├── Warranties
    ├── Manuals
    └── Contracts
```

The **Knowledge** and **Documents** branches above are the shallow version of a
much deeper subsystem for the home specifically — see
[House Knowledge](#house-knowledge-facts-provenance-and-institutional-memory)
below for the full fact/provenance model they expand into.

## House Knowledge: Facts, Provenance, and Institutional Memory

This is the deepest elaboration of the Knowledge and Documents branches
above, scoped to the home itself. A house accumulates decades of knowledge —
what was observed, built, changed, repaired, and believed — fragmented
across inspection PDFs, renovation drawings, invoices, photos, emails, and
memory. The goal is not to summarize those documents. It's to preserve the
house's institutional memory as a coherent, auditable model.

> Documents tell us what was said. Photos tell us what was visible.
> Homeowners tell us what they know. Home OS connects these into an
> evolving model of the house.

### The Fact

A `Fact` is the fundamental object: something Home OS knows, believes, or
explicitly does not know about the house.

```
Fact
├── What
├── Type
├── Status
├── Time
├── Location
├── Source
├── Evidence
├── Interpretation
├── Confidence
├── Related components
├── Related events
├── Related facts
├── Related tasks
├── Photos
└── Notes
```

Most of this is inferred automatically. AI should ask the homeowner only
about ambiguity, interpretation, and missing context — never make them fill
out a form.

Facts have different semantic types, because an observation is not the same
thing as an interpretation:

- **Observation** — something someone actually saw, measured, or documented
  ("Inspector observed two cracks in the rear foundation").
- **Condition** — an ongoing physical characteristic ("The second-floor
  floor is uneven in the centre").
- **Diagnosis / Interpretation** — an explanation for a condition, with the
  interpreter preserved ("Likely longstanding settlement").
- **Decision** — something the homeowner decided ("We are not going to
  level the second floor").
- **Event** — something that happened to the house ("Structural renovation
  completed in 2017").
- **Specification** — a known technical property ("Main electrical service
  is 200A").
- **Maintenance Requirement** — something needing recurring attention
  ("Roof flashing sealant should be inspected periodically").
- **Warranty** — a time-bound guarantee ("Roof membrane warranty expires in
  2031").
- **Unknown** — something explicitly not known ("Foundation drainage
  condition is unknown"). Unknown is a valid, important state — Home OS
  should never invent an answer just because a field exists.

### Provenance is first-class

Every fact must be able to answer "why do we believe this?" Evidence can
come from an inspection, an engineer, a contractor, an architect, a
previous owner, the current homeowner, the municipality, a manufacturer, a
photograph, a physical observation, or Home OS's own inference — and a fact
can carry several. Multiple independent sources create stronger evidence.

Source observation, homeowner context, and Home OS's current interpretation
are kept as **distinct layers, never collapsed**. Home OS must never
overwrite historical source material with its current interpretation:

```
Evidence
   ↓
Interpretation
   ↓
Current state
```

not:

```
PDF → AI summary → "Truth"
```

Example: a 2022 inspection reports "significant unevenness observed in the
centre of the second floor, cause not determined." Home OS surfaces this
and asks the homeowner what they know. They explain it predates the 2017
renovation and is longstanding settling. Home OS now represents *both* the
original inspector observation and the homeowner's context as separate,
sourced statements feeding a current interpretation ("known longstanding
condition, no current concern") — never silently rewriting the inspector's
words to match the homeowner's explanation.

Internally every fact also carries an **epistemic status** — confirmed
(strong evidence), likely (professional assessment or homeowner belief),
reported by previous owner, Home OS inference, or unknown. This should stay
subtle in the normal UI but be inspectable, because it's what keeps an
AI-generated interpretation from becoming indistinguishable from an
observed fact.

### Time, events, and components

Facts relate to each other and to events through typed relationships:
predates, follows, caused by, repaired by, replaced by, discovered during,
documented by, confirmed by, contradicted by, related to, located in,
affects, requires, supersedes. This lets Home OS reason about the
building's history rather than just storing dates.

Major events (construction, renovations, inspections, repairs) are
first-class objects that facts attach to — turning a pile of documents into
a building history rather than a document repository.

Facts also attach to a hierarchy of physical components (Foundation,
Structure, Exterior, Roof, Plumbing, Electrical, HVAC, Safety, and their
sub-parts under Property → Building), and to a physical location where
possible ("Foundation → rear façade → beneath kitchen window"). This is
what makes "show me everything Home OS knows about my foundation"
answerable, and eventually enables a spatial/floor-plan view of facts —
without ever needing to expose a "knowledge graph" to the user.

### Lifecycle: current state vs. historical state

A fact's status evolves — a loose lifecycle of Unknown → Observed →
Understood → Action required → Scheduled → Completed → Verified →
Historical, not rigid, and not every fact type uses every stage.

Home OS must distinguish *what happened* from *what is true now*. A
foundation crack's page shows "first documented 2022, repaired 2023,
verified 2024, no known recurrence 2026" — not the flattened, misleading
"the house has foundation cracks." The original observation stays in
history; only the current state changes.

Unknowns are actionable, not dead ends: an unresolved fact ("roof
structure: unknown") becomes an open question that a later renovation
photo, drawing, or homeowner answer can resolve — transitioning the fact
from Unknown to Known with new provenance attached, never guessed.

Some facts also go stale and need periodic reconfirmation (e.g., "roof
flashing should be inspected annually") — Home OS should be able to say "5
house knowledge items are due for review this month."

### Photos as evidence

Photos extracted from an imported document shouldn't stay buried inside it.
When a document is imported, Home OS extracts and classifies its photos,
proposing associations by component (e.g. "74 photos found: 12 exterior, 8
roof, 7 electrical, ..."). Each photo retains its original document/page,
date, inferred location and component, and the fact(s) it's evidence for —
so a page reads as "Front lintel — surface corrosion, photographed May
2022" rather than "inspection.pdf, page 16."

Homeowners can add new photos to an existing fact over time, and Home OS
proposes the match ("this appears to be the front lintel from your 2022
inspection — has anything changed?"), building a longitudinal visual
record. AI should stay conservative here: visual comparison over time is
useful, but an automated read of a photo should never be presented as a
professional structural or safety assessment.

A fact's evidence can accumulate this way indefinitely — an inspection
observation and photo in 2022, a homeowner note that it was treated in
2024, a homeowner photo in 2026 showing it's visually unchanged —
representing evidence → condition → action → verification over the life of
the fact.

### Documents, cross-referencing, and contradictions

Documents and facts are many-to-many: a document (a 2017 renovation)
supports many facts (drawings, permit, invoice, photos, engineering
report), and a fact (a floor bulge) can be backed by many documents (an
inspection, a homeowner note, a later photo). Documents are evidence for
the house model, not the primary interface to it.

When a new document is imported, Home OS compares it against existing
house knowledge and surfaces two distinct kinds of findings, each labeled
for what it is:

- **Possible connections** — an inference, explicitly marked as such, not a
  fact from either document (e.g. "the 2022 inspection's steel columns
  appear consistent with the 2017 renovation drawings").
- **Potential conflicts** — e.g. a 2017 engineering drawing specifies one
  beam size, a 2024 contractor invoice references a different one. Home OS
  surfaces the conflict; it does not decide which document is correct.

### Maintenance and monitoring

Documents automatically produce maintenance candidates ("inspector
recommends checking roof flashing sealant" → a proposed recurring
maintenance item), but Home OS keeps "inspector recommends X" and "X is
definitely required" as distinct — the recommendation itself is evidence;
the maintenance requirement is Home OS's own structured interpretation of
it.

Some conditions (brick bulging, foundation cracks, floor settlement, roof
membrane condition, flashing, ...) call for ongoing observation rather than
immediate action. Home OS supports monitoring a fact with a recurring
check-in cadence, building a longitudinal condition record ("2022 observed
→ 2023 unchanged → 2024 unchanged → ..."), and can maintain the conditions
that would actually change its assessment (e.g., for a "longstanding,
stable" floor settlement interpretation: a noticeable increase in slope,
new cracking, doors/windows becoming hard to operate, or a professional
assessment of active movement) — a framework for reasoning over time
without the system claiming to diagnose structural conditions itself.

### House Brain and Ask My House

The primary UI surfaces this accumulated knowledge directly, by component —
"Structure: 12 known facts, 2 unresolved questions, 1 item being
monitored," and similarly for Envelope, Electrical, Plumbing, HVAC,
History — plus a running feed of "things Home OS knows about your house,"
each with its source.

Every fact supports a **"Why?"** interaction: tapping it shows the
homeowner statement or professional source it's derived from, down to the
original wording, making the AI's memory auditable rather than opaque.

This structured model is what makes natural-language Q&A over the house
genuinely useful, in the same spirit as the other assistants in
[The AI layer](#the-ai-layer) — **Ask My House**:

**Q:** "Can I remove this wall?"

**A:** "This wall appears to be load-bearing based on the 2017 structural
drawings, which introduced steel beams and columns in this area. The 2022
inspection could not inspect concealed load-bearing walls. Evidence: 2017
structural plans, 2022 inspection. Recommendation: have the proposed
modification reviewed by a structural engineer."

**Q:** "What do I know about my roof?" synthesizes type, age, inspection
observations, repairs, photos, warranties, unresolved questions,
maintenance requirements, and current monitoring status.

**Q:** "What work should I probably do this year?" combines unresolved
inspection recommendations, recurring maintenance, overdue reviews,
equipment age, warranties, monitored conditions, and homeowner tasks.

The answer comes from the structured house model, not a semantic search
over PDFs — the same "reasoning layer over a personal semantic model"
architecture as the rest of Household OS, applied to the house itself:

```
Documents (PDFs, drawings, invoices)
              ↓
        AI extraction
              ↓
            Facts
     ↙        ↓        ↘
 Events   Components  Evidence
     ↘        ↓        ↙
        House model
              ↑
      Homeowner knowledge
              ↑
           Photos
              ↓
     Tasks / maintenance
```

The AI is primarily the interface for extracting and updating knowledge;
the structured model underneath is the durable product.

### Positioning: not a document chatbot

A document chatbot's pitch is "ask questions about your documents" — PDF →
RAG → answer. House Knowledge's pitch is **Home OS remembers your house**:
documents, photos, professional observations, homeowner knowledge, events,
repairs, and ongoing observations all feed a persistent house model, which
produces a current understanding, which produces actions and answers. The
inspection isn't the product — it's the first major contribution to the
house's memory. This is the same principle as
["the important data is not the object"](#the-important-data-is-not-the-object)
above, applied rigorously: a house has institutional memory, and Home OS's
job is to make it explicit, structured, auditable, and persistent.

### MVP: House Knowledge

Rather than building the full knowledge graph above at once, the first
slice is one killer workflow, layered onto [V1](#mvp) once document upload
exists:

1. **Upload a document.** User uploads a home inspection.
2. **Extract things worth remembering.** "I found 37 things worth
   remembering about your house" (steel columns, 200A electrical service,
   foundation cracks, a central floor bulge, roof flashing needing
   maintenance, ...).
3. **Identify ambiguity.** Most facts are accepted automatically. Ambiguous
   or interpretive ones prompt the homeowner directly: "Inspector says
   cause not determined for the floor bulge — what's your understanding?"
   with a short set of response options (longstanding condition / it was
   repaired / still investigating / I don't know / add my own explanation)
   rather than a free-text-only prompt.
4. **Build structured facts** — with evidence, provenance, relationships,
   and current state — from the document plus the homeowner's answers.
5. **Extract and associate photos** from the document as evidence attached
   to facts and locations.
6. **Make it searchable** — browsable by component, fact, event, photo,
   document, maintenance item, and open question, and answerable via Ask
   My House.

## The AI layer

The database is not the product. The intelligence is.

Instead of browsing records, users ask questions.

### Maintenance assistant

**Q:** "What maintenance should I do this spring?"

**A:** "Your furnace filter was last changed 8 months ago. The deck was stained 4 years ago and the stain you used typically lasts 3-5 years. Your water heater is now 11 years old."

### Financial assistant

**Q:** "Why did our spending increase?"

Not: "Housing increased 4%."

Instead: "Your expenses increased primarily because of the kitchen renovation and higher insurance premiums. Your baseline spending excluding projects has remained stable."

### Budgeting assistant

Goals are user-set targets on top of the financial model, not another derived insight - "don't spend more than $X in this category this month," a savings target, a debt paydown pace.

**Q:** "How are we doing on our grocery budget this month?"

**A:** "You've spent $612 of your $700 grocery budget with 9 days left in the month - on pace to come in under, unless the last week runs above your usual rate."

Proactively, not just on request: "You're at 90% of your dining-out budget with a week left in the month."

It knows:

- category spending limits (monthly, recurring)
- savings/investment targets
- debt payoff goals and pace
- progress against each, computed from the same categorized transaction history the financial assistant already reasons over

### Decision assistant

**Q:** "Should we replace the dishwasher?"

It knows:

- age
- repair history
- original cost
- previous repair costs
- similar products considered
- household preferences

### Renovation assistant

**Q:** "Should we redo the bathroom?"

It knows:

- previous renovation budgets
- preferred materials
- contractors used
- financing capacity
- past decisions

### House assistant ("Ask My House")

**Q:** "Can I remove this wall?"

It knows the full fact/provenance model for the house — see
[House Knowledge](#house-knowledge-facts-provenance-and-institutional-memory)
for the complete model and more examples.

## The important data is not the object

This is where it connects strongly to Architectural Persistence.

The valuable information is often not:

> "We have maple floors."

The valuable information is:

> "We chose maple because darker floors showed scratches from kids and pets."

Not:

> "Paint color: Benjamin Moore X."

But:

> "We selected this because it matched the kitchen renovation and we liked warmer tones."

The value is the reasoning behind decisions.

## Capture model

The system should continuously observe.

**Passive inputs**

- bank transactions
- emails
- receipts
- calendars
- photos
- documents

**Active inputs**

- conversations
- voice notes
- photos
- questions

Example:

**User:** "Plumber came today and fixed the leak."

**AI:** "I found your plumber. Should I add this as a repair event for the basement bathroom?"

## Relationship to your other apps

The interesting realization is that these aren't separate products. They are different views of the same primitive.

**Foodie**

```
Recipes, Shopping, Preferences, Cooking history
        ↓
Food model
        ↓
Cooking assistant
```

**Finance/Home**

```
Transactions, Assets, Documents, Decisions
        ↓
Household model
        ↓
Life assistant
```

**Books/media**

```
Ratings, History, Taste
        ↓
Preference model
        ↓
Personal recommendation engine
```

The common architecture:

```
Messy life artifacts
        ↓
AI extraction
        ↓
Personal semantic model
        ↓
Reasoning layer
```

## MVP

A realistic first version could be surprisingly small.

### Version 1 — "Scan your home"

- photo capture
- object recognition
- document upload → fact extraction (see
  [House Knowledge MVP](#mvp-house-knowledge) for the detailed workflow)
- asset extraction
- simple chat

### Version 2

Add:

- receipts
- maintenance tracking
- warranties
- transaction import

### Version 3

Add:

- proactive reminders
- financial insights
- budgeting goals (category spending limits, savings/debt targets, progress tracking)
- renovation planning
- vendor recommendations

## Why this idea is interesting

The thesis is not:

> "Everyone needs another home inventory app."

The thesis is:

> "AI makes it economically feasible to create durable personal context models that were previously impossible to maintain."

The household is just one of the first domains where the payoff is obvious.
