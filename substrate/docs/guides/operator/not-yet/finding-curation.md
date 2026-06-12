---
status: blocked-on-ui
verb: curate a finding (accept / reject) from the Risks tab
spec: substrate/docs/pipeline/specs/operator-finding-curation.yaml (status: blocked)
reserved-selectors: canvas-finding-curate-accept, canvas-finding-curate-reject
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Curate a finding from the Risks tab — not yet built

**What this will be.** Accept / reject affordances on a finding's expanded
detail in the canvas Risks tab. A rejected finding is a documented curation
outcome — recorded with actor identity, never an error.

**What exists today.** Curation runs API-side with full attribution
(`ai_curation_events`), as [task 3](../03-diagnostics.md) drives it from its
committed transcript; the Risks tab renders findings read-only.

**When it ships.** The curation UI PR claims the two reserved names, the
capture spec flips to `active`, and task 3 gains the in-UI curation step.
