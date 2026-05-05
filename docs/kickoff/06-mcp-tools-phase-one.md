# MCP Tools — Phase 1

This document is the contract for what tools exist, what they take, what they return, who can call them, and what they audit.

All tools are async. All inputs and outputs have pydantic schemas in `src/meridian_orchestrator/tools/schemas.py`. All state-changing tools write an audit log entry. Errors are domain exceptions per `04-tech-stack.md`.

## Conventions

- Tool names are `snake_case`.
- Inputs and outputs are JSON-serializable pydantic models.
- Every tool is registered with the MCP server with a description that the calling agent can read.
- Errors surface to the agent as MCP tool errors, with a code (`PERMISSION_DENIED`, `NOT_FOUND`, `BUDGET_EXCEEDED`, `VALIDATION_ERROR`, `CONFLICT`) and a short message. **No permission graph leakage** in error messages.

## Permission model

Each agent's `mcp_tools_allowed` list is the authoritative allowlist. Server-side resolution:

1. MCP request arrives with auth token.
2. `auth.py` resolves token → `agent_id`.
3. Tool dispatcher looks up `agent.mcp_tools_allowed`.
4. If tool not in list → raise `PermissionError` (mapped to `PERMISSION_DENIED`).
5. Otherwise execute.

**Hierarchy enforcement** is layered on top: some tools have additional checks beyond presence in the allowlist. Documented per-tool below.

## Tool catalogue

### Task management

#### `submit_task`

```python
class SubmitTaskInput(BaseModel):
    agent_role: str           # the role expected to claim the task
    description: str
    priority: int = 2         # 1..5
    complexity: int = 3       # 1..5
    parent_task_id: str | None = None  # if this is a sub-task

class SubmitTaskOutput(BaseModel):
    task: Task
```

**Hierarchy check**: caller may submit a task only for a role they manage (direct report). Mike can submit for PM. PM can submit for Engineer. Engineer can submit for Implementer or Tester. PM **cannot** submit a task with `agent_role='implementer'` — that's a hierarchy bypass.

If `parent_task_id` is set, caller must own the parent task (must be `claimed_by` the caller) **or** caller must be the role that submitted the parent. Sub-task submissions during dispatch are the canonical use of this.

**Audited.** Action: `task.submit`.

#### `get_queue`

```python
class GetQueueInput(BaseModel):
    status: list[str] | None = None
    agent_role: str | None = None
    project_id: str | None = None
    limit: int = 50
    offset: int = 0

class GetQueueOutput(BaseModel):
    tasks: list[Task]
    total: int
```

**Hierarchy check**: Implementer/Tester can read only their own claimed tasks (filter applied server-side regardless of input). PM/Engineer can read everything in their subtree.

**Not audited** (read-only, high frequency).

#### `update_task_status`

```python
class UpdateTaskStatusInput(BaseModel):
    task_id: str
    status: Literal['claimed','in_progress','completed','failed','declined','cancelled']
    notes: str | None = None

class UpdateTaskStatusOutput(BaseModel):
    task: Task
```

**Hierarchy check**: caller must be `claimed_by` the task **or** caller must be the role that submitted it (the latter only for `cancelled`).

State machine validated server-side per `05-data-model.md`. Invalid transitions raise `CONFLICT`.

**Audited.** Action: `task.status_change`. `details_json` includes the from/to states.

#### `set_task_priority`

```python
class SetTaskPriorityInput(BaseModel):
    task_id: str
    priority: int  # 1..5
```

**Hierarchy check**: caller must be the submitter, the claimed-by, or an ancestor in the hierarchy.

**Audited.** Action: `task.priority_change`.

#### `claim_task`

```python
class ClaimTaskInput(BaseModel):
    task_id: str

class ClaimTaskOutput(BaseModel):
    task: Task
```

The implementation uses a conditional UPDATE (`UPDATE tasks SET claimed_by = ?, claimed_at = ?, status = 'claimed' WHERE id = ? AND status = 'pending'`). If no rows updated, raise `CONFLICT` (already claimed).

**Hierarchy check**: caller's role must equal `task.agent_role`. Implementer cannot claim Tester tasks even if both report to the same Engineer.

**Audited.** Action: `task.claim`.

#### `get_subtasks`

```python
class GetSubtasksInput(BaseModel):
    parent_task_id: str

class GetSubtasksOutput(BaseModel):
    tasks: list[Task]
```

**Hierarchy check**: caller must be in the chain (submitter, claimed_by, or ancestor of) the parent task.

**Not audited.**

### Budget & usage

#### `record_api_call`

```python
class RecordApiCallInput(BaseModel):
    model: str                                                      # e.g. 'claude-opus-4-7', 'claude-sonnet-4-6'
    input_tokens: int
    output_tokens: int
    task_id: str | None = None                                      # the task this call was for
    notes: str | None = None                                        # free-form agent self-annotation (observability tier-1)
    workflow_id: str | None = None                                  # cohort key — population protocol deferred (BACKLOG)
    task_type: str | None = None                                    # cohort key — population protocol deferred (BACKLOG)

class RecordApiCallOutput(BaseModel):
    usage_id: str
    window_start: str
```

`agent_id` is taken from the auth token, not the input. `cost_usd` is computed server-side from a model→price table maintained in `tools/budget.py`. `agent_role` is **not** an input field — it's auto-populated server-side from the caller's resolved role (per Q6α at M5-subscription-routing prep) and stored on the `api_usage` row's `agent_role` column for cohort aggregation. The four observability fields (`notes`, `workflow_id`, `agent_role`, `task_type`) are tier-1 cost-interrogation scaffolding per `BACKLOG.md` § Observability roadmap; `notes` / `task_type` are optional caller-supplied tags that default to NULL when not provided.

**`workflow_id` alpha-denormalization** (M5-continuous-pm-driver commit 1): `workflow_id` is server-derived at write time by walking `tasks.parent_task_id` from the caller's bound dispatch's task to the chain root. Resolution precedence is **Reading B** (per F1.1 / D1.1): server-derived wins when present; caller-supplied falls through when server-derived returns None. Server-derived is None when (a) the caller has no bound non-terminal dispatch (PM-direct OAuth case), (b) the dispatch's `task_id` is NULL (post-M4c-supervisor-base supervisor-row case per migration 0007), or (c) the chain exceeds the defensive depth limit `_WORKFLOW_CHAIN_MAX_DEPTH = 100` (cycle protection). When both caller-supplied and server-derived are present and disagree, server-derived wins and a `record_api_call.workflow_id_conflict` structlog WARNING surfaces with caller_supplied_workflow_id, server_derived_workflow_id, caller_agent_id, and dispatch_id fields. No audit row for the soft conflict — high-frequency call site, audit log not the right surface (per Q3 Reading A). The chain walk uses a recursive CTE single-query within the existing `storage.transaction()` envelope; the CTE is bounded by `_WORKFLOW_CHAIN_MAX_DEPTH` for cycle defense.

Per-bucket attribution is **query-time derivation**, not a stored field. The daemon polls `https://api.anthropic.com/api/oauth/usage` (`anthropic-beta: oauth-2025-04-20`) at 30s cadence and aggregates against the cached snapshot at query time in `get_token_usage` and `get_utilization_report`. A single API call increments multiple counters simultaneously (e.g., a Sonnet call lands on `subscription_5h` AND `subscription_7d` AND `subscription_7d_sonnet`); per-bucket aggregation is derived from `model` plus the snapshot rather than stamped at write time. See `project_subscription_first_architecture` memory.

**Allowed for all four agents.**

**Audited.** Action: `api.record_call`.

#### `get_token_usage`

```python
class GetTokenUsageInput(BaseModel):
    agent_id: str | None = None       # None = all agents (rolled up)
    window: Literal['current','last','today','last_24h'] = 'current'

class GetTokenUsageOutput(BaseModel):
    input_tokens: int
    output_tokens: int
    cost_usd: float
    by_model: dict[str, dict]         # per-model breakdown
    by_agent: dict[str, dict] | None  # only when agent_id is None
    window_start: str
    window_end: str
```

**Hierarchy check**: PM/Engineer can read everyone in their subtree. Implementer/Tester can read only their own usage.

**Not audited.**

#### `get_burn_rate`

```python
class GetBurnRateInput(BaseModel):
    window: Literal['current','last','last_24h'] = 'current'

class GetBurnRateOutput(BaseModel):
    tokens_per_hour: float
    projected_window_total: int
    cost_per_hour: float
    projected_window_cost: float
```

**Not audited.**

#### `check_budget_for_task`

```python
class CheckBudgetForTaskInput(BaseModel):
    complexity: int     # 1..5
    priority: int       # 1..5

class CheckBudgetForTaskOutput(BaseModel):
    recommended_model: Literal['claude-haiku-4-5','claude-sonnet-4-6','claude-opus-4-7']
    recommended_bucket: Literal['subscription_5h', 'api_key_overflow']
    routing_factors: list[str]      # named tags describing decision context (queryable as data)
    fallback_active: bool           # true when snapshot was unavailable / stale; per-role defaults applied
    rationale_text: str             # human-readable summary
```

Output schema amended at M5-subscription-routing commit 4 from the original `rationale: str` to the structured shape above. ``routing_factors`` carries named tags (e.g., ``per_role_default_engineer``, ``subscription_7d_opus_at_67pct_below_threshold``, ``opus_downgraded_to_sonnet_due_to_7d_opus_high``) so tier-2 observability analysis can query routing decisions as data without string parsing. Pct-format factors follow the stable convention ``<dimension>_at_<value>pct_<context>``. ``fallback_active`` flags the snapshot-unavailable degraded path; ``rationale_text`` always names the fallback state explicitly when active.

Stub in M5 — concrete routing logic refined as Mike observes the system run. Subscription-first routing logic (per `project_subscription_first_architecture` memory):

1. **`subscription_5h` normal/upgrade pace.** Route against the subscription, tier per task complexity (defaults: PM=Opus, Engineer=Sonnet, Implementer=Sonnet, Tester=Sonnet, with priority-driven upgrades). Most common path.
2. **`subscription_7d_opus` high (≥80%, threshold pending operational data).** Downgrade Opus → Sonnet for non-critical work; Opus reserved for critical-priority tasks until the 7-day Opus counter recovers. Routing tier choice changes; the 5-hour cycle continues to drive immediate pacing.
3. **`subscription_5h` conserve (<20% remaining).** Finish in-flight; pause new high-token dispatches; PM compaction pre-staged. Engineer compacts at 80% per `11-engineer-bridge.md` § Engineer compaction.
4. **`subscription_5h` rate_limited.** Surface `info`-urgency to Mike. Resume aggressively at window reset.
5. **Subscription saturated, `extra_usage.is_enabled: false`.** Queue task; surface `urgent`-urgency to Mike — autonomous chain is genuinely capacity-constrained. Operator decides whether to enable overflow.
6. **Subscription saturated, `extra_usage.is_enabled: true`.** Route to `api_key_overflow`. Surface `info`-urgency to Mike per call (overflow billing is real money).

Tier defaults remain conservative (Sonnet for Engineer/Implementer/Tester) with priority-driven upgrades. The routing logic decides per-task; defaults are not blanket-upgraded to Opus.

**Not audited.** (Read-only routing decisions called on every planning iteration; structured `routing_factors` surface decisions at the API boundary, and structlog DEBUG-level traces are available for operator deep-dive without bloating audit_log.)

### Window state and PM mode (M5/M6)

Three tools support PM's continuous-mode operation. `get_window_state` and `set_pm_mode` ship at M5. `get_utilization_report` ships at M6 alongside the dashboard summary.

#### `get_window_state`

```python
class GetWindowStateInput(BaseModel):
    agent_id: str | None = None                                   # default: caller's own agent
    bucket: Literal[
        'subscription_5h',
        'subscription_7d',
        'subscription_7d_opus',
        'subscription_7d_sonnet',
        'api_key_overflow',
    ] = 'subscription_5h'                                          # default: primary pacing counter

class GetWindowStateOutput(BaseModel):
    bucket: str
    window_start: str
    window_end: str
    utilization: float                                             # 0-100, normalized per /api/oauth/usage
    minutes_to_reset: int
    pace_recommendation: Literal['normal', 'upgrade', 'conserve', 'rate_limited', 'unknown']
    snapshot_age_seconds: int | None                               # age of cached snapshot; None on cold-start
    null_counters: list[str]                                       # counters returning null on the latest poll
    current_pm_mode: str | None                                    # current pm_mode_log mode
    most_recent_pm_mode_log_timestamp: str | None                  # for mode-flip detection by callers
```

Returns the current state for the specified agent within the requested bucket, derived from the daemon's polled `/api/oauth/usage` snapshot. `pace_recommendation` is computed from `utilization` and `minutes_to_reset` per the table in `02-architecture.md` § Window-aware burn (per bucket). PM's continuous-mode planner consumes `subscription_5h` on every planning iteration as the primary pacing signal; per-model 7-day counters drive routing-tier decisions in `check_budget_for_task`.

`snapshot_age_seconds` exposes cache staleness directly. The daemon's poll cadence is 30s default with exponential backoff on 429 (60→120→240→480→600s ceiling). Three distinct states with three distinct values per M5-pm-mode-and-window-state commit 6 prep Q3 disposition:

- `None` — no snapshot exists (cold-start; daemon just started, no successful poll yet).
- `0` — snapshot just polled.
- positive int — snapshot is N seconds old.

When `snapshot_age_seconds > 120`, callers treat utilization as `unknown` for that planning iteration; routing logic degrades accordingly. `None` (cold-start) also forces `pace_recommendation='unknown'` since no data is available. `null_counters` lists which counters returned null on the latest poll (per the B-1 fallback rule: null is treated as "feature unknown; fall back to overall counter" — fallback decisions live with the routing layer per Q4 disposition, not at this tool's layer).

The `current_pm_mode` and `most_recent_pm_mode_log_timestamp` fields let Engineer detect mode flips against a stored cursor without a separate `get_pm_mode` tool. Callers that don't need mode info ignore these fields.

**Allowed for PM and Engineer.** Implementer/Tester see only their own usage via `get_token_usage`.

**Not audited** (read-only, called frequently).

#### `set_pm_mode`

```python
class SetPmModeInput(BaseModel):
    mode: Literal['continuous', 'interactive', 'paused_blocked', 'paused_question']
    reason: str | None = None

class SetPmModeOutput(BaseModel):
    mode: str
    transition_logged: bool         # True if a row was written to pm_mode_log (False on no-op same-mode write)
```

PM uses this for deliberate self-reported mode transitions (entering `paused_question` when surfacing a blocking question to Mike, returning to `continuous` after resolution). The daemon writes auto-transitions (Mike's MCP token use → `interactive`) directly to `pm_mode_log` without going through this tool — the tool is for PM's self-reports.

**Allowed for PM only.**

**Audited.** Action: `pm_mode.transition`.

#### `declare_current_focus`

```python
class DeclareCurrentFocusInput(BaseModel):
    objective: str
    output_type: Literal[
        'document', 'code_change', 'decision', 'review', 'investigation'
    ]
    acceptance_criteria: str
    bound_workflow_id: str | None = None     # chain root task_id when workflow-bound

class DeclareCurrentFocusOutput(BaseModel):
    focus_id: str
    superseded_prior_focus_id: str | None    # set when auto-supersede fired
```

Ships at M5-continuous-pm-driver commit 2 alongside `pm_current_focus` table + `complete_current_focus` + `get_current_focus`. PM declares the active work-unit boundary: an objective ("ship the X feature"), an `output_type` (one of five closed-enum values per F3.6 Q1 disposition), and acceptance criteria the loop validates the actual output against at completion (loop-side validation per F2.2 A1 disposition). Optional `bound_workflow_id` ties the focus to a top-level submitted task chain when applicable; NULL for review/investigation work that doesn't map to a dispatched task chain (per F3.6 Q3 + Q8 dispositions).

**Auto-supersede semantics** (F3.6 Q8 Reading A): when a prior incomplete focus exists at declare time, the prior row is auto-completed in the same transaction with `contract_finding_text='superseded'` (and `contract_validated_bool` left NULL — supersede is not a validation outcome). Two audit rows emit on the auto-supersede path: `pm.current_focus.superseded` (target = prior focus) then `pm.current_focus.declared` (target = new focus). The at-most-one-active-focus invariant is preserved across Mike-interrupt-driven pivots.

**Allowed for PM only.**

**Audited.** Actions: `pm.current_focus.declared` (always); `pm.current_focus.superseded` (when auto-supersede fired). Full per-action payloads per F3.6 Q4 disposition; see `11-engineer-bridge.md` § Audit log entries.

#### `complete_current_focus`

```python
class CompleteCurrentFocusInput(BaseModel):
    contract_validated: bool
    finding_text: str | None = None
    actual_output_summary: str | None = None  # used in contract_violation audit

class CompleteCurrentFocusOutput(BaseModel):
    focus_id: str
    contract_validated: bool
```

Closes the active focus with the loop's validation result. The loop computes `contract_validated` (True if actual output matches `acceptance_criteria` within the declared `output_type`; False if mismatch surfaced); this writer records the result. **Implicit-current shape** (F3.6 Q9 Reading B): no `focus_id` parameter — PM has at-most-one active focus per the Q8 invariant; the handler reads it implicitly and completes that one. Calling with no active focus raises an error (the loop's logic is expected to declare-then-complete; a no-active call indicates a contract violation in the loop, not a silent no-op).

**Audit shape** (F3.6 Q4 Reading A): `pm.current_focus.completed` always emits; `pm.current_focus.contract_violation` emits in the same transaction when `contract_validated=False`. Both target the focus_id; payloads diverge per Q4 spec.

**Allowed for PM only.**

**Audited.** Actions: `pm.current_focus.completed` (always); `pm.current_focus.contract_violation` (when `contract_validated=False`).

#### `get_current_focus`

```python
class GetCurrentFocusOutput(BaseModel):
    current_focus: PmCurrentFocus | None  # None when no active focus

class PmCurrentFocus(BaseModel):
    id: str
    objective: str
    output_type: Literal[
        'document', 'code_change', 'decision', 'review', 'investigation'
    ]
    acceptance_criteria: str
    bound_workflow_id: str | None
    declared_at: str
    completed_at: str | None                 # None when active
    contract_validated_bool: bool | None     # None until completed
    contract_finding_text: str | None        # None on declare; set on complete or supersede
```

Returns the active `pm_current_focus` row or `None`. Daemon-internal MCP read consumed by the continuous-loop driver subprocess at commits 7-8. Per F3.6.C Q6 Reading A disposition, ships co-shipping with `declare_current_focus` / `complete_current_focus` rather than deferring to commit 7 — co-design with the table schema avoids prep-debt at commit 7. Per `BACKLOG.md` § Observability roadmap scope clarification 2026-05-04, the "read-side post-flag-flips" sequencing applies to operator-facing tier-2 surfaces, not daemon-internal subprocess reads; a planned subprocess consumer is sufficient justification to ship the read alongside the write.

**Empty-state contract**: `current_focus = None` when no active focus exists. The discriminated container shape lets the loop driver distinguish "no focus declared yet" from "RPC failed" cleanly.

**Allowed for PM only.** Engineer's mode-aware planning may eventually need read access (mirror Q4 set_pm_mode disposition pattern); promote at M5-flag-flips if Engineer's continuous-loop spec lands with that need.

**Not audited** (read-only, called frequently from PM's loop). Mirrors `get_window_state`'s not-audited convention.

#### `get_utilization_report`

```python
class GetUtilizationReportInput(BaseModel):
    session_start: str | None = None    # ISO 8601; default: 24h ago

class WindowUtilization(BaseModel):
    bucket: Literal[
        'subscription_5h',
        'subscription_7d',
        'subscription_7d_opus',
        'subscription_7d_sonnet',
        'api_key_overflow',
    ]
    window_start: str
    window_end: str
    utilization: float                   # 0-100, normalized per /api/oauth/usage
    phases_shipped: int
    questions_to_mike: int
    unused_attribution: dict[str, int]   # 'mike_interactive' | 'queue_starved' | 'rate_limited' | 'unattributable' → token-equivalent

class GetUtilizationReportOutput(BaseModel):
    subscription_5h_windows: list[WindowUtilization]
    subscription_7d_windows: list[WindowUtilization]
    subscription_7d_opus_windows: list[WindowUtilization]
    subscription_7d_sonnet_windows: list[WindowUtilization]
    api_key_overflow_windows: list[WindowUtilization]   # only populated when extra_usage.is_enabled: true
    subscription_5h_utilization_pct: float               # primary metric — target 70-90%
    subscription_7d_opus_utilization_pct: float          # routing-tier input — drives Opus → Sonnet downgrades
    subscription_7d_sonnet_utilization_pct: float
    api_key_overflow_used_credits: float | None          # only when extra_usage.is_enabled: true
    null_counter_observations: dict[str, int]            # counter name → number of poll cycles it returned null
    unused_reasons: list[str]                            # human-readable explanations
```

Consumed by PM at end-of-session to populate the utilization section of the session report. `subscription_5h_utilization_pct` is the primary metric (target 70-90%); per-model 7-day percentages are routing-tier inputs (drive Opus → Sonnet downgrades when high). The daemon attributes unused capacity per bucket to known causes (Mike interactive periods, queue starvation, rate-limit events); unattributable unused capacity is flagged in `unused_reasons` so PM can surface to Mike as a `question`.

`null_counter_observations` exposes how often each per-model counter returned null during the session — operational data driving the B-1 fallback-vs-revision decision. After a week of operation this tells us whether nulls are persistent (treat as "feature unavailable" permanently) or transient (treat as 0).

API-key overflow utilization is only populated when `extra_usage.is_enabled: true`. When the operator has overflow disabled (Phase 1 default), the `api_key_overflow_*` fields are empty/None.

**Allowed for PM and Mike.**

**Audited.** Action: `utilization.report_read`.

### Knowledge

#### `read_knowledge`

```python
class ReadKnowledgeInput(BaseModel):
    path: str   # e.g. /product/substrate/foundation-doc.md

class ReadKnowledgeOutput(BaseModel):
    path: str
    content: str
    sha256: str
    last_modified: str
```

**Permission check**: `path` must match one of the agent's `knowledge_paths_readable` glob patterns. Path traversal (`..`, absolute paths escaping the knowledge root) → `PERMISSION_DENIED`, never `NOT_FOUND` (don't leak structure).

**Not audited by default.** Specific sensitive paths can be opted in via config.

#### `list_knowledge`

```python
class ListKnowledgeInput(BaseModel):
    prefix: str = '/'
    recursive: bool = False

class ListKnowledgeOutput(BaseModel):
    entries: list[KnowledgeEntry]   # path, type (file/dir), size, last_modified
```

**Permission check**: results filtered to readable paths. Mike sees everything.

**Not audited.**

#### `write_knowledge`

```python
class WriteKnowledgeInput(BaseModel):
    path: str
    content: str
    expected_sha256: str | None = None  # optimistic concurrency

class WriteKnowledgeOutput(BaseModel):
    path: str
    sha256: str
    last_modified: str
```

**Permission check**: `path` must match one of the agent's `knowledge_paths_writable` glob patterns.

If `expected_sha256` is provided and doesn't match the current file hash → `CONFLICT`. Use this when an agent has read a file, intends to modify it, and wants to detect concurrent writes.

**Audited.** Action: `knowledge.write`. `details_json` includes the SHA256 before and after.

### Routing & discovery

#### `list_agents`

```python
class ListAgentsInput(BaseModel):
    role: str | None = None
    status: str | None = None

class ListAgentsOutput(BaseModel):
    agents: list[AgentSummary]   # id, role, reports_to, status
```

**Allowed for all agents.** No hierarchy filtering — agents need to know who exists to coordinate.

**Not audited.**

#### `get_agent_config`

```python
class GetAgentConfigInput(BaseModel):
    agent_id: str

class GetAgentConfigOutput(BaseModel):
    role: str
    display_name: str
    reports_to: str | None
    manages: list[str]
    mcp_tools_allowed: list[str]
    knowledge_paths_readable: list[str]
    knowledge_paths_writable: list[str]
    # system_prompt deliberately NOT exposed via this tool
```

**Hierarchy check**: caller can see configs for self, ancestors, descendants. Cross-tree visibility is allowed but limited to the public fields above.

**Not audited.**

#### `get_dashboard_summary`

```python
class GetDashboardSummaryInput(BaseModel):
    pass

class GetDashboardSummaryOutput(BaseModel):
    markdown: str
```

Returns a Markdown summary for Mike's session start: open queue, recent completions, blockers, escalations, current burn rate, last 5 audit entries.

**Allowed only for PM and Mike.** Engineer, Implementer, Tester are denied.

**Audited.** Action: `dashboard.read`.

#### `get_org_chart`

```python
class GetOrgChartInput(BaseModel):
    pass

class GetOrgChartOutput(BaseModel):
    chart: dict     # nested tree: {role, agent_id, manages: [...]}
```

**Allowed for all agents.**

**Not audited.**

### Subagent dispatch (bridge — designed at M3)

These tools are listed here for completeness; their precise wire shapes finalize at M3 alongside the bridge architecture.

#### Rate-limit handling

Dispatch tools must handle Anthropic rate-limit responses gracefully — this is expected operational reality on a subscription-first cost model, not a failure. Behavior:

1. Sub-agent invocation hits a rate-limit response.
2. Bridge captures the limit, logs `event=dispatch.rate_limited` with model and `retry_after`.
3. Dispatch is queued (status `queued`) until the retry-after window elapses.
4. Engineer is informed via dispatch result polling — `get_dispatch_status` returns `status='queued'` with a `retry_after` field populated.
5. Engineer surfaces the rate-limit to PM via task notes.
6. PM may notify Mike via `notify_human(urgency='question', ...)` if the delay materially affects work. Per the PM system prompt, rate-limit-blocked dispatches are framed as `question`-urgency, not `urgent`.
7. **No silent retry.** Each retry attempt is recorded in the audit log as a distinct event.

#### `dispatch_implementer`

```python
class DispatchImplementerInput(BaseModel):
    task_id: str
    spec_path: str            # path within knowledge base or working directory
    working_directory: str    # e.g. C:\Users\mbgol\dev\meridian-homepoc

class DispatchImplementerOutput(BaseModel):
    dispatch_id: str
    status: Literal['queued','running']
```

**Allowed only for Engineer.**

**Audited.** Action: `dispatch.implementer`.

#### `dispatch_tester`

```python
class DispatchTesterInput(BaseModel):
    task_id: str
    commit_hash: str          # what to verify
    working_directory: str
    assertion_spec: str       # what success looks like
```

**Allowed only for Engineer.**

**Audited.** Action: `dispatch.tester`.

#### `get_dispatch_status`

```python
class GetDispatchStatusInput(BaseModel):
    dispatch_id: str

class GetDispatchStatusOutput(BaseModel):
    dispatch_id: str
    status: Literal['queued','running','completed','failed','declined']
    started_at: str | None
    completed_at: str | None
```

**Allowed for Engineer (own dispatches), PM (read-only across the subtree).**

**Not audited.**

#### `get_dispatch_result`

```python
class GetDispatchResultInput(BaseModel):
    dispatch_id: str

class GetDispatchResultOutput(BaseModel):
    dispatch_id: str
    status: Literal['completed','failed','declined']
    commit_hash: str | None
    test_results: dict | None
    queue_items_raised: list[str]
    decline_reason: str | None
    artifacts: list[str]      # paths in working_directory
```

**Allowed for Engineer (own dispatches), PM (read-only).**

**Audited.** Action: `dispatch.result_read`.

### Notification (PM-only)

These tools are PM-only. Engineer, Implementer, and Tester get `PERMISSION_DENIED` on any of them. Phase 1 communication primitive: PM↔Mike via Telegram. The notification service is implemented at M6.

#### `notify_human`

```python
class NotifyHumanInput(BaseModel):
    urgency: Literal['urgent','question','info']
    subject: str
    body: str
    related_task_id: str | None = None

class NotifyHumanOutput(BaseModel):
    outbound_message_id: str
    delivered: bool        # True when sent immediately (urgent/question); False when queued for batch (info)
```

`urgent` and `question` send immediately. `info` is batched — the notification service drains the queue at end-of-session or every hour, max 4 per send. If `related_task_id` is set and `urgency='question'`, the related task is set `blocking_human=1` until Mike's reply lands.

If Telegram is unreachable at send time, the row is persisted with `delivered=0` and the service retries with exponential backoff. The audit log captures send attempts.

**Audited.** Action: `notification.send`.

#### `get_unread_replies`

```python
class GetUnreadRepliesInput(BaseModel):
    pass

class GetUnreadRepliesOutput(BaseModel):
    messages: list[InboundMessage]   # marked consumed=1 atomically when read
```

PM calls this on session start (and whenever it needs fresh signal from Mike). Returns rows from `inbound_messages` where `consumed=0`, marks them consumed in the same transaction. If a returned message has `in_reply_to` pointing to a `question`-urgency outbound, the corresponding task's `blocking_human` is cleared in the same transaction.

**Audited.** Action: `notification.consume`.

#### `get_message_history`

```python
class GetMessageHistoryInput(BaseModel):
    direction: Literal['inbound','outbound','both'] = 'both'
    since: str | None = None
    until: str | None = None
    related_task_id: str | None = None
    limit: int = 50

class GetMessageHistoryOutput(BaseModel):
    inbound: list[InboundMessage]
    outbound: list[OutboundMessage]
```

Read-only. PM uses this to reconstruct prior conversations with Mike when context is needed.

**Not audited** (read-only, may be called frequently).

### Audit

#### `get_audit_log`

```python
class GetAuditLogInput(BaseModel):
    actor: str | None = None
    action: str | None = None
    since: str | None = None    # ISO 8601
    until: str | None = None
    limit: int = 100
    offset: int = 0

class GetAuditLogOutput(BaseModel):
    entries: list[AuditEntry]
    total: int
```

**Allowed for Mike only in Phase 1.** Phase 2 may extend to PM with subtree-scoped views.

**Audited.** Action: `audit.read`. (Yes, reading the audit log writes to the audit log.)

## Permission summary table

| Tool | Mike | PM | Engineer | Implementer | Tester |
|---|---|---|---|---|---|
| submit_task | yes | yes (eng only) | yes (impl/tester only) | no | no |
| get_queue | yes | yes | yes | own only | own only |
| update_task_status | yes | yes (own/sub) | yes (own/sub) | own only | own only |
| set_task_priority | yes | yes | yes | no | no |
| claim_task | no | yes | yes | yes | yes |
| get_subtasks | yes | yes | yes | own chain | own chain |
| record_api_call | n/a | yes | yes | yes | yes |
| get_token_usage | yes | subtree | subtree | own | own |
| get_burn_rate | yes | yes | yes | no | no |
| check_budget_for_task | yes | yes | yes | no | no |
| read_knowledge | all | per glob | per glob | per glob | per glob |
| list_knowledge | all | per glob | per glob | per glob | per glob |
| write_knowledge | all | per glob | per glob | per glob | per glob |
| list_agents | yes | yes | yes | yes | yes |
| get_agent_config | yes | yes | yes | yes | yes |
| get_dashboard_summary | yes | yes | no | no | no |
| get_org_chart | yes | yes | yes | yes | yes |
| dispatch_implementer | no | no | yes | no | no |
| dispatch_tester | no | no | yes | no | no |
| get_dispatch_status | yes | subtree | own | no | no |
| get_dispatch_result | yes | subtree | own | no | no |
| get_window_state | yes | yes | yes | no | no |
| set_pm_mode | no | yes | no | no | no |
| declare_current_focus | no | yes | no | no | no |
| complete_current_focus | no | yes | no | no | no |
| get_current_focus | no | yes | no | no | no |
| get_utilization_report | yes | yes | no | no | no |
| notify_human | no | yes | no | no | no |
| get_unread_replies | no | yes | no | no | no |
| get_message_history | no | yes | no | no | no |
| get_audit_log | yes | no (Phase 1) | no | no | no |

## Hierarchy invariants summary

1. **Mike → PM only.** Mike's tokens have access to PM-level surfaces, the audit log, and the notification tools. He does not call Engineer-level tools (he could technically as the human, but the protocol is "Mike talks to PM"; this is enforced by *not* connecting Mike's Claude Code session to an Engineer-role token via OAuth, not by server-side denial). **Caller-role detail:** Mike's interactive Claude Code session connects via the PM-role OAuth flow, so his MCP requests carry `role:pm` in the access token's scope. Server-side `_resolve_caller_role_via_token` resolves Mike's `caller_role` to `"pm"` — there is no separate `"mike"` role at the token / handler layer. The "Mike" column in the permission summary table above is a human-readability convenience naming the operator persona; the role-gate on every PM-allowed handler accepts `caller_role == "pm"` for both Mike (via OAuth) and a static-token PM-continuous subprocess.
2. **PM → Engineer only.** PM has no `dispatch_*` tools. PM cannot directly invoke `dispatch_implementer` or `dispatch_tester`. PM dispatches work to Engineer via `submit_task`; only Engineer has dispatch tools in its allowed list. The dispatch tools are categorically different from `submit_task` — `submit_task` enqueues work, `dispatch_*` invokes a sub-agent immediately.
3. **Engineer → {Implementer, Tester} only.** Engineer has no `get_dashboard_summary`, no `notify_human`, no Mike-direct surface. Engineer can read knowledge under `/product/**` (including PM-authored foundation doc and `/product/substrate/decisions/**`) but cannot write to PM's writable paths. Engineer's writable knowledge paths are limited to work-artifact paths under `/product/substrate/work-logs/**`. Foundation decisions under `/product/substrate/decisions/**` are PM-write-only.
4. **Implementer/Tester sibling isolation.** Implementer and Tester see only their own tasks, their own usage, their own dispatch results. They cannot read each other's tasks even within the same dispatch chain. Tester reads Implementer's results via Engineer-mediated tools — Engineer fetches `get_dispatch_result` and passes the relevant fields (commit hash, artifacts list) into Tester's spec — not directly from Implementer's task records.
5. **Notification access is PM-only.** `notify_human`, `get_unread_replies`, `get_message_history` are not in any other agent's allowlist. Engineer/Implementer/Tester surface upward through the existing task chain.
6. **Decline propagation** is upward through `update_task_status('declined', notes=...)`. The audit log preserves the chain via `parent_task_id`.
