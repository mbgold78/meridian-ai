# FINAL-GATE 3a acceptance checker (engine-spec contracts.md §13 row 3a / §7.3):
# 1) REST detail for both §14.1 vectors adapts (per the documented §7.3 mapping)
#    to objects DEEP-EQUAL to the homepoc fixture FILE objects;
# 2) MCP get_contract returns JSON identical to the REST envelope's data.
# Fixture source of truth: C:/Users/mbgol/dev/meridian-homepoc/frontend/src/demo/beacon/contracts.ts
# (extracted to fixture-full.json via node eval of the FILE — never prose). Run date: 2026-06-11.
import json
import sys
import urllib.request

BASE = "http://localhost:8080/api/v1"
MCP = "http://localhost:8080/mcp"


def get(url):
    with urllib.request.urlopen(url) as r:
        return json.load(r)


def adapt(detail):
    """The §7.3 adapter mapping (mirrors frontend contractData.adaptContractDetail)."""
    out = {
        "id": detail["contractKey"],
        "type": detail["type"],
        "title": detail["title"],
        "context": detail["context"],
        "parties": [
            {
                "partyLabel": p["partyLabel"],
                "commitments": [c["text"] for c in p["commitments"]],
            }
            for p in detail["parties"]
        ],
        "enforcement": {
            "monitor": detail["enforcement"]["monitor"],
            "monitorMechanism": detail["enforcement"]["monitorMechanism"],
            "declineTriggers": [t["text"] for t in detail["enforcement"]["declineTriggers"]],
        },
        "linkedSubsidiarySlugs": [
            l["slug"] for l in detail["linkedEntities"] if l["entityType"] == "SUBSIDIARY_SLUG"
        ],
        "proposedAt": detail["proposedAt"][:10],
    }
    if detail.get("estimatedValue") is not None:
        out["estimatedValue"] = detail["estimatedValue"]
    return out


def mcp_call(session, payload):
    req = urllib.request.Request(MCP, data=json.dumps(payload).encode(), method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Accept", "application/json, text/event-stream")
    req.add_header("X-MCP-Principal", "final-gate")
    if session:
        req.add_header("Mcp-Session-Id", session)
    with urllib.request.urlopen(req) as r:
        sid = r.headers.get("Mcp-Session-Id")
        raw = r.read().decode("utf-8")
    if raw.strip() and not raw.lstrip().startswith("{"):
        raw = "".join(l[5:].strip() for l in raw.splitlines() if l.startswith("data:"))
    return sid, (json.loads(raw) if raw.strip() else None)


fixture = {c["id"]: c for c in json.load(open(
    r"C:/Users/mbgol/DEV/meridian-ai/fixture-full.json", encoding="utf-8-sig"))}

failures = 0
rest_data = {}
for key in ("co-tribal-knowledge-depopulation", "fi-shared-pre-fill-agent"):
    detail = get(f"{BASE}/contracts/{key}")["data"]
    rest_data[key] = detail
    adapted = adapt(detail)
    expected = fixture[key]
    if adapted == expected:
        print(f"PASS  REST detail == fixture FILE object  [{key}]"
              f"  (status={detail['status']}, estimatedValue"
              f" {'present' if 'estimatedValue' in adapted else 'ABSENT (null preserved)'})")
    else:
        failures += 1
        print(f"FAIL  {key}: adapted != fixture")
        for k in set(expected) | set(adapted):
            if expected.get(k) != adapted.get(k):
                print(f"   field {k}:\n     fixture: {expected.get(k)!r}\n     adapted: {adapted.get(k)!r}")

# ---- MCP get_contract parity (scripted Streamable-HTTP client) -------------
sid, _ = mcp_call(None, {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                         "params": {"protocolVersion": "2025-03-26", "capabilities": {},
                                    "clientInfo": {"name": "final-gate", "version": "0"}}})
mcp_call(sid, {"jsonrpc": "2.0", "method": "notifications/initialized"})
for key in ("co-tribal-knowledge-depopulation", "fi-shared-pre-fill-agent"):
    _, resp = mcp_call(sid, {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                             "params": {"name": "get_contract",
                                        "arguments": {"contract_key": key}}})
    result = resp["result"]
    body = json.loads(result["content"][0]["text"])
    if result.get("isError"):
        failures += 1
        print(f"FAIL  MCP get_contract isError [{key}]")
    elif body == rest_data[key]:
        print(f"PASS  MCP get_contract == REST data (JSON-equal)  [{key}]")
    else:
        failures += 1
        print(f"FAIL  MCP/REST divergence [{key}]")
        for k in set(body) | set(rest_data[key]):
            if body.get(k) != rest_data[key].get(k):
                print(f"   field {k} differs")

print("3a RESULT:", "PASS" if failures == 0 else f"{failures} FAILURE(S)")
sys.exit(1 if failures else 0)
