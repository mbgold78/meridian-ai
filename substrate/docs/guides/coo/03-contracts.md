# Task 3 — Read a coordination contract, and where its monitoring stands

What this task teaches: a coordination contract's terms as the platform
renders them, and — honestly — which parts of its operational state the UI
shows today versus what your engagement team reads for you.

A **coordination contract** is a documented, monitorable agreement between
parts of the portfolio — parties, commitments, and the conditions under
which a party may decline without being in breach. Not a slide-deck
proposal: the platform watches it.

## Read a contract

1. From the dashboard, click a clickable opportunity card (one promoted to a
   documented contract), or follow a **federation invitation** segment from
   your subsidiary's context band — both land on the contract detail.

2. Read the terms first-class:

{{capture:contract-detail}}

   - **Header** — contract type, title, proposal date, linked subsidiaries,
     estimated value when recorded.
   - **Context** — why this contract exists.
   - **Parties and commitments** — each party with its enumerated
     obligations. These commitments are what monitoring is *about*.
   - **Enforcement** — three panels: **Monitoring** (what the platform
     watches, in prose), **Mechanism** (the same rule machine-readable,
     verbatim), and **Decline triggers** (the documented no-fault exits).
   - **Available actions** — **Review proposal** and **Decline** render
     because review and decline are first-class concepts of the contract
     model. They are deliberately inactive in this release —
     [not-yet/contract-respond.md](not-yet/contract-respond.md).

## Where the monitor status stands — as rendered today

3. Two honest limits of this surface, so you read it correctly:

   - It does **not** yet show lifecycle state: an activated contract still
     shows only its "Proposed" date line here. The activation event *is*
     visible as a ledger row in the engagement's decision register, and your
     engagement team reads monitor runs and breach state through the
     operator tooling ([operator task 5](../operator/05-contracts.md)).
   - Consequently "is this contract currently in breach?" is a question for
     your engagement team today.
     [not-yet/contract-status.md](not-yet/contract-status.md) tracks the
     surface that will answer it here.

   What the dashboard *does* surface live, already: breach-driven
   coordination signals roll into the signal cards you read in task 1.

## You'll know you've read it right when…

…you can state, from the page alone: *who committed to what, what the
platform watches, and under which conditions a party may walk away without
breach* — and you know which question (current breach state) this page
cannot yet answer and who answers it meanwhile.
