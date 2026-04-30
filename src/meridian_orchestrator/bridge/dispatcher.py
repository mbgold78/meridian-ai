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
dispatch and the ``--ff-only`` merge into canonical/main; M4c adds timeout
and heartbeat watchdog.

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
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal, Protocol

import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.worktree import DispatchWorktree
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


def _now_iso() -> str:
    return datetime.now(tz=UTC).isoformat(timespec="seconds")


def _new_dispatch_id() -> str:
    return uuid.uuid4().hex


class DispatcherError(OrchestratorError):
    """Raised when dispatch lifecycle steps fail before completion."""

    code = "DISPATCH_ERROR"


@dataclass(frozen=True)
class DispatchLaunchContext:
    """The information a subprocess launcher needs to start a sub-agent."""

    dispatch_id: str
    role: DispatchRole
    prompt: str
    working_directory: Path
    token: str
    env_extras: dict[str, str] = field(default_factory=dict)


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


async def _default_launcher(ctx: DispatchLaunchContext) -> SubprocessHandle:
    """Default subprocess launcher: invoke ``claude`` with the prompt and env.

    Parametrically distinct from the test fake — production launches the real
    Claude Code subprocess; tests inject a fake that simulates ``complete_dispatch``
    being called in-process.

    Note: this default presumes ``claude`` is on PATH on the daemon host. M2's
    operator setup and the M7 service install ensure this; the spec has a
    BACKLOG item for an explicit configurable path.
    """
    env = os.environ.copy()
    env["MERIDIAN_ORCHESTRATOR_TOKEN"] = ctx.token
    env["MERIDIAN_ORCHESTRATOR_DISPATCH_ID"] = ctx.dispatch_id
    env.update(ctx.env_extras)
    proc = await asyncio.create_subprocess_exec(
        "claude",
        "-p",
        ctx.prompt,
        cwd=str(ctx.working_directory),
        env=env,
        stdin=asyncio.subprocess.DEVNULL,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    return proc  # asyncio.subprocess.Process satisfies SubprocessHandle


class BridgeDispatcher:
    """Owner of per-dispatch lifecycle for Engineer-driven sub-agents.

    A single instance per daemon. Constructed in the daemon lifespan with the
    canonical clone, the worktrees root, the storage handle, and the token
    store. ``subprocess_launcher`` defaults to :func:`_default_launcher`; tests
    inject a fake for in-process simulation.
    """

    def __init__(
        self,
        *,
        storage: Storage,
        token_store: TokenStore,
        canonical: CanonicalClone,
        worktrees_root: Path,
        subprocess_launcher: SubprocessLauncher | None = None,
        poll_interval_seconds: float = 0.1,
    ) -> None:
        self._storage = storage
        self._token_store = token_store
        self._canonical = canonical
        self._worktrees_root = worktrees_root
        self._launcher: SubprocessLauncher = (
            subprocess_launcher
            if subprocess_launcher is not None
            else _default_launcher
        )
        self._poll = poll_interval_seconds

    async def dispatch(
        self,
        *,
        task_id: str,
        role: DispatchRole,
        role_agent_id: str,
        spec_path: Path,
        timeout_seconds: int = 300,
        timeout_override_seconds: int | None = None,
        keep_worktree_on_failure: bool = False,
        engineer_decisions: list[dict[str, Any]] | None = None,
        actor: str,
        requested_working_directory: str | None = None,
    ) -> DispatchOutcome:
        """Run the full dispatch lifecycle and return the final outcome.

        ``role_agent_id`` is the Implementer/Tester agent UUID resolved from
        the registry; it goes on the issued token so the subprocess's MCP
        calls resolve to that agent. ``actor`` is the Engineer's agent_id —
        the audit ``actor`` for ``dispatch.issued``.

        ``requested_working_directory`` is recorded as advisory in the
        ``dispatch.issued`` audit detail; the actual subprocess cwd is the
        per-dispatch worktree the daemon provisions off ``substrate-canonical``.
        """
        if role not in ("implementer", "tester"):
            raise DispatcherError(f"unsupported role for dispatch: {role!r}")

        dispatch_id = _new_dispatch_id()
        worktree = DispatchWorktree(
            canonical=self._canonical,
            dispatch_id=dispatch_id,
            worktrees_root=self._worktrees_root,
        )
        effective_timeout = timeout_override_seconds or timeout_seconds

        prompt = await asyncio.to_thread(
            spec_path.read_text, encoding="utf-8"
        )

        await self._insert_dispatch_row(
            dispatch_id=dispatch_id,
            task_id=task_id,
            role=role,
            working_directory=str(worktree.path),
            spec_path=str(spec_path),
            timeout_seconds=effective_timeout,
            timeout_override_seconds=timeout_override_seconds,
            keep_worktree_on_failure=keep_worktree_on_failure,
            engineer_decisions=engineer_decisions or [],
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
                "spec_path": str(spec_path),
                "timeout_seconds": effective_timeout,
                "engineer_decisions": engineer_decisions or [],
                "requested_working_directory": requested_working_directory,
            },
        )

        issued_token: IssuedToken | None = None
        proc: SubprocessHandle | None = None
        terminate_reason = "completed"
        try:
            issued_token = await asyncio.to_thread(
                self._token_store.issue,
                role_agent_id,
                role,
                f"dispatch:{dispatch_id}",
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

            await self._canonical.fetch_and_fast_forward()
            await worktree.provision()

            ctx = DispatchLaunchContext(
                dispatch_id=dispatch_id,
                role=role,
                prompt=prompt,
                working_directory=worktree.path,
                token=issued_token.token,
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
                    "cwd": str(worktree.path),
                },
            )

            outcome = await self._await_terminal(dispatch_id, proc)
            terminate_reason = outcome.status
            return outcome
        finally:
            if issued_token is not None:
                revoked = await asyncio.to_thread(
                    self._token_store.revoke, issued_token.record.token_id
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
                if started is not None and started["started_at"] is not None:
                    try:
                        delta = datetime.now(tz=UTC) - datetime.fromisoformat(
                            started["started_at"]
                        )
                        total_elapsed = int(delta.total_seconds())
                    except ValueError:
                        total_elapsed = None

            keep = (
                keep_worktree_on_failure
                and terminate_reason in _TERMINAL_STATES
                and terminate_reason != "completed"
            )
            if not keep:
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

    async def _insert_dispatch_row(
        self,
        *,
        dispatch_id: str,
        task_id: str,
        role: DispatchRole,
        working_directory: str,
        spec_path: str,
        timeout_seconds: int,
        timeout_override_seconds: int | None,
        keep_worktree_on_failure: bool,
        engineer_decisions: list[dict[str, Any]],
    ) -> None:
        import json as _json

        await self._storage.execute(
            "INSERT INTO dispatches "
            "(id, task_id, role, status, working_directory, spec_path, "
            "timeout_seconds, timeout_override_seconds, keep_worktree_on_failure, "
            "engineer_decisions_json) "
            "VALUES (?, ?, ?, 'queued', ?, ?, ?, ?, ?, ?)",
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
            ),
        )

    async def _await_terminal(
        self, dispatch_id: str, proc: SubprocessHandle
    ) -> DispatchOutcome:
        """Loop until the dispatch row reaches a terminal state.

        If the subprocess exits before ``complete_dispatch`` lands a terminal
        state on the row, mark the dispatch ``failed`` with a note. M4a does
        not enforce a hard timeout — that ships at M4c.
        """
        while True:
            # Poll dispatches row before proc.returncode: subprocess may exit
            # cleanly after writing terminal state, in which case row is
            # authoritative and returncode is decoration. Reverse the order
            # and a clean-exit-after-complete_dispatch race surfaces as a
            # spurious "failed" with rc=0.
            row = await self._storage.fetch_one(
                "SELECT status FROM dispatches WHERE id = ?", (dispatch_id,)
            )
            if row is None:
                raise DispatcherError(
                    f"dispatch row {dispatch_id} disappeared during await loop"
                )
            status = str(row["status"])
            if status in _TERMINAL_STATES:
                return await self._build_outcome(dispatch_id)

            if proc.returncode is not None:
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

    async def _build_outcome(self, dispatch_id: str) -> DispatchOutcome:
        import json as _json

        row = await self._storage.fetch_one(
            "SELECT status, commit_hash, decline_reason, test_results_json, "
            "queue_items_raised_json, artifacts_json, notes, "
            "actual_touched_files_json FROM dispatches WHERE id = ?",
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
