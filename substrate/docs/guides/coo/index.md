# Substrate COO guide (D5)

Five reading tasks for the **subsidiary COO and the corporate operating
partner** — the executives who consume the portfolio surfaces. Everything in
these tasks works in the product today, with your mouse only: no terminal,
no API calls, nothing to install. Each task ends with a plain-language
closer naming what you should be able to say after doing it.

| # | Task | File |
|---|------|------|
| 1 | Read the portfolio dashboard — what needs you today | [01-dashboard.md](01-dashboard.md) |
| 2 | Read a cascade's timeline — including a decline | [02-cascades.md](02-cascades.md) |
| 3 | Read a coordination contract — and where its monitoring stands | [03-contracts.md](03-contracts.md) |
| 4 | Read the agent Operations tab — what is running in your company | [04-operations.md](04-operations.md) |
| 5 | Walk a finding back to its evidence | [05-finding-provenance.md](05-finding-provenance.md) |

## Conventions used by every task

**Getting there.** Your engagement team gives you the platform URL and a
sign-in. The portfolio surfaces live under **`/portfolio`**; task 1 starts
at the front door and the rest deep-link from there. Screenshots are
regenerated from the product by the docs build on every change, never
hand-captured, and were taken as the **Executive** persona — the same view
you get. (Personas change labels and the identity chip, not what data you
can see.)

**The one vocabulary rule.** A documented "no" — a declined cascade, a
declined contract — is a *legitimate, recorded governance outcome*, never an
error. Reddish accents on these surfaces mean *this needs a leader's
attention*, not *something is broken*.

**Read the banner.** The portfolio chrome carries a provenance banner that
states, on every page, which parts of what you see are live engine data and
which are illustrative. The tasks point out the live parts as they go; when
the banner and a card disagree, trust the card's own provenance note.

## Not yet in the UI

Six things an executive will eventually do *in these surfaces* are not
built yet. Each has a stub topic in [`not-yet/`](not-yet/) stating what
exists today and what to use meanwhile — and each stub is backed by a
pre-written, registry-named capture spec that the docs build skips (visibly)
until the surface ships, at which point this guide gains the real task in
the same PR:

| Not-yet verb | Stub |
|---|---|
| Respond to / decline a cascade at your level | [not-yet/cascade-respond.md](not-yet/cascade-respond.md) |
| Review / decline a contract proposal | [not-yet/contract-respond.md](not-yet/contract-respond.md) |
| See a contract's operational state & monitor history | [not-yet/contract-status.md](not-yet/contract-status.md) |
| Attest a commitment was honored | [not-yet/attestation.md](not-yet/attestation.md) |
| A notification feed for attention events | [not-yet/notification-feed.md](not-yet/notification-feed.md) |
| Pause / resume an agent from the Operations tab | [not-yet/agent-controls.md](not-yet/agent-controls.md) |
