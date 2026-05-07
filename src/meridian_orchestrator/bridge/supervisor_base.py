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
from meridian_orchestrator.errors import OrchestratorError
from meridian_orchestrator.storage.datetime_utils import _coerce_naive_to_utc
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

_GRACE_SECONDS_DEFAULT = 10.0


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
    env_extras: dict[str, str] = field(default_factory=dict)


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
    """
    env = build_subprocess_env(token=ctx.token, dispatch_id=ctx.dispatch_id)
    env.update(ctx.env_extras)
    # CLI flag literal verified via `claude --help` (2026-05-05):
    # `--effort <level>` accepts low/medium/high/xhigh/max. Same
    # ordering as bridge/dispatcher.py:_default_launcher — effort
    # flag before the prompt's `-p` positional.
    proc = await asyncio.create_subprocess_exec(
        "claude",
        "--effort",
        ctx.effort,
        "-p",
        ctx.prompt,
        cwd=str(ctx.working_directory),
        env=env,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    return proc  # asyncio.subprocess.Process satisfies the Protocol


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
        grace_seconds: float = _GRACE_SECONDS_DEFAULT,
        effort: str = "high",
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
        self._grace = grace_seconds
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

        # Mutable lifecycle state.
        self._current_dispatch_id: str | None = None
        self._current_token: IssuedToken | None = None
        self._current_proc: SupervisorSubprocessHandle | None = None
        self._respawn_history: deque[_RespawnEntry] = deque()
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

        if token is not None and dispatch_id is not None:
            revoked = await asyncio.to_thread(
                self._token_store.revoke, token.record.token_id
            )
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

        issued = await asyncio.to_thread(
            self._token_store.issue,
            self._supervisor_agent_id(),
            self._token_role(),
            self._token_label(),
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

        ctx = SupervisorLaunchContext(
            dispatch_id=dispatch_id,
            role=self._token_role_with_label(),
            prompt=self._subprocess_prompt(),
            working_directory=self._working_directory(),
            token=issued.token,
            effort=self._effort,
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
        else:
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
            revoked = await asyncio.to_thread(
                self._token_store.revoke,
                self._current_token.record.token_id,
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
            revoked = await asyncio.to_thread(
                self._token_store.revoke,
                self._current_token.record.token_id,
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

        if token is not None:
            revoked = await asyncio.to_thread(
                self._token_store.revoke, token.record.token_id
            )
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
