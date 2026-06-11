# Product Design Session Packet — Cascade Authoring + Contract Flows

**Purpose:** one working session with Mike that closes every product decision the engine builds deliberately left open. Engines ship read-model-first; nothing below blocks shipping — these gate only the **leader-facing write surfaces** (cascade authoring UX, contract Review/Decline buttons).

**How to use:** each item states the question, the options, the position currently shipped (autonomous + revisable per the standing grant), and exactly what flips if you choose differently. Decisions are ordered so the anchor decision (#1) is taken first — several later items collapse depending on its answer.

**Pre-reads (optional, 10 min):** ADR-001 §3 D2–D7/D12, ADR-002 D6/D7, `engine-spec/cascade.md` §13, `engine-spec/contracts.md` §15.

---

## Part 1 — Cascade authoring (the anchor: homepoc queue #24)

### 1. Authoring shape — how does a leader write a cascade?
**Question:** AI-suggested, free-form, terse-imperative — or all three?
**What exists:** `cascade_drafts` persists all three shapes already (`FREE_FORM`, `IMPERATIVE`, `AI_SUGGESTED`); the write API is operator-grade and shape-neutral; AI-suggest endpoint live behind `substrate.orchestrator.enabled`. Only human promotion (`/issue`) creates a cascade; AI text never auto-issues (P6).
**Needed from you:** the leader-facing UX — entry points (from a finding? from a dashboard tile? blank-slate?), issuance preview, routing suggestions, edit-approve loop for AI suggestions.
**Flip cost:** zero schema/API change regardless of choice — this was designed to survive any answer. Pure frontend + product work.

### 2. Heading/summary shape (queue #22) — *collapses into #1*
**What exists:** reserved nullable `summary` column, unrendered.
**Needed:** whether cascades carry a short heading distinct from the critique, and who writes it (author vs AI).

### 3. Drill-to-level interaction (queue #23) — *collapses into #1*
**What exists:** the full level timeline is the only navigation; no drill verb, no drill affordance.
**Needed:** whether "drill to L[N]" is a real interaction and what it shows.

### 4. Decline reading — may the originator short-circuit?
**Question:** when a level declines, may the originator accept that decline directly (current), or must each level above endorse it first (strict hop-by-hop)?
**Shipped position (ADR-001 D3):** short-circuit allowed — the originator may accept a pending decline at any time; the level above simultaneously gets endorse-decline / recascade / absorb verbs; intermediate non-response stays visible in the audit trail. Rationale: forcing full endorsement ceremony before the COO can close is the bureaucracy the product kills. **This is the top flagged synthesis risk** — two binding sentences pull in different directions.
**Flip cost:** one transition guard in `CascadeLifecycleService`; schema unaffected (`accepted_decline_action_id` already records which decline was accepted).

### 5. Decline propagation to parallel cascades (plan open decision #2)
**Question:** does one subsidiary's decline affect sibling cascades issued in the same fan-out?
**Shipped position (ADR-001 D2):** issuing cascade only. Grouped cascades (`cascade_group_id`) get a read-only advisory `SIBLING_DECLINE` notification to each in-flight sibling's originator — zero state change on siblings. Auto-propagation was rejected: it fabricates responses in other chains' audit trails.
**Option if demanded:** an explicit operator-cited `decline_reference` link (designed, not built). Never auto-propagation.
**Flip cost:** additive — new link + UI affordance; nothing existing changes.

### 6. Escalation timeout behavior
**Question:** when a level sits past its response SLA, should the system ever act for the human?
**Shipped position (ADR-001 D5):** notification-only, never a state transition (idempotent overdue notifications: default SLA 7d, renotify every 3d, max 5; SLA=0 disables per cascade). Auto-advance to the next owner was rejected for v1 — it fabricates a routing act.
**Option if demanded:** opt-in per-cascade auto-advance policy, default off.
**Calibration sub-item:** are 7d / 3d / ×5 the right defaults for the first engagement?

### 7. `looking` / `absorbed` semantics ratification
**Question:** the two level states never exercised by any fixture were encoded at one-sentence fidelity (LOOKING = received-not-acted; ABSORBED = takes the work, stops downward propagation, cascade stays in-flight until you resolve). Is that the product you want?
**Not encoded (flagged as extension points):** absorbed progress tracking / completion evidence / originator sign-off; looking reassignment/expiry.
**Flip cost:** projections are deterministically rebuildable from the action log — later-ratified semantics are a projector change, **not** a data migration.

---

## Part 2 — Contract flows

### 8. Review/Decline product flow (homepoc queue #28)
**Question:** what happens, step by step, when a party clicks Review or Decline on a proposed contract?
**What exists:** contract entities + V5 migration landed; respond/decline endpoints ship in phase 3a with **UI affordances deliberately inert**; party responses are idempotent; content freezes at the first party response; decline validates a pre-agreed trigger and requires reasoning, returns 200, renders subtle-destructive (never breach-colored).
**Needed from you:** the review ceremony (counter-proposal? comment thread? simple accept/decline?), who may respond for a COMBINED party, and whether a declined PROPOSED contract is revisable or dead.

### 9. Decline as escape hatch — open breach at decline time
**Question:** may a party invoke a decline trigger while they have an open breach (i.e., decline their way out of remediation)?
**Shipped position (ADR-002 D6):** allowed, logged `ALLOW_WITH_FLAG`; open breaches survive the decline and run their human lifecycle to terminal. Blocking was rejected as an invented invariant.
**Flip cost:** one gateway guard — small.

### 10. Federation review-boundary semantics
**Question:** federation contracts permit decline "at quarterly review boundaries" — what is a review boundary, concretely?
**Shipped position (ADR-002 D6):** enforced only when `review_boundary` config is non-null; the fixture ships it null, so declines pass with `ALLOW_WITH_FLAG REVIEW_BOUNDARY_UNCONFIGURED` rather than inventing a calendar rule.
**Needed:** real boundary semantics (calendar quarters? contract anniversary? party-agreed dates?) — or confirmation that per-contract config is the right grain.

### 11. Enforcement-claim wording for sales copy
**Question:** sign off the honest scope of "agents cannot route around the contract."
**Shipped reality (ADR-002 D1):** literally true **for substrate-held state** (CI-proven gateway coverage, one named v1 exemption: ingestion bulk-commit). Actions inside customer systems that never transit substrate are *observed* (monitors, attestations, probes), not intercepted.
**Needed:** approved copy: "adherence is observable; non-adherence is a signal; violating substrate writes are blocked" — never "all violations are blocked."

---

## Part 3 — Five-minute calibration items (defaults need a yes/no, not a discussion)

| Item | Shipped default | Source |
|---|---|---|
| Cascade response SLA / renotify / limit | 7d / 3d / ×5, SLA=0 disables | ADR-001 D5 |
| Notification retention | unbounded (flagged) | ADR-001 D10 |
| Contract enforcement rollout | per-engagement `shadow` → flip to `enforce` after a clean window | ADR-002 D4 |
| Gateway fail posture | fail-closed for AGENT/SERVICE, fail-open+flag for HUMAN | ADR-002 D5 |
| Enforcement decision-log retention | none yet (named risk — needed before any high-traffic agent engagement) | ADR-002 D17 |
| Decision-log level | matched-only (non-matching ALLOWs counted, not rowed) | ADR-002 D17 |

---

## Explicitly NOT on the agenda (decided autonomously, working, revisit triggers armed)

Append-only action-log architecture; rebuildable projections; idempotency-key mechanics; pessimistic locking; transactional notification outbox; decline-never-error rendering; one-PDP/three-PEP enforcement model; tiny predicate DSL scope; monitor INDETERMINATE-never-breaches; hash-chain mirroring scope; demo seeding via real lifecycle verbs. These have explicit REVISIT TRIGGERS in the ADRs and need no session time unless a trigger fires.

**Suggested agenda (30 min):** #1 + its collapses (#2, #3) — 10 min · #4 — 5 min · #8 — 10 min · Part 3 table — 5 min. Items #5–#7, #9–#11 can ride along or go async.
