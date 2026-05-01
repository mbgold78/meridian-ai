# Backlog

## Phase 1 (M1–M7)

Tracked via milestones in `docs/kickoff/08-implementation-plan.md`.

## Phase 2 (M8–M12)

Sketched in `docs/kickoff/12-phase-two-engineering-team.md`.

## Deferred

- Subscription tier identification (M7+ deployment surface). The `/api/oauth/usage` endpoint exposes utilization normalized 0-100% per counter but does not expose a `plan` field — the absolute capacity ceilings of the operator's subscription are not directly readable. Not blocking M5: routing decisions need normalized utilization, not absolute ceilings, and the `subscription_first_architecture` memory's polled-snapshot mechanism works without tier identification. Becomes relevant if/when the orchestrator surfaces tier info to end-user-facing dashboards, upgrade-path nudges, or absolute-token reporting (none in Phase 1 scope). Spec home: `project_subscription_first_architecture` memory § Subscription tier identification gap.
- Web dashboard UI (Claude Code is the only Phase 1 operator interface)
- Cloud deployment (architecture is cloud-portable but Phase 1 is local-only)
- Multi-user support
- Lawyer, Accountant, Designer, Advisor agents (deferred indefinitely)
- Multi-recipient Telegram notifications (Phase 1 is single-chat-id)
- SAVEPOINT-based nested transactions in storage (Phase 1 has reentrant flat transactions)
- TLS for the MCP server (Phase 1 binds loopback only and runs plain HTTP — no over-the-wire threat on `127.0.0.1`. When Phase 2 deploys to a non-loopback bind, generate or import a properly-trusted cert and re-enable TLS in the uvicorn config.)
- Claude Desktop GUI Custom Connector support (Phase 1 targets Claude Code's connector flow because it uses RFC 8252 loopback redirects against a localhost daemon. Claude Desktop's GUI Custom Connectors are cloud-mediated — Anthropic's `160.79.104.0/21` traffic does the discovery — and cannot reach a local-only daemon. If Phase 2 needs Claude Desktop UX, the path is either a stdio-bridge subprocess in `claude_desktop_config.json` or a public ingress.)
- Manual `supervisor.halt()` clear without service restart (M4c+; supervisor.halt() currently requires service restart for recovery — adequate for Phase 1 since the operator is in the loop, but a future admin command to clear the halt without restart would improve operability when the underlying cause is well-understood and a full restart is heavyweight. Spec home: `docs/kickoff/11-engineer-bridge.md` § Cap escalation surface.)
- Retrofit supervisor respawn paths to single-SQL-transaction atomicity (M4c-compaction +). Both the heartbeat-timeout respawn and the compaction-driven respawn currently use the in-process `_current_dispatch_id is None` flag to gate the watchdog during the brief window where the old row is terminal but the new row hasn't been inserted yet. External readers (currently none) see a transient "no active supervisor row" state. When external readers are introduced — likely M5's PMSupervisor lifecycle visualization or M6's `notify_human` surfacing — wrap the old-row-terminal UPDATE and the new-row INSERT in a single SQLite transaction so external queries see consistent state at all times. Spec home: `docs/kickoff/11-engineer-bridge.md` § Engineer compaction (atomicity subsection).

## Open questions deferred to later milestones

- M3: Engineer↔subagent bridge architecture choice (Option A leading; see `docs/kickoff/11-engineer-bridge.md`)
- M3: Continuous-mode PM driver mechanism (rides on bridge decision)
- M5: Concrete model routing logic in `check_budget_for_task` (stub at M5, refined as Mike observes)
- M6: Whether to fold notification tables into earlier migrations if no production state exists at M6 start
