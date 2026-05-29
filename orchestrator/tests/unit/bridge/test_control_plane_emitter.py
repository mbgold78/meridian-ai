"""Unit tests for ``bridge.control_plane_emitter`` + its ``write_audit`` hook.

Coverage (per the daemon→CP emitter dispatch spec):

a. Emitter no-ops and spawns NO HTTP request when the CP URL is unset.
b. A sample audit action maps to the correct CP ``event_type`` + payload
   and POSTs to the right endpoint (httpx monkeypatched).
c. An httpx error inside the detached POST task is swallowed and does NOT
   propagate.
d. ``write_audit`` still returns/writes its row normally when the emitter
   would raise (the hook never affects the audit-write contract).

The emitter is fire-and-forget: ``emit_event`` schedules the POST on a
detached ``asyncio`` task. Tests that assert on the POST await the
scheduled tasks (``asyncio.sleep(0)`` / gather) before checking.
"""

from __future__ import annotations

import asyncio
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any, ClassVar

import httpx
import pytest
import pytest_asyncio

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge import control_plane_emitter as cpe
from meridian_orchestrator.bridge.control_plane_emitter import (
    INGEST_PATH,
    build_event,
    emit_event,
)
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage

CP_URL = "http://127.0.0.1:8000"


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


class _RecordingClient:
    """Fake ``httpx.AsyncClient`` async-context-manager recording POSTs.

    Captures every ``post`` call's url + json. ``raise_on_post`` makes the
    POST raise to exercise the error-swallowing path.
    """

    calls: ClassVar[list[dict[str, Any]]] = []

    def __init__(
        self,
        *,
        status_code: int = 200,
        raise_on_post: bool = False,
        **_: Any,
    ) -> None:
        self._status_code = status_code
        self._raise_on_post = raise_on_post

    async def __aenter__(self) -> _RecordingClient:
        return self

    async def __aexit__(self, *exc: object) -> None:
        return None

    async def post(self, url: str, *, json: dict[str, Any]) -> httpx.Response:
        type(self).calls.append({"url": url, "json": json})
        if self._raise_on_post:
            raise httpx.ConnectError("boom")
        return httpx.Response(self._status_code, request=httpx.Request("POST", url))


@pytest.fixture
def recording_client(monkeypatch: pytest.MonkeyPatch) -> type[_RecordingClient]:
    """Monkeypatch the emitter's ``httpx.AsyncClient`` with the recorder and
    reset its call log."""
    _RecordingClient.calls = []

    def _factory(**kwargs: Any) -> _RecordingClient:
        return _RecordingClient(**kwargs)

    monkeypatch.setattr(cpe.httpx, "AsyncClient", _factory)
    return _RecordingClient


async def _drain_tasks() -> None:
    """Let detached emit tasks run to completion."""
    # Yield once so create_task'd coroutines start, then gather any that the
    # emitter scheduled on this loop.
    await asyncio.sleep(0)
    pending = [
        t
        for t in asyncio.all_tasks()
        if t is not asyncio.current_task() and not t.done()
    ]
    if pending:
        await asyncio.gather(*pending, return_exceptions=True)


# --- (a) no-op when URL unset ---------------------------------------------


async def test_emit_noops_and_spawns_no_request_when_url_unset(
    recording_client: type[_RecordingClient],
) -> None:
    emit_event(
        action="dispatch.merged",
        target="d-1",
        details={"dispatch_id": "d-1", "commit_hash": "abc123"},
        control_plane_url=None,
    )
    await _drain_tasks()
    assert recording_client.calls == []


async def test_emit_noops_for_high_frequency_skip_action(
    recording_client: type[_RecordingClient],
) -> None:
    emit_event(
        action="dispatch.tool_activity",
        target="d-1",
        details={"dispatch_id": "d-1", "tool_name": "Read"},
        control_plane_url=CP_URL,
    )
    await _drain_tasks()
    assert recording_client.calls == []


async def test_emit_noops_for_action_not_in_contract(
    recording_client: type[_RecordingClient],
) -> None:
    emit_event(
        action="pm.compaction.handoff_written",
        target="/tmp/handoff.md",
        details={"path": "/tmp/handoff.md"},
        control_plane_url=CP_URL,
    )
    await _drain_tasks()
    assert recording_client.calls == []


# --- (b) maps action → event_type + payload + endpoint ---------------------


async def test_emit_maps_action_and_posts_to_ingest_endpoint(
    recording_client: type[_RecordingClient],
) -> None:
    emit_event(
        action="dispatch.merged",
        target="d-42",
        details={"dispatch_id": "d-42", "commit_hash": "deadbeef", "branch": "x"},
        control_plane_url=CP_URL,
    )
    await _drain_tasks()

    assert len(recording_client.calls) == 1
    call = recording_client.calls[0]
    assert call["url"] == CP_URL + INGEST_PATH
    body = call["json"]
    assert body["event_type"] == "dispatch.merged"
    assert body["source"] == "daemon"
    assert body["dispatch_id"] == "d-42"
    # Contract payload for dispatch.merged: dispatch_id, commit_hash.
    assert body["payload"]["dispatch_id"] == "d-42"
    assert body["payload"]["commit_hash"] == "deadbeef"
    # event_id + timestamp present; received_at intentionally absent.
    assert "event_id" in body and "timestamp" in body
    assert "received_at" not in body


def test_build_event_task_status_change_fans_out_to_terminal_types() -> None:
    completed = build_event(
        action="task.status_change",
        target="t-1",
        details={"task_id": "t-1", "to_status": "completed", "notes": "done"},
    )
    failed = build_event(
        action="task.status_change",
        target="t-1",
        details={"task_id": "t-1", "to_status": "failed"},
    )
    declined = build_event(
        action="task.status_change",
        target="t-1",
        details={"task_id": "t-1", "to_status": "declined"},
    )
    in_progress = build_event(
        action="task.status_change",
        target="t-1",
        details={"task_id": "t-1", "to_status": "in_progress"},
    )
    assert completed is not None and completed["event_type"] == "task.completed"
    assert completed["payload"]["new_status"] == "completed"
    assert completed["payload"]["notes"] == "done"
    assert failed is not None and failed["event_type"] == "task.failed"
    assert declined is not None and declined["event_type"] == "task.declined"
    # Non-terminal transitions are not in the CP enum → generic progress signal.
    assert in_progress is not None
    assert in_progress["event_type"] == "dispatch.progress_signal"


def test_build_event_missing_projector_action_maps_to_progress_signal() -> None:
    event = build_event(
        action="dispatch.token_revoked",
        target="d-9",
        details={"dispatch_id": "d-9", "token_id": "tok-9", "reason": "shutdown"},
    )
    assert event is not None
    assert event["event_type"] == "dispatch.progress_signal"
    assert event["payload"]["dispatch_id"] == "d-9"
    assert event["payload"]["token_id"] == "tok-9"
    # audit_action is surfaced for downstream disambiguation.
    assert event["payload"]["audit_action"] == "dispatch.token_revoked"


def test_build_event_returns_none_for_skip_and_unknown() -> None:
    assert build_event(action="daemon.healthy", target=None, details={}) is None
    assert (
        build_event(action="dispatch.progress_signal", target=None, details={})
        is None
    )
    assert build_event(action="totally.unknown", target=None, details={}) is None


def test_build_event_task_submit_maps_to_task_submitted() -> None:
    event = build_event(
        action="task.submit",
        target="t-7",
        details={
            "task_id": "t-7",
            "agent_role": "engineer",
            "description": "do the thing",
            "priority": 1,
            "complexity": "medium",
            "task_type": "feature",
            "submitted_by": "pm",
            "caller_source": "mcp",
            "extra_field_dropped": "ignored",
        },
    )
    assert event is not None
    assert event["event_type"] == "task.submitted"
    assert event["task_id"] == "t-7"
    p = event["payload"]
    assert p["agent_role"] == "engineer"
    assert p["task_type"] == "feature"
    assert p["submitted_by"] == "pm"
    assert p["caller_source"] == "mcp"


# --- (c) httpx error inside POST is swallowed ------------------------------


async def test_post_error_is_swallowed_and_does_not_propagate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def _factory(**kwargs: Any) -> _RecordingClient:
        return _RecordingClient(raise_on_post=True, **kwargs)

    monkeypatch.setattr(cpe.httpx, "AsyncClient", _factory)
    _RecordingClient.calls = []

    # emit_event itself must not raise.
    emit_event(
        action="dispatch.failed",
        target="d-err",
        details={"dispatch_id": "d-err", "reason": "boom"},
        control_plane_url=CP_URL,
    )
    # Draining the detached task must also not raise — the error is swallowed
    # inside _post_event. gather(return_exceptions) would mask a propagating
    # error, so assert no task ended in an unhandled exception.
    await asyncio.sleep(0)
    pending = [
        t
        for t in asyncio.all_tasks()
        if t is not asyncio.current_task() and not t.done()
    ]
    results = await asyncio.gather(*pending, return_exceptions=True)
    assert all(not isinstance(r, BaseException) for r in results)
    # The POST was attempted (recorded before raising).
    assert len(_RecordingClient.calls) == 1


async def test_emit_swallows_non_2xx_response(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def _factory(**kwargs: Any) -> _RecordingClient:
        return _RecordingClient(status_code=500, **kwargs)

    monkeypatch.setattr(cpe.httpx, "AsyncClient", _factory)
    _RecordingClient.calls = []

    emit_event(
        action="dispatch.merged",
        target="d-5",
        details={"dispatch_id": "d-5", "commit_hash": "c"},
        control_plane_url=CP_URL,
    )
    await _drain_tasks()
    # Request attempted; no exception surfaced.
    assert len(_RecordingClient.calls) == 1


# --- (d) write_audit is unaffected when the emitter raises -----------------


async def test_write_audit_returns_normally_when_emitter_internals_raise(
    storage: SqliteStorage, monkeypatch: pytest.MonkeyPatch
) -> None:
    """When the emitter's internals blow up (here: ``build_event`` raises),
    the REAL ``emit_event`` swallows it, so ``write_audit`` still writes its
    row and returns the audit id.

    This is the load-bearing guarantee: the CP mirror can never break the
    audit write. We force a failure deep inside ``emit_event`` (not at its
    call site) so the path through the real defensive wrapper is exercised."""
    monkeypatch.setenv("MERIDIAN_ORCH_CONTROL_PLANE_URL", CP_URL)

    def _exploding_build(**_: Any) -> dict[str, Any]:
        raise RuntimeError("build_event exploded")

    # Patch build_event as referenced inside the emitter module so the real
    # emit_event's try/except has to catch it.
    monkeypatch.setattr(cpe, "build_event", _exploding_build)

    audit_id = await write_audit(
        storage,
        actor="system",
        action="dispatch.merged",
        target="d-2",
        details={"dispatch_id": "d-2", "commit_hash": "def"},
    )

    # write_audit returned normally despite the emitter internals exploding.
    assert isinstance(audit_id, str) and len(audit_id) == 32
    row = await storage.fetch_one(
        "SELECT action, target FROM audit_log WHERE id = ?", (audit_id,)
    )
    assert row is not None
    assert row["action"] == "dispatch.merged"
    assert row["target"] == "d-2"


async def test_write_audit_with_real_emitter_unset_url_writes_row(
    storage: SqliteStorage, monkeypatch: pytest.MonkeyPatch
) -> None:
    """End-to-end through the real emit_event hook with the CP URL unset:
    the row is written, no HTTP attempted, write_audit returns the id."""
    monkeypatch.delenv("MERIDIAN_ORCH_CONTROL_PLANE_URL", raising=False)

    # Guard: if the real emitter ever tried to POST, this would record it.
    calls: list[Any] = []

    def _factory(**kwargs: Any) -> _RecordingClient:
        calls.append(kwargs)
        return _RecordingClient(**kwargs)

    monkeypatch.setattr(cpe.httpx, "AsyncClient", _factory)

    audit_id = await write_audit(
        storage,
        actor="pm",
        action="task.claim",
        target="t-1",
        details={"task_id": "t-1", "claimed_by": "engineer"},
    )
    await _drain_tasks()

    assert isinstance(audit_id, str)
    assert calls == []  # URL unset → no client constructed
    row = await storage.fetch_one(
        "SELECT action FROM audit_log WHERE id = ?", (audit_id,)
    )
    assert row is not None and row["action"] == "task.claim"


async def test_write_audit_with_real_emitter_set_url_mirrors_event(
    storage: SqliteStorage,
    monkeypatch: pytest.MonkeyPatch,
    recording_client: type[_RecordingClient],
) -> None:
    """End-to-end: CP URL set via env → write_audit mirrors the mapped event
    to the ingest endpoint while still writing its row."""
    monkeypatch.setenv("MERIDIAN_ORCH_CONTROL_PLANE_URL", CP_URL)

    audit_id = await write_audit(
        storage,
        actor="system",
        action="dispatch.subprocess_started",
        target="d-3",
        details={"dispatch_id": "d-3", "pid": 4321, "cwd": "/work/tree"},
    )
    await _drain_tasks()

    assert isinstance(audit_id, str)
    assert len(recording_client.calls) == 1
    body = recording_client.calls[0]["json"]
    assert body["event_type"] == "dispatch.subprocess_started"
    assert body["dispatch_id"] == "d-3"
    assert body["payload"]["subprocess_pid"] == 4321
    assert body["payload"]["working_directory"] == "/work/tree"
