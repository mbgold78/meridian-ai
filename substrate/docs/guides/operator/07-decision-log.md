# Task 7 — Verify the decision log, and how to read a break

What this task teaches: reading the engagement's tamper-evident record and
interpreting the verify report — intact and broken — from the response shape.
Committed evidence:
`substrate/docs/acceptance/doc-1/s4-operator/transcript-7-decision-log.txt`
(both verifies below are quoted from it).

The decision log is hash-chained: each entry stores a SHA-256 of its own
canonical content plus the previous entry's hash; the first entry's
previous-hash is the literal `GENESIS`. Three classes of acts write entries:
design decisions, contract accountability (activation / decline / breach
waiver), and agent governance (deploy / promote / retire). Every boot
re-verifies every domain chain.

## Steps

1. Read the chain in order — every `previousHash` must equal the prior
   entry's `currentHash`:

   ```text
   GET /decision-log?domainId=…
   CONSOLIDATE              APPROVED  prev=GENESIS  -> curr=32f4d5b7  D-001: Consolidate IssuesPortal and Archer Issues Tracking
   RETIRE                   APPROVED  prev=32f4d5b7 -> curr=626759e6  D-002: Retire Piedmont Risk Register and Complete Archer Migration
   AUTOMATE_MANUAL_PROCESS  PROPOSED  prev=626759e6 -> curr=9d95072c  D-003: Automate Change Log / Change Risk Tool Dual Entry
   CONSOLIDATE              REJECTED  prev=9d95072c -> curr=e28d3133  D-004: Consolidation of NPA System and Change Risk Tool
   FORMALIZE_MANUAL_PROCESS PROPOSED  prev=e28d3133 -> curr=cba6baf1  D-005: Formalize CRE Concentration Calculation Before Retirement
   CONTRACT_ACCOUNTABILITY  APPROVED  prev=cba6baf1 -> curr=bd55b4ae  contract:co-tribal-knowledge-depopulation:CONTRACT_ACTIVATED
   ```

   Note the sixth entry: the demo's contract activation was anchored through
   the same chain as the design decisions — cross-engine accountability in
   one record.

   Hash values are per-boot: a fresh seed mints a new chain, so your
   `prev=`/`curr=` prefixes will differ from this committed transcript. The
   types, statuses, titles, ordering, and the `GENESIS` anchor are what must
   match — and the verify report below is boot-stable.

2. Verify on demand:

   ```text
   GET /decision-log/verify?domainId=…
   ```

   ```json
   {
     "valid": true,
     "totalDecisions": 6,
     "verifiedLinks": 6,
     "firstBrokenAt": null,
     "message": "All 6 decisions verified; chain is intact."
   }
   ```

3. After a working session, verify again. This transcript's session (tasks
   4–6 on the same boot) activated a contract and deployed + promoted an
   agent — three new anchors, chain still intact:

   ```json
   { "valid": true, "totalDecisions": 9, "verifiedLinks": 9, "firstBrokenAt": null,
     "message": "All 9 decisions verified; chain is intact." }
   ```

   ```text
   CONTRACT_ACCOUNTABILITY  APPROVED  prev=bd55b4ae -> curr=47760551  contract:fi-shared-pre-fill-agent:CONTRACT_ACTIVATED
   AGENT_DEPLOYED           APPROVED  prev=47760551 -> curr=6b87343c  agent:a-docs-pilot:AGENT_DEPLOYED
   AGENT_PROMOTED           APPROVED  prev=6b87343c -> curr=3426ec8a  agent:a-docs-pilot:AGENT_PROMOTED
   ```

## Interpreting a break

Do **not** corrupt a chain to see this — the report's shape tells you
everything. On a break, the same endpoint answers:

| Field | Intact | Broken — how to read it |
|---|---|---|
| `valid` | `true` | `false` |
| `verifiedLinks` | `== totalDecisions` | The number of links that verified **before** the first break — everything up to there is still trustworthy |
| `firstBrokenAt` | `null` | The id of the first decision whose link failed — the chain stops being trustworthy **at** this entry |
| `message` | "All N decisions verified; chain is intact." | Describes the failure: the entry's stored content no longer matches its hash, or its previous-hash no longer matches its predecessor |

A break means something was modified outside the append path. **Treat it as
an incident**: the log can tell you *where* trust stops, never *what*
changed — pull database backups and access logs from before
`firstBrokenAt`'s timestamp. There is no repair verb; a chain is never
quietly re-hashed.

## Verify in UI

4. The same verify is one click in the product: on the Living Canvas, the
   drawer's **Log** tab (deep-linkable as `/?view=decisions`) lists the chain
   newest-first with each row's `prev → curr` hash pair, and the **Verify
   chain** button renders the intact banner. The capture below is the drawer
   header — count, button, banner (the row hashes are per-boot values, which
   is exactly what the chain design predicts, so the regression capture crops
   to the deterministic part):

{{capture:operator-decision-verify}}

## You'll know it worked when…

…the verify response reads **`valid: true` with `verifiedLinks` equal to
`totalDecisions` and `firstBrokenAt: null`** — and after your governance
session the re-verify shows the chain **grew (6 → 9 here) and still
verifies**, with the new anchors naming exactly the acts you performed.
