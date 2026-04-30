# Testing & Acceptance

## Testing philosophy

Three principles, in priority order:

1. **Tests are part of the deliverable.** A milestone is not done until its tests are green and committed alongside the code. No "tests later".
2. **Test the contract, not the implementation.** Public-tool tests assert input/output schemas, error codes, and audit-log effects. They survive refactors. Internal-helper tests are fine where the helper is itself complex (e.g., the 5-hour-window math, the glob-permission resolver).
3. **Integration tests use real SQLite and a real MCP server.** No DB mocks. No MCP mocks. The cost of running a real server in-process is small; the cost of mock/prod divergence is large.

## Layers

### Unit tests (`tests/unit/`)

Mirror `src/`. Each module has a corresponding test file. Async tests via `pytest-asyncio`.

What unit tests cover:
- Pydantic validation rules
- Storage CRUD round-trips against a real ephemeral SQLite DB (`tmp_path`)
- Permission decisions (every matrix cell in `06-mcp-tools-phase-one.md`)
- Migration idempotency (run twice, no error, no duplicate side effects)
- Pure helpers (window math, glob matchers, state machine validation)

What unit tests do NOT cover:
- MCP wire protocol (that's integration)
- Subagent dispatch end-to-end (that's integration)
- File watcher behavior (that's integration)

### Integration tests (`tests/integration/`)

Scenario-based. Each test boots a real MCP server in-process against an ephemeral SQLite DB, connects an in-process MCP client, runs a scenario, asserts.

What integration tests cover:
- MCP round-trip with a valid token
- MCP rejection with an invalid token
- Hot reload of an agent config affects subsequent permission checks
- Full PM→Engineer→Implementer→Tester cycle (M4+)
- Audit log captures the dispatch chain (M4+)
- Knowledge sync produces a readable foundation doc (M6+)

### Smoke test (`scripts/smoke-test.py`)

Single script, runs against a *real* installed daemon. Runs the full chain end-to-end. Used by Mike at M7 acceptance and as a regression check after any service-level change.

Target runtime: <30 seconds. If it ever exceeds 60s, surface that as a regression.

## Acceptance per milestone

Each milestone's acceptance criteria are listed in `08-implementation-plan.md`. The checkpoint with Mike is the gate: until Mike says "next", the milestone is not closed.

### What "Mike says next" actually means

- For M1–M2: Mike runs the commands, sees the expected output, says continue.
- For M3 specifically: Mike reviews and approves `11-engineer-bridge.md` before M4 implementation starts.
- For M4: Mike walks through a real PM→Engineer→Implementer cycle in his interactive Claude Code session and observes the resulting commit in `meridian-homepoc`.
- For M5–M6: Mike inspects the JSON outputs and confirms they reconcile with reality.
- For M7: Mike installs as a service, restarts his machine, confirms automatic startup, runs the smoke test.

If a checkpoint reveals a problem, fix it, re-run, re-checkpoint. Don't argue the test is wrong unless it actually is — and if it is, fix the test in the same commit.

## What "test green" means

- `uv run pytest` exits 0
- `uv run ruff check src/ tests/` exits 0
- `uv run mypy --strict src/` exits 0
- `uv run ruff format --check src/ tests/` exits 0

All four. Per commit. No exceptions for "I'll fix it next commit" — that's how dirty trees compound.

## What's NOT tested in Phase 1

- Performance/load. The system is single-user. If a tool returns in <500ms in normal use, that's fine. No performance regressions tracked.
- Cross-platform parity. Windows is the primary target (Mike's machine). Mac launchd and Linux systemd configs ship at M7 for portability but are not tested as part of CI in Phase 1.
- Resilience to filesystem corruption, OS-level failures, partial-write scenarios. SQLite's WAL mode and the daily backup are the safety net.
- Network failure modes. The Phase 1 daemon makes few outbound calls; rate-limit handling per `02-architecture.md` is the only network concern, and it's tested via mocked HTTP errors at M5.

## Test data

Tests use:
- An ephemeral SQLite DB in `tmp_path` per test (no shared fixtures across tests except the seeded company/project)
- An in-process MCP server with a known set of test tokens
- A `tests/fixtures/` directory for any larger sample knowledge files
- Frozen time via `freezegun` where window-boundary math is involved

No tests touch the real `~/.meridian-orchestrator/` data directory. Ever.

## Coverage expectations

Not enforced as a hard percentage. The expectation is:
- Every public tool has at least one happy-path unit test and one denial test
- Every state-machine transition has a test
- Every permission-matrix cell has a test
- Every integration test scenario in `08-implementation-plan.md` is implemented

If a piece of code has no test, that's either intentional (with a comment saying why) or a bug.
