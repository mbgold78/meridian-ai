"""Direct-handler tests for ``tools.pm_handoff``.

Exercises :func:`tools.pm_handoff.write_pm_handoff`,
:func:`tools.pm_handoff.get_most_recent_handoff`,
:func:`tools.pm_handoff.read_most_recent_handoff`, and the filename
helpers (:func:`_format_handoff_filename`, :func:`_parse_handoff_filename`).

Covers (M5-continuous-pm-driver commit 8, F9.0 Reading Y disposition):

- Filesystem behavior: directory creation; file existence; filename
  collision rejection; sort-by-timestamp on read; malformed-filename
  filtering on read; cold-start (None on empty/missing dir).
- Permission gate at handler layer (PM-only on both tools).
- Audit emission shape (``pm.compaction.handoff_written`` with details).
- Filename schema round-trip + Windows-safe colon-replacement +
  UTC-required validation.
- Settings ``pm_handoff_dir`` field default behavior.

Permission tests at MCP wrapper layer + Pydantic boundary tests live
in ``tests/integration/test_pm_handoff_mcp_wrapper.py``. End-to-end
compaction-write-then-respawn-read flow lives in
``tests/integration/test_pm_compaction_e2e.py``.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.config import Settings
from meridian_orchestrator.errors import (
    PermissionError as OrchestratorPermissionError,
)
from meridian_orchestrator.errors import ValidationError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_handoff import (
    WritePmHandoffInput,
    _format_handoff_filename,
    _parse_handoff_filename,
    get_most_recent_handoff,
    read_most_recent_handoff,
    write_pm_handoff,
)


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


@pytest.fixture
def handoff_dir(tmp_path: Path) -> Path:
    return tmp_path / "pm-handoffs"


def _make_get_handoff_dir(path: Path):  # type: ignore[no-untyped-def]
    """Closure factory mirroring main.py lifespan's get_pm_handoff_dir
    plumbing — returns a callable that returns the path."""
    def _getter() -> Path:
        return path

    return _getter


# ---------------------------------------------------------------------------
# Filename schema — round-trip + Windows-safe + UTC-required
# ---------------------------------------------------------------------------


def test_format_handoff_filename_roundtrips_to_parsed_datetime() -> None:
    """_format_handoff_filename + _parse_handoff_filename are inverses
    on UTC datetimes with microsecond precision."""
    now = datetime(2026, 5, 7, 14, 30, 5, 123456, tzinfo=UTC)
    name = _format_handoff_filename(now)
    parsed = _parse_handoff_filename(name)
    assert parsed == now


def test_format_handoff_filename_is_windows_safe() -> None:
    """The filename must not contain any character that's illegal on
    Windows filesystems — most importantly, no colons."""
    now = datetime(2026, 5, 7, 14, 30, 5, 123456, tzinfo=UTC)
    name = _format_handoff_filename(now)
    forbidden = '<>:"/\\|?*'
    for char in forbidden:
        assert char not in name, (
            f"filename {name!r} contains Windows-illegal character {char!r}"
        )


def test_format_handoff_filename_rejects_naive_datetime() -> None:
    """_format_handoff_filename refuses naive (tzinfo=None) datetimes —
    the Z suffix in the output would lie about UTC-ness."""
    naive = datetime(2026, 5, 7, 14, 30, 5, 123456)
    with pytest.raises(ValueError, match="UTC-aware"):
        _format_handoff_filename(naive)


def test_format_handoff_filename_rejects_non_utc_timezone() -> None:
    """_format_handoff_filename refuses non-UTC tz-aware datetimes —
    the Z suffix encodes UTC; passing EST would produce a misleading
    filename."""
    est = timezone(timedelta(hours=-5))
    non_utc = datetime(2026, 5, 7, 14, 30, 5, 123456, tzinfo=est)
    with pytest.raises(ValueError, match="UTC-aware"):
        _format_handoff_filename(non_utc)


def test_parse_handoff_filename_returns_none_on_unknown_format() -> None:
    """Files that don't match the convention return None — the
    directory-listing read filter relies on this for graceful handling
    of operator-placed README, .DS_Store, or partial-write files."""
    assert _parse_handoff_filename("README.md") is None
    assert _parse_handoff_filename(".DS_Store") is None
    assert _parse_handoff_filename("2026-05-07.md") is None  # too short
    assert _parse_handoff_filename("not-a-timestamp.md") is None


def test_parse_handoff_filename_lexical_order_matches_chronological() -> None:
    """Q8 abstraction-unit pin (F9.7.B): filesystem-by-timestamp
    correctness depends on ISO-timestamp lexical sort matching
    chronological order. Three timestamped filenames should sort
    lexically in the same order they sort chronologically."""
    earlier = datetime(2026, 5, 7, 10, 0, 0, 0, tzinfo=UTC)
    middle = datetime(2026, 5, 7, 12, 0, 0, 0, tzinfo=UTC)
    later = datetime(2026, 5, 7, 14, 0, 0, 0, tzinfo=UTC)
    names = [
        _format_handoff_filename(d) for d in (later, earlier, middle)
    ]
    assert sorted(names) == [
        _format_handoff_filename(earlier),
        _format_handoff_filename(middle),
        _format_handoff_filename(later),
    ]


# ---------------------------------------------------------------------------
# read_most_recent_handoff — cold-start, sort, malformed-filtering
# ---------------------------------------------------------------------------


def test_read_most_recent_handoff_returns_none_when_dir_missing(
    handoff_dir: Path,
) -> None:
    """If the directory doesn't exist (cold-start before first write),
    return None. Equivalent to no prior handoff."""
    assert not handoff_dir.exists()
    assert read_most_recent_handoff(handoff_dir) is None


def test_read_most_recent_handoff_returns_none_on_empty_dir(
    handoff_dir: Path,
) -> None:
    """Empty directory (operator pre-created but no handoff written)
    returns None."""
    handoff_dir.mkdir(parents=True)
    assert read_most_recent_handoff(handoff_dir) is None


def test_read_most_recent_handoff_returns_most_recent_by_timestamp(
    handoff_dir: Path,
) -> None:
    """Three handoffs written in chronological order; helper returns
    the most-recent one (last write wins)."""
    handoff_dir.mkdir(parents=True)
    earlier = datetime(2026, 5, 7, 10, 0, 0, 0, tzinfo=UTC)
    middle = datetime(2026, 5, 7, 12, 0, 0, 0, tzinfo=UTC)
    later = datetime(2026, 5, 7, 14, 0, 0, 0, tzinfo=UTC)
    for d, content in [
        (earlier, "first handoff"),
        (later, "third handoff"),
        (middle, "second handoff"),
    ]:
        (handoff_dir / _format_handoff_filename(d)).write_text(
            content, encoding="utf-8"
        )

    result = read_most_recent_handoff(handoff_dir)
    assert result is not None
    content, written_at = result
    assert content == "third handoff"
    assert written_at == later


def test_read_most_recent_handoff_skips_malformed_filenames(
    handoff_dir: Path,
) -> None:
    """Operator-placed README, .DS_Store, partial-write files are
    skipped rather than failing the read. Only files matching the
    timestamp-suffix convention participate."""
    handoff_dir.mkdir(parents=True)
    valid_at = datetime(2026, 5, 7, 14, 30, 0, 123456, tzinfo=UTC)
    valid_name = _format_handoff_filename(valid_at)
    (handoff_dir / valid_name).write_text("valid handoff", encoding="utf-8")
    (handoff_dir / "README.md").write_text("operator note", encoding="utf-8")
    (handoff_dir / ".DS_Store").write_text("", encoding="utf-8")
    (handoff_dir / "partial-write.tmp").write_text(
        "partial", encoding="utf-8"
    )

    result = read_most_recent_handoff(handoff_dir)
    assert result is not None
    content, written_at = result
    assert content == "valid handoff"
    assert written_at == valid_at


# ---------------------------------------------------------------------------
# write_pm_handoff — happy path, dir creation, collision, audit shape
# ---------------------------------------------------------------------------


async def test_write_pm_handoff_writes_file_and_returns_metadata(
    storage: SqliteStorage, handoff_dir: Path
) -> None:
    """Happy path: writes a file at <handoff_dir>/<iso-timestamp>.md;
    returns written_at + path + content_size."""
    fixed_now = datetime(2026, 5, 7, 14, 30, 5, 123456, tzinfo=UTC)
    out = await write_pm_handoff(
        storage=storage,
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        request=WritePmHandoffInput(content="# Handoff\n\nSome content."),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
        now=fixed_now,
    )

    expected_filename = _format_handoff_filename(fixed_now)
    expected_path = handoff_dir / expected_filename

    assert out.written_at == fixed_now
    assert out.path == str(expected_path)
    assert out.content_size == len("# Handoff\n\nSome content.")
    assert expected_path.exists()
    assert expected_path.read_text(encoding="utf-8") == "# Handoff\n\nSome content."


async def test_write_pm_handoff_creates_directory_if_missing(
    storage: SqliteStorage, handoff_dir: Path
) -> None:
    """Operator doesn't have to pre-create the handoff directory; the
    handler creates it on first write."""
    assert not handoff_dir.exists()
    out = await write_pm_handoff(
        storage=storage,
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        request=WritePmHandoffInput(content="content"),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
    )
    assert handoff_dir.exists()
    assert handoff_dir.is_dir()
    assert Path(out.path).exists()


async def test_write_pm_handoff_pm_only(
    storage: SqliteStorage, handoff_dir: Path
) -> None:
    """Permission gate rejects non-PM roles."""
    for bad_role in ("engineer", "implementer", "tester"):
        with pytest.raises(OrchestratorPermissionError):
            await write_pm_handoff(
                storage=storage,
                get_handoff_dir=_make_get_handoff_dir(handoff_dir),
                request=WritePmHandoffInput(content="content"),
                caller_agent_id=f"{bad_role}-agent",
                caller_role=bad_role,
            )

    # Directory wasn't created by any of the rejected calls
    assert not handoff_dir.exists()


async def test_write_pm_handoff_filename_collision_raises_validation_error(
    storage: SqliteStorage, handoff_dir: Path
) -> None:
    """If a file with the same microsecond-resolution filename already
    exists, the handler refuses to overwrite — surfaces the
    multi-PM-process or microsecond-collision case for operator
    visibility."""
    fixed_now = datetime(2026, 5, 7, 14, 30, 5, 123456, tzinfo=UTC)
    # First write succeeds
    await write_pm_handoff(
        storage=storage,
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        request=WritePmHandoffInput(content="first"),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
        now=fixed_now,
    )
    # Second write at same timestamp raises
    with pytest.raises(ValidationError, match="already exists"):
        await write_pm_handoff(
            storage=storage,
            get_handoff_dir=_make_get_handoff_dir(handoff_dir),
            request=WritePmHandoffInput(content="second"),
            caller_agent_id="pm-agent-1",
            caller_role="pm",
            now=fixed_now,
        )


async def test_write_pm_handoff_emits_audit_with_full_details(
    storage: SqliteStorage, handoff_dir: Path
) -> None:
    """Audit shape per F9.2 Q2-A: pm.compaction.handoff_written action;
    details capture path, filename, content_size, written_at."""
    fixed_now = datetime(2026, 5, 7, 14, 30, 5, 123456, tzinfo=UTC)
    content = "# Handoff at conserve\n\nfoo bar"
    out = await write_pm_handoff(
        storage=storage,
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        request=WritePmHandoffInput(content=content),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
        now=fixed_now,
    )

    rows = await storage.fetch_all(
        "SELECT actor, action, target, details_json FROM audit_log "
        "WHERE action = 'pm.compaction.handoff_written'"
    )
    assert len(rows) == 1
    row = rows[0]
    assert row["actor"] == "pm-agent-1"
    assert row["target"] == out.path
    details = json.loads(row["details_json"])
    assert details["path"] == out.path
    assert details["filename"] == _format_handoff_filename(fixed_now)
    assert details["content_size"] == len(content)
    assert details["written_at"] == fixed_now.isoformat()


# ---------------------------------------------------------------------------
# get_most_recent_handoff — handler-layer cold-start + permission
# ---------------------------------------------------------------------------


async def test_get_most_recent_handoff_cold_start_returns_none_fields(
    handoff_dir: Path,
) -> None:
    """Cold-start (no prior handoff) returns content=None, written_at=None.
    PM's prompt instructs handling: None means fresh session."""
    out = await get_most_recent_handoff(
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        caller_role="pm",
    )
    assert out.content is None
    assert out.written_at is None


async def test_get_most_recent_handoff_returns_most_recent(
    storage: SqliteStorage, handoff_dir: Path
) -> None:
    """Two writes; the second is returned as most-recent."""
    earlier = datetime(2026, 5, 7, 10, 0, 0, 0, tzinfo=UTC)
    later = datetime(2026, 5, 7, 14, 0, 0, 0, tzinfo=UTC)
    await write_pm_handoff(
        storage=storage,
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        request=WritePmHandoffInput(content="earlier handoff"),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
        now=earlier,
    )
    await write_pm_handoff(
        storage=storage,
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        request=WritePmHandoffInput(content="later handoff"),
        caller_agent_id="pm-agent-1",
        caller_role="pm",
        now=later,
    )

    out = await get_most_recent_handoff(
        get_handoff_dir=_make_get_handoff_dir(handoff_dir),
        caller_role="pm",
    )
    assert out.content == "later handoff"
    assert out.written_at == later


async def test_get_most_recent_handoff_pm_only(
    handoff_dir: Path,
) -> None:
    """Permission gate rejects non-PM roles."""
    for bad_role in ("engineer", "implementer", "tester"):
        with pytest.raises(OrchestratorPermissionError):
            await get_most_recent_handoff(
                get_handoff_dir=_make_get_handoff_dir(handoff_dir),
                caller_role=bad_role,
            )


# ---------------------------------------------------------------------------
# Settings.pm_handoff_dir — field default behavior
# ---------------------------------------------------------------------------


def test_settings_pm_handoff_dir_default_is_none(tmp_path: Path) -> None:
    """Settings.pm_handoff_dir default is None; main.py lifespan
    closure substitutes <data_dir>/pm-handoffs/."""
    s = Settings(data_dir=tmp_path)
    assert s.pm_handoff_dir is None


def test_settings_pm_handoff_dir_accepts_explicit_path(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Operator override via env var sets the field. Lifespan closure
    uses settings.pm_handoff_dir directly when not None."""
    monkeypatch.setenv(
        "MERIDIAN_ORCH_PM_HANDOFF_DIR", str(tmp_path / "custom-handoffs")
    )
    s = Settings(data_dir=tmp_path)
    assert s.pm_handoff_dir == tmp_path / "custom-handoffs"
