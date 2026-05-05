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
  `/product/substrate/pm-handoffs/<timestamp>.md` summarizing
  current_focus + planned-next + open-questions.
- On respawn (post-compaction or post-crash), `PMSupervisor._reconstruct_state`
  reads the most recent handoff doc and provides it as context to the
  newly-spawned PM subprocess (via subprocess spec / env / stdin —
  decision belongs with PMSupervisor implementation, not the loop).
- The handoff doc shape is M5-continuous-pm-driver scope. Suggested
  required sections: current_focus state at compaction, planned-next
  task chain, open-questions to Mike, recent decisions log pointer,
  cohort summary (workflow_id of in-flight chain). Free-form markdown
  with required-section headers; PM authors at compaction; respawn-PM
  reads at start.

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
| 8 | Compaction-trigger + handoff-doc generation + `PMSupervisor._reconstruct_state` reading most-recent handoff | `bridge/pm_supervisor.py` (extension), `tools/pm_handoff.py` (new) | +14 |
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
parse semantically. The handoff is read by `PMSupervisor._reconstruct_state`
on respawn — its consumption is "read most-recent file, inject as
context to spawned PM" (per `bridge/supervisor_base.py:264-272`); it
doesn't structurally parse sections. Reading-C achieves contract
discipline via prompt + audit-scan without parser surface.

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
| `read_current_X` helper with rowid-DESC ordering | 1 (`read_current_pm_mode`) | +1: `read_current_focus`. | Threshold-of-three: at the THIRD instance, surface for shared utility extraction. Watch at commit 3 (`pm_spin_rationale_log` may add a third). |
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
