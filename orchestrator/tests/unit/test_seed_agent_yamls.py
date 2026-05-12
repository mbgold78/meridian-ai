"""Unit tests for ``meridian_orchestrator.main._seed_agent_yamls_if_runtime_empty``.

Designed-shape policy under test (BACKLOG entry "Agent yaml runtime-dir
auto-seeding"): "if directory doesn't exist OR is empty → copy;
respect operator's runtime edits." Three scenarios cover the policy
boundary:

- First boot with empty runtime → all source yamls land at target;
  ``agents_dir.seeded`` event with ``files_copied`` list.
- Subsequent boot with operator-edited runtime → no copy;
  ``agents_dir.seed_skipped`` event; operator content preserved
  byte-for-byte.
- First boot on a data dir with no ``config/`` subdirectory → parent
  chain created idempotently then copy proceeds.

The ``_SEED_AGENT_YAMLS_SOURCE_DIR`` module-level constant is
monkeypatched to a tmp-built fake source so the tests are not coupled
to the live repo's ``config/agents/`` file set (which may change across
commits) and don't write into Mike's real data dir.
"""

from __future__ import annotations

from pathlib import Path

import pytest
import structlog.testing

from meridian_orchestrator import main as main_module
from meridian_orchestrator.config import Settings


def _build_fake_source(tmp_path: Path, names: list[str]) -> Path:
    src = tmp_path / "fake-source"
    src.mkdir()
    for name in names:
        (src / name).write_text(f"# fake {name}\n", encoding="utf-8")
    return src


def test_seed_copies_into_empty_runtime_dir(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """First-boot path: ``agents_dir`` doesn't exist; all source yamls
    land at target; ``agents_dir.seeded`` event fires with the sorted
    ``files_copied`` list + ``source_dir`` + ``target_dir``."""
    source = _build_fake_source(
        tmp_path,
        ["engineer.yaml", "implementer.yaml", "pm.yaml", "tester.yaml"],
    )
    monkeypatch.setattr(
        main_module, "_SEED_AGENT_YAMLS_SOURCE_DIR", source
    )
    settings = Settings(data_dir=tmp_path / "data")
    assert not settings.agents_dir.exists()

    with structlog.testing.capture_logs() as captured:
        main_module._seed_agent_yamls_if_runtime_empty(settings)

    target = settings.agents_dir
    assert target.is_dir()
    assert sorted(p.name for p in target.iterdir()) == [
        "engineer.yaml",
        "implementer.yaml",
        "pm.yaml",
        "tester.yaml",
    ]
    seeded_events = [
        e for e in captured if e.get("event") == "agents_dir.seeded"
    ]
    assert len(seeded_events) == 1
    evt = seeded_events[0]
    assert evt["source_dir"] == str(source)
    assert evt["target_dir"] == str(target)
    assert evt["files_copied"] == [
        "engineer.yaml",
        "implementer.yaml",
        "pm.yaml",
        "tester.yaml",
    ]
    assert not [
        e for e in captured if e.get("event") == "agents_dir.seed_skipped"
    ]


def test_seed_skips_when_runtime_dir_non_empty(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """Idempotency / operator-edit preservation: a pre-existing entry
    blocks the copy; ``agents_dir.seed_skipped`` fires with
    ``files_observed`` count; operator content survives byte-for-byte
    and the un-shipped source yaml is NOT copied in."""
    source = _build_fake_source(tmp_path, ["engineer.yaml", "pm.yaml"])
    monkeypatch.setattr(
        main_module, "_SEED_AGENT_YAMLS_SOURCE_DIR", source
    )
    settings = Settings(data_dir=tmp_path / "data")
    settings.agents_dir.mkdir(parents=True)
    operator_edited = settings.agents_dir / "pm.yaml"
    operator_payload = "# operator edit; do not overwrite\n"
    operator_edited.write_text(operator_payload, encoding="utf-8")

    with structlog.testing.capture_logs() as captured:
        main_module._seed_agent_yamls_if_runtime_empty(settings)

    assert operator_edited.read_text(encoding="utf-8") == operator_payload
    assert not (settings.agents_dir / "engineer.yaml").exists()
    skipped_events = [
        e for e in captured if e.get("event") == "agents_dir.seed_skipped"
    ]
    assert len(skipped_events) == 1
    evt = skipped_events[0]
    assert evt["target_dir"] == str(settings.agents_dir)
    assert evt["files_observed"] == 1
    assert not [
        e for e in captured if e.get("event") == "agents_dir.seeded"
    ]


def test_seed_creates_parents_for_nonexistent_runtime_dir(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """First boot on a data dir with no ``config/`` subdirectory: the
    function creates the full ``data_dir/config/agents`` chain via
    ``mkdir(parents=True)`` before copying. Exercises the case where
    neither the immediate parent nor the runtime dir exist
    (``data_dir`` itself may also be absent — daemon's responsibility
    to create it on first boot)."""
    source = _build_fake_source(tmp_path, ["engineer.yaml"])
    monkeypatch.setattr(
        main_module, "_SEED_AGENT_YAMLS_SOURCE_DIR", source
    )
    fresh_data_dir = tmp_path / "fresh-data-dir"
    settings = Settings(data_dir=fresh_data_dir)
    assert not fresh_data_dir.exists()

    main_module._seed_agent_yamls_if_runtime_empty(settings)

    assert (fresh_data_dir / "config" / "agents" / "engineer.yaml").is_file()
