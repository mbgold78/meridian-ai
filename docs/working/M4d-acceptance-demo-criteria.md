# M4d Acceptance Demo — Gate Criteria

Pre-execution gate criteria for M4d's acceptance demo run at commit 9.
Per Q6 commit 3 acceptance disposition (M4d-task-management-surface,
2026-05-07): both α (synthetic) and β (substrate) demos run same day.
Each criterion is **binary pass/fail** — gate-condition framing makes
this an operational checklist Mike runs against during commit 9 demo
execution; narrative framing would leave criteria interpretation-
dependent.

Each criterion is grep-able and auditable post-execution.

## α — Synthetic acceptance demo

Mechanical-bug verification. Removes substrate complexity as confound;
exercises M4d task-tool surfaces in isolation against a fixture-built
scenario. Run via the integration test path
`tests/integration/test_pm_engineer_implementer_chain.py` plus manual
operator inspection of audit_log.

| # | Criterion | Verification command / inspection |
|---|---|---|
| α1 | Full state-transition cycle completes without raising; final `task.status = 'completed'` | Run `pytest tests/integration/test_pm_engineer_implementer_chain.py::test_full_cycle_oauth_submit_static_claim_status_lifecycle`; assert exit 0 |
| α2 | Double-claim raises `CONFLICT` with `ConflictError` class | Run `pytest tests/integration/test_pm_engineer_implementer_chain.py::test_double_claim_within_cycle_raises_conflict`; assert exit 0 |
| α3 | Role-mismatch claim raises `PERMISSION_DENIED` with `OrchestratorPermissionError` class | Run `pytest tests/integration/test_claim_task_mcp_wrapper.py::test_implementer_cannot_claim_tester_task`; assert exit 0 |
| α4 | Audit chain queryable for the cycle (action='task.submit' / 'task.claim' / 'task.status_change' all present in audit_log for the task_id) | `SELECT action FROM audit_log WHERE target = '<task_id>' ORDER BY rowid;` returns ≥4 rows including all three actions |
| α5 | Cohort coverage `workflow_id` populates from chain root via record_api_call's α-denormalization | `SELECT workflow_id FROM api_usage WHERE id = '<usage_id>'` returns the chain-root task_id (NOT NULL when bound dispatch exists) |
| α6 | Cohort coverage `task_type` populates from chain root via record_api_call's α-denormalization (commit 7 symmetric extension) | `SELECT task_type FROM api_usage WHERE id = '<usage_id>'` returns chain-root task_type (NOT NULL when chain-root has explicit task_type) |
| α7 | Decline propagation via `update_task_status('declined')` succeeds; audit captures `from_status='claimed'`, `to_status='declined'` | Run `pytest tests/integration/test_pm_engineer_implementer_chain.py::test_decline_propagation_via_update_task_status`; assert exit 0 |

**α gate**: all 7 criteria PASS → α-demo passes; M4d wire-shape verified end-to-end via production MCP path. Failure on any single criterion blocks β-demo execution; surface-and-disposition discipline applies.

## β — Substrate acceptance demo

Real Mike-substrate task. Validates the full PM→Engineer→Implementer→Engineer→PM cycle (M4 acceptance line 132 + M4d acceptance lines 253-255) against real complexity. Run via Mike's interactive Claude Code session against a substrate-clone working directory.

Pre-conditions before β-demo:
- α gate PASSED (all 7 criteria)
- Daemon running with Engineer subprocess flag flipped on (`MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true`) — note this is M5-flag-flips territory; if not yet flipped, β-demo runs against direct dispatch handler call rather than supervisor-managed subprocess
- Substrate clone provisioned at `Settings.substrate_working_directory` (per `seed.py`); clean working tree
- Mike's Claude Code session connected via OAuth-PM token

| # | Criterion | Verification command / inspection |
|---|---|---|
| β1 | Mike's `submit_task` call succeeds via OAuth path; `submitted_by='human:mike'` in tasks table | `SELECT submitted_by FROM tasks WHERE id = '<task_id>'` returns `'human:mike'` |
| β2 | PM submits subtask to Engineer (caller_source='static' if PM-as-agent, OR caller_source='oauth' if Mike-via-PM-direct); subtask's `parent_task_id` references the root task_id | `SELECT parent_task_id FROM tasks WHERE id = '<subtask_id>'` returns the root task_id |
| β3 | Engineer claims subtask; status flips pending → claimed; `claimed_by` matches Engineer's agent_id | `SELECT status, claimed_by FROM tasks WHERE id = '<subtask_id>'` returns `('claimed', '<engineer_agent_id>')` |
| β4 | Engineer's `dispatch_implementer` call succeeds; subprocess spawns; subprocess returns exit 0 (or terminal status from the dispatcher's awaited result) | `SELECT status FROM dispatches WHERE id = '<dispatch_id>'` returns `'completed'` (or `'failed'` / `'declined'` for those scenarios) |
| β5 | Substrate clone has new commit SHA after subprocess completes; commit message contains `task_id` reference (or sub-agent's standardized commit-message format) | `git log -1 --format=%H%n%s` in substrate working tree; SHA differs from pre-demo HEAD; commit message contains `task_id` substring |
| β6 | Engineer marks subtask completed via `update_task_status('completed')`; `actual_tokens` populated on subtask row | `SELECT status, actual_tokens FROM tasks WHERE id = '<subtask_id>'` returns `('completed', <non-null int>)` |
| β7 | PM observes outcome via `get_queue` filtered to `status='completed'`; subtask appears in result set | Mike calls `get_queue(status=['completed'])` from interactive Claude Code session; subtask in returned `tasks` list |
| β8 | Full audit chain present: `task.submit` (root) + `task.submit` (subtask) + `task.claim` + `dispatch.issued` + `dispatch.complete_called` + `task.status_change` + ... all queryable via `get_audit_log` (M6 deliverable; if M6 not shipped, direct SQL acceptable) | `SELECT action FROM audit_log WHERE target IN (<root_id>, <subtask_id>, <dispatch_id>) ORDER BY rowid` returns ≥6 rows covering all listed actions |

**β gate**: all 8 criteria PASS → β-demo passes; M4d acceptance demo complete; M4 acceptance criteria (08-implementation-plan.md:122-128) met end-to-end via production MCP path; load-bearing demo runs without manual SQL.

## Failure handling

If α-gate FAILS at any criterion:
- Block β-demo execution
- Surface the failing criterion + observed state via prep-checkpoint contradiction discipline
- Disposition: fix in commit 8.5 (hygiene fix) OR mark M4d closeout with explicit unresolved-acceptance flag (per phase-1-acceptance-drift.md precedent)

If β-gate FAILS at any criterion:
- Document failure in M4d closeout doc (commit 9) under `## Drift between kickoff scope and landed scope`
- Route to M5-flag-flips OR M5-acceptance OR a new M4d.1 sub-milestone per Mike's disposition
- M4d closeout still ships per Convention 1; remediation is forward-looking

## Cross-references

- **Spec home**: `docs/kickoff/08-implementation-plan.md:249-255` (M4d Deliverables) + `:259-264` (M4d Acceptance) + `:122-128` (M4 Acceptance criteria, met end-to-end via M4d)
- **Phase-1 ceremony**: NOT triggered by M4d; M4d acceptance is M4d-acceptance-only per status-dump (post-commit-6, 2026-05-07). Phase-1 ceremony at M7 checkpoint per `08-implementation-plan.md:405`.
- **Drift-scan precedent**: `docs/working/phase-1-acceptance-drift.md` (M4 reopened 2026-05-03 finding).
