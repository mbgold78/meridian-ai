# M5-flag-flips — sub-milestone close review

Closes the M5-flag-flips sub-milestone. Chain-wide retrospective per the canonical closeout convention (`docs/conventions/00-README.md` § 1).

This sub-milestone landed: (a) Codex review findings F2-F5 from M5-subscription-routing chain (commits 1-4 — concurrent transaction safety, dispatch timeout watchdog, subprocess pipe drain, tester `commit_hash` pass-through); (b) `enable_engineer_supervisor` flag flip + engineer.yaml `## Compaction trigger` subsection (commit 5); (c) `enable_pm_supervisor` flag flip (commit 6, with the existing pm.yaml compaction wiring from M5-continuous-pm-driver commits 7+8 staying in place). Conftest autouse fixture absorbs the 31-file flag-flip cascade pre-emptively at commit 5.

**Decision density was structurally moderate but methodology-heavy** — chain established 6 new durable conventions (Conventions 12-17 promoted at this commit), 9 empirical instances of bidirectional verification-first discipline (#13 confirmed across 5 surface categories), 9 instances of methodology-encoded-as-test enforcement (Convention 17 with three sub-types), and surfaced 8 V2 architectural-evolution candidates from the codex clean-slate critique that consolidate at this commit alongside the prior degraded-state-recovery V2 entry.

The discipline observed at this chain crystallizes into a chapter-organized observations section (precedent: M5-continuous-pm-driver close, 2026-05-07; renewed here). The methodology-as-moat thesis matured: discipline is the product, not just the code.

---

## Chapter 1 — Commit chain narrative

Seven substantive commits + scope-reframe commit 0. Test count trajectory: 739 (chain start, post-M4d) → 790 (post-commit-6) → 790 (post-closeout commit 7; docs-only, no test additions). Net +51 tests through the chain.

| Commit | Hash | Description | Unique tests | F2.1 estimate | Sub-category |
|---|---|---|---|---|---|
| 0 | `52198cd` | chore: kickoff scope reframe — three deferral entries decoupled | 0 | 0 | docs-only |
| 1 | `4c1ab8b` | F2 contextvars fix (Codex finding 2; concurrent transaction safety) | ~5-6 | 8 | sentinel-heavy |
| 2 | `358d278` | F3 dispatch timeout watchdog (Codex finding 3) | ~5-6 | 8 | sentinel-heavy |
| 3 | `b5c553d` | F4 subprocess pipe drain (Codex finding 4; stdout=DEVNULL, stderr drained) | 24 | 12 | infrastructure-with-failure-isolation |
| 4 | `cd0f6bb` | F5 tester `commit_hash` pass-through (Codex finding 5; INSERT + COALESCE preservation) | ~6-8 | 8 | sentinel-heavy |
| 5 | `06787f1` | engineer-supervisor flag flip + engineer.yaml `## Compaction trigger` + conftest autouse fixture | 6 (smoke + yaml + 4 production-default) | 1-3 | config-flag-flip |
| 6 | `cf8bf81` | pm-supervisor flag flip (pm.yaml already wired; smoke + yaml + 2 production-default) | 4 | 1-3 | config-flag-flip |
| 7 | (this) | closeout doc + BACKLOG V2 consolidation + 6 convention promotions + memory correction | 0 | 0 | docs-only |

**Spec amendments shipped**:
- `config/agents/engineer.yaml` — `## Compaction trigger` subsection at commit 5 (signal_compaction at subscription_5h ≥ 80% with snapshot-freshness gate; millisecond-precision iteration_marker)
- `config/agents/pm.yaml` — NO amendment at this chain (compaction wiring shipped at M5-continuous-pm-driver commits 7+8; CQ-D resolution at commit 6 prep confirmed empirically)
- `BACKLOG.md` — Entries 1+2 added at commit 5 (operator-tunable heartbeat timeout; extended stale-snapshot escalation); Entry 3 manual paste at commit 5 (degraded-state recovery / V2 #9); Entry 1 expansion at commit 6 (PM-supervisor empirical confirmation); 8 V2 architectural-evolution candidates consolidated at commit 7 per CQ-F Reading A
- `docs/conventions/00-README.md` — 6 new conventions (12-17) at commit 7

**Migrations shipped**: none. M5-flag-flips chain is pure code (commits 1-4) + config-flag-flip (commits 5-6) + docs (commit 7); no schema work.

**Codex finding status**:
- Findings F2-F5 (from M5-subscription-routing round 1) **ALL RESOLVED** at commits 1-4 (4c1ab8b / 358d278 / b5c553d / cd0f6bb).
- No new code-review pass during this chain. A future Codex review on M5-flag-flips work is appropriate before β operational gate — particularly on the conftest autouse fixture pattern (potential blast radius across all tests) and the config-flag-flip pattern (production-default contracts).

---

## Chapter 2 — BACKLOG carry-forward

Three entries landed at commits 5+6; eight V2 architectural-evolution candidates consolidate at commit 7 alongside Entry 3 (forming 9 entries total in the new BACKLOG.md `## Phase 2 architectural-evolution candidates` section per CQ-F Reading A disposition).

**Existing entries (commits 5+6)**:

- **Entry 1: Operator-tunable heartbeat timeout via environment variable** (commit 5; expanded at commit 6 with PM-symmetry empirical confirmation). Trigger: first instance of operational data showing 30min default needs adjustment. Generalizes to both supervisors (engineer + pm) per commit-6 expansion. Touchpoints: `bridge/supervisor_base.py:274` + `bridge/engineer_supervisor.py:75` + `bridge/pm_supervisor.py:87` + main.py construction sites.
- **Entry 2: Extended stale-snapshot escalation for Engineer compaction trigger** (commit 5). Trigger: persistent stale-snapshot windows during operation. Cross-references V2 #9 governance-layer subsumption.
- **Entry 3: Degraded-state recovery / operator pacing control loop** (commit 5 manual paste; **migrated at commit 7 to V2 roadmap section as #9**). State-based recovery vs calendar-time rest. 12 signals + 12 recovery modes + 5-step escalation rule + 7 exit criteria + governance-layer placement.

**Drift-detection scan** (standard scope: spec docs + agent yamls + migration headers + relevant docstrings): two findings, both fixed in-flight or not requiring fix.
1. `project_m5_engineer_supervisor_flag_flip` memory's `MERIDIAN_ORCH_ENGINEER_HEARTBEAT_TIMEOUT_SECONDS` env-var claim — caught at commit 5 prep; corrected at session-end before commit 5 closed (memory now reflects hardcoded 30min default + BACKLOG Entry 1 cross-reference).
2. `BACKLOG.md` 8 V2 codex-critique candidates not yet present — caught at commit 7 prep; **resolved at this commit** via CQ-F Reading A consolidation.

**Sub-milestone-boundary internal-spec-consistency scan** (Convention 3 invocation): concept inventory for M5-flag-flips:
- `enable_engineer_supervisor` flag flip (commit 5): config.py:42 comment past-tense; `project_m5_engineer_supervisor_flag_flip` memory (env-var claim corrected); `engineer.yaml` § Compaction trigger; conftest autouse fixture; production-default contract tests.
- `enable_pm_supervisor` flag flip (commit 6): config.py:48 comment past-tense; `pm.yaml` § Compaction lifecycle (already wired pre-chain); conftest autouse fixture (pre-emptive coverage from commit 5); production-default contract tests.
- conftest autouse fixture: docstring + body comment past-tense at commit 6.

Scan finding: clean. Commits 5+6 amended adjacent surfaces (config.py comments, conftest docstring/body, BACKLOG Entry 1 PM-symmetry expansion) atomically with the runtime changes per Convention 12 (adjacent-hygiene-during-touch). No residual drift.

---

## Chapter 3 — Discipline-pattern observations

Chapter-organized per Convention 1 § "At ≥7 patterns, organize into chapter-level structure" rule (precedent: M5-continuous-pm-driver close, 2026-05-07; renewed here).

### 3.1 Methodology-as-moat — closeout-candidate observation #11

> Closeout discipline + BACKLOG watch-for-pattern + conventions + discipline-pattern observations is the executable methodology that produces clean primitives over time. The methodology IS the product distinction (continuity + control framing); architecture without methodology produces fast-and-loose code that needs rewriting in 8 weeks.

The codex clean-slate critique (2026-05-07) surfaced 8 V2 architectural-evolution candidates. The naive instinct was to evaluate "should we rewrite?" The right framing was: the methodology that surfaces these candidates with concrete triggers IS the product; rewriting code without preserving the methodology produces another 8-week throwaway.

**Disposition: Path A confirmed.** Continue current trajectory; V2 architectural questions become BACKLOG entries with concrete triggers, not rewrite directives. This commit consolidates the 8 candidates + Entry 3 into the BACKLOG `## Phase 2 architectural-evolution candidates` section.

### 3.2 Methodology-as-product extension — closeout-candidate observation #12

V2 #9 (degraded-state recovery / operator pacing control loop, Entry 3) operationalizes the methodology as governance-layer feature. Pacing discipline + verification-first + closeout discipline are parts of cumulative methodology; V2 #9 extends the methodology into the product as governance-layer feature. The methodology becomes a product surface.

State-based recovery vs calendar-time rest is a Phase 2 design principle that generalizes beyond pacing — applies to any quality-protection mechanism in continuously-running systems where "tomorrow" isn't meaningful as a primitive.

### 3.3 Bidirectional verification-first discipline — closeout-candidate observation #13 CONFIRMED

**Promote from candidate to confirmed pattern at this closeout.** Empirical anchor: 9 instances across 5 surface categories.

> Empirical verification at prep time corrects reviewer-Claude factual errors AND code-Claude implementation drift before they propagate to commits. Verification-first discipline (#8) is bidirectional — applies to reviewer-Claude's reasoning, not just code-Claude's implementation. Operates across multiple verification surfaces.

**Five surface categories** (CQ-B disposition: keep 5; collapse loses informative structural signal):

| Surface | Verification ritual | Empirical instances |
|---|---|---|
| **Code-level** | Read source files; grep; verify implementation against claim | 3 (F4 prep `claude --help`; F5 prep `complete_dispatch` clobber; commit 5 prep engineer.yaml signal_compaction missing) |
| **Memory-level** | Read memory files; check claims against current code | 2 (commit 5 prep env-var drift; commit 7 prep V2 BACKLOG state drift) |
| **Cross-system-level** | Verify document state across systems | 2 (commit 5 prep Entry 3 placeholder gap; commit 7 authoring Agent degradation section pre-existence at BACKLOG file tail) |
| **Test-infrastructure-level** | Verify test harness state propagates correctly to production | 1 (commit 5 conftest cascade discovery) |
| **Kickoff-assumption-level** | Verify kickoff prompt assumptions against current state | 1 (commit 6 prep symmetric-mirror falsified — pm.yaml CQ-D) |

Each surface has structurally distinct verification ritual; surface-collapse (e.g., merging memory + cross-system as "out-of-codebase verification") loses precision. **Asymmetric distribution is honest empirical data**: code-level dominates because code work is most frequent; memory-level is second because of how memory + carry-forward dynamics operate.

**Framework-refinement question pinned for future-Claude evaluation** (not resolved at this closeout): the surface-category framework may benefit from distinguishing **where-drift-detected** (the surface that exposed the drift) from **what-drift-category** (the underlying cause). Instance #9 (commit 7 authoring Agent degradation section pre-existence) fits cross-system-level as cross-document verification, but the underlying cause is reviewer-Claude memory drift — same shape as #4 (env-var) and #8 (V2 BACKLOG state). Future closeouts may evolve the framework to capture both axes. Don't restructure mid-authoring; pin the question for future-Claude.

### 3.4 Recursive verification-first discipline — closeout-candidate observation #16

**Distinct observation, not sub-instance of #13** (CQ-C disposition).

> Verification-first discipline operates RECURSIVELY across the methodology stack itself, not just on content surfaces. Kickoff prompts contain assumptions; prep checkpoints verify them; diff checkpoints verify prep claims; commit messages reflect diff state; memory updates reflect commit messages. Each layer is subject to verification at the next layer.

**Distinguishing #13 from #16**:
- #13: bidirectional verification operating across CONTENT surfaces (5 categories above)
- #16: recursive verification operating across METHODOLOGY STACK layers (kickoff → prep → diff → commit → memory)

Three concrete instances of #16:
1. **Commit 6 prep** anticipated CQ-D (pm.yaml structural divergence) at kickoff time → empirical verification at prep confirmed → resolution preserved through diff + commit.
2. **Commit 7 prep** explicitly marked 5 assumption surfaces "TO VERIFY" → verification at prep caught V2 BACKLOG state drift AND validated other 4 surfaces. Anticipation-of-empirical-finding succeeded.
3. **This closeout doc** itself is an instance of #16: the prep checkpoint anticipated chapter structure; closeout drafting verified against M5-continuous-pm-driver / M4d precedents; structure adapted where empirical reading found drift (e.g., 12-item Convention 1 pattern shape verified before drafting; 6-chapter framing per Mike's approval).

**The discipline becoming structurally embedded across the methodology stack** is the meta-pattern. Pin at closeout for future-Claude framing of how the methodology evolves.

### 3.5 Methodology-encoded-as-test-enforcement — closeout-candidate observation #14 (Convention 17)

**Promote to Convention 17 at this commit** (CQ-D disposition: ONE convention with three sub-type taxonomy).

9 instances across M5-flag-flips chain. Three distinguishable sub-types:

| Sub-type | Mechanism | Instances this chain | Anchor strength |
|---|---|---|---|
| **Contract enforcement** | Introspect function signatures via `inspect.signature` | 1 (F5 keyword-only contract test) | observation; not threshold |
| **Presence enforcement** | Read text artifacts (yaml, markdown) and assert load-bearing substring presence | 2 (commit 5 engineer.yaml content-assertion; commit 6 pm.yaml content-assertion) | threshold-of-two |
| **Invariant enforcement** | Assert defaults / validators behave per spec; negative-invariant tests | 6 (commit 5 production-default contract tests × 4; commit 6 production-default × 2 including no-validator-dependency) | threshold-of-two+ |

Different mechanisms; shared underlying thesis: encode methodology as test surface. Convention 17 promotes with all three sub-types named; chapter content distinguishes anchor strength explicitly. Future fourth instance with new mechanism extends sub-type list within Convention 17 rather than spawning Convention 18+.

### 3.6 Lineage notes for future-Claude

These items are non-obvious from the code alone and worth surfacing for cold reads.

- **Empirical instance #8 of #13 caught at commit 7 prep** (worked example of #13 + #16 recursive operation): the kickoff's "V2 architectural-evolution roadmap with 9 entries" framing implied all 9 were in BACKLOG.md; empirical verification at prep time caught that only 1 of 9 (Entry 3 manual paste) was actually in BACKLOG. The closeout-carry-forward memory states "8 candidates to be added at M5-flag-flips closeout commit 7" correctly; the kickoff's framing was off. CQ-F was created at prep to disposition placement; Reading A (consolidate) selected; 8 candidates land at this commit. Without prep-time empirical verification, closeout chapters would have referenced 8 non-existent BACKLOG entries — caught before propagating.

- **PM yaml's intentional structural asymmetry from engineer.yaml** (commit 6 CQ-D resolution): PM uses `pace_recommendation == 'conserve'` indirection (vs Engineer's literal `utilization >= 80`); fires at the same threshold via `tools/budget.py:_compute_pace_recommendation`'s `remaining < 20%` rule. PM has companion `write_pm_handoff` call before signal_compaction (Engineer has no handoff doc). PM has anti-flapping discipline (recent-handoff-staleness check + Mike-presence deferral); Engineer doesn't need one because Engineer's compaction is single-shot per cycle. Asymmetry is intentional architecture — reject any push for surface-symmetry refactor.

- **30min heartbeat is hardcoded at `supervisor_base.py:274` + `engineer_supervisor.py:75` + `pm_supervisor.py:87`**, NOT exposed as env var. Memory drift (`MERIDIAN_ORCH_ENGINEER_HEARTBEAT_TIMEOUT_SECONDS=1800` claim) was caught at commit 5 prep and corrected. BACKLOG Entry 1 covers env-var tunability for both supervisors with concrete trigger.

- **Conftest autouse fixture absorbs flag-flip cascade by design** (commit 5 unanticipated addition; per Convention 16). Production defaults flipped at commits 5+6 activate `substrate_working_directory` validator on every `Settings()` call (engineer-supervisor only; PM has no validator dependency). Tests don't exercise canonical-clone provisioning path — autouse env-var override restores pre-flag-flip test-time behavior. Pre-emptive coverage at commit 5 meant zero test-side churn at commit 6 — Convention 16 prevention-mode operating as designed.

- **Chain-summary test count trajectory of 739 → 790 (+51)** is dominated by F4 commit 3 (+24 unique tests for subprocess pipe drain infrastructure). Other commits: F2/F3/F5 sentinel-heavy at 5-8 unique each; commits 5+6 config-flag-flip at 4-6 unique each. Closeout commit 7 adds 0 tests (pure docs).

---

## Chapter 4 — Convention promotions

Six new conventions land at this commit, bringing the conventions doc total from 11 to 17. Detailed text lives in `docs/conventions/00-README.md` § 12-17.

### Convention 12: adjacent-hygiene-during-touch (PINNED)

Threshold-of-three reached during chain. Three+ empirical instances:
- F3 (commit 2): `_await_terminal` docstring update touched alongside `asyncio.timeout` addition
- F4 (commit 3): dispatcher.py module docstring + supervisor_base.py module docstring touched alongside subprocess pipe drain
- Commit 5: config.py:42 comment past-tense update alongside flag flip
- Commit 6: config.py:48 + conftest.py docstring/body + BACKLOG Entry 1 PM-symmetry expansion

**Rule**: when a commit's primary work touches a file or domain, fix small adjacent drift opportunistically (stale comments, version drift, past-tense corrections). Tight cap: only directly-adjacent drift; surfaced drift outside scope routes to BACKLOG or separate hygiene commit.

### Convention 13: finally-as-cleanup-anchor (PINNED)

Threshold-of-two fired at F4. Two empirical instances during M5-flag-flips:
- F3 (commit 2): `dispatch()` finally clause's race-protection re-check after `asyncio.timeout`
- F4 (commit 3): drain task cancellation lives in 5 EXISTING cleanup paths (`dispatch()` finally + LongRunningSupervisor's shutdown / _handle_heartbeat_timeout / _handle_subprocess_unexpected_exit / _handle_compaction_completed)

**Rule**: lifecycle cleanup operations leverage the existing `dispatch()`/supervisor finally + cleanup-path anchors rather than introducing parallel cleanup lifecycles. New cleanup operations attach to the existing anchor's pre-existing structure.

### Convention 14: production-code-surface-for-testability (PINNED)

Threshold-of-three reached during chain. Three empirical instances:
- M5-pm-mode-and-window-state commit 5: `token_store=None` defensive handling
- M5-pm-mode-and-window-state commit 7: `_run() → _run() + _run_lifespan_body(settings, shutdown)` refactor
- F4 (commit 3): `DrainingSubprocess` wrapper as public-shape concrete class + `dispatches_root: Path | None = None` constructor parameter

**Rule**: expand production-code surface for testability when the same surface serves a production-correctness purpose; reject expansions that exist purely for test-only purposes.

### Convention 15: operational-verification-before-config-flip (PROMOTING — detection-mode)

Threshold-of-two fired at commits 5+6 (CQ-A disposition).

**Rule**: When promoting a config flag from default-False to default-True at sub-milestone close, run the operational verification checklist at prep time before drafting the flip; commit-time scaffolding is smoke + yaml content-assertion + production-default contracts.

**Detection-mode methodology**: verification before commit catches drift; flag flip lands cleanly. Pairs with Convention 16 (prevention-mode).

### Convention 16: feature-flag-flip cascade (PROMOTING — prevention-mode)

Threshold-of-two stayed at two via prevention rather than detection (CQ-A disposition).

**Rule**: Anticipate flag-flip cascade. When production default flips activate validators previously dormant in test surfaces, add autouse fixture pre-emptively at first cascade. Future flag flips with same validator pattern then require zero test-side churn.

**Prevention-mode methodology**: pre-emptive mitigation prevents pattern recurrence. Convention 15 (verifies before flip) + Convention 16 (mitigates after) form complementary pair encoding methodology for high-impact config changes.

Empirical observation: at commit 5, conftest autouse fixture absorbed cascade for both `enable_engineer_supervisor` AND `enable_pm_supervisor` (pre-emptive coverage); commit 6 then required zero test-side churn for the cascade. Architectural success — pattern stayed at threshold-of-two via prevention, not detection.

### Convention 17: methodology-encoded-as-test (PROMOTING — three sub-types)

Threshold reached overwhelmingly: 9 instances across M5-flag-flips chain (CQ-D disposition).

**Rule**: Architectural decisions encoded in source-controlled artifacts (function signatures, yaml prompts, settings defaults) are protected against silent drift by tests that read those artifacts and assert on load-bearing properties. Three sub-types:
- **Contract enforcement**: introspect function signatures via `inspect.signature` (e.g., F5 keyword-only contract test)
- **Presence enforcement**: read text artifacts and assert substring presence (e.g., yaml content-assertion tests at commits 5+6)
- **Invariant enforcement**: assert defaults / validators behave per spec; negative-invariant tests (e.g., production-default tests; commit 6's no-validator-dependency negative-invariant)

Mechanism follows artifact shape. Future fourth instance with new mechanism extends sub-type list within Convention 17.

---

## Chapter 5 — V2 roadmap (Phase 2 architectural-evolution candidates)

9 entries consolidated at this commit per CQ-F Reading A disposition. Full text in BACKLOG.md `## Phase 2 architectural-evolution candidates` section. Phase 2 sequencing analysis below; entries in BACKLOG ordered by codex-critique original numbering (1-8) + #9 degraded-state-recovery (Entry 3 migration).

### Phase 2 sequencing analysis

Per CQ-E disposition, V2 #7 (event emission) is prerequisite for V2 #9 (degraded-state recovery — governance layer subscribes to event stream). Other dependency relationships informally surfaced:

| V2 # | Title | Phase 2 dependency |
|---|---|---|
| 1 | Command-handler layer | Foundation; substrate for #2, #5, #6 |
| 2 | Policy layer consolidation `can(actor, action, resource)` | Builds on #1 |
| 3 | Event-log-first architecture | Foundation; substrate for #7 |
| 4 | Actor uniformity (eliminate `human:mike` literal) | Standalone |
| 5 | Run/dispatch as first-class concept | Related to #1; loosely coupled |
| 6 | Explicit transaction ownership and concurrency contracts | Builds on #1 |
| 7 | Observation as structured event emission | Builds on #3; prereq for #9 |
| 8 | Project adapter abstraction | Standalone |
| 9 | Degraded-state recovery / operator pacing control loop | Consumes #7's events; refined by `## Agent degradation detection and recovery` sub-entry at BACKLOG file tail (reasoning-degradation telemetry sub-surface; Mike paste 2026-05-09; framed as sub-entry, not parallel V2 entry) |

**Sequencing recommendation** (deferred to Phase 2 prep; surfaced here for future-Claude):
- Foundations: #1 (command-handler) and #3 (event-log-first) ship first
- Derivatives on #1: #2, #5, #6
- Derivatives on #3 + #7: #9
- Standalones: #4, #8 (any time)

V2 #9 admits incremental phase: signal-tracking-via-direct-queries pre-V2 #7 (some of the 12 signals are queryable directly without event stream — compaction pressure, rate-limit failures). Full 12-signal subscription + 12-recovery-mode orchestration requires V2 #7 as foundation.

---

## Chapter 6 — Process metrics + build-time data point

### V1 build-time data point (closeout-candidate observation #15)

- **88+ commits** in V1 orchestrator (Meridian) build through M5-flag-flips closeout (counts vary slightly by interpretation; chain through cf8bf81 commit 6 = 6 substantive commits + commit 0 chore + this commit 7 closeout).
- **9.5 calendar days** elapsed from project start (2026-04-28) to commit 7 (2026-05-08).
- **~6-10 weeks conventional solo-dev compressed** into the 9.5 days.

**Compression source**: AI implementation speed × human architectural pressure × disciplined decision capture. **NOT dogfooding** — this is pre-dogfooding by simulation; **true dogfooding begins at M5-acceptance β** (per M4d closeout § Acceptance demo execution: β depends on Mike's interactive Claude Code session with real subprocess spawns + real substrate commits).

**Why this matters at closeout**: establishes the empirical base rate for the methodology-as-moat observation. The 6-10× compression isn't "AI is fast" — it's "AI fast + architectural pressure + decision capture compounds." Each of the 9 V2 BACKLOG entries above is an instance of decision capture creating Phase 2 optionality without spending Phase 1 budget on it.

### F2.1 framework refinement — 4 sub-categories empirically anchored

**Sub-category 1 — Sentinel-heavy fix** (low-end-of-band, 5-8 unique tests):
- F2 (commit 1): ~5-6 unique tests (contextvars sentinel coverage)
- F3 (commit 2): ~5-6 unique tests (timeout sentinel)
- F5 (commit 4): ~6-8 unique tests

**Sub-category 2 — Infrastructure-with-failure-isolation** (above-band, 20-30 unique tests):
- F4 (commit 3): 24 unique tests (13 stderr_drain + 11 default_launcher_pipe_drain). Cost of mechanical guardrail verification; not a defect.

**Sub-category 3 — Storage/migration** (band-position TBD; M4d data points contributed; M5-flag-flips shipped no migrations):
- M4d migrations data points exist; baseline calibrates over Phase 1 work.

**Sub-category 4 — Config-flag-flip** (below-band, 2-6 unique tests + production-default contracts):
- Commit 5: 6-7 test surface (2 unique smoke+yaml + 4 production-default contract tests + 1 conftest fixture)
- Commit 6: 4-5 test surface (3 unique smoke+yaml+production-default + small hygiene)
- Framework: "2-3 unique tests + production-default contracts (count varies by validator architecture asymmetries)."

**Sub-category 5 — Documentation-shaped commits** (observation candidate, NOT yet promoted):
- Commit 7 (this): 0 unique tests (pure docs: closeout doc + 6 conventions + BACKLOG V2 consolidation)
- 1 empirical data point — below threshold-of-two for sub-category framework refinement.
- Pin as observation: "if M5-acceptance closeout produces 0-5 unique tests with similar shape, sub-category 5 promotes."

### Drift between kickoff scope and landed scope

Three substantive divergences from kickoff scope:

1. **Commit 0 scope reframe** — pre-chain reframe decoupling three deferral entries from sub-milestone closeout. Captured in commit 0 chore commit; intended scope reduction.

2. **Conftest autouse fixture (commit 5)** — unanticipated addition needed to absorb 31-file flag-flip cascade. Surfaced at commit 5 gate-run when pytest -x revealed the validator activation. Convention 16 prevention-mode (this closeout's architectural-success observation) crystallized from the unanticipated addition.

3. **CQ-D fired at commit 6** — kickoff's "PLUS pm.yaml system_prompt amendment" assumption empirically falsified at prep time (pm.yaml signal_compaction wiring shipped at M5-continuous-pm-driver commits 7+8). Empirical instance #7 of #13. NO pm.yaml amendment at commit 6.

4. **CQ-F fired at commit 7** — kickoff's "V2 architectural-evolution roadmap with 9 entries" assumption empirically falsified at prep time (only 1 of 9 in BACKLOG.md). Empirical instance #8 of #13. 8 candidates land at this commit per Reading A consolidation.

5. **Instance #9 of #13 caught at commit 7 authoring time** — `## Agent degradation detection and recovery` section pre-existed at BACKLOG file tail (Mike paste 2026-05-09, between commit 6 close and commit 7 prep). Reviewer-Claude memory of the artifact's pre-existence drifted; commit 7 prep checkpoint did not surface it; verification at authoring time (post-edit BACKLOG sanity check) caught the section. Cross-system-level surface strengthens to 2 instances. Disposition: Reading A (preserve in place) per Mike's commit-5-protective-precedent extending to 2026-05-09 paste; closeout doc + BACKLOG amendments cross-reference the section as sub-entry of V2 #9.

The pattern: kickoff scope is provisional; verification-first discipline at prep + diff + authoring time refines scope before commit. Drift is detected (or prevented) at the next surface, not silently propagated.

---

## What's next

Sub-milestone 8 of 9 closes. Remaining M5 sub-milestones per the breakdown:

- **M5-acceptance** — operator-driven smoke test concluding M5; β operational gate (Mike's interactive Claude Code session with real subprocess spawns + real substrate commits). Verifier-subagent / Playwright / Chrome thread + β scope shape decisions live here. Phase 1 acceptance-coverage scan readiness check at this milestone per Convention 2.

**Cross-sub-milestone carry-forwards live** (BACKLOG.md):
- Entries 1+2 from this chain (operator-tunable heartbeat; extended stale-snapshot escalation)
- Entry 3 → migrated to V2 #9 in `## Phase 2 architectural-evolution candidates` section
- 8 codex-critique V2 entries → consolidated in same section

**Memory correction at this commit**: `project_m5_engineer_supervisor_flag_flip` env-var claim was already corrected at end of session pre-commit-5 close (via inline edit to memory file). Closeout cross-references the correction; no further memory edits needed at this commit.

The methodology-as-moat thesis (#11) + bidirectional verification-first discipline (#13 confirmed) + recursive verification-first (#16) + Convention 17 (methodology-encoded-as-test) are the most consequential cross-cutting items established during this chain. Future-Claude reading the chain process cold finds the conventions at `docs/conventions/00-README.md`; finds the discipline-pattern observations in this closeout's Chapter 3; finds the V2 roadmap in BACKLOG.md `## Phase 2 architectural-evolution candidates` section.
