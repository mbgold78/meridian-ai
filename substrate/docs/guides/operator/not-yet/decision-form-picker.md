---
status: blocked-on-ui
verb: pick affected systems/processes on the New Decision form
spec: substrate/docs/pipeline/specs/operator-decision-picker.yaml (status: blocked)
reserved-selectors: decision-affected-picker
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Decision-form entity picker — not yet built

**What this will be.** A picker for **Affected Systems** and **Affected
Manual Processes** on the Future State Designer's New Decision form,
replacing the paste-the-IDs fields.

**What exists today.** The form takes comma-separated entity IDs — workable
for the engagement team (its actual users; executives consume decisions
through the canvas Log tab and the register table), but it requires knowing
the IDs. Entity IDs are readable from the canvas drawer and the API.

**When it ships.** The picker PR claims the reserved name, the capture spec
flips to `active`, and this stub becomes a step in the designer
documentation.
