## Agent operations

Substrate keeps a governance registry of every AI agent operating inside the engagement. The registry is not a deployment tool — agents run wherever they run (a customer's EHR integration, an orchestration platform, substrate's own tool gateway). What the registry guarantees is that **no agent exists in the engagement without provenance, a mandate, and a named chain of accountable humans** — and that every control action you take against an agent is recorded, attributed, and honest about what it actually did.

### What every registered agent carries

A registration cannot be created without four facts. The API rejects a deploy that is missing any of them, so on every agent you look at, these are always present:

| Fact | What it is |
|---|---|
| **Deployed by** | The person or team that put the agent into operation (a label, optionally linked to an organization member). |
| **Deployed at** | The timestamp of the deployment act, set by the server — not self-reported. |
| **Deployment criteria** (the mandate) | The verbatim prose statement of what the agent is for and what it must achieve — for example, *"Route inbound patient messages to clinical or front-office queues with target 95% routing accuracy and zero PHI leakage to non-clinical staff."* The mandate is **immutable after deployment**. There is no edit operation. Changing an agent's mandate means retiring the registration and deploying a successor, which keeps a lineage link to its predecessor — so the audit trail can never be quietly rewritten. |
| **Oversight chain** | An ordered list of accountable people — for example *Front Office Manager → Compliance Officer → COO*. Position 1 receives signals first; unresolved problems escalate up the chain automatically (after 3 days for interventions, by default). At least one entry is required. |

Each registration also records where the agent executes (its **execution locus**: substrate's own tool gateway, an orchestration platform, or a customer system — this determines what "pause" can physically mean, see the control model below), its **supervisory model** (full-auto / human-review-above-threshold / human-in-loop — recorded and displayed; substrate does not itself enforce the threshold), and safety defaults (agents are **read-only at the gateway unless explicitly granted otherwise**).

### Where you see agents

In the demo workspace, open a subsidiary's dashboard (Subsidiary COO vantage point) and select the **Operations** tab. See `../pipeline/build/captures/08-subsidiary-operations.png`.

The tab has three regions:

1. **Fleet stat strip** — *Agents active* (the whole non-retired fleet), *Agents in intervention*, and *Open findings*. The agent counts come from the live signals API.
2. **Recent agent activity** — one row per agent showing the name, role, a status pill, the most recent activity line, and a provenance line that always renders: *"Deployed by Curasync RCM team · Sep 14 2025 · Oversight: Curasync RCM Lead → Curasync COO"*. The activity line is **derived from real observations** (the latest sighting, else the latest lifecycle event, else "No activity observed") — it is never invented. An adherence figure with no reported data renders as an em-dash, never a fabricated number.
3. Clicking a row expands a panel showing the **verbatim deployment criteria** and the **numbered oversight chain**. The mandate is deliberately kept off the row line — it is a click-through detail.

Be aware of one honesty boundary in the current UI: **the Operations tab is read-only**. There are no pause/kill/throttle buttons on screen yet. All agent lifecycle controls are operator-grade REST calls (reference table below). The product rule is that no control affordance ships in the UI ahead of the full honest-disclosure plumbing behind it.

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

Many agents run in systems substrate cannot command — a vendor's runtime inside the customer's network, for example. A "Pause" control that claimed to stop such an agent would be a lie. Substrate refuses to tell that lie, structurally:

**Every control response declares what actually happened.** When you pause, kill, or throttle an agent, the response carries an `enforcement` block:

```json
{ "effect": "gateway-block" | "recorded-intent",
  "confirmationState": "not-required" | "pending" | "acknowledged" | "confirmed",
  "disclosure": "<plain-language statement of what this control physically did>" }
```

And **before** you act, the agent detail (`GET /api/v1/agents/{idOrKey}`) carries a `controlEffects` map showing the same effect-and-disclosure for pause, kill, and throttle — so you know what a control will do before you invoke it.

The two effects:

- **`gateway-block`** — the agent's actions transit substrate's own tool gateway, and enforcement mode is active. The control is absolute on that path: the agent's next governed call is denied (within roughly 30 seconds of cache settling). Nothing further to confirm — `confirmationState` is `not-required`.
- **`recorded-intent`** — substrate cannot physically reach the runtime. The control records the order in the append-only event log, notifies the oversight chain, and opens **confirmation tracking**: the runtime may acknowledge the order (a machine fact, the one write a suspended agent is still allowed), and a named human may confirm the agent actually stopped, with evidence. Until then the agent shows as *"Paused — unconfirmed at runtime."* Crucially, **silence is never treated as compliance** — an absence of sightings never auto-confirms a stop. If a recorded-intent control sits unconfirmed past a timeout (24 hours by default), the oversight chain is notified.

The disclosure text varies honestly by situation. Verified examples from a running instance:

- Customer-system agent: *"records intent and notifies the oversight chain; substrate cannot reach this runtime — runtime acknowledgment and human confirmation are tracked, and sighting absence never auto-confirms."*
- Orchestrated agent: *"records intent; no new dispatches are submitted on this mandate and dispatch results are quarantined at apply — in-flight dispatches cannot be aborted."* (Work already in flight cannot be stopped, but its results are refused on arrival.)
- Gateway agent with enforcement in shadow (observe-only) mode: the disclosure states plainly that governed calls are *logged as would-deny and signaled, not blocked* — and that enforce mode would make it a hard block.

Pause, kill, and retire all require a recorded reason. Resume and promote are different in kind: substrate's own registry state *is* the control plane for them, so they take effect immediately everywhere.

### Zombie signals

If a paused or killed agent is observed acting anyway, substrate does four things at once: records the sighting (it is true and belongs in the record), denies the governed action when enforcement allows, appends a control-violation event, and raises a signal to the entire oversight chain:

| Signal | Meaning | Urgency |
|---|---|---|
| `ACTIVE_WHILE_PAUSED` | A paused agent was sighted acting. | Warning-to-destructive; opens/refreshes an intervention automatically. |
| `SEEN_AFTER_KILL` | A killed or retired agent was sighted acting. | The highest-urgency signal class in the registry. |

These appear in the fleet signals (`controlViolations`) and the attention feed. For an externally-run agent, this is exactly the design intent: substrate cannot always stop the agent, but non-adherence to a recorded order becomes an immediate, audited, named-human-visible fact.

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

Agents deploy into **shadow** by default. Promotion to live is gated:

1. Someone requests promotion (recorded, with a summary of the shadow window — how long, how many sightings, latest adherence).
2. A **compliance review** must be recorded and decided **approved** by a named reviewer with a written rationale, *after* the request. Without one, the promote call is refused with `PROMOTION_REVIEW_REQUIRED`.
3. The promote consumes that review — single use, recorded in the event log with the review's identity.

A rejected review is informational, never error-colored, and promotion can be re-requested. Deploying an agent straight to live is possible but requires an explicit recorded justification.

### Throttle budgets

Throttles set operating budgets on an agent without pausing it. Four budget kinds exist: `ACTIONS_PER_WINDOW`, `TOKENS_PER_DAY`, `SCHEDULE_WINDOW`, `SAMPLING_PCT`. Honesty note: **only `ACTIONS_PER_WINDOW` is actually enforced at the gateway today** (over the limit, the agent's governed calls are denied — an `AGENT_GATE_DENIAL` whose reason is `AGENT_BUDGET_EXHAUSTED` — and a signal fires at exhaustion). The other three kinds are recorded as published obligations — the agent runtime can read them via its self-inspection tools, and they appear in the audit trail — but no gateway evaluator checks them yet. The throttle response's enforcement block discloses this the same way pause does.

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

### Operator reference: agent endpoints

All under `/api/v1`, returning a `{data, meta, errors}` envelope. `{idOrKey}` accepts the registration UUID or the agent key (e.g. `a-msg-router`). Every write body requires an `idempotencyKey`; replays return the original result without duplicating events. An optional `expectedEventSeq` rejects stale writes (409 with the current state in the response).

| Read | Returns |
|---|---|
| `GET /agents?domainId=&status=&includeRetired=` | Fleet rows. |
| `GET /agents/{idOrKey}` | Full detail: mandate, gates, oversight, control effects, enforcement state, liveness, budgets, interventions, promotion state, recent events. |
| `GET /agents/{idOrKey}/events` | The full attributed audit timeline. |
| `GET /agents/{idOrKey}/sightings` · `/gates` · `/metrics` | Liveness, gate standings, metric samples. |
| `GET /agents/signals` · `/attention` · `/feed` · `/notifications` | Aggregates described above. |

| Write | Notes |
|---|---|
| `POST /agents` | Deploy. Missing provenance fields are rejected with named codes (e.g. `AGENT_PROVENANCE_MISSING_OVERSIGHT`). |
| `POST /agents/{idOrKey}/pause` · `/kill` · `/retire` | `{reason!}` — 200 + enforcement disclosure. |
| `POST /agents/{idOrKey}/resume` | Returns to pre-pause mode. |
| `POST /agents/{idOrKey}/throttle` · `DELETE .../throttle/{kind}` | Set / clear a budget. |
| `POST /agents/{idOrKey}/promote/request` · `/promote` | Promotion flow; promote 409s without a fresh approved review. |
| `POST /agents/{idOrKey}/reviews` · `POST /agents/reviews/{id}/decide` | Record and decide compliance reviews. |
| `POST /agents/{idOrKey}/interventions` · `POST /agents/interventions/{id}/acknowledge` · `/resolve` | Intervention lifecycle. |
| `POST /agents/{idOrKey}/oversight` | Replace the oversight chain (before/after recorded). |
| `POST /agents/{idOrKey}/attest-liveness` · `/confirm-control` | Human attestation and control confirmation. |
| `POST /agents/{idOrKey}/principal` · `DELETE .../principal` | Bind/revoke the agent's gateway identity. |

Agent runtimes themselves get a small, deliberately limited tool set on substrate's MCP server: read their own registration and control state (`get_my_registration`, `get_my_control_state`), report telemetry (`agent_heartbeat`, `report_agent_activity`), and acknowledge a control order (`acknowledge_control` — the one write a suspended agent retains). **There are no agent-callable lifecycle tools.** Deploy, promote, pause, kill, and retire are human-only acts, enforced at the service layer and again at the gateway.

### Current limits (agents)

- **No control UI.** Pause/kill/throttle/promote are REST-only. The Operations tab shows the fleet, statuses, provenance, and mandates, but has no action buttons yet.
- **Enforcement depends on posture.** Until the enforcement ladder is in `enforce` mode, even gateway-resident agents report `recorded-intent` with an explicit disclosure — read the disclosure, not your assumptions.
- Oversight chain entries are labels (optionally linked to members); substrate does not validate them against an org chart.
- Adherence is just the latest reported `adherence` metric — if nothing has been reported, it renders as a dash.

---

## Diagnostics and findings

The diagnostic engine is how substrate turns a client's raw documents into reviewable, evidence-backed findings about their operating environment — and how humans, not the AI, decide what becomes part of the engagement record.

### The pipeline in plain words

1. **Ingest documents.** The ingestion CLI takes customer source files (CSV, JSON, text) through `convert → stage → commit`. Conversion extracts entities; staging writes human-reviewable JSON you inspect before anything is committed; commit creates or updates entities in the canonical model (systems, processes, canonical entities) for a named domain. Every source file is registered with its SHA-256 hash, every commit writes an audit run, and every extracted entity carries evidence rows pointing back to its source file. Re-running commit is idempotent — already-committed files are hash-skipped.
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
- **`groundingMode: EXISTENCE_ONLY`** — this is the most important phrase to understand when reading AI findings. It means the machine has verified that the cited evidence **exists** and traces to a registered source document. It has **not** verified that the evidence semantically supports the claim. That judgment is the human's, and the accept action is its durable record. The product copy rule is binding: evidence citations are *existence-verified*; semantic support is *human-verified at acceptance*. Nothing in substrate ever claims "machine-verified grounding."

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

Honesty note for the current release: **curation is API-only.** There are no accept/reject buttons in the web UI yet, and the demo subsidiary dashboards still render illustrative fixture findings on their Operations tab (the demo banner says so: "live engine data + illustrative fixtures"). The live findings list rides `GET /api/v1/ecosystem/findings?domainId=` with filters `curationStatus`, `aiGenerated`, `includeRejected`.

### Diagnostic runs and their outcomes

Start a run with `POST /api/v1/diagnostics/runs` — body `{domainId!, idempotencyKey!, scopes?, batchSize?, requiredMinFindings?, dryRun?}` (`dryRun: true` returns the batch plan without executing). One run at a time per domain; a second start is refused with `DIAGNOSTIC_RUN_ACTIVE`. Track it with `GET /diagnostics/runs/{id}` — status, per-batch ledger, candidate/filtered/committed counts, a histogram of *why* candidates were filtered, and token/cost totals. `POST .../resume` re-executes failed or interrupted batches without ever duplicating findings (an interrupted batch committed nothing, so re-running it is safe); `POST .../cancel` stops a run cleanly and is never error-colored.

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

- In the **Capability Designer**, the "AI drafts" dialog proposes capability drafts grounded in the ingested document universe, showing each draft's proposed fields and the evidence behind it. When nothing comes back, the dialog shows the explanation (empty universe, all candidates filtered) rather than a blank panel. **In the current UI this dialog is a read-only preview — there is no accept button in it yet.**
- The accept/reject acts exist as API verbs: `POST /api/v1/ai/generative/commit` (`{assertionId!, overrides?, idempotencyKey!}`) materializes a draft as a Capability in `DRAFT` status, marked AI-generated. Grounding is **re-verified at the moment of commit** — if the cited evidence has since vanished, the commit is refused (`DRAFT_GROUNDING_LOST`) rather than silently materialized. Any human edits applied at acceptance are themselves recorded as part of the audit event. `POST /api/v1/ai/generative/reject` requires a rationale, creates no entity, and returns 200. Per-item only, same as findings.

### Running without an AI key

The platform boots and runs without an `ANTHROPIC_API_KEY`. What changes:

- Ingestion's deterministic converters (e.g. manual-process CSVs) work regardless; LLM-assisted extraction needs the key or the explicit mock provider (`--substrate.llm.provider=mock`), which exercises the full pipeline mechanics deterministically.
- Diagnostic runs, chat, and generative drafts need a real key for real analysis. With the mock provider they run end-to-end with scripted output (useful for rehearsal and verification); with neither key nor mock they fail loudly at call time, never silently.
- The demo workspace's findings and dashboards do not depend on the key at all.

### Operator runbook: a real diagnostic, end to end

The proven sequence (the acceptance record with full run JSON is preserved at `../acceptance/diagnostic-glacier-2026-06-11.json`, run against the checked-in sample corpus):

```powershell
cd substrate
# 1–2. Ingest the corpus into a domain (mock provider shown; drop the override with a real key)
.\gradlew.bat :ingestion:bootRun --args="convert-and-stage --input samples --substrate.llm.provider=mock"
.\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"

# 3. Start a run against the running app (or use the CLI runner with --substrate.diagnostic.run=true)
#    POST /api/v1/diagnostics/runs  {"domainId":"…","requiredMinFindings":1,"idempotencyKey":"run-1"}
# 4. Poll GET /api/v1/diagnostics/runs/{id} until terminal; read the batch ledger and filter histogram
# 5. Review: GET /api/v1/ecosystem/findings?domainId=…&curationStatus=PENDING_REVIEW
#    and GET /api/v1/findings/{id}/provenance for each before deciding
# 6. Curate: POST /api/v1/findings/{id}/accept | /reject  (reject needs a rationale)
# 7. Integrity sweep: GET /api/v1/diagnostics/verify?domainId=…  → expect brokenChains: []
```

In the demo workspace as shipped, the seeded findings are operator-authored illustrations (no AI findings exist until you ingest a corpus and run a diagnostic — verified live: the demo domain's verify sweep reports zero AI findings, honestly, with explicit zeros).

### Current limits (diagnostics)

- **No curation UI** — accept/reject and the generative commit are API verbs only; the AI-drafts dialog is preview-only.
- **Evidence is document-level**, not passage-level: the chain proves which file supports a finding, not which paragraph.
- Grounding is existence-only by design (see above); semantic verification is the human's act at acceptance.
- Demo dashboards mix live engine data (agents, signals) with illustrative fixture findings; the banner discloses this.

### Further reading

- Engine vocabulary and full state machines: `substrate/docs/engine-spec/agents.md`, `substrate/docs/engine-spec/diagnostic.md`
- Runnable setup and ingestion reference: `substrate/docs/dev-setup.md`
- Proven acceptance evidence: `substrate/docs/acceptance/diagnostic-glacier-2026-06-11.json`, `substrate/docs/acceptance/final/`
