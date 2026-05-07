"""Direct tests for ``tools.tasks``.

M4d-task-management-surface ships the six task-management MCP tools
across commits 2-7. This file exercises the module's schemas, helpers,
and handlers in isolation. Coverage grows commit-by-commit as handlers
land:

- **Commit 2 (skeleton)**: schema smokes + boundary validation +
  ``_require_role`` flat-allowlist helper.
- **Commit 3 (submit_task)**: ``_check_can_submit_task`` hierarchy +
  Q9 self-submit gate; ``_check_can_submit_task_parent_ownership``
  two-clause OR; ``_derive_company_project_for_caller`` Phase 1
  single-project sentinel; ``submit_task`` happy path + edge cases
  (``submitted_by`` for OAuth/static; audit ``details_json`` includes
  ``caller_source``; atomicity rollback).
- Commits 4-7 will add handler tests for claim_task, update_task_status,
  get_queue, get_subtasks, set_task_priority.

Output models (``SubmitTaskOutput``, ``ClaimTaskOutput``, etc.) are
not tested directly per CQ5: each is a ``task: Task`` wrapper; coverage
is redundant with
``tests/unit/storage/test_models.py:test_task_with_all_m4d_fields``.

Trivial input schemas (``ClaimTaskInput``, ``GetSubtasksInput``) are
not smoke-tested per the same scope-cap discipline — Pydantic
structural validation suffices for single-string-field models.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from pathlib import Path

import pytest
import pytest_asyncio
from pydantic import ValidationError

from meridian_orchestrator.agents.config import AgentConfig
from meridian_orchestrator.agents.permissions import PermissionResolver
from meridian_orchestrator.errors import (
    ConflictError,
    NotFoundError,
)
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError
from meridian_orchestrator.storage.migrations import run_migrations
from meridian_orchestrator.storage.sqlite import SqliteStorage
from meridian_orchestrator.tools.tasks import (
    HUMAN_LITERAL_ROLE_MAP,
    HUMAN_MIKE_LITERAL,
    ClaimTaskInput,
    GetQueueInput,
    GetSubtasksInput,
    SetTaskPriorityInput,
    SubmitTaskInput,
    UpdateTaskStatusInput,
    _check_can_claim,
    _check_can_read_subtasks,
    _check_can_set_priority,
    _check_can_submit_task,
    _check_can_submit_task_parent_ownership,
    _check_can_update_task_status,
    _derive_company_project_for_caller,
    _require_role,
    claim_task,
    get_queue,
    get_subtasks,
    set_task_priority,
    submit_task,
    update_task_status,
)


def test_submit_task_input_defaults() -> None:
    """``SubmitTaskInput`` minimal construction: priority=2, complexity=3,
    parent_task_id=None, task_type=None per spec defaults."""
    inp = SubmitTaskInput(agent_role="engineer", description="ship X")
    assert inp.priority == 2
    assert inp.complexity == 3
    assert inp.parent_task_id is None
    assert inp.task_type is None


def test_get_queue_input_defaults() -> None:
    """``GetQueueInput`` minimal construction: status=None (no filter),
    agent_role=None, project_id=None, limit=50, offset=0 per spec."""
    inp = GetQueueInput()
    assert inp.status is None
    assert inp.agent_role is None
    assert inp.project_id is None
    assert inp.limit == 50
    assert inp.offset == 0


def test_update_task_status_input_literal_accepts_six_values() -> None:
    """``UpdateTaskStatusInput.status`` Literal walks the six valid
    transition statuses; ``'pending'`` rejects (no transition TO pending
    per ``docs/kickoff/05-data-model.md`` state machine)."""
    valid = [
        "claimed",
        "in_progress",
        "completed",
        "failed",
        "declined",
        "cancelled",
    ]
    for status in valid:
        UpdateTaskStatusInput(task_id="t1", status=status)  # type: ignore[arg-type]

    with pytest.raises(ValidationError):
        UpdateTaskStatusInput(task_id="t1", status="pending")  # type: ignore[arg-type]
    with pytest.raises(ValidationError):
        UpdateTaskStatusInput(task_id="t1", status="bogus")  # type: ignore[arg-type]


def test_priority_complexity_bounds_validation() -> None:
    """``Field(ge=1, le=5)`` enforces priority and complexity bounds at
    Pydantic boundary; out-of-range values raise ``ValidationError``.
    Covers ``SubmitTaskInput`` AND ``SetTaskPriorityInput`` priority
    Field."""
    # In-bounds: edges accepted.
    SubmitTaskInput(
        agent_role="engineer", description="x", priority=1, complexity=1
    )
    SubmitTaskInput(
        agent_role="engineer", description="x", priority=5, complexity=5
    )
    SetTaskPriorityInput(task_id="t1", priority=1)
    SetTaskPriorityInput(task_id="t1", priority=5)

    # Out-of-bounds: reject.
    for bad in (0, 6, -1, 100):
        with pytest.raises(ValidationError):
            SubmitTaskInput(
                agent_role="engineer", description="x", priority=bad
            )
        with pytest.raises(ValidationError):
            SubmitTaskInput(
                agent_role="engineer", description="x", complexity=bad
            )
        with pytest.raises(ValidationError):
            SetTaskPriorityInput(task_id="t1", priority=bad)


def test_get_queue_limit_offset_bounds_validation() -> None:
    """``GetQueueInput`` limit ``Field(ge=1, le=500)`` and offset
    ``Field(ge=0)`` enforce CQ8 disposition cap at 500 and non-negative
    offset."""
    # In-bounds.
    GetQueueInput(limit=1, offset=0)
    GetQueueInput(limit=500, offset=1000)

    # Out-of-bounds.
    with pytest.raises(ValidationError):
        GetQueueInput(limit=0)
    with pytest.raises(ValidationError):
        GetQueueInput(limit=501)
    with pytest.raises(ValidationError):
        GetQueueInput(offset=-1)


def test_get_queue_status_filter_validates_taskstatus_values() -> None:
    """``GetQueueInput.status``: ``list[TaskStatus] | None`` — Pydantic
    Literal membership validates at API boundary per CQ2. Tightens
    spec's ``list[str]`` to typed enum; unknown statuses reject."""
    # All seven TaskStatus values accepted.
    inp = GetQueueInput(
        status=[
            "pending",
            "claimed",
            "in_progress",
            "completed",
            "failed",
            "declined",
            "cancelled",
        ]
    )
    assert inp.status is not None
    assert len(inp.status) == 7

    # Subset filter accepted.
    inp = GetQueueInput(status=["pending", "claimed"])
    assert inp.status == ["pending", "claimed"]

    # Unknown status rejects.
    with pytest.raises(ValidationError):
        GetQueueInput(status=["bogus"])  # type: ignore[list-item]


def test_require_role_allows_listed_rejects_unlisted() -> None:
    """``_require_role`` flat-allowlist primitive: ``caller_role`` in
    ``*allowed`` passes; otherwise raises
    ``OrchestratorPermissionError``. Mirrors
    ``tools/dispatch.py:_require_role`` exactly; per-tool hierarchy
    helpers land at commits 3-7 alongside their handlers."""
    # Listed role passes.
    _require_role("pm", "pm", "engineer")
    _require_role("engineer", "pm", "engineer")

    # Unlisted role rejects.
    with pytest.raises(OrchestratorPermissionError):
        _require_role("implementer", "pm", "engineer")
    with pytest.raises(OrchestratorPermissionError):
        _require_role("tester", "pm", "engineer")

    # Empty allowlist rejects everything.
    with pytest.raises(OrchestratorPermissionError):
        _require_role("pm")


# ---------------------------------------------------------------------------
# Commit 3 tests: submit_task handler + helpers + Q9 (A) caller-distinction
# ---------------------------------------------------------------------------


def _agent_config(
    role: str, manages: list[str] | None = None
) -> AgentConfig:
    """Minimal AgentConfig for PermissionResolver construction.

    Mirrors tests/unit/agents/test_permissions.py:_agent shape.
    """
    return AgentConfig(
        role=role,  # type: ignore[arg-type]
        display_name=role.upper(),
        reports_to=None if role == "pm" else "pm",  # type: ignore[arg-type]
        manages=manages or [],  # type: ignore[arg-type]
        system_prompt="x",
        mcp_tools_allowed=["submit_task"],
        effort="high",
    )


def _submit_task_resolver() -> PermissionResolver:
    """PermissionResolver with the canonical Phase 1 hierarchy:
    PM → Engineer → {Implementer, Tester}."""
    return PermissionResolver(
        {
            "pm": _agent_config("pm", manages=["engineer"]),
            "engineer": _agent_config(
                "engineer", manages=["implementer", "tester"]
            ),
            "implementer": _agent_config("implementer"),
            "tester": _agent_config("tester"),
        }
    )


@pytest_asyncio.fixture
async def storage(tmp_path: Path) -> AsyncIterator[SqliteStorage]:
    """Storage fixture with seeded company + project + agents.

    Mirrors tests/integration/test_pm_focus_mcp_wrapper.py shape but
    seeds three agent rows (pm, engineer, implementer) to satisfy
    ``_check_can_submit_task_parent_ownership``'s LEFT JOIN to agents.
    """
    s = SqliteStorage(tmp_path / "data" / "orchestrator.db")
    await s.connect()
    await run_migrations(s)
    await s.execute(
        "INSERT INTO companies (id, name) VALUES ('co1', 'Meridian AI')"
    )
    await s.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj1', 'co1', 'Substrate Product')"
    )
    for agent_id, role in (
        ("agent-pm", "pm"),
        ("agent-eng-a", "engineer"),
        ("agent-eng-b", "engineer"),  # second PM-A-dies-PM-B-resumes test
        ("agent-impl", "implementer"),
    ):
        await s.execute(
            "INSERT INTO agents (id, company_id, role, config_path) "
            "VALUES (?, 'co1', ?, '/dev/null')",
            (agent_id, role),
        )
    yield s
    await s.close()


# ----- _check_can_submit_task (hierarchy + Q9; pure-Python; no DB) ---------


def test_check_can_submit_task_direct_managee_passes() -> None:
    """PM submitting for engineer passes both gates: Q9 not triggered
    (target != caller) and hierarchy via manages_directly (engineer is
    in PM's direct manages list per 06-mcp-tools-phase-one.md:44)."""
    resolver = _submit_task_resolver()
    _check_can_submit_task(
        caller_role="pm",
        target_role="engineer",
        caller_source="static",
        permissions=resolver,
    )


def test_check_can_submit_task_non_direct_managee_rejects() -> None:
    """PM submitting for implementer is a hierarchy bypass per spec
    line 44: "PM **cannot** submit a task with agent_role='implementer'
    — that's a hierarchy bypass." The gate uses manages_directly
    (NOT is_descendant); engineer is the direct manager of implementer.
    Transitive reads (PM→Engineer→Implementer) are spec-explicit
    bypasses."""
    resolver = _submit_task_resolver()
    with pytest.raises(
        OrchestratorPermissionError, match="permission denied for submit_task"
    ):
        _check_can_submit_task(
            caller_role="pm",
            target_role="implementer",
            caller_source="static",
            permissions=resolver,
        )


def test_check_can_submit_task_self_target_static_rejects() -> None:
    """PM-as-agent (caller_source='static') self-submit rejects per Q9.
    PMSupervisor subprocess holds a static token; should not enqueue
    work for itself (continuous loop is the actor; submit_task is for
    delegating-down-the-hierarchy work). Q9 runs first; never reaches
    hierarchy gate."""
    resolver = _submit_task_resolver()
    with pytest.raises(OrchestratorPermissionError, match="permission denied for submit_task"):
        _check_can_submit_task(
            caller_role="pm",
            target_role="pm",
            caller_source="static",
            permissions=resolver,
        )


def test_check_can_submit_task_self_target_oauth_passes() -> None:
    """Mike-via-OAuth (caller_source='oauth') self-submit allowed per
    Q9 (A). Mike's MCP requests carry role:pm in the access token's
    scope per hierarchy invariant 1; submitting a PM-role task is a
    legitimate operator action. Q9 short-circuits to pass; hierarchy
    gate (which would otherwise reject self via manages_directly==False)
    is bypassed."""
    resolver = _submit_task_resolver()
    _check_can_submit_task(
        caller_role="pm",
        target_role="pm",
        caller_source="oauth",
        permissions=resolver,
    )


def test_check_can_submit_task_inverse_hierarchy_rejects() -> None:
    """Engineer trying to submit for PM is an inverse-hierarchy bypass.
    manages_directly("engineer", "pm") returns False (engineer doesn't
    manage PM; PM manages engineer); hierarchy gate raises."""
    resolver = _submit_task_resolver()
    with pytest.raises(
        OrchestratorPermissionError, match="permission denied for submit_task"
    ):
        _check_can_submit_task(
            caller_role="engineer",
            target_role="pm",
            caller_source="static",
            permissions=resolver,
        )


# ----- _check_can_submit_task_parent_ownership (clauses 1 + 2; DB) ---------


async def _seed_parent_task(
    storage: SqliteStorage,
    *,
    task_id: str,
    submitted_by: str,
    claimed_by: str | None = None,
) -> None:
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, submitted_by, claimed_by) "
        "VALUES (?, 'co1', 'proj1', 'engineer', 'parent', ?, ?)",
        (task_id, submitted_by, claimed_by),
    )


async def test_parent_ownership_clause_1_claimed_by_matches(
    storage: SqliteStorage,
) -> None:
    """Clause 1 (agent-identity ownership): caller_agent_id matches
    parent.claimed_by. Returns parent row dict for company/project
    inheritance."""
    await _seed_parent_task(
        storage, task_id="parent-c1", submitted_by="agent-pm",
        claimed_by="agent-eng-a",
    )
    parent = await _check_can_submit_task_parent_ownership(
        storage=storage,
        parent_task_id="parent-c1",
        caller_agent_id="agent-eng-a",
        caller_role="engineer",
    )
    assert parent["id"] == "parent-c1"
    assert parent["company_id"] == "co1"
    assert parent["project_id"] == "proj1"


async def test_parent_ownership_clause_2_role_via_agent_uuid(
    storage: SqliteStorage,
) -> None:
    """Clause 2 (role-level authority): submitter's role resolved via
    JOIN to agents. PM-A-dies-PM-B-resumes: engineer-A submits parent;
    engineer-B (different agent UUID; same role) submits subtask;
    clause 1 fails (claimed_by mismatch), clause 2 passes (both have
    role=engineer)."""
    await _seed_parent_task(
        storage, task_id="parent-c2",
        submitted_by="agent-eng-a",
        claimed_by=None,
    )
    parent = await _check_can_submit_task_parent_ownership(
        storage=storage,
        parent_task_id="parent-c2",
        caller_agent_id="agent-eng-b",
        caller_role="engineer",
    )
    assert parent["submitter_role"] == "engineer"


async def test_parent_ownership_clause_2_role_via_human_mike_literal(
    storage: SqliteStorage,
) -> None:
    """Clause 2 with HUMAN_LITERAL_ROLE_MAP resolution: parent submitted
    by Mike (submitted_by='human:mike' literal); JOIN to agents returns
    NULL for submitter_role; HUMAN_LITERAL_ROLE_MAP maps the literal to
    'pm' per CQ10 Reading A. PM-as-agent caller passes clause 2."""
    await _seed_parent_task(
        storage, task_id="parent-c2-mike",
        submitted_by=HUMAN_MIKE_LITERAL,
        claimed_by=None,
    )
    parent = await _check_can_submit_task_parent_ownership(
        storage=storage,
        parent_task_id="parent-c2-mike",
        caller_agent_id="agent-pm",
        caller_role="pm",
    )
    assert parent["submitted_by"] == HUMAN_MIKE_LITERAL


async def test_parent_ownership_neither_clause_matches_rejects(
    storage: SqliteStorage,
) -> None:
    """Neither clause matches: caller is not claimed_by parent (clause
    1 fails) and caller_role doesn't match submitter's role (clause 2
    fails). Raises PermissionError with diagnostic detail."""
    await _seed_parent_task(
        storage, task_id="parent-fail",
        submitted_by="agent-pm",
        claimed_by="agent-eng-a",
    )
    with pytest.raises(OrchestratorPermissionError, match="permission denied for submit_task"):
        await _check_can_submit_task_parent_ownership(
            storage=storage,
            parent_task_id="parent-fail",
            caller_agent_id="agent-impl",
            caller_role="implementer",
        )


# ----- _derive_company_project_for_caller (Phase 1 sentinel) ---------------


async def test_derive_company_project_phase_1_single_project_passes(
    storage: SqliteStorage,
) -> None:
    """Phase 1 single-company-single-project happy path: caller's
    company_id resolved from agents table; the single project under
    that company resolved by FK lookup."""
    company_id, project_id = await _derive_company_project_for_caller(
        storage=storage, caller_agent_id="agent-pm"
    )
    assert company_id == "co1"
    assert project_id == "proj1"


async def test_derive_company_project_multi_project_sentinel_rejects(
    storage: SqliteStorage,
) -> None:
    """Phase 1 single-project assumption pinned by runtime sentinel
    (CQ2 refinement). When a second project lands under the same
    company, raise ConflictError rather than silent-first-row-pick.
    Forces the architectural shift to update submit_task derivation
    when Phase 2 multi-project introduces."""
    await storage.execute(
        "INSERT INTO projects (id, company_id, name) VALUES "
        "('proj2', 'co1', 'Second Product')"
    )
    with pytest.raises(ConflictError, match="multiple projects"):
        await _derive_company_project_for_caller(
            storage=storage, caller_agent_id="agent-pm"
        )


# ----- submit_task handler -------------------------------------------------


async def test_submit_task_root_happy_path(
    storage: SqliteStorage,
) -> None:
    """PM submits a root engineer-targeted task. Verifies: tasks row
    written with caller-supplied fields; submitted_by=caller_agent_id
    (CQ3 static path); audit row written with action='task.submit'."""
    request = SubmitTaskInput(
        agent_role="engineer",
        description="ship feature X",
        priority=1,
        complexity=4,
    )
    out = await submit_task(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=request,
        caller_agent_id="agent-pm",
        caller_role="pm",
        caller_source="static",
    )

    assert out.task.agent_role == "engineer"
    assert out.task.description == "ship feature X"
    assert out.task.priority == 1
    assert out.task.complexity == 4
    assert out.task.submitted_by == "agent-pm"
    assert out.task.task_type is None
    assert out.task.status == "pending"

    rows = await storage.fetch_all("SELECT id, submitted_by FROM tasks")
    assert len(rows) == 1
    assert rows[0]["submitted_by"] == "agent-pm"

    audits = await storage.fetch_all(
        "SELECT actor, action, target FROM audit_log"
    )
    assert len(audits) == 1
    assert audits[0]["action"] == "task.submit"
    assert audits[0]["actor"] == "agent-pm"
    assert audits[0]["target"] == out.task.id


async def test_submit_task_subtask_inherits_company_project(
    storage: SqliteStorage,
) -> None:
    """Subtask path: parent_task_id set; ownership clause 1 passes
    (engineer-A claimed parent); subtask inherits parent's
    company_id/project_id without re-derivation."""
    # Engineer-A claims a PM-submitted parent task.
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, submitted_by, claimed_by, status) VALUES "
        "('root-1', 'co1', 'proj1', 'engineer', 'parent', 'agent-pm', "
        "'agent-eng-a', 'claimed')"
    )

    request = SubmitTaskInput(
        agent_role="implementer",
        description="implement subtask",
        parent_task_id="root-1",
    )
    out = await submit_task(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=request,
        caller_agent_id="agent-eng-a",
        caller_role="engineer",
        caller_source="static",
    )

    assert out.task.parent_task_id == "root-1"
    assert out.task.company_id == "co1"
    assert out.task.project_id == "proj1"
    assert out.task.agent_role == "implementer"


async def test_submit_task_with_task_type_populates_column(
    storage: SqliteStorage,
) -> None:
    """task_type field round-trips through the INSERT and is preserved
    on the returned Task model. Verifies the M4d task_type cohort key
    flows from input through storage to output."""
    request = SubmitTaskInput(
        agent_role="engineer",
        description="plan a thing",
        task_type="planning",
    )
    out = await submit_task(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=request,
        caller_agent_id="agent-pm",
        caller_role="pm",
        caller_source="static",
    )
    assert out.task.task_type == "planning"


async def test_submit_task_oauth_caller_submitted_by_human_mike_literal(
    storage: SqliteStorage,
) -> None:
    """CQ3 Reading A oauth path: caller_source='oauth' →
    submitted_by='human:mike' literal (per 05-data-model.md:209-216
    convention). First operational use of the OAuth-vs-static
    distinction at the data layer."""
    request = SubmitTaskInput(
        agent_role="engineer",
        description="Mike submits via OAuth",
    )
    out = await submit_task(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=request,
        caller_agent_id="agent-pm",  # Mike's OAuth token binds to PM agent
        caller_role="pm",
        caller_source="oauth",
    )
    assert out.task.submitted_by == HUMAN_MIKE_LITERAL


async def test_submit_task_audit_details_includes_caller_source(
    storage: SqliteStorage,
) -> None:
    """CQ3 belt-and-suspenders: audit details_json includes
    submitted_by AND caller_source so forensic queries can correlate
    OAuth-vs-static origin without TokenRecord lookup. Both static
    and oauth paths surface in audit log."""
    import json

    # Static caller.
    await submit_task(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=SubmitTaskInput(
            agent_role="engineer", description="static call"
        ),
        caller_agent_id="agent-pm",
        caller_role="pm",
        caller_source="static",
    )
    # OAuth caller.
    await submit_task(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=SubmitTaskInput(
            agent_role="engineer", description="oauth call"
        ),
        caller_agent_id="agent-pm",
        caller_role="pm",
        caller_source="oauth",
    )

    audit_rows = await storage.fetch_all(
        "SELECT details_json FROM audit_log "
        "WHERE action = 'task.submit' ORDER BY rowid"
    )
    assert len(audit_rows) == 2
    static_details = json.loads(audit_rows[0]["details_json"])
    oauth_details = json.loads(audit_rows[1]["details_json"])

    assert static_details["caller_source"] == "static"
    assert static_details["submitted_by"] == "agent-pm"
    assert oauth_details["caller_source"] == "oauth"
    assert oauth_details["submitted_by"] == HUMAN_MIKE_LITERAL


async def test_submit_task_atomicity_failure_rolls_back(
    storage: SqliteStorage,
) -> None:
    """Atomicity (CQ6): full-transaction wrap. Subtask submission with
    a non-existent parent_task_id raises NotFoundError mid-transaction;
    no tasks row, no audit row. Failure must leave zero residual
    state."""
    pre_task_count = (await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM tasks"
    ))["c"]
    pre_audit_count = (await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM audit_log"
    ))["c"]

    with pytest.raises(NotFoundError):
        await submit_task(
            storage=storage,
            permissions=_submit_task_resolver(),
            request=SubmitTaskInput(
                agent_role="implementer",
                description="orphan subtask",
                parent_task_id="does-not-exist",
            ),
            caller_agent_id="agent-eng-a",
            caller_role="engineer",
            caller_source="static",
        )

    post_task_count = (await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM tasks"
    ))["c"]
    post_audit_count = (await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM audit_log"
    ))["c"]
    assert post_task_count == pre_task_count
    assert post_audit_count == pre_audit_count


def test_human_literal_role_map_phase_1_shape() -> None:
    """HUMAN_LITERAL_ROLE_MAP at commit 3: single Phase 1 entry mapping
    'human:mike' → 'pm' per CQ10 Reading A. Sentinel test pins the
    Phase 1 simplification; Phase 2 generalization tracked in BACKLOG."""
    assert HUMAN_MIKE_LITERAL == "human:mike"
    assert HUMAN_LITERAL_ROLE_MAP == {"human:mike": "pm"}


# ---------------------------------------------------------------------------
# Commit 4 tests: claim_task handler + _check_can_claim helper
# ---------------------------------------------------------------------------


# ----- _check_can_claim (strict role equality; no DB; no PermissionResolver)


@pytest.mark.parametrize(
    ("caller_role", "target_agent_role", "should_pass"),
    [
        ("engineer", "engineer", True),
        ("implementer", "implementer", True),
        ("tester", "tester", True),
        ("pm", "pm", True),
        # Sibling-role rejection (spec line 111 anti-example):
        ("implementer", "tester", False),
        ("tester", "implementer", False),
        # Inverse-hierarchy rejection:
        ("engineer", "pm", False),
        ("pm", "engineer", False),
        # Cross-tier rejection:
        ("pm", "implementer", False),
    ],
)
def test_check_can_claim_strict_role_equality(
    caller_role: str, target_agent_role: str, should_pass: bool
) -> None:
    """``_check_can_claim`` enforces strict ``caller_role ==
    target_agent_role`` per ``06-mcp-tools-phase-one.md:111``. NOT a
    hierarchy walk; sibling-role claims rejected even within the same
    dispatch chain (Implementer cannot claim Tester tasks). Walks the
    role x role matrix to confirm equality semantic."""
    if should_pass:
        _check_can_claim(
            caller_role=caller_role, target_agent_role=target_agent_role
        )
    else:
        with pytest.raises(
            OrchestratorPermissionError, match="permission denied for claim_task"
        ):
            _check_can_claim(
                caller_role=caller_role,
                target_agent_role=target_agent_role,
            )


# ----- claim_task handler -------------------------------------------------


async def _seed_pending_task(
    storage: SqliteStorage,
    *,
    task_id: str,
    agent_role: str = "engineer",
    submitted_by: str = "agent-pm",
) -> None:
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, submitted_by, status) VALUES "
        "(?, 'co1', 'proj1', ?, 'pending task', ?, 'pending')",
        (task_id, agent_role, submitted_by),
    )


async def test_claim_task_pending_to_claimed_happy_path(
    storage: SqliteStorage,
) -> None:
    """Engineer claims an engineer-targeted pending task. Atomic UPDATE
    flips status pending → claimed; SELECT-verify returns the row;
    audit row written with action='task.claim'."""
    await _seed_pending_task(storage, task_id="t-claim-1")

    out = await claim_task(
        storage=storage,
        request=ClaimTaskInput(task_id="t-claim-1"),
        caller_agent_id="agent-eng-a",
        caller_role="engineer",
    )

    assert out.task.id == "t-claim-1"
    assert out.task.status == "claimed"
    assert out.task.claimed_by == "agent-eng-a"
    assert out.task.claimed_at is not None

    audits = await storage.fetch_all(
        "SELECT actor, action, target FROM audit_log "
        "WHERE action = 'task.claim'"
    )
    assert len(audits) == 1
    assert audits[0]["actor"] == "agent-eng-a"
    assert audits[0]["target"] == "t-claim-1"


async def test_claim_task_starting_from_claimed_state_raises_conflict(
    storage: SqliteStorage,
) -> None:
    """Conditional UPDATE's WHERE status='pending' clause matches 0
    rows when the task is already in 'claimed' state. SELECT-verify
    returns None (status != 'pending' OR claimed_by != caller); raises
    CONFLICT. Mechanically equivalent to the race-condition-via-
    SELECT-verify-failure scenario; test exercises the
    starting-from-non-pending-state pathway."""
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, claimed_by, claimed_at, status) VALUES "
        "('t-already-claimed', 'co1', 'proj1', 'engineer', "
        "'pre-claimed', 'agent-eng-b', datetime('now'), 'claimed')"
    )

    with pytest.raises(ConflictError, match="not in pending state"):
        await claim_task(
            storage=storage,
            request=ClaimTaskInput(task_id="t-already-claimed"),
            caller_agent_id="agent-eng-a",
            caller_role="engineer",
        )

    # Audit was rolled back; original claimed_by preserved.
    row = await storage.fetch_one(
        "SELECT claimed_by FROM tasks WHERE id = 't-already-claimed'"
    )
    assert row is not None
    assert row["claimed_by"] == "agent-eng-b"
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'task.claim'"
    )
    assert audits == []


async def test_claim_task_not_found_raises_not_found(
    storage: SqliteStorage,
) -> None:
    """task_id that doesn't resolve raises NotFoundError. The
    pre-transaction SELECT for hierarchy check catches missing-task
    before the conditional UPDATE; CONFLICT is reserved for
    state-mismatch failures."""
    with pytest.raises(NotFoundError, match="not found"):
        await claim_task(
            storage=storage,
            request=ClaimTaskInput(task_id="t-bogus"),
            caller_agent_id="agent-eng-a",
            caller_role="engineer",
        )


async def test_claim_task_role_mismatch_raises_permission_error(
    storage: SqliteStorage,
) -> None:
    """Implementer attempting to claim a Tester-targeted task fails
    the strict-equality hierarchy gate per spec line 111. The check
    runs after task lookup but before transaction; no UPDATE
    attempted; no audit row."""
    await _seed_pending_task(
        storage, task_id="t-tester", agent_role="tester"
    )

    with pytest.raises(
        OrchestratorPermissionError, match="permission denied for claim_task"
    ):
        await claim_task(
            storage=storage,
            request=ClaimTaskInput(task_id="t-tester"),
            caller_agent_id="agent-impl",
            caller_role="implementer",
        )

    # Task remains pending; no audit row.
    row = await storage.fetch_one(
        "SELECT status, claimed_by FROM tasks WHERE id = 't-tester'"
    )
    assert row is not None
    assert row["status"] == "pending"
    assert row["claimed_by"] is None
    audits = await storage.fetch_all(
        "SELECT id FROM audit_log WHERE action = 'task.claim'"
    )
    assert audits == []


# ---------------------------------------------------------------------------
# Commit 5 tests: update_task_status state machine + helper + handler
# ---------------------------------------------------------------------------


# ----- _check_can_update_task_status (two-clause OR; cancelled-conditional)


@pytest.mark.parametrize(
    (
        "scenario",
        "claimed_by",
        "submitted_by",
        "submitter_role",
        "caller_agent_id",
        "caller_role",
        "target_status",
        "expected_pass",
    ),
    [
        # Clause 1 (claimed_by match) passes for any target_status.
        (
            "clause_1_claimed_by_match_for_completed",
            "agent-eng-a", "agent-pm", "pm",
            "agent-eng-a", "engineer", "completed", True,
        ),
        (
            "clause_1_claimed_by_match_for_cancelled",
            "agent-eng-a", "agent-pm", "pm",
            "agent-eng-a", "engineer", "cancelled", True,
        ),
        # Clause 2 (cancelled + role match via UUID submitter) passes.
        (
            "clause_2_cancelled_role_match_uuid",
            "agent-eng-a", "agent-pm", "pm",
            "agent-pm", "pm", "cancelled", True,
        ),
        # Clause 2 (cancelled + role match via human:mike literal) passes.
        (
            "clause_2_cancelled_role_match_human_mike",
            "agent-eng-a", HUMAN_MIKE_LITERAL, None,
            "agent-pm", "pm", "cancelled", True,
        ),
        # Clause 2 only applies for cancelled — non-cancelled with
        # role-match-but-not-claimed-by rejects.
        (
            "clause_2_does_not_apply_for_non_cancelled",
            "agent-eng-a", "agent-pm", "pm",
            "agent-pm", "pm", "completed", False,
        ),
        # Neither clause matches — rejects.
        (
            "neither_clause_matches",
            "agent-eng-a", "agent-pm", "pm",
            "agent-impl", "implementer", "cancelled", False,
        ),
    ],
)
def test_check_can_update_task_status_two_clause_or(
    scenario: str,
    claimed_by: str,
    submitted_by: str,
    submitter_role: str | None,
    caller_agent_id: str,
    caller_role: str,
    target_status: str,
    expected_pass: bool,
) -> None:
    """``_check_can_update_task_status`` enforces two-clause OR per
    ``06-mcp-tools-phase-one.md:81``: clause 1 (claimed_by match,
    always applies) OR clause 2 (caller_role == submitter_role, only
    for target_status='cancelled'). Clause 2 submitter-role
    resolution structurally identical to
    ``_check_can_submit_task_parent_ownership`` clause 2 (commit 3);
    cross-tool extraction tracked at BACKLOG.md per CQ1 deferral.

    Walks the matrix: clause-1-pass / clause-2-pass-via-UUID /
    clause-2-pass-via-human:mike / clause-2-non-cancelled-rejects /
    neither-clause-matches."""
    task = {
        "claimed_by": claimed_by,
        "submitted_by": submitted_by,
        "submitter_role": submitter_role,
    }
    if expected_pass:
        _check_can_update_task_status(
            task=task,
            caller_agent_id=caller_agent_id,
            caller_role=caller_role,
            target_status=target_status,
        )
    else:
        with pytest.raises(OrchestratorPermissionError, match="permission denied for update_task_status"):
            _check_can_update_task_status(
                task=task,
                caller_agent_id=caller_agent_id,
                caller_role=caller_role,
                target_status=target_status,
            )


# ----- update_task_status state-machine matrix sentinel (CQ3 Reading A) ----


def _state_machine_cases() -> list[tuple[str, str, bool]]:
    """Generate the full Cartesian (from_status, to_status, allowed)
    matrix per _ALLOWED_TRANSITIONS. 7 from-states x 6 to-states from
    the UpdateTaskStatusInput.status Literal = 42 cases."""
    from meridian_orchestrator.tools.tasks import _ALLOWED_TRANSITIONS

    from_states = list(_ALLOWED_TRANSITIONS.keys())
    to_states = [
        "claimed",
        "in_progress",
        "completed",
        "failed",
        "declined",
        "cancelled",
    ]

    cases: list[tuple[str, str, bool]] = []
    for from_status in from_states:
        for to_status in to_states:
            allowed = to_status in _ALLOWED_TRANSITIONS[from_status]
            cases.append((from_status, to_status, allowed))
    return cases


@pytest.mark.parametrize(
    ("from_status", "to_status", "allowed"), _state_machine_cases()
)
async def test_update_task_status_state_machine_matrix(
    storage: SqliteStorage,
    from_status: str,
    to_status: str,
    allowed: bool,
) -> None:
    """Full-cartesian state-machine sentinel per CQ3 Reading A: walks
    42 (from_status, to_status) pairs (7 from-states x 6 to-states from
    UpdateTaskStatusInput.status Literal). Allowed transitions
    succeed; disallowed transitions raise ConflictError. Sentinel test
    pins the matrix invariant in ``_ALLOWED_TRANSITIONS``; future
    refactor of the matrix or transitions catches at this test before
    silent-fail in production.

    Caller is claimed_by the task to bypass the hierarchy gate cleanly
    (clause 1 always passes); the matrix is the load-bearing invariant
    being tested."""
    task_id = f"t-sm-{from_status}-{to_status}"
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, claimed_by) VALUES "
        "(?, 'co1', 'proj1', 'engineer', 'matrix-test', ?, "
        "'agent-eng-a')",
        (task_id, from_status),
    )

    request = UpdateTaskStatusInput(
        task_id=task_id,
        status=to_status,  # type: ignore[arg-type]
    )

    if allowed:
        out = await update_task_status(
            storage=storage,
            request=request,
            caller_agent_id="agent-eng-a",
            caller_role="engineer",
        )
        assert out.task.status == to_status
    else:
        with pytest.raises(ConflictError, match="invalid transition"):
            await update_task_status(
                storage=storage,
                request=request,
                caller_agent_id="agent-eng-a",
                caller_role="engineer",
            )


# ----- update_task_status handler (happy path + audit + edge cases) -------


async def test_update_task_status_happy_path_writes_audit_with_from_to(
    storage: SqliteStorage,
) -> None:
    """Engineer (claimed_by) updates claimed -> in_progress. Audit row
    written with action='task.status_change', details_json captures
    from_status / to_status / notes per CQ6 Reading A minimal-audit
    shape."""
    import json

    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, claimed_by) VALUES "
        "('t-update-1', 'co1', 'proj1', 'engineer', 'happy', "
        "'claimed', 'agent-eng-a')"
    )

    out = await update_task_status(
        storage=storage,
        request=UpdateTaskStatusInput(
            task_id="t-update-1",
            status="in_progress",
            notes="starting work",
        ),
        caller_agent_id="agent-eng-a",
        caller_role="engineer",
    )

    assert out.task.status == "in_progress"
    assert out.task.notes == "starting work"

    audits = await storage.fetch_all(
        "SELECT actor, action, target, details_json FROM audit_log "
        "WHERE action = 'task.status_change'"
    )
    assert len(audits) == 1
    assert audits[0]["actor"] == "agent-eng-a"
    assert audits[0]["target"] == "t-update-1"
    details = json.loads(audits[0]["details_json"])
    assert details["from_status"] == "claimed"
    assert details["to_status"] == "in_progress"
    assert details["notes"] == "starting work"


async def test_update_task_status_not_found_raises_not_found(
    storage: SqliteStorage,
) -> None:
    """task_id that doesn't resolve raises NotFoundError. Precursor
    SELECT catches missing-task before hierarchy / state-machine
    checks or transaction; CONFLICT is reserved for hierarchy /
    transition failures."""
    with pytest.raises(NotFoundError, match="not found"):
        await update_task_status(
            storage=storage,
            request=UpdateTaskStatusInput(
                task_id="t-bogus", status="completed"
            ),
            caller_agent_id="agent-eng-a",
            caller_role="engineer",
        )


# ---------------------------------------------------------------------------
# Commit 6 tests: get_queue + get_subtasks (read-side handlers)
# ---------------------------------------------------------------------------


# ----- get_queue: hierarchy filter parametrized by role --------------------


@pytest.mark.parametrize(
    ("caller_role", "caller_agent_id", "expected_visible_ids"),
    [
        # PM sees all 4 tasks (subtree-includes-self covers all roles).
        ("pm", "agent-pm", {"t-pm", "t-eng", "t-impl-claimed", "t-tester"}),
        # Engineer sees engineer + implementer + tester subtree
        # (excludes PM-targeted).
        ("engineer", "agent-eng-a", {"t-eng", "t-impl-claimed", "t-tester"}),
        # Implementer sees ONLY their own claimed tasks.
        ("implementer", "agent-impl", {"t-impl-claimed"}),
        # Tester not seeded with claimed tasks → sees nothing.
        ("tester", "agent-tester", set()),
    ],
)
async def test_get_queue_hierarchy_visibility_filter_per_role(
    storage: SqliteStorage,
    caller_role: str,
    caller_agent_id: str,
    expected_visible_ids: set[str],
) -> None:
    """get_queue's visibility-filter (CQ3 commit 6 Reading B sub-
    category) applies role-scoped SQL WHERE filter. PM/Engineer see
    inclusive subtree; Implementer/Tester see only own claimed."""
    # Seed the four-tier task scenario. Use real seeded agent ids.
    await storage.execute(
        "INSERT INTO agents (id, company_id, role, config_path) "
        "VALUES ('agent-tester', 'co1', 'tester', '/dev/null')"
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status) VALUES "
        "('t-pm', 'co1', 'proj1', 'pm', 'pm-task', 'pending'), "
        "('t-eng', 'co1', 'proj1', 'engineer', 'eng-task', 'pending'), "
        "('t-tester', 'co1', 'proj1', 'tester', 'tester-task', 'pending')"
    )
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, claimed_by) VALUES "
        "('t-impl-claimed', 'co1', 'proj1', 'implementer', "
        "'impl-claimed', 'claimed', 'agent-impl')"
    )

    out = await get_queue(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=GetQueueInput(),
        caller_agent_id=caller_agent_id,
        caller_role=caller_role,
    )

    visible_ids = {task.id for task in out.tasks}
    assert visible_ids == expected_visible_ids
    assert out.total == len(expected_visible_ids)


async def test_get_queue_status_filter(
    storage: SqliteStorage,
) -> None:
    """get_queue's status filter accepts list[TaskStatus] and combines
    via SQL AND with the hierarchy-visibility filter."""
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status) VALUES "
        "('t-1', 'co1', 'proj1', 'engineer', 'pending one', 'pending'), "
        "('t-2', 'co1', 'proj1', 'engineer', 'completed one', 'completed'), "
        "('t-3', 'co1', 'proj1', 'engineer', 'failed one', 'failed')"
    )

    # Filter to pending + completed.
    out = await get_queue(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=GetQueueInput(status=["pending", "completed"]),
        caller_agent_id="agent-pm",
        caller_role="pm",
    )
    assert {t.id for t in out.tasks} == {"t-1", "t-2"}


async def test_get_queue_agent_role_filter_conflict_raises_per_cq5(
    storage: SqliteStorage,
) -> None:
    """CQ5 commit 6: agent_role filter outside caller's hierarchy
    scope raises PERMISSION_DENIED. Implementer querying
    agent_role='engineer' is outside scope (impl's scope = {self}
    only); raises rather than silently returning empty."""
    with pytest.raises(
        OrchestratorPermissionError, match="permission denied for get_queue"
    ):
        await get_queue(
            storage=storage,
            permissions=_submit_task_resolver(),
            request=GetQueueInput(agent_role="engineer"),
            caller_agent_id="agent-impl",
            caller_role="implementer",
        )


@pytest.mark.parametrize(
    ("limit", "offset", "expected_count", "expected_total"),
    [
        # In-bounds basic cases.
        (50, 0, 5, 5),
        (2, 0, 2, 5),
        (2, 2, 2, 5),
        (100, 0, 5, 5),
        # Offset beyond total returns empty page; total still matches
        # full match-set.
        (50, 10, 0, 5),
    ],
)
async def test_get_queue_pagination_bounds(
    storage: SqliteStorage,
    limit: int,
    offset: int,
    expected_count: int,
    expected_total: int,
) -> None:
    """Pagination invariant: limit caps result count; offset advances;
    total reports full match-set count regardless of limit/offset.
    Pydantic Field(ge=1, le=500) for limit and Field(ge=0) for offset
    enforce the API-level bounds (covered by commit 2 schema tests);
    this test exercises runtime semantics."""
    for i in range(5):
        await storage.execute(
            "INSERT INTO tasks (id, company_id, project_id, agent_role, "
            "description, status) VALUES "
            f"('t-page-{i}', 'co1', 'proj1', 'engineer', 'desc', 'pending')"
        )

    out = await get_queue(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=GetQueueInput(limit=limit, offset=offset),
        caller_agent_id="agent-pm",
        caller_role="pm",
    )
    assert len(out.tasks) == expected_count
    assert out.total == expected_total


# ----- _check_can_read_subtasks (raise-gate per CQ4 Reading A) ------------


@pytest.mark.parametrize(
    ("scenario", "claimed_by", "submitted_by", "parent_role",
     "caller_agent_id", "caller_role", "expected_pass"),
    [
        # Submitter (agent-id match) passes.
        (
            "submitter_match",
            None, "agent-pm", "engineer",
            "agent-pm", "pm", True,
        ),
        # claimed_by (agent-id match) passes.
        (
            "claimed_by_match",
            "agent-eng-a", "agent-pm", "engineer",
            "agent-eng-a", "engineer", True,
        ),
        # Ancestor passes (PM is ancestor of engineer-targeted parent).
        (
            "ancestor_pm_to_engineer_parent",
            "agent-eng-a", "agent-other", "engineer",
            "agent-pm", "pm", True,
        ),
        # Sibling-role rejects (impl not in chain of tester-targeted
        # parent).
        (
            "sibling_role_not_in_chain",
            None, "agent-pm", "tester",
            "agent-impl", "implementer", False,
        ),
    ],
)
async def test_check_can_read_subtasks_in_chain_matrix(
    storage: SqliteStorage,
    scenario: str,
    claimed_by: str | None,
    submitted_by: str,
    parent_role: str,
    caller_agent_id: str,
    caller_role: str,
    expected_pass: bool,
) -> None:
    """`_check_can_read_subtasks` raise-gate: caller in chain
    (submitter / claimed_by / ancestor) passes; otherwise raises
    PERMISSION_DENIED per CQ4 Reading A. Walks the chain matrix."""
    parent_id = f"parent-{scenario}"
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, submitted_by, claimed_by) VALUES "
        "(?, 'co1', 'proj1', ?, 'parent', 'pending', ?, ?)",
        (parent_id, parent_role, submitted_by, claimed_by),
    )

    if expected_pass:
        await _check_can_read_subtasks(
            storage=storage,
            permissions=_submit_task_resolver(),
            parent_task_id=parent_id,
            caller_agent_id=caller_agent_id,
            caller_role=caller_role,
        )
    else:
        with pytest.raises(
            OrchestratorPermissionError, match="permission denied for get_subtasks"
        ):
            await _check_can_read_subtasks(
                storage=storage,
                permissions=_submit_task_resolver(),
                parent_task_id=parent_id,
                caller_agent_id=caller_agent_id,
                caller_role=caller_role,
            )


async def test_check_can_read_subtasks_parent_not_found_raises(
    storage: SqliteStorage,
) -> None:
    """parent_task_id that doesn't resolve raises NotFoundError, not
    PermissionError. Distinct from in-chain-rejection failure mode."""
    with pytest.raises(NotFoundError, match="not found"):
        await _check_can_read_subtasks(
            storage=storage,
            permissions=_submit_task_resolver(),
            parent_task_id="bogus-parent",
            caller_agent_id="agent-pm",
            caller_role="pm",
        )


# ----- get_subtasks handler (raise-gate + visibility-filter hybrid) -------


async def test_get_subtasks_returns_visible_subtasks_under_parent(
    storage: SqliteStorage,
) -> None:
    """get_subtasks happy path: PM caller (in-chain via ancestor)
    returns all subtasks under parent; visibility-filter is no-op for
    PM (sees inclusive subtree)."""
    # Parent task (engineer-targeted).
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, submitted_by, claimed_by) VALUES "
        "('parent-1', 'co1', 'proj1', 'engineer', 'parent', "
        "'in_progress', 'agent-pm', 'agent-eng-a')"
    )
    # Subtasks under parent.
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, "
        "parent_task_id, agent_role, description, status) VALUES "
        "('sub-1', 'co1', 'proj1', 'parent-1', 'implementer', "
        "'sub a', 'pending'), "
        "('sub-2', 'co1', 'proj1', 'parent-1', 'tester', "
        "'sub b', 'pending')"
    )

    out = await get_subtasks(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=GetSubtasksInput(parent_task_id="parent-1"),
        caller_agent_id="agent-pm",
        caller_role="pm",
    )
    assert {t.id for t in out.tasks} == {"sub-1", "sub-2"}


# ---------------------------------------------------------------------------
# Commit 7 tests: set_task_priority handler + helper
# ---------------------------------------------------------------------------


# ----- _check_can_set_priority (3-clause OR; agent-id submitter) ----------


@pytest.mark.parametrize(
    ("scenario", "submitted_by", "claimed_by", "task_role",
     "caller_agent_id", "caller_role", "expected_pass"),
    [
        # Clause 1: submitter (agent-id match) passes.
        (
            "submitter_agent_id_match",
            "agent-pm", None, "engineer",
            "agent-pm", "pm", True,
        ),
        # Clause 2: claimed_by (agent-id match) passes.
        (
            "claimed_by_match",
            "agent-pm", "agent-eng-a", "engineer",
            "agent-eng-a", "engineer", True,
        ),
        # Clause 3: ancestor (PM transitively manages engineer) passes.
        (
            "ancestor_pm_to_engineer_task",
            "agent-other", "agent-other", "engineer",
            "agent-pm", "pm", True,
        ),
        # Submitter agent-id-only NOT role-level: PM-A submits; PM-B
        # (different agent_id; same role) attempts to set priority.
        # Clause 1 fails (different agent_id); Clause 3 passes ONLY if
        # PM is ancestor of task's agent_role. For PM-targeted task,
        # PM IS in own subtree (is_descendant inclusive); passes via
        # clause 3. Verifies submitter clause is agent-id-only per
        # spec line 105 wording.
        (
            "submitter_agent_id_only_not_role_level",
            "agent-pm-a", None, "pm",
            "agent-pm-b", "pm", True,
        ),
        # Sibling-role rejects (impl is not in chain of tester task).
        (
            "sibling_role_rejects",
            "agent-pm", None, "tester",
            "agent-impl", "implementer", False,
        ),
        # Inverse hierarchy rejects (engineer not ancestor of PM).
        (
            "inverse_hierarchy_rejects",
            "agent-pm", None, "pm",
            "agent-eng-a", "engineer", False,
        ),
    ],
)
async def test_check_can_set_priority_three_clause_or(
    storage: SqliteStorage,
    scenario: str,
    submitted_by: str,
    claimed_by: str | None,
    task_role: str,
    caller_agent_id: str,
    caller_role: str,
    expected_pass: bool,
) -> None:
    """`_check_can_set_priority` enforces 3-clause OR per spec line
    105: submitter (agent-id-level per pre-prep flag 1 verification)
    OR claimed-by (agent-id) OR ancestor (transitive role).

    Mechanically identical to commit 6's _check_can_read_subtasks
    shape; cross-tool duplication tracked at BACKLOG.md § in-chain
    raise-gate logic duplication watch entry."""
    task_id = f"t-prio-{scenario}"
    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, submitted_by, claimed_by) VALUES "
        "(?, 'co1', 'proj1', ?, 'priority-test', 'pending', ?, ?)",
        (task_id, task_role, submitted_by, claimed_by),
    )

    if expected_pass:
        result = await _check_can_set_priority(
            storage=storage,
            permissions=_submit_task_resolver(),
            task_id=task_id,
            caller_agent_id=caller_agent_id,
            caller_role=caller_role,
        )
        assert result["id"] == task_id
    else:
        with pytest.raises(
            OrchestratorPermissionError, match="permission denied for set_task_priority"
        ):
            await _check_can_set_priority(
                storage=storage,
                permissions=_submit_task_resolver(),
                task_id=task_id,
                caller_agent_id=caller_agent_id,
                caller_role=caller_role,
            )


async def test_check_can_set_priority_not_found_raises(
    storage: SqliteStorage,
) -> None:
    """task_id that doesn't resolve raises NotFoundError."""
    with pytest.raises(NotFoundError, match="not found"):
        await _check_can_set_priority(
            storage=storage,
            permissions=_submit_task_resolver(),
            task_id="bogus",
            caller_agent_id="agent-pm",
            caller_role="pm",
        )


# ----- set_task_priority handler (race-free UPDATE; minimal audit) --------


async def test_set_task_priority_writes_audit_with_from_to(
    storage: SqliteStorage,
) -> None:
    """Engineer (claimed_by) updates priority. Audit captures
    from/to per CQ7 commit 7 minimal-input-capture shape."""
    import json

    await storage.execute(
        "INSERT INTO tasks (id, company_id, project_id, agent_role, "
        "description, status, claimed_by, priority) VALUES "
        "('t-prio-update', 'co1', 'proj1', 'engineer', 'p3 to p1', "
        "'claimed', 'agent-eng-a', 3)"
    )

    out = await set_task_priority(
        storage=storage,
        permissions=_submit_task_resolver(),
        request=SetTaskPriorityInput(task_id="t-prio-update", priority=1),
        caller_agent_id="agent-eng-a",
        caller_role="engineer",
    )

    assert out.task.priority == 1

    audits = await storage.fetch_all(
        "SELECT actor, action, target, details_json FROM audit_log "
        "WHERE action = 'task.priority_change'"
    )
    assert len(audits) == 1
    details = json.loads(audits[0]["details_json"])
    assert details["from_priority"] == 3
    assert details["to_priority"] == 1


async def test_set_task_priority_not_found_raises(
    storage: SqliteStorage,
) -> None:
    """task_id that doesn't resolve raises NotFoundError; no UPDATE
    attempted; no audit row."""
    with pytest.raises(NotFoundError, match="not found"):
        await set_task_priority(
            storage=storage,
            permissions=_submit_task_resolver(),
            request=SetTaskPriorityInput(task_id="bogus", priority=2),
            caller_agent_id="agent-pm",
            caller_role="pm",
        )


# ---------------------------------------------------------------------------
# F1 fix: permission-denied error messages MUST NOT leak forensic detail
# ---------------------------------------------------------------------------


def test_permission_denied_messages_do_not_leak_forensic_fields() -> None:
    """Spec wording (06-mcp-tools-phase-one.md:14-22): 'No permission
    graph leakage in error messages.' Per F1 codex post-close finding +
    Workstream 1 fix: error messages are generic per-tool ('permission
    denied for <tool>'); forensic detail (caller_agent_id, task_id,
    claimed_by, submitter_role, parent_role, etc.) is logged via
    structlog WARNING with structured fields, NOT in the
    OrchestratorPermissionError message text.

    Exercises the simplest helper (_check_can_claim) to verify the
    contract; covers the message-shape invariant for ALL 7 raise sites
    via a representative case. Forbidden-substring sentinel — failure
    here means a future refactor regressed the F1 fix."""
    forbidden_substrings = [
        "agent-",            # caller_agent_id leakage
        "task-",             # task_id leakage
        "human:mike",        # literal leakage
        "claimed_by",        # field-name leakage
        "submitter",         # field-name leakage
        "submitter_role",    # field-name leakage
        "agent_role",        # field-name leakage
    ]

    try:
        _check_can_claim(caller_role="implementer", target_agent_role="tester")
    except OrchestratorPermissionError as e:
        message = str(e)
        # Generic message present.
        assert "permission denied for claim_task" in message, (
            f"expected generic 'permission denied for claim_task'; got {message!r}"
        )
        # No forensic-field leakage.
        for forbidden in forbidden_substrings:
            assert forbidden not in message, (
                f"forensic-field leakage in error message: {forbidden!r} "
                f"appears in {message!r}. F1 spec violation regression."
            )
    else:
        msg = "_check_can_claim should have raised OrchestratorPermissionError"
        raise AssertionError(msg)

