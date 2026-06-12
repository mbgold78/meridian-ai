# Meridian Substrate — User Manual

**Substrate v0.1.0 — manual generated 2026-06-12**

Meridian Substrate is the system of record for a holding-company transformation engagement. Meridian deploys it into a client's environment for the duration of an engagement, where it ingests the client's organizational reality into a canonical model, records evidence-backed diagnostic findings against it, cascades leadership direction down the organization with documented responses (including first-class declines), holds cross-subsidiary coordination contracts the platform monitors automatically, and keeps every AI agent operating in the estate under a named human oversight chain with honest, disclosed controls. This manual is for the two kinds of people who use it: **Meridian engagement operators**, who deploy and run the platform, and **client-side executives and COOs**, who log in to see and act.

> **About this file.** This manual is assembled from six editable section sources in `substrate/docs/manual/` — `01-overview-getting-started.md` through `06-admin-api-troubleshooting.md`. To change the manual, edit those files and regenerate this one; do not edit `USER-MANUAL.md` directly.

**Conventions used throughout.** Wire-level literals — states, signals, error codes, configuration keys — appear in `code font` exactly as the API returns them. Anything unbuilt, intentionally inert, or degraded in this release is called out where you would trip on it, always in the same form: a `> **Not yet:**` blockquote. Commands are shown as PowerShell from the `substrate/` directory (`.\gradlew.bat`); on macOS/Linux substitute `./gradlew`.

## Table of contents

**Part 1 — Overview and getting started**

- [What is Meridian Substrate](#what-is-meridian-substrate)
- [The two user roles](#the-two-user-roles)
- [Getting started](#getting-started)

**Part 2 — The client surfaces**

- [The client surfaces](#the-client-surfaces)
- [Living Canvas](#living-canvas)
- [Future State Designer](#future-state-designer)
- [Capability Designer](#capability-designer)
- [Portfolio](#portfolio)

**Part 3 — Cascades and contracts**

- [Cascades and Contracts](#cascades-and-contracts)
- [Part 1 — Cascades](#part-1--cascades)
- [Part 2 — Coordination contracts](#part-2--coordination-contracts)

**Part 4 — Agents and diagnostics**

- [Agent operations](#agent-operations)
- [Diagnostics and findings](#diagnostics-and-findings)

**Part 5 — Operator workflows**

- [Operator workflows (CLI)](#operator-workflows-cli)

**Part 6 — Administration, API reference, troubleshooting**

- [Administration](#administration)
- [API & MCP reference](#api--mcp-reference)
- [Troubleshooting & FAQ](#troubleshooting--faq)
- [Further reading](#further-reading)

---

## What is Meridian Substrate

Meridian Substrate is the system of record for a holding-company transformation engagement. Meridian deploys it into a client's environment for the duration of an engagement, and it holds the engagement's working truth end to end:

1. **Ingest organizational reality.** Customer source files (CSV, JSON, text) are converted into a canonical model of the organization — systems, manual processes, people, integrations — through a convert → stage → human-review → commit pipeline. Nothing enters the model without a review step.
2. **Diagnose.** Findings (risks, redundancies, consolidation opportunities) are recorded against that model with severity and evidence, and portfolio-level statistics are computed from them.
3. **Cascade direction.** Leadership sets direction at the top; it propagates down the organization level by level, and each level responds. A **Decline is a first-class response** that travels back up with reasoning — it is never treated as an error.
4. **Contract coordination.** Cross-subsidiary commitments are written down as coordination contracts the platform monitors on a schedule, with documented decline triggers and an enforcement gateway.
5. **Operate AI agents under oversight.** Every AI agent gets a durable identity, an oversight chain of named humans, a lifecycle (shadow → live → paused → retired), and honest controls — the platform never claims to have stopped something it cannot reach.

Five engines back these capabilities, and every surface in the product is a view over one or more of them:

| Engine | What it holds | Where you see it |
|---|---|---|
| Cascading evaluation | Direction propagating down the org; level-by-level responses including Decline; an append-only action timeline that is the audit trail | Cascade detail pages under `/portfolio` |
| Coordination contracts | Cross-subsidiary commitments, scheduled monitors, decline triggers, an enforcement gateway (`off` / `shadow` / `enforce`) | Contract detail pages under `/portfolio` |
| Agent registry | Durable agent identity, oversight chains, lifecycle state, interventions, control verbs with disclosed enforcement effect | Subsidiary **Operations** tab |
| Diagnostic | Findings over the ingested model, severity counts, computed portfolio statistics | Canvas risk badges; dashboard stat strips |
| Decision log | Append-only, hash-chained record of design decisions and engine accountability events; the chain is re-verified at every boot | Future State Designer decision table |

Two properties shape how engagements buy and run it:

- **Sellable whole or in part.** Modules deploy independently. An engagement that only needs the diagnostic and decision-log engines can run without the rest; absent modules degrade honestly (for example, agent controls disclose that no enforcement gateway is present) rather than pretending to work.
- **Runs with or without the orchestrator.** Substrate is a sibling to Meridian's orchestrator product, which it calls when work should be done by AI agents (diagnostic generation, AI-assisted cascade drafting). With the orchestrator absent — the default — everything works except the AI-suggestion endpoint for cascade drafts, which simply does not exist.

Substrate is **not** a multi-tenant SaaS. Each engagement is its own deployment in the customer's infrastructure, with the customer's identity provider and the customer's data residency.

## The two user roles

**Meridian engagement operators** run the platform for the client: they deploy it, run ingestion, configure branding and authentication, author cascades and contracts through the API, register and deploy agents, and watch platform health. Operator-facing tools include the ingestion CLI, the OpenAPI explorer, and the admin endpoints. Parts 5 and 6 of this manual are theirs.

**Client-side executives and COOs** log in to see and act: explore the canvas of their systems and processes, read findings, respond to cascades (including declining them), review coordination contracts, and watch the agent fleet operate under its oversight chains. In the demo dataset, the `/portfolio` landing page makes the two client vantage points explicit — a **Corporate Operating Partner** view across all subsidiaries and a **Subsidiary COO** view inside one operating company.

Within a session, what you see can also be shaped by a **persona** (Executive, Architect, and so on) — covered under [First-run orientation](#first-run-orientation) below. In a production deployment the persona is derived from your sign-in identity; in local development you pick it from a switcher.

## Getting started

### Run it locally — no Docker required

Requirements: Java 21 (and Node 20+ if you intend to work on the web client; the Gradle build compiles the client into the server automatically).

From the repository root:

```powershell
cd substrate
.\gradlew.bat :app:bootRun --args="--substrate.demo.seed=true --substrate.demo.contracts.activate=true"
```

That single command builds the web client, starts the server with the `dev` profile (in-memory database, no sign-in required), seeds the demo dataset, and serves everything at **http://localhost:8080**.

The same thing from a built jar:

```powershell
.\gradlew.bat :app:bootJar
java -jar app\build\libs\app-0.1.0.jar --spring.profiles.active=dev --substrate.demo.seed=true --substrate.demo.contracts.activate=true
```

The default port is 8080; add `--server.port=8090` (or any port) to move it. Omit the two `substrate.demo.*` flags and the product boots **empty** — a real engagement starts from ingestion, not from a seed.

### Profiles

| Profile | Database | Sign-in | Use it for |
|---|---|---|---|
| `dev` (default) | In-memory H2 | None (anonymous mode — every request gets a synthetic operator identity) | Day-to-day local use and demos. Data resets on every restart. |
| `dev-shared` | File-based H2 at `substrate/data/` | None (anonymous mode) | Running the ingestion CLI and the app against **one shared database**, so the full ingest → diagnose flow works across both processes. Reset by deleting `substrate/data/`. |
| `postgres` | PostgreSQL | OIDC (real sign-in) | The Docker Compose stack, deployment testing, and customer environments. |

### Demo and fixture flags

All of these default to **off**. The first two are the standard demo boot; the rest are situational levers. None of them ever belongs in a customer engagement build. Operator-level detail on each lever — including what the fixed clock is for — is under [The demo pack](#the-demo-pack-seeding-a-demonstration-environment).

| Flag | What it does |
|---|---|
| `--substrate.demo.seed=true` | Seeds the Beacon Industries demo dataset (inventory below). Seeding only ever touches demo-marked workspaces — it never writes into engagement data, and on a database with only real workspaces it does nothing. |
| `--substrate.demo.contracts.activate=true` | Records the fixture acceptances so the tribal-knowledge coordination contract boots `ACTIVE` (and its scheduled monitor runs). Without it both demo contracts sit at `PROPOSED`. Only effective together with the seed flag. |
| `--substrate.demo.contracts.guards=true` | Additionally seeds a synthetic guard-bearing contract for demonstrating the enforcement gateway (the two standard demo contracts deliberately compile to zero guards). Only effective together with the seed flag. |
| `--substrate.demo.fixed-clock=2026-05-12T00:00:00Z` | Pins the engines' clock to a fixed instant so date-relative displays are deterministic in demos. |
| `--substrate.transformation.fixture-enabled=true` | Serves the fictional transformation-plan mockup that backs the canvas Roadmap view. With the flag off (the default) that endpoint returns 404 and the Roadmap view has no data — see [What does not work yet](#what-does-not-work-yet). |

### What the demo seed creates

Verified against a freshly seeded running instance (the operator-side seeder mechanics are under [The demo pack](#the-demo-pack-seeding-a-demonstration-environment)):

| Seeded | Detail |
|---|---|
| 23 workspaces | 1 primary engagement workspace ("Operational Risk" — the fictional *Meridian Bancorp · Risk Consolidation* engagement) plus 22 Beacon Industries subsidiary workspaces |
| 12 systems, 26 manual processes | The canvas nodes of the primary workspace, with integrations, organization members, and process–system links |
| 21 findings | 3 critical, 8 high, 10 medium — all on the primary workspace |
| 70 agents | Distributed across the Beacon subsidiaries with oversight chains; 8 flagged as requiring intervention |
| 3 cascades | Including one currently in a declined state, so the upward-Decline timeline is visible |
| 2 coordination contracts | "Tribal-knowledge depopulation across four healthcare/insurance subs" (`ACTIVE` when the activate flag is set) and "Shared compliance-form pre-fill agent" (a `PROPOSED` federation invitation) |
| 6 decision-log entries | Five design decisions (consolidate / retire / automate / formalize, in proposed, approved, and rejected states) plus the contract-activation accountability entry; the hash chain verifies intact |
| 2 capabilities | Customer Onboarding → KYC Verification, in the Capability Designer |
| Branding | The default Meridian branding configuration for the primary workspace |

### What URL to open

Open **http://localhost:8080** (or the port you chose). The main surfaces:

| URL | Surface |
|---|---|
| `/` | **Living Canvas** — the engagement's systems and manual processes as an interactive map |
| `/designer` | **Future State Designer** — the design-decision table and accountability ledger |
| `/designer/capabilities` | **Capability Designer** — the capability tree |
| `/portfolio` | **Beacon portfolio landing** — the demo vantage-point picker |
| `/portfolio/dashboard` | Corporate Operating Partner view across all 22 subsidiaries |
| `/portfolio/subsidiary/<name>` | Subsidiary COO view, with Capabilities / Environment / Plan / Operations tabs |

Operator endpoints on the same port: `/swagger-ui.html` (interactive API explorer), `/actuator/health` (liveness/readiness), and `/h2-console` (database console, dev profiles only). Any URL the app does not recognize shows a "not found" panel with a way back — you cannot strand yourself on a blank page.

### First-run orientation

You land on the **Living Canvas**. A one-time welcome overlay explains the vocabulary: every tile is a system you run, lines are data flows, red badges count open risks, amber tiles are manual processes, and a 🧠 marker means tribal knowledge (one person knows how it works). Dismiss it and it stays dismissed for that browser.

Around the canvas:

- **Top left** — the workspace wordmark ("Meridian" by default; an engagement's branding configuration can change the name, colors, and terminology across the whole product).
- **Top right** — links to the Capability Designer and the Portfolio demo, plus the **persona switcher**.
- **Bottom** — the chat bar. Ask a question and the assistant answers about your data, highlighting what it talks about on the canvas. The assistant requires an Anthropic API key on the server; see [Living Canvas](#living-canvas) for the exact behavior with and without one.

**The persona switcher.** In local anonymous mode it is a dropdown: **Architect** (the default) and **Executive** are functional; Domain Lead, Subject Matter Expert, Auditor, and Data Owner are labeled *Preview* and not yet differentiated. Your selection travels with every request and shapes persona-aware behavior such as assistant responses. It persists across reloads in the browser. Under real OIDC sign-in there is nothing to switch: the switcher becomes a read-only identity badge showing your name and the persona derived from your token's roles, plus a sign-out button. (The wire values behind the labels — e.g. Architect is `L3_ANALYST` — appear in every response envelope's `meta.personaLevel`; see [The response envelope](#the-response-envelope).)

**Dark mode reality.** There is no in-app light/dark toggle today. The product follows your operating system's light/dark preference. The one exception is deliberate: the Beacon portfolio dashboards are designed dark-only, so entering `/portfolio` forces dark mode and leaving it restores whatever you had before.

### The demo dataset explained

**Beacon Industries is fictional** — a made-up diversified portfolio operator with 22 subsidiaries, used to demonstrate the portfolio surfaces. The landing page says so in its header ("Fixture dataset") and footer, and a banner at the top of every portfolio page states the data provenance for what you are looking at: *"live engine data + illustrative fixtures"* when the backend is answering, or *"Fixture dataset (demo)"* when it is not.

Be precise about which is which today:

**Served live by the engines** (from seeded demo data — these are real engine reads, and the numbers move if the data moves):

- The Living Canvas nodes, edges, stats strip, and findings
- The Future State Designer decision table and accountability ledger
- The Capability Designer tree
- The portfolio dashboard statistics strip (for example "8 of 70 registered agents in intervention")
- Cascade detail pages, including the append-only action timeline (Issued → Cascaded → Declined)
- Contract detail pages (parties, commitments, monitor mechanism, decline triggers)
- The subsidiary **Operations** tab agent fleet, with lifecycle badges and oversight chains

**Still fixture data** (illustrative, hard-coded in the client, awaiting their live swaps):

- The subsidiary roster, revenue, and industry figures on the portfolio landing page
- The "Where each subsidiary sits" lifecycle-phase grid on the dashboard
- The subsidiary **Capabilities**, **Environment**, and **Plan** tab content
- The canvas Roadmap view (flag-gated fixture; see below)

One visible consequence worth knowing before a demo: the dashboard's lifecycle-stall card truthfully reports the 22 subsidiaries as unphased while the fixture grid directly below places each one in a phase — see the [dashboard zone walkthrough](#corporate-operating-partner-dashboard) for why both halves are honest.

Screenshots of all of these surfaces, captured from a seeded build, are in [`../acceptance/final/`](../acceptance/final/) (for example [`01-living-canvas.png`](../acceptance/final/01-living-canvas.png), [`05-portfolio-dashboard.png`](../acceptance/final/05-portfolio-dashboard.png), [`06-cascade-detail-decline-pending.png`](../acceptance/final/06-cascade-detail-decline-pending.png)).

### What does not work yet

The at-a-glance honesty list. Each item is covered in depth where you would meet it; the cross-references point there.

> **Not yet:**
>
> - **Cascade authoring has no UI.** Cascades are created and advanced through the API only, and that write API is operator-grade — leadership-facing authoring is a later phase. Reading cascades in the UI is fully built. See [Authoring a cascade](#authoring-a-cascade--what-works-today).
> - **Contract Review and Decline buttons are intentionally inert.** They render disabled with an explanatory tooltip rather than doing something undefined. See [Contract detail page](#contract-detail-page).
> - **The transformation Roadmap is behind a disabled flag.** By default the plan endpoint returns 404 and the Roadmap drawer view has nothing to show; the flag serves a fictional mockup for demos only — there is no plan engine yet. See [Living Canvas](#living-canvas).
> - **AI features need an API key.** Chat, AI-assisted diagnostics, and generative drafting call a real model and require `ANTHROPIC_API_KEY` on the server; without it they degrade visibly (a structured `LLM_UNAVAILABLE` error), never silently. See [Running without an AI key](#running-without-an-ai-key).
> - **Agent pause/kill enforcement depends on the enforcement mode.** Every control response discloses what the verb actually did: `gateway-block` (the platform's gateway will refuse the agent's actions) or `recorded-intent` (the instruction was recorded and the oversight chain notified, but the platform cannot itself stop the runtime). The client-facing Operations tab is read-only today — these verbs are operator-API-only. See [Controlling an agent](#controlling-an-agent-the-honest-control-model).

### Docker Compose and Keycloak (Docker-capable machines)

If your machine runs Docker, the full stack — app with PostgreSQL and real OIDC sign-in against a bundled Keycloak — comes up with:

```powershell
cd substrate
copy .env.example .env    # then edit passwords
docker compose up --build
```

That gives you the app on :8080 (postgres profile), PostgreSQL on :5432, and Keycloak on :8081 with a pre-imported realm and throwaway dev users `dev-admin`, `dev-operator`, `dev-exec`, and `dev-coo` (password = username) so you can experience real sign-in, role-derived personas, and the read-only identity badge. Keycloak is a development/testing identity provider only — production engagements connect to the customer's own IdP through generic OIDC configuration, no code changes. Full setup detail: see [Further reading](#further-reading).

## The client surfaces

Meridian Substrate ships four client-facing surfaces. Each one is a browser route served by the platform itself, so every URL below can be bookmarked, shared, and hard-refreshed safely. An address that matches no surface renders a "Page not found" panel with a way back — never a blank screen.

| Surface | Route | What it shows |
|---|---|---|
| Living Canvas | `/` | The ecosystem map for the current engagement: systems, data flows, manual processes, risks, the reduction thesis, and the decision log. |
| Future State Designer | `/designer` | The design-decision register: create decisions, run impact analysis, see every decision land in the tamper-evident log. |
| Capability Designer | `/designer/capabilities` | The editable capability model: a tree you can rename, reorder, reparent, and extend, with AI-drafted proposals. |
| Portfolio | `/portfolio` and everything under it | The holding-company dashboards: the Operating Partner view, the Subsidiary COO view, cascade detail, and coordination-contract detail. |

A few older links redirect rather than break: `/ecosystem` goes to the canvas, `/diagnostics` opens the canvas with the Risks panel, `/roadmap` and `/transformation` open the Plan panel, and `/decisions` opens the decision Log panel.

**Shared chrome.** On the canvas, a floating wordmark sits top-left (it carries the workspace's configured product name — "Meridian" in the demo) and three controls sit top-right: a link to the **Capability Designer**, a link to **Portfolio**, and the persona control. On other canvas-family pages the chrome collapses to a "Back to canvas" bar. The persona control behaves as described under [First-run orientation](#first-run-orientation): a dropdown in development/demo deployments (Architect and Executive selectable; four more marked Preview — the persona shapes how the AI assistant answers, it does not change what data you can see), and a read-only identity chip with sign-out under production single sign-on.

**Which engagement am I looking at?** The canvas-family surfaces scope themselves to a *workspace domain* — the engagement's data boundary. The platform resolves it automatically: a deploy-time pin wins if one is configured, otherwise your selection, otherwise the only (or first non-demo) domain in the database. On a freshly installed environment with no ingested data, the canvas shows a "No workspace yet" panel explaining that data arrives through ingestion — that is a normal first-run state, not an error.

## Living Canvas

The Living Canvas, at `/`, is the home surface: one screen that draws every system the client runs, every data flow between them, and every manual process people perform by hand. Screenshot: [`01-living-canvas.png`](../acceptance/final/01-living-canvas.png).

On your first visit a one-time welcome overlay explains the basics (it never returns after you dismiss it; it is remembered per browser).

### Reading the map

**System tiles** are the solid cards. Each shows:

- The system's name and vendor.
- A colored left edge plus a small pill for the system type — see the legend, bottom-left:

| Color | Type |
|---|---|
| Sky blue | Core system |
| Teal | Ancillary |
| Violet | Vendor |
| Gray | Legacy |

- An optional business-line chip.
- A **status dot** with the recorded lifecycle status:

| Dot | Status |
|---|---|
| Green | `active` |
| Red | `deprecated` |
| Amber | `in-review` |
| Gray | Any other or unknown status (shown verbatim) |

- A **finding badge** — a red or orange numbered circle counting open findings against that system. Red (with a pulsing halo around the whole tile) means at least one finding is `CRITICAL` or `HIGH` severity; orange means only lower severities are open.

**Manual-process tiles** are the amber, dashed-border cards tagged **Manual**. A 🧠 icon marks *tribal knowledge* — a process only one person knows how to run. A **HIGH RISK** chip appears when the process carries a high or critical risk rating.

**Edges** are the animated lines between tiles: each one is a recorded data flow (integration) between two systems.

Clicking any system tile opens the detail drawer on its **From** (provenance) tab — the evidence trail for that specific system. Tiles can be dragged to rearrange the view; scroll to pan, and pinch or use the controls to zoom.

When the AI assistant or a drawer panel refers to specific systems, the relevant tiles briefly light up with an amber **spotlight** ring so you can see what is being talked about.

### The summary ribbon (top center)

Four figures summarize the engagement (the open-risks figure carries the high/critical count beneath it). Know which are computed and which are estimates:

| Stat | Meaning | Source |
|---|---|---|
| systems | Systems on the map (in Target lens: systems remaining after reduction) | Engine — counted from ingested data |
| manual procs | Manual processes on the map | Engine |
| open risks | Open findings, with the high/critical count beneath | Engine — diagnostic findings |
| annual save target | Dollar figure from the reduction thesis | **Heuristic estimate** — labeled "heuristic estimate" on screen; never a computed plan figure |

Below the ribbon, a one-line subtitle changes with the active lens. In Target and Waves it explicitly says *"Illustrative retirement heuristics — plan engine pending"* — see the note under the lens rail.

### The lens rail (left edge)

Three lenses change what the map shows:

| Lens | What you see |
|---|---|
| **Today** | What the client runs right now. |
| **Target** | The end state: systems slated for retirement fade to gray; the survivors stay lit. |
| **Waves** | The journey: a timeline scrubber appears and systems fade out month by month as you drag. |

> **Not yet:** which systems fade, and when, in Target and Waves currently comes from illustrative demo heuristics, not from a computed transformation plan — the plan engine is not built. The on-screen subtitle says so whenever those lenses are active. Treat Target/Waves as a storytelling view, not a committed schedule.

### The wave scrubber (Waves lens only)

A timeline from month 0 to month 18, banded into **Wave 1 (months 1–6)**, **Wave 2 (7–12)**, and **Wave 3 (13–18)**. Drag the handle to any month, or press **Play** to animate one month every 0.7 seconds (Pause stops it; after month 18 the button becomes Replay). The current month and wave name show above the track.

### The project-health badge (top center, below the ribbon)

A pill with a progress ring reading the engagement's *completeness* score (0–100):

| Ring color | Meaning |
|---|---|
| Green | Ready to reduce — the evidence base supports executing cuts |
| Red | One or more blockers are open (a red counter chip shows how many) |
| Blue | In progress — no blockers, not yet ready |

Clicking it opens the **Guide** tab of the detail drawer. The score, blockers, and readiness flag are computed by the engine from the actual state of the engagement (sources ingested, coverage, unresolved conflicts, open high-severity findings).

### The DNA pill (top left, under the wordmark)

A small "DNA" button. Click it to see the engagement's configuration card: project name, domain, primary objective, reduction mode (e.g., AGGRESSIVE), target horizon in months, the entity types being tracked, and the regulatory context. Read-only.

### The detail drawer (right edge)

A rail of six tabs on the right edge; clicking one slides out a panel (clicking again collapses it). The active tab is mirrored into the URL (`/?view=risks` etc.), so panel views are linkable.

| Tab | Label · subtitle | What it contains |
|---|---|---|
| Guide | "Guide · What next" | The assistant's status board: completeness ring, **Next best action**, open **Blockers** (each with a button jumping to the tab where it can be addressed), and the four-phase **Playbook** (Sources → Coverage → Conflicts → Reduction). Each phase shows a status chip — ○ Pending, ⟳ In progress, ⚠ Blocked, ✓ Done — and its checklist, with unmet checks carrying a gap description. |
| Thesis | "Thesis · Keep vs cut" | The reduction verdict: a headline (e.g., "You run 12 systems. You need 2."), pills for have / keep / cut / savings-per-year, then a **Cut list** and **Keep list**. Each cut entry shows the estimated annual saving, what absorbs its work, and the reasons; hovering an entry spotlights that system on the map; switching lists spotlights the whole set. A "Before you cut" footer lists key risks to resolve first. The keep/cut split is computed deterministically by the reduction engine from ingested evidence; the dollar figures are heuristic estimates (see ribbon note). |
| Risks | "Risks · What hurts" | All open findings, sorted by severity, with count chips per severity. Severity vocabulary: `CRITICAL` (red), `HIGH` (orange), `MEDIUM` (amber), `LOW` (green); a finding carrying no severity renders as **INFO** (blue). Click a finding to expand its description, the recommendation, and chips for every affected system — expanding also spotlights those systems on the map. |
| Plan | "Plan · Waves" | The wave roadmap: before→after metrics (systems, roles, operating cost) and initiatives grouped under Waves 1–3, each with complexity, duration, and owner; "Jump →" scrubs the map to that wave's start month. See the callout below. |
| Log | "Log · Decisions" | The domain's decision log, newest first. Each entry shows its title, status chip (see [Future State Designer](#future-state-designer) for the vocabulary), decision type, date, and — in monospace — the hash chain link: `prev <hash> → <hash>`. The **Verify chain** button asks the engine to re-walk the entire chain and reports the result inline, e.g. "✓ All 6 decisions verified; chain is intact · 6/6 links" (green) or a red failure message identifying the break. Verification is a read-only check; it changes nothing. |
| From | "From · Source chain" | Provenance. With no system selected, it lists the evidence chain for every ingested entity in the workspace, grouped by source file; click a system tile first and it scopes to that system. Each evidence card shows the label, source file and document type, the extractor that produced it, a fingerprint (SHA-256 prefix) of the source document, and a confidence pip: green ≥ 90%, amber 70–89%, red below 70%. |

> **Not yet:** the transformation-plan engine is not built. In a default build the plan endpoint intentionally returns nothing (it is behind a demo fixture flag that ships off), and the **Plan** tab sits on "Loading transformation plan…" indefinitely. When the demo flag is on, what you see is a fixture mock-up, not computed plan data.

### The ambient chat bar (bottom center)

A persistent input bar — "Ask Meridian anything…" — with four suggestion chips when the conversation is empty (e.g., "What is my biggest risk?"). Press **Enter** or click **Ask** to send; Shift+Enter inserts a newline. Replies appear in a conversation bubble above the bar (collapsible). When the assistant used data tools to answer, small monospace pills under the reply name them — and the canvas spotlights the systems involved: finding-related tools light up at-risk systems and auto-open the Risks panel; overlap tools light up systems that share duplicate data entities.

> **Not yet:** the chat assistant requires the platform to be configured with an Anthropic API key. Without one, the rest of the canvas works normally, but asking a question returns an error reply in the bubble. (The backend answers with a structured `LLM_UNAVAILABLE` error; the current UI shows the generic "Request failed with status code 503" instead of that friendlier text — a known cosmetic gap.) The page never crashes or unloads.

### Add Source (bottom left, above the legend)

The **+ Add Source** button opens a modal that probes a URL you paste (for example an OpenAPI document). Press **Probe** and the platform identifies the API type — OpenAPI/Swagger, OData, GraphQL, plain JSON, or Unknown — with a confidence percentage (green ≥ 85%, amber 60–84%, red below), hints about what it found, and up to eight sample endpoints.

> **Not yet:** this modal is identification only. The **"Add to project"** button does not yet register the source or trigger ingestion — it simply closes the dialog. Actual data ingestion runs through the operator-side ingestion CLI (see [Operator workflows](#operator-workflows-cli)).

## Future State Designer

The decision register, at `/designer`: where target-architecture decisions are recorded, analyzed, and made permanent. Screenshot: [`02-designer.png`](../acceptance/final/02-designer.png).

### The decision table

Each row is one design decision, with sortable columns:

- **Title**
- **Type** — one of nine decision types:

| Decision type | Shape of the change |
|---|---|
| CONSOLIDATE | Merge systems (informational/blue) |
| INTEGRATE | Connect systems (blue) |
| MODERNIZE | Improve a system (green) |
| AUTOMATE MANUAL PROCESS | Replace hand-work with automation (green) |
| FORMALIZE MANUAL PROCESS | Document/standardize hand-work (green) |
| RETIRE | Remove a system (red) |
| ELIMINATE MANUAL PROCESS | Remove hand-work (red) |
| DECOUPLE | Break a dependency (amber) |
| ABSORB MANUAL PROCESS | Fold hand-work into a system (amber) |

- **Status** — `PROPOSED` (blue), `APPROVED` (green), `REJECTED` (red), `SUPERSEDED` (gray).
- **Affected** — counted honestly as "N systems · M processes".
- **Date**, and an **Actions** column.

Beyond human-authored decisions, the engines append their own governance events here — for example, activating a coordination contract writes an accountability row into the same register, so the log is a single audit trail across human and machine actions. (The full list of what gets anchored is under [Reading and verifying the decision log](#reading-and-verifying-the-decision-log).)

### Creating a decision

**New Decision** (top right) opens the form inline. Title and Description are required (inline validation); Decision Type is a dropdown of the nine types above; Rationale is optional. **Affected Systems** and **Affected Manual Processes** take comma-separated entity IDs.

> **Not yet:** there is no picker for affected systems/processes — you paste IDs. Practically, this form is used by the engagement team, not client executives. Executives consume decisions through the canvas Log tab and this table.

**Create Decision** submits; the new row appears immediately with status `PROPOSED`.

### How decisions become permanent

Every created decision is appended to the domain's hash-chained decision log: the entry stores a cryptographic hash of its content plus the hash of the previous entry, so any later tampering breaks the chain visibly. This is the same log the canvas **Log** tab displays (prev → current hashes on every entry) and the same chain its **Verify chain** button re-validates. Nothing in the UI can edit or delete a logged decision — superseding it is itself a new entry.

### Run Impact Analysis

Rows with status `PROPOSED` carry a **Run Impact Analysis** button. It sends the decision's scope and description to the AI for evaluation and opens a panel below the table containing a written summary plus two tables — **System Impacts** and **Process Impacts** — each rated HIGH (red), MEDIUM (amber), or LOW (green) with an explanation. **Close** dismisses the panel.

> **Not yet:** without an Anthropic API key, the analysis fails and the panel shows an error banner; the decision itself is unaffected.

## Capability Designer

A full-screen, two-pane editor for the engagement's capability model, at `/designer/capabilities`. Screenshot: [`03-capability-designer.png`](../acceptance/final/03-capability-designer.png). (If your workspace renames "Capability" to another term, every label here follows suit.)

**Header**: a "Canvas" link back to `/`, the surface title, a live count of capabilities, and two actions — **Draft from Universe** and **Create Capability**.

### The tree (left pane)

All capabilities as a collapsible hierarchy. Interactions:

- **Click** a row to select it; the right pane shows its detail.
- **Drag onto another row** to reparent — the move is saved immediately.
- **Drag within the same parent** to reorder siblings — also saved immediately.

If a save conflicts with someone else's concurrent change, the tree snaps back to the server's truth and a dismissible red banner explains: *"…conflicted with a concurrent change — the tree was refreshed; please retry."* Your screen never silently shows a move the server rejected.

### The detail pane (right pane)

For the selected capability:

- **Name** — click the pencil to edit inline. Enter saves, Escape cancels, and an empty name is rejected with an inline error. Every accepted rename is recorded in the capability's server-side rename history (the history itself is not yet displayed on this screen).
- **Description** — same click-to-edit pattern; Save/Cancel buttons (Enter inserts a newline here).
- Read-only fields: **Status** (`ACTIVE` green, `DEPRECATED` red, others gray — e.g. `DRAFT`), **Level** (depth in the tree), **AI-generated** (yes/no), **Created by**, and a technical block with the record's identifiers, version, and timestamps.

Failed edits follow the same conflict discipline as the tree: values refresh to server truth and the error is shown, never swallowed.

### Create Capability

A dialog: display name (required), an internal name auto-derived from it (lowercase, dashes; editable), an optional parent (pre-filled with your current selection), and an optional description. On create, the tree refreshes and the new capability is selected.

### Draft from Universe (AI assist)

Opens a dialog that immediately asks the AI for capability proposals grounded in the engagement's ingested evidence (the "Universe of Inputs"). Each draft card shows a proposed name and description, how many evidence sources ground it, and a reference to the underlying assertion. If nothing comes back, the dialog says why (for example, all candidates were filtered for weak grounding) rather than showing an empty panel.

> **Not yet:** drafts are a **read-only preview** — there is no accept button; committing a draft into the tree ships in a later phase (the API verb already exists — see [The generative path](#the-generative-path-ai-drafts-to-accepted-capabilities)). And this feature is AI-backed: without an Anthropic API key the dialog shows a "Request failed" error.

## Portfolio

The holding-company surfaces, under `/portfolio` — presenting a fictional demonstration portfolio, **Beacon Industries**, 22 subsidiaries across 9 industries. These dashboards always render in dark mode and carry their own chrome: an amber provenance banner on top, then a header bar showing where you are, with **Switch vantage point** and **Exit** controls.

**Read the banner — it tells you what is real.** These surfaces mix two kinds of data, and the banner states which mode you are in:

- *"Beacon Industries — Demo workspace · live engine data + illustrative fixtures"* — the platform's engines are answering: cascades, contracts, agents, and portfolio statistics are live, served from the seeded engagement; the remaining content (subsidiary roster, revenues, opportunity cards) is illustrative fixture data.
- *"Beacon Industries — Fixture dataset (demo)"* — the backend is not answering; everything on screen is deterministic illustrative data.

The sections below note per-element which engine serves it live.

### Landing page — the vantage-point picker

Screenshot: [`04-portfolio-landing.png`](../acceptance/final/04-portfolio-landing.png). Two cards:

- **Corporate Operating Partner** — the federated view across all subsidiaries. "Enter portfolio dashboard" goes to `/portfolio/dashboard`.
- **Subsidiary COO** — one operating company's view. Choose a subsidiary from the dropdown, then "Enter subsidiary view" goes to `/portfolio/subsidiary/<slug>`.

A text link below exits to the standard product, and a footnote restates the fixture/live data split.

### Corporate Operating Partner dashboard

At `/portfolio/dashboard`. Screenshot: [`05-portfolio-dashboard.png`](../acceptance/final/05-portfolio-dashboard.png). Five zones, top to bottom:

**Zone 1 — Header strip.** Portfolio identity figures (subsidiary count, industries, total revenue, active phases — all fixture), plus a provenance chip on the right repeating the live/fixture disclosure; hover it for the precise breakdown.

**Zone 2 — Coordination signals.** Four stat cards:

| Card | Meaning | Source |
|---|---|---|
| Cross-portfolio opportunities | Capabilities being redundantly rebuilt across subsidiaries | **Always fixture** (kept coherent with the Zone 4 cards below) |
| Agents in intervention | Deployed AI agents currently requiring human intervention, "of N registered" | **Live** — agent registry. If the registry is unreachable the card shows an em-dash and says "registry unavailable — counts withheld, not zero"; it never fabricates a 0 |
| Lifecycle stalls | Subsidiaries sitting 90+ days in their current phase | **Live** — and when subsidiaries have no lifecycle data at all, the card says so separately ("· N unphased (no lifecycle data)") rather than hiding them in the stall count. In the current demo seed, lifecycle data exists only for the root engagement, so this card honestly reports the 22 satellite subsidiaries as unphased — even while the fixture phase grid below plots them confidently. Trust the card; the grid is illustrative |
| Coordination signals | Cross-subsidiary coordination activity — computed as contract events plus cascade actions over the trailing 7 days | **Live** |

**Zone 3 — "Where each subsidiary sits."** Seven columns, one per lifecycle phase — **Plug-in, Design, Map, Plan, Execute, Monitor, Automate** — with each subsidiary as a clickable chip (left-edge color = its industry). Fixture data. Clicking a chip enters that subsidiary's COO view.

**Zone 4 — "Where coordination is failing."** AI-surfaced opportunity cards (fixture): title, narrative, affected-subsidiary chips, estimated annual impact. Cards that have been promoted to a documented coordination contract are clickable and open the contract detail surface; cards without a contract are display-only. The subsidiary chips inside a card navigate to that subsidiary without triggering the card.

**Zone 4.5 — "Cascades you've issued."** **Live** (fixture fallback in degraded mode). A cascade is a critique an executive issues down the chain of command; each card shows the critique's first sentence, when it originated, the chain depth it currently sits at ("Currently at L2 · 3 level chain"), and an attention chip — **Decline pending** (red), **Escalating** (blue), or **Absorbed** (green), plus a separate amber **Overdue** chip when a response deadline has passed. The full signal vocabulary, including wire values, is under [Attention signals](#attention-signals--what-the-chips-mean). Cards order themselves by operator urgency: decline-pending first, then overdue, then the rest by recency. Only in-flight cascades appear here; if none are in flight the section disappears. **Inspect cascade** opens the [cascade detail page](#cascade-detail-page).

> **Not yet:** there is no "issue a cascade" button anywhere in the client UI — cascade *authoring* is operator-grade only (API/MCP; see [Authoring a cascade](#authoring-a-cascade--what-works-today)). This dashboard is the read side.

**Zone 5 — Full portfolio roster.** A sortable table of all subsidiaries (fixture): name and industry, phase pill, capability/process counts, agent count (with a red "N intervention" chip where applicable), findings count (amber-highlighted above 5), and revenue. Every name links into the COO view. On narrow screens the table becomes stacked cards.

### Subsidiary COO view

At `/portfolio/subsidiary/<slug>` — the single-company vantage point: one operating company plus the parent's signals reaching into it. Top to bottom: a header strip (identity, description, revenue/headcount/HQ rail — fixture), then the **portfolio context band**: "Part of Beacon Industries · Currently in [phase] phase · N cross-portfolio opportunities involve you · N federation invitations pending." Segments with a non-zero count are clickable; the federation-invitation segment opens the relevant coordination contract with a back link that returns here.

Four tabs follow; the active tab rides the URL (`?tab=operations`), so tab views are linkable and browser back/forward works.

| Tab | Contents | Source |
|---|---|---|
| **Capabilities** | A read-only two-pane copy of the capability model (tree + detail) scoped to this subsidiary. It deliberately mirrors the real Capability Designer's layout, but nothing here is editable — the page says so. | Fixture |
| **Environment** | System cards (kind, vendor, integration count), one card flagged where the platform sees concentrated tribal-knowledge risk, plus external integration partners. | Fixture (partners are illustrative per-industry stubs) |
| **Plan** | Initiatives in flight with per-initiative progress meters, value, and target dates; the seven-phase lifecycle spine with the current phase highlighted; and the canonical completion requirements for leaving the current phase. | Fixture |
| **Operations** | What the company is running today — see below. | **Mixed; agent data is live** |

**The Operations tab** (screenshot: [`08-subsidiary-operations.png`](../acceptance/final/08-subsidiary-operations.png)) has three regions:

1. **Fleet stat strip** — *Agents active* and *Agents in intervention* come **live** from the agent registry whenever it answers (each Beacon subsidiary maps to its own seeded engagement domain); *Open findings* is still fixture data.

2. **Recent agent activity** — the agent fleet, **live** from the registry. Each row: name, role, latest observed activity (derived from real sightings and lifecycle events — "No activity observed" when there is none, never an invented line), a provenance line ("Deployed by … · date · Oversight: chain"), and a governance-status pill — **Operating** (green), **Shadow mode** (blue), **Paused** (amber), or **In intervention** (red); the full status vocabulary, including wire values and display precedence, is under [Agent statuses](#agent-statuses). Retired agents never appear in this list. Clicking a row expands its **deployment panel**: the deployment criteria recorded when the agent was approved (fetched on demand; "not yet recorded" when absent — never fabricated) and the numbered **oversight chain** of accountable humans.

   > **Not yet:** this tab is **read-only**. The status pill is the *governance* state — what the registry says the agent should be doing — which is not the same as runtime confirmation. There are no pause/kill buttons in this UI; agent control verbs, and the runtime-confirmation and enforcement-mode disclosures that come with them, live in the operator-grade agent API today and ship here in a later phase. See [Controlling an agent](#controlling-an-agent-the-honest-control-model).

3. **Open findings** — severity-pilled cards (Low blue / Medium amber / High red) with detail, related-system chips, and how long ago each surfaced. Fixture data.

### Cascade detail page

At `/portfolio/cascade/<id>`. Screenshot: [`06-cascade-detail-decline-pending.png`](../acceptance/final/06-cascade-detail-decline-pending.png). **Live** from the cascade engine (fixture fallback in degraded mode); an unknown id renders a "Cascade not found" panel with a back link.

Top to bottom:

1. **Header** — an eyebrow stating the aggregate state (*In-flight cascade*, *Resolved cascade — looks good*, or *Resolved cascade — declined*; a resolved decline is a peer outcome, never failure language), the original critique verbatim (it travels the chain unchanged), who issued it and when, and an **Acceptance criteria** panel — what "fixed" means, in the issuer's words. These never change after issuance.
2. **Level-by-level transparency** — one card per level of the chain (L0, L1, …), in chain order: owner, date, a state pill (`looking` / `cascaded` / `absorbed` / `declined` — vocabulary under [Cascade and level state vocabulary](#cascade-and-level-state-vocabulary)), and that level's text — the refined critique it passed down, the decline reasoning it passed up, an "absorbed the work" note, or "Awaiting response since *date*" for a still-looking owner.
3. **Action timeline** (live mode only) — the append-only log of every recorded step, oldest first, numbered: Issued, Cascaded, Absorbed, Declined, Recascaded, Resolved — looks good, Resolved — declined, each with actor, level, date, routing ("→ Curasync HIM Director"), and any attached reasoning. This includes history the level cards alone cannot show — for example, a declined round that was later recascaded. This is the issuer's single audit trail.
4. **Cascade summary** footer — the current resting state in one line.

There is deliberately no edit affordance anywhere on this page. The record is append-only.

### Contract detail page

At `/portfolio/contract/<id>`. Screenshot: [`07-contract-detail.png`](../acceptance/final/07-contract-detail.png). **Live** from the contract engine (fixture fallback in degraded mode); reachable from opportunity cards on the dashboard and from a subsidiary's federation-invitation segment (the back link returns wherever you came from). Unknown ids render a "Contract not found" panel.

A coordination contract is a documented, monitorable agreement between parts of the portfolio — not a slide-deck proposal (the full concept is in [Part 2 — Coordination contracts](#part-2--coordination-contracts)). The surface renders its terms first-class:

- **Header** — contract type (*Cross-portfolio opportunity* or *Federation invitation*), title, proposal date, linked subsidiaries, and the estimated value when one is recorded.
- **Context** — the narrative of why this contract exists.
- **Parties and commitments** — each party with its enumerated obligations.
- **Enforcement** — three panels: **Monitoring** (what the platform watches, in prose), **Mechanism** (the monitoring rule in machine-readable form, shown verbatim), and **Decline triggers** — the documented conditions under which a party may decline without being in breach.
- **Available actions** — **Review proposal** and **Decline** buttons.

> **Not yet:** the Review and Decline buttons are **deliberately inert** in this release. They render (disabled, with tooltips saying the flow is not defined in v1) because review and decline are first-class concepts of the contract model, but the product flows have not shipped — contract responses are currently operator-grade actions (`POST /api/v1/contracts/{idOrKey}/respond`, `…/decline`, or the MCP tools). Also, this surface does not yet display the contract's lifecycle status, monitor outcomes, breaches, or attestations: a contract that is `ACTIVE` with an open breach renders the same terms-first view as a proposed one ("Proposed Apr 1 2026 …"). That operational state is fully available through the read API and the [signals endpoint](#signals--the-contract-heartbeat-for-a-domain), which is where operators watch it today; the activation event is also visible as a ledger row in the Future State Designer's decision register.

## Cascades and Contracts

Meridian Substrate gives a holding company two complementary governance instruments. A **cascade** moves a leader's critique *down* an accountability chain, one named owner at a time, and carries every answer back up. A **coordination contract** binds two or more parties *across* the portfolio to explicit commitments, with Substrate monitoring compliance.

Both instruments share one binding principle: **a documented "no" is a legitimate outcome, never an error.** A subsidiary that declines a critique with reasoning, or a party that declines a contract by citing a pre-agreed trigger, has completed the process correctly. Nothing in the product — not a status code, not a color, not a word — treats a decline as a failure. The reddish "destructive" accent you will see next to declines means *this needs leadership attention*, not *something broke*.

## Part 1 — Cascades

### What a cascade is

A cascade starts when a leader (the **originator** — typically the Operating Partner) issues a critique with explicit **acceptance criteria**: what a satisfactory answer looks like, including, where appropriate, the conditions under which a documented decline is acceptable. The critique is routed to a named first owner (a subsidiary COO, for example). That owner reads it and does one of three things: passes it down with their own refinement, takes the work on themselves, or declines with reasoning. Each handoff adds a **level** to the chain — level 0 is the originator, level 1 the first owner, and so on.

Two things make this different from forwarding an email:

1. **The original critique is durable.** The text the originator wrote, and the acceptance criteria, are frozen at issuance and visible unmodified at every level. Each level adds its own refinement *alongside* the original — refinements never replace or dilute it.
2. **The chain is the audit trail.** Every action — issue, cascade, absorb, decline, recascade, resolve — is recorded permanently as it happens, with the actor, the timestamp, and the text they wrote. Nothing in this log can be edited or deleted afterward, by anyone.

### Where you see cascades

- **Operating Partner dashboard** (`/portfolio/dashboard`) — the **"Cascades you've issued"** grid, described in the [dashboard walkthrough](#corporate-operating-partner-dashboard): every in-flight cascade as a card with its attention chip, ordered decline-pending first, then overdue, then by recency. In the demo workspace this grid is live engine data.
- **Cascade detail page** (`/portfolio/cascade/<id>`) — the full record: frozen critique and acceptance criteria, level-by-level transparency, the append-only action timeline, and the resting-state summary. The page walkthrough is under [Cascade detail page](#cascade-detail-page); the state vocabularies it renders are defined below.

### Level verbs — what each owner can do

When a cascade arrives at your level you are the single accountable owner of the next move. You act exactly once.

| Verb | Who can use it | What it does |
|---|---|---|
| **Cascade** | The owner currently "looking" at the critique | Passes the work down: you write your own refined critique (the original stays attached) and name the next owner. The chain grows one level. |
| **Absorb** | The owner currently "looking" | You take the work yourself — downward propagation stops. You document what you will do (a refined critique is required). The cascade stays open until the originator reviews and resolves it. |
| **Decline** | The owner currently "looking" | You decline with documented reasoning (required). The cascade does not end — a *pending decline* now sits with the level above you, and the originator is alerted. |

A decline is answered by the level **above** the declining level, which has exactly three options:

| Decline-response verb | What it does |
|---|---|
| **Endorse the decline** (decline) | You agree with your subordinate's decline and add your own reasoning. The pending decline moves up one level — a run of endorsed declines can climb all the way to the originator. |
| **Recascade** | You disagree: you rewrite the critique with new framing and send it back down. The level below is reactivated as a fresh round, possibly with a different owner. The earlier decline stays in the permanent record. |
| **Absorb over the decline** | You take the work yourself rather than push it back down. The decline below you stays in history as answered, no longer pending. |

The bottom of the chain — the level whose state currently determines what happens next — is called the **frontier** in API responses ("Currently at L2" on the dashboard cards).

### Cascade and level state vocabulary

**Cascade (whole-chain) states:**

| State | Wire value | Meaning |
|---|---|---|
| In-flight | `in-flight` | The cascade is open; someone in the chain owes an action, or the originator owes a resolution. |
| Resolved — looks good | `resolved-looks-good` | The originator reviewed the outcome and closed the cascade satisfied. Terminal. |
| Resolved — declined | `resolved-declined` | The originator formally accepted a documented decline and closed the cascade. **A peer terminal outcome, equal in standing to "looks good."** Terminal. |

Terminal means terminal: no action of any kind is accepted afterward. If the issue resurfaces, a new cascade is issued (the new record can reference its predecessor).

**Level states** (the pill on each level card):

| State | Pill | Meaning |
|---|---|---|
| Looking | `looking` (amber) | This owner has the critique and has not yet acted. The detail page shows "Awaiting response since *date* — no decision yet." |
| Cascaded | `cascaded` (blue) | This level refined the critique and passed it down. |
| Absorbed | `absorbed` (green) | This level took the work; the chain stops here. |
| Declined | `declined` (red accent) | This level declined with documented reasoning. Leadership attention, not failure. |

### Attention signals — what the chips mean

Every in-flight cascade carries exactly one attention signal, recomputed on every action. It drives the chip on the dashboard card and the ordering of the grid.

| Signal | Wire value | Tone | Meaning |
|---|---|---|---|
| Decline pending | `decline-pending` | Red accent (leader attention) | The bottom of the chain is a decline that nobody has answered yet. The level above the decliner owes a response, and the originator may resolve by accepting the decline. |
| Escalating | `escalating` | Blue (informational) | The critique has been passed down and the bottom owner is still looking — the chain is working as designed, response outstanding. |
| Absorbed | `absorbed` | Green | Someone took the work. The cascade awaits the originator's review and resolution. |
| Resolved — looks good | `resolved-looks-good` | Green | Terminal; never shown in the in-flight grid, but the record remains readable. |
| Resolved — declined | `resolved-declined` | Neutral | Terminal; same as above. |

**Overdue** is *not* a signal — it is a separate flag that overlays any in-flight signal as an additional amber "Overdue" chip when a response deadline has passed (see [Deadlines and overdue notifications](#deadlines-and-overdue-notifications)).

For operators, `GET /api/v1/cascades/attention?domainId=<id>` returns the same information as a worklist of *whose action is demanded right now* — one item per outstanding obligation, e.g. "Decline pending — Curasync HIM Director declined at L2" (originator's item) and "Respond to decline — … response owned at L1" (the response owner's item). `GET /api/v1/cascades/stats?domainId=<id>` returns the counts by state and signal.

### Originator resolution — closing a cascade

Only the originator can close a cascade, and only with a written resolution note. There are exactly two resolutions:

- **Looks good** — the originator is satisfied with the outcome (typically after an absorb, or when the chain's answer met the acceptance criteria). Note that acceptance criteria can pre-authorize a documented decline as a satisfactory answer — in that case the originator may still close as "looks good."
- **Accepted decline** — there is a pending decline at the bottom of the chain, and the originator formally accepts it, citing the specific decline being accepted. The cascade closes as `resolved-declined`, and the accepted decline's reasoning becomes part of the permanent resolution record.

Either way, every level owner in the chain is notified of the resolution.

### Deadlines and overdue notifications

Each cascade carries a response SLA, set at issuance:

| Setting | Default | Meaning |
|---|---|---|
| Response SLA | **7 days** per level | Each newly activated level owner has this many days to act before the level is flagged overdue. Set per cascade at issuance; the engagement default applies when unset. |
| Re-notification interval | 3 days | After the deadline passes, reminders repeat at this interval. |
| Re-notification limit | 5 | Reminders stop after this many windows. |
| SLA = 0 | — | Disables deadlines for that cascade entirely (no deadline, never overdue). The demo data uses this so the demo stays deterministic. |

An hourly background sweep checks deadlines. Two important guarantees:

- **The sweep never changes state.** Going overdue never auto-advances, auto-reassigns, or auto-declines anything. It only sets the overdue flag and writes notifications. The flag clears as soon as the level acts.
- The same applies to a decline that sits unanswered past the SLA — the originator and the response owner are reminded ("decline overdue"), but nothing moves without a human verb.

Overdue and other cascade notifications (issued, level activated, cascaded, absorbed, declined, decline-pending, sibling-decline advisories, resolution) are written to a per-recipient feed, readable at `GET /api/v1/cascades/notifications?recipientId=<memberId>` with a mark-read endpoint.

> **Not yet:** there is no notification bell in the web UI — today the feed is an API/operator surface, and attention reaches leaders through the dashboard signal chips and ordering. Email/webhook delivery is not built.

### Authoring a cascade — what works today

> **Not yet:** leaders cannot author cascades in the UI. There is no "issue a cascade" button anywhere in the product — the authoring experience (entry points, routing suggestions, AI-assisted drafting and preview) is awaiting a dedicated design session and will not be built ahead of it. Acting on levels (cascade/absorb/decline/recascade/resolve) also has no UI affordances yet; the detail surfaces are read-only.

**Operators can author and drive cascades through the REST write API** on behalf of the engagement. The flow is draft-then-issue:

1. `POST /api/v1/cascade-drafts` — create a draft with the domain, authoring mode, critique text, acceptance criteria, and optionally an SLA in days.
2. `PATCH /api/v1/cascade-drafts/{id}` — refine the draft while it is open.
3. *(Optional)* `POST /api/v1/cascade-drafts/{id}/suggest` — request an AI-drafted critique/criteria suggestion. This requires the orchestrator integration to be enabled on the instance; when it is disabled (the default), this endpoint returns 404 and everything else works unchanged. A suggestion never auto-issues — a human promotes the text into the draft.
4. `POST /api/v1/cascade-drafts/{id}/issue` — issue the cascade, naming the level-1 owner. This creates the live cascade, freezes the critique and criteria, starts the level-1 clock, and notifies the owner. (`POST …/discard` abandons a draft instead.)

Acting on a live cascade uses one endpoint per verb: `POST /api/v1/cascades/{id}/levels/{depth}/cascade | absorb | decline | recascade`, and `POST /api/v1/cascades/{id}/resolve` for the originator. Every write body requires an `idempotencyKey` (safe retries — replaying the same key returns the original result without duplicating the action) and accepts an optional `expectedChainSeq` fencing token. Actor identity is taken from the persona headers (`X-Persona-Id`, `X-Persona-Label`); list endpoints require `domainId`.

Two response conventions worth internalizing (the full code list is in the [error code vocabulary](#error-code-vocabulary)): a legitimate decline — `decline` on a level, or `resolve` as declined — always returns **200**, by design; and the 409 family (`CASCADE_STALE_STATE`, `CASCADE_ILLEGAL_TRANSITION`, `CASCADE_TERMINAL`) means "the chain moved on or the verb is not legal from here" — the response body carries the current cascade state, so refresh and re-evaluate rather than retry blindly.

## Part 2 — Coordination contracts

### What a coordination contract is

A coordination contract is a structured agreement between named **parties** — subsidiaries, corporate org units, a combined group of subsidiaries, or an external party — that makes cross-portfolio cooperation explicit and monitorable. Two contract types exist today: **cross-portfolio opportunity** (several subsidiaries coordinate on a shared initiative) and **federation invitation** (one subsidiary invites another to share a capability, such as an AI agent).

Each contract carries:

- **Parties and commitments** — each party's concrete obligations, in plain language, some with a due date or a reporting cadence (monthly/quarterly).
- **Decline triggers** — the pre-agreed conditions under which a party may walk away *without breach*. The right of refusal is scoped in advance, in writing.
- **Enforcement text** — a plain-language description of how compliance is monitored, plus the machine-oriented mechanism text shown verbatim in a monospace panel.
- **Monitors** — the automated checks compiled from the enforcement terms (see below).

### Where you see contracts

- **Contract detail page** (`/portfolio/contract/<key>`) — the terms-first surface walkthrough, entry points, and its current limitations (inert Review/Decline buttons; no operational state displayed) are under [Contract detail page](#contract-detail-page).
- **Operating Partner dashboard** — the coordination-signals strip includes a live "Coordination signals" count (contract events plus cascade actions over the trailing 7 days).

### Contract lifecycle

| Status | Meaning |
|---|---|
| `PROPOSED` | Drafted and awaiting party responses. Contract content (parties, commitments, triggers, value, enforcement text) is frozen the moment the first party responds. |
| `ACTIVE` | Every required party accepted. Monitors arm and begin sweeping; enforcement guards (if any) go live. |
| `DECLINED` | Terminal — and **legitimate**. Reached either when any required party declines at the proposal stage (reasoning required), or when a party invokes a pre-agreed decline trigger on an active contract. Monitors suspend and guards deactivate immediately. Never error-coded, never colored as a breach. |
| `WITHDRAWN`, `CONCLUDED` | Reserved in the data model; **unreachable today** — no flow produces them yet. |

Party responses are one-per-party and immutable once recorded. Open breaches never block a decline — they survive it and keep their human lifecycle (you can still acknowledge, remediate, or waive a breach on a declined contract).

### Monitors — how Substrate watches compliance

Each monitor is an automated check compiled from the contract's enforcement terms, running on a cadence (a background sweep claims due monitors roughly every 60 seconds; each monitor then waits for its next cadence boundary). Monitor types:

| Rule type | What it checks |
|---|---|
| `ATTESTATION_CADENCE` | Did the responsible party attest for the most recent closed period (e.g., the monthly progress report), within the grace window (e.g., 7 days)? |
| `METRIC_THRESHOLD` | Compares reported metric values across parties against a threshold (e.g., utilization imbalance > 30%). **Requires data from all required parties for the period — it never computes on partial data.** |
| `MCP_PROBE` | Liveness only: is the party's registered agent endpoint reachable? Never produces a breach. |
| `UNSTRUCTURED` | The enforcement term could not be compiled into a rule. Every tick asks for human verification rather than pretending to check. |

Monitor status is `ACTIVE` or `SUSPENDED` (all monitors suspend when a contract is declined; they are only armed while the contract is active). Each run records a permanent, bounded trace of what it evaluated. Run outcomes:

| Outcome | Meaning |
|---|---|
| `COMPLIANT` | The period checked out. |
| `NOT_DUE` | The period (plus grace) has not closed yet — nothing to judge. |
| `BREACH_DETECTED` | A real, evidenced miss — e.g., no attestation past the grace deadline, or a threshold exceeded on complete data. |
| `INDETERMINATE` | **The monitor could not observe enough to judge — and the system refuses to guess.** Missing metric samples, an unreachable endpoint, or an uncompilable rule produce a *needs-input* signal (data is stale / adherence unobservable / human verification due) — never a fabricated breach, and never a fabricated green. |
| `ERROR` | The evaluator itself failed. An administrative signal; breach state is untouched. |

Operators can force a check anytime with `POST /api/v1/contracts/{idOrKey}/monitors/{monitorId}/run-now` and read history with `GET …/monitor-runs`.

### Attestations

For cadence-bearing commitments, the responsible party files a periodic **attestation** — a dated, attributed compliance claim, optionally with a statement and evidence references. One attestation per commitment per period (resubmitting updates it; every submission is kept in the event history).

| Claim | Effect |
|---|---|
| `ON_TRACK` / `COMPLETE` | Commitment is compliant for the period. |
| `AT_RISK` | Commitment flagged at-risk — a visible signal, not a breach. |
| `MISSED` | An honest miss — opens a breach immediately, attributed to the attestation. |

Operators and party agents file via `POST /api/v1/commitments/{commitmentId}/attest` (or the `attest_commitment` MCP tool). Period keys are calendar-based: `2026-05` for monthly, `2026-Q2` for quarterly.

> **Not yet:** there is no attestation form in the web UI; attestations are filed via API or MCP only.

**Commitment compliance states** (per commitment, shown in API detail responses):

| State | Meaning |
|---|---|
| `UNEVALUATED` | No monitor or attestation has judged it yet (also the resting state for commitments without cadence/monitors). |
| `COMPLIANT` | Last evaluation passed. |
| `AT_RISK` | Flagged by an at-risk attestation. |
| `BREACHED` | An open breach references this commitment. |
| `WAIVED` | Its breach was waived by a human. |

### Breaches and the human verbs

A breach is opened by a monitor or by a `MISSED` attestation — never by a hunch. Its lifecycle is forward-only and fully attributed:

| Status | Meaning |
|---|---|
| `DETECTED` | Opened with evidence (which monitor, which commitment, which period). At most one open breach exists per source and period — a re-detection refreshes the existing breach rather than piling on duplicates. |
| `SURFACED` | Published to signals/dashboards (same sweep today). |
| `ACKNOWLEDGED` | A named human has seen it: `POST /api/v1/breaches/{id}/acknowledge`. |
| `REMEDIATED` | Closed with **evidence references required**: `POST /api/v1/breaches/{id}/remediate`. Returns the commitment to compliant. |
| `WAIVED` | Closed by **human judgment with a mandatory written rationale**: `POST /api/v1/breaches/{id}/waive`. **Waiving is human-only — agents are denied by a built-in, non-removable rule.** |

A later monitor pass over an open breach records "compliance restored" in the event history but does **not** auto-close the breach — a human confirms remediation, always.

### Decline triggers and DECLINE_ELIGIBLE

Most decline triggers are pure human-judgment grounds — always invocable, never "armed." Some carry a machine-evaluable predicate (e.g., *agent accuracy below 90%*, fed by reported metrics). The sweep evaluates these and flips the trigger's standing:

- **Dormant** — the condition does not currently hold.
- **Armed** — the condition holds *right now*. The contract surfaces a `DECLINE_ELIGIBLE` signal: a subtle leader-attention accent meaning "a pre-agreed exit condition is live; a party could legitimately invoke it." **Arming is not a breach and forces nothing** — invoking the decline remains a human decision.

Invoking a decline on an active contract (`POST /api/v1/contracts/{idOrKey}/decline`, or the `invoke_decline` MCP tool) requires citing one of the contract's own triggers and providing written reasoning. It returns success (HTTP 200) with the contract in its `DECLINED` terminal state — Substrate's vocabulary holds even here.

### The enforcement ladder — off, shadow, enforce

Contracts can carry **guards**: rules that watch governed write actions elsewhere in the platform (for example, promoting an agent) and may flag or block them while the contract is active. How much teeth the guards have is an instance-level setting (`substrate.contracts.enforcement.mode`):

| Mode | Plain meaning |
|---|---|
| `off` | The gateway is skipped entirely (a counter still ticks). Nothing is evaluated. |
| `shadow` (**the default**) | Every governed action is fully evaluated and the verdict is *recorded* — a would-be block is logged as `WOULD_DENY` — but **nothing is actually blocked**. This is recorded-intent mode: you can audit exactly what enforcement *would have done* before turning it on. |
| `enforce` | A matching deny-guard actually blocks the action. |

When a block happens in `enforce` mode, the caller receives **HTTP 409 with error code `CONTRACT_DENIAL`**, naming the contract and clause that blocked the action and a decision id for the audit trail. It is a governance refusal — "an active contract forbids this right now" — not a system failure; agents receive the same information as a structured tool error so they can adapt. The same ladder is what stands behind agent pause/kill controls elsewhere in the product — those controls disclose whether they are `recorded-intent` or an actual `gateway-block` (see [Controlling an agent](#controlling-an-agent-the-honest-control-model)).

Every gateway verdict uses this vocabulary (readable per contract at `GET /api/v1/contracts/{idOrKey}/decisions`):

| Decision | Meaning |
|---|---|
| `ALLOW` | No guard matched; the action proceeded. |
| `ALLOW_WITH_FLAG` | The action proceeded but something was noted for review (e.g., a guard flagged it, or a predicate could not be evaluated — flagged, never silently skipped). |
| `WOULD_DENY` | Shadow mode: this would have been blocked in enforce mode. The action proceeded. |
| `DENY` | Enforce mode: the action was blocked (the 409 above). |

If the gateway itself errors, it fails safe by principal: a human's action proceeds flagged; an agent's or service's action is denied.

**Demo note:** both demo contracts compile to zero blocking guards, so on the demo instance you will see monitors, breaches, and signals in action, but no denial will ever fire regardless of mode (the `substrate.demo.contracts.guards` lever exists for exactly this — see [The demo pack](#the-demo-pack-seeding-a-demonstration-environment)).

### Signals — the contract heartbeat for a domain

`GET /api/v1/contracts/signals?domainId=<id>` is the operator's one-call summary, computed live from the records (never cached or stamped):

| Field | Meaning |
|---|---|
| `pendingFederationInvitations` | Proposed federation invitations awaiting this domain's response. |
| `openBreaches` | Open breach count (with a by-severity breakdown when severities are set). |
| `declineEligible` | Currently armed decline triggers, by contract. |
| `needsInput` | Monitors that could not observe (stale data / unobservable endpoint / human verification due) — the honest "we don't know" list. |
| `monitorCoverage` | How many monitors are structured checks versus unstructured human-verify placeholders — a rubber-stamp visibility ratio worth watching. |
| `coordinationPatternsThisWeek` | Contract events plus cascade actions in the trailing 7 days (with a breakdown); feeds the dashboard's "Coordination signals" card. |
| `reviewBoundariesDue` | Contracts with a configured review window currently open (empty unless boundaries are configured). |

### What is not built yet (contracts)

> **Not yet:**
>
> - **No contract authoring over the API or UI.** The REST surface has no create/edit endpoint for contracts today; the demo contracts are installed by the engagement seed. Authoring exists at the internal service layer and ships with a later phase.
> - **Review/Decline buttons inert in the UI**, and **the contract page does not show live operational state** (status, monitors, breaches, attestations) — see [Contract detail page](#contract-detail-page); respond/decline and the operational state are API/MCP-only for now.
> - **Review boundaries** (time-windowed decline rights on federation contracts) are recorded but unenforced unless explicitly configured; when unconfigured, a decline simply proceeds with a note in the decision log.
> - **Notification channels** (email/webhook) are not built; the signals endpoint and the dashboard are the delivery surfaces.

## Agent operations

Substrate keeps a governance registry of every AI agent operating inside the engagement. The registry is not a deployment tool — agents run wherever they run (a customer's EHR integration, an orchestration platform, Substrate's own tool gateway). What the registry guarantees is that **no agent exists in the engagement without provenance, a mandate, and a named chain of accountable humans** — and that every control action you take against an agent is recorded, attributed, and honest about what it actually did.

### What every registered agent carries

A registration cannot be created without four facts. The API rejects a deploy that is missing any of them, so on every agent you look at, these are always present:

| Fact | What it is |
|---|---|
| **Deployed by** | The person or team that put the agent into operation (a label, optionally linked to an organization member). |
| **Deployed at** | The timestamp of the deployment act, set by the server — not self-reported. |
| **Deployment criteria** (the mandate) | The verbatim prose statement of what the agent is for and what it must achieve — for example, *"Route inbound patient messages to clinical or front-office queues with target 95% routing accuracy and zero PHI leakage to non-clinical staff."* The mandate is **immutable after deployment**. There is no edit operation. Changing an agent's mandate means retiring the registration and deploying a successor, which keeps a lineage link to its predecessor — so the audit trail can never be quietly rewritten. |
| **Oversight chain** | An ordered list of accountable people — for example *Front Office Manager → Compliance Officer → COO*. Position 1 receives signals first; unresolved problems escalate up the chain automatically (after 3 days for interventions, by default). At least one entry is required. |

Each registration also records where the agent executes (its **execution locus**: Substrate's own tool gateway, an orchestration platform, or a customer system — this determines what "pause" can physically mean, see the control model below), its **supervisory model** (full-auto / human-review-above-threshold / human-in-loop — recorded and displayed; Substrate does not itself enforce the threshold), and safety defaults (agents are **read-only at the gateway unless explicitly granted otherwise**).

### Where you see agents

In the demo workspace, open a subsidiary's dashboard (Subsidiary COO vantage point) and select the **Operations** tab — the three regions of that tab (live fleet strip, live agent activity rows with expandable mandate/oversight panels, fixture findings) are walked through under [Subsidiary COO view](#subsidiary-coo-view). Screenshot: [`08-subsidiary-operations.png`](../acceptance/final/08-subsidiary-operations.png).

> **Not yet:** the Operations tab is read-only — there are no pause/kill/throttle buttons on screen. All agent lifecycle controls are operator-grade REST calls. The product rule is that no control affordance ships in the UI ahead of the full honest-disclosure plumbing behind it.

### Agent statuses

The status pill is the agent's **governance state** — what the organization has decided about this agent. It is not a claim that the agent is currently running (that is liveness, a separate signal, below).

| Wire status | UI pill | Meaning |
|---|---|---|
| `live` | Operating | Approved to act. Has passed (or was deployed with explicit justification bypassing) shadow trial. |
| `shadow` | Shadow mode | Observation-only trial. At the gateway a shadow agent may report telemetry (heartbeats, metrics, activity) but every other governed write is refused. |
| `paused` | Paused | A human suspended it, with a mandatory recorded reason. Resume returns it to whatever mode it was in before the pause. |
| `intervention-required` | In intervention | At least one intervention is open on this agent (see below). **Important: this is an attention overlay, not a stop.** An agent in intervention is typically still running underneath — on the live demo instance, the No-Show Predictor shows `status: intervention-required` while its underlying operational mode is still `live`. Stopping it is a separate, separately-recorded act. |
| `retired` | *(not shown)* | Terminal — either retired in an orderly way or killed (the distinction is recorded). Retired agents are **excluded from fleet lists by default**; pass `includeRetired=true` to see them. No further lifecycle action is possible except closing out paperwork on already-open interventions. A successor deployment links back via predecessor lineage. |

When more than one condition holds, the displayed status follows a fixed precedence: **retired → paused → intervention-required → shadow/live**.

Pause, kill, retire, and a rejected promotion review are *never* presented as errors — not in HTTP status codes (always 200), not in color. They are legitimate governance outcomes.

### Controlling an agent: the honest control model

This is the part of the registry most worth understanding precisely.

Many agents run in systems Substrate cannot command — a vendor's runtime inside the customer's network, for example. A "Pause" control that claimed to stop such an agent would be a lie. Substrate refuses to tell that lie, structurally:

**Every control response declares what actually happened.** When you pause, kill, or throttle an agent, the response carries an `enforcement` block:

```json
{ "effect": "gateway-block" | "recorded-intent",
  "confirmationState": "not-required" | "pending" | "acknowledged" | "confirmed",
  "disclosure": "<plain-language statement of what this control physically did>" }
```

And **before** you act, the agent detail (`GET /api/v1/agents/{idOrKey}`) carries a `controlEffects` map showing the same effect-and-disclosure for pause, kill, and throttle — so you know what a control will do before you invoke it.

The two effects:

- **`gateway-block`** — the agent's actions transit Substrate's own tool gateway, and enforcement mode is active. The control is absolute on that path: the agent's next governed call is denied (within roughly 30 seconds of cache settling). Nothing further to confirm — `confirmationState` is `not-required`. This effect is claimed **only** when the agent executes through Substrate's MCP surface *and* the contracts enforcement ladder is in `enforce` mode.
- **`recorded-intent`** — Substrate cannot physically reach the runtime (or, in `shadow`/`off` mode, does not block at the gateway). The control records the order in the append-only event log, notifies the oversight chain, and opens **confirmation tracking**: the runtime may acknowledge the order (a machine fact, the one write a suspended agent is still allowed), and a named human may confirm the agent actually stopped, with evidence (`confirmationState`: `pending` → `acknowledged` → `confirmed`). Until then the agent shows as *"Paused — unconfirmed at runtime."* Crucially, **silence is never treated as compliance** — an absence of sightings never auto-confirms a stop. If a recorded-intent control sits unconfirmed past a timeout (24 hours by default), the oversight chain is notified.

The disclosure text varies honestly by situation. Verified examples from a running instance:

- Customer-system agent: *"records intent and notifies the oversight chain; substrate cannot reach this runtime — runtime acknowledgment and human confirmation are tracked, and sighting absence never auto-confirms."*
- Orchestrated agent: *"records intent; no new dispatches are submitted on this mandate and dispatch results are quarantined at apply — in-flight dispatches cannot be aborted."* (Work already in flight cannot be stopped, but its results are refused on arrival.)
- Gateway agent with enforcement in `shadow` (observe-only) mode: the disclosure states plainly that governed calls are *logged as would-deny and signaled, not blocked* — and that `enforce` mode would make it a hard block.

Pause, kill, and retire all require a recorded reason. Resume and promote are different in kind: Substrate's own registry state *is* the control plane for them, so they take effect immediately everywhere.

### Zombie signals

If a paused or killed agent is observed acting anyway, Substrate does four things at once: records the sighting (it is true and belongs in the record), denies the governed action when enforcement allows, appends a control-violation event, and raises a signal to the entire oversight chain:

| Signal | Meaning | Urgency |
|---|---|---|
| `ACTIVE_WHILE_PAUSED` | A paused agent was sighted acting. | Warning-to-destructive; opens/refreshes an intervention automatically. |
| `SEEN_AFTER_KILL` | A killed or retired agent was sighted acting. | The highest-urgency signal class in the registry. |

These appear in the fleet signals (`controlViolations`) and the attention feed. For an externally-run agent, this is exactly the design intent: Substrate cannot always stop the agent, but non-adherence to a recorded order becomes an immediate, audited, named-human-visible fact.

### Interventions

An intervention is a flag on an agent that says *a human needs to look at this* — drift detected, a gateway violation, a monitor alarm, or simply someone's judgment. Opening one requires a reason; it can be opened by a person or raised by the engine itself (engine-raised interventions deduplicate, so a repeating violation refreshes one open intervention rather than piling up copies).

The lifecycle is open → (optionally acknowledge) → resolve. Resolution requires both a category and a written note:

| Resolution | Effect |
|---|---|
| `resumed` / `no-action` / `reconfigured` | Closes the intervention; agent state unchanged by the resolution itself. |
| `demoted-to-shadow` | Additionally moves a live agent back to shadow trial (its shadow clock restarts). |
| `retired` | Records the judgment — but actually stopping the agent requires the separate kill/retire control, so both acts are individually attributed. |

An intervention left unresolved escalates: after the dwell window (3 days by default) the next person up the oversight chain is notified, then the next, on a bounded renotify schedule.

### Shadow-to-live promotion

Agents deploy into **shadow** by default. Promotion to `live` is gated:

1. Someone requests promotion (recorded, with a summary of the shadow window — how long, how many sightings, latest adherence).
2. A **compliance review** must be recorded and decided **approved** by a named reviewer with a written rationale, *after* the request. Without one, the promote call is refused with `PROMOTION_REVIEW_REQUIRED`.
3. The promote consumes that review — single use, recorded in the event log with the review's identity.

A rejected review is informational, never error-colored, and promotion can be re-requested. Deploying an agent straight to `live` is possible but requires an explicit recorded justification.

### Throttle budgets

Throttles set operating budgets on an agent without pausing it. Four budget kinds exist: `ACTIONS_PER_WINDOW`, `TOKENS_PER_DAY`, `SCHEDULE_WINDOW`, `SAMPLING_PCT`.

> **Not yet:** only `ACTIONS_PER_WINDOW` is actually enforced at the gateway today (over the limit, the agent's governed calls are denied — an `AGENT_GATE_DENIAL` whose reason is `AGENT_BUDGET_EXHAUSTED` — and a signal fires at exhaustion). The other three kinds are recorded as published obligations — the agent runtime can read them via its self-inspection tools, and they appear in the audit trail — but no gateway evaluator checks them yet. The throttle response's enforcement block discloses this the same way pause does.

### Liveness: when "last seen" can be trusted

The registry's liveness data comes **only from real sightings** — never from the fact that someone edited the agent's record. The sighting channels: a gateway action, a heartbeat, a metric report, a human attestation ("I observed this agent operating", with evidence), or dispatch activity. Two derived fields tell you how to read it:

| Field | Values | Meaning |
|---|---|---|
| Observability | `observable` / `attest-only` | Whether any telemetry channel exists at all. An `attest-only` agent (typical for customer systems with no gateway binding) can only ever be vouched for by a human — the UI rule is to render that plainly ("Not observable — last attested 12 days ago by RCM Lead") rather than show a fabricated green dot. |
| Staleness | `fresh` / `stale` / `never-seen` / `not-expected` | `stale` only when a reporting expectation exists and has been exceeded. An agent that is not expected to report is never painted red for silence. |

### Fleet signals and the attention feed

Two read endpoints aggregate the fleet for dashboards and triage. Verified live against the demo seed:

- **`GET /api/v1/agents/signals?domainId=`** — fleet counts by status, open interventions (with oldest), unconfirmed controls (e.g. the demo's Patient Message Router pause, pending runtime confirmation since April 19), control violations, liveness counts, pending promotions, exhausted budgets, and gate coverage (how many mandate gates are machine-checkable vs human-judgment vs not yet evaluated). Zeros are always explicit — an absent capability is reported as absent, never silently rendered as zero.
- **`GET /api/v1/agents/attention?domainId=`** — a triage list, most urgent first:

| Attention kind | Tone |
|---|---|
| `agent-in-intervention`, `control-violation` | destructive |
| `agent-paused`, `control-unconfirmed`, `criteria-at-risk` | warn |
| `human-verify-due` | info |

A composed engagement-wide feed at `GET /api/v1/attention?domainId=` merges agent attention with cascade attention into one list. Oversight-chain members also receive per-person notification rows (`GET /api/v1/agents/notifications?recipientId=`, with a mark-read action).

### The mandate as a living gate

Each clause of the deployment criteria is stored as a **criteria gate**, authored once at deploy and frozen with the mandate:

- **Structured** gates carry a machine predicate over reported metrics — e.g. *"target 95% routing accuracy"* becomes `routing_accuracy ≥ 0.95`, evaluated on a cadence. Standing flips between `met` and `at-risk`; an at-risk flip notifies oversight position 1 *suggesting* an intervention. Missing data **never** marks a gate at-risk — standing stays `unevaluated` and is flagged as needing input, distinct from green.
- **Unstructured** gates are clauses no machine can check — *"zero PHI leakage"* is the canonical example. These are flagged honestly rather than faked: on their cadence they emit a recurring **"Human verification due"** attention item so a named human re-attests them. A gate can be weak, but it is never silently inert.

Gates only ever signal. **No gate outcome auto-pauses or auto-kills an agent** — the system never takes a control action in a human accountability chain on its own.

### Operator reference: where the agent API lives

The full agent endpoint map (deploy, control verbs, promotion, interventions, oversight, liveness attestation), the request conventions every write shares (`idempotencyKey`, optional `expectedEventSeq`), and the MCP tool set are in the [API & MCP reference](#api--mcp-reference). One boundary worth restating here: agent runtimes themselves get a small, deliberately limited tool set on Substrate's MCP server — read their own registration and control state (`get_my_registration`, `get_my_control_state`), report telemetry (`agent_heartbeat`, `report_agent_activity`), and acknowledge a control order (`acknowledge_control` — the one write a suspended agent retains). **There are no agent-callable lifecycle tools.** Deploy, promote, pause, kill, and retire are human-only acts, enforced at the service layer and again at the gateway.

### Current limits (agents)

> **Not yet:**
>
> - **No control UI.** Pause/kill/throttle/promote are REST-only. The Operations tab shows the fleet, statuses, provenance, and mandates, but has no action buttons.
> - **Enforcement depends on posture.** Until the enforcement ladder is in `enforce` mode, even gateway-resident agents report `recorded-intent` with an explicit disclosure — read the disclosure, not your assumptions.
> - Oversight chain entries are labels (optionally linked to members); Substrate does not validate them against an org chart.
> - Adherence is just the latest reported `adherence` metric — if nothing has been reported, it renders as a dash.

## Diagnostics and findings

The diagnostic engine is how Substrate turns a client's raw documents into reviewable, evidence-backed findings about their operating environment — and how humans, not the AI, decide what becomes part of the engagement record.

### The pipeline in plain words

1. **Ingest documents.** The ingestion CLI takes customer source files (CSV, JSON, text) through `convert → stage → commit` — the full runnable procedure is under [Ingesting a client corpus](#ingesting-a-client-corpus-convert--stage--commit). Conversion extracts entities; staging writes human-reviewable JSON you inspect before anything is committed; commit creates or updates entities in the canonical model (systems, processes, canonical entities) for a named domain. Every source file is registered with its SHA-256 hash, every commit writes an audit run, and every extracted entity carries evidence rows pointing back to its source file. Re-running commit is idempotent — already-committed files are hash-skipped.
2. **Run a diagnostic.** The engine plans deterministic batches over the canonical model (per scope — systems, processes, canonical entities — plus one domain-wide pass for cross-cutting observations) and asks the AI to propose findings for each batch.
3. **Filter at the door.** Every AI-proposed candidate passes a single mandatory filter before anything is saved: parseability, allowed type and severity, required fields, and — for content findings — **grounding**: the candidate must cite evidence records that actually exist in the domain and that the AI actually retrieved during the run. A candidate citing invented evidence is dropped, and the drop is counted and reported per batch (you always see how many candidates were filtered and why). **A finding with no evidence chain cannot exist.**
4. **Humans curate.** Committed AI findings land as *pending review*. A named human accepts or rejects each one. Only then do they surface on client-facing views.

### What a finding is

A finding carries a title, a severity (`CRITICAL` / `HIGH` / `MEDIUM` / `LOW`), a type from a fixed taxonomy (duplication, coverage gap, data quality, and so on), a description, a recommendation, the affected systems/processes, and — for AI findings — a render-ready snapshot of its cited evidence (each item naming the source file and the document registry record behind it). Operator-authored findings exist too; they are marked `aiGenerated: false` and are not subject to AI curation.

### Reviewing a finding: the provenance walk

`GET /api/v1/findings/{id}/provenance` materializes the full audit chain in one response:

> finding → the AI assertion that produced it → the evidence records it cites → the registered source documents (file name, SHA-256, which ingestion run processed them) → the ingestion runs → any human curation decisions, in order.

Two honesty fields ride every walk:

- **`chainComplete`** — true only when every hop resolves. A dangling hop is *named*, never hidden. (Verified live: a demo-seeded operator finding honestly reports `chainComplete: false` with named gaps like "operator/fixture finding has no assertion_id (not subject to AI grounding)" and "(no ingest-run provenance)".)
- **`groundingMode: EXISTENCE_ONLY`** — this is the most important phrase to understand when reading AI findings. It means the machine has verified that the cited evidence **exists** and traces to a registered source document. It has **not** verified that the evidence semantically supports the claim. That judgment is the human's, and the accept action is its durable record. The product copy rule is binding: evidence citations are *existence-verified*; semantic support is *human-verified at acceptance*. Nothing in Substrate ever claims "machine-verified grounding."

A domain-wide integrity sweep is available at `GET /api/v1/diagnostics/verify?domainId=` — it walks every AI finding and reports totals, complete chains, broken chains with the missing hop named, and the grounding-mode census. Evidence is currently traced at **document level** (the chain ends at the source file, not a page or passage).

### Curating findings: accept and reject

- `POST /api/v1/findings/{id}/accept` — body `{rationale?, idempotencyKey!}`. Records that a named human verified the finding. Legal from pending review *and* from rejected (changing your mind is allowed, and both decisions stay in the history).
- `POST /api/v1/findings/{id}/reject` — body `{rationale!, idempotencyKey!}`. **The rationale is mandatory** — a reject without one is refused (`RATIONALE_REQUIRED`). Rejection returns 200: it is a legitimate product outcome, not an error, and it is never rendered in error colors.

Curation is **strictly per-item**. There is deliberately no bulk-accept: each acceptance is one human judgment about one claim, and the audit trail (an append-only curation event log) preserves every decision even when later reversed.

Curation status drives visibility:

| Status | Meaning | Who sees it |
|---|---|---|
| *(none)* | Operator-authored or demo finding — not subject to AI curation. | Everyone. |
| `PENDING_REVIEW` | AI-committed, awaiting a human decision. | Operators only. **Client-facing (COO) views exclude pending findings** — an executive never sees an unvetted AI claim. |
| `ACCEPTED` | A named human verified it. The acceptance instant becomes the finding's client-visible "surfaced" date. | Everyone. |
| `REJECTED` | Declined with rationale. Hidden from default lists (restorable with `includeRejected=true`); never counted in dashboards. | Operators on request. |
| `SUPERSEDED` | Replaced by a newer run (below). Hidden from default lists. | Operators on request. |

> **Not yet:** curation is API-only — there are no accept/reject buttons in the web UI, and the demo subsidiary dashboards still render illustrative fixture findings on their Operations tab (the demo banner says so). The live findings list rides `GET /api/v1/ecosystem/findings?domainId=` with filters `curationStatus`, `aiGenerated`, `includeRejected`.

### Diagnostic runs and their outcomes

Start a run with `POST /api/v1/diagnostics/runs` — body `{domainId!, idempotencyKey!, scopes?, batchSize?, requiredMinFindings?, dryRun?}` (`dryRun: true` returns the batch plan without executing). One run at a time per domain; a second start is refused with `DIAGNOSTIC_RUN_ACTIVE`. Track it with `GET /diagnostics/runs/{id}` — status, per-batch ledger, candidate/filtered/committed counts, a histogram of *why* candidates were filtered, and token/cost totals. `POST …/resume` re-executes failed or interrupted batches without ever duplicating findings (an interrupted batch committed nothing, so re-running it is safe); `POST …/cancel` stops a run cleanly and is never error-colored.

These are the run statuses you will see in the API and as `DIAG_EVENT` lines from the CLI runner ([Diagnostic runs from the command line](#diagnostic-runs-from-the-command-line)):

| Run status | Meaning |
|---|---|
| `PLANNED` | Run and batch plan persisted, not started. |
| `RUNNING` | Batches executing (sequentially). |
| `COMPLETED` | Every batch finished and the yield requirement was met. A completed run with **zero findings is legitimate** when no minimum was required — a clean domain is a real result — but it always carries a mandatory explanation and an explicit yield-zero event. Silence is not. |
| `COMPLETED_WITH_ERRORS` | Some batches succeeded, some exhausted their retries. Rendered as a warning, never as a whole-run failure. |
| `ZERO_YIELD` | **A loud product outcome, not a system error.** The run executed, candidates were produced, but fewer findings survived the evidence filter than the `requiredMinFindings` you asked for (including the all-filtered case). The run detail tells you the reason and the per-batch filter counts. From the CLI this is a distinct exit code (3) so scripts can never mistake "the command ran" for "the product worked." |
| `FAILED` | The AI produced zero parseable candidates across all batches, or every batch failed. |
| `CANCELLED` | Operator cancel. Neutral. |

### Re-runs and supersession

When a new run over the same scope completes, prior AI findings from earlier runs that are *still pending review* are automatically marked **superseded** — each with its own audit event naming the superseding run. Findings a human already accepted or rejected are **never touched** by supersession; human decisions outrank re-runs. Failed, cancelled, and zero-yield runs supersede nothing. The practical effect: re-running a diagnostic refreshes the pending queue instead of piling duplicates into it, and a third identical run leaves the pending set stable.

### The generative path: AI drafts to accepted capabilities

The same grounding discipline governs AI *proposals* of new model content (currently: Capabilities).

- In the **Capability Designer**, the [Draft from Universe dialog](#draft-from-universe-ai-assist) proposes capability drafts grounded in the ingested document universe, showing each draft's proposed fields and the evidence behind it. When nothing comes back, the dialog shows the explanation (empty universe, all candidates filtered) rather than a blank panel. In the current UI this dialog is a read-only preview — there is no accept button in it yet.
- The accept/reject acts exist as API verbs: `POST /api/v1/ai/generative/commit` (`{assertionId!, overrides?, idempotencyKey!}`) materializes a draft as a Capability in `DRAFT` status, marked AI-generated. Grounding is **re-verified at the moment of commit** — if the cited evidence has since vanished, the commit is refused (`DRAFT_GROUNDING_LOST`) rather than silently materialized. Any human edits applied at acceptance are themselves recorded as part of the audit event. `POST /api/v1/ai/generative/reject` requires a rationale, creates no entity, and returns 200. Per-item only, same as findings.

### Running without an AI key

The platform boots and runs without an `ANTHROPIC_API_KEY`. What changes:

- Ingestion's deterministic converters (e.g. manual-process CSVs) work regardless; LLM-assisted extraction needs the key or the explicit mock provider (`--substrate.llm.provider=mock`), which exercises the full pipeline mechanics deterministically.
- Diagnostic runs, chat, and generative drafts need a real key for real analysis. With the mock provider they run end-to-end with scripted output (useful for rehearsal and verification); with neither key nor mock they fail loudly at call time with a structured `LLM_UNAVAILABLE` error, never silently (see [Troubleshooting](#troubleshooting--faq)).
- The demo workspace's findings and dashboards do not depend on the key at all.

### The operator runbook, in one pointer

The proven end-to-end sequence — ingest the Glacier sample corpus, run a diagnostic against it, read the event lines, curate the findings, and sweep the evidence chains — is the worked example running through [Operator workflows (CLI)](#operator-workflows-cli). The complete acceptance record with full run JSON is preserved at [`../acceptance/diagnostic-glacier-2026-06-11.json`](../acceptance/diagnostic-glacier-2026-06-11.json).

In the demo workspace as shipped, the seeded findings are operator-authored illustrations — no AI findings exist until you ingest a corpus and run a diagnostic (verified live: the demo domain's verify sweep reports zero AI findings, honestly, with explicit zeros).

### Current limits (diagnostics)

> **Not yet:**
>
> - **No curation UI** — accept/reject and the generative commit are API verbs only; the AI-drafts dialog is preview-only.
> - **Evidence is document-level**, not passage-level: the chain proves which file supports a finding, not which paragraph.
> - Grounding is existence-only by design (see above); semantic verification is the human's act at acceptance.
> - Demo dashboards mix live engine data (agents, signals) with illustrative fixture findings; the banner discloses this.

## Operator workflows (CLI)

This section is for the Meridian engagement team — the people with a terminal open on the machine that runs Substrate. It covers the four hands-on procedures that are driven from the command line rather than the browser: ingesting a client corpus, running a diagnostic, standing up a demo environment, and verifying the decision log. Client-side users never need any of this; everything here happens before or beneath what they see in the UI.

All commands run from the `substrate/` directory of the deployment checkout. The examples use the local development shape (no Docker, file-based database); the same commands work against a shared Postgres stack by adding `--spring.profiles.active=postgres` and the standard `SUBSTRATE_DB_*` environment variables. REST examples use `http://localhost:8080`, the default API port — substitute your deployment's port if it differs.

### Ingesting a client corpus: convert → stage → commit

Ingestion is how real client data enters Substrate. It is a deliberate three-step pipeline with a human review gate in the middle:

1. **convert** — read raw source files (CSV, JSON, Markdown/text) and extract canonical entity JSON from them.
2. **stage** — copy the converted output into a staging directory for a human to review.
3. **commit** — write the reviewed, staged entities into the engagement database as systems, processes, canonical entities, organization members, and their relationships, with full provenance.

Nothing reaches the database until you run `commit`. The staging step exists so an operator can open the JSON files, sanity-check what the extractor produced, and delete or correct anything wrong before it becomes engagement data.

The ingestion tool is a standalone command-line application (it starts no web server). It shares the same database migrations as the main application, and in the local dev shape it writes to the file-based database at `substrate/data/engagement-dev.mv.db`. An API booted with `--spring.profiles.active=dev-shared` reads the same file, so entities you commit are immediately visible to it. (The plain `dev` profile uses an in-memory database and will *not* see CLI commits.)

#### Worked example: the Glacier sample corpus

A fictional sample corpus — "Glacier Peak Credit Union" — is checked in at `ingestion/samples/`: `glacier-systems.csv`, `glacier-manual-processes.csv`, `glacier-entities.csv`, and `glacier-workflow-notes.md`. It is the standard smoke test for the pipeline and the worked example throughout this section.

```powershell
cd substrate

# 1. Convert + stage. With no API key, use the deterministic mock provider.
.\gradlew.bat :ingestion:bootRun --args="convert-and-stage --input samples --substrate.llm.provider=mock"

# 2. Review the staged JSON in ingestion/staging/, then commit.
.\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"

# 3. Idempotency check: re-run commit. Every staged file is recognized by its
#    content hash and skipped — 0 created, 0 updated, exit code 0.
.\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"
```

Each stage prints a single machine-readable summary line prefixed `INGEST_EVENT` (greppable from wrappers and CI), in addition to the normal logs. Expected shapes for the Glacier corpus:

```
INGEST_EVENT {"event":"convert_complete","inputDir":"samples","outputDir":"output","filesProcessed":4,"filesAlreadyIngested":0,"filesUnsupported":0,"entitiesExtracted":37,"errors":0,"ingestRunId":"<uuid>"}
INGEST_EVENT {"event":"stage_complete","outputDir":"output","stagingDir":"staging","filesStaged":4}
INGEST_EVENT {"event":"commit_complete","stagingDir":"staging","filesCommitted":4,"filesAlreadyCommitted":0,"filesUnrecognized":0,"entitiesCommitted":42,"entitiesUpdated":0,"errors":0,"entityCounts":{"systems":6,"processes":7,"canonical-entities":5,"process-system-links":9,"process-roles":10,"organization-members":5},"ingestRunId":"<uuid>"}
```

(Field order within the JSON is not guaranteed; match on field names.)

The re-run of `commit` reports `filesCommitted: 0, filesAlreadyCommitted: 4` and identical row counts in the database — re-committing the same corpus is always safe.

#### Commands and flags

| Command | What it does |
|---|---|
| `convert` | Convert source files to entity JSON in the output directory |
| `stage` | Copy converted JSON into the staging directory for review |
| `commit` | Write staged entities into the database (create or update) |
| `convert-and-stage` | Convert and stage in one step |

| Flag | Meaning |
|---|---|
| `--input <dir>` | Source directory for convert; staging directory for commit |
| `--output <dir>` | Where converted entity JSON is written (default `output/` under the ingestion module) |
| `--staging <dir>` | Staging directory (default `staging/` under the ingestion module) |
| `--domain <name>` | Target domain for commit. Created if absent; defaults to a domain named `Ingested` |

Relative paths resolve under `substrate/ingestion/`. Dotted flags such as `--substrate.llm.provider=mock` are configuration overrides, not CLI flags — they pass through to the application.

#### Mock vs. real LLM provider

Structured CSVs (systems, manual processes, entities) convert **deterministically** — no LLM is involved for them, with or without a key. Free-text sources (the workflow-notes Markdown) go through an LLM extraction step:

- **Mock provider** (`--substrate.llm.provider=mock`): a deterministic stand-in that echoes rows through the pipeline. It exercises the full mechanics — file registration, hashing, staging, commit, audit — without a network call. Use it for smoke tests and when no API key is configured.
- **Real provider** (the default): set the key first — `$env:ANTHROPIC_API_KEY = '...'` — and drop the provider override: `convert-and-stage --input samples`. Extraction quality on free text is real but output can vary run to run.

#### Ingestion exit codes

The process exit code is the contract for scripts and CI:

| Exit code | Meaning |
|---|---|
| `0` | Success — including a fully-deduplicated re-run (everything already ingested is a legitimate no-op) |
| `1` | Errors during processing (per-file errors are printed to stderr) |
| `2` | Usage error — unknown command, missing input directory |
| `3` | **Zero-yield hard fail** — the run consumed nothing: nothing converted, staged, or committed, and nothing legitimately recognized as already done. Typically an empty or wrong directory. Accompanied by an `INGEST_EVENT {"event":"zero_yield", ..., "reason":"..."}` line |

The zero-yield code exists because "the command exited 0" must never be mistaken for "the corpus was ingested." If you point the tool at the wrong directory, it fails loudly instead of succeeding emptily.

#### Where the audit trail lands

Every convert and commit writes an audit row to the `ingest_runs` table; the run id is echoed as `ingestRunId` in the `INGEST_EVENT` line. Source and staged files are registered in `document_registry` with their SHA-256 content hash and stamped with the converter run that processed them. This is not bookkeeping for its own sake: it is the foundation of finding provenance. When a diagnostic later commits an AI finding against ingested data, `GET /api/v1/findings/{id}/provenance` walks from the finding through its evidence back to these registry rows and ingest runs — down to the original source filename and content hash (see [Reviewing a finding](#reviewing-a-finding-the-provenance-walk)).

To reset the local dev database for a clean slate, stop both processes and delete `substrate/data/` (and `ingestion/output/` plus `ingestion/staging/` to also clear pipeline intermediates).

### Diagnostic runs from the command line

A diagnostic run asks the AI to examine a domain's ingested landscape — systems, processes, canonical entities — and propose findings (redundancies, gaps, data-quality issues). Every candidate passes the evidence filter described under [Diagnostics and findings](#diagnostics-and-findings); only survivors are committed, as findings awaiting human review. Runs execute in batches and are resumable; an interrupted run never produces duplicate findings.

#### The CLI

The runner boots the application with no web server, executes exactly one synchronous run against a named domain, prints structured event lines, and exits:

```powershell
cd substrate
.\gradlew.bat :app:bootRun --args="--spring.profiles.active=dev-shared --substrate.diagnostic.run=true --substrate.diagnostic.domain-name=Glacier --substrate.diagnostic.required-min-findings=1 --substrate.llm.provider=mock --substrate.llm.mock.diagnostic-script=grounded-content"
```

- `--spring.profiles.active=dev-shared` points the runner at the same file-based database the ingestion CLI commits to, so it can see the Glacier domain.
- `--substrate.diagnostic.domain-name` selects the domain **by name**. If the name is unknown, the runner tells you to list known domains with `GET /api/v1/domains` and exits 4.
- `--substrate.diagnostic.required-min-findings=1` declares your yield requirement: the run must commit at least this many findings or it is a hard failure (exit 3). Set it whenever you expect output; leaving it 0 means "an empty result is acceptable," and the runner will then exit 0 on zero findings — but always with an explicit yield line, never silently.

**Provider note.** The bare mock provider answers diagnostics with prose, which parses to zero candidates — the run fails with exit 2 (`NO_CANDIDATES`). For a meaningful deterministic smoke test add a mock script: `--substrate.llm.mock.diagnostic-script=grounded-content` (commits real evidence-grounded findings, as in the example above), `structural` (commits one structural finding), or `ungrounded-content` (exercises the all-filtered rejection lane). For a real run, set `ANTHROPIC_API_KEY` and drop both `substrate.llm.*` overrides; real yield varies run to run, which is exactly why `required-min-findings` exists.

#### Diagnostic exit codes

| Exit code | Meaning |
|---|---|
| `0` | Run completed and the yield requirement was met. Includes the legitimate "clean domain" case — zero findings with `required-min-findings=0` — which always prints an explicit `DIAG_EVENT kind=yield` line explaining the zero. A `COMPLETED_WITH_ERRORS` run (some batches exhausted their retries but the yield requirement was still met) also exits 0 — check the run detail for the per-batch ledger |
| `2` | The run failed or did not run to completion: no parseable candidates at all (`NO_CANDIDATES`), all batches failed, the run was cancelled mid-flight, or another run was already active for the domain |
| `3` | **Zero-yield hard fail**: candidates were produced but fewer were committed than `required-min-findings` — either everything was filtered out or the count fell below the requirement |
| `4` | Misconfiguration: the runner was enabled without a domain name, or the named domain does not exist |

#### Reading DIAG_EVENT lines

The engine emits one greppable `DIAG_EVENT kind=…` line per significant step. A healthy run looks like:

```
DIAG_EVENT kind=run_started runId=… domain=… scopes=systems,processes,canonical-entities batches=4 executor=in-process
DIAG_EVENT kind=batch_completed runId=… batch=1/4 returned=3 filtered=2 committed=1
...
DIAG_EVENT kind=run_finalized runId=… status=COMPLETED committed=4 filtered=8 superseded=0 tokensIn=320 tokensOut=160 costEstimate=0.0168
DIAG_EVENT kind=cli_exit runId=… status=COMPLETED committed=4 exit=0
```

| `kind` | Meaning |
|---|---|
| `run_started` | Run accepted; shows scope list, batch count, executor |
| `batch_completed` | One batch done: candidates `returned`, `filtered` (rejected by the filter), `committed` |
| `batch_failed` | A batch exhausted its retries; the run continues with the rest |
| `batch_dispatched` / `batch_fallback` / `batch_skipped_budget` | Orchestrator-dispatched execution lane only: a batch was sent to an external agent, fell back to in-process execution, or was skipped on budget refusal (neutral, not an error) |
| `run_finalized` | Terminal status plus totals: committed, filtered, superseded prior findings, token and cost totals |
| `run_cancelled` | Operator cancel completed (neutral — cancel is never an error) |
| `yield` | Explicit yield statement — including the mandatory explanation when the legitimate result is zero |
| `zero_yield` | The hard-fail case: below the declared requirement (exit 3) |
| `all_content_filtered` / `no_content_findings` | Warnings: every evidence-citing candidate was filtered, or the run yielded only structural observations. Worth a human look; not fatal by themselves |
| `misconfiguration` / `run_conflict` / `cli_exit` | CLI-level lines: bad configuration (exit 4), another run already active (exit 2), and the final exit summary |
| `batch_*_refused_stale_claim` | Internal consistency guards — a stale executor tried to report on a batch that had been reclaimed. Safe to ignore unless they recur |

The run status reported in `run_finalized` and by `GET /api/v1/diagnostics/runs/{id}` uses the vocabulary defined under [Diagnostic runs and their outcomes](#diagnostic-runs-and-their-outcomes) (`PLANNED`, `RUNNING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, `ZERO_YIELD`, `FAILED`, `CANCELLED`).

#### REST equivalents

Everything the CLI does is also available over HTTP, plus resume/cancel and the integrity sweep:

| Endpoint | What it does |
|---|---|
| `POST /api/v1/diagnostics/runs` | Start a run. Body: `{"domainId": "…", "requiredMinFindings": 1, "idempotencyKey": "…"}` (scopes, batch size, executor optional; `"dryRun": true` returns the batch plan without running). Returns 202 with the run id. A second start while one is active returns 409 `DIAGNOSTIC_RUN_ACTIVE` — one run per domain at a time |
| `GET /api/v1/diagnostics/runs?domainId=…` | Run history, newest first |
| `GET /api/v1/diagnostics/runs/{id}` | Full detail: status, per-batch ledger, filter histogram (why candidates were rejected), token and cost totals |
| `POST /api/v1/diagnostics/runs/{id}/resume` | Re-execute pending, failed, or stale batches after a crash or partial failure. No duplicate findings are possible — a batch that did not finish committed nothing. Resuming a finished run is a harmless no-op |
| `POST /api/v1/diagnostics/runs/{id}/cancel` | Stop a run: queued batches are cancelled, the in-flight batch finishes its current step. Returns 200; cancel is never error-colored |
| `GET /api/v1/diagnostics/verify?domainId=…` | Integrity sweep of every AI finding in the domain: total, how many have a complete evidence chain, any broken chains with the missing link named, and any findings grounded only in demo fixture data |

A clean sweep on a domain with 12 AI findings looks like: `{"total":12,"chainComplete":12,"brokenChains":[],"fixtureGrounded":[],"groundingModes":{"EXISTENCE_ONLY":12}}`. All responses use the standard `{data, meta, errors}` envelope. Remember what `EXISTENCE_ONLY` means — evidence verified to exist, semantic support verified by the human at acceptance (see [Reviewing a finding](#reviewing-a-finding-the-provenance-walk)).

### The demo pack: seeding a demonstration environment

The demo pack populates a fresh database with a complete fictional engagement — "Meridian Bancorp" operational risk, plus the "Beacon" holding-company portfolio — so every surface has something credible to show. It is strictly demo-lane: seeding is off by default, only ever writes into domains marked as demo, and none of its levers belong in a customer engagement build. Production data enters only through ingestion and the engines. (The user-visible inventory of what gets seeded is under [What the demo seed creates](#what-the-demo-seed-creates).)

Boot with seeding enabled:

```powershell
cd substrate
.\gradlew.bat :app:bootRun --args="--spring.profiles.active=dev --substrate.demo.seed=true --substrate.demo.contracts.activate=true"
```

#### What gets seeded

Seeders run in dependency order; the boot log prints one `[seed] <Name> completed in N ms` line per seeder plus row counts, so you can confirm what landed without writing SQL.

| Seeder | Creates |
|---|---|
| Domain | The primary demo domain ("Operational Risk" — Meridian Bancorp), marked as demo |
| Systems | The 12-system landscape plus system-to-system integrations |
| Processes | 26 manual processes, process–system links, role assignments, a sample process step |
| Organization | Organization members and role-overlap relationships |
| Canonical entities | Shared data entities and their overlap relationships |
| Capabilities | A sample capability tree (customer-onboarding root with a KYC child) and links |
| Diagnostic | Fixture findings, plus design decisions appended through the real hash-chained decision log |
| Evidence | Synthetic document-registry and evidence rows backing the seeded entities (marked `extractor='seed'` so they are always distinguishable from real ingested provenance) |
| Readers | One sample external-source registration ("Ingestion ETL") |
| Phases | Lifecycle phase states for the domain; Design is set in-progress at seed time |
| Cascades | Three demonstration decision cascades, driven through the real cascade lifecycle verbs (draft → issue → cascade → … → decline) — the seed doubles as a state-machine test |
| Contracts | The two fixture coordination contracts, created `PROPOSED` through the real contract service |
| Agents | ~70 fixture agents replayed through the real lifecycle (deploy → review → promote / pause / intervention), plus the 22 Beacon subsidiary domains that populate the portfolio view |
| Branding | One active branding configuration per demo domain |

#### Re-boot idempotency

Re-booting with `substrate.demo.seed=true` against an existing database is safe:

- **Fresh database** (no domains at all): full bulk seed.
- **Existing database**: only domains marked demo are revisited, with per-entity "create if missing" logic — existing data is not duplicated, and new fixtures added since the last boot are picked up without a wipe. The Beacon subsidiary domains are excluded from this iteration (their fleet re-converges separately), so fixtures are never multiplied across 22 satellites.
- **Database containing only non-demo (production) domains**: the seed run is a complete no-op. The demo pack never touches production domains.

#### The demo levers

| Property | Default | What it demonstrates |
|---|---|---|
| `substrate.demo.seed` | `false` | Master switch. All other levers are inert without it |
| `substrate.demo.contracts.activate` | `false` | Records fixture acceptances for every required party of the "co-tribal-knowledge-depopulation" contract, flipping it `ACTIVE`. Without it both fixture contracts sit `PROPOSED` forever; with it, contract monitoring, breach detection, and the compliance demo sequence have a live contract to work against |
| `substrate.demo.contracts.guards` | `false` | Seeds and activates a deliberately **synthetic** guard-bearing contract (`demo-gateway-guards`) with deny guards on agent promotion and metric reporting, bound to the demo agent principal. The two realistic fixture contracts intentionally compile to zero enforcement guards, so gateway-blocking demos need this synthetic one |
| `substrate.demo.fixed-clock` | unset | Pins the engine clock to an ISO-8601 instant, e.g. `--substrate.demo.fixed-clock=2026-09-10T15:12:23Z`. Two uses: making the contract breach-detection sequence reproducible byte-for-byte (pin "now" past a reporting period's grace window), and demonstrating the portfolio lifecycle-stall signal (pin ~91 days past seed time and the dashboard's stall count flips 0→1, since the seeded domain has then dwelt in Design past the 90-day threshold). The boot log prints a loud `DEMO LEVER ACTIVE` warning when set |
| `substrate.transformation.fixture-enabled` | `false` | Serves the fictional transformation-plan mock-up to the (otherwise disabled) Roadmap surface. Demo only |

None of these belong in a customer build; the clock lever in particular makes every time-based computation in the product wrong on purpose.

#### Caveat: failed seeds on a persistent database

The seed run executes in one transaction, with one exception: decision-log appends commit **immediately and independently** (that is what makes the chain tamper-evident, and it holds during seeding too). If a seed run fails *after* the diagnostic seeder has written its design decisions — rare, but possible on a persistent Postgres database — the domain and its entities roll back while the already-committed decision rows survive, referencing a domain that never came to exist. The next boot sees no domains, seeds a fresh demo domain, and the orphan chain rows linger. They are self-consistent (the startup chain verifier reads them as intact) and purely cosmetic, but if they bother you:

```sql
DELETE FROM design_decisions WHERE domain_id NOT IN (SELECT id FROM domains);
```

In-memory or freshly-deleted dev databases never exhibit this; it only accumulates where the database outlives the failed boot.

### Reading and verifying the decision log

The decision log is the engagement's tamper-evident record. Each entry stores a SHA-256 hash of its own canonical content plus the hash of the previous entry in its domain's chain; the first entry's previous-hash is the literal string `GENESIS`. Any after-the-fact modification of an entry breaks every subsequent link, and the break is detectable on demand and at every application boot (boot-time re-verification of every domain chain is on by default).

#### Endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/v1/decision-log?domainId=…` | The full chain for a domain, in chronological order |
| `GET /api/v1/decision-log/verify?domainId=…` | A chain-integrity verification report |

Each entry in the list carries: `id`, `decisionType`, `status` (`PROPOSED` / `APPROVED` / `REJECTED`), `title`, `description`, `rationale`, `affectedSystemIds`, `affectedProcessIds`, `previousHash`, and `currentHash`. Reading down the list, every `previousHash` should equal the prior entry's `currentHash`, starting from `GENESIS`.

An intact verification report (captured live against a seeded instance):

```json
{
  "data": {
    "valid": true,
    "totalDecisions": 6,
    "verifiedLinks": 6,
    "firstBrokenAt": null,
    "message": "All 6 decisions verified; chain is intact."
  }
}
```

A broken chain reports `valid: false`, `verifiedLinks` equal to the number of links that verified **before** the first break, `firstBrokenAt` naming the first decision whose link failed, and a message describing the failure. A break means an entry's stored content no longer matches its hash, or its previous-hash no longer matches its predecessor — i.e., something was modified outside the append path. Treat it as an incident: the log itself cannot tell you what changed, only where the chain stops being trustworthy.

#### What is anchored in the chain

Three classes of acts write chain entries:

| Anchor | Written when | Appears as |
|---|---|---|
| Design decisions | An operator records a decision (`POST /api/v1/design/decisions`), and the demo seed's fixture decisions | The decision's own type: `CONSOLIDATE`, `RETIRE`, `MODERNIZE`, the manual-process verbs, etc. |
| Contract accountability | A coordination contract is **activated**, **declined** (either decline path), or a breach is **waived** | `CONTRACT_ACCOUNTABILITY` |
| Agent governance | An agent is **deployed** (the anchor carries a digest of its mandate criteria), **promoted** to live, or **retired/killed** (carries the retirement reason) | `AGENT_DEPLOYED` / `AGENT_PROMOTED` / `AGENT_RETIRED` |

The contract and agent anchors are written immediately after the underlying act commits; the chain carries stable identifiers (contract keys, row UUIDs, period keys, actor ids), never display names or free prose — rationale text lives on the underlying event rows, referenced by id.

Deliberately **not** anchored: agent pause/oversight changes (recorded in the agents' own append-only event log — too frequent for the chain), and AI finding accept/reject decisions (recorded in the curation audit table; an optional per-run digest anchor exists but is off by default).

### Where acceptance evidence lives

Every workflow above was proven with runnable commands, and the evidence is checked in under `substrate/docs/acceptance/`. These files are the best worked examples available — real captured outputs, not idealized ones — and a good way to check "what should this look like when it works":

| Path | What it shows |
|---|---|
| `acceptance/diagnostic-glacier-2026-06-11.json` | The complete Glacier worked example end to end: ingest event lines, two diagnostic runs with full run-detail JSON (batch ledgers, filter histograms, supersession across runs), curation, a clean verify sweep — and the negative proof that an empty domain with a yield requirement exits 3 |
| `acceptance/d5/` | Portfolio statistics acceptance: the baseline portfolio JSON, the fixed-clock boot that flips the stall count 0→1, a dashboard screenshot, and the write-up of the exact commands |
| `acceptance/phase3/` and `acceptance/d3/` | Contract gateway and agent-governance acceptance transcripts, including the shadow-mode vs. enforce-mode evidence pairs |
| `acceptance/phase5/` | Authentication acceptance: runnable scripts and transcripts for both the anonymous dev mode and the full OIDC mode |
| `acceptance/final/` | Screenshots of the shipped surfaces (e.g. [the portfolio dashboard](../acceptance/final/05-portfolio-dashboard.png), [a contract detail](../acceptance/final/07-contract-detail.png)) plus the visual-verification notes |

## Administration

This part is for the engagement operator — the person who configures, deploys, and watches the platform. Client-side users can skip to [API & MCP reference](#api--mcp-reference) or [Troubleshooting & FAQ](#troubleshooting--faq).

### Authentication modes

Substrate runs in one of two authentication modes, set per deployment by `substrate.auth.mode`. The mode changes who the platform believes you are; everything downstream (the persona shown in the header bar, the actor recorded on every action, the admin gates) follows from it.

| | `anonymous` (dev / demo default) | `oidc` (real deployments) |
|---|---|---|
| Who can call the API | Anyone who can reach the port — there is no login | Only callers presenting a valid token from the configured identity provider; everything else gets **401** (the unauthenticated liveness probes — `/api/v1/health`, `/actuator/health`, `/actuator/info` — stay public by design) |
| Where your identity comes from | Self-asserted request headers (the persona switcher writes them) | The verified token issued by the customer's identity provider |
| Persona switcher in the UI | A dropdown — pick any persona, the app believes you | Replaced by a read-only identity chip showing your name and mapped persona, with a sign-out button |
| Admin actions | Granted by self-asserting the admin persona header | Require a real admin role in your token |
| Audit trail actor | The asserted persona label/id (spoofable by design — disclosed) | Your stable user id from the identity provider |

#### Anonymous mode — what to know

- This is the mode the dev and demo profiles run in. There is no login screen and no security filter on the request path at all.
- The **persona switcher** in the header offers six personas — **Architect** (the default, wire level `L3_ANALYST`) and **Executive** are functional; Domain Lead, Subject Matter Expert, Auditor, and Data Owner are marked *Preview* (see [First-run orientation](#first-run-orientation)). It is *self-asserted*: switching personas simply changes the headers the browser sends. It exists so a demo or workshop can walk through different viewpoints — it is not access control, and the platform discloses as much.
- Admin-gated actions (currently branding administration) require asserting the admin persona level on the request — an operator-grade act done with an API client, not the switcher.
- Agent and AI-system callers on the MCP surface identify themselves with a plain `X-MCP-Principal` header (trusted because the deployment is local; equally self-asserted).

Anonymous mode is acceptable only for local development and fixture-data demos. It must never face a network with real users or real data.

#### OIDC mode — what changes

OIDC mode turns the application into a standard OpenID Connect resource server. **The customer brings the identity provider** — any conformant issuer works (Okta, Microsoft Entra ID, Auth0, a customer-internal IdP). Keycloak is used as the throwaway *test* IdP in the Docker Compose stack and in CI; it is never a production dependency.

For the signed-in user:

- The SPA performs a standard browser login (authorization code + PKCE) against the configured issuer. You land back where you started, including deep links.
- Your **persona is derived from your token's roles**, through a per-engagement role-map (first matching role wins, with a configured default). The switcher becomes a read-only chip — you cannot impersonate another persona.
- **Admin actions need an admin role** in your token (default role name `substrate-admin`, configurable per engagement). Asserting the old admin header does nothing — header trust is dead in this mode and is logged as a warning if attempted.
- Every action you take is recorded against your stable user id from the token, so originator-only and owner-only rules genuinely bind to *you*.

For machine callers (agents) on `/mcp`: an unauthenticated call gets **401**. Agents authenticate with client-credential tokens; the principal identity is taken from a configured token claim (default `sub`, fallback `azp`) and matched against the principal id registered on the agent registration or contract party.

Role mapping is configuration, never code. The keys an operator sets per engagement (full detail in `substrate/docs/auth.md`):

| Key (under `substrate.auth.oidc.`) | Purpose |
|---|---|
| `issuer-uri` | The customer IdP's issuer URL (discovery endpoint) |
| `audience` | Optional audience pin on accepted tokens |
| `claims.roles` | Dot-path to the roles claim in the customer's tokens (Keycloak: `realm_access.roles`) |
| `claims.display-name` | Claim used for the display name in the identity chip |
| `persona.role-map` / `persona.default-level` | Ordered role → persona projection; fallback persona |
| `admin-roles` | Role name(s) that unlock admin actions (default `substrate-admin`) |
| `mcp.principal-claim` | Token claim used as the MCP principal id (default `sub`) |

One transport quirk worth knowing: the chat stream uses browser Server-Sent Events, which cannot send an auth header. On the stream path only, the app accepts the standard `access_token` query parameter instead. Use short-lived tokens; this is the RFC-specified bearer fallback.

### Observability

Everything is built in, and **everything that exports data is off by default**. With default configuration a Substrate process writes structured JSON logs to stdout and keeps metrics in memory; nothing leaves the process until an engagement explicitly points it at the *customer's* collector.

#### Logs

- Structured JSON to stdout, one object per line. Every line carries a `requestId` — the same UUID returned to API clients in the `X-Request-Id` response header and in every response envelope's `meta.requestId`. Given any API response, you can find its exact log lines.
- Where the stdout stream lands depends on the deploy shape: the terminal that launched `gradlew :app:bootRun` (local dev), `docker compose logs substrate-app` (Compose), or the engagement's CloudWatch log group (AWS — `aws logs tail <log-group> --since 10m`).
- A `demo` Spring profile switches the console to human-readable plain text for live walkthroughs.
- When tracing is enabled, log lines additionally carry `traceId`/`spanId`, so logs and traces cross-reference each other.

#### Metrics

All engine metrics are recorded under the `substrate_` prefix and tagged with the engagement id. The inventory that matters to an operator:

| Metric | What it counts |
|---|---|
| `substrate_contract_gateway_evaluations` | Every enforcement-gateway evaluation, tagged by decision (`allow`, `allow_with_flag`, `deny`, `would_deny`, `allow_unevaluated`) and enforcement mode |
| `substrate_agent_gateway_denials` | Agent-call denials at the gateway, tagged by reason — **the number to watch while running shadow mode** |
| `substrate_contract_monitor_runs` | Each automated contract-monitor sweep, by outcome (`compliant`, `breach_detected`, `indeterminate`, `not_due`, `error`, …) |
| `substrate_contract_breaches_opened` | Newly opened breaches (re-confirmations of an already-open breach do not count) |
| `substrate_cascade_actions` | Cascade actions by type (`issue`, `cascade`, `absorb`, `decline`, `recascade`, resolve variants). **Decline is a legitimate action — never alert on it as an error** |
| `substrate_agent_verbs` | Agent lifecycle verbs (deployed, paused, resumed, killed, promoted, …) |
| `substrate_diagnostic_runs` | Diagnostic run completions by status (`completed`, `completed_with_errors`, `zero_yield`, `failed`, `cancelled`) |
| `substrate_diagnostic_findings_committed` / `substrate_diagnostic_candidates_filtered` | Per-run finding totals — filtered vastly exceeding committed signals a grounding gap |
| `substrate_llm_calls` / `substrate_llm_tokens` / `substrate_llm_cost_estimated` | AI usage and estimated spend, tagged by model |

Standard JVM, HTTP-latency, and connection-pool meters ship alongside, all carrying the same engagement tag.

#### Pointing at a customer stack

The customer runs (or already has) an OpenTelemetry-compatible collector inside their own environment. The entire integration is two environment variables:

```
SUBSTRATE_OBSERVABILITY_ENABLED=true
SUBSTRATE_OBSERVABILITY_OTLP_ENDPOINT=http://otel-collector.internal:4318
```

Metrics push every 60 s (configurable) and traces export with 10 % sampling by default; tracing can be pinned on/off independently of metrics. Collector auth headers, when required, are supplied as environment variables — secrets never land in configuration files. There is no Meridian-hosted telemetry endpoint in any configuration: one engagement, one deploy, one observability tail, all inside the customer's environment.

#### Actuator endpoints per mode

| Mode | `/actuator/health`, `/actuator/info` | `/actuator/metrics` | Everything else |
|---|---|---|---|
| `anonymous` | Public (health checks depend on this) | **404** — not exposed | 404 |
| `anonymous` + `substrate.observability.actuator.expose=true` | Public | Public — local debugging only, never a customer build | 404 |
| `oidc` | Public | 401 without a token, 403 without an admin role, 200 with one | 404 |

Production metrics are *pushed* (OTLP); the actuator metrics endpoint is an admin convenience, not the export path.

### Deployment in one page

Same application image, three shapes:

| Shape | Where | Identity provider | Data allowed |
|---|---|---|---|
| **Local dev** | `gradlew :app:bootRun` (zero dependencies: in-memory H2, anonymous auth) or `docker compose up` (Postgres + Keycloak) | None / Keycloak container | Fixtures only |
| **Meridian-hosted testing** | Meridian's own AWS account, fixture engagements, only to validate deploy tooling | Keycloak (throwaway) | Fixtures only — never customer data |
| **Customer production** | The customer's AWS account — their VPC, their IAM, their data residency | The customer's IdP | The engagement's data; it never leaves their account |

Local setup (both shapes, plus the ingestion CLI walkthrough) is in `substrate/docs/dev-setup.md`. The full per-engagement AWS runbook — customer-handoff prerequisites, Terraform state bootstrap, digest-pinned image flow, the two-phase AI-assist key enablement, day-2 operations (password rotation, image rollback with its forward-only-migration caveat), and the gated teardown sequence — is `substrate/docs/deploy.md`. Do not work from memory on a customer deploy; follow the runbook's smoke checklist ("apply exited 0" is not "product worked").

### Configuration reference

The `substrate.*` keys an operator actually touches. All are environment-overridable; structured keys use standard Spring relaxed binding.

| Key | Default | What it does |
|---|---|---|
| `substrate.auth.mode` | `anonymous` | The auth mode ladder described above. Set `oidc` for any real deployment |
| `substrate.engagement.id` | `dev-engagement` | Engagement identifier — stamps every metric and trace |
| `substrate.demo.seed` | `false` | Seeds the fictional demo dataset at startup. Demo runs only — **never in a customer build** |
| `substrate.demo.contracts.activate` | `false` | Demo lever: records fixture acceptances so a seeded contract starts `ACTIVE` (monitor demos). Only effective with `demo.seed=true` |
| `substrate.demo.contracts.guards` | `false` | Demo lever: seeds a synthetic guard-bearing contract to demonstrate enforcement denials. Never in a customer build |
| `substrate.demo.fixed-clock` | unset | Pins the engine clock to a fixed instant for deterministic demos. Never in a customer build |
| `substrate.transformation.fixture-enabled` | `false` | Serves the fictional transformation-plan mock-up behind the Roadmap view. Demo runs only |
| `substrate.contracts.enforcement.mode` | `shadow` | The [enforcement ladder](#the-enforcement-ladder--off-shadow-enforce): `off` (gateway skipped), `shadow` (full evaluation, would-be denials logged, nothing blocked), `enforce` (denials block). Ship `shadow`; flip to `enforce` after a clean observation window |
| `substrate.contracts.monitor.enabled` / `monitor.sweep-interval` | `true` / 60 s | The automated contract-monitor sweep |
| `substrate.contracts.mcp.enabled` | `true` | Mounts the MCP server at `/mcp` |
| `substrate.cascade.default-response-sla-days` | `7` | Days before a cascade level with no response is flagged overdue (per-cascade override; `0` disables). Notification-only — never a forced state change |
| `substrate.cascade.overdue-renotify-days` / `overdue-renotify-limit` | `3` / `5` | Re-notification cadence and cap for overdue cascade levels |
| `substrate.cascade.sweep-cron` | hourly | When the overdue sweep runs |
| `substrate.orchestrator.enabled` / `orchestrator.base-url` | `false` / empty | Connects the optional orchestrator sibling product. Off ⇒ AI cascade-draft suggestions return 404; everything else unaffected |
| `substrate.llm.provider` | `anthropic` | `mock` gives deterministic no-network behavior for tests and the ingestion CLI |
| `substrate.llm.anthropic.api-key` | empty (env `ANTHROPIC_API_KEY`) | Blank ⇒ degraded mode: the app boots and runs fine, AI-backed calls return a structured 503 (see Troubleshooting) |
| `substrate.llm.anthropic.model` | `claude-opus-4-8` (env `SUBSTRATE_LLM_MODEL` overrides) | The model id. Not hardcoded in code — the shipped configuration supplies the default; if explicitly blanked, AI calls fail loudly at call time, never at boot |
| `substrate.observability.enabled` / `otlp.endpoint` | `false` / localhost | Telemetry export master switch + collector base URL |
| `substrate.decision-log.verify-on-boot` | `true` | Re-verifies decision-log hash chains at startup |

## API & MCP reference

### The response envelope

Every REST response (except the chat event stream) is wrapped in one envelope: `{data, meta, errors}`. A real response from a running instance:

```json
{
  "data": { "timestamp": "2026-06-12T14:02:08.361Z", "status": "UP" },
  "meta": {
    "timestamp": "2026-06-12T14:02:08.362Z",
    "requestId": "28aab34b-69b7-45e7-8fa2-5a0280d090f7",
    "version": "0.1.0",
    "personaLevel": "L3_ANALYST"
  },
  "errors": []
}
```

- `data` — the payload; `null` on errors, and on *conflict* errors it can instead carry the **current state of the resource** so a client can refresh without a second call.
- `meta.requestId` — matches the `X-Request-Id` response header and the `requestId` field of every server log line for this request. Quote it when reporting a problem.
- `meta.personaLevel` — who the platform believed you were.
- `errors` — empty on success; otherwise structured `{code, message, field}` entries. Stack traces are never exposed.

Conventions that hold across all engines:

- **`domainId` is required** on engine list/read endpoints (cascades, contracts, agents, diagnostics, decision log). Data is partitioned per domain; there is no cross-domain list.
- **Every state-changing request body requires an `idempotencyKey`** (any client-chosen string, ≤128 chars). Replaying the same key with the same action returns the original result; replaying it with a *different* action is rejected as a conflict — a silent dropped command never masquerades as success.
- Optional **optimistic-concurrency fields** (`expectedChainSeq` on cascade commands, `expectedEventSeq` on agent verbs) let a client say "apply this only if nothing moved since I looked". A mismatch returns a 409 *with the current detail in `data`* — refresh and re-decide.
- **Declines, cancels, pause and kill are never errors.** Declining a cascade level or contract, cancelling a diagnostic run, pausing or killing an agent each return 200 — they are legitimate governance outcomes, not failures.

### Endpoint map

Method + path + purpose, grouped by engine. Paths are under `http://<host>/api/v1`. A sampling of the read endpoints below was verified against a live instance.

**Cascade engine** — decisions cascading through portfolio levels:

| Method & path | Purpose |
|---|---|
| `POST /cascade-drafts` | Create a draft cascade (operator-grade; there is no authoring UI yet) |
| `PATCH /cascade-drafts/{id}` · `GET /cascade-drafts/{id}` · `POST /cascade-drafts/{id}/discard` | Edit / read / discard a draft |
| `POST /cascade-drafts/{id}/issue` | Issue the draft as a live cascade |
| `POST /cascade-drafts/{id}/suggest` | AI-assisted draft suggestion — 404 unless the orchestrator integration is enabled |
| `GET /cascades?domainId=…` | List cascades (filters: state, signal, owner, affected domain) |
| `GET /cascades/{id}` · `GET /cascades/{id}/actions` | Detail; append-only action timeline |
| `GET /cascades/attention?domainId=…` | Whose action is demanded right now |
| `GET /cascades/stats?domainId=…` | Dashboard statistics (engine-computed) |
| `GET /cascades/notifications?recipientId=…` · `POST /cascades/notifications/{id}/read` | In-app notification feed; mark read |
| `POST /cascades/{id}/levels/{depth}/cascade` \| `absorb` \| `decline` \| `recascade` | The four level responses |
| `POST /cascades/{id}/resolve` | Originator resolution (looks-good or declined) |

**Coordination contracts engine** — cross-subsidiary commitments with monitoring and enforcement:

| Method & path | Purpose |
|---|---|
| `GET /contracts?domainId=…` | Contract cards (filters: status, type) |
| `GET /contracts/signals?domainId=…` | Aggregate signals: pending invitations, open breaches, monitor coverage |
| `GET /contracts/{idOrKey}` | Full detail: parties, commitments + compliance, enforcement text verbatim, decline triggers, breach summary |
| `GET /contracts/{idOrKey}/events` \| `breaches` \| `decisions` \| `monitor-runs` \| `attestations` | Audit timelines and enforcement-decision history |
| `POST /contracts/{idOrKey}/respond` | A party's response (accept / decline) to a proposed contract |
| `POST /contracts/{idOrKey}/decline` | Invoke an armed decline trigger on an active contract — returns 200, it is a first-class outcome |
| `POST /contracts/{idOrKey}/metric-samples` | Report a metric sample against a monitored commitment |
| `POST /commitments/{commitmentId}/attest` | Record a compliance attestation |
| `POST /breaches/{breachId}/acknowledge` \| `remediate` \| `waive` | Breach workflow; **waive is human-only** in every mode |
| `POST /contracts/{idOrKey}/monitors/{monitorId}/run-now` | Force an immediate monitor evaluation |

**Agent registry** — the inventory and control surface for AI agents operating in the estate:

| Method & path | Purpose |
|---|---|
| `POST /agents` | Deploy (register) an agent — the deploy gate rejects missing provenance/oversight with per-field codes |
| `GET /agents?domainId=…` · `GET /agents/{idOrKey}` | Fleet rows; full detail incl. mandate, gates, oversight chain, `controlEffects` |
| `GET /agents/signals` \| `attention` \| `feed` \| `notifications` (`?domainId=…`) | Fleet health aggregates, attention strip, activity feed |
| `GET /agents/{idOrKey}/events` \| `sightings` \| `gates` \| `metrics` | Per-agent timelines |
| `POST /agents/{idOrKey}/pause` \| `resume` \| `kill` \| `retire` | Control verbs — always 200, with an honest enforcement disclosure (see below) |
| `POST /agents/{idOrKey}/throttle` · `DELETE /agents/{idOrKey}/throttle/{kind}` | Set / clear rate-and-budget throttles |
| `POST /agents/{idOrKey}/promote/request` · `POST /agents/{idOrKey}/promote` | Shadow → live promotion (review-gated) |
| `POST /agents/{idOrKey}/reviews` · `POST /agents/reviews/{id}/decide` | Open / decide a promotion review |
| `POST /agents/{idOrKey}/interventions` · `POST /agents/interventions/{id}/acknowledge` \| `resolve` | Human intervention workflow |
| `POST /agents/{idOrKey}/oversight` · `attest-liveness` · `confirm-control` · `principal` (+ `DELETE`) | Oversight chain, liveness attestation, human control confirmation, MCP principal binding |

`{idOrKey}` accepts the registration UUID or the agent key (e.g. `a-msg-router`). All lifecycle verbs are REST-only and human-only — agents cannot administer themselves or each other over MCP.

**Diagnostic engine** — AI-assisted findings with a human curation gate:

| Method & path | Purpose |
|---|---|
| `POST /diagnostics/runs` | Start a diagnostic run for a domain (one active run per domain) |
| `GET /diagnostics/runs?domainId=…` · `GET /diagnostics/runs/{id}` | Run history; run detail with batch outcomes |
| `POST /diagnostics/runs/{id}/resume` \| `cancel` | Resume an interrupted run; cancel (200, never error-colored) |
| `GET /findings/{id}/provenance` | The full evidence walk for one finding — every hop from finding to source document |
| `GET /diagnostics/verify?domainId=…` | Chain-integrity sweep over every AI-generated finding in the domain |
| `POST /findings/{id}/accept` \| `reject` | Human curation; reject **requires a rationale** |
| `POST /ai/generative/commit` \| `reject` | Materialize or reject an AI-drafted assertion — commit re-checks that the cited evidence still resolves |

**Decision log** — append-only, hash-chained record of design decisions:

| Method & path | Purpose |
|---|---|
| `GET /decision-log?domainId=…` | The domain's decision chain in order |
| `GET /decision-log/verify?domainId=…` | Recompute and verify the hash chain (see [Reading and verifying the decision log](#reading-and-verifying-the-decision-log)) |
| `POST /design/decisions` · `GET /design/decisions` · `/design/decisions/{id}` | Record / browse decisions |
| `POST /design/decisions/{id}/approve` \| `reject` · `POST /design/impact-analysis` | Decision workflow; impact analysis |

**Cross-cutting:**

| Method & path | Purpose |
|---|---|
| `GET /health` · `GET /info` | Liveness and build info (also `/actuator/health` for infrastructure checks) |
| `POST /chat` · `GET /chat/stream` | AI assistant — sync, and Server-Sent-Events streaming (the one envelope-exempt endpoint) |
| `GET /domains` | The engagement's domains (subsidiaries) |
| `GET /ecosystem/graph?domainId=…` · `/ecosystem/findings` \| `overlaps` \| `manual-dependencies` | The living-canvas data: systems, processes, links |
| `GET /portfolio/subsidiaries/{domainId}` | Subsidiary dashboard aggregate |
| `GET /transformation/plan` | Transformation plan — 404 unless the demo fixture flag is on (no plan engine yet) |
| `GET /llm/cost-summary` | In-process AI token/cost aggregate since startup |
| `GET/POST /branding/configs` · `POST /branding/configs/{id}/activate` · `POST /branding/assets` | White-label branding (admin-gated) |
| Capabilities / processes / systems / organization | Conventional CRUD reads under `/capabilities`, `/processes`, `/systems`, `/organization/*` powering the canvas and designer |

Interactive API documentation is served at `/swagger-ui.html` on a running instance.

### Error code vocabulary

The structured codes a client will actually see, by family. Conflict responses (409) from cascade and agent commands carry the resource's **current detail** in `data` — refresh, don't retry blindly.

| Code | HTTP | Meaning / what to do |
|---|---|---|
| `VALIDATION_ERROR` | 400 | A request-body field failed validation; `errors[].field` names it |
| `BAD_REQUEST` | 400 | Malformed parameter or unparseable value |
| `NOT_FOUND` | 404 | Unknown id on a generic resource |
| `CONFLICT` | 409 | Concurrent modification (optimistic lock or database constraint) — refresh and retry |
| `INTERNAL_ERROR` | 500 | Unexpected server error; quote `meta.requestId` when reporting. Also currently returned when a **required query parameter is missing** (see Troubleshooting) |
| **Cascade** | | |
| `CASCADE_STALE_STATE` | 409 | Your `expectedChainSeq` is behind — the cascade moved on; current detail is in `data` |
| `CASCADE_ILLEGAL_TRANSITION` / `CASCADE_TERMINAL` | 409 | The command is not legal from the current state / the cascade is already resolved |
| `CASCADE_IDEMPOTENCY_REUSE` | 409 | Same idempotency key replayed with a different command |
| `CASCADE_NOT_LEVEL_OWNER` / `CASCADE_NOT_ORIGINATOR` | 403 | Only the level owner may respond / only the originator may resolve |
| **Contracts** | | |
| `CONTRACT_CONTENT_FROZEN` | 409 | Contract content cannot change after proposal |
| `INVALID_CONTRACT_STATE` | 409 | Command not valid for the contract's current status |
| `RESPONSE_CONFLICT` | 409 | A conflicting party response already exists |
| `CONTRACT_DENIAL` | 409 | **The enforcement gateway blocked the action** (`enforce` mode). The message names the contract, the clauses, and a decision id; the decision is recorded even though the action rolled back. A governance outcome, not a bug |
| `AGENT_GATE_DENIAL` | 409 | Same, but the agent-registry gate denied. The structured content carries the specific reason — e.g. `AGENT_CONTROL_SUSPENDED` (a paused agent attempted a governed call) or `AGENT_BUDGET_EXHAUSTED` (an `ACTIONS_PER_WINDOW` throttle budget is spent; calls are denied until the window resets) |
| `WAIVER_HUMAN_ONLY` | 403 | Breach waiver attempted by a non-human principal — waivers are human-only in every mode |
| **Agents** | | |
| `AGENT_NOT_FOUND` | 404 | Unknown agent id/key |
| `AGENT_KEY_CONFLICT` | 409 | An agent with this key already exists in the domain |
| `AGENT_STALE_STATE` / `AGENT_ILLEGAL_TRANSITION` / `AGENT_TERMINAL` | 409 | As the cascade family — current detail in `data` |
| `AGENT_IDEMPOTENCY_REUSE` | 409 | Same idempotency key, different verb |
| `PROMOTION_REVIEW_REQUIRED` | 409 | Promotion attempted without a decided review |
| `AGENT_PROVENANCE_*` family | 400 | Deploy gate: missing provenance/oversight fields, named per field |
| **Diagnostics** | | |
| `DIAGNOSTIC_RUN_ACTIVE` | 409 | A run is already active for this domain |
| `DRAFT_GROUNDING_LOST` | 409 | The draft's cited evidence no longer resolves — it is never silently materialized |
| `CURATION_CONFLICT` | 409 | Curation verb against an illegal state, or idempotency key replayed with a different action |
| `RATIONALE_REQUIRED` | 400 | A reject was submitted without a rationale |
| **AI availability** | | |
| `LLM_UNAVAILABLE` | 503 | No API key configured / provider unreachable — the app itself is healthy (see Troubleshooting) |
| `LLM_REFUSAL` | 422 | The model declined to answer |
| `RATE_LIMITED` | 429 | Provider rate limit; back off and retry |

### MCP server

Substrate mounts **one MCP server** (`substrate-contracts`) at `POST /mcp` (streamable HTTP transport), enabled by default. It carries the contracts tools *and* the agent tools — one server, one principal model. This is the surface AI agents use to read their obligations and report compliance.

**How callers identify themselves**

- *Anonymous mode:* the `X-MCP-Principal` header, trusted on the basis that the deployment is local. Self-asserted and disclosed as such.
- *OIDC mode:* `/mcp` requires a bearer token like every API route (401 otherwise). The principal id is read from the configured token claim (default `sub`, fallback `azp`) and matched against the principal id registered on an agent registration or contract party. The Compose dev realm ships a `substrate-agent` client-credentials client for this.

**Read tools** (ungoverned — no gateway evaluation):

| Tool | Purpose |
|---|---|
| `list_contracts` / `get_contract` / `get_commitments` | Contract cards, full detail (enforcement text verbatim), commitment rows with compliance state |
| `get_obligations_for_principal` | What the *calling* party owes right now: open commitments, due dates, missing attestations |
| `get_breaches` / `get_enforcement_decisions` | Breach rows; the gateway's decision history |
| `check_action` | Advisory pre-check: "would this action be allowed?" — lets an agent ask before acting |
| `list_agents` / `get_agent` | Agent fleet rows; full agent detail |
| `get_my_registration` / `get_my_control_state` | The caller's own mandate, gates and standing; its current mode, budgets and schedule window — the self-limiting reads a well-behaved agent polls |

**Governed write tools** (every call transits the enforcement gateway):

| Tool | Purpose |
|---|---|
| `attest_commitment` / `record_commitment_progress` | Compliance attestations and progress notes |
| `report_metric` | Metric sample against a monitored commitment — hard-requires the caller to be the bound party |
| `respond_to_contract` / `invoke_decline` | Party response to a proposal; invoke an armed decline trigger (a decline returns a *success* result — it is never error-shaped) |
| `acknowledge_breach` / `mark_breach_remediated` | Breach workflow |
| `waive_breach` | Always denied for agent principals — waiver is human-only by a built-in, non-removable guard |
| `agent_heartbeat` / `report_agent_activity` / `acknowledge_control` | Agent runtime liveness, activity reporting, and acknowledgment of a pause/kill control request |

Tool failures come back as `isError: true` tool *results* with structured content (`{code, message, …}`; denials carry `CONTRACT_DENIAL` with contract keys, clause ids and a decision id) — never MCP protocol errors, so agent runtimes can adapt rather than crash. Writes without a principal identity are rejected with `PRINCIPAL_REQUIRED`. One disclosed limitation: apart from `report_metric`, the compliance write tools do not verify that the caller is the bound party of the target commitment — every write is attributed to the verified principal in the audit trail, but attribution is not authorization. This is a known, deliberately disclosed gap pending a product decision.

**Pause/kill honesty on the wire.** Agent control verbs (REST) return the `enforcement` block — `{effect, confirmationState, disclosure}` — defined canonically under [Controlling an agent](#controlling-an-agent-the-honest-control-model). The rule of thumb for operators: `gateway-block` is claimed only when the agent executes through Substrate's MCP surface *and* the enforcement ladder is in `enforce` mode; everything else — agents in orchestrator or customer systems, and *all* agents while the mode is `shadow` or `off` — reports `recorded-intent`, with the disclosure string spelling out the active case in plain language. Substrate never claims to have stopped something it did not stop; read the disclosure, not just the 200.

## Troubleshooting & FAQ

**Dashboards show zeros / blanks right after startup.**
Demo seeding runs *after* the HTTP port opens, so for roughly the first 2–4 seconds the API answers but the demo data is still being written. Wait a moment and refresh. If stats stay empty, check the launch flags: the demo dataset only seeds with `--substrate.demo.seed=true`, and seeded contracts only start active with `--substrate.demo.contracts.activate=true`.

**A GET returns 500 `INTERNAL_ERROR` and the message says nothing useful.**
Check the **required `domainId` query parameter** first. Engine list/read endpoints (`/cascades`, `/contracts`, `/agents` signals, `/diagnostics/runs`, `/decision-log/verify`, `/ecosystem/graph`, …) require `?domainId=<uuid>`; a missing required parameter currently surfaces as a generic 500 rather than a 400. Get a valid id from `GET /api/v1/domains`. (Verified live: the same endpoints answer 200 the moment the parameter is supplied.)

**Chat answers 503 `LLM_UNAVAILABLE`.**
No `ANTHROPIC_API_KEY` is configured (or the provider is unreachable). This is the designed degraded mode: the application is healthy and every non-AI feature works; only AI-backed calls (chat, AI diagnostics, generative drafting) return this structured 503. Supply the key (locally: set the env var and restart; AWS: the two-phase AI-assist enablement in the deploy runbook). For pipeline testing without a key, the deterministic mock provider (`--substrate.llm.provider=mock`) exercises the full mechanics with no network call — see [Running without an AI key](#running-without-an-ai-key).

**The Roadmap view is empty.**
Expected. The transformation Roadmap is backed by `GET /api/v1/transformation/plan`, which returns 404 unless the demo fixture flag (`substrate.transformation.fixture-enabled`) is on — there is no plan engine yet, only a fictional demo mock-up. The UI shows an empty state rather than an error. The flag must stay off in any customer build.

**The Review / Decline buttons on a contract do nothing.**
Intentional in this release — see [Contract detail page](#contract-detail-page). Party responses and declines are performed through the API (`POST /contracts/{idOrKey}/respond`, `POST /contracts/{idOrKey}/decline`) or the MCP tools.

**Where do I author a cascade? I don't see a button.**
There is no cascade authoring UI yet. Cascades are created through the operator-grade write API (`POST /api/v1/cascade-drafts` → edit → `…/issue`); the UI renders issued cascades, their timelines, and attention signals read-only. See [Authoring a cascade](#authoring-a-cascade--what-works-today).

**I paused an agent — is it actually stopped?**
Read the `disclosure` in the verb response (see [Controlling an agent](#controlling-an-agent-the-honest-control-model)). `gateway-block` means its next governed call is denied; `recorded-intent` means Substrate recorded and notified but cannot itself halt the runtime — track `confirmationState` until the runtime acknowledges or a human confirms. Note also that the portfolio Operations tab is read-only: pause/kill are API verbs, not UI buttons, in this release.

**Port already in use / nothing on the expected port.**
The backend defaults to **8080** (`server.port`; override with `--server.port=NNNN` or `SERVER_PORT`). The Vite dev server runs on **5173** and proxies `/api` to 8080. The Compose stack adds Postgres on 5432 and Keycloak on 8081. Demo instances are often started on an alternate port (e.g. 8090) precisely to avoid colliding with a dev backend — check the launch command before assuming a crash.

**My data disappeared after a restart.**
In the zero-dependency dev shape the application database is **in-memory H2** — every restart is a clean slate (plus the few seconds of re-seeding, if enabled). This is by design for development. Two contrasts: the **ingestion CLI** (and an app booted with the `dev-shared` profile) uses a *file-based* H2 under `substrate/data/`, so committed ingestion entities survive restarts — delete that directory to reset; and the Compose/customer shapes run Postgres, where nothing resets.

**Is the demo data real?**
No. Everything seeded by the demo pack is fictional — the engagement identity ("Meridian Bancorp · Risk Consolidation"), the portfolio subsidiaries (Curasync Clinical Systems, RevSync Health Financial, Sentari Lab Informatics, Pulsewell Telehealth, ConstructLedger, …), and the ingestion sample corpus ("Glacier Peak Credit Union"). None of it may ship in a customer build: `substrate.demo.seed`, `substrate.demo.contracts.*`, `substrate.demo.fixed-clock`, and `substrate.transformation.fixture-enabled` all default off and must stay off. The seeder also refuses to write into non-demo domains by construction.

**Where are the logs?**
Stdout, as structured JSON. Local dev: the terminal running `gradlew :app:bootRun`. Compose: `docker compose logs substrate-app`. Customer AWS: the engagement's CloudWatch log group (`aws logs tail <log-group> --since 10m`). Correlate any API response to its log lines via `meta.requestId` / the `X-Request-Id` header.

**Something looks tampered with — how do I verify the audit chain?**
Decision-log entries are hash-chained per domain. `GET /api/v1/decision-log/verify?domainId=<uuid>` recomputes the chain and, on failure, names the first broken link in `firstBrokenAt` — the full report format and what a break means are under [Reading and verifying the decision log](#reading-and-verifying-the-decision-log). The same check runs automatically at every boot (`substrate.decision-log.verify-on-boot`, on by default) — a tampered chain is loud, not silent. For AI findings, `GET /api/v1/diagnostics/verify?domainId=<uuid>` sweeps every AI-generated finding's evidence chain and names any dangling hop, and `GET /api/v1/findings/{id}/provenance` walks a single finding hop-by-hop back to its source documents.

## Further reading

The documents this manual builds on, all in the repository:

- [`substrate/docs/dev-setup.md`](../dev-setup.md) — local development shapes, tests, and the ingestion CLI quickstart
- [`substrate/docs/auth.md`](../auth.md) — the full auth-mode reference: anonymous vs OIDC, persona projection, admin gating
- [`substrate/docs/deploy.md`](../deploy.md) — the per-engagement AWS deployment runbook
- [`substrate/docs/observability.md`](../observability.md) — metrics, logs, and tracing detail for engagement operators
- [`substrate/docs/engine-spec/`](../engine-spec/) — the engine specifications (`cascade.md`, `contracts.md`, `agents.md`, `diagnostic.md`) with the full state machines behind the vocabularies in this manual
- [`substrate/docs/acceptance/final/visual-verification.md`](../acceptance/final/visual-verification.md) — the screenshot-evidenced visual acceptance tour of the shipped surfaces
- [`substrate/docs/acceptance/`](../acceptance/) — runnable acceptance evidence for every operator workflow (see [Where acceptance evidence lives](#where-acceptance-evidence-lives))
