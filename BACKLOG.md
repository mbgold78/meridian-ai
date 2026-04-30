# Backlog

## Phase 1 (M1–M7)

Tracked via milestones in `docs/kickoff/08-implementation-plan.md`.

## Phase 2 (M8–M12)

Sketched in `docs/kickoff/12-phase-two-engineering-team.md`.

## Deferred

- Anthropic usage API polling (Phase 2+; agents self-report via `record_api_call` in Phase 1)
- Web dashboard UI (Claude Desktop is the only Phase 1 interface)
- Cloud deployment (architecture is cloud-portable but Phase 1 is local-only)
- Multi-user support
- Lawyer, Accountant, Designer, Advisor agents (deferred indefinitely)
- Multi-recipient Telegram notifications (Phase 1 is single-chat-id)
- SAVEPOINT-based nested transactions in storage (Phase 1 has reentrant flat transactions)
- Automated TLS cert renewal (Phase 1 generates a 365-day self-signed cert at first startup and only regenerates if the file is missing or expired; the operator must re-import the new cert into the OS trust store on regeneration. Phase 2 should add a renewal task that rotates the cert before expiry and a notification surface for the trust-store re-import step.)

## Open questions deferred to later milestones

- M3: Engineer↔subagent bridge architecture choice (Option A leading; see `docs/kickoff/11-engineer-bridge.md`)
- M3: Continuous-mode PM driver mechanism (rides on bridge decision)
- M5: Concrete model routing logic in `check_budget_for_task` (stub at M5, refined as Mike observes)
- M6: Whether to fold notification tables into earlier migrations if no production state exists at M6 start
