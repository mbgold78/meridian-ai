# Engineer ↔ Subagent Bridge

**Status: APPROVED (M3 design). Awaiting Mike's explicit sign-off in `08-implementation-plan.md` § M3 checkpoint before M4 implementation begins.**

This document was a stub during the kickoff package; M3 finalizes it. The constraints are unchanged from the stub except where M2 made them concrete (see Constraint 10). The decision sections, lifecycle spec, sequence diagrams, storage shape, and timeout behavior are all new at M3.

## Decision summary

- **Subprocess model:** **Option A — Claude Code subprocess.** Subscription-inherited, parallel-friendly, the natural fit for the Phase 1 cost model. Other options were considered and rejected; rationale below.
- **Result delivery:** **Path 3 — MCP-tool completion.** Sub-agent calls `complete_dispatch(...)` to deliver structured output through the same MCP channel it already uses for `record_api_call`. No stdout parsing.
- **Auth:** Per-dispatch static token issued via the keyring `TokenStore` (the dual-source auth shipped at M2). Token is `source="static"`, scoped to the dispatch's role, revoked on terminal state.
- **Working-directory isolation:** Per-dispatch `git worktree` against per-dispatch branch (`dispatch/<dispatch_id>`) under a daemon-owned tree, separate from Mike's manual working tree. Merge protocol surfaces conflicts to Engineer → PM; PM escalates to Mike for manual resolution in Phase 1.
- **Continuous-mode PM:** Same subprocess infrastructure (Claude Code subprocess, static MCP token, structured output via tool calls). Lifecycle owner is the daemon's `PMSupervisor`, not Engineer. Coordinates with the interactive PM (Mike's Claude Code session) via `pm_mode_log` reads on each planning iteration.

## Constraints (load-bearing)

A working bridge must satisfy all of:

1. **Different working directory.** Engineer's logical context is `meridian-ai/orchestrator/`; the dispatched sub-agent's working directory is `meridian-homepoc/`. This path is parameterized in config so the eventual merge (`13-merge-plan.md`) is a config edit, not a code change.

2. **Full filesystem capability for the sub-agent.** Read, write, exec in its working directory. The substrate-codebase work needs Edit, Write, Bash, Glob, Grep, Read at minimum. Tester additionally needs Playwright MCP tools (browser_navigate, snapshot, evaluate, console_messages, network_requests, wait_for, take_screenshot, close).

3. **Structured output Engineer can consume.** Commit hash (or null if no commit), test pass/fail with details, queue items raised, decline reason if declined, list of artifacts. Delivered via the `complete_dispatch` MCP tool (see § Result delivery), not stdout parsing.

4. **No manual intervention once dispatched.** Engineer dispatches → bridge executes → Engineer polls or awaits → result returned. Mike does not click anything in the middle.

5. **Subscription-compatible by default.** Phase 1 runs on Mike's Claude Pro/Max subscription. The bridge inherits that subscription via Claude Code's existing auth state. API credits remain available for overflow but are not the default path.

6. **Graceful rate-limit handling.** Log the event, surface to PM, surface to Mike via session report. Do not silently retry.

7. **Parallel concurrent dispatch — Phase 1, not Phase 2.** Per the utilization addendum, Engineer dispatches multiple sub-agents in *parallel* in Phase 1. Two phases touching different substrate files dispatch simultaneously; two phases touching the same files must serialize. The bridge provides:
   - Isolated working-directory checkouts per concurrent dispatch (per-dispatch git worktrees against per-dispatch branches)
   - Merge logic that lands parallel branches back into the canonical working tree, including conflict detection
   - A dispatch-graph data structure Engineer uses to plan parallel vs. serial execution

8. **Audit chain preservation.** Every dispatch produces audit log entries that name the parent task and the dispatch_id, so Mike can reconstruct who-did-what-on-whose-behalf via `get_audit_log`. The full per-dispatch lifecycle audit set is enumerated in § Audit log entries.

9. **Decline propagation.** A sub-agent's decline must surface to Engineer with reason intact, and Engineer must surface it to PM. The bridge cannot silently swallow declines.

10. **Continuous-mode PM driving.** The bridge architecture also governs how the daemon launches and supervises the long-running PM Claude Code subprocess that runs PM-continuous when Mike is not interactive. This is the same mechanism family as sub-agent dispatch — a Claude Code subprocess driven by the daemon — but with different lifecycle semantics:
    - Long-lived (until Mike opens his interactive Claude Code session, or until daemon shutdown), not per-task
    - Reads `pm_mode_log` on each planning iteration to detect Mike's presence and pause dispatch when interactive
    - Reads the task queue and `get_window_state` continuously to drive its own dispatch loop
    - Coordinates with the interactive PM (Mike's Claude Code session, when active) via the database — both PM instances share the same system prompt and MCP access; they don't communicate directly

    Sub-agent dispatch and continuous-mode PM driving share infrastructure (subprocess launching, working-directory provisioning, MCP-token issuance, structured-output capture) but have different lifecycle managers (`BridgeDispatcher` for the former, `PMSupervisor` for the latter).

## Candidate architectures (decision rationale)

### Option A: Subprocess Claude Code — **CHOSEN**

Engineer (or `PMSupervisor` for continuous-mode) launches a Claude Code subprocess pointed at the substrate working directory. Spec passed via `claude -p "<spec>"` argument or stdin. Subprocess inherits Claude Code's auth (Mike's subscription). Structured output delivered via the new `complete_dispatch` MCP tool — **not** via stdout parsing.

- **Pros:** Subscription-inherited. Sub-agent has full Claude Code tooling (Edit, Write, Bash, Glob, Grep, Read) for free. Parallel dispatch is straightforward (concurrent subprocesses with isolated worktrees). Continuous-mode PM uses the same primitive.
- **Cons (now resolved):** Process lifecycle management is real but bounded — `Process.launch`/`Process.wait_for` patterns are standard. The historical "stdout parsing fragility" concern is *eliminated* by Path 3 (MCP-tool completion); see § Result delivery.
- **Phase-2 fitness:** Concurrent subprocesses work; the model is straightforward.

### Option B: MCP-to-MCP — rejected

Engineer exposes Implementer/Tester as nested MCP services. Cleanly typed in principle but requires significant glue code; Claude Code's subagent invocation is not designed to be driven by an external MCP client; subscription inheritance is unclear.

### Option C: File-based handoff — rejected

Engineer writes spec files; a polling Claude Code instance picks them up. Adds latency, requires a dedicated polling instance, and Path 3's MCP-tool completion gives most of the same crash-recovery properties (every state transition is audit-logged) without the polling overhead.

### Option D: Direct Anthropic API calls — rejected

Burns API credits instead of using Mike's subscription. Disqualified by the Phase 1 cost model. Re-implementing Claude Code's filesystem and Playwright tooling is also nontrivial.

## Architecture: Option A + MCP-tool completion

### Result delivery: the `complete_dispatch` MCP tool

This is the load-bearing implementation decision. Rather than parse free-form stdout from the Claude Code subprocess, the sub-agent **calls back into the orchestrator** via a new MCP tool to deliver its structured result. The subprocess is already an MCP client (it has a Bearer token for `record_api_call`); adding `complete_dispatch` to that connection costs nothing.

```python
class CompleteDispatchInput(BaseModel):
    dispatch_id: str
    status: Literal["completed", "declined", "failed"]
    commit_hash: str | None = None
    test_results: dict | None = None         # Tester only
    queue_items_raised: list[str] = []       # spec or follow-up items the dispatch surfaced
    decline_reason: str | None = None        # required when status == "declined"
    artifacts: list[str] = []                # paths inside working_directory
    notes: str | None = None

class CompleteDispatchOutput(BaseModel):
    accepted: bool
    next_action: Literal["terminate", "wait"]   # daemon-driven; "wait" reserved for future
```

**Allowed for:** Implementer, Tester. The continuous-mode PM does **not** call this tool — it stays alive across iterations and signals state via `set_pm_mode` and `submit_task`.

**Audit:** action `dispatch.complete_called`. `details_json` includes the full input.

**Required state:** `dispatch_id` must match an active dispatch, the caller's resolved `agent_id` must match the dispatch's expected role, and the dispatch must not already be in a terminal state. Otherwise: `CONFLICT`.

### Auth path: per-dispatch static token

Each dispatch gets its own keyring-backed token. Lifecycle:

1. `BridgeDispatcher` (or `PMSupervisor`) calls `TokenStore.issue(agent_id=<role-uuid>, role=<role>, label=f"dispatch:{dispatch_id}", source="static")`. The returned `mor_<id>_<secret>` token string is held in memory only — never written to disk except via the keyring's hashed record.
2. The token (full string) is passed to the subprocess via env var `MERIDIAN_ORCHESTRATOR_TOKEN`. `MERIDIAN_ORCHESTRATOR_DISPATCH_ID` is also set.
3. The subprocess's MCP client (configured via the Claude Code project config injected by the daemon) authenticates as Bearer `<token>`. The orchestrator's `OAuthAuthorizationServerProvider.load_access_token` resolves it (the M2 dual-source auth covers both OAuth and static tokens through the same lookup).
4. On terminal state (any of `completed`, `declined`, `failed`, `timed_out`, `cancelled`), `TokenStore.revoke(token_id)` is called immediately. Future MCP calls from a still-running subprocess fail with 401.
5. Audit `dispatch.token_created` on issuance, `dispatch.token_revoked` on revocation.

This design intentionally does **not** issue OAuth-flow tokens for dispatches. Dispatched subprocesses have no operator at the keyboard to walk through an approval; they're pre-authorized by the dispatch act itself.

### Working-directory model

Per-dispatch worktrees prevent concurrent subprocesses from stomping each other.

- **Worktree path:** `<MERIDIAN_ORCH_DATA_DIR>/dispatch-worktrees/<dispatch_id>/`
- **Branch:** `dispatch/<dispatch_id>`, branched from the substrate's main HEAD at dispatch time (`git -C <substrate> worktree add -b dispatch/<id> <worktree_path>`)
- **Substrate path:** read from `projects.working_directory` for the project the parent task belongs to; defaults to `C:\Users\mbgol\dev\meridian-homepoc\` per the M1 seed
- **Subprocess cwd:** the worktree path, not the substrate's primary working tree — keeps Mike's manual work uncontaminated
- **Merge protocol on completion:** daemon attempts `git merge --ff-only dispatch/<id>` against substrate main. On conflict (overlap with main commits since dispatch start), daemon records `merge_conflict=true` on the dispatch result. Engineer surfaces to PM; PM decides whether to re-dispatch with rebased base or escalate to Mike via `notify_human(urgency="question")`. Phase 1 has no automated conflict resolution.
- **Cleanup on terminal state:** worktree removed (`git worktree remove --force`) and branch deleted unless `keep_worktree_on_failure` is set (debug aid). Default is to clean up regardless of outcome.

### Lifecycle state machine

```
            ┌─────────┐
            │ queued  │  ── daemon issued token, subprocess not yet started
            └────┬────┘
                 │ Process.launch
                 ▼
            ┌─────────┐
            │ running │  ── subprocess started; MCP-authenticated on first call
            └────┬────┘
        ┌────────┼────────┬───────────┬────────────┐
        │        │        │           │            │
        ▼        ▼        ▼           ▼            ▼
  ┌──────────┐┌────────┐┌────────┐┌──────────┐┌───────────┐
  │completed ││declined││ failed ││timed_out ││ cancelled │
  └──────────┘└────────┘└────────┘└──────────┘└───────────┘

terminal states (subprocess reaped, token revoked, worktree cleaned):
  - completed:  subprocess called complete_dispatch(status='completed')
  - declined:   subprocess called complete_dispatch(status='declined', decline_reason=...)
  - failed:     subprocess called complete_dispatch(status='failed') OR exited nonzero
                without calling complete_dispatch
  - timed_out:  hard timeout fired; daemon SIGTERM'd subprocess
  - cancelled:  Engineer or PM cancelled mid-flight (M5+ surface; reserved at M4)
```

Engineer polls `get_dispatch_status` and `get_dispatch_result` to drive its own task progression; it does not block awaiting subprocess exit.

### Timeout and abandonment

Dispatched subprocesses can hang for non-failure reasons (slow tool calls, Playwright wait_for, model latency). The bridge spec is explicit so the failure tail is bounded.

#### Hard timeout calculation

```
hard_timeout_seconds = max(estimated_tokens / model_throughput_tps, 300)
```

- `estimated_tokens` is `tasks.estimated_tokens` set by PM at submit time (column added in migration `0002_task_planning.sql` per the M4 deliverables).
- `model_throughput_tps` is a per-model constant in `bridge/timeouts.py`. Initial table:
  - `claude-haiku-4-5`: 100 tps
  - `claude-sonnet-4-6`: 60 tps
  - `claude-opus-4-7`: 30 tps
- `300` is a 5-minute floor — even tiny dispatches get five minutes to start up, authenticate, and call `complete_dispatch`.
- Configurable per-dispatch via an optional `timeout_override_seconds` field on `dispatch_implementer` / `dispatch_tester` for cases where Engineer knows a specific dispatch will run long (e.g., a Playwright suite that legitimately takes 20 minutes).

#### Soft warning

If 120 seconds elapse since the dispatch's last `record_api_call` (`dispatches.last_activity_at`) **and** `actual_tokens_so_far < estimated_tokens * 0.8`, log `dispatch.soft_warning` with the dispatch_id, idle seconds, and remaining budget. No process action; this is observability for Engineer and PM to consult via `get_dispatch_status`.

The `0.8` threshold prevents soft warnings during legitimate end-of-dispatch quiescence (subprocess wrapping up, last tool call finished, working on `complete_dispatch` payload).

#### Hard timeout behavior

On `hard_timeout_seconds` elapsed without `complete_dispatch` having been called:

1. Audit `dispatch.timeout` with elapsed seconds and the count of `record_api_call` events seen.
2. SIGTERM the subprocess. Give it a 10-second grace period to clean up.
3. If still alive: SIGKILL.
4. Update the dispatch row to state `timed_out` (distinct from `failed` and `declined`).
5. `TokenStore.revoke(token_id)`. Audit `dispatch.token_revoked` with reason `timeout`.
6. Audit `dispatch.subprocess_terminated` with exit code (or signal) and elapsed time.
7. `git worktree remove --force` on the dispatch worktree.

#### Engineer's response to a timeout

`get_dispatch_status` returns `timed_out`. Engineer's response:

- `update_task_status(task_id, 'failed', notes=f"dispatch {id} timed out at {elapsed}s")` on the parent task
- Surface to PM via the task chain. PM's queue poll picks up the failed task; PM decides whether to re-submit with adjusted parameters (longer timeout, smaller spec, different model tier) or escalate to Mike via `notify_human(urgency='question', related_task_id=...)`.

**Engineer does NOT auto-retry timed-out dispatches.** This is a design choice. Auto-retry creates loops in the failure tail (e.g., a misconfigured working tree, a substrate file in a bad state, a flaky external dependency), and PM has the situational context to make the resume/escalate call. M4's tests assert this — a forced timeout produces exactly one dispatch attempt.

### Audit log entries

Per-dispatch lifecycle writes the following `audit_log` rows. All rows include `dispatch_id` in `details_json`; per-event additional fields listed below.

| Action | When | Additional `details_json` fields |
|---|---|---|
| `dispatch.issued` | Engineer's `dispatch_*` MCP call validates and persists the dispatch row | `task_id`, `role`, `working_directory`, `spec_path`, `timeout_seconds` |
| `dispatch.token_created` | `TokenStore.issue` returns | `token_id` (never the secret) |
| `dispatch.subprocess_started` | `Process.launch` returns | `pid`, `command` (redacted of token), `cwd` |
| `dispatch.mcp_authenticated` | First authenticated MCP call from the subprocess lands | `agent_id`, `first_tool_called` |
| `dispatch.last_activity` | Each `record_api_call` from the dispatch | `model`, `input_tokens`, `output_tokens` |
| `dispatch.soft_warning` | 2 minutes since last activity, budget remaining | `idle_seconds`, `tokens_used_so_far`, `tokens_estimated` |
| `dispatch.complete_called` | Subprocess calls `complete_dispatch` | full `CompleteDispatchInput` payload |
| `dispatch.timeout` | Hard timeout fires | `elapsed_seconds`, `record_api_call_count` |
| `dispatch.token_revoked` | `TokenStore.revoke` returns | `token_id`, `reason` (`completed`, `declined`, `failed`, `timeout`, `cancelled`) |
| `dispatch.subprocess_terminated` | Process exit captured | `exit_code`, `signal`, `total_elapsed_seconds` |
| `dispatch.merge_conflict` | Worktree merge into main fails | `branch`, `conflicting_files` |

The chain from `task.submit` → `task.claim` → `dispatch.issued` → ... → `dispatch.subprocess_terminated` → `task.status_change` is the load-bearing audit trail Mike inspects via `get_audit_log` to reconstruct the cycle.

### Storage shape

A new `dispatches` table ships at M4. This is *not* an `ALTER TABLE tasks` extension because dispatches have multiple-per-task semantics (one task can spawn multiple dispatches across retries or staged execution).

```sql
-- Migration 0005_dispatches.sql at M4 (numbering after 0001-0004 already planned)

CREATE TABLE dispatches (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id),
    role TEXT NOT NULL,                          -- 'implementer' | 'tester' | 'pm-continuous'
    status TEXT NOT NULL DEFAULT 'queued',       -- queued | running | completed | declined | failed | timed_out | cancelled
    working_directory TEXT NOT NULL,
    spec_path TEXT,
    commit_hash TEXT,
    decline_reason TEXT,
    estimated_tokens INTEGER,
    actual_tokens INTEGER,
    timeout_seconds INTEGER NOT NULL,
    token_id TEXT,                               -- the keyring token_id (NEVER the secret)
    subprocess_pid INTEGER,
    issued_at TEXT NOT NULL DEFAULT (datetime('now')),
    started_at TEXT,
    last_activity_at TEXT,
    completed_at TEXT,
    timed_out_at TEXT,
    artifacts_json TEXT,
    test_results_json TEXT,
    queue_items_raised_json TEXT,
    notes TEXT
);

CREATE INDEX idx_dispatches_task ON dispatches(task_id);
CREATE INDEX idx_dispatches_status ON dispatches(status);
CREATE INDEX idx_dispatches_role_status ON dispatches(role, status);   -- continuous-mode PM lookup
```

The `token_id` column stores the keyring `token_id` (the short hex prefix), not the full token string. The full token never persists to SQLite — only to keyring's hashed record.

### Continuous-mode PM specifics

The continuous-mode PM uses the same subprocess infrastructure with different lifecycle semantics:

| Aspect | Sub-agent dispatch (Engineer-driven) | Continuous-mode PM (PMSupervisor-driven) |
|---|---|---|
| Lifecycle owner | `bridge.dispatcher.BridgeDispatcher` | `bridge.pm_supervisor.PMSupervisor` |
| Subprocess command | `claude -p <spec>` | `claude -p <pm_continuous_prompt>` |
| Working directory | Per-dispatch git worktree under data dir | `<MERIDIAN_ORCH_DATA_DIR>/pm-continuous/` (no git, no worktree) |
| Token role | `implementer` or `tester` | `pm` (with `label="pm-continuous"` to distinguish from interactive PM tokens) |
| Hard timeout | `max(estimated_tokens / tps, 300s)` per dispatch | None — long-lived |
| Result delivery | `complete_dispatch` MCP tool | No completion call. PM stays alive; signals state via `set_pm_mode` and enqueues work via `submit_task` |
| Termination | On `complete_dispatch` or hard timeout | Daemon shutdown, or admin command |
| Coordination | None with other dispatches | Reads `pm_mode_log` on each iteration to detect Mike's interactive presence; pauses dispatch when mode is `interactive` |
| Audit row source | `dispatches` table | `dispatches` table with `role='pm-continuous'`, but most state is in `pm_mode_log` and the task queue |

Engineer does **not** dispatch the continuous PM and does **not** directly coordinate with it. The two communicate exclusively through the database — task queue, `pm_mode_log`, and audit log. Engineer reads the queue and claims tasks regardless of which PM instance enqueued them; the continuous PM submits tasks the same way the interactive PM does.

## Sequence diagrams

### 1. Normal dispatch (happy path)

```
PM                  Engineer            Daemon                Subprocess        Substrate worktree
 │                    │                   │                       │                    │
 │ submit_task ─────► │                   │                       │                    │
 │                    │ claim_task ──────►│ (status=claimed)      │                    │
 │                    │ dispatch_implementer(task_id, spec, cwd)─►│                    │
 │                    │                   │ TokenStore.issue ─────│ [audit token_created]
 │                    │                   │ git worktree add ─────┼───────────────────►│ (branch dispatch/<id>)
 │                    │                   │ Process.launch ──────►│ (subprocess starts)│
 │                    │                   │ [audit subprocess_started]
 │                    │                   │                       │                    │
 │                    │                   │◄── record_api_call ───│ (model call 1)
 │                    │                   │ [audit mcp_authenticated, last_activity]
 │                    │                   │                       │ (subprocess works) │
 │                    │                   │◄── record_api_call ───│ (model call N)
 │                    │                   │                       │                    │
 │                    │                   │◄── complete_dispatch(commit_hash, ...) ────│
 │                    │                   │ [audit complete_called]
 │                    │                   │ TokenStore.revoke ────│ [audit token_revoked]
 │                    │                   │ git merge --ff-only ──┼───────────────────►│ (lands in main)
 │                    │                   │ Process.terminate ───►│ [audit subprocess_terminated]
 │                    │                   │ git worktree remove ──┼───────────────────►│ (cleaned)
 │                    │                   │                       │                    │
 │                    │ get_dispatch_status (running → completed) │                    │
 │                    │ get_dispatch_result                       │                    │
 │                    │ update_task_status(completed, notes) ────►│                    │
 │                    │                   │                       │                    │
 │ (next queue poll) ►│ sees task completed                       │                    │
```

### 2. Parallel dispatch (Engineer running two subprocesses concurrently)

```
Engineer's dispatch graph: task_A (file foo.py) and task_B (file bar.py) — disjoint files
Engineer makes both dispatches in parallel:

PM ─► submit_task(task_A) ─► submit_task(task_B)
Engineer claims both, then:

  Engineer ─► dispatch_implementer(task_A) ─► daemon: token_A, worktree wt_A, subproc_A
  Engineer ─► dispatch_implementer(task_B) ─► daemon: token_B, worktree wt_B, subproc_B

[ subproc_A and subproc_B run concurrently, each in its own worktree, each holding its own token ]
[ each makes its own record_api_call events; each calls complete_dispatch when done ]

  subproc_A ─► complete_dispatch(commit_hash_A, status='completed', ...)
  subproc_B ─► complete_dispatch(commit_hash_B, status='completed', ...)

Daemon merges in completion order:
  git merge --ff-only dispatch/A → succeeds (foo.py only)
  git merge --ff-only dispatch/B → succeeds (bar.py only; no overlap)

If the dispatch graph was wrong and B did touch foo.py:
  git merge --ff-only dispatch/B → conflict
  Daemon records dispatch_B.merge_conflict=true; conflicting_files=['foo.py']
  [audit dispatch.merge_conflict for dispatch_B]
  Engineer's get_dispatch_result returns merge_conflict
  Engineer ─► update_task_status(task_B, 'failed', notes='merge conflict on foo.py')
  PM decides: re-dispatch task_B serialized after task_A's merge, or escalate
```

### 3. Decline propagation

```
PM ─► submit_task(task_X) ─► Engineer claims ─► Engineer.dispatch_implementer(task_X)
Daemon: token_X, worktree wt_X, subproc_X

subproc_X reads spec, reads foundation doc via read_knowledge,
detects the spec contradicts the foundation (e.g., asks for behavior the
foundation explicitly forbids).

subproc_X ─► complete_dispatch(
                status='declined',
                decline_reason='Spec asks for inline mutation of input prop, '
                               'but foundation §UI-7 requires immutable inputs.'
             )

Daemon: dispatch state → declined
[audit complete_called with full decline payload]
TokenStore.revoke; subprocess terminated; worktree cleaned

Engineer's get_dispatch_status returns declined
Engineer ─► update_task_status(task_X, 'declined', notes=<decline_reason>)
[task.status_change records the propagation, parent_task_id ties it to PM's submission]

PM's queue poll: task_X declined
PM decides:
  - clarify spec & re-submit with adjusted requirements
  - or escalate to Mike via notify_human(urgency='question', related_task_id=task_X)
PM does NOT auto-resubmit identical specs.
```

### 4. Continuous-mode PM driving

```
[Daemon startup]
PMSupervisor.launch:
  TokenStore.issue(role='pm', label='pm-continuous', source='static') → token_PM
  Process.launch: claude -p <pm_continuous_system_prompt> --cwd <data_dir>/pm-continuous/
    env: MERIDIAN_ORCHESTRATOR_TOKEN=<token_PM>, MERIDIAN_ORCHESTRATOR_DISPATCH_ID=pm-continuous

[Continuous PM subprocess loop]
  while not shutdown:
    state = get_window_state()
    pace = state.pace_recommendation                   # normal | upgrade | conserve | rate_limited
    inbound = get_unread_replies()                     # Mike's Telegram replies (M6+)
    mode = (most recent row in pm_mode_log).mode       # interactive | continuous | paused_*
    if mode == 'interactive':
      sleep N seconds; continue                        # Mike has the window; back off
    if pace == 'conserve':
      finish in-flight only; no new dispatches
    queue = get_queue(status=['pending'], agent_role='engineer')
    work_item = pick highest-priority dispatchable
    submit_task(...) to enqueue an Engineer claim, or set_pm_mode('paused_blocked', ...) if nothing dispatchable
    record_api_call after model interactions
    sleep until next iteration

[Mike opens his interactive Claude Code session]
  Mike's first MCP call to the orchestrator:
    Daemon's auth context detects Mike's token (role=pm, label!='pm-continuous')
    Daemon writes pm_mode_log: mode='interactive', triggered_by='mike_message'
  Continuous PM's next loop iteration: reads pm_mode_log, sees interactive, pauses dispatch

[Mike's session ends, 30 minutes elapse without his MCP token activity]
  Daemon writes pm_mode_log: mode='continuous', triggered_by='mike_inactivity_timeout'
  Continuous PM resumes dispatch on next iteration

[Daemon shutdown]
  PMSupervisor.terminate:
    SIGTERM continuous PM subprocess; 10s grace; SIGKILL if alive
    TokenStore.revoke(token_PM)
    [audit subprocess_terminated, token_revoked]
```

### 5. Timeout / abandonment

```
PM ─► submit_task(task_T, estimated_tokens=8000)
Engineer claims, Engineer.dispatch_tester(task_T, ...)
Daemon: token_T, worktree wt_T, subproc_T
  hard_timeout_seconds = max(8000 / 60_tps, 300) = 300s     # 5-minute floor wins (sonnet)
  [audit dispatch.issued with timeout_seconds=300]

subproc_T does some work (record_api_call events flow in)
[audit last_activity at each call]

Subprocess hangs (e.g., Playwright wait_for on an element that never renders):
  120 seconds since last record_api_call AND actual_tokens_so_far < 6400
  [audit dispatch.soft_warning idle_seconds=120 tokens_used=2100 tokens_estimated=8000]
  No process action; Engineer can read this via get_dispatch_status

300 seconds elapsed since dispatch.subprocess_started, no complete_dispatch call:
  [audit dispatch.timeout elapsed_seconds=300 record_api_call_count=4]
  Daemon: SIGTERM subproc_T
  Daemon: 10s grace period → subprocess does not exit cleanly
  Daemon: SIGKILL
  Daemon: dispatch.status = 'timed_out'; timed_out_at = now()
  TokenStore.revoke(token_T)  [audit dispatch.token_revoked reason='timeout']
  [audit dispatch.subprocess_terminated exit_code=-9 (SIGKILL) total_elapsed=310s]
  git worktree remove --force wt_T

Engineer's get_dispatch_status returns timed_out:
  Engineer ─► update_task_status(task_T, 'failed', notes='dispatch timed out at 300s; 4 record_api_call events seen')
  [audit task.status_change with parent_task_id chain preserved]

PM's queue poll: task_T failed
PM decides:
  - Re-submit task_T with timeout_override_seconds=900 (longer Playwright window)
  - Or escalate: notify_human(urgency='question', subject='Tester timeout', body='task_T timed out at 300s after 4 model calls; suspect Playwright wait_for hang. Re-dispatch with longer timeout, or investigate the test target?', related_task_id=task_T)

Engineer does NOT auto-retry. PM's choice is the design.
```

## M4 implementation plan (preview — actual ship plan in `08-implementation-plan.md`)

The M4 milestone implements the bridge per this spec. Concrete deliverables:

- `src/meridian_orchestrator/bridge/__init__.py`
- `src/meridian_orchestrator/bridge/dispatcher.py` — `BridgeDispatcher` class for Engineer-driven dispatches
- `src/meridian_orchestrator/bridge/pm_supervisor.py` — `PMSupervisor` class for continuous-mode PM lifecycle
- `src/meridian_orchestrator/bridge/worktree.py` — git worktree provisioning + merge protocol
- `src/meridian_orchestrator/bridge/timeouts.py` — `model_throughput_tps` table + timeout calculation
- `src/meridian_orchestrator/storage/sql/0005_dispatches.sql` — schema per § Storage shape
- `src/meridian_orchestrator/tools/dispatch.py` — `dispatch_implementer`, `dispatch_tester`, `get_dispatch_status`, `get_dispatch_result`, **and the new `complete_dispatch` tool**
- Tests:
  - Unit: timeout calculation, state machine transitions, decline propagation, audit log emission, token revocation on each terminal state
  - Integration: full PM→Engineer→Implementer→Engineer→PM cycle with a real `meridian-homepoc` no-op task; parallel-dispatch scenario with two non-conflicting Implementers; merge-conflict scenario; timeout scenario

The M4 acceptance criteria in `08-implementation-plan.md` already enumerate what passes; this spec doesn't add to them.

## Cross-doc cleanup scope (deferred to a separate commit)

The kickoff package was authored before the M2 operator-interface decision. The following Claude Desktop references are stale and should be updated to "Claude Code (Mike's interactive session)" or equivalent in a separate commit. **Not** updated as part of this commit per Mike's M3 design-review instruction.

| File | Lines | Context |
|---|---|---|
| `01-context.md` | 47 | "MCP-as-service. Claude Desktop is the human interface" |
| `02-architecture.md` | 5, 9, 105, 179 | Service shape narrative, ASCII diagram, PM operating modes table, process model summary |
| `03-phase-one-scope.md` | 32, 59 | Acceptance scenario, deferred items |
| `04-tech-stack.md` | 17 | `mcp` dep description ("client is Claude Desktop") |
| `06-mcp-tools-phase-one.md` | 595 | Hierarchy invariant note about Mike's token configuration |
| `07-agent-configs-phase-one.md` | 30, 121, 123 | PM YAML system prompt narrative — interactive-PM identification |
| `08-implementation-plan.md` | 48, 62, 95, 124, 157, 202, 204, 239 | M2 deliverable (README), M2 checkpoint, M3 checkpoint, M4 checkpoint, M5 checkpoint, M6 two-part checkpoint, M7 checkpoint |
| `09-testing-and-acceptance.md` | 55 | M4 acceptance walkthrough |
| `14-telegram-setup.md` | 34 | Test step from notification setup |
| `orchestrator-phase-1-kickoff.md` | 184, 549, 551, 561, 565, 569, 582 | MCP-as-service note, M2 acceptance, M2 checkpoint, M4 checkpoint, M5 checkpoint, M6 checkpoint, success scenario |
| `orchestrator-utilization-addendum.md` | 23, 107, 213 | PM mode definition, interactive-mode priority, interactive-mode detection |

**Cleanup guidance:** rename "Claude Desktop conversation" → "interactive Claude Code session" and "Claude Desktop" → "Claude Code" throughout, but preserve the structural meaning of the *interactive vs continuous* mode dichotomy. The `02-architecture.md` ASCII diagram and the `orchestrator-utilization-addendum.md` mode definition are the load-bearing references — get those right first; the checkpoint-section references in `08-implementation-plan.md` are mechanical replacements.

## Approval gate

Per `08-implementation-plan.md` § M3 checkpoint, Mike reviews this document and approves the bridge architecture (both sub-agent dispatch and continuous-mode PM driving) before M4 implementation begins. This document supersedes the prior STUB.

## What this commit does NOT do

- Update other kickoff docs — that's a separate cleanup commit per § Cross-doc cleanup scope.
- Write any M4 implementation code — `bridge/` modules, the `dispatches` table migration, and the `complete_dispatch` tool ship at M4.
- Modify the parallel M3 work-streams (agent config loading, permission framework, four agent YAMLs) — those are tracked separately in `08-implementation-plan.md` § M3 deliverables and don't depend on this spec.
