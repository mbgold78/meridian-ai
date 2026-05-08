"""Unit tests for ``meridian_orchestrator.config.Settings``.

Coverage at M5-flag-flips commit 5: the production-default flip for
``enable_engineer_supervisor`` (False → True) activates the
``substrate_working_directory`` validator on every ``Settings()`` call
that doesn't override the flag. The autouse
``_default_supervisor_flags_off_for_tests`` fixture in
``tests/conftest.py`` masks this for every other test by setenv'ing
the flag to ``"false"``; this file's tests explicitly clear the
autouse override via ``monkeypatch.delenv`` so the production-default
behavior is exercised in isolation.

This is the only place in the test suite where the production
flag-on default is asserted directly. Without these tests, a future
revert of the flag flip (or a Pydantic-settings config change that
silently breaks the env-var precedence) would not surface in CI —
every other test runs under the autouse-masked default and would
pass either way.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from meridian_orchestrator.config import Settings


def _clear_supervisor_flag_overrides(monkeypatch: pytest.MonkeyPatch) -> None:
    """Strip the conftest autouse fixture's env-var override so the
    ``Settings`` field defaults take effect (production-shape
    behavior).

    Without this, ``Settings()`` reads
    ``MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=false`` from the
    autouse fixture and the substrate-working-directory validator
    never fires — masking the very behavior these tests assert.
    """
    monkeypatch.delenv("MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR", raising=False)
    monkeypatch.delenv("MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR", raising=False)


def test_production_default_enables_engineer_supervisor(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """M5-flag-flips commit 5 contract: the production default for
    ``enable_engineer_supervisor`` is ``True``. With the flag on AND
    ``substrate_working_directory`` provided (canonical-clone path),
    ``Settings()`` constructs successfully and exposes the on state.
    """
    _clear_supervisor_flag_overrides(monkeypatch)
    settings = Settings(
        data_dir=tmp_path,
        substrate_working_directory=tmp_path,
    )
    assert settings.enable_engineer_supervisor is True
    assert settings.substrate_working_directory == tmp_path


def test_production_default_validator_requires_substrate_working_directory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """M5-flag-flips commit 5 contract: with the flag on (production
    default) AND ``substrate_working_directory`` unset, the
    ``_require_substrate_working_directory_when_supervisor_enabled``
    validator raises ``ValidationError`` on ``Settings()``
    construction. This is the production fail-fast surface the
    conftest autouse fixture masks for every other test; pinning it
    here ensures a future revert of the flag flip surfaces in CI
    rather than silently passing under autouse-masked defaults.
    """
    _clear_supervisor_flag_overrides(monkeypatch)
    with pytest.raises(
        ValidationError, match="MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY"
    ):
        Settings(data_dir=tmp_path)


def test_explicit_flag_off_bypasses_substrate_validator(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """M5-flag-flips commit 5 contract: explicit
    ``enable_engineer_supervisor=False`` constructor kwarg bypasses
    the substrate-working-directory validator regardless of whether
    ``substrate_working_directory`` is provided. This is the
    semantic the conftest autouse fixture relies on — verifies the
    bypass works via constructor kwarg, mirror of the env-var path
    below.
    """
    _clear_supervisor_flag_overrides(monkeypatch)
    settings = Settings(
        data_dir=tmp_path,
        enable_engineer_supervisor=False,
    )
    assert settings.enable_engineer_supervisor is False
    assert settings.substrate_working_directory is None


def test_env_var_flag_off_bypasses_substrate_validator(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """M5-flag-flips commit 5 contract: env-var override
    ``MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=false`` (the mechanism
    the conftest autouse fixture uses) bypasses the validator the
    same way an explicit constructor kwarg does. Pins the symmetric
    behavior so a future Pydantic-settings config change that
    silently breaks the env-var path doesn't silently break the
    autouse fixture's contract for the entire test suite.
    """
    _clear_supervisor_flag_overrides(monkeypatch)
    monkeypatch.setenv("MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR", "false")
    settings = Settings(data_dir=tmp_path)
    assert settings.enable_engineer_supervisor is False
