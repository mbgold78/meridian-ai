#!/bin/bash
# Engine-spec contracts.md §13 row 3c — USER-RUNNABLE acceptance sequence.
#
# Two phases, one boot each (the enforcement mode is engagement config, ADR D4):
#
#   PHASE A (shadow — the §1/D4 ship default):
#     gradlew :app:bootRun --args="--spring.profiles.active=dev \
#       --substrate.demo.seed=true --substrate.demo.contracts.guards=true"
#     ./acceptance-3c.sh shadow
#
#   PHASE B (enforce):
#     gradlew :app:bootRun --args="--spring.profiles.active=dev \
#       --substrate.demo.seed=true --substrate.demo.contracts.guards=true \
#       --substrate.contracts.enforcement.mode=enforce"
#     ./acceptance-3c.sh enforce
#
# The guards lever seeds + activates the SYNTHETIC guard-bearing contract
# demo-gateway-guards (agent.promote + contract.report_metric DENY guards;
# agent-fleet party bound to MCP principal 'demo-agent') — both fixture
# contracts compile to ZERO guards by spec §16.
set -u
export PYTHONUTF8=1   # MCP responses are UTF-8; keep py from cp1252-decoding them on Windows
BASE="http://localhost:8080/api/v1"
MCP="http://localhost:8080/mcp"
KEY="demo-gateway-guards"
H1='Content-Type: application/json'
H2='X-Persona-Id: operating-partner'
AGENT='X-MCP-Principal: demo-agent'
PHASE="${1:-shadow}"

step() { echo; echo "================ $1 ================"; }
show() { py -3 -m json.tool 2>/dev/null || cat; }

# ---- minimal scripted Streamable-HTTP MCP client (curl + python) ----------
# Initialize once, capture Mcp-Session-Id, then tools/call with the agent
# identity header. Responses may arrive as JSON or SSE frames — both parsed.
unwrap_jsonrpc() {
  py -3 -c "
import json,sys
raw=sys.stdin.read()
body=''
if 'data:' in raw and not raw.lstrip().startswith('{'):
    body=''.join(l[5:].strip() for l in raw.splitlines() if l.startswith('data:'))
else:
    body=raw
d=json.loads(body)
print(json.dumps(d.get('result', d), indent=2))
"
}

mcp_init() {
  local headers
  headers=$(curl -s -D - -o /dev/null "$MCP" \
    -H "$H1" -H 'Accept: application/json, text/event-stream' -H "$AGENT" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"acceptance-3c","version":"0.1.0"}}}')
  SESSION=$(echo "$headers" | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id" {print $2}')
  curl -s -o /dev/null "$MCP" -H "$H1" -H 'Accept: application/json, text/event-stream' \
    -H "$AGENT" -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
}

mcp_call() { # $1 = tool name, $2 = arguments JSON
  curl -s "$MCP" -H "$H1" -H 'Accept: application/json, text/event-stream' \
    -H "$AGENT" -H "Mcp-Session-Id: $SESSION" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    | unwrap_jsonrpc
}

# ---- shared lookups --------------------------------------------------------
DETAIL=$(curl -s "$BASE/contracts/$KEY")
FLEET_PARTY=$(echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([p['id'] for p in d['parties'] if p['partyLabel']=='Gateway Demo Agent Fleet'][0])")
FLEET_COMMITMENT=$(echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print([c['id'] for p in d['parties'] for c in p['commitments']
       if p['partyLabel']=='Gateway Demo Agent Fleet'][0])")

step "0. Preconditions: $KEY seeded by the guards lever and ACTIVE (guards live, §2.2)"
echo "$DETAIL" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('status      :', d['status'])
print('activatedAt :', d['activatedAt'])
print('parties     :', [p['partyLabel'] for p in d['parties']])"

mcp_init
echo "(MCP session: $SESSION)"

if [ "$PHASE" = "shadow" ]; then

  step "1. MCP check_action agent.promote  ==> expect verdict BLOCK"
  mcp_call check_action '{"action_type":"agent.promote"}'

  step "2. SHADOW: governed REST write (metric-samples = contract.report_metric, DENY-guarded) ==> expect HTTP 200"
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" \
    -d "{\"partyId\":\"$FLEET_PARTY\",\"metricKey\":\"utilization_share\",\"value\":0.61,\"periodKey\":\"2026-Q1\"}" \
    "$BASE/contracts/$KEY/metric-samples"

  step "3. GET decisions?decision=WOULD_DENY  ==> the shadow-mode would-be denial is rowed"
  curl -s "$BASE/contracts/$KEY/decisions?decision=WOULD_DENY" | show

  echo
  echo "================ PHASE A (shadow) DONE — reboot with --substrate.contracts.enforcement.mode=enforce ================"

else

  step "4. ENFORCE: the same REST write  ==> expect HTTP 409, code CONTRACT_DENIAL"
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" \
    -d "{\"partyId\":\"$FLEET_PARTY\",\"metricKey\":\"utilization_share\",\"value\":0.61,\"periodKey\":\"2026-Q1\"}" \
    "$BASE/contracts/$KEY/metric-samples"

  step "5. ENFORCE: same verb over MCP report_metric (principal demo-agent) ==> expect isError + structured CONTRACT_DENIAL payload"
  mcp_call report_metric "{\"contract_key\":\"$KEY\",\"metric_key\":\"utilization_share\",\"value\":0.61,\"period_key\":\"2026-Q1\"}"

  step "6. Open a real breach (attest MISSED — the attest verb is un-guarded here) ==> 200; breach DETECTED"
  curl -s -X POST -H "$H1" -H "$H2" \
    -d '{"periodKey":"ONE_TIME","complianceClaim":"MISSED","statement":"missed - opens the waive-lane breach"}' \
    "$BASE/commitments/$FLEET_COMMITMENT/attest" > /dev/null
  BREACH_ID=$(curl -s "$BASE/contracts/$KEY/breaches" | py -3 -c "
import json,sys; print(json.load(sys.stdin)['data'][0]['id'])")
  curl -s "$BASE/contracts/$KEY/breaches" | py -3 -c "
import json,sys
for b in json.load(sys.stdin)['data']:
    print({k: b[k] for k in ('id','status','detectedBy','summary')})"

  step "7. MCP waive_breach as AGENT  ==> DENIED (ADR D16 built-in non-removable guard; isError CONTRACT_DENIAL / WAIVER_HUMAN_ONLY)"
  mcp_call waive_breach "{\"breach_id\":\"$BREACH_ID\",\"rationale\":\"agent attempting a waiver\"}"

  echo
  echo "================ PHASE B (enforce) DONE ================"

fi
