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

## Conventions

Durable disciplines for the chain process — sub-milestone closeouts,
phase-boundary scans, sub-milestone-boundary internal-spec-consistency
scans, watch-for-pattern thresholds, code-inspection sentinel
observability, carry-forward routing, and others — live in
`docs/conventions/00-README.md`.

**Migrated 2026-05-07** at M5-continuous-pm-driver commit 9 close
per the discrete trigger that originally lived here ("when a fifth
durable discipline convention lands, lift to dedicated doc"). Pre-
migration count was 4; M5-continuous-pm-driver chain added 5+ more,
firing the trigger.

The conventions doc is the canonical home; sub-milestone closeouts
reference it; future-Claude reading the chain process cold finds the
conventions there, not scattered across kickoff/BACKLOG/grounding-doc
surfaces.

## What this package is *not*

- A user manual. End-user docs live in the top-level `README.md` (authored at M1, refined at M7).
- A spec for Phase 2. `12-phase-two-engineering-team.md` is a sketch, not a contract.
- Frozen. If something is wrong, fix the document and note the change. Treat these as living artifacts, not historical records.
