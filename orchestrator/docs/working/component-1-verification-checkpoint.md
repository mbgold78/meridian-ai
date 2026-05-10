# Component 1 verification checkpoint

Pre-Components-2/3 empirical verification. Component 1 (OAuth-PM
registry binding) has landed locally + integration tests pass; this
checkpoint exercises the runtime path against a live daemon to confirm
the architectural fix closes the operator-interface gap β surfaced.

If pass: proceed to Components 2/3 in same commit. If fail: surface
findings; commit shape disposition reopens.

## What Component 1 changes

- `auth.py`: `TokenStore.find_oauth_caller_by_client_id(client_id, role) -> agent_id | None` helper for alpha persistence
- `oauth.py:exchange_authorization_code`: looks up existing agent_id by client_id before issuing; reuses if found (alpha persistence); invokes `on_oauth_caller_registered` callback after issuance
- `agents/registry.py`:
  - Snapshot data shape: `dict[agent_id, RegisteredAgent]` (was `dict[role, RegisteredAgent]`)
  - `RegisteredAgent.source: TokenSource` field added (`"static"` | `"oauth"`)
  - `get(role)` semantics preserved (returns static-source supervisor)
  - `get_by_agent_id(agent_id)` becomes direct dict lookup
  - `_reload_locked` extends to include active OAuth tokens in snapshot
  - `register_oauth_caller(storage, company_id, agent_id, role)` helper for explicit OAuth registration + agents-table UPSERT
- `server.py:build_server` + `serve()`: thread `on_oauth_caller_registered` callback param
- `main.py`: closure built over `(registry, storage, company_id)`; passed to `serve_mcp`

## Pre-conditions

Daemon must be running with supervisors enabled, all keyring tokens issued, substrate path configured. Per `M5-acceptance-beta-readiness.md` Section 2 operator-setup checklist. Confirm before starting:

- `claude mcp list` shows `meridian-orchestrator: http://127.0.0.1:8765/mcp (HTTP) - ! Needs authentication` (registration exists; daemon reachable; OAuth handshake pending)
- `uv run python -m scripts.list-tokens` shows active static tokens for all four roles (pm, engineer, implementer, tester)
- Daemon log shows `pm_supervisor.started` + `engineer_supervisor.started` + `startup.complete` with `dispatcher_constructed=true`

If pre-conditions fail: run β-readiness checklist before this checkpoint.

## Verification sequence

### Step 1 — Capture pre-OAuth baseline state

```
uv run python -m scripts.list-tokens
```

Expected: 4 active static tokens (pm/engineer/implementer/tester); zero or more revoked records.
**Empirical anchor to capture:** snapshot of `list-tokens` output. Note PM static `token_id` + `agent_id`; OAuth tokens may exist from prior attempts (pre-Component-1).

```sql
-- via direct SQLite or future query tool
SELECT id, role, reports_to FROM agents;
```

Expected: 4 rows, one per static-source agent (pm/engineer/implementer/tester). PM row has `reports_to=NULL`.
**Empirical anchor to capture:** agent_id values per role.

### Step 2 — Open interactive Claude Code session + OAuth flow

```
claude
```

Inside the session:
- Type `/mcp`. Expected output: lists registered MCP servers including `meridian-orchestrator: ! Needs authentication`.
- Select `meridian-orchestrator` → `Authenticate` option.
- Browser opens to daemon's `/authorize` endpoint. The daemon's approval form shows the connector name + redirect URI + `role:pm` scope. Click **Approve**.
- Browser redirects back to claude (loopback URL); claude session shows OAuth handshake completion.
- Re-run `/mcp` inside session. Expected: `meridian-orchestrator: ✓ Connected`.

**Empirical anchors to capture:**
- Daemon log entry `oauth.token_issued` with `client_id`, `agent_id`, `role=pm`, `agent_id_reused=False` (first flow for this client_id)
- Daemon log entry `agents.oauth_caller_registered` with `agent_id` matching the issued token's agent_id and `role=pm`
- `uv run python -m scripts.list-tokens` shows new active OAuth token with `role=pm`, `source=oauth`

### Step 3 — Verify registry binding + agents table state

```sql
SELECT id, role, reports_to FROM agents WHERE role = 'pm';
```

Expected: 2 rows for `role='pm'` — the static-PM (from initial seed) + the OAuth-PM (newly registered). OAuth-PM row has `reports_to=NULL`.
**Empirical anchor to capture:** OAuth-PM agent_id matches the one logged at `oauth.token_issued`.

### Step 4 — Submit a substrate work item via natural-language conversation

In the interactive Claude Code session, instruct claude:

> "Submit a task to Engineer for substrate work item: <choose β10-criterion task — mechanically simple but architecturally non-trivial>."

Claude (acting as PM via OAuth) calls `submit_task(...)` through the orchestrator MCP.

**Empirical anchors to capture:**
- Claude session reports successful `submit_task` invocation with returned `task_id`
- `audit_log` row with `action='task.submit'` + `actor=<oauth-pm-agent-id>` + `target=<task_id>` + `details_json` containing the task's submitted_by + agent_role
- `tasks` table row exists with `id=<task_id>`, `agent_role='engineer'`, `submitted_by=<oauth-pm-agent-id>` or `'human:mike'` per submit_task's `submitted_by` resolution logic

**Pass criterion at this step:** `submit_task` succeeds without `PERMISSION_DENIED` or `NOT_FOUND` errors. This is the load-bearing verification — pre-Component-1, this would fail because OAuth-PM agent_id wasn't in the agent registry's lookup surface, breaking PM hierarchy validation in `submit_task`'s permission check.

### Step 5 — Verify Engineer-supervisor picks up the task

Within ~60 seconds (Engineer-supervisor's continuous-loop tick interval), the Engineer subprocess should claim the submitted task.

**Empirical anchors to capture:**
- `audit_log` row with `action='task.claim'` + `actor=<engineer-agent-id>` + `target=<task_id>`
- `tasks` table row updated: `status='claimed'`, `claimed_by=<engineer-agent-id>`
- Optional further chain (depending on substrate work item shape): `dispatch.issued` audit row → `dispatch.subprocess_started` → Implementer commit → `dispatch.complete_called` → Engineer dispatches Tester → eventual `task.status_change` to `completed`

**Pass criterion at this step:** Engineer claims the task. Full dispatch chain completion is bonus (validates β5/β6 wiring from commits 3.5 + 3.6); minimum verification is "task moves out of pending and is claimed by Engineer."

### Step 6 — Re-OAuth flow → alpha persistence verification

Close the interactive Claude Code session. Re-open with `claude`. Re-run OAuth flow per Step 2.

**Empirical anchors to capture:**
- Daemon log entry `oauth.token_issued` shows `agent_id_reused=True` this time (alpha persistence kicked in)
- The new OAuth token's `agent_id` matches the agent_id from Step 2

**Pass criterion at this step:** Mike-on-Mike's-machine has stable agent_id across OAuth re-authorizations. Audit consistency preserved.

## Failure modes + dispositions

### Mode A — `submit_task` raises `PERMISSION_DENIED` or `NOT_FOUND`

Indicates Component 1's registry binding didn't fire OR the binding shape doesn't unblock `submit_task`'s validation path. Possible causes:
- `on_oauth_caller_registered` callback failed silently (check daemon log for `oauth.caller_registered_callback_failed`)
- `register_oauth_caller` UPSERT didn't write to agents table (check via SQL query)
- `submit_task`'s permission check uses a registry method other than `get_by_agent_id` (check `tools/tasks.py` permission check path)

**Disposition:** surface with daemon log + agents table query + the exact `submit_task` error message; commit shape reopens.

### Mode B — OAuth flow completes but registry binding visible only after daemon restart

Indicates the explicit `register_oauth_caller` callback path didn't fire OR the callback returned without updating the snapshot. Possible causes:
- Callback throws exception caught in `oauth.py:exchange_authorization_code` (check daemon log for `oauth.caller_registered_callback_failed`)
- `register_oauth_caller` updates snapshot but `_lock` contention prevents visible change (race condition; rare)

**Disposition:** surface with daemon log; check whether `register_oauth_caller` is being invoked at all; commit shape reopens if invocation is broken.

### Mode C — Step 6 alpha persistence fails (new agent_id minted)

Indicates `find_oauth_caller_by_client_id` didn't find the existing record OR `TokenStore.issue` didn't honor the passed agent_id. Possible causes:
- Client_id isn't actually stable across Claude Code re-auths (M2/M3 contract assumption violated)
- `find_oauth_caller_by_client_id` filter logic has a bug (compare to test_auth.py unit test)

**Disposition:** surface with `list-tokens` showing OAuth records for both flows; identify whether client_id matched; multi-device BACKLOG entry may need expansion.

### Mode D — Steps 1-5 pass but Engineer-supervisor doesn't pick up task

Not a Component 1 failure — indicates Engineer-supervisor's continuous-loop logic isn't claiming pending tasks OR Engineer-supervisor isn't running. Check:
- Daemon log shows `engineer_supervisor.started` (supervisor was spawned at startup)
- Engineer subprocess stderr.log under `<dispatches_root>/<engineer-spawn-dispatch_id>/stderr.log` for runtime errors
- Engineer-supervisor's spawn token may have permission issues (check audit for permission-denied entries with `actor=<engineer-agent-id>`)

**Disposition:** orthogonal to Component 1; route as separate finding (could be Engineer-side runtime gap that β-readiness checklist should have caught at supervisor-startup verification).

## Pass/fail summary

**PASS** = all of:
- Step 2 OAuth flow completes; daemon logs `oauth.token_issued` + `agents.oauth_caller_registered`
- Step 3 agents table has OAuth-PM row with `reports_to=NULL`
- Step 4 `submit_task` succeeds; task row created
- Step 5 Engineer claims the task within ~60s
- Step 6 alpha persistence: re-OAuth reuses same agent_id

**FAIL** on any → surface findings; commit shape disposition reopens.

## After verification

Pass → proceed to Components 2/3 + 4/5 (yaml annotations + 8 BACKLOG entries + multi-device BACKLOG entry + wrapper scripts + tests + docs) in same commit.

Fail → surface to operator; do NOT land the commit; disposition cycle decides next step (re-architect Component 1, or split into separate commits, or scope-expand to address the gap that surfaced).
