# Phase 2 Engineering Team

**Status: SKETCH. This document is informational, not a contract. Phase 2 is decided after Phase 1 ships and Mike has run the system in real work for some time. The point of this document is to make sure Phase 1 doesn't accidentally close off Phase 2.**

## Phase 2 org chart

```
Mike (CEO)
  │
  └── PM (unchanged from Phase 1)
        │
        ├── Lead Engineer
        │     │
        │     ├── Backend Engineer
        │     ├── Frontend Engineer
        │     ├── Data Modeler
        │     └── Integration Engineer
        │
        └── UAT Engineer  (direct to PM, separate from Lead's chain)
```

UAT does NOT report to Lead Engineer. UAT validates against user intent and must not report to the chain it's auditing — that's the same independence-of-test-from-implementation principle the Tester role embodies in Phase 1, applied at the org-design level.

## What stays from Phase 1

- PM's role, authority, decision altitudes. Unchanged.
- The hierarchy enforcement model. PM still cannot bypass Lead Engineer to call Backend. Lead Engineer still cannot bypass UAT.
- The subscription-first cost model.
- MCP-as-service. No web UI yet.
- The `agents` table with `reports_to`. Just more rows.
- Audit log. Same schema, longer chains.
- Decline as first-class. Now propagating across more layers.

## What changes

### Engineer becomes Lead Engineer

The Phase 1 Engineer role is renamed to Lead Engineer (or the name `engineer` is repurposed — TBD at Phase 2 kickoff). Lead Engineer's job is coordination across Backend / Frontend / Data Modeler / Integration. Implementer and Tester continue to exist as the *execution* primitives, dispatched by the four specialist engineers (not by Lead Engineer directly).

### Implementer/Tester gain structure

Phase 1's Implementer/Tester are general-purpose. In Phase 2, they may specialize (BackendImplementer, FrontendImplementer, etc.) or remain general — the call comes at Phase 2 kickoff with Mike. Either way, the dispatch bridge needs to handle the routing.

### Contracts as artifacts

When Backend submits a piece of work that depends on a piece of work Frontend will do (e.g., an API route that the frontend will consume), the dependency is captured as a **contract** — a structured artifact in the knowledge base under `/product/substrate/contracts/`. The contract specifies the interface, the producer, the consumer, the acceptance criteria.

Lead Engineer arbitrates contracts. Backend cannot unilaterally change the shape of a contract Frontend depends on. Mike's role is unchanged: he sets product direction, PM coordinates, Lead Engineer coordinates engineering, contracts mediate inter-specialist coordination.

This is a deliberate architectural choice. Inter-agent free-form coordination is an n² problem. Contracts make it n.

### Decline propagation paths

In Phase 1: Implementer/Tester decline → Engineer → PM → Mike.

In Phase 2:

- Backend Implementer declines → Backend Engineer → Lead Engineer → PM → Mike
- UAT Engineer declines (this is the meaningful one) → PM → Mike. UAT bypasses Lead because UAT validates user intent, and Lead is downstream of that intent.

The Decline mechanism must preserve enough chain context that PM and Mike can reconstruct *why* a decline happened five layers down. Phase 1 establishes the wire shape (`parent_task_id`, audit log `details_json` with chain). Phase 2 stresses it.

## Phase 2 milestone sketch (M8–M12)

This is a sketch. Real planning happens at Phase 2 kickoff.

| Milestone | Theme |
|---|---|
| M8 | Lead Engineer role + role rename of Engineer; permission framework extension |
| M9 | Backend / Frontend / Data Modeler / Integration agent configs and bridge support for parallel dispatch |
| M10 | Contracts-as-artifacts: schema, contract authoring tool, contract-violation detection |
| M11 | UAT Engineer role + intent-validation harness |
| M12 | Cross-cutting: dashboard adjustments for the bigger org, audit log viewer, knowledge-base reorg |

## What Phase 1 must not preclude

Restated explicitly so Phase 1 design choices don't accidentally close doors:

1. **Multiple direct reports per agent.** PM has only Engineer in Phase 1; in Phase 2 PM has Lead Engineer + UAT Engineer. The `manages` array in agent config accommodates this from day one.
2. **Multiple agents per role: allowed from Phase 1.** The `UNIQUE(company_id, role)` constraint was dropped in Phase 1 per Mike's decision before M1 starts. Reasoning: agent registration is a deliberate operation driven by config files (the constraint protects against a failure mode that doesn't exist in practice), and Phase 2 will likely need >1 of some specialist roles (e.g., a second Backend Engineer when one is overloaded). A non-unique index on `(company_id, role)` is in place for query performance. See `05-data-model.md` § "Note on `agents` — `UNIQUE(company_id, role)` dropped" for the full rationale.
3. **Parallel sub-agent dispatch.** The bridge architecture chosen at M3 must accommodate concurrent dispatches. Phase 1 may serialize for simplicity; Phase 2 will not.
4. **Cross-tree visibility.** Lead Engineer needs to see across all four specialists. The hierarchy enforcement currently allows ancestor-of and self; Phase 2 will need same-subtree visibility too. Permission framework should be a clean extension, not a rewrite.
5. **Contract-as-artifact authoring.** No specific Phase 1 design needed beyond "knowledge base supports arbitrary directories". Already true.

## When Phase 2 kicks off

After Phase 1 has been running in real work for at least 4 weeks. Mike will have observed:

- Where PM bottlenecks
- Where Engineer's coordination breaks down (single coordinator across multi-front work?)
- Where UAT-style intent-validation is missed
- What the actual cost model looks like in practice

The Phase 2 kickoff message will be richer than the Phase 1 one because Mike will have data. Don't second-guess Phase 2 from inside Phase 1.
