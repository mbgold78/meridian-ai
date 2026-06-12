---
status: blocked-on-ui
verb: register a probed source / trigger ingestion from Add Source
spec: substrate/docs/pipeline/specs/operator-add-source-commit.yaml (status: blocked)
reserved-selectors: add-source-commit
registry: substrate/docs/pipeline/specs/selector-registry.yaml
---

# Add Source actually adding the source — not yet built

**What this will be.** The canvas Add Source modal completing its own
promise: after probing a URL and identifying the API type, **Add to
project** registers the source and kicks off ingestion.

**What exists today.** The modal is identification-only — probe results
render (type, confidence, sample endpoints) and "Add to project" simply
closes the dialog. Real ingestion runs through the operator-side CLI
end to end, as [task 2](../02-corpus-ingest.md) drives it from its
committed transcripts.

**When it ships.** The registration PR claims the reserved name, the
capture spec flips to `active`, and task 2 gains the UI on-ramp beside the
CLI path.
