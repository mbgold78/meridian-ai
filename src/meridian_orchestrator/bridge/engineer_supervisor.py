"""Engineer-supervisor concrete subclass.

Extends :class:`bridge.supervisor_base.LongRunningSupervisor` with
engineer-specific subprocess command, working directory, token role/label,
and post-respawn state reconstruction. Ships at M4c-engineer-supervisor.

The Engineer subprocess is a long-running ``claude -p`` invocation whose
prompt is the ``system_prompt`` field of ``config/agents/engineer.yaml``,
loaded via the M3 :class:`AgentRegistry`. The supervisor's static token
binds to the Engineer agent_id resolved by the registry from the keyring's
``role='engineer'`` token (``engineer.yaml``'s ``id`` field is null;
registry resolution at startup populates it). The token's *label* is
``engineer-supervisor`` to distinguish this token's use from any future
sub-agent Engineer token.

Working directory: ``<MERIDIAN_ORCH_DATA_DIR>/engineer/`` (no git, no
worktree — sub-agents handle code per the spec). The directory is created
on first start if it doesn't exist.

Post-respawn state reconstruction: no-op. Engineer reads
``get_dispatch_status`` and ``get_recent_dispatches`` on every iteration,
so the database is the source of truth and no daemon-side bookkeeping
survives across respawns. PMSupervisor (M5) keeps this hook no-op too
per M5-pm-supervisor-base P-2 Reading B + M5-continuous-pm-driver F9.0
Reading Y disposition (2026-05-07); PM's handoff-doc reading at
compaction respawn is a subprocess-prompt concern via
:mod:`meridian_orchestrator.tools.pm_handoff` MCP tools + pm.yaml
convention, NOT daemon-side ``_reconstruct_state`` override.

See ``docs/kickoff/11-engineer-bridge.md`` § Long-running supervisors and
§ Heartbeat watchdog for the full lifecycle semantics.
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


class EngineerSupervisor(LongRunningSupervisor):
    """Long-running Engineer supervisor (engineer-supervisor lifecycle owner).

    Construction:

    - ``registry`` is the :class:`AgentRegistry` whose ``initial_load`` has
      already resolved agent_ids from keyring tokens. The supervisor reads
      ``registry.get("engineer")`` at construction time. If no engineer
      agent is registered (e.g., no static token issued yet), construction
      raises :class:`ValidationError` — the wiring layer in ``main.py``
      should not enable the supervisor flag without first issuing the token.
    - ``working_directory`` is the per-spec ``<data_dir>/engineer/``. The
      caller (typically ``main.py`` reading ``settings.engineer_supervisor_dir``)
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
        dispatches_root: Path | None = None,
    ) -> None:
        engineer = registry.get("engineer")
        if engineer is None:
            raise ValidationError(
                "EngineerSupervisor requires a registered 'engineer' agent. "
                "No engineer agent resolves from the registry — check that "
                "config/agents/engineer.yaml exists and a static keyring "
                "token with role='engineer' has been issued via "
                "scripts/issue-token.py."
            )
        self._registry = registry
        self._engineer_working_directory = working_directory
        super().__init__(
            storage=storage,
            token_store=token_store,
            subprocess_launcher=subprocess_launcher,
            check_interval_seconds=check_interval_seconds,
            timeout_seconds=timeout_seconds,
            respawn_cap=respawn_cap,
            respawn_window_seconds=respawn_window_seconds,
            grace_seconds=grace_seconds,
            # Effort level resolved from registry's Engineer config per
            # F6.2 Q3 Reading A. Held on the base across respawns
            # (heartbeat-driven respawns reuse the same effort);
            # yaml hot-reload picks up new effort at the NEXT
            # supervisor restart per F6.2 Q9 disposition.
            effort=engineer.config.effort,
            # M5-flag-flips F4: per-dispatch stderr log root passes
            # through to the default supervisor launcher via
            # SupervisorLaunchContext. main.py wires
            # settings.dispatches_root.
            dispatches_root=dispatches_root,
        )

    # ---- LongRunningSupervisor hooks -------------------------------------

    def _subprocess_prompt(self) -> str:
        """The Engineer system prompt from ``config/agents/engineer.yaml``,
        loaded by the registry. Read fresh on each spawn so a hot-reload of
        the YAML between spawns picks up the new prompt."""
        engineer = self._registry.get("engineer")
        # Re-checked on every spawn rather than cached at construction time:
        # if the operator hot-edits the YAML mid-session, the new prompt
        # lands on the next respawn. The registry.lifespan() watcher
        # handles config reloads atomically.
        if engineer is None:
            raise ValidationError(
                "engineer agent disappeared from registry between construction "
                "and spawn — investigate the agents directory state"
            )
        return engineer.config.system_prompt

    def _working_directory(self) -> Path:
        return self._engineer_working_directory

    def _token_role(self) -> str:
        return "engineer"

    def _token_label(self) -> str:
        return "engineer-supervisor"

    def _supervisor_agent_id(self) -> str:
        """The engineer agent_id resolved by the registry from the keyring
        token whose role is 'engineer'. Resolved fresh on every spawn so a
        token rotation (revoke + reissue) between spawns picks up the new
        agent_id (in practice the agent_id is stable across reissues per
        the M2/M3 reconciliation contract, but reading fresh keeps the
        coupling explicit)."""
        engineer = self._registry.get("engineer")
        if engineer is None:
            raise ValidationError(
                "engineer agent disappeared from registry between construction "
                "and spawn — investigate the agents directory state"
            )
        return engineer.agent_id

    async def _reconstruct_state(self) -> None:
        """Engineer's post-respawn hook is a no-op.

        Engineer reads ``get_dispatch_status`` and ``get_recent_dispatches``
        on every iteration of its operating loop, so the database is the
        source of truth and no daemon-side state survives across respawns
        by design. PMSupervisor (M5) keeps this hook no-op too per
        M5-continuous-pm-driver F9.0 Reading Y disposition (2026-05-07);
        PM's handoff-doc reading at compaction respawn is a
        subprocess-prompt concern via
        :mod:`meridian_orchestrator.tools.pm_handoff` MCP tools, NOT
        daemon-side ``_reconstruct_state`` override.
        """
        return None
