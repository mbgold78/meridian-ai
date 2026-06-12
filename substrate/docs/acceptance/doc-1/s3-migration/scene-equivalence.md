# DOC-1 S3 — scene-equivalence notes: archived originals vs pipeline captures

**What was compared.** The 9 hand-captured originals (now at
`substrate/docs/acceptance/final/archive/`, captured 2026-06-11 **without a
fixed engine clock**, all viewport-cropped 1536x960) against the
pipeline-generated set (`substrate/docs/pipeline/build/captures/`, S2-verified
manifest `1be63f91…`, canonical pinned clock `2026-05-12T00:00:00Z`). Pixel
parity is explicitly NOT the criterion (plan §8 A3); this records scene-level
equivalence and every content difference found beyond date strings. Each pair
was inspected visually (full images plus zoomed crops of the data-bearing
regions).

**Global, deliberate differences (apply to several scenes):**

- **Viewport vs fullPage:** every original is a 1536x960 viewport crop; the
  pipeline captures 05 (1536x3400), 06 (1536x2200), 07 (1536x2400) and
  08 (1536x2000) as fullPage per their specs. The generated images are strict
  content supersets — the shared top region was compared crop-to-crop in each
  case; the extra zones (dashboard opportunity/cascade/roster sections,
  cascade action timeline + footer summary, contract enforcement + actions
  footer, operations findings cards) exist only in the generated set.
- **Capture-day wall-clock dates:** date strings that render from
  `@CreationTimestamp`-style seed rows show the capture day (originals
  6/11/2026, pipeline run 6/12/2026), NOT the pinned engine clock — see the
  scene 02 caveat below.

| Scene | Verdict | Detail |
|---|---|---|
| 01 living-canvas | **Equivalent** | Identical layout, node set, stats strip (12 systems / 26 manual procs / 21 open risks, 11 high / $945K), Project health 75 + 2 blockers, legend, chat bar + suggestion chips. Only the SMIL flow-edge dots sit at different points along their paths (pipeline pins them at t=0; the original caught them mid-flight). |
| 02 designer | **Equivalent rows; see clock caveat** | Same 6 rows (D-001..D-005 + the CONTRACT_ACTIVATED ledger anchor), same type/status chips, same affected counts, same Run Impact Analysis affordances. DATE column: original 6/11/2026, generated 6/12/2026 — the **capture day**, not the pinned 5/12/2026. The decision dates render wall-clock seed timestamps, which the plan (§5) assumed were not UI-rendered. Within a single verify run this is byte-stable (S2 proved it across 4 same-day boots), but manifests WILL drift across calendar days — flagged in the slice report as a follow-on for the determinism contract. |
| 03 capability-designer | **Identical** | Tree (Customer Onboarding → KYC Verification), 2-capabilities count, Draft from Universe / Create Capability, empty-state detail pane. |
| 04 portfolio-landing | **CONTENT DIFFERS (product copy fix landed)** | Hero, both vantage cards, subsidiary select identical. The footer differs: the original still carries the false fixture-era claim "Every subsidiary, capability, agent, and finding shown here is fixture data. No backend is contacted; AI responses are pre-canned" (vv Finding 2); the generated shows the corrected copy "Beacon Industries is a fictional demonstration portfolio. Cascades, contracts, agents, and portfolio statistics are served live by the substrate engines…". The fix anticipated by plan §7 FILE-5 landed between the two captures; the pipeline deliberately pins current copy. Not a regression — the original footer was documented as false when it was captured. |
| 05 portfolio-dashboard | **CONTENT DIFFERS (clock-derived stat)** | Header, zone structure, phase grid (PLUG-IN 6 / DESIGN 5 / MAP 4 / PLAN 3 / EXECUTE 2 / MONITOR 1 / AUTOMATE 1, same subsidiaries) identical. Three of four signal cards identical (cross-portfolio 5, agents-in-intervention 8 of 70, lifecycle stalls 0 + "22 unphased"). **Coordination Signals reads 1 in the generated capture vs 3 in the original** — it is a time-windowed metric ("Cross-sub patterns AI surfaced this week") evaluated against the pinned clock 2026-05-12 instead of the original's wall clock 2026-06-11. Same surface, same wiring; different deterministic value under the canonical instant. |
| 06 cascade-detail | **Identical content** | Headline critique, acceptance-criteria panel, L0/L1/L2 level rows with CASCADED/CASCADED/DECLINED chips, decline reasoning, and the **seed-absolute** dates (Apr 8 / Apr 11 / Apr 22 2026) match exactly. Generated fullPage additionally shows the action timeline (#1 Issued → #2 Cascaded → #3 Declined) the original crop cut off. |
| 07 contract-detail | **Identical content** | "Proposed Apr 1 2026" (seed-absolute), estimated value $1.2M, parties/commitments verbatim. Generated fullPage additionally shows the enforcement section and inert Review/Decline footer. |
| 08 subsidiary-operations | **Equivalent + deliberate pre-step** | Shared region identical (header $350M / 620 / Indianapolis IN, Plan-phase strip, fleet stats 5 active / 1 intervention / 2 findings, same agent rows and lifecycle badges). The generated capture has the Pre-Visit Intake Assistant **deployment panel expanded** — the spec's documented pre-step (plan §3 worked example), absent from the original. FullPage additionally shows all 5 agent rows + both open-finding cards. |
| 08a | **NOT REPRODUCED — deliberate (FILE-6)** | The original documents a since-fixed bug: a fully blank page at the guessable URL `/portfolio/subsidiary/curasync-clinical/operations`. The catch-all NotFound route has landed, so the blank scene is **irreproducible**. The pipeline captures the fixed 404 panel ("There is nothing at /portfolio/subsidiary/curasync-clinical/operations" + recovery links) at the same URL, output renamed `08a-operations-path-segment-notfound.png`. The original BLANK PNG is retained in the archive as the bug-repro record. |

**Bottom line.** No scene was quietly replaced by a different scene. Two
scenes carry real content differences beyond date strings — 04 (footer copy
fixed in the product) and 05 (time-windowed coordination-signals stat: 1 under
the canonical clock vs 3 at the original wall clock) — and 08a is re-specced,
not reproduced, per plan FILE-6. All three are deliberate, explained above,
and surfaced in the S3 slice report rather than shipped silently.
