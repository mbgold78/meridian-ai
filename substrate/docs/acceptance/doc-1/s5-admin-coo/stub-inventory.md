# DOC-1 S5 — stub inventory cross-check (acceptance A6)

The plan's A6 list names **11 not-yet UI verbs**: "cascade issue+AI authoring;
cascade level verbs; contract review/decline; contract operational state;
attestation; notification feed; agent controls; finding curation;
generative-draft accept; Add Source commit; decision-form picker" — placed per
the A6 default split (six genuinely COO-facing in the COO guide; five
operator-grade in the operator guide).

Every stub has `status: blocked-on-ui` frontmatter, a pre-written
`status: blocked` capture spec (schema-validated, runner-skipped with a
visible count), and reserved selector names registered with owner notes in
`substrate/docs/pipeline/specs/selector-registry.yaml` (22 entries).

| # | A6 verb | Placement | Stub topic | Blocked spec | Reserved names |
|---|---------|-----------|------------|--------------|----------------|
| 1 | cascade level verbs | COO | `guides/coo/not-yet/cascade-respond.md` | `coo-cascade-respond` | cascade-actions, cascade-action-respond, cascade-action-decline |
| 2 | contract review/decline | COO | `guides/coo/not-yet/contract-respond.md` | `coo-contract-respond` | contract-respond-flow (Review/Decline buttons themselves shipped inert) |
| 3 | contract operational state | COO | `guides/coo/not-yet/contract-status.md` | `coo-contract-status` | contract-status-chip, contract-monitor-history (FILE-2 / UI-1) |
| 4 | attestation | COO | `guides/coo/not-yet/attestation.md` | `coo-attestation` | contract-attestation-form, contract-attestation-submit |
| 5 | notification feed | COO | `guides/coo/not-yet/notification-feed.md` | `coo-notification-feed` | notification-feed, notification-item |
| 6 | agent controls | COO | `guides/coo/not-yet/agent-controls.md` | `coo-agent-controls` | agent-controls, agent-control-pause, agent-control-resume, agent-enforcement-disclosure (FILE-3 / UI-2) |
| 7 | cascade issue + AI authoring | operator | `guides/operator/not-yet/cascade-authoring.md` | `operator-cascade-authoring` | cascade-issue-button, cascade-author-form, cascade-author-ai-draft |
| 8 | finding curation | operator | `guides/operator/not-yet/finding-curation.md` | `operator-finding-curation` | canvas-finding-curate-accept, canvas-finding-curate-reject |
| 9 | generative-draft accept | operator | `guides/operator/not-yet/draft-accept.md` | `operator-draft-accept` | draft-accept |
| 10 | Add Source commit | operator | `guides/operator/not-yet/add-source-commit.md` | `operator-add-source-commit` | add-source-commit |
| 11 | decision-form picker | operator | `guides/operator/not-yet/decision-form-picker.md` | `operator-decision-picker` | decision-affected-picker |

Inventory check: 11/11 A6 verbs covered, 6 COO + 5 operator, no extras. The
two ruling-P-1 RESERVED names from the plan's §4 table (#28
contract-monitor-history, #32 agent-enforcement-disclosure) are registered and
ride stubs #3 and #6 respectively.

Not part of the verb inventory but registered in the same contract: the
`oidc-identity-chip` spec is `status: manual-job` (ruling D-C — the manual
Compose/Keycloak lane, never the gate), referenced from the admin guide's
identity-model caveat.
