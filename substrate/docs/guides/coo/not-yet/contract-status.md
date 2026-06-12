---
status: blocked-on-ui
verb: see a contract's operational state and monitor history
spec: substrate/docs/pipeline/specs/coo-contract-status.yaml (status: blocked)
reserved-selectors: contract-status-chip, contract-monitor-history
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Contract operational state & monitor history — not yet in the UI

**What this will be.** The contract detail page answers "where does this
contract stand right now?": a lifecycle status chip (proposed / active /
declined / expired) and the monitor's run history — when the platform last
checked each commitment and what it concluded, including open breaches and
their grace deadlines.

**What exists today.** An activated contract still shows only its "Proposed"
date line here ([task 3](../03-contracts.md) reads this surface honestly).
The data already exists engine-side — monitor runs and breach summaries ride
the wire today and your engagement team reads them through operator tooling
([operator task 5](../../operator/05-contracts.md)); the activation event is
visible as a ledger row in the decision register.

**When it ships.** This is the filed UI-1 follow-on product slice. It claims
the two reserved names, the capture spec flips to `active`, and this stub
becomes the real task.
