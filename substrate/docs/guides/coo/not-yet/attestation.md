---
status: blocked-on-ui
verb: attest a commitment was honored
spec: substrate/docs/pipeline/specs/coo-attestation.yaml (status: blocked)
reserved-selectors: contract-attestation-form, contract-attestation-submit
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Attest a commitment — not yet in the UI

**What this will be.** A party to a coordination contract records, from the
contract detail page, that a commitment was honored — an attestation, with
the attesting identity attributed on the record. Attestations are part of
what the contract monitor weighs.

**What exists today.** Attestation exists as a platform verb (agent- and
API-grade); it is exercised by your engagement team and by registered
agents, and every attestation is attributed and auditable. No UI surface
renders or accepts attestations yet — the contract page shows the
commitments themselves ([task 3](../03-contracts.md)).

**When it ships.** The feature PR claims the two reserved names, the capture
spec flips to `active`, and this stub becomes the real task.
