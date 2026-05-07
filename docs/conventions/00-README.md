# Conventions

Durable disciplines that span sub-milestone boundaries within the
Meridian orchestrator chain process. This doc is the canonical home
for conventions; sub-milestone closeouts reference it; future-Claude
reading the chain process cold finds the conventions here, not
scattered across kickoff/BACKLOG/grounding-doc surfaces.

**Migrated 2026-05-07** at M5-continuous-pm-driver commit 9 close
per the discrete migration trigger named in
`docs/kickoff/00-README.md` § Convention-doc migration trigger
(originally set at "fifth durable discipline convention lands";
fired during M5-continuous-pm-driver chain). Pre-migration count was
4 conventions; M5-continuous-pm-driver chain added 5+ more.

Each convention names the rule + reasoning + scope of application +
known exception cases (or "no known exceptions yet") + establishment
provenance.

---

## 1. Sub-milestone closeout artifact

**Rule:** When a sub-milestone in M3-M7's chain closes, the close
review writes a canonical artifact at
`docs/closeouts/<sub-milestone-slug>.md` (e.g.,
`docs/closeouts/M5-subscription-routing.md`). The closeout is the
**chain-wide retrospective** for that sub-milestone — distinct from
the per-commit prep dispositions that surface during the build.

Closeout structure (one doc per sub-milestone, naming by
sub-milestone slug):

- **Chain summary** — commits, hashes, test count trajectory, line counts.
- **Spec amendments shipped** — cross-references to commits.
- **Migrations shipped** — version + commit + brief description.
- **New cross-layer single-source-of-truth instances** — constants / helpers compounded during the chain.
- **Watch-for-pattern outcomes** — threshold-of-three resolutions, new patterns at 1-2 instances.
- **Codex finding status** — when a code-review pass ingests findings; track their disposition.
- **Lineage notes for future-Claude** — non-obvious reasoning that wouldn't surface from cold-reading the code.
- **Address-now items routed forward** — explicit list with status (resolved during chain / BACKLOG'd / still deferred).
- **Drift-detection scan** — output of the spec-amendment-runtime-drift scan (empty-scan acknowledgement is its own signal).
- **Drift between kickoff scope and landed scope** — anything that diverged.
- **Discipline-pattern observations** — patterns reinforced or surfaced during the chain. At ≥7 patterns, organize into chapter-level structure (precedent: M5-continuous-pm-driver close, 2026-05-07).
- **What's next** — pointer to remaining sub-milestones in the parent milestone.

The closeout artifact is the answer to "if I read this chain cold
six months from now, what do I need to know?" — the prep checkpoints
answer "what should we do next." Pair, not substitute.

**Reasoning:** the per-commit prep dispositions live in working/
grounding docs that are sub-milestone-scoped; six months from now
those docs are historical archive, not durable reference. The
closeout consolidates what's worth carrying forward into a stable
location.

**Established at:** M5-subscription-routing close, 2026-05-02.

---

## 2. Phase-boundary acceptance-criteria-coverage scan

**Rule:** Before any Phase closeout, run an acceptance-criteria-
coverage scan against shipped state. For each M-level deliverable
shipped in the phase: read the deliverable's acceptance criterion
from `docs/kickoff/08-implementation-plan.md` or the deliverable's
spec doc; verify acceptance-criterion-coverage exists in production
code paths, not just test fixtures. Distinguish three categories:

- **(I-A)** implemented and acceptance-met
- **(I-NR)** implemented but acceptance-not-runnable end-to-end
  (typically because tests pass via direct SQL fixtures but the
  production-side caller surface to seed the path is missing)
- **(N-I)** not implemented

**Output:** a Phase-closeout TODO list at
`docs/working/phase-N-acceptance-drift.md`. For each (N-I) or (I-NR)
entry, disposition either:

- **Remediate before Phase close** (a remediation sub-milestone, e.g.,
  M4d for the M4 task-management surface gap discovered 2026-05-03).
- **Document as known Phase-N+1-debt** (carry-forward with explicit
  framing — "spec-vs-implementation drift, not authorized deferral"
  if applicable).

**Discipline rule:** if any acceptance-criterion-coverage gap is
found, the gap surfaces explicitly in the Phase closeout doc with
the same "spec-vs-implementation drift, not authorized deferral"
framing as the M4 finding from 2026-05-03. Phase closure does not
happen with silent gaps; gaps are either remediated or documented as
known carry-forward debt.

**Trigger:** before any Phase closeout (Phase 1 closeout being the
next instance). Also fires ad-hoc when a sub-milestone prep
checkpoint discovers a phantom-surface drift (the F2.1 commit-1
contradiction during M5-continuous-pm-driver grounding, 2026-05-03,
was the first such ad-hoc trigger).

**What this convention specifically catches** (and what it does not):
sub-milestone closeouts catch sub-milestone-internal drift — within
the closing sub-milestone's claimed scope, did everything ship
cleanly? They don't see deliverables that were specified at M-level
(or phase-level) but never picked up by any sub-milestone as scope.
The 2026-05-03 M4 finding is the canonical example: every M4
sub-milestone shipped its scoped deliverables (all I-A at
sub-milestone granularity), but `tools/tasks.py` and the six task-
management MCP tools were M-level deliverables that no sub-milestone
took as scope. The phase-boundary scan is the convention that sees
this — its value is specifically catching **inter-sub-milestone scope
drift**, not duplicating sub-milestone-internal verification.
Future-Claude running this scan should look for M-level deliverables
unclaimed by any sub-milestone, not re-verify sub-milestone-internal
acceptance.

**Established at:** 2026-05-03 via M5-continuous-pm-driver F2.1
commit-1 contradiction.

---

## 3. Sub-milestone-boundary internal-spec-consistency scan

**Rule:** Internal-spec-consistency scan runs at **sub-milestone close
AND at phase boundary** — paired conventions, neither subsumes the
other. Both required.

- **Sub-milestone scope** (narrower; triggers more often): for each
  architectural concept introduced or modified at this sub-milestone,
  grep mentions across `docs/kickoff/` + `bridge/*.py` +
  `tools/*.py` docstrings; flag mentions divergent from the
  disposition that landed at this sub-milestone. Catches recently-
  created drift at creation point — cheaper to fix; smaller blast
  radius.
- **Phase scope** (broader; triggers less often): full architectural-
  concept inventory; divergence check across all sources. Catches
  accumulated drift across sub-milestones.

**Reasoning:** existing phase-boundary scan (Convention 2 above)
catches **spec-vs-implementation drift** (spec says X exists but X
doesn't) but does NOT catch **internal-spec-contradiction** (multiple
spec sources internally contradict on whether X is implemented or how
X works). The two drift categories surface differently —
implementation drift surfaces at code-write pressure; internal-spec
drift surfaces only when the contradiction becomes load-bearing
(e.g., when a downstream commit tries to implement against the
contradicted concept). By that point, the drift is older + has more
dependent context + costlier to resolve.

Sub-milestone-boundary scan catches recently-created drift at
creation point. Phase-boundary scan catches accumulated drift before
phase transitions. Both run; neither alone is sufficient.

**Operational shape:** at sub-milestone close, for each architectural
concept introduced or modified at this sub-milestone:

1. Grep all `docs/kickoff/` + `bridge/*.py` + `tools/*.py` docstrings
   + relevant `docs/working/<sub-milestone>-grounding.md` for
   mentions of the concept.
2. Produce a "concept → mentions" table — one row per source location.
3. Read each mention; flag rows where the mention diverges (on
   mechanism, location, or behavior) from the disposition that
   landed at this sub-milestone.
4. Surface flagged rows as a sub-milestone closeout finding; resolve
   via spec amendments at sub-milestone close OR via a follow-on
   commit early in the next sub-milestone.

Cheap to run; catches the spec-vs-spec category before
next-sub-milestone commit-pressure forces it.

**Worked example:** M5-pm-supervisor-base disposition (P-2 Reading B,
2026-05-01) chose subprocess-side handoff-doc reading for
`pm_supervisor.py`'s `_reconstruct_state` behavior. Five other spec
sources (`bridge/supervisor_base.py:196` + `:284-298`;
`bridge/engineer_supervisor.py:23` + `:152-154`;
`docs/kickoff/11-engineer-bridge.md:753`) still described the
daemon-side override mechanism — unamended. Sub-milestone-boundary
scan at M5-pm-supervisor-base close would have surfaced the
divergence; instead it stayed latent until M5-continuous-pm-driver
commit 8 implementation pressure forced resolution. Resolution
shipped at commit 8 atomically with the runtime change per the
`feedback_spec_amendment_runtime_drift` convention.

**Distinct from `feedback_spec_amendment_runtime_drift`:** the
drift-runtime convention covers "when amending spec, also amend
runtime files that consume the spec." This convention covers "when
sub-milestone closes, scan for spec sources that internally
contradict on the concepts introduced or modified at this sub-
milestone." Different surface; both required.

**Known exception cases:** None yet. Possible exception shapes:

- **Concept introduced but not yet load-bearing** — a sub-milestone
  may introduce a concept whose mechanism is genuinely undecided
  (e.g., placeholder spec text marking the concept as "deferred").
  Skip those at sub-milestone scan; they surface naturally at the
  next sub-milestone that exercises the concept under decision
  pressure.
- **Concept whose internal contradiction is intentional** — rare; if
  a contradiction is deliberate (e.g., "Phase 1 ships X mechanism,
  Phase 2 will ship Y mechanism, both currently documented"),
  document the intentionality at the source locations. The scan
  should still surface the divergence so the disposing-Claude can
  confirm intentionality vs latent drift.

**Established at:** M5-continuous-pm-driver commit 8 prep
(2026-05-07; F9.0 / F9.8 surfacing).

---

## 4. Watch-for-pattern with explicit thresholds

**Rule:** When a code/architectural pattern surfaces twice with
similar shape, add a watch-for-pattern entry to `BACKLOG.md` (or the
relevant section of the active sub-milestone's grounding doc) with
an explicit threshold (typically 3 instances). At threshold, the
pattern is dispositioned: extract / convert to convention / accept
as one-off.

**Reasoning:** pattern emergence is gradual; coding-time recognition
of "we've seen this before" is unreliable across multiple sessions
and authors. Explicit threshold + named pattern + tracked count
makes the disposition mechanical-not-vibes when threshold fires.

**Established at:** mid-M5 via BACKLOG entries (`bridge/` → `tools/`
cross-layer imports at threshold-of-three; production-code-surface-
for-testability at threshold-of-three; etc.).

**Refinement:** threshold-of-three is **trigger-to-evaluate, not
mandate-to-extract.** At three instances, the discipline is to ASK
the question, not to answer "yes" by default. Surface similarity
does not imply shared semantic load. Reject extraction when the
abstraction unit doesn't carry semantic weight; the rule-of-three
is a heuristic, not a mandate.

This refinement frames the May 3 UTC-coercion precedent correctly:
extraction landed there because the abstraction unit (timezone-
aware-then-naive coercion) was load-bearing semantic logic, not
just shared SQL shape. Future watch-for-pattern entries reading
this trail should evaluate "what's the abstraction unit?" before
assuming extraction at three is automatic.

**Refinement established at:** F4.5.2, M5-continuous-pm-driver
commit 3 prep, 2026-05-04. Carried into Followup-7 Q3 disposition;
threshold reframed without commit-3 extraction. Applied at
M5-continuous-pm-driver commit 8 F9.2 Q8: filesystem-by-timestamp
read pattern is a different abstraction unit than SQL-rowid-DESC
(different correctness conditions: rowid-monotonicity vs ISO-
timestamp-lexical-sort); no extraction warranted; SQL-rowid-DESC
watch stays at 2.

---

## 5. Threshold-of-three same-commit extraction

**Rule:** When a watch-for-pattern's threshold-of-three is reached
AND extraction is the right disposition (per Convention 4
refinement above), extract in the **same commit** as the triggering
call site, not as a follow-on hygiene commit.

**Reasoning:** deferring extraction to a hygiene commit creates
window where the third-instance call-site has shipped but the
extraction hasn't, leaving the pattern at three duplicated instances
in landed code. Same-commit extraction collapses the window and
ships the abstraction with its third consumer.

**Established at:** M5-subscription-routing commit 2, 2026-05-02 —
UTC-coercion utility extracted to
`storage/datetime_utils._coerce_naive_to_utc` when the third call
site appeared in `tools/budget.py:_resolve_window`.

---

## 6. Settings field tests colocate with consumers

**Rule:** When a `Settings` field has load-bearing semantics
(defaults, range-validation, contract invariants), its tests live
with the consumer module that enforces the contract — not with
`config.py` (which aggregates fields without owning their semantics).

**Reasoning:** `config.py` is a config-aggregation module; individual
fields' contracts (e.g., `audit_sample_percentage` defaulting to 0.0
to enforce opt-in semantics, range-validating to [0.0, 1.0] to match
`Sampler` constructor's expectation) belong with the consumer that
defines and enforces them. Test boundary follows contract boundary,
not source-file boundary. Splitting Settings field tests across
`tests/unit/test_config.py` and consumer test files would force
future-Claude reading either file to chase across to find the full
picture; co-locating keeps the contract surface unified.

**Established at:** M5-continuous-pm-driver commit 4, 2026-05-05.
`Settings.audit_sample_percentage` field tests live in
`tests/unit/audit/test_sampler.py` (default 0.0 + range validation)
alongside `Sampler` writer tests, not in a dedicated `test_config.py`.

**Implications:**

- M5-continuous-pm-driver commit 5 (effort plumbing): pm.yaml's
  `effort` field tests colocate with PMSupervisor's tests, not with
  pm.yaml loader tests.
- M5-continuous-pm-driver commit 7 (`pm_loop_tick_seconds`): tests
  live in `tests/unit/tools/test_pm_loop.py`.
- M5-continuous-pm-driver commit 8 (`pm_handoff_dir`): tests live
  in `tests/unit/tools/test_pm_handoff.py`.

**Known exception cases:**

- **Multiple consumers of the same field** — if a Settings field has
  two or more consumers with non-overlapping contracts, colocation
  may not be obvious. Recommendation: place tests with the
  load-bearing-most consumer; cross-reference from the lighter
  consumer's test file. Surface as exception to the rule rather than
  letting the rule erode silently.
- **Field with no clear single consumer** — if a Settings field's
  contract is purely structural (e.g., a deprecated alias, a pure
  pass-through), `tests/unit/test_config.py` may be the right home.
  Surface as exception with explicit "no consumer-side contract"
  framing.

If a counter-case surfaces during future commit prep, the
disposing-Claude should evaluate and either (a) extend the rule to
cover the new shape, or (b) document the case as an exception. Don't
let the rule erode silently.

---

## 7. Carry-forward note routing (scope-determines-location)

**Rule:** Scope-determines-location for carry-forward notes between
commits / sub-milestones / phases:

- **Same-sub-milestone carry-forwards** (note from commit N to
  commit M within the same sub-milestone) live in the
  sub-milestone's grounding doc under a `## Carry-forward notes`
  section. The grounding doc is already opened at every commit prep
  within the sub-milestone; this section is the canonical surface.
- **Cross-sub-milestone carry-forwards** (note from sub-milestone X
  to sub-milestone Y) live in `BACKLOG.md` under a dedicated
  `## Carry-forward to future sub-milestones` section, distinct from
  the existing `## Deferred` section. **Lazy-created** on first
  cross-boundary entry — empty headers are documentation-without-
  purpose. When the first cross-sub-milestone carry-forward
  materializes, the BACKLOG.md section gets created with that entry.

**Format for entries** (both surfaces):

```
### → commit N (or → <sub-milestone-name>): <topic>

<one-paragraph carry-forward note + cross-reference to detail-prose
location, e.g., "Detail prose: F4.2 Q1 disposition pin in this
grounding doc.">
```

**Reasoning:** scope-determines-location avoids two failure modes:
(a) "grounding doc only" loses cross-boundary entries when the
sub-milestone closes and grounding doc moves to historical archive;
(b) "BACKLOG.md only" creates junk-drawer accumulation + redundancy
with grounding doc surfaces that are already opened at every commit
prep within the sub-milestone.

**Established at:** M5-continuous-pm-driver commit 3 prep
(Decision 5 disposition), 2026-05-04. First cross-sub-milestone
materialization at commit 4 (F5.6.C — audit_sample_log re-evaluator
deferred to focused-observability sub-milestone).

---

## 8. Carry-forward annotation discipline

**Rule:** When downstream commits change baseline assumptions for an
existing carry-forward note, **annotate in-place preserving the
original reasoning chain** — do not silently rewrite.

**Format:**
```
**DISPOSITIONED YYYY-MM-DD (commit N prep, F<X>.<Y> Q<Z>-<reading>):**
<new value> per <reasoning>; <prior assumption> from commits <a-b>
prep preserved as historical context; <volume framings or other
prior dispositions> from prior commits hold under <new value> with
margin. **Re-evaluation trigger:** <empirical condition that
warrants revisit>.
```

**Reasoning:** preserves the cumulative reasoning chain so future-
Claude reading the closeout sees the trajectory of decisions, not
just the final state. Silent rewrite loses the "why we changed our
minds" context that's load-bearing for future re-evaluation.

**Established at:** F8.6.B, M5-continuous-pm-driver commit 7 prep,
2026-05-06. First instance: F8.2 Q2-B disposition changing tick
cadence baseline from anticipated 30s (commits 3-4 prep) to 60s
default (commit 7 disposition).

**Pairs with `feedback_supersession_marker_shape` memory:** for
layered-revision docs, the supersession marker
`[SUPERSEDED YYYY-MM-DD]` is preferred over
`[SUPERSEDED — see ... above]` because it survives further
supersessions unambiguously.

---

## 9. Code-inspection sentinel observability

**Rule:** Code-inspection sentinels (tests that read production
source files as text and assert forbidden patterns) are brittle to
legitimate refactor by design — that brittleness is the appropriate
cost of explicit disposition-pinning. To distinguish substantive
architectural drift from path/pattern staleness, when a code-
inspection sentinel fires, the disposition pass categorizes the
failure as one of two modes:

- **Mode 1 (legitimate refactor):** source-file rename / split /
  reorganization preserves architectural intent; sentinel breaks
  because path or pattern is stale. **FIX:** update sentinel's
  path / pattern. **EXPECTED REFACTORING COST:** mechanical. Track
  as Mode 1 fire in that commit's diff checkpoint with one-line note.
- **Mode 2 (architectural drift):** code change violates the
  dispositioned invariant substantively. Sentinel fires correctly.
  **FIX:** revert the architectural drift, NOT the sentinel. (If
  genuine substantive reason to change the invariant: re-litigate
  the disposition in a prep checkpoint, then update sentinel after
  re-disposition lands.) Track as Mode 2 fire (a discipline win —
  sentinel caught real violation).

**Failure-message contract:** sentinel failure messages must
explicitly distinguish the two modes and prompt the developer to
determine which applies. Failure message names the contract being
violated + the disposition-pin reasoning + the per-mode fix
guidance.

**Mode 1 / Mode 2 tracking + (b)-revisit trigger:** track Mode 1
fire count + update complexity per chain. **(b) revisit trigger**
(sentinel-pattern-vs-runtime-test): 3+ Mode 1 fires within chain OR
single Mode 1 fire requiring materially complex sentinel update
(more than mechanical path / pattern adjustment). Either trips
revisit. Mode 2 fires don't trigger (b) revisit — they validate the
(a) sentinel-pattern choice. Makes the sentinel-pattern choice
empirical-not-aesthetic; future-Claude reviewing a fire knows
which mode + which fix.

**Reasoning:** code-inspection sentinels are a powerful tool for
pinning architectural decisions durably (a docstring claim is easy
to forget; a failing test demands attention). But they're brittle
by design. Without the Mode 1 / Mode 2 distinction, future-Claude
seeing a fire defaults to "fix the test" without evaluating whether
the test is correctly catching real drift. The convention forces
explicit categorization at every fire.

**Established at:** F2.5 closeout-staging, M5-continuous-pm-driver
commit 7 draft, 2026-05-06. First commit shipping the convention:
commit 7 of M5-continuous-pm-driver, with two sentinels
(`test_pm_supervisor_does_not_import_tools_budget_or_pm_focus` +
`test_pm_loop_module_does_not_define_read_current_helper`).

---

## Convention establishment cadence (observation, not yet pinned)

**Observation (M5-continuous-pm-driver close, 2026-05-07):**
convention-establishment cadence at this chain was 5+ new
conventions across 8 commits, vs M5-subscription-routing's 0-1
across 6 commits. Whether this is cadence-correlated-with-
architectural-density (commit-shape-dependent) or M5-continuous-pm-
driver-specific (one-off chain shape) needs more chains' worth of
data.

**Pre-scoping suggestion:** at sub-milestones with similar
architectural density (multiple load-bearing dispositions; multiple
new spec amendments; high prep-checkpoint decision count),
pre-allocate scope for convention establishment + migration
evaluation.

**Not yet pinned as a pattern.** Single data point. M6 dashboard
work + M5-flag-flips will provide additional data points. Pin as a
convention if cadence-vs-density correlation holds across 2-3 more
chains.

---

## 10. Python docstrings use ASCII for ambiguous Unicode (RUF002)

**Rule:** Python docstrings use ASCII for ambiguous Unicode (Greek
letters such as `α`, `β`; multiplication sign `×`; etc.) per ruff
RUF002. Markdown, SQL, and code comments retain Unicode.

**Reasoning:** cousin to closed-enum sentinel pattern (Convention 4
trail). Both prevent silent style drift. Ruff catches the issue at
lint time; convention pre-empts re-discovery at every commit. Python
docstrings are tooling-introspected in ways markdown/SQL aren't,
making the ASCII vs Unicode distinction load-bearing for tooling
compatibility.

**Established at:** M4d-task-management-surface commits 2 (α→alpha
in tasks.py docstring) and 4 (×→x in test_tasks.py docstring).
Promoted at M4d commit 9 closeout per CQ2 disposition.

**Known exceptions:** none — Python docstrings unambiguously use
ASCII; markdown / SQL / code comments unambiguously retain Unicode.

---

## 11. Programmatic-Cartesian matrix sentinels

**Rule:** parametrized matrix sentinels generate Cartesian
programmatically from a source-of-truth dict + input domain, NOT
from hardcoded pair lists.

**Reasoning:** structural robustness against silent coverage gaps.
Cousin to closed-enum sentinel pattern (Convention 4 trail) — both
assert the test against the source-of-truth structure (dict / Literal
/ CHECK constraint), so future refactors that touch the source are
caught automatically.

**Worked example:** state-machine matrix at `update_task_status` in
M4d commit 5. `_state_machine_cases()` (in `tests/unit/tools/test_tasks.py`)
generates the 42 `(from, to)` pairs from `_ALLOWED_TRANSITIONS` dict
(in `tools/tasks.py`) + the `UpdateTaskStatusInput.status` Literal
input domain. The sentinel test (`test_update_task_status_state_machine_matrix`)
walks the generated cases. A future refactor of the dict OR the
Literal triggers the sentinel before silent drift lands.

**Operational shape:** at the test site, write a `def _matrix_cases()
-> list[tuple[...]]` helper that imports the source-of-truth dict
and the input domain (Literal / set), iterates the Cartesian, and
returns the `(input1, input2, expected)` tuples. Decorate the test
with `@pytest.mark.parametrize` over the helper's return value. The
test asserts behavior matches `expected` derived from the dict.

**Established at:** M4d-task-management-surface commit 5 first
instance shipping. Promoted at M4d commit 9 closeout per CQ2
disposition (single-instance promotion rationale: cheap; cousin to
established convention; pre-emptive codification saves future-Code
re-discovery).

**Known exceptions:** none yet. Possible exception shape: matrices
where the source-of-truth structure is itself dynamic (e.g.,
operator-supplied at company instantiation); programmatic Cartesian
would need to read the dynamic source. Surface as exception when
encountered.
