# Engineer ↔ Subagent Bridge

**Status: APPROVED-WITH-REVISIONS (M3 design, 2026-04-30). Awaiting Mike's final sign-off in `08-implementation-plan.md` § M3 checkpoint before M4 implementation begins.**

This document was a stub during the kickoff package; M3 finalizes it. The constraints are unchanged from the stub except where M2 made them concrete (see Constraint 10). The decision sections, lifecycle spec, sequence diagrams, storage shape, and timeout behavior are all new at M3. Revisions following the M3 review (canonical-clone merge model, continuous-mode PM heartbeat watchdog, schema and timeout corrections) are folded in throughout.

## Decision summary

- **Subprocess model:** **Option A — Claude Code subprocess.** Subscription-inherited, parallel-friendly, the natural fit for the Phase 1 cost model. Other options were considered and rejected; rationale below.
- **Result delivery:** **Path 3 — MCP-tool completion.** Sub-agent calls `complete_dispatch(...)` to deliver structured output through the same MCP channel it already uses for `record_api_call`. No stdout parsing.
- **Auth:** Per-dispatch static token issued via the keyring `TokenStore` (the dual-source auth shipped at M2). Token is `source="static"`, scoped to the dispatch's role, revoked on terminal state.
- **Working-directory isolation:** Daemon owns its own substrate clone at `<MERIDIAN_ORCH_DATA_DIR>/substrate-canonical/`, kept current with the upstream remote. Per-dispatch `git worktree` adds a fresh branch (`dispatch/<dispatch_id>`) off `substrate-canonical/main`. On `complete_dispatch(status='completed')`, daemon attempts `git merge --ff-only` into `substrate-canonical/main` and pushes to upstream. Mike's manual working tree at `meridian-homepoc/` is **never touched directly**; it stays current via Mike's own `git pull` against the same upstream. Merge conflicts surface to Engineer → PM as `question`-urgency; rollback is a PM-submitted revert task that flows through the same dispatch cycle.
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

The bridge owns a **canonical clone** of the substrate at `<MERIDIAN_ORCH_DATA_DIR>/substrate-canonical/`. Per-dispatch worktrees branch from this canonical clone, never from Mike's manual working tree at `C:\Users\mbgol\dev\meridian-homepoc\`. Completed dispatches auto-merge into the canonical clone's `main` and push to the same upstream Mike's working tree pulls from. Mike's working tree stays current via Mike's own `git pull`; the daemon never reads or writes Mike's working tree.

This separation is load-bearing. Concurrent automated dispatches into Mike's working tree would race with his manual edits, branch operations, and IDE state. The canonical clone is the daemon's playground; Mike's working tree is his.

#### Layout

```
<MERIDIAN_ORCH_DATA_DIR>/
├── substrate-canonical/           # daemon-owned clone of meridian-homepoc upstream
│   ├── .git/                      # full clone, tracking origin/main
│   └── ... working tree ...       # daemon's "main" working copy; never edited directly
└── dispatch-worktrees/
    ├── <dispatch_id_A>/           # `git worktree add -b dispatch/<A> <path> origin/main`
    └── <dispatch_id_B>/           # parallel-safe; each worktree on its own branch
```

#### Substrate-canonical lifecycle

- **First daemon startup:** if `substrate-canonical/` does not exist, daemon clones from upstream (`git clone <upstream_url> substrate-canonical`). Upstream URL is read from the substrate project's git remote at `projects.working_directory` — i.e., the daemon inspects Mike's working tree's `origin` once at provisioning time, then operates against that upstream directly.
- **Before each dispatch:** daemon does `git -C substrate-canonical fetch origin && git -C substrate-canonical merge --ff-only origin/main` to stay current with anything Mike or other automation pushed since the last dispatch. If fast-forward fails (canonical has diverged from upstream — should never happen since daemon's the only writer), daemon emits `dispatch.canonical_diverged` audit event, halts the dispatch, surfaces as `urgent` to PM. Manual operator recovery; not auto-healed.
- **No daemon edits to canonical's working tree.** The daemon only `fetch`/`merge --ff-only`/`push` against canonical. Edits happen in worktrees off canonical, then merge back via `--ff-only`.

#### Per-dispatch worktree

- **Path:** `<MERIDIAN_ORCH_DATA_DIR>/dispatch-worktrees/<dispatch_id>/`
- **Branch:** `dispatch/<dispatch_id>`, created by `git -C substrate-canonical worktree add -b dispatch/<id> <worktree_path> origin/main` at dispatch time.
- **Subprocess cwd:** the worktree path. The Claude Code subprocess sees a clean checkout of upstream main, makes its edits, commits to the dispatch branch (Implementer's job is to land at least one commit; commit hash is reported via `complete_dispatch`).

#### Merge protocol on completion

On `complete_dispatch(status='completed', commit_hash=<sha>)`:

1. Daemon refreshes canonical: `git -C substrate-canonical fetch origin && git -C substrate-canonical merge --ff-only origin/main`. If a non-ff is required here, see § Merge conflicts below.
2. Daemon attempts to merge the dispatch branch into canonical's `main` via `git -C substrate-canonical merge --ff-only dispatch/<id>`. The `--ff-only` flag makes this safe: if upstream has moved since the worktree was branched, the merge fails cleanly without creating a merge commit on top of stale state.
3. On `--ff-only` success: daemon pushes canonical's `main` to upstream (`git -C substrate-canonical push origin main`). Audit `dispatch.merged` with the new upstream HEAD.
4. On `--ff-only` failure (upstream advanced): daemon does **not** auto-rebase. It records `merge_conflict_files_json` and `dispatch.merge_conflict`, leaves the dispatch branch in canonical (cleanup deferred until conflict-resolution decision), surfaces to Engineer → PM. PM decides: re-dispatch with rebased base (which gets a fresh worktree off the new `origin/main`), or escalate to Mike. No automated rebase.
5. The previous `dispatch/<id>` branch and its worktree are removed (`git worktree remove --force` + `git branch -D dispatch/<id>`) once the dispatch reaches a terminal state — *unless* `keep_worktree_on_failure` is set (see below).

#### Merge conflicts

Two distinct conflict surfaces:

- **`origin/main` advanced during dispatch.** The dispatch branch can no longer fast-forward into canonical's main. Common case: parallel dispatches landed first, or Mike pushed something. Recovery: PM re-submits the same task with a fresh worktree off the new `origin/main`. Engineer dispatches; the second attempt typically merges cleanly.
- **Genuine semantic conflict.** Two parallel dispatches edited the same file. The dispatch graph should have caught this and serialized them; if it didn't, the second-merging dispatch fails `--ff-only`. Recovery: same as above — PM re-submits with a fresh base. The dispatch graph logic gets a follow-up to refine the conflict-detection heuristics.

In both cases, the conflicting paths are stored in `dispatches.merge_conflict_files_json` (a JSON array of paths) so PM can reason about scope.

#### Rollback

Phase 1 has no `dispatch.revert` tool or daemon-driven rollback. Rollback is a normal PM action: PM submits a task whose description is "revert commit `<sha>` from substrate" with appropriate context; Engineer claims; Engineer dispatches Implementer with the spec "git revert `<sha>`, push, commit hash `<new_sha>`"; Implementer's `complete_dispatch` reports success; daemon merges and pushes via the same protocol. Audit chain preserved.

#### Cleanup on terminal state

Worktree removed and branch deleted on every terminal state by default — `completed`, `declined`, `failed`, `timed_out`, `cancelled`. The `keep_worktree_on_failure` per-dispatch flag (see § keep_worktree_on_failure flag) overrides this for non-`completed` terminal states only; even when set, daemon **always** cleans up worktrees on service restart to prevent orphan accumulation.

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

**Calibration note (M4 follow-up).** The tps constants above are starting estimates pending empirical correction. M4 integration tests run at least one real dispatch each on Sonnet and Opus, measure observed tokens-per-second from the `record_api_call` event stream and the dispatch's `started_at` / `completed_at` timestamps, and update `bridge/timeouts.py` with corrected values before M5 closes. If M4 lands without the calibration, the bridge spec opens a queue item titled "Calibrate model_throughput_tps from observed dispatch data" with priority 2; the queue item must be resolved before M5 acceptance.

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

Per-dispatch lifecycle writes the following `audit_log` rows. All rows include `dispatch_id` in `details_json`; per-event additional fields listed below. The `get_dispatch_events` MCP tool (see § `get_dispatch_events` MCP tool below) returns the same rows that `get_audit_log` returns, filtered by `dispatch_id` — both tools read the same `audit_log` table; the difference is the filter shape and the hierarchy-scoping rules.

| Action | When | Additional `details_json` fields |
|---|---|---|
| `dispatch.issued` | Engineer's `dispatch_*` MCP call validates and persists the dispatch row | `task_id`, `role`, `working_directory`, `spec_path`, `timeout_seconds`, `engineer_decisions` (full payload from the dispatch input) |
| `dispatch.token_created` | `TokenStore.issue` returns | `token_id` (never the secret) |
| `dispatch.subprocess_started` | `Process.launch` returns | `pid`, `command` (redacted of token), `cwd` |
| `dispatch.mcp_authenticated` | First authenticated MCP call from the subprocess lands | `agent_id`, `first_tool_called` |
| `dispatch.last_activity` | Each `record_api_call` from the dispatch | `model`, `input_tokens`, `output_tokens` |
| `dispatch.soft_warning` | 2 minutes since last activity, budget remaining | `idle_seconds`, `tokens_used_so_far`, `tokens_estimated` |
| `dispatch.complete_called` | Subprocess calls `complete_dispatch` | full `CompleteDispatchInput` payload |
| `dispatch.timeout` | Hard timeout fires (sub-agent dispatches) | `elapsed_seconds`, `record_api_call_count` |
| `dispatch.heartbeat_timeout` | Continuous-mode PM idle > 30 min | `idle_seconds`, `last_tool_called` |
| `dispatch.token_revoked` | `TokenStore.revoke` returns | `token_id`, `reason` (`completed`, `declined`, `failed`, `timeout`, `heartbeat_timeout`, `cancelled`) |
| `dispatch.subprocess_terminated` | Process exit captured | `exit_code`, `signal`, `total_elapsed_seconds`, `worktree_retained` |
| `dispatch.merged` | Dispatch branch fast-forwarded into canonical main and pushed | `branch`, `new_upstream_head` |
| `dispatch.merge_conflict` | Worktree merge into main fails | `branch`, `conflicting_files` |
| `dispatch.canonical_diverged` | Canonical clone has diverged from upstream (should never happen) | `canonical_head`, `upstream_head` |

The chain from `task.submit` → `task.claim` → `dispatch.issued` → ... → `dispatch.subprocess_terminated` → `task.status_change` is the load-bearing audit trail Mike inspects via `get_audit_log` to reconstruct the cycle.

### Storage shape

A new `dispatches` table ships at M4. This is *not* an `ALTER TABLE tasks` extension because dispatches have multiple-per-task semantics (one task can spawn multiple dispatches across retries or staged execution).

```sql
-- Migration 0005_dispatches.sql at M4 (after 0002_task_planning.sql, also at M4)

CREATE TABLE dispatches (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL REFERENCES tasks(id),
    role TEXT NOT NULL,                          -- 'implementer' | 'tester' | 'pm-continuous' | 'engineer-supervisor'
    status TEXT NOT NULL DEFAULT 'queued',       -- queued | running | completed | declined | failed | timed_out | cancelled
    working_directory TEXT NOT NULL,
    spec_path TEXT,
    commit_hash TEXT,
    decline_reason TEXT,
    estimated_tokens INTEGER,
    actual_tokens INTEGER,
    timeout_seconds INTEGER NOT NULL,
    timeout_override_seconds INTEGER,            -- per-dispatch override of the calculated default
    keep_worktree_on_failure INTEGER NOT NULL DEFAULT 0,  -- debug flag, see § keep_worktree_on_failure
    token_id TEXT,                               -- the keyring token_id (NEVER the secret)
    subprocess_pid INTEGER,
    engineer_decisions_json TEXT,                -- JSON array of altitude-1 decisions Engineer made composing the spec; see § engineer_decisions field
    issued_at TEXT NOT NULL DEFAULT (datetime('now')),
    started_at TEXT,
    last_activity_at TEXT,
    completed_at TEXT,
    timed_out_at TEXT,
    merge_conflict_files_json TEXT,              -- JSON array of conflicting paths; NULL when no conflict
    actual_touched_files_json TEXT,              -- JSON array of file paths populated by daemon from `git diff --name-only` post-completion. NULL until completion. NULL if commit_hash is NULL (no commit means no touches). See § Substrate touch map cache
    artifacts_json TEXT,
    test_results_json TEXT,
    queue_items_raised_json TEXT,
    notes TEXT
);

CREATE INDEX idx_dispatches_task ON dispatches(task_id);
CREATE INDEX idx_dispatches_status ON dispatches(status);
CREATE INDEX idx_dispatches_role_status ON dispatches(role, status);   -- continuous-mode PM and engineer-supervisor lookup
CREATE INDEX idx_dispatches_role_completed ON dispatches(role, status, completed_at) WHERE status='completed';   -- Engineer's get_recent_dispatches scan for substrate touch map
```

The `token_id` column stores the keyring `token_id` (the short hex prefix), not the full token string. The full token never persists to SQLite — only to keyring's hashed record.

**M4 ships two migrations sequentially: `0002_task_planning.sql` (the existing planned migration adding `tasks.estimated_tokens` and `tasks.actual_tokens`) and `0005_dispatches.sql` (the new dispatches table from this spec).** They apply in numeric order on a fresh start. The reservation gap (`0003_pm_mode.sql` ships at M5; `0004_notifications.sql` ships at M6) is intentional and matches the existing migration plan in `05-data-model.md`. The migrations are independent — `0005_dispatches.sql` does not reference any column or table that `0003`/`0004` add — so out-of-ship-order numbering is functionally safe. M4 integration tests run with both 0002 and 0005 applied, and the dispatch lifecycle tests in particular exercise the new columns without depending on `pm_mode_log` or notification tables.

### `keep_worktree_on_failure` flag

A debug aid for investigating failed or declined dispatches. Default behavior cleans up the worktree on every terminal state (see § Cleanup on terminal state above). When the flag is set, the worktree and dispatch branch are preserved on **non-completed** terminal states (`failed`, `declined`, `timed_out`, `cancelled`) so the operator can inspect the partial state.

- **Default:** `false` (clean up unconditionally).
- **Configuration:** per-dispatch field `keep_worktree_on_failure: bool = False` on `DispatchImplementerInput` / `DispatchTesterInput`. Engineer sets it when dispatching for diagnostic purposes; not in the normal hot path.
- **Cleanup on `completed`:** worktree and branch always removed regardless of flag — there's no diagnostic value in keeping a worktree whose work successfully merged.
- **Cleanup on service restart:** daemon **always** cleans up worktrees on startup, even when the flag is set. This prevents orphan accumulation across daemon crashes/restarts. If the operator wants to inspect a dispatch's worktree, the inspection must happen between the dispatch reaching its terminal state and the next daemon restart.
- **Audit:** `dispatch.subprocess_terminated` includes a `worktree_retained: bool` field so the audit log records whether cleanup ran.

### `engineer_decisions` field

Engineer's altitude-1 reasoning lands in three layers per `engineer.yaml` § Altitude framework: work-logs (primary), structured decision metadata in dispatch specs (secondary), inline notes (tertiary). The secondary layer is implemented as the `engineer_decisions` field on the dispatch input schemas:

```python
class EngineerDecision(BaseModel):
    decision: str                                 # one-line summary of the choice made
    rationale: str                                # the "because Y" — factual, not defensive
    alternatives_considered: list[str] | None = None  # other paths Engineer evaluated, optional

class DispatchImplementerInput(BaseModel):
    task_id: str
    spec_path: str
    working_directory: str
    timeout_override_seconds: int | None = None
    keep_worktree_on_failure: bool = False
    engineer_decisions: list[EngineerDecision] = []

class DispatchTesterInput(BaseModel):
    task_id: str
    commit_hash: str
    working_directory: str
    assertion_spec: str
    timeout_override_seconds: int | None = None
    keep_worktree_on_failure: bool = False
    engineer_decisions: list[EngineerDecision] = []
```

The sub-agent reads `engineer_decisions` as constraints — Engineer made these calls when composing the spec, the sub-agent inherits them rather than re-deciding. No acknowledgment field; the audit trail is the `dispatch.issued` audit row's `details_json` (which captures the full `engineer_decisions` payload) plus Engineer's work-log "decisions made" section (which references the same decisions in human-readable prose).

Engineer's prompt (`engineer.yaml`) instructs Engineer to write specs that are explicit about their inherited constraints — the sub-agent should never have to *guess* whether something is Engineer's call or its own to make. If the spec is ambiguous about ownership, that's a no-ambiguity-surface violation per Engineer's spec-writing rules; Engineer resolves before dispatch.

### Substrate touch map cache

The substrate touch map is the cache Engineer reads on every iteration to predict file overlap when composing parallel dispatches (per `engineer.yaml` § Parallel dispatch: balanced). The mechanism:

1. **Population.** When a dispatch reaches `status='completed'` and the commit landed cleanly (`commit_hash IS NOT NULL` and `--ff-only` succeeded), the daemon runs `git diff --name-only <commit_hash>~1 <commit_hash>` against the canonical clone, parses the output as a list of paths, and writes the JSON-encoded list to `dispatches.actual_touched_files_json` for the dispatch row.
2. **Read path.** Engineer queries via `get_recent_dispatches(role='implementer', status=['completed'], since=<recent>)` and aggregates `actual_touched_files` per task type / spec keyword / file region as needed. The aggregation lives in Engineer's working memory for the current iteration only — see `engineer.yaml`'s "you do not maintain a persistent in-memory dispatch graph" discipline.
3. **No cache for declined or failed dispatches.** A dispatch that didn't commit didn't actually touch the substrate as far as the canonical clone is concerned (the worktree was cleaned). `actual_touched_files_json` stays NULL.
4. **Index.** `idx_dispatches_role_completed` (above) makes Engineer's repeated scans cheap — `WHERE status='completed' AND role='implementer' AND completed_at >= ?` hits the index directly.

### `get_dispatch_events` MCP tool (M4)

Engineer needs to subscribe to its own in-flight dispatches' audit events for prediction-divergence detection (see `engineer.yaml` § Parallel dispatch). `get_dispatch_events` is the polling surface for that subscription.

```python
class GetDispatchEventsInput(BaseModel):
    dispatch_ids: list[str]
    since_timestamp: str | None = None    # ISO 8601 UTC; default: no time filter (returns all events for the listed dispatches)

class GetDispatchEventsOutput(BaseModel):
    events: list[AuditEvent]               # same shape as get_audit_log entries
```

Returns the same audit rows that `get_audit_log` returns, filtered by `dispatch_id` (the rows whose `details_json.dispatch_id` is in the input list) and optionally by timestamp. The tool is read-only and not itself audited — query traffic on observability tools would dwarf the actual dispatch lifecycle audit signal.

**Hierarchy check.** Engineer can only query events for dispatches Engineer initiated (the audit row's `actor` is in Engineer's subtree, OR the dispatch row's `task_id` was claimed by Engineer). PM can query any dispatch (subtree includes all dispatches). Mike can query all.

**Allowed for PM, Engineer.** Implementer/Tester see only their own dispatch events via `get_dispatch_status` / `get_dispatch_result`; they don't need the audit-event polling surface.

### `get_recent_dispatches` MCP tool (M4)

Engineer's primary use is building the substrate touch map cache (above). PM's use is post-mortem queries — "show me everything Implementer did in the last 24 hours."

```python
class DispatchSummary(BaseModel):
    id: str
    task_id: str
    role: Literal['implementer', 'tester', 'pm-continuous', 'engineer-supervisor']
    status: Literal['queued', 'running', 'completed', 'declined', 'failed', 'timed_out', 'cancelled']
    working_directory: str
    completed_at: str | None
    actual_touched_files: list[str]        # parsed from actual_touched_files_json; empty list if NULL
    commit_hash: str | None

class GetRecentDispatchesInput(BaseModel):
    role: Literal['implementer', 'tester', 'pm-continuous', 'engineer-supervisor'] | None = None
    status: list[str] | None = None       # filter to these statuses; default: any
    since: str | None = None              # ISO 8601 UTC; default: 24h ago
    limit: int = 50

class GetRecentDispatchesOutput(BaseModel):
    dispatches: list[DispatchSummary]
```

Read-only; not audited (high-frequency for Engineer's loop).

**Hierarchy check.** Same scoping as `get_dispatch_events`. Engineer sees its own dispatches and its sub-agents' dispatches; PM sees everything in its subtree (Engineer + Implementer + Tester); Mike sees all. Implementer/Tester see only their own dispatches via `get_dispatch_status` / `get_dispatch_result`.

**Allowed for PM, Engineer.**

### Long-running supervisors: continuous-mode PM and Engineer

Two long-running supervisor subprocesses share infrastructure and lifecycle semantics: the continuous-mode PM (PMSupervisor-managed, `dispatches.role='pm-continuous'`) and the Engineer (EngineerSupervisor-managed, `dispatches.role='engineer-supervisor'`). Both are Claude Code subprocesses with daemon-issued static tokens; both use the heartbeat watchdog discipline below; neither has a hard timeout because neither is per-task. They differ in role responsibilities (PM does product/altitude reasoning, Engineer does technical decomposition and dispatch supervision) but the lifecycle plumbing is symmetric.

| Aspect | Sub-agent dispatch (Engineer-driven) | Long-running supervisor (PMSupervisor or EngineerSupervisor) |
|---|---|---|
| Lifecycle owner | `bridge.dispatcher.BridgeDispatcher` | `bridge.pm_supervisor.PMSupervisor` (continuous PM) or `bridge.engineer_supervisor.EngineerSupervisor` (Engineer) |
| Subprocess command | `claude -p <spec>` | `claude -p <continuous_pm_prompt>` (continuous PM) or `claude -p <engineer_prompt>` (Engineer) |
| Working directory | Per-dispatch git worktree under data dir | `<MERIDIAN_ORCH_DATA_DIR>/pm-continuous/` or `<MERIDIAN_ORCH_DATA_DIR>/engineer/` (no git, no worktree) |
| Token role | `implementer` or `tester` | `pm` with `label='pm-continuous'`, or `engineer` with `label='engineer-supervisor'` |
| Hard timeout | `max(estimated_tokens / tps, 300s)` per dispatch | None — long-lived; heartbeat watchdog instead |
| Result delivery | `complete_dispatch` MCP tool | No completion call. Supervisor stays alive; signals state via tool calls (`set_pm_mode` for PM, `update_task_status` for Engineer) |
| Termination | On `complete_dispatch` or hard timeout | Daemon shutdown, admin command, or heartbeat-watchdog respawn |
| Coordination | None with other dispatches | Reads `pm_mode_log` on each iteration (continuous PM pauses dispatch when `interactive`; Engineer prepends a one-time mode-flip context header on the next status update) |
| Audit row source | `dispatches` table | `dispatches` table with `role='pm-continuous'` or `role='engineer-supervisor'` (single supervisor row per role, persistent across daemon lifetime barring respawn) |

Engineer does **not** dispatch the continuous PM and does **not** directly coordinate with it. The two communicate exclusively through the database — task queue, `pm_mode_log`, and audit log. Engineer reads the queue and claims tasks regardless of which PM instance enqueued them; the continuous PM submits tasks the same way the interactive PM does.

#### Heartbeat watchdog

Long-running supervisors (continuous PM and Engineer) have no natural completion signal — by design, they run until daemon shutdown. That creates a hung-subprocess hazard: a supervisor that wedges on a stuck model call has no automatic recovery without operator intervention. The heartbeat watchdog closes that gap. The discipline below applies to both `role='pm-continuous'` and `role='engineer-supervisor'` rows; the supervisor implementations (`PMSupervisor`, `EngineerSupervisor`) share the same watchdog code path.

**Expected cadence.** A long-running supervisor is expected to make at least one MCP call (any MCP tool the supervisor is permitted to call — for both supervisors: `get_window_state`, `get_queue`, `record_api_call`, `claim_task`, `update_task_status`; PM-continuous additionally: `set_pm_mode`, `submit_task`; engineer-supervisor additionally: `dispatch_implementer`, `dispatch_tester`, `get_dispatch_status`, `get_dispatch_events`, etc.) within every **15-minute** window during normal operation. Each MCP call updates `dispatches.last_activity_at` for the supervisor's dispatch row, the same column used for sub-agent dispatch tracking.

**Watchdog logic.** The supervisor checks `last_activity_at` every 60 seconds. If `now - last_activity_at > 30 minutes`, the supervisor subprocess is presumed hung:

1. Audit `dispatch.heartbeat_timeout` with `idle_seconds`, `last_tool_called`, and the dispatch_id.
2. Standard timeout sequence: SIGTERM the subprocess, 10-second grace period, SIGKILL if still alive.
3. `TokenStore.revoke(token_id)`. Audit `dispatch.token_revoked` with `reason='heartbeat_timeout'`.
4. Audit `dispatch.subprocess_terminated` with exit code (or signal).
5. Mark the dispatch row `status='timed_out'`, `timed_out_at=now()`.
6. **Immediately respawn** a fresh supervisor subprocess (new dispatch_id, new token, new subprocess). The new instance reconstructs state from the database — `pm_mode_log` and queue for continuous PM; `get_dispatch_status` and `get_recent_dispatches` for Engineer rebuilding its dispatch graph. No in-memory state to migrate. Audit a fresh `dispatch.issued` chain.
7. If three respawns occur within an hour for the same role, the supervisor halts further respawns and surfaces `urgent`-urgency to Mike via `notify_human` (operator must investigate; auto-respawn alone isn't a fix for a deeper bug).

**Why 15-minute expected cadence and 30-minute timeout.** The continuous PM's normal loop polls `pm_mode_log`, `get_window_state`, and the queue every few minutes; even when paused (`mode=interactive`), it should continue polling to detect when Mike's session ends. Engineer's loop runs faster (claim → compose → dispatch → poll → write) but the cadence floor is the same — symmetry between supervisor types simplifies the watchdog. A 15-minute expected cadence gives generous slack for slow model calls; a 30-minute timeout (2× the expected cadence) is the unambiguous "hung" threshold. Configurable per supervisor type via `MERIDIAN_ORCH_PM_HEARTBEAT_EXPECTED_SECONDS` / `MERIDIAN_ORCH_PM_HEARTBEAT_TIMEOUT_SECONDS` (default 900 / 1800) and `MERIDIAN_ORCH_ENGINEER_HEARTBEAT_EXPECTED_SECONDS` / `MERIDIAN_ORCH_ENGINEER_HEARTBEAT_TIMEOUT_SECONDS` (same defaults).

**Distinct from hard timeout.** Sub-agent dispatches use the `max(estimated_tokens / tps, 300s)` hard timeout; long-running supervisors use heartbeat-only because they have no estimated-tokens budget to bound on. The audit action `dispatch.heartbeat_timeout` is distinct from `dispatch.timeout` so the audit log distinguishes "ran too long for its budget" from "stopped responding."

**Engineer-supervisor specifics.** Engineer's supervisor row is a single persistent row across daemon lifetime (modulo respawns). Engineer reconstructs its dispatch graph from `get_dispatch_status` (in-flight rows it owns) and `get_recent_dispatches` (recent history, for the substrate touch map cache) on every loop iteration — no in-memory dispatch graph survives across iterations. This is intentional: the database is the source of truth, respawns recover cleanly, and stale-state hazards are eliminated by design.

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
    Daemon's auth context resolves the bearer token via TokenStore.lookup; the
    resolved TokenRecord has source='oauth', role='pm' (Mike's interactive
    token; OAuth-issued at M2 onboarding via Claude Code's loopback flow).
    The (source='oauth', role='pm') tuple is the unambiguous signal of Mike's
    interactive presence — distinct from the continuous-mode PM, whose token
    is source='static', role='pm', label='pm-continuous'.
    Daemon writes pm_mode_log: mode='interactive', triggered_by='mike_message'
  Continuous PM's next loop iteration: reads pm_mode_log, sees interactive, pauses dispatch

[Mike's session ends, 30 minutes elapse without his MCP token activity]
  Daemon writes pm_mode_log: mode='continuous', triggered_by='mike_inactivity_timeout'
  Continuous PM resumes dispatch on next iteration

[Heartbeat watchdog respawn — see § Heartbeat watchdog]
  PMSupervisor checks dispatches.last_activity_at every 60s.
  If now - last_activity_at > 30 minutes:
    [audit heartbeat_timeout idle_seconds=N last_tool_called=<tool>]
    SIGTERM, 10s grace, SIGKILL. TokenStore.revoke(token_PM).
    [audit token_revoked reason='heartbeat_timeout', subprocess_terminated]
    Mark dispatch row status='timed_out'.
    Immediately respawn: new dispatch_id, new token, fresh subprocess.
    [audit dispatch.issued chain for the new instance]
  Three respawns within an hour: halt; notify_human(urgency='urgent', ...).

[Daemon shutdown]
  PMSupervisor.terminate:
    SIGTERM continuous PM subprocess; 10s grace; SIGKILL if alive
    TokenStore.revoke(token_PM)
    [audit subprocess_terminated, token_revoked]
```

**Auth-source distinction recap.** The interactive PM (Mike) authenticates via OAuth (`source='oauth'`); the continuous PM authenticates via a daemon-issued static token (`source='static'`). The daemon's auto-flip of `pm_mode_log` to `interactive` is gated on `(source='oauth', role='pm')` — checking source rather than label means the distinction is enforced at the auth layer, not by string matching on a label that an attacker (or a future operator who renames a token's label) could forge. This relies on M2's `TokenRecord.source` being load-bearing: the daemon trusts that field, and it is set at issuance time only by the OAuth provider (`source='oauth'`) or by the static-token CLI (`source='static'`). Neither path lets the caller specify the source.

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
- `src/meridian_orchestrator/bridge/pm_supervisor.py` — `PMSupervisor` class for continuous-mode PM lifecycle (includes the heartbeat watchdog)
- `src/meridian_orchestrator/bridge/canonical.py` — provisioning and `fetch`/`merge --ff-only`/`push` operations on `<data_dir>/substrate-canonical/`
- `src/meridian_orchestrator/bridge/worktree.py` — git worktree provisioning, dispatch-branch lifecycle, and the `--ff-only` merge protocol
- `src/meridian_orchestrator/bridge/timeouts.py` — `model_throughput_tps` table + timeout calculation. Constants are starting estimates; M4 integration tests measure observed tps for at least one real Sonnet dispatch and one real Opus dispatch and update this file before M5 closes (queue item raised if not addressed).
- `src/meridian_orchestrator/storage/sql/0002_task_planning.sql` — pre-existing planned migration; ships at M4 with this work
- `src/meridian_orchestrator/storage/sql/0005_dispatches.sql` — schema per § Storage shape (including the `engineer_decisions_json`, `actual_touched_files_json`, and `merge_conflict_files_json` columns + `idx_dispatches_role_completed` partial index); ships at M4 alongside 0002
- `src/meridian_orchestrator/tools/dispatch.py` — `dispatch_implementer`, `dispatch_tester`, `get_dispatch_status`, `get_dispatch_result`, **plus the new `complete_dispatch`, `get_dispatch_events`, and `get_recent_dispatches` MCP tools** (schemas in § `engineer_decisions` field, § `get_dispatch_events` MCP tool, § `get_recent_dispatches` MCP tool above)
- `DispatchImplementerInput` and `DispatchTesterInput` extended with the `engineer_decisions: list[EngineerDecision] = []` field; sub-agent inherits Engineer's altitude-1 decisions as constraints rather than re-deciding
- Daemon-side substrate touch map population: post-`complete_dispatch` and post-`--ff-only` merge, daemon runs `git diff --name-only <commit>~1 <commit>` against the canonical clone and writes the JSON-encoded path list to `dispatches.actual_touched_files_json`
- Tests:
  - Unit: timeout calculation (including override), heartbeat watchdog state transitions, all 7 dispatch lifecycle terminal states, decline propagation, audit log emission for all 13 actions, token revocation on each terminal state, `keep_worktree_on_failure` default and override behavior, `merge_conflict_files_json` population on conflict, `engineer_decisions` payload propagation through `dispatch.issued` audit details_json and back via `complete_dispatch` payload, `actual_touched_files_json` population after a clean merge, `get_dispatch_events` and `get_recent_dispatches` hierarchy scoping (Engineer sees own subtree, PM sees full subtree, Implementer/Tester denied)
  - Integration: substrate-canonical provisioning on first daemon startup; full PM→Engineer→Implementer→Engineer→PM cycle with a real `meridian-homepoc` upstream and a no-op task; parallel-dispatch scenario with two non-conflicting Implementers ending in clean fast-forward merges; merge-conflict scenario where the second-merging dispatch fails `--ff-only` and surfaces conflicting files; timeout scenario; heartbeat-timeout-respawn scenario for the continuous PM and the engineer-supervisor; substrate touch map cache E2E (Engineer dispatches Implementer; on completion, daemon populates `actual_touched_files_json`; subsequent Engineer iteration reads via `get_recent_dispatches` and uses the touch set in its parallelization decision)
  - Calibration: observed-tps measurement for Sonnet and Opus dispatches; `bridge/timeouts.py` updated before M5

The migrations apply in numeric order: `0002_task_planning.sql` first (adding `tasks.estimated_tokens` and `tasks.actual_tokens`), then `0005_dispatches.sql` (creating the new table). Integration tests run with both applied. The reservation gap (`0003_pm_mode.sql` ships at M5; `0004_notifications.sql` ships at M6) is intentional and documented in `05-data-model.md`; the migrations are independent so out-of-ship-order numbering is functionally safe.

The M4 acceptance criteria in `08-implementation-plan.md` already enumerate what passes; this spec adds the heartbeat-respawn integration test and the calibration deliverable.

## Cross-doc cleanup scope (completed in `docs(M3): align kickoff package with Claude Code interactive PM (post-M2)`)

The kickoff package was authored before the M2 operator-interface decision. The Claude Desktop references listed below were stale and have been updated to "Claude Code (Mike's interactive session)" or equivalent in the cleanup commit referenced in this section heading. The table is preserved as a historical record of the M2-driven scope.

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
