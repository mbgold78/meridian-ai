# Task 5 — Walk a finding back to its evidence

What this task teaches: the platform's central honesty mechanism — every
finding traces to source evidence you can inspect, fingerprint and all.

This task uses the engagement workspace (the Living Canvas at **`/`**) — the
same surface your engagement team works in, read here as an executive.

## Read the finding

1. On the canvas, open the drawer's **Risks** tab (the rail on the right
   edge; the view is linkable as `/?view=risks`). Findings list
   severity-first, with count chips per severity at the top:

{{capture:coo-finding-detail}}

2. Click the top finding to expand it. The expanded body is three things:
   the **description** (what the platform observed), the **recommendation**
   (what it suggests doing about it), and **Affects** — chips naming every
   system involved. Expanding also spotlights those systems on the map
   behind the drawer, so the finding has a *place*, not just a paragraph.

3. Note the system names under **Affects** — you are about to check the
   platform's homework on them.

## Walk the source chain

4. Open the **From** tab ("Source chain", linkable as `/?view=provenance`).
   This is provenance: the evidence chain for every ingested entity in the
   workspace, grouped by source file.

{{capture:coo-provenance-walk}}

5. Find an affected system's entries. Each evidence card shows the label,
   the **source file** it came from, the **extractor** that produced it, a
   **fingerprint** (SHA-256 prefix) of the registered source document, and a
   confidence pip (green ≥ 90%, amber 70–89%, red below).

   One honesty note: in the demonstration workspace these entries carry a
   `seed` extractor chip — synthetic demo evidence, labeled as such. In your
   real engagement the chips read `ingestion` and the source files are your
   corpus.

6. That is the full walk: *finding → affected system → source document
   fingerprint*. Anything the canvas asserts can be challenged down this
   chain — and curating findings (accepting or rejecting them, which is
   itself recorded) is engagement-team work today
   ([operator task 3](../operator/03-diagnostics.md)).

## You'll know you've read it right when…

…you picked one finding and can name *the system it implicates and the
source file (with its fingerprint) behind that implication* — and the phrase
"where does this number come from?" now has a two-click answer instead of a
meeting.
