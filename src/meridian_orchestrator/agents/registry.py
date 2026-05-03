"""In-memory agent registry with watchfiles-driven hot reload.

The registry is the runtime source of truth for agent identity, hierarchy,
and permissions. On startup it:

1. Loads every ``<role>.yaml`` from ``<MERIDIAN_ORCH_DATA_DIR>/config/agents/``
2. Resolves each agent's UUID by looking up the keyring token whose role
   matches (M2/M3 reconciliation contract — token UUIDs become agents row
   primary keys; orphan/unseeded warnings logged on mismatch)
3. Seeds the agents table via :meth:`AgentRegistry.seed_into_storage` —
   idempotent UPSERT of the in-memory snapshot, called from
   ``main.py``'s lifespan after :func:`storage.seed.seed_initial` has
   established ``company_id``. Two-pass with a single transaction (row
   UPSERTs first with ``reports_to=NULL``; ``reports_to`` UPDATE pass
   second once all parent UUIDs are insertable). Intermediate state is
   never visible to concurrent readers, and pass-2 failure rolls back
   pass 1 atomically — the agents table returns to its pre-call state.
4. Starts a ``watchfiles`` background task that re-loads + re-validates on
   any change to the agents directory

Hot reload is config-only: the registry's in-memory snapshot updates atomically.
The agents table in SQLite is **not** re-seeded on hot reload — operators edit
the YAML to change permissions, system prompts, etc., not to add/remove agents
(adding a new role requires a daemon restart and a fresh keyring token).
Operator-edited YAML's mutable fields (``config_path``, ``reports_to``)
propagate to SQL rows on the next daemon restart via the same
:meth:`seed_into_storage` UPSERT path; identity-stable fields (``id``,
``role``, ``company_id``) are preserved across restarts.
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
from meridian_orchestrator.storage.interface import Storage

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

    # ----- SQL seed -------------------------------------------------------

    async def seed_into_storage(
        self, storage: Storage, company_id: str
    ) -> None:
        """Idempotent UPSERT of the in-memory snapshot into the
        ``agents`` SQL table.

        Called from ``main.py``'s lifespan after :meth:`initial_load`
        has populated the snapshot and after
        :func:`storage.seed.seed_initial` has established the
        ``company_id``. MUST run before the MCP server starts
        accepting connections — otherwise the first MCP request that
        writes ``api_usage`` (or any row referencing ``agents.id``)
        hits ``FOREIGN KEY CONSTRAINT FAILED``.

        **Two-pass shape, single transaction** — both invariants
        below are load-bearing; future maintainers extending this
        method must preserve them. Splitting into separate
        transactions or moving the boundary breaks both:

        1. **Intermediate state never visible.** The half-resolved
           "all rows present, ``reports_to`` still NULL" state
           between pass 1 and pass 2 is held inside one transaction;
           concurrent readers see either pre-seed state or
           post-seed state, never the middle.
        2. **Atomic rollback on pass-2 failure.** If pass 2's
           ``reports_to`` UPDATE raises (FK violation, schema drift,
           etc.), pass 1's UPSERTs roll back; the agents table
           returns to its pre-call state. No partial-seed rows
           persist.

        **Failure raises; lifespan propagates; daemon fails to start.**
        Seed is load-bearing for ``record_api_call``'s
        ``api_usage.agent_id REFERENCES agents(id)`` FK validity —
        silent failure would leave the daemon healthy-looking but
        broken on the first MCP call that records token usage.

        **UPSERT semantics** preserve identity-stable fields (``id``,
        ``role``, ``company_id``) across reruns while propagating
        mutable fields (``config_path``, ``reports_to``) when the
        operator's YAML edits land on the next daemon restart. Hot-
        reload of YAML during an active daemon run does NOT re-seed
        — that's the documented contract preserved at module
        docstring.

        Args:
            storage: Storage handle.
            company_id: Resolved from
                :func:`storage.seed.seed_initial`. Set as
                ``agents.company_id`` for every row. Phase 1 is
                single-company; Phase 2 multi-tenancy will need
                per-agent company resolution. Empty-string
                ``company_id`` raises ``ValueError`` defensively —
                it would FK-fail at INSERT time anyway, but the
                explicit guard fails fast and informatively rather
                than producing a SQLite error far from the cause.
        """
        if not company_id:
            raise ValueError(
                "seed_into_storage requires a non-empty company_id; "
                "callers must run storage.seed.seed_initial first to "
                "establish the company row before agents are seeded."
            )

        # Snapshot a stable local view. self._snapshot is replaced
        # atomically by the watcher's _reload_locked, so list() at
        # this point produces an immutable iteration target even if
        # the watcher fires mid-seed. The next daemon restart picks
        # up any post-snapshot YAML edits via this same path —
        # consistent with the hot-reload-doesn't-re-seed contract.
        snapshot = list(self._snapshot.values())
        snapshot_by_role = {ra.config.role: ra for ra in snapshot}
        agents_dir = self._agents_dir

        async with storage.transaction():
            # Pass 1: UPSERT each row with reports_to=NULL. The
            # ON CONFLICT clause updates only mutable fields
            # (config_path); identity-stable fields are preserved
            # because excluded.{id,role,company_id} would equal the
            # existing values for valid reruns. Only config_path
            # actually changes when the data_dir relocates between
            # runs.
            for ra in snapshot:
                config_path = str(agents_dir / f"{ra.config.role}.yaml")
                await storage.execute(
                    "INSERT INTO agents (id, company_id, role, config_path) "
                    "VALUES (?, ?, ?, ?) "
                    "ON CONFLICT(id) DO UPDATE SET "
                    "config_path = excluded.config_path",
                    (ra.agent_id, company_id, ra.config.role, config_path),
                )

            # Pass 2: resolve reports_to to the parent agent's UUID.
            # PM (and any agent with reports_to=None in YAML) gets
            # an explicit NULL UPDATE so reruns after a YAML edit
            # that introduces a None reports_to clear stale FKs.
            # Orphan reports_to (parent role in YAML but not in
            # snapshot — e.g., parent role's keyring token missing)
            # also degrades to NULL; AgentRegistry.initial_load
            # already logged the unseeded-role warning, no
            # re-logging here.
            for ra in snapshot:
                parent_role = ra.config.reports_to
                parent = (
                    snapshot_by_role.get(parent_role)
                    if parent_role is not None
                    else None
                )
                parent_id = parent.agent_id if parent is not None else None
                await storage.execute(
                    "UPDATE agents SET reports_to = ? WHERE id = ?",
                    (parent_id, ra.agent_id),
                )

        logger.info(
            "agents.seeded",
            count=len(snapshot),
            roles=sorted(snapshot_by_role.keys()),
        )

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
