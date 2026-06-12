# Task 2 — Ingest the Glacier corpus: convert → stage → review → commit

What this task teaches: how real client data enters Substrate, with the
checked-in "Glacier Peak Credit Union" sample corpus
(`substrate/ingestion/samples/`) as the standard smoke test. The pipeline is
deliberately three steps with a human review gate in the middle — nothing
reaches the database until you run `commit`. Committed evidence: the
`INGEST_EVENT` shapes in `substrate/docs/manual/05-operator-workflows.md`
and the end-to-end record `substrate/docs/acceptance/diagnostic-glacier-2026-06-11.json`.

## Prerequisites

- No app needs to be running for convert/stage/commit — the ingestion tool is
  a standalone CLI (it starts no web server).
- It writes to the **file-based** dev database (`substrate/data/`). An app
  booted with `--spring.profiles.active=dev-shared` reads the same file; the
  plain `dev` profile (in-memory) will **not** see CLI commits.
- No API key needed: the mock provider exercises the full pipeline
  deterministically.

## Steps

1. Convert and stage in one step, with the deterministic mock provider:

   ```powershell
   cd substrate
   .\gradlew.bat :ingestion:bootRun --args="convert-and-stage --input samples --substrate.llm.provider=mock"
   ```

   Each stage prints one machine-readable summary line prefixed
   `INGEST_EVENT` (field order not guaranteed — match on names):

   ```text
   INGEST_EVENT {"event":"convert_complete","inputDir":"samples","outputDir":"output","filesProcessed":4,"filesAlreadyIngested":0,"filesUnsupported":0,"entitiesExtracted":37,"errors":0,"ingestRunId":"<uuid>"}
   INGEST_EVENT {"event":"stage_complete","outputDir":"output","stagingDir":"staging","filesStaged":4}
   ```

2. **Review — the human gate.** Open the staged JSON under
   `substrate/ingestion/staging/` in your editor and sanity-check what the
   extractor produced. Delete or correct anything wrong *now*; this is the
   whole reason staging exists. The 4 Glacier files stage 37 extracted
   entities — the three structured CSVs convert deterministically (no LLM
   involved); only the free-text workflow notes went through the (mock)
   extraction step.

3. Commit the reviewed staging directory into a named domain:

   ```powershell
   .\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"
   ```

   ```text
   INGEST_EVENT {"event":"commit_complete","stagingDir":"staging","filesCommitted":4,"filesAlreadyCommitted":0,"filesUnrecognized":0,"entitiesCommitted":42,"entitiesUpdated":0,"errors":0,"entityCounts":{"systems":6,"processes":7,"canonical-entities":5,"process-system-links":9,"process-roles":10,"organization-members":5},"ingestRunId":"<uuid>"}
   ```

   The domain is created if absent. Every source file was registered in
   `document_registry` with its SHA-256 content hash and the run wrote an
   audit row to `ingest_runs` — this is the foundation of finding provenance
   (task 3 walks it).

4. **Idempotency check** — run the same commit again:

   ```powershell
   .\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"
   ```

   Every staged file is recognized by its content hash and skipped:
   `filesCommitted: 0, filesAlreadyCommitted: 4`, identical row counts, exit
   code 0. Re-committing the same corpus is always safe.

## Exit codes — the contract for scripts

| Exit | Meaning |
|---|---|
| `0` | Success — including the fully-deduplicated re-run (a legitimate no-op) |
| `1` | Errors during processing (per-file errors on stderr) |
| `2` | Usage error — unknown command, missing input directory |
| `3` | **Zero-yield hard fail** — the run consumed nothing and nothing was legitimately already done; comes with an `INGEST_EVENT {"event":"zero_yield", …}` line. "The command exited 0" must never be mistaken for "the corpus was ingested." |

Mock vs real provider: structured CSVs never need a key. For real free-text
extraction set `$env:ANTHROPIC_API_KEY` and drop the
`--substrate.llm.provider=mock` override; output then varies run to run,
which is exactly why the explicit event lines and exit codes exist.

## You'll know it worked when…

…the first commit prints `commit_complete` with **`entitiesCommitted: 42`**
(6 systems, 7 processes, 5 canonical entities, 9 process–system links, 10
process roles, 5 organization members) and exit 0, and the re-run prints
`filesAlreadyCommitted: 4` with **`entitiesCommitted: 0`** and still exit 0 —
the metric that moved is committed entities 0 → 42, then deliberately 0 on
replay.
