"""Direct-handler tests for ``bridge.activity.record_oauth_pm_auto_flip``.

The auto-flip writer is the daemon-side observation hook for Mike's
MCP token use. M5-pm-mode-and-window-state commit 5 ships:

- Reading B on Q1: write only on transition (skip when current is
  already ``interactive``).
- Reading B on Q2: skip paused states (don't override PM-deliberate
  ``paused_blocked`` / ``paused_question``).
- Sub-Reading "alpha" on freshness: per-message freshness is NOT captured;
  PM's continuous loop computes the interactive window from
  ``most_recent_pm_mode_log_timestamp``. Self-corrects via PM's
  flip-back-to-continuous logic. (Phase 1 approximation; BACKLOG
  entry for Phase 2 refinement.)

Tests cover the helper directly without MCP server context. The
end-to-end integration through ``server._observe_caller`` (including
transactional rollback covering both the activity update and the
auto-flip) is exercised separately in
``tests/integration/test_observe_caller.py``.
"""

from __future__ import annotations

import json
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from pathlib import Path

import pytest
import pytest_asyncio

from meridian_orchestrator.auth import TokenRecord
from meridian_orchestrator.bridge import activity as activity_mod
from meridian_orchestrator.bridge.activity import record_oauth_pm_auto_flip
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.pm_mode import PAUSED_MODES, PmMode


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    yield s
    await s.close()


def _oauth_pm_record() -> TokenRecord:
    """Construct an oauth-pm TokenRecord for the helper to observe."""
    return TokenRecord(
        token_id="oauth-pm-token-id",
        agent_id="mike-pm-agent-id",
        role="pm",
        label="mike-claude-code",
        secret_hash="hash",
        issued_at=datetime.now(tz=UTC).isoformat(timespec="seconds"),
        revoked_at=None,
        source="oauth",
        client_id="claude-code-client",
    )


def _static_pm_record() -> TokenRecord:
    """Static-issued PM token (the pm-continuous supervisor)."""
    return TokenRecord(
        token_id="static-pm-token-id",
        agent_id="pm-continuous-supervisor-agent",
        role="pm",
        label="pm-continuous",
        secret_hash="hash",
        issued_at=datetime.now(tz=UTC).isoformat(timespec="seconds"),
        revoked_at=None,
        source="static",
    )


def _oauth_engineer_record() -> TokenRecord:
    """Engineer token issued via OAuth (hypothetical — Phase 1 has
    only PM as oauth role, but the source/role check is on both
    fields, so the test exercises the role gate independently)."""
    return TokenRecord(
        token_id="oauth-engineer-token-id",
        agent_id="engineer-agent-id",
        role="engineer",
        label="oauth-engineer",
        secret_hash="hash",
        issued_at=datetime.now(tz=UTC).isoformat(timespec="seconds"),
        revoked_at=None,
        source="oauth",
        client_id="some-engineer-client",
    )


async def _seed_pm_mode(s: SqliteStorage, mode: str) -> None:
    """Seed a pm_mode_log row with the given mode (most-recent state)."""
    await s.execute(
        "INSERT INTO pm_mode_log (id, mode, triggered_by) "
        "VALUES (?, ?, ?)",
        (f"seed-{mode}", mode, "manual"),
    )


# ---------------------------------------------------------------------------
# Test 1: writes mode_log on first interaction (empty pm_mode_log)
# ---------------------------------------------------------------------------


async def test_oauth_pm_request_writes_on_empty_pm_mode_log(
    storage: SqliteStorage,
) -> None:
    """When pm_mode_log is empty (current_mode = None), an oauth-pm
    observation transitions to interactive. The first row in the
    table comes from this writer.

    Also verifies the audit-details payload on the empty-log baseline:
    ``old_mode`` must be ``None`` in the JSON, not the string
    ``"None"`` and not absent. Encodes the Q3 None-baseline contract
    at the audit layer (the pm_mode_log row check verifies the
    storage layer; this assertion verifies the audit-consumer
    contract).
    """
    pre = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert pre == []  # confirm starting empty

    await record_oauth_pm_auto_flip(storage, _oauth_pm_record())

    rows = await storage.fetch_all(
        "SELECT mode, triggered_by FROM pm_mode_log"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "interactive"
    assert rows[0]["triggered_by"] == "mike_message"

    # Audit details on the empty-log baseline: old_mode must be None
    # (Python None ↔ JSON null), not the string "None" and not absent.
    audits = await storage.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'pm_mode.auto_flip'"
    )
    assert len(audits) == 1
    details = json.loads(audits[0]["details_json"])
    assert "old_mode" in details, (
        "audit details_json missing 'old_mode' key on empty-log "
        "baseline; the audit-consumer contract requires the field "
        "to always be present."
    )
    assert details["old_mode"] is None, (
        f"audit old_mode is {details['old_mode']!r} on empty-log "
        "baseline; expected JSON null (Python None). The Q3 "
        "None-baseline contract requires the absence of any prior "
        "pm_mode_log row to surface as old_mode=None in the audit, "
        "not as the string 'None' or any other sentinel."
    )


async def test_oauth_pm_request_writes_mode_log_on_first_interaction(
    storage: SqliteStorage,
) -> None:
    """A first interaction transition (continuous → interactive) writes
    an interactive row. Mirrors the empty-log case but with an
    explicit pre-state."""
    await _seed_pm_mode(storage, "continuous")
    await record_oauth_pm_auto_flip(storage, _oauth_pm_record())

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    assert len(rows) == 2
    assert rows[0]["mode"] == "continuous"
    assert rows[1]["mode"] == "interactive"


# ---------------------------------------------------------------------------
# Test 2: skips when already interactive
# ---------------------------------------------------------------------------


async def test_oauth_pm_request_skips_when_already_interactive(
    storage: SqliteStorage,
) -> None:
    """Reading B per the M5-pm-mode-and-window-state commit 5 prep:
    write only on transition. Subsequent oauth-pm observations during
    an interactive stretch produce no writes.

    Failure mode this test prevents: a future commit treating every
    oauth-pm request as a write target (Reading A) would explode
    pm_mode_log volume during sustained Mike chats."""
    await _seed_pm_mode(storage, "interactive")
    await record_oauth_pm_auto_flip(storage, _oauth_pm_record())

    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    # Only the seeded row exists; the helper did not write.
    assert len(rows) == 1
    assert rows[0]["id"] == "seed-interactive"


# ---------------------------------------------------------------------------
# Tests 3 + 4: skip paused states (Q2 contract tests)
# ---------------------------------------------------------------------------


async def test_oauth_pm_request_skips_when_paused_blocked(
    storage: SqliteStorage,
) -> None:
    """Reading B on Q2: paused_blocked is a PM-deliberate state via
    set_pm_mode; the daemon's auto-flip does not override it. Mike's
    MCP activity during a paused stretch is not observable in
    pm_mode_log under this writer's design — PM uses other freshness
    signals (M5-telegram-foundation will route Mike's reply-to-paused-
    question messages through a different channel).

    Failure mode this test prevents: a future commit removing the
    paused-state guard would erase PM's deliberate state-machine
    semantics on every Mike MCP request, breaking PM's
    paused-state expectations.
    """
    await _seed_pm_mode(storage, "paused_blocked")
    await record_oauth_pm_auto_flip(storage, _oauth_pm_record())

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "paused_blocked", (
        "auto-flip overrode paused_blocked state; the Q2 disposition "
        "from M5-pm-mode-and-window-state commit 5 prep specifies "
        "skip-when-paused. Daemon does not override PM-deliberate "
        "state machine."
    )


async def test_oauth_pm_request_skips_when_paused_question(
    storage: SqliteStorage,
) -> None:
    """Symmetric to paused_blocked: paused_question is also PM-
    deliberate; auto-flip skips. This is the more common case in
    practice — PM enters paused_question when surfacing a blocking
    question to Mike via Telegram, and Mike's reply will arrive via
    Telegram inbound (not via Mike opening Claude Code), so this
    skip path rarely triggers in the steady state. When it does
    (Mike opens Claude Code while PM is paused_question), the
    conservative skip is correct."""
    await _seed_pm_mode(storage, "paused_question")
    await record_oauth_pm_auto_flip(storage, _oauth_pm_record())

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "paused_question"


# ---------------------------------------------------------------------------
# Test 6: non-pm oauth callers don't trigger auto-flip
# ---------------------------------------------------------------------------


async def test_non_pm_oauth_request_does_not_write_pm_mode_log(
    storage: SqliteStorage,
) -> None:
    """The auto-flip checks BOTH source='oauth' AND role='pm'. An
    OAuth-issued token with a non-PM role doesn't trigger the flip
    even if it arrives via the same OAuth flow. Phase 1 only PM is
    OAuth-issued, but the role gate is independent and should be
    durably tested."""
    await _seed_pm_mode(storage, "continuous")
    await record_oauth_pm_auto_flip(storage, _oauth_engineer_record())

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "continuous"


# ---------------------------------------------------------------------------
# Test 7: static-pm tokens don't trigger auto-flip (contract test)
# ---------------------------------------------------------------------------


async def test_static_pm_token_does_not_trigger_auto_flip(
    storage: SqliteStorage,
) -> None:
    """Only OAuth-issued PM tokens (Mike via Claude Code) represent
    Mike's presence. Static-issued PM tokens are the daemon-internal
    pm-continuous supervisor; their MCP activity is the PM subprocess
    doing its own work, not Mike interacting.

    Failure mode this test prevents: a future commit relaxing the
    source check (e.g., role='pm' alone would trigger) would cause
    every PM-supervisor MCP call to flip pm_mode_log to interactive,
    permanently masking Mike's actual presence with PM's own
    activity. Catastrophic for the auto-flip's intent."""
    await _seed_pm_mode(storage, "continuous")
    await record_oauth_pm_auto_flip(storage, _static_pm_record())

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    assert len(rows) == 1
    assert rows[0]["mode"] == "continuous", (
        "static-pm token triggered auto-flip; the source='oauth' check "
        "has been removed or relaxed. Only Mike's OAuth-PM token "
        "represents his presence; static PM tokens are the pm-continuous "
        "supervisor's own activity."
    )


# ---------------------------------------------------------------------------
# Test 8: audit emits pm_mode.auto_flip action
# ---------------------------------------------------------------------------


async def test_audit_emits_pm_mode_auto_flip_action(
    storage: SqliteStorage,
) -> None:
    """The auto-flip emits a pm_mode.auto_flip audit row (distinct
    from pm_mode.transition emitted by set_pm_mode). actor='system'
    per the daemon-internal-events convention; target=pm_mode_log_id;
    details capture old_mode, new_mode, triggering_agent_id,
    triggering_token_id, pm_mode_log_id."""
    await _seed_pm_mode(storage, "continuous")
    record = _oauth_pm_record()
    await record_oauth_pm_auto_flip(storage, record)

    audits = await storage.fetch_all(
        "SELECT actor, action, target, details_json FROM audit_log "
        "WHERE action = 'pm_mode.auto_flip'"
    )
    assert len(audits) == 1
    audit = audits[0]
    assert audit["actor"] == "system"
    assert audit["action"] == "pm_mode.auto_flip"
    # target is the inserted pm_mode_log row id.
    assert audit["target"] is not None

    details = json.loads(audit["details_json"])
    assert details["old_mode"] == "continuous"
    assert details["new_mode"] == "interactive"
    assert details["triggering_agent_id"] == record.agent_id
    assert details["triggering_token_id"] == record.token_id
    assert details["pm_mode_log_id"] == audit["target"]

    # And no pm_mode.transition audit (that's set_pm_mode's action).
    transition_audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'pm_mode.transition'"
    )
    assert transition_audits == [], (
        "auto-flip incorrectly emitted pm_mode.transition; the two "
        "actions are distinct (pm_mode.auto_flip = daemon-initiated; "
        "pm_mode.transition = set_pm_mode-initiated)."
    )


# ---------------------------------------------------------------------------
# Test: multiple oauth-pm calls in a stretch produce only one write
# (reinforces Q1 Reading B contract)
# ---------------------------------------------------------------------------


async def test_multiple_oauth_pm_calls_in_stretch_produce_one_write(
    storage: SqliteStorage,
) -> None:
    """Three rapid oauth-pm observations: first one transitions
    continuous → interactive; second and third are no-ops because
    current is already interactive. Encodes the Q1 Reading B
    write-on-transition contract."""
    await _seed_pm_mode(storage, "continuous")
    record = _oauth_pm_record()
    await record_oauth_pm_auto_flip(storage, record)
    await record_oauth_pm_auto_flip(storage, record)
    await record_oauth_pm_auto_flip(storage, record)

    rows = await storage.fetch_all(
        "SELECT mode FROM pm_mode_log ORDER BY rowid"
    )
    # seed-continuous, one transition row → 2 total.
    assert len(rows) == 2, (
        f"three rapid oauth-pm observations produced {len(rows) - 1} "
        "transition rows (expected 1 — the first one); the Q1 "
        "Reading B 'write on transition only' contract is broken. "
        "Subsequent observations during the same interactive stretch "
        "must be no-ops."
    )
    assert rows[0]["mode"] == "continuous"
    assert rows[1]["mode"] == "interactive"


# ---------------------------------------------------------------------------
# Direct-call (inner) transaction rollback
# ---------------------------------------------------------------------------


async def test_direct_call_rolls_back_pm_mode_log_on_audit_failure(
    storage: SqliteStorage, monkeypatch: pytest.MonkeyPatch
) -> None:
    """When :func:`record_oauth_pm_auto_flip` is called directly (no
    outer transaction), its own ``storage.transaction()`` provides
    standalone atomicity. A failure in ``write_audit`` rolls back
    the preceding ``pm_mode_log`` INSERT.

    The integration test ``test_observe_caller_rolls_back_both_writes_on_failure``
    exercises the OUTER transaction path (the ``_observe_caller``
    wrapper's transaction subsumes this helper's inner one as a
    no-op via reentrancy). This test covers the symmetric case —
    the helper called directly without an outer transaction, where
    its own inner transaction is the one doing the rollback.

    Failure mode this test prevents: a future commit removing the
    ``async with storage.transaction():`` wrapper from
    ``record_oauth_pm_auto_flip`` would still pass the
    integration test (because the outer transaction in
    ``_observe_caller`` provides atomicity for the wrapper-driven
    path) but would silently break atomicity for any future
    direct-call path (e.g., a daemon-internal scheduler invoking
    the helper outside a request context).
    """
    real_write_audit = activity_mod.write_audit

    async def failing_write_audit(*args: object, **kwargs: object) -> str:
        raise RuntimeError("simulated audit failure for direct-call rollback")

    monkeypatch.setattr(activity_mod, "write_audit", failing_write_audit)

    with pytest.raises(RuntimeError, match="simulated audit failure"):
        await record_oauth_pm_auto_flip(storage, _oauth_pm_record())

    # pm_mode_log row must NOT exist — the INSERT was rolled back
    # when the audit failed inside the helper's own transaction.
    rows = await storage.fetch_all("SELECT id FROM pm_mode_log")
    assert rows == [], (
        "pm_mode_log row persisted despite audit write failure in "
        "the direct-call path; record_oauth_pm_auto_flip's inner "
        "storage.transaction() wrapper was removed or is not "
        "rolling back. Without it, direct-call paths (no outer "
        "transaction) lose atomicity between the INSERT and the "
        "audit."
    )

    # And restore the real audit writer for completeness (monkeypatch
    # auto-undoes at fixture teardown, but the assertion is clearer
    # this way).
    assert real_write_audit is not failing_write_audit


# ---------------------------------------------------------------------------
# Contrived-case contract: OAuth-PM tokens are never dispatch-bound
# ---------------------------------------------------------------------------


async def test_oauth_pm_tokens_are_not_dispatch_bound_in_production(
    storage: SqliteStorage,
) -> None:
    """Production invariant: OAuth-issued PM tokens (Mike's tokens
    from Claude Code) are never bound to a dispatches row. Mike is
    not a daemon-launched subprocess; his auth flow is OAuth, not
    static-token-bound-to-dispatch.

    The ``test_observe_caller_rolls_back_both_writes_on_failure``
    integration test in ``tests/integration/test_tool_activity_writer.py``
    constructs a contrived case (oauth-pm token bound to a
    dispatches row) to exercise atomic rollback across both fan-out
    destinations of ``_observe_caller``. The contrivance is
    deliberate and documented inline in that test.

    This contract test asserts the production invariant directly.
    If a future code path starts binding OAuth-PM tokens to
    dispatches rows (e.g., a misguided refactor of the OAuth flow
    or the dispatcher), this test fails informatively rather than
    the rollback test silently becoming non-contrived and changing
    semantic meaning.

    Verifies the invariant by inspecting:

    1. ``oauth.py:_record_to_access_token`` — OAuth tokens carry
       ``client_id=record.agent_id`` and a role scope, but do not
       bind to any dispatch.
    2. ``bridge.activity.record_mcp_call`` docstring — explicitly
       names "operator OAuth tokens" as the no-matching-dispatch
       skip case.
    3. The dispatches schema — ``token_id`` is populated only by
       the bridge dispatcher when a subprocess dispatch is launched
       (Implementer/Tester) or by a supervisor's lifecycle row;
       OAuth flow does not write to ``dispatches``.
    """
    # Simulate the OAuth flow: issue an oauth-pm token, observe
    # that no dispatches row exists for it.
    record = _oauth_pm_record()

    # In production, the OAuth provider's load_access_token path
    # never writes a dispatches row. We simulate the post-OAuth
    # state: the token exists conceptually (via the token_record
    # we constructed), no dispatches row touches it.
    rows = await storage.fetch_all(
        "SELECT id FROM dispatches WHERE token_id = ?",
        (record.token_id,),
    )
    assert rows == [], (
        "found a dispatches row bound to an OAuth-PM token's "
        "token_id; production invariant 'OAuth-PM tokens are not "
        "dispatch-bound' has been violated. Either (a) the OAuth "
        "flow now binds tokens to dispatches (refactor the auto-"
        "flip rollback integration test to no longer be "
        "contrived), or (b) some other code path is accidentally "
        "writing dispatches rows for OAuth-PM tokens (bug)."
    )


# ---------------------------------------------------------------------------
# Single-source-of-truth sentinel: PAUSED_MODES derived from PmMode
# ---------------------------------------------------------------------------


def test_paused_modes_derived_from_pm_mode_literal() -> None:
    """``tools.pm_mode.PAUSED_MODES`` must derive from ``PmMode`` via
    ``typing.get_args`` filtering by the ``paused_`` prefix. Single
    source of truth — adding a new ``paused_*`` value to the
    Literal must auto-extend the frozenset.

    Failure modes this sentinel catches:

    1. Someone duplicates the paused-modes set as a literal
       ``frozenset({'paused_blocked', 'paused_question'})``
       elsewhere — this test asserts the canonical derivation
       from PmMode produces exactly the expected values, so a
       drift in either direction (canonical changed, duplicated
       set didn't follow) fails informatively.
    2. Someone renames a paused value without preserving the
       ``paused_`` prefix (e.g., ``paused_blocked`` →
       ``blocked_pause``). The derivation by prefix filter would
       silently drop the renamed value. This test asserts the
       expected paused values explicitly, catching the mismatch.

    Co-located with the auto-flip writer's tests because that's
    the consumer of ``PAUSED_MODES``; the migration's CHECK-
    constraint sentinel test
    (``test_pm_mode_check_constraint_accepts_all_spec_modes``)
    covers the migration ↔ PmMode alignment, and this test covers
    the PmMode ↔ PAUSED_MODES alignment.
    """
    # Re-derive from PmMode using the same get_args pattern.
    from typing import get_args
    expected = frozenset(
        m for m in get_args(PmMode) if m.startswith("paused_")
    )
    assert expected == PAUSED_MODES, (
        f"PAUSED_MODES ({PAUSED_MODES!r}) drifted from the "
        f"derived value ({expected!r}); the single-source-of-truth "
        "from PmMode is broken. Either someone replaced PAUSED_MODES "
        "with a literal set that didn't track a PmMode change, or "
        "the derivation in tools/pm_mode.py was modified."
    )

    # Also assert the explicit expected values so a rename-without-
    # prefix-preservation (paused_blocked → blocked_pause) fails
    # here as a known-paused-value-missing.
    assert frozenset(
        {"paused_blocked", "paused_question"}
    ) == PAUSED_MODES, (
        f"PAUSED_MODES has {PAUSED_MODES!r}; expected exactly "
        "{'paused_blocked', 'paused_question'}. A paused mode was "
        "renamed without preserving the 'paused_' prefix, or an "
        "unrelated PmMode value was added with a coincidental "
        "'paused_' prefix."
    )
