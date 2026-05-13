# Session 7 close — 2026-05-12

Session 7 closed cleanly: pre-β preparation session covering β-readiness doc refresh + CF1 code probe + CF4 invalidation at probe (both tokenizer-recalibration angle AND quota-doubling angle) + Pin 12 firings #6 + #7 cataloged. **Threshold rule unchanged at 2 of 3** (zero production code changes; doc-only commit). β re-execution deferred to session 8 per Option C' split-session methodology. Three carry-forwards revised; CF4 marked invalidated; CF1 promoted to **highest-priority investigation route** with code-level anchor at `bridge/supervisor_base.py:875-920`.

Use this doc as session-8 restart anchor.

---

## What landed at session 7

One doc-only commit landing the β-readiness refresh + this close handoff doc:

- **(Step 4 pending)** `docs(working): session 7 close — β-readiness refresh + CF1 code probe findings + CF4 invalidated + Pin 12 firings #6 + #7` — refreshes `docs/working/M5-acceptance-beta-readiness.md` (+123/-32) and authors this file (~300 LOC). **Does NOT count against threshold rule (doc-only).**

Threshold rule remains 2 of 3 throughout session 7. β re-execution at session 8 is acceptance-only work; advances threshold only if CF1 investigation surfaces architectural fix shipped at session 8.

## Repo state

- HEAD pre-Step-4: `75a2ad8` (session 6 close docs commit) — unchanged throughout session 7 probe work
- HEAD post-Step-4: pending (this doc + β-readiness refresh)
- Working tree at session-7-close commit: clean
- Threshold rule: **2 of 3** (unchanged from session 6 close)
- main is 14 commits ahead of origin/main (private state; not pushed)

## Session 7 probe findings

### Probe 1: Opus 4.7 addendum existence

`docs/kickoff/opus-4-7-effort-and-thinking-addendum.md` — **250 lines, established 2026-05-06 at M5-continuous-pm-driver commit 6** per F1.3 hybrid-amendment-doc disposition. Covers:
- Per-role effort assignments (PM/Engineer/Implementer `xhigh`; Tester `high`)
- Adaptive thinking daemon-vs-subprocess boundary (CC v2.1.117+ defaults to xhigh on Opus 4.7)
- CLI flag verification (`--effort <level>` literal verified)
- Operator-agency framing (full 5-value Literal)
- Phase 2 reopening conditions

**CF4's "addendum overdue from before M2" premise was inaccurate.** Pin 12 firing #6 — Code carried forward stale session-memory framing without repo verification. Six firings of Pin 12 across the M5-acceptance arc.

### Probe 2: CF4 compaction-trigger hypothesis (tokenizer angle)

PM compaction trigger logic per `config/agents/pm.yaml:188` + `engineer.yaml:182` + `docs/kickoff/orchestrator-utilization-addendum.md` 2026-05-01 revision:

> **Compaction triggers on `subscription_5h >= 80%`, not token count.**

The trigger is **utilization-percentage-based**, normalized 0-100% by `/api/oauth/usage`. The 80% threshold is independent of tokenizer counts. Opus 4.7 tokenizer change (~1x-1.35x more tokens) affects how quickly utilization rises but NOT the 80% compaction threshold. If anything, the tokenizer change causes compaction to fire **earlier** (correct behavior, not miscalibration).

**CF4 tokenizer-angle hypothesis invalidated at probe.**

### Probe 3: CF4 quota-hardcoding hypothesis (Anthropic quota-doubling angle, ~2026-05-06)

Code probe of `src/meridian_orchestrator/`:
- `tools/budget.py` thresholds are percentage-based: `_CONSERVE_REMAINING_THRESHOLD = 20.0` / `_RATE_LIMITED_UTILIZATION_THRESHOLD = 100.0` / `_UPGRADE_REMAINING_THRESHOLD = 50.0` / `_UPGRADE_MINUTES_TO_RESET_THRESHOLD = 60`
- `_CYCLE_DURATION_SECONDS` constants (`subscription_5h: 5*3600`, `subscription_7d*: 7*86400`) are **time-based**, used only for computing `window_start` from `resets_at`; not quota-related
- `bridge/oauth_usage.py` polls `/api/oauth/usage` at 30s cadence (with 60→120→240→480→600s exponential backoff on 429); reads percentage values returned by Anthropic; **no quota-amount assumptions**

**No hardcoded token-budget-per-window constant exists.** Quota-doubling is transparent to Orchestrator's percentage-based architecture: same absolute usage → half utilization% under doubled quota → compaction fires later (correctly proportional to actual capacity); the 80% threshold stays 80% against current normalized quota.

**CF4 quota-hardcoding-angle hypothesis invalidated at probe.** Pin 12 firing #7 — Code drafted session 7 framing without checking whether Anthropic's quota landscape had shifted recently. Mike caught at his second prompt.

### Probe 4: CF1 supervisor lifecycle code anchor

`src/meridian_orchestrator/bridge/supervisor_base.py:738-928` — full lifecycle code path.

**Architecture:**

```
Watchdog (line 738):
  Every check_interval seconds:
    1. _check_and_handle_subprocess_exit (line 772)
       - Read proc.returncode
       - If _compaction_pending (line 794) → _handle_compaction_completed
       - Else → _handle_subprocess_unexpected_exit
    2. (If no exit detected) _check_and_handle_timeout

_compaction_pending flag (line 387):
  - Default False
  - Set True by mark_compaction_pending() (line 579), called by
    tools.supervisor.signal_compaction MCP handler
  - Reset False on every _spawn() (line 608)
```

**Two designed exit paths:**

| Path | Trigger | Status | Cap counter | Notes-on-row |
|---|---|---|---|---|
| **`_handle_compaction_completed`** (line 800) | subprocess called `signal_compaction` MCP tool before exit | `compacted` | **NOT incremented** (healthy lifecycle) | (none) |
| **`_handle_subprocess_unexpected_exit`** (line 875) | subprocess exited without `signal_compaction` call | `failed` | **incremented** (failure mode, same policy as heartbeat-timeout) | `"subprocess exited rc={exit_code} without prior compaction signal"` |

**CF1 categorization per session 7 spec**:

- (a) Designed exit path — benign supervisor lifecycle: **REJECTED.** Code labels this path `dispatch.subprocess_unexpected_exit` with `status='failed'` + cap-counter increment + notes string. Failure-mode-detection, not benign.
- (b) Unexpected exit path — genuine finding (subprocess timeout / internal error / designed-handshake-bypass): **CONFIRMED.** The "without prior compaction signal" log is the designed-handshake-bypass label.
- (c) Hybrid — designed for specific condition firing outside that condition: not applicable; the code path is exactly what its label says.

**Net**: the supervisor design correctly distinguishes the two paths. **PM is misbehaving** by exiting without calling `signal_compaction` first. The "without prior compaction signal" notes string is exactly what the code writes when subprocess exits unexpectedly — same code path as if PM had crashed, except rc=0 rather than rc=non-zero.

**Two PM-behavior hypotheses** (need β stderr/stdout capture to disambiguate):
1. PM subprocess processes its prompt once and exits naturally (Claude Code's default "task done" behavior) before entering the continuous loop's `wait_for_next_tick`. The prompt's continuous-mode section instructs the loop, but Claude may not "enter" the loop if it interprets its instructions as a one-shot.
2. PM enters the loop but hits an internal abnormal exit (turn count, context length, error). Claude Code can return rc=0 even on internal completion-without-task-fulfillment.

**Stabilization signal**: session 6 PM dispatch 3 (`174fa15f`) sustained 11+ min, so the prompt CAN drive the continuous loop correctly. First two dispatches may be related to startup-race or first-tick state.

**Cap-policy implication for β**: 2 unexpected exits in 4 min = 30/hr rate if sustained — well over the 3/hr cap. Session 6 didn't hit cap (only 2 exits total before stabilization). β execution will exercise this empirically and may surface the cap-hit condition if the rate sustains.

**Race-condition validation**: `dispatch.token_revoked` with `reason='unexpected_exit'` (per code at line 908) means tokens revoked on unexpected exit. If Engineer holds token-bound reference to failed-PM dispatch, that reference invalidates without automatic Engineer-side knowledge of failover.

## Carry-forwards updated (post-session-7 disposition)

### CF1: PM-supervisor rc=0-without-prior-compaction-signal — **promoted to highest-priority investigation route**

Session 7 code probe confirms CF1 categorization (b): the rc=0-no-compaction path is the supervisor's **designed failure-mode-detection** path. PM is exhibiting the failure mode the code is designed to catch.

Investigation priorities for session 8:
1. **Capture PM subprocess stderr/stdout at β** to see what PM does in those first 1-3 min before exit. Daemon writes stderr to `<dispatches_root>/<dispatch_id>/stderr.log` per F4 retention semantics — verify this works for PM-supervisor dispatches (β6 row in β-readiness doc).
2. **Verify PM's prompt has the continuous loop being entered**, not just being instructed. Sample of PM's first conversation turn would surface whether it's reaching the `wait_for_next_tick` call.
3. **Check Claude Code subprocess defaults** — does Claude Code subprocess have any "fast-exit-on-prompt-complete" behavior that bypasses prompt-instructed loops? Cross-reference Opus 4.7 behavioral default shift ("fewer tool calls by default, using reasoning more") — could this affect loop entry?
4. **Check why dispatch 3 stabilized**. Session 6 observed PM dispatch 3 (`174fa15f`) sustained 11+ min while dispatches 1 and 2 each lasted ~3 min and ~1 min. Same daemon, same env, same prompt. What state difference at startup allowed sustained running?

Race-condition capacity remains latent at session 7 close. Session 8 β execution will exercise active PM-Engineer coordination — if rc=0 pattern recurs during coordination window, race likely manifests.

Code anchor: `bridge/supervisor_base.py:875-920` for `_handle_subprocess_unexpected_exit` + line 794 for the `_compaction_pending` dispatch in watchdog.

### CF2: Engineer continuous-mode token consumption rate — **unchanged**

Session 6 partial invalidation holds (`actual_tokens` flat at 57212 during 8-min steady-state observation). Session 8 β execution will exercise PM-Engineer coordination under active work — measure rate during ACTIVE work (Engineer received dispatch + reasoning + tool use) vs IDLE state (current observation).

`actual_tokens` column semantics question remains open — investigation route for session 8b dedicated cost session if needed.

### CF3: Pin 9 generalization + observability-bounded-by-schema-reality fold-in — **unchanged**

Route to M6 closeout pin set authoring. Two empirical anchors (session 5 pm.yaml drift; session 6 PM rc=0 + observability). No session-7 action; no pin commit during session 7 per scope discipline.

### CF4: Opus 4.7 tokenizer / quota-doubling hypothesis — **INVALIDATED at probe**

Both angles of the hypothesis dissolved at session 7 probe:

- **Tokenizer-recalibration angle**: invalidated (Probe 2). Compaction trigger is utilization-percentage-based, not token-count-based. The 80% threshold is independent of tokenizer change.
- **Quota-doubling angle (added at session-7 amendment)**: invalidated (Probe 3). No hardcoded token-budget-per-window constant exists. Orchestrator's percentage-based architecture is transparent to Anthropic's 5-hour quota doubling.

Behavioral-shift cross-reference ("fewer tool calls, using reasoning more") remains as a possible CF1-investigation thread for session 8 disposition. Engineer's observed ~60-70s tick cadence at session 6 may partially reflect this default. Not a primary CF1 explanation but worth surfacing alongside the PM-behavior hypotheses above.

**CF4 closes as invalidated.** Documented for audit-chain completeness; do not investigate at session 8.

### CF5 (new, session 7): Pin 12 firing pattern observation — **route to M6 closeout pin-frequency observation**

Session 7 added **two Pin 12 firings**:

- **#6**: CF4 "addendum overdue" framing carried forward from stale session memory without repo verification. Pre-existing 250-line addendum at `docs/kickoff/opus-4-7-effort-and-thinking-addendum.md` (2026-05-06) was the actual artifact.
- **#7**: Session 7 framing drafted without checking Anthropic's recent quota landscape shift (~2026-05-06 5-hour quota doubling). Mike caught at his second prompt.

Total Pin 12 firings across M5-acceptance arc: **7** (5 from M5-acceptance + 1 session-5 baseline-eyeball + 1 from session 7 firing #6 + 1 from session 7 firing #7 = 7; the session-6 845-vs-843 reconciliation was a different shape per Pin 13 symmetric-widening, not a Pin 12 firing).

Pattern: every probe-discoverable claim drafted without probe-verification has produced a Pin 12 firing. **The methodology amendment should generalize Pin 12**: claims about repo state, codebase state, or external-system state (Anthropic quotas, model capabilities) require probe-verification at authoring time, not session-memory recall.

Route to M6 closeout pin set authoring as Pin 12 generalization candidate. Don't author pin commit during session 7 per scope discipline.

## Session 8 forward sequence

**Primary path: β re-execution end-to-end with CF1 investigation in parallel.**

1. **Operator-driven daemon spinup** in interactive PowerShell per refreshed Section 2 of β-readiness doc.
2. **Probe-then-act sequence** through Section 2 checklist (env var; token state; daemon start; MCP reachability; substrate work item selection).
3. **Mike opens interactive PM Claude Code session**, submits substrate work item.
4. **β execution begins.** Code session monitors via SQLite audit_log + dispatch table polling, ready to surface anomalies.
5. **Watch for CF1 manifestation**: PM rc=0-no-compaction during active PM-Engineer coordination. Capture stderr.log if/when it fires.
6. **If CF1 fires during β**: capture stderr/stdout sample, characterize the pattern (timing, frequency, conditions); back-burner architectural fix vs surface as deferred investigation per Mike's call.
7. **β findings capture**: author `docs/working/M5-acceptance-beta-findings.md` per kickoff-doc β findings shape (commit 4.5 artifact when β runs operationally).
8. **Session 8 close**: if β succeeds end-to-end without architectural-fix-requiring findings, MVD threshold reached. If β surfaces blocking findings, session 9 disposition for fix work.

**Alternate path: pre-β PM-behavior characterization at code level** (if Mike prefers extra probe before β):
- Read PM's prompt structure for continuous-mode entry path
- Trace Claude Code subprocess default behavior on completion of prompt
- Pre-author hypothesis disposition before β execution
- Then β execution at session 8b

Recommend primary path (β first, characterize CF1 if it fires). Reasons:
- β empirically tests CF1 race-condition capacity under active coordination
- Pre-β code-only characterization is read-only/speculative; β data informs disposition better
- Audit-cleanness pattern Mike favored at original Option B already preserved (β runs against stable session-7-close state)

## Dogfooding-readiness state

Sessions remaining to β-re-execution-ready:

- Session 4 (Engineer fix authoring + Pin 9 firing) — **CLOSED**
- Session 5 (auto-seeding fix + Windows shutdown BACKLOG) — **CLOSED**
- Session 6 (Engineer fix runtime exercise + commit + 4 CF surface) — **CLOSED**
- Session 7 (pre-β preparation: β-readiness refresh + CF1 probe + CF4 invalidation + Pin 12 firings #6 + #7) — **CLOSED at this doc** ✓
- Session 8 (β re-execution end-to-end + CF1 manifestation watch)
- If β surfaces blocking findings: session 9+ for fix work

Updated MVD estimate: **2-4 sessions** (session 8 β execution + 0-3 sessions for any blocking findings).

- If β succeeds clean (no CF1 manifestation, no new I-NR): **MVD at session 8 close** — best case
- If CF1 fires during β but doesn't block end-to-end completion: MVD at session 8 close + CF1 fix at session 9 (post-MVD)
- If CF1 fires AND blocks end-to-end (e.g., race condition manifests + corrupts coordination state): **MVD slips to session 9 or later** for CF1 fix before β can re-run
- If new I-NR surfaces unrelated to CF1: 1-2 sessions per new I-NR fix

CF2 cost investigation (if needed): can run in parallel or post-MVD.

## How to resume next session

Open session 8 with one of:

- **β execution primary path**: "Session 8 open — β re-execution end-to-end. Read `session-7-close-2026-05-12.md` as restart anchor. Probe-verify state at 75a2ad8 + session-7 doc-only commit. Walk Section 2 checklist; daemon spinup in interactive PowerShell; Mike opens PM session; submits substrate work item; β begins. Code session monitors via SQLite polling; captures CF1 if it fires."
- **Pre-β code characterization (alternate)**: "Session 8 open — pre-β PM-behavior characterization at code level per CF1 alternate path. Read PM continuous-mode prompt entry; trace Claude Code subprocess defaults on prompt-completion; pre-author hypothesis disposition. β execution at session 8b."

Probe-verify at session-8 open before action:
- HEAD matches session-7-close doc commit
- Working tree clean
- Threshold rule 2 of 3 (unchanged)
- β-readiness doc refresh reflects current state (session 7 banner present; Section 4 shows 2 of 3)
- CF1-CF5 statuses readable in this doc

## Session 7 arc summary

Probe-verify clean (HEAD 75a2ad8) → Option C' accepted per session-7-open framing → quota-hardcoding probe clean (CF4 quota-angle invalidated) → β-readiness refresh +123/-32 across 5 sections (banner + β-row state + Section 2 contexts + Section 4 threshold + Section 5 postscript) → CF1 code probe at `supervisor_base.py:738-928` (categorization (b) confirmed; designed failure-mode-detection path) → session 7 close handoff doc authored (this file) → Step 4 doc-only commit pending.

Designed disposition shape: zero production-code changes; doc-only artifacts; CF4 closed at probe; CF1 elevated with code-level anchor; β execution sequence finalized for session 8. CC4 pin discipline operating cleanly across session 7; Pin 12 firings #6 + #7 cataloged. Threshold rule unchanged at **2 of 3** throughout.
