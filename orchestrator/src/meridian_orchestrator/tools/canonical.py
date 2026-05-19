"""Canonical-clone registry MCP tools.

M5-canonical-clone-mgmt slice 3 surfaces the
:class:`bridge.canonical_registry.CanonicalCloneRegistry` register / list /
unregister operations as PM-callable MCP tools, layered on top of the
Slice 1 storage substrate and the Slice 2 dispatcher refactor.

Three tools:

- ``register_canonical_clone`` — INSERT a new canonical row; emits
  ``canonical_clone.registered`` audit row.
- ``list_canonical_clones`` — SELECT all canonical rows ordered by
  ``created_at`` ASC. Read-only; no audit emission (mirrors
  ``get_queue`` precedent).
- ``unregister_canonical_clone`` — DELETE a canonical row; emits
  ``canonical_clone.unregistered`` audit row.

**Constraint #7 honored** (no changes to ``bridge/canonical.py`` or
``bridge/canonical_registry.py``): ``bridge.canonical.CanonicalClone``
does NOT carry a ``name`` field; ``CanonicalCloneRegistry.list_all``
returns ``list[CanonicalClone]`` without names. The Pydantic shadow
:class:`CanonicalCloneModel` carries ``name`` — synthesized at the
handler boundary either from the request (``register_canonical_clone``)
or from a direct SELECT (``list_canonical_clones`` via the private
:func:`_list_with_names` helper).

**PM-only authorization** is enforced wrapper-side via
``PermissionResolver.require_tool``. Slice 3 ships the tools with no
role's ``mcp_tools_allowed`` listing them — every call returns
``PERMISSION_DENIED`` until slice 3 Commit 2 lands the ``pm.yaml``
activation.

**PM Operator Addendum ratifications (Q1–Q8):**

- Q1 — Reserved name ``'default'`` rejected on register AND unregister
  at the handler boundary post-Pydantic.
- Q2 — Active-dispatch unregister rejection uses positive-form
  ``status IN ('queued', 'running')``.
- Q3 — Pydantic ``name`` regex ``^[a-z][a-z0-9-]{0,62}$``, length 1–63.
- Q4 — Absolute-path check via ``Path(request.path).is_absolute()``
  at the handler boundary; no disk-existence check.
- Q5 — ``CanonicalCloneModel`` synthesizes ``name``; bridge dataclass
  untouched.
- Q6 — Audit action strings ``canonical_clone.registered`` and
  ``canonical_clone.unregistered`` (task naming; supersedes roadmap).
- Q7 — ``upstream_url`` stored verbatim in audit payload (no redaction).
- Q8 — Permission failures emit no audit rows.
"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path

from pydantic import BaseModel, Field

from meridian_orchestrator.audit.logger import write_audit
from meridian_orchestrator.bridge.canonical_registry import (
    CanonicalCloneRegistry,
)
from meridian_orchestrator.errors import (
    AuthError,
    ConflictError,
    ValidationError,
)
from meridian_orchestrator.storage.interface import Storage

# ----- Pydantic schemas ----------------------------------------------------


# Canonical-name validation regex per Q3 ratification:
# - lowercase letter / digit / hyphen
# - must start with a letter
# - 1–63 chars (DNS-label-length ceiling)
_CANONICAL_NAME_PATTERN = r"^[a-z][a-z0-9-]{0,62}$"


class CanonicalCloneModel(BaseModel):
    """Pydantic shadow of :class:`bridge.canonical.CanonicalClone` that
    carries the canonical's ``name``.

    Q5 ratification: the bridge dataclass does NOT carry ``name``;
    handlers synthesize this model directly from either the request
    fields (``register_canonical_clone``) or from a direct SQL SELECT
    against the ``canonical_clones`` table (``list_canonical_clones``
    via :func:`_list_with_names`).
    """

    name: str
    path: str
    upstream_url: str
    default_branch: str


class RegisterCanonicalCloneInput(BaseModel):
    name: str = Field(
        min_length=1, max_length=63, pattern=_CANONICAL_NAME_PATTERN
    )
    path: str = Field(min_length=1)
    upstream_url: str = Field(min_length=1)
    default_branch: str = "main"


class RegisterCanonicalCloneOutput(BaseModel):
    canonical: CanonicalCloneModel


class ListCanonicalClonesInput(BaseModel):
    pass


class ListCanonicalClonesOutput(BaseModel):
    canonicals: list[CanonicalCloneModel]


class UnregisterCanonicalCloneInput(BaseModel):
    name: str = Field(
        min_length=1, max_length=63, pattern=_CANONICAL_NAME_PATTERN
    )


class UnregisterCanonicalCloneOutput(BaseModel):
    unregistered_name: str


# ----- Internal helpers ----------------------------------------------------


async def _list_with_names(storage: Storage) -> list[CanonicalCloneModel]:
    """Direct SELECT against the ``canonical_clones`` table that
    materializes the Pydantic shadow including ``name``.

    Q5 ratification: ``bridge.canonical_registry.list_all()`` returns
    ``list[CanonicalClone]`` without names (Constraint #7 prohibits
    modifying the bridge dataclass or registry). The handler reads the
    table directly to get ``name`` per row.
    """
    rows = await storage.fetch_all(
        "SELECT name, path, upstream_url, default_branch "
        "FROM canonical_clones ORDER BY created_at ASC"
    )
    return [
        CanonicalCloneModel(
            name=row["name"],
            path=row["path"],
            upstream_url=row["upstream_url"],
            default_branch=row["default_branch"],
        )
        for row in rows
    ]


# ----- Handlers ------------------------------------------------------------


async def register_canonical_clone(
    *,
    storage: Storage,
    registry: CanonicalCloneRegistry,
    request: RegisterCanonicalCloneInput,
    caller_agent_id: str,
) -> RegisterCanonicalCloneOutput:
    """INSERT a new canonical row; emit ``canonical_clone.registered``.

    Validation order (post-Pydantic):
    1. Reserved-name check (Q1): ``name == 'default'`` raises
       :class:`ConflictError`.
    2. Absolute-path check (Q4): ``Path(request.path).is_absolute()``
       raises :class:`ValidationError`.

    INSERT + audit are wrapped in a single ``storage.transaction()``
    per the M4d CQ6 / β-S11-4 atomicity discipline.

    :class:`bridge.canonical_registry.CanonicalAlreadyRegisteredError`
    propagates unchanged from ``registry.register`` (duplicate name).
    """
    if request.name == "default":
        raise ConflictError(
            "canonical name 'default' is reserved"
        )

    path_obj = Path(request.path)
    if not path_obj.is_absolute():
        raise ValidationError("path must be absolute")

    async with storage.transaction():
        await registry.register(
            name=request.name,
            path=path_obj,
            upstream_url=request.upstream_url,
            default_branch=request.default_branch,
        )
        await write_audit(
            storage,
            actor=caller_agent_id,
            action="canonical_clone.registered",
            target=request.name,
            details={
                "default_branch": request.default_branch,
                "name": request.name,
                "path": str(path_obj),
                "upstream_url": request.upstream_url,
            },
        )

    return RegisterCanonicalCloneOutput(
        canonical=CanonicalCloneModel(
            name=request.name,
            path=str(path_obj),
            upstream_url=request.upstream_url,
            default_branch=request.default_branch,
        )
    )


async def list_canonical_clones(
    *,
    storage: Storage,
    request: ListCanonicalClonesInput,  # noqa: ARG001
) -> ListCanonicalClonesOutput:
    """Read-only list of all canonical rows ordered by ``created_at`` ASC.

    Q5: bypasses ``CanonicalCloneRegistry.list_all`` to read ``name``
    per row directly from the table (Constraint #7 preserves the
    bridge dataclass shape).

    No audit emission per ``get_queue`` precedent (high-frequency
    read-only).
    """
    canonicals = await _list_with_names(storage)
    return ListCanonicalClonesOutput(canonicals=canonicals)


async def unregister_canonical_clone(
    *,
    storage: Storage,
    registry: CanonicalCloneRegistry,
    request: UnregisterCanonicalCloneInput,
    caller_agent_id: str,
) -> UnregisterCanonicalCloneOutput:
    """DELETE a canonical row; emit ``canonical_clone.unregistered``.

    Validation order (post-Pydantic):
    1. Reserved-name check (Q1): ``name == 'default'`` raises
       :class:`ConflictError` — the dispatcher consumes ``get_default()``
       per-call (Slice 2), so unregistering would break the next
       dispatch.
    2. Active-dispatch check (Q2): any ``dispatches`` row with
       ``canonical_name = <name> AND status IN ('queued', 'running')``
       raises :class:`ConflictError`. Terminal-state dispatches do NOT
       block.

    DELETE + audit are wrapped in a single ``storage.transaction()``.

    :class:`bridge.canonical_registry.CanonicalNotFoundError` propagates
    unchanged from ``registry.unregister`` (no row with that name).
    """
    if request.name == "default":
        raise ConflictError(
            "canonical name 'default' cannot be unregistered (reserved)"
        )

    in_use_row = await storage.fetch_one(
        "SELECT COUNT(*) AS c FROM dispatches "
        "WHERE canonical_name = ? AND status IN ('queued', 'running')",
        (request.name,),
    )
    if in_use_row is not None and in_use_row["c"] > 0:
        raise ConflictError(
            f"canonical {request.name!r} has {in_use_row['c']} "
            "active dispatch row(s); finish or cancel them before unregistering"
        )

    async with storage.transaction():
        await registry.unregister(request.name)
        await write_audit(
            storage,
            actor=caller_agent_id,
            action="canonical_clone.unregistered",
            target=request.name,
            details={"name": request.name},
        )

    return UnregisterCanonicalCloneOutput(unregistered_name=request.name)


# ----- MCP wrapper factory -------------------------------------------------


def _register_canonical_clone_tools(
    mcp: object,
    storage: Storage,
    token_store: object,
    registry: object,
    get_canonical_registry: Callable[[], CanonicalCloneRegistry | None],
) -> None:
    """Register the three canonical-clone MCP tools on ``mcp``.

    Mirrors the existing factory pattern (e.g.,
    ``server._register_dispatch_initiation_tools``): each ``@mcp.tool``
    wrapper resolves caller identity + role, gates on
    ``PermissionResolver.require_tool``, records activity via
    ``_observe_caller``, resolves the lazy ``get_canonical_registry``
    callable, and delegates to the pure handler.

    Server-side helpers (``_resolve_agent_id``,
    ``_resolve_caller_role_via_token``, ``_observe_caller``) are imported
    lazily to avoid the circular ``server.py`` ↔ ``tools/canonical.py``
    import edge.
    """
    from meridian_orchestrator.agents.permissions import PermissionResolver
    from meridian_orchestrator.server import (
        _observe_caller,
        _resolve_agent_id,
        _resolve_caller_role_via_token,
    )

    @mcp.tool(  # type: ignore[attr-defined]
        name="register_canonical_clone",
        description=(
            "PM only. Register a new canonical-clone row in the daemon's "
            "canonical_clones registry. name must match "
            "^[a-z][a-z0-9-]{0,62}$ and is rejected if equal to "
            "'default' (reserved; auto-seeded at boot). path must be "
            "absolute; existence is not checked at registration time "
            "(deferred to ensure_provisioned). Emits "
            "canonical_clone.registered audit row with "
            "{default_branch, name, path, upstream_url}."
        ),
    )
    async def _register_canonical_clone(
        name: str,
        path: str,
        upstream_url: str,
        default_branch: str = "main",
    ) -> RegisterCanonicalCloneOutput:
        agent_id = _resolve_agent_id()
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())  # type: ignore[attr-defined]
        resolver.require_tool(caller_role, "register_canonical_clone")  # type: ignore[arg-type]
        await _observe_caller(storage, token_store, "register_canonical_clone")  # type: ignore[arg-type]
        canonical_registry = get_canonical_registry()
        if canonical_registry is None:
            raise AuthError("canonical registry not available")
        request = RegisterCanonicalCloneInput(
            name=name,
            path=path,
            upstream_url=upstream_url,
            default_branch=default_branch,
        )
        return await register_canonical_clone(
            storage=storage,
            registry=canonical_registry,
            request=request,
            caller_agent_id=agent_id,
        )

    @mcp.tool(  # type: ignore[attr-defined]
        name="list_canonical_clones",
        description=(
            "PM only. List all canonical-clone rows ordered by "
            "created_at ASC. Read-only; not audited. Returns the "
            "Pydantic shadow CanonicalCloneModel per row (name, path, "
            "upstream_url, default_branch)."
        ),
    )
    async def _list_canonical_clones() -> ListCanonicalClonesOutput:
        _resolve_agent_id()
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())  # type: ignore[attr-defined]
        resolver.require_tool(caller_role, "list_canonical_clones")  # type: ignore[arg-type]
        await _observe_caller(storage, token_store, "list_canonical_clones")  # type: ignore[arg-type]
        canonical_registry = get_canonical_registry()
        if canonical_registry is None:
            raise AuthError("canonical registry not available")
        return await list_canonical_clones(
            storage=storage,
            request=ListCanonicalClonesInput(),
        )

    @mcp.tool(  # type: ignore[attr-defined]
        name="unregister_canonical_clone",
        description=(
            "PM only. Unregister a canonical-clone row by name. Rejects "
            "name='default' (reserved). Rejects when any dispatches row "
            "references the canonical with status IN ('queued', "
            "'running') (terminal-state dispatches do not block). "
            "Emits canonical_clone.unregistered audit row with {name} "
            "(minimal payload; path/upstream_url reconstructable from "
            "the matching .registered audit row)."
        ),
    )
    async def _unregister_canonical_clone(
        name: str,
    ) -> UnregisterCanonicalCloneOutput:
        agent_id = _resolve_agent_id()
        caller_role = _resolve_caller_role_via_token()
        resolver = PermissionResolver(registry.configs_by_role())  # type: ignore[attr-defined]
        resolver.require_tool(caller_role, "unregister_canonical_clone")  # type: ignore[arg-type]
        await _observe_caller(storage, token_store, "unregister_canonical_clone")  # type: ignore[arg-type]
        canonical_registry = get_canonical_registry()
        if canonical_registry is None:
            raise AuthError("canonical registry not available")
        request = UnregisterCanonicalCloneInput(name=name)
        return await unregister_canonical_clone(
            storage=storage,
            registry=canonical_registry,
            request=request,
            caller_agent_id=agent_id,
        )


__all__ = [
    "CanonicalCloneModel",
    "ListCanonicalClonesInput",
    "ListCanonicalClonesOutput",
    "RegisterCanonicalCloneInput",
    "RegisterCanonicalCloneOutput",
    "UnregisterCanonicalCloneInput",
    "UnregisterCanonicalCloneOutput",
    "list_canonical_clones",
    "register_canonical_clone",
    "unregister_canonical_clone",
]
