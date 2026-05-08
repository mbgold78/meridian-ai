# M5-continuous-pm-driver — sub-milestone close review

Closes the M5-continuous-pm-driver sub-milestone. Chain-wide retrospective per the canonical closeout convention (`docs/conventions/00-README.md` § 1).

This sub-milestone landed PM's continuous-mode loop infrastructure: workflow_id α-denormalization, narration tier 1 (pm_current_focus + pm_spin_rationale_log), audit-sampling tier 1 (audit_sample_log), effort plumbing, the loop driver core (get_loop_state + wait_for_next_tick), the compaction lifecycle (handoff via tools/pm_handoff.py + signal_compaction PM widening), and the e2e + closeout work.

**Decision density was materially higher than M5-subscription-routing** — the chain established 5+ new durable conventions (triggering the convention-doc migration during the chain itself), surfaced 6 bidirectional-discipline invocations across 4 distinct direction-shapes, and validated the F2.1 commit-shape-correlation framework across 9 commit data points. The Discipline-pattern observations section uses chapter-level organization per a precedent set at this close (Q1 Reading B disposition).

## Chain summary

Nine commits. Test count trajectory: 433 (chain start) → ~594 (post-close-review). Net +161 collected; +124 unique across the chain.

| Commit | Hash | Description | Unique tests | F2.1 estimate | Divergence |
|---|---|---|---|---|---|
| 1 | `ff47573` | workflow_id α-denormalization in `record_api_call` wrapper | 9 | 8 | +13% |
| 2 | `b1ad27a` | `pm_current_focus` table + write/read API + completion-validation audit event-kind | 25 | 14 | +79% |
| 3 | `433cef5` | `pm_spin_rationale_log` table + writer + closed-enum sentinel | 11 | 8 | +37% |
| 4 | `1e323ca` | `audit_sample_log` table + sampling-percentage config + async sampler scaffold | 21 | 10 | +110% |
| 5 | `71d1e86` | Effort plumbing (yaml field + supervisor + dispatcher pass `--effort`) | 7 | 9 | -22% |
| 6 | `1eeea94` | Hybrid spec amendment (Opus 4.7 effort + thinking addendum + 02/07/11 merges) | 0 | 0 | n/a (docs) |
| 7 | `af25497` | Continuous loop driver core (architectural keystone) | 16 | 18 | -11% |
| 8 | `68f0967` | Compaction lifecycle + handoff via tools/pm_handoff.py + signal_compaction PM widening | 29 | 14 | +107% |
| 9 | (this) | E2E integration tests + drift-scan + internal-spec-consistency scan + closeout | 3 | 20 | -85% |

**Trajectory pattern** (validated across 9 data points; chapter 5 below): test counts correlate with commit shape, not commit number.

## Spec amendments shipped

- **`docs/kickoff/02-architecture.md`** — small reference to PM compaction (commit 8, F9.0 Reading Y).
- **`docs/kickoff/05-data-model.md`** — was amended at M5-subscription-routing close; no further amendments this chain.
- **`docs/kickoff/06-mcp-tools-phase-one.md`** — extensive (commits 1, 2, 3, 4, 7, 8):
  - `RecordApiCallInput.workflow_id` α-denormalization framing (commit 1).
  - `pm_current_focus` MCP tool entries (`declare_current_focus`, `complete_current_focus`, `get_current_focus`) per F3 dispositions (commit 2).
  - `record_spin_rationale` entry + closed-enum decision/rationale_tag taxonomy (commit 3).
  - `record_sampleable_event` entry + closed-enum source_event_kind taxonomy (commit 4).
  - `wait_for_next_tick` + `get_loop_state` entries; first server-side blocking MCP tool flag (commit 7).
  - `write_pm_handoff` + `get_most_recent_handoff` entries; `signal_compaction` permission widening to PM (commit 8, F9.0 Reading Y).
- **`docs/kickoff/07-agent-configs-phase-one.md`** — agent yaml `effort` field convention (commit 5/6, F1.3 effort plumbing).
- **`docs/kickoff/08-implementation-plan.md`** — M5 deliverables migration bullets (commits 1, 2, 3, 4).
- **`docs/kickoff/11-engineer-bridge.md`** — line 753 PMSupervisor description amended; new § PM compaction subsection (commit 8, F9.0 Reading Y); cross-references to `tools/pm_handoff.py` and `Settings.pm_handoff_dir` plumbing.
- **`docs/kickoff/opus-4-7-effort-and-thinking-addendum.md`** — NEW addendum doc (commit 6, F1.3); full Anthropic-supported effort taxonomy + thinking budget framing for PM xhigh effort.
- **`config/agents/pm.yaml`** — `effort: xhigh` field (commit 5); continuous-mode iteration loop section (commit 7); compaction lifecycle section (commit 8); allowlist extensions for all new tools.
- **`bridge/supervisor_base.py`** + **`bridge/engineer_supervisor.py`** docstrings — amended at commit 8 (F9.0 Reading Y) to align with subprocess-side handoff-read disposition.

## Migrations shipped

- **`0010_pm_current_focus.sql`** (commit 2) — pm_current_focus table per F3.6 dispositions; closed-enum output_type CHECK; nullable bound_workflow_id (F3.6.B Q3 Reading B); idx_pm_focus_active partial index.
- **`0011_pm_spin_rationale_log.sql`** (commit 3) — pm_spin_rationale_log table per F4 dispositions; closed-enum decision + rationale_tag CHECK constraints.
- **`0012_audit_sample_log.sql`** (commit 4) — audit_sample_log table per F5 dispositions; closed-enum source_event_kind + re_eval_state CHECK constraints; composite index on `(re_eval_state, sampled_at)` for the future async re-evaluator's pending-row scan.

Three migrations total. Schema-version end-state: 12.

## New cross-layer single-source-of-truth instances

Compounded the "derive don't duplicate" pattern from prior sub-milestones:

- **`pm_focus.OutputType`** Literal + matching CHECK (`storage/sql/0010_pm_current_focus.sql`) per F3.6.B Q1 Reading A. Sentinel test `test_pm_current_focus_output_type_check_constraint` walks all five enum values; locks Python Literal + DB CHECK alignment.
- **`pm_spin.Decision`** + **`pm_spin.RationaleTag`** Literals + matching CHECKs (`storage/sql/0011_pm_spin_rationale_log.sql`) per F4 Q2 Reading A + Q3 Reading A. Sentinel tests walk all enum values for both columns.
- **`audit/sampler.SourceEventKind`** + **`audit/sampler.ReEvalState`** Literals + matching CHECKs (`storage/sql/0012_audit_sample_log.sql`) per F5.6.B Q1 Reading A + F5 Q5 Reading A. Sentinel tests walk all enum values.
- **`agents/config.Effort`** Literal (`agents/config.py`) per F6.6.B Q1 Reading A — full Anthropic-supported five-value set (`low / medium / high / xhigh / max`) with operator-agency reasoning (yaml field is operator tuning surface; full set admits exploration).
- **Closure-captured-callable plumbing pattern graduated from watch-for-pattern to convention.** Five instances across the chain: `get_oauth_usage_snapshot` (M5-pm-mode-and-window-state precedent); `Sampler` instance (commit 4); `get_pm_loop_tick_seconds` (commit 7); `get_pm_handoff_dir` (commit 8); `get_pm_supervisor` (commit 8). Pattern is now established convention; future Settings-driven config flows through main.py lifespan via closure to tools/-layer consumers without bridge → tools imports.

## Watch-for-pattern outcomes

Three threshold patterns at chain start; outcomes:

| Pattern | Pre-chain | End-of-chain | Action |
|---|---|---|---|
| `read_current_X` rowid-DESC helper | 1 (`read_current_pm_mode`) | 2 (+ `read_current_focus` from commit 2) | Watch continues at 2. Threshold-of-three did not trip during chain. F4.5.2 reframe (Convention 4 in conventions doc): trigger-to-evaluate, not mandate-to-extract. Filesystem-by-timestamp pattern (commit 8, `read_most_recent_handoff`) is a different abstraction unit per F9.7.B; doesn't count as third instance. |
| `bridge/` → `tools/` cross-layer imports | 2 | 2 | Unchanged. Commit 8 F9.0 Reading Y disposition kept supervisor unchanged at compaction boundary; no new bridge → tools imports. |
| `token_store=None` defensive sites | 2 | 2 | Unchanged through chain. |
| Production-code-surface-for-testability | 2 | 2 | Unchanged. Commit 7 Q6-A monkeypatch-asyncio.sleep disposition kept production code clean (no explicit clock-injection seam). Commit 8 closure-captured `get_pm_handoff_dir` is convention-conforming, not testability surface. |
| Closed-enum CHECK + sentinel test | 4 (mode, triggered_by, dispatches.status, output_type from commit 2) | 9+ (added: rationale_tag, decision, source_event_kind, re_eval_state, effort) | **Graduated to convention.** No longer requires watch-for-pattern tracking — closed-enum + Literal + sentinel test is the standard shape for finite-taxonomy schema columns. (F2.5 closeout-staging note from commit 4, 2026-05-05.) |
| Closure-captured callable for tools/-layer config | 1 (`get_oauth_usage_snapshot` from M5-pm-mode-and-window-state) | 5+ | **Graduated to convention** during chain. Convention-conforming additions don't require watch tracking. |

New observations (single instance — patterns added during chain):

- **Server-side blocking MCP tool**: first instance `wait_for_next_tick` (commit 7). Flagged in `BACKLOG.md` § Architectural patterns to review for second-instance evaluation. Pattern review only matters when second blocking-MCP tool lands; not yet.
- **Filesystem-most-recent-by-timestamp**: first instance `read_most_recent_handoff` (commit 8). Distinct abstraction unit from SQL-rowid-DESC (different correctness conditions: rowid-monotonicity vs ISO-timestamp-lexical-sort); no immediate watch warranted.
- **Code-inspection sentinel**: 2 instances (commit 7's Q4 + Q9 invariant pins). Convention 9 in conventions doc captures the Mode 1 / Mode 2 categorization + (b)-revisit trigger. Both sentinels stayed green through commits 7-9 (no Mode 1 or Mode 2 fires).

## Codex finding status

**No code-review pass during this chain.** Codex review findings round 1 was M5-subscription-routing-scoped (commit 1 of that chain); Findings 2-5 are deferred to M5-flag-flips per BACKLOG. No new findings to track here.

A future code-review pass on M5-continuous-pm-driver work is appropriate before M5-flag-flips closes — particularly on the supervisor base layer's compaction-completed action role-prefix derivation (commit 8) and the closure-captured-callable pattern (now 5+ instances).

## Lineage notes for future-Claude

These items are non-obvious from the code alone and worth surfacing for cold reads.

- **F9.0 Reading Y disposition (subprocess-side handoff-read) preserves M5-pm-supervisor-base P-2 Reading B.** The architectural decision to keep `PMSupervisor._reconstruct_state` as no-op + place handoff-read in `tools/pm_handoff.py` was load-bearing at commit 8; defended on substrate-evolution-decoupling (defense (4)) + recency-and-deliberateness of the M5-pm-supervisor-base disposition. Five spec sources (`bridge/supervisor_base.py:196` + `:284-298`; `bridge/engineer_supervisor.py:23` + `:152-154`; `docs/kickoff/11-engineer-bridge.md:753`) had described daemon-side override; commit 8 amended all atomically per `feedback_spec_amendment_runtime_drift`. A future revision wanting daemon-side handoff-doc reading would need to remove `test_reconstruct_state_is_noop_at_initial_spawn_and_respawn` invariant test + write a new spec — explicit re-litigation, not silent accretion.

- **`signal_compaction` permission widened from engineer-only to engineer + pm at commit 8.** F9.2 Q3 Reading A: the supervisor-level compaction lifecycle is genuinely identical for Engineer and PM at the base layer. The wrapper resolves caller_role to engineer-supervisor or pm-supervisor; the handler is supervisor-instance-agnostic but role-aware for the audit-action namespace (`engineer.compaction_triggered` vs `pm.compaction_triggered`). The watchdog's compaction-completed audit derives action from `_token_role()` — symmetric `pm.compaction_completed` for PMSupervisor. Future supervisor types (if any) inherit this role-derived audit shape automatically.

- **Cadence baseline 60s vs 30s decision (F8.2 Q2-B at commit 7).** The carry-forward from commits 3-4 anticipated 30s cadence as baseline; commit 7 dispositioned 60s on conservative-then-tune-down framing. Volume framings from commits 3-4 (pm_spin_rationale_log row volume; sampler-trigger MCP call volume) hold under 60s with margin. Re-evaluation trigger: empirical data showing PM under-responsiveness at 60s warrants tune-down.

- **Compaction is once-per-5h-cycle event under healthy operation.** F9.2 Q9-A disposition: per-tick write count at conserve-trigger ticks spikes to 3-5 but average per-tick write count effectively unchanged from commit 7 baseline (1-3 writes per typical tick). F8.2 Q10 write-tool aggregation rejection holds. Re-evaluation reactivates only if empirical operation shows compaction firing more frequently than once per 5h cycle.

- **F4.5.2 reframe of threshold-of-three** (commit 3 prep, 2026-05-04). Trigger-to-evaluate, not mandate-to-extract. Surface similarity does not imply shared semantic load. Frames the May 3 UTC-coercion precedent correctly (extraction landed because abstraction unit was load-bearing semantic logic, not just shared SQL shape). Applied at commit 8 F9.7.B Q8: filesystem-by-timestamp + SQL-rowid-DESC have different correctness conditions (filename-convention vs rowid-monotonicity); no shared-helper extraction warranted. Promoted to Convention 4 in conventions doc.

- **Decision density correlates with sub-milestone architectural complexity.** This chain established 5+ durable conventions vs M5-subscription-routing's 0-1. Convention-doc migration trigger fired during chain. Single data point — not yet pinned as predictive pattern (Convention 9-adjacent observation in conventions doc).

- **F2.1 trajectory pattern is now well-grounded across 9 data points** (commits 1-9). Storage-layer commits overshoot estimates; plumbing/integration tracks; spec-amendment is zero by design; closing commit undershoots. See chapter 5 of Discipline-pattern observations below.

## Address-now items routed forward

All items either resolved during the chain or properly deferred:

- ✓ workflow_id population protocol — resolved at commit 1 (α-denormalization).
- ✓ pm_current_focus + completion-validation audit event-kind — resolved at commit 2.
- ✓ pm_spin_rationale_log + closed-enum rationale_tag — resolved at commit 3.
- ✓ audit_sample_log + sampling-percentage config + async sampler scaffold (writer side) — resolved at commit 4. Re-evaluator deferred per BACKLOG cross-sub-milestone carry-forward (destination updated 2026-05-07: post-M4d operational use; runtime-data-driven trigger).
- ✓ Effort plumbing (agent yaml + supervisor + dispatcher) — resolved at commit 5.
- ✓ Hybrid spec amendment for Opus 4.7 effort + thinking — resolved at commit 6.
- ✓ Continuous loop driver core — resolved at commit 7.
- ✓ Compaction lifecycle + handoff + signal_compaction widening — resolved at commit 8.
- ✓ Convention-doc migration — **resolved at commit 9** (this commit). Migrated to `docs/conventions/00-README.md` per the discrete trigger that fired during chain.
- ✓ task_type protocol resolution — **deferred to M4d-task-management-surface** per F2.1 commit-1 contradiction surface (2026-05-03). Not in M5-continuous-pm-driver scope after the rollback.
- ✓ audit_sample_log re-evaluator — **deferred** to focused-observability sub-milestone (post-M4d operational use; runtime-data-driven trigger). BACKLOG'd at commit 4; destination updated 2026-05-07.
- (still deferred) `test_tool_activity_writer.py` rename — not touched this chain.
- (still deferred) `complete_dispatch` transactional parity — not touched this chain.
- (still deferred) Verify `MODEL_PRICING` before M5-flag-flips — operator workflow.

Nothing else address-now post-chain.

## Drift-detection scan (standard scope)

Scope: spec docs + agent yamls + migration headers + relevant docstrings (`tools/pm_focus.py`, `tools/pm_spin.py`, `tools/pm_loop.py`, `tools/pm_handoff.py`, `tools/supervisor.py`, `audit/sampler.py`, `bridge/supervisor_base.py`, `bridge/engineer_supervisor.py`, `bridge/pm_supervisor.py`, `server.py`, `main.py`).

Two findings; both fixed at commit 9:

1. **`docs/working/M5-continuous-pm-driver-grounding.md:1293` stale Reading X reference in F2.3 Q1 prose.** The original disposition prose said "The handoff is read by `PMSupervisor._reconstruct_state` on respawn" — accurate at the time F2.3 Q1 was dispositioned (2026-05-03), superseded by F9.0 Reading Y (2026-05-07). Fixed at commit 9 with in-place `[SUPERSEDED 2026-05-07]` annotation per Convention 8 (carry-forward annotation discipline). The F2.3 Q1 Reading C disposition itself (yaml-convention required-section markdown; no daemon-side validation) still holds — only the consumer location changes.

2. **`BACKLOG.md` cross-sub-milestone carry-forward destination stale.** The "audit_sample_log re-evaluator" entry referenced "post-M5-flag-flips focused-observability sub-milestone" as destination; updated 2026-05-07 per Q6 disposition to "post-M4d operational use; trigger is runtime-data-driven cause (40-80 hours continuous-mode operation, OR first 'I can't explain this' event, OR sub-milestone close review revealing missed pattern)." Fixed at commit 9 with explicit supersession framing.

Other scan targets clean:
- 0010 / 0011 / 0012 migration headers accurate.
- pm.yaml allowlist correctly includes all new tools shipped during chain.
- Engineer.yaml / implementer.yaml / tester.yaml correctly omit PM-only tools (write_pm_handoff, get_most_recent_handoff, get_loop_state, wait_for_next_tick, etc.).
- Touched-module docstrings reference shipped commits accurately.
- Spec docs internally consistent post-commit-8 amendments.

## Sub-milestone-boundary internal-spec-consistency scan (first invocation)

This convention's first invocation per Convention 3 (conventions doc). Operational shape: for each architectural concept introduced or modified at commits 1-8, grep mentions across `docs/kickoff/` + `bridge/*.py` + `tools/*.py` docstrings + this chain's grounding doc; flag mentions divergent from the disposition that landed.

**Concept inventory + scan results:**

| Concept | Sources scanned | Findings |
|---|---|---|
| `workflow_id` α-denormalization (commit 1) | `tools/budget.py`; `06-mcp-tools-phase-one.md`; grounding doc F1.1 disposition | Clean. All sources describe the chain-root walk via `tasks.parent_task_id` consistently. |
| `pm_current_focus` table + completion-validation contract (commit 2) | `tools/pm_focus.py`; `0010_*.sql`; `06-mcp-tools-phase-one.md`; `05-data-model.md`; grounding doc F3 dispositions | Clean. Reading A (dedicated table) + Reading C `bound_workflow_id` (F3.6.B Q3) + four-action audit namespace (F3.6.D Q4) consistently described. |
| `pm_spin_rationale_log` (commit 3) | `tools/pm_spin.py`; `0011_*.sql`; `06-mcp-tools-phase-one.md`; grounding doc F4 dispositions | Clean. Closed-enum decision + rationale_tag + per-tick-no-dedup framing consistent. |
| `audit_sample_log` + sampling-percentage (commit 4) | `audit/sampler.py`; `0012_*.sql`; `06-mcp-tools-phase-one.md`; `config.py`; grounding doc F5 dispositions | Clean. Closed-enum source_event_kind (F5.6.B Reading A) + writer-only-this-chain framing + re-evaluator deferral consistent. BACKLOG cross-sub-milestone carry-forward destination updated this commit. |
| Effort plumbing (commit 5) | `agents/config.py`; `bridge/supervisor_base.py`; `bridge/engineer_supervisor.py`; `bridge/pm_supervisor.py`; `bridge/dispatcher.py`; `config/agents/*.yaml`; `07-agent-configs-phase-one.md`; `opus-4-7-effort-and-thinking-addendum.md` | Clean. Full five-value Effort Literal (F6.6.B Q1 Reading A) + per-role yaml field + supervisor-construction-time threading consistent. |
| Continuous loop driver (commit 7) | `tools/pm_loop.py`; `06-mcp-tools-phase-one.md`; `config/agents/pm.yaml`; grounding doc F8 dispositions | Clean. F8.6.A Reading D (supervisor unchanged at loop-iteration scope) + closure-pattern plumbing consistent. |
| Compaction lifecycle / handoff / signal_compaction widening (commit 8) | `tools/pm_handoff.py`; `tools/supervisor.py`; `bridge/supervisor_base.py`; `bridge/engineer_supervisor.py`; `bridge/pm_supervisor.py`; `06-mcp-tools-phase-one.md`; `11-engineer-bridge.md`; `02-architecture.md`; grounding doc F9.0 + F9.7.A | Clean post-commit-8 amendments. F9.0 Reading Y (subprocess-side handoff-read; supervisor unchanged; signal_compaction PM widening with role-derived audit action) consistently described across all 8 amended sources. The F2.3 Q1 stale prose finding (drift-scan above) was the only residual; fixed at commit 9. |

**No flagged findings beyond the F2.3 Q1 prose update already captured in the standard drift-scan above.** The convention's first invocation surfaces a clean state — commit 8's atomic amendment per `feedback_spec_amendment_runtime_drift` did the heavy lifting; the sub-milestone-boundary scan confirms no residual drift.

This is a positive signal for the convention: when amendments land atomically with runtime changes, the boundary scan finds little. Future sub-milestones with similar discipline will exercise this convention more strenuously when the amendment-atomicity discipline isn't followed.

## Drift between kickoff scope and landed scope

Three substantive divergences from kickoff scope, all dispositioned in-flight:

1. **Commit 1 dropped (task_type γ rolled back).** F2.1 commit-1 contradiction surface (2026-05-03): the original 10-commit chain plan included a `tasks.task_type` migration + `submit_task` schema field. Mike's disposition rolled back to 9-commit chain with workflow_id α only; task_type γ moves to M4d-task-management-surface (remediation sub-milestone scheduled after M5-continuous-pm-driver). Captured in F2.1 row 1 framing amendment.

2. **Convention-doc migration executed during chain.** Pre-chain, the migration trigger was set at "fifth durable discipline convention lands"; trigger fired during chain (5+ conventions added). Migration executed at commit 9 per Q2 Reading A disposition. Was not anticipated in M5-continuous-pm-driver kickoff scope; emerged from the chain's decision density.

3. **F9.0 Reading Y vs Reading X disposition at commit 8.** Reading X (daemon-side `_reconstruct_state` override) was the consensus framing across 5 spec sources entering commit 8; Reading Y (subprocess-side via tools/pm_handoff.py) was the outlier from M5-pm-supervisor-base P-2 Reading B disposition. Reading Y won on substantive defense (4) + recency-and-deliberateness; spec amendments at commit 8 atomically aligned all sources to Reading Y. Was not pre-determinable in M5-continuous-pm-driver kickoff — emerged from the verification-cycle at commit 8 prep.

The Codex-finding ingestion at M5-subscription-routing was an analogous in-flight emergence; precedent established that chains may legitimately surface scope-amendments via prep-checkpoint discipline. Both this chain's emergences (commit-1 rollback + convention-doc migration + F9.0 disposition) followed the same pattern: surfaced explicitly, dispositioned, captured in amendments.

## Discipline-pattern observations

Chapter-level organization per Q1 Reading B disposition (precedent set at this close, 2026-05-07). Flat-list shape (M5-subscription-routing precedent) doesn't scale at 14+ patterns; chapter structure admits volume without splitting findability.

### Chapter 1 — Review-process discipline

**Bidirectional-contradiction handling matured to four direction-shapes** across six invocations during this chain:

| # | Instance | Direction-shape | Locus |
|---|---|---|---|
| 1 | F1.1 task_type γ scope-punt revisit | Substance reversal | Code's first surfacing wrong; revisit produces correction |
| 2 | F5.6.B Q1 closed-enum reversal | Substance reversal | Code's recommendation wrong-on-merits; pushback exposes wrong-ness |
| 3 | F6.5.1 / F6.6.B Q1 framing-precedent reset | Framing-precedent-application | Code's recommendation right but defended via precedent that doesn't carry |
| 4 | F7.5.1 / Q1 (commit 6) | Prep-instruction error | Reviewing-Claude's prep instruction wrong; Code surfaces |
| 5 | F8.6.A Q4 (commit 7) | Hybrid (prep-instruction + framing-precedent) | Both fired simultaneously |
| 6 | F9.0 / Q1 (commit 8) | Spec-text-consensus vs reconsidered-disposition tension | Source count cuts one way, recency cuts the other; substance requires weighing recency over count |

Pattern is durable across both review-roles + the timing axis. The fourth direction-shape (#6) is genuinely new: spec-text-consensus is not the highest authority when a more recent + more deliberate disposition exists. Recency-and-deliberateness can override count.

**Verification-first discipline at architecturally-consequential dispositions.** F8.6.A and F9.0 precedents: when a disposition has multi-source spec implications, the verification cycle (paste citations verbatim before defending; restate defenses with substance not labels; verify reframe text before applying it) catches over-extension and partial-defense weakness that the recommendation-only round misses.

F8.6.A surfaced that Code's first defense leveraged P-2 architectural-separation citation more strongly than the docstring's text supported; F9.0 surfaced that Reading Y's defense (5) was ergonomics not architecture and shouldn't have been in the case. The verification cycle is a real corrector of substance, not just paranoia.

**Anticipated-pushback maturation.** Each prep checkpoint's anticipated-pushback section grew more substantive across the chain. By commit 8 F9.5, anticipated pushbacks landed at six items including hybrid bidirectional-discipline framing + multi-file spec amendment scope concerns + load-bearing-disposition acknowledgment. Future preps inherit higher-quality anticipated-pushback discipline.

**Explicit review at chain close.** Per memory `feedback_explicit_review_at_chain_close`: commit 9's drift-scan + sub-milestone-boundary internal-spec-consistency scan + closeout-doc drafting forced a review-mindset pass across the chain. Surfaced two minor drift findings (F2.3 Q1 stale prose; BACKLOG cross-sub-milestone destination) that build-mindset hadn't caught during commits 1-8.

### Chapter 2 — Test/code conventions

**Closed-enum sentinel pattern graduated** from threshold-of-three watch (active during commits 1-3) to established convention (commits 4+). At commit 8 the pattern was at 9+ instances. No longer requires watch-for-pattern tracking — closed-enum CHECK + Pydantic Literal + sentinel test is the standard shape for finite-taxonomy schema columns.

**Code-inspection sentinel observability convention** (Convention 9 in conventions doc). Code-inspection sentinels (tests that read production source files as text and assert forbidden patterns) are brittle to legitimate refactor by design — that brittleness is the appropriate cost of explicit disposition-pinning. Mode 1 (legitimate refactor; update sentinel) vs Mode 2 (architectural drift; revert change, not sentinel) categorization at every fire forces explicit categorization. (b)-revisit trigger: 3+ Mode 1 fires within chain OR single Mode 1 fire requiring materially complex update.

First instance: commit 7 shipped two sentinels (`test_pm_supervisor_does_not_import_tools_budget_or_pm_focus` + `test_pm_loop_module_does_not_define_read_current_helper`). Both stayed green through commits 7-9 (no fires of either mode).

**Settings field test colocation** (Convention 6 in conventions doc; Decision 4 from commit 4). Test boundary follows contract boundary, not source-file boundary. Applied to `audit_sample_percentage` (commit 4), `pm_loop_tick_seconds` (commit 7), `pm_handoff_dir` (commit 8) — tests live in the consumer test files, not `test_config.py`.

**Contract tests fail informatively** per memory `feedback_contract_tests_fail_informatively`. Pattern established across M4-M5 and reinforced this chain: encode architectural decisions as tests whose name describes what's violated and whose failure message names the contract + rationale. Examples this chain: `test_reconstruct_state_is_noop_at_initial_spawn_and_respawn` (no-op invariant + future-proofing comment); the two commit-7 invariant pins (Mode 1/Mode 2 prompts in failure messages); `test_pm_compaction_full_lifecycle_writes_pm_namespaced_audits` (Q3-A symmetry pin).

### Chapter 3 — Pattern-threshold management

**Threshold-of-three reframed as trigger-to-evaluate, not mandate-to-extract** (F4.5.2 — Convention 4 refinement in conventions doc). At three instances, the discipline is to ASK the question, not to answer "yes" by default. Surface similarity does not imply shared semantic load. Reject extraction when the abstraction unit doesn't carry semantic weight.

This frames the May 3 UTC-coercion precedent correctly: extraction landed there because the abstraction unit (timezone-aware-then-naive coercion) was load-bearing semantic logic, not just shared SQL shape. Applied at commit 8 F9.7.B Q8: filesystem-by-timestamp + SQL-rowid-DESC have different correctness conditions; no shared-helper extraction.

**Closure-captured callable for tools/-layer config** graduated from watch-for-pattern to established convention during chain. Five instances now (`get_oauth_usage_snapshot`, `Sampler` instance, `get_pm_loop_tick_seconds`, `get_pm_handoff_dir`, `get_pm_supervisor`). Pattern lives in `main.py` lifespan; tools/ consumes via callable; zero new bridge → tools imports.

**Watch-for-pattern preventative operation.** Three watches stayed at 2 (didn't trip threshold-of-three) through chain: `read_current_X` rowid-DESC, `bridge/` → `tools/` cross-layer imports, production-code-surface-for-testability. Each watch-entry's documented threshold + specific trigger condition kept dispositions mechanical-not-vibes. Watch-entries are working as designed.

**Accept-with-revisit-trigger pattern.** Multiple commits dispositioned with explicit revisit triggers rather than aesthetic judgment. Examples: F8.2 Q2-B 60s cadence (revisit if empirical operation shows under-responsiveness); F9.2 Q9-A compaction-aggregation rejection (revisit if compaction frequency exceeds once-per-cycle); F9.2 Q7-A anti-flapping discipline (revisit defense-in-depth if oscillation observed). Pattern: dispositions are accept-now-with-clear-reactivation-condition rather than accept-permanently-or-defer-permanently.

### Chapter 4 — Carry-forward and convention discipline

**Carry-forward note routing** (Convention 7 in conventions doc; Decision 5 from commits 3-4). Scope-determines-location: same-sub-milestone → grounding doc; cross-sub-milestone → BACKLOG.md (lazy-create on first cross-boundary entry). First cross-sub-milestone entry materialized at commit 4 (audit_sample_log re-evaluator). Convention is durable.

**Carry-forward annotation discipline** (Convention 8 in conventions doc; F8.6.B from commit 7). When downstream commits change baseline assumptions, annotate in-place with explicit `**DISPOSITIONED YYYY-MM-DD**` marker preserving the original reasoning chain. Don't silently rewrite. First instance at commit 7 (cadence baseline 30s → 60s); second at commit 8 (per-iteration write count carry-forward RESOLVED); third at commit 9 (audit_sample_log re-evaluator destination updated).

**Sub-milestone-boundary internal-spec-consistency scan** (Convention 3 in conventions doc; F9.8 from commit 8 prep). Paired with phase-boundary scan; neither subsumes the other. First invocation at commit 9 (this commit) found clean state — commit 8's atomic amendment did the heavy lifting; the boundary scan confirms no residual drift. Convention is operational; first invocation validates the design.

**F2.1 surface-as-finding rule** with unique-function metric clarification. Per F6.6 (commit 5 disposition): unique-function count is the primary trigger metric, not collected count. Trigger fires at >200% over F2.1 estimate. Across chain: trigger fired NEVER (commit 4 was at +110%; commit 8 was at +107%; both under threshold). Each invocation of the surface-as-finding rule produced a discipline-pattern observation rather than a re-prep — the rule is working as designed (catch divergence as observation, not enforce per-commit budget).

**Convention-doc migration executed via discrete trigger** (commit 9). Pre-chain, the migration trigger was set at "fifth durable discipline convention lands"; the discrete-trigger discipline made migration mechanical-not-vibes. Trigger fired during chain; migration executed at the closing commit per Q2 Reading A disposition. Convention catalog now lives in `docs/conventions/00-README.md`. Future durable conventions land there, not BACKLOG.md or kickoff/00-README.md.

### Chapter 5 — F2.1 trajectory and commit-shape correlation framework

**The F2.1 surface-as-finding rule is now well-grounded across 9 data points** (commits 1-9). Trajectory:

| Commit | Shape | Unique actual | Estimate | Divergence |
|---|---|---|---|---|
| 1 | wiring-only (record_api_call extension) | 9 | 8 | +13% |
| 2 | storage-layer (new table + 3 MCP tools + audit kinds) | 25 | 14 | +79% |
| 3 | storage-layer (new table + 1 MCP tool) | 11 | 8 | +37% |
| 4 | storage-layer + plumbing (new table + new module + Settings) | 21 | 10 | +110% |
| 5 | plumbing (yaml + supervisor + dispatcher) | 7 | 9 | -22% |
| 6 | docs-only (spec amendment) | 0 | 0 | n/a |
| 7 | architectural-keystone (loop driver core) | 16 | 18 | -11% |
| 8 | architectural-keystone (compaction lifecycle + handoff + permission widening) | 29 | 14 | +107% |
| 9 | closing (e2e + scans + closeout) | 3 | 20 | -85% |

**Pattern observations:**

- **Storage-layer commits consistently overshoot estimates** (commits 2, 3, 4 at +79% / +37% / +110%; commit 8's table-less but architectural-multi-tool work also at +107%). Driver: parametrize blow-ups (per-role-rejected × N permission tests), default-populates + sentinel mirror tests, MCP wrapper test fixtures.
- **Plumbing/integration commits track close to estimates** (commits 1, 5, 7 at +13% / -22% / -11%). Driver: estimate-shape was honest for these; less parametrize multiplicity.
- **Spec-amendment-only commit ships zero tests** (commit 6 at 0 / 0). Design intent — verification via mypy on yamls.
- **Closing commit undershoots estimates** (commit 9 at -85% / 3 unique vs +20). Driver: closing-commit work is verification + docs, not net-new code. F2.1 +20 estimate likely anticipated higher E2E volume; per-component MCP wrapper tests already cover individual paths from commits 2-8.

**Three-reason framing for the overshoot pattern** (per F2.5 commits 2-4 closeout-staging notes):

1. **Precedent-mirror tests added at draft time** uniformly with established module patterns (parametrize blow-ups; default-populates-on-insert mirrors; sentinel-test mirrors).
2. **Pushback-driven additions at review time** — test surface added in response to reviewing-Claude pushbacks (e.g., commit 2's boundary-aware-truncation tests from Item 2 pushback resolution).
3. **Disposition-driven invariant pins at draft time** — sentinel tests pinning disposition reasoning into durable regression (e.g., commit 3's `no_dedup_emits_per_tick` pinning Q1 Reading A and `does_not_write_audit_log` pinning Q6 Reading A + F4.5.3 reasoning).

All three reasons disposition as scope-correct + absorbed-pattern; chain-wide estimate accepts these as healthy discipline cost rather than budget overrun.

**Future preps should use commit-shape-aware estimation rather than uniform per-commit numbers.** The F2.1 estimates were made at sub-milestone start before commit-shape distribution was known; the trajectory data now exists to calibrate future estimates against shape categories (storage: +50-110% over baseline expected; plumbing: ±20% of baseline; docs: 0; closing: -50 to -85%).

**Trigger condition NEVER FIRED across 9 commits** — no commit's unique-function count exceeded 200% of estimate (commits 4 and 8 were closest at +110% and +107%). Per F6.6 metric clarification (unique-function count primary, not collected count), the trigger is working as designed: catch divergence as observation, not enforce per-commit budget.

**Pattern is now well-grounded enough to apply forward** at M5-flag-flips and M6 dashboard preps. Storage-layer estimates should be doubled; closing-commit estimates should be quartered.

## What's next

Sub-milestone 7 of 9 closes. Remaining M5 sub-milestones per the breakdown:

- **M4d-task-management-surface** — remediation sub-milestone for the M4 task-management surface gap (`tools/tasks.py` + six MCP tools that no M4 sub-milestone took as scope). Includes `tasks.task_type` migration + `submit_task` schema field that rolled back from M5-continuous-pm-driver commit 1 per F2.1 contradiction surface.
- **M5-flag-flips** — `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true` + `MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR=true`; `MODEL_PRICING` verification; api_key_overflow window-cycle resolution; Codex Findings 2-5 addressed; the 80% Opus-downgrade threshold validated against operational data.
- **M5-acceptance** — operator-driven smoke test concluding M5.

Cross-sub-milestone carry-forwards live (BACKLOG.md § Carry-forward to future sub-milestones):

- **audit_sample_log re-evaluator** → focused-observability sub-milestone (post-M4d operational use; runtime-data-driven trigger per the destination update at this commit).

The convention-doc migration + the F9.0 Reading Y disposition + the bidirectional-discipline four-direction-shape framework are the most consequential cross-cutting items established during this chain. Future-Claude reading the chain process cold finds the conventions at `docs/conventions/00-README.md`; finds the architectural Reading Y framing in this closeout's Lineage notes + 11-engineer-bridge.md § PM compaction; finds the bidirectional-discipline framework in this closeout's Discipline-pattern observations chapter 1.
