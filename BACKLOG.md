# Backlog

## Phase 1 (M1–M7)

Tracked via milestones in `docs/kickoff/08-implementation-plan.md`.

## Phase 2 (M8–M12)

Sketched in `docs/kickoff/12-phase-two-engineering-team.md`.

## Deferred

- Anthropic usage API polling (Phase 2+; agents self-report via `record_api_call` in Phase 1)
- Web dashboard UI (Claude Code is the only Phase 1 operator interface)
- Cloud deployment (architecture is cloud-portable but Phase 1 is local-only)
- Multi-user support
- Lawyer, Accountant, Designer, Advisor agents (deferred indefinitely)
- Multi-recipient Telegram notifications (Phase 1 is single-chat-id)
- SAVEPOINT-based nested transactions in storage (Phase 1 has reentrant flat transactions)
- TLS for the MCP server (Phase 1 binds loopback only and runs plain HTTP — no over-the-wire threat on `127.0.0.1`. When Phase 2 deploys to a non-loopback bind, generate or import a properly-trusted cert and re-enable TLS in the uvicorn config.)
- Claude Desktop GUI Custom Connector support (Phase 1 targets Claude Code's connector flow because it uses RFC 8252 loopback redirects against a localhost daemon. Claude Desktop's GUI Custom Connectors are cloud-mediated — Anthropic's `160.79.104.0/21` traffic does the discovery — and cannot reach a local-only daemon. If Phase 2 needs Claude Desktop UX, the path is either a stdio-bridge subprocess in `claude_desktop_config.json` or a public ingress.)

## Open questions deferred to later milestones

- M3: Engineer↔subagent bridge architecture choice (Option A leading; see `docs/kickoff/11-engineer-bridge.md`)
- M3: Continuous-mode PM driver mechanism (rides on bridge decision)
- M5: Concrete model routing logic in `check_budget_for_task` (stub at M5, refined as Mike observes)
- M6: Whether to fold notification tables into earlier migrations if no production state exists at M6 start
