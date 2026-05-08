# Context

## Who Mike is

Michael Brian Goldberg. Director-level technology engineering manager with ~20 years in financial services. At Wells Fargo since 2018; prior roles at Citigroup and Morgan Stanley. He builds in evenings and weekends. He is the CEO of Meridian AI, LLC and intends to incorporate the company in North Carolina.

He prefers direct, honest pushback over flattery. He is negative-leaning and skeptical. He sets the pace. He does not want to be protected from his own ambition. He will tell you when he's done; you will not tell him.

When you interview him, ask one question at a time. When he asks for work, push back once on real concerns and then deliver. Don't dump options-lists at him without taking a position.

## What Meridian AI is

Meridian AI, LLC is the company. Meridian is the substrate product. The thesis is **substrate-as-governance**: a coordination layer that makes the AI-leveraged portfolio legible to leaders. Three primitives are load-bearing:

1. **Durable agent identity** — provenance, oversight chain, who-acted-when.
2. **Cascading evaluation** — L0 (a leader) issues a critique; the critique propagates downward an arbitrary depth; **Decline** is a first-class upward action a downstream agent can return.
3. **MCP-enforced coordination contracts** — the substrate, not the agents, enforces the rules.

Target buyer is Fortune 2000 portfolio operators (NOT G-SIBs), PE operating partners, and transformation leaders at mid-cap PE-backed holding companies. The current demo is **Beacon Industries** — a fictional $5B portfolio operator with 22 invented subsidiaries.

The demo is real software. Phase B (real cascading evaluation engine, real MCP enforcement layer, real coordination contracts beyond fixtures) is post-demo work. Current focus: ship demo-shareable, then commercialize.

## The two codebases

Phase 1 lives across two parallel codebases that will eventually merge.

**`C:\Users\mbgol\dev\meridian-homepoc\`** — the substrate product itself. Stage 6 just closed. Three substrate-as-governance UI surfaces shipped: agent provenance, cascading evaluation, coordination contracts. ~25 commits across the build to date. A working agent loop with Implementer + Verifier subagents in Claude Code is operational. Demo-shareable closeout work remains: Stage 5 narrative, Stage 7 polish, cascade authoring workflow design.

**`C:\Users\mbgol\dev\meridian-ai\orchestrator\`** — *this* build. The company infrastructure that runs Meridian AI as an AI-staffed company. Empty at kickoff. Phase 1 makes it operational.

These merge post-demo into a `meridian-ai/` monorepo with `orchestrator/` and `substrate/` (renamed from `meridian-homepoc`) as siblings. See `13-merge-plan.md`. For Phase 1, the substrate codebase stays at its current path. Engineer's working-directory path is parameterized in config so the eventual rename is one config edit.

## Why an Orchestrator

Mike is the CEO. His direct reports are AI agents. The Orchestrator is the infrastructure those agents run on: queue, audit log, permission framework, hierarchy enforcement, knowledge base, budget tracking. It exists so Mike can talk to the PM agent and have product work coordinated, dispatched, and verified without him micromanaging the chain.

Phase 1 has four agents: PM, Engineer, Implementer, Tester. Phase 2 expands the engineering side. Other roles (Lawyer, Accountant, Designer, Advisor) are deferred. See `03-phase-one-scope.md`.

## What was decided in the conversation that produced this kickoff

The kickoff message itself — the long brief that started this build — was authored on April 27–28, 2026 by Mike and a Claude conversation that had ~25 commits of substrate context loaded. That context isn't carried forward in code. The kickoff message and these documents *are* the carry-forward. When something here contradicts something Mike says now, trust Mike — he has the live context.

## Anchor decisions (so you don't relitigate them)

- Local-only daemon for v1. No remote, no Tailscale, no cloud. Cloud-portable architecture, but no cloud work in Phase 1.
- Subscription-first cost model. Phase 1 runs on Mike's Claude Pro/Max subscription. API credits are overflow.
- MCP-as-service. Claude Code is the human interface (interactive PM session via OAuth loopback). No custom chat UI.
- Strict hierarchy. PM ↔ Engineer ↔ {Implementer, Tester}. No bypass.
- PM has downgrade authority. The destructive-chip semantic question (worked example in `07`) is the canonical illustration.
