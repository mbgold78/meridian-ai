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

        Runs ``git fetch origin`` then ``git merge --ff-only origin/<branch>``.
        Raises :class:`CanonicalCloneError` if either step fails.

        M4a behavior: surfaces the failure unconditionally. M4b will distinguish
        the canonical-divergence case (upstream rewrote history, canonical is
        no longer fast-forwardable) and emit the ``dispatch.canonical_diverged``
        audit event with manual operator recovery.
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
        rc, _, stderr = await _run_git(
            "merge", "--ff-only", f"origin/{self._default_branch}", cwd=self._path
        )
        if rc != 0:
            raise CanonicalCloneError(
                f"git merge --ff-only origin/{self._default_branch} failed (rc={rc}): "
                f"{stderr.strip()!r}"
            )
        logger.debug("canonical.refreshed", path=str(self._path))
