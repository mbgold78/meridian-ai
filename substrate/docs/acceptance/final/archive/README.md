# Archived hand-captured originals (pre-pipeline)

**Provenance.** These 9 PNGs are the hand-captured screenshots from the final
visual-verification tour of 2026-06-11 (build `3ec4e87..15d368d`, viewport
1536x960, Playwright/Chromium driven manually). The boot used
`--substrate.demo.seed=true --substrate.demo.contracts.activate=true` but **no
fixed engine clock** — wall-clock was 2026-06-11. The tour notes that cite
them live one directory up: [`../visual-verification.md`](../visual-verification.md).

**Why archived (DOC-1 S3, plan §8 A3).** As of DOC-1 these scenes are
regenerated deterministically by the docs capture pipeline
(`gradlew docsCapture` / `docsVerify`, specs in
`substrate/docs/pipeline/specs/`) under the canonical capture posture — dev
profile, seeded demo pack, activated contracts, pinned engine clock
`substrate.demo.fixed-clock=2026-05-12T00:00:00Z`. The regenerated PNGs are
**build artifacts** at `substrate/docs/pipeline/build/captures/` and are never
checked in; the manual references those paths. Because the originals were not
clock-pinned, pixel parity with the regenerated set is expected to fail by
design — equivalence is scene-level, recorded in
`substrate/docs/acceptance/doc-1/s3-migration/scene-equivalence.md`.

**The 08a special case (plan §7 FILE-6).** `08a-operations-path-segment-BLANK.png`
documented Finding 1 of the tour: an unmatched SPA deep link
(`/portfolio/subsidiary/curasync-clinical/operations`) rendered a fully blank
page. That bug has since been fixed (catch-all NotFound route, `App.tsx`), so
the BLANK scene is **irreproducible** and is archived without a pipeline
equivalent. The pipeline instead captures the now-fixed NotFound panel at the
same guessable URL as `../../../pipeline/build/captures/08a-operations-path-segment-notfound.png`.

Do not edit or replace files in this directory; they are historical evidence.
