# Phase 1 Scope

## In scope

### Agents (4)

| Role | Reports to | Manages | Notes |
|---|---|---|---|
| PM | Mike (human) | Engineer | Top role. Coordinates Meridian product work. |
| Engineer | PM | Implementer, Tester | Dispatches sub-agents. Cannot surface to Mike. |
| Implementer | Engineer | — | Sub-agent. Migrated from `meridian-homepoc/.claude/agents/meridian-implementer.md`. |
| Tester | Engineer | — | Sub-agent. Migrated from `meridian-homepoc/.claude/agents/meridian-verifier.md`. |

### Capabilities

- MCP server exposing the tools listed in `06-mcp-tools-phase-one.md`
- SQLite storage with the schema in `05-data-model.md`
- Audit log capturing every state-changing tool call with full hierarchy chain
- Permission framework enforcing both per-tool allowlists and hierarchy invariants
- Engineer↔subagent dispatch bridge (architecture decided at M3, implemented at M4)
- Knowledge base with substrate foundation doc and queue synced from `meridian-homepoc`
- Budget/usage tracking via agent-self-report (`record_api_call`)
- 5-hour rolling window aggregation and burn-rate projection
- Service deployment with launchd/systemd/Windows Service configs
- Daily SQLite backup, log rotation, smoke test script

### Acceptance — Phase 1 Definition of Done

All twelve must be true:

1. Service starts cleanly via system service manager
2. Claude Code connects via MCP and lists all Phase 1 tools
3. Mike can chat with PM such that PM submits a task to Engineer, Engineer dispatches Implementer in `meridian-homepoc`, Implementer ships a real code change, Tester verifies, Engineer reports back, PM surfaces to Mike — end-to-end
4. PM-level decision discipline is observable: mechanical decisions decided silently, foundational decisions logged with reasoning, downgradable strategics decided + downgrade-justified, non-downgradable strategics escalated
5. Hierarchy enforcement: Mike cannot bypass PM. PM cannot bypass Engineer. Permissions enforced server-side.
6. State persists across service restart
7. All MCP tools have unit tests; all pass
8. Smoke test script passes end-to-end (full PM→Engineer→Implementer→Tester chain)
9. Audit log captures every tool call including hierarchy chain
10. Editing an agent YAML and re-invoking the agent uses the new prompt (hot reload works)
11. README documents how to install, configure, and run
12. Knowledge sync from substrate codebase works (PM can read foundation doc and queue)

## Explicitly out of scope for Phase 1

These are deferred. They are listed here so nobody (Mike, future Claude, anyone reading this in six months) wonders whether they were forgotten.

### Agents not in Phase 1

- **Lawyer, Accountant** — deferred indefinitely; company-formation work happens later.
- **Designer agent** — Mike uses Claude Designer manually for now.
- **Advisor agent** — deferred.
- **UAT Engineer** — Phase 2.
- **Lead Engineer** + **Backend Engineer** + **Frontend Engineer** + **Data Modeler** + **Integration Engineer** — Phase 2 (see `12-phase-two-engineering-team.md`).

### Capabilities not in Phase 1

- Inter-agent coordination beyond strict hierarchy. PM↔Engineer↔{Implementer,Tester} is the only chain.
- Web dashboard UI. Claude Code is the only interface.
- Anthropic usage API polling. Agents self-report via `record_api_call`. Polling is a Phase-2-or-later optimization.
- Cloud deployment.
- Multi-user support. Mike is the only human user.
- Remote access (no Tailscale, no VPN-driven exposure).
- Background queue worker process (Celery, RQ, etc.). Single async process only.

## Phase-2-compatibility requirements baked into Phase 1

Phase 1 interfaces must not preclude Phase 2. Specifically:

1. The `agents` table includes a `reports_to` field (FK to `agents.id`, NULL = reports to human). This is in v1 schema — not added later.
2. MCP tools enforce hierarchy: PM dispatches only to direct reports; cannot bypass Engineer to call Implementer directly. The permission framework is hierarchy-aware from day one.
3. Audit log captures the full chain on Decline propagation. `details_json` includes the parent task chain when a sub-agent declines.
4. The `tasks` table includes `parent_task_id` for nested work. Sub-tasks dispatched by parent agents are first-class rows.
5. The bridge architecture chosen at M3 must not preclude Phase 2's parallel-dispatch use case (Engineer dispatching multiple sub-agents concurrently).

If a Phase 1 design choice would make any of those harder to add in Phase 2, surface it to Mike. Do not silently take the Phase-1-easier path.

## Decline as first-class

A sub-agent can decline a dispatched task. Decline is *not* failure — it's a structured upward action with a reason. The audit log records it. The bridge surfaces it. Engineer must surface a Decline to PM (cannot suppress); PM must surface a non-downgradable Decline to Mike (cannot suppress).

This is mostly a Phase-2 concern (Decline propagation across multiple layers), but Phase 1 must establish the wire shape so it works when there are more layers.

## When in doubt about scope

Ask Mike. Don't add scope. Don't drop scope. If a milestone runs significantly long, surface that — Mike will choose between cutting scope, extending the milestone, or splitting it.
