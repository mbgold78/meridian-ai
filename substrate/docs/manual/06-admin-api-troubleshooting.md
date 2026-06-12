## Administration

This part is for the engagement operator — the person who configures, deploys, and watches the platform. Client-side users can skip to [API & MCP reference](#api--mcp-reference) or [Troubleshooting & FAQ](#troubleshooting--faq).

### Authentication modes

Substrate runs in one of two authentication modes, set per deployment by `substrate.auth.mode`. The mode changes who the platform believes you are; everything downstream (the persona shown in the header bar, the actor recorded on every action, the admin gates) follows from it.

| | `anonymous` (dev / demo default) | `oidc` (real deployments) |
|---|---|---|
| Who can call the API | Anyone who can reach the port — there is no login | Only callers presenting a valid token from the configured identity provider; everything else gets **401** (the unauthenticated liveness probes — `/api/v1/health`, `/actuator/health`, `/actuator/info` — stay public by design) |
| Where your identity comes from | Self-asserted request headers (the persona switcher writes them) | The verified token issued by the customer's identity provider |
| Persona switcher in the UI | A dropdown — pick any persona, the app believes you | Replaced by a read-only identity chip showing your name and mapped persona, with a sign-out button |
| Admin actions | Granted by self-asserting the admin persona header | Require a real admin role in your token |
| Audit trail actor | The asserted persona label/id (spoofable by design — disclosed) | Your stable user id from the identity provider |

#### Anonymous mode — what to know

- This is the mode the dev and demo profiles run in. There is no login screen and no security filter on the request path at all.
- The **persona switcher** in the header offers six personas (the default is Architect — wire level L3_ANALYST; Architect and Executive are functional, the other four are marked Preview). It is *self-asserted*: switching personas simply changes the headers the browser sends. It exists so a demo or workshop can walk through different viewpoints — it is not access control, and the platform discloses as much.
- Admin-gated actions (currently branding administration) require asserting the admin persona level on the request — an operator-grade act done with an API client, not the switcher.
- Agent and AI-system callers on the MCP surface identify themselves with a plain `X-MCP-Principal` header (trusted because the deployment is local; equally self-asserted).

Anonymous mode is acceptable only for local development and fixture-data demos. It must never face a network with real users or real data.

#### OIDC mode — what changes

OIDC mode turns the application into a standard OpenID Connect resource server. **The customer brings the identity provider** — any conformant issuer works (Okta, Microsoft Entra ID, Auth0, a customer-internal IdP). Keycloak is used as the throwaway *test* IdP in the Docker Compose stack and in CI; it is never a production dependency.

For the signed-in user:

- The SPA performs a standard browser login (authorization code + PKCE) against the configured issuer. You land back where you started, including deep links.
- Your **persona is derived from your token's roles**, through a per-engagement role-map (first matching role wins, with a configured default). The switcher becomes a read-only chip — you cannot impersonate another persona.
- **Admin actions need an admin role** in your token (default role name `substrate-admin`, configurable per engagement). Asserting the old admin header does nothing — header trust is dead in this mode and is logged as a warning if attempted.
- Every action you take is recorded against your stable user id from the token, so originator-only and owner-only rules genuinely bind to *you*.

For machine callers (agents) on `/mcp`: an unauthenticated call gets **401**. Agents authenticate with client-credential tokens; the principal identity is taken from a configured token claim (default `sub`, fallback `azp`) and matched against the principal id registered on the agent registration or contract party.

Role mapping is configuration, never code. The keys an operator sets per engagement (full detail in `substrate/docs/auth.md`):

| Key (under `substrate.auth.oidc.`) | Purpose |
|---|---|
| `issuer-uri` | The customer IdP's issuer URL (discovery endpoint) |
| `audience` | Optional audience pin on accepted tokens |
| `claims.roles` | Dot-path to the roles claim in the customer's tokens (Keycloak: `realm_access.roles`) |
| `claims.display-name` | Claim used for the display name in the identity chip |
| `persona.role-map` / `persona.default-level` | Ordered role → persona projection; fallback persona |
| `admin-roles` | Role name(s) that unlock admin actions (default `substrate-admin`) |
| `mcp.principal-claim` | Token claim used as the MCP principal id (default `sub`) |

One transport quirk worth knowing: the chat stream uses browser Server-Sent Events, which cannot send an auth header. On the stream path only, the app accepts the standard `access_token` query parameter instead. Use short-lived tokens; this is the RFC-specified bearer fallback.

### Observability

Everything is built in, and **everything that exports data is off by default**. With default configuration a Substrate process writes structured JSON logs to stdout and keeps metrics in memory; nothing leaves the process until an engagement explicitly points it at the *customer's* collector.

#### Logs

- Structured JSON to stdout, one object per line. Every line carries a `requestId` — the same UUID returned to API clients in the `X-Request-Id` response header and in every response envelope's `meta.requestId`. Given any API response, you can find its exact log lines.
- Where the stdout stream lands depends on the deploy shape: the terminal that launched `gradlew :app:bootRun` (local dev), `docker compose logs substrate-app` (Compose), or the engagement's CloudWatch log group (AWS — `aws logs tail <log-group> --since 10m`).
- A `demo` Spring profile switches the console to human-readable plain text for live walkthroughs.
- When tracing is enabled, log lines additionally carry `traceId`/`spanId`, so logs and traces cross-reference each other.

#### Metrics

All engine metrics are recorded under the `substrate_` prefix and tagged with the engagement id. The inventory that matters to an operator:

| Metric | What it counts |
|---|---|
| `substrate_contract_gateway_evaluations` | Every enforcement-gateway evaluation, tagged by decision (`allow`, `allow_with_flag`, `deny`, `would_deny`, `allow_unevaluated`) and enforcement mode |
| `substrate_agent_gateway_denials` | Agent-call denials at the gateway, tagged by reason — **the number to watch while running shadow mode** |
| `substrate_contract_monitor_runs` | Each automated contract-monitor sweep, by outcome (`compliant`, `breach_detected`, `indeterminate`, `not_due`, `error`, …) |
| `substrate_contract_breaches_opened` | Newly opened breaches (re-confirmations of an already-open breach do not count) |
| `substrate_cascade_actions` | Cascade actions by type (`issue`, `cascade`, `absorb`, `decline`, `recascade`, resolve variants). **Decline is a legitimate action — never alert on it as an error** |
| `substrate_agent_verbs` | Agent lifecycle verbs (deployed, paused, resumed, killed, promoted, …) |
| `substrate_diagnostic_runs` | Diagnostic run completions by status (`completed`, `completed_with_errors`, `zero_yield`, `failed`, `cancelled`) |
| `substrate_diagnostic_findings_committed` / `substrate_diagnostic_candidates_filtered` | Per-run finding totals — filtered vastly exceeding committed signals a grounding gap |
| `substrate_llm_calls` / `substrate_llm_tokens` / `substrate_llm_cost_estimated` | AI usage and estimated spend, tagged by model |

Standard JVM, HTTP-latency, and connection-pool meters ship alongside, all carrying the same engagement tag.

#### Pointing at a customer stack

The customer runs (or already has) an OpenTelemetry-compatible collector inside their own environment. The entire integration is two environment variables:

```
SUBSTRATE_OBSERVABILITY_ENABLED=true
SUBSTRATE_OBSERVABILITY_OTLP_ENDPOINT=http://otel-collector.internal:4318
```

Metrics push every 60 s (configurable) and traces export with 10 % sampling by default; tracing can be pinned on/off independently of metrics. Collector auth headers, when required, are supplied as environment variables — secrets never land in configuration files. There is no Meridian-hosted telemetry endpoint in any configuration: one engagement, one deploy, one observability tail, all inside the customer's environment.

#### Actuator endpoints per mode

| Mode | `/actuator/health`, `/actuator/info` | `/actuator/metrics` | Everything else |
|---|---|---|---|
| `anonymous` | Public (health checks depend on this) | **404** — not exposed | 404 |
| `anonymous` + `substrate.observability.actuator.expose=true` | Public | Public — local debugging only, never a customer build | 404 |
| `oidc` | Public | 401 without a token, 403 without an admin role, 200 with one | 404 |

Production metrics are *pushed* (OTLP); the actuator metrics endpoint is an admin convenience, not the export path.

### Deployment in one page

Same application image, three shapes:

| Shape | Where | Identity provider | Data allowed |
|---|---|---|---|
| **Local dev** | `gradlew :app:bootRun` (zero dependencies: in-memory H2, anonymous auth) or `docker compose up` (Postgres + Keycloak) | None / Keycloak container | Fixtures only |
| **Meridian-hosted testing** | Meridian's own AWS account, fixture engagements, only to validate deploy tooling | Keycloak (throwaway) | Fixtures only — never customer data |
| **Customer production** | The customer's AWS account — their VPC, their IAM, their data residency | The customer's IdP | The engagement's data; it never leaves their account |

Local setup (both shapes, plus the ingestion CLI walkthrough) is in `substrate/docs/dev-setup.md`. The full per-engagement AWS runbook — customer-handoff prerequisites, Terraform state bootstrap, digest-pinned image flow, the two-phase AI-assist key enablement, day-2 operations (password rotation, image rollback with its forward-only-migration caveat), and the gated teardown sequence — is `substrate/docs/deploy.md`. Do not work from memory on a customer deploy; follow the runbook's smoke checklist ("apply exited 0" is not "product worked").

### Configuration reference

The `substrate.*` keys an operator actually touches. All are environment-overridable; structured keys use standard Spring relaxed binding.

| Key | Default | What it does |
|---|---|---|
| `substrate.auth.mode` | `anonymous` | The auth mode ladder described above. Set `oidc` for any real deployment |
| `substrate.engagement.id` | `dev-engagement` | Engagement identifier — stamps every metric and trace |
| `substrate.demo.seed` | `false` | Seeds the fictional demo dataset at startup. Demo runs only — **never in a customer build** |
| `substrate.demo.contracts.activate` | `false` | Demo lever: records fixture acceptances so a seeded contract starts in the active state (monitor demos). Only effective with `demo.seed=true` |
| `substrate.demo.contracts.guards` | `false` | Demo lever: seeds a synthetic guard-bearing contract to demonstrate enforcement denials. Never in a customer build |
| `substrate.demo.fixed-clock` | unset | Pins the engine clock to a fixed instant for deterministic demos. Never in a customer build |
| `substrate.transformation.fixture-enabled` | `false` | Serves the fictional transformation-plan mock-up behind the Roadmap view. Demo runs only |
| `substrate.contracts.enforcement.mode` | `shadow` | The enforcement ladder: `off` (gateway skipped), `shadow` (full evaluation, would-be denials logged, nothing blocked), `enforce` (denials block). Ship `shadow`; flip to `enforce` after a clean observation window |
| `substrate.contracts.monitor.enabled` / `monitor.sweep-interval` | `true` / 60 s | The automated contract-monitor sweep |
| `substrate.contracts.mcp.enabled` | `true` | Mounts the MCP server at `/mcp` |
| `substrate.cascade.default-response-sla-days` | `7` | Days before a cascade level with no response is flagged overdue (per-cascade override; `0` disables). Notification-only — never a forced state change |
| `substrate.cascade.overdue-renotify-days` / `overdue-renotify-limit` | `3` / `5` | Re-notification cadence and cap for overdue cascade levels |
| `substrate.cascade.sweep-cron` | hourly | When the overdue sweep runs |
| `substrate.orchestrator.enabled` / `orchestrator.base-url` | `false` / empty | Connects the optional orchestrator sibling product. Off ⇒ AI cascade-draft suggestions return 404; everything else unaffected |
| `substrate.llm.provider` | `anthropic` | `mock` gives deterministic no-network behavior for tests and the ingestion CLI |
| `substrate.llm.anthropic.api-key` | empty (env `ANTHROPIC_API_KEY`) | Blank ⇒ degraded mode: the app boots and runs fine, AI-backed calls return a structured 503 (see Troubleshooting) |
| `substrate.llm.anthropic.model` | `claude-opus-4-8` (env `SUBSTRATE_LLM_MODEL` overrides) | The model id. Not hardcoded in code — the shipped configuration supplies the default; if explicitly blanked, AI calls fail loudly at call time, never at boot |
| `substrate.observability.enabled` / `otlp.endpoint` | `false` / localhost | Telemetry export master switch + collector base URL |
| `substrate.decision-log.verify-on-boot` | `true` | Re-verifies decision-log hash chains at startup |

---

## API & MCP reference

### The response envelope

Every REST response (except the chat event stream) is wrapped in one envelope: `{data, meta, errors}`. A real response from a running instance:

```json
{
  "data": { "timestamp": "2026-06-12T14:02:08.361Z", "status": "UP" },
  "meta": {
    "timestamp": "2026-06-12T14:02:08.362Z",
    "requestId": "28aab34b-69b7-45e7-8fa2-5a0280d090f7",
    "version": "0.1.0",
    "personaLevel": "L3_ANALYST"
  },
  "errors": []
}
```

- `data` — the payload; `null` on errors, and on *conflict* errors it can instead carry the **current state of the resource** so a client can refresh without a second call.
- `meta.requestId` — matches the `X-Request-Id` response header and the `requestId` field of every server log line for this request. Quote it when reporting a problem.
- `meta.personaLevel` — who the platform believed you were.
- `errors` — empty on success; otherwise structured `{code, message, field}` entries. Stack traces are never exposed.

Conventions that hold across all engines:

- **`domainId` is required** on engine list/read endpoints (cascades, contracts, agents, diagnostics, decision log). Data is partitioned per domain; there is no cross-domain list.
- **Every state-changing request body requires an `idempotencyKey`** (any client-chosen string, ≤128 chars). Replaying the same key with the same action returns the original result; replaying it with a *different* action is rejected as a conflict — a silent dropped command never masquerades as success.
- Optional **optimistic-concurrency fields** (`expectedChainSeq` on cascade commands, `expectedEventSeq` on agent verbs) let a client say "apply this only if nothing moved since I looked". A mismatch returns a 409 *with the current detail in `data`* — refresh and re-decide.
- **Declines, cancels, pause and kill are never errors.** Declining a cascade level or contract, cancelling a diagnostic run, pausing or killing an agent each return 200 — they are legitimate governance outcomes, not failures.

### Endpoint map

Method + path + purpose, grouped by engine. Paths are under `http://<host>/api/v1`. A sampling of the read endpoints below was verified against a live instance.

**Cascade engine** — decisions cascading through portfolio levels:

| Method & path | Purpose |
|---|---|
| `POST /cascade-drafts` | Create a draft cascade (operator-grade; there is no authoring UI yet) |
| `PATCH /cascade-drafts/{id}` · `GET /cascade-drafts/{id}` · `POST /cascade-drafts/{id}/discard` | Edit / read / discard a draft |
| `POST /cascade-drafts/{id}/issue` | Issue the draft as a live cascade |
| `POST /cascade-drafts/{id}/suggest` | AI-assisted draft suggestion — 404 unless the orchestrator integration is enabled |
| `GET /cascades?domainId=…` | List cascades (filters: state, signal, owner, affected domain) |
| `GET /cascades/{id}` · `GET /cascades/{id}/actions` | Detail; append-only action timeline |
| `GET /cascades/attention?domainId=…` | Whose action is demanded right now |
| `GET /cascades/stats?domainId=…` | Dashboard statistics (engine-computed) |
| `GET /cascades/notifications?recipientId=…` · `POST /cascades/notifications/{id}/read` | In-app notification feed; mark read |
| `POST /cascades/{id}/levels/{depth}/cascade` \| `absorb` \| `decline` \| `recascade` | The four level responses |
| `POST /cascades/{id}/resolve` | Originator resolution (looks-good or declined) |

**Coordination contracts engine** — cross-subsidiary commitments with monitoring and enforcement:

| Method & path | Purpose |
|---|---|
| `GET /contracts?domainId=…` | Contract cards (filters: status, type) |
| `GET /contracts/signals?domainId=…` | Aggregate signals: pending invitations, open breaches, monitor coverage |
| `GET /contracts/{idOrKey}` | Full detail: parties, commitments + compliance, enforcement text verbatim, decline triggers, breach summary |
| `GET /contracts/{idOrKey}/events` \| `breaches` \| `decisions` \| `monitor-runs` \| `attestations` | Audit timelines and enforcement-decision history |
| `POST /contracts/{idOrKey}/respond` | A party's response (accept / decline) to a proposed contract |
| `POST /contracts/{idOrKey}/decline` | Invoke an armed decline trigger on an active contract — returns 200, it is a first-class outcome |
| `POST /contracts/{idOrKey}/metric-samples` | Report a metric sample against a monitored commitment |
| `POST /commitments/{commitmentId}/attest` | Record a compliance attestation |
| `POST /breaches/{breachId}/acknowledge` \| `remediate` \| `waive` | Breach workflow; **waive is human-only** in every mode |
| `POST /contracts/{idOrKey}/monitors/{monitorId}/run-now` | Force an immediate monitor evaluation |

**Agent registry** — the inventory and control surface for AI agents operating in the estate:

| Method & path | Purpose |
|---|---|
| `POST /agents` | Deploy (register) an agent — the deploy gate rejects missing provenance/oversight with per-field codes |
| `GET /agents?domainId=…` · `GET /agents/{idOrKey}` | Fleet rows; full detail incl. mandate, gates, oversight chain |
| `GET /agents/signals` \| `attention` \| `feed` \| `notifications` (`?domainId=…`) | Fleet health aggregates, attention strip, activity feed |
| `GET /agents/{idOrKey}/events` \| `sightings` \| `gates` \| `metrics` | Per-agent timelines |
| `POST /agents/{idOrKey}/pause` \| `resume` \| `kill` \| `retire` | Control verbs — always 200, with an honest enforcement disclosure (see MCP section below) |
| `POST /agents/{idOrKey}/throttle` · `DELETE /agents/{idOrKey}/throttle/{kind}` | Set / clear rate-and-budget throttles |
| `POST /agents/{idOrKey}/promote/request` · `POST /agents/{idOrKey}/promote` | Shadow → live promotion (review-gated) |
| `POST /agents/{idOrKey}/reviews` · `POST /agents/reviews/{id}/decide` | Open / decide a promotion review |
| `POST /agents/{idOrKey}/interventions` · `POST /agents/interventions/{id}/acknowledge` \| `resolve` | Human intervention workflow |
| `POST /agents/{idOrKey}/oversight` · `attest-liveness` · `confirm-control` · `principal` (+ `DELETE`) | Oversight chain, liveness attestation, human control confirmation, MCP principal binding |

All lifecycle verbs are REST-only and human-only — agents cannot administer themselves or each other over MCP.

**Diagnostic engine** — AI-assisted findings with a human curation gate:

| Method & path | Purpose |
|---|---|
| `POST /diagnostics/runs` | Start a diagnostic run for a domain (one active run per domain) |
| `GET /diagnostics/runs?domainId=…` · `GET /diagnostics/runs/{id}` | Run history; run detail with batch outcomes |
| `POST /diagnostics/runs/{id}/resume` \| `cancel` | Resume an interrupted run; cancel (200, never error-colored) |
| `GET /findings/{id}/provenance` | The full evidence walk for one finding — every hop from finding to source document |
| `GET /diagnostics/verify?domainId=…` | Chain-integrity sweep over every AI-generated finding in the domain |
| `POST /findings/{id}/accept` \| `reject` | Human curation; reject **requires a rationale** |
| `POST /ai/generative/commit` \| `reject` | Materialize or reject an AI-drafted assertion — commit re-checks that the cited evidence still resolves |

**Decision log** — append-only, hash-chained record of design decisions:

| Method & path | Purpose |
|---|---|
| `GET /decision-log?domainId=…` | The domain's decision chain in order |
| `GET /decision-log/verify?domainId=…` | Recompute and verify the hash chain (see Troubleshooting) |
| `POST /design/decisions` · `GET /design/decisions` · `/design/decisions/{id}` | Record / browse decisions |
| `POST /design/decisions/{id}/approve` \| `reject` · `POST /design/impact-analysis` | Decision workflow; impact analysis |

**Cross-cutting:**

| Method & path | Purpose |
|---|---|
| `GET /health` · `GET /info` | Liveness and build info (also `/actuator/health` for infrastructure checks) |
| `POST /chat` · `GET /chat/stream` | AI assistant — sync, and Server-Sent-Events streaming (the one envelope-exempt endpoint) |
| `GET /domains` | The engagement's domains (subsidiaries) |
| `GET /ecosystem/graph?domainId=…` · `/ecosystem/findings` \| `overlaps` \| `manual-dependencies` | The living-canvas data: systems, processes, links |
| `GET /portfolio/subsidiaries/{domainId}` | Subsidiary dashboard aggregate |
| `GET /transformation/plan` | Transformation plan — 404 unless the demo fixture flag is on (no plan engine yet) |
| `GET /llm/cost-summary` | In-process AI token/cost aggregate since startup |
| `GET/POST /branding/configs` · `POST /branding/configs/{id}/activate` · `POST /branding/assets` | White-label branding (admin-gated) |
| Capabilities / processes / systems / organization | Conventional CRUD reads under `/capabilities`, `/processes`, `/systems`, `/organization/*` powering the canvas and designer |

Interactive API documentation is served at `/swagger-ui.html` on a running instance.

### Error code vocabulary

The structured codes a client will actually see, by family. Conflict responses (409) from cascade and agent commands carry the resource's **current detail** in `data` — refresh, don't retry blindly.

| Code | HTTP | Meaning / what to do |
|---|---|---|
| `VALIDATION_ERROR` | 400 | A request-body field failed validation; `errors[].field` names it |
| `BAD_REQUEST` | 400 | Malformed parameter or unparseable value |
| `NOT_FOUND` | 404 | Unknown id on a generic resource |
| `CONFLICT` | 409 | Concurrent modification (optimistic lock or database constraint) — refresh and retry |
| `INTERNAL_ERROR` | 500 | Unexpected server error; quote `meta.requestId` when reporting. Also currently returned when a **required query parameter is missing** (see Troubleshooting) |
| **Cascade** | | |
| `CASCADE_STALE_STATE` | 409 | Your `expectedChainSeq` is behind — the cascade moved on; current detail is in `data` |
| `CASCADE_ILLEGAL_TRANSITION` / `CASCADE_TERMINAL` | 409 | The command is not legal from the current state / the cascade is already resolved |
| `CASCADE_IDEMPOTENCY_REUSE` | 409 | Same idempotency key replayed with a different command |
| `CASCADE_NOT_LEVEL_OWNER` / `CASCADE_NOT_ORIGINATOR` | 403 | Only the level owner may respond / only the originator may resolve |
| **Contracts** | | |
| `CONTRACT_CONTENT_FROZEN` | 409 | Contract content cannot change after proposal |
| `INVALID_CONTRACT_STATE` | 409 | Command not valid for the contract's current status |
| `RESPONSE_CONFLICT` | 409 | A conflicting party response already exists |
| `CONTRACT_DENIAL` | 409 | **The enforcement gateway blocked the action** (enforce mode). The message names the contract, the clauses, and a decision id; the decision is recorded even though the action rolled back. A governance outcome, not a bug |
| `AGENT_GATE_DENIAL` | 409 | Same, but the agent-registry gate denied. The structured content carries the specific reason — e.g. `AGENT_CONTROL_SUSPENDED` (a paused agent attempted a governed call) or `AGENT_BUDGET_EXHAUSTED` (an `ACTIONS_PER_WINDOW` throttle budget is spent; calls are denied until the window resets) |
| `WAIVER_HUMAN_ONLY` | 403 | Breach waiver attempted by a non-human principal — waivers are human-only in every mode |
| **Agents** | | |
| `AGENT_NOT_FOUND` | 404 | Unknown agent id/key |
| `AGENT_KEY_CONFLICT` | 409 | An agent with this key already exists in the domain |
| `AGENT_STALE_STATE` / `AGENT_ILLEGAL_TRANSITION` / `AGENT_TERMINAL` | 409 | As the cascade family — current detail in `data` |
| `AGENT_IDEMPOTENCY_REUSE` | 409 | Same idempotency key, different verb |
| `PROMOTION_REVIEW_REQUIRED` | 409 | Promotion attempted without a decided review |
| `AGENT_PROVENANCE_*` family | 400 | Deploy gate: missing provenance/oversight fields, named per field |
| **Diagnostics** | | |
| `DIAGNOSTIC_RUN_ACTIVE` | 409 | A run is already active for this domain |
| `DRAFT_GROUNDING_LOST` | 409 | The draft's cited evidence no longer resolves — it is never silently materialized |
| `CURATION_CONFLICT` | 409 | Curation verb against an illegal state, or idempotency key replayed with a different action |
| `RATIONALE_REQUIRED` | 400 | A reject was submitted without a rationale |
| **AI availability** | | |
| `LLM_UNAVAILABLE` | 503 | No API key configured / provider unreachable — the app itself is healthy (see Troubleshooting) |
| `LLM_REFUSAL` | 422 | The model declined to answer |
| `RATE_LIMITED` | 429 | Provider rate limit; back off and retry |

### MCP server

Substrate mounts **one MCP server** (`substrate-contracts`) at `POST /mcp` (streamable HTTP transport), enabled by default. It carries the contracts tools *and* the agent tools — one server, one principal model. This is the surface AI agents use to read their obligations and report compliance.

**How callers identify themselves**

- *Anonymous mode:* the `X-MCP-Principal` header, trusted on the basis that the deployment is local. Self-asserted and disclosed as such.
- *OIDC mode:* `/mcp` requires a bearer token like every API route (401 otherwise). The principal id is read from the configured token claim (default `sub`, fallback `azp`) and matched against the principal id registered on an agent registration or contract party. The Compose dev realm ships a `substrate-agent` client-credentials client for this.

**Read tools** (ungoverned — no gateway evaluation):

| Tool | Purpose |
|---|---|
| `list_contracts` / `get_contract` / `get_commitments` | Contract cards, full detail (enforcement text verbatim), commitment rows with compliance state |
| `get_obligations_for_principal` | What the *calling* party owes right now: open commitments, due dates, missing attestations |
| `get_breaches` / `get_enforcement_decisions` | Breach rows; the gateway's decision history |
| `check_action` | Advisory pre-check: "would this action be allowed?" — lets an agent ask before acting |
| `list_agents` / `get_agent` | Agent fleet rows; full agent detail |
| `get_my_registration` / `get_my_control_state` | The caller's own mandate, gates and standing; its current mode, budgets and schedule window — the self-limiting reads a well-behaved agent polls |

**Governed write tools** (every call transits the enforcement gateway):

| Tool | Purpose |
|---|---|
| `attest_commitment` / `record_commitment_progress` | Compliance attestations and progress notes |
| `report_metric` | Metric sample against a monitored commitment — hard-requires the caller to be the bound party |
| `respond_to_contract` / `invoke_decline` | Party response to a proposal; invoke an armed decline trigger (a decline returns a *success* result — it is never error-shaped) |
| `acknowledge_breach` / `mark_breach_remediated` | Breach workflow |
| `waive_breach` | Always denied for agent principals — waiver is human-only by a built-in, non-removable guard |
| `agent_heartbeat` / `report_agent_activity` / `acknowledge_control` | Agent runtime liveness, activity reporting, and acknowledgment of a pause/kill control request |

Tool failures come back as `isError: true` tool *results* with structured content (`{code, message, …}`; denials carry `CONTRACT_DENIAL` with contract keys, clause ids and a decision id) — never MCP protocol errors, so agent runtimes can adapt rather than crash. Writes without a principal identity are rejected with `PRINCIPAL_REQUIRED`. One disclosed limitation: apart from `report_metric`, the compliance write tools do not verify that the caller is the bound party of the target commitment — every write is attributed to the verified principal in the audit trail, but attribution is not authorization. This is a known, deliberately disclosed gap pending a product decision.

**Pause/kill honesty — the enforcement disclosure**

When an operator pauses or kills an agent (REST verbs), the platform tells you — before and after — exactly what that act physically does, in the verb response's `enforcement` block:

```
{ "status": …, "enforcement": { "effect", "confirmationState", "disclosure" }, "agent": … }
```

- `effect: "gateway-block"` — the registry flip *is* the control plane: the agent's next governed MCP call is denied at the gateway. Claimed **only** when the agent executes through Substrate's MCP surface *and* the enforcement mode is `enforce`.
- `effect: "recorded-intent"` — Substrate cannot (or, in `shadow`/`off` mode, does not) stop the runtime itself. The intent is recorded, the oversight chain is notified, and acknowledgment/confirmation are tracked (`confirmationState`: `pending` → `acknowledged` when the runtime acks → `confirmed` when a human verifies; absence of sightings never auto-confirms). This is what you get for agents running in orchestrator or customer systems, and for *all* agents while the enforcement mode is `shadow` or `off`.

The `disclosure` string spells out the active case in plain language (e.g. *"registry flip recorded; the shared enforcement ladder is in SHADOW: this agent's governed calls are rowed would-deny … — enforce mode makes this a hard gateway block"*). Substrate never claims to have stopped something it did not stop; read the disclosure, not just the 200.

---

## Troubleshooting & FAQ

**Dashboards show zeros / blanks right after startup.**
Demo seeding runs *after* the HTTP port opens, so for roughly the first 2–4 seconds the API answers but the demo data is still being written. Wait a moment and refresh. If stats stay empty, check the launch flags: the demo dataset only seeds with `--substrate.demo.seed=true`, and seeded contracts only start active with `--substrate.demo.contracts.activate=true`.

**A GET returns 500 `INTERNAL_ERROR` and the message says nothing useful.**
Check the **required `domainId` query parameter** first. Engine list/read endpoints (`/cascades`, `/contracts`, `/agents` signals, `/diagnostics/runs`, `/decision-log/verify`, `/ecosystem/graph`, …) require `?domainId=<uuid>`; a missing required parameter currently surfaces as a generic 500 rather than a 400. Get a valid id from `GET /api/v1/domains`. (Verified live: the same endpoints answer 200 the moment the parameter is supplied.)

**Chat answers 503 `LLM_UNAVAILABLE`.**
No `ANTHROPIC_API_KEY` is configured (or the provider is unreachable). This is the designed degraded mode: the application is healthy and every non-AI feature works; only AI-backed calls (chat, AI diagnostics, generative drafting) return this structured 503. Supply the key (locally: set the env var and restart; AWS: the two-phase AI-assist enablement in the deploy runbook). For pipeline testing without a key, the deterministic mock provider (`--substrate.llm.provider=mock`) exercises the full mechanics with no network call.

**The Roadmap view is empty.**
Expected. The transformation Roadmap is backed by `GET /api/v1/transformation/plan`, which returns 404 unless the demo fixture flag (`substrate.transformation.fixture-enabled`) is on — there is no plan engine yet, only a fictional demo mock-up. The UI shows an empty state rather than an error. The flag must stay off in any customer build.

**The Review / Decline buttons on a contract do nothing.**
Intentional in this release: the contract detail page renders the affordances but they are inert. Party responses and declines are performed through the API (`POST /contracts/{idOrKey}/respond`, `POST /contracts/{idOrKey}/decline`) or the MCP tools. The detail page itself is fully live (see `../acceptance/final/07-contract-detail.png`).

**Where do I author a cascade? I don't see a button.**
There is no cascade authoring UI yet. Cascades are created through the operator-grade write API (`POST /api/v1/cascade-drafts` → edit → `…/issue`); the UI renders issued cascades, their timelines, and attention signals read-only.

**I paused an agent — is it actually stopped?**
Read the `disclosure` in the verb response (see the MCP section above). `gateway-block` means its next governed call is denied; `recorded-intent` means Substrate recorded and notified but cannot itself halt the runtime — track `confirmationState` until the runtime acknowledges or a human confirms. Note also that the portfolio Operations tab is read-only: pause/kill are API verbs, not UI buttons, in this release.

**Port already in use / nothing on the expected port.**
The backend defaults to **8080** (`server.port`; override with `--server.port=NNNN` or `SERVER_PORT`). The Vite dev server runs on **5173** and proxies `/api` to 8080. The Compose stack adds Postgres on 5432 and Keycloak on 8081. Demo instances are often started on an alternate port (e.g. 8090) precisely to avoid colliding with a dev backend — check the launch command before assuming a crash.

**My data disappeared after a restart.**
In the zero-dependency dev shape the application database is **in-memory H2** — every restart is a clean slate (plus the few seconds of re-seeding, if enabled). This is by design for development. Two contrasts: the **ingestion CLI** uses a *file-based* H2 under `substrate/data/`, so committed ingestion entities survive restarts (delete that directory to reset); and the Compose/customer shapes run Postgres, where nothing resets.

**Is the demo data real?**
No. Everything seeded by the demo pack is fictional — the engagement identity ("Meridian Bancorp · Risk Consolidation"), the portfolio subsidiaries (Curasync Clinical Systems, RevSync Health Financial, Sentari Lab Informatics, Pulsewell Telehealth, ConstructLedger, …), and the ingestion sample corpus ("Glacier Peak Credit Union"). None of it may ship in a customer build: `substrate.demo.seed`, `substrate.demo.contracts.*`, `substrate.demo.fixed-clock`, and `substrate.transformation.fixture-enabled` all default off and must stay off. The seeder also refuses to write into non-demo domains by construction.

**Where are the logs?**
Stdout, as structured JSON. Local dev: the terminal running `gradlew :app:bootRun`. Compose: `docker compose logs substrate-app`. Customer AWS: the engagement's CloudWatch log group (`aws logs tail <log-group> --since 10m`). Correlate any API response to its log lines via `meta.requestId` / the `X-Request-Id` header.

**Something looks tampered with — how do I verify the audit chain?**
Decision-log entries are hash-chained per domain. Run:

```
GET /api/v1/decision-log/verify?domainId=<uuid>
```

A healthy chain answers like this (live output):

```json
{ "valid": true, "totalDecisions": 6, "verifiedLinks": 6,
  "firstBrokenAt": null, "message": "All 6 decisions verified; chain is intact." }
```

`firstBrokenAt` pinpoints the first broken link if verification fails. The same check runs automatically at every boot (`substrate.decision-log.verify-on-boot`, on by default) — a tampered chain is loud, not silent. For AI findings, `GET /api/v1/diagnostics/verify?domainId=<uuid>` sweeps every AI-generated finding's evidence chain and names any dangling hop, and `GET /api/v1/findings/{id}/provenance` walks a single finding hop-by-hop back to its source documents.

---

**Further reading:** `substrate/docs/auth.md` (full auth-mode reference), `substrate/docs/observability.md` (telemetry detail), `substrate/docs/deploy.md` (the AWS runbook), `substrate/docs/dev-setup.md` (local setup and the ingestion CLI), `substrate/docs/engine-spec/` (engine state vocabularies), and `substrate/docs/acceptance/final/visual-verification.md` (screenshot evidence of the shipped surfaces).
