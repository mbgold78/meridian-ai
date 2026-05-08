# M5-continuous-pm-driver — design-grounding findings

**Status:** pre-prep grounding doc, not the prep checkpoint. Findings to
inform Mike + reviewing-Claude iteration before scope decomposition opens.

**Path convention proposal:** `docs/working/` is a new directory introduced
by this doc. Suggested role: pre-prep working artifacts that may not
survive to the closeout (analyses, exploratory drafts, grounding scans).
`docs/kickoff/` remains canonical spec; `docs/closeouts/` remains
chain-wide retrospectives. Distinct from both.

---

## Section 1 — workflow_id / task_type protocol resolution

BACKLOG enumerates α/β/γ candidates for each. Recommendations below; I
mark one as gating disposition where the choice is genuinely contested.

### workflow_id — recommend α-denormalized (NOT contested)

**Recommendation: α (parent_task_id chain root), with denormalization at
write time** — the column is populated from the resolved chain root on
every `record_api_call` write, NOT recomputed at query time via WITH
RECURSIVE CTE.

**Reasoning grounded in what M5-continuous-pm-driver actually needs:**

- The continuous loop's natural unit of work is "a task chain rooted at a
  PM-submitted top-level task." `tasks.parent_task_id` already encodes
  the chain shape. The chain root IS the workflow.
- Denormalization (write-time stamping) makes the existing
  `idx_api_usage_workflow` index load-bearing. The pure α framing
  ("derivable via JOIN through `tasks.parent_task_id`") leaves the
  column NULL and the index vestigial. Population at write time costs
  one extra SELECT per `record_api_call` — cheap given the call rate.
- Zero PM/agent burden. No prompt amendments. No new MCP tool. No
  net-new infrastructure. Population happens in the wrapper layer.

**Reasoning grounded in downstream observability constraints:**

- BACKLOG line 37 names by_workflow_id as a likely cohort breakdown
  candidate that depends on this protocol. α-denormalized makes
  `SELECT ... GROUP BY workflow_id` immediately tractable; α-via-JOIN
  pushes a recursive CTE into every cohort query.
- The observability roadmap's "capture is upfront; derivation is later"
  principle (BACKLOG observability § Baseline scaffolding) argues for
  populating the column now even if the cohort query doesn't ship in
  this sub-milestone. Capture is the sub-milestone obligation; surface
  is the future obligation.

**Trade-off (named, not hedged):**

- Calls without a parent_task_id chain (e.g., Mike's interactive PM
  session calling `record_api_call` directly without first calling
  `submit_task`) leave workflow_id NULL. That's correct — interactive
  Mike isn't a workflow in the cohort sense; it's ad-hoc. The cohort
  query reports unattributed calls as `workflow_id IS NULL`, which is
  the honest answer.

**Implementation shape (not for decision here, sketched for Section 6
question-quality):** wrapper-side helper `_resolve_workflow_id_for_call`
that walks `tasks.parent_task_id` from the caller's bound dispatch
(if any) until parent is NULL, returns the root id. Stamped on
`api_usage.workflow_id` in the same `record_api_call` transaction.

### task_type — gating disposition (γ recommended; β is lower-scope competitor)

**Recommendation under no-scope-pressure: γ (operator/PM threads at
`submit_task`; propagates through dispatch chain to subprocess
record_api_call).**

**Recommendation under scope-pressure: β-with-PM-classification (PM
self-tags via `record_api_call.task_type`).**

**Why this is contested between γ and β** (and why α is rejected):

- α (closed-set enum at the migration / Pydantic Literal level) is a
  category error in the BACKLOG text — see Section 3 drift finding. The
  enum BACKLOG suggests for α (`planning` / `implementation` /
  `testing` / `review`) is plausible but not yet validated against real
  workload patterns; locking it in at Phase 1 risks forced-fit.
- γ has the right cohort-key shape (PM has the workflow context;
  Engineer/Implementer/Tester don't) but expands the sub-milestone scope
  to include: (a) `submit_task` schema extension, (b) propagation
  through Engineer's dispatch composer to subprocess env or spec, (c)
  subprocess record_api_call call sites threading the value. The
  propagation mechanism is itself a small architectural commitment.
- β is the lowest-scope option that still gets meaningful cohorts.
  PM-classification (NOT the "agents self-tag from fitness taxonomy"
  shape — see Section 3 drift) means PM annotates `record_api_call`
  calls at its own discretion. Engineer/Implementer/Tester do NOT
  classify. Coverage is partial-but-coherent: PM's calls have task_type
  cohorts; subprocess calls are NULL. Cohort queries can join
  subprocess calls back to PM's parent workflow_id (Section 1 α) for
  inheritance at query time. Lower information density than γ; no
  schema or dispatch-chain expansion.

**The contested dimension:** scope. γ is the better cohort key. β is the
cheaper sub-milestone. Mike-decidable.

**Sub-question to surface in Section 6:** if γ, does the propagation
mechanism (b) ride at this sub-milestone, or split off as its own
sub-milestone behind submit_task / dispatch-chain extension?

---

## Section 2 — observability constraints on the continuous loop

The observability roadmap's "ship and observe — don't reopen design"
note (BACKLOG § Critique-loop discipline note) is binding. This section
asks: what telemetry MUST the loop emit so already-designed tier-2 / tier-3
surfaces have the data when they ship — not what should be redesigned.

Per the roadmap's sequencing section, M5-continuous-pm-driver's
observability scope is:

- PM's `current_focus` deliverable contract (objective / output_type /
  acceptance_criteria) + completion validation.
- Liveness logging (PM-supervisor scope).
- Spin-rationale logging (per keep-alive-vs-shutdown decision).
- Random-audit-sampling infrastructure (sampling logic; async re-eval
  follows).
- Narration tier 1 + fitness tier 1 (PM scope).

### Required field gaps vs what M5-subscription-routing landed

| Required for tier-1 capture | Currently captured? | Gap shape |
|---|---|---|
| `current_focus` state (objective / output_type / acceptance_criteria) | NO | Net-new persistence. Options: dedicated `pm_current_focus` table; new column on `pm_mode_log`; separate event log keyed to dispatch_id. Decision belongs in the prep checkpoint. |
| Completion validation (actual output → declared objective mismatch flag) | NO | Net-new audit-finding type. Hooks into the existing `audit_log` table; new event name (e.g., `pm.current_focus.contract_violation`). |
| Liveness windows (PM alive vs. shutdown over time) | PARTIAL — `dispatches.role='pm-continuous'` rows + their state transitions give this for free | No new persistence; query-side derivation against `dispatches`. |
| Spin-rationale annotation (why kept alive vs shutdown) | NO | Net-new column or table. Each PMSupervisor "kept alive at iteration N because reason X" decision needs a rationale tag. PMSupervisor's existing respawn audit covers transitions, not steady-state retention rationale. |
| Random-audit-sampling infrastructure | NO | Net-new sampling-percentage config + async re-evaluator. Storage for sample selections + their async outcomes. |
| OAuth-vs-local accounting cross-validation delta | PARTIAL — both halves are captured (`/api/oauth/usage` snapshot in poller cache; `api_usage` aggregation via `get_token_usage`). Delta NOT computed. | Computed surface, not new capture. Query-side derivation. |
| Cohort keys on `api_usage` | YES — shipped at commit 1 (notes / workflow_id / agent_role / task_type). | None on this row. workflow_id population protocol: Section 1. |

### Net-new persistence required at this sub-milestone

The four gaps marked NO in the table above translate to specific
persistence work:

1. **`current_focus` state** — one new primitive. Either a table
   (`pm_current_focus` with id / pm_mode_log_id / objective /
   output_type / acceptance_criteria / declared_at / completed_at /
   contract_validated_bool / contract_finding_text) or a column-on-
   pm_mode_log shape. The table shape gives clean per-focus history
   without the pm_mode rate; column-on-pm_mode_log conflates two
   semantically distinct event types. Lean toward a dedicated table;
   surface for prep-checkpoint disposition.

2. **`pm_spin_rationale_log`** — one new table. Schema sketch:
   id / iteration_timestamp / decision (kept_alive / shutdown_recommended) /
   rationale_tag (queued_work / mike_presence / heartbeat_cheap /
   forgot_to_shutdown — closed enum) / rationale_free_text. Iteration
   cadence = once per loop tick (~30s? — surface for prep). Roll-up
   queries fold this into fitness tier 2.

3. **`audit_sample_log`** — sampling-percentage config + async sample
   selection + async re-evaluation outcome storage. Schema sketch:
   id / sampled_at / source_event_kind (current_focus_completion /
   dispatch_completion / etc.) / source_event_id / re_eval_state
   (pending / in_progress / complete) / re_eval_finding /
   re_eval_completed_at. Not load-bearing for the loop's own decisions;
   load-bearing for silent-compliance defenses (BACKLOG observability
   § Silent compliance defenses).

4. **Completion-validation finding type** — extends `audit_log`'s
   existing `event_kind` enum. No new table; new event name. Lowest-
   surface gap.

### Telemetry the loop must emit per iteration (no net-new persistence required)

These reuse existing surfaces:

- `record_api_call` on every Claude API call PM makes (already shipped;
  workflow_id population per Section 1 makes the cohort cohorts).
- `set_pm_mode` on mode transitions (already shipped).
- `audit_log` writes for current_focus declarations, contract-completion
  events, contract violations (existing audit_log event-kind extension).

### Compaction-at-80% interaction (forward-reference to Section 5)

PM-side compaction prep at `subscription_5h` conserve (>= 80%) needs
the loop to write a handoff doc and signal compaction. This is a
narration tier 1 + addendum-constraint cross-cutting concern — see
Section 5.

---

## Section 3 — BACKLOG drift

Drift-scan: BACKLOG text vs M5-subscription-routing landed reality. Two
findings — one real category error, one mild oversimplification.

### Finding 1 — real drift: task_type β option conflates two taxonomies

BACKLOG line 34 (under "Resolve `api_usage.task_type` population
protocol at M5-continuous-pm-driver"):

> (β) agent-classification — agent self-tags from the four-category
> provisional fitness taxonomy in BACKLOG § Observability roadmap
> (supply-side / demand-side / coordination / decision) or a different
> per-call taxonomy yet to be defined.

The four-category taxonomy referenced (`supply-side / demand-side /
coordination / decision`) is the **fitness-anomaly causal taxonomy** in
the observability roadmap, not a task taxonomy. Re-reading the roadmap:

> **Provisional causal taxonomy for fitness anomalies (expected to
> evolve):**
> - *Supply-side* (queue starvation, capacity exhaustion)
> - *Demand-side* (over-provisioning, idle agents, unnecessary spin)
> - *Coordination* (dependency blocking, cross-agent waits)
> - *Decision* (routing inefficiency, mis-prioritization)

Those categorize WHY anomalies happened, not WHAT a task is. A task is
not "supply-side" — that label categorizes a runtime fitness signal,
not the work being done. Conflating them in the β option means the β
option as written would degrade cohort coherence (rows tagged
`supply-side` would mix tasks-of-many-kinds whose only commonality was
that someone-once-thought-they-related-to-supply-side reasoning).

**Disposition:** rewrite β before consuming it as a real candidate. β
should specify "agent-classification with a task-shape taxonomy yet to
be defined" (and acknowledge the taxonomy is the harder problem). My
Section 1 task_type recommendation rejects α (forced-fit closed enum)
and recommends γ (PM-tagged at submit_task) or β-with-PM-classification
under scope-pressure. Both flavors of β I named are
PM-self-tag-with-coherent-shape, NOT the BACKLOG-as-written
fitness-taxonomy-self-tag.

**Action:** BACKLOG amendment shipping at the prep checkpoint or commit
1 of M5-continuous-pm-driver — strike the fitness-taxonomy parenthetical
from β, replace with a more accurate framing. Treated as drift fix, not
design reopening (the don't-reopen note guards against design drift,
not text correctness).

### Finding 2 — mild oversimplification: tier-1-rides-along framing

BACKLOG observability § "Depth tiers (per concern)" closing line:

> Tier 1 of each concern roughly matches the data-capture work that
> rides along with existing M5 sub-milestones at near-zero marginal
> cost.

Reality at end of M5-subscription-routing: only **cost-interrogation
tier 1** has shipped (notes + workflow_id + agent_role + task_type
cohort keys at commit 1). Narration tier 1 + fitness tier 1 land at
M5-continuous-pm-driver per the same roadmap's Sequencing section,
which IS more specific:

> *During M5-continuous-pm-driver:* PM's `current_focus` with
> deliverable contract + completion validation; liveness logging;
> spin-rationale logging. Random-audit-sampling infrastructure
> (sampling logic; the actual re-evaluation is async). Narration tier
> 1 + fitness tier 1 (PM scope).

The "roughly matches" framing oversells the rides-along claim; the
narration + fitness tier 1 capture is genuine NET-NEW work at this
sub-milestone, not zero-marginal-cost ride-along. The Sequencing
section is accurate. The Depth-tiers section is aspirational.

**Disposition:** No action. Reading both sections together, the
Sequencing section is operative; the Depth-tiers section is
introductory framing. Mild text-vs-text inconsistency, not a design
drift. Flagged here so future-Claude reading just the Depth-tiers line
doesn't misread "near-zero marginal cost" for fitness/narration tier 1
at this sub-milestone.

### Findings explicitly NOT raised (drift-scan scope discipline)

Walked the following BACKLOG sections; no drift:

- Watch-for-pattern entries (UTC-coercion RESOLVED; bridge→tools at 2;
  token_store=None at 2; production-code-surface-for-testability at
  2). Verified counts via the closeout's outcomes table.
- Codex Findings 2-5 status. Verified routing.
- workflow_id α/β/γ framing (line 27-31). Accurately frames
  candidates; my Section 1 recommendation is a stance on the existing
  options, not a drift.
- task_type α framing (line 33). The forced-fit risk is correctly
  flagged.
- Cohort breakdowns deferral (line 37). by_model + by_agent shipped;
  remaining three deferred. Verified in `GetTokenUsageOutput`.
- `MODEL_PRICING` UNVERIFIED status. Verified the inline source
  comment at `tools/budget.py:106` still reads "UNVERIFIED."
- api_key_overflow window-cycle deferred placeholder. Verified
  `_build_unknown_output` is invoked for the api_key_overflow branch.

---

## Section 4 — cohort-query threshold prediction

BACKLOG line 37: cohort breakdowns on `get_token_usage` defer until
"real-session investigation shows operators querying one cohort grouping
repeatedly via direct SQL or via the future `get_audit_log` ad-hoc query
path."

### What the continuous loop will actually call per iteration

Per addendum + pm.yaml § Continuous mode + Subscription-first
utilization expectations:

- `get_window_state(bucket="subscription_5h")` — every planning
  iteration. Primary pacing signal.
- `get_window_state(bucket="subscription_7d_opus")` — likely every
  planning iteration (Opus-downgrade routing context).
- `get_burn_rate(window="current")` — every planning iteration.
- `check_budget_for_task(complexity, priority)` — once per dispatch
  decision (not per iteration; per task being routed).
- `get_token_usage(window="current")` — at most once per planning
  iteration. PM compares observed-so-far to the 70-90% target and to
  by_model split (Opus vs Sonnet).
- `get_utilization_report` — at session boundaries (not per iteration).
  Lands at M6, not now.
- `record_api_call` — every Claude API call PM makes.

**No iteration-frequency call needs a cohort filter.** PM uses the
default subtree rollup (`agent_id=None`), `by_model` to gauge
model-mix, `by_agent` to identify which subordinate is consuming
tokens. The cohort dimensions land on `api_usage` rows for later
querying; they're not in the hot-path read.

### When does the threshold trip

| Threshold | Cohort | Prediction |
|---|---|---|
| First load-bearing | by_agent_role | M5-flag-flips. When Engineer-supervisor + Implementer/Tester dispatches go live, operator (Mike) wants role-aggregated burn ("PM-direct vs Engineer-subtree split"). by_agent is too granular (per-agent_id; PM has one, Engineer has one); by_agent_role is the right lens. Already auto-populated, complete-from-day-1. Likely first cohort grouping promoted to typed output field. |
| Second load-bearing | by_workflow_id | M5-flag-flips or shortly after. Once a real chain runs end-to-end, "what did this M5-flag-flips chain cost" becomes the natural diagnostic question. Requires Section 1 α-denormalized population. |
| Third load-bearing | by_task_type | After task_type protocol resolution actually populates the column with operator-meaningful values (Section 1 γ ships at this sub-milestone or later; β-with-PM-classification ships only PM-direct rows tagged). Likely M5-acceptance or M6 telemetry. |

**Will the threshold trip during M5-continuous-pm-driver itself?** No
— the loop's own logic doesn't have a cohort use case. The data
capture lands now; the surface promotion lands later, triggered by
M5-flag-flips operational data.

**One caveat that could escalate by_agent_role earlier:** if the
continuous loop's spin-rationale logic uses "Engineer-subtree burn
rate" as one of its keep-alive signals (e.g., "PM stays alive when
Engineer is producing, shuts down when Engineer is starved"), the
loop itself becomes a by_agent_role consumer. The current closeout
doesn't lock that decision; surface as a Section-6 question.

---

## Section 5 — addendum constraints on the continuous loop

Three items mentioned in the kickoff handoff message: compaction-beta-at-80%,
cross-session memory at `/product/substrate/pm-handoffs/`,
adaptive-thinking + xhigh effort for PM. Status of each below.

### Compaction at ~80% subscription_5h utilization — REAL constraint

Addendum 2026-05-01 (canonical) line 28:

> Compaction triggers on `subscription_5h >= 80%`, not premium. The
> 5-hour cycle drives compaction; per-model 7-day counters drive
> routing-tier downgrades only.

`02-architecture.md` and `06-mcp-tools-phase-one.md` both reference
"PM compaction pre-staged" at conserve threshold and Engineer
compacting at 80% per `11-engineer-bridge.md` § Engineer compaction.

**Constraint on the continuous loop:**

- At the moment `subscription_5h.pace_recommendation` flips to
  `conserve` (utilization >= 80%; remaining < 20%), the loop must
  trigger PM-side compaction prep.
- PM-side compaction prep produces a handoff doc and signals
  compaction (analogous to Engineer's `signal_compaction` MCP tool).
- The current `signal_compaction` tool exists for Engineer; PM
  needs an analogous trigger or shares the same tool. The
  compaction-trigger surface for PM is M5-continuous-pm-driver scope.

**Specific accommodation:** the loop reads
`get_window_state(bucket="subscription_5h").pace_recommendation` on
every iteration; transition to `conserve` is the trigger edge.
Self-test: confirm the loop doesn't oscillate (conserve → compact →
respawn → conserve → compact → ...). PMSupervisor's three-respawns-
per-hour cap (`bridge.supervisor_base.LongRunningSupervisor`)
provides the safety, but the design should not lean on the safety
net — the loop should compact at most once per 5h cycle.

### Cross-session memory at /product/substrate/pm-handoffs/ — REAL constraint

Confirmed in `11-engineer-bridge.md:746` and
`bridge/supervisor_base.py:264-265` and
`bridge/engineer_supervisor.py:147-148`:

> [PMSupervisor's `_reconstruct_state`] reads the most recent
> `/product/substrate/pm-handoffs/` doc per the M5 PM-compaction
> strategy.

**Constraint on the continuous loop:**

- On compaction trigger, PM writes a handoff doc to
  `<Settings.pm_handoff_dir>/<timestamp>.md` (default
  `<data_dir>/pm-handoffs/`; env-tunable to substrate-relative path)
  summarizing current_focus + planned-next + open-questions.
- On respawn (post-compaction or post-crash), the new PM subprocess
  reads the most recent handoff doc via the `get_most_recent_handoff`
  MCP tool at session-start (per pm.yaml § Compaction lifecycle), NOT
  via daemon-side `PMSupervisor._reconstruct_state` override.
  [SUPERSEDED 2026-05-07 by F9.0 Reading Y disposition — original
  framing in this section described `PMSupervisor._reconstruct_state`
  reading the doc daemon-side and providing it via subprocess spec /
  env / stdin. Reading Y disposition (preserving M5-pm-supervisor-base
  P-2 Reading B) places handoff-read subprocess-side via
  `tools/pm_handoff.py` MCP tools per defense (4) substrate-evolution-
  decoupling. Five+ spec sources amended at commit 8 atomically per
  `feedback_spec_amendment_runtime_drift` convention.]
- The handoff doc shape is M5-continuous-pm-driver scope. Required
  sections per F2.3 Q1 Reading C (yaml-convention; not daemon-
  validated): current_focus state at compaction, planned-next task
  chain, open-questions to Mike, recent decisions log pointer, cohort
  summary (workflow_id of in-flight chain). Free-form markdown with
  required-section headers; PM authors at compaction; respawn-PM reads
  at start.

**Coupling to Section 2 narration tier 1:** the handoff doc is a
natural output of PM's `current_focus` state at compaction. If
current_focus is captured in a `pm_current_focus` table per Section 2,
the handoff doc generation reads that table + recent
audit_log + recent api_usage to compose the markdown. Persistence
choice (table vs column) impacts handoff-doc generation logic.

### Adaptive thinking + xhigh effort for PM — NOT in current spec

Searched: addendum 2026-05-01, pm.yaml, 02-architecture.md,
07-agent-configs-phase-one.md, 11-engineer-bridge.md. None mention
"adaptive thinking," "xhigh," extended-thinking budget, or reasoning
effort for PM.

`config/agents/pm.yaml` has `model_tier_default: opus` and the
`record_api_call` field set is `model / input_tokens / output_tokens
/ task_id / notes / workflow_id / task_type` — no thinking-budget or
effort fields.

**Two possibilities, Mike-decidable:**

1. Mike is paraphrasing an external/verbal commitment that hasn't
   landed in the spec yet. If so, the M5-continuous-pm-driver scope
   needs to extend pm.yaml with thinking-budget config + extend the
   subprocess invocation to pass `--max-thinking-budget` (or
   equivalent — Claude Code's CLI flag, TBD) at PMSupervisor spawn.
2. Mike is misremembering / conflating with a different design
   decision. If so, no scope change needed.

Surfaced in Section 6 as gating disposition. Don't act on it without
Mike's confirmation; it's a real scope expansion if it lands.

---

## Section 6 — open questions for Mike

Six items genuinely needing Mike's decision before the prep checkpoint
can open. Three are Section 1/5 cross-references.

1. **task_type protocol** (Section 1 gating disposition) — γ (operator
   threads at submit_task; propagates through dispatch chain) gives
   the better cohort key but expands scope to (a) submit_task schema
   extension + (b) dispatch-chain propagation. β-with-PM-classification
   (PM self-tags via record_api_call.task_type; subprocess calls stay
   NULL) is the lower-scope alternative with partial-but-coherent
   coverage. Pick γ or β. If γ: does the propagation mechanism (b)
   ride at this sub-milestone or split off behind submit_task /
   dispatch-chain extension as its own deliverable?

2. **PM handoff doc shape** (Section 5) — required-sections markdown
   schema for `/product/substrate/pm-handoffs/<timestamp>.md`.
   Suggested sections: current_focus state at compaction, planned-next
   task chain, open-questions to Mike, recent decisions log pointer,
   cohort summary (workflow_id of in-flight chain). Mike-decidable:
   accept this list, edit, or delegate the format to PM yaml prompt
   discretion.

3. **Adaptive thinking + xhigh effort for PM** (Section 5) — not in
   current addendum or pm.yaml. Either (a) a real scope expansion at
   this sub-milestone (extend pm.yaml + PMSupervisor spawn args), or
   (b) misremembered/conflated framing. Surface for Mike to confirm.

4. **`current_focus` state persistence shape** (Section 2) — dedicated
   `pm_current_focus` table vs column extension on `pm_mode_log`. The
   table shape gives clean per-focus history + supports completion-
   validation cleanly; the column shape conflates two semantically
   distinct event types. Recommend table; flag for Mike's preference
   in case there's a reason I'm missing to keep persistence
   consolidated.

5. **Spin-rationale taxonomy closed enum vs free-text** (Section 2) —
   the `pm_spin_rationale_log` rationale_tag column. BACKLOG observability
   § "spin-rationale annotation (why is this agent kept alive — queued
   work / Mike-presence / heartbeat-cheap / forgot-to-shutdown)" hints
   at a closed enum. Recommend closed enum (forces taxonomic
   discipline; cohort queries trivial); flag for Mike since BACKLOG
   doesn't lock the shape.

6. **Loop's by_agent_role consumption** (Section 4 caveat) — does the
   continuous loop's spin-rationale logic use Engineer-subtree burn
   rate as a keep-alive signal? If yes, by_agent_role becomes a
   load-bearing cohort breakdown DURING this sub-milestone (escalating
   the Section 4 prediction). If no, by_agent_role lands at M5-flag-flips
   per the prediction. Decision shapes whether `GetTokenUsageOutput`
   gains a `by_agent_role` field at this sub-milestone or later.

---

## Cross-cutting observations

**Sub-milestone size.** The intersection of Section 2 (4 net-new
persistence primitives), Section 5 (handoff doc shape + compaction-prep
trigger logic + possibly adaptive-thinking config), Section 1 (workflow_id
denormalization wiring; possibly task_type γ scope), and the
continuous-loop driver itself is large. The closeout's "What's next"
language ("largest remaining sub-milestone... likely 1-2 sessions on its
own") is plausibly under-estimated. Worth flagging at prep-checkpoint
that scope decomposition may justify splitting into multiple sub-milestones
(e.g., M5-pm-narration-fitness-tier1 + M5-continuous-pm-driver-loop)
if the prep checkpoint's commit decomposition exceeds reasonable bounds.

**Patterns to carry forward** (closeout discipline-pattern observations):

- UTC-coercion utility (`storage/datetime_utils._coerce_naive_to_utc`)
  ready for any new pm_mode_log-shaped writer that parses datetime
  strings.
- Drift-scan discipline at sub-milestone close (this doc demonstrates
  pre-prep variant; landed-state drift-scan stays at close per the
  established convention).
- Threshold-of-three extraction watch: token_store=None defensive
  surface at 2; production-code-surface-for-testability at 2.
  Section 2's net-new persistence work introduces several testability
  surfaces — watch for the third instance triggering extraction.
- Sentinel tests for closed enums (rationale_tag taxonomy if Q5
  disposition closes on enum) — mirrors `_PRIORITY_TIER_DELTA` /
  `_TIER_HIERARCHY` / `_AGENT_ROLE_SUBTREE` sentinel pattern.

**End of original grounding pass.**

---

# Followup-1 — addressing four pre-prep pushbacks

Mike pushed back on four points before dispositions land. This section
addresses each. Original Section 1 / 5 framings are updated below where
the pushback exposed an error; otherwise extended.

## F1.1 — task_type cohort-quality argument (revised)

**Pushback:** "Mike-decidable on scope grounds" punted a technical
question by reframing it as scope. Defend γ on cohort quality first.
Produce a concrete query β cannot answer or answers worse — or default
to β.

**Position update:** I CAN produce the query. Recommendation revised to
γ on cohort-quality grounds. The original "scope-Mike-decidable" framing
was wrong on two counts: (a) it punted technical analysis upward; (b)
it overestimated propagation cost, missing that γ admits a
**server-side denormalization** implementation that needs no subprocess
plumbing.

### Concrete query where γ outperforms β

**Setup:** PM submits two sibling tasks under workflow `wf_001`:
- `task_a`, task_type = `research`
- `task_b`, task_type = `implementation`

Both tasks have `parent_task_id` pointing at the workflow root. Engineer
dispatches Implementer for both tasks, Tester for `task_b`. PM continues
planning while subprocess work runs.

**Query:** "Across all workflows in the last 7 days, total cost broken
down by `(agent_role, task_type)`."

```sql
SELECT agent_role, task_type, SUM(cost_usd) AS total_cost
FROM api_usage
WHERE window_start >= datetime('now', '-7 days')
GROUP BY agent_role, task_type
ORDER BY agent_role, task_type;
```

**Under γ (server-side denormalization stamps task_type on every row):**

| agent_role  | task_type      | total_cost |
|-------------|----------------|-----------:|
| pm          | research       | $0.50 |
| pm          | implementation | $0.30 |
| engineer    | research       | $0.40 |
| engineer    | implementation | $0.80 |
| implementer | research       | $0.20 |
| implementer | implementation | $0.60 |
| tester      | implementation | $0.40 |

Index `idx_api_usage_role_type` (composite `(agent_role, task_type)`,
shipped at commit 1) covers the query. No JOIN.

**Under β-with-PM-self-tag (PM annotates own calls; subprocess rows have
`task_type = NULL`):**

| agent_role  | task_type      | total_cost |
|-------------|----------------|-----------:|
| pm          | planning       | $0.50 |
| pm          | implementation | $0.30 |
| engineer    | NULL           | $1.20 |
| implementer | NULL           | $0.80 |
| tester      | NULL           | $0.40 |

Subprocess rows are **unattributed**. The query produces incomplete
cohort coverage. This is "answers worse" per Mike's bar — the result
shape is correct SQL but the cohort dimension collapses for 73% of rows
(every row not from PM).

**Under β-with-task_id-propagation-and-tasks.task_type-column** (a hybrid
that recovers cohort coverage via JOIN):

```sql
SELECT u.agent_role, t.task_type, SUM(u.cost_usd) AS total_cost
FROM api_usage u
LEFT JOIN tasks t ON u.task_id = t.id
WHERE u.window_start >= datetime('now', '-7 days')
GROUP BY u.agent_role, t.task_type;
```

Same output shape as γ. Costs: a JOIN per query, and `idx_api_usage_role_type`
(shipped at commit 1) becomes vestigial — the index covers
`(agent_role, task_type)` ON api_usage, not via JOIN. The cohort-roadmap
"Cohort keys: workflow_id, agent_role, task_type **on api_usage rows**"
design intent (BACKLOG observability § Baseline scaffolding) is not
satisfied by the JOIN path.

### The installed-base argument

The shipped 0009 migration **already reified γ-shape** at commit 1:

```sql
ALTER TABLE api_usage ADD COLUMN task_type TEXT;
CREATE INDEX idx_api_usage_role_type ON api_usage(agent_role, task_type);
```

Choosing β at this point produces dead schema (column always NULL on
subprocess rows; index half-covered) OR requires migration 0010 to drop
the column + index. Either outcome is sunk-cost discipline waste — the
infrastructure decision was already made.

### γ via server-side denormalization (revised scope)

Original framing inflated γ's scope by assuming subprocess-side
plumbing (Engineer's dispatch chain propagating task_type to subprocess
env or spec). That's not necessary.

**Implementation shape:**

1. **Migration 0010**: add `task_type TEXT` column to `tasks` table.
2. **Schema extension**: `submit_task` accepts optional `task_type`.
3. **Wrapper enhancement** in `record_api_call`: when caller has a
   bound non-terminal dispatch, JOIN to the dispatch's `task_id` →
   `tasks.task_type` and stamp on `api_usage.task_type`. Mirror of the
   existing `agent_role` server-stamp pattern (commit 1).
4. **PM-direct calls** (no bound dispatch): `task_type` stays NULL by
   default; PM may pass explicitly via the existing optional
   `RecordApiCallInput.task_type` field if it has a self-classification
   for an unbound call.

Subprocess agents (Engineer / Implementer / Tester) require **zero**
new plumbing. The denormalization happens server-side at write time,
JOINing through the bound dispatch's task. Same atomicity envelope as
the existing `agent_role` server-stamp.

**Scope cost:** one migration, one schema field, one wrapper helper.
Bounded.

**Coverage characteristic:** complete from day 1 for dispatch-bound
calls. PM-direct calls (no bound dispatch) carry NULL task_type unless
PM explicitly tags. The NULL coverage on PM-direct is honest — those
calls aren't attributable to a submitted task — and is the same shape
as workflow_id NULL on PM-direct (Section 1).

### Position

Recommend γ via server-side denormalization. The cohort-coverage
delta on subprocess rows is the deciding query result; the installed-
base argument compounds it; the revised scope removes the propagation
concern that drove the original "Mike-decidable" punt.

The original Section 1 framing of "task_type contested between γ and β
on scope grounds" is **withdrawn**. γ wins on cohort quality at bounded
scope.

---

## F1.2 — split seam contract

**Pushback:** Open question whether to split. Don't pre-commit. Surface
the seam contract; disposition follows from shape.

### What the loop consumes from narration/fitness primitives

(at this sub-milestone — read-side surfaces ship post-M5-flag-flips per
the BACKLOG observability sequencing.)

**Nothing.** The loop's per-iteration logic does not read its own
narration or fitness state during operation. The loop reads:
- `get_window_state` (loop's own input, not a narration/fitness primitive)
- `get_burn_rate` (same)
- `pm_mode_log` (existing primitive shipped at M5-pm-mode-and-window-state)
- complete_dispatch outcomes (loop ↔ Engineer interface, not narration)

The loop does NOT read `pm_current_focus`, `pm_spin_rationale_log`, or
`audit_sample_log` during operation at this sub-milestone.

### What the loop produces that primitives consume (write-side)

| Loop emission | Primitive consuming |
|---|---|
| `pm_current_focus` row INSERT on each work-unit boundary | New `pm_current_focus` table |
| `pm_spin_rationale_log` row INSERT on each iteration tick | New `pm_spin_rationale_log` table |
| Sampling-trigger events at iteration boundaries | New `audit_sample_log` (sampler picks per its sampling-percentage config) |
| `audit_log` event INSERT (existing primitive): `pm.current_focus.declared`, `pm.current_focus.completed`, `pm.current_focus.contract_violation`, `pm.compaction_prep`, `pm.handoff_doc_written` | Existing `audit_log` table (no schema change beyond event-kind extension) |

### Boundary cleanliness — two decisions shape it

**Decision A — completion-validation logic placement:**

Validation = "actual output matches declared current_focus's
output_type / acceptance_criteria → match | mismatch finding."

- **A1, loop-side:** loop sees the actual outcome (Engineer's
  complete_dispatch report; PM's dispatch chain terminates) and
  computes the finding directly. Writes via existing `audit_log`
  primitive. Narration primitive is **pure storage** — write-only seam.
- **A2, primitive-side:** loop calls primitive with `(current_focus_id,
  actual_outcome_payload)`; primitive computes finding and writes. Loop
  sends payload across the boundary; primitive owns logic.

A1 keeps the seam write-only. A2 introduces a function-call boundary
through which actual_outcome flows; primitive needs to reason about
outcome shapes (which evolve as Engineer's complete_dispatch shapes
evolve) — entanglement with non-narration concerns.

**Decision B — audit-sampling trigger placement:**

Sample selection = "this event was sampleable; sampler picks a fraction
for re-evaluation per its config."

- **B1, loop-driven trigger:** loop fires "sampleable event N happened"
  hook at iteration boundary; sampler picks per sampling-percentage
  config. Write-only seam.
- **B2, primitive-driven scan:** sampler reads narration/fitness state
  on a periodic schedule, picks samples from already-stored rows.
  Primitive needs read-API for sampler; bidirectional seam.

B1 keeps the seam write-only. B2 introduces a primitive→sampler
read-side that competes with the "read-side ships post-M5-flag-flips"
sequencing.

### Seam contract shape

| Decision | Boundary shape if chosen |
|---|---|
| A1 + B1 | Pure write-only seam. Loop emits; primitives store. Reads land later. **Clean split possible.** |
| A1 + B2 | Mostly write-only with one read-side (sampler→primitive). Sampler logic can co-locate with primitive sub-milestone or with loop sub-milestone — pick a side. **Mostly clean.** |
| A2 + B1 | Mostly write-only with one logic-cross-seam (loop hands payload to primitive's validation logic). **Entanglement on validation surface.** |
| A2 + B2 | Bidirectional. Validation logic + sample read-API both span the seam. **Entangled.** |

### Schema co-design risk (orthogonal to A/B)

Whichever cell holds, the primitive schemas (pm_current_focus,
pm_spin_rationale_log, audit_sample_log) need fields matching what
the loop emits. Defining schemas in a primitives-first sub-milestone
without the loop's emission shape risks under/over-specifying.

**Mitigation independent of split decision:** the primitives sub-milestone
prep checkpoint drafts the loop's emission shape (alongside primitive
schema) even if the loop ships separately. Risk degrades to design-
process churn (re-discovering the right shape during the loop sub-
milestone), not architectural correctness. Mild cost.

### Disposition follows from contract — without lean

I do **not** pre-commit a split-vs-keep recommendation. The disposition
follows from A/B above:

- **(A1, B1) → split is feasible cleanly.** Pure write-only seam holds;
  primitives ship as state-storage; loop ships as emit-and-orchestrate.
- **(A1, B2) → split is feasible with a sampler-side seam decision.**
  Where the sampler's read-API logic ships (primitives sub-milestone or
  loop sub-milestone) needs prep-checkpoint disposition.
- **(A2, *) → keep whole.** Validation-logic-cross-seam means the
  primitive isn't pure storage; co-locating with loop reduces the
  cognitive load on the primitive sub-milestone reviewer.

Recommend disposition order: settle A and B first as their own
prep-checkpoint questions. Split-vs-keep falls out.

---

## F1.3 — adaptive thinking + effort spec amendment

**Pushback confirmed:** Real spec gap. Land the amendment in this
sub-milestone. Mike's substantive content is verified-with-corrections
in F1.4 below; this section addresses the **spec home** and the
plumbing.

### Substantive content (verified vs Mike's framing)

| Mike's claim | Verified status | Source |
|---|---|---|
| Adaptive thinking is the only supported thinking-on mode on Opus 4.7 | **TRUE** | platform.claude.com/docs/en/build-with-claude/adaptive-thinking |
| It is implicit; no config needed | **PARTIALLY TRUE** with correction below | (see F1.4) |
| `thinking.type: "enabled"` returns 400 on Opus 4.7 | **TRUE** — verbatim docs: "manual `thinking: {type: 'enabled'}` is rejected with a 400 error" | adaptive-thinking docs |
| Effort levels: PM/Engineer/Implementer xhigh; Tester high | **VALID configuration choice**; both levels supported | platform.claude.com/docs/en/build-with-claude/effort |
| Pair with task budgets (separate spec line) | **TRUE** — `task-budgets-2026-03-13` beta header exists; per-loop token budget with running countdown | (mentioned in /effort and /adaptive-thinking pages) |

**Correction to "implicit":**

At the raw API: adaptive thinking on Opus 4.7 is NOT implicit. The
default with no `thinking` parameter is **thinking off**. To engage
adaptive thinking, the request must explicitly set
`thinking: {type: "adaptive"}`. Quoted: "Thinking is off unless you
explicitly set `thinking: {type: 'adaptive'}` in your request"
(adaptive-thinking docs).

At the Claude Code subprocess (which is what Phase 1 actually launches):
Claude Code v2.1.111+ on Opus 4.7 sets adaptive thinking + xhigh effort
as defaults transparently. From the Orchestrator daemon's perspective,
calling `claude -p` with no thinking args produces a subprocess that
applies the right defaults. So "implicit" reads correctly **at the
subprocess boundary**, but inaccurately at the raw API boundary.

The distinction matters because: Phase 1 has no daemon-side raw API
client. The "no-op for legacy thinking-mode handling" framing is moot
for Phase 1 — there's nothing to make a no-op.

### Plumbing — what actually has to change

Phase 1's daemon launches Claude Code subprocesses via
`bridge/dispatcher.py:_default_launcher` and (M4c) `bridge/supervisor_base.py`.
The launcher invokes `claude -p <prompt>` with env + cwd. No model arg,
no effort arg, no thinking arg.

Effort plumbing path:

1. Add `effort` field to agent yaml (pm.yaml + engineer.yaml +
   implementer.yaml + tester.yaml). Field type: closed-set Literal
   (`low` | `medium` | `high` | `xhigh` | `max`).
2. Agent registry exposes `effort` per agent (via the existing
   `AgentConfig` shape).
3. PMSupervisor / BridgeDispatcher reads agent config's `effort` and
   passes `--effort <level>` to the `claude` subprocess invocation.
   Verified flag: Claude Code CLI accepts `--effort <level>` for
   single-session override.
4. **No thinking-mode plumbing.** Claude Code handles adaptive thinking
   transparently on Opus 4.7 (and falls back to manual on older models
   per its own logic). Daemon's subprocess args carry effort only.

Task-budget plumbing (paired item):

5. `task-budgets-2026-03-13` beta header. Need to verify Claude Code
   CLI surface — `--task-budget` flag, env var, or settings field.
   Flagged for further verification before plumbing lands; safe to
   ship effort-plumbing first and task-budget-plumbing as a follow-up
   commit within the same sub-milestone if surface needs more research.

### Where the spec lives — recommend hybrid (a) + brief amendment doc

Mike framed the choice binary: **(a) embed directly** vs **(b)
standalone amendment doc**. The kickoff convention from
`docs/kickoff/00-README.md` is actually neither — it's **both**:

> When a substantial design change happens after kickoff, the change is
> captured in a separate `*-addendum.md` file in this directory **and
> merged into the relevant numbered docs**. The addendum file persists
> as a record of *why* the change was made; the numbered docs reflect
> the current state.

The 2026-05-01 subscription-first revision sits in
`orchestrator-utilization-addendum.md` AND is merged into 02 / 06 / 07
/ 08 / 11.

**Surfaced trade-offs:**

- **Pure (a) embed-only:** future-Claude reading `pm.yaml`'s
  `effort: xhigh` lacks rationale audit trail. Why xhigh? Why not
  high? Adopting xhigh as a runtime artifact without a "why" record
  invites future drift (a future Mike or future-Claude could lower it
  to `high` for cost-saving without recognizing the cohort/agentic-loop
  argument that justified xhigh).
- **Pure (b) standalone-only:** future-Claude reading `pm.yaml`
  doesn't see the effort field at all (the doc-only path doesn't
  enforce anything). The runtime artifact is unchanged; configuration
  is documentation, not code. Wrong shape — runtime artifacts are the
  source of truth for runtime behavior.
- **(a) + brief amendment doc** (the kickoff convention): runtime
  artifacts get the field; amendment doc carries the rationale. Mirrors
  the proven pattern of `orchestrator-utilization-addendum.md`. Future-
  Claude reading `pm.yaml`'s `effort: xhigh` can chase to amendment
  for the why if needed; doesn't need to chase for the what.

**Recommendation:** hybrid (a) + brief amendment doc. New file:
`docs/kickoff/opus-4-7-effort-and-thinking-addendum.md` (~150-300 lines;
shorter than the utilization addendum since the architectural surface
is narrower). Embedded changes:

- pm.yaml: add `effort: xhigh` (and Engineer/Implementer/Tester yamls
  per Mike's spec).
- 07-agent-configs-phase-one.md: PM YAML section gains `effort` line +
  brief rationale + pointer to the addendum. Engineer/Implementer/Tester
  YAML sections same.
- 11-engineer-bridge.md: launcher contract gains "passes `--effort`
  from agent config to subprocess." Engineer-supervisor + PMSupervisor
  spawn args section.
- 02-architecture.md: brief reference to the effort-config layer (less
  load-bearing — the field is operational, not architectural;
  optional).

Mike's "mild preference for (a)" is honored — embed is primary. The
amendment doc captures rationale per established convention; if Mike
wants to skip it, that's a deliberate convention deviation worth
naming explicitly.

---

## F1.4 — verification (current behavior, not recall)

**Pushback:** "thinking.type: enabled returns 400 on Opus 4.7" was
two-layers-of-hearsay from a 6-day-old conversation. Verify directly.

### Verified (current Anthropic docs, fetched 2026-05-03)

**Source 1:** `platform.claude.com/docs/en/build-with-claude/adaptive-thinking`

> Claude Opus 4.7 (`claude-opus-4-7`), adaptive thinking is the only
> supported thinking mode. **Thinking is off unless you explicitly set
> `thinking: {type: "adaptive"}` in your request**; manual
> `thinking: {type: "enabled"}` is rejected with a 400 error.

So: **Mike's recall was correct on the 400 behavior.** Two-layers-of-
hearsay survived; the wrapper-handling guidance "no-op for legacy
thinking-mode handling" reads as "remove the field entirely from any
4.7-bound request" — silent strip is the right shape, not defensive
handling.

**Source 2:** `platform.claude.com/docs/en/build-with-claude/effort`

API parameter path: `output_config.effort`. Levels supported on Opus
4.7: `low`, `medium`, `high`, `xhigh`, `max`. The API default is
`high`; xhigh requires explicit set. Generally available, no beta
header.

**Source 3:** `code.claude.com/docs/en/model-config`

Claude Code v2.1.117+ defaults to `xhigh` on Opus 4.7. CLI flag
`--effort <level>` is single-session. Env var `CLAUDE_CODE_EFFORT_LEVEL`.
Settings field `effortLevel`. Subagent frontmatter field `effort`.

> Opus 4.7 always uses adaptive reasoning. The fixed thinking budget
> mode and `CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING` do not apply to it.

So from the Claude Code subprocess perspective, adaptive thinking IS
implicit — CC handles it. The Orchestrator daemon launching `claude
-p` doesn't need to plumb thinking-mode.

### Verified — Orchestrator API client wrapper status

Searched `src/` for any Anthropic API client, thinking handling, or
effort handling:

- `grep -i thinking` in `src/`: zero matches (one false positive in
  audit/logger.py docstring referring to "best-effort audit").
- `grep -i effort` in `src/`: same false positive only.
- `grep -i 'anthropic'` in `src/`: matches are about the OAuth poller
  hitting `/api/oauth/usage` (httpx, not the Anthropic SDK), the
  pricing-table source comment, and credential-stripping in
  `bridge/launch_env.py`. No Anthropic SDK import. No
  `client.messages.create` call.

**Conclusion:** Phase 1 Orchestrator has **no Anthropic API client**
and **no thinking-mode handling code**. The "no-op for legacy thinking"
rule applies to a layer that doesn't exist. The spec amendment in F1.3
includes no daemon-side thinking-mode handling — there's nothing to
make a no-op of.

### Implications for the spec amendment

- Mike's "API client wrapper should be a no-op" statement is **moot
  for Phase 1**. Restated for the amendment doc: "If/when an
  Orchestrator-side API client emerges (currently none in Phase 1),
  legacy `thinking: {type: 'enabled'}` handling on 4.7-bound requests
  must be silently stripped (verified: API rejects with 400). For
  Phase 1, Claude Code subprocesses handle adaptive thinking
  transparently; no daemon-side thinking-mode code is required."
- Mike's "implicit" framing reads correctly **at the Claude Code
  subprocess boundary** but not at the raw API boundary. The spec
  amendment should be precise about the boundary: "On Opus 4.7
  via Claude Code subprocess, adaptive thinking is the implicit
  default (CC v2.1.111+). On Opus 4.7 via raw API, adaptive thinking
  must be set explicitly via `thinking: {type: 'adaptive'}`. Phase 1
  uses only the subprocess path."

### Sources

- [Adaptive thinking - Claude API Docs](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking)
- [Effort - Claude API Docs](https://platform.claude.com/docs/en/build-with-claude/effort)
- [Model configuration - Claude Code Docs](https://code.claude.com/docs/en/model-config)

---

**End of Followup-1.**

---

# Followup-2 — prep checkpoint for M5-continuous-pm-driver

This is the prep checkpoint per the operational protocol convention.
Scope decomposition + seam contract decisions + numbered open questions
with recommendations. Dispositions from Mike + reviewing-Claude land
before the diff drafts.

---

## F2.0 — verification finding ahead of scope (genuine blocker, surface first)

**Task budgets are NOT supported on Claude Code or Cowork surfaces at
launch.** Verbatim from current docs at
`platform.claude.com/docs/en/build-with-claude/task-budgets`:

> "Task budgets are not supported on Claude Code or Cowork surfaces at
> launch. Use task budgets directly via the Messages API on Claude
> Opus 4.7."

The `task_budget` field lives on `output_config.task_budget` on the
Messages API, gated by the `task-budgets-2026-03-13` beta header. The
Orchestrator daemon launches Claude Code subprocesses (`claude -p`); it
has no Messages API client (verified in F1.4). Task budget plumbing
through the existing `_default_launcher` is **not implementable**.

**Three options for Mike's disposition** (Q5 below):

- **(α) Defer.** Drop task budgets from M5-continuous-pm-driver scope.
  Land effort plumbing only at this sub-milestone. Re-evaluate when
  Claude Code adds support OR when a Phase 2 daemon-side API client
  emerges. **Smallest scope; honest "not blocked, but not shipped."**
- **(β) Build a daemon-side Messages API client at this sub-milestone.**
  PM-continuous subprocess routes Messages API calls through the daemon
  rather than via Claude Code. Massive scope expansion: net-new
  Anthropic SDK dependency, OAuth credential handling at the daemon
  layer (subscription auth currently lives in the subprocess), thinking
  + display + caching + tool-use lifecycle all reimplemented. Phase 2
  shape, not Phase 1.
- **(γ) Daemon-side soft equivalent.** Ship a daemon-tracked per-loop
  token countdown using existing `api_usage` aggregation; surface to
  PM via a new MCP tool (e.g., `get_task_budget_remaining(workflow_id)`)
  and PM yaml prompt guidance ("when remaining < 20%, finish the
  current work-unit and emit a handoff"). Approximates the
  Anthropic-side feature without the server-side context-injected
  countdown — PM reads the value via a tool call rather than seeing it
  inline. Loses the "Claude self-paces against an injected marker"
  property; preserves the operator-side budget guardrail. Modest scope:
  one new MCP tool + PM prompt amendment + sampling-percentage tweaks.

**My recommendation: (α) defer.** Reasoning:

- The post-Phase-1 framing Mike referenced for task budgets pairs with
  effort because both are "Opus 4.7 specifics that become load-bearing
  when PM runs a sustained agentic loop." But the load-bearing case
  for effort xhigh is **per-step depth** (each iteration tick of PM's
  loop reasons more thoroughly); the load-bearing case for task budgets
  is **per-loop breadth** (PM doesn't run away on a multi-hour task).
  Phase 1 has subscription-window-based pacing primitives already
  (`subscription_5h` `conserve` triggers compaction-prep at 80%). The
  subscription-window pacing IS the Phase 1 budget guardrail. Task
  budgets would be a finer-grained refinement; valuable but not
  load-bearing absent the subscription pacing primitives being
  insufficient.
- (β) is genuinely Phase 2 shape. Building a Messages API client at
  this sub-milestone roughly doubles the M5-continuous-pm-driver
  surface and would extend the chain to ~15+ commits.
- (γ) is implementable but the value is incremental over (α). PM's
  prompt already directs aggressive utilization; the missing piece is
  per-loop boundary, which the existing compaction-trigger handles
  reasonably.

If (α), update the F1.3 amendment doc to scope task budgets as
explicit-out-of-Phase-1 with the rationale; pair-shipping was the
post-M1 framing's intent but the Claude Code surface unsupported-at-
launch makes pair-shipping impossible. Honest deferral is the right
call.

If Mike disagrees, (γ) is the smallest implementable path. (β) is
out-of-scope for this sub-milestone.

---

## F2.1 — scope decomposition (9-commit sketch under keep-whole; revised 2026-05-03)

**Revision note (2026-05-03):** Original 10-commit chain dropped commit 1
("0010 migration: `tasks.task_type` column + `submit_task` schema
field") per F2.1 commit-1 contradiction surface — `tools/tasks.py` and
`submit_task` are unimplemented. Per Mike's disposition, F1.1 task_type γ
rolled back; the migration + schema field move to M4d-task-management-surface
(remediation sub-milestone scheduled after this one). Original commit 2
(workflow_id α-denormalization) becomes new commit 1 with task_type
denormalization removed (workflow_id α only). Test delta drops from +12
to ~+8 for the renumbered commit 1.

Section 1 workflow_id α + F1.2 keep-whole + F1.3 effort plumbing + F2.0
task-budget defer compose to a 9-commit chain.

| # | Commit | Touchpoint | Tests delta (rough) |
|---|---|---|---|
| 1 | workflow_id α-denormalization in `record_api_call` wrapper (chain-root walk via `tasks.parent_task_id`; PM-direct calls stay NULL) | `tools/budget.py:record_api_call`, `server.py` wrapper | +8 |
| 2 | `pm_current_focus` table + write/read API + completion-validation `audit_log` event-kind extension. Includes nullable `bound_workflow_id` per Q8 disposition | `storage/sql/0010_*.sql`, `tools/pm_focus.py` (new), `audit/logger.py` | +14 |
| 3 | `pm_spin_rationale_log` table + writer + closed-enum rationale_tag sentinel | `storage/sql/0011_*.sql`, `tools/pm_spin.py` (new) | +8 |
| 4 | `audit_sample_log` table + sampling-percentage config + async sampler scaffold | `storage/sql/0012_*.sql`, `audit/sampler.py` (new), `config.py` | +10 |
| 5 | Effort plumbing: agent yaml field, PMSupervisor + BridgeDispatcher pass `--effort` to claude subprocess | `config/agents/*.yaml`, `bridge/supervisor_base.py`, `bridge/dispatcher.py:_default_launcher`, `agents/config.py` | +9 |
| 6 | Hybrid spec amendment: `docs/kickoff/opus-4-7-effort-and-thinking-addendum.md` + merges into 02 / 07 / 11 | `docs/kickoff/*` | +0 (docs) |
| 7 | Continuous loop driver core: `get_window_state` polling + `check_budget_for_task` routing + iteration loop | `bridge/pm_supervisor.py` (new continuous-mode logic), `tools/pm_loop.py` (new) | +18 |
| 8 | Compaction-trigger + handoff-doc generation + handoff-read via tools/pm_handoff.py MCP tools + signal_compaction permission widening to PM | `tools/pm_handoff.py` (new), `tools/supervisor.py` (permission widening), `bridge/supervisor_base.py` (role-derived audit action) | +14 |
| 9 | e2e integration tests + drift-scan + sub-milestone closeout doc | `tests/integration/`, `docs/closeouts/` | +20 |

**Total: ~101 net new tests** (revised from ~111 after commit 1 drop).
Final test count post-chain: ~534.

**Test counts are targets, not budgets.** Per-commit estimates are
derived from analogous test surface in M5-subscription-routing's commit
chain. Actual deltas land per-commit and may shift based on what real
test surface emerges (defensive cases discovered during drafting,
fixture-vs-unit splits, integration coverage that overlaps unit
coverage, etc.). The ~101 estimate is the chain-wide order-of-magnitude
target; future prep checkpoints should not treat per-commit numbers as
budgets the chain has to hit.

**Surface-as-finding rule:** if actual test deltas materially diverge
from the per-commit estimates by more than ~20% on any single commit,
surface as a prep-checkpoint finding for that commit, not silent
absorption. The divergence indicates either (a) actual test surface
differs from analogous-precedent extrapolation (acceptable if
documented), or (b) the commit's scope shifted during drafting (real
finding requiring disposition).

**Migration count revised:** three new migrations at this sub-milestone
(0010 / 0011 / 0012), one less than the original four. The 0010 slot
is reclaimed by `pm_current_focus`; original 0010 (`tasks.task_type`)
moves to M4d's migration namespace (TBD when M4d preps).

**Rationale notes per commit (revised for 9-commit chain):**

- **Commit 1** is workflow_id α-denormalization in the `record_api_call`
  wrapper. Lands BEFORE narration/fitness primitives so the cohort
  dimension is populated when the new primitives start writing rows.
  task_type γ portion was rolled back per F2.1 commit-1 contradiction
  (2026-05-03); moves to M4d-task-management-surface.
- **Commits 2-4** are the narration/fitness tier-1 data-capture
  primitives. State storage only — no read-side surfaces (those ship
  post-M5-flag-flips per BACKLOG observability sequencing).
- **Commit 5** is the effort plumbing. Decoupled from narration/fitness
  primitives; could swap with commit 6 ordering.
- **Commit 6** is the spec amendment. Pure docs; verifies via `mypy`
  on yamls (closed-enum Literal type) but has no test surface itself.
- **Commits 7-8** are the loop. Consumer of all primitives shipped in
  commits 1-5.
- **Commit 8 row amended in-place at commit 8 prep (F9.0 Reading Y
  disposition, 2026-05-07).** Original framing described
  "`PMSupervisor._reconstruct_state` reading most-recent handoff" with
  files `bridge/pm_supervisor.py` (extension) + `tools/pm_handoff.py`
  (new); per F9.0 Reading Y the handoff-read mechanism lives
  subprocess-side via `tools/pm_handoff.py` MCP tools, NOT daemon-side
  `_reconstruct_state` override. Files updated to reflect actual
  amendment surface. PMSupervisor unchanged at commit 8 (invariant
  test from M5-pm-supervisor-base stays green). Per
  `feedback_spec_amendment_runtime_drift` convention: 5+ spec sources
  (supervisor_base.py + engineer_supervisor.py docstrings; 11-engineer-
  bridge.md:753; 02-architecture.md; 06-mcp-tools-phase-one.md;
  this Section 5; this F2.1 row) amended atomically with the runtime
  change.
- **Commit 9** closes the chain.

**Open question on decomposition (Q6 below):** is this the right
shape, or should it be restructured?

---

## F2.2 — seam contract recommendations (F1.2 follow-through)

Per D1.2: "Produce a recommendation for (A1 vs A2) and (B1 vs B2) with
explicit reasoning grounded in the loop's actual data flow."

### Decision A — validation logic placement

**Recommend A1 (loop-side validation).** Not contested.

Reasoning grounded in actual data flow:

- The validation logic compares `actual_output` against declared
  `output_type` / `acceptance_criteria`. The `actual_output` arrives at
  the loop as the terminal state of a dispatch chain rooted at PM's
  current_focus — Engineer's `complete_dispatch` outcomes
  (status, commit_hash, test_results, queue_items_raised, artifacts,
  notes, merge_conflict_files). These are dispatch-shape concepts
  living in `bridge/dispatcher.py:DispatchOutcome`.
- The narration primitive's purpose is **store narration state**.
  Moving validation logic to the primitive forces the primitive to
  take a domain dependency on `DispatchOutcome`. That's coupling
  unrelated to its purpose.
- A1 keeps the primitive narrow (state storage; insert/select on
  `pm_current_focus`). The loop owns the comparison logic; writes
  findings via the existing `audit_log` primitive. **Pure write-only
  seam preserved.**
- A2's argument would be "centralize validation logic for tier-2
  evolution." But tier-2 surfaces ship post-M5-flag-flips; tier-2
  evolution can refactor at that point. Centralizing now is premature.

### Decision B — audit-sampling trigger placement

**Recommend B1 (loop-driven trigger).** Not contested.

Reasoning grounded in actual data flow:

- Sampleable events are work-unit completions and current_focus
  contract-validation outcomes. These are emitted by the loop at
  iteration boundaries. The loop has the natural "I just finished a
  work unit" signal.
- B1: loop fires `record_sampleable_event(event_kind, source_id)` at
  iteration boundary; sampler picks per `sampling_percentage` config
  asynchronously. The loop emits; sampler subscribes. No drift state.
- B2: sampler reads `pm_current_focus` / `audit_log` on a schedule,
  selects samples from already-stored rows. Requires bookmark state
  (`last_sampled_id` per source kind), idempotency reasoning around
  partial-batch failures, separate timer infrastructure. Materially
  more state to manage for the same outcome.
- The loop's natural iteration cadence makes B1 cheap. B2 introduces
  separate scheduler concerns.

### Disposition that follows from (A1, B1)

The seam is **clean write-only.** Per F1.2's contract analysis, this
maps to "split is feasible cleanly."

But: **I recommend keep-whole** despite the clean seam. The deciding
factor is **verification surface**, not seam shape:

- The narration/fitness primitives (commits 3-5) are useful only to
  the loop. Shipping them in a primitives-first sub-milestone with no
  consumer means no end-to-end test surface for their write/read
  contracts. Same shape as the kickoff doc's PMSupervisor-at-M4
  reasoning ("at M4 there is no verification surface — shipping
  PMSupervisor without an integration test would be unverified
  surface").
- Mitigation under split would be: ship primitives + a fake-loop test
  fixture that exercises the write paths. That fake-loop scope
  approximates the real loop's emission shape, which means designing
  the loop's emission shape twice (once as fake, once as real) — or
  pre-locking the real shape in the primitives sub-milestone, which
  collapses the split's distinctness.
- Sub-milestone overhead (prep checkpoint × 2, dispositions × 2,
  closeouts × 2) compounds the cost.

The seam being clean enough to split is the F1.2 "shape" disposition;
the verification-surface argument is a follow-on architectural concern
that votes against split despite clean shape.

**Recommend: keep whole.** Sub-milestone is large but coherent.

---

## F2.3 — open questions (Q1-Q8)

Numbered with multiple readings + recommendation per the operational
protocol convention. Reviewing-Claude reviews; Mike dispositions.

### Q1 — PM handoff doc shape (required sections)

What does PM write to `/product/substrate/pm-handoffs/<timestamp>.md`
on compaction trigger?

- **Reading A:** PM yaml prompt directs PM to author free-form
  markdown; doc shape is "whatever PM produces." Lowest schema cost;
  highest variance across handoffs.
- **Reading B:** Required-section markdown schema enforced by the
  daemon at write time. Sections: `current_focus`, `planned_next`,
  `open_questions`, `recent_decisions_pointer`, `cohort_summary`
  (workflow_id of in-flight chain). Daemon validates section
  presence; missing sections → write rejected with audit-log finding.
  Highest discipline; tightest contract.
- **Reading C (recommended):** Required-section markdown schema as
  a PM yaml convention (PM's prompt names the required sections); no
  daemon-side validation. Honor system + audit-log scan at compaction
  surfaces if a handoff is missing required sections. Middle path:
  schema discipline without write-rejection rigidity.

**Recommendation: Reading C.** Daemon-side validation (B) is
heavyweight for a markdown doc that the daemon doesn't otherwise
parse semantically. The handoff is read by the new PM subprocess
via the `get_most_recent_handoff` MCP tool (per F9.0 Reading Y
disposition, 2026-05-07; supersedes the original framing of
`PMSupervisor._reconstruct_state` daemon-side override). The
read-side handler returns content + written_at; PM-prompt consumes
the markdown without structural section parsing. Reading-C achieves
contract discipline via prompt + audit-scan without parser surface.

[SUPERSEDED 2026-05-07: original Reading C prose described the
handoff being read by `PMSupervisor._reconstruct_state` on respawn
per `bridge/supervisor_base.py:264-272`. F9.0 Reading Y disposition
moved handoff-read subprocess-side via tools/pm_handoff.py MCP
tools; the F2.3 Q1 Reading C disposition (yaml-convention
required-section markdown; no daemon-side validation; audit-emit on
write) still holds — only the consumer location changes.]

### Q2 — `pm_current_focus` persistence shape

Where does `current_focus` state live?

- **Reading A:** Dedicated `pm_current_focus` table. Schema:
  `id / pm_mode_log_id / objective / output_type / acceptance_criteria
  / declared_at / completed_at / contract_validated_bool /
  contract_finding_text`. Clean per-focus history.
- **Reading B:** New columns on `pm_mode_log`:
  `current_focus_objective`, `current_focus_output_type`, etc.
  Conflates pm-mode events (interactive↔continuous transitions) with
  per-focus-declarations (work-unit declarations) — different event
  rates and semantics.

**Recommendation: Reading A.** Per F1's Section 2 framing; per
column-conflation cost. Not contested.

### Q3 — `pm_spin_rationale_log.rationale_tag` taxonomy

Closed enum or free text?

- **Reading A:** Closed enum: `queued_work` | `mike_presence` |
  `heartbeat_cheap` | `forgot_to_shutdown`. Closed-set Literal +
  CHECK constraint. Sentinel test locks the enum. Mirrors
  `_PRIORITY_TIER_DELTA` / `_TIER_HIERARCHY` / `_AGENT_ROLE_SUBTREE`
  sentinel patterns from M5-subscription-routing.
- **Reading B:** Free-text rationale_tag + free-text rationale_detail.
  Lower discipline; higher noise; cohort queries collapse.

**Recommendation: Reading A.** Forces taxonomic discipline. The
fitness anomaly causal taxonomy in BACKLOG observability roadmap is
explicitly "expected to evolve," but this taxonomy is for keep-alive
rationale (different axis) and BACKLOG observability roadmap names the
four tags directly: "(why is this agent kept alive — queued work /
Mike-presence / heartbeat-cheap / forgot-to-shutdown)." Land the four
as the closed enum; future-evolve with a new migration if needed.

### Q4 — Loop's `by_agent_role` consumption (Section 4 caveat)

Does the continuous loop's spin-rationale logic use Engineer-subtree
burn rate as a keep-alive signal?

- **Reading A:** Yes. PM stays alive when Engineer-subtree is
  producing tokens (active dispatching); shuts down (or shifts to
  spin-rationale=`heartbeat_cheap`) when Engineer-subtree is starved.
  This makes `by_agent_role` load-bearing DURING this sub-milestone:
  needs `GetTokenUsageOutput.by_agent_role` field promoted from
  BACKLOG-deferred to shipped at this sub-milestone.
- **Reading B:** No. PM's keep-alive logic uses simpler signals:
  queue depth (count of unblocked dispatchable tasks), recent dispatch
  outcomes (last N completed dispatches), `subscription_5h` pace
  recommendation. Engineer-subtree-specific burn isn't load-bearing.
  `by_agent_role` deferral per BACKLOG line 37 holds; promotes at
  M5-flag-flips per the original Section 4 prediction.

**Recommendation: Reading B.** The simpler signals (queue depth + last
N dispatch outcomes + pace recommendation) cover the keep-alive
decision adequately; Engineer-subtree-burn is a finer-grained signal
that's diagnostic-worthwhile but not decision-critical. Adding
`by_agent_role` to `GetTokenUsageOutput` at this sub-milestone expands
scope marginally without clear value. Defer per BACKLOG line 37.

If Mike wants Reading A: it adds ~30-50 lines to commit 8 (loop
driver) + ~10 lines to `GetTokenUsageOutput`; small additional scope
but a real promotion of a deferred BACKLOG item.

### Q5 — Task-budget plumbing (F2.0 finding)

Per F2.0 above. Three options: (α) defer, (β) build daemon-side API
client, (γ) soft equivalent.

**Recommendation: (α) defer.** Reasoning in F2.0.

### Q6 — Commit decomposition (revised for 9-commit chain)

Is the 9-commit shape from F2.1 (revised 2026-05-03) right, or
restructure?

- **Reading A (as proposed):** 9 commits as listed in revised F2.1.
  pm_current_focus completion-validation already integrated into commit
  2 since the table commit and event-kind extension are inseparable
  after the F2.1 commit-1 drop.
- **Reading B (split commits 7-8):** Split commit 7 further —
  `get_window_state` polling (one commit) and `check_budget_for_task`
  routing (next commit). The loop iteration scaffold is a separate
  concern from the routing decision.
- **Reading C (collapse 3+4 into one):** The two log tables share
  shape; combining tightens but at the cost of a less-targeted prep
  checkpoint per primitive.

**Recommendation: Reading A.** The revised 9-commit decomposition has
per-commit tractability with reasonable test deltas (~101 net new
tests). Reading B is defensible (loop scaffold + routing are
conceptually distinct) but the loop's first commit will need both
get_window_state polling AND check_budget_for_task wiring to demonstrate
end-to-end iteration cadence; splitting them produces a commit 7 that
runs but doesn't make routing decisions, which has no integration test
surface.

### Q7 — Effort level for interactive Mike PM (OAuth-PM session)

Mike's interactive PM session (Claude Code subprocess on his desktop,
not daemon-launched) is OAuth-authenticated as PM. Does Mike's session
inherit pm.yaml's `effort: xhigh`, or use Mike's own session settings?

- **Reading A:** Mike's session inherits pm.yaml's effort. The daemon
  doesn't launch Mike's session, so the inheritance mechanism would be
  Mike running `claude --effort xhigh` himself. Yaml is informational;
  Mike's tooling is separate.
- **Reading B:** Mike's session uses Mike's own Claude Code defaults
  (Claude Code v2.1.117+ defaults to xhigh on Opus 4.7 anyway —
  effectively same outcome). Yaml `effort: xhigh` reads as
  documentation of the daemon-launched PM-continuous's effort level
  only; Mike's interactive PM is governed by Mike's tooling.

**Recommendation: Reading B.** Mike's interactive PM is not daemon-
launched; the daemon has no plumbing path into Mike's Claude Code
session. The yaml's `effort` field documents the daemon-launched
PM-continuous subprocess. Mike's tooling defaults to xhigh on Opus 4.7
anyway (per Claude Code v2.1.117+ defaults). Document this in the F1.3
amendment doc to prevent future-Claude assuming the yaml controls
Mike's session.

### Q8 — Workflow_id population on PM-direct calls

Section 1 α-denormalized populates `workflow_id` on `api_usage` rows
via the caller's bound dispatch's task chain root. Mike's interactive
PM (OAuth-PM, no bound dispatch) → workflow_id NULL.

Continuous PM (static-token PM-continuous) — does the continuous
subprocess have a "current task" concept that could populate
workflow_id even without a bound dispatch?

- **Reading A:** Continuous PM has a `current_focus` (per Section 2 /
  Q2). If `current_focus` is bound to a top-level submitted task,
  workflow_id is the chain root of that task. Stamp via
  `current_focus.bound_task_id` lookup.
- **Reading B:** Continuous PM's `current_focus` is NOT necessarily
  bound to a submitted task — it's a declared work-unit objective that
  may span multiple submissions or be unrelated to dispatched work
  (e.g., "review last hour of audit log"). Stamping workflow_id from
  current_focus would conflate work-unit-objective with
  task-chain-root. Leave NULL.
- **Reading C:** Continuous PM's `current_focus` declares a
  `bound_workflow_id` field at declaration time IF the focus is bound
  to a workflow; this is the population source for own-call
  workflow_id. Honest about cases where focus isn't workflow-bound.

**Recommendation: Reading C.** Most honest. Adds one optional column
(`pm_current_focus.bound_workflow_id`) with semantics "this focus is
the operator's intent for workflow X." When set, PM-continuous's calls
during this focus stamp workflow_id from the focus. When NULL (focus
isn't workflow-bound — e.g., review work), api_usage rows have NULL
workflow_id. Schema cost is one nullable column; semantic clarity
benefit is worth it.

---

## F2.4 — watch-for-pattern checks

New patterns this sub-milestone may surface or compound:

- **Server-side denormalization at write time for cohort keys.**
  Commit 1 introduces this pattern (workflow_id α via
  JOIN-at-record_api_call-write; task_type γ deferred to M4d). First
  instance lands at commit 1 of M5-continuous-pm-driver
  (workflow_id chain-root resolution in record_api_call). If a third
  cohort key emerges later (per BACKLOG observability roadmap's
  evolving tier-2 work), watch for extraction of a shared
  "denormalize-from-bound-dispatch" helper. Threshold: 3.
- **Recursive CTE for tree/chain traversal in storage queries.**
  First instance lands at commit 1 of M5-continuous-pm-driver
  (`_resolve_workflow_id_from_dispatch` walks `tasks.parent_task_id`
  chain via `WITH RECURSIVE`). Pattern is durable across the codebase:
  workflow chains (M5), agent management hierarchies (M6 dashboard
  subtree), knowledge subtree walks (M6), `get_subtasks` chain reads
  (M4d). Threshold: 3 — at the third instance, surface for same-commit
  extraction of a shared helper (the `WITH RECURSIVE` pattern is
  small but error-prone — depth guards, cycle protection, base-case
  semantics — so a shared utility pays compound interest). Same-commit
  extraction discipline per M5-subscription-routing commit 2's
  UTC-coercion precedent.
- **Closed enum + sentinel test for narration/fitness fields.**
  rationale_tag (Q3, recommend closed enum), `effort` Literal in agent
  yamls. Two new closed-enum sentinels at this sub-milestone.
  Mirrors the `_PRIORITY_TIER_DELTA` / `_TIER_HIERARCHY` /
  `_AGENT_ROLE_SUBTREE` sentinel pattern from M5-subscription-routing.
  Pattern continues at 5+ instances now (counting forward).
- **Production-code-surface-for-testability** at 2 pre-chain
  (token_store=None defensive surface; `_run_lifespan_body`
  refactor). Commit 8's loop driver may surface a third instance —
  the loop's iteration mechanism likely needs a test seam (e.g.,
  injectable clock for testing iteration cadence; injectable
  snapshot-getter for testing fallback paths). Watch the threshold
  at commit 8 prep; if a third instance lands, prep-checkpoint
  contradiction surfaces per the original BACKLOG entry.
- **`bridge/` → `tools/` cross-layer imports** at 2. Commit 8's loop
  driver in `bridge/pm_supervisor.py` consumes
  `tools/budget.py:check_budget_for_task` and possibly
  `tools/pm_focus.py:write_current_focus`. If both land, that's
  instances 3 and 4 — well past threshold. Layer-split reconsideration
  triggers per the BACKLOG entry.
- **Migration count.** Three new migrations (0010, 0011, 0012) at
  this sub-milestone (revised 2026-05-04 from "four (0010-0013)" after
  F2.1 chain revision dropped the `tasks.task_type` migration to M4d).
  Migration boilerplate is small but not zero; if the rate continues,
  watch for migration-template extraction.

---

## F2.5 — discipline notes for the closeout

Per D1.1, capture in the closeout doc:

- **Scope-punt pattern observation.** Original Section 1 framing
  punted task_type as "Mike-decidable on scope grounds." Mike's
  pushback exposed this as scope-punt. Revisit-with-pressure produced
  γ via server-side denormalization + the concrete cohort-coverage
  query. This is the bidirectional-contradiction-discipline pattern
  applied to my own first surfacings, not just to reviewing-Claude's
  framings against repo state. Capture in closeout § Discipline-pattern
  observations.
- **Three-layers-of-hearsay protection.** F1.4 verified Mike's
  6-day-old recall against current docs directly via WebFetch. The
  recall was correct (`thinking.type: "enabled"` → 400 on Opus 4.7);
  verification was net-positive (low cost, confirmed claim). Pattern:
  external-API-contract claims that have any layers of hearsay get
  verified-with-citation before landing in spec. Capture as a
  meta-discipline pattern.
- **Verification finding produced a real blocker** (F2.0). Mike's
  D1.3 directive ("If verification produces blockers, surface them")
  was load-bearing — without it I would have silently descoped task
  budgets to ship effort first. Capture as evidence the directive
  worked as intended.
- **F2.1 surface-as-finding rule first invoked at commit 2 prep
  (2026-05-04).** +35.7% test-count divergence (14 → 19) dispositioned
  as scope-correct (driven by Q3 + Q4 + Q6 disposition decisions, not
  silent drift); documented and rolled forward per F2.1's "(a) actual
  differs from precedent, acceptable if documented" branch; precedent
  established for future invocations of the rule. The disposition
  shape — "test-count divergence is scope-correct when driven by
  disposition decisions, not silent implementation drift" — keeps
  F2.1 estimates honest as estimates while preventing them from
  becoming budgets that constrain disposition quality at prep time.
  Captured here for the closeout § Discipline-pattern observations.
- **F2.1 surface-as-finding rule second invocation at commit 2 draft
  (2026-05-04).** 27 collected / 25 unique vs F3.6.E's 19 estimate
  (+92.9% over F2.1's 14 baseline). Two-reason pattern observation
  surfaced: (1) precedent-mirror tests added at draft time uniformly
  with established module patterns (parametrize blow-ups,
  default-populates-on-insert mirrors, sentinel-test mirrors);
  (2) pushback-driven additions at review time (test surface added
  in response to reviewing-Claude implementation-discipline
  pushbacks — Item 2's boundary-aware truncation surfaced 4
  truncation-branch tests). Both surface as F2.1 findings, both
  disposition as scope-correct, both accept rolling-forward without
  re-prep. Pattern is durable across two invocations in two commits.
- **Threshold-of-three watch is trigger-to-evaluate, not
  trigger-to-extract (F4.5.2, 2026-05-04).** At three instances,
  the discipline is to ASK the question, not to answer "yes" by
  default. Surface similarity does not imply shared semantic load.
  Reject extraction when the abstraction unit doesn't carry
  semantic weight; the rule-of-three is a heuristic, not a mandate.
  This frames the May 3 UTC-coercion precedent correctly:
  extraction landed there because the abstraction unit
  (timezone-aware-then-naive coercion) was load-bearing semantic
  logic, not just shared SQL shape. Future watch-for-pattern
  entries reading this trail should evaluate "what's the
  abstraction unit?" before assuming extraction at three is
  automatic. (Carried into Followup-7 Q3 disposition; threshold
  reframed to "anticipated commits 7-8" without commit-3
  extraction.)
- **F2.1 surface-as-finding rule third invocation at commit 3 draft
  (2026-05-04).** 15 collected / 11 unique vs F4.4's 8/10 estimate
  (87.5% over F2.1's +8 baseline). Three reasons identified for
  systematic low-by-25%-to-90% F2.1 estimate divergence (durable
  framing for chain-wide tolerance, established across commits
  2-3): (1) **precedent-mirror tests added at draft time** —
  defaults, parametrize blow-ups, sentinel mirrors uniform with
  established module patterns; (2) **pushback-driven additions at
  review time** — test surface added in response to reviewing-Claude
  pushback (e.g., commit 2's boundary-aware-truncation tests from
  Item 2 pushback resolution); (3) **disposition-driven invariant
  pins at draft time** — sentinel tests pinning disposition reasoning
  into durable regression (e.g., commit 3's `no_dedup_emits_per_tick`
  pinning Q1 Reading A and `does_not_write_audit_log` pinning Q6
  Reading A + F4.5.3 reasoning). All three reasons disposition as
  scope-correct + absorbed-pattern; chain-wide estimate accepts
  these as healthy discipline cost rather than budget overrun.
- **Carry-forward note convention established at commit 3
  (Decision 5 disposition, 2026-05-04).** Scope-determines-location
  routing: same-sub-milestone → grounding doc § Carry-forward notes;
  cross-sub-milestone → `BACKLOG.md` § Carry-forward to future
  sub-milestones (lazy-created on first cross-boundary entry). First
  sub-milestone-internal entry: cadence-volume interaction → commit 7.
  No cross-sub-milestone entries yet. Convention defends against
  pure-(a) "grounding doc only" (incomplete for cross-boundary) and
  pure-(b) "BACKLOG.md only" (junk-drawer risk + redundant with
  grounding doc surface that's already opened at every commit prep).
  Convention-reference lives in grounding doc § Carry-forward notes
  header note for future-Claude findability.
- **Decision 5 convention's first cross-sub-milestone materialization
  at commit 4 (2026-05-05).** F5.6.C disposition (b)-refined:
  audit_sample_log re-evaluator deferred to post-M5-flag-flips
  focused-observability sub-milestone. BACKLOG.md §
  Carry-forward to future sub-milestones lazy-created at commit 4
  with this entry; cross-references existing BACKLOG observability
  roadmap entry for canonical destination context. Convention
  operates as designed: first cross-boundary entry triggers
  section creation; future entries append.
- **Bidirectional-contradiction-discipline second invocation at
  commit 4 prep (2026-05-05).** F5.6.B Q1 reading reversal:
  Reading C (open TEXT + spec-doc taxonomy) abandoned in favor of
  Reading A (closed enum) after reviewing-Claude's three-counter-
  argument pushback exposed two specific framing errors: (1)
  PM-only permission scope conflated with pm.*-prefix-only
  taxonomy scope; (2) `pm.spin_rationale.recorded` exclusion
  based on aggregation-shape consumer framing missed
  sampling-as-random-selection consumer shape (which is the
  silent-compliance defense the table exists for). First
  invocation was F1.1 task_type γ scope-punt revisit during
  M5-continuous-pm-driver chain prep. Pattern is durable: surfacing
  questions produces real corrections to my own framing, not just
  dispositions between alternatives.
- **Naming-convention precedent (small) — refinements vs
  primed-notation (2026-05-05).** F5.6.C "(b)'" labeling
  suggested structural difference from (b); honest framing was
  "(b) with refinements 1, 2." For future iterations, refinements
  within a reading should be surfaced as "Reading X with
  refinements N1, N2" rather than primed-notation that suggests
  structural divergence. Captured as small precedent.
- **Bidirectional discipline on own framing — surface new options
  at the same level (2026-05-05).** F5.6.C (d) was introduced
  buried inside (b)'-vs-(b) framing rather than surfaced at the
  same level as (a)/(b). Belated honest acknowledgment landed when
  Mike asked "What's (d)?" Better practice: when introducing a
  new option beyond the surfaced set, present it at the same level
  with explicit "introducing (X) — wasn't in original disposition
  set" framing. Captured as bidirectional-discipline-on-own-framing
  observation.
- **Closed-enum sentinel pattern graduation (2026-05-05).** Pattern
  matured from threshold-of-three watch (active during commits 1-3)
  to established convention (commits 4+). At commit 4 the pattern
  is at 9 instances (mode, triggered_by, dispatches.status,
  output_type, decision, rationale_tag, source_event_kind,
  re_eval_state, plus pre-M5 dispatches enums). No longer requires
  watch-for-pattern tracking — closed-enum CHECK + Pydantic Literal
  + sentinel test is the standard shape for finite-taxonomy
  schema columns. Future closed-enum additions don't need
  threshold-of-three evaluation; they ship with the same shape
  unless a specific reason to deviate surfaces (cf. F5.6.B Q1
  reading reversal: my Reading C departure was specifically
  rejected by reviewing-Claude on cohort-coherence grounds).
- **Decision 4 colocation rule established at commit 4
  (2026-05-05).** Settings field tests colocate with their
  consumer, not with config.py. Reasoning: test boundary follows
  contract boundary, not source-file boundary; config.py
  aggregates fields without owning their semantics. Captured in
  BACKLOG.md § Conventions for cross-sub-milestone durability.
  First instance: `Settings.audit_sample_percentage` tests in
  `tests/unit/audit/test_sampler.py` alongside Sampler writer
  tests, not in a dedicated `test_config.py`.
- **F2.1 surface-as-finding rule fourth invocation at commit 4
  draft (2026-05-05).** 29 collected / ~21 unique vs F5.4's 13/15
  estimate; 29 / 10 baseline (F2.1's commit-4 estimate) = 190%
  over. Largest divergence yet. **Trajectory observation across
  commits 2-4:** 64% → 93% → 190% over F2.1 estimates. Each
  commit's drivers honest per the three-reason framing
  (precedent-mirror tests, pushback-driven additions,
  disposition-driven invariant pins); the trajectory-shape
  observation is independent of any single commit's drivers.
  **Trigger condition pinned for commit 5:** if commit 5's F2.1
  divergence exceeds 200% with the same three-reason framing,
  re-prep commits 5-9 estimates with current empirical data —
  the issue at that point isn't the specific commit but that
  F2.1 estimates have become miscalibrated for the chain's
  actual disposition density. Don't re-baseline now; trigger
  condition activates at commit 5 if hit.
- **Bidirectional-contradiction-discipline third invocation at
  commit 5 prep (F6.6.B / F6.5.1, 2026-05-05).** Q1 framing-
  precedent reset: Reading A (full 5-value Effort Literal) was
  substantively right but defended via F5.6.B-symmetry framing
  that didn't carry — F5.6.B was about SQL CHECK migration cost;
  effort Literal expansion is type-annotation cost. Reviewing-
  Claude's pushback tested the precedent's actual conditions;
  Code's reset honestly named "tried to import F5.6.B precedent's
  authority via symmetric-framing dress-up." Reading A re-defended
  on operator-agency grounds (Argument 2) — yaml field is operator
  tuning surface; full Anthropic-supported set admits exploration.
  First instance was F1.1 task_type γ scope-punt revisit; second
  was F5.6.B Q1 enum reversal; third is this framing-precedent
  reset.
- **Framing-precedent-application discipline emerging as distinct
  review surface (2026-05-05).** Pattern observation across the
  three bidirectional-contradiction instances: commits 1-3
  pushbacks were on **substance** (Code's recommendation was
  wrong-on-merits; pushback exposed the wrong-ness). Commits 4-5
  pushbacks were on **framing-precedent-application** (Code's
  recommendation was substantively right but defended via
  precedent-shape that didn't quite carry). Different shape;
  different review-mindset target. The reviewing-Claude habit of
  testing "does the precedent's actual conditions hold here?"
  produces real corrections that substance-only review wouldn't
  catch. Capture as distinct discipline pattern from
  substance-disposition review.
- **Bidirectional-contradiction-discipline fourth invocation at
  commit 6 prep — pattern operating in third direction (2026-05-06).**
  F7.5.1 / Q1: Mike's commit-6-prep instruction said
  "New doc: docs/kickoff/orchestrator-utilization-addendum.md" —
  wrong on both counts (not new; wrong file vs F1.3 disposition's
  separate `opus-4-7-effort-and-thinking-addendum.md`). Code's
  prep surfaced the discrepancy as Q1 with three Readings; Mike's
  disposition acknowledged the prep-instruction error and
  accepted Reading C (separate file with cross-reference). First
  three bidirectional instances (F1.1 / F5.6.B / F6.5.1) involved
  Code's recommendations being corrected by reviewing-Claude
  pushback. This fourth instance is reviewing-Claude's prep
  instruction being corrected by Code's prep surfacing.
  **Pattern operating in third direction:** discipline applies to
  reviewing-Claude's prep instructions, not just to Code's
  surfacings or to recommendation framing. The bidirectional
  discipline is genuinely bidirectional — both reviewer and
  drafter check each other's framing.
  Three direction-shapes now identified:
    1. Substance reversal (commits 1-3): Code's recommendation
       wrong-on-merits; reviewing-Claude exposes via pushback.
    2. Framing-precedent-application (commits 4-5): Code's
       recommendation substantively right but defended via
       precedent that doesn't carry; reviewing-Claude tests the
       precedent's conditions.
    3. Prep-instruction error (commit 6): reviewing-Claude's
       prep instruction itself wrong; Code's prep surfaces the
       discrepancy against canonical disposition (F1.3 here).
  Pattern is durable across both roles; capture as
  three-direction discipline.
- **Bidirectional-discipline fifth invocation has hybrid
  direction-shape (commit 7 prep, F8.6.A, 2026-05-06).** Both
  prep-instruction-error AND framing-precedent-application
  fired simultaneously: (a) Mike's prep instruction said
  pm_supervisor.py consumes tools/budget.py + tools/pm_focus.py
  (substantively wrong per Reading D defense's
  symmetry-with-EngineerSupervisor + single-responsibility +
  closure-pattern-precedent arguments); (b) Code's first Reading
  D defense leveraged the P-2 architectural-separation citation
  applied more strongly than the docstring's text supports
  (citation is scoped specifically to `_reconstruct_state`'s
  no-op behavior; "subprocess owns startup reads" doesn't
  generalize to "supervisor doesn't grow loop-driver state at
  any commit"). Both errors corrected in one verification round.
  **Refines the prior closeout note about prep-instruction
  frequency:** the pattern isn't reviewing-Claude prep
  instructions running loose in isolation; it's that
  prep-instructions and framing-precedent-application can
  compound, and verification discipline (paste citation
  verbatim) catches the compound through citation precision.
  Once cited verbatim, over-extension becomes self-evident and
  triggers re-defense on independent arguments. Three-direction
  classification stays useful but compound instances expected
  going forward.
- **Carry-forward annotation discipline (commit 7 prep, F8.6.B,
  2026-05-06).** When downstream commits change baseline
  assumptions for an existing carry-forward note, the discipline
  is to **annotate in-place** preserving the original reasoning
  chain — not to silently rewrite. Format: `**DISPOSITIONED YYYY-MM-DD
  (commit N prep, F<X>.<Y> Q<Z>-<reading>):** <new value> per
  <reasoning>; <prior assumption> from commits <a-b> prep
  preserved as historical context; volume framings from prior
  commits hold under <new value> with margin. **Re-evaluation
  trigger:** <empirical condition that warrants revisit>`.
  Preserves the cumulative reasoning chain so future-Claude
  reading the closeout sees the trajectory of decisions, not
  just the final state. First instance: F8.2 Q2-B disposition
  changing tick cadence baseline from anticipated 30s (commits
  3-4 prep) to 60s default (commit 7 disposition).
- **Code-inspection sentinel observability convention (commit 7
  draft, 2026-05-06).** Code-inspection sentinels (tests that
  read production source files as text and assert forbidden
  patterns) are brittle to legitimate refactor by design — that
  brittleness is the appropriate cost of explicit
  disposition-pinning. To distinguish substantive architectural
  drift from path/pattern staleness, when a code-inspection
  sentinel fires the disposition pass categorizes the failure:
  - **Mode 1 (legitimate refactor):** source-file rename /
    split / reorganization preserves architectural intent;
    sentinel breaks because path or pattern is stale. FIX:
    update sentinel's path / pattern. EXPECTED REFACTORING
    COST: mechanical. Track as Mode 1 fire in that commit's
    diff checkpoint with one-line note.
  - **Mode 2 (architectural drift):** code change violates the
    dispositioned invariant substantively. Sentinel fires
    correctly. FIX: revert the architectural drift, NOT the
    sentinel. (If genuine substantive reason to change the
    invariant: re-litigate the disposition in a prep checkpoint,
    then update sentinel after re-disposition lands.) Track as
    Mode 2 fire (a discipline win — sentinel caught real
    violation).
  Sentinel failure messages must explicitly distinguish the two
  modes and prompt the developer to determine which applies
  (see test_pm_supervisor_does_not_import_tools_budget_or_pm_focus
  + test_pm_loop_module_does_not_define_read_current_helper for
  the canonical message shape).
  **Mode 1 / Mode 2 tracking + (b)-revisit trigger:** track Mode
  1 fire count + update complexity per chain. (b) revisit
  trigger (sentinel-pattern-vs-runtime-test): 3+ Mode 1 fires
  within chain OR single Mode 1 fire requiring materially
  complex sentinel update (more than mechanical path / pattern
  adjustment). Either trips revisit. Mode 2 fires don't trigger
  (b) revisit — they validate the (a) sentinel-pattern choice.
  Makes the sentinel-pattern choice empirical-not-aesthetic;
  future-Claude reviewing a fire knows which mode + which fix.
  First commit shipping the convention: commit 7 of
  M5-continuous-pm-driver, with two sentinels (Q4 + Q9
  invariant pins).
- **F2.1 surface-as-finding rule fifth invocation at commit 5
  draft (2026-05-05) — trigger condition NOT FIRED per metric
  clarification.** 19 collected / 7 unique vs F2.1's +9 estimate.
  By collected count: 19/9 = 211% over (would trip 200% trigger);
  by unique-function count: 7/9 = 78% under (doesn't trip). Mike
  dispositioned (b) — accept unique-function as primary trigger
  metric.

  **Metric clarification pin (durable convention):** F2.1
  surface-as-finding rule uses **unique-function count as primary
  metric for trigger evaluation**. Reasoning: F2.1 estimates are
  set at prep time before parametrize-blow-up patterns are
  visible; estimates implicitly target unique-function intent.
  Collected count is secondary observation that includes
  parametrize multiplicity (coverage discipline, not cognitive
  surface expansion). Trigger evaluation uses unique-function
  metric; collected-count trajectory tracked as observation.

  This is metric clarification, not retroactive trigger change.
  The F2.5 trajectory note (commits 2-4: 64% → 93% → 190%) cited
  collected counts because that was the framing in effect when
  the trigger was set; (b)'s acceptance clarifies forward without
  re-litigating prior commits.

  **Unique-function trajectory across commits 2-5:**

  | Commit | Unique actual | F2.1 estimate | Divergence |
  |---|---|---|---|
  | 2 | ~21 | ~14 | +50% |
  | 3 | ~11 | ~8 | +37% |
  | 4 | ~21 | ~10 | +110% |
  | 5 | 7 | ~9 | −22% |

  Different shape than collected-count trajectory (commits 2-4
  ran ~2-3× baseline). Unique-function tracks closer to estimate
  with one outlier (commit 4); commit 5 is UNDER baseline.

  **Three-reason framing applicability per metric:**
  - (1) Precedent-mirror tests: applies dominantly to **collected**
    count (parametrize blow-ups); modestly to unique count
    (default-populates patterns mirroring established
    yaml-loading shapes).
  - (2) Pushback-driven additions: applies to **unique** count
    (new test functions added in response to review).
  - (3) Disposition-driven invariant pins: applies to **unique**
    count (sentinels and invariants are unique cognitive surface,
    not parametrize multiplicity).

  Three-reason framing applies primarily to unique-function
  metric. Collected-count divergence dominantly attributable to
  reason (1) parametrize blow-ups; not load-bearing for trigger
  evaluation. Trigger condition stays at >200% with the
  unique-function metric across commits 6-9; collected-count
  observation continues without trigger-firing role.

---

## What lands at the diff checkpoint

After Mike + reviewing-Claude disposition the questions above:

- F1.1 resolved (γ + denormalization) — commits 1-2
- F1.2 resolved (A1 + B1 + keep-whole) — informs commit decomposition
- F1.3 resolved (hybrid spec amendment + effort-only at this
  sub-milestone) — commits 6-7; task budgets deferred per Q5(α) if
  accepted
- F1.4 resolved — no daemon-side wrapper changes; documented in
  amendment
- Q1-Q8 dispositioned

Diff checkpoint surfaces commit-by-commit prep with the above as
fixed scope. Order of operations: Mike + reviewing-Claude review this
prep checkpoint → dispositions → I draft commit 1 → diff checkpoint
for commit 1 → commit 1 lands → repeat through commit 10 → e2e tests
+ closeout doc → sub-milestone closes.

**End of Followup-2.**

---

# Followup-3 — commit 2 prep checkpoint (pm_current_focus + write/read API + completion-validation audit event-kind)

**Date:** 2026-05-04. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 2 (revised 9-commit chain, post-rollback).
**Prerequisites landed:** commit 1 (workflow_id α-denormalization, ff47573).
**Pre-prep tidy applied at session open:** F2.4 watch-for-pattern entry
"Migration count" corrected from "(0010, 0011, 0012, 0013 — four,
actually)" to "(0010, 0011, 0012)" — drift introduced during the F2.1
revision (2026-05-03) when the original 0010/`tasks.task_type` row
moved to M4d but F2.4 wasn't synced. F1.1's references at lines 631
and 643 are within the historical-record-of-withdrawn-position scope
(the F2.1 rollback note explicitly defers the M4d migration namespace
to "TBD when M4d preps") — left as-is.

---

## F3.1 — touch points

Per F2.1 row 2: `storage/sql/0010_*.sql`, `tools/pm_focus.py` (new),
`audit/logger.py`. Resolved file-by-file:

| File | Status | Touch shape |
|---|---|---|
| `src/meridian_orchestrator/storage/sql/0010_pm_current_focus.sql` | NEW | Table DDL with `id / objective / output_type / acceptance_criteria / bound_workflow_id (nullable FK to tasks) / declared_at / completed_at / contract_validated_bool / contract_finding_text`; indexes per Q5 disposition. CHECK constraint on `output_type` only if Q1 closed-enum lands. |
| `src/meridian_orchestrator/tools/pm_focus.py` | NEW | Pydantic schemas (`DeclareFocusInput/Output`, `CompleteFocusInput/Output`, `GetCurrentFocusOutput`, `PmCurrentFocus` row model); `read_current_focus` helper (mirrors `read_current_pm_mode`); `declare_current_focus` / `complete_current_focus` handlers; `OutputType` Literal; `_PM_FOCUS_AUDIT_ACTIONS` constants. |
| `src/meridian_orchestrator/audit/logger.py` | UNCHANGED | Audit-log `action` column is free-form TEXT (no schema enum), so "event-kind extension" lands as new namespaced action constants in `tools/pm_focus.py` — `pm.current_focus.declared`, `pm.current_focus.completed`, `pm.current_focus.contract_violation`. No code change to `audit/logger.py` itself; spec amendment to `11-engineer-bridge.md` § Audit log entries is the documentation surface. |
| `src/meridian_orchestrator/server.py` | EDIT | `_register_pm_focus_tools` helper added; called from `build_server` alongside `_register_pm_mode_tools` (line 314). MCP tools per Q6 disposition. |
| `src/meridian_orchestrator/main.py` | UNCHANGED | No lifespan changes — `tools/pm_focus.py` uses only `Storage`, no new daemon dependencies. (Compaction-trigger writer at commit 8 may need lifespan; not this commit.) |
| `docs/kickoff/06-mcp-tools-phase-one.md` | EDIT | New tool entries per Q6 closed disposition. |
| `docs/kickoff/05-data-model.md` | EDIT | New `pm_current_focus` table entry mirroring `pm_mode_log` shape. |
| `docs/kickoff/11-engineer-bridge.md` | EDIT | Audit-log entries table extended with `pm.current_focus.*` actions per Q4 disposition. |
| `tests/unit/storage/test_migrations.py` | EDIT | Migration-applied smoke test for 0010 (mirror existing 0009 pattern). |
| `tests/unit/tools/test_pm_focus.py` | NEW | Mirrors `test_pm_mode.py` shape (declare / complete / read / atomicity / output_type sentinel if closed-enum). |
| `tests/integration/test_pm_focus_mcp_wrapper.py` | NEW | Mirrors `test_pm_mode_mcp_wrapper.py` (PM-allowed, non-PM-rejected, audit-row emitted). |

**Spec amendments ride at commit 2** per established convention
(`feat(M5-pm-mode-and-window-state): commit 6 — get_window_state + Q8
helper + spec amendments` precedent). The 06 / 05 / 11 doc edits
co-locate with the implementing commit so future drift-scan can pin
spec text to landed code via single-commit blame.

---

## F3.2 — open questions (Q1-Q9)

### Q1 — `output_type` typing: closed enum or open text?

The deliverable contract per F1's Section 2:
`(objective / output_type / acceptance_criteria)`. `output_type`
classifies what the focus produces.

- **Reading A — closed enum.** `Literal["document", "code_change",
  "decision", "review", "investigation"]` + CHECK constraint on
  `pm_current_focus.output_type` + sentinel test pinning the enum.
  Forces taxonomic discipline; cohort queries trivial; cohort signal
  for tier-2 narration evolution. Mirrors `pm_mode_log.mode` /
  `dispatches.status` / pending Q3 `pm_spin_rationale_log.rationale_tag`
  pattern. Risk: forced-fit on early validation against real workload
  (echoes the F1.1-Section-1 task_type α rejection). Mitigation: the
  five-value taxonomy is already operator-meaningful and can evolve
  via future migration (precedent: `0008_dispatches_status_check.sql`).
- **Reading B — open text.** No CHECK; PM writes free-form output_type
  values per yaml prompt convention. Lower discipline; higher noise;
  cohort queries collapse. Same downside shape as the rejected
  rationale_tag-as-free-text in Q3.
- **Reading C — open text now, closed-enum after one real-loop
  cycle.** Ship Reading B at commit 2; convert to closed enum at
  M5-flag-flips after observing what PM actually writes. Avoids
  forced-fit but defers discipline; the dead-text-values produced
  during the open-text window become permanent rows the eventual
  CHECK constraint must accommodate (or rewrite via UPDATE).

**Recommendation: Reading A (closed enum).** The five-value taxonomy
is already operator-meaningful and matches the BACKLOG observability
roadmap's tier-1 narration framing ("PM declares deliverable contract;
contract validates at completion"). `code_change` / `document` /
`decision` / `review` / `investigation` cover the narration shapes
this loop produces. Forced-fit risk is lower than for task_type
because output_type classifies the focus's PRODUCT rather than the
work-shape itself; product taxonomies evolve more slowly than work
taxonomies. If a sixth shape emerges, future migration (mirror
`0008_dispatches_status_check.sql` precedent) is the right tool.
Reading C's gradual approach trades current discipline for future
backfill cost; not worth it.

### Q2 — `pm_mode_log_id` FK on `pm_current_focus`?

F1's Section 2 schema sketch named `pm_mode_log_id` as a column.
Surface as a question because the relationship semantics are weak.

- **Reading A — keep the FK.** Each focus declaration links to the
  most-recent pm_mode_log row at declaration time. Audit-trail
  property: "this focus was declared while PM was in mode X."
  Operator-side reconstruction of "what mode was PM in when this
  focus landed" via single JOIN.
- **Reading B — drop the FK.** `pm_current_focus` is independent of
  pm_mode transitions. Modes change asynchronously (Mike messages
  flip continuous → interactive); foci change at PM's deliberate
  declaration. The semantic relationship is "co-incident at a moment
  in time," not "owned by." Operator queries reconstruct via
  timestamp-join (`WHERE pm_mode_log.timestamp <= pm_current_focus.declared_at`),
  not FK-join.

**Recommendation: Reading B (drop the FK).** The operator-side
"what mode was PM in" question is answerable via timestamp-join
against pm_mode_log without coupling the schema. Coupling produces
maintenance friction: every pm_mode-transition that happens between
declare and complete (Mike messages flipping to interactive then
back) makes the recorded pm_mode_log_id stale. Independent tables
with timestamp coordination is the more honest shape.

### Q3 — `bound_workflow_id` storage shape

Per F2.3 Q8 disposition (Reading C): `pm_current_focus.bound_workflow_id`
is the chain root task_id when the focus is workflow-bound; NULL
when the focus is unrelated to dispatched work. Implementation
question:

- **Reading A — `bound_workflow_id TEXT REFERENCES tasks(id)`,
  nullable.** Foreign-key constraint enforces referential integrity;
  workflow_id must point at a real task row. Matches the existing
  `api_usage.task_id REFERENCES tasks(id)` pattern.
- **Reading B — `bound_workflow_id TEXT`, no FK.** Free-text storage.
  Lower constraint cost; admits typos / dangling references. Same
  shape as `api_usage.workflow_id` in 0009 (no FK there because
  caller-supplied workflow_id may predate the task rows or be
  operator-meaningful labels rather than chain roots).

**Recommendation: Reading A (FK with nullable).** Unlike
`api_usage.workflow_id` (which is caller-supplied OR
server-derived from chain-root walk in commit 1's wrapper),
`pm_current_focus.bound_workflow_id` is set at declare-time by PM
asserting "this focus is the operator's intent for workflow X."
PM has the bound task in scope (it's the workflow root it submitted);
referential integrity is cheap and catches the
PM-references-nonexistent-task bug class at write time. ON DELETE
behavior: leave default (NO ACTION) — if a workflow root task is
deleted, the focus row's FK constraint blocks; cascade or SET NULL
would silently corrupt narration.

### Q4 — Audit-log `action` namespace and shape

Three new namespaced actions land at this commit:
`pm.current_focus.declared`, `pm.current_focus.completed`,
`pm.current_focus.contract_violation`. Question is target + details
shape per action.

- **Reading A — target = focus_id; per-action details payloads
  fully-specified.**
  - `pm.current_focus.declared`: target=focus_id; details={objective,
    output_type, acceptance_criteria, bound_workflow_id, declared_at}
  - `pm.current_focus.completed`: target=focus_id; details={focus_id,
    contract_validated, finding_text (if any), completed_at,
    declared_at_for_join}
  - `pm.current_focus.contract_violation`: target=focus_id;
    details={focus_id, contract_validated=false, finding_text,
    actual_output_summary}
- **Reading B — minimal details; reconstruction via FK-join to
  pm_current_focus.** target=focus_id; details only carries data not
  in the table row (e.g., contract_violation's actual_output_summary).

**Recommendation: Reading A (full payloads).** Audit log is the
durable timeline; full payloads make the timeline self-contained
without requiring future-Claude / operators to JOIN through
pm_current_focus to reconstruct what happened. Storage cost is
trivial (audit details_json is a TEXT column, no row count
explosion). Mirrors the dispatch.* audit action precedent in
`11-engineer-bridge.md` § Audit log entries (full per-action
payloads).

The third action `pm.current_focus.contract_violation` is the
completion-validation finding type promised by F1's Section 2. It
fires alongside `pm.current_focus.completed` when
`contract_validated=false`. Per F2.2 A1 disposition (loop-side
validation), the loop computes the validated/finding result and
calls `complete_current_focus`; the writer emits both
`pm.current_focus.completed` AND `pm.current_focus.contract_violation`
(when applicable) in the same transaction.

### Q5 — Indexes on `pm_current_focus`

What indexes ship at the migration?

- **Reading A — `idx_pm_focus_declared_at` (BTREE on declared_at)
  + `idx_pm_focus_bound_workflow` (BTREE on bound_workflow_id) +
  `idx_pm_focus_active` (partial INDEX where `completed_at IS NULL`).**
  Three indexes. The first supports "most recent focus" lookups;
  the second supports "all foci for workflow X" queries; the third
  supports "is there an active focus right now" — the loop's hot path.
- **Reading B — `idx_pm_focus_declared_at` only.** Single index; rely
  on `ORDER BY rowid DESC LIMIT 1` for read_current_focus (mirrors
  `read_current_pm_mode`'s rowid-DESC ordering).
- **Reading C — no indexes.** Table is low-volume; sequential scan
  acceptable.

**Recommendation: Reading B (declared_at only).** SQLite partial
indexes are supported but add maintenance cost; the active-focus
lookup happens at most once per loop tick (~30s — see commit 7
prep), well within sequential-scan bounds even at 1000+ row table
size. The bound_workflow_id index is premature — workflow-cohort
queries land post-M5-flag-flips per BACKLOG observability
sequencing, and the workflow_id-on-api_usage index from 0009 is
the load-bearing surface for those queries (api_usage rows
outnumber pm_current_focus rows by 100x+). Add bound_workflow_id
index later if the cohort workload tips.

### Q6 — MCP tool surface at commit 2

Three handlers in `tools/pm_focus.py`. Question is which ship as MCP
tools at commit 2 vs deferred.

- **Reading A — three MCP tools at commit 2:** `declare_current_focus`
  (PM-only), `complete_current_focus` (PM-only), `get_current_focus`
  (PM/Engineer per get_window_state precedent).
- **Reading B — two MCP tools at commit 2:** declare + complete only
  (PM-only). Read API exists in `tools/pm_focus.py` for daemon-side
  reuse but no MCP wrapper.
- **Reading C — zero MCP tools at commit 2.** Module exposes
  Python-API only; MCP wrappers ship at commit 7 alongside the loop
  driver that consumes them.

**Recommendation: Reading B (declare + complete).** The continuous
PM is a Claude Code subprocess and consumes pm_focus via MCP — so
declare + complete MUST be MCP-exposed by the time commit 7 lands.
Shipping them at commit 2 lets commit 7's loop integrate against the
real MCP surface without a co-shipping coupling.

`get_current_focus` MCP exposure is deferred per BACKLOG observability
read-side-ships-post-M5-flag-flips sequencing. The Python-side
`read_current_focus` helper is needed at this commit for the writer's
dangling-detection logic (Q9) and for commit 7's loop reuse, but
it's not wrapped as an MCP tool until a real consumer surfaces.
Engineer's mode-aware planning may eventually need it (mirror Q4
disposition pattern); promote at that time.

### Q7 — Atomicity envelope for declare / complete

Mirrors set_pm_mode's envelope. Question is whether to special-case
declare's dangling-detection.

- **Reading A — single-transaction wrap.** declare:
  `transaction { read_dangling_focus → optional auto-complete-as-superseded
  → INSERT new focus → write_audit }`. complete:
  `transaction { read_focus_by_id → UPDATE focus completed → write_audit
  (× 1 or 2 depending on contract_validated) }`. Mirrors set_pm_mode.
- **Reading B — separate transactions for dangling-detection vs new
  declare.** Reduces transaction scope; admits TOCTOU between read and
  INSERT.

**Recommendation: Reading A (single transaction).** TOCTOU
concerns are real (concurrent declare calls during a Mike-interrupt
race) and the transaction cost is negligible for low-volume writes.
Per `set_pm_mode`'s precedent rationale at lines 188-198 of
`tools/pm_mode.py`. Not contested.

### Q8 — Dangling-incomplete-focus handling on new declare

When PM calls `declare_current_focus` while a prior focus has
NULL `completed_at` (no explicit complete call landed), what
happens?

- **Reading A — auto-complete prior with `contract_validated=NULL` +
  `contract_finding_text='superseded'`.** Treat the prior as implicitly
  superseded; the new declaration succeeds. Dangling rows never
  accumulate.
- **Reading B — reject the new declaration with
  `OrchestratorPermissionError`-shaped error.** PM must explicitly
  complete-or-abandon the prior first. Forces discipline.
- **Reading C — allow concurrent multiple-active-foci.** No constraint;
  any number of incomplete focus rows can coexist.

**Recommendation: Reading A (auto-complete-as-superseded).** The
continuous loop's reality includes Mike-interrupts that can arrive
mid-focus and cause PM to pivot without a clean completion. Reading B
is too rigid for that workload. Reading C loses the
"current_focus is one thing at a time" semantic that the read-side
queries depend on (`read_current_focus` returning "the active focus"
implies at-most-one). Reading A preserves the at-most-one invariant
while admitting the operational reality. The auto-completion writes
a separate audit row `pm.current_focus.superseded` (fourth audit
action at this commit — surface for Q4 amendment if Reading A
sticks).

### Q9 — `complete_current_focus` shape: id-required or current-implicit?

Does `complete_current_focus` require the caller to pass the
`focus_id`, or does it implicitly target the current (most-recent
incomplete) focus?

- **Reading A — required `focus_id` parameter.** Caller passes the
  id of the focus being completed. Explicit; admits backfill /
  out-of-order completions; matches REST-shaped expectations.
- **Reading B — implicit current focus.** Handler reads
  `read_current_focus()` and completes that one. Caller omits id.
  Fewer error modes; cannot accidentally complete the wrong focus;
  matches `set_pm_mode`'s pattern (no id required).

**Recommendation: Reading B (implicit current).** PM in the loop
has exactly one active focus at any time (per Q8 Reading A
invariant). Requiring id is friction without value — PM can't
complete a prior focus that's already been auto-superseded, and
there's no concurrent-focus case where id-disambiguation matters.
Mirrors `set_pm_mode`'s id-free shape. The audit row records
`focus_id` in details for downstream join.

If Mike wants Reading A: add ~10 lines to pm_focus.py for the
explicit lookup + lookup-error handling.

---

## F3.3 — watch-for-pattern checks

| Pattern | Status pre-commit | Commit 2 instance | Threshold? |
|---|---|---|---|
| Closed-enum CHECK + sentinel test | 3 (pm_mode_log.mode, pm_mode_log.triggered_by, dispatches.status; pending: pm_spin_rationale_log.rationale_tag at commit 3) | +1 if Q1 Reading A: `pm_current_focus.output_type` (5-value Literal). | Pattern is durable; threshold-of-extraction not yet triggered (no shared utility worth pulling out). |
| `read_current_X` helper with rowid-DESC ordering | 1 (`read_current_pm_mode`) | +1: `read_current_focus`. | Threshold-of-three: at the THIRD instance, surface for shared utility extraction. Watch at commit 3 (`pm_spin_rationale_log` may add a third). **REFRAMED 2026-05-04 (F4.5.1, Followup-7):** commit 3's pm_spin is write-only per Q1+Q4 dispositions; no third instance introduced at commit 3. Reset watch to "anticipated third instance at commits 7-8 when continuous-loop driver's read consumers land." Commit-3-no-extraction is NOT discipline drift — it's the writer-only nature of the pm_spin commit, plus the Q4 disposition (no daemon-side read helper) plus the Q3-Reading-E observation that surface similarity doesn't necessarily mean shared semantic load. |
| Nullable optional FK to `tasks(id)` | 1 (`api_usage.task_id`) | +1 if Q3 Reading A: `pm_current_focus.bound_workflow_id`. | Pattern is small, no extraction warranted. |
| Audit action namespacing `pm.<surface>.<verb>` | 1 (`pm_mode.transition`) | +3 (or +4 if Q8-A adds `pm.current_focus.superseded`): `pm.current_focus.declared` / `.completed` / `.contract_violation`. | First multi-action surface in the `pm.*` namespace; sets precedent for commit 3 (`pm.spin_rationale.recorded`) and commit 4 (`pm.audit_sample.*`). |
| Single-transaction declare-with-dangling-detection | 0 | +1: declare auto-supersede pattern. | Net-new pattern. May surface in commit 3's spin-rationale writer if rate-limit collapsing applies; watch then. |
| Production-code-surface-for-testability | 2 pre-chain (token_store=None defensive surface; `_run_lifespan_body` refactor) | 0 expected; pm_focus's helpers don't introduce new test seams beyond established patterns. | Per F2.4 watch — third instance hypothesized at commit 8 (loop driver injectable clock). Commit 2 doesn't trip it. |
| `bridge/` → `tools/` cross-layer imports | 2 pre-chain | 0 at commit 2 (pm_focus.py is `tools/`-only). | Per F2.4 watch — commits 7-8 may add instances 3-4. |
| Migration count | 1 added at commit 1 zero (commit 1 was wrapper-only) | +1: 0010_pm_current_focus.sql. | Per F2.4 watch (revised today): 3 total at this sub-milestone (0010/0011/0012). Boilerplate-extraction threshold not yet tripped. |

---

## F3.4 — test-target estimate

F2.1 estimate: +14. Working breakdown against that target:

| Layer | Count | Notes |
|---|---|---|
| Migration smoke tests | 1 | `test_migration_0010_creates_pm_current_focus` — table + indexes + FK + CHECK present after apply. Unit, mirrors 0009 tests. |
| CHECK-constraint sentinel | 1 | `test_pm_current_focus_output_type_check_constraint` — INSERT each closed-enum value succeeds; INSERT bogus value raises. Lands only if Q1 Reading A. |
| `read_current_focus` helper | 2 | empty-table → None; populated → most-recent-by-rowid. |
| `declare_current_focus` writer | 4 | basic declare; declare-with-bound-workflow; declare-while-prior-active (Q8-A auto-supersede); audit row emitted with full payload (Q4-A). |
| `complete_current_focus` writer | 3 | basic complete (contract_validated=true); complete-with-finding (contract_validated=false → second audit row for `pm.current_focus.contract_violation` per Q4-A); complete-when-no-active raises. |
| MCP wrappers | 2 | PM-allowed (declare + complete each smoke); non-PM-rejected (one path tests the gate). |
| Audit-action namespace | 1 | All four (or three) `pm.current_focus.*` actions emit with correct namespace + payload — table-driven test. |
| Total | **14** | Aligns with F2.1 estimate. |

**Range under disposition variance:** Q1 Reading B drops the
CHECK-constraint sentinel (-1 → 13). Q8 Reading B drops the
auto-supersede test path (-1 → 13). Q9 Reading A adds the explicit
focus_id lookup test path (+2 → 16). Per F2.1's surface-as-finding
rule: divergence > 20% (i.e., < 11 or > 17) surfaces; this range
sits within tolerance.

**Verification gates per project pattern:** ruff, mypy --strict,
pytest. Integrated-state-green; per-commit isolation not strictly
required at this commit (no destructive history rewrites in scope).

---

## F3.5 — what lands at the diff checkpoint

After Mike + reviewing-Claude disposition Q1-Q9 above:

- 0010 migration drafted with chosen constraints
- `tools/pm_focus.py` drafted with chosen handler shapes
- `server.py` `_register_pm_focus_tools` drafted
- Spec amendments drafted for 06 / 05 / 11
- Test fixtures + assertions drafted

Diff checkpoint surfaces commit-by-commit for review; commit 2
lands; commit 3 prep checkpoint opens (`pm_spin_rationale_log` —
will share `read_current_X` helper rowid-DESC pattern; rationale_tag
closed enum per F2.3 Q3 Reading A).

**End of Followup-3.**

---

# Followup-6 — F3.2 disposition feedback + Q3/Q6 re-prep + Q4 update

**Date:** 2026-05-04. **Trigger:** Mike's F3.2 disposition pass surfaced
three items needing re-prep before commit 2 drafts open: Q3 collapsed
three readings into one; Q6 contradiction between "Python helper for
daemon-side reuse" and the subprocess-MCP-only consumer reality at
commits 7-8; Q4 framing of the fourth audit action as conditional on
Q8 should be made explicit now that Q8 Reading A is dispositioned.
Six accepts noted at section close. F3.4 test-target update triggered
by the re-prep dispositions surfaced as F2.1-shape finding.

Section numbered F3.6 (skipping F3.5's "diff checkpoint" sub-section
slot reserved for the next round when Q3/Q6 disposition lands).

---

## F3.6.A — accepts noted (Q1 / Q2 / Q5 / Q7 / Q8 / Q9)

| Q | Disposition | Notes |
|---|---|---|
| Q1 | Reading A (closed-enum `output_type`) | 5-value Literal + CHECK + sentinel test. Closed-enum sentinel pattern continuing per F3.3. |
| Q2 | Reading B (drop `pm_mode_log_id` FK) | Independent tables with timestamp coordination > FK coupling for co-incident-not-owned relationships. |
| Q5 | Reading B (declared_at index only) | bound_workflow_id index deferred-with-rationale. **BACKLOG entry surfaced 2026-05-04** at `BACKLOG.md` § Deferred (entry titled "Add idx_pm_focus_bound_workflow..."). Trigger named: real workflow-cohort query against `pm_current_focus` surfacing in operator-side ad-hoc SQL or typed output field on a future tool. |
| Q7 | Reading A (single transaction) | Mirrors set_pm_mode precedent. Not contested. |
| Q8 | Reading A (auto-complete-as-superseded) | Fourth audit action `pm.current_focus.superseded` ships with this disposition. Payload spec'd in updated Q4 below. |
| Q9 | Reading B (implicit current focus) | Mirrors set_pm_mode id-free shape. |

These six accepts compose the fixed-scope baseline. Re-prep targets
below produce dispositions that compose with the accepts to fix
commit 2's full scope.

---

## F3.6.B — Q3 re-prep with three explicit readings

**Original framing collapsed Reading A's NO ACTION vs SET NULL choice
into prose; the no-FK alternative was treated implicitly via the
`api_usage.workflow_id` precedent reference but not surfaced as a
distinct Reading.** Re-prep below.

### Q3 (re-prepped) — `bound_workflow_id` storage shape

Per F2.3 Q8 disposition (Reading C): `pm_current_focus.bound_workflow_id`
is the chain root task_id when the focus is workflow-bound; NULL
when the focus is unrelated to dispatched work. Three implementation
shapes:

- **Reading A — `bound_workflow_id TEXT REFERENCES tasks(id)` with
  ON DELETE NO ACTION (default), nullable.** Foreign-key constraint
  enforces referential integrity at write time (PM-references-
  nonexistent-task bug class caught immediately) and at task-deletion
  time (deleting a task with referencing focus rows is BLOCKED;
  operator must explicitly resolve before deletion).
- **Reading B — `bound_workflow_id TEXT REFERENCES tasks(id)` with
  ON DELETE SET NULL, nullable.** Foreign-key constraint at write
  time same as A; on task deletion, referencing focus rows have
  their `bound_workflow_id` silently NULLed. Narration record
  loses the historical "this focus was bound to workflow X" claim
  without trace.
- **Reading C — `bound_workflow_id TEXT`, no FK, nullable.** Free-text
  storage. No write-time integrity check; PM may store typos,
  stale ids, or operator-meaningful labels that aren't task ids.
  Same shape as `api_usage.workflow_id` in 0009.

**Recommendation: Reading A (FK with NO ACTION).**

**Reading A vs Reading C (defending FK at all):**
`pm_current_focus.bound_workflow_id` is materially different from
`api_usage.workflow_id` in shape. `api_usage.workflow_id` is set
along two paths: (a) caller-supplied free-form annotation (operator
labels, not necessarily task ids); (b) server-derived from the
recursive chain-root walk in commit 1's wrapper (always a real
task_id when present). Path (a) is why no FK was added at 0009 —
caller-supplied labels may not point at task rows. Path (b) does
point at real task_ids but lands as a derived stamp, not an
operator-deliberate claim.

`pm_current_focus.bound_workflow_id` is single-path: PM declares
the focus's workflow binding at `declare_current_focus` time,
asserting "this focus is the operator's intent for workflow X."
PM has the workflow root task in scope (it's the task PM submitted
or was directed to). The deliberate-claim shape benefits from
referential integrity at write — a typo or stale id should fail
loudly at declare-time, not silently land as a dangling reference
in the narration record.

The constraint cost is borne once per declare call (low frequency:
one-per-work-unit-boundary, ~once per 30s loop iteration at most
per commit 7's pacing), with strong typo-catching value. Reading C
trades that integrity for symmetry with `api_usage.workflow_id`,
but the symmetry is false — the two columns have different
populating paths and different operator semantics.

**Reading A vs Reading B (defending NO ACTION over SET NULL):**
The narration record is the durable timeline. Pre-deletion, a
focus row asserts "I was bound to workflow X, and here is what I
produced." Post-SET-NULL, that row asserts "I was bound to NULL"
— the historical claim is silently destroyed. The operator who
later asks "what foci were bound to workflow X?" sees a query
result that's missing rows because the workflow row was deleted
afterward, not because no foci were bound to X.

NO ACTION inverts the cost: task deletion fails when referencing
focus rows exist, forcing the operator to acknowledge the
narration record before destroying its referent. The operational
cost is real (an operator-driven `DELETE FROM tasks WHERE id=...`
fails when narration references it) but the cost is catching a
data-integrity decision the operator should be making consciously,
not absorbing silently.

Phase 1 has no `tools/tasks.py` (M4d) — task deletion is not a
production code path until M4d ships, and even then it's likely
tomb-stoning (status='archived') rather than DELETE. The
NO-ACTION-blocks branch is rarely-triggered in real operation;
when it does trigger, it surfaces a real operator decision rather
than swallowing it.

**Reading A is recommended.** Both alternatives addressed
explicitly: Reading C loses write-time typo-catching at the cost
of symmetry with a column that has different population semantics;
Reading B loses narration-record fidelity at the cost of
deletion-flow ergonomics that aren't load-bearing in Phase 1.

If Reading A creates operational friction post-M4d (operators
deleting tasks legitimately and tripping over focus references):
revisit at M4d's prep checkpoint, with the option to re-migrate to
SET NULL or add a `pm_current_focus.bound_workflow_archived_at`
column to soft-record the deletion event.

### Q3 disposition impact on commit 2 scope

- 0010 migration: `bound_workflow_id TEXT REFERENCES tasks(id)`
  — no explicit `ON DELETE` clause needed (NO ACTION is SQLite's
  default).
- Migration smoke test gains FK-constraint check: INSERT
  pm_current_focus row with bogus `bound_workflow_id` → IntegrityError.
- Test count delta: +1 (FK-constraint sentinel test).

---

## F3.6.C — Q6 re-prep with subprocess-MCP constraint named

**Original Reading B contained a contradiction:** "Python-side
read_current_focus helper is needed at this commit for the writer's
dangling-detection logic (Q9) and for commit 7's loop reuse." The
loop driver at commits 7-8 runs as a Claude Code subprocess and
consumes the daemon via MCP — it cannot import `tools.pm_focus`
directly. Re-prep below resolves the contradiction.

### Q6 (re-prepped) — `get_current_focus` MCP tool: ship at commit 2 or defer to commit 7?

The Python-side `read_current_focus` helper is needed at commit 2
for the declare writer's dangling-detection logic (Q8 Reading A
auto-supersede path) regardless of disposition — the writer is
daemon-internal Python, not subprocess. The question is whether
the MCP wrapper (`get_current_focus`) ships at commit 2 or defers
to commit 7.

**Resolved scope of "read-side post-flag-flips" sequencing:** The
BACKLOG observability roadmap's "read-side ships post-M5-flag-flips"
applies to operator-facing tier-2 surfaces (cohort queries,
dashboards, ad-hoc SQL replacements like `get_audit_log`). The
continuous-loop driver reading its own focus via MCP at commits 7-8
is a daemon-internal-consumer surface — same shape as
`get_window_state` (PM/Engineer reading their own pace state via
MCP, not an operator-facing dashboard). The post-flag-flips
sequencing does not gate `get_current_focus`.

Two coherent paths:

- **Reading A — ship `get_current_focus` MCP tool at commit 2.**
  Three MCP tools at commit 2: `declare_current_focus` (PM-only),
  `complete_current_focus` (PM-only), `get_current_focus` (PM-only
  — see permission discussion below). Read surface co-ships with
  write surface; future-Claude reading commit history sees
  focus-management ship as one unit. Test count delta: +3 (PM
  allowed; empty-state returns None / sentinel; with-active-focus
  returns full row).
- **Reading B — defer `get_current_focus` MCP tool to commit 7
  alongside loop driver.** Two MCP tools at commit 2; Python
  `read_current_focus` helper exists for declare's dangling-detection.
  Third MCP tool ships with its real consumer at commit 7.

**Recommendation: Reading A (ship at commit 2).**

**Reading A vs Reading B (defending co-ship over defer):**

1. **Re-litigation cost under Reading B is real.** Deferring
   `get_current_focus` to commit 7 means commit 7's prep checkpoint
   has to settle four design points that should be settled with
   the table schema:
   - **Output schema:** does `GetCurrentFocusOutput` return the
     full pm_current_focus row, a subset (objective + output_type
     + acceptance_criteria + bound_workflow_id), or wrap it in a
     `current_focus: PmCurrentFocus | None` discriminated shape?
   - **Empty-state contract:** when no active focus exists, does
     the handler return `None` (Optional in Python; null in MCP
     output), raise an error, or return a sentinel object with
     `is_active=false`?
   - **Permission scope:** PM-only (matches declare/complete) or
     PM/Engineer (matches get_window_state precedent for
     mode-aware planning)?
   - **Caller-context:** does the subprocess loop call the same
     read path as a hypothetical operator-facing future tool, or
     is there a separate read API?
   These four design points are co-design with the table schema
   (e.g., output schema reflects the column choices in 0010;
   empty-state contract interacts with Q8's at-most-one invariant).
   Settling them at commit 7 means commit 7's prep re-traces the
   commit 2 table-design conversation. Prep-debt accumulating is
   the F3.5 risk pattern Mike named.

2. **Audit-trail cohesion.** Co-shipping write + read surface in
   one commit gives future-Claude reading commit history a single
   blame-point for "focus management surface lands." Reading B
   spreads it across commits 2 and 7 with a Python-helper-only
   intermediate state at commits 3-6 that has no real consumer.

3. **Test surface completeness.** Commit 2's tests under Reading A
   exercise the full write→read→complete round-trip via the MCP
   wrappers. Reading B's commit 2 tests exercise write paths only;
   the read path's test coverage rides at commit 7 where it's
   harder to isolate from the loop driver's own tests.

4. **The "no consumer yet" argument is weak here.** It applies
   when the consumer is speculative or unbound. Commit 7's loop
   driver is a committed deliverable in this sub-milestone's chain;
   designing for a planned consumer is different from designing
   for a hypothetical future surface. The BACKLOG roadmap's
   "ship when consumer materializes" guidance targets speculative
   surfaces, not ones with planned consumers two commits away.

**Reading A is recommended.** Reading B's surface savings (~3
tests, ~30 LOC of MCP wrapper) don't offset the re-litigation
cost on commit 7.

**Permission scope under Reading A:** PM-only at commit 2.
Engineer's mode-aware planning may eventually need read access
(mirror Q4 disposition pattern of the M5-pm-mode-and-window-state
flow), but Engineer's actual loop logic doesn't yet read PM's
focus — that surfaces (if at all) at M5-flag-flips when Engineer's
own continuous loop spec lands. Promote PM-only → PM/Engineer if
that need surfaces; don't pre-grant.

### Q6 disposition impact on commit 2 scope

- New MCP tool: `_register_pm_focus_tools` registers three tools
  (declare / complete / get) instead of two.
- New Pydantic schema: `GetCurrentFocusOutput` with
  `current_focus: PmCurrentFocus | None` shape (None on empty;
  full row when active).
- New tests: 3 (PM-allowed smoke; empty-state returns None;
  with-active-focus returns full row).
- Test count delta: +3 (vs original Reading B baseline).

---

## F3.6.D — Q4 prose update with fourth audit action explicit

Per Mike's Q4 disposition: ship the four-action shape, not three +
conditional. Fourth action `pm.current_focus.superseded` lands as
durable Q4-A spec.

### Q4 (updated) — Audit-log `action` namespace and shape

Four namespaced actions land at this commit per the
auto-complete-as-superseded disposition (Q8 Reading A). Reading A's
recommendation stands; payload table updated:

- **`pm.current_focus.declared`** — emitted on every successful
  declare (after any auto-supersede write).
  - target=focus_id (the new focus)
  - details={objective, output_type, acceptance_criteria,
    bound_workflow_id, declared_at}
- **`pm.current_focus.completed`** — emitted on every successful
  complete; emitted in the same transaction as
  `pm.current_focus.contract_violation` when applicable.
  - target=focus_id
  - details={focus_id, contract_validated, finding_text (if any),
    completed_at, declared_at_for_join}
- **`pm.current_focus.contract_violation`** — emitted alongside
  `pm.current_focus.completed` when `contract_validated=false`.
  Same transaction.
  - target=focus_id
  - details={focus_id, contract_validated=false, finding_text,
    actual_output_summary}
- **`pm.current_focus.superseded`** — emitted in the declare-when-
  prior-active path (Q8 Reading A); fires before
  `pm.current_focus.declared` in the same transaction. The prior
  focus row's `completed_at` is set to declare-time and
  `contract_finding_text='superseded'` per Q8 Reading A.
  - target=focus_id (the prior focus that was superseded)
  - details={focus_id, superseded_at, superseded_by_focus_id,
    prior_objective_summary}

**Storage cost** of the fourth action: trivial. `audit_log` is a
TEXT-column table with no row-count gate; one extra row per
declare-while-prior-active path is negligible.

**Reading B (minimal details + reconstruction via FK-join) is
explicitly rejected.** Audit log is the durable timeline; full
payloads make it self-contained. Joining through pm_current_focus
post-deletion (if a focus row is ever pruned for retention) loses
the audit detail; full payloads survive table evolution.

### Q4 disposition impact on commit 2 scope

- Fourth audit-action constant in `tools/pm_focus.py`:
  `_PM_FOCUS_AUDIT_ACTIONS = {"declared", "completed",
  "contract_violation", "superseded"}` (or named-constant style
  per project convention).
- New test: superseded action emitted with full payload on
  declare-while-prior-active path. Test count delta: +1.
- Spec amendment to `11-engineer-bridge.md` § Audit log entries
  table includes all four actions.

---

## F3.6.E — F3.4 test-target update (F2.1-shape finding)

The dispositions above push commit 2's test count above the
F2.1 estimate of 14. F2.1's surface-as-finding rule (>20%
divergence on any single commit) triggers a finding for commit 2
disposition state. Net deltas vs original F3.4 baseline:

| Source | Δ tests |
|---|---|
| Original F3.4 baseline | 14 |
| Q3 Reading A (FK constraint sentinel) | +1 |
| Q4-updated (superseded action emitted with full payload) | +1 |
| Q6 Reading A (3 new `get_current_focus` MCP tests) | +3 |
| **Revised total** | **19** |

**19 / 14 = 35.7% over the F2.1 estimate.** Surfaces as F2.1
finding for commit 2: actual test surface differs from
analogous-precedent extrapolation by more than 20%. Finding
disposition (per F2.1's "(a) actual differs from precedent,
acceptable if documented" branch): the divergence is driven by
Q3 + Q6 dispositions that landed AFTER F2.1's per-commit
estimate; the dispositions are scope-correct and the test
count reflects the real surface needed to verify them. Document
in F3.6 (this section) and roll forward; do not re-litigate
F2.1's per-commit estimate retroactively (per F2.1 directive
"future prep checkpoints should not treat per-commit numbers
as budgets the chain has to hit").

**Chain-wide impact:** ~+5 tests vs F2.1's commit-2 estimate.
Chain-wide F2.1 total of ~101 becomes ~106. Within F2.1's
chain-wide order-of-magnitude tolerance; no chain-wide finding
needed.

---

## F3.6.F — what lands at the diff checkpoint

After Mike disposition Q3 + Q6 above:

- 0010 migration drafted with FK NO ACTION constraint per Q3
- `tools/pm_focus.py` drafted with three handlers (declare /
  complete / get) per Q6
- `server.py` `_register_pm_focus_tools` registers three MCP
  tools per Q6
- Audit-action namespace expanded to four actions per Q4 update
- Spec amendments drafted for 06 / 05 / 11 reflecting the above
- Test fixtures + assertions drafted (~19 tests)
- Q5 BACKLOG entry already landed (2026-05-04)

Diff checkpoint surfaces commit-by-commit; commit 2 lands; commit
3 prep opens (`pm_spin_rationale_log` — will share `read_current_X`
helper rowid-DESC pattern as third instance, surfacing the
extraction question per F3.3 watch).

**End of Followup-6.**

---

# Followup-7 — commit 3 prep checkpoint (pm_spin_rationale_log + writer + closed-enum sentinel)

**Date:** 2026-05-04. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 3.
**Prerequisites landed:** commit 1 (workflow_id α-denormalization,
ff47573); commit 2 (pm_current_focus + write/read API, b1ad27a);
.gitignore chore (414728f).

**Migration number pin:** F2.1's revised chain table (line 1103)
already lists `storage/sql/0011_*.sql` for commit 3. Pre-rollback
F2.0 had this slot at 0012; the F2.1 revision (2026-05-03) shifted
to 0011 when 0010/`tasks.task_type` moved to M4d. No further
renumber needed at commit 3 prep open.

**Q3 closed-enum disposition pinned:** F3.6 Q3 dispositioned
rationale_tag closed enum (Reading A: queued_work / mike_presence /
heartbeat_cheap / forgot_to_shutdown). Not re-surfaced as a new
question at this prep; spec amendment uses that disposition
directly.

---

## F4.1 — touch points

Per F2.1 row 3: `storage/sql/0011_*.sql`, `tools/pm_spin.py` (new).
Resolved file-by-file (extraction-conditional rows marked):

| File | Status | Touch shape |
|---|---|---|
| `src/meridian_orchestrator/storage/sql/0011_pm_spin_rationale_log.sql` | NEW | Table DDL with `id / iteration_timestamp / decision (closed enum CHECK) / rationale_tag (closed enum CHECK) / rationale_free_text` + single index on iteration_timestamp (mirrors commit 2's Q5 Reading B disposition). |
| `src/meridian_orchestrator/tools/pm_spin.py` | NEW | Pydantic schemas (`RecordSpinRationaleInput / Output`); `Decision` Literal; `RationaleTag` Literal; `record_spin_rationale` handler. Read-helper conditional on Q4 disposition below. |
| `src/meridian_orchestrator/server.py` | EDIT | `_register_pm_spin_tools` helper conditional on Q5 (MCP wrapper at commit 3 vs deferred). |
| `docs/kickoff/06-mcp-tools-phase-one.md` | EDIT | Tool entry + permission table row, conditional on Q5. |
| `docs/kickoff/05-data-model.md` | EDIT | New `pm_spin_rationale_log` table entry + migration table row for 0011. |
| `docs/kickoff/11-engineer-bridge.md` | UNCHANGED IF Q6 Reading A | If Q6 Reading B (audit_log integration), audit-log-entries table extends with `pm.spin_rationale.recorded`. |
| `tests/unit/storage/test_migrations.py` | EDIT | EXPECTED_VERSIONS update; columns/indexes/CHECK assertions for 0011 (mirror commit 2's pattern, ~3-4 new tests). |
| `tests/integration/test_daemon_lifecycle.py` | EDIT | Lifecycle version-set update for 0011. |
| `tests/unit/tools/test_pm_spin.py` | NEW | Direct-handler tests + closed-enum sentinels. |
| `tests/integration/test_pm_spin_mcp_wrapper.py` | NEW IF Q5 Reading A | MCP wrapper tests parallel to commit 2's pattern. |

**Conditional touchpoints (extraction question Q3):**

| File | Status if extraction lands at commit 3 |
|---|---|
| `src/meridian_orchestrator/storage/most_recent.py` (or alternative location per Q3-sub-question) | NEW shared helper module |
| `src/meridian_orchestrator/tools/pm_mode.py` | EDIT — refactor `read_current_pm_mode` to use shared utility |
| `src/meridian_orchestrator/tools/pm_focus.py` | EDIT — refactor `read_current_focus` to use shared utility |
| `tests/unit/storage/test_most_recent.py` | NEW — direct unit tests for the shared utility |

---

## F4.2 — open questions (Q1-Q7)

### Q1 — Spin-rationale emission cadence + dedup

What triggers a `record_spin_rationale` call, and is dedup applied?

- **Reading A — per-tick, no dedup.** Loop emits one row per planning iteration (~30s cadence per F2 Section 2). Volume bounded at ~2880 rows/day; cohort aggregation happens at query time. Simplest writer: single INSERT per call, no read-back. **Does not introduce a third `read_current_X` helper instance.**
- **Reading B — per-rationale-change only.** Subprocess tracks last-emitted rationale in memory; only writes when (decision, rationale_tag) tuple changes from prior emission. Lower volume; subprocess-side state required; loses fidelity on duration ("kept_alive for 47 ticks" requires inferring from gaps). **Does not introduce a daemon-side read helper.**
- **Reading C — per-tick with server-side dedup.** Writer reads last row's (decision, rationale_tag); skips INSERT if same tuple. Moderate volume; daemon-side state via the read-back; **introduces the third `read_current_X` helper instance, tripping the threshold-of-three for Q3 extraction.**

**Recommendation: Reading A (per-tick, no dedup).** The narration log is durable timeline of "what PM was doing every tick"; coalescing loses temporal fidelity. ~2880 rows/day is bounded; aggregate at query time via GROUP BY rationale_tag with COUNT and time-window roll-ups (mirrors api_usage's append-only + query-time-aggregation shape per `project_subscription_first_architecture`).

**Disposition impact on Q3:** Reading A means commit 3 does NOT introduce a third `read_current_X` instance. The threshold-of-three claim in Mike's framing depends on Q1 Reading C; surface this at the bottom of the prep as anticipated pushback.

**[DISPOSITIONED 2026-05-04: Reading A accepted.]** Per-tick no dedup gives faithful timeline; dedup hides operator-relevant oscillation signal. **Carry-forward note for commit 7 prep:** pm_spin row volume scales with the loop's tick cadence, which commit 7 actually decides (F2 Section 2 framed "30s? — surface for prep"; commit 7 is where it lands). 30s cadence = ~2880 rows/day; if commit 7 lands materially different cadence (e.g., 5s for tighter responsiveness or 5min for cost-conscious operation), the volume framing in Q6 below revisits and the BACKLOG retention/coalescing question reopens. Pin this dependency for commit 7 prep checkpoint — cadence-volume interaction needs explicit naming when tick cadence is decided.

### Q2 — `decision` column closed-enum CHECK

The `decision` column carries values `kept_alive` or `shutdown_recommended` per F2 Section 2 schema sketch. Should it be a closed-enum CHECK like rationale_tag (Q3 disposition Reading A)?

- **Reading A — closed-enum CHECK + sentinel test.** `Literal["kept_alive", "shutdown_recommended"]` + CHECK + sentinel. Mirrors output_type / mode / rationale_tag patterns.
- **Reading B — free TEXT, no CHECK.** Lower discipline; admits future-decision-types without migration; mirrors `tasks.status` historical lack-of-CHECK (later added at 0008).

**Recommendation: Reading A.** Closed-enum sentinel pattern continuing per F3.3 watch. Two-value enum is small enough that forced-fit risk is negligible; future expansion (e.g., `kept_alive_partial`, `shutdown_pending_handoff`) lands via a future migration mirroring `0008_dispatches_status_check.sql`. Sentinel test confirms the Pydantic Literal and CHECK list stay in sync.

### Q3 — `read_current_X` helper extraction (threshold-of-three)

**Status of pattern at commit 3 open:**

1. `tools/pm_mode.py:read_current_pm_mode` (M5-pm-mode-and-window-state commit 6) — `SELECT mode FROM pm_mode_log ORDER BY rowid DESC LIMIT 1`. Returns `str | None`.
2. `tools/pm_focus.py:read_current_focus` (M5-continuous-pm-driver commit 2, b1ad27a) — `SELECT (...columns...) FROM pm_current_focus WHERE completed_at IS NULL ORDER BY rowid DESC LIMIT 1`. Returns `PmCurrentFocus | None`.

**Is there a third instance at commit 3?** Conditional on Q1.

**Sub-question (regardless of Q1 disposition): is "rowid-DESC LIMIT 1 with optional WHERE" the right abstraction unit?** The two existing instances differ on:
- Return type (single string vs full Pydantic model)
- WHERE clause (none vs `completed_at IS NULL`)
- Result-row parsing (single column vs multi-column with bool coercion)

Common only at the SQL-shape level (rowid-DESC LIMIT 1 + None-on-empty). The behavior — what columns to fetch, what to return — is per-call-site distinct. Extraction options:

- **Reading A — Same-commit extraction at commit 3 (assuming Q1-C lands).** Shared helper module location: `storage/most_recent.py` (next to `datetime_utils.py` precedent). Signature: a generic `fetch_most_recent_row(storage, table, columns, where=None) -> dict | None` returning the raw row dict; per-call-site code parses into typed return values. Refactor: `read_current_pm_mode` and `read_current_focus` rewritten to call the helper. Test: shared utility unit tests + verify pm_mode + pm_focus tests still pass.
- **Reading B — Defer extraction; let third instance materialize organically.** If Q1-A or Q1-B lands, no third helper at commit 3; threshold not really tripped. Continue watch-for-pattern entry. Most likely third instance: commits 7-8 loop driver reading own most-recent dispatch outcome for diagnostic logic.
- **Reading C — Pre-emptive extraction NOW at commit 3, regardless of Q1 disposition.** Acknowledge anticipated third instance within next 5 commits; avoid the multi-commit refactor by extracting at the second instance with the third in scope. Risks premature abstraction on a pattern whose shape may evolve.
- **Reading D — Split commit 3 into 3a (extraction + refactor pm_mode + pm_focus) and 3b (pm_spin_rationale_log table + writer). Only viable if Q1-C; otherwise 3a has no consumer and the F2.2 verification-surface argument applies (extracting without a consumer = no integration test for the contract).
- **Reading E — Pattern doesn't justify extraction.** Per the sub-question above: behavior diverges per call-site enough that a generic helper would be a thin SQL-string-builder wrapper without semantic value. Convert F3.3 watch-for-pattern entry to RESOLVED-as-not-extracted with rationale; future call sites continue to inline the rowid-DESC pattern with their own typed parsing. Rule-of-three is a heuristic, not a mandate; some patterns warrant extraction at 3 instances, others don't.

**Recommendation: contingent.**

- If Q1 disposition lands as Reading A or Reading B (no third instance at commit 3): Reading B for Q3 (defer extraction to organic third instance) OR Reading E (resolve the watch entry as not-extractable).
- If Q1 lands as Reading C (writer reads back for dedup): Reading A for Q3 (same-commit extraction) — the dedup-read introduces a real third instance with non-trivial integration test surface.

**My lean across both Q1 dispositions: Reading E (pattern doesn't justify extraction).** The two existing helpers are thin (5-15 lines each); the abstraction unit is per-call-site result parsing rather than the SQL shape. A generic `fetch_most_recent_row` helper would be a SQL-string-builder that adds attack surface (caller-supplied table/column names) without removing meaningful duplication. Convert F3.3's watch-for-pattern to RESOLVED-as-not-extracted and explicitly document that the rule-of-three is a trigger-to-evaluate, not a mandate-to-extract. UTC-coercion (datetime_utils precedent) was extracted at three because the helper was a pure function with shared semantic load; rowid-DESC SQL is a query template, not a shared semantic unit.

This is the bidirectional-contradiction-discipline pattern: surfacing the question Mike asked produces a pushback against the framing, not just a disposition between Mike's options.

**[DISPOSITIONED 2026-05-04: Q3 dissolves per F4.5.1 reframe.]** Q1-A + Q4-B together mean commit 3 is write-only; no third `read_current_X` instance materializes. Threshold-of-three watch resets to "anticipated commits 7-8" (see F3.3 watch-entry update). Reading E's contrarian framing — "rule-of-three is trigger-to-evaluate, not mandate-to-extract" — captured separately in F2.5 closeout-staging notes as durable framing for future thresholds-of-three.

### Q4 — Dedicated read helper for pm_spin_rationale_log?

Independent of Q1's dedup question, does commit 3 ship a `read_most_recent_spin_rationale` Python helper for daemon-side consumers (loop driver at commits 7-8)?

- **Reading A — Yes, ship a Python helper at commit 3.** Mirrors the prep-debt argument from F3.6.C Q6: defer-to-commit-7 forces re-litigation of output schema, empty-state contract, and caller-context. Even if commit 3's writer doesn't need the helper internally, the loop driver at commit 7-8 likely does (for diagnostic / liveness reporting). Ship at commit 3 with table schema; wire MCP wrapper only if Q5 Reading A.
- **Reading B — No, defer until a real consumer materializes.** pm_spin_rationale_log is append-only; readers aggregate rather than fetch most-recent. No daemon-side read helper at commit 3.

**Recommendation: Reading B.** The narration-log shape doesn't have an obvious "most recent" consumer in the way pm_mode_log does (read_current_pm_mode powers Mike-presence detection in get_window_state — a real load-bearing read). Spin-rationale's consumers are aggregations (counts, time-window roll-ups for fitness tier 2). If commit 7-8 surfaces a real read-back use case, a helper lands then; speculative shipping risks designing the wrong shape.

This shifts away from the F3.6.C Q6 co-design argument because the use case shape is different — pm_current_focus has at-most-one-active read semantics matching get; pm_spin_rationale_log has "what's the most recent N rows with what aggregation" semantics matching future query tools.

### Q5 — MCP wrapper surface for record_spin_rationale

PM-continuous is a subprocess and writes via MCP. Is the wrapper at commit 3 or deferred?

- **Reading A — `record_spin_rationale` MCP tool ships at commit 3.** PM-only. Same shape as commit 2's declare/complete shipping with the table. Subprocess consumer at commits 7-8 is committed; co-shipping avoids prep-debt.
- **Reading B — Defer MCP wrapper to commit 7 alongside loop driver.** Writer Python API exists; daemon-side direct callers can use it; MCP wrapper rides with consumer.

**Recommendation: Reading A.** The Q4 disposition (no read helper at commit 3) is asymmetric with Q5 — write is the load-bearing surface for the loop driver, read is speculative. Co-ship the write surface; defer the read.

### Q6 — audit_log integration on record_spin_rationale

Each spin-rationale write also emits an audit_log row?

- **Reading A — No audit_log integration.** pm_spin_rationale_log IS the durable timeline for spin-rationale events. Audit_log row would be redundant with the table row. Volume argument: ~2880 audit rows/day from spin-rationale alone (vs current audit volume of ~tens-to-hundreds/day) is significant noise.
- **Reading B — Audit each spin-rationale write.** Mirrors `pm_mode.transition` pattern (set_pm_mode writes audit even on no-op same-mode call). Operator-visible "PM is calling record_spin_rationale every tick" trail.

**Recommendation: Reading A.** Volume is the deciding argument; a 30x increase in audit_log row count for redundant content is operational noise, not load-bearing audit. record_api_call (which has similar high-frequency cadence) DOES audit per call — so this isn't "high-frequency tools never audit." But record_api_call's audit row carries cross-table context (dispatch_id, agent_id, model, cost) that the api_usage row alone doesn't capture. pm_spin_rationale_log row IS self-contained; audit row would mirror it byte-for-byte on the durable fields. Skip.

**[DISPOSITIONED 2026-05-04: Reading A accepted with reasoning revision per F4.5.3.]** Load-bearing reasoning is **not** volume — that's tiebreaker observation, not rejection. Real reason: pm_spin_rationale_log IS itself the durable record of spin-rationale decisions. Cross-recording into audit_log creates two-write-paths-for-same-event with divergence risk between table and audit, plus operator confusion about canonical source. Audit_log scope: cross-cutting events needing cross-domain query. pm_spin events query target is pm_spin_rationale_log directly; no cross-domain join required. No audit_log integration needed. Volume argument retained as second-order observation but not the basis for rejection.

### Q7 — Atomicity envelope

- **Reading A — single INSERT, no transaction wrap.** SQLite single-statement INSERTs are atomic by default. If Q1-A + Q4-B + Q6-A, no multi-statement sequence to wrap.
- **Reading B — explicit transaction wrap.** Defensive consistency with project pattern.

**Recommendation: Reading A.** Single-statement atomicity is sufficient when there's no multi-write sequence. Mirror api_usage when no companion writes fire. (record_api_call DOES wrap because it has the conditional dispatch UPDATE + audit_log write; spin-rationale has neither.)

If Q6 Reading B lands (audit_log added), upgrade to Reading B for atomicity.

---

## F4.3 — watch-for-pattern check

| Pattern | Status pre-commit | Commit 3 instance | Disposition |
|---|---|---|---|
| Closed-enum CHECK + sentinel test | 4 (mode, triggered_by, dispatches.status, output_type) | +2: `decision` + `rationale_tag`. | Pattern durable; no extraction warranted. |
| `read_current_X` rowid-DESC helper | 2 | Conditional on Q1; if zero new instance, threshold not tripped at commit 3. Q3 surfaces re-evaluation: pattern may not justify extraction (Reading E) regardless. |
| Audit action `pm.<surface>.<verb>` namespace | 5 (`pm_mode.transition` + four `pm.current_focus.*`) | +0 if Q6-A; +1 (`pm.spin_rationale.recorded`) if Q6-B. | Pattern continuing; no extraction surface. |
| `bridge/` → `tools/` cross-layer imports | 2 | 0 at commit 3 (pm_spin.py is `tools/`-only). Commits 7-8 loop driver may add. | No change. |
| Production-code-surface-for-testability | 2 pre-chain | 0 at commit 3 (writer is straightforward; no test seam needed). | No change. |
| Migration count | 2 added at this sub-milestone (0010 commit 2; commit 1 was wrapper-only) | +1: 0011. Total 3 at sub-milestone close (matches F2.4 revised count). | No change. |
| **F2.1 surface-as-finding rule** | 2 invocations at commit 2 (prep + draft) | Likely +1 at commit 3 draft if test count exceeds F2.1's +8 estimate. Per the closeout pattern observation (F2.5), expected systematic underestimate driven by precedent-mirror tests + pushback-driven additions. | No re-prep; document. |

---

## F4.4 — test-target estimate

F2.1 estimate: +8. Working breakdown under recommended dispositions
(Q1-A, Q2-A, Q3-E, Q4-B, Q5-A, Q6-A, Q7-A):

| Layer | Count |
|---|---|
| Migration smoke tests (columns + index) | 1 |
| Decision CHECK constraint sentinel | 1 |
| Rationale_tag CHECK constraint sentinel | 1 |
| iteration_timestamp default-populates | 1 |
| Writer happy path (basic; with free_text) | 2 |
| MCP wrapper PM-allowed | 1 |
| MCP wrapper non-PM-rejected (parametrized × 3) | 1 unique / 3 collected |
| **Total under recommendations** | **8 unique / 10 collected** |

**At F2.1's +8 baseline.** Per the closeout § Discipline-pattern
observation captured at commit 2 (F3.6.E precedent): F2.1 estimates
run systematically low by ~25% for two reasons; expect actual draft
count to exceed this estimate, surface as F2.1 finding third
invocation if so, disposition as scope-correct + absorbed-pattern
continuation.

**Range under disposition variance:**

- Q3 Reading A or D (extraction lands at commit 3): +3-4 tests for shared utility unit tests + verification refactor. Total ~12 unique.
- Q4 Reading A (read helper at commit 3): +2 tests. Total ~10 unique.
- Q6 Reading B (audit_log integration): +1 test (audit row sentinel). Total ~9 unique.

Worst case (all dispositions flip toward more surface): ~14 unique. 14 / 8 = 75% over F2.1 estimate.

---

## F4.5 — anticipated pushbacks

Three weak points in this prep that Mike's review may surface:

1. **Q1 Reading A vs Mike's "third instance" framing.** Mike's commit-3-prep-open instruction stated "this is the third instance of the read_current_X rowid-DESC helper pattern" as fact. My Q1 Reading A recommendation (per-tick, no dedup) means commit 3 introduces NO new read helper, which contradicts Mike's framing. Either (a) Mike was anticipating a specific read-back use case I should surface (most likely: loop driver at commits 7-8 reading own most-recent for hysteresis or diagnostic), in which case the Q1 disposition stands but the Q3 question becomes "extract now in anticipation vs wait for organic third"; or (b) Mike has a different read-back use case I'm missing, which Q1 needs to surface as a fourth Reading. Surface this as the load-bearing pushback — the threshold-of-three trigger depends on whether a real read-back materializes at commit 3 or is anticipated.

2. **Q3 Reading E (pattern doesn't justify extraction) is contrarian to the rule-of-three precedent.** The UTC-coercion extraction at M5-subscription-routing commit 2 was held up as a model in F3.3 watch-for-pattern entries. Reading E argues this case differs (per-call-site result parsing differs; no shared semantic unit). Mike may push back: either "rule-of-three is a mandate, extract regardless" or "you missed a real shared abstraction — the empty-handling and rowid-DESC convention IS shared semantic load." If pushback lands as the latter, the right abstraction is probably a `MostRecentRowReader[T]` typed helper rather than a generic SQL builder — surface as a sub-reading if Reading E doesn't survive.

3. **Q6 Reading A (no audit_log) volume argument may be too facile.** I claimed 2880 audit rows/day from spin-rationale would be "operational noise." That's an unsubstantiated volume comparison without Phase 1 baseline data. The honest framing is: audit_log volume in Phase 1 is unknown; 2880 rows/day from one source is significant relative to what's currently shipped (dispatch lifecycle audit ~10s of rows per dispatch; ~hundreds/day at heavy use). But if Mike values the audit-trail consistency more than volume, Reading B is defensible. The volume argument is a tiebreaker, not a load-bearing rejection. Surface as second-order finding.

---

## F4.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q7 + the three anticipated-pushback items:

- 0011 migration drafted with Q2 + Q3-disposition CHECK constraints
- `tools/pm_spin.py` drafted with Q1 + Q4 + Q7-disposition writer shape
- `server.py` `_register_pm_spin_tools` if Q5-A
- Spec amendments drafted for 06 + 05 (and 11 if Q6-B)
- Test fixtures + assertions drafted (~8-14 tests depending on dispositions)
- F3.3 watch-for-pattern entry updated per Q3 disposition (continued / RESOLVED-extracted / RESOLVED-not-extracted)

Diff checkpoint surfaces commit-by-commit; commit 3 lands; commit
4 prep opens (`audit_sample_log` — third planned migration at this
sub-milestone; sampling-percentage config + async sampler scaffold).

**End of Followup-7.**

---

# Followup-8 — commit 4 prep checkpoint (audit_sample_log + sampling-percentage config + async sampler scaffold)

**Date:** 2026-05-04. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 4.
**Prerequisites landed:** commits 1-3 (workflow_id α, ff47573;
pm_current_focus, b1ad27a; pm_spin_rationale_log, 433cef5);
.gitignore chore (414728f).

**F2.2 B1 disposition pinned:** loop-driven trigger for sampling
("loop fires sampleable event N happened hook at iteration boundary;
sampler picks per sampling-percentage config asynchronously"). Pure
write-only seam from loop side. Not re-surfaced as a new question;
spec uses that disposition.

---

## F5.1 — touch points

Per F2.1 row 4: `storage/sql/0012_*.sql`, `audit/sampler.py` (new),
`config.py`. Resolved file-by-file:

| File | Status | Touch shape |
|---|---|---|
| `src/meridian_orchestrator/storage/sql/0012_audit_sample_log.sql` | NEW | Table DDL with `id / sampled_at / source_event_kind / source_event_id / re_eval_state (CHECK) / re_eval_finding / re_eval_completed_at` + index on `(re_eval_state, sampled_at)` for the future async re-evaluator's pending-row scan. Q1/Q2/Q5/Q6 dispositions shape the column constraints. |
| `src/meridian_orchestrator/audit/sampler.py` | NEW | `Sampler` class (constructor takes `sampling_percentage`); `maybe_sample(storage, event_kind, event_id) -> str \| None` writer; module-level `_sample_decision(percentage)` pure function. Q9 disposition shapes the algorithm; Q11 disposition shapes the scaffold scope. |
| `src/meridian_orchestrator/config.py` | EDIT | Add `audit_sample_percentage: float` field. Default per Q10 disposition. |
| `src/meridian_orchestrator/main.py` | EDIT | Lifespan wiring: construct `Sampler` instance from `Settings.audit_sample_percentage` at daemon startup; provide closure-pattern accessor for the wrapper layer (mirrors `get_oauth_usage_snapshot` pattern from `OAuthUsagePoller`). |
| `src/meridian_orchestrator/server.py` | EDIT | `_register_audit_sample_tools` helper conditional on Q3 (explicit hook MCP tool vs embedded). |
| `docs/kickoff/05-data-model.md` | EDIT | New `audit_sample_log` table entry + migration table row for 0012. |
| `docs/kickoff/06-mcp-tools-phase-one.md` | EDIT | Tool entry + permission table row, conditional on Q3 Reading A. |
| `docs/kickoff/11-engineer-bridge.md` | UNCHANGED IF Q7 Reading A | If Q7 Reading B (audit_log integration on sample writes), audit-log entries table extends. |
| `BACKLOG.md` | EDIT | Cohort/secondary indexes deferral (mirror commit 2's Q5 BACKLOG entry pattern); tier-2 read-side surfaces still post-M5-flag-flips per existing BACKLOG observability roadmap. |
| `tests/unit/storage/test_migrations.py` | EDIT | EXPECTED_VERSIONS update; columns/indexes/CHECK assertions for 0012 (mirror commits 2-3 patterns). |
| `tests/integration/test_daemon_lifecycle.py` | EDIT | Lifecycle version-set update for 0012. |
| `tests/unit/audit/test_sampler.py` | NEW | Direct-handler tests for `Sampler` + closed-enum sentinel for re_eval_state. |
| `tests/integration/test_audit_sample_mcp_wrapper.py` | NEW IF Q3 Reading A | MCP wrapper tests parallel to commit 3's pattern. |

---

## F5.2 — open questions (Q1-Q9)

### Q1 — `source_event_kind` taxonomy: closed enum vs open TEXT

`source_event_kind` classifies what kind of event the sampler is
referencing. F2 Section 2 sketch: "current_focus_completion /
dispatch_completion / etc." — the "/etc." signals open-ended.
Phase 1 candidate kinds (events that exist in the system today):
`pm.current_focus.completed`, `pm.current_focus.contract_violation`,
`pm.current_focus.superseded`, `pm.current_focus.declared`,
`pm_spin_rationale.recorded`, `dispatch.complete_called`,
`dispatch.subprocess_terminated`, etc.

- **Reading A — closed enum CHECK + sentinel test.** Lock the kinds
  list at commit 4 with the events that exist today (~6-8 values).
  Future event types require migration. Forced-fit risk: future
  event types from M5-flag-flips / M6 dashboard work need migration
  plumbing; some kinds (`dispatch.timeout`, `pm.current_focus.superseded`)
  may or may not warrant sampling and locking the list now decides
  pre-emptively.
- **Reading B — open TEXT, no CHECK.** Free-form string. Loop driver
  passes whatever string identifies the event type. Lower discipline;
  admits typo'd values silently into the table.
- **Reading C — open TEXT, but document the canonical kinds list in
  spec (`05-data-model.md`).** Convention-enforced rather than
  schema-enforced. Audit-side sanity-check tooling could later flag
  unknown kinds without blocking writes.

**Recommendation: Reading C.** Reading A locks the kinds taxonomy
prematurely — Phase 1 hasn't yet observed which events PM and
operators actually find sampling-worthy. The closed-enum-sentinel
pattern's value comes from cohort-coherence (each value is
operator-meaningful and dispatch-affecting); audit_sample_log's
event_kind is descriptive metadata, not dispatch-affecting routing.
Reading B's typo risk is real but small at low write volume
(sampling fraction × event volume → per-day samples in the dozens
or hundreds); a typo is recoverable by data-fix, not load-bearing
breakage. Reading C splits the difference: discipline via spec doc,
flexibility via schema. Promote to closed-enum at M5-flag-flips
or post-tier-2-data when the taxonomy is empirically grounded.

This is a deliberate departure from the closed-enum-sentinel pattern
established at 6 instances. Surface as anticipated pushback in F5.5
since reviewing-Claude may push back on rule-of-six erosion.

### Q2 — `source_event_id` storage: FK vs polymorphic free TEXT

The event might live in `audit_log` (most events), `pm_current_focus`
(focus rows), `pm_spin_rationale_log` (per-tick decisions),
`dispatches` (lifecycle). `source_event_id` references one row but
the target table varies by event_kind.

- **Reading A — single polymorphic TEXT column, no FK.** Loose
  coupling; caller-supplied id; consumer interprets per
  source_event_kind. Multi-table referential integrity not enforced
  at write time. Schema cost: minimal. Operational cost: dangling
  references possible if the source row is deleted.
- **Reading B — separate per-kind FK columns
  (`audit_log_id NULLABLE`, `pm_current_focus_id NULLABLE`, etc.).**
  Strict FK on each. Cumbersome schema; future event-kind additions
  require migration to add columns. Six nullable FKs is a wide
  table.
- **Reading C — single FK to `audit_log(id)`.** Force all sampleable
  events to flow through `audit_log` first; sample's
  source_event_id always points at an audit_log row. Constrains
  the universe of sampleable events to those already audit-logged.
  pm_spin_rationale_log rows are NOT audit-logged per Q6/F4.5.3;
  Reading C would either block them from sampling or require their
  audit-logging to be re-litigated (breaking commit 3's discipline).

**Recommendation: Reading A.** Polymorphic TEXT is the standard
shape for event-classifier-plus-event-identifier patterns when the
event universe is heterogeneous. Reading B's per-kind columns scale
poorly. Reading C's funnel-through-audit-log breaks commit 3's
no-audit-integration disposition for pm_spin events (and would force
re-litigating that disposition just to satisfy schema strictness on
a column where strictness isn't load-bearing). Document the
conventional id types per kind in `05-data-model.md` (e.g.,
"event_kind=pm.current_focus.completed → event_id is the
audit_log.id row whose action='pm.current_focus.completed';
event_kind=pm_spin_rationale.recorded → event_id is the
pm_spin_rationale_log.id row").

### Q3 — Sampler trigger interface: explicit hook MCP tool vs embedded in existing wrappers

F2.2 B1 says "loop fires hook at iteration boundary." Implementation
question: how does the loop fire the hook, and where does the hook
land?

- **Reading A — explicit MCP tool `record_sampleable_event(event_kind, event_id)`.** Loop driver subprocess calls this tool after declare/complete/etc. Sampler picks per config; writes audit_sample_log row if selected. Decoupled: sampling logic in `audit/sampler.py`, not in event-emission handlers. Loop has full control over what's sampleable (excludes some kinds, e.g., per-tick spin_rationale).
- **Reading B — embedded in existing event-emission handlers.** `complete_current_focus` calls `sampler.maybe_sample(...)` at the end of its own handler; same for declare, dispatch.complete_called paths, etc. Loop doesn't make extra MCP calls. Subprocess sees no new tool; sampling is automatic from server side. Couples sampling logic into every state-changing handler.
- **Reading C — hybrid: embed in some wrappers (pm.current_focus.* writes); explicit tool for others (pm_spin_rationale.recorded, dispatch lifecycle).** Pick per event-kind. Highest control; most code paths.

**Recommendation: Reading A (explicit hook).** Three reasons:
1. **F2.2 B1 framing is "loop fires hook"** — keeps sampling
   decoupled from event-emission. Reading B couples sampling into
   every state-changing handler, increasing per-handler cognitive
   load and creating implicit cross-cuts that future-Claude
   reading any handler might miss.
2. **Loop has selective control** — pm_spin_rationale at
   high-cadence (~2880/day) probably shouldn't be sampled at all
   (or at much lower percentage); pm.current_focus.completed at
   low-cadence is probably worth high-percentage sampling. Loop
   knows which events are sampling-relevant; embedded handler
   doesn't.
3. **Symmetric with the audit-log non-integration pattern from
   commit 3** — pm_spin_rationale_log is its own durable record,
   not cross-cutting; audit_sample_log is its own selection
   record, not embedded into event emission. Same architectural
   shape: dedicated tables for dedicated concerns; cross-cutting
   logic in dedicated modules (sampler.py).

Reading B's "subprocess saves an MCP call" is small (the loop is
already making 5-15 MCP calls per iteration). Reading C's
cherry-picked hybrid is the worst of both worlds — neither
decoupled nor uniform.

### Q4 — `audit_sample_percentage` config shape

Single global percentage or per-event-kind?

- **Reading A — single global float `audit_sample_percentage`.** Settings field; one number controls all sampling. Simple. Per-kind needs multi-key config.
- **Reading B — dict-shaped per-event-kind config.** Settings.audit_sample_percentages: dict[str, float] = {} — operator overrides specific kinds; default for unconfigured kinds.
- **Reading C — single global default + future per-kind override.** Ship Reading A at commit 4; layer per-kind override in a future commit if real workload shows the need.

**Recommendation: Reading C.** Reading A is the right Phase 1
shape; pre-emptive per-kind config is YAGNI-territory. Operator
needs operational data on what percentage works before deciding
whether per-kind tuning matters. Reading B ships flexibility
without empirical demand. Document the future expansion path in
the Settings field's docstring so future-Claude reading the field
sees the deferred-by-design shape.

### Q5 — `re_eval_state` closed-enum CHECK

Three states per F2 Section 2: `pending` / `in_progress` /
`complete`.

- **Reading A — closed enum CHECK + sentinel test.** Mirrors
  decision / rationale_tag / output_type / mode patterns. 8th
  closed-enum sentinel in the codebase.
- **Reading B — open TEXT, no CHECK.** State machine values land
  via Python types only.

**Recommendation: Reading A.** State-machine columns benefit from
schema-enforced enum constraint — defense-in-depth catches
manual-SQL or future-external-integration writes. Pattern is durable
through 8+ instances; no concerns about over-extraction (each
sentinel is per-table-per-column, not a shared utility).

Anticipated pushback: 8 closed-enum sentinels means 8 distinct
migrations should the enums need expansion. Pattern discipline cost
is real but the alternative (no schema enforcement) is worse.

### Q6 — Audit-log integration on sample writes

Each `Sampler.maybe_sample` write to `audit_sample_log` also writes
audit_log?

- **Reading A — No audit_log integration.** Mirrors commit 3 Q6
  Reading A + F4.5.3 reasoning: audit_sample_log IS itself the
  durable record of what was sampled; cross-recording into
  audit_log creates two-write-paths-for-same-event with divergence
  risk and operator confusion. audit_log scope: cross-cutting
  events needing cross-domain query.
- **Reading B — Audit each sample write.** One audit row per
  sampled event with action='audit_sample.selected'. Operator
  trail: "what samples were picked, in what order."

**Recommendation: Reading A.** F4.5.3 reasoning applies directly
— audit_sample_log IS the durable record. Cross-recording is
redundant. Sentinel test pins the contract (mirrors commit 3's
`test_record_spin_rationale_does_not_write_audit_log`). Reading B's
"operator trail" argument is satisfied by querying
audit_sample_log directly; ordering is preserved by `sampled_at`.

### Q7 — Atomicity envelope on `Sampler.maybe_sample`

- **Reading A — single INSERT, no transaction wrap.** SQLite
  single-statement INSERT is atomic. With no companion writes (Q6-A
  no audit), no multi-statement sequence to wrap. Mirrors commit 3
  Q7 disposition.
- **Reading B — explicit transaction wrap.** Defensive consistency
  with project pattern.

**Recommendation: Reading A.** Conditional on Q6-A; both held.

### Q8 — Sampling algorithm: coin flip vs deterministic-stratified

Per-event sampling at percentage P%.

- **Reading A — coin flip (`random.random() < P`).** Simple;
  unbiased over many events; standard sampling shape. Phase 1
  default.
- **Reading B — deterministic-stratified (every Nth event,
  N = 1/P).** Predictable; minimum-rate guarantees ("at least 1
  sample per N events"); better small-sample-size stats. Requires
  per-kind counter state (in-memory or column on
  audit_sample_log).
- **Reading C — reservoir sampling** (uniformly select K samples
  from a stream of unknown length). Better statistical guarantees
  for fixed-K-target sampling but doesn't match "percentage of
  events" framing.

**Recommendation: Reading A.** Phase 1 sampling target is
operator-friendly framing ("sample 5% of events"); coin flip
satisfies that with minimal code. Reading B's predictability
benefit is small at Phase 1 volumes; Reading C's reservoir shape
is for fixed-target sampling, not the "percentage of events" shape.
Promote to deterministic-stratified at M5-flag-flips if operator
ad-hoc analysis shows variance is hiding important signals.

### Q9 — Default `audit_sample_percentage` value

What's the default when the operator hasn't configured the field?

- **Reading A — `0.0` (sampling disabled by default).** Sampler
  scaffold ships but writes nothing until operator opts in.
  Conservative; no surprise audit_sample_log row growth.
- **Reading B — `0.05` (5%) or some non-zero default.** Sampling
  active immediately; operator sees sampled rows from day 1
  without explicit config. May surprise operator with
  audit_sample_log growth they didn't ask for.
- **Reading C — `None` / unset → sampler scaffolding doesn't
  construct the Sampler instance at all; record_sampleable_event
  hook becomes a no-op.** Most conservative; explicit-opt-in
  shape; aligns with Q9 commit-2 "ship-as-disabled-by-default"
  flag conventions (`enable_engineer_supervisor=False`,
  `enable_pm_supervisor=False`).

**Recommendation: Reading A.** Default of `0.0` is opt-in
(operator must set non-zero to enable) with the same shape as
existing flags. Reading C's None-shape adds branching at every
hook call site (`if sampler is None: skip`); Reading A's `0.0`
makes the coin flip always return False, no branching needed.
Reading B's auto-enable ships behavior the operator didn't ask
for.

### Q10 — Scope of "async sampler scaffold" at commit 4

F2.1 row 4 framing: "table + sampling-percentage config + async
sampler scaffold." What's "scaffold" exactly?

- **Reading A — full sampler shape.** Table + config + Sampler
  class + maybe_sample writer + MCP wrapper. The async
  re-evaluator (the actual handler that processes
  pending → in_progress → complete) is OUT of scope (deferred to
  later commit, likely commits 7-8 alongside loop driver).
- **Reading B — full sampler + minimal re-evaluator stub.**
  Reading A + a stub re-evaluator that runs once per lifespan and
  marks pending rows as complete with re_eval_finding="stub
  scaffold; replace with real re-evaluation". Scaffold-as-runnable
  end-to-end.
- **Reading C — table + config only.** Sampler class + writer
  deferred to a future commit. "Scaffold" means just the
  persistence layer.

**Recommendation: Reading A.** Reading B's stub re-evaluator is
operationally weird — it would mark every sample as
"re-evaluation complete" without doing anything useful, polluting
the column's semantics. Reading C is too narrow — without the
writer, the scaffold has no integration test surface (the F2.2
verification-surface argument applies). Reading A ships the path
end-to-end through the write side; the re-evaluator is the next
piece to land but is genuinely separate work (cross-event
re-evaluation logic, possibly LLM-based, definitely async).

---

## F5.3 — watch-for-pattern check

| Pattern | Status pre-commit | Commit 4 instance | Disposition |
|---|---|---|---|
| Closed-enum CHECK + sentinel test | 7 (mode, triggered_by, dispatches.status, output_type, decision, rationale_tag, plus pre-M5 dispatches enums) | +1 (re_eval_state) if Q5-A. NOT +1 for source_event_kind per Q1 Reading C departure (open TEXT + spec-doc taxonomy). | Departure from closed-enum-sentinel pattern at Q1. Surface as anticipated pushback (F5.5). |
| `read_current_X` rowid-DESC helper | 2 (still); commit 3 added zero | 0 at commit 4 (sampler is write-only at this commit; pending-row scan is the future re-evaluator's read shape, not a "most-recent" lookup). | No change. |
| Audit-action `pm.<surface>.<verb>` namespace | 5 (`pm_mode.transition` + four `pm.current_focus.*`) | 0 at commit 4 if Q6-A; +1 (`audit_sample.selected`) if Q6-B. | No new actions per Q6-A. |
| `bridge/` → `tools/` cross-layer imports | 2 | 0 at commit 4 (sampler is `audit/`-only; main.py wires lifespan but main.py is the orchestration layer, not bridge). | No change. |
| Production-code-surface-for-testability | 2 pre-chain | possibly +1 — Sampler may need a clock/randomness injection seam for deterministic tests of the coin-flip behavior. Watch at draft time. | Watch. |
| Migration count | 3 added at this sub-milestone (0010/0011/0012) | 0012 closes the F2.4 revised count. | F2.4 revised "(0010, 0011, 0012)" matches. |
| F2.1 surface-as-finding rule | 3 invocations (commits 2 prep, 2 draft, 3 draft) | Likely +1 at commit 4 draft. Per closeout pattern observation, expected systematic underestimate. | No re-prep. |
| **Q1 Reading C** (Open TEXT + spec-doc taxonomy departure from closed-enum precedent) | NEW pattern instance | First instance of "schema-discipline relaxation when cohort-coherence value is low" — surface as a watch-for-pattern entry if a second instance emerges (e.g., `audit_sample_log.re_eval_finding` is already free-text-ish). Capture in F5.4. |

---

## F5.4 — test-target estimate

F2.1 estimate: +10. Working breakdown under recommended dispositions
(Q1-C, Q2-A, Q3-A, Q4-C, Q5-A, Q6-A, Q7-A, Q8-A, Q9-A, Q10-A):

| Layer | Count |
|---|---|
| Migration smoke tests (columns + indexes) | 1 |
| `re_eval_state` CHECK constraint sentinel | 1 |
| `sampled_at` default-populates | 1 |
| `Sampler._sample_decision` pure-function (0%, 100%, 50% statistical) | 3 |
| `Sampler.maybe_sample` writer happy path (sampled / skipped) | 2 |
| Settings `audit_sample_percentage` default + range validation | 2 |
| MCP wrapper `record_sampleable_event` PM-allowed | 1 |
| MCP wrapper `record_sampleable_event` non-PM-rejected (parametrize × 3) | 1 unique / 3 collected |
| Audit-log non-integration sentinel (Q6 invariant pin) | 1 |
| **Total under recommendations** | **13 unique / 15 collected** |

**vs F2.1 estimate of +10: 50% over baseline.** Per the closeout
pattern observation (three F2.1 invocations now, three durable
reasons), this falls squarely in the "scope-correct +
absorbed-pattern" disposition envelope. Q1 Reading C departure
from closed-enum may also produce a "spec-doc taxonomy compliance"
test verifying the canonical kinds list documented in
`05-data-model.md` matches what the sampler actually accepts —
that's a possible +1 if I draft it.

**Range under disposition variance:**

- Q5 Reading B (no CHECK): -1.
- Q6 Reading B (audit integration): +1.
- Q10 Reading B (stub re-evaluator): +2-3.
- Q11 Reading C (table-only): -4-5 (no writer tests, no MCP
  wrapper tests).

Range: ~9-17 unique. Default disposition produces ~13.

---

## F5.5 — anticipated pushbacks

Three weak points likely to surface at review:

1. **Q1 Reading C departs from the closed-enum-sentinel pattern at
   6+ instances.** I'm rejecting the established pattern for
   `source_event_kind` based on the cohort-coherence-value-is-low
   argument. Reviewing-Claude / Mike may push back: "rule-of-six
   means closed-enum is established discipline; rejecting it
   requires stronger justification than 'descriptive metadata, not
   dispatch-affecting routing.'" Counter-argument I'd raise: F4.5.2
   captured "threshold-of-three watch is trigger-to-evaluate, not
   trigger-to-extract" — same reasoning applies inverted here.
   Closed-enum-sentinel pattern is a pattern at surface; the
   abstraction unit (cohort-coherence + dispatch-affecting + finite
   taxonomy) is what makes a closed-enum the right shape. When
   those conditions don't hold, the pattern shouldn't apply
   reflexively. But this is genuinely contestable. Mike may
   disposition Reading A (closed-enum) to preserve discipline and
   accept the migration cost on future kinds. If so, surface
   the candidate kinds list at draft time for closure.

2. **Q3 Reading A's "explicit hook MCP tool" requires loop driver
   to make extra MCP calls per iteration.** At ~30s tick cadence,
   this is ~2880 record_sampleable_event calls/day if every event
   is sampleable. Reading B (embedded) saves those calls. The
   counter-argument I gave (loop has selective control over what's
   sampleable) is real but Reading B can also support selectivity
   via per-handler skip logic. If MCP-call-volume becomes a real
   concern at commits 7-8 (the loop driver may have its own MCP
   call budget), Reading A revisits. Surface at commit 7 prep as
   a cadence-volume interaction note (mirroring the commit 3
   carry-forward to commit 7).

3. **Q10 Reading A defers the actual async re-evaluator without
   naming when it lands.** "Future commit, likely commits 7-8" is
   loose. The re-evaluator is genuinely separate work (cross-event
   logic, possibly LLM-driven, definitely async). It might not
   land at all in M5-continuous-pm-driver — it could be deferred
   to a focused observability sub-milestone post-M5-flag-flips.
   If so, audit_sample_log fills with `re_eval_state='pending'`
   rows that never advance, which is operationally ugly. Surface
   the explicit deferral target — name a sub-milestone or BACKLOG
   entry that takes the re-evaluator. This avoids the
   "scaffolding-without-consumer" smell.

---

## F5.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q10 + the three anticipated-pushback items:

- 0012 migration drafted with Q1+Q2+Q5 dispositions
- `audit/sampler.py` drafted with Q3+Q4+Q8+Q10 dispositions
- `config.py` extended with `audit_sample_percentage` per Q4+Q9
- `main.py` lifespan wires Sampler instance via closure pattern
- `server.py` `_register_audit_sample_tools` if Q3 Reading A
- Spec amendments drafted for 06 + 05 (and 11 if Q6-B)
- BACKLOG.md gets cohort-index deferral entry (mirror commit 2's
  pattern) + re-evaluator routing entry per F5.5 pushback 3
  resolution
- Test fixtures + assertions drafted (~13-15 tests)
- Carry-forward notes section in grounding doc gets new entries
  if commit 7 / future-commit dependencies surface

Diff checkpoint surfaces commit-by-commit; commit 4 lands; commit
5 prep opens (effort plumbing — agent yaml field +
PMSupervisor/BridgeDispatcher pass `--effort` to claude
subprocess).

**End of Followup-8.**

---

# Followup-9 — F5.2 disposition feedback + Q1/Q10 re-engagement

**Date:** 2026-05-05. **Trigger:** Reviewing-Claude's dispositions on
F5.2 Q1-Q10 + F5.5 pushbacks (yesterday). Q1 and Q10 sent back for
re-engagement; Q3 accepted with carry-forward extension; Q2 / Q4 /
Q5 / Q6 / Q7 / Q8 / Q9 accepted clean.

Section numbered F5.6.

---

## F5.6.A — accepts noted (Q2 / Q3 / Q4 / Q5 / Q6 / Q7 / Q8 / Q9)

| Q | Disposition | Notes |
|---|---|---|
| Q2 | Reading A (polymorphic free TEXT, no FK) | Document conventional id types per kind in 05-data-model.md. |
| Q3 | Reading A (explicit hook MCP tool) | Accepted with carry-forward extension: cadence-volume → commit 7 entry now covers both pm_spin row volume (commit 3) AND sampler-trigger MCP volume (commit 4). Doc-only update to Carry-forward notes section below. |
| Q4 | Reading C (single global at commit 4; defer per-kind override) | Future per-kind expansion via dict layer. |
| Q5 | Reading A (closed-enum CHECK + sentinel) | 8th sentinel; pattern durable. |
| Q6 | Reading A (no audit_log integration) | F4.5.3 reasoning applies directly; sentinel test pins invariant. |
| Q7 | Reading A (single INSERT no transaction) | Conditional on Q6-A; held. |
| Q8 | Reading A (coin flip) | Phase 1 default; promote to deterministic-stratified at M5-flag-flips if variance hides signals. |
| Q9 | Reading A (`0.0` opt-in disabled-by-default) | Explicit-opt-in shape; mirrors enable_engineer_supervisor / enable_pm_supervisor flag conventions. |

---

## F5.6.B — Q1 reconsidered: switching to Reading A (closed enum)

**Reviewing-Claude's three counter-arguments hold; Reading C falls
apart.** Capturing the reasoning durably for F2.5 closeout
observations (corrects my Q1 Reading C framing):

1. **Finite taxonomy IS satisfied.** Phase 1 reality: M5-flag-flips
   enables existing surfaces (no new event kinds); M6 is operator-facing
   reads (no new event kinds). New event kinds emerge only from
   genuinely new Phase 2+ infrastructure, at which point migration is
   the right tool. The kinds list at commit 4 is effectively stable
   through the chain. My "/etc." hedge in F5.2 Q1 was unsupported
   by the actual phase shape.
2. **Cohort-coherence IS satisfied.** I confused
   "routing-decision-affecting" with "cohort-load-bearing."
   `source_event_kind` exists FOR cohort queries — "fraction of
   pm.current_focus.completed events sampled," "all samples for
   dispatch.subprocess_terminated events." Typo'd values would
   collapse the cohort dimension. That's exactly the
   closed-enum-sentinel pattern's load-bearing case.
3. **"Dispatch-affecting" was a fake criterion** I introduced to
   justify Reading C. The actual closed-enum precedent (mode,
   triggered_by, dispatches.status, output_type, decision,
   rationale_tag) is **finite + operator-meaningful +
   cohort-load-bearing** — none are "dispatch-affecting" per se.
   Mode/output_type/decision are narrative state classifiers,
   exactly like source_event_kind would be.

**[DISPOSITIONED 2026-05-05: Reading A accepted.]**

### Q1 disposition impact on commit 4 scope

Closed-enum CHECK on `source_event_kind` with the candidate kinds
list constrained by Q3 Reading A's PM-only scope (PM's loop is the
only caller of `record_sampleable_event` at commit 4):

**Closed enum at commit 4:**

```sql
CHECK (source_event_kind IN (
    'pm.current_focus.completed',
    'pm.current_focus.contract_violation',
    'pm.current_focus.superseded'
))
```

Three values. Tight Phase 1 scope matching what's operationally
sample-worthy from PM's perspective. Excluded values + rationale:

- `pm.current_focus.declared` — declares are unmodifiable assertions
  (no validation outcome to re-evaluate); sampling provides no
  diagnostic value.
- `pm_spin_rationale.recorded` — high-volume aggregation-shape
  consumer per F4.1 reframe; sampling individual rows isn't
  operationally useful (cohort question is "what fraction of ticks
  resulted in shutdown_recommended" — answered by aggregation, not
  sampling).
- `dispatch.*` events — Engineer's subtree territory; PM's MCP token
  doesn't emit these. Future Engineer continuous loop spec
  (post-M5-flag-flips) may extend the kinds list via migration.
- `pm_mode.transition` — low-volume; sampling barely makes sense.
- `api_usage.recorded` — high-volume; cost cohort sampled differently
  (per-call cost-interrogation tier 1 already shipped at
  M5-subscription-routing).

**Future expansion via migration:**
- Engineer continuous loop spec lands → add `dispatch.*` kinds.
- Operator workload shows value in additional kinds (e.g.,
  high-volume retrospective sampling of pm_spin_rationale during a
  drift investigation) → add via migration.

Mirrors `0008_dispatches_status_check.sql` precedent for closed-enum
expansion via migration.

### Q1 disposition impact on F5.3 watch-for-pattern check

The F5.3 entry "Q1 Reading C (Open TEXT + spec-doc taxonomy
departure from closed-enum precedent)" no longer applies. The
closed-enum sentinel pattern continues uniformly (`re_eval_state`
+ `source_event_kind` = +2 sentinels at commit 4). Pattern is at
9+ instances; durable as before.

### Q1 disposition impact on F5.5 anticipated-pushbacks

F5.5 pushback #1 ("Q1 Reading C departs from closed-enum precedent")
resolves: pushback was correct, Reading C withdrawn, Reading A
accepted. Capture in F2.5 closeout-staging notes as second instance
of bidirectional-contradiction-discipline pattern (first was F1.1
task_type γ rollback during M5-continuous-pm-driver chain prep).

---

## F5.6.C — Q10 reconsidered: third option (b)' surfaced and recommended

**Two surfaced options + my third option:**

- **(a) Name re-evaluator in specific commit slot.** Chain expansion
  (4b/7.5/8.5) — re-litigates F2.1's 9-commit shape. Re-evaluator is
  genuinely separate work (cross-event re-eval logic, async
  machinery, possibly LLM-driven); shoehorning into existing slots
  distorts their scope.
- **(b) Defer cross-sub-milestone via BACKLOG.md § Carry-forward to
  future sub-milestones (lazy-create per Decision 5 convention).**
  Generic carry-forward; destination unnamed.
- **(b)' Lazy-create BACKLOG.md § Carry-forward to future
  sub-milestones at commit 4 with the audit_sample_log re-evaluator
  entry naming the destination explicitly: post-M5-flag-flips
  focused-observability sub-milestone, per existing BACKLOG
  observability roadmap entry.** Entry cross-references the
  canonical roadmap.
- **(d) Pure-amendment of existing BACKLOG observability roadmap
  entry without using the Carry-forward section.** Avoids
  lazy-creation churn but splits routing between observability
  roadmap and Carry-forward immediately at commit 4, weakening the
  brand-new Decision 5 convention.

**Recommendation: (b)'.**

**Defending (b)' over (a):** chain expansion is the wrong shape.
Re-evaluator is observability tier-2 infrastructure (cross-event
re-eval logic, async machinery, possibly LLM-driven).
M5-continuous-pm-driver's chain is "narration + fitness tier-1 +
loop driver"; tier-2 work explicitly defers per BACKLOG observability
roadmap. Forcing the re-evaluator into 4b/7.5/8.5 distorts the
chain's scope.

**Defending (b)' over (b):** vague "TBD" makes the carry-forward
note operationally weak. Future-Claude opening the
focused-observability sub-milestone shouldn't have to re-litigate
where the re-evaluator goes — the destination is already implied by
BACKLOG observability roadmap. Naming it at commit 4 closes the
loop.

**Defending (b)' over (d):** Decision 5 convention is brand new
(commit 3). Splitting routing between observability roadmap and
Carry-forward section immediately at commit 4 — when there's no
compelling architectural reason — weakens the convention before
it's established. (b)' uses Decision 5's surface as designed AND
cross-references to the canonical roadmap, getting both: convention
adherence + explicit destination.

### Q10 disposition impact on commit 4 scope

- BACKLOG.md gets new section `## Carry-forward to future
  sub-milestones` (lazy-created at commit 4 — first cross-boundary
  entry).
- First entry: `### → focused-observability sub-milestone
  (post-M5-flag-flips): audit_sample_log re-evaluator`. Body names
  the work scope (process pending samples → in_progress → complete
  with re_eval_finding text; possibly LLM-driven; async machinery)
  + cross-references the existing BACKLOG observability roadmap
  entry that already implicitly defers the re-evaluation.
- F2.5 closeout-staging notes get an entry capturing the convention's
  first cross-boundary materialization.

---

## F5.6.D — Q3 carry-forward extension (doc-only)

Q3 disposition accepted with extension: the existing
"→ commit 7" carry-forward entry in the grounding doc § Carry-forward
notes section (added at commit 3 for pm_spin row volume) is amended
to also cover sampler-trigger MCP call volume scaling with loop
tick cadence. Both topics share the same "scaling with cadence"
shape; consolidating under one entry header keeps the carry-forward
surface tight.

Updated entry header: `### → commit 7: pm_spin row volume +
sampler-trigger MCP call volume scale with loop tick cadence`.
Body extends the existing prose to mention the additional MCP call
budget consideration at commit 7.

---

## F5.6.E — what lands at the diff checkpoint

After these dispositions land:

- 0012 migration drafted with Q1 Reading A closed-enum CHECK on
  source_event_kind (3 values) + Q5 Reading A closed-enum CHECK on
  re_eval_state (3 values) + Q2 Reading A polymorphic free TEXT
  source_event_id
- `audit/sampler.py` drafted with Q3 Reading A explicit hook + Q8
  coin flip + Q10 scope (writer + MCP wrapper; re-evaluator
  deferred per Q10 disposition)
- `config.py` extended with `audit_sample_percentage: float`
  (default 0.0, ge=0.0, le=1.0)
- `main.py` lifespan wires Sampler instance via closure pattern
- `server.py` `_register_audit_sample_tools` ships
  `record_sampleable_event` MCP tool (PM-only)
- Spec amendments: 06-mcp-tools (record_sampleable_event tool +
  permission row); 05-data-model (audit_sample_log table section +
  source_event_kind kinds list); migration table row for 0012; 11
  unchanged per Q6-A
- BACKLOG.md gets:
  - Cohort/secondary indexes deferral (mirror commit 2's pattern)
  - NEW `## Carry-forward to future sub-milestones` section
    (lazy-created per Decision 5; first entry: re-evaluator →
    post-M5-flag-flips focused-observability sub-milestone)
- Grounding doc § Carry-forward notes: existing → commit 7 entry
  amended to cover sampler-trigger MCP volume in addition to pm_spin
  row volume
- F2.5 closeout-staging notes: capture (a) Q1 reading-reversal as
  second bidirectional-contradiction-discipline-pattern instance;
  (b) Decision 5 convention's first cross-boundary materialization
  at commit 4

Diff checkpoint surfaces commit-by-commit; commit 4 lands; commit
5 prep opens (effort plumbing).

**End of Followup-9.**

---

# Followup-10 — commit 5 prep checkpoint (effort plumbing — agent yaml field + supervisor + dispatcher pass `--effort`)

**Date:** 2026-05-05. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 5.
**Prerequisites landed:** commits 1-4 (workflow_id α, ff47573;
pm_current_focus, b1ad27a; pm_spin_rationale_log, 433cef5;
audit_sample_log, 1e323ca); .gitignore chore (414728f).

**Structural shift from commits 1-4:** this commit is **plumbing**,
not storage-layer expansion. No migration. No new MCP tool. No new
table. No closed-enum CHECK constraint at the schema layer (Pydantic
Literal still applies at the AgentConfig boundary). The questions
shape themselves around **cross-module value flow** —
yaml → AgentConfig → dispatcher/supervisor constructors → launch
context → subprocess CLI args. Each step in the flow is a
verification surface; the dispositions cluster around field
placement, defaults, legacy-fallback when effort is absent, and
plumbing-path choice.

**Spec amendment scope deferred to commit 6** per F2.1 chain plan:
addendum doc `docs/kickoff/opus-4-7-effort-and-thinking-addendum.md`
+ merges into 02 / 07 / 11 ride at commit 6 (docs-only). At commit 5
the yaml fields land + plumbing wires up; at commit 6 the
rationale-and-merges land. Commit 5 verifies plumbing without the
docs being in place. Surface in F6.5.3 as anticipated pushback if
the split feels wrong.

**Per-role effort assignments per addendum (orchestrator-utilization-
addendum.md):** PM xhigh, Engineer xhigh, Implementer xhigh, Tester
high. Locked at this commit.

---

## F6.1 — touch points

Per F2.1 row 5: `config/agents/*.yaml`, `bridge/supervisor_base.py`,
`bridge/dispatcher.py:_default_launcher`, `agents/config.py`. Resolved
file-by-file:

| File | Status | Touch shape |
|---|---|---|
| `config/agents/pm.yaml` | EDIT | Add `effort: xhigh` line. |
| `config/agents/engineer.yaml` | EDIT | Add `effort: xhigh` line. |
| `config/agents/implementer.yaml` | EDIT | Add `effort: xhigh` line. |
| `config/agents/tester.yaml` | EDIT | Add `effort: high` line. |
| `src/meridian_orchestrator/agents/config.py` | EDIT | Add `Effort` Literal type alias; add `effort: Effort` field on AgentConfig (default per Q2 disposition). |
| `src/meridian_orchestrator/bridge/dispatcher.py` | EDIT | Add `effort: str` field on `DispatchLaunchContext`; thread effort through `BridgeDispatcher.dispatch()` kwargs (per Q3 disposition); `_default_launcher` passes `--effort <level>` to subprocess. |
| `src/meridian_orchestrator/bridge/supervisor_base.py` | EDIT | Add `effort: str` field on `SupervisorLaunchContext`; thread through; `_default_launcher` passes `--effort <level>`. |
| `src/meridian_orchestrator/bridge/engineer_supervisor.py` | EDIT | When constructing SupervisorLaunchContext, populate effort from AgentConfig (Engineer's). |
| `src/meridian_orchestrator/bridge/pm_supervisor.py` | EDIT | Same as engineer_supervisor for PM. |
| `src/meridian_orchestrator/server.py` (or `tools/dispatch.py`) | EDIT | Wrapper / handler resolves effort from registry per Q3 disposition, threads to `dispatcher.dispatch()`. |
| `src/meridian_orchestrator/main.py` | UNCHANGED OR EDIT | Lifespan-time wiring of registry → supervisor constructors. Whether main.py is touched depends on Q3. |
| `tests/unit/agents/test_config.py` (existing) | EDIT | Add tests for `effort` field schema + Literal validation + per-yaml-file value assertions. |
| `tests/unit/bridge/test_dispatcher.py` (existing) | EDIT | Verify `--effort <level>` lands in subprocess args; thread-through correctness. |
| `tests/unit/bridge/test_supervisor_base.py` (existing) | EDIT | Same for supervisor launcher. |
| `docs/kickoff/07-agent-configs-phase-one.md` | DEFERRED | Spec amendment lives at commit 6; not touched at commit 5. |
| `docs/kickoff/{02,11}.md` | DEFERRED | Same. |
| New addendum doc | DEFERRED | Same. |

---

## F6.2 — open questions (Q1-Q9)

### Q1 — `Effort` Literal value scope: full CLI surface vs Phase-1-actual

What values does the `Effort` Literal accept?

- **Reading A — full CLI surface.** `Literal["low", "medium", "high", "xhigh", "max"]` per F1.3 Source 3 verification (Claude Code CLI accepts these 5 values). Defense-in-depth alongside the CLI's own validation. Operator-tunable: future operator adjustments (Tester to medium for cost; PM to max for high-stakes) admissible without migration.
- **Reading B — Phase-1-actual.** `Literal["high", "xhigh"]` matching addendum-specified values only. Forced-fit narrower; future operator adjustments require schema change.
- **Reading C — open string.** Pass through to CLI without pre-validation. Lowest discipline.

**Recommendation: Reading A.** Effort is operator-tunable (different from `source_event_kind` at commit 4 which was bounded by what the loop emits — a system-side constraint). Operators changing effort per-agent should not require schema migration. Closed Literal matching CLI surface gives Pydantic-boundary defense without locking out legitimate operator config space. Reading B's narrowing surfaces the same forced-fit risk F5.6.B Q1 reading reversal exposed in the opposite direction; symmetric framing avoids that error.

### Q2 — Default value when effort field is absent from yaml

What happens if a yaml file is loaded without an `effort` field?

- **Reading A — required field; AgentConfig fails Pydantic validation.** Yamls must declare effort explicitly. Hard-fail at load time. Operator editing a yaml to remove effort surfaces the bug at daemon startup.
- **Reading B — soft default.** Pydantic field defaults to `"high"` (Claude Code's CLI default per F1.3 Source 3). Yamls without effort silently get the default. WARN log on load.
- **Reading C — None default + no `--effort` flag passed.** AgentConfig's effort defaults to None; subprocess invocation skips the flag entirely; subprocess inherits Claude Code's own default. No explicit failure mode; no override of CC's behavior when yaml is silent.

**Recommendation: Reading A (required field; hard-fail).** Effort is a deliberate operator config per addendum; missing field is a bug worth surfacing loudly. Soft defaults (B/C) mask config drift — a yaml edit that accidentally removes effort would not produce visible behavior change (CC defaults are reasonable) but would silently disagree with operator intent. Hard-fail forces the yaml to be explicit; pairs with Phase 1 conventions of explicit-config-over-magic-defaults (mirrors `enable_engineer_supervisor=False` opt-in shape in spirit — operator must declare).

**Mitigation for test fixtures:** AgentConfig's pytest fixtures may need to specify effort. Surface as part of the test-target estimate; trivial fixture extension.

### Q3 — Plumbing path: dispatcher kwarg vs registry access vs static mapping

How does effort flow from AgentConfig (loaded by registry) into the launcher's subprocess args?

- **Reading A — kwarg through `dispatcher.dispatch()`.** MCP wrapper layer (server.py's `_register_dispatch_initiation_tools`) resolves effort from registry, passes through `dispatcher.dispatch(..., effort=...)`. Dispatcher appends to `DispatchLaunchContext`. Supervisor symmetric: supervisor's caller (PMSupervisor / EngineerSupervisor) reads its own role's effort from a held registry reference, passes into SupervisorLaunchContext.
- **Reading B — pass `AgentRegistry` to `BridgeDispatcher` constructor.** Dispatcher does the lookup at dispatch time. BridgeDispatcher's signature gains a registry param.
- **Reading C — static `effort_per_role` mapping.** Lifespan builds `{role: effort}` from registry once at startup; passes to BridgeDispatcher as a static dict. Dispatcher looks up by role at dispatch time. Dispatcher doesn't depend on AgentRegistry.

**Recommendation: Reading A.** Two reasons:
1. **Symmetric with existing pattern** — `dispatch_implementer` / `dispatch_tester` MCP handlers already resolve role-specific config (model_tier_default → `tools/dispatch.py:_resolve_model_for_role` calls `registry.get_agent_config(role)`). Adding effort to the same resolution path is a small extension.
2. **No new dispatcher dependency** — Reading B couples BridgeDispatcher to AgentRegistry, expanding its constructor surface. Reading C requires lifespan to build the mapping at the moment registry is loaded but supervisors aren't yet constructed (correlation order issue). Reading A keeps each constructor's dependency surface minimal.

For supervisors: SupervisorBase / EngineerSupervisor / PMSupervisor are currently constructed with role-specific config at lifespan time. Their effort can be passed at construction (constructor kwarg) and stored as instance state, OR resolved at launch time from a registry reference held by the supervisor. Recommend: constructor kwarg for symmetry with how supervisors already receive role-specific config like `working_directory`.

### Q4 — Per-role effort mapping location

Per addendum: PM/Engineer/Implementer xhigh; Tester high. Where does this mapping live?

- **Reading A — per-yaml hard-coded.** Each yaml file declares its own effort line; no shared constant.
- **Reading B — shared constant in `agents/config.py`** — `DEFAULT_EFFORT_PER_ROLE: dict[AgentRole, Effort]`. Yamls that omit effort inherit from constant; yamls with explicit effort override.
- **Reading C — implicit role-based default at load time.** AgentConfig's `effort` defaults via a model-validator that resolves to role-keyed default if not specified.

**Recommendation: Reading A (per-yaml hard-coded).** Phase 1 has 4 yamls; the per-yaml shape mirrors how `model_tier_default` already works (each yaml declares its own value). Reading B/C add indirection without offsetting benefit at this scale; future role additions just declare their own yaml. Operator changes one yaml affects only that agent — clean blast radius.

If Q2 lands as Reading B/C (soft default), Reading B for Q4 becomes natural alignment (the soft default IS the role-keyed mapping). If Q2 stays at Reading A (hard-fail), Reading A here is the right shape — no implicit defaults to manage.

### Q5 — Subprocess CLI flag literal: `--effort` exact name verification

F1.3 Source 3 verified the flag exists; the literal `--effort <level>` shape needs verification at draft time. Possibilities:
- `--effort <level>`
- `--effort=<level>`
- `--effort-level <level>`
- `--reasoning-effort <level>`
- Other flag literal

This isn't a multi-Reading question — it's an empirical verification step that lands at draft time via `claude --help` or current Claude Code docs. Surface as a pre-draft verification item.

**Action:** verify the exact flag literal at draft time. If F1.3 Source 3's reading is wrong (e.g., flag is named differently or accepts arg in `=`-glued form), surface as draft deviation per discipline; document the actual flag in the launcher's code comment.

### Q6 — Mike's interactive PM session: documented non-effect

Per F3.6 Q7 disposition: Mike's interactive PM session is not daemon-launched; the daemon has no plumbing path into Mike's Claude Code session. The yaml's effort field documents the daemon-launched PM-continuous subprocess. Mike's tooling defaults to xhigh on Opus 4.7 (per Claude Code v2.1.117+ defaults).

**No question for commit 5.** The effort plumbing applies only to daemon-launched subprocesses (PMSupervisor's PM-continuous, EngineerSupervisor's Engineer, BridgeDispatcher's Implementer/Tester). Documented in the spec amendment at commit 6.

Surfacing this here for completeness — Mike-edited future yaml might assume effort governs Mike's session; the docstring on AgentConfig.effort should make the daemon-only scope explicit.

### Q7 — Test surface shape

Test categories at commit 5:

1. **Yaml loading**: each yaml's effort field parses into the expected value (4 yamls × 1 assertion = 4 unique tests OR 1 parametrized × 4 collected).
2. **Pydantic Literal validation**: bogus effort value rejected at AgentConfig construction (1 unique).
3. **Effort Literal sentinel**: closed-enum sentinel pattern (per F2.5 graduation note: "no longer requires watch-for-pattern tracking" — but the sentinel-test pattern continues uniformly). 1 unique listing all 5 values from Reading A.
4. **Hard-fail on missing effort** (per Q2 disposition): 1 unique test.
5. **Dispatcher subprocess args**: `--effort <level>` lands in command list; positioning correct. 1-2 unique.
6. **Supervisor base subprocess args**: same for the supervisor launcher. 1-2 unique.
7. **Per-role mapping in yamls**: `pm.yaml`'s effort is xhigh; `tester.yaml`'s is high. Either covered by yaml-loading tests or a separate addendum-compliance test.

**Total estimate: 9-12 unique / 12-18 collected** under recommended dispositions. F2.1 estimate: +9. Per closeout pattern observation, expect actual to run higher per the three-reason framing. Expected to NOT trip the 200% trigger condition (>20 collected).

### Q8 — Mock subprocess vs actual subprocess in tests

- **Reading A — mock the launcher.** Verify the subprocess args list contains `--effort <level>` at the expected position. Standard test-injection pattern (existing dispatcher / supervisor tests already mock the launcher).
- **Reading B — actual claude subprocess invocation.** Integration test that exercises real subprocess. Heavyweight; verifies CC's CLI semantics rather than the daemon's plumbing.

**Recommendation: Reading A (mock).** The CLI flag's actual semantics are Claude Code's contract, not the daemon's. The daemon's responsibility is "produce the right command line"; verifying the produced args list is the discipline target. Existing test fixtures already mock the launcher; effort plumbing tests slot in.

### Q9 — Effort field freeze: once set at AgentConfig, immutable for the daemon's lifetime

Once daemon starts, effort is loaded from yaml and held in registry. If Mike edits a yaml mid-flight (hot-reload via `watchfiles` per existing AgentRegistry behavior), does the running supervisor / dispatcher pick up the new effort?

- **Reading A — hot-reload picks up new effort; subsequent dispatches use new value.** Dispatcher re-resolves at each `dispatch()` call via Reading A's kwarg-from-registry path; supervisor re-reads at next launch (after compaction respawn or restart). Aligns with how `model_tier_default` hot-reloads today.
- **Reading B — frozen at startup; effort changes require daemon restart.** Snapshot-on-startup; in-flight subprocesses unaffected; new dispatches use the snapshot.
- **Reading C — depends on Q3.** Reading A on Q3 (kwarg-resolution per dispatch) gives Reading A here naturally. Reading B/C on Q3 (constructor kwarg) gives Reading B here.

**Recommendation: Reading A (hot-reload picks up).** Aligns with existing model_tier_default hot-reload behavior; operator editing yaml expects behavior change. Reading B's freeze is surprising. The supervisors' "next launch" granularity is acceptable — supervisors are long-lived and respawns are infrequent; effort is read at launch time.

This depends on Q3 Reading A (kwarg resolution from registry per dispatch). If Q3 lands as Reading C (static mapping), this question's Reading A becomes harder to honor.

---

## F6.3 — watch-for-pattern check

| Pattern | Status pre-commit | Commit 5 instance | Disposition |
|---|---|---|---|
| Closed-enum CHECK + sentinel test | Graduated to established convention at commit 4 (9 instances) | +1 (Effort Literal) at AgentConfig boundary; no SQL CHECK because no migration. Pydantic Literal sentinel test continues. | Pattern uniform; no extraction surface. |
| Yaml field plumbing through DispatchLaunchContext / SupervisorLaunchContext | NEW pattern | First yaml-field plumbing through both launch contexts. Future yaml fields with subprocess-arg implications would mirror this shape. | Watch — flag for second instance. |
| `bridge/` ↔ `agents/` cross-layer dependency | NEW potentially | Q3 Reading A's "wrapper resolves effort from registry → dispatch kwarg" doesn't introduce new bridge→agents imports (wrapper layer has both registry and dispatcher). Reading B/C might. | Per Q3 disposition. |
| Production-code-surface-for-testability | 2 pre-chain (token_store=None, _run_lifespan_body) | Possibly +1 if effort plumbing requires injection seams beyond existing launcher mock. Watch at draft time. | Watch. |
| F2.1 surface-as-finding rule | 4 invocations | Likely +1 at commit 5 draft. Per closeout trigger condition: re-baseline if >200% over F2.1 estimate (>18 tests vs F2.1's +9). | Surface explicitly at diff checkpoint. |

---

## F6.4 — test-target estimate

F2.1 estimate: +9. Working breakdown under recommended dispositions
(Q1-A, Q2-A, Q3-A, Q4-A, Q5-verify-at-draft, Q7-default,
Q8-A, Q9-A):

| Layer | Count |
|---|---|
| Effort Literal sentinel (5 values pass + bogus rejects) | 2 |
| Per-yaml effort assertion (4 agents × 1 unique parametrized) | 1 unique / 4 collected |
| Hard-fail on missing effort (Q2-A pin) | 1 |
| AgentConfig schema test (effort field shape) | 1 |
| Dispatcher launcher: `--effort <level>` in args | 1-2 |
| Supervisor launcher: `--effort <level>` in args | 1-2 |
| Engineer/PM supervisor effort threading | 1-2 |
| **Total under recommendations** | **9-11 unique / 12-15 collected** |

**vs F2.1 estimate of +9: roughly aligned.** Per closeout pattern
observation (three-reason framing, four prior invocations), expect
some divergence — disposition-driven invariant pins (Q2 hard-fail,
Q9 hot-reload behavior) and precedent-mirror tests
(yaml-loading-per-agent parametrize) likely push collected count
higher.

**Trajectory observation:** commits 2-4 ran 64% → 93% → 190% over.
Commit 5's structural shift (no migration, no MCP tool, smaller
total surface) may break the trajectory if final divergence stays
under 100%. **Trigger condition for re-baseline activates if
divergence >200% (>18 tests collected).** Surface explicitly at
diff checkpoint per F2.5 closeout trigger pin.

---

## F6.5 — anticipated pushbacks

Three weak points likely to surface at review:

1. **Q1 Reading A vs Phase-1-actual narrowing.** Reading B narrows
   the Effort Literal to {high, xhigh} matching addendum-specified
   usage. F5.6.B Q1 reading reversal landed Reading A on
   source_event_kind because the kinds were operationally bounded
   and operator-tunable scope was limited. Effort is more genuinely
   operator-tunable (different per-role tunings reasonable for
   different cost/latency trade-offs). Reading A holds; surface for
   reviewing-Claude to confirm the symmetry framing isn't sleight
   of hand.

2. **Q2 Reading A hard-fail vs soft default.** Hard-fail at yaml
   load is the discipline shape but introduces a friction surface
   at test-fixture construction (every AgentConfig test fixture
   must specify effort). Reading C (None default + skip flag) is
   the friction-minimizing alternative. The discipline argument
   wins on operator-config integrity grounds, but I should
   acknowledge the friction trade. If Mike values fixture
   simplicity over discipline-loud, Reading C is defensible. The
   trade is real.

3. **Q3 Reading A vs Reading C plumbing-path choice.** Reading C
   (static `effort_per_role` mapping passed to BridgeDispatcher
   constructor) avoids per-dispatch registry lookup. Reading A
   (kwarg per dispatch) matches existing model_tier_default
   resolution path but adds per-dispatch lookup work — small
   per-call cost, but in PM's high-cadence loop world, every
   per-dispatch call sums up. If Q9's hot-reload requirement is
   important, Reading A wins. If hot-reload doesn't matter (Reading
   B on Q9), Reading C is faster. Q3 disposition depends on Q9
   disposition; surface the dependency explicitly.

4. **Commit 5 / commit 6 split.** F2.1's chain plan separates
   plumbing (commit 5) from spec amendment (commit 6). At commit 5,
   the yamls ship with `effort` lines but no spec doc explains
   why the values were chosen. Future-Claude reading commit 5 in
   isolation (e.g., during a partial git blame exploration) sees
   "effort: xhigh" without rationale; that rationale lands at
   commit 6 (the addendum doc + merged 02/07/11 docs). Acceptable
   shape per F2.1 chain plan. Surface as deliberate scope choice;
   if Mike disposition prefers folding commit 6 into commit 5, the
   chain plan revises at this point.

---

## F6.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q9 + the four anticipated-pushback items:

- Effort Literal added to `agents/config.py` per Q1
- `effort` field added to AgentConfig per Q2
- Per-yaml `effort:` lines added per Q4 (PM/Engineer/Implementer
  xhigh; Tester high)
- `DispatchLaunchContext` / `SupervisorLaunchContext` extended with
  effort field
- `_default_launcher` invocations pass `--effort <level>` per Q5
  (verified at draft time)
- Dispatcher `dispatch()` kwarg + supervisor constructor kwarg per
  Q3
- Wrapper layer in `server.py` resolves effort from registry per Q3
- Tests for yaml loading, Literal validation, dispatcher args,
  supervisor args (~9-15 collected)
- F2.1 divergence assessment surfaced explicitly at diff checkpoint
  per F2.5 trigger-condition pin
- 11-engineer-bridge.md NOT touched (commit 6 scope per F2.1
  chain plan; will land alongside addendum doc)

Diff checkpoint surfaces commit-by-commit; commit 5 lands; commit
6 prep opens (hybrid spec amendment doc + merges into 02 / 07 / 11).

**End of Followup-10.**

---

# Followup-11 — F6.2 disposition feedback + Q1/Q2 re-engagement

**Date:** 2026-05-05. **Trigger:** Reviewing-Claude's dispositions on
F6.2 Q1-Q9 + F6.5 pushbacks. Q1 sent back for framing-precedent
reset; Q2 sent back for friction quantification before disposition;
Q3-Q9 + commit-message refinement accepted clean.

Section numbered F6.6.

---

## F6.6.A — accepts noted (Q3 / Q4 / Q5 / Q6 / Q7 / Q8 / Q9 + commit-message refinement)

| Q | Disposition | Notes |
|---|---|---|
| Q3 | Reading A (kwarg per dispatch from registry) | Symmetric with model_tier_default resolution; no new dispatcher dependency. |
| Q4 | Reading A (per-yaml hard-coded) | Mirrors model_tier_default precedent; no centralization. |
| Q5 | Verification at draft time | Per F1.3 Source 3 verification + on-draft Claude Code CLI doc check. Document verification source in launcher code comment. |
| Q6 | Non-question | Mike's interactive PM session governed by client convention; daemon-launched PM-continuous governed by yaml. Documentation lands at commit 6. |
| Q7 | Informational | Test-target estimate assessed at diff checkpoint. F2.1 re-baseline trigger active. |
| Q8 | Reading A (mock subprocess) | Existing pattern; verify args list contains `--effort <level>`. |
| Q9 | Reading A (hot-reload at next dispatch/launch) | Composes with Q3-A; aligns with model_tier_default hot-reload behavior. |
| commit-5/6 split | Accept with cross-reference | Commit 5 message body explicitly references commit 6 for rationale-doc forward pointer. Cheap insurance against orphaned-context. |

---

## F6.6.B — Q1 reconsidered: drop F5.6.B symmetry framing; defend on operator-agency

**Reviewing-Claude's pushback exposed framing-precedent-application
sleight of hand:** F5.6.B was a SQL CHECK on a cohort key column
where expansion costs a migration; effort Literal expansion is type
annotation + sentinel test. Migration-cost asymmetry is real but I
tried to dress it up as symmetric reasoning to import F5.6.B's
precedent authority. Drop entirely.

**Reading A defended on operator-agency (Argument 2):**

Effort is operator-tunable per Anthropic's own guidance — xhigh for
agentic loops, minimum high for intelligence-sensitive work,
medium/low admissible for cost-sensitive contexts, max for
hard-problem dispatches. The yaml field IS the operator's tuning
surface. Restricting to Phase-1-actual `{high, xhigh}` (Reading B)
freezes operator agency without architectural reason. Full 5-value
Literal admits exploration without migration overhead.

Argument 1 (validate at yaml-load time) supports closed-enum-of-some-shape
but isn't load-bearing for the 5-value choice. Argument 2 is the
defense.

**[DISPOSITIONED 2026-05-05: Reading A accepted on operator-agency
grounds.]** Code comment on the `Effort` Literal documents Phase 1
actual usage per orchestrator-utilization-addendum:

> Phase 1 actual usage per addendum: PM/Engineer/Implementer at
> xhigh, Tester at high. Full 5-value surface admits operator
> tuning beyond defaults — medium/low for cost-conscious
> exploration, max for high-stakes overrides — without schema
> migration overhead.

---

## F6.6.C — Q2 reconsidered: friction quantified; Reading A accepted

**Friction inventory (verified 2026-05-05):**

- Direct `AgentConfig(...)` construction sites in tests: **2 sites in 2 files.**
  - `tests/unit/agents/test_config.py:136-140` — `test_agent_config_frozen` (mechanical kwarg add)
  - `tests/unit/agents/test_permissions.py:12-20` — `_agent()` helper builder (mechanical kwarg + helper signature update)
- Yaml-loading test fixtures: **zero friction** (all use `_seed_repo_yamls` or `REPO_CONFIG_DIR` reads from updated canonical yamls).
- Source-code AgentConfig construction sites: **zero** (only via `load_agent_config` from yaml).

**Total: 2 sites, both mechanical kwarg adds, no fixture rework.**

Well below the 5-10 threshold for clean-Reading-A.

**[DISPOSITIONED 2026-05-05: Reading A accepted.]** Hard-fail
required field gives discipline shape without compounding test
cost. When updating the 2 test sites, inline context comment
documents the kwarg's required-by-disposition origin:

> effort: required per F6.2 Q2 disposition (hard-fail surfacing
> config drift). Future-Claude removing this as "unused" without
> context would silently violate the discipline.

---

## F6.6.D — what lands at the diff checkpoint

After F6.6.B + F6.6.C dispositions land:

- Effort Literal added to `agents/config.py` (full 5-value per
  Q1-A; code-comment documents Phase 1 actual usage)
- `effort: Effort` field added to AgentConfig (required, no
  default, per Q2-A)
- Per-yaml `effort:` lines added to all 4 agents per Q4-A
- `DispatchLaunchContext` / `SupervisorLaunchContext` extended
- `_default_launcher` invocations pass `--effort <level>`
  (verified flag literal per Q5)
- Dispatcher `dispatch()` kwarg + supervisor effort threading per
  Q3-A
- Wrapper layer in `server.py` resolves effort from registry per
  Q3-A
- 2 existing test sites updated with `effort=` kwarg + inline
  context comment
- New tests for yaml loading, Literal validation, dispatcher /
  supervisor args
- F2.1 divergence assessment surfaced at diff checkpoint
- Commit message body references commit 6 explicitly for rationale
  forward pointer

Spec amendment: NONE at commit 5 (deferred to commit 6 per F6.5.4).

**End of Followup-11.**

---

# Followup-12 — commit 6 prep checkpoint (hybrid spec amendment — addendum doc + 02/07/11 merges)

**Date:** 2026-05-06. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 6.
**Prerequisites landed:** commits 1-5 (workflow_id α, ff47573;
pm_current_focus, b1ad27a; pm_spin_rationale_log, 433cef5;
audit_sample_log, 1e323ca; effort plumbing, 71d1e86).

**Structural shift:** docs-only commit. F2.1 estimate: +0 tests.
Gates re-verify trivially. No new code, no new schema, no new test
cases beyond consistency-of-existing tests.

**Spec-consistency baseline (verified 2026-05-06):** Zero existing
kickoff docs mention "effort" / "thinking" / "adaptive" / "xhigh."
The yaml comments added at commit 5 reference
`orchestrator-utilization-addendum` for guidance — but that doc
does NOT currently contain the per-role effort assignment or any
Opus 4.7 thinking-mode content. Commit 6 closes that
forward-reference gap (the references-to-content lands at this
commit). No consistency-gap-elsewhere to fix; the only spec surface
is this commit's deliverable.

---

## F7.1 — touch points

Per F1.3 framing + Mike's commit-6-prep instruction:

| File | Status | Touch shape (conditional on Q1) |
|---|---|---|
| `docs/kickoff/opus-4-7-effort-and-thinking-addendum.md` (NEW per Q1 Reading A) **OR** new section in existing `docs/kickoff/orchestrator-utilization-addendum.md` (per Q1 Reading B) | NEW or EDIT | Q1 disposition shapes the choice. |
| `docs/kickoff/02-architecture.md` | EDIT | Brief reference per Q3 disposition. |
| `docs/kickoff/07-agent-configs-phase-one.md` | EDIT | Effort field schema + per-role assignment table per Q4 disposition. |
| `docs/kickoff/11-engineer-bridge.md` | EDIT | Effort flow through dispatcher launcher per Q5 disposition. |
| `config/agents/{pm,engineer,implementer,tester}.yaml` | UNCHANGED IF Q1-B; EDIT IF Q1-A | If Q1-A (separate file), yaml comments update from `orchestrator-utilization-addendum` to `opus-4-7-effort-and-thinking-addendum`. |
| `src/meridian_orchestrator/agents/config.py` | UNCHANGED IF Q1-B; EDIT IF Q1-A | Effort Literal docstring cross-reference update if Q1-A. |
| `src/meridian_orchestrator/bridge/dispatcher.py` | UNCHANGED IF Q1-B; possibly EDIT IF Q1-A | Launcher comments may cross-reference. |
| `src/meridian_orchestrator/bridge/supervisor_base.py` | Same as above |

---

## F7.2 — open questions (Q1-Q5)

### Q1 — Addendum file location: new file vs new section in existing utilization addendum

F1.3 disposition (Followup-1) recommended **a new file**:
`docs/kickoff/opus-4-7-effort-and-thinking-addendum.md` (~150-300
lines, shorter than the utilization addendum). Mike's commit-6-prep
instruction names **the existing file**:
`docs/kickoff/orchestrator-utilization-addendum.md`. The
discrepancy may be a slip (Mike intended F1.3's separate file) or a
deliberate convention shift (fold related addendum content
together).

- **Reading A — separate file** (`opus-4-7-effort-and-thinking-addendum.md`).
  Per F1.3. Topic-scoped: Opus 4.7 effort + thinking is its own
  concern (model-version-specific, time-bounded by Opus 4.7's
  lifecycle); orchestrator-utilization-addendum is about
  subscription pacing (operationally durable). Different scope
  shapes argue for separate files. Future addendums similarly
  topic-scoped.
- **Reading B — new section in existing utilization addendum.** Per
  Mike's literal instruction. The yaml comments added at commit 5
  already reference `orchestrator-utilization-addendum` (forward
  reference to content that doesn't yet exist there); landing the
  effort+thinking content in the same file resolves the reference
  in-place without yaml-comment updates. Less doc proliferation.
  Risk: addendum becomes catch-all for Opus-version-specific
  guidance, blurring the original utilization scope.
- **Reading C — separate file BUT cross-reference from utilization
  addendum + update yaml comments to point at new file.** Combines
  topic-scoping (A) with explicit cross-reference for discoverability.
  Cost: yaml + code comment updates.

**Recommendation: Reading C (separate file with cross-reference).**

Reading A's topic-scoping argument is sound — orchestrator-utilization-addendum
is about subscription pacing (operator's "spend what you bought"
discipline), Opus 4.7 effort is about model-version-specific
configuration. Folding them risks the utilization addendum becoming
"all things addendum" over time as Phase 2+ adds new
model-version-specific guidance. Reading B's "in-place reference
resolution" is real but solves the wrong problem — yaml comments
should point at the topic-scoped doc, not at the doc that happens
to be referenced today.

Reading C's cost is small: 4 yaml comment updates + 3 code-comment
updates. Mechanical changes; low risk. The cross-reference from
utilization addendum to the new opus-4-7 addendum keeps
discoverability cheap for future-Claude reading utilization-context
first.

**Surface as anticipated pushback** (F7.5.1) — Mike's literal
instruction names the existing file; if the slip-vs-deliberate
question lands as "deliberate," I'll switch to Reading B.

### Q2 — F1.3 Q7 disposition placement (Mike's interactive PM session non-effect)

F1.3 Q7 disposition: Mike's interactive PM session governed by
local Claude Code defaults (xhigh on Opus 4.7 by client convention),
not by yaml effort field. The yaml field configures daemon-launched
PM-continuous subprocess only.

Where does this content live in the spec?

- **Reading A — addendum only.** The addendum doc carries the
  Mike-interactive-PM non-effect framing. 07-agent-configs reflects
  the yaml schema without re-explaining the daemon-vs-client
  distinction.
- **Reading B — 07-agent-configs only.** The yaml-schema doc
  explains the field's scope (daemon-launched only); addendum
  doesn't repeat the operational framing.
- **Reading C — both, with single canonical statement in addendum
  + brief reference from 07.** The addendum is the rationale doc;
  07 is the field-schema reference. Cross-reference keeps both
  audiences served.

**Recommendation: Reading C.** 07-agent-configs is what
future-Claude opens to understand "what does the effort field
do?"; the daemon-vs-client distinction is part of that answer.
Addendum carries the full framing (rationale + Anthropic guidance
verification + adaptive-thinking F1.4 finding); 07 cross-references
it. Both audiences served.

### Q3 — 02-architecture.md merge content scope

What lands in 02-architecture for effort plumbing?

- **Reading A — brief reference under agent-launch architecture.**
  ~5-10 line note: "Daemon-launched agent subprocesses receive
  `--effort <level>` per the per-role yaml `effort` field. See
  opus-4-7-effort-and-thinking-addendum.md for guidance." Anchors
  the cross-reference at the architectural surface.
- **Reading B — skip 02 entirely.** Effort plumbing is a launcher
  detail; 02 is high-level architecture. No natural fit. Land
  content in 07 + 11 + addendum only.
- **Reading C — full content in 02 with copy in 07/11.** Makes 02
  the canonical location (architectural primacy) with reference
  copies elsewhere. Risk: drift between copies.

**Recommendation: Reading A (brief reference).** 02 is primarily
high-level deployment / agent topology; effort plumbing is a thin
slice of agent-launch behavior worth naming briefly with a
cross-reference. Reading B's "skip entirely" risks future-Claude
reading 02 cold and missing the configuration surface; Reading C's
full-copy invites drift.

### Q4 — 07-agent-configs-phase-one.md merge content scope

The yaml-schema doc gets the most substantive merge:

- Effort field added to the schema reference (`effort: <Literal>` line)
- Per-role assignment table (PM/Engineer/Implementer xhigh, Tester high)
- Cross-reference to addendum for rationale
- Mike's interactive PM session non-effect framing per Q2-C

This is straightforward. No multi-Reading question; surface as
content-scope confirmation.

### Q5 — 11-engineer-bridge.md merge content scope

11 covers the bridge architecture — dispatcher + supervisors. The
merge content:

- Reference to `--effort` flag flow through `_default_launcher`
  (both dispatcher and supervisor variants)
- Note that effort is resolved per-dispatch from registry
  (kwarg-from-registry per F6.2 Q3-A); supervisor effort frozen
  across respawns per F6.2 Q9-A
- Cross-reference to addendum

No multi-Reading; content-scope confirmation. Brief — the launcher
detail is the load-bearing technical content; 11 is the
architectural framing that doesn't need duplicate prose.

---

## F7.3 — addendum content scope (per F1.3 + F1.4 + F6.6 dispositions)

If Q1 lands as Reading A or C (separate file
`opus-4-7-effort-and-thinking-addendum.md`), the addendum content:

1. **Header + status:** new addendum, established at
   M5-continuous-pm-driver commit 6.
2. **Per-role effort assignment table:** PM/Engineer/Implementer
   xhigh, Tester high. Rationale per role:
   - PM: agentic loop, intelligence-sensitive (continuous-mode
     planning + dispatch coordination).
   - Engineer: agentic loop, intelligence-sensitive (spec
     composition + dispatch lifecycle).
   - Implementer: code-change work, "minimum high for
     intelligence-sensitive" per Anthropic guidance; xhigh chosen
     for agentic-tool work.
   - Tester: narrower work shape (verifying assertion specs
     against artifacts); high reflects lower
     intelligence-sensitivity vs Implementer's xhigh.
3. **Operator-agency framing per F6.6.B:** full 5-value Literal
   admits operator tuning beyond defaults; medium/low for
   cost-conscious exploration; max for high-stakes overrides.
   Closed-Literal at config-load defends against typos; full
   surface admits exploration without schema migration.
4. **Adaptive thinking F1.4 distinction:** at the raw API,
   adaptive thinking is NOT implicit (must set
   `thinking: {type: "adaptive"}` explicitly); at the Claude Code
   subprocess boundary, CC v2.1.117+ defaults to adaptive on Opus
   4.7. Daemon launches CC subprocesses; daemon-side has no
   thinking-mode plumbing per F1.4 verification.
5. **Wrapper-doesn't-exist-in-Phase-1 finding (F1.4):** "If/when
   an Orchestrator-side API client emerges (currently none in
   Phase 1), legacy `thinking: {type: 'enabled'}` handling on
   4.7-bound requests must be silently stripped." For Phase 1,
   moot — no daemon-side API client exists.
6. **Mike's interactive PM session non-effect (F1.3 Q7):**
   yaml effort governs daemon-launched PM-continuous; Mike's
   interactive PM Claude Code session governed by client-side
   defaults (CC v2.1.117+ defaults to xhigh on Opus 4.7).
7. **CLI flag verification source:** verified via `claude --help`
   2026-05-05 during commit 5 draft; `--effort <level>` accepts
   low/medium/high/xhigh/max. Documented as ongoing-verifiable
   fact (re-verify when Anthropic doc + CLI surface evolves).
8. **Anthropic guidance citation:**
   - `platform.claude.com/docs/en/build-with-claude/effort`
   - `platform.claude.com/docs/en/build-with-claude/adaptive-thinking`
   - `code.claude.com/docs/en/model-config`
9. **Closing note:** Phase 2 may reopen if daemon-side API client
   emerges (F1.4 task-budgets context).

Length target per F1.3: ~150-300 lines.

---

## F7.4 — F2.1 estimate

F2.1 commit-6 estimate: +0 tests (docs-only).

No surface-as-finding evaluation needed — no test surface to
diverge from estimate. F2.1 trigger condition (per F6.6 metric
clarification: unique-function count primary) doesn't apply to
zero-test commits. Confirm at diff checkpoint that test count
remains 532 unchanged.

---

## F7.5 — anticipated pushbacks

Three weak points likely to surface at review:

1. **Q1 file-vs-section: F1.3 vs Mike's literal instruction
   discrepancy.** F1.3 (Followup-1) recommended separate file;
   Mike's commit-6-prep instruction names the existing utilization
   addendum file. My Reading C recommendation honors F1.3 but
   adds the cross-reference layer to address Mike's
   in-place-reference framing. If Mike's instruction was
   deliberate (fold related Opus-version-specific addendum
   content together), Reading B is the right call. If it was a
   slip, Reading A or C land. Surface explicitly so disposition
   is conscious, not absorbed.

2. **Addendum bloat under Reading B.** If Q1 lands as Reading B
   (fold into existing utilization addendum), the addendum doc
   adds ~150-300 lines to a 354-line file — doubles the file
   size. Existing utilization addendum scope is "subscription
   pacing"; folding Opus 4.7 effort + thinking expands scope to
   "utilization + model-version-specific config." Worth surfacing
   the scope-blur risk if Reading B lands.

3. **F1.3 Q7 placement (Q2-C) duplication risk.** Reading C
   surfaces the Mike-interactive-PM non-effect content in BOTH
   addendum + 07. Drift risk: future amendment to one doesn't
   propagate. Mitigation: 07's reference is a brief
   "see addendum § Mike's interactive PM session" pointer rather
   than a content copy; addendum is the canonical statement.
   Surface to confirm the cross-reference shape (pointer vs
   summary).

---

## F7.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q5 + the three anticipated-pushback items:

- New addendum doc (Q1-A or C) OR new section in existing
  utilization addendum (Q1-B), with content per F7.3
- 02-architecture.md brief reference per Q3
- 07-agent-configs-phase-one.md schema + per-role table + Q2
  cross-reference
- 11-engineer-bridge.md launcher reference per Q5
- (If Q1-A or C) yaml + code comment updates to cross-reference
  the new addendum file
- Test count stays 532; gates re-verify trivially

Diff checkpoint surfaces the docs in toto for review; commit 6
lands; commit 7 prep opens (continuous loop driver core +
get_window_state polling + check_budget_for_task routing +
iteration loop — the load-bearing implementation work).

**End of Followup-12.**

---

# Followup-13 — commit 7 prep checkpoint (continuous loop driver core — keystone implementation)

**Date:** 2026-05-06. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 7 — the architectural keystone.
**Prerequisites landed:** commits 1-6 (workflow_id α, ff47573;
pm_current_focus, b1ad27a; pm_spin_rationale_log, 433cef5;
audit_sample_log, 1e323ca; effort plumbing, 71d1e86; spec amendment,
1eeea94).

**Structural framing:** load-bearing implementation work. Five prior
commits laid the table-and-tool primitives PM's loop will consume
(focus, spin-rationale, audit-sample, window-state, burn-rate);
commit 7 wires PM's actual per-iteration loop behavior. The
keystone shape means **decision density highest in the chain so
far** — Mike's prep-instruction framing is right.

**Active carry-forward consumed at this prep** (per grounding doc §
Carry-forward notes "→ commit 7"):

- pm_spin row volume (commit 3 disposition): scales linearly with
  loop tick cadence per F4.2 Q1 Reading A (per-tick emission, no
  dedup). 30s cadence → ~2880 rows/day; 5s → ~17,280; 5min → ~288.
  Materially-different cadence reopens F4.2 Q1 disposition (per-tick
  dedup may become defensible at high volume) and softens/sharpens
  F4.2 Q6 audit-log non-integration tiebreaker observation.
- Sampler-trigger MCP call volume (commit 4 disposition): scales
  with cadence × per-iteration sampleable-event count (~2-5 events
  per iteration per F5.6.D extension framing). At 30s cadence with
  typical event mix: ~5,760-14,400 record_sampleable_event MCP
  calls/day. Materially-different cadence reopens F5.2 Q3 Reading
  A explicit-hook disposition (embedded-handler alternative may
  win on call-volume grounds at high cadence).

Both volume topics resolve at this commit's Q2 disposition (tick
cadence value).

**Active watch-for-pattern that may trip at this commit:**

1. **read_current_X rowid-DESC helper at threshold-of-three.**
   Existing instances: `read_current_pm_mode` (commit M5-pm-mode-
   and-window-state); `read_current_focus` (commit 2 of this
   chain). Loop driver at commit 7 may add a third instance for
   keep-alive logic (e.g., `read_most_recent_spin_rationale` for
   hysteresis or anti-flapping). Per F4.5.1 reframe (carried forward
   to closeout § Discipline-pattern observations): threshold-of-three
   is **trigger-to-evaluate not mandate-to-extract**. Surface
   extraction question explicitly per Q9 below if a third instance
   lands.
2. **Production-code-surface-for-testability at threshold-of-three
   (currently 2).** Existing instances: token_store=None defensive
   surface; _run_lifespan_body refactor. Commit 7's loop iteration
   mechanism likely needs test seam (injectable clock for testing
   iteration cadence; injectable snapshot-getter for fallback
   paths). If a third instance lands per Q6 below, surface as
   prep-checkpoint contradiction per the original BACKLOG entry.
3. **bridge → tools cross-layer imports at 2 going to 3-4.** Mike's
   prep-instruction framing anticipates pm_supervisor.py importing
   tools/budget.py and tools/pm_focus.py (instances 3 and 4),
   which would trip the layer-split reconsideration watch.
   Surface explicitly per Q4 below — my Reading D recommendation
   defends "minimal supervisor extension; no new bridge→tools
   imports" against Mike's framing on architectural-pattern-
   preservation grounds. Bidirectional-discipline territory; this
   may be the fifth instance.

---

## F8.1 — touch points

Per F2.1 row 7 + per Q4 disposition shape:

| File | Status | Touch shape |
|---|---|---|
| `src/meridian_orchestrator/tools/pm_loop.py` | NEW | MCP tool implementations: `wait_for_next_tick` per Q1; `get_loop_state` aggregator per Q3 (conditional). Pydantic schemas. Module-internal helpers. |
| `src/meridian_orchestrator/server.py` | EDIT | `_register_pm_loop_tools` helper + invoke from `build_server`. Wrapper exposes pm_loop tools as MCP. |
| `src/meridian_orchestrator/main.py` | EDIT | Lifespan-time wiring of cadence config (Settings.pm_loop_tick_seconds) into pm_loop tools via closure pattern (mirrors get_oauth_usage_snapshot). |
| `src/meridian_orchestrator/config.py` | EDIT | Add `pm_loop_tick_seconds: float` Settings field per Q2 disposition (default value disposition-dependent). |
| `src/meridian_orchestrator/bridge/pm_supervisor.py` | UNCHANGED IF Q4 Reading D; EDIT IF Q4 Reading A/B/C | Per Q4 disposition. Reading D: zero changes; loop logic lives in tools/pm_loop.py + pm.yaml prompt. Reading A/B/C: extends supervisor with tools/ imports + new state. |
| `config/agents/pm.yaml` | EDIT (per Q5) | system_prompt extension with continuous-mode loop instructions (call get_loop_state, decide, record_spin_rationale, wait_for_next_tick, repeat). |
| `tests/unit/tools/test_pm_loop.py` | NEW | Direct-handler tests for wait_for_next_tick + get_loop_state. Includes injectable clock per Q6. |
| `tests/integration/test_pm_loop_mcp_wrapper.py` | NEW | End-to-end MCP tests. |
| `tests/integration/test_pm_supervisor.py` | EDIT (conditional on Q4) | Extensions if pm_supervisor.py grows new state. |
| `docs/kickoff/06-mcp-tools-phase-one.md` | EDIT | New tool entries (wait_for_next_tick + get_loop_state + permission rows). |
| `docs/kickoff/02-architecture.md` | EDIT (small) | Possibly: row in Phase 1 specifics naming continuous loop driver. |
| `docs/kickoff/11-engineer-bridge.md` | EDIT (conditional) | Per Q4-A (if pm_supervisor extends), possibly add notes; per Q4-D, unchanged. |

---

## F8.2 — open questions (Q1-Q10)

### Q1 — Tick cadence enforcement mechanism

How does PM's continuous loop pace itself? PM is a Claude Code
subprocess; CC has no native sleep tool, no per-turn timer.

- **Reading A — server-side `wait_for_next_tick` MCP tool.** PM
  calls this tool at end of each iteration; server-side handler
  sleeps for `tick_seconds` (read from Settings via closure) then
  returns. Daemon-controlled cadence; PM's prompt instructions
  drive the call-pattern. Test seam: monkeypatch asyncio.sleep
  for deterministic tests. Standard pattern for CC-subprocess
  cadence control.
- **Reading B — PM-prompt-driven sleep instruction.** Prompt says
  "wait N seconds between iterations." Without a CC sleep tool,
  PM has no actual sleep mechanism — would need to do busy-work
  (e.g., issue MCP no-op calls in a loop) or skip sleep entirely.
  Not viable with current CC surface.
- **Reading C — event-driven loop (no fixed cadence).** PM iterates
  only when state changes (new task in queue, Mike-presence flip,
  dispatch outcome, etc.). PM blocks on a "wait_for_event" tool
  that returns when any of those events occur. Higher complexity
  (event-firing infrastructure); changes the loop's semantic
  shape from "tick" to "react."
- **Reading D — daemon-side throttling via subprocess control.**
  Daemon SIGSTOPs / SIGCONTs the PM subprocess at tick boundaries.
  Out-of-band cadence enforcement. Operationally fragile (signal
  handling mid-MCP-call risks subprocess corruption); rejected
  for OS-level complexity.

**Recommendation: Reading A.** Server-side MCP-tool sleep is the
established pattern for CC-subprocess server-controlled blocking
behavior (precedent: would mirror how get_window_state's
snapshot-staleness gates pace_recommendation, though not exactly
the same shape). Test seam straightforward. Cadence configurable
via Settings. Reading C is interesting for Phase 2 (event-driven
loops have better capacity utilization properties) but is a
materially different design surface; Phase 1 tick-cadence is the
right starting point per F2 Section 2 framing.

### Q2 — Tick interval default value

Mike's carry-forward names three operating points: 30s (anticipated
baseline); 5s (tight responsiveness); 5min (cost-conscious). What's
the Phase 1 default?

- **Reading A — 30s.** Anticipated baseline. ~2880 pm_spin rows/day;
  ~5,760-14,400 sampler-trigger MCP calls/day. Within commit 3 +
  commit 4 framing tolerance. Operator can tune via Settings env
  var.
- **Reading B — 60s.** Conservative; halves volumes vs Reading A.
  ~1440 rows/day; ~2880-7200 MCP calls/day. Operator tunes down to
  30s (or lower) for responsiveness.
- **Reading C — 5min (300s).** Cost-conscious starting point.
  ~288 rows/day; ~576-1440 MCP calls/day. Operator tunes down for
  responsiveness as workload demands.
- **Reading D — adaptive cadence.** Loop runs at tight interval
  (e.g., 30s) when active (queued work + Mike absent); tight
  interval (5s) when Mike interactive; loose interval (5min) when
  idle. Server-side adaptive; PM doesn't control it. Higher
  complexity.

**Recommendation: Reading B (60s default).** Argument: 30s is
"anticipated baseline" but the volume framing assumed 30s without
discounting that commits 3-4 didn't yet have empirical operator
data. 60s halves volume across both watch-for-pattern surfaces
(pm_spin row growth + sampler-trigger MCP volume) while remaining
responsive (Mike's interactive-mode auto-flip happens within a
single tick window for any reasonable workload pattern). Reading
A's 30s is operator-tunable to; conservative-then-tune-down is
better than aggressive-then-tune-up for Phase 1's "no empirical
data yet" state. Reading C's 5min is too loose for the
continuous-loop framing; Reading D's adaptive cadence is Phase 2
shape.

**Carry-forward resolution:** at 60s default, both volume framings
from commits 3-4 hold (within "operational noise" / "marginal MCP
volume" bounds). F4.2 Q1 (per-tick no dedup for pm_spin) doesn't
reopen. F5.2 Q3 (explicit hook for sampler) doesn't reopen. The
carry-forward sleeps unless operator tunes cadence below ~10s.

### Q3 — Loop state aggregator MCP tool

PM's per-iteration reads (per F2 Section 2): get_window_state ×2
(subscription_5h + subscription_7d_opus), get_burn_rate, pm_mode_log
(via get_window_state's current_pm_mode field), pm_current_focus
(via get_current_focus). That's 3-4 separate MCP calls per iteration
in the disaggregated case.

- **Reading A — `get_loop_state` aggregator.** New MCP tool that
  bundles all per-iteration reads into one structured response.
  PM's prompt says "call get_loop_state at start of each
  iteration." Reduces per-iteration MCP call count from 3-4 to 1.
  Schema cost: new Pydantic output model with nested fields.
- **Reading B — disaggregated; PM calls individual tools per
  iteration.** No new aggregator tool. PM's prompt enumerates the
  reads. Higher MCP call volume per iteration.
- **Reading C — aggregator optional; both shipped.** PM's prompt
  recommends aggregator; individual tools available for ad-hoc
  reads. Maximum flexibility; maximum surface.

**Recommendation: Reading A (aggregator).** Per-iteration call
count matters at scale (60s cadence × 4 calls = 5,760 reads/day
that could be 1,440 with aggregator). The aggregator mirrors
get_window_state's existing aggregation pattern (window state +
PM mode + age in one read). Schema cost is one-time; runtime
benefit is permanent. Reading B is only defensible if the
aggregator's shape would be wrong (e.g., the four reads have
different freshness semantics that bundling would obscure) —
not the case here, since all are point-in-time snapshots PM
consumes for one-decision purposes.

### Q4 — pm_supervisor.py scope at commit 7 (LOAD-BEARING)

This is the load-bearing question per F8 anticipated pushback.
What does bridge/pm_supervisor.py change at commit 7?

Mike's prep-instruction framing names "loop driver in
bridge/pm_supervisor.py consumes tools/budget.py and tools/pm_focus.py"
as the watch-trigger expectation (instances 3 and 4 of bridge→tools
imports). But the M5-pm-supervisor-base disposition (P-2 Reading B,
recorded in pm_supervisor's docstring at lines 20-29) established
the pattern: **daemon owns lifecycle; subprocess owns reads via
prompt instructions.**

The P-2 disposition is explicit:
> Architectural separation: daemon owns lifecycle (spawn / heartbeat
> / respawn / token); subprocess owns startup reads via prompt
> instructions.

Loop driver concerns are subprocess-side reads + decisions; the
daemon-side supervisor's role is unchanged at commit 7 if the
P-2 pattern holds.

- **Reading A — supervisor extends with tools/budget.py +
  tools/pm_focus.py imports** (per Mike's prep framing; trips
  bridge→tools watch at instances 3-4). Supervisor reads window
  state for compaction-timing decisions; reads current focus for
  diagnostic surfacing. Triggers F2.4 layer-split reconsideration.
- **Reading B — supervisor imports only tools/budget.py for
  compaction-timing decisions** (instance 3; doesn't trip the
  4-instance threshold). Compaction-timing actually lands at
  commit 8, not commit 7; pulling this in at 7 is premature.
- **Reading C — supervisor extends with tools/pm_loop.py imports
  only.** Loop infrastructure consumed at supervisor layer (e.g.,
  for surfacing iteration metrics in dashboard). Adds one
  bridge→tools instance.
- **Reading D — supervisor unchanged at commit 7.** Loop logic
  lives entirely in tools/pm_loop.py + pm.yaml prompt extension.
  Honors P-2 architectural separation; preserves "daemon owns
  lifecycle, subprocess owns reads" pattern. Zero new
  bridge→tools imports; layer-split watch doesn't trip at this
  commit.
- **Reading E — supervisor extends with NEW non-tools state
  only** (e.g., loop tick cadence config; audit-friendly
  per-iteration metrics like "last seen iteration timestamp").
  No new tools/ imports; daemon-side state grows but stays
  within the watchdog scope. Compromise: supervisor knows about
  the loop's existence without consuming tools/.

**Recommendation: Reading D.** P-2 pattern is the right
architectural commitment to preserve. Bridge→tools imports at
instances 3-4 would represent material drift from the
"supervisor as watchdog" framing established at M5-pm-supervisor-
base. The loop's daemon-side state (cadence config) flows through
main.py's lifespan into tools/pm_loop.py via closure pattern
(mirrors `get_oauth_usage_snapshot` from M5-pm-mode-and-window-state
commit 7) — no supervisor involvement needed.

**Bidirectional-discipline territory:** Mike's prep-instruction
framing anticipated Reading A (3-4 imports). Reading D contradicts
Mike's framing on architectural-pattern-preservation grounds. If
Mike's framing was the substantive expectation (supervisor SHOULD
take on more responsibility at commit 7 for some reason I'm
missing), Reading A or B/C/E lands; surface as F8.5.1 anticipated
pushback so the disposition is conscious.

If Reading D holds: zero new bridge→tools instances at commit 7;
watch stays at 2; doesn't trip layer-split threshold.

### Q5 — pm.yaml system_prompt extension scope

PM's continuous-mode loop needs prompt instructions. Current
system_prompt covers high-level continuous-mode behavior;
commit 7 adds operational specifics (call get_loop_state,
record_spin_rationale, wait_for_next_tick, repeat).

- **Reading A — substantive extension at commit 7.** Add a
  dedicated "continuous-mode iteration loop" section (~30-50 lines)
  with explicit per-tick instruction sequence + decision
  guidance.
- **Reading B — minimal extension at commit 7.** Brief paragraph
  pointing at the new tools; rely on PM's general continuous-mode
  framing for the rest.
- **Reading C — extension splits across commits 7 and 8.** Commit
  7 covers loop iteration; commit 8 (compaction + handoff) covers
  compaction-prep and handoff-doc instructions. Both substantial.
- **Reading D — extension lands at commit 8 alongside compaction
  prompt instructions.** Commit 7 ships tools/pm_loop.py without
  prompt updates; PM's loop is implicit (default CC behavior of
  reading available tools and using them). Defers all prompt work
  to commit 8.

**Recommendation: Reading A (substantive extension).** PM's loop
behavior requires explicit prompt guidance — without it, PM's
default behavior is "respond to Mike's input then stop" (CC's
default mode), not "iterate until shutdown_recommended." The
prompt extension IS the behavioral driver; tools/pm_loop.py
provides the surface but doesn't drive use. Reading D defers the
load-bearing instruction-set behind a tools-only commit, which
ships infrastructure without consumer; commit 7's integration
test surface would be poor.

Reading C's split has merit if compaction prompt instructions
materially expand commit 8 — surface in commit 8 prep if so. At
this commit, ship the loop instructions in scope.

### Q6 — Production-code-surface-for-testability — clock injection

`wait_for_next_tick` (Q1 Reading A) sleeps. Tests need
deterministic cadence assertions; can't actually wait 60s per test.

- **Reading A — monkeypatch asyncio.sleep in tests.** Standard
  Python testing pattern; no production-code change beyond what
  Q1 Reading A's tool already provides.
- **Reading B — explicit clock injection seam.** `wait_for_next_tick`
  takes optional `_clock_sleep` parameter (defaults to
  `asyncio.sleep`); tests inject a fake. Production-code-surface
  expansion for testability — would be the third instance of the
  watch-for-pattern.
- **Reading C — Settings-based override.** Test fixtures set
  `MERIDIAN_ORCH_PM_LOOP_TICK_SECONDS=0.001` to make the sleep
  near-instant. No production-code-surface change; tests use
  small but real sleeps.

**Recommendation: Reading A (monkeypatch).** Established Python
pattern; doesn't expand production-code-surface; doesn't trip the
testability watch. Commit 4's Sampler tests already use
monkeypatch.setattr for asyncio.create_subprocess_exec; same
discipline applies here. Reading B's explicit injection is more
"correct" by some testability framings but trips the watch
without offsetting benefit. Reading C's small-but-real sleeps
introduce flakiness on slow CI machines.

If Reading A holds: zero new testability-surface instances at
commit 7; watch stays at 2; doesn't trip threshold.

### Q7 — Mike-interactive-mode handling in the loop

When pm_mode_log indicates Mike is interactive, what does the
continuous loop do?

- **Reading A — pause iteration entirely.** Loop calls
  wait_for_next_tick but skips all per-iteration writes (no
  spin_rationale row; no focus changes). Resumes on next tick if
  Mike no longer interactive.
- **Reading B — continue iterating with `mike_presence` rationale.**
  Loop emits spin_rationale row with `decision='kept_alive'` +
  `rationale_tag='mike_presence'` per F4.2 Q3 disposition's
  rationale taxonomy. Continues focus state-tracking; defers any
  dispatch-affecting work (no new submit_task at this commit
  anyway since M4d not shipped).
- **Reading C — shut down PM-continuous subprocess.** Loop signals
  shutdown; supervisor terminates PM. Interactive PM (Mike's
  Claude Code session) takes over. Aggressive; risks losing
  continuous-loop state.

**Recommendation: Reading B.** F4.2 Q3 disposition explicitly
named `mike_presence` as one of the four rationale_tag closed-enum
values; Reading B uses that as designed. The loop's
narration/fitness telemetry stays continuous (operator can later
analyze "how often was PM kept alive due to Mike presence");
Reading A's gap-during-interactive loses signal. Reading C is
addendum-incompatible (PM-continuous is meant to stay alive
aggressively per orchestrator-utilization-addendum).

### Q8 — Keep-alive vs shutdown_recommended decision logic

What inputs feed the per-tick decision? PM's prompt drives the
actual decision; the question is what state the loop_state
aggregator surfaces for the decision.

- **Reading A — full state input.** queue_depth (M4d future,
  currently always 0) + Mike presence + pace_recommendation +
  recent dispatch outcomes (from get_recent_dispatches).
- **Reading B — minimal at commit 7; expand at M4d.** pace_recommendation
  + Mike presence. Defer queue-depth signal until M4d ships
  get_queue.
- **Reading C — pace_recommendation only.** Bottoms out: PM stays
  alive until subscription_5h hits conserve threshold (signaled
  via pace_recommendation='conserve'); shutdown_recommended at
  conserve. Compaction-prep at commit 8 picks up there.

**Recommendation: Reading B.** Reading C is too narrow — Mike-presence
detection is a load-bearing input for the loop's spin-rationale
classification (F4.2 Q3 disposition explicitly named
`mike_presence`); excluding it would invalidate one quarter of the
rationale taxonomy. Reading A's queue_depth is M4d-shipped, not
available at commit 7 — including in the aggregator's output
schema requires either a stub field or a forward-compatible
shape. Reading B is the right Phase 1 scope: pace_recommendation
(routing pacing) + Mike presence (load-aware throttling). Defer
queue_depth to M4d.

### Q9 — read_current_X helper extraction at threshold-of-three

Per active watch-for-pattern: `read_current_pm_mode` (1) +
`read_current_focus` (2) exist; commit 7 may add a third.

Question: does commit 7 add a `read_most_recent_spin_rationale`
or similar third-instance helper?

- **Reading A — yes, add helper for hysteresis logic.** Loop's
  decision logic reads last spin_rationale to avoid rapid flapping
  between rationale_tags. Third instance lands; per F4.5.1 reframe,
  surface extraction-evaluation question.
- **Reading B — no new helper at commit 7.** Loop's decision
  state lives in PM's prompt (each tick decides afresh); no
  daemon-side hysteresis needed. Zero new bridge/tools state.
  Watch stays at 2.
- **Reading C — helper added but as private utility within
  tools/pm_loop.py** (not at module-toplevel like
  `read_current_pm_mode`). Doesn't count as "third instance" of
  the established pattern — it's a per-module implementation
  detail, not a reusable shared helper.

**Recommendation: Reading B.** Hysteresis logic is PM-prompt's
responsibility, not the loop infrastructure's. PM's prompt can
read its own previous decision via the audit_log → get_audit_log
(M6) path or via inspection of pm_spin_rationale_log via direct
SQL (not currently MCP-exposed); but the loop infrastructure
shouldn't pre-bake hysteresis assumptions. Reading B keeps the
loop's role at "surface state + record decisions"; PM's prompt
does the smart logic.

If Reading B holds: zero new read_current_X instances at commit 7;
watch stays at 2; threshold-of-three doesn't trip yet.

### Q10 — Loop iteration write-tool aggregation

Each iteration may write multiple things: pm_spin_rationale_log
row (per-tick); pm_current_focus updates (on focus changes);
audit_sample_log writes (when sampleable events occur); audit_log
rows (existing tools' audit emissions).

Does commit 7 add a `record_loop_iteration` aggregator that
bundles per-tick writes?

- **Reading A — aggregator write tool.** New MCP tool that takes
  per-iteration write payload (decision, rationale_tag, optional
  focus changes, optional sample triggers) and dispatches to
  pm_spin / pm_focus / sampler in one call. Reduces MCP call
  count for write-side per iteration.
- **Reading B — no aggregator; PM calls existing tools
  individually.** record_spin_rationale + complete_current_focus
  + record_sampleable_event are separate calls. Higher per-tick
  MCP call volume but cleaner separation.
- **Reading C — aggregator optional.** Both shipped; PM prompt
  recommends aggregator for typical iterations.

**Recommendation: Reading B.** Write-side aggregation is harder
than read-side aggregation because the writes have distinct
audit-trail semantics — pm_spin_rationale_log row IS its own
durable record (commit 3 Q6 disposition); rolling it into a
parent aggregate audit row would re-litigate that disposition.
Read-side aggregation (Q3 Reading A) bundles point-in-time reads
which all have the same freshness shape; write-side aggregation
would conflate distinct event semantics. Reading B keeps writes
explicit; per-iteration MCP call count slightly higher (~3-4 vs
1) but each call carries clear semantic load.

---

## F8.3 — watch-for-pattern check

| Pattern | Status pre-commit | Commit 7 instance (per recommendations) | Disposition |
|---|---|---|---|
| Closed-enum CHECK + sentinel test | 9+ instances; established convention per F2.5 graduation | Possibly +0; new MCP tool inputs use existing Literals (PmMode, etc.). Surface if a new closed-enum lands. | No threshold concern. |
| `read_current_X` rowid-DESC helper | 2 | +0 per Q9 Reading B (no new helper at commit 7). Threshold-of-three watch continues; possibly trips at commit 8 (compaction-related read of focus state). | No trip at commit 7. |
| `bridge/` → `tools/` cross-layer imports | 2 | +0 per Q4 Reading D (supervisor unchanged). Mike's prep framing anticipated +2; my recommendation contradicts on P-2 architectural-pattern grounds. **Anticipated pushback F8.5.1.** | No trip at commit 7 if Reading D holds. |
| Production-code-surface-for-testability | 2 | +0 per Q6 Reading A (monkeypatch asyncio.sleep; no explicit injection seam). Threshold-of-three watch continues. | No trip at commit 7. |
| Audit-action `pm.<surface>.<verb>` namespace | 5 | +0 (existing actions reused; no new audit shape). | No expansion. |
| F2.1 surface-as-finding rule | 5 invocations | Likely +1 at commit 7 draft. F2.1 estimate +18 unique. Trigger condition (>200% over via unique-function metric per F6.6 clarification) at >36 unique tests — possible at this commit's keystone scope but not certain. | Surface at diff checkpoint. |
| Cadence-volume carry-forward (commits 3-4) | Active | Resolved per Q2 disposition. At Reading B (60s default), both commit-3 and commit-4 framings hold. | Carry-forward closes. |

---

## F8.4 — test-target estimate

F2.1 estimate: +18. Working breakdown under recommended dispositions
(Q1-A, Q2-B, Q3-A, Q4-D, Q5-A, Q6-A, Q7-B, Q8-B, Q9-B, Q10-B):

| Layer | Count |
|---|---|
| `wait_for_next_tick` MCP handler tests (sleep behavior + cadence config + cancel-safety) | 3-4 |
| `get_loop_state` aggregator handler tests (state composition + freshness + Mike-presence reflection + per-bucket data) | 4-5 |
| Pydantic schemas (LoopStateOutput shape; closed-enum sentinels if any) | 1-2 |
| MCP wrapper integration (PM-allowed × 2 tools; non-PM rejected × 3 parametrized × 2 tools) | 2 unique / 8 collected |
| Settings `pm_loop_tick_seconds` default + range validation | 2 |
| Lifespan wiring (Sampler-style closure for pm_loop tools) | 1 |
| Q4-D invariant pin (test asserts pm_supervisor.py has no new tools/ imports — sentinel against future drift) | 1 |
| Q9-B invariant pin (test asserts no new read_current_X helper added) | 1 |
| Q6-A test pattern (monkeypatch asyncio.sleep) — used in cadence tests | (covered above) |
| **Total under recommendations** | **15-19 unique / 20-26 collected** |

**vs F2.1 estimate of +18 unique:** roughly aligned (15-19 / 18 = 83-106%).
Per closeout pattern observation, expect actual to land slightly
higher with parametrize blow-ups. F2.1 trigger condition (>200%
unique-function metric) at >36 unique — well above expected actual.
**Trigger should not fire at commit 7 under recommendations.**

If Q4 lands as Reading A/B/C (supervisor extends), test count
grows by +3-5 unique for supervisor extension tests; pushes total
to ~18-24. Still under trigger threshold.

---

## F8.5 — anticipated pushbacks

Five weak points likely to surface at review:

1. **Q4 Reading D contradicts your prep-instruction framing.** Your
   prep instruction said "loop driver in bridge/pm_supervisor.py
   consumes tools/budget.py and tools/pm_focus.py" — anticipating
   Reading A (instances 3-4 of bridge→tools, tripping the layer-
   split watch). My Reading D recommendation defends "supervisor
   unchanged at commit 7" on P-2 architectural-pattern grounds
   (recorded in pm_supervisor's own docstring lines 20-29). If
   your framing was the substantive expectation (supervisor SHOULD
   take on more loop-driver responsibility), surface why so
   Reading A/B/C/E lands; if Reading D is right, your prep
   instruction may be the fifth bidirectional-discipline instance
   operating in the third direction (prep-instruction error).
   Genuinely contestable; test the precedent's conditions.

2. **Q1 Reading A `wait_for_next_tick` introduces a new MCP tool
   pattern (server-side blocking).** No existing Phase 1 MCP tool
   blocks server-side for arbitrary durations. Closest analog is
   none — even get_window_state returns immediately. The blocking-
   behavior pattern is novel; future reviewers may push back on
   "should we use a different mechanism" (e.g., HTTP long-poll;
   server-sent events). For Phase 1, blocking MCP is the simplest
   fit (CC subprocess blocks on its own MCP call regardless of
   server-side mechanism); flagging the pattern for future
   architectural review.

3. **Q2 Reading B 60s vs Reading A 30s default cadence.** My
   recommendation diverges from the carry-forward's "anticipated
   30s" baseline. Argument: anticipation isn't operator data;
   conservative-then-tune-down is better than aggressive-then-
   tune-up at Phase 1. Reviewing-Claude may push back: "30s was
   already framed as anticipated baseline; defaulting to 60s
   doubles the loop's responsiveness lag without offsetting
   operator benefit." Both readings defensible; the disposition
   has trajectory implications for commits 3-4 watch-for-pattern
   framings.

4. **Q5 Reading A substantive prompt extension may bleed into
   commit 8 scope.** Loop iteration prompt + compaction prompt are
   conceptually distinct but operationally interleaved (compaction
   triggers from loop iteration's pace_recommendation read). If
   the prompt extension at commit 7 has to anticipate compaction
   cleanly, scope creeps toward commit 8. Reading C's split-across-
   commits pattern is defensible if scope creep materializes.

5. **Q10 Reading B leaves per-iteration MCP call count at 3-4.**
   Each iteration: get_loop_state (1) + record_spin_rationale (1)
   + wait_for_next_tick (1) + optional declare_current_focus /
   complete_current_focus / record_sampleable_event (0-3). Mean
   ~4-5 calls/iteration. At 60s cadence: ~5,760-7,200 MCP
   calls/day total. Reading A's write-aggregator would reduce
   this; my reasoning rejected on audit-trail-semantics grounds.
   If MCP call volume becomes an operational concern (commits
   8-9 may surface this), Reading A revisits.

---

## F8.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q10 + the five anticipated-pushback
items:

- `tools/pm_loop.py` drafted with Q1+Q3+Q6 dispositions
- `Settings.pm_loop_tick_seconds` per Q2 (default value
  disposition-dependent; range validation)
- `main.py` lifespan wires cadence config via closure pattern
- `server.py` `_register_pm_loop_tools` for the new MCP tools
- `config/agents/pm.yaml` system_prompt extension per Q5
- `bridge/pm_supervisor.py` per Q4 (Reading D: unchanged; A/B/C/E:
  extended)
- Tests per F8.4 estimate (~15-19 unique / 20-26 collected)
- Spec amendments: 06-mcp-tools (new tool entries +
  permission rows); possibly 02 (small reference) + 11 (only if
  Q4 brings supervisor changes)
- F2.1 divergence assessment surfaced at diff checkpoint per
  unique-function metric (per F6.6 clarification)
- Carry-forward "→ commit 7" entry RESOLVED in grounding doc §
  Carry-forward notes (cadence-volume framings hold under Q2-B
  60s default); update entry header to "RESOLVED 2026-05-06"

Diff checkpoint surfaces commit-by-commit; commit 7 lands; commit
8 prep opens (compaction-trigger + handoff-doc generation +
PMSupervisor._reconstruct_state extending to read most-recent
handoff). Commit 8's prep will revisit Q4 (supervisor scope grows
materially at compaction-trigger commit) and Q9 (read-helper
threshold may trip if compaction needs handoff-doc-state read).

**End of Followup-13.**

---

# Carry-forward notes

**Convention established 2026-05-04 (Decision 5 disposition, commit 3 prep):**
Scope-determines-location routing for carry-forward notes between
commits / sub-milestones / phases:

- **Same-sub-milestone carry-forwards** (note from commit N to commit
  M within the same sub-milestone) live here in the grounding doc
  under this `## Carry-forward notes` section. The grounding doc is
  already opened at every commit prep within the sub-milestone; this
  section is the canonical surface.
- **Cross-sub-milestone carry-forwards** (note from sub-milestone X
  to sub-milestone Y) live in `BACKLOG.md` under a dedicated
  `## Carry-forward to future sub-milestones` section, distinct from
  the existing `## Deferred` section. **Lazy-created** on first
  cross-boundary entry — empty headers are
  documentation-without-purpose. When the first cross-sub-milestone
  carry-forward materializes, the BACKLOG.md section gets created
  with that entry; this convention reference holds until then.

**Format for entries** (both surfaces):

```
### → commit N (or → <sub-milestone-name>): <topic>

<one-paragraph carry-forward note + cross-reference to detail-prose
location, e.g., "Detail prose: F4.2 Q1 disposition pin in this
grounding doc.">
```

**Grep targets**: `Carry-forward`, `→ commit N`, `→ <sub-milestone-name>`.

---

### → commit 7: pm_spin row volume + sampler-trigger MCP call volume scale with loop tick cadence (from commits 3 + 4)

Two volume topics for commit 7's tick-cadence decision; bundled
under one carry-forward header since both scale linearly with the
same parameter.

**Topic 1 — pm_spin_rationale_log row volume (from commit 3).**
pm_spin_rationale_log emits one row per planning iteration per F4.2
Q1 Reading A disposition (per-tick, no dedup). Row volume scales
linearly with the loop's tick cadence, which **commit 7 actually
decides** (F2 Section 2 framed "30s? — surface for prep"; the F2.1
chain places loop driver core at commit 7). At a 30s cadence,
volume is ~2880 rows/day. Materially different cadence shifts the
volume framing:

- 5s cadence → ~17,280 rows/day. Volume becomes operationally
  significant; retention / coalescing becomes a real question. F4.2
  Q1 disposition reopens.
- 5min cadence → ~288 rows/day. Volume is trivial. The Q6 audit-log
  volume tiebreaker observation softens.

**Topic 2 — sampler-trigger MCP call volume (from commit 4, F5.2 Q3
Reading A + F5.6.D extension).** record_sampleable_event MCP tool
fires once per sampleable event PM observes during a tick (each
pm.current_focus.completed / contract_violation / superseded /
spin_rationale.recorded / dispatch.complete_called PM emits or
observes from Engineer's complete_dispatch outcomes). At nominal
loop cadence with typical event mix, this adds ~2-5 MCP calls per
iteration — volume budget concern at high tick rates. Reading B
(embedded in handlers) was rejected at F5.2 Q3 on selectivity
grounds; if MCP-call-volume budget pressure surfaces at commit 7,
the embedded alternative revisits.

**Carry-forward to commit 7 prep:** when tick cadence is decided,
explicitly name the cadence-volume interaction in the prep checkpoint.
Surface whether the chosen cadence triggers reopening of:

- F4.2 Q1 (per-tick no dedup for pm_spin_rationale_log) — if volume
  is operationally significant
- F4.2 Q6 (no audit_log integration on pm_spin_rationale_log) —
  volume is second-order observation per F4.5.3, but high-volume
  scenarios may make it load-bearing
- BACKLOG entry for `pm_spin_rationale_log` retention / coalescing
  policy — independent of dispositions; pure storage-volume question
- F5.2 Q3 (explicit-hook MCP tool for sampler) — if MCP-call-volume
  budget is constrained at chosen cadence, embedded-handler
  alternative (Reading B) revisits

**Detail prose**: F4.2 Q1 disposition pin in this grounding doc
(grep "DISPOSITIONED 2026-05-04: Reading A accepted" within F4.2
Q1); F5.2 Q3 disposition prose for the sampler-trigger interface.

**DISPOSITIONED 2026-05-06 (commit 7 prep, F8.2 Q2-B):** Anticipated
30s at commits 3-4 prep; dispositioned to 60s at commit 7 per
conservative-then-tune-down framing. Volume framings from commits
3-4 (pm_spin operational noise at ~2880 rows/day under 30s baseline
→ ~1440 rows/day under 60s; sampler-trigger marginal MCP volume
at ~5,760-14,400 calls/day under 30s → ~2,880-7,200 calls/day under
60s) hold under 60s with margin. **Re-evaluation trigger:** empirical
data showing PM under-responsiveness at 60s warrants tune-down to
30s or lower.

### → commits 8-9: per-iteration MCP write call count at 3-4 (from commit 7)

Per F8.2 Q10-B disposition (write-side aggregation rejected on
audit-trail-semantics grounds): per-iteration MCP write call
count at typical iteration is spin_rationale (1) + sample_event
(0-1) + complete_current_focus (0-1, on completion ticks) ≈ 1-3
writes per iteration; ~2,880-7,200 write calls/day under 60s
cadence baseline (Q2-B disposition).

**Re-evaluation trigger:** if commits 8-9 add write events that
push per-iteration count materially higher (e.g., handoff-doc
generation at compaction trigger emits multiple writes), revisit
F8.2 Q10 write-tool aggregation rejection. Aggregator was rejected
on audit-trail-semantics grounds; if call volume becomes operational
concern, evaluate hybrid (aggregator for batched-emit-of-related-
writes within one transaction; per-tool emit for distinct-semantic
writes).

**Detail prose**: F8.2 Q10 disposition prose; F8.2 Q3 read-aggregation
asymmetric framing (read aggregation good per Q3-A; write
aggregation bad per Q10-B).

**DISPOSITIONED 2026-05-07 (commit 8 prep, F9.2 Q9-A / F9.7
disposition table):** Compaction is once-per-5h-cycle event under
healthy operation; per-tick write count at conserve-trigger ticks
spikes to 3-5 (handoff_written audit + signal_compaction audit +
pm_spin row + 0-1 sample-event row) but compaction frequency is
~0.001 of ticks under 60s cadence. Average per-tick write count
effectively unchanged from commit 7 baseline (1-3 writes per
typical tick). F8.2 Q10 write-tool aggregation rejection holds.
**Re-evaluation trigger absent.** Carry-forward "→ commits 8-9:
per-iteration MCP write call count at 3-4" RESOLVED at commit 8
prep. Re-evaluation reactivates only if empirical operation shows
compaction firing more frequently than once per 5h cycle.

---

# Followup-14 — commit 8 prep checkpoint (compaction-trigger + handoff-doc generation + handoff-read mechanism)

**Date:** 2026-05-07. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 8 — compaction boundary work; commit 7's
two architecturally-pressured invariants face their re-evaluation
pressure here.
**Prerequisites landed:** commits 1-7 (workflow_id α, ff47573;
pm_current_focus, b1ad27a; pm_spin_rationale_log, 433cef5;
audit_sample_log, 1e323ca; effort plumbing, 71d1e86; spec amendment,
1eeea94; continuous loop driver core, af25497).

**Structural framing.** Commit 8 wires PM's compaction lifecycle:
detection (loop-side polling for `pace_recommendation == 'conserve'`),
handoff-doc authoring, supervisor signal, post-respawn handoff-read.
Two architectural pressure points surface here:

1. **Spec contradiction on handoff-read mechanism location.** Multiple
   recently-shipped sources (supervisor_base.py docstring;
   engineer_supervisor.py docstring; 11-engineer-bridge.md:753;
   F2.1 row 8 itself; Mike's prep-instruction framing) describe
   `PMSupervisor._reconstruct_state` overriding to read the handoff
   doc daemon-side. One recently-shipped source (pm_supervisor.py
   P-2 Reading B disposition + pinning invariant test) says the
   opposite — handoff-read is subprocess-prompt concern. Surfaced
   as F9.0 below before any other dispositions because it shapes
   touch-points + every downstream Q.

2. **F8.6.A Reading D defense survival across the compaction
   boundary.** Reading D at commit 7 ("supervisor unchanged") was
   defended on (1) symmetric-with-EngineerSupervisor, (2)
   single-responsibility lifecycle-vs-iteration, (3) loop-state
   config flow via main.py lifespan closure. At commit 8 the
   compaction boundary tests each defense; defense (1) breaks
   explicitly per `11-engineer-bridge.md:543` ("PM compaction with
   handoff doc is a different mechanism that ships at M5 — Engineer
   does not need it because PM's altitude-1 reasoning carries
   narrative context the DB doesn't capture"). F9.0 evaluates whether
   the remaining defenses (2)+(3) carry on independent grounds
   despite (1) breaking.

**Active carry-forward consumed at this prep** (per grounding doc §
Carry-forward notes "→ commits 8-9"):

- per-iteration MCP write call count at 3-4 (from commit 7,
  F8.2 Q10-B disposition). Commit 8 adds compaction-trigger writes
  (handoff-doc generation; signal_compaction emit; pm_spin row with
  conserve-time rationale). Per-tick write count at typical
  (non-conserve) tick stays at 1-3; per-tick write count at
  conserve-trigger tick spikes to 3-5. Compaction is a once-per-5h
  event under healthy operation, not per-tick. **Average write
  count effectively unchanged.** F8.2 Q10 aggregator-rejection does
  NOT reopen on volume grounds. Re-evaluation trigger from carry-
  forward header (handoff-doc generation pushing per-iteration write
  count materially higher) does not fire — the handoff-write happens
  at compaction-trigger ticks only, not every tick. See F9.2 Q9
  re-evaluation below for explicit disposition.

**Active watch-for-pattern that may trip at this commit:**

1. **bridge → tools cross-layer imports at 2.** Per F9.0 below's
   recommendation (Reading Y), supervisor unchanged → +0 imports;
   watch stays at 2. Per Reading X, supervisor would import
   filesystem-handoff-read helpers — likely not from tools/ layer
   directly (filesystem reads are stdlib), so doesn't trip the
   specific bridge → tools watch even under Reading X. Watch shape
   for tools/pm_handoff.py's plumbing into pm_supervisor.py is
   distinct: NEW pattern surface "supervisor consumes tools/-layer
   modules directly" that would be instance 1 if it landed under
   Reading X. Surface explicitly per F9.0 disposition.

2. **read_current_X rowid-DESC helper at 2.** Q9-from-commit-7
   threshold-of-three watch may appear to trip if commit 8 introduces
   `read_most_recent_handoff` as a third instance of "read-most-recent-X"
   pattern. Per F4.5.2 reframe (threshold-of-three is trigger-to-
   evaluate, not mandate-to-extract), the abstraction-unit evaluation
   distinguishes: `read_current_pm_mode` + `read_current_focus` share
   the SQL-rowid-DESC abstraction unit; `read_most_recent_handoff` is
   filesystem-directory-listing-by-timestamp. Different mechanism,
   different semantic load. Per F9.2 Q8 below: handoff-read does NOT
   count as third instance of the SQL-rowid-DESC pattern. Watch stays
   at 2 for that pattern. A separate pattern "read most-recent file
   by timestamp from substrate directory" emerges at instance 1, no
   threshold concern.

3. **Production-code-surface-for-testability at 2.** Commit 8's
   handoff-read tools accept directory-path closure (mirrors
   commit-7 cadence-config closure). Tests use tmp_path fixture +
   monkeypatch over the closure — established Python pattern, no
   new production-code seam. Watch stays at 2 if recommendations
   hold.

4. **Architectural-patterns-to-review** (BACKLOG.md § Architectural
   patterns to review, established 2026-05-06). First-instance
   pattern from commit 7: "server-side blocking MCP tool"
   (`wait_for_next_tick`). Commit 8 may add a second blocking-MCP
   tool if compaction-signal-then-block pattern lands as
   `wait_for_compaction_completion` or similar. Per F9.2 Q4
   recommendation below: NO, commit 8's signal_compaction surface
   is non-blocking (subprocess exits cleanly post-signal; daemon
   watches exit). Pattern stays at 1 instance.

5. **Code-inspection sentinel observability convention** (commit 7
   draft, F2.5). Two sentinels active:
   `test_pm_supervisor_does_not_import_tools_budget_or_pm_focus`
   (Q4 invariant) and `test_pm_loop_module_does_not_define_read_current_helper`
   (Q9 invariant). Per F9.0 Reading Y: neither sentinel fires at
   commit 8 (handoff tools live in new tools/pm_handoff.py module,
   not in tools/pm_loop.py or pm_supervisor.py). Per F9.0 Reading X:
   Q4 sentinel does NOT fire under Reading X (handoff-read uses
   stdlib pathlib, not tools/budget.py or tools/pm_focus.py); Q9
   sentinel does NOT fire (handoff-read in pm_handoff.py if added,
   not in pm_loop.py). Both readings keep both sentinels green.
   **Mode 1 / Mode 2 categorization:** if either sentinel fires
   despite this expectation, categorize per F2.5 convention before
   proceeding.

---

## F9.0 — spec contradiction on handoff-read mechanism (LOAD-BEARING)

This is the central architectural decision for commit 8. Surfaced
before F9.1 touch-points because the choice shapes touch-points,
test surface, and every downstream Q.

### The contradiction

The codebase contains TWO conflicting specifications about whether
`PMSupervisor._reconstruct_state` reads the handoff doc on respawn.

**Reading X — daemon-side override** (per multiple shipped sources):

- `bridge/supervisor_base.py:196-197`: "`_reconstruct_state` —
  post-spawn daemon-side bookkeeping (Engineer's is no-op; PM's
  reads handoff doc at M5)"
- `bridge/supervisor_base.py:284-298` (default impl docstring):
  "PM's M5 subclass overrides to read the most recent
  `/product/substrate/pm-handoffs/` doc per the M5 PM-compaction
  strategy."
- `bridge/engineer_supervisor.py:23`: "PMSupervisor (M5) overrides
  this hook with a handoff-doc read."
- `bridge/engineer_supervisor.py:152-154` (no-op docstring): "The
  hook exists in the base for PMSupervisor (M5) which will override
  it to read the most recent `/product/substrate/pm-handoffs/` doc."
- `docs/kickoff/11-engineer-bridge.md:753`: "[PMSupervisor's]
  post-respawn state reconstruction that reads the most recent
  `/product/substrate/pm-handoffs/` doc per the M5 PM compaction
  strategy."
- `docs/working/M5-continuous-pm-driver-grounding.md:399-402`
  (Section 5): "On respawn (post-compaction or post-crash),
  `PMSupervisor._reconstruct_state` reads the most recent handoff
  doc and provides it as context to the newly-spawned PM subprocess
  (via subprocess spec / env / stdin — decision belongs with
  PMSupervisor implementation, not the loop)."
- F2.1 row 8: "Compaction-trigger + handoff-doc generation +
  `PMSupervisor._reconstruct_state` reading most-recent handoff."
- Mike's prep-instruction framing for this commit:
  "PMSupervisor._reconstruct_state extension to read most-recent
  handoff is supervisor-side state behavior."

**Reading Y — subprocess-side via prompt-driven MCP call** (per
pm_supervisor.py's M5-pm-supervisor-base P-2 Reading B disposition):

- `bridge/pm_supervisor.py:20-29` (just shipped at M5-pm-supervisor-
  base, P-2 Reading B): "Handoff-doc reading is a subprocess-prompt
  concern landed at M5-continuous-pm-driver, not daemon-side
  bookkeeping. Architectural separation: daemon owns lifecycle
  (spawn / heartbeat / respawn / token); subprocess owns startup
  reads via prompt instructions."
- `bridge/pm_supervisor.py:167-191` (`_reconstruct_state` no-op):
  "Putting filesystem reads in this method would couple daemon code
  to substrate filesystem layout that evolves with prompt changes —
  wrong layer for that knowledge."
- `tests/integration/test_pm_supervisor.py:369-410` (invariant pin
  test `test_reconstruct_state_is_noop_at_initial_spawn_and_respawn`):
  "Enforces the M5-pm-supervisor-base prep checkpoint P-2 Reading B
  invariant: PMSupervisor._reconstruct_state is no-op."
- F2.5 closeout note (commit 7 prep, F8.6.A Reading D defense
  citation): "subprocess owns startup reads via prompt instructions."

The contradiction is real, not a misreading. The two recent commits
(M4c-engineer-supervisor and M5-pm-supervisor-base) reach OPPOSITE
conclusions; supervisor_base.py and engineer_supervisor.py docstrings
were authored before the M5-pm-supervisor-base P-2 disposition
reversed the framing for pm_supervisor.py specifically. The
contradiction is currently latent because handoff-reading isn't
implemented; commit 8 is where the choice becomes load-bearing.

Per memory `feedback_contradiction_handling`: surface both readings +
recommendation; never ship-then-document.

Per memory `feedback_spec_amendment_runtime_drift`: whichever direction
commit 8 lands, multiple spec/runtime files need amendment to align.

### Reading X — daemon-side `_reconstruct_state` override

Mechanism (per Section 5 framing line 401: "via subprocess spec / env
/ stdin — decision belongs with PMSupervisor implementation"):

- `pm_supervisor.py` overrides `_reconstruct_state`. Reads the
  most-recent file from `/product/substrate/pm-handoffs/<*>.md`,
  loads its content, holds on supervisor instance.
- New base-class hook `_extra_subprocess_prompt()` (or override
  `_subprocess_prompt()` to concatenate yaml-prompt + handoff-content)
  — supervisor injects handoff-content into subprocess prompt at
  spawn time.
- Or alternative: write handoff-content to a tempfile / stdin /
  environment variable that the subprocess reads at startup.
- pm.yaml prompt extension: at startup, instructs PM to look for
  handoff content in [wherever Reading X chose to deliver it].

Required spec amendments under Reading X (none invalidate Reading X
pre-amendment, but the pm_supervisor.py P-2 framing must be reversed):

- `bridge/pm_supervisor.py:20-29` docstring: reverse P-2 Reading B
  framing to align with Reading X.
- `bridge/pm_supervisor.py:167-191` `_reconstruct_state` docstring:
  rewrite as the new daemon-side implementation's docs.
- `tests/integration/test_pm_supervisor.py:369-410` invariant pin
  test: explicitly REMOVE; new test asserts the daemon-side read
  behavior.
- New test: `test_reconstruct_state_reads_most_recent_handoff_at_respawn`
  (or similar) — validates the read mechanism works.

Test surface estimate: +6-9 unique tests for the daemon-side path
(read tests, prompt-injection tests, base-class-hook tests if added,
subprocess-spawn integration test with handoff content).

### Reading Y — subprocess-side via tools/pm_handoff.py MCP tools (Reading D-extended)

Mechanism:

- New `tools/pm_handoff.py` module with two PM-only MCP tools:
  `write_pm_handoff(content)` (write-side) and `get_most_recent_handoff()`
  (read-side, returns content + metadata; or None if no handoff
  exists yet).
- `Settings.pm_handoff_dir` (path) plumbed via main.py lifespan to
  closure-captured callable (mirrors the cadence-config and
  `get_oauth_usage_snapshot` precedents).
- pm.yaml prompt extension (continues from commit 7's loop-iteration
  section):
  - At startup: PM calls `get_most_recent_handoff()`; treats
    returned content (if any) as resumed-context.
  - At conserve-trigger: PM authors handoff content per required-
    sections schema (per F2.3 Q1 Reading C); calls
    `write_pm_handoff(content)`; calls `signal_compaction()`;
    subprocess exits cleanly afterward.
- `bridge/pm_supervisor.py` UNCHANGED. `_reconstruct_state` stays
  no-op. Invariant test stays passing (no Mode 1 or Mode 2 fire).

Required spec amendments under Reading Y (multiple, mostly docstring
+ kickoff text alignment):

- `bridge/supervisor_base.py:196-197` and `:284-298` docstrings:
  remove the "PM's M5 subclass overrides to read the handoff doc"
  framing; replace with "all subclasses' default is no-op; subprocess-
  side handoff-read in PM's case lives in tools/pm_handoff.py."
- `bridge/engineer_supervisor.py:23` and `:152-154` docstrings:
  remove "PMSupervisor (M5) overrides this hook with a handoff-doc
  read" framing.
- `docs/kickoff/11-engineer-bridge.md:753`: rewrite PMSupervisor entry
  to reflect Reading Y (`_reconstruct_state` no-op; handoff-read in
  tools/pm_handoff.py via PM-prompt-driven MCP call).
- F2.1 row 8 framing: amend "PMSupervisor._reconstruct_state reading
  most-recent handoff" → "tools/pm_handoff.py read+write helpers;
  PMSupervisor unchanged."
- Section 5 framing in this grounding doc: amend to align (Reading Y
  framing for the handoff-read location).

Test surface estimate: +9-12 unique tests for the handoff-tools path
(write + read handlers; permission gates; tmp_path filesystem fixtures;
empty-dir handling; sort-by-timestamp shape; lifespan wiring).

### Recommendation: Reading Y

Defenses for Reading Y, evaluated against commit 7's F8.6.A Reading D
(1)+(2)+(3) defense survival pattern:

**Defense (1) — symmetric with EngineerSupervisor.** BREAKS at this
boundary. `11-engineer-bridge.md:543` is explicit: "PM compaction
with handoff doc is a different mechanism that ships at M5 —
Engineer does not need it because PM's altitude-1 reasoning carries
narrative context the DB doesn't capture." The design EXPLICITLY
breaks engineer-symmetry at the handoff boundary. Defense (1) does
not carry. Reading Y cannot lean on it.

**Defense (2) — single-responsibility lifecycle vs iteration scope.**
HOLDS, but ambiguous. `_reconstruct_state` is documented as
"post-spawn daemon-side bookkeeping" (lifecycle scope per its base-
class framing). Reading the substrate filesystem at this hook IS
lifecycle (spawn-time setup), not iteration. So defense (2)
ambiguously SUPPORTS Reading X. But reframed: "subprocess startup
reads via prompt instructions" puts handoff-read on the subprocess
side per the design choice the M5-pm-supervisor-base disposition
made. Both interpretations defensible; defense (2) doesn't decide
on its own.

**Defense (3) — loop-state config via main.py lifespan closure.**
HOLDS. `Settings.pm_handoff_dir` flows via main.py lifespan to the
new tools/pm_handoff.py closure (mirrors the cadence-config closure
from commit 7; mirrors `get_oauth_usage_snapshot` precedent). Zero
new bridge → tools imports. Reading Y consumes this defense
symmetrically.

**Independent defenses for Reading Y** (not derived from F8.6.A
Reading D defense pattern):

(4) **The substrate filesystem layout is operator-tunable substrate
config, not daemon-internal infrastructure.** Putting filesystem
reads in supervisor lifecycle code couples daemon to substrate-layout
knowledge that's better encapsulated as MCP-tool-mediated boundary.
This is the substantive reason given in pm_supervisor.py:175-177:
"Putting filesystem reads in this method would couple daemon code
to substrate filesystem layout that evolves with prompt changes —
wrong layer for that knowledge." This argument was load-bearing at
M5-pm-supervisor-base prep; it remains load-bearing at this boundary.

(5) **Reading Y's failure mode is testable via established patterns.**
tools/pm_handoff.py read-side handler tests use tmp_path fixture +
closure-captured directory; mirrors audit/sampler.py + tools/pm_focus.py
test shapes from commits 2-4. Reading X requires fixture growth on
supervisor lifecycle integration tests (the existing
test_pm_supervisor.py fixtures don't currently have substrate
filesystem layout) — per memory `feedback_optional_dependency_in_helpers`,
adding mandatory fixture-growth here is the wrong direction;
optional-with-None-fallback is the lighter-touch shape, but Reading X
needs the read to actually happen for handoff-context to reach PM.

(6) **Reading Y preserves commit 7's architectural commitments
without re-litigation.** F8.6.A Reading D's defense at commit 7
explicitly anticipated the commit 8 boundary: pm_supervisor.py's
docstring lines 184-189 future-proofed for "if a future revision
wants daemon-side handoff-doc reading, override this method. The
test ... will fail informatively, prompting a deliberate
reconsideration of the layer separation." Reading X would invoke
that future-proofing. Reading Y declines to invoke it on the grounds
that the M5-pm-supervisor-base disposition still holds — no new
substantive reason to reverse it has surfaced.

**Defense (1) breaking is acknowledged.** The remaining defenses
(2)+(3)+(4)+(5)+(6) carry on independent grounds. Reading Y's case
doesn't depend on engineer-symmetry; it depends on the
operator-tunable-substrate-layout argument + closure-pattern
plumbing + commit-7-architectural-commitment preservation.

### Counter-argument (Reading X case)

Surface explicitly so the disposition is conscious (per memory
`feedback_contradiction_handling`):

- **Spec text consensus.** Five spec sources (supervisor_base.py
  ×2 docstring locations; engineer_supervisor.py ×2 docstring
  locations; 11-engineer-bridge.md:753) and Mike's prep-instruction
  framing all describe Reading X. One spec source (pm_supervisor.py
  M5-pm-supervisor-base disposition) describes Reading Y. The
  numerical weight is not the deciding factor — pm_supervisor.py's
  disposition was made WITH knowledge of the boundary it would face
  here. But the spec text consensus signals operator/Mike intent
  that the original framing was load-bearing.
- **Failure-handling shape.** Under Reading X, handoff-read failure
  (file missing, parse error, permission error) can halt the
  supervisor before subprocess starts — operator gets a clear
  daemon-side error. Under Reading Y, handoff-read failure happens
  subprocess-side after spawn; PM observes None / error; PM-prompt
  decides what to do (proceed without context, or signal an
  operator-attention surface). Reading X's failure mode is
  operationally cleaner if Mike values "supervisor halts on
  handoff-corruption" semantics.
- **Atomicity.** Under Reading X, daemon ensures handoff-read
  completes before subprocess startup — no race between PM-startup-
  read and any concurrent handoff-write (though concurrent writes
  shouldn't occur in normal lifecycle). Under Reading Y, PM's
  startup MCP call sequence is at PM's discretion — PM might call
  other tools before reading handoff, observing intermediate state.
  Likely not material in practice; surface for completeness.

If Mike values failure-handling shape (Reading X argument) over
substrate-layout-encapsulation (Reading Y argument), Reading X
lands and the test invariant from commit 7 explicitly removes with
re-disposition.

### Disposition impact on F9.1 / F9.2 / F9.4

Touch-points (F9.1) differ materially: Reading Y adds tools/pm_handoff.py
+ pm.yaml + main.py + Settings (mirrors commit-7 plumbing shape);
Reading X adds pm_supervisor.py override + base-class hook for
prompt-injection + new test pin + multiple docstring/spec amendments.

Test estimate (F9.4) under Reading Y: ~17-20 unique. Under Reading X:
~13-16 unique (smaller because no new MCP tools' permission/wrapper
surface). F2.1 +14 estimate aligns better with Reading X's shape;
Reading Y's higher count is driven by MCP-tool-permission-test
parametrize patterns (each new tool ships with the
non-PM-rejected-×3 parametrize block per established convention).

---

## F9.1 — touch points (per F9.0 Reading Y recommendation)

| File | Status | Touch shape |
|---|---|---|
| `src/meridian_orchestrator/tools/pm_handoff.py` | NEW | `write_pm_handoff` (PM-only MCP) + `get_most_recent_handoff` (PM-only MCP). Pydantic schemas (`WritePmHandoffInput`, `GetMostRecentHandoffOutput`). Closure-captured handoff-dir resolution. Filesystem helpers (write file with timestamp; list dir + sort by timestamp + read top file). |
| `src/meridian_orchestrator/server.py` | EDIT | `_register_pm_handoff_tools` helper + invoke from `build_server`. Wraps the two new MCP tools with permission gates. |
| `src/meridian_orchestrator/main.py` | EDIT | Lifespan-time wiring of handoff-dir config (Settings.pm_handoff_dir) into pm_handoff tools via closure pattern (mirrors `get_oauth_usage_snapshot` + `Sampler` instance + `get_loop_tick_seconds` precedents). |
| `src/meridian_orchestrator/config.py` | EDIT | Add `pm_handoff_dir: Path` Settings field per F9.2 Q5 disposition. Default `/product/substrate/pm-handoffs/` resolved against substrate root if Mike picks the conventional location. |
| `src/meridian_orchestrator/tools/supervisor.py` | EDIT (per F9.2 Q3) | `signal_compaction` permission widening: PM-allowed alongside Engineer per Q3 Reading A; OR new `signal_pm_compaction` tool per Q3 Reading B. |
| `src/meridian_orchestrator/bridge/pm_supervisor.py` | UNCHANGED | Per F9.0 Reading Y. `_reconstruct_state` stays no-op. Invariant test stays passing. |
| `src/meridian_orchestrator/bridge/supervisor_base.py` | DOCSTRING-ONLY EDIT | Per F9.0 Reading Y spec amendment: remove "PM's M5 subclass overrides to read the handoff doc" framing (lines 196-197 + 284-298 docstrings). |
| `src/meridian_orchestrator/bridge/engineer_supervisor.py` | DOCSTRING-ONLY EDIT | Per F9.0 Reading Y spec amendment: remove "PMSupervisor (M5) overrides this hook with a handoff-doc read" framing (lines 23 + 152-154). |
| `config/agents/pm.yaml` | EDIT (per F9.2 Q10) | system_prompt extension: startup handoff-read instruction + conserve-trigger compaction-prep sequence (author handoff content with required sections; call `write_pm_handoff`; call `signal_compaction`). |
| `tests/unit/tools/test_pm_handoff.py` | NEW | Direct-handler tests for both tools. Filesystem-fixture-based (tmp_path). Permission gates. Empty-dir handling. Sort-by-timestamp shape. |
| `tests/integration/test_pm_handoff_mcp_wrapper.py` | NEW | End-to-end MCP tests with permission parametrize blow-ups. |
| `tests/integration/test_pm_compaction_e2e.py` | NEW | Conserve-trigger → handoff-write → signal_compaction → supervisor respawn → next PM reads handoff. |
| `docs/kickoff/06-mcp-tools-phase-one.md` | EDIT | Two new tool entries (write_pm_handoff + get_most_recent_handoff + permission rows). |
| `docs/kickoff/11-engineer-bridge.md` | EDIT | Per F9.0 Reading Y spec amendment: amend line 753 PMSupervisor description; possibly add a § PM compaction subsection symmetric with § Engineer compaction. |
| `docs/kickoff/02-architecture.md` | EDIT (small) | Reference to PM compaction mechanism (parallel to Engineer compaction reference). |

Under F9.0 Reading X (alternative), touch points shift to
`bridge/pm_supervisor.py` (override `_reconstruct_state`) + new
base-class hook in `bridge/supervisor_base.py` for prompt-injection +
explicit removal of `test_reconstruct_state_is_noop_at_initial_spawn_and_respawn`
+ new daemon-side handoff-read test. tools/pm_handoff.py would still
likely exist for the WRITE side (PM authors handoff at compaction-
trigger), so Reading X is "Reading Y for the write side + daemon-side
override for the read side" — hybrid, but with a different
architectural commitment.

---

## F9.2 — open questions (Q1-Q10)

Numbered with multiple readings + recommendation. Reviewing-Claude
reviews; Mike dispositions.

### Q1 — Handoff-read mechanism location (LOAD-BEARING; subsumes F9.0)

Disposition surface for the F9.0 spec contradiction.

- **Reading A — Reading X** (daemon-side `_reconstruct_state`
  override). Spec-text-consensus argument. Failure-handling shape
  (supervisor halts on handoff-corruption). See F9.0 Reading X
  prose for full mechanism + spec amendments.
- **Reading B — Reading Y** (subprocess-side via tools/pm_handoff.py
  MCP tools). M5-pm-supervisor-base P-2 Reading B preservation.
  Substrate-layout-encapsulation argument. See F9.0 Reading Y prose
  for full mechanism + spec amendments.
- **Reading C — Hybrid.** Daemon-side reads the handoff filename
  (most-recent path resolution); subprocess-side reads the content
  via MCP tool that takes the path as input. Combines Reading X's
  daemon-side path-resolution (where filesystem-layout knowledge
  lives) with Reading Y's subprocess-side content delivery (where
  prompt-context lives). More moving parts; less defensible than
  picking one side cleanly.

**Recommendation: Reading B (Reading Y).** Per F9.0 prose. Defenses
(2)+(3)+(4)+(5)+(6) carry independently of defense (1) which breaks
at this boundary. M5-pm-supervisor-base P-2 Reading B disposition
holds without new substantive reason to reverse.

If Mike values supervisor-halts-on-handoff-corruption semantics
(Reading X argument 2), Reading A lands; the invariant test from
commit 7 explicitly removes with re-disposition; spec amendments
shift accordingly.

### Q2 — Handoff doc shape: re-confirm F2.3 Q1 Reading C

F2.3 Q1 (line 1247) dispositioned Reading C: required-section markdown
schema as PM yaml convention; no daemon-side validation; audit-log
scan for missing sections. Sections: `current_focus`, `planned_next`,
`open_questions`, `recent_decisions_pointer`, `cohort_summary`.

At commit 8 implementation pressure, does Reading C still hold, or
does daemon-side validation become defensible?

- **Reading A — re-confirm F2.3 Q1 Reading C.** Yaml-convention
  + audit-emit-on-write. `write_pm_handoff` writes content as-is;
  audits with `pm.compaction.handoff_written` action + details
  (path, content_size, sections_detected_via_simple_regex). No
  validation rejection on missing sections.
- **Reading B — daemon-side strict validation.** `write_pm_handoff`
  parses required sections from content; rejects write if any
  required section missing. Tightest contract; highest schema cost
  (regex / parser brittle to markdown variations).
- **Reading C — soft validation with operator-visible warning.**
  `write_pm_handoff` writes content; audits both `handoff_written`
  + (if sections missing) `pm.compaction.handoff_sections_missing`
  with details (which sections absent). Operator-visible signal
  without write-rejection.

**Recommendation: Reading A (re-confirm F2.3 Q1 Reading C).**
Yaml-convention + audit-emit. F2.3 Q1's reasoning still holds:
daemon doesn't structurally parse the doc semantically (PM's
respawned-self reads the markdown via Q6 read-tool's content
return; the daemon never parses sections). Reading C's
section-detection-on-audit is more defensible than Reading B's
strict-rejection but still adds parser surface to the daemon for
no daemon-side consumer; defer until M6 dashboard wants the
section-presence query.

If commit 8 ships Reading A, audit-log scan for missing-sections
remains future-work (M6 dashboard). Operator surface for handoff
quality stays informal until then.

### Q3 — Compaction-signal MCP tool: extend or new?

PM's compaction at conserve-trigger needs to signal the supervisor
to clean-exit + respawn. Engineer has `signal_compaction` (M4c,
engineer-only). Does PM use the same tool with permission widened,
or get a new `signal_pm_compaction`?

- **Reading A — extend `signal_compaction` permission.** Single
  tool, callable by both Engineer and PM. Handler resolves caller's
  token to whichever supervisor (engineer or pm-continuous) has a
  running dispatch and sets `_compaction_pending` on that instance.
  Symmetric mechanism; one tool surface; mirrors how
  `set_pm_mode` doesn't have an `engineer.set_engineer_mode` parallel.
- **Reading B — new `signal_pm_compaction` tool.** Distinct surface
  for PM-side compaction. Handler is dedicated (looks up
  PMSupervisor instance only). Allows PM-specific compaction
  semantics to evolve without entangling engineer's tool. Adds
  duplicate code-shape.
- **Reading C — extend with permission widening + behavior parity.**
  Reading A with explicit assertion that PM and Engineer compaction
  semantics are identical at the supervisor level (`_compaction_pending`
  flag flow + clean-exit + respawn-without-cap-increment).
  Differences (handoff-write side; PM-prompt-driven detection
  trigger) live elsewhere. Just naming/ergonomics over Reading A.

**Recommendation: Reading A (extend permission).** The supervisor-
level compaction lifecycle is genuinely identical for Engineer and
PM (set `_compaction_pending`, clean-exit, no cap-counter increment,
respawn fresh). The role-distinct work happens at handoff-write
(PM only) + handoff-read-on-respawn (PM only); both live in
tools/pm_handoff.py, not in `signal_compaction`'s scope. Reading B's
duplicate tool would diverge over time without architectural reason.
Reading C is Reading A with extra ceremony.

Permission widening shape: tools/supervisor.py allowed_roles
extended from `("engineer",)` to `("engineer", "pm")`; handler's
supervisor-resolution logic extended to look up either instance via
the caller's token role. Per memory
`project_mcp_tool_wrapper_activity_recording`: ensure `_observe_caller`
fan-out continues to operate correctly under the widened permission.

### Q4 — Compaction-trigger detection mechanism

How does PM know it's time to compact?

- **Reading A — loop-side polling on `pace_recommendation == 'conserve'`.**
  PM's per-tick `get_loop_state.subscription_5h_state.pace_recommendation`
  read surfaces `'conserve'` when subscription_5h utilization >= 80%.
  PM's prompt judges: at first observation of `'conserve'` in this
  cycle, run the compaction-prep sequence. Loop-side; subprocess-
  driven; symmetric with Engineer's per-iteration compaction
  detection per `11-engineer-bridge.md:516`.
- **Reading B — daemon-side push.** Daemon observes pace_recommendation
  flip in `get_oauth_usage_snapshot`; signals subprocess via a
  new MCP tool that subprocess polls (e.g., `get_compaction_signal`).
  Adds infrastructure surface; subprocess still polls so the
  push-vs-poll distinction collapses.
- **Reading C — daemon-side trigger via subprocess SIGTERM.**
  Daemon detects pace_recommendation flip; sends signal to
  subprocess to cleanly exit. Subprocess writes handoff during
  exit-handler. OS-signal-handling-mid-MCP-call corruption risk
  per F8.2 Q1 Reading D rejection at commit 7.

**Recommendation: Reading A (loop-side polling).** Symmetric with
Engineer's compaction detection (Engineer's prompt reads
`get_window_state(bucket='subscription_5h')` per iteration; calls
`signal_compaction` when conserve fires). PM has the same signal
already aggregated in `get_loop_state.subscription_5h_state` per
commit 7. Zero new infrastructure; the existing per-tick read covers
detection. Reading B's push pattern adds infrastructure for no
subprocess-controllability gain. Reading C's signal-handling is
operationally fragile per the F8.2 Q1 Reading D framing.

### Q5 — `Settings.pm_handoff_dir` plumbing

Where does the handoff doc directory live?

- **Reading A — Settings field; default to substrate-relative path.**
  `Settings.pm_handoff_dir: Path` defaults to a substrate-relative
  path (e.g., resolved against `Settings.substrate_root` if that
  exists, or against a hardcoded `/product/substrate/pm-handoffs/`
  fallback). Operator override via env var.
- **Reading B — Settings field; absolute-only default.** Default
  to an absolute path under data_dir (e.g., `<data_dir>/pm-handoffs/`).
  Operator override via env var. Cleaner test-fixture shape (tmp_path
  resolves directly).
- **Reading C — yaml-driven.** PM yaml has `handoff_dir` field;
  registry passes through to tools/pm_handoff.py via closure. More
  layers; misaligns with the established pattern of operational
  config in Settings (cadence, sample-percentage, etc.).

**Recommendation: Reading B (Settings + absolute-default under
data_dir).** Operationally cleanest. Test fixtures (tmp_path)
override via env var. Production default `<data_dir>/pm-handoffs/`
keeps handoff files alongside daemon-managed state instead of inside
substrate-managed working directory (where the substrate's git
operations might interact with them). The "/product/substrate/
pm-handoffs/" framing in the kickoff docs is aspirational; if Mike
prefers that location operationally, override via env var.

If Mike prefers the substrate-relative location semantically (so
PM operating sessions naturally read the handoff via substrate
filesystem awareness), Reading A or substrate-root-coupled variant
lands. Both are env-tunable; the question is the default.

### Q6 — `get_most_recent_handoff` output shape

What does the read-side MCP tool return?

- **Reading A — content + metadata.** Output: `{content: str | None,
  written_at: datetime | None, path: str | None, sections_detected:
  list[str]}`. Returns None-fields when no handoff exists yet
  (cold-start). PM prompt instructs handling of None case.
- **Reading B — content only; None when absent.** Output: `{content:
  str | None}`. Simpler shape; PM doesn't need metadata for resumed-
  context purposes.
- **Reading C — content + minimal metadata.** Output: `{content: str
  | None, written_at: datetime | None}`. Drops path + sections
  fields. Middle ground.

**Recommendation: Reading C.** Content + timestamp gives PM enough
to reason about "how recent is this resumed-context" without adding
parser surface (sections_detected field would require a markdown
parser). Path field has low operator-facing value at the MCP
surface (audit_log records the path on writes).

If Q2 lands as Reading C (soft validation with sections-missing
audit), the section-detection logic lives audit-side at write time,
not at read time; Q6 Reading C aligns.

### Q7 — Anti-flapping discipline for once-per-5h-cycle compaction

Section 5 self-test: "the loop should compact at most once per 5h
cycle. PMSupervisor's three-respawns-per-hour cap provides the
safety, but the design should not lean on the safety net." But:
compaction-respawns explicitly do NOT count toward the 3-per-hour
cap (per supervisor_base.py:646-654). So there's no automatic safety
net for compaction oscillation. PM-prompt judgment is the primary
discipline.

What stops PM from compacting on every tick after the first
conserve fire?

- **Reading A — PM-prompt judgment + handoff-doc state.** PM's
  prompt instructs: "compact only at the conserve-edge transition;
  if you just woke up from compaction (handoff_written_at is
  recent), don't compact again until next 5h cycle." PM consumes
  `get_most_recent_handoff.written_at` to detect post-compaction
  state. No daemon-side throttle.
- **Reading B — daemon-side throttle on signal_compaction.** Handler
  rejects (or audits-as-throttled) compaction signals within N
  minutes of the previous compaction completion. N defaulted to
  some fraction of the 5h cycle (e.g., 60 minutes minimum gap).
- **Reading C — both.** Reading A as primary discipline + Reading B
  as defense-in-depth.

**Recommendation: Reading A (PM-prompt + handoff-doc state).**
Per Q9-from-commit-7 disposition: hysteresis logic is PM-prompt's
responsibility, not infrastructure's. Reading B adds daemon-side
state (last-compaction-completed-at timestamp; per-supervisor) and
re-introduces the "infrastructure encodes assumptions about
behavior" pattern that Q9-B rejected.

PM's startup read of handoff_written_at gives PM the state needed
to judge "just-compacted, don't compact again immediately." If
empirical operation surfaces oscillation, Reading C revisits.
Defense-in-depth is the right framing for round 2, not round 1.

### Q8 — Q9-from-commit-7 re-evaluation: read_current_X threshold

Mike's prep instructions flagged: "threshold-of-three watch may trip
(read_current_pm_mode + read_current_focus + read_current_handoff
= 3)."

Is `read_most_recent_handoff` (the helper underlying `get_most_recent_handoff`
MCP tool) a third instance of the `read_current_X` rowid-DESC helper
pattern?

- **Reading A — yes, it's a third instance.** Surface similarity:
  "read most-recent X." Threshold-of-three trips per F2.4. Per
  F4.5.2 reframe: trigger-to-evaluate, evaluation says YES extract
  shared utility for "read most recent X by ordering Y."
- **Reading B — no, different abstraction unit.** Per F4.5.2 reframe:
  abstraction-unit evaluation is the load-bearing question, not
  surface similarity. `read_current_pm_mode` + `read_current_focus`
  share the SQL-rowid-DESC abstraction unit (read top row from log
  table by ROWID DESC LIMIT 1). `read_most_recent_handoff` is
  filesystem-directory-listing-by-timestamp-suffix-sort. Different
  mechanism (filesystem vs SQL), different ordering primitive
  (filename string sort vs rowid integer), different semantic load.
- **Reading C — surface similarity is enough at threshold-of-three;
  evaluate at next instance.** Land helper non-extracted at commit
  8; surface another evaluation at hypothetical fourth instance.
  Lower disruption.

**Recommendation: Reading B (different abstraction unit; threshold
does NOT trip at this commit).** F4.5.2 reframe (Followup-7 / F2.5
discipline note) is explicit: "Surface similarity does not imply
shared semantic load. Reject extraction when the abstraction unit
doesn't carry semantic weight." The SQL-rowid-DESC abstraction unit
is "tail of an append-only log table"; filesystem-by-timestamp is
"directory listing with timestamp-suffix file naming." Different
data substrates, different ordering correctness conditions
(filename sort assumes ISO-timestamp filenames; rowid sort assumes
monotonic insertion ID). Sharing a helper would conflate these.

Watch for the SQL-rowid-DESC pattern stays at 2. A NEW pattern
"read most-recent file by timestamp from substrate directory" emerges
at instance 1. No threshold concern.

If commit 8 lands as Reading B, the Q9-from-commit-7 invariant pin
test (`test_pm_loop_module_does_not_define_read_current_helper`) does
NOT fire because the new helper lives in tools/pm_handoff.py, not
tools/pm_loop.py. The pin's path scope (tools/pm_loop.py only) was
chosen narrowly at commit 7 specifically so future commits adding
read helpers to other modules don't have to re-litigate the pin.
This anticipates exactly the boundary commit 8 is at.

### Q9 — Q10-from-commit-7 re-evaluation: write aggregator rejection

Per carry-forward "→ commits 8-9": if commit 8 adds write events
that push per-iteration count materially higher, revisit F8.2 Q10
write-tool aggregation rejection.

Does commit 8's compaction-write surface (handoff-write +
signal_compaction emit + pm_spin row at conserve-trigger) push
per-iteration write count high enough to reopen?

- **Reading A — no, compaction is once-per-5h-cycle event.** Per-tick
  write count at typical (non-conserve) tick stays at 1-3 (commit
  7's Q10-B disposition baseline). Per-tick write count at
  conserve-trigger tick spikes to 3-5 (handoff_written audit +
  signal_compaction audit + pm_spin row + maybe 1-2 sample-event
  rows). Compaction occurs ~1× per 5h cycle under healthy operation;
  this is ~0.003% of ticks under 60s cadence. Average write count
  effectively unchanged. Q10 aggregator-rejection holds.
- **Reading B — yes, reopen Q10 because compaction-tick write
  semantics ARE related-write-bundle.** Handoff_written + signal_compaction
  are causally bundled (PM authors handoff THEN signals); aggregator
  could capture this as one "compaction_initiated" emit with both
  rows derived. Different shape than Q10's normal-tick framing.
- **Reading C — accept Reading A; flag for if compaction frequency
  shifts.** If empirical operation shows compaction firing more
  than once per 5h cycle (e.g., short-cycle subscription pressure
  + PM's prompt judgment trips conservatively), revisit. Don't
  pre-bake aggregator for hypothetical high-frequency compaction.

**Recommendation: Reading A (Q10 stays rejected; compaction is
once-per-cycle event, not per-tick concern).** Volume math: under
60s cadence, ~3,600 ticks per 5h cycle, of which 1 is compaction-
trigger. The marginal write events at the compaction tick (3-5 vs
1-3) average to ~0.001 additional writes/tick across the cycle.
Not a volume-budget concern.

Reading B's "causally bundled" framing has merit at the audit-trail-
shape level (handoff_written → signal_compaction is a causal chain),
but Q10-B's rejection ground (write events have distinct audit-
trail semantics) holds: handoff_written is its own durable record
(action: `pm.compaction.handoff_written`, target: handoff-doc-path);
signal_compaction is its own event (action: `pm.compaction.signaled`,
target: dispatch_id). Bundling would conflate the records' targets
+ make audit-log query semantics murkier.

Carry-forward "→ commits 8-9" RESOLVES at this commit's Reading A
disposition.

### Q10 — pm.yaml prompt extension scope at commit 8

Commit 7 shipped the per-tick iteration loop section. Commit 8 adds
compaction-prep + handoff sequence. How much yaml prompt expansion?

- **Reading A — substantive extension.** New "Compaction lifecycle"
  section (~30-40 lines) with: startup handoff-read instruction;
  conserve-detection trigger; handoff-doc authoring guidance per
  required-sections; write_pm_handoff + signal_compaction call
  sequence; once-per-cycle anti-flapping discipline (Q7 Reading A
  framing).
- **Reading B — minimal extension.** Brief paragraph pointing at
  the new tools; relies on PM's general continuous-mode framing for
  the handoff-content-shape decision.
- **Reading C — extend across commits 8 and 9.** Commit 8 ships
  compaction trigger + write/read sequence; commit 9 (e2e tests +
  closeout) adds the required-sections content guidance. Defers
  schema-shape work.

**Recommendation: Reading A (substantive extension).** Commit 7's
F8.2 Q5 disposition picked Reading A for the same reason: the
prompt extension IS the behavioral driver. Without explicit
handoff-content guidance (required sections per F2.3 Q1 Reading C),
PM's free-form authoring would skip required sections, and the
audit-emit-on-missing-sections discipline never gets useful signal.

The forward-reference placeholder at pm.yaml line 134 ("compaction-
prep at commit 8 picks up the shutdown handling") replaces with
concrete compaction-prep prose. The startup handoff-read instruction
adds at the top of the continuous-mode iteration loop section.

---

## F9.3 — watch-for-pattern check

| Pattern | Status pre-commit | Commit 8 instance (per F9.0 Reading Y + Q1-B + Q2-A + Q3-A + Q4-A + Q5-B + Q6-C + Q7-A + Q8-B + Q9-A + Q10-A) | Disposition |
|---|---|---|---|
| Closed-enum CHECK + sentinel test | 9+ instances; established convention per F2.5 graduation | +0 expected; new MCP tool inputs/outputs use existing types. Audit-action namespace adds two new actions (`pm.compaction.handoff_written`, `pm.compaction.signaled`) — string convention, not closed-enum. | No threshold concern. |
| `read_current_X` rowid-DESC helper | 2 | +0 per Q8 Reading B. Filesystem-by-timestamp pattern (instance 1 of NEW pattern) is a different abstraction unit per F4.5.2 reframe. | No trip; SQL pattern stays at 2; new filesystem pattern starts at 1. |
| `bridge/` → `tools/` cross-layer imports | 2 | +0 per F9.0 Reading Y (supervisor unchanged). Reading X would still likely +0 (filesystem reads use stdlib, not tools/-layer). | No trip. |
| Production-code-surface-for-testability | 2 | +0 per Q5 + closure pattern (mirrors commit-7 cadence-config plumbing). tmp_path + monkeypatch on the closure are established patterns. | No trip. |
| Audit-action `pm.<surface>.<verb>` namespace | 5 | +2 (`pm.compaction.handoff_written`, `pm.compaction.signaled`). Convention-conforming; no expansion question. | No expansion concern. |
| Server-side blocking MCP tool (BACKLOG.md § Architectural patterns to review) | 1 (`wait_for_next_tick`) | +0 per Q4 Reading A (loop-side polling, not blocking). | No second-instance trigger; pattern-review stays deferred. |
| Closure-captured callable for tools/-layer config | 4 (`get_oauth_usage_snapshot`, Sampler instance, `get_loop_tick_seconds`, soon `get_pm_handoff_dir`) | +1. Pattern is now established convention; the +1 is convention-conforming. | No threshold concern; pattern is graduated like closed-enum. |
| Code-inspection sentinel | 2 (Q4 invariant + Q9 invariant from commit 7) | +0-1. Q1 disposition (whichever lands) may add a pin (e.g., if Reading B holds, "tools/pm_handoff.py defines no read_most_recent_X helper outside its declared shape" — but this would over-pin and isn't necessary). Default: no new pin; the architectural pinning at commit 7 covers commit 8's territory. | If a new sentinel feels load-bearing during draft, surface for explicit add. |
| Spec amendment scope | 0 prior at this sub-milestone (commit 6 did kickoff doc merges; this is structural amendment) | +5-7 spec/runtime files per F9.0 Reading Y (supervisor_base.py + engineer_supervisor.py + 11-engineer-bridge.md + 02-architecture.md + 06-mcp-tools-phase-one.md + Section 5 grounding doc + F2.1 row 8 framing). Per memory `feedback_spec_amendment_runtime_drift`. | Surface as F9.5 anticipated pushback — multi-file amendment scope is non-trivial. |
| F2.1 surface-as-finding rule | 5 invocations + 1 NOT-FIRED clarification | Likely +1 at commit 8 draft. F2.1 estimate +14 unique. Trigger condition (>200% over via unique-function metric per F6.6 clarification) at >28 unique tests — possible if Reading Y's MCP-tool-permission parametrize blow-ups push count. | Surface at diff checkpoint. |
| Carry-forward "→ commits 8-9" | Active (per-iteration write count) | RESOLVED per Q9 Reading A. Annotate per F8.6.B carry-forward annotation discipline: "DISPOSITIONED 2026-05-07 (commit 8 prep, F9.2 Q9-A): per-iteration write count at compaction-trigger ticks spikes to 3-5 but compaction is once-per-5h-cycle event; average write count effectively unchanged. Q10 aggregator-rejection holds." | Carry-forward closes. |

---

## F9.4 — test-target estimate

F2.1 estimate: +14. Working breakdown under recommended dispositions
(Q1-B, Q2-A, Q3-A, Q4-A, Q5-B, Q6-C, Q7-A, Q8-B, Q9-A, Q10-A):

| Layer | Count |
|---|---|
| `write_pm_handoff` handler tests (write succeeds; permission gate; audit emission shape; filename timestamp uniqueness; directory-create-on-missing) | 4-5 |
| `get_most_recent_handoff` handler tests (returns most recent; returns None on empty dir; permission gate; sort-by-timestamp shape with ISO filenames; malformed filename ignored) | 4-5 |
| Pydantic schemas (output models for both tools; closed-enum sentinels — none expected per F9.3) | 1 |
| MCP wrapper integration (PM-allowed × 2 tools; non-PM rejected × 3 parametrized × 2 tools) | 2 unique / 8 collected |
| Settings `pm_handoff_dir` default + path-resolution validation | 2 |
| Lifespan wiring (closure pattern for pm_handoff tools) | 1 |
| `signal_compaction` permission widening tests (PM-allowed; supervisor-resolution to PMSupervisor instance; `_compaction_pending` flag set on PMSupervisor; mirror of existing engineer tests) | 3-4 |
| Audit emission shape (`pm.compaction.handoff_written` + `pm.compaction.signaled` action strings; details fields) | 2 |
| E2e: PM at conserve writes handoff + signals + supervisor respawns + new PM reads handoff + handoff content matches | 1-2 |
| pm.yaml system_prompt extension verification (yaml-load test + presence of compaction-section keywords; no new closed-enum sentinels needed) | 1 |
| **Total under recommendations** | **17-22 unique / 22-29 collected** |

**vs F2.1 estimate of +14 unique:** trajectory is 17-22 / 14 =
121-157% over. F2.1 trigger condition (>200% via unique-function
metric) at >28 unique — under threshold. **Trigger should not fire
at commit 8 under recommendations.** Surface as F2.1 finding 6th
invocation at diff checkpoint with three-reason framing.

If Q1 lands as Reading A (Reading X — daemon-side override), test
count shifts: -3-5 unique (no new MCP-tool-permission parametrize
blow-ups for read-side; tools/pm_handoff.py still exists for write
side) + 4-6 unique (new pm_supervisor.py override tests + base-class
hook tests + invariant-test removal/replacement). Net: ~17-23 unique,
similar magnitude.

---

## F9.5 — anticipated pushbacks

Six weak points likely to surface at review:

1. **F9.0 Reading Y contradicts spec text consensus.** Five spec
   sources describe Reading X; Mike's prep-instruction framing
   describes Reading X. My recommendation is Reading Y based on
   pm_supervisor.py's M5-pm-supervisor-base disposition, which is
   one source against five. The reasoning chain (P-2 Reading B
   was made knowing this exact boundary; defenses (2)+(3)+(4)+(5)+(6)
   carry independently of (1)) is defensible but reasonable people
   can prefer the spec-text-consensus on operator-clarity grounds
   alone. Genuinely contestable; this is the load-bearing prep-
   checkpoint disposition for commit 8.

2. **Defense (1) breaking is honest.** F8.6.A Reading D defense at
   commit 7 used "symmetric with EngineerSupervisor" as one of three
   defense legs. At commit 8 boundary it breaks explicitly — the
   design intentionally diverges Engineer/PM at handoff per
   `11-engineer-bridge.md:543`. F9.0 Reading Y's case rests on the
   remaining (2)+(3)+(4)+(5)+(6) defenses. If reviewing-Claude
   tests the precedent's actual conditions (per the F2.5
   framing-precedent-application discipline), the test exposes
   that one of the original three legs no longer carries — a real
   cost to the precedent's authority. Independent grounds carry
   the recommendation; the precedent-extension is partial, not
   total. Honest framing required. **Hybrid bidirectional-discipline
   instance:** prep-instruction-error (Mike's Reading X framing) +
   framing-precedent-application (Reading D's defense (1) breaking
   at this boundary).

3. **Multi-file spec amendment scope per Reading Y.** Reading Y
   requires editing supervisor_base.py + engineer_supervisor.py
   docstrings + 11-engineer-bridge.md:753 + 02-architecture.md +
   Section 5 of this grounding doc + F2.1 row 8 framing. Five-to-
   seven-file amendment is non-trivial; mistakes risk leaving stale
   spec text that contradicts the runtime behavior. Per memory
   `feedback_spec_amendment_runtime_drift`: ensure ALL referencing
   spec/runtime files amend in the same commit or follow-on commit
   (commit 9 closeout could pick up stragglers — but cleaner is
   one-commit atomic amendment). Reviewing-Claude may push back:
   "Reading X has a smaller amendment surface (just remove invariant
   test + amend pm_supervisor.py docstring; spec text already says
   Reading X)." This is a legitimate operational consideration.

4. **`get_most_recent_handoff` returns None on cold-start.** First
   PM startup ever (no prior handoff) → None content. PM's prompt
   must handle the None case ("you have no resumed-context; treat
   this as a fresh continuous-mode session"). Yaml-prompt extension
   needs explicit cold-start guidance. Reviewing-Claude may push
   back: "Cold-start handling is yaml-prompt's job; what if PM
   doesn't handle it?" — this is the standard
   yaml-convention-without-daemon-validation tradeoff. Audit-log
   surfaces missing-section behavior post-hoc.

5. **Anti-flapping leans on PM-prompt judgment (Q7 Reading A).**
   Per Q9-from-commit-7 disposition rationale (hysteresis is PM-
   prompt's responsibility), Reading A defers oscillation
   prevention to PM's judgment + handoff-doc state. Reviewing-
   Claude may push back: "PM's prompt is auto-iterating; what if
   PM's judgment fails and oscillation occurs?" Mitigation per
   Q7 Reading C (defense-in-depth) revisits at empirical
   oscillation observation. Reading A is round-1 discipline;
   round-2 adds Reading B if needed. Acknowledge this is
   trust-the-prompt territory.

6. **F2.1 estimate divergence at +21% to +57% over.** Sixth
   invocation of F2.1 surface-as-finding rule. Per F2.5 unique-
   function metric clarification: trigger at >200% (>28 unique);
   17-22 estimated unique stays under. Three-reason framing
   (precedent-mirror tests, pushback-driven additions, disposition-
   driven invariant pins) likely applies; surface explicitly at
   diff checkpoint with reason breakdown. Trajectory across
   commits 2-7 (50% / 37% / 110% / -22% / -22% / TBD-7): commit 8
   estimate at ~125% would land in the upper-middle of the
   distribution; not a trigger fire but worth noting in the
   trajectory observation.

---

## F9.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q10 + the six anticipated-pushback items
above:

- `tools/pm_handoff.py` drafted with Q1-B + Q2-A + Q5-B + Q6-C +
  Q7-A dispositions (write_pm_handoff, get_most_recent_handoff;
  closure-captured handoff-dir; ISO-timestamp filename convention).
- `Settings.pm_handoff_dir` per Q5 (default under data_dir; env-
  tunable).
- `main.py` lifespan wires handoff-dir config via closure pattern.
- `server.py` `_register_pm_handoff_tools` for the new MCP tools.
- `tools/supervisor.py` permission widening on `signal_compaction`
  per Q3-A; supervisor-resolution logic extended to pm-continuous.
- `config/agents/pm.yaml` system_prompt extension per Q10-A
  (compaction lifecycle section ~30-40 lines).
- `bridge/pm_supervisor.py` UNCHANGED per F9.0 Reading Y (Q1-B);
  invariant test stays passing.
- `bridge/supervisor_base.py` + `bridge/engineer_supervisor.py`
  docstring amendments per F9.0 Reading Y spec amendment surface.
- `docs/kickoff/06-mcp-tools-phase-one.md` new tool entries +
  permission rows.
- `docs/kickoff/11-engineer-bridge.md` line 753 amendment +
  possible new § PM compaction subsection.
- `docs/kickoff/02-architecture.md` small reference to PM
  compaction mechanism.
- Section 5 of THIS grounding doc amended for Reading Y framing
  (inline annotation per F8.6.B carry-forward annotation discipline:
  preserve original Reading X framing as historical context with
  `[SUPERSEDED 2026-05-07 — see F9.0 Reading Y disposition]` marker
  per memory `feedback_supersession_marker_shape`).
- F2.1 row 8 framing amendment at line 1108 (per Reading Y).
- Tests per F9.4 estimate (~17-22 unique / 22-29 collected).
- F2.1 divergence assessment surfaced at diff checkpoint per
  unique-function metric (per F6.6 clarification) — sixth invocation.
- Carry-forward "→ commits 8-9" entry RESOLVED in grounding doc §
  Carry-forward notes (Q9 Reading A disposition); update entry
  header to "RESOLVED 2026-05-07."

Diff checkpoint surfaces commit-by-commit; commit 8 lands; commit 9
prep opens (e2e integration tests + drift-scan + sub-milestone
closeout doc).

**End of Followup-14.**

---

# Followup-15 — F9.2 disposition feedback + Q1 verification cycle + convention-gap addition

**Date:** 2026-05-07. Captures Mike's dispositions on F9.2 Q1-Q10
following the verification cycle (9 verbatim citations + Reading Y
defense restatement + F4.5.2 reframe verification + test-pin
forbidden_helpers verbatim).

## F9.7 — accepts noted (Q1 / Q2 / Q3 / Q4 / Q5 / Q6 / Q7 / Q8 / Q9 / Q10)

| Question | Disposition | Note |
|---|---|---|
| Q1 | Reading B (Reading Y — subprocess-side via tools/pm_handoff.py) | Two converging reasons — see F9.7.A. Spec amendment scope ships atomically with runtime change per `feedback_spec_amendment_runtime_drift` convention. |
| Q2 | Reading A (re-confirm F2.3 Q1 Reading C — yaml-convention + audit-emit) | No daemon-side strict validation; audit-emit-on-write with `pm.compaction.handoff_written` action. |
| Q3 | Reading A (extend `signal_compaction` permission to PM) | Single tool, callable by both Engineer and PM; handler resolves caller token to whichever supervisor has running dispatch. |
| Q4 | Reading A (loop-side polling on pace_recommendation == 'conserve') | Symmetric with Engineer's compaction detection per `11-engineer-bridge.md:516`. |
| Q5 | Reading B (Settings field; absolute-default under data_dir) | `<data_dir>/pm-handoffs/` default; env-tunable. |
| Q6 | Reading C (content + minimal metadata) | Output: `{content: str | None, written_at: datetime | None}`. |
| Q7 | Reading A (PM-prompt + handoff-doc state anti-flapping) | Hysteresis is PM-prompt's responsibility per Q9-from-commit-7 disposition rationale. |
| Q8 | Reading B (different abstraction unit; threshold does NOT trip) | Pin abstraction-unit-correctness-model distinction explicitly — see F9.7.B. |
| Q9 | Reading A (Q10-from-commit-7 stays rejected; carry-forward RESOLVES) | Compaction once-per-5h-cycle event; average per-tick write count effectively unchanged. |
| Q10 | Reading A (substantive prompt extension at commit 8) | New "Compaction lifecycle" section ~30-40 lines in pm.yaml. |

## F9.7.A — Q1 disposition: Reading Y substance

Reading Y accepted on two converging substantive grounds.

**Reason 1 — defense (4) substrate-evolution-decoupling carries on
operational grounds, not aesthetic grounds.**

Substrate is Mike's product-content git repo with its own evolution
cadence and audience. The substrate filesystem layout (path scheme;
filename convention; possible manifest files; possible per-cycle
subdirectories) is operator-tunable per PM-yaml convention changes.

Concrete failure mode under Reading X: Mike decides handoff docs
need a manifest file or new directory structure → `pm_supervisor.py`
update + daemon redeploy. Under Reading Y: `pm.yaml` convention +
`tools/pm_handoff.py` update — both inside the daemon-orchestration
layer that should evolve with PM behavior. Substrate evolution
shouldn't force daemon evolution. `Settings.pm_handoff_dir` under
Reading X mitigates path-coupling shallowly but not
structural-coupling.

Defense (4) is substantive architecture, not "less coupling is
cleaner" aesthetics. The other Reading-Y defenses ((2) indeterminate;
(3) convention-respect; (6) meta-discipline) are supporting context
only; defense (5) ergonomics rightly removed from the case per
honest restatement.

**Reason 2 — spec-text-consensus cuts the wrong way on
recency-and-deliberateness.**

Five Reading X sources are temporally older (`supervisor_base.py`
and `engineer_supervisor.py` docstrings; `11-engineer-bridge.md`).
The Reading Y disposition cites M5-pm-supervisor-base prep checkpoint
(2026-05-01) — reconsidered position. Reading X sources weren't
re-amended at M5-pm-supervisor-base because that disposition's
spec-amendment scope wasn't traced across the codebase. **Source
count favors Reading X; recency-and-deliberateness favors Reading Y.**

The spec-vs-spec drift is exactly the category F9.8's convention-
gap finding identifies. The convention extension (sub-milestone-
boundary internal-spec-consistency scan) would have caught this
at M5-pm-supervisor-base sub-milestone close.

### Q1 disposition impact on commit 8 spec amendment scope

Per `feedback_spec_amendment_runtime_drift` convention, runtime
change ships with spec update. Commit 8 atomic amendment list:

1. `bridge/supervisor_base.py:196-197` docstring — change "PM's
   reads handoff doc at M5" to align with Reading Y; PM's
   `_reconstruct_state` stays no-op; handoff-read is
   subprocess-prompt concern via `tools/pm_handoff.py`.
2. `bridge/supervisor_base.py:284-298` docstring (default
   `_reconstruct_state` impl) — same amendment to "PM's M5 subclass
   overrides to read the most recent `/product/substrate/pm-handoffs/`
   doc."
3. `bridge/engineer_supervisor.py:23` docstring — change "PMSupervisor
   (M5) overrides this hook with a handoff-doc read" to "PM's
   `_reconstruct_state` stays no-op per M5-pm-supervisor-base P-2
   Reading B; handoff-read is subprocess-prompt concern."
4. `bridge/engineer_supervisor.py:152-154` docstring — same amendment.
5. `docs/kickoff/11-engineer-bridge.md:753` — change "post-respawn
   state reconstruction that reads the most recent
   `/product/substrate/pm-handoffs/` doc" to "post-respawn state
   reconstruction is no-op per architectural separation; handoff-doc
   reading is subprocess-prompt concern via `tools/pm_handoff.py`
   and `pm.yaml` convention."
6. `docs/kickoff/11-engineer-bridge.md` — possibly add new § PM
   compaction subsection symmetric with § Engineer compaction.
7. `docs/working/M5-continuous-pm-driver-grounding.md:399-402`
   (Section 5 framing) — amend to align; in-place annotation per
   F8.6.B carry-forward annotation discipline.
8. `docs/working/M5-continuous-pm-driver-grounding.md:1108` (F2.1
   row 8 framing) — amend "PMSupervisor._reconstruct_state reading
   most-recent handoff" → "tools/pm_handoff.py read+write helpers;
   PMSupervisor unchanged."
9. `docs/kickoff/06-mcp-tools-phase-one.md` — add `write_pm_handoff`
   + `get_most_recent_handoff` tool entries; amend `signal_compaction`
   permission row to include PM.
10. `docs/kickoff/02-architecture.md` — small reference to PM
    compaction mechanism (parallel to Engineer compaction reference).

Amendment lands in commit 8 atomically. No spec-amendment commit
split-off; the runtime change's spec update is part of the same
atomic disposition.

## F9.7.B — Q8 disposition: abstraction-unit-correctness-model distinction (pin)

Q8 reasoning lands as part of the disposition prose, not just as
acceptance. Future-Claude reading the disposition cold sees the
substantive distinction, not just the conclusion.

**Pinned reasoning (Q8 disposition prose):**

Filesystem-by-timestamp and SQL-rowid-DESC have different
**correctness conditions**, not just different surface syntax:

- **SQL-rowid-DESC depends on rowid monotonicity** — a database
  invariant guaranteed by SQLite's append-only ROWID semantics.
  Trustable; doesn't break under operator action.
- **Filesystem-by-timestamp depends on ISO-timestamp lexical sort
  matching chronological order** — a filename convention. PM-
  convention-dependent; can break if Mike changes filename schema
  (e.g., switches from `<iso-timestamp>.md` to `<seq-number>.md`
  or adds prefixes that re-order lexical sort).

A shared "find most recent of ordered sequence" helper would hide
this difference. Future-Claude using a generic helper might assume
both backends have the same correctness model — **they don't**.
Different correctness models → different testing requirements →
different code paths → different helpers warranted.

Per F4.5.2 reframe ("evaluate abstraction unit; reject extraction
when abstraction unit doesn't carry semantic weight"): the
abstraction unit "find most recent of ordered sequence" is generic
sequence-finding competence, NOT load-bearing semantic logic. The
load-bearing semantic logic is per-substrate (rowid-monotonicity
vs filename-convention). Extracting at three would conflate
different correctness models behind a uniform-looking interface.

**`read_current_X` rowid-DESC watch stays at 2** (read_current_pm_mode
+ read_current_focus). `read_most_recent_handoff` is instance 1 of
a NEW pattern (filesystem-most-recent-by-timestamp) — no threshold
concern.

**Sentinel-test pin scope:** the commit-7 invariant pin
(`test_pm_loop_module_does_not_define_read_current_helper`) protects
both `read_current_*` and `read_most_recent_*` naming patterns
within `tools/pm_loop.py` only. The new `read_most_recent_handoff`
in `tools/pm_handoff.py` is outside the pin's file scope; pin stays
green. **No re-litigation of Q9-from-commit-7 needed.**

## F9.8 — convention-gap finding (sub-milestone-boundary internal-spec-consistency scan)

Captured for closeout § Discipline-pattern observations. Refined
per Mike's sub-milestone-boundary scope refinement.

**Phase-boundary scan convention has gap (commit 8 prep, F9.0
surfacing, 2026-05-07).** Existing phase-boundary scan catches
spec-vs-implementation drift (spec says X exists but X doesn't).
Commit 8 surfaced **internal-spec-contradiction across 5+ sources
at compaction-trigger pressure point**, latent through commits 1-7:

- `bridge/supervisor_base.py:196`
- `bridge/supervisor_base.py:284-298`
- `bridge/engineer_supervisor.py:23`
- `bridge/engineer_supervisor.py:152-154`
- `docs/kickoff/11-engineer-bridge.md:753`
- `docs/working/M5-continuous-pm-driver-grounding.md` Section 5 framing
- F2.1 row 8 framing

…all describe Reading X (daemon-side `_reconstruct_state` override).

- `bridge/pm_supervisor.py:20-29`
- `bridge/pm_supervisor.py:167-191`
- `tests/integration/test_pm_supervisor.py:369-410` invariant pin

…describe Reading Y (subprocess-side via prompt-driven MCP call).

Latent through M5-pm-supervisor-base + M5-continuous-pm-driver
commits 1-7 because handoff-reading not implemented; surfaces at
commit 8 implementation pressure.

**Convention extension (refined per Mike's sub-milestone-boundary
refinement):**

Internal-spec-consistency scan runs at **sub-milestone close AND
phase boundary** — paired conventions, neither subsumes the other.

- **Sub-milestone scope** (narrower; triggers more often): any
  architectural concept introduced or modified at this sub-
  milestone — grep mentions across `docs/kickoff/` + `bridge/*.py`
  + `tools/*.py` docstrings; flag mentions divergent from the
  disposition that landed at this sub-milestone. Catches recently-
  created drift at creation point — cheaper to fix; smaller blast
  radius.
- **Phase scope** (broader; triggers less often): full
  architectural-concept inventory; divergence check across all
  sources. Catches accumulated drift across sub-milestones.

**Operational shape:** at sub-milestone close, for each architectural
concept introduced or modified at this sub-milestone, grep all
`docs/kickoff/` + `bridge/*.py` + `tools/*.py` docstrings for
mentions; produce a "concept → mentions" table; flag rows where
mentions diverge on mechanism, location, or behavior. Cheap to run;
catches this category before next-sub-milestone commit-pressure
forces it.

**Worked example.** M5-pm-supervisor-base disposition (P-2 Reading B,
2026-05-01) chose Reading Y for `pm_supervisor.py`'s
`_reconstruct_state` behavior. Sub-milestone-boundary scan at
M5-pm-supervisor-base close would have surfaced: 5 other spec
sources still describe Reading X mechanism — unamended. Disposition
cycle adds spec amendments to the closing commit OR raises a
follow-on commit. Either way, the spec drift doesn't ship into
M5-continuous-pm-driver where commit-8 implementation pressure
forces resolution.

**Convention durability (BACKLOG.md placement).** Lands in
`BACKLOG.md` § Conventions alongside other durable conventions
(test-colocation per Decision 4 from commit 4; carry-forward
note routing per Decision 5 from commits 3-4). M5-pm-supervisor-base
+ M5-continuous-pm-driver instance is the worked example.

**Distinct finding from Q1 disposition.** Q1 dispositions resolves
the contradiction one direction; the convention gap is independent
— even if Q1 had landed as Reading X, the spec-internal-consistency
check would have caught the latent contradiction at M5-pm-supervisor-
base sub-milestone close.

## F9.9 — bidirectional-discipline classification: sixth invocation, fourth direction-shape

Capture for F2.5 closeout discipline-pattern observations.

**Sixth bidirectional-discipline instance.** Direction-shape is new:

| # | Instance | Direction-shape | Locus |
|---|---|---|---|
| 1 | F1.1 task_type γ scope-punt revisit | Substance reversal | Code's first surfacing wrong; revisit produces correction |
| 2 | F5.6.B Q1 closed-enum reversal | Substance reversal | Code's recommendation wrong-on-merits; pushback exposes wrong-ness |
| 3 | F6.5.1 / F6.6.B Q1 framing-precedent reset | Framing-precedent-application | Code's recommendation right but defended via precedent that doesn't carry |
| 4 | F7.5.1 / Q1 (commit 6 prep) | Prep-instruction error | Reviewing-Claude's prep instruction wrong; Code surfaces |
| 5 | F8.6.A Q4 (commit 7 prep) | Hybrid (prep-instruction-error + framing-precedent-application) | Both fired simultaneously |
| 6 | F9.0 / Q1 (commit 8 prep) | **Spec-text-consensus vs reconsidered-disposition tension** | Code's verification revealed source count cuts one way, recency-and-deliberateness cuts the other; disposition substantively requires weighing recency over count |

**Fourth direction-shape captured:** spec-text-consensus vs
reconsidered-disposition tension. When spec sources contradict,
source count is one input but recency and deliberateness can override.
The convention extension (sub-milestone-boundary internal-spec-
consistency scan per F9.8) catches this category before commit-
pressure forces it.

Pattern operating across both review-roles + the timing axis is
genuinely tri-axial: substance / framing-application / direction-of-
prep-error / temporal-source-recency. Capture as four-direction
discipline.

## F9.10 — what lands at commit 8 diff checkpoint (post-disposition)

After Mike's dispositions Q1-Q10:

**Code (new files):**
- `src/meridian_orchestrator/tools/pm_handoff.py` — `write_pm_handoff`
  + `get_most_recent_handoff` MCP tools per Q1-B + Q5-B + Q6-C.
  Pydantic schemas. Closure-captured handoff-dir resolution.
  Filesystem helpers (write timestamped file; list+sort+read).

**Code (edits):**
- `src/meridian_orchestrator/config.py` — `Settings.pm_handoff_dir:
  Path` field per Q5-B (default `<data_dir>/pm-handoffs/`).
- `src/meridian_orchestrator/main.py` — lifespan-time wiring of
  handoff-dir config via closure pattern.
- `src/meridian_orchestrator/server.py` — `_register_pm_handoff_tools`
  helper + invoke from `build_server`.
- `src/meridian_orchestrator/tools/supervisor.py` — `signal_compaction`
  permission widening per Q3-A (engineer + pm); supervisor-resolution
  logic extended.
- `src/meridian_orchestrator/bridge/pm_supervisor.py` — UNCHANGED per
  Q1-B Reading Y. Invariant test stays passing.

**Spec amendments (per F9.7.A spec amendment scope):**
- `bridge/supervisor_base.py:196-197` + `:284-298` docstrings.
- `bridge/engineer_supervisor.py:23` + `:152-154` docstrings.
- `docs/kickoff/11-engineer-bridge.md:753` (+ possibly new § PM
  compaction subsection).
- `docs/kickoff/06-mcp-tools-phase-one.md` — new tool entries +
  `signal_compaction` PM-permission row update.
- `docs/kickoff/02-architecture.md` — small reference.
- This grounding doc Section 5 framing (line 399-402) — in-place
  annotation per F8.6.B.
- F2.1 row 8 framing (line 1108) — amend.

**Yaml extension:**
- `config/agents/pm.yaml` — system_prompt extension per Q10-A
  (new "Compaction lifecycle" section ~30-40 lines).

**Convention addition:**
- `BACKLOG.md` § Conventions — sub-milestone-boundary internal-spec-
  consistency scan per F9.8.

**Tests (per F9.4 estimate ~17-22 unique / 22-29 collected):**
- `tests/unit/tools/test_pm_handoff.py` (new) — handler tests.
- `tests/integration/test_pm_handoff_mcp_wrapper.py` (new) — MCP
  wrapper integration.
- `tests/integration/test_pm_compaction_e2e.py` (new) — end-to-end
  compaction flow.
- `tests/integration/test_signal_compaction.py` (or wherever existing
  signal_compaction tests live) — extend with PM-allowed parametrize
  + supervisor-resolution-to-PMSupervisor case.

**Carry-forward annotations (grounding doc § Carry-forward notes):**
- "→ commits 8-9: per-iteration MCP write call count" — annotate
  RESOLVED 2026-05-07 per Q9-A.

**F2.1 surface-as-finding:**
- Sixth invocation expected at draft. Three-reason framing applies.
- Per F2.5 unique-function metric: trigger at >28 unique. Estimate
  17-22 unique under recommendations; ~125% over baseline; under
  trigger.

**End of Followup-15.**

---

# Followup-16 — commit 9 prep checkpoint (e2e integration tests + drift-scan + sub-milestone-boundary internal-spec-consistency scan + sub-milestone closeout doc)

**Date:** 2026-05-07. **Sub-milestone:** M5-continuous-pm-driver.
**Commit scope:** F2.1 row 9 — closing commit. Three workstreams (E2E
integration coverage, two scans, closeout doc) plus one meta-finding
(convention-doc migration trigger fired during this chain).
**Prerequisites landed:** commits 1-8 (workflow_id α, ff25497;
pm_current_focus, b1ad27a; pm_spin_rationale_log, 433cef5;
audit_sample_log, 1e323ca; effort plumbing, 71d1e86; spec amendment,
1eeea94; continuous loop driver core, af25497; compaction lifecycle +
handoff via tools/pm_handoff.py, 68f0967).

**Structural framing.** Commit 9 closes the chain. Three workstreams
plus one meta-finding:

1. **E2E integration tests** covering full continuous-loop lifecycle.
   Each cross-path component is unit-and-MCP-wrapper tested in
   isolation across commits 2-8 (test_pm_loop_mcp_wrapper.py covers
   loop tools; test_pm_focus_mcp_wrapper.py covers focus tools;
   test_pm_spin_mcp_wrapper.py covers spin tool; test_audit_sample_mcp_wrapper.py
   covers sampler; test_pm_handoff_mcp_wrapper.py covers handoff tools;
   test_pm_compaction_e2e.py covers handoff-write→signal→respawn→handoff-read).
   What's missing: **the per-tick chained sequence** that exercises
   multiple consumers in their natural ordering — a tick where PM
   calls get_loop_state, declares focus, completes prior focus,
   records spin rationale, records sample event, waits for next tick.
   And the cross-tick chain over 3-5 ticks that includes a conserve
   transition triggering the compaction lifecycle.

2. **Drift-scan** per closeout convention (M5-subscription-routing
   precedent). Spec docs + agent yamls + migration headers + relevant
   docstrings. Catches what drifted across commits 1-8.

3. **Sub-milestone-boundary internal-spec-consistency scan** — first
   scheduled invocation per the new convention added at commit 8 F9.8
   to BACKLOG.md § Conventions. M5-pm-supervisor-base + commit 8 was
   the worked example (handoff-mechanism contradiction across 5+ spec
   sources, latent through commits 1-7); this is the convention's
   first runtime exercise. Operational shape: grep architectural-
   concept mentions for concepts introduced or modified at this
   sub-milestone; group by concept; flag divergent framings.

4. **Sub-milestone closeout doc** at `docs/closeouts/M5-continuous-pm-driver.md`
   per docs/kickoff/00-README.md § Sub-milestone closeouts convention.
   The Discipline-pattern observations section will be substantial
   per Mike's prep instructions; warrants chapter-level organization
   (Q1 below).

**Meta-finding (F10.0): convention-doc migration trigger fired.**
`docs/kickoff/00-README.md:152-171` says: "when a fifth durable
discipline convention lands, lift the convention sections out of
this README into a dedicated `docs/conventions/00-README.md` (or
`docs/closeouts/00-README.md` — naming dispositioned at migration
time)." Pre-chain count was 4. During M5-continuous-pm-driver chain,
at least three new conventions landed in BACKLOG.md § Conventions:

- Decision 4 colocation rule (commit 4) — "Settings field tests
  colocate with their consumer."
- Decision 5 carry-forward routing (commits 3-4) — "scope-determines-
  location for carry-forward notes between commits / sub-milestones /
  phases" (this exists in BACKLOG.md but as part of carry-forward
  section rather than § Conventions explicitly; semantically
  convention-shaped).
- F9.8 sub-milestone-boundary internal-spec-consistency scan (commit 8) —
  "internal-spec-consistency scan runs at sub-milestone close AND at
  phase boundary; paired conventions."

Plus closeout-staging notes captured in F2.5:
- F8.6.B carry-forward annotation discipline (commit 7 prep) — "annotate
  in-place preserving the original reasoning chain."
- Code-inspection sentinel observability convention (commit 7 draft) —
  "Mode 1 vs Mode 2 categorization with (b)-revisit trigger."
- F4.5.2 threshold-of-three reframe (commit 3 prep) — "trigger-to-
  evaluate, not mandate-to-extract."

**Trigger fired count: 5+ depending on what reaches the bar.** Q2
below disposes the migration timing. Surfaced as load-bearing F10.0
because the migration is a precedent-setting decision and the discrete
trigger named in 00-README.md has fired during this chain.

**Active carry-forward state at this prep:**

Same-sub-milestone (grounding doc § Carry-forward notes):
- "→ commit 7: cadence-volume + sampler-trigger MCP volume" — RESOLVED
  2026-05-06 at commit 7 prep (F8.2 Q2-B 60s baseline holds).
- "→ commits 8-9: per-iteration MCP write call count" — RESOLVED
  2026-05-07 at commit 8 prep (F9.2 Q9-A; compaction once-per-cycle
  event; average write count effectively unchanged).

Both same-sub-milestone carry-forwards close at commit 9 in
"resolved-during-chain" state. No new same-sub-milestone carry-forwards
expected from commit 9 (closing commit).

Cross-sub-milestone (BACKLOG.md § Carry-forward to future sub-milestones):
- "→ focused-observability sub-milestone (post-M5-flag-flips):
  audit_sample_log re-evaluator" — landed at commit 4 (F5.6.C).

Q6 below evaluates whether commit 9 surfaces additional cross-sub-
milestone carry-forwards.

**Active watch-for-pattern that may trip at this commit:**

1. **F2.1 surface-as-finding rule.** Sixth invocation at commit 8
   landed at 200% (28/14, exactly at trigger threshold but per
   ">200%" rule did not fire). Commit 9's F2.1 estimate is +20.
   Trigger at >40 unique. E2E integration test scope is the
   load-bearing variable: a 5-scenario suite at granularity-of-
   commit-7 (15-19 unique each) is unrealistic — these are scenario
   tests, not handler-shape tests. Realistic count is 5-15 unique.
   Trajectory observation: **commits 2-4 storage-layer overshoot
   pattern** (50%, 37%, 110% over) does NOT predict commit 9's
   shape because commit 9 isn't storage-layer; **commits 5/7 plumbing
   undershoot pattern** (-22% / TBD-7) is the closer analog. Expect
   commit 9 to land 50-100% of estimate. Surface as F2.1 trajectory
   observation at diff checkpoint regardless.

2. **Watch-for-pattern entries** (BACKLOG.md and F3.3/F4.3/F5.3/etc.):
   - `read_current_X` rowid-DESC helper at 2 (commit 8 confirmed).
   - `bridge/` → `tools/` cross-layer imports at 2 (commit 8 confirmed
     no new imports).
   - Production-code-surface-for-testability at 2 (commit 8 confirmed).
   - Closure-captured callable for tools/-layer config: now 5
     instances (graduated from watch to convention per F9.3).
   - Closed-enum sentinel: 9+ instances (graduated per F2.5).

   Commit 9 is closeout work; doesn't introduce new code patterns
   that would trip these watches. All confirmed-no-trip at commit 8;
   confirmed-no-trip at commit 9 by inspection of the workstream
   shape (E2E tests, drift-scan, closeout doc).

3. **Bidirectional-discipline pattern.** Six invocations through
   commit 8 across four direction-shapes (substance reversal /
   framing-precedent-application / prep-instruction-error / hybrid /
   spec-text-consensus-vs-reconsidered-disposition). Closing commit
   may surface a seventh during drift-scan or internal-spec-consistency
   scan — these are review-mindset workstreams that historically
   surface review-mindset findings. Expect 0-2 invocations.

4. **Convention-establishment cadence.** This chain established 3-6
   new durable conventions (depending on count). Compared to
   M5-subscription-routing's 0-1, the cadence is ~5x. Surface as
   discipline-pattern observation in the closeout (Q1 chapter
   organization recommendation).

---

## F10.0 — convention-doc migration trigger (load-bearing meta-finding)

**Surfaced before F10.1 touch points** because the migration is a
precedent-setting decision that shapes commit 9's scope.

### The trigger

`docs/kickoff/00-README.md:167-171` (verbatim):

> **Migration trigger:** when a fifth durable discipline convention
> lands, lift the convention sections out of this README into a
> dedicated `docs/conventions/00-README.md` (or
> `docs/closeouts/00-README.md` — naming dispositioned at migration
> time). Discrete trigger, not a vibes call. Until the trigger fires,
> these sections stay co-located here.

The discrete trigger has fired during this chain. The current count
in 00-README.md (line 152) is 4; this chain added enough conventions
to reach 5+ by any reasonable counting standard.

### Convention inventory (as of commit 8)

Pre-chain (per 00-README.md:152-165):

1. Sub-milestone closeout artifact (M5-subscription-routing close, 2026-05-02).
2. Phase-boundary acceptance-criteria-coverage scan (2026-05-03).
3. Watch-for-pattern (mid-M5 BACKLOG entries with explicit thresholds).
4. Threshold-of-three same-commit extraction (M5-subscription-routing commit 2 UTC-coercion precedent).

Added in this chain (definitive):

5. Settings field tests colocate with consumers (Decision 4, commit 4 — BACKLOG.md § Conventions).
6. Internal-spec-consistency scan at sub-milestone close (F9.8, commit 8 — BACKLOG.md § Conventions).

Added in this chain (closeout-staging; not yet promoted to BACKLOG.md § Conventions):

7. Decision 5 carry-forward routing (commits 3-4) — currently in BACKLOG.md but as a carry-forward header note rather than convention entry. Convention-shaped semantically.
8. Carry-forward annotation discipline (F8.6.B, commit 7 prep) — currently only in F2.5 closeout-staging.
9. Code-inspection sentinel observability (commit 7 draft, F2.5) — Mode 1 vs Mode 2 categorization + (b)-revisit trigger. Currently only in F2.5 closeout-staging.
10. Threshold-of-three reframed as trigger-to-evaluate, not mandate-to-extract (F4.5.2, commit 3 prep) — refines #4 above; currently only in F2.5 closeout-staging.

**Conservative count** (BACKLOG.md § Conventions only): 6 (4 pre-chain + Decision 4 + F9.8). Trigger fired.

**Liberal count** (including closeout-staging): 10. Trigger fired with margin.

### Migration timing options (Q2 below)

The trigger naming itself doesn't disposition WHEN to migrate — only
WHETHER. Options:

- **Reading A — migrate at commit 9.** Closing commit is the natural
  boundary; the closeout doc would reference the new convention doc
  rather than 00-README.md. Adds non-trivial scope to commit 9
  (extract sections, create new doc, update cross-references in
  00-README.md + BACKLOG.md). Tests not affected. Spec amendment
  in scope.
- **Reading B — defer to next sub-milestone.** Migration is hygiene
  work that doesn't fit naturally in commit 9's three-workstream
  shape. Surface as BACKLOG entry; address at first hygiene-touchpoint
  in M5-flag-flips or later. Risk: convention authors at intermediate
  sub-milestones add to the wrong location until migration lands.
- **Reading C — migrate at commit 9 BUT scope-narrowed.** Create the
  new doc + move the four pre-chain conventions; leave the chain's
  6+ added conventions where they are (BACKLOG.md § Conventions and
  F2.5 closeout-staging). Operator-decides during follow-up whether
  to consolidate further. Lower scope; preserves "where conventions
  currently live" reading-discipline.
- **Reading D — migrate at commit 9 with full consolidation.**
  Reading A's scope plus actively promoting the closeout-staging
  conventions (#7-10 in inventory above) to the new doc. Highest
  scope; cleanest end-state.

**Recommendation: Reading B (defer).** Argument: commit 9's three-
workstream shape (E2E tests + scans + closeout doc) is already large.
Migration is genuine hygiene work that benefits from focused attention,
not a piggyback on closing-commit scope. The risk under Reading B
(convention authors add to wrong location at intermediate sub-
milestones) is low: closeout-staging is a known interim location;
authors during M5-flag-flips would naturally consolidate at next
hygiene cycle.

If Mike disposes Reading A or D: Q1 chapter organization for the
closeout's Discipline-pattern observations section becomes more
load-bearing because the closeout would also reference the migrated
convention doc — chapter structure should align with convention
groupings.

If Mike disposes Reading C: clean partial migration; closeout doc
references the new convention doc for the four-pre-chain conventions
+ references BACKLOG.md and F2.5 for the chain-added conventions.

### Discipline pattern in the trigger itself

Even if the migration defers, surface this in the closeout's
Discipline-pattern observations chapter: **"convention-establishment
cadence at architecturally-decisive sub-milestones is ~5x baseline
sub-milestone cadence."** The migration trigger was set at 4
conventions assuming linear accumulation; this chain proved the
accumulation is non-linear. Future sub-milestones with similar
density (M5-flag-flips? M6 dashboard?) may want pre-migration scoping.

---

## F10.1 — touch points (per recommended dispositions)

| File | Status | Touch shape |
|---|---|---|
| `tests/integration/test_continuous_loop_e2e.py` | NEW | Per-tick chained sequence + cross-tick chain over 3-5 ticks + conserve transition triggering compaction. Q3 below disposes the test-shape granularity (single mega-test / 2-3 focused / 5+ scenario tests). |
| `docs/closeouts/M5-continuous-pm-driver.md` | NEW | Sub-milestone closeout per 00-README.md convention. Q1 below disposes the Discipline-pattern observations chapter organization. |
| `docs/working/M5-continuous-pm-driver-grounding.md` | EDIT | Followup-17 (this prep's closeout-feedback cycle, when it lands). Carry-forward annotations RESOLVED for both same-sub-milestone entries. F10 dispositions captured. |
| `BACKLOG.md` | EDIT | Drift-scan + internal-spec-consistency-scan findings (if any) routed forward. Possibly a new convention entry if Q2 disposes Reading C / D. Possibly Q6 cross-sub-milestone carry-forwards. |
| `docs/kickoff/00-README.md` | EDIT (conditional on Q2) | Convention count update; if migration lands at commit 9 (Q2 Reading A/C/D), section moves out + cross-reference added. |
| `docs/conventions/00-README.md` | NEW (conditional on Q2-A/C/D) | Migrated convention sections per Q2 disposition. Naming TBD per migration spec. |
| Various spec/yaml files | EDIT (conditional on drift-scan findings) | Drift-scan typically surfaces 0-3 docstring milestone-attribution slips + 0-2 yaml-allowlist drifts. M5-subscription-routing close found 2 (allowlist + docstring). |
| Various spec files | EDIT (conditional on internal-spec-consistency scan) | The convention's first invocation; scope per the operational shape in BACKLOG.md § Conventions. |

---

## F10.2 — open questions (Q1-Q7)

### Q1 — Closeout's Discipline-pattern observations chapter organization (precedent-setting)

The Discipline-pattern observations section will be substantial — 14+
patterns from grounding doc closeout-staging notes. M5-subscription-
routing precedent uses a flat bulleted list (4 items). Scaling that
shape to 14+ items breaks readability.

- **Reading A — flat list (M5-subscription-routing precedent).** Each
  pattern as a top-level bullet under § Discipline-pattern observations.
  Simple; precedent-conforming; readable at 4 patterns; brittle at 14+.
- **Reading B — chapter-level organization** (Mike's prep-instruction
  recommendation). Group patterns into thematic chapters within the
  Discipline-pattern observations section. Candidate chapter
  groupings:
  - **Review-process patterns**: bidirectional-contradiction discipline,
    verification-first at architecturally-consequential dispositions,
    explicit review at chain close, prep-instruction precision.
  - **Test/code-conventions**: closed-enum sentinel graduation,
    code-inspection sentinel observability, Decision 4 colocation,
    contract tests fail informatively.
  - **Pattern-threshold management**: threshold-of-three reframe,
    closure-captured-callable graduation, closed-enum graduation,
    watch-for-pattern resolution shapes.
  - **Carry-forward and convention discipline**: Decision 5 routing,
    F8.6.B annotation discipline, sub-milestone-boundary internal-
    spec-consistency scan, F2.1 surface-as-finding rule, accept-with-
    revisit-trigger pattern.
  - **F2.1 trajectory observations**: per-commit overshoot/undershoot
    correlated with commit shape; unique-function metric clarification.
- **Reading C — separate file**. Move discipline-pattern observations
  to `docs/working/M5-continuous-pm-driver-discipline-patterns.md` as
  a standalone artifact; closeout doc has a brief summary section
  pointing at it. Highest extraction; full content stays organized
  but findability across chains may suffer (closeout-readers expect
  the patterns inside the closeout).

**Recommendation: Reading B (chapter-level).** Reading A's flat
shape genuinely doesn't scale — at 14+ items the section becomes a
wall of bullets that's harder to read than M5-subscription-routing's
4-item shape. Reading C extracts too aggressively; the patterns
belong with the chain that established them. Reading B's chapter-
level groupings are the natural compromise: structure that admits
volume without splitting findability.

The candidate chapter groupings above are suggestions; final
groupings should emerge during draft. Surface chapter list as a
"Closeout structure preview" at the top of the Discipline-pattern
section so future-Claude reading the closeout cold sees the
organization-shape before the content.

If Mike disposes Reading A: shorten the patterns to one-line
summaries; defer detailed prose to the grounding doc cross-references.

If Mike disposes Reading C: closeout's Discipline-pattern section
becomes a TOC-shape pointing at the standalone file; standalone
file uses Reading B chapter organization.

### Q2 — Convention-doc migration timing (per F10.0)

See F10.0 for full readings + recommendation.

**Recommendation: Reading B (defer to next sub-milestone).**

### Q3 — E2E test scope and granularity

What does the E2E integration test suite at commit 9 actually
exercise?

- **Reading A — single mega-test** that exercises the full chain in
  one async test function. Setup PM supervisor → simulate 5-10 ticks
  with snapshot transitions → assert all cross-path state at end.
  Lowest function count; hardest to debug when failures land mid-chain.
- **Reading B — 2-3 focused scenario tests.** Split by scenario
  archetype: (a) per-tick chained sequence at a single tick, (b)
  cross-tick chain over 3-5 ticks with focus completion + supersede,
  (c) conserve transition triggering compaction lifecycle. Each test
  exercises an end-to-end slice; failures localize to scenario.
- **Reading C — 5+ scenario tests.** Each scenario archetype above
  plus: (d) cold-start (no prior handoff), (e) post-compaction
  respawn reading prior handoff, (f) Mike-presence interaction (paused
  iteration with mike_presence rationale), (g) anti-flapping
  discipline (post-compaction within-window doesn't re-compact).
  Highest function count; cleanest scenario isolation; most
  fixture-setup overhead.

**Recommendation: Reading B (2-3 focused scenario tests).** Reading A
is operationally fragile (test failures land in the middle of a long
chain; debugging requires mental reconstruction of where state
diverged). Reading C is over-scoped — scenarios (d)-(f) are already
covered by existing per-component MCP wrapper tests:
- Cold-start handoff: test_pm_token_get_most_recent_handoff_cold_start_returns_none
- Post-compaction respawn: test_pm_compaction_e2e (commit 8)
- Mike-presence: test_pm_loop covers `mike_presence` rationale_tag
  via current_pm_mode field on subscription_5h_state
- Anti-flapping: PM-prompt-judgment territory (Q9-from-commit-7
  rationale); not a daemon-testable behavior at this layer

The cross-path that's NOT yet covered is the per-tick chained
sequence and the conserve-transition compaction trigger. Reading B
covers both with reasonable granularity:
- E2E test 1: per-tick chained sequence (single tick, full
  consumer chain).
- E2E test 2: cross-tick chain (3 ticks, focus state evolution).
- E2E test 3: conserve transition (snapshot flips, compaction
  triggered, full lifecycle through respawn).

Estimated unique test count: ~3 unique scenarios × ~3-5 unique
assertions per scenario at the test-function granularity = ~3-5
unique tests under Reading B. Conservative compared to F2.1's +20
estimate; surface as F2.1 trajectory observation.

### Q4 — Sub-milestone-boundary internal-spec-consistency scan execution shape

The convention is new; commit 9 is its first invocation. Execution
shape:

- **Reading A — execute in commit 9.** Run the scan; surface findings
  in the closeout's Drift-detection scan section (alongside the
  existing convention's findings); fix any flagged divergences in
  the same commit.
- **Reading B — defer to a dedicated commit.** Closing commit's
  three-workstream shape is already large; scan adds non-trivial
  scope. Defer to a hygiene commit at start of M5-flag-flips.
- **Reading C — execute in commit 9 with reduced scope.** Run the
  scan but limit to architectural concepts introduced or modified
  at commit 7 (continuous loop driver) and commit 8 (compaction
  lifecycle) — the most recent + highest-density. Skip earlier
  commits' concepts since they've already had downstream-pressure
  to surface drift.

**Recommendation: Reading A (execute in commit 9).** The convention
was added at commit 8 specifically to catch sub-milestone-internal
drift at sub-milestone close; deferring its first invocation past
the close defeats the convention's purpose. Reading C's reduced
scope misses the point — earlier commits' concepts may have drifted
silently exactly because they haven't had downstream pressure (the
M5-pm-supervisor-base + commit 8 worked example shows this).

Operational shape per BACKLOG.md § Conventions entry: for each
architectural concept introduced or modified during commits 1-8,
grep mentions across `docs/kickoff/` + `bridge/*.py` + `tools/*.py`
docstrings + relevant grounding doc sections; produce a "concept →
mentions" table; flag mentions divergent from the disposition that
landed.

Concept inventory for the scan (preliminary; refine at draft):
- `workflow_id` denormalization (commit 1)
- `pm_current_focus` table + completion-validation contract (commit 2)
- `pm_spin_rationale_log` (commit 3)
- `audit_sample_log` + sampling percentage (commit 4)
- Effort plumbing yaml field (commit 5)
- Spec amendment (Opus 4.7 effort + thinking) (commit 6)
- Continuous loop driver (commit 7)
- Compaction lifecycle / handoff / signal_compaction widening (commit 8)

Concept-by-concept grep + group + flag is the work product. Findings
land in the closeout's Drift-detection scan section under a new
sub-section "Sub-milestone-boundary internal-spec-consistency scan
(first invocation)."

### Q5 — Closeout doc length expectations

M5-subscription-routing closeout: 150 lines / 12 sections. M5-
continuous-pm-driver chain is 2-3x larger by commit count and
~3-5x larger by decision density. Soft length targets:

- **Reading A — proportional scaling**: ~300-450 lines. Simple
  proportional argument from precedent's 150 / 6 commits = 25
  lines/commit baseline; 9 commits × 25 = 225, scaled up for
  decision density to 300-450.
- **Reading B — content-driven, no soft target**: write what the
  content needs; review for compression after draft. Avoid
  pre-committing to length that may force omissions.
- **Reading C — explicit length cap**: 500 lines. Forces
  compression discipline; if content overflows, extract
  Discipline-pattern observations to standalone (Q1 Reading C
  fallback).

**Recommendation: Reading B (content-driven, no soft target).**
Closeout is the "if I read this chain cold six months from now"
artifact per 00-README.md. Length should serve readability.
M5-subscription-routing's 150 lines was right for that chain;
M5-continuous-pm-driver's right length is whatever the content
needs given Q1 chapter-level organization.

Surface at draft: if length exceeds ~700 lines, evaluate
compression vs Q1 Reading C extraction. Don't pre-commit to a
length cap at prep time.

### Q6 — Cross-sub-milestone carry-forwards (BACKLOG.md § Carry-forward to future sub-milestones)

Existing entry: audit_sample_log re-evaluator → focused-observability
sub-milestone (commit 4 / F5.6.C).

Does commit 9 surface additional cross-sub-milestone carry-forwards?

- **Reading A — none expected**. Commits 1-8 each surfaced their own
  carry-forwards at prep time; closing commit doesn't introduce new
  scope that would generate carry-forwards.
- **Reading B — internal-spec-consistency scan may surface some**.
  If the scan flags concept-divergences that aren't fixable in
  commit 9 scope (e.g., cross-cutting spec drift that touches
  M-level documentation), those become cross-sub-milestone carry-
  forwards.
- **Reading C — convention-doc migration deferral creates one** (if
  Q2 disposes Reading B). Add a BACKLOG entry routing the migration
  to next sub-milestone.

**Recommendation: Reading B + Reading C composite.** The internal-
spec-consistency scan may or may not surface carry-forwards (depends
on findings); always-possible-but-not-pre-determinable. The
convention-doc migration deferral (Q2-B) deterministically generates
one carry-forward.

Surface during draft: if the scan flags any concept that needs
cross-sub-milestone resolution, add to BACKLOG.md § Carry-forward
to future sub-milestones with explicit destination + reasoning.

### Q7 — Convention-establishment precedent in the closeout

The convention-establishment cadence at this chain (~5x baseline) is
itself a discipline-pattern observation. Should the closeout pin
this cadence as a precedent for evaluating future sub-milestones?

- **Reading A — yes, pin as expected pattern at architectural
  density**. Closeout includes a section: "Convention-establishment
  cadence correlates with architectural decision density; sub-
  milestones at M5-continuous-pm-driver-style density should expect
  5+ new conventions, not the M5-subscription-routing baseline of
  0-1." Future-Claude reads this and pre-scopes convention-doc work
  accordingly.
- **Reading B — observe, don't pin**. Pin the observation but not
  the pattern; one chain's data isn't enough to justify a precedent.
  M6 dashboard work may have similar density (or may not); pinning
  prematurely risks miscalibration.
- **Reading C — don't surface**. Convention-establishment cadence
  is meta-discipline-not-substantive-discipline; the closeout's
  Discipline-pattern observations should focus on substantive
  patterns (review process, contract tests, etc.) rather than
  meta-observations about pattern frequency.

**Recommendation: Reading B (observe, don't pin yet).** One chain's
~5x cadence is a single data point. Surface it explicitly so future-
Claude has the data; don't pre-commit to a pattern that needs
2-3 chains' worth of data to validate.

The framing for the closeout: "Convention-establishment cadence
during this chain was 5+ conventions across 8 commits, vs M5-
subscription-routing's 0-1 across 6 commits. Whether this is
cadence-correlated-with-architectural-density (commit-shape-
dependent) or M5-continuous-pm-driver-specific (one-off chain
shape) needs more chains' worth of data. Pre-scoping suggestion:
at sub-milestones with similar architectural density (multiple
load-bearing dispositions; multiple new spec amendments; high
prep-checkpoint decision count), pre-allocate scope for convention
establishment + migration evaluation."

---

## F10.3 — watch-for-pattern check

| Pattern | Status pre-commit | Commit 9 instance | Disposition |
|---|---|---|---|
| Closed-enum CHECK + sentinel | Graduated to convention (9+ instances) | +0 expected; closeout work doesn't introduce new closed-enum surfaces. | No threshold concern. |
| `read_current_X` rowid-DESC helper | 2 (commit 8 confirmed) | +0 expected. Filesystem-by-timestamp pattern (commit 8) at instance 1 of NEW pattern; no further work at commit 9. | No trip. |
| `bridge/` → `tools/` cross-layer imports | 2 | +0 expected. Closeout work is docs + tests + scans; no new bridge code. | No trip. |
| Production-code-surface-for-testability | 2 | +0 expected. | No trip. |
| Closure-captured callable | 5 (graduated per F9.3) | +0; pattern is now established convention. | No threshold concern. |
| Code-inspection sentinel | 2 (Q4 + Q9 from commit 7) | +0 expected. Closeout work doesn't introduce new sentinels. | Confirmed no Mode 1 / Mode 2 fires through commit 8; commit 9 inspection-shape unchanged. |
| Audit-action `pm.<surface>.<verb>` namespace | 7 (post-commit-8: 5 prior + handoff_written + 2 compaction prefix) | +0 expected. | No expansion concern. |
| F2.1 surface-as-finding rule | 6 invocations + 1 NOT-FIRED clarification | Likely +1 at commit 9 draft. F2.1 estimate +20; expected actual 5-15 unique under Reading B. Trajectory observation: closeout commits undershoot estimates because they're verification + docs work, not net-new code. | Surface at diff checkpoint. |
| Bidirectional-discipline | 6 invocations + 4 direction-shapes | Possible +0 to +2 at commit 9 (drift-scan + internal-spec-consistency scan are review-mindset workstreams that historically surface review findings). | Surface in closeout if invocations land. |
| Convention-establishment cadence | NEW observation at this chain (5+ vs M5-subscription-routing baseline 0-1) | +0 conventions expected at commit 9; the work is closing existing conventions, not establishing new ones. | Q7 disposes whether to pin as precedent. |

---

## F10.4 — test-target estimate

F2.1 estimate: +20. Working breakdown under recommended dispositions
(Q1-B, Q2-B, Q3-B, Q4-A, Q5-B, Q6-B+C, Q7-B):

| Layer | Count |
|---|---|
| E2E test 1: per-tick chained sequence (full consumer chain in one tick) | 1-2 |
| E2E test 2: cross-tick chain (3 ticks; focus state evolution; supersede + complete + new declare) | 1-2 |
| E2E test 3: conserve transition (snapshot flips → compaction → respawn) | 1-2 |
| Drift-scan fix tests (if any drift surfaces) | 0-2 |
| Internal-spec-consistency scan fix tests (if any divergence surfaces) | 0-2 |
| Closeout doc test surface | 0 (no new tests) |
| **Total under recommendations** | **3-10 unique** |

**vs F2.1 estimate of +20 unique:** 3-10 / 20 = 15-50%. **Material
undershoot.** F2.1's +20 estimate likely anticipated higher E2E
volume (perhaps Reading C-style 5+ scenario tests + drift fixes).
Recommendation rationale: scenarios (d)-(f) named in Q3 Reading C
are already covered by existing per-component tests; net-new
scenario coverage at commit 9 is 3 scenarios.

**F2.1 surface-as-finding rule trigger evaluation:** divergence is
~50-85% under estimate (not over). Per F2.5 unique-function metric,
trigger fires at >200% OVER. Under-estimate divergence does NOT
trigger re-prep but warrants F2.1 trajectory observation.

**Trajectory observation across chain (per F2.5 trajectory framing):**

| Commit | Unique actual | F2.1 estimate | Divergence |
|---|---|---|---|
| 2 | ~21 | ~14 | +50% |
| 3 | ~11 | ~8 | +37% |
| 4 | ~21 | ~10 | +110% |
| 5 | 7 | ~9 | -22% |
| 6 | 0 (docs) | 0 | n/a |
| 7 | TBD (estimated 17 actual; needs verification) | 18 | ~-5% |
| 8 | 28 | 14 | +100% |
| 9 (projected) | 3-10 | 20 | -50% to -85% |

**Pattern observation across the chain:**
- Storage-layer commits (2, 3, 4) consistently overshoot F2.1
  estimates due to parametrize blow-ups, default-populates, and
  sentinel mirror tests.
- Plumbing/integration commits (5, 7) track close to estimates.
- Spec-amendment commit (6) has 0 tests by design.
- Architectural-keystone commit (8) overshoots due to multi-tool
  test surface + spec amendment test surface.
- Closing commit (9 projected) undershoots because the work shape
  is verification + docs, not net-new code.

**F2.1 estimates are honest as estimates** (per F2.1's own
disclaimer: "Test counts are targets, not budgets"). Trajectory
shape across commit-shapes is now well-established. Future sub-
milestone preps should use commit-shape-aware estimation rather
than uniform per-commit numbers.

---

## F10.5 — anticipated pushbacks

Five weak points likely to surface at review:

1. **Q2 Reading B (defer migration) leaves convention-doc location
   ambiguous through M5-flag-flips.** Reviewing-Claude may push back:
   "If trigger has fired, executing the migration AT commit 9 is the
   discrete-trigger discipline; deferring is convention drift."
   Counter-argument: commit 9's three-workstream scope is already
   closing-commit-large; migration is hygiene work that benefits
   from focused attention. The discrete-trigger discipline says
   "do the migration when 5 conventions land," not "do the migration
   in the same commit that crosses the threshold." Reading A or D
   acceptable if Mike values trigger-fire-discipline over scope
   management.

2. **Q3 Reading B 3-test scope may miss real cross-path defects.**
   Per memory `feedback_integration_defects_need_production_shape_tests`,
   defects only surface when independently-evolved code paths
   actually integrate. Reading B's 3 scenarios cover common paths;
   Reading C's 5+ scenarios cover edge-cases that may produce real
   defects. Reading B's case rests on existing per-component tests
   covering scenarios (d)-(f) — but per-component tests don't
   integrate them with the loop. If reviewing-Claude pushes back:
   "scenario (d) cold-start needs integration coverage; existing
   test_pm_token_get_most_recent_handoff_cold_start_returns_none
   tests the handler in isolation, not in a continuous-loop
   context," that's defensible. Reading C lands.

3. **Q4 internal-spec-consistency scan first invocation may surface
   findings that complicate commit 9 scope.** The convention is
   designed to catch drift early; first invocation may catch a
   non-trivial divergence (analogous to the M5-pm-supervisor-base +
   commit 8 worked example). If a finding lands that requires
   spec amendments across multiple files, commit 9 scope grows.
   Mitigation: surface findings as drift-scan-style sub-section in
   closeout; fix in same commit if mechanical (yaml allowlists,
   docstring slips); BACKLOG if structural.

4. **Q1 Reading B chapter-level organization is precedent-setting.**
   M5-subscription-routing precedent uses flat list; switching to
   chapter-level at this closeout means future closeouts inherit
   the question of "which shape?" Reviewing-Claude may push back:
   "Stick with flat list for precedent stability; if 14 patterns
   is too many, pre-prune which deserve closeout-level treatment vs
   F2.5 closeout-staging-only." Counter: 14+ patterns is genuinely
   too many for a flat list; pruning to fit a precedent shape is
   tail-wagging-dog. Reading B is the right shape; precedent updates
   to "flat list at small N; chapters at N >= ~7."

5. **F2.1 trajectory observation across the chain is itself worth
   formalizing as a discipline-pattern.** Per the Q7 Reading B
   recommendation (observe, don't pin), the cadence-vs-density
   observation is one data point. The F2.1 trajectory across
   commit-shapes is now 8 data points (commits 2-9 projected) — a
   real pattern. Closeout's discipline-pattern observations should
   include "F2.1 estimates are commit-shape-correlated, not
   uniform: storage-layer overshoots, plumbing tracks, docs is
   zero, architectural-keystone overshoots, closing commit
   undershoots." Future preps use shape-aware estimation.

---

## F10.6 — what lands at the diff checkpoint

After Mike disposition Q1-Q7 + the five anticipated-pushback items
above:

**Code (new files):**
- `tests/integration/test_continuous_loop_e2e.py` — 3 scenario tests
  per Q3 Reading B (per-tick chain + cross-tick chain + conserve
  transition). Or 5+ tests per Reading C if disposed.

**Docs (new files):**
- `docs/closeouts/M5-continuous-pm-driver.md` — sub-milestone
  closeout per 00-README.md convention; chapter-level Discipline-
  pattern observations per Q1 Reading B.

**Docs (edits):**
- `docs/working/M5-continuous-pm-driver-grounding.md` — Followup-17
  feedback cycle on this prep when it lands; carry-forward annotations
  RESOLVED for both same-sub-milestone entries; F10 dispositions
  captured.
- `BACKLOG.md` — drift-scan + internal-spec-consistency-scan
  findings if any; new cross-sub-milestone carry-forwards per Q6
  (convention-doc migration if Q2-B; any scan-surfaced items).
- Various spec/yaml/migration files — drift-scan fixes (typically
  0-3 mechanical updates).

**Conditional on Q2 disposition:**
- Q2-A or D: `docs/conventions/00-README.md` (or
  `docs/closeouts/00-README.md`) NEW — migrated convention sections.
  `docs/kickoff/00-README.md` — extracted sections + cross-reference.
- Q2-B (recommended): no migration this commit; BACKLOG entry routes
  forward.
- Q2-C: partial migration; closeout cross-references both old and
  new locations.

**Tests per F10.4 estimate (~3-10 unique).** F2.1 trajectory
observation surfaced at diff checkpoint per unique-function metric;
trajectory is undershoot, not overshoot — does NOT fire trigger.
Surface as F2.1 finding for the closeout's discipline-pattern
chapter on test-shape patterns.

**Discipline-pattern observations** in closeout (chapter-level per
Q1 Reading B) covering at minimum:
- Review-process patterns
- Test/code-conventions
- Pattern-threshold management
- Carry-forward and convention discipline
- F2.1 trajectory observations across commit-shapes

**Both same-sub-milestone carry-forwards (cadence-volume + per-iteration
write count) RESOLVED.**

**Cross-sub-milestone carry-forwards updated** per Q6 disposition.

**Internal-spec-consistency scan first invocation findings** in
closeout's Drift-detection scan section under sub-section "Sub-
milestone-boundary internal-spec-consistency scan (first invocation,
M5-continuous-pm-driver close, 2026-05-07)."

**Convention-establishment cadence observation** captured per Q7
Reading B (observe, don't pin yet).

**Final commit closes the chain.** Sub-milestone closes; remaining
M5 work is M5-flag-flips + M5-acceptance.

**End of Followup-16.**

---

# Followup-17 — F10 disposition feedback + commit 9 outcomes

**Date:** 2026-05-07. Captures Mike's dispositions on F10.0 + Q1-Q7 + format refinements + commit-9 verification asks. Closing follow-up of the M5-continuous-pm-driver chain.

## F10.7 — accepts noted (F10.0 / Q1-Q7)

| Disposition | Resolution |
|---|---|
| F10.0 / Q2 | **Reading A reversed** (Code's Q2 Reading B → Mike's Reading A): execute migration NOW. Trigger fired during chain; deferring undermines the discrete-trigger discipline. Migration scope was bounded; executed at commit 9. |
| Q1 | **Reading B accepted with refinement**: F2.1 trajectory promoted to standalone chapter (8+ data points; well-grounded). Final 5 chapters: review-process discipline / test-code conventions / pattern-threshold management / carry-forward and convention discipline / F2.1 trajectory + commit-shape correlation framework. |
| Q3 | **Reading B accepted with verification ask**: 3 focused scenario tests (per-tick chain + cross-tick chain + conserve transition). Mike directed reading test_pm_compaction_e2e.py body before drafting — done; existing E2E covers the handoff-write→signal→respawn→handoff-read cycle in isolation; new E2E adds the per-tick chained sequence + cross-tick + conserve transition that per-component tests don't exercise together. |
| Q4 | **Reading A accepted**: execute scan in commit 9. Did so; first invocation surfaced no findings beyond the F2.3 Q1 stale prose finding caught by the standard drift-scan. Convention-validation outcome: when commit-N atomic amendment per `feedback_spec_amendment_runtime_drift` does the heavy lifting, the boundary scan finds little. Future sub-milestones with less-disciplined amendment will exercise the scan more strenuously. |
| Q5 | **Reading B accepted**: content-driven length. Closeout landed at 288 lines (vs M5-subscription-routing's 150). Substantial; appropriate for chain density. |
| Q6 | **Reading B + C composite accepted with destination update**: scan-surfaced (none beyond drift-scan finding) + Q2-A migration eliminates the convention-doc deferral as a carry-forward source + audit_sample_log re-evaluator destination updated 2026-05-07 to "post-M4d operational use; runtime-data-driven trigger." |
| Q7 | **Reading B accepted**: observe convention-establishment cadence as single data point; don't pin yet. F2.1 trajectory has 8+ data points and is pin-able as discipline-pattern observation. Closeout chapter 5 + chapter 4 distinguish well-grounded vs observed-not-pinned. |
| Format refinements | All accepted: timestamps compress to elapsed-approximate + harness cross-reference; tool-call breakdown stays; quality signals primary; compaction-signals fold into quality-signals unless compaction actually fires. |
| Commit-7 actual count verification | **Verified before propagating** per Mike's instruction: 16 unique / 24 collected (12 in test_pm_loop.py + 4 in test_pm_loop_mcp_wrapper.py = 16 unique; 8 parametrize blow-ups). vs F2.1 +18 estimate = **-11% under** (not -5% as my Followup-16 prep estimated). Trajectory table in closeout uses 16 / -11%. |

## F10.7.A — Q2 Reading A migration: bounded structure decision

Mike's Reading A came with a conditional fallback: "If the migration's structure decision (what the new conventions doc looks like) takes more than 30 minutes to disposition during commit 9 prep, defer per Reading B."

Structure decision was bounded. The new doc lifts:

- 4 pre-chain conventions from kickoff/00-README.md (sub-milestone closeout artifact; phase-boundary acceptance-criteria-coverage scan; watch-for-pattern; threshold-of-three same-commit extraction).
- 5 conventions added during this chain (sub-milestone-boundary internal-spec-consistency scan; Settings field test colocation; carry-forward note routing; carry-forward annotation discipline; code-inspection sentinel observability).
- 1 observation-not-yet-pinned (convention-establishment cadence; single data point).

Numbering structure is sequential 1-9 + observation footer. No drama in structure decision; well under the 30-min budget.

`docs/conventions/00-README.md` created; `docs/kickoff/00-README.md` updated with pointer paragraph; `BACKLOG.md` § Conventions section reduced to pointer; closeout doc cross-references the new conventions doc.

## F10.7.B — Drift-scan + sub-milestone-boundary internal-spec-consistency scan outcomes

**Standard drift-scan**: 2 findings, both fixed at commit 9:

1. `docs/working/M5-continuous-pm-driver-grounding.md:1293` stale Reading X reference in F2.3 Q1 prose (pre-Reading-Y disposition). Fixed in-place with `[SUPERSEDED 2026-05-07]` annotation per Convention 8.
2. `BACKLOG.md` cross-sub-milestone carry-forward destination stale ("post-M5-flag-flips"); updated to runtime-data-driven trigger per Q6.

**Sub-milestone-boundary internal-spec-consistency scan** (first invocation): no findings beyond the drift-scan's F2.3 Q1 catch. Convention-validation outcome captured in closeout § Sub-milestone-boundary internal-spec-consistency scan (first invocation).

## F10.7.C — Test count outcomes vs F2.1 estimate

F2.1 commit-9 estimate was +20. Actual: 3 unique tests. Divergence: -85% under estimate.

Trajectory across chain (all 9 commits now well-grounded):

- Storage-layer commits (2, 4, 8): +79% / +110% / +107% over estimate
- Plumbing/wiring commits (1, 5, 7): +13% / -22% / -11% (track close)
- Docs-only commit (6): 0 by design
- Closing commit (9): -85% under (verification + docs work)

F2.1 surface-as-finding rule trigger NEVER FIRED across 9 commits (none exceeded 200% over estimate). Rule is working as designed: catch divergence as observation, not enforce per-commit budget. Closeout chapter 5 captures the framework for forward use.

## F10.7.D — Both same-sub-milestone carry-forwards RESOLVED

- "→ commit 7: cadence-volume + sampler-trigger MCP volume" — RESOLVED 2026-05-06 at commit 7 prep (F8.2 Q2-B 60s baseline holds with margin).
- "→ commits 8-9: per-iteration MCP write call count" — RESOLVED 2026-05-07 at commit 8 prep (compaction once-per-cycle event; average per-tick write count effectively unchanged).

No new same-sub-milestone carry-forwards from commit 9 (closing commit).

## F10.7.E — Bidirectional-discipline summary across chain

Six invocations across four direction-shapes during this chain:

1. Substance reversal (F1.1, F5.6.B)
2. Framing-precedent-application (F6.5.1 / F6.6.B)
3. Prep-instruction error (F7.5.1, F8.6.A hybrid)
4. Spec-text-consensus vs reconsidered-disposition tension (F9.0)

Pattern operating across both review-roles + the timing axis. Closeout chapter 1 captures the framework. Future preps inherit higher-quality bidirectional-discipline awareness.

## F10.7.F — Final commit shape

Commit 9 ships:

- 1 new test file (`tests/integration/test_continuous_loop_e2e.py`) — 3 scenario tests.
- 1 new closeout doc (`docs/closeouts/M5-continuous-pm-driver.md`) — 288 lines; chapter-level Discipline-pattern observations.
- 1 new conventions doc (`docs/conventions/00-README.md`) — 9 conventions + observation footer; migrated from kickoff/00-README.md.
- 2 doc edits: `docs/kickoff/00-README.md` (extracted convention sections; pointer paragraph); `BACKLOG.md` § Conventions (reduced to pointer); `BACKLOG.md` cross-sub-milestone carry-forward destination updated.
- 1 grounding-doc fix: F2.3 Q1 prose Reading X supersession annotation.
- 1 grounding-doc append: this Followup-17 disposition capture.

Commit 9 closes the M5-continuous-pm-driver chain.

**End of Followup-17.**

**End of M5-continuous-pm-driver grounding doc.** Future-Claude reading this doc cold for archive purposes finds the canonical retrospective at `docs/closeouts/M5-continuous-pm-driver.md`; finds durable conventions at `docs/conventions/00-README.md`; finds active cross-sub-milestone carry-forwards at `BACKLOG.md` § Carry-forward to future sub-milestones.


