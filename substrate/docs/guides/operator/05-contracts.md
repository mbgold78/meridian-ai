# Task 5 — Contract lifecycle: respond → activate → monitor → breach

What this task teaches: taking the seeded PROPOSED federation invitation to
ACTIVE by recording party acceptances, forcing monitor evaluations and
reading the run history over the monitor API, and reading a breach where it
actually surfaces. Committed evidence:
`substrate/docs/acceptance/doc-1/s4-operator/transcript-5-contracts.txt`,
cross-checked against the phase-3 originals
`substrate/docs/acceptance/phase3/substrate-3b-acceptance-evidence.txt` and
`substrate/docs/acceptance/d3/acceptance-3b.sh` (the breach beats below
reproduce their shapes exactly).

**Honesty up front (two things this task does NOT pretend):**

- **There is no contract-authoring API.** The two demo contracts are
  installed by the seed; authoring ships with a later phase. The operator
  task starts from the seeded `PROPOSED` contract.
- **The contract detail page renders terms only.** Lifecycle status, monitor
  outcomes, run history, and breaches are API-only today (a filed UI gap).
  Everything operational in this task is therefore a terminal capture — that
  is faithfully how an operator reads it now.

One discovered behavior worth knowing under the pinned demo clock: monitors
are armed "due now" at activation, but the sweep claims a monitor only when
`next_run_at` is strictly in the past — and a **frozen** clock never gets
there. On a fixed-clock demo boot, monitor evaluations therefore come from
`run-now` (exactly how the committed phase-3 acceptance produced them); the
sweep's trigger-arming and breach-surfacing passes still tick every ~60s.
On a real-clock deployment the sweep runs monitors by itself.

## Steps

1. Read the seeded federation invitation — `PROPOSED`, monitor not armed:

   ```text
   GET /contracts/fi-shared-pre-fill-agent
   status   : PROPOSED
   parties  : [('Polestar Life Admin (federation host)', 'required', None), ('Curasync Clinical Systems', 'required', None)]
   monitors : [('utilization-imbalance', 'SUSPENDED', 'METRIC_THRESHOLD')]
   ```

2. Record the first party's acceptance — responses are one-per-party and
   immutable; content froze the moment the first party responded:

   ```bash
   curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P0\",\"response\":\"ACCEPTED\"}" \
     "$BASE/contracts/fi-shared-pre-fill-agent/respond"
   ```

   ```text
   status   : PROPOSED          ← still: activation needs the LAST required acceptance
   parties  : [('Polestar Life Admin (federation host)', {'response': 'ACCEPTED', 'respondedBy': 'operating-partner', 'respondedAt': '2026-05-12T00:00:00Z', …}), ('Curasync Clinical Systems', None)]
   ```

3. Record the second acceptance — the contract activates and its monitors
   arm in the same act:

   ```text
   status     : ACTIVE
   activatedAt: 2026-05-12T00:00:00Z
   monitors   : [('utilization-imbalance', 'ACTIVE')]
   ```

   (A required party answering `DECLINED` with reasoning would instead
   resolve the contract `DECLINED` — with HTTP 200, because a documented
   decline is a legitimate terminal outcome, never an error.)

4. Force an evaluation of the newly armed monitor:

   ```bash
   curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
     "$BASE/contracts/fi-shared-pre-fill-agent/monitors/$MONITOR_ID/run-now"
   ```

   ```json
   "outcome": "INDETERMINATE",
   "periodKey": "2026-Q1",
   "details": {
       "rule": "METRIC_THRESHOLD", "metricKey": "utilization_share", "threshold": 0.3,
       "missingPartyIds": ["…", "…"],
       "note": "incomplete period data — NEVER computed on partial reports (ADR D11); needs-input signal ADHERENCE_STALE"
   }
   ```

   No party has reported metrics yet, so the monitor **refuses to guess** —
   `INDETERMINATE` plus a needs-input signal, never a fabricated breach and
   never a fabricated green.

5. Read the run history — every evaluation is recorded permanently with its
   bounded trace:

   ```text
   GET /contracts/fi-shared-pre-fill-agent/monitor-runs
   → the INDETERMINATE run above, newest first ("monitorId"/"since" query params narrow it)
   ```

6. Now the breach lane. The co-tribal contract booted ACTIVE (demo activate
   lever), and under the pinned clock its April reporting period is past the
   7-day grace window. Force the check:

   ```bash
   curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
     "$BASE/contracts/co-tribal-knowledge-depopulation/monitors/$MONITOR_ID/run-now"
   ```

   ```json
   "outcome": "BREACH_DETECTED",
   "periodKey": "2026-04",
   "details": {
       "rule": "ATTESTATION_CADENCE", "graceDays": 7,
       "commitments": [{"commitmentId": "…", "cadence": "MONTHLY", "periodKey": "2026-04",
                        "graceDeadline": "2026-05-07", "result": "BREACH_DETECTED"}]
   }
   ```

7. Watch the sweep surface it — poll the breach list until `SURFACED`
   (never sleep; the surfacing pass ticks every ~60s):

   ```text
   GET /contracts/co-tribal-knowledge-depopulation/breaches
   "status": "SURFACED", "periodKey": "2026-04", "detectedBy": "MONITOR",
   "summary": "Missing attestation for period 2026-04 past 7 grace days — Report monthly progress against the Beacon-provided template"
   ```

   The breach's human lifecycle from here: `POST /breaches/{id}/acknowledge`,
   `/remediate` (evidence refs required), `/waive` (written rationale
   mandatory, human-only). A later compliant pass never auto-closes it.

8. The contract's run history now carries the detection:

   ```text
   GET /contracts/co-tribal-knowledge-depopulation/monitor-runs
   run 13f54d2b…  outcome=BREACH_DETECTED  periodKey=2026-04
   ```

9. Read the one-call operator heartbeat:

   ```text
   GET /contracts/signals?domainId=…
   "openBreaches": {"total": 1, "bySeverity": {}},
   "needsInput": [{"contractKey": "fi-shared-pre-fill-agent", "monitorId": "…", "kind": "ADHERENCE_STALE"}],
   "monitorCoverage": {"structured": 2, "unstructured": 0}
   ```

   `openBreaches` moved 0 → 1 from your detection, and the fi-shared monitor
   that could not observe sits honestly in `needsInput`.

## Read the breach

10. Where does this reach the dashboard today? The contract page stays
    terms-first (see the honesty note above), so the breach itself is read
    over the API exactly as in steps 7–9. The dashboard echo is the
    coordination-signal strip on `/portfolio/dashboard`: its "Coordination
    signals" card counts contract events plus cascade actions over the
    trailing 7 days — the activation, run, and breach events your session
    just created are part of that count on next load:

{{capture:operator-breach-signals}}

## You'll know it worked when…

…the second `respond` flips `status` to **`ACTIVE`** with the monitor
`('utilization-imbalance', 'ACTIVE')`, the co-tribal `run-now` returns
**`BREACH_DETECTED` for `periodKey: 2026-04`**, the breach list shows it
**`SURFACED`**, and `GET /contracts/signals` reports **`openBreaches.total`
moved 0 → 1** with the unobservable monitor listed under `needsInput` rather
than silently green.
