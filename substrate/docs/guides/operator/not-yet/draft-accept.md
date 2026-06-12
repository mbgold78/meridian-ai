---
status: blocked-on-ui
verb: accept a Draft-from-Universe capability proposal into the tree
spec: substrate/docs/pipeline/specs/operator-draft-accept.yaml (status: blocked)
reserved-selectors: draft-accept
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Accept a generative capability draft — not yet built

**What this will be.** An accept button on each Draft-from-Universe card in
the capability designer: committing an AI-proposed, evidence-grounded
capability into the tree as a first-class node.

**What exists today.** The dialog is a **read-only preview**: proposals
with their evidence grounding render, but nothing can be committed —
capabilities are created by hand (Create Capability) after reading the
drafts. The feature is AI-backed, so it additionally needs an Anthropic key
even to preview.

**When it ships.** The accept PR claims the reserved name and flips the
capture spec; note the spec may remain a manual-lane capture even then (its
content depends on a live AI response).
