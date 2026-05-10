# M5-acceptance — sub-milestone close review

Closes the M5-acceptance sub-milestone (9 of 9; final M5 sub-milestone). Chain-wide retrospective per the canonical closeout convention (`docs/conventions/00-README.md` § 1).

This sub-milestone landed: (a) kickoff doc + Finding A surfacing (commit 1; β operational gate framed; `yaml.system_prompt`-not-wired-at-dispatch empirically anchored at five code locations); (b) dispatcher launcher wiring across three orthogonal flag surfaces — `--append-system-prompt` (commit 2; Finding A closure), `--allowedTools` (commit 3; conditional-permission-gating closure), `--mcp-config` + `--strict-mcp-config` (commit 3.5; subprocess MCP auth closure); (c) supervisor symmetric auth wiring + Path Y file-move (commit 3.6; supervisor-side gap symmetric to dispatcher work); (d) β-readiness artifact (commit 4; Option B disposition splitting β-as-readiness from β-as-execution per Reading 2 charitable disposition); (e) post-β architectural remediation closing the OAuth-PM operator-interface gap β surfaced — Components 1-5 + logging refactor + 21 unit tests collected (commit 5).

A monorepo restructure (`e67a121`) lands between commit 4 and commit 5 — pure file rename moving the orchestrator under `meridian-ai/` as a subdirectory, with `meridian-homepoc` reserved as substrate sibling. Non-M5-acceptance content; logged here for chain-narrative completeness.

**Decision density was structurally heavy** — chain established Convention 19 (auth + permission wiring at subprocess launchers; threshold-of-three reached at commit 3.6), confirmed the external-dependency-behavior verification pattern at six sub-instances, established Reading B at write-time as durable discipline (no methodology-bleed in production docstrings; methodology context lives in commit messages + BACKLOG entries + closeout chapters), and surfaced the runtime-exercise-vs-wiring-test methodology amendment (Pin 9) which β execution then empirically validated. Pin 9 is the most consequential single pin: it predicted the Engineer-supervisor continuous-loop class of finding before β surfaced it, and the prediction held.

The methodology-as-moat thesis (#11 from M5-flag-flips closeout) accumulated a further empirical anchor: Pin 9's prediction → β execution validation → harness specification + harness-via-dispatch sequencing decisions are a single methodology-coherent arc producing the M6 milestone shape. Discipline established at one milestone gets validated empirically at the next; the artifact chain composes.

---

## Chapter 1 — Commit chain narrative

Seven substantive M5-acceptance commits + one non-M5 monorepo restructure. Test count trajectory: 790 → 840 collected items at HEAD (`pytest --collect-only`). Net +50 collected items through the chain. Function-level delta verified via `git grep '^def test_\|^async def test_'` at each commit boundary: 655 → 700 = +45 functions. The +5 spread between functions and collected items is parametrize expansion; commit 5's `test_load_role_system_prompt_reads_yaml × 4 roles` contributes 3 of those 5 cases (1 function, 4 collected). Per-commit table values below are collected items.

| Commit | Hash | Description | Functions added | Collected items | F2.1 estimate | Sub-category |
|---|---|---|---|---|---|---|
| 1 | `aff31fd` | docs: kickoff doc opening sub-milestone 9/9 + Finding A surfaced + 4 decision dispositions + 11 β gate criteria | 0 | 0 | 0 | docs-only |
| 2 | `8b15611` | `--append-system-prompt` wiring (Finding A closure; Convention 17 sub-type (c)) | 4 | 5 | 8 | sentinel-heavy (low-band) |
| 3 | `a7d974a` | `--allowedTools` wiring (conditional-permission-gating closure) | 7 | 7 | 8 | sentinel-heavy (low-mid-band) |
| 3.5 | `28cd760` | `--mcp-config` + `--strict-mcp-config` wiring (subprocess MCP auth closure; β6 unblocks) | 8 | 8 | 12 | sentinel-heavy (mid-band) |
| 3.6 | `666ac14` | supervisor symmetric auth wiring + Path Y extraction (`bridge/mcp_config.py`) | 8 (+2 relocated, count-neutral via rename) | 9 | 12 | sentinel-heavy (mid-band) |
| 4 | `ff0042c` | β-readiness artifact (Option B disposition; β-as-readiness vs β-as-execution split) | 0 | 0 | 0 | docs-only |
| — | `e67a121` | restructure: orchestrator under `meridian-ai/` as subdirectory (non-M5-acceptance) | 0 | 0 | 0 | non-M5 |
| 5 | `0160f24` | post-β architectural remediation: Components 1-5 + logging refactor + tests | 18 | 21 | (high) | infrastructure-with-failure-isolation |

**Chain totals**: +45 functions / +50 collected items. Per-commit collected sums to 50 ✓. Reconciliation note on commit 5: the commit message cited "24 new unit tests" but `pytest --collect-only` at HEAD verifies 21 collected items in the new test files (`test_auth.py:3 + test_oauth.py:3 + test_registry.py:4 + test_config.py:2 + test_operator_wrapper.py:9`). The 3 "Convention 17 sub-type (c) Invariant enforcement on argv shape under build_argv" tests in commit 5's component-4 breakdown are the build_argv tests *inside* `test_operator_wrapper.py`'s 9-item collection (`test_build_argv_composition_pm` + `test_build_argv_no_passthrough_args` + `test_build_argv_raises_on_missing_claude_binary`) — labelled separately for sub-type categorization, not added separately. Commit message double-counted by 3.

**Spec amendments shipped**:
- `bridge/dispatcher.py` — `DispatchLaunchContext.allowed_tools` field + `mcp_config_path` field; `_default_launcher` argv composition extended across commits 2 + 3 + 3.5; Option A ordering preserved (`--effort → --append-system-prompt → --allowedTools → --mcp-config <path> --strict-mcp-config → -p`).
- `bridge/supervisor_base.py` — `SupervisorLaunchContext.allowed_tools` + `mcp_config_path` fields; `_subprocess_allowed_tools()` abstract hook (mirrors `_subprocess_prompt()` shape); `_default_launcher` argv composition (Option A ordering preserved with intentional `--append-system-prompt` omission per Q4' disposition — see Pin 1).
- `bridge/mcp_config.py` (NEW at commit 3.5; widened at commit 3.6 with Path Y) — per-dispatch and per-spawn JSON helpers + `_ORCHESTRATOR_MCP_TOOL_PREFIX` constant + `resolve_allowed_tools_for_role()` helper. Architectural fit at bridge layer: launcher-composition logic belongs adjacent to the launchers that consume it.
- `agents/config.py` — `AgentConfig.allowed_tools: list[str]` field with single-source-of-truth at launcher composition time (no yaml duplication).
- `config/agents/{tester,implementer,pm,engineer}.yaml` — `allowed_tools` field added across all four (commits 3 + 3.6); disambiguating comment blocks document why the launcher-composition source-of-truth trumps yaml prose constraints.
- `auth.py` — `TokenStore.find_oauth_caller_by_client_id(client_id, role) -> agent_id | None` helper (Component 1 alpha persistence).
- `oauth.py` — `exchange_authorization_code` invokes `on_oauth_caller_registered` callback after issuance; alpha-persistence reuse via `find_oauth_caller_by_client_id` lookup.
- `agents/registry.py` — snapshot keyed by `agent_id` (was `role`); `RegisteredAgent.source: TokenSource` field; `register_oauth_caller` helper for explicit OAuth registration + agents-table UPSERT; `get(role)` filters static-source to preserve 10 existing call-site semantics; `get_by_agent_id` resolves OAuth-source callers.
- `server.py` + `main.py` — `on_oauth_caller_registered` callback threaded through `build_server` + `serve()`; closure built over `(registry, storage, company_id)` at `main.py`.
- `config/agents/pm.yaml` — `mcp_tools_allowed` grouped + annotated with BACKLOG pointers for 7 deferred Phase 1 PM tools (commit 5 Component 2).
- `scripts/_operator_wrapper.py` (NEW at commit 5) + `scripts/operate-as-pm.py` + `scripts/operate-as-engineer.py` — operator wrapper scripts materializing the manual operator path as code (Component 3; spawns `claude --dangerously-skip-permissions --append-system-prompt <yaml.system_prompt>` with `sys.argv` passthrough).
- Logging refactor (cross-stack timestamp/format unification) — EDT/EST millisecond-precision timestamps + PID + thread ID per log line; `_add_meta_fields` structlog processor + `_human_renderer`; Python logging mirror via `_MatchingFormatter` + `_JSONFormatter`; format toggle via `MERIDIAN_ORCH_LOG_FORMAT`; `ZoneInfo("America/New_York")` via `tzdata>=2024.1` on win32 platform marker.
- `pyproject.toml` — `pythonpath = ["src", "."]` for `scripts/` pytest import resolution.
- `README.md` — "One-time operator setup" section (yaml copy + substrate path env var + token issuance commands); "Operator wrappers" section (`operate-as-pm` / `operate-as-engineer` docs); per Convention 12 adjacent-hygiene at commit 3 (`claude mcp add` documentation gets `-s user` scope flag with empirical-verification provenance).

**BACKLOG amendments**:
- Commit 3 added 3 entries to `## Future architectural work` (subprocess directory access constraints; restricted Bash patterns per role; per-dispatch MCP server configuration).
- Commit 3.5: δ-3 entry RESOLVED marker prepended with Q1/Q2/Q3 disposition record (audit-lineage convention preserved); ζ-1 entry added (per-spawn supervisor auth wiring; lifecycle-anchor framing).
- Commit 3.6: ζ-1 RESOLVED marker; bridge/→tools/ cross-layer imports watch entry RESOLVED via Path Y extraction.
- Commit 5: 7 entries added (notify_human HIGH; knowledge tools HIGH; dashboard tools MEDIUM; telegram-inbound LOW; multi-device operator identity post-α; **Engineer-supervisor continuous-loop discipline TRIGGERED 2026-05-09** β-chain auto-completion blocker; agent yaml runtime-dir auto-seeding).

**Migrations shipped**: none. M5-acceptance chain is pure code (commits 2 + 3 + 3.5 + 3.6) + docs (commits 1 + 4) + multi-component remediation (commit 5: code + tests + scripts + docs). No schema work.

**Codex finding status**: no codex review pass during this chain. Empirical β execution at commit 5 prep surfaced the Engineer continuous-loop gap (BACKLOG entry, not codex finding); future codex review on the M5-acceptance work is appropriate before M6 harness lands — particularly on the auth+permission wiring pattern (Convention 19 anchor) and the Component 1 OAuth-PM registry binding (load-bearing for any future operator identity work).

**β execution outcome**: Component 1 verification (2026-05-09) succeeded end-to-end at the OAuth-PM submit_task surface — task `a424a35a-b9d4-4693-ada5-6fb9e37f3091` registered cleanly through the FastMCP auth → `registry.get_by_agent_id` resolution → handler permission_check → `submit_task` UPSERT path. β-chain stalled at Engineer pickup: task sat in `pending` state with `claimed_by=None` for 2+ minutes; Engineer-supervisor dispatches showed `status='failed'` repeatedly. Pre-existing Engineer/PM yaml asymmetry around continuous-loop discipline is the root cause; pinned as BACKLOG entry with three-option blast-radius differentiation (Option 1 prompt-only ~20 LOC ≪ Option 2 lifecycle clean-exit ~30-50 LOC ≪ Option 3 new tool surface ~50-80 LOC). Pin 9 (supervisor-runtime-never-exercised methodology amendment) predicted this class of finding.

---

## Chapter 2 — BACKLOG carry-forward

[Deferred to session 2: 7 Component-2 entries detailed with priorities + triggers; Engineer-supervisor continuous-loop TRIGGERED entry; agent yaml runtime-dir auto-seeding entry; commit-3 trio + commit-3.5 ζ-1 RESOLVED entries cross-referenced; sub-milestone-boundary internal-spec-consistency scan per Convention 3 invocation.]

---

## Chapter 3 — Discipline-pattern observations

Chapter-organized per Convention 1 § "At ≥7 patterns, organize into chapter-level structure" rule (precedent: M5-flag-flips close 2026-05-08; renewed here). M5-acceptance chain accumulated 22 patterns across the methodology stack; sub-section thematic clustering below. Reading B reverse-applies in this artifact: methodology context pulled from commit messages and BACKLOG entries INTO prose, deduplicated where multiple pins share an underlying observation.

This session covers pins 1-12 across sub-sections 3.1 through 3.5; pins 13-22 land in session 2.

### 3.1 Commit-to-commit methodology discipline established

Four pins anchored to discipline patterns surfaced or confirmed at specific commits during the chain. All four are observation-quality patterns that did not pre-exist before M5-acceptance and are now durable methodology elements.

#### Pin 1 — Q4' intentional-architectural-difference observation

> Verify intentional-architectural-difference vs. parallel-commit-vintage-gap before scope-expanding.

Anchored at commit 3.6 prep cycle Q4'. The dispatcher launcher (commit 2) wires `--append-system-prompt` for implementer + tester subprocesses. The supervisor launcher (commit 3.6) intentionally OMITS `--append-system-prompt` because supervisor's `yaml.system_prompt` IS the `-p` user message itself — an intentional M3 architectural difference, not a parallel-commit-vintage gap. Without prep-time empirical verification (code-trace at `bridge/supervisor_base.py:583-591` + `engineer_supervisor.py:_subprocess_prompt` + `pm_supervisor.py:_subprocess_prompt`), scope-expansion would have wired `--append-system-prompt` symmetrically and broken supervisor's M3 design.

**Methodology amendment**: when a parallel structural-mirror pattern is candidate at commit prep, verify whether the asymmetry is intentional architecture (preserve) or accumulated drift (close gap). Code-trace verification is the correct surface; symmetric-mirror-by-default is the wrong default.

#### Pin 4 — Q-dependency sequencing pattern

> Prep prompts surface inter-question dependencies at framing time so disposition order matches dependency order.

Anchored at commit 3.5 prep cycle Q1/Q2/Q3 sequencing. Q2 (per-dispatch token minting) had to disposition before Q1 (config retention semantics) because Q1's "RETAIN" disposition was contingent on Q2's "minted-already-correct" outcome. Naive Q1→Q2→Q3 sequencing would have forced premature Q1 disposition under uncertainty about Q2's value. Prep prompt re-ordered Q2-before-Q1 at framing time; disposition order then matched dependency order cleanly.

**Methodology amendment**: at multi-question prep cycles, surface inter-question dependencies in the prep prompt before disposition begins. Disposition order matches dependency order; premature lock-in on dependent questions cascades into reasoning errors that prep-time re-litigation can't cleanly recover from. Pairs with Pin 5 — both pins emerged from the commit 3.5 Q-disposition cycle as distinct prep-cycle-discipline categories: Pin 4 about question-ordering, Pin 5 about reading-bias from naming.

#### Pin 6 — Reading B at write-time discipline

> No methodology-bleed in production docstrings; methodology context lives in commit messages, BACKLOG entries, and closeout chapters.

Anchored at commit 3.5 review (Reading B trim applied post-draft) and confirmed at commit 3.6 (Reading B applied at write-time, no re-trim needed). At commit 3.5, ~17 LOC of methodology bleed (ε-3 / Q1 / Q2 / ζ-1 dispositions, "commit 3.5 prep" attributions, reviewer-Claude / Mike-disposition references) was trimmed from production docstrings post-draft. At commit 3.6, the discipline ran at write-time: no methodology bleed appeared in the draft, no trim needed.

**Methodology amendment**: production docstrings carry architectural content + brief commit-reference tags + empirical-anchor dates. Disposition-cycle context (cycle markers, reading numbers, reviewer attributions, prep-cycle dates) routes elsewhere. Reading B is bidirectional: applies as a constraint at write-time on production code (this pin) AND in reverse for closeout chapters (this artifact) where the chapter pulls methodology context INTO prose and deduplicates where multiple pins share an underlying observation.

#### Pin 8 — Watch entries resolved at trigger moment

> When a prep cycle surfaces a Convention 4 watch trigger, evaluation happens in the same commit cycle, not pushed to closeout.

Anchored at commit 3.5 (δ-3 entry RESOLVED in same commit as `--mcp-config` wiring lands) and commit 3.6 (ζ-1 RESOLVED + bridge/→tools/ cross-layer imports RESOLVED via Path Y extraction in same commit as supervisor symmetric wiring). Convention 4's "trigger-to-evaluate" rule operating at trigger moment rather than deferring to closeout. Counter-precedent if future commits defer watch resolutions to closeout: layer-correction at trigger-evaluate moment is correct operational timing; closeout deferral would let drift accumulate between trigger fire and closeout authoring.

**Methodology amendment**: Convention 4 watch entries fire in two phases — (1) entry pin time when watch threshold is first observed; (2) trigger-evaluate time when threshold-of-N is reached. Phase 2 evaluation is in-same-commit work, not closeout-chapter work. Closeout chapter notes that the resolution happened (audit-lineage); does not perform the resolution.

### 3.2 External-dependency-behavior verification confirmed pattern

#### Pin 3 — External-dependency-behavior verification at six sub-instances

> Empirical verification of external system behavior at prep cycle is a confirmed methodology pattern; six sub-instances accumulated across M5-acceptance chain.

Promote from candidate to confirmed pattern at this closeout. Six sub-instances:

| # | Surface | Verification |
|---|---|---|
| 1 | `claude --print` permission gating | commit 3 prep `/tmp/cc-cwd-probe-*` directories empirically prove fail-closed on cross-boundary operations |
| 2 | MCP tool naming (`mcp__<server>__<tool>` with hyphens) | commit 3 prep subprocess permission-denial messages naming `mcp__meridian-orchestrator__list_agents` |
| 3 | `claude --allowedTools` Playwright invocation | commit 3 prep `claude --effort low --allowedTools` subprocess returning real page title from example.com |
| 4 | `claude --print` `MERIDIAN_ORCHESTRATOR_TOKEN` env var translation | commit 3.5 prep empirically proves env var does NOT translate to `Authorization: Bearer` header |
| 5 | `--strict-mcp-config` hermeticity | commit 3.5 prep sentinel-at-user-scope vs. empty-config probe returns "Tool not available" |
| 6 | `mcp_config.json` schema end-to-end | commit 3.5 prep page title returned + CLOSED_OK from full subprocess invocation with stdio-only config |

**Methodology amendment**: external system behavior (subprocess CLI flags, MCP protocol, OAuth handshakes, OS-specific subprocess launching) is verified empirically at prep cycle BEFORE the wiring commit lands. Code-only verification (read source, infer behavior) is insufficient when the external system is closed-source or has documented-but-untested behaviors. Threshold-of-six confirms the pattern as durable methodology, not one-off occurrence. Distinct from Pin 10 (prep-prompt drafting accuracy) — that pattern is about operator/reviewer drafting drift; this pattern is about external-system behavior empirical verification.

### 3.3 Architectural-fit timing decisions

Two pins anchored to scope-decision timing — when an architectural correction surfaces during chain work, whether to land it in-commit or defer.

#### Pin 2 — Path Y layer-correction at trigger-evaluate moment

> File-move with equivalent tests is in-commit work; bounded blast radius does not require its own commit.

Anchored at commit 3.6 Path Y file-move: `_ORCHESTRATOR_MCP_TOOL_PREFIX` + `resolve_allowed_tools_for_role` moved from `tools/dispatch.py` to `bridge/mcp_config.py` per architectural fit (launcher-composition logic belongs at bridge layer). The bridge/→tools/ cross-layer imports watch entry fired during commit 3.6 prep; evaluation outcome was extraction (in-commit) rather than deferral (separate commit or closeout-chapter pin).

**Methodology amendment**: file-move-with-equivalent-tests has bounded blast radius (two import sites, existing tests catch bad import). Treating every architectural change as needing its own commit is rationalization mode. Reserve blast-radius isolation for high-risk wiring changes (auth + permission landing + lifecycle-bearing); apply in-commit extraction for equivalent-tests file-moves. Pairs with Pin 8 — Path Y is one of the two watch entries resolved at trigger moment during commit 3.6.

#### Pin 7 — Convention 19 candidate threshold-of-three reaches at commit 3.6

> Auth + permission wiring at subprocess launchers is a confirmed methodology pattern.

Pattern-replication at launcher level reaches threshold-of-three at commit 3.6:
1. Commit 2: `--append-system-prompt` at `bridge/dispatcher.py:_default_launcher`
2. Commit 3: `--allowedTools` at `bridge/dispatcher.py:_default_launcher`
3. Commit 3.5: `--mcp-config` + `--strict-mcp-config` at `bridge/dispatcher.py:_default_launcher`
4. Commit 3.6: same three flag surfaces at `bridge/supervisor_base.py:_default_launcher` (combined; supervisor symmetric)

**Promote to Convention 19 at this closeout** (chapter 4 detail in session 2). Each instance ships argv composition with Option A ordering (effort/lifecycle flags → auth/permission flags → prompt), conditional-on-presence semantics (skip flag if value is None), and per-dispatch / per-spawn JSON for hermetic MCP wiring. Future instance with new auth/permission flag follows Convention 19's template; pattern-divergence requires explicit disposition.

### 3.4 Runtime-exercise methodology amendment

#### Pin 9 — Supervisor-enabled runtime configuration never exercised end-to-end (sharpened framing)

> Sub-milestone close requires a runtime smoke test that exercises the wiring under the configuration that actually uses it, not just unit tests of the wiring in isolation.

Sharpened pin authored at commit 4 β-readiness artifact. Commits 2 through 3.6 wired and tested supervisor functionality entirely via injected-fake unit tests and supervisor-flag-disabled integration tests. The supervisor-enabled runtime configuration — `substrate_working_directory` set, engineer/implementer/tester tokens issued, daemon started with supervisors enabled — had never been exercised end-to-end through any prior session. β-readiness audit at commit 4 was the first probe of that runtime path.

Same architectural-gap shape as M4: wiring tests passed without exercising the runtime that uses the wiring. The diagnostic shape generalizes — if the test suite mocks the runtime configuration, the runtime configuration is precisely what the test suite cannot guarantee.

**Methodology amendment**: sub-milestone close requires a runtime smoke test that exercises the wiring under the configuration that actually uses it, not just unit tests of the wiring in isolation. Pin separately from other observations; this is methodology amendment scope, not a passing observation.

**β execution at commit 5 empirically validated Pin 9's prediction**. The Engineer-supervisor continuous-loop gap was invisible to commits 2-3.6 unit + integration tests; surfaced only when Component 1 verification ran the actual usage configuration end-to-end (daemon started, supervisors enabled, OAuth-PM session opened, real submit_task issued, Engineer-supervisor's actual continuous-loop behavior observed). Pin 9 predicted this class of finding; β execution demonstrates the prediction. The methodology-as-moat thesis matures further: predictions encoded as methodology amendments at one milestone get empirically validated at the next, and the artifact chain composes the validation back into the methodology stock. The harness specification + harness-via-dispatch sequencing decisions (Pins 21-22, session 2) are the M6 milestone shape that follows from this validation arc.

### 3.5 Drafting-discipline amendments

Four pins anchored to drafting-time discipline distinct from production-code discipline — how prep prompts, kickoff docs, commit messages, and convention-bearing artifacts get authored without drift between operator/reviewer framing and runtime ground truth. Pin 5 covers reading-bias from naming; Pin 10 covers prep-prompt drafting accuracy; Pin 11 covers role-vs-runtime-mode disambiguation; Pin 12 covers test-count-claim verification.

#### Pin 5 — Env-var-name-vs-architectural-anchor observation

> Env var name constants are not architectural anchors; trace value origin against the name before treating the name as a singleton-implication.

Anchored at commit 3.5 prep cycle Q2 disposition. Q2's prep prompt framed "shared `MERIDIAN_ORCHESTRATOR_TOKEN`" as the architectural model — implying a single token shared across all dispatcher subprocess invocations. Code-trace verification at `dispatcher.py:501-506` showed per-call `TokenStore.issue` minting (per-dispatch tokens, not shared). The "shared" framing in the prep prompt was a misleading artifact of seeing the env var NAME constant; the env var is a key for the token-passing channel, not a singleton-token store.

**Methodology amendment**: an env var name constant suggests its referent's identity to readers. When prep prompts or BACKLOG entries reference an env var by name, verify the runtime value origin (where the value is set; what mints it; whether it's stable across calls or freshly minted per call) before treating the name as architectural anchor for the value's lifecycle. Pairs with Pin 4 — both pins emerged from the commit 3.5 Q-disposition cycle as distinct prep-cycle-discipline categories: Pin 4 about question-ordering, Pin 5 about reading-bias from naming.

#### Pin 10 — Prep-prompt drafting drift caught at Code-side empirical verification

> Operator/reviewer drafts; Code verifies empirically; drift caught at write-time rather than execution time.

Anchored at commit 4 β-readiness artifact daemon-start command correction. Operator-side prep prompt drafted `uv run python -m meridian_orchestrator` (would fail — no `__main__.py` at `src/meridian_orchestrator/`; no `[project.scripts]` in `pyproject.toml`). Code-side empirical verification at write-time corrected to `uv run python -m meridian_orchestrator.main` (per `README:41` + verified usage in commit 3 auth probe daemon-start). Caught before propagating to β execution time, where the failure would have looked like operator setup error rather than drafting drift.

**Methodology amendment**: prep prompts composed by operator or reviewer-Claude benefit from Code-side empirical command verification before lock-in. Distinct discipline from external-dependency-behavior verification (Pin 3): that pattern is about empirical verification of external system behavior; this pin is about prep-prompt-drafting accuracy as a separate verification surface. Both surfaces operate; conflating them loses the distinction.

#### Pin 11 — Kickoff doc PM-supervisor terminology conflation

> When role-name and runtime-mode-name overlap lexically, kickoff docs must disambiguate at first use and maintain the distinction throughout.

M5-acceptance kickoff used "PM" and "supervisor" interchangeably in places where they're architecturally distinct. PM is a role (yaml-defined agent identity — `config/agents/pm.yaml`); supervisor is a runtime mode (`bridge/supervisor_base.py`'s continuous-loop dispatcher that wakes agents, instantiated as `PMSupervisor` and `EngineerSupervisor`). Conflation obscured that the supervisor-runtime path was never exercised by β, only the PM-as-conversational-agent path. The terminology blur retroactively contributed to Pin 18's framing problem (β-as-readiness vs. β-as-execution split was needed at commit 4 because the kickoff's β framing inherited Path C without explicit disposition — see session 2).

**Methodology amendment**: when role-name and runtime-mode-name overlap lexically, kickoff docs must disambiguate at first use and maintain the distinction throughout. "PM-the-role" vs. "PM-supervisor-the-runtime" are not interchangeable; loose coupling at framing time produces under-specified acceptance criteria that surface as scope-confusion when execution diverges from anticipation.

#### Pin 12 — Artifact count claims must be pytest-verified, not eyeballed from new-file inventory

> Test counts cited in commit messages must be `pytest --collect-only` verified, not derived by eyeballing new test-file contents.

Anchored at commit 5 commit message test-count cite. Commit 5's message body claimed "24 new unit tests" derived by summing across new-file breakdowns: 3 (auth) + 3 (oauth) + 4 (registry) + 2 (config) + 6 wrapper functions + 3 Convention 17 sub-type (c) build_argv = 21 + 3 double-counted = "24". The pytest-verified count at HEAD is 21 collected items — the 3 build_argv tests (`test_build_argv_composition_pm` + `test_build_argv_no_passthrough_args` + `test_build_argv_raises_on_missing_claude_binary`) are inside the 9-item `test_operator_wrapper.py` collection, not additive. Sub-type categorization in the commit-message breakdown labels existing tests; it does not add new tests. Surfaced at M5-acceptance closeout chapter authoring time when chain totals (function-level delta + collected-item delta) failed to reconcile against the per-commit cite sum; reconciliation drove explicit `pytest --collect-only` verification + cross-reference against `git grep` function-count delta.

**Methodology amendment**: when a commit message cites a test count, derive the count from `pytest --collect-only` output run at the commit, not from eyeballing new-file contents + breakdown by file. Sub-type categorization (Convention 17 sub-types, F2.1 sub-categorization, etc.) labels existing tests; it does not add new tests. Pairs with Pin 10 (prep-prompt drafting drift caught at Code-side empirical verification) — both pins concern artifact claims that didn't survive verification: Pin 10 about command syntax in prep prompts, Pin 12 about count claims in commit messages. Pairs at broader thesis level with Pin 11 (kickoff doc terminology disambiguation): drafting-discipline amendments share the underlying principle that artifact precision requires verification grounded in runtime ground truth (pytest output, runtime command verification, role-vs-runtime disambiguation), not author-side reasoning alone.

---

[Session 2 deliverables: chapter 2 (BACKLOG carry-forward) + chapter 3 sub-sections 3.6 through 3.8 (pins 13-22) + chapter 4 (Convention 19 promotion) + chapter 5 (V2 roadmap status) + chapter 6 (process metrics + build-time data point) + chapter 7 (Phase 1 acceptance-coverage scan per kickoff Decision 3) + closing "What's next" section (M6 harness + Engineer continuous-loop fix sequencing).]
