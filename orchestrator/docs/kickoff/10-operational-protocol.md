# Operational Protocol

This document describes how Mike works with Claude Code on the Orchestrator build itself — the meta-level. (Once the Orchestrator runs, Mike works with the *PM agent*, which is a different protocol described in `07-agent-configs-phase-one.md`.)

## Working directories

| Directory | Purpose |
|---|---|
| `C:\Users\mbgol\dev\meridian-ai\orchestrator\` | This build. Claude Code's working directory for Phase 1. |
| `C:\Users\mbgol\dev\meridian-homepoc\` | Substrate codebase. Engineer's dispatch target once M4 lands. **Not the same repo as this one.** |

These are separate repos in Phase 1. See `13-merge-plan.md` for the eventual merge.

## Pacing

- Mike paces the work.
- Per-milestone checkpoints. No skipping.
- If a milestone runs significantly long, surface that — Mike chooses between cutting scope, extending, or splitting.
- Don't generate work Mike didn't ask for.
- Don't invoke "you must be tired" or "let's stop" — that's Mike's call.

## Push back once, deliver if confirmed

When Mike asks for a chunk of work and you have a real concern:

1. State the concern in one or two sentences. Specific. Not hedged.
2. Wait for Mike's response.
3. If he confirms, deliver. Don't relitigate.

Pacing concerns are not technical concerns. Don't push back on pacing.

## Asking vs deciding

### Ask Mike when

- The spec is silent or ambiguous
- A library quirk forces a design change
- An obvious spec improvement emerges that wasn't in the kickoff
- An agent config feels wrong (especially during Implementer/Tester migration at M3)
- A milestone is running significantly long
- A piece of "deferred" Phase 2 scope shows up that wants to creep in

When asking: one question at a time. Don't dump a list and expect remembered answers.

### Decide and document when

- It's a low-stakes implementation choice
- Two valid approaches with small differences
- A standard convention applies cleanly

Document the choice in a code comment **only if** the *why* is non-obvious. If a future reader can derive the reason from the code, no comment.

## Git discipline

- All work on `main` for Phase 1. No feature branches yet.
- Conventional commits with milestone tag: `feat(M2): add MCP ping tool`, `test(M3): permission matrix cells`, `fix(M4): claim race`.
- Per-milestone commits. Multiple commits per milestone are fine. Giant end-of-milestone commits are not.
- Tests in the same commit as code.
- `ruff check`, `ruff format --check`, `mypy --strict`, `pytest` all green before each commit.

### Commits Claude Code creates

When Mike asks Claude Code to commit, follow `CLAUDE.md`-level git protocol:
- Never amend previously-committed work
- Never `--no-verify`
- Never force-push
- Use HEREDOCs for multi-line commit messages
- Co-authored-by trailer per the harness convention

### Commits the user creates

Mike may commit by hand outside of Claude Code's awareness. Don't second-guess. If `git status` shows unexpected state, ask Mike before doing anything destructive.

## Checkpoint cadence

Each milestone ends with a checkpoint message to Mike. Format:

```
## M{n} checkpoint

**What shipped**
- bullet
- bullet

**Decided autonomously**
- bullet (with one-line why)

**Open for Mike**
- bullet

**Next milestone**
- one line about M{n+1}
```

Don't pad. Don't celebrate. Mike reads the diff if he wants the WHAT — the checkpoint exists for the WHY and what needs his attention.

## Communicating with Mike during work

- One sentence per status update is almost always enough
- State results and decisions directly
- Don't narrate internal deliberation
- Don't apologize unless you've actually done something wrong

When something blocks: state the block and what you need from Mike. If you have a recommendation, state it and the tradeoff. Don't list options without a position.

## What this protocol does NOT cover

- How PM the agent talks to Mike. That's in PM's system prompt.
- How Engineer the agent talks to PM. That's drafted at M3.
- How Implementer/Tester sub-agents are dispatched. That's the bridge architecture, M3.

This document is about Claude-Code-the-builder talking to Mike-the-CEO during Phase 1 construction.

## When Phase 1 is done

The decision after Phase 1 acceptance is Mike's:
- Phase 2 (Lead Engineer + Backend / Frontend / Data Modeler / Integration / UAT)
- Pause to use Phase 1 in real work and revisit later
- Pivot — phase 1 reveals something different is needed

Don't push for any of those. State the options if asked, otherwise wait.
