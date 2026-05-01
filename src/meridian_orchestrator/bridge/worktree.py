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

import shutil
from dataclasses import dataclass
from pathlib import Path

import structlog

from meridian_orchestrator.bridge.canonical import CanonicalClone, _run_git
from meridian_orchestrator.errors import OrchestratorError

logger = structlog.get_logger(__name__)


class WorktreeError(OrchestratorError):
    """Raised when worktree provisioning or cleanup fails."""

    code = "WORKTREE_ERROR"


@dataclass(frozen=True, slots=True)
class MergeResult:
    """Outcome of merging a dispatch branch into canonical's default branch.

    ``ok`` is true when ff-rebase + ``--ff-only`` merge both succeeded.

    On success, ``new_head`` is canonical's post-merge HEAD sha and
    ``touched_files`` lists the paths the merge introduced (computed from
    ``git diff --name-only`` between pre- and post-merge canonical HEADs —
    correct under rebase, since the rebase may have rewritten commit hashes
    but the canonical-HEAD-advance diff captures the actual landed delta).

    On failure, ``conflicting_files`` enumerates paths the dispatch branch
    touched since divergence — PM's scope for re-dispatch decisions.

    ``original_commit_hash`` is the dispatch branch's HEAD *before* rebase
    rewrote it (the sub-agent-reported commit). Captured on every result so
    audit rows can record both the sub-agent's commit and the landed-on-
    canonical commit (``new_head`` on success). ``None`` only when the
    branch had no resolvable HEAD at the start.
    """

    ok: bool
    new_head: str | None
    conflicting_files: list[str]
    touched_files: list[str]
    original_commit_hash: str | None
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
        # `.git/config`, sidestepping a known Windows file-handle race on
        # `.git/config.lock`. Residual transient lock errors are caught by
        # the retry inside `_run_git` itself.
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

    async def merge_into_canonical(self) -> MergeResult:
        """Land the dispatch branch into canonical's default branch.

        Two-step protocol (M4b.1, resolves spec line 147 vs sequence diagram
        522-524 tension):

        1. **ff-rebase the dispatch branch onto canonical's main.** ``git
           rebase main dispatch/<id>`` replays the dispatch's commits on top
           of canonical's current HEAD. For disjoint-file parallel dispatches
           the rebase is a no-op-on-content (commits replay cleanly with new
           hashes), and the subsequent ``--ff-only`` merge succeeds. For
           real semantic conflicts (two dispatches edited the same lines),
           rebase aborts; we run ``git rebase --abort`` to leave canonical
           clean and fall through to the merge_conflict surface.
        2. **``--ff-only`` merge** of the rebased dispatch branch into
           canonical's main. Always succeeds when step 1 succeeded (dispatch
           branch is now a strict descendant of main).

        The rebase rewrites the dispatch branch's commit hashes. The
        sub-agent-reported original hash is preserved as
        :attr:`MergeResult.original_commit_hash`; the post-merge canonical
        HEAD is :attr:`MergeResult.new_head`. The audit chain records both
        (``dispatch.merged`` includes both fields; ``dispatch.merge_conflict``
        records the original).

        Caller (dispatcher) holds the canonical lock across this call and
        has already refreshed canonical via ``fetch_and_fast_forward``.

        Other failure modes (canonical missing) raise :class:`WorktreeError`.
        """
        if not self._canonical.is_provisioned:
            raise WorktreeError(
                f"canonical clone at {self._canonical.path} is not provisioned"
            )

        pre_canonical_head = await self._canonical.head_sha()
        branch_head_pre_rebase = await self._rev_parse(self._branch)
        if branch_head_pre_rebase is None:
            return MergeResult(
                ok=False,
                new_head=None,
                conflicting_files=[],
                touched_files=[],
                original_commit_hash=None,
                reason=f"dispatch branch {self._branch!r} has no resolvable HEAD",
            )

        # Run the rebase from inside the worktree, where dispatch/<id> is
        # already checked out. Running it from the canonical clone fails with
        # "branch already used by worktree at ..." because git enforces
        # branch-exclusivity across worktrees of the same repo.
        rebase_rc, _, rebase_stderr = await _run_git(
            "rebase",
            f"refs/heads/{self._canonical.default_branch}",
            cwd=self._path,
        )
        if rebase_rc != 0:
            # Abort to restore the worktree's HEAD and the dispatch branch to
            # their pre-rebase state. The abort itself can fail if rebase
            # didn't actually start (e.g., rebase rejected the input upfront),
            # in which case the worktree is already clean — log but don't
            # propagate.
            abort_rc, _, abort_stderr = await _run_git(
                "rebase", "--abort", cwd=self._path
            )
            if abort_rc != 0:
                logger.debug(
                    "worktree.rebase_abort_skipped",
                    dispatch_id=self._dispatch_id,
                    rc=abort_rc,
                    stderr=abort_stderr.strip(),
                )
            conflicting = await self._touched_files_since_base(
                branch_head_pre_rebase
            )
            logger.warning(
                "worktree.rebase_conflict",
                dispatch_id=self._dispatch_id,
                branch=self._branch,
                conflicting_files=conflicting,
                stderr=rebase_stderr.strip(),
            )
            return MergeResult(
                ok=False,
                new_head=None,
                conflicting_files=conflicting,
                touched_files=[],
                original_commit_hash=branch_head_pre_rebase,
                reason=f"rebase aborted on conflict: {rebase_stderr.strip()}",
            )

        # Rebase succeeded; ff-only merge into main. By construction this
        # should succeed since the dispatch branch is now a descendant of
        # canonical's main. The defensive handling below catches the
        # unexpected failure (e.g., main moved between rebase and merge,
        # which the canonical lock prevents in practice but a paranoid
        # caller might still observe).
        rc, _, stderr = await _run_git(
            "merge", "--ff-only", self._branch, cwd=self._canonical.path
        )
        if rc != 0:
            conflicting = await self._touched_files_since_base(
                branch_head_pre_rebase
            )
            logger.warning(
                "worktree.merge_failed_after_rebase",
                dispatch_id=self._dispatch_id,
                branch=self._branch,
                conflicting_files=conflicting,
                stderr=stderr.strip(),
            )
            return MergeResult(
                ok=False,
                new_head=None,
                conflicting_files=conflicting,
                touched_files=[],
                original_commit_hash=branch_head_pre_rebase,
                reason=(
                    f"--ff-only failed after successful rebase: {stderr.strip()}"
                ),
            )

        new_head = await self._canonical.head_sha()
        touched = await self._diff_canonical_advance(pre_canonical_head, new_head)
        logger.info(
            "worktree.merged",
            dispatch_id=self._dispatch_id,
            original_commit=branch_head_pre_rebase,
            new_canonical_head=new_head,
            branch=self._branch,
        )
        return MergeResult(
            ok=True,
            new_head=new_head,
            conflicting_files=[],
            touched_files=touched,
            original_commit_hash=branch_head_pre_rebase,
        )

    async def _diff_canonical_advance(
        self, before: str, after: str
    ) -> list[str]:
        """Return ``git diff --name-only <before> <after>`` against canonical.

        Captures the actual file delta the dispatch's merge introduced into
        canonical's main, regardless of whether rebase rewrote commit hashes.
        """
        if before == after:
            return []
        rc, stdout, _ = await _run_git(
            "diff", "--name-only", before, after, cwd=self._canonical.path
        )
        if rc != 0:
            return []
        return [line for line in stdout.splitlines() if line.strip()]

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
