# ADR-002 — Coordination Contract Engine (`engine.contract`): MCP enforcement model

**Status:** Accepted (autonomous, revisable) — Mike review pending
**Date:** 2026-06-09
**Resolves:** `orchestrator/docs/plan/substrate-production-plan.md` §10 open decision #3 ("MCP enforcement model: runtime check vs static analyzer vs wrapper service vs hybrid")
**Companion spec:** `substrate/docs/engine-spec/contracts.md` (implementation-grade detail)
**Binding inputs:** `substrate/docs/port-map.md` §6 `[CONTRACT-CONTENT]`, `[CONTRACT-ENFORCEMENT]`, `[CONTRACT-COMMITMENT-STATES]`, §2.7, §4.5, §5 (S5/S6), §7 (D2); `substrate/docs/orchestrator-substrate-contract.md`; homepoc `frontend/src/demo/beacon/contracts.ts` + `phases/specs/06-governance-3.md`; redesign P19.

Every decision below was made autonomously under Mike's standing authorization (decide, document as revisable ADRs with explicit revisit triggers, do not block). Each subsection states alternatives considered and a **REVISIT TRIGGER**. None of these decisions are sacred; all are reversible before the first customer engagement (per-engagement fresh databases, plan §8, mean schema revisions pre-first-customer are cheap).

---

## Context

The substrate plan (§5) requires a net-new "Coordination contracts" module: *"MCP server that holds the contract, blocks violating agent actions, surfaces commitments as inspectable state."* Homepoc shipped UI only — two fixture contracts (`co-tribal-knowledge-depopulation`, `fi-shared-pre-fill-agent`) rendered by `ContractDetailView` with deliberately inert Review/Decline buttons (queue #28).

Binding constraints:

- **P19:** participating systems/agents join the substrate by exposing/speaking MCP; adherence is observable, non-adherence is a signal; "agents cannot route around the contract — the contract is the substrate."
- **One-directional dependency:** substrate depends on orchestrator, never the reverse. Orchestrator must never carry substrate-aware hooks. Agents are advisors; their output becomes state only when substrate applies an accepted dispatch result through its own write path.
- **Degraded mode:** substrate without orchestrator must keep enforcement functional (write checks + scheduled monitors + human attestations).
- **Decline is a legitimate first-class terminal resolution, never an error** (port-map trap #8).
- **Read-model-first sequencing** (port-map §6 engine sequencing rule).
- **Flag-not-invent:** where homepoc semantics are UNKNOWN (review-boundary timing, contract completion, Review/Decline UX, decline→cascade propagation), model explicit extension points; do not encode invented invariants.

Three candidate designs were produced (runtime-interceptor purist; monitor+gate hybrid; contract-as-data predicate engine), judged, and synthesized. The runtime-interceptor design won on domain fidelity and operability; ten specific grafts from the other two designs are folded in below (marked *graft*).

---

## Decision

### D1 — Enforcement model: a single in-process runtime Policy Decision Point (`ContractGateway`)

One substrate-owned PDP, `com.meridian.substrate.engine.contract.gateway.ContractGateway`, evaluated **synchronously, in the same DB transaction, before any governed state mutation commits**. A DENY throws → transaction rolls back → enveloped 409 with code `CONTRACT_DENIAL` citing contract key + clause ids. A denial is a governance outcome, never a 5xx.

**Alternatives considered:**
- *Static analyzer* — rejected: agent behavior is dynamic; there is nothing meaningful to analyze pre-execution; would not make the "cannot route around" claim true.
- *Standalone wrapper/proxy service* — rejected: extra deploy unit violating "whole or in part" simplicity; unnecessary because substrate is already the single authoritative write path; adds a network hop to every write.
- *Monitor-only (no synchronous gate)* — rejected as the whole model, adopted as a component (D10): monitors detect violations that already happened in the world; the gate prevents violations of substrate state. Both are needed; neither substitutes for the other.
- *Hybrid with orchestrator-side hooks* — rejected: violates the one-directional dependency (see D2).

The strongest product guarantee — "agents cannot route around the contract" — is made literally true *for substrate-held state* by construction: substrate grants no write surface that bypasses the gateway (with one explicit, documented v1 exemption — D3).

**Honest scope of the claim:** actions inside customer systems that never transit substrate are *observed* (monitors, attestations, probes), not intercepted. Product copy must say "adherence is observable; non-adherence is a signal" (P19) and "violating substrate writes are blocked," never "all violations are blocked."

**REVISIT TRIGGER:** PDP p99 > 50ms on a governed hot path; or a customer engagement requires enforcing actions that never transit substrate.

### D2 — Policy Enforcement Points: all three are substrate-side; the orchestrator is never a PEP

Exactly three PEPs, all inside substrate:

1. Every substrate **MCP governed tool call** from agent/party principals (`substrate-contracts` server, D8).
2. Every **mutating REST service command**, made declarative via a `@Governed(actionType=…)` AOP annotation on service methods — with an **ArchUnit architecture test asserting 100% coverage** of mutating methods in governed packages (escapes require an explicit `@Ungoverned(reason=…)` annotation so exemptions are auditable). "No route around" is CI-proven, not hoped.
3. The **dispatch-result apply path** — when substrate accepts an orchestrator `get_dispatch_result` and applies it, that apply is itself a `GovernedAction` (`actionType=apply_dispatch_result`, principal type SERVICE).

Agent tool calls inside the orchestrator are **never** intercepted: that would invert the one-directional dependency. This also makes degraded mode identical-by-construction (D12).

**Alternatives considered:** orchestrator-side dispatch-loop interception (rejected — dependency inversion; escalate to Mike if anyone proposes it); explicit `gateway.check(...)` calls in each service without AOP (rejected — drifts silently as services grow; the monitor+gate candidate design had exactly this weakness).

**REVISIT TRIGGER:** orchestrator ever grows a substrate-aware hook (violates the seam doc — escalate, don't adopt); or the governed-package list stops being maintainable by ArchUnit rule.

### D3 — Ingestion bulk-commit path is exempt from the gateway in v1 (*graft*, declared not discovered)

The `ingestion` module's bulk commit path writes canonical entities without transiting app/engine services (module rule: ingestion depends only on common). v1 position: ingestion is **operator-curated, pre-activation engagement data**; it is declared exempt, with the exemption documented in code (`@UngovernedModule` marker + package-level comment) and in this ADR. ArchUnit coverage proofs (D2) cover app+engine packages only — this exemption is the known, named gap.

**Alternatives considered:** routing ingestion commits through the gateway (rejected for v1 — guards target operational actions, not bulk historical load; the coupling adds risk with no fixture-implied value); blocking ingestion while any contract is ACTIVE (rejected — invented invariant).

**REVISIT TRIGGER:** Phase 4 service-mode ingest (REST upload + async ingest job) lands — at that point ingest becomes an operational write path on a possibly-ACTIVE engagement and MUST be re-evaluated for gateway coverage before ship.

### D4 — Enforcement mode ladder: `substrate.contracts.enforcement.mode = off | shadow | enforce`

Ship phase 3c in `shadow` (would-be denials logged as `WOULD_DENY`, nothing blocked); flip to `enforce` per engagement after a clean shadow window. This is the coupling-risk answer that does not weaken the chokepoint.

**Alternatives considered:** enforce-at-day-one (rejected — a gateway defect could block operations before confidence exists); per-contract mode (deferred — per-engagement is the v1 grain).

**REVISIT TRIGGER:** first engagement demanding enforce-at-day-one; or shadow telemetry shows the ladder is ceremony (zero WOULD_DENY over a full engagement).

### D5 — Fail posture: fail-closed for AGENT/SERVICE principals, fail-open + `ALLOW_WITH_FLAG` for HUMAN principals

If gateway evaluation itself fails (exception, timeout), agent and service actions are denied; human actions proceed with a flagged decision row. Humans are never locked out of their system of record by an engine fault.

**Alternatives considered:** uniform fail-open (rejected — guts the agent-facing guarantee); uniform fail-closed (rejected — an engine bug bricks the operator surface); per-guard fail posture (rejected for v1 — config surface without a driving requirement).

**REVISIT TRIGGER:** Phase 5 OIDC lands with break-glass roles; or a regulator requires human fail-closed.

### D6 — Decline: governed, first-class, legitimate, terminal; review-boundary enforced only when configured; triggers can be predicate-armed

- `invoke_decline(contract, decline_trigger, reasoning)` is a governed action that validates the cited pre-agreed `contract_decline_triggers` row, requires reasoning, terminates the contract as `DECLINED` (terminal, non-error, HTTP 200, subtle-destructive UI accent), atomically suspends monitors and deactivates guards, and emits `ContractDeclinedEvent`.
- Declining is **never** logged as a violation or breach. Decline ≠ breach, structurally.
- For `federation-invitation` contracts, the quarterly review-boundary check runs **only if** `review_boundary` config is non-null. Boundary semantics appear in no homepoc doc — when unconfigured, the gateway ALLOWs and logs `ALLOW_WITH_FLAG` rather than inventing a calendar rule (flag-not-invent at runtime).
- *Graft (predicate-armed triggers):* when a decline trigger's condition is machine-detectable within the v1 guard DSL (D9), the scheduler evaluates it and arms a `DECLINE_ELIGIBLE` signal (subtle-destructive accent, never a breach). Pure human-judgment triggers (the majority of fixture triggers) remain always invocable. Notably, the fixture trigger "cost-share imbalance > 30% sustained over two quarters" requires temporal `sustainedFor` semantics that the v1 DSL deliberately does not have — it ships predicate-null (human judgment), and is the canonical example of the D9 revisit trigger.
- Decline never auto-creates cascades. `[CONTRACT-COMMITMENT-STATES]` says decline "connects to the cascade Decline primitive" — that connection is the published `ContractDeclinedEvent` only; cascade consumption is gated on the cascade-authoring session (queue #24 / plan open decision #2). Reviewers must reject any auto-cascade wiring.
- An open breach does not block `invoke_decline` (gateway ALLOWs + `ALLOW_WITH_FLAG`): whether a party may decline to escape remediation is a product question for Mike's session, not an invariant to invent. Open breaches survive decline and run their human lifecycle to terminal.

**Alternatives considered:** a DeclineInvocation PENDING_REVIEW/counterparty-accept flow (rejected — invents flow semantics inside the explicitly Mike-gated Review/Decline area, queue #28); blocking decline while breaches are open (rejected — invented invariant); auto-resolving open breaches as "superseded by decline" (rejected — invented terminal state).

**REVISIT TRIGGER:** Mike's Review/Decline product-design session (queue #28); the decline-propagation decision (queue #24 / plan open decision #2); first engagement defining real review-boundary semantics.

### D7 — Contract lifecycle: only fixture-implied states are reachable; content freezes at the FIRST party response

`ContractStatus`: `PROPOSED → ACTIVE` (all required parties accepted), `PROPOSED → DECLINED` (any required party declines), `ACTIVE → DECLINED` (via `invoke_decline`). `WITHDRAWN` and `CONCLUDED` exist as **schema-reserved, transition-unreachable** enum values — completion/withdrawal semantics appear in no fixture or doc (flag-not-invent).

*Graft (freeze-on-first-response):* contract content (parties, commitments, decline triggers, estimatedValue, enforcement text) is frozen once the **first** `contract_party_responses` row is recorded — not merely on leaving PROPOSED. Otherwise `PATCH`-while-PROPOSED could shift text under a party that already accepted. `PATCH /contracts/{id}` is legal only while status=PROPOSED **and** zero responses exist.

**Alternatives considered:** a DRAFT state before PROPOSED (rejected — no fixture implies it; the freeze invariant is a one-line fix that buys the same safety); reachable COMPLETED/SUPERSEDED/EXPIRED states and amendment versioning (rejected — invented semantics; the contract-as-data candidate's own risk list admitted this).

**REVISIT TRIGGER:** first contract in a real engagement reaching natural end (forces CONCLUDED semantics); operator demand for pre-proposal drafting (forces DRAFT).

### D8 — Substrate hosts the MCP server (`substrate-contracts`); authoring stays REST-only

The MCP server is substrate-side (engine logic, app-module transport at `/mcp`, Streamable HTTP, official MCP Java SDK). It is BOTH the inspectable-state surface (read tools, `check_action` advisory preflight) and the governed agent write surface (attest, report metrics, respond, decline, breach verbs). Because it is substrate-internal, it runs identically without orchestrator. Contract **authoring** is REST/operator-only in v1 — agents cannot author contracts.

*Graft (contract identity):* contracts carry a stable slug `contract_key`, unique per domain (e.g. `co-tribal-knowledge-depopulation`). The fixture ids are slugs and the fixture `monitorMechanism` text literally binds to them ("MCP coordination contract id co-tribal-knowledge-depopulation"); UUID-only identity would break fixture deep-link parity and agent tool ergonomics. MCP tools and UI deep links address contracts by `contract_key`; UUIDs remain the PK/FK currency.

**Alternatives considered:** agents author contracts over MCP (rejected — deliberately limited agent powers in v1); UUID-only identity (rejected per graft rationale).

**REVISIT TRIGGER:** Phase 5 OIDC (client-credential principals replace localhost trust); cross-engagement federation requiring `contract_key` namespacing.

### D9 — Guards are first-class columns with a deliberately tiny predicate DSL

`contract_clause_guards` puts the PDP hot path on indexed first-class columns (`action_type`, `entity_type`, `effect`, `active`) — never JSON predicates in the match path — so the §4.5 JSONB deferral holds and evaluation stays at 1–2 indexed reads + in-memory predicate eval (target p50 < 5 ms, cached per domain). The optional `predicate` JSON supports **field equality and numeric threshold matchers only**. Richer logic (temporal windows, sustained-over-N-periods, cross-entity joins) is an SPI extension point, not invented v1 surface.

**Alternatives considered:** a maximal typed predicate AST with selector resolvers (rejected — classic v1 over-build; highest implementation risk of the three candidates; outpaces any authoring UI, so operators would fall back to text-only and enforcement would silently degrade); free-form SpEL/scripting (rejected — unauditable, injection surface).

**REVISIT TRIGGER:** first contract needing a predicate the DSL can't express — this is simultaneously the named JSONB-flip condition in port-map §4.5. (The fi-shared-pre-fill-agent "sustained over two quarters" decline trigger is already a known instance; it ships as human-judgment.)

### D10 — Monitors: verbatim prose + structured rule sibling; UNSTRUCTURED degradation; INDETERMINATE never breaches

- `monitor_mechanism` prose is stored **verbatim** TEXT and rendered unchanged by the existing UI mono panel; a structured `rule_spec` sibling is authored alongside and is what executes. Display never silently drifts from prose, and prose never pretends to execute.
- Both fixture mechanisms compile cleanly: co-tribal → `ATTESTATION_CADENCE` (MONTHLY, graceDays=7, surface to Operating Partner dashboard); fi-shared → `METRIC_THRESHOLD` (utilization imbalance > 30%, QUARTERLY).
- *Graft (UNSTRUCTURED):* mechanism prose that doesn't compile to a structured rule becomes `rule_type=UNSTRUCTURED`, which flags a recurring human-verify signal on its cadence — a monitor can be weak but never silently inert (no-silent-zero-yield). The structured-vs-unstructured monitor-coverage ratio is surfaced on the operator signals feed so degradation is visible.
- *Graft (INDETERMINATE/NOT_DUE):* monitor-run outcomes are `COMPLIANT | BREACH_DETECTED | INDETERMINATE | NOT_DUE | ERROR`. **Hard invariant: INDETERMINATE (missing party reports, unregistered feed, unreachable party endpoint, unknown rule version) NEVER auto-opens a breach** — it surfaces as a distinct needs-input signal class (`ADHERENCE_STALE`, `ADHERENCE_UNOBSERVABLE`, `HUMAN_VERIFY_DUE`) that the dashboard must render distinctly from compliant. This is flag-not-invent applied at runtime.
- *Graft (evaluation trace):* each run persists a bounded trace (rule, inputs resolved, counts/values) in `contract_monitor_runs.details`, capped at `substrate.contracts.trace.max-bytes` (default 16 KiB), for audit defensibility.

**Alternatives considered:** generating the mono-panel text from the compiled rule (rejected — sacrifices verbatim fixture parity; the contract-as-data candidate's own risks flagged this as its fixture-parity trap); treating un-compilable prose as a hard authoring error (rejected — human-cadence obligations are legitimate attestation-only content).

**REVISIT TRIGGER:** orchestrator-assisted authoring lands (LLM-compile prose→rule with human confirm); first real telemetry feed replaces attestation-reported metrics.

### D11 — Metrics are push-based in v1; MCP probing is liveness-only (*graft*)

`METRIC_THRESHOLD` evaluators read from `contract_metric_samples` populated by the `report_metric` MCP tool (and REST sibling). Evaluators **require complete period data from all required parties** and emit `ADHERENCE_STALE` (INDETERMINATE) rather than computing imbalance on partial reports. Substrate's outward `MCP_PROBE` (reusing `ReaderRegistration kind=MCP_CLIENT` as the registered party-side endpoint, P19 outward direction) is retained **only** for adherence/liveness pings → `ADHERENCE_UNOBSERVABLE` signal; it is not the metric path, so the existing ProbeService SSRF surface is not enlarged.

**Alternatives considered:** substrate polls party systems for metrics (rejected — enlarges SSRF surface, invents integration contracts that don't exist yet); computing on partial data with a confidence note (rejected — misleading imbalance numbers are worse than a stale flag).

**REVISIT TRIGGER:** the reader/connector framework maturing in Phase 4+; first federated onboarding defining a richer party handshake.

### D12 — Degraded mode is the base case, unchanged by construction

Because all PEPs are substrate-internal, enforcement without orchestrator is **identical**: same gateway on REST + MCP writes, same scheduled monitors, same attestation surface (`attest_commitment`), same breach lifecycle. Only orchestrator-backed affordances disappear (AI clause drafting, optional auto-remediation `submit_task`), gated by `substrate.orchestrator.enabled` exactly like every other engine.

**REVISIT TRIGGER:** none — this is a consequence of D2, revisited only if D2 is.

### D13 — Idempotency keys are explicit (*graft*)

- Attestations: `UNIQUE(commitment_id, period_key)` — re-submission over MCP/REST is an idempotent upsert; every submission still appends a `contract_events` row, so history is never lost.
- Breaches: at most one open breach per `(monitor_id, commitment_id, period_key)`; re-detection refreshes `last_confirmed_at` instead of opening a duplicate. Enforced in the service under row lock, backstopped by a Postgres partial unique index in the vendor migration dir (H2 dev cannot express partial indexes — service-enforced there, consistent with the branding-active precedent).
- Party responses: `UNIQUE(party_id)` per contract; duplicate identical response is an idempotent 200; conflicting response is a 409.

**REVISIT TRIGGER:** evidence of period-key ambiguity across timezones in a real engagement (v1 computes period keys in a configured engagement zone, default UTC).

### D14 — Contract accountability events are mirrored into the engine.decision hash chain, bounded (*graft*)

Contract activation (final accept), decline (both PROPOSED→DECLINED and ACTIVE→DECLINED), and breach **waivers** are mirrored as entries into the existing SHA-256 hash chain (`engine.decision`), giving contracts the same tamper-evidence as design decisions. Mirroring is bounded to these lifecycle/waiver events to avoid per-domain append-lock contention; chain inputs reference immutable `contract_key`/clause ids, never display titles (`[DECISIONLOG-COVERAGE]`). A contract-transition hash-vector test is pinned **before** enabling (port-map trap #3).

**Alternatives considered:** mirroring every contract event (rejected — chain volume/contention); no mirroring (rejected — waiver is exactly the action a regulated buyer wants tamper-evident).

**REVISIT TRIGGER:** chain contention under load; external attestation hook design (plan §5 decision-log delta).

### D15 — `contract_type` is a validated string with a configurable allowlist

Seeded `{cross-portfolio-opportunity, federation-invitation}` per `[CONTRACT-CONTENT]` "extensible"; matches the port-map free-text-status precedent. A closed enum would force a migration per new type.

**REVISIT TRIGGER:** type-specific behavior beyond the federation decline-boundary check.

### D16 — Waiver is human-only via a built-in, non-removable guard

Agents may attest, acknowledge, and propose remediation; they may never waive a breach. Waiver and remediation both leave append-only events with actor identity; waiver requires rationale.

**REVISIT TRIGGER:** Phase 5 role model lands (waiver becomes role-gated rather than principal-type-gated).

### D17 — Decision log is a separate high-volume table with a matched-only default

`contract_enforcement_decisions` is kept separate from `contract_events` (different volume/retention profile). Volume policy: `substrate.contracts.decision-log.level = matched-only (default) | all` — decisions where no guard matched are counted in metrics, not rowed; `ALLOW_WITH_FLAG`, `DENY`, `WOULD_DENY`, and ALLOWs that matched ≥1 guard are always rowed. DENY decision rows persist even though the business transaction rolls back (REQUIRES_NEW write). No retention policy exists yet — flagged.

**REVISIT TRIGGER:** retention/rollup decision required before any high-traffic agent engagement (named risk).

### D18 — Delivery is strictly read-model-first: 3a → 3b → 3c

- **3a** — entities + V5 migration + read API + MCP read tools + `ContractDetailView` fixture swap at the documented `BEACON_PORTFOLIO` seam; DemoPack seeds the two fixture contracts. Review/Decline affordances stay inert (queue #28) even though endpoints exist.
- **3b** — monitors + attestations + metric samples + breach lifecycle (**detect, don't block**) + signals feed.
- **3c** — gateway PEPs + governed write tools, shipped `shadow`, flipped to `enforce` per engagement (D4).

Acceptance per phase is user-runnable (curl/MCP-client invocations against seeded contracts; monitor-lapse demo via injectable clock) — no report-only completions. Testcontainers-Postgres in CI is the gate; H2-green is never acceptance (this dev machine has no Docker).

**REVISIT TRIGGER:** none — sequencing is binding; only the 3b/3c split point may flex.

---

## Consequences

**Positive**
- Plan open decision #3 is resolved decisively: one PDP, three substrate-side PEPs, CI-proven coverage. The "cannot route around" claim is true by construction for substrate state, with its one exemption (ingestion, v1) named in code and here.
- The one-directional orchestrator dependency is preserved untouched; degraded mode is identical-by-construction, keeping substrate sellable standalone.
- Decline is structurally legitimate everywhere (terminal status, 200 responses, subtle-destructive rendering, never breach-colored), satisfying the binding constraint at the schema, API, and gateway layers simultaneously.
- Fixture parity is exact: verbatim `monitorMechanism` prose, slug identity, supersetting `BeaconContract` DTO — the frontend swap is an adapter, not a redesign.
- Every UNKNOWN semantic is an explicit extension point with an owner trigger (Mike sessions #24/#28, first natural completion, first real telemetry), not an invented invariant.

**Negative / accepted costs**
- Coupling: every governed write path takes a gateway dependency; a gateway defect can block operations. Priced and mitigated (shadow ladder D4, human fail-open D5, indexed+cached hot path D9) — residual risk is the highest of the candidate models and is accepted deliberately.
- Enforcement-value asymmetry in v1: both fixture contracts compile to monitors/attestations and **zero blocking guards** — the synchronous gateway adds little until an agent population (Phase 4 / D3-agent-registry) generates blockable actions. Demos should lead with `check_action` + a guard-bearing federation contract scenario.
- Identity is weak until Phase 5: persona headers and MCP principals are spoofable (S9-equivalent); decision rows record the claimed principal verbatim. Must be stated in any compliance conversation before OIDC lands.
- `@Governed` retrofit across Phase-1 mutating services is a wide mechanical blast radius (made safe by shadow default; made honest by the ArchUnit gate running in CI from 3c day one).
- 14 tables land before Mike's Review/Decline session; if that session reshapes party responses or trigger invocation, V5 amendments ripple (mitigated: per-engagement fresh DBs allow pre-first-customer migration edits; flag at slice-close).

**Follow-ups owed to Mike (do not block, surface at next review)**
1. Review/Decline product flows (queue #28) — endpoints exist, UX undesigned, affordances inert.
2. Decline→cascade propagation (queue #24 / plan open decision #2) — event-only seam shipped.
3. Decline-vs-open-breach interaction (escape-hatch question) — currently ALLOW_WITH_FLAG.
4. Enforcement-claim wording for sales copy (D1 honest-scope paragraph).
5. Decision-log retention policy (D17).
6. Review-boundary semantics for federation contracts (D6) — unenforced when unconfigured.
