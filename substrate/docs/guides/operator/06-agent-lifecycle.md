# Task 6 — Agent lifecycle: register → shadow → promote (review gate) → pause

What this task teaches: putting an AI agent under governance — registration
with mandatory provenance, the shadow default, the promotion review gate,
and the control verbs whose responses **disclose what they physically did**.
Committed evidence:
`substrate/docs/acceptance/doc-1/s4-operator/transcript-6-agents.txt`;
the shadow/enforce disclosure pair is independently committed at
`substrate/docs/acceptance/d3/acceptance-6c-evidence-shadow.txt` and
`…-enforce.txt` (this task's boot rides the ship-default `shadow` ladder, so
the disclosures below match the shadow file exactly).

**Honesty up front:** the client-facing Operations tab is read-only — there
are no pause/kill/promote buttons in the UI. Agent lifecycle controls are
operator-grade REST verbs, and that is the product rule (no control
affordance ships ahead of its honest-disclosure plumbing).

## Steps

1. Register (deploy). A registration cannot exist without provenance — who
   deployed it, the verbatim mandate, at least one criteria gate, and an
   oversight chain of named humans. Missing fields are rejected with named
   codes (e.g. `AGENT_PROVENANCE_MISSING_OVERSIGHT`):

   ```bash
   curl -s -X POST -H "$H1" -H 'X-Persona-Label: Curasync RCM team' "$BASE/agents" -d "{
     \"idempotencyKey\":\"$(uuid)\",\"domainId\":\"$DOMAIN_ID\",\"agentKey\":\"a-docs-pilot\",
     \"name\":\"Claims Pre-fill Pilot\",\"roleDescription\":\"Pre-fills compliance forms from claim context\",
     \"executionLocus\":\"substrate-mcp\",\"deployedByLabel\":\"Curasync RCM team\",
     \"deploymentCriteria\":\"Operate within the governed gateway scope, target 90% pre-fill accuracy\",
     \"criteriaGates\":[{\"gateText\":\"target 90% pre-fill accuracy\",\"ruleType\":\"structured\",
       \"predicate\":{\"metric\":\"prefill_accuracy\",\"gte\":0.90},\"cadence\":\"MONTHLY\"}],
     \"oversightChain\":[{\"label\":\"Curasync RCM Lead\"},{\"label\":\"Curasync COO\"}],
     \"supervisoryModel\":\"human-in-loop\"}"
   ```

   ```text
   id        : a-docs-pilot | status: shadow | operationalMode: shadow
   deployedBy: Curasync RCM team | deployedAt: 2026-05-12T00:00:00Z
   oversight : ['Curasync RCM Lead', 'Curasync COO']
   readOnly  : True (read-only at the gateway unless explicitly granted otherwise)
   ```

   Agents land in **shadow** — observation-only trial — by default.
   Deploying straight to live requires an explicit recorded
   `liveJustification`. The mandate is immutable after deployment; changing
   it means retiring and deploying a successor with a lineage link.

2. Before touching any control, read what it would actually do — the detail
   carries a `controlEffects` map:

   ```text
   GET /agents/a-docs-pilot?domainId=…
   "pause": {
     "effect": "recorded-intent",
     "disclosure": "registry flip recorded; the shared enforcement ladder (substrate.contracts.enforcement.mode) is in SHADOW: this agent's governed calls are rowed would-deny and zombie activity is signaled, not blocked — enforce mode makes this a hard gateway block"
   }
   ```

   Same shape for `kill` and `throttle`. Two effects exist: `gateway-block`
   (Substrate's own gateway will actually refuse the agent's next governed
   call) and `recorded-intent` (the order is recorded, the oversight chain is
   notified, and confirmation is tracked — Substrate never claims to have
   stopped something it cannot reach).

3. Try to promote without a review — **the gate is real**:

   ```text
   POST /agents/a-docs-pilot/promote   → HTTP 409
   "code": "PROMOTION_REVIEW_REQUIRED",
   "message": "promote requires an unconsumed APPROVED PROMOTION review newer than the request (ADR-003 D10) — record + decide a review first"
   ```

4. Request promotion (recorded, with the shadow window it summarizes):

   ```text
   POST /agents/a-docs-pilot/promote/request   {"idempotencyKey":"…"}
   promotion  : {"requestedAt": "2026-05-12T00:00:00Z", "latestReview": null}
   shadow since: 2026-05-12T00:00:00Z
   ```

5. Record and decide the compliance review — a named reviewer, a written
   rationale, decided **after** the request:

   ```bash
   curl -s -X POST … "$BASE/agents/a-docs-pilot/reviews?domainId=$DOMAIN_ID" \
     -d '{"reviewKind":"promotion","requestedByLabel":"Curasync RCM Lead"}'
   curl -s -X POST … "$BASE/agents/reviews/$REVIEW_ID/decide" \
     -d '{"decision":"approved","rationale":"Shadow window clean; mandate gates structured; oversight chain staffed","reviewerLabel":"Curasync RCM Lead"}'
   ```

   ```json
   "reviewKind": "promotion", "decision": "approved",
   "reviewer": "Curasync RCM Lead",
   "rationale": "Shadow window clean; mandate gates structured; oversight chain staffed",
   "consumedByEventId": null
   ```

   (A `rejected` review is informational, never error-colored — promotion can
   simply be re-requested.)

6. Promote — the approved review is consumed, single use, recorded in the
   event log with the review's identity:

   ```text
   POST /agents/a-docs-pilot/promote   → HTTP 200
   status : live
   ```

7. Pause, with the mandatory reason — and **read the enforcement block in
   the response, not your assumptions**:

   ```bash
   curl -s -X POST … "$BASE/agents/a-docs-pilot/pause?domainId=$DOMAIN_ID" \
     -d "{\"idempotencyKey\":\"$(uuid)\",\"reason\":\"Monthly mandate-gate review window\"}"
   ```

   ```json
   HTTP 200, "status": "paused",
   "enforcement": {
     "effect": "recorded-intent",
     "confirmationState": "pending",
     "disclosure": "registry flip recorded; the shared enforcement ladder (substrate.contracts.enforcement.mode) is in SHADOW: this agent's governed calls are rowed would-deny and zombie activity is signaled, not blocked — enforce mode makes this a hard gateway block"
   }
   ```

   That disclosure is the product's honesty contract. On this shadow-ladder
   boot the pause is recorded intent: a governed call by the paused agent
   would still proceed, rowed `WOULD_DENY`, and a sighting raises
   `ACTIVE_WHILE_PAUSED` to the whole oversight chain. The committed
   enforce-mode evidence (`acceptance-6c-evidence-enforce.txt`) shows the
   other end of the ladder — the same pause answers
   `"effect": "gateway-block", "confirmationState": "not-required"` and the
   agent's next governed call is denied with a structured
   `AGENT_GATE_DENIAL / AGENT_CONTROL_SUSPENDED` error. Same verb, different
   physical truth, always disclosed. `POST …/resume` returns the agent to
   its pre-pause mode.

## Observe in UI

8. Open the subsidiary dashboard's **Operations** tab
   (`/portfolio/subsidiary/curasync-clinical?tab=operations`). The fleet
   list shows each agent's lifecycle badge (Operating / Shadow / In
   intervention / Paused), the always-rendered provenance line ("Deployed by
   … · Oversight: …"), and — on click — the verbatim deployment criteria
   with the numbered oversight chain:

{{capture:subsidiary-operations}}

## You'll know it worked when…

…the bare promote is refused **409 `PROMOTION_REVIEW_REQUIRED`**, the
promote after the approved review returns **200 with `status: live`**, and
the pause returns **200 with `status: paused` plus an `enforcement` block
whose disclosure names exactly what the control physically did** — the
metric that moved is the agent's governance state, shadow → live → paused,
each transition attributed in the event log.
