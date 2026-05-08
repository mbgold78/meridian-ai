# M4d-task-management-surface — sub-milestone close review

Closes the M4d-task-management-surface sub-milestone. Chain-wide retrospective per the canonical closeout convention (`docs/conventions/00-README.md` § 1).

This sub-milestone landed the six task-management MCP tools (`submit_task`, `claim_task`, `update_task_status`, `get_queue`, `get_subtasks`, `set_task_priority`) plus migration `0013_tasks_task_type_and_status_check.sql`, the `task_type` α-denormalization symmetric extension at `record_api_call`, and the chain-level e2e tests verifying production MCP path. Remediation sub-milestone for the M4-reopened acceptance gap (per `docs/kickoff/08-implementation-plan.md:134-149`).

**Decision density was high but architecturally coherent** — chain established 2 new closeout candidates beyond the 8 inherited from M5-continuous-pm-driver close (10 total now), plus 2 convention candidates promoted at this close. F2.1 sub-category framework gained empirical confirmation across 8 cleanly partitioned data points; framework formalization documented in chapter 5 below.

**One mid-flight defect (F1)** — codex review caught audit-vs-api_usage divergence at commit 7 that disposition-trail self-review missed. Commit 8.5 fix-commit landed before closeout; closeout candidate #9 (dual-reviewer discipline) is the empirical lesson.

## Chain summary

Nine substantive commits + commit-0 chore + commit 8.5 hygiene fix. Test count trajectory: 595 (chain start, post-`ffbb8f6`) → 739 (post-commit-9-doc-land). Net +144 collected.

| Commit | Hash | Description | Unique tests | F2.1 estimate | Divergence |
|---|---|---|---|---|---|
| 0 | `2ce57a0` | chore: BACKLOG auto-persona Phase 2 entry | 0 | 0 | n/a |
| 1 | `541488c` | migration 0013 + Task model fix + sentinels + spec amendments | 7 | 22 | -68% |
| 2 | `bf2fd68` | tools/tasks.py module skeleton (12 schemas + `_require_role`) | 7 | 5 | +40% |
| 3 | `e9c7715` | submit_task + Q9 (A) caller-distinction fold + manages_directly correction | 25 (handler scope) / 30 total | 22 | +14% (handler scope) / +36% (total) |
| 4 | `e871762` | claim_task (clean handler shape) | 7 | 7 | 0% |
| 5 | `e7c1001` | update_task_status (state-machine matrix + 2-clause OR cancelled-conditional) | 6 | 10 | -15% |
| 6 | `c5486bb` | get_queue + get_subtasks (multi-tool read-side) | 10 | 10 | 0% |
| 7 | `d48b632` | set_task_priority + cohort coverage activation (cross-module) | 8 | 10 | -20% |
| 8 | `0ed3621` | chain-level e2e tests + M4d-acceptance-demo-criteria.md | 3 | 4 | -25% |
| 8.5 | `e9c2ab5` | F1 audit-vs-api_usage divergence fix + T1 paired tests | 0 (in-place additions) | 0 | n/a |
| 9 | `6b5ba1b` | closeout doc + drift-scan + sub-milestone-boundary scan + spec amendments + α demo execution | 0 | n/a | n/a |

**Trajectory pattern** (validated across 8 data points; chapter 5 below): unique-fn count correlates with commit-shape sub-category, not commit number. Hypothesis B (read-side per-tool baseline materially lower than write-side) empirically confirmed at commit 6.

## Spec amendments shipped

- **`docs/kickoff/06-mcp-tools-phase-one.md`** — extensive (commits 1, 3, 7, 9):
  - `submit_task` work-shape vs work-domain framing (commit 1; `task_type` taxonomy block).
  - `submit_task` parent-ownership clause 2 role-resolution semantics (commit 3 indirect via spec citation in helper docstrings).
  - `record_api_call` `task_type` α-denormalization symmetric extension (commit 7).
  - **`SubmitTaskInput` schema block amended at commit 9** to include `task_type` field per drift-scan A2.
  - **`get_subtasks` permission summary table amended at commit 9** to "own claimed (Phase 1 simplification)" per drift-scan A1 + CQ1 disposition.
- **`docs/kickoff/05-data-model.md`** — commit 1: tasks DDL block updated; new `### tasks.task_type` field-semantics subsection; migrations table row 0013.
- **`docs/conventions/00-README.md`** — commit 9 (this commit): two new conventions promoted per CQ2 disposition (RUF002 Unicode-in-Python-docstring; programmatic-Cartesian matrix sentinels).

## Migrations shipped

- **`0013_tasks_task_type_and_status_check.sql`** (commit 1) — adds `tasks.task_type` column with closed nine-value CHECK (work-shape taxonomy); adds CHECK on `tasks.status` mirroring 0008 `dispatches.status` precedent. Table rewrite per SQLite ADD CONSTRAINT limitation; greenfield (no production rows pre-M4d submit_task) so DROP/RENAME safe. Indexes recreated post-rename.

One migration total. Schema-version end-state: 13.

## New cross-layer single-source-of-truth instances

Compounded the "derive don't duplicate" pattern from prior sub-milestones:

- **`storage.models.TaskType`** Literal + matching CHECK (`storage/sql/0013_tasks_task_type_and_status_check.sql`) per Q1 work-shape taxonomy disposition. Sentinel test walks all nine values; locks Python Literal + DB CHECK alignment.
- **`storage.models.TaskStatus`** CHECK constraint added at 0013 (existed as Literal since M1; commit 1 added DB-layer enforcement). Sentinel test walks all seven values.
- **`agents.permissions.PermissionResolver.manages_directly`** method (commit 3 disposition correction; M4d's only PermissionResolver public method addition). Strict one-hop semantics distinct from `is_descendant`'s transitive-subtree-inclusive shape.
- **`tools.tasks.HUMAN_MIKE_LITERAL`** + **`HUMAN_LITERAL_ROLE_MAP`** (commit 3) — Phase 1 literal-handling for `submitted_by` per CQ3 + CQ10 dispositions. Two-site centralization at commit 3 below threshold-of-three; module-level constants.
- **`tools.tasks._ALLOWED_TRANSITIONS`** dict (commit 5) — state-machine matrix; full-cartesian sentinel test walks 42 (from, to) pairs programmatically generated per CQ3 commit 5 Reading A.
- **`tools.budget.ChainRootCohortKeys`** NamedTuple (commit 7) — symmetric extension shape for chain-root resolution returning workflow_id + task_type from single recursive CTE walk.

## Watch-for-pattern outcomes

Watch entries at chain start were 6 (carried from M5-continuous-pm-driver close); 13 watches active at chain end (post-commit-8.5).

| Pattern | Pre-chain | End-of-chain | Disposition |
|---|---|---|---|
| `read_current_X` rowid-DESC | 2 | 2 | Threshold-of-three; no new instance during M4d |
| `bridge/` → `tools/` | 2 | 2 | Threshold-of-three; no new instance |
| `token_store=None` defensive | 2 | 2 | Threshold-of-three; no new instance |
| Production-code-surface-for-testability | 2 | 2 | Threshold-of-three; no new instance |
| Closed-enum CHECK + sentinel | 9+ | 11+ | Convention; TaskType + tasks.status added at 0013 |
| Closure-captured callable | 5+ | 5+ | Convention |
| Adjacent-hygiene-during-touch | 1 | 2 | New watch at commit 1 (Task model drift fix); threshold-of-three for formalization |
| Migration-adjacent test fixture parallel-shape | — | 1 | NEW at commit 1 (`test_daemon_lifecycle.py` 12→13 parallel update) |
| Destructive-migration safety check | — | 1 | NEW at commit 1; threshold-of-two for convention promotion |
| `_require_role` helper duplication | — | 2 | NEW at commit 2 (dispatch.py + tasks.py); threshold-of-three |
| Caller-source resolution at handler | — | 1 | NEW at commit 3; threshold-of-three (likely stays at 1) |
| OAuth-vs-static data-layer distinction | — | 1 | NEW at commit 3 (`submitted_by="human:mike"`); threshold-of-three |
| Permission-check-returns-data | — | 2 | NEW at commit 3; per-tool-by-design rationale documented at commit 7 (4-tool table); threshold-of-three for evaluation |
| Disposition-correction during implementation | — | 1 | NEW at commit 3 (`is_descendant`→`manages_directly`); threshold-of-three |
| Per-tool hierarchy-authorization shape diversity | — | 5 raise-gate / 2 vis-filter | NEW at commit 4; renamed at commit 6 with two-bucket accounting; trigger 6th raise-gate OR 3rd vis-filter |
| Two-query atomic-update pattern | 1 (`complete_dispatch`) | 3 | At threshold-of-three at commit 7; deferred indefinitely with renewed basis (cosmetic-not-correctness; trigger 4th-with-non-cosmetic-motivation) |
| Submitter-role resolution shared logic | — | 2 | NEW at commit 5 (cross-tool); threshold-of-three for `_resolve_submitter_role` extraction |
| Task-row subtree SQL scoping | — | 2 | NEW at commit 6; threshold-of-three for `_task_scoping_where_clause` extraction |
| In-chain raise-gate logic duplication | — | 2 | NEW at commit 7; threshold-of-three for `_check_can_access_task_chain` extraction |
| Multi-tool packaging at commit-shape level | — | 1 | NEW at commit 6; second-instance trigger |

**Three watches at threshold-of-two for cross-tool extraction held through M4d** (submitter-role resolution; task-row subtree SQL scoping; in-chain raise-gate logic). All three pinned for closeout drift-scan audit; **no third instance materialized during M4d**. Citation comments at all sites carry forward as long-term documentation per Mike's accepted dispositions. Trigger remains: third instance in any future commit.

## Codex finding status

**Pre-close fresh-eyes Agent review at commit 8 caught F1 / A1 / A2 / A3 / D1 / D2 / T2 / Q1.** External Codex post-close review ran on 2026-05-07 against commits 0-9 plus 8.5; findings route through this amendment / BACKLOG (post-close findings: F1 permission graph leakage [P1] → standalone fix-commit; F2 denied-tools-mutate-caller-activity [P1] → standalone fix-commit; F3 set_task_priority audit from_priority race → BACKLOG; F4 ancestor gate inclusive-self → CQ1 commit 9 closeout disposition Reading A spec amendment per Mike-decision routing; F5 atomicity docstring → corrected at this amendment commit; F6 closeout review framing → this revised passage; F7 closeout table TBD → updated; F8 α-demo claim refined). Findings ingested:

- **F1 (CRITICAL)** — `record_api_call` audit details wrote `request.task_type` instead of `resolved_task_type`. Audit and api_usage diverged on every server-derived task_type case. **Fixed at commit 8.5** (`e9c2ab5`); paired T1 audit-vs-api_usage parity assertions added across 3 task_type α tests.
- **A1** (`get_subtasks` visibility filter "claimed_by" vs spec "own chain") — **resolved at commit 9 closeout drift-scan** per CQ1 Reading A: spec amended to "own claimed (Phase 1 simplification)" with Phase 2 BACKLOG generalization entry.
- **A2** (spec schema block missing `task_type` field) — **resolved at commit 9 drift-scan**: `SubmitTaskInput` schema block amended to include `task_type: TaskType | None = None  # work-shape cohort key`.
- **A3** (spec line-number drift) — single-instance observation; documented; no spec amendment.
- **D1** (convention candidate evaluations) — **both promoted at commit 9** per CQ2 disposition: RUF002 Unicode-in-Python-docstring + programmatic-Cartesian-from-source-of-truth-dict matrix sentinels. Both land in `docs/conventions/00-README.md` with cousin-to-closed-enum-sentinel framing.
- **D2** (watch entries currency check) — drift-scan confirms watch counts current per BACKLOG state.
- **T2** (cycle defense symmetric assertion for task_type) — **deferred to BACKLOG** per codex routing; trigger: cycle-defense behavior change for either field requires symmetric assertion.
- **Q1** (`assert updated is not None` in set_task_priority) — **deferred to BACKLOG** per codex routing; trigger: delete_task tool ships OR M7 production deployment.
- **Five false positives** confirmed per established M4d disposition discipline (T3 race-free CQ6; three threshold-of-two extractions F4.5.2 reframe; set_task_priority race-free UPDATE CQ6; no phase-1-acceptance-coverage scan at M4d phase-1 ceremony at M7; pre-convention closeout docs Reading B forward-only).

## Lineage notes for future-Claude

These items are non-obvious from the code alone and worth surfacing for cold reads.

- **F1 was caught by fresh-eyes Agent, not disposition-trail self-review.** I "verified" task_type α-denormalization at commit 7 by reading my commit message + spec amendment, NOT the actual `INSERT INTO api_usage` SQL + parallel audit-write site. Existence-of-`resolved_task_type`-variable in scope ≠ correct-use-at-both-write-sites. Closeout candidates #8 (verification-first checkpoint distinction "X exists" vs "X is right home for Y") and #9 (dual-reviewer discipline) crystallized from this. F1 is the empirical worked example.

- **CQ4 commit 4 disposition correction (is_descendant → manages_directly) was caught by my own test, not by reviewer-Claude.** My prep used `is_descendant` (transitive); spec line 44 says "direct report" (one-hop). Mike's verbatim-paste discipline + my test failure together caught it. Three-stage verification-first discipline (prep / diff / test) operating; cost gradient prep < diff < test < post-commit < production.

- **Q1 task_type taxonomy framing reset (substrate-shape → product-agnostic work-shape).** Initial proposal anchored on substrate vocabulary (planning / implementation / testing / review / etc.); Mike's pushback reset to product-agnostic work-shape (planning / dispatching / monitoring / review / investigation / synthesis / execution / revision / uncategorized) with coordination/production split. Operational-context bias closeout candidate (#1) operating; pinned in `feedback_operational_context_bias.md` memory.

- **CQ4 commit 3 + CQ10 commit 3 cohort coupling.** CQ3 Reading A (`submitted_by="human:mike"` literal) introduced the literal; CQ4 Reading B (clause 2 role-level) required JOIN to agents; CQ10 Reading A (special-case "human:mike"→"pm" inline) handles the literal under JOIN-fails. Three-way disposition coupling; M4d-discipline-pattern observation surfacing during the chain.

- **Pre-emptive handoff document at commit 9 prep opening.** Drafted as cheap insurance per Mike's commit 8.5 routing discipline. Closeout candidate #10 surfaced from the operational practice; pinned in Ch 1.

- **Sub-category framework calibration.** Storage-layer +50-110% multiplier from M5-continuous-pm-driver did NOT transfer cleanly to M4d's commit-shape distribution. Empirical data (8 data points) supports four sub-categories: pure-migration / pure-skeleton / handler-with-extras (clean / state-machine / cohort-extension) / multi-tool-read-side. Framework formalization in chapter 5.

## Address-now items routed forward

All items either resolved during the chain, fixed at commit 8.5, or properly deferred:

- ✓ Migration 0013 (tasks.task_type + tasks.status CHECK) — resolved at commit 1
- ✓ tools/tasks.py module skeleton — resolved at commit 2
- ✓ submit_task + Q9 (A) caller-distinction fold — resolved at commit 3
- ✓ claim_task + manages_directly disposition correction — resolved at commit 3 + commit 4
- ✓ update_task_status state-machine matrix — resolved at commit 5
- ✓ get_queue + get_subtasks read-side multi-tool — resolved at commit 6
- ✓ set_task_priority + cohort coverage activation — resolved at commit 7
- ✓ Chain-level e2e tests + acceptance demo criteria — resolved at commit 8
- ✓ F1 audit-vs-api_usage divergence fix + T1 paired tests — resolved at commit 8.5
- ✓ A1 get_subtasks visibility filter — resolved at commit 9 (this commit) per CQ1 Reading A spec amendment
- ✓ A2 spec schema block missing task_type — resolved at commit 9 spec amendment
- ✓ D1 convention candidate promotions — resolved at commit 9 per CQ2 disposition
- ✓ workflow_id population-protocol BACKLOG entry — **marked RESOLVED at commit 9** per UTC-coercion-RESOLVED precedent (Decision 4 commit 1 carry-forward)
- (deferred) T2 cycle defense symmetric assertion — BACKLOG with concrete trigger
- (deferred) Q1 assert in set_task_priority — BACKLOG with concrete trigger
- (deferred) Three threshold-of-two extractions — citation comments serve as long-term documentation; threshold-of-three at any future commit
- (deferred) audit_sample_log re-evaluator — destination remains focused-observability sub-milestone (post-M4d operational use; runtime-data-driven trigger per M5-continuous-pm-driver close)

## Drift-detection scan (standard scope)

Scope: spec docs + migration headers + relevant docstrings (`tools/tasks.py`, `tools/budget.py`, `agents/permissions.py`, `server.py`).

Findings dispositioned at commit 9:

1. **A1**: `get_subtasks` visibility filter "claimed_by" vs spec "own chain". Resolved per CQ1 Reading A — spec amended.
2. **A2**: `SubmitTaskInput` schema block missing `task_type` field. Resolved — schema block amended at commit 9.
3. **A3**: Spec line-number drift (commits 1 vs 7 references shifted ~10 lines due to commit 1's task_type semantics block expansion). Single-instance observation; no convention promotion warranted yet (similar pattern requires recurrence).
4. **workflow_id RESOLVED hygiene**: BACKLOG entry marked RESOLVED at commit 9 per Decision 4 commit 1 carry-forward.

Other scan targets clean:
- 0013 migration header accurate.
- pm.yaml / engineer.yaml / implementer.yaml / tester.yaml allowlists correctly include the six new task tools per commit 9 spot-check.
- Touched-module docstrings reference shipped commits accurately.

## Sub-milestone-boundary internal-spec-consistency scan (Convention 3)

Per Convention 3: for each architectural concept introduced or modified at commits 1-8, grep mentions across `docs/kickoff/` + `bridge/*.py` + `tools/*.py` docstrings; flag mentions divergent from the disposition that landed.

**Concept inventory + scan results:**

| Concept | Sources scanned | Findings |
|---|---|---|
| `tasks.task_type` taxonomy + CHECK (commit 1) | `tools/tasks.py`; `0013_*.sql`; `06-mcp-tools-phase-one.md`; `05-data-model.md` | Clean post-commit-9 amendments. Q1 disposition (work-shape; nine values; coordination/production split) consistent across all sources. |
| `manages_directly` (commit 3) | `agents/permissions.py`; `tools/tasks.py:_check_can_submit_task`; commit 3 message | Clean. Strict one-hop disposition consistent. |
| `HUMAN_MIKE_LITERAL` + `HUMAN_LITERAL_ROLE_MAP` (commit 3) | `tools/tasks.py`; `06-mcp-tools-phase-one.md` § submit_task; BACKLOG § Future architectural work | Clean. Phase 1 simplification framing consistent. |
| `_check_can_*` per-tool helpers (commits 3-7) | `tools/tasks.py`; spec § hierarchy clauses | Clean. Per-tool hierarchy semantic diversity intentional per CQ3 commit 6 watch entry. |
| `_ALLOWED_TRANSITIONS` state-machine matrix (commit 5) | `tools/tasks.py`; `05-data-model.md` § tasks.status; spec § update_task_status | Clean. Full-cartesian sentinel walks 42 pairs programmatically generated. |
| `ChainRootCohortKeys` NamedTuple + α-denormalization symmetric extension (commit 7) | `tools/budget.py`; `06-mcp-tools-phase-one.md` § record_api_call; commit 7 message | Clean post-commit-8.5 fix. F1 forensic-divergence resolved; audit and api_usage parity verified by T1 tests. |
| `_register_task_tools` wrappers (commits 3-7) | `server.py`; per-tool spec descriptions | Clean. All six tools registered; descriptions match spec wording. |
| Acceptance demo criteria (commit 8) | `docs/working/M4d-acceptance-demo-criteria.md` | Clean. Gate-condition framing per CQ2 commit 8 refinement. |

**No flagged findings beyond the F1 / A1 / A2 / A3 already captured** in the codex review + standard drift-scan above. Convention 3's commit-9 invocation surfaces a clean state — the codex-review-driven F1 catch + drift-scan A1-A3 amendments cover the substantive findings; sub-milestone-boundary scan validates no residual drift.

## Drift between kickoff scope and landed scope

Three substantive divergences from kickoff scope, all dispositioned in-flight or at closeout:

1. **M4d itself is a remediation sub-milestone** for the M4-reopened acceptance gap (per `08-implementation-plan.md:134-149`, 2026-05-03 finding). The original M4 scope claimed task-management surface ship at M4 close (2026-05-01); it didn't. M4d shipped the gap; M4 acceptance criteria (lines 122-128) met end-to-end via production MCP path at α demo (β pending Mike's substrate session).

2. **F1 mid-flight discovery (commit 7→8.5).** Cross-module commit at commit 7 introduced audit-vs-api_usage divergence on server-derived task_type. Caught by codex review (Agent fresh-eyes); fix-and-test-coverage commit 8.5 inserted before closeout. Pattern surfaced in flight; substance corrected; closeout candidate #8 + #9 capture lessons.

3. **`_resolve_workflow_id_from_dispatch` rename to `_resolve_chain_root_cohort_keys_from_dispatch` at commit 7.** Function expanded scope (workflow_id only → cohort keys including task_type); rename reflects expanded scope. Single-call-site refactor; mechanical churn. Was not pre-determinable in M4d kickoff — emerged from CQ2 commit 7 disposition.

## Discipline-pattern observations

Chapter-level organization per M5-continuous-pm-driver precedent (chapters at 9+ patterns, 2026-05-07). M4d ends with **10 patterns** + **2 promoted conventions** (Appendix A); chapter structure adopted.

**Pattern-to-chapter mapping** (explicit table-of-contents per Mike's CQ4 refinement):

| Pattern | Chapter | Worked example |
|---|---|---|
| #1 Operational-context bias on infrastructure surfaces | Ch 1 | Q1 substrate→product-agnostic taxonomy reset |
| #2 Bidirectional-discipline catches reviewer-side state-tracking errors | Ch 1 | duplicate-disposition turn (Q1/Q9 cycle) |
| #5 Prep-time primitive citations | Ch 1 | Mike's commit 3 closeout addition |
| #7 Disposition-correction during implementation | Ch 1 | commit 3 is_descendant→manages_directly |
| #10 Pre-emptive handoff document discipline | Ch 1 | commit 9 prep opening |
| #3 Describing-as-substitute-for-pasting | Ch 2 | commit 2 CQ4 verification gap |
| #4 Verbatim-re-read-catches-clause-collapse-errors | Ch 2 | commit 3 CQ4 |
| #6 Verification-first catches at three stages (prep/diff/test) | Ch 2 | F1 caught at fresh-eyes review |
| #8 Verification-first checkpoint distinction "X exists" vs "X is right home" | Ch 2 | F1 worked example |
| #9 Dual-reviewer discipline | Ch 3 | F1 empirical instance (Agent caught what disposition-trail missed) |

### Chapter 1 — Review-process discipline

Five patterns spanning the review/collaboration workflow.

**#1 Operational-context bias on infrastructure surfaces.** Recent operational context anchors framing on the immediate product even when the surface being designed is product-agnostic infrastructure. Q1 task_type taxonomy reset is the worked example: my initial proposal (planning / implementation / testing / review / triage / etc.) was M5-chain-process vocabulary; Mike's pushback reset to product-agnostic work-shape (planning / dispatching / monitoring / review / investigation / synthesis / execution / revision / uncategorized) with coordination/production split. Pinned in `feedback_operational_context_bias.md` memory; carry-forward to all infrastructure-surface design.

**#2 Bidirectional-discipline catches reviewer-side state-tracking errors.** Disposition discipline operates in BOTH directions; Code surfaces ambiguity rather than silently re-processing or guessing intent when reviewer state-tracking errors occur. Empirical instance: commit 4 prep cycle's duplicate-disposition turn — Mike re-emitted Q1 disposition (action sequence already in flight); Code caught the duplicate, surfaced explicitly for confirmation, did not redundantly re-process. Pattern operates at recovery-from-reviewer-error, complement to the more common "reviewer catches Code error" direction.

**#5 Prep-time primitive citations.** When a prep checkpoint introduces or names a primitive, paste the spec wording the primitive implements at prep time. Extends verbatim-paste discipline from code-to-code (commit 2 CQ4) to spec-to-primitive mapping. If this discipline had been operating at commit 4 prep, the `is_descendant` (transitive) vs spec's "direct report" (one-hop) mismatch would have surfaced in prep instead of at test-failure time. Pinned by Mike at commit 4 close-of-CQ.

**#7 Disposition-correction during implementation.** Mid-draft test failure can surface a substantive disposition error that prep-time review missed. Commit 4's `_check_can_submit_task_non_descendant_rejects` test failure caught the `is_descendant`→`manages_directly` substance error; Mike's verbatim-paste of spec line 44 confirmed; new `manages_directly` method on PermissionResolver landed in same commit. Three-stage verification-first discipline (prep/diff/test) operating; cost gradient prep < diff < test < post-commit < production. (Companion to #6 in Ch 2.)

**#10 Pre-emptive handoff document discipline at high-context-budget commits.** Drafting a handoff document IMMEDIATELY at commit prep opening — not as fallback when compaction fires — eliminates re-construction cost if compaction strikes mid-draft. M4d's empirical instance: commit 9 prep at ~96% budget; handoff doc drafted before closeout doc; captured position in chain, scope, F2.1 framework state, active watches, closeout candidates, drift-scan targets, and disposition state for any open questions. Operational practice; codify if it recurs at future high-budget commits.

### Chapter 2 — Verification-first discipline

Four patterns; verification-first is the highest-value discipline operating across M4d. F1 is the central worked example.

**#3 Describing-as-substitute-for-pasting.** When verifying a substantive claim ("X mirrors Y exactly" / "the implementation matches the spec"), description is not verification. The actual paste is verification. Empirical instance: commit 2 CQ4 — my diff checkpoint claimed `_require_role` was "exact mirror of dispatch.py:_require_role" with description; Mike asked for verbatim paste; pasted; identity confirmed at 8/8 lines char-for-char. Description bypasses the work verbatim-paste does.

**#4 Verbatim-re-read-catches-clause-collapse-errors.** When dispositioning multi-clause spec wording, re-reading the spec verbatim catches clause-collapse errors (treating two clauses as one when they encode different work). Empirical instance: commit 3 CQ4 parent-ownership disposition. My Reading A collapsed clause 2 (role-level) into clause 1 (agent-id-level); Mike's pushback + verbatim spec paste of line 46 demonstrated the clauses do different work (role-authority-persists-across-agent-instances semantics). Reading B accepted; HUMAN_LITERAL_ROLE_MAP introduced via CQ10 to handle resulting literal-handling case.

**#6 Verification-first catches at three stages (prep / diff / test).** Layered verification catches disposition errors at progressively-more-expensive stages; all M4d catches landed pre-commit. Empirical instances: prep-stage catch (commit 5 CQ1 cross-tool extraction substance); diff-stage catch (commit 2 CQ4 verbatim paste); test-stage catch (commit 4 disposition correction is_descendant→manages_directly). Cost gradient: prep < diff < test < post-commit < production. Layered discipline working as intended.

**#8 Verification-first checkpoint distinction: "X exists" vs "X is the right home for Y".** Existence verification is mechanically cheap (grep for X); home-suitability verification requires reading file contents to confirm category alignment. Empirical worked example: **F1**. At commit 7 I "verified" task_type α-denormalization by reading my own commit message + spec amendment. Existence-of-`resolved_task_type`-variable was correct (variable in scope; resolution logic matched workflow_id pattern). What I missed: the audit-write site at line 580 didn't USE the variable. Existence ≠ correct-use-at-call-site. The F1 fix at commit 8.5 was 1 line (`request.task_type` → `resolved_task_type`); the missing T1 audit-vs-api_usage parity assertion was 5-10 LOC. Discipline rule: when verifying "X is the right home for Y," read the actual call-site, not just the variable declaration.

### Chapter 3 — Dual-reviewer discipline

One pattern; carries the closing-stage lesson of M4d's review process.

**#9 Dual-reviewer discipline catches different bug classes.** Disposition-trail review (Code self-review with full disposition history) and fresh-eyes review (Agent / codex without disposition context) catch DIFFERENT bug classes:

- **Disposition-trail review** catches scope/intent/false-positive issues; verifies disposition matches implementation; can confirm `is_descendant` vs `manages_directly` because it knows the spec wording disposition.
- **Fresh-eyes review** catches implementation-vs-claim asymmetries the disposition-author can't see in their own code; reads what was actually written, not what the author intended. F1 is the empirical instance: 7 commits of careful disposition discipline didn't catch the audit-write site bug; Agent's 600-word review caught it on first read.

**Both reviewers required.** Single-reviewer (whether disposition-trail or fresh-eyes alone) leaves the other class of bugs latent. M4d's commit 8 codex review pause was the operational instance of dual-reviewer discipline; F1 surfaced; commit 8.5 corrected. Future sub-milestones: codex review at commit 8 close (pre-closeout) is the M4d-codified discipline.

**Refinement (post-close codex review, 2026-05-07)**: Codex post-close review caught F1 (permission graph leakage) and F2 (observation-before-allowlist) — **both spec violations** — that neither Code's disposition-trail review nor Code's internal Agent fresh-eyes review caught at the pre-close gate. Empirical validation that **external fresh-eyes catches what internal fresh-eyes misses**. Internal Agent dispatch (run within Code's session against same disposition state) and external codex (run separately by operator against committed state) are different review classes; both should run at major boundaries. Updated discipline: dual-reviewer at commit 8 close (internal Agent) PLUS post-close external codex review before sub-milestone fully closes; F1+F2 catches at post-close are routed via amendment/fix-commit cycle (this commit + workstreams 1+2).

## Chapter 5 — F2.1 trajectory and sub-category framework formalization

The F2.1 surface-as-finding rule is now well-grounded across **8 data points** (commits 1-8). Sub-category framework empirically supported.

| Commit | Shape | Unique actual | Estimate | Divergence |
|---|---|---|---|---|
| 1 | pure-migration (table rewrite + sentinels + drift fix) | 7 | 22 | -68% |
| 2 | pure-skeleton (6 Pydantic schemas + flat-allowlist primitive) | 7 | 5 | +40% |
| 3 | handler + primitive correction (submit_task + manages_directly addition) | 25 (handler scope) / 30 total | 22 | +14% (handler scope) / +36% (total) |
| 4 | clean handler (claim_task) | 7 | 7 | 0% |
| 5 | clean handler + state-machine matrix (parametrized 42 cases) | 6 | 10 | -15% |
| 6 | multi-tool read-side combined effect (get_queue + get_subtasks) | 10 | 10 | 0% |
| 7 | cross-module commit (handler + cohort extension) | 8 | 10 | -20% |
| 8 | integration-only (e2e tests + criteria doc) | 3 | 4 | -25% |

**Pattern observations:**

- **Pure-migration commits undershoot estimates** (commit 1 at -68%). Driver: single-table-rewrite + closed-enum sentinels mostly self-contained; estimate over-counted multi-test-fn surface that didn't materialize.
- **Pure-skeleton commits track close to baseline plumbing** (commit 2 at +40%; baseline was 5 anchored). Driver: 6-schema surface count slightly inflates beyond typical plumbing baseline of 1-2 schemas.
- **Handler-only clean commits land at baseline** (commit 4 at 0%; commit 6 at 0% combined effect). Driver: clean-handler shape is the framework anchor; deviations indicate sub-category effects.
- **Handler + primitive correction overshoots scope** (commit 3 at +14% handler scope). Driver: disposition-correction-during-implementation overhead (5 extra `manages_directly` tests on PermissionResolver) compounds with handler scope.
- **Handler + state-machine matrix undershoots** (commit 5 at -15%). Driver: programmatic Cartesian generation collapses 42 cases to 1 unique fn; matrix coverage ≠ unique-fn count.
- **Cross-module commits undershoot** (commit 7 at -20%). Driver: cohort extension reuses existing test fixtures; cross-module touch is plumbing-shape not handler-shape.
- **Integration-only commits undershoot** (commit 8 at -25%). Driver: e2e tests are concise per-scenario; criteria doc is documentation-only (zero test surface).

**Three-reason framing for divergence patterns** (per F2.5 closeout-staging notes from M5-continuous-pm-driver):

1. **Programmatic-Cartesian sentinels** (commit 5): one unique fn / N collected. The matrix is one invariant tested at scale; framework's unique-fn metric measures invariant count, not test count. Per CQ2 commit 5 acceptance: convention-promote at this closeout.
2. **Disposition-correction overhead** (commit 3 +14% handler scope vs +36% total): primitive corrections during implementation add adjacent test surface (5 `manages_directly` tests) that lands outside handler scope. Sub-category accounting can isolate handler-scope from primitive-correction-overhead.
3. **Combined-effect data points** (commit 6 multi-tool packaging + read-side; commit 7 cross-module): single data point captures combined effect. Future sub-milestones with single-tool read-side OR pure-handler-cross-module-extension would isolate variables.

**Sub-category framework formalized**: 4-5 cleanly distinguishable shapes; clean-handler-baseline robust; handler-complexity (state-machine matrix) absorbs into "extras" when matrix is parametrize-walk; cross-module + multi-tool combined-effect data points await Phase 2 isolation.

**Trigger condition NEVER FIRED across 8 commits** — no commit's unique-fn count exceeded 200% of estimate. Pattern is now well-grounded enough to apply forward at M5-flag-flips and M6 dashboard preps.

## Acceptance demo execution

Per `docs/working/M4d-acceptance-demo-criteria.md` (commit 8 deliverable). Demo runs same day per Q6 commit 3 acceptance.

### α — Synthetic acceptance demo (executed at commit 9; 2026-05-07)

7 binary pass/fail criteria; all PASS.

| # | Criterion | Result |
|---|---|---|
| α1 | Full state-transition cycle completes; final `task.status = 'completed'` | ✓ PASS — `test_full_cycle_oauth_submit_static_claim_status_lifecycle` |
| α2 | Double-claim raises CONFLICT with ConflictError class | ✓ PASS — `test_double_claim_within_cycle_raises_conflict` |
| α3 | Role-mismatch claim raises PERMISSION_DENIED with OrchestratorPermissionError class | ✓ PASS — `test_implementer_cannot_claim_tester_task` |
| α4 | Audit chain queryable for the cycle (task.submit / task.claim / task.status_change all present) | ✓ PASS — assertion in α1 test |
| α5 | Cohort coverage workflow_id populates from chain root via record_api_call's α-denormalization | ✓ PASS — pre-existing workflow_id α tests at `test_record_api_call.py:868+` |
| α6 | Cohort coverage task_type populates from chain root via record_api_call's α-denormalization (commit 7 + commit 8.5 F1 fix) | ✓ PASS — `test_task_type_alpha_resolves_chain_root_when_root_has_task_type` (with T1 audit-parity assertion verifying F1 fix) |
| α7 | Decline propagation via update_task_status('declined') succeeds; audit captures from/to | ✓ PASS — `test_decline_propagation_via_update_task_status` |

**α gate**: PASS. M4d wire-shape verified end-to-end via production MCP path. β-gate unblocked.

### β — Substrate acceptance demo (Mike's interactive Claude Code session; **PENDING execution**)

8 binary pass/fail criteria per `docs/working/M4d-acceptance-demo-criteria.md`. Pre-conditions:
- α gate PASSED (✓ above)
- M5-flag-flips territory: `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true` flip status (if not yet flipped, β runs against direct dispatch handler call; this is M5-flag-flips' explicit deliverable per `08-implementation-plan.md:301`)
- Substrate clone provisioned (per `seed.py`); clean working tree
- Mike's Claude Code session connected via OAuth-PM token

**β execution status: scoped under M5-flag-flips or M5-acceptance per Mike-decision when M5 work begins** (per CQ3 commit 9 closeout β-routing disposition Reading 3, 2026-05-07).

**M4d closes α-verified.** β is operational-use-event rather than M4d-correctness-verification; gate semantics differ. Three reasons for the routing:

1. β depends on Mike's interactive session with real subprocess spawns + real substrate commits — substantial operational session, not quick verification. Forcing same-day-before-commit-9 would couple M4d closeout to operational timing artificially.
2. α-verified state is clean closeout point: six task tools MCP-path verified; cohort α (workflow_id + task_type) verified via record_api_call handler-integration tests at `tests/integration/test_record_api_call.py` (NOT via MCP wrapper for `record_api_call`'s α path; the α gate exercises the recursive CTE walk through the handler interface). β verifies operational USE itself with substrate complexity, not M4d correctness.
3. β scoped under M5-flag-flips (activates operational mode via `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true` flip) or M5-acceptance (validates operational mode end-to-end with real work) reflects gate semantics correctly.

Rejected alternatives at commit 9 disposition:
- `git commit --amend` (couples M4d closeout to β session timing; if β reveals defect, amend creates hybrid closeout-and-fix commit conflating closure with remediation).
- Follow-up `chore(M4d): β acceptance demo execution` commit (middle path; structurally less clean than forward-routing under M5).

β failure routing if it surfaces in M5 scope: per `docs/working/M4d-acceptance-demo-criteria.md` § Failure handling. β-revealed defects route to M5-flag-flips' fix scope OR a new sub-milestone per Mike's disposition at the time, not back to M4d.

## What's next

Sub-milestone 9 of 9 closes (counting M4d as the inserted remediation sub-milestone). Remaining Phase 1 milestones per the breakdown:

- **M5-flag-flips** — `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true` + `MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR=true`; `MODEL_PRICING` verification; api_key_overflow window-cycle resolution; Codex Findings 2-5 addressed (carried from M5-subscription-routing); 80% Opus-downgrade threshold validated against operational data.
- **M5-acceptance** — operator-driven smoke test concluding M5.
- **M6 (knowledge + routing + audit + notification)** — `read_knowledge` / `list_knowledge` / `write_knowledge` / `get_dashboard_summary` / `get_audit_log` / `notify_human` / `get_unread_replies` / `get_message_history` + Telegram bot + utilization report. Migration `0004_notifications.sql`.
- **M7 (deployment + hardening)** — service install configs (launchd/systemd/Windows Service); smoke test script; daily backup; log rotation; **Phase 1 acceptance ceremony** (per `08-implementation-plan.md:405`; phase-boundary acceptance-criteria-coverage scan first invocation per Convention 2).

Cross-sub-milestone carry-forwards live (BACKLOG.md § Carry-forward to future sub-milestones):

- **audit_sample_log re-evaluator** → focused-observability sub-milestone (post-M4d operational use; runtime-data-driven trigger).

The dual-reviewer discipline (closeout candidate #9), pre-emptive handoff document discipline (#10), and the sub-category framework formalization (chapter 5) are the most consequential cross-cutting items established during this chain. Future-Claude reading the chain process cold finds:
- Conventions at `docs/conventions/00-README.md` (now at 11 conventions: 9 inherited + 2 promoted at commit 9)
- Disposition-pattern observations chapters above
- F2.1 sub-category framework data in chapter 5 of this closeout

## Appendix A — Convention candidates promoted at commit 9

Per CQ2 commit 9 disposition: both candidates promoted to `docs/conventions/00-README.md` with cousin-to-closed-enum-sentinel framing.

### A.1 RUF002 Unicode-in-Python-docstring

**Rule**: Python docstrings use ASCII for ambiguous Unicode (Greek letters, ×, etc.) per ruff RUF002. Markdown, SQL, and code comments retain Unicode.

**Reasoning**: cousin to closed-enum sentinel pattern; both prevent silent style drift. Ruff catches at lint time; convention pre-empts re-discovery at every commit.

**Establishment provenance**: M4d commits 2 (α→alpha in tasks.py docstring) and 4 (×→x in test_tasks.py docstring). Two instances; cheap; codify.

### A.2 Programmatic-Cartesian matrix sentinels

**Rule**: Parametrized matrix sentinels generate Cartesian programmatically from source-of-truth dict + input domain, NOT hardcoded pair lists.

**Examples**: state-machine matrix at `update_task_status` (commit 5 of M4d). `_state_machine_cases()` generates the 42 (from, to) pairs from `_ALLOWED_TRANSITIONS` dict + input Literal — sentinel asserts the matrix invariant against the dict structure itself; future refactors of either catch automatically.

**Reasoning**: structural robustness against silent coverage gaps. Cousin to closed-enum sentinel pattern; both prevent silent drift between Python source-of-truth and test assertions.

**Establishment provenance**: M4d commit 5 first instance shipping. Single instance; promoted now per CQ2 cheap-codification rationale.

---

End of M4d-task-management-surface closeout review.
