"""Shared pytest fixtures.

The ``keyring_memory`` fixture replaces the global keyring backend with an
in-memory dict for the duration of a test. We never want unit or integration
tests to read or write Mike's actual Windows Credential Manager.
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
