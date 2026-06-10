# ADR-001 — Cascading Evaluation Engine (`engine.cascade`)

**Status:** Accepted (autonomous, revisable) — Mike review pending
**Date:** 2026-06-09
**Deciders:** Synthesizer agent under Mike's autonomy grant ("decide, document as revisable ADRs with explicit revisit-triggers, do not block"); design produced by parallel-design + judge + graft synthesis.
**Companion spec:** `substrate/docs/engine-spec/cascade.md` (implementation-grade detail; this ADR records *why*, the spec records *what exactly*).

---

## 1. Context

Phase 2 of the substrate production plan (`orchestrator/docs/plan/substrate-production-plan.md` §9) requires a net-new cascading evaluation engine. Homepoc shipped **UI only** — three TypeScript fixture cascades (`meridian-homepoc/frontend/src/demo/beacon/cascades.ts`) rendered by `CascadeDetailView` and the Operating Partner "Cascades you've issued" grid. There is no Java entity, no table, no state machine.

Binding constraints (from `substrate/docs/port-map.md` §6, §2.7, and the contract doc):

- **[CASCADE-LIFECYCLE]** aggregate states exactly `{in-flight, resolved-looks-good, resolved-declined}`; a documented Decline is a LEGITIMATE terminal resolution, never an error/failure state.
- **[CASCADE-LEVELS]** per-level states exactly `{looking, absorbed, cascaded, declined}`; state-conditional payloads (refinedCritique for cascaded/absorbed, declineReasoning for declined); every level action timestamped with an owner. ⚠ `looking` and `absorbed` are never fixture-exercised — semantics inferred from naming + one spec sentence; **flag, don't invent**.
- **[CASCADE-DURABILITY]** L0 originalCritique + acceptanceCriteria immutable and visible at every level; the level chain IS the audit trail, "authored as the work happens, not retrofitted" → append-only level records.
- **[CASCADE-PROPAGATION]** arbitrary depth N, linear chain (not a tree) in v1, exactly one active bottom-of-chain; dashboard signal derives from the bottom level (declined → "Decline pending" destructive/leader-attention per resolved queue #21; cascaded → "Escalating" info); decline-pending cascades render beside in-intervention agents.
- **[CASCADE-VISIBILITY]** single-throat-to-choke: the originator inspects every level's decision/translation/timing without chain-of-command permission. Drill-to-L[N] semantics undefined (queue #23) — do not invent.
- **[CASCADE-AUTHORING-GAP]** the write/authoring UX is undesigned (queue #24, blocks #22 and #23); read model + state machine first; leader authoring UX gated on a working session with Mike.
- **Read-model-first sequencing** (port-map engine sequencing rule, chunk D1).
- **Whole-or-in-part deploy**; Postgres via JPA in the engine module; **fully functional degraded** with `substrate.orchestrator.enabled=false` (contract doc: substrate→orchestrator MCP is one-directional; agents are advisors, never mutators).
- Open decisions from plan §10 #2: Decline propagation to parallel cascades? Auto-escalation timeout? Authoring shape?

Two competing designs were produced (an event-sourced hash-chained core vs. a governance-first JPA aggregate with an append-only action log). The judge selected the governance-first design as winner on implementability (lands on the Phase-1-ported Spring/JPA base in one focused build using existing codebase idioms) and on product completeness (it gives the originator and intermediate levels real verbs after a decline), with seven grafts from the event-sourced design. This ADR records the merged result.

---

## 2. Decision — architecture in one paragraph

A conventional Spring/JPA engine in `engine/src/main/java/com/meridian/substrate/engine/cascade`, entities in `common.domain`, repositories in `common.repo`, controllers in `app.api.v1`, migration `V4__cascades.sql` (+ `postgresql/V4_1` trigger). Truth for everything that *happened* lives in an **append-only `cascade_level_actions` table**; `cascades` (aggregate) and `cascade_levels` (one row per depth) are cheap projections kept consistent in the same transaction and **deterministically rebuildable** from the action log. Decline is a first-class 200-response transition with immediate originator visibility, hop-by-hop response verbs for the level above (endorse-decline / recascade / absorb), and an originator short-circuit accept. Escalation timeouts never transition state — they emit idempotent overdue notifications. Notifications are a **transactional outbox** written in the same transaction as the action. Authoring persists in a separate mutable `cascade_drafts` table supporting free-form / imperative / AI-suggested shapes; AI-suggest is the engine's only orchestrator touchpoint, feature-gated. Read DTOs speak the fixture vocabulary (kebab-case) so the Beacon UI swap is confined to the `BEACON_PORTFOLIO` factory seam.

Each autonomously-decided open point follows, with alternatives and revisit triggers.

---

## 3. Decisions

### D1. Core shape: JPA aggregate + append-only action log + rebuildable projections (not full event sourcing)

**Decision.** `cascade_level_actions` is append-only and is the audit-trail source of truth for everything mutable; `cascades.current_state`, `chain_seq`, frontier fields, and `cascade_levels` rows are projections updated synchronously in the same transaction. A `CascadeProjectionRebuilder` (graft from the event-sourced design) replays the action log to re-derive every projection field deterministically — restore-after-restart safety, and the lever by which later-ratified `looking`/`absorbed` semantics apply retroactively as a projector change, never a data migration. Immutable issuance artifacts (originalCritique, acceptanceCriteria) live on the `cascades` row AND on the ISSUE action row, so the rebuilder is self-sufficient for derived fields and the immutability invariant is double-anchored.

**Alternatives considered.**
- *Full event-sourced core with per-cascade SHA-256 hash chain* (losing design): structurally strongest flag-not-invent posture, but heaviest implementation risk on the ported base — gapless-seq optimistic append is fiddly under JPA constraint-violation rollback semantics, and it requires refactoring the ported `DecisionLogService` into a shared canonical-JSON hasher before any event is written. Rejected for Phase 2; its replay/rebuild idea and several mechanics are grafted in.
- *Plain mutable rows only*: rejected — violates [CASCADE-DURABILITY] ("the level chain IS the audit trail"); discipline is not a structure.

**REVISIT TRIGGER:** if a cross-aggregate write-model need emerges, or if tamper-evidence requirements harden beyond the D15 attestation seam (e.g., a regulated buyer demands per-cascade hash chains), re-evaluate the event-sourced core; the action log is already the event stream, so migration is projector work, not data archaeology.

### D2. Decline propagation scope: issuing cascade ONLY, with advisory sibling notifications via `cascade_group_id`

**Decision.** A decline transitions only the cascade it was issued in. Auto-propagating state to parallel cascades would fabricate responses in other chains' audit trails — the worst failure mode for a governance system of record (Curasync's HIPAA gate says nothing about Sentari). Concretely (graft): cascades may carry an optional `cascade_group_id`; when a level declines in a grouped cascade, the engine writes an advisory `SIBLING_DECLINE` notification row to each in-flight sibling's originator in the same transaction — **zero state change** on siblings, deduplicated to one advisory per declining-cascade/sibling pair. The sibling cascade's detail DTO surfaces these advisories read-only. Cross-cascade relevance by `affected_domain_id` join remains an additional read-model affordance.

**Alternatives considered:** auto-propagating decline state to group siblings (rejected — fabricated audit records); nothing at all (rejected — plan §10 #2 explicitly asks for a position, and the read-only join alone gives the operator no push signal).

**REVISIT TRIGGER:** the queue-#24 authoring session; or the first real multi-subsidiary fan-out where one structural decline (e.g., a regulatory class) plausibly answers the premise portfolio-wide — then add an explicit operator-cited `decline_reference` link, never auto-propagation.

### D3. Decline propagation mechanics: immediate originator visibility + hop-by-hop ownership + originator short-circuit

**Decision.** The moment the bottom-of-chain declines: (a) "Decline pending" surfaces on the originator's dashboard immediately (destructive = leader-attention semantics per resolved queue #21, rendered beside in-intervention agents); (b) the level above simultaneously owns the chain-of-command response with three verbs — **endorse-decline** (decline at their own level, own mandatory reasoning), **recascade** (revised refinement, reactivates the level below as a new round), or **absorb** (take the work themselves); (c) the originator may at any time accept a pending decline directly (`resolved-declined`, recording `accepted_decline_action_id`) without waiting for full hop-by-hop endorsement — intermediate non-response stays honestly visible in the audit trail. Forcing full endorsement ceremony before the COO can close is the bureaucracy the product exists to kill.

**Alternatives considered:** strict sequential hop-by-hop endorsement before L0 may accept (rejected — bureaucratic; binding text "propagates to L0 and is accepted" supports direct visibility); decline freezes the chain with no verbs above (the losing design's posture; rejected — leaves an originator who *disagrees* with a decline no in-engine move).

**Known synthesis risk (carried from winner):** this reading reconciles two binding sentences that pull in different directions ("propagates to L0 and is accepted" vs "dashboard signal derives from the bottom level"). If Mike reads propagation as strictly sequential, only the resolve-transition guard changes; schema is unaffected (`accepted_decline_action_id` already records which decline was accepted).

**REVISIT TRIGGER:** queue-#24 session — explicitly ask Mike whether strict hop-by-hop-only is wanted.

### D4. Endorse-decline IS decline (the per-level state set stays closed at the binding four)

**Decision.** Passing a decline up is modeled as the upper level transitioning CASCADED→DECLINED with its own mandatory declineReasoning. No fifth state is invented; the upward "decline chain" is the contiguous run of DECLINED levels in the audit log.

**Alternatives considered:** a distinct `endorsed-decline` state (rejected — invents vocabulary outside the binding set); modeling endorsement as metadata on the original decline (rejected — hides an accountability act that deserves its own attributed, timestamped record).

**REVISIT TRIGGER:** ratification of richer decline-chain semantics in the queue-#24 session.

### D5. Auto-escalation timeout: notification-only, NEVER a state transition

**Decision.** The system never speaks for a human in an accountability chain. Per-cascade `response_sla_days` (engagement default `substrate.cascade.default-response-sla-days=7`; explicit `0` disables per cascade — DemoPack seeds with SLA disabled for determinism) measured per level activation→first action, plus decline-pending dwell on the originator. An hourly `@Scheduled` sweep emits `LEVEL_OVERDUE` / `DECLINE_OVERDUE` notifications to the frontier owner, the level above, and the originator, and sets `overdue` projection flags (warn overlay on dashboards). **Sweeper idempotency (graft):** at most one notification per `(cascade, depth, round, renotify-window)` via a deterministic `dedupe_key` with a DB UNIQUE backstop; re-notify every `substrate.cascade.overdue-renotify-days=3` up to `substrate.cascade.overdue-renotify-limit=5`. No synthetic accept/decline ever (a Decline is documented human pushback by definition).

**Alternatives considered:** opt-in auto-advance to the next owner on timeout (rejected for v1 — fabricates a routing act; would land as explicit per-cascade policy, default off, if demanded); no timeout at all (rejected — "decline-pending urgency depends on dashboard visits" was a flagged operability gap).

**REVISIT TRIGGER:** customer feedback demanding auto-advance/auto-reassign; first-engagement calibration of SLA + renotify defaults.

### D6. Authoring persistence: separate mutable `cascade_drafts` table; cascades are born IN_FLIGHT

**Decision.** No DRAFT state on the aggregate — a draft demands nothing of anyone, so no accountability record exists until a human issues. `cascade_drafts` persists all three authoring shapes today (`FREE_FORM`, `IMPERATIVE`, `AI_SUGGESTED`) with `ProducedBy` provenance: AI-suggested text lands in `suggested_*` columns with full orchestrator dispatch provenance (`suggestion_provenance` JSON: task id, dispatch-events digest, model); **only human promotion (`/issue`) creates a cascade** — issued cascades carry `produced_by ∈ {HUMAN, AI_ASSISTIVE}`, never `AI_GENERATIVE` (P6 grounding: ungrounded AI output never auto-issues). The engine write API is deliberately shape-neutral and operator-grade so the queue-#24 session cannot invalidate persistence; the heading/summary shape (#22) is a reserved nullable `summary` column, unrendered.

**Alternatives considered:** aggregate DRAFT state (rejected — pollutes the binding three-state space); drafts inside the audit chain (rejected — nothing organizational has happened yet).

**REVISIT TRIGGER:** the queue-#24 cascade-authoring design session with Mike (resolves #22 and #23 too). Frontend authoring surfaces must NOT be built ahead of that session.

### D7. Originator closure: `resolved-looks-good` with mandatory `resolution_note` (no WITHDRAWN state)

**Decision.** [CASCADE-LIFECYCLE] defines looks-good as "originator accepts the outcome" — including "nothing further needed" and including an outcome whose acceptanceCriteria explicitly enumerated a documented Decline as acceptable (c-vendor-cost fixture). The mandatory note carries the audit distinction; no fourth aggregate state. Reissue-after-closure = a new cascade with `predecessor_cascade_id` lineage.

**Alternatives considered:** a WITHDRAWN terminal state (rejected — outside the binding set; the note is sufficient); a reopen verb (rejected — explicit extension point, terminal states are absorbing in v1).

**REVISIT TRIGGER:** operator confusion in the first engagement; first engagement needing rework-after-resolution beyond reissue lineage.

### D8. Concurrency + idempotency: pessimistic per-cascade lock + `chain_seq` fencing + MANDATORY idempotency key

**Decision.** All mutations serialize per cascade via `PESSIMISTIC_WRITE` on the `cascades` row (matching the ported homepoc `CapabilityService`/`BrandingConfigService` precedent; maps to `SELECT … FOR UPDATE` on PG and is honored by H2 PG-mode), with action insert + level projection + aggregate update + notification outbox rows in ONE transaction. `cascades.chain_seq` (monotonic, +1 per action) is the optimistic fencing token: requests may carry `expectedChainSeq` → 409 `CASCADE_STALE_STATE` (with current state in the envelope) on mismatch. **Idempotency key is mandatory (graft):** every state-changing request carries a client-generated `idempotencyKey`; the action column is `NOT NULL` with `UNIQUE(cascade_id, idempotency_key)`; replays return the original result as 200, not a duplicate action. Issuance idempotency anchors on the draft row (`OPEN→ISSUED` under draft-row lock; re-issuing an ISSUED draft returns the existing cascade).

**Alternatives considered:** constraint-only optimistic append with retry-once (losing design; rejected — fiddly under JPA exception/rollback semantics for the same correctness); `pg_advisory_lock` (rejected — PG-only, breaks H2 dev parity); nullable idempotency key (the winner's original shape; rejected per graft — leaves unprotected retries possible).

**REVISIT TRIGGER:** a contention profile that makes the coarse per-cascade lock measurable (not expected at human-scale write volume).

### D9. Append-only enforcement: PG trigger + service discipline; H2 dev is service-enforced only

**Decision.** `db/migration/postgresql/V4_1__cascade_actions_append_only_trigger.sql` raises on any UPDATE/DELETE of `cascade_level_actions`. Repositories expose no update/delete methods for the action entity; services never mutate loaded action rows. H2 dev lacks the trigger (precedent: V2_1 partial index, port-map §4.3) — Testcontainers-Postgres CI is the gate; H2-green is never acceptance.

**REVISIT TRIGGER:** none expected; if H2 dev-profile mutation bugs bite before CI catches them, add an H2-compatible shim or a runtime assertion layer.

### D10. Notifications: transactional outbox written in the SAME transaction as the action (graft, replaces AFTER_COMMIT)

**Decision.** `cascade_notifications` rows are written inside the action transaction (the judge's correction: an `@TransactionalEventListener(AFTER_COMMIT)` write can silently drop notifications on a crash between commit and listener, contradicting the engine's own atomicity invariant). The in-app attention feed and notification list read this table in Phase 2; email/webhook channel adapters poll the same rows (`dispatched_at` watermark) behind `substrate.cascade.notification-channels` in Phase 5. At-least-once delivery, transports decoupled.

**Alternatives considered:** AFTER_COMMIT listener (rejected — lossy on crash); synchronous external dispatch in-tx (rejected — couples commit latency/failure to transports).

**REVISIT TRIGGER:** push/real-time requirements; notification-table growth/retention policy at engagement scale (currently unbounded — calibrate in first engagement).

### D11. Owner identity: soft member FK + denormalized label

**Decision.** `owner_member_id UUID NULL` (soft reference to `organization_members`, app-enforced like every soft ref in the canonical model) + `owner_label VARCHAR NOT NULL` on every level/action/notification/cascade — honors S5 (fixture string labels → identity FKs) while surviving engagements where the org model is partially ingested; the label is the render fallback. Actor identity comes from `PersonaContext` headers in Phase 2 (S9: spoofable, accepted per staging plan) and the OIDC principal in Phase 5; `actor_label` + nullable `actor_member_id` are recorded on every action so historical records bind to stable identity once IAM lands. Routing is fully manual — `OrganizationMember.reportsTo` is a name string, not an FK, so the engine cannot validate chain-of-command routing (named risk, not an invariant).

**REVISIT TRIGGER:** Phase 5 OIDC landing (actor ids become IAM-stable; re-verify originator-only/owner-only guards); agent-as-level-owner extension when the D3 agent registry lands (`agent_registration_id` becomes a legal owner reference).

### D12. `looking` / `absorbed` encoded at one-sentence fidelity ONLY (flag-not-invent)

**Decision.** LOOKING = initial state on activation, nothing more (no reassignment, expiry, or dwell semantics beyond the D5 overdue notification). ABSORBED = terminates downward propagation, refinedCritique required, cascade stays in-flight pending originator resolution (forced by the aggregate machine — only originator acceptance resolves). NOT encoded, each a named extension point in code + spec §13: absorbed completion/progress tracking, absorbed originator sign-off requirements, looking reassignment, drill-to-L[N] (#23), Monitor/Automate-adjacent behavior. Because the action log records facts and projections are rebuildable (D1), later-ratified semantics are projector changes.

**REVISIT TRIGGER:** the port-map §6 ⚠ validation — fixture/product validation of looking/absorbed semantics; first engagement exercising an absorbed level.

### D13. Read model speaks fixture vocabulary; swap confined to the `BEACON_PORTFOLIO` seam

**Decision.** DTO state values serialize kebab-case via `@JsonValue` (`in-flight`, `resolved-looks-good`, `resolved-declined`, `looking`, `absorbed`, `cascaded`, `declined`); `CascadeDetail.levels[]` matches the `CascadeLevel` view-type shape 1:1, so the C9 dashboards swap at the documented `BEACON_PORTFOLIO` factory seam with a thin adapter and zero view-type rework. `affectedSubsidiarySlug` becomes `affected_domain_id` (a Beacon subsidiary IS a Domain). The richer actions timeline is additive. The frontend must add a 409-and-refresh pattern (new for the Beacon UI) before write affordances ship.

**REVISIT TRIGGER:** Phase 5 identity makes member FKs required; queue-#22/#23 resolutions reshape the detail surface.

### D14. Degraded mode boundary: exactly one orchestrator touchpoint

**Decision.** The entire engine — issue, cascade, absorb, decline, recascade, resolve, notifications, escalation, read model, DemoPack — is pure substrate. The orchestrator MCP seam touches exactly one endpoint (`POST /cascade-drafts/{id}/suggest`), gated on `substrate.orchestrator.enabled` (`@ConditionalOnProperty`); when disabled, the endpoint 404s and the UI affordance hides. Substrate consumes only `submit_task` / `get_dispatch_status` / `get_dispatch_result` / `get_dispatch_events` / `check_budget_for_task`; never PM-loop tools. Orchestrator-dispatched agents get read-only context via the public GET endpoints; **there is no agent write path** — agent output flows back through `get_dispatch_result` into the draft, applied only by human promotion.

**REVISIT TRIGGER:** cascade-level execution BY agents (an agent as a level owner) — rides the same seam, gated on the D3/D11 agent-registry extension.

### D15. Cross-engine attestation: extension seam, not a second hash stack (graft, lightened)

**Decision.** A `CascadeAttestationHook` interface (no-op default) is invoked on terminal resolution; the designed-but-not-built implementation anchors a SHA-256 digest of the cascade's canonical-serialized action log into the existing `engine.decision` hash chain — tamper-evidence for the governance audit trail without building a second canonical-JSON hashing stack in Phase 2.

**REVISIT TRIGGER:** first engagement/regulated-buyer demand for cascade tamper-evidence; implement behind `substrate.cascade.attest-to-decision-log` (default false).

### D16. DemoPack seed: action-stream replay through the real state machine (graft)

**Decision.** The three homepoc fixtures are seeded by driving the real lifecycle service (draft → issue → cascade → … → decline) with an injectable fixed clock (S15), never by inserting rows — so the seed is also a standing integration test of the state machine. **c-vendor-cost is the terminal-decline test vector — NOT c-tribal-succession** (port-map §6 fixture caveat: 05-walkthrough.md drifted; the block-5 checkpoint and the actual fixture put the decline on c-vendor-cost, verified against `cascades.ts` 2026-06-09). Engine semantics add LOOKING frontier rows the fixtures omit (fixtures never render `looking`); the two frontier owner labels not present in fixture rows are derived from fixture narrative text and flagged as seed-only fiction (demo data never ships to customers, port-map trap #9).

**REVISIT TRIGGER:** fixture changes in homepoc; Beacon fixture excision at live-swap time.

### D17. Build order: read-model-first (binding D1 sequencing)

**Decision.** V4 migration + entities/repos → projections + read API + DTO adapters → DemoPack action-stream seed → Beacon `CascadeDetailView`/dashboard swap at the `BEACON_PORTFOLIO` seam → minimal command API → escalation sweeper → drafts + AI-suggest behind the orchestrator seam. Acceptance at every chunk is user-runnable (boot + curl, per the acceptance-must-be-user-runnable rule); Testcontainers-Postgres is the gate.

**REVISIT TRIGGER:** none — this is the binding sequencing rule restated for the record.

---

## 4. Consequences

**Positive.**
- Decline-never-error is structural: no ERROR/FAILED value exists in any cascade enum; DECLINE and RESOLVE(declined) return 200; `resolved-declined` is a peer terminal state; a pinned contract test guards the envelope path.
- The audit trail is append-only by construction (action log + PG trigger), and every projection is rebuildable — UNKNOWN-semantics ratification later is projector work, not data migration.
- One focused build on existing codebase idioms (pessimistic locks, JsonNodeConverter TEXT JSON, vendor-split Flyway, PersonaContext actors, envelope advice) — the implementability criterion that decided the judgment.
- Whole-or-in-part holds: the engine is fully functional with orchestrator absent; exactly one endpoint disappears.
- The originator always has a move after a decline (accept it, or watch the level above endorse/recascade/absorb) — no frozen chains.

**Negative / accepted risks.**
- D3's decline mechanics are a synthesis of binding sentences in tension — top revisit item for Mike; only a transition guard changes if overruled.
- `looking`/`absorbed` render beats are untested against real product expectations (port-map ⚠); kept minimal deliberately.
- Authorization is persona-header trust until Phase 5 — originator-only/owner-only guards are spoofable in Phase 2 (accepted per S9 staging).
- Mis-routing is silently legitimate (reportsTo is a string); manual routing is v1 reality.
- H2 dev cannot enforce the append-only trigger or prove TIMESTAMPTZ/locking parity — CI discipline is load-bearing; this dev machine cannot run Docker.
- The operator-grade write API risks becoming de-facto leader authoring UX if the queue-#24 session slips.
- Notification retention and SLA/renotify tuning are first-engagement calibration items.
- JSON columns are TEXT per port-map §4.5; payload-predicate queries would trigger the documented one-pass jsonb flip.

**Follow-ups owed to Mike (revisit triggers consolidated):** queue-#24 authoring session (D2, D3, D4, D6 — also resolves #22/#23); strict-vs-short-circuit decline reading (D3); looking/absorbed ratification (D12); first-engagement calibration of SLA/renotify/retention (D5, D10); OIDC re-verification of actor guards (D11); attestation demand (D15).
