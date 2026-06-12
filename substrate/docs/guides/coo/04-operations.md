# Task 4 — Read the agent Operations tab

What this task teaches: what is actually running in your company — the AI
agent fleet as governance records it — and how to read the status pills
without over-reading them.

## Enter your subsidiary

1. From **`/portfolio`**, choose **Subsidiary COO**, pick your company from
   the dropdown, and enter. (The captures below use the demo portfolio's
   *Curasync Clinical Systems*.) The **portfolio context band** under the
   header tells you how the parent reaches into your company — including any
   pending federation invitations, which click through to their contract.

2. Open the **Operations** tab (the active tab rides the URL, so the page
   is linkable and back/forward works).

## Read the fleet

3. Three regions, top to bottom:

{{capture:coo-operations-tab}}

   - **Fleet stat strip** — *Agents active* and *Agents in intervention*
     are **live from the agent registry**; the strip's provenance marker
     says so on the page.
   - **Recent agent activity** — the fleet, live. Each row: name, role,
     latest *observed* activity (derived from real sightings — "No activity
     observed" when there is none, never an invented line), a provenance
     line (who deployed it, when, and its human oversight chain), and a
     governance-status pill: **Operating** (green), **Shadow mode** (blue —
     running alongside humans, output not acted on), **Paused** (amber),
     **In intervention** (red — requires a human). Retired agents never
     appear. Clicking a row expands its deployment detail: the recorded
     deployment criteria ("not yet recorded" when absent — never fabricated)
     and the numbered oversight chain of accountable humans.
   - **Open findings** — severity-pilled cards with how long ago each
     surfaced.

4. Read the pills precisely: the pill is the **governance state** — what the
   registry says the agent should be doing — not runtime confirmation. And
   this tab is **read-only** today: pausing or resuming an agent is done by
   your engagement team ([operator task
   6](../operator/06-agent-lifecycle.md));
   [not-yet/agent-controls.md](not-yet/agent-controls.md) tracks the
   controls landing here.

## You'll know you've read it right when…

…you can say *how many agents operate in your company, which one most
recently did something observable, who is accountable for it* (read its
oversight chain aloud) — and you catch yourself correctly saying "the
registry says it's operating" rather than "it's running fine".
