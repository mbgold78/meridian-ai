# Meridian Orchestrator

Internal infrastructure for Meridian AI's AI-staffed company operations.

This is the Phase 1 build of the Orchestrator — a long-lived Python daemon that coordinates an AI-staffed company hierarchy via MCP. The operator (Mike) talks to a PM agent in Claude Desktop; PM coordinates with an Engineer agent that dispatches Implementer and Tester sub-agents into the substrate codebase.

Design documents live in `docs/kickoff/`. Read `00-README.md` first.

## Status

- M1: Project skeleton + storage layer
- M2: MCP server scaffolding + auth (pending)
- M3: Agent config loading + permission framework + bridge design (pending)
- M4: Task management + subagent dispatch with parallel (pending)
- M5: Budget & usage + window-aware planner + continuous-mode driver (pending)
- M6: Knowledge & routing + audit log + notification service (pending)
- M7: Service deployment + smoke test + hardening (pending)

## Requirements

- Python 3.12+ (uv will fetch one if your system Python is older)
- [`uv`](https://docs.astral.sh/uv/) for package management

## Install

```bash
uv sync
```

## Initialize the database

```bash
uv run python scripts/init-db.py
```

Creates `${MERIDIAN_ORCH_DATA_DIR}/data/orchestrator.db` (default `~/.meridian-orchestrator/data/orchestrator.db`), runs migrations, and seeds the Meridian AI company plus the Substrate Product project. Override the data dir via `.env` (copy `.env.example` to `.env` and edit).

## Run the daemon

```bash
uv run python -m meridian_orchestrator.main
```

M1 is a no-op skeleton — connects to the DB, runs migrations, idles, exits cleanly on SIGTERM / SIGINT / SIGBREAK.

## Test

```bash
uv run pytest
uv run ruff check src/ tests/
uv run mypy --strict src/
```

## Layout

```
src/meridian_orchestrator/   service code
docs/kickoff/                 design documents — start at 00-README.md
tests/unit/                   mirrors src/
tests/integration/            scenario-based (M2+)
scripts/                      init-db, smoke-test, sync-substrate-knowledge
service/                      launchd/systemd/Windows Service configs (M7)
```

## License

Proprietary. © Meridian AI, LLC.
