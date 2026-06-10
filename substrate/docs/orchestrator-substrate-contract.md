# Orchestrator ↔ Substrate Contract

**Status:** v1. Concretized from the ratified engine designs (ADR-001 cascade, ADR-002 contracts; specs in `docs/engine-spec/`). v0 stub history preserved in §Version history.

## Direction

One-directional dependency: **substrate depends on orchestrator**; orchestrator does not depend on substrate.

This means:
- Substrate can import / call orchestrator APIs.
- Orchestrator must never import or assume substrate types, schemas, or surfaces.
- Removing substrate from a deployment must not break orchestrator. Removing orchestrator from a deployment must leave substrate operational in degraded ("no AI assist") mode.

## What substrate consumes (orchestrator's MCP tool surface)

Substrate registers as a caller with its own role identity (`substrate-svc`; identity wiring lands with Phase 5 auth). It consumes exactly five tools:

| Tool | Substrate use |
|---|---|
| `check_budget_for_task(...)` | Preflight before every submission. Budget refusal is a non-event for governance: the requesting feature records `suggestion_state=ERROR` with a provenance note and the human path continues. |
| `submit_task(agent_role, description, complexity?, priority?, task_type?)` | The single dispatch verb. Known task types: `cascade-critique-draft` (cascade draft AI-suggest, Phase 2), contract remediation hook (Phase 3, optional), diagnostic generation / finding curation (Phase 4). |
| `get_dispatch_status(task_id)` | Non-blocking poll (`@Scheduled` or on-read refresh — never blocking HTTP in a request thread). |
| `get_dispatch_result(task_id)` | Retrieves agent output. Output enters substrate ONLY as *suggestions a human may promote* (e.g. draft critique text) or as *candidates substrate explicitly applies through its own governed write path* (`apply_dispatch_result` PEP for contract-relevant changes, `FindingCandidate → Finding` commit in Phase 4). Ungrounded AI output never auto-issues. |
| `get_dispatch_events(task_id)` | Digested into provenance JSON on the consuming record — the engagement's audit trail of the AI assist. |

Substrate does NOT consume orchestrator-internal tools: `dispatch_implementer` / `dispatch_tester` (no role-hierarchy bypass), `set_pm_mode`, `record_spin_rationale`, `wait_for_next_tick`.

Every orchestrator touchpoint in substrate code is behind `@ConditionalOnProperty(substrate.orchestrator.enabled)`; the consuming endpoints 404 when disabled.

## What substrate exposes to orchestrator-dispatched agents

Two surfaces, both substrate-authoritative:

**1. Read-side REST (Phase 2+).** Agents dispatched on substrate's behalf read engagement context via the public `/api/v1` GET endpoints (canonical entities, cascade read model, contract state, decision log). Context packs passed to `submit_task` reference these URLs rather than dumping payloads.

**2. `substrate-contracts` MCP server (Phase 3+).** Streamable HTTP at `/mcp` (official MCP Java SDK), gated by `substrate.contracts.mcp.enabled`:
- **Read tools (ungoverned):** `list_contracts`, `get_contract`, `get_commitments`, `get_obligations_for_principal` (the agent-ergonomics keystone: what the calling party owes now), `get_breaches`, `get_enforcement_decisions`, `check_action` (advisory PDP dry-run; the real check still happens at write).
- **Governed write tools (every call transits `ContractGateway`):** `attest_commitment`, `record_commitment_progress`, `report_metric`, `respond_to_contract`, `invoke_decline` (first-class legitimate action, never an error result), `acknowledge_breach`, `mark_breach_remediated`. `waive_breach` carries a built-in non-removable guard denying AGENT principals — waiving a breach is a human act.
- **Cascade read aliases (Phase 3):** `cascade_list`, `cascade_get`, `cascade_timeline` over the cascade read API. **No agent write path to cascades, ever** (agent-as-level-owner is a named extension point, not current behavior).

Contract authoring has no MCP tools (REST-only). Substrate remains the system of record; agents are advisors and governed parties, not privileged mutators.

## Authentication between substrate and orchestrator

- **Phases 1–4 (local/testing):** localhost trust. MCP callers identify via `X-MCP-Principal` header → `Principal(AGENT, value)`; **documented as spoofable** and acceptable only because no customer data exists in these phases.
- **Phase 5 (production):** OIDC client-credentials. Substrate authenticates to orchestrator as `substrate-svc` (PM-equivalent permission for `submit_task` to Engineer-or-lower). Agent principals on the MCP server bind to `contract_parties.mcp_principal_id`. No schema change required — identity columns exist from Phase 2/3.

## Degraded mode (substrate without orchestrator)

Degraded mode is **identical by construction**, not a parallel code path. With `substrate.orchestrator.enabled=false`:
- Cascade authoring: `POST /cascade-drafts/{id}/suggest` is absent (404); free-form/imperative authoring unaffected.
- Contracts: gateway, monitors, attestations, metrics, breaches, MCP server, signals all run on Postgres alone; the remediation `submit_task` hook and `apply_dispatch_result` PEP simply never fire.
- Diagnostics (Phase 4): operator-authored findings instead of AI generation.
- UI: "request AI assist" affordances hidden or no-op.

No other conditional paths are permitted — if a feature needs more degradation logic than a missing bean, its design is wrong.

## Deployment co-location

Substrate and orchestrator may be deployed:
- **Together** — both in the customer's environment for an AI-leveraged engagement.
- **Substrate alone** — degraded mode, fully functional system of record.
- **Orchestrator alone** — substrate absent; orchestrator unaffected by design (it never knows substrate exists).

---

## Version history

- **v0** — 2026-05-15, stub authored during Phase 0 scaffolding.
- **v1** — 2026-06-09, concretized from ADR-001/ADR-002 + engine specs: exact consumed tool set with per-feature usage, the `substrate-contracts` MCP server surface, identity posture per phase, degraded-mode-by-construction rule.
