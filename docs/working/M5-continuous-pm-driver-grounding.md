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
- **Migration count.** Three new migrations (0010, 0011, 0012,
  0013 — four, actually) at this sub-milestone. Migration boilerplate
  is small but not zero; if the rate continues, watch for migration-
  template extraction.

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
