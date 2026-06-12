---
status: blocked-on-ui
verb: issue a cascade from the UI (incl. AI-assisted drafting)
spec: substrate/docs/pipeline/specs/operator-cascade-authoring.yaml (status: blocked)
reserved-selectors: cascade-issue-button, cascade-author-form, cascade-author-ai-draft
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Issue a cascade from the UI — not yet built

**What this will be.** An "issue a cascade" affordance on the dashboard's
cascade grid: a form for the critique text, acceptance criteria and target
chain, with optional AI-assisted drafting (Anthropic-key-gated like every AI
assist surface).

**What exists today.** Cascade authoring is operator-grade only:
draft → PATCH → issue via the API, exactly as
[task 4](../04-cascades.md) drives it from its committed transcript. The
dashboard is the read side — your issued cascade appears in the grid the
moment it exists.

**When it ships.** The authoring PR claims the three reserved names, the
capture spec flips to `active`, and task 4 gains the UI path beside the API
path.
