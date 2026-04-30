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
    model: str                 # e.g. 'claude-opus-4-7', 'claude-sonnet-4-6'
    input_tokens: int
    output_tokens: int
    task_id: str | None = None # the task this call was for

class RecordApiCallOutput(BaseModel):
    usage_id: str
    window_start: str
```

`agent_id` is taken from the auth token, not the input. `cost_usd` is computed server-side from a model→price table maintained in `tools/budget.py`.

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
    rationale: str
```

Stub in M5 — concrete routing logic refined as Mike observes the system run. The stub returns recommendations based on complexity * priority and current burn rate.

**Not audited.**

### Window state and PM mode (M5/M6)

Three tools support PM's continuous-mode operation. `get_window_state` and `set_pm_mode` ship at M5. `get_utilization_report` ships at M6 alongside the dashboard summary.

#### `get_window_state`

```python
class GetWindowStateInput(BaseModel):
    agent_id: str | None = None     # default: caller's own agent

class GetWindowStateOutput(BaseModel):
    window_start: str
    window_end: str
    tokens_used: int
    tokens_estimated_remaining: int
    percentage_used: float
    minutes_to_reset: int
    pace_recommendation: Literal['normal', 'upgrade', 'conserve', 'rate_limited']
```

Returns the current 5-hour window state for the specified agent. `pace_recommendation` is computed from `percentage_used` and `minutes_to_reset` per the table in `02-architecture.md` § Window-aware burn. PM's continuous-mode planner consumes this on every planning iteration. Engineer also calls it before dispatching to avoid saturating its own window.

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

#### `get_utilization_report`

```python
class GetUtilizationReportInput(BaseModel):
    session_start: str | None = None    # ISO 8601; default: 24h ago

class WindowUtilization(BaseModel):
    window_start: str
    window_end: str
    tokens_used: int
    tokens_capacity: int
    percentage_used: float
    phases_shipped: int
    questions_to_mike: int
    unused_attribution: dict[str, int]   # 'mike_interactive' | 'queue_starved' | 'rate_limited' | 'unattributable' → tokens

class GetUtilizationReportOutput(BaseModel):
    windows: list[WindowUtilization]
    total_utilization_pct: float
    unused_reasons: list[str]            # human-readable explanations
```

Consumed by PM at end-of-session to populate the utilization section of the session report. The daemon attributes unused capacity to known causes (Mike interactive periods, queue starvation, rate-limit events); unattributable unused capacity is flagged in `unused_reasons` so PM can surface to Mike as a `question`.

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
| get_utilization_report | yes | yes | no | no | no |
| notify_human | no | yes | no | no | no |
| get_unread_replies | no | yes | no | no | no |
| get_message_history | no | yes | no | no | no |
| get_audit_log | yes | no (Phase 1) | no | no | no |

## Hierarchy invariants summary

1. **Mike → PM only.** Mike's tokens have access to PM-level surfaces, the audit log, and the notification tools. He does not call Engineer-level tools (he could technically as the human, but the protocol is "Mike talks to PM"; this is enforced by *not* connecting Mike's Claude Code session to an Engineer-role token via OAuth, not by server-side denial).
2. **PM → Engineer only.** PM has no `dispatch_*` tools. PM cannot directly invoke `dispatch_implementer` or `dispatch_tester`. PM dispatches work to Engineer via `submit_task`; only Engineer has dispatch tools in its allowed list. The dispatch tools are categorically different from `submit_task` — `submit_task` enqueues work, `dispatch_*` invokes a sub-agent immediately.
3. **Engineer → {Implementer, Tester} only.** Engineer has no `get_dashboard_summary`, no `notify_human`, no Mike-direct surface. Engineer can read knowledge under `/product/**` (including PM-authored foundation doc and `/product/substrate/decisions/**`) but cannot write to PM's writable paths. Engineer's writable knowledge paths are limited to work-artifact paths under `/product/substrate/work-logs/**`. Foundation decisions under `/product/substrate/decisions/**` are PM-write-only.
4. **Implementer/Tester sibling isolation.** Implementer and Tester see only their own tasks, their own usage, their own dispatch results. They cannot read each other's tasks even within the same dispatch chain. Tester reads Implementer's results via Engineer-mediated tools — Engineer fetches `get_dispatch_result` and passes the relevant fields (commit hash, artifacts list) into Tester's spec — not directly from Implementer's task records.
5. **Notification access is PM-only.** `notify_human`, `get_unread_replies`, `get_message_history` are not in any other agent's allowlist. Engineer/Implementer/Tester surface upward through the existing task chain.
6. **Decline propagation** is upward through `update_task_status('declined', notes=...)`. The audit log preserves the chain via `parent_task_id`.
