"""Long-running supervisor base class for engineer-supervisor (M4c) and
pm-continuous (M5).

Both supervisor types share the lifecycle plumbing defined here:

- The heartbeat watchdog (60-second poll, 30-minute timeout per spec defaults)
- The terminate/grace/SIGKILL chain on detected hang
- Token issuance + revocation + audit chain on each spawn / respawn
- The three-respawns-per-hour cap with halt + structlog ERROR + audit

Subclasses implement role-specific hooks: subprocess command, working
directory, token role/label, and post-respawn state reconstruction.

Watchdog formula uses ``COALESCE(last_activity_at, started_at)`` so a
silently-crashed supervisor that dies before its first MCP call still
trips the timeout (see Decision A in the M4c-supervisor-base prep). The
``last_activity_at`` writer (and the paired ``last_tool_called`` column)
lives in :mod:`bridge.activity`; this module is the *reader*.

Cap behavior (Decision C in prep): ``start()`` raises
:class:`HaltedSupervisorError` when called on a halted instance. Recovery
requires service restart, which provides a fresh supervisor object.

M5-flag-flips F4 subprocess output handling (symmetric with
``bridge/dispatcher.py:_default_launcher``): ``stdout=DEVNULL``;
``stderr=PIPE`` drained continuously to
``<data_dir>/dispatches/<dispatch_id>/stderr.log`` via
:mod:`bridge.stderr_drain`. Drain task cancellation lives in each of
this base class's existing cleanup paths — :meth:`shutdown`,
:meth:`_handle_heartbeat_timeout`, :meth:`_handle_subprocess_unexpected_exit`,
:meth:`_handle_compaction_completed` — per F4 guardrail #5.

See ``docs/kickoff/11-engineer-bridge.md`` § Heartbeat watchdog and
§ Cap escalation surface for the full lifecycle semantics.
"""

from __future__ import annotations

import asyncio
import contextlib
import uuid
from abc import ABC, abstractmethod
from collections import deque
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Protocol

import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.dispatcher import _TERMINAL_STATES
from meridian_orchestrator.bridge.launch_env import build_subprocess_env
from meridian_orchestrator.bridge.mcp_config import (
    SubprocessRole,
    mcp_config_path,
    write_mcp_config,
)
from meridian_orchestrator.bridge.stderr_drain import (
    DrainingSubprocess,
    _drain_stream_to_file,
    cancel_drain_task,
    stderr_log_path,
    system_prompt_file_path,
)
from meridian_orchestrator.errors import OrchestratorError
from meridian_orchestrator.storage.datetime_utils import _coerce_naive_to_utc
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

_GRACE_SECONDS_DEFAULT = 10.0

# Operator Fix Window 2026-05-27. Short user-message string passed as the
# ``claude -p`` argument. The full supervisor playbook (~30-37 KB) lands
# via ``--append-system-prompt-file`` so the argv stays well under the
# Windows CreateProcess limit. The kickoff tells the subprocess to read
# the appended playbook and start the supervisor cycle by checking MCP
# state. Role substituted via :func:`_supervisor_kickoff`.
_SUPERVISOR_KICKOFF_TEMPLATE = (
    "You are the Meridian {role_label} supervisor. Operate continuously "
    "according to the appended supervisor playbook. Start by checking the "
    "orchestrator queue/state via MCP, then take the next allowed action."
)


def _supervisor_kickoff(role: str) -> str:
    """Map ``ctx.role`` (e.g., ``'pm-continuous'`` / ``'engineer-supervisor'``)
    to the small role-tailored kickoff string passed as ``-p``.

    Unknown roles fall back to the literal role string — defensive against
    future supervisor types; the subprocess still receives a sensible
    kickoff and the bulk content via the appended file."""
    if role.startswith("pm"):
        label = "PM"
    elif role.startswith("engineer"):
        label = "Engineer"
    else:
        label = role
    return _SUPERVISOR_KICKOFF_TEMPLATE.format(role_label=label)


class HaltedSupervisorError(OrchestratorError):
    """Raised by :meth:`LongRunningSupervisor.start` when called on a
    halted instance.

    The three-respawns-per-hour cap has fired; recovery requires service
    restart, not a fresh ``start()`` call. The typed exception lets
    callers (e.g., a daemon-startup orchestrator re-checking supervisor
    liveness) distinguish "halted, do not retry" from other transient
    errors.
    """

    code = "SUPERVISOR_HALTED"


class SupervisorSpawnError(OrchestratorError):
    """Raised by :func:`_default_launcher` when it cannot compose a safe
    argv for the subprocess.

    Specifically: the per-dispatch ``system_prompt.md`` could not be
    written (mkdir / write ``OSError``) AND the supervisor playbook is too
    large to pass inline via ``-p`` without tripping the Windows
    CreateProcess argv limit (``WinError 206``). Rather than silently
    falling back to a known-crashing inline argv — the exact failure mode
    the file-and-pointer fix exists to prevent — we surface a typed,
    actionable error so daemon startup fails loudly with a message that
    names the IO failure instead of an opaque ``FileNotFoundError:
    [WinError 206]``.
    """

    code = "SUPERVISOR_SPAWN_FAILED"


# Operator Fix Window 2026-05-27. Largest ``ctx.prompt`` we will pass
# inline via ``-p`` when the file-and-pointer path is unavailable
# (``dispatches_root`` unset — test fixtures — or the system_prompt.md
# write failed). Chosen well below the Windows CreateProcess command-line
# limit (~32767 chars) to leave headroom for the rest of argv + the
# subprocess module's list2cmdline quoting. Real supervisor playbooks are
# 28-37 KB and always take the file path; test-fixture prompts are a few
# hundred bytes. A prompt above this bound with no writable file path is a
# hard spawn failure (:class:`SupervisorSpawnError`) — see
# :func:`_default_launcher`.
_MAX_INLINE_PROMPT_BYTES = 8192


def _now_iso() -> str:
    """Tz-aware ISO 8601 with microsecond precision.

    Microseconds (rather than ``timespec="seconds"``) so the watchdog's
    age comparison resolves activity updates within the same wall-clock
    second. With second-precision the ``last_activity_at`` column would
    not change for sub-second-spaced activity bursts, and the watchdog's
    `now - effective_dt` would walk forward from the second boundary,
    spuriously firing the timeout for a busy supervisor.
    """
    return datetime.now(tz=UTC).isoformat(timespec="microseconds")


def _new_dispatch_id() -> str:
    return uuid.uuid4().hex


@dataclass(frozen=True)
class SupervisorLaunchContext:
    """Information a launcher needs to start a supervisor subprocess."""

    dispatch_id: str
    role: str
    prompt: str
    working_directory: Path
    token: str
    # Effort level for the claude subprocess invocation per
    # M5-continuous-pm-driver commit 5 effort plumbing. Set at
    # supervisor construction time from the registry's
    # AgentConfig.effort for the supervisor's role (PM/Engineer).
    # Passed to the launcher which threads it into the
    # `claude --effort <level>` subprocess flag. Phase 1 values
    # per orchestrator-utilization-addendum: PM xhigh, Engineer
    # xhigh.
    effort: str = "high"
    # Subprocess-level tool allowlist passed to ``claude --allowedTools
    # <tools>`` so the supervisor subprocess can invoke MCP tools +
    # outside-cwd reads + non-standard Bash commands (``claude --print``
    # mode fails closed on these by default per empirical verification
    # 2026-05-08). Empty-list default so test fixtures constructing
    # SupervisorLaunchContext directly don't silently inject a flag with
    # empty content; the launcher conditionally inserts the flag only
    # when the list is non-empty. Production path: subclass's
    # :meth:`LongRunningSupervisor._subprocess_allowed_tools` resolves via
    # :func:`bridge.mcp_config.resolve_allowed_tools_for_role` against
    # the registry's per-role yaml ``allowed_tools`` + ``mcp_tools_allowed``
    # fields. Symmetric with ``DispatchLaunchContext.allowed_tools``.
    allowed_tools: list[str] = field(default_factory=list)
    # Per-spawn JSON config at ``<dispatches_root>/<spawn_dispatch_id>/
    # mcp_config.json`` containing the orchestrator HTTP MCP entry with
    # ``Authorization: Bearer <per-spawn-token>`` header. Launcher emits
    # ``--mcp-config <path> --strict-mcp-config`` so the subprocess uses
    # ONLY this config; user-scope and local-scope registrations cannot
    # leak in. ``None`` default so test fixtures don't silently inject
    # the flags. Production path: :meth:`LongRunningSupervisor._spawn`
    # calls :func:`bridge.mcp_config.write_mcp_config` to materialize the
    # JSON before launcher invocation, then threads the path here.
    # Symmetric with ``DispatchLaunchContext.mcp_config_path``.
    mcp_config_path: Path | None = None
    env_extras: dict[str, str] = field(default_factory=dict)
    # M5-flag-flips F4 stderr drain destination root. Symmetric with
    # DispatchLaunchContext.dispatches_root in bridge/dispatcher.py.
    # Launcher writes per-dispatch stderr to ``<dispatches_root>/
    # <dispatch_id>/stderr.log`` for operator post-mortem on uncaught
    # supervisor crashes. ``None`` disables the drain (DEVNULL); test
    # fixtures with custom subprocess_launcher injections that don't
    # wire the path keep working unchanged. Production main.py wires
    # ``settings.dispatches_root`` explicitly via the supervisor
    # constructor.
    dispatches_root: Path | None = None


class SupervisorSubprocessHandle(Protocol):
    """Minimal contract for a launched supervisor subprocess.

    :class:`asyncio.subprocess.Process` natively satisfies this Protocol.
    Tests inject a fake exposing the same attributes for deterministic
    timing of terminate / kill / wait.
    """

    @property
    def pid(self) -> int | None: ...

    @property
    def returncode(self) -> int | None: ...

    async def wait(self) -> int: ...

    def terminate(self) -> None: ...

    def kill(self) -> None: ...


SupervisorSubprocessLauncher = Callable[
    [SupervisorLaunchContext], Awaitable[SupervisorSubprocessHandle]
]


async def _default_launcher(
    ctx: SupervisorLaunchContext,
) -> SupervisorSubprocessHandle:
    """Default launcher: invoke ``claude -p <prompt>`` with the supervisor's
    token + dispatch_id in env vars.

    Presumes ``claude`` is on PATH on the daemon host (BACKLOG item for
    explicit configurable path). M2's operator setup and the M7 service
    install ensure this.

    Env construction routes through :func:`bridge.launch_env.build_subprocess_env`
    to strip Anthropic credential env vars per #12352. ``allow_api_key`` is
    not exposed here at M5-record-api-call (long-running supervisors always
    inherit subscription auth at this sub-milestone); M5-flag-flips will
    revisit when ``extra_usage.is_enabled: true`` becomes a configurable
    operator path.

    M5-flag-flips F4 subprocess output handling (symmetric with
    ``bridge/dispatcher.py:_default_launcher``): ``stdout=DEVNULL``
    (operationally noise — supervisor outcomes flow via ``complete_dispatch``
    and the heartbeat / unexpected-exit / compaction audit chain).
    ``stderr=PIPE`` drained continuously to a per-dispatch log file under
    ``<dispatches_root>/<dispatch_id>/stderr.log`` for uncaught-crash
    diagnostic capture. When ``ctx.dispatches_root`` is None (test
    fixtures), stderr also goes to DEVNULL — no drain task spawned. The
    wrapper :class:`bridge.stderr_drain.DrainingSubprocess` exposes the
    drain task for cancellation in the supervisor's existing cleanup
    paths per F4 guardrail #5.
    """
    env = build_subprocess_env(token=ctx.token, dispatch_id=ctx.dispatch_id)
    env.update(ctx.env_extras)

    # F4 guardrail #3: create the per-dispatch directory before subprocess
    # launch. Helper handles OSError best-effort per F4 guardrail #4 —
    # returns None if mkdir fails; launcher falls back to DEVNULL stderr.
    stderr_path: Path | None = None
    if ctx.dispatches_root is not None:
        stderr_path = stderr_log_path(ctx.dispatches_root, ctx.dispatch_id)
    stderr_kwarg = (
        asyncio.subprocess.PIPE
        if stderr_path is not None
        else asyncio.subprocess.DEVNULL
    )

    # Operator Fix Window 2026-05-27: write the supervisor playbook
    # (subclass-provided ``ctx.prompt``, ~30-37KB) to a per-dispatch file
    # and pass it via ``claude --append-system-prompt-file <path>`` so
    # the argv stays well under the Windows CreateProcess limit
    # (WinError 206). The ``-p`` argument shrinks to a small role-aware
    # kickoff string. The file is sibling to ``stderr.log`` +
    # ``mcp_config.json`` under ``<dispatches_root>/<dispatch_id>/``.
    #
    # Falls back to the legacy inline ``-p <ctx.prompt>`` shape when
    # ``dispatches_root`` is None (test fixtures injecting custom
    # subprocess_launcher) OR when the path helper returns None (mkdir
    # failure). The fallback preserves test-fixture argv shape exactly
    # so existing tests don't churn.
    sysprompt_path: Path | None = None
    if ctx.dispatches_root is not None:
        sysprompt_path = system_prompt_file_path(
            ctx.dispatches_root, ctx.dispatch_id
        )
        if sysprompt_path is not None:
            try:
                sysprompt_path.write_text(ctx.prompt, encoding="utf-8")
            except OSError as exc:
                # Best-effort: if the file write fails, fall back to
                # inline prompt (same as test-fixture path). Surface
                # via WARNING so operators can spot persistent IO
                # failures distinct from the silent fast path.
                logger.warning(
                    "supervisor.system_prompt_file_write_failed",
                    dispatch_id=ctx.dispatch_id,
                    path=str(sysprompt_path),
                    error=str(exc),
                )
                sysprompt_path = None

    # CLI flag literals verified via `claude --help` + 2026-05-27 probe:
    # - `--effort <level>`: low/medium/high/xhigh/max
    # - `--allowedTools <tools...>`: comma- or space-separated tool names
    # - `--mcp-config <configs...>` + `--strict-mcp-config`: hermetic MCP
    # - `--append-system-prompt-file=<path>`: appends file contents to
    #   the default Claude Code system prompt (does NOT replace it; tool
    #   set + MCP behavior preserved). Verified live 2026-05-27 by
    #   running ``claude --append-system-prompt-file=<file> -p <q>`` and
    #   observing both the appended sentinel AND Claude Code's default
    #   tooling (Read/Edit/Grep) in the response. We emit the single-token
    #   ``--append-system-prompt-file=<path>`` form — byte-identical to the
    #   live-verified invocation — rather than the two-token
    #   ``--append-system-prompt-file <path>`` shape, so what ships matches
    #   what was probed (no parse-form asymmetry).
    #
    # Argv shape (post-fix): ("claude", "--effort", <level>,
    # ["--allowedTools", <space-joined>],
    # ["--mcp-config", <path>, "--strict-mcp-config"],
    # ["--append-system-prompt-file=<sysprompt_path>"], "-p", <kickoff>).
    # Inline path when sysprompt_path is None: ("claude", "--effort",
    # ..., "-p", <ctx.prompt>) — identical to pre-fix shape so test
    # fixtures injecting custom launchers OR running without
    # dispatches_root don't see argv churn. Guarded by
    # _MAX_INLINE_PROMPT_BYTES: a large prompt with no writable file path
    # raises SupervisorSpawnError rather than re-creating the WinError 206
    # condition this fix exists to prevent.
    argv: list[str] = ["claude", "--effort", ctx.effort]
    if ctx.allowed_tools:
        argv.extend(["--allowedTools", " ".join(ctx.allowed_tools)])
    if ctx.mcp_config_path is not None:
        argv.extend(
            ["--mcp-config", str(ctx.mcp_config_path), "--strict-mcp-config"]
        )
    if sysprompt_path is not None:
        argv.append(f"--append-system-prompt-file={sysprompt_path}")
        argv.extend(["-p", _supervisor_kickoff(ctx.role)])
    else:
        # No writable file path (dispatches_root unset, or mkdir/write
        # failed above). Inline is only safe for small prompts — a 28-37 KB
        # supervisor playbook passed inline is precisely the WinError 206
        # crash this fix removes. Fail loudly with an actionable typed
        # error instead of composing an argv that CreateProcess will reject
        # with an opaque FileNotFoundError.
        prompt_bytes = len(ctx.prompt.encode("utf-8"))
        if prompt_bytes > _MAX_INLINE_PROMPT_BYTES:
            raise SupervisorSpawnError(
                f"cannot launch {ctx.role} supervisor (dispatch "
                f"{ctx.dispatch_id}): system_prompt.md could not be written "
                f"and the {prompt_bytes}-byte playbook is too large to pass "
                f"inline via -p (Windows CreateProcess argv limit ~32 KB, "
                f"WinError 206). Check disk space / permissions for the "
                f"dispatches directory under dispatches_root."
            )
        argv.extend(["-p", ctx.prompt])
    proc = await asyncio.create_subprocess_exec(
        *argv,
        cwd=str(ctx.working_directory),
        env=env,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=stderr_kwarg,
    )

    drain_task: asyncio.Task[None] | None = None
    if stderr_path is not None:
        drain_task = asyncio.create_task(
            _drain_stream_to_file(
                proc.stderr, stderr_path, dispatch_id=ctx.dispatch_id
            ),
            name=f"stderr-drain-{ctx.dispatch_id}",
        )

    return DrainingSubprocess(proc=proc, drain_task=drain_task)


@dataclass(frozen=True)
class _RespawnEntry:
    """One row in the supervisor's in-process respawn history.

    ``triggered_by_dispatch_id`` is the dispatch_id of the *timed-out*
    instance whose timeout drove this respawn. ``last_tool_called`` is
    that instance's ``dispatches.last_tool_called`` value at the
    timeout-detection moment (Decision B).
    """

    timestamp: datetime
    triggered_by_dispatch_id: str
    last_tool_called: str | None


@dataclass(frozen=True)
class _CycleHistoryEntry:
    """One row in the supervisor's in-process cycle-completed history.

    The spin guard (β-S11-4 commit B) tracks healthy supervisor cycle
    completions in a fixed-window deque. ``triggered_by_dispatch_id`` is
    the dispatch_id of the just-completed-cycle instance;
    ``last_tool_called`` is its ``dispatches.last_tool_called`` value at
    the cycle-completed moment, for diagnostic capture in the halt audit.
    """

    timestamp: datetime
    triggered_by_dispatch_id: str
    last_tool_called: str


class LongRunningSupervisor(ABC):
    """Abstract base for engineer-supervisor (M4c) and pm-continuous (M5).

    Subclasses implement five hooks:

    - :meth:`_subprocess_prompt` — the ``claude -p`` prompt
    - :meth:`_working_directory` — subprocess cwd (persistent across respawns)
    - :meth:`_token_role` and :meth:`_token_label` — for the issued token
    - :meth:`_supervisor_agent_id` — the agent_id the token binds to
    - :meth:`_reconstruct_state` — post-spawn daemon-side bookkeeping
      (default no-op; both Engineer and PM keep no-op per architectural
      separation — handoff-doc reading for PM lives subprocess-side via
      tools/pm_handoff.py + pm.yaml convention; database is source of
      truth for Engineer)

    The subclass holds no lifecycle state — all dispatch state lives on
    this base. Subclass tests can inspect the base's protected state
    (``_current_dispatch_id``, ``_respawn_history``, ``_halted``) directly.
    """

    def __init__(
        self,
        *,
        storage: Storage,
        token_store: TokenStore,
        subprocess_launcher: SupervisorSubprocessLauncher | None = None,
        check_interval_seconds: float = 60.0,
        timeout_seconds: int = 1800,
        respawn_cap: int = 3,
        respawn_window_seconds: int = 3600,
        cycle_completed_cap: int = 5,
        cycle_completed_window_seconds: int = 60,
        grace_seconds: float = _GRACE_SECONDS_DEFAULT,
        effort: str = "high",
        dispatches_root: Path | None = None,
        daemon_url: str | None = None,
    ) -> None:
        self._storage = storage
        self._token_store = token_store
        self._launcher: SupervisorSubprocessLauncher = (
            subprocess_launcher
            if subprocess_launcher is not None
            else _default_launcher
        )
        self._check_interval = check_interval_seconds
        self._timeout_seconds = timeout_seconds
        self._respawn_cap = respawn_cap
        self._respawn_window = timedelta(seconds=respawn_window_seconds)
        # β-S11-4 commit B spin guard: N healthy cycle-completed exits
        # within W seconds halts the supervisor via _halt_spin_guard.
        # Separate deque from _respawn_history (A2 binding): healthy
        # cycles and failure-recovery cycles have different N/W and
        # different halt semantics — conflating would cross-couple the
        # two cap accounts.
        self._cycle_completed_cap = cycle_completed_cap
        self._cycle_completed_window = timedelta(
            seconds=cycle_completed_window_seconds
        )
        self._grace = grace_seconds
        # M5-flag-flips F4: per-dispatch stderr log root. Threaded to
        # the default launcher via SupervisorLaunchContext.dispatches_root.
        # Symmetric with BridgeDispatcher's same-named field. Optional
        # with None default to keep test fixtures with custom
        # subprocess_launcher injections free from path-wiring churn
        # (per feedback_optional_dependency_in_helpers precedent).
        # main.py wires settings.dispatches_root explicitly through
        # EngineerSupervisor / PMSupervisor subclasses.
        self._dispatches_root = dispatches_root
        # Effort level threaded into SupervisorLaunchContext at each
        # _spawn() call. Set at construction from the registry's
        # AgentConfig.effort for the supervisor's role (PM/Engineer)
        # by main.py's lifespan; held on the supervisor across
        # respawns so heartbeat-driven respawns reuse the same
        # effort level (no re-resolution per respawn — yaml hot-reload
        # picks up new effort at the NEXT supervisor restart, not at
        # mid-flight respawns, per F6.2 Q9 disposition's
        # supervisor-granularity acknowledgment).
        self._effort = effort
        # Orchestrator daemon URL embedded into per-spawn mcp_config.json's
        # meridian-orchestrator HTTP MCP entry as the ``url`` field.
        # Resolved from settings.mcp_host + settings.mcp_port at main.py
        # construction time. ``None`` default + None-handling at spawn
        # time preserves the test-fixture path: when daemon_url is None
        # OR dispatches_root is None, the per-spawn JSON write is
        # skipped and the launcher omits ``--mcp-config`` entirely.
        # Symmetric with ``BridgeDispatcher._daemon_url``.
        self._daemon_url = daemon_url

        # Mutable lifecycle state.
        self._current_dispatch_id: str | None = None
        self._current_token: IssuedToken | None = None
        self._current_proc: SupervisorSubprocessHandle | None = None
        self._respawn_history: deque[_RespawnEntry] = deque()
        self._cycle_history: deque[_CycleHistoryEntry] = deque()
        self._halted: bool = False
        self._started: bool = False
        self._watchdog_task: asyncio.Task[None] | None = None
        # Compaction signal: set True by tools.supervisor.signal_compaction
        # via mark_compaction_pending(); read by the watchdog at subprocess-
        # exit detection time to distinguish a compaction-driven clean exit
        # from an unexpected exit. Reset to False on every _spawn() so
        # respawned supervisors start with a clean slate. See
        # 11-engineer-bridge.md § Engineer compaction.
        self._compaction_pending: bool = False

    # ---- Subclass hooks ---------------------------------------------------

    @abstractmethod
    def _subprocess_prompt(self) -> str:
        """The ``claude -p`` prompt for the subprocess."""
        ...

    @abstractmethod
    def _working_directory(self) -> Path:
        """Subprocess cwd. Persistent across respawns (single directory per
        supervisor type — engineer-supervisor uses ``<data_dir>/engineer/``)."""
        ...

    @abstractmethod
    def _token_role(self) -> str:
        """Token's role (``engineer`` or ``pm``)."""
        ...

    @abstractmethod
    def _token_label(self) -> str:
        """Token's label (e.g., ``engineer-supervisor``)."""
        ...

    @abstractmethod
    def _supervisor_agent_id(self) -> str:
        """The agent_id the supervisor's static token binds to."""
        ...

    @abstractmethod
    def _subprocess_allowed_tools(self) -> list[str]:
        """The ``claude --allowedTools`` argv list for the supervisor
        subprocess. Mirrors :meth:`_subprocess_prompt` shape — read fresh
        on each spawn so a hot-reload of yaml ``allowed_tools`` between
        spawns picks up the new list.

        Subclasses typically resolve via
        :func:`bridge.mcp_config.resolve_allowed_tools_for_role` against
        the registry: built-in tools (yaml ``allowed_tools``) +
        prefixed orchestrator MCP tools (yaml ``mcp_tools_allowed``).
        Empty list causes the launcher to omit ``--allowedTools``
        entirely (test-fixture path)."""
        ...

    async def _reconstruct_state(self) -> None:
        """Daemon-side post-spawn hook. Default: no-op.

        Both Engineer's and PM's subclasses leave this as no-op per
        architectural separation (M5-pm-supervisor-base P-2 Reading B,
        2026-05-01; reaffirmed at M5-continuous-pm-driver commit 8
        per F9.0 Reading Y disposition, 2026-05-07):

        - **Engineer**: the Engineer subprocess reads
          ``get_dispatch_status`` and ``get_recent_dispatches`` on
          every iteration, so the database is the source of truth and
          no daemon-side bookkeeping is needed.
        - **PM**: handoff-doc reading at compaction respawn is a
          subprocess-prompt concern via :mod:`tools.pm_handoff`
          (``write_pm_handoff`` + ``get_most_recent_handoff`` MCP
          tools) + pm.yaml convention. The substrate filesystem layout
          for handoffs is operator-tunable and evolves with PM-yaml
          conventions; encapsulating it in tools/ keeps daemon-internal
          lifecycle code substrate-layout-agnostic. A future revision
          wanting daemon-side handoff-doc reading would need to
          override this method AND remove
          ``test_reconstruct_state_is_noop_at_initial_spawn_and_respawn``
          and write a new spec (per Reading X future-proofing path).

        Called from :meth:`_spawn` on every spawn (initial start and each
        respawn). Subclasses that need to distinguish initial-vs-respawn
        can inspect ``self._respawn_history`` (empty on initial start).
        """
        return None

    # ---- Public lifecycle -------------------------------------------------

    async def start(self) -> None:
        """Issue token, insert dispatch row, launch subprocess, start watchdog.

        Raises:
            HaltedSupervisorError: if this instance has been halted by
                the three-respawns-per-hour cap. Recovery requires service
                restart, which produces a fresh supervisor instance.
            RuntimeError: if already started (programming bug).
        """
        if self._halted:
            raise HaltedSupervisorError(
                f"{self._token_label()} is halted; service restart required "
                "for recovery"
            )
        if self._started:
            raise RuntimeError(
                f"{self._token_label()} already started; call shutdown() first"
            )

        await self._spawn()
        self._started = True
        self._watchdog_task = asyncio.create_task(
            self._watchdog_loop(),
            name=f"supervisor-watchdog-{self._token_label()}",
        )

    async def shutdown(self) -> None:
        """Daemon-shutdown path. Cancels watchdog, terminates subprocess,
        revokes token, marks current dispatch row ``status='cancelled'``.

        Does NOT halt the supervisor — graceful shutdown is distinct from
        cap-fire halt. After ``shutdown()``, a fresh ``start()`` is allowed
        (it would issue a new token and spawn a new subprocess).
        """
        if self._watchdog_task is not None:
            self._watchdog_task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._watchdog_task
            self._watchdog_task = None

        proc = self._current_proc
        token = self._current_token
        dispatch_id = self._current_dispatch_id

        if proc is not None:
            await self._terminate_subprocess(proc)
            # M5-flag-flips F4 guardrail #5: cancel the stderr drain task
            # in the existing shutdown cleanup path. No-op for non-
            # DrainingSubprocess handles (test fakes) and for already-
            # completed drain tasks (subprocess closed stderr cleanly →
            # drain hit EOF before terminate).
            await cancel_drain_task(proc)

        if token is not None and dispatch_id is not None:
            revoked = await self._token_store.revoke(token.record.token_id)
            if revoked:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.token_revoked",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "token_id": token.record.token_id,
                        "reason": "shutdown",
                    },
                )

        if dispatch_id is not None:
            await self._storage.execute(
                "UPDATE dispatches SET status = 'cancelled', "
                "completed_at = ? WHERE id = ?",
                (_now_iso(), dispatch_id),
            )
            await write_audit(
                self._storage,
                actor="system",
                action="dispatch.subprocess_terminated",
                target=dispatch_id,
                details={
                    "dispatch_id": dispatch_id,
                    "reason": "shutdown",
                },
            )

        self._current_proc = None
        self._current_token = None
        self._current_dispatch_id = None
        self._started = False

    @property
    def is_halted(self) -> bool:
        """True if the three-respawns-per-hour cap has fired on this
        instance. Recovery requires service restart."""
        return self._halted

    @property
    def current_dispatch_id(self) -> str | None:
        """The currently-active dispatch_id, or None during the brief gap
        between marking a row terminal and inserting the new respawn row.
        Read by the ``signal_compaction`` MCP tool handler to verify the
        supervisor has an active dispatch before setting the compaction
        flag."""
        return self._current_dispatch_id

    @property
    def supervisor_agent_id(self) -> str:
        """The agent_id the supervisor's static token binds to. Subclass-
        provided via :meth:`_supervisor_agent_id`; exposed as a property
        for the ``signal_compaction`` MCP tool handler's caller-identity
        validation."""
        return self._supervisor_agent_id()

    def mark_compaction_pending(self) -> None:
        """Mark this supervisor as having a pending compaction.

        Called by the ``signal_compaction`` MCP tool handler after the
        Engineer subprocess decides to compact. The flag is reset to False
        on every :meth:`_spawn` (so respawned supervisors start clean). The
        watchdog reads this flag at subprocess-exit detection time to
        distinguish compaction (expected, status='compacted') from
        unexpected exit (status='failed').

        See ``11-engineer-bridge.md`` § Engineer compaction for the full
        contract and the daemon-restart edge case.
        """
        self._compaction_pending = True

    # ---- Internal lifecycle -----------------------------------------------

    async def _spawn(self) -> None:
        """Issue token, insert dispatches row at status='running', launch
        subprocess, audit chain. Used by :meth:`start` and the
        respawn-on-timeout path. Inserts ``task_id=NULL`` directly via
        storage (per migration 0007 + the spec's call-site separation
        contract).
        """
        dispatch_id = _new_dispatch_id()
        # Reset the compaction flag for the new dispatch (whether this is
        # an initial start, a heartbeat-timeout respawn, an unexpected-exit
        # respawn, or a compaction-completed respawn). The flag belongs to
        # the just-issued dispatch, not any prior instance.
        self._compaction_pending = False

        await self._storage.execute(
            "INSERT INTO dispatches "
            "(id, task_id, role, status, working_directory, timeout_seconds) "
            "VALUES (?, NULL, ?, 'queued', ?, ?)",
            (
                dispatch_id,
                self._token_role_with_label(),
                str(self._working_directory()),
                self._timeout_seconds,
            ),
        )
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.issued",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "role": self._token_role_with_label(),
                "supervisor_label": self._token_label(),
                "working_directory": str(self._working_directory()),
                "timeout_seconds": self._timeout_seconds,
            },
        )

        # β-S11-5/6 fix: indexed=False for high-churn per-spawn supervisor
        # tokens. Same reasoning as bridge/dispatcher.py — the keyring
        # `_index` blob would otherwise grow unbounded across supervisor
        # respawn cycles and trip Windows Credential Manager's
        # ~2560-byte CredentialBlob limit, boot-blocking the daemon.
        # Per-token `token:<id>` record is still written, so the
        # subprocess's MCP Bearer auth and the cleanup-path revocations
        # both work normally; tradeoff is that supervisor per-spawn
        # tokens don't surface in `scripts/list-tokens.py` (visibility
        # via audit_log / dispatches instead).
        issued = await self._token_store.issue(
            self._supervisor_agent_id(),
            self._token_role(),
            self._token_label(),
            indexed=False,
        )
        await self._storage.execute(
            "UPDATE dispatches SET token_id = ? WHERE id = ?",
            (issued.record.token_id, dispatch_id),
        )
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.token_created",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "token_id": issued.record.token_id,
            },
        )

        await self._reconstruct_state()

        # Materialize per-spawn mcp_config.json with the orchestrator
        # HTTP MCP entry carrying ``Authorization: Bearer <issued.token>``
        # header. Both ``self._dispatches_root`` and ``self._daemon_url``
        # must be set for the JSON write to fire — when either is None
        # (test-fixture path with custom subprocess_launcher injection),
        # ``mcp_config_path_value`` stays None and the launcher omits
        # ``--mcp-config`` entirely. File persists past dispatch
        # terminal state; per-spawn token revocation in the supervisor's
        # cleanup paths (shutdown / heartbeat-timeout / unexpected-exit /
        # compaction-completed) renders the retained config inert via
        # ``auth.TokenStore.lookup`` returning None for revoked records.
        # Mirrors stderr.log retention convention. Per-spawn directory
        # accumulates alongside stderr.log (same dispatch_id keying).
        # No Playwright entry — supervisors don't run browser-driven
        # verification (role asymmetry: only Tester gets Playwright).
        mcp_config_path_value: Path | None = None
        if (
            self._dispatches_root is not None
            and self._daemon_url is not None
        ):
            cfg_path = mcp_config_path(self._dispatches_root, dispatch_id)
            # _token_role() returns "pm" or "engineer" per supervisor
            # subclass contract; both are SubprocessRole literals.
            supervisor_role: SubprocessRole = self._token_role()  # type: ignore[assignment]
            if cfg_path is not None and write_mcp_config(
                cfg_path,
                daemon_url=self._daemon_url,
                token=issued.token,
                role=supervisor_role,
            ):
                mcp_config_path_value = cfg_path

        ctx = SupervisorLaunchContext(
            dispatch_id=dispatch_id,
            role=self._token_role_with_label(),
            prompt=self._subprocess_prompt(),
            working_directory=self._working_directory(),
            token=issued.token,
            effort=self._effort,
            allowed_tools=self._subprocess_allowed_tools(),
            mcp_config_path=mcp_config_path_value,
            dispatches_root=self._dispatches_root,
        )
        proc = await self._launcher(ctx)

        await self._storage.execute(
            "UPDATE dispatches SET status = 'running', "
            "subprocess_pid = ?, started_at = ? WHERE id = ?",
            (proc.pid, _now_iso(), dispatch_id),
        )
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.subprocess_started",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "pid": proc.pid,
                "cwd": str(self._working_directory()),
            },
        )

        # Atomic state update: synchronous assignments only.
        self._current_dispatch_id = dispatch_id
        self._current_token = issued
        self._current_proc = proc

    def _token_role_with_label(self) -> str:
        """Return the role string used in the dispatches.role column.

        For supervisors this is ``engineer-supervisor`` or ``pm-continuous``
        — distinct from the token's role (``engineer`` / ``pm``) so the
        column distinguishes supervisor rows from sub-agent rows. The
        token's role + label are stored on the keyring record; the
        dispatches.role column carries the combined identity.
        """
        return self._token_label()

    # ---- Watchdog ---------------------------------------------------------

    async def _watchdog_loop(self) -> None:
        """Background task. Polls every ``check_interval`` seconds. Exits
        cleanly when ``self._halted`` becomes True after a cap fire or
        when the task is cancelled (shutdown path).
        """
        try:
            while not self._halted:
                await asyncio.sleep(self._check_interval)
                if self._halted:
                    break
                if self._current_dispatch_id is None:
                    # Transient state between respawn-end and
                    # current_dispatch_id assignment. Skip iteration.
                    continue
                try:
                    # Subprocess-exit detection runs first. If the
                    # subprocess has exited, the dispatch is terminal
                    # one way or another (compacted, unexpected_exit,
                    # or — in race-prone scenarios — already terminal
                    # via heartbeat-timeout); skip the timeout check.
                    if await self._check_and_handle_subprocess_exit():
                        continue
                    await self._check_and_handle_timeout()
                except asyncio.CancelledError:
                    raise
                except Exception:
                    logger.exception(
                        "supervisor.watchdog_iteration_failed",
                        role=self._token_label(),
                        dispatch_id=self._current_dispatch_id,
                    )
        except asyncio.CancelledError:
            raise

    async def _check_and_handle_subprocess_exit(self) -> bool:
        """If the supervised subprocess has exited, audit + mark terminal +
        respawn. Returns True if an exit was handled (caller should skip
        the timeout check).

        Distinguishes compaction-driven clean exit (``_compaction_pending``
        is True; row → ``status='compacted'``; respawn does NOT increment
        the cap counter) from unexpected exit (``_compaction_pending`` is
        False; row → ``status='failed'``; respawn DOES increment the cap
        counter, same as a heartbeat timeout).

        See ``11-engineer-bridge.md`` § Engineer compaction.
        """
        proc = self._current_proc
        dispatch_id = self._current_dispatch_id
        if proc is None or dispatch_id is None:
            return False
        if proc.returncode is None:
            return False  # subprocess still running

        # Subprocess has exited. Determine which path.
        exit_code = proc.returncode
        if self._compaction_pending:
            await self._handle_compaction_completed(dispatch_id, exit_code)
            return True

        # β-S11-4 commit A: classify clean rc=0 + useful MCP activity as
        # healthy supervisor-cycle completion (status='completed'; no cap-
        # counter increment). See docs/specs/beta-S11-4-supervisor-cycle-
        # completed.md § 2 + § 4 priority ordering.
        if exit_code == 0:
            row = await self._storage.fetch_one(
                "SELECT last_tool_called, started_at FROM dispatches "
                "WHERE id = ?",
                (dispatch_id,),
            )
            if row is not None and row["last_tool_called"] is not None:
                await self._handle_supervisor_cycle_completed(
                    dispatch_id,
                    exit_code,
                    row["last_tool_called"],
                    row["started_at"],
                )
                return True

        await self._handle_subprocess_unexpected_exit(dispatch_id, exit_code)
        return True

    async def _handle_compaction_completed(
        self, dispatch_id: str, exit_code: int
    ) -> None:
        """The supervisor exited cleanly after signal_compaction. Audit
        ``<role>.compaction_completed``, revoke token, mark row
        'compacted', respawn without touching the respawn-history
        (compaction is healthy lifecycle, not failure recovery).

        Audit-action namespace is role-prefixed via
        :meth:`_token_role` — engineer-supervisor writes
        ``engineer.compaction_completed`` (preserves M4c convention);
        pm-supervisor writes ``pm.compaction_completed`` (added at
        M5-continuous-pm-driver commit 8 per F9.2 Q3 Reading A
        symmetric-role-namespace framing).
        """
        await write_audit(
            self._storage,
            actor="system",
            action=f"{self._token_role()}.compaction_completed",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "exit_code": exit_code,
            },
        )

        if self._current_token is not None:
            revoked = await self._token_store.revoke(
                self._current_token.record.token_id
            )
            if revoked:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.token_revoked",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "token_id": self._current_token.record.token_id,
                        "reason": "compacted",
                    },
                )

        await self._storage.execute(
            "UPDATE dispatches SET status = 'compacted', "
            "completed_at = ? WHERE id = ?",
            (_now_iso(), dispatch_id),
        )

        # M5-flag-flips F4 guardrail #5: cancel the stderr drain task in
        # the existing compaction-completed cleanup path before clearing
        # the proc reference. Subprocess already exited cleanly so the
        # drain task most likely hit EOF; cancel + await is idempotent
        # and ensures the task object isn't orphaned.
        await cancel_drain_task(self._current_proc)

        # Clear in-process state — subprocess is dead, token revoked, row
        # terminal. Watchdog will skip iterations until _spawn() sets the
        # new _current_dispatch_id (the (b)-2 atomicity pattern).
        self._current_proc = None
        self._current_token = None
        self._current_dispatch_id = None

        # Respawn fresh. NOTE: no cap-counter increment — compaction is
        # healthy lifecycle, not failure recovery. The cap exists for
        # hung-supervisor detection (auto-respawn alone isn't a fix for
        # a deeper bug). An Engineer that compacts three times in an
        # hour is healthy under load; halting it would defeat the point
        # of compaction. Heartbeat-timeout and unexpected-exit respawns
        # DO increment the cap counter (see _handle_heartbeat_timeout
        # and _handle_subprocess_unexpected_exit) — those are failure
        # modes, not lifecycle.
        await self._spawn()

    async def _handle_supervisor_cycle_completed(
        self,
        dispatch_id: str,
        exit_code: int,
        last_tool_called: str,
        started_at_raw: str | None,
    ) -> None:
        """The supervisor exited cleanly (rc=0) after at least one MCP call.
        Classify as a healthy cycle completion: status='completed', new
        ``dispatch.supervisor_cycle_completed`` audit event, respawn without
        touching ``_respawn_history`` (A3 invariant — healthy cycles do not
        interact with the failure-recovery cap counter).

        Mirrors :meth:`_handle_compaction_completed` shape. See
        ``docs/specs/beta-S11-4-supervisor-cycle-completed.md`` § 2, § 8,
        § 10. Spin-guard (commit B) is a separate landing.
        """
        cycle_duration_seconds: float | None = None
        if started_at_raw is not None:
            try:
                effective_dt = datetime.fromisoformat(started_at_raw)
            except ValueError:
                effective_dt = None
            if effective_dt is not None:
                effective_dt = _coerce_naive_to_utc(effective_dt)
                cycle_duration_seconds = round(
                    (datetime.now(tz=UTC) - effective_dt).total_seconds(), 3
                )

        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.supervisor_cycle_completed",
            target=dispatch_id,
            details={
                "supervisor_role": self._token_role_with_label(),
                "dispatch_id": dispatch_id,
                "last_tool_called": last_tool_called,
                "cycle_duration_seconds": cycle_duration_seconds,
                "rc": exit_code,
                "compaction_triggered": False,
            },
        )

        if self._current_token is not None:
            revoked = await self._token_store.revoke(
                self._current_token.record.token_id
            )
            if revoked:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.token_revoked",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "token_id": self._current_token.record.token_id,
                        "reason": "cycle_completed",
                    },
                )

        await self._storage.execute(
            "UPDATE dispatches SET status = 'completed', "
            "completed_at = ? WHERE id = ?",
            (_now_iso(), dispatch_id),
        )

        await cancel_drain_task(self._current_proc)

        self._current_proc = None
        self._current_token = None
        self._current_dispatch_id = None

        logger.debug(
            "supervisor.cycle_completed",
            role=self._token_role_with_label(),
            dispatch_id=dispatch_id,
            last_tool_called=last_tool_called,
            cycle_duration_seconds=cycle_duration_seconds,
        )

        # β-S11-4 commit B spin guard: prune entries older than the
        # window, append this cycle's entry, and halt if N exits have
        # occurred within W seconds. Healthy cycles do NOT touch
        # _respawn_history (A3 invariant); the spin-guard deque is
        # independent of the failure-recovery cap.
        self._prune_cycle_history()
        self._cycle_history.append(
            _CycleHistoryEntry(
                timestamp=datetime.now(tz=UTC),
                triggered_by_dispatch_id=dispatch_id,
                last_tool_called=last_tool_called,
            )
        )
        if len(self._cycle_history) >= self._cycle_completed_cap:
            await self._halt_spin_guard(triggering_dispatch_id=dispatch_id)
        else:
            await self._spawn()

    async def _handle_subprocess_unexpected_exit(
        self, dispatch_id: str, exit_code: int
    ) -> None:
        """The supervisor exited without a prior compaction signal.
        Treat as a failure (status='failed') and apply the same cap
        policy as heartbeat-timeout respawns (counts toward the
        three-respawns-per-hour limit)."""
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.subprocess_unexpected_exit",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "exit_code": exit_code,
                "signal": None,  # we observed exit; no signal we sent
            },
        )

        if self._current_token is not None:
            revoked = await self._token_store.revoke(
                self._current_token.record.token_id
            )
            if revoked:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.token_revoked",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "token_id": self._current_token.record.token_id,
                        "reason": "unexpected_exit",
                    },
                )

        await self._storage.execute(
            "UPDATE dispatches SET status = 'failed', "
            "completed_at = ?, notes = ? WHERE id = ?",
            (
                _now_iso(),
                f"subprocess exited rc={exit_code} without prior compaction signal",
                dispatch_id,
            ),
        )

        # M5-flag-flips F4 guardrail #5: cancel the stderr drain task in
        # the existing unexpected-exit cleanup path before clearing the
        # proc reference. Subprocess already exited (returncode is set);
        # drain task most likely hit EOF, but explicit cancel + await is
        # idempotent and prevents orphan task accumulation if the kernel
        # didn't propagate EOF cleanly (e.g., crash-mid-write).
        await cancel_drain_task(self._current_proc)

        self._current_proc = None
        self._current_token = None
        self._current_dispatch_id = None

        # Cap-aware respawn — same policy as heartbeat-timeout.
        # last_tool_called for this respawn is captured from the row
        # before we clear in-process state; for unexpected exit, we
        # don't have a fresh read, so use None (consistent with the
        # "we don't know what happened" framing).
        self._prune_respawn_history()
        if len(self._respawn_history) >= self._respawn_cap:
            await self._halt(
                triggering_dispatch_id=dispatch_id,
                triggering_last_tool=None,
            )
        else:
            self._respawn_history.append(
                _RespawnEntry(
                    timestamp=datetime.now(tz=UTC),
                    triggered_by_dispatch_id=dispatch_id,
                    last_tool_called=None,
                )
            )
            await self._spawn()

    async def _check_and_handle_timeout(self) -> None:
        """One watchdog iteration: read row, check age, fire timeout if hung."""
        dispatch_id = self._current_dispatch_id
        if dispatch_id is None:
            return

        row = await self._storage.fetch_one(
            "SELECT last_activity_at, started_at, last_tool_called, status "
            "FROM dispatches WHERE id = ?",
            (dispatch_id,),
        )
        if row is None:
            return  # row vanished (test cleanup, or external manipulation)
        if row["status"] in _TERMINAL_STATES:
            return  # already terminal — something else handled this dispatch

        effective = row["last_activity_at"] or row["started_at"]
        if effective is None:
            return  # neither timestamp set; defensive (shouldn't happen post-spawn)

        try:
            effective_dt = datetime.fromisoformat(effective)
        except ValueError:
            return  # unparseable timestamp; defensive

        # Defensive: tolerate naive timestamps by treating them as UTC.
        # The activity writer (bridge.activity._UPDATE_SQL) uses
        # tz-aware ISO strings, and supervisor_base's _now_iso() does
        # the same. But other dispatches.* timestamp columns are SQL
        # `datetime('now')` (naive). If a future code path writes the
        # last_activity_at column via SQL `datetime('now')` instead of
        # routing through record_mcp_call, the watchdog should still
        # work — naive == UTC convention is enforced here as a backstop.
        # Shared helper extracted at M5-subscription-routing commit 2.
        effective_dt = _coerce_naive_to_utc(effective_dt)

        idle_float = (datetime.now(tz=UTC) - effective_dt).total_seconds()
        if idle_float <= self._timeout_seconds:
            return  # not hung

        # Cast for the audit row's int-typed idle_seconds field. The
        # comparison stays float so timeout_seconds=1 fires at idle=1.05,
        # not idle=2 (the int-truncation trap).
        last_tool: str | None = row["last_tool_called"]
        await self._handle_heartbeat_timeout(dispatch_id, last_tool, int(idle_float))

    async def _handle_heartbeat_timeout(
        self,
        dispatch_id: str,
        last_tool_called: str | None,
        idle_seconds: int,
    ) -> None:
        """Run the 7-step timeout sequence from the spec."""
        # Step 1: audit dispatch.heartbeat_timeout with the just-captured
        # last_tool_called from the dispatches row (Decision B's "captured
        # at timeout-detection moment").
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.heartbeat_timeout",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "role": self._token_role_with_label(),
                "idle_seconds": idle_seconds,
                "last_tool_called": last_tool_called,
            },
        )

        # Steps 2-4: terminate subprocess, revoke token, audit termination.
        proc = self._current_proc
        token = self._current_token

        exit_code: int | None = None
        signal_used: str | None = None
        if proc is not None:
            exit_code, signal_used = await self._terminate_subprocess(proc)
            # M5-flag-flips F4 guardrail #5: cancel the stderr drain task
            # in the existing heartbeat-timeout cleanup path, immediately
            # after _terminate_subprocess returns so the drain doesn't
            # outlive the proc reference clear below.
            await cancel_drain_task(proc)

        if token is not None:
            revoked = await self._token_store.revoke(token.record.token_id)
            if revoked:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.token_revoked",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "token_id": token.record.token_id,
                        "reason": "heartbeat_timeout",
                    },
                )

        if proc is not None:
            await write_audit(
                self._storage,
                actor="system",
                action="dispatch.subprocess_terminated",
                target=dispatch_id,
                details={
                    "dispatch_id": dispatch_id,
                    "exit_code": exit_code,
                    "signal": signal_used,
                    "reason": "heartbeat_timeout",
                },
            )

        # Step 5: mark row terminal.
        await self._storage.execute(
            "UPDATE dispatches SET status = 'timed_out', "
            "timed_out_at = ?, completed_at = ? WHERE id = ?",
            (_now_iso(), _now_iso(), dispatch_id),
        )

        # Clear current state — subprocess is dead, token revoked.
        self._current_proc = None
        self._current_token = None
        self._current_dispatch_id = None

        # Steps 6-7: cap check, then respawn or halt.
        self._prune_respawn_history()
        if len(self._respawn_history) >= self._respawn_cap:
            await self._halt(
                triggering_dispatch_id=dispatch_id,
                triggering_last_tool=last_tool_called,
            )
        else:
            self._respawn_history.append(
                _RespawnEntry(
                    timestamp=datetime.now(tz=UTC),
                    triggered_by_dispatch_id=dispatch_id,
                    last_tool_called=last_tool_called,
                )
            )
            await self._spawn()

    def _prune_respawn_history(self) -> None:
        """Drop respawn entries older than ``respawn_window_seconds``."""
        cutoff = datetime.now(tz=UTC) - self._respawn_window
        while self._respawn_history and self._respawn_history[0].timestamp < cutoff:
            self._respawn_history.popleft()

    def _prune_cycle_history(self) -> None:
        """Drop cycle-history entries older than ``cycle_completed_window``."""
        cutoff = datetime.now(tz=UTC) - self._cycle_completed_window
        while (
            self._cycle_history
            and self._cycle_history[0].timestamp < cutoff
        ):
            self._cycle_history.popleft()

    async def _halt(
        self,
        triggering_dispatch_id: str,
        triggering_last_tool: str | None,
    ) -> None:
        """Cap fired. Write audit row, structlog ERROR, set _halted=True.

        The watchdog loop's ``while not self._halted`` exits cleanly after
        ``_handle_heartbeat_timeout`` returns. ``halt()`` is called from
        within the watchdog task; self-cancellation would interrupt this
        very call, so we don't cancel the task here — natural loop exit
        is enough.
        """
        if self._halted:
            return

        respawn_dispatch_ids: list[str] = [
            e.triggered_by_dispatch_id for e in self._respawn_history
        ]
        last_tool_per_dispatch: list[str | None] = [
            e.last_tool_called for e in self._respawn_history
        ]

        details: dict[str, Any] = {
            "role": self._token_role_with_label(),
            "respawn_dispatch_ids": respawn_dispatch_ids,
            "last_tool_called_per_dispatch": last_tool_per_dispatch,
        }
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.heartbeat_timeout_cap",
            target=triggering_dispatch_id,
            details=details,
        )

        # structlog ERROR — the cap is exceptional. Three respawns in an
        # hour means the underlying issue is not transient; operator
        # attention is required. ERROR-level surfaces in default filters
        # and any future log-aggregation pipeline.
        logger.error(
            "supervisor.halted",
            role=self._token_role_with_label(),
            label=self._token_label(),
            triggering_dispatch_id=triggering_dispatch_id,
            triggering_last_tool=triggering_last_tool,
            respawn_dispatch_ids=respawn_dispatch_ids,
            last_tool_called_per_dispatch=last_tool_per_dispatch,
            recovery="service restart required",
        )

        self._halted = True
        self._started = False

    async def _halt_spin_guard(
        self,
        triggering_dispatch_id: str,
    ) -> None:
        """Spin guard fired: too many cycle-completed exits in too short a
        window. Write halt audit, structlog ERROR, set _halted=True.

        Mirror of :meth:`_halt`. Distinct from the
        ``dispatch.heartbeat_timeout_cap`` audit and ``supervisor.halted``
        structlog event so observability tooling can route the two halt
        paths differently (one is failure-recovery exhaustion, the other
        is rapid-healthy-cycle exhaustion).
        """
        if self._halted:
            return

        dispatch_ids: list[str] = [
            e.triggered_by_dispatch_id for e in self._cycle_history
        ]
        last_tool_per_dispatch: list[str] = [
            e.last_tool_called for e in self._cycle_history
        ]

        details: dict[str, Any] = {
            "role": self._token_role_with_label(),
            "n": self._cycle_completed_cap,
            "window_seconds": int(
                self._cycle_completed_window.total_seconds()
            ),
            "dispatch_ids": dispatch_ids,
            "last_tool_called_per_dispatch": last_tool_per_dispatch,
        }
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.supervisor_cycle_spin_halt",
            target=triggering_dispatch_id,
            details=details,
        )

        logger.error(
            "supervisor.cycle_spin_halt",
            role=self._token_role_with_label(),
            label=self._token_label(),
            triggering_dispatch_id=triggering_dispatch_id,
            n=self._cycle_completed_cap,
            window_seconds=int(
                self._cycle_completed_window.total_seconds()
            ),
            dispatch_ids=dispatch_ids,
            last_tool_called_per_dispatch=last_tool_per_dispatch,
            recovery="service restart required",
        )

        self._halted = True
        self._started = False

    async def _terminate_subprocess(
        self, proc: SupervisorSubprocessHandle
    ) -> tuple[int | None, str | None]:
        """SIGTERM with grace period then SIGKILL. Returns (exit_code, signal_used).

        Returns immediately if the subprocess has already exited cleanly
        (e.g., crashed before the watchdog detected the hang). Mirrors
        :meth:`bridge.dispatcher.BridgeDispatcher._terminate_subprocess`
        — same shape, same defensive ordering.
        """
        if proc.returncode is not None:
            return proc.returncode, None
        try:
            proc.terminate()
        except ProcessLookupError:
            return proc.returncode, None
        rc: int | None
        try:
            rc = await asyncio.wait_for(proc.wait(), timeout=self._grace)
            return rc, "SIGTERM"
        except TimeoutError:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            try:
                rc = await asyncio.wait_for(proc.wait(), timeout=self._grace)
            except TimeoutError:
                rc = proc.returncode
            return rc, "SIGKILL"
