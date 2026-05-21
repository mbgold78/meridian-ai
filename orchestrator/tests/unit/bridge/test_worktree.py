"""Unit tests for :class:`bridge.worktree.DispatchWorktree`.

M5-canonical-clone-mgmt slice 4 (C2) pins the per-canonical nested
worktree path layout: ``<worktrees_root>/<canonical_name>/<dispatch_id>/``
replaces the pre-Slice-4 flat ``<worktrees_root>/<dispatch_id>/``. The
``DispatchWorktree.__init__`` constructor accepts a required
``canonical_name: str`` kwarg; ``provision()`` (and its
``mkdir(parents=True, exist_ok=True)``) covers the new nested depth
without source change.

These tests stay pure-Python: no git, no canonical clone provisioning,
no async work. They exercise the constructor's path-construction
arithmetic and verify directory creation against the nested depth.
"""

from __future__ import annotations

import shutil
import subprocess
from pathlib import Path

import pytest

from meridian_orchestrator.bridge.canonical import CanonicalClone
from meridian_orchestrator.bridge.worktree import DispatchWorktree


def _make_canonical_stub(tmp_path: Path) -> CanonicalClone:
    """Return a non-provisioned CanonicalClone instance.

    The constructor under test does not touch the canonical's filesystem
    state — the canonical is recorded as-is. Provisioning is exercised
    by the integration tests, not the unit layer.
    """
    return CanonicalClone(
        path=tmp_path / "canonical-stub",
        upstream_url="https://example.com/repo.git",
    )


def test_path_nested_under_canonical_name_substrate(tmp_path: Path) -> None:
    """`DispatchWorktree.path` nests under the canonical_name segment.

    With `canonical_name="substrate"` and `dispatch_id="abc123"`, the
    constructed path equals `<worktrees_root>/substrate/abc123`. Pins
    the C2 path-layout shape for the operator-observability win
    (`ls dispatch-worktrees/substrate/` answers the operationally
    common question without a DB query).
    """
    worktrees_root = tmp_path / "dispatch-worktrees"
    canonical = _make_canonical_stub(tmp_path)
    worktree = DispatchWorktree(
        canonical=canonical,
        dispatch_id="abc123",
        worktrees_root=worktrees_root,
        canonical_name="substrate",
    )
    assert worktree.path == worktrees_root / "substrate" / "abc123"


def test_path_nested_under_canonical_name_default(tmp_path: Path) -> None:
    """Default-canonical dispatches also nest under `<root>/default/<id>/`.

    Pre-Slice-4 worktrees sat at the flat `<root>/<id>/` layout. Post-C2
    the layout is uniform across all canonicals — `'default'` is just
    one name among many. Backward-compat for the existing callers
    (which pass `canonical_name=None` and the dispatcher resolves to
    `'default'`) is preserved by the dispatcher's explicit-literal
    capture at § 3d of the slice-4 spec (`resolved_canonical_name =
    canonical_name if canonical_name is not None else "default"`).
    """
    worktrees_root = tmp_path / "dispatch-worktrees"
    canonical = _make_canonical_stub(tmp_path)
    worktree = DispatchWorktree(
        canonical=canonical,
        dispatch_id="abc123",
        worktrees_root=worktrees_root,
        canonical_name="default",
    )
    assert worktree.path == worktrees_root / "default" / "abc123"


def test_path_differs_across_canonicals(tmp_path: Path) -> None:
    """Two worktrees with the same dispatch_id but different canonicals
    have distinct paths.

    Filesystem-collision safety: even with a shared dispatch_id (which
    cannot happen by construction since dispatch_id is uuid hex, but
    the test pins the path-shape property independently), the canonical
    name segment guarantees the worktrees occupy different parent
    directories.
    """
    worktrees_root = tmp_path / "dispatch-worktrees"
    canonical = _make_canonical_stub(tmp_path)
    wt_default = DispatchWorktree(
        canonical=canonical,
        dispatch_id="shared-id",
        worktrees_root=worktrees_root,
        canonical_name="default",
    )
    wt_substrate = DispatchWorktree(
        canonical=canonical,
        dispatch_id="shared-id",
        worktrees_root=worktrees_root,
        canonical_name="substrate",
    )
    assert wt_default.path != wt_substrate.path
    assert wt_default.path.parent != wt_substrate.path.parent


def test_constructor_requires_canonical_name_keyword(tmp_path: Path) -> None:
    """`canonical_name` is a required keyword on the constructor.

    Omitting it raises `TypeError` from the keyword-only signature.
    Pins the contract that every caller (today only the dispatcher)
    must supply the resolved name explicitly; no implicit defaulting
    to `'default'` at the worktree layer.
    """
    canonical = _make_canonical_stub(tmp_path)
    with pytest.raises(TypeError):
        DispatchWorktree(  # type: ignore[call-arg]
            canonical=canonical,
            dispatch_id="abc123",
            worktrees_root=tmp_path / "dispatch-worktrees",
        )


@pytest.mark.asyncio
async def test_provision_creates_nested_parent_directories(
    tmp_path: Path,
) -> None:
    """`provision()` creates `<root>/<canonical_name>/` if missing.

    The constructor sets `self._path = worktrees_root / canonical_name /
    dispatch_id`; `provision()` body's
    `self._path.parent.mkdir(parents=True, exist_ok=True)` then creates
    both the `worktrees_root` and the `canonical_name` parent in one
    go. This test exercises the mkdir leg directly (without invoking
    `git worktree add`, which requires a provisioned canonical clone).

    Pre-create an upstream + canonical clone with real git, then call
    `provision()` end-to-end — the nested parent directory is created
    by the dispatcher path and the worktree lands at
    `<root>/<canonical>/<id>/`.
    """
    if shutil.which("git") is None:
        pytest.skip("git is not on PATH")

    # Set up a real upstream + canonical so `provision()` can run
    # `git worktree add` successfully.
    upstream = tmp_path / "upstream"
    upstream.mkdir()
    subprocess.run(
        ["git", "init", "-b", "main"],
        cwd=str(upstream),
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.email", "test@example.com"],
        cwd=str(upstream),
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "config", "user.name", "Test"],
        cwd=str(upstream),
        check=True,
        capture_output=True,
    )
    (upstream / "README.md").write_text("hello\n", encoding="utf-8")
    subprocess.run(
        ["git", "add", "README.md"],
        cwd=str(upstream),
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "commit", "-m", "initial"],
        cwd=str(upstream),
        check=True,
        capture_output=True,
    )

    canonical = CanonicalClone(
        path=tmp_path / "canonical",
        upstream_url=str(upstream),
    )
    await canonical.ensure_provisioned()

    worktrees_root = tmp_path / "dispatch-worktrees"
    # Confirm the nested parent does not exist before provision().
    assert not (worktrees_root / "substrate").exists()

    worktree = DispatchWorktree(
        canonical=canonical,
        dispatch_id="abc123def",
        worktrees_root=worktrees_root,
        canonical_name="substrate",
    )
    await worktree.provision()

    # After provision(): nested parent and worktree both exist.
    assert (worktrees_root / "substrate").is_dir()
    assert (worktrees_root / "substrate" / "abc123def").is_dir()
    assert worktree.path == worktrees_root / "substrate" / "abc123def"

    # Cleanup — leaves the canonical clone intact.
    await worktree.cleanup()
