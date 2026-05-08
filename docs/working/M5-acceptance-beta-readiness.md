# M5-acceptance β-readiness

Pre-condition verification + operator-setup checklist for the β
operational gate (commit 4.5 artifact: `docs/working/M5-acceptance-beta-
findings.md` lands when β runs end-to-end through PM→Engineer→
Implementer→Tester chain with real subprocess→MCP round-trips).

β execution shape (per kickoff doc): Mike opens interactive Claude Code
session as PM; submits one substrate work item; PM-supervisor composes
and submits to Engineer; Engineer-supervisor dispatches Implementer +
Tester; chain reports back. Bounded HITL per CQ-E E-2.

## Section 1 — β1–β11 pre-condition verification

| # | Pre-condition | State | Empirical anchor |
|---|---|---|---|
| β1 | Daemon running locally; FastMCP at `127.0.0.1:8765/mcp` reachable | ✗ Not running | `claude mcp list` shows `meridian-orchestrator: ✗ Failed to connect` |
| β2 | PM/Engineer-supervisor wiring (`--allowedTools` + `--mcp-config` + `--strict-mcp-config` + per-spawn JSON; no `--append-system-prompt`) | ✓ Verified at code level | Commit 3.6 (666ac14): `bridge/supervisor_base.py` argv composition + `_subprocess_allowed_tools` hook + `_spawn` JSON write |
| β3 | Implementer/Tester dispatch wiring (`--append-system-prompt` + `--allowedTools` + `--mcp-config` + `--strict-mcp-config` + per-dispatch JSON) | ✓ Verified at code level | Commits 2 + 3 + 3.5 (8b15611 + a7d974a + 28cd760): `bridge/dispatcher.py` argv composition |
| β4 | All 4 yaml files have `allowed_tools` content | ✓ Verified at code level | tester.yaml + implementer.yaml (commit 3); pm.yaml + engineer.yaml (commit 3.6) |
| β5 | Per-dispatch JSON materializes correctly under `<dispatches_root>/<dispatch_id>/mcp_config.json` (Playwright entry for tester only) | ⚠ Wiring verified; not exercised end-to-end | Unit tests at commit 3.5 + 3.6 confirm shape via mocked subprocess; real materialization requires real dispatch run |
| β6 | Per-spawn JSON materializes correctly under `<dispatches_root>/<spawn_dispatch_id>/mcp_config.json` (no Playwright) | ⚠ Wiring verified; not exercised end-to-end | Unit tests at commit 3.6 confirm shape; real materialization requires real supervisor spawn |
| β7 | FastMCP auth chain validates per-token Bearer headers; revoked tokens return None from `auth.TokenStore.lookup` | ⚠ Wiring verified; not exercised end-to-end | `server.py:198,219,222` enforces; `auth.TokenStore.revoke` + `lookup` semantics confirmed at unit-test level |
| β8 | Audit trail captures dispatch lifecycle | ⚠ Wiring exists; only real run populates rows | `dispatch.token_created` / `dispatch.subprocess_started` / `dispatch.complete_called` / `dispatch.token_revoked` audit emissions in `bridge/dispatcher.py` + `bridge/supervisor_base.py` |
| β9 | stderr.log + mcp_config.json both retained at terminal state | ⚠ Wiring exists; only real run produces files | F4 stderr drain + commit 3.5 + 3.6 retention semantics |
| β10 | Substrate work item selected | ✗ Not selected | Operator's choice; not made yet |
| β11 | Bounded HITL per CQ-E E-2 | N/A pre-execution | Depends on Mike's intervention discipline during β |

## Section 2 — Operator-setup checklist (probe-then-act)

Sequenced. Each item is a concrete command + expected output anchor.
Probe state before acting; do not assume.

- [ ] **Set `MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY` env var.** Set in
  `.env` at orchestrator root or in shell env. Path should point to the
  substrate clone (e.g., `C:\Users\mbgol\dev\meridian-homepoc` per
  current substrate location).
  - Expected: env var visible to daemon at startup.
  - Empirical context: never set in any prior session; daemon validation
    raised `MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY must be set when
    MERIDIAN_ORCH_ENABLE_ENGINEER_SUPERVISOR=true` at the auth-probe
    daemon-start in commit 3 prep cycle.

- [ ] **Probe existing keyring tokens (do not assume state).**
  ```
  uv run python -m scripts.list-tokens
  ```
  - Expected: full table including revoked rows.
  - Inspection criteria: are `engineer` / `implementer` / `tester` rows
    present with `status=active`? At β-readiness audit time (2026-05-08)
    only `pm` rows visible (1 static + 2 OAuth); engineer/implementer/
    tester absent. If absent → proceed to issuance steps below; if
    present with mismatched agent_id → investigate before re-issuing
    (revoking + re-issuing changes agent_id and breaks audit-row FK
    references).

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

- [ ] **Start daemon with supervisors enabled.**
  ```
  uv run python -m meridian_orchestrator.main
  ```
  - Expected log lines (in order): `startup.begin`;
    `migration.applying` / `migration.applied` (any pending migrations);
    `canonical.provisioning_begin` (substrate clone provisioning;
    first-boot is slow); `canonical.provisioning_complete` with
    `elapsed_seconds`; `agents.seeded` with all four roles in `roles`;
    `pm_supervisor.started` with `dispatch_id`;
    `engineer_supervisor.started` with `dispatch_id`; `startup.complete`
    with `engineer_supervisor_enabled=true` + `pm_supervisor_enabled=true`
    + `dispatcher_constructed=true` + `agents_loaded` listing all four
    roles; `Uvicorn running on http://127.0.0.1:8765`.
  - Failure modes to watch: `ValidationError` on
    `MERIDIAN_ORCH_SUBSTRATE_WORKING_DIRECTORY` (preceding step missed);
    `agents.unseeded_role` warnings for any role missing a token
    (preceding token-issuance steps missed); `EngineerSupervisor
    requires a registered 'engineer' agent` (registry resolution
    failed); canonical-provisioning errors (substrate path wrong or
    not a git repo).

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

Count: **0 of 3** post-commit-4.

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
