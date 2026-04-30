# Engineer ↔ Subagent Bridge

**Status: STUB. Full architectural design and implementation plan land at M3 with Mike's review. This document captures the constraints the bridge must satisfy and the candidate architectures so the M3 work has a clean starting point.**

The hardest architectural question in Phase 1 is: how does the Engineer agent — running inside the Orchestrator daemon — dispatch Implementer and Tester sub-agents that operate in a *different* working directory (`C:\Users\mbgol\dev\meridian-homepoc\`) and have full read/write/exec access there?

Defer the answer. Document the question.

## Constraints (load-bearing)

A working bridge must satisfy all of:

1. **Different working directory.** Engineer's logical context is `meridian-ai/orchestrator/`; the dispatched sub-agent's working directory is `meridian-homepoc/`. This path is parameterized in config so the eventual merge (`13-merge-plan.md`) is a config edit, not a code change.

2. **Full filesystem capability for the sub-agent.** Read, write, exec in its working directory. The substrate-codebase work needs Edit, Write, Bash, Glob, Grep, Read at minimum. Tester additionally needs Playwright MCP tools (browser_navigate, snapshot, evaluate, console_messages, network_requests, wait_for, take_screenshot, close).

3. **Structured output Engineer can consume.** At minimum: commit hash (or null if no commit), test pass/fail with details, queue items raised, decline reason if declined, list of artifacts.

4. **No manual intervention once dispatched.** Engineer dispatches → bridge executes → Engineer polls or awaits → result returned. Mike does not click anything in the middle.

5. **Subscription-compatible by default.** Phase 1 runs on Mike's Claude Pro/Max subscription. The bridge must inherit that subscription for sub-agent execution. API credits are overflow, not the default path.

6. **Graceful rate-limit handling.** Log the event, surface to PM, surface to Mike via session report. Do not silently retry.

7. **Parallel concurrent dispatch — Phase 1, not Phase 2.** Per the utilization addendum, Engineer dispatches multiple sub-agents in *parallel* in Phase 1. Two phases touching different substrate files dispatch simultaneously; two phases touching the same files must serialize. The bridge provides:
   - Isolated working-directory checkouts per concurrent dispatch (per-dispatch git worktrees against per-dispatch branches is the leading model)
   - Merge logic that lands parallel branches back into the canonical working tree, including conflict detection and a documented protocol for surfacing conflicts to Engineer (which surfaces to PM)
   - A dispatch-graph data structure Engineer uses to plan parallel vs. serial execution
   
   This is the largest single piece of M3 design work and the largest piece of M4 implementation work. Original kickoff estimated parallel as Phase 2 deferral; addendum brings it forward to Phase 1.

8. **Audit chain preservation.** Every dispatch produces audit log entries that name the parent task and the dispatch_id, so Mike can reconstruct who-did-what-on-whose-behalf via `get_audit_log`.

9. **Decline propagation.** A sub-agent's decline must surface to Engineer with reason intact, and Engineer must surface it to PM. The bridge cannot silently swallow declines.

10. **Continuous-mode PM driving (added by utilization addendum).** The bridge architecture also governs how the daemon launches and supervises the long-running PM Claude Code subprocess that runs PM-continuous when Mike is not interactive. This is the same mechanism family as sub-agent dispatch — a Claude Code subprocess driven by the daemon — but with different lifecycle semantics:
   - Long-lived (until Mike opens Claude Desktop or daemon shuts down), not per-task
   - Reads `pm_mode_log` on each planning iteration to detect Mike's presence and pause dispatch when interactive
   - Reads the task queue and `get_window_state` continuously to drive its own dispatch loop
   - Coordinates with the interactive PM (Mike's Claude Desktop conversation, when active) via the database — both PM instances share the same system prompt and MCP access; they don't communicate directly
   
   The M3 decision must address both sub-agent dispatch *and* continuous-mode PM driving. They likely share infrastructure (subprocess launching, working-directory provisioning, structured-output capture) but have different lifecycle managers.

## Candidate architectures

These are evaluated at M3, not now.

### Option A: Subprocess Claude Code

Engineer launches a Claude Code subprocess pointed at the substrate working directory. Spec passed via stdin or a temp file. The subprocess inherits Claude Code's auth (Mike's subscription). Stdout/stderr captured; completion signaled by a structured marker the sub-agent emits.

- **Pros:** Subscription-inherited. Sub-agent has full Claude Code tooling (Edit, Write, Bash, Glob, Grep, Read) for free. Familiar to Mike (this is how subagents work in `meridian-homepoc` today).
- **Cons:** Process lifecycle management. Parsing structured output from free-form stdout is fragile. Concurrent dispatches mean concurrent subprocesses — manageable but real.
- **Phase-2 fitness:** Concurrent subprocesses are fine; the model is straightforward.
- **Strongly preferred path** as of kickoff, on the strength of subscription compatibility (Phase 1 runs on Mike's Claude Pro/Max subscription per `02-architecture.md`). Option A is also the most natural fit for the addendum's parallel-dispatch and continuous-mode-PM requirements: subprocess Claude Code instances can run in parallel (one Implementer, one Tester, one continuous-mode PM, all simultaneously), each with its own working directory and inherited subscription. Other options remain in scope for M3 evaluation but require explicit justification — specifically, why the cost-model penalty (Option D burns API credits), the implementation cost (Options B and C), or the parallel-dispatch fit (Options C and D) outweigh what Option A delivers.

### Option B: MCP-to-MCP

Engineer exposes Implementer/Tester as nested MCP services, with some kind of relay between Orchestrator MCP and Claude Code's existing subagent invocation.

- **Pros:** Wire shape uniformly MCP. Cleanly typed I/O.
- **Cons:** Significant glue code. Claude Code's subagent invocation isn't designed to be driven by an external MCP client. Doesn't obviously inherit the subscription cleanly.
- **Phase-2 fitness:** Clean for parallel dispatch in principle.

### Option C: File-based handoff

Engineer writes a spec file to a designated location. A polling Claude Code instance (or file-watcher-driven invocation) picks it up, runs, writes a response file. Engineer polls for the response.

- **Pros:** Simple. Decoupled. Crash-recovery friendly (specs and responses are durable on disk).
- **Cons:** Latency. Polling overhead. Requires a Claude Code instance running specifically for this purpose.
- **Phase-2 fitness:** Concurrent dispatch via per-spec-file sharding is clean.

### Option D: Direct Anthropic API calls

Engineer holds Implementer/Tester system prompts and calls the Anthropic API directly with appropriate tools, bypassing Claude Code entirely.

- **Pros:** Maximum control. Clean async semantics. No subprocess management.
- **Cons:** **Doesn't use Mike's subscription.** Burns API credits. Re-implements all the file-system tooling Claude Code provides for free. Tester's Playwright tools especially nontrivial to reproduce.
- **Phase-2 fitness:** Clean, but the cost model alone disqualifies as the default Phase 1 path.

## What M3 produces

This document, fully fleshed out, with:

- Chosen architecture and rationale, addressing **both** sub-agent dispatch and continuous-mode PM driving
- Sequence diagram of a normal dispatch (PM submits → Engineer claims → Engineer dispatches → sub-agent runs → result returned → Engineer surfaces to PM)
- Sequence diagram of a parallel dispatch (Engineer dispatches two Implementers concurrently against two non-conflicting working trees, both complete, branches merge)
- Sequence diagram of a decline propagation
- Sequence diagram of continuous-mode PM driving (daemon launches PM subprocess → PM reads `pm_mode_log` and queue → PM dispatches → Mike opens Claude Desktop → daemon flips `pm_mode_log` → PM detects and pauses → Mike's session ends → PM resumes)
- Storage shape (a `dispatches` table or extension of `tasks`?)
- Working-directory model (per-dispatch worktrees, branch naming, merge protocol, conflict surfacing)
- Implementation plan for M4 (concrete files, concrete tests)
- Mike's explicit approval before M4 implementation begins

## What M3 does NOT produce

- A working implementation. M4 builds it.
- A decision about Phase 2 parallel-dispatch implementation. The design must accommodate it; the implementation can come later.
- A revision to the constraints list above. If the constraints are wrong, surface to Mike and amend this stub before choosing.
