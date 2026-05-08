# Backlog

## Phase 1 (M1–M7)

Tracked via milestones in `docs/kickoff/08-implementation-plan.md`.

## Phase 2 (M8–M12)

Sketched in `docs/kickoff/12-phase-two-engineering-team.md`.

## Deferred

- Subscription tier identification (M7+ deployment surface). The `/api/oauth/usage` endpoint exposes utilization normalized 0-100% per counter but does not expose a `plan` field — the absolute capacity ceilings of the operator's subscription are not directly readable. Not blocking M5: routing decisions need normalized utilization, not absolute ceilings, and the `subscription_first_architecture` memory's polled-snapshot mechanism works without tier identification. Becomes relevant if/when the orchestrator surfaces tier info to end-user-facing dashboards, upgrade-path nudges, or absolute-token reporting (none in Phase 1 scope). Spec home: `project_subscription_first_architecture` memory § Subscription tier identification gap.
- Add direct unit tests for `meridian_orchestrator.server._resolve_agent_id` mirroring `tests/unit/test_server_helpers.py`'s pattern for `_resolve_caller_token_id`. Identity-resolution helpers are load-bearing across every wrapper but currently only exercised via integration tests through the request context. Surfaced at M5-record-api-call commit 4 when its sibling helper got direct unit coverage. Not blocking M5; hygiene work for the next time these helpers are touched.
- Consider renaming `api_usage.window_start` to `api_usage.call_timestamp` at next migration cycle. Semantic drift since the subscription-first architecture: the column actually holds the call's timestamp (per-counter window membership is reconstructed at query time from the polled `/api/oauth/usage` snapshot's `resets_at` boundaries), but the legacy "window_start" name suggests it's the start of a window. Schema names that lie to future readers compound into archaeology pain. Not blocking M5; hygiene for future schema work. Touchpoint: `tools/budget.py:record_api_call` writes the call timestamp to this column; future query-side aggregation in `tools/budget.py:get_token_usage` interprets it as a per-call timestamp. A rename migration would CREATE-new-column, copy values, drop old column.
- Verify model pricing AND check for new model tiers (decoupled from M5-flag-flips at 2026-05-07 kickoff per subscription-only-at-β reframe). `MODEL_PRICING` in `tools/budget.py` is stubbed UNVERIFIED at M5-record-api-call (commit 3 of the chain) with prices observed during drafting (2026-05-01); pricing may shift over time and Anthropic may introduce new tiers between updates. The table is not auto-updating. **Trigger:** (a) operator switches to API-billing path (e.g., flips `extra_usage.is_enabled: true` to enable paid overflow) OR (b) first instance where subscription-vs-API cost comparison is needed for a routing decision (e.g., evaluating subscription_5h vs api_key_overflow under utilization pressure). Original M5-flag-flips coupling assumed flag-flip closeout exposed cost-comparison routing; subscription-first architecture per `project_subscription_first_architecture` makes API cost a separate axis off-by-default. **Fix scope:** cross-reference `docs.anthropic.com/pricing`, update `MODEL_PRICING` and `tests/unit/tools/test_budget.py`'s coverage in lockstep, flip the inline source comment from "UNVERIFIED" to "verified YYYY-MM-DD" in a follow-up commit at trigger. **Cost estimate:** ~1h.
- Wrap `complete_dispatch`'s UPDATE+audit writes in `storage.transaction()` for atomicity parity with `record_api_call` (M5-record-api-call commit 5). Lower blast-radius than `record_api_call`'s stake: an audit-after-UPDATE failure leaves an incomplete audit chain but the dispatch row is correctly terminal — no cross-table disagreement like `api_usage` vs `dispatches.actual_tokens`. Worth doing for project-wide consistency; before this lands, `record_api_call` is the only multi-write tool handler with transactional atomicity and the asymmetry is non-obvious to future readers. Touchpoint: `tools/dispatch.py:complete_dispatch` lines 400-425 (UPDATE dispatches → write_audit). Pattern to mirror: `async with storage.transaction(): ...` wrapping both writes.
- **Watch-for-pattern, not fix-now:** `token_store=None` defensive handling in production code to accommodate test fixtures (current sites: `server._observe_caller` accepts `TokenStore | None`; `_register_agent_tools` accepts `TokenStore | None`). The defensive `None` surface exists because test fixtures construct partial server states. Small smell. If two or three more such defensive Nones emerge as M5 sub-milestones add wrappers, a fixture refactor (test fixtures construct a real TokenStore) is preferable to expanding the defensive surface. Don't refactor preemptively; flag the pattern at the next prep checkpoint that adds a similar Optional.
- Resolve `api_key_overflow` window-cycle semantics for `get_window_state` when extra_usage routing becomes operational (decoupled from M5-flag-flips at 2026-05-07 kickoff per subscription-only-at-β reframe). Phase 1 ships `get_window_state(bucket="api_key_overflow")` returning the unknown placeholder per M5-pm-mode-and-window-state commit 6 prep Q5 disposition because the window boundary is genuinely unspecified. **Trigger:** subscription approaches weekly limit (e.g., `subscription_7d.utilization` regularly exceeds 70%) AND operator flips `extra_usage.is_enabled: true` to enable paid overflow; under default subscription-only state with `extra_usage.is_enabled: false`, the unknown-placeholder is the correct surface and the cycle question is genuinely deferred. **Specific question to resolve at trigger:** is the window calendar-month (resets at the operator's billing-cycle anniversary day-of-month), rolling-30-days (resets exactly 30 days after the previous reset), or billing-cycle-anniversary (resets on the day Anthropic's billing cycle resets, which may differ from the calendar month if the operator subscribed mid-month)? `extra_usage` block has `monthly_limit` and `used_credits` fields but no `cycle_start` or `resets_at`. **Fix scope:** cross-reference `docs.anthropic.com/billing` (or experiment via `extra_usage.is_enabled: true` toggle and observe where `used_credits` resets), pick the semantics, encode in `_CYCLE_DURATION_SECONDS` or a dedicated `api_key_overflow`-specific computation, remove the unknown-placeholder short-circuit in `tools/budget.py:get_window_state`, add tests for the resolved semantics. **Cost estimate:** ~2h. Touchpoints: `tools/budget.py:get_window_state` (the api_key_overflow short-circuit at the disposition mark); `tools/budget.py:_CYCLE_DURATION_SECONDS` (the missing entry).
- Validate 80% Opus-downgrade threshold (`subscription_7d_opus.utilization >= 80%`) against operational data (added 2026-05-07 at M5-flag-flips kickoff per subscription-only-at-β reframe). The threshold in `check_budget_for_task` per `project_subscription_first_architecture` memory § Routing logic reshape is the documented starting point pending operator-data-driven calibration; below operational-utilization territory it's inert (no downgrade decisions firing) and validation has no signal to operate on. **Trigger:** `subscription_7d_opus.utilization` regularly exceeds 50% during real Engineer/PM operation, making the 80% threshold load-bearing for routing decisions (i.e., utilization is climbing into the territory where the threshold actually fires). **Fix scope:** observe utilization patterns over a meaningful operational window (~1-2 weeks of real chain execution), correlate with task-quality outcomes (does Sonnet downgrade preserve task success rate at the 80% boundary, or does it degrade quality enough to warrant a lower threshold like 70%?), pin or shift threshold; codify revised value in `check_budget_for_task` decision logic and add explicit-threshold tests. **Cost estimate:** ~2-4h observation period (passive, alongside other operational work) + ~1h analysis + ~1h codification.
- **Watch-for-pattern, not fix-now:** Production code surface expanded for testability. Two instances now: (1) ``token_store=None`` defensive handling in ``server._observe_caller`` and the six ``_register_*`` helpers (commit 5; accommodates test fixtures that don't construct a TokenStore); (2) ``_run() → _run() + _run_lifespan_body(settings, shutdown)`` refactor in ``src/meridian_orchestrator/main.py`` (commit 7; accommodates tests passing an external shutdown event without OS signals). Both defensible individually — ``_run_lifespan_body`` is a clean separation-of-concerns refactor (signal handling vs lifespan body) that would be defensible even without the test motivation; ``token_store=None`` is a small defensive surface for cleanly handling test fixture cases. But a pattern is forming: production code shape adapting to test constraints. At a third instance, consider whether tests should be using a different injection pattern (test fixtures with full TokenStore construction, dependency injection container, conftest helpers that build the production graph) rather than continuing to expand production-code surface for test-only purposes. Don't pre-decide the destination; surface as a prep-checkpoint contradiction at the third instance. Threshold: 3 instances. Frame consistent with the other watch-for-pattern entries.
- **Watch-for-pattern RESOLVED:** Inline UTC-coercion convention extracted at M5-subscription-routing commit 2 to `storage/datetime_utils._coerce_naive_to_utc`. Trigger fired when the third call site appeared in `tools/budget.py:_resolve_window` (the snapshot-resets_at parser); per the documented rule-of-three threshold, extraction landed in the same commit. All three former inline sites (`bridge/supervisor_base.py`, `tools/budget.py:_read_most_recent_pm_mode_log_timestamp`, `tools/budget.py:_resolve_window`) now call the shared helper. Unit tests for the helper at `tests/unit/storage/test_datetime_utils.py` lock both behaviors (naive→coerce; tz-aware→preserve). Entry kept (not deleted) as audit lineage — future watch-for-pattern entries reading this trail see how the discipline cycles from pattern surface → threshold → extraction → resolution marker.
- **Code Reviewer milestone-boundary item, not fix-now:** Project-wide audit of naive-datetime conversion patterns. The bug fixed at M5-pm-mode-and-window-state commit 6 (unconditional `.replace(tzinfo=UTC)` would clobber tz-aware inputs by overwriting their offset; e.g., a stored `+05:00` timestamp would emerge as `+00:00` with the same wall-clock numbers, wrong by 5 hours of real time) was localized to one helper, but the same footgun could exist elsewhere. At the next Code Reviewer milestone-boundary pass, grep `replace(tzinfo=` across `src/` and verify each instance has the defensive `if parsed.tzinfo is None` guard (or another correct pattern that preserves existing offsets). The bug class is "invisible on UTC CI, catastrophic on non-UTC dev machine" — the kind of issue that doesn't surface in test runs but shows up in production traces. Not blocking; queue at the next Code Reviewer pass.
- **Watch-for-pattern, not fix-now:** `bridge/` → `tools/` cross-layer imports. Two instances now: `bridge/activity.py` imports `PAUSED_MODES` (commit 5) and `read_current_pm_mode` (commit 6 — Q8 helper extraction) from `tools/pm_mode.py`. Reasoning was sound for both (single-source-of-truth value > cross-layer import cost; no cycle since `tools/pm_mode.py` doesn't import from `bridge/`). Two instances stay below the watch-pattern threshold. If a third `bridge` → `tools` import emerges across M5 sub-milestones, the layer split needs reconsidering — likely outcome would be a shared domain-types module (e.g., `bridge/pm_mode_types.py`) that both directories can import from. Don't pre-decide the destination; surface as a prep-checkpoint contradiction at the third instance.
- **Watch-for-pattern, not fix-now:** Adjacent-hygiene-during-touch. Fixing small adjacent drift (model fields out of sync with migrations, helper docstrings stale, etc.) opportunistically when a commit's primary work touches the same domain — rather than deferring to a separate hygiene commit. Two instances now: (1) UTC-coercion utility extracted at M5-subscription-routing commit 2 to `storage/datetime_utils._coerce_naive_to_utc` when the third call site appeared in `tools/budget.py:_resolve_window`; (2) `storage.models.Task` model drift fix at M4d-task-management-surface commit 1 (added `estimated_tokens` + `actual_tokens` fields that 0002_task_planning.sql shipped at M4 but the Pydantic model never picked up — folded in alongside the `task_type` addition since the commit was already touching the task domain). Pattern protection is the **tight cap**: only fix drift directly adjacent to the commit's primary work; surfaced drift outside that scope routes to BACKLOG or a separate hygiene commit, not folded. Threshold of three triggers evaluation per F4.5.2 reframe (Convention 4 in `docs/conventions/00-README.md`): extract as convention or document as discipline-pattern observation. Don't pre-decide; surface at the next prep-checkpoint that performs adjacent-hygiene during touch.
- **Watch-for-pattern, not fix-now:** Migration-adjacent test fixture parallel-shape assertions. `tests/integration/test_daemon_lifecycle.py:64,78` hardcodes the migration-version set as parallel-shape assertion (not via `EXPECTED_VERSIONS` reference from `tests/unit/storage/test_migrations.py`). First instance surfaced at M4d-task-management-surface commit 1: every migration-adding commit must touch this site, and the duplication is invisible until a migration is added and the test fails. Two real options when the third migration-adding commit hits this site: (a) **centralize via reference** to migrations registry — `test_daemon_lifecycle.py` imports `EXPECTED_VERSIONS` (or a function returning the live set from the migration discovery) so single-source-of-truth holds; (b) **formalize as expected-touch** in migration-adding commit prep scope — accept the duplication, but surface "update parallel-shape assertion" as a checklist item in every migration-adding commit's prep checkpoint. Don't pre-decide; surface at the third migration-adding commit's prep checkpoint. Touchpoint: `tests/integration/test_daemon_lifecycle.py:64,78`. Pinning this watch now prevents future-Claude from being surprised at every migration commit; the pattern is recoverable into convention.
- **Watch-for-pattern, not fix-now:** `_require_role` helper duplication. Two instances now: (1) `tools/dispatch.py:166-170` (M4a; Engineer-only flat-allowlist primitive used by `dispatch_implementer` / `dispatch_tester` / `complete_dispatch`); (2) `tools/tasks.py` (M4d-task-management-surface commit 2; six task-management tools' flat-allowlist primitive). Both are 5-line `if caller_role not in allowed: raise OrchestratorPermissionError(...)` — exact mechanical mirror confirmed at commit 2 prep CQ4 verification (Mike's watch (2): no hierarchy logic crept in; pure flat-allowlist primitive). If a third `tools/` module needs the same flat-allowlist primitive, evaluate extraction at threshold-of-three per F4.5.2 reframe (Convention 4 in `docs/conventions/00-README.md`): the abstraction unit is `caller_role in allowed → raise on miss`; the question at threshold is whether shared semantic load warrants extraction (e.g., a shared `tools/_permissions.py` helper module) or whether each `tools/` module should carry its own copy. Don't pre-decide; surface at the third instance's prep checkpoint. Touchpoints: `tools/dispatch.py:166-170`; `tools/tasks.py` (post-schemas section).
- **Watch-for-pattern, not fix-now:** Per-tool hierarchy-authorization shape diversity (renamed at M4d commit 6 per CQ3 Reading B; was "hierarchy-gate semantic diversity"). Two-bucket accounting reflects architectural reality of two distinct authorization operations (gate-shape and filter-shape are structurally different — different mechanisms [SQL vs Python-conditional], different failure modes [filtered subset vs error response], different test surfaces). A tool may contribute to one OR both buckets; "hybrid" describes the tool, not a third shape.

  **(a) Raise-gate shapes** (handler-entry-raise-on-mismatch). **Five** instances now: (1) `is_descendant` transitive subtree query (M5+ read-side ops; e.g. `get_dispatch_status` subtree scoping); (2) `manages_directly` one-hop adjacency query (M4d commit 3 `submit_task`); (3) strict `caller_role == task.agent_role` equality identity check (M4d commit 4 `claim_task`); (4) two-clause OR with cancelled-conditional second clause — agent-identity (`claimed_by`) OR role-via-resolution (`HUMAN_LITERAL_ROLE_MAP`-aware) (M4d commit 5 `update_task_status` via `_check_can_update_task_status`); (5) in-chain raise-gate — submitter OR claimed_by OR ancestor (`is_descendant(caller_role, parent.agent_role)`) (M4d commit 6 `get_subtasks` via `_check_can_read_subtasks`). Each fundamentally distinct in semantics; per-tool-by-design per spec.

  **(b) Visibility-filter shapes** (SQL-WHERE-clause filtered subset). **Two** instances now: (1) role-scoped queue filter — PM/Engineer subtree-inclusive role-IN-list, Implementer/Tester `claimed_by = caller_agent_id` (M4d commit 6 `get_queue`); (2) role-scoped subtask filter — same per-role visibility shape applied to subtasks under a given parent (M4d commit 6 `get_subtasks` post-raise-gate result filtering). Both shapes are mechanically inline SQL clauses; tracked as duplication at the task-row subtree SQL scoping watch entry below.

  Watch tracks **design space expansion**, not problem accumulation. **Trigger updated:** sixth distinct raise-gate semantic OR third distinct visibility-filter semantic surfaces in a `tools/` module — at that point the design space's growth pattern in the relevant bucket is the question, not whether to extract. Touchpoints: `agents/permissions.py` (is_descendant; manages_directly); `tools/tasks.py` (`_check_can_submit_task` uses manages_directly + Q9; `_check_can_claim` uses inline `==`; `_check_can_update_task_status` uses two-clause OR with cancelled-conditional; `_check_can_read_subtasks` uses three-clause in-chain raise-gate; `get_queue` and `get_subtasks` post-raise-gate use inline role-scoped SQL visibility-filter).
- **Watch-for-pattern, not fix-now:** Task-row subtree SQL scoping pattern duplication. **Two** instances now: (1) `tools/tasks.py:get_queue` hierarchy clause — PM `1=1`, Engineer `agent_role IN (...)`, Implementer/Tester `claimed_by = ?` (M4d commit 6); (2) `tools/tasks.py:get_subtasks` post-raise-gate visibility filter — identical per-role clause shape applied as additional WHERE clause alongside `parent_task_id = ?` (M4d commit 6). Distinct from `dispatch.py:_scoping_where_clause` (different SQL shape; tasks vs dispatch tables) and from `submitter-role resolution shared logic across tools` watch (different shared logic). Disposition (CQ6 commit 6 Reading A): inline at two call sites; threshold-of-three for cross-handler extraction per F4.5.2 reframe (Convention 4 in `docs/conventions/00-README.md`). **Trigger:** third instance surfaces in M4d commit 7 (`set_task_priority` may need similar subtree filter for hierarchy validation) OR post-M4d work. At threshold-of-three, evaluate `_task_scoping_where_clause(caller_role, caller_agent_id) -> tuple[str, list]` extraction in `tools/tasks.py` (mirrors `dispatch.py:_scoping_where_clause` precedent shape but for task-row scoping). Pinned as **closeout drift-scan target** at M4d commit 9: audit whether third instance materialized; if yes, extraction-at-trigger discipline validated; if no, citation comments serve as long-term documentation. Touchpoints: `tools/tasks.py:get_queue` (commit 6 first instance); `tools/tasks.py:get_subtasks` (commit 6 second instance).
- **Watch-for-pattern, not fix-now:** Submitter-role resolution shared logic across tools. **Two** instances now: (1) `tools/tasks.py:_check_can_submit_task_parent_ownership` clause 2 (M4d commit 3 `submit_task` parent-ownership check); (2) `tools/tasks.py:_check_can_update_task_status` clause 2 (M4d commit 5 `update_task_status` cancelled-only role check). Both resolve submitter-role via JOIN to agents (UUID-shape `submitted_by`) OR via `HUMAN_LITERAL_ROLE_MAP` (Phase 1 literals like `"human:mike"`); both check `caller_role == submitter_role`. Mechanically identical resolution logic; load-bearing semantic logic (not just shared SQL shape). **Distinct from per-tool hierarchy-gate semantic diversity watch** (which tracks gate-shape diversity at the tool level); this watch tracks shared sub-logic across the gates. Disposition (CQ1 commit 5 Reading B): deferred extraction; citation comments at both sites pointing to the parallel; two-instance count below threshold-of-three for cross-tool extraction per F4.5.2 reframe (Convention 4 in `docs/conventions/00-README.md`). **Trigger:** third instance surfaces in any future commit (set_task_priority, get_subtasks, get_queue, or post-M4d work). At threshold-of-three: extract to `_resolve_submitter_role(storage, task_id) -> str | None` helper at the triggering commit; refactor both prior call sites in same commit per same-commit-extraction discipline (Convention 5). Pinned as **closeout drift-scan target** at M4d commit 9: audit whether third instance materialized; if yes, extraction-at-trigger discipline validated; if no, citation comments become long-term documentation for post-M4d future-Code/Mike adding new tools. Touchpoints: `tools/tasks.py:_check_can_submit_task_parent_ownership` (commit 3 first instance); `tools/tasks.py:_check_can_update_task_status` (commit 5 second instance).
- Add rowcount return to `Storage.execute` interface for one-query atomic-update verification. **Three** instances of the two-query atomic-update pattern (UPDATE then SELECT-verify) now: (1) `tools/dispatch.py:complete_dispatch` — UPDATE dispatches + audit (read-then-act lifecycle); (2) `tools/tasks.py:claim_task` — UPDATE tasks WHERE status='pending' + SELECT-verify claimed_by + status (M4d commit 4); (3) `tools/tasks.py:update_task_status` — UPDATE tasks WHERE id=? AND status=? + SELECT-verify status (M4d commit 5; race protection on starting state). The Storage interface (`storage/interface.py:Storage.execute`) returns `None` (no rowcount); the two-query pattern is necessary to disambiguate "UPDATE matched 0 rows" from "UPDATE succeeded". **Disposition (CQ2 commit 5 Reading B): renewed deferral basis.** Threshold-of-three reached and evaluated; pattern is correct + understood; perf cost unmeasurable at low atomic-update frequency; cosmetic-not-correctness optimization. Storage interface change carries non-trivial blast radius (Protocol method addition + backend implementation + tests + 0-3 call-site migrations). **Trigger updated:** fourth instance with non-cosmetic motivation (e.g., concurrency-shape change requiring rowcount semantics; atomic-update pattern needing rowcount for correctness rather than cosmetic clarity; or batch-update operation where SELECT-verify becomes prohibitively expensive). Spec home: `storage/interface.py:Storage.execute`. Phase 1 acceptance: two-query pattern works correctly under SQLite atomicity; the cost is two queries per atomic update, not correctness. Touchpoints: `tools/dispatch.py:complete_dispatch` (lines 393-450); `tools/tasks.py:claim_task` (commit 4); `tools/tasks.py:update_task_status` (commit 5).
- **Watch-for-pattern, not fix-now:** Permission-check-returns-data. **Two** instances now (per-tool-by-design rationale per CQ7 commit 7 disposition; architectural intent, NOT drift). Per-tool asymmetry across M4d helpers:

  | Helper | Returns data? | Rationale |
  |---|---|---|
  | `_check_can_submit_task_parent_ownership` (commit 3) | YES — parent row dict | LEFT JOIN already needed for clause 2 role resolution; handler reuses for `company_id` / `project_id` inheritance. Avoiding redundant SELECT. |
  | `_check_can_update_task_status` (commit 5) | NO — returns None on success | Handler had already SELECTed for state-machine validation; helper takes data dict. |
  | `_check_can_read_subtasks` (commit 6) | NO — returns None on success | Raise-gate scope is parent-access only; handler does the visibility-filter SELECT separately. |
  | `_check_can_set_priority` (commit 7) | YES — task row dict | Helper's required SELECT yields `priority` field handler reuses for audit from-priority capture. Avoiding redundant SELECT before UPDATE. |

  **Architectural intent**: helper returns data only when the helper's required SELECT (for the gate logic) provides exactly what the handler also needs. Per-tool decision based on whether redundancy would otherwise occur. Not "drift" — both shapes are intentional and load-bearing. Threshold-of-three for evaluation of whether to formalize as convention (e.g., "permission helpers MAY return the queried-as-side-effect data when the same query is required for both") OR split into two helpers (e.g., separate `_check_can_*` returning None and `_load_*` for the data fetch) when the architectural intent stops being legible. Don't pre-decide; surface at the third instance's prep checkpoint. Touchpoints: `tools/tasks.py:_check_can_submit_task_parent_ownership` (commit 3); `tools/tasks.py:_check_can_set_priority` (commit 7).
- **Watch-for-pattern, not fix-now:** In-chain raise-gate logic duplication. **Two** instances now: (1) `tools/tasks.py:_check_can_read_subtasks` (M4d commit 6; `get_subtasks` parent-access raise-gate); (2) `tools/tasks.py:_check_can_set_priority` (M4d commit 7; `set_task_priority` hierarchy gate). Both shape: 3-clause OR — submitter (agent-id) OR claimed-by (agent-id) OR ancestor (transitive role via `permissions.is_descendant`). Mechanically identical primitive operations; same SELECT shape (`SELECT id, agent_role, claimed_by, submitted_by FROM tasks`); same raise pattern with diagnostic detail. Distinct from `submitter-role resolution shared logic across tools` watch (different shared logic — that watch tracks JOIN-to-agents-or-HUMAN_LITERAL_ROLE_MAP-fallback for role-level "submitter" wording at line 46/91; THIS watch tracks 3-clause OR with agent-id-level "submitter" per line 105). Disposition (CQ1 commit 7 Reading B; mirrors commit 5 CQ1 cross-tool deferral precedent): keep inline at both call sites with explicit citation comments; defer extraction to third instance per threshold-of-three convention. **Trigger:** third instance surfaces in any future commit (post-M4d work likely; M4d's remaining commits 8-9 are integration + closeout, no new handlers). At threshold-of-three: extract to `_check_can_access_task_chain(storage, permissions, task_id, caller_agent_id, caller_role) -> dict[str, Any]` helper at the triggering commit; refactor both prior call sites in same commit per same-commit-extraction discipline (Convention 5). Pinned as **closeout drift-scan target** at M4d commit 9: audit whether third instance materialized; if yes, extraction-at-trigger discipline validated; if no, citation comments serve as long-term documentation. Touchpoints: `tools/tasks.py:_check_can_read_subtasks` (commit 6 first instance); `tools/tasks.py:_check_can_set_priority` (commit 7 second instance).
- **Watch-for-pattern, not fix-now:** Destructive-migration safety check. When a migration DROPs or table-rewrites a column or table with potential data loss, the prep checkpoint includes explicit verification: `grep src/ + scripts/ for INSERT INTO <table>` targeting the affected table; verification result documented in the migration header. Greenfield safety doesn't hold by default — pre-M4d, the assumption was that no production code path INSERTed into tasks before submit_task shipped, but the assumption needed explicit verification, not implicit trust. M4d commit 1's table-rewrite of `tasks` for CHECK constraints was the first explicit verification (greenfield-empty confirmed; DROP TABLE tasks succeeded because no rows in dispatches/api_usage/pm_current_focus/outbound_messages referenced tasks rows). Discipline rule: future destructive migrations follow the same verification, with results captured in the migration header comment. Promote to `docs/conventions/00-README.md` if a second destructive migration follows the same discipline (threshold-of-two for promotion since the verification step is mechanical and load-bearing — not the threshold-of-three reserved for shape decisions). Touchpoint: future migrations adding CHECK constraints to existing columns OR rewriting tables for shape changes.
- Rename `tests/integration/test_tool_activity_writer.py` to a name that reflects its current scope. The file was originally scoped to the dispatches activity writer (`_record_caller_activity` → `record_mcp_call`); at M5-pm-mode-and-window-state commit 5 the helper was renamed to `_observe_caller` with broader fan-out (activity update + OAuth-PM auto-flip + transactional rollback). The filename now under-describes the file's coverage. Suggested target name: `test_observe_caller.py`. Mechanical rename; deferred to a hygiene commit to keep the renaming pass focused on the helper itself.
- Phase 2 refinement to per-message freshness for the OAuth-PM auto-flip writer. Phase 1 (M5-pm-mode-and-window-state commit 5) ships transition-based approximation: `pm_mode_log` captures stretch-start, not per-message activity, per Reading B at the commit 5 prep. PM's continuous loop computes the interactive window as `now - most_recent_pm_mode_log_timestamp < 30min`. Self-corrects via PM's flip-back-to-continuous logic — heavy-chat Mike experiences periodic 30-min throttle cycles, not continuous throttling. Implement Phase 2 if operational data shows the approximation produces visible problems for sustained-chat scenarios. Implementation shape (Sub-Reading β at commit 5 prep): daemon-side in-memory `_last_oauth_pm_activity_at` cache, exposed via a new `get_window_state` output field (e.g., `last_oauth_pm_activity_seconds_ago: int | None`). Touchpoint: `bridge/activity.py:record_oauth_pm_auto_flip` (extend with cache update); `tools/budget.py:get_window_state` (extend output schema).
- Add `idx_pm_focus_bound_workflow` index on `pm_current_focus.bound_workflow_id` when workflow-cohort queries against `pm_current_focus` become hot. Deferred at M5-continuous-pm-driver commit 2 prep (F3.6 Q5 disposition, 2026-05-04): the loop's at-most-once-per-tick `read_current_focus` lookup is well within sequential-scan bounds even at 1000+ row counts; workflow-cohort surfaces against `pm_current_focus` are not load-bearing pre-M5-flag-flips, and the workflow_id-on-`api_usage` index from migration 0009 is the load-bearing surface for cost-cohort queries (api_usage rows outnumber pm_current_focus rows by 100x+). Trigger to revisit: a real workflow-cohort query against `pm_current_focus` (e.g., "all foci bound to workflow X with their completion findings") shows up in operator-side ad-hoc SQL or as a typed output field on a future `get_focus_history` MCP tool. Touchpoint: `storage/sql/0010_pm_current_focus.sql` (new index would land in a follow-up migration).
- Add cohort indexes (`source_event_kind`, individually or in combination with `re_eval_state`) on `audit_sample_log` when sample-cohort queries become hot. Deferred at M5-continuous-pm-driver commit 4 prep (F5.2 Q5 disposition mirror of commit 2's pattern, 2026-05-05): commit 4 ships single composite index on `(re_eval_state, sampled_at)` supporting the future async re-evaluator's pending-row scan and operator-side time-window queries; row counts at default-disabled config (audit_sample_percentage=0.0) and even at low non-zero percentages stay well within sequential-scan bounds for cohort filters. Trigger to revisit: real workload at non-zero sampling percentage shows operators querying by source_event_kind repeatedly via ad-hoc SQL or via a future typed output on operator-facing observability surfaces. Touchpoint: `storage/sql/0012_audit_sample_log.sql` (new index would land in a follow-up migration).
- **Partial roster operation** (M5-subscription-routing commit 0.5): if a YAML's `reports_to` names a role whose token is missing from the keyring, that parent isn't in the registry's in-memory snapshot and `agents.seed_into_storage` sets `reports_to=NULL` for the dependent row rather than raising. `AgentRegistry.initial_load` already logs the unseeded-role warning; the seed reflects the partial roster faithfully in SQL. Behavior is deliberate, not silent failure — a future operator inspecting the agents table mid-roster (e.g., engineer YAML present but engineer token not yet issued) sees `implementer.reports_to=NULL` and `tester.reports_to=NULL`; that's the documented partial-roster shape. The next daemon restart with full keyring tokens issues the same UPSERT path and resolves `reports_to` to the parent's UUID. Touchpoint: `agents/registry.py:seed_into_storage`.
- **RESOLVED at M5-continuous-pm-driver commit 1 (2026-05-03):** `api_usage.workflow_id` population protocol resolved as **α-denormalization** (server-derived from chain root via recursive CTE in `tools/budget.py:_resolve_chain_root_cohort_keys_from_dispatch`; renamed at M4d commit 7 for symmetric task_type extension). Resolution precedence Reading B — server-derived wins; caller-supplied falls through; conflict logs `record_api_call.workflow_id_conflict` structlog WARNING. Per F1.1 / D1.1 disposition. Spec home: `docs/kickoff/06-mcp-tools-phase-one.md` § record_api_call. M4d commit 7 shipped symmetric `task_type` α-denormalization extension (commit 8.5 fixed the audit-vs-api_usage divergence at line 580). Entry kept (not deleted) as audit lineage — future watch-for-pattern entries reading this trail see how the discipline cycles from deferred → resolved → symmetric-extended. Marked RESOLVED at M4d commit 9 closeout per Decision 4 commit 1 carry-forward. Original three-protocol framing preserved below for historical context:
  - (α) `parent_task_id` chain root — derivable via JOIN through `tasks.parent_task_id`. Denormalization is redundant; argument for shipping it is cohort-query speed without recursive CTE. Lowest design surface; highest data quality (always populated wherever a task chain exists).
  - (β) operator-initiated correlation ID — a new tool (`begin_workflow` / `current_workflow_id` MCP wrapper, or env-var threading at PM-continuous spawn time) mints and propagates a workflow_id. Highest discipline; requires net-new infrastructure.
  - (γ) free-text agent self-tag — agents annotate at will. Lowest discipline; highest noise; risk of `workflow_id` becoming a synonym for `notes` and the cohort dimension collapsing.
  Cross-reference the observability roadmap's `current_focus` deliverable contract (narration tier 1 at M5-continuous-pm-driver) — workflow_id may naturally pair with the work-unit boundaries that contract introduces. Touchpoint: `tools/budget.py:RecordApiCallInput.workflow_id` (currently optional, default NULL).
- **RESOLVED at M4d-task-management-surface commit 1 (2026-05-07):** `api_usage.task_type` population protocol resolved as **γ-protocol** (operator threads `task_type` at `submit_task` time; agents inherit via the dispatch chain). Migration `0013_tasks_task_type_and_status_check.sql` adds `tasks.task_type` column with closed nine-value CHECK enforcing the **work-shape taxonomy** (Q1 disposition, 2026-05-07): coordination shape (`planning` / `dispatching` / `monitoring` / `review`) + production shape (`investigation` / `synthesis` / `execution` / `revision`) + `uncategorized` fallback. The taxonomy is product-agnostic infrastructure, not substrate-specific. M4d commit 7 extends the existing `workflow_id` α-denormalization recursive CTE in `tools/budget.py:record_api_call` to also resolve `task_type` from the chain root. Spec amendments at `docs/kickoff/05-data-model.md` § `tasks.task_type` and `docs/kickoff/06-mcp-tools-phase-one.md` § `submit_task`. Entry kept (not deleted) as audit lineage — future watch-for-pattern entries reading this trail see how the discipline cycles from deferred → resolved.
- Cohort breakdowns on `get_token_usage` output (deferred). Currently spec-as-written: `by_model`, `by_agent`. Cohort columns (`workflow_id`, `agent_role`, `task_type`) shipped at commit 1 of M5-subscription-routing but not exposed in `get_token_usage` output structure. **Trigger for revisiting:** if real-session investigation shows operators querying one cohort grouping repeatedly via direct SQL or via the future `get_audit_log` ad-hoc query path, promote that grouping to a typed output field on `get_token_usage`. Likely candidates in order of readiness: `by_agent_role` (auto-populated, complete coverage from day 1), `by_workflow_id` (depends on workflow_id population protocol resolution at M5-continuous-pm-driver), `by_task_type` (depends on task_type protocol resolution). Don't add all three at once; add by demand. Touchpoint: `tools/budget.py:GetTokenUsageOutput`.
- Web dashboard UI (Claude Code is the only Phase 1 operator interface)
- Cloud deployment (architecture is cloud-portable but Phase 1 is local-only)
- Multi-user support
- Lawyer, Accountant, Designer, Advisor agents (deferred indefinitely)
- Multi-recipient Telegram notifications (Phase 1 is single-chat-id)
- SAVEPOINT-based nested transactions in storage (Phase 1 has reentrant flat transactions)
- TLS for the MCP server (Phase 1 binds loopback only and runs plain HTTP — no over-the-wire threat on `127.0.0.1`. When Phase 2 deploys to a non-loopback bind, generate or import a properly-trusted cert and re-enable TLS in the uvicorn config.)
- Claude Desktop GUI Custom Connector support (Phase 1 targets Claude Code's connector flow because it uses RFC 8252 loopback redirects against a localhost daemon. Claude Desktop's GUI Custom Connectors are cloud-mediated — Anthropic's `160.79.104.0/21` traffic does the discovery — and cannot reach a local-only daemon. If Phase 2 needs Claude Desktop UX, the path is either a stdio-bridge subprocess in `claude_desktop_config.json` or a public ingress.)
- Manual `supervisor.halt()` clear without service restart (M4c+; supervisor.halt() currently requires service restart for recovery — adequate for Phase 1 since the operator is in the loop, but a future admin command to clear the halt without restart would improve operability when the underlying cause is well-understood and a full restart is heavyweight. Spec home: `docs/kickoff/11-engineer-bridge.md` § Cap escalation surface.)
- Retrofit supervisor respawn paths to single-SQL-transaction atomicity (M4c-compaction +). Both the heartbeat-timeout respawn and the compaction-driven respawn currently use the in-process `_current_dispatch_id is None` flag to gate the watchdog during the brief window where the old row is terminal but the new row hasn't been inserted yet. External readers (currently none) see a transient "no active supervisor row" state. When external readers are introduced — likely M5's PMSupervisor lifecycle visualization or M6's `notify_human` surfacing — wrap the old-row-terminal UPDATE and the new-row INSERT in a single SQLite transaction so external queries see consistent state at all times. Spec home: `docs/kickoff/11-engineer-bridge.md` § Engineer compaction (atomicity subsection).
- Auto-persona review-discipline ingraining (Phase 2 sub-milestone). The metrics-driven quality-signals discipline established in M5-continuous-pm-driver depends on reviewing-Claude + Mike paired-reviewer pattern. Auto-persona mode loses this pairing. Three pattern candidates for ingraining: (1) cross-persona review at architectural decision points; (2) operator spot-check ritual at sub-milestone boundaries with quality-signals pattern audit; (3) persona-prompt-level metrics-format requirement. Sub-milestone scope: design the cross-persona review protocol, define operator-spot-check ritual, bake metrics requirement into persona prompts. Trigger: Phase 2 transition, after first auto-persona sub-milestone has run enough to surface real failure modes.
- **Operator-tunable heartbeat timeout via environment variable.** The supervisor heartbeat timeout is hardcoded at `bridge/supervisor_base.py:274` (`timeout_seconds: int = 1800`; default 30 minutes) and `bridge/engineer_supervisor.py:75` (matching constructor default), and not exposed as a Settings env var. Pinned at M5-flag-flips commit 5 prep when memory's claimed `MERIDIAN_ORCH_ENGINEER_HEARTBEAT_TIMEOUT_SECONDS=1800` env var was empirically verified to NOT exist (memory drift caught at prep time per bidirectional verification-first discipline). Phase 1 default matches `engineer.yaml`'s "30 minutes silent triggers SIGTERM and respawn" promise; tunability isn't load-bearing pre-operational-data. **Trigger:** first instance of operational data showing the 30min default needs adjustment OR first operator request for shorter/longer timeout. **Fix scope:** add `MERIDIAN_ORCH_ENGINEER_HEARTBEAT_TIMEOUT_SECONDS` field to `config.py:Settings`; thread through `main.py` `EngineerSupervisor` construction; add explicit-default test mirroring the `pm_loop_tick_seconds` / `audit_sample_percentage` precedents (~5-10 LOC + 1 test). PM-supervisor heartbeat empirically verified at M5-flag-flips commit 6 to share the same hardcoded pattern (`bridge/pm_supervisor.py:87` matching constructor default; `main.py` constructs `PMSupervisor` without override, symmetric with `EngineerSupervisor`). Fix scope generalizes to both supervisors: add `MERIDIAN_ORCH_PM_HEARTBEAT_TIMEOUT_SECONDS` symmetric to the engineer field; thread through both `main.py` construction sites; one explicit-default test per supervisor (~10-15 LOC + 2 tests instead of the original ~5-10 LOC + 1 test for engineer-only). Touchpoints: `bridge/supervisor_base.py:274`; `bridge/engineer_supervisor.py:75`; `bridge/pm_supervisor.py:87`; `main.py` (both `EngineerSupervisor(...)` and `PMSupervisor(...)` construction sites in `_run_lifespan_body`'s post-`mcp_ready` block).
- **Extended stale-snapshot escalation for Engineer compaction trigger.** The `## Compaction trigger` subsection in `config/agents/engineer.yaml` (added at M5-flag-flips commit 5) gates compaction signaling on `snapshot_age_seconds <= 120`. When the snapshot is stale, Engineer continues normal iteration without signaling, rechecking on each iteration. Persistent stale-snapshot windows (snapshot ageing past 120s for many consecutive iterations) currently produce no escalation surface — Engineer keeps iterating with `pace_recommendation='unknown'` until the snapshot freshens. **Trigger:** first instance of stale-snapshot persisting beyond N iterations during real Engineer operation (operational observation post-flag-flip). **Fix scope:** define escalation threshold (N consecutive stale-snapshot iterations); decide escalation mechanism (operator alert via task note? automatic compaction-trigger override regardless of staleness? supervisor restart with diagnostic packet?). Cost: TBD; design decision required at trigger. **Related:** Phase 2 V2 roadmap entry "Degraded-state recovery / operator pacing control loop" (this file's tail) — stale-snapshot escalation likely subsumes under that governance layer's signal-tracking + recovery-mode framework rather than landing as a standalone Engineer-yaml refinement. Touchpoints: `config/agents/engineer.yaml` § Compaction trigger; `tools/budget.py:get_window_state` (snapshot_age_seconds source); future governance-layer module per V2 #9 placement.

## Conventions

**Migrated 2026-05-07** to `docs/conventions/00-README.md` per the
discrete migration trigger named in `docs/kickoff/00-README.md` —
fired during M5-continuous-pm-driver chain when convention count
crossed 5+. The conventions doc is the canonical home for durable
disciplines (sub-milestone closeouts, phase-boundary scans, sub-
milestone-boundary internal-spec-consistency scans, watch-for-
pattern thresholds, threshold-of-three same-commit extraction,
Settings field test colocation, carry-forward routing, code-
inspection sentinel observability, and others).

This section is left as a pointer for future-Claude reading the
chain process cold. Future durable conventions land in
`docs/conventions/00-README.md`, not here.

## Architectural patterns to review

Section established 2026-05-06 per M5-continuous-pm-driver F8.2 Q1-A novel-pattern flag (Decision 5 lazy-create rule). Patterns whose first instance lands during a sub-milestone but whose architectural implications warrant review when a second instance materializes. Trigger framing: "when second instance lands, evaluate whether <pattern> is becoming established convention (formalize) or one-off (document as exception)."

### Server-side blocking MCP tool

**First instance:** `wait_for_next_tick` (M5-continuous-pm-driver commit 7, `tools/pm_loop.py`). PM's continuous loop calls this tool at end of each iteration; server-side handler sleeps for `Settings.pm_loop_tick_seconds` then returns. Daemon-controlled cadence; PM-prompt-driven call pattern.

**Why flagged:** No existing Phase 1 MCP tool blocks server-side for arbitrary durations. Closest analog is none — even `get_window_state` returns immediately. Blocking-behavior pattern is novel for the daemon's MCP surface.

**Trigger to revisit:** when a second server-side blocking MCP tool lands (in any sub-milestone), evaluate:
- Is the blocking-MCP pattern becoming the convention for daemon-driven cadence / synchronization on subprocess agents? If so, formalize via shared base / utility (e.g., `tools/_blocking.py` with cancel-safety + timeout + clock-injection helpers).
- Or is it remaining one-off for the continuous-loop case? If so, document `wait_for_next_tick` as exception in `tools/pm_loop.py` module docstring; future blocking-MCP needs carry their own justification.

**Architectural alternatives** (for second-instance evaluation):
- HTTP long-poll pattern (server holds connection until event)
- Server-sent events / streaming (one-way push from server to subprocess)
- Event-driven loop (subprocess subscribes to events; server pushes when events fire — eliminates blocking entirely)

For Phase 1, blocking MCP is the simplest fit (CC subprocess blocks on its own MCP call regardless of server-side mechanism); pattern review only matters at second instance.

## Carry-forward to future sub-milestones

Section established 2026-05-05 per Decision 5 convention (M5-continuous-pm-driver F3.6.D, refined per M5-continuous-pm-driver F5.6.C). Lazy-create on first cross-sub-milestone carry-forward; entries name the destination explicitly + cross-reference detail-prose location. Distinct from `## Deferred` (which is for things to do at unspecified-future-time); this section is for routing notes from one sub-milestone to a specifically-named future sub-milestone.

### → focused-observability sub-milestone (post-M4d operational use; runtime-data-driven trigger): audit_sample_log re-evaluator

`audit_sample_log` is shipped at M5-continuous-pm-driver commit 4 (migration 0012, `audit/sampler.py`) with the **writer side complete** — `record_sampleable_event` MCP tool selects and persists rows with `re_eval_state='pending'`. The **async re-evaluator** (the handler that processes `pending → in_progress → complete` with `re_eval_finding` text; possibly LLM-driven; loads sampled rows + their source events; produces a re-evaluation finding for the silent-compliance defense) is **out of scope at M5-continuous-pm-driver** per F5.2 Q10 Reading A disposition.

Destination: post-M4d operational use; **trigger is runtime-data-driven cause** (40-80 hours continuous-mode operation, OR first "I can't explain this" event, OR sub-milestone close review revealing missed pattern). Updated 2026-05-07 per M5-continuous-pm-driver commit 9 prep F10.2 Q6 disposition; supersedes the earlier "post-M5-flag-flips" framing — M5-flag-flips is no longer the trigger because (β) operational-gate observability scoping made the trigger runtime-data-cause-driven rather than schedule-driven. The existing observability roadmap entry at `## Three concerns` § Sequencing names the destination as "a focused observability sub-milestone"; this entry pins the runtime-data-driven trigger condition explicitly.

Scope of the re-evaluator at the destination sub-milestone:

- Periodic poll (or hook-driven) over `audit_sample_log` WHERE `re_eval_state = 'pending'`.
- For each pending sample, load the source event from its canonical table (audit_log or pm_spin_rationale_log per the per-kind id semantic table in `05-data-model.md` § audit_sample_log).
- Re-evaluate the source event's outcome — likely LLM-driven (PM-as-reviewer judging its own work, or another agent acting as auditor) per BACKLOG observability roadmap framing.
- Update `re_eval_state` to `complete` with `re_eval_finding` text + `re_eval_completed_at` timestamp.
- Surface findings into tier-2 operator surfaces (the work that lands when the destination sub-milestone is scoped).

Detail prose: M5-continuous-pm-driver grounding doc § F5.2 Q10 Reading A disposition + § F5.6.C (b)-refined disposition (grep "audit_sample_log re-evaluator" within the grounding doc).

Watch shape during the writer-only window: at default-disabled config (`audit_sample_percentage=0.0`), no rows accumulate in `audit_sample_log`; the deferred re-evaluator has nothing to process. At non-zero operator-set percentages, rows accumulate with `re_eval_state='pending'` indefinitely until the destination sub-milestone ships. This is operationally acceptable at low percentages; if the operator enables high-percentage sampling before the re-evaluator lands, a manual SQL pass to clear stale pending rows is the bridge.

### → Phase 2 (operational-data-driven trigger): per-dispatch stderr log retention/rotation policy

M5-flag-flips F4 (commit 3) ships per-dispatch stderr capture at `<data_dir>/dispatches/<dispatch_id>/stderr.log` via `bridge/stderr_drain.py`. Files persist beyond worktree cleanup so post-mortem on a completed-then-cleaned dispatch is still possible. **No retention or rotation policy ships at F4** — files accumulate indefinitely under `<data_dir>/dispatches/`.

Phase 1 acceptance: at single-operator volume, accumulation is operationally fine for weeks-to-months. The persistent-by-default shape is load-bearing for the F4 disposition (Reading C: stderr drain to per-dispatch file as the operator-friendly diagnostic surface; cleanup-on-terminal-state would defeat the post-mortem purpose).

**Concrete trigger** (per F4 guardrail #6): first instance where stderr log directory size becomes operational concern OR first instance where operator manually deletes old stderr logs (the manual-deletion event is the operational signal that retention policy is now load-bearing).

**Fix scope at trigger:** evaluate three policy shapes:
- **Fixed-window TTL** (e.g., delete logs older than 30 days). Simplest; risks deleting still-referenced logs if Mike post-mortems an old dispatch.
- **Terminal-state-based cleanup** (e.g., delete logs for dispatches in `completed` state older than 7 days; preserve `failed` / `timed_out` / `unexpected_exit` indefinitely). Aligns retention with diagnostic value but adds dispatch-state-aware cleanup logic.
- **Operator-tunable policy** via `MERIDIAN_ORCH_STDERR_LOG_RETENTION_DAYS` (Settings field; default unset = no cleanup, opt-in to TTL). Defers the policy decision to operational data + Mike's preference.

**Touchpoints at trigger:** `bridge/stderr_drain.py` (cleanup helper, possibly invoked from a daemon-startup or periodic asyncio task); `config.py` (Settings field if operator-tunable); `BACKLOG.md` cross-reference here.

**Cost estimate:** ~2-4h depending on policy shape; trigger-driven so deferral cost is zero unless trigger fires.

## Future architectural work

Section established 2026-05-07 at M4d-task-management-surface commit 1 per Decision 5 routing convention (lazy-create on first entry). Distinct from `## Deferred` (which is for things to do at unspecified-future-time without a designed shape) and from `## Carry-forward to future sub-milestones` (which routes to a specifically-named destination). This section is for **designed-but-deferred-on-trigger architectural concepts** — the shape is sketched; a triggering condition is named; the work waits for the trigger.

### Per-product work_domain taxonomy

`tasks.task_type` at M4d describes work-**shape** (cognitive operation): nine values across coordination shape (`planning` / `dispatching` / `monitoring` / `review`) and production shape (`investigation` / `synthesis` / `execution` / `revision`) plus `uncategorized` fallback. The taxonomy is **product-agnostic infrastructure**: Meridian Orchestrator is product-agnostic, and `tasks.task_type` lives in the data model used by every product the orchestrator runs.

Per-product work_**domain** (e.g., `code_implementation`, `substrate_review`, `content_synthesis`) is product-bound and lives in the per-product context. Phase 1 ships work_domain as free-text in `tasks.notes` (or operator-supplied details on adjacent surfaces); a per-product taxonomy is **future architectural work** for when the orchestrator runs against multiple products.

**Designed shape** (sketch; refine when triggered):

- Operator-supplied enum at company instantiation: each company config names its own `work_domain` taxonomy as a closed enum scoped to its product.
- Per-company config table (e.g., `company_work_domain_taxonomy`): `company_id` × ordered list of valid `work_domain` values.
- Constraint enforcement: at `submit_task` time the handler validates `work_domain` against the caller's company's taxonomy if present (else free-text).
- Cross-company aggregation: cohort queries that span companies project on `task_type` (product-agnostic); intra-company queries can additionally project on `work_domain`.

**Triggers** (any one fires):

- Second product instantiated against the orchestrator (operationally distinct from Meridian-substrate; the per-product distinction becomes load-bearing for cohort queries).
- Cohort coherence problems surface from free-text work_domain in `tasks.notes` or `details_json` — operator-side ad-hoc SQL repeatedly groups on substring patterns or runs into typo / case / synonymy mismatches.
- Operator-facing surface (dashboard, utilization report) wants per-domain breakdown and finds free-text too noisy.

**Spec home**: `docs/kickoff/05-data-model.md` § future per-product work_domain section (created when the work lands).

**Cross-reference**: `docs/kickoff/06-mcp-tools-phase-one.md` § `submit_task` describes the work-shape vs work-domain split; M4d's `task_type` is the work-shape side.

### get_subtasks own-chain visibility (Phase 2 generalization)

`get_subtasks` at M4d ships visibility-filter as `claimed_by = caller_agent_id` for implementer/tester per CQ1 commit 9 Reading A disposition. Spec amended at line 880 to "own claimed (Phase 1 simplification)". Phase 1 single-implementer-per-task scenarios make the distinction operationally invisible.

**Phase 2 generalization shape**: implementer/tester see all subtasks under the parent that are in their dispatch chain — caller is in parent chain (raise-gate) AND any subtask under that parent is visible to caller. SQL filter shifts from `claimed_by = caller_agent_id` to a chain-walk filter (CTE through `parent_task_id` to identify all chain-root descendants visible to caller).

**Trigger**: Phase 2 multi-implementer-per-task scenario surfaces a case where caller is in parent chain but not directly claimed by any visible subtask (e.g., Implementer A's dispatch spawns Tester B + Tester C; Implementer A queries get_subtasks(parent) and would expect to see B + C even though A doesn't claim either).

**Spec home**: `docs/kickoff/06-mcp-tools-phase-one.md` line 880 + § get_subtasks. Amend to "own chain (full chain visibility)" and update implementation accordingly.

**Cross-reference**: `tools/tasks.py:get_subtasks` (current implementation; visibility-filter at the role-scope clause).

### Add cycle defense symmetric assertion for task_type α-denormalization

`tests/integration/test_record_api_call.py:test_workflow_id_alpha_cycle_defense_returns_none` verifies workflow_id returns None on chain overflow (cycle defense). Symmetric assertion for task_type would assert task_type also returns None on the same overflow case. T2 from codex review at M4d commit 8 close.

**Trigger**: cycle-defense behavior change for either field requires symmetric assertion landing.

**Cross-reference**: `tools/budget.py:_resolve_chain_root_cohort_keys_from_dispatch` returns `ChainRootCohortKeys(None, None)` on chain overflow; existing test only asserts workflow_id field.

**Cost**: ~5 LOC; extend existing test or add parallel `test_task_type_alpha_cycle_defense_returns_none`.

### set_task_priority audit from_priority race-condition under concurrent writes

`tools/tasks.py:set_task_priority` reads `from_priority` from the `_check_can_set_priority` helper return BEFORE entering the transaction; the helper SELECT happens outside transaction scope. Under concurrent set_task_priority calls (T1 reads priority=3; T2 reads priority=3; T1 UPDATE priority=1; T2 UPDATE priority=2), T2's audit `from_priority=3` is stale — actual prior state at T2's UPDATE was 1 (T1's result), not 3. F3 from codex post-close review at M4d commit 9 close.

**Trigger**: first instance where audit `from_priority` discrepancy causes operational confusion OR Phase 2 introduces multi-operator priority-change scenarios where the race window matters operationally.

**Fix options**:
- (1) `UPDATE ... RETURNING old_priority` (SQLite 3.35+ supports RETURNING) — single-query atomic from-state capture
- (2) Write-lock-with-retry (`BEGIN IMMEDIATE` + retry on conflict) — heavier; pessimistic
- (3) Predicate-with-conflict-path (`UPDATE ... WHERE id=? AND priority=?` for caller-supplied expected from-state) — caller-provides-prior-state shape

**Cost**: ~20-35 LOC plus concurrency test exercising the race window.

**Cross-reference**: `tools/tasks.py:set_task_priority`; `_check_can_set_priority` helper return shape includes `priority` field handler reuses.

### Replace `assert updated is not None` with explicit ConflictError raise in set_task_priority

`tools/tasks.py:set_task_priority` uses `assert updated is not None` after the UPDATE+SELECT pattern. Asserts strip with `python -O`; if a row is deleted concurrently between gate-check and SELECT (rare), the assert disappears in optimized builds and `Task(**dict(updated))` raises `TypeError` on None. Q1 from codex review at M4d commit 8 close.

**Trigger**: when delete_task tool ships (Phase 2) OR production deployment via launchd/systemd lands at M7.

**Cross-reference**: `tools/tasks.py:set_task_priority` near the `await write_audit(...)` call.

**Cost**: 3-line change; replace assert with `if updated is None: raise ConflictError(...)`.

### Generalize HUMAN_LITERAL_ROLE_MAP to pattern-based human-prefix resolution

`tools/tasks.py:HUMAN_LITERAL_ROLE_MAP` at M4d-task-management-surface commit 3 (2026-05-07) is a single-entry Phase 1 simplification mapping `"human:mike"` → `"pm"` for clause 2 of `submit_task`'s parent-ownership check. The literal arises from CQ3 Reading A: `submitted_by="human:mike"` for OAuth callers per `05-data-model.md:209-216` convention. Clause 2 of CQ4 Reading B requires resolving "the role that submitted the parent" — for UUID-shaped `submitted_by` values, JOIN to `agents` works; for the `"human:mike"` literal, the map provides the role.

Phase 2 generalization shape: when non-Mike OAuth callers land (multi-operator support; Phase 2 multi-tenancy; etc.), the literal becomes a pattern (`"human:<name>"`) and the map becomes a function — e.g., `_resolve_human_literal_role(submitted_by) -> str | None` that pattern-matches `"human:*"` and returns a role per operator config (or per-company defaults; or hardcoded human→PM convention).

**Trigger** (any one fires):

- Second `human:<name>` literal lands (operator other than Mike submits).
- First non-Mike OAuth caller lands (Phase 2 multi-operator; the OAuth provider issues tokens for non-Mike humans).
- Cohort breakdown surface (utilization report, dashboard) needs to distinguish OAuth callers from agents on aggregate views and finds the single-literal handling brittle.

**Spec home**: `docs/kickoff/06-mcp-tools-phase-one.md` § `submit_task` mentions task_type but not the literal-handling; future amendment when generalization lands.

**Cross-reference**: `tools/tasks.py:_check_can_submit_task_parent_ownership` is the call site; the special-case branch (`if submitter_role is None: submitter_role = HUMAN_LITERAL_ROLE_MAP.get(...)`) is where the generalization lands.

**Phase 1 acceptance**: 4-line special-case (constant + map + branch); two-site-in-same-commit (`submitted_by` populator + clause 2 resolver) was the threshold for module-level extraction at commit 3. Phase 2 trigger threshold is independent — the map exists; generalization is when the map outgrows the dict shape.

## Open questions deferred to later milestones

- M3: Engineer↔subagent bridge architecture choice (Option A leading; see `docs/kickoff/11-engineer-bridge.md`)
- M3: Continuous-mode PM driver mechanism (rides on bridge decision)
- M5: Concrete model routing logic in `check_budget_for_task` (stub at M5, refined as Mike observes)
- M6: Whether to fold notification tables into earlier migrations if no production state exists at M6 start


## Codex code review findings (round 1, ingested at commit 1 of M5-subscription-routing)

Five findings surfaced by Codex during M5-subscription-routing prep. Finding 1 (agents-table seeding gap) resolved at commit 0.5 (`4874c8e`). Findings 2-5 deferred per routing below; Codex's framing preserved verbatim in quotes, with routing annotation and touchpoint annotation outside.

- **Finding 2 — Concurrent transaction safety.** "Shared `_in_txn` boolean fails under concurrent MCP requests. The reentrant-transaction pattern from M5 commit 5 was load-bearing on `_in_txn`; if the boolean is shared per-storage-instance rather than coroutine-local, concurrent requests can interleave catastrophically (request B's writes committed/rolled-back by request A's transaction). Sequential tests pass; production concurrency breaks." Routing: address before M5-flag-flips (where concurrent MCP load becomes real with Engineer + PM running simultaneously). Fix shape: `contextvars.ContextVar[bool]` (coroutine-local) or per-connection storage state. Touchpoint: `storage/sqlite.py:_in_txn`.
- **Finding 3 — Dispatch timeout not enforced.** "`timeout_seconds` written to dispatch row but no watchdog. Subprocess hangs without calling `complete_dispatch` leaves dispatch open indefinitely." Routing: address at M5-flag-flips (where Engineer dispatching Implementer/Tester makes ordinary dispatches load-bearing). Fix shape: asyncio deadline in `_await_terminal` or separate watchdog task. Touchpoint: `bridge/dispatcher.py`.
- **Finding 4 — Subprocess pipe deadlock potential.** "`stdout=PIPE`/`stderr=PIPE` without drain tasks; OS buffer fills if Claude Code outputs enough; child blocks; looks like hung dispatch." Routing: address at M5-flag-flips. Fix shape: DEVNULL where output isn't needed, or background drain tasks. Touchpoint: `bridge/dispatcher.py:_default_launcher`.
- **Finding 5 — Tester `commit_hash` dropped.** "`DispatchTesterInput.commit_hash` required, docstring says recorded up front, but `dispatch_tester` doesn't pass through to `BridgeDispatcher.dispatch`. Observability loss, not runtime safety." Routing: M5-flag-flips or hygiene commit (lower urgency). Touchpoint: `tools/dispatch.py:dispatch_tester`.


# Obeservability
# Observability roadmap

Three first-class observability concerns share underlying data capture but answer different questions at different cadences. Ship as one design surface (cross-cutting toggle/discoverability framework) with three concerns × three depth tiers; sequence the cells across M5 sub-milestones, M6, and beyond.

This document is the canonical reference for the observability layer. The current design is documented first; the **Design lineage** section at the end records four rounds of critique-driven refinement so future sessions can audit what changed and why rather than silently regressing to earlier assumptions.

---

## Three concerns

**1. Agent self-narration.** "What are you working on, when can I see something." Each agent maintains current-focus state at work-unit boundaries; queryable any time; ETA carries confidence tag (concrete / conditional / unknown-with-rationale); slippage surfaces explicitly (original + revised, not hidden). Forcing function: agents declare deliverables before starting, can't invent them post-hoc.

**Narration is enforceable contract, not free-text description.** The `current_focus` state shape is a deliverable contract:
- `objective` (what's being accomplished)
- `output_type` (artifact / decision / analysis / etc.)
- `acceptance_criteria` (how completion is verifiable)

At work-unit completion, agent maps actual output → declared objective. Mismatch flagged in audit log as a finding, not silent. Converts narration from diary to commit log.

**2. Runtime fitness.** "Is the daemon doing its job — filling capacity, contracting when idle, JIT not spinning, cross-validating internally." Per-agent liveness windows (alive vs. shutdown over time); context utilization (how much of each agent's context is in active use); spin-rationale annotation (why is this agent kept alive — queued work / Mike-presence / heartbeat-cheap / forgot-to-shutdown); OAuth-vs-local accounting cross-validation delta; queue-fitness metrics (claim latency by priority/role, idle time per agent). Surfaced inline in agent status reports, as findings in sub-milestone close reviews, and via on-demand query.

**3. Cost interrogation.** "Did we spend too much, are prompts too broad, are personas doing too much on premium tokens, is there optimization across personas/tasks/models." Investigational not analytical — accessible raw data + ad-hoc query power, optional self-annotation by agents (free-text `notes` field on `api_usage`), no premature classification schema. Investigation tool is MCP query access to `api_usage` — no panel, no dashboard.

**Baseline scaffolding now (dimensions, not values):** Don't pre-compute baseline numbers. Do define baseline dimensions:
- Cohort keys: `workflow_id`, `agent_role`, `task_type` on `api_usage` rows
- Aggregation hooks: token-per-task, model-mix-per-chain, latency-per-claim

Baselines become derivable once enough data accumulates. Capture is upfront; derivation is later.

**Provisional causal taxonomy for fitness anomalies (expected to evolve):**
- *Supply-side* (queue starvation, capacity exhaustion)
- *Demand-side* (over-provisioning, idle agents, unnecessary spin)
- *Coordination* (dependency blocking, cross-agent waits)
- *Decision* (routing inefficiency, mis-prioritization)

Tier 2 reasoning maps anomalies to one (or more) categories with confidence: "likely supply-side, low confidence." Categories may merge, split, or be replaced as real patterns surface.

---

## Depth tiers (per concern)

**Tier 1 always on** (cheap, base awareness). **Tiers 2 and 3 opt-in, runtime-toggleable**, with cost estimates surfaced when offered.

| Concern | Tier 1 (always on) | Tier 2 (opt-in) | Tier 3 (opt-in, anomaly-scoped) |
|---|---|---|---|
| Narration | current-focus deliverable contracts; ETA-with-confidence-tag; slippage tracking; completion validation | verbose narration (rationale logging on every agent decision, not just boundaries) | explanatory drilldown — agent spends tokens reasoning *why* a decision was made when asked |
| Fitness | liveness windows; queue-fitness metrics; OAuth-vs-local cross-validation delta | per-call context-utilization tracking; spin-rationale annotation on every keep-alive-vs-shutdown decision | per-anomaly explanatory drilldown (agent reasons about *why* an anomaly happened, scoped to one investigation) |
| Cost interrogation | `notes` field + cohort keys (`workflow_id`, `agent_role`, `task_type`) on `api_usage`; raw query access | per-call complexity annotation; per-call routing-rationale annotation (why Opus vs Sonnet) | prompt-content sampling for outlier calls (e.g., input >50K tokens; sampled and stored for post-hoc audit) |

Tier 1 of each concern roughly matches the data-capture work that rides along with existing M5 sub-milestones at near-zero marginal cost. Tier 2 and 3 are the opt-in surfaces.

---

## Toggle infrastructure (cross-cutting)

`set_observability_level(scope, level)` MCP tool. Scope is per-concern (narration / fitness / cost); level is `tier1` / `tier1+tier2` / `tier1+tier2+tier3` or finer-grained per-feature flags. State persisted in a config table, runtime-flippable without restart.

**Cost estimates surfaced at toggle-offer time.** When the daemon recommends enabling a tier, it names the overhead (e.g., "~3% token overhead", "~50ms added per agent call", "~2KB per audit log row"). Operator decides knowing the cost.

**Off-ramp / sunset rules.** Tier 2 and 3 toggles auto-disable after 24 hours unless renewed (configurable per scope). Prevents silent runtime-cost accrual when a toggle is flipped on for an investigation and forgotten. Renewal is one-tool-call. Sunset event logs to audit log.

### Observability state log (queryable timeline)

Table `observability_state_log`:
- timestamp
- scope (per-concern: narration / fitness / cost)
- tier (tier1 / tier1+tier2 / etc., or per-feature flags)
- trigger reason (operator-explicit / nudge-acceptance / sunset / etc.)
- duration (set on transition out)

Every toggle state change writes a row. Investigations query this alongside `api_usage`, `tasks`, `audit_log` to reconstruct system state at any point. Required for causal reconstruction, false-positive suppression ("was tier 2 even on?"), toggle-effect attribution.

---

## Hard meta-cost budgets with priority profiles

**Hard percentage caps per concern** (defaults: narration ≤ 5%, fitness ≤ 3%, cost ≤ 2%; configurable). Circuit-breaker behavior when exceeded — degrades to tier 1 for the offending concern.

**Priority profiles tied to system phase, not static ordering.** Under cap pressure, the lowest-priority concern in the active profile degrades first.

| Profile | Priority order |
|---|---|
| Active execution | narration > fitness > cost |
| Anomaly investigation | fitness > narration > cost |
| Review / post-mortem | cost > fitness > narration |

**Profile transition triggers (explicit, logged in `observability_state_log`):**
- *Active execution → anomaly investigation:* anomaly detection above severity threshold; or operator explicit signal ("something feels off"); or sustained metric divergence
- *Active execution → review/post-mortem:* operator invokes sub-milestone close review; or operator explicit signal ("let's review what just happened")
- *Investigation/review → active execution:* anomaly resolution; or operator explicit signal ("back to work"); or timeout fallback (default behavior)

Operator-overridable.

---

## Protective vs. optimizing autonomy boundary

Formalized design principle. Future surface additions classified explicitly.

**Allowed: protective autonomy.**
- Circuit breakers (cap exceeded → degrade to tier 1)
- Auto-sunset on toggles
- Bucket exhaustion → halt expensive operations
- System protects itself and the operator from runtime failure modes

**Disallowed: optimizing autonomy.**
- Routing decisions
- Behavior shifts toward presumed-better outcomes
- Any action whose intent is "system improves itself"
- Operator decides optimizations; system surfaces proposals

Distinction is durable, not contextual judgment. Future design discussions ask "is this protective or optimizing?" with a clean answer.

---

## Discoverability — toggles surface as recommendations

Toggles must be **obviously available**, not buried. Failure mode being protected against: hours into something feeling off, finally discovering "I could have turned on deeper introspection at hour two." That's tooling that exists but doesn't announce itself.

When the daemon (or an agent) notices something it can't fully explain — token spend above recent baseline, heartbeat-to-work ratio anomalous, queue depth growing without obvious cause, cross-validation delta widening, claim-vs-alive ratio low — the response is **a contextual recommendation in whatever artifact Mike is looking at**, naming the specific toggle that would help.

Examples:

> "PM heartbeat-to-work ratio is 4:1 in the last hour, higher than the 2:1 baseline. Don't know why yet. Want me to enable per-call rationale logging (narration tier 2)? ~3% token overhead; will let me explain the next time you ask."

> "M5-subscription-routing close review: subscription_7d_opus burned 67% during the chain. Higher than the 40% projected. Hard to explain post-hoc. Enabling per-call complexity-annotation (cost tier 2) for the next chain would let me audit Opus-vs-Sonnet routing at the next close."

> "Engineer was alive 3h22m this session, claimed-tasks 47min. Claim-vs-alive ratio low end of healthy. Could be normal (work shape) or routing inefficiency. Spin-rationale logging (fitness tier 2) would disambiguate."

### Discoverability constraints

- **Hard constraints:** max 1 recommendation per artifact; cooldown 30-60 min per anomaly type; confidence threshold before nudging.
- **Triggered by genuine signals:** anomaly thresholds (calibrated per-concern), explicit user cues ("something feels off"), recurrence patterns (asked about same anomaly twice).
- **High signal-to-noise bar.** If the daemon nudges on every routine status report, the nudges become background noise.
- **Recommendations name specific toggle + cost + expected gain**, not vague "want more info?"

### Nudge acceptance → outcome correlation, not just acceptance rate

Track nudge acceptance rate AND outcome correlation:
- Did accepting this nudge correlate with the operator subsequently being able to explain or act on something they couldn't before?
- Initial measurement is proxy (operator query patterns post-acceptance, e.g., did they ask about the same anomaly again within 24 hours).

If acceptance rate high without outcome improvement → triggers miscalibrated. Adjust thresholds. System self-calibrates against operator outcomes, not just operator clicks.

---

## Cross-signal disagreement detection

**Minimum-viable disagreement detection ships with tier 1 surfaces.** At artifact / status level:
- Cross-signal inconsistency detection (no resolution logic; just visibility)
- List of conflicting signals with their respective claims
- **Severity dimension** (low / moderate / high) derived from:
  - Magnitude of divergence (small variance vs. direct contradiction)
  - Number of signals involved (two-signal mismatch vs. all-three contradiction)
  - Confidence levels of the disagreeing signals

Example: narration says "task completed cleanly" (high confidence); fitness shows high coordination delay during that period (medium confidence); cost shows abnormal token spike (high confidence). Three-signal contradiction with high-confidence boundary signals → severity high.

Severity makes flag actionable, not just informative — operator triages by severity rather than mentally evaluating every disagreement.

Full reconciliation (resolving conflicting truths) remains future architectural work.

---

## Observability completeness model (structured, quantified)

Returned with every on-demand status query and embedded in artifact footers.

**Coverage by concern:**
- Narration completeness (% of work units with validated contracts)
- Fitness completeness (% of metrics active vs. possible at current toggle level)
- Cost completeness (% of calls with cohort keys + annotations)

**Missing critical signals:** binary flags (e.g., "context utilization OFF (tier 2 toggle)")

**Data freshness per concern:**
- Real-time (data current within seconds)
- Delayed (data current within minutes; specify lag bound)
- Stale (data significantly behind real-time; specify staleness)

**Confidence score per anomaly:** derived from completeness AND freshness in the relevant concern. A stale-data anomaly has lower confidence than a real-time one.

Quantified shape forces operator to see uncertainty, not skim past it.

---

## Proposals layer (counterfactual reasoning)

Future sub-milestone (after tier 1 fitness + cost data has accumulated). System simulates counterfactuals and proposes them; operator decides.

**Counterfactual scope (initial):** routing-class decisions where math is tractable (model A vs. model B for class of work, given recorded complexity scores and cost ratios). Defer richer counterfactuals (agent lifecycle, scheduling) until later.

**Trigger:** anomaly thresholds that fire discoverability nudges + operator explicit request ("propose alternatives for the last chain"). NOT periodic — that becomes background noise.

### Proposal contract (non-negotiable)

Every proposal carries:
- **Data coverage** (% of relevant history used)
- **Similarity basis** (how comparable past tasks are; cohort match strength)
- **Quality impact known vs. proxy** (no direct A/B, what proxy is used, what its limits are)
- **Confidence level** derived from coverage + similarity
- **Feasibility flag** (high / constrained / low) — not every counterfactual is operationally executable
- **Constraint description** (why not fully feasible — e.g., "12 of the 42 candidate tasks have hard sequencing dependencies on prior Opus context")

Example shape:

> "Projected token reduction: 18%. Basis: 42 prior tasks with similar complexity score (±1), same agent role. Coverage: 63% of last 7 days routing decisions. Quality impact: unknown (no direct A/B); proxy: Sonnet success rate 73% on comparable tasks. Confidence: low-moderate. Feasibility: constrained (12 of 42 candidates have hard sequencing dependencies on prior Opus context)."

Proposals are not autonomy. They're counterfactual reasoning surfaced to operator with explicit evidence basis. Reduces operator's analytical burden without reducing decision authority.

---

## Silent compliance defenses

Agents optimizing for passing validation rather than doing real work is a first-order failure mode, not a later refinement.

**Random audit sampling:** % of work units re-evaluated independently of agent's own narration. Sampling rate calibrated against tier-1 cost budget.

**Cross-signal checks:** correlation between narration length and actual work complexity (uniform narration is suspicious); low-anomaly-rate cross-checked against utilization variance (suspiciously smooth metrics).

**Audit rigor escalation triggers** (refinement on rejection of full adversarial-audit infrastructure):
- Sustained drop in anomaly rate + stable workload (suspicious smoothness)
- Increasing narration uniformity (text-similarity metrics on narration over time trending up)
- Declining variance in audit findings (audit results becoming homogeneous)

When triggered: temporarily increase audit sampling rate AND widen audit scope (deeper re-evaluation of sampled work units, not just spot-check). Logged as a phase transition in `observability_state_log`. Defense scales with apparent quietness rather than apparent loudness — "system looks too healthy" becomes a trigger, not a comfort.

Full adversarial-audit infrastructure (cross-agent re-evaluation, deliberately hard edge cases) deferred unless escalated audits surface evidence it's needed.

Findings written to audit log as integrity-check results.

---

## Intervention cost tracking with target behavior

Table `intervention_log`:
- timestamp
- trigger (anomaly nudge / proposal / direct operator query / etc.)
- action taken (toggle flipped / routing override / proposal accepted/rejected / no action)
- time to resolution (anomaly detected → resolved or normalized)
- linked anomaly_id / proposal_id

**Target behavior (expected trends, not just capture):**
- Interventions per workflow ↓ over time (system getting smarter or operator burden lower)
- Time-to-resolution ↓ over time (proposals + nudges get more accurate or operator response more efficient)
- Proposal acceptance correlates with intervention reduction (proposals are doing real work, not just being acknowledged)

Trends become first-class metrics surfaced in sub-milestone close reviews. Failure to trend in expected direction is itself a finding. Real KPI for system quality. Token efficiency without intervention reduction is a degenerate optimum.

---

## First-week operating posture

Reality is week 1 produces noise that looks like signal. Without explicit posture, the failure mode is operator misreading noise as system failure → "fixing" calibration artifacts → degrading clarity.

**Posture for the first sessions after observability layer ships:**
- **Anomaly thresholds:** loose (high tolerance) initially; tightened as data accumulates and baseline distributions form
- **Nudges:** conservative (high confidence threshold); reduced false-positive rate at cost of sensitivity
- **Proposals:** disabled or explicitly returning "insufficient data — calibration phase"
- **Operator expectation:** explicitly framed as calibration, not steady-state operation. Anomalies during this phase are noise unless persistent.

Posture is an operating state, recorded in config and logged. System exits calibration phase via explicit operator signal or after threshold metric stability is achieved.

Future Claude sessions reading this on day 4 of operation should see "we expected this to be noisy" instead of "we should redesign."

---

## Sequencing across milestones

**Tier 1 data capture rides along with M5 sub-milestones at near-zero marginal cost:**

- *During M5-subscription-routing (next):* `notes` field + cohort keys (`workflow_id`, `agent_role`, `task_type`) on `api_usage`. Cost-interrogation tier 1.
- *During M5-continuous-pm-driver:* PM's `current_focus` with deliverable contract + completion validation; liveness logging; spin-rationale logging. Random-audit-sampling infrastructure (sampling logic; the actual re-evaluation is async). Narration tier 1 + fitness tier 1 (PM scope).
- *During M5-flag-flips:* extend narration + liveness + spin-rationale to Engineer. First moment when liveness windows across multiple agents become observable. Cross-validation delta computation (OAuth poller vs. local `api_usage` aggregation) becomes useful.

**Tier 1 surfaces** (inline footers in agent status reports; findings in close reviews; on-demand `get_daemon_status` MCP tool; cross-signal disagreement detection; observability completeness model; intervention_log; priority profile transitions) ship post-M5-flag-flips as a focused observability sub-milestone, OR fold into M6 if M5 is running long. Order matters — surfaces ship after enough data has accumulated to make them non-empty.

**Scope clarification (2026-05-04, per M5-continuous-pm-driver F3.6.C Q6 disposition):** The "post-M5-flag-flips" sequencing on read-side surfaces applies specifically to **operator-facing tier-2 surfaces** — cohort queries, dashboards, ad-hoc SQL replacements like `get_audit_log`, and operator-targeted aggregations. It does **not** apply to **daemon-internal MCP reads** consumed by Phase 1 subprocess agents (PM-continuous reading its own `current_focus` to drive loop logic; Engineer reading `get_window_state` for mode-aware planning; analogous future cases). Daemon-internal reads ship alongside their writers in the sub-milestone that introduces the underlying state, because the consumer is committed (loop driver, mode-aware planner) and deferring forces the consumer's prep checkpoint to re-litigate read-side schema (output shape, empty-state contract, permission scope, caller-context) that should settle with the table-design conversation. Future-Claude reading this entry cold: don't apply the deferral to daemon-internal MCP reads where a Phase 1 subprocess agent is the planned consumer.

**Scope refinement (2026-05-04, per M5-continuous-pm-driver F4.2 Q4 + F4.5.1 reframe):** The "ship daemon-internal reads with their writers" rule above is conditioned on the consumer's read-shape matching the table's natural read-shape. `pm_current_focus` (commit 2) ships `get_current_focus` because the loop's read-shape is "most-recent-active row," matching the table's at-most-one invariant. `pm_spin_rationale_log` (commit 3) does NOT ship a read MCP tool because the consumer shape for spin-rationale is **aggregations** (time-windowed roll-ups, count-by-rationale_tag), not most-recent-active reads. Aggregation-shape consumers are operator-facing tier-2 work (ship post-M5-flag-flips per the original sequencing); deferring those does not create the prep-debt the F3.6.C reasoning warned about, because there's no co-design with table schema for an aggregation surface. Future-Claude evaluating "ship the read with the writer?" should ask: does the consumer's read-shape match the table's natural read-shape? If yes, co-ship (pm_current_focus). If no, defer (pm_spin_rationale_log).

**Toggle infrastructure** (`set_observability_level`, sunset rules, `observability_state_log`, audit-log integration) ships alongside Tier 1 surfaces. Off by default; tier 1 always on regardless.

**Tier 2 features** (verbose narration, per-call rationale logging, per-call complexity/routing annotation, context-utilization tracking) post-M6. Each tier-2 feature is its own scoped sub-milestone — implement, calibrate cost, expose toggle, add discoverability triggers.

**Proposals layer:** post-tier-2, requires accumulated data.

**Tier 3 features** (anomaly-scoped explanatory drilldown, prompt-content sampling for outliers): later still. Tier 3 is opt-in *and* anomaly-scoped — only relevant when an investigation is already in flight.

**Unified-truth reconciliation** (full resolution of cross-signal disagreements; currently only detection ships): named future architectural work.

---

## Future architectural concern (named, not solved)

### Unified truth reconciliation across narration, fitness, cost

Cross-signal disagreement *detection* with severity ships in tier 1 surfaces. **Reconciliation** (resolving disagreements rather than just flagging them) remains future work.

**Why not now:** Reconciliation requires knowing what disagreement looks like in this specific system. Designing the unified view before observing the parallel tracks risks specifying a framework that doesn't match how disagreements manifest.

**Trigger for revisiting:** After tier 1 has run enough sessions to characterize where the tracks diverge. Likely M6 or post-M6 work. The shape will probably involve a unified event/state model that all three concerns project from, with reconciliation rules at the projection boundary.

### Latent coupling between concerns

Narration verbosity affects token cost. Cost caps degrade narration. Degraded narration affects fitness interpretability. These are interacting systems with feedback loops, not parallel concerns with shared data.

Named as a known second-order-effects category. Don't try to solve now; flag for awareness during debugging if cross-concern symptoms emerge.

---

## Unaddressed failure modes (forced adversarial structure)

This section exists to prevent design drift toward "we've thought of everything."

1. **Outcome quality tracking absent.** No mechanism for "did this call succeed at its purpose." Proposals layer's quality-impact-unknown framing is honest but doesn't fix it. Adding outcome quality is its own design problem (who judges, on what timeline, with what evidence). Deferred.

2. **Cross-session operator behavior drift.** Operator interpretation of the same signal may drift across sessions. Mitigations available (operator notes on findings, periodic recalibration prompts) but not designed yet.

3. **Anomaly threshold calibration.** Initial values are guesses. First-week operating posture (above) addresses this; calibration is an active operating state, not a one-time setup.

4. **Proposals layer cold-start.** First weeks of operation, no proposals are surface-able. System should explicitly say "insufficient data for proposals" rather than degrading to low-confidence proposals.

5. **Privacy / sensitivity in tier 3 prompt sampling.** Sampling prompt content raises concerns if prompts ever contain confidential context. Sampling-with-redaction or sampling-with-explicit-opt-in per call needed. Defer to tier 3 design.

6. **Toggle-state recovery after daemon crash.** Resume previous state from `observability_state_log`? Reset to defaults? Operator-confirm-on-restart? Design choice deferred.

7. **Evidence-basis gaming.** Once proposals declare evidence basis, agents can game it (cherry-pick favorable cohorts to produce confident proposals). Audit-sampling defense addresses some of this; full defense requires independent evidence-basis verification, not designed yet.

---

## Meta-cost flag

Three first-class observability concerns means three first-class places where the daemon spends tokens on itself. Self-narration writes to state at every work-unit boundary; fitness logging at every agent state transition; cost annotation potentially per API call.

Tier 1 is cheap (mostly database writes; agent self-annotation is short text). Tier 2 starts costing real tokens (rationale logging is multi-sentence). Tier 3 can be expensive (per-anomaly drilldown is dedicated reasoning). Hard percentage caps (above) prevent runaway. The observability of *that meta-cost* is itself a question — track separately via the `notes` field tagging or dedicated audit segregation.

---

## Open design questions (defer to focused observability sub-milestone)

- Anomaly-threshold calibration values. Initial values are guesses; need real-session data.
- Recommendation cadence ceiling. How many nudges per artifact / per session before signal-to-noise drops? Probably 1-2 per artifact max; worth a hard cap.
- Toggle scope granularity. Per-concern (narration/fitness/cost) is the obvious split; per-feature flags within a concern is finer. Pick at design time based on whether operators want the granularity.
- Prompt-content sampling (tier 3 cost) sampling-with-redaction details.

---

# Design lineage

The current design above reflects four rounds of adversarial review. This section preserves the round-by-round refinement chronology so future sessions can audit decisions rather than rediscover them. Round-by-round structure prevents silent regression to earlier assumptions.

Each change uses the structure: **Change → Original assumption → Failure mode → New design.**

## Round 1 — initial design

Three concerns × three depth tiers. Toggle infrastructure with sunset rules. Discoverability via contextual nudges. Cost-aware (meta-cost flagged). Sequencing across M5 sub-milestones.

Original framing was operator-mood-driven checkpoint pattern: Mike opens a website or looks at a produced result and either says "great" or puts strategic directions forward. Runtime fitness signals woven into artifacts, not a separate dashboard. On-demand status via `get_daemon_status` MCP tool answering "what are you working on, when can I see something."

## Round 2 — structural enforcement

**Narration becomes enforceable contract.** *Original:* free-text declarations + "discipline" of declaring before starting. *Failure mode:* coherence without accountability; storytelling appearing consistent but semantically hollow. *New:* deliverable contract structure (objective / output_type / acceptance_criteria) with completion validation mapping output → declared objective; mismatch is audit-log finding.

**Observability state queryable timeline.** *Original:* toggle state is config; current state is what matters. *Failure mode:* irreproducible findings ("was tier 2 even on?"). *New:* `observability_state_log` table with timestamp / scope / tier / trigger reason / duration.

**Hard meta-cost budgets with priority ordering.** *Original:* visibility-only meta-cost tracking. *Failure mode:* observability cannibalizes throughput non-linearly. *New:* hard percentage caps with circuit-breaker degradation; priority ordering (initially static) for which concern degrades first under pressure.

**Discoverability constraints + acceptance correlation.** *Original:* "high signal-to-noise bar" hand-wave. *Failure mode:* nudges fire too often (ignored) or too rarely (missed leverage); optimizing for clicks rather than understanding. *New:* hard constraints (1 recommendation per artifact, cooldown windows, confidence thresholds) + track acceptance correlated with outcome improvement, not just acceptance rate.

**Baseline scaffolding (dimensions, not values).** *Original:* baselines emerge from running the system. *Failure mode:* without scaffolding, data is shapeless and clean baseline derivation later impossible. *New:* cohort keys + aggregation hooks captured upfront; baseline values derived later.

**Provisional causal taxonomy.** *Original:* taxonomy emerges from observed patterns. *Failure mode:* pure emergence produces inconsistent labeling, fragmented reasoning. *New:* four-category provisional taxonomy (supply-side / demand-side / coordination / decision); explicitly expected to evolve.

**Proposals layer (middle path between observe-only and autonomy).** *Original:* binary choice between observation and autonomous-actuation; rejected the latter. *Failure mode:* false binary; missed the middle layer where system simulates counterfactuals and operator decides. *New:* proposals layer as future sub-milestone scoped to routing-class counterfactuals; non-negotiable evidence-basis declaration (coverage / similarity / quality-impact / confidence).

**Silent compliance defenses first-class.** *Original:* deferred as "later scoping." *Failure mode:* first-order, not later-order; agents converge to passing the system before tier 2 ships. *New:* random audit sampling + cross-signal correlation checks ship from the start.

**Observability completeness as structured quantified model.** *Original:* "how much you don't know" as narrative footer. *Failure mode:* operators over-trust partial views; false confidence. *New:* structured coverage by concern + binary flags for missing signals + per-anomaly confidence scores.

**Intervention cost tracking.** *Original:* track token cost (productive) and observability cost (meta) only. *Failure mode:* a system that looks efficient by token metrics but requires constant operator intervention is failing. *New:* `intervention_log` table with trigger / action / time-to-resolution.

## Round 3 — refinement

**Cross-signal disagreement detection (timing pulled forward).** *Original:* unified truth as future architectural concern, post-M6. *Failure mode:* divergence happens immediately, not later; operator must manually triangulate every cross-signal mismatch. *New:* minimum-viable disagreement flag with severity dimension ships in tier 1 surfaces; full reconciliation stays deferred. Severity (low / moderate / high) derived from divergence magnitude + signals involved + confidence levels.

**Counterfactual feasibility constraint.** *Original:* evidence basis sufficient for proposal credibility. *Failure mode:* proposals can be analytically valid but operationally impossible. *New:* feasibility flag (high / constrained / low) + constraint description added to non-negotiable proposal contract.

**Data freshness as completeness dimension.** *Original:* completeness = coverage + missing signals + anomaly confidence. *Failure mode:* completeness ≠ timeliness; stale data with high coverage produces over-trust. *New:* per-concern data freshness (real-time / delayed / stale) added to completeness model; anomaly confidence incorporates freshness.

**Explicit transition triggers for priority profiles.** *Original:* phase-based profiles (active / investigation / review) with implicit transitions. *Failure mode:* implicit transitions produce inconsistent priority behavior. *New:* explicit triggers for each transition direction, logged in `observability_state_log`.

**Intervention cost target behavior.** *Original:* `intervention_log` captures events; trends observable post-hoc. *Failure mode:* logging without target produces passive data collection. *New:* expected trend behavior defined (interventions per workflow ↓, time-to-resolution ↓, proposal acceptance correlating with intervention reduction); failure to trend is itself a finding.

**Protective vs. optimizing autonomy boundary formalization.** *Original:* "no control loop / no autonomy." *Failure mode:* conceptual inconsistency — circuit breakers + caps + auto-degradation already ARE autonomy; hand-waving invites future drift. *New:* explicit classification (allowed: protective; disallowed: optimizing) as durable design principle.

**Audit rigor escalation triggers.** *Original:* random sampling alone as silent-compliance defense. *Failure mode:* sampling has limited sensitivity to drift signals that look like calmer-system rather than weirder-system. *New:* triggers for escalating audit rigor (sustained anomaly drop + stable workload, increasing narration uniformity, declining audit-finding variance) → temporarily widen audit scope. Full adversarial-audit infrastructure rejected; escalation infrastructure accepted.

**First-week operating posture (named expectation).** *Original:* implicit assumption of calibrated thresholds, meaningful anomaly detection. *Failure mode:* week 1 produces noise that looks like signal; operator "fixes" calibration artifacts. *New:* explicit first-week posture (loose thresholds, conservative nudges, proposals disabled, calibration-phase framing) documented and recorded in config.

## Critique-loop discipline note

After four rounds, inflection point reached. Round 5 explicitly declined.

**Reasoning preserved verbatim:** "Risk has shifted from design flaws → interpretation errors. The system is now strong enough that bad operator conclusions become the main failure mode. You will be tempted to 'fix' issues that are actually calibration artifacts. The next phase is not 'improve the design' — it is 'learn how the design behaves under real conditions without overreacting.' If you keep iterating at design-level now, you'll degrade clarity and introduce unnecessary coupling."

This applies to future sessions reading this entry: **do not re-open observability design questions during M5 or initial operations without specific runtime-data-driven cause.** The next meaningful improvements come from real anomaly patterns, real intervention cost trends, real disagreement frequency — not pre-runtime critique.

If a future session feels the urge to refine this design before runtime data exists, that urge IS the failure mode being warned against. The right response is to ship and observe.

## Degraded-state recovery / operator pacing control loop

**Phase**: 2 architectural evolution / methodology-as-product
**Status**: Deferred from M5-flag-flips disposition 2026-05-09
**Trigger for prioritization**: First real dogfooding session where the operator becomes the recovery glue after throttle/context rotation fails, OR repeated manual pacing/adjudication is needed to preserve quality.

### Rationale

Product-relevant but not Phase 1-critical. The correct abstraction is not "take a break" or calendar-time rest. Orchestrator should not depend on "tomorrow" as a primitive. The right model is state-based recovery: detect degraded quality signals, enter a named recovery mode, and exit only when explicit conditions are met.

### Scope

- Soft signaling only by default. No hard paternalistic enforcement.
- System surfaces quality-risk telemetry and recommended recovery modes.
- Human remains able to override, continue, pause, or change priority.
- Goal: reduce decision load and preserve work quality, not moralize rest.

### Signals to track

- Context pressure / compaction pressure
- Budget or subscription-window pressure
- Repeated correction loops or reversal rate
- Same issue reopened repeatedly
- Conflicting reviewer conclusions
- Test instability or flaky retry behavior
- Dispatch timeout / heartbeat failures
- Tool/runtime failures
- High unresolved finding count
- Rapid high-stakes decision cadence
- Late-stage closeout or post-close fix churn
- Operator interaction pattern suggesting overloaded decision queue

### Recovery modes

- Compact and resume from handoff
- Reduce concurrency
- Downgrade risk: switch from implementation to read-only review/synthesis
- Split work into smaller tasks
- Freeze implementation and launch adjudication
- Spawn independent reviewer/root-cause agents
- Produce options matrix with exact citations
- Queue implementation tasks but defer execution until explicit approval
- Run diagnostic loop / minimal reproduction
- Roll back to last known good checkpoint when appropriate
- Mark task blocked with diagnostic packet
- Continue unrelated safe work around the blocker

### Escalation rule

If throttle/reduced-concurrency does not recover, do not stall. Escalate from pacing control to fault isolation:

1. Identify the binding constraint
2. Fork recovery paths where safe
3. Roll back or isolate if needed
4. Ask the operator for one bounded decision only when required
5. Preserve diagnostics and continue safe work if blocked

### Exit criteria

Each recovery mode must define clear exit conditions, such as:

- Fresh context spawned and handoff accepted
- Tests stable for N runs
- Reviewer disagreement adjudicated
- Human decision recorded
- Budget window recovered or model route changed
- Blocker isolated and backlog/fix task opened
- Last known good checkpoint restored

### Architectural placement

Lives above individual Engineer/Implementer/Tester as a PM/supervisor governance layer. Not a subprocess "break" feature. Quality-preserving control loop for a human-operated AI delivery system.

### Connection to V2 roadmap

- Connects to V2 #7 (observation as structured event emission, not wrapper-side mutation): governance layer subscribes to event stream, doesn't participate in command execution.
- Connects to methodology-as-moat #12: pacing discipline + verification-first + closeout discipline are parts of cumulative methodology; this entry extends the methodology into the product as governance-layer feature. The methodology becomes a product surface.
- State-based recovery vs calendar-time rest is a Phase 2 design principle that generalizes beyond pacing — applies to any quality-protection mechanism in continuously-running systems.