# Kickoff Package — Meridian AI Orchestrator (Phase 1)

This directory holds the design documents that govern the Phase 1 build. They were authored before any code was written and are the canonical reference for what Phase 1 is, what it isn't, and how it gets built.

If a document and the running code disagree, the code is wrong, the document is stale, or both. Either way, surface the divergence — don't paper over it.

## How to read this package

Read `01` through `04` first. They establish *who*, *why*, *what*, and *with what tools*. Everything else builds on those four.

After that, the order matters less. `05`–`07` are the technical contracts (data, tools, agents). `08`–`10` are the build playbook (milestones, tests, operational protocol). `11`–`13` are forward-looking (the bridge design that lands at M3, the Phase 2 org structure, the eventual monorepo merge).

## The 15 documents

| # | Title | Purpose |
|---|---|---|
| 00 | This README | Orientation |
| 01 | Context | Mike, Meridian, two-codebase reality |
| 02 | Architecture | MCP-as-service, local-only Python daemon |
| 03 | Phase 1 scope | Agent set, definition of done, out-of-scope |
| 04 | Tech stack | Locked library and convention choices |
| 05 | Data model | Schema, filesystem layout, migrations, backup |
| 06 | MCP tools (Phase 1) | Full tool specs, permission model, hierarchy enforcement |
| 07 | Agent configs (Phase 1) | YAML configs for PM, Engineer, Implementer, Tester |
| 08 | Implementation plan | Milestones M1–M7 |
| 09 | Testing & acceptance | Testing philosophy and per-milestone test requirements |
| 10 | Operational protocol | How Mike and Claude Code collaborate on this build |
| 11 | Engineer bridge (stub) | Constraints; full design lands at M3 |
| 12 | Phase 2 engineering team | Org chart, contracts-as-artifacts, M8–M12 sketch |
| 13 | Merge plan | Eventual `meridian-ai/` monorepo |
| 14 | Telegram setup | Manual steps for Mike to enable PM↔Mike notifications (M6) |

## Amendments

The numbered documents are the source of truth. When a substantial design change happens after kickoff, the change is captured in a separate `*-addendum.md` file in this directory and merged into the relevant numbered docs. The addendum file persists as a record of *why* the change was made; the numbered docs reflect the current state.

Current addenda:

- `orchestrator-utilization-addendum.md` (2026-04-28) — aggressive subscription utilization (continuous mode, parallel dispatch, window-aware burn). Merged into 02, 05, 06, 07, 08, 11.

## Ground rules (terse form)

- Mike sets the pace. Push back once on real concerns; if confirmed, deliver.
- Direct, honest pushback over flattery. No over-celebration. No protective hand-wringing.
- One question at a time when interviewing.
- Don't generate work Mike hasn't asked for.
- Mike talks to PM. PM talks to Engineer. Engineer dispatches Implementer/Tester. No bypass.
- Strategic-non-downgradable questions go up. Everything else, decide and log.
- When in doubt about decision altitude, escalate.

Long form of the rules is in `01-context.md` and the PM system prompt in `07-agent-configs-phase-one.md`.

## Sub-milestone closeouts

When a sub-milestone in M3-M7's chain closes, the close review writes a canonical artifact at `../closeouts/<sub-milestone-slug>.md` (e.g., `../closeouts/M5-subscription-routing.md`). The closeout is the **chain-wide retrospective** for that sub-milestone — distinct from the per-commit prep dispositions that surface during the build.

Convention (one doc per sub-milestone, naming by sub-milestone slug):

- **Chain summary** — commits, hashes, test count trajectory, line counts.
- **Spec amendments shipped** — cross-references to commits.
- **Migrations shipped** — version + commit + brief description.
- **New cross-layer single-source-of-truth instances** — constants / helpers compounded during the chain.
- **Watch-for-pattern outcomes** — threshold-of-three resolutions, new patterns at 1-2 instances.
- **Codex finding status** — when a code-review pass ingests findings; track their disposition.
- **Lineage notes for future-Claude** — non-obvious reasoning that wouldn't surface from cold-reading the code.
- **Address-now items routed forward** — explicit list with status (resolved during chain / BACKLOG'd / still deferred).
- **Drift-detection scan** — output of the spec-amendment-runtime-drift scan (empty-scan acknowledgement is its own signal).
- **Drift between kickoff scope and landed scope** — anything that diverged.
- **Discipline-pattern observations** — patterns reinforced or surfaced during the chain.
- **What's next** — pointer to remaining sub-milestones in the parent milestone.

The closeout artifact is the answer to "if I read this chain cold six months from now, what do I need to know?" — the prep checkpoints answer "what should we do next." Pair, not substitute.

## Phase-boundary acceptance-criteria-coverage scan

Sub-milestone closeouts catch drift inside the closing sub-milestone's
scope. Drift that lives between sub-milestone scopes — typically
acceptance criteria at the M-level that cross multiple sub-milestone
chains, or M-level deliverables that were specified but never picked up
by any sub-milestone — is outside the closeout convention's reach by
construction.

The phase-boundary scan is a separate convention that addresses this gap.

**Trigger:** before any Phase closeout (Phase 1 closeout being the next
instance). Also fires ad-hoc when a sub-milestone prep checkpoint
discovers a phantom-surface drift (the F2.1 commit-1 contradiction during
M5-continuous-pm-driver grounding, 2026-05-03, was the first such
ad-hoc trigger).

**What this convention specifically catches** (and what it does not):
sub-milestone closeouts catch sub-milestone-internal drift — within the
closing sub-milestone's claimed scope, did everything ship cleanly? They
don't see deliverables that were specified at M-level (or phase-level)
but never picked up by any sub-milestone as scope. The 2026-05-03 M4
finding is the canonical example: every M4 sub-milestone shipped its
scoped deliverables (all I-A at sub-milestone granularity), but
`tools/tasks.py` and the six task-management MCP tools were M-level
deliverables that no sub-milestone took as scope. The phase-boundary
scan is the convention that sees this — its value is specifically
catching **inter-sub-milestone scope drift**, not duplicating
sub-milestone-internal verification. Future-Claude running this scan
should look for M-level deliverables unclaimed by any sub-milestone, not
re-verify sub-milestone-internal acceptance.

**Scope:** every M-level deliverable shipped in the phase. For each:

1. Read the deliverable's acceptance criterion from
   `08-implementation-plan.md` or the deliverable's spec doc.
2. Verify acceptance-criterion-coverage exists in production code paths,
   not just test fixtures. Distinguish three categories:
   - **(I-A)** implemented and acceptance-met
   - **(I-NR)** implemented but acceptance-not-runnable end-to-end
     (typically because tests pass via direct SQL fixtures but the
     production-side caller surface to seed the path is missing)
   - **(N-I)** not implemented

**Output:** a Phase-closeout TODO list at
`../working/phase-N-acceptance-drift.md`. For each (N-I) or (I-NR) entry,
disposition either:

- **Remediate before Phase close** (a remediation sub-milestone, e.g.,
  M4d for the M4 task-management surface gap discovered 2026-05-03).
- **Document as known Phase-N+1-debt** (carry-forward with explicit
  framing — "spec-vs-implementation drift, not authorized deferral" if
  applicable).

**Discipline rule:** if any acceptance-criterion-coverage gap is found,
the gap surfaces explicitly in the Phase closeout doc with the same
"spec-vs-implementation drift, not authorized deferral" framing as the
M4 finding from 2026-05-03. Phase closure does not happen with silent
gaps; gaps are either remediated or documented as known carry-forward
debt.

The convention is durable: future Phase boundaries (Phase 2 closeout,
etc.) inherit the pattern. The first instance at 2026-05-03 surfaced
that the M4 closeout did not iterate acceptance criteria against shipped
state — had it done so, the gap would have been caught at M4 close, not
6 weeks later mid-M5. Per-milestone analog of this convention (iterate
acceptance criteria explicitly at every M-level close) is captured by
extension; see "Pattern observation: M4 built; M5 wires" caveat in
`08-implementation-plan.md`.

## Convention-doc migration trigger

The two convention sections above (sub-milestone closeout + phase-boundary
scan) live in this kickoff README because there's no dedicated convention
doc yet, and co-location with the kickoff narrative is cheaper than
splitting at low convention count. As more durable conventions accumulate,
the right home is a dedicated convention doc.

**Current count: four durable conventions:**

1. Sub-milestone closeout artifact (established M5-subscription-routing
   close, 2026-05-02).
2. Phase-boundary acceptance-criteria-coverage scan (established
   2026-05-03 via M5-continuous-pm-driver F2.1 commit-1 contradiction).
3. Watch-for-pattern (established mid-M5 via BACKLOG entries with
   explicit thresholds — `bridge/` → `tools/` cross-layer imports at
   threshold-of-three; production-code-surface-for-testability at
   threshold-of-three; etc.).
4. Threshold-of-three same-commit extraction (established
   M5-subscription-routing commit 2 — UTC-coercion utility extracted
   when the third call site appeared, in the same commit as the
   triggering call site rather than deferring to a hygiene commit).

**Migration trigger:** when a fifth durable discipline convention lands,
lift the convention sections out of this README into a dedicated
`docs/conventions/00-README.md` (or `docs/closeouts/00-README.md` —
naming dispositioned at migration time). Discrete trigger, not a vibes
call. Until the trigger fires, these sections stay co-located here.

## What this package is *not*

- A user manual. End-user docs live in the top-level `README.md` (authored at M1, refined at M7).
- A spec for Phase 2. `12-phase-two-engineering-team.md` is a sketch, not a contract.
- Frozen. If something is wrong, fix the document and note the change. Treat these as living artifacts, not historical records.
