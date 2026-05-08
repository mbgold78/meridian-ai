"""Domain exception hierarchy.

Per ``docs/kickoff/04-tech-stack.md`` § Custom exception hierarchy. The MCP server
maps these to tool-error codes (see ``server.py``); never raise bare ``Exception``.
"""

from __future__ import annotations


class OrchestratorError(Exception):
    """Base for all orchestrator domain errors."""

    code: str = "INTERNAL_ERROR"


class AuthError(OrchestratorError):
    """Authentication failure — invalid, missing, or revoked token."""

    code = "AUTH_FAILED"


class PermissionError(OrchestratorError):
    """Caller is authenticated but not permitted to invoke the tool/resource."""

    code = "PERMISSION_DENIED"


class BudgetError(OrchestratorError):
    """Operation rejected because it would exceed a budget envelope."""

    code = "BUDGET_EXCEEDED"


class NotFoundError(OrchestratorError):
    """Requested entity does not exist."""

    code = "NOT_FOUND"


class ConflictError(OrchestratorError):
    """Operation conflicts with current state (e.g., double-claim)."""

    code = "CONFLICT"


class ValidationError(OrchestratorError):
    """Input failed domain validation beyond pydantic schema enforcement."""

    code = "VALIDATION_ERROR"
