"""Engineer-driven sub-agent dispatch.

The :class:`BridgeDispatcher` is the daemon-side lifecycle manager for an
Engineer-issued ``dispatch_implementer`` (M4a) or ``dispatch_tester`` (M4b)
call. Per-dispatch flow:

1. Insert a ``dispatches`` row in ``status='queued'``.
2. Issue a per-dispatch static token via :class:`auth.TokenStore` and audit
   ``dispatch.token_created``.
3. Refresh the canonical clone (``fetch origin`` + ``merge --ff-only
   origin/main``) and provision a per-dispatch worktree.
4. Launch the Claude Code subprocess with the spec content as ``-p`` and the
   token + dispatch_id in the env, audit ``dispatch.subprocess_started``.
5. Poll the dispatch row for a terminal state (set by ``complete_dispatch``),
   while also watching the subprocess exit code. Whichever surfaces first is
   the truth: ``complete_dispatch`` lands the canonical terminal state;
   subprocess exit without ``complete_dispatch`` is treated as ``failed``.
6. Revoke the token, terminate the subprocess if still alive, clean up the
   worktree (unless ``keep_worktree_on_failure`` is set on a non-completed
   terminal state), audit ``dispatch.token_revoked`` and
   ``dispatch.subprocess_terminated``.

M4a scope: serial only; no merge protocol on completion (worktree branch is
just removed); no timeout enforcement; no heartbeat. M4b adds parallel
dispatch and the ``--ff-only`` merge into canonical/main. M5-flag-flips F3
adds dispatch timeout enforcement via :func:`asyncio.timeout` in
``_await_terminal``; heartbeat watchdog deferred to a future sub-milestone
(no current BACKLOG entry; surface as new entry with concrete trigger if
operationally indicated post-flag-flip). M5-flag-flips F4 closes the
subprocess pipe deadlock surface (``stdout=DEVNULL``; ``stderr=PIPE``
drained continuously to ``<data_dir>/dispatches/<dispatch_id>/stderr.log``
via :mod:`bridge.stderr_drain`); drain task cancellation lives in
``dispatch()``'s finally clause per the F4 guardrails.

Subprocess launching is injectable via the ``subprocess_launcher`` constructor
parameter so tests can simulate a sub-agent that calls ``complete_dispatch``
in-process without invoking ``claude`` for real.
"""

from __future__ import annotations

import asyncio
import contextlib
import os
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Literal, Protocol

import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.canonical import (
    CanonicalClone,
    CanonicalDivergedError,
)
from meridian_orchestrator.bridge.canonical_registry import CanonicalCloneRegistry
from meridian_orchestrator.bridge.launch_env import build_subprocess_env
from meridian_orchestrator.bridge.mcp_config import mcp_config_path, write_mcp_config
from meridian_orchestrator.bridge.stderr_drain import (
    DrainingSubprocess,
    _drain_stream_to_file,
    cancel_drain_task,
    stderr_log_path,
    stdout_log_path,
)
from meridian_orchestrator.bridge.timeouts import ModelString, calculate_timeout
from meridian_orchestrator.bridge.worktree import DispatchWorktree, MergeResult
from meridian_orchestrator.errors import OrchestratorError
from meridian_orchestrator.storage.interface import Storage

logger = structlog.get_logger(__name__)

DispatchRole = Literal["implementer", "tester"]
DispatchTerminalStatus = Literal[
    "completed", "declined", "failed", "timed_out", "cancelled", "compacted"
]
DispatchStatus = Literal["queued", "running"] | DispatchTerminalStatus

_TERMINAL_STATES: frozenset[str] = frozenset(
    {"completed", "declined", "failed", "timed_out", "cancelled", "compacted"}
)

_GRACE_SECONDS = 10

# β-S11-17.5: minimum wall-clock interval between FS-activity probes
# inside a single dispatch's _await_terminal poll loop. Once stage 2's
# elapsed-past-threshold gate fires, the dispatcher walks the worktree
# checking mtimes to distinguish healthy work-in-progress from a true
# silent hang. Without throttling, this O(N) walk would re-fire on
# every poll iteration (default poll_interval_seconds=0.1) until
# activity is confirmed — wasteful daemon load especially under
# concurrent cold-start-hang dispatches. 1.0s probe interval preserves
# early-detection (stage 2 threshold is ≥ a few seconds even in tests
# and ≥ 60s in production; one second of probe-granularity is
# invisible against either) while bounding walk frequency to at most
# 1 probe per second per dispatch until activity flips the cache.
_FS_ACTIVITY_PROBE_INTERVAL_SECONDS = 1.0

# β-S11-17.5: grace window AFTER the no-MCP threshold elapses during
# which the dispatcher continues to re-probe the worktree for activity
# before firing stage 2 terminal. Without a grace window, stage 2
# would terminate on the FIRST negative probe — making the throttle a
# no-op (only one probe per dispatch ever runs) and giving a healthy
# slow-start Implementer no second chance to register activity. With
# the grace window, the dispatcher probes at the throttled rate
# (typically 2 probes in the default 2s grace window — one at
# threshold + 0s, one at threshold + 1s) before classifying as a true
# hang. 2.0s is short enough to be invisible in production
# (180s + 2s = 182s detection vs. legacy 180s) while large enough to
# make the throttle meaningful and to absorb sub-second scheduling
# jitter that could otherwise cause a false-positive stage-2 kill on
# the first poll-iteration after threshold.
_FS_ACTIVITY_GRACE_SECONDS = 2.0

# Process-wide guard: at most one BridgeDispatcher per canonical clone. The
# asyncio Lock on canonical-mutating operations is per-instance, so two
# dispatchers against the same canonical would race on `git worktree add`,
# `merge --ff-only`, and `push origin`. Production has one dispatcher per
# daemon, so this is convention; the guard makes the convention enforceable.
_dispatcher_owned_canonicals: set[Path] = set()


def _now_iso() -> str:
    return datetime.now(tz=UTC).isoformat(timespec="seconds")


def _new_dispatch_id() -> str:
    return uuid.uuid4().hex


class DispatcherError(OrchestratorError):
    """Raised when dispatch lifecycle steps fail before completion."""

    code = "DISPATCH_ERROR"


# β-S11-17 / β-S11-17.5 cold-start hang classification ---------------------
#
# Stages of subprocess hang surfaced by the dispatcher's early-detection
# thresholds. Used in:
# - audit event ``dispatch.cold_start_hang_detected`` (terminal action)
# - audit event ``dispatch.cold_start_hang_warning`` (warn-only)
# - ``dispatches.notes`` text on terminal cold-start-hang dispatches
# - PM/Engineer auto-resolve classification (β-S11-12 enum extension; see
#   ``config/agents/pm.yaml`` § Auto-resolve discipline for the closed
#   enum that downstream telemetry packets must match).
#
# Stage ordering reflects the dispatch lifecycle:
# 1. ``pre_subprocess_started``: launcher coroutine did not return within
#    ``cold_start_no_subprocess_seconds``. No subprocess pid recorded; no
#    worktree work observable; the dispatch row is still
#    ``status='queued'`` at detection time. Terminal action.
# 2. ``pre_first_mcp_activity``: subprocess launched but did not issue any
#    authenticated MCP tool call within
#    ``cold_start_no_mcp_activity_seconds``. ``last_tool_called`` is NULL
#    at detection time. β-S11-17.5: secondary filesystem-activity probe
#    distinguishes a true hang (no MCP AND no worktree writes — Slice 5
#    ``84f98435``/``5da62138``/``95ecbfc5`` shape) from a healthy
#    Implementer/Tester that's doing Read+Edit+Bash work without
#    intermediate MCP calls (``complete_dispatch`` is typically the only
#    MCP call, 14–16 minutes in). Terminal only when BOTH signals are
#    absent. Worktree force-preserved for forensic review on terminal.
# 3. ``pre_filesystem_activity``: subprocess made at least one MCP call
#    (``last_tool_called`` is non-NULL) but ``last_activity_at`` has not
#    advanced within ``cold_start_no_useful_activity_seconds``. Warn-only
#    — dispatcher emits one ``dispatch.cold_start_hang_warning`` audit
#    per dispatch; Engineer-side retry policy decides whether to
#    terminate or wait.
ColdStartHangStage = Literal[
    "pre_subprocess_started",
    "pre_first_mcp_activity",
    "pre_filesystem_activity",
]


# β-S11-17.5 FS-activity probe states:
# - "present": at least one non-.git file inside the worktree has an mtime
#   strictly newer than the dispatch's started_at. The subprocess is
#   doing real work (Edit/Write/Bash output); stage 2 is suppressed.
# - "none": probe ran and found no fresh mtimes. Combined with
#   ``last_tool_called IS NULL`` past threshold, this is a true hang.
# - "not_probed": probe was not invoked for this classification (stage 1,
#   stage 3, or stage 2 short-circuited before threshold).
ColdStartHangFsActivity = Literal["present", "none", "not_probed"]


@dataclass(frozen=True)
class ColdStartHangClassification:
    """Per-dispatch cold-start hang state at detection time.

    ``stage``: which threshold was breached.
    ``elapsed_seconds``: elapsed time since the lifecycle anchor relevant
    to this stage (issued_at for stage 1, started_at for stage 2,
    last_activity_at for stage 3). Integer-rounded for audit readability.
    ``is_terminal``: True for stages 1+2 (dispatch is failed); False for
    stage 3 (warn-only).
    ``fs_activity`` (β-S11-17.5): outcome of the worktree filesystem
    probe. "present" means stage 2 was suppressed (returned via separate
    code path — not constructed here). "none" means stage 2 fired
    terminally despite the probe (true hang). "not_probed" is the
    default for stage 1, stage 3, and any classification constructed
    outside the production code path (tests, future stages).
    """

    stage: ColdStartHangStage
    elapsed_seconds: int
    is_terminal: bool
    fs_activity: ColdStartHangFsActivity = "not_probed"


@dataclass(frozen=True)
class _ColdStartHangThresholds:
    """Carry the three per-dispatch hang-detection thresholds together.

    Decoupled from ``config.Settings`` so the dispatcher's test fixtures
    can construct lightweight thresholds without instantiating full
    Settings (which require ``MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY``
    via the engineer-supervisor validator).
    """

    no_subprocess_seconds: float = 60.0
    no_mcp_activity_seconds: float = 180.0
    no_useful_activity_seconds: float = 600.0


def _has_worktree_activity_since(
    worktree: Path, since: datetime
) -> bool:
    """β-S11-17.5: probe whether any non-.git file under ``worktree`` has
    an mtime strictly newer than ``since``.

    Used by stage 2 of the cold-start hang classifier to distinguish a
    true silent hang (no MCP call AND no worktree writes — Slice 5
    failure shape) from a healthy Implementer/Tester subprocess that's
    doing Read+Edit+Bash work without intermediate MCP calls. The latter
    is the dominant shape: ``complete_dispatch`` is typically the only
    MCP call a dispatched sub-agent makes, fired 14–16 minutes after
    subprocess start; the original 180s stage-2 threshold would have
    killed both successful Slice 4 dispatches as false positives had it
    been active at the time.

    Best-effort: returns False on missing path, permission errors, or
    OS-level walk failures. The decision in
    ``_check_cold_start_hang_classification`` is conservative — when in
    doubt (no activity probed, walk errored, ambiguous mtime), we
    classify as no-activity and let stage 2 fire terminally. A genuine
    hang failing to be detected because the probe crashed is the lesser
    risk; an actively-working dispatch being killed because the probe
    couldn't decide is the costly one. Both call sites accept this
    tradeoff by passing the result as a single boolean.

    Skips ``.git/`` to avoid relying on git-internal housekeeping mtimes
    (``git status``/``diff`` invocations from the subprocess update
    ``.git/index`` even when no production code changed). The
    Implementer's primary work — Read/Edit/Write/Bash on source files —
    always touches non-.git mtimes, so skipping .git is a conservative
    signal without false negatives.

    The threshold comparison is ``mtime > since_ts`` (strict). Worktree
    provisioning writes happen BEFORE ``started_at`` is recorded (per
    ``dispatch()`` lifecycle ordering: ``ensure_provisioned`` → launcher
    → UPDATE started_at), so provisioning artifacts (.git pointer,
    mcp_config, README, src/ files) always have mtimes ≤ started_at and
    are correctly filtered out. Filesystem timer resolution on Windows
    (NTFS, 15.6ms by default) is well below the 180s+ threshold window
    so the strict inequality is safe.
    """
    try:
        since_ts = since.timestamp()
    except (ValueError, OSError):  # pragma: no cover
        return False
    stack: list[Path] = [worktree]
    while stack:
        current = stack.pop()
        try:
            with os.scandir(current) as it:
                for entry in it:
                    name = entry.name
                    if name == ".git":
                        continue
                    try:
                        is_dir = entry.is_dir(follow_symlinks=False)
                    except OSError:
                        continue
                    if is_dir:
                        stack.append(Path(entry.path))
                        continue
                    try:
                        st = entry.stat(follow_symlinks=False)
                    except OSError:
                        continue
                    if st.st_mtime > since_ts:
                        return True
        except (OSError, PermissionError):
            continue
    return False


def _check_cold_start_hang_classification(
    row: dict[str, Any],
    issued_at: datetime,
    now: datetime,
    thresholds: _ColdStartHangThresholds,
    *,
    has_worktree_activity: bool = False,
) -> ColdStartHangClassification | None:
    """Return a classification if the dispatch row shows a cold-start
    hang at ``now``; ``None`` otherwise.

    Called from inside ``BridgeDispatcher._await_terminal``'s poll loop.
    ``row`` must contain ``started_at``, ``last_tool_called``,
    ``last_activity_at`` — pulled via the dispatch row SELECT each
    iteration. ``issued_at`` is the dispatch's row-insertion timestamp,
    sourced once at the top of ``_await_terminal`` (constant across the
    poll loop).

    Stage decision rules:

    - ``started_at`` is None AND ``now - issued_at >= no_subprocess_seconds``
      → stage 1 (``pre_subprocess_started``), terminal.
    - ``started_at`` is set AND ``last_tool_called`` is None AND
      ``now - started_at >= no_mcp_activity_seconds`` → stage 2
      (``pre_first_mcp_activity``):
        * if ``has_worktree_activity`` is True (β-S11-17.5): SUPPRESS
          — return ``None``. The subprocess is alive and editing files
          even though no MCP call has landed yet. Continue polling.
        * else: terminal classification with ``fs_activity="none"``.
          Both signals absent — true hang shape.
    - ``last_tool_called`` is set AND ``last_activity_at`` is set AND
      ``now - last_activity_at >= no_useful_activity_seconds`` → stage 3
      (``pre_filesystem_activity``), warn-only.

    Stage 1 is mostly defensive — in the current production code path,
    ``_launcher(ctx)`` is awaited in ``dispatch()`` BEFORE
    ``_await_terminal`` is called, so ``started_at`` is always non-None
    by the time this helper runs. Stage 1 is therefore detected via
    ``asyncio.wait_for(self._launcher(ctx), timeout=...)`` in
    ``dispatch()`` and not here. Kept in this helper for completeness in
    case future refactors move the launcher call inside the poll loop.

    β-S11-17.5: the ``has_worktree_activity`` kwarg is the *caller's*
    decision (the dispatcher's poll loop calls
    :func:`_has_worktree_activity_since` lazily, only when the stage-2
    elapsed condition fires, and caches the boolean across the
    remainder of the dispatch via a poll-loop-local flag). This keeps
    the helper a pure function for unit testing and decouples the
    classification rules from the filesystem-probe implementation.
    """
    started_at_raw = row.get("started_at")

    # Stage 1: no subprocess_started yet.
    if started_at_raw is None:
        elapsed = (now - issued_at).total_seconds()
        if elapsed >= thresholds.no_subprocess_seconds:
            return ColdStartHangClassification(
                stage="pre_subprocess_started",
                elapsed_seconds=int(elapsed),
                is_terminal=True,
                fs_activity="not_probed",
            )
        return None

    last_tool_called = row.get("last_tool_called")
    started_at = datetime.fromisoformat(started_at_raw)

    # Stage 2: subprocess started but no first MCP activity.
    if last_tool_called is None:
        elapsed = (now - started_at).total_seconds()
        if elapsed >= thresholds.no_mcp_activity_seconds:
            if has_worktree_activity:
                # β-S11-17.5 recalibration: the subprocess is alive and
                # editing files. No MCP call yet is the normal shape for
                # an Implementer/Tester whose only MCP interaction is
                # ``complete_dispatch`` at end-of-work. Not a hang;
                # suppress and continue polling.
                return None
            # β-S11-17.5 grace window: the dispatcher re-probes the
            # worktree at the throttled rate
            # (_FS_ACTIVITY_PROBE_INTERVAL_SECONDS) during the grace.
            # Without the grace, stage 2 would terminate on the first
            # negative probe — making the throttle a no-op and giving
            # a slow-start Implementer no second chance to register
            # activity. Terminal only fires after the grace expires
            # AND every probe in the window came back negative.
            if elapsed < (
                thresholds.no_mcp_activity_seconds
                + _FS_ACTIVITY_GRACE_SECONDS
            ):
                return None
            return ColdStartHangClassification(
                stage="pre_first_mcp_activity",
                elapsed_seconds=int(elapsed),
                is_terminal=True,
                fs_activity="none",
            )
        return None

    # Stage 3: MCP activity present but last_activity_at stale.
    last_activity_raw = row.get("last_activity_at")
    if last_activity_raw is None:
        # last_tool_called set but last_activity_at not — race window
        # between MCP-call body and middleware UPDATE. Skip; next poll
        # will catch a real stall.
        return None
    last_activity_at = datetime.fromisoformat(last_activity_raw)
    elapsed = (now - last_activity_at).total_seconds()
    if elapsed >= thresholds.no_useful_activity_seconds:
        return ColdStartHangClassification(
            stage="pre_filesystem_activity",
            elapsed_seconds=int(elapsed),
            is_terminal=False,
            fs_activity="not_probed",
        )
    return None


@dataclass(frozen=True)
class DispatchLaunchContext:
    """The information a subprocess launcher needs to start a sub-agent."""

    dispatch_id: str
    role: DispatchRole
    prompt: str
    working_directory: Path
    token: str
    # Effort level for the claude subprocess invocation per
    # M5-continuous-pm-driver commit 5 effort plumbing. Resolved at
    # dispatch time from the registry's AgentConfig.effort for the
    # role (per F6.2 Q3 Reading A: kwarg-from-registry resolution
    # mirrors model_tier_default's pattern). Passed to the launcher
    # which threads it into the `claude --effort <level>` subprocess
    # flag. Phase 1 values per orchestrator-utilization-addendum:
    # Implementer xhigh, Tester high.
    effort: str = "high"
    # M5-acceptance commit 2 launcher-wiring (Finding A closure): the
    # dispatched sub-agent's behavioral discipline (179-line
    # tester.yaml.system_prompt; 109-line implementer.yaml.system_prompt)
    # is loaded into the subprocess via ``--append-system-prompt``
    # immediately before the ``-p <prompt>`` positional. Empty default
    # so non-dispatcher callers (test fixtures constructing
    # DispatchLaunchContext directly) don't silently inject a flag;
    # the launcher conditionally inserts ``--append-system-prompt
    # <content>`` only when ``system_prompt`` is non-empty. Production
    # path: ``tools/dispatch.py`` resolves from
    # ``AgentConfig.system_prompt`` via the registry, mirrors the
    # ``effort`` plumbing pattern.
    system_prompt: str = ""
    # M5-acceptance commit 3 launcher-wiring: subprocess-level tool
    # allowlist passed to ``claude --allowedTools <tools>`` so the
    # dispatched subprocess can invoke MCP tools + outside-cwd reads
    # + non-standard Bash commands (verified empirically 2026-05-08:
    # ``claude --print`` mode fails closed on these by default).
    # Empty-list default so test fixtures constructing
    # DispatchLaunchContext directly don't silently inject a flag with
    # empty content; the launcher conditionally inserts
    # ``--allowedTools <space-joined>`` only when the list is non-empty.
    # Production path: ``tools/dispatch.py:_resolve_allowed_tools_for_role``
    # combines ``AgentConfig.allowed_tools`` (built-in tools + non-
    # orchestrator MCP tools) with prefixed orchestrator MCP tools
    # derived from ``AgentConfig.mcp_tools_allowed`` per Mike's D1
    # disposition A1 (single-source-of-truth at launcher composition
    # time). Mirrors ``system_prompt`` and ``effort`` plumbing patterns.
    allowed_tools: list[str] = field(default_factory=list)
    env_extras: dict[str, str] = field(default_factory=dict)
    # M5-flag-flips F4 stderr drain destination root. The launcher
    # writes per-dispatch stderr to ``<dispatches_root>/<dispatch_id>/
    # stderr.log`` for operator post-mortem on uncaught subprocess
    # crashes. ``None`` disables the drain (launcher uses
    # ``stderr=DEVNULL``); production main.py always wires a real
    # path via ``Settings.dispatches_root``. ``None`` is the
    # test-friendly default per ``feedback_optional_dependency_in_helpers``
    # — the 11 integration test fixtures that construct dispatcher with
    # custom ``subprocess_launcher`` callables don't need to grow a new
    # path argument they wouldn't use.
    dispatches_root: Path | None = None
    # Per-dispatch JSON config at ``<dispatches_root>/<dispatch_id>/
    # mcp_config.json`` containing the orchestrator HTTP MCP entry with
    # ``Authorization: Bearer <token>`` header + (tester only) Playwright
    # stdio entry. Launcher emits ``--mcp-config <path> --strict-mcp-config``
    # so the subprocess uses ONLY this config; user-scope and local-scope
    # registrations cannot leak in. ``None`` default so non-dispatcher
    # callers (test fixtures with custom ``subprocess_launcher`` injections)
    # don't silently inject the flags; the launcher conditionally emits
    # both flags only when the path is non-None. Production path:
    # ``BridgeDispatcher.dispatch`` calls
    # :func:`bridge.mcp_config.write_mcp_config` to materialize the JSON
    # before launcher invocation, then threads the path here.
    mcp_config_path: Path | None = None


class SubprocessHandle(Protocol):
    """Minimal contract for a launched dispatch subprocess.

    The default :func:`asyncio.subprocess.Process` already satisfies this
    interface; tests inject a fake that exposes the same attributes.
    """

    @property
    def pid(self) -> int | None: ...

    @property
    def returncode(self) -> int | None: ...

    async def wait(self) -> int: ...

    def terminate(self) -> None: ...

    def kill(self) -> None: ...


SubprocessLauncher = Callable[[DispatchLaunchContext], Awaitable[SubprocessHandle]]


@dataclass(frozen=True)
class DispatchOutcome:
    """The final state of a dispatch lifecycle.

    Mirrors the subset of the ``dispatches`` row a caller (Engineer's
    ``get_dispatch_result``, the test harness) needs to drive its own state
    progression.

    ``merge_conflict_files`` is populated when an Implementer dispatch
    completed cleanly on its branch but the ``--ff-only`` merge into
    canonical/main failed (M4b). PM uses this list to scope re-dispatch
    decisions; the dispatch's ``status`` remains ``completed``.
    """

    dispatch_id: str
    status: DispatchTerminalStatus
    commit_hash: str | None = None
    decline_reason: str | None = None
    test_results: dict[str, Any] | None = None
    queue_items_raised: list[str] = field(default_factory=list)
    artifacts: list[str] = field(default_factory=list)
    notes: str | None = None
    actual_touched_files: list[str] = field(default_factory=list)
    merge_conflict_files: list[str] = field(default_factory=list)


async def _default_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
    """Default subprocess launcher: invoke ``claude`` with the prompt and env.

    Parametrically distinct from the test fake — production launches the real
    Claude Code subprocess; tests inject a fake that simulates ``complete_dispatch``
    being called in-process.

    Note: this default presumes ``claude`` is on PATH on the daemon host. M2's
    operator setup and the M7 service install ensure this; the spec has a
    BACKLOG item for an explicit configurable path.

    Env construction routes through :func:`bridge.launch_env.build_subprocess_env`
    to strip Anthropic credential env vars per #12352. ``allow_api_key`` is
    not exposed here at M5-record-api-call (sub-agent dispatches always
    inherit subscription auth at this sub-milestone); M5-flag-flips will
    revisit if explicit overflow per-dispatch becomes needed.

    M5-flag-flips F4 + β-S11-17.5 subprocess output handling:
    ``stderr=PIPE`` and ``stdout=PIPE`` (β-S11-17.5) both drained
    continuously to per-dispatch log files under
    ``<dispatches_root>/<dispatch_id>/{stderr,stdout}.log``. Pre-β-S11-17.5
    stdout was ``DEVNULL`` (legacy: structured outcomes flow via
    ``complete_dispatch``, stdout was treated as operationally noise);
    the Slice 5 cold-start hang diagnostic showed that discarded stdout
    is exactly the forensic surface needed when Claude CLI hangs silently
    at startup, so β-S11-17.5 captures both streams. When
    ``ctx.dispatches_root`` is None (e.g., test fixtures with custom
    ``subprocess_launcher`` injection that don't wire the path), each
    stream independently falls back to DEVNULL — no drain task spawned.
    The wrapper :class:`DrainingSubprocess` exposes both drain tasks for
    cancellation in ``dispatch()``'s existing finally clause per F4
    guardrail #5.
    """
    env = build_subprocess_env(token=ctx.token, dispatch_id=ctx.dispatch_id)
    env.update(ctx.env_extras)

    # F4 guardrail #3 + β-S11-17.5: create the per-dispatch directory
    # before subprocess launch. Helpers handle OSError best-effort per
    # F4 guardrail #4 — returns None if mkdir fails, launcher falls back
    # to DEVNULL for that stream. stderr and stdout share the same
    # parent directory; calling both helpers is idempotent (second mkdir
    # is a no-op).
    stderr_path: Path | None = None
    stdout_path: Path | None = None
    if ctx.dispatches_root is not None:
        stderr_path = stderr_log_path(ctx.dispatches_root, ctx.dispatch_id)
        stdout_path = stdout_log_path(ctx.dispatches_root, ctx.dispatch_id)
    stderr_kwarg = (
        asyncio.subprocess.PIPE
        if stderr_path is not None
        else asyncio.subprocess.DEVNULL
    )
    stdout_kwarg = (
        asyncio.subprocess.PIPE
        if stdout_path is not None
        else asyncio.subprocess.DEVNULL
    )

    # CLI flag literals verified via `claude --help`:
    # - `--effort <level>` (2026-05-05): low/medium/high/xhigh/max
    # - `--append-system-prompt <prompt>` (2026-05-08, commit 2):
    #   appends to Claude Code's default system prompt
    # - `--allowedTools <tools...>` (2026-05-08, commit 3): comma- or
    #   space-separated list of tool names; example:
    #   `"Bash(git *) Edit"`. Single argv element with space-separated
    #   tool names — supports B1 unrestricted Bash disposition.
    # - `--mcp-config <configs...>` (2026-05-08, commit 3.5): "Load
    #   MCP servers from JSON files or strings". `--strict-mcp-config`
    #   "Only use MCP servers from --mcp-config, ignoring all other
    #   MCP configurations" — hermetic semantic verified empirically
    #   2026-05-08 (sentinel-at-user-scope vs. empty-config probe).
    #
    # Argv shape (Option A ordering): ("claude", "--effort", <level>,
    # ["--append-system-prompt", <sp>], ["--allowedTools", <space-joined>],
    # ["--mcp-config", <path>, "--strict-mcp-config"], "-p", <prompt>).
    # Each conditional block is omitted entirely when its source field
    # is empty/None so non-dispatcher callers (test fixtures with default
    # DispatchLaunchContext) don't silently extend argv. Order keeps
    # `-p <prompt>` last (positional adjacency); flags compose freely
    # before the positional per `claude --help`.
    argv: list[str] = ["claude", "--effort", ctx.effort]
    if ctx.system_prompt:
        argv.extend(["--append-system-prompt", ctx.system_prompt])
    if ctx.allowed_tools:
        argv.extend(["--allowedTools", " ".join(ctx.allowed_tools)])
    if ctx.mcp_config_path is not None:
        argv.extend(
            ["--mcp-config", str(ctx.mcp_config_path), "--strict-mcp-config"]
        )
    argv.extend(["-p", ctx.prompt])
    proc = await asyncio.create_subprocess_exec(
        *argv,
        cwd=str(ctx.working_directory),
        env=env,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=stdout_kwarg,
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
    stdout_drain_task: asyncio.Task[None] | None = None
    if stdout_path is not None:
        stdout_drain_task = asyncio.create_task(
            _drain_stream_to_file(
                proc.stdout, stdout_path, dispatch_id=ctx.dispatch_id
            ),
            name=f"stdout-drain-{ctx.dispatch_id}",
        )

    return DrainingSubprocess(
        proc=proc,
        drain_task=drain_task,
        stdout_drain_task=stdout_drain_task,
    )


class BridgeDispatcher:
    """Owner of per-dispatch lifecycle for Engineer-driven sub-agents.

    A single instance per daemon. Constructed in the daemon lifespan via
    the async factory :meth:`create`, which threads the
    :class:`CanonicalCloneRegistry` through and registers the default
    canonical's path in the process-wide owned-canonicals guard. Slice 2
    of M5-canonical-clone-mgmt: the dispatcher holds the registry rather
    than a single :class:`CanonicalClone` and resolves the canonical
    per-dispatch (defaulting to 'default' until Slice 4 wires
    per-dispatch routing through ``dispatches.canonical_name``).

    ``subprocess_launcher`` defaults to :func:`_default_launcher`; tests
    inject a fake for in-process simulation.
    """

    def __init__(
        self,
        *,
        storage: Storage,
        token_store: TokenStore,
        canonical_registry: CanonicalCloneRegistry,
        worktrees_root: Path,
        subprocess_launcher: SubprocessLauncher | None = None,
        poll_interval_seconds: float = 0.1,
        push_after_merge: bool = True,
        dispatches_root: Path | None = None,
        daemon_url: str | None = None,
        cold_start_no_subprocess_seconds: float = 60.0,
        cold_start_no_mcp_activity_seconds: float = 180.0,
        cold_start_no_useful_activity_seconds: float = 600.0,
    ) -> None:
        self._storage = storage
        self._token_store = token_store
        self._canonical_registry = canonical_registry
        self._worktrees_root = worktrees_root
        # M5-flag-flips F4: per-dispatch stderr log root. Threaded to the
        # default launcher via DispatchLaunchContext.dispatches_root.
        # Optional with None default to keep the 11 integration test
        # fixtures that inject custom subprocess_launcher callables free
        # from path-wiring churn (per feedback_optional_dependency_in_helpers
        # precedent). main.py wires settings.dispatches_root explicitly.
        self._dispatches_root = dispatches_root
        # Orchestrator daemon URL embedded into per-dispatch mcp_config.json's
        # meridian-orchestrator HTTP MCP entry as the ``url`` field.
        # Resolved from settings.mcp_host + settings.mcp_port at main.py
        # construction time. ``None`` default + None-handling at
        # dispatch-time preserves the test-fixture path: when daemon_url
        # is None OR dispatches_root is None, the per-dispatch JSON
        # write is skipped and the launcher omits ``--mcp-config``
        # entirely. Production main.py wires both.
        self._daemon_url = daemon_url
        self._launcher: SubprocessLauncher = (
            subprocess_launcher
            if subprocess_launcher is not None
            else _default_launcher
        )
        self._poll = poll_interval_seconds
        self._push_after_merge = push_after_merge
        # Serializes canonical-clone operations across concurrent dispatches:
        # the pre-dispatch fetch+ff-only refresh, and the post-completion
        # merge of the dispatch branch into canonical/main + push to upstream.
        # Per-dispatch subprocess work runs in parallel — only canonical
        # mutation is serialized. Slice 2 of M5-canonical-clone-mgmt keeps
        # this a single lock; Slice 4 introduces per-canonical locks
        # (keyed by canonical name) when concurrent multi-canonical
        # dispatch is enabled.
        self._canonical_lock = asyncio.Lock()

        # β-S11-17 (2026-05-21) cold-start hang detection thresholds. See
        # ``_check_cold_start_hang_classification`` for the stage decision
        # rules. Production wiring: ``main.py`` reads from
        # ``Settings.dispatch_cold_start_*`` and threads through; the
        # constructor defaults match those Settings defaults so
        # ``BridgeDispatcher(... )`` without explicit thresholds runs the
        # production-correct guards.
        self._cold_start_thresholds = _ColdStartHangThresholds(
            no_subprocess_seconds=cold_start_no_subprocess_seconds,
            no_mcp_activity_seconds=cold_start_no_mcp_activity_seconds,
            no_useful_activity_seconds=cold_start_no_useful_activity_seconds,
        )

    @classmethod
    async def create(
        cls,
        *,
        storage: Storage,
        token_store: TokenStore,
        canonical_registry: CanonicalCloneRegistry,
        worktrees_root: Path,
        subprocess_launcher: SubprocessLauncher | None = None,
        poll_interval_seconds: float = 0.1,
        push_after_merge: bool = True,
        dispatches_root: Path | None = None,
        daemon_url: str | None = None,
        cold_start_no_subprocess_seconds: float = 60.0,
        cold_start_no_mcp_activity_seconds: float = 180.0,
        cold_start_no_useful_activity_seconds: float = 600.0,
    ) -> BridgeDispatcher:
        """Async factory: build the dispatcher and register the default
        canonical's path in the process-wide owned-paths guard.

        Constraint #7 of the Slice 2 spec binds the registry surface
        untouched, so the per-call resolution pattern (``dispatch()``
        calls ``await self._canonical_registry.get_default()``) starts
        from an async lookup. ``__init__`` cannot perform that lookup
        because it is synchronous; this factory performs the lookup,
        then registers the resolved path. The plain constructor stores
        the registry only — direct construction skips the guard, so
        production must use ``await BridgeDispatcher.create(...)``.
        """
        instance = cls(
            storage=storage,
            token_store=token_store,
            canonical_registry=canonical_registry,
            worktrees_root=worktrees_root,
            subprocess_launcher=subprocess_launcher,
            poll_interval_seconds=poll_interval_seconds,
            push_after_merge=push_after_merge,
            dispatches_root=dispatches_root,
            daemon_url=daemon_url,
            cold_start_no_subprocess_seconds=cold_start_no_subprocess_seconds,
            cold_start_no_mcp_activity_seconds=cold_start_no_mcp_activity_seconds,
            cold_start_no_useful_activity_seconds=cold_start_no_useful_activity_seconds,
        )
        default = await canonical_registry.get_default()
        if default.path in _dispatcher_owned_canonicals:
            raise RuntimeError(
                "BridgeDispatcher: another dispatcher already targets canonical "
                f"at {default.path}; per-instance asyncio.Lock on canonical "
                "operations would race. Production has one dispatcher per daemon."
            )
        _dispatcher_owned_canonicals.add(default.path)
        return instance

    async def dispatch(
        self,
        *,
        task_id: str,
        role: DispatchRole,
        role_agent_id: str,
        prompt: str,
        spec_path_descriptor: Path | None = None,
        timeout_seconds: int = 300,
        timeout_override_seconds: int | None = None,
        model: ModelString | None = None,
        effort: str = "high",
        keep_worktree_on_failure: bool = False,
        engineer_decisions: list[dict[str, Any]] | None = None,
        actor: str,
        requested_working_directory: str | None = None,
        commit_hash: str | None = None,
        system_prompt: str = "",
        allowed_tools: list[str] | None = None,
        canonical_name: str | None = None,
    ) -> DispatchOutcome:
        """Run the full dispatch lifecycle and return the final outcome.

        ``role_agent_id`` is the Implementer/Tester agent UUID resolved from
        the registry; it goes on the issued token so the subprocess's MCP
        calls resolve to that agent. ``actor`` is the Engineer's agent_id —
        the audit ``actor`` for ``dispatch.issued``.

        ``prompt`` is what gets passed to ``claude -p``. The Implementer
        handler reads it from ``DispatchImplementerInput.spec_path``; the
        Tester handler uses ``DispatchTesterInput.assertion_spec`` directly.
        ``spec_path_descriptor`` is stored on the dispatch row for audit
        cross-reference; ``None`` is fine (Tester has no spec file).

        ``requested_working_directory`` is recorded as advisory in the
        ``dispatch.issued`` audit detail; the actual subprocess cwd is the
        per-dispatch worktree the daemon provisions off ``substrate-canonical``.

        ``model`` is the canonical model string for the dispatched role
        (e.g., ``claude-sonnet-4-6`` for implementer/tester at default tier).
        When provided AND ``timeout_override_seconds`` is None, the
        dispatcher reads ``tasks.estimated_tokens`` from storage and computes
        the hard timeout via :func:`bridge.timeouts.calculate_timeout`.
        Precedence: ``timeout_override_seconds`` > ``calculate_timeout(model,
        estimated_tokens)`` > ``timeout_seconds`` (legacy default 300).

        ``commit_hash`` is recorded on the ``dispatches`` row at INSERT time
        per M5-flag-flips F5 — Tester dispatches identify the commit under
        verification up front so PM/Engineer queries surface what's being
        tested before the dispatch terminates. Implementer and supervisor
        lifecycle paths pass ``None`` (default); the column starts NULL and
        is populated when the Implementer's ``complete_dispatch`` reports
        the new commit it composed. F5's ``COALESCE`` shape on
        ``complete_dispatch`` UPDATE preserves the up-front Tester value
        through terminal.

        ``system_prompt`` is the dispatched sub-agent's behavioral discipline
        (yaml.system_prompt for the implementer/tester role) loaded into the
        subprocess via ``--append-system-prompt`` per M5-acceptance commit 2
        (Finding A closure). Empty default means the launcher omits the flag
        entirely — preserves the test-fixture path where callers construct
        BridgeDispatcher with custom subprocess_launcher and don't thread
        registry-resolved prompts. Production path: ``tools/dispatch.py``
        ``dispatch_implementer`` / ``dispatch_tester`` resolve from
        ``AgentConfig.system_prompt`` via the registry and pass through.

        ``allowed_tools`` is the subprocess-level tool allowlist passed to
        ``claude --allowedTools`` per M5-acceptance commit 3 (closes the
        conditional permission-gating gap surfaced empirically at prep
        cycle: ``claude --print`` mode fails closed on MCP tool invocations
        + outside-cwd reads + non-standard Bash commands). ``None`` default
        coerces to empty list and the launcher omits the flag entirely —
        same test-fixture-friendly fallback as ``system_prompt``.
        Production path: ``tools/dispatch.py:_resolve_allowed_tools_for_role``
        combines yaml ``allowed_tools`` + prefixed ``mcp_tools_allowed``
        per Mike's D1 disposition A1 (single-source-of-truth at launcher
        composition time, not yaml duplication).
        """
        if role not in ("implementer", "tester"):
            raise DispatcherError(f"unsupported role for dispatch: {role!r}")

        # Slice 4: resolve the canonical per-dispatch via the registry,
        # routed by the optional ``canonical_name`` kwarg. ``None``
        # resolves to the default canonical (preserves pre-Slice-4
        # behavior for every existing caller); a non-None name resolves
        # via ``get_by_name`` and propagates ``CanonicalNotFoundError``
        # verbatim on unknown names (Ratification #3 — no try/except
        # wrap; the resolver fails BEFORE any dispatch row insert,
        # token issuance, or worktree provision, so nothing to clean
        # up). ``resolved_canonical_name`` captures the explicit literal
        # for INSERT + audit threading per Ratification #4 (no reliance
        # on the migration-0014 column DEFAULT).
        canonical = (
            await self._canonical_registry.get_default()
            if canonical_name is None
            else await self._canonical_registry.get_by_name(canonical_name)
        )
        resolved_canonical_name = (
            canonical_name if canonical_name is not None else "default"
        )

        dispatch_id = _new_dispatch_id()
        worktree = DispatchWorktree(
            canonical=canonical,
            dispatch_id=dispatch_id,
            worktrees_root=self._worktrees_root,
            canonical_name=resolved_canonical_name,
        )

        # Timeout precedence: explicit override > calculate_timeout(model,
        # estimated_tokens) > legacy timeout_seconds default. The model-
        # based path reads estimated_tokens from storage rather than
        # asking the caller — keeps the timeouts.py dependency localized
        # in the dispatcher.
        if timeout_override_seconds is not None:
            effective_timeout = timeout_override_seconds
        elif model is not None:
            task_row = await self._storage.fetch_one(
                "SELECT estimated_tokens FROM tasks WHERE id = ?", (task_id,)
            )
            estimated = (
                int(task_row["estimated_tokens"])
                if task_row is not None and task_row["estimated_tokens"] is not None
                else None
            )
            effective_timeout = calculate_timeout(
                model=model, estimated_tokens=estimated
            )
        else:
            effective_timeout = timeout_seconds

        spec_path_str = str(spec_path_descriptor) if spec_path_descriptor else None
        await self._insert_dispatch_row(
            dispatch_id=dispatch_id,
            task_id=task_id,
            role=role,
            working_directory=str(worktree.path),
            spec_path=spec_path_str,
            timeout_seconds=effective_timeout,
            timeout_override_seconds=timeout_override_seconds,
            keep_worktree_on_failure=keep_worktree_on_failure,
            engineer_decisions=engineer_decisions or [],
            commit_hash=commit_hash,
            canonical_name=resolved_canonical_name,
        )
        await write_audit(
            self._storage,
            actor=actor,
            action="dispatch.issued",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "task_id": task_id,
                "role": role,
                "working_directory": str(worktree.path),
                "spec_path": spec_path_str,
                "timeout_seconds": effective_timeout,
                "engineer_decisions": engineer_decisions or [],
                "requested_working_directory": requested_working_directory,
                "canonical_name": resolved_canonical_name,
            },
        )

        issued_token: IssuedToken | None = None
        proc: SubprocessHandle | None = None
        terminate_reason = "completed"
        try:
            # β-S11-5/6 fix: indexed=False for high-churn per-dispatch tokens
            # so the keyring `_index` aggregator doesn't grow unbounded and
            # exceed the underlying backend's per-credential blob limit
            # (Windows Credential Manager: ~2560 bytes; CredWrite returns
            # WinError 1783 above that, boot-blocking the daemon). The
            # per-token record at `token:<id>` is still written, so MCP
            # Bearer auth (lookup) and revocation work normally; the
            # tradeoff is that per-dispatch tokens don't appear in
            # `scripts/list-tokens.py` — their lifecycle is visible via
            # audit_log + dispatches table queries instead.
            issued_token = await self._token_store.issue(
                role_agent_id,
                role,
                f"dispatch:{dispatch_id}",
                indexed=False,
            )
            await self._storage.execute(
                "UPDATE dispatches SET token_id = ? WHERE id = ?",
                (issued_token.record.token_id, dispatch_id),
            )
            await write_audit(
                self._storage,
                actor="system",
                action="dispatch.token_created",
                target=dispatch_id,
                details={
                    "dispatch_id": dispatch_id,
                    "token_id": issued_token.record.token_id,
                },
            )

            try:
                # Hold the canonical lock across both fetch_and_fast_forward
                # AND worktree.provision: `git worktree add` writes to the
                # canonical clone's `.git/config`, and on Windows two
                # concurrent provisions race for `.git/config.lock`. Releasing
                # the lock between fetch and provision is what surfaces the
                # race intermittently. Subprocess execution runs in parallel
                # under the lock-free middle of the lifecycle; only canonical-
                # mutating operations are serialized.
                async with self._canonical_lock:
                    await canonical.fetch_and_fast_forward()
                    await worktree.provision()
            except CanonicalDivergedError as exc:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.canonical_diverged",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "canonical_head": await canonical.head_sha(),
                        "error": str(exc),
                        "canonical_name": resolved_canonical_name,
                    },
                    success=False,
                    error_message=str(exc),
                )
                await self._storage.execute(
                    "UPDATE dispatches SET status = 'failed', "
                    "completed_at = ?, notes = ? WHERE id = ?",
                    (
                        _now_iso(),
                        f"canonical diverged before dispatch could begin: {exc}",
                        dispatch_id,
                    ),
                )
                terminate_reason = "failed"
                raise

            # Materialize per-dispatch mcp_config.json with the
            # orchestrator HTTP MCP entry carrying ``Authorization: Bearer
            # <issued_token.token>`` header + (tester only) Playwright
            # stdio entry. Both ``self._dispatches_root`` and
            # ``self._daemon_url`` must be set for the JSON write to fire
            # — when either is None (test-fixture path with custom
            # subprocess_launcher injection), ``mcp_config_path_value``
            # stays None and the launcher omits ``--mcp-config`` entirely.
            # File persists past dispatch terminal state; token revocation
            # in this method's finally clause renders the retained config
            # inert via ``auth.TokenStore.lookup`` returning None for
            # revoked records. Mirrors stderr.log retention convention.
            mcp_config_path_value: Path | None = None
            if (
                self._dispatches_root is not None
                and self._daemon_url is not None
            ):
                cfg_path = mcp_config_path(
                    self._dispatches_root, dispatch_id
                )
                if cfg_path is not None and write_mcp_config(
                    cfg_path,
                    daemon_url=self._daemon_url,
                    token=issued_token.token,
                    role=role,
                ):
                    mcp_config_path_value = cfg_path

            ctx = DispatchLaunchContext(
                dispatch_id=dispatch_id,
                role=role,
                prompt=prompt,
                working_directory=worktree.path,
                token=issued_token.token,
                effort=effort,
                system_prompt=system_prompt,
                allowed_tools=allowed_tools or [],
                dispatches_root=self._dispatches_root,
                mcp_config_path=mcp_config_path_value,
            )
            # β-S11-17 stage 1 cold-start hang: bound the launcher
            # coroutine by ``cold_start_no_subprocess_seconds``. A healthy
            # ``claude-code`` subprocess launches in <5s on the operator's
            # M2 Pro reference machine; the 60s default is conservative
            # against transient OS scheduling / disk contention. A hung
            # launcher (e.g., uv-toolchain wedge, claude-code binary
            # missing, OS-level subprocess-launch failure) used to block
            # the full ``timeout_seconds`` (typically 1800s); now it
            # surfaces as ``cold_start_hang:pre_subprocess_started`` in
            # ``~no_subprocess_seconds`` instead.
            try:
                proc = await asyncio.wait_for(
                    self._launcher(ctx),
                    timeout=self._cold_start_thresholds.no_subprocess_seconds,
                )
            except TimeoutError:
                classification = ColdStartHangClassification(
                    stage="pre_subprocess_started",
                    elapsed_seconds=int(
                        self._cold_start_thresholds.no_subprocess_seconds
                    ),
                    is_terminal=True,
                )
                await self._mark_cold_start_hang_terminal(
                    dispatch_id, classification
                )
                terminate_reason = "failed"
                return await self._build_outcome(dispatch_id)

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
                    "cwd": str(worktree.path),
                },
            )

            outcome = await self._await_terminal(
                dispatch_id,
                proc,
                timeout_seconds=effective_timeout,
                working_directory=worktree.path,
            )
            if outcome.status == "completed" and outcome.commit_hash:
                outcome = await self._merge_and_record(
                    dispatch_id=dispatch_id,
                    outcome=outcome,
                    worktree=worktree,
                    canonical=canonical,
                    canonical_name=resolved_canonical_name,
                )
            terminate_reason = outcome.status
            return outcome
        finally:
            if issued_token is not None:
                revoked = await self._token_store.revoke(
                    issued_token.record.token_id
                )
                if revoked:
                    await write_audit(
                        self._storage,
                        actor="system",
                        action="dispatch.token_revoked",
                        target=dispatch_id,
                        details={
                            "dispatch_id": dispatch_id,
                            "token_id": issued_token.record.token_id,
                            "reason": terminate_reason,
                        },
                    )

            exit_code: int | None = None
            signal_used: str | None = None
            total_elapsed: int | None = None
            started = await self._storage.fetch_one(
                "SELECT started_at FROM dispatches WHERE id = ?", (dispatch_id,)
            )
            if proc is not None:
                exit_code, signal_used = await self._terminate_subprocess(proc)
                # M5-flag-flips F4 guardrail #5: drain task cancellation
                # lives in the existing dispatch() finally clause, not a
                # parallel lifecycle. Helper is no-op for non-DrainingSubprocess
                # handles (test fakes) and for already-completed drain tasks
                # (subprocess closed stderr cleanly → drain hit EOF).
                await cancel_drain_task(proc)
                if started is not None and started["started_at"] is not None:
                    try:
                        delta = datetime.now(tz=UTC) - datetime.fromisoformat(
                            started["started_at"]
                        )
                        total_elapsed = int(delta.total_seconds())
                    except ValueError:
                        total_elapsed = None

            # β-S11-17: stage 2 cold-start hangs force-preserve the
            # worktree for forensic review regardless of the caller's
            # ``keep_worktree_on_failure`` flag. Stage 1 hangs don't —
            # the worktree was provisioned but nothing wrote to it
            # (no subprocess launched), so retention has zero diagnostic
            # value. Stage 3 hangs are warn-only and don't reach this
            # cleanup path (subprocess still alive).
            force_keep_worktree_for_cold_start_hang = (
                notes_for_outcome := await self._storage.fetch_one(
                    "SELECT notes FROM dispatches WHERE id = ?",
                    (dispatch_id,),
                )
            ) is not None and (
                str(notes_for_outcome["notes"] or "").startswith(
                    "cold-start-hang:pre_first_mcp_activity:"
                )
            )
            keep = (
                (
                    keep_worktree_on_failure
                    or force_keep_worktree_for_cold_start_hang
                )
                and terminate_reason in _TERMINAL_STATES
                and terminate_reason != "completed"
            )
            if not keep:
                # cleanup runs `git worktree remove` + `git branch -D`
                # against canonical; both touch canonical's .git/config
                # and would race with another dispatch's lock-protected
                # canonical operations if not serialized through the same
                # lock. Holding the lock here is brief (worktree remove +
                # branch delete are fast).
                async with self._canonical_lock:
                    await worktree.cleanup()

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
                        "total_elapsed_seconds": total_elapsed,
                        "worktree_retained": bool(keep),
                    },
                )

    async def _merge_and_record(
        self,
        *,
        dispatch_id: str,
        outcome: DispatchOutcome,
        worktree: DispatchWorktree,
        canonical: CanonicalClone,
        canonical_name: str,
    ) -> DispatchOutcome:
        """Merge the dispatch branch into canonical and record the result.

        Holds :attr:`_canonical_lock` so concurrent dispatches serialize
        their canonical-mutating operations. Performs a fresh
        ``fetch_and_fast_forward`` (so a parallel dispatch's earlier merge is
        visible) and then attempts ``--ff-only`` of the dispatch branch.

        On successful merge:
        - pushes canonical to upstream if ``push_after_merge`` is set
        - populates ``dispatches.actual_touched_files_json`` from
          ``git diff --name-only <commit>~1 <commit>`` against canonical
        - audits ``dispatch.merged``
        - returns an updated outcome with ``actual_touched_files`` set

        On merge conflict:
        - populates ``dispatches.merge_conflict_files_json`` with paths the
          dispatch touched since divergence
        - audits ``dispatch.merge_conflict``
        - returns an updated outcome whose ``status`` is still ``completed``
          (the work landed on the dispatch branch; the merge surface is a
          separate signal). PM uses ``merge_conflict_files_json`` to scope
          re-dispatch.

        On canonical divergence mid-flight:
        - audits ``dispatch.canonical_diverged``
        - leaves the dispatch row as ``completed`` (the work is on the
          branch) but the upstream catchup is broken; operator recovery.
        """
        import json as _json

        commit_hash = outcome.commit_hash
        if not commit_hash:
            return outcome

        async with self._canonical_lock:
            try:
                await canonical.fetch_and_fast_forward()
            except CanonicalDivergedError as exc:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.canonical_diverged",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "canonical_head": await canonical.head_sha(),
                        "error": str(exc),
                        "canonical_name": canonical_name,
                    },
                    success=False,
                    error_message=str(exc),
                )
                return outcome

            merge: MergeResult = await worktree.merge_into_canonical()

            if not merge.ok:
                conflict_json = _json.dumps(
                    list(merge.conflicting_files), sort_keys=True
                )
                await self._storage.execute(
                    "UPDATE dispatches SET merge_conflict_files_json = ? "
                    "WHERE id = ?",
                    (conflict_json, dispatch_id),
                )
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.merge_conflict",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "branch": worktree.branch,
                        "original_commit_hash": merge.original_commit_hash,
                        "conflicting_files": list(merge.conflicting_files),
                        "reason": merge.reason,
                        "canonical_name": canonical_name,
                    },
                )
                from dataclasses import replace as _replace
                return _replace(
                    outcome,
                    actual_touched_files=[],
                    merge_conflict_files=list(merge.conflicting_files),
                )

            await write_audit(
                self._storage,
                actor="system",
                action="dispatch.merged",
                target=dispatch_id,
                details={
                    "dispatch_id": dispatch_id,
                    "branch": worktree.branch,
                    "original_commit_hash": merge.original_commit_hash,
                    "new_upstream_head": merge.new_head,
                    "canonical_name": canonical_name,
                },
            )

            if self._push_after_merge:
                try:
                    await canonical.push_to_upstream()
                except OrchestratorError as exc:
                    logger.warning(
                        "dispatch.push_failed",
                        dispatch_id=dispatch_id,
                        error=str(exc),
                    )

            await self._storage.execute(
                "UPDATE dispatches SET actual_touched_files_json = ? "
                "WHERE id = ?",
                (_json.dumps(merge.touched_files, sort_keys=True), dispatch_id),
            )

            from dataclasses import replace as _replace
            return _replace(
                outcome, actual_touched_files=list(merge.touched_files)
            )

    async def _insert_dispatch_row(
        self,
        *,
        dispatch_id: str,
        task_id: str,
        role: DispatchRole,
        working_directory: str,
        spec_path: str | None,
        timeout_seconds: int,
        timeout_override_seconds: int | None,
        keep_worktree_on_failure: bool,
        engineer_decisions: list[dict[str, Any]],
        commit_hash: str | None,
        canonical_name: str,
    ) -> None:
        import json as _json

        await self._storage.execute(
            "INSERT INTO dispatches "
            "(id, task_id, role, status, working_directory, spec_path, "
            "timeout_seconds, timeout_override_seconds, keep_worktree_on_failure, "
            "engineer_decisions_json, commit_hash, canonical_name) "
            "VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                dispatch_id,
                task_id,
                role,
                working_directory,
                spec_path,
                timeout_seconds,
                timeout_override_seconds,
                1 if keep_worktree_on_failure else 0,
                _json.dumps(engineer_decisions, sort_keys=True),
                commit_hash,
                canonical_name,
            ),
        )

    async def _await_terminal(
        self,
        dispatch_id: str,
        proc: SubprocessHandle,
        *,
        timeout_seconds: int,
        working_directory: Path | None = None,
    ) -> DispatchOutcome:
        """Loop until the dispatch row reaches a terminal state.

        If the subprocess exits before ``complete_dispatch`` lands a terminal
        state on the row, mark the dispatch ``failed`` with a note. The
        polling loop is bounded by :func:`asyncio.timeout` so a subprocess
        that hangs without writing terminal state and without exiting is
        capped by ``timeout_seconds`` (the dispatcher's effective timeout
        precedence: ``timeout_override_seconds`` > ``calculate_timeout(model,
        estimated_tokens)`` > ``timeout_seconds`` default). On timeout, the
        row is re-read to avoid overwriting a terminal state that landed at
        the deadline boundary; only if still non-terminal is it marked
        ``timed_out``. Subprocess termination is unconditional via the
        surrounding ``dispatch()`` finally — no termination plumbing here.

        β-S11-17 (2026-05-21): at each iteration the row is re-read with
        the cold-start hang detection columns (``started_at``,
        ``last_tool_called``, ``last_activity_at``) and passed through
        :func:`_check_cold_start_hang_classification`. Stage 2 hangs
        (subprocess started, no first MCP activity within
        ``no_mcp_activity_seconds``) terminate the dispatch ~25-30 min
        before the legacy ``timeout_seconds`` would have fired. Stage 3
        hangs (MCP activity present but stale beyond
        ``no_useful_activity_seconds``) emit a single
        ``dispatch.cold_start_hang_warning`` audit per dispatch; the
        subprocess continues running and Engineer-side retry policy
        decides the next action.

        β-S11-17.5 (2026-05-21): stage 2 is recalibrated to also probe
        the dispatch worktree for filesystem activity newer than
        ``started_at``. Healthy Implementer/Tester dispatches commonly
        spend 10-15 minutes on Read+Edit+Bash work before their first
        MCP call (which is typically ``complete_dispatch`` itself), so
        the 180s no-MCP threshold misfires against them. The
        filesystem-activity probe distinguishes that healthy shape from
        a true silent hang (no MCP AND no FS writes — the Slice 5
        reproducer). When activity is observed, stage 2 is suppressed
        for the remainder of this dispatch (cached via the
        ``fs_activity_confirmed`` loop-local flag); when activity is
        absent at the threshold boundary, stage 2 fires terminally
        with ``fs_activity="none"`` recorded in notes + audit.
        """
        # Per-dispatch issued_at and stage-3 warn-once gate. issued_at is
        # constant across the poll loop; SELECT once and cache.
        issued_row = await self._storage.fetch_one(
            "SELECT issued_at FROM dispatches WHERE id = ?", (dispatch_id,)
        )
        if issued_row is None or issued_row["issued_at"] is None:
            raise DispatcherError(
                f"dispatch row {dispatch_id} has no issued_at"
            )
        issued_at = datetime.fromisoformat(str(issued_row["issued_at"]))
        cold_start_warning_emitted = False
        # β-S11-17.5: once the worktree has shown filesystem activity
        # newer than ``fs_probe_since``, the subprocess is doing real
        # work even if no MCP call has landed. Cache the True
        # observation across remaining poll iterations to avoid
        # re-walking the worktree on every poll (FS probe is O(N) over
        # worktree files; cheap but not free at 0.1s poll cadence).
        # Flag never resets to False — activity is monotonic per
        # dispatch lifecycle.
        #
        # ``fs_probe_since`` is taken AS A MICROSECOND-PRECISION
        # SNAPSHOT at _await_terminal entry, NOT read from the DB
        # ``started_at`` column. ``started_at`` is stored
        # seconds-truncated via _now_iso(timespec='seconds'); a
        # worktree-provisioning file written 200ms before
        # _await_terminal's started_at UPDATE would otherwise show as
        # mtime > started_at after truncation, false-positively
        # suppressing stage 2 on a true hang. Local-snapshot is
        # strictly after the launcher returned and the subprocess was
        # spawned, so it's a sound lower bound for "any FS activity
        # caused by the subprocess".
        #
        # β-S11-17.5 throttle-fix (2026-05-22): the SAME snapshot is
        # also used as the in-memory reference for stage-2 elapsed
        # accounting (both the lazy-probe gate below AND the classifier
        # call), replacing the DB-truncated ``started_at`` parse on
        # both sites. Without this alignment, the classifier's elapsed
        # carries a 0–1s forward bias (seconds truncation) while the
        # throttle's ``next_fs_probe_at`` schedule uses microsecond
        # ``datetime.now()``. The mismatched clocks let terminal fire
        # before the next throttled probe gets a chance to observe
        # activity — i.e. a healthy slow-start dispatch with FS
        # activity arriving after the second probe-slot would be killed
        # despite the grace window. Stage-2 elapsed measured from
        # fs_probe_since is on the same wall-clock as the probe
        # schedule, restoring the intended invariant: terminal fires
        # only after every probe inside (threshold + grace) returned
        # negative.
        fs_probe_since = datetime.now(tz=UTC)
        fs_activity_confirmed = False
        # β-S11-17.5 throttle: wall-clock time at which the NEXT FS
        # probe is allowed. ``None`` until the first probe runs (so the
        # very first stage-2-elapsed iteration probes immediately); set
        # to ``now + _FS_ACTIVITY_PROBE_INTERVAL_SECONDS`` after each
        # negative probe so subsequent poll iterations within the
        # window skip the walk entirely. A positive probe sets
        # ``fs_activity_confirmed = True`` which short-circuits the
        # whole gate for the rest of the dispatch lifecycle.
        next_fs_probe_at: datetime | None = None
        try:
            async with asyncio.timeout(timeout_seconds):
                while True:
                    # Poll dispatches row before proc.returncode: subprocess may exit
                    # cleanly after writing terminal state, in which case row is
                    # authoritative and returncode is decoration. Reverse the order
                    # and a clean-exit-after-complete_dispatch race surfaces as a
                    # spurious "failed" with rc=0.
                    row = await self._storage.fetch_one(
                        "SELECT status, started_at, last_tool_called, "
                        "last_activity_at FROM dispatches WHERE id = ?",
                        (dispatch_id,),
                    )
                    if row is None:
                        raise DispatcherError(
                            f"dispatch row {dispatch_id} disappeared during await loop"
                        )
                    status = str(row["status"])
                    if status in _TERMINAL_STATES:
                        return await self._build_outcome(dispatch_id)

                    # β-S11-17.5 lazy FS-activity probe: only walk the
                    # worktree when stage 2 *would* fire terminally,
                    # and only when we haven't already confirmed
                    # activity earlier in this dispatch. Cheap-first
                    # gating (started_at set, no last_tool_called yet,
                    # elapsed ≥ threshold) avoids the walk for the
                    # entire first stage-2-threshold window of every
                    # dispatch.
                    if (
                        not fs_activity_confirmed
                        and working_directory is not None
                        and row["started_at"] is not None
                        and row["last_tool_called"] is None
                    ):
                        now_dt = datetime.now(tz=UTC)
                        # β-S11-17.5 throttle-fix: measure stage-2
                        # elapsed against fs_probe_since (microsecond
                        # in-memory) instead of the seconds-truncated
                        # DB ``started_at``. See the fs_probe_since
                        # comment above for the full rationale.
                        stage2_elapsed = (
                            now_dt - fs_probe_since
                        ).total_seconds()
                        if (
                            stage2_elapsed
                            >= self._cold_start_thresholds.no_mcp_activity_seconds
                            and (
                                next_fs_probe_at is None
                                or now_dt >= next_fs_probe_at
                            )
                        ):
                            if _has_worktree_activity_since(
                                working_directory, fs_probe_since
                            ):
                                fs_activity_confirmed = True
                            else:
                                # β-S11-17.5: schedule the next probe
                                # at least 1s out so the worktree walk
                                # doesn't burn CPU at the poll cadence.
                                # If stage 2 fires terminal on THIS
                                # iteration (no MCP + no FS), the
                                # next-probe slot is irrelevant — the
                                # dispatch is about to be marked
                                # failed and the loop exits.
                                next_fs_probe_at = (
                                    now_dt
                                    + timedelta(
                                        seconds=_FS_ACTIVITY_PROBE_INTERVAL_SECONDS
                                    )
                                )

                    # β-S11-17 / β-S11-17.5 cold-start hang detection.
                    # β-S11-17.5 throttle-fix: override the row's
                    # seconds-truncated ``started_at`` with the
                    # microsecond in-memory snapshot ``fs_probe_since``
                    # for stage-2 elapsed accounting. Without this the
                    # classifier's elapsed lives on a different clock
                    # than the throttle's probe schedule, and terminal
                    # can fire 0–1s before the next throttled probe is
                    # scheduled — killing healthy dispatches whose FS
                    # activity arrives inside the would-be grace
                    # window. Stage 1 (issued_at) and stage 3
                    # (last_activity_at) read different columns and
                    # are unaffected.
                    row_for_classifier = dict(row)
                    if row_for_classifier.get("started_at") is not None:
                        row_for_classifier["started_at"] = (
                            fs_probe_since.isoformat()
                        )
                    classification = _check_cold_start_hang_classification(
                        row_for_classifier,
                        issued_at=issued_at,
                        now=datetime.now(tz=UTC),
                        thresholds=self._cold_start_thresholds,
                        has_worktree_activity=fs_activity_confirmed,
                    )
                    if classification is not None:
                        if classification.is_terminal:
                            await self._mark_cold_start_hang_terminal(
                                dispatch_id, classification
                            )
                            return await self._build_outcome(dispatch_id)
                        elif not cold_start_warning_emitted:
                            await self._emit_cold_start_hang_warning(
                                dispatch_id, classification
                            )
                            cold_start_warning_emitted = True

                    if proc.returncode is not None:
                        # Re-read status before declaring failed: the subprocess can
                        # call complete_dispatch and signal-clean-exit between our
                        # last SELECT and this check, leaving us with a stale
                        # "running" read paired with a fresh non-None returncode.
                        # Without the re-read we'd mark a successful dispatch as
                        # failed because the SELECT happened to land just before
                        # the UPDATE.
                        row = await self._storage.fetch_one(
                            "SELECT status FROM dispatches WHERE id = ?",
                            (dispatch_id,),
                        )
                        if row is not None and str(row["status"]) in _TERMINAL_STATES:
                            return await self._build_outcome(dispatch_id)

                        rc = proc.returncode
                        logger.warning(
                            "dispatch.subprocess_exited_without_complete",
                            dispatch_id=dispatch_id,
                            rc=rc,
                        )
                        await self._storage.execute(
                            "UPDATE dispatches SET status = 'failed', "
                            "completed_at = ?, notes = ? WHERE id = ?",
                            (
                                _now_iso(),
                                f"subprocess exited rc={rc} without complete_dispatch",
                                dispatch_id,
                            ),
                        )
                        return await self._build_outcome(dispatch_id)

                    await asyncio.sleep(self._poll)
        except TimeoutError:
            # Race-protection re-read mirrors the SELECT-before-returncode
            # pattern above (CQ-B Reading A): complete_dispatch may have
            # landed a terminal state at the deadline boundary. Re-check
            # before overwriting; only mark timed_out if still non-terminal.
            # Subprocess termination is handled by dispatch()'s finally
            # via _terminate_subprocess regardless of this return path.
            row = await self._storage.fetch_one(
                "SELECT status FROM dispatches WHERE id = ?", (dispatch_id,)
            )
            if row is not None and str(row["status"]) in _TERMINAL_STATES:
                return await self._build_outcome(dispatch_id)

            logger.warning(
                "dispatch.timed_out",
                dispatch_id=dispatch_id,
                timeout_seconds=timeout_seconds,
            )
            await self._storage.execute(
                "UPDATE dispatches SET status = 'timed_out', "
                "completed_at = ?, notes = ? WHERE id = ?",
                (
                    _now_iso(),
                    (
                        f"dispatch timed out after {timeout_seconds}s "
                        "without complete_dispatch"
                    ),
                    dispatch_id,
                ),
            )
            return await self._build_outcome(dispatch_id)

    async def _mark_cold_start_hang_terminal(
        self,
        dispatch_id: str,
        classification: ColdStartHangClassification,
    ) -> None:
        """β-S11-17 / β-S11-17.5: mark a dispatch as ``failed`` with
        cold-start hang classification embedded in ``notes`` + emit
        ``dispatch.cold_start_hang_detected`` audit event.

        Called from two sites:
        - ``dispatch()`` after the launcher coroutine timed out (stage 1)
        - ``_await_terminal`` polling loop on stage 2 detection

        ``notes`` format:
        - stage 1 / 3: ``cold-start-hang:<stage>:elapsed=<seconds>s``
        - stage 2 (β-S11-17.5): the same prefix with an
          ``:fs_activity=<state>`` suffix appended. The existing prefix
          test ``startswith("cold-start-hang:pre_first_mcp_activity:")``
          still matches. The new suffix records the FS probe outcome
          for forensic correlation with the daemon log and worktree
          contents at termination time.

        The ``cold-start-hang:`` prefix is parsed by ``dispatch()``'s
        finally clause to force-preserve the worktree for stage 2 (stage
        1 has nothing to preserve; the subprocess never ran).
        """
        notes = (
            f"cold-start-hang:{classification.stage}:"
            f"elapsed={classification.elapsed_seconds}s"
        )
        if classification.fs_activity != "not_probed":
            notes = f"{notes}:fs_activity={classification.fs_activity}"
        logger.warning(
            "dispatch.cold_start_hang_detected",
            dispatch_id=dispatch_id,
            stage=classification.stage,
            elapsed_seconds=classification.elapsed_seconds,
            fs_activity=classification.fs_activity,
        )
        await self._storage.execute(
            "UPDATE dispatches SET status = 'failed', "
            "completed_at = ?, notes = ? WHERE id = ?",
            (_now_iso(), notes, dispatch_id),
        )
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.cold_start_hang_detected",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "stage": classification.stage,
                "elapsed_seconds": classification.elapsed_seconds,
                "fs_activity": classification.fs_activity,
            },
        )

    async def _emit_cold_start_hang_warning(
        self,
        dispatch_id: str,
        classification: ColdStartHangClassification,
    ) -> None:
        """β-S11-17: emit a one-shot ``dispatch.cold_start_hang_warning``
        audit event for stage 3 (``pre_filesystem_activity``). No
        terminal action — the subprocess continues running and
        Engineer-side retry policy decides whether to recycle the
        dispatch or wait. The caller (``_await_terminal``) gates re-emit
        via a per-dispatch local flag so the audit row appears at most
        once per dispatch.
        """
        logger.warning(
            "dispatch.cold_start_hang_warning",
            dispatch_id=dispatch_id,
            stage=classification.stage,
            elapsed_seconds=classification.elapsed_seconds,
        )
        await write_audit(
            self._storage,
            actor="system",
            action="dispatch.cold_start_hang_warning",
            target=dispatch_id,
            details={
                "dispatch_id": dispatch_id,
                "stage": classification.stage,
                "elapsed_seconds": classification.elapsed_seconds,
            },
        )

    async def _build_outcome(self, dispatch_id: str) -> DispatchOutcome:
        import json as _json

        row = await self._storage.fetch_one(
            "SELECT status, commit_hash, decline_reason, test_results_json, "
            "queue_items_raised_json, artifacts_json, notes, "
            "actual_touched_files_json, merge_conflict_files_json "
            "FROM dispatches WHERE id = ?",
            (dispatch_id,),
        )
        if row is None:
            raise DispatcherError(f"dispatch row {dispatch_id} not found")

        def _maybe_list(raw: Any) -> list[Any]:
            if raw is None:
                return []
            try:
                return list(_json.loads(raw))
            except (ValueError, TypeError):
                return []

        return DispatchOutcome(
            dispatch_id=dispatch_id,
            status=row["status"],
            commit_hash=row["commit_hash"],
            decline_reason=row["decline_reason"],
            test_results=(
                _json.loads(row["test_results_json"])
                if row["test_results_json"]
                else None
            ),
            queue_items_raised=_maybe_list(row["queue_items_raised_json"]),
            artifacts=_maybe_list(row["artifacts_json"]),
            notes=row["notes"],
            actual_touched_files=_maybe_list(row["actual_touched_files_json"]),
            merge_conflict_files=_maybe_list(row["merge_conflict_files_json"]),
        )

    async def _terminate_subprocess(
        self, proc: SubprocessHandle
    ) -> tuple[int | None, str | None]:
        """SIGTERM with a 10-second grace then SIGKILL. Returns (exit_code, signal).

        Returns immediately if the subprocess has already exited cleanly.
        """
        if proc.returncode is not None:
            return proc.returncode, None
        try:
            proc.terminate()
        except ProcessLookupError:
            return proc.returncode, None
        rc: int | None
        try:
            rc = await asyncio.wait_for(proc.wait(), timeout=_GRACE_SECONDS)
            return rc, "SIGTERM"
        except TimeoutError:
            with contextlib.suppress(ProcessLookupError):
                proc.kill()
            try:
                rc = await asyncio.wait_for(proc.wait(), timeout=_GRACE_SECONDS)
            except TimeoutError:
                rc = proc.returncode
            return rc, "SIGKILL"
