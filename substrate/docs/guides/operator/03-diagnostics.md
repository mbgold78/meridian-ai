# Task 3 — Diagnostic run, curation, and the provenance walk

What this task teaches: asking the AI to examine the ingested landscape,
reading the structured event lines and exit codes, accepting/rejecting
findings as a named human, and walking a finding's evidence chain back to
the source file — CLI and REST. Committed evidence: the complete worked
record `substrate/docs/acceptance/diagnostic-glacier-2026-06-11.json`
(two runs, batch ledgers, filter histograms, curation, verify sweep, and the
exit-3 negative proof).

## Prerequisites

Task 2 completed (the Glacier domain exists in the shared dev database).
The runner here uses `dev-shared` plus the deterministic mock script; for a
real run set `ANTHROPIC_API_KEY` and drop both `substrate.llm.*` overrides.

## Steps — CLI lane

1. Run one synchronous diagnostic against the Glacier domain, declaring your
   yield requirement:

   ```powershell
   cd substrate
   .\gradlew.bat :app:bootRun --args="--spring.profiles.active=dev-shared --substrate.diagnostic.run=true --substrate.diagnostic.domain-name=Glacier --substrate.diagnostic.required-min-findings=1 --substrate.llm.provider=mock --substrate.llm.mock.diagnostic-script=grounded-content"
   ```

   `required-min-findings=1` makes "nothing survived the filter" a hard
   failure (exit 3) instead of a silent success. Leaving it 0 means "an
   empty result is acceptable" — the runner then exits 0 on zero findings,
   but always with an explicit yield line, never silently.

2. Read the `DIAG_EVENT` lines. A healthy run:

   ```text
   DIAG_EVENT kind=run_started runId=… domain=… scopes=systems,processes,canonical-entities batches=4 executor=in-process
   DIAG_EVENT kind=batch_completed runId=… batch=1/4 returned=3 filtered=2 committed=1
   ...
   DIAG_EVENT kind=run_finalized runId=… status=COMPLETED committed=4 filtered=8 superseded=0 tokensIn=320 tokensOut=160 costEstimate=0.0168
   DIAG_EVENT kind=cli_exit runId=… status=COMPLETED committed=4 exit=0
   ```

   Per batch you always see how many candidates the model returned, how many
   the mandatory filter rejected, and how many were committed. Exit codes:
   `0` yield met (including a legitimate clean-domain zero), `2` no parseable
   candidates / all batches failed / cancelled / run already active, `3`
   **zero-yield hard fail** (below your declared requirement), `4`
   misconfiguration (no/unknown domain).

## Steps — REST lane and curation

3. Everything the CLI does is also HTTP (the runner and the API share one
   engine). Start, watch, and sweep — note list endpoints require `domainId`:

   ```text
   POST /api/v1/diagnostics/runs            {"domainId":"…","requiredMinFindings":1,"idempotencyKey":"run-1"}   → 202, run id
   GET  /api/v1/diagnostics/runs/{id}       status, per-batch ledger, filter histogram, token/cost totals
   GET  /api/v1/ecosystem/findings?domainId=…&curationStatus=PENDING_REVIEW
   ```

   A second start while one is active returns 409 `DIAGNOSTIC_RUN_ACTIVE` —
   one run per domain at a time. The committed run detail shows the shape to
   expect: `candidatesReturned: 12, candidatesFiltered: 8,
   findingsCommitted: 4`, with `filterHistogram: {"badSeverity": 4,
   "ungrounded": 4}` — every rejection is counted and named.

4. Walk a finding's provenance before judging it:

   ```text
   GET /api/v1/findings/{id}/provenance
   ```

   The chain materializes in one response: finding → AI assertion → evidence
   records → registered source documents (file name, SHA-256, converter run)
   → ingest runs → curation decisions. From the committed walk:
   `chainComplete: true`, `groundingMode: EXISTENCE_ONLY`, evidence
   `extractor: ingestion`, registry
   `sha256: e6326c4639783a3150b705ec4c6a070a207c5dd4e28d46968e867496c4e446ca`
   for `glacier-systems.csv`. **`EXISTENCE_ONLY` is the phrase to
   understand:** the machine verified the cited evidence *exists* and traces
   to a real source file — whether it semantically supports the claim is what
   *you* certify at acceptance. Nothing in Substrate ever claims
   "machine-verified grounding."

5. Curate — strictly per item, no bulk verbs:

   ```text
   POST /api/v1/findings/{id}/accept   {"rationale":"…","idempotencyKey":"c-1"}        (rationale optional)
   POST /api/v1/findings/{id}/reject   {"rationale":"…","idempotencyKey":"c-2"}        (rationale MANDATORY — RATIONALE_REQUIRED otherwise)
   ```

   Both return 200 — rejection is a legitimate outcome. Accepted/rejected
   decisions are never touched by later runs; only still-pending findings
   get superseded by a re-run (the committed evidence shows run 2 superseding
   run 1's pending leftovers while the human decisions stood).

6. Integrity-sweep the whole domain:

   ```text
   GET /api/v1/diagnostics/verify?domainId=…
   ```

   The committed clean sweep:

   ```json
   {"total":12,"chainComplete":12,"brokenChains":[],"fixtureGrounded":[],"groundingModes":{"EXISTENCE_ONLY":12}}
   ```

## Provenance UI

7. The same chain has a UI face: on the Living Canvas, the drawer's **From**
   tab (deep-linkable as `/?view=provenance`) lists evidence entries grouped
   by source file — label, source file, extractor chip, sha256 prefix. On a
   seeded demo boot the entries are the seeder's synthetic rows (the chip
   honestly says `seed`); after your Glacier ingest on `dev-shared`, real
   rows say `ingestion` and name the CSVs you committed:

{{capture:operator-provenance-drawer}}

## You'll know it worked when…

…`cli_exit` reports `status=COMPLETED committed=4 exit=0`, your accept and
reject both return 200 (with the reject refusing to proceed until you wrote
a rationale), and the verify sweep reports **`brokenChains: []` with
`chainComplete` equal to `total`** — the metric that moved is committed
findings 0 → 4, then the pending-review queue shrinking as your curation
decisions land.
