#!/bin/bash
# Engine-spec contracts.md §13 row 3b — USER-RUNNABLE acceptance sequence.
# App booted with: --spring.profiles.active=dev --substrate.demo.seed=true
#   --substrate.demo.contracts.activate=true --substrate.demo.fixed-clock=2026-05-12T00:00:00Z
set -u
BASE="http://localhost:8080/api/v1"
H1='Content-Type: application/json'
H2='X-Persona-Id: operating-partner'
H3='X-Persona-Label: Operating Partner'

step() { echo; echo "================ $1 ================"; }
show() { py -3 -m json.tool 2>/dev/null || cat; }

# Demo domain id — printed by the ContractSeeder boot log line; passed as $1.
DOMAIN_ID="$1"

DETAIL=$(curl -s "$BASE/contracts/co-tribal-knowledge-depopulation")
MONITOR_ID=$(echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([m['id'] for m in d['monitors'] if m['name']=='missing-monthly-report'][0])
")
COMMITMENT_ID=$(echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([c['id'] for p in d['parties'] for c in p['commitments']
       if c['text']=='Report monthly progress against the Beacon-provided template'][0])
")
TRIGGER0_ID=$(echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print(d['enforcement']['declineTriggers'][0]['id'])
")

step "0. Preconditions: co-tribal ACTIVE under fixed clock 2026-05-12T00:00:00Z"
echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status      :', d['status'])
print('activatedAt :', d['activatedAt'])
print('monitor     :', [(m['name'], m['status'], m['ruleType']) for m in d['monitors']])
"

step "1. POST run-now missing-monthly-report  ==> expect BREACH_DETECTED period 2026-04"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
  "$BASE/contracts/co-tribal-knowledge-depopulation/monitors/$MONITOR_ID/run-now" | show

step "2. GET breaches  ==> expect DETECTED->SURFACED"
curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/breaches" | show
BREACH_ID=$(curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/breaches" | py -3 -c "
import json,sys; print(json.load(sys.stdin)['data'][0]['id'])")

step "3. GET signals  ==> open-breach count moved to 1; pendingFederationInvitations=1 (vector-2 pin)"
curl -s "$BASE/contracts/signals?domainId=$DOMAIN_ID" | show

step "4. POST attest 2026-05 ON_TRACK on the Curasync monthly-report commitment"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d '{"periodKey":"2026-05","complianceClaim":"ON_TRACK","statement":"May progress report submitted on the Beacon template"}' \
  "$BASE/commitments/$COMMITMENT_ID/attest" | show

step "5. POST run-now again  ==> expect COMPLIANT"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
  "$BASE/contracts/co-tribal-knowledge-depopulation/monitors/$MONITOR_ID/run-now" | show

FI=$(curl -s "$BASE/contracts/fi-shared-pre-fill-agent")
P0=$(echo "$FI" | py -3 -c "import json,sys;print(json.load(sys.stdin)['data']['parties'][0]['id'])")
P1=$(echo "$FI" | py -3 -c "import json,sys;print(json.load(sys.stdin)['data']['parties'][1]['id'])")
FIMON=$(echo "$FI" | py -3 -c "import json,sys;print(json.load(sys.stdin)['data']['monitors'][0]['id'])")
ACC_TRIG=$(echo "$FI" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([t['id'] for t in d['enforcement']['declineTriggers']
       if t['text']=='Polestar agent reaches accuracy threshold below 90%'][0])
")

step "6. Activate vector 2 over the respond endpoint (both parties ACCEPTED)"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P0\",\"response\":\"ACCEPTED\"}" \
  "$BASE/contracts/fi-shared-pre-fill-agent/respond" > /dev/null
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P1\",\"response\":\"ACCEPTED\"}" \
  "$BASE/contracts/fi-shared-pre-fill-agent/respond" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status  :', d['status'])
print('monitors:', [(m['name'], m['status']) for m in d['monitors']])
"

step "7. ONE party's metric sample (utilization_share 0.72, 2026-Q1) then run-now ==> INDETERMINATE (ADR D11)"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P0\",\"metricKey\":\"utilization_share\",\"value\":0.72,\"periodKey\":\"2026-Q1\"}" \
  "$BASE/contracts/fi-shared-pre-fill-agent/metric-samples" | show
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" \
  "$BASE/contracts/fi-shared-pre-fill-agent/monitors/$FIMON/run-now" | show

step "8. GET signals  ==> ADHERENCE_STALE in needsInput (zero-yield surfaces, never silent)"
curl -s "$BASE/contracts/signals?domainId=$DOMAIN_ID" | show

step "9. Arming demo: accuracy 0.85 (< 0.90 predicate) reported; wait for the 60s sweep arming pass (§2.6)"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d "{\"partyId\":\"$P0\",\"metricKey\":\"accuracy\",\"value\":0.85,\"periodKey\":\"2026-Q1\"}" \
  "$BASE/contracts/fi-shared-pre-fill-agent/metric-samples" > /dev/null
for i in $(seq 1 100); do
  ELIG=$(curl -s "$BASE/contracts/signals?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys; print(len(json.load(sys.stdin)['data']['declineEligible']))")
  if [ "$ELIG" != "0" ]; then echo "DECLINE_ELIGIBLE armed after ~$((i))s:"; break; fi
  sleep 1
done
curl -s "$BASE/contracts/signals?domainId=$DOMAIN_ID" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print(json.dumps(d['declineEligible'], indent=2))
"

step "10. POST decline fi-shared (cites the armed accuracy trigger) ==> 200 + DECLINED + monitors SUSPENDED"
curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" -H "$H3" \
  -d "{\"declineTriggerId\":\"$ACC_TRIG\",\"reasoning\":\"Sustained accuracy below the agreed 90% threshold\"}" \
  "$BASE/contracts/fi-shared-pre-fill-agent/decline" | py -3 -c "
import sys
raw=sys.stdin.read()
body, _, code = raw.rpartition('HTTP ')
import json
d=json.loads(body)['data']
print('HTTP        :', code.strip())
print('status      :', d['status'])
print('resolvedAt  :', d['resolvedAt'])
print('monitors    :', [(m['name'], m['status']) for m in d['monitors']])
"

step "11. GET fi-shared decisions ==> ALLOW_WITH_FLAG REVIEW_BOUNDARY_UNCONFIGURED (§6.2 null lane)"
curl -s "$BASE/contracts/fi-shared-pre-fill-agent/decisions" | show

step "12. POST decline co-tribal over the OPEN breach ==> 200; breach survives (§6.3)"
curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" -H "$H3" \
  -d "{\"declineTriggerId\":\"$TRIGGER0_ID\",\"reasoning\":\"Beacon methodology proved insufficient for the regulated compliance-officer roles\"}" \
  "$BASE/contracts/co-tribal-knowledge-depopulation/decline" | py -3 -c "
import sys
raw=sys.stdin.read()
body, _, code = raw.rpartition('HTTP ')
import json
d=json.loads(body)['data']
print('HTTP        :', code.strip())
print('status      :', d['status'])
print('monitors    :', [(m['name'], m['status']) for m in d['monitors']])
"

step "13. GET co-tribal decisions ==> ALLOW_WITH_FLAG OPEN_BREACHES_AT_DECLINE"
curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/decisions" | show

step "14. GET co-tribal breaches ==> the April breach SURVIVED decline (still SURFACED)"
curl -s "$BASE/contracts/co-tribal-knowledge-depopulation/breaches" | py -3 -c "
import json,sys
for b in json.load(sys.stdin)['data']:
    print({k: b[k] for k in ('status','periodKey','detectedBy','summary')})
"

step "15. Breach human verbs survive decline: acknowledge then waive (HUMAN, rationale mandatory; ADR D14 mirrored)"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d '{"note":"Reviewed with Curasync COO post-decline"}' \
  "$BASE/breaches/$BREACH_ID/acknowledge" | py -3 -c "
import json,sys; d=json.load(sys.stdin)['data']; print('status:', d['status'])"
curl -s -X POST -H "$H1" -H "$H2" -H "$H3" -d '{"rationale":"April reporting gap accepted given the contract was declined; no remediation value remains"}' \
  "$BASE/breaches/$BREACH_ID/waive" | py -3 -c "
import json,sys; d=json.load(sys.stdin)['data']
print('status:', d['status'], '| resolution:', d['resolution'], '| actor:', d['resolutionActor'])"

step "16. Terminal guard: decline again ==> 409 INVALID_CONTRACT_STATE (state error, never decline vocabulary)"
curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" -H "$H3" \
  -d "{\"declineTriggerId\":\"$ACC_TRIG\",\"reasoning\":\"again\"}" \
  "$BASE/contracts/fi-shared-pre-fill-agent/decline" | tail -3

echo
echo "================ DONE ================"
