---
status: blocked-on-ui
verb: respond to / decline a cascade at your level
spec: substrate/docs/pipeline/specs/coo-cascade-respond.yaml (status: blocked)
reserved-selectors: cascade-actions, cascade-action-respond, cascade-action-decline
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Respond to a cascade — not yet in the UI

**What this will be.** When a cascade reaches your level, you act on it from
the cascade detail page: take the work (absorb), refine it and pass it down,
or formally decline with reasoning that travels back up. A decline is a
documented governance outcome, never an error.

**What exists today.** The read side is complete — every level, every state
pill, the full action timeline ([task 2](../02-cascades.md)). The verbs
themselves exist and are recorded by the engine; your engagement team
executes them ([operator task 4](../../operator/04-cascades.md)). No
respond/decline affordance renders anywhere in the client UI yet.

**When it ships.** The capture spec above is pre-written against the
reserved selector names and flips to `active` in the same PR that builds the
buttons; this stub becomes the real task with screenshots at that moment.
