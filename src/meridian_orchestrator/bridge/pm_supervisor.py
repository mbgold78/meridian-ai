"""PM continuous-mode supervisor concrete subclass.

Extends :class:`bridge.supervisor_base.LongRunningSupervisor` with
PM-specific subprocess command, working directory, token role/label,
and post-respawn state reconstruction. Ships at M5-pm-supervisor-base.

The PM continuous subprocess is a long-running ``claude -p`` invocation
whose prompt is the ``system_prompt`` field of ``config/agents/pm.yaml``,
loaded via the M3 :class:`AgentRegistry`. The supervisor's static token
binds to the PM agent_id resolved by the registry from the keyring's
``role='pm'`` token (see README § Long-running supervisor preconditions
for the operator setup; ``pm.yaml``'s ``id`` field is null and registry
resolution at startup populates it from the seeded keyring token).

Working directory: ``<MERIDIAN_ORCH_DATA_DIR>/pm-continuous/`` (no git,
no worktree — PM's writes land via MCP tools, not filesystem).
Symmetric with EngineerSupervisor's ``<data_dir>/engineer/``. The
directory is created on first start by ``main.py``.

**Post-respawn state reconstruction is no-op** (per the M5-pm-supervisor-
base prep checkpoint, P-2 Reading B, 2026-05-01). Handoff-doc reading
is a subprocess-prompt concern landed at M5-continuous-pm-driver, not
daemon-side bookkeeping. Architectural separation: daemon owns
lifecycle (spawn / heartbeat / respawn / token); subprocess owns
startup reads via prompt instructions. Mirrors EngineerSupervisor's
no-op pattern for the same reason. The
``test_reconstruct_state_is_noop_at_initial_spawn_and_respawn`` test
enforces the no-op invariant durably so a future commit doesn't
silently start adding behavior.

Token labeling: the seed token (operator-issued via
``scripts/issue-token.py role=pm label=<seed-label>``) is used only by
the registry to resolve PM's agent_id. The supervisor mints its own
per-spawn token at :meth:`start` time with ``label='pm-continuous'`` —
that is the token the continuous PM subprocess actually authenticates
with. Same two-token pattern as EngineerSupervisor.

See ``docs/kickoff/11-engineer-bridge.md`` § Long-running supervisors
and § Heartbeat watchdog for the full lifecycle semantics, and
``project_subscription_first_architecture`` memory for the M5
subscription-first cost model PM operates against.
"""

from __future__ import annotations

from pathlib import Path

from meridian_orchestrator.agents.registry import AgentRegistry
from meridian_orchestrator.auth import TokenStore
from meridian_orchestrator.bridge.supervisor_base import (
    LongRunningSupervisor,
    SupervisorSubprocessLauncher,
)
from meridian_orchestrator.errors import ValidationError
from meridian_orchestrator.storage.interface import Storage


class PMSupervisor(LongRunningSupervisor):
    """Long-running PM continuous-mode supervisor (pm-continuous lifecycle owner).

    Construction:

    - ``registry`` is the :class:`AgentRegistry` whose ``initial_load`` has
      already resolved agent_ids from keyring tokens. The supervisor reads
      ``registry.get("pm")`` at construction time. If no pm agent is
      registered (e.g., no static seed token issued yet), construction
      raises :class:`ValidationError` — the wiring layer in ``main.py``
      should not enable the supervisor flag without first issuing the seed
      token via ``scripts/issue-token.py role=pm`` (see README § Long-
      running supervisor preconditions for the operator setup).
    - ``working_directory`` is the per-spec ``<data_dir>/pm-continuous/``.
      The caller (typically ``main.py`` reading ``settings.pm_continuous_dir``)
      is responsible for the path; this class does not introspect Settings.
    - All watchdog / cap / launcher parameters pass through to
      :class:`LongRunningSupervisor` — see its docstring for defaults.
    """

    def __init__(
        self,
        *,
        registry: AgentRegistry,
        working_directory: Path,
        storage: Storage,
        token_store: TokenStore,
        subprocess_launcher: SupervisorSubprocessLauncher | None = None,
        check_interval_seconds: float = 60.0,
        timeout_seconds: int = 1800,
        respawn_cap: int = 3,
        respawn_window_seconds: int = 3600,
        grace_seconds: float = 10.0,
    ) -> None:
        pm = registry.get("pm")
        if pm is None:
            raise ValidationError(
                "PMSupervisor requires a registered 'pm' agent. "
                "No pm agent resolves from the registry — check that "
                "config/agents/pm.yaml exists and a static keyring "
                "token with role='pm' has been issued via "
                "scripts/issue-token.py (see README § Long-running "
                "supervisor preconditions for the operator setup)."
            )
        self._registry = registry
        self._pm_working_directory = working_directory
        super().__init__(
            storage=storage,
            token_store=token_store,
            subprocess_launcher=subprocess_launcher,
            check_interval_seconds=check_interval_seconds,
            timeout_seconds=timeout_seconds,
            respawn_cap=respawn_cap,
            respawn_window_seconds=respawn_window_seconds,
            grace_seconds=grace_seconds,
            # Effort level resolved from registry's PM config per F6.2
            # Q3 Reading A. Mirrors EngineerSupervisor's threading
            # pattern. Mike's interactive PM session not affected —
            # this configures the daemon-launched PM-continuous
            # subprocess only.
            effort=pm.config.effort,
        )

    # ---- LongRunningSupervisor hooks -------------------------------------

    def _subprocess_prompt(self) -> str:
        """The PM system prompt from ``config/agents/pm.yaml``, loaded by
        the registry. Read fresh on each spawn so a hot-reload of the
        YAML between spawns picks up the new prompt.

        Mirror of :meth:`EngineerSupervisor._subprocess_prompt`. The
        registry.lifespan() watcher handles config reloads atomically;
        this method just reads the current snapshot.
        """
        pm = self._registry.get("pm")
        if pm is None:
            raise ValidationError(
                "pm agent disappeared from registry between construction "
                "and spawn — investigate the agents directory state"
            )
        return pm.config.system_prompt

    def _working_directory(self) -> Path:
        return self._pm_working_directory

    def _token_role(self) -> str:
        return "pm"

    def _token_label(self) -> str:
        return "pm-continuous"

    def _supervisor_agent_id(self) -> str:
        """The pm agent_id resolved by the registry from the keyring
        seed token whose role is 'pm'. Resolved fresh on every spawn so
        a token rotation (revoke + reissue) between spawns picks up the
        new agent_id (in practice the agent_id is stable across reissues
        per the M2/M3 reconciliation contract, but reading fresh keeps
        the coupling explicit).

        Mirror of :meth:`EngineerSupervisor._supervisor_agent_id`.
        """
        pm = self._registry.get("pm")
        if pm is None:
            raise ValidationError(
                "pm agent disappeared from registry between construction "
                "and spawn — investigate the agents directory state"
            )
        return pm.agent_id

    async def _reconstruct_state(self) -> None:
        """PM's post-respawn hook is a no-op (P-2 Reading B,
        M5-pm-supervisor-base prep checkpoint, 2026-05-01).

        Handoff-doc reading is a subprocess-prompt concern landed at
        M5-continuous-pm-driver, not daemon-side bookkeeping.
        Architectural separation: daemon owns lifecycle (spawn /
        heartbeat / respawn / token); subprocess owns startup reads via
        prompt instructions. Putting filesystem reads in this method
        would couple daemon code to substrate filesystem layout that
        evolves with prompt changes — wrong layer for that knowledge.

        EngineerSupervisor's ``_reconstruct_state`` is no-op for the
        same architectural reason: Engineer reads ``get_dispatch_status``
        and ``get_recent_dispatches`` on every iteration per its prompt,
        so the database is the source of truth.

        Future-proofing: if a future revision wants daemon-side handoff-
        doc reading, override this method. The
        ``test_reconstruct_state_is_noop_at_initial_spawn_and_respawn``
        test will fail informatively, prompting a deliberate
        reconsideration of the layer separation rather than silently
        accreting behavior here.
        """
        return None
