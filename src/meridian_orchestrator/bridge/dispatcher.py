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
import uuid
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Literal, Protocol

import structlog

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.auth import IssuedToken, TokenStore
from meridian_orchestrator.bridge.canonical import (
    CanonicalClone,
    CanonicalDivergedError,
)
from meridian_orchestrator.bridge.launch_env import build_subprocess_env
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
    """
    env = build_subprocess_env(token=ctx.token, dispatch_id=ctx.dispatch_id)
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
        push_after_merge: bool = True,
    ) -> None:
        if canonical.path in _dispatcher_owned_canonicals:
            raise RuntimeError(
                "BridgeDispatcher: another dispatcher already targets canonical "
                f"at {canonical.path}; per-instance asyncio.Lock on canonical "
                "operations would race. Production has one dispatcher per daemon."
            )
        _dispatcher_owned_canonicals.add(canonical.path)
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
        self._push_after_merge = push_after_merge
        # Serializes canonical-clone operations across concurrent dispatches:
        # the pre-dispatch fetch+ff-only refresh, and the post-completion
        # merge of the dispatch branch into canonical/main + push to upstream.
        # Per-dispatch subprocess work runs in parallel — only canonical
        # mutation is serialized.
        self._canonical_lock = asyncio.Lock()

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
        """
        if role not in ("implementer", "tester"):
            raise DispatcherError(f"unsupported role for dispatch: {role!r}")

        dispatch_id = _new_dispatch_id()
        worktree = DispatchWorktree(
            canonical=self._canonical,
            dispatch_id=dispatch_id,
            worktrees_root=self._worktrees_root,
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
                    await self._canonical.fetch_and_fast_forward()
                    await worktree.provision()
            except CanonicalDivergedError as exc:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.canonical_diverged",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "canonical_head": await self._canonical.head_sha(),
                        "error": str(exc),
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
            if outcome.status == "completed" and outcome.commit_hash:
                outcome = await self._merge_and_record(
                    dispatch_id=dispatch_id,
                    outcome=outcome,
                    worktree=worktree,
                )
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
                await self._canonical.fetch_and_fast_forward()
            except CanonicalDivergedError as exc:
                await write_audit(
                    self._storage,
                    actor="system",
                    action="dispatch.canonical_diverged",
                    target=dispatch_id,
                    details={
                        "dispatch_id": dispatch_id,
                        "canonical_head": await self._canonical.head_sha(),
                        "error": str(exc),
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
                },
            )

            if self._push_after_merge:
                try:
                    await self._canonical.push_to_upstream()
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
