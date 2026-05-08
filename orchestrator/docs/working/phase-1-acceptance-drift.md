# Phase 1 acceptance-criteria-coverage drift scan

**Date:** 2026-05-03
**Scope:** M1 through M5-subscription-routing (closed milestones only).
**Out of scope (per Mike's directive):** M5-continuous-pm-driver, M6, M7.
**Trigger:** F2.1 commit-1 contradiction surface during M5-continuous-pm-driver
prep checkpoint revealed `tools/tasks.py` and the six task-management MCP
tools absent. The closeout convention catches drift inside the closing
sub-milestone's scope; phase-boundary drift requires a separate scan.
This is the first instance of the **phase-boundary acceptance-criteria-
coverage scan** convention (per Mike's disposition Part 3).

**Methodology:** for each M-level milestone, read the acceptance criterion
from `docs/kickoff/08-implementation-plan.md`, verify acceptance-criterion-
relevant production code paths exist in `src/`. Distinguish three
categories per Mike's Part 4 framing:

- **(I-A)** implemented and acceptance-met
- **(I-NR)** implemented but acceptance-not-runnable end-to-end (typically
  because tests pass via direct SQL fixtures but the production-side
  caller surface to seed the path is missing)
- **(N-I)** not implemented

For (N-I) and (I-NR) entries: note remediation routing.

**Out-of-bound items (not part of acceptance criteria):** spec-doc
internal cross-references, BACKLOG entries, watch-for-pattern thresholds,
discipline conventions. Only acceptance-criterion coverage is in scope.

---

## M1 — Project skeleton & storage layer

**Status: I-A.**

Acceptance criteria (08-implementation-plan.md:25-32):

| Criterion | Verification |
|---|---|
| `uv sync` runs clean | Implicit; gates green at session start |
| Service starts/exits on SIGTERM | `src/meridian_orchestrator/main.py` exists with lifespan; `python -m meridian_orchestrator.main` is the entrypoint |
| `scripts/init-db.py` creates `data/orchestrator.db` | `scripts/init-db.py` exists |
| `tests/unit/storage/` passes | Directory contains 5 test files (`test_datetime_utils.py`, `test_migrations.py`, `test_models.py`, `test_seed.py`, `test_sqlite.py`); 433 total tests passing |
| `ruff check src/ tests/` passes | Verified at session start |
| `mypy --strict src/` passes | Verified at session start; 37 source files |

Module structure verified per `04-tech-stack.md`: `agents/`, `audit/`,
`auth.py`, `bridge/`, `config.py`, `errors.py`, `main.py`, `oauth.py`,
`server.py`, `storage/`, `tools/`. All present.

**No drift.**

---

## M2 — MCP server scaffolding & auth

**Status: I-A.**

Acceptance criteria (08-implementation-plan.md:51-58):

| Criterion | Verification |
|---|---|
| Service starts + MCP reachable | `src/meridian_orchestrator/server.py` exists |
| Valid token connects + ping round-trip | `PingOutput` schema at `server.py:129`; `ping` handler at `server.py:296` |
| Invalid/missing token rejected | `auth.py` exists with token validation; tests cover denial path (`tests/unit/test_auth.py`) |
| Tokens persist across daemon restart | `auth.py` uses `keyring` (per kickoff spec) |
| `ping` echoes input + agent_id | Same handler as above |
| `tests/integration/test_mcp_ping.py` passes | File exists; passes in 433-test suite |

CLI scripts present: `scripts/issue-token.py`, `scripts/revoke-token.py`,
`scripts/list-tokens.py`.

**No drift.**

---

## M3 — Agent config loading, permission framework, bridge design

**Status: I-A on code surface; one operator-side caveat.**

Acceptance criteria (08-implementation-plan.md:86-91):

| Criterion | Verification |
|---|---|
| 4 YAMLs load + validate | `config/agents/{pm,engineer,implementer,tester}.yaml` all present |
| Hot reload triggers on YAML edit | `agents/registry.py` uses `watchfiles`; tests cover reload behavior |
| Permission checks correct | `agents/permissions.py:PermissionResolver` implements per-tool allowlist + hierarchy |
| Denial errors don't leak permission graph | `permissions.py:80-82` raises `OrchestratorPermissionError` with generic message |
| Bridge architecture documented | `docs/kickoff/11-engineer-bridge.md` exists (760+ lines) |
| Mike-approved | **Operator-side; not verifiable from code state.** Architectural disposition lives in Mike's reading. Absence-of-finding here is not a positive verification of approval. |

`list_agents` / `get_agent_config` / `get_org_chart` deliverables shipped
as sync handlers in `tools/agents.py`. Wired into server.py via
`_register_agents_tools` at `server.py:1022`.

**No code-surface drift.** Mike-approval is an operator state I cannot
verify; flagged as caveat, not drift.

---

## M4 — Task management + subagent dispatch

**Status: every M4 sub-milestone I-A at sub-milestone-internal scope;
M-level scope has N-I gap due to inter-sub-milestone scope drift.**

The headline finding of this scan. The framing here matters: each M4
sub-milestone (M4a / M4b / M4c-supervisor-base / M4c-engineer-supervisor
/ M4c-compaction / M4c-tooling) shipped exactly its scoped deliverables.
None drifted internally. The N-I gap exists at **M-level inter-sub-milestone
scope** — `tools/tasks.py` and the six task-management MCP tools were
M-level deliverables (08-implementation-plan.md:103-104) that no
sub-milestone took as scope. Different failure mode than sub-milestone
drift; deserves different language.

### M4 sub-milestones (sub-milestone-internal granularity) — all I-A

| Sub-milestone | Scoped deliverables | Status |
|---|---|---|
| **M4a** (storage + serial dispatch lifecycle, `15ec0eb`) | `0002_task_planning.sql`, `0005_dispatches.sql`, `BridgeDispatcher` serial path, four dispatch handlers, audit chain | I-A |
| **M4b** (parallel + merge protocol, `cb9277c` + `f3e0f52` + `e431005`) | `--ff-only` post-dispatch merge, `CanonicalDivergedError`, `dispatch_tester`, asyncio.Lock canonical operations, Windows `.git/config.lock` mitigation | I-A |
| **M4c-supervisor-base** (`af0695c` + `0b50e66`) | `LongRunningSupervisor` ABC, watchdog (60s/30min/3-respawns-per-hour), `bridge/activity.py`, MCP middleware via `_record_caller_activity`, e2e activity-recording test | I-A |
| **M4c-engineer-supervisor** (`c6da070`) | `EngineerSupervisor`, `Settings.engineer_supervisor_dir` + `enable_engineer_supervisor` flag, main.py wiring with closure pattern, two production-shape datetime bug fixes | I-A |
| **M4c-compaction** (`ab99f88`) | `signal_compaction` MCP tool, subprocess-exit detection, `_compaction_pending`, `0008` CHECK constraint, six new audit actions | I-A |
| **M4c-tooling** (`8667098`) | `bridge/timeouts.py`, `get_recent_dispatches`, `get_dispatch_events`, BridgeDispatcher `model` param | I-A |

Every sub-milestone hit its own scope cleanly. Sub-milestone-internal
verification: nothing drifted.

### M4 dispatch surface (cross-sub-milestone composite) — production-runnable post-M5-wrapper-closure

Deliverables (08-implementation-plan.md:108-118):

| Deliverable | Status |
|---|---|
| `bridge/dispatcher.py`, `canonical.py`, `worktree.py`, `timeouts.py`, `supervisor_base.py`, `engineer_supervisor.py` | All present |
| `tools/dispatch.py`: `dispatch_implementer`, `dispatch_tester`, `complete_dispatch`, `get_dispatch_status`, `get_dispatch_result`, `get_dispatch_events`, `get_recent_dispatches` | All present |
| `tools/supervisor.py`: `signal_compaction` (engineer-only) | Present |
| `audit/logger.py` | Present |
| Migrations 0002, 0005, 0006, 0007, 0008 | All present |
| MCP wrappers in server.py for the dispatch lifecycle | Present (M5-wrapper-closure: `9bf6d8a`–`a184faf`) |
| BridgeDispatcher in main.py lifespan | Present (M5-wrapper-closure) |

### M4 M-level inter-sub-milestone scope — N-I

The M-level deliverable list at 08-implementation-plan.md:103-104 names a
surface that was **not picked up by any of the six M4 sub-milestones**:

> `tools/tasks.py` — `submit_task`, `get_queue`, `update_task_status`,
> `set_task_priority`, `claim_task`, `get_subtasks`

**Reality:** `tools/tasks.py` does not exist. None of the six tools are
implemented. Verified per F2.1 commit-1 contradiction surface
(2026-05-03):

| Tool | Status | Evidence |
|---|---|---|
| `submit_task` | N-I | No matching function definition in `src/`. Only references are: `agents/permissions.py:13` docstring (example); spec docs; `config/agents/{pm,engineer}.yaml` allowlists. |
| `get_queue` | N-I | Same. |
| `update_task_status` | N-I | Same. |
| `set_task_priority` | N-I | Same. |
| `claim_task` | N-I | Same. |
| `get_subtasks` | N-I | Same. |

Production-side INSERT INTO tasks: zero matches in `src/`. Production code
paths read from the tasks table (`bridge/dispatcher.py:292` for
`estimated_tokens`; `tools/dispatch.py:635` for `claimed_by`) but never
write. Test fixtures are the only source of task rows.

M4 closed (2026-05-01) with this surface unbuilt and **not flagged in the
M4-accumulated BACKLOG items** (08-implementation-plan.md:151-161). This
is the inter-sub-milestone scope drift — none of the M4 sub-milestone
prep checkpoints surfaced "this M-level deliverable hasn't been claimed
by any sub-milestone yet." Sub-milestone closeouts catch
sub-milestone-internal drift; this gap was outside their reach by
construction. The phase-boundary scan convention is the one that catches
it.

### M4 M-level acceptance criteria affected by the N-I

| Criterion (08:122-128) | Resolution |
|---|---|
| All unit tests pass | I-A (433 tests passing) |
| Integration test produces real commit in `meridian-homepoc` upstream | **I-NR at M-level.** Tests pass via direct SQL fixture insert (`tests/integration/test_dispatch_parallel_and_merge.py:92`); production-runnable only after M4d closes the seed-task path. Not sub-milestone drift; M-level acceptance was scoped to require a seed-task surface that no sub-milestone implemented. |
| Audit log captures full chain — Mike queries via `get_audit_log` | Cross-milestone dependency on M6 (`get_audit_log` is M6 deliverable). Not M4 drift; documented forward-reference. |
| Substrate touch map cache populates correctly | I-A within tests; I-NR-at-M-level for production-end-to-end (same shape as the integration-test criterion). |
| Double-claim returns CONFLICT | **UNVERIFIABLE due to N-I `claim_task`.** |
| Role-mismatch claim returns PERMISSION_DENIED | **UNVERIFIABLE due to N-I `claim_task`.** |
| `engineer_decisions` empty-vs-default no NULL hazards | I-A (test coverage). |

### M4 checkpoint — load-bearing demo not runnable

Checkpoint (08-implementation-plan.md:132):

> "Mike walks through a real PM→Engineer→Implementer→Engineer→PM cycle
> in his interactive Claude Code session, observes the work happening in
> `meridian-homepoc`, queries the audit log, sees the chain. **This is
> Phase 1's load-bearing demo: the company actually staffs itself.**"

**Not runnable in production.** PM has no MCP tool to seed the cycle's
first hop. Mike would have to hand-INSERT a task row via direct SQL to
demonstrate the demo. The dependency runs through the M-level N-I gap,
not through any sub-milestone-internal failure.

### M4 reopened (per Mike's disposition 2026-05-03)

Per Mike's Part 2 disposition: M4 status updated from closed to
**"reopened — M-level acceptance criterion not met; M-level deliverable
`tools/tasks.py` (six MCP tools) was not picked up by any M4
sub-milestone."**

The reopen is at M-level. Sub-milestone closures (M4a, M4b, M4c-*) are
not affected — each shipped its own scope cleanly. M4d remediates the
inter-sub-milestone scope gap.

Remediation: **M4d-task-management-surface**, scheduled after
M5-continuous-pm-driver and before Phase 1 closeout. Scope matches the
N-I gap exactly: `tools/tasks.py` implementing all six tools per spec at
`06-mcp-tools-phase-one.md:30-122`, MCP wrappers in `server.py`,
hierarchy enforcement per `agents/permissions.py:is_descendant`,
audit-chain integration, integration tests covering the
PM→Engineer→Implementer chain via the production MCP path (not direct
SQL fixtures), and the M4 acceptance demo.

**No part of the M4 task-management surface is in scope of
M5-continuous-pm-driver.**

---

## M5 — Budget & usage tools, window-aware planner, continuous-mode driver

M5 is in-progress. Closed sub-milestones in scan scope per the git log:

- M5-pm-supervisor-base
- M5-wrapper-closure
- M5-record-api-call
- M5-pm-mode-and-window-state
- M5-subscription-routing

M5-continuous-pm-driver is the open sub-milestone (out of scan scope).
M5-flag-flips and M5-acceptance are downstream sub-milestones (not yet
started; not in scan scope).

M5's full M-level acceptance (08-implementation-plan.md:196-205) is
partially verifiable now (sub-milestone-internal acceptance) and
partially deferred to M5 close (end-to-end items requiring the
continuous-mode driver). I separate these.

### M5 sub-milestones — sub-milestone-internal acceptance

#### M5-pm-supervisor-base — I-A

Deliverable: `PMSupervisor` extending `LongRunningSupervisor` with no-op
`_reconstruct_state`; `Settings` flag `enable_pm_supervisor`; main.py
wiring with PM-first shutdown ordering and 90s timeouts; production-shape
tests including no-op invariant.

Verified: `bridge/pm_supervisor.py` exists; `main.py:32` imports
PMSupervisor; `tests/integration/test_pm_supervisor.py` exists.

#### M5-wrapper-closure — I-A

Deliverable per BACKLOG.md M4-accumulated item line 158: five MCP wrappers
in server.py for dispatch lifecycle + BridgeDispatcher construction in
main.py + handler registry + e2e MCP integration tests. Pairs with
M4d-task-management-surface (the M4 dispatch wrappers were the gap that
M5-wrapper-closure closed; M4d will close the parallel task-management
surface).

Verified: `dispatch_implementer` / `dispatch_tester` /
`complete_dispatch` / `get_dispatch_status` / `get_dispatch_result`
wrappers in `server.py:_register_dispatch_initiation_tools` (line 334) +
`_register_dispatch_completion_tools` (line 761) +
`_register_dispatch_query_tools` (line 309 helper call). `main.py` lifespan
constructs `BridgeDispatcher`. `tests/integration/test_dispatch_mcp_wrappers.py`
covers e2e.

#### M5-record-api-call — I-A

Deliverable: `record_api_call` writer + cost computation + audit chain.

Verified: `tools/budget.py:record_api_call` (line 210), MODEL_PRICING
table (line 119), `_compute_cost_usd` (line 173), MCP wrapper in
`_register_budget_tools` (server.py:463).

#### M5-pm-mode-and-window-state — I-A

Deliverable: `pm_mode_log` table (`0003_pm_mode.sql`) + OAuth poller +
`set_pm_mode` MCP tool + `get_window_state` MCP tool + lifespan wiring.

Verified: `storage/sql/0003_pm_mode.sql`; `bridge/oauth_usage.py`;
`tools/pm_mode.py:set_pm_mode` handler; `tools/budget.py:get_window_state`;
main.py wires the OAuth poller; tests cover all paths.

Caveat: addendum framing of pm_mode_log auto-flip writer disposition was
"Reading B (transition-based, not per-message)" per BACKLOG entry +
M5-subscription-routing closeout. Implementation matches disposition.

#### M5-subscription-routing — I-A

Deliverable: 0009 migration + `record_api_call` extension with cohort
columns + `get_token_usage` + `get_burn_rate` + `check_budget_for_task`.

Verified per the only existing closeout doc:
`docs/closeouts/M5-subscription-routing.md` — six commits across the chain,
318→433 tests, ruff/mypy/pytest gates green.

### M5 M-level acceptance — partially deferred

Items in 08-implementation-plan.md:196-205 not yet verifiable until M5
closes:

| Item | Status | Routing |
|---|---|---|
| Daemon auto-flips pm_mode_log on (source='oauth', role='pm') | I-A (M5-pm-mode-and-window-state) | None |
| Continuous-mode driver pauses high-token dispatches when interactive | DEFERRED to M5-continuous-pm-driver | Current sub-milestone |
| Subscription-saturated integration test passes end-to-end | DEFERRED to M5-continuous-pm-driver / M5-flag-flips | Future sub-milestones |
| Engineer compaction production trigger wiring (engineer.yaml prompt) | DEFERRED to M5-flag-flips | Future sub-milestone (BACKLOG entry) |
| MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR flip to true | DEFERRED to M5-flag-flips | Future sub-milestone |

These are not drift; they are documented in-progress work per the M5
sub-milestone breakdown.

**No drift on closed M5 sub-milestones.**

---

## Summary table

### Sub-milestone-internal granularity (every entry I-A)

| Sub-milestone | Status | Routing |
|---|---|---|
| M1 | I-A | None |
| M2 | I-A | None |
| M3 | I-A | None — Mike-approval is operator-side, separate concern |
| M4a (storage + serial dispatch lifecycle) | I-A | None |
| M4b (parallel + merge protocol) | I-A | None |
| M4c-supervisor-base | I-A | None |
| M4c-engineer-supervisor | I-A | None |
| M4c-compaction | I-A | None |
| M4c-tooling | I-A | None |
| M5-pm-supervisor-base | I-A | None |
| M5-wrapper-closure | I-A | None |
| M5-record-api-call | I-A | None |
| M5-pm-mode-and-window-state | I-A | None |
| M5-subscription-routing | I-A | None (formal closeout doc exists) |

### M-level inter-sub-milestone scope

| M | Surface | Status | Routing |
|---|---|---|---|
| M4 | Task-management surface (six MCP tools) | **N-I — inter-sub-milestone scope drift; no sub-milestone took this M-level deliverable as scope** | **M4d-task-management-surface** (per Mike's disposition 2026-05-03) |
| M4 | M-level acceptance criteria 2, 4 (integration test + touch-map E2E) | I-NR at M-level (production-end-to-end blocked by N-I above) | M4d closes the production path |
| M4 | M-level acceptance criteria 5, 6 (double-claim CONFLICT, role-mismatch PERMISSION_DENIED) | UNVERIFIABLE (depends on N-I `claim_task`) | M4d |
| M4 | Checkpoint demo (load-bearing PM→Engineer→Implementer→PM cycle) | Not runnable in production | M4d |
| M5 M-level acceptance | End-to-end items requiring continuous-mode driver | In-progress (not drift) | M5-continuous-pm-driver / M5-flag-flips |

---

## Headline finding

**Drift is localized to one M-level inter-sub-milestone scope gap: the M4
task-management surface (six MCP tools).** Every sub-milestone in scope
shipped its scoped deliverables cleanly (all I-A at sub-milestone-internal
granularity). The N-I gap is a different failure mode: an M-level
deliverable that no sub-milestone took as scope, surviving M4 closure
because no sub-milestone-internal verification could see it.

No additional phantom-surface drift was discovered beyond what F2.1
commit-1 surfaced. M5-continuous-pm-driver's planned scope (per F2.1
chain revised to 9 commits in the grounding doc) is not designing against
further missing infrastructure.

The M4 M-level acceptance criteria that read I-NR-at-M-level (the
integration-test commit + touch-map E2E criteria) are downstream effects
of the N-I gap, not separate drift. M4d remediates by shipping the
seed-task surface; once shipped, the M-level acceptance criteria become
runnable end-to-end. Single sub-milestone closes both the N-I gap and
the I-NR-at-M-level downstream effects.

---

## Cross-cutting observations

### O1 — Closeout convention's adherence is partial-by-construction

The closeout convention from `docs/kickoff/00-README.md:53-72` says
*"When a sub-milestone in M3-M7's chain closes, the close review writes
a canonical artifact at `../closeouts/<sub-milestone-slug>.md`."* Reality:
only `M5-subscription-routing.md` exists. M4 sub-milestones (M4a, M4b,
M4c-supervisor-base, M4c-engineer-supervisor, M4c-compaction, M4c-tooling)
and earlier M5 sub-milestones (M5-pm-supervisor-base, M5-wrapper-closure,
M5-record-api-call, M5-pm-mode-and-window-state) all closed without
separate closeout docs.

This is not drift in the same sense as the M4 task-management surface —
the closeout convention was newly adopted at M5-subscription-routing's
close (the closeout doc itself states *"This closeout's checklist was
constructed ad-hoc during commit 6"*). Earlier closures predate the
convention and so don't violate it. But it's worth noting for the
phase-1 closeout planning: the discipline check the closeout convention
brings was not applied at M4 close, which is part of why M4's
task-management gap survived undetected for 6 weeks.

### O2 — M4 closeout in 08-implementation-plan.md lacks acceptance verification

The M4 trajectory (08-implementation-plan.md:134-149) enumerates eight
shipped commits across four sub-milestones, plus accumulated BACKLOG
items (lines 151-161). It does NOT iterate the M4 acceptance criteria
from lines 122-128 against shipped reality. Had it done so, the
task-management gap would have surfaced at M4 close.

This is the per-milestone analog of the phase-boundary scan convention
Mike's Part 3 disposition formalizes. The pattern: enumerate acceptance
criteria explicitly at close; verify each against shipped state; route
unmet criteria to remediation or document as accepted carry-forward.

### O3 — Phase-boundary scan convention catches what sub-milestone closeouts cannot

The M4 task-management surface was outside any sub-milestone's scope
after M4 closed. Sub-milestone closeouts catch sub-milestone-internal
drift; phase-boundary scans catch drift at the milestone-or-phase level.

Mike's Part 3 disposition formalizes this convention. The convention
applies to Phase 1 closeout next, and to any future Phase boundary.

---

## Status of the scan

**One drift finding: M4 task-management surface (six MCP tools absent;
production load-bearing demo not runnable).**

Routing: M4d-task-management-surface, scheduled after M5-continuous-pm-driver
and before Phase 1 closeout.

No further drift discovered. M5-continuous-pm-driver may proceed under
the revised 9-commit chain per the grounding doc's F2.1 update.

**End of scan.**
