"""Daemon-owned canonical clone of the substrate.

The bridge owns a clone at ``<MERIDIAN_ORCH_DATA_DIR>/substrate-canonical/``
that is the *only* working copy the daemon ever edits. Per-dispatch worktrees
branch from this clone (see :mod:`bridge.worktree`); merged dispatch branches
push back to the same upstream Mike's manual working tree at
``meridian-homepoc/`` pulls from. The daemon never reads or writes Mike's
working tree.

M4a scope: provisioning (clone-on-first-start) and pre-dispatch refresh
(``fetch origin`` + ``merge --ff-only origin/main``). Canonical-divergence
detection — when canonical's HEAD has somehow advanced past upstream's, which
should never happen since the daemon is the only writer — is deferred to M4b
along with the worktree merge protocol.
"""

from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

import structlog

from meridian_orchestrator.errors import OrchestratorError

logger = structlog.get_logger(__name__)


class CanonicalCloneError(OrchestratorError):
    """Raised when canonical-clone provisioning or refresh fails."""

    code = "CANONICAL_CLONE_ERROR"


class CanonicalDivergedError(OrchestratorError):
    """Raised when canonical's HEAD is not an ancestor of upstream/<branch>.

    Per ``11-engineer-bridge.md`` this should never happen — the daemon is
    the only writer of the canonical clone, and every merge is ``--ff-only``
    against either upstream or a dispatch branch. Surfacing this error means
    something has touched canonical out-of-band (operator manually committed
    in there, a partial recovery from a previous crash that left a wedged
    merge state, etc.). Recovery is manual: operator inspects, resets, or
    deletes-and-reclones canonical. Auto-healing is intentionally not
    implemented — the right move is "stop and call a human."
    """

    code = "CANONICAL_DIVERGED"


async def _run_git(*args: str, cwd: Path | None = None) -> tuple[int, str, str]:
    """Run ``git <args>`` and return ``(returncode, stdout, stderr)``."""
    proc = await asyncio.create_subprocess_exec(
        "git",
        *args,
        cwd=str(cwd) if cwd is not None else None,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stdout_b, stderr_b = await proc.communicate()
    return (
        proc.returncode if proc.returncode is not None else -1,
        stdout_b.decode("utf-8", errors="replace"),
        stderr_b.decode("utf-8", errors="replace"),
    )


async def discover_upstream_url(substrate_working_directory: Path) -> str:
    """Read the ``origin`` remote URL from the substrate project's working tree.

    Per ``11-engineer-bridge.md``: the daemon inspects Mike's working tree's
    ``origin`` once at provisioning time, then operates against that upstream
    directly. Raises :class:`CanonicalCloneError` if the path is not a git
    repository or has no ``origin`` remote configured.
    """
    if not substrate_working_directory.exists():
        raise CanonicalCloneError(
            f"substrate working directory does not exist: {substrate_working_directory}"
        )
    rc, stdout, stderr = await _run_git(
        "config", "--get", "remote.origin.url", cwd=substrate_working_directory
    )
    if rc != 0:
        raise CanonicalCloneError(
            f"could not read remote.origin.url from {substrate_working_directory}: "
            f"git exited {rc}; stderr={stderr.strip()!r}"
        )
    url = stdout.strip()
    if not url:
        raise CanonicalCloneError(
            f"no origin remote configured in {substrate_working_directory}"
        )
    return url


class CanonicalClone:
    """Manages the daemon-owned ``substrate-canonical/`` clone.

    A single instance per substrate project is expected for the daemon's
    lifetime. The path defaults to ``<data_dir>/substrate-canonical/`` but is
    parameterized so tests can point at a tmp_path. The upstream URL is taken
    on construction; callers typically resolve it via
    :func:`discover_upstream_url` against the configured project's working
    directory.
    """

    def __init__(
        self,
        *,
        path: Path,
        upstream_url: str,
        default_branch: str = "main",
    ) -> None:
        self._path = path
        self._upstream_url = upstream_url
        self._default_branch = default_branch

    @property
    def path(self) -> Path:
        """The canonical clone's working tree path."""
        return self._path

    @property
    def upstream_url(self) -> str:
        return self._upstream_url

    @property
    def default_branch(self) -> str:
        return self._default_branch

    @property
    def is_provisioned(self) -> bool:
        return (self._path / ".git").exists()

    async def ensure_provisioned(self) -> None:
        """Clone the upstream if the canonical path does not yet exist.

        Idempotent — second call on a provisioned clone is a no-op.
        """
        if self.is_provisioned:
            return
        if self._path.exists():
            raise CanonicalCloneError(
                f"canonical path {self._path} exists but is not a git repository; "
                "manual cleanup required before the daemon can provision"
            )
        self._path.parent.mkdir(parents=True, exist_ok=True)
        logger.info(
            "canonical.clone_begin",
            path=str(self._path),
            upstream=self._upstream_url,
        )
        rc, _, stderr = await _run_git(
            "clone",
            self._upstream_url,
            str(self._path),
        )
        if rc != 0:
            if self._path.exists():
                shutil.rmtree(self._path, ignore_errors=True)
            raise CanonicalCloneError(
                f"git clone failed (rc={rc}): {stderr.strip()!r}"
            )
        logger.info("canonical.clone_ok", path=str(self._path))

    async def fetch_and_fast_forward(self) -> None:
        """Refresh the canonical clone against upstream.

        Runs ``git fetch origin``, verifies canonical's HEAD is an ancestor of
        ``origin/<branch>`` (or equal to it), then ``git merge --ff-only
        origin/<branch>``.

        Raises :class:`CanonicalDivergedError` if HEAD is *not* an ancestor of
        ``origin/<branch>`` — i.e., canonical has commits upstream doesn't,
        which should never happen because the daemon is the only writer of
        canonical. Caller (the dispatcher) audits ``dispatch.canonical_diverged``
        and halts the dispatch.

        Raises :class:`CanonicalCloneError` for any other failure (fetch
        failed, merge step itself fails despite the ancestry check passing).
        """
        if not self.is_provisioned:
            raise CanonicalCloneError(
                f"canonical clone at {self._path} is not provisioned; "
                "call ensure_provisioned() first"
            )
        rc, _, stderr = await _run_git("fetch", "origin", cwd=self._path)
        if rc != 0:
            raise CanonicalCloneError(
                f"git fetch origin failed (rc={rc}): {stderr.strip()!r}"
            )

        canonical_head = await self._rev_parse("HEAD")
        upstream_head = await self._rev_parse(f"origin/{self._default_branch}")

        rc, _, _ = await _run_git(
            "merge-base",
            "--is-ancestor",
            "HEAD",
            f"origin/{self._default_branch}",
            cwd=self._path,
        )
        if rc != 0:
            raise CanonicalDivergedError(
                "canonical HEAD is not an ancestor of "
                f"origin/{self._default_branch}: canonical_head={canonical_head}, "
                f"upstream_head={upstream_head}. Manual recovery required."
            )

        rc, _, stderr = await _run_git(
            "merge", "--ff-only", f"origin/{self._default_branch}", cwd=self._path
        )
        if rc != 0:
            raise CanonicalCloneError(
                f"git merge --ff-only origin/{self._default_branch} failed (rc={rc}): "
                f"{stderr.strip()!r}"
            )
        logger.debug("canonical.refreshed", path=str(self._path))

    async def push_to_upstream(self) -> None:
        """Push canonical's default branch to ``origin``.

        Called by the dispatcher after a successful ``--ff-only`` merge of a
        dispatch branch into canonical's main. Raises :class:`CanonicalCloneError`
        on push failure (which surfaces the original landed commit but blocks
        the upstream catch-up; operator follow-up).
        """
        if not self.is_provisioned:
            raise CanonicalCloneError(
                f"canonical clone at {self._path} is not provisioned"
            )
        rc, _, stderr = await _run_git(
            "push", "origin", self._default_branch, cwd=self._path
        )
        if rc != 0:
            raise CanonicalCloneError(
                f"git push origin {self._default_branch} failed (rc={rc}): "
                f"{stderr.strip()!r}"
            )

    async def head_sha(self) -> str:
        """Return the current HEAD sha of the canonical clone."""
        return await self._rev_parse("HEAD")

    async def _rev_parse(self, ref: str) -> str:
        rc, stdout, stderr = await _run_git("rev-parse", ref, cwd=self._path)
        if rc != 0:
            raise CanonicalCloneError(
                f"git rev-parse {ref!r} failed (rc={rc}): {stderr.strip()!r}"
            )
        return stdout.strip()
