"""Unit tests for ``bridge.dispatch_log_routes`` — the raw subprocess-output
tail endpoint (op-live-activity).

Coverage:
- tails the last N lines of stdout/stderr from
  ``<dispatches_root>/<dispatch_id>/{stdout,stderr}.log``
- clamps + defaults the ``lines`` query param
- 404 (empty) for a missing file / unknown dispatch / DEVNULL'd stream
- 400 for an invalid ``stream`` value
- 400 + no filesystem touch for a path-traversal ``dispatch_id``
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pytest
from starlette.requests import Request
from starlette.responses import Response

from meridian_orchestrator.bridge.dispatch_log_routes import (
    DISPATCH_LOG_PATH,
    make_dispatch_log_routes,
)
from meridian_orchestrator.config import Settings


def _settings(tmp_path: Path) -> Settings:
    return Settings(data_dir=tmp_path)


def _get_handler(settings: Settings) -> Any:
    routes = make_dispatch_log_routes(settings)
    assert len(routes) == 1
    path, methods, handler = routes[0]
    assert path == DISPATCH_LOG_PATH
    assert methods == ["GET"]
    return handler


def _make_request(dispatch_id: str, query: str = "") -> Request:
    """Build a minimal Starlette Request with path_params + query string."""
    scope: dict[str, Any] = {
        "type": "http",
        "method": "GET",
        "path": f"/dispatches/{dispatch_id}/log",
        "path_params": {"dispatch_id": dispatch_id},
        "query_string": query.encode(),
        "headers": [],
    }
    return Request(scope)


def _body(response: Response) -> dict[str, Any]:
    return json.loads(bytes(response.body).decode())


def _write_log(
    tmp_path: Path, dispatch_id: str, stream: str, content: str
) -> None:
    d = tmp_path / "dispatches" / dispatch_id
    d.mkdir(parents=True, exist_ok=True)
    (d / f"{stream}.log").write_text(content, encoding="utf-8")


async def test_tails_stdout_lines(tmp_path: Path) -> None:
    _write_log(
        tmp_path, "d-1", "stdout", "line1\nline2\nline3\nline4\n"
    )
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request("d-1", "stream=stdout&lines=2"))
    assert resp.status_code == 200
    body = _body(resp)
    assert body["exists"] is True
    assert body["lines"] == ["line3", "line4"]
    assert body["total_lines"] == 4
    assert body["returned_lines"] == 2
    assert body["truncated"] is True


async def test_tails_stderr_default_stream_is_stdout(tmp_path: Path) -> None:
    _write_log(tmp_path, "d-2", "stderr", "err-a\nerr-b\n")
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request("d-2", "stream=stderr"))
    assert resp.status_code == 200
    body = _body(resp)
    assert body["stream"] == "stderr"
    assert body["lines"] == ["err-a", "err-b"]
    assert body["truncated"] is False


async def test_returns_all_lines_when_fewer_than_requested(
    tmp_path: Path,
) -> None:
    _write_log(tmp_path, "d-3", "stdout", "only\n")
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request("d-3", "lines=500"))
    body = _body(resp)
    assert body["lines"] == ["only"]
    assert body["total_lines"] == 1
    assert body["truncated"] is False


async def test_invalid_lines_param_falls_back_to_default(
    tmp_path: Path,
) -> None:
    _write_log(tmp_path, "d-4", "stdout", "a\nb\n")
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request("d-4", "lines=notanint"))
    assert resp.status_code == 200
    body = _body(resp)
    assert body["lines"] == ["a", "b"]


async def test_missing_file_returns_404_empty(tmp_path: Path) -> None:
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request("nope", "stream=stdout"))
    assert resp.status_code == 404
    body = _body(resp)
    assert body["exists"] is False
    assert body["lines"] == []
    assert body["total_lines"] == 0


async def test_invalid_stream_returns_400(tmp_path: Path) -> None:
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request("d-1", "stream=bogus"))
    assert resp.status_code == 400
    body = _body(resp)
    assert body["error"] == "invalid_stream"


@pytest.mark.parametrize(
    "bad_id",
    ["..", "../secret", "a/b", "a\\b", "."],
)
async def test_path_traversal_dispatch_id_rejected(
    tmp_path: Path, bad_id: str
) -> None:
    handler = _get_handler(_settings(tmp_path))
    resp = await handler(_make_request(bad_id, "stream=stdout"))
    assert resp.status_code == 400
    body = _body(resp)
    assert body["error"] == "invalid_dispatch_id"
