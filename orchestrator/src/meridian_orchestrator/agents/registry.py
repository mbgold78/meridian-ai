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
from meridian_orchestrator.auth import TokenSource, TokenStore
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)


@dataclass(frozen=True, slots=True)
class RegisteredAgent:
    """An agent's runtime identity = config + resolved UUID + token source.

    ``source`` distinguishes static-token-bound agents (daemon-spawned
    supervisors + sub-agent dispatches; one per role at a time) from
    OAuth-bound agents (operator's interactive sessions; multiple
    possible per role under multi-device or multi-operator scenarios).
    Same ``config`` (yaml-loaded ``AgentConfig``) for both sources within
    a role — the role's discipline applies regardless of how the caller
    authenticated. The source field is what distinguishes them at
    registry-lookup time so :meth:`AgentRegistry.get` returns the
    supervisor agent (static-source) for callers asking "who's the
    PM/Engineer/Implementer/Tester for this role?", while
    :meth:`AgentRegistry.get_by_agent_id` returns whichever agent
    (static or OAuth) holds the requested ``agent_id``.
    """

    config: AgentConfig
    agent_id: str
    source: TokenSource = "static"


class AgentRegistry:
    """In-memory registry of loaded agent configs, keyed by ``agent_id``.

    Snapshot is keyed by ``agent_id`` (not role) so multiple agents per role
    can coexist — most commonly: static-source supervisor + OAuth-source
    operator session within the same role (e.g., daemon-spawned PM-supervisor
    + Mike-as-PM-via-OAuth interactive session). :meth:`get` returns the
    static-source supervisor for a role (existing semantics for callers
    asking "who's the PM"); :meth:`get_by_agent_id` returns whichever agent
    (static or OAuth) holds the requested id.

    Thread-safety: a single asyncio.Lock guards snapshot mutations. All
    reads return immutable references (the snapshot dict is replaced
    atomically on reload, never mutated in place).
    """

    def __init__(self, agents_dir: Path, token_store: TokenStore | None = None) -> None:
        self._agents_dir = agents_dir
        self._token_store = token_store if token_store is not None else TokenStore()
        self._snapshot: dict[str, RegisteredAgent] = {}
        self._lock = asyncio.Lock()
        self._watch_task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()

    # ----- snapshot accessors ---------------------------------------------

    def get(self, role: AgentRole) -> RegisteredAgent | None:
        """Return the static-source supervisor agent for ``role``, or None.

        Filter-and-return-first-static-match preserves existing call-site
        semantics: callers asking "who's the PM/Engineer/Implementer/Tester
        for this role?" get the supervisor agent (daemon-spawned, static-
        token-bound). OAuth-source agents in the snapshot are skipped here
        — they're operator-session agents reachable only via
        :meth:`get_by_agent_id`.
        """
        for agent in self._snapshot.values():
            if agent.config.role == role and agent.source == "static":
                return agent
        return None

    def get_by_agent_id(self, agent_id: str) -> RegisteredAgent | None:
        """Return the agent for ``agent_id``, or None if not registered.

        Direct dict lookup against the agent_id-keyed snapshot. Resolves
        both static-source supervisor agents and OAuth-source operator
        agents — caller distinguishes via the returned ``source`` field
        if needed.
        """
        return self._snapshot.get(agent_id)

    def all_agents(self) -> list[RegisteredAgent]:
        return list(self._snapshot.values())

    def configs_by_role(self) -> dict[AgentRole, AgentConfig]:
        """Snapshot of role → AgentConfig for the permission resolver.

        Returns the static-source supervisor's config per role; OAuth-source
        agents share the same role yaml so this mapping isn't role-multivalued
        in practice (per-role config is yaml-driven and identical across
        same-role agents regardless of token source).
        """
        result: dict[AgentRole, AgentConfig] = {}
        for ra in self._snapshot.values():
            if ra.source == "static":
                result[ra.config.role] = ra.config
        return result

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
        # Static-source filter: reports_to chains follow the org-chart
        # supervisor agents only — OAuth-source operator agents aren't
        # part of the hierarchy. Without this filter, multiple agents
        # per role (static-supervisor + OAuth-operator under Component 1)
        # would last-wins-clobber the role-keyed dict and resolve
        # reports_to chains against the wrong agent (e.g., Engineer's
        # reports_to=PM would resolve to an OAuth-PM agent_id rather
        # than the static-supervisor PM agent_id).
        snapshot_by_role = {
            ra.config.role: ra for ra in snapshot if ra.source == "static"
        }
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

    async def register_oauth_caller(
        self,
        storage: Storage,
        company_id: str,
        agent_id: str,
        role: AgentRole,
    ) -> None:
        """Register an OAuth-issued caller in the registry snapshot + agents
        table.

        Called from the OAuth exchange callback after
        :meth:`auth.TokenStore.issue` mints (or reuses, per alpha persistence)
        an ``agent_id`` for an OAuth caller. Adds the caller to the
        agent_id-keyed snapshot with ``source='oauth'`` so subsequent
        :meth:`get_by_agent_id` lookups resolve the OAuth caller's
        registered config; UPSERTs the agents row in storage so
        ``submit_task`` (and other handlers with FK constraints to
        ``agents.id``) accept the OAuth caller's ``agent_id`` without
        ``FOREIGN KEY CONSTRAINT FAILED``.

        Idempotent on repeat calls for the same ``agent_id`` — alpha persistence
        reuses the same agent_id across OAuth re-authorization flows for the
        same ``client_id``; this method's ``ON CONFLICT(id) DO UPDATE``
        semantics + snapshot dict assignment make repeated registrations
        safe. ``reports_to`` is set to NULL for OAuth callers — operator
        agents aren't part of the org-chart hierarchy (the static-source
        supervisor for the role is what answers "who reports to whom"
        questions; OAuth callers are operator identities outside that
        chain).

        ``role`` must be a known agent role with a yaml config; the method
        no-ops with a warning otherwise (defensive against caller bugs).
        """
        async with self._lock:
            existing = self._snapshot.get(agent_id)
            cfg: AgentConfig | None
            if existing is not None and existing.config.role == role:
                cfg = existing.config
            else:
                # Re-read configs to find the matching role's yaml
                try:
                    configs = await asyncio.to_thread(
                        load_all_agent_configs, self._agents_dir
                    )
                except AgentConfigError as exc:
                    logger.error(
                        "agents.oauth_register_failed_config_load",
                        agent_id=agent_id,
                        role=role,
                        error=str(exc),
                    )
                    return
                cfg = configs.get(role)
                if cfg is None:
                    logger.warning(
                        "agents.oauth_register_unknown_role",
                        agent_id=agent_id,
                        role=role,
                        message=(
                            f"OAuth caller registration for role {role!r} "
                            "skipped: no yaml config exists at "
                            f"{self._agents_dir}. Caller will not resolve "
                            "to a registered agent."
                        ),
                    )
                    return

            self._snapshot[agent_id] = RegisteredAgent(
                config=cfg, agent_id=agent_id, source="oauth"
            )

        config_path = str(self._agents_dir / f"{role}.yaml")
        async with storage.transaction():
            await storage.execute(
                "INSERT INTO agents (id, company_id, role, config_path) "
                "VALUES (?, ?, ?, ?) "
                "ON CONFLICT(id) DO UPDATE SET "
                "config_path = excluded.config_path",
                (agent_id, company_id, role, config_path),
            )
            # OAuth callers have no reports_to — they're operator identities,
            # not org-chart entities. Set explicitly to NULL so reruns after
            # any prior state reset clear stale FKs.
            await storage.execute(
                "UPDATE agents SET reports_to = NULL WHERE id = ?",
                (agent_id,),
            )

        logger.info(
            "agents.oauth_caller_registered",
            agent_id=agent_id,
            role=role,
        )

    # ----- internal -------------------------------------------------------

    async def _reload_locked(self) -> None:
        """Re-read the configs dir under the existing lock.

        Builds the agent_id-keyed snapshot from two sources:

        1. **Static tokens** (one per role): yaml-driven; supervisor agent
           identities. Existing semantics preserved — orphan-token warnings
           fire when keyring has a static token whose role lacks a yaml
           config; unseeded-role warnings fire when yaml exists without a
           static token (acceptable for PM at runtime since interactive PM
           is OAuth-issued).
        2. **OAuth tokens** (multiple possible per role): operator-session
           identities (Mike-as-PM-via-OAuth at Phase 1; multi-operator or
           multi-device under future scenarios). Each active OAuth token
           with a yaml-defined role contributes an entry keyed by its
           ``agent_id``. OAuth tokens for unconfigured roles surface the
           orphan-token warning shape (same diagnostic; different message)
           — operationally rare, but defensive against malformed keyring
           state.
        """
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
        active_oauth_tokens = [
            r
            for r in token_records
            if r.source == "oauth" and r.revoked_at is None and not r.is_expired()
        ]

        new_snapshot: dict[str, RegisteredAgent] = {}

        # Pass 1: static-source supervisor agents (one per role, yaml-driven)
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
            new_snapshot[agent_id] = RegisteredAgent(
                config=cfg, agent_id=agent_id, source="static"
            )

        # Pass 2: OAuth-source operator agents (zero-to-many per role)
        for tok in active_oauth_tokens:
            if tok.role not in {"pm", "engineer", "implementer", "tester"}:
                continue
            tok_role: AgentRole = tok.role  # type: ignore[assignment]
            oauth_cfg = configs.get(tok_role)
            if oauth_cfg is None:
                logger.warning(
                    "agents.oauth_orphan_token",
                    role=tok.role,
                    token_id=tok.token_id,
                    message=(
                        f"keyring has an OAuth token for role {tok.role!r} but no "
                        f"<role>.yaml config exists at {self._agents_dir}. The "
                        "OAuth caller will not resolve to a registered agent "
                        "until the YAML is added."
                    ),
                )
                continue
            new_snapshot[tok.agent_id] = RegisteredAgent(
                config=oauth_cfg, agent_id=tok.agent_id, source="oauth"
            )

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
                agent_count=len(new_snapshot),
                static_count=sum(
                    1 for ra in new_snapshot.values() if ra.source == "static"
                ),
                oauth_count=sum(
                    1 for ra in new_snapshot.values() if ra.source == "oauth"
                ),
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
