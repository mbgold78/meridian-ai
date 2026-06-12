---
status: blocked-on-ui
verb: a notification feed for attention events
spec: substrate/docs/pipeline/specs/coo-notification-feed.yaml (status: blocked)
reserved-selectors: notification-feed, notification-item
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Notification feed — not yet in the UI

**What this will be.** A feed in the portfolio chrome that pushes attention
events to you as they happen: a level declined your cascade, a contract
breach was detected, an agent entered intervention.

**What exists today.** The *pull* side of exactly those events: the
dashboard computes them live on every read, and its ordering is the
notification logic — decline-pending cascades first, signal cards counting
agents in intervention and coordination signals
([task 1](../01-dashboard.md) teaches that reading habit). Until the feed
ships, the dashboard scan is the feed.

**When it ships.** The feature slice decides the feed's home in the chrome,
claims the reserved names, the capture spec flips to `active`, and this stub
becomes the real task.
