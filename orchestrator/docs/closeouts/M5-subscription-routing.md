# M5-subscription-routing — sub-milestone close review

Closes the M5-subscription-routing sub-milestone. This is the canonical artifact
for the chain-wide retrospective: lineage notes, drift findings, watch-for-pattern
outcomes, and address-now routing.

This closeout's checklist was constructed ad-hoc during commit 6. Future
sub-milestones may benefit from a written close-review checklist at prep close;
deferred decision pending observation of this artifact's shape.

## Chain summary

Six commits. Test count trajectory: 318 (chain start) → 433 (post-close-review).
Net +115 tests through the chain.

| Commit | Hash | Description | Tests delta |
|---|---|---|---|
| 0.5 | `4874c8e` | agents-table seeding (Codex Finding 1 remediation) | 318 → 325 (+7) |
| 1 | `8918bb3` | observability tier-1 columns + `record_api_call` extension | 325 → 332 (+7) |
| 2 | `2066a8f` | `get_token_usage` + `get_burn_rate` + UTC-coercion extraction | 332 → 382 (+50) |
| 4 | `5e07a9a` | `check_budget_for_task` pure routing function | 382 → 418 (+36) |
| 5 | `a4b70a7` | `check_budget_for_task` wrapper + `skipped_no_token` log tightening | 418 → 428 (+10) |
| 6 | (this) | e2e tests + close review | 428 → 433 (+5) |

Numbering note: commits 2 and 3 merged per prep disposition (shared window-resolution helper + tightly coupled aggregation). No commit 3 in the canonical chain.

## Spec amendments shipped

- **`05-data-model.md`** — `api_usage` DDL block comment + migration-table row for `0009_observability_cost_tier1.sql` (commit 1).
- **`06-mcp-tools-phase-one.md`**:
  - `RecordApiCallInput` schema extension with three optional cohort fields (commit 1).
  - Mike-as-PM caller-role detail in hierarchy invariant 1 (commit 2).
  - `CheckBudgetForTaskOutput` rename `rationale` → `rationale_text` + structured `routing_factors` + `fallback_active` (commit 4).
  - "Not audited" rationale sentence for `check_budget_for_task` (commit 5).
- **`08-implementation-plan.md`** — M5 deliverables migration bullet for `0009_observability_cost_tier1.sql` (commit 1).
- **`orchestrator-utilization-addendum.md`** — implementation-status pointer at the bottom (commit 6).

## Migrations shipped

- **`0009_observability_cost_tier1.sql`** (commit 1) — four columns on `api_usage`: `notes`, `workflow_id`, `agent_role`, `task_type`. Indexes `idx_api_usage_workflow` + `idx_api_usage_role_type`. Schema-version 9.

## New cross-layer single-source-of-truth instances

Compounded the "derive don't duplicate" pattern from prior sub-milestones:

- **`_AGENT_ROLE_SUBTREE: dict[str, frozenset[str]]`** (`tools/budget.py`, commit 2). Hardcoded subtree map per Q3 disposition; documented exception to the derive-don't-duplicate pattern with `agents.reports_to` (Phase 1 fixed cardinality; runtime derivation is over-engineering for static cardinality). Single-source contract test asserts the four entries + values.
- **`_PRIORITY_TIER_DELTA: dict[int, int]`** (commit 4). `{1: +1, 2: 0, 3: 0, 4: 0, 5: -1}` per Q6. Sentinel test locks the mapping.
- **`_TIER_HIERARCHY: tuple[str, ...]`** (commit 4). Cheapest → most-capable ordering. Sentinel guards against accidental reorder which would silently invert priority semantics.
- **`_ROLE_DEFAULT_TIER: dict[str, int]`** (commit 4). Per-role baseline tier. Sentinel.
- **`_PCT_FACTOR_FORMAT_PATTERN: str`** (commit 4). Regex pattern documenting the stable structural prefix `<dimension>_at_<value>pct_<context>` for percentage-embedded routing factors. Sentinel test walks fixture outputs and asserts every pct-format factor matches the pattern. Pays compound interest at observability tier 2.
- **`_is_snapshot_fully_parseable`** (`tools/budget.py`, commit 4). Single-call-site for malformed-snapshot detection; guards routing fallback path.

## Watch-for-pattern outcomes

Three threshold patterns at chain start; one resolved during the chain.

| Pattern | Pre-chain count | End-of-chain count | Action |
|---|---|---|---|
| Inline UTC-coercion convention | 2 | 0 (extracted) | **Resolved at commit 2.** Third instance arose in `_resolve_window`; trigger fired per BACKLOG entry; extracted to `storage/datetime_utils._coerce_naive_to_utc` with unit tests covering naive-coerce + tz-aware-preserve (the `+05:00`-bug-class). BACKLOG entry marked RESOLVED preserving audit lineage. |
| `bridge/` → `tools/` cross-layer imports | 2 | 2 | Unchanged; no new cross-layer imports introduced. |
| `token_store=None` defensive sites | 2 | 2 | Unchanged. |
| Production-code-surface-expanded-for-testability | 2 | 2 | Unchanged. |

New observation (single instance — not yet a pattern): `check_budget_for_task` is async-but-no-I/O (commit 5). The async signature matches the wrapper-pattern convention while the body wraps the pure `_compute_routing` decision function. Comment in handler docstring makes the deliberate choice explicit so future readers don't see it as accidental drift.

## Codex findings status (round 1)

Five findings ingested at commit 1. Status:

- **Finding 1** (agents-table seeding gap) — **RESOLVED** at commit 0.5.
- **Finding 2** (concurrent transaction safety; shared `_in_txn` boolean) — deferred to before M5-flag-flips. BACKLOG'd.
- **Finding 3** (dispatch timeout not enforced) — deferred to M5-flag-flips. BACKLOG'd.
- **Finding 4** (subprocess pipe deadlock potential) — deferred to M5-flag-flips. BACKLOG'd.
- **Finding 5** (tester `commit_hash` dropped) — deferred to M5-flag-flips or hygiene. BACKLOG'd.

The grouping convention (Codex review findings under a dedicated subheading in BACKLOG.md) was established at commit 1 and signals "review-pass-as-event"; future review passes inherit the pattern.

## Lineage notes for future-Claude

These items are non-obvious from the code alone and worth surfacing for cold reads.

- **Cohort grouping deferral on `get_token_usage` output** (commit 2). Spec output ships with `by_model` + `by_agent` only; `by_workflow_id` / `by_agent_role` / `by_task_type` defer to operational-data-driven decisions. BACKLOG entry frames the trigger for revisiting (likely candidates in order of readiness: `by_agent_role` first since it's auto-populated; `by_workflow_id` and `by_task_type` follow once their population protocols resolve at M5-continuous-pm-driver).

- **Bidirectional contradiction handling caught permission-gate drift at commit 5 prep**. Reviewing-Claude's kickoff message stated *"every dispatch-able role checks budget before it dispatches"* as the permission framing for `check_budget_for_task`. The spec at `06-mcp-tools-phase-one.md:629` actually denies Implementer/Tester. Surfacing the contradiction transparently (Q1 of commit 5 prep) led to the right disposition: spec is right; Implementer/Tester are dispatched-INTO subprocesses with a model fixed by Engineer at dispatch time and don't make their own routing decisions. Wrapper enforces denial via `_require_role(caller_role, "pm", "engineer")`.

- **Pure-function-not-class structural call at commit 4**. The reviewing-Claude prep imprecisely framed the "BackoffStateMachine precedent" as suggesting a class shape. Routing has no inter-call state; pure function is the right shape. The precedent applies as "I/O-separable decision logic," not literally "use a class." Reasoning correction surfaced explicitly during commit 4 prep + reflected in the closeout for future readers.

- **Malformed-snapshot disposition (a) trade-off** (commit 4). Routing collapses any malformed required counter to the fallback path indistinguishable from `snapshot=None`. Conservative semantics avoid the silent-rate_limited-miss bug class on a malformed `five_hour`. Observability loss documented in `_compute_routing` docstring as deliberate; tier-2 distinction would need a separate signal layer.

- **Q5 sub-amendment pinned the pct-factor format convention** (commit 4). Without the explicit format, future tier-2 LIKE-pattern queries against `routing_factors` would depend on string-format conventions that drift silently. Sentinel test walks fixture outputs and enforces the format on every factor matching `*pct*`.

## Address-now items routed forward

All items either resolved during the chain or properly deferred:

- ✓ `oauth_usage_poller.skipped_no_token` log tightening — landed at commit 5.
- ✓ Cohort grouping deferral on `get_token_usage` — BACKLOG'd at commit 2.
- ✓ workflow_id / task_type protocol resolution framing — BACKLOG'd at commit 1, deferred to M5-continuous-pm-driver.
- ✓ 80% Opus-downgrade threshold verification — deferred to M5-flag-flips after operational data.
- ✓ Codex Findings 2-5 — BACKLOG'd at commit 1, deferred to M5-flag-flips.
- ✓ Partial-roster operation (commit 0.5) — BACKLOG'd at commit 0.5 as documented behavior.
- (still deferred) `test_tool_activity_writer.py` rename — no natural touchpoint during M5-subscription-routing; queued for next sub-milestone whose chain naturally touches the file.
- (still deferred) `complete_dispatch` transactional parity — no natural touchpoint during M5-subscription-routing; same disposition.
- (still deferred) Verify `MODEL_PRICING` before M5-flag-flips — operator workflow per the BACKLOG entry; not a code-side action.

Nothing else address-now post-chain.

## Drift-detection scan (Q5 standard scope)

Scope: spec docs (`05-data-model`, `06-mcp-tools-phase-one`, `08-implementation-plan`) + `config/agents/*.yaml` + migration headers + relevant docstrings (`tools/budget.py`, `server.py`, `main.py`, `bridge/oauth_usage.py`).

Two findings; both fixed at commit 6:

1. **`config/agents/pm.yaml` missing `check_budget_for_task` in `mcp_tools_allowed`.** Spec at `06-mcp-tools-phase-one.md:629` permits PM, but the runtime allowlist didn't include it after the spec amendment landed at commit 5. Vocabulary-drift sub-category. Fixed at commit 6.

2. **`server.py:_register_budget_tools` docstring milestone-attribution slip.** Said *"check_budget_for_task lands at M5-subscription-routing commit 4"* — actually the pure routing function landed at commit 4 and the wrapper landed at commit 5. Milestone-attribution sub-category. Fixed at commit 6.

Other scan targets clean:
- 0001 / 0009 migration headers accurate post-chain.
- `orchestrator-utilization-addendum.md` no stale forward-references; gets one-line implementation-status pointer at the bottom (commit 6).
- Spec docs internally consistent; M5-subscription-routing references propagate correctly.
- `engineer.yaml` correctly includes `check_budget_for_task`; `implementer.yaml` and `tester.yaml` correctly omit it (spec denies them).
- Touched-module docstrings reference shipped commits accurately (with the one milestone-slip noted above).

## Drift between kickoff scope and landed scope

None. The kickoff dispositions (Q1 hybrid window semantics, Q2 caller-role-aware routing, Q3 fallback to per-role defaults, Q4 80% Opus-downgrade threshold, Q5 priority delta, Q6 cohort columns auto-populated, Q7 inherited deferrals) all landed as dispositioned. The architectural sub-amendment (structured `routing_factors` + `fallback_active`) similarly landed as dispositioned.

The Codex-finding ingestion (commit 1) was an unplanned addition triggered by the round-1 review pause. Finding 1 became commit 0.5 (precursor, not in original chain plan); Findings 2-5 went to BACKLOG.

The malformed-snapshot disposition (commit 4 review) was an in-flight refinement clarifying the trade-off between three readings. Landed as disposition (a) — full fallback collapse, indistinguishable from `snapshot=None`.

## Discipline-pattern observations

Patterns reinforced through the chain:

- **Bidirectional contradiction handling** caught the permission-gate drift at commit 5 prep (described in Lineage notes above).
- **Sub-milestone close review against BACKLOG checklist** (this artifact). The pattern is documented in `feedback_explicit_review_at_chain_close` memory; commit 6 implements it.
- **Pre-checkpoint disposition cycle** (prep → dispositions → diff → land) held across all six commits. Each commit surfaced a structured prep checkpoint with numbered questions + recommendations; user dispositioned each before drafting.
- **Watch-for-pattern at threshold-of-three**. The UTC-coercion pattern hit threshold at commit 2 and was extracted in the same commit per the BACKLOG entry's documented trigger (rather than deferring).

## What's next

Sub-milestone 6 of 9 closes. Remaining M5 sub-milestones per the breakdown:

- **M5-continuous-pm-driver** — workflow_id / task_type population protocols resolve here; PM's `current_focus` deliverable contract; first narration-tier-1 work per the observability roadmap.
- **M5-flag-flips** — `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true`; `MODEL_PRICING` verification; api_key_overflow window-cycle resolution; Codex Findings 2-5 addressed; the 80% Opus-downgrade threshold validated against operational data.
- **M5-acceptance** — the operator-driven smoke test concluding M5.

The agents-table seeding gap (Codex Finding 1) and the inline UTC-coercion extraction (compound pattern resolution) are the most consequential cross-cutting items resolved during this chain.
