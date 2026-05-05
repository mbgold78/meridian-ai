"""PM spin-rationale narration tool.

M5-continuous-pm-driver commit 3 ships ``pm_spin_rationale_log`` table +
``record_spin_rationale`` MCP tool per BACKLOG § Observability roadmap
fitness tier 1 (M5-continuous-pm-driver scope).

A spin-rationale is one loop-iteration decision: whether PM kept itself
alive (``kept_alive``) or surfaced a shutdown recommendation
(``shutdown_recommended``), classified by a rationale_tag from a closed
4-value enum (``queued_work`` / ``mike_presence`` / ``heartbeat_cheap``
/ ``forgot_to_shutdown``) per F3.6 Q3 disposition. Optional
``rationale_free_text`` carries supplementary diagnostic context.

**Cadence + dedup** (F4.2 Q1 Reading A): per-tick emission, no dedup.
Loop driver at commits 7-8 calls ``record_spin_rationale`` once per
planning iteration. Volume bounded by tick cadence (commit 7 decides);
faithful timeline preserves operator-relevant oscillation signals
that dedup would hide.

**Single write path** (F4.2 Q4 Reading B): write-only at commit 3.
No daemon-side read helper — pm_spin_rationale_log's consumers are
aggregation-shape (tier-2 observability roll-ups), not most-recent-active
reads. Read surfaces ship post-M5-flag-flips per BACKLOG observability
sequencing as operator-facing tier-2 work. If commits 7-8 surface a
real read-back use case (hysteresis, diagnostic), a helper lands
then; speculative shipping risks designing the wrong shape.

**No audit_log integration** (F4.2 Q6 Reading A per F4.5.3 reasoning):
pm_spin_rationale_log IS itself the durable record. Cross-recording
into audit_log would create two-write-paths-for-same-event with
divergence risk and operator confusion about canonical source.
audit_log scope is cross-cutting events needing cross-domain query;
pm_spin events query target IS this table directly.

**Atomicity** (F4.2 Q7 Reading A): single INSERT, no transaction wrap.
SQLite single-statement INSERTs are atomic by default. With no
companion writes (no audit_log row, no read-back), the transaction
envelope adds zero value. (record_api_call wraps because it has the
conditional dispatch UPDATE + audit_log write; spin-rationale has
neither.)

**Permissions**: PM-only at the handler layer per
``06-mcp-tools-phase-one.md`` § ``record_spin_rationale``. Engineer's
own continuous-loop spec (if it lands at M5-flag-flips) may need
parallel infrastructure; promote the schema if so, don't extend
this PM-only handler.
"""

from __future__ import annotations

import uuid
from typing import Literal

from pydantic import BaseModel

from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage

# Single source of truth for the spin-rationale decision enum. The
# migration's CHECK constraint on pm_spin_rationale_log.decision and
# the Pydantic Literal here must stay in sync; the
# test_pm_spin_decision_check_constraint sentinel test durably enforces
# alignment by inserting every value below into pm_spin_rationale_log
# and asserting both pass the CHECK.
Decision = Literal["kept_alive", "shutdown_recommended"]

# Single source of truth for the rationale_tag enum per F3.6 Q3 Reading
# A disposition (Followup-2). Closed 4-value taxonomy classifying why
# the loop made the kept_alive / shutdown_recommended decision:
#
# - queued_work: dispatchable tasks remain in the queue; PM stays alive
#   to drive them to completion.
# - mike_presence: Mike is interactive (recent oauth-pm-token activity);
#   PM stays alive to be responsive to interrupts.
# - heartbeat_cheap: no queued work and no Mike activity, but the
#   periodic heartbeat cost is low enough that staying alive is
#   defensible (vs the cost of respawn-from-handoff at next demand).
# - forgot_to_shutdown: PM should have shut down by some prior tick's
#   logic but didn't; current tick recognizes the missed shutdown and
#   recommends it now.
#
# Sentinel test pins this enum; the migration's CHECK list and the
# Literal here stay in sync.
RationaleTag = Literal[
    "queued_work", "mike_presence", "heartbeat_cheap", "forgot_to_shutdown"
]


# ----- Pydantic schemas ----------------------------------------------------


class RecordSpinRationaleInput(BaseModel):
    decision: Decision
    rationale_tag: RationaleTag
    rationale_free_text: str | None = None


class RecordSpinRationaleOutput(BaseModel):
    spin_rationale_id: str


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    """Mirror of :func:`tools.pm_mode._require_role` /
    :func:`tools.pm_focus._require_role`."""
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Handler -------------------------------------------------------------


async def record_spin_rationale(
    *,
    storage: Storage,
    request: RecordSpinRationaleInput,
    caller_role: str,
) -> RecordSpinRationaleOutput:
    """PM-only handler for the ``record_spin_rationale`` MCP tool.

    Single-INSERT writer — no transaction wrap (Q7 Reading A) since
    no companion writes fire (no audit_log row per Q6 Reading A; no
    read-back per Q4 Reading B). Per-tick emission, no dedup
    (Q1 Reading A) — every loop iteration produces one row regardless
    of whether the (decision, rationale_tag) tuple matches the prior
    iteration.

    The ``iteration_timestamp`` column takes the SQL default
    ``datetime('now')`` — same convention as
    ``pm_mode_log.timestamp`` and ``pm_current_focus.declared_at``.
    No rowid-DESC most-recent-row helper at this commit; consumers
    aggregate via time-windowed roll-ups (tier-2 observability work
    post-M5-flag-flips), not most-recent-active reads.

    Returns the generated row id; caller may persist it for cross-
    referencing in subsequent diagnostic logs (e.g., the loop driver
    at commits 7-8 may want to record the spin_rationale_id alongside
    its in-memory state for trace reconstruction).
    """
    _require_role(caller_role, "pm")

    spin_rationale_id = uuid.uuid4().hex
    await storage.execute(
        "INSERT INTO pm_spin_rationale_log "
        "(id, decision, rationale_tag, rationale_free_text) "
        "VALUES (?, ?, ?, ?)",
        (
            spin_rationale_id,
            request.decision,
            request.rationale_tag,
            request.rationale_free_text,
        ),
    )
    return RecordSpinRationaleOutput(spin_rationale_id=spin_rationale_id)


__all__ = [
    "Decision",
    "RationaleTag",
    "RecordSpinRationaleInput",
    "RecordSpinRationaleOutput",
    "record_spin_rationale",
]
