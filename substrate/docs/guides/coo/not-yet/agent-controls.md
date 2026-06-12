---
status: blocked-on-ui
verb: pause / resume an agent from the Operations tab
spec: substrate/docs/pipeline/specs/coo-agent-controls.yaml (status: blocked)
reserved-selectors: agent-controls, agent-control-pause, agent-control-resume, agent-enforcement-disclosure
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Agent controls — not yet in the UI

**What this will be.** Pause and resume verbs on an agent's expanded
deployment panel in the Operations tab — plus the **enforcement
disclosure** that must ship with them: whether a pause is a *recorded
instruction* the agent is expected to honor or a *gateway-enforced block*,
which gates apply, and when the agent entered shadow mode.

**What exists today.** The Operations tab is read-only and says so
([task 4](../04-operations.md)): the status pill is the governance state,
and the control verbs (pause / resume / throttle / kill) are exercised by
your engagement team through the agent API, where every verb response
already carries the enforcement disclosure
([operator task 6](../../operator/06-agent-lifecycle.md)).

**When it ships.** The controls slice claims the three control names; the
enforcement disclosure is the filed UI-2 follow-on slice and claims the
fourth. The capture spec flips to `active` and this stub becomes the real
task.
