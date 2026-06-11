# D5 acceptance — computed statistics severing S6 (build step 9)

Date: 2026-06-11 · Spec: `docs/engine-spec/diagnostic.md` §12/§14 step 9 · ADR-004 D13
Machine: dev (Docker-less) — H2 fast lane; Testcontainers-Postgres ITs compile locally, RUN IN CI (`PostgresTransformationPersistenceIT` — test plan #16). H2-green is never acceptance.

## User-runnable sequence (executed, evidence preserved here)

Boot A — DemoPack seed, NORMAL clock:

```
java -jar app/build/libs/app-0.1.0.jar --spring.profiles.active=dev --substrate.demo.seed=true
curl :8080/api/v1/portfolio
```

→ `portfolio-baseline-2026-06-11.json`:
`lifecycleStallCount: 0`, `unphased: 22` (Beacon satellites — SEPARATE count),
primary card `('Operational Risk', 'design', stalled=false)`,
`coordinationPatternsThisWeek: 0` with visible breakdown `{contractEvents:0, cascadeActions:0}`,
`agentsAvailable: true, agentsInIntervention: 8`, `declinePendingCascades: 1`,
`computedFrom.demoDomainsIncluded: true`.

Thin-slice CRUD on the live boot (spec §10.1, all 2xx through the envelope):

```
POST /api/v1/transformation/plans                      → 201 plan (status DRAFT)
POST /api/v1/transformation/plans/{id}/initiatives     → 201 initiative
PATCH /api/v1/initiatives/{id}/progress {55,"note"}    → 200, progressUpdatedAt stamped
POST  …/initiatives {"status":"COMPLETE","progressPct":40}
GET  /api/v1/portfolio/subsidiaries/{domainId}         → initiatives:
       ('Retire manual reconciliation bridge', PLANNED, 55)
       ('Document tribal processes', COMPLETE, 100)    ← stored 40; §12.5 renders 100
PATCH …/progress {"progressPct":101}                   → HTTP 400 (never persisted)
```

Subsidiary composition also showed: 21 client-surfaced findings with §12.4
`surfacedAt = createdAt` (operator/fixture rows), rollups
`{HIGH:8, MEDIUM:10, CRITICAL:3}` operator==client (no PENDING_REVIEW rows yet),
`pendingFederationInvitations: 1` (delegated §12.3 number).

Boot B — SAME seed + the fixed-clock lever (§14 step 9 acceptance lever):

```
java -jar app/build/libs/app-0.1.0.jar --spring.profiles.active=dev \
  --substrate.demo.seed=true --substrate.demo.fixed-clock=2026-09-10T15:12:23Z   # now+91d
curl :8080/api/v1/portfolio
```

→ `portfolio-stall-flip-2026-06-11.json`:
**`lifecycleStallCount: 1`** (flip 0→1), primary card `('Operational Risk', 'design', stalled=TRUE)`,
`unphased: 22` UNCHANGED — never folded into the stall count (§12.1),
`computedFrom.stallThresholdDays: 90`.

Dashboard (served bundle at `:8080/portfolio/dashboard`, Boot B) renders the LIVE numbers
at the BEACON_PORTFOLIO seam. Full screenshot-harness capture (`npm run screenshots`,
20 PNGs desktop+mobile) at `substrate/demo-screenshots/2026-06-11T15-17-13Z/`; the
operating-partner capture is preserved here as
`portfolio-dashboard-live-stats-2026-06-11.png`:
- "Lifecycle stalls 1 — 90+ days in current phase · 22 unphased (no lifecycle data)"
- "Agents in intervention 8 — Of 70 registered across portfolio"
- "Coordination signals 0" (a real, visible zero with per-source breakdown on the wire)
The strip carries `data-stats-source="live"`; an absent agent registry renders an
em-dash + "registry unavailable — counts withheld, not zero" (absent ≠ 0; pinned by
vitest + JUnit).

## Gates run

- `gradlew build` — GREEN (all modules; 395 app tests, engine/common/ingestion suites,
  ArchUnit governed-coverage incl. the three new `@Governed` verbs).
- `npm run type-check && lint && test -- --run && build` — GREEN (92 tests/13 files,
  incl. the new `portfolioData.test.ts` adapter deep-equals + absent-vs-zero pins).
- In-CI twin of this lever: `PortfolioDemoStallTest` (baseline 0 + satellites unphased)
  and `PortfolioDemoStallFlipTest` (REST-surface flip to 1 under a dynamic future clock).

## Honest scope notes

- Frontend live swap covers the S6 STATISTICS strip; the subsidiary roster/detail
  surfaces still render fixture subsidiaries — the per-subsidiary live swap rides the
  existing satellite-domain resolution (`agentData.resolveBeaconDomainId`) against
  `GET /portfolio/subsidiaries/{domainId}` as a follow-on slice (documented in
  `portfolioData.ts`).
- Demo isolation: demo domains form the portfolio ONLY on a demo-seeded boot
  (`substrate.demo.seed=true`); production posture (default) excludes them —
  `computedFrom.demoDomainsIncluded` states the posture.
- PG-only V8 behavior is CI-verified only (no Docker on this machine) — unrun is unrun.
