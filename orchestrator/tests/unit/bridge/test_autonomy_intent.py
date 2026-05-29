"""Unit tests for ``bridge.autonomy_intent`` — the best-effort daemon → CP
autonomy-intent read consumed by the liveness watchdog's mode gating.

Coverage:

a. No CP URL configured -> returns None (fail open), no HTTP request.
b. Happy path: parses the agreed ``{"projects": [...]}`` shape into a
   snapshot keyed by project_id; Frozen/Manual rows are ``rules_suppressed``.
c. A non-2xx response -> None (fail open).
d. An httpx error -> None (fail open), swallowed.
e. Malformed / partial JSON -> tolerant parse (no raise; absent rows simply
   not present in the snapshot).
f. ``is_suppressed`` fail-open semantics for unknown / missing projects.
"""

from __future__ import annotations

from typing import Any, ClassVar

import httpx
import pytest

from meridian_orchestrator.bridge import autonomy_intent as ai
from meridian_orchestrator.bridge.autonomy_intent import (
    INTENT_PATH,
    AutonomyIntentSnapshot,
    ProjectIntent,
    fetch_autonomy_intent,
)

CP_URL = "http://127.0.0.1:8000"


class _RecordingClient:
    """Fake ``httpx.AsyncClient`` recording GETs and returning a canned body."""

    calls: ClassVar[list[str]] = []

    def __init__(
        self,
        *,
        status_code: int = 200,
        body: Any = None,
        raise_on_get: bool = False,
        **_: Any,
    ) -> None:
        self._status_code = status_code
        self._body = body if body is not None else {"projects": []}
        self._raise_on_get = raise_on_get

    async def __aenter__(self) -> _RecordingClient:
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    async def get(self, url: str) -> httpx.Response:
        type(self).calls.append(url)
        if self._raise_on_get:
            raise httpx.ConnectError("boom")
        return httpx.Response(
            self._status_code,
            json=self._body,
            request=httpx.Request("GET", url),
        )


def _patch_client(
    monkeypatch: pytest.MonkeyPatch, **client_kwargs: Any
) -> type[_RecordingClient]:
    _RecordingClient.calls = []

    def _factory(**_kwargs: Any) -> _RecordingClient:
        return _RecordingClient(**client_kwargs)

    monkeypatch.setattr(ai.httpx, "AsyncClient", _factory)
    return _RecordingClient


# --- (a) unconfigured -------------------------------------------------------


async def test_no_url_returns_none_no_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    client = _patch_client(monkeypatch)
    result = await fetch_autonomy_intent(control_plane_url=None)
    assert result is None
    assert client.calls == []


# --- (b) happy path ---------------------------------------------------------


async def test_happy_path_parses_snapshot(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    body = {
        "projects": [
            {
                "projectId": "p-frozen",
                "autonomy": "Frozen",
                "callbackArmed": False,
                "callbackMechanism": "none",
            },
            {
                "projectId": "p-cont",
                "autonomy": "Continuous",
                "callbackArmed": True,
                "callbackMechanism": "pm.tick.continuous(60s)",
            },
            {
                "projectId": "p-manual",
                "autonomy": "Manual",
                "callbackArmed": False,
            },
        ]
    }
    client = _patch_client(monkeypatch, body=body)
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert client.calls == [CP_URL + INTENT_PATH]
    assert snap is not None
    assert snap.is_suppressed("p-frozen") is True
    assert snap.is_suppressed("p-manual") is True
    assert snap.is_suppressed("p-cont") is False
    assert snap.by_project["p-cont"].callback_armed is True
    assert (
        snap.by_project["p-cont"].callback_mechanism == "pm.tick.continuous(60s)"
    )


# --- (c) non-2xx ------------------------------------------------------------


async def test_non_2xx_fails_open(monkeypatch: pytest.MonkeyPatch) -> None:
    _patch_client(monkeypatch, status_code=503)
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert snap is None


# --- (d) httpx error --------------------------------------------------------


async def test_connect_error_swallowed_fails_open(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _patch_client(monkeypatch, raise_on_get=True)
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert snap is None


# --- (e) malformed / partial ------------------------------------------------


async def test_malformed_body_tolerated(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Not a dict -> empty snapshot.
    _patch_client(monkeypatch, body=["unexpected", "list"])
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert snap is not None
    assert snap.by_project == {}


async def test_partial_rows_skipped(monkeypatch: pytest.MonkeyPatch) -> None:
    body = {
        "projects": [
            {"autonomy": "Frozen"},  # no projectId -> skipped
            {"projectId": "p-ok", "autonomy": "Manual"},
            "not-a-dict",  # skipped
        ]
    }
    _patch_client(monkeypatch, body=body)
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert snap is not None
    assert list(snap.by_project) == ["p-ok"]
    assert snap.is_suppressed("p-ok") is True


# --- (f) is_suppressed fail-open semantics ----------------------------------


def test_is_suppressed_fail_open_for_unknown_and_missing() -> None:
    snap = AutonomyIntentSnapshot(
        by_project={
            "p-frozen": ProjectIntent(
                project_id="p-frozen", autonomy="Frozen", callback_armed=False
            )
        }
    )
    assert snap.is_suppressed("p-frozen") is True
    # Unknown project -> not suppressed (fail open).
    assert snap.is_suppressed("p-unknown") is False
    # No project id -> not suppressed.
    assert snap.is_suppressed(None) is False
    assert snap.is_suppressed("") is False


def test_unknown_mode_not_suppressed() -> None:
    intent = ProjectIntent(
        project_id="p", autonomy="Maintenance", callback_armed=False
    )
    assert intent.rules_suppressed is False
    intent_none = ProjectIntent(project_id="p2", autonomy=None, callback_armed=False)
    assert intent_none.rules_suppressed is False


# --- (g) ULTRACODE: by_canonical index + ultracode_for fail-closed ----------
# op-ultracode-daemon. The dispatcher reads the per-project ULTRACODE flag
# by canonical-clone name from the same intent snapshot, fail-CLOSED to
# False on every uncertainty (the inverse of is_suppressed's fail-open).


async def test_parse_reads_ultracode_and_canonical(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The parser reads ``ultracode`` + ``canonical`` tolerantly and builds
    the ``by_canonical`` index for rows that carry a canonical."""
    body = {
        "projects": [
            {
                "projectId": "p-on",
                "autonomy": "Continuous",
                "callbackArmed": True,
                "ultracode": True,
                "canonical": "curasync",
            },
            {
                "projectId": "p-off",
                "autonomy": "Guided",
                "callbackArmed": False,
                "ultracode": False,
                "canonical": "player-kb",
            },
        ]
    }
    _patch_client(monkeypatch, body=body)
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert snap is not None
    # Fields parsed onto ProjectIntent.
    assert snap.by_project["p-on"].ultracode is True
    assert snap.by_project["p-on"].canonical == "curasync"
    assert snap.by_project["p-off"].ultracode is False
    # by_canonical index populated for both.
    assert set(snap.by_canonical) == {"curasync", "player-kb"}
    # ultracode_for resolves by canonical.
    assert snap.ultracode_for("curasync") is True
    assert snap.ultracode_for("player-kb") is False


async def test_parse_defaults_ultracode_false_and_canonical_none(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Rows that omit ``ultracode``/``canonical`` default to False/None
    (fail closed). A canonical-less row stays out of the by_canonical
    index, so ultracode_for can never find it -> False."""
    body = {
        "projects": [
            {
                "projectId": "p-bare",
                "autonomy": "Continuous",
                "callbackArmed": True,
            }
        ]
    }
    _patch_client(monkeypatch, body=body)
    snap = await fetch_autonomy_intent(control_plane_url=CP_URL)
    assert snap is not None
    assert snap.by_project["p-bare"].ultracode is False
    assert snap.by_project["p-bare"].canonical is None
    assert snap.by_canonical == {}
    assert snap.ultracode_for("p-bare") is False


def test_ultracode_for_fail_closed_for_unknown_and_missing() -> None:
    """ultracode_for fails CLOSED: unknown canonical, empty, and None all
    return False even though the snapshot itself is non-None."""
    snap = AutonomyIntentSnapshot(
        by_canonical={
            "curasync": ProjectIntent(
                project_id="p",
                autonomy="Continuous",
                callback_armed=True,
                ultracode=True,
                canonical="curasync",
            )
        }
    )
    assert snap.ultracode_for("curasync") is True
    assert snap.ultracode_for("unknown-canonical") is False
    assert snap.ultracode_for(None) is False
    assert snap.ultracode_for("") is False


def test_ultracode_for_off_when_flag_false() -> None:
    """A known canonical whose ProjectIntent.ultracode is False reads off."""
    snap = AutonomyIntentSnapshot(
        by_canonical={
            "curasync": ProjectIntent(
                project_id="p",
                autonomy="Continuous",
                callback_armed=True,
                ultracode=False,
                canonical="curasync",
            )
        }
    )
    assert snap.ultracode_for("curasync") is False
