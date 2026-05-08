# Merge Plan — Eventual `meridian-ai/` Monorepo

**Status: forward-looking. The merge happens post-demo. This document captures the plan so Phase 1 design choices don't paint the merge into a corner.**

## Target structure

```
C:\Users\mbgol\dev\meridian-ai\
├── orchestrator/                  # this Phase 1 build, current path
│   └── (full project tree)
└── substrate/                     # rename of meridian-homepoc, current path
    └── (full project tree)
```

Both as siblings. Independent build/test/deploy chains. Shared knowledge base under `~/.meridian-orchestrator/knowledge/` continues to be the canonical reference for both.

## What "merge" means in this context

It is **not** a code merge. The two codebases stay independent. They are siblings under a common root for ergonomic reasons:

- One `cd` to either side
- Cross-references in docs use `../substrate/...` or `../orchestrator/...` paths
- Knowledge sync from substrate to orchestrator (M6's `scripts/sync-substrate-knowledge.py`) becomes a relative path operation
- A future top-level `meridian-ai/` README points to both

Possibly a top-level `pyproject.toml` workspace later if there's shared Python tooling worth deduplicating, but that's a separate decision.

## Pre-merge state (Phase 1)

| Path | Contents |
|---|---|
| `C:\Users\mbgol\dev\meridian-homepoc\` | Substrate codebase. Stays. |
| `C:\Users\mbgol\dev\meridian-ai\orchestrator\` | This build. Stays. |

Engineer's working directory for sub-agent dispatch is parameterized in `config/orchestrator.yaml`:

```yaml
substrate_working_directory: C:\Users\mbgol\dev\meridian-homepoc
```

## Post-merge state

```yaml
substrate_working_directory: C:\Users\mbgol\dev\meridian-ai\substrate
```

That single config edit is the entirety of the path change required by the merge.

## The actual merge steps (when Mike chooses to do it)

1. `mkdir C:\Users\mbgol\dev\meridian-ai\` (already exists for `orchestrator/`)
2. `git mv C:\Users\mbgol\dev\meridian-homepoc → C:\Users\mbgol\dev\meridian-ai\substrate` (in shell terms)
   - Practically this is `mv` of the working directory; the `.git` metadata moves with it. The Git remote stays the same; only the local clone path changes.
3. Update `~/.meridian-orchestrator/config/orchestrator.yaml`'s `substrate_working_directory` to the new path.
4. Update any IDE workspace files, shell aliases, or shortcuts pointing at the old path.
5. Trigger the file watcher (or restart the daemon) to pick up the new config.
6. Smoke test: PM submits a task, Engineer dispatches Implementer at the new path, observe a real commit, audit log captures the new path.

## What must NOT happen at merge

- **Do not rewrite Git history.** Substrate's commit history is real artifact of the build and stays intact across the rename.
- **Do not introduce a top-level Git repo over the two siblings.** They remain separate Git repos. A future shared workspace tool can sit alongside without overlay.
- **Do not move the data directory** (`~/.meridian-orchestrator/`). It's user-scoped, not project-scoped. The merge is filesystem rearrangement, not a state migration.

## When the merge happens

After Phase 1 acceptance (M7) and after the substrate demo is shareable. Whichever comes second. Mike's call. The merge is a 15-minute operation; there's no reason to do it earlier and a small reason to wait (it's annoying to update IDE/shell config).

## What Phase 1 must do to make this trivial

Already covered, but restated:

1. **Parameterize `substrate_working_directory`.** Done in `02-architecture.md` and `05-data-model.md` (the `projects.working_directory` column).
2. **Don't hardcode `meridian-homepoc` anywhere in code.** Always read from config. Test for this — a grep for `meridian-homepoc` in `src/` should return zero hits outside of comments and tests.
3. **Don't hardcode `meridian-homepoc` in agent system prompts.** PM, Engineer, Implementer, Tester prompts reference "the substrate codebase" or read the working directory from context, not the literal path.
4. **Don't put orchestrator state inside `meridian-homepoc/`** (or vice versa). The two stay independent. Knowledge sync flows substrate → orchestrator's knowledge base, not the other way.

## Open questions deliberately left for the merge moment

- Whether to introduce a shared `pyproject.toml` workspace (probably not, but might be valuable if dev tooling overlaps)
- Whether to publish the orchestrator as a pip package eventually
- Whether the substrate codebase itself eventually consumes Orchestrator MCP (substrate-staffed-by-orchestrator is a thesis-coherent direction, but not a Phase 1 question)

These are not merge blockers. They are post-merge product questions.
