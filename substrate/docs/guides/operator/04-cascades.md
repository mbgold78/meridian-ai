# Task 4 — Author and issue a cascade via the API, observe it in the UI

What this task teaches: the operator-grade authoring flow (leaders cannot
author cascades in the UI yet — there is deliberately no button), the
draft-then-issue discipline, acting on a level as its owner, and where the
result shows up on the dashboard. Committed evidence:
`substrate/docs/acceptance/doc-1/s4-operator/transcript-4-cascade.txt`
(every block below is quoted from it).

## Prerequisites

The seeded demo boot from the index page. Resolve the demo domain id once —
ids regenerate every boot, so never hardcode them:

```bash
DOMAIN_ID=$(curl -s $BASE/domains | py -3 -c "
import json,sys
print([d['id'] for d in json.load(sys.stdin)['data'] if d['name']=='Operational Risk'][0])")
```

## Steps

1. Create an **open draft** — domain, authoring mode, the critique, and the
   acceptance criteria (including, where appropriate, the conditions under
   which a documented decline is acceptable). `responseSlaDays: 0` disables
   deadlines for this cascade:

   ```bash
   curl -s -X POST -H "$H1" -H "$H2" -H "$H3" "$BASE/cascade-drafts" -d "{
     \"domainId\":\"$DOMAIN_ID\",\"authoringMode\":\"free-form\",
     \"originalCritique\":\"Manual eligibility re-checks are duplicated across intake and billing. Consolidate to one owner per claim before Q3.\",
     \"acceptanceCriteria\":\"A single named owner per claim; duplicate re-checks retired; a documented decline citing regulatory constraints is acceptable.\",
     \"summary\":\"Consolidate duplicated eligibility re-checks\",
     \"responseSlaDays\":0}"
   ```

   ```text
   201 → "status": "open", "issuedCascadeId": null, "authorLabel": "Operating Partner"
   ```

2. Refine while it is open (absent fields untouched; PATCH after issue is a
   409 — drafts freeze on promotion):

   ```bash
   curl -s -X PATCH -H "$H1" -H "$H2" -H "$H3" "$BASE/cascade-drafts/$DRAFT_ID" \
     -d '{"originalCritique":"Manual eligibility re-checks are duplicated across intake and billing. Consolidate to one accountable owner per claim before Q3 close."}'
   ```

   *(Optional)* `POST /cascade-drafts/{id}/suggest` asks the orchestrator for
   an AI-drafted critique/criteria suggestion. With the orchestrator
   integration disabled — the default — the route does not exist (404) and
   everything else here works unchanged. A suggestion never auto-issues.

3. **Issue** — name the level-1 owner. This creates the live cascade,
   freezes the critique and criteria, and starts the level-1 clock:

   ```bash
   curl -s -X POST -H "$H1" -H "$H2" -H "$H3" "$BASE/cascade-drafts/$DRAFT_ID/issue" \
     -d "{\"idempotencyKey\":\"$(uuidgen)\",\"level1OwnerLabel\":\"Curasync COO\"}"
   ```

   ```text
   currentState   : in-flight | attentionSignal: escalating
   originatedBy   : Operating Partner | originatedAt: 2026-05-12T00:00:00Z
   frontier       : L 1 looking - Curasync COO
   levels         : [(0, 'Operating Partner', 'cascaded'), (1, 'Curasync COO', 'looking')]
   ```

   Re-issuing with the **same** `idempotencyKey` returns HTTP 200 with the
   same cascade — issuance is idempotent on the draft row, no duplicates.

4. Act on the level **as its owner** — level verbs belong to the current
   owner of the acted depth, and actor identity rides the persona headers.
   Here L1 passes the work down with a refined critique:

   ```bash
   curl -s -X POST -H "$H1" -H 'X-Persona-Id: curasync-coo' -H 'X-Persona-Label: Curasync COO' \
     "$BASE/cascades/$CASCADE_ID/levels/1/cascade" -d "{
     \"idempotencyKey\":\"$(uuidgen)\",
     \"refinedCritique\":\"HIM owns the consolidation: produce the single-owner-per-claim model and retire the billing-side re-check.\",
     \"nextOwnerLabel\":\"Curasync HIM Director\"}"
   ```

   ```text
   currentState   : in-flight | attentionSignal: escalating | frontierDepth: 2
   levels         : [(0, 'Operating Partner', 'cascaded'), (1, 'Curasync COO', 'cascaded'), (2, 'Curasync HIM Director', 'looking')]
   ```

   The other level verbs use the same shape: `…/levels/{depth}/absorb`
   (take the work; chain stops), `…/decline` (documented reasoning required;
   returns 200 — a decline is never an error), `…/recascade` (answer a
   decline by sending it back down reframed), and `POST /cascades/{id}/resolve`
   for the originator. Acting with the wrong identity is 403
   `CASCADE_NOT_LEVEL_OWNER`; acting out of turn is 409
   `CASCADE_ILLEGAL_TRANSITION`.

5. Read the permanent action timeline — append-only, nothing editable:

   ```text
   GET /cascades/{id}/actions
   #1 issue    L0 Operating Partner  -> Curasync COO
   #2 cascade  L1 Curasync COO       -> Curasync HIM Director
   ```

6. Check the operator worklist and the counts:

   ```text
   GET /cascades/attention?domainId=…     one item per outstanding obligation
   level-looking      Awaiting response — Curasync HIM Director at L2     ← yours

   GET /cascades/stats?domainId=…
   "total": 4, "byState": {"in-flight": 4, …}, "bySignal": {"decline-pending": 1, "escalating": 3, "absorbed": 0}
   ```

## Observe in UI

7. Open `/portfolio/dashboard`. The **"Cascades you've issued"** grid shows
   every in-flight cascade as a card, attention-ordered — decline-pending
   first, then overdue, then escalating by recency. Your new cascade lands as
   an *Escalating* card reading "Currently at L2 · 3 level chain"; its
   **Inspect cascade** link opens the full detail (frozen critique, level
   timeline, action log). The capture below shows the three seeded cascades
   on a fresh demo boot — your card appears alongside them:

{{capture:operator-cascade-grid}}

## You'll know it worked when…

…`GET /cascades/stats` shows **`total` moved 3 → 4** with your cascade
counted under `bySignal.escalating`, the action timeline reads
`#1 issue → #2 cascade` with the actors you used, and the dashboard grid
shows one more in-flight card than the seeded three.
