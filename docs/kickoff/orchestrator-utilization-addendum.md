# Addendum: Aggressive subscription utilization

## Premium-first revision (2026-04-30)

Mike upgraded to Claude Opus 20x premium. The cost model inverts: premium is the primary capacity, standard Pro/Max subscription becomes overflow. The behavioral specifications below still apply — the *aggressive utilization* commitment is unchanged — but the bucket targeting changes:

- The 70–90% utilization target applies to **premium specifically**, not to total subscription capacity. Standard subscription utilization is uncapped (overflow indicator, no target).
- Where the original addendum says "subscription window," read it as "premium window" first; standard window is the fallback when premium is in `conserve` or `rate_limited`.
- "Idle subscription capacity is wasted capacity" becomes "**idle premium capacity is wasted capacity**." Standard subscription idle is fine — it's the relief valve, not the primary fuel.
- Two reasons premium is primary: (a) it doesn't compound across windows the way standard subscription does — unused premium time is gone; (b) reserving premium for "important" work creates a hoarding pattern where premium goes unused while standard windows hit rate limits.
- Rate-limit semantics: premium `rate_limited` is `info`-urgency (uncommon under 20x, diagnostic); standard `rate_limited` after premium fallback is `question`-urgency (both buckets under pressure is meaningful signal); both depleted is `urgent`.

The original addendum content is preserved as-authored below — its behavioral specifications still apply. Read it through the premium-first lens: PM continuous mode dispatches against premium first; Engineer reads `get_window_state(bucket="premium")` as primary; the dispatch loop continues on standard when premium throttles.

See `02-architecture.md` § Premium-first utilization model and `06-mcp-tools-phase-one.md` § `get_window_state` for the per-bucket schema. Migration `0006_buckets.sql` (M5) adds `api_usage.bucket` to track which bucket each call landed on.

---

## Original addendum (pre-2026-04-30)

The current kickoff is defensively scoped on rate-limit handling — graceful degradation, no silent retry, surface to Mike. That's correct for *not breaking* but insufficient for what Mike actually wants: full utilization of the Claude Pro/Max subscription window so the Orchestrator delivers meaningful Meridian work continuously, not just when Mike is at the keyboard.

This addendum amends the kickoff to make aggressive subscription utilization a first-class architectural commitment. Apply these changes to the affected documents before M1 progresses past basic scaffolding.

---

## Core principle

Idle subscription capacity is wasted capacity. PM running autonomously during Mike's work hours should be dispatching as much work to Engineer as the rolling 5-hour window allows, with Engineer dispatching to Implementer/Tester as aggressively as their windows allow.

Subscription rate limits are *the binding constraint*, not Mike's review cadence, not arbitrary pacing.

The Orchestrator is not "polite about budget" — it's "deliberate about budget."

---

## Behavioral specifications

### PM continuous mode

PM operates in two modes: **interactive** (Mike is in an interactive Claude Code session with PM, asking questions or directing work) and **continuous** (Mike is not present, PM is autonomously advancing the work queue).

Both modes are first-class. Continuous mode is not a degraded version of interactive mode — it has its own behavior pattern.

In continuous mode, PM:

- Reads the dashboard summary and inbound replies on session start
- Reviews the open task queue across all priorities
- Identifies work that can proceed without Mike's input (mechanical decisions, foundational decisions PM can make autonomously, downgrade-eligible items, work that consumes prior approvals)
- Dispatches to Engineer continuously — not one task at a time, but as many parallel-eligible tasks as Engineer can absorb without saturating
- Consumes Engineer's results, advances the queue, dispatches the next batch
- Periodically writes session-progress notes to the queue (every ~30 minutes of activity) so Mike can read context if he checks Telegram
- Sends `info` Telegram notifications at configurable cadence (default: hourly batched, max 4 messages per send) summarizing recent activity
- Pauses on `question` or `urgent` items that genuinely require Mike — those go to Telegram immediately, dependent work parks
- Continues working on independent threads while waiting on Mike's reply to one question

PM does NOT:

- Wait for Mike to "approve" continuing between phases
- Stop work because the previous phase shipped successfully
- Conserve subscription tokens "for later" when Mike might want to chat
- Fall idle when the queue has work that doesn't need Mike's input

PM is told explicitly in its system prompt: *"Continuous mode is your default operating state when Mike is not interactive. Idle PM time is subscription tokens being thrown away. Mike has explicitly authorized aggressive utilization."*

### Window-aware burn

The Orchestrator tracks the rolling 5-hour window state per agent (PM has its own window from Mike's subscription; Engineer/Implementer/Tester each have their own when invoked via subprocess Claude Code).

PM checks `get_burn_rate` and `get_token_usage` regularly to inform planning. The behavior:

- **Window > 50% remaining, > 2 hours to reset:** Normal pace. Dispatch work as needed, don't artificially throttle.
- **Window > 50% remaining, < 1 hour to reset:** *Upgrade pace.* Consider promoting lower-priority work into the current window. Better to burn the tokens on real work than waste them at reset.
- **Window 20-50% remaining, > 1 hour to reset:** Continue at normal pace, but defer optional polish-tier work to the next window.
- **Window < 20% remaining:** Conservation mode. Complete in-flight work, don't dispatch new high-token tasks (large refactors, broad codebase reads). Continue with low-token work (queue updates, decision logging).
- **Rate limit hit:** Per existing kickoff — log audit, surface to Mike via question-urgency, don't silently retry. Resume aggressively at window reset.

The transition into "upgrade pace" is the key behavior. PM should *actively look for work to promote* into windows that have unused capacity, not passively wait for reset.

### Cross-window queue management

Tasks in the queue have priority (1=critical, 2=high, 3=normal, 4=low). PM's continuous-mode planning runs roughly:

1. Highest-priority unblocked tasks dispatch first.
2. Once high-priority is dispatched, mid-priority tasks fill remaining capacity.
3. Low-priority tasks fill any remaining capacity within the current window.
4. Tasks not dispatchable in the current window queue for the next window with their priority preserved.
5. At window reset, the queue resumes with highest-priority remaining work.

PM's planning uses estimated token cost per task (based on complexity rating) to decide what fits in remaining window capacity. Stub the estimator at M5 with simple heuristics; refine in phase 2 when real data accumulates.

### Engineer parallel dispatch

Engineer is not single-threaded. When PM submits multiple tasks for Engineer to coordinate, Engineer dispatches Implementer/Tester subagents in parallel where the work has no dependencies.

Specifically:

- Two phases that touch different files in the substrate codebase can dispatch simultaneously — Implementer A works on file X, Implementer B works on file Y. Tester A and Tester B verify in parallel.
- Two phases that touch the same files must serialize — order them, dispatch second after first completes.
- Engineer maintains a dispatch graph: which phases depend on which, which can parallelize.
- The bridge architecture (M3 design) must support multiple concurrent subagent invocations, not just one-at-a-time.

This means Option A (subprocess Claude Code) needs to handle launching N parallel subprocess instances. Each gets its own working directory checkout, runs against its own git branch, merges back when complete. The merge logic is non-trivial — covered in M3 bridge design.

### Utilization reporting

End-of-session report includes a utilization section:

```
Subscription utilization (5-hour windows during this session):
- Window 1 (08:00-13:00 ET): 78% used, 22% unused → 4 phases shipped
- Window 2 (13:00-18:00 ET): 91% used, 9% unused → 5 phases shipped, 1 question to Mike
- Window 3 (18:00-23:00 ET): 34% used, 66% unused → 2 phases shipped (Mike interactive, PM throttled to share window)

Total session utilization: 68% of available capacity
Reason for unused capacity: Mike's interactive sessions in window 3 (expected behavior)
```

Honest reporting: PM tells Mike how much capacity was used, why any was unused, whether the Orchestrator is actually delivering on the "burn the subscription" promise.

If utilization drops below 60% across a session for reasons other than Mike's interactive presence, PM surfaces that as a `question` urgency: "I'm not utilizing the subscription as expected. Possible causes: queue starved (no work to dispatch), Engineer stalled (rate-limit on substrate codebase work), continuous-mode logic bug. Investigate?"

### Mike's interactive sessions take precedence

When Mike is actively conversing with PM in his interactive Claude Code session, that conversation has priority over continuous-mode work. The reason: Mike's conversation is using the same subscription window PM is burning autonomously. PM should detect interactive mode (recent message exchange with Mike) and throttle continuous-mode dispatches to leave headroom for the conversation.

Specifically:

- When Mike sends a message to PM, PM enters interactive mode for the next 30 minutes (sliding window — every Mike message resets the timer).
- In interactive mode, PM completes any in-flight Engineer dispatches but does not initiate new high-token dispatches.
- When 30 minutes pass with no Mike message, PM returns to continuous mode and resumes aggressive dispatch.

This isn't conservation — it's making sure Mike's conversation feels responsive instead of stalling because the subscription window is saturated by background work.

---

## Schema additions (small)

Add to `05-data-model.md`:

```sql
-- Tracks PM's mode transitions for observability
CREATE TABLE pm_mode_log (
    id TEXT PRIMARY KEY,
    timestamp TEXT NOT NULL DEFAULT (datetime('now')),
    mode TEXT NOT NULL,  -- 'interactive' | 'continuous' | 'paused_blocked' | 'paused_question'
    reason TEXT,
    triggered_by TEXT  -- 'mike_message' | 'window_reset' | 'queue_empty' | 'rate_limit' | 'manual'
);

CREATE INDEX idx_pm_mode_timestamp ON pm_mode_log(timestamp);
```

This is observability data, not load-bearing state. Mike can query it to understand how PM has been operating.

Add to `tasks` table:

```sql
ALTER TABLE tasks ADD COLUMN estimated_tokens INTEGER;  -- PM's estimate for window planning
ALTER TABLE tasks ADD COLUMN actual_tokens INTEGER;    -- Set by Engineer on completion
```

These let PM's planner make window-aware decisions and refine estimates over time.

---

## MCP tool additions (PM-only)

Add to `06-mcp-tools-phase-one.md`:

```python
class GetWindowStateInput(BaseModel):
    agent_id: Optional[str] = None  # default: current agent
    
class GetWindowStateOutput(BaseModel):
    window_start: str
    window_end: str
    tokens_used: int
    tokens_estimated_remaining: int
    percentage_used: float
    minutes_to_reset: int
    pace_recommendation: str  # 'normal' | 'upgrade' | 'conserve' | 'rate_limited'
```

`get_window_state` is the planning primitive PM uses for window-aware burn decisions.

```python
class SetPmModeInput(BaseModel):
    mode: str  # 'continuous' | 'interactive' | 'paused'
    reason: Optional[str] = None
    
class SetPmModeOutput(BaseModel):
    mode: str
    transition_logged: bool
```

`set_pm_mode` is how PM logs its own mode transitions for observability.

```python
class GetUtilizationReportInput(BaseModel):
    session_start: Optional[str] = None  # default: 24h ago
    
class GetUtilizationReportOutput(BaseModel):
    windows: List[dict]  # per-window breakdown
    total_utilization_pct: float
    unused_reasons: List[str]
```

`get_utilization_report` is consumed by PM at end-of-session to populate the utilization section of the session report.

---

## PM system prompt additions

Append to `07-agent-configs-phase-one.md` PM YAML system prompt under "Your operational pattern":

```
## Continuous mode (default when Mike is not interactive)

Idle PM time is subscription tokens being thrown away. Mike has explicitly authorized aggressive utilization. When you are not in active conversation with Mike, you should be advancing the work queue continuously, not waiting.

In continuous mode:

- Read inbound Telegram replies on session start. Consume any waiting answers.
- Read the open task queue. Identify work that can proceed without Mike's input — mechanical decisions, foundational decisions you can make autonomously, downgrade-eligible items, items waiting on prior approvals you already have.
- Dispatch to Engineer continuously. Don't wait for one task to finish before queuing the next. Engineer can handle parallel dispatch where dependencies allow.
- Use `get_window_state` to inform pacing. Normal pace when window has capacity, upgrade pace when window has unused capacity nearing reset, conservation mode when window is depleted.
- When you complete a phase that ships substrate work, log to queue, send `info` Telegram update at the configured batch cadence, and immediately plan the next phase. Don't wait for Mike to "approve continuing."
- When you encounter a `question` or `urgent` item that requires Mike, send the Telegram immediately and continue working on independent threads. Don't park the entire chain on one open question if other work can proceed.

When Mike sends a message in his interactive Claude Code session, transition to interactive mode immediately. In interactive mode, throttle continuous dispatch to leave window headroom for the conversation. Return to continuous mode 30 minutes after Mike's last message.

## Subscription utilization expectations

Mike's Pro/Max subscription is the binding resource constraint on Orchestrator throughput. Your job in continuous mode is to use it deliberately — not to "save" capacity for hypothetical future use, but to convert it into shipped work.

Targets for honest reporting:

- During Mike's work hours (8 AM - 6 PM ET, weekdays), continuous-mode utilization should run 70-90% of available capacity.
- Lower utilization is acceptable when (a) Mike is interactive heavily, (b) the queue has genuinely no dispatchable work, (c) Engineer is rate-limited on substrate codebase, or (d) you're paused on a legitimate `question` to Mike with no other independent work.
- Lower utilization for any other reason should be flagged as a `question` urgency: "I'm under-utilizing the subscription. Possible reasons: [list]. Investigate?"

The end-of-session report includes a utilization section. Be honest about what was used and why anything wasn't.

## What this means in practice

You are not polite about budget. You are deliberate about budget. You will dispatch more work in a day of continuous mode than Mike used to dispatch in a week of evening sessions, because you are using the full window every hour Mike's work day permits.

This is the design intent. Don't second-guess it.
```

---

## Engineer system prompt additions (incorporated when Engineer prompt is drafted at M3)

Engineer's system prompt (drafted at M3, not now) must include:

- Engineer accepts parallel dispatch from PM. When PM submits multiple tasks, Engineer evaluates dispatch graph and runs non-conflicting work in parallel.
- Engineer maintains its own window-state awareness via `get_window_state`. When Engineer's subagent invocations would saturate Engineer's window, Engineer queues rather than failing.
- Engineer reports utilization back to PM. PM aggregates Engineer + own utilization for end-of-session report.
- Engineer is not artificially throttled by Mike's review cadence. Engineer ships completed phases to PM as they finish; PM batches updates to Mike.

Add this to `11-engineer-bridge.md` as a constraint the M3 bridge design must satisfy: parallel subprocess invocation, isolated working directories per concurrent dispatch, merge logic for landing parallel branches.

---

## M-milestone integration

This addendum touches multiple milestones:

- **M3 (agent configs + bridge design)** — Engineer bridge must support parallel dispatch. PM YAML gains continuous-mode and utilization sections of system prompt.
- **M4 (task management)** — `estimated_tokens` and `actual_tokens` columns added. Task priority gets first-class use in PM's planner.
- **M5 (budget tools)** — `get_window_state` tool added. Burn rate calculation refined for pace-recommendation output.
- **M6 (knowledge, routing, notifications, dashboard)** — `get_utilization_report` tool added. Dashboard summary includes utilization stats. PM mode log is queryable.
- **M7 (deployment, smoke test)** — Smoke test includes a continuous-mode scenario: PM dispatches 3 parallel phases, Engineer parallelizes Implementer/Tester, all complete, utilization is reported correctly.

Phase 1 timeline impact: roughly +3-5 days for the parallel dispatch logic in M3-M4 and the window-aware planning in M5. Tradeoff is meaningful: subscription utilization roughly doubles compared to single-threaded sequential dispatch.

---

## What this enables for Mike

With this addendum applied, the Orchestrator running during Mike's work day produces:

- Continuous progress on substrate closeout work without Mike at the keyboard
- Telegram check-ins at configurable cadence (default hourly) summarizing progress
- Rapid response to Mike's `question` replies because PM is actively in continuous mode
- Honest utilization reporting so Mike can see whether the autonomous chain is delivering on its promise

Realistic expectation: substrate closeout work that took 1-2 weeks of Mike's evening sessions can ship in 5-10 working days of continuous-mode operation. The Orchestrator phase 1 build itself takes 1.5-3 weeks of evening sessions. Total to demo-shareable: 3-5 weeks from Orchestrator kickoff approval, not 6-8 weeks.

If utilization runs lower than expected during the substrate closeout phase, PM surfaces it as a `question` and Mike can investigate or accept.

---

## Apply

Update the affected documents per the changes above, then present the diff for Mike's review. After Mike approves, M1 may proceed (or continue if M1 has already started — this addendum touches M3-M7, M1-M2 are unaffected).

End of addendum.
