"""Random-audit-sampling infrastructure.

M5-continuous-pm-driver commit 4 ships ``audit_sample_log`` table +
``Sampler`` class + ``record_sampleable_event`` MCP tool per BACKLOG §
Observability roadmap (silent-compliance defenses, M5-continuous-pm-
driver scope). PM's loop driver subprocess at commits 7-8 will be the
production caller; commit 4 ships the writer + MCP wrapper so the
consumer can integrate against the real surface without co-shipping
coupling.

A "sampleable event" is one whose outcome is worth periodically
re-evaluating — typically completion-validation events
(pm.current_focus.completed, pm.current_focus.contract_violation,
dispatch.complete_called) or narration emissions
(pm.current_focus.superseded, pm.spin_rationale.recorded). Sampling
selects a fraction of these for asynchronous re-evaluation; selected
samples land in audit_sample_log with re_eval_state='pending'. The
async re-evaluator (the handler that processes pending → in_progress →
complete with re_eval_finding text) is **out of scope** at commit 4
per F5.2 Q10 Reading A disposition; deferred to the
post-M5-flag-flips focused-observability sub-milestone per F5.6.C
disposition.

**Cadence + algorithm** (F5.2 Q8 Reading A): coin flip per event with
probability ``audit_sample_percentage``. Phase 1 default is 0.0
(disabled-by-default opt-in shape per F5.2 Q9 Reading A); operator
sets a non-zero value to enable sampling.

**Trigger interface** (F5.2 Q3 Reading A): explicit MCP hook tool
``record_sampleable_event(source_event_kind, source_event_id)``. PM's
loop driver subprocess calls this tool after observing sampleable
events; sampler picks per config and writes the audit_sample_log row
if selected. Loop has selective control over which event-kinds are
sampleable (high-volume kinds like spin_rationale may warrant lower
percentages or be excluded entirely; per-kind tuning lands in a
future commit per F5.2 Q4 Reading C).

**No audit_log integration** (F5.2 Q6 Reading A per F4.5.3 reasoning,
continued from commit 3): audit_sample_log IS itself the durable
record of what was sampled. Cross-recording into audit_log would
create two-write-paths-for-same-event with divergence risk and
operator confusion about canonical source.

**Atomicity** (F5.2 Q7 Reading A): single-INSERT writer, no
transaction wrap. SQLite single-statement INSERTs are atomic by
default. Conditional on Q6-A; held.

**Permissions**: PM-only at the handler layer. Engineer's continuous
loop spec (if it lands at M5-flag-flips with parallel sampling
infrastructure) may need symmetric Engineer-emittable hook; promote
the schema if so, don't extend this PM-only handler.
"""

from __future__ import annotations

import random
import uuid
from typing import Literal

from pydantic import BaseModel

from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.interface import Storage

# Single source of truth for the source_event_kind enum. The migration's
# CHECK constraint on audit_sample_log.source_event_kind and the
# Pydantic Literal here must stay in sync; the
# test_audit_sample_source_event_kind_check_constraint sentinel test
# durably enforces alignment by inserting every value below into
# audit_sample_log and asserting all five pass the CHECK.
#
# The 5 values match what PM's loop driver will emit
# record_sampleable_event for at commits 7-8:
#
# - pm.current_focus.completed: completion-validation outcomes — the
#   load-bearing case for re-evaluating whether PM's contract_validated
#   decision was correct.
# - pm.current_focus.contract_violation: explicit validation failures
#   — re-evaluation can confirm or revise PM's finding.
# - pm.current_focus.superseded: implicit pivots from Mike-interrupt
#   pressure — re-evaluation can flag patterns of churn.
# - pm.spin_rationale.recorded: per-tick keep-alive-vs-shutdown
#   decisions — random spot-check audits per the silent-compliance
#   defense framing in BACKLOG observability roadmap.
# - dispatch.complete_called: Engineer's complete_dispatch outcomes
#   — re-evaluation can audit whether the dispatch's reported result
#   matches the actual artifacts.
#
# Future expansion via migration when Engineer's continuous loop spec
# lands (post-M5-flag-flips, additional dispatch.* kinds) or operator
# workload demands additional kinds. Mirrors
# 0008_dispatches_status_check.sql precedent.
SourceEventKind = Literal[
    "pm.current_focus.completed",
    "pm.current_focus.contract_violation",
    "pm.current_focus.superseded",
    "pm.spin_rationale.recorded",
    "dispatch.complete_called",
]

# State-machine enum for the async re-evaluation lifecycle. Values
# locked at commit 4; future expansion (e.g., 'failed', 'abandoned')
# lands via migration when the re-evaluator surfaces real terminal
# variants.
ReEvalState = Literal["pending", "in_progress", "complete"]


# ----- Pure helpers --------------------------------------------------------


def _sample_decision(
    percentage: float, *, rng: random.Random | None = None
) -> bool:
    """Coin-flip sampling decision per F5.2 Q8 Reading A.

    Returns True with probability ``percentage`` (0.0-1.0). Phase 1
    default is 0.0 (disabled); operator sets non-zero via
    ``audit_sample_percentage`` Settings field to enable.

    The ``rng`` parameter is for deterministic testing — pass a
    ``random.Random(seed)`` instance to produce reproducible
    sequences. Production callers omit and use the module's global
    ``random`` state.

    Edge cases:
    - ``percentage <= 0.0`` → always False (fast-path; no rng call).
    - ``percentage >= 1.0`` → always True (fast-path; no rng call).
    - Values in between → ``rng.random() < percentage``.

    The fast-paths matter because the production caller's hot path
    (every record_sampleable_event invocation) hits the percentage=0.0
    case at default-disabled-config; skipping rng work eliminates a
    measurable per-call cost at high event rates.
    """
    if percentage <= 0.0:
        return False
    if percentage >= 1.0:
        return True
    rng_resolved = rng if rng is not None else random
    return rng_resolved.random() < percentage


# ----- Sampler class -------------------------------------------------------


class Sampler:
    """Random-audit sampler for events emitted by PM's loop driver.

    Constructed once at daemon lifespan startup with the configured
    ``audit_sample_percentage``; passed to the MCP wrapper layer where
    ``record_sampleable_event`` invokes :meth:`maybe_sample` per call.

    The class is small (one method + one constructor field) but
    encapsulates the percentage-config + storage-write contract so
    future per-kind percentage configuration (F5.2 Q4 Reading C
    deferral) can layer in without breaking the writer signature.
    """

    def __init__(self, sampling_percentage: float):
        """Construct a Sampler with the given global sampling percentage.

        Validates that ``sampling_percentage`` is in [0.0, 1.0].
        Settings.audit_sample_percentage applies the same range
        constraint at the boundary; this constructor's check is
        defense-in-depth for callers that bypass Settings (test
        fixtures constructing Sampler directly).
        """
        if not 0.0 <= sampling_percentage <= 1.0:
            raise ValueError(
                f"sampling_percentage must be in [0.0, 1.0]; "
                f"got {sampling_percentage!r}"
            )
        self.sampling_percentage = sampling_percentage

    async def maybe_sample(
        self,
        storage: Storage,
        *,
        source_event_kind: SourceEventKind,
        source_event_id: str,
    ) -> str | None:
        """Coin-flip a sampling decision; if selected, write an
        audit_sample_log row with re_eval_state='pending' and return
        the sample id. If not selected, return None.

        Single-INSERT writer per F5.2 Q7 Reading A; no transaction
        wrap (no companion writes — Q6 Reading A; no read-back).

        Returns ``None`` when the coin flip skipped (default case at
        ``sampling_percentage=0.0``); returns the new sample_id (UUID
        hex) when sampled.
        """
        if not _sample_decision(self.sampling_percentage):
            return None
        sample_id = uuid.uuid4().hex
        await storage.execute(
            "INSERT INTO audit_sample_log "
            "(id, source_event_kind, source_event_id) "
            "VALUES (?, ?, ?)",
            (sample_id, source_event_kind, source_event_id),
        )
        return sample_id


# ----- Pydantic schemas + handler ------------------------------------------


class RecordSampleableEventInput(BaseModel):
    source_event_kind: SourceEventKind
    source_event_id: str


class RecordSampleableEventOutput(BaseModel):
    sample_id: str | None  # None when not sampled (most common case)


def _require_role(caller_role: str, *allowed: str) -> None:
    """Mirror of :func:`tools.pm_mode._require_role` /
    :func:`tools.pm_focus._require_role` /
    :func:`tools.pm_spin._require_role`."""
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


async def record_sampleable_event(
    *,
    storage: Storage,
    sampler: Sampler,
    request: RecordSampleableEventInput,
    caller_role: str,
) -> RecordSampleableEventOutput:
    """PM-only handler for the ``record_sampleable_event`` MCP tool.

    Thin wrapper around :meth:`Sampler.maybe_sample` adding the
    role-gate check. Returns ``RecordSampleableEventOutput`` with
    ``sample_id=None`` when the coin flip skipped (most common case
    at default-disabled config) or with the new sample_id when
    sampled.

    The PM-only role gate is permission scope (only PM token can
    call this tool). The closed-enum source_event_kind constraint
    is taxonomy scope (which event kinds are sampleable); the two
    are independent — PM may emit record_sampleable_event for
    Engineer-sourced events (e.g., dispatch.complete_called) when
    PM observes those outcomes via complete_dispatch result reads.
    """
    _require_role(caller_role, "pm")
    sample_id = await sampler.maybe_sample(
        storage,
        source_event_kind=request.source_event_kind,
        source_event_id=request.source_event_id,
    )
    return RecordSampleableEventOutput(sample_id=sample_id)


__all__ = [
    "ReEvalState",
    "RecordSampleableEventInput",
    "RecordSampleableEventOutput",
    "Sampler",
    "SourceEventKind",
    "record_sampleable_event",
]
