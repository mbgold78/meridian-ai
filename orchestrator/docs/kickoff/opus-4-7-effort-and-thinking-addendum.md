# Addendum: Opus 4.7 effort + adaptive-thinking guidance

**Established:** 2026-05-06 at M5-continuous-pm-driver commit 6 (per
F1.3 hybrid-amendment-doc disposition; F6.6 plumbing dispositions
Q1-Q9; F1.4 verification of Anthropic doc surface).

**Scope:** Opus 4.7-specific guidance for daemon-launched agent
subprocesses — the per-role `effort` field assignment, adaptive
thinking's daemon-vs-subprocess boundary, and the
operator-agency framing for the field's full Anthropic-supported
value surface.

**Out of scope:** subscription pacing — that's
`orchestrator-utilization-addendum.md`. Future Opus-version-specific
guidance (Opus 4.8, Opus 5.0, etc.) lands in dedicated addenda
following this file's pattern; folding model-version-specific
content into the utilization addendum was rejected at F7.2 Q1
disposition (scope blurring + discoverability + cross-reference
durability across Opus versions).

**Cross-references in code/config:** `config/agents/{pm,engineer,implementer,tester}.yaml`
effort-field comments; `src/meridian_orchestrator/agents/config.py`'s
`Effort` Literal docstring; `docs/kickoff/02-architecture.md` §
agent-launch architecture; `docs/kickoff/07-agent-configs-phase-one.md`
§ agent yaml schema; `docs/kickoff/11-engineer-bridge.md` §
launcher.

---

## Per-role effort assignments

| Role | Effort | Deployment surface |
|---|---|---|
| PM | `xhigh` | Daemon-launched PM-continuous subprocess (PMSupervisor) |
| Engineer | `xhigh` | Daemon-launched Engineer subprocess (EngineerSupervisor) |
| Implementer | `xhigh` | Daemon-launched per-dispatch subprocess (BridgeDispatcher) |
| Tester | `high` | Daemon-launched per-dispatch subprocess (BridgeDispatcher) |

These values land in `config/agents/<role>.yaml` as a required
`effort` field per F6.2 Q2 disposition (hard-fail on missing).
They flow through `AgentConfig.effort` (Pydantic Literal validated
at yaml-load time) into `DispatchLaunchContext.effort` /
`SupervisorLaunchContext.effort`, then into the
`claude --effort <level>` subprocess CLI flag at the launcher
layer.

### Per-role rationale

**PM (xhigh).** PM's continuous-mode planning + dispatch
coordination is an agentic loop: per-iteration decisions cover
queue inspection, work-unit selection, dispatch routing, Mike-presence
detection, and compaction-prep timing. Anthropic's effort guidance
names "agentic loops" as a primary `xhigh` use case;
intelligence-sensitivity is high because each tick's decision
shapes the next several iterations.

**Engineer (xhigh).** Engineer's subprocess composes Implementer /
Tester dispatch specs from PM-submitted task descriptions, manages
the dispatch lifecycle (timeouts, retries, decline propagation),
and resolves merge-conflict cleanup. Spec composition is
intelligence-sensitive (the Implementer's prompt determines code
quality); dispatch-coordination is loop-shaped. xhigh matches PM's
agentic-loop framing.

**Implementer (xhigh).** Code-change work is the canonical
"intelligence-sensitive" case per Anthropic's effort guidance
("minimum high for intelligence-sensitive"). xhigh is chosen
specifically because Implementer dispatches are agentic-tool
work — substantive code production with iterative compile/test
cycles, not single-shot completions. The xhigh upgrade from the
"minimum high" baseline reflects Implementer's load-bearing role
in delivering substrate-product changes.

**Tester (high).** Tester's work shape is narrower than
Implementer's — verifying assertion specs against produced
artifacts. Less intelligence-sensitive than producing the code
in the first place; verification can lean on structured
comparison + targeted assertion-check rather than open-ended
synthesis. `high` is the one-tier-down assignment; cost-vs-quality
trade-off favors high here without sacrificing assertion fidelity.

---

## Operator-agency framing

The `effort` yaml field is the operator's tuning surface. The
`Effort` Literal admits **all five** Anthropic-supported values
(`low`, `medium`, `high`, `xhigh`, `max`) per F6.6.B Q1
disposition (corrected reasoning per F6.5.1 framing-precedent
reset). The Phase 1 default assignments above are starting points,
not constraints.

Restricting the Literal to Phase-1-actual `{high, xhigh}` would
freeze operator agency without architectural reason — operator may
want `medium` for cost-conscious quality-vs-cost exploration on
Tester (does Tester at medium produce acceptably-thorough test
reports at materially-lower cost?), may want `max` for high-stakes
Engineer dispatches on hard problems (one-off override for a
load-bearing decision). Full 5-value Literal admits the full
Anthropic-supported tuning surface; the schema imposes no
additional cost vs the narrower Literal.

The yaml-load Pydantic Literal validation rejects typo /
unsupported values at config-load time, not at subprocess args
time — the load-bearing property is not "lock the values to
Phase-1-actual" but "validate at config-load not at runtime." Both
properties hold under the full 5-value Literal.

---

## Adaptive thinking — daemon vs subprocess boundary

Adaptive thinking on Opus 4.7 has different behavior at the raw
Anthropic API surface vs the Claude Code subprocess surface. F1.4
verification (2026-05-03, against current Anthropic + Claude Code
docs) established the distinction.

**At the raw Anthropic API:**
- Adaptive thinking is NOT implicit. Default behavior with no
  `thinking` parameter is **thinking off**.
- To engage adaptive thinking, the request must explicitly set
  `thinking: {type: "adaptive"}`.
- Manual `thinking: {type: "enabled"}` is REJECTED with a 400
  error on Opus 4.7 — adaptive is the only supported thinking
  mode for this model.

**At the Claude Code subprocess:**
- Claude Code v2.1.117+ on Opus 4.7 sets adaptive thinking +
  xhigh effort as defaults transparently.
- From the Orchestrator daemon's perspective, calling
  `claude -p` with no thinking args produces a subprocess that
  applies the right defaults.
- "Adaptive thinking is implicit" reads correctly **at the
  subprocess boundary**, but inaccurately at the raw API
  boundary.

**Implication for Phase 1 daemon plumbing:** Phase 1 has no
daemon-side raw API client. The daemon launches Claude Code
subprocesses via `bridge/dispatcher.py:_default_launcher` and
`bridge/supervisor_base.py:_default_launcher`; these invoke
`claude --effort <level> -p <prompt>`. Claude Code handles
adaptive thinking transparently on Opus 4.7. **No daemon-side
thinking-mode plumbing is required.**

---

## Wrapper-doesn't-exist-in-Phase-1 finding

F1.4 surveyed `src/` for any Anthropic API client / thinking-mode
handling / effort handling code beyond the existing `claude -p`
subprocess invocation. Result: zero Anthropic SDK imports, zero
`client.messages.create` calls, zero thinking-mode handling.

**Phase 1 Orchestrator has no Anthropic API client.** The "no-op
for legacy thinking-mode handling" guidance from external
documentation applies to a layer that doesn't exist in this
codebase.

**Phase 2+ guidance** (deferred, captured here for future-Claude):
if/when an Orchestrator-side API client emerges (e.g., for
daemon-side request inspection, prompt caching, batch processing,
or any direct Messages API integration), legacy
`thinking: {type: "enabled"}` handling on 4.7-bound requests must
be silently stripped (verified: API rejects with 400). The wrapper
should detect 4.7-bound requests and either omit the `thinking`
parameter entirely (admit Anthropic's default-off) or set
`thinking: {type: "adaptive"}` explicitly.

For Phase 1: moot.

---

## Mike's interactive PM session

The yaml `effort` field configures **daemon-launched** subprocesses
only:
- PMSupervisor's PM-continuous subprocess
- EngineerSupervisor's Engineer subprocess
- BridgeDispatcher's Implementer / Tester per-dispatch subprocesses

Mike's interactive PM Claude Code session is **not daemon-launched**.
The daemon has no plumbing path into Mike's local Claude Code
session — there's no `--effort` flag the daemon could pass to a
subprocess Mike is running on his desktop.

**Mike's interactive PM is governed by client-side defaults:**
Claude Code v2.1.117+ defaults to `xhigh` on Opus 4.7. Mike's
local CC client applies this default automatically; the yaml
`effort` field has no effect on it.

**Future-Claude reading the yaml:** the `effort: xhigh` line in
`config/agents/pm.yaml` does NOT govern Mike's interactive PM
session. It governs the daemon-launched PM-continuous subprocess
that PMSupervisor spawns when `enable_pm_supervisor=True`. If Mike
wants to tune his interactive session's effort, he uses the
Claude Code CLI's `--effort` flag, env var
`CLAUDE_CODE_EFFORT_LEVEL`, settings field `effortLevel`, or
subagent frontmatter — not the yaml field.

---

## CLI flag verification

**Verified 2026-05-05 during M5-continuous-pm-driver commit 5
draft via `claude --help`:**

> `--effort <level>                                  Effort level for the current session (low, medium, high, xhigh, max)`

The CLI flag literal is `--effort <level>` (space-separated, not
`=`-glued); accepts the five values that match the `Effort`
Literal in `agents/config.py`.

**Re-verification trigger:** when the Anthropic doc surface
evolves (new effort tier; flag literal change; deprecation), this
file's verification source is the canonical re-verification entry
point. Mirror the structure: re-run `claude --help`, update the
flag literal + value list here, update the `Effort` Literal in
`agents/config.py` with a migration to align the closed enum if
the value list shifts.

---

## Anthropic guidance citation

Source documents (verified at F1.4, 2026-05-03; re-verified for
this addendum's content 2026-05-06):

- **Effort levels:** [platform.claude.com/docs/en/build-with-claude/effort](https://platform.claude.com/docs/en/build-with-claude/effort) — API parameter at `output_config.effort`; values `low/medium/high/xhigh/max` on Opus 4.7. API default is `high`; xhigh requires explicit set. Generally available, no beta header.
- **Adaptive thinking:** [platform.claude.com/docs/en/build-with-claude/adaptive-thinking](https://platform.claude.com/docs/en/build-with-claude/adaptive-thinking) — Opus 4.7 supports only adaptive thinking mode; manual `thinking: {type: "enabled"}` rejected with 400; adaptive engaged via explicit `thinking: {type: "adaptive"}` (off by default at API).
- **Claude Code model config:** [code.claude.com/docs/en/model-config](https://code.claude.com/docs/en/model-config) — CC v2.1.117+ defaults to `xhigh` on Opus 4.7; flag `--effort <level>` for single-session override; env `CLAUDE_CODE_EFFORT_LEVEL`; settings field `effortLevel`; subagent frontmatter `effort`. Adaptive reasoning always on for Opus 4.7; fixed-thinking-budget mode and `CLAUDE_CODE_DISABLE_ADAPTIVE_THINKING` do not apply.

---

## Phase 2 reopening conditions

This addendum scopes to Phase 1 (daemon launches CC subprocesses;
no daemon-side API client). Phase 2 reopens this guidance if:

- A daemon-side Anthropic API client emerges (the
  wrapper-doesn't-exist finding above transitions from "moot" to
  load-bearing).
- Task budgets become supported on Claude Code or Cowork surfaces
  (currently unsupported per F2.0 verification; deferred from
  M5-continuous-pm-driver per Q5(α) disposition).
- A new Opus version (4.8, 5.0, etc.) ships with different effort
  / thinking semantics — likely lands in a sibling addendum
  (`opus-4-8-effort-and-thinking-addendum.md`, etc.) following
  this file's pattern, with cross-references between them and
  potentially a deprecation note here.
