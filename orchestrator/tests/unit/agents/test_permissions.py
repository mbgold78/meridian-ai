"""Unit tests for agents.permissions."""

from __future__ import annotations

import pytest

from meridian_orchestrator.agents.config import AgentConfig
from meridian_orchestrator.agents.permissions import PermissionResolver
from meridian_orchestrator.errors import PermissionError as OrchestratorPermissionError


def _agent(role: str, manages: list[str] | None = None, tools: list[str] | None = None) -> AgentConfig:
    return AgentConfig(
        role=role,  # type: ignore[arg-type]
        display_name=role.upper(),
        reports_to=None if role == "pm" else "pm",  # type: ignore[arg-type]
        manages=manages or [],  # type: ignore[arg-type]
        system_prompt="x",
        mcp_tools_allowed=tools or [],
        # Required per F6.2 Q2 disposition (hard-fail surfacing config
        # drift). Permission tests don't depend on effort value; "high"
        # is a stable test default. Removing this kwarg without context
        # would silently violate the discipline.
        effort="high",
    )


def _resolver() -> PermissionResolver:
    return PermissionResolver(
        {
            "pm": _agent("pm", manages=["engineer"], tools=["submit_task", "notify_human"]),
            "engineer": _agent("engineer", manages=["implementer", "tester"], tools=["submit_task", "dispatch_implementer"]),
            "implementer": _agent("implementer", tools=["claim_task", "complete_dispatch"]),
            "tester": _agent("tester", tools=["claim_task", "complete_dispatch"]),
        }
    )


def test_check_tool_allows_listed_tool() -> None:
    r = _resolver()
    decision = r.check_tool("pm", "submit_task")
    assert decision.allowed is True


def test_check_tool_denies_unlisted_tool() -> None:
    r = _resolver()
    decision = r.check_tool("engineer", "notify_human")
    assert decision.allowed is False
    assert "not in" in decision.reason


def test_require_tool_raises_on_denial() -> None:
    r = _resolver()
    with pytest.raises(OrchestratorPermissionError):
        r.require_tool("implementer", "submit_task")


def test_require_tool_silent_on_allow() -> None:
    r = _resolver()
    r.require_tool("implementer", "claim_task")  # no exception


def test_unknown_caller_role_denied() -> None:
    r = _resolver()
    decision = r.check_tool("nonexistent", "submit_task")  # type: ignore[arg-type]
    assert decision.allowed is False
    assert "unknown caller role" in decision.reason


def test_is_descendant_self() -> None:
    r = _resolver()
    assert r.is_descendant("pm", "pm") is True


def test_is_descendant_direct_child() -> None:
    r = _resolver()
    assert r.is_descendant("pm", "engineer") is True


def test_is_descendant_transitive() -> None:
    r = _resolver()
    assert r.is_descendant("pm", "implementer") is True
    assert r.is_descendant("pm", "tester") is True


def test_is_descendant_not_in_subtree() -> None:
    r = _resolver()
    assert r.is_descendant("engineer", "pm") is False
    assert r.is_descendant("implementer", "tester") is False


def test_is_ancestor_inverse_of_descendant() -> None:
    r = _resolver()
    assert r.is_ancestor("engineer", "pm") is True
    assert r.is_ancestor("pm", "engineer") is False


def test_is_in_subtree() -> None:
    r = _resolver()
    assert r.is_in_subtree("pm", "implementer") is True
    assert r.is_in_subtree("engineer", "tester") is True
    assert r.is_in_subtree("tester", "engineer") is False


def test_manages_directly_strict_one_hop() -> None:
    """``manages_directly`` is strictly one-hop, NOT transitive — the
    semantics ``submit_task``'s hierarchy gate requires per
    ``06-mcp-tools-phase-one.md:44``. Mike→PM→Engineer→{Impl,Tester}
    are direct edges; transitive reads are spec-explicit bypasses.
    """
    r = _resolver()
    # Direct edges return True.
    assert r.manages_directly("pm", "engineer") is True
    assert r.manages_directly("engineer", "implementer") is True
    assert r.manages_directly("engineer", "tester") is True


def test_manages_directly_transitive_returns_false() -> None:
    """The PM→Implementer transitive path must return False per spec
    line 44 anti-example: 'PM **cannot** submit a task with
    agent_role=implementer — that's a hierarchy bypass.' This is the
    behavioral distinction from ``is_descendant`` (which returns True
    for transitive paths)."""
    r = _resolver()
    assert r.manages_directly("pm", "implementer") is False
    assert r.manages_directly("pm", "tester") is False
    # Confirm is_descendant disagrees on the same input — the two
    # methods serve different gates.
    assert r.is_descendant("pm", "implementer") is True


def test_manages_directly_self_returns_false() -> None:
    """A role does not directly manage itself; ``manages_directly``
    returns False for self-target. Distinct from ``is_descendant``
    (which returns True for self per inclusive-subtree convention).
    The Q9 self-submit gate in ``submit_task`` is layered separately
    on top of this primitive."""
    r = _resolver()
    assert r.manages_directly("pm", "pm") is False
    assert r.manages_directly("engineer", "engineer") is False


def test_manages_directly_inverse_returns_false() -> None:
    """Inverse-hierarchy reads return False — the primitive is
    direction-sensitive. engineer does not directly manage PM."""
    r = _resolver()
    assert r.manages_directly("engineer", "pm") is False
    assert r.manages_directly("implementer", "engineer") is False


def test_manages_directly_unknown_caller_returns_false() -> None:
    """Unknown caller_role (no agent registered) returns False —
    ``_direct_manages`` returns ``[]`` for unregistered roles, so the
    membership check is trivially False."""
    r = _resolver()
    assert r.manages_directly("nonexistent", "pm") is False  # type: ignore[arg-type]
