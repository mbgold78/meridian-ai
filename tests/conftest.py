"""Shared pytest fixtures.

The ``keyring_memory`` fixture replaces the global keyring backend with an
in-memory dict for the duration of a test. We never want unit or integration
tests to read or write Mike's actual Windows Credential Manager.

The ``_default_supervisor_flags_off_for_tests`` fixture defaults the
``enable_engineer_supervisor`` and ``enable_pm_supervisor`` flags to
``false`` at the env-var layer for every test. Production defaults
flipped to ``true`` at M5-flag-flips commit 5 (engineer-supervisor) and
commit 6 (pm-supervisor); the engineer-supervisor flipped default
activates the substrate_working_directory validator on every
``Settings()`` call (the pm-supervisor flag has no validator
dependency per `config.py` § enable_pm_supervisor). Tests that don't exercise the canonical-clone
provisioning path (the vast majority — they construct supervisors
directly via the class API or bypass the lifespan body) shouldn't need
to thread substrate_working_directory through every fixture. The
autouse env-var override restores the pre-flag-flip test-time
behavior; tests that DO want the flag-on path opt in explicitly via
``monkeypatch.setenv("MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR",
"true")`` plus their own substrate_working_directory wiring.

Operational verification of the flag-on path — the supervisor
lifecycle exercising canonical-clone provisioning + Engineer
subprocess on real Anthropic API calls — lives at M5-acceptance β,
not in unit/integration tests. The test surface here covers
code-shape correctness; β covers operational behavior under real
load. Reference: ``project_m5_engineer_supervisor_flag_flip`` memory
§ verification checklist (subscription_5h attribution,
compaction-at-80%, heartbeat calibration).
"""

from __future__ import annotations

from collections.abc import Iterator

import keyring
import pytest
from keyring.backend import KeyringBackend


class _MemoryKeyring(KeyringBackend):
    """Process-local in-memory keyring. Not thread-safe — fine for pytest."""

    priority = 1.0  # type: ignore[assignment]

    def __init__(self) -> None:
        self._store: dict[tuple[str, str], str] = {}

    def get_password(self, service: str, username: str) -> str | None:
        return self._store.get((service, username))

    def set_password(self, service: str, username: str, password: str) -> None:
        self._store[(service, username)] = password

    def delete_password(self, service: str, username: str) -> None:
        try:
            del self._store[(service, username)]
        except KeyError as exc:
            from keyring.errors import PasswordDeleteError

            raise PasswordDeleteError(str(exc)) from exc


@pytest.fixture(autouse=True)
def keyring_memory() -> Iterator[_MemoryKeyring]:
    """Swap in a fresh in-memory keyring for every test, restore afterward."""
    previous = keyring.get_keyring()
    backend = _MemoryKeyring()
    keyring.set_keyring(backend)
    try:
        yield backend
    finally:
        keyring.set_keyring(previous)


@pytest.fixture(autouse=True)
def _default_supervisor_flags_off_for_tests(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Default the supervisor enable flags to ``false`` at the env-var
    layer for every test (M5-flag-flips commit 5 + future commit 6).

    Production default for ``enable_engineer_supervisor`` flipped to
    ``True`` at commit 5; the flipped default activates the
    ``substrate_working_directory`` validator on every ``Settings()``
    call. Tests that don't exercise canonical-clone provisioning would
    otherwise need to thread ``substrate_working_directory`` through
    every fixture or pass an explicit ``enable_engineer_supervisor=False``
    constructor kwarg. The autouse env-var override restores the
    pre-flag-flip test-time behavior so existing fixtures keep working
    without churn.

    Tests that DO want the flag-on path opt in explicitly via
    ``monkeypatch.setenv("MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR",
    "true")`` plus their own ``substrate_working_directory`` wiring;
    pydantic-settings constructor kwargs and explicit env-var setenv
    both override this default.

    Same pattern applied to ``enable_pm_supervisor`` for the upcoming
    commit-6 flag flip — once that commit lands the autouse default
    will already be in place; no test-side churn at that commit.
    """
    # See M5-flag-flips commit 5+6 dispositions for reasoning; production
    # defaults flipped at those commits (engineer-supervisor at 5;
    # pm-supervisor at 6). Operational verification of the flag-on paths
    # lives at M5-acceptance β, not unit/integration tests.
    monkeypatch.setenv("MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR", "false")
    monkeypatch.setenv("MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR", "false")
