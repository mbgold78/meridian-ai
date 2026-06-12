# Contributing to the guides + capture pipeline (DOC-1)

The screenshots in these guides are **build artifacts, not files anyone edits**.
They are produced by the `:docs` capture pipeline (`substrate/docs/pipeline/`)
from declarative YAML specs, against a deterministically seeded app boot, and
the CI gate re-proves byte-identical output on every PR. That design carries
one obligation for every contributor:

## The rule

> **A PR that changes a user-visible surface updates the affected guide task
> topic AND its capture spec in the same PR.**

"User-visible surface" means anything a capture spec selects or shows: a
`data-testid` / `data-*` attribute, an interaction a spec's `preSteps` drive,
copy or layout inside a captured scene.

### How the rule is enforced

1. **Mechanically (the gate).** The capture suite doubles as the UI regression
   suite. Rename or remove a documented selector, or break a documented
   interaction, and the `docs-verify` CI job fails naming exactly what to fix:

   ```
   DOCS-VERIFY FAIL topic=operator/06-agent-lifecycle#observe-in-ui spec=subsidiary-operations selector=[data-testid="agent-row"][data-agent-key="a-pre-visit"] (wait failed)
   ```

   `topic` is the guide section to update; `spec` is the YAML file in
   `substrate/docs/pipeline/specs/` to fix alongside it. This check is
   unskippable — it runs on every PR touching `substrate/**`.

2. **Advisorily (the path hint).** If a PR touches `substrate/frontend/src/**`
   without touching any file under `substrate/docs/pipeline/specs/**`, a soft
   CI step (`scripts/spec-path-hint.mjs`) annotates the run with the topics
   whose specs reference selectors appearing in the touched files (both the
   merge-base and HEAD side, so deletions are caught). It is a warning, never
   a gate — the gate is #1.

3. **This document.**

Changed pixels without changed selectors (pure copy/style changes inside a
captured scene) do not fail the gate — the gate proves *determinism and
selector integrity*, not pixel-freeze against history. Re-running the pipeline
regenerates the new canonical captures; that is the point of the design.

## How to add a capture spec

Specs are **data** — the runner interprets them; there is no per-scene code.
Drop a `<id>.yaml` into `substrate/docs/pipeline/specs/` (schema:
`src/lib/schema.js`, worked examples: plan §3 and any shipped spec):

| Field | Notes |
|---|---|
| `id`, `title`, `topic` | `topic` is the guide backlink named in failures — keep it pointing at a real heading (`dir/file#anchor`). |
| `route` | SPA route; `{var}` placeholders filled by `resolve` (API id-resolution — never hardcode per-boot UUIDs, ruling P-3). |
| `persona` | Applied by seeding `meridian-persona-level` in localStorage via `addInitScript` (ruling P-5) — chip and wire header stay atomic. |
| `waits` | Ordered readiness gates (`api:` poll or `selector:`). **Every spec needs a live-mode assertion** — a selector matching `data-*source/provenance*="live"` — or an explicit `liveModeExempt: <reason>`; the frontend's `retry: false` data hooks silently fall back to fixtures, and a spec without this assertion can freeze fallback UI into the docs. |
| `preSteps` | `click:` / `fill:` / `hover:` / `waitSelector:` — prefer `data-testid` selectors. |
| `capture` | `fullPage` or `element` + selector. |
| `annotations` | Numbered callouts anchored to **selectors** (positions computed from live `boundingBox()` at capture time, never hardcoded pixels). |
| `determinism` | `static` / `seeded` / `animated` — `animated` turns on the CSS animation kill + SMIL freeze + is mandatory for canvas-family scenes. |
| `status` | `active` (default, gated) / `blocked` (pre-written against RESERVED registry names — skipped with a visible count) / `manual-job` (workflow_dispatch lane only). Non-active requires `statusReason`. |
| `allowedApiFailures` | Expected, deterministic non-2xx app responses (substring + written reason). Anything unmatched fails the capture loudly. |
| `runtimeAuth` | Rare — injects `window.__SUBSTRATE_AUTH__` (the frontend's runtime auth-config seam) before app scripts run. Used only by the manual OIDC lane; the jar's embedded bundle is built without `VITE_*` auth env and resolves `anonymous` otherwise. |

Then reference the capture from a guide topic with `{{capture:<id>}}` —
`docsAssemble` resolves it (and fails loudly if the spec is not `active`).

## Running locally

From `substrate/` (Windows: `.\gradlew.bat`, elsewhere `./gradlew`):

```
gradlew docsCapture                # boot + capture every active spec -> docs/pipeline/build/captures
gradlew docsVerify                 # the gate: two full sweeps, byte-compared SHA-256 manifests
gradlew docsVerify -PfreshBoots    # same, but the second sweep gets a fresh JVM boot (new H2, new UUIDs) — what CI runs
gradlew docsAssemble               # resolve capture placeholders into build/manual + link-check all doc image refs
gradlew docsCapture -PdocsSpec=<id>                      # one spec (explicit selection also overrides a non-active skip)
gradlew docsCapture -PdocsSpec=<id> -PdocsBaseUrl=<url>  # attach to an already-running instance (debugging; skips boot + warm-up)
```

First run downloads the pipeline's own pinned Node + Chromium; no manual
setup. Exit codes: `0` ok, `2` environment/boot, `3` regression (schema or
selector — the `DOCS-VERIFY FAIL` format above), `4` determinism (manifests
differ), `5` assemble (broken placeholder/link).

**Windows caveat (ratified D-A): within-platform only.** `docsVerify` on
Windows proves the suite is self-deterministic on Windows; **Linux CI output
is the canonical baseline**. Never compare a Windows hash manifest to a Linux
one — DirectWrite vs FreeType glyph rasterization makes cross-platform
byte-identity unachievable for text-bearing pages by construction. Hashes in
committed acceptance transcripts are platform-local evidence.

## The determinism contract (what makes two runs byte-identical)

The gate's claim is strong — two fresh boots, byte-identical PNGs — and rests
on every one of these. Do not weaken one without re-running `docsVerify`
twice locally:

- **Pinned browser:** `@playwright/test` is pinned exact (`1.59.1`) in
  `docs/pipeline/package.json`, **in lockstep with
  `substrate/frontend/package.json`** (decision D-B's accepted cost). Bump
  both in the same PR or neither.
- **Pinned engine clock:** the app boots with
  `--substrate.demo.fixed-clock=2026-05-12T00:00:00Z` (`@Primary
  Clock.fixed`, UTC) — the canonical capture instant (ruling P-4).
- **Seeded data:** demo seed runs in ONE outer transaction; readiness is an
  API poll (`GET /api/v1/domains` until count == 23), never a sleep — health
  `UP` is not "seeded".
- **Pinned render context:** `locale en-US`, `timezoneId UTC`, viewport
  1536x960, `deviceScaleFactor 1`, `colorScheme dark`, plus the deterministic
  Chromium raster flags in `pipeline.config.yaml` (`--disable-gpu`,
  `--disable-partial-raster`, `--disable-lcd-text`, srgb — each one is an
  empirical S1/S2 finding, documented inline).
- **Fonts:** the Google Fonts CDN is intercepted and served from vendored
  copies (`assets/fonts/`); capture waits on `document.fonts.ready`.
- **Animation handling:** `determinism: animated` injects the CSS
  animation/transition kill and freezes SMIL clocks
  (`svg.pauseAnimations()` + `setCurrentTime(0)`, re-applied each settle
  attempt).
- **Warm-up sweep:** each boot gets one full DISCARDED capture sweep first —
  cold-app first-session text rasterization differs reproducibly on one
  canvas surface; warm-state pixels are canonical.
- **Settle discipline:** a capture is accepted only after 3 consecutive
  byte-identical screenshots at rAF spacing with zero in-flight app API
  requests; any FAILED app API request fails the spec (unless listed in
  `allowedApiFailures`) because the frontend's `retry: false` hooks would
  freeze fallback UI into the PNG.

## The storage-key contract (ruling P-5)

The runner pre-seeds localStorage via `addInitScript` before any app script
runs. These keys are a **documented contract between the frontend and the
pipeline** — renaming them in frontend code breaks every capture and the
docs gate; do it only deliberately, updating `pipeline.config.yaml`
(`storage.personaKey`) and the affected specs in the same PR:

| Key | Role |
|---|---|
| `meridian-persona-level` | Persona seeding for EVERY spec — sets the rendered chip and the `X-Persona-Level` wire header atomically. Header-only injection is wrong by construction. |
| `substrate-canvas-welcomed-v1` | Suppresses the canvas welcome overlay (preferred over clicking it away). |
| `substrate-workspace-domain-id` | Workspace pin; its value is a per-boot domain UUID — specs fill it via `resolve`, never hardcode. |
| `meridian-theme` | Theme pin where a spec needs it. |

The frontend sources of these keys already carry do-not-rename deviation
notes; this table is the pipeline-side half of that contract.

## The clock-skew note (ruling P-4)

Two clocks exist and they deliberately disagree:

- **Backend:** `substrate.demo.fixed-clock=2026-05-12T00:00:00Z` (engine,
  monitors, stall math — `2026-05-12` is permanently in the past relative to
  boot, so `lifecycleStallCount` stays 0 forever).
- **Frontend:** `FIXTURE_CLOCK`, compile-time `2026-04-26` (`clock.ts`); the
  live `setPortfolioClock` seam exists but is **not armed**.

The 16-day skew is harmless — portfolio date strings render from fixture/seed
absolutes, and relative strings ("Surfaced N days ago") are
FIXTURE_CLOCK-deterministic — but it is an invariant, not an accident. Do not
"fix" one clock to match the other in passing: alignment is a FILED follow-up
that rides the S15 live-clock swap (see `docs/acceptance/doc-1/FILED.md`).
The stall-flip operator scene is the one exception: it runs a separate lane
whose boot instant is computed (boot+91d), never hardcoded.

## The selector-registry claim process (ruling P-2)

`substrate/docs/pipeline/specs/selector-registry.yaml` reserves testid /
data-attribute names for surfaces that **do not exist yet** (no inert DOM
stubbing — that would invent product surface). If your feature PR builds one
of those surfaces, you MUST, in that same PR:

1. use the reserved name **exactly** as registered (`data-testid` /
   `data-*` spelling);
2. move the entry from `reserved:` to `shipped:` in the registry;
3. flip the paired pre-written capture spec from `status: blocked` to
   `active` (and make it pass — run `gradlew docsVerify` locally);
4. replace the `status: blocked-on-ui` stub topic in
   `docs/guides/{coo,operator}/not-yet/` with the real task topic.

From that merge on, the docs gate protects your new surface mechanically.
Never silently rename a registered name — stub topics and blocked specs spell
them, and the path-hint step keys on the registry.
