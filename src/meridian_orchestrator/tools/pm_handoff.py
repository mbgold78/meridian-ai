"""PM compaction handoff-doc tools.

M5-continuous-pm-driver commit 8 ships PM's compaction lifecycle
mechanism. PM-continuous detects subscription_5h pace_recommendation
flipping to ``conserve`` (>= 80% utilization), authors a handoff doc
summarizing current_focus + planned-next + open-questions + recent-
decisions-pointer + cohort-summary (sections per F2.3 Q1 Reading C
PM-yaml convention), calls :func:`write_pm_handoff` to persist it,
then signals compaction (extended :func:`tools.supervisor.signal_compaction`,
PM-allowed per F9.2 Q3-A). The supervisor's watchdog observes the
clean exit, audits ``pm.compaction_completed``, marks the dispatch
row ``status='compacted'``, and respawns PM fresh. The new PM
subprocess at startup calls :func:`get_most_recent_handoff` to load
the prior handoff content as resumed-context.

**Architectural placement** (per F9.0 Reading Y disposition,
2026-05-07; preserving M5-pm-supervisor-base P-2 Reading B):
handoff-read is a subprocess-prompt concern via MCP tools, NOT
daemon-side ``_reconstruct_state`` override. Defense (4):
substrate-evolution-decoupling — substrate filesystem layout
(`/product/substrate/pm-handoffs/` or operator-tunable equivalent)
is part of Mike's product-content git repo, evolves with PM-yaml
convention, shouldn't force daemon evolution. Layout knowledge
lives in this module + pm.yaml, not in pm_supervisor.py.

**Closure plumbing**: ``Settings.pm_handoff_dir`` (or its derived
default ``<data_dir>/pm-handoffs/``) flows via main.py lifespan to
a ``get_pm_handoff_dir`` closure consumed by the MCP wrappers.
Mirrors ``get_pm_loop_tick_seconds`` (commit 7) +
``get_oauth_usage_snapshot`` (M5-pm-mode-and-window-state) lazy-
callable precedents. Zero new bridge → tools imports.

**Permission**: PM-only on both tools. PM-continuous subprocess is
the load-bearing caller; Mike's interactive PM session governed
by client-side defaults — interactive PM doesn't run the
compaction lifecycle.

**Filename convention**: ISO-8601 UTC timestamp with colons
replaced by hyphens (Windows-safe), microseconds preserved for
uniqueness across rapid same-second writes. Sort order is
lexical-on-filename = chronological because ISO-8601 sorts
chronologically when colons-or-hyphens are uniformly applied.
PM-prompt-driven; if Mike changes the convention, this module +
pm.yaml update together.

**Audit-action namespace**: ``pm.compaction.handoff_written`` on
:func:`write_pm_handoff` per F9.2 Q2-A (re-confirm F2.3 Q1
Reading C — yaml-convention with audit-emit-on-write; no daemon-
side strict validation). Read-side (:func:`get_most_recent_handoff`)
does NOT audit: reads are non-state-changing and audit volume from
read-on-every-PM-startup would be operational noise without
diagnostic value.

**Q8 abstraction-unit pin** (F9.7.B, 2026-05-07): the read helper
:func:`read_most_recent_handoff` is filesystem-by-timestamp, NOT
SQL-rowid-DESC. Different correctness conditions:
SQL-rowid-DESC depends on rowid monotonicity (database invariant);
filesystem-by-timestamp depends on ISO-timestamp lexical sort
matching chronological order (PM-convention-dependent; can break
if filename schema changes). Different semantic load → no shared
helper extraction with ``read_current_pm_mode`` /
``read_current_focus``. The ``read_current_X`` rowid-DESC watch
stays at 2 (commit 7 pin scope). New pattern
"read most-recent file by timestamp" starts at instance 1 here.
"""

from __future__ import annotations

import re
from collections.abc import Callable
from datetime import UTC, datetime, timedelta
from pathlib import Path

from pydantic import BaseModel, Field

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.errors import ValidationError
from meridian_orchestrator.storage.interface import Storage

# Filename convention: ISO-8601 UTC with colons replaced by hyphens.
# Format: YYYY-MM-DDTHH-MM-SS.ffffffZ.md
# Lexical sort = chronological sort because all components are
# zero-padded fixed-width and uniformly delimited.
_FILENAME_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}-\d{2}-\d{2}\.\d{6}Z\.md$"
)
_FILENAME_SUFFIX = ".md"


def _format_handoff_filename(now: datetime) -> str:
    """Render ``now`` as a Windows-safe lexically-sortable filename.

    ``now`` MUST be UTC (tzinfo=UTC). The ``Z`` suffix in the filename
    encodes that explicitly; passing a naive or non-UTC datetime would
    produce a misleading filename.
    """
    if now.tzinfo is None or now.utcoffset() != timedelta(0):
        raise ValueError(
            "_format_handoff_filename requires a UTC-aware datetime; "
            f"got tzinfo={now.tzinfo!r}"
        )
    # ISO 8601 with colons → hyphens for Windows-safety.
    iso = now.strftime("%Y-%m-%dT%H-%M-%S.%f")
    return f"{iso}Z{_FILENAME_SUFFIX}"


def _parse_handoff_filename(name: str) -> datetime | None:
    """Parse a handoff filename back to its UTC datetime, or None if
    the filename doesn't match the convention.

    Used for filtering directory listings — files that don't match the
    schema (operator-placed README, .DS_Store, etc.) are skipped rather
    than failing the read.
    """
    if not _FILENAME_PATTERN.match(name):
        return None
    stem = name[: -len(_FILENAME_SUFFIX)].rstrip("Z")
    try:
        # Re-insert colons for ISO parser; format is YYYY-MM-DDTHH-MM-SS.ffffff
        # We split on 'T' to colon-restore only the time portion.
        date_part, time_part = stem.split("T", 1)
        time_iso = time_part.replace("-", ":", 2)
        parsed = datetime.fromisoformat(f"{date_part}T{time_iso}")
        return parsed.replace(tzinfo=UTC)
    except ValueError:
        return None


# ----- Pydantic schemas ----------------------------------------------------


class WritePmHandoffInput(BaseModel):
    content: str = Field(
        ...,
        min_length=1,
        description=(
            "Markdown handoff content. PM authors per pm.yaml convention; "
            "required sections (current_focus, planned_next, open_questions, "
            "recent_decisions_pointer, cohort_summary) are yaml-convention, "
            "not daemon-validated (per F2.3 Q1 Reading C). The handler "
            "writes the content as-is to a timestamped file."
        ),
    )


class WritePmHandoffOutput(BaseModel):
    written_at: datetime
    path: str
    content_size: int


class GetMostRecentHandoffOutput(BaseModel):
    """Output for ``get_most_recent_handoff``.

    ``content`` and ``written_at`` are both ``None`` on cold-start (no
    prior handoff exists). PM's prompt instructs handling: cold-start
    means treat session as fresh; non-None means resumed-context from
    the prior compaction's handoff.
    """

    content: str | None
    written_at: datetime | None


# ----- Permissions ---------------------------------------------------------


def _require_role(caller_role: str, *allowed: str) -> None:
    if caller_role not in allowed:
        raise OrchestratorPermissionError(
            f"caller role {caller_role!r} cannot invoke this tool"
        )


# ----- Filesystem helpers --------------------------------------------------


def read_most_recent_handoff(
    handoff_dir: Path,
) -> tuple[str, datetime] | None:
    """Filesystem-side helper: return the most recent handoff's
    (content, written_at), or None if no valid handoff exists.

    Skips files that don't match the timestamp-suffix filename
    convention (operator-placed READMEs, .DS_Store, partial writes
    from prior failures). If the directory doesn't exist, returns
    None — equivalent to cold-start.

    **Q8 abstraction-unit pin** (F9.7.B): filesystem-by-timestamp
    correctness depends on ISO-timestamp lexical sort matching
    chronological order. Filename-schema changes break this; the
    ``_parse_handoff_filename`` filter is the single point that
    decides which files participate in the sort.
    """
    if not handoff_dir.exists():
        return None
    candidates: list[tuple[str, datetime]] = []
    for entry in handoff_dir.iterdir():
        if not entry.is_file():
            continue
        parsed = _parse_handoff_filename(entry.name)
        if parsed is None:
            continue
        candidates.append((entry.name, parsed))
    if not candidates:
        return None
    # Sort by parsed datetime; lexical sort on filename produces the
    # same order, but parsing first guards against operator-placed
    # files that happen to lexically-sort higher than valid handoffs.
    candidates.sort(key=lambda pair: pair[1])
    most_recent_name, most_recent_at = candidates[-1]
    most_recent_path = handoff_dir / most_recent_name
    content = most_recent_path.read_text(encoding="utf-8")
    return content, most_recent_at


# ----- Handlers ------------------------------------------------------------


async def write_pm_handoff(
    *,
    storage: Storage,
    get_handoff_dir: Callable[[], Path],
    request: WritePmHandoffInput,
    caller_agent_id: str,
    caller_role: str,
    now: datetime | None = None,
) -> WritePmHandoffOutput:
    """PM-only handler for the ``write_pm_handoff`` MCP tool.

    PM authors handoff content at compaction-trigger; this handler
    persists it to ``<handoff_dir>/<iso-timestamp>.md`` and audits the
    write via ``pm.compaction.handoff_written`` action.

    Per F2.3 Q1 Reading C: yaml-convention for required sections;
    no daemon-side strict validation. Audit details capture
    ``content_size`` for operator-side handoff-quality observability;
    section presence detection deferred to M6 dashboard scope.

    The handoff directory is created on first write if it doesn't
    exist (operator doesn't have to pre-create). Filename uniqueness
    relies on microsecond resolution; if two writes land in the same
    microsecond (effectively impossible in a single-PM-process model
    but theoretically possible under operator-error-driven
    multi-PM-process scenarios), the second write would overwrite the
    first — surface as an explicit ValidationError for operator visibility
    rather than silent overwrite.

    The optional ``now`` parameter exists for test-determinism — tests
    inject a fixed datetime to assert exact filename + audit timestamp.
    Production path leaves it None and uses ``datetime.now(UTC)``.
    """
    _require_role(caller_role, "pm")

    written_at = now if now is not None else datetime.now(UTC)
    handoff_dir = get_handoff_dir()
    handoff_dir.mkdir(parents=True, exist_ok=True)

    filename = _format_handoff_filename(written_at)
    path = handoff_dir / filename

    if path.exists():
        # Microsecond collision (or operator-error multi-PM-process).
        # Surface explicitly rather than silent overwrite — this
        # signals operator-attention surface for "two PM instances
        # writing handoffs at the same instant."
        raise ValidationError(
            f"handoff filename {filename!r} already exists at "
            f"{handoff_dir}; refusing to overwrite. This typically "
            "indicates either a microsecond-resolution collision "
            "(re-call write_pm_handoff after a brief delay) or a "
            "multi-PM-process scenario that warrants operator "
            "investigation."
        )

    path.write_text(request.content, encoding="utf-8")
    content_size = len(request.content)

    await write_audit(
        storage,
        actor=caller_agent_id,
        action="pm.compaction.handoff_written",
        target=str(path),
        details={
            "path": str(path),
            "filename": filename,
            "content_size": content_size,
            "written_at": written_at.isoformat(),
        },
    )

    return WritePmHandoffOutput(
        written_at=written_at,
        path=str(path),
        content_size=content_size,
    )


async def get_most_recent_handoff(
    *,
    get_handoff_dir: Callable[[], Path],
    caller_role: str,
) -> GetMostRecentHandoffOutput:
    """PM-only handler for the ``get_most_recent_handoff`` MCP tool.

    Returns the most recent handoff's content + written-at, or None
    on cold-start (no prior handoff exists). PM-prompt-driven:
    called at startup to load resumed-context from prior compaction.

    Output shape per F9.2 Q6-C (content + minimal metadata): drops
    ``path`` and ``sections_detected`` fields — path has low operator-
    facing value at the MCP surface (audit_log records the path on
    writes); sections-detection requires markdown parser surface
    deferred per F2.3 Q1 Reading C.

    No audit emission on read — audit volume from read-on-every-PM-
    startup would be operational noise without diagnostic value.
    Operator visibility into read patterns is deferred to M6 dashboard.
    """
    _require_role(caller_role, "pm")

    handoff_dir = get_handoff_dir()
    result = read_most_recent_handoff(handoff_dir)
    if result is None:
        return GetMostRecentHandoffOutput(content=None, written_at=None)
    content, written_at = result
    return GetMostRecentHandoffOutput(content=content, written_at=written_at)


__all__ = [
    "GetMostRecentHandoffOutput",
    "WritePmHandoffInput",
    "WritePmHandoffOutput",
    "get_most_recent_handoff",
    "read_most_recent_handoff",
    "write_pm_handoff",
]
