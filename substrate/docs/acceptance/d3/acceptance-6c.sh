#!/bin/bash
# Engine-spec agents.md §16.1 row 6c — USER-RUNNABLE acceptance sequence.
#
# Two phases, one boot each (enforcement mode is engagement config, ADR-002 D4;
# agents ride the SAME ladder — one ladder, not two, ADR-003 D7):
#
#   PHASE A (shadow — the ship default):
#     gradlew :app:bootRun --args="--spring.profiles.active=dev \
#       --substrate.demo.seed=true --substrate.demo.contracts.guards=true"
#     ./acceptance-6c.sh shadow
#
#   PHASE B (enforce):
#     gradlew :app:bootRun --args="--spring.profiles.active=dev \
#       --substrate.demo.seed=true --substrate.demo.contracts.guards=true \
#       --substrate.contracts.enforcement.mode=enforce"
#     ./acceptance-6c.sh enforce
#
# Beats (spec §16.1 row 6c):
#   shadow : MCP heartbeat as a PAUSED agent -> sighting + WOULD_DENY rowed +
#            ACTIVE_WHILE_PAUSED in signals; get_my_registration returns the
#            verbatim mandate; party-bound principal allowed party-scoped verbs.
#   enforce: governed write as paused agent -> structured AGENT_GATE_DENIAL
#            isError; kill then heartbeat -> SEEN_AFTER_KILL; unregistered
#            principal -> denied; throttle 2/window -> third call
#            AGENT_BUDGET_EXHAUSTED; the seeded agent.promote DENY guard
#            blocks promote.
set -u
export PYTHONUTF8=1
BASE="http://localhost:8080/api/v1"
MCP="http://localhost:8080/mcp"
H1='Content-Type: application/json'
H2='X-Persona-Label: Curasync RCM team'
PHASE="${1:-shadow}"
PRINCIPAL="acceptance-6c-agent"

step() { echo; echo "================ $1 ================"; }
show() { py -3 -m json.tool 2>/dev/null || cat; }
uuid() { py -3 -c "import uuid; print(uuid.uuid4())"; }

# ---- minimal scripted Streamable-HTTP MCP client (curl + python) ----------
unwrap_jsonrpc() {
  py -3 -c "
import json,sys
raw=sys.stdin.read()
if 'data:' in raw and not raw.lstrip().startswith('{'):
    body=''.join(l[5:].strip() for l in raw.splitlines() if l.startswith('data:'))
else:
    body=raw
d=json.loads(body)
print(json.dumps(d.get('result', d), indent=2))
"
}

mcp_init() { # $1 = principal header value
  local headers
  headers=$(curl -s -D - -o /dev/null "$MCP" \
    -H "$H1" -H 'Accept: application/json, text/event-stream' -H "X-MCP-Principal: $1" \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"acceptance-6c","version":"0.1.0"}}}')
  SESSION=$(echo "$headers" | tr -d '\r' | awk -F': ' 'tolower($1)=="mcp-session-id" {print $2}')
  curl -s -o /dev/null "$MCP" -H "$H1" -H 'Accept: application/json, text/event-stream' \
    -H "X-MCP-Principal: $1" -H "Mcp-Session-Id: $SESSION" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}'
}

mcp_call() { # $1 = principal, $2 = tool name, $3 = arguments JSON
  curl -s "$MCP" -H "$H1" -H 'Accept: application/json, text/event-stream' \
    -H "X-MCP-Principal: $1" -H "Mcp-Session-Id: $SESSION" \
    -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"$2\",\"arguments\":$3}}" \
    | unwrap_jsonrpc
}

# ---- shared fixtures --------------------------------------------------------
# The guards lever's domain hosts everything so the seeded agent.promote DENY
# guard (contracts 3c acceptance) can fire on the promote beat. The contract
# detail DTO carries no domainId, so resolve it by listing contracts per
# domain until demo-gateway-guards appears.
DOMAIN=$(py -3 -c "
import json, urllib.request
domains = json.load(urllib.request.urlopen('$BASE/domains'))['data']
for d in domains:
    cards = json.load(urllib.request.urlopen(
        '$BASE/contracts?domainId=' + d['id']))['data']
    if any(c.get('contractKey') == 'demo-gateway-guards' for c in cards):
        print(d['id']); break
")

deploy_agent() { # $1 = agent key, $2 = extra JSON fields (may be empty)
  curl -s -X POST -H "$H1" -H "$H2" "$BASE/agents" -d "{
    \"idempotencyKey\":\"$(uuid)\",\"domainId\":\"$DOMAIN\",\"agentKey\":\"$1\",
    \"name\":\"Acceptance $1\",\"roleDescription\":\"6c acceptance vector\",
    \"executionLocus\":\"substrate-mcp\",\"deployedByLabel\":\"Curasync RCM team\",
    \"deploymentCriteria\":\"Operate within the governed gateway scope, target 90% pre-fill accuracy\",
    \"criteriaGates\":[{\"gateText\":\"target 90% pre-fill accuracy\",\"ruleType\":\"structured\",
      \"predicate\":{\"metric\":\"prefill_accuracy\",\"gte\":0.90},\"cadence\":\"MONTHLY\"}],
    \"oversightChain\":[{\"label\":\"Curasync RCM Lead\"},{\"label\":\"Curasync COO\"}],
    \"supervisoryModel\":\"human-in-loop\",\"mcpPrincipalId\":\"$PRINCIPAL-$1\"
    $2}" | py -3 -c "import json,sys; print('deployed:', json.load(sys.stdin)['data']['id'])"
}

verb() { # $1 = idOrKey/verb path, $2 = body JSON
  curl -s -w "\nHTTP %{http_code}\n" -X POST -H "$H1" -H "$H2" "$BASE/agents/$1" -d "$2"
}

signals() {
  curl -s "$BASE/agents/signals?domainId=$DOMAIN" | py -3 -c "
import json,sys
d=json.load(sys.stdin)['data']
print('controlViolations :', d['controlViolations'])
print('budgetsExhausted  :', d['budgetsExhausted'])
print('gateCoverage      :', d['gateCoverage'])"
}

if [ "$PHASE" = "shadow" ]; then

  step "1. Deploy a SUBSTRATE_MCP-locus agent bound to principal $PRINCIPAL-a-acc (domain of the guards lever)"
  deploy_agent "a-acc" ""

  step "2. Pause it ==> 200, enforcement.effect=recorded-intent + the honest SHADOW-ladder disclosure (shadow rows would-deny, never blocks — gateway-block is claimed only in enforce)"
  verb "a-acc/pause?domainId=$DOMAIN" "{\"idempotencyKey\":\"$(uuid)\",\"reason\":\"Acceptance: pause the governed agent\"}" \
    | py -3 -c "
import json,sys
raw=sys.stdin.read(); body=json.loads(raw[:raw.rfind('HTTP')])
print('HTTP        :', raw[raw.rfind('HTTP'):].strip())
print('enforcement :', body['data']['enforcement'])"

  step "3. SHADOW: MCP agent_heartbeat as the PAUSED agent ==> proceeds (shadow never blocks)"
  mcp_init "$PRINCIPAL-a-acc"
  mcp_call "$PRINCIPAL-a-acc" agent_heartbeat '{"activity_summary":"overnight batch still polling"}'

  step "4. The touch was SIGHTED + WOULD_DENY rowed + ACTIVE_WHILE_PAUSED in signals (zombie flow, §6.4)"
  curl -s "$BASE/agents/a-acc/sightings?domainId=$DOMAIN" | py -3 -c "
import json,sys
for s in json.load(sys.stdin)['data']:
    print({k: s[k] for k in ('channel','detail','seenAt')})"
  signals

  step "5. get_my_registration ==> the caller's VERBATIM mandate + gates + standing"
  mcp_call "$PRINCIPAL-a-acc" get_my_registration '{}' | py -3 -c "
import json,sys
d=json.load(sys.stdin)['structuredContent']
print('deploymentCriteria:', d['deploymentCriteria'])
print('status            :', d['status'])
print('gates             :', [(g['gateText'], g['standing']) for g in d['criteriaGates']])
print('oversightChain    :', d['oversightChain'])"

  step "6. Party-bound principal (demo-agent) keeps party-scoped contract verbs (P19 carve-out)"
  mcp_init "demo-agent"
  mcp_call "demo-agent" report_metric '{"contract_key":"demo-gateway-guards","metric_key":"utilization_share","value":0.61,"period_key":"2026-Q1"}'

  echo
  echo "================ PHASE A (shadow) DONE — reboot with --substrate.contracts.enforcement.mode=enforce ================"

else

  step "7. Deploy + pause a fresh agent for the enforce beats ==> 200, enforcement.effect=gateway-block (the D17 window is CLOSED and the ladder is ENFORCE)"
  deploy_agent "a-enf" ""
  verb "a-enf/pause?domainId=$DOMAIN" "{\"idempotencyKey\":\"$(uuid)\",\"reason\":\"Acceptance: pause before enforce beats\"}" \
    | py -3 -c "
import json,sys
raw=sys.stdin.read(); body=json.loads(raw[:raw.rfind('HTTP')])
print('HTTP        :', raw[raw.rfind('HTTP'):].strip())
print('enforcement :', body['data']['enforcement'])"

  step "8. ENFORCE: agent_heartbeat as the PAUSED agent ==> structured isError AGENT_GATE_DENIAL / AGENT_CONTROL_SUSPENDED"
  mcp_init "$PRINCIPAL-a-enf"
  mcp_call "$PRINCIPAL-a-enf" agent_heartbeat '{}'

  step "9. Kill, then heartbeat ==> SEEN_AFTER_KILL (the highest-urgency signal class)"
  verb "a-enf/kill?domainId=$DOMAIN" "{\"idempotencyKey\":\"$(uuid)\",\"reason\":\"Acceptance: emergency stop\"}" > /dev/null
  mcp_call "$PRINCIPAL-a-enf" agent_heartbeat '{}' > /dev/null
  signals

  step "10. Unregistered principal ==> denied (registration-required honesty)"
  mcp_init "never-registered-agent"
  mcp_call "never-registered-agent" agent_heartbeat '{}'

  step "11. Throttle 2/window, then three governed calls ==> third is AGENT_BUDGET_EXHAUSTED"
  deploy_agent "a-thr" ""
  verb "a-thr/throttle?domainId=$DOMAIN" "{\"idempotencyKey\":\"$(uuid)\",\"budgetKind\":\"ACTIONS_PER_WINDOW\",\"limitValue\":2,\"windowMinutes\":60}" > /dev/null
  mcp_init "$PRINCIPAL-a-thr"
  mcp_call "$PRINCIPAL-a-thr" agent_heartbeat '{"activity_summary":"1"}' > /dev/null
  mcp_call "$PRINCIPAL-a-thr" agent_heartbeat '{"activity_summary":"2"}' > /dev/null
  mcp_call "$PRINCIPAL-a-thr" agent_heartbeat '{"activity_summary":"3"}'
  signals

  step "12. The seeded agent.promote DENY guard (contracts 3c lever) blocks promote ==> 409 CONTRACT_DENIAL"
  deploy_agent "a-promo" ""
  verb "a-promo/promote/request?domainId=$DOMAIN" "{\"idempotencyKey\":\"$(uuid)\"}" > /dev/null
  REVIEW=$(curl -s -X POST -H "$H1" -H "$H2" "$BASE/agents/a-promo/reviews?domainId=$DOMAIN" \
    -d '{"reviewKind":"promotion","requestedByLabel":"Curasync RCM Lead"}' \
    | py -3 -c "import json,sys; print(json.load(sys.stdin)['data']['id'])")
  curl -s -X POST -H "$H1" -H "$H2" "$BASE/agents/reviews/$REVIEW/decide" \
    -d '{"decision":"approved","rationale":"acceptance vector","reviewerLabel":"Curasync RCM Lead"}' > /dev/null
  verb "a-promo/promote?domainId=$DOMAIN" "{\"idempotencyKey\":\"$(uuid)\"}"

  echo
  echo "================ PHASE B (enforce) DONE ================"

fi
