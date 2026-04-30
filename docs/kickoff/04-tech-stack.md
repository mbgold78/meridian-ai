# Tech Stack

These are locked decisions. Don't relitigate without Mike's sign-off.

## Runtime

- **Python 3.12+** — modern type system, pattern matching where it helps, native `asyncio.TaskGroup`.

## Package management

- **`uv`** — not pip, not poetry, not pdm. `uv sync` for installs, `uv run` for invocations, `uv lock` for the lockfile. `pyproject.toml` is the source of truth for dependencies.

## Core dependencies

| Library | Use | Notes |
|---|---|---|
| `mcp` | MCP server (Anthropic's official Python SDK) | Server side; client is Claude Desktop. |
| `pydantic` v2 | Data validation, config schemas, tool I/O schemas | Strict mode. No `Config.allow_population_by_field_name` foot-guns. |
| `pyyaml` | Agent config files | Safe-load only. |
| `aiosqlite` | Async SQLite driver | Single connection pool managed via async context manager. |
| `httpx` | Async HTTP client | For any outbound calls (Anthropic API overflow, knowledge sync). |
| `structlog` | Structured JSON logging | Every log line is a JSON object with consistent fields. |
| `keyring` | OS keychain integration | Windows Credential Manager on Mike's machine. |
| `watchfiles` | File watcher for hot config reload | Used by the agent-config watcher in M3. |

## Dev dependencies

| Library | Use |
|---|---|
| `pytest` + `pytest-asyncio` | Test runner with async support |
| `pytest-cov` | Coverage reporting |
| `ruff` | Linting + formatting (replaces black, isort, flake8) |
| `mypy` | Type checking (strict mode) |

## Conventions

### Type hints

Type hints **everywhere**. Mypy in strict mode. No `# type: ignore` without a comment explaining why.

### Async-first

All I/O is async. Tool functions are async. Storage methods are async. The only sync code is pure-compute helpers.

### No global state

Pass dependencies explicitly. The MCP server is constructed by `main.py` with explicit storage, audit, budget, and config dependencies wired in. No module-level singletons except for `structlog`'s logger factory (which is the recommended pattern).

### Custom exception hierarchy

```python
class OrchestratorError(Exception): ...
class AuthError(OrchestratorError): ...
class PermissionError(OrchestratorError): ...
class BudgetError(OrchestratorError): ...
class NotFoundError(OrchestratorError): ...
class ConflictError(OrchestratorError): ...
class ValidationError(OrchestratorError): ...
```

Never raise bare `Exception`. Each tool's error contract is documented in `06-mcp-tools-phase-one.md`.

### Structured logging

Every log line is a JSON object. Standard fields:

- `timestamp` — ISO 8601 UTC
- `level` — debug/info/warning/error
- `event` — short event name, e.g. `tool.dispatch`, `agent.permission_denied`
- `actor` — agent_id when known
- `task_id` — when applicable
- `details` — nested object for event-specific fields

Logs are written to `~/.meridian-orchestrator/logs/orchestrator-YYYY-MM-DD.log` with daily rotation, plus a symlinked `orchestrator.log` for tailing.

### Docstrings

Every public function has a docstring. Format:

```python
async def submit_task(
    agent_role: str,
    description: str,
    priority: int,
    complexity: int,
    parent_task_id: str | None = None,
) -> Task:
    """Submit a new task to the queue.

    Args:
        agent_role: Role that should claim the task.
        description: What the task is.
        priority: 1 (highest) through 5 (lowest).
        complexity: 1 through 5.
        parent_task_id: If this task was dispatched by another agent.

    Returns:
        The persisted Task row.

    Raises:
        ValidationError: If priority or complexity is out of range.
        PermissionError: If the caller cannot submit tasks for the target role.
    """
```

### Tests mirror src/

`tests/unit/storage/test_tasks.py` mirrors `src/meridian_orchestrator/storage/tasks.py`. Integration tests live in `tests/integration/` and don't mirror — they're scenario-based.

### No comments that paraphrase code

Comments explain *why*. Code explains *what*. If a comment paraphrases the next line, delete it.

### Secrets and configuration

`.env` is `.gitignore`'d. The file holds service-level operational secrets: `MERIDIAN_ORCH_DATA_DIR`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` (M6), and any future tokens. No secrets land in YAML configs or in the repo. Per-agent MCP auth tokens live in the OS keychain via `keyring`, not in `.env`.

## Project layout

```
src/meridian_orchestrator/
  __init__.py
  main.py                  # Entry point, supervisor
  config.py                # Pydantic settings (loads .env)
  server.py                # MCP server construction + tool registration
  auth.py                  # Token issuance, lookup, keychain integration
  storage/
    __init__.py
    interface.py           # Protocol/ABC for storage backends
    sqlite.py              # aiosqlite implementation
    migrations.py          # Forward-only migrations
    models.py              # Pydantic row models
  tools/
    __init__.py
    tasks.py               # submit_task, get_queue, etc.
    budget.py              # record_api_call, get_burn_rate, etc.
    knowledge.py           # read/list/write_knowledge
    routing.py             # list_agents, get_dashboard_summary, get_org_chart
    dispatch.py            # dispatch_implementer, dispatch_tester (M4)
    audit.py               # get_audit_log
  agents/
    __init__.py
    config.py              # YAML loader + pydantic models
    registry.py            # In-memory registry, file watcher
    permissions.py         # Permission resolver (per-tool + hierarchy)
  audit/
    __init__.py
    logger.py              # Audit log writer
  bridge/                  # Engineer↔subagent dispatch (M4)
    __init__.py
    # contents decided at M3 per 11-engineer-bridge.md
```

## Versioning

Semantic versioning. `0.x.y` until Phase 1 ships in full; bump to `1.0.0` at Phase 1 acceptance. Version lives in `pyproject.toml`.
