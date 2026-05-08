# M5-acceptance — kickoff

**Date:** 2026-05-08
**Status:** opens M5-acceptance sub-milestone (8 of 9 closed at M5-flag-flips
2026-05-08; this is the last M5 sub-milestone). Per the M5 sub-milestone
breakdown (`project_m5_sub_milestone_breakdown_proposal` memory), this is
the **substrate-dogfooding milestone** — the moment Orchestrator stops
being build-target and starts being product-you-actually-use.
**Precedent shape:** acceptance-shaped sub-milestone artifact under
`docs/working/`, modeled after `M4d-acceptance-demo-criteria.md`.

---

## Sub-milestone scope

M5-acceptance ships:
1. **Wiring gap closure** — `bridge/dispatcher.py:_default_launcher` adds
   `--append-system-prompt <yaml.system_prompt>` for implementer/tester roles
   so the migrated meridian-verifier / meridian-implementer behavioral
   discipline actually runs in the dispatched subprocess. Per Decision 2
   Reading I disposition (this doc) addressing **Finding A** (yaml.system_prompt
   not loaded at dispatch; empirical anchors at `tools/dispatch.py:226,340` +
   `bridge/dispatcher.py:237` + `tools/agents.py:96` + `server.py:1594`).
2. **β operational gate execution** — Mike's interactive Claude Code session
   dispatches one substrate work item through the full PM→Engineer→Implementer→
   Tester chain with real subprocess spawns, real substrate-clone commits, and
   bounded HITL. First true dogfooding tick.
3. **Phase 1 acceptance-coverage scan as closeout chapter** — per Convention 2;
   shipped within the M5-acceptance closeout doc rather than as a separate
   `phase-1-acceptance-coverage.md` artifact (Decision 3 Reading B).

**Out of scope for M5-acceptance:**
- New PM/Engineer/Implementer/Tester behavior beyond what's already shipped
- Phase 2 architectural-evolution candidates (BACKLOG `## Phase 2 architectural-
  evolution candidates` section pins 9 entries with concrete triggers)
- Multi-day β sessions or polish-chain dogfooding (one substrate work item only)
- New MCP tools or schema migrations
- Convention promotions unless one organically threshold-fires during the chain

---

## Decision dispositions

Four decisions resolved at kickoff prep checkpoint (2026-05-08, this session).
Reasoning preserved here as the canonical disposition record.

### Decision 1 — β scope shape: Reading A scope-capped

One substrate work item from meridian-homepoc Stage 5+ polish queue (per CQ-D
disposition D-1 below). Full PM→Engineer→Implementer→Tester chain. Single
substrate commit. NOT multi-day. NOT polish chain. Mike observes; bounded
intervention per Decision E-2.

**Rationale:** Reading B (smoke test only) is too thin — operational findings
are the entire point; a single happy-path tick won't surface them. Reading A
at full ceremony risks scope inflation; the cap holds it to one substrate
commit's worth of operational observation.

### Decision 2 — Tester role wiring: Reading I (close the gap)

Add `--append-system-prompt <yaml.system_prompt>` to launcher when role is
implementer or tester. Mechanical edit at `bridge/dispatcher.py:_default_launcher`;
preserves migrated behavioral discipline; makes β actually exercise meridian-
verifier behavior. Convention 17 fires at commit 2 — concrete sub-type(s)
decided at commit 2 prep per actual test shape (likely a mix: invariant
enforcement on subprocess argv shape; presence enforcement on yaml content).

**Rationale:** yaml shipping 179 lines of behavioral discipline as
documentation-only is architecturally dishonest. Reading II (Engineer composes
everything) centralizes drift risk. Reading III (defer to V2 #8) loses β
operational signal. Reading I is small mechanical change with large signal
multiplier. Phase 1 DoD criterion 3 (full chain end-to-end) requires Tester
role actually loaded.

**Sub-decision (Playwright MCP wiring): Reading I.a — user-level registration.**
Mike runs `claude mcp add playwright -- ...` once at user scope; daemon
subprocess inherits. Conditional on CQ-D (defers if β substrate work item is
backend-only).

### Decision 3 — Phase 1 acceptance-coverage scan format: Reading B (closeout chapter)

Convention 2's three-category I-A / I-NR / N-I scan ships as a chapter within
the M5-acceptance closeout doc rather than as a separate artifact. Standalone
artifact would duplicate framing; M5-acceptance closeout is the natural first-
instance home for the Phase 1 ceremony.

**Cross-reference:** prior phase-boundary scan precedent at
`docs/working/phase-1-acceptance-drift.md` (2026-05-03; partial scan covering
M1 through M5-subscription-routing). M5-acceptance closeout chapter completes
the remaining scope (M5-continuous-pm-driver through M5-acceptance + any
remaining M-level deliverables not picked up by sub-milestone scope).

### Decision 4 — Commit chain: Reading B (chain) contingent on Decision 2 = Reading I

Five-commit chain shape:

| # | Scope | Notes |
|---|---|---|
| 1 | M5-acceptance kickoff doc (this artifact) | TODAY's commit; docs-only |
| 2 | Dispatcher launcher wiring + Convention 17 sub-type (b) tests | Closes Finding A; tightens tester.yaml:178 (Convention 12 adjacent-hygiene); commit-2 prep verifies token cost + subprocess MCP inheritance |
| 3 | Playwright MCP registration confirmation (conditional) | Conditional on CQ-D — fires if work item touches frontend |
| 4 | β session capture (operational findings doc) | New artifact at `docs/working/M5-acceptance-beta-findings.md` |
| 5 | M5-acceptance closeout | Ships Phase 1 acceptance-coverage scan as chapter |

---

## Empirical findings surfaced at prep

### Finding A — yaml.system_prompt NOT wired at dispatch

`tester.yaml.system_prompt` and `implementer.yaml.system_prompt` are NOT loaded
into the dispatched subprocess. Empirical anchors:

- `tools/dispatch.py:226` — `dispatch_implementer` passes `prompt=prompt`
  (Engineer-composed) to dispatcher
- `tools/dispatch.py:340` — `dispatch_tester` passes
  `prompt=request.assertion_spec` (assertion_spec ONLY)
- `bridge/dispatcher.py:_default_launcher` invokes
  `claude --effort <level> -p <prompt>` with NO `--system-prompt` /
  `--append-system-prompt` flag
- `tools/agents.py:96` + `server.py:1594` — `get_agent_config` MCP tool
  excludes system_prompt from output

The dispatched subprocess runs as default Claude Code with only the user-level
prompt. Behavioral discipline encoded in yaml exists as documentation, not as
runtime constraint.

**Empirical instance #10 of #13 — multi-surface simultaneously** (kickoff-
assumption-level + code-level). Pin at M5-acceptance closeout: some #13
instances cross multiple surface categories simultaneously; worth distinguishing
at chapter framing per reviewer-Claude disposition.

**Closure path:** Decision 2 Reading I, commit 2.

### Finding B — Playwright MCP not registered

`claude mcp list` on Mike's machine: only claude.ai Google Drive/Gmail/Calendar
(each `! Needs authentication`). No Playwright MCP. tester.yaml:48-54 assumes
Playwright is "wired into the dispatch subprocess's Claude Code config" — that
wiring does not currently exist. **Closure path:** sub-decision I.a, commit 3
conditional.

**Provenance note:** Finding B is the CQ-C-resolved Playwright MCP gap,
re-framed at doc-authoring time for parallel pin equity with Finding A;
closure path already specified at sub-decision I.a + commit 3 conditional;
does NOT change Decision 2 or Decision 4; does NOT add to #13 count
(same operational-state-level empirical surface as risk surface 6 in the
prep verification table — substantive content unchanged; labeling new).
Doc-authoring-time labeling decision pinned for closeout review as a
verification-first sub-surface candidate (possible Convention 18 or
sub-element of #16 framework).

### Risk surface verification (per #16 recursive verification-first)

Six risk surfaces flagged in kickoff prep. Verification outcomes:

- ✓ M5-flag-flips closeout state matches kickoff framing
- ✓ Phase 1 DoD per `03-phase-one-scope.md` intact (12 criteria)
- ✓ Convention 2 readiness check shape (three categories; output discretion)
- ⚠ tester.yaml + implementer.yaml content faithfully migrated BUT not loaded
  at dispatch (Finding A)
- ⚠ `claude -p` supports `--agents`/`--system-prompt`/`--append-system-prompt`/
  `--mcp-config`/etc.; current launcher uses NONE (capability exists, not wired)
- ⚠ Mike's machine: no meridian-verifier or meridian-implementer subagents
  registered (yaml-side migration is the substantive port; /agents-side
  registration not load-bearing under Decision 2 Reading I); no Playwright MCP
  (Finding B)

---

## β operational gate criteria

β session executes against a real meridian-homepoc Stage 5+ polish queue work
item (CQ-D disposition D-1). Each criterion is binary pass/fail; failure on
any criterion surfaces in the operational findings doc with Mike's disposition
on routing (block / continue / defer).

| # | Criterion | Verification |
|---|---|---|
| β1 | Mike's `submit_task` via OAuth path lands; `submitted_by='human:mike'` | `SELECT submitted_by FROM tasks WHERE id = '<task_id>'` returns `'human:mike'` |
| β2 | PM-supervisor processes the task; PM submits subtask to Engineer; subtask `parent_task_id` references root | `SELECT parent_task_id FROM tasks WHERE id = '<subtask_id>'` returns root task_id |
| β3 | Engineer-supervisor claims subtask; status pending → claimed; `claimed_by` is Engineer agent_id | `SELECT status, claimed_by FROM tasks WHERE id = '<subtask_id>'` |
| β4 | Engineer's `dispatch_implementer` spawns subprocess; subprocess inherits implementer.yaml.system_prompt via `--append-system-prompt` | dispatch row terminal status `completed`; verify launcher invocation captured `--append-system-prompt` flag (commit-2 sentinel will catch missing flag at gate-run; β-time verification is via per-dispatch stderr.log inspection) |
| β5 | Implementer subprocess produces commit on `dispatch/<dispatch_id>` branch in worktree off substrate-canonical | `git log -1 --format=%H%n%s` in worktree returns new commit; commit message format matches implementer.yaml's spec (single commit per dispatch, exact title from spec) |
| β6 | Engineer's `dispatch_tester` spawns subprocess; subprocess inherits tester.yaml.system_prompt via `--append-system-prompt` AND has Playwright MCP available (if work item touches frontend per CQ-D) | dispatch row terminal status `completed`; Tester's `complete_dispatch` payload populates `test_results.assertions` with non-trivial per-assertion detail (proves yaml.system_prompt loaded; default Claude Code wouldn't compose this shape) |
| β7 | Tester's verification result lands in audit chain; `complete_dispatch` payload preserved | `SELECT details_json FROM audit_log WHERE action='dispatch.complete_called' AND target='<tester_dispatch_id>'` returns full payload including `judgment_observations` field |
| β8 | Engineer marks subtask completed via `update_task_status('completed')`; `actual_tokens` populated | `SELECT status, actual_tokens FROM tasks WHERE id = '<subtask_id>'` returns `('completed', <non-null int>)` |
| β9 | PM observes outcome via PM continuous loop; PM surfaces completion to Mike via task note OR direct chat exchange | observable in PM session output / pm_handoff doc / next-tick PM behavior |
| β10 | Substrate clone has the new commit on its main branch; merge happened cleanly | `git log -1 --format=%H` in `Settings.substrate_working_directory` returns new commit; matches dispatch's recorded commit_hash |
| β11 | Full audit chain present: `task.submit` (root + subtask) + `task.claim` + `dispatch.issued` (implementer + tester) + `dispatch.complete_called` (×2) + `task.status_change` + record_api_call rows with `workflow_id` populated from chain root | `SELECT action FROM audit_log WHERE target IN (...) ORDER BY rowid` returns ≥8 rows; `SELECT workflow_id FROM api_usage WHERE workflow_id = '<root_task_id>'` returns N>0 rows |

**β gate:** all 11 criteria PASS → Phase 1 DoD criterion 3 met end-to-end via
production MCP path; M5 closes; Phase 1 closeout opens. Failure on any
criterion captured in operational findings doc; routing decision per Mike.

### Pre-conditions before β session (block β if not met)

- Commit 2 landed: launcher wires `--append-system-prompt` for implementer +
  tester; presence-enforcement tests pass
- Commit 3 conditional: if work item touches frontend, Playwright MCP
  registered at user scope (`claude mcp list` shows `playwright`); subprocess
  inheritance verified empirically
- Substrate clone provisioned at `Settings.substrate_working_directory`; clean
  working tree
- Daemon running with both supervisor flags ON (`MERIDIAN_ORCH_ENABLE_PM_SUPERVISOR=true`
  + `MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true`); both supervisors
  responsive to heartbeat
- Mike's Claude Code session connected via OAuth-PM token; `record_api_call`
  emits with chain attribution

### β session HITL scope (CQ-E disposition E-2)

Mike intervenes only on:
- (a) subprocess hangs / heartbeat timeout breaches without progress
- (b) subprocess loops on the same finding without progress
- (c) obviously wrong output that would corrupt substrate clone
- (d) clear contract violation (e.g., subprocess calls MCP tool not in its
  allowlist; permission error surfacing)

Mike does NOT intervene on:
- minor disposition disagreements with Engineer's spec composition or sub-agent
  prompt interpretation — capture as operational finding
- Tester assertion failures (whether spec-vs-rendering mismatch or substrate
  bug surfaced by verification) — capture as operational finding; the
  assertion-failure IS the operational signal worth observing

Each intervention shape captured verbatim in the findings doc with timestamp
+ intervened-state + post-intervention-state. The discipline matters because
β session pressure compounds when triggers are ambiguous.

### Operational findings doc (commit 4)

`docs/working/M5-acceptance-beta-findings.md` (new artifact at commit 4).
Captures:
- Pre-session pre-conditions checklist (verified ✓ or notes)
- Per-β-criterion observed result + supporting evidence (SQL output, log path,
  audit row excerpt)
- HITL interventions: timestamp + trigger + intervened-state + outcome
- Operational findings: subprocess behaviors observed; prompt-discipline gaps
  surfaced; carry-forward candidates for M6/M7 or Phase 2
- Disposition routing for any β failures (block / continue / defer to follow-on)

---

## Carry-forward watches active in this sub-milestone

- **Convention 12 (adjacent-hygiene-during-touch):** at commit 2, tighten
  `config/agents/tester.yaml:178` ("When this system prompt and Engineer's
  `assertion_spec` disagree, trust the spec.") which becomes accurate text
  once `--append-system-prompt` wires the system_prompt into the subprocess.
  Currently misleading because system_prompt isn't loaded.
- **Convention 17 (methodology-encoded-as-test):** fires at commit 2; concrete
  sub-type(s) decided at commit 2 prep per actual test shape. Likely mix:
  invariant enforcement on subprocess argv shape (`_default_launcher`
  invokes subprocess with `--append-system-prompt` followed by
  yaml.system_prompt content); presence enforcement on yaml file content
  (load-bearing behavioral-discipline substrings present). May also pin a
  contract-enforcement sub-type instance if `_default_launcher`'s signature
  grows a role-aware param.
- **Convention 15 (operational-verification-before-config-flip):** dormant in
  M5-acceptance scope (no flag flips planned).
- **Convention 16 (feature-flag-flip cascade prevention):** dormant.
- **#13 framework (bidirectional verification-first):** Finding A is
  empirical instance #10 — multi-surface (kickoff-assumption-level + code-
  level simultaneously). Worth distinguishing at closeout chapter framing
  that some #13 instances cross multiple surface categories at once.
- **#16 (recursive verification-first):** operating throughout. Two of six
  risk surfaces flipped from "assumed clean" to "verified-with-nuance" via
  empirical check at prep time. Methodology-as-moat operating: prep
  verification prevented propagating false architectural assumptions to
  operational test scope.

---

## Verification asks pinned for commit 2 prep

Before commit 2 launcher-wiring lands:

1. **Token cost estimate.** Compute typical token cost of
   `tester.yaml.system_prompt` (179 lines) + typical `assertion_spec` (varies)
   + Claude Code default system prompt. Verify against Tester subprocess
   context budget. If budget concerns surface, evaluate `--system-prompt`
   (replace default) vs `--append-system-prompt` (concatenate). Different
   operational behavior; verify before commit. Same check for implementer.
2. **Subprocess MCP inheritance.** Empirically confirm subprocess Claude Code
   inherits user-level MCP registrations from the daemon host. If inheritance
   fails, fall back to sub-decision I.b (per-dispatch `--mcp-config`) for
   Playwright wiring. Test path: register a trivial MCP at user scope;
   spawn `claude --effort low -p "list mcp tools"` from a daemon-equivalent
   subprocess context; assert the trivial MCP appears in tool list.
3. **Test placement.** New presence/invariant enforcement tests live with the
   dispatcher (`tests/unit/bridge/test_dispatcher.py` or sibling), not with
   `tests/unit/agents/test_config.py`. General module-under-test colocation;
   not Convention-anchored. (Convention 6 was incorrectly cited at draft —
   that convention governs Settings-field tests specifically; #13 instance
   #11, multi-surface (code-level + memory-level), caught at reviewer-Claude
   verification check time.)

---

## Cross-references

- **Spec home:** `docs/kickoff/03-phase-one-scope.md` § Acceptance — Phase 1
  Definition of Done (12 criteria; β gate primarily verifies criterion 3 +
  ancillary criteria 4, 5, 8, 9)
- **Sub-milestone breakdown:** `project_m5_sub_milestone_breakdown_proposal`
  memory — M5-acceptance is sub-milestone 9 of 9; final M5 sub-milestone
- **Predecessor closeout:** `docs/closeouts/M5-flag-flips.md` (2026-05-08;
  Conventions 12-17 promoted; 9 V2 entries consolidated; β operational test
  routed forward to this sub-milestone per § What's next)
- **Acceptance precedent shape:** `docs/working/M4d-acceptance-demo-criteria.md`
  (M4d α + β acceptance gate criteria; this doc adapts the shape for Phase 1
  end-to-end β operational gate)
- **Convention reference:** `docs/conventions/00-README.md` § 1 (closeout
  artifact) + § 2 (Phase-boundary acceptance-criteria-coverage scan) + § 12
  (adjacent-hygiene) + § 17 (methodology-encoded-as-test)
- **Pre-Orchestrator artifact provenance:** `config/agents/tester.yaml`
  (migrated from `meridian-homepoc/.claude/agents/meridian-verifier.md`);
  `config/agents/implementer.yaml` (migrated from
  `meridian-homepoc/.claude/agents/meridian-implementer.md`); both adapted
  for orchestrator bridge architecture per `docs/kickoff/11-engineer-bridge.md`
- **BACKLOG carry-forward:** `BACKLOG.md` § Phase 2 architectural-evolution
  candidates (V2 #1-9); § Future architectural work; § Carry-forward to
  future sub-milestones; § Deferred (Mike's existing routing surfaces)
