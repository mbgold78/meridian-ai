# Final Visual Verification — Full-Surface Tour + Deferred C8 Acceptance

- **Date:** 2026-06-11 (15:48–16:00 ET)
- **Build under test:** commits `3ec4e87..15d368d`; fresh `npm run build` (vite 5.4.21, 7118 modules) + `:app:bootJar` — jar asset hashes verified identical to the fresh vite output (`index-WiKXVnwi.js` et al.).
- **Boot:** `java -jar app-0.1.0.jar --spring.profiles.active=dev --substrate.demo.seed=true --substrate.demo.contracts.activate=true --server.port=8080`. Started in 14.1s; all 14 seeders ran clean (1 demo domain `3a3a81f0…`, 22 satellite domains, 70 agents, 2 contracts with `co-tribal-knowledge-depopulation` ACTIVE, 3 cascades, 12 systems, 26 processes).
- **Viewport:** 1536x960, Playwright (Chromium).
- **App log over the whole tour:** 0 ERRORs. WARNs only: Flyway/H2 version advisory, `SdkAnthropicClient DISABLED` (no API key — known), transformation-plan 404s (known S4 flag), LLM_UNAVAILABLE 503s from the deliberate chat probes.

> **Provenance note (added 2026-06-12, DOC-1 S3 — the only later edit to this record, besides re-pointing the screenshot paths below into `archive/`).** The screenshots cited in this tour were hand-captured **without a fixed engine clock** and now live in [`archive/`](archive/) (see `archive/README.md`). The same scenes are regenerated deterministically by the docs capture pipeline (`gradlew docsCapture` → `substrate/docs/pipeline/build/captures/`) under the canonical pinned clock `2026-05-12T00:00:00Z`, so the regenerated PNGs intentionally do not pixel-match these originals. Finding 1's blank-page scene (`archive/08a-operations-path-segment-BLANK.png`) is irreproducible — the missing catch-all route has since been fixed — and the pipeline captures the fixed NotFound panel at the same URL instead (`../../pipeline/build/captures/08a-operations-path-segment-notfound.png`). Scene-level equivalence between originals and regenerated captures is recorded in `../doc-1/s3-migration/scene-equivalence.md`.

## Surface-by-surface results

| # | Surface | State | Console errors | Screenshot |
|---|---------|-------|----------------|------------|
| a | `/` LivingCanvas | **PASS — deferred C8 check closed.** Accessibility snapshot contains 12 system nodes (Archer GRC, Piedmont Risk Register, LossDB, IssuesPortal, KRI Hub, Scenario Workbench, NPA System, Vendor Risk Platform, Change Risk Tool, Change Log, AuditTrack, Risk Reporting Hub), 26 manual-process nodes (MP-001..MP-026, HIGH RISK badges, 🧠 markers), 18 edge groups — real seeded nodes, not just chrome. Stats strip 12/26/21/$945K. Today/Target/Waves, DNA, drawer rail, legend, AmbientChatBar all present. | 2x known transformation-plan 404 only | `archive/01-living-canvas.png` |
| b | `/designer` | PASS. Decision table renders 5 seeded decisions (D-001..D-005, correct type/status chips, Run Impact Analysis on the two PROPOSED rows) + the `contract:co-tribal-knowledge-depopulation:CONTRACT_ACTIVATED` accountability ledger row — live cross-engine D3a wiring visible. | none | `archive/02-designer.png` |
| c | `/designer/capabilities` | PASS. Tree renders both seeded capabilities (Customer Onboarding → KYC Verification), Draft from Universe + Create Capability affordances. | none | `archive/03-capability-designer.png` |
| d | `/portfolio` | PASS (renders fully) — but see Finding 2: the footer's "No backend is contacted" claim is false and contradicts the banner on the same page. | none | `archive/04-portfolio-landing.png` |
| e | `/portfolio/dashboard` | PASS. Live D5 stats strip: agents-in-intervention **8 of 70 registered** (matches seed exactly — live engine numbers). Phase grid plots all 22 subsidiaries. See Finding 3 for the stall-card/grid contradiction. | none | `archive/05-portfolio-dashboard.png` |
| f | `/portfolio/cascade/46da9b4e-…` (c-vendor-cost, decline-pending) | PASS — **live mode confirmed**: the append-only Action timeline (#1 Issued → #2 Cascaded → #3 Declined) renders; that section only exists for live data. L0→L1→L2 level timeline with decline reasoning passed up; summary "Currently at L2, state: declined." | none | `archive/06-cascade-detail-decline-pending.png` |
| g | `/portfolio/contract/co-tribal-knowledge-depopulation` | PASS (live). Parties/commitments, verbatim monitor mechanism panel, 3 decline triggers; Review/Decline rendered inert per queue #28. Note: surface shows only "Proposed Apr 1 2026" with no lifecycle-status indicator even though this contract is ACTIVE — documented as deliberate in `contractData.ts` ("status … deliberately dropped"), recorded here as an observation, not a defect. | none | `archive/07-contract-detail.png` |
| h | `/portfolio/subsidiary/curasync-clinical?tab=operations` | PASS — **live agent registry confirmed by network log**: `GET /api/v1/agents?domainId=6201e872…` 200 + `GET /api/v1/agents/signals…` 200 + `GET /api/v1/portfolio` 200. 5 agents with lifecycle badges (Operating / Shadow mode / In intervention / Paused), oversight chains, 2 open findings. | none | `archive/08-subsidiary-operations.png` |

Every surface was reached by direct URL (hard load against the Boot-served bundle), so SPA-fallback forwarding is verified for all toured routes.

## AmbientChatBar wire-shape check (step 3)

- `POST /api/v1/chat` with `{"domainId":"3a3a81f0-…","message":"What is my biggest risk?"}` → **503** `{"data":null,"meta":{timestamp,requestId,version,personaLevel:null},"errors":[{"code":"LLM_UNAVAILABLE","message":"Anthropic LLM client is disabled…","field":null}]}` — degraded mode, expected without an API key (known/dispositioned; the success lane cannot be exercised on this machine).
- **Envelope** matches `ApiResponse`/`ApiMeta`/`ApiError` in `frontend/src/shared/types/api.ts` field-for-field.
- **ChatResponse**: backend record `(message, toolCalls: List<ToolCallTrace>, inputTokens, outputTokens)` ≡ `chat.ts` `ChatResponse` verbatim. **ToolCallTrace**: backend `(toolName, arguments Map, result String, latencyMs long)` ≡ `chat.ts` `ToolCallTrace`. **No mismatch — no fix to chat.ts required.**
- In-browser degraded-path behavior: clicking a suggestion chip renders the user bubble + an error reply and the SPA **stays mounted** (the original C8 unmount bug stays fixed). Wart: the bubble shows axios's generic "Request failed with status code 503" instead of the envelope's human-readable `errors[0].message` — on non-2xx the axios throw is surfaced before the envelope is unwrapped (`AmbientChatBar.tsx` ~L76–109 only reads `env.errors` on 2xx). Minor, recorded below.

## Findings (new — not in the known/dispositioned list)

### Finding 1 — Unmatched SPA deep links render a silent fully-blank page (cross-phase: SpaController × router table)
`SpaController` forwards seven whole namespaces (`/portfolio/**`, `/designer/**`, `/ecosystem/**`, …) to `index.html`, but `App.tsx`/`routes.tsx` define **no catch-all route**, so `useRoutes` returns `null` and the user gets a black screen with zero console output and no recovery affordance.
- Repro: `http://localhost:8080/portfolio/subsidiary/curasync-clinical/operations` (the natural URL guess for the tab — real route carries `?tab=operations`) → blank (`archive/08a-operations-path-segment-BLANK.png`). Also `/designer/nonexistent-subpage` → blank.
- The unknown-**id** cases were each owned (cascade/contract views have in-route 404 panels), but the unknown-**path** case fell between the backend-fallback doctor and the router doctor. Suggested fix: a `*` route rendering a NotFound panel (and one inside the `/portfolio` children).

### Finding 2 — Portfolio landing footer is now false: "No backend is contacted; AI responses are pre-canned" (truthfulness drift)
`PortfolioLandingPage` footer: "Every subsidiary, capability, agent, and finding shown here is fixture data. No backend is contacted…" — written in the Phase-1 fixture era. Since the Phase-2/3/D3/D5 live swaps, these surfaces demonstrably fetch `/api/v1/domains`, `/branding`, `/agents`, `/agents/signals`, `/portfolio`, `/cascades`, `/contracts` (captured in the network log this session), and the **same page's top banner** says "live engine data + illustrative fixtures". Violates the project's own honesty bar (cf. the agents.md §5.3 disposition). One-line copy fix.

### Finding 3 — Dashboard self-contradiction: stall card says "22 unphased (no lifecycle data)" while the grid below plots all 22 subsidiaries into lifecycle phases
Mechanism: the stall card is the **live** §12.1 absent-vs-zero wire (truthful: `PhaseSeeder` seeds `phase_states` for the root demo domain only, so all 22 satellite domains are unphased), while "Where each subsidiary sits" is the **fixture** roster (live per-subsidiary swap documented as a follow-on slice in `portfolioData.ts`). Each half is individually documented; the on-screen pairing is the cross-phase gap — a demo viewer reads "no lifecycle data" directly above 22 confidently-phased subsidiaries. Cheapest reconciliation: have `AgentDemoSeeder`/`PhaseSeeder` seed a `phase_states` row per satellite domain matching the fixture phases.

### Minor observations (no action required this stage)
- `ApiMeta.personaLevel` is typed `string` but the wire delivers `null` (observed on the chat 503). No consumer reads it today.
- AmbientChatBar drops the backend's human-readable degraded-mode message on non-2xx (see wire-shape section).
- Contract detail shows no lifecycle status for an ACTIVE contract — deliberate per `contractData.ts` adapter doc; flagging only because the seeder boots with `contracts.activate=true` specifically to demo activation, and no portfolio surface shows the result (the designer ledger row is the only visible trace).

## Verdict
All 8 toured surfaces render against the seeded backend with no unknown console errors; the deferred C8 LivingCanvas acceptance **closes as PASS**. Chat wire shapes match — no frontend fix needed. Three new findings recorded above (1 functional robustness, 2 truthfulness/coherence), none release-blocking for a demo build but Finding 1 is user-visible from a guessable URL.

App stopped after the tour (PID 58440); the user's 8090 instance untouched.
