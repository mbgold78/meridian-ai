# Substrate admin guide (D4) — Day-0 workstream

The checklist-driven workstream for the **engagement administrator**: the
person who takes a deployed Substrate stack from "the jar boots" to "real
customer users sign in and the platform is accepted for go-live". One pass
through this document, top to bottom, is Day 0.

**What this guide deliberately is not.** It does not repeat the reference
documentation — it sequences it. Three documents own the details and are
cross-referenced throughout, never duplicated:

| Reference | Owns |
|---|---|
| [`auth.md`](../../auth.md) | The auth mode ladder, every `substrate.auth.oidc.*` key's semantics, identity→persona projection, MCP principal binding, SSE token transport, verification lanes |
| [`deploy.md`](../../deploy.md) | The per-engagement AWS deploy runbook: customer handoff, IAM/deploy-role, Terraform steps, how config (including the role map) rides the task definition |
| [`observability.md`](../../observability.md) | The `substrate.observability.*` keys, the `substrate_*` metric inventory, log↔trace correlation |

---

## 1. Day-0 readiness checklist

Work this table before scheduling the go-live session. Every row is a
yes/no; a "no" is a blocker you can name to the customer precisely.

| # | Check | How to verify | Detail lives in |
|---|---|---|---|
| R1 | **Network egress: `api.anthropic.com:443`** reachable from the app subnet — required only if the engagement buys AI assist (chat, impact analysis, drafting). Without it the platform runs; the AI surfaces show their documented error states instead of answers | From a host in the app subnet: `curl -sI https://api.anthropic.com` completes (any HTTP status — you are proving the path, not the key) | deploy.md step 0.8 / step 6 |
| R2 | **Network egress: OIDC issuer** reachable from the app subnet — token signing keys are discovered from `<issuer>/.well-known/openid-configuration` at startup | `curl -s <issuer>/.well-known/openid-configuration` returns the discovery JSON. Air-gapped from the issuer? Plan to set `jwk-set-uri` instead (it wins over discovery) | auth.md §1 |
| R3 | **Network path: OTLP collector** reachable from the app subnet (only if the engagement ships telemetry) | `curl -sI http://<collector>:4318` answers; collector auth headers agreed if required | observability.md §2 |
| R4 | **IAM: deploy role + ExternalId** in place, operator principal trusted | Customer returned the CloudFormation `RoleArn`; ExternalId exchanged out-of-band, exported in the operator env only | deploy.md step 0.2–0.3 |
| R5 | **IdP: admin role exists and is assigned** to at least one named human. If nobody holds the role mapped by `admin-roles`, the admin gates are unreachable — there is no break-glass | Customer IdP admin confirms the role/group exists and lists its members | §4 below; auth.md §4 |
| R6 | **IdP: roles claim path verified against a real decoded token**, not the IdP's documentation. Get one sample access token for a test user and read where the roles actually sit | Decode the JWT payload (any base64 tool) and locate the roles array — that exact dot-path becomes `claims.roles` in §3 | auth.md §1 |
| R7 | **Audience pin agreed.** Decide the expected `aud` value with the customer and set `audience`; a token minted for a different API must be rejected | Same decoded sample token: confirm the `aud` claim value | auth.md §1; AuthProperties `oidc.audience` |
| R8 | **SSE paths inventoried.** Browser `EventSource` cannot send headers, so the chat stream path accepts the RFC 6750 `access_token` query parameter — confined to exactly the configured paths. Confirm the default (`/api/v1/chat/stream`) is right for this engagement, and that no proxy/ALB in front buffers `text/event-stream` | Review `sse.token-query-paths`; check LB idle timeout vs stream lifetime | auth.md §6 |
| R9 | **Redirect URI registered** in the customer IdP for the SPA callback, and its path is on a route the app forwards | Customer IdP admin registers the agreed redirect URI | auth.md §9 (redirectUri caveat) |

## 2. OIDC integration — the keys you will set

All identity configuration is per-engagement config, never code
(`AuthProperties`, prefix `substrate.auth.oidc` — semantics in auth.md §1;
how the values ride the ECS task definition in deploy.md "Keycloak vs
customer IdP"). The complete claim-mapping key set:

| Key | What it does |
|---|---|
| `substrate.auth.mode` | `oidc` for any customer engagement (`anonymous` is the dev default) |
| `substrate.auth.oidc.issuer-uri` | The customer's issuer; `iss` is validated against it |
| `substrate.auth.oidc.jwk-set-uri` | Direct JWKS fallback when discovery is unreachable (wins over discovery) |
| `substrate.auth.oidc.audience` | Expected `aud` containment pin (R7) |
| `substrate.auth.oidc.claims.roles` | Dot-path to the roles claim in the access token (R6) |
| `substrate.auth.oidc.claims.display-name` | Claim carrying the human label; falls back `preferred_username` → `sub` |
| `substrate.auth.oidc.persona.role-map` | Ordered role → persona-level projection; **first matching entry wins** — put the most-privileged mappings first |
| `substrate.auth.oidc.persona.default-level` | Level when no mapped role matches |
| `substrate.auth.oidc.admin-roles` | Roles (post claim-mapping) that satisfy admin gates (§4) |
| `substrate.auth.oidc.mcp.principal-claim` | MCP principal id claim (`sub` default, `azp` for client-id registration) — auth.md §5 |
| `substrate.auth.oidc.sse.token-query-paths` | Exact GET paths accepting `?access_token=` (R8) |

## 3. Role-map worked examples

Two complete, real-shape variants. Role names are the example engagement's —
substitute the customer's. Persona levels on the right are the backend's
proven vocabulary: `ADMIN`, `EXECUTIVE`, `L3_ANALYST` (the same projection
the dev realm uses — see `application-postgres.yml`).

### Variant A — Microsoft Entra ID

App roles defined on the Substrate app registration (and assigned to users
or groups) arrive in the **`roles`** claim of the v2.0 access token, so the
claim path is simply `roles`:

```yaml
substrate:
  auth:
    mode: oidc
    oidc:
      issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
      audience: <application (client) ID of the Substrate app registration>
      claims:
        roles: roles                  # Entra app roles claim
        display-name: name
      persona:
        role-map:                     # ordered — first match wins
          Substrate.Admin: ADMIN
          Substrate.Executive: EXECUTIVE
          Substrate.Operator: L3_ANALYST
        default-level: L3_ANALYST
      admin-roles:
        - Substrate.Admin
```

### Variant B — Okta

Okta projects **groups**, not app roles: add a Groups claim (with a filter
such as `substrate-`) to the authorization server, and point the roles claim
path at it:

```yaml
substrate:
  auth:
    mode: oidc
    oidc:
      issuer-uri: https://<org>.okta.com/oauth2/default
      audience: api://default         # the authorization server's audience
      claims:
        roles: groups                 # Okta groups claim (per the claim filter)
        display-name: name
      persona:
        role-map:                     # ordered — first match wins
          substrate-admins: ADMIN
          substrate-executives: EXECUTIVE
          substrate-operators: L3_ANALYST
        default-level: L3_ANALYST
      admin-roles:
        - substrate-admins
```

Keycloak (the throwaway test IdP, never production) uses
`claims.roles: realm_access.roles` — the swap between all three is
configuration only, no rebuild (deploy.md "Keycloak vs customer IdP").

**How these values reach the task definition** (deploy.md owns the detail):
flat keys ride plain env vars via Spring relaxed binding
(`SUBSTRATE_AUTH_OIDC_CLAIMS_ROLES=groups`); the structured `role-map` rides
`SPRING_APPLICATION_JSON` — see `engagements/example/engagement.tfvars.example`.
Secrets never land in tfvars or the repo.

## 4. Persona / role mapping — decision table

Decide, with the customer, which of their roles project onto which persona
level. Persona changes presentation and identity attribution — it does
**not** change data visibility (the product's design; the guides state it
rather than imply otherwise).

| The user is… | Map their role to | What that grants |
|---|---|---|
| Platform administrator (branding, operational config) | `ADMIN` | Passes the admin gates (§5) **only if the role is also listed in `admin-roles`** — `ADMIN` as a persona level is a label; the gate checks roles, not levels |
| Portfolio / subsidiary executive — read-path consumer of dashboards, cascades, contracts ([COO guide](../coo/index.md)) | `EXECUTIVE` | Executive presentation; identity chip shows "Executive" |
| Engagement team: operators, architects, analysts ([operator guide](../operator/index.md)) | `L3_ANALYST` | Working-surface presentation (canvas, designer) |
| Anyone else (unmatched roles) | `persona.default-level` | Ship the default as `L3_ANALYST` for engagement-team-heavy tenants; tighten to `EXECUTIVE` where most users are client-side readers |

Ordering matters: the role map is evaluated **first match wins** in YAML
insertion order, and users commonly hold several groups — list the
most-privileged mappings first.

## 5. Admin-role gating

The admin gate consults **real roles from the verified JWT**: allow iff the
caller holds one of `admin-roles` (auth.md §4). Two facts worth stating to
the customer plainly:

- In oidc mode, `X-Persona-Level: ADMIN` header spoofing is dead — ignored
  with a WARN, proven by test.
- Actuator metrics endpoints are admin-role-gated under oidc; the
  anonymous-mode `substrate.observability.actuator.expose` switch is a
  dev-only lever that must remain off (§8).

Verify R5 before go-live: at least one real human in the admin role.

## 6. Observability hookup

Two values flip telemetry on (everything export-shaped is OFF by default;
nothing leaves the process until you set them — observability.md §1–2):

```bash
SUBSTRATE_OBSERVABILITY_ENABLED=true
SUBSTRATE_OBSERVABILITY_OTLP_ENDPOINT=http://<customer-collector>:4318
```

Collector auth headers, push interval, trace sampling, and the
`substrate_*` metric inventory: observability.md §2–3. Confirm R3 first; a
wrong endpoint fails quietly from the user's perspective (export errors land
in the app log, not the UI).

## 7. Go-live acceptance checklist

Run on the production stack, in order, with the customer watching. Each row
names its observable end state — if you cannot observe it, the row failed.

| # | Check | Command / action | You'll know it worked when… |
|---|---|---|---|
| G1 | **Endpoints answer** | `curl -s https://<fqdn>/api/v1/health` | …health returns 200/UP, **and** an unauthenticated `curl -si https://<fqdn>/api/v1/domains` returns **401** — proving the resource server is on. A 200 here means auth mode is misconfigured: stop |
| G2 | **OIDC round-trip with a real user** | A real customer user (not a test realm) signs in through the SPA | …the IdP login completes, the user lands back on the page they asked for, the **identity chip** (top right) shows their display name + projected persona, and a data surface (e.g. `/portfolio`) renders live data. Sign-out returns to the IdP |
| G3 | **Admin gate honored both ways** | The R5 admin user and a non-admin user each exercise an admin endpoint | …admin user passes; non-admin gets **403** (not 401 — they are authenticated, just not authorized) |
| G4 | **Decision-log verify passes** | `GET /api/v1/decision-log/verify?domainId=<id>` with a bearer token (the one-click UI equivalent: canvas drawer → Log → Verify chain) | …`valid: true`, `verifiedLinks == totalDecisions`, `firstBrokenAt: null` ([operator task 7](../operator/07-decision-log.md) owns interpreting anything else) |
| G5 | **Ingestion dry-run commits** | The engagement operator runs the corpus-ingest task ([operator task 2](../operator/02-corpus-ingest.md)) against a scratch domain with a small real-corpus sample | …the commit step reports `entitiesCommitted > 0`, and replaying the same commit reports 0 (idempotent replay — the task's worked-when state) |

## 8. Demo flags — the must-stay-off list

This is the **only** place demo levers appear in this guide. Every key below
ships off/unset; any of them set in a customer engagement build is a
misconfiguration, full stop.

| Key | Must be | What it would do if set |
|---|---|---|
| `substrate.demo.seed` | `false` (default) | Seeds the fictional demo engagement into the database |
| `substrate.demo.contracts.activate` | `false` (default) | Records fixture acceptances to flip the demo contract ACTIVE |
| `substrate.demo.contracts.guards` | `false` (default) | Seeds the synthetic guard-bearing demo contract |
| `substrate.demo.fixed-clock` | **unset** | Pins the engine clock to a fixed instant — every time-dependent computation freezes |
| `substrate.transformation.fixture-enabled` | `false` (default) | Serves the fictional transformation-plan mockup on the Plan tab |
| `substrate.observability.actuator.expose` | `false` (default) | Exposes unauthenticated actuator metrics (anonymous-mode dev lever; under oidc the admin-role gate applies instead) |

## 9. Identity-model caveat — what the screenshots show

Every screenshot in this documentation suite is captured against the **dev
identity model** (anonymous mode): the top-right control is the persona
*switcher* dropdown, and persona rides spoofable headers by design in that
mode. Under OIDC — every customer engagement — that control is replaced by
the read-only **identity chip**: verified display name, claim-derived
persona, sign-out button. Same layout position, different control; nothing
else on any documented surface changes with the identity model.

The chip's own capture is deliberately **not** part of the docs gate
(ratified decision D-C — it needs a real IdP login, which is flake surface
the gate excludes). Its capture spec is pre-written and marked
`status: manual-job`:
`substrate/docs/pipeline/specs/oidc-identity-chip.yaml` — executed by the
manually-triggered CI job against the Compose/Keycloak stack, never by
`docsVerify`.
