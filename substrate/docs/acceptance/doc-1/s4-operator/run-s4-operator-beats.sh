#!/bin/bash
# DOC-1 S4 — operator-guide terminal beats, USER-RUNNABLE (acceptance A4).
#
# App booted with the CANONICAL capture posture (plan §5 / ratification P-4):
#   java -jar app/build/libs/app-0.1.0.jar --spring.profiles.active=dev \
#     --substrate.demo.seed=true --substrate.demo.contracts.activate=true \
#     --substrate.demo.fixed-clock=2026-05-12T00:00:00Z --server.port=<port>
#
# Usage: ./run-s4-operator-beats.sh <baseUrl> <section>
#   section ∈ decisions-intact | cascade | contracts | agents | decisions-recheck
#
# Sections are ordered ON PURPOSE: decisions-intact runs FIRST (the pristine
# seeded chain is 6 entries; the cascade/contract/agent beats append
# CONTRACT_ACCOUNTABILITY and AGENT_* anchors), decisions-recheck runs LAST
# to show the chain GREW and still verifies. Guide blocks in
# docs/guides/operator/ are taken verbatim from the transcripts this script
# produced (committed beside it) — guide and evidence must agree.
set -u
export PYTHONUTF8=1
BASE="${1:?baseUrl, e.g. http://localhost:53180}/api/v1"
SECTION="${2:?section}"
H1='Content-Type: application/json'
H2='X-Persona-Id: operating-partner'
H3='X-Persona-Label: Operating Partner'

step() { echo; echo "================ $1 ================"; }
show() { py -3 -m json.tool 2>/dev/null || cat; }
uuid() { py -3 -c "import uuid; print(uuid.uuid4())"; }

DOMAIN_ID=$(curl -s "$BASE/domains" | py -3 -c "
import json,sys
print([d['id'] for d in json.load(sys.stdin)['data'] if d['name']=='Operational Risk'][0])")

# ---------------------------------------------------------------- decisions
if [ "$SECTION" = "decisions-intact" ]; then

  step "1. GET /decision-log?domainId= ==> the seeded chain in order: 6 entries, GENESIS-anchored"
  curl -s "$BASE/decision-log?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys
for d in json.load(sys.stdin)['data']:
    print(f\"{d['decisionType']:<24} {d['status']:<9} prev={ (d['previousHash'] or 'GENESIS')[:8] :<8} -> curr={d['currentHash'][:8]}  {d['title']}\")"

  step "2. GET /decision-log/verify?domainId= ==> intact report (valid, links == decisions, no first-broken pointer)"
  curl -s "$BASE/decision-log/verify?domainId=$DOMAIN_ID" | show

# ------------------------------------------------------------------ cascade
elif [ "$SECTION" = "cascade" ]; then

  step "1. POST /cascade-drafts ==> 201 OPEN draft (free-form; critique + acceptance criteria; SLA 0 = no deadline)"
  DRAFT=$(curl -s -X POST -H "$H1" -H "$H2" -H "$H3" "$BASE/cascade-drafts" -d "{
    \"domainId\":\"$DOMAIN_ID\",\"authoringMode\":\"free-form\",
    \"originalCritique\":\"Manual eligibility re-checks are duplicated across intake and billing. Consolidate to one owner per claim before Q3.\",
    \"acceptanceCriteria\":\"A single named owner per claim; duplicate re-checks retired; a documented decline citing regulatory constraints is acceptable.\",
    \"summary\":\"Consolidate duplicated eligibility re-checks\",
    \"responseSlaDays\":0}")
  echo "$DRAFT" | show
  DRAFT_ID=$(echo "$DRAFT" | py -3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")

  step "2. PATCH /cascade-drafts/{id} ==> refine while OPEN (absent fields untouched)"
  curl -s -X PATCH -H "$H1" -H "$H2" -H "$H3" "$BASE/cascade-drafts/$DRAFT_ID" \
    -d '{"originalCritique":"Manual eligibility re-checks are duplicated across intake and billing. Consolidate to one accountable owner per claim before Q3 close."}' \
    | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status          :', d['status'])
print('originalCritique:', d['originalCritique'])"

  IKEY=$(uuid)
  step "3. POST /cascade-drafts/{id}/issue ==> 201, the live cascade is born IN_FLIGHT at L1 (critique + criteria frozen)"
  ISSUED=$(curl -s -X POST -H "$H1" -H "$H2" -H "$H3" "$BASE/cascade-drafts/$DRAFT_ID/issue" \
    -d "{\"idempotencyKey\":\"$IKEY\",\"level1OwnerLabel\":\"Curasync COO\"}")
  echo "$ISSUED" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('id             :', d['id'])
print('currentState   :', d['currentState'], '| attentionSignal:', d['attentionSignal'])
print('originatedBy   :', d['originatedBy'], '| originatedAt:', d['originatedAt'])
print('frontier       : L', d['frontierDepth'], d['frontierState'], '-', d['frontierOwnerLabel'])
print('levels         :', [(l['level'], l['ownerLabel'], l['state']) for l in d['levels']])"
  CASCADE_ID=$(echo "$ISSUED" | py -3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")

  step "4. Re-issue with the SAME idempotencyKey ==> 200 the SAME cascade (idempotent on the draft row, ADR D8)"
  curl -s -o /dev/null -w "HTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" -H "$H3" \
    "$BASE/cascade-drafts/$DRAFT_ID/issue" -d "{\"idempotencyKey\":\"$IKEY\",\"level1OwnerLabel\":\"Curasync COO\"}"

  step "5. POST /cascades/{id}/levels/1/cascade AS the L1 owner ==> chain grows to L2 (level verbs belong to the level owner)"
  curl -s -X POST -H "$H1" -H 'X-Persona-Id: curasync-coo' -H 'X-Persona-Label: Curasync COO' \
    "$BASE/cascades/$CASCADE_ID/levels/1/cascade" -d "{
    \"idempotencyKey\":\"$(uuid)\",
    \"refinedCritique\":\"HIM owns the consolidation: produce the single-owner-per-claim model and retire the billing-side re-check.\",
    \"nextOwnerLabel\":\"Curasync HIM Director\"}" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('currentState   :', d['currentState'], '| attentionSignal:', d['attentionSignal'], '| frontierDepth:', d['frontierDepth'])
print('levels         :', [(l['level'], l['ownerLabel'], l['state']) for l in d['levels']])"

  step "5b. GET /cascades/{id}/actions ==> the append-only action timeline (the audit trail)"
  curl -s "$BASE/cascades/$CASCADE_ID/actions" | py -3 -c "
import json,sys
for a in json.load(sys.stdin)['data']:
    print(f\"#{a['chainSeq']} {a['action']:<8} L{a['depth']} {a['actorLabel']:<18} -> {a['nextOwnerLabel'] or '-'}\")"

  step "6. GET /cascades/attention?domainId= ==> the worklist names whose action is demanded right now"
  curl -s "$BASE/cascades/attention?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys
for a in json.load(sys.stdin)['data']:
    print(f\"{a['kind']:<18} {a['headline']}\")"

  step "7. GET /cascades/stats?domainId= ==> the issued count moved 3 -> 4 (the new cascade is escalating)"
  curl -s "$BASE/cascades/stats?domainId=$DOMAIN_ID" | show

# ---------------------------------------------------------------- contracts
elif [ "$SECTION" = "contracts" ]; then

  FI=$(curl -s "$BASE/contracts/fi-shared-pre-fill-agent")
  P0=$(echo "$FI" | py -3 -c "import json,sys;print(json.load(sys.stdin)['data']['parties'][0]['id'])")
  P1=$(echo "$FI" | py -3 -c "import json,sys;print(json.load(sys.stdin)['data']['parties'][1]['id'])")

  step "1. GET /contracts/fi-shared-pre-fill-agent ==> the seeded federation invitation sits PROPOSED; monitor not armed"
  echo "$FI" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status   :', d['status'])
print('parties  :', [(p['partyLabel'], 'required' if p['responseRequired'] else 'optional', p['response']) for p in d['parties']])
print('monitors :', [(m['name'], m['status'], m['ruleType']) for m in d['monitors']])"

  step "2. POST respond — party 1 of 2 ACCEPTED ==> still PROPOSED (activation needs the LAST required acceptance)"
  curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P0\",\"response\":\"ACCEPTED\"}" \
    "$BASE/contracts/fi-shared-pre-fill-agent/respond" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status   :', d['status'])
print('parties  :', [(p['partyLabel'], p['response']) for p in d['parties']])"

  step "3. POST respond — party 2 of 2 ACCEPTED ==> ACTIVE; monitors arm"
  curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P1\",\"response\":\"ACCEPTED\"}" \
    "$BASE/contracts/fi-shared-pre-fill-agent/respond" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status     :', d['status'])
print('activatedAt:', d['activatedAt'])
print('parties    :', [(p['partyLabel'], p['response']) for p in d['parties']])
print('monitors   :', [(m['name'], m['status']) for m in d['monitors']])"

  # Under the pinned demo clock the sweep never claims a monitor on its own
  # (next_run_at < now is never true when the clock is frozen) — run-now is
  # the demo lever, exactly as in the committed acceptance-3b transcripts.
  # The sweep's ARMING and SURFACING passes still tick every PT60S.
  FIMON=$(curl -s "$BASE/contracts/fi-shared-pre-fill-agent" | py -3 -c "
import json,sys; print(json.load(sys.stdin)['data']['monitors'][0]['id'])")

  step "4. POST run-now on the armed monitor ==> INDETERMINATE: no party reported metrics yet — the monitor never guesses on missing data (ADR D11)"
  curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
    "$BASE/contracts/fi-shared-pre-fill-agent/monitors/$FIMON/run-now" | show

  step "5. GET /contracts/fi-shared-pre-fill-agent/monitor-runs ==> the run history records every evaluation with its bounded trace"
  curl -s "$BASE/contracts/fi-shared-pre-fill-agent/monitor-runs" | show

  COMON=$(curl -s "$BASE/contracts/co-tribal-knowledge-depopulation" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([m['id'] for m in d['monitors'] if m['name']=='missing-monthly-report'][0])")

  step "6. POST run-now missing-monthly-report (co-tribal, ACTIVE via the demo lever) ==> BREACH_DETECTED period 2026-04"
  curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
    "$BASE/contracts/co-tribal-knowledge-depopulation/monitors/$COMON/run-now" | show

  step "7. Watch the sweep SURFACE the breach: poll GET breaches until status=SURFACED (the PT60S surfacing pass; never sleep)"
  for i in $(seq 1 90); do
    ST=$(curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/breaches" | py -3 -c "
import json,sys
rows=json.load(sys.stdin)['data']
print(rows[0]['status'] if rows else 'NONE')")
    if [ "$ST" = "SURFACED" ]; then echo "breach SURFACED after ~${i}s of polling:"; break; fi
    sleep 1
  done
  curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/breaches" | show

  step "8. GET /contracts/co-tribal-knowledge-depopulation/monitor-runs ==> the BREACH_DETECTED run is in the history"
  curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/monitor-runs" | py -3 -c "
import json,sys
for r in json.load(sys.stdin)['data']:
    print(f\"run {r['id'][:8]}…  outcome={r['outcome']:<16} periodKey={r['periodKey']}\")"

  step "9. GET /contracts/signals?domainId= ==> openBreaches.total=1 — the one-call operator heartbeat"
  curl -s "$BASE/contracts/signals?domainId=$DOMAIN_ID" | show

# ------------------------------------------------------------------- agents
elif [ "$SECTION" = "agents" ]; then

  AH='X-Persona-Label: Curasync RCM team'

  step "1. POST /agents ==> register (deploy); agents land in SHADOW by default; provenance fields are mandatory"
  curl -s -X POST -H "$H1" -H "$AH" "$BASE/agents" -d "{
    \"idempotencyKey\":\"$(uuid)\",\"domainId\":\"$DOMAIN_ID\",\"agentKey\":\"a-docs-pilot\",
    \"name\":\"Claims Pre-fill Pilot\",\"roleDescription\":\"Pre-fills compliance forms from claim context\",
    \"executionLocus\":\"substrate-mcp\",\"deployedByLabel\":\"Curasync RCM team\",
    \"deploymentCriteria\":\"Operate within the governed gateway scope, target 90% pre-fill accuracy\",
    \"criteriaGates\":[{\"gateText\":\"target 90% pre-fill accuracy\",\"ruleType\":\"structured\",
      \"predicate\":{\"metric\":\"prefill_accuracy\",\"gte\":0.90},\"cadence\":\"MONTHLY\"}],
    \"oversightChain\":[{\"label\":\"Curasync RCM Lead\"},{\"label\":\"Curasync COO\"}],
    \"supervisoryModel\":\"human-in-loop\"}" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('id        :', d['id'], '| status:', d['status'], '| operationalMode:', d['operationalMode'])
print('deployedBy:', d['deployedBy'], '| deployedAt:', d['deployedAt'])
print('oversight :', d['oversightChain'])
print('readOnly  :', d['readOnly'], '(read-only at the gateway unless explicitly granted otherwise)')"

  step "2. GET /agents/a-docs-pilot ==> controlEffects disclose what pause/kill/throttle would physically do BEFORE you act"
  curl -s "$BASE/agents/a-docs-pilot?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status        :', d['status'])
print('controlEffects:')
print(json.dumps(d['controlEffects'], indent=2))"

  step "3. POST promote WITHOUT a decided review ==> 409 PROMOTION_REVIEW_REQUIRED (the review gate is real)"
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$AH" \
    "$BASE/agents/a-docs-pilot/promote?domainId=$DOMAIN_ID" -d "{\"idempotencyKey\":\"$(uuid)\"}" | py -3 -c "
import sys, json
raw=sys.stdin.read(); body=json.loads(raw[:raw.rfind('HTTP')])
print('HTTP  :', raw[raw.rfind('HTTP'):].strip())
print('errors:', json.dumps(body['errors'], indent=2))"

  step "4. POST promote/request ==> recorded; the detail now carries the request + the shadow window it summarizes"
  curl -s -X POST -H "$H1" -H "$AH" "$BASE/agents/a-docs-pilot/promote/request?domainId=$DOMAIN_ID" \
    -d "{\"idempotencyKey\":\"$(uuid)\"}" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('verb status:', d['status'])"
  curl -s "$BASE/agents/a-docs-pilot?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('promotion  :', json.dumps(d['promotion']))
print('shadow since:', d['shadowEnteredAt'])"

  step "5. POST reviews + decide approved ==> a named reviewer records the compliance review with a written rationale"
  REVIEW=$(curl -s -X POST -H "$H1" -H "$AH" "$BASE/agents/a-docs-pilot/reviews?domainId=$DOMAIN_ID" \
    -d '{"reviewKind":"promotion","requestedByLabel":"Curasync RCM Lead"}' \
    | py -3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
  echo "review recorded: $REVIEW"
  curl -s -X POST -H "$H1" -H "$AH" "$BASE/agents/reviews/$REVIEW/decide" \
    -d '{"decision":"approved","rationale":"Shadow window clean; mandate gates structured; oversight chain staffed","reviewerLabel":"Curasync RCM Lead"}' | show

  step "6. POST promote ==> 200; the approved review is CONSUMED (single use); status live"
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$AH" \
    "$BASE/agents/a-docs-pilot/promote?domainId=$DOMAIN_ID" -d "{\"idempotencyKey\":\"$(uuid)\"}" | py -3 -c "
import sys, json
raw=sys.stdin.read(); body=json.loads(raw[:raw.rfind('HTTP')])
print('HTTP   :', raw[raw.rfind('HTTP'):].strip())
print('status :', body['data']['status'])"

  step "7. POST pause {reason!} ==> 200 + the enforcement block — READ THE DISCLOSURE, not your assumptions (P-1)"
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$AH" \
    "$BASE/agents/a-docs-pilot/pause?domainId=$DOMAIN_ID" \
    -d "{\"idempotencyKey\":\"$(uuid)\",\"reason\":\"Monthly mandate-gate review window\"}" | py -3 -c "
import sys, json
raw=sys.stdin.read(); body=json.loads(raw[:raw.rfind('HTTP')])
print('HTTP        :', raw[raw.rfind('HTTP'):].strip())
print('status      :', body['data']['status'])
print('enforcement :')
print(json.dumps(body['data']['enforcement'], indent=2))"

# --------------------------------------------------------- decisions-recheck
elif [ "$SECTION" = "decisions-recheck" ]; then

  step "8. RE-VERIFY after this session's governance acts ==> the chain GREW (contract activation + agent deploy/promote anchors) and still verifies"
  curl -s "$BASE/decision-log/verify?domainId=$DOMAIN_ID" | show
  curl -s "$BASE/decision-log?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys
rows=json.load(sys.stdin)['data']
print(f'{len(rows)} chain entries; the appended anchors:')
for d in rows[6:]:
    print(f\"{d['decisionType']:<24} {d['status']:<9} prev={d['previousHash'][:8]} -> curr={d['currentHash'][:8]}  {d['title']}\")"

else
  echo "unknown section: $SECTION" >&2; exit 2
fi

echo
echo "================ SECTION $SECTION DONE ================"
