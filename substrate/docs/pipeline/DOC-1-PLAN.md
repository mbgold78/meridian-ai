# DOC-1 Implementation Plan — Docs Capture Pipeline & Guide Suite

**Status:** awaiting Mike's review. No branch created, no code written.
**Synthesized from:** milestone spec + Scout A (UI selectors), Scout B (build/seed/clock/Playwright), Scout C (scenes/guides/evidence). Paths relative to repo root `C:\Users\mbgol\DEV\meridian-ai\` unless noted.

---

## 1. Executive summary

**Shape of the solution.** A new `:docs` Gradle subproject rooted at `substrate/docs/pipeline/` — its own Node project (Playwright pinned `1.59.1` to match `substrate/frontend/package.json:45`) — that: boots `app-0.1.0.jar` on a runner-allocated free port with `--substrate.demo.seed=true --substrate.demo.contracts.activate=true --substrate.demo.fixed-clock=2026-05-12T00:00:00Z`; polls the API for seed completion (domain count == 23, never sleeps); executes declarative YAML capture specs (data, not code); composites numbered annotations anchored to selectors (not pixels) post-capture; assembles the markdown guides; and verifies determinism by capturing twice from two fresh boots and byte-comparing SHA-256 manifests. Additive `data-testid`/`data-*` attributes (~41 across ~20 component files — today exactly ONE production testid exists, `persona-identity`) make the capture suite double as UI regression: a missing selector fails the build naming the guide topic and spec. Delivered as six PR-sized slices in one worktree branch, rebase-then-ff-only, evidence under `substrate/docs/acceptance/doc-1/`.

**Three decisions Mike must ratify:**

| # | Decision | Recommendation |
|---|----------|----------------|
| D-A | **Hash mode:** byte-identical vs tolerance, given Windows (DirectWrite) vs Linux (FreeType) glyph rasterization makes cross-platform byte-identity unachievable for text-bearing pages | Byte-identical **within one platform**: the `:docs:verify` gate runs two fresh-boot captures on Linux CI and requires identical SHA-256 manifests. Linux CI output is canonical. Windows local runs get the same two-run self-identity check; cross-platform comparison is informational-only via pixelmatch tolerance. No perceptual hashing. |
| D-B | **Where the pipeline lives:** new Node project at `substrate/docs/pipeline/` vs extending `frontend/scripts/capture-demo-screenshots.ts` | New Node project. `org.gradle.parallel=true` (`substrate/gradle.properties:25`) means a second Gradle project running npm against `frontend/` races `:app:npmInstall` over `frontend/node_modules`. Cost: a duplicated `@playwright/test` pin, kept in lockstep (CONTRIBUTING rule). The existing frontend script remains until S2 lands, then is deprecated in place. |
| D-C | **OIDC chip capture: gate or manual job?** Keycloak-in-Docker is CI-feasible (the existing `KeycloakOidcRoundTripIT` already runs it in the backend job), but a browser login flow in the gate adds flake surface | Manual `workflow_dispatch` job using Compose + `infra/keycloak/realm-substrate.json`. The chip spec (`persona-identity`, already testid'd) is **excluded** from the `:docs:verify` gate manifest. Spec explicitly permits this ("capture chip via Compose in a manually-triggered job if gate would flake"). |

**Where reality contradicts the spec's assumptions (stated plainly, details in later sections):**

- **Fixed-clock flag actual name/format:** `substrate.demo.fixed-clock=<ISO-8601 instant>` (`DemoFixedClockConfig.java:45-50`, `@Primary Clock.fixed(..., UTC)`). Proven instants: `2026-05-12T00:00:00Z` (contract-breach lane) and seed+91d (stall-flip lane). Not "demo.clock", not a date — a full instant.
- **Test counts:** backend is **984 tests** (app 447 / common 70 / engine 448 / ingestion 19; 69 skipped locally, Docker-gated, run in CI). Frontend exact runtime count is **unknown** (21 test files, 141 static `it()`/`test()` occurrences; `it.each` may expand). Stale figures floating in repo docs ("395 app / 92 frontend / 13 files", "133/133") are wrong and must not be cited.
- **Frontend clock seam:** `FIXTURE_CLOCK` (compile-time, `2026-04-26`) is the active portfolio clock; the `setPortfolioClock` live seam is built but **not armed** (`clock.ts:13-23`) — good for capture. Frontend fixture clock and backend boot clock are independent and will disagree by 16 days under the pinned instant; harmless because portfolio date strings render from fixtures/seed absolutes, but it must be a documented invariant, not a surprise.
- **"8 screenshots" is actually 9 PNGs:** `08a-operations-path-segment-BLANK.png` is a bug repro whose bug is since fixed (catch-all NotFound at `App.tsx:53`). Irreproducible; archived, not migrated (§7 FILE-6).
- **Two D3 operator-guide moments have no UI:** contract monitor history (DTO carries `monitors[]`/`breachSummary`, adapter drops them — `contractData.ts:51`) and agent enforcement disclosure (`criteriaGates`/`readOnly`/`shadowEnteredAt` never rendered — `OperationsTab.tsx:266-332`). Re-specced to API-transcript + what-renders-today; UI gaps filed (§7 FILE-2/3).
- **Cascade respond/decline affordances do not exist anywhere** in the frontend (only contract Review/Decline exist, inert). D5 stubs use **reserved testid names in a registry**, no DOM stubbing (§7 FILE-4).
- **"Contract propose" has no API** (service-layer only, `USER-MANUAL.md:798`) — operator task starts from seeded PROPOSED contracts.
- **The vv reference screenshots were captured WITHOUT a fixed clock** — the pipeline's first run will not pixel-match `acceptance/final/*` by design. D2 is scene-equivalence, not pixel-equality, against the originals.
- A proto-pipeline already exists (`frontend/scripts/capture-demo-screenshots.ts`, `npm run screenshots`): declarative routes, networkidle+750ms, no assertions, no id-resolution (its cascade route 400s against the live API and silently captures the fixture fallback). DOC-1 supersedes it rather than starting from zero conceptually.

---

## 2. Worktree plan

- **Branch:** `doc-1/docs-pipeline` off `main`.
- **Isolation:** `git worktree add ..\meridian-ai-wt-doc-1 doc-1/docs-pipeline` (orchestrator standard; main tree untouched).
- **Merge discipline:** per slice — `git rebase main` then ff-only merge to `main`. No merge commits.
- **Evidence:** `substrate/docs/acceptance/doc-1/` with per-slice subdirs (`s1-core/` … `s6-ci/`), including the headline artifact: a transcript of `:docs:verify` passing twice with identical hashes.

| Slice | Scope | Acceptance check (each slice independently demonstrable) |
|-------|-------|-----------------------------------------------------------|
| **S1** Pipeline core (D1) | `:docs` subproject + Node runner: boot/seed/readiness, spec parser, capture, annotation compositor, hash manifest, 2 pilot specs using only **existing** selectors (`persona-identity`, `data-banner-provenance`, aria landmarks) | `gradlew :docs:capture` twice from fresh boots → identical manifest on the local platform; transcript to `acceptance/doc-1/s1-core/` |
| **S2** Testids + 8 scene specs | Additive attributes per §4 (~28 required + chosen optionals); 8 scene specs incl. live-mode assertions and cascade-id API resolution | Frontend `npm test` green (re-measured count recorded); `:docs:verify` over the 8 scenes passes twice; deliberate testid rename on a scratch branch fails naming topic+spec |
| **S3** D2 migration proof | Same 8 filenames/scenes from the pipeline; originals archived to `acceptance/final/archive/`; all **31 PNG references across 7 manual files** (both styles: markdown links AND plain backtick paths) re-pointed; link check in `:docs:assemble` | Link audit zero broken; archive present; 08a deviation recorded |
| **S4** D3 operator guide | 7 tasks (boot empty-vs-seeded; Glacier ingest; diagnostic+curation+provenance; cascade authored+issued via API→UI; contract respond→monitor→breach; agent register shadow→promote→pause; chain verify) each ending with the worked-when observable; **fresh curl transcript for cascade issue** (none exists in repo); G1/G2 moments re-specced | Tester executes each task verbatim against a fresh boot; worked-when states observed; transcripts to `acceptance/doc-1/s4-operator/` |
| **S5** D4 admin + D5 COO | Admin: Day-0 readiness checklist (egress to api.anthropic.com/IAM/OTLP, issuer reachability, audience pin, admin-role presence, SSE paths), Entra ID (`roles` claim) + Okta (`groups`) role-map worked examples, persona/role decision table, dev-identity caveat + OIDC chip difference, must-stay-off demo-flag list; COO: read-path tasks + stub topics (`status: blocked-on-ui` frontmatter) with pre-written capture specs against reserved testids for all 11 not-yet UI verbs | Stub inventory == §7 not-yet verb list; `:docs:assemble` passes; admin checklist cross-references (not duplicates) auth.md/deploy.md/observability.md |
| **S6** D6 CI wiring | `docs-verify` gate job in `substrate-ci.yml`; manual OIDC chip job; `guides/CONTRIBUTING.md` rule set; path-based CI hint step | CI green on a no-op PR; scratch testid-rename PR shows red `docs-verify` naming topic+spec (captured as evidence); manual OIDC job runs once successfully |

---

## 3. Capture-spec schema (YAML, v1)

Specs live in `substrate/docs/pipeline/specs/*.yaml`. They are **data**: the runner interprets; no per-scene code. Global settings (boot flags, clock instant, locale `en-US`, timezone `UTC`, font interception, default viewport) live in `substrate/docs/pipeline/pipeline.config.yaml`, not per-spec.

| Field | Type / Req | Semantics |
|-------|------------|-----------|
| `id` | string, req | Unique kebab-case key; default output basename; named in verify failures |
| `title` | string, req | Human scene title for docs and error messages |
| `topic` | string, req | Guide-topic backlink, e.g. `operator/06-agent-lifecycle#observe-in-ui` — the topic named when this spec's selectors fail |
| `route` | string, req | SPA route; may contain `{var}` placeholders filled by `resolve` |
| `resolve` | list, opt | API id-resolution for per-boot UUIDs (domain ids, cascade ids regenerate every boot — in-memory H2). Each: `var`, `get` (API path), `pick` (JSONPath) |
| `persona` | enum, req | `L1_EXECUTIVE` (client-guide captures) or `L3_ANALYST` (operator). **Applied via `addInitScript` seeding localStorage `meridian-persona-level`** before app scripts run — sets the rendered chip and the `X-Persona-Level` wire header atomically (`personaSlice.ts:13`, `client.ts:28-29`). Header-only injection is wrong by construction (chip would diverge). Note: persona does not change data visibility (manual:239) |
| `localStorage` | map, opt | Extra pre-seeds: `substrate-canvas-welcomed-v1: "1"` (suppress welcome overlay), `meridian-theme`, `substrate-workspace-domain-id` (no workspace-switcher UI exists) |
| `viewport` | object, opt | Default `1536x960`, `deviceScaleFactor: 1` (matches the vv tour) |
| `waits` | list, req | Ordered readiness gates, two kinds: `api:` `{get, until: {path, equals/min}, timeoutMs}` — poll an API predicate (e.g. seeded domain count, breach state after the PT60S sweep); `selector:` visible-wait, optionally `attr`/`equals` for state attributes (`[data-canvas-ready="true"]`, `[data-stats-source="live"]`). **Every spec must include at least one live-mode assertion** to defeat the silent fixture fallback (`agentData.ts:121-138`). Never `sleep` |
| `preSteps` | list, opt | Ordered interactions: `click:`, `fill: {selector, value}`, `hover:`, `waitSelector:`. Selectors prefer `data-testid`; aria/role allowed where Scout A marked EXISTING-OK |
| `capture` | object, req | `mode: fullPage` or `mode: element` + `selector:` (element-crop via boundingBox) |
| `annotations` | list, opt | Numbered callouts: `n`, `anchor` (selector — position computed from live `boundingBox()` at capture time, never hardcoded pixels), `offset: {x,y}` relative to anchor box, `text`. Composited **post-capture** by a deterministic pure-JS renderer into `<output>.annotated.png`; the base PNG stays the regression artifact; both are hashed |
| `output` | string, opt | Output filename; default `{id}.png`. D2 specs set the legacy filenames |
| `determinism` | enum, req | `static` (fixed copy), `seeded` (engine/fixture data under pinned seed+clock), `animated` (canvas family: CSS animation-freeze injection + settle-poll mandated; see §5) |

**Worked example — simple (portfolio landing):**

```yaml
id: portfolio-landing
title: Portfolio vantage-point picker
topic: client/coo-quick-guide#entering-the-portfolio
route: /portfolio
persona: L1_EXECUTIVE
waits:
  - api: { get: /api/v1/domains, until: { path: "$.data.length", equals: 23 }, timeoutMs: 60000 }
  - selector: 'select[aria-label="Choose subsidiary"]'
  - selector: '[data-banner-provenance="live"]'    # live-mode assertion (PortfolioLayout.tsx:68)
capture: { mode: fullPage }
output: 04-portfolio-landing.png
determinism: static
```

**Worked example — complex (operations tab, pre-step + callouts):**

```yaml
id: subsidiary-operations
title: Operations tab — agent fleet with deployment detail expanded
topic: operator/06-agent-lifecycle#observe-in-ui
route: /portfolio/subsidiary/curasync-clinical?tab=operations   # slug + ?tab= are boot-stable
persona: L3_ANALYST
localStorage: { substrate-canvas-welcomed-v1: "1" }
waits:
  - api: { get: /api/v1/domains, until: { path: "$.data.length", equals: 23 }, timeoutMs: 60000 }
  - selector: '[data-agents-source="live"]'        # plan-added provenance attr; defeats fixture fallback
  - selector: '[data-testid="agent-row"][data-agent-key="a-pre-visit"]'
preSteps:
  - click: '[data-testid="agent-row"][data-agent-key="a-pre-visit"]'
  - waitSelector: '[data-testid="agent-deployment-panel"]'
capture: { mode: fullPage }
annotations:
  - { n: 1, anchor: '[data-testid="agent-row"][data-agent-key="a-pre-visit"]',
      offset: { x: -18, y: 4 }, text: "Lifecycle badge: Operating / Shadow / In intervention / Paused" }
  - { n: 2, anchor: '[data-testid="agent-deployment-panel"]',
      offset: { x: -18, y: 8 }, text: "Deployment detail: criteria and oversight chain" }
  - { n: 3, anchor: '[data-testid="finding-card"]',
      offset: { x: -18, y: 4 }, text: "Open finding — 'Surfaced N days ago' is fixture-clock-pinned" }
output: 08-subsidiary-operations.png
determinism: seeded
```

---

## 4. data-testid additions (additive-only; full Scout A table)

**Totals:** ~41 attributes (≈28 REQUIRED, ≈12 optional, +1 plan addition) across **~20 files** (19 components + shared `DataTable`). All additive — no behavior changes; the one logic-adjacent item is `data-canvas-ready` (reflects existing `nodes.length>0` state; flagged). EXISTING-OK rows add nothing (cited selector is already stable). RESERVED = name registered in `specs/selector-registry.yaml`, **no DOM element today**. Rows keep Scout A's numbering for auditability; paths relative to `substrate/frontend/src/`.

| # | File:line | Element | Testid / attribute | Req | Rationale / risk |
|---|-----------|---------|--------------------|-----|------------------|
| **Scene a — `/` Living Canvas** |
| 1 | features/canvas/LivingCanvas.tsx:388 | loaded-canvas root | `living-canvas` + `data-canvas-ready={nodes.length>0}` | REQ | Only deterministic wait gate for async ELK layout; logic-adjacent (state-reflecting attr) |
| 2 | features/canvas/SummaryRibbon.tsx:48,115 | stats strip; per-stat | `canvas-summary-ribbon`; `data-stat="systems\|manual-procs\|open-risks\|annual-savings"` | REQ | No landmark exists |
| 3 | features/canvas/nodes/CanvasSystemNode.tsx:86 | system tile | `canvas-system-node` | REQ | Per-node id via xyflow `.react-flow__node[data-id]` — **unverified in live DOM, confirm at impl** |
| 4 | features/canvas/nodes/CanvasProcessNode.tsx:35 | process tile | `canvas-process-node` | REQ | Same |
| 5 | features/canvas/LensRail.tsx:73 | lens buttons | — (aria-label Today/Target/Waves) | OK | |
| 6 | features/canvas/AmbientChatBar.tsx:206-228 | textarea / Ask / chips | `chat-input` REQ; `chat-send`, `chat-suggestion` opt | REQ/opt | Placeholder is brand-driven (unstable) |
| 7 | features/canvas/WelcomeOverlay.tsx:82-87 | dismiss button | `welcome-dismiss` | opt | Preferred pre-step: localStorage `substrate-canvas-welcomed-v1` seed |
| 8 | features/canvas/CompletenessBadge.tsx, DnaPill.tsx | badge / pill | `completeness-badge`, `dna-pill` | REQ | No aria/role at all |
| **Scene b — `/designer`** |
| 9 | shared/components/DataTable.tsx:109-110 | table row | `data-row-id={keyExtractor(item)}` passthrough | REQ | One generic change covers decision rows incl. CONTRACT_ACTIVATED ledger row |
| 10 | features/designer/FutureStateDesigner.tsx:220-229 | Run Impact Analysis | — (static text) | OK | |
| 11 | features/designer/ImpactAnalysisPanel.tsx | result panel root | `impact-analysis-panel` | REQ | Dynamic content |
| 12 | features/designer/FutureStateDesigner.tsx:274-279 | New Decision / Cancel | — (static text) | OK | |
| **Scene c — `/designer/capabilities`** |
| 13 | features/capability-designer/CapabilityDesigner.tsx:155-171 | header buttons | `create-capability` REQ (label `Create ${label}` terminology-driven); `draft-from-universe` opt | REQ/opt | |
| 14 | features/capability-designer/CapabilityTree.tsx:135-158 | tree row | `capability-tree-row` + `data-capability-id={node.data.id}` | REQ | treeitem accessible name is data-driven; react-arborist virtualization caveat (§7) |
| 15 | features/capability-designer/CapabilityDetailPane.tsx:161-186 | name edit/save | `capability-name-edit`, `capability-name-save` (only if pane interactions documented); Save/Cancel aria static OK | opt | aria-labels :161/:180 terminology-interpolated |
| **Scene d — `/portfolio` landing** |
| 16 | features/portfolio/PortfolioLandingPage.tsx:57-92 | persona cards | `persona-card-operating-partner`, `persona-card-subsidiary-coo` | opt | Static text works; testids cheap insurance. Select OK (aria) |
| **Scene e — `/portfolio/dashboard`** |
| 17 | features/portfolio/CorporateOperatingPartnerView.tsx:417 | signal cards | `data-signal="cross-opportunities\|agents-intervention\|lifecycle-stalls\|coordination-signals"` | REQ | Assert per-card copy without matching dynamic numbers; rides existing `data-card-source` |
| 18 | features/portfolio/CorporateOperatingPartnerView.tsx:695 | cascade card | `cascade-card` + `data-cascade-id={row.id}` | REQ | Live ids are per-boot UUIDs; heading is engine critique text |
| 19 | features/portfolio/CorporateOperatingPartnerView.tsx:902 | roster row | `subsidiary-row` + `data-slug={sub.slug}` | opt | Row link name is fixture-stable |
| 20 | features/portfolio/PortfolioLayout.tsx:68 | provenance banner | — (`data-banner-provenance` shipped) | OK | |
| **Scene f — cascade detail** |
| 21 | features/portfolio/CascadeDetailView.tsx:84-118 | header | `cascade-header` | REQ | h1 is engine critique text |
| 22 | features/portfolio/CascadeDetailView.tsx:161 | level row | `cascade-level-row` + `data-level` + `data-state` | REQ | Per-level targeting |
| 23 | features/portfolio/CascadeDetailView.tsx:306-309 | action timeline entry | `cascade-action-row` + `data-chain-seq` | REQ | Must assert #1 Issued → #2 Cascaded ordering; timeline presence is also the live-vs-fixture discriminator |
| 24 | features/portfolio/CascadeDetailView.tsx:345 | footer summary | `cascade-summary` | opt | Static label exists |
| **Scene g — contract detail** |
| 25 | features/portfolio/ContractDetailView.tsx:321-331 | inert Review button | `contract-action-review` | REQ | Forward-stable: name may change when live verb ships |
| 26 | features/portfolio/ContractDetailView.tsx:333-344 | inert Decline button | `contract-action-decline` | REQ | Same |
| 27 | features/portfolio/ContractDetailView.tsx:312 | footer actions region | `contract-actions` | REQ | No landmark |
| 28 | — | monitor history | `contract-monitor-history` | **RESERVED** | **Surface does not render** (adapter drops `monitors[]`/`breachSummary`, contractData.ts:51) — §7 FILE-2 |
| **Scene h — operations tab** |
| 29 | features/portfolio/SubsidiaryCooView.tsx:104-107 | tab buttons | — (role=tab, static labels) | OK | |
| 30 | features/portfolio/OperationsTab.tsx:209-215 | agent row | `agent-row` + `data-agent-key={agent.id}` | REQ | aria-label interpolates Expand/Collapse + dynamic name; agent_key is seed-stable |
| 31 | features/portfolio/OperationsTab.tsx:281-284 | deployment panel | `agent-deployment-panel` | REQ | role=region has dynamic name |
| 32 | — | enforcement disclosure | `agent-enforcement-disclosure` | **RESERVED** | **Never rendered** (criteriaGates/readOnly/shadowEnteredAt dropped) — §7 FILE-3 |
| 33 | features/portfolio/OperationsTab.tsx:444 | finding card | `finding-card` + `data-finding-id` | REQ | Dynamic fixture text; "Surfaced N days ago" is FIXTURE_CLOCK-deterministic |
| 34 | features/portfolio/OperationsTab.tsx:80-90 | fleet stat cards | `data-stat="agents-active\|agents-intervention\|open-findings"` | opt | Labels static |
| — | features/portfolio/OperationsTab.tsx (roster container) | agents data provenance | `data-agents-source={source}` | **REQ (plan addition)** | Beyond Scout A: mirrors shipped `data-stats-source` pattern; the only way a spec can refuse the silent live→fixture fallback on this surface |
| **D3 operator moments (beyond scene overlap)** |
| 36 | features/canvas/drawer/RisksTab.tsx:78-130 | finding row + expanded body | `canvas-finding-row` + `data-finding-id`; `canvas-finding-detail` | REQ | No role/label at all |
| 37 | features/canvas/drawer/ProvenanceTab.tsx:78,19 | provenance tab + entry | `provenance-tab`; `provenance-entry` + `data-evidence-id` | REQ | Headers dynamic; sha256/extractor chips hookless |
| 38 | features/canvas/drawer/DetailDrawer.tsx:85-124 | rail / panel | rail — (static aria); panel `drawer-panel` + `data-tab={tab}` | REQ | Capture can deep-link `/?view=provenance` instead of clicking |
| 42 | features/canvas/drawer/DecisionsTab.tsx:53-86 | verify button / result / rows | button — (static text); `decision-verify-result` + `data-valid`; `decision-log-row` + `data-decision-id` | REQ/opt | Result banner content dynamic ("✓ All N decisions verified… N/N links") |
| **D5 COO + pre-steps** |
| 45 | — | cascade respond/decline | `cascade-actions`, `cascade-action-respond`, `cascade-action-decline` | **RESERVED** | **Do not exist anywhere** (CascadeDetailView.tsx:19-20 EXTENSION POINT(cascade-3)) — §7 FILE-4 |
| 46 | features/portfolio/SubsidiaryCooView.tsx:266-286 | federation invitations segment | `federation-invitations-segment` | REQ | Count-interpolated text |
| 47 | back-links (Contract/Cascade views) | — (static text) | OK | |
| 49 | features/personas/PersonaSwitcher.tsx:131-145 | anonymous trigger | `persona-switcher-trigger` | REQ* | No aria-label; visible text = current persona. *Required only if the persona-switch capture shows the dropdown; localStorage pre-seed is the default mechanism |
| 50 | features/personas/PersonaSwitcher.tsx:149-184 | dropdown options | `persona-option` + `data-level` | REQ* | Same condition |
| 51 | features/personas/PersonaSwitcher.tsx:49 | OIDC chip | — (`persona-identity` shipped) | OK | |

**Constraint restated:** these are the *only* production-code changes in DOC-1. RESERVED names go in `specs/selector-registry.yaml` only.

---

## 5. Determinism contract

**What guarantees stable output:**

| Guarantee | Mechanism | Source |
|-----------|-----------|--------|
| Browser bits | `@playwright/test` pinned exact `1.59.1` → pinned Chromium build | package.json:45 |
| Seed data | 14 seeders, Kahn topo order, ONE outer transaction (all-or-nothing); fresh in-memory H2 every boot | SeedOrchestrator.java:56-117 |
| Engine clock | `--substrate.demo.fixed-clock=2026-05-12T00:00:00Z` (`@Primary Clock.fixed`, UTC) — canonical instant; 3b-proven: co-tribal ACTIVE, April breach detectable, stall=0 | DemoFixedClockConfig.java:45-50 |
| Frontend clock | `FIXTURE_CLOCK` compile-time `2026-04-26`; live seam NOT armed | clock.ts:13-47 |
| Seed readiness | API poll: `GET /api/v1/domains` until count==23 (health UP ≠ seeded — HTTP accepts traffic while the seed transaction is open) | SeedOrchestrator.java:100-117 |
| Breach-state scenes | Additional API poll for `BREACH_DETECTED` (first PT60S monitor sweep lands up to ~60s after seed) | application.yml:182-186 |
| Layout | ELK `layered` with fixed options — deterministic for identical input | LivingCanvas.tsx:72-94 |
| Locale/timezone | Playwright context `locale: 'en-US'`, `timezoneId: 'UTC'` — pins the four locale/tz-dependent renderers (PlanTab.tsx:337, DecisionsTab.tsx:17, CapabilityDetailPane.tsx:326, FutureStateDesigner.tsx:210) | Scout B audit |
| Rendering scale | `deviceScaleFactor: 1`, fixed 1536x960 viewport | vv tour parity |
| Per-boot ids | `resolve` steps (root domain by name "Operational Risk"; cascade by `attentionSignal=='decline-pending'`); never hardcoded UUIDs | Scout C |

**Gaps Scout B found, and mitigations:**

| Gap | Severity | Mitigation |
|-----|----------|------------|
| Google Fonts CDN (`index.html:12-17`, `display=swap`) — network flake + fallback-glyph capture | High | **No production change** (self-hosting is owned by the C7 theming chunk): runner intercepts font requests via `page.route` and serves vendored copies from `pipeline/assets/fonts/`; await `document.fonts.ready` before capture |
| framer-motion JS animations (8 canvas files, zero `reducedMotion` handling) — NOT neutralized by Playwright `animations:'disabled'` | High (canvas scenes) | `determinism: animated` class: inject `*{animation:none!important;transition:none!important}` (kills the infinite CSS pulses — `animate-pulse`, live-dot); entrance animations terminate, then settle-poll: two consecutive identical screenshots at rAF spacing before accepting |
| Silent live→fixture fallback (`agentData.ts:121-138`, retry:false, same pattern in cascade/contract data) | High | Mandatory per-spec live-mode assertion (§3); `data-agents-source` addition |
| Wall-clock seed fields (PhaseSeeder `enteredAt` deliberately `Instant.now()`; all `@CreationTimestamp` rows) | Med | Not UI-rendered; only effect is `lifecycleStallCount`, which stays 0 while fixed-clock < boot+90d — `2026-05-12` is permanently in the past → stable forever. Stall-flip capture (D3 T7) runs a **separate second-boot lane** with computed instant boot+91d; rendered date strings stay fixture/seed-absolute so the lane stays deterministic (verify at impl) |
| Chat-bar `Date.now()` ids (AmbientChatBar.tsx:71-96) | Low | Capture specs never exercise chat send |
| Radix ScrollArea / platform scrollbars | Low | fullPage capture + fixed viewport; element-crop where inner scroll is unavoidable |
| Seeder-content drift breaks name-based selectors | Med | Attr-based targeting (`data-agent-key`, `data-slug`, `data-chain-seq`) preferred everywhere over accessible-name matching |

**Two-run verification design (`:docs:verify`):** boot fresh instance → full capture → SHA-256 manifest A → kill → boot fresh (new H2, new UUIDs) → recapture → manifest B → require A==B byte-for-byte → emit transcript (both manifests + diff result) to `acceptance/doc-1/`. This proves determinism against reseeding, id-resolution, clocks, and animation handling in one shot.

**Honest hash-mode statement:** byte-identical PNGs are achievable **within one platform + pinned browser** (that is the gate, run on Linux CI; canonical baselines are Linux artifacts). Cross-platform byte-identity is **not achievable** for text-bearing pages (DirectWrite vs FreeType rasterization — general knowledge, consistent with Playwright's own platform-suffixed snapshots). Windows local runs get the same two-run self-identity check plus structural selector assertions; Windows-vs-Linux pixel comparison is informational-only (pixelmatch tolerance). No perceptual hashing anywhere — it adds tuning surface without buying anything the two-run design doesn't.

---

## 6. Gradle / CI design

**Module wiring:** `settings.gradle` adds `include 'docs'` with `project(':docs').projectDir = file('docs/pipeline')`.

Two hazards Scout B verified, with mitigations:

1. **Root `subprojects { apply plugin: 'java' }`** (`substrate/build.gradle:21-28`) would drag `:docs` into `gradlew build` — and CI's backend job runs plain `./gradlew build`. Mitigation: guard the subprojects block to skip `docs` (one-line build-script change; not production code), AND keep all `:docs` tasks detached from `build`/`check` lifecycles — invoked explicitly only.
2. **`org.gradle.parallel=true`** → a second node-gradle project against `frontend/` races `:app:npmInstall`. Mitigation: `:docs` owns its own `nodeProjectDir = docs/pipeline` (decision D-B), pinning `nodeVersion=22.14.0` from the same `gradle.properties` values as `:app`.

**Task graph:**

| Task | Depends on | Does |
|------|-----------|------|
| `:docs:npmInstall` | — | `npm ci` in `docs/pipeline` (node-gradle, same pinned Node as `:app`, app/build.gradle:136-145 precedent) |
| `:docs:capture` | `:app:bootJar`, `:docs:npmInstall` | Allocates a free port itself and passes `--server.port=N` (no port-file mechanism exists; `--server.port=0` stdout parsing unverified — Scout B); spawns jar with seed/activate/fixed-clock flags; API-polls readiness; runs the spec runner; writes `build/docs/captures/` |
| `:docs:assemble` | `:docs:capture` | Composes guides from `docs/guides/` sources + captures into `build/docs/manual/`; **formalizes the missing assembler** (USER-MANUAL.md claims regeneration but no script exists) including the `Honest limitation.` → `Not yet:` normalization; link-checks all image/manual references (both markdown-link and backtick-path styles) |
| `:docs:verify` | — (orchestrates) | Two full capture runs from fresh boots; SHA-256 manifest compare; selector assertions. Failure message format: `DOCS-VERIFY FAIL topic=<topic> spec=<id> selector=<selector>` |

Screenshots are build artifacts — `build/docs/**` never checked in; manual sources reference capture paths that `:docs:assemble` validates against the fresh capture set. Structure stays single-source multi-target (markdown now; heading/figure discipline keeps it PDF-ready later).

**CI placement** (`.github/workflows/substrate-ci.yml`):

| Job | Trigger | Gate? |
|-----|---------|-------|
| `docs-verify` (new) | PRs touching `substrate/**` (same paths filter as existing jobs); ubuntu-latest, JDK 21 + setup-gradle, `npx playwright install chromium --with-deps`, `./gradlew :docs:verify` | **Yes** (D6) |
| `docs-oidc-chip` (new) | `workflow_dispatch` only; Compose boots Keycloak (`infra/keycloak/realm-substrate.json`; Docker proven available — container job + KeycloakOidcRoundTripIT); captures the `persona-identity` chip via browser login | **No** (decision D-C) |
| Existing `backend` | unchanged — `:docs` excluded from `build` lifecycle so `./gradlew build` is unaffected | — |

**Enforcing "feature PR touching a documented surface must update its spec":**

1. **Primary (hard):** the capture suite *is* the regression suite. Rename/remove a testid or break a documented interaction → `:docs:verify` selector failure in the gate, naming topic + spec. This is mechanical and unskippable.
2. **Secondary (soft hint):** a CI step diffs PR paths against the merge-base; if `substrate/frontend/src/**` changed and `substrate/docs/pipeline/specs/**` did not, it posts a PR annotation listing the topics whose specs reference selectors in touched files (lookup via a generated `selector-registry.json` mapping testid → spec → topic). Hint, not gate — the gate is #1.
3. **Documented:** `guides/CONTRIBUTING.md` (D6) states the rule, the failure-message format, and how to re-run capture locally.

---

## 7. Risks & filed items

**Filed, not fixed (per spec constraint):**

| ID | Item | Disposition |
|----|------|-------------|
| FILE-1 | `GET /api/v1/contracts` without `domainId` returns **500 INTERNAL_ERROR** (live-verified) where a 400 validation error is correct | File issue. Guides document only the correct call (`?domainId=<resolved>`); the wart is never shown or worked around in docs |
| FILE-2 | Contract **monitor history** surface absent — wire DTO carries `monitors[]`/`breachSummary` (contracts.ts:174-175), adapter deliberately drops them (contractData.ts:51) | File UI gap. D3 contract task re-specced: monitor/breach beats proven via API transcript (acceptance-3b lane); UI shows terms only — stated honestly in the guide. Testid name RESERVED |
| FILE-3 | Agent **enforcement disclosure** never rendered (criteriaGates/readOnly/shadowEnteredAt dropped before AgentDeploymentPanel) | Same treatment: API transcript + what-renders-today; RESERVED testid |
| FILE-4 | Cascade **respond/decline affordances do not exist** anywhere (spec premise "exist but inert" true only for contracts) | RESERVED names in selector registry; **no inert DOM stubbing** (that's a product decision mirroring the queue-#28 contract-footer pattern, not a docs decision). D5 stubs reference reserved names |
| FILE-5 | vv Finding 2 (false portfolio-landing footer) + Finding 3 (stall-card/grid copy contradiction) — open copy issues on scenes 04/05 | Capture pins current copy deliberately; when copy fixes land, re-capture is one command (that's the point of the pipeline). Noted in D2 evidence |
| FILE-6 | `08a-operations-path-segment-BLANK.png` documents a since-fixed bug — irreproducible | D2 migrates **8** scenes; 08a archived with provenance note; optional follow-up: repurpose the URL as a NotFound-panel scene |
| FILE-7 | Live agents list returns `agentKey=null` on the wire (live-checked) | Verify before documenting any `{idOrKey}`-by-key curl in D3 T5; source keys from deploy responses or agent detail |
| FILE-8 | No assembler exists for USER-MANUAL.md despite its self-description; section sources and assembled output use different callout vocabulary (`Honest limitation.` vs `Not yet:`) | `:docs:assemble` formalizes it (S5); D5 stub extraction keys on the source form, not the assembled form |

**Risk register (top items; scout lists incorporated):**

| Risk | Likelihood/Impact | Mitigation |
|------|-------------------|------------|
| xyflow `.react-flow__node[data-id]` behavior asserted from library knowledge, not verified in live DOM | M/M | Verify in S1 pilot before S2 specs rely on it; fallback: testid inside node components (we own them) |
| Canvas determinism (ELK async + fitView + infinite pulses) | H/H | `data-canvas-ready` gate + animated-class handling (§5); canvas is the S1 pilot's hard case |
| react-arborist virtualization drops off-screen rows from DOM | L/L now | Demo tree = 2 capabilities; breaks only if the capability seeder grows past viewport — note in registry |
| Capture runner couples to three localStorage key contracts (`meridian-persona-level`, `substrate-workspace-domain-id`, `substrate-canvas-welcomed-v1`) with do-not-rename deviations | M/M | Keys centralized in `pipeline.config.yaml`; CONTRIBUTING notes the coupling |
| Terminology/brand-driven labels (Create `${capability}`, `appDisplayName`, interpolated aria) break under workspace overrides | M/M | Testids proposed for all of them (§4); guides never text-match these strings |
| Persona segregation yields almost no visual difference (persona doesn't change data visibility) | certainty/L | Stated in guides; Executive-vs-Architect captures differ in chip only — not escalated as a gap, it's the product's design |
| Duplicated Playwright pin (frontend + docs/pipeline) drifts | M/L | CONTRIBUTING lockstep rule; version sourced from one place in renovate config if/when added |
| Fixed-clock instant ages (repo life) | L/L | `2026-05-12` is permanently past → stall=0 stable; only the stall-flip lane computes its instant relative to boot |
| First pipeline run won't pixel-match checked-in vv PNGs (clock pinned now, wasn't then) | certainty/none | By design; D2 acceptance is scene-equivalence + filename parity, recorded as deviation |

---

## 8. Acceptance criteria restated against reality

| # | Spec criterion | Reality correction | Demonstrated by |
|---|----------------|--------------------|-----------------|
| A1 | Clean checkout: `:docs:verify` twice → identical hashes | "Identical" = within-platform (decision D-A); Linux CI canonical; each `:docs:verify` invocation itself contains two fresh-boot runs | CI `docs-verify` log + local transcript at `acceptance/doc-1/verify-transcript.txt` showing two manifests, byte-equal |
| A2 | Scratch-branch testid rename fails naming topic+spec | Mechanism is the selector assertion in `:docs:verify`, message `DOCS-VERIFY FAIL topic=… spec=… selector=…` | S6 evidence: red CI run on a scratch PR renaming `agent-row`, captured to `acceptance/doc-1/s6-ci/` |
| A3 | 8 legacy screenshots reproduced, same filenames/scenes; originals archived; manual links resolve | 9 PNGs exist; 08a archived-not-reproduced (bug fixed — FILE-6). "Links resolve" = all **31 references across 7 files in two styles** pass the `:docs:assemble` link check. Pixel parity with originals is explicitly NOT the criterion (clock now pinned) | S3 evidence: link-audit output, archive listing, side-by-side scene-equivalence notes |
| A4 | Operator tasks executable verbatim by a fresh reader | 7 tasks mapped (Scout C); T3 cascade-issue needs a **fresh curl transcript** (none exists in repo); "contract propose" reworded — no authoring API exists, task starts from seeded PROPOSED; two moments re-specced per FILE-2/3 | S4: Tester runs each task verbatim on a fresh boot; worked-when observables logged to `acceptance/doc-1/s4-operator/` (acceptance-must-be-user-runnable rule) |
| A5 | Existing tests green | Correct figures: **backend 984** (app 447/common 70/engine 448/ingestion 19; 69 Docker-skips locally, exercised in CI). Frontend exact count unknown until run (21 files, ~141 static cases) — **re-measure at S2 and record**; stale "395/92/133" figures rejected | Per-slice: `./gradlew build` + frontend `npm test` outputs in slice evidence |
| A6 | COO stubs for every not-yet UI verb | 11 UI-verb stubs enumerated (cascade issue+AI authoring; cascade level verbs; contract review/decline; contract operational state; attestation; notification feed; agent controls; finding curation; generative-draft accept; Add Source commit; decision-form picker). Placement decision: the genuinely COO-facing six live in the COO guide; the five operator-grade verbs stub in the operator guide (default — object if wrong) | S5: stub inventory table cross-checked against the §3 verb list; every stub has `status: blocked-on-ui` frontmatter + a pre-written capture spec against RESERVED testids |

**Secondary decisions defaulted in this plan (object if wrong):** canonical clock instant `2026-05-12T00:00:00Z` + separate stall-flip lane; FILE-2/3 re-spec rather than build UI; 08a archived; COO-vs-operator stub placement split; fonts via runner interception not self-hosting.

---

## 9. Ratification record — 2026-06-12 (product-owner authority delegated to orchestrator by Mike)

| # | Question | Ruling |
|---|---|---|
| D-A | Hash mode | RATIFIED as recommended: byte-identical within-platform; Linux CI is the canonical baseline; local Windows runs verify structural determinism (selector pass + within-run stability), never cross-platform byte equality. |
| D-B | Pipeline home | RATIFIED: new `:docs` Gradle subproject at `substrate/docs/pipeline/` with its own node project; explicitly excluded from the backend gate task graph. |
| D-C | OIDC chip capture | RATIFIED: manual `workflow_dispatch` Compose job, non-gate. |
| P-1 | Two D3 moments lacking UI (contract monitor history; agent enforcement disclosure) | RE-SPEC to the operator-grade API path: both doc moments become terminal captures of the real API responses (`GET .../monitors` + run history; verb-response `enforcement` block), which is faithful to how an operator actually reads them today. The two UI rendering gaps are FILED as named follow-on product slices (UI-1 contract monitor-history panel; UI-2 enforcement-disclosure rendering in Ops detail) — NOT built in DOC-1, preserving the zero-production-change constraint. |
| P-2 | Cascade respond/decline forward testids | RESERVE name-only in the selector registry. Adding inert cascade affordances would invent product surface that queue #24/#28 sessions explicitly gate. D5 stubs reference registry names; the feature PR that builds the verbs claims them. |
| P-3 | Live-entity capture identity (per-boot UUIDs) | Capture specs never hard-code engine UUIDs. Selection rides stable fixture keys via data-* attributes (agent_key, contractKey, slug) and seed-deterministic signals (e.g. the unique decline-pending card); the data-* machine-readable markers are formalized as part of the capture contract alongside data-testid. |
| P-4 | Canonical docs clock | Backend flag `substrate.demo.fixed-clock=2026-05-12T00:00:00Z` is the canonical capture instant. Frontend `FIXTURE_CLOCK` (2026-04-26) is left untouched — it is deterministic, which is all captures require; the 16-day semantic skew is documented in CONTRIBUTING.md and its alignment is FILED (rides the S15 live-clock swap, not DOC-1). |
| P-5 | Persona application in captures | localStorage pre-seeding via addInitScript (keys `meridian-persona-level`, `substrate-workspace-domain-id`, `substrate-canvas-welcomed-v1`), treated as a documented storage-key contract with do-not-rename guards already present in source. |
| P-6 | Breach-state scenes | Captures requiring `BREACH_DETECTED` poll the monitor-run API for the post-sweep state (never sleep), consistent with the seed-readiness poll discipline. |

Acceptance criterion 5 is restated per §8 against real counts (backend 984; frontend = whatever `npm run test -- --run` reports at slice time, captured in evidence).
