# M5-acceptance β-readiness

Pre-condition verification + operator-setup checklist for the β
operational gate (commit 4.5 artifact: `docs/working/M5-acceptance-beta-
findings.md` lands when β runs end-to-end through PM→Engineer→
Implementer→Tester chain with real subprocess→MCP round-trips).

β execution shape (per kickoff doc): Mike opens interactive Claude Code
session as PM; submits one substrate work item; PM-supervisor composes
and submits to Engineer; Engineer-supervisor dispatches Implementer +
Tester; chain reports back. Bounded HITL per CQ-E E-2.

## Session 7 refresh banner (2026-05-12)

This doc was authored pre-session-5; refreshed at session 7 open to
reflect:

- Auto-seeding fix (commit `8365e98`, session 5) — daemon now
  auto-deploys yaml configs from repo source to runtime dir at startup;
  manual `Copy-Item` no longer required.
- Engineer continuous-loop fix (commit `fd36f37`, session 6) — Engineer
  subprocess sustained in continuous mode across 9 cycle boundaries at
  session 6 runtime exercise; β5/β6 wiring assumptions still hold.
- Windows shutdown gap (a) workaround validated at session 6 — Ctrl+C
  in interactive PowerShell shell traverses clean shutdown sequence
  (`shutdown.begin` → `subprocess_terminated` (both supervisors) →
  `token_revoked` (both) → `storage.closed` → `shutdown.complete`).
- Threshold rule advanced 0 of 3 → 2 of 3 at session 5 + session 6
  feat commits.
- CF1-CF4 session 6 carry-forwards routed to session 8 β re-execution
  per session-6 close handoff doc `docs/working/session-6-close-2026-05-12.md`.

This refresh updates β-row state markers (Section 1), operator-setup
checklist empirical contexts (Section 2), threshold count (Section 4),
and adds methodology postscript to Section 5. β execution remains
operator-driven at session 8.

## Section 1 — β1–β11 pre-condition verification

| # | Pre-condition | State | Empirical anchor |
|---|---|---|---|
| β1 | Daemon running locally; FastMCP at `127.0.0.1:8765/mcp` reachable | ✓ Verified at session 5 + 6 runtime exercises (operator-driven daemon spinup) | Session 5 (2026-05-11): daemon PID 41084 ran ~95s with auto-seeded yamls + `startup.complete` not skeleton mode. Session 6 (2026-05-12): daemon PID 16960 ran ~18 min through Ctrl+C clean shutdown; transcripts at `~/.meridian-orchestrator/transcripts/auto-seeding-runtime-exercise-2026-05-11.log` + `~/.meridian-orchestrator/transcripts/engineer-fix-runtime-exercise-2026-05-12.log`. β1 must be re-probed at β open since daemon doesn't auto-start across sessions. |
| β2 | PM/Engineer-supervisor wiring (`--allowedTools` + `--mcp-config` + `--strict-mcp-config` + per-spawn JSON; no `--append-system-prompt`) | ✓ Verified at code level + runtime | Code: commit 3.6 (666ac14) `bridge/supervisor_base.py`. Runtime: session 6 daemon spinup observed `pm_supervisor.started` (dispatch `f98ce3df`) + `engineer_supervisor.started` (dispatch `5b8c4b33`) with explicit dispatch_ids in startup log; both supervisors transitioned `running` → `cancelled` cleanly at Ctrl+C. |
| β3 | Implementer/Tester dispatch wiring (`--append-system-prompt` + `--allowedTools` + `--mcp-config` + `--strict-mcp-config` + per-dispatch JSON) | ✓ Verified at code level | Commits 2 + 3 + 3.5 (8b15611 + a7d974a + 28cd760): `bridge/dispatcher.py` argv composition. Runtime exercise still pending — sessions 4-6 exercised supervisor paths only; Implementer/Tester per-dispatch paths await β execution. |
| β4 | All 4 yaml files have `allowed_tools` content | ✓ Verified at code level + auto-seed-deployed at runtime | tester.yaml + implementer.yaml (commit 3); pm.yaml + engineer.yaml (commit 3.6 + commit `fd36f37` session-6 extension adding `get_loop_state` + `wait_for_next_tick` to engineer.yaml `mcp_tools_allowed`). Session 6 verified `agents_dir.seeded files_copied=['engineer.yaml','implementer.yaml','pm.yaml','tester.yaml']` at daemon start. |
| β5 | Per-dispatch JSON materializes correctly under `<dispatches_root>/<dispatch_id>/mcp_config.json` (Playwright entry for tester only) | ⚠ Wiring verified; not exercised end-to-end | Unit tests at commit 3.5 + 3.6 confirm shape via mocked subprocess. Real materialization requires actual Implementer/Tester dispatch — first exercise at session 8 β execution. |
| β6 | Per-spawn JSON materializes correctly under `<dispatches_root>/<spawn_dispatch_id>/mcp_config.json` (no Playwright) | ✓ Verified at code level + runtime | Unit tests at commit 3.6 confirm shape. Session 6 daemon spinup spawned PM-supervisor + Engineer-supervisor subprocesses with full wiring; subprocess_started audit events fired with dispatch_ids — per-spawn JSON materialization is implicit in successful subprocess spawn. |
| β7 | FastMCP auth chain validates per-token Bearer headers; revoked tokens return None from `auth.TokenStore.lookup` | ⚠ Wiring verified; partial runtime exercise | `server.py:198,219,222` enforces; `auth.TokenStore.revoke` + `lookup` semantics confirmed at unit-test level. Session 6 exercised `dispatch.token_created` + `dispatch.token_revoked` audit emission at shutdown; revoked-token rejection path at MCP layer remains unexercised. |
| β8 | Audit trail captures dispatch lifecycle | ✓ Verified at runtime | Session 6 audit_log entries: `dispatch.issued` / `dispatch.subprocess_started` / `dispatch.token_created` / `dispatch.token_revoked` / `dispatch.subprocess_unexpected_exit` (PM rc=0 carry-forward CF1) / `dispatch.subprocess_terminated` with `reason='shutdown'` (clean Ctrl+C path). Full lifecycle coverage minus `dispatch.complete_called` (requires Implementer/Tester dispatch end-to-end at β). |
| β9 | stderr.log + mcp_config.json both retained at terminal state | ⚠ Wiring exists; only real Implementer/Tester run produces files | F4 stderr drain + commit 3.5 + 3.6 retention semantics. Supervisor exercises (sessions 5-6) don't write to per-dispatch dirs; this is dispatcher-path specific. First exercise at session 8 β execution. |
| β10 | Substrate work item selected | ✗ Not selected | Operator's choice; not made yet. Selection criteria per Section 2 unchanged. |
| β11 | Bounded HITL per CQ-E E-2 | N/A pre-execution | Depends on Mike's intervention discipline during β. |

## Section 2 — Operator-setup checklist (probe-then-act)

Sequenced. Each item is a concrete command + expected output anchor.
Probe state before acting; do not assume.

- [ ] **Set `MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY` env var.** Set in
  `.env` at orchestrator root or inline in the interactive PowerShell
  session before daemon start. Path should point to the substrate clone
  (e.g., `C:\Users\mbgol\dev\meridian-homepoc` per current substrate
  location).
  - Expected: env var visible to daemon at startup.
  - Empirical context: session 6 used inline PowerShell-shell-set
    pattern: `$env:MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY = "C:/Users/mbgol/dev/meridian-homepoc"` before `uv run python -m meridian_orchestrator.main`. Both `.env` and inline-shell paths work; inline is simpler for single-session β execution. Required because Engineer-supervisor enabled; daemon validation raises `ValidationError` on missing var.

- [ ] **Probe existing keyring tokens (do not assume state).**
  ```
  uv run python -m scripts.list-tokens
  ```
  - Expected: full table including revoked rows.
  - Inspection criteria: are `engineer` / `implementer` / `tester` rows
    present with `status=active`? Sessions 5 + 6 daemon spinups
    succeeded with all four roles configured, so tokens were active at
    those points. State may have drifted since (token revocation at
    daemon shutdown is observed at session 6 shutdown traversal:
    `dispatch.token_revoked` for both PM + Engineer supervisor
    dispatches). Re-probe at β open; if active rows present for
    `engineer` / `implementer` / `tester`, proceed directly to daemon
    start. If absent → proceed to issuance steps below. If present with
    mismatched agent_id → investigate before re-issuing (revoking +
    re-issuing changes agent_id and breaks audit-row FK references).

- [ ] **(Conditional on probe) Issue static engineer token.**
  ```
  uv run python -m scripts.issue-token --role engineer --label mike-cd
  ```
  - Expected: token_id printed; row appears in `list-tokens` with
    `status=active`.

- [ ] **(Conditional) Issue static implementer token.**
  ```
  uv run python -m scripts.issue-token --role implementer --label mike-cd
  ```

- [ ] **(Conditional) Issue static tester token.**
  ```
  uv run python -m scripts.issue-token --role tester --label mike-cd
  ```

- [ ] **Start daemon with supervisors enabled in interactive PowerShell.**
  ```
  uv run python -m meridian_orchestrator.main
  ```
  - **Critical: run in interactive PowerShell context.** Required for
    Ctrl+C → clean shutdown path per Windows shutdown gap (a)
    workaround (BACKLOG `a3562e2`). Validated at session 6 runtime
    exercise — `taskkill`/`Stop-Process` is the unmitigated Windows
    shutdown gap that (a) workaround avoids.
  - Expected log lines (in order, per session 5 + 6 observations):
    `startup.begin`; `migration.applying` / `migration.applied` (any
    pending migrations); `canonical.provisioning_begin` (substrate
    clone provisioning; first-boot is slow);
    `canonical.provisioning_complete` with `elapsed_seconds`;
    **`agents_dir.seeded`** with `files_copied=['engineer.yaml','implementer.yaml','pm.yaml','tester.yaml']` (auto-seeding fix `8365e98`); `agents.seeded` with all four roles in `roles`;
    `pm_supervisor.started` with `dispatch_id`;
    `engineer_supervisor.started` with `dispatch_id`; `startup.complete`
    with `engineer_supervisor_enabled=true` + `pm_supervisor_enabled=true`
    + `dispatcher_constructed=true` + `agents_loaded` listing all four
    roles; `Uvicorn running on http://127.0.0.1:8765`.
  - **Side observation (informational; non-blocker per session 6)**:
    `oauth_usage_poller.skipped_no_token` fires if
    `CLAUDE_CODE_OAUTH_TOKEN` not set — budget-aware routing falls back
    to per-role defaults; `fallback_active=True` appears in
    `routing_factors` on `check_budget_for_task` calls;
    `get_window_state` returns `pace_recommendation='unknown'`. Doesn't
    block β execution but affects routing-decision verbosity in audit
    trail. Set token if budget-aware routing visibility is wanted at β.
  - Failure modes to watch: `ValidationError` on
    `MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY` (preceding step missed);
    `agents.unseeded_role` warnings for any role missing a token
    (preceding token-issuance steps missed); `EngineerSupervisor
    requires a registered 'engineer' agent` (registry resolution
    failed); canonical-provisioning errors (substrate path wrong or
    not a git repo); auto-seeding regression — `agents_dir.seeded`
    fails to fire OR `files_copied` count != 4 (session 5 fix
    `8365e98` regression — investigate before β).

- [ ] **Verify daemon reachable via MCP.**
  ```
  claude mcp list
  ```
  - Expected: `meridian-orchestrator: http://127.0.0.1:8765/mcp (HTTP) - ✓ Connected`.

- [ ] **(Optional) Re-register meridian-orchestrator at user scope per
  PM session cwd preference.**
  ```
  claude mcp add --transport http -s user meridian-orchestrator http://127.0.0.1:8765/mcp
  ```
  - Empirical context: only needed if Mike's interactive PM session
    opens from non-home cwd. Per ε-3 Surface 1 + Surface 2
    consolidation, subprocess auth path uses per-dispatch `--mcp-config`
    + `--strict-mcp-config` (hermetic) and does NOT depend on user-scope
    registration; this re-registration is operator convenience for
    Mike's interactive PM Claude Code session only.

- [ ] **Select substrate work item per β10 framing.**
  - Criterion: mechanically simple but architecturally non-trivial.
  - Examples: single-field fixture data structure addition with tests;
    endpoint mirror of existing one; refactor touching 3-5 files.
  - Operator discretion.

- [ ] **Open interactive PM Claude Code session; β execution begins.**

## Section 3 — Recommended β execution sequencing

After all checklist items above complete:

1. Operator setup → daemon start → daemon reachable via MCP → substrate
   work item selected.
2. Mike opens interactive PM Claude Code session (OAuth-authenticated
   per existing PM OAuth tokens).
3. Mike submits work item to PM-supervisor in natural language.
4. PM-supervisor composes work item via orchestrator MCP tools
   (`submit_task`, `notify_human` if needed for clarification).
5. PM-supervisor submits subtask to Engineer-supervisor via task queue.
6. Engineer-supervisor (daemon-spawned subprocess; running continuous
   loop per engineer.yaml prompt) picks up subtask; composes
   implementer dispatch spec.
7. Engineer-supervisor invokes `dispatch_implementer`; daemon spawns
   Implementer subprocess with full wiring (`--effort` +
   `--append-system-prompt` + `--allowedTools` + `--mcp-config <path>` +
   `--strict-mcp-config` + `-p <spec>`); per-dispatch JSON written at
   `<dispatches_root>/<dispatch_id>/mcp_config.json`.
8. Implementer subprocess writes/edits substrate code via
   `allowed_tools`; commits on `dispatch/<dispatch_id>` branch in
   per-dispatch worktree; reports completion via `complete_dispatch`
   MCP tool.
9. Engineer-supervisor invokes `dispatch_tester`; daemon spawns Tester
   subprocess with full wiring + Playwright entry in per-dispatch
   mcp_config.json; verifies implementer's commit.
10. Tester reports verification result via `complete_dispatch`.
11. Engineer-supervisor merges dispatch branch into substrate canonical
    (per worktree merge semantics in `bridge/dispatcher.py:_merge_and_record`).
12. Engineer-supervisor reports completion to PM-supervisor (via task
    status update or direct chat).
13. PM-supervisor reports outcome to Mike.

Findings capture artifact: `docs/working/M5-acceptance-beta-findings.md`
(commit 4.5 artifact when β runs operationally). Structure per kickoff
doc β findings capture shape:
- β session start time + substrate work item selected + git HEADs at
  start (orchestrator + substrate)
- Pre-condition verification results (β1–β11 pass/fail with anchors)
- Execution timeline (per-step start/end, subprocess invocations, MCP
  tool calls, audit trail entries)
- Findings: each surface flagged with severity (BLOCK / CONTINUE /
  DEFER) per disposition routing
- Threshold rule status update at session close
- β session end time + git HEADs at end + outcome (β closed / β reopen
  required)

## Section 4 — Threshold rule status

Count: **2 of 3** post-session-6-close.

Advances since β-readiness audit (commit 4, pre-session-5):
- Session 5 commit `8365e98` (`feat(daemon): runtime config auto-seeding`) advanced 0 of 3 → 1 of 3
- Session 6 commit `fd36f37` (`feat(engineer): continuous-loop scope-amended fix per BACKLOG Option 1`) advanced 1 of 3 → 2 of 3

Doc-only commits in sessions 5-7 (BACKLOG Windows shutdown entry
`a3562e2`, cowork .gitignore `9b1c36f`, session 5-7 close handoff
docs) do NOT count against threshold rule.

Session 8 β execution: operator-acceptance work; does NOT advance
threshold rule by itself. If CF1 investigation surfaces architectural
fix shipped at session 8, that commit advances 2 of 3 → 3 of 3.

Reading 2 (charitable) disposition recorded: β-as-readiness vs.
β-as-execution split is operational clarification of what β-the-commit
was always going to look like, not chain-growth methodology
rationalization. β operational gate is operator-driven; commit 4
artifact captures pre-condition state + operator-setup checklist;
commit 4.5 artifact captures β findings when Mike runs β operationally.

The split does NOT count as a new commit triggered by wiring discovery;
it is an artifact of cap discipline + Reading B (Code session cannot run
operator-driven β; commit 4 artifact + commit 4.5 findings = the
clean way to land the β work across sessions).

Pin reading 2 here for closeout chapter anchor so future threshold-rule
applications anchor to this disposition rather than re-litigating the
split.

## Section 5 — Closeout pin (8th pin) — sharpened framing

Sharpened pin for M5-acceptance closeout chapter (commit 5):

> Commits 2 through 3.6 wired and tested supervisor functionality
> entirely via injected-fake unit tests and supervisor-flag-disabled
> integration tests. The supervisor-enabled runtime configuration —
> substrate_working_directory set, engineer/implementer/tester tokens
> issued, daemon started with supervisors enabled — has never been
> exercised end-to-end through any prior session. β-readiness audit at
> commit 4 is the first probe of that runtime path. Same architectural-
> gap shape as M4: wiring tests passed without exercising the runtime
> that uses the wiring. Methodology amendment for future milestones:
> sub-milestone close requires a runtime smoke test that exercises the
> wiring under the configuration that actually uses it, not just unit
> tests of the wiring in isolation.

Pin as standalone item in commit 5 closeout chapter, NOT folded into
other pins. Methodology amendment, not just an observation.

### Session 7 postscript (2026-05-12): pin operationalized across sessions 4-6

The methodology amendment above has been operationalized across the
M5-acceptance arc:

- **Session 4** runtime exercise (Engineer fix authoring): surfaced
  Pin 9 firing — runtime exercise revealed `_supervisor_kwargs` field
  drift that unit tests didn't catch. Methodology amendment validated
  empirically: runtime smoke test catches what wiring tests miss.
- **Session 5** auto-seeding runtime exercise (commit `8365e98`):
  Mike's (α) operator-clear path exercised the runtime against the
  shipped fix; surfaced Finding 2 pm.yaml drift as a side effect.
- **Session 6** Engineer fix runtime exercise (commit `fd36f37`):
  Engineer-supervisor sustained continuous mode across 9 cycle
  boundaries; surfaced 4 carry-forwards (CF1-CF4) routed to session 8
  β re-execution.

Companion methodology pin candidates surfaced session 6 (route to M6
closeout pin set):

- **Pin 9 generalization** (CF3): runtime exercise surface area
  extends beyond the fix under test. Side-findings deserve disposition
  discipline even when fix verification is clean. Two empirical anchors
  (session 5 pm.yaml drift; session 6 PM rc=0-no-compaction pattern).
- **Observability-bounded-by-schema-reality sub-observation** (CF3
  fold-in): when designed-behavior surface doesn't intersect observable
  surface (e.g., `get_loop_state` called per prompt but no per-MCP-call
  audit_log ledger), fix can be correctness-verified at intersect
  surfaces but design-fidelity verified only by indirect evidence.

Both candidates compose with the original 8th pin rather than
superseding it. M5-acceptance closeout chapter 5 retains the 8th pin
as authored; M6 closeout chapter can pin the generalization with
sessions 4-6 as multi-anchor empirical evidence.
