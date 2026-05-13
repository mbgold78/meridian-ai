"""PM continuous-mode loop driver tools.

M5-continuous-pm-driver commit 7 ships the architectural keystone for
PM's continuous loop: two MCP tools that PM's prompt-driven iteration
sequence calls each tick.

Architectural shape (per F8.6 dispositions, 2026-05-06):

- :func:`get_loop_state` — read aggregator. PM calls once per
  iteration to bundle the per-tick state reads (subscription_5h
  + subscription_7d_opus window state + global burn rate + active
  pm_current_focus). Reduces per-iteration MCP read count from 3-4
  to 1 (F8.2 Q3 Reading A). Read-side aggregation is asymmetric vs
  write-side (Q10 Reading B): reads share point-in-time freshness
  semantics; writes have distinct audit-trail semantics that
  bundling would conflate.
- :func:`wait_for_next_tick` — server-side blocking. PM calls at end
  of each iteration to wait for the next tick window. Cadence
  configured via ``Settings.pm_loop_tick_seconds`` (default 60s
  per F8.2 Q2-B; conservative-then-tune-down framing). Daemon-
  controlled cadence; PM-prompt-driven call pattern.

  **First server-side blocking MCP tool in Phase 1** (per F8.2 Q1-A
  novel-pattern flag, BACKLOG.md § Architectural patterns to review).
  Pattern review triggers when second instance lands.

**Permission**: PM-only on both tools. PM-continuous subprocess is
the load-bearing caller; Mike's interactive PM session governed by
client-side defaults (per opus-4-7-effort-and-thinking-addendum.md §
Mike's interactive PM session) — interactive PM doesn't run the
continuous-loop iteration sequence.

**Architectural separation maintained** (per F8.6.A Reading D
disposition): bridge/pm_supervisor.py is unchanged at this commit.
Loop driver lives entirely in tools/pm_loop.py + pm.yaml prompt
extension. Supervisor stays at lifecycle-scope (spawn, watchdog,
token, activity, respawn); loop iteration scope lives subprocess-
side via PM's prompt-driven calls to these tools.

**Cadence config flow**: ``Settings.pm_loop_tick_seconds`` →
``main.py`` lifespan reads → ``get_loop_tick_seconds`` closure →
``server.py`` ``_register_pm_loop_tools`` consumes via
closure-captured callable (mirrors ``get_oauth_usage_snapshot`` /
``Sampler`` instance precedents from prior commits). No new
bridge → tools imports.

**Test seam**: monkeypatch ``asyncio.sleep`` in tests for
deterministic cadence assertions (per F8.2 Q6-A; doesn't expand
production-code-surface for testability).
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable

from pydantic import BaseModel

from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage
from meridian_orchestrator.tools.budget import (
    GetBurnRateInput,
    GetBurnRateOutput,
    GetWindowStateInput,
    GetWindowStateOutput,
    SnapshotProvider,
    get_burn_rate,
    get_window_state,
)
from meridian_orchestrator.tools.pm_focus import (
    PmCurrentFocus,
    read_current_focus,
)

# ----- Pydantic schemas ----------------------------------------------------


class WaitForNextTickOutput(BaseModel):
    """Output for ``wait_for_next_tick``. Returns the actual sleep
    duration so callers can verify cadence (and tests can assert
    on the value)."""

    slept_seconds: float


class GetLoopStateOutput(BaseModel):
    """Aggregated per-iteration state for PM's continuous loop.

    Bundles four reads PM's prompt-driven iteration sequence consumes:
    - ``subscription_5h_state``: pace recommendation for primary
      pacing counter (PM stays alive while utilization < conserve
      threshold; compaction-prep at conserve per F2 Section 5).
      Also carries ``current_pm_mode`` + ``most_recent_pm_mode_log_timestamp``
      for Mike-presence detection (F8.2 Q7-B disposition: continue
      iterating with ``mike_presence`` rationale_tag when interactive).
    - ``subscription_7d_opus_state``: routing-tier downgrade context
      (Opus → Sonnet downgrades when subscription_7d_opus near
      saturation per ``check_budget_for_task``).
    - ``burn_rate``: global subscription-window burn rate. Whole-
      subscription scope (PM/Engineer share); not role-scoped.
    - ``current_focus``: PM's active ``pm_current_focus`` row (or
      ``None`` if no active focus). Drives PM's "what am I working
      on" awareness across iterations.

    Decision-input scope per F8.2 Q8-B: pace_recommendation + Mike
    presence + current_focus state. Queue depth deferred to M4d
    (when ``get_queue`` ships). Recent dispatch outcomes
    (``get_recent_dispatches``) NOT bundled — operator-facing
    diagnostic surface, not load-bearing per-iteration input.
    """

    subscription_5h_state: GetWindowStateOutput
    subscription_7d_opus_state: GetWindowStateOutput
    burn_rate: GetBurnRateOutput
    current_focus: PmCurrentFocus | None


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    """Mirror of pm_focus / pm_spin / audit.sampler permission gates."""
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Handlers ------------------------------------------------------------


async def wait_for_next_tick(
    *,
    get_tick_seconds: Callable[[], float],
    caller_role: str,
) -> WaitForNextTickOutput:
    """PM/Engineer-permitted handler for the ``wait_for_next_tick`` MCP tool.

    Both PM continuous-mode loop driver (M5-continuous-pm-driver
    commit 7) and Engineer-supervisor continuous-mode loop driver
    (M5-acceptance follow-on) call this for per-tick keepalive.

    Server-side blocking: sleeps for the configured tick interval
    then returns. PM's prompt instructs the call at end of each
    iteration to pace the loop.

    **Cadence resolution**: ``get_tick_seconds`` is a closure
    resolving to ``Settings.pm_loop_tick_seconds`` at call time
    (lifespan-time config; effectively static across daemon
    lifetime since Settings is constructed once at startup).

    **Test seam** (per F8.2 Q6-A): tests monkeypatch
    ``asyncio.sleep`` for deterministic cadence assertions. The
    handler doesn't expose an explicit clock-injection seam —
    monkeypatch is the established Python testing pattern and
    keeps production-code-surface clean (doesn't trip the
    testability watch-for-pattern).

    **Cancel-safety**: ``asyncio.sleep`` is cancellation-aware;
    if the daemon is shutting down or the supervisor's watchdog
    forces the subprocess to terminate, the sleep aborts cleanly.
    No special handling needed.

    **Novel pattern flag** (per BACKLOG.md § Architectural patterns
    to review): first server-side blocking MCP tool in Phase 1.
    Pattern review triggers when second instance lands.
    """
    _require_role(caller_role, "pm", "engineer")
    tick_seconds = get_tick_seconds()
    await asyncio.sleep(tick_seconds)
    return WaitForNextTickOutput(slept_seconds=tick_seconds)


async def get_loop_state(
    *,
    storage: Storage,
    get_snapshot: SnapshotProvider,
    caller_agent_id: str,
    caller_role: str,
) -> GetLoopStateOutput:
    """PM/Engineer-permitted handler for the ``get_loop_state`` MCP tool.

    Both PM continuous-mode loop driver (M5-continuous-pm-driver
    commit 7) and Engineer-supervisor continuous-mode loop driver
    (M5-acceptance follow-on) call this for aggregated per-tick
    iteration state.

    Aggregates four per-iteration reads into one MCP response.
    Each underlying read is delegated to its existing handler:
    - ``get_window_state(bucket="subscription_5h")``
    - ``get_window_state(bucket="subscription_7d_opus")``
    - ``get_burn_rate(window="current")``
    - ``read_current_focus`` (Python helper, no MCP wrapper called)

    The read-aggregation pattern is asymmetric vs write-side per
    F8.2 Q3-A vs Q10-B: reads share point-in-time freshness
    semantics (all four are "snapshot at the moment of the call");
    writes have distinct audit-trail semantics (each write is its
    own durable record; bundling would conflate).

    **No new closed-enum sentinels** — output schema composes
    existing types. Failure paths (snapshot cold-start, stale
    snapshot, etc.) propagate from the underlying handlers'
    well-tested branches.

    **No tools/ → tools/ cross-layer concern** — this is intra-tools
    composition, not cross-layer (pm_loop.py imports budget.py +
    pm_focus.py within the same tools/ layer). Bridge → tools watch
    is unaffected.
    """
    _require_role(caller_role, "pm", "engineer")

    subscription_5h_state = await get_window_state(
        storage=storage,
        get_snapshot=get_snapshot,
        request=GetWindowStateInput(bucket="subscription_5h"),
        caller_agent_id=caller_agent_id,
        caller_role=caller_role,
    )
    subscription_7d_opus_state = await get_window_state(
        storage=storage,
        get_snapshot=get_snapshot,
        request=GetWindowStateInput(bucket="subscription_7d_opus"),
        caller_agent_id=caller_agent_id,
        caller_role=caller_role,
    )
    burn_rate = await get_burn_rate(
        storage=storage,
        get_snapshot=get_snapshot,
        request=GetBurnRateInput(window="current"),
        caller_role=caller_role,
    )
    current_focus = await read_current_focus(storage)

    return GetLoopStateOutput(
        subscription_5h_state=subscription_5h_state,
        subscription_7d_opus_state=subscription_7d_opus_state,
        burn_rate=burn_rate,
        current_focus=current_focus,
    )


__all__ = [
    "GetLoopStateOutput",
    "WaitForNextTickOutput",
    "get_loop_state",
    "wait_for_next_tick",
]
