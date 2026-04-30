"""Per-dispatch git worktree provisioning.

Each dispatch gets its own ``git worktree`` branched off
``substrate-canonical``'s ``origin/main`` at dispatch time. The subprocess's
working directory is the worktree path. Multiple worktrees coexist for
parallel dispatches (each on its own branch ``dispatch/<dispatch_id>``).

M4a scope: provision + cleanup. The ``--ff-only`` merge protocol that lands
the dispatch branch back into canonical's ``main`` ships at M4b along with
:class:`bridge.canonical.CanonicalClone`'s divergence detection.

Cleanup is idempotent — the dispatcher calls it on every terminal state
(including ``failed`` cases where the worktree may already be partly
destroyed). On daemon startup the dispatcher also sweeps any leftover
worktrees regardless of the per-dispatch ``keep_worktree_on_failure`` flag,
to prevent orphan accumulation across crashes (see
``11-engineer-bridge.md`` § Cleanup on terminal state).
"""

from __future__ import annotations

import shutil
from pathlib import Path

import structlog

from meridian_orchestrator.bridge.canonical import CanonicalClone, _run_git
from meridian_orchestrator.errors import OrchestratorError

logger = structlog.get_logger(__name__)


class WorktreeError(OrchestratorError):
    """Raised when worktree provisioning or cleanup fails."""

    code = "WORKTREE_ERROR"


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

        rc, _, stderr = await _run_git(
            "worktree",
            "add",
            "-b",
            self._branch,
            str(self._path),
            f"origin/{self._canonical.default_branch}",
            cwd=self._canonical.path,
        )
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
