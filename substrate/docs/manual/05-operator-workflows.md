## Operator workflows (CLI)

This section is for the Meridian engagement team — the people with a terminal open on the machine that runs Substrate. It covers the four hands-on procedures that are driven from the command line rather than the browser: ingesting a client corpus, running a diagnostic, standing up a demo environment, and verifying the decision log. Client-side users never need any of this; everything here happens before or beneath what they see in the UI.

All commands run from the `substrate/` directory of the deployment checkout. On Windows use `.\gradlew.bat`; on macOS/Linux use `./gradlew`. The examples use the local development shape (no Docker, file-based database); the same commands work against a shared Postgres stack by adding `--spring.profiles.active=postgres` and the standard `SUBSTRATE_DB_*` environment variables. REST examples use `http://localhost:8080`, the default API port — substitute your deployment's port if it differs.

---

### Ingesting a client corpus: convert → stage → commit

Ingestion is how real client data enters Substrate. It is a deliberate three-step pipeline with a human review gate in the middle:

1. **convert** — read raw source files (CSV, JSON, Markdown/text) and extract canonical entity JSON from them.
2. **stage** — copy the converted output into a staging directory for a human to review.
3. **commit** — write the reviewed, staged entities into the engagement database as systems, processes, canonical entities, organization members, and their relationships, with full provenance.

Nothing reaches the database until you run `commit`. The staging step exists so an operator can open the JSON files, sanity-check what the extractor produced, and delete or correct anything wrong before it becomes engagement data.

The ingestion tool is a standalone command-line application (it starts no web server). It shares the same database migrations as the main application, and in the local dev shape it writes to the file-based database at `substrate/data/engagement-dev.mv.db`. An API booted with `--spring.profiles.active=dev-shared` reads the same file, so entities you commit are immediately visible to it. (The plain `dev` profile uses an in-memory database and will *not* see CLI commits.)

#### Worked example: the Glacier sample corpus

A fictional sample corpus — "Glacier Peak Credit Union" — is checked in at `ingestion/samples/`: `glacier-systems.csv`, `glacier-manual-processes.csv`, `glacier-entities.csv`, and `glacier-workflow-notes.md`. It is the standard smoke test for the pipeline and the worked example throughout this section.

```powershell
cd substrate

# 1. Convert + stage. With no API key, use the deterministic mock provider.
.\gradlew.bat :ingestion:bootRun --args="convert-and-stage --input samples --substrate.llm.provider=mock"

# 2. Review the staged JSON in ingestion/staging/, then commit.
.\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"

# 3. Idempotency check: re-run commit. Every staged file is recognized by its
#    content hash and skipped — 0 created, 0 updated, exit code 0.
.\gradlew.bat :ingestion:bootRun --args="commit --domain Glacier"
```

Each stage prints a single machine-readable summary line prefixed `INGEST_EVENT` (greppable from wrappers and CI), in addition to the normal logs. Expected shapes for the Glacier corpus:

```
INGEST_EVENT {"event":"convert_complete","inputDir":"samples","outputDir":"output","filesProcessed":4,"filesAlreadyIngested":0,"filesUnsupported":0,"entitiesExtracted":37,"errors":0,"ingestRunId":"<uuid>"}
INGEST_EVENT {"event":"stage_complete","outputDir":"output","stagingDir":"staging","filesStaged":4}
INGEST_EVENT {"event":"commit_complete","stagingDir":"staging","filesCommitted":4,"filesAlreadyCommitted":0,"filesUnrecognized":0,"entitiesCommitted":42,"entitiesUpdated":0,"errors":0,"entityCounts":{"systems":6,"processes":7,"canonical-entities":5,"process-system-links":9,"process-roles":10,"organization-members":5},"ingestRunId":"<uuid>"}
```

(Field order within the JSON is not guaranteed; match on field names.)

The re-run of `commit` reports `filesCommitted: 0, filesAlreadyCommitted: 4` and identical row counts in the database — re-committing the same corpus is always safe.

#### Commands and flags

| Command | What it does |
|---|---|
| `convert` | Convert source files to entity JSON in the output directory |
| `stage` | Copy converted JSON into the staging directory for review |
| `commit` | Write staged entities into the database (create or update) |
| `convert-and-stage` | Convert and stage in one step |

| Flag | Meaning |
|---|---|
| `--input <dir>` | Source directory for convert; staging directory for commit |
| `--output <dir>` | Where converted entity JSON is written (default `output/` under the ingestion module) |
| `--staging <dir>` | Staging directory (default `staging/` under the ingestion module) |
| `--domain <name>` | Target domain for commit. Created if absent; defaults to a domain named `Ingested` |

Relative paths resolve under `substrate/ingestion/`. Dotted flags such as `--substrate.llm.provider=mock` are configuration overrides, not CLI flags — they pass through to the application.

#### Mock vs. real LLM provider

Structured CSVs (systems, manual processes, entities) convert **deterministically** — no LLM is involved for them, with or without a key. Free-text sources (the workflow-notes Markdown) go through an LLM extraction step:

- **Mock provider** (`--substrate.llm.provider=mock`): a deterministic stand-in that echoes rows through the pipeline. It exercises the full mechanics — file registration, hashing, staging, commit, audit — without a network call. Use it for smoke tests and when no API key is configured.
- **Real provider** (the default): set the key first — `$env:ANTHROPIC_API_KEY = '...'` — and drop the provider override: `convert-and-stage --input samples`. Extraction quality on free text is real but output can vary run to run.

#### Exit codes

The process exit code is the contract for scripts and CI:

| Exit code | Meaning |
|---|---|
| `0` | Success — including a fully-deduplicated re-run (everything already ingested is a legitimate no-op) |
| `1` | Errors during processing (per-file errors are printed to stderr) |
| `2` | Usage error — unknown command, missing input directory |
| `3` | **Zero-yield hard fail** — the run consumed nothing: nothing converted, staged, or committed, and nothing legitimately recognized as already done. Typically an empty or wrong directory. Accompanied by an `INGEST_EVENT {"event":"zero_yield", ..., "reason":"..."}` line |

The zero-yield code exists because "the command exited 0" must never be mistaken for "the corpus was ingested." If you point the tool at the wrong directory, it fails loudly instead of succeeding emptily.

#### Where the audit trail lands

Every convert and commit writes an audit row to the `ingest_runs` table; the run id is echoed as `ingestRunId` in the `INGEST_EVENT` line. Source and staged files are registered in `document_registry` with their SHA-256 content hash and stamped with the converter run that processed them. This is not bookkeeping for its own sake: it is the foundation of finding provenance. When a diagnostic later commits an AI finding against ingested data, `GET /api/v1/findings/{id}/provenance` walks from the finding through its evidence back to these registry rows and ingest runs — down to the original source filename and content hash.

To reset the local dev database for a clean slate, stop both processes and delete `substrate/data/` (and `ingestion/output/` plus `ingestion/staging/` to also clear pipeline intermediates).

---

### Diagnostic runs from the command line

A diagnostic run asks the AI to examine a domain's ingested landscape — systems, processes, canonical entities — and propose findings (redundancies, gaps, data-quality issues). Every candidate the model produces passes through a filter that checks its type, severity, required fields, and — for content findings — that every piece of evidence it cites actually exists in the domain. Only survivors are committed, as findings awaiting human review. Runs execute in batches and are resumable; an interrupted run never produces duplicate findings.

#### The CLI

The runner boots the application with no web server, executes exactly one synchronous run against a named domain, prints structured event lines, and exits:

```powershell
cd substrate
.\gradlew.bat :app:bootRun --args="--spring.profiles.active=dev-shared --substrate.diagnostic.run=true --substrate.diagnostic.domain-name=Glacier --substrate.diagnostic.required-min-findings=1 --substrate.llm.provider=mock --substrate.llm.mock.diagnostic-script=grounded-content"
```

- `--spring.profiles.active=dev-shared` points the runner at the same file-based database the ingestion CLI commits to, so it can see the Glacier domain.
- `--substrate.diagnostic.domain-name` selects the domain **by name**. If the name is unknown, the runner tells you to list known domains with `GET /api/v1/domains` and exits 4.
- `--substrate.diagnostic.required-min-findings=1` declares your yield requirement: the run must commit at least this many findings or it is a hard failure (exit 3). Set it whenever you expect output; leaving it 0 means "an empty result is acceptable," and the runner will then exit 0 on zero findings — but always with an explicit yield line, never silently.

**Provider note.** The bare mock provider answers diagnostics with prose, which parses to zero candidates — the run fails with exit 2 (`NO_CANDIDATES`). For a meaningful deterministic smoke test add a mock script: `--substrate.llm.mock.diagnostic-script=grounded-content` (commits real evidence-grounded findings, as in the example above), `structural` (commits one structural finding), or `ungrounded-content` (exercises the all-filtered rejection lane). For a real run, set `ANTHROPIC_API_KEY` and drop both `substrate.llm.*` overrides; real yield varies run to run, which is exactly why `required-min-findings` exists.

#### Exit codes

| Exit code | Meaning |
|---|---|
| `0` | Run completed and the yield requirement was met. Includes the legitimate "clean domain" case — zero findings with `required-min-findings=0` — which always prints an explicit `DIAG_EVENT kind=yield` line explaining the zero. A `COMPLETED_WITH_ERRORS` run (some batches exhausted their retries but the yield requirement was still met) also exits 0 — check the run detail for the per-batch ledger |
| `2` | The run failed or did not run to completion: no parseable candidates at all (`NO_CANDIDATES`), all batches failed, the run was cancelled mid-flight, or another run was already active for the domain |
| `3` | **Zero-yield hard fail**: candidates were produced but fewer were committed than `required-min-findings` — either everything was filtered out or the count fell below the requirement |
| `4` | Misconfiguration: the runner was enabled without a domain name, or the named domain does not exist |

#### Reading DIAG_EVENT lines

The engine emits one greppable `DIAG_EVENT kind=…` line per significant step. A healthy run looks like:

```
DIAG_EVENT kind=run_started runId=… domain=… scopes=systems,processes,canonical-entities batches=4 executor=in-process
DIAG_EVENT kind=batch_completed runId=… batch=1/4 returned=3 filtered=2 committed=1
...
DIAG_EVENT kind=run_finalized runId=… status=COMPLETED committed=4 filtered=8 superseded=0 tokensIn=320 tokensOut=160 costEstimate=0.0168
DIAG_EVENT kind=cli_exit runId=… status=COMPLETED committed=4 exit=0
```

| `kind` | Meaning |
|---|---|
| `run_started` | Run accepted; shows scope list, batch count, executor |
| `batch_completed` | One batch done: candidates `returned`, `filtered` (rejected by the filter), `committed` |
| `batch_failed` | A batch exhausted its retries; the run continues with the rest |
| `batch_dispatched` / `batch_fallback` / `batch_skipped_budget` | Orchestrator-dispatched execution lane only: a batch was sent to an external agent, fell back to in-process execution, or was skipped on budget refusal (neutral, not an error) |
| `run_finalized` | Terminal status plus totals: committed, filtered, superseded prior findings, token and cost totals |
| `run_cancelled` | Operator cancel completed (neutral — cancel is never an error) |
| `yield` | Explicit yield statement — including the mandatory explanation when the legitimate result is zero |
| `zero_yield` | The hard-fail case: below the declared requirement (exit 3) |
| `all_content_filtered` / `no_content_findings` | Warnings: every evidence-citing candidate was filtered, or the run yielded only structural observations. Worth a human look; not fatal by themselves |
| `misconfiguration` / `run_conflict` / `cli_exit` | CLI-level lines: bad configuration (exit 4), another run already active (exit 2), and the final exit summary |
| `batch_*_refused_stale_claim` | Internal consistency guards — a stale executor tried to report on a batch that had been reclaimed. Safe to ignore unless they recur |

#### Run statuses

`GET /api/v1/diagnostics/runs/{id}` reports one of:

| Status | Meaning |
|---|---|
| `PLANNED` | Run and batch plan persisted, execution not started |
| `RUNNING` | At least one batch executing |
| `COMPLETED` | Every batch finished and the yield requirement was met |
| `COMPLETED_WITH_ERRORS` | Mixed: some batches committed findings, at least one batch failed after retries |
| `ZERO_YIELD` | All batches ran but committed findings fell below the requirement — a loud product failure, not a server error |
| `FAILED` | No parseable candidates anywhere, or every batch failed |
| `CANCELLED` | Operator cancel — always neutral |

#### REST equivalents

Everything the CLI does is also available over HTTP, plus resume/cancel and the integrity sweep:

| Endpoint | What it does |
|---|---|
| `POST /api/v1/diagnostics/runs` | Start a run. Body: `{"domainId": "…", "requiredMinFindings": 1, "idempotencyKey": "…"}` (scopes, batch size, executor optional; `"dryRun": true` returns the batch plan without running). Returns 202 with the run id. A second start while one is active returns 409 `DIAGNOSTIC_RUN_ACTIVE` — one run per domain at a time |
| `GET /api/v1/diagnostics/runs?domainId=…` | Run history, newest first |
| `GET /api/v1/diagnostics/runs/{id}` | Full detail: status, per-batch ledger, filter histogram (why candidates were rejected), token and cost totals |
| `POST /api/v1/diagnostics/runs/{id}/resume` | Re-execute pending, failed, or stale batches after a crash or partial failure. No duplicate findings are possible — a batch that did not finish committed nothing. Resuming a finished run is a harmless no-op |
| `POST /api/v1/diagnostics/runs/{id}/cancel` | Stop a run: queued batches are cancelled, the in-flight batch finishes its current step. Returns 200; cancel is never error-colored |
| `GET /api/v1/diagnostics/verify?domainId=…` | Integrity sweep of every AI finding in the domain: total, how many have a complete evidence chain, any broken chains with the missing link named, and any findings grounded only in demo fixture data |

A clean sweep on a domain with 12 AI findings looks like: `{"total":12,"chainComplete":12,"brokenChains":[],"fixtureGrounded":[],"groundingModes":{"EXISTENCE_ONLY":12}}`. All responses use the standard `{data, meta, errors}` envelope.

One honesty note that also appears in the review UI: `EXISTENCE_ONLY` means evidence citations are verified to **exist** — that the cited documents and rows are real and traceable to source files. Whether the evidence semantically supports the claim is what the human reviewer certifies when accepting a finding.

---

### The demo pack: seeding a demonstration environment

The demo pack populates a fresh database with a complete fictional engagement — "Meridian Bancorp" operational risk, plus the "Beacon" holding-company portfolio — so every surface has something credible to show. It is strictly demo-lane: seeding is off by default, only ever writes into domains marked as demo, and none of its levers belong in a customer engagement build. Production data enters only through ingestion and the engines.

Boot with seeding enabled:

```powershell
cd substrate
.\gradlew.bat :app:bootRun --args="--spring.profiles.active=dev --substrate.demo.seed=true --substrate.demo.contracts.activate=true"
```

#### What gets seeded

Seeders run in dependency order; the boot log prints one `[seed] <Name> completed in N ms` line per seeder plus row counts, so you can confirm what landed without writing SQL.

| Seeder | Creates |
|---|---|
| Domain | The primary demo domain ("Operational Risk" — Meridian Bancorp), marked as demo |
| Systems | The 12-system landscape plus system-to-system integrations |
| Processes | 26 manual processes, process–system links, role assignments, a sample process step |
| Organization | Organization members and role-overlap relationships |
| Canonical entities | Shared data entities and their overlap relationships |
| Capabilities | A sample capability tree (customer-onboarding root with a KYC child) and links |
| Diagnostic | Fixture findings, plus design decisions appended through the real hash-chained decision log |
| Evidence | Synthetic document-registry and evidence rows backing the seeded entities (marked `extractor='seed'` so they are always distinguishable from real ingested provenance) |
| Readers | One sample external-source registration ("Ingestion ETL") |
| Phases | Lifecycle phase states for the domain; Design is set in-progress at seed time |
| Cascades | Three demonstration decision cascades, driven through the real cascade lifecycle verbs (draft → issue → cascade → … → decline) — the seed doubles as a state-machine test |
| Contracts | The two fixture coordination contracts, created `PROPOSED` through the real contract service |
| Agents | ~70 fixture agents replayed through the real lifecycle (deploy → review → promote / pause / intervention), plus the 22 Beacon subsidiary domains that populate the portfolio view |
| Branding | One active branding configuration per demo domain |

#### Re-boot idempotency

Re-booting with `substrate.demo.seed=true` against an existing database is safe:

- **Fresh database** (no domains at all): full bulk seed.
- **Existing database**: only domains marked demo are revisited, with per-entity "create if missing" logic — existing data is not duplicated, and new fixtures added since the last boot are picked up without a wipe. The Beacon subsidiary domains are excluded from this iteration (their fleet re-converges separately), so fixtures are never multiplied across 22 satellites.
- **Database containing only non-demo (production) domains**: the seed run is a complete no-op. The demo pack never touches production domains.

#### The demo levers

| Property | Default | What it demonstrates |
|---|---|---|
| `substrate.demo.seed` | `false` | Master switch. All other levers are inert without it |
| `substrate.demo.contracts.activate` | `false` | Records fixture acceptances for every required party of the "co-tribal-knowledge-depopulation" contract, flipping it `ACTIVE`. Without it both fixture contracts sit `PROPOSED` forever; with it, contract monitoring, breach detection, and the compliance demo sequence have a live contract to work against |
| `substrate.demo.contracts.guards` | `false` | Seeds and activates a deliberately **synthetic** guard-bearing contract (`demo-gateway-guards`) with deny guards on agent promotion and metric reporting, bound to the demo agent principal. The two realistic fixture contracts intentionally compile to zero enforcement guards, so gateway-blocking demos need this synthetic one |
| `substrate.demo.fixed-clock` | unset | Pins the engine clock to an ISO-8601 instant, e.g. `--substrate.demo.fixed-clock=2026-09-10T15:12:23Z`. Two uses: making the contract breach-detection sequence reproducible byte-for-byte (pin "now" past a reporting period's grace window), and demonstrating the portfolio lifecycle-stall signal (pin ~91 days past seed time and the dashboard's stall count flips 0→1, since the seeded domain has then dwelt in Design past the 90-day threshold). The boot log prints a loud `DEMO LEVER ACTIVE` warning when set |
| `substrate.transformation.fixture-enabled` | `false` | Serves the fictional transformation-plan mock-up to the (otherwise disabled) Roadmap surface. Demo only |

None of these belong in a customer build; the clock lever in particular makes every time-based computation in the product wrong on purpose.

#### Caveat: failed seeds on a persistent database

The seed run executes in one transaction, with one exception: decision-log appends commit **immediately and independently** (that is what makes the chain tamper-evident, and it holds during seeding too). If a seed run fails *after* the diagnostic seeder has written its design decisions — rare, but possible on a persistent Postgres database — the domain and its entities roll back while the already-committed decision rows survive, referencing a domain that never came to exist. The next boot sees no domains, seeds a fresh demo domain, and the orphan chain rows linger. They are self-consistent (the startup chain verifier reads them as intact) and purely cosmetic, but if they bother you:

```sql
DELETE FROM design_decisions WHERE domain_id NOT IN (SELECT id FROM domains);
```

In-memory or freshly-deleted dev databases never exhibit this; it only accumulates where the database outlives the failed boot.

---

### Reading and verifying the decision log

The decision log is the engagement's tamper-evident record. Each entry stores a SHA-256 hash of its own canonical content plus the hash of the previous entry in its domain's chain; the first entry's previous-hash is the literal string `GENESIS`. Any after-the-fact modification of an entry breaks every subsequent link, and the break is detectable on demand and at every application boot (boot-time re-verification of every domain chain is on by default).

#### Endpoints

| Endpoint | Returns |
|---|---|
| `GET /api/v1/decision-log?domainId=…` | The full chain for a domain, in chronological order |
| `GET /api/v1/decision-log/verify?domainId=…` | A chain-integrity verification report |

Each entry in the list carries: `id`, `decisionType`, `status` (`PROPOSED` / `APPROVED` / `REJECTED`), `title`, `description`, `rationale`, `affectedSystemIds`, `affectedProcessIds`, `previousHash`, and `currentHash`. Reading down the list, every `previousHash` should equal the prior entry's `currentHash`, starting from `GENESIS`.

An intact verification report (captured live against a seeded instance):

```json
{
  "data": {
    "valid": true,
    "totalDecisions": 6,
    "verifiedLinks": 6,
    "firstBrokenAt": null,
    "message": "All 6 decisions verified; chain is intact."
  }
}
```

A broken chain reports `valid: false`, `verifiedLinks` equal to the number of links that verified **before** the first break, `firstBrokenAt` naming the first decision whose link failed, and a message describing the failure. A break means an entry's stored content no longer matches its hash, or its previous-hash no longer matches its predecessor — i.e., something was modified outside the append path. Treat it as an incident: the log itself cannot tell you what changed, only where the chain stops being trustworthy.

#### What is anchored in the chain

Three classes of acts write chain entries:

| Anchor | Written when | Appears as |
|---|---|---|
| Design decisions | An operator records a decision (`POST /api/v1/design/decisions`), and the demo seed's fixture decisions | The decision's own type: `CONSOLIDATE`, `RETIRE`, `MODERNIZE`, the manual-process verbs, etc. |
| Contract accountability | A coordination contract is **activated**, **declined** (either decline path), or a breach is **waived** | `CONTRACT_ACCOUNTABILITY` |
| Agent governance | An agent is **deployed** (the anchor carries a digest of its mandate criteria), **promoted** to live, or **retired/killed** (carries the retirement reason) | `AGENT_DEPLOYED` / `AGENT_PROMOTED` / `AGENT_RETIRED` |

The contract and agent anchors are written immediately after the underlying act commits; the chain carries stable identifiers (contract keys, row UUIDs, period keys, actor ids), never display names or free prose — rationale text lives on the underlying event rows, referenced by id.

Deliberately **not** anchored: agent pause/oversight changes (recorded in the agents' own append-only event log — too frequent for the chain), and AI finding accept/reject decisions (recorded in the curation audit table; an optional per-run digest anchor exists but is off by default).

---

### Where acceptance evidence lives

Every workflow above was proven with runnable commands, and the evidence is checked in under `substrate/docs/acceptance/`. These files are the best worked examples available — real captured outputs, not idealized ones — and a good way to check "what should this look like when it works":

| Path | What it shows |
|---|---|
| `acceptance/diagnostic-glacier-2026-06-11.json` | The complete Glacier worked example end to end: ingest event lines, two diagnostic runs with full run-detail JSON (batch ledgers, filter histograms, supersession across runs), curation, a clean verify sweep — and the negative proof that an empty domain with a yield requirement exits 3 |
| `acceptance/d5/` | Portfolio statistics acceptance: the baseline portfolio JSON, the fixed-clock boot that flips the stall count 0→1, a dashboard screenshot, and the write-up of the exact commands |
| `acceptance/phase3/` and `acceptance/d3/` | Contract gateway and agent-governance acceptance transcripts, including the shadow-mode vs. enforce-mode evidence pairs |
| `acceptance/phase5/` | Authentication acceptance: runnable scripts and transcripts for both the anonymous dev mode and the full OIDC mode |
| `acceptance/final/` | Screenshots of the shipped surfaces (e.g. [the portfolio dashboard](../acceptance/final/05-portfolio-dashboard.png), [a contract detail](../acceptance/final/07-contract-detail.png)) plus the visual-verification notes |

For deeper background on the states and vocabularies used here, see `substrate/docs/engine-spec/` (engine specifications) and `substrate/docs/dev-setup.md` (environment shapes and profiles).
