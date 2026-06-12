## The client surfaces

Meridian Substrate ships four client-facing surfaces. Each one is a browser route served by the platform itself, so every URL below can be bookmarked, shared, and hard-refreshed safely. An address that matches no surface renders a "Page not found" panel with a way back — never a blank screen.

| Surface | Route | What it shows |
|---|---|---|
| Living Canvas | `/` | The ecosystem map for the current engagement: systems, data flows, manual processes, risks, the reduction thesis, and the decision log. |
| Future State Designer | `/designer` | The design-decision register: create decisions, run impact analysis, see every decision land in the tamper-evident log. |
| Capability Designer | `/designer/capabilities` | The editable capability model: a tree you can rename, reorder, reparent, and extend, with AI-drafted proposals. |
| Portfolio | `/portfolio` and everything under it | The holding-company dashboards: the Operating Partner view, the Subsidiary COO view, cascade detail, and coordination-contract detail. |

A few older links redirect rather than break: `/ecosystem` goes to the canvas, `/diagnostics` opens the canvas with the Risks panel, `/roadmap` and `/transformation` open the Plan panel, and `/decisions` opens the decision Log panel.

**Shared chrome.** On the canvas, a floating wordmark sits top-left (it carries the workspace's configured product name — "Meridian" in the demo) and three controls sit top-right: a link to the **Capability Designer**, a link to **Portfolio**, and the persona control. On other canvas-family pages the chrome collapses to a "Back to canvas" bar. What the persona control does depends on how you signed in:

- **Development / demo deployments** (anonymous access): it is a dropdown that picks the persona sent with every request — **Architect** (technical analysis) and **Executive** (business-outcome focus) are selectable today; Domain Lead, Subject Matter Expert, Auditor, and Data Owner appear in the list but are marked not yet ready. The persona shapes how the AI assistant answers; it does not change what data you can see.
- **Production deployments** (single sign-on): there is nothing to switch. You see a read-only identity chip — your name, your persona as derived from your verified login, and a sign-out button.

**Which engagement am I looking at?** The canvas-family surfaces scope themselves to a *workspace domain* — the engagement's data boundary. The platform resolves it automatically: a deploy-time pin wins if one is configured, otherwise your selection, otherwise the only (or first non-demo) domain in the database. On a freshly installed environment with no ingested data, the canvas shows a "No workspace yet" panel explaining that data arrives through ingestion — that is a normal first-run state, not an error.

---

## Living Canvas (`/`)

The Living Canvas is the home surface: one screen that draws every system the client runs, every data flow between them, and every manual process people perform by hand. Screenshot: `../pipeline/build/captures/01-living-canvas.png`.

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

- A **finding badge** — a red or orange numbered circle counting open findings against that system. Red (with a pulsing halo around the whole tile) means at least one finding is CRITICAL or HIGH severity; orange means only lower severities are open.

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

Below the ribbon, a one-line subtitle changes with the active lens. In Target and Waves it explicitly says *"Illustrative retirement heuristics — plan engine pending"* — see the honesty note under the lens rail.

### The lens rail (left edge)

Three lenses change what the map shows:

| Lens | What you see |
|---|---|
| **Today** | What the client runs right now. |
| **Target** | The end state: systems slated for retirement fade to gray; the survivors stay lit. |
| **Waves** | The journey: a timeline scrubber appears and systems fade out month by month as you drag. |

> **Honest limitation.** Which systems fade, and when, in Target and Waves currently comes from illustrative demo heuristics, not from a computed transformation plan — the plan engine is not built yet. The on-screen subtitle says so whenever those lenses are active. Treat Target/Waves as a storytelling view, not a committed schedule.

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
| Risks | "Risks · What hurts" | All open findings, sorted by severity, with count chips per severity. Severity vocabulary: **CRITICAL** (red), **HIGH** (orange), **MEDIUM** (amber), **LOW** (green); findings without a severity render with the blue **INFO** fallback style. Click a finding to expand its description, the recommendation, and chips for every affected system — expanding also spotlights those systems on the map. |
| Plan | "Plan · Waves" | The wave roadmap: before→after metrics (systems, roles, operating cost) and initiatives grouped under Waves 1–3, each with complexity, duration, and owner; "Jump →" scrubs the map to that wave's start month. **Honest limitation:** the transformation-plan engine is not built. In a default build the plan endpoint intentionally returns nothing (it is behind a demo fixture flag that ships off), and this tab sits on "Loading transformation plan…" indefinitely. When the demo flag is on, what you see is a fixture mock-up, not computed plan data. |
| Log | "Log · Decisions" | The domain's decision log, newest first. Each entry shows its title, status chip (see Designer section for the vocabulary), decision type, date, and — in monospace — the hash chain link: `prev <hash> → <hash>`. The **Verify chain** button asks the engine to re-walk the entire chain and reports the result inline, e.g. "✓ All 6 decisions verified; chain is intact · 6/6 links" (green) or a red failure message identifying the break. Verification is a read-only check; it changes nothing. |
| From | "From · Source chain" | Provenance. With no system selected, it lists the evidence chain for every ingested entity in the workspace, grouped by source file; click a system tile first and it scopes to that system. Each evidence card shows the label, source file and document type, the extractor that produced it, a fingerprint (SHA-256 prefix) of the source document, and a confidence pip: green ≥ 90%, amber 70–89%, red below 70%. |

### The ambient chat bar (bottom center)

A persistent input bar — "Ask Meridian anything…" — with four suggestion chips when the conversation is empty (e.g., "What is my biggest risk?"). Press **Enter** or click **Ask** to send; Shift+Enter inserts a newline. Replies appear in a conversation bubble above the bar (collapsible). When the assistant used data tools to answer, small monospace pills under the reply name them — and the canvas spotlights the systems involved: finding-related tools light up at-risk systems and auto-open the Risks panel; overlap tools light up systems that share duplicate data entities.

> **Without an AI key.** The chat assistant requires the platform to be configured with an Anthropic API key. Without one, the rest of the canvas works normally, but asking a question returns an error reply in the bubble. (The backend answers with a clear "LLM unavailable" message; the current UI shows the generic "Request failed with status code 503" instead of that friendlier text — a known cosmetic gap.) The page never crashes or unloads.

### Add Source (bottom left, above the legend)

The **+ Add Source** button opens a modal that probes a URL you paste (for example an OpenAPI document). Press **Probe** and the platform identifies the API type — OpenAPI/Swagger, OData, GraphQL, plain JSON, or Unknown — with a confidence percentage (green ≥ 85%, amber 60–84%, red below), hints about what it found, and up to eight sample endpoints.

> **Honest limitation.** This modal is identification only. The **"Add to project"** button does not yet register the source or trigger ingestion — it simply closes the dialog. Actual data ingestion runs through the operator-side ingestion CLI (see the operator section of this manual).

---

## Future State Designer (`/designer`)

The decision register: where target-architecture decisions are recorded, analyzed, and made permanent. Screenshot: `../pipeline/build/captures/02-designer.png`.

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

Beyond human-authored decisions, the engines append their own governance events here — for example, activating a coordination contract writes an accountability row into the same register, so the log is a single audit trail across human and machine actions.

### Creating a decision

**New Decision** (top right) opens the form inline. Title and Description are required (inline validation); Decision Type is a dropdown of the nine types above; Rationale is optional. **Affected Systems** and **Affected Manual Processes** take comma-separated entity IDs.

> **Honest limitation.** There is no picker for affected systems/processes yet — you paste IDs. Practically, this form is used by the engagement team, not client executives. Executives consume decisions through the canvas Log tab and this table.

**Create Decision** submits; the new row appears immediately with status `PROPOSED`.

### How decisions become permanent

Every created decision is appended to the domain's hash-chained decision log: the entry stores a cryptographic hash of its content plus the hash of the previous entry, so any later tampering breaks the chain visibly. This is the same log the canvas **Log** tab displays (prev → current hashes on every entry) and the same chain its **Verify chain** button re-validates. Nothing in the UI can edit or delete a logged decision — superseding it is itself a new entry.

### Run Impact Analysis

Rows with status `PROPOSED` carry a **Run Impact Analysis** button. It sends the decision's scope and description to the AI for evaluation and opens a panel below the table containing a written summary plus two tables — **System Impacts** and **Process Impacts** — each rated HIGH (red), MEDIUM (amber), or LOW (green) with an explanation. **Close** dismisses the panel.

> **Without an AI key**, the analysis fails and the panel shows an error banner; the decision itself is unaffected.

---

## Capability Designer (`/designer/capabilities`)

A full-screen, two-pane editor for the engagement's capability model. Screenshot: `../pipeline/build/captures/03-capability-designer.png`. (If your workspace renames "Capability" to another term, every label here follows suit.)

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

> **Honest limitations.** Drafts are a **read-only preview** — there is no accept button yet; committing a draft into the tree ships in a later phase. And this feature is AI-backed: without an Anthropic API key the dialog shows a "Request failed" error.

---

## Portfolio (`/portfolio`)

The holding-company surfaces, presenting a fictional demonstration portfolio — **Beacon Industries**, 22 subsidiaries across 9 industries. These dashboards always render in dark mode and carry their own chrome: an amber provenance banner on top, then a header bar showing where you are, with **Switch vantage point** and **Exit** controls.

**Read the banner — it tells you what is real.** These surfaces mix two kinds of data, and the banner states which mode you are in:

- *"Beacon Industries — Demo workspace · live engine data + illustrative fixtures"* — the platform's engines are answering: cascades, contracts, agents, and portfolio statistics are live, served from the seeded engagement; the remaining content (subsidiary roster, revenues, opportunity cards) is illustrative fixture data.
- *"Beacon Industries — Fixture dataset (demo)"* — the backend is not answering; everything on screen is deterministic illustrative data.

The sections below note per-element which engine serves it live.

### Landing page — the vantage-point picker (`/portfolio`)

Screenshot: `../pipeline/build/captures/04-portfolio-landing.png`. Two cards:

- **Corporate Operating Partner** — the federated view across all subsidiaries. "Enter portfolio dashboard" goes to `/portfolio/dashboard`.
- **Subsidiary COO** — one operating company's view. Choose a subsidiary from the dropdown, then "Enter subsidiary view" goes to `/portfolio/subsidiary/<slug>`.

A text link below exits to the standard product, and a footnote restates the fixture/live data split.

### Corporate Operating Partner dashboard (`/portfolio/dashboard`)

Screenshot: `../pipeline/build/captures/05-portfolio-dashboard.png`. Five zones, top to bottom:

**Zone 1 — Header strip.** Portfolio identity figures (subsidiary count, industries, total revenue, active phases — all fixture), plus a provenance chip on the right repeating the live/fixture disclosure; hover it for the precise breakdown.

**Zone 2 — Coordination signals.** Four stat cards:

| Card | Meaning | Source |
|---|---|---|
| Cross-portfolio opportunities | Capabilities being redundantly rebuilt across subsidiaries | **Always fixture** (kept coherent with the Zone 4 cards below) |
| Agents in intervention | Deployed AI agents currently requiring human intervention, "of N registered" | **Live** — agent registry. If the registry is unreachable the card shows an em-dash and says "registry unavailable — counts withheld, not zero"; it never fabricates a 0 |
| Lifecycle stalls | Subsidiaries sitting 90+ days in their current phase | **Live** — and when subsidiaries have no lifecycle data at all, the card says so separately ("· N unphased (no lifecycle data)") rather than hiding them in the stall count. In the current demo seed, lifecycle data exists only for the root engagement, so this card honestly reports the 22 satellite subsidiaries as unphased — even while the fixture phase grid below plots them confidently. Trust the card; the grid is illustrative |
| Coordination signals | Cross-subsidiary patterns surfaced this week (contract events + cascade actions) | **Live** |

**Zone 3 — "Where each subsidiary sits."** Seven columns, one per lifecycle phase — **Plug-in, Design, Map, Plan, Execute, Monitor, Automate** — with each subsidiary as a clickable chip (left-edge color = its industry). Fixture data. Clicking a chip enters that subsidiary's COO view.

**Zone 4 — "Where coordination is failing."** AI-surfaced opportunity cards (fixture): title, narrative, affected-subsidiary chips, estimated annual impact. Cards that have been promoted to a documented coordination contract are clickable and open the contract detail surface; cards without a contract are display-only. The subsidiary chips inside a card navigate to that subsidiary without triggering the card.

**Zone 4.5 — "Cascades you've issued."** **Live** (fixture fallback in degraded mode). A cascade is a critique an executive issues down the chain of command; each card shows the critique's first sentence, when it originated, the chain depth it currently sits at, and an attention chip:

| Chip | Meaning |
|---|---|
| Decline pending (red) | A level below has formally declined — this needs the issuer's attention. A decline is a documented governance outcome, not an error |
| Escalating (blue) | The critique is still moving down the chain — a response is out |
| Absorbed (green) | A level has taken the work — no further cascade needed |
| Overdue (amber, separate chip) | A response deadline has passed (only when deadlines are enabled) |

Cards order themselves by operator urgency: decline-pending first, then overdue, then the rest by recency. Only in-flight cascades appear here; if none are in flight the section disappears. **Inspect cascade** opens the detail surface.

> **Honest limitation.** There is no "issue a cascade" button anywhere in the client UI yet — cascade *authoring* is operator-grade only (API/MCP). This dashboard is the read side.

**Zone 5 — Full portfolio roster.** A sortable table of all subsidiaries (fixture): name and industry, phase pill, capability/process counts, agent count (with a red "N intervention" chip where applicable), findings count (amber-highlighted above 5), and revenue. Every name links into the COO view. On narrow screens the table becomes stacked cards.

### Subsidiary COO view (`/portfolio/subsidiary/<slug>`)

The single-company vantage point — one operating company plus the parent's signals reaching into it. Top to bottom: a header strip (identity, description, revenue/headcount/HQ rail — fixture), then the **portfolio context band**: "Part of Beacon Industries · Currently in <phase> phase · N cross-portfolio opportunities involve you · N federation invitations pending." Segments with a non-zero count are clickable; the federation-invitation segment opens the relevant coordination contract with a back link that returns here.

Four tabs follow; the active tab rides the URL (`?tab=operations`), so tab views are linkable and browser back/forward works.

| Tab | Contents | Source |
|---|---|---|
| **Capabilities** | A read-only two-pane copy of the capability model (tree + detail) scoped to this subsidiary. It deliberately mirrors the real Capability Designer's layout, but nothing here is editable — the page says so. | Fixture |
| **Environment** | System cards (kind, vendor, integration count), one card flagged where the platform sees concentrated tribal-knowledge risk, plus external integration partners. | Fixture (partners are illustrative per-industry stubs) |
| **Plan** | Initiatives in flight with per-initiative progress meters, value, and target dates; the seven-phase lifecycle spine with the current phase highlighted; and the canonical completion requirements for leaving the current phase. | Fixture |
| **Operations** | What the company is running today — see below. | **Mixed; agent data is live** |

**The Operations tab** (screenshot: `../pipeline/build/captures/08-subsidiary-operations.png`) has three regions:

1. **Fleet stat strip** — *Agents active* and *Agents in intervention* come **live** from the agent registry whenever it answers (each Beacon subsidiary maps to its own seeded engagement domain); *Open findings* is still fixture data.
2. **Recent agent activity** — the agent fleet, **live** from the registry. Each row: name, role, latest observed activity (derived from real sightings and lifecycle events — "No activity observed" when there is none, never an invented line), a provenance line ("Deployed by … · date · Oversight: chain"), and a governance-status pill:

   | Pill | Meaning |
   |---|---|
   | Operating (green) | Live in production |
   | Shadow mode (blue) | Running alongside humans, output not acted on |
   | Paused (amber) | Suspended by its overseers |
   | In intervention (red) | Flagged — requires human intervention |

   Retired agents never appear in this list. Clicking a row expands its **deployment panel**: the deployment criteria recorded when the agent was approved (fetched on demand; "not yet recorded" when absent — never fabricated) and the numbered **oversight chain** of accountable humans.

   > **Honest limitation.** This tab is **read-only**. The status pill is the *governance* state — what the registry says the agent should be doing — which is not the same as runtime confirmation. There are no pause/kill buttons in this UI yet; agent control verbs (and the runtime-confirmation and enforcement-mode disclosures that come with them — whether a pause is a recorded instruction or a gateway-enforced block) live in the operator-grade agent API today and ship here in a later phase. See the operator section of this manual.

3. **Open findings** — severity-pilled cards (Low blue / Medium amber / High red) with detail, related-system chips, and how long ago each surfaced. Fixture data.

### Cascade detail (`/portfolio/cascade/<id>`)

Screenshot: `../pipeline/build/captures/06-cascade-detail-decline-pending.png`. **Live** from the cascade engine (fixture fallback in degraded mode); an unknown id renders a "Cascade not found" panel with a back link.

- **Header** — an eyebrow stating the aggregate state (*In-flight cascade*, *Resolved cascade — looks good*, or *Resolved cascade — declined*; a resolved decline is a peer outcome, never failure language), the original critique verbatim (it travels the chain unchanged), who issued it and when, and an **Acceptance criteria** panel — what "fixed" means, in the issuer's words.
- **Level-by-level transparency** — every level of the chain (L0, L1, …) with its owner, date, and a state pill:

| Level state | Meaning |
|---|---|
| Looking (amber) | This level holds the critique and has not yet decided — "Awaiting response since <date>" |
| Cascaded (blue) | This level refined the critique and passed it down; the refined wording is shown |
| Absorbed (green) | This level took the work; the cascade stops here |
| Declined (red) | This level formally pushed back; its reasoning (which travels *up*) is shown |

- **Action timeline** (live mode only) — the append-only log of every recorded step, oldest first, numbered: Issued, Cascaded, Absorbed, Declined, Recascaded, Resolved — looks good, Resolved — declined, each with actor, level, date, and any attached reasoning. This is the issuer's single audit trail.
- **Cascade summary** footer — the current resting state in one line.

### Coordination contract detail (`/portfolio/contract/<id>`)

Screenshot: `../pipeline/build/captures/07-contract-detail.png`. **Live** from the contract engine (fixture fallback in degraded mode); reachable from opportunity cards on the dashboard and from a subsidiary's federation-invitation segment (the back link returns wherever you came from). Unknown ids render a "Contract not found" panel.

A coordination contract is a documented, monitorable agreement between parts of the portfolio — not a slide-deck proposal. The surface renders its terms first-class:

- **Header** — contract type (*Cross-portfolio opportunity* or *Federation invitation*), title, proposal date, linked subsidiaries, and the estimated value when one is recorded.
- **Context** — the narrative of why this contract exists.
- **Parties and commitments** — each party with its enumerated obligations.
- **Enforcement** — three panels: **Monitoring** (what the platform watches, in prose), **Mechanism** (the monitoring rule in machine-readable form, shown verbatim), and **Decline triggers** — the documented conditions under which a party may decline without being in breach.
- **Available actions** — **Review proposal** and **Decline** buttons.

> **Honest limitations.** The Review and Decline buttons are **deliberately inert** in this release: they render (with tooltips saying the flow is not defined in v1) because review and decline are first-class concepts of the contract model, but the product flows have not shipped — contract responses are currently operator-grade actions. Also, this surface does not yet display the contract's lifecycle status: a contract that has been activated still shows only its "Proposed <date>" line here (the activation event is visible as a ledger row in the Future State Designer's decision register).

---

### Further reading

- Engine state vocabularies and invariants: `substrate/docs/engine-spec/` (`cascade.md`, `contracts.md`, `agents.md`, `diagnostic.md`).
- The full visual acceptance tour these surfaces were originally verified in: `substrate/docs/acceptance/final/visual-verification.md` (its hand-captured screenshots are archived alongside it; the screenshots referenced above are regenerated by the docs pipeline).
