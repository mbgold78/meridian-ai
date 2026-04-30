"""In-memory agent registry with watchfiles-driven hot reload.

The registry is the runtime source of truth for agent identity, hierarchy,
and permissions. On startup it:

1. Loads every ``<role>.yaml`` from ``<MERIDIAN_ORCH_DATA_DIR>/config/agents/``
2. Resolves each agent's UUID by looking up the keyring token whose role
   matches (M2/M3 reconciliation contract — token UUIDs become agents row
   primary keys; orphan/unseeded warnings logged on mismatch)
3. Seeds the agents table via the storage layer (idempotent on re-run)
4. Starts a ``watchfiles`` background task that re-loads + re-validates on
   any change to the agents directory

Hot reload is config-only: the registry's in-memory snapshot updates atomically.
The agents table in SQLite is **not** re-seeded on hot reload — operators edit
the YAML to change permissions, system prompts, etc., not to add/remove agents
(adding a new role requires a daemon restart and a fresh keyring token).
"""

from __future__ import annotations

import asyncio
import contextlib
from collections.abc import AsyncIterator, Iterable
from dataclasses import dataclass
from pathlib import Path

import structlog
from watchfiles import awatch

from meridian_orchestrator.agents.config import (
    AgentConfig,
    AgentConfigError,
    AgentRole,
    load_all_agent_configs,
)
from meridian_orchestrator.auth import TokenStore

logger = structlog.get_logger(__name__)


@dataclass(frozen=True, slots=True)
class RegisteredAgent:
    """An agent's runtime identity = config + resolved UUID."""

    config: AgentConfig
    agent_id: str


class AgentRegistry:
    """In-memory registry of loaded agent configs, keyed by role.

    Thread-safety: a single asyncio.Lock guards snapshot mutations. All reads
    return immutable references (the snapshot dict is replaced atomically on
    reload, never mutated in place).
    """

    def __init__(self, agents_dir: Path, token_store: TokenStore | None = None) -> None:
        self._agents_dir = agents_dir
        self._token_store = token_store if token_store is not None else TokenStore()
        self._snapshot: dict[AgentRole, RegisteredAgent] = {}
        self._lock = asyncio.Lock()
        self._watch_task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()

    # ----- snapshot accessors ---------------------------------------------

    def get(self, role: AgentRole) -> RegisteredAgent | None:
        return self._snapshot.get(role)

    def get_by_agent_id(self, agent_id: str) -> RegisteredAgent | None:
        for agent in self._snapshot.values():
            if agent.agent_id == agent_id:
                return agent
        return None

    def all_agents(self) -> list[RegisteredAgent]:
        return list(self._snapshot.values())

    def configs_by_role(self) -> dict[AgentRole, AgentConfig]:
        """Snapshot of role → AgentConfig for the permission resolver."""
        return {role: ra.config for role, ra in self._snapshot.items()}

    # ----- lifecycle ------------------------------------------------------

    async def initial_load(self) -> None:
        """Load configs and resolve UUIDs from keyring tokens.

        Logs warnings (not errors) for:

        - **Orphaned tokens:** keyring tokens whose role has no corresponding
          YAML config (operator typo, or a token issued for a role not yet
          configured)
        - **Unseeded agents:** YAML configs whose role has no static keyring
          token (fine for PM at runtime since PM's interactive token is
          OAuth-issued; flagged anyway so the operator can audit)
        """
        async with self._lock:
            await self._reload_locked()

    async def start_watcher(self) -> None:
        """Spawn the watchfiles task. Idempotent — second call is a no-op."""
        if self._watch_task is not None:
            return
        self._stop_event.clear()
        self._watch_task = asyncio.create_task(
            self._watch_loop(), name="agent-registry-watch"
        )

    async def stop(self) -> None:
        """Stop the watcher task. Safe to call when watcher isn't running."""
        if self._watch_task is None:
            return
        self._stop_event.set()
        self._watch_task.cancel()
        with contextlib.suppress(asyncio.CancelledError, Exception):
            await self._watch_task
        self._watch_task = None

    @contextlib.asynccontextmanager
    async def lifespan(self) -> AsyncIterator[AgentRegistry]:
        """Async context manager that loads, watches, and stops cleanly."""
        await self.initial_load()
        await self.start_watcher()
        try:
            yield self
        finally:
            await self.stop()

    # ----- internal -------------------------------------------------------

    async def _reload_locked(self) -> None:
        """Re-read the configs dir under the existing lock."""
        try:
            configs = await asyncio.to_thread(
                load_all_agent_configs, self._agents_dir
            )
        except AgentConfigError as exc:
            logger.error("agents.reload_failed", error=str(exc))
            return

        token_records = await asyncio.to_thread(self._token_store.list_records)
        active_static_tokens_by_role = {
            r.role: r
            for r in token_records
            if r.source == "static" and r.revoked_at is None and not r.is_expired()
        }

        new_snapshot: dict[AgentRole, RegisteredAgent] = {}
        for role, cfg in configs.items():
            tok = active_static_tokens_by_role.get(role)
            if tok is None and cfg.id is None:
                logger.warning(
                    "agents.unseeded_role",
                    role=role,
                    message=(
                        f"agent role {role!r} has no static keyring token; "
                        "no agent_id will be resolved at registry load time. "
                        "If this role is intended to be reachable via the "
                        "static-token path (Engineer/Implementer/Tester), "
                        "issue a token via `scripts/issue-token.py`."
                    ),
                )
                continue
            agent_id = cfg.id or (tok.agent_id if tok is not None else None)
            if agent_id is None:
                continue
            new_snapshot[role] = RegisteredAgent(config=cfg, agent_id=agent_id)

        for token_role, tok in active_static_tokens_by_role.items():
            if (
                token_role in {"pm", "engineer", "implementer", "tester"}
                and token_role not in configs
            ):
                logger.warning(
                    "agents.orphan_token",
                    role=token_role,
                    token_id=tok.token_id,
                    message=(
                        f"keyring has a static token for role {token_role!r} but no "
                        f"<role>.yaml config exists at {self._agents_dir}. The "
                        "token will not resolve to an agent until the YAML is added."
                    ),
                )

        previous = self._snapshot
        self._snapshot = new_snapshot
        if previous != new_snapshot:
            logger.info(
                "agents.snapshot_updated",
                roles=sorted(new_snapshot.keys()),
                count=len(new_snapshot),
            )

    async def _watch_loop(self) -> None:
        try:
            async for _changes in awatch(
                self._agents_dir,
                stop_event=self._stop_event,
                recursive=True,
            ):
                async with self._lock:
                    await self._reload_locked()
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            logger.error("agents.watcher_crashed", error=str(exc))


def filter_to_known_roles(roles: Iterable[str]) -> list[AgentRole]:
    """Helper: filter an arbitrary string iterable down to known agent roles."""
    valid = {"pm", "engineer", "implementer", "tester"}
    return [r for r in roles if r in valid]  # type: ignore[misc]
