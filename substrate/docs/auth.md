# Substrate auth — the Phase 5 mode ladder (Stage 1: resource server + identity; Stage 2: SPA wiring §9)

Binding inputs: production plan §4 (generic OIDC, customer brings the IdP, Keycloak is the
throwaway test IdP), port-map seam S9, ADR-001 D11 / ADR-002 D8 revisit hooks, agents.md §8.

## 1. Mode ladder — `substrate.auth.mode`

| mode | posture | identity source |
|---|---|---|
| `anonymous` (code default; dev/dev-shared profiles) | Phase 1–4 behavior **byte-for-byte**. `AuthModeEnvironmentPostProcessor` excludes ALL Spring Security auto-configuration, so the servlet pipeline is identical to before the oauth2-resource-server dependency existed — no filter chain, no CSRF, no security headers. | `AnonymousIdentitySource`: verbatim spoofable `X-Persona-*` headers (S9, accepted posture). MCP: `X-MCP-Principal` header (localhost trust). |
| `oidc` (postgres profile default via `SUBSTRATE_AUTH_MODE`) | Generic OIDC resource server (`OidcSecurityConfig`): `/api/**` + `/mcp` require a verified bearer JWT (401 otherwise); SPA shell, springdoc, actuator, `/api/v1/health` stay public. Stateless, CSRF off, CORS delegates to the existing MVC mappings. | `OidcIdentitySource`: the verified JWT. Persona/MCP header trust is DEAD — headers are ignored with a WARN log. |

Configuration is per-engagement, never code (`AuthProperties`, prefix `substrate.auth.oidc`):
`issuer-uri` (any conformant IdP — Okta, Entra ID, Auth0, customer-internal), `jwk-set-uri`
(direct JWKS fallback when discovery is unreachable), `audience` (optional `aud` containment
pin), `claims.roles` (dot-path, e.g. Keycloak `realm_access.roles`), `claims.display-name`,
`persona.role-map` (ordered role→persona-level projection, first match wins) +
`persona.default-level`, `admin-roles`, `mcp.principal-claim`, `sse.token-query-paths`.
Secrets never land in the repo — all values are env-refs (see `application-postgres.yml`).

## 2. SubstrateIdentity → PersonaContext projection

`PersonaInterceptor` resolves one `SubstrateIdentity` per `/api/**` request through the single
active `IdentitySource` and projects it onto the request-scoped `PersonaContext`
(level/id/label) plus the roles-aware `SubstrateIdentityContext`. **No PersonaContext consumer
changed** — controllers, actor mappers (`PersonaActors`, `CascadeController.currentActor`,
`AgentController.currentActor`), envelope meta and the governed-principal source all keep
reading PersonaContext exactly as before; the ladder swaps what feeds it.

## 3. Actor identity mapping (ADR-001 D11 landed; port-map S9 "IAM-compatible stable user id")

Under `oidc`, the projected persona id IS the token `sub`. Existing actor fields accept it
without schema change (verified — no additive columns):

| sink | column(s) | anonymous mode | oidc mode |
|---|---|---|---|
| Contract/agent events, attestations, metric samples | `actor_id`, `attested_by`, `reported_by`, `resolution_actor` — `VARCHAR(255)` | persona-header fallback chain (id → label → level), verbatim | token `sub` (≤255 chars; any conformant IdP fits) |
| Cascade actions / agent status events | `actor_member_id UUID` + `actor_label VARCHAR(255)` | `X-Persona-Id` when UUID-parseable, else label-only | `sub` when UUID-shaped (Keycloak subs are UUIDs) → stable member binding; non-UUID subs degrade to label-only actors (documented, not an error) |
| Hash-chain anchors (DecisionLogService via cascade/contract/agent mirrors) | canonical-JSON `metadata.actorMemberId` / `resolutionActor` | persona-derived | the OIDC `sub` — chain entries bind to IAM-stable identity; canonical-JSON layout untouched (pinned hash vectors unaffected) |
| Curation events (`ai_curation_events`) | `actor_label`, `actor_persona_id VARCHAR(255)` | header label / id | display-name claim / `sub` |

Re-verified under real identity (D11 revisit): originator-only/owner-only comparisons keep
working because the same user always presents the same `sub`, where before they depended on
clients re-sending the same spoofable header.

## 4. Admin gates

`BrandingAdminAuthorizer` (the only admin gate today; any future `@Governed` admin path should
use the same bean): anonymous mode keeps the documented `X-Persona-Level: ADMIN` check; oidc
mode consults REAL roles from the verified JWT — allow iff the caller holds one of
`substrate.auth.oidc.admin-roles` (default `substrate-admin`). `X-Persona-Level` spoofing dies
in oidc mode: ignored with a WARN, proven by test.

## 5. MCP principal binding (ADR-002 D8 / agents.md §8 landed)

Anonymous mode: `X-MCP-Principal` header (unchanged). Oidc mode: `/mcp` sits behind the
resource-server chain — **unauthenticated MCP → HTTP 401** before the servlet; the principal
carried to tool handlers is `mcp.principal-claim` (default `sub`, fallback `azp`) of the
verified client-credential JWT, and the existing agents.md §8.2 resolution ladder matches it
against `agent_registrations.mcp_principal_id` / `contract_parties.mcp_principal_id`.
Keycloak client-credentials: `sub` = service-account user id (stable UUID), `azp` = client id —
engagements register whichever they configure as the principal claim. The dev realm ships a
`substrate-agent` service client for this.

## 6. SSE / EventSource token transport

Browser `EventSource` cannot set headers. The configured stream paths (default
`/api/v1/chat/stream`) additionally accept the **RFC 6750 §2.3 `access_token` URI query
parameter** — the standard bearer fallback for header-less clients
(`SseTokenBearerTokenResolver`). Chosen over a cookie: RFC-specified, stateless, no CSRF
story. Cost (token can reach access logs) is confined to exactly these paths; use short-lived
access tokens.

## 7. Verification lanes (no Docker on the dev box; ZERO cloud provisioning)

- **JWT-mock suites** (run everywhere): `spring-security-test` `jwt()` post-processors +
  `@MockitoBean JwtDecoder` — `app/src/test/.../auth/Oidc*Test`, plus
  `AnonymousModeSecurityAbsenceTest` pinning the security-free anonymous pipeline.
- **Real-issuer round-trip** (CI / any Docker host): `KeycloakOidcRoundTripIT` —
  Testcontainers boots `quay.io/keycloak/keycloak` with
  `infra/keycloak/realm-substrate.json` imported, gets real password-grant tokens
  (`dev-admin` / `dev-coo`), and proves: API accepts the token, persona maps from realm roles,
  admin role honored, non-admin 403, no token 401. Self-disables without Docker
  (`@Testcontainers(disabledWithoutDocker = true)`) — same pattern as the Postgres ITs; rides
  the existing `./gradlew build` CI path.
- **Anonymous regression**: the entire pre-existing suite runs UNMODIFIED under the default
  mode.
- Local stack with a real IdP: `docker-compose up` (postgres profile, Keycloak at
  `http://keycloak:8080/realms/substrate`) — testing shape only; production is the customer's
  environment with their IdP (plan §3 — nothing is ever provisioned by us).

Dev users (throwaway, compose/IT only): `dev-admin`, `dev-operator`, `dev-exec`, `dev-coo`
(password = username); roles `substrate-admin|operator|exec|coo`.

## 8. Out of scope for Stage 1 (deliberate)

~~Frontend OIDC login flow~~ (landed in Stage 2 — §9),
method-level role annotations beyond the existing gates, multi-tenant issuer federation,
token-exchange for orchestrator dispatches. The contracts/agents gateway keeps its own
principal-type model unchanged.

## 9. Frontend wiring (Stage 2 — `frontend/src/auth/`)

Anonymous mode is a NO-OP: `AuthProvider` renders children synchronously under a constant
context, the axios interceptor keeps sending `X-Persona-Level` from localStorage, the
PersonaSwitcher dropdown is unchanged — pre-Phase-5 UX byte-for-byte (no provider even needs
to be mounted for components to behave). Oidc mode is additive:

- **Mode resolution** (`auth/config.ts`): Stage 1 did NOT expose the auth mode on
  `GET /api/v1/info`, so the SPA resolves it frontend-side — runtime-injected
  `window.__SUBSTRATE_AUTH__` (per-engagement config served next to the bundle; one artifact,
  no rebuild) → `VITE_SUBSTRATE_AUTH_MODE`/`VITE_OIDC_*` build env → `anonymous`. When a later
  backend stage adds the mode to `/api/v1/info`, `resolveAuthConfig()` is the single swap
  point. Misconfigured oidc (missing authority/clientId) fails safe to anonymous with a
  console warning; the backend still 401s if IT runs oidc.
- **Flow**: authorization code + PKCE via `oidc-client-ts` (public SPA client, no secret);
  tokens in sessionStorage; `automaticSilentRenew` for refresh; silent-renew failure or an API
  401 → interactive re-auth redirect; sign-out = RP-initiated logout redirect. Children are
  withheld until a verified user exists, so no API call ever fires unauthenticated.
- **Identity attachment** (`api/client.ts` + `auth/runtime.ts`): under oidc the request
  interceptor sends `Authorization: Bearer <access_token>` and the persona header is NOT sent
  (S9 severed — the backend would ignore it with a WARN anyway). SSE (`streamChat`) appends
  the RFC 6750 §2.3 `access_token` query parameter on the stream path (§6).
- **PersonaSwitcher**: under oidc the persona is claim-derived and read-only — an identity
  chip (display label `name`→`preferred_username`→`sub`, persona via the configured
  role-map mirror, sign-out button) replaces the dropdown. The frontend role-map
  (`window.__SUBSTRATE_AUTH__.oidc.roleMap` / `rolesClaimPath`) is a DISPLAY-ONLY mirror of
  `substrate.auth.oidc.persona.role-map`; the backend's projection from the verified token
  stays authoritative.
- **Tests**: `auth/__tests__` (config resolution, claim extraction, AuthProvider with a mocked
  UserManager), `api/__tests__/client.test.ts` (interceptor in both modes, 401 behavior),
  `api/__tests__/chatStream.test.ts` (SSE token URL), `personas/__tests__` (mode rendering).
  No real IdP is contacted anywhere in the frontend suite.
