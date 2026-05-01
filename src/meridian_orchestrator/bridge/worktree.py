"""Per-dispatch git worktree provisioning + merge protocol.

Each dispatch gets its own ``git worktree`` branched off
``substrate-canonical``'s ``origin/main`` at dispatch time. The subprocess's
working directory is the worktree path. Multiple worktrees coexist for
parallel dispatches (each on its own branch ``dispatch/<dispatch_id>``).

On ``complete_dispatch(status='completed', commit_hash=...)``, the dispatcher
asks the worktree to merge into canonical's main via the ``--ff-only``
protocol (M4b). On success the canonical clone fast-forwards and the
dispatcher pushes to upstream. On failure the conflicting paths are recorded
in ``dispatches.merge_conflict_files_json`` and PM gets the surface for
re-dispatch / escalation; no auto-rebase is attempted.

Cleanup is idempotent — the dispatcher calls it on every terminal state
(including ``failed`` cases where the worktree may already be partly
destroyed). On daemon startup the dispatcher also sweeps any leftover
worktrees regardless of the per-dispatch ``keep_worktree_on_failure`` flag,
to prevent orphan accumulation across crashes (see
``11-engineer-bridge.md`` § Cleanup on terminal state).
"""

from __future__ import annotations

import asyncio
import shutil
from dataclasses import dataclass
from pathlib import Path

import structlog

from meridian_orchestrator.bridge.canonical import CanonicalClone, _run_git
from meridian_orchestrator.errors import OrchestratorError

# git on Windows occasionally leaves `.git/config.lock` or similar lock
# files briefly after a command returns — the file-handle release isn't
# synchronous with process exit. The asyncio Lock on the dispatcher's
# canonical operations is structurally correct: it serializes Python-side
# calls, and on Linux this is sufficient. The retry below is a Windows
# accommodation, not a covering for a real concurrency bug. If you ever
# see one of these markers in stderr on Linux, that IS a real bug — the
# fix is upstream, not tightening the retry. See M4b checkpoint notes.
_LOCK_ERROR_MARKERS = (
    "could not lock config file",
    ".git/config.lock",
    "File exists",
)


def _is_lock_contention(stderr: str) -> bool:
    return any(marker in stderr for marker in _LOCK_ERROR_MARKERS)

logger = structlog.get_logger(__name__)


class WorktreeError(OrchestratorError):
    """Raised when worktree provisioning or cleanup fails."""

    code = "WORKTREE_ERROR"


@dataclass(frozen=True, slots=True)
class MergeResult:
    """Outcome of merging a dispatch branch into canonical's default branch.

    ``ok`` is true on a successful fast-forward. On failure, ``conflicting_files``
    enumerates paths the dispatch branch touched since divergence — PM uses
    this to scope the re-dispatch or escalation. ``new_head`` is set on
    success to the new canonical HEAD sha.
    """

    ok: bool
    new_head: str | None
    conflicting_files: list[str]
    reason: str | None = None


def dispatch_branch_name(dispatch_id: str) -> str:
    """The git branch name used for a dispatch's isolated working tree."""
    return f"dispatch/{dispatch_id}"


class DispatchWorktree:
    """A per-dispatch ``git worktree`` off the canonical clone's default branch.

    The dispatch's subprocess uses ``self.path`` as its cwd. The branch name
    is derived from the dispatch_id so it is stable across daemon restarts —
    matters for reattach logic at M4c+ when the daemon comes back up while
    a dispatch is in flight.
    """

    def __init__(
        self,
        *,
        canonical: CanonicalClone,
        dispatch_id: str,
        worktrees_root: Path,
    ) -> None:
        self._canonical = canonical
        self._dispatch_id = dispatch_id
        self._path = worktrees_root / dispatch_id
        self._branch = dispatch_branch_name(dispatch_id)

    @property
    def path(self) -> Path:
        return self._path

    @property
    def branch(self) -> str:
        return self._branch

    @property
    def dispatch_id(self) -> str:
        return self._dispatch_id

    async def provision(self) -> None:
        """Create the dispatch's branch + worktree off ``origin/<default_branch>``.

        Equivalent to::

            git -C <canonical> worktree add -b dispatch/<id> <worktree_path> origin/main

        Raises :class:`WorktreeError` if the canonical clone is unprovisioned
        or the ``git worktree add`` command fails.
        """
        if not self._canonical.is_provisioned:
            raise WorktreeError(
                f"canonical clone at {self._canonical.path} is not provisioned; "
                "cannot create dispatch worktree"
            )
        if self._path.exists():
            raise WorktreeError(
                f"worktree path {self._path} already exists; "
                "cleanup() prior dispatch state before provisioning"
            )
        self._path.parent.mkdir(parents=True, exist_ok=True)

        # `--no-track` skips writing branch upstream config to canonical's
        # `.git/config`. We don't use upstream tracking on dispatch branches
        # — the daemon manages their lifecycle by branch ref directly — and
        # skipping the config write reduces a Windows-specific race where
        # parallel `git worktree add` calls collide on `.git/config.lock`
        # because the OS hasn't released the lock-file handle from the
        # prior add by the time the next add tries to create it. A short
        # retry on the residual race covers the rest.
        rc, stderr = await self._git_add_with_retry()
        if rc != 0:
            raise WorktreeError(
                f"git worktree add failed (rc={rc}): {stderr.strip()!r}"
            )
        logger.info(
            "worktree.provisioned",
            dispatch_id=self._dispatch_id,
            path=str(self._path),
            branch=self._branch,
        )

    async def _git_add_with_retry(
        self, max_attempts: int = 3
    ) -> tuple[int, str]:
        """Run ``git worktree add`` with a short retry on lock contention."""
        rc = -1
        stderr = ""
        for attempt in range(max_attempts):
            rc, _, stderr = await _run_git(
                "worktree",
                "add",
                "--no-track",
                "-b",
                self._branch,
                str(self._path),
                f"origin/{self._canonical.default_branch}",
                cwd=self._canonical.path,
            )
            if rc == 0:
                return rc, stderr
            if not _is_lock_contention(stderr):
                return rc, stderr
            # Best-effort: clean up any partial worktree directory + branch
            # the failed add may have left behind, so the retry sees a clean
            # slate.
            if self._path.exists():
                shutil.rmtree(self._path, ignore_errors=True)
            await _run_git(
                "branch", "-D", self._branch, cwd=self._canonical.path
            )
            await asyncio.sleep(0.05 * (attempt + 1))
        return rc, stderr

    async def cleanup(self) -> None:
        """Remove the worktree and delete its branch. Idempotent.

        Tries ``git worktree remove --force`` first; if that leaves the
        directory behind (e.g., the worktree was never registered with the
        canonical clone), removes the directory directly. Then deletes the
        dispatch branch via ``git branch -D``. Errors are logged but not
        raised — cleanup must succeed-or-degrade so terminal-state handling
        is not blocked by a stale worktree.
        """
        if self._canonical.is_provisioned:
            rc, _, stderr = await _run_git(
                "worktree",
                "remove",
                "--force",
                str(self._path),
                cwd=self._canonical.path,
            )
            if rc != 0:
                logger.warning(
                    "worktree.remove_failed",
                    dispatch_id=self._dispatch_id,
                    path=str(self._path),
                    rc=rc,
                    stderr=stderr.strip(),
                )

        if self._path.exists():
            shutil.rmtree(self._path, ignore_errors=True)

        if self._canonical.is_provisioned:
            rc, _, stderr = await _run_git(
                "branch", "-D", self._branch, cwd=self._canonical.path
            )
            if rc != 0:
                logger.debug(
                    "worktree.branch_delete_skipped",
                    dispatch_id=self._dispatch_id,
                    branch=self._branch,
                    rc=rc,
                    stderr=stderr.strip(),
                )

        logger.info(
            "worktree.cleaned",
            dispatch_id=self._dispatch_id,
            path=str(self._path),
        )

    async def head_commit(self) -> str | None:
        """Return the HEAD commit sha of the dispatch branch, or ``None``.

        Returns ``None`` if the worktree no longer exists or the branch
        has no commits beyond the base. Used by the dispatcher to capture
        the commit hash a sub-agent reports via ``complete_dispatch`` when
        verifying it landed on the dispatch branch.
        """
        if not self._path.exists():
            return None
        rc, stdout, _ = await _run_git("rev-parse", "HEAD", cwd=self._path)
        if rc != 0:
            return None
        sha = stdout.strip()
        return sha or None

    async def merge_into_canonical(self) -> MergeResult:
        """Attempt ``git merge --ff-only dispatch/<id>`` into canonical's default branch.

        The caller (dispatcher) ensures the canonical clone is fresh by calling
        :meth:`bridge.canonical.CanonicalClone.fetch_and_fast_forward` first
        (under the canonical lock so concurrent dispatches serialize their
        merges).

        Returns a :class:`MergeResult`:

        - On success: ``ok=True``, ``new_head=<sha>``, ``conflicting_files=[]``.
          Caller pushes canonical to upstream and audits ``dispatch.merged``.
        - On non-fast-forward failure: ``ok=False``, ``new_head=None``,
          ``conflicting_files=[<paths>]``. The list contains paths touched on
          the dispatch branch since the divergence point — PM's scope for
          re-dispatch decisions. Caller audits ``dispatch.merge_conflict``.

        Other failure modes (canonical missing, git invocation broke) raise
        :class:`WorktreeError` so the caller can distinguish "merge worked
        but didn't fast-forward" from "merge plumbing broke."
        """
        if not self._canonical.is_provisioned:
            raise WorktreeError(
                f"canonical clone at {self._canonical.path} is not provisioned"
            )

        # Resolve the dispatch branch's HEAD and the merge base before merging
        # so we can compute conflicting_files without depending on git's exact
        # error message on --ff-only failure.
        branch_head = await self._rev_parse(self._branch)
        if branch_head is None:
            return MergeResult(
                ok=False,
                new_head=None,
                conflicting_files=[],
                reason=f"dispatch branch {self._branch!r} has no resolvable HEAD",
            )

        rc, _, stderr = await _run_git(
            "merge", "--ff-only", self._branch, cwd=self._canonical.path
        )
        if rc == 0:
            new_head = await self._canonical.head_sha()
            logger.info(
                "worktree.merged",
                dispatch_id=self._dispatch_id,
                new_canonical_head=new_head,
                branch=self._branch,
            )
            return MergeResult(
                ok=True,
                new_head=new_head,
                conflicting_files=[],
            )

        conflicting = await self._touched_files_since_base(branch_head)
        logger.warning(
            "worktree.merge_conflict",
            dispatch_id=self._dispatch_id,
            branch=self._branch,
            conflicting_files=conflicting,
            stderr=stderr.strip(),
        )
        return MergeResult(
            ok=False,
            new_head=None,
            conflicting_files=conflicting,
            reason=stderr.strip() or "non-fast-forward merge",
        )

    async def _touched_files_since_base(self, branch_head: str) -> list[str]:
        """Return the files touched on the dispatch branch since divergence.

        Equivalent to ``git diff --name-only <merge-base> <branch_head>``.
        Returns an empty list on any failure; the caller treats that as
        "couldn't determine; report empty" rather than raising — PM still
        gets the merge-conflict signal even if the file scope is unknown.
        """
        rc, stdout, _ = await _run_git(
            "merge-base",
            f"refs/heads/{self._canonical.default_branch}",
            branch_head,
            cwd=self._canonical.path,
        )
        if rc != 0:
            return []
        merge_base = stdout.strip()
        rc, stdout, _ = await _run_git(
            "diff",
            "--name-only",
            merge_base,
            branch_head,
            cwd=self._canonical.path,
        )
        if rc != 0:
            return []
        return [line for line in stdout.splitlines() if line.strip()]

    async def _rev_parse(self, ref: str) -> str | None:
        rc, stdout, _ = await _run_git(
            "rev-parse", ref, cwd=self._canonical.path
        )
        if rc != 0:
            return None
        sha = stdout.strip()
        return sha or None
