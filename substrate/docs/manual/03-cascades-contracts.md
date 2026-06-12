## Cascades and Contracts

Meridian Substrate gives a holding company two complementary governance instruments. A **cascade** moves a leader's critique *down* an accountability chain, one named owner at a time, and carries every answer back up. A **coordination contract** binds two or more parties *across* the portfolio to explicit commitments, with the substrate monitoring compliance.

Both instruments share one binding principle: **a documented "no" is a legitimate outcome, never an error.** A subsidiary that declines a critique with reasoning, or a party that declines a contract by citing a pre-agreed trigger, has completed the process correctly. Nothing in the product — not a status code, not a color, not a word — treats a decline as a failure. The reddish "destructive" accent you will see next to declines means *this needs leadership attention*, not *something broke*.

---

## Part 1 — Cascades

### What a cascade is

A cascade starts when a leader (the **originator** — typically the Operating Partner) issues a critique with explicit **acceptance criteria**: what a satisfactory answer looks like, including, where appropriate, the conditions under which a documented decline is acceptable. The critique is routed to a named first owner (a subsidiary COO, for example). That owner reads it and does one of three things: passes it down with their own refinement, takes the work on themselves, or declines with reasoning. Each handoff adds a **level** to the chain — level 0 is the originator, level 1 the first owner, and so on.

Two things make this different from forwarding an email:

1. **The original critique is durable.** The text the originator wrote, and the acceptance criteria, are frozen at issuance and visible unmodified at every level. Each level adds its own refinement *alongside* the original — refinements never replace or dilute it.
2. **The chain is the audit trail.** Every action — issue, cascade, absorb, decline, recascade, resolve — is recorded permanently as it happens, with the actor, the timestamp, and the text they wrote. Nothing in this log can be edited or deleted afterward, by anyone.

### Where you see cascades

- **Operating Partner dashboard** (`/portfolio/dashboard`) — the **"Cascades you've issued"** grid shows every in-flight cascade as a card: the first sentence of the critique, where the chain currently sits ("Currently at L2 · 3 level chain"), and an attention-signal chip. Cards are ordered by attention: decline-pending first, then overdue, then escalating by most recent activity. Each card's **Inspect cascade** link opens the detail page. In the demo workspace this grid is live engine data (the header dot's tooltip says which parts of the dashboard are live versus illustrative).
- **Cascade detail page** (`/portfolio/cascade/<id>`) — the full record. See [The cascade detail page](#the-cascade-detail-page) below. Screenshot: `../acceptance/final/06-cascade-detail-decline-pending.png`.

### Level verbs — what each owner can do

When a cascade arrives at your level you are the single accountable owner of the next move. You act exactly once.

| Verb | Who can use it | What it does |
|---|---|---|
| **Cascade** | The owner currently "looking" at the critique | Passes the work down: you write your own refined critique (the original stays attached) and name the next owner. The chain grows one level. |
| **Absorb** | The owner currently "looking" | You take the work yourself — downward propagation stops. You document what you will do (a refined critique is required). The cascade stays open until the originator reviews and resolves it. |
| **Decline** | The owner currently "looking" | You decline with documented reasoning (required). The cascade does not end — a *pending decline* now sits with the level above you, and the originator is alerted. |

A decline is answered by the level **above** the declining level, which has exactly three options:

| Decline-response verb | What it does |
|---|---|
| **Endorse the decline** (decline) | You agree with your subordinate's decline and add your own reasoning. The pending decline moves up one level — a run of endorsed declines can climb all the way to the originator. |
| **Recascade** | You disagree: you rewrite the critique with new framing and send it back down. The level below is reactivated as a fresh round, possibly with a different owner. The earlier decline stays in the permanent record. |
| **Absorb over the decline** | You take the work yourself rather than push it back down. The decline below you stays in history as answered, no longer pending. |

The bottom of the chain — the level whose state currently determines what happens next — is called the **frontier** in API responses ("Currently at L2" on the dashboard cards).

### Cascade and level state vocabulary

**Cascade (whole-chain) states:**

| State | Wire value | Meaning |
|---|---|---|
| In-flight | `in-flight` | The cascade is open; someone in the chain owes an action, or the originator owes a resolution. |
| Resolved — looks good | `resolved-looks-good` | The originator reviewed the outcome and closed the cascade satisfied. Terminal. |
| Resolved — declined | `resolved-declined` | The originator formally accepted a documented decline and closed the cascade. **A peer terminal outcome, equal in standing to "looks good."** Terminal. |

Terminal means terminal: no action of any kind is accepted afterward. If the issue resurfaces, a new cascade is issued (the new record can reference its predecessor).

**Level states** (the pill on each level card):

| State | Pill | Meaning |
|---|---|---|
| Looking | `looking` (amber) | This owner has the critique and has not yet acted. The detail page shows "Awaiting response since *date* — no decision yet." |
| Cascaded | `cascaded` (blue) | This level refined the critique and passed it down. |
| Absorbed | `absorbed` (green) | This level took the work; the chain stops here. |
| Declined | `declined` (red accent) | This level declined with documented reasoning. Leadership attention, not failure. |

### Attention signals — what the chips mean

Every in-flight cascade carries exactly one attention signal, recomputed on every action. It drives the chip on the dashboard card and the ordering of the grid.

| Signal | Wire value | Tone | Meaning |
|---|---|---|---|
| Decline pending | `decline-pending` | Red accent (leader attention) | The bottom of the chain is a decline that nobody has answered yet. The level above the decliner owes a response, and the originator may resolve by accepting the decline. |
| Escalating | `escalating` | Blue (informational) | The critique has been passed down and the bottom owner is still looking — the chain is working as designed, response outstanding. |
| Absorbed | `absorbed` | Green | Someone took the work. The cascade awaits the originator's review and resolution. |
| Resolved — looks good | `resolved-looks-good` | Green | Terminal; never shown in the in-flight grid, but the record remains readable. |
| Resolved — declined | `resolved-declined` | Neutral | Terminal; same as above. |

**Overdue** is *not* a signal — it is a separate flag that overlays any in-flight signal as an additional amber "Overdue" chip when a response deadline has passed (see [Deadlines and overdue notifications](#deadlines-and-overdue-notifications)).

For operators, `GET /api/v1/cascades/attention?domainId=<id>` returns the same information as a worklist of *whose action is demanded right now* — one item per outstanding obligation, e.g. "Decline pending — Curasync HIM Director declined at L2" (originator's item) and "Respond to decline — … response owned at L1" (the response owner's item). `GET /api/v1/cascades/stats?domainId=<id>` returns the counts by state and signal.

### The cascade detail page

The detail page shows, top to bottom:

1. **Header** — an eyebrow naming the aggregate state ("In-flight cascade", "Resolved cascade — declined"), the original critique as the headline, the originator and issue date, and the **acceptance criteria** in a side panel. These never change after issuance.
2. **Level-by-level transparency** — one card per level, in chain order: owner, date, state pill, and that level's text — the refined critique it passed down, the decline reasoning it passed up, an "absorbed the work" note, or the awaiting-response beat for a still-looking owner.
3. **Action timeline** — the permanent action log, oldest first: sequence number, verb, level, actor, date, routing ("→ Curasync HIM Director"), and the text recorded with the action. This includes history the level cards alone cannot show — for example, a declined round that was later recascaded. This section appears when the page is backed by the live engine.
4. **Cascade summary** — a one-line footer: where the chain currently rests, or the terminal resolution.

There is deliberately no edit affordance anywhere on this page. The record is append-only.

### Originator resolution — closing a cascade

Only the originator can close a cascade, and only with a written resolution note. There are exactly two resolutions:

- **Looks good** — the originator is satisfied with the outcome (typically after an absorb, or when the chain's answer met the acceptance criteria). Note that acceptance criteria can pre-authorize a documented decline as a satisfactory answer — in that case the originator may still close as "looks good."
- **Accepted decline** — there is a pending decline at the bottom of the chain, and the originator formally accepts it, citing the specific decline being accepted. The cascade closes as resolved-declined, and the accepted decline's reasoning becomes part of the permanent resolution record.

Either way, every level owner in the chain is notified of the resolution.

### Deadlines and overdue notifications

Each cascade carries a response SLA, set at issuance:

| Setting | Default | Meaning |
|---|---|---|
| Response SLA | **7 days** per level | Each newly activated level owner has this many days to act before the level is flagged overdue. Set per cascade at issuance; the engagement default applies when unset. |
| Re-notification interval | 3 days | After the deadline passes, reminders repeat at this interval. |
| Re-notification limit | 5 | Reminders stop after this many windows. |
| SLA = 0 | — | Disables deadlines for that cascade entirely (no deadline, never overdue). The demo data uses this so the demo stays deterministic. |

An hourly background sweep checks deadlines. Two important guarantees:

- **The sweep never changes state.** Going overdue never auto-advances, auto-reassigns, or auto-declines anything. It only sets the overdue flag and writes notifications. The flag clears as soon as the level acts.
- The same applies to a decline that sits unanswered past the SLA — the originator and the response owner are reminded ("decline overdue"), but nothing moves without a human verb.

Overdue and other cascade notifications (issued, level activated, cascaded, absorbed, declined, decline-pending, sibling-decline advisories, resolution) are written to a per-recipient feed, readable at `GET /api/v1/cascades/notifications?recipientId=<memberId>` with a mark-read endpoint. **There is no notification bell in the web UI yet** — today the feed is an API/operator surface, and attention reaches leaders through the dashboard signal chips and ordering. Email/webhook delivery is not built.

### Authoring a cascade — what works today

**Leaders cannot author cascades in the UI yet.** There is no "issue a cascade" button anywhere in the product — the authoring experience (entry points, routing suggestions, AI-assisted drafting and preview) is awaiting a dedicated design session and will not be built ahead of it. Acting on levels (cascade/absorb/decline/recascade/resolve) also has no UI affordances yet; the detail surfaces are read-only.

**Operators can author and drive cascades through the REST write API** on behalf of the engagement. The flow is draft-then-issue:

1. `POST /api/v1/cascade-drafts` — create a draft with the domain, authoring mode, critique text, acceptance criteria, and optionally an SLA in days.
2. `PATCH /api/v1/cascade-drafts/{id}` — refine the draft while it is open.
3. *(Optional)* `POST /api/v1/cascade-drafts/{id}/suggest` — request an AI-drafted critique/criteria suggestion. This requires the orchestrator integration to be enabled on the instance; when it is disabled (the default), this endpoint returns 404 and everything else works unchanged. A suggestion never auto-issues — a human promotes the text into the draft.
4. `POST /api/v1/cascade-drafts/{id}/issue` — issue the cascade, naming the level-1 owner. This creates the live cascade, freezes the critique and criteria, starts the level-1 clock, and notifies the owner. (`POST .../discard` abandons a draft instead.)

Acting on a live cascade uses one endpoint per verb: `POST /api/v1/cascades/{id}/levels/{depth}/cascade | absorb | decline | recascade`, and `POST /api/v1/cascades/{id}/resolve` for the originator. Every write body requires an `idempotencyKey` (safe retries — replaying the same key returns the original result without duplicating the action) and accepts an optional `expectedChainSeq` fencing token. Actor identity is taken from the persona headers (`X-Persona-Id`, `X-Persona-Label`); list endpoints require `domainId`.

Operator-relevant responses:

| HTTP / code | Meaning |
|---|---|
| 200 on `decline` and `resolve (declined)` | By design. A legitimate decline is a success response, always. |
| 409 `CASCADE_STALE_STATE` | Someone else acted first; the response body carries the current cascade state. Refresh and re-evaluate — this is "the chain moved on," not a fault. |
| 409 `CASCADE_ILLEGAL_TRANSITION` | The verb is not legal from the current state (e.g., acting on a non-frontier level, acting twice). |
| 409 `CASCADE_TERMINAL` | The cascade is already resolved; nothing further is accepted. |
| 403 `CASCADE_NOT_LEVEL_OWNER` / `CASCADE_NOT_ORIGINATOR` | The acting identity does not own this level / did not originate this cascade. |

---

## Part 2 — Coordination contracts

### What a coordination contract is

A coordination contract is a structured agreement between named **parties** — subsidiaries, corporate org units, a combined group of subsidiaries, or an external party — that makes cross-portfolio cooperation explicit and monitorable. Two contract types exist today: **cross-portfolio opportunity** (several subsidiaries coordinate on a shared initiative) and **federation invitation** (one subsidiary invites another to share a capability, such as an AI agent).

Each contract carries:

- **Parties and commitments** — each party's concrete obligations, in plain language, some with a due date or a reporting cadence (monthly/quarterly).
- **Decline triggers** — the pre-agreed conditions under which a party may walk away *without breach*. The right of refusal is scoped in advance, in writing.
- **Enforcement text** — a plain-language description of how compliance is monitored, plus the machine-oriented mechanism text shown verbatim in a monospace panel.
- **Monitors** — the automated checks compiled from the enforcement terms (see below).

### Where you see contracts

- **Contract detail page** (`/portfolio/contract/<key>`) — parties with their commitments, the enforcement section (monitoring description, mechanism panel, decline triggers), the estimated value when one exists, and the actions footer. Entry points: the opportunity cards on the Operating Partner dashboard and the federation-invitation band on a Subsidiary COO view. Screenshot: `../acceptance/final/07-contract-detail.png`.
- **Operating Partner dashboard** — the coordination-signals strip includes a live "Coordination signals" count (contract events plus cascade actions over the trailing 7 days).

Two honest limitations of the contract detail page today:

1. **The Review proposal and Decline buttons are intentionally inert.** They render (disabled, with an explanatory tooltip) to show that review and decline are first-class actions on every contract, but the product flows behind them are deliberately unbuilt pending a design session. The footer says so on the page itself. The underlying respond/decline operations work — via the operator API and MCP tools (below).
2. **The page does not yet display lifecycle status, monitor outcomes, breaches, or attestations.** A contract that is ACTIVE with an open breach renders the same terms-first view as a proposed one ("Proposed Apr 1 2026 …"). That operational state is fully available through the read API and signals endpoint, which is where operators watch it today.

### Contract lifecycle

| Status | Meaning |
|---|---|
| `PROPOSED` | Drafted and awaiting party responses. Contract content (parties, commitments, triggers, value, enforcement text) is frozen the moment the first party responds. |
| `ACTIVE` | Every required party accepted. Monitors arm and begin sweeping; enforcement guards (if any) go live. |
| `DECLINED` | Terminal — and **legitimate**. Reached either when any required party declines at the proposal stage (reasoning required), or when a party invokes a pre-agreed decline trigger on an active contract. Monitors suspend and guards deactivate immediately. Never error-coded, never colored as a breach. |
| `WITHDRAWN`, `CONCLUDED` | Reserved in the data model; **unreachable today** — no flow produces them yet. |

Party responses are one-per-party and immutable once recorded. Open breaches never block a decline — they survive it and keep their human lifecycle (you can still acknowledge, remediate, or waive a breach on a declined contract).

### Monitors — how the substrate watches compliance

Each monitor is an automated check compiled from the contract's enforcement terms, running on a cadence (a background sweep claims due monitors roughly every 60 seconds; each monitor then waits for its next cadence boundary). Monitor types:

| Rule type | What it checks |
|---|---|
| `ATTESTATION_CADENCE` | Did the responsible party attest for the most recent closed period (e.g., the monthly progress report), within the grace window (e.g., 7 days)? |
| `METRIC_THRESHOLD` | Compares reported metric values across parties against a threshold (e.g., utilization imbalance > 30%). **Requires data from all required parties for the period — it never computes on partial data.** |
| `MCP_PROBE` | Liveness only: is the party's registered agent endpoint reachable? Never produces a breach. |
| `UNSTRUCTURED` | The enforcement term could not be compiled into a rule. Every tick asks for human verification rather than pretending to check. |

Monitor status is `ACTIVE` or `SUSPENDED` (all monitors suspend when a contract is declined; they are only armed while the contract is active). Each run records a permanent, bounded trace of what it evaluated. Run outcomes:

| Outcome | Meaning |
|---|---|
| `COMPLIANT` | The period checked out. |
| `NOT_DUE` | The period (plus grace) has not closed yet — nothing to judge. |
| `BREACH_DETECTED` | A real, evidenced miss — e.g., no attestation past the grace deadline, or a threshold exceeded on complete data. |
| `INDETERMINATE` | **The monitor could not observe enough to judge — and the system refuses to guess.** Missing metric samples, an unreachable endpoint, or an uncompilable rule produce a *needs-input* signal (data is stale / adherence unobservable / human verification due) — never a fabricated breach, and never a fabricated green. |
| `ERROR` | The evaluator itself failed. An administrative signal; breach state is untouched. |

Operators can force a check anytime with `POST /api/v1/contracts/{idOrKey}/monitors/{monitorId}/run-now` and read history with `GET .../monitor-runs`.

### Attestations

For cadence-bearing commitments, the responsible party files a periodic **attestation** — a dated, attributed compliance claim, optionally with a statement and evidence references. One attestation per commitment per period (resubmitting updates it; every submission is kept in the event history).

| Claim | Effect |
|---|---|
| `ON_TRACK` / `COMPLETE` | Commitment is compliant for the period. |
| `AT_RISK` | Commitment flagged at-risk — a visible signal, not a breach. |
| `MISSED` | An honest miss — opens a breach immediately, attributed to the attestation. |

Operators and party agents file via `POST /api/v1/commitments/{commitmentId}/attest` (or the `attest_commitment` MCP tool). Period keys are calendar-based: `2026-05` for monthly, `2026-Q2` for quarterly. There is no attestation form in the web UI yet.

**Commitment compliance states** (per commitment, shown in API detail responses):

| State | Meaning |
|---|---|
| `UNEVALUATED` | No monitor or attestation has judged it yet (also the resting state for commitments without cadence/monitors). |
| `COMPLIANT` | Last evaluation passed. |
| `AT_RISK` | Flagged by an at-risk attestation. |
| `BREACHED` | An open breach references this commitment. |
| `WAIVED` | Its breach was waived by a human. |

### Breaches and the human verbs

A breach is opened by a monitor or by a `MISSED` attestation — never by a hunch. Its lifecycle is forward-only and fully attributed:

| Status | Meaning |
|---|---|
| `DETECTED` | Opened with evidence (which monitor, which commitment, which period). At most one open breach exists per source and period — a re-detection refreshes the existing breach rather than piling on duplicates. |
| `SURFACED` | Published to signals/dashboards (same sweep today). |
| `ACKNOWLEDGED` | A named human has seen it: `POST /api/v1/breaches/{id}/acknowledge`. |
| `REMEDIATED` | Closed with **evidence references required**: `POST /api/v1/breaches/{id}/remediate`. Returns the commitment to compliant. |
| `WAIVED` | Closed by **human judgment with a mandatory written rationale**: `POST /api/v1/breaches/{id}/waive`. **Waiving is human-only — agents are denied by a built-in, non-removable rule.** |

A later monitor pass over an open breach records "compliance restored" in the event history but does **not** auto-close the breach — a human confirms remediation, always.

### Decline triggers and DECLINE_ELIGIBLE

Most decline triggers are pure human-judgment grounds — always invocable, never "armed." Some carry a machine-evaluable predicate (e.g., *agent accuracy below 90%*, fed by reported metrics). The sweep evaluates these and flips the trigger's standing:

- **Dormant** — the condition does not currently hold.
- **Armed** — the condition holds *right now*. The contract surfaces a `DECLINE_ELIGIBLE` signal: a subtle leader-attention accent meaning "a pre-agreed exit condition is live; a party could legitimately invoke it." **Arming is not a breach and forces nothing** — invoking the decline remains a human decision.

Invoking a decline on an active contract (`POST /api/v1/contracts/{idOrKey}/decline`, or the `invoke_decline` MCP tool) requires citing one of the contract's own triggers and providing written reasoning. It returns success (HTTP 200) with the contract in its declined terminal state — the substrate's vocabulary holds even here.

### The enforcement ladder — off, shadow, enforce

Contracts can carry **guards**: rules that watch governed write actions elsewhere in the platform (for example, promoting an agent) and may flag or block them while the contract is active. How much teeth the guards have is an instance-level setting:

| Mode | Plain meaning |
|---|---|
| `off` | The gateway is skipped entirely (a counter still ticks). Nothing is evaluated. |
| `shadow` (**the default, and the live instance's current mode**) | Every governed action is fully evaluated and the verdict is *recorded* — a would-be block is logged as `WOULD_DENY` — but **nothing is actually blocked**. This is recorded-intent mode: you can audit exactly what enforcement *would have done* before turning it on. |
| `enforce` | A matching deny-guard actually blocks the action. |

When a block happens in enforce mode, the caller receives **HTTP 409 with error code `CONTRACT_DENIAL`**, naming the contract and clause that blocked the action and a decision id for the audit trail. It is a governance refusal — "an active contract forbids this right now" — not a system failure; agents receive the same information as a structured tool error so they can adapt. The same ladder is what stands behind agent pause/kill controls elsewhere in the product — those surfaces disclose whether a control is recorded-intent or an actual gateway block (see the Agents section of this manual).

Every gateway verdict uses this vocabulary (readable per contract at `GET /api/v1/contracts/{idOrKey}/decisions`):

| Decision | Meaning |
|---|---|
| `ALLOW` | No guard matched; the action proceeded. |
| `ALLOW_WITH_FLAG` | The action proceeded but something was noted for review (e.g., a guard flagged it, or a predicate could not be evaluated — flagged, never silently skipped). |
| `WOULD_DENY` | Shadow mode: this would have been blocked in enforce mode. The action proceeded. |
| `DENY` | Enforce mode: the action was blocked (the 409 above). |

If the gateway itself errors, it fails safe by principal: a human's action proceeds flagged; an agent's or service's action is denied.

Honest demo note: **both demo contracts compile to zero blocking guards**, so on the demo instance you will see monitors, breaches, and signals in action, but no denial will ever fire regardless of mode.

### Signals — the contract heartbeat for a domain

`GET /api/v1/contracts/signals?domainId=<id>` is the operator's one-call summary, computed live from the records (never cached or stamped):

| Field | Meaning |
|---|---|
| `pendingFederationInvitations` | Proposed federation invitations awaiting this domain's response. |
| `openBreaches` | Open breach count (with a by-severity breakdown when severities are set). |
| `declineEligible` | Currently armed decline triggers, by contract. |
| `needsInput` | Monitors that could not observe (stale data / unobservable endpoint / human verification due) — the honest "we don't know" list. |
| `monitorCoverage` | How many monitors are structured checks versus unstructured human-verify placeholders — a rubber-stamp visibility ratio worth watching. |
| `coordinationPatternsThisWeek` | Contract events plus cascade actions in the trailing 7 days (with a breakdown); feeds the dashboard's "Coordination signals" card. |
| `reviewBoundariesDue` | Contracts with a configured review window currently open (empty unless boundaries are configured). |

### What is not built yet (contracts)

- **No contract authoring over the API or UI.** The REST surface has no create/edit endpoint for contracts today; the demo contracts are installed by the engagement seed. Authoring exists at the internal service layer and ships with a later phase.
- **Review/Decline buttons inert in the UI** (see above) — respond/decline work via the operator API (`POST /api/v1/contracts/{idOrKey}/respond`, `.../decline`) and MCP tools only.
- **The contract page does not show live operational state** (status, monitors, breaches, attestations) — API only for now.
- Review boundaries (time-windowed decline rights on federation contracts) are recorded but unenforced unless explicitly configured; when unconfigured, a decline simply proceeds with a note in the decision log.
- Notification channels (email/webhook) are not built; signals and the dashboard are the delivery surfaces.

---

### Further reading

Operator-level state machine and API detail: `substrate/docs/engine-spec/cascade.md` and `substrate/docs/engine-spec/contracts.md`. Verified live walkthrough evidence with screenshots: `substrate/docs/acceptance/final/visual-verification.md`.
