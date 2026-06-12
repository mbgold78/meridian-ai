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
| Coordination contracts | Cross-subsidiary commitments, scheduled monitors, decline triggers, an enforcement gateway (off / shadow / enforce) | Contract detail pages under `/portfolio` |
| Agent registry | Durable agent identity, oversight chains, lifecycle state, interventions, control verbs with disclosed enforcement effect | Subsidiary **Operations** tab |
| Diagnostic | Findings over the ingested model, severity counts, computed portfolio statistics | Canvas risk badges; dashboard stat strips |
| Decision log | Append-only, hash-chained record of design decisions and engine accountability events; the chain is re-verified at every boot | Future State Designer decision table |

Two properties shape how engagements buy and run it:

- **Sellable whole or in part.** Modules deploy independently. An engagement that only needs the diagnostic and decision-log engines can run without the rest; absent modules degrade honestly (for example, agent controls disclose that no enforcement gateway is present) rather than pretending to work.
- **Runs with or without the orchestrator.** Substrate is a sibling to Meridian's orchestrator product, which it calls when work should be done by AI agents (diagnostic generation, AI-assisted cascade drafting). With the orchestrator absent — the default — everything works except the AI-suggestion endpoint for cascade drafts, which simply does not exist.

Substrate is **not** a multi-tenant SaaS. Each engagement is its own deployment in the customer's infrastructure, with the customer's identity provider and the customer's data residency.

## The two user roles

**Meridian engagement operators** run the platform for the client: they deploy it, run ingestion, configure branding and authentication, author cascades and contracts through the API, register and deploy agents, and watch platform health. Operator-facing tools include the ingestion CLI, the OpenAPI explorer, and the admin endpoints.

**Client-side executives and COOs** log in to see and act: explore the canvas of their systems and processes, read findings, respond to cascades (including declining them), review coordination contracts, and watch the agent fleet operate under its oversight chains. In the demo dataset, the `/portfolio` landing page makes the two client vantage points explicit — a **Corporate Operating Partner** view across all subsidiaries and a **Subsidiary COO** view inside one operating company.

Within a session, what you see can also be shaped by a **persona** (Executive, Architect, and so on) — covered under First-run orientation below. In a production deployment the persona is derived from your sign-in identity; in local development you pick it from a switcher.

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

All of these default to **off**. The first two are the standard demo boot; the rest are situational levers. None of them ever belongs in a customer engagement build.

| Flag | What it does |
|---|---|
| `--substrate.demo.seed=true` | Seeds the Beacon Industries demo dataset (inventory below). Seeding only ever touches demo-marked workspaces — it never writes into engagement data, and on a database with only real workspaces it does nothing. |
| `--substrate.demo.contracts.activate=true` | Records the fixture acceptances so the tribal-knowledge coordination contract boots in the ACTIVE state (and its scheduled monitor runs). Without it both demo contracts sit at PROPOSED. Only effective together with the seed flag. |
| `--substrate.demo.contracts.guards=true` | Additionally seeds a synthetic guard-bearing contract for demonstrating the enforcement gateway (the two standard demo contracts deliberately compile to zero guards). Only effective together with the seed flag. |
| `--substrate.demo.fixed-clock=2026-05-12T00:00:00Z` | Pins the engines' clock to a fixed instant so date-relative displays are deterministic in demos. |
| `--substrate.transformation.fixture-enabled=true` | Serves the fictional transformation-plan mockup that backs the canvas Roadmap view. With the flag off (the default) that endpoint returns 404 and the Roadmap view has no data — see "What does not work yet". |

### What the demo seed creates

Verified against a freshly seeded running instance:

| Seeded | Detail |
|---|---|
| 23 workspaces | 1 primary engagement workspace ("Operational Risk" — the fictional *Meridian Bancorp · Risk Consolidation* engagement) plus 22 Beacon Industries subsidiary workspaces |
| 12 systems, 26 manual processes | The canvas nodes of the primary workspace, with integrations, organization members, and process–system links |
| 21 findings | 3 critical, 8 high, 10 medium — all on the primary workspace |
| 70 agents | Distributed across the Beacon subsidiaries with oversight chains; 8 flagged as requiring intervention |
| 3 cascades | Including one currently in a declined state, so the upward-Decline timeline is visible |
| 2 coordination contracts | "Tribal-knowledge depopulation across four healthcare/insurance subs" (ACTIVE when the activate flag is set) and "Shared compliance-form pre-fill agent" (PROPOSED federation invitation) |
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
- **Bottom** — the chat bar. Ask a question and the assistant answers about your data, highlighting what it talks about on the canvas. **This requires a configured Anthropic API key** (`ANTHROPIC_API_KEY` in the server's environment). Without one the server answers with a clear "LLM unavailable" error rather than fabricating a response, and the rest of the product is unaffected.

**The persona switcher.** In local anonymous mode it is a dropdown: **Architect** (the default) and **Executive** are functional; Domain Lead, Subject Matter Expert, Auditor, and Data Owner are labeled *Preview* and not yet differentiated. Your selection travels with every request and shapes persona-aware behavior such as assistant responses. It persists across reloads in the browser. Under real OIDC sign-in there is nothing to switch: the switcher becomes a read-only identity badge showing your name and the persona derived from your token's roles, plus a sign-out button.

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

One visible consequence worth knowing before a demo: the dashboard's lifecycle-stall card truthfully reports the 22 subsidiaries as "unphased (no lifecycle data)" — the live engine has no phase records for them — while the fixture grid directly below confidently places each one in a phase. Both halves are honest about their own source; the pairing reads oddly until the per-subsidiary phase data goes live.

Screenshots of all of these surfaces, captured from a seeded build, are in [`../acceptance/final/`](../acceptance/final/) (for example [`01-living-canvas.png`](../acceptance/final/01-living-canvas.png), [`05-portfolio-dashboard.png`](../acceptance/final/05-portfolio-dashboard.png), [`06-cascade-detail-decline-pending.png`](../acceptance/final/06-cascade-detail-decline-pending.png)).

### What does not work yet

Stated plainly, because each of these is somewhere a user will look:

- **Cascade authoring has no UI.** Cascades are created and advanced through the API only, and that write API is operator-grade — leadership-facing authoring is a later phase. Reading cascades in the UI is fully built.
- **Contract Review and Decline buttons are intentionally inert.** The contract detail page renders them disabled with an explanatory tooltip: Review and Decline are first-class actions in the contract model, but their product flows are explicitly unbuilt, and the buttons say so rather than doing something undefined.
- **The transformation Roadmap is behind a disabled flag.** By default the plan endpoint returns 404 and the Roadmap drawer view has nothing to show. The flag (`substrate.transformation.fixture-enabled=true`) serves a fictional mockup for demos only — there is no plan engine behind it yet.
- **AI features need an API key.** Chat and AI-assisted diagnostics call a real model and require `ANTHROPIC_API_KEY` on the server. Without it they degrade visibly (the chat answers with an LLM-unavailable error; the ingestion CLI offers a deterministic mock provider for exercising the pipeline). Nothing else degrades.
- **Agent pause/kill enforcement depends on the enforcement mode.** The pause, kill, and throttle verbs exist in the API, and every response (and the agent detail) discloses what the verb actually did: `GATEWAY_BLOCK` means the platform's gateway will actually refuse the agent's actions; `RECORDED_INTENT` means the platform recorded the instruction and notified the oversight chain but cannot itself stop the runtime — the agent shows as "paused — unconfirmed at runtime" until a human confirms it. The platform never claims enforcement it does not have. The client-facing Operations tab is currently **read-only**: it shows fleet status but ships no pause/kill buttons yet, so these verbs are operator-API-only today.

### Docker Compose and Keycloak (Docker-capable machines)

If your machine runs Docker, the full stack — app with PostgreSQL and real OIDC sign-in against a bundled Keycloak — comes up with:

```powershell
cd substrate
copy .env.example .env    # then edit passwords
docker compose up --build
```

That gives you the app on :8080 (postgres profile), PostgreSQL on :5432, and Keycloak on :8081 with a pre-imported realm and throwaway dev users `dev-admin`, `dev-operator`, `dev-exec`, and `dev-coo` (password = username) so you can experience real sign-in, role-derived personas, and the read-only identity badge. Keycloak is a development/testing identity provider only — production engagements connect to the customer's own IdP through generic OIDC configuration, no code changes.

Full setup detail lives in [`dev-setup.md`](../dev-setup.md) (local shapes, tests, ingestion CLI), [`auth.md`](../auth.md) (the auth-mode ladder and persona mapping), and [`deploy.md`](../deploy.md) (per-engagement deployment into a customer AWS account). This manual does not duplicate them.

### Further reading

- [`../dev-setup.md`](../dev-setup.md) — local development shapes and the ingestion CLI quickstart
- [`../auth.md`](../auth.md) — anonymous vs OIDC modes, persona projection, admin gating
- [`../deploy.md`](../deploy.md) — the engagement deployment runbook
- [`../observability.md`](../observability.md) — metrics and tracing for engagement operators
- [`../engine-spec/`](../engine-spec/) — the engine specifications (cascade, contracts, agents, diagnostic), including the full state and signal vocabularies used later in this manual
