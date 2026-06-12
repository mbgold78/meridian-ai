---
status: blocked-on-ui
verb: review / decline a contract proposal
spec: substrate/docs/pipeline/specs/coo-contract-respond.yaml (status: blocked)
reserved-selectors: contract-respond-flow
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Review or decline a contract proposal — not yet in the UI

**What this will be.** From the contract detail's action footer, a party
walks a response flow: accept the proposal's commitments, or decline —
including the contract's own documented **decline triggers**, the no-fault
exits ([task 3](../03-contracts.md)).

**What exists today.** The **Review proposal** and **Decline** buttons
render — review and decline are first-class concepts of the contract model —
but they are deliberately inactive (their tooltips say so). Contract
responses are recorded by your engagement team today
([operator task 5](../../operator/05-contracts.md)).

**When it ships.** The buttons keep their shipped names; the flow they open
claims the reserved `contract-respond-flow` name, the capture spec flips to
`active`, and this stub becomes the real task.
